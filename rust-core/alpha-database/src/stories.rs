//! Stories — kept on this phone only, each as one JSON value in the key-value
//! store. A contact's story arrives in the 1:1 chat with them (end-to-end
//! encrypted like any message) and is stored under `story:<id>`; our own under
//! `mystory:<id>` with its audience and who has seen it. Everything past its
//! `expires_at` is removed by [`sweep_expired`].

use crate::repositories::KeyValueRepo;
use rusqlite::{params, Connection};

const OTHER: &str = "story:";
const MINE: &str = "mystory:";

fn list_prefix(conn: &Connection, prefix: &str) -> Vec<(String, serde_json::Value)> {
    let mut out = Vec::new();
    let Ok(mut stmt) = conn.prepare("SELECT key, value FROM key_value_store WHERE key LIKE ?1 || '%'") else { return out };
    let Ok(rows) = stmt.query_map(params![prefix], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))) else { return out };
    for (k, v) in rows.flatten() {
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&v) {
            out.push((k[prefix.len()..].to_string(), json));
        }
    }
    out
}

/// A contact's story (as received).
pub fn put_story(conn: &Connection, id: &str, v: &serde_json::Value) {
    let _ = KeyValueRepo::set(conn, &format!("{OTHER}{id}"), &v.to_string());
}

pub fn get_story(conn: &Connection, id: &str) -> Option<serde_json::Value> {
    KeyValueRepo::get(conn, &format!("{OTHER}{id}")).ok().flatten().and_then(|s| serde_json::from_str(&s).ok())
}

pub fn delete_story(conn: &Connection, id: &str) {
    let _ = KeyValueRepo::delete(conn, &format!("{OTHER}{id}"));
}

pub fn stories(conn: &Connection) -> Vec<serde_json::Value> {
    list_prefix(conn, OTHER).into_iter().map(|(_, v)| v).collect()
}

/// Our own story (with audience and viewers).
pub fn put_mine(conn: &Connection, id: &str, v: &serde_json::Value) {
    let _ = KeyValueRepo::set(conn, &format!("{MINE}{id}"), &v.to_string());
}

pub fn get_mine(conn: &Connection, id: &str) -> Option<serde_json::Value> {
    KeyValueRepo::get(conn, &format!("{MINE}{id}")).ok().flatten().and_then(|s| serde_json::from_str(&s).ok())
}

pub fn delete_mine(conn: &Connection, id: &str) {
    let _ = KeyValueRepo::delete(conn, &format!("{MINE}{id}"));
}

pub fn mine(conn: &Connection) -> Vec<serde_json::Value> {
    list_prefix(conn, MINE).into_iter().map(|(_, v)| v).collect()
}

/// Remove every story (ours and others') whose time is over.
pub fn sweep_expired(conn: &Connection, now: i64) -> usize {
    let mut n = 0;
    for (prefix, list) in [(OTHER, list_prefix(conn, OTHER)), (MINE, list_prefix(conn, MINE))] {
        for (id, v) in list {
            if v.get("expires_at").and_then(|x| x.as_i64()).unwrap_or(0) <= now {
                let _ = KeyValueRepo::delete(conn, &format!("{prefix}{id}"));
                n += 1;
            }
        }
    }
    n
}
