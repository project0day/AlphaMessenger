//! `alpha-database` — SQLCipher-encrypted local database for AlphaMessenger.
//!
//! This crate provides:
//! - **Pool management** ([`DatabasePool`]) for encrypted SQLite connections.
//! - **Migrations** applied automatically on open.
//! - **Model types** mirroring the database schema.
//! - **Repository pattern** CRUD for all tables.
//!
//! # Quick start
//!
//! ```no_run
//! use alpha_database::DatabasePool;
//! use std::path::Path;
//!
//! let pool = DatabasePool::open(Path::new("messenger.db"), "s3cr3t").unwrap();
//! let conn = pool.connection();
//! // ... use repositories with &conn
//! ```

pub mod backup_sql;
pub mod error;
pub mod group_roles;
pub mod policy;
pub mod comments;
pub mod whispers;
pub mod secret_media;
pub mod personas;
pub mod devices;
pub mod peer_relays;
pub mod receipts;
pub mod polls;
pub mod privacy;
pub mod stories;
pub mod migrations;
pub mod models;
pub mod pool;
pub mod repositories;
pub mod delivery;
pub mod early;

// ── Re-exports ─────────────────────────────────────────────────────────────

pub use error::{DatabaseError, Result};
pub use models::*;
pub use pool::DatabasePool;
pub use rusqlite::Connection;
pub use repositories::{
    ChatRepo, ChunkConfirmation, ContactRepo, KeyValueRepo, KeyPackageRepo, MediaSendKeyRepo, MediaSendObject, MediaSendObjectRepo, MessageRepo, MlsGroupRepo,
    MlsStorageRepo, OutboxEntry, OutboxRepo, PendingMedia, PendingMediaRepo, PendingSend,
    PendingSendRepo, ProcessedEventsRepo, SyncCheckpointRepo, new_chat, new_chat_member, new_contact, new_message,
    new_ephemeral_message, new_mls_group_row, new_mls_key_package_row, take_gone_media,
};
