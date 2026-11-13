//! Comprehensive error types for transport operations.

use thiserror::Error;

/// Errors that can occur during transport operations.
#[derive(Debug, Error)]
pub enum TransportError {
    /// Not connected to any relay.
    #[error("not connected to relay")]
    NotConnected,

    /// Connection to relay failed.
    #[error("connection to relay '{url}' failed: {reason}")]
    ConnectionFailed {
        /// The relay URL that failed.
        url: String,
        /// The reason for the failure.
        reason: String,
    },

    /// Publishing an event failed.
    #[error("publish failed: {0}")]
    PublishFailed(String),

    /// Subscription failed.
    #[error("subscription failed: {0}")]
    SubscriptionFailed(String),

    /// Event query failed.
    #[error("event query failed: {0}")]
    QueryFailed(String),

    /// Serialization error.
    #[error("serialization error: {0}")]
    Serialization(String),

    /// Deserialization error.
    #[error("deserialization error: {0}")]
    Deserialization(String),

    /// Gift wrap (NIP-59) error.
    #[error("gift wrap error: {0}")]
    GiftWrap(String),

    /// Encryption/decryption error (NIP-44).
    #[error("encryption error: {0}")]
    Encryption(String),

    /// Invalid MLS envelope format.
    #[error("invalid envelope: {0}")]
    InvalidEnvelope(String),

    /// Nostr SDK error.
    #[error("nostr error: {0}")]
    Nostr(String),

    /// A timeout occurred.
    #[error("timeout: {0}")]
    Timeout(String),

    /// Signer not configured on the client.
    #[error("signer not configured")]
    SignerNotConfigured,

    /// Event not found.
    #[error("event not found")]
    EventNotFound,

    /// Relay pool error — all relays failed to respond.
    #[error("all relays failed: {0}")]
    AllRelaysFailed(String),
}

/// Convenience alias for results in this crate.
pub type Result<T> = std::result::Result<T, TransportError>;

impl From<nostr_sdk::client::Error> for TransportError {
    fn from(err: nostr_sdk::client::Error) -> Self {
        TransportError::Nostr(err.to_string())
    }
}

impl From<nostr::event::builder::Error> for TransportError {
    fn from(err: nostr::event::builder::Error) -> Self {
        TransportError::Nostr(err.to_string())
    }
}

impl From<nostr::nips::nip59::Error> for TransportError {
    fn from(err: nostr::nips::nip59::Error) -> Self {
        TransportError::GiftWrap(err.to_string())
    }
}

impl From<nostr::event::Error> for TransportError {
    fn from(err: nostr::event::Error) -> Self {
        TransportError::Nostr(err.to_string())
    }
}

impl From<nostr::signer::SignerError> for TransportError {
    fn from(err: nostr::signer::SignerError) -> Self {
        TransportError::Nostr(err.to_string())
    }
}

impl From<base64::DecodeError> for TransportError {
    fn from(err: base64::DecodeError) -> Self {
        TransportError::Deserialization(err.to_string())
    }
}

impl From<std::io::Error> for TransportError {
    fn from(err: std::io::Error) -> Self {
        TransportError::Nostr(err.to_string())
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transport_error_display_not_connected() {
        let err = TransportError::NotConnected;
        assert_eq!(err.to_string(), "not connected to relay");
    }

    #[test]
    fn transport_error_display_connection_failed() {
        let err = TransportError::ConnectionFailed {
            url: "wss://relay.example".to_string(),
            reason: "timeout".to_string(),
        };
        let msg = err.to_string();
        assert!(msg.contains("wss://relay.example"));
        assert!(msg.contains("timeout"));
    }

    #[test]
    fn transport_error_display_publish_failed() {
        let err = TransportError::PublishFailed("signing error".to_string());
        let msg = err.to_string();
        assert!(msg.contains("publish failed"));
        assert!(msg.contains("signing error"));
    }

    #[test]
    fn transport_error_display_invalid_envelope() {
        let err = TransportError::InvalidEnvelope("missing version".to_string());
        let msg = err.to_string();
        assert!(msg.contains("invalid envelope"));
        assert!(msg.contains("missing version"));
    }

    #[test]
    fn transport_error_display_gift_wrap() {
        let err = TransportError::GiftWrap("unwrap failed".to_string());
        let msg = err.to_string();
        assert!(msg.contains("gift wrap"));
        assert!(msg.contains("unwrap failed"));
    }

    #[test]
    fn transport_error_display_serialization() {
        let err = TransportError::Serialization("json error".to_string());
        let msg = err.to_string();
        assert!(msg.contains("serialization"));
    }

    #[test]
    fn transport_error_display_deserialization() {
        let err = TransportError::Deserialization("base64 error".to_string());
        let msg = err.to_string();
        assert!(msg.contains("deserialization"));
    }

    #[test]
    fn transport_error_display_all_relays_failed() {
        let err = TransportError::AllRelaysFailed("no relay responded".to_string());
        let msg = err.to_string();
        assert!(msg.contains("all relays failed"));
    }

    #[test]
    fn result_type_ok() {
        let res: Result<String> = Ok("hello".to_string());
        assert_eq!(res.unwrap(), "hello");
    }

    #[test]
    fn result_type_err() {
        let res: Result<String> = Err(TransportError::NotConnected);
        assert!(res.is_err());
    }
}
