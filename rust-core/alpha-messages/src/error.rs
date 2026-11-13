//! Error types for message operations.

use thiserror::Error;

/// Errors that can occur during message operations.
#[derive(Debug, Error)]
pub enum MessageError {
    /// Serialization failed.
    #[error("serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    /// Validation failed.
    #[error("validation error: {0}")]
    Validation(String),

    /// Invalid UUID format.
    #[error("invalid UUID: {0}")]
    InvalidUuid(String),

    /// Empty body where content is required.
    #[error("empty body")]
    EmptyBody,

    /// Body exceeds maximum length.
    #[error("body exceeds maximum length of {max} characters (got {actual})")]
    BodyTooLong {
        max: usize,
        actual: usize,
    },

    /// Invalid emoji for reaction.
    #[error("invalid emoji: {0}")]
    InvalidEmoji(String),

    /// Invalid TTL value for disappearing messages.
    #[error("invalid TTL: {ttl_seconds}s (must be {min}..{max})")]
    InvalidTtl {
        ttl_seconds: u32,
        min: u32,
        max: u32,
    },

    /// Empty field where a value is required.
    #[error("empty field: {0}")]
    EmptyField(String),

    /// Invalid size value.
    #[error("invalid size: {0}")]
    InvalidSize(String),

    /// Batch deserialization error.
    #[error("batch deserialization error at index {index}: {reason}")]
    BatchDeserialization {
        index: usize,
        reason: String,
    },
}

/// Convenience alias for results in this crate.
pub type Result<T> = std::result::Result<T, MessageError>;
