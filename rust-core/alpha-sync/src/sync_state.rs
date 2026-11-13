//! Sync state tracking with per-relay checkpoints.
//!
//! Each relay gets its own checkpoint (last seen event ID and timestamp).
//! Checkpoints are persisted to the `sync_checkpoints` database table
//! via [`alpha_database::SyncCheckpointRepo`] and loaded on startup
//! so that catch-up can resume from the last known position.

use alpha_database::{DatabasePool, SyncCheckpoint, SyncCheckpointRepo};
use std::collections::HashMap;
use std::sync::Arc;

use crate::error::Result;

/// Tracks sync state across all relays.
///
/// Internally caches checkpoints in memory and persists changes
/// to the database on every update using [`SyncCheckpointRepo`].
pub struct SyncState {
    /// Underlying database pool.
    db: Arc<DatabasePool>,
    /// In-memory cache: relay_url → checkpoint.
    checkpoints: HashMap<String, SyncCheckpoint>,
}

impl SyncState {
    /// Load sync state from the database.
    ///
    /// Reads all existing `sync_checkpoints` rows into the in-memory cache
    /// via [`SyncCheckpointRepo::list`].
    pub fn new(db: &Arc<DatabasePool>) -> Result<Self> {
        let conn = db.connection();
        let all = SyncCheckpointRepo::list(&conn)?;

        let checkpoints: HashMap<String, SyncCheckpoint> = all
            .into_iter()
            .map(|cp| (cp.relay_url.clone(), cp))
            .collect();

        Ok(Self {
            db: Arc::clone(db),
            checkpoints,
        })
    }

    /// Get the checkpoint for a specific relay, if one exists.
    pub fn checkpoint(&self, relay_url: &str) -> Option<&SyncCheckpoint> {
        self.checkpoints.get(relay_url)
    }

    /// Update (or insert) the checkpoint for a relay.
    ///
    /// The new timestamp must be >= the current one; otherwise the
    /// update is silently ignored (stale / out-of-order).
    pub fn update_checkpoint(
        &mut self,
        relay_url: &str,
        event_id: &str,
        timestamp: u64,
    ) -> Result<()> {
        // Only advance the checkpoint forward.
        if let Some(existing) = self.checkpoints.get(relay_url) {
            if timestamp as i64 <= existing.last_timestamp {
                tracing::trace!(
                    "Ignoring stale checkpoint for {}: ts={} <= existing={}",
                    relay_url,
                    timestamp,
                    existing.last_timestamp,
                );
                return Ok(());
            }
        }

        let conn = self.db.connection();
        SyncCheckpointRepo::upsert(&conn, relay_url, Some(event_id), timestamp as i64)?;

        // Update in-memory cache. We need to re-read because `upsert` sets
        // `updated_at` server-side. For simplicity, construct a local model.
        let cp = SyncCheckpoint {
            relay_url: relay_url.to_string(),
            last_event_id: Some(event_id.to_string()),
            last_timestamp: timestamp as i64,
            updated_at: chrono::Utc::now().to_rfc3339(),
        };
        self.checkpoints.insert(relay_url.to_string(), cp);

        tracing::debug!(
            "Updated checkpoint for {}: event_id={}, ts={}",
            relay_url,
            event_id,
            timestamp,
        );

        Ok(())
    }

    /// Return all known relay URLs that have checkpoints.
    pub fn relay_urls(&self) -> impl Iterator<Item = &String> {
        self.checkpoints.keys()
    }
}
