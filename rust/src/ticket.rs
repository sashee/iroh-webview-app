//! Turning what the user pasted into something dialable.
//!
//! Two forms are accepted, because both are things `iroh-ssh-ticket` on the
//! far side prints:
//!
//! * a ticket — the id, optionally carrying the relay urls in use when it was
//!   made. Relay urls keep discovery off the critical path, which matters on a
//!   phone (see the DNS note in `lib.rs`).
//! * a bare endpoint id — 64 hex characters. Stable across restarts and
//!   networks, but it can only be resolved through discovery.
//!
//! Pure: no endpoint is bound and no network is touched.

use std::str::FromStr;

use iroh::{EndpointAddr, EndpointId};
use iroh_tickets::endpoint::EndpointTicket;

/// Why a pasted string is not something we can dial.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TicketError {
    /// Nothing but whitespace.
    Empty,
    /// Neither a ticket nor an endpoint id.
    Unrecognised,
}

impl std::fmt::Display for TicketError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TicketError::Empty => write!(f, "no endpoint given"),
            TicketError::Unrecognised => {
                write!(f, "not an iroh ticket or endpoint id")
            }
        }
    }
}

impl std::error::Error for TicketError {}

/// Parse a pasted ticket or endpoint id into an address to dial.
///
/// Surrounding whitespace is stripped: these arrive by copy-paste, and a
/// trailing newline from a terminal is not a typo worth rejecting.
pub fn parse(input: &str) -> Result<EndpointAddr, TicketError> {
    let input = input.trim();
    if input.is_empty() {
        return Err(TicketError::Empty);
    }
    if let Ok(ticket) = EndpointTicket::from_str(input) {
        return Ok(ticket.endpoint_addr().clone());
    }
    if let Ok(id) = EndpointId::from_str(input) {
        return Ok(EndpointAddr::new(id));
    }
    Err(TicketError::Unrecognised)
}

/// A short, stable, DNS-safe label for an endpoint.
///
/// Used as the leftmost label of the `<label>.localhost` origin the WebView
/// loads, which is what gives each endpoint its own cookie jar and its own
/// autofill origin. It has to be a pure function of the id so that returning to
/// an endpoint returns to the same origin, and lowercase hex is already a valid
/// DNS label.
pub fn host_label(addr: &EndpointAddr) -> String {
    // 16 hex characters — 64 bits — is far more than enough to keep a handful of
    // hand-entered endpoints apart, and keeps the origin readable in the URL bar.
    addr.id.to_string().chars().take(16).collect()
}

/// The loopback origin the WebView is pointed at for this endpoint.
pub fn proxy_url(label: &str, port: u16) -> String {
    format!("http://{label}.localhost:{port}/")
}

/// The port this endpoint prefers to be served on.
///
/// A fresh random port each launch would be harmless for cookies, which key on
/// host alone -- but `localStorage`, `sessionStorage` and IndexedDB key on the
/// whole origin, *including the port*, and so would be wiped every time the app
/// started. A restored WebView history has the same problem: its entries name
/// the port the proxy had last time.
///
/// So the port is a pure function of the endpoint id, like the label. The range
/// sits above the well-known services and below Linux's default ephemeral range
/// (32768 and up), so it collides neither with something the device is serving
/// nor with a port the kernel might hand out to somebody else.
pub fn preferred_port(addr: &EndpointAddr) -> u16 {
    const FIRST: u32 = 20_000;
    const COUNT: u32 = 12_000;

    // FNV-1a over the id. Any stable hash would do; this one is three lines and
    // needs no dependency.
    let hash = addr.id.as_bytes().iter().fold(0x811c9dc5u32, |hash, byte| {
        (hash ^ *byte as u32).wrapping_mul(0x0100_0193)
    });
    (FIRST + hash % COUNT) as u16
}

#[cfg(test)]
mod tests {
    use super::*;
    use iroh::SecretKey;

    fn an_id() -> EndpointId {
        // Fixed bytes rather than a random key: the label assertions below are
        // about a specific id, and a random one would make them untestable.
        SecretKey::from_bytes(&[7u8; 32]).public()
    }

    #[test]
    fn parses_a_bare_endpoint_id() {
        let id = an_id();
        let addr = parse(&id.to_string()).expect("parse");
        assert_eq!(addr.id, id);
    }

    #[test]
    fn parses_a_ticket() {
        let id = an_id();
        let ticket = EndpointTicket::from(EndpointAddr::new(id));
        let addr = parse(&ticket.to_string()).expect("parse");
        assert_eq!(addr.id, id);
    }

    #[test]
    fn a_ticket_keeps_its_relay_urls() {
        let id = an_id();
        let relay = "https://relay.example/".parse().expect("relay url");
        let ticket = EndpointTicket::from(EndpointAddr::new(id).with_relay_url(relay));
        let addr = parse(&ticket.to_string()).expect("parse");
        assert_eq!(addr.id, id);
        assert_eq!(addr.relay_urls().count(), 1);
    }

    #[test]
    fn surrounding_whitespace_is_ignored() {
        let id = an_id();
        let padded = format!("  \n\t{id}\n  ");
        assert_eq!(parse(&padded).expect("parse").id, id);
    }

    #[test]
    fn empty_input_is_reported_as_empty() {
        assert_eq!(parse(""), Err(TicketError::Empty));
        assert_eq!(parse("   \n\t "), Err(TicketError::Empty));
    }

    #[test]
    fn garbage_is_rejected() {
        assert_eq!(parse("hello"), Err(TicketError::Unrecognised));
        assert_eq!(parse("http://example.com"), Err(TicketError::Unrecognised));
    }

    #[test]
    fn a_truncated_endpoint_id_is_rejected() {
        let id = an_id().to_string();
        assert_eq!(parse(&id[..32]), Err(TicketError::Unrecognised));
    }

    #[test]
    fn an_endpoint_id_with_a_bad_character_is_rejected() {
        let mut id = an_id().to_string();
        id.replace_range(0..1, "z");
        assert_eq!(parse(&id), Err(TicketError::Unrecognised));
    }

    #[test]
    fn the_label_is_a_stable_pure_function_of_the_id() {
        let addr = EndpointAddr::new(an_id());
        assert_eq!(host_label(&addr), host_label(&addr));
        // Relay urls are addressing detail, not identity: they must not change
        // the origin, or returning to an endpoint would lose its cookies.
        let relay = "https://relay.example/".parse().expect("relay url");
        let with_relay = EndpointAddr::new(an_id()).with_relay_url(relay);
        assert_eq!(host_label(&addr), host_label(&with_relay));
    }

    #[test]
    fn the_label_is_a_valid_dns_label() {
        let label = host_label(&EndpointAddr::new(an_id()));
        assert_eq!(label.len(), 16);
        assert!(label.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit()));
    }

    #[test]
    fn distinct_endpoints_get_distinct_labels() {
        let a = EndpointAddr::new(SecretKey::from_bytes(&[1u8; 32]).public());
        let b = EndpointAddr::new(SecretKey::from_bytes(&[2u8; 32]).public());
        assert_ne!(host_label(&a), host_label(&b));
    }

    #[test]
    fn the_proxy_url_is_a_loopback_origin() {
        assert_eq!(proxy_url("abc123", 41234), "http://abc123.localhost:41234/");
    }

    #[test]
    fn the_preferred_port_is_stable_for_an_endpoint() {
        // The whole point: the same endpoint must come back on the same origin,
        // or its localStorage and its restored history are lost every launch.
        let addr = EndpointAddr::new(an_id());
        assert_eq!(preferred_port(&addr), preferred_port(&addr));
    }

    #[test]
    fn the_preferred_port_ignores_relay_urls() {
        let relay = "https://relay.example/".parse().expect("relay url");
        let bare = EndpointAddr::new(an_id());
        let with_relay = EndpointAddr::new(an_id()).with_relay_url(relay);
        assert_eq!(preferred_port(&bare), preferred_port(&with_relay));
    }

    #[test]
    fn the_preferred_port_avoids_privileged_and_ephemeral_ranges() {
        // Below 1024 needs root; 32768 and up is what the kernel hands out for
        // outbound sockets, so binding there invites a collision.
        for seed in 0u8..64 {
            let addr = EndpointAddr::new(SecretKey::from_bytes(&[seed; 32]).public());
            let port = preferred_port(&addr);
            assert!((20_000..32_000).contains(&port), "port {port} out of range");
        }
    }

    #[test]
    fn distinct_endpoints_mostly_get_distinct_ports() {
        // Not a promise -- 12000 ports and a hash will collide eventually, which
        // is why binding falls back. But it must not be systematically clumped.
        let ports: std::collections::HashSet<u16> = (0u8..64)
            .map(|seed| preferred_port(&EndpointAddr::new(SecretKey::from_bytes(&[seed; 32]).public())))
            .collect();
        assert!(ports.len() >= 60, "only {} distinct ports for 64 endpoints", ports.len());
    }
}
