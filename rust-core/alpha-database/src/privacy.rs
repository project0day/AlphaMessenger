//! Who may reach me, decided on my own phone.
//!
//! There is no server to enforce anything, so every rule here is applied by
//! the device it protects: a new chat from a stranger becomes a request, a
//! call the rules forbid never rings, a blocked person's invitations are
//! ignored. The settings never leave the device.
//!
//! KV keys:
//! - `privacy:<setting>` → the chosen value (absent = the default below)
//! - `blocked:<pubkey hex>` → unix secs when blocked
//! - `chat_request:<chat id>` → JSON `{"from": pubkey hex, "kind": "direct"|"group", "at": unix secs}`

use rusqlite::Connection;

use crate::repositories::KeyValueRepo;

/// Calls and voice/video: who may ring me.
pub const CALLS: &str = "calls";
pub const VIDEO_CALLS: &str = "video_calls";
/// A new direct chat from someone: straight in, into Requests, or not at all.
pub const NEW_CHATS: &str = "new_chats";
/// Being added to a group or channel.
pub const GROUP_INVITES: &str = "group_invites";
/// What others see of me: profile photo (and cover), "last seen" / online, the "about" text.
pub const PHOTO: &str = "photo";
pub const LAST_SEEN: &str = "last_seen";
pub const ABOUT: &str = "about";
/// My custom status text ("busy", "at the sea"…).
pub const STATUS: &str = "status";

pub const ALL: &str = "all";
pub const CONTACTS: &str = "contacts";
pub const NOBODY: &str = "nobody";
/// [NEW_CHATS] only: the chat waits in Requests.
pub const REQUESTS: &str = "requests";

/// Every setting with its allowed values; the first is the default.
const SETTINGS: &[(&str, &[&str])] = &[
    (CALLS, &[CONTACTS, ALL, NOBODY]),
    (VIDEO_CALLS, &[CONTACTS, ALL, NOBODY]),
    (NEW_CHATS, &[REQUESTS, ALL, NOBODY]),
    (GROUP_INVITES, &[CONTACTS, ALL, NOBODY]),
    (PHOTO, &[ALL, CONTACTS, NOBODY]),
    (LAST_SEEN, &[CONTACTS, ALL, NOBODY]),
    (ABOUT, &[ALL, CONTACTS, NOBODY]),
    (STATUS, &[CONTACTS, ALL, NOBODY]),
];

fn setting_key(name: &str) -> String { format!("privacy:{name}") }
fn blocked_key(pk: &str) -> String { format!("blocked:{}", pk.to_ascii_lowercase()) }
fn request_key(chat: &str) -> String { format!("chat_request:{chat}") }
fn removed_key(pk: &str) -> String { format!("not_contact:{}", pk.to_ascii_lowercase()) }

/// The value of a setting (its default when never set or unknown).
pub fn get(conn: &Connection, name: &str) -> String {
    let Some((_, allowed)) = SETTINGS.iter().find(|(n, _)| *n == name) else { return String::new() };
    KeyValueRepo::get(conn, &setting_key(name)).ok().flatten()
        .filter(|v| allowed.contains(&v.as_str()))
        .unwrap_or_else(|| allowed[0].to_string())
}

/// Change a setting. Unknown names or values are refused (`false`).
pub fn set(conn: &Connection, name: &str, value: &str) -> bool {
    let Some((_, allowed)) = SETTINGS.iter().find(|(n, _)| *n == name) else { return false };
    if !allowed.contains(&value) { return false; }
    KeyValueRepo::set(conn, &setting_key(name), value).is_ok()
}

/// Every setting and its current value.
pub fn all(conn: &Connection) -> Vec<(&'static str, String)> {
    SETTINGS.iter().map(|(n, _)| (*n, get(conn, n))).collect()
}

// --- Block list ---------------------------------------------------------------

pub fn is_blocked(conn: &Connection, pubkey_hex: &str) -> bool {
    !pubkey_hex.is_empty() && KeyValueRepo::get(conn, &blocked_key(pubkey_hex)).ok().flatten().is_some()
}

pub fn set_blocked(conn: &Connection, pubkey_hex: &str, blocked: bool, now: i64) {
    if pubkey_hex.is_empty() { return; }
    if blocked {
        let _ = KeyValueRepo::set(conn, &blocked_key(pubkey_hex), &now.to_string());
    } else {
        let _ = KeyValueRepo::delete(conn, &blocked_key(pubkey_hex));
    }
}

// --- Contacts -------------------------------------------------------------------

/// A contact: someone I have an accepted direct chat with (one I started, or
/// a request I accepted) and did not remove from contacts. Membership of a
/// common group or channel is not one.
pub fn is_contact(conn: &Connection, pubkey_hex: &str) -> bool {
    if pubkey_hex.is_empty() { return false; }
    let pk = pubkey_hex.to_ascii_lowercase();
    let Some(chat) = KeyValueRepo::get(conn, &format!("direct_chat_for:{pk}")).ok().flatten() else {
        return false;
    };
    request_of(conn, &chat).is_none() && KeyValueRepo::get(conn, &removed_key(&pk)).ok().flatten().is_none()
}

/// My contacts: `(pubkey hex, direct chat id)`.
pub fn contacts(conn: &Connection) -> Vec<(String, String)> {
    KeyValueRepo::list_prefix(conn, "direct_chat_for:").unwrap_or_default().into_iter()
        .filter_map(|(k, chat)| Some((k.strip_prefix("direct_chat_for:")?.to_string(), chat)))
        .filter(|(pk, _)| is_contact(conn, pk))
        .collect()
}

/// My contacts whose chat with me still exists. A link can outlive its chat
/// (a conversation deleted by an older path); such a person is nobody to show
/// a story to or to list.
pub fn live_contacts(conn: &Connection) -> Vec<(String, String)> {
    contacts(conn).into_iter()
        .filter(|(_, chat)| crate::repositories::ChatRepo::get(conn, chat).ok().flatten().is_some())
        .collect()
}

/// Take someone out of my contacts (the chat stays) — or back in.
pub fn set_contact(conn: &Connection, pubkey_hex: &str, contact: bool) {
    if pubkey_hex.is_empty() { return; }
    if contact {
        let _ = KeyValueRepo::delete(conn, &removed_key(pubkey_hex));
    } else {
        let _ = KeyValueRepo::set(conn, &removed_key(pubkey_hex), "1");
    }
}

/// Is `chat_id` my direct chat with a contact? (Their profile copy is the "contact" one.)
pub fn is_contact_chat(conn: &Connection, chat_id: &str) -> bool {
    if KeyValueRepo::list_prefix(conn, "direct_chat_for:").unwrap_or_default().into_iter()
        .any(|(k, chat)| chat == chat_id && k.strip_prefix("direct_chat_for:").is_some_and(|pk| is_contact(conn, pk)))
    {
        return true;
    }
    // Contact is a person, not a chat: a second direct chat with them (two
    // invitations that crossed) is theirs as well.
    let direct: bool = conn
        .query_row("SELECT chat_type = 'direct' FROM chats WHERE id = ?1", [chat_id], |r| r.get(0))
        .unwrap_or(false);
    direct && KeyValueRepo::get(conn, &format!("peer_npub_for_chat:{chat_id}")).ok().flatten()
        .is_some_and(|pk| is_contact(conn, &pk))
}

/// Does a "what others see" setting show this to a contact / to anyone else?
pub fn shows(conn: &Connection, setting: &str, to_contact: bool) -> bool {
    match get(conn, setting).as_str() {
        ALL => true,
        CONTACTS => to_contact,
        _ => false,
    }
}

// --- Requests -------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
pub struct ChatRequest {
    pub chat_id: String,
    /// Who brought us in (pubkey hex).
    pub from: String,
    /// "direct" or "group".
    pub kind: String,
    pub at: i64,
}

pub fn request_of(conn: &Connection, chat_id: &str) -> Option<ChatRequest> {
    let raw = KeyValueRepo::get(conn, &request_key(chat_id)).ok().flatten()?;
    let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
    Some(ChatRequest {
        chat_id: chat_id.to_string(),
        from: v.get("from")?.as_str()?.to_string(),
        kind: v.get("kind").and_then(|k| k.as_str()).unwrap_or("direct").to_string(),
        at: v.get("at").and_then(|a| a.as_i64()).unwrap_or(0),
    })
}

pub fn is_request(conn: &Connection, chat_id: &str) -> bool {
    KeyValueRepo::get(conn, &request_key(chat_id)).ok().flatten().is_some()
}

pub fn mark_request(conn: &Connection, chat_id: &str, from: &str, kind: &str, at: i64) {
    let v = serde_json::json!({"from": from.to_ascii_lowercase(), "kind": kind, "at": at});
    let _ = KeyValueRepo::set(conn, &request_key(chat_id), &v.to_string());
}

/// The request becomes an ordinary chat (its sender a contact, for a direct one).
pub fn accept(conn: &Connection, chat_id: &str) {
    let _ = KeyValueRepo::delete(conn, &request_key(chat_id));
}

/// Pending requests, newest first.
pub fn requests(conn: &Connection) -> Vec<ChatRequest> {
    let mut out: Vec<ChatRequest> = KeyValueRepo::list_prefix(conn, "chat_request:").unwrap_or_default()
        .into_iter()
        .filter_map(|(k, _)| request_of(conn, k.strip_prefix("chat_request:")?))
        .collect();
    out.sort_by(|a, b| b.at.cmp(&a.at));
    out
}

// --- Decisions ------------------------------------------------------------------

/// What to do with an invitation (an MLS Welcome) from `inviter`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Admission {
    /// An ordinary chat.
    Join,
    /// Join, but keep it in Requests until the user accepts.
    Request,
    /// Not at all: the invitation is dropped and nobody is told.
    Ignore,
}

pub fn admission(conn: &Connection, inviter_hex: &str, is_group: bool) -> Admission {
    if is_blocked(conn, inviter_hex) { return Admission::Ignore; }
    if is_group {
        match get(conn, GROUP_INVITES).as_str() {
            ALL => Admission::Join,
            NOBODY => Admission::Ignore,
            _ => if is_contact(conn, inviter_hex) { Admission::Join } else { Admission::Request },
        }
    } else {
        if is_contact(conn, inviter_hex) { return Admission::Join; }
        match get(conn, NEW_CHATS).as_str() {
            ALL => Admission::Join,
            NOBODY => Admission::Ignore,
            _ => Admission::Request,
        }
    }
}

/// What the call rules say about whoever sees this profile copy: a contact
/// (`to_contact`) or anyone else. (Video follows its own rule.)
pub fn calls_open_to(conn: &Connection, video: bool, to_contact: bool) -> bool {
    match get(conn, if video { VIDEO_CALLS } else { CALLS }).as_str() {
        ALL => true,
        CONTACTS => to_contact,
        _ => false,
    }
}

/// May `caller` (pubkey hex) ring me from `chat_id`?
pub fn call_allowed(conn: &Connection, chat_id: &str, caller_hex: &str, video: bool) -> bool {
    if caller_hex.is_empty() || is_blocked(conn, caller_hex) { return false; }
    let rule = get(conn, if video { VIDEO_CALLS } else { CALLS });
    match rule.as_str() {
        ALL => !is_request(conn, chat_id),
        NOBODY => false,
        _ => !is_request(conn, chat_id) && is_contact(conn, caller_hex),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DatabasePool;

    fn db() -> (DatabasePool, std::path::PathBuf) {
        let path = std::env::temp_dir().join(format!("priv-{}.db", uuid::Uuid::new_v4()));
        (DatabasePool::open(&path, "k").unwrap(), path)
    }

    const ANN: &str = "aa01";
    const BOB: &str = "bb02";

    fn befriend(conn: &Connection, pk: &str, chat: &str) {
        KeyValueRepo::set(conn, &format!("direct_chat_for:{pk}"), chat).unwrap();
    }

    #[test]
    fn defaults_and_validation() {
        let (pool, path) = db();
        let conn = pool.connection();
        assert_eq!(get(&conn, CALLS), CONTACTS);
        assert_eq!(get(&conn, NEW_CHATS), REQUESTS);
        assert!(!set(&conn, CALLS, "requests"), "requests is for new chats only");
        assert!(!set(&conn, "unknown", ALL));
        assert!(set(&conn, CALLS, NOBODY));
        assert_eq!(get(&conn, CALLS), NOBODY);
        drop(conn); drop(pool); let _ = std::fs::remove_file(path);
    }

    #[test]
    fn a_stranger_waits_in_requests_a_contact_does_not() {
        let (pool, path) = db();
        let conn = pool.connection();
        befriend(&conn, ANN, "c-ann");
        assert_eq!(admission(&conn, ANN, false), Admission::Join);
        assert_eq!(admission(&conn, BOB, false), Admission::Request);
        assert_eq!(admission(&conn, BOB, true), Admission::Request, "group invite from a stranger");

        // Bob's chat is a request until accepted; then he is a contact.
        befriend(&conn, BOB, "c-bob");
        mark_request(&conn, "c-bob", BOB, "direct", 10);
        assert!(!is_contact(&conn, BOB));
        assert_eq!(requests(&conn).len(), 1);
        accept(&conn, "c-bob");
        assert!(is_contact(&conn, BOB));
        assert!(requests(&conn).is_empty());

        set(&conn, NEW_CHATS, NOBODY);
        assert_eq!(admission(&conn, "cc03", false), Admission::Ignore);
        drop(conn); drop(pool); let _ = std::fs::remove_file(path);
    }

    #[test]
    fn what_others_see_and_removing_a_contact() {
        let (pool, path) = db();
        let conn = pool.connection();
        befriend(&conn, ANN, "c-ann");
        assert!(is_contact_chat(&conn, "c-ann"));
        assert!(shows(&conn, PHOTO, false), "photo: everyone by default");
        assert!(shows(&conn, LAST_SEEN, true) && !shows(&conn, LAST_SEEN, false), "last seen: contacts by default");
        set(&conn, ABOUT, NOBODY);
        assert!(!shows(&conn, ABOUT, true));
        assert_eq!(contacts(&conn), vec![(ANN.to_string(), "c-ann".to_string())]);
        set_contact(&conn, ANN, false);
        assert!(!is_contact(&conn, ANN) && !is_contact_chat(&conn, "c-ann") && contacts(&conn).is_empty());
        set_contact(&conn, ANN, true);
        assert!(is_contact(&conn, ANN));
        drop(conn); drop(pool); let _ = std::fs::remove_file(path);
    }

    #[test]
    fn calls_follow_the_rules_and_the_block_list() {
        let (pool, path) = db();
        let conn = pool.connection();
        befriend(&conn, ANN, "c-ann");
        assert!(call_allowed(&conn, "c-ann", ANN, false));
        assert!(!call_allowed(&conn, "group", BOB, false), "a stranger from a group");
        set(&conn, CALLS, ALL);
        assert!(call_allowed(&conn, "group", BOB, false));
        mark_request(&conn, "c-bob", BOB, "direct", 1);
        assert!(!call_allowed(&conn, "c-bob", BOB, false), "never from an unaccepted request");
        set(&conn, VIDEO_CALLS, NOBODY);
        assert!(!call_allowed(&conn, "c-ann", ANN, true));
        set_blocked(&conn, ANN, true, 1);
        assert!(!call_allowed(&conn, "c-ann", ANN, false));
        assert_eq!(admission(&conn, ANN, false), Admission::Ignore);
        set_blocked(&conn, ANN, false, 1);
        assert!(call_allowed(&conn, "c-ann", ANN, false));
        drop(conn); drop(pool); let _ = std::fs::remove_file(path);
    }
}
