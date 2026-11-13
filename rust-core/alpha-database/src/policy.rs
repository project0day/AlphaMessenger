//! Who may write what into a group or channel — ONE answer.
//!
//! The same function is asked by every receiver (what to accept), by our own
//! core before anything is sent (so a forbidden message never leaves the
//! device) and by the screen (so the composer is locked with the reason
//! instead of pretending to send). Before this module the three places each
//! had their own idea, and a muted member typed and "sent" comments that every
//! other phone silently dropped.
//!
//! What a decentralised group can and cannot promise:
//! * honest clients agree: a message the rules forbid is dropped by every one
//!   of them, so nobody sees it — whatever client sent it;
//! * a modified client can still put bytes on the wire; only removal from the
//!   group (new keys) stops a member cryptographically.

use rusqlite::Connection;

use crate::group_roles;
use crate::repositories::KeyValueRepo;

/// What a member wants to put into the chat.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Intent {
    /// An ordinary message — in a channel, a post.
    Message,
    /// A comment under a channel post. `kind` is one of
    /// [`group_roles::COMMENT_KINDS`]: "text" | "link" | "photo" | "video" | "voice" | "file".
    Comment { post_id: String, kind: &'static str },
}

/// Why a write is not allowed. Each reason is something the user can be told.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Denial {
    /// The member may not write until `until` (unix seconds).
    Muted { until: i64 },
    /// Only the owner and admins write here (every channel post, an admins-only group).
    AdminsOnly,
    /// The group is closed for everyone but admins until `until`.
    ChatClosed { until: i64 },
    /// Comments are switched off for the whole channel.
    CommentsOff,
    /// Comments under this post are closed.
    PostClosed,
    /// The channel does not allow this kind of content in comments.
    KindNotAllowed,
    /// Slow mode: the next comment is possible in `wait` seconds.
    SlowMode { wait: i64 },
    /// The sender's identity in this group is not known yet (its directory
    /// entry has not arrived), so it cannot be judged an admin.
    UnknownSender,
}

impl Denial {
    /// A stable code for the app (it picks the wording).
    pub fn code(&self) -> &'static str {
        match self {
            Denial::Muted { .. } => "muted",
            Denial::AdminsOnly => "admins_only",
            Denial::ChatClosed { .. } => "chat_closed",
            Denial::CommentsOff => "comments_off",
            Denial::PostClosed => "post_closed",
            Denial::KindNotAllowed => "kind_not_allowed",
            Denial::SlowMode { .. } => "slow_mode",
            Denial::UnknownSender => "unknown_sender",
        }
    }

    /// The number that goes with the reason: an end time (unix seconds) for
    /// `muted` / `chat_closed`, seconds left for `slow_mode`, otherwise 0.
    pub fn value(&self) -> i64 {
        match self {
            Denial::Muted { until } | Denial::ChatClosed { until } => *until,
            Denial::SlowMode { wait } => *wait,
            _ => 0,
        }
    }
}

/// The member's identity key as THIS group knows it — what every decision
/// about rights rests on.
///
/// Only the group's own directory (`dir:<chat>:<sig>`), written by whoever
/// added the member, by a directory whose provenance was checked, or by the
/// leaf's own proof (see [`record_proven`]). The shared `member_npub:<sig>` map
/// serves names and pictures only: what one group taught it never grants
/// rights in another.
pub fn sender_pubkey(conn: &Connection, chat_id: &str, sig_hex: &str) -> Option<String> {
    KeyValueRepo::get(conn, &format!("dir:{chat_id}:{sig_hex}")).ok().flatten().filter(|v| !v.is_empty())
}

/// Members whose leaf is signed by their Nostr key: that key is who they are
/// in this chat, whatever an adder or a directory said before.
pub fn record_proven(conn: &Connection, chat_id: &str, proven: &[(String, String)]) {
    for (sig, npub) in proven {
        let key = format!("dir:{chat_id}:{sig}");
        let known = KeyValueRepo::get(conn, &key).ok().flatten().unwrap_or_default();
        if known == *npub {
            continue;
        }
        if !known.is_empty() {
            tracing::warn!(target: "ACCOUNT", "member binding in {chat_id} corrected by the leaf's own proof");
        }
        let _ = KeyValueRepo::set(conn, &key, npub);
        let _ = KeyValueRepo::set(conn, &format!("member_npub:{sig}"), npub);
    }
}

/// Same rule as `alpha_messages::can_manage_group`: the owner and admins.
fn manages(role: &str) -> bool {
    role == group_roles::ROLE_OWNER || role == group_roles::ROLE_ADMIN
}

/// May the member write `intent` into `chat_id`?
///
/// * `pubkey` — the member's identity key in this group (`None` = not known yet);
/// * `sender_sig` — their MLS signature key, hex (slow mode is kept per sender);
/// * `at` — when the message was written (its send time; slow mode);
/// * `now` — the current time (mutes and closures that may have ended).
pub fn may_write(
    conn: &Connection,
    chat_id: &str,
    pubkey: Option<&str>,
    sender_sig: &str,
    intent: &Intent,
    at: i64,
    now: i64,
) -> Result<(), Denial> {
    // No recorded owner: a 1:1 chat or a legacy group without governance.
    if group_roles::owner_of(conn, chat_id).is_none() {
        return Ok(());
    }
    let channel = group_roles::is_channel(conn, chat_id);
    if let Some(pk) = pubkey {
        if manages(&group_roles::role_of(conn, chat_id, pk)) {
            return Ok(());
        }
        let muted = group_roles::mute_until(conn, chat_id, pk);
        if muted > now {
            return Err(Denial::Muted { until: muted });
        }
    }
    match intent {
        Intent::Comment { post_id, kind } if channel => {
            if post_id.is_empty() {
                return Err(Denial::PostClosed);
            }
            let cfg: serde_json::Value = serde_json::from_str(&group_roles::channel_cfg_raw(conn, chat_id))
                .unwrap_or(serde_json::Value::Null);
            if !cfg.get("comments").and_then(|x| x.as_bool()).unwrap_or(true) {
                return Err(Denial::CommentsOff);
            }
            let closed = cfg.get("closed").and_then(|x| x.as_array())
                .map(|a| a.iter().any(|v| v.as_str() == Some(post_id.as_str())))
                .unwrap_or(false);
            if closed {
                return Err(Denial::PostClosed);
            }
            let allowed = cfg.get("allow").and_then(|a| a.get(*kind)).and_then(|x| x.as_bool()).unwrap_or(true);
            if !allowed {
                return Err(Denial::KindNotAllowed);
            }
            let slow = group_roles::comment_slow_secs(conn, chat_id);
            if slow > 0 && pubkey.is_some() {
                let last = group_roles::last_comment_at(conn, chat_id, sender_sig);
                if last > 0 && at > 0 && at - last < slow {
                    return Err(Denial::SlowMode { wait: slow - (at - last) });
                }
            }
            Ok(())
        }
        _ => {
            let (write, _, _) = group_roles::permissions(conn, chat_id);
            let closed = group_roles::closed_until(conn, chat_id);
            if pubkey.is_none() {
                // Its key arrives with the directory a moment later. Where anyone
                // writes it writes too; where only admins write it cannot be one.
                return if channel || write == "admins" || closed > now { Err(Denial::UnknownSender) } else { Ok(()) };
            }
            if channel || write == "admins" {
                return Err(Denial::AdminsOnly);
            }
            if closed > now {
                return Err(Denial::ChatClosed { until: closed });
            }
            Ok(())
        }
    }
}

/// Record that the member just commented (slow mode counts from here).
pub fn note_comment(conn: &Connection, chat_id: &str, sender_sig: &str, at: i64) {
    if group_roles::comment_slow_secs(conn, chat_id) > 0 {
        let last = group_roles::last_comment_at(conn, chat_id, sender_sig);
        group_roles::set_last_comment_at(conn, chat_id, sender_sig, at.max(last));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{new_chat, ChatRepo, ChatType, DatabasePool};

    const CHAT: &str = "c1";
    const OWNER: &str = "owner_pk";
    const SUB: &str = "sub_pk";

    fn db(channel: bool) -> (DatabasePool, std::path::PathBuf) {
        let dir = std::env::temp_dir().join(format!("policy-{}-{}", std::process::id(), rand_suffix()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = DatabasePool::open(&dir.join("t.db"), "").unwrap();
        {
            let conn = db.connection();
            ChatRepo::insert(&conn, &new_chat(CHAT.to_string(), ChatType::Group, Some("g".into()), 0)).unwrap();
            group_roles::set_owner(&conn, CHAT, OWNER);
            if channel {
                group_roles::set_channel_cfg(&conn, CHAT, &serde_json::json!({"on": true, "comments": true}));
            }
        }
        (db, dir)
    }

    fn rand_suffix() -> u128 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
    }

    fn comment(post: &str, kind: &'static str) -> Intent {
        Intent::Comment { post_id: post.to_string(), kind }
    }

    #[test]
    fn owner_always_writes_subscriber_never_posts() {
        let (db, _d) = db(true);
        let c = db.connection();
        assert_eq!(may_write(&c, CHAT, Some(OWNER), "o", &Intent::Message, 1, 1), Ok(()));
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &Intent::Message, 1, 1), Err(Denial::AdminsOnly));
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("p", "text"), 1, 1), Ok(()));
    }

    #[test]
    fn unknown_sender_cannot_post_into_a_channel() {
        let (db, _d) = db(true);
        let c = db.connection();
        assert_eq!(may_write(&c, CHAT, None, "x", &Intent::Message, 1, 1), Err(Denial::UnknownSender));
        // A newcomer's comment is judged by the comment rules alone.
        assert_eq!(may_write(&c, CHAT, None, "x", &comment("p", "text"), 1, 1), Ok(()));
    }

    #[test]
    fn mute_tells_its_end() {
        let (db, _d) = db(true);
        let c = db.connection();
        group_roles::set_mute(&c, CHAT, SUB, 5000);
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("p", "text"), 100, 100), Err(Denial::Muted { until: 5000 }));
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("p", "text"), 6000, 6000), Ok(()));
    }

    #[test]
    fn each_comment_rule_has_its_reason() {
        let (db, _d) = db(true);
        let c = db.connection();
        group_roles::set_channel_cfg(&c, CHAT, &serde_json::json!({"on": true, "comments": true, "closed": ["p1"], "allow": {"photo": false}, "slow": 60}));
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("p1", "text"), 1, 1), Err(Denial::PostClosed));
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("p2", "photo"), 1, 1), Err(Denial::KindNotAllowed));
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("", "text"), 1, 1), Err(Denial::PostClosed));
        note_comment(&c, CHAT, "s", 1000);
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("p2", "text"), 1030, 1030), Err(Denial::SlowMode { wait: 30 }));
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("p2", "text"), 1060, 1060), Ok(()));
        group_roles::set_channel_cfg(&c, CHAT, &serde_json::json!({"on": true, "comments": false}));
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &comment("p2", "text"), 1, 1), Err(Denial::CommentsOff));
    }

    #[test]
    fn group_rules() {
        let (db, _d) = db(false);
        let c = db.connection();
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &Intent::Message, 1, 1), Ok(()));
        assert_eq!(may_write(&c, CHAT, None, "x", &Intent::Message, 1, 1), Ok(()), "an open group lets a newcomer write");
        group_roles::set_closed_until(&c, CHAT, 500);
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &Intent::Message, 1, 100), Err(Denial::ChatClosed { until: 500 }));
        group_roles::set_closed_until(&c, CHAT, 0);
        group_roles::set_permissions(&c, CHAT, "admins", "admins", "admins");
        assert_eq!(may_write(&c, CHAT, Some(SUB), "s", &Intent::Message, 1, 1), Err(Denial::AdminsOnly));
    }

    #[test]
    fn a_leaf_s_own_proof_beats_what_an_adder_wrote() {
        let (db, _dir) = db(false);
        let conn = db.connection();
        KeyValueRepo::set(&conn, &format!("dir:{CHAT}:sig1"), "mallory_pk").unwrap();
        record_proven(&conn, CHAT, &[("sig1".to_string(), "bob_pk".to_string())]);
        assert_eq!(sender_pubkey(&conn, CHAT, "sig1").as_deref(), Some("bob_pk"));
        assert_eq!(KeyValueRepo::get(&conn, "member_npub:sig1").unwrap().as_deref(), Some("bob_pk"));
    }

    #[test]
    fn rights_rest_on_this_groups_directory_only() {
        let (db, _dir) = db(false);
        let conn = db.connection();
        // Learnt elsewhere (another group, or a display hint): no rights here.
        KeyValueRepo::set(&conn, "member_npub:sig9", "admin_pk").unwrap();
        assert_eq!(sender_pubkey(&conn, CHAT, "sig9"), None);
        KeyValueRepo::set(&conn, &format!("dir:{CHAT}:sig9"), "bob_pk").unwrap();
        assert_eq!(sender_pubkey(&conn, CHAT, "sig9").as_deref(), Some("bob_pk"));
    }
}