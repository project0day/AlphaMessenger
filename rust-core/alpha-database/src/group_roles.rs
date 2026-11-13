//! Group role storage (G-3), backed by the key-value store.
//!
//! MLS has no roles — this is the app-layer convention. We keep it in KV
//! (no schema migration) using these keys, all scoped per chat:
//!
//! - `group_owner:<chat_id>`            → owner's Nostr pubkey (hex)
//! - `group_role:<chat_id>:<pubkey>`    → "admin" | "moderator" (absent = member)
//! - `group_roles_ver:<chat_id>`        → monotonic u64 for replay protection
//!
//! Pure storage logic, no crypto. Permission *rules* live in
//! `alpha_messages::roles`; this module only reads/writes the stored roles.

use crate::repositories::KeyValueRepo;
use rusqlite::Connection;

pub const ROLE_OWNER: &str = "owner";
pub const ROLE_ADMIN: &str = "admin";
pub const ROLE_MODERATOR: &str = "moderator";
pub const ROLE_MEMBER: &str = "member";

fn normalize(role: &str) -> &'static str {
    match role {
        ROLE_OWNER => ROLE_OWNER,
        ROLE_ADMIN => ROLE_ADMIN,
        ROLE_MODERATOR => ROLE_MODERATOR,
        _ => ROLE_MEMBER,
    }
}

/// The owner's pubkey hex for a chat, if one is recorded.
pub fn owner_of(conn: &Connection, chat_id: &str) -> Option<String> {
    KeyValueRepo::get(conn, &format!("group_owner:{}", chat_id))
        .ok()
        .flatten()
        .filter(|s| !s.is_empty())
}

/// Who rules a chat: its recorded owner, else the member who brought us in
/// (their signature is on our Welcome). `None` only for a group we created
/// before owners were recorded.
pub fn effective_owner(conn: &Connection, chat_id: &str) -> Option<String> {
    owner_of(conn, chat_id).or_else(|| {
        KeyValueRepo::get(conn, &format!("dir_bootstrap_auth:{}", chat_id)).ok().flatten().filter(|s| !s.is_empty())
    })
}

/// Whether [pubkey_hex] is a member our directory knows in this chat.
pub fn is_known_member(conn: &Connection, chat_id: &str, pubkey_hex: &str) -> bool {
    KeyValueRepo::list_prefix(conn, &format!("dir:{}:", chat_id))
        .map(|rows| rows.iter().any(|(_, v)| v == pubkey_hex))
        .unwrap_or(false)
}

/// Record the owner of a chat (set once at create_group; updated on transfer).
pub fn set_owner(conn: &Connection, chat_id: &str, owner_pubkey_hex: &str) {
    let _ = KeyValueRepo::set(conn, &format!("group_owner:{}", chat_id), owner_pubkey_hex);
}

/// Resolve the effective role of a pubkey in a chat (owner > stored > member).
pub fn role_of(conn: &Connection, chat_id: &str, pubkey_hex: &str) -> String {
    if let Some(owner) = owner_of(conn, chat_id) {
        if owner == pubkey_hex {
            return ROLE_OWNER.to_string();
        }
    }
    KeyValueRepo::get(conn, &format!("group_role:{}:{}", chat_id, pubkey_hex))
        .ok()
        .flatten()
        .map(|r| normalize(&r).to_string())
        .unwrap_or_else(|| ROLE_MEMBER.to_string())
}

/// Current role-version counter for a chat (0 if none).
pub fn roles_version(conn: &Connection, chat_id: &str) -> u64 {
    KeyValueRepo::get(conn, &format!("group_roles_ver:{}", chat_id))
        .ok()
        .flatten()
        .and_then(|s| s.parse().ok())
        .unwrap_or(0)
}

/// Persist a new role-version counter for a chat.
pub fn set_roles_version(conn: &Connection, chat_id: &str, version: u64) {
    let _ = KeyValueRepo::set(conn, &format!("group_roles_ver:{}", chat_id), &version.to_string());
}

// ── Group mute (G-4) ───────────────────────────────────────────────────────
// `group_mute:<chat>:<pubkey>` → unix-seconds expiry (0/absent = not muted)
// `group_mutes_ver:<chat>`     → monotonic u64 for replay protection

/// Mute expiry (unix secs) for a member, or 0 if not muted.
pub fn mute_until(conn: &Connection, chat_id: &str, pubkey_hex: &str) -> i64 {
    KeyValueRepo::get(conn, &format!("group_mute:{}:{}", chat_id, pubkey_hex))
        .ok().flatten().and_then(|s| s.parse().ok()).unwrap_or(0)
}

/// Set (until_ts>0) or clear (until_ts<=0) a member's mute.
pub fn set_mute(conn: &Connection, chat_id: &str, pubkey_hex: &str, until_ts: i64) {
    let key = format!("group_mute:{}:{}", chat_id, pubkey_hex);
    if until_ts > 0 {
        let _ = KeyValueRepo::set(conn, &key, &until_ts.to_string());
    } else {
        let _ = KeyValueRepo::delete(conn, &key);
    }
}

pub fn mutes_version(conn: &Connection, chat_id: &str) -> u64 {
    KeyValueRepo::get(conn, &format!("group_mutes_ver:{}", chat_id))
        .ok().flatten().and_then(|s| s.parse().ok()).unwrap_or(0)
}

pub fn set_mutes_version(conn: &Connection, chat_id: &str, version: u64) {
    let _ = KeyValueRepo::set(conn, &format!("group_mutes_ver:{}", chat_id), &version.to_string());
}

// ── Group permissions (G-8) ─────────────────────────────────────────────────
// `group_perm_write:<chat>`  → "all" | "admins"  (who may send messages)
// `group_perm_invite:<chat>` → "all" | "admins"  (who may invite / add)
// `group_perm_edit:<chat>`   → "all" | "admins"  (who may edit group info)
// `group_perms_ver:<chat>`   → monotonic u64 for replay protection
// Absent ⇒ sensible defaults: write="all", invite="admins", edit="admins".

const PERM_ALL: &str = "all";
const PERM_ADMINS: &str = "admins";

fn normalize_perm(v: &str, default: &str) -> String {
    match v {
        PERM_ALL => PERM_ALL.to_string(),
        PERM_ADMINS => PERM_ADMINS.to_string(),
        _ => default.to_string(),
    }
}

/// (who_can_write, who_can_invite, who_can_edit), each "all"|"admins".
pub fn permissions(conn: &Connection, chat_id: &str) -> (String, String, String) {
    let get = |k: &str, d: &str| {
        KeyValueRepo::get(conn, &format!("{}:{}", k, chat_id))
            .ok().flatten().map(|s| normalize_perm(&s, d)).unwrap_or_else(|| d.to_string())
    };
    (
        get("group_perm_write", PERM_ALL),
        get("group_perm_invite", PERM_ADMINS),
        get("group_perm_edit", PERM_ADMINS),
    )
}

/// Persist the group permission policy.
pub fn set_permissions(conn: &Connection, chat_id: &str, write: &str, invite: &str, edit: &str) {
    let _ = KeyValueRepo::set(conn, &format!("group_perm_write:{}", chat_id), &normalize_perm(write, PERM_ALL));
    let _ = KeyValueRepo::set(conn, &format!("group_perm_invite:{}", chat_id), &normalize_perm(invite, PERM_ADMINS));
    let _ = KeyValueRepo::set(conn, &format!("group_perm_edit:{}", chat_id), &normalize_perm(edit, PERM_ADMINS));
}

/// Allowed reactions of a group: ("all" | "some" | "none", the emoji for "some").
pub fn reactions(conn: &Connection, chat_id: &str) -> (String, Vec<String>) {
    let mode = KeyValueRepo::get(conn, &format!("group_react_mode:{}", chat_id))
        .ok().flatten().filter(|m| m == "some" || m == "none").unwrap_or_else(|| "all".to_string());
    let list = if mode == "some" {
        KeyValueRepo::get(conn, &format!("group_react_list:{}", chat_id)).ok().flatten()
            .and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok()).unwrap_or_default()
    } else { Vec::new() };
    (mode, list)
}

pub fn set_reactions(conn: &Connection, chat_id: &str, mode: &str, list: &[String]) {
    let mode = match mode { "some" | "none" => mode, _ => "all" };
    let _ = KeyValueRepo::set(conn, &format!("group_react_mode:{}", chat_id), mode);
    let list = if mode == "some" { list.to_vec() } else { Vec::new() };
    let _ = KeyValueRepo::set(conn, &format!("group_react_list:{}", chat_id),
        &serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string()));
}

/// May `emoji` be put on a message in this chat? (Removing one always may.)
pub fn reaction_allowed(conn: &Connection, chat_id: &str, emoji: &str) -> bool {
    match reactions(conn, chat_id) {
        (m, _) if m == "none" => false,
        (m, list) if m == "some" => list.iter().any(|e| e == emoji),
        _ => true,
    }
}

/// G-10: unix-seconds until which the chat is temporarily closed (0 = open).
pub fn closed_until(conn: &Connection, chat_id: &str) -> i64 {
    KeyValueRepo::get(conn, &format!("group_closed_until:{}", chat_id))
        .ok().flatten().and_then(|s| s.parse().ok()).unwrap_or(0)
}

pub fn set_closed_until(conn: &Connection, chat_id: &str, until_ts: i64) {
    let _ = KeyValueRepo::set(conn, &format!("group_closed_until:{}", chat_id), &until_ts.max(0).to_string());
}

pub fn perms_version(conn: &Connection, chat_id: &str) -> u64 {
    KeyValueRepo::get(conn, &format!("group_perms_ver:{}", chat_id))
        .ok().flatten().and_then(|s| s.parse().ok()).unwrap_or(0)
}

pub fn set_perms_version(conn: &Connection, chat_id: &str, version: u64) {
    let _ = KeyValueRepo::set(conn, &format!("group_perms_ver:{}", chat_id), &version.to_string());
}

// ── Group audit log (G-9) ────────────────────────────────────────────────────
// `group_audit:<chat>` → JSON array of {id,actor,action,target,target_name,ts},
// newest last, capped to the most recent AUDIT_CAP entries. Dedup by id.

const AUDIT_CAP: usize = 200;

/// The raw audit-log JSON array string for a chat ("[]" if none).
pub fn audit_log_json(conn: &Connection, chat_id: &str) -> String {
    KeyValueRepo::get(conn, &format!("group_audit:{}", chat_id))
        .ok().flatten().filter(|s| !s.is_empty()).unwrap_or_else(|| "[]".to_string())
}

/// Append one audit entry (dedup by `id`, cap to the newest AUDIT_CAP).
pub fn append_audit(
    conn: &Connection,
    chat_id: &str,
    id: &str,
    actor: &str,
    actor_name: &str,
    action: &str,
    target: &str,
    target_name: &str,
    ts: &str,
) {
    let mut arr: Vec<serde_json::Value> = serde_json::from_str(&audit_log_json(conn, chat_id))
        .unwrap_or_default();
    if arr.iter().any(|e| e.get("id").and_then(|v| v.as_str()) == Some(id)) {
        return; // already recorded (relay echo / multi-device)
    }
    arr.push(serde_json::json!({
        "id": id, "actor": actor, "actor_name": actor_name, "action": action,
        "target": target, "target_name": target_name, "ts": ts,
    }));
    if arr.len() > AUDIT_CAP {
        let drop = arr.len() - AUDIT_CAP;
        arr.drain(0..drop);
    }
    let _ = KeyValueRepo::set(
        conn,
        &format!("group_audit:{}", chat_id),
        &serde_json::Value::Array(arr).to_string(),
    );
}

/// Apply a role change to the KV store.
///
/// - `new_role == owner` → ownership transfer: set the new owner, demote the
///   previous owner (`current_owner`) to ADMIN, and clear any explicit role row
///   for the new owner (owner is implied, not stored in the role map).
/// - `new_role == member` → delete the explicit role row (member is the default).
/// - otherwise → store "admin"/"moderator".
pub fn apply_role_change(
    conn: &Connection,
    chat_id: &str,
    current_owner: &str,
    target_pubkey: &str,
    new_role: &str,
) {
    let role = normalize(new_role);
    let role_key = format!("group_role:{}:{}", chat_id, target_pubkey);
    match role {
        ROLE_OWNER => {
            // Demote the old owner to admin so they keep elevated rights.
            if !current_owner.is_empty() && current_owner != target_pubkey {
                let _ = KeyValueRepo::set(
                    conn,
                    &format!("group_role:{}:{}", chat_id, current_owner),
                    ROLE_ADMIN,
                );
            }
            set_owner(conn, chat_id, target_pubkey);
            let _ = KeyValueRepo::delete(conn, &role_key);
        }
        ROLE_MEMBER => {
            let _ = KeyValueRepo::delete(conn, &role_key);
        }
        other => {
            let _ = KeyValueRepo::set(conn, &role_key, other);
        }
    }
    // FIX (muted owner/admin is absurd): a privileged member can never be muted.
    // When promoting to owner/admin/moderator, clear any existing mute so role
    // and mute-state stay consistent (bug: "transferred ownership but the new
    // owner stayed muted"). Runs in the ONE applier used by both the local action
    // and the received GroupRoleUpdate, so every client converges. Bump the mutes
    // version so a late/stale GroupMuteUpdate can't re-mute them after promotion.
    if role != ROLE_MEMBER && mute_until(conn, chat_id, target_pubkey) != 0 {
        let v = mutes_version(conn, chat_id) + 1;
        set_mute(conn, chat_id, target_pubkey, 0);
        set_mutes_version(conn, chat_id, v);
    }
}

/// Pinned messages of a chat, newest pin first.
pub fn pinned(conn: &Connection, chat_id: &str) -> Vec<String> {
    KeyValueRepo::get(conn, &format!("pinned:{}", chat_id)).ok().flatten()
        .and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok())
        .unwrap_or_default()
}

/// Pin (to the top of the list) or unpin a message. At most 50 are kept.
pub fn set_pinned(conn: &Connection, chat_id: &str, message_id: &str, pin: bool) {
    let mut list: Vec<String> = pinned(conn, chat_id).into_iter().filter(|m| m != message_id).collect();
    if pin { list.insert(0, message_id.to_string()); }
    list.truncate(50);
    let _ = KeyValueRepo::set(conn, &format!("pinned:{}", chat_id),
        &serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string()));
}

/// May this member pin in this chat? A direct chat (or a group without a
/// recorded owner): everyone. A group: owner/admins, or everyone when the
/// group lets all members edit its info.
pub fn may_pin(conn: &Connection, chat_id: &str, pubkey_hex: &str) -> bool {
    if owner_of(conn, chat_id).is_none() { return true; }
    let role = role_of(conn, chat_id, pubkey_hex);
    if role == "owner" || role == "admin" { return true; }
    permissions(conn, chat_id).2 == PERM_ALL
}

// ── Channels ────────────────────────────────────────────────────────────────
// `channel_cfg:<chat>` → JSON {"on": bool, "comments": bool, "protect": bool,
//                              "join": "link"|"request"}; absent = not a channel.
// `chan_views:<chat>:<post>` → JSON array of short viewer tags (distinct count).

/// The stored channel settings ("" when the chat is not a channel).
pub fn channel_cfg_raw(conn: &Connection, chat_id: &str) -> String {
    KeyValueRepo::get(conn, &format!("channel_cfg:{}", chat_id)).ok().flatten().unwrap_or_default()
}

/// Comment kinds a channel can allow or forbid.
pub const COMMENT_KINDS: [&str; 6] = ["text", "link", "photo", "video", "voice", "file"];

pub fn set_channel_cfg(conn: &Connection, chat_id: &str, cfg: &serde_json::Value) {
    let allow_in = cfg.get("allow");
    let mut allow = serde_json::Map::new();
    for k in COMMENT_KINDS {
        allow.insert(k.to_string(), serde_json::json!(allow_in.and_then(|a| a.get(k)).and_then(|x| x.as_bool()).unwrap_or(true)));
    }
    let closed: Vec<String> = cfg.get("closed").and_then(|x| x.as_array()).map(|a| a.iter()
        .filter_map(|v| v.as_str().filter(|s| !s.is_empty() && s.len() <= 128).map(str::to_string))
        .take(500).collect()).unwrap_or_default();
    let clean = serde_json::json!({
        "on": cfg.get("on").and_then(|x| x.as_bool()).unwrap_or(false),
        "comments": cfg.get("comments").and_then(|x| x.as_bool()).unwrap_or(true),
        "protect": cfg.get("protect").and_then(|x| x.as_bool()).unwrap_or(false),
        // The channel hides its admins (they act under personas). A fact of
        // the channel, set by its owner's core, never a switch.
        "anon": cfg.get("anon").and_then(|x| x.as_bool()).unwrap_or(false),
        "join": if cfg.get("join").and_then(|x| x.as_str()) == Some("request") { "request" } else { "link" },
        // Seconds between two comments of one person (0 = no limit).
        "slow": cfg.get("slow").and_then(|x| x.as_u64()).unwrap_or(0).min(86_400),
        "allow": allow,
        // Posts whose comments are closed (the switch above is the default).
        "closed": closed,
    });
    let _ = KeyValueRepo::set(conn, &format!("channel_cfg:{}", chat_id), &clean.to_string());
}

fn channel_flag(conn: &Connection, chat_id: &str, key: &str, default: bool) -> bool {
    serde_json::from_str::<serde_json::Value>(&channel_cfg_raw(conn, chat_id)).ok()
        .and_then(|v| v.get(key).and_then(|x| x.as_bool())).unwrap_or(default)
}

/// Is this group a channel (only admins post, subscribers read)?
pub fn is_channel(conn: &Connection, chat_id: &str) -> bool { channel_flag(conn, chat_id, "on", false) }

/// Does a newcomer get the earlier messages? Channels: yes unless turned off;
/// groups: only when turned on.
pub fn history_on(conn: &Connection, chat_id: &str) -> bool {
    channel_flag(conn, chat_id, "history", is_channel(conn, chat_id))
}

/// May subscribers comment under posts?
pub fn comments_on(conn: &Connection, chat_id: &str) -> bool { channel_flag(conn, chat_id, "comments", true) }

const VIEWS_CAP: usize = 20_000;

/// Count `viewer` as having seen each post (distinct per viewer).
pub fn add_views(conn: &Connection, chat_id: &str, post_ids: &[String], viewer: &str) {
    add_tagged(conn, "chan_views", chat_id, post_ids, viewer)
}

/// Count `who` as having forwarded each post (once per person and post).
pub fn add_forwards(conn: &Connection, chat_id: &str, post_ids: &[String], who: &str) {
    add_tagged(conn, "chan_fwd", chat_id, post_ids, who)
}

/// Forwards of each of `post_ids` as `{id: count}`.
pub fn forwards_json(conn: &Connection, chat_id: &str, post_ids: &[String]) -> String {
    counts_json(conn, "chan_fwd", chat_id, post_ids)
}

fn add_tagged(conn: &Connection, prefix: &str, chat_id: &str, post_ids: &[String], who: &str) {
    let tag: String = who.chars().take(16).collect();
    for id in post_ids.iter().take(100) {
        let key = format!("{}:{}:{}", prefix, chat_id, id);
        let mut list: Vec<String> = KeyValueRepo::get(conn, &key).ok().flatten()
            .and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        if list.iter().any(|t| t == &tag) || list.len() >= VIEWS_CAP { continue; }
        list.push(tag.clone());
        let _ = KeyValueRepo::set(conn, &key, &serde_json::to_string(&list).unwrap_or_default());
    }
}

/// Views of each of `post_ids` as `{id: count}`.
pub fn views_json(conn: &Connection, chat_id: &str, post_ids: &[String]) -> String {
    counts_json(conn, "chan_views", chat_id, post_ids)
}

fn counts_json(conn: &Connection, prefix: &str, chat_id: &str, post_ids: &[String]) -> String {
    let mut out = serde_json::Map::new();
    for id in post_ids.iter().take(500) {
        let n = KeyValueRepo::get(conn, &format!("{}:{}:{}", prefix, chat_id, id)).ok().flatten()
            .and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok()).map(|l| l.len()).unwrap_or(0);
        out.insert(id.clone(), serde_json::json!(n));
    }
    serde_json::Value::Object(out).to_string()
}

fn channel_cfg_value(conn: &Connection, chat_id: &str) -> serde_json::Value {
    serde_json::from_str(&channel_cfg_raw(conn, chat_id)).unwrap_or(serde_json::Value::Null)
}

/// May a subscriber comment under `post_id` with this kind of content?
pub fn comment_allowed(conn: &Connection, chat_id: &str, post_id: &str, kind: &str) -> bool {
    let cfg = channel_cfg_value(conn, chat_id);
    if !cfg.get("comments").and_then(|x| x.as_bool()).unwrap_or(true) { return false; }
    if cfg.get("closed").and_then(|x| x.as_array()).map(|a| a.iter().any(|v| v.as_str() == Some(post_id))).unwrap_or(false) {
        return false;
    }
    cfg.get("allow").and_then(|a| a.get(kind)).and_then(|x| x.as_bool()).unwrap_or(true)
}

/// Slow mode: seconds between two comments of one person (0 = off).
pub fn comment_slow_secs(conn: &Connection, chat_id: &str) -> i64 {
    channel_cfg_value(conn, chat_id).get("slow").and_then(|x| x.as_i64()).unwrap_or(0)
}

/// When `sender` last commented here (their send time, unix secs).
pub fn last_comment_at(conn: &Connection, chat_id: &str, sender: &str) -> i64 {
    KeyValueRepo::get(conn, &format!("chan_last_cmt:{}:{}", chat_id, sender)).ok().flatten()
        .and_then(|s| s.parse().ok()).unwrap_or(0)
}

pub fn set_last_comment_at(conn: &Connection, chat_id: &str, sender: &str, at: i64) {
    let _ = KeyValueRepo::set(conn, &format!("chan_last_cmt:{}:{}", chat_id, sender), &at.to_string());
}
