//! A phone that was offline gets stored events newest first: the "delete for everyone"
//! or the edit can come before the message it changes. It must still apply.

use std::sync::Arc;

use alpha_database::{new_chat, ChatRepo, ChatType, DatabasePool, MessageRepo};
use alpha_messages::{serialize, AlphaMessage, MessageBuilder};
use alpha_sync::{EventProcessor, ProcessedEvent};

const CHAT: &str = "c0ffee";
const AUTHOR: &str = "aa";
const STRANGER: &str = "bb";

fn setup() -> (EventProcessor, Arc<DatabasePool>) {
    let dir = std::env::temp_dir().join(format!("early-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let db = Arc::new(DatabasePool::open(&dir.join("t.db"), "").unwrap());
    ChatRepo::insert(&db.connection(), &new_chat(CHAT.to_string(), ChatType::Direct, None, 0)).unwrap();
    (EventProcessor::new(Arc::clone(&db), dir.join("a"), dir.join("g")), db)
}

fn delete_of(target: &str) -> Vec<u8> {
    serialize(&AlphaMessage::Delete { id: uuid::Uuid::new_v4().to_string(), target_id: target.to_string(), seq: 0 }).unwrap()
}

fn is_new(ev: &ProcessedEvent) -> bool {
    matches!(ev, ProcessedEvent::NewMessage { .. })
}

#[test]
fn a_delete_that_came_first_deletes_the_message_when_it_arrives() {
    let (p, db) = setup();
    let early = p.process_application_message(CHAT, &delete_of("m1"), AUTHOR, 20).unwrap();
    assert!(!is_new(&early), "nothing to show yet");

    let msg = serialize(&MessageBuilder::text("secret").id("m1").build()).unwrap();
    let arrived = p.process_application_message(CHAT, &msg, AUTHOR, 10).unwrap();
    assert!(!is_new(&arrived), "a deleted message is never shown nor notified");
    let row = MessageRepo::get(&db.connection(), "m1").unwrap().unwrap();
    assert!(row.is_deleted && row.content.is_none(), "stored deleted, its text gone");
}

#[test]
fn an_edit_that_came_first_applies_when_the_message_arrives() {
    let (p, db) = setup();
    let edit = serialize(&AlphaMessage::Edit {
        id: "m2".into(),
        body: "fixed".into(),
        edited_at: "2026-11-22T10:00:00Z".into(),
        seq: 0,
    })
    .unwrap();
    p.process_application_message(CHAT, &edit, AUTHOR, 20).unwrap();
    let msg = serialize(&MessageBuilder::text("typo").id("m2").build()).unwrap();
    assert!(is_new(&p.process_application_message(CHAT, &msg, AUTHOR, 10).unwrap()));
    let row = MessageRepo::get(&db.connection(), "m2").unwrap().unwrap();
    assert_eq!(row.content.as_deref(), Some("fixed"));
}

#[test]
fn someone_elses_early_delete_does_not_delete_the_message() {
    let (p, db) = setup();
    p.process_application_message(CHAT, &delete_of("m3"), STRANGER, 20).unwrap();
    let msg = serialize(&MessageBuilder::text("mine").id("m3").build()).unwrap();
    assert!(is_new(&p.process_application_message(CHAT, &msg, AUTHOR, 10).unwrap()));
    assert!(!MessageRepo::get(&db.connection(), "m3").unwrap().unwrap().is_deleted);
}
