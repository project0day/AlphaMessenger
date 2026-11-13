//! Serialization for MLS payload.
//!
//! All messages are JSON-serialized in compact format (no whitespace)
//! to minimize MLS ciphertext overhead.

use crate::error::Result;
use crate::message_type::AlphaMessage;

/// Serialize a single message to compact JSON bytes.
///
/// Uses serde_json with compact format (no whitespace) to minimize
/// the size of the MLS ciphertext.
pub fn serialize(message: &AlphaMessage) -> Result<Vec<u8>> {
    let bytes = serde_json::to_vec(message)?;
    Ok(bytes)
}

/// Deserialize a single message from JSON bytes.
///
/// Parses the raw bytes obtained after MLS decryption into a structured
/// AlphaMessage.
pub fn deserialize(bytes: &[u8]) -> Result<AlphaMessage> {
    let message: AlphaMessage = serde_json::from_slice(bytes)?;
    Ok(message)
}

/// Serialize a batch of messages to compact JSON bytes.
///
/// Useful for sending multiple messages in a single MLS application
/// message to reduce encryption overhead.
pub fn serialize_batch(messages: &[AlphaMessage]) -> Result<Vec<u8>> {
    let bytes = serde_json::to_vec(messages)?;
    Ok(bytes)
}

/// Deserialize a batch of messages from JSON bytes.
///
/// Parses the raw bytes obtained after MLS decryption into a vector
/// of AlphaMessage instances.
pub fn deserialize_batch(bytes: &[u8]) -> Result<Vec<AlphaMessage>> {
    let messages: Vec<AlphaMessage> = serde_json::from_slice(bytes)?;
    Ok(messages)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::builder::MessageBuilder;

    #[test]
    fn test_serialize_deserialize_text() {
        let msg = MessageBuilder::text("Hello, world!").build();
        let bytes = serialize(&msg).expect("serialize");
        let deserialized = deserialize(&bytes).expect("deserialize");
        assert_eq!(msg, deserialized);
    }

    #[test]
    fn test_serialize_is_compact() {
        let msg = MessageBuilder::text("Hello").build();
        let bytes = serialize(&msg).expect("serialize");
        let json_str = String::from_utf8(bytes).expect("valid utf8");
        // Compact JSON should not contain newlines or extra spaces
        assert!(!json_str.contains('\n'), "compact JSON should not have newlines");
        assert!(!json_str.contains(": "), "compact JSON should not have ': ' spacing");
    }

    #[test]
    fn test_serialize_deserialize_all_types() {
        let messages = vec![
            MessageBuilder::text("text msg").build(),
            MessageBuilder::edit("edit-id", "new body")
                .edited_at("2025-01-15T12:00:00Z")
                .build(),
            MessageBuilder::reaction("target-id", "👍").build(),
            MessageBuilder::reply("reply-to-id", "reply body").build(),
            MessageBuilder::delete("target-id").build(),
            MessageBuilder::disappearing("secret", 60).build(),
            MessageBuilder::media("ref", "image", "photo.png", 1024)
                .caption("nice")
                .build(),
        ];

        for msg in &messages {
            let bytes = serialize(msg).expect("serialize");
            let deserialized = deserialize(&bytes).expect("deserialize");
            assert_eq!(*msg, deserialized);
        }
    }

    #[test]
    fn test_serialize_batch() {
        let messages = vec![
            MessageBuilder::text("msg1").build(),
            MessageBuilder::text("msg2").build(),
            MessageBuilder::text("msg3").build(),
        ];
        let bytes = serialize_batch(&messages).expect("serialize_batch");
        let deserialized = deserialize_batch(&bytes).expect("deserialize_batch");
        assert_eq!(messages, deserialized);
    }

    #[test]
    fn test_deserialize_invalid_json() {
        let result = deserialize(b"not json");
        assert!(result.is_err());
    }

    #[test]
    fn test_deserialize_batch_invalid_json() {
        let result = deserialize_batch(b"not json");
        assert!(result.is_err());
    }

    #[test]
    fn test_deserialize_unknown_type() {
        let json = r#"{"type":"unknown","id":"x"}"#;
        let result = deserialize(json.as_bytes());
        assert!(result.is_err());
    }

    #[test]
    fn test_roundtrip_preserves_optional_fields() {
        // With optional fields
        let msg_with = MessageBuilder::text("hello")
            .reply_to("reply-id")
            .build();
        let bytes_with = serialize(&msg_with).expect("serialize");
        let back_with = deserialize(&bytes_with).expect("deserialize");
        assert_eq!(msg_with, back_with);

        // Without optional fields
        let msg_without = MessageBuilder::text("hello").build();
        let bytes_without = serialize(&msg_without).expect("serialize");
        let back_without = deserialize(&bytes_without).expect("deserialize");
        assert_eq!(msg_without, back_without);
    }
}
