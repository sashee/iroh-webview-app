//! Starting and stopping the proxy from a non-async caller.
//!
//! The app drives this from Kotlin, which has no runtime of its own to lend us,
//! so [`Running`] owns a tokio runtime on its own threads. Only one endpoint is
//! ever active — the app switches rather than running several — so there is one
//! [`Running`] at a time and no registry to keep.

use std::{net::Ipv4Addr, net::SocketAddr, time::Duration};

use iroh::{endpoint::presets, Endpoint, EndpointAddr};
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

use crate::{
    proxy,
    ticket::{self, TicketError},
};

/// Why the proxy could not be started.
#[derive(Debug)]
pub enum StartError {
    /// What the user pasted is not dialable.
    Ticket(TicketError),
    /// The tokio runtime, the iroh endpoint, or the loopback socket.
    Io(String),
}

impl std::fmt::Display for StartError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StartError::Ticket(cause) => write!(f, "{cause}"),
            StartError::Io(cause) => write!(f, "{cause}"),
        }
    }
}

impl std::error::Error for StartError {}

/// A bound proxy. Dropping it stops accepting and tears the runtime down.
#[derive(Debug)]
pub struct Running {
    port: u16,
    label: String,
    shutdown: CancellationToken,
    // Declared last so it is dropped last: the runtime must outlive the tasks
    // cancellation is asking to finish.
    runtime: Option<Runtime>,
}

impl Running {
    /// The loopback port the WebView should be pointed at.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// The DNS label identifying this endpoint's origin.
    pub fn label(&self) -> &str {
        &self.label
    }
}

impl Drop for Running {
    fn drop(&mut self) {
        self.shutdown.cancel();
        if let Some(runtime) = self.runtime.take() {
            // Without a timeout, a connection still streaming a large response
            // would hold the drop -- and this runs on whatever thread Kotlin
            // called stop() from, which may be the main one.
            runtime.shutdown_timeout(Duration::from_secs(1));
        }
    }
}

/// Bind a proxy for what the user pasted.
///
/// The endpoint uses the n0 defaults: relays for reachability, and discovery so
/// that a bare endpoint id resolves. A ticket carrying relay urls does not need
/// the discovery half — see the DNS note in the crate docs.
pub fn start(input: &str) -> Result<Running, StartError> {
    let addr = ticket::parse(input).map_err(StartError::Ticket)?;
    let runtime = Runtime::new().map_err(|e| StartError::Io(e.to_string()))?;
    let endpoint = runtime
        .block_on(Endpoint::builder(presets::N0).bind())
        .map_err(|e| StartError::Io(e.to_string()))?;
    bind_with(runtime, endpoint, addr, proxy::DIAL_TIMEOUT)
}

/// The half of [`start`] that does not choose an endpoint, so tests can supply a
/// loopback-only one.
pub fn bind_with(
    runtime: Runtime,
    endpoint: Endpoint,
    addr: EndpointAddr,
    dial_timeout: Duration,
) -> Result<Running, StartError> {
    let label = ticket::host_label(&addr);
    let listener = runtime
        .block_on(proxy::bind_loopback(ticket::preferred_port(&addr)))
        .map_err(|e| StartError::Io(e.to_string()))?;
    let port = listener
        .local_addr()
        .map_err(|e| StartError::Io(e.to_string()))?
        .port();

    let shutdown = CancellationToken::new();
    runtime.spawn(proxy::serve(
        listener,
        endpoint,
        addr,
        dial_timeout,
        shutdown.clone(),
    ));

    Ok(Running {
        port,
        label,
        shutdown,
        runtime: Some(runtime),
    })
}

/// Where the proxy binds. Exposed for the tests that assert it is loopback.
pub fn loopback(port: u16) -> SocketAddr {
    SocketAddr::from((Ipv4Addr::LOCALHOST, port))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_malformed_ticket_fails_before_anything_is_bound() {
        let error = start("not a ticket").expect_err("should fail");
        assert!(matches!(error, StartError::Ticket(TicketError::Unrecognised)));
    }

    #[test]
    fn an_empty_ticket_is_reported_as_empty() {
        let error = start("   ").expect_err("should fail");
        assert!(matches!(error, StartError::Ticket(TicketError::Empty)));
    }

    fn an_addr(seed: u8) -> EndpointAddr {
        EndpointAddr::new(iroh::SecretKey::from_bytes(&[seed; 32]).public())
    }

    /// A proxy for the endpoint identified by `seed`.
    ///
    /// The seed is a parameter because the preferred port is a function of the
    /// endpoint id: two tests sharing an id would race for the same port when
    /// the suite runs in parallel.
    fn local_running(seed: u8) -> Running {
        let runtime = Runtime::new().expect("runtime");
        let endpoint = runtime
            .block_on(
                Endpoint::builder(presets::Minimal)
                    .bind_addr(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
                    .expect("bind_addr")
                    .bind(),
            )
            .expect("endpoint");
        bind_with(runtime, endpoint, an_addr(seed), Duration::from_millis(200)).expect("start")
    }

    #[test]
    fn starting_binds_a_loopback_port_that_accepts() {
        let running = local_running(1);
        assert_ne!(running.port(), 0);
        std::net::TcpStream::connect(loopback(running.port())).expect("connect");
    }

    #[test]
    fn the_label_identifies_the_endpoint() {
        let running = local_running(2);
        assert_eq!(running.label(), ticket::host_label(&an_addr(2)));
    }

    #[test]
    fn stopping_releases_the_port() {
        let running = local_running(3);
        let port = running.port();
        drop(running);

        // Cancellation is asynchronous; the port goes away promptly but not
        // instantly.
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while std::time::Instant::now() < deadline {
            if std::net::TcpStream::connect(loopback(port)).is_err() {
                return;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        panic!("the port was still accepting after stop");
    }

    #[test]
    fn the_bound_port_is_the_endpoints_preferred_one() {
        // What makes the origin stable across launches. Asserted here rather
        // than only in `ticket` because it is the wiring that tends to rot: the
        // pure function can stay right while the caller stops using it.
        let running = local_running(4);
        assert_eq!(running.port(), ticket::preferred_port(&an_addr(4)));
    }

    #[test]
    fn a_taken_preferred_port_falls_back_instead_of_failing() {
        // Something else on the device may hold it. A proxy on the wrong port
        // costs this launch's web storage; no proxy at all costs everything.
        let squatter = std::net::TcpListener::bind(loopback(ticket::preferred_port(&an_addr(5))))
            .expect("occupy the preferred port");

        let running = local_running(5);

        assert_ne!(running.port(), ticket::preferred_port(&an_addr(5)));
        assert_ne!(running.port(), 0);
        std::net::TcpStream::connect(loopback(running.port())).expect("still serving");
        drop(squatter);
    }
}
