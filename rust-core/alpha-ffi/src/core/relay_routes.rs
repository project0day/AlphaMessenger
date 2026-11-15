//! Reaching people on their relays. Each of us tells contacts (inside MLS,
//! never in public) where we listen, and a contact code (QR, link) carries
//! the same hint for the first message. Sends then go to our relays and to
//! theirs, so two people need no relay in common.

use super::*;
use alpha_database::peer_relays;
use nostr::nips::nip19::{FromBech32, ToBech32};

/// Relays we add per group beyond our own: the ones most members share.
const MAX_GROUP_ROUTE: usize = 4;
/// Relays a contact code names.
const CODE_RELAYS: usize = 3;

/// The relays we listen on (enabled, read), from the saved list.
pub(super) fn own_listen_relays(conn: &rusqlite::Connection) -> Vec<String> {
    let list: Vec<PersistedRelay> = KeyValueRepo::get(conn, KV_RELAYS).ok().flatten()
        .and_then(|j| serde_json::from_str(&j).ok())
        .unwrap_or_default();
    let mut urls: Vec<String> = list.into_iter().filter(|r| r.enabled && r.read).map(|r| r.url).collect();
    // On the reserve relay we listen there too: contacts must write there.
    if alpha_transport::nostr_relay_client::reserve_active() {
        urls.push(alpha_transport::nostr_relay_client::RESERVE_RELAY.to_string());
    }
    peer_relays::clean(urls)
}

fn hex_of(key: &str) -> Option<String> {
    let k = key.trim();
    if k.len() == 64 && k.bytes().all(|b| b.is_ascii_hexdigit()) { return Some(k.to_ascii_lowercase()); }
    nostr::PublicKey::from_bech32(k).ok().map(|p| p.to_hex())
}

/// Group relays we read beside our own: at most this many per group.
const LISTEN_PER_GROUP: usize = 2;

/// Rebuild the transport's routes from what contacts told us and who is in which
/// group. Returns the group relays we must also read: those of groups whose
/// members, counting the same way we do, would not write where we listen.
pub(super) fn refresh(db: &DatabasePool, transport: &NostrRelayClient) -> Vec<String> {
    let conn = db.connection();
    let own = own_listen_relays(&conn);
    let ours = |u: &str| own.iter().any(|o| o.eq_ignore_ascii_case(u.trim_end_matches('/')));
    let raw = peer_relays::all(&conn);
    let inboxes: std::collections::HashMap<String, Vec<String>> = raw.iter()
        .map(|(pk, list)| (pk.clone(), list.iter().filter(|u| !ours(u)).cloned().collect::<Vec<_>>()))
        .filter(|(_, l)| !l.is_empty())
        .collect();
    let raw: std::collections::HashMap<String, Vec<String>> = raw.into_iter().collect();
    let mut members: std::collections::HashMap<String, std::collections::HashSet<String>> = Default::default();
    for (k, v) in KeyValueRepo::list_prefix(&conn, "dir:").unwrap_or_default() {
        let Some(chat) = k.strip_prefix("dir:").and_then(|r| r.split(':').next()) else { continue };
        if let Some(pk) = hex_of(&v) { members.entry(chat.to_string()).or_default().insert(pk); }
    }
    drop(conn);
    let mut groups = std::collections::HashMap::new();
    let mut listen: std::collections::HashMap<String, usize> = Default::default();
    for (chat, people) in members {
        let Ok(gid) = hex::decode(&chat) else { continue };
        let mut count: std::collections::HashMap<String, usize> = Default::default();
        for pk in &people {
            for u in inboxes.get(pk).into_iter().flatten() { *count.entry(u.clone()).or_default() += 1; }
        }
        let mut ranked: Vec<(String, usize)> = count.into_iter().collect();
        ranked.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        let route: Vec<String> = ranked.into_iter().take(MAX_GROUP_ROUTE).map(|(u, _)| u).collect();
        // What the others send to: the top of ALL members' relays, ours counted
        // too. None of ours among them → we read the group's busiest ones.
        let mut all: std::collections::HashMap<String, usize> = Default::default();
        for pk in &people {
            for u in raw.get(pk).into_iter().flatten() { *all.entry(u.trim_end_matches('/').to_ascii_lowercase()).or_default() += 1; }
        }
        for o in &own { *all.entry(o.trim_end_matches('/').to_ascii_lowercase()).or_default() += 1; }
        let mut all: Vec<(String, usize)> = all.into_iter().collect();
        all.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        let covered = all.iter().take(MAX_GROUP_ROUTE).any(|(u, _)| ours(u));
        if !covered && !own.is_empty() {
            for u in route.iter().take(LISTEN_PER_GROUP) {
                *listen.entry(u.clone()).or_default() += 1;
            }
        }
        if !route.is_empty() { groups.insert(gid, route); }
    }
    tracing::info!(target: "TRANSPORT", "relay routes: {} people, {} chats", inboxes.len(), groups.len());
    transport.set_relay_routes(inboxes, groups);
    let mut listen: Vec<(String, usize)> = listen.into_iter().collect();
    listen.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    listen.into_iter().map(|(u, _)| u).collect()
}

impl AlphaMessengerCore {
    /// Our contact code: the npub with up to three of our relays (nprofile),
    /// so whoever scans it writes first where we listen.
    pub fn contact_code(&self) -> Result<String, MessengerError> {
        let pk = self.identity.lock().ok()
            .and_then(|g| g.as_ref().map(|id| id.nostr().public_key()))
            .ok_or_else(|| MessengerError::InternalError { description: "no identity".into() })?;
        let relays: Vec<nostr::RelayUrl> = {
            let conn = self.db.connection();
            own_listen_relays(&conn).into_iter().take(CODE_RELAYS)
                .filter_map(|u| nostr::RelayUrl::parse(&u).ok()).collect()
        };
        if relays.is_empty() {
            return pk.to_bech32().map_err(|e| MessengerError::InternalError { description: e.to_string() });
        }
        nostr::nips::nip19::Nip19Profile::new(pk, relays).to_bech32()
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })
    }

    /// A key someone gave us, as the npub the rest of the core takes. A contact
    /// code's relays are remembered for that person.
    pub(super) fn accept_contact_code(&self, input: &str) -> String {
        let t = input.trim().trim_start_matches("nostr:");
        if !t.starts_with("nprofile1") { return t.to_string(); }
        match nostr::nips::nip19::Nip19Profile::from_bech32(t) {
            Ok(p) => {
                {
                    let conn = self.db.connection();
                    let relays: Vec<String> = p.relays.iter().map(|r| r.to_string()).collect();
                    peer_relays::set(&conn, &p.public_key.to_hex(), &relays);
                }
                refresh(&self.db, &self.transport);
                p.public_key.to_bech32().unwrap_or_else(|_| t.to_string())
            }
            Err(_) => t.to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn own_relays_are_the_enabled_readable_ones() {
        let dir = std::env::temp_dir().join(format!("routes-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let db = DatabasePool::open(&dir.join("r.db"), "").unwrap();
        let conn = db.connection();
        let list = vec![
            PersistedRelay { url: "wss://on.example".into(), read: true, write: true, enabled: true },
            PersistedRelay { url: "wss://off.example".into(), read: true, write: true, enabled: false },
            PersistedRelay { url: "wss://writeonly.example".into(), read: false, write: true, enabled: true },
        ];
        KeyValueRepo::set(&conn, KV_RELAYS, &serde_json::to_string(&list).unwrap()).unwrap();
        assert_eq!(own_listen_relays(&conn), vec!["wss://on.example"]);

        // Two members of chat "ab" told us where they listen; ours is left out,
        // the relay both use comes first.
        let (a, b) = ("1".repeat(64), "2".repeat(64));
        peer_relays::set(&conn, &a, &["wss://shared.example".into(), "wss://on.example".into()]);
        peer_relays::set(&conn, &b, &["wss://shared.example".into(), "wss://b.example".into()]);
        KeyValueRepo::set(&conn, "dir:ab:sig1", &a).unwrap();
        KeyValueRepo::set(&conn, "dir:ab:sig2", &b).unwrap();
        drop(conn);
        let t = NostrRelayClient::new(vec!["wss://on.example".to_string()]);
        // A member shares our relay and the group is small: nothing extra to read.
        assert!(refresh(&db, &t).is_empty());
        assert_eq!(t.group_route(&[0xab]), vec!["wss://shared.example", "wss://b.example"]);

        // A big group "cd" where nobody else uses our relay: we read its busiest ones.
        let conn = db.connection();
        let relays = ["wss://p.example", "wss://q.example", "wss://r.example", "wss://s.example", "wss://t.example"];
        for (i, rl) in relays.iter().enumerate() {
            for j in 0..3 {
                let pk = format!("{:064x}", 0xc0de00 + i * 16 + j);
                peer_relays::set(&conn, &pk, &[rl.to_string()]);
                KeyValueRepo::set(&conn, &format!("dir:cd:s{i}{j}"), &pk).unwrap();
            }
        }
        // p is the busiest: one more member there.
        let extra_pk = "9".repeat(64);
        peer_relays::set(&conn, &extra_pk, &["wss://p.example".into()]);
        KeyValueRepo::set(&conn, "dir:cd:sx", &extra_pk).unwrap();
        drop(conn);
        let listen = refresh(&db, &t);
        assert_eq!(listen.len(), 2);
        assert_eq!(listen[0], "wss://p.example");
        let pk = nostr::PublicKey::from_hex(&a).ok();
        if let Some(pk) = pk { assert_eq!(t.inbox_route(&pk), vec!["wss://shared.example"]); }
        let _ = std::fs::remove_dir_all(&dir);
    }
}
