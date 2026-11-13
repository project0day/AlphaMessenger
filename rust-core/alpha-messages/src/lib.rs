//! `alpha-messages` — App-layer message format for AlphaMessenger.
//!
//! This crate defines the structured format for messages that go INSIDE
//! the MLS payload. After MLS decryption, we get raw bytes — this crate
//! defines the structured format for those bytes.
//!
//! This is pure product code, **NO crypto**.
//!
//! # Modules
//!
//! - [`message_type`] — The `AlphaMessage` enum and variants
//! - [`builder`] — Fluent builder pattern for constructing messages
//! - [`serializer`] — JSON serialization/deserialization for MLS payload
//! - [`validator`] — Message validation rules
//! - [`expiry`] — Expiry policies for ephemeral messages (STAGE D)
//! - [`ttl`] — TTL / disappearing message management
//! - [`error`] — Error types
//!
//! # Ephemeral messages (STAGE D)
//!
//! Text and Media messages can carry an optional [`ExpiryPolicy`] that
//! controls when the message should be automatically deleted:
//!
//! - **AfterSend** — timer starts at send time.
//! - **AfterRead** — timer starts when the recipient reads; a `ReadSignal`
//!   is sent back so the sender also deletes.
//! - **ViewOnce** — render once, then immediately zeroize.
//!
//! **Security disclaimer (D4):** Ephemeral messages provide best-effort
//! deletion. A modified client or screenshot can circumvent deletion.
//! Mitigation: `FLAG_SECURE` on ephemeral screens. No guarantee on
//! compromised devices.

pub mod builder;
pub mod error;
pub mod expiry;
pub mod message_type;
pub mod roles;
pub mod serializer;
pub mod ttl;
pub mod validator;

// Re-export the main types for convenience.
pub use builder::{
    DeleteBuilder, DisappearingBuilder, EditBuilder, MediaBuilder, MessageBuilder, ReactionBuilder,
    ReadSignalBuilder, ReplyBuilder, TextBuilder,
};
pub use error::{MessageError, Result};
pub use expiry::{
    ExpiryPolicy, EphemeralPlaintext, ExpirySweeper, SweepError,
    validate_expiry_policy, MIN_TTL_SECS, MAX_TTL_SECS, MIN_WHISPER_SECS, MAX_WHISPER_SECS, MAX_GLANCE_SECS,
};
pub use message_type::{legacy_comment, strip_legacy_comment, AlphaMessage, HistoryItem, COMMENT_MARK};
pub use roles::{
    can_change_role, can_manage_group, can_moderate, can_remove, normalize_role, role_rank,
    ROLE_ADMIN, ROLE_MEMBER, ROLE_MODERATOR, ROLE_OWNER,
};
pub use serializer::{deserialize, deserialize_batch, serialize, serialize_batch};
pub use ttl::{compute_expiry, is_expired, seconds_remaining, TtlManager};
pub use validator::{validate, validate_inbound};
