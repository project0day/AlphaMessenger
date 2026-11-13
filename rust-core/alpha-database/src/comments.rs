//! Comment threads under channel posts.
//!
//! A comment is an ordinary row of `messages`; the `comments` table only links
//! it to the post it discusses. The feed of a channel leaves these rows out
//! ([`crate::MessageRepo::get_by_chat`]), a discussion shows exactly them
//! ([`crate::MessageRepo::get_thread`]).

use rusqlite::{params, Connection, OptionalExtension};

use crate::error::Result;

/// Record that message `message_id` (already stored in `chat_id`) is a comment
/// under `post_id`. Idempotent: the same message delivered twice links once.
pub fn link(conn: &Connection, message_id: &str, chat_id: &str, post_id: &str) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO comments (message_id, chat_id, post_id, created_at)
         SELECT id, chat_id, ?3, created_at FROM messages WHERE id = ?1 AND chat_id = ?2",
        params![message_id, chat_id, post_id],
    )?;
    Ok(())
}

/// The post a stored message comments on (`None` = not a comment).
pub fn post_of(conn: &Connection, message_id: &str) -> Option<String> {
    conn.query_row(
        "SELECT post_id FROM comments WHERE message_id = ?1",
        params![message_id],
        |r| r.get::<_, String>(0),
    )
    .optional()
    .ok()
    .flatten()
}

/// Replace the words of a stored message (used once to drop the header of a
/// comment written in the old text format).
pub fn set_content(conn: &Connection, message_id: &str, content: Option<&str>) -> Result<()> {
    conn.execute("UPDATE messages SET content = ?2 WHERE id = ?1", params![message_id, content])?;
    Ok(())
}

/// What the feed shows under a post.
#[derive(Debug, Clone, PartialEq)]
pub struct ThreadSummary {
    pub post_id: String,
    /// Comments that are not deleted.
    pub count: u32,
    /// Of them, our own (they are never "new" for us).
    pub own: u32,
    /// Time of the newest one (RFC3339).
    pub last_at: String,
    /// Up to three most recent distinct authors, newest first.
    pub recent_senders: Vec<String>,
}

/// One summary per post of `chat_id` that has at least one live comment.
pub fn summaries(conn: &Connection, chat_id: &str) -> Result<Vec<ThreadSummary>> {
    let mut stmt = conn.prepare(
        "SELECT c.post_id, m.sender_id, m.created_at
         FROM comments c JOIN messages m ON m.id = c.message_id
         WHERE c.chat_id = ?1 AND m.is_deleted = 0
         ORDER BY m.created_at DESC",
    )?;
    let rows = stmt.query_map(params![chat_id], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?))
    })?;
    let mut out: Vec<ThreadSummary> = Vec::new();
    let mut index = std::collections::HashMap::<String, usize>::new();
    for row in rows {
        let (post, sender, at) = row?;
        let i = *index.entry(post.clone()).or_insert_with(|| {
            out.push(ThreadSummary { post_id: post, count: 0, own: 0, last_at: at, recent_senders: Vec::new() });
            out.len() - 1
        });
        let s = &mut out[i];
        s.count += 1;
        if sender == "self" { s.own += 1; }
        if s.recent_senders.len() < 3 && !s.recent_senders.contains(&sender) {
            s.recent_senders.push(sender);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{new_chat, new_contact, new_message, ChatRepo, ChatType, ContactRepo, DatabasePool, MessageRepo, MessageType};
    use std::sync::atomic::{AtomicU32, Ordering};

    static CLOCK: AtomicU32 = AtomicU32::new(0);

    fn db() -> (DatabasePool, std::path::PathBuf) {
        let path = std::env::temp_dir().join(format!("cmt-{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&path, "k").unwrap();
        (pool, path)
    }

    fn put(conn: &Connection, chat: &str, sender: &str, kind: MessageType, content: &str, reply_to: Option<&str>) -> String {
        let mut m = new_message(chat.into(), sender.into(), kind, Some(content.into()), reply_to.map(Into::into), None, None, None);
        m.created_at = format!("2026-01-01T00:00:{:02}Z", CLOCK.fetch_add(1, Ordering::SeqCst));
        MessageRepo::insert(conn, &m).unwrap();
        m.id
    }

    #[test]
    fn feed_and_thread_split_the_chat() {
        let (pool, path) = db();
        let conn = pool.connection();
        let chat = new_chat("c1".into(), ChatType::Group, Some("c".into()), 0);
        ChatRepo::insert(&conn, &chat).unwrap();
        for who in ["self", "ann"] {
            let _ = ContactRepo::insert(&conn, &new_contact(who.into(), Some(who.into()), vec![0; 32], None));
        }
        let post = put(&conn, &chat.id, "self", MessageType::Text, "post", None);
        let cmt = put(&conn, &chat.id, "ann", MessageType::Text, "nice", None);
        link(&conn, &cmt, &chat.id, &post).unwrap();
        link(&conn, &cmt, &chat.id, &post).unwrap();
        let like = put(&conn, &chat.id, "self", MessageType::Reaction, "❤", Some(&cmt));
        let post_like = put(&conn, &chat.id, "ann", MessageType::Reaction, "👍", Some(&post));

        let feed: Vec<String> = MessageRepo::get_by_chat(&conn, &chat.id, 50, 0).unwrap().into_iter().map(|m| m.id).collect();
        assert_eq!(feed, vec![post.clone(), post_like]);
        let thread: Vec<String> = MessageRepo::get_thread(&conn, &chat.id, &post, 50, 0).unwrap().into_iter().map(|m| m.id).collect();
        assert_eq!(thread, vec![cmt.clone(), like]);

        assert_eq!(post_of(&conn, &cmt).as_deref(), Some(post.as_str()));
        let s = summaries(&conn, &chat.id).unwrap();
        assert_eq!(s.len(), 1);
        assert_eq!((s[0].count, s[0].recent_senders.clone()), (1, vec!["ann".to_string()]));

        // A comment is never the chat's latest message nor unread.
        assert_eq!(MessageRepo::latest_in_chat(&conn, &chat.id).unwrap().map(|m| m.id), Some(post));
        assert_eq!(MessageRepo::count_unread(&conn, &chat.id).unwrap(), 0);

        // Deleting the message drops its link.
        MessageRepo::delete(&conn, &cmt).unwrap();
        assert!(post_of(&conn, &cmt).is_none());
        drop(conn);
        drop(pool);
        let _ = std::fs::remove_file(path);
    }
}
