//! Deletes and edits that arrived before the message they name. Relays hand stored
//! events back newest first, so a phone that was offline can meet the "delete" before
//! the message itself. They are held here until the message comes, then applied to it.

use rusqlite::Connection;

use crate::repositories::KeyValueRepo;

const PREFIX: &str = "early:";

/// A held delete or edit: who sent it, as whom it is stored, when, and its bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Held {
    pub sender_id: String,
    pub author: String,
    pub at: u64,
    pub payload: Vec<u8>,
    /// When it was held, unix seconds: what never finds its message is pruned.
    pub held_at: i64,
}

fn key(kind: &str, chat_id: &str, target_id: &str) -> String {
    format!("{PREFIX}{kind}:{chat_id}:{target_id}")
}

/// Holds [held] for the message [target_id]. One delete and the latest edit are kept.
pub fn hold(conn: &Connection, kind: &str, chat_id: &str, target_id: &str, held: &Held) {
    let k = key(kind, chat_id, target_id);
    if kind == "edit" {
        if let Some(prev) = KeyValueRepo::get(conn, &k).ok().flatten().and_then(|v| parse(&v)) {
            if prev.at > held.at {
                return;
            }
        }
    }
    let v = serde_json::json!({
        "s": held.sender_id, "a": held.author, "t": held.at,
        "p": hex::encode(&held.payload), "h": held.held_at,
    });
    let _ = KeyValueRepo::set(conn, &k, &v.to_string());
}

/// What is held for [target_id] in [chat_id], edits before the delete; the records go.
pub fn take(conn: &Connection, chat_id: &str, target_id: &str) -> Vec<Held> {
    ["edit", "delete"]
        .iter()
        .filter_map(|kind| {
            let k = key(kind, chat_id, target_id);
            let v = KeyValueRepo::get(conn, &k).ok().flatten()?;
            let _ = KeyValueRepo::delete(conn, &k);
            parse(&v)
        })
        .collect()
}

/// Drops what has waited longer than [max_age_secs] for a message that never came.
pub fn prune(conn: &Connection, now: i64, max_age_secs: i64) -> usize {
    let stale: Vec<String> = KeyValueRepo::list(conn)
        .unwrap_or_default()
        .into_iter()
        .filter(|e| e.key.starts_with(PREFIX))
        .filter(|e| parse(&e.value).map_or(true, |h| now - h.held_at > max_age_secs))
        .map(|e| e.key)
        .collect();
    stale.iter().for_each(|k| {
        let _ = KeyValueRepo::delete(conn, k);
    });
    stale.len()
}

fn parse(v: &str) -> Option<Held> {
    let j: serde_json::Value = serde_json::from_str(v).ok()?;
    Some(Held {
        sender_id: j.get("s")?.as_str()?.to_string(),
        author: j.get("a")?.as_str()?.to_string(),
        at: j.get("t")?.as_u64()?,
        payload: hex::decode(j.get("p")?.as_str()?).ok()?,
        held_at: j.get("h")?.as_i64()?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DatabasePool;

    fn db() -> DatabasePool {
        let dir = std::env::temp_dir().join(format!("early-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        DatabasePool::open(&dir.join("t.db"), "").unwrap()
    }

    fn held(at: u64, held_at: i64) -> Held {
        Held { sender_id: "aa".into(), author: "aa".into(), at, payload: vec![1, 2, 3], held_at }
    }

    #[test]
    fn a_held_delete_waits_for_its_message_and_is_taken_once() {
        let db = db();
        let conn = db.connection();
        hold(&conn, "delete", "c", "m1", &held(10, 100));
        assert!(take(&conn, "c", "other").is_empty());
        assert_eq!(take(&conn, "c", "m1"), [held(10, 100)]);
        assert!(take(&conn, "c", "m1").is_empty(), "applied once");
    }

    #[test]
    fn the_latest_edit_is_kept_and_comes_before_the_delete() {
        let db = db();
        let conn = db.connection();
        hold(&conn, "delete", "c", "m1", &held(30, 100));
        hold(&conn, "edit", "c", "m1", &held(20, 100));
        hold(&conn, "edit", "c", "m1", &held(10, 100));
        let got = take(&conn, "c", "m1");
        assert_eq!(got.iter().map(|h| h.at).collect::<Vec<_>>(), [20, 30]);
    }

    #[test]
    fn what_never_finds_its_message_is_pruned() {
        let db = db();
        let conn = db.connection();
        hold(&conn, "delete", "c", "old", &held(1, 0));
        hold(&conn, "delete", "c", "new", &held(1, 1_000));
        assert_eq!(prune(&conn, 1_000, 500), 1);
        assert!(take(&conn, "c", "old").is_empty());
        assert_eq!(take(&conn, "c", "new").len(), 1);
    }
}
