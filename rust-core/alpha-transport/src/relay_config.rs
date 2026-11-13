//! Configuration for relay connections.
//!
//! Provides [`RelayConfig`] for individual relay settings and [`RelayPoolConfig`]
//! for managing a set of relays. Both support the builder pattern for
//! ergonomic construction.
//!
//! # Default Relays
//!
//! [`RelayPoolConfig::default_relays()` returns a pool pre-configured with
//! 5 public Nostr relays for out-of-the-box connectivity. Users can
//! add/remove relays and toggle read/write flags per relay.
//!
//! # Read/Write Flags
//!
//! Each relay has independent `read` and `write` flags:
//! - `read`: Subscribe to events from this relay (incoming messages).
//! - `write`: Publish events to this relay (outgoing messages).
//!
//! This allows asymmetric configurations, e.g. a fast local relay for
//! writing and a slow public relay for reading only.

use std::time::Duration;

/// Default connection timeout in seconds.
const DEFAULT_CONNECTION_TIMEOUT_SECS: u64 = 30;

// ---------------------------------------------------------------------------
// Default public relay URLs
// ---------------------------------------------------------------------------

/// Default public Nostr relay URLs for out-of-the-box connectivity.
///
/// These are well-known, high-availability public relays. Users can
/// replace or extend this list in Settings → Relays.
// Three rock-solid, high-uptime relays. The flaky ones (nostr.band, snort,
// wellorder) are gone — they timed out and dragged every publish. Users can
// add/remove/ping more in Settings → Relays.
// A single default relay is a single point of failure: when Damus returns 503
// (it does, under load) KeyPackage publish and group delivery break entirely.
// We seed THREE rock-solid relays for redundancy — publish succeeds if ANY of
// them accepts, and peers can fetch KeyPackages from whichever is up. These
// three are reliable; the previously-flaky ones (nostr.band, wellorder, snort)
// stay out. Users still add/remove any relay in Settings → Relays.
//
// Six since 2026-10: one relay that throttles or bans (Damus banned both test
// phones for a startup burst) must never decide whether a message arrives.
// The added ones were probed open (no auth, no payment) and fast; offchain.pub
// was dropped again: it refuses keys outside its "web of trust".
//
// 2026-11 (examples/relay_probe.rs, relay_history.rs): every one takes our
// four kinds from a fresh key, serves them back and keeps 30 days of history.
// nos.lol and nostr.mom (one host) refuse connections from everywhere; snort
// keeps no history (nothing to catch up from); noswhere stores nothing.
// nostr.bitcoiner.social later refused every event of a one-off key (its "web
// of trust"): all our group events and gift wraps are such.
pub const DEFAULT_RELAYS: &[&str] = &[
    "wss://relay.primal.net",
    "wss://nostr.oxtr.dev",
    "wss://relay.nostr.net",
    "wss://nostr.data.haus",
    "wss://nostr-01.yakihonne.com",
];

// ---------------------------------------------------------------------------
// RelayConfig
// ---------------------------------------------------------------------------

/// Configuration for a single Nostr relay connection.
#[derive(Debug, Clone)]
pub struct RelayConfig {
    /// The WebSocket URL of the relay (e.g. `wss://relay.damus.io`).
    pub url: String,
    /// Whether to subscribe to events from this relay (read).
    pub read: bool,
    /// Whether to publish events to this relay (write).
    pub write: bool,
    /// Whether this relay is enabled. Disabled relays are kept in
    /// configuration but not connected.
    pub enabled: bool,
    /// Connection timeout for establishing the WebSocket.
    pub connection_timeout: Duration,
}

impl RelayConfig {
    /// Create a new `RelayConfig` with the given URL and default settings.
    ///
    /// By default, both read and write are enabled, the relay is enabled,
    /// and the connection timeout is 30 seconds.
    pub fn new(url: impl Into<String>) -> Self {
        Self {
            url: url.into(),
            read: true,
            write: true,
            enabled: true,
            connection_timeout: Duration::from_secs(DEFAULT_CONNECTION_TIMEOUT_SECS),
        }
    }

    /// Return a builder initialised with this config's values.
    pub fn builder(url: impl Into<String>) -> RelayConfigBuilder {
        RelayConfigBuilder {
            config: Self::new(url),
        }
    }

    /// Whether this relay is usable for reading (enabled AND read flag).
    pub fn is_readable(&self) -> bool {
        self.enabled && self.read
    }

    /// Whether this relay is usable for writing (enabled AND write flag).
    pub fn is_writable(&self) -> bool {
        self.enabled && self.write
    }
}

// ---------------------------------------------------------------------------
// RelayConfigBuilder
// ---------------------------------------------------------------------------

/// Builder for [`RelayConfig`].
#[derive(Debug, Clone)]
pub struct RelayConfigBuilder {
    config: RelayConfig,
}

impl RelayConfigBuilder {
    /// Set whether the relay is used for reading (subscribing to events).
    pub fn read(mut self, read: bool) -> Self {
        self.config.read = read;
        self
    }

    /// Set whether the relay is used for writing (publishing events).
    pub fn write(mut self, write: bool) -> Self {
        self.config.write = write;
        self
    }

    /// Set whether the relay is enabled.
    pub fn enabled(mut self, enabled: bool) -> Self {
        self.config.enabled = enabled;
        self
    }

    /// Set the connection timeout.
    pub fn connection_timeout(mut self, timeout: Duration) -> Self {
        self.config.connection_timeout = timeout;
        self
    }

    /// Build the final [`RelayConfig`].
    pub fn build(self) -> RelayConfig {
        self.config
    }
}

// ---------------------------------------------------------------------------
// RelayPoolConfig
// ---------------------------------------------------------------------------

/// Configuration for a pool of relay connections.
#[derive(Debug, Clone)]
pub struct RelayPoolConfig {
    /// The relay configurations in this pool.
    pub relays: Vec<RelayConfig>,
}

impl RelayPoolConfig {
    /// Create an empty pool configuration.
    pub fn new() -> Self {
        Self { relays: Vec::new() }
    }

    /// Return a builder initialised with an empty pool.
    pub fn builder() -> RelayPoolConfigBuilder {
        RelayPoolConfigBuilder {
            config: Self::new(),
        }
    }

    /// Create a pool pre-configured with default public relays.
    ///
    /// All default relays have both read and write enabled.
    pub fn default_relays() -> Self {
        let mut config = Self::new();
        for url in DEFAULT_RELAYS {
            config.add_relay(RelayConfig::new(*url));
        }
        config
    }

    /// Add a relay configuration to the pool.
    pub fn add_relay(&mut self, config: RelayConfig) {
        // Avoid duplicate URLs.
        if !self.relays.iter().any(|r| r.url == config.url) {
            self.relays.push(config);
        }
    }

    /// Remove a relay by URL.
    pub fn remove_relay(&mut self, url: &str) -> bool {
        let before = self.relays.len();
        self.relays.retain(|r| r.url != url);
        self.relays.len() != before
    }

    /// Get the list of relay URLs.
    pub fn relay_urls(&self) -> Vec<&str> {
        self.relays.iter().map(|r| r.url.as_str()).collect()
    }

    /// Get the list of enabled relay URLs.
    pub fn enabled_relay_urls(&self) -> Vec<&str> {
        self.relays
            .iter()
            .filter(|r| r.enabled)
            .map(|r| r.url.as_str())
            .collect()
    }

    /// Get the list of readable (enabled + read) relay URLs.
    pub fn readable_relay_urls(&self) -> Vec<&str> {
        self.relays
            .iter()
            .filter(|r| r.is_readable())
            .map(|r| r.url.as_str())
            .collect()
    }

    /// Get the list of writable (enabled + write) relay URLs.
    pub fn writable_relay_urls(&self) -> Vec<&str> {
        self.relays
            .iter()
            .filter(|r| r.is_writable())
            .map(|r| r.url.as_str())
            .collect()
    }

    /// Number of relays configured.
    pub fn len(&self) -> usize {
        self.relays.len()
    }

    /// Whether the pool is empty.
    pub fn is_empty(&self) -> bool {
        self.relays.is_empty()
    }

    /// Find a relay by URL.
    pub fn find(&self, url: &str) -> Option<&RelayConfig> {
        self.relays.iter().find(|r| r.url == url)
    }

    /// Find a relay by URL (mutable).
    pub fn find_mut(&mut self, url: &str) -> Option<&mut RelayConfig> {
        self.relays.iter_mut().find(|r| r.url == url)
    }

    /// Update the read/write/enabled flags for a specific relay.
    pub fn update_relay_flags(
        &mut self,
        url: &str,
        read: Option<bool>,
        write: Option<bool>,
        enabled: Option<bool>,
    ) -> bool {
        if let Some(relay) = self.find_mut(url) {
            if let Some(r) = read {
                relay.read = r;
            }
            if let Some(w) = write {
                relay.write = w;
            }
            if let Some(e) = enabled {
                relay.enabled = e;
            }
            true
        } else {
            false
        }
    }
}

impl Default for RelayPoolConfig {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// RelayPoolConfigBuilder
// ---------------------------------------------------------------------------

/// Builder for [`RelayPoolConfig`].
#[derive(Debug, Clone)]
pub struct RelayPoolConfigBuilder {
    config: RelayPoolConfig,
}

impl RelayPoolConfigBuilder {
    /// Add a relay by URL with default settings (read + write).
    pub fn add_relay(mut self, url: impl Into<String>) -> Self {
        self.config.add_relay(RelayConfig::new(url));
        self
    }

    /// Add a relay with a custom [`RelayConfig`].
    pub fn add_relay_config(mut self, config: RelayConfig) -> Self {
        self.config.add_relay(config);
        self
    }

    /// Add a read-only relay.
    pub fn add_read_only_relay(mut self, url: impl Into<String>) -> Self {
        self.config.add_relay(
            RelayConfig::builder(url).read(true).write(false).build(),
        );
        self
    }

    /// Add a write-only relay.
    pub fn add_write_only_relay(mut self, url: impl Into<String>) -> Self {
        self.config.add_relay(
            RelayConfig::builder(url).read(false).write(true).build(),
        );
        self
    }

    /// Build the final [`RelayPoolConfig`].
    pub fn build(self) -> RelayPoolConfig {
        self.config
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // ── RelayConfig tests ──

    #[test]
    fn relay_config_new_defaults() {
        let config = RelayConfig::new("wss://relay.example");
        assert_eq!(config.url, "wss://relay.example");
        assert!(config.read);
        assert!(config.write);
        assert!(config.enabled);
        assert_eq!(config.connection_timeout, Duration::from_secs(30));
    }

    #[test]
    fn relay_config_builder_read_only() {
        let config = RelayConfig::builder("wss://relay.example")
            .read(true)
            .write(false)
            .build();
        assert!(config.read);
        assert!(!config.write);
        assert!(config.is_readable());
        assert!(!config.is_writable());
    }

    #[test]
    fn relay_config_builder_write_only() {
        let config = RelayConfig::builder("wss://relay.example")
            .read(false)
            .write(true)
            .build();
        assert!(!config.read);
        assert!(config.write);
        assert!(!config.is_readable());
        assert!(config.is_writable());
    }

    #[test]
    fn relay_config_builder_disabled() {
        let config = RelayConfig::builder("wss://relay.example")
            .enabled(false)
            .build();
        assert!(!config.enabled);
        assert!(!config.is_readable()); // disabled = not readable even if read=true
        assert!(!config.is_writable()); // disabled = not writable even if write=true
    }

    #[test]
    fn relay_config_builder_custom_timeout() {
        let config = RelayConfig::builder("wss://relay.example")
            .connection_timeout(Duration::from_secs(60))
            .build();
        assert_eq!(config.connection_timeout, Duration::from_secs(60));
    }

    #[test]
    fn relay_config_builder_full_custom() {
        let config = RelayConfig::builder("wss://relay.example")
            .read(false)
            .write(true)
            .enabled(false)
            .connection_timeout(Duration::from_secs(10))
            .build();
        assert_eq!(config.url, "wss://relay.example");
        assert!(!config.read);
        assert!(config.write);
        assert!(!config.enabled);
        assert_eq!(config.connection_timeout, Duration::from_secs(10));
    }

    // ── RelayPoolConfig tests ──

    #[test]
    fn pool_config_new_is_empty() {
        let config = RelayPoolConfig::new();
        assert!(config.is_empty());
        assert_eq!(config.len(), 0);
    }

    #[test]
    fn pool_config_default_is_empty() {
        let config = RelayPoolConfig::default();
        assert!(config.is_empty());
    }

    #[test]
    fn pool_config_add_relay() {
        let mut config = RelayPoolConfig::new();
        config.add_relay(RelayConfig::new("wss://relay1.example"));
        assert_eq!(config.len(), 1);
        assert!(!config.is_empty());
    }

    #[test]
    fn pool_config_relay_urls() {
        let mut config = RelayPoolConfig::new();
        config.add_relay(RelayConfig::new("wss://relay1.example"));
        config.add_relay(RelayConfig::new("wss://relay2.example"));

        let urls = config.relay_urls();
        assert_eq!(urls.len(), 2);
        assert!(urls.contains(&"wss://relay1.example"));
        assert!(urls.contains(&"wss://relay2.example"));
    }

    #[test]
    fn pool_config_duplicate_urls_are_deduplicated() {
        let mut config = RelayPoolConfig::new();
        config.add_relay(RelayConfig::new("wss://relay1.example"));
        config.add_relay(RelayConfig::new("wss://relay1.example")); // duplicate
        config.add_relay(RelayConfig::new("wss://relay2.example"));

        assert_eq!(config.len(), 2, "Duplicate relay URLs should be deduplicated");
    }

    #[test]
    fn pool_config_default_relays() {
        let config = RelayPoolConfig::default_relays();
        assert!(!config.is_empty());
        assert!(config.len() >= 3, "Should have at least 3 default relays");

        // All default relays should be readable and writable
        for relay in &config.relays {
            assert!(relay.is_readable(), "Default relay {} should be readable", relay.url);
            assert!(relay.is_writable(), "Default relay {} should be writable", relay.url);
        }
    }

    #[test]
    fn pool_config_enabled_relay_urls() {
        let mut config = RelayPoolConfig::new();
        config.add_relay(RelayConfig::new("wss://relay1.example"));
        config.add_relay(
            RelayConfig::builder("wss://relay2.example")
                .enabled(false)
                .build(),
        );

        let urls = config.enabled_relay_urls();
        assert_eq!(urls.len(), 1);
        assert!(urls.contains(&"wss://relay1.example"));
    }

    #[test]
    fn pool_config_readable_writable_urls() {
        let mut config = RelayPoolConfig::new();
        config.add_relay(RelayConfig::new("wss://relay1.example")); // read+write
        config.add_relay(
            RelayConfig::builder("wss://relay2.example").read(true).write(false).build(),
        );
        config.add_relay(
            RelayConfig::builder("wss://relay3.example").read(false).write(true).build(),
        );

        let readable = config.readable_relay_urls();
        let writable = config.writable_relay_urls();

        assert_eq!(readable.len(), 2); // relay1 + relay2
        assert_eq!(writable.len(), 2); // relay1 + relay3
    }

    #[test]
    fn pool_config_update_relay_flags() {
        let mut config = RelayPoolConfig::new();
        config.add_relay(RelayConfig::new("wss://relay1.example"));

        // Update flags
        let updated = config.update_relay_flags("wss://relay1.example", Some(false), None, None);
        assert!(updated);
        let relay = config.find("wss://relay1.example").unwrap();
        assert!(!relay.read);
        assert!(relay.write); // unchanged

        // Non-existent relay
        let updated = config.update_relay_flags("wss://nonexistent", Some(true), None, None);
        assert!(!updated);
    }

    #[test]
    fn pool_config_remove_relay() {
        let mut config = RelayPoolConfig::new();
        config.add_relay(RelayConfig::new("wss://relay1.example"));
        config.add_relay(RelayConfig::new("wss://relay2.example"));

        let removed = config.remove_relay("wss://relay1.example");
        assert!(removed);
        assert_eq!(config.len(), 1);
        assert!(!config.relay_urls().contains(&"wss://relay1.example"));
    }

    #[test]
    fn pool_config_find_relay() {
        let mut config = RelayPoolConfig::new();
        config.add_relay(RelayConfig::new("wss://relay1.example"));

        assert!(config.find("wss://relay1.example").is_some());
        assert!(config.find("wss://nonexistent").is_none());

        // Mutable find
        config.find_mut("wss://relay1.example").unwrap().read = false;
        assert!(!config.find("wss://relay1.example").unwrap().read);
    }

    // ── RelayPoolConfigBuilder tests ──

    #[test]
    fn pool_config_builder_add_relay() {
        let config = RelayPoolConfig::builder()
            .add_relay("wss://relay1.example")
            .add_relay("wss://relay2.example")
            .build();

        assert_eq!(config.len(), 2);
    }

    #[test]
    fn pool_config_builder_deduplication() {
        let config = RelayPoolConfig::builder()
            .add_relay("wss://relay1.example")
            .add_relay("wss://relay1.example") // duplicate
            .build();

        assert_eq!(config.len(), 1, "Builder should deduplicate relay URLs");
    }

    #[test]
    fn pool_config_builder_add_relay_config() {
        let custom_relay = RelayConfig::builder("wss://custom.example")
            .read(false)
            .write(true)
            .connection_timeout(Duration::from_secs(5))
            .build();

        let config = RelayPoolConfig::builder()
            .add_relay("wss://relay1.example")
            .add_relay_config(custom_relay)
            .build();

        assert_eq!(config.len(), 2);

        let custom = config.relays.iter().find(|r| r.url == "wss://custom.example").unwrap();
        assert!(!custom.read);
        assert!(custom.write);
        assert_eq!(custom.connection_timeout, Duration::from_secs(5));
    }

    #[test]
    fn pool_config_builder_read_only_relay() {
        let config = RelayPoolConfig::builder()
            .add_read_only_relay("wss://relay.example")
            .build();

        let relay = &config.relays[0];
        assert!(relay.read);
        assert!(!relay.write);
    }

    #[test]
    fn pool_config_builder_write_only_relay() {
        let config = RelayPoolConfig::builder()
            .add_write_only_relay("wss://relay.example")
            .build();

        let relay = &config.relays[0];
        assert!(!relay.read);
        assert!(relay.write);
    }

    #[test]
    fn pool_config_builder_empty() {
        let config = RelayPoolConfig::builder().build();
        assert!(config.is_empty());
    }

    // ── Default values verification ──

    #[test]
    fn default_connection_timeout_is_30_seconds() {
        let config = RelayConfig::new("wss://relay.example");
        assert_eq!(config.connection_timeout, Duration::from_secs(30));
    }

    #[test]
    fn default_read_write_both_true() {
        let config = RelayConfig::new("wss://relay.example");
        assert!(config.read, "Default read should be true");
        assert!(config.write, "Default write should be true");
    }

    #[test]
    fn default_enabled_is_true() {
        let config = RelayConfig::new("wss://relay.example");
        assert!(config.enabled, "Default enabled should be true");
    }

    #[test]
    fn default_relays_have_redundancy() {
        // At least 2 relays so a single relay's 503 can't break publish/fetch.
        assert!(DEFAULT_RELAYS.len() >= 2);
        // Damus wants AUTH for gift wraps (no invitation ever arrives through it)
        // and bans our request rate: not a default.
        assert!(!DEFAULT_RELAYS.contains(&"wss://relay.damus.io"));
    }
}
