//! The assistant: a chat with an AI service the user configured, kept on this
//! phone only. It is a local chat like Saved Messages: nothing in it is
//! published, uploaded or synced; the app talks to the service itself.
//!
//! Its id starts with [`ASSISTANT_CHAT_PREFIX`]. Chats that come from the
//! network are keyed by a hex MLS group id, which can never contain ':' — so
//! no peer, group or channel can produce a chat the app shows as the assistant.

use super::*;

pub const ASSISTANT_CHAT_PREFIX: &str = "assistant:";
/// The sender of the assistant's replies (one contact row for every profile).
pub const ASSISTANT_SENDER_ID: &str = "assistant";

/// A chat that lives on this phone only: never published, uploaded or synced.
pub fn is_local_chat(chat_id: &str) -> bool {
    chat_id == SAVED_MESSAGES_CHAT_ID || is_assistant_chat(chat_id)
}

pub fn is_assistant_chat(chat_id: &str) -> bool {
    chat_id.starts_with(ASSISTANT_CHAT_PREFIX)
}

impl AlphaMessengerCore {
    /// The assistant chat of one profile, created on first use.
    pub fn ensure_assistant_chat(&self, profile_id: String, name: String) -> Result<ChatInfo, MessengerError> {
        let profile = profile_id.trim();
        if profile.is_empty() || !profile.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
            return Err(MessengerError::InvalidArgument { description: "bad assistant profile id".into() });
        }
        let chat_id = format!("{ASSISTANT_CHAT_PREFIX}{profile}");
        let name = name.trim().chars().take(64).collect::<String>();
        let conn = self.db.connection();
        self.ensure_assistant_contact(&conn);
        if let Ok(Some(c)) = ChatRepo::get(&conn, &chat_id) {
            if !name.is_empty() && c.name.as_deref() != Some(name.as_str()) {
                let _ = ChatRepo::update(&conn, &chat_id, Some(&name), None);
            }
            return Ok(chat_info_basic(c.id, "direct".to_string(), Some(name).filter(|n| !n.is_empty()).or(c.name), 0, c.created_at));
        }
        let chat = new_chat(chat_id.clone(), alpha_database::models::ChatType::Direct, Some(name).filter(|n| !n.is_empty()), 0);
        ChatRepo::insert(&conn, &chat)
            .map_err(|e| MessengerError::DatabaseError { description: format!("create assistant chat: {e}") })?;
        Ok(chat_info_basic(chat.id, "direct".to_string(), chat.name, 0, chat.created_at))
    }

    /// Store a reply of the assistant in its chat; returns the message id.
    /// [id]: given up front, so the answer on screen keeps one identity from its first word to its stored row.
    pub fn insert_assistant_message(&self, chat_id: String, body: String, id: Option<String>) -> Result<String, MessengerError> {
        if !is_assistant_chat(&chat_id) {
            return Err(MessengerError::InvalidArgument { description: "not an assistant chat".into() });
        }
        if let Some(id) = id.as_deref() {
            if uuid::Uuid::parse_str(id).is_err() {
                return Err(MessengerError::InvalidArgument { description: "bad message id".into() });
            }
        }
        {
            let conn = self.db.connection();
            self.ensure_assistant_contact(&conn);
        }
        let builder = MessageBuilder::text(&body);
        let msg = match id.as_deref() { Some(id) => builder.id(id), None => builder }.build();
        let id = msg.id().to_string();
        self.persist_alpha_message(&chat_id, &msg, ASSISTANT_SENDER_ID)?;
        Ok(id)
    }

    /// A picture or a voice the assistant made, stored in its chat from a file on
    /// this phone (it never leaves it). Returns the message id.
    pub fn insert_assistant_media(
        &self,
        chat_id: String,
        file_path: String,
        media_type: String,
        caption: Option<String>,
        meta_json: Option<String>,
    ) -> Result<String, MessengerError> {
        if !is_assistant_chat(&chat_id) {
            return Err(MessengerError::InvalidArgument { description: "not an assistant chat".into() });
        }
        if !matches!(media_type.as_str(), "image" | "video" | "audio") {
            return Err(MessengerError::InvalidArgument { description: "bad media type".into() });
        }
        {
            let conn = self.db.connection();
            self.ensure_assistant_contact(&conn);
        }
        let filename = std::path::Path::new(&file_path).file_name()
            .map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "file".into());
        let size = std::fs::metadata(&file_path).map(|m| m.len()).unwrap_or(0);
        let mut v = serde_json::json!({
            "local_only": true,
            "media_type": media_type,
            "filename": filename,
            "original_size": size,
            "local_path": file_path,
        });
        merge_display_meta(&mut v, &meta_json);
        let mut builder = MessageBuilder::media(&v.to_string(), &media_type, &filename, size);
        if let Some(c) = caption.as_ref().filter(|c| !c.is_empty()) { builder = builder.caption(c); }
        let msg = builder.build();
        let id = msg.id().to_string();
        self.persist_alpha_message(&chat_id, &msg, ASSISTANT_SENDER_ID)?;
        Ok(id)
    }

    fn ensure_assistant_contact(&self, conn: &rusqlite::Connection) {
        if matches!(alpha_database::ContactRepo::get(conn, ASSISTANT_SENDER_ID), Ok(Some(_))) { return; }
        // No key: it is not a nostr identity and never receives anything.
        let c = alpha_database::repositories::new_contact(ASSISTANT_SENDER_ID.into(), Some("Assistant".into()), vec![0; 32], None);
        let _ = alpha_database::ContactRepo::insert(conn, &c);
    }

    /// The message an upload belongs to lives in a local chat: the file stays on
    /// this phone and its ref points at it. `op_id` is a message id, or
    /// `<message id>#<item>` for an album item.
    pub(super) fn local_upload_ref(
        &self,
        op_id: &str,
        file_path: &str,
        media_type: &str,
        poster_b64: &Option<String>,
        meta_json: &Option<String>,
    ) -> Option<String> {
        let msg_id = op_id.split('#').next().unwrap_or(op_id);
        let chat = {
            let conn = self.db.connection();
            MessageRepo::get(&conn, msg_id).ok().flatten()?.chat_id
        };
        if !is_assistant_chat(&chat) { return None; }
        let filename = std::path::Path::new(file_path).file_name()
            .map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "file".into());
        let size = std::fs::metadata(file_path).map(|m| m.len()).unwrap_or(0);
        let mut v = serde_json::json!({
            "local_only": true,
            "media_type": media_type,
            "filename": filename,
            "original_size": size,
            "local_path": file_path,
        });
        if let (Some(p), serde_json::Value::Object(m)) = (poster_b64.as_ref().filter(|p| !p.is_empty()), &mut v) {
            m.insert("poster_b64".into(), serde_json::Value::String(p.clone()));
        }
        merge_display_meta(&mut v, meta_json);
        Some(v.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_our_prefix_is_an_assistant() {
        assert!(is_assistant_chat("assistant:main"));
        assert!(is_local_chat("assistant:main"));
        assert!(is_local_chat(SAVED_MESSAGES_CHAT_ID));
        // A network chat id is hex: it can never take the prefix.
        assert!(!is_assistant_chat(&hex::encode(b"assistant:main")));
        assert!(!is_local_chat("ab12cd"));
    }
}
