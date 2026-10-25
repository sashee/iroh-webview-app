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

use std::{
    collections::BTreeMap,
    net::SocketAddr,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use iroh::{
    endpoint::{Connection, PathId},
    Endpoint, EndpointAddr,
};
use tokio::{io::AsyncWriteExt, net::TcpListener, time::MissedTickBehavior};
use tokio_util::sync::CancellationToken;

use crate::{
    gateway,
    pipe::{forward_bidi, Activity},
    ALPN, HANDSHAKE,
};

/// How long one dial may take before the browser is told the endpoint is
/// unreachable.
///
/// Short enough that a page load fails visibly rather than spinning, long
/// enough to cover a relay-mediated connection to a peer that is awake. A
/// parameter rather than a hard constant so the unreachable-endpoint tests do
/// not have to spend it.
pub const DIAL_TIMEOUT: Duration = Duration::from_secs(20);

/// How long the stream towards the endpoint may take nothing, while the
/// browser has bytes for it, before the connection is closed
/// ([`crate::pipe::copy_watched`]). A browser then sees a failed request, which
/// it can retry, rather than one that never ends.
pub const STALL_TIMEOUT: Duration = Duration::from_secs(30);

/// What the proxy does about a response that doesn't come.
///
/// A connection that sent the endpoint a request and heard nothing back is not
/// necessarily stuck: a server may take minutes to answer, as one passing a
/// large upload on to S3 does. What says it is stuck is QUIC: the connection
/// losing packets, over and over, while the answer is overdue. That is what
/// connections Android leaves behind do after it gives the app its network
/// back: they carry a small request but not a request body (DESIGN.md, Android
/// specifics). Closing one makes the browser send the request again on a fresh
/// connection, which Chromium does by itself on a connection it had reused.
#[derive(Clone, Copy, Debug)]
pub struct Watchdog {
    /// How long an answer may be owed before the connection's losses count.
    pub overdue: Duration,
    /// How often a connection is looked at.
    pub every: Duration,
    /// How many packets, not counting MTU probes, the connection may lose
    /// after the answer is overdue. A lost keep-alive or two is the network
    /// being a network; a request lost again and again is more.
    pub lost_packets: u64,
}

pub const WATCHDOG: Watchdog = Watchdog {
    overdue: Duration::from_secs(20),
    every: Duration::from_secs(5),
    lost_packets: 3,
};

/// The packets each open path of `connection` has lost, not counting MTU
/// probes: losing those is how a path finds its MTU.
fn losses(connection: &Connection) -> BTreeMap<PathId, u64> {
    let paths = connection.paths();
    (&paths)
        .into_iter()
        .map(|path| {
            let stats = path.stats();
            (path.id(), stats.lost_packets.saturating_sub(stats.lost_plpmtud_probes))
        })
        .collect()
}

/// The packets lost between two snapshots of [`losses`], on the paths open at
/// both.
pub fn lost_between<K: Ord>(before: &BTreeMap<K, u64>, after: &BTreeMap<K, u64>) -> u64 {
    after
        .iter()
        .filter_map(|(path, lost)| before.get(path).map(|was| lost.saturating_sub(*was)))
        .sum()
}

/// One line about each path of `connection`, for the log.
fn describe(connection: &Connection) -> String {
    let paths = connection.paths();
    (&paths)
        .into_iter()
        .map(|path| {
            let stats = path.stats();
            format!(
                "{}{} rtt {:?} mtu {} sent {} lost {} (mtu probes {}) black holes {}",
                if path.is_relay() { "relay" } else { "direct" },
                if path.is_selected() { " (selected)" } else { "" },
                stats.rtt,
                stats.current_mtu,
                stats.sent_packets,
                stats.lost_packets,
                stats.lost_plpmtud_probes,
                stats.black_holes_detected,
            )
        })
        .collect::<Vec<_>>()
        .join("; ")
}

/// Resolves, saying why, once `connection` has owed an answer for longer than
/// `watchdog.overdue` and lost more than `watchdog.lost_packets` packets since
/// then (see [`Watchdog`]). Never resolves otherwise. An answer overdue without
/// those losses is logged once, with what QUIC says of each path.
pub async fn stuck(connection: &Connection, activity: &Activity, watchdog: Watchdog) -> String {
    let mut ticks = tokio::time::interval(watchdog.every);
    ticks.set_missed_tick_behavior(MissedTickBehavior::Delay);
    // Since when the answer is owed, and the losses once it was overdue.
    let mut overdue: Option<(Instant, BTreeMap<PathId, u64>)> = None;
    loop {
        ticks.tick().await;
        let Some(since) = activity.owed_since().filter(|since| since.elapsed() >= watchdog.overdue) else {
            overdue = None;
            continue;
        };
        match &overdue {
            Some((at, before)) if *at == since => {
                let lost = lost_between(before, &losses(connection));
                if lost > watchdog.lost_packets {
                    return format!("no answer for {:?}, and {lost} packets lost since it was due: {}", since.elapsed(), describe(connection));
                }
            }
            _ => {
                log::info!("an answer has been owed for {:?}: {}", since.elapsed(), describe(connection));
                overdue = Some((since, losses(connection)));
            }
        }
    }
}

/// The connections the proxy carries, closable all at once.
///
/// Each accepted connection holds the token of the generation it was accepted
/// in, a child of the proxy's shutdown token. [`Connections::close_all`]
/// cancels that generation, which ends every connection in it, and starts the
/// next one, so the connections accepted afterwards are untouched. Android
/// blocking the app's network is what calls it: an iroh connection that was
/// open across a block may carry small requests afterwards but never bulk data
/// again, so the browser is made to open fresh ones (DESIGN.md, Android
/// specifics).
#[derive(Clone, Debug)]
pub struct Connections {
    shutdown: CancellationToken,
    generation: Arc<Mutex<CancellationToken>>,
}

impl Connections {
    pub fn new(shutdown: &CancellationToken) -> Self {
        Self {
            shutdown: shutdown.clone(),
            generation: Arc::new(Mutex::new(shutdown.child_token())),
        }
    }

    /// Close every connection open now. Those accepted afterwards carry on.
    pub fn close_all(&self) {
        let fresh = self.shutdown.child_token();
        let ended = std::mem::replace(&mut *self.lock(), fresh);
        ended.cancel();
    }

    fn current(&self) -> CancellationToken {
        self.lock().clone()
    }

    /// Recovered from poisoning: a token has no invariant a panic could break.
    fn lock(&self) -> std::sync::MutexGuard<'_, CancellationToken> {
        self.generation.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// Accept connections until `shutdown` is cancelled.
///
/// Every accepted connection is handled on its own task, so a slow dial to the
/// far side never blocks the next request — a browser opens several connections
/// per origin and expects all of them to make progress. Each one ends with
/// `shutdown`, when `connections` closes its generation, or when `watchdog`
/// finds it stuck.
pub async fn serve(
    listener: TcpListener,
    endpoint: Endpoint,
    addr: EndpointAddr,
    dial_timeout: Duration,
    watchdog: Watchdog,
    shutdown: CancellationToken,
    connections: Connections,
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
        let stop = connections.current();
        tokio::spawn(async move {
            handle(stream, peer, endpoint, addr, dial_timeout, watchdog, stop).await;
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
    watchdog: Watchdog,
    stop: CancellationToken,
) {
    // Every local peer is served. There is no way to tell one from another on
    // Android -- see the note on the loopback port in `lib.rs`.
    log::debug!("accepted {peer}");

    let connection = tokio::select! {
        dialed = tokio::time::timeout(dial_timeout, endpoint.connect(addr, ALPN)) => dialed,
        _ = stop.cancelled() => return,
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
    let activity = Arc::new(Activity::default());
    // Ends this connection alone, when its answer is stuck.
    let this = stop.child_token();
    let forwarded = tokio::select! {
        forwarded = forward_bidi(read, write, recv, send, STALL_TIMEOUT, activity.clone(), &this) => forwarded,
        why = stuck(&connection, &activity, watchdog) => {
            log::warn!("closing the connection for {peer}, stuck: {why}");
            this.cancel();
            return;
        }
        _ = stop.cancelled() => return,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn losses_count_on_the_paths_open_before_and_after() {
        let before = BTreeMap::from([(0, 5), (1, 2)]);
        let after = BTreeMap::from([(0, 9), (2, 40)]);
        assert_eq!(lost_between(&before, &after), 4, "a path that closed or opened since doesn't count");
        assert_eq!(lost_between(&after, &after), 0);
    }
}
