//! Name resolution for the endpoint: DNS-over-HTTPS, and nothing else.
//!
//! iroh resolves names to reach a relay and to look a bare endpoint id up under
//! `dns.iroh.link`. By default it asks the active network's nameservers, and a
//! network that filters DNS answers those lookups with NXDOMAIN or a sinkhole
//! address. The dial then fails as "Endpoint unreachable", with nothing to say
//! why. So every lookup goes to public DoH servers instead, and never to the
//! network's resolver -- not even as a fallback, because the fallback is exactly
//! the path the filter controls.
//!
//! The servers are dialled by address, so reaching them needs no DNS of its own,
//! and their certificates are checked against that address with the same
//! compiled-in roots the relays are checked against. What this cannot get past
//! is a network that blocks the servers themselves: then resolution fails, and
//! the dial with it, the same way it did before.

use std::net::Ipv4Addr;

use iroh::{
    dns::{DnsResolver, NameserverConfig},
    tls::{default_provider, CaTlsConfig},
};

/// Cloudflare, Google and Quad9. iroh's resolver (n0-dns-resolver) picks among
/// them by measured latency and moves on when one fails, so the order is not a
/// preference.
///
/// IPv4 only, which reaches them on an IPv6-only network too, through Android's
/// 464XLAT. (iroh 1.0's resolver, hickory, could not use an IPv6 address here:
/// it put it in the request URI without brackets.)
pub const DOH_SERVERS: [Ipv4Addr; 6] = [
    Ipv4Addr::new(1, 1, 1, 1),
    Ipv4Addr::new(1, 0, 0, 1),
    Ipv4Addr::new(8, 8, 8, 8),
    Ipv4Addr::new(8, 8, 4, 4),
    Ipv4Addr::new(9, 9, 9, 9),
    Ipv4Addr::new(149, 112, 112, 112),
];

/// The resolver the app's endpoint uses: [`DOH_SERVERS`] over HTTPS.
pub fn doh() -> std::io::Result<DnsResolver> {
    over(DOH_SERVERS)
}

/// A resolver that asks only `servers`, over HTTPS on port 443 at the standard
/// path, `/dns-query`.
///
/// No system configuration is read: iroh's builder starts from an empty server
/// list and adds only these.
fn over(servers: impl IntoIterator<Item = Ipv4Addr>) -> std::io::Result<DnsResolver> {
    let tls = CaTlsConfig::embedded().client_config(default_provider())?;
    Ok(DnsResolver::builder()
        .add_nameserver_configs(servers.into_iter().map(|ip| NameserverConfig::https(ip.into())))
        .tls_client_config(tls)
        .build())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    /// Hits the real servers, so it cannot run in the Nix sandbox:
    /// `cargo test --lib -- --ignored`. Each server on its own, so one that has
    /// stopped answering, or whose certificate no longer names its address,
    /// cannot hide behind the others.
    #[tokio::test]
    #[ignore = "needs network"]
    async fn every_server_answers_over_https() {
        for server in DOH_SERVERS {
            let resolver = over([server]).expect("resolver");
            let addresses: Vec<_> = resolver
                .lookup_ipv4("dns.iroh.link", Duration::from_secs(10))
                .await
                .unwrap_or_else(|e| panic!("{server} failed: {e}"))
                .collect();
            assert!(!addresses.is_empty(), "{server} returned no addresses");
        }
    }
}
