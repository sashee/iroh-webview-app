//! Bidirectional byte forwarding between a local socket and an iroh stream.
//!
//! Adapted from `iroh-ssh` in sashee/nixos-test, which is itself adapted from
//! dumbpipe 0.39 (MIT OR Apache-2.0, n0-computer/dumbpipe). Kept generic over
//! the reader/writer pair rather than taking a `TcpStream`, so the whole
//! forwarding path can be tested over `tokio::io::duplex()` with no network at
//! all — which is most of what there is to get wrong here.
//!
//! Nothing in this module looks at the bytes. That is the property that makes
//! chunked encoding, `Range`, `Set-Cookie`, redirects and keep-alive work
//! without any code: they are the browser's and the server's business, and the
//! proxy is not a participant.

use std::io;

use tokio::io::{AsyncRead, AsyncWrite};
use tokio_util::sync::CancellationToken;

/// Copy from a reader into a send stream, resetting the stream on cancellation.
async fn copy_to_stream(
    mut from: impl AsyncRead + Unpin,
    mut send: noq::SendStream,
    token: CancellationToken,
) -> io::Result<u64> {
    tokio::select! {
        res = tokio::io::copy(&mut from, &mut send) => {
            let size = res?;
            // Half-close rather than reset: the far side may still be reading a
            // request body, and an HTTP client that has finished sending is not
            // finished receiving.
            send.finish()?;
            Ok(size)
        }
        _ = token.cancelled() => {
            send.reset(0u8.into()).ok();
            Err(io::Error::other("cancelled"))
        }
    }
}

/// Copy from a recv stream into a writer, stopping the stream on cancellation.
async fn copy_from_stream(
    mut recv: noq::RecvStream,
    mut to: impl AsyncWrite + Unpin,
    token: CancellationToken,
) -> io::Result<u64> {
    tokio::select! {
        res = tokio::io::copy(&mut recv, &mut to) => Ok(res?),
        _ = token.cancelled() => {
            recv.stop(0u8.into()).ok();
            Err(io::Error::other("cancelled"))
        }
    }
}

fn cancel<T>(token: CancellationToken) -> impl Fn(T) -> T {
    move |x| {
        token.cancel();
        x
    }
}

/// Forward in both directions until either side finishes or errors.
///
/// A failure in one direction cancels the other: a browser that has gone away
/// should not leave a half-pipe holding an iroh stream open, and a peer that has
/// gone away should surface to the browser as a closed socket rather than a
/// hang.
pub async fn forward_bidi(
    from_local: impl AsyncRead + Send + Sync + Unpin + 'static,
    to_local: impl AsyncWrite + Send + Sync + Unpin + 'static,
    from_remote: noq::RecvStream,
    to_remote: noq::SendStream,
) -> io::Result<(u64, u64)> {
    let outbound_token = CancellationToken::new();
    let inbound_token = outbound_token.clone();

    let outbound = tokio::spawn(async move {
        copy_to_stream(from_local, to_remote, outbound_token.clone())
            .await
            .map_err(cancel(outbound_token))
    });
    let inbound = tokio::spawn(async move {
        copy_from_stream(from_remote, to_local, inbound_token.clone())
            .await
            .map_err(cancel(inbound_token))
    });

    let received = inbound.await.map_err(io::Error::other)??;
    let sent = outbound.await.map_err(io::Error::other)??;
    Ok((sent, received))
}
