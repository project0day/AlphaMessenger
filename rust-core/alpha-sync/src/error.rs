//! Error types for sync operations.

use thiserror::Error;

/// Errors that can occur during sync operations.
#[derive(Debug, Error)]
pub enum SyncError {
    /// The database operation failed.
    #[error("database error: {0}")]
    Database(#[from] alpha_database::error::DatabaseError),

    /// Transport/relay communication failed.
    #[error("transport error: {0}")]
    Transport(#[from] alpha_transport::error::TransportError),

    /// MLS session error.
    #[error("MLS error: {0}")]
    Mls(String),

    /// Message processing error.
    #[error("message processing error: {0}")]
    MessageProcessing(String),

    /// Checkpoint not found for a relay.
    #[error("no checkpoint for relay: {0}")]
    NoCheckpoint(String),

    /// Catch-up failed.
    #[error("catch-up failed: {0}")]
    CatchUpFailed(String),

    /// An epoch gap was detected during processing.
    #[error("epoch gap in chat {chat_id}: expected {expected}, got {actual}")]
    EpochGap {
        chat_id: String,
        expected: u64,
        actual: u64,
    },

    /// The sync manager is not initialized.
    #[error("sync manager not initialized")]
    NotInitialized,

    /// Serialization/deserialization error.
    #[error("serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    /// Internal error (e.g. lock poisoned).
    #[error("internal error: {0}")]
    Internal(String),

    /// A generic error.
    #[error("{0}")]
    Other(String),
}

/// Convenience alias for results in this crate.
pub type Result<T> = std::result::Result<T, SyncError>;
