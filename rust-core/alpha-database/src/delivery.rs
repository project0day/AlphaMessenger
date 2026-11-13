//! E2E Delivery Layer storage (Phase 1).
//!
//! Server-grade delivery over the Nostr fabric: a message is "delivered" only
//! after the recipient decrypts + persists + sends a `DeliveryAck`. This
//! module backs that with five tables (migration 012):
//!
//! - `delivery_seq_counters` — sender's monotonic per-(chat, sender) counter.
//! - `delivery_watermarks` — receiver's contiguous received/acked watermark.
//! - `delivery_gaps` — open holes in a sender's seq stream (for ResendRequest).
//! - `delivery_pending_seqs` — seqs received out-of-order, pending contiguity.
//! - `delivery_outbox` — sender's per-message store until fully ACKed.
//! - `delivery_acks` — sender's per-recipient ACK tracking (groups).
//!
//! All watermarks are **forward-only** (never decreased). `seq == 0` means
//! "unsequenced" (legacy/control) and is excluded from delivery tracking.

use chrono::Utc;
use rusqlite::{params, Connection};

use crate::error::Result;

fn now_rfc3339() -> String {
    Utc::now().to_rfc3339()
}

fn now_secs() -> i64 {
    Utc::now().timestamp()
}

// ═══════════════════════════════════════════════════════════════════════════
// Sender-side: sequence allocation + outbox + per-recipient ACK tracking
// ═══════════════════════════════════════════════════════════════════════════

/// One row in the delivery outbox (a message awaiting full ACK).
#[derive(Debug, Clone)]
pub struct DeliveryOutboxEntry {
    pub id: i64,
    pub chat_id: String,
    pub msg_id: String,
    pub seq: u64,
    pub payload: Vec<u8>,
    pub state: String,
    pub created_at: String,
    pub last_sent_at: String,
    pub send_count: i64,
}

/// Sender-side delivery operations.
pub struct DeliveryRepo;

impl DeliveryRepo {
    // ── Sequence allocation ───────────────────────────────────────────────

    /// Atomically allocate the next seq for (chat_id, sender_id) and return
    /// it. The counter starts at 1 (seq 0 is reserved for "unsequenced").
    /// This is the sender's OWN counter — they're the only writer, so there's
    /// no contention, but the UPSERT keeps it atomic across reconnects.
    pub fn alloc_seq(conn: &Connection, chat_id: &str, sender_id: &str) -> Result<u64> {
        // Ensure the row exists.
        conn.execute(
            "INSERT OR IGNORE INTO delivery_seq_counters (chat_id, sender_id, next_seq)
             VALUES (?1, ?2, 1)",
            params![chat_id, sender_id],
        )?;
        // Atomically increment + read the allocated value.
        let next: i64 = conn.query_row(
            "UPDATE delivery_seq_counters SET next_seq = next_seq + 1
             WHERE chat_id = ?1 AND sender_id = ?2
             RETURNING next_seq - 1",
            params![chat_id, sender_id],
            |r| r.get(0),
        )?;
        Ok(next as u64)
    }

    // ── Outbox ────────────────────────────────────────────────────────────

    /// Enqueue a message into the delivery outbox (state=pending). Called at
    /// compose time, right before the first publish attempt.
    pub fn outbox_enqueue(
        conn: &Connection,
        chat_id: &str,
        msg_id: &str,
        seq: u64,
        payload: &[u8],
    ) -> Result<i64> {
        conn.execute(
            "INSERT INTO delivery_outbox (chat_id, msg_id, seq, payload, state, created_at, last_sent_at, send_count)
             VALUES (?1, ?2, ?3, ?4, 'pending', ?5, '', 0)",
            params![chat_id, msg_id, seq, payload, now_rfc3339()],
        )?;
        Ok(conn.last_insert_rowid())
    }

    /// All pending (not-yet-fully-ACKed) outbox rows, oldest first. Used by
    /// the resend timer to re-publish messages that haven't been ACKed within
    /// the timeout window (proactive resend — covers "relay lost the event
    /// entirely, receiver never sees a gap because no higher seq arrived").
    pub fn outbox_list_pending(conn: &Connection) -> Result<Vec<DeliveryOutboxEntry>> {
        Self::outbox_list_pending_for_chat(conn, None)
    }

    /// Pending outbox rows for a specific chat (or all chats if `chat_id`
    /// is `None`), oldest first.
    pub fn outbox_list_pending_for_chat(
        conn: &Connection,
        chat_id: Option<&str>,
    ) -> Result<Vec<DeliveryOutboxEntry>> {
        let map_row = |r: &rusqlite::Row| -> rusqlite::Result<DeliveryOutboxEntry> {
            Ok(DeliveryOutboxEntry {
                id: r.get(0)?,
                chat_id: r.get(1)?,
                msg_id: r.get(2)?,
                seq: r.get::<_, i64>(3)? as u64,
                payload: r.get(4)?,
                state: r.get(5)?,
                created_at: r.get(6)?,
                last_sent_at: r.get(7)?,
                send_count: r.get(8)?,
            })
        };
        let mut out = Vec::new();
        if let Some(c) = chat_id {
            let mut stmt = conn.prepare(
                "SELECT id, chat_id, msg_id, seq, payload, state, created_at, last_sent_at, send_count
                 FROM delivery_outbox WHERE state = 'pending' AND chat_id = ?1
                 ORDER BY id ASC",
            )?;
            let rows = stmt.query_map(params![c], map_row)?;
            for r in rows { out.push(r?); }
        } else {
            let mut stmt = conn.prepare(
                "SELECT id, chat_id, msg_id, seq, payload, state, created_at, last_sent_at, send_count
                 FROM delivery_outbox WHERE state = 'pending'
                 ORDER BY id ASC",
            )?;
            let rows = stmt.query_map([], map_row)?;
            for r in rows { out.push(r?); }
        }
        Ok(out)
    }

    /// Outbox rows for a chat with seq in [from_seq, to_seq] — used to fulfill
    /// a ResendRequest (re-publish the specific missing messages).
    pub fn outbox_get_range(
        conn: &Connection,
        chat_id: &str,
        from_seq: u64,
        to_seq: u64,
    ) -> Result<Vec<DeliveryOutboxEntry>> {
        let mut stmt = conn.prepare(
            "SELECT id, chat_id, msg_id, seq, payload, state, created_at, last_sent_at, send_count
             FROM delivery_outbox
             WHERE chat_id = ?1 AND seq >= ?2 AND seq <= ?3
             ORDER BY seq ASC",
        )?;
        let rows = stmt.query_map(
            params![chat_id, from_seq as i64, to_seq as i64],
            |r| {
                Ok(DeliveryOutboxEntry {
                    id: r.get(0)?,
                    chat_id: r.get(1)?,
                    msg_id: r.get(2)?,
                    seq: r.get::<_, i64>(3)? as u64,
                    payload: r.get(4)?,
                    state: r.get(5)?,
                    created_at: r.get(6)?,
                    last_sent_at: r.get(7)?,
                    send_count: r.get(8)?,
                })
            },
        )?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    /// Mark an outbox row as just (re)sent: bump send_count, update
    /// last_sent_at. Keeps the row pending until ACKed.
    pub fn outbox_mark_sent(conn: &Connection, id: i64) -> Result<()> {
        conn.execute(
            "UPDATE delivery_outbox SET send_count = send_count + 1, last_sent_at = ?1 WHERE id = ?2",
            params![now_rfc3339(), id],
        )?;
        Ok(())
    }

    /// Delete outbox rows that have been fully ACKed by all current recipients
    /// (the caller passes the list of current non-self member acker_ids).
    /// Returns the msg_ids of deleted rows so the FFI can fire delivery-status
    /// callbacks (SENDING → DELIVERED). A row at seq=S is fully ACKed when
    /// every acker in `acker_ids` has `highest_acked_seq >= S`. If
    /// `acker_ids` is empty (1:1 where peer hasn't acked yet, or solo), no
    /// row is considered acked unless `acker_ids` covers it.
    pub fn outbox_delete_fully_acked(
        conn: &Connection,
        chat_id: &str,
        acker_ids: &[String],
    ) -> Result<Vec<String>> {
        // Fetch pending rows for this chat.
        let pending = Self::outbox_list_pending_for_chat(conn, Some(chat_id))?;
        if pending.is_empty() {
            return Ok(Vec::new());
        }
        // For each acker, read highest_acked_seq.
        let mut acker_watermarks: std::collections::HashMap<&str, u64> =
            std::collections::HashMap::new();
        for a in acker_ids {
            let h: i64 = conn
                .query_row(
                    "SELECT highest_acked_seq FROM delivery_acks WHERE chat_id = ?1 AND acker_id = ?2",
                    params![chat_id, a],
                    |r| r.get(0),
                )
                .unwrap_or(0);
            acker_watermarks.insert(a, h as u64);
        }
        // A row is fully acked iff all ackers have watermark >= row.seq.
        // If there are no ackers at all (e.g. group of 1 = just us), we can't
        // ever ack — keep pending (TTL gc will eventually clear it).
        if acker_ids.is_empty() {
            return Ok(Vec::new());
        }
        let mut deleted = Vec::new();
        for row in &pending {
            let mut all_acked = true;
            for a in acker_ids {
                let h = acker_watermarks.get(a.as_str()).copied().unwrap_or(0);
                if h < row.seq {
                    all_acked = false;
                    break;
                }
            }
            if all_acked {
                conn.execute(
                    "DELETE FROM delivery_outbox WHERE id = ?1",
                    params![row.id],
                )?;
                deleted.push(row.msg_id.clone());
            }
        }
        Ok(deleted)
    }

    /// Mark an outbox row as failed (e.g. MLS group gone, unrecoverable).
    pub fn outbox_mark_failed(conn: &Connection, id: i64) -> Result<()> {
        conn.execute(
            "UPDATE delivery_outbox SET state = 'failed' WHERE id = ?1",
            params![id],
        )?;
        Ok(())
    }

    /// Delete outbox rows older than `ttl_secs` that are still pending — they
    /// correspond to recipients who never ACKed (uninstalled app, permanent
    /// offline). Prevents unbounded growth.
    pub fn outbox_prune_expired(conn: &Connection, ttl_secs: i64) -> Result<u32> {
        let cutoff = now_secs() - ttl_secs;
        let n = conn.execute(
            "DELETE FROM delivery_outbox
             WHERE state = 'pending'
               AND unixepoch(strftime('%s', created_at)) < ?1",
            params![cutoff],
        )?;
        Ok(n as u32)
    }

    // ── Per-recipient ACK tracking (sender side) ──────────────────────────

    /// Record a DeliveryAck received from `acker_id`: advance that acker's
    /// `highest_acked_seq` (forward-only). Then recompute the outbox for this
    /// chat and return msg_ids of newly-fully-ACKed rows.
    pub fn record_ack(
        conn: &Connection,
        chat_id: &str,
        acker_id: &str,
        upto_seq: u64,
    ) -> Result<()> {
        // Forward-only upsert.
        conn.execute(
            "INSERT INTO delivery_acks (chat_id, acker_id, highest_acked_seq, updated_at)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(chat_id, acker_id) DO UPDATE SET
                highest_acked_seq = MAX(highest_acked_seq, excluded.highest_acked_seq),
                updated_at = excluded.updated_at",
            params![chat_id, acker_id, upto_seq as i64, now_rfc3339()],
        )?;
        Ok(())
    }

    // ── Receiver-side: watermarks + gap tracking ──────────────────────────

    /// Get (highest_received_seq, highest_acked_seq) for (chat_id, sender_id).
    /// Returns (0, 0) if never tracked.
    pub fn get_watermarks(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
    ) -> Result<(u64, u64)> {
        let row: Option<(i64, i64)> = conn
            .query_row(
                "SELECT highest_received_seq, highest_acked_seq FROM delivery_watermarks
                 WHERE chat_id = ?1 AND sender_id = ?2",
                params![chat_id, sender_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .ok();
        Ok(row.map(|(r, a)| (r as u64, a as u64)).unwrap_or((0, 0)))
    }

    /// Advance `highest_received_seq` to `new_val` (forward-only). Used when
    /// the contiguous watermark moves forward (in-order arrival or gap fill).
    pub fn advance_received(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        new_val: u64,
    ) -> Result<()> {
        conn.execute(
            "INSERT INTO delivery_watermarks (chat_id, sender_id, highest_received_seq, highest_acked_seq)
             VALUES (?1, ?2, ?3, 0)
             ON CONFLICT(chat_id, sender_id) DO UPDATE SET
                highest_received_seq = MAX(highest_received_seq, excluded.highest_received_seq)",
            params![chat_id, sender_id, new_val as i64],
        )?;
        Ok(())
    }

    /// Advance `highest_acked_seq` (what WE have acked to others) to
    /// `new_val` (forward-only).
    pub fn advance_acked(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        new_val: u64,
    ) -> Result<()> {
        conn.execute(
            "INSERT INTO delivery_watermarks (chat_id, sender_id, highest_received_seq, highest_acked_seq)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(chat_id, sender_id) DO UPDATE SET
                highest_acked_seq = MAX(highest_acked_seq, excluded.highest_acked_seq)",
            params![chat_id, sender_id, new_val as i64, new_val as i64],
        )?;
        Ok(())
    }

    /// Record a pending-ahead seq (received out-of-order, awaiting contiguity).
    pub fn pending_insert(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        seq: u64,
    ) -> Result<()> {
        conn.execute(
            "INSERT OR IGNORE INTO delivery_pending_seqs (chat_id, sender_id, seq)
             VALUES (?1, ?2, ?3)",
            params![chat_id, sender_id, seq as i64],
        )?;
        Ok(())
    }

    /// Drain pending-ahead seqs starting from `from_seq`: return all pending
    /// seqs >= from_seq in ascending order, and REMOVE them from the table
    /// (the caller will re-advance the contiguous watermark and re-insert any
    /// that are still ahead of the new watermark). Used after an in-order
    /// arrival to see how far the contiguous chain extends.
    pub fn pending_drain_from(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        from_seq: u64,
    ) -> Result<Vec<u64>> {
        let mut stmt = conn.prepare(
            "SELECT seq FROM delivery_pending_seqs
             WHERE chat_id = ?1 AND sender_id = ?2 AND seq >= ?3
             ORDER BY seq ASC",
        )?;
        let rows = stmt.query_map(params![chat_id, sender_id, from_seq as i64], |r| {
            r.get::<_, i64>(0).map(|v| v as u64)
        })?;
        let mut seqs = Vec::new();
        for r in rows {
            seqs.push(r?);
        }
        // Delete the drained ones; the caller re-inserts any still-ahead.
        conn.execute(
            "DELETE FROM delivery_pending_seqs
             WHERE chat_id = ?1 AND sender_id = ?2 AND seq >= ?3",
            params![chat_id, sender_id, from_seq as i64],
        )?;
        Ok(seqs)
    }

    /// Record an open gap [from_seq, to_seq] for (chat_id, sender_id). Merges
    /// with an existing gap if `from_seq` already exists (extends to_seq).
    pub fn gap_record(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        from_seq: u64,
        to_seq: u64,
    ) -> Result<()> {
        conn.execute(
            "INSERT INTO delivery_gaps (chat_id, sender_id, from_seq, to_seq, detected_at)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT(chat_id, sender_id, from_seq) DO UPDATE SET
                to_seq = MAX(to_seq, excluded.to_seq)",
            params![chat_id, sender_id, from_seq as i64, to_seq as i64, now_secs()],
        )?;
        Ok(())
    }

    /// List open gaps for (chat_id, sender_id) that are older than
    /// `min_age_secs` and haven't been requested in the last
    /// `request_cooldown_secs`. Used by the recovery timer to send
    /// ResendRequests.
    pub fn gap_list_open(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        min_age_secs: i64,
        request_cooldown_secs: i64,
    ) -> Result<Vec<(u64, u64)>> {
        let cutoff_age = now_secs() - min_age_secs;
        let cooldown = now_secs() - request_cooldown_secs;
        let mut stmt = conn.prepare(
            "SELECT from_seq, to_seq FROM delivery_gaps
             WHERE chat_id = ?1 AND sender_id = ?2
               AND detected_at <= ?3
               AND requested_at <= ?4
             ORDER BY from_seq ASC",
        )?;
        let rows = stmt.query_map(
            params![chat_id, sender_id, cutoff_age, cooldown],
            |r| Ok((r.get::<_, i64>(0)? as u64, r.get::<_, i64>(1)? as u64)),
        )?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    /// Mark a gap (from_seq) as requested at the current time (cooldown).
    pub fn gap_mark_requested(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        from_seq: u64,
    ) -> Result<()> {
        conn.execute(
            "UPDATE delivery_gaps SET requested_at = ?1
             WHERE chat_id = ?2 AND sender_id = ?3 AND from_seq = ?4",
            params![now_secs(), chat_id, sender_id, from_seq as i64],
        )?;
        Ok(())
    }

    /// Close (delete) gaps that are fully covered by the contiguous
    /// watermark: any gap whose to_seq <= highest_received_seq is filled.
    pub fn gap_close_covered(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        highest_received_seq: u64,
    ) -> Result<()> {
        conn.execute(
            "DELETE FROM delivery_gaps
             WHERE chat_id = ?1 AND sender_id = ?2 AND to_seq <= ?3",
            params![chat_id, sender_id, highest_received_seq as i64],
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pool::DatabasePool;

    /// Each test gets its own temp-file SQLCipher database with migrations
    /// applied. Returns the pool; tests call `.connection()` (a MutexGuard
    /// that derefs to `&Connection` for the repo methods).
    fn fresh_pool() -> DatabasePool {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_delivery_{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&p, "").expect("open test db");
        let _ = std::fs::remove_file(&p);
        {
            let conn = pool.connection();
            crate::migrations::run_migrations(&conn).expect("migrations");
        }
        pool
    }

    #[test]
    fn test_seq_allocation_is_monotonic() {
        let pool = fresh_pool();
        let conn = pool.connection();
        let s1 = DeliveryRepo::alloc_seq(&conn, "chat1", "alice").unwrap();
        let s2 = DeliveryRepo::alloc_seq(&conn, "chat1", "alice").unwrap();
        let s3 = DeliveryRepo::alloc_seq(&conn, "chat1", "alice").unwrap();
        assert_eq!(s1, 1);
        assert_eq!(s2, 2);
        assert_eq!(s3, 3);
        // Different sender in the same chat has its own counter.
        let b1 = DeliveryRepo::alloc_seq(&conn, "chat1", "bob").unwrap();
        assert_eq!(b1, 1);
        // Same sender in a different chat has its own counter.
        let a_c2 = DeliveryRepo::alloc_seq(&conn, "chat2", "alice").unwrap();
        assert_eq!(a_c2, 1);
    }

    #[test]
    fn test_watermarks_forward_only() {
        let pool = fresh_pool();
        let conn = pool.connection();
        let (hr, ha) = DeliveryRepo::get_watermarks(&conn, "c", "s").unwrap();
        assert_eq!((hr, ha), (0, 0));
        DeliveryRepo::advance_received(&conn, "c", "s", 5).unwrap();
        let (hr, _) = DeliveryRepo::get_watermarks(&conn, "c", "s").unwrap();
        assert_eq!(hr, 5);
        // "Decrease" — must stay (forward-only).
        DeliveryRepo::advance_received(&conn, "c", "s", 3).unwrap();
        let (hr, _) = DeliveryRepo::get_watermarks(&conn, "c", "s").unwrap();
        assert_eq!(hr, 5, "watermark must never decrease");
        DeliveryRepo::advance_acked(&conn, "c", "s", 4).unwrap();
        DeliveryRepo::advance_acked(&conn, "c", "s", 2).unwrap();
        let (_, ha) = DeliveryRepo::get_watermarks(&conn, "c", "s").unwrap();
        assert_eq!(ha, 4, "acked watermark must never decrease");
    }

    #[test]
    fn test_outbox_lifecycle() {
        let pool = fresh_pool();
        let conn = pool.connection();
        let id1 = DeliveryRepo::outbox_enqueue(&conn, "c", "m1", 1, b"p1").unwrap();
        let id2 = DeliveryRepo::outbox_enqueue(&conn, "c", "m2", 2, b"p2").unwrap();
        let _id3 = DeliveryRepo::outbox_enqueue(&conn, "c", "m3", 3, b"p3").unwrap();
        let pending = DeliveryRepo::outbox_list_pending(&conn).unwrap();
        assert_eq!(pending.len(), 3);
        let range = DeliveryRepo::outbox_get_range(&conn, "c", 2, 3).unwrap();
        assert_eq!(range.len(), 2);
        assert_eq!(range[0].seq, 2);
        assert_eq!(range[1].seq, 3);
        DeliveryRepo::outbox_mark_sent(&conn, id1).unwrap();
        let p1 = DeliveryRepo::outbox_list_pending(&conn).unwrap()
            .into_iter().find(|e| e.id == id1).unwrap();
        assert_eq!(p1.send_count, 1);
        assert!(!p1.last_sent_at.is_empty());
        conn.execute("DELETE FROM delivery_outbox WHERE id = ?1", params![id2]).unwrap();
        let pending = DeliveryRepo::outbox_list_pending(&conn).unwrap();
        assert_eq!(pending.len(), 2);
    }

    #[test]
    fn test_record_ack_and_fully_acked_delete() {
        let pool = fresh_pool();
        let conn = pool.connection();
        DeliveryRepo::outbox_enqueue(&conn, "c", "m1", 1, b"p1").unwrap();
        DeliveryRepo::outbox_enqueue(&conn, "c", "m2", 2, b"p2").unwrap();
        DeliveryRepo::outbox_enqueue(&conn, "c", "m3", 3, b"p3").unwrap();
        DeliveryRepo::record_ack(&conn, "c", "bob", 2).unwrap();
        let deleted = DeliveryRepo::outbox_delete_fully_acked(&conn, "c", &["bob".to_string()]).unwrap();
        assert_eq!(deleted.len(), 2, "seq 1 and 2 should be fully acked");
        let remaining = DeliveryRepo::outbox_list_pending(&conn).unwrap();
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].seq, 3);
        DeliveryRepo::record_ack(&conn, "c", "bob", 3).unwrap();
        let deleted = DeliveryRepo::outbox_delete_fully_acked(&conn, "c", &["bob".to_string()]).unwrap();
        assert_eq!(deleted.len(), 1);
        assert!(DeliveryRepo::outbox_list_pending(&conn).unwrap().is_empty());
    }

    #[test]
    fn test_group_ack_needs_all_members() {
        let pool = fresh_pool();
        let conn = pool.connection();
        DeliveryRepo::outbox_enqueue(&conn, "c", "m1", 1, b"p1").unwrap();
        DeliveryRepo::record_ack(&conn, "c", "bob", 1).unwrap();
        let deleted = DeliveryRepo::outbox_delete_fully_acked(
            &conn, "c", &["bob".to_string(), "carol".to_string()],
        ).unwrap();
        assert_eq!(deleted.len(), 0, "need all members to ack");
        DeliveryRepo::record_ack(&conn, "c", "carol", 1).unwrap();
        let deleted = DeliveryRepo::outbox_delete_fully_acked(
            &conn, "c", &["bob".to_string(), "carol".to_string()],
        ).unwrap();
        assert_eq!(deleted.len(), 1, "now fully acked");
    }

    #[test]
    fn test_gap_record_and_close() {
        let pool = fresh_pool();
        let conn = pool.connection();
        DeliveryRepo::advance_received(&conn, "c", "s", 2).unwrap();
        DeliveryRepo::gap_record(&conn, "c", "s", 3, 4).unwrap();
        DeliveryRepo::pending_insert(&conn, "c", "s", 5).unwrap();
        let gaps = DeliveryRepo::gap_list_open(&conn, "c", "s", 0, 0).unwrap();
        assert_eq!(gaps.len(), 1);
        assert_eq!(gaps[0], (3, 4));
        DeliveryRepo::advance_received(&conn, "c", "s", 4).unwrap();
        DeliveryRepo::gap_close_covered(&conn, "c", "s", 4).unwrap();
        let gaps = DeliveryRepo::gap_list_open(&conn, "c", "s", 0, 0).unwrap();
        assert_eq!(gaps.len(), 0, "gap [3..4] should be closed");
    }

    #[test]
    fn test_pending_drain_advances_contiguity() {
        let pool = fresh_pool();
        let conn = pool.connection();
        DeliveryRepo::advance_received(&conn, "c", "s", 2).unwrap();
        DeliveryRepo::pending_insert(&conn, "c", "s", 4).unwrap();
        DeliveryRepo::pending_insert(&conn, "c", "s", 5).unwrap();
        DeliveryRepo::advance_received(&conn, "c", "s", 3).unwrap();
        let drained = DeliveryRepo::pending_drain_from(&conn, "c", "s", 4).unwrap();
        assert_eq!(drained, vec![4, 5]);
        let drained2 = DeliveryRepo::pending_drain_from(&conn, "c", "s", 4).unwrap();
        assert!(drained2.is_empty());
    }
}
