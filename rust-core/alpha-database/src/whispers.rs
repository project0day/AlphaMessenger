//! Whispers: read only under the finger, within a time limit, then burned.
//!
//! A whisper is an ordinary row of `messages` marked one-view, with no content
//! and no media ref; this table holds what the row means. For an INCOMING
//! whisper it also holds the secret itself (text or media ref) — inside the
//! encrypted database, never in `messages`, so previews, search and exports
//! never meet it — until it is burned. Burning wipes the secret and keeps a
//! tombstone (who, how long it was read, a screenshot attempt).

use rusqlite::{params, Connection, OptionalExtension};

use crate::error::Result;

/// Key prefix of a sent message's blobs waiting to leave the storage servers.
pub const MEDIA_PURGE_PREFIX: &str = "media_purge:";

/// Unopened whispers burn after this long.
pub const OPEN_WITHIN_SECS: i64 = 24 * 60 * 60;

/// What the screen needs to know about a whisper (never the secret).
#[derive(Debug, Clone, serde::Serialize)]
pub struct WhisperMeta {
    pub message_id: String,
    pub outgoing: bool,
    pub kind: String,
    pub secs: u32,
    pub burned: bool,
    pub used_secs: Option<u32>,
    pub shot: bool,
    pub expired: bool,
    /// A timed view (the whole media for `secs`, 0 = until closed).
    pub glance: bool,
}

/// The secret of an incoming whisper, while it is still there.
#[derive(Debug, Clone, serde::Serialize)]
pub struct WhisperSecret {
    pub kind: String,
    pub body: Option<String>,
    pub media_ref: Option<String>,
    pub secs: u32,
    pub glance: bool,
}

/// Record an incoming whisper with its secret. Idempotent.
pub fn put_incoming(
    conn: &Connection, message_id: &str, chat_id: &str, wire_id: &str,
    kind: &str, body: Option<&str>, media_ref: Option<&str>, secs: u32, glance: bool, created_at: &str,
) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO whispers (message_id, chat_id, wire_id, outgoing, kind, body, media_ref, secs, glance, created_at)
         VALUES (?1, ?2, ?3, 0, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![message_id, chat_id, wire_id, kind, body, media_ref, secs, glance as i64, created_at],
    )?;
    Ok(())
}

/// Record that our own message `message_id` is a whisper (we keep no secret). Idempotent.
pub fn put_outgoing(conn: &Connection, message_id: &str, chat_id: &str, kind: &str, secs: u32, glance: bool, created_at: &str) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO whispers (message_id, chat_id, wire_id, outgoing, kind, secs, glance, created_at)
         VALUES (?1, ?2, ?1, 1, ?3, ?4, ?5, ?6)",
        params![message_id, chat_id, kind, secs, glance as i64, created_at],
    )?;
    Ok(())
}

/// Whether message `message_id` (ours or theirs) is a whisper or a timed view.
pub fn is_whisper(conn: &Connection, message_id: &str) -> bool {
    conn.query_row("SELECT 1 FROM whispers WHERE message_id = ?1", params![message_id], |_| Ok(()))
        .optional()
        .ok()
        .flatten()
        .is_some()
}

/// The media kind of a timed view ("image" / "video"); `None` = not one.
pub fn glance_kind(conn: &Connection, message_id: &str) -> Option<String> {
    conn.query_row(
        "SELECT kind FROM whispers WHERE message_id = ?1 AND glance = 1",
        params![message_id],
        |r| r.get::<_, String>(0),
    )
    .optional()
    .ok()
    .flatten()
}

/// Our own whisper's (seconds, timed view) — `None` = not a whisper.
pub fn outgoing_mode(conn: &Connection, message_id: &str) -> Option<(u32, bool)> {
    conn.query_row(
        "SELECT secs, glance FROM whispers WHERE message_id = ?1 AND outgoing = 1",
        params![message_id],
        |r| Ok((r.get::<_, u32>(0)?, r.get::<_, i64>(1)? != 0)),
    )
    .optional()
    .ok()
    .flatten()
}

/// The secret of an incoming whisper that has not burned yet.
pub fn secret(conn: &Connection, message_id: &str) -> Option<WhisperSecret> {
    conn.query_row(
        "SELECT kind, body, media_ref, secs, glance FROM whispers
         WHERE message_id = ?1 AND outgoing = 0 AND burned_at IS NULL",
        params![message_id],
        |r| Ok(WhisperSecret {
            kind: r.get(0)?, body: r.get(1)?, media_ref: r.get(2)?, secs: r.get(3)?, glance: r.get::<_, i64>(4)? != 0,
        }),
    )
    .optional()
    .ok()
    .flatten()
}

/// Burn an incoming whisper: wipe the secret, keep the tombstone.
/// Returns (chat_id, wire_id) to tell the sender, or None if it was already burned.
pub fn burn_incoming(conn: &Connection, message_id: &str, used_secs: u32, shot: bool, at: &str) -> Result<Option<(String, String)>> {
    let row = conn
        .query_row(
            "SELECT chat_id, wire_id FROM whispers WHERE message_id = ?1 AND outgoing = 0 AND burned_at IS NULL",
            params![message_id],
            |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)),
        )
        .optional()?;
    if row.is_none() {
        return Ok(None);
    }
    conn.execute(
        "UPDATE whispers SET body = NULL, media_ref = NULL, burned_at = ?2, used_secs = ?3, shot = ?4
         WHERE message_id = ?1",
        params![message_id, at, used_secs, shot as i64],
    )?;
    conn.execute("UPDATE messages SET consumed = 1, content = NULL, media_ref = NULL WHERE id = ?1", params![message_id])?;
    Ok(row)
}

/// The recipient burned our whisper `wire_id` in `chat_id`.
pub fn burned_by_peer(conn: &Connection, chat_id: &str, wire_id: &str, used_secs: u32, shot: bool, expired: bool, at: &str) -> Result<bool> {
    let n = conn.execute(
        "UPDATE whispers SET burned_at = COALESCE(burned_at, ?3), used_secs = ?4, shot = MAX(shot, ?5), expired = ?6
         WHERE chat_id = ?1 AND message_id = ?2 AND outgoing = 1",
        params![chat_id, wire_id, at, used_secs, shot as i64, expired as i64],
    )?;
    if n > 0 {
        conn.execute("UPDATE messages SET consumed = 1 WHERE id = ?1", params![wire_id])?;
        // Seen and burned there: its file leaves the storage servers now.
        let key = format!("{MEDIA_PURGE_PREFIX}{wire_id}");
        if let Some(mut v) = crate::KeyValueRepo::get(conn, &key).ok().flatten()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok())
        {
            v["due"] = serde_json::json!(chrono::Utc::now().timestamp());
            let _ = crate::KeyValueRepo::set(conn, &key, &v.to_string());
        }
    }
    Ok(n > 0)
}

/// Incoming whispers nobody opened in time burn unread. Returns (message_id, chat_id, wire_id).
pub fn expire_unopened(conn: &Connection, now: &chrono::DateTime<chrono::Utc>) -> Result<Vec<(String, String, String)>> {
    let cut = (*now - chrono::Duration::seconds(OPEN_WITHIN_SECS)).to_rfc3339();
    let mut stmt = conn.prepare(
        "SELECT message_id, chat_id, wire_id FROM whispers
         WHERE outgoing = 0 AND burned_at IS NULL AND created_at <= ?1",
    )?;
    let rows: Vec<(String, String, String)> = stmt
        .query_map(params![cut], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
        .filter_map(|r| r.ok())
        .collect();
    let at = now.to_rfc3339();
    for (id, _, _) in &rows {
        conn.execute(
            "UPDATE whispers SET body = NULL, media_ref = NULL, burned_at = ?2, used_secs = 0, expired = 1 WHERE message_id = ?1",
            params![id, at],
        )?;
        conn.execute("UPDATE messages SET consumed = 1, content = NULL, media_ref = NULL WHERE id = ?1", params![id])?;
    }
    Ok(rows)
}

/// What the screen shows for the whispers of a chat (no secrets).
pub fn list_for_chat(conn: &Connection, chat_id: &str) -> Vec<WhisperMeta> {
    let Ok(mut stmt) = conn.prepare(
        "SELECT message_id, outgoing, kind, secs, burned_at, used_secs, shot, expired, glance FROM whispers WHERE chat_id = ?1",
    ) else {
        return Vec::new();
    };
    stmt.query_map(params![chat_id], |r| {
        Ok(WhisperMeta {
            message_id: r.get(0)?,
            outgoing: r.get::<_, i64>(1)? != 0,
            kind: r.get(2)?,
            secs: r.get(3)?,
            burned: r.get::<_, Option<String>>(4)?.is_some(),
            used_secs: r.get(5)?,
            shot: r.get::<_, i64>(6)? != 0,
            expired: r.get::<_, i64>(7)? != 0,
            glance: r.get::<_, i64>(8)? != 0,
        })
    })
    .map(|it| it.filter_map(|r| r.ok()).collect())
    .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DatabasePool;

    fn db() -> (DatabasePool, std::path::PathBuf) {
        let path = std::env::temp_dir().join(format!("whisper-{}.db", uuid::Uuid::new_v4()));
        (DatabasePool::open(&path, "k").unwrap(), path)
    }

    #[test]
    fn a_whisper_burns_once_and_keeps_only_a_tombstone() {
        let (pool, path) = db();
        let conn = pool.connection();
        let now = chrono::Utc::now().to_rfc3339();
        put_incoming(&conn, "m1", "c1", "w1", "text", Some("4815"), None, 15, false, &now).unwrap();
        // A second delivery of the same message changes nothing.
        put_incoming(&conn, "m1", "c1", "w1", "text", Some("other"), None, 15, false, &now).unwrap();
        assert_eq!(secret(&conn, "m1").unwrap().body.as_deref(), Some("4815"));
        assert_eq!(burn_incoming(&conn, "m1", 9, true, &now).unwrap(), Some(("c1".into(), "w1".into())));
        assert!(secret(&conn, "m1").is_none(), "the secret is gone");
        assert_eq!(burn_incoming(&conn, "m1", 9, true, &now).unwrap(), None, "burns once");
        let meta = list_for_chat(&conn, "c1");
        assert!(meta[0].burned && meta[0].shot && meta[0].used_secs == Some(9));
        drop(conn); drop(pool); let _ = std::fs::remove_file(path);
    }

    #[test]
    fn our_whisper_learns_it_was_read() {
        let (pool, path) = db();
        let conn = pool.connection();
        let now = chrono::Utc::now().to_rfc3339();
        put_outgoing(&conn, "w1", "c1", "image", 30, false, &now).unwrap();
        assert_eq!(outgoing_mode(&conn, "w1"), Some((30, false)));
        assert!(secret(&conn, "w1").is_none(), "we keep no secret of our own");
        assert!(burned_by_peer(&conn, "c1", "w1", 12, false, false, &now).unwrap());
        assert!(!burned_by_peer(&conn, "other", "w1", 12, false, false, &now).unwrap(), "only the chat it was sent to");
        let meta = list_for_chat(&conn, "c1");
        assert!(meta[0].outgoing && meta[0].burned && meta[0].used_secs == Some(12));
        drop(conn); drop(pool); let _ = std::fs::remove_file(path);
    }

    #[test]
    fn unopened_whispers_burn_after_a_day() {
        let (pool, path) = db();
        let conn = pool.connection();
        let old = (chrono::Utc::now() - chrono::Duration::hours(25)).to_rfc3339();
        let fresh = chrono::Utc::now().to_rfc3339();
        put_incoming(&conn, "old", "c1", "w-old", "text", Some("x"), None, 15, false, &old).unwrap();
        put_incoming(&conn, "new", "c1", "w-new", "text", Some("y"), None, 15, false, &fresh).unwrap();
        let gone = expire_unopened(&conn, &chrono::Utc::now()).unwrap();
        assert_eq!(gone.len(), 1);
        assert!(secret(&conn, "old").is_none() && secret(&conn, "new").is_some());
        drop(conn); drop(pool); let _ = std::fs::remove_file(path);
    }

    #[test]
    fn a_timed_view_is_kept_as_one_and_its_file_leaves_once_seen() {
        let (pool, path) = db();
        let conn = pool.connection();
        let now = chrono::Utc::now().to_rfc3339();
        put_outgoing(&conn, "g1", "c1", "image", 5, true, &now).unwrap();
        assert_eq!(outgoing_mode(&conn, "g1"), Some((5, true)));
        put_incoming(&conn, "g2", "c1", "w2", "video", None, Some("{}"), 0, true, &now).unwrap();
        assert!(secret(&conn, "g2").unwrap().glance);
        assert!(list_for_chat(&conn, "c1").iter().all(|m| m.glance));
        assert!(is_whisper(&conn, "g2") && !is_whisper(&conn, "nope"));

        let later = chrono::Utc::now().timestamp() + 3600;
        let key = format!("{MEDIA_PURGE_PREFIX}g1");
        crate::KeyValueRepo::set(&conn, &key, &serde_json::json!({ "ids": ["x"], "due": later }).to_string()).unwrap();
        assert!(burned_by_peer(&conn, "c1", "g1", 5, false, false, &now).unwrap());
        let v: serde_json::Value = serde_json::from_str(&crate::KeyValueRepo::get(&conn, &key).unwrap().unwrap()).unwrap();
        assert!(v["due"].as_i64().unwrap() <= chrono::Utc::now().timestamp(), "deleted from the servers right away");
        drop(conn); drop(pool); let _ = std::fs::remove_file(path);
    }
}
