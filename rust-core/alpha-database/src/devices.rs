//! This account's phones (linked devices).
//!
//! The account is the Nostr key, shared by all of them; each phone is its own
//! MLS leaf with its own signing key, bound to that Nostr key. A leaf whose
//! proven owner is our account but which is not this phone's is one of our
//! other phones: what it sends is ours, not a peer's.
//!
//! Like personas, the list is mirrored in memory so the receiving pipeline
//! answers "is this one of mine" without a database lock.

use std::sync::{OnceLock, RwLock};

use rusqlite::Connection;
use serde::{Deserialize, Serialize};

use crate::KeyValueRepo;

/// This phone's id: random, made once, never leaves the account's devices.
pub const KV_DEVICE_ID: &str = "device_id_v1";
/// Every phone of the account, this one included: JSON `[Device]`.
pub const KV_DEVICES: &str = "linked_devices_v1";
/// Our account's Nostr key, hex — who "we" are for the receiving pipeline.
pub const KV_OWN_ACCOUNT: &str = "own_account_hex_v1";

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct Device {
    pub id: String,
    #[serde(default)]
    pub name: String,
    /// The phone's MLS signing key (hex): its leaf in every chat but persona ones.
    #[serde(default)]
    pub sig: String,
    #[serde(default)]
    pub added_at: i64,
    /// Its leaves under our personas (channels we own hidden).
    #[serde(default)]
    pub persona_sigs: Vec<String>,
}

#[derive(Default)]
struct Cache {
    own_account: String,
    devices: Vec<Device>,
}

fn cache() -> &'static RwLock<Cache> {
    static C: OnceLock<RwLock<Cache>> = OnceLock::new();
    C.get_or_init(|| RwLock::new(Cache::default()))
}

/// This phone's id; made on first use.
pub fn this_device_id(conn: &Connection) -> String {
    if let Some(id) = KeyValueRepo::get(conn, KV_DEVICE_ID).ok().flatten().filter(|s| !s.is_empty()) {
        return id;
    }
    let id = hex::encode(&uuid::Uuid::new_v4().as_bytes()[..12]);
    let _ = KeyValueRepo::set(conn, KV_DEVICE_ID, &id);
    id
}

pub fn list(conn: &Connection) -> Vec<Device> {
    KeyValueRepo::get(conn, KV_DEVICES).ok().flatten()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save(conn: &Connection, devices: &[Device]) {
    if let Ok(json) = serde_json::to_string(devices) {
        let _ = KeyValueRepo::set(conn, KV_DEVICES, &json);
    }
    if let Ok(mut c) = cache().write() {
        c.devices = devices.to_vec();
    }
}

/// Add or update one device (by id).
pub fn upsert(conn: &Connection, d: Device) {
    let mut all = list(conn);
    match all.iter_mut().find(|x| x.id == d.id) {
        Some(x) => *x = d,
        None => all.push(d),
    }
    save(conn, &all);
}

pub fn remove(conn: &Connection, id: &str) {
    let mut all = list(conn);
    all.retain(|d| d.id != id);
    save(conn, &all);
}

pub fn set_own_account(conn: &Connection, pk_hex: &str) {
    let _ = KeyValueRepo::set(conn, KV_OWN_ACCOUNT, pk_hex);
    if let Ok(mut c) = cache().write() {
        c.own_account = pk_hex.to_string();
    }
}

/// Fill the in-memory copy from the database just opened.
pub fn load_cache(conn: &Connection) {
    let own = KeyValueRepo::get(conn, KV_OWN_ACCOUNT).ok().flatten().unwrap_or_default();
    let devices = list(conn);
    if let Ok(mut c) = cache().write() {
        *c = Cache { own_account: own, devices };
    }
}

/// More than one phone on this account.
pub fn linked() -> bool {
    cache().read().map(|c| c.devices.len() > 1).unwrap_or(false)
}

pub fn own_account() -> String {
    cache().read().map(|c| c.own_account.clone()).unwrap_or_default()
}

/// Signing keys of our other phones.
pub fn other_sigs(this_id: &str) -> Vec<String> {
    cache().read()
        .map(|c| c.devices.iter().filter(|d| d.id != this_id && !d.sig.is_empty()).map(|d| d.sig.clone()).collect())
        .unwrap_or_default()
}

/// One of our phones' leaves (any of them, persona leaves too).
pub fn is_our_sig(sig: &str) -> bool {
    !sig.is_empty() && cache().read()
        .map(|c| c.devices.iter().any(|d| d.sig == sig || d.persona_sigs.iter().any(|p| p == sig)))
        .unwrap_or(false)
}

/// Who we are in [chat_id]: our persona there, else our account.
pub fn own_in_chat(chat_id: &str) -> String {
    crate::personas::cached(chat_id).map(|p| p.nostr_pk).unwrap_or_else(own_account)
}

/// [sig] is a leaf of ours in [chat_id] (as proven by its binding), not a
/// peer's: one of our other phones. Always false while there is one phone.
pub fn is_own_leaf(conn: &Connection, chat_id: &str, sig: &str) -> bool {
    if !linked() || sig.is_empty() {
        return false;
    }
    // Our list names it, or its leaf proves our key (a leaf of an older
    // build proves nothing: then only the list can say).
    if is_our_sig(sig) {
        return true;
    }
    let me = own_in_chat(chat_id);
    !me.is_empty() && crate::policy::sender_pubkey(conn, chat_id, sig).as_deref() == Some(me.as_str())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DatabasePool;

    #[test]
    fn a_leaf_of_our_account_is_ours_only_once_a_second_phone_is_linked() {
        let path = std::env::temp_dir().join(format!("alpha_devices_{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&path, "k").unwrap();
        let conn = pool.connection();
        let id = this_device_id(&conn);
        assert_eq!(id, this_device_id(&conn), "made once");
        set_own_account(&conn, "aa");
        KeyValueRepo::set(&conn, "dir:c:s2", "aa").unwrap();
        KeyValueRepo::set(&conn, "dir:c:p1", "bb").unwrap();
        save(&conn, &[Device { id: id.clone(), sig: "s1".into(), ..Default::default() }]);
        assert!(!is_own_leaf(&conn, "c", "s2"), "one phone: nothing changes");
        upsert(&conn, Device { id: "other".into(), sig: "s2".into(), ..Default::default() });
        assert!(is_own_leaf(&conn, "c", "s2"));
        assert!(!is_own_leaf(&conn, "c", "p1"), "a peer's leaf");
        assert_eq!(other_sigs(&id), vec!["s2".to_string()]);
        remove(&conn, "other");
        load_cache(&conn);
        assert!(!linked());
        drop(conn);
        drop(pool);
        let _ = std::fs::remove_file(path);
    }
}
