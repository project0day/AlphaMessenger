//! AlphaMessenger sync engine.
//!
//! Offline→online catch-up using Nostr since-filters and the event
//! processing pipeline.

pub mod catch_up;
pub mod error;
pub mod event_processor;
pub mod sync_state;

// Re-export key types for convenience.
pub use catch_up::CatchUpManager;
pub use error::SyncError;
pub use event_processor::{adopt_legacy_comments, file_comment, write_intent, EventProcessor, ProcessedEvent};
pub use sync_state::SyncState;
