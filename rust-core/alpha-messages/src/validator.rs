//! Message validation logic.
//!
//! Validates message structure and content according to AlphaMessenger rules.
//! This is pure data validation — no crypto is involved.

use crate::error::{MessageError, Result};
use crate::expiry::{self, ExpiryPolicy};
use crate::message_type::AlphaMessage;

/// Maximum body length in characters.
const MAX_BODY_LENGTH: usize = 32_000;

/// Maximum emoji length in characters.
const MAX_EMOJI_LENGTH: usize = 8;

/// Minimum TTL for disappearing messages (5 seconds).
const MIN_TTL_SECONDS: u32 = 5;

/// Maximum TTL for disappearing messages (24 hours).
const MAX_TTL_SECONDS: u32 = 86_400;

/// Validate a message's structure and content.
///
/// # Errors
///
/// Returns `MessageError` if any validation rule is violated.
/// What a received message may carry. [`validate`] holds what WE send to its
/// own, stricter rules; this only stops a peer from making us store or loop over
/// more than any real message needs, with room above what our clients send.
pub fn validate_inbound(message: &AlphaMessage) -> Result<()> {
    const TEXT: usize = 64 * 1024;
    const SHORT: usize = 1024;
    const IMAGE_B64: usize = 3 * 512 * 1024; // 1.5 MiB of base64
    const MEDIA_REF: usize = 256 * 1024;
    fn len(field: &str, s: &str, max: usize) -> Result<()> {
        if s.len() > max { Err(MessageError::InvalidSize(format!("{field}: {} > {max}", s.len()))) } else { Ok(()) }
    }
    fn count(field: &str, n: usize, max: usize) -> Result<()> {
        if n > max { Err(MessageError::InvalidSize(format!("{field}: {n} items > {max}"))) } else { Ok(()) }
    }
    if let Some(thread) = message.thread() {
        len("thread", thread, SHORT)?;
    }
    match message {
        AlphaMessage::Text { body, .. } | AlphaMessage::Edit { body, .. } => len("body", body, TEXT),
        AlphaMessage::Reply { body, quoted_text, .. } => {
            len("body", body, TEXT)?;
            len("quoted_text", quoted_text, TEXT)
        }
        AlphaMessage::Reaction { emoji, .. } => len("emoji", emoji, SHORT),
        AlphaMessage::Media { media_ref, media_type, filename, caption, .. } => {
            len("media_ref", media_ref, MEDIA_REF)?;
            len("media_type", media_type, SHORT)?;
            len("filename", filename, SHORT)?;
            len("caption", caption.as_deref().unwrap_or(""), TEXT)
        }
        AlphaMessage::DeliveredSignal { message_ids, .. } => count("message_ids", message_ids.len(), 2_000),
        // Call signalling rides here: an SDP offer is several kilobytes.
        AlphaMessage::ChatState { state, .. } => len("state", state, TEXT),
        AlphaMessage::ProfileUpdate { display_name, bio, avatar_jpeg_base64, cover_jpeg_base64, status, .. } => {
            len("display_name", display_name, SHORT)?;
            len("bio", bio, TEXT)?;
            len("avatar", avatar_jpeg_base64, IMAGE_B64)?;
            len("cover", cover_jpeg_base64, IMAGE_B64)?;
            len("status", status.as_deref().unwrap_or(""), SHORT)
        }
        AlphaMessage::GroupMetadataUpdate { name, description, avatar_jpeg_base64, .. } => {
            len("name", name, SHORT)?;
            len("description", description, TEXT)?;
            len("avatar", avatar_jpeg_base64, IMAGE_B64)
        }
        AlphaMessage::GroupRoleSnapshot { roles_json, .. } => len("roles_json", roles_json, MEDIA_REF),
        AlphaMessage::GroupBan { reason, .. } => len("reason", reason, SHORT),
        AlphaMessage::StoryPost { media_ref, caption, .. } => {
            len("media_ref", media_ref, MEDIA_REF)?;
            len("caption", caption, TEXT)
        }
        AlphaMessage::GroupPermissionsUpdate { reactions, .. } => count("reactions", reactions.len(), 512),
        AlphaMessage::PollVote { options, .. } => count("options", options.len(), 64),
        AlphaMessage::PollClose { tally, .. } => count("tally", tally.len(), 64),
        AlphaMessage::ChannelHistory { items, .. } => count("items", items.len(), 500),
        AlphaMessage::ChannelSeen { post_ids, forwarded, .. } => {
            count("post_ids", post_ids.len(), 5_000)?;
            count("forwarded", forwarded.len(), 5_000)
        }
        AlphaMessage::GroupDirectory { entries, .. } => count("entries", entries.len(), 10_000),
        AlphaMessage::SecretChat { invite_id, step, data, .. } => {
            len("invite_id", invite_id, SHORT)?;
            len("step", step, SHORT)?;
            len("data", data, MEDIA_REF)
        }
        _ => Ok(()),
    }
}

pub fn validate(message: &AlphaMessage) -> Result<()> {
    if let Some(thread) = message.thread() {
        validate_id(thread)?;
    }
    match message {
                AlphaMessage::Text { id, body, reply_to, expiry, .. } => {
            validate_id(id)?;
            validate_body(body)?;
            if let Some(ref rid) = reply_to {
                validate_id(rid)?;
            }
            if let Some(ref policy) = expiry {
                validate_expiry_policy(policy)?;
            }
        }
                AlphaMessage::Edit { id, body, edited_at, .. } => {
            validate_id(id)?;
            validate_body(body)?;
            validate_rfc3339(edited_at)?;
        }
        AlphaMessage::Reaction {
            id,
            target_id,
            emoji,
            remove: _,
            ..
        } => {
            validate_id(id)?;
            validate_id(target_id)?;
            validate_emoji(emoji)?;
        }
        AlphaMessage::Reply {
            id,
            body,
            reply_to,
            ..
        } => {
            validate_id(id)?;
            validate_body(body)?;
            validate_id(reply_to)?;
        }
                AlphaMessage::Delete { id, target_id, .. } => {
            validate_id(id)?;
            validate_id(target_id)?;
        }
        AlphaMessage::Disappearing {
            id,
            body,
            ttl_seconds,
            ..
        } => {
            validate_id(id)?;
            validate_body(body)?;
            validate_ttl(*ttl_seconds)?;
        }
        AlphaMessage::Media {
            id,
            media_ref,
            media_type,
            filename,
            size_bytes,
            caption: _,
            reply_to,
            expiry,
            ..
        } => {
            validate_id(id)?;
            validate_media_ref(media_ref)?;
            validate_media_type(media_type)?;
            validate_filename(filename)?;
            validate_size_bytes(*size_bytes)?;
            if let Some(ref rid) = reply_to {
                validate_id(rid)?;
            }
            if let Some(ref policy) = expiry {
                validate_expiry_policy(policy)?;
            }
        }
        AlphaMessage::ReadSignal {
            target_id,
            chat_id,
            read_at,
        } => {
            validate_id(target_id)?;
            if chat_id.is_empty() {
                return Err(MessageError::EmptyField("chat_id".to_string()));
            }
            validate_rfc3339(read_at)?;
        }
        AlphaMessage::DeliveredSignal { chat_id, message_ids } => {
            if chat_id.is_empty() {
                return Err(MessageError::EmptyField("chat_id".to_string()));
            }
            // One batch of received messages; a huge list is not a receipt.
            if message_ids.is_empty() || message_ids.len() > 500 {
                return Err(MessageError::Validation(format!("delivered signal with {} ids", message_ids.len())));
            }
            for id in message_ids {
                validate_id(id)?;
            }
        }
        AlphaMessage::ChatState { chat_id, state, .. } => {
            if chat_id.is_empty() {
                return Err(MessageError::EmptyField("chat_id".to_string()));
            }
            match state.as_str() {
                "typing" | "recording" | "paused" => {}
                // Calls, addressed signals, room states (bounded: an SDP fits).
                s if (s.starts_with("call:") || s.starts_with("to:") || s.starts_with("room:")) && s.len() <= 32 * 1024 => {}
                _ => {
                    return Err(MessageError::Validation(format!(
                        "invalid chat state: '{}'",
                        state
                    )));
                }
            }
        }
        AlphaMessage::ProfileUpdate { ts, .. } => {
            // Body sizes are bounded by MLS layer; we just sanity-check ts.
            validate_rfc3339(ts)?;
        }
        AlphaMessage::ProfileRequest { ts } => {
            validate_rfc3339(ts)?;
        }
        AlphaMessage::GroupMetadataUpdate { ts, look, .. } => {
            validate_rfc3339(ts)?;
            // A look is a small JSON object (colours + one photo ref).
            if !look.is_empty() && (look.len() > 8192
                || !serde_json::from_str::<serde_json::Value>(look).map(|v| v.is_object()).unwrap_or(false)) {
                return Err(MessageError::Validation("bad chat look".to_string()));
            }
        }
        AlphaMessage::GroupRoleUpdate { target_pubkey, role, ts, .. } => {
            if target_pubkey.is_empty() {
                return Err(MessageError::EmptyField("target_pubkey".to_string()));
            }
            match role.as_str() {
                "owner" | "admin" | "moderator" | "member" => {}
                _ => {
                    return Err(MessageError::Validation(format!(
                        "invalid role: '{}'",
                        role
                    )));
                }
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::GroupRoleSnapshot { owner_pubkey, ts, .. } => {
            if owner_pubkey.is_empty() {
                return Err(MessageError::EmptyField("owner_pubkey".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::GroupRoleSyncRequest { ts } => {
            validate_rfc3339(ts)?;
        }
                // --- Transport directory ---
        //
                // Only the record's FORM is validated here. Whether it matches the
                // current group membership, duplicates and contradictions — that
                // is the recipient's business: only it knows the membership, and it
                // must refuse wholesale, never silently drop a suspicious row.
        AlphaMessage::SecretChat { invite_id, step, ts, .. } => {
            validate_id(invite_id)?;
            if !matches!(step.as_str(), "invite" | "accept" | "welcome" | "decline" | "end") {
                return Err(MessageError::EmptyField("step".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::GroupDirectory { version, entries, ts, .. } => {
            if *version == 0 {
                return Err(MessageError::EmptyField("version".to_string()));
            }
            for e in entries {
                if e.sig_key.is_empty() {
                    return Err(MessageError::EmptyField("sig_key".to_string()));
                }
                if e.npub_hex.is_empty() {
                    return Err(MessageError::EmptyField("npub_hex".to_string()));
                }
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::GroupAuditEntry { id, action, ts, .. } => {
            if id.is_empty() {
                return Err(MessageError::EmptyField("id".to_string()));
            }
            if action.is_empty() {
                return Err(MessageError::EmptyField("action".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::ChannelHistory { items, ts } => {
            if items.is_empty() || items.len() > 100 || items.iter().any(|i| i.msg.is_empty() || i.msg.len() > 64 * 1024
                || i.from.as_ref().is_some_and(|f| f.is_empty() || f.len() > 128)) {
                return Err(MessageError::Validation("channel_history items".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::ChannelSeen { post_ids, forwarded, ts } => {
            if (post_ids.is_empty() && forwarded.is_empty()) || post_ids.len() > 100 || forwarded.len() > 100
                || post_ids.iter().chain(forwarded.iter()).any(|i| i.is_empty() || i.len() > 128) {
                return Err(MessageError::Validation("channel_seen ids".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::GroupPermissionsUpdate { who_can_write, who_can_invite, who_can_edit, reactions_mode, reactions, channel_cfg, ts, .. } => {
            validate_rfc3339(ts)?;
            if channel_cfg.len() > 32 * 1024 || (!channel_cfg.is_empty() && serde_json::from_str::<serde_json::Value>(channel_cfg).map(|v| !v.is_object()).unwrap_or(true)) {
                return Err(MessageError::Validation("channel_cfg".to_string()));
            }
            match reactions_mode.as_str() {
                "" | "all" | "some" | "none" => {}
                _ => return Err(MessageError::Validation(format!("invalid reactions mode: '{}'", reactions_mode))),
            }
            if reactions.len() > 64 || reactions.iter().any(|e| e.is_empty() || e.len() > 32) {
                return Err(MessageError::Validation("invalid reactions list".to_string()));
            }
            for v in [who_can_write, who_can_invite, who_can_edit] {
                match v.as_str() {
                    "all" | "admins" => {}
                    _ => return Err(MessageError::Validation(format!(
                        "invalid permission value: '{}' (must be all|admins)", v
                    ))),
                }
            }
        }
        AlphaMessage::GroupBan { target_pubkey, ts, .. } |
        AlphaMessage::GroupMuteUpdate { target_pubkey, ts, .. } => {
            if target_pubkey.is_empty() {
                return Err(MessageError::EmptyField("target_pubkey".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::StoryPost { story_id, kind, ts, expires_at, created_at, text, media_ref, stickers, .. } => {
            if stickers.len() > 8192 {
                return Err(MessageError::Validation("story stickers".to_string()));
            }
            if story_id.is_empty() || story_id.len() > 64 {
                return Err(MessageError::EmptyField("story_id".to_string()));
            }
            match kind.as_str() {
                "image" | "video" => if media_ref.is_empty() { return Err(MessageError::EmptyField("media_ref".to_string())); },
                "text" => if text.is_empty() || text.len() > 2000 { return Err(MessageError::Validation("story text".to_string())); },
                _ => return Err(MessageError::Validation(format!("story kind '{}'", kind))),
            }
            if expires_at <= created_at || expires_at - created_at > 7 * 24 * 3600 {
                return Err(MessageError::Validation("story lifetime".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::StoryDelete { story_id, ts } | AlphaMessage::StoryView { story_id, ts, .. } => {
            if story_id.is_empty() || story_id.len() > 64 {
                return Err(MessageError::EmptyField("story_id".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::PinMessage { target_id, ts, .. } => {
            if target_id.is_empty() || target_id.len() > 128 {
                return Err(MessageError::EmptyField("target_id".to_string()));
            }
            validate_rfc3339(ts)?;
        }
        AlphaMessage::ChatDeleted { ts } => {
            validate_rfc3339(ts)?;
        }
        AlphaMessage::GroupDisbanded { actor_pubkey, ts } => {
            if actor_pubkey.is_empty() {
                return Err(MessageError::EmptyField("actor_pubkey".to_string()));
            }
            validate_rfc3339(ts)?;
        }
                // Delivery-layer control messages. Validated structurally.
        AlphaMessage::DeliveryAck { chat_id, sender_id, upto_seq: _ } => {
            if chat_id.is_empty() {
                return Err(MessageError::EmptyField("chat_id".to_string()));
            }
            if sender_id.is_empty() {
                return Err(MessageError::EmptyField("sender_id".to_string()));
            }
        }
        AlphaMessage::PollVote { poll_id, options, ts } => {
            validate_rfc3339(ts)?;
            if poll_id.is_empty() || options.len() > 10 {
                return Err(MessageError::Validation("bad poll vote".to_string()));
            }
        }
        AlphaMessage::PollClose { poll_id, tally, ts } => {
            validate_rfc3339(ts)?;
            if poll_id.is_empty() || tally.len() > 10 {
                return Err(MessageError::Validation("bad poll close".to_string()));
            }
        }
        AlphaMessage::WhisperBurned { target_id, used_secs, .. } => {
            validate_id(target_id)?;
            if *used_secs > crate::MAX_WHISPER_SECS {
                return Err(MessageError::Validation(format!("whisper read for {}s (max {})", used_secs, crate::MAX_WHISPER_SECS)));
            }
        }
        AlphaMessage::ResendRequest { chat_id, sender_id, from_seq, to_seq } => {
            if chat_id.is_empty() {
                return Err(MessageError::EmptyField("chat_id".to_string()));
            }
            if sender_id.is_empty() {
                return Err(MessageError::EmptyField("sender_id".to_string()));
            }
            let (f, t) = (*from_seq, *to_seq);
            if f == 0 || t < f {
                return Err(MessageError::Validation(format!(
                    "invalid resend range: from_seq={} to_seq={} (need from_seq>=1, to_seq>=from_seq)",
                    f, t
                )));
            }
            const MAX_RESEND_RANGE: u64 = 500;
            if t - f + 1 > MAX_RESEND_RANGE {
                return Err(MessageError::Validation(format!(
                    "resend range too large: {} seqs (max {})",
                    t - f + 1,
                    MAX_RESEND_RANGE
                )));
            }
        }
    }
    Ok(())
}

/// Validate an [`ExpiryPolicy`].
pub fn validate_expiry_policy(policy: &ExpiryPolicy) -> Result<()> {
    expiry::validate_expiry_policy(policy).map_err(|e| {
        match e {
            expiry::ExpiryPolicyError::InvalidTtl { ttl_secs, min, max } => {
                MessageError::InvalidTtl {
                    ttl_seconds: ttl_secs,
                    min,
                    max,
                }
            }
        }
    })
}

/// Validate that a string is a valid UUID.
pub fn validate_id(id: &str) -> Result<()> {
    if id.is_empty() {
        return Err(MessageError::InvalidUuid("ID cannot be empty".to_string()));
    }
    if uuid::Uuid::parse_str(id).is_err() {
        return Err(MessageError::InvalidUuid(format!(
            "'{}' is not a valid UUID",
            id
        )));
    }
    Ok(())
}

/// Validate that the body is non-empty and within length limits.
fn validate_body(body: &str) -> Result<()> {
    if body.is_empty() {
        return Err(MessageError::EmptyBody);
    }
    if body.len() > MAX_BODY_LENGTH {
        return Err(MessageError::BodyTooLong {
            max: MAX_BODY_LENGTH,
            actual: body.len(),
        });
    }
    Ok(())
}

/// Validate that the emoji is within length limits.
fn validate_emoji(emoji: &str) -> Result<()> {
    if emoji.is_empty() {
        return Err(MessageError::InvalidEmoji("emoji cannot be empty".to_string()));
    }
    // Count Unicode characters, not bytes
    let char_count = emoji.chars().count();
    if char_count > MAX_EMOJI_LENGTH {
        return Err(MessageError::InvalidEmoji(format!(
            "emoji too long: {} chars (max {})",
            char_count, MAX_EMOJI_LENGTH
        )));
    }
    Ok(())
}

/// Validate TTL is within the allowed range (5s to 24h).
fn validate_ttl(ttl_seconds: u32) -> Result<()> {
    if ttl_seconds < MIN_TTL_SECONDS || ttl_seconds > MAX_TTL_SECONDS {
        return Err(MessageError::InvalidTtl {
            ttl_seconds,
            min: MIN_TTL_SECONDS,
            max: MAX_TTL_SECONDS,
        });
    }
    Ok(())
}

/// Validate that the media reference is non-empty.
fn validate_media_ref(media_ref: &str) -> Result<()> {
    if media_ref.is_empty() {
        return Err(MessageError::EmptyField("media_ref".to_string()));
    }
    Ok(())
}

/// Validate that the media type is a recognized value.
fn validate_media_type(media_type: &str) -> Result<()> {
    match media_type {
        // "voice" = a recorded voice note (rendered like audio, labelled distinctly).
        "image" | "video" | "audio" | "file" | "voice" => Ok(()),
        _ => Err(MessageError::Validation(format!(
            "invalid media_type: '{}' (must be image, video, audio, file or voice)",
            media_type
        ))),
    }
}

/// Validate that the filename is non-empty.
fn validate_filename(filename: &str) -> Result<()> {
    if filename.is_empty() {
        return Err(MessageError::EmptyField("filename".to_string()));
    }
    Ok(())
}

/// Validate that size_bytes is positive.
fn validate_size_bytes(size_bytes: u64) -> Result<()> {
    if size_bytes == 0 {
        return Err(MessageError::InvalidSize(
            "size_bytes must be greater than 0".to_string(),
        ));
    }
    Ok(())
}

/// Validate that a string is a valid RFC3339 timestamp.
fn validate_rfc3339(timestamp: &str) -> Result<()> {
    if chrono::DateTime::parse_from_rfc3339(timestamp).is_err() {
        return Err(MessageError::Validation(format!(
            "invalid RFC3339 timestamp: '{}'",
            timestamp
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::builder::MessageBuilder;

    fn valid_uuid() -> String {
        uuid::Uuid::new_v4().to_string()
    }

    #[test]
    fn test_validate_valid_text() {
        let msg = MessageBuilder::text("Hello").build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_text_with_reply_to() {
        let reply_id = valid_uuid();
        let msg = MessageBuilder::text("Hello").reply_to(&reply_id).build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_text_with_after_send() {
        let msg = MessageBuilder::text("Hello").after_send(60).build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_text_with_after_read() {
        let msg = MessageBuilder::text("Hello").after_read(30).build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_text_with_view_once() {
        let msg = MessageBuilder::text("Hello").view_once().build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_text_invalid_expiry_ttl() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "Hello".to_string(),
            reply_to: None,
            expiry: Some(ExpiryPolicy::AfterSend { ttl_secs: 1 }),
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidTtl { .. })));
    }

    #[test]
    fn test_validate_empty_body() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "".to_string(),
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::EmptyBody)));
    }

    #[test]
    fn test_validate_body_too_long() {
        let long_body = "a".repeat(32_001);
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: long_body,
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::BodyTooLong { .. })));
    }

    #[test]
    fn test_validate_body_at_max_length() {
        let body = "a".repeat(32_000);
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body,
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_invalid_uuid() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: "not-a-uuid".to_string(),
            body: "Hello".to_string(),
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidUuid(_))));
    }

    #[test]
    fn test_validate_invalid_reply_to_uuid() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "Hello".to_string(),
            reply_to: Some("not-a-uuid".to_string()),
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidUuid(_))));
    }

    #[test]
    fn test_validate_valid_edit() {
        let id = valid_uuid();
        let msg = MessageBuilder::edit(&id, "New text")
            .edited_at("2025-01-15T12:00:00Z")
            .build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_edit_invalid_timestamp() {
        let msg = AlphaMessage::Edit {
            id: valid_uuid(),
            body: "New text".to_string(),
            edited_at: "not-a-timestamp".to_string(),
            seq: 0,
        };
        assert!(matches!(validate(&msg), Err(MessageError::Validation(_))));
    }

    #[test]
    fn test_validate_reaction() {
        let target_id = valid_uuid();
        let msg = MessageBuilder::reaction(&target_id, "👍").build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_reaction_emoji_too_long() {
        let msg = AlphaMessage::Reaction {
            id: valid_uuid(),
            target_id: valid_uuid(),
            emoji: "abcdefghijklm".to_string(),
            remove: None,
            seq: 0,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidEmoji(_))));
    }

    #[test]
    fn test_validate_reaction_empty_emoji() {
        let msg = AlphaMessage::Reaction {
            id: valid_uuid(),
            target_id: valid_uuid(),
            emoji: "".to_string(),
            remove: None,
            seq: 0,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidEmoji(_))));
    }

    #[test]
    fn test_validate_reply() {
        let reply_to = valid_uuid();
        let msg = MessageBuilder::reply(&reply_to, "My reply").build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_delete() {
        let target_id = valid_uuid();
        let msg = MessageBuilder::delete(&target_id).build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_disappearing() {
        let msg = MessageBuilder::disappearing("Secret", 60).build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_disappearing_ttl_too_low() {
        let msg = AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "Secret".to_string(),
            ttl_seconds: 1,
            seq: 0,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidTtl { .. })));
    }

    #[test]
    fn test_validate_disappearing_ttl_too_high() {
        let msg = AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "Secret".to_string(),
            ttl_seconds: 100_000,
            seq: 0,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidTtl { .. })));
    }

    #[test]
    fn test_validate_disappearing_ttl_at_boundaries() {
        let msg_min = AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "Secret".to_string(),
            ttl_seconds: 5,
            seq: 0,
        };
        assert!(validate(&msg_min).is_ok());

        let msg_max = AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "Secret".to_string(),
            ttl_seconds: 86_400,
            seq: 0,
        };
        assert!(validate(&msg_max).is_ok());

        let msg_below = AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "Secret".to_string(),
            ttl_seconds: 4,
            seq: 0,
        };
        assert!(validate(&msg_below).is_err());

        let msg_above = AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: valid_uuid(),
            body: "Secret".to_string(),
            ttl_seconds: 86_401,
            seq: 0,
        };
        assert!(validate(&msg_above).is_err());
    }

    #[test]
    fn test_validate_media() {
        let msg = MessageBuilder::media("sha256:abc", "image", "photo.png", 1024).build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_media_with_view_once() {
        let msg = MessageBuilder::media("sha256:abc", "image", "photo.png", 1024)
            .view_once()
            .build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_media_all_types() {
        for mt in &["image", "video", "audio", "file"] {
            let msg = MessageBuilder::media("ref", mt, "file.ext", 100).build();
            assert!(validate(&msg).is_ok(), "media_type '{}' should be valid", mt);
        }
    }

    #[test]
    fn test_validate_media_invalid_type() {
        let msg = AlphaMessage::Media { sent_ms: 0, silent: false,
            id: valid_uuid(),
            media_ref: "ref".to_string(),
            media_type: "invalid".to_string(),
            filename: "file.ext".to_string(),
            size_bytes: 100,
            caption: None,
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::Validation(_))));
    }

    #[test]
    fn test_validate_media_empty_ref() {
        let msg = AlphaMessage::Media { sent_ms: 0, silent: false,
            id: valid_uuid(),
            media_ref: "".to_string(),
            media_type: "image".to_string(),
            filename: "photo.png".to_string(),
            size_bytes: 100,
            caption: None,
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::EmptyField(_))));
    }

    #[test]
    fn test_validate_media_empty_filename() {
        let msg = AlphaMessage::Media { sent_ms: 0, silent: false,
            id: valid_uuid(),
            media_ref: "ref".to_string(),
            media_type: "image".to_string(),
            filename: "".to_string(),
            size_bytes: 100,
            caption: None,
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::EmptyField(_))));
    }

    #[test]
    fn test_validate_media_zero_size() {
        let msg = AlphaMessage::Media { sent_ms: 0, silent: false,
            id: valid_uuid(),
            media_ref: "ref".to_string(),
            media_type: "image".to_string(),
            filename: "photo.png".to_string(),
            size_bytes: 0,
            caption: None,
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidSize(_))));
    }

    #[test]
    fn test_validate_read_signal() {
        let msg = MessageBuilder::read_signal(&valid_uuid(), "chat-123").build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_read_signal_invalid_target_id() {
        let msg = AlphaMessage::ReadSignal {
            target_id: "not-a-uuid".to_string(),
            chat_id: "chat-123".to_string(),
            read_at: "2025-01-15T12:00:00Z".to_string(),
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidUuid(_))));
    }

    #[test]
    fn test_validate_read_signal_empty_chat_id() {
        let msg = AlphaMessage::ReadSignal {
            target_id: valid_uuid(),
            chat_id: "".to_string(),
            read_at: "2025-01-15T12:00:00Z".to_string(),
        };
        assert!(matches!(validate(&msg), Err(MessageError::EmptyField(_))));
    }

    #[test]
    fn test_validate_read_signal_invalid_timestamp() {
        let msg = AlphaMessage::ReadSignal {
            target_id: valid_uuid(),
            chat_id: "chat-123".to_string(),
            read_at: "not-a-timestamp".to_string(),
        };
        assert!(matches!(validate(&msg), Err(MessageError::Validation(_))));
    }

    #[test]
    fn test_validate_id_valid() {
        assert!(validate_id(&valid_uuid()).is_ok());
        assert!(validate_id("550e8400-e29b-41d4-a716-446655440000").is_ok());
    }

    #[test]
    fn test_validate_id_empty() {
        assert!(matches!(validate_id(""), Err(MessageError::InvalidUuid(_))));
    }

    #[test]
    fn test_validate_id_invalid_format() {
        assert!(matches!(
            validate_id("not-a-uuid"),
            Err(MessageError::InvalidUuid(_))
        ));
    }

    #[test]
    fn test_validate_media_with_reply_to() {
        let reply_id = valid_uuid();
        let msg = MessageBuilder::media("ref", "image", "photo.png", 1024)
            .reply_to(&reply_id)
            .build();
        assert!(validate(&msg).is_ok());
    }

    #[test]
    fn test_validate_media_invalid_reply_to() {
        let msg = AlphaMessage::Media { sent_ms: 0, silent: false,
            id: valid_uuid(),
            media_ref: "ref".to_string(),
            media_type: "image".to_string(),
            filename: "photo.png".to_string(),
            size_bytes: 100,
            caption: None,
            reply_to: Some("not-a-uuid".to_string()),
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert!(matches!(validate(&msg), Err(MessageError::InvalidUuid(_))));
    }
}

#[cfg(test)]
mod inbound_tests {
    use super::*;

    #[test]
    fn inbound_limits_pass_real_traffic_and_stop_floods() {
        let text = |body: String| AlphaMessage::Text {
            id: "a".into(), body, reply_to: None, expiry: None, seq: 0, sent_ms: 0, silent: false, thread: None,
        };
        assert!(validate_inbound(&text("x".repeat(30_000))).is_ok(), "our longest piece of text");
        assert!(validate_inbound(&text("x".repeat(70_000))).is_err());
        let call = AlphaMessage::ChatState { chat_id: "c".into(), state: format!("call:{}", "v".repeat(12_000)), ts: 0, thread: None };
        assert!(validate_inbound(&call).is_ok(), "a call offer with its SDP");
        let seen = AlphaMessage::ChannelSeen { post_ids: vec!["p".into(); 6_000], forwarded: vec![], ts: String::new() };
        assert!(validate_inbound(&seen).is_err());
    }
}
