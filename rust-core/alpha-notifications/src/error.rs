//! Error types for notification operations.

use thiserror::Error;

/// Errors that can occur during notification operations.
#[derive(Debug, Error)]
pub enum NotificationError {
    /// HTTP request to the ntfy server failed.
    #[error("ntfy HTTP error: {0}")]
    Http(#[from] reqwest::Error),

    /// The ntfy server returned an error response.
    #[error("ntfy server error: status {status}, body: {body}")]
    ServerError { status: u16, body: String },

    /// Topic derivation failed.
    #[error("topic derivation failed: {0}")]
    TopicDerivation(String),

    /// Subscription stream closed unexpectedly.
    #[error("subscription closed: {0}")]
    SubscriptionClosed(String),

    /// Notification payload was invalid.
    #[error("invalid notification: {0}")]
    InvalidNotification(String),

    /// The wake service is already running.
    #[error("wake service already running")]
    AlreadyRunning,

    /// The wake service is not running.
    #[error("wake service not running")]
    NotRunning,

    /// UnifiedPush registration failed.
    #[error("UnifiedPush registration failed: {0}")]
    UnifiedPushRegistration(String),

    /// UnifiedPush unregistration failed.
    #[error("UnifiedPush unregistration failed: {0}")]
    UnifiedPushUnregistration(String),

    /// Serialization/deserialization error.
    #[error("serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    /// A generic error.
    #[error("{0}")]
    Other(String),
}

/// Convenience alias for results in this crate.
pub type Result<T> = std::result::Result<T, NotificationError>;
