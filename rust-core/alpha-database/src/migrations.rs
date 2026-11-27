//! Full SQL migration definitions for the AlphaMessenger database.
//!
//! Migrations are applied in order when the database is first opened.
//! Each migration is identified by a version number.

use crate::error::{DatabaseError, Result};
use rusqlite::Connection;
use tracing::info;

/// A single migration step.
struct Migration {
    version: u64,
    description: &'static str,
    sql: &'static str,
}

/// All migrations in order.
fn migrations() -> Vec<Migration> {
    vec![
        Migration {
            version: 1,
            description: "Initial schema",
            sql: include_str!("migrations/001_initial.sql"),
        },
        Migration {
            version: 2,
            description: "Ephemeral message support (STAGE D)",
            sql: include_str!("migrations/002_ephemeral.sql"),
        },
        Migration {
            version: 3,
            description: "MLS persistent storage",
            sql: include_str!("migrations/003_mls_storage.sql"),
        },
        Migration {
            version: 4,
            description: "Chat-list flags: pin/mute/archive/unread",
            sql: include_str!("migrations/004_chat_flags.sql"),
        },
        Migration {
            version: 5,
            description: "Contact profile fields: bio/avatar/version",
            sql: include_str!("migrations/005_contact_profile.sql"),
        },
        Migration {
            version: 6,
            description: "Group metadata: avatar/description/version",
            sql: include_str!("migrations/006_group_metadata.sql"),
        },
        Migration {
            version: 7,
            description: "Outbox: guaranteed-delivery retry queue",
            sql: include_str!("migrations/007_outbox.sql"),
        },
        Migration {
            version: 8,
            description: "Pending sends: defer-encryption outgoing queue",
            sql: include_str!("migrations/008_pending_sends.sql"),
        },
        Migration {
            version: 10,
            description: "Pending media: offline media send queue",
            sql: include_str!("migrations/010_pending_media.sql"),
        },
        Migration {
            version: 11,
            description: "Processed-event dedup (stop SecretReuse re-decrypt floods)",
            sql: include_str!("migrations/011_processed_events.sql"),
        },
        Migration {
            version: 12,
            description: "E2E delivery layer: seq counters, ACK watermarks, gaps, outbox (Phase 1)",
            sql: include_str!("migrations/012_delivery_layer.sql"),
        },
        Migration {
            version: 13,
            description: "Consumed KeyPackages (survive restart, prevent NoMatchingKeyPackage)",
            sql: include_str!("migrations/013_consumed_key_packages.sql"),
        },
        Migration {
            version: 14,
            description: "Pending-media upload state machine (status/reason/attempts → Failed+Retry)",
            sql: include_str!("migrations/014_pending_media_status.sql"),
        },
        Migration {
            version: 15,
            description: "Pending-media: remember blobs are uploaded → retry publishes only",
            sql: include_str!("migrations/015_pending_media_blob_state.sql"),
        },
        Migration {
            version: 16,
            description: "TD-003: persist delivered_at so DELIVERED survives reload",
            sql: include_str!("migrations/016_delivered_at.sql"),
        },
        Migration {
            version: 17,
            description: "T3: media_send_keys — keys to reproduce the same ciphertext",
            sql: include_str!("migrations/017_media_send_keys.sql"),
        },
        Migration {
            version: 18,
            description: "T3: media_send_objects — trusted path and expected identity",
            sql: include_str!("migrations/018_media_send_objects.sql"),
        },
        Migration {
            version: 19,
            description: "T4: checkpoint of the chunks the storage confirmed",
            sql: include_str!("migrations/019_media_send_checkpoint.sql"),
        },
        Migration {
            version: 20,
            description: "T4: one queue row per message and one send owner",
            sql: include_str!("migrations/020_pending_media_single_owner.sql"),
        },
        Migration {
            version: 21,
            description: "P1: delivery state per recipient (NIP-59 envelopes)",
            sql: include_str!("migrations/021_delivery_fanout.sql"),
        },
        Migration {
            version: 22,
            description: "Comment threads: which post each comment discusses",
            sql: include_str!("migrations/022_comments.sql"),
        },
        Migration {
            version: 23,
            description: "Whispers: read under the finger, then burned",
            sql: include_str!("migrations/023_whispers.sql"),
        },
        Migration {
            version: 24,
            description: "Personas: who we are in an anonymous channel",
            sql: include_str!("migrations/024_personas.sql"),
        },
        Migration {
            version: 25,
            description: "Personas: the leaf key beside the keys",
            sql: include_str!("migrations/025_persona_sig.sql"),
        },
        Migration {
            version: 26,
            description: "Delivery receipts: who has received our messages",
            sql: include_str!("migrations/026_receipts.sql"),
        },
        Migration {
            version: 27,
            description: "Polls: votes and closed polls",
            sql: include_str!("migrations/027_polls.sql"),
        },
        Migration {
            version: 28,
            description: "Reserved (withdrawn invite keys; tables unused)",
            sql: include_str!("migrations/028_invite_keys.sql"),
        },
        Migration {
            version: 29,
            description: "Timed view of whisper media",
            sql: include_str!("migrations/029_whisper_glance.sql"),
        },
        Migration {
            version: 30,
            description: "Full-text message search",
            sql: include_str!("migrations/030_message_search.sql"),
        },
        Migration {
            version: 31,
            description: "Attachment kind and name; search over both",
            sql: include_str!("migrations/031_media_kind.sql"),
        },
        Migration {
            version: 32,
            description: "Files by kind: documents, apps, archives",
            sql: include_str!("migrations/032_file_kinds.sql"),
        },
        Migration {
            version: 33,
            description: "Circles, GIFs, places and polls by kind",
            sql: include_str!("migrations/033_more_kinds.sql"),
        },
        Migration {
            version: 34,
            description: "Albums by what they hold",
            sql: include_str!("migrations/034_album_kinds.sql"),
        },
        Migration {
            version: 35,
            description: "Messages sent without sound",
            sql: include_str!("migrations/035_silent.sql"),
        },
    ]
}

/// Run all pending migrations on the given connection.
pub fn run_migrations(conn: &Connection) -> Result<()> {
    // Ensure the schema migrations tracking table exists.
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY,
            description TEXT NOT NULL,
            applied_at TEXT NOT NULL
        );",
    )?;

    let current_version: u64 = conn
        .query_row(
            "SELECT COALESCE(MAX(version), 0) FROM schema_migrations",
            [],
            |row| row.get(0),
        )
        .unwrap_or(0);

    for migration in migrations() {
        if migration.version > current_version {
            info!(
                "Applying migration v{}: {}",
                migration.version, migration.description
            );

            conn.execute_batch(migration.sql).map_err(|e| {
                DatabaseError::MigrationFailed {
                    version: migration.version,
                    message: e.to_string(),
                }
            })?;

            let now = chrono::Utc::now().to_rfc3339();
            conn.execute(
                "INSERT INTO schema_migrations (version, description, applied_at) VALUES (?1, ?2, ?3)",
                rusqlite::params![migration.version, migration.description, now],
            )?;

            info!("Migration v{} applied successfully", migration.version);
        }
    }

    Ok(())
}
