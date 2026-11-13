//! Local SOCKS5 switch: every client of the core connects through it, and it
//! sends each connection either straight out or into Tor.
//!
//! The clients (relay pool, media, wake-ups) take their proxy once, when they
//! are built. Pointing them all at this switch lets private mode change while
//! the app runs: [`set_tor`] flips the route and closes the tunnels opened
//! under the old one, so every client reconnects through the new route.
//!
//! In Tor mode a connection that cannot reach Tor fails; it never falls back
//! to a direct one.

use std::net::{IpAddr, SocketAddr};
use std::sync::RwLock;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

static TOR: RwLock<Option<SocketAddr>> = RwLock::new(None);

/// Bumped on every route change; open tunnels close when it moves.
fn route_changed() -> &'static tokio::sync::watch::Sender<u64> {
    static TX: std::sync::OnceLock<tokio::sync::watch::Sender<u64>> = std::sync::OnceLock::new();
    TX.get_or_init(|| tokio::sync::watch::channel(0).0)
}

/// Route through the Tor SOCKS port at `tor`, or straight out with `None`.
/// Tunnels opened under the previous route are closed.
pub fn set_tor(tor: Option<SocketAddr>) {
    if let Ok(mut t) = TOR.write() {
        *t = tor;
    }
    route_changed().send_modify(|g| *g += 1);
    tracing::warn!("NET_SWITCH route={}", if tor.is_some() { "tor" } else { "direct" });
}

/// The device's network changed (or went away): every tunnel, and every dial
/// still in progress, is bound to the old one and is closed now, so clients
/// reconnect over the new network at once instead of waiting for a dead
/// socket to time out.
pub fn network_changed() {
    route_changed().send_modify(|g| *g += 1);
    tracing::warn!("NET_SWITCH network changed: tunnels closed");
}

pub fn tor_route() -> Option<SocketAddr> {
    TOR.read().ok().and_then(|t| *t)
}

static TOR_PORT: RwLock<Option<SocketAddr>> = RwLock::new(None);

/// Where our Tor listens, whether or not private mode routes through it:
/// Tor-only listeners use it always.
pub fn set_tor_port(port: Option<SocketAddr>) {
    if let Ok(mut t) = TOR_PORT.write() {
        *t = port;
    }
}

fn tor_port() -> Option<SocketAddr> {
    TOR_PORT.read().ok().and_then(|t| *t).or_else(tor_route)
}

/// Which tunnels share a Tor circuit. Tor keeps streams that present different
/// SOCKS credentials on different circuits (`IsolateSOCKSAuth`, on by default),
/// so each scope hands Tor its own credentials.
#[derive(Clone)]
struct Scope {
    secret: [u8; 16],
    /// Bumped to ask Tor for new circuits for this listener alone.
    renewals: std::sync::Arc<std::sync::atomic::AtomicU64>,
    /// One circuit per destination host (long-lived listeners: our relays must
    /// not see one exit for all of them); otherwise one for the whole listener.
    per_host: bool,
    /// Through Tor whatever the route; no Tor, no connection.
    tor_only: bool,
}

impl Scope {
    fn new(per_host: bool) -> Self {
        Self { secret: rand::random(), renewals: Default::default(), per_host, tor_only: false }
    }

    /// Fresh after every route change, so a new route never reuses old circuits.
    fn credentials(&self, generation: u64, host: &str) -> String {
        use sha2::{Digest, Sha256};
        let mut h = Sha256::new();
        h.update(self.secret);
        h.update(generation.to_be_bytes());
        h.update(self.renewals.load(std::sync::atomic::Ordering::Relaxed).to_be_bytes());
        if self.per_host {
            h.update(host.to_ascii_lowercase().as_bytes());
        }
        hex::encode(&h.finalize()[..16])
    }
}

/// Start a listener on a free loopback port. `allow` judges the addresses a
/// name resolves to on the direct route (the media listener keeps internal
/// networks out); on the Tor route the exit resolves names itself. In Tor every
/// destination host gets a circuit of its own.
pub async fn start(allow: fn(&IpAddr) -> bool) -> std::io::Result<SocketAddr> {
    spawn_listener(allow, Scope::new(true)).await.map(|(addr, _)| addr)
}

/// Like [`start`], but closed when the guard drops: for a connection that
/// lives as long as something else does (a persona's inbox).
pub async fn start_owned() -> std::io::Result<(SocketAddr, OnceListener)> {
    owned(Scope::new(true)).await
}

async fn owned(scope: Scope) -> std::io::Result<(SocketAddr, OnceListener)> {
    let renewals = scope.renewals.clone();
    spawn_listener(|_| true, scope).await.map(|(addr, task)| (addr, OnceListener { task, renewals }))
}

/// Like [`start_owned`], but through Tor even while private mode is off. With
/// Tor not running its connections fail: never a direct one.
pub async fn start_tor_only() -> std::io::Result<(SocketAddr, OnceListener)> {
    owned(Scope { tor_only: true, ..Scope::new(true) }).await
}

/// Closes a [`start_once`] / [`start_owned`] / [`start_tor_only`] listener when dropped.
pub struct OnceListener {
    task: tokio::task::JoinHandle<()>,
    renewals: std::sync::Arc<std::sync::atomic::AtomicU64>,
}

impl OnceListener {
    /// New Tor circuits for whatever connects through this listener from now
    /// on (the caller reconnects); other listeners keep theirs.
    pub fn renew(&self) {
        self.renewals.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
}

impl Drop for OnceListener {
    fn drop(&mut self) {
        self.task.abort();
    }
}

/// A listener for one short operation: everything it carries shares one Tor
/// circuit, apart from every other listener's. Closes when the guard drops;
/// tunnels already open run to their end.
pub async fn start_once() -> std::io::Result<(SocketAddr, OnceListener)> {
    owned(Scope::new(false)).await
}

async fn spawn_listener(
    allow: fn(&IpAddr) -> bool,
    scope: Scope,
) -> std::io::Result<(SocketAddr, tokio::task::JoinHandle<()>)> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
    let addr = listener.local_addr()?;
    let task = tokio::spawn(async move {
        loop {
            let Ok((sock, peer)) = listener.accept().await else { continue };
            // Only this process connects here; loopback is all we accept.
            if !peer.ip().is_loopback() {
                continue;
            }
            let scope = scope.clone();
            tokio::spawn(async move {
                if let Err(e) = serve(sock, allow, scope).await {
                    tracing::debug!("net switch: {e}");
                }
            });
        }
    });
    Ok((addr, task))
}

async fn serve(mut client: TcpStream, allow: fn(&IpAddr) -> bool, scope: Scope) -> std::io::Result<()> {
    let bad = |m: &str| std::io::Error::new(std::io::ErrorKind::InvalidData, m.to_string());

    // Greeting: version 5, any methods; we answer "no auth".
    let mut head = [0u8; 2];
    client.read_exact(&mut head).await?;
    if head[0] != 5 {
        return Err(bad("not socks5"));
    }
    let mut methods = vec![0u8; head[1] as usize];
    client.read_exact(&mut methods).await?;
    client.write_all(&[5, 0]).await?;

    // Request: only CONNECT.
    let mut req = [0u8; 4];
    client.read_exact(&mut req).await?;
    if req[0] != 5 || req[1] != 1 {
        client.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).await?;
        return Err(bad("only connect"));
    }
    let host = match req[3] {
        1 => {
            let mut b = [0u8; 4];
            client.read_exact(&mut b).await?;
            IpAddr::from(b).to_string()
        }
        4 => {
            let mut b = [0u8; 16];
            client.read_exact(&mut b).await?;
            IpAddr::from(b).to_string()
        }
        3 => {
            let mut n = [0u8; 1];
            client.read_exact(&mut n).await?;
            let mut b = vec![0u8; n[0] as usize];
            client.read_exact(&mut b).await?;
            String::from_utf8(b).map_err(|_| bad("bad name"))?
        }
        _ => return Err(bad("bad address type")),
    };
    let mut p = [0u8; 2];
    client.read_exact(&mut p).await?;
    let port = u16::from_be_bytes(p);

    let mut generation = route_changed().subscribe();
    let current = *generation.borrow_and_update();

    let route = if scope.tor_only { tor_port() } else { tor_route() };
    if scope.tor_only && route.is_none() {
        client.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).await?;
        return Err(std::io::Error::new(std::io::ErrorKind::NotConnected, "no Tor for a Tor-only listener"));
    }
    let dial = async {
        match route {
            Some(tor) => {
                let user = scope.credentials(current, &host);
                tokio_socks::tcp::Socks5Stream::connect_with_password(tor, (host.as_str(), port), &user, "x")
                    .await
                    .map(|s| s.into_inner())
                    .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))
            }
            None => connect_direct(&host, port, allow).await,
        }
    };
    // A dial over a network that just went away can hang for minutes: bound
    // it, and drop it the moment the network or the route changes.
    let timeout = if route.is_some() { TOR_DIAL_TIMEOUT } else { DIRECT_DIAL_TIMEOUT };
    let upstream = tokio::select! {
        r = tokio::time::timeout(timeout, dial) => r.unwrap_or_else(|_| {
            Err(std::io::Error::new(std::io::ErrorKind::TimedOut, "dial timed out"))
        }),
        _ = generation.changed() => Err(std::io::Error::new(std::io::ErrorKind::Interrupted, "network changed")),
    };
    let mut upstream = match upstream {
        Ok(s) => s,
        Err(e) => {
            client.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).await?;
            return Err(e);
        }
    };
    client.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await?;

    tokio::select! {
        r = tokio::io::copy_bidirectional(&mut client, &mut upstream) => r.map(|_| ()),
        _ = generation.changed() => Ok(()),
    }
}

const DIRECT_DIAL_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);
const TOR_DIAL_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);
/// Head start of each address over the next (RFC 8305).
const ATTEMPT_DELAY: std::time::Duration = std::time::Duration::from_millis(250);

async fn connect_direct(host: &str, port: u16, allow: fn(&IpAddr) -> bool) -> std::io::Result<TcpStream> {
    let addrs: Vec<SocketAddr> = tokio::net::lookup_host((host, port)).await?.collect();
    if addrs.is_empty() || addrs.iter().any(|a| !allow(&a.ip())) {
        return Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "destination not allowed"));
    }
    race_addresses(interleave_families(addrs)).await
}

/// IPv6 and IPv4 alternate, so a network with a broken family costs one
/// attempt delay rather than a dial timeout per address of that family.
fn interleave_families(addrs: Vec<SocketAddr>) -> Vec<SocketAddr> {
    let first_v6 = addrs.first().map_or(true, |a| a.is_ipv6());
    let (mut a, mut b): (std::collections::VecDeque<_>, std::collections::VecDeque<_>) =
        addrs.into_iter().partition(|x| x.is_ipv6() == first_v6);
    let mut out = Vec::with_capacity(a.len() + b.len());
    while !a.is_empty() || !b.is_empty() {
        out.extend(a.pop_front());
        out.extend(b.pop_front());
    }
    out
}

/// Each address starts [ATTEMPT_DELAY] after the previous one (sooner if it
/// fails); the first to connect wins and the rest are dropped.
async fn race_addresses(addrs: Vec<SocketAddr>) -> std::io::Result<TcpStream> {
    use futures_util::stream::{FuturesUnordered, StreamExt};
    let mut pending = addrs.into_iter();
    let mut attempts = FuturesUnordered::new();
    let mut last = None;
    loop {
        if let Some(a) = pending.next() {
            attempts.push(TcpStream::connect(a));
        } else if attempts.is_empty() {
            break;
        }
        let more = pending.len() > 0;
        tokio::select! {
            r = attempts.next(), if !attempts.is_empty() => match r {
                Some(Ok(s)) => return Ok(s),
                Some(Err(e)) => last = Some(e),
                None => {}
            },
            _ = tokio::time::sleep(ATTEMPT_DELAY), if more => {}
        }
        if !more {
            while let Some(r) = attempts.next().await {
                match r {
                    Ok(s) => return Ok(s),
                    Err(e) => last = Some(e),
                }
            }
            break;
        }
    }
    Err(last.unwrap_or_else(|| std::io::Error::new(std::io::ErrorKind::Other, "no address")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    /// The route is process-wide: tests that set it must not interleave.
    static ROUTE: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

    /// Stands in for Tor's SOCKS port: demands username/password, records each
    /// username, then accepts the CONNECT.
    async fn fake_tor() -> (SocketAddr, Arc<Mutex<Vec<String>>>) {
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let rec = Arc::clone(&seen);
        tokio::spawn(async move {
            loop {
                let (mut s, _) = l.accept().await.unwrap();
                let rec = Arc::clone(&rec);
                tokio::spawn(async move {
                    let mut h = [0u8; 2];
                    s.read_exact(&mut h).await?;
                    let mut m = vec![0u8; h[1] as usize];
                    s.read_exact(&mut m).await?;
                    if !m.contains(&2) {
                        return s.write_all(&[5, 0xff]).await;
                    }
                    s.write_all(&[5, 2]).await?;
                    let mut v = [0u8; 2];
                    s.read_exact(&mut v).await?;
                    let mut user = vec![0u8; v[1] as usize];
                    s.read_exact(&mut user).await?;
                    let mut pl = [0u8; 1];
                    s.read_exact(&mut pl).await?;
                    let mut pass = vec![0u8; pl[0] as usize];
                    s.read_exact(&mut pass).await?;
                    rec.lock().unwrap().push(String::from_utf8(user).unwrap());
                    s.write_all(&[1, 0]).await?;
                    let mut r = [0u8; 4];
                    s.read_exact(&mut r).await?;
                    let rest = match r[3] {
                        1 => 6,
                        4 => 18,
                        _ => {
                            let mut n = [0u8; 1];
                            s.read_exact(&mut n).await?;
                            n[0] as usize + 2
                        }
                    };
                    let mut b = vec![0u8; rest];
                    s.read_exact(&mut b).await?;
                    s.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await
                });
            }
        });
        (addr, seen)
    }

    async fn open(switch: SocketAddr, host: &str) {
        tokio_socks::tcp::Socks5Stream::connect(switch, (host, 443)).await.unwrap();
    }

    fn users(seen: &Arc<Mutex<Vec<String>>>) -> Vec<String> {
        seen.lock().unwrap().clone()
    }

    #[tokio::test]
    async fn each_destination_host_gets_its_own_circuit() {
        let _g = ROUTE.lock().await;
        let (tor, seen) = fake_tor().await;
        set_tor(Some(tor));
        let sw = start(|_| true).await.unwrap();
        open(sw, "relay-a.example").await;
        open(sw, "relay-a.example").await;
        open(sw, "relay-b.example").await;
        let u = users(&seen);
        assert_eq!(u.len(), 3);
        assert_eq!(u[0], u[1], "same host, same circuit");
        assert_ne!(u[0], u[2], "another host, another circuit");
        set_tor(None);
    }

    #[tokio::test]
    async fn one_off_listeners_share_nothing_with_each_other() {
        let _g = ROUTE.lock().await;
        let (tor, seen) = fake_tor().await;
        set_tor(Some(tor));
        let (a, _ga) = start_once().await.unwrap();
        let (b, _gb) = start_once().await.unwrap();
        let main = start(|_| true).await.unwrap();
        open(a, "relay-a.example").await;
        open(a, "relay-b.example").await;
        open(b, "relay-a.example").await;
        open(main, "relay-a.example").await;
        let u = users(&seen);
        assert_eq!(u[0], u[1], "one operation, one circuit");
        assert_ne!(u[0], u[2], "two operations, two circuits");
        assert_ne!(u[0], u[3], "an operation never shares the long-lived circuit");
        assert_ne!(u[2], u[3]);
        set_tor(None);
    }

    #[tokio::test]
    async fn a_route_change_gives_fresh_circuits() {
        let _g = ROUTE.lock().await;
        let (tor, seen) = fake_tor().await;
        set_tor(Some(tor));
        let sw = start(|_| true).await.unwrap();
        open(sw, "relay-a.example").await;
        set_tor(Some(tor));
        open(sw, "relay-a.example").await;
        let u = users(&seen);
        assert_ne!(u[0], u[1]);
        set_tor(None);
    }

    #[tokio::test]
    async fn a_tor_only_listener_uses_tor_while_private_mode_is_off() {
        let _g = ROUTE.lock().await;
        let (tor, seen) = fake_tor().await;
        set_tor(None);
        set_tor_port(Some(tor));
        let (sw, _guard) = start_tor_only().await.unwrap();
        open(sw, "relay-a.example").await;
        assert_eq!(users(&seen).len(), 1, "went through Tor");

        // Tor not running: no connection at all, never a direct one.
        let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let target_addr = target.local_addr().unwrap();
        set_tor_port(None);
        assert!(tokio_socks::tcp::Socks5Stream::connect(sw, target_addr).await.is_err());
        set_tor_port(None);
    }

    #[tokio::test]
    async fn renewing_a_listener_gives_it_new_circuits_only() {
        let _g = ROUTE.lock().await;
        let (tor, seen) = fake_tor().await;
        set_tor(None);
        set_tor_port(Some(tor));
        let (a, guard_a) = start_tor_only().await.unwrap();
        let (b, _guard_b) = start_tor_only().await.unwrap();
        open(a, "relay-a.example").await;
        open(b, "relay-a.example").await;
        guard_a.renew();
        open(a, "relay-a.example").await;
        open(b, "relay-a.example").await;
        let u = users(&seen);
        assert_ne!(u[0], u[2], "the renewed listener asks for a new circuit");
        assert_eq!(u[1], u[3], "the other listener keeps its circuit");
        set_tor_port(None);
    }

    #[tokio::test]
    async fn unreachable_tor_never_falls_back_to_direct() {
        let _g = ROUTE.lock().await;
        let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let target_addr = target.local_addr().unwrap();
        let reached = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let flag = Arc::clone(&reached);
        tokio::spawn(async move {
            if target.accept().await.is_ok() {
                flag.store(true, std::sync::atomic::Ordering::SeqCst);
            }
        });
        // A port nothing listens on: Tor is down.
        let dead = TcpListener::bind("127.0.0.1:0").await.unwrap().local_addr().unwrap();
        set_tor(Some(dead));
        let sw = start(|_| true).await.unwrap();
        let (once, _guard) = start_once().await.unwrap();
        assert!(tokio_socks::tcp::Socks5Stream::connect(sw, target_addr).await.is_err());
        assert!(tokio_socks::tcp::Socks5Stream::connect(once, target_addr).await.is_err());
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        assert!(!reached.load(std::sync::atomic::Ordering::SeqCst));
        set_tor(None);
    }

    #[tokio::test]
    async fn a_one_off_listener_closes_with_its_guard() {
        let (addr, guard) = start_once().await.unwrap();
        drop(guard);
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        assert!(TcpStream::connect(addr).await.is_err());
    }

    #[tokio::test]
    async fn direct_route_connects_and_relays_bytes() {
        let _g = ROUTE.lock().await;
        let echo = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let echo_addr = echo.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut s, _) = echo.accept().await.unwrap();
            let mut b = [0u8; 4];
            s.read_exact(&mut b).await.unwrap();
            s.write_all(&b).await.unwrap();
        });
        set_tor(None);
        let sw = start(|_| true).await.unwrap();
        let mut s = tokio_socks::tcp::Socks5Stream::connect(sw, echo_addr).await.unwrap();
        s.write_all(b"ping").await.unwrap();
        let mut b = [0u8; 4];
        s.read_exact(&mut b).await.unwrap();
        assert_eq!(&b, b"ping");
    }

    #[tokio::test]
    async fn filter_refuses_disallowed_destinations() {
        let _g = ROUTE.lock().await;
        set_tor(None);
        let sw = start(|ip| !ip.is_loopback()).await.unwrap();
        let r = tokio_socks::tcp::Socks5Stream::connect(sw, ("127.0.0.1", 9)).await;
        assert!(r.is_err());
    }
}
