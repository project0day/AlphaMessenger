//! Who may write into a group or channel is checked by every receiver: a
//! modified client must not be able to post where only admins post, nor a
//! muted member at all. Comments under channel posts are the one exception.

use std::sync::Arc;

use alpha_database::{group_roles, new_chat, ChatRepo, ChatType, DatabasePool, KeyValueRepo};
use alpha_messages::{serialize, MessageBuilder, COMMENT_MARK};
use alpha_sync::{EventProcessor, ProcessedEvent};

const CHAT: &str = "c0ffee";
const OWNER: &str = "aa";
const MEMBER: &str = "bb";

fn setup(channel: bool) -> (EventProcessor, Arc<DatabasePool>, std::path::PathBuf) {
    let dir = std::env::temp_dir().join(format!("chan-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let db = Arc::new(DatabasePool::open(&dir.join("t.db"), "").unwrap());
    {
        let conn = db.connection();
        ChatRepo::insert(&conn, &new_chat(CHAT.to_string(), ChatType::Group, Some("g".into()), 0)).unwrap();
        group_roles::set_owner(&conn, CHAT, "owner_pk");
        KeyValueRepo::set(&conn, &format!("dir:{CHAT}:{OWNER}"), "owner_pk").unwrap();
        KeyValueRepo::set(&conn, &format!("dir:{CHAT}:{MEMBER}"), "member_pk").unwrap();
        if channel {
            group_roles::set_channel_cfg(&conn, CHAT, &serde_json::json!({"on": true, "comments": true}));
        }
    }
    let p = EventProcessor::new(Arc::clone(&db), dir.join("a"), dir.join("g"));
    (p, db, dir)
}

fn text(body: &str) -> Vec<u8> { serialize(&MessageBuilder::text(body).build()).unwrap() }

fn stored(ev: &ProcessedEvent) -> bool { matches!(ev, ProcessedEvent::NewMessage { .. }) }

#[test]
fn subscriber_cannot_post_into_a_channel() {
    let (p, _db, _d) = setup(true);
    let ev = p.process_application_message(CHAT, &text("spam"), MEMBER, 1).unwrap();
    assert!(!stored(&ev), "a subscriber's post must be dropped");
}

#[test]
fn owner_posts_and_subscriber_comments() {
    let (p, _db, _d) = setup(true);
    let ev = p.process_application_message(CHAT, &text("news"), OWNER, 1).unwrap();
    assert!(stored(&ev), "the owner posts");
    let comment = format!("{COMMENT_MARK}{{\"p\":\"x\",\"n\":\"Fil\"}}\nnice");
    let ev = p.process_application_message(CHAT, &text(&comment), MEMBER, 2).unwrap();
    assert!(stored(&ev), "a comment is allowed while comments are on");
    // Comments neither count as unread nor become the chat's preview.
    let conn = _db.connection();
    assert_eq!(alpha_database::MessageRepo::count_unread(&conn, CHAT).unwrap(), 1, "only the post is unread");
    let last = alpha_database::MessageRepo::latest_in_chat(&conn, CHAT).unwrap().unwrap();
    assert_eq!(last.content.as_deref(), Some("news"), "the preview is the post");
}

#[test]
fn comments_off_drops_comments() {
    let (p, db, _d) = setup(true);
    group_roles::set_channel_cfg(&db.connection(), CHAT, &serde_json::json!({"on": true, "comments": false}));
    let comment = format!("{COMMENT_MARK}{{\"p\":\"x\",\"n\":\"Fil\"}}\nnice");
    let ev = p.process_application_message(CHAT, &text(&comment), MEMBER, 1).unwrap();
    assert!(!stored(&ev));
}

#[test]
fn admins_only_group_and_muted_member() {
    let (p, db, _d) = setup(false);
    let ev = p.process_application_message(CHAT, &text("hi"), MEMBER, 1).unwrap();
    assert!(stored(&ev), "an open group lets members write");
    group_roles::set_mute(&db.connection(), CHAT, "member_pk", chrono::Utc::now().timestamp() + 3600);
    let ev = p.process_application_message(CHAT, &text("hi again"), MEMBER, 2).unwrap();
    assert!(!stored(&ev), "a muted member is dropped");
    group_roles::set_mute(&db.connection(), CHAT, "member_pk", 0);
    group_roles::set_permissions(&db.connection(), CHAT, "admins", "admins", "admins");
    let ev = p.process_application_message(CHAT, &text("and again"), MEMBER, 3).unwrap();
    assert!(!stored(&ev), "admins-only drops a member's message");
}

// ── who may delete / edit, and the comment rules ─────────────────────────────

fn comment(post: &str, body: &str) -> Vec<u8> {
    text(&format!("{COMMENT_MARK}{{\"p\":\"{post}\",\"n\":\"Fil\"}}\n{body}"))
}

fn deleted(db: &DatabasePool, id: &str) -> bool {
    alpha_database::MessageRepo::get(&db.connection(), id).unwrap().map(|m| m.is_deleted).unwrap_or(false)
}

#[test]
fn only_author_or_admin_deletes_and_only_author_edits() {
    let (p, db, _d) = setup(true);
    let post = serialize(&MessageBuilder::text("news").id("post1").build()).unwrap();
    p.process_application_message(CHAT, &post, OWNER, 1).unwrap();
    // A subscriber can neither delete nor rewrite the owner's post.
    let del = serialize(&MessageBuilder::delete("post1").build()).unwrap();
    p.process_application_message(CHAT, &del, MEMBER, 2).unwrap();
    assert!(!deleted(&db, "post1"), "a subscriber must not delete a post");
    let edit = serialize(&MessageBuilder::edit("post1", "hacked").build()).unwrap();
    p.process_application_message(CHAT, &edit, MEMBER, 3).unwrap();
    let body = alpha_database::MessageRepo::get(&db.connection(), "post1").unwrap().unwrap().content;
    assert_eq!(body.as_deref(), Some("news"), "a subscriber must not edit a post");
    // The owner removes a subscriber's comment.
    let c = serialize(&MessageBuilder::text(&format!("{COMMENT_MARK}{{\"p\":\"post1\",\"n\":\"Fil\"}}\ntrash")).id("c1").build()).unwrap();
    p.process_application_message(CHAT, &c, MEMBER, 4).unwrap();
    let del_c = serialize(&MessageBuilder::delete("c1").build()).unwrap();
    p.process_application_message(CHAT, &del_c, OWNER, 5).unwrap();
    assert!(deleted(&db, "c1"), "the owner deletes any comment");
}

#[test]
fn comments_closed_on_one_post_only() {
    let (p, db, _d) = setup(true);
    group_roles::set_channel_cfg(&db.connection(), CHAT, &serde_json::json!({"on": true, "comments": true, "closed": ["p1"]}));
    assert!(!stored(&p.process_application_message(CHAT, &comment("p1", "hi"), MEMBER, 1).unwrap()), "closed post");
    assert!(stored(&p.process_application_message(CHAT, &comment("p2", "hi"), MEMBER, 2).unwrap()), "other posts stay open");
}

#[test]
fn forbidden_kinds_and_slow_mode() {
    let (p, db, _d) = setup(true);
    group_roles::set_channel_cfg(&db.connection(), CHAT, &serde_json::json!({"on": true, "slow": 60, "allow": {"link": false}}));
    assert!(!stored(&p.process_application_message(CHAT, &comment("p1", "see https://x.y"), MEMBER, 1000).unwrap()), "links forbidden");
    assert!(stored(&p.process_application_message(CHAT, &comment("p1", "first"), MEMBER, 1000).unwrap()));
    assert!(!stored(&p.process_application_message(CHAT, &comment("p1", "too soon"), MEMBER, 1030).unwrap()), "slow mode");
    assert!(stored(&p.process_application_message(CHAT, &comment("p1", "later"), MEMBER, 1061).unwrap()));
}
