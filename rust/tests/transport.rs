//! End-to-end tests: WebView → loopback proxy → iroh → far-side listener →
//! origin server, and back.
//!
//! These are the tests that justify the design. Each one asserts a property the
//! handover document worried about — chunked bodies, `Range`, `Set-Cookie`,
//! keep-alive, streaming — and each one passes for the same reason: the proxy
//! copies bytes and never looks at them. If any of these ever fails, something
//! has started parsing HTTP that should not be.

mod support;

use std::time::Duration;

use support::{exchange, read_one_response, round_trip, FarSide, NearSide, Origin};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// A canned response with a `Content-Length`.
fn sized(body: &str) -> Vec<u8> {
    format!(
        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    )
    .into_bytes()
}

async fn pipeline(respond: impl Fn(&str, usize) -> Vec<u8> + Send + Sync + 'static) -> (Origin, FarSide, NearSide) {
    let origin = Origin::serving(respond).await;
    let far = FarSide::forwarding_to(origin.addr).await;
    let near = NearSide::dialing(far.addr.clone()).await;
    (origin, far, near)
}

#[tokio::test(flavor = "multi_thread")]
async fn a_request_reaches_the_origin_and_the_response_comes_back() {
    let (origin, _far, near) = pipeline(|_, _| sized("hello")).await;

    let mut stream = near.connect().await;
    let response = exchange(&mut stream, b"GET /index.html HTTP/1.1\r\nHost: x.localhost\r\n\r\n").await;

    let text = String::from_utf8(response).expect("utf-8");
    assert!(text.starts_with("HTTP/1.1 200 OK\r\n"), "{text}");
    assert!(text.ends_with("hello"), "{text}");

    let heads = origin.request_heads().await;
    assert_eq!(heads.len(), 1);
    assert!(heads[0].starts_with("GET /index.html HTTP/1.1\r\n"), "{:?}", heads[0]);
}

#[tokio::test(flavor = "multi_thread")]
async fn request_headers_arrive_verbatim() {
    // The proxy must not add, drop, reorder or rewrite anything -- an
    // `X-Forwarded-For` or a normalised `Host` would both be visible here.
    let (origin, _far, near) = pipeline(|_, _| sized("ok")).await;

    let request = "GET /p HTTP/1.1\r\n\
         Host: abc123.localhost:1\r\n\
         Cookie: session=abc; other=def\r\n\
         Accept-Encoding: gzip, deflate, br\r\n\
         X-Odd_Header: Mixed-Case Value\r\n\
         \r\n";
    let mut stream = near.connect().await;
    exchange(&mut stream, request.as_bytes()).await;

    assert_eq!(origin.request_heads().await[0], request);
}

#[tokio::test(flavor = "multi_thread")]
async fn set_cookie_reaches_the_browser_untouched() {
    // The reason the proxy exists rather than a `shouldInterceptRequest` hook:
    // cookies have to arrive as real response headers on a real origin.
    let (_origin, _far, near) = pipeline(|_, _| {
        b"HTTP/1.1 200 OK\r\n\
          Set-Cookie: session=s3cret; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax\r\n\
          Set-Cookie: theme=dark; Path=/\r\n\
          Content-Length: 2\r\n\
          \r\n\
          hi"
            .to_vec()
    })
    .await;

    let mut stream = near.connect().await;
    let response = exchange(&mut stream, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n").await;
    let text = String::from_utf8(response).expect("utf-8");

    assert!(text.contains("Set-Cookie: session=s3cret; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax\r\n"), "{text}");
    assert!(text.contains("Set-Cookie: theme=dark; Path=/\r\n"), "{text}");
}

#[tokio::test(flavor = "multi_thread")]
async fn keep_alive_carries_several_requests_over_one_connection() {
    // One TCP connection is one iroh connection, so this also asserts that the
    // single bi stream is reused rather than re-dialed per request.
    let (origin, _far, near) = pipeline(|_, count| sized(&format!("response {count}"))).await;

    let mut stream = near.connect().await;
    for expected in 1..=3 {
        let response = exchange(
            &mut stream,
            format!("GET /{expected} HTTP/1.1\r\nHost: x\r\n\r\n").as_bytes(),
        )
        .await;
        let text = String::from_utf8(response).expect("utf-8");
        assert!(text.ends_with(&format!("response {expected}")), "{text}");
    }

    assert_eq!(origin.request_count().await, 3);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_chunked_response_is_forwarded_as_chunks() {
    let (_origin, _far, near) = pipeline(|_, _| {
        b"HTTP/1.1 200 OK\r\n\
          Content-Type: text/plain\r\n\
          Transfer-Encoding: chunked\r\n\
          \r\n\
          5\r\nhello\r\n\
          1\r\n \r\n\
          5\r\nworld\r\n\
          0\r\n\r\n"
            .to_vec()
    })
    .await;

    let mut stream = near.connect().await;
    let response = exchange(&mut stream, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n").await;
    let text = String::from_utf8(response).expect("utf-8");

    // Chunk framing survives: the proxy has not silently de-chunked or buffered.
    assert!(text.contains("Transfer-Encoding: chunked"), "{text}");
    assert!(text.contains("5\r\nhello\r\n"), "{text}");
    assert!(text.ends_with("0\r\n\r\n"), "{text}");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_range_request_and_its_206_pass_through() {
    let (origin, _far, near) = pipeline(|_, _| {
        b"HTTP/1.1 206 Partial Content\r\n\
          Content-Range: bytes 100-199/4096\r\n\
          Accept-Ranges: bytes\r\n\
          Content-Length: 3\r\n\
          \r\n\
          abc"
            .to_vec()
    })
    .await;

    let mut stream = near.connect().await;
    let response = exchange(
        &mut stream,
        b"GET /video.mp4 HTTP/1.1\r\nHost: x\r\nRange: bytes=100-199\r\n\r\n",
    )
    .await;
    let text = String::from_utf8(response).expect("utf-8");

    assert!(origin.request_heads().await[0].contains("Range: bytes=100-199"));
    assert!(text.starts_with("HTTP/1.1 206 Partial Content\r\n"), "{text}");
    assert!(text.contains("Content-Range: bytes 100-199/4096"), "{text}");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_redirect_is_passed_through_rather_than_followed() {
    // Following it here would resolve the relative Location against the wrong
    // origin. It is the browser's job.
    let (_origin, _far, near) = pipeline(|_, _| {
        b"HTTP/1.1 302 Found\r\nLocation: /login\r\nContent-Length: 0\r\n\r\n".to_vec()
    })
    .await;

    let mut stream = near.connect().await;
    let response = exchange(&mut stream, b"GET /dashboard HTTP/1.1\r\nHost: x\r\n\r\n").await;
    let text = String::from_utf8(response).expect("utf-8");

    assert!(text.starts_with("HTTP/1.1 302 Found\r\n"), "{text}");
    assert!(text.contains("Location: /login"), "{text}");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_request_body_is_forwarded() {
    let (origin, _far, near) = pipeline(|_, _| sized("saved")).await;

    let mut stream = near.connect().await;
    let response = exchange(
        &mut stream,
        b"POST /login HTTP/1.1\r\n\
          Host: x\r\n\
          Content-Type: application/x-www-form-urlencoded\r\n\
          Content-Length: 27\r\n\
          \r\n\
          username=me&password=secret",
    )
    .await;

    assert!(String::from_utf8_lossy(&response).ends_with("saved"));
    assert!(origin.request_heads().await[0].starts_with("POST /login HTTP/1.1\r\n"));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_large_response_streams_rather_than_buffering() {
    // Eight megabytes: far past any single buffer in the path, and past what a
    // phone would want held in memory. Correctness here is the assertion that
    // the bytes all arrive in order; the streaming itself is what makes it
    // possible at all.
    const SIZE: usize = 8 * 1024 * 1024;

    let (_origin, _far, near) = pipeline(|_, _| {
        let mut response =
            format!("HTTP/1.1 200 OK\r\nContent-Length: {SIZE}\r\n\r\n").into_bytes();
        response.extend((0..SIZE).map(|i| (i % 251) as u8));
        response
    })
    .await;

    let mut stream = near.connect().await;
    stream
        .write_all(b"GET /big HTTP/1.1\r\nHost: x\r\n\r\n")
        .await
        .expect("write");

    let response = read_one_response(&mut stream).await;
    let body = &response[response
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .expect("head")
        + 4..];

    assert_eq!(body.len(), SIZE);
    assert!(
        body.iter().enumerate().all(|(i, b)| *b == (i % 251) as u8),
        "body bytes came back out of order or corrupted"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn several_connections_are_isolated_from_each_other() {
    // A browser opens several connections per origin. Each is its own iroh
    // connection, and a response must not be delivered down the wrong one.
    let (_origin, _far, near) = pipeline(|head, _| {
        let path = head.split_whitespace().nth(1).unwrap_or("/").to_string();
        sized(&format!("for {path}"))
    })
    .await;

    let mut streams = Vec::new();
    for _ in 0..6 {
        streams.push(near.connect().await);
    }
    // Send every request before reading any response, so a proxy that muddled
    // connections would have every opportunity to do so.
    for (i, stream) in streams.iter_mut().enumerate() {
        stream
            .write_all(format!("GET /path{i} HTTP/1.1\r\nHost: x\r\n\r\n").as_bytes())
            .await
            .expect("write");
    }
    for (i, stream) in streams.iter_mut().enumerate() {
        let response = read_one_response(stream).await;
        let text = String::from_utf8(response).expect("utf-8");
        assert!(text.ends_with(&format!("for /path{i}")), "{text}");
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn the_browser_closing_a_connection_does_not_disturb_the_others() {
    let (_origin, _far, near) = pipeline(|_, count| sized(&format!("n={count}"))).await;

    let doomed = near.connect().await;
    let mut kept = near.connect().await;
    drop(doomed);

    let response = exchange(&mut kept, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n").await;
    assert!(String::from_utf8_lossy(&response).contains("n="));
}

#[tokio::test(flavor = "multi_thread")]
async fn an_unreachable_endpoint_produces_a_502_rather_than_a_hang() {
    let addr = FarSide::absent().await;
    let near = NearSide::dialing_with_timeout(addr, Duration::from_secs(2)).await;

    let mut stream = near.connect().await;
    let response = tokio::time::timeout(
        Duration::from_secs(10),
        round_trip(&mut stream, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n"),
    )
    .await
    .expect("the proxy answered rather than hanging");

    let text = String::from_utf8(response).expect("utf-8");
    assert!(text.starts_with("HTTP/1.1 502 Bad Gateway\r\n"), "{text}");
    assert!(text.contains("Connection: close"), "{text}");
    assert!(text.contains("Endpoint unreachable"), "{text}");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_origin_going_away_closes_the_browser_connection() {
    // The far side connects to the origin per stream. If the origin refuses,
    // the browser must see the connection close rather than wait forever.
    let origin = Origin::serving(|_, _| sized("ok")).await;
    let unused = {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        listener.local_addr().expect("addr")
    };
    drop(origin);

    let far = FarSide::forwarding_to(unused).await;
    let near = NearSide::dialing(far.addr.clone()).await;

    let mut stream = near.connect().await;
    stream
        .write_all(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        .await
        .expect("write");

    let mut response = Vec::new();
    tokio::time::timeout(Duration::from_secs(30), stream.read_to_end(&mut response))
        .await
        .expect("the connection closed rather than hanging")
        .expect("read");
    assert!(response.is_empty(), "expected no response bytes, got {response:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn stopping_the_proxy_stops_answering() {
    let (_origin, _far, near) = pipeline(|_, _| sized("ok")).await;
    let port = near.port;
    drop(near);

    // The listener is dropped with the proxy, so the port stops accepting.
    // Retried briefly: cancellation is asynchronous.
    let closed = async {
        loop {
            match tokio::net::TcpStream::connect(("127.0.0.1", port)).await {
                Err(_) => break,
                Ok(_) => tokio::time::sleep(Duration::from_millis(20)).await,
            }
        }
    };
    tokio::time::timeout(Duration::from_secs(5), closed)
        .await
        .expect("the port stopped accepting");
}
