use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use tracing::{debug, instrument};

use crate::error::{MediaError, MediaResult};

//  Storage suitability, health and endpoint choice.
//
// Lives INSIDE `BlossomClient` — no second router is created next to the
// existing one. Only knowledge about endpoints lives here; sending,
// encryption and replica search remain in the same client.
//
// Nothing secret is stored: no keys, no media refs, no object ids.
// Only endpoint addresses, capabilities and counters.
//
// State lives in process memory, and correctness does NOT depend on it:
// losing the knowledge just means the first attempt goes blind again.

/// What exactly an endpoint must support for a specific operation.
///
/// Deliberately split. A measurement on real servers showed suitability
/// depends on the REPRESENTATION: chunks travel as a text envelope, the
/// external manifest as a raw stream. A server accepting the first is not
/// obliged to accept the second — a large file can be less available than a small one.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum StorageCapability {
    /// Stream chunk: `application/json` envelope.
    StreamChunkJson,
    /// External manifest: raw `application/octet-stream`.
    ExternalManifestOctet,
}

impl StorageCapability {
    /// Which capability this content type requires.
    pub fn of_content_type(content_type: &str) -> Self {
        if content_type.starts_with("application/json") {
            Self::StreamChunkJson
        } else {
            Self::ExternalManifestOctet
        }
    }
}

/// Why a request failed. The class determines the ACTION, not just the text.
///
/// Before this split everything collapsed into one string and was handled
/// the same way: 415 was retried on every chunk, 429 ignored `Retry-After`,
/// and 401 went into a retry though there was nothing to retry.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailureClass {
    /// 415 — the endpoint does not accept this representation. NOT a disease
    /// of the endpoint: it may be alive and fine with other content.
    /// Excluded only for this capability, until the next probe.
    Capability,
    /// 401/403 — refused on rights or policy. Retrying is pointless.
    AuthPolicy,
    /// 429 — rate limit. `Retry-After` is respected.
    RateLimit,
    /// Timeout, break, 408, 5xx — transient. Penalty and cooldown.
    Transient,
}

impl FailureClass {
    fn of_status(status: u16) -> Self {
        match status {
            415 => Self::Capability,
            401 | 403 => Self::AuthPolicy,
            429 => Self::RateLimit,
            _ => Self::Transient,
        }
    }
}

/// Transport failure with the routing decision preserved.
///
/// Not just text goes up: while the transport is still choosing an endpoint
/// it needs the class, the status and the address. The string is glued at
/// the boundary, where there is nothing left to decide.
#[derive(Debug, Clone)]
pub struct StorageFailure {
    pub endpoint: String,
    pub status: Option<u16>,
    pub class: FailureClass,
    pub retry_after: Option<std::time::Duration>,
    pub detail: String,
}

impl StorageFailure {
    /// Message WITHOUT the full address and without the object id: only the
    /// host name and class reach the log.
    fn short(&self) -> String {
        let host = self
            .endpoint
            .split_once("://")
            .map(|(_, r)| r)
            .unwrap_or(&self.endpoint)
            .split('/')
            .next()
            .unwrap_or("?");
        match self.status {
            Some(s) => format!("{host}: {s} ({:?})", self.class),
            None => format!("{host}: {:?}", self.class),
        }
    }
}

/// An error with its causes (reqwest hides the real reason in `source()`),
/// without the URL: only the host is logged elsewhere.
fn error_chain(e: &reqwest::Error) -> String {
    let mut out = String::new();
    let mut cur: Option<&(dyn std::error::Error + 'static)> = std::error::Error::source(e);
    while let Some(c) = cur {
        if !out.is_empty() { out.push_str(": "); }
        out.push_str(&c.to_string());
        cur = c.source();
    }
    let kind = if e.is_timeout() { "timeout" } else if e.is_connect() { "connect" } else { "request" };
    if out.is_empty() { kind.to_string() } else { format!("{kind}: {out}") }
}

/// Health and capabilities of ONE endpoint.
#[derive(Debug, Default, Clone)]
struct EndpointState {
    /// `None` — not yet established; it must not be probed by banning, or
    /// the first network failure would forever declare the endpoint unsuitable.
    chunk_json: Option<bool>,
    manifest_octet: Option<bool>,
    /// Smoothed latency of a successful request, microseconds. 0 — no data.
    ewma_micros: f64,
    consecutive_failures: u32,
    cooldown_until: Option<std::time::Instant>,
    last_class: Option<FailureClass>,
}

impl EndpointState {
    fn capability(&self, cap: StorageCapability) -> Option<bool> {
        match cap {
            StorageCapability::StreamChunkJson => self.chunk_json,
            StorageCapability::ExternalManifestOctet => self.manifest_octet,
        }
    }

    fn set_capability(&mut self, cap: StorageCapability, ok: bool) {
        match cap {
            StorageCapability::StreamChunkJson => self.chunk_json = Some(ok),
            StorageCapability::ExternalManifestOctet => self.manifest_octet = Some(ok),
        }
    }

    fn cooling(&self, now: std::time::Instant) -> bool {
        self.cooldown_until.map(|t| t > now).unwrap_or(false)
    }

    /// Success: smooth the latency and lift penalties.
    fn note_success(&mut self, cap: StorageCapability, micros: f64) {
        self.set_capability(cap, true);
        self.ewma_micros = if self.ewma_micros == 0.0 {
            micros
        } else {
            self.ewma_micros * EWMA_DECAY + micros * (1.0 - EWMA_DECAY)
        };
        self.consecutive_failures = 0;
        self.cooldown_until = None;
        self.last_class = None;
    }

    /// Failure: the action depends on the class.
    fn note_failure(&mut self, cap: StorageCapability, f: &StorageFailure, now: std::time::Instant) {
        self.last_class = Some(f.class);
        match f.class {
            // Not a disease of the endpoint: the failure counter is untouched
            // and no cooldown is set — it is suitable for something else.
            FailureClass::Capability => self.set_capability(cap, false),
            FailureClass::AuthPolicy => {
                self.consecutive_failures = self.consecutive_failures.saturating_add(1);
                self.cooldown_until = Some(now + AUTH_COOLDOWN);
            }
            FailureClass::RateLimit => {
                self.consecutive_failures = self.consecutive_failures.saturating_add(1);
                let wait = f.retry_after.unwrap_or(RATE_COOLDOWN).min(MAX_COOLDOWN);
                self.cooldown_until = Some(now + wait);
            }
            FailureClass::Transient => {
                self.consecutive_failures = self.consecutive_failures.saturating_add(1);
                // Growth with a ceiling: a burst of breaks must not disable
                // the endpoint forever — networks come back.
                let secs = (1u64 << self.consecutive_failures.min(5)).min(MAX_COOLDOWN.as_secs());
                self.cooldown_until = Some(now + std::time::Duration::from_secs(secs));
            }
        }
    }
}

const EWMA_DECAY: f64 = 0.7;
const AUTH_COOLDOWN: std::time::Duration = std::time::Duration::from_secs(300);
const RATE_COOLDOWN: std::time::Duration = std::time::Duration::from_secs(30);
const MAX_COOLDOWN: std::time::Duration = std::time::Duration::from_secs(120);

/// Descriptor for a blob stored on a Blossom relay.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlobDescriptor {
    /// URL to download the blob.
    pub url: String,
    /// SHA-256 hash of the blob (hex-encoded).
    pub sha256: String,
    /// Size of the blob in bytes.
    pub size: u64,
    /// Unix timestamp (seconds) when the blob was uploaded.
    pub uploaded: u64,
}

/// Wrapper around Nostr keys for signing Blossom authorization events.
///
/// The `MediaSigner` holds the Nostr key pair used to authenticate
/// uploads and deletions on Blossom relays.
pub struct MediaSigner {
    keys: nostr::Keys,
    /// Media uploads: every blob is authorized by a FRESH key, so a server can't
    /// link the chunks of one file (or one user's files) by signer.
    per_blob: bool,
    /// When set, the per-blob key is DERIVED from this private root and the blob
    /// hash: still unlinkable for the server, but we can re-derive it later to
    /// delete exactly that blob ("don't keep on servers" chats).
    derive_root: Option<[u8; 32]>,
}

impl MediaSigner {
    /// Create a new `MediaSigner` from existing Nostr keys.
    pub fn new(keys: nostr::Keys) -> Self {
        Self { keys, per_blob: false, derive_root: None }
    }

    /// Generate a new random key pair.
    pub fn generate() -> Self {
        Self {
            keys: nostr::Keys::generate(),
            per_blob: true,
            derive_root: None,
        }
    }

    /// Per-blob keys derived from `root` (see [`Self::derive_root`]).
    pub fn derived(root: [u8; 32]) -> Self {
        Self { keys: nostr::Keys::generate(), per_blob: true, derive_root: Some(root) }
    }

    fn key_for_blob(&self, sha256_hex: &str) -> Option<nostr::Keys> {
        let root = self.derive_root?;
        let mut input = b"alpha-blossom-blob-key:".to_vec();
        input.extend_from_slice(sha256_hex.as_bytes());
        let secret = blake3::keyed_hash(&root, &input);
        nostr::SecretKey::from_slice(secret.as_bytes()).ok().map(nostr::Keys::new)
    }

    /// Get the public key hex string.
    pub fn public_key(&self) -> String {
        self.keys.public_key().to_hex()
    }

    /// Sign a Blossom authorization event for the given action and SHA-256 hash.
    ///
    /// Creates a kind 24242 event with:
    /// - `t` tag: the action ("upload" or "delete")
    /// - `x` tag: SHA-256 hash of the blob
    /// - `expiration` tag: 5 minutes from now
    fn sign_auth_event(&self, action: &str, sha256_hex: &str) -> MediaResult<String> {
        let now = chrono::Utc::now();
        let expiration = now + chrono::Duration::minutes(5);
        let expiration_ts = nostr::Timestamp::from(expiration.timestamp() as u64);

        let tags = vec![
            nostr::Tag::custom(nostr::TagKind::custom("t"), vec![action.to_string()]),
            nostr::Tag::custom(nostr::TagKind::custom("x"), vec![sha256_hex.to_string()]),
            nostr::Tag::expiration(expiration_ts),
        ];

        // Generic content: the app is not named to the storage server.
        let one_time;
        let keys = if let Some(k) = self.key_for_blob(sha256_hex) {
            one_time = k;
            &one_time
        } else if self.per_blob && action == "upload" {
            one_time = nostr::Keys::generate();
            &one_time
        } else {
            &self.keys
        };
        let event = nostr::EventBuilder::new(nostr::Kind::Custom(24242), "Upload Blob")
            .tags(tags)
            .sign_with_keys(keys)
            .map_err(|e| MediaError::SigningError(format!("Failed to sign auth event: {e}")))?;

        // Serialize to JSON and base64-encode for the Authorization header
        let event_json = serde_json::to_string(&event)
            .map_err(|e| MediaError::SigningError(format!("Failed to serialize event: {e}")))?;

        Ok(base64::Engine::encode(
            &base64::engine::general_purpose::STANDARD,
            event_json,
        ))
    }
}

/// How many mirrors to REPLICATE a blob to. `desired` servers are attempted (in
/// parallel); the upload succeeds as long as at least `required` land — so one
/// dead/slow server degrades redundancy but never blocks a send, and a chunk ends
/// up on several servers so download has real fallback.
#[derive(Debug, Clone, Copy)]
pub struct ReplicationPolicy {
    /// Minimum successful mirrors for the upload to count as success.
    pub required: usize,
    /// How many servers to attempt (upper bound).
    pub desired: usize,
}

impl Default for ReplicationPolicy {
    fn default() -> Self {
        Self { required: 1, desired: 3 }
    }
}

impl ReplicationPolicy {
    /// --- Critical send path ---
    ///
    /// One copy per usable endpoint. No redundancy is created here: while a
    /// human waits for the send, every extra request is their time and
    /// their traffic.
    ///
    /// Why this is not "weakened reliability": with `required = 1`, the
    /// second and third copies did NOT affect the outcome anyway — success
    /// was counted on the first. They only burned requests. Real redundancy
    /// is the background replicator's job ([`Self::background`]) — it owes
    /// no foreign waiting.
    ///
    /// Measured on real servers: of three configured ones, our format is
    /// accepted by ONE. The other two answer 415 on every chunk — not
    /// occasionally but always. With `desired = 3` that is two thirds of
    /// requests doomed from the start.
    pub fn primary() -> Self {
        Self { required: 1, desired: 1 }
    }

    /// --- Background replication ---
    ///
    /// Redundancy belongs here, and fits here: the work runs without a
    /// human waiting on the result. The value is kept exactly as the old
    /// default, so speed does not eat a future retention policy.
    pub fn background() -> Self {
        Self { required: 1, desired: 3 }
    }
}

/// Client for interacting with a Blossom file relay.
///
/// Blossom is a decentralized blob storage protocol built on Nostr.
/// Blobs are uploaded with Nostr-signed authorization events and
/// can be retrieved by URL or deleted by SHA-256 hash.
#[derive(Clone)]
pub struct BlossomClient {
    /// Ordered list of Blossom HTTP server base URLs. Upload tries each in
    /// turn until one succeeds (servers go down / rate-limit / require
    /// payment, so a single endpoint is fragile).
    servers: Vec<String>,
    /// The user's own servers go first; off only for a client that tests one server.
    include_own: bool,
    /// Every request goes through a Tor proxy of its own (a secret chat's route).
    tor: bool,
    http: Arc<reqwest::Client>,
    /// Replica-search memory — see DiscoveryMemory.
    discovery: Arc<DiscoveryMemory>,
    /// Endpoint health and capabilities. One per client, in memory.
    health: Arc<std::sync::Mutex<std::collections::HashMap<String, EndpointState>>>,
}

/// Media servers the user added. They go before the public ones in every
/// client: a new file lands there first, and downloads look there first.
static OWN_SERVERS: std::sync::RwLock<Vec<String>> = std::sync::RwLock::new(Vec::new());

/// Replace the user's own media servers (https base URLs).
pub fn set_own_servers(urls: Vec<String>) {
    let clean: Vec<String> = urls.into_iter()
        .map(|u| u.trim().trim_end_matches('/').to_string())
        .filter(|u| u.starts_with("https://") || u.contains(".onion"))
        .collect();
    if let Ok(mut s) = OWN_SERVERS.write() { *s = clean; }
}

pub fn own_servers() -> Vec<String> {
    OWN_SERVERS.read().map(|s| s.clone()).unwrap_or_default()
}

/// Servers the project's health map finds down or losing blobs: tried last, never dropped
/// (the map is hours old; a server back up still gets its turn).
static MAP_DOWN: std::sync::RwLock<Vec<String>> = std::sync::RwLock::new(Vec::new());

pub fn set_map_down(urls: Vec<String>) {
    let clean = urls.into_iter().map(|u| u.trim().trim_end_matches('/').to_string()).collect();
    if let Ok(mut s) = MAP_DOWN.write() { *s = clean; }
}

fn map_down(server: &str) -> bool {
    let s = server.trim_end_matches('/');
    MAP_DOWN.read().map(|d| d.iter().any(|x| x == s)).unwrap_or(false)
}

/// Servers once in [DEFAULT_BLOSSOM_SERVERS]: older messages still point there.
const RETIRED_BLOSSOM_SERVERS: &[&str] = &["https://blossom.band", "https://cdn.hzrd149.com"];

/// Public Blossom servers that accept anonymous Nostr-signed uploads.
/// Used as a fallback when the caller doesn't supply its own list.
///
/// Measured 2026-11-10 with `examples/blossom_servers_probe.rs` (anonymous
/// one-time-key uploads of our chunks, read back and byte-compared): these
/// store and serve them. Picked for how long they have run and how many use
/// them, each by another operator; the fastest first. cdn.hzrd149.com now
/// wants an account (401) and left the list; primal takes our JSON chunks only.
pub const DEFAULT_BLOSSOM_SERVERS: &[&str] = &[
    "https://blossom.primal.net",
    "https://nostr.download",
    "https://blossom.data.haus",
    "https://blossom.ditto.pub",
    "https://blossom.nmail.li",
    "https://blossom.jumble.social",
    "https://blossom.yakihonne.com",
];

/// The project's long-term store for the official channel: read-only for
/// everyone (uploads need its owner's secret path), so readers fetch from it
/// and find copies on it, but never upload there.
pub const ARCHIVE_BLOSSOM_SERVERS: &[&str] = &["https://rs-blossom-b2.rs-nostr-relay.workers.dev"];

/// Extra copies made in the background after a blob landed on its first server:
/// three in all, so a file outlives two servers dropping it.
/// Downloads find them by content hash on every configured server.
const BACKGROUND_REPLICAS: usize = 2;

/// One chunk upload attempt on the critical path; see [`BlossomClient::upload_mirrored`].
const CHUNK_PUT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(15);

/// Bounds background replication so it never competes with the user's sends.
fn replica_slots() -> &'static tokio::sync::Semaphore {
    static SLOTS: std::sync::OnceLock<tokio::sync::Semaphore> = std::sync::OnceLock::new();
    SLOTS.get_or_init(|| tokio::sync::Semaphore::new(2))
}

/// Redirects are followed only when they cannot be abused for SSRF: at most 3
/// hops, https only, no credentials, and an IP-literal target must be public.
/// Domain targets are safe because every name goes through [`crate::egress::SafeResolver`].
/// (Servers such as blossom.primal.net answer downloads with 302 → CDN.)
fn safe_redirect_policy() -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(|attempt| {
        if attempt.previous().len() >= 3 {
            return attempt.error("too many redirects");
        }
        let url = attempt.url();
        if url.scheme() != "https" || !url.username().is_empty() || url.password().is_some() {
            return attempt.stop();
        }
        let host = url.host_str().unwrap_or("").trim_start_matches('[').trim_end_matches(']');
        match host.parse::<std::net::IpAddr>() {
            Ok(ip) if !crate::egress::is_globally_routable(&ip) => attempt.stop(),
            _ if host.is_empty() => attempt.stop(),
            _ => attempt.follow(),
        }
    })
}

/// Which way a client's requests leave the phone.
enum Route {
    /// The app-wide media route (direct, or Tor in private mode).
    AppWide,
    /// Always this SOCKS proxy.
    Via(std::net::SocketAddr),
}

impl BlossomClient {
    fn http_client(route: Route) -> Arc<reqwest::Client> {
        let builder = reqwest::Client::builder();
        let builder = match route {
            Route::AppWide => crate::with_http_proxy(builder),
            Route::Via(addr) => crate::with_socks_proxy(builder, Some(addr)),
        };
        Arc::new(
            builder
                // A single request here is ONE ~512 KB chunk (streaming up/down) or a
                // small manifest/thumbnail blob — never a whole large file. The old
                // 120 s total timeout meant a dead/hung mirror stalled the WHOLE
                // ordered streaming download for up to MAX_CHUNK_ATTEMPTS × 120 s
                // (~3–8 min "frozen then resumes" — confirmed on device). Fail fast
                // instead: a dead server trips connect_timeout in 12 s, a hung
                // transfer trips the 40 s total, and the per-chunk retry then gets a
                // fresh connection — so a flaky mirror costs seconds, not minutes.
                // Redirects only through `safe_redirect_policy`: an unchecked 302
                // could steer the request to an internal node.
                // Name resolution goes through our policy: a disallowed address
                // never reaches the connection pool, so no check-to-connect
                // window exists (no DNS rebinding). The request name is not
                // rewritten — TLS SNI and certificate checks stay as before.
                .dns_resolver(std::sync::Arc::new(crate::egress::SafeResolver))
                .redirect(safe_redirect_policy())
                .connect_timeout(std::time::Duration::from_secs(12))
                .timeout(std::time::Duration::from_secs(40))
                // Recycle idle sockets fast so a half-dead keep-alive connection is
                // not reused for the next chunk.
                .pool_idle_timeout(std::time::Duration::from_secs(15))
                // Force HTTP/1.1. Over HTTP/2 every concurrent chunk request to a
                // host is MULTIPLEXED onto ONE TCP connection, so when a CDN stalls
                // that connection ALL in-flight chunks freeze together and resume
                // together (exactly the "two files paused at the same instant"
                // symptom). HTTP/1.1 gives each concurrent request its own
                // connection, so one stalled socket can't drag the others down, and
                // a body-read error just drops that single connection.
                .http1_only()
                .build()
                .unwrap_or_default(),
        )
    }

    /// Sanitize a server URL: only keep `http(s)://` endpoints (a `wss://`
    /// Nostr relay is NOT a Blossom server) and strip trailing slashes.
    ///
    /// H3 (RoadmapSecurityAudit): cleartext `http://` is rejected. Blossom auth
    /// (a kind-24242 Nostr-signed event in the `Authorization` header) would
    /// otherwise travel entirely in the clear. The only exception is `.onion`
    /// hosts, where Tor already encrypts the transport. Defaults are HTTPS, so
    /// this only blocks a user/import from adding an insecure server.
    fn sanitize(url: &str) -> Option<String> {
        let u = url.trim().trim_end_matches('/');
        if u.starts_with("https://") {
            Some(u.to_string())
        } else if u.starts_with("http://") && Self::is_onion_authority(u) {
            Some(u.to_string())
        } else if Self::loopback_allowed(u) {
            Some(u.to_string())
        } else {
            None
        }
    }

    /// True if the URL's host is a Tor `.onion` address (authority parsing that
    /// ignores userinfo, port and path).
    fn is_onion_authority(url: &str) -> bool {
        let after_scheme = url.split_once("://").map(|(_, r)| r).unwrap_or(url);
        let authority = after_scheme.split(['/', '?', '#']).next().unwrap_or(after_scheme);
        let host = authority.rsplit_once('@').map(|(_, h)| h).unwrap_or(authority);
        let host = host.split(':').next().unwrap_or(host);
        host.to_ascii_lowercase().ends_with(".onion")
    }

    /// Create a new `BlossomClient` pointing at a single server URL. If the
    /// URL isn't a valid http(s) Blossom endpoint, falls back to the public
    /// default servers.
    pub fn new(relay_url: &str) -> Self {
        let servers = match Self::sanitize(relay_url) {
            Some(u) => vec![u],
            None => DEFAULT_BLOSSOM_SERVERS.iter().map(|s| s.to_string()).collect(),
        };
        Self {
            servers,
            include_own: true,
            tor: false,
            http: Self::http_client(Route::AppWide),
            discovery: Arc::new(DiscoveryMemory::default()),
            health: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
        }
    }

    /// This client talks to its own list only, never the user's servers (a server test).
    pub fn listed_only(mut self) -> Self {
        self.include_own = false;
        self
    }

    /// Like [`Self::with_servers`], through the SOCKS proxy at [proxy] instead
    /// of the app-wide one (a secret chat's Tor-only channel).
    pub fn with_servers_via(urls: &[String], proxy: std::net::SocketAddr) -> Self {
        let mut client = Self::with_servers(urls);
        client.http = Self::http_client(Route::Via(proxy));
        client.tor = true;
        client
    }

    /// Create a client with an explicit ordered server list. Non-http(s)
    /// entries are dropped; if nothing valid remains, the public defaults
    /// are used.
    pub fn with_servers(urls: &[String]) -> Self {
        let mut servers: Vec<String> =
            urls.iter().filter_map(|u| Self::sanitize(u)).collect();
        if servers.is_empty() {
            servers = DEFAULT_BLOSSOM_SERVERS.iter().map(|s| s.to_string()).collect();
        }
        Self {
            servers,
            include_own: true,
            tor: false,
            http: Self::http_client(Route::AppWide),
            discovery: Arc::new(DiscoveryMemory::default()),
            health: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
        }
    }

    /// Upload a blob, trying each configured server until one succeeds.
    ///
    /// The blob is uploaded via PUT with a Nostr-signed authorization event
    /// (kind 24242) in the `Authorization` header.
    #[instrument(skip(self, data, signer), fields(size = data.len()))]
    pub async fn upload(
        &self,
        data: &[u8],
        signer: &MediaSigner,
        // Declared blob MIME. Blossom servers reject the generic
        // `application/octet-stream` for unrecognised binary (encrypted stream
        // chunks) with 415, so the caller passes the real file MIME
        // (e.g. `video/mp4`). Not part of the auth signature (only `t`+`x=sha256`),
        // and the blob is fetched raw on download, so this never affects crypto.
        content_type: &str,
    ) -> MediaResult<BlobDescriptor> {
        let sha256_hex = compute_sha256_hex(data);
        let auth_token = signer.sign_auth_event("upload", &sha256_hex)?;

        let mut last_err = String::from("no Blossom servers configured");
        for server in &self.all_servers() {
            let url = format!("{server}/upload");
            debug!(sha256 = %sha256_hex, server = %server, "Uploading blob to Blossom");

            let response = match self
                .http
                .put(&url)
                .header("Authorization", format!("Nostr {auth_token}"))
                .header("Content-Type", content_type)
                .body(data.to_vec())
                .send()
                .await
            {
                Ok(r) => r,
                Err(e) => {
                    last_err = format!("{server}: request failed: {e}");
                    debug!("{}", last_err);
                    continue;
                }
            };

            if !response.status().is_success() {
                let status = response.status();
                let body = response.text().await.unwrap_or_default();
                last_err = format!("{server}: status {status}: {body}");
                debug!("{}", last_err);
                continue;
            }

            match response.json::<BlobDescriptor>().await {
                Ok(descriptor) => {
                    debug!(url = %descriptor.url, "Blob uploaded successfully");
                    return Ok(descriptor);
                }
                Err(e) => {
                    last_err = format!("{server}: bad response: {e}");
                    debug!("{}", last_err);
                    continue;
                }
            }
        }

        Err(MediaError::UploadFailed(format!(
            "all Blossom servers failed (last: {last_err})"
        )))
    }

    /// Upload ONE blob to ONE specific server. Shared by the parallel replication
    /// path below. `auth_token` is computed once from the blob's sha256 (it signs
    /// the hash, not the server) so all mirrors reuse the same signature.
    async fn upload_to_server(
        &self,
        server: &str,
        data: &[u8],
        content_type: &str,
        auth_token: &str,
    ) -> MediaResult<BlobDescriptor> {
        self.put_once(server, data, content_type, auth_token)
            .await
            .map_err(|f| MediaError::UploadFailed(f.short()))
    }

    /// One request to one endpoint with a TYPED failure.
    ///
    /// It differs from `upload_to_server` in exactly what it exists for:
    /// status, class and `Retry-After` go up instead of a glued string —
    /// otherwise the chooser has nothing to decide on, and every failure
    /// gets the same treatment (415 was retried per chunk, 429 ignored the pause).
    async fn put_once(
        &self,
        server: &str,
        data: &[u8],
        content_type: &str,
        auth_token: &str,
    ) -> Result<BlobDescriptor, StorageFailure> {
        let url = format!("{server}/upload");
        let fail = |status: Option<u16>, retry_after: Option<std::time::Duration>, detail: String| {
            StorageFailure {
                endpoint: server.to_string(),
                status,
                class: status.map(FailureClass::of_status).unwrap_or(FailureClass::Transient),
                retry_after,
                detail,
            }
        };
        let response = self
            .http
            .put(&url)
            .header("Authorization", format!("Nostr {auth_token}"))
            .header("Content-Type", content_type)
            .body(data.to_vec())
            .send()
            .await
            .map_err(|e| fail(None, None, error_chain(&e)))?;
        if !response.status().is_success() {
            let status = response.status().as_u16();
            // `Retry-After` is sometimes seconds; we do not parse dates — a
            // self-chosen pause is safer than a parse mistake.
            let retry_after = response
                .headers()
                .get(reqwest::header::RETRY_AFTER)
                .and_then(|v| v.to_str().ok())
                .and_then(|s| s.trim().parse::<u64>().ok())
                .map(std::time::Duration::from_secs);
            let body = response.text().await.unwrap_or_default();
            return Err(fail(Some(status), retry_after, body));
        }
        response
            .json::<BlobDescriptor>()
            .await
            // The response could not be parsed — the endpoint answers, but
            // not with this; that is transient on its side, not a missing capability.
            .map_err(|e| fail(None, None, format!("bad response: {e}")))
    }

    // --- Endpoint choice ---

    /// Endpoints suitable for this capability, in preference order.
    ///
    /// Rules by descending importance:
    ///   1. known-unsuitable (415 for this capability) — excluded entirely;
    ///   2. cooling down after a failure — at the end, but NOT dropped:
    ///      when one endpoint is suitable, work with it rather than fail;
    ///   3. confirmed suitability ahead of unknown;
    ///   4. lower smoothed latency ahead of higher.
    ///
    /// Unknown (`None`) does not mean unsuitable: otherwise one network
    /// failure would strike out a healthy endpoint forever.
    fn eligible_for(&self, cap: StorageCapability) -> Vec<String> {
        self.eligible_ranked(cap).into_iter().map(|(_, s)| s).collect()
    }

    /// [`Self::eligible_for`] with each server's tier (0 = healthy and confirmed).
    fn eligible_ranked(&self, cap: StorageCapability) -> Vec<(u8, String)> {
        let now = std::time::Instant::now();
        let health = match self.health.lock() {
            Ok(h) => h,
            // The lock is poisoned — fall back to the configured order.
            // Correctness does not depend on health knowledge; it is only speed.
            Err(_) => return self.all_servers().into_iter().map(|s| (1, s)).collect(),
        };
        let own = own_servers();
        let mut ranked: Vec<(u8, u64, String)> = self
            .all_servers()
            .iter()
            .filter_map(|s| {
                let st = health.get(s);
                if st.and_then(|x| x.capability(cap)) == Some(false) {
                    return None; // unsuitable for THIS capability
                }
                let cooling = st.map(|x| x.cooling(now)).unwrap_or(false);
                let known = st.and_then(|x| x.capability(cap)) == Some(true);
                let latency = st.map(|x| x.ewma_micros as u64).unwrap_or(u64::MAX / 2);
                // Sort key: non-cooling and confirmed first; the user's own server leads while it works.
                let tier = match (cooling, known) {
                    (false, _) if own.contains(s) => 0,
                    (false, true) => 0,
                    (false, false) => 1,
                    (true, true) => 2,
                    (true, false) => 3,
                };
                let tier = if !own.contains(s) && map_down(s) { tier.max(3) } else { tier };
                let latency = if own.contains(s) && !cooling { 0 } else { latency };
                Some((tier, latency, s.clone()))
            })
            .collect();
        ranked.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.cmp(&b.1)));
        ranked.into_iter().map(|(t, _, s)| (t, s)).collect()
    }

    fn note_success(&self, server: &str, cap: StorageCapability, micros: f64) {
        if let Ok(mut h) = self.health.lock() {
            h.entry(server.to_string()).or_default().note_success(cap, micros);
        }
    }

    fn note_failure(&self, server: &str, cap: StorageCapability, f: &StorageFailure) {
        if let Ok(mut h) = self.health.lock() {
            h.entry(server.to_string())
                .or_default()
                .note_failure(cap, f, std::time::Instant::now());
        }
    }

    /// Diagnostics for tests and reports: (address, capability, latency µs,
    /// consecutive failures, cooling down). Contains no secrets.
    pub fn health_report(&self) -> Vec<(String, Option<bool>, Option<bool>, u64, u32, bool)> {
        let now = std::time::Instant::now();
        let h = match self.health.lock() {
            Ok(h) => h,
            Err(_) => return Vec::new(),
        };
        self.all_servers()
            .iter()
            .map(|s| {
                let st = h.get(s).cloned().unwrap_or_default();
                (
                    s.clone(),
                    st.chunk_json,
                    st.manifest_octet,
                    st.ewma_micros as u64,
                    st.consecutive_failures,
                    st.cooling(now),
                )
            })
            .collect()
    }

    /// Replicate a blob across up to `policy.desired` servers IN PARALLEL, returning
    /// a [`ChunkLocation`] per successful mirror. Errs only if fewer than
    /// `policy.required` land — a flaky server degrades redundancy, never blocks the
    /// send. This is the upload half of the "no single point of failure per chunk"
    /// design: a chunk that lives on 2–3 servers survives any one going down.
    pub async fn upload_mirrored(
        &self,
        data: &[u8],
        signer: &MediaSigner,
        content_type: &str,
        policy: ReplicationPolicy,
    ) -> MediaResult<Vec<crate::media_ref::ChunkLocation>> {
        let sha256_hex = compute_sha256_hex(data);
        let auth_token = signer.sign_auth_event("upload", &sha256_hex)?;
        let desired = policy.desired.max(1);
        let required = policy.required.max(1);
        let cap = StorageCapability::of_content_type(content_type);

        // --- Traversal order: suitable and healthy first ---
        //
        // It used to fan out over the FIRST `desired` addresses of the
        // config, and with `desired = 3` every chunk went to all three at
        // once. A measurement on real servers showed our format is accepted
        // by ONE of three: two thirds of requests were doomed from the start
        // and only burned slots of the shared budget.
        //
        // Now the address is chosen by the required CAPABILITY, and the
        // attempt count comes from policy: the critical path takes one copy,
        // the background replicator takes as many as asked.
        // Consecutive chunks take turns over every healthy, confirmed server: one server
        // carrying all of a big file is both its bottleneck and the first to rate-limit.
        let ranked = self.eligible_ranked(cap);
        let best = ranked.iter().take_while(|(t, _)| *t == 0).count();
        let mut candidates: Vec<String> = ranked.into_iter().map(|(_, s)| s).collect();
        if best > 1 {
            static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
            candidates[..best].rotate_left(NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed) % best);
        }
        if candidates.is_empty() {
            return Err(MediaError::UploadFailed(
                "нет ни одной точки, принимающей это представление".into(),
            ));
        }

        let mut locations: Vec<crate::media_ref::ChunkLocation> = Vec::new();
        let mut landed: Vec<String> = Vec::new();
        let mut last: Option<StorageFailure> = None;
        for server in &candidates {
            if locations.len() >= desired {
                break;
            }
            let started = std::time::Instant::now();
            // A chunk takes a few seconds even on a slow link. A server that holds it
            // longer is stalling: move on at once rather than wait out the 40 s client
            // timeout while every other chunk of the file is already sent.
            let put = match tokio::time::timeout(CHUNK_PUT_TIMEOUT, self.put_once(server, data, content_type, &auth_token)).await {
                Ok(r) => r,
                Err(_) => Err(StorageFailure {
                    endpoint: server.clone(),
                    status: None,
                    class: FailureClass::Transient,
                    retry_after: None,
                    detail: format!("no answer in {}s", CHUNK_PUT_TIMEOUT.as_secs()),
                }),
            };
            match put {
                Ok(d) => {
                    self.note_success(server, cap, started.elapsed().as_micros() as f64);
                    // The ACTUAL address that accepted the object is recorded
                    // — what the download later looks for. With switching this
                    // is no longer the server that stood first in the config.
                    // The ref travels to every reader: record the public base the
                    // server answered with, never our base (an own server's upload
                    // path may carry its secret).
                    let relay = d.url.rsplit_once('/').map(|(b, _)| b.to_string()).unwrap_or_default();
                    locations.push(crate::media_ref::ChunkLocation { relay, url: d.url });
                    landed.push(server.clone());
                }
                Err(f) => {
                    self.note_failure(server, cap, &f);
                    // A rights refusal and a missing capability are pointless
                    // to retry — move to the next endpoint immediately.
                    // No status = the request never got an answer: say why.
                    if f.status.is_none() {
                        tracing::warn!("STORAGE_FAIL {} cause={}", f.short(), f.detail);
                    } else {
                        debug!("хранилище отклонило: {}", f.short());
                    }
                    last = Some(f);
                }
            }
        }

        if locations.len() < required {
            let why = last.map(|f| f.short()).unwrap_or_else(|| "точек не осталось".into());
            return Err(MediaError::UploadFailed(format!(
                "копий {} из требуемых {} ({why})",
                locations.len(),
                required
            )));
        }
        // Redundancy without making the sender wait: copy the blob to the next
        // suitable servers in the background. A file on one server is lost the
        // day that server drops it.
        if desired == 1 {
            self.replicate_in_background(&candidates, &landed, data, content_type, &auth_token);
        }
        Ok(locations)
    }

    fn replicate_in_background(
        &self,
        candidates: &[String],
        landed: &[String],
        data: &[u8],
        content_type: &str,
        auth_token: &str,
    ) {
        let targets: Vec<String> = candidates
            .iter()
            .filter(|s| !landed.contains(s))
            .take(BACKGROUND_REPLICAS)
            .cloned()
            .collect();
        if targets.is_empty() || tokio::runtime::Handle::try_current().is_err() {
            return;
        }
        let this = self.clone();
        let data = data.to_vec();
        let content_type = content_type.to_string();
        let auth_token = auth_token.to_string();
        let cap = StorageCapability::of_content_type(&content_type);
        tokio::spawn(async move {
            let Ok(_permit) = replica_slots().acquire().await else { return };
            for server in targets {
                let started = std::time::Instant::now();
                match this.put_once(&server, &data, &content_type, &auth_token).await {
                    Ok(_) => this.note_success(&server, cap, started.elapsed().as_micros() as f64),
                    Err(f) => {
                        this.note_failure(&server, cap, &f);
                        debug!("replica refused: {}", f.short());
                    }
                }
            }
        });
    }

    /// Download a blob from the given URL.
    #[instrument(skip(self))]
    pub async fn download(&self, url: &str) -> MediaResult<Vec<u8>> {
        // The address came from a foreign MediaRef — untrusted data. Passing
        // it through the policy is MANDATORY and stands here, at the single
        // exit point, so no call can bypass it.
        let approved = crate::egress::check(url).await?;
        let url = approved.url.as_str();
        debug!(url = %url, "Downloading blob from Blossom relay");

        let response = self
            .http
            .get(url)
            .send()
            .await
            .map_err(|e| MediaError::DownloadFailed(format!("HTTP request failed: {e}")))?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            if matches!(status.as_u16(), 404 | 410) {
                return Err(MediaError::NotOnServer(format!("status {status}")));
            }
            return Err(MediaError::DownloadFailed(format!(
                "Download failed with status {status}: {body}"
            )));
        }

        // The body of an untrusted server is read with a limit. `bytes()`
        // would buffer as much as they send — checking length afterwards is too late.
        Self::read_bounded(response, crate::limits::MAX_MANIFEST_BYTES).await
    }

    /// Delete a blob from the relay by its SHA-256 hash.
    #[instrument(skip(self, signer))]
    pub async fn delete(&self, sha256: &str, signer: &MediaSigner) -> MediaResult<()> {
        debug!(sha256 = %sha256, "Deleting blob from Blossom relay");

        let auth_token = signer.sign_auth_event("delete", sha256)?;

        // Best-effort: ask every server that might hold the blob to drop it.
        let mut last_err = String::from("no Blossom servers configured");
        let mut any_ok = false;
        for server in &self.all_servers() {
            let url = format!("{server}/{sha256}");
            match self
                .http
                .delete(&url)
                .header("Authorization", format!("Nostr {auth_token}"))
                .send()
                .await
            {
                Ok(r) if r.status().is_success() => { any_ok = true; }
                Ok(r) => {
                    let status = r.status();
                    last_err = format!("{server}: status {status}");
                }
                Err(e) => { last_err = format!("{server}: {e}"); }
            }
        }

        if any_ok {
            debug!("Blob deleted successfully");
            Ok(())
        } else {
            Err(MediaError::DeleteFailed(last_err))
        }
    }
}

/// Compute SHA-256 hash of data and return as hex string.
///
/// Blossom relays identify blobs by SHA-256 hash, so this is used
/// for relay operations (not for media integrity, which uses BLAKE3).
pub fn compute_sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let result = hasher.finalize();
    hex::encode(result)
}

/// Compute raw SHA-256 hash bytes.
pub fn compute_sha256(data: &[u8]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let result = hasher.finalize();
    let mut out = [0u8; 32];
    out.copy_from_slice(&result);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_media_signer_generate() {
        let signer = MediaSigner::generate();
        let pk = signer.public_key();
        assert!(!pk.is_empty());
    }

    #[test]
    fn test_sign_auth_event() {
        let signer = MediaSigner::generate();
        let result = signer.sign_auth_event("upload", "abc123");
        assert!(result.is_ok());

        let token = result.unwrap();
        // Should be valid base64
        let decoded = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &token);
        assert!(decoded.is_ok());

        // Should be valid JSON event
        let json: serde_json::Value = serde_json::from_slice(&decoded.unwrap()).unwrap();
        assert_eq!(json["kind"], 24242);
    }

    #[test]
    fn test_compute_sha256() {
        let data = b"hello world";
        let hash = compute_sha256(data);
        assert_eq!(hash.len(), 32);

        // SHA-256 of "hello world" is well-known
        let expected = hex::encode(hash);
        assert_eq!(
            expected,
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        );
    }

    #[test]
    fn test_compute_sha256_hex() {
        let data = b"hello world";
        let hex_str = compute_sha256_hex(data);
        assert_eq!(
            hex_str,
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        );
    }

    #[test]
    fn test_blossom_client_new() {
        let client = BlossomClient::new("https://blossom.example.com");
        assert_eq!(client.servers.first().map(|s| s.as_str()), Some("https://blossom.example.com"));
    }

    #[test]
    fn test_blossom_client_trims_trailing_slash() {
        let client = BlossomClient::new("https://blossom.example.com/");
        assert_eq!(client.servers.first().map(|s| s.as_str()), Some("https://blossom.example.com"));
    }
}

//  TRANSPORT V1 — capability probe of real Blossom servers.
//
//  RUN EXPLICITLY ONLY:
//      cargo test -p alpha-media t1_probe -- --ignored --nocapture
//
//  Marked `#[ignore]` deliberately. It is network diagnostics, not a test:
//  it talks to foreign servers, and its place is in a human's hands — not
//  in a normal run, let alone in the app. It does nothing destructive:
//  one 8 KiB blob per representation, no attempts to probe size limits or
//  trigger 429 — pushing a foreign server to failure for a table row is
//  unacceptable. What cannot be established safely stays UNKNOWN.
#[cfg(test)]
mod t1_probe {
    use super::*;

    /// 8 KiB of high entropy — ciphertext-like but deliberately small.
    fn probe_body(seed: u8) -> Vec<u8> {
        (0..8192u32)
            .map(|i| (i.wrapping_mul(2654435761) >> 13) as u8 ^ seed)
            .collect()
    }

    /// A full circle for ONE representation on ONE server.
    ///
    /// Checks exactly what the transport will rely on:
    ///   body → SHA256(local) → PUT → descriptor.sha256 → GET(hash) → SHA256(back)
    /// All four identities go into the result, not collapsed into "ok":
    /// any pair diverging means replica discovery by content-id cannot work.
    async fn probe_one(server: &str, content_type: &str, seed: u8) -> String {
        let client = BlossomClient::new(server);
        let signer = MediaSigner::generate();
        let body = probe_body(seed);
        let local = compute_sha256_hex(&body);

        let descriptor = match client.upload(&body, &signer, content_type).await {
            Ok(d) => d,
            Err(e) => return format!("  {content_type:<26} PUT=FAIL ({e})"),
        };
        let hash_matches = descriptor.sha256.eq_ignore_ascii_case(&local);
        let hash_is_lower_hex = descriptor.sha256.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c));

        // GET by content-id, NOT by the returned URL: probe exactly what
        // replica discovery on a foreign server will stand on.
        let by_hash_url = format!("{}/{}", server.trim_end_matches('/'), descriptor.sha256);
        let (get_ok, bytes_equal, back_hash) = match client.download(&by_hash_url).await {
            Ok(back) => (true, back == body, compute_sha256_hex(&back)),
            Err(e) => (false, false, format!("FAIL: {e}")),
        };

        // A repeated PUT of the same object — is it safe (idempotency).
        let repeat = match client.upload(&body, &signer, content_type).await {
            Ok(d2) => format!("OK same_hash={}", d2.sha256.eq_ignore_ascii_case(&local)),
            Err(e) => format!("FAIL ({e})"),
        };

        format!(
            "  {content_type:<26} PUT=OK\n\
             \x20   local_sha256      = {local}\n\
             \x20   descriptor.sha256 = {}\n\
             \x20   C. descriptor==local : {}\n\
             \x20   hash_is_lowercase_hex: {}\n\
             \x20   returned_url         : {}\n\
             \x20   A. GET-by-hash       : {}\n\
             \x20   B. bytes_identical   : {}   sha256(downloaded)={}\n\
             \x20   E. repeated_PUT      : {}",
            descriptor.sha256, hash_matches, hash_is_lower_hex, descriptor.url,
            get_ok, bytes_equal, back_hash, repeat,
        )
    }

    #[tokio::test]
    #[ignore = "сетевая диагностика: cargo test -p alpha-media t1_probe -- --ignored --nocapture"]
    async fn t1_capability_matrix() {
        println!("\n=== T1 CAPABILITY MATRIX ===");
        println!("тело пробы: 8192 B, по одному на representation");
        for (i, server) in crate::DEFAULT_BLOSSOM_SERVERS.iter().enumerate() {
            println!("\n[{server}]");
            // The current production envelope format is JSON.
            println!("{}", probe_one(server, "application/json", i as u8).await);
            // A candidate that is only MEASURED here, never enabled.
            println!("{}", probe_one(server, "application/octet-stream", (i + 100) as u8).await);
        }
        println!("\nлимиты размера и 429/Retry-After: UNKNOWN — безопасно не устанавливаются");
        println!("=== END ===\n");
    }
}

#[cfg(test)]
mod t2_manifest_probe {
    use super::*;

    /// A body of the SAME shape as the external manifest: a raw binary block
    /// `nonce(24) || blake3(32) || size(8, LE) || ciphertext`.
    ///
    /// This is what must be probed. Suitability depends on the
    /// REPRESENTATION: chunks travel as a text JSON envelope, the manifest
    /// as raw `application/octet-stream`, past the envelope. A server
    /// accepting chunks is not obliged to accept the manifest, so a large
    /// file can be less available than a small one.
    fn manifest_shaped_body() -> Vec<u8> {
        let mut b = Vec::with_capacity(64 + 4096);
        b.extend_from_slice(&[0x5Au8; 24]);                 // nonce
        b.extend_from_slice(&[0xA5u8; 32]);                 // blake3 of the plaintext
        b.extend_from_slice(&(4096u64).to_le_bytes());      // size
        b.extend((0..4096u32).map(|i| (i.wrapping_mul(2654435761) >> 11) as u8));
        b
    }

    #[tokio::test]
    #[ignore = "сетевая диагностика: cargo test -p alpha-media t2_manifest_probe -- --ignored --nocapture"]
    async fn t2_manifest_capability_matrix() {
        println!("\n=== T2 MANIFEST CAPABILITY MATRIX ===");
        let body = manifest_shaped_body();
        let local = compute_sha256_hex(&body);
        println!("тело манифест-образное: {} B, sha256={local}", body.len());
        for server in crate::DEFAULT_BLOSSOM_SERVERS.iter() {
            let client = BlossomClient::new(server);
            let signer = MediaSigner::generate();
            match client.upload(&body, &signer, "application/octet-stream").await {
                Err(e) => println!("[{server}] EXTERNAL_MANIFEST_OCTET_STREAM = UNSUPPORTED ({e})"),
                Ok(d) => {
                    let id_ok = d.sha256.eq_ignore_ascii_case(&local);
                    let by_hash = format!("{}/{}", server.trim_end_matches('/'), d.sha256);
                    let (get_ok, equal) = match client.download(&by_hash).await {
                        Ok(back) => (true, back == body),
                        Err(_) => (false, false),
                    };
                    println!(
                        "[{server}] EXTERNAL_MANIFEST_OCTET_STREAM = SUPPORTED\n\
                         \x20   manifest_hash == descriptor.sha256 : {id_ok}\n\
                         \x20   GET by content-id                  : {get_ok}\n\
                         \x20   bytes identical                    : {equal}",
                    );
                }
            }
        }
        println!("=== END ===\n");
    }
}

/// Replica-search memory for the client's lifetime.
///
/// Two different quantities that must not be mixed:
///  * `missing` — the (server, object) pair. A "no such object" answer
///    speaks about exactly that object. Under partial replication a
///    neighbor may lack chunk 17 and happily have chunk 18, so striking
///    out a whole server over one miss makes the file unavailable yourself.
///  * `preferred` — a server that has already served objects. The next
///    searches start from it: thanks to this, one dead server does not
///    become an extra request on every one of six hundred chunks.
#[derive(Default)]
pub(crate) struct DiscoveryMemory {
    missing: std::sync::Mutex<std::collections::HashSet<(String, String)>>,
    preferred: std::sync::Mutex<Option<String>>,
}

impl BlossomClient {
    /// Whether THIS OBJECT is known to be missing on this server.
    pub(crate) fn discovery_missing(&self, server: &str, object_id: &str) -> bool {
        self.discovery
            .missing
            .lock()
            .map(|m| m.contains(&(server.to_string(), object_id.to_string())))
            .unwrap_or(false)
    }

    pub(crate) fn discovery_note_missing(&self, server: &str, object_id: &str) {
        if let Ok(mut m) = self.discovery.missing.lock() {
            // Memory bound: the search must not become a leak on a long
            // session. When full, start over — correctness is unchanged,
            // only the cost.
            if m.len() > 4096 {
                m.clear();
            }
            m.insert((server.to_string(), object_id.to_string()));
        }
    }

    pub(crate) fn discovery_note_hit(&self, server: &str) {
        if let Ok(mut p) = self.discovery.preferred.lock() {
            *p = Some(server.to_string());
        }
    }

    /// Requests leave through Tor (this client's route, or the app-wide proxy).
    pub fn via_tor(&self) -> bool {
        self.tor || crate::http_proxy_active()
    }

    /// A server this user chose or the app ships (or once shipped) with — not one
    /// a sender put into a message. Only these are contacted without Tor.
    pub fn is_known(&self, url: &str) -> bool {
        fn host(u: &str) -> String {
            u.split("://").nth(1).and_then(|r| r.split('/').next()).unwrap_or(u).to_ascii_lowercase()
        }
        let h = host(url);
        self.all_servers().iter().map(|s| host(s))
            .chain(DEFAULT_BLOSSOM_SERVERS.iter().chain(RETIRED_BLOSSOM_SERVERS).chain(ARCHIVE_BLOSSOM_SERVERS).map(|s| host(s)))
            .any(|k| k == h)
    }

    /// The user's own servers, then this client's list, without repeats.
    pub(crate) fn all_servers(&self) -> Vec<String> {
        let mut out = if self.include_own { own_servers() } else { Vec::new() };
        for s in &self.servers {
            if !out.iter().any(|o| o.trim_end_matches('/') == s.trim_end_matches('/')) { out.push(s.clone()); }
        }
        out
    }

    /// Configured servers in search order: the one that worked — first.
    pub(crate) fn configured_servers(&self) -> Vec<String> {
        let preferred = self.discovery.preferred.lock().ok().and_then(|p| p.clone());
        let all = self.all_servers();
        let mut out: Vec<String> = Vec::with_capacity(all.len());
        if let Some(p) = preferred {
            if all.iter().any(|s| s.trim_end_matches('/') == p) {
                out.push(p);
            }
        }
        for s in &all {
            if !out.iter().any(|o| o.trim_end_matches('/') == s.trim_end_matches('/')) {
                out.push(s.clone());
            }
        }
        // Read-only archive last; skipped when the owner already lists it under its upload path.
        let host = |u: &str| u.split("://").nth(1).and_then(|r| r.split('/').next()).unwrap_or(u).to_ascii_lowercase();
        for a in ARCHIVE_BLOSSOM_SERVERS {
            if !out.iter().any(|o| host(o) == host(a)) {
                out.push(a.to_string());
            }
        }
        out
    }
}

impl BlossomClient {
    /// Find an object by its identity on any CONFIGURED storage.
    ///
    /// The public entry for those without a list of hint addresses — first
    /// of all the external manifest: its identity (`manifest_hash`) sits in
    /// the ref, and its single address may have died with the server.
    ///
    /// Identity verification is MANDATORY here and done in place: a 200
    /// answer does not prove the server returned that very object.
    /// Cryptographic properties of the content are checked separately, higher up the stack.
    pub async fn discover_by_content_id(&self, content_id: &str) -> MediaResult<Vec<u8>> {
        let id = content_id.trim().to_ascii_lowercase();
        if id.len() != 64 || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(MediaError::DownloadFailed("content-id: не 64 hex".into()));
        }
        let mut last: Option<MediaError> = None;
        // Any answer other than "not here" (a timeout, a 5xx) leaves the object possibly alive.
        let servers = self.configured_servers();
        let mut unsure = servers.is_empty();
        for server in servers {
            let base = server.trim_end_matches('/');
            if self.discovery_missing(base, &id) {
                continue;
            }
            match self.download(&format!("{base}/{id}")).await {
                Ok(body) => {
                    if compute_sha256_hex(&body).eq_ignore_ascii_case(&id) {
                        self.discovery_note_hit(base);
                        return Ok(body);
                    }
                    // The server answered but returned the WRONG object. Cannot accept.
                    tracing::warn!(target: "alpha_ffi", "DISCOVER {base}: тело не соответствует идентичности");
                    self.discovery_note_missing(base, &id);
                    last = Some(MediaError::DownloadFailed("content-id mismatch".into()));
                }
                Err(e) => {
                    // Only "not here" is remembered: a timeout says nothing about the object.
                    if matches!(e, MediaError::NotOnServer(_)) { self.discovery_note_missing(base, &id); } else { unsure = true; }
                    last = Some(e);
                }
            }
        }
        if unsure { Err(last.unwrap_or(MediaError::DownloadFailed("нет кандидатов".into()))) } else { Err(MediaError::Gone) }
    }
}

impl BlossomClient {
    /// The local acceptance testbench. Exists ONLY in test builds.
    ///
    /// Split via `#[cfg]`, not `if cfg!(test)`: in the latter the branch is
    /// physically present in production code, held up only by the optimizer
    /// folding it away. The "HTTPS only to the outside" invariant must be
    /// guaranteed by the compiler, not by hoping for an optimization.
    #[cfg(test)]
    fn loopback_allowed(u: &str) -> bool {
        u.starts_with("http://127.0.0.1:")
    }

    #[cfg(not(test))]
    #[inline(always)]
    fn loopback_allowed(_u: &str) -> bool {
        false
    }
}

#[cfg(test)]
mod t2_manifest_acceptance {
    use super::*;
    use std::collections::HashMap;

    /// The same local testbench as for chunk acceptance: "A is dead, the
    /// object is on B" cannot be proven on live servers — there one can
    /// neither kill a node nor swap the bytes.
    async fn spawn(objects: HashMap<String, Vec<u8>>) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else { return };
                let objects = objects.clone();
                tokio::spawn(async move {
                    use tokio::io::{AsyncReadExt, AsyncWriteExt};
                    let mut buf = [0u8; 2048];
                    let n = sock.read(&mut buf).await.unwrap_or(0);
                    if n == 0 { return; }
                    let req = String::from_utf8_lossy(&buf[..n]).to_string();
                    let path = req.split_whitespace().nth(1).unwrap_or("/")
                        .trim_start_matches('/').to_string();
                    let resp = match objects.get(&path) {
                        Some(b) => {
                            let mut r = format!(
                                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", b.len()
                            ).into_bytes();
                            r.extend_from_slice(b);
                            r
                        }
                        None => b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),
                    };
                    let _ = sock.write_all(&resp).await;
                    let _ = sock.shutdown().await;
                });
            }
        });
        addr
    }

    /// A manifest-shaped body: a raw binary block as written by
    /// `externalize_ref_if_large`.
    fn manifest_body() -> Vec<u8> {
        let mut b = vec![0x5Au8; 24];
        b.extend_from_slice(&[0xA5u8; 32]);
        b.extend_from_slice(&(2048u64).to_le_bytes());
        b.extend((0..2048u32).map(|i| (i.wrapping_mul(2654435761) >> 11) as u8));
        b
    }

    /// A→B MANIFEST: the only address is dead, a copy of the manifest is on B.
    #[tokio::test]
    async fn t2_manifest_discovered_on_second_server() {
        let body = manifest_body();
        let id = compute_sha256_hex(&body);
        let a = spawn(HashMap::new()).await;                                  // missing for this object
        let b = spawn(HashMap::from([(id.clone(), body.clone())])).await;

        let client = BlossomClient::with_servers(&[a.clone(), b.clone()]);
        // The direct address on A does not answer — exactly as in resolve_manifest.
        assert!(client.download(&format!("{a}/{id}")).await.is_err());
        let got = client.discover_by_content_id(&id).await
            .expect("манифест обязан найтись на втором хранилище");
        assert_eq!(got, body);
    }

    /// Manifest corruption: B serves OTHER bytes for the right request.
    #[tokio::test]
    async fn t2_manifest_corruption_rejected() {
        let real = manifest_body();
        let id = compute_sha256_hex(&real);
        let mut tampered = real.clone();
        tampered[100] ^= 0xFF;                       // one bit — a different object already
        let b = spawn(HashMap::from([(id.clone(), tampered)])).await;

        let client = BlossomClient::with_servers(&[b]);
        let r = client.discover_by_content_id(&id).await;
        assert!(r.is_err(), "подменённый манифест обязан быть отвергнут по идентичности");
    }

    /// An invalid identity generates no arbitrary requests.
    #[tokio::test]
    async fn t2_manifest_invalid_identity_never_builds_a_request() {
        let a = spawn(HashMap::new()).await;
        let client = BlossomClient::with_servers(&[a]);
        for bad in ["", "../etc/passwd", "nothex", &"a".repeat(63)] {
            assert!(client.discover_by_content_id(bad).await.is_err(), "принято: {bad}");
        }
    }
}

impl BlossomClient {
    /// Read a response body with a HARD limit.
    ///
    /// `response.bytes()` buffers as much as the server sends, and checking
    /// the length AFTER that is pointless: the memory is already taken.
    /// The server is untrusted — it may omit the length or lie about it, so
    /// the declared length is checked before reading, and at most `limit
    /// + 1` bytes are read, to catch the lie on the very first extra byte.
    async fn read_bounded(
        mut response: reqwest::Response,
        limit: u64,
    ) -> MediaResult<Vec<u8>> {
        if let Some(declared) = response.content_length() {
            if declared > limit {
                return Err(MediaError::DownloadFailed(format!(
                    "тело объявлено как {declared} Б при пределе {limit} Б"
                )));
            }
        }
        let mut out: Vec<u8> = Vec::new();
        while let Some(part) = response
            .chunk()
            .await
            .map_err(|e| MediaError::DownloadFailed(format!("чтение тела: {e}")))?
        {
            out.extend_from_slice(&part);
            if out.len() as u64 > limit {
                return Err(MediaError::DownloadFailed(format!(
                    "тело превысило предел {limit} Б — сервер объявил не то, что прислал"
                )));
            }
        }
        Ok(out)
    }
}
