//! Ephemeral rooms inside a chat (a group call, for one): who is in, each
//! member's state, and signals addressed to one member.
//!
//! Everything rides on transient chat states (MLS-encrypted, never stored in
//! the history). Wire forms of the state string:
//! - `to:<member>:<payload>`   for one member only; everyone else drops it here;
//! - `room:<room>:<json>`      a member's state in a room; empty json = left.
//! A member who stops announcing drops out after [`TTL`].

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

/// A member silent for this long is gone (announce well within it).
pub const TTL: Duration = Duration::from_secs(45);

type Members = HashMap<String, (String, Instant)>;

fn rooms() -> &'static Mutex<HashMap<(String, String), Members>> {
    static R: OnceLock<Mutex<HashMap<(String, String), Members>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashMap::new()))
}

/// A room name usable on the wire: no separator, bounded.
pub fn clean_room(room: &str) -> String {
    room.chars().filter(|c| *c != ':' && !c.is_control()).take(64).collect()
}

/// Rooms and members kept at most: names come from peers, so a member inventing
/// a new room per message must not grow this table without end.
const MAX_ROOMS: usize = 256;
const MAX_MEMBERS: usize = 256;

/// Record `member`'s state in a room ("" = left).
pub fn set(chat_id: &str, room: &str, member: &str, state: &str) {
    let mut all = rooms().lock().unwrap_or_else(|p| p.into_inner());
    let room = clean_room(room);
    let key = (chat_id.to_string(), room);
    if !state.is_empty() && !all.contains_key(&key) && all.len() >= MAX_ROOMS {
        // Make room by forgetting the silent; still full = this one is not kept.
        all.retain(|_, m| { m.retain(|_, (_, at)| at.elapsed() < TTL); !m.is_empty() });
        if all.len() >= MAX_ROOMS { return; }
    }
    if state.is_empty() {
        if let Some(m) = all.get_mut(&key) {
            m.remove(member);
            if m.is_empty() { all.remove(&key); }
        }
    } else {
        let m = all.entry(key).or_default();
        if m.len() >= MAX_MEMBERS && !m.contains_key(member) {
            m.retain(|_, (_, at)| at.elapsed() < TTL);
            if m.len() >= MAX_MEMBERS { return; }
        }
        m.insert(member.to_string(), (state.to_string(), Instant::now()));
    }
}

/// Who is in the room now: `[{"member","state","age"}]`, the silent ones pruned.
pub fn snapshot(chat_id: &str, room: &str) -> String {
    let mut all = rooms().lock().unwrap_or_else(|p| p.into_inner());
    let key = (chat_id.to_string(), clean_room(room));
    let Some(m) = all.get_mut(&key) else { return "[]".to_string() };
    m.retain(|_, (_, at)| at.elapsed() < TTL);
    let out: Vec<serde_json::Value> = m.iter().map(|(member, (state, at))| serde_json::json!({
        "member": member,
        "state": state,
        "age": at.elapsed().as_secs(),
    })).collect();
    if m.is_empty() { all.remove(&key); }
    serde_json::Value::Array(out).to_string()
}

/// How many are in the room now.
pub fn count(chat_id: &str, room: &str) -> u32 {
    let mut all = rooms().lock().unwrap_or_else(|p| p.into_inner());
    let key = (chat_id.to_string(), clean_room(room));
    let Some(m) = all.get_mut(&key) else { return 0 };
    m.retain(|_, (_, at)| at.elapsed() < TTL);
    let n = m.len() as u32;
    if n == 0 { all.remove(&key); }
    n
}

/// An incoming chat state: what the app should see, or None when it is not
/// for us. Room states also update the room table.
pub fn route(own_member: &str, chat_id: &str, sender: &str, state: &str) -> Option<String> {
    if let Some(rest) = state.strip_prefix("to:") {
        let (to, payload) = rest.split_once(':')?;
        let ours = (!own_member.is_empty() && to == own_member) || alpha_database::devices::is_our_sig(to);
        return ours.then(|| payload.to_string());
    }
    if let Some(rest) = state.strip_prefix("room:") {
        let (room, body) = rest.split_once(':')?;
        set(chat_id, room, sender, body);
    }
    Some(state.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addressed_signal_reaches_only_its_member() {
        assert_eq!(route("me", "c", "a", "to:me:offer"), Some("offer".to_string()));
        assert_eq!(route("me", "c", "a", "to:you:offer"), None);
        assert_eq!(route("", "c", "a", "to:me:offer"), None);
        assert_eq!(route("me", "c", "a", "typing"), Some("typing".to_string()));
    }

    #[test]
    fn room_tracks_join_state_and_leave() {
        route("me", "c1", "a", "room:call:{\"mic\":true}");
        route("me", "c1", "b", "room:call:{\"mic\":false}");
        assert_eq!(count("c1", "call"), 2);
        assert!(snapshot("c1", "call").contains("\"member\":\"a\""));
        route("me", "c1", "a", "room:call:");
        assert_eq!(count("c1", "call"), 1);
        assert_eq!(count("c1", "other"), 0);
    }

    #[test]
    fn room_name_is_wire_safe() {
        assert_eq!(clean_room("a:b\nc"), "abc");
    }
}
