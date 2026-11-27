//! Outgoing request policy for media blobs.
//!
//! The SINGLE place through which a request to an address from another user
//! goes out. Addresses in `MediaRef` are written by the SENDER — untrusted
//! data: unchecked, a recipient would `GET` an internal node of their own
//! network, a cloud metadata service or a local service on a foreign say-so.
//! That is classic SSRF, and the cure is not a "bad prefix" list at call
//! sites but one primitive that cannot be bypassed.
//!
//! What is checked is not the string but the DESTINATION: the name resolves
//! to addresses, and every resulting address must be globally routable.
//! Checking the name alone is not enough — it can point anywhere, and point
//! differently on the next resolution.

use std::net::IpAddr;

use crate::error::{MediaError, MediaResult};

/// A validated destination: address parsed, name resolved, all resulting
/// addresses allowed. Created only via [`check`].
#[derive(Debug, Clone)]
pub struct Approved {
    pub url: String,
}

/// Allow a request to an untrusted address — or refuse with a reason.
pub async fn check(raw: &str) -> MediaResult<Approved> {
    let deny = |why: &str| MediaError::DownloadFailed(format!("egress: {why}"));

    let url = reqwest::Url::parse(raw).map_err(|e| deny(&format!("unparsable address: {e}")))?;

    // Scheme. Outgoing — TLS only; anything else is not up for discussion.
    let scheme = url.scheme();
    if scheme != "https" && !(cfg!(test) && scheme == "http") {
        return Err(deny(&format!("scheme {scheme} not allowed, https only")));
    }

    // Credentials in the URL — a sign of trying to smuggle something past the parser.
    if !url.username().is_empty() || url.password().is_some() {
        return Err(deny("an address with credentials is not allowed"));
    }

    let host = url.host_str().ok_or_else(|| deny("an address without a host"))?;
    let port = url.port_or_known_default().ok_or_else(|| deny("unknown port"))?;

    // The testbench — only in test builds and only in its own form
    // `http://127.0.0.1:<port>`. A plain `https://127.0.0.1` stays forbidden
    // here too: the exception is made for the local test server, not for the
    // loopback address in general.
    #[cfg(test)]
    {
        if scheme == "http" && host == "127.0.0.1" {
            return Ok(Approved { url: url.to_string() });
        }
    }

    // A literal address is judged strictly: proxy-mapped ranges are only
    // meaningful as answers of a local VPN's resolver, never typed in a URL.
    if let Ok(ip) = host.trim_start_matches('[').trim_end_matches(']').parse::<IpAddr>() {
        if !is_globally_routable(&ip) {
            return Err(deny(&format!("destination {ip} is not global")));
        }
        return Ok(Approved { url: url.to_string() });
    }

    // Through Tor the exit resolves the name and refuses internal addresses;
    // a local lookup here would only leak the name past the proxy.
    if crate::http_proxy_active() {
        return Ok(Approved { url: url.to_string() });
    }

    // The name resolves to ADDRESSES, and each is checked. The name alone
    // guarantees nothing: it can point into an internal network, and point
    // differently across resolutions.
    let addrs: Vec<IpAddr> = tokio::net::lookup_host((host, port))
        .await
        .map_err(|e| deny(&format!("the name does not resolve: {e}")))?
        .map(|s| s.ip())
        .collect();
    if addrs.is_empty() {
        return Err(deny("the name gave no address"));
    }
    for ip in &addrs {
        if !resolved_allowed(ip) {
            return Err(deny(&format!("destination {ip} is not global")));
        }
    }
    Ok(Approved { url: url.to_string() })
}

/// An address a VPN-style local proxy hands out in place of the real one and
/// intercepts itself (fake-IP DNS: 240.0.0.0/4, 198.18.0.0/15). Neither range
/// is reachable on a real network, so it cannot lead into a LAN.
fn is_proxy_mapped(ip: &IpAddr) -> bool {
    let v4 = match ip {
        IpAddr::V4(v4) => *v4,
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() { Some(v4) => v4, None => return false },
    };
    let o = v4.octets();
    (o[0] >= 240 && !v4.is_broadcast()) || (o[0] == 198 && (o[1] == 18 || o[1] == 19))
}

/// Whether an address that came from name resolution may be connected to.
pub fn resolved_allowed(ip: &IpAddr) -> bool {
    is_globally_routable(ip) || is_proxy_mapped(ip)
}

/// A globally routable address.
///
/// Rule by inversion — FORBIDDEN ranges are enumerated, and anything inside
/// them is rejected. An "allowed" list would be more dangerous: any
/// unfamiliar range would silently become reachable.
pub fn is_globally_routable(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            !(v4.is_loopback()
                || v4.is_private()
                || v4.is_link_local()
                || v4.is_broadcast()
                || v4.is_documentation()
                || v4.is_unspecified()
                || v4.is_multicast()
                // 100.64/10 — carrier-grade NAT
                || (o[0] == 100 && (64..=127).contains(&o[1]))
                // 192.0.0/24 — IETF special assignments
                || (o[0] == 192 && o[1] == 0 && o[2] == 0)
                // 198.18/15 — benchmark testing ranges
                || (o[0] == 198 && (o[1] == 18 || o[1] == 19))
                // 240/4 — reserved
                || o[0] >= 240
                // 0.0.0.0/8 — "this node". `::1` via to_ipv4() lands here
                // too, so the range is closed on both sides.
                || o[0] == 0)
        }
        IpAddr::V6(v6) => {
            // IPv6-native traits are checked FIRST. The reverse order was a
            // real hole: `to_ipv4()` maps `::1` to `0.0.0.1`, and the loopback
            // passed as an ordinary external address. Caught by a test.
            if v6.is_loopback() || v6.is_unspecified() || v6.is_multicast() {
                return false;
            }
            // An IPv4 address written in IPv6 form must be checked as IPv4:
            // otherwise `::ffff:127.0.0.1` would slip past every check.
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_globally_routable(&IpAddr::V4(v4));
            }
            let seg = v6.segments();
            !(v6.is_loopback()
                || v6.is_unspecified()
                || v6.is_multicast()
                // fc00::/7 — unique local
                || (seg[0] & 0xfe00) == 0xfc00
                // fe80::/10 — link-local
                || (seg[0] & 0xffc0) == 0xfe80
                // 2001:db8::/32 — documentation
                || (seg[0] == 0x2001 && seg[1] == 0x0db8))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn denied(url: &str) -> bool {
        check(url).await.is_err()
    }

    #[tokio::test]
    async fn ssrf_rejects_non_global_destinations() {
        for u in [
            "https://127.0.0.1/abc",
            "https://localhost/abc",
            "https://10.1.2.3/abc",
            "https://172.16.0.5/abc",
            "https://192.168.1.1/abc",
            "https://169.254.169.254/latest/meta-data",   // cloud metadata service
            "https://[::1]/abc",
            "https://[fc00::1]/abc",
            "https://[fe80::1]/abc",
            "https://[::ffff:127.0.0.1]/abc",             // IPv4 in IPv6 form
            "https://[::ffff:10.0.0.1]/abc",
            "https://0.0.0.0/abc",
            "https://100.64.0.1/abc",
            "https://240.0.0.1/abc",
        ] {
            assert!(denied(u).await, "the address must be refused: {u}");
        }
    }

    #[tokio::test]
    async fn ssrf_rejects_bad_urls_and_schemes() {
        for u in [
            "ftp://example.com/x",
            "file:///etc/passwd",
            "https://user:pass@example.com/x",            // credentials in URL
            "not a url",
            "https://",
            "gopher://example.com/x",
        ] {
            assert!(denied(u).await, "the address must be refused: {u}");
        }
    }

    #[tokio::test]
    async fn ssrf_allows_ordinary_public_https() {
        // A public address must pass — otherwise the guard would break federation.
        let r = check("https://nostr.download/abc").await;
        assert!(r.is_ok(), "a public https address must pass: {r:?}");
    }

    #[test]
    fn proxy_mapped_answers_pass_but_lan_does_not() {
        for ok in ["240.0.0.19", "198.18.0.5", "1.1.1.1"] {
            assert!(resolved_allowed(&ok.parse().unwrap()), "{ok}");
        }
        for bad in ["127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "255.255.255.255", "::1"] {
            assert!(!resolved_allowed(&bad.parse().unwrap()), "{bad}");
        }
    }

    #[test]
    fn ipv4_mapped_ipv6_is_judged_as_ipv4() {
        let mapped: IpAddr = "::ffff:192.168.0.1".parse().unwrap();
        assert!(!is_globally_routable(&mapped));
        let public: IpAddr = "::ffff:1.1.1.1".parse().unwrap();
        assert!(is_globally_routable(&public));
    }
}

// --- Safe resolver: connections go ONLY to validated addresses ---

/// A resolver that hands the HTTP client only allowed addresses.
///
/// Checking the address separately and then letting the client resolve the
/// name again is a guard with a hole: between check and connect there is a
/// window where the domain owner substitutes an internal address (DNS
/// rebinding). Here the decision is made INSIDE name resolution, so a
/// disallowed address simply never reaches the connection pool.
///
/// The request name is not rewritten, so TLS SNI and certificate validation
/// stay bound to the original name — they must not be weakened in any case.
#[derive(Debug, Default)]
pub struct SafeResolver;

impl reqwest::dns::Resolve for SafeResolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        Box::pin(async move {
            let host = name.as_str().to_string();
            let addrs: Vec<std::net::SocketAddr> = tokio::net::lookup_host((host.as_str(), 0))
                .await
                .map_err(|e| -> Box<dyn std::error::Error + Send + Sync> { Box::new(e) })?
                .collect();
            if addrs.is_empty() {
                return Err("egress: the name gave no addresses".into());
            }
            // A mixed answer (public + internal) is rejected WHOLESALE.
            // Picking the "good" addresses out of a suspicious answer means
            // trusting a source that already behaved hostilely.
            if addrs.iter().any(|s| !resolved_allowed(&s.ip())) {
                return Err("egress: the name resolved to a forbidden address".into());
            }
            let iter: Box<dyn Iterator<Item = std::net::SocketAddr> + Send> =
                Box::new(addrs.into_iter());
            Ok(iter)
        })
    }
}
