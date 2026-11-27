//! Send resume: what the storage already accepted and what can be skipped.
//!
//! A broken link used to cost the whole file — the send restarted at byte
//! zero because accepted chunks were remembered nowhere. A measured case:
//! a 53 MB video lost connectivity at chunk 130 of 203 — 368 chunks went out instead of 203.
//!
//! What decides "send or skip" lives here:
//!
//!   * [`ConfirmedChunk`] — what is known about an accepted chunk;
//!   * [`ChunkCheckpoint`] — where it is written (implemented outside, in the
//!     core, on top of the encrypted DB; the DB is not pulled in here);
//!   * [`ConfirmationBatcher`] — when to write, so the disk is not hit per
//!     256 KiB, yet confirmations are not held in memory for minutes;
//!   * [`plan_chunk`] — the single place where the skip decision is made.
//!
//! THE MAIN RULE the module exists for: a chunk may be skipped ONLY on an
//! identity match — recomputed from the immutable source — against the identity
//! recorded after storage confirmation. Neither an address, nor a number, nor
//! "we seem to have sent this already" grants a skip.

use crate::media_ref::{ChunkLocation, StreamChunkUploadInfo, TransportObjectId};

/// A chunk accepted by the storage: the confirmation reached the disk.
///
/// Field completeness is a requirement, not convenience: the chunk record in
/// the final media ref is built from these. The recipient must not be able
/// to tell a resumed send from a fresh one by the ref's contents.
#[derive(Debug, Clone)]
pub struct ConfirmedChunk {
    pub index: u32,
    /// Total chunks in this output — for diagnostics and record integrity.
    pub total: u32,
    pub transport_object_id: TransportObjectId,
    pub plaintext_bytes: u64,
    pub ciphertext_bytes: u64,
    pub chunk_hash: String,
    pub locations: Vec<ChunkLocation>,
}

impl ConfirmedChunk {
    /// The chunk record for the final ref — the same one a freshly sent chunk
    /// gets. Skipped and new chunks must not differ in form: that is the
    /// indistinguishability of resume.
    pub fn to_upload_info(&self) -> StreamChunkUploadInfo {
        StreamChunkUploadInfo {
            locations: self.locations.clone(),
            ciphertext_size: self.ciphertext_bytes,
            original_size: self.plaintext_bytes,
            hash_hex: self.chunk_hash.clone(),
            transport_object_id: Some(self.transport_object_id.clone()),
        }
    }
}

/// Where confirmations are stored. Implemented by the core on top of the encrypted DB.
///
/// A separate behavior, not a direct DB dependency — so the media pipeline
/// does not drag storage along and stays testable without it.
pub trait ChunkCheckpoint: Send + Sync {
    /// Confirmed chunks of THIS send at THIS revision. The send identity
    /// (who, which element, which revision) is baked into the implementation
    /// and cannot be swapped from outside.
    fn load(&self) -> Vec<ConfirmedChunk>;

    /// Write a batch in ONE transaction. `Ok` means: the record would survive
    /// a process death right now.
    fn commit(&self, batch: &[ConfirmedChunk]) -> Result<(), String>;
}

/// How many chunks may wait for a write. Eight is 2 MiB of re-sending in the
/// worst case: cheaper than 8 separate disk flushes per 2 MiB of file.
pub const MAX_BATCH_CHUNKS: usize = 8;

/// How long a confirmation may wait for a write. On a slow network eight
/// chunks accumulate for minutes, and without this cap confirmations would
/// live only in memory — vanishing on disconnect exactly when most needed.
pub const MAX_BATCH_MILLIS: u64 = 2_000;

/// A confirmation accumulator with two thresholds: by count AND by time.
pub struct ConfirmationBatcher<'a> {
    sink: Option<&'a dyn ChunkCheckpoint>,
    pending: std::sync::Mutex<(Vec<ConfirmedChunk>, std::time::Instant)>,
}

impl<'a> ConfirmationBatcher<'a> {
    pub fn new(sink: Option<&'a dyn ChunkCheckpoint>) -> Self {
        Self {
            sink,
            pending: std::sync::Mutex::new((Vec::new(), std::time::Instant::now())),
        }
    }

    /// A chunk was accepted by storage. The write happens when either threshold hits.
    pub fn add(&self, c: ConfirmedChunk) {
        if self.sink.is_none() {
            return;
        }
        let ready = {
            let mut g = self.pending.lock().expect("batcher poisoned");
            g.0.push(c);
            g.0.len() >= MAX_BATCH_CHUNKS
        };
        if ready {
            self.flush();
        } else {
            self.flush_if_stale();
        }
    }

    /// Flush if a batch has sat too long. Also called before work on the next
    /// chunk begins: otherwise, on a slow network, the last confirmation would
    /// wait for the next one, which may be half a minute away.
    pub fn flush_if_stale(&self) {
        let stale = {
            let g = self.pending.lock().expect("batcher poisoned");
            !g.0.is_empty()
                && g.1.elapsed() >= std::time::Duration::from_millis(MAX_BATCH_MILLIS)
        };
        if stale {
            self.flush();
        }
    }

    /// Flush everything accumulated.
    ///
    /// A failed write does NOT abort the send: confirmations speed up the next
    /// attempt, they are not a condition of the current one's success. Chunks
    /// that were not written simply go out again after a disconnect, as before
    /// the checkpoint existed. Returning them to the accumulator is forbidden:
    /// a repeated failure would pile them up without limit.
    pub fn flush(&self) {
        let Some(sink) = self.sink else { return };
        let batch = {
            let mut g = self.pending.lock().expect("batcher poisoned");
            g.1 = std::time::Instant::now();
            std::mem::take(&mut g.0)
        };
        if batch.is_empty() {
            return;
        }
        let n = batch.len();
        match sink.commit(&batch) {
            Ok(()) => tracing::debug!(target: "alpha_media", "checkpoint: {n} chunks stored"),
            Err(e) => tracing::warn!(
                target: "alpha_media",
                "checkpoint: batch of {n} chunks not stored ({e}), they go again after a cut"
            ),
        }
    }
}

/// Decision for one chunk. The single place it is made.
#[derive(Debug)]
pub enum ChunkPlan {
    /// Exactly these bytes are already accepted by storage — no network.
    Skip(StreamChunkUploadInfo),
    /// Send. The string is why the checkpoint was not trusted (empty if there
    /// was none).
    Upload(&'static str),
}

/// Whether this chunk can be skipped.
///
/// Not a similarity check but identity: the object identity recomputed from
/// the reproduced chunk must equal the recorded one. Size and hash of the
/// original bytes are checked as well — they are already computed, cost
/// nothing, and catch the case where identity would match despite diverged
/// content (i.e. forgery or a corrupt record).
///
/// Any divergence — send. The old record is not trusted, and a silent skip
/// would be the worst outcome: the recipient would get a ref pointing at
/// chunks that do not exist.
pub fn plan_chunk(
    confirmed: Option<&ConfirmedChunk>,
    actual_id: &TransportObjectId,
    actual_plaintext_bytes: u64,
    actual_hash_hex: &str,
) -> ChunkPlan {
    let Some(c) = confirmed else {
        return ChunkPlan::Upload("");
    };
    if c.locations.is_empty() {
        return ChunkPlan::Upload("no addresses");
    }
    if &c.transport_object_id != actual_id {
        return ChunkPlan::Upload("identity mismatch");
    }
    if c.plaintext_bytes != actual_plaintext_bytes {
        return ChunkPlan::Upload("size mismatch");
    }
    if c.chunk_hash != actual_hash_hex {
        return ChunkPlan::Upload("hash mismatch");
    }
    ChunkPlan::Skip(c.to_upload_info())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    fn oid(seed: u8) -> TransportObjectId {
        TransportObjectId::of_bytes(&[seed; 16])
    }

    fn confirmed(index: u32, seed: u8) -> ConfirmedChunk {
        ConfirmedChunk {
            index,
            total: 3,
            transport_object_id: oid(seed),
            plaintext_bytes: 1000,
            ciphertext_bytes: 1040,
            chunk_hash: "aa".repeat(32),
            locations: vec![ChunkLocation {
                relay: "https://s".into(),
                url: format!("https://s/{}", oid(seed)),
            }],
        }
    }

    /// A recording disk: counts transactions and can "fail" before writing.
    #[derive(Default)]
    struct Disk {
        rows: Mutex<Vec<ConfirmedChunk>>,
        commits: Mutex<usize>,
        broken: Mutex<bool>,
    }

    impl ChunkCheckpoint for Disk {
        fn load(&self) -> Vec<ConfirmedChunk> {
            self.rows.lock().unwrap().clone()
        }
        fn commit(&self, batch: &[ConfirmedChunk]) -> Result<(), String> {
            if *self.broken.lock().unwrap() {
                return Err("disk unavailable".into());
            }
            *self.commits.lock().unwrap() += 1;
            self.rows.lock().unwrap().extend_from_slice(batch);
            Ok(())
        }
    }

    // --- skip decision ---

    #[test]
    fn skipped_only_on_a_full_match() {
        let c = confirmed(0, 1);
        let plan = plan_chunk(Some(&c), &oid(1), 1000, &"aa".repeat(32));
        assert!(matches!(plan, ChunkPlan::Skip(_)), "everything matches, nothing to send");
    }

    #[test]
    fn a_foreign_identity_gives_no_skip() {
        let c = confirmed(0, 1);
        let plan = plan_chunk(Some(&c), &oid(2), 1000, &"aa".repeat(32));
        assert!(matches!(plan, ChunkPlan::Upload(_)));
    }

    /// Revision changed — a different file, but rows from the old one remain.
    ///
    /// The guard here is not "we looked at the revision number" but that the
    /// new file's bytes yield a different identity. Even if an old record made
    /// it into the selection, it gets no skip.
    #[test]
    fn a_record_of_another_revision_does_not_apply() {
        let old = confirmed(0, 9);
        let plan = plan_chunk(Some(&old), &oid(10), 1000, &"aa".repeat(32));
        assert!(matches!(plan, ChunkPlan::Upload("identity mismatch")));
    }

    #[test]
    fn a_record_without_addresses_is_useless() {
        let mut c = confirmed(0, 1);
        c.locations.clear();
        let plan = plan_chunk(Some(&c), &oid(1), 1000, &"aa".repeat(32));
        assert!(matches!(plan, ChunkPlan::Upload("no addresses")));
    }

    #[test]
    fn a_corrupt_size_or_hash_cancels_the_skip() {
        let c = confirmed(0, 1);
        assert!(matches!(
            plan_chunk(Some(&c), &oid(1), 999, &"aa".repeat(32)),
            ChunkPlan::Upload("size mismatch")
        ));
        assert!(matches!(
            plan_chunk(Some(&c), &oid(1), 1000, &"bb".repeat(32)),
            ChunkPlan::Upload("hash mismatch")
        ));
    }

    #[test]
    fn without_a_checkpoint_everything_is_sent() {
        assert!(matches!(plan_chunk(None, &oid(1), 1000, "x"), ChunkPlan::Upload("")));
    }

    // --- when confirmations reach the disk ---

    #[test]
    fn a_batch_is_stored_by_chunk_count() {
        let disk = Arc::new(Disk::default());
        let b = ConfirmationBatcher::new(Some(disk.as_ref()));
        for i in 0..MAX_BATCH_CHUNKS as u32 {
            b.add(confirmed(i, i as u8));
        }
        assert_eq!(*disk.commits.lock().unwrap(), 1, "the count threshold must fire");
        assert_eq!(disk.load().len(), MAX_BATCH_CHUNKS);
    }

    /// The time threshold. Without it, on a slow network a confirmation would
    /// sit in memory until the next chunk — i.e. vanish on disconnect.
    #[test]
    fn a_batch_is_stored_by_time_without_waiting_for_the_count() {
        let disk = Arc::new(Disk::default());
        let b = ConfirmationBatcher::new(Some(disk.as_ref()));
        b.add(confirmed(0, 0));
        assert_eq!(*disk.commits.lock().unwrap(), 0, "a single chunk waits");

        std::thread::sleep(std::time::Duration::from_millis(MAX_BATCH_MILLIS + 50));
        b.flush_if_stale();
        assert_eq!(*disk.commits.lock().unwrap(), 1, "a stale batch must go to disk");
        assert_eq!(disk.load().len(), 1);
    }

    /// Failure BEFORE the write: no confirmation, the chunk goes out again. Safe.
    #[test]
    fn a_crash_before_storing_loses_only_the_unstored() {
        let disk = Arc::new(Disk::default());
        {
            let b = ConfirmationBatcher::new(Some(disk.as_ref()));
            for i in 0..MAX_BATCH_CHUNKS as u32 {
                b.add(confirmed(i, i as u8));
            }
            // Three more confirmations never reached the threshold — "process died".
            for i in 100..103u32 {
                let mut g = b.pending.lock().unwrap();
                g.0.push(confirmed(i, i as u8));
            }
            std::mem::forget(b);
        }
        let durable = disk.load();
        assert_eq!(durable.len(), MAX_BATCH_CHUNKS, "only the stored batch is on disk");
        assert!(durable.iter().all(|c| c.index < 100), "the unstored must not survive");
    }

    /// Failure AFTER the write: the chunk never goes to the network again.
    #[test]
    fn a_crash_after_storing_cancels_the_resend() {
        let disk = Arc::new(Disk::default());
        {
            let b = ConfirmationBatcher::new(Some(disk.as_ref()));
            b.add(confirmed(5, 5));
            b.flush();
        }
        let durable = disk.load();
        let c = durable.iter().find(|c| c.index == 5).expect("the confirmation must survive the crash");
        assert!(matches!(
            plan_chunk(Some(c), &oid(5), 1000, &"aa".repeat(32)),
            ChunkPlan::Skip(_)
        ));
    }

    /// The disk does not write — the send must continue, just without the speedup.
    #[test]
    fn a_failed_store_does_not_break_the_send() {
        let disk = Arc::new(Disk::default());
        *disk.broken.lock().unwrap() = true;
        let b = ConfirmationBatcher::new(Some(disk.as_ref()));
        for i in 0..MAX_BATCH_CHUNKS as u32 * 2 {
            b.add(confirmed(i, i as u8));
        }
        b.flush();
        assert_eq!(disk.load().len(), 0);
        // The accumulator does not grow without limit under constant failure.
        assert!(b.pending.lock().unwrap().0.len() < MAX_BATCH_CHUNKS);
    }

    /// The record of a skipped chunk is indistinguishable from a freshly
    /// sent one: same fields, same meaning.
    #[test]
    fn a_skipped_chunk_gives_a_full_record_for_the_ref() {
        let c = confirmed(3, 3);
        let info = c.to_upload_info();
        assert!(!info.locations.is_empty());
        assert_eq!(info.original_size, c.plaintext_bytes);
        assert_eq!(info.ciphertext_size, c.ciphertext_bytes);
        assert_eq!(info.hash_hex, c.chunk_hash);
        assert_eq!(info.transport_object_id.as_ref(), Some(&c.transport_object_id));
    }
}
