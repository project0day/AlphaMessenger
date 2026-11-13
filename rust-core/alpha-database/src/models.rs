//! Rust model types matching the database schema.
//!
//! Each struct corresponds to a table row and derives Serialize/Deserialize,
//! Clone, and Debug for ergonomic use across the application.

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

/// The type of a chat – either a 1:1 direct message or a group conversation.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ChatType {
    Direct,
    Group,
}

impl ChatType {
    /// Convert to the string stored in the database.
    pub fn as_str(&self) -> &'static str {
        match self {
            ChatType::Direct => "direct",
            ChatType::Group => "group",
        }
    }

    /// Parse from the database string representation.
    pub fn from_str_db(s: &str) -> Result<Self, String> {
        match s {
            "direct" => Ok(ChatType::Direct),
            "group" => Ok(ChatType::Group),
            other => Err(format!("invalid chat_type: '{other}'")),
        }
    }
}

/// Error type that can be used as a rusqlite `FromSql` conversion error source.
#[derive(Debug)]
pub struct EnumParseError(String);

impl std::fmt::Display for EnumParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for EnumParseError {}

impl ChatType {
    /// Parse from the database string, returning a [`rusqlite::Error`] on failure.
    pub fn from_sql(s: &str, column_idx: usize) -> std::result::Result<Self, rusqlite::Error> {
        Self::from_str_db(s).map_err(|e| {
            rusqlite::Error::FromSqlConversionFailure(
                column_idx,
                rusqlite::types::Type::Text,
                Box::new(EnumParseError(e)),
            )
        })
    }
}

/// The type of a message.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum MessageType {
    Text,
    Edit,
    Reaction,
    Reply,
    Delete,
    Media,
    Disappearing,
    ReadSignal,
}

impl MessageType {
    /// Convert to the string stored in the database.
    pub fn as_str(&self) -> &'static str {
        match self {
            MessageType::Text => "text",
            MessageType::Edit => "edit",
            MessageType::Reaction => "reaction",
            MessageType::Reply => "reply",
            MessageType::Delete => "delete",
            MessageType::Media => "media",
            MessageType::Disappearing => "disappearing",
            MessageType::ReadSignal => "read_signal",
        }
    }

    /// Parse from the database string representation.
    pub fn from_str_db(s: &str) -> Result<Self, String> {
        match s {
            "text" => Ok(MessageType::Text),
            "edit" => Ok(MessageType::Edit),
            "reaction" => Ok(MessageType::Reaction),
            "reply" => Ok(MessageType::Reply),
            "delete" => Ok(MessageType::Delete),
            "media" => Ok(MessageType::Media),
            "disappearing" => Ok(MessageType::Disappearing),
            "read_signal" => Ok(MessageType::ReadSignal),
            other => Err(format!("invalid msg_type: '{other}'")),
        }
    }

    /// Parse from the database string, returning a [`rusqlite::Error`] on failure.
    pub fn from_sql(s: &str, column_idx: usize) -> std::result::Result<Self, rusqlite::Error> {
        Self::from_str_db(s).map_err(|e| {
            rusqlite::Error::FromSqlConversionFailure(
                column_idx,
                rusqlite::types::Type::Text,
                Box::new(EnumParseError(e)),
            )
        })
    }
}

/// The expiry policy for an ephemeral message.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ExpiryPolicyType {
    None,
    AfterSend,
    AfterRead,
    ViewOnce,
}

impl ExpiryPolicyType {
    /// Convert to the string stored in the database.
    pub fn as_str(&self) -> &'static str {
        match self {
            ExpiryPolicyType::None => "none",
            ExpiryPolicyType::AfterSend => "after_send",
            ExpiryPolicyType::AfterRead => "after_read",
            ExpiryPolicyType::ViewOnce => "view_once",
        }
    }

    /// Parse from the database string representation.
    pub fn from_str_db(s: &str) -> Result<Self, String> {
        match s {
            "none" => Ok(ExpiryPolicyType::None),
            "after_send" => Ok(ExpiryPolicyType::AfterSend),
            "after_read" => Ok(ExpiryPolicyType::AfterRead),
            "view_once" => Ok(ExpiryPolicyType::ViewOnce),
            other => Err(format!("invalid expiry_policy: '{other}'")),
        }
    }

    /// Parse from the database string, returning a [`rusqlite::Error`] on failure.
    pub fn from_sql(s: &str, column_idx: usize) -> std::result::Result<Self, rusqlite::Error> {
        Self::from_str_db(s).map_err(|e| {
            rusqlite::Error::FromSqlConversionFailure(
                column_idx,
                rusqlite::types::Type::Text,
                Box::new(EnumParseError(e)),
            )
        })
    }
}

// ---------------------------------------------------------------------------
// Table models
// ---------------------------------------------------------------------------

/// A contact in the address book.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Contact {
    /// Nostr npub (primary key).
    pub id: String,
    /// Human-readable display name.
    pub display_name: Option<String>,
    /// Raw 32-byte secp256k1 public key.
    pub nostr_pubkey: Vec<u8>,
    /// Serialized MLS credential (serde_json).
    pub mls_credential: Option<Vec<u8>>,
    /// RFC3339 creation timestamp.
    pub created_at: String,
    /// RFC3339 last-updated timestamp.
    pub updated_at: String,
    // --- Profile propagation ---
    /// Peer's bio / status. Empty string if unknown / cleared.
    #[serde(default)]
    pub bio: String,
    /// Absolute path to the cached avatar file. Empty if no avatar.
    #[serde(default)]
    pub avatar_path: String,
    /// SHA256 hex of the avatar bytes — used as a cache key so we can
    /// dedup write_to_disk when the same hash arrives twice.
    #[serde(default)]
    pub avatar_hash: String,
    /// Monotonic version counter from the peer. Receivers MUST drop
    /// incoming ProfileUpdates with `version <= profile_version`.
    #[serde(default)]
    pub profile_version: i64,
    /// RFC3339 timestamp of the last ProfileUpdate accepted.
    #[serde(default)]
    pub profile_updated_at: Option<String>,
}

/// A chat conversation (1:1 or group).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Chat {
    /// MLS GroupId hex-encoded (primary key).
    pub id: String,
    /// Whether this is a direct or group chat.
    pub chat_type: ChatType,
    /// Group name (NULL for 1:1).
    pub name: Option<String>,
    /// Current MLS epoch.
    pub epoch: i64,
    /// RFC3339 creation timestamp.
    pub created_at: String,
    /// RFC3339 last-updated timestamp.
    pub updated_at: String,
    /// Local-only UX flags.
    pub pinned_at: Option<i64>,
    pub muted_until: Option<i64>,
    pub archived_at: Option<i64>,
    pub unread_override: bool,
    // --- Group metadata ---
    /// Absolute path to the cached group/peer avatar JPEG. Empty if
    /// no avatar broadcast yet. For groups: file is stored under
    /// `<files>/group_avatars/{chat_id}.jpg`. For directs: shadows
    /// the contact's avatar_path (kept in sync at receive time).
    #[serde(default)]
    pub avatar_path: String,
    /// SHA256 hex of the avatar bytes — dedup key for receivers.
    #[serde(default)]
    pub avatar_hash: String,
    /// Free-form group description. Empty for 1:1 chats.
    #[serde(default)]
    pub description: String,
    /// Monotonic counter incremented by the sender on every
    /// `GroupMetadataUpdate`. Receivers must drop incoming updates
    /// with `version <= metadata_version` already stored.
    #[serde(default)]
    pub metadata_version: i64,
}

/// Membership link between a chat and a contact.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMember {
    /// Reference to [`Chat::id`].
    pub chat_id: String,
    /// Reference to [`Contact::id`].
    pub contact_id: String,
    /// MLS leaf index (may be NULL if not yet assigned).
    pub leaf_index: Option<i64>,
    /// RFC3339 timestamp when the member joined.
    pub joined_at: String,
}

/// A single message within a chat.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    /// UUID (primary key).
    pub id: String,
    /// Reference to [`Chat::id`].
    pub chat_id: String,
    /// Reference to [`Contact::id`].
    pub sender_id: String,
    /// What kind of message this is.
    pub msg_type: MessageType,
    /// Message body (NULL for tombstones/deletes).
        /// **Security note:** for ViewOnce messages, this is NULL in the
    /// database — plaintext is held in memory only and zeroized after view.
    pub content: Option<String>,
    /// Message ID being replied to.
    pub reply_to: Option<String>,
    /// Reference to a media attachment.
    pub media_ref: Option<String>,
    /// Time-to-live in seconds for disappearing messages.
    pub ttl_seconds: Option<i64>,
    /// Computed expiry timestamp (RFC3339).
    pub expires_at: Option<String>,
    /// RFC3339 creation timestamp.
    pub created_at: String,
    /// RFC3339 edit timestamp (if edited).
    pub edited_at: Option<String>,
    /// Soft-delete tombstone flag.
    pub is_deleted: bool,
    /// Expiry policy mode ('none', 'after_send', 'after_read', 'view_once').
    /// NULL means no expiry (same as 'none').
    pub expiry_policy: Option<ExpiryPolicyType>,
    /// When the recipient first opened the message (for AfterRead).
    pub read_at: Option<String>,
    /// Whether a ViewOnce message has been viewed and consumed.
    pub consumed: bool,
    /// When the message became fully delivered (all recipients ACKed) — RFC3339.
        /// NULL until delivered. Persisted so DELIVERED survives reload.
    pub delivered_at: Option<String>,
}

/// Metadata row for an MLS group.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MlsGroupRow {
    /// Hex-encoded MLS GroupId (primary key).
    pub group_id: String,
    /// Current epoch.
    pub epoch: i64,
    /// Ciphersuite identifier.
    pub ciphersuite: i64,
    /// Whether the group is still active.
    pub is_active: bool,
    /// RFC3339 creation timestamp.
    pub created_at: String,
    /// RFC3339 last-updated timestamp.
    pub updated_at: String,
}

/// Metadata row for an MLS key package.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MlsKeyPackageRow {
    /// UUID (primary key).
    pub id: String,
    /// KeyPackage hash reference (unique).
    pub hash_ref: Vec<u8>,
    /// TLS-serialized KeyPackage data.
    pub key_package_data: Vec<u8>,
    /// RFC3339 creation timestamp.
    pub created_at: String,
    /// Whether this key package has been consumed.
    pub is_consumed: bool,
}

/// Sync checkpoint tracking progress for a relay.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncCheckpoint {
    /// Relay URL (primary key).
    pub relay_url: String,
    /// Last Nostr event ID processed.
    pub last_event_id: Option<String>,
    /// Last Nostr event timestamp.
    pub last_timestamp: i64,
    /// RFC3339 last-updated timestamp.
    pub updated_at: String,
}

/// Generic key-value store entry.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyValueEntry {
    /// Key (primary key).
    pub key: String,
    /// Stored value.
    pub value: String,
    /// RFC3339 last-updated timestamp.
    pub updated_at: String,
}
