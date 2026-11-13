//! Message builder pattern for constructing AlphaMessage instances.
//!
//! Provides a fluent API with type-safe builders for each message variant.
//! IDs are auto-generated with UUID v4 if not explicitly specified.

use crate::expiry::ExpiryPolicy;
use crate::message_type::AlphaMessage;

/// Generates a new UUID v4 string.
fn new_uuid() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// Entry point for the message builder pattern.
pub struct MessageBuilder;

impl MessageBuilder {
    /// Create a text message builder.
    pub fn text(body: &str) -> TextBuilder {
        TextBuilder {
            id: new_uuid(),
            body: body.to_string(),
            reply_to: None,
            expiry: None,
        }
    }

    /// Create an edit message builder.
    pub fn edit(id: &str, new_body: &str) -> EditBuilder {
        EditBuilder {
            id: id.to_string(),
            body: new_body.to_string(),
            edited_at: chrono::Utc::now().to_rfc3339(),
        }
    }

    /// Create a reaction message builder.
    pub fn reaction(target_id: &str, emoji: &str) -> ReactionBuilder {
        ReactionBuilder {
            id: new_uuid(),
            target_id: target_id.to_string(),
            emoji: emoji.to_string(),
            remove: None,
        }
    }

    /// Create a reply message builder.
    pub fn reply(reply_to: &str, body: &str) -> ReplyBuilder {
        ReplyBuilder {
            id: new_uuid(),
            body: body.to_string(),
            reply_to: reply_to.to_string(),
        }
    }

    /// Create a delete message builder.
    pub fn delete(target_id: &str) -> DeleteBuilder {
        DeleteBuilder {
            id: new_uuid(),
            target_id: target_id.to_string(),
        }
    }

    /// Create a disappearing message builder (legacy).
    ///
    /// Prefer `MessageBuilder::text(body).expiry(ExpiryPolicy::AfterSend { ttl_secs })`
    /// for new code.
    pub fn disappearing(body: &str, ttl_seconds: u32) -> DisappearingBuilder {
        DisappearingBuilder {
            id: new_uuid(),
            body: body.to_string(),
            ttl_seconds,
        }
    }

    /// Create a media message builder.
    pub fn media(
        media_ref: &str,
        media_type: &str,
        filename: &str,
        size_bytes: u64,
    ) -> MediaBuilder {
        MediaBuilder {
            id: new_uuid(),
            media_ref: media_ref.to_string(),
            media_type: media_type.to_string(),
            filename: filename.to_string(),
            size_bytes,
            caption: None,
            reply_to: None,
            expiry: None,
        }
    }

    /// Create a read-signal builder.
    ///
    /// Sent by the recipient when they open an `AfterRead` ephemeral
    /// message, so the sender also starts their expiry timer.
    pub fn read_signal(target_id: &str, chat_id: &str) -> ReadSignalBuilder {
        ReadSignalBuilder {
            target_id: target_id.to_string(),
            chat_id: chat_id.to_string(),
            read_at: chrono::Utc::now().to_rfc3339(),
        }
    }

    /// Create a chat-state (typing / recording) signal. `state` is one of
    /// "typing", "recording", "paused". The timestamp is filled with the
    /// current epoch millis.
    /// `thread`: the channel post whose discussion this happens in.
    pub fn chat_state(chat_id: &str, state: &str, thread: Option<&str>) -> AlphaMessage {
        AlphaMessage::ChatState {
            chat_id: chat_id.to_string(),
            state: state.to_string(),
            ts: chrono::Utc::now().timestamp_millis().max(0) as u64,
            thread: thread.filter(|t| !t.is_empty()).map(str::to_string),
        }
    }
}

/// Builder for text messages.
pub struct TextBuilder {
    id: String,
    body: String,
    reply_to: Option<String>,
    expiry: Option<ExpiryPolicy>,
}

impl TextBuilder {
    /// Set a custom message ID (overrides auto-generated UUID).
    pub fn id(mut self, id: &str) -> Self {
        self.id = id.to_string();
        self
    }

    /// Set the message this is a reply to.
    pub fn reply_to(mut self, reply_to: &str) -> Self {
        self.reply_to = Some(reply_to.to_string());
        self
    }

    /// Set the expiry policy for this text message.
    ///
    /// Use this for ephemeral messages:
    /// - `ExpiryPolicy::AfterSend { ttl_secs }` — timer starts at send.
    /// - `ExpiryPolicy::AfterRead { ttl_secs }` — timer starts at read.
    /// - `ExpiryPolicy::ViewOnce` — render once, then zeroize.
    pub fn expiry(mut self, policy: ExpiryPolicy) -> Self {
        self.expiry = Some(policy);
        self
    }

    /// Convenience: set AfterSend expiry with the given TTL.
    pub fn after_send(self, ttl_secs: u32) -> Self {
        self.expiry(ExpiryPolicy::AfterSend { ttl_secs })
    }

    /// Convenience: set AfterRead expiry with the given TTL.
    pub fn after_read(self, ttl_secs: u32) -> Self {
        self.expiry(ExpiryPolicy::AfterRead { ttl_secs })
    }

    /// Convenience: set ViewOnce expiry.
    pub fn view_once(self) -> Self {
        self.expiry(ExpiryPolicy::ViewOnce)
    }

    /// Build the text message.
    pub fn build(self) -> AlphaMessage {
        AlphaMessage::Text { sent_ms: 0, silent: false,
            id: self.id,
            body: self.body,
            reply_to: self.reply_to,
            expiry: self.expiry,
            seq: 0,
            thread: None,
        }
    }
}

/// Builder for edit messages.
pub struct EditBuilder {
    id: String,
    body: String,
    edited_at: String,
}

impl EditBuilder {
    /// Set a custom edited_at timestamp (RFC3339).
    pub fn edited_at(mut self, edited_at: &str) -> Self {
        self.edited_at = edited_at.to_string();
        self
    }

    /// Build the edit message.
    pub fn build(self) -> AlphaMessage {
        AlphaMessage::Edit {
            id: self.id,
            body: self.body,
            edited_at: self.edited_at,
            seq: 0,
        }
    }
}

/// Builder for reaction messages.
pub struct ReactionBuilder {
    id: String,
    target_id: String,
    emoji: String,
    remove: Option<bool>,
}

impl ReactionBuilder {
    /// Set a custom reaction ID (overrides auto-generated UUID).
    pub fn id(mut self, id: &str) -> Self {
        self.id = id.to_string();
        self
    }

    /// Mark this reaction as a removal.
    pub fn remove(mut self) -> Self {
        self.remove = Some(true);
        self
    }

    /// Build the reaction message.
    pub fn build(self) -> AlphaMessage {
        AlphaMessage::Reaction {
            id: self.id,
            target_id: self.target_id,
            emoji: self.emoji,
            remove: self.remove,
            seq: 0,
        }
    }
}

/// Builder for reply messages.
pub struct ReplyBuilder {
    id: String,
    body: String,
    reply_to: String,
}

impl ReplyBuilder {
    /// Set a custom message ID (overrides auto-generated UUID).
    pub fn id(mut self, id: &str) -> Self {
        self.id = id.to_string();
        self
    }

    /// Build the reply message.
    pub fn build(self) -> AlphaMessage {
        AlphaMessage::Reply { sent_ms: 0, silent: false,
            id: self.id,
            body: self.body,
            reply_to: self.reply_to,
            quoted_text: String::new(),
            seq: 0,
            thread: None,
        }
    }
}

/// Builder for delete messages.
pub struct DeleteBuilder {
    id: String,
    target_id: String,
}

impl DeleteBuilder {
    /// Set a custom message ID (overrides auto-generated UUID).
    pub fn id(mut self, id: &str) -> Self {
        self.id = id.to_string();
        self
    }

    /// Build the delete message.
    pub fn build(self) -> AlphaMessage {
        AlphaMessage::Delete {
            id: self.id,
            target_id: self.target_id,
            seq: 0,
        }
    }
}

/// Builder for disappearing messages (legacy).
pub struct DisappearingBuilder {
    id: String,
    body: String,
    ttl_seconds: u32,
}

impl DisappearingBuilder {
    /// Set a custom message ID (overrides auto-generated UUID).
    pub fn id(mut self, id: &str) -> Self {
        self.id = id.to_string();
        self
    }

    /// Build the disappearing message.
    pub fn build(self) -> AlphaMessage {
        AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: self.id,
            body: self.body,
            ttl_seconds: self.ttl_seconds,
            seq: 0,
        }
    }
}

/// Builder for media messages.
pub struct MediaBuilder {
    id: String,
    media_ref: String,
    media_type: String,
    filename: String,
    size_bytes: u64,
    caption: Option<String>,
    reply_to: Option<String>,
    expiry: Option<ExpiryPolicy>,
}

impl MediaBuilder {
    /// Set a custom message ID (overrides auto-generated UUID).
    pub fn id(mut self, id: &str) -> Self {
        self.id = id.to_string();
        self
    }

    /// Set an optional caption for the media.
    pub fn caption(mut self, caption: &str) -> Self {
        self.caption = Some(caption.to_string());
        self
    }

    /// Set the message this media is a reply to.
    pub fn reply_to(mut self, reply_to: &str) -> Self {
        self.reply_to = Some(reply_to.to_string());
        self
    }

    /// Set the expiry policy for this media message.
    pub fn expiry(mut self, policy: ExpiryPolicy) -> Self {
        self.expiry = Some(policy);
        self
    }

    /// Convenience: set AfterSend expiry with the given TTL.
    pub fn after_send(self, ttl_secs: u32) -> Self {
        self.expiry(ExpiryPolicy::AfterSend { ttl_secs })
    }

    /// Convenience: set AfterRead expiry with the given TTL.
    pub fn after_read(self, ttl_secs: u32) -> Self {
        self.expiry(ExpiryPolicy::AfterRead { ttl_secs })
    }

    /// Convenience: set ViewOnce expiry.
    pub fn view_once(self) -> Self {
        self.expiry(ExpiryPolicy::ViewOnce)
    }

    /// Build the media message.
    pub fn build(self) -> AlphaMessage {
        AlphaMessage::Media { sent_ms: 0, silent: false,
            id: self.id,
            media_ref: self.media_ref,
            media_type: self.media_type,
            filename: self.filename,
            size_bytes: self.size_bytes,
            caption: self.caption,
            reply_to: self.reply_to,
            expiry: self.expiry,
            seq: 0,
            thread: None,
        }
    }
}

/// Builder for read-signal messages.
pub struct ReadSignalBuilder {
    target_id: String,
    chat_id: String,
    read_at: String,
}

impl ReadSignalBuilder {
    /// Set a custom read_at timestamp (defaults to now).
    pub fn read_at(mut self, read_at: &str) -> Self {
        self.read_at = read_at.to_string();
        self
    }

    /// Build the read-signal message.
    pub fn build(self) -> AlphaMessage {
        AlphaMessage::ReadSignal {
            target_id: self.target_id,
            chat_id: self.chat_id,
            read_at: self.read_at,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_text_builder_basic() {
        let msg = MessageBuilder::text("Hello").build();
        match msg {
            AlphaMessage::Text { body, reply_to, expiry, .. } => {
                assert_eq!(body, "Hello");
                assert!(reply_to.is_none());
                assert!(expiry.is_none());
            }
            _ => panic!("Expected Text message"),
        }
    }

    #[test]
    fn test_text_builder_with_reply() {
        let msg = MessageBuilder::text("Hello")
            .reply_to("660e8400-e29b-41d4-a716-446655440001")
            .build();
        match msg {
            AlphaMessage::Text { body, reply_to, .. } => {
                assert_eq!(body, "Hello");
                assert_eq!(
                    reply_to,
                    Some("660e8400-e29b-41d4-a716-446655440001".to_string())
                );
            }
            _ => panic!("Expected Text message"),
        }
    }

    #[test]
    fn test_text_builder_custom_id() {
        let msg = MessageBuilder::text("Hello").id("custom-id").build();
        assert_eq!(msg.id(), "custom-id");
    }

    #[test]
    fn test_text_builder_after_send() {
        let msg = MessageBuilder::text("Vanishing")
            .after_send(60)
            .build();
        match msg {
            AlphaMessage::Text { expiry, .. } => {
                assert!(matches!(expiry, Some(ExpiryPolicy::AfterSend { ttl_secs: 60 })));
            }
            _ => panic!("Expected Text message"),
        }
    }

    #[test]
    fn test_text_builder_after_read() {
        let msg = MessageBuilder::text("Read me")
            .after_read(30)
            .build();
        match msg {
            AlphaMessage::Text { expiry, .. } => {
                assert!(matches!(expiry, Some(ExpiryPolicy::AfterRead { ttl_secs: 30 })));
            }
            _ => panic!("Expected Text message"),
        }
    }

    #[test]
    fn test_text_builder_view_once() {
        let msg = MessageBuilder::text("Secret")
            .view_once()
            .build();
        match msg {
            AlphaMessage::Text { expiry, .. } => {
                assert!(matches!(expiry, Some(ExpiryPolicy::ViewOnce)));
            }
            _ => panic!("Expected Text message"),
        }
    }

    #[test]
    fn test_edit_builder() {
        let msg = MessageBuilder::edit("msg-id", "New text").build();
        match msg {
            AlphaMessage::Edit {
                id,
                body,
                edited_at,
                ..
            } => {
                assert_eq!(id, "msg-id");
                assert_eq!(body, "New text");
                assert!(!edited_at.is_empty());
            }
            _ => panic!("Expected Edit message"),
        }
    }

    #[test]
    fn test_reaction_builder() {
        let msg = MessageBuilder::reaction("target-id", "👍").build();
        match msg {
            AlphaMessage::Reaction {
                target_id,
                emoji,
                remove,
                ..
            } => {
                assert_eq!(target_id, "target-id");
                assert_eq!(emoji, "👍");
                assert!(remove.is_none());
            }
            _ => panic!("Expected Reaction message"),
        }
    }

    #[test]
    fn test_reaction_builder_remove() {
        let msg = MessageBuilder::reaction("target-id", "👍").remove().build();
        match msg {
            AlphaMessage::Reaction { remove, .. } => {
                assert_eq!(remove, Some(true));
            }
            _ => panic!("Expected Reaction message"),
        }
    }

    #[test]
    fn test_reply_builder() {
        let msg = MessageBuilder::reply("original-id", "My reply").build();
        match msg {
            AlphaMessage::Reply {
                body,
                reply_to,
                ..
            } => {
                assert_eq!(body, "My reply");
                assert_eq!(reply_to, "original-id");
            }
            _ => panic!("Expected Reply message"),
        }
    }

    #[test]
    fn test_delete_builder() {
        let msg = MessageBuilder::delete("target-id").build();
        match msg {
            AlphaMessage::Delete { target_id, .. } => {
                assert_eq!(target_id, "target-id");
            }
            _ => panic!("Expected Delete message"),
        }
    }

    #[test]
    fn test_disappearing_builder() {
        let msg = MessageBuilder::disappearing("Secret", 60).build();
        match msg {
            AlphaMessage::Disappearing {
                body,
                ttl_seconds,
                ..
            } => {
                assert_eq!(body, "Secret");
                assert_eq!(ttl_seconds, 60);
            }
            _ => panic!("Expected Disappearing message"),
        }
    }

    #[test]
    fn test_media_builder() {
        let msg = MessageBuilder::media("ref123", "image", "photo.png", 2048)
            .caption("Nice pic")
            .reply_to("msg-id")
            .build();
        match msg {
            AlphaMessage::Media {
                media_ref,
                media_type,
                filename,
                size_bytes,
                caption,
                reply_to,
                expiry,
                ..
            } => {
                assert_eq!(media_ref, "ref123");
                assert_eq!(media_type, "image");
                assert_eq!(filename, "photo.png");
                assert_eq!(size_bytes, 2048);
                assert_eq!(caption, Some("Nice pic".to_string()));
                assert_eq!(reply_to, Some("msg-id".to_string()));
                assert!(expiry.is_none());
            }
            _ => panic!("Expected Media message"),
        }
    }

    #[test]
    fn test_media_builder_view_once() {
        let msg = MessageBuilder::media("ref123", "image", "photo.png", 2048)
            .view_once()
            .build();
        match msg {
            AlphaMessage::Media { expiry, .. } => {
                assert!(matches!(expiry, Some(ExpiryPolicy::ViewOnce)));
            }
            _ => panic!("Expected Media message"),
        }
    }

    #[test]
    fn test_read_signal_builder() {
        let msg = MessageBuilder::read_signal("msg-id", "chat-id").build();
        match msg {
            AlphaMessage::ReadSignal {
                target_id,
                chat_id,
                read_at,
            } => {
                assert_eq!(target_id, "msg-id");
                assert_eq!(chat_id, "chat-id");
                assert!(!read_at.is_empty());
            }
            _ => panic!("Expected ReadSignal message"),
        }
    }

    #[test]
    fn test_read_signal_builder_custom_time() {
        let msg = MessageBuilder::read_signal("msg-id", "chat-id")
            .read_at("2025-01-15T12:30:00Z")
            .build();
        match msg {
            AlphaMessage::ReadSignal { read_at, .. } => {
                assert_eq!(read_at, "2025-01-15T12:30:00Z");
            }
            _ => panic!("Expected ReadSignal message"),
        }
    }

    #[test]
    fn test_auto_generated_ids_are_valid_uuids() {
        let msg = MessageBuilder::text("test").build();
        let id = msg.id();
        assert!(uuid::Uuid::parse_str(id).is_ok(), "ID should be valid UUID v4");
    }
}
