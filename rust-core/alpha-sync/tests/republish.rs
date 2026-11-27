//! A send retried after its relay confirmation was lost goes out as a new event (MLS
//! encrypts every attempt anew), carrying the same message id. The receiver keeps one
//! message, whichever copy arrives first.

use std::sync::Arc;

use alpha_database::{new_chat, ChatRepo, ChatType, DatabasePool, MessageRepo};
use alpha_messages::{serialize, MessageBuilder};
use alpha_sync::{EventProcessor, ProcessedEvent};

const CHAT: &str = "c0ffee";
const SENDER: &str = "aa";

fn setup() -> (EventProcessor, Arc<DatabasePool>) {
    let dir = std::env::temp_dir().join(format!("republish-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let db = Arc::new(DatabasePool::open(&dir.join("t.db"), "").unwrap());
    ChatRepo::insert(&db.connection(), &new_chat(CHAT.to_string(), ChatType::Direct, None, 0)).unwrap();
    let p = EventProcessor::new(Arc::clone(&db), dir.join("a"), dir.join("g"));
    (p, db)
}

fn rows_with_id(db: &DatabasePool, id: &str) -> usize {
    usize::from(MessageRepo::get(&db.connection(), id).unwrap().is_some())
}

#[test]
fn a_republished_album_is_stored_once() {
    let (p, db) = setup();
    let album = r#"{"album":true,"media_type":"album","items":[]}"#;
    let attempt = || serialize(&MessageBuilder::media(album, "album", "album", 0).id("m-album").build()).unwrap();

    let first = p.process_application_message(CHAT, &attempt(), SENDER, 1).unwrap();
    assert!(matches!(first, ProcessedEvent::NewMessage { .. }), "the first copy is the message");
    let again = p.process_application_message(CHAT, &attempt(), SENDER, 2).unwrap();
    assert!(!matches!(again, ProcessedEvent::NewMessage { .. }), "the retried copy is not a second message");
    assert_eq!(rows_with_id(&db, "m-album"), 1);
}

#[test]
fn a_republished_text_is_stored_once() {
    let (p, db) = setup();
    let attempt = || serialize(&MessageBuilder::text("hello").id("m-text").build()).unwrap();

    p.process_application_message(CHAT, &attempt(), SENDER, 1).unwrap();
    let again = p.process_application_message(CHAT, &attempt(), SENDER, 2).unwrap();
    assert!(!matches!(again, ProcessedEvent::NewMessage { .. }));
    assert_eq!(rows_with_id(&db, "m-text"), 1);
}
