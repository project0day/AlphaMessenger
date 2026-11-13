//! Which received files belong to secret chats: their transport is decided
//! here, on this phone, never by the ref the sender wrote.

use rusqlite::{params, Connection};

/// Key prefix marking a secret chat (`<prefix><chat id>` -> its origin chat).
pub const SECRET_CHAT_PREFIX: &str = "secret_chat:";

/// Whether a file with content hash [hash] arrived in a secret chat (as a
/// message or as a whisper's secret).
pub fn received_in_secret_chat(conn: &Connection, hash: &str) -> bool {
    if hash.len() < 32 || !hash.bytes().all(|b| b.is_ascii_hexdigit()) {
        return false;
    }
    let like = format!("%{hash}%");
    conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM messages m JOIN key_value_store k ON k.key = ?2 || m.chat_id WHERE m.media_ref LIKE ?1)
             OR EXISTS(SELECT 1 FROM whispers w JOIN key_value_store k ON k.key = ?2 || w.chat_id WHERE w.media_ref LIKE ?1)",
        params![like, SECRET_CHAT_PREFIX],
        |r| r.get::<_, bool>(0),
    )
    .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{ChatType, MessageType};
    use crate::{new_chat, new_contact, new_message, ChatRepo, ContactRepo, DatabasePool, KeyValueRepo, MessageRepo};

    #[test]
    fn a_secret_chats_file_is_known_as_such_whatever_its_ref_says() {
        let path = std::env::temp_dir().join(format!("secret-media-{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&path, "k").unwrap();
        let conn = pool.connection();
        let secret_hash = "ab".repeat(32);
        let open_hash = "cd".repeat(32);
        ContactRepo::insert(&conn, &new_contact("peer".into(), None, vec![0; 32], None)).unwrap();
        for (chat, hash) in [("s1", &secret_hash), ("o1", &open_hash)] {
            ChatRepo::insert(&conn, &new_chat(chat.into(), ChatType::Direct, None, 0)).unwrap();
            let r = format!(r#"{{"hash":"{hash}","tor":false}}"#);
            MessageRepo::insert(&conn, &new_message(chat.into(), "peer".into(), MessageType::Media, None, None, Some(r), None, None)).unwrap();
        }
        KeyValueRepo::set(&conn, &format!("{SECRET_CHAT_PREFIX}s1"), "o1").unwrap();

        assert!(received_in_secret_chat(&conn, &secret_hash));
        assert!(!received_in_secret_chat(&conn, &open_hash));
        assert!(!received_in_secret_chat(&conn, "%"), "no pattern from outside");
        drop(conn);
        drop(pool);
        let _ = std::fs::remove_file(path);
    }
}
