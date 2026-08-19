//! A hermetic pair of ends for the transport tests.
//!
//! Everything binds `127.0.0.1` and uses `presets::Minimal`, so no relay, no
//! discovery and no DNS is reached for and the whole thing runs inside the Nix
//! build sandbox. Two findings from the S0 spike are load-bearing here:
//! endpoints left on `0.0.0.0` make the path selector churn between local
//! interfaces, and an `Endpoint` dropped while a response is still in flight
//! tears the connection down under it.

#![allow(dead_code)]

use std::{
    net::{Ipv4Addr, SocketAddr},
    sync::Arc,
    time::Duration,
};

use iroh::{endpoint::presets, Endpoint, EndpointAddr};
use iroh_webview_proxy::{pipe::forward_bidi, proxy, ALPN, HANDSHAKE};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};
use tokio_util::sync::CancellationToken;

/// Bind an endpoint the way the sandbox requires.
async fn local_endpoint(alpns: Vec<Vec<u8>>) -> Endpoint {
    Endpoint::builder(presets::Minimal)
        .alpns(alpns)
        .bind_addr(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
        .expect("bind_addr")
        .bind()
        .await
        .expect("bind endpoint")
}

fn loopback_addr(endpoint: &Endpoint) -> EndpointAddr {
    let port = endpoint
        .bound_sockets()
        .iter()
        .find_map(|s| match s {
            SocketAddr::V4(v4) => Some(v4.port()),
            _ => None,
        })
        .expect("an ipv4 bound socket");
    EndpointAddr::new(endpoint.id()).with_ip_addr(SocketAddr::from((Ipv4Addr::LOCALHOST, port)))
}

/// The far side: an in-test `iroh-uds-listen`, forwarding each incoming stream
/// to a fresh TCP connection to `upstream`.
pub struct FarSide {
    endpoint: Endpoint,
    pub addr: EndpointAddr,
    shutdown: CancellationToken,
}

impl FarSide {
    pub async fn forwarding_to(upstream: SocketAddr) -> Self {
        let endpoint = local_endpoint(vec![ALPN.to_vec()]).await;
        let addr = loopback_addr(&endpoint);
        let shutdown = CancellationToken::new();

        let accepting = endpoint.clone();
        let token = shutdown.clone();
        tokio::spawn(async move {
            loop {
                let incoming = tokio::select! {
                    incoming = accepting.accept() => incoming,
                    _ = token.cancelled() => break,
                };
                let Some(incoming) = incoming else { break };
                tokio::spawn(async move {
                    let Ok(conn) = incoming.await else { return };
                    let Ok((send, mut recv)) = conn.accept_bi().await else {
                        return;
                    };
                    let mut handshake = [0u8; HANDSHAKE.len()];
                    if recv.read_exact(&mut handshake).await.is_err() || handshake != HANDSHAKE {
                        return;
                    }
                    let Ok(tcp) = TcpStream::connect(upstream).await else {
                        return;
                    };
                    let (read, write) = tcp.into_split();
                    let _ = forward_bidi(read, write, recv, send).await;
                    drop(conn);
                });
            }
        });

        Self {
            endpoint,
            addr,
            shutdown,
        }
    }

    /// An endpoint that nothing is listening on, for the unreachable-peer tests.
    pub async fn absent() -> EndpointAddr {
        let endpoint = local_endpoint(vec![ALPN.to_vec()]).await;
        let addr = loopback_addr(&endpoint);
        // Closed, so the address is well-formed but nothing answers it.
        endpoint.close().await;
        addr
    }
}

impl Drop for FarSide {
    fn drop(&mut self) {
        self.shutdown.cancel();
    }
}

/// The near side: the proxy under test.
pub struct NearSide {
    endpoint: Endpoint,
    pub port: u16,
    shutdown: CancellationToken,
}

impl NearSide {
    pub async fn dialing(addr: EndpointAddr) -> Self {
        Self::dialing_with_timeout(addr, proxy::DIAL_TIMEOUT).await
    }

    /// A short dial timeout keeps the unreachable-endpoint tests quick; the
    /// production value is [`proxy::DIAL_TIMEOUT`].
    pub async fn dialing_with_timeout(addr: EndpointAddr, dial_timeout: Duration) -> Self {
        let endpoint = local_endpoint(vec![]).await;
        // Any free port: these tests dial by number, and a fixed one would
        // collide between tests running in parallel.
        let listener = proxy::bind_loopback(0).await.expect("bind loopback");
        let port = listener.local_addr().expect("local_addr").port();
        let shutdown = CancellationToken::new();

        tokio::spawn(proxy::serve(
            listener,
            endpoint.clone(),
            addr,
            dial_timeout,
            shutdown.clone(),
        ));

        Self {
            endpoint,
            port,
            shutdown,
        }
    }

    pub fn url(&self, path: &str) -> String {
        format!("http://127.0.0.1:{}{path}", self.port)
    }

    /// Open a connection to the proxy, as the WebView would.
    pub async fn connect(&self) -> TcpStream {
        TcpStream::connect(SocketAddr::from((Ipv4Addr::LOCALHOST, self.port)))
            .await
            .expect("connect to proxy")
    }
}

impl Drop for NearSide {
    fn drop(&mut self) {
        self.shutdown.cancel();
    }
}

/// A minimal HTTP/1.1 origin server, standing in for whatever sits behind the
/// socket on the far side.
///
/// Hand-rolled rather than pulled from a crate so the tests can send responses
/// that a well-behaved server library would not — chunked bodies split at
/// awkward places, a body larger than any buffer, a stall in the middle of a
/// response.
pub struct Origin {
    pub addr: SocketAddr,
    pub requests: Arc<tokio::sync::Mutex<Vec<String>>>,
}

impl Origin {
    /// Serve `respond(request_line_and_headers, request_count)` for each request,
    /// handling keep-alive so several requests may share a connection.
    pub async fn serving<F>(respond: F) -> Self
    where
        F: Fn(&str, usize) -> Vec<u8> + Send + Sync + 'static,
    {
        let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
            .await
            .expect("bind origin");
        let addr = listener.local_addr().expect("local_addr");
        let requests = Arc::new(tokio::sync::Mutex::new(Vec::new()));

        let respond = Arc::new(respond);
        let seen = requests.clone();
        tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else {
                    break;
                };
                let respond = respond.clone();
                let seen = seen.clone();
                tokio::spawn(async move {
                    let mut buffered = Vec::new();
                    let mut chunk = [0u8; 4096];
                    loop {
                        // Read until the end of a request head, then answer it.
                        while !contains_head(&buffered) {
                            match stream.read(&mut chunk).await {
                                Ok(0) | Err(_) => return,
                                Ok(n) => buffered.extend_from_slice(&chunk[..n]),
                            }
                        }
                        let split = head_end(&buffered).expect("a complete head");
                        let head = String::from_utf8_lossy(&buffered[..split]).to_string();
                        buffered.drain(..split);

                        let count = {
                            let mut seen = seen.lock().await;
                            seen.push(head.clone());
                            seen.len()
                        };
                        if stream.write_all(&respond(&head, count)).await.is_err() {
                            return;
                        }
                    }
                });
            }
        });

        Self { addr, requests }
    }

    pub async fn request_count(&self) -> usize {
        self.requests.lock().await.len()
    }

    pub async fn request_heads(&self) -> Vec<String> {
        self.requests.lock().await.clone()
    }
}

fn head_end(buffer: &[u8]) -> Option<usize> {
    buffer
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|at| at + 4)
}

fn contains_head(buffer: &[u8]) -> bool {
    head_end(buffer).is_some()
}

/// Write a request to the proxy and read until the connection closes.
pub async fn round_trip(stream: &mut TcpStream, request: &[u8]) -> Vec<u8> {
    stream.write_all(request).await.expect("write request");
    let mut response = Vec::new();
    stream
        .read_to_end(&mut response)
        .await
        .expect("read response");
    response
}

/// Write a request and read exactly one response, leaving the connection open.
pub async fn exchange(stream: &mut TcpStream, request: &[u8]) -> Vec<u8> {
    stream.write_all(request).await.expect("write request");
    read_one_response(stream).await
}

/// Read one complete HTTP/1.1 response, honouring `Content-Length` and chunked
/// framing so the connection is left positioned for the next one.
pub async fn read_one_response(stream: &mut TcpStream) -> Vec<u8> {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 4096];

    while !contains_head(&buffer) {
        let n = stream.read(&mut chunk).await.expect("read head");
        assert_ne!(n, 0, "connection closed mid-head");
        buffer.extend_from_slice(&chunk[..n]);
    }
    let head_len = head_end(&buffer).expect("a complete head");
    let head = String::from_utf8_lossy(&buffer[..head_len]).to_lowercase();

    let want = if let Some(len) = header_value(&head, "content-length") {
        head_len + len.trim().parse::<usize>().expect("a length")
    } else if head.contains("transfer-encoding: chunked") {
        loop {
            if let Some(end) = chunked_end(&buffer[head_len..]) {
                break head_len + end;
            }
            let n = stream.read(&mut chunk).await.expect("read chunked body");
            assert_ne!(n, 0, "connection closed mid-body");
            buffer.extend_from_slice(&chunk[..n]);
        }
    } else {
        head_len
    };

    while buffer.len() < want {
        let n = stream.read(&mut chunk).await.expect("read body");
        assert_ne!(n, 0, "connection closed mid-body");
        buffer.extend_from_slice(&chunk[..n]);
    }
    buffer.truncate(want);
    buffer
}

fn header_value(lowercased_head: &str, name: &str) -> Option<String> {
    lowercased_head
        .lines()
        .find_map(|line| line.strip_prefix(&format!("{name}:")))
        .map(|value| value.trim().to_string())
}

/// The offset just past a terminating `0\r\n\r\n`, if the body is complete.
fn chunked_end(body: &[u8]) -> Option<usize> {
    body.windows(5)
        .position(|w| w == b"0\r\n\r\n")
        .map(|at| at + 5)
}
