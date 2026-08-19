//! The loopback listener.
//!
//! One accepted TCP connection becomes one iroh connection carrying one bi
//! stream, which is exactly what `iroh-uds-listen` on the far side expects: it
//! does a single `accept_bi`, reads the handshake, and hands the stream to a
//! fresh connection to the socket behind it.
//!
//! Connection-per-connection rather than stream-per-request is what makes the
//! proxy byte-transparent. HTTP/1.1 keep-alive, pipelining, chunked bodies and
//! `Range` all happen inside a stream we never inspect.

use std::{net::SocketAddr, time::Duration};

use iroh::{Endpoint, EndpointAddr};
use tokio::{io::AsyncWriteExt, net::TcpListener};
use tokio_util::sync::CancellationToken;

use crate::{gateway, pipe::forward_bidi, ALPN, HANDSHAKE};

/// How long one dial may take before the browser is told the endpoint is
/// unreachable.
///
/// Short enough that a page load fails visibly rather than spinning, long
/// enough to cover a relay-mediated connection to a peer that is awake. A
/// parameter rather than a hard constant so the unreachable-endpoint tests do
/// not have to spend it.
pub const DIAL_TIMEOUT: Duration = Duration::from_secs(20);

/// Accept connections until `shutdown` is cancelled.
///
/// Every accepted connection is handled on its own task, so a slow dial to the
/// far side never blocks the next request — a browser opens several connections
/// per origin and expects all of them to make progress.
pub async fn serve(
    listener: TcpListener,
    endpoint: Endpoint,
    addr: EndpointAddr,
    dial_timeout: Duration,
    shutdown: CancellationToken,
) {
    loop {
        let accepted = tokio::select! {
            accepted = listener.accept() => accepted,
            _ = shutdown.cancelled() => break,
        };
        let accepted = match accepted {
            Ok(accepted) => Ok(accepted),
            Err(ref cause) => {
                log::error!("the loopback listener stopped accepting: {cause}");
                accepted
            }
        };
        let Ok((stream, peer)) = accepted else {
            // A listener that has stopped accepting does not recover by being
            // asked again, and spinning on the error would burn a core to hide
            // it. The app notices through the port going dead.
            break;
        };

        let endpoint = endpoint.clone();
        let addr = addr.clone();
        let shutdown = shutdown.clone();
        tokio::spawn(async move {
            handle(stream, peer, endpoint, addr, dial_timeout, shutdown).await;
        });
    }
}

/// Carry one accepted connection over to the endpoint.
async fn handle(
    mut stream: tokio::net::TcpStream,
    peer: SocketAddr,
    endpoint: Endpoint,
    addr: EndpointAddr,
    dial_timeout: Duration,
    shutdown: CancellationToken,
) {
    // Every local peer is served. There is no way to tell one from another on
    // Android -- see the note on the loopback port in `lib.rs`.
    log::debug!("accepted {peer}");

    let connection = tokio::select! {
        dialed = tokio::time::timeout(dial_timeout, endpoint.connect(addr, ALPN)) => dialed,
        _ = shutdown.cancelled() => return,
    };

    let connection = match connection {
        Ok(Ok(connection)) => connection,
        Ok(Err(cause)) => {
            log::warn!("dial failed for {peer}: {cause}");
            return refuse(&mut stream, &cause.to_string()).await;
        }
        Err(_) => {
            log::warn!("dial timed out for {peer} after {dial_timeout:?}");
            return refuse(&mut stream, "the endpoint did not answer in time").await;
        }
    };
    log::info!("connected to the endpoint for {peer}");

    let Ok((mut send, recv)) = connection.open_bi().await else {
        return refuse(&mut stream, "could not open a stream to the endpoint").await;
    };
    // The dialing side writes first, as dumbpipe's listener expects.
    if send.write_all(&HANDSHAKE).await.is_err() {
        return refuse(&mut stream, "the endpoint rejected the handshake").await;
    }

    let (read, write) = stream.into_split();
    let forwarded = tokio::select! {
        forwarded = forward_bidi(read, write, recv, send) => forwarded,
        _ = shutdown.cancelled() => return,
    };
    // An error here is the ordinary shape of a browser closing a connection, so
    // it is not worth a warning -- but the byte counts are what tell you whether
    // anything actually flowed.
    match forwarded {
        Ok((sent, received)) => log::debug!("{peer} closed: sent {sent}, received {received}"),
        Err(cause) => log::debug!("{peer} ended: {cause}"),
    }
    // Held until both directions are done: dropping the last `Connection`
    // handle closes it, and a response that `finish()` had only queued would be
    // lost.
    drop(connection);
}

/// Tell the browser why, then close.
async fn refuse(stream: &mut tokio::net::TcpStream, reason: &str) {
    let _ = stream.write_all(&gateway::bad_gateway(reason)).await;
    let _ = stream.shutdown().await;
}

/// Bind the loopback listener, preferring `port`.
///
/// The preferred port keeps the origin stable across launches, which is what
/// `localStorage` and a restored WebView history depend on -- see
/// [`crate::ticket::preferred_port`]. It is a preference rather than a
/// requirement: something else on the device may already hold it, and a working
/// proxy on the wrong port beats no proxy at all. Falling back costs this
/// launch's web storage, not the session, because cookies key on host alone.
pub async fn bind_loopback(port: u16) -> std::io::Result<TcpListener> {
    match TcpListener::bind(SocketAddr::from((std::net::Ipv4Addr::LOCALHOST, port))).await {
        Ok(listener) => Ok(listener),
        Err(cause) => {
            log::warn!("preferred port {port} unavailable ({cause}); taking any free port");
            TcpListener::bind(SocketAddr::from((std::net::Ipv4Addr::LOCALHOST, 0))).await
        }
    }
}
