//! # alpha-transport
//!
//! Nostr as a dumb transport pipe for MLS messages. The relay sees **only**
//! ciphertext — never plaintext.
//!
//! ## Custom Nostr Kind
//!
//! Kind **443** is used as the generic "MLS envelope" event kind. This
//! aligns with [`Kind::MlsKeyPackage`] in `nostr` 0.37 (NIP-104). The
//! [`MlsEnvelope`] struct carries a [`MlsMessageType`] discriminator so the
//! receiver can tell Welcome / Application / Proposal / Commit messages apart
//! without the relay learning anything about the content type.
//!
//! ## Gift-Wrap (NIP-59)
//!
//! Welcome messages and other 1-to-1 deliveries are gift-wrapped so the relay
//! cannot even determine who sent the message to whom. Group application
//! messages are published as public kind-443 events (the payload is still
//! MLS ciphertext — only group members can decrypt).

pub mod envelope;
pub mod error;
pub mod gift_wrap;
pub mod net_switch;
pub mod nostr_mls;
pub mod nostr_relay_client;
pub mod relay_config;
mod relay_parking;
pub mod sealed;
pub mod subscription;

// ---------------------------------------------------------------------------
// Re-exports
// ---------------------------------------------------------------------------

pub use envelope::{ChatKind, MlsEnvelope, MlsMessageType};
pub use error::TransportError;
pub use gift_wrap::{GiftWrapBuilder, GiftWrapReader};
pub use nostr_mls::NostrMlsBridge;
pub use nostr_relay_client::{NostrRelayClient, PersonaPlan, RawMlsEvent, RelayStatusSnapshot};
pub use nostr_sdk::{RelayMessage, RelayPoolNotification, RelayStatus};
pub use nostr_sdk::pool::monitor::MonitorNotification;
pub use relay_config::{RelayConfig, RelayConfigBuilder, RelayPoolConfig, RelayPoolConfigBuilder, DEFAULT_RELAYS};
pub use subscription::{MlsSubscription, SubscriptionManager};

/// Custom Nostr event kind for MLS protocol envelopes.
///
/// The relay sees only opaque ciphertext — no metadata about content type.
/// Kind 443 corresponds to [`nostr::Kind::MlsKeyPackage`] (NIP-104).
pub const NOSTR_KIND_MLS_ENVELOPE: u16 = 443;
