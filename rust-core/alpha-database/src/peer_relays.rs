//! Where our contacts listen: the relays each one told us about over MLS (or
//! in the code we scanned from them). We write to those too, so two people
//! reach each other without choosing a common relay.

use rusqlite::Connection;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::KeyValueRepo;

const KV_PREFIX: &str = "peer_relays:";
/// More would only be a list of someone's relays to spray at.
pub const MAX_PER_PERSON: usize = 6;

static CHANGED: AtomicBool = AtomicBool::new(true);

/// A relay list as we keep it: wss only, no duplicates, short.
pub fn clean<I: IntoIterator<Item = String>>(relays: I) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for r in relays {
        let r = r.trim().trim_end_matches('/').to_string();
        let ok = r.starts_with("wss://") && r.len() <= 200 && r.len() > 6
            && !r[6..].contains(|c: char| c.is_whitespace() || c == '"' || c == '\'');
        if ok && !out.iter().any(|o| o.eq_ignore_ascii_case(&r)) {
            out.push(r);
        }
        if out.len() == MAX_PER_PERSON { break; }
    }
    out
}

fn is_pubkey_hex(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit())
}

/// Remember [relays] for [pubkey_hex]. An empty list keeps what we had: an
/// older build says nothing, which is not "I listen nowhere".
pub fn set(conn: &Connection, pubkey_hex: &str, relays: &[String]) {
    if !is_pubkey_hex(pubkey_hex) { return; }
    let list = clean(relays.iter().cloned());
    if list.is_empty() { return; }
    let key = format!("{KV_PREFIX}{}", pubkey_hex.to_ascii_lowercase());
    let json = serde_json::to_string(&list).unwrap_or_default();
    if KeyValueRepo::get(conn, &key).ok().flatten().as_deref() == Some(json.as_str()) { return; }
    if KeyValueRepo::set(conn, &key, &json).is_ok() {
        CHANGED.store(true, Ordering::SeqCst);
    }
}

/// Every person we know relays of: (pubkey hex, relays).
pub fn all(conn: &Connection) -> Vec<(String, Vec<String>)> {
    KeyValueRepo::list_prefix(conn, KV_PREFIX).unwrap_or_default().into_iter()
        .filter_map(|(k, v)| {
            let pk = k.strip_prefix(KV_PREFIX)?.to_string();
            let list: Vec<String> = serde_json::from_str(&v).ok()?;
            Some((pk, list))
        })
        .collect()
}

/// True once after any list changed (and at start): the routes are rebuilt then.
pub fn take_changed() -> bool {
    CHANGED.swap(false, Ordering::SeqCst)
}

/// Something else the routes depend on changed (a member joined or left).
pub fn mark_changed() {
    CHANGED.store(true, Ordering::SeqCst);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_secure_distinct_short_lists() {
        let l = clean(vec![
            "wss://a.example/".into(), "WSS://A.example".into(), "ws://plain.example".into(),
            "wss://b.example".into(), "https://x".into(), "wss://c d".into(),
        ]);
        assert_eq!(l, vec!["wss://a.example", "wss://b.example"]);
        let many: Vec<String> = (0..20).map(|i| format!("wss://r{i}.example")).collect();
        assert_eq!(clean(many).len(), MAX_PER_PERSON);
    }
}
