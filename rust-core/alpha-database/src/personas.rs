//! Personas: who we are in an anonymous channel (see migrations 024, 025).
//!
//! One row per channel where we act under a persona instead of our identity.
//! The row holds private keys — it lives only inside the encrypted database.
//!
//! Every write also updates an in-memory copy, so "who am I in this chat" is
//! answered without a database lock — from the receiving pipeline and from
//! the core alike, and safely from inside any other lock. [`load_cache`]
//! fills it when an account's database is opened.

use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

use rusqlite::{params, Connection, OptionalExtension};

use crate::error::Result;

#[derive(Debug, Clone)]
pub struct PersonaRow {
    pub chat_id: String,
    /// Nostr secret key, hex.
    pub nostr_sk: String,
    /// Nostr public key, hex — the persona's inbox and address.
    pub nostr_pk: String,
    /// Serialized MLS signer + credential.
    pub mls: String,
    /// The persona's MLS leaf key, hex.
    pub sig: String,
    /// False while an admin's persona waits for its Welcome into the channel.
    pub joined: bool,
}

fn cache() -> &'static RwLock<HashMap<String, PersonaRow>> {
    static C: OnceLock<RwLock<HashMap<String, PersonaRow>>> = OnceLock::new();
    C.get_or_init(|| RwLock::new(HashMap::new()))
}

fn row(r: &rusqlite::Row<'_>) -> rusqlite::Result<PersonaRow> {
    Ok(PersonaRow {
        chat_id: r.get(0)?,
        nostr_sk: r.get(1)?,
        nostr_pk: r.get(2)?,
        mls: r.get(3)?,
        joined: r.get::<_, i64>(4)? != 0,
        sig: r.get(5)?,
    })
}

const COLS: &str = "chat_id, nostr_sk, nostr_pk, mls, joined, sig";

/// Record the persona for a channel. A channel has exactly one persona of ours.
pub fn put(conn: &Connection, p: &PersonaRow) -> Result<()> {
    conn.execute(
        "INSERT OR REPLACE INTO personas (chat_id, nostr_sk, nostr_pk, mls, joined, sig, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![p.chat_id, p.nostr_sk, p.nostr_pk, p.mls, p.joined as i64, p.sig, chrono::Utc::now().to_rfc3339()],
    )?;
    if let Ok(mut c) = cache().write() {
        c.insert(p.chat_id.clone(), p.clone());
    }
    Ok(())
}

/// Our persona in a channel, if we act there under one.
pub fn get(conn: &Connection, chat_id: &str) -> Option<PersonaRow> {
    conn.query_row(&format!("SELECT {COLS} FROM personas WHERE chat_id = ?1"), params![chat_id], row)
        .optional()
        .ok()
        .flatten()
}

/// The persona whose inbox is `nostr_pk` (to open an envelope addressed to it).
pub fn by_inbox(conn: &Connection, nostr_pk: &str) -> Option<PersonaRow> {
    conn.query_row(&format!("SELECT {COLS} FROM personas WHERE nostr_pk = ?1"), params![nostr_pk], row)
        .optional()
        .ok()
        .flatten()
}

/// Every persona of ours.
pub fn all(conn: &Connection) -> Vec<PersonaRow> {
    let Ok(mut st) = conn.prepare(&format!("SELECT {COLS} FROM personas")) else { return Vec::new() };
    st.query_map([], row).map(|it| it.filter_map(|r| r.ok()).collect()).unwrap_or_default()
}

/// The persona entered its channel.
pub fn mark_joined(conn: &Connection, chat_id: &str) -> Result<()> {
    conn.execute("UPDATE personas SET joined = 1 WHERE chat_id = ?1", params![chat_id])?;
    if let Ok(mut c) = cache().write() {
        if let Some(p) = c.get_mut(chat_id) { p.joined = true; }
    }
    Ok(())
}

/// Fill in the leaf key of a row written before it was kept.
pub fn set_sig(conn: &Connection, chat_id: &str, sig: &str) -> Result<()> {
    conn.execute("UPDATE personas SET sig = ?2 WHERE chat_id = ?1", params![chat_id, sig])?;
    if let Ok(mut c) = cache().write() {
        if let Some(p) = c.get_mut(chat_id) { p.sig = sig.to_string(); }
    }
    Ok(())
}

/// Forget the persona (we left or were removed from the channel).
pub fn delete(conn: &Connection, chat_id: &str) -> Result<()> {
    conn.execute("DELETE FROM personas WHERE chat_id = ?1", params![chat_id])?;
    if let Ok(mut c) = cache().write() {
        c.remove(chat_id);
    }
    Ok(())
}

/// Read every row of the database just opened into the in-memory copy.
pub fn load_cache(conn: &Connection) {
    let rows = all(conn);
    if let Ok(mut c) = cache().write() {
        *c = rows.into_iter().map(|p| (p.chat_id.clone(), p)).collect();
    }
}

/// Our persona in `chat_id` from the in-memory copy (joined or not).
pub fn cached(chat_id: &str) -> Option<PersonaRow> {
    cache().read().ok().and_then(|c| c.get(chat_id).cloned())
}

/// Every persona from the in-memory copy.
pub fn cached_all() -> Vec<PersonaRow> {
    cache().read().map(|c| c.values().cloned().collect()).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DatabasePool;

    #[test]
    fn a_persona_is_found_by_channel_and_by_inbox() {
        let path = std::env::temp_dir().join(format!("persona-{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&path, "k").unwrap();
        let conn = pool.connection();
        let chat = format!("c-{}", uuid::Uuid::new_v4());
        let p = PersonaRow {
            chat_id: chat.clone(),
            nostr_sk: "aa".into(),
            nostr_pk: format!("pk-{chat}"),
            mls: "{}".into(),
            sig: String::new(),
            joined: false,
        };
        put(&conn, &p).unwrap();
        assert_eq!(get(&conn, &chat).unwrap().nostr_pk, p.nostr_pk);
        assert_eq!(by_inbox(&conn, &p.nostr_pk).unwrap().chat_id, chat);
        assert!(!cached(&chat).unwrap().joined);
        mark_joined(&conn, &chat).unwrap();
        set_sig(&conn, &chat, "ff").unwrap();
        assert!(get(&conn, &chat).unwrap().joined);
        assert!(cached(&chat).unwrap().joined);
        assert_eq!(cached(&chat).unwrap().sig, "ff");
        delete(&conn, &chat).unwrap();
        assert!(get(&conn, &chat).is_none());
        assert!(cached(&chat).is_none());
        drop(conn);
        drop(pool);
        let _ = std::fs::remove_file(&path);
    }
}
