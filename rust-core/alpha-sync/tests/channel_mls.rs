//! A channel run end to end with real MLS: an owner and two subscribers, each
//! with their own MLS state, database and event processor. Every message is
//! encrypted by its sender, decrypted by each receiver and judged by that
//! receiver's own rules — what the phones do, minus the relays.
//!
//! The "forged" sends below skip the sender's own check on purpose: they are
//! what a modified client would put on the wire.

use std::sync::Arc;

use alpha_database::{comments, group_roles, new_chat, policy, ChatRepo, ChatType, DatabasePool, KeyValueRepo, MessageRepo};
use alpha_messages::{serialize, AlphaMessage, MessageBuilder, COMMENT_MARK};
use alpha_mls::provider::AlphaCryptoProvider;
use alpha_mls::{AlphaCredential, MlsSessionManager, ReceivedMessage};
use alpha_sync::{write_intent, EventProcessor, ProcessedEvent};
use openmls_traits::OpenMlsProvider;

struct Client {
    mls: MlsSessionManager<AlphaCryptoProvider>,
    db: Arc<DatabasePool>,
    proc: EventProcessor,
    /// MLS signature key, hex — the sender id receivers see.
    sig: String,
    /// Identity key (what roles and mutes are keyed by).
    pk: String,
    _dir: std::path::PathBuf,
}

impl Client {
    fn new(label: &str) -> Self {
        let provider = AlphaCryptoProvider::new();
        let (_c, signer) = AlphaCredential::generate(label.as_bytes()).unwrap();
        let cred = AlphaCredential::new(label.as_bytes(), &signer).unwrap();
        cred.store_signer(provider.storage()).unwrap();
        let sig = hex::encode(signer.public());
        let mls = MlsSessionManager::new(provider, signer, cred);
        let dir = std::env::temp_dir().join(format!("chmls-{label}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = Arc::new(DatabasePool::open(&dir.join("t.db"), "").unwrap());
        let proc = EventProcessor::new(Arc::clone(&db), dir.join("a"), dir.join("g"));
        Client { mls, db, proc, sig, pk: format!("{label}_pk"), _dir: dir }
    }

    /// An honest send: our own rules first (as the core does), then encrypt.
    fn send(&mut self, gid: &[u8], chat: &str, msg: &AlphaMessage) -> Option<Vec<u8>> {
        if let Some(intent) = write_intent(msg) {
            let now = chrono::Utc::now().timestamp();
            let conn = self.db.connection();
            if policy::may_write(&conn, chat, Some(&self.pk), &self.sig, &intent, now, now).is_err() {
                return None;
            }
        }
        Some(self.forge(gid, msg))
    }

    /// What a modified client does: encrypt and send, no rules asked.
    fn forge(&mut self, gid: &[u8], msg: &AlphaMessage) -> Vec<u8> {
        self.mls.send(gid, &serialize(msg).unwrap()).unwrap()
    }

    /// An admin action is applied on the admin's own phone at once (the core
    /// does this next to sending it) — the same processing, with our own key.
    fn act(&mut self, gid: &[u8], chat: &str, msg: &AlphaMessage) -> Vec<u8> {
        let ct = self.forge(gid, msg);
        self.proc.process_application_message(chat, &serialize(msg).unwrap(), &self.sig.clone(), 0).unwrap();
        ct
    }

    /// Decrypt and judge. True when the message became a chat message.
    fn receive(&mut self, gid: &[u8], chat: &str, ct: &[u8], ts: u64) -> bool {
        match self.mls.receive(gid, ct).unwrap() {
            ReceivedMessage::Application { data, sender } => {
                let ev = self.proc.process_application_message(chat, &data, &hex::encode(&sender.signature_key), ts).unwrap();
                matches!(ev, ProcessedEvent::NewMessage { .. })
            }
            _ => false,
        }
    }
}

/// Owner + two subscribers in one channel, everyone knowing everyone's key.
fn channel() -> (Client, Client, Client, Vec<u8>, String) {
    let mut owner = Client::new("owner");
    let mut a = Client::new("alice");
    let mut b = Client::new("bob");
    let gid = owner.mls.create_group().unwrap();
    let kp_a = a.mls.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (_c1, w_a) = owner.mls.add_member_to_group(&gid, &kp_a).unwrap();
    a.mls.join_chat(&w_a).unwrap();
    let kp_b = b.mls.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (c2, w_b) = owner.mls.add_member_to_group(&gid, &kp_b).unwrap();
    a.mls.receive(&gid, &c2).unwrap();
    b.mls.join_chat(&w_b).unwrap();

    let chat = hex::encode(&gid);
    let everyone = [(owner.sig.clone(), owner.pk.clone()), (a.sig.clone(), a.pk.clone()), (b.sig.clone(), b.pk.clone())];
    for c in [&owner, &a, &b] {
        let conn = c.db.connection();
        ChatRepo::insert(&conn, &new_chat(chat.clone(), ChatType::Group, Some("ch".into()), 0)).unwrap();
        group_roles::set_owner(&conn, &chat, &owner.pk);
        group_roles::set_channel_cfg(&conn, &chat, &serde_json::json!({"on": true, "comments": true}));
        // The group directory (sig → identity), as the adder publishes it.
        for (sig, pk) in &everyone {
            KeyValueRepo::set(&conn, &format!("dir:{chat}:{sig}"), pk).unwrap();
        }
    }
    (owner, a, b, gid, chat)
}

/// Ids of the posts the tests comment on.
const P: &str = "6f1c7a52-0d6e-4c0e-9a51-1b0c3b2f6a01";
const P1: &str = "6f1c7a52-0d6e-4c0e-9a51-1b0c3b2f6a02";
const P2: &str = "6f1c7a52-0d6e-4c0e-9a51-1b0c3b2f6a03";

/// A comment as the app sends it: a text message in the post's thread.
fn comment(post: &str, body: &str) -> AlphaMessage {
    MessageBuilder::text(body).build().in_thread(Some(post.to_string()))
}

/// A comment as versions before the typed thread wrote it.
fn old_comment(post: &str, body: &str) -> AlphaMessage {
    MessageBuilder::text(&format!("{COMMENT_MARK}{{\"p\":\"{post}\",\"n\":\"x\"}}\n{body}")).build()
}

#[test]
fn owner_posts_reach_everyone() {
    let (mut owner, mut a, mut b, gid, chat) = channel();
    let ct = owner.send(&gid, &chat, &MessageBuilder::text("news").build()).expect("the owner may post");
    assert!(a.receive(&gid, &chat, &ct, 1));
    assert!(b.receive(&gid, &chat, &ct, 1));
}

#[test]
fn a_forged_post_by_a_subscriber_is_seen_by_nobody() {
    let (mut owner, mut a, mut b, gid, chat) = channel();
    let post = MessageBuilder::text("spam").build();
    assert!(a.send(&gid, &chat, &post).is_none(), "an honest client does not send it at all");
    let ct = a.forge(&gid, &post);
    assert!(!owner.receive(&gid, &chat, &ct, 1), "the owner drops it");
    assert!(!b.receive(&gid, &chat, &ct, 1), "the other subscriber drops it");
}

#[test]
fn a_mute_reaches_the_muted_one_and_their_forged_comment_is_dropped() {
    let (mut owner, mut a, mut b, gid, chat) = channel();
    let until = chrono::Utc::now().timestamp() + 3600;
    let mute = AlphaMessage::GroupMuteUpdate { version: 1, target_pubkey: a.pk.clone(), until_ts: until, ts: String::new() };
    let ct = owner.act(&gid, &chat, &mute);
    a.receive(&gid, &chat, &ct, 1);
    b.receive(&gid, &chat, &ct, 1);
    {
        // The muted member's OWN phone knows it — the composer can say so.
        let conn = a.db.connection();
        let v = policy::may_write(&conn, &chat, Some(&a.pk), &a.sig, &policy::Intent::Comment { post_id: P.into(), kind: "text" }, 10, 10);
        assert_eq!(v, Err(policy::Denial::Muted { until }));
    }
    assert!(a.send(&gid, &chat, &comment(P, "hi")).is_none(), "an honest client refuses");
    let ct = a.forge(&gid, &comment(P, "hi"));
    assert!(!owner.receive(&gid, &chat, &ct, 2), "the owner drops it");
    assert!(!b.receive(&gid, &chat, &ct, 2), "the other subscriber drops it");
}

#[test]
fn a_member_unknown_to_a_receiver_cannot_post_but_can_comment() {
    let (_owner, mut a, mut b, gid, chat) = channel();
    // Bob has not learned Alice's key yet (her directory entry is late).
    KeyValueRepo::delete(&b.db.connection(), &format!("dir:{chat}:{}", a.sig)).ok();
    let ct = a.forge(&gid, &MessageBuilder::text("I am the admin").build());
    assert!(!b.receive(&gid, &chat, &ct, 1), "an unknown sender is never taken for an admin");
    let ct = a.forge(&gid, &comment(P, "hello"));
    assert!(b.receive(&gid, &chat, &ct, 2), "a comment is judged by the comment rules alone");
}

#[test]
fn a_subscriber_cannot_change_the_rules() {
    let (mut owner, mut a, mut b, gid, chat) = channel();
    let forged = AlphaMessage::GroupPermissionsUpdate {
        version: 99, who_can_write: "all".into(), who_can_invite: "all".into(), who_can_edit: "all".into(),
        closed_until: 0, reactions_mode: String::new(), reactions: vec![],
        channel_cfg: r#"{"on":false}"#.into(), ts: String::new(),
    };
    let ct = a.forge(&gid, &forged);
    owner.receive(&gid, &chat, &ct, 1);
    b.receive(&gid, &chat, &ct, 1);
    assert!(group_roles::is_channel(&b.db.connection(), &chat), "still a channel for Bob");
    assert!(group_roles::is_channel(&owner.db.connection(), &chat), "still a channel for the owner");
    let ct = a.forge(&gid, &MessageBuilder::text("now I post").build());
    assert!(!b.receive(&gid, &chat, &ct, 2));
}

#[test]
fn closing_one_post_closes_only_it_for_everyone() {
    let (mut owner, mut a, mut b, gid, chat) = channel();
    let close = AlphaMessage::GroupPermissionsUpdate {
        version: 1, who_can_write: "admins".into(), who_can_invite: "admins".into(), who_can_edit: "admins".into(),
        closed_until: 0, reactions_mode: String::new(), reactions: vec![],
        channel_cfg: format!(r#"{{"on":true,"comments":true,"closed":["{P1}"]}}"#), ts: String::new(),
    };
    let ct = owner.act(&gid, &chat, &close);
    a.receive(&gid, &chat, &ct, 1);
    b.receive(&gid, &chat, &ct, 1);
    assert!(a.send(&gid, &chat, &comment(P1, "x")).is_none(), "Alice's own phone refuses: the post is closed");
    let ct = a.forge(&gid, &comment(P1, "x"));
    assert!(!b.receive(&gid, &chat, &ct, 2) && !owner.receive(&gid, &chat, &ct, 2));
    let ct = a.send(&gid, &chat, &comment(P2, "y")).expect("another post is open");
    assert!(b.receive(&gid, &chat, &ct, 3));
}

#[test]
fn a_comment_lives_in_its_post_discussion_not_in_the_feed() {
    let (mut owner, mut a, mut b, gid, chat) = channel();
    let post = MessageBuilder::text("news").build();
    let ct = owner.act(&gid, &chat, &post);
    assert!(a.receive(&gid, &chat, &ct, 1) && b.receive(&gid, &chat, &ct, 1));

    let c = comment(post.id(), "first!");
    let ct = a.send(&gid, &chat, &c).expect("comments are open");
    assert!(b.receive(&gid, &chat, &ct, 2) && owner.receive(&gid, &chat, &ct, 2));

    for who in [&owner, &b] {
        let conn = who.db.connection();
        let feed: Vec<String> = MessageRepo::get_by_chat(&conn, &chat, 50, 0).unwrap().into_iter().map(|m| m.id).collect();
        assert_eq!(feed, vec![post.id().to_string()], "the feed is posts only");
        let thread = MessageRepo::get_thread(&conn, &chat, post.id(), 50, 0).unwrap();
        assert_eq!(thread.len(), 1);
        assert_eq!(thread[0].content.as_deref(), Some("first!"), "stored as plain words");
        assert_eq!(comments::post_of(&conn, c.id()).as_deref(), Some(post.id()));
    }
}

#[test]
fn an_old_format_comment_is_filed_with_its_words_only() {
    let (_owner, mut a, mut b, gid, chat) = channel();
    let c = old_comment(P, "from an old version");
    let ct = a.forge(&gid, &c);
    assert!(b.receive(&gid, &chat, &ct, 1));
    let conn = b.db.connection();
    assert!(MessageRepo::get_by_chat(&conn, &chat, 50, 0).unwrap().is_empty());
    let thread = MessageRepo::get_thread(&conn, &chat, P, 50, 0).unwrap();
    assert_eq!(thread[0].content.as_deref(), Some("from an old version"));
}

#[test]
fn a_moderator_removes_comments_but_not_posts() {
    let (mut owner, mut a, mut b, gid, chat) = channel();
    let role = AlphaMessage::GroupRoleUpdate {
        version: 1, target_pubkey: a.pk.clone(), role: "moderator".into(), ts: String::new(),
    };
    let ct = owner.act(&gid, &chat, &role);
    a.receive(&gid, &chat, &ct, 1);
    b.receive(&gid, &chat, &ct, 1);

    let post = MessageBuilder::text("news").build();
    let ct = owner.act(&gid, &chat, &post);
    a.receive(&gid, &chat, &ct, 2);
    b.receive(&gid, &chat, &ct, 2);
    let rude = comment(post.id(), "rude");
    let ct = b.send(&gid, &chat, &rude).unwrap();
    owner.receive(&gid, &chat, &ct, 3);
    a.receive(&gid, &chat, &ct, 3);

    let del_comment = MessageBuilder::delete(rude.id()).build();
    let ct = a.forge(&gid, &del_comment);
    assert!(owner.receive(&gid, &chat, &ct, 4), "the moderator's delete of a comment is applied");
    assert!(MessageRepo::get(&owner.db.connection(), rude.id()).unwrap().unwrap().is_deleted);

    let del_post = MessageBuilder::delete(post.id()).build();
    let ct = a.forge(&gid, &del_post);
    owner.receive(&gid, &chat, &ct, 5);
    assert!(!MessageRepo::get(&owner.db.connection(), post.id()).unwrap().unwrap().is_deleted, "a post stays");
}

/// A profile broadcast reaches a receiver as a "contact" copy and "member"
/// copies shaped by the sender's privacy settings: the fuller copy of one
/// broadcast wins whatever order they arrive in; a newer broadcast wins.
#[test]
fn the_contact_copy_of_a_profile_wins_and_a_newer_one_replaces_it() {
    let (mut owner, mut a, _b, gid, chat) = channel();
    let profile = |version: u64, bio: &str, scope: &str| AlphaMessage::ProfileUpdate {
        version, display_name: "Owner".into(), bio: bio.into(),
        avatar_jpeg_base64: String::new(), avatar_hash: String::new(),
        cover_jpeg_base64: String::new(), cover_hash: String::new(),
        avatar_ref: String::new(), cover_ref: String::new(), avatar_version: 0, cover_version: 0,
        presence_secret: String::new(), wake_topic: String::new(), ts: String::new(),
        scope: Some(scope.into()),
        status: None, status_color: None, can_call: None, can_video: None,
        video_ref: String::new(), video_version: 0, relays: Vec::new(),
    };
    let sig = owner.sig.clone();
    let bio = |a: &Client| alpha_database::ContactRepo::get(&a.db.connection(), &sig).unwrap().map(|c| c.bio).unwrap_or_default();
    let ct = owner.forge(&gid, &profile(1, "about me", "contact"));
    a.receive(&gid, &chat, &ct, 1);
    let ct = owner.forge(&gid, &profile(1, "", "member"));
    a.receive(&gid, &chat, &ct, 2);
    assert_eq!(bio(&a), "about me", "the thinner copy of the same broadcast does not override");
    let ct = owner.forge(&gid, &profile(2, "", "member"));
    a.receive(&gid, &chat, &ct, 3);
    assert_eq!(bio(&a), "", "a newer broadcast replaces it (e.g. no longer a contact)");
}
