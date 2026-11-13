//! Comprehensive error types for the alpha-mls crate.
//!
//! Wraps OpenMLS errors into a unified error hierarchy suitable for
//! consumption by higher-level AlphaMessenger crates.

use thiserror::Error;

/// Top-level error type for all MLS operations.
#[derive(Error, Debug)]
pub enum MlsError {
    /// Failed to create a group.
    #[error("Group creation failed: {0}")]
    GroupCreation(String),

    /// Failed to join a group from a Welcome message.
    #[error("Welcome join failed: {0}")]
    WelcomeJoin(String),

    /// Failed to add a member to a group.
    #[error("Add member failed: {0}")]
    AddMember(String),

    /// Failed to remove a member from a group.
    #[error("Remove member failed: {0}")]
    RemoveMember(String),

    /// Failed to encrypt / send a message.
    #[error("Send message failed: {0}")]
    SendMessage(String),

    /// Failed to decrypt / receive a message.
    #[error("A leaf's credential does not prove its owner: {0}")]
    ForgedLeaf(String),

    #[error("Receive message failed: {0}")]
    ReceiveMessage(String),

    /// The group is inactive (we were evicted).
    #[error("Group is inactive: use after eviction")]
    GroupInactive,

    /// The group has a pending commit that must be resolved first.
    #[error("Group has pending commit")]
    PendingCommit,

    /// No matching key package found for this Welcome.
    #[error("No matching key package for Welcome")]
    NoMatchingKeyPackage,

    /// Missing ratchet tree when joining.
    #[error("Missing ratchet tree for Welcome")]
    MissingRatchetTree,

    /// Serialization / deserialization failure.
    #[error("Serialization error: {0}")]
    Serialization(String),

    /// TLS codec error.
    #[error("TLS codec error: {0}")]
    TlsCodec(String),

    /// Storage provider error.
    #[error("Storage error: {0}")]
    Storage(String),

    /// Crypto error from OpenMLS.
    #[error("Crypto error: {0}")]
    Crypto(String),

    /// Invalid epoch transition detected.
    #[error("Invalid epoch: expected {expected}, got {actual}")]
    InvalidEpoch { expected: u64, actual: u64 },

    /// Epoch gap detected — missing intermediate epochs.
    #[error("Epoch gap: missing epochs {from_epoch}..{to_epoch}")]
    EpochGap { from_epoch: u64, to_epoch: u64 },

    /// A group with the given ID was not found.
    #[error("Group not found")]
    GroupNotFound,

    /// The group already exists in storage.
    #[error("Group already exists")]
    GroupAlreadyExists,

    /// Export secret failed.
    #[error("Export secret failed: {0}")]
    ExportSecret(String),

    /// Leave group failed.
    #[error("Leave group failed: {0}")]
    LeaveGroup(String),

    /// Generic / unexpected error.
    #[error("{0}")]
    Other(String),
}

// ---------------------------------------------------------------------------
// Conversions from OpenMLS error types
// ---------------------------------------------------------------------------

impl From<openmls::error::LibraryError> for MlsError {
    fn from(e: openmls::error::LibraryError) -> Self {
        MlsError::Other(format!("OpenMLS library error: {e:?}"))
    }
}

impl From<tls_codec::Error> for MlsError {
    fn from(e: tls_codec::Error) -> Self {
        MlsError::TlsCodec(format!("{e:?}"))
    }
}

impl From<serde_json::Error> for MlsError {
    fn from(e: serde_json::Error) -> Self {
        MlsError::Serialization(e.to_string())
    }
}
