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

/// Splits a profile's chat id from the id of a conversation put aside.
/// Profile ids are `[A-Za-z0-9_-]`, so the separator never occurs in one.
const CONVERSATION_SEP: char = '~';

/// A past conversation with an assistant: kept apart, hidden from the chat list.
pub fn is_assistant_conversation(chat_id: &str) -> bool {
    is_assistant_chat(chat_id) && chat_id.contains(CONVERSATION_SEP)
}

/// One past conversation with an assistant.
#[derive(Debug, Clone)]
pub struct AssistantConversation {
    pub chat_id: String,
    pub title: String,
    pub last_at: String,
    pub count: u32,
}

impl AlphaMessengerCore {
    /// Puts the current conversation of a profile aside; the profile's chat
    /// keeps its id (pins, folders, archive stay) and starts empty.
    /// False when there was nothing to put aside.
    pub fn assistant_new_conversation(&self, profile_id: String) -> Result<bool, MessengerError> {
        let base = Self::assistant_base(&profile_id)?;
        let conn = self.db.connection();
        let db = |e: rusqlite::Error| MessengerError::DatabaseError { description: format!("new conversation: {e}") };
        let tx = conn.unchecked_transaction().map_err(db)?;
        let moved = Self::stash_conversation(&tx, &base)?;
        tx.commit().map_err(db)?;
        Ok(moved)
    }

    /// Brings a past conversation back into the profile's chat; what was there
    /// is put aside first, so nothing is lost.
    pub fn assistant_open_conversation(&self, profile_id: String, chat_id: String) -> Result<(), MessengerError> {
        let base = Self::assistant_base(&profile_id)?;
        if !chat_id.starts_with(&format!("{base}{CONVERSATION_SEP}")) {
            return Err(MessengerError::InvalidArgument { description: "not a conversation of this assistant".into() });
        }
        let conn = self.db.connection();
        let db = |e: rusqlite::Error| MessengerError::DatabaseError { description: format!("open conversation: {e}") };
        let tx = conn.unchecked_transaction().map_err(db)?;
        Self::stash_conversation(&tx, &base)?;
        Self::move_messages(&tx, &chat_id, &base).map_err(db)?;
        tx.execute("DELETE FROM chats WHERE id = ?1", [&chat_id]).map_err(db)?;
        tx.commit().map_err(db)
    }

    /// The conversations a profile put aside, newest first.
    pub fn assistant_conversations(&self, profile_id: String) -> Result<Vec<AssistantConversation>, MessengerError> {
        let base = Self::assistant_base(&profile_id)?;
        let lead = format!("{base}{CONVERSATION_SEP}");
        let conn = self.db.connection();
        let db = |e: rusqlite::Error| MessengerError::DatabaseError { description: format!("list conversations: {e}") };
        let mut st = conn.prepare(
            "SELECT c.id, COALESCE(c.name, ''), \
                    COALESCE((SELECT MAX(created_at) FROM messages m WHERE m.chat_id = c.id), c.created_at), \
                    (SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id AND m.is_deleted = 0) \
             FROM chats c WHERE substr(c.id, 1, length(?1)) = ?1 ORDER BY 3 DESC",
        ).map_err(db)?;
        let rows = st.query_map([&lead], |r| Ok(AssistantConversation {
            chat_id: r.get(0)?, title: r.get(1)?, last_at: r.get(2)?, count: r.get::<_, i64>(3)? as u32,
        })).map_err(db)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(db)
    }

    fn assistant_base(profile_id: &str) -> Result<String, MessengerError> {
        let p = profile_id.trim();
        if p.is_empty() || !p.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
            return Err(MessengerError::InvalidArgument { description: "bad assistant profile id".into() });
        }
        Ok(format!("{ASSISTANT_CHAT_PREFIX}{p}"))
    }

    fn stash_conversation(conn: &rusqlite::Connection, base: &str) -> Result<bool, MessengerError> {
        let db = |e: rusqlite::Error| MessengerError::DatabaseError { description: format!("new conversation: {e}") };
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM messages WHERE chat_id = ?1 AND is_deleted = 0", [base], |r| r.get(0),
        ).map_err(db)?;
        if count == 0 { return Ok(false); }
        // Named by the user's first words, the way the list shows it later.
        let first: Option<String> = conn.query_row(
            "SELECT content FROM messages WHERE chat_id = ?1 AND sender_id <> ?2 AND is_deleted = 0 \
             AND content IS NOT NULL AND trim(content) <> '' ORDER BY created_at LIMIT 1",
            rusqlite::params![base, ASSISTANT_SENDER_ID], |r| r.get(0),
        ).ok();
        let title = first.map(|t| {
            let line = t.split_whitespace().collect::<Vec<_>>().join(" ");
            let short: String = line.chars().take(60).collect();
            if line.chars().count() > 60 { format!("{short}…") } else { short }
        });
        let id = format!("{base}{CONVERSATION_SEP}{}", uuid::Uuid::new_v4().simple());
        let chat = new_chat(id.clone(), alpha_database::models::ChatType::Direct, title, 0);
        ChatRepo::insert(conn, &chat).map_err(|e| MessengerError::DatabaseError { description: format!("new conversation: {e}") })?;
        Self::move_messages(conn, base, &id).map_err(db)?;
        Ok(true)
    }

    fn move_messages(conn: &rusqlite::Connection, from: &str, to: &str) -> rusqlite::Result<()> {
        for table in ["messages", "pending_sends", "pending_media"] {
            conn.execute(&format!("UPDATE {table} SET chat_id = ?2 WHERE chat_id = ?1"), [from, to])?;
        }
        Ok(())
    }

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
    /// [reply_to]: a message of this chat the answer quotes.
    pub fn insert_assistant_message(&self, chat_id: String, body: String, id: Option<String>, reply_to: Option<String>) -> Result<String, MessengerError> {
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
        let reply_to = reply_to.filter(|r| self.in_assistant_chat(&chat_id, r));
        let builder = MessageBuilder::text(&body);
        let builder = match id.as_deref() { Some(id) => builder.id(id), None => builder };
        let msg = match reply_to.as_deref() { Some(r) => builder.reply_to(r), None => builder }.build();
        let id = msg.id().to_string();
        self.persist_alpha_message(&chat_id, &msg, ASSISTANT_SENDER_ID)?;
        Ok(id)
    }

    /// The assistant reacts to a message of its chat; its one reaction there, replaced by a new one.
    pub fn insert_assistant_reaction(&self, chat_id: String, target_id: String, emoji: String) -> Result<(), MessengerError> {
        if !is_assistant_chat(&chat_id) || !self.in_assistant_chat(&chat_id, &target_id) {
            return Err(MessengerError::InvalidArgument { description: "no such message in this chat".into() });
        }
        let emoji = emoji.trim();
        if emoji.is_empty() || emoji.chars().count() > 8 || emoji.chars().any(|c| c.is_alphanumeric()) {
            return Err(MessengerError::InvalidArgument { description: "not an emoji".into() });
        }
        {
            let conn = self.db.connection();
            let _ = conn.execute(
                "DELETE FROM messages WHERE chat_id = ?1 AND sender_id = ?2 AND msg_type = 'reaction' AND reply_to = ?3",
                rusqlite::params![chat_id, ASSISTANT_SENDER_ID, target_id],
            );
        }
        let msg = MessageBuilder::reaction(&target_id, emoji).build();
        self.persist_alpha_message(&chat_id, &msg, ASSISTANT_SENDER_ID)
    }

    fn in_assistant_chat(&self, chat_id: &str, message_id: &str) -> bool {
        let conn = self.db.connection();
        matches!(MessageRepo::get(&conn, message_id), Ok(Some(m)) if m.chat_id == chat_id)
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
