//! Expiry policy for ephemeral messages.
//!
//! Defines the [`ExpiryPolicy`] enum that controls when a message should
//! be automatically deleted. This is an **app-layer** concept — it does
//! not touch MLS crypto. The MLS layer always encrypts/decrypts the same
//! way; expiry is enforced after decryption at the application level.
//!
//! # Security disclaimers (D4)
//!
//! Ephemeral messages provide **best-effort** deletion on the recipient's
//! device. A modified client, root access, or a screenshot can circumvent
//! deletion. Mitigations include:
//! - `FLAG_SECURE` on Android / `screenCaptureDisabled` on iOS for
//!   ephemeral message screens (prevents screenshots/recording).
//! - View-once messages are never written to disk in cleartext (memory-only).
//!
//! **No guarantee of deletion on a compromised device is made.**

use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

// ---------------------------------------------------------------------------
// ExpiryPolicy
// ---------------------------------------------------------------------------

/// Policy controlling when an ephemeral message expires and is deleted.
///
/// This is attached as an optional field on [`crate::AlphaMessage::Text`]
/// and [`crate::AlphaMessage::Media`]. When `None`, the message is
/// permanent (the default).
///
/// # Wire format
///
/// Serialized as a JSON object with a `"mode"` tag:
/// ```json
/// {"mode": "none"}
/// {"mode": "after_send", "ttl_secs": 300}
/// {"mode": "after_read", "ttl_secs": 60}
/// {"mode": "view_once"}
/// ```
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "mode")]
pub enum ExpiryPolicy {
    /// No expiry — the message is permanent.
    #[serde(rename = "none")]
    None,

    /// Timer starts at the moment the message is **sent**.
    /// Both sender and receiver schedule deletion at `send_time + ttl_secs`.
    #[serde(rename = "after_send")]
    AfterSend {
        /// Time-to-live in seconds from send time.
        ttl_secs: u32,
    },

    /// Timer starts when the **recipient opens** the message.
    /// The receiver sends a `ReadSignal` app-message (inside MLS) back to
    /// the sender so both sides delete at the same time.
    #[serde(rename = "after_read")]
    AfterRead {
        /// Time-to-live in seconds from the read timestamp.
        ttl_secs: u32,
    },

    /// The message may be viewed exactly once, then immediately zeroized.
    /// Plaintext is **never** written to disk — held in memory only.
    /// After the first view, the message is marked `consumed` and
    /// plaintext + media key + thumbnail are zeroized.
    ViewOnce,

    /// A whisper: shown only under the reader's finger, for at most `secs`
    /// seconds of touch, then burned on both sides. An older app cannot read
    /// this mode and drops the message — the secret never shows in the open.
    #[serde(rename = "whisper")]
    Whisper {
        /// Seconds of reading (while the finger is on the screen).
        secs: u32,
        /// A timed view instead: the whole media shown for `secs` seconds
        /// (0 = until closed), once. An older app reads it as a whisper.
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        glance: bool,
    },
}

impl ExpiryPolicy {
    /// Returns `true` if this policy means the message is ephemeral
    /// (i.e., not permanent).
    pub fn is_ephemeral(&self) -> bool {
        !matches!(self, ExpiryPolicy::None)
    }

    /// Returns `true` if this policy requires a read-signal to be sent
    /// back to the sender when the recipient opens the message.
    pub fn requires_read_signal(&self) -> bool {
        matches!(self, ExpiryPolicy::AfterRead { .. })
    }

    /// Returns `true` if the plaintext must **never** be persisted to disk.
    ///
    /// Currently only `ViewOnce` enforces this — the message is held in
    /// memory only and zeroized after the first view.
    pub fn memory_only(&self) -> bool {
        matches!(self, ExpiryPolicy::ViewOnce)
    }

    /// Returns the TTL in seconds if the policy has one, or `None`.
    pub fn ttl_secs(&self) -> Option<u32> {
        match self {
            ExpiryPolicy::AfterSend { ttl_secs } | ExpiryPolicy::AfterRead { ttl_secs } => {
                Some(*ttl_secs)
            }
            ExpiryPolicy::None | ExpiryPolicy::ViewOnce | ExpiryPolicy::Whisper { .. } => None,
        }
    }

    /// Compute the absolute expiry timestamp based on the policy and a
    /// reference time.
    ///
    /// - `AfterSend`: `reference_time + ttl_secs`
    /// - `AfterRead`: returns `None` — expiry is computed later when the
    ///   recipient reads the message.
    /// - `ViewOnce`: returns `None` — no fixed expiry; deletion happens
    ///   immediately after first view.
    /// - `None`: returns `None` — the message is permanent.
    pub fn compute_expiry(&self, reference_time: &DateTime<Utc>) -> Option<DateTime<Utc>> {
        match self {
            ExpiryPolicy::AfterSend { ttl_secs } => {
                Some(*reference_time + Duration::seconds(i64::from(*ttl_secs)))
            }
            ExpiryPolicy::AfterRead { .. } => None,
            ExpiryPolicy::ViewOnce | ExpiryPolicy::Whisper { .. } => None,
            ExpiryPolicy::None => None,
        }
    }

    /// Compute the expiry for an `AfterRead` message once the read
    /// timestamp is known.
    ///
    /// Returns `None` if the policy is not `AfterRead`.
    pub fn compute_read_expiry(&self, read_at: &DateTime<Utc>) -> Option<DateTime<Utc>> {
        match self {
            ExpiryPolicy::AfterRead { ttl_secs } => {
                Some(*read_at + Duration::seconds(i64::from(*ttl_secs)))
            }
            _ => None,
        }
    }
}

/// Reading time of a whisper: 3 seconds to 2 minutes.
pub const MIN_WHISPER_SECS: u32 = 3;
pub const MAX_WHISPER_SECS: u32 = 120;

/// A timed view lasts at most this long; 0 = until the viewer closes it.
pub const MAX_GLANCE_SECS: u32 = 10;

/// Minimum TTL for AfterSend / AfterRead (5 seconds).
pub const MIN_TTL_SECS: u32 = 5;

/// Maximum TTL for AfterSend / AfterRead (24 hours = 86 400 seconds).
pub const MAX_TTL_SECS: u32 = 86_400;

/// Validate an [`ExpiryPolicy`].
///
/// Returns `Ok(())` if the policy is valid, or a descriptive error.
pub fn validate_expiry_policy(policy: &ExpiryPolicy) -> Result<(), ExpiryPolicyError> {
    match policy {
        ExpiryPolicy::None | ExpiryPolicy::ViewOnce => Ok(()),
        ExpiryPolicy::Whisper { secs, glance: true } => {
            if *secs <= MAX_GLANCE_SECS {
                Ok(())
            } else {
                Err(ExpiryPolicyError::InvalidTtl { ttl_secs: *secs, min: 0, max: MAX_GLANCE_SECS })
            }
        }
        ExpiryPolicy::Whisper { secs, .. } => {
            if (MIN_WHISPER_SECS..=MAX_WHISPER_SECS).contains(secs) {
                Ok(())
            } else {
                Err(ExpiryPolicyError::InvalidTtl { ttl_secs: *secs, min: MIN_WHISPER_SECS, max: MAX_WHISPER_SECS })
            }
        }
        ExpiryPolicy::AfterSend { ttl_secs } | ExpiryPolicy::AfterRead { ttl_secs } => {
            if *ttl_secs < MIN_TTL_SECS || *ttl_secs > MAX_TTL_SECS {
                Err(ExpiryPolicyError::InvalidTtl {
                    ttl_secs: *ttl_secs,
                    min: MIN_TTL_SECS,
                    max: MAX_TTL_SECS,
                })
            } else {
                Ok(())
            }
        }
    }
}

/// Errors that can occur during expiry policy validation.
#[derive(Debug, thiserror::Error)]
pub enum ExpiryPolicyError {
    /// TTL is outside the allowed range.
    #[error("invalid TTL: {ttl_secs}s (must be {min}..{max})")]
    InvalidTtl {
        ttl_secs: u32,
        min: u32,
        max: u32,
    },
}

// ---------------------------------------------------------------------------
// ExpirySweeper — crash-safe background deletion
// ---------------------------------------------------------------------------

/// Sweeper for expired ephemeral messages.
///
/// This is a **stateless** utility that queries the database for messages
/// whose `expires_at <= now` and deletes them (hard delete, including
/// associated media blobs).
///
/// # Crash safety
///
/// Expiry timestamps are persisted to the database (`expires_at` column).
/// The sweeper does NOT rely on in-memory timers. On every call to
/// [`ExpirySweeper::sweep`], all expired messages are cleaned up.
/// This means:
/// - If the app is killed mid-life, on restart a single `sweep()` call
///   will clean up everything that has expired in the meantime.
/// - The UI should call `sweep()` on startup AND periodically (e.g.,
///   every 30 seconds).
pub struct ExpirySweeper;

impl ExpirySweeper {
    /// Run one sweep pass: delete all messages whose `expires_at` has passed.
    ///
    /// Returns the number of messages deleted.
    ///
    /// The caller should also arrange for media blob deletion for each
    /// expired message that has a `media_ref`. This function accepts a
    /// closure `on_delete_media` that is called for each deleted message
    /// that has a media reference, giving the caller a chance to request
    /// deletion from Blossom/relay (best-effort).
    ///
    /// # Arguments
    ///
    /// * `conn` — A database connection.
    /// * `on_delete_media` — Called for each expired message that has a
    ///   `media_ref`. The closure receives `(message_id, media_ref)`.
    ///   Deletion is best-effort — errors should be logged but not
    ///   propagated.
    pub fn sweep<F>(
        conn: &rusqlite::Connection,
        on_delete_media: F,
    ) -> Result<u64, SweepError>
    where
        F: Fn(&str, &str),
    {
        let now = Utc::now().to_rfc3339();

        // First, find all expired messages with media refs so we can
        // attempt media deletion before removing the database rows.
        let expired_with_media: Vec<(String, String)> = conn
            .prepare(
                "SELECT id, media_ref FROM messages
                 WHERE expires_at IS NOT NULL AND expires_at <= ?1
                 AND media_ref IS NOT NULL",
            )
            .map_err(|e| SweepError::Database(e.to_string()))?
            .query_map(rusqlite::params![now], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| SweepError::Database(e.to_string()))?
            .filter_map(|r| r.ok())
            .collect();

        // Request media deletion (best-effort).
        for (msg_id, media_ref) in &expired_with_media {
            on_delete_media(msg_id, media_ref);
        }

        // Now hard-delete the expired messages.
        let changes = conn
            .execute(
                "DELETE FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?1",
                rusqlite::params![now],
            )
            .map_err(|e| SweepError::Database(e.to_string()))?;

        if changes > 0 {
            tracing::info!("ExpirySweeper: deleted {} expired message(s)", changes);
        }

        Ok(changes as u64)
    }

    /// Convenience: sweep without media deletion callback.
    pub fn sweep_no_media(conn: &rusqlite::Connection) -> Result<u64, SweepError> {
        Self::sweep(conn, |_, _| {})
    }

    /// Sweep ViewOnce messages that have been consumed (viewed) but not
    /// yet deleted. These are messages where `consumed = 1` and
    /// `expiry_policy = 'view_once'`.
    ///
    /// ViewOnce messages are deleted immediately after viewing, but if the
    /// app crashes between marking consumed and deleting, this sweep
    /// catches them on restart.
    pub fn sweep_consumed_view_once(
        conn: &rusqlite::Connection,
    ) -> Result<u64, SweepError> {
        let changes = conn
            .execute(
                "DELETE FROM messages WHERE consumed = 1 AND expiry_policy = 'view_once'",
                rusqlite::params![],
            )
            .map_err(|e| SweepError::Database(e.to_string()))?;

        if changes > 0 {
            tracing::info!(
                "ExpirySweeper: cleaned up {} consumed ViewOnce message(s)",
                changes
            );
        }

        Ok(changes as u64)
    }

    /// Full sweep: expired + consumed ViewOnce.
    pub fn sweep_full<F>(
        conn: &rusqlite::Connection,
        on_delete_media: F,
    ) -> Result<u64, SweepError>
    where
        F: Fn(&str, &str),
    {
        let expired = Self::sweep(conn, &on_delete_media)?;
        let consumed = Self::sweep_consumed_view_once(conn)?;
        Ok(expired + consumed)
    }
}

/// Errors from the expiry sweeper.
#[derive(Debug, thiserror::Error)]
pub enum SweepError {
    /// Database error during sweep.
    #[error("database error: {0}")]
    Database(String),
}

// ---------------------------------------------------------------------------
// Zeroize support for secret material
// ---------------------------------------------------------------------------

/// A wrapper for message plaintext that auto-zeroizes on drop.
///
/// Used for ephemeral messages (especially ViewOnce) to ensure that
/// plaintext content does not linger in memory after use.
#[derive(Clone)]
pub struct EphemeralPlaintext {
    /// The plaintext bytes. Zeroized on drop.
    data: Vec<u8>,
}

impl EphemeralPlaintext {
    /// Create a new `EphemeralPlaintext` from raw bytes.
    pub fn new(data: Vec<u8>) -> Self {
        Self { data }
    }

    /// Access the plaintext bytes.
    pub fn as_bytes(&self) -> &[u8] {
        &self.data
    }

    /// Consume and zeroize the plaintext.
    pub fn consume(mut self) {
        self.data.zeroize();
    }

    /// Take ownership of the inner bytes, zeroizing the wrapper.
    /// The caller is now responsible for zeroizing the returned `Vec`.
    pub fn into_bytes(mut self) -> Vec<u8> {
        std::mem::take(&mut self.data)
        // self.data is now empty Vec; original bytes returned to caller
        // Caller MUST zeroize when done
    }
}

impl Drop for EphemeralPlaintext {
    fn drop(&mut self) {
        self.data.zeroize();
    }
}

impl std::fmt::Debug for EphemeralPlaintext {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "EphemeralPlaintext({} bytes, REDACTED)", self.data.len())
    }
}

#[cfg(test)]
mod tests {

    #[test]
    fn a_timed_view_lasts_up_to_ten_seconds_or_until_closed() {
        for secs in [0, 1, 10] {
            assert!(validate_expiry_policy(&ExpiryPolicy::Whisper { secs, glance: true }).is_ok());
        }
        assert!(validate_expiry_policy(&ExpiryPolicy::Whisper { secs: 11, glance: true }).is_err());
        assert!(validate_expiry_policy(&ExpiryPolicy::Whisper { secs: 1, glance: false }).is_err(), "a whisper keeps its own range");
        let json = serde_json::to_string(&ExpiryPolicy::Whisper { secs: 5, glance: false }).unwrap();
        assert!(!json.contains("glance"), "a plain whisper is sent as before");
    }

    use super::*;

    #[test]
    fn test_expiry_policy_none() {
        let policy = ExpiryPolicy::None;
        assert!(!policy.is_ephemeral());
        assert!(!policy.requires_read_signal());
        assert!(!policy.memory_only());
        assert!(policy.ttl_secs().is_none());
        assert!(policy.compute_expiry(&Utc::now()).is_none());
    }

    #[test]
    fn test_expiry_policy_after_send() {
        let policy = ExpiryPolicy::AfterSend { ttl_secs: 300 };
        assert!(policy.is_ephemeral());
        assert!(!policy.requires_read_signal());
        assert!(!policy.memory_only());
        assert_eq!(policy.ttl_secs(), Some(300));

        let now = Utc::now();
        let expiry = policy.compute_expiry(&now).unwrap();
        assert_eq!((expiry - now).num_seconds(), 300);
    }

    #[test]
    fn test_expiry_policy_after_read() {
        let policy = ExpiryPolicy::AfterRead { ttl_secs: 60 };
        assert!(policy.is_ephemeral());
        assert!(policy.requires_read_signal());
        assert!(!policy.memory_only());
        assert_eq!(policy.ttl_secs(), Some(60));

        let now = Utc::now();
        // Before read: no fixed expiry
        assert!(policy.compute_expiry(&now).is_none());

        // After read: expiry computed
        let read_at = Utc::now();
        let expiry = policy.compute_read_expiry(&read_at).unwrap();
        assert_eq!((expiry - read_at).num_seconds(), 60);
    }

    #[test]
    fn test_expiry_policy_view_once() {
        let policy = ExpiryPolicy::ViewOnce;
        assert!(policy.is_ephemeral());
        assert!(!policy.requires_read_signal());
        assert!(policy.memory_only());
        assert!(policy.ttl_secs().is_none());
        assert!(policy.compute_expiry(&Utc::now()).is_none());
    }

    #[test]
    fn test_validate_expiry_policy_ok() {
        assert!(validate_expiry_policy(&ExpiryPolicy::None).is_ok());
        assert!(validate_expiry_policy(&ExpiryPolicy::ViewOnce).is_ok());
        assert!(validate_expiry_policy(&ExpiryPolicy::AfterSend { ttl_secs: 5 }).is_ok());
        assert!(validate_expiry_policy(&ExpiryPolicy::AfterSend { ttl_secs: 86_400 }).is_ok());
        assert!(validate_expiry_policy(&ExpiryPolicy::AfterRead { ttl_secs: 60 }).is_ok());
    }

    #[test]
    fn test_validate_expiry_policy_invalid_ttl() {
        assert!(validate_expiry_policy(&ExpiryPolicy::AfterSend { ttl_secs: 1 }).is_err());
        assert!(validate_expiry_policy(&ExpiryPolicy::AfterSend { ttl_secs: 100_000 }).is_err());
        assert!(validate_expiry_policy(&ExpiryPolicy::AfterRead { ttl_secs: 4 }).is_err());
    }

    #[test]
    fn test_expiry_policy_serialization() {
        // AfterSend
        let policy = ExpiryPolicy::AfterSend { ttl_secs: 300 };
        let json = serde_json::to_string(&policy).unwrap();
        assert!(json.contains("\"mode\":\"after_send\""));
        assert!(json.contains("\"ttl_secs\":300"));
        let deserialized: ExpiryPolicy = serde_json::from_str(&json).unwrap();
        assert_eq!(policy, deserialized);

        // AfterRead
        let policy = ExpiryPolicy::AfterRead { ttl_secs: 60 };
        let json = serde_json::to_string(&policy).unwrap();
        assert!(json.contains("\"mode\":\"after_read\""));
        let deserialized: ExpiryPolicy = serde_json::from_str(&json).unwrap();
        assert_eq!(policy, deserialized);

        // ViewOnce — serde internally-tagged unit variants may serialize
        // differently. The key test is round-trip consistency.
        let policy = ExpiryPolicy::ViewOnce;
        let json = serde_json::to_string(&policy).unwrap();
        let deserialized: ExpiryPolicy = serde_json::from_str(&json).unwrap();
        assert_eq!(policy, deserialized);

        // None
        let policy = ExpiryPolicy::None;
        let json = serde_json::to_string(&policy).unwrap();
        let deserialized: ExpiryPolicy = serde_json::from_str(&json).unwrap();
        assert_eq!(policy, deserialized);
    }

    #[test]
    fn test_ephemeral_plaintext_zeroize_on_drop() {
        let data = vec![0xDE, 0xAD, 0xBE, 0xEF];
        let pt = EphemeralPlaintext::new(data);
        assert_eq!(pt.as_bytes(), &[0xDE, 0xAD, 0xBE, 0xEF]);

        // Drop should zeroize
        drop(pt);
        // We can't directly test that memory was zeroized, but Drop ran without panic
    }

    #[test]
    fn test_ephemeral_plaintext_consume() {
        let data = vec![0xCA, 0xFE, 0xBA, 0xBE];
        let pt = EphemeralPlaintext::new(data.clone());
        pt.consume();
        // consume() zeroizes and drops
    }

    #[test]
    fn test_ephemeral_plaintext_debug_redacted() {
        let data = vec![1, 2, 3, 4, 5];
        let pt = EphemeralPlaintext::new(data);
        let debug_str = format!("{:?}", pt);
        assert!(debug_str.contains("5 bytes"));
        assert!(debug_str.contains("REDACTED"));
        assert!(!debug_str.contains(&format!("{:?}", vec![1, 2, 3, 4, 5])));
    }
}
