//! A loopback HTTP proxy whose upstream is an iroh endpoint.
//!
//! The Android app points a WebView at `http://<label>.localhost:<port>/`. This
//! crate is what answers there: it accepts the connection, dials the endpoint,
//! and copies bytes. Because it copies bytes and nothing else, the WebView
//! believes it is talking to an ordinary HTTP origin on loopback — so cookies,
//! caching, redirects, relative URL resolution and form handling are the
//! browser's job, not ours. That is the whole design.
//!
//! The wire format is dumbpipe's, matching `iroh-uds-listen` in
//! sashee/nixos-test: ALPN [`ALPN`], one bi stream per connection, [`HANDSHAKE`]
//! written by the dialer first. Nothing on the far side has to change.
//!
//! # A note on the loopback port
//!
//! Any app on the device can reach it. The obvious guard -- look the peer socket
//! up in `/proc/net/tcp` and require our own uid -- is not available: SELinux
//! denies untrusted apps that file from Android 10 onwards, and the replacement
//! (`ConnectivityManager.getConnectionOwnerUid`) is restricted to the active
//! VpnService. So the port is open to anything local that finds it.
//!
//! What that costs is reachability, not data: a session cookie lives in the
//! WebView's own jar and is attached only to requests the WebView makes, so
//! another app gets an unauthenticated conversation with whatever is behind the
//! endpoint. That is the same posture the far side already takes -- see
//! `monitoring-platform-tunnel.nix`, "this authenticates nobody ... what is
//! behind the socket does the authenticating".
//!
//! # A note on DNS
//!
//! iroh resolves a bare endpoint id through DNS, and Android has no
//! `/etc/resolv.conf` — `iroh_dns` reads the active network's nameservers
//! through JNI instead, which needs an Android context installed before the
//! first `DnsResolver` is built. [`install_android_context`] does that; the app
//! calls it once at startup. Without it, iroh catches the resulting panic and
//! falls back to public nameservers, so the release profile must keep panics
//! unwinding (`panic = "abort"` would turn that fallback into a crash).
//!
//! Tickets that carry relay urls sidestep discovery altogether, which is why
//! [`ticket::parse`] keeps them.

pub mod gateway;
pub mod pipe;
pub mod proxy;
pub mod runtime;
pub mod ticket;

/// The dumbpipe ALPN, for wire compatibility with the listener on the far side
/// (and with a stock `dumbpipe connect`).
pub const ALPN: &[u8] = b"DUMBPIPEV0";

/// The handshake the dialing side writes after `open_bi`, consumed by the
/// accepting side. Same convention as dumbpipe.
pub const HANDSHAKE: [u8; 5] = *b"hello";

#[cfg(target_os = "android")]
mod android;
