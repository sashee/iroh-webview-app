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

use std::{io, time::Duration};

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio_util::sync::CancellationToken;

/// Copy `from` into `to` until `from` ends, failing with `TimedOut` when `to`
/// accepts nothing for `stall`.
///
/// Only writes are watched. A read may wait for ever, and should: a connection
/// the browser keeps alive between requests sends nothing for minutes. A write
/// that waits means bytes are queued and the other side is taking none of them,
/// which, for writes into iroh, is a connection that no longer carries anything
/// (DESIGN.md, Android specifics). Every write that moves at least one byte
/// counts as progress, so a slow link is not a stalled one.
pub async fn copy_watched(
    mut from: impl AsyncRead + Unpin,
    mut to: impl AsyncWrite + Unpin,
    stall: Duration,
) -> io::Result<u64> {
    let mut buffer = vec![0u8; 64 * 1024];
    let mut total = 0u64;
    loop {
        let read = from.read(&mut buffer).await?;
        if read == 0 {
            return Ok(total);
        }
        let mut written = 0;
        while written < read {
            let wrote = tokio::time::timeout(stall, to.write(&buffer[written..read]))
                .await
                .map_err(|_| {
                    io::Error::new(
                        io::ErrorKind::TimedOut,
                        format!("the endpoint took nothing for {stall:?}"),
                    )
                })??;
            if wrote == 0 {
                return Err(io::ErrorKind::WriteZero.into());
            }
            written += wrote;
        }
        total += read as u64;
    }
}

/// Copy from a reader into a send stream, resetting the stream on cancellation,
/// and failing when the stream stalls for `stall` ([`copy_watched`]).
async fn copy_to_stream(
    from: impl AsyncRead + Unpin,
    mut send: noq::SendStream,
    token: CancellationToken,
    stall: Duration,
) -> io::Result<u64> {
    tokio::select! {
        res = copy_watched(from, &mut send, stall) => {
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
/// hang. The stream towards the endpoint failing to take anything for `stall`
/// is such a failure. The other direction has no such limit: a browser may stop
/// reading for as long as it likes, as a paused `<audio>` element does.
///
/// Cancelling `stop` ends both directions too. Each runs on a task of its own,
/// which dropping this future would not end, and those tasks hold the sockets.
pub async fn forward_bidi(
    from_local: impl AsyncRead + Send + Sync + Unpin + 'static,
    to_local: impl AsyncWrite + Send + Sync + Unpin + 'static,
    from_remote: noq::RecvStream,
    to_remote: noq::SendStream,
    stall: Duration,
    stop: &CancellationToken,
) -> io::Result<(u64, u64)> {
    let outbound_token = stop.child_token();
    let inbound_token = outbound_token.clone();

    let outbound = tokio::spawn(async move {
        copy_to_stream(from_local, to_remote, outbound_token.clone(), stall)
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

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;

    const STALL: Duration = Duration::from_millis(200);

    #[tokio::test]
    async fn everything_is_copied_until_the_reader_ends() {
        let data: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
        let (mut source, from) = duplex(1 << 20);
        let (to, mut sink) = duplex(1 << 20);
        let writing = data.clone();
        tokio::spawn(async move {
            source.write_all(&writing).await.unwrap();
        });
        let copying = tokio::spawn(copy_watched(from, to, STALL));
        let mut copied = Vec::new();
        sink.read_to_end(&mut copied).await.unwrap();
        assert_eq!(copying.await.unwrap().unwrap(), data.len() as u64);
        assert_eq!(copied, data);
    }

    #[tokio::test]
    async fn a_writer_that_takes_nothing_fails_the_copy_after_the_stall() {
        let (mut source, from) = duplex(1 << 20);
        // A small pipe whose far end is never read: it fills, then takes nothing.
        let (to, _never_read) = duplex(1024);
        source.write_all(&[7u8; 10_000]).await.unwrap();
        let started = std::time::Instant::now();
        let error = copy_watched(from, to, STALL).await.unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        assert!(started.elapsed() >= STALL);
    }

    #[tokio::test]
    async fn a_slow_writer_that_keeps_taking_is_not_a_stalled_one() {
        let (mut source, from) = duplex(1 << 20);
        let (to, mut sink) = duplex(512);
        source.write_all(&[1u8; 4096]).await.unwrap();
        drop(source);
        let copying = tokio::spawn(copy_watched(from, to, STALL));
        // 512 bytes every 100 ms: slower than the whole copy could take within one stall, but never idle that long.
        let mut copied = 0;
        let mut buffer = [0u8; 512];
        while copied < 4096 {
            tokio::time::sleep(Duration::from_millis(100)).await;
            copied += sink.read(&mut buffer).await.unwrap();
        }
        assert_eq!(copying.await.unwrap().unwrap(), 4096);
    }

    #[tokio::test]
    async fn an_idle_reader_is_not_a_stall() {
        let (mut source, from) = duplex(1024);
        let (to, mut sink) = duplex(1024);
        let copying = tokio::spawn(copy_watched(from, to, STALL));
        tokio::time::sleep(STALL * 3).await;
        source.write_all(b"late").await.unwrap();
        drop(source);
        let mut copied = Vec::new();
        sink.read_to_end(&mut copied).await.unwrap();
        assert_eq!(copied, b"late");
        assert_eq!(copying.await.unwrap().unwrap(), 4);
    }
}
