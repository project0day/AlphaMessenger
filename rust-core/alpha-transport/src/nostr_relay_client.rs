//! WebSocket Nostr relay client.
//!
//! [`NostrRelayClient`] wraps a `nostr_sdk::Client` to provide high-level
//! operations for connecting to relays, publishing events, subscribing, and
//! querying events. The underlying `Client` handles reconnection, relay pool
//! management, and event signing internally.

use std::sync::Arc;
use std::time::Duration;

use nostr::{Event, EventBuilder, EventId, Filter, Keys, Kind, PublicKey, SubscriptionId, Timestamp};
use nostr::util::JsonUtil; // PIPE_TRACE: Event::try_as_json for the publish-size log
use nostr_sdk::Client;
use tokio::sync::RwLock;

use crate::envelope::MlsEnvelope;
use crate::error::{Result, TransportError};
use crate::relay_config::RelayPoolConfig;
use crate::sealed::{self, OwnEvents, SharedOwnEvents, TransportKeyRing, KIND_SEALED_GROUP, KIND_SEALED_PRESENCE, TAG_ROUTE};

/// Kind used downstream for an opened sealed group event (the inner MLS envelope).
pub const KIND_MLS_ENVELOPE_INNER: u16 = 443;

// --- Network proxy (Tor / Orbot) ---

static NETWORK_PROXY: std::sync::RwLock<Option<std::net::SocketAddr>> = std::sync::RwLock::new(None);

/// Route every relay connection created after this call through a SOCKS5 proxy
/// (e.g. Orbot at 127.0.0.1:9050). `None` = direct connections.
pub fn set_network_proxy(addr: Option<std::net::SocketAddr>) {
    if let Ok(mut p) = NETWORK_PROXY.write() {
        *p = addr;
    }
}

pub fn network_proxy() -> Option<std::net::SocketAddr> {
    NETWORK_PROXY.read().ok().and_then(|p| *p)
}

static GROUP_PROXY: std::sync::RwLock<Option<std::net::SocketAddr>> = std::sync::RwLock::new(None);

/// The switch listener of the group connection: its own Tor circuits, so a
/// relay cannot tie our group tags to the connection that names our npub.
pub fn set_group_proxy(addr: Option<std::net::SocketAddr>) {
    if let Ok(mut p) = GROUP_PROXY.write() {
        *p = addr;
    }
}

fn group_proxy() -> Option<std::net::SocketAddr> {
    let own = GROUP_PROXY.read().ok().and_then(|p| *p);
    if own.is_none() && network_proxy().is_some() {
        tracing::error!(target: "TRANSPORT", "group connection has no listener of its own: it shares the identity's circuits");
    }
    own.or_else(network_proxy)
}

/// The proxy for one short-lived client: a switch listener of its own, so in
/// Tor it shares no circuit with the long-lived connections or with other
/// operations. Keep the guard until the client is shut down. No listener, no
/// connection: falling back to a shared one would tie this operation to it.
async fn one_off_proxy() -> Result<(Option<std::net::SocketAddr>, Option<crate::net_switch::OnceListener>)> {
    if network_proxy().is_none() {
        return Ok((None, None));
    }
    let (addr, guard) = crate::net_switch::start_once().await
        .map_err(|e| TransportError::Nostr(format!("one-off listener: {e}")))?;
    Ok((Some(addr), Some(guard)))
}

fn route_tag(event: &Event) -> Option<sealed::Tag16> {
    event.tags.iter().find_map(|t| {
        let s = t.as_slice();
        if s.len() >= 2 && s[0] == TAG_ROUTE { sealed::parse_tag(&s[1]) } else { None }
    })
}

// ---------------------------------------------------------------------------
// RawMlsEvent
// ---------------------------------------------------------------------------

/// A raw MLS event received from a Nostr relay, before decryption.
///
/// Contains the event metadata and the base64-encoded MLS ciphertext
/// payload. The `payload_base64` field holds the raw content from the
/// Nostr event, which should be decoded and passed to the MLS session
/// manager for decryption.
///
/// For **kind-1059 gift-wrapped** events the `original_event_json` field
/// carries the full JSON-serialised `nostr::Event` so that the catch-up
/// layer can reconstruct it and pass it to `GiftWrapReader::unwrap()`.
/// Without this, gift-wraps can never be opened (the content alone is
/// not enough â€” NIP-59 requires the seal pubkey/sig too).
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RawMlsEvent {
    /// The Nostr event ID.
    pub event_id: String,
    /// The relay URL this event was received from.
    pub relay_url: String,
    /// Unix timestamp of the event.
    pub created_at: u64,
    /// Base64-encoded MLS ciphertext payload (for kind-443) or JSON content
    /// of the seal (for kind-1059 before unwrapping).
    pub payload_base64: String,
    /// The Nostr event kind (443 for MLS envelope, 1059 for gift-wrapped).
    pub kind: u16,
    /// Full JSON of the original nostr::Event, populated for kind-1059
    /// gift-wrapped events so they can be reconstructed and unwrapped.
    /// `None` for kind-443 events where the content base64 is sufficient.
    #[serde(default)]
    pub original_event_json: Option<String>,
    /// Hex of the Nostr event author's public key. For kind-443 group
    /// application messages this is the sender's real nostr pubkey â€” we
    /// use it to map an MLS leaf signature key â†’ nostr npub so the UI can
    /// add/DM group members. Empty for gift-wraps (author is ephemeral).
    #[serde(default)]
    pub author_pubkey_hex: String,
}

// ---------------------------------------------------------------------------
// NostrRelayClient
// ---------------------------------------------------------------------------

/// WebSocket Nostr relay client backed by `nostr_sdk::Client`.
///
/// Thread-safe: the inner `Client` is `Send + Sync`, and all mutable state
/// (like the connected flag) is protected by a `RwLock`.
///
/// # Relay Pool with Read/Write Flags
///
/// Each relay can be configured with independent `read` and `write` flags:
/// - **Read relays** are used for subscribing to events and queries.
/// - **Write relays** are used for publishing events.
/// - A relay can be both read and write (the default).
/// - Disabled relays are kept in config but not connected.
///
/// # Tor (Private Mode)
///
/// When `tor_enabled` is set to `true`, the client expects a local SOCKS5
/// proxy (e.g. Orbot on Android, or a system Tor service) to be running
/// at `127.0.0.1:9050`. All WebSocket connections are routed through this
/// proxy, hiding the user's IP address from relays.
///
/// **Implementation:** We use the SOCKS5 proxy approach rather than the
/// built-in `nostr-sdk/tor` feature because the latter causes a
/// `libsqlite3-sys` version conflict with our SQLCipher database.
/// The SOCKS5 proxy approach is more flexible: it works with Orbot,
/// system Tor, or any SOCKS5-compatible proxy.
///
/// **Note:** NIP-59 gift-wrap + MLS already hides message metadata.
/// Tor adds **IP-level** anonymity on top of that.
///
/// **No relay chaining:** Nostr relays don't route messages between each
/// other. Tor is the ONLY way to hide IP. There is no "relay chain like Tor"
/// â€” that's not how Nostr works.
#[derive(Debug, Clone)]
pub struct NostrRelayClient {
    /// The identity connection: only what is addressed to or signed by our
    /// npub (its gift wraps, its KeyPackages).
    client: Conn,
    /// The group connection, under a random key: sealed group events and
    /// presence, both ways. Nothing on it names us.
    groups: Conn,
    /// One connection per persona inbox, so an anonymous admin is tied
    /// neither to our npub nor to our groups.
    personas: Arc<std::sync::Mutex<std::collections::HashMap<String, PersonaConn>>>,
    /// Every connection's notifications, merged for the realtime listener.
    merged: tokio::sync::broadcast::Sender<nostr_sdk::RelayPoolNotification>,
    forwarding: Arc<std::sync::atomic::AtomicBool>,
    /// Our npub: events it signs go out on the identity connection.
    identity: Arc<std::sync::RwLock<Option<PublicKey>>>,
    persona_sync: Arc<tokio::sync::Mutex<()>>,
    /// Groups where we speak as a persona: their events travel on that
    /// persona's connection only, never beside our other groups.
    persona_groups: Arc<std::sync::RwLock<std::collections::HashMap<Vec<u8>, PublicKey>>>,
    /// Personas whose connection goes through Tor even with private mode off.
    tor_only: Arc<std::sync::RwLock<std::collections::HashSet<PublicKey>>>,
    /// Per persona: the relays its chat uses and when it last had traffic.
    persona_plans: Arc<std::sync::RwLock<std::collections::HashMap<PublicKey, PersonaPlan>>>,
    /// Personas held awake until then: their chat is on screen, or a send waits.
    persona_woken: Arc<std::sync::Mutex<std::collections::HashMap<PublicKey, std::time::Instant>>>,
    /// Sleeping personas (no connection) as of the last evaluation.
    persona_asleep: Arc<std::sync::RwLock<std::collections::HashSet<PublicKey>>>,
    /// Relay URLs that this client should connect to.
    relay_urls: Arc<RwLock<Vec<String>>>,
    /// Per-relay read/write configuration.
    relay_flags: Arc<RwLock<std::collections::HashMap<String, RelayFlags>>>,
    /// Whether the client is currently connected.
    connected: Arc<RwLock<bool>>,
    /// Whether Tor (private mode) is enabled for all relay connections.
    /// When true, all WebSocket traffic is routed through the Tor network.
    tor_enabled: Arc<RwLock<bool>>,
    /// P1 relay health score per URL: +1 on publish ACK, -1 on publish failure.
    /// Used for visibility/diagnostics (publish still goes to ALL write relays â€”
    /// strictly more reliable than top-N on Nostr).
    relay_scores: Arc<RwLock<std::collections::HashMap<String, i32>>>,
    /// Epoch keys of our groups: seal outgoing, open incoming, build filters.
    key_ring: Arc<TransportKeyRing>,
    /// Ids of events we published (their echo must not reach MLS again).
    own_events: SharedOwnEvents,
    /// Hex routing tags of our contacts' presence channels.
    presence_tags: Arc<std::sync::RwLock<Vec<String>>>,
    /// Inboxes of our personas (anonymous channels): gift wraps addressed to
    /// them are ours too.
    persona_inboxes: Arc<std::sync::RwLock<Vec<PublicKey>>>,
    /// Groups whose events must expire on relays ("don't keep on servers").
    /// Per-group "don't keep on servers" overrides; groups not listed follow the default.
    no_store_groups: Arc<std::sync::RwLock<std::collections::HashMap<Vec<u8>, bool>>>,
    no_store_default: Arc<std::sync::atomic::AtomicBool>,
    /// Relays that told us to slow down ("rate-limited", "banned"): skipped
    /// for publishing until their pause ends (url â†’ until, strikes). Hammering
    /// such a relay only extends the ban.
    cooldown: Arc<std::sync::Mutex<std::collections::HashMap<String, (std::time::Instant, u32)>>>,
    /// Subscriptions a relay will not serve without AUTH, which we never do
    /// ((relay, subscription id) → until): not asked there again for a while,
    /// while the relay keeps serving everything else.
    sub_refusals: Arc<std::sync::Mutex<std::collections::HashMap<(String, String), std::time::Instant>>>,
    /// Times of the recent publishes: an even pace instead of bursts.
    pace: Arc<tokio::sync::Mutex<std::collections::VecDeque<std::time::Instant>>>,
    /// The device has a network (as the OS reports it). Without one nothing
    /// dials: failed attempts only drain the battery.
    network_up: Arc<std::sync::atomic::AtomicBool>,
    /// Relays seen `Pending` (connect asked, no connection task), since when.
    pending_since: Arc<std::sync::Mutex<std::collections::HashMap<String, std::time::Instant>>>,
    /// When each (connection, relay) was last restarted by `revive_stuck_relays`.
    revived_at: Arc<std::sync::Mutex<std::collections::HashMap<String, std::time::Instant>>>,
    /// NIP-42: the latest challenge of each (connection, relay).
    auth_challenges: Arc<std::sync::Mutex<std::collections::HashMap<(u64, String), String>>>,
    /// NIP-42: when each (connection, relay) last answered one, and how often in a row.
    auth_tries: Arc<std::sync::Mutex<std::collections::HashMap<(u64, String), (std::time::Instant, u32)>>>,
    /// (relay, subscription) refused for want of AUTH: answered at its next challenge.
    auth_wanted: Arc<std::sync::Mutex<std::collections::HashSet<(String, String)>>>,
    auth_signer: Arc<std::sync::RwLock<Option<SignerSlot>>>,
    /// When each (connection, relay) was last reconnected to get a NIP-42 challenge.
    auth_redials: Arc<std::sync::Mutex<std::collections::HashMap<(u64, String), std::time::Instant>>>,
    /// Events a relay refused for want of AUTH, per (connection, relay): sent again after it.
    auth_held: Arc<std::sync::Mutex<std::collections::HashMap<(u64, String), Vec<Event>>>>,
    /// Relays each person listens on (pubkey hex), as they told us over MLS.
    inbox_routes: Arc<std::sync::RwLock<std::collections::HashMap<String, Vec<String>>>>,
    /// Relays the other members of each group listen on, most shared first.
    group_routes: Arc<std::sync::RwLock<std::collections::HashMap<Vec<u8>, Vec<String>>>>,
    /// Relays a subscription was held back from (paused, or just authenticated):
    /// the heartbeat asks them once they may be asked.
    resub_later: Arc<std::sync::Mutex<std::collections::HashSet<String>>>,
    /// Relays that keep failing, per connection: not redialled until a probe finds them up.
    parking: Arc<std::sync::Mutex<crate::relay_parking::Parking>>,
    supervising: Arc<std::sync::atomic::AtomicBool>,
    /// Recipient relays connected write-only, per connection: when last used.
    write_only: Arc<std::sync::Mutex<std::collections::HashMap<(u64, String), std::time::Instant>>>,
    /// Relays of our groups we read from (group events only) because the other
    /// members do not write where we listen.
    listen_extra: Arc<std::sync::RwLock<Vec<String>>>,
    publish_stats: Arc<std::sync::Mutex<std::collections::HashMap<String, PublishStats>>>,
}

/// A persona's own connection and the switch listener only it uses.
struct PersonaConn {
    client: Conn,
    listener: Option<crate::net_switch::OnceListener>,
    /// The switch listener's address: this connection's route.
    proxy: Option<std::net::SocketAddr>,
}

impl std::fmt::Debug for PersonaConn {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("PersonaConn")
    }
}

/// Which connection an event goes out on.
enum Lane {
    Identity,
    Groups,
    Persona(Conn, PublicKey),
    /// Signed by an unrelated key (a backup locator, a sticker pack): a
    /// short-lived connection of its own.
    Detached,
    /// A persona group's event while that persona has no connection: it
    /// waits, it never borrows another connection.
    Held,
}

/// How long a send waits for a persona's connection to come up through Tor.
const PERSONA_CONNECT_WAIT: Duration = Duration::from_secs(60);

/// A persona's chat with no traffic for this long sleeps: no connection,
/// only a short visit every [PERSONA_VISIT_EVERY].
const PERSONA_IDLE_SECS: u64 = 24 * 3600;
/// How often a sleeping persona's relays are visited, and for how long.
const PERSONA_VISIT_EVERY: u64 = 15 * 60;
const PERSONA_VISIT_FOR: u64 = 90;
/// A wake (chat on screen, a send) holds the connection at least this long.
const PERSONA_WAKE_HOLD: Duration = Duration::from_secs(180);

/// The personas that sleep at [now]: quiet for a day, not held awake, and
/// not in the shared visit window (all sleepers visit together, one burst).
fn sleeping(
    plans: &std::collections::HashMap<PublicKey, PersonaPlan>,
    woken: &std::collections::HashSet<PublicKey>,
    now: u64,
) -> std::collections::HashSet<PublicKey> {
    if now % PERSONA_VISIT_EVERY < PERSONA_VISIT_FOR { return Default::default(); }
    plans.iter()
        .filter(|(pk, p)| p.may_sleep && !woken.contains(*pk) && now.saturating_sub(p.active_at) > PERSONA_IDLE_SECS)
        .map(|(pk, _)| *pk)
        .collect()
}

/// How a persona's connection is run.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct PersonaPlan {
    /// The relays its chat agreed on; empty = all of ours.
    pub relays: Vec<String>,
    /// Unix secs of the chat's last message: the subscription reaches back to
    /// it, and long silence lets the connection sleep.
    pub active_at: u64,
    /// Whether it may sleep at all (secret chats; anonymous channels stay up).
    pub may_sleep: bool,
}

enum LaneKind {
    Persona,
    Shared,
}

/// Most relays one event goes to (ours + the recipients').
const MAX_TARGETS: usize = 8;

/// Publishes allowed per second (see `keep_pace`).
const PACE_MAX: usize = 4;

/// One spelling of a relay url ("wss://x" and "wss://x/" are the same relay).
fn relay_key(url: &str) -> String {
    url.trim().trim_end_matches('/').to_ascii_lowercase()
}

/// Timeouts in a row per relay (a dead socket the pool still calls connected).
fn stale_map() -> &'static std::sync::Mutex<std::collections::HashMap<String, u32>> {
    static M: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<String, u32>>> = std::sync::OnceLock::new();
    M.get_or_init(Default::default)
}

/// One more timeout from [url]; returns how many in a row.
fn stale_count(url: &str) -> u32 {
    stale_map().lock().map(|mut m| {
        let n = m.entry(relay_key(url)).or_insert(0);
        *n += 1;
        *n
    }).unwrap_or(0)
}

fn stale_reset(url: &str) {
    if let Ok(mut m) = stale_map().lock() { m.remove(&relay_key(url)); }
}

/// A relay said "no" in a way a retry cannot fix soon (NIP-01 prefixes): slow
/// down, banned, auth required, blocked. A timeout or a dropped socket is not
/// a refusal â€” that is worth sending again.
/// Live subscription ids, one per connection kind.
const SUB_IDENTITY: &str = "alpha-mls-giftwrap";
const SUB_GROUPS: &str = "alpha-groups";
const SUB_PERSONA: &str = "alpha-persona";

/// How long a relay that wants AUTH for a subscription is not asked for it.
const SUB_REFUSAL_SECS: u64 = 6 * 60 * 60;

fn is_refusal(reason: &str) -> bool {
    let r = reason.to_ascii_lowercase();
    r.contains("rate-limit") || r.contains("rate limit") || r.contains("ratelimit")
        || r.contains("banned") || r.contains("too many") || r.contains("too much") || r.contains("slow down")
        || r.contains("auth-required") || r.contains("blocked") || r.contains("restricted")
}

/// Per-relay read/write/enable flags.
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct RelayFlags {
    /// Whether to subscribe to events from this relay.
    pub(crate) read: bool,
    /// Whether to publish events to this relay.
    pub(crate) write: bool,
    /// Whether this relay is enabled.
    pub(crate) enabled: bool,
}

/// Snapshot returned to the FFI for the Relays settings screen.
#[derive(Debug, Clone)]
pub struct RelayStatusSnapshot {
    pub url: String,
    pub read: bool,
    pub write: bool,
    pub enabled: bool,
    /// Whether the underlying WebSocket reports as connected right now.
    pub connected: bool,
    /// Latest measured round-trip in milliseconds, or None if not measured.
    pub ping_ms: Option<u64>,
    /// Seconds since the last connection attempt, if known.
    pub last_attempt_secs_ago: Option<u64>,
    /// "connected", "connecting", "unreachable" (parked), "limited" (asked us to wait), "disabled".
    pub state: &'static str,
    /// Seconds until the next probe (unreachable) or the end of the pause (limited).
    pub retry_secs: u64,
    /// Our inbox (gift wraps) is served only after AUTH, which we never give.
    pub inbox_auth: bool,
    /// Our events it took and refused since the app started.
    pub accepted: u32,
    pub rejected: u32,
    /// The relay's own words for its last refusal; empty when the last event went in.
    pub last_error: String,
}

/// What one relay did with our events (shared connections).
#[derive(Debug, Clone, Default)]
struct PublishStats {
    accepted: u32,
    rejected: u32,
    last_error: String,
}

impl RelayFlags {
    pub(crate) fn new(read: bool, write: bool, enabled: bool) -> Self {
        Self { read, write, enabled }
    }
}

/// Build a nostr-sdk `Client` with relay-pool size limits disabled.
///
/// **Why this matters**: nostr-sdk defaults to a ~70 KB max event size on
/// the receiver side. Our `AlphaMessage::ProfileUpdate` carries a
/// base64-encoded avatar JPEG plus the MLS ciphertext wrapper â€” easily
/// 60-100 KB on the wire. Without lifting the limit, peers silently drop
/// our profile broadcasts with `Received event too large: size=88016,
/// max_size=71680`, which is exactly what we observed when one phone
/// updated profile and the other never re-rendered.
fn build_client_with_lifted_limits(proxy: Option<std::net::SocketAddr>) -> Conn {
    use nostr_sdk::{ClientBuilder, ClientOptions};
    let limits = relay_limits();
    // No automatic NIP-42 AUTH: a relay whose AUTH is broken (Damus answered
    // every attempt with a config error) got a retry every ~0.3 s from each
    // connection â€” counted as abuse, it banned us ("too many rate-limit
    // violations"). Our relays need no AUTH; the answer was a random key anyway.
    // No `verify_subscriptions`: it drops an event that arrives a moment after
    // its one-off query closed, and that late copy is what saves a message the
    // live subscription missed. Size limits and our own signature check stay.
    let mut opts = ClientOptions::default().relay_limits(limits).automatic_authentication(false);
    if let Some(addr) = proxy {
        tracing::info!("Relay connections via SOCKS5 proxy {}", addr);
        opts = opts.connection(nostr_sdk::client::Connection::new().proxy(addr));
    }
    // Relays that require NIP-42 AUTH get a random per-process key: the
    // connection authenticates without revealing the user's identity.
    // Relay connection changes come from the monitor, not the pool notifications.
    let client = ClientBuilder::default()
        .signer(Keys::generate())
        .opts(opts)
        .monitor(nostr_sdk::pool::monitor::Monitor::new(256))
        .build();
    static NEXT_LANE: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
    Conn { client, proxy, id: NEXT_LANE.fetch_add(1, std::sync::atomic::Ordering::Relaxed) }
}

/// A dropped socket is retried this often, for as long as it stays down.
const RELAY_RETRY_INTERVAL: Duration = Duration::from_secs(3);

/// One pool connection. Every relay it adds gets [`Conn::relay_options`]: the
/// SDK's own `add_relay` gives them a backoff that grows with every failure the
/// relay has ever had and never shrinks, so after a few network drops a relay
/// waits a minute between attempts for the rest of the process.
#[derive(Debug, Clone)]
pub(crate) struct Conn {
    client: Client,
    proxy: Option<std::net::SocketAddr>,
    /// Which connection this is (clones share it): the key of its parking state.
    id: u64,
}

fn off_relays() -> &'static std::sync::RwLock<std::collections::HashSet<String>> {
    static OFF: std::sync::OnceLock<std::sync::RwLock<std::collections::HashSet<String>>> = std::sync::OnceLock::new();
    OFF.get_or_init(|| std::sync::RwLock::new(std::collections::HashSet::new()))
}

/// The user switched this relay off: every connection, the one-off ones too, leaves it alone.
fn switched_off(url: &str) -> bool {
    off_relays().read().map(|s| s.contains(&relay_key(url))).unwrap_or(false)
}

fn set_switched_off(url: &str, off: bool) {
    if let Ok(mut s) = off_relays().write() {
        if off { s.insert(relay_key(url)); } else { s.remove(&relay_key(url)); }
    }
}

fn sync_switched_off(flags: &std::collections::HashMap<String, RelayFlags>) {
    if let Ok(mut s) = off_relays().write() {
        *s = flags.iter().filter(|(_, f)| !f.enabled).map(|(u, _)| relay_key(u)).collect();
    }
}

/// The keys an inbox (our npub or a persona's) proves itself with on NIP-42.
/// None asks for the account key.
pub type AuthSigner = Arc<dyn Fn(Option<&PublicKey>) -> Option<Keys> + Send + Sync>;

#[derive(Clone)]
struct SignerSlot(AuthSigner);

impl std::fmt::Debug for SignerSlot {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { f.write_str("SignerSlot") }
}

impl std::ops::Deref for Conn {
    type Target = Client;
    fn deref(&self) -> &Client {
        &self.client
    }
}

impl Conn {
    fn relay_options(&self, flags: nostr_sdk::pool::RelayServiceFlags) -> nostr_sdk::pool::RelayOptions {
        let opts = nostr_sdk::pool::RelayOptions::new()
            .flags(flags)
            .limits(relay_limits())
            .retry_interval(RELAY_RETRY_INTERVAL)
            .adjust_retry_interval(false);
        match self.proxy {
            Some(addr) => opts.connection_mode(nostr_sdk::pool::ConnectionMode::Proxy(addr)),
            None => opts,
        }
    }

    /// Read and write. `false` when the relay was already there, or is one
    /// the user switched off (no connection of ours ever opens to it).
    pub async fn add_relay(&self, url: &str) -> std::result::Result<bool, nostr_sdk::pool::pool::Error> {
        if switched_off(url) { return Ok(false); }
        self.client.pool().add_relay(url, self.relay_options(nostr_sdk::pool::RelayServiceFlags::default())).await
    }

    /// Read only (a relay of our group's we only listen on).
    pub async fn add_read_relay(&self, url: &str) -> std::result::Result<bool, nostr_sdk::pool::pool::Error> {
        use nostr_sdk::pool::RelayServiceFlags as F;
        if switched_off(url) { return Ok(false); }
        self.client.pool().add_relay(url, self.relay_options(F::PING | F::READ)).await
    }

    /// Write only (a recipient's relay we don't otherwise use).
    pub async fn add_write_relay(&self, url: &str) -> std::result::Result<bool, nostr_sdk::pool::pool::Error> {
        use nostr_sdk::pool::RelayServiceFlags as F;
        if switched_off(url) { return Ok(false); }
        self.client.pool().add_relay(url, self.relay_options(F::PING | F::WRITE)).await
    }
}

/// A relay's connection task sees a termination request on its next poll; a
/// connect issued before then is dropped by the SDK ("already running"), so
/// a restart waits this long between the two.
const RESTART_GRACE: Duration = Duration::from_millis(250);

/// Our connections dial a relay one after another, this far apart: all at once,
/// several from one address in the same instant, a relay answers 429.
const LANE_STAGGER: Duration = Duration::from_millis(500);

/// `Pending` longer than this means the connect was dropped: no task runs.
const PENDING_STUCK: Duration = Duration::from_secs(10);
/// Pause between the subscriptions of two persona inboxes.
const PERSONA_SUB_STAGGER: Duration = Duration::from_millis(300);
/// The first NIP-42 retry waits this long; each failed one doubles it, up to an hour.
const AUTH_RETRY_BASE: Duration = Duration::from_secs(30);
/// A relay is restarted at most this often.
const REVIVE_EVERY: Duration = Duration::from_secs(60);
/// How long a restart waits for the old connection task to stop.
const RESTART_WAIT: Duration = Duration::from_secs(1);

/// How often the parking supervisor looks at the pools (local state only).
const PARKING_TICK: Duration = Duration::from_secs(5);

/// The project's own relay: joined only while the user has no relay that works,
/// so a phone never loses contact because every public relay is gone or deleted.
pub const RESERVE_RELAY: &str = "wss://rs-nostr-relay.rs-nostr-relay.workers.dev";
/// At most this many group relays read beside our own.
const MAX_LISTEN_EXTRA: usize = 4;
/// How long none of the user's relays may be down before the reserve joins.
const RESERVE_AFTER: Duration = Duration::from_secs(90);
/// How long a user relay must stay up before the reserve leaves again.
const RESERVE_HOLD: Duration = Duration::from_secs(600);

static RESERVE_ON: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
/// (none of the user's relays up since, one of them up since).
static RESERVE_CLOCK: std::sync::Mutex<(Option<std::time::Instant>, Option<std::time::Instant>)> =
    std::sync::Mutex::new((None, None));

/// We are on the reserve relay: it is in the pool, but it is not the user's.
pub fn reserve_active() -> bool {
    RESERVE_ON.load(std::sync::atomic::Ordering::SeqCst)
}

fn is_reserve(url: &str) -> bool {
    relay_key(url) == relay_key(RESERVE_RELAY)
}

/// Ours, not the user's: hidden from their list and never saved into it.
fn reserve_slot(url: &str) -> bool {
    reserve_active() && is_reserve(url)
}
/// One probe's connect deadline.
const PROBE_TIMEOUT: Duration = Duration::from_secs(10);
/// A recipient relay connected for a send is let go after this long unused.
const WRITE_ONLY_IDLE: Duration = Duration::from_secs(600);

/// Size limits lifted for our largest messages (see the client builder above).
fn relay_limits() -> nostr_sdk::pool::RelayLimits {
    use nostr_sdk::pool::RelayLimits;
    // Big enough for our largest message (a profile with its avatar, a history
    // batch), small enough that a hostile relay cannot exhaust memory.
    let mut limits = RelayLimits::default();
    limits.messages.max_size = Some(2 * 1024 * 1024);
    limits.events.max_size = Some(2 * 1024 * 1024);
    limits.events.max_num_tags = Some(500);
    // Our own kinds have known shapes: anything far beyond them is dropped
    // before it is parsed further. Group events and gift wraps carry at most a
    // profile with its picture or a large group's Welcome; presence is tiny.
    for (kind, size, tags) in [
        (KIND_SEALED_GROUP, 1024 * 1024, 8),
        (KIND_SEALED_PRESENCE, 16 * 1024, 8),
        (Kind::GiftWrap.as_u16(), 1024 * 1024, 8),
        (443, 1024 * 1024, 32),
    ] {
        limits.events.max_size_per_kind.insert(Kind::from(kind), Some(size));
        limits.events.max_num_tags_per_kind.insert(Kind::from(kind), Some(tags));
    }
    limits
}

impl NostrRelayClient {
    /// Create a new client for the given relay URLs.
    ///
    /// All relays are configured with read=true, write=true, enabled=true.
    /// Does **not** connect immediately â€” call [`connect`](Self::connect) to
    /// establish the WebSocket connections.
    pub fn new(relay_urls: Vec<String>) -> Self {
        let client = build_client_with_lifted_limits(network_proxy());
        let flags: std::collections::HashMap<String, RelayFlags> = relay_urls
            .iter()
            .map(|url| (url.clone(), RelayFlags::new(true, true, true)))
            .collect();
        Self {
            client,
            groups: build_client_with_lifted_limits(group_proxy()),
            personas: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            merged: tokio::sync::broadcast::channel(4096).0,
            forwarding: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            identity: Arc::new(std::sync::RwLock::new(None)),
            persona_sync: Arc::new(tokio::sync::Mutex::new(())),
            persona_groups: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            tor_only: Arc::new(std::sync::RwLock::new(std::collections::HashSet::new())),
            persona_plans: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            persona_woken: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            persona_asleep: Arc::new(std::sync::RwLock::new(std::collections::HashSet::new())),
            relay_urls: Arc::new(RwLock::new(relay_urls)),
            relay_flags: Arc::new(RwLock::new(flags)),
            connected: Arc::new(RwLock::new(false)),
            tor_enabled: Arc::new(RwLock::new(false)),
            relay_scores: Arc::new(RwLock::new(std::collections::HashMap::new())),
            key_ring: Arc::new(TransportKeyRing::default()),
            own_events: Arc::new(std::sync::Mutex::new(OwnEvents::default())),
            presence_tags: Arc::new(std::sync::RwLock::new(Vec::new())),
            persona_inboxes: Arc::new(std::sync::RwLock::new(Vec::new())),
            no_store_groups: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            no_store_default: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            cooldown: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            sub_refusals: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            pace: Arc::new(tokio::sync::Mutex::new(std::collections::VecDeque::new())),
            network_up: Arc::new(std::sync::atomic::AtomicBool::new(true)),
            pending_since: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            revived_at: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            auth_challenges: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            auth_tries: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            auth_wanted: Arc::new(std::sync::Mutex::new(std::collections::HashSet::new())),
            auth_signer: Arc::new(std::sync::RwLock::new(None)),
            auth_redials: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            auth_held: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            inbox_routes: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            group_routes: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            resub_later: Arc::new(std::sync::Mutex::new(std::collections::HashSet::new())),
            parking: Arc::new(std::sync::Mutex::new(Default::default())),
            supervising: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            write_only: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            listen_extra: Arc::new(std::sync::RwLock::new(Vec::new())),
            publish_stats: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
        }
    }

    /// Create a client from a [`RelayPoolConfig`].
    ///
    /// Reads the read/write/enabled flags from each relay configuration
    /// and applies them when connecting. Only enabled relays are connected.
    pub fn from_config(config: &RelayPoolConfig) -> Self {
        let client = build_client_with_lifted_limits(network_proxy());
        let urls: Vec<String> = config.relays.iter().map(|r| r.url.clone()).collect();
        let flags: std::collections::HashMap<String, RelayFlags> = config
            .relays
            .iter()
            .map(|r| (r.url.clone(), RelayFlags::new(r.read, r.write, r.enabled)))
            .collect();
        Self {
            client,
            groups: build_client_with_lifted_limits(group_proxy()),
            personas: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            merged: tokio::sync::broadcast::channel(4096).0,
            forwarding: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            identity: Arc::new(std::sync::RwLock::new(None)),
            persona_sync: Arc::new(tokio::sync::Mutex::new(())),
            persona_groups: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            tor_only: Arc::new(std::sync::RwLock::new(std::collections::HashSet::new())),
            persona_plans: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            persona_woken: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            persona_asleep: Arc::new(std::sync::RwLock::new(std::collections::HashSet::new())),
            relay_urls: Arc::new(RwLock::new(urls)),
            relay_flags: Arc::new(RwLock::new(flags)),
            connected: Arc::new(RwLock::new(false)),
            tor_enabled: Arc::new(RwLock::new(false)),
            relay_scores: Arc::new(RwLock::new(std::collections::HashMap::new())),
            key_ring: Arc::new(TransportKeyRing::default()),
            own_events: Arc::new(std::sync::Mutex::new(OwnEvents::default())),
            presence_tags: Arc::new(std::sync::RwLock::new(Vec::new())),
            persona_inboxes: Arc::new(std::sync::RwLock::new(Vec::new())),
            no_store_groups: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            no_store_default: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            cooldown: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            sub_refusals: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            pace: Arc::new(tokio::sync::Mutex::new(std::collections::VecDeque::new())),
            network_up: Arc::new(std::sync::atomic::AtomicBool::new(true)),
            pending_since: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            revived_at: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            auth_challenges: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            auth_tries: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            auth_wanted: Arc::new(std::sync::Mutex::new(std::collections::HashSet::new())),
            auth_signer: Arc::new(std::sync::RwLock::new(None)),
            auth_redials: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            auth_held: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            inbox_routes: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            group_routes: Arc::new(std::sync::RwLock::new(std::collections::HashMap::new())),
            resub_later: Arc::new(std::sync::Mutex::new(std::collections::HashSet::new())),
            parking: Arc::new(std::sync::Mutex::new(Default::default())),
            supervising: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            write_only: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            listen_extra: Arc::new(std::sync::RwLock::new(Vec::new())),
            publish_stats: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
        }
    }

    /// Create a client with default public relays.
    ///
    /// Uses [`RelayPoolConfig::default_relays()`] for the relay list.
    pub fn with_default_relays() -> Self {
        Self::from_config(&RelayPoolConfig::default_relays())
    }

    // -----------------------------------------------------------------------
    // Sealed transport
    // -----------------------------------------------------------------------

    pub fn key_ring(&self) -> Arc<TransportKeyRing> {
        Arc::clone(&self.key_ring)
    }

    pub fn set_no_store(&self, group_id: &[u8], enabled: bool) {
        if let Ok(mut g) = self.no_store_groups.write() {
            g.insert(group_id.to_vec(), enabled);
        }
    }

    /// New default for every group; clears the per-group overrides.
    pub fn set_no_store_default(&self, enabled: bool) {
        self.no_store_default.store(enabled, std::sync::atomic::Ordering::Relaxed);
        if let Ok(mut g) = self.no_store_groups.write() {
            g.clear();
        }
    }

    pub fn is_no_store(&self, group_id: &[u8]) -> bool {
        let over = self.no_store_groups.read().ok().and_then(|g| g.get(group_id).copied());
        over.unwrap_or_else(|| self.no_store_default.load(std::sync::atomic::Ordering::Relaxed))
    }

    pub fn mark_own_event(&self, id: &str) {
        if let Ok(mut o) = self.own_events.lock() {
            o.insert(id.to_string());
        }
    }

    pub fn is_own_event(&self, id: &str) -> bool {
        self.own_events.lock().map(|o| o.contains(id)).unwrap_or(false)
    }

    /// Replace the presence routing tags. Returns true if they changed.
    /// Set the inboxes of our personas. Returns true when the set changed
    /// (the caller then resubscribes).
    pub fn set_persona_inboxes(&self, mut inboxes: Vec<PublicKey>) -> bool {
        inboxes.sort_by_key(|k| k.to_hex());
        inboxes.dedup();
        match self.persona_inboxes.write() {
            Ok(mut cur) if *cur != inboxes => {
                *cur = inboxes;
                true
            }
            _ => false,
        }
    }

    /// Which groups we speak in as which persona, and which personas go
    /// through Tor only. Returns true when anything changed (resubscribe).
    pub fn set_persona_routes(
        &self,
        groups: std::collections::HashMap<Vec<u8>, PublicKey>,
        tor_only: std::collections::HashSet<PublicKey>,
    ) -> bool {
        let mut changed = false;
        if let Ok(mut g) = self.persona_groups.write() {
            if *g != groups { *g = groups; changed = true; }
        }
        if let Ok(mut t) = self.tor_only.write() {
            if *t != tor_only { *t = tor_only; changed = true; }
        }
        changed
    }

    fn persona_of_group(&self, group_id: &[u8]) -> Option<PublicKey> {
        self.persona_groups.read().ok().and_then(|g| g.get(group_id).copied())
    }

    pub fn set_presence_tags(&self, mut tags: Vec<String>) -> bool {
        tags.sort();
        tags.dedup();
        match self.presence_tags.write() {
            Ok(mut cur) if *cur != tags => {
                *cur = tags;
                true
            }
            _ => false,
        }
    }

    /// Convert a relay event into a [`RawMlsEvent`] for the MLS pipeline.
    ///
    /// - kind 1059 (gift wrap) is passed through with its full JSON;
    /// - kind 445 (sealed group event) is opened with the epoch key of its
    ///   routing tag; the result carries the inner envelope as kind 443 and an
    ///   empty author (the outer signer is a one-time key);
    /// - our own echoes, unknown tags and anything else yield `None`.
    pub fn raw_from_event(&self, event: &Event, relay_url: &str) -> Option<RawMlsEvent> {
        // Checked here too, whatever the pool did: catch-up results come this way.
        if event.verify().is_err() {
            tracing::warn!("dropping unverifiable event from {}", relay_url);
            return None;
        }
        let kind = event.kind.as_u16();
        let event_id = event.id.to_hex();
        match kind {
            1059 => Some(RawMlsEvent {
                event_id,
                relay_url: relay_url.to_string(),
                created_at: event.created_at.as_u64(),
                payload_base64: event.content.clone(),
                kind,
                original_event_json: serde_json::to_string(event).ok(),
                author_pubkey_hex: event.pubkey.to_hex(),
            }),
            KIND_SEALED_GROUP => {
                if self.is_own_event(&event_id) {
                    return None;
                }
                let tag = route_tag(event)?;
                let key = self.key_ring.lookup(&tag)?;
                let bytes = sealed::unseal(&key.key, &tag, &event.content)?;
                let envelope = MlsEnvelope::from_bytes(&bytes).ok()?;
                // The tag is bound to one group: reject a mismatching envelope.
                if envelope.group_id != key.group_id {
                    tracing::warn!("sealed event {} names a different group than its tag", event_id);
                    return None;
                }
                Some(RawMlsEvent {
                    event_id,
                    relay_url: relay_url.to_string(),
                    created_at: event.created_at.as_u64(),
                    payload_base64: envelope.to_base64(),
                    kind: KIND_MLS_ENVELOPE_INNER,
                    original_event_json: None,
                    author_pubkey_hex: String::new(),
                })
            }
            _ => None,
        }
    }

    /// Open a sealed presence heartbeat with the matching contact key.
    /// Returns the routing tag (to identify the contact) and the payload.
    pub fn open_presence(&self, event: &Event, key_for_tag: impl Fn(&sealed::Tag16) -> Option<sealed::Key32>) -> Option<(sealed::Tag16, Vec<u8>)> {
        if event.kind.as_u16() != KIND_SEALED_PRESENCE || self.is_own_event(&event.id.to_hex()) {
            return None;
        }
        let tag = route_tag(event)?;
        let key = key_for_tag(&tag)?;
        sealed::unseal(&key, &tag, &event.content).map(|p| (tag, p))
    }

    fn sealed_group_filter(tags: Vec<String>) -> Filter {
        use nostr::{Alphabet, SingleLetterTag};
        Filter::new()
            .kind(Kind::from(KIND_SEALED_GROUP))
            .custom_tags(SingleLetterTag::lowercase(Alphabet::H), tags)
    }

    /// Publish a sealed presence heartbeat on the given routing tag.
    pub async fn publish_sealed_presence(&self, tag: &sealed::Tag16, key: &sealed::Key32, payload: &[u8]) -> Result<EventId> {
        let content = sealed::seal(key, tag, payload)
            .ok_or_else(|| TransportError::PublishFailed("presence seal failed".into()))?;
        let expires = Timestamp::from(Timestamp::now().as_u64() + 600);
        let event = EventBuilder::new(Kind::from(KIND_SEALED_PRESENCE), content)
            .tags([
                nostr::Tag::custom(nostr::TagKind::custom(TAG_ROUTE), [hex::encode(tag)]),
                nostr::Tag::expiration(expires),
            ])
            .sign_with_keys(&Keys::generate())
            .map_err(|e| TransportError::PublishFailed(format!("presence sign: {e}")))?;
        self.mark_own_event(&event.id.to_hex());
        self.publish(event).await
    }

    // -----------------------------------------------------------------------
    // Tor (Private Mode)
    // -----------------------------------------------------------------------

    /// Enable or disable Tor (Private Mode) for all relay connections.
    ///
    /// When enabled, all WebSocket traffic is routed through the Tor network,
    /// hiding the user's IP address from relays. This adds latency but
    /// provides network-level anonymity.
    ///
    /// **Requires reconnect** after toggling: call `disconnect()` then
    /// `connect()` for the change to take effect.
    ///
    /// **Note:** NIP-59 gift-wrap + MLS already hides message metadata
    /// (who sent what to whom). Tor adds **IP-level** anonymity on top.
    ///
    /// **No relay chaining:** Nostr relays don't route messages between
    /// each other. Tor is the ONLY way to hide your IP from relays.
    pub async fn set_tor_enabled(&self, enabled: bool) {
        let mut tor = self.tor_enabled.write().await;
        *tor = enabled;
        tracing::info!(
            "Tor (Private Mode) {} â€” reconnect required for changes to take effect",
            if enabled { "ENABLED" } else { "DISABLED" },
        );
    }

    /// Whether Tor (Private Mode) is currently enabled.
    pub async fn is_tor_enabled(&self) -> bool {
        *self.tor_enabled.read().await
    }

    /// Get a snapshot of the current relay configuration for UI display.
    ///
    /// Returns a list of `(url, read, write, enabled)` tuples.
    pub async fn relay_config_snapshot(&self) -> Vec<(String, bool, bool, bool)> {
        let urls = self.relay_urls.read().await;
        let flags = self.relay_flags.read().await;
        urls.iter()
            .filter(|url| !reserve_slot(url))
            .map(|url| {
                let f = flags.get(url).copied().unwrap_or(RelayFlags::new(true, true, true));
                (url.clone(), f.read, f.write, f.enabled)
            })
            .collect()
    }

    /// Join or leave the reserve relay. The user's list rules: none at all (all
    /// deleted) → join at once; all switched off → never (a choice to be
    /// offline); some on but none reachable for [RESERVE_AFTER] → join; leave
    /// once one has stayed up for [RESERVE_HOLD]. Returns the new state on a change.
    pub async fn reserve_tick(&self) -> Option<bool> {
        if !self.network_available() {
            return None;
        }
        let on = reserve_active();
        let flags = self.relay_flags.read().await.clone();
        let own: Vec<String> = self.relay_urls.read().await.iter().filter(|u| !reserve_slot(u)).cloned().collect();
        let enabled: Vec<String> = own.iter().filter(|u| flags.get(*u).map(|f| f.enabled).unwrap_or(true)).cloned().collect();
        let want = if own.is_empty() {
            true
        } else if enabled.is_empty() || enabled.iter().any(|u| is_reserve(u)) {
            false
        } else {
            let pool = self.client.pool();
            let mut up = false;
            for u in &enabled {
                if let Ok(ru) = nostr::RelayUrl::parse(u) {
                    if let Ok(r) = pool.relay(&ru).await {
                        if r.status() == nostr_sdk::RelayStatus::Connected { up = true; break; }
                    }
                }
            }
            let now = std::time::Instant::now();
            let mut clock = RESERVE_CLOCK.lock().ok()?;
            if up {
                clock.0 = None;
                let since = *clock.1.get_or_insert(now);
                on && now.duration_since(since) < RESERVE_HOLD
            } else {
                clock.1 = None;
                let since = *clock.0.get_or_insert(now);
                on || now.duration_since(since) >= RESERVE_AFTER
            }
        };
        if want == on {
            return None;
        }
        if want {
            if let Err(e) = self.add_relay_with_flags(RESERVE_RELAY, true, true, true).await {
                tracing::warn!(target: "TRANSPORT", "RESERVE join failed: {e}");
                return None;
            }
            RESERVE_ON.store(true, std::sync::atomic::Ordering::SeqCst);
            tracing::warn!(target: "TRANSPORT", "RESERVE joined: no relay of the user's works");
        } else {
            RESERVE_ON.store(false, std::sync::atomic::Ordering::SeqCst);
            let _ = self.remove_relay(RESERVE_RELAY).await;
            tracing::warn!(target: "TRANSPORT", "RESERVE left: the user's relays work again");
        }
        Some(want)
    }

    // -----------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------

    /// Connect to all **enabled** relays.
    ///
    /// Skips disabled relays. For each enabled relay, the read/write flags
    /// determine whether it's used for subscribing, publishing, or both.
    /// If a relay is already added, it will not be added again.
    pub async fn connect(&self) -> Result<()> {
        let urls = self.relay_urls.read().await;
        let flags = self.relay_flags.read().await;
        sync_switched_off(&flags);

        let mut connected_count = 0;
        let mut skipped_count = 0;

        for url in urls.iter() {
            let relay_flags = flags.get(url).copied().unwrap_or(RelayFlags::new(true, true, true));

            if !relay_flags.enabled {
                tracing::debug!("Skipping disabled relay: {}", url);
                skipped_count += 1;
                continue;
            }

            for conn in [&self.client, &self.groups] {
                conn.add_relay(url.as_str())
                    .await
                    .map_err(|e| TransportError::ConnectionFailed {
                        url: url.clone(),
                        reason: e.to_string(),
                    })?;
            }

            tracing::debug!(
                "Added relay {} (read={}, write={})",
                url,
                relay_flags.read,
                relay_flags.write,
            );
            connected_count += 1;
        }

        drop(urls);
        drop(flags);
        self.start_forwarding();
        self.start_parking();
        self.client.connect().await;
        tokio::time::sleep(LANE_STAGGER).await;
        self.groups.connect().await;
        // connect() is fire-and-forget â€” sockets are still handshaking when it
        // returns. WAIT until at least one relay is really Connected, otherwise
        // the immediate subscribe / KeyPackage publish in start_sync fail with
        // "not connected to relay" (a fresh account then never publishes its
        // KeyPackage â†’ peers can't add it).
        self.wait_until_connected(std::time::Duration::from_secs(8)).await;

        let mut connected = self.connected.write().await;
        *connected = true;

        tracing::info!(
            "Connected to {} relay(s), skipped {} disabled",
            connected_count,
            skipped_count,
        );
        Ok(())
    }

    /// (connected, total) enabled relays right now â€” for status logging.
    pub async fn connected_relay_count(&self) -> (usize, usize) {
        let urls = self.relay_urls.read().await.clone();
        let pool = self.client.pool();
        let total = urls.len();
        let mut connected = 0usize;
        for url in urls {
            if let Ok(ru) = nostr::RelayUrl::parse(&url) {
                if let Ok(r) = pool.relay(&ru).await {
                    if format!("{:?}", r.status()) == "Connected" { connected += 1; }
                }
            }
        }
        (connected, total)
    }

    /// Poll the relay pool until at least one relay reaches `Connected` or the
    /// timeout elapses. Returns true if connected. Logs how many relays are up.
    pub async fn wait_until_connected(&self, timeout: std::time::Duration) -> bool {
        let start = std::time::Instant::now();
        loop {
            if self.is_connected().await {
                let (c, t) = self.connected_relay_count().await;
                tracing::info!(target: "TRANSPORT", "connect complete in {:?} â€” connected_relays={}/{}", start.elapsed(), c, t);
                return true;
            }
            if start.elapsed() >= timeout {
                let (c, t) = self.connected_relay_count().await;
                tracing::warn!(target: "TRANSPORT", "wait_until_connected timed out after {:?} â€” connected_relays={}/{}", timeout, c, t);
                return false;
            }
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
        }
    }

    /// Disconnect from all relays.
    pub async fn disconnect(&self) -> Result<()> {
        for conn in self.connections() {
            conn.disconnect().await;
        }

        let mut connected = self.connected.write().await;
        *connected = false;

        tracing::info!("Disconnected from all relays");
        Ok(())
    }

    /// Whether at least one enabled relay socket is REALLY connected. Queries
    /// the live relay-pool status instead of trusting a manual flag (a stale
    /// `connected=true` after airplane mode / network change was making the
    /// client publish into half-dead sockets â€” TRANSPORT hardening).
    pub async fn is_connected(&self) -> bool {
        self.any_connected(&self.client).await && self.any_connected(&self.groups).await
    }

    async fn any_connected(&self, conn: &Client) -> bool {
        let urls = self.relay_urls.read().await.clone();
        let pool = conn.pool();
        for url in urls {
            if let Ok(ru) = nostr::RelayUrl::parse(&url) {
                if let Ok(r) = pool.relay(&ru).await {
                    if format!("{:?}", r.status()) == "Connected" {
                        return true;
                    }
                }
            }
        }
        false
    }

    /// Force a clean reconnect: drop all sockets, then reconnect the pool.
    /// Used on network-available / app-foreground so we don't rely on the
    /// SDK's passive reconnect (which can leave half-open sockets after an
    /// airplane-mode window). Caller must re-subscribe afterwards.
    pub async fn force_reconnect(&self) -> Result<()> {
        tracing::info!(target: "TRANSPORT", "force_reconnect: disconnecting all relays");
        self.restart_connections().await;
        // Wait for real connectivity before returning so the caller's resubscribe
        // doesn't fail with "not connected to relay".
        self.wait_until_connected(std::time::Duration::from_secs(8)).await;
        *self.connected.write().await = true;
        tracing::info!(target: "TRANSPORT", "force_reconnect: reconnect issued");
        Ok(())
    }

    /// Every connection closes its sockets and dials again at once; a relay
    /// waiting out its retry interval is woken. Subscriptions stay with each
    /// relay and are sent again as it connects.
    async fn restart_connections(&self) {
        if !self.network_available() {
            return;
        }
        let conns = self.connections();
        for conn in &conns {
            conn.disconnect().await;
        }
        tokio::time::sleep(RESTART_GRACE).await;
        // The identity connection at once; the others one after another behind it,
        // without holding up the caller (subscriptions are sent as each comes up).
        let mut rest = conns.into_iter();
        if let Some(first) = rest.next() {
            self.connect_lane(&first).await;
        }
        let me = self.clone();
        tokio::spawn(async move {
            for conn in rest {
                tokio::time::sleep(LANE_STAGGER).await;
                me.connect_lane(&conn).await;
            }
        });
    }

    /// Dial every relay of [conn] except the ones parked on it.
    async fn connect_lane(&self, conn: &Conn) {
        let parked = self.parking.lock().map(|p| p.parked_on(conn.id)).unwrap_or_default();
        for (url, relay) in conn.relays().await {
            if !parked.contains(&relay_key(&url.to_string())) {
                relay.connect();
            }
        }
    }

    fn start_parking(&self) {
        if self.supervising.swap(true, std::sync::atomic::Ordering::SeqCst) {
            return;
        }
        let me = self.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(PARKING_TICK).await;
                me.parking_tick().await;
            }
        });
    }

    /// Park the relays that keep failing, probe the parked ones that are due,
    /// and let go of recipient relays nothing was sent to for a while.
    async fn parking_tick(&self) {
        use crate::relay_parking::Verdict;
        if !self.network_available() {
            return;
        }
        let lanes = self.connections();
        let now = std::time::Instant::now();
        let any_up = self.any_connected(&self.client).await || self.any_connected(&self.groups).await;
        let mut nothing_up = !any_up;
        if nothing_up {
            for c in &lanes { if self.any_connected(c).await { nothing_up = false; break; } }
        }
        if let Ok(mut p) = self.parking.lock() {
            p.retain_lanes(&lanes.iter().map(|c| c.id).collect());
        }
        self.drop_idle_write_only(&lanes, now).await;
        let identity_lane = self.client.id;
        for conn in lanes {
            for (url, relay) in conn.relays().await {
                let key = relay_key(&url.to_string());
                // Nothing reachable at all (no route, a firewall): only the
                // identity connection looks for the way back; the others would
                // knock on the same relays for nothing and wake when it is found.
                if nothing_up && conn.id != identity_lane
                    && self.parking.lock().map(|p| p.is_parked(conn.id, &key)).unwrap_or(false) {
                    continue;
                }
                let stats = relay.stats();
                let connected = relay.status() == nostr_sdk::RelayStatus::Connected;
                let verdict = match self.parking.lock() {
                    Ok(mut p) => p.observe(conn.id, &key, connected, stats.attempts(), stats.success(), now),
                    Err(_) => continue,
                };
                match verdict {
                    Verdict::Leave => {}
                    Verdict::Park => {
                        relay.disconnect();
                        let wait = self.parking.lock().map(|mut p| p.park(conn.id, &key, any_up, now)).unwrap_or_default();
                        tracing::warn!(target: "TRANSPORT", "RELAY_PARKED {key} (lane {}): not reachable, next try in {}s", conn.id, wait.as_secs());
                    }
                    Verdict::Probe => {
                        let parking = Arc::clone(&self.parking);
                        let lane = conn.id;
                        let me = self.clone();
                        tokio::spawn(async move {
                            let ok = relay.try_connect(PROBE_TIMEOUT).await.is_ok();
                            if ok && nothing_up && lane == identity_lane {
                                // The way back is there: the silent lanes dial again now.
                                for c in me.connections() {
                                    if c.id == identity_lane { continue; }
                                    if let Ok(mut p) = parking.lock() { p.clear_lane(c.id); }
                                    me.connect_lane(&c).await;
                                }
                            }
                            if let Ok(mut p) = parking.lock() {
                                if ok {
                                    p.probe_ok(lane, &key);
                                    tracing::warn!(target: "TRANSPORT", "RELAY_BACK {key} (lane {lane})");
                                } else {
                                    let wait = p.probe_failed(lane, &key, any_up, std::time::Instant::now());
                                    tracing::info!(target: "TRANSPORT", "RELAY_PARKED {key} (lane {lane}): still down, next try in {}s", wait.as_secs());
                                }
                            }
                        });
                    }
                }
            }
        }
    }

    /// Recipient relays added for a send and unused for [WRITE_ONLY_IDLE] leave
    /// the pool: no socket, no pings, no redials for them.
    async fn drop_idle_write_only(&self, lanes: &[Conn], now: std::time::Instant) {
        let idle: Vec<(u64, String)> = match self.write_only.lock() {
            Ok(mut w) => {
                w.retain(|(l, _), _| lanes.iter().any(|c| c.id == *l));
                let idle: Vec<(u64, String)> = w.iter()
                    .filter(|(_, at)| now.duration_since(**at) >= WRITE_ONLY_IDLE)
                    .map(|(k, _)| k.clone())
                    .collect();
                for k in &idle {
                    w.remove(k);
                }
                idle
            }
            Err(_) => return,
        };
        if idle.is_empty() {
            return;
        }
        let mut own: std::collections::HashSet<String> = self.relay_urls.read().await.iter().map(|u| relay_key(u)).collect();
        own.extend(self.listen_extra().iter().map(|u| relay_key(u)));
        for (lane, url) in idle {
            if own.contains(&url) {
                continue;
            }
            if let Some(conn) = lanes.iter().find(|c| c.id == lane) {
                let _ = conn.remove_relay(url.as_str()).await;
                tracing::info!(target: "TRANSPORT", "write-only relay {url} let go (unused {}s)", WRITE_ONLY_IDLE.as_secs());
            }
        }
    }

    /// Our relays parked on the identity connection (not reachable right now).
    pub fn parked_relays(&self) -> Vec<String> {
        self.parking.lock().map(|p| p.parked_on(self.client.id).into_iter().collect()).unwrap_or_default()
    }

    /// The OS reported the device's network: gained, switched (Wi-Fi to
    /// mobile and back) or lost. Sockets of the old network are closed at
    /// once instead of dying slowly. With a network every relay dials now;
    /// without one nothing dials until it comes back.
    pub async fn set_network_available(&self, available: bool) {
        use std::sync::atomic::Ordering;
        self.network_up.store(available, Ordering::Release);
        crate::net_switch::network_changed();
        if let Ok(mut p) = self.parking.lock() {
            p.clear();
        }
        if available {
            tracing::warn!(target: "TRANSPORT", "NETWORK up: reconnecting every relay now");
            self.restart_connections().await;
        } else {
            tracing::warn!(target: "TRANSPORT", "NETWORK lost: relays closed until it returns");
            for conn in self.connections() {
                conn.disconnect().await;
            }
        }
    }

    pub fn network_available(&self) -> bool {
        self.network_up.load(std::sync::atomic::Ordering::Acquire)
    }

    /// Relays whose connect the SDK dropped (left `Pending` with no task)
    /// are restarted; without this they would stay down for good.
    pub async fn revive_stuck_relays(&self) {
        if !self.network_available() {
            return;
        }
        let now = std::time::Instant::now();
        let conns = self.connections();
        // Keyed by the connection itself: persona connections come and go, and
        // a position in the list would hand one relay's waiting time to another.
        let mut seen = std::collections::HashSet::new();
        for conn in &conns {
            for (url, relay) in conn.relays().await {
                let key = format!("{}|{url}", conn.id);
                seen.insert(key.clone());
                if relay.status() != nostr_sdk::RelayStatus::Pending {
                    if let Ok(mut p) = self.pending_since.lock() { p.remove(&key); }
                    continue;
                }
                let since = self.pending_since.lock().ok()
                    .map(|mut p| *p.entry(key.clone()).or_insert(now))
                    .unwrap_or(now);
                if now.duration_since(since) < PENDING_STUCK {
                    continue;
                }
                // Once a minute at most for one relay: a restart that did not
                // help is not repeated every few seconds.
                let recent = self.revived_at.lock().ok()
                    .and_then(|r| r.get(&key).copied())
                    .is_some_and(|at| now.duration_since(at) < REVIVE_EVERY);
                if recent { continue; }
                tracing::warn!(target: "TRANSPORT", "RELAY_STUCK {url}: connect was dropped, restarting it");
                if let Ok(mut p) = self.pending_since.lock() { p.remove(&key); }
                if let Ok(mut r) = self.revived_at.lock() { r.insert(key.clone(), now); }
                relay.disconnect();
                // A connect issued while the old task still winds down is dropped
                // by the SDK ("already running"): give it time to stop first.
                tokio::time::sleep(RESTART_WAIT).await;
                relay.connect();
            }
        }
        if let Ok(mut p) = self.pending_since.lock() { p.retain(|k, _| seen.contains(k)); }
        if let Ok(mut r) = self.revived_at.lock() { r.retain(|k, at| seen.contains(k) && now.duration_since(*at) < REVIVE_EVERY); }
    }

    /// Get the list of relay URLs.
    pub async fn relay_urls(&self) -> Vec<String> {
        self.relay_urls.read().await.clone()
    }

    /// Snapshot of every configured relay with its current connection state
    /// and a freshly measured ping. Used by the Settings â†’ Relays screen so
    /// the user can see which relays are alive and which to disable.
    pub async fn relay_statuses(&self) -> Vec<RelayStatusSnapshot> {
        let urls = self.relay_urls.read().await.clone();
        let flags = self.relay_flags.read().await.clone();
        let pool = self.client.pool();
        let now = std::time::Instant::now();
        let cooldown = self.cooldown.lock().map(|c| c.clone()).unwrap_or_default();
        let inbox_refused = self.refused_for(SUB_IDENTITY);
        let stats = self.publish_stats.lock().map(|s| s.clone()).unwrap_or_default();
        let mut out = Vec::with_capacity(urls.len());
        for url in urls {
            let f = flags.get(&url).copied().unwrap_or(RelayFlags { read: true, write: true, enabled: true });
            let parsed = nostr::RelayUrl::parse(&url).ok();
            let (connected, last_attempt_secs_ago) = if let Some(ru) = parsed.clone() {
                match pool.relay(&ru).await {
                    Ok(r) => {
                        let status = r.status();
                        // Stringly check â€” nostr_sdk re-exports differ
                        // between v0.37 minor revisions. "Connected" is the
                        // stable wire representation.
                        let connected = format!("{:?}", status) == "Connected";
                        (connected, None::<u64>)
                    }
                    Err(_) => (false, None),
                }
            } else {
                (false, None)
            };
            let key = relay_key(&url);
            let parked = self.parking.lock().ok().and_then(|p| p.retry_in(self.client.id, &key, now));
            let paused = cooldown.get(&key).filter(|(until, _)| *until > now).map(|(until, _)| *until - now);
            let (state, retry) = if !f.enabled {
                ("disabled", None)
            } else if parked.is_some() {
                ("unreachable", parked)
            } else if paused.is_some() {
                ("limited", paused)
            } else if connected {
                ("connected", None)
            } else {
                ("connecting", None)
            };
            let s = stats.get(&key).cloned().unwrap_or_default();
            out.push(RelayStatusSnapshot {
                url,
                read: f.read,
                write: f.write,
                enabled: f.enabled,
                connected,
                ping_ms: None,
                last_attempt_secs_ago,
                state,
                retry_secs: retry.map(|d| d.as_secs().max(1)).unwrap_or(0),
                inbox_auth: inbox_refused.contains(&key),
                accepted: s.accepted,
                rejected: s.rejected,
                last_error: s.last_error,
            });
        }
        out
    }

    /// Ping every relay by attempting a TCP-level connect to the
    /// websocket host and measuring the time. Doesn't depend on nostr-sdk
    /// per-relay APIs which differ across minor versions. Returns
    /// `ping_ms = None` if the host couldn't be reached within `timeout`.
    pub async fn ping_relays(
        &self,
        timeout: std::time::Duration,
    ) -> Vec<(String, Option<u64>)> {
        // Switched-off relays are not measured: the user asked for no traffic to them.
        let urls: Vec<String> = self.relay_urls.read().await.iter().filter(|u| !switched_off(u)).cloned().collect();
        let mut futs = Vec::with_capacity(urls.len());
        for url in urls {
            let url_clone = url.clone();
            futs.push(async move {
                let ping = measure_tcp_ping(&url_clone, timeout).await;
                (url_clone, ping)
            });
        }
        futures_util::future::join_all(futs).await
    }

    /// Add a relay URL at runtime with default flags (read+write, enabled).
    pub async fn add_relay(&self, url: &str) -> Result<()> {
        self.add_relay_with_flags(url, true, true, true).await
    }

    /// Add a relay URL at runtime with custom read/write/enabled flags.
    pub async fn add_relay_with_flags(
        &self,
        url: &str,
        read: bool,
        write: bool,
        enabled: bool,
    ) -> Result<()> {
        // The user adds the reserve relay themselves: from now on it is theirs.
        if is_reserve(url) {
            RESERVE_ON.store(false, std::sync::atomic::Ordering::SeqCst);
        }
        // A group relay we only read becomes a full one of the user's.
        self.forget_listen_extra(url).await;
        {
            let mut urls = self.relay_urls.write().await;
            if urls.iter().any(|u| u == url) {
                // Update flags for existing relay
                let mut flags = self.relay_flags.write().await;
                flags.insert(url.to_string(), RelayFlags::new(read, write, enabled));
                set_switched_off(url, !enabled);
                return Ok(());
            }
            urls.push(url.to_string());
        }

        // Store the flags
        {
            let mut flags = self.relay_flags.write().await;
            flags.insert(url.to_string(), RelayFlags::new(read, write, enabled));
            set_switched_off(url, !enabled);
        }

        if !enabled {
            tracing::debug!("Added disabled relay: {}", url);
            return Ok(());
        }

        // Connect it right away: also when it is the ONLY relay (every other
        // one was removed), nothing is "already connected" and waiting for
        // that would leave the new relay silent until a restart.
        for conn in self.connections() {
            conn.add_relay(url)
                .await
                .map_err(|e| TransportError::ConnectionFailed {
                    url: url.to_string(),
                    reason: e.to_string(),
                })?;
            conn.connect_relay(url)
                .await
                .map_err(|e| TransportError::ConnectionFailed {
                    url: url.to_string(),
                    reason: e.to_string(),
                })?;
        }

        tracing::debug!("Added relay {} (read={}, write={}, enabled={})", url, read, write, enabled);
        Ok(())
    }

    /// Update the read/write/enabled flags for a relay.
    pub async fn update_relay_flags(
        &self,
        url: &str,
        read: Option<bool>,
        write: Option<bool>,
        enabled: Option<bool>,
    ) -> Result<()> {
        if let Some(on) = enabled { set_switched_off(url, !on); }
        // A relay switched on at runtime: a disabled relay was never put in the
        // pool, so add + connect it now (no-op when it is already there).
        if enabled == Some(true) {
            if let Ok(mut p) = self.parking.lock() {
                p.clear_url(&relay_key(url));
            }
            let known = self.relay_flags.read().await.contains_key(url);
            if known {
                for conn in self.connections() {
                    let _ = conn.add_relay(url).await;
                    let _ = conn.connect_relay(url).await;
                }
            }
        }
        // Switched off: leave the pool at once â€” no more subscriptions, sends or
        // waits on it. Its settings stay, so switching it on brings it back.
        if enabled == Some(false) {
            for conn in self.connections() {
                let _ = conn.remove_relay(url).await;
            }
        }
        let mut flags = self.relay_flags.write().await;
        if let Some(existing) = flags.get_mut(url) {
            if let Some(r) = read {
                existing.read = r;
            }
            if let Some(w) = write {
                existing.write = w;
            }
            if let Some(e) = enabled {
                existing.enabled = e;
            }
            tracing::debug!(
                "Updated relay {} flags: read={}, write={}, enabled={}",
                url, existing.read, existing.write, existing.enabled,
            );
            Ok(())
        } else {
            Err(TransportError::ConnectionFailed {
                url: url.to_string(),
                reason: "relay not found".to_string(),
            })
        }
    }

    /// Get the relay flags for a specific relay.
    pub async fn relay_flags(&self, url: &str) -> (bool, bool, bool) {
        let flags = self.relay_flags.read().await;
        let f = flags.get(url).copied().unwrap_or(RelayFlags::new(true, true, true));
        (f.read, f.write, f.enabled)
    }

    /// Get the list of enabled relay URLs.
    pub async fn enabled_relay_urls(&self) -> Vec<String> {
        let urls = self.relay_urls.read().await;
        let flags = self.relay_flags.read().await;
        urls.iter()
            .filter(|url| {
                flags.get(*url).map(|f| f.enabled).unwrap_or(true)
            })
            .cloned()
            .collect()
    }

    /// Drop [url] from the group relays we only read (the next route refresh may add it back).
    async fn forget_listen_extra(&self, url: &str) {
        let had = self.listen_extra.write().map(|mut l| {
            let n = l.len();
            l.retain(|e| relay_key(e) != relay_key(url));
            n != l.len()
        }).unwrap_or(false);
        if had {
            let _ = self.groups.remove_relay(url).await;
        }
    }

    /// Remove a relay URL at runtime.
    pub async fn remove_relay(&self, url: &str) -> Result<()> {
        self.forget_listen_extra(url).await;
        {
            let mut urls = self.relay_urls.write().await;
            urls.retain(|u| u != url);
        }
        {
            let mut flags = self.relay_flags.write().await;
            flags.remove(url);
        }

        for conn in self.connections() {
            conn.remove_relay(url)
                .await
                .map_err(|e| TransportError::Nostr(e.to_string()))?;
        }

        Ok(())
    }

    /// Get a reference to the inner nostr-sdk [`Client`].
    ///
    /// Useful for advanced operations not exposed by this wrapper.
    pub fn inner(&self) -> &Client {
        &self.client
    }

    /// Get a broadcast receiver for live relay pool notifications.
    ///
    /// Used by the realtime listener loop in the FFI layer to receive
    /// every event the pool publishes to subscribers.
    pub fn notifications(&self) -> tokio::sync::broadcast::Receiver<nostr_sdk::RelayPoolNotification> {
        self.merged.subscribe()
    }

    /// Identity, groups and every persona connection.
    fn connections(&self) -> Vec<Conn> {
        let mut all = vec![self.client.clone(), self.groups.clone()];
        if let Ok(p) = self.personas.lock() {
            all.extend(p.values().map(|c| c.client.clone()));
        }
        all
    }

    fn start_forwarding(&self) {
        if !self.forwarding.swap(true, std::sync::atomic::Ordering::SeqCst) {
            self.forward(&self.client, true);
            self.forward(&self.groups, false);
        }
    }

    /// Pass [conn]'s notifications to the merged stream. Only the identity
    /// connection's shutdown ends the listener; a persona's goes with it alone.
    fn forward(&self, conn: &Conn, pass_shutdown: bool) {
        use nostr_sdk::RelayPoolNotification;
        use tokio::sync::broadcast::error::RecvError;
        let mut rx = conn.notifications();
        let tx = self.merged.clone();
        let me = self.clone();
        let lane = conn.id;
        tokio::spawn(async move {
            loop {
                let got = rx.recv().await;
                if let Ok(RelayPoolNotification::Message { relay_url, message: nostr_sdk::RelayMessage::Auth { challenge } }) = &got {
                    me.note_auth_challenge(lane, relay_url.as_str(), challenge);
                }
                match got {
                    Ok(RelayPoolNotification::Shutdown) => {
                        if pass_shutdown {
                            let _ = tx.send(RelayPoolNotification::Shutdown);
                        }
                        return;
                    }
                    Ok(n) => {
                        let _ = tx.send(n);
                    }
                    Err(RecvError::Lagged(n)) => tracing::warn!("relay notifications: {} dropped (catch-up covers them)", n),
                    Err(RecvError::Closed) => return,
                }
            }
        });
    }

    /// Our npub: what it signs goes out on the identity connection only.
    pub fn set_identity(&self, pk: PublicKey) {
        if let Ok(mut i) = self.identity.write() {
            *i = Some(pk);
        }
    }

    fn lane(&self, event: &Event) -> Lane {
        let author = event.pubkey;
        if let Some(c) = self.personas.lock().ok().and_then(|p| p.get(&author.to_hex()).map(|c| c.client.clone())) {
            return Lane::Persona(c, author);
        }
        let kind = event.kind.as_u16();
        if kind == KIND_SEALED_GROUP {
            let group = route_tag(event).and_then(|t| self.key_ring.lookup(&t)).map(|k| k.group_id);
            if let Some(pk) = group.and_then(|g| self.persona_of_group(&g)) {
                return self.personas.lock().ok()
                    .and_then(|p| p.get(&pk.to_hex()).map(|c| Lane::Persona(c.client.clone(), pk)))
                    .unwrap_or(Lane::Held);
            }
            return Lane::Groups;
        }
        if kind == KIND_SEALED_PRESENCE {
            return Lane::Groups;
        }
        let me = self.identity.read().ok().and_then(|i| *i);
        let persona = self.persona_inboxes.read().map(|p| p.contains(&author)).unwrap_or(false);
        if persona || me.map_or(false, |me| me != author) { Lane::Detached } else { Lane::Identity }
    }

    /// Each relay's connection status as it changes.
    pub fn relay_status(&self) -> Option<tokio::sync::broadcast::Receiver<nostr_sdk::pool::monitor::MonitorNotification>> {
        self.client.monitor().map(|m| m.subscribe())
    }

    // -----------------------------------------------------------------------
    // Publishing
    // -----------------------------------------------------------------------

    /// Publish a pre-signed event to all relays with the WRITE flag.
    ///
    /// Returns the event ID on success.

    /// Publish to our relays AND to [extra] (the recipients' read relays),
    /// at most [`MAX_TARGETS`] in all. A recipient relay we don't use is
    /// connected write-only: nothing is read or subscribed there.
    pub async fn publish_routed(&self, event: Event, extra: Vec<String>) -> Result<EventId> {
        if extra.is_empty() { return self.publish(event).await; }
        let own: std::collections::HashSet<String> = self.relay_urls.read().await.iter().map(|u| relay_key(u)).collect();
        let paused: std::collections::HashSet<String> = self.paused_relays().into_iter().map(|(u, _)| u).collect();
        let room = MAX_TARGETS.saturating_sub(own.len());
        let mut added = Vec::new();
        for url in extra {
            if added.len() >= room { break; }
            let k = relay_key(&url);
            if !url.starts_with("wss://") || own.contains(&k) || paused.contains(&k) || added.iter().any(|a: &String| relay_key(a) == k) {
                continue;
            }
            added.push(url);
        }
        self.publish_to(event, added).await
    }

    pub async fn publish(&self, event: Event) -> Result<EventId> {
        self.publish_to(event, Vec::new()).await
    }

    async fn publish_to(&self, event: Event, extra: Vec<String>) -> Result<EventId> {
        let mut lane = self.lane(&event);
        if matches!(lane, Lane::Held) && self.wake_for(&event).await {
            lane = self.lane(&event);
        }
        let lane_kind = if matches!(lane, Lane::Persona(..)) { LaneKind::Persona } else { LaneKind::Shared };
        let mut persona_urls = None;
        // A persona connection without its own route leaves from our address, so a
        // relay's "slow down" is about it as much as about our shared connections.
        let mut same_address = true;
        let conn = match lane {
            Lane::Identity => self.client.clone(),
            Lane::Groups => self.groups.clone(),
            Lane::Persona(c, pk) => {
                persona_urls = self.persona_relays(&pk);
                same_address = self.personas.lock().ok()
                    .and_then(|p| p.get(&pk.to_hex()).map(|c| c.proxy.is_none()))
                    .unwrap_or(false);
                c
            }
            Lane::Held => return Err(TransportError::NotConnected),
            Lane::Detached => {
                let mut urls = self.publish_targets().await;
                urls.extend(extra);
                let id = event.id;
                return if Self::publish_detached(urls, vec![event], Duration::from_secs(10)).await > 0 {
                    Ok(id)
                } else {
                    Err(TransportError::AllRelaysFailed("no relay accepted the event".into()))
                };
            }
        };
        // A persona's connection goes through its own Tor circuits and may still
        // be coming up: wait for it rather than fail and sit until a retry.
        let ready = match lane_kind {
            LaneKind::Persona => self.wait_connected(&conn, PERSONA_CONNECT_WAIT).await,
            LaneKind::Shared => self.any_connected(&conn).await,
        };
        if !ready {
            return Err(TransportError::NotConnected);
        }
        // A recipient relay we don't use is connected write-only, on the
        // connection this event belongs to.
        if let Ok(mut w) = self.write_only.lock() {
            let now = std::time::Instant::now();
            for url in &extra {
                w.insert((conn.id, relay_key(url)), now);
            }
        }
        for url in &extra {
            if conn.add_write_relay(url.as_str()).await.unwrap_or(false) {
                if let Ok(r) = conn.relay(url.as_str()).await {
                    let _ = r.try_connect(std::time::Duration::from_secs(4)).await;
                }
            }
        }

        // PIPE_TRACE (media-manifest hypothesis confirmation): the on-wire event
        // size. A large video whose media_ref inlines ~1000 chunk descriptors
        // balloons the event past relay limits (~64-128KB) â†’ all relays reject â†’
        // receiver never gets it. Log the size + kind BEFORE `event` is moved.
        let event_size = event.try_as_json().map(|s| s.len()).unwrap_or(0);
        let event_kind = event.kind.as_u16();
        if event_size > 32 * 1024 {
            tracing::warn!(target: "PIPE_TRACE", "PUBLISH_SIZE event_size={}B kind={}", event_size, event_kind);
        }

        // An even pace (no bursts that get us banned), and only to relays that
        // are not asking us to wait.
        self.keep_pace().await;
        let shared = matches!(lane_kind, LaneKind::Shared);
        // A relay's pause holds for every connection leaving from our address; a
        // persona's own Tor connection tries every relay.
        let relay_wide = shared || same_address;
        let mut targets = match (shared, persona_urls) {
            (true, _) | (false, None) if relay_wide => self.publish_targets().await,
            (_, Some(urls)) if relay_wide => self.not_paused(urls),
            (_, urls) => urls.unwrap_or_default(),
        };
        if targets.is_empty() {
            targets = self.relay_urls.read().await.clone();
        }
        targets.extend(extra);
        // Only relays this connection has: any other send fails at once ("relay not found").
        let in_pool: std::collections::HashSet<String> = conn.relays().await.keys().map(|u| relay_key(u.as_str())).collect();
        let here: Vec<String> = targets.iter().filter(|u| in_pool.contains(&relay_key(u))).cloned().collect();
        if !here.is_empty() {
            targets = here;
        }
        // A relay parked on this connection is not dialled: sending to it only fails.
        let parked = self.parking.lock().map(|p| p.parked_on(conn.id)).unwrap_or_default();
        if !parked.is_empty() {
            let open: Vec<String> = targets.iter().filter(|u| !parked.contains(&relay_key(u))).cloned().collect();
            if !open.is_empty() {
                targets = open;
            }
        }
        let event_id = event.id;

        // ONE relay OK = the event left the device. Each relay gets its own send
        // at once, and we return on the FIRST acceptance: waiting for every OK
        // left a message in "sending" until the slowest relay timed out (two
        // relays that never answered kept every message pending ~10 s). The
        // other sends finish in the background â€” accepted, refused (relay
        // paused) or timed out (sent again later, a stale socket reconnected).
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(bool, String)>();
        let total = targets.len();
        for url in targets {
            let me = self.clone();
            let conn = conn.clone();
            let ev = event.clone();
            let tx = tx.clone();
            tokio::spawn(async move {
                let ok = me.send_one(&conn, &url, ev, event_size, shared, relay_wide).await;
                let _ = tx.send((ok, url));
            });
        }
        drop(tx);
        let mut answered = 0usize;
        while let Some((ok, url)) = rx.recv().await {
            answered += 1;
            if ok {
                if !shared {
                    tracing::info!(target: "ACCOUNT", "persona publish: accepted by {} ({} of {} answered)", relay_key(&url), answered, total);
                }
                tracing::info!(target: "TRANSPORT", "publish {}: accepted ({} of {} relays answered so far)", event_id, answered, total);
                return Ok(event_id);
            }
        }
        if shared {
            tracing::warn!(target: "TRANSPORT", "publish: no relay accepted the event ({} failed) - will retry", total);
        } else {
            tracing::warn!(target: "ACCOUNT", "persona publish: no relay accepted the event ({} failed) - will retry", total);
        }
        Err(TransportError::AllRelaysFailed("no relay accepted the event".into()))
    }

    /// Send [event] to one relay and account for the answer. `true` = accepted.
    /// A refusal pauses the relay; a timeout is sent again later and, three in
    /// a row, marks the socket stale: that relay alone is paused and reconnected.
    /// [relay_wide]: the connection leaves from our address, so the relay's
    /// refusals and acceptances hold for all such connections.
    async fn send_one(&self, conn: &Conn, url: &str, event: Event, event_size: usize, shared: bool, relay_wide: bool) -> bool {
        // A dead socket is that connection's own: a persona's is reconnected alone,
        // never pausing the relay for our other connections.
        let stale_key = if shared { url.to_string() } else { format!("persona|{url}") };
        let out = match conn.send_event_to([url], &event).await {
            Ok(out) => out,
            Err(e) => {
                tracing::warn!(target: "PIPE_TRACE", "RELAY_REJECT relay={} event_size={}B reason={}", url, event_size, e);
                return false;
            }
        };
        if !out.success.is_empty() {
            if relay_wide { self.note_accepted(url); }
            if shared { self.note_publish(url, None); }
            stale_reset(&stale_key);
            return true;
        }
        for (r, reason) in out.failed.iter() {
            let why = format!("{:?}", reason);
            // A NIP-01 refusal carries a "prefix:"; a timeout is the socket's, not the relay's word.
            if shared && reason.contains(':') {
                if reason.starts_with("duplicate") { self.note_publish(url, None); } else { self.note_publish(url, Some(reason.as_str())); }
            }
            tracing::warn!(target: "PIPE_TRACE", "RELAY_REJECT relay={} event_size={}B reason={}", r, event_size, why);
            if why.to_ascii_lowercase().contains("auth-required") {
                // Kept for the moment this connection has proved itself to the relay.
                self.hold_for_auth(conn.id, url, event.clone());
                continue;
            }
            if is_refusal(&why) {
                if relay_wide { self.note_refusal(url, &why); }
            } else if stale_count(&stale_key) >= 3 {
                stale_reset(&stale_key);
                if shared {
                    self.mark_stale(conn, url).await;
                } else {
                    let _ = conn.disconnect_relay(url).await;
                    let _ = conn.connect_relay(url).await;
                }
            }
        }
        false
    }

    fn note_publish(&self, url: &str, refusal: Option<&str>) {
        if let Ok(mut s) = self.publish_stats.lock() {
            let e = s.entry(relay_key(url)).or_default();
            match refusal {
                None => {
                    e.accepted = e.accepted.saturating_add(1);
                    e.last_error.clear();
                }
                Some(r) => {
                    e.rejected = e.rejected.saturating_add(1);
                    e.last_error = r.chars().take(80).collect();
                }
            }
        }
    }

    /// A relay that keeps timing out has a dead socket the pool still calls
    /// "connected": pause it for a minute and reconnect just it.
    async fn mark_stale(&self, conn: &Client, url: &str) {
        if let Ok(mut c) = self.cooldown.lock() {
            let until = std::time::Instant::now() + std::time::Duration::from_secs(60);
            let strikes = c.get(&relay_key(url)).map(|(_, s)| *s).unwrap_or(0);
            c.insert(relay_key(url), (until, strikes));
        }
        tracing::warn!(target: "TRANSPORT", "RELAY_STALE {} â€” 3 timeouts in a row: paused 60s, reconnecting it", url);
        let _ = conn.disconnect_relay(url).await;
        let _ = conn.connect_relay(url).await;
    }

    /// At most [`PACE_MAX`] publishes per second; a burst waits its turn
    /// (startup KeyPackages, outbox replay, profile copies) instead of
    /// tripping a relay's spam limit.
    async fn keep_pace(&self) {
        const WINDOW: std::time::Duration = std::time::Duration::from_millis(1000);
        loop {
            let wait = {
                let mut q = self.pace.lock().await;
                let now = std::time::Instant::now();
                while q.front().map_or(false, |t| now.duration_since(*t) >= WINDOW) {
                    q.pop_front();
                }
                if q.len() < PACE_MAX {
                    q.push_back(now);
                    None
                } else {
                    q.front().map(|t| WINDOW.saturating_sub(now.duration_since(*t)))
                }
            };
            match wait {
                None => return,
                Some(d) => tokio::time::sleep(d.max(std::time::Duration::from_millis(20))).await,
            }
        }
    }

    /// [urls] without the relays paused right now.
    fn not_paused(&self, urls: Vec<String>) -> Vec<String> {
        let now = std::time::Instant::now();
        let paused = self.cooldown.lock().map(|c| c.clone()).unwrap_or_default();
        urls.into_iter()
            .filter(|u| paused.get(relay_key(u).as_str()).map_or(true, |(until, _)| *until <= now))
            .collect()
    }

    /// The enabled write relays that are not paused. Empty = no relay is
    /// open, and the caller publishes to all of them rather than to none.
    async fn publish_targets(&self) -> Vec<String> {
        let urls = self.relay_urls.read().await.clone();
        let flags = self.relay_flags.read().await.clone();
        let now = std::time::Instant::now();
        let paused = self.cooldown.lock().map(|c| c.clone()).unwrap_or_default();
        urls.into_iter()
            .filter(|u| flags.get(u).map(|f| f.enabled && f.write).unwrap_or(true))
            .filter(|u| paused.get(relay_key(u).as_str()).map_or(true, |(until, _)| *until <= now))
            .collect()
    }

    /// A relay told us no (a CLOSED subscription, a NOTICE): the same pause
    /// rules as for a refused publish.
    pub fn note_relay_refusal(&self, relay: &str, reason: &str) {
        self.note_refusal(relay, reason);
    }

    /// A relay closed one of our live subscriptions. "auth-required" is about
    /// the gift-wrap filter only: it is left out of what we ask that relay for a
    /// while, and the relay is not paused. Any other refusal pauses the relay.
    /// Returns whether the subscription should be asked for again (for AUTH:
    /// at once without that filter, the closed REQ took groups and presence too).
    pub fn note_subscription_closed(&self, relay: &str, subscription: &str, reason: &str) -> bool {
        let auth = reason.to_ascii_lowercase().contains("auth-required");
        // A relay's rules (AUTH, web of trust, whitelist, payment) answer the
        // same way next time: left out for hours instead of asked again.
        if auth || is_policy_refusal(reason) {
            // One-off queries carry random ids: nothing to remember for them.
            if !subscription.starts_with("alpha-") {
                return false;
            }
            if auth {
                if let Ok(mut w) = self.auth_wanted.lock() {
                    w.insert((relay_key(relay), subscription.to_string()));
                }
                let me = self.clone();
                let (relay, sub) = (relay.to_string(), subscription.to_string());
                tokio::spawn(async move { me.answer_auth(&relay, &sub).await });
            }
            if let Ok(mut r) = self.sub_refusals.lock() {
                let until = std::time::Instant::now() + std::time::Duration::from_secs(SUB_REFUSAL_SECS);
                let fresh = r.insert((relay_key(relay), subscription.to_string()), until).is_none();
                // Already left out: asking again would only be refused again.
                return fresh;
            }
            return false;
        }
        self.note_refusal(relay, reason);
        true
    }

    /// Where our contacts and the members of our groups listen.
    pub fn set_relay_routes(
        &self,
        inboxes: std::collections::HashMap<String, Vec<String>>,
        groups: std::collections::HashMap<Vec<u8>, Vec<String>>,
    ) {
        if let Ok(mut i) = self.inbox_routes.write() { *i = inboxes; }
        if let Ok(mut g) = self.group_routes.write() { *g = groups; }
    }

    /// Relays [pk] listens on, beyond ours.
    pub fn inbox_route(&self, pk: &PublicKey) -> Vec<String> {
        self.inbox_routes.read().ok().and_then(|i| i.get(&pk.to_hex()).cloned()).unwrap_or_default()
    }

    /// Relays of our groups we read from, group events only.
    pub fn listen_extra(&self) -> Vec<String> {
        self.listen_extra.read().map(|l| l.clone()).unwrap_or_default()
    }

    /// Read group events also on [urls]: the other members write there, not where
    /// we listen. The group connection joins them read-only. True on a change
    /// (the caller resubscribes).
    pub async fn set_listen_extra(&self, urls: Vec<String>) -> bool {
        let own: std::collections::HashSet<String> = self.relay_urls.read().await.iter().map(|u| relay_key(u)).collect();
        let mut next: Vec<String> = Vec::new();
        for u in urls {
            let k = relay_key(&u);
            if u.starts_with("wss://") && !own.contains(&k) && !switched_off(&u) && !next.iter().any(|n| relay_key(n) == k) {
                next.push(u);
            }
        }
        next.truncate(MAX_LISTEN_EXTRA);
        let old = self.listen_extra();
        let same = old.len() == next.len() && old.iter().all(|o| next.iter().any(|n| relay_key(n) == relay_key(o)));
        if same {
            return false;
        }
        if let Ok(mut l) = self.listen_extra.write() { *l = next.clone(); }
        for u in &old {
            if !next.iter().any(|n| relay_key(n) == relay_key(u)) {
                let _ = self.groups.remove_relay(u.as_str()).await;
            }
        }
        for u in &next {
            if self.groups.add_read_relay(u).await.unwrap_or(false) {
                let _ = self.groups.connect_relay(u.as_str()).await;
            }
        }
        tracing::info!(target: "TRANSPORT", "group relays read: {}", next.len());
        true
    }

    /// Relays the other members of [group_id] listen on.
    pub fn group_route(&self, group_id: &[u8]) -> Vec<String> {
        self.group_routes.read().ok().and_then(|g| g.get(group_id).cloned()).unwrap_or_default()
    }

    /// The keys NIP-42 answers are signed with, per inbox.
    pub fn set_auth_signer(&self, signer: AuthSigner) {
        if let Ok(mut s) = self.auth_signer.write() { *s = Some(SignerSlot(signer)); }
    }

    /// Relays that may be asked again now for a subscription held back from them.
    pub fn take_resub_later(&self) -> Vec<String> {
        let open: std::collections::HashSet<String> = self.resub_later.lock()
            .map(|l| l.iter().cloned().collect()).unwrap_or_default();
        let open = self.not_paused(open.into_iter().collect());
        if let Ok(mut l) = self.resub_later.lock() { for u in &open { l.remove(u); } }
        open
    }

    fn note_auth_challenge(&self, lane: u64, relay: &str, challenge: &str) {
        if let Ok(mut c) = self.auth_challenges.lock() {
            c.insert((lane, relay_key(relay)), challenge.to_string());
        }
        let sub = self.subscription_of_lane(lane);
        let wanted = self.auth_wanted.lock().map(|w| w.contains(&(relay_key(relay), sub.to_string()))).unwrap_or(false);
        if wanted {
            let me = self.clone();
            let relay = relay.to_string();
            tokio::spawn(async move { me.answer_auth(&relay, sub).await });
        }
    }

    fn hold_for_auth(&self, lane: u64, relay: &str, event: Event) {
        const MAX_HELD: usize = 32;
        if let Ok(mut h) = self.auth_held.lock() {
            let list = h.entry((lane, relay_key(relay))).or_default();
            if !list.iter().any(|e| e.id == event.id) && list.len() < MAX_HELD { list.push(event); }
        }
        let sub = self.subscription_of_lane(lane);
        if let Ok(mut w) = self.auth_wanted.lock() { w.insert((relay_key(relay), sub.to_string())); }
        let me = self.clone();
        let relay = relay.to_string();
        tokio::spawn(async move { me.answer_auth(&relay, sub).await });
    }

    fn subscription_of_lane(&self, lane: u64) -> &'static str {
        if lane == self.client.id { SUB_IDENTITY } else if lane == self.groups.id { SUB_GROUPS } else { SUB_PERSONA }
    }

    /// NIP-42, only for a relay that refused one of our subscriptions for want
    /// of it. Each inbox proves itself with its own key, which its REQ names
    /// anyway; the group connection with a random one. Retries back off, so a
    /// relay whose AUTH keeps failing gets no flood.
    async fn answer_auth(&self, relay: &str, sub: &str) {
        // (connection, whose key: None = random, Some(None) = the account, Some(Some(pk)) = a persona)
        let lanes: Vec<(Conn, Option<Option<PublicKey>>)> = match sub {
            SUB_IDENTITY => vec![(self.client.clone(), Some(None))],
            SUB_GROUPS => vec![(self.groups.clone(), None)],
            _ => self.personas.lock().map(|p| p.iter()
                .filter_map(|(k, c)| PublicKey::from_hex(k).ok().map(|pk| (c.client.clone(), Some(Some(pk)))))
                .collect()).unwrap_or_default(),
        };
        let key = relay_key(relay);
        let mut answered = false;
        let mut proved: Vec<Conn> = Vec::new();
        for (conn, inbox) in lanes {
            let slot = (conn.id, key.clone());
            let Some(challenge) = self.auth_challenges.lock().ok().and_then(|mut c| c.remove(&slot)) else {
                // Its challenge came before we listened (it is sent once, on connect):
                // one fresh connection, at most every half hour, brings a new one.
                let due = self.auth_redials.lock().map(|mut r| {
                    let due = r.get(&slot).is_none_or(|at| at.elapsed() > Duration::from_secs(1800));
                    if due { r.insert(slot.clone(), std::time::Instant::now()); }
                    due
                }).unwrap_or(false);
                if due {
                    if let Ok(r) = conn.relay(relay).await {
                        tracing::warn!(target: "TRANSPORT", "RELAY_AUTH {key}: reconnecting for a challenge");
                        r.disconnect();
                        tokio::time::sleep(RESTART_WAIT).await;
                        r.connect();
                    }
                }
                continue;
            };
            let now = std::time::Instant::now();
            let allowed = self.auth_tries.lock().map(|mut t| {
                let (last, strikes) = t.get(&slot).copied().unwrap_or((now - Duration::from_secs(7200), 0));
                let strikes = if now.duration_since(last) > Duration::from_secs(1800) { 0 } else { strikes };
                let wait = (AUTH_RETRY_BASE * 2u32.saturating_pow(strikes)).min(Duration::from_secs(3600));
                if strikes > 0 && now.duration_since(last) < wait { return false; }
                t.insert(slot.clone(), (now, strikes + 1));
                true
            }).unwrap_or(false);
            if !allowed { continue; }
            let keys = match inbox {
                Some(who) => match self.auth_signer.read().ok().and_then(|s| s.clone()).and_then(|s| (s.0)(who.as_ref())) {
                    Some(k) => k,
                    None => continue,
                },
                None => Keys::generate(),
            };
            let Ok(url) = nostr::RelayUrl::parse(relay) else { continue };
            let Ok(event) = EventBuilder::auth(challenge, url.clone()).sign_with_keys(&keys) else { continue };
            let Ok(r) = conn.relay(url).await else { continue };
            if r.send_msg(nostr::ClientMessage::Auth(std::borrow::Cow::Owned(event))).is_ok() {
                tracing::warn!(target: "TRANSPORT", "RELAY_AUTH {key} for {sub}");
                answered = true;
                proved.push(conn.clone());
            }
        }
        if !answered { return; }
        // The relay's OK comes within a moment; the subscription is asked again
        // after it (a refusal now would put it back for the next challenge).
        tokio::time::sleep(Duration::from_secs(2)).await;
        for conn in proved {
            let held = self.auth_held.lock().ok().and_then(|mut h| h.remove(&(conn.id, key.clone()))).unwrap_or_default();
            for ev in held {
                let _ = conn.send_event_to([relay], &ev).await;
            }
        }
        if let Ok(mut r) = self.sub_refusals.lock() { r.remove(&(key.clone(), sub.to_string())); }
        if let Ok(mut w) = self.auth_wanted.lock() { w.remove(&(key.clone(), sub.to_string())); }
        if let Ok(mut l) = self.resub_later.lock() { l.insert(relay.to_string()); }
    }

    /// Relays that refused [subscription] and are not asked for it now.
    fn refused_for(&self, subscription: &str) -> std::collections::HashSet<String> {
        let now = std::time::Instant::now();
        self.sub_refusals.lock().map(|mut r| {
            r.retain(|_, until| *until > now);
            r.keys().filter(|(_, s)| s == subscription).map(|(relay, _)| relay.clone()).collect()
        }).unwrap_or_default()
    }

    /// A relay refused with "rate-limited" / "banned" / "too many": pause it
    /// (1, 2, 4 â€¦ up to 30 minutes). Other refusals don't pause it.
    fn note_refusal(&self, relay: &str, reason: &str) {
        if !is_refusal(reason) {
            return;
        }
        if let Ok(mut c) = self.cooldown.lock() {
            let key = relay_key(relay);
            // One wave of parallel refusals is ONE strike: refusals that arrive
            // while the relay is already paused don't stretch the pause.
            if c.get(&key).map_or(false, |(until, _)| *until > std::time::Instant::now()) {
                return;
            }
            let strikes = c.get(&key).map(|(_, s)| *s).unwrap_or(0).saturating_add(1);
            let secs = (60u64 << (strikes - 1).min(5)).min(30 * 60);
            c.insert(key.clone(), (std::time::Instant::now() + std::time::Duration::from_secs(secs), strikes));
            tracing::warn!(target: "TRANSPORT", "RELAY_PAUSE {} for {}s (strike {}): {}", key, secs, strikes, reason);
        }
    }

    /// A relay accepted again: its pause history is forgiven.
    fn note_accepted(&self, relay: &str) {
        if let Ok(mut c) = self.cooldown.lock() {
            if c.remove(&relay_key(relay)).is_some() {
                tracing::warn!(target: "TRANSPORT", "RELAY_RESUMED {}", relay_key(relay));
            }
        }
    }

    /// Relays paused right now (url, seconds left) â€” for diagnostics.
    pub fn paused_relays(&self) -> Vec<(String, u64)> {
        let now = std::time::Instant::now();
        self.cooldown.lock().map(|c| {
            c.iter().filter(|(_, (until, _))| *until > now)
                .map(|(u, (until, _))| (u.clone(), until.duration_since(now).as_secs()))
                .collect()
        }).unwrap_or_default()
    }

    // -----------------------------------------------------------------------
    // Subscriptions
    // -----------------------------------------------------------------------

    /// Subscribe with the given filter.
    ///
    /// Returns the subscription ID for later management.
    pub async fn subscribe(&self, filter: Filter) -> Result<SubscriptionId> {
        if !self.is_connected().await {
            return Err(TransportError::NotConnected);
        }

        let output = self
            .client
            .subscribe(filter, None)
            .await
            .map_err(|e| TransportError::SubscriptionFailed(e.to_string()))?;

        tracing::debug!("Subscribed with id {}", output.val);
        Ok(output.val)
    }

    /// Subscribe to both MLS envelope (kind 443) and gift-wrapped (kind 1059)
    /// events. This is the standard subscription for realtime message reception.
    ///
    /// Returns the subscription ID for later management.
    pub async fn subscribe_mls_and_giftwrap(
        &self,
        self_pubkey: &PublicKey,
    ) -> Result<SubscriptionId> {
        self.subscribe_mls_and_giftwrap_on(self_pubkey, None).await
    }

    /// Like [`subscribe_mls_and_giftwrap`], on [only] these relays (`None` =
    /// all): a relay that closed our subscription is asked again alone,
    /// without re-sending the REQ to every relay.
    pub async fn subscribe_mls_and_giftwrap_on(
        &self,
        self_pubkey: &PublicKey,
        only: Option<Vec<String>>,
    ) -> Result<SubscriptionId> {
        self.set_identity(*self_pubkey);
        if !self.is_connected().await {
            return Err(TransportError::NotConnected);
        }

        // NIP-59 explicitly says gift-wraps must be discovered via the `#p`
        // tag (recipient pubkey). Without that filter most relays either
        // refuse the subscription or silently truncate the result set, which
        // is exactly why peer B never saw the Welcome.
        //
        // ROOT-CAUSE FIX (delivery breaks under load): the kind-443 realtime
        // subscription used to be UNBOUNDED (no `since`). A relay answers a new
        // subscription by REPLAYING its whole stored history that matches â€” so
        // every (re)subscribe dumped HOURS of old kind-443 events (observed 900+
        // events aged 2-8h), each taking the MLS lock to process into nothing.
        // That saturated the lock and starved REAL incoming messages â†’ "sent but
        // never received" once a few groups were active. Bound the live
        // subscription to a small recent window: realtime only needs events from
        // ~now onward; anything older is the job of catch-up (which uses the
        // persisted per-relay watermark). A 120s look-back absorbs clock skew and
        // brief resubscribe gaps without pulling the history avalanche.
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs()).unwrap_or(0);
        let realtime_since = Timestamp::from(now.saturating_sub(120));
        // Gift wraps are #p-addressed and backdated, so no `since`. Sealed group
        // events are fetched only for OUR routing tags (the relay learns nothing
        // about the groups), bounded to a recent window; history is catch-up's job.
        let group_tags = self.key_ring.subscription_tags_where(|g| self.persona_of_group(g).is_none());
        let group_filter = (!group_tags.is_empty())
            .then(|| Self::sealed_group_filter(group_tags).since(realtime_since));
        let presence_tags = self.presence_tags.read().map(|t| t.clone()).unwrap_or_default();
        let presence_filter = (!presence_tags.is_empty()).then(|| {
            use nostr::{Alphabet, SingleLetterTag};
            Filter::new()
                .kind(Kind::from(KIND_SEALED_PRESENCE))
                .custom_tags(SingleLetterTag::lowercase(Alphabet::H), presence_tags)
        });

        // ROOT-CAUSE FIX (storm after hours: "ERROR: too many concurrent REQs"):
        // STABLE subscription ids. A new id per resubscribe left the old REQ
        // open on a live socket until relays refused new ones; the same id
        // REPLACES the REQ. One REQ per relay and connection: a REQ per filter
        // tripled the requests and relays banned us for it.
        //
        // Each connection asks only for its own: the identity one for our gift
        // wraps, the group one for group events and presence, each persona for
        // its inbox. No single REQ ties our npub to our groups or personas.
        let extra = self.listen_extra();
        let is_extra = |u: &String| extra.iter().any(|e| relay_key(e) == relay_key(u));
        let (urls, extra_targets): (Vec<String>, Vec<String>) = match &only {
            Some(urls) if !urls.is_empty() => urls.iter().cloned().partition(|u| !is_extra(u)),
            _ => {
                let flags = self.relay_flags.read().await.clone();
                let own: Vec<String> = self.relay_urls.read().await.iter()
                    .filter(|u| flags.get(*u).map(|f| f.enabled && f.read).unwrap_or(true))
                    .cloned().collect();
                let more = extra.iter().filter(|e| !own.iter().any(|o| relay_key(o) == relay_key(e))).cloned().collect();
                (own, more)
            }
        };
        let gift_id = SubscriptionId::new(SUB_IDENTITY);
        let gift_filter = Filter::new().kind(Kind::GiftWrap).pubkey(*self_pubkey);
        if !urls.is_empty() {
            self.subscribe_lane(&self.client, &gift_id, &urls, vec![gift_filter]).await?;
        }

        let group_id = SubscriptionId::new(SUB_GROUPS);
        let extra_filter = group_filter.clone();
        let rest: Vec<Filter> = group_filter.into_iter().chain(presence_filter).collect();
        if rest.is_empty() {
            self.groups.unsubscribe(&group_id).await;
        } else if !urls.is_empty() {
            self.subscribe_lane(&self.groups, &group_id, &urls, rest).await?;
        }
        // The group's relays we only read: group events, nothing that names us.
        if let (Some(f), false) = (extra_filter, extra_targets.is_empty()) {
            if let Err(e) = self.subscribe_lane(&self.groups, &group_id, &extra_targets, vec![f]).await {
                tracing::warn!(target: "TRANSPORT", "group-relay subscription failed: {e}");
            }
        }

        self.sync_personas().await;
        let personas: Vec<(PublicKey, Conn)> = self.personas.lock().map(|p| {
            p.iter().filter_map(|(k, c)| PublicKey::from_hex(k).ok().map(|pk| (pk, c.client.clone()))).collect()
        }).unwrap_or_default();
        let persona_id = SubscriptionId::new(SUB_PERSONA);
        for (n, (pk, conn)) in personas.into_iter().enumerate() {
            // Several inboxes at once from one address look like a flood.
            if n > 0 { tokio::time::sleep(PERSONA_SUB_STAGGER).await; }
            let mut filters = vec![Filter::new().kind(Kind::GiftWrap).pubkey(pk)];
            let tags = self.key_ring.subscription_tags_where(|g| self.persona_of_group(g) == Some(pk));
            if !tags.is_empty() {
                // A connection that slept (or a chat whose relays the shared
                // catch-up does not visit) reaches back to its last message.
                let since = self.persona_plans.read().ok()
                    .and_then(|p| p.get(&pk).map(|p| p.active_at.saturating_sub(600)))
                    .filter(|t| *t > 0)
                    .map(|t| Timestamp::from(t.min(realtime_since.as_u64())))
                    .unwrap_or(realtime_since);
                filters.push(Self::sealed_group_filter(tags).since(since));
            }
            let urls = self.persona_relays(&pk).unwrap_or_else(|| urls.clone());
            if self.connected_count(&conn).await < urls.len() {
                // Still coming up through Tor: ask each relay as soon as it is
                // there, not at the next heartbeat minutes later.
                self.subscribe_when_connected(conn, persona_id.clone(), urls.clone(), filters);
            } else if let Err(e) = self.subscribe_lane(&conn, &persona_id, &urls, filters).await {
                tracing::warn!(target: "TRANSPORT", "persona subscription failed: {e}");
            }
        }

        tracing::debug!("Subscribed: identity, groups and {} persona connection(s)", self.personas.lock().map(|p| p.len()).unwrap_or(0));
        Ok(gift_id)
    }

    /// Relays of [conn] (a persona's: its own set) that are up now.
    async fn connected_count(&self, conn: &Client) -> usize {
        let mut n = 0;
        for r in conn.relays().await.values() {
            if format!("{:?}", r.status()) == "Connected" { n += 1; }
        }
        n
    }

    /// Whether [conn] (a persona's) has a relay up within [max].
    async fn wait_connected(&self, conn: &Client, max: Duration) -> bool {
        let until = std::time::Instant::now() + max;
        loop {
            if self.connected_count(conn).await > 0 { return true; }
            if std::time::Instant::now() >= until { return false; }
            tokio::time::sleep(Duration::from_millis(400)).await;
        }
    }

    /// Subscribe on [conn] again each time more of its relays are up, until
    /// all are or a few minutes pass.
    fn subscribe_when_connected(&self, conn: Conn, id: SubscriptionId, urls: Vec<String>, filters: Vec<Filter>) {
        let me = self.clone();
        tokio::spawn(async move {
            let until = std::time::Instant::now() + Duration::from_secs(180);
            // Each relay is asked once, when it comes up: asking all of them
            // again every time one more connects reads as abuse to relays.
            let mut asked: std::collections::HashSet<String> = std::collections::HashSet::new();
            while std::time::Instant::now() < until {
                let mut fresh = Vec::new();
                for (url, relay) in conn.relays().await {
                    let u = url.to_string();
                    if relay.status() == nostr_sdk::RelayStatus::Connected && !asked.contains(&relay_key(&u))
                        && urls.iter().any(|w| relay_key(w) == relay_key(&u)) {
                        fresh.push(u);
                    }
                }
                if !fresh.is_empty() {
                    tracing::info!(target: "ACCOUNT", "persona link: {} more of {} relays up", fresh.len(), urls.len());
                    match me.subscribe_lane(&conn, &id, &fresh, filters.clone()).await {
                        Ok(()) => asked.extend(fresh.iter().map(|u| relay_key(u))),
                        Err(e) => tracing::debug!("persona subscription retry: {e}"),
                    }
                }
                if asked.len() >= urls.len() { return; }
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
        });
    }

    /// The route (switch listener) of the persona connection carrying [group_id].
    pub fn persona_proxy(&self, group_id: &[u8]) -> Option<std::net::SocketAddr> {
        let pk = self.persona_of_group(group_id)?;
        self.personas.lock().ok()?.get(&pk.to_hex())?.proxy
    }

    /// New Tor circuits for the persona connection carrying [group_id]: its
    /// listener presents new credentials and the connection reconnects. Other
    /// connections keep their circuits.
    pub async fn renew_persona_circuits(&self, group_id: &[u8]) -> bool {
        let Some(pk) = self.persona_of_group(group_id) else { return false };
        let conn = self.personas.lock().ok().and_then(|p| {
            let c = p.get(&pk.to_hex())?;
            c.listener.as_ref()?.renew();
            Some(c.client.clone())
        });
        let Some(conn) = conn else { return false };
        if let Ok(mut p) = self.parking.lock() {
            p.clear_lane(conn.id);
        }
        conn.disconnect().await;
        conn.connect().await;
        tracing::info!(target: "ACCOUNT", "persona link: new circuits asked for");
        let me = self.identity.read().ok().and_then(|i| *i);
        if let Some(me) = me {
            let _ = self.subscribe_mls_and_giftwrap(&me).await;
        }
        true
    }

    /// Whether the connection carrying [group_id] (a persona's) has a relay up.
    pub async fn persona_link_ready(&self, group_id: &[u8]) -> bool {
        let Some(pk) = self.persona_of_group(group_id) else { return self.is_connected().await };
        let conn = self.personas.lock().ok().and_then(|p| p.get(&pk.to_hex()).map(|c| c.client.clone()));
        match conn {
            Some(c) => self.connected_count(&c).await > 0,
            None => false,
        }
    }

    // -----------------------------------------------------------------------
    // Persona plans: own relays per chat, sleep when idle
    // -----------------------------------------------------------------------

    /// The relays a persona's chat agreed on (`None` = all of ours).
    fn persona_relays(&self, pk: &PublicKey) -> Option<Vec<String>> {
        self.persona_plans.read().ok()?.get(pk).map(|p| p.relays.clone()).filter(|r| !r.is_empty())
    }

    /// Set every persona's plan. True when a connection must open, close or
    /// change relays (the caller then resubscribes).
    pub fn set_persona_plans(&self, plans: std::collections::HashMap<PublicKey, PersonaPlan>) -> bool {
        let relays_changed = match self.persona_plans.write() {
            Ok(mut cur) => {
                let changed = plans.iter().any(|(k, p)| cur.get(k).map(|c| c.relays != p.relays).unwrap_or(true))
                    || cur.keys().any(|k| !plans.contains_key(k));
                *cur = plans;
                changed
            }
            Err(_) => false,
        };
        self.evaluate_sleep() || relays_changed
    }

    /// Keep [group_id]'s persona connected for a while (its chat is on
    /// screen). True when it was asleep (the caller then resubscribes).
    pub fn wake_group(&self, group_id: &[u8]) -> bool {
        let Some(pk) = self.persona_of_group(group_id) else { return false };
        if let Ok(mut w) = self.persona_woken.lock() {
            w.insert(pk, std::time::Instant::now() + PERSONA_WAKE_HOLD);
        }
        self.evaluate_sleep()
    }

    /// Which personas sleep now. True when that changed.
    pub fn evaluate_sleep(&self) -> bool {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        let woken: std::collections::HashSet<PublicKey> = self.persona_woken.lock().map(|mut w| {
            let t = std::time::Instant::now();
            w.retain(|_, until| *until > t);
            w.keys().copied().collect()
        }).unwrap_or_default();
        let asleep = self.persona_plans.read().map(|plans| sleeping(&plans, &woken, now)).unwrap_or_default();
        match self.persona_asleep.write() {
            Ok(mut cur) if *cur != asleep => {
                tracing::info!(target: "ACCOUNT", "persona sleep: {} asleep", asleep.len());
                *cur = asleep;
                true
            }
            _ => false,
        }
    }

    /// A send for a sleeping persona's group: wake it and wait for its
    /// connection. True when it is there.
    async fn wake_for(&self, event: &Event) -> bool {
        let Some(gid) = route_tag(event).and_then(|t| self.key_ring.lookup(&t)).map(|k| k.group_id) else { return false };
        if !self.wake_group(&gid) && self.persona_of_group(&gid).is_none() { return false; }
        if let Some(me) = self.identity.read().ok().and_then(|i| *i) {
            let _ = self.subscribe_mls_and_giftwrap(&me).await;
        }
        let pk = self.persona_of_group(&gid);
        pk.and_then(|pk| self.personas.lock().ok().and_then(|p| p.get(&pk.to_hex()).map(|c| c.client.clone()))).is_some()
    }

    /// One REQ [id] with [filters] on [conn], on every relay of [urls] that has
    /// not refused this subscription (AUTH wanted, which we never give).
    async fn subscribe_lane(&self, conn: &Client, id: &SubscriptionId, urls: &[String], filters: Vec<Filter>) -> Result<()> {
        let refused = self.refused_for(id.as_str());
        let open = self.not_paused(urls.to_vec());
        if let Ok(mut later) = self.resub_later.lock() {
            later.extend(urls.iter().filter(|u| !open.contains(u)).cloned());
        }
        let targets: Vec<(String, Vec<Filter>)> = open.iter()
            .filter(|u| !refused.contains(&relay_key(u)))
            .map(|u| (u.clone(), filters.clone()))
            .collect();
        if targets.is_empty() {
            return Ok(());
        }
        let what: Vec<String> = filters.iter().map(|f| {
            let kinds: Vec<u16> = f.kinds.iter().flatten().map(|k| k.as_u16()).collect();
            let keys: usize = f.generic_tags.values().map(|v| v.len()).sum();
            format!("{:?}x{}", kinds, keys)
        }).collect();
        tracing::info!(target: "ACCOUNT", "SUB {} relays={} filters={:?}", id, targets.len(), what);
        conn.pool()
            .subscribe_targeted(id.clone(), targets, nostr_sdk::SubscribeOptions::default())
            .await
            .map(|_| ())
            .map_err(|e| TransportError::SubscriptionFailed(e.to_string()))
    }

    /// A connection for every persona inbox, each through a switch listener of
    /// its own; connections of personas that are gone are closed. In private
    /// mode a persona whose listener cannot start stays offline rather than
    /// borrow another connection's circuits.
    async fn sync_personas(&self) {
        let _one_at_a_time = self.persona_sync.lock().await;
        let asleep = self.persona_asleep.read().map(|a| a.clone()).unwrap_or_default();
        let wanted: Vec<PublicKey> = self.persona_inboxes.read().map(|p| p.clone()).unwrap_or_default()
            .into_iter().filter(|pk| !asleep.contains(pk)).collect();
        let wanted_hex: std::collections::HashSet<String> = wanted.iter().map(|k| k.to_hex()).collect();
        let gone: Vec<PersonaConn> = self.personas.lock().map(|mut p| {
            let keys: Vec<String> = p.keys().filter(|k| !wanted_hex.contains(*k)).cloned().collect();
            keys.iter().filter_map(|k| p.remove(k)).collect()
        }).unwrap_or_default();
        for conn in gone {
            conn.client.shutdown().await;
        }
        let urls = self.enabled_relay_urls().await;
        let mut opened = 0u32;
        for pk in wanted {
            let hex = pk.to_hex();
            if self.personas.lock().map(|p| p.contains_key(&hex)).unwrap_or(true) {
                continue;
            }
            let tor_only = self.tor_only.read().map(|t| t.contains(&pk)).unwrap_or(true);
            let (proxy, listener) = if tor_only || network_proxy().is_some() {
                let started = if tor_only {
                    crate::net_switch::start_tor_only().await
                } else {
                    crate::net_switch::start_owned().await
                };
                match started {
                    Ok((addr, guard)) => (Some(addr), Some(guard)),
                    Err(e) => {
                        tracing::error!(target: "TRANSPORT", "persona connection not opened: {e}");
                        continue;
                    }
                }
            } else {
                (None, None)
            };
            let client = build_client_with_lifted_limits(proxy);
            for u in self.persona_relays(&pk).as_ref().unwrap_or(&urls) {
                let _ = client.add_relay(u.as_str()).await;
            }
            self.forward(&client, false);
            // After our shared connections, one by one, without holding up the caller
            // (its subscription waits for the relays to come up anyway).
            opened += 1;
            let dialing = client.clone();
            tokio::spawn(async move {
                tokio::time::sleep(LANE_STAGGER * opened).await;
                dialing.connect().await;
            });
            if let Ok(mut p) = self.personas.lock() {
                p.insert(hex, PersonaConn { client, listener, proxy });
            }
        }
    }

    /// Unsubscribe from a subscription.
    pub async fn unsubscribe(&self, subscription_id: SubscriptionId) {
        for conn in self.connections() {
            conn.unsubscribe(&subscription_id).await;
        }
    }

    /// Unsubscribe from all active subscriptions.
    pub async fn unsubscribe_all(&self) {
        for conn in self.connections() {
            conn.unsubscribe_all().await;
        }
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /// One-shot query: fetch events matching the filter, waiting at most
    /// `timeout` for responses.
    pub async fn get_events_of(
        &self,
        filter: Filter,
        timeout: Duration,
    ) -> Result<Vec<Event>> {
        if !self.is_connected().await {
            return Err(TransportError::NotConnected);
        }

        let events = self
            .client
            .fetch_events(filter, timeout)
            .await
            .map_err(|e| TransportError::QueryFailed(e.to_string()))?;

        Ok(events.to_vec())
    }

    /// A query that leaves no trace of who asked: a separate, short-lived
    /// connection with its own random key (through the proxy when one is set),
    /// sharing nothing with the connection that carries our own subscriptions.
    pub async fn fetch_anonymous(urls: Vec<String>, filter: Filter, timeout: Duration) -> Result<Vec<Event>> {
        let (proxy, _scope) = one_off_proxy().await?;
        let client = build_client_with_lifted_limits(proxy);
        for url in &urls {
            let _ = client.add_relay(url.as_str()).await;
        }
        client.connect().await;
        let events = client
            .fetch_events(filter, timeout)
            .await
            .map_err(|e| TransportError::QueryFailed(e.to_string()));
        client.shutdown().await;
        Ok(events?.to_vec())
    }

    /// The well-known relays that are NOT already among ours: copies go only
    /// there, so no relay gets the same event twice (twice the load against
    /// its rate limits).
    pub async fn well_known_extra(&self) -> Vec<String> {
        let own: std::collections::HashSet<String> = self.relay_urls.read().await
            .iter().map(|u| relay_key(u)).collect();
        crate::DEFAULT_RELAYS.iter()
            .filter(|u| !own.contains(&relay_key(u)))
            .map(|u| u.to_string())
            .collect()
    }

    /// Publish already-signed events to [urls] on a separate, short-lived
    /// connection (through the proxy when one is set). Returns how many events
    /// at least one relay accepted.
    pub async fn publish_detached(urls: Vec<String>, events: Vec<Event>, timeout: Duration) -> usize {
        // A relay whose rules refuse events of one-off keys (all of ours here)
        // refuses the next one too: left out for a while instead of asked again.
        let urls: Vec<String> = urls.into_iter().filter(|u| !refuses_one_off_keys(u)).collect();
        if urls.is_empty() { return 0; }
        let (proxy, _scope) = match one_off_proxy().await {
            Ok(p) => p,
            Err(e) => {
                tracing::error!(target: "ACCOUNT", "detached publish not sent: {e}");
                return 0;
            }
        };
        let client = build_client_with_lifted_limits(proxy);
        for url in &urls {
            let _ = client.add_relay(url.as_str()).await;
        }
        let _ = client.try_connect(timeout).await;
        let mut ok = 0usize;
        for ev in events {
            match client.send_event(&ev).await {
                Ok(out) => {
                    let took: Vec<String> = out.success.iter().map(|u| u.to_string()).collect();
                    for (u, why) in out.failed.iter() {
                        if is_policy_refusal(why) { note_refuses_one_off_keys(&u.to_string()); }
                    }
                    let refused: Vec<String> = out.failed.iter().map(|(u, why)| format!("{u} ({why})")).collect();
                    tracing::info!(target: "ACCOUNT", "detached kind {}: accepted by {:?}, refused by {:?}",
                        ev.kind.as_u16(), took, refused);
                    if !out.success.is_empty() { ok += 1; }
                }
                Err(e) => tracing::warn!(target: "ACCOUNT", "detached kind {}: {}", ev.kind.as_u16(), e),
            }
        }
        client.shutdown().await;
        ok
    }

    /// One-shot query with multiple filters.
    pub async fn get_events_of_multi(
        &self,
        filters: Vec<Filter>,
        timeout: Duration,
    ) -> Result<Vec<Event>> {
        if !self.is_connected().await {
            return Err(TransportError::NotConnected);
        }

        // One filter per REQ: the filters are asked side by side, the answers merged.
        let answers = futures_util::future::join_all(
            filters.into_iter().map(|f| self.client.fetch_events(f, timeout)),
        ).await;
        let mut seen = std::collections::HashSet::new();
        let mut events = Vec::new();
        for answer in answers {
            for e in answer.map_err(|e| TransportError::QueryFailed(e.to_string()))? {
                if seen.insert(e.id) { events.push(e); }
            }
        }
        Ok(events)
    }

    // -----------------------------------------------------------------------
    // Since-filter (catch-up) â€” now includes kind 443 AND kind 1059
    // -----------------------------------------------------------------------

    /// Build a catch-up filter for MLS envelope events since a given timestamp.
    ///
    /// Returns a filter that matches **both** kind 443 (MLS envelope) and
    /// kind 1059 (gift-wrapped) events. This ensures that catch-up catches
    /// not only group application messages but also gift-wrapped Welcome
    /// messages that arrived while offline.
    ///
    /// The `relay_url` parameter is accepted for API symmetry and logging but
    /// Nostr filters are relay-agnostic â€” the same filter can be sent to any
    /// relay.
    pub fn since_filter(&self, relay_url: &str, last_timestamp: u64) -> Filter {
        tracing::debug!(
            "Building since-filter for relay={}, since={}",
            relay_url,
            last_timestamp
        );

        Filter::new()
            .kinds([Kind::MlsKeyPackage, Kind::GiftWrap]) // 443 + 1059
            .since(Timestamp::from(last_timestamp))
    }

    /// Build a catch-up filter for ONLY kind 443 (MLS envelope) events.
    ///
    /// Use this when you only need public MLS envelopes (e.g. for
    /// group messages) and don't need gift-wrapped Welcome messages.
    pub fn since_filter_mls_only(&self, _relay_url: &str, last_timestamp: u64) -> Filter {
        Self::sealed_group_filter(self.key_ring.all_tags()).since(Timestamp::from(last_timestamp))
    }

    /// Build a catch-up filter for ONLY kind 1059 (gift-wrapped) events.
    ///
    /// Use this when you only need gift-wrapped Welcome messages
    /// (e.g. when the caller handles them separately).
    pub fn since_filter_giftwrap_only(&self, _relay_url: &str, last_timestamp: u64) -> Filter {
        // Widen by NIP-59's max backdate (â‰¤2 days) so backdated gift-wrapped
        // Welcomes aren't skipped once our checkpoint moves past their
        // randomized created_at. Event-id dedup keeps reprocessing harmless.
        let gw_since = last_timestamp.saturating_sub(3 * 24 * 60 * 60);
        Filter::new()
            .kind(Kind::GiftWrap)
            .since(Timestamp::from(gw_since))
    }

    /// Query events since a given timestamp, optionally filtered by author.
    ///
    /// Uses the Nostr `since` filter to fetch both kind-443 and kind-1059
    /// events that arrived after the given timestamp. Returns the events
    /// converted to [`RawMlsEvent`] instances ready for MLS decryption.
    pub async fn query_events_since(
        &self,
        relay_url: &str,
        since: u64,
        self_pubkey: &PublicKey,
    ) -> Result<Vec<RawMlsEvent>> {
        // Two separate filters: 443 broad, 1059 narrowed by `#p` tag.
        // Merging into a single Filter with `.kinds([443,1059]).pubkey(self)`
        // does NOT work the way you'd hope â€” the `#p` constraint then applies
        // to BOTH kinds and we'd lose all 443 events because group messages
        // don't carry a `p` tag.
        // A ceiling per query and relay, far above a long absence's worth.
        const CATCH_UP_LIMIT: usize = 5_000;
        let group_tags = self.key_ring.all_tags_where(|g| self.persona_of_group(g).is_none());
        let groups_for = |tags: Vec<String>| {
            let f = Self::sealed_group_filter(tags).limit(CATCH_UP_LIMIT);
            if since > 0 { f.since(Timestamp::from(since)) } else { f }
        };
        let f_445 = groups_for(group_tags.clone());
        let mut gw_since = 0;
        if since > 0 {
            // NIP-59 gift-wraps carry a RANDOMIZED created_at up to ~2 days in
            // the PAST (to hide timing). A naive `.since(checkpoint)` therefore
            // DROPS a Welcome whose backdated timestamp is older than our
            // checkpoint â€” the recipient silently never joins (this was the
            // "Welcome from phone A doesn't arrive" bug). Widen the 1059 window
            // by the max backdate so backdated Welcomes are still fetched;
            // event-id dedup + idempotent join keep reprocessing harmless.
            // 3 days â‰¥ NIP-59's max 2-day backdate window.
            gw_since = since.saturating_sub(3 * 24 * 60 * 60);
        }
        let wraps_for = |inbox: PublicKey| {
            let f = Filter::new().kind(Kind::GiftWrap).pubkey(inbox).limit(CATCH_UP_LIMIT);
            if gw_since > 0 { f.since(Timestamp::from(gw_since)) } else { f }
        };
        tracing::debug!(
            "Building since-filters for relay={}, since={}, self={}",
            relay_url, since, self_pubkey,
        );
        // THIS relay only â€” its own watermark is what `since` is. (The query
        // used to go to the whole pool for every relay: 5 relays Ã— 2 REQs to
        // ALL relays per catch-up, one after the other at 15 s each, so a slow
        // or closing relay pushed the pass past the core's 20 s limit and the
        // whole catch-up â€” every event already received â€” was thrown away.)
        //
        // Group events and gift wraps are still two REQs (a busy group stream
        // must not crowd the backdated Welcomes out of one response), but they
        // run side by side, and what one of them returns survives the other.
        if !self.is_connected().await {
            return Err(TransportError::NotConnected);
        }
        let timeout = Duration::from_secs(8);
        // Each query on the connection that owns it, as in the live subscription.
        // A relay that wants AUTH for a subscription refuses its query every time.
        let refused = |sub: &str| self.refused_for(sub).contains(&relay_key(relay_url));
        let groups_refused = refused(SUB_GROUPS);
        let q_445 = async {
            if group_tags.is_empty() || groups_refused { return Ok(Vec::new()); }
            self.groups.fetch_events_from([relay_url], f_445, timeout).await.map(|e| e.to_vec())
        };
        // A group relay we only read: group events only, our inboxes stay unasked there.
        let group_relay_only = self.listen_extra().iter().any(|e| relay_key(e) == relay_key(relay_url));
        let wraps_refused = refused(SUB_IDENTITY) || group_relay_only;
        let q_1059 = async {
            if wraps_refused { return Ok(Vec::new()); }
            self.client.fetch_events_from([relay_url], wraps_for(*self_pubkey), timeout).await.map(|e| e.to_vec())
        };
        let personas: Vec<(PublicKey, Conn)> = if refused(SUB_PERSONA) || group_relay_only {
            Vec::new()
        } else {
            self.personas.lock().map(|p| {
                p.iter().filter_map(|(k, c)| PublicKey::from_hex(k).ok().map(|pk| (pk, c.client.clone()))).collect()
            }).unwrap_or_default()
        };
        // A persona's inbox and its groups, both on its own connection.
        let mut persona_queries = Vec::new();
        for (pk, conn) in personas {
            if self.persona_relays(&pk).is_some_and(|r| !r.iter().any(|u| relay_key(u) == relay_key(relay_url))) {
                continue;
            }
            if !wraps_refused {
                persona_queries.push((conn.clone(), wraps_for(pk)));
            }
            let tags = self.key_ring.all_tags_where(|g| self.persona_of_group(g) == Some(pk));
            if !tags.is_empty() {
                persona_queries.push((conn, groups_for(tags)));
            }
        }
        let q_personas = futures_util::future::join_all(persona_queries.into_iter().map(|(conn, f)| async move {
            conn.fetch_events_from([relay_url], f, timeout).await.map(|e| e.to_vec())
        }));
        let (r_445, r_1059, r_personas) = tokio::join!(q_445, q_1059, q_personas);
        let mut events = Vec::new();
        match r_445 {
            Ok(ev) => events.extend(ev),
            Err(e) => tracing::warn!("catch-up 445 query failed on {}: {}", relay_url, e),
        }
        for r in std::iter::once(r_1059).chain(r_personas) {
            match r {
                Ok(gw) => events.extend(gw),
                Err(e) => tracing::warn!("catch-up 1059 query failed on {}: {}", relay_url, e),
            }
        }

        let raw_events: Vec<RawMlsEvent> = events
            .iter()
            .filter_map(|event| self.raw_from_event(event, relay_url))
            .collect();

        tracing::debug!(
            "Queried {} events since {} from {} (kinds 443+1059)",
            raw_events.len(),
            since,
            relay_url,
        );

        Ok(raw_events)
    }

    /// Query only kind-443 (MLS envelope) events since a given timestamp.
    pub async fn query_mls_events_since(
        &self,
        relay_url: &str,
        since: u64,
    ) -> Result<Vec<RawMlsEvent>> {
        let filter = self.since_filter_mls_only(relay_url, since);
        let timeout = Duration::from_secs(15);
        let events = self.get_events_of(filter, timeout).await?;

        let raw_events: Vec<RawMlsEvent> = events
            .iter()
            .filter_map(|event| self.raw_from_event(event, relay_url))
            .collect();

        Ok(raw_events)
    }

    /// Query only kind-1059 (gift-wrapped) events since a given timestamp.
    pub async fn query_giftwrap_events_since(
        &self,
        relay_url: &str,
        since: u64,
    ) -> Result<Vec<RawMlsEvent>> {
        let filter = self.since_filter_giftwrap_only(relay_url, since);
        let timeout = Duration::from_secs(15);
        let events = self.get_events_of(filter, timeout).await?;

        let raw_events: Vec<RawMlsEvent> = events
            .iter()
            .filter_map(|event| self.raw_from_event(event, relay_url))
            .collect();

        Ok(raw_events)
    }

    /// Build a catch-up filter for MLS events from a specific author since a
    /// given timestamp.
    pub fn since_filter_for_author(
        &self,
        relay_url: &str,
        author: &PublicKey,
        last_timestamp: u64,
    ) -> Filter {
        tracing::debug!(
            "Building since-filter for relay={}, author={}, since={}",
            relay_url,
            author,
            last_timestamp
        );

        Filter::new()
            .kinds([Kind::MlsKeyPackage, Kind::GiftWrap])
            .author(*author)
            .since(Timestamp::from(last_timestamp))
    }
}

/// Compile-time assertion that `NostrRelayClient` is `Send + Sync`.
const _: () = {
    fn _assert() {
        fn is_send_sync<T: Send + Sync>() {}
        is_send_sync::<NostrRelayClient>();
    }
};

/// TCP-level ping to a WebSocket relay URL.
///
/// Parses `wss://host[:port]/...` (or `ws://...`), opens a TCP connection
/// with the supplied timeout and returns the elapsed time in milliseconds.
/// Returns `None` if the URL is malformed or the host is unreachable within
/// `timeout`.
///
/// Why TCP and not a real WebSocket / NIP-11 round-trip:
/// - Independent of nostr-sdk minor versions (whose per-relay ping APIs
///   are inconsistent between 0.37.x revisions).
/// - Fast: a single SYN/ACK is enough to know the host is alive and
///   approximately how far away it is. Good enough for the red/yellow/
///   green dot UX.
async fn measure_tcp_ping(ws_url: &str, timeout: std::time::Duration) -> Option<u64> {
    // Strip scheme
    let (is_secure, rest) = if let Some(r) = ws_url.strip_prefix("wss://") {
        (true, r)
    } else if let Some(r) = ws_url.strip_prefix("ws://") {
        (false, r)
    } else {
        return None;
    };
    // Strip path/query
    let host_port = rest.split(['/', '?']).next().unwrap_or(rest);
    if host_port.is_empty() {
        return None;
    }
    // Default port for ws/wss
    let addr = if host_port.contains(':') {
        host_port.to_string()
    } else if is_secure {
        format!("{}:443", host_port)
    } else {
        format!("{}:80", host_port)
    };

    let start = std::time::Instant::now();
    // The same path as the relay connections: through the net switch (Tor when
    // private mode is on), never a direct socket that would show the real IP.
    let reached = match network_proxy() {
        Some(proxy) => tokio::time::timeout(timeout, tokio_socks::tcp::Socks5Stream::connect(proxy, addr.as_str()))
            .await
            .is_ok_and(|r| r.is_ok()),
        None => tokio::time::timeout(timeout, tokio::net::TcpStream::connect(&addr))
            .await
            .is_ok_and(|r| r.is_ok()),
    };
    reached.then(|| start.elapsed().as_millis() as u64)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------


/// How long a relay that refused us by its rules is left out of one-off publishes.
const POLICY_REFUSAL_PAUSE: Duration = Duration::from_secs(6 * 3600);

fn policy_refusals() -> &'static std::sync::Mutex<std::collections::HashMap<String, std::time::Instant>> {
    static M: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<String, std::time::Instant>>> = std::sync::OnceLock::new();
    M.get_or_init(|| std::sync::Mutex::new(std::collections::HashMap::new()))
}

/// A refusal by the relay's rules (who may write), not a passing failure:
/// the same event from the same kind of key is refused again.
fn is_policy_refusal(why: &str) -> bool {
    let w = why.to_ascii_lowercase();
    ["web of trust", "restricted:", "blocked:", "not allowed", "auth-required", "pow:", "whitelist", "paid"]
        .iter().any(|m| w.contains(m))
}

fn note_refuses_one_off_keys(url: &str) {
    if let Ok(mut m) = policy_refusals().lock() {
        m.insert(relay_key(url), std::time::Instant::now());
    }
}

fn refuses_one_off_keys(url: &str) -> bool {
    let Ok(mut m) = policy_refusals().lock() else { return false };
    let key = relay_key(url);
    match m.get(&key) {
        Some(at) if at.elapsed() < POLICY_REFUSAL_PAUSE => true,
        Some(_) => { m.remove(&key); false }
        None => false,
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn only_a_relay_s_rules_leave_it_out_of_one_off_publishes() {
        assert!(super::is_policy_refusal("Policy violated and pubkey is not in our web of trust."));
        assert!(super::is_policy_refusal("blocked: not on the list"));
        assert!(super::is_policy_refusal("auth-required: sign in"));
        assert!(!super::is_policy_refusal("timeout"));
        assert!(!super::is_policy_refusal("rate-limited: slow down"));
        assert!(!super::is_policy_refusal("relay not connected"));
        super::note_refuses_one_off_keys("wss://wot.example/");
        assert!(super::refuses_one_off_keys("wss://wot.example"));
        assert!(!super::refuses_one_off_keys("wss://open.example"));
    }

    use super::*;
    use nostr::Keys;

    fn make_client() -> NostrRelayClient {
        NostrRelayClient::new(vec!["wss://test.relay".to_string()])
    }

    #[test]
    fn refusals_pause_timeouts_retry() {
        for r in ["rate-limited: slow down", "event not published: banned: too many rate-limit violations",
                  "auth-required: we only serve members", "blocked: not allowed", "restricted: paid relay"] {
            assert!(is_refusal(r), "{r} must pause the relay");
        }
        for r in ["timeout", "relay not connected (status changed)", "connection reset"] {
            assert!(!is_refusal(r), "{r} must be retried");
        }
    }

    /// The relay ping goes through the configured proxy with the host name
    /// (resolved at the proxy), never as a direct socket.
    #[tokio::test]
    async fn relay_ping_goes_through_the_proxy() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy = listener.local_addr().unwrap();
        let asked = tokio::spawn(async move {
            let (mut s, _) = listener.accept().await.unwrap();
            let mut b = [0u8; 2];
            s.read_exact(&mut b).await.unwrap();
            let mut m = vec![0u8; b[1] as usize];
            s.read_exact(&mut m).await.unwrap();
            s.write_all(&[5, 0]).await.unwrap();
            let mut req = [0u8; 5];
            s.read_exact(&mut req).await.unwrap();
            let mut host = vec![0u8; req[4] as usize];
            s.read_exact(&mut host).await.unwrap();
            let mut port = [0u8; 2];
            s.read_exact(&mut port).await.unwrap();
            s.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
            (String::from_utf8(host).unwrap(), u16::from_be_bytes(port))
        });

        set_network_proxy(Some(proxy));
        let ping = measure_tcp_ping("wss://relay.example.invalid", std::time::Duration::from_secs(5)).await;
        set_network_proxy(None);

        assert!(ping.is_some(), "an unresolvable host is reached only through the proxy");
        assert_eq!(asked.await.unwrap(), ("relay.example.invalid".to_string(), 443));
    }

    #[tokio::test]
    async fn closed_subscription_pauses_the_relay() {
        let c = make_client();
        c.note_relay_refusal("wss://test.relay/", "rate-limited: too fast");
        assert!(c.paused_relays().iter().any(|(u, _)| u == "wss://test.relay"));
        // A plain timeout does not pause.
        let d = make_client();
        d.note_relay_refusal("wss://test.relay", "timeout");
        assert!(d.paused_relays().is_empty());
    }

    /// Helper: check if a filter's kinds set contains a specific kind.
    fn filter_has_kind(filter: &Filter, kind: Kind) -> bool {
        filter.kinds.as_ref().map_or(false, |set| set.contains(&kind))
    }

    /// Helper: check if a filter's authors set contains a specific pubkey.
    fn filter_has_author(filter: &Filter, author: &PublicKey) -> bool {
        filter.authors.as_ref().map_or(false, |set| set.contains(author))
    }

    // â”€â”€ since_filter tests (now includes both 443 and 1059) â”€â”€

    #[test]
    fn since_filter_produces_kind_443_and_1059() {
        let client = make_client();
        let filter = client.since_filter("wss://test.relay", 1000);
        // The filter should contain BOTH kind 443 and kind 1059
        assert!(filter_has_kind(&filter, Kind::MlsKeyPackage), "must include kind 443");
        assert!(filter_has_kind(&filter, Kind::GiftWrap), "must include kind 1059");
    }

    #[test]
    fn since_filter_has_correct_since_timestamp() {
        let client = make_client();
        let ts: u64 = 1700000000;
        let filter = client.since_filter("wss://test.relay", ts);
        assert_eq!(filter.since, Some(Timestamp::from(ts)));
    }

    #[test]
    fn since_filter_json_has_both_kinds_and_since() {
        let client = make_client();
        let filter = client.since_filter("wss://test.relay", 1700000000);
        let json = serde_json::to_value(&filter).unwrap();

        // Verify "kinds" array contains both 443 and 1059
        let kinds = json.get("kinds").expect("filter should have 'kinds'");
        let kinds_arr = kinds.as_array().expect("'kinds' should be an array");
        assert!(kinds_arr.iter().any(|k| k.as_u64() == Some(443)),
            "kinds array should contain 443, got: {:?}", kinds_arr);
        assert!(kinds_arr.iter().any(|k| k.as_u64() == Some(1059)),
            "kinds array should contain 1059, got: {:?}", kinds_arr);

        // Verify "since" field is present
        let since = json.get("since").expect("filter should have 'since'");
        assert_eq!(since.as_u64(), Some(1700000000));
    }

    // â”€â”€ since_filter_mls_only (sealed group events only) â”€â”€

    #[test]
    fn since_filter_mls_only_produces_only_sealed_group_kind() {
        let client = make_client();
        let filter = client.since_filter_mls_only("wss://test.relay", 1000);
        assert!(filter_has_kind(&filter, Kind::from(KIND_SEALED_GROUP)));
        assert!(!filter_has_kind(&filter, Kind::MlsKeyPackage));
        assert!(!filter_has_kind(&filter, Kind::GiftWrap));
    }

    // â”€â”€ since_filter_giftwrap_only (kind 1059 only) â”€â”€

    #[test]
    fn since_filter_giftwrap_only_produces_only_kind_1059() {
        let client = make_client();
        let filter = client.since_filter_giftwrap_only("wss://test.relay", 1000);
        assert!(!filter_has_kind(&filter, Kind::MlsKeyPackage));
        assert!(filter_has_kind(&filter, Kind::GiftWrap));
    }

    // â”€â”€ since_filter_for_author tests â”€â”€

    #[test]
    fn since_filter_for_author_includes_author() {
        let client = make_client();
        let author = Keys::generate().public_key();
        let filter = client.since_filter_for_author("wss://test.relay", &author, 1000);

        assert!(filter_has_author(&filter, &author));
    }

    #[test]
    fn since_filter_for_author_has_both_kinds() {
        let client = make_client();
        let author = Keys::generate().public_key();
        let filter = client.since_filter_for_author("wss://test.relay", &author, 500);

        assert!(filter_has_kind(&filter, Kind::MlsKeyPackage), "must include kind 443");
        assert!(filter_has_kind(&filter, Kind::GiftWrap), "must include kind 1059");
    }

    #[test]
    fn since_filter_for_author_has_correct_since() {
        let client = make_client();
        let author = Keys::generate().public_key();
        let ts: u64 = 1700000000;
        let filter = client.since_filter_for_author("wss://test.relay", &author, ts);

        assert_eq!(filter.since, Some(Timestamp::from(ts)));
    }

    #[test]
    fn since_filter_for_author_json_format() {
        let client = make_client();
        let author = Keys::generate().public_key();
        let filter = client.since_filter_for_author("wss://test.relay", &author, 1700000000);
        let json = serde_json::to_value(&filter).unwrap();

        // Verify "kinds" contains both 443 and 1059
        let kinds = json.get("kinds").expect("filter should have 'kinds'");
        assert!(kinds.as_array().unwrap().iter().any(|k| k.as_u64() == Some(443)));
        assert!(kinds.as_array().unwrap().iter().any(|k| k.as_u64() == Some(1059)));

        // Verify "authors" contains the author's hex pubkey
        let authors = json.get("authors").expect("filter should have 'authors'");
        let authors_arr = authors.as_array().expect("'authors' should be an array");
        assert!(authors_arr.iter().any(|a| a.as_str() == Some(author.to_hex().as_str())),
            "authors should contain the author's pubkey");

        // Verify "since"
        assert_eq!(json.get("since").and_then(|s| s.as_u64()), Some(1700000000));
    }

    // â”€â”€ Regression tests for BUG-02/03 â”€â”€

    #[test]
    fn regression_bug02_filter_uses_correct_kinds() {
        let client = make_client();
        let filter = client.since_filter("wss://relay.example", 0);

        // The filter should contain exactly 2 kinds: 443 and 1059
        let kinds = filter.kinds.as_ref().expect("filter should have kinds set");
        assert_eq!(kinds.len(), 2, "since_filter should have exactly 2 kinds (443 + 1059)");
        assert!(kinds.contains(&Kind::MlsKeyPackage), "must include kind 443");
        assert!(kinds.contains(&Kind::GiftWrap), "must include kind 1059");
    }

    #[test]
    fn regression_bug03_author_filter_includes_kinds_and_author() {
        let client = make_client();
        let author = Keys::generate().public_key();
        let filter = client.since_filter_for_author("wss://relay.example", &author, 0);

        // Must have BOTH kinds AND the author
        assert!(filter_has_kind(&filter, Kind::MlsKeyPackage),
            "BUG-03 regression: author filter must include kind 443");
        assert!(filter_has_kind(&filter, Kind::GiftWrap),
            "author filter must include kind 1059");
        assert!(filter_has_author(&filter, &author),
            "BUG-03 regression: author filter must include the author pubkey");
    }

    // â”€â”€ RawMlsEvent serialization â”€â”€

    #[test]
    fn raw_mls_event_serialization_roundtrip() {
        let event = RawMlsEvent {
            event_id: "abc123".to_string(),
            relay_url: "wss://relay.example".to_string(),
            created_at: 1700000000,
            payload_base64: "AQAAAAAAAAA=".to_string(),
            kind: 443,
            original_event_json: None,
            author_pubkey_hex: String::new(),
        };
        let json = serde_json::to_string(&event).unwrap();
        let parsed: RawMlsEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.event_id, "abc123");
        assert_eq!(parsed.relay_url, "wss://relay.example");
        assert_eq!(parsed.created_at, 1700000000);
        assert_eq!(parsed.payload_base64, "AQAAAAAAAAA=");
        assert_eq!(parsed.kind, 443);
    }

    #[test]
    fn raw_mls_event_kind_1059_serialization() {
        let event = RawMlsEvent {
            event_id: "def456".to_string(),
            relay_url: "wss://relay.example".to_string(),
            created_at: 1700000001,
            payload_base64: "BgAAAAAAAAA=".to_string(),
            kind: 1059,
            original_event_json: None,
            author_pubkey_hex: String::new(),
        };
        let json = serde_json::to_string(&event).unwrap();
        let parsed: RawMlsEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.kind, 1059);
    }

    // â”€â”€ NostrRelayClient construction tests â”€â”€

    #[test]
    fn new_client_stores_relay_urls() {
        let client = NostrRelayClient::new(vec![
            "wss://relay1.example".to_string(),
            "wss://relay2.example".to_string(),
        ]);
        let _ = client.inner();
    }

    #[test]
    fn from_config_creates_client_with_relay_urls() {
        let config = RelayPoolConfig::builder()
            .add_relay("wss://relay1.example")
            .add_relay("wss://relay2.example")
            .build();

        let client = NostrRelayClient::from_config(&config);
        let _ = client.inner();
    }

    // â”€â”€ Kind constants â”€â”€

    #[test]
    fn mls_key_package_kind_is_443() {
        assert_eq!(Kind::MlsKeyPackage.as_u16(), 443);
    }

    #[test]
    fn gift_wrap_kind_is_1059() {
        assert_eq!(Kind::GiftWrap.as_u16(), 1059);
    }

    #[test]
    fn each_event_goes_out_on_its_own_connection() {
        let client = NostrRelayClient::new(vec!["wss://relay1.example".to_string()]);
        let me = Keys::generate();
        let persona = Keys::generate();
        client.set_identity(me.public_key());
        client.set_persona_inboxes(vec![persona.public_key()]);
        let signed = |keys: &Keys, kind: u16| EventBuilder::new(Kind::from(kind), "x").sign_with_keys(keys).unwrap();

        assert!(matches!(client.lane(&signed(&me, 443)), Lane::Identity));
        assert!(matches!(client.lane(&signed(&Keys::generate(), KIND_SEALED_GROUP)), Lane::Groups));
        assert!(matches!(client.lane(&signed(&Keys::generate(), KIND_SEALED_PRESENCE)), Lane::Groups));
        // A persona without its connection, or an unrelated key, never borrows ours.
        assert!(matches!(client.lane(&signed(&persona, 443)), Lane::Detached));
        assert!(matches!(client.lane(&signed(&Keys::generate(), 30078)), Lane::Detached));

        // A group we speak in as a persona: its events wait for that persona's
        // connection rather than ride the shared group one.
        let group = b"secret-group".to_vec();
        client.key_ring().insert_epoch(&group, 1, &[9u8; 32]);
        let (tag, _) = crate::sealed::derive_epoch_keys(&[9u8; 32]);
        let event = EventBuilder::new(Kind::from(KIND_SEALED_GROUP), "x")
            .tags([nostr::Tag::custom(nostr::TagKind::custom(TAG_ROUTE), [hex::encode(tag)])])
            .sign_with_keys(&Keys::generate()).unwrap();
        assert!(matches!(client.lane(&event), Lane::Groups));
        client.set_persona_routes([(group, persona.public_key())].into(), [persona.public_key()].into());
        assert!(matches!(client.lane(&event), Lane::Held));
    }

    #[test]
    fn a_quiet_secret_chat_sleeps_between_visits_and_wakes_when_opened() {
        let quiet = Keys::generate().public_key();
        let recent = Keys::generate().public_key();
        let joining = Keys::generate().public_key();
        let opened = Keys::generate().public_key();
        let day = 24 * 3600;
        // A moment outside the shared visit window.
        let now = 1000 * PERSONA_VISIT_EVERY + PERSONA_VISIT_FOR + 10;
        let plan = |active_at: u64, may_sleep: bool| PersonaPlan { relays: vec![], active_at, may_sleep };
        let plans: std::collections::HashMap<PublicKey, PersonaPlan> = [
            (quiet, plan(now - 2 * day, true)),
            (recent, plan(now - 60, true)),
            (joining, plan(now - 2 * day, false)),
            (opened, plan(now - 2 * day, true)),
        ].into();
        let woken: std::collections::HashSet<PublicKey> = [opened].into();
        assert_eq!(sleeping(&plans, &woken, now), [quiet].into());
        // In the visit window every one of them is up.
        assert!(sleeping(&plans, &woken, 1000 * PERSONA_VISIT_EVERY + 5).is_empty());
    }
}
