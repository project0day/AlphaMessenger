use tracing::{debug, info, instrument};

use crate::blossom::BlossomClient;
use crate::chunker::EncryptedChunker;
use crate::crypto::{generate_key, blake3_hash, MediaCrypto, StreamCrypto, EncryptedStreamChunk};
use crate::error::{MediaError, MediaResult};
use crate::media_ref::{UploadResult, StreamChunkUploadInfo, ChunkLocation};
use crate::thumbnail::{MediaType, ThumbnailGenerator};

/// How often an upload's progress moves between confirmations.
const SHOW_EVERY: std::time::Duration = std::time::Duration::from_millis(250);

/// What the user sees of an upload: confirmed bytes, plus what the link has carried since
/// the last confirmation at the rate this upload has measured (bytes confirmed over the time
/// spent), never more than nine tenths of what is in flight. The window keeps several
/// seconds of chunks in flight and they share the link, so confirmations alone come in
/// bursts; crediting each chunk by its own elapsed time counted a whole wave as nearly done
/// when its first chunk landed. The estimate stops when nothing has landed for a chunk's
/// time or the network changed; the shown value never goes back, and the end is shown only
/// after the last confirmation.
struct SendProgress {
    total: u64,
    confirmed: std::sync::atomic::AtomicU64,
    shown: std::sync::atomic::AtomicU64,
    chunk_ms: std::sync::atomic::AtomicU64,
    origin: std::time::Instant,
    /// Since `origin`, when a chunk last landed or set out.
    active_ms: std::sync::atomic::AtomicU64,
    /// Bytes confirmed by this run (not the resumed ones), the first departure and the last
    /// landing since `origin`: the measured rate.
    landed: std::sync::atomic::AtomicU64,
    first_ms: std::sync::atomic::AtomicU64,
    last_land_ms: std::sync::atomic::AtomicU64,
    flying: std::sync::Mutex<std::collections::HashMap<u32, (std::time::Instant, u64, u64)>>,
}

/// A chunk on its way; leaves the estimate when dropped, whether it landed or failed.
struct Flight<'a> {
    of: &'a SendProgress,
    idx: u32,
    started: std::time::Instant,
    bytes: u64,
}

impl Drop for Flight<'_> {
    fn drop(&mut self) {
        self.of.flying.lock().unwrap_or_else(|p| p.into_inner()).remove(&self.idx);
    }
}

impl SendProgress {
    fn new(total: u64, confirmed: u64) -> Self {
        Self {
            total,
            confirmed: confirmed.min(total).into(),
            shown: 0.into(),
            chunk_ms: 0.into(),
            origin: std::time::Instant::now(),
            active_ms: 0.into(),
            landed: 0.into(),
            first_ms: u64::MAX.into(),
            last_land_ms: 0.into(),
            flying: Default::default(),
        }
    }

    fn now_ms(&self) -> u64 {
        self.origin.elapsed().as_millis() as u64
    }

    fn mark_active(&self) {
        self.active_ms.store(self.now_ms(), std::sync::atomic::Ordering::Relaxed);
    }

    fn fly(&self, idx: u32, bytes: u64) -> Flight<'_> {
        use std::sync::atomic::Ordering::Relaxed;
        let started = std::time::Instant::now();
        let epoch = crate::link::network_epoch();
        self.flying.lock().unwrap_or_else(|p| p.into_inner()).insert(idx, (started, bytes, epoch));
        let _ = self.first_ms.compare_exchange(u64::MAX, self.now_ms(), Relaxed, Relaxed);
        self.mark_active();
        Flight { of: self, idx, started, bytes }
    }

    fn land(&self, flight: Flight<'_>) {
        use std::sync::atomic::Ordering::Relaxed;
        let (bytes, ms) = (flight.bytes, flight.started.elapsed().as_millis().max(1) as u64);
        drop(flight);
        self.confirmed.fetch_add(bytes, Relaxed);
        self.landed.fetch_add(bytes, Relaxed);
        self.last_land_ms.store(self.now_ms(), Relaxed);
        self.mark_active();
        let _ = self.chunk_ms.fetch_update(Relaxed, Relaxed, |old| Some(if old == 0 { ms } else { (old * 3 + ms) / 4 }));
    }

    fn estimate(&self) -> u64 {
        use std::sync::atomic::Ordering::Relaxed;
        let confirmed = self.confirmed.load(Relaxed).min(self.total);
        let (landed, first, last) = (self.landed.load(Relaxed), self.first_ms.load(Relaxed), self.last_land_ms.load(Relaxed));
        let pace = self.chunk_ms.load(Relaxed);
        if landed == 0 || pace == 0 || last <= first {
            return confirmed;
        }
        // Time counts only up to a chunk's pace past the last activity: a link that stopped
        // landing anything stops the estimate with it.
        let now = self.now_ms().min(self.active_ms.load(Relaxed) + pace);
        let carried = (landed as u128 * now.saturating_sub(last) as u128 / (last - first) as u128) as u64;
        let epoch = crate::link::network_epoch();
        // Nine tenths at most: the rest of a chunk is the server's answer, not the link's.
        let flying: u64 = self.flying.lock().unwrap_or_else(|p| p.into_inner()).values()
            .filter(|(_, _, e)| *e == epoch)
            .map(|(_, b, _)| b * 9 / 10)
            .sum();
        (confirmed + carried.min(flying)).min(self.total.saturating_sub(1)).max(confirmed)
    }

    fn show(&self, on_progress: &(dyn Fn(u64, u64) + Sync)) {
        let now = self.estimate();
        if self.shown.fetch_max(now, std::sync::atomic::Ordering::Relaxed) < now {
            on_progress(now, self.total);
        }
    }
}

#[cfg(test)]
mod send_progress_tests {
    use super::SendProgress;
    use std::time::Duration;

    #[test]
    fn moves_between_confirmations_and_never_back() {
        let p = SendProgress::new(1_000, 0);
        let seen = std::sync::Mutex::new(Vec::new());
        let report = |d: u64, _t: u64| seen.lock().unwrap().push(d);
        let first = p.fly(0, 100);
        std::thread::sleep(Duration::from_millis(20));
        p.land(first);
        p.show(&report);
        let flying = p.fly(1, 100);
        std::thread::sleep(Duration::from_millis(15));
        p.show(&report);
        drop(flying);
        p.show(&report);
        let seen = seen.into_inner().unwrap();
        assert_eq!(seen[0], 100);
        assert!(seen.len() == 2 && seen[1] > 100 && seen[1] < 200, "in-flight share shows: {seen:?}");
    }

    #[test]
    fn the_estimate_stops_when_nothing_lands() {
        let p = SendProgress::new(10_000, 0);
        let first = p.fly(0, 100);
        std::thread::sleep(Duration::from_millis(20));
        p.land(first);
        let _stuck = p.fly(1, 1_000);
        std::thread::sleep(Duration::from_millis(80));
        let a = p.estimate();
        std::thread::sleep(Duration::from_millis(80));
        assert_eq!(p.estimate(), a, "a link that lands nothing shows no movement");
    }

    #[test]
    fn a_wave_in_flight_is_not_counted_done_when_its_first_chunk_lands() {
        let p = SendProgress::new(100_000, 0);
        let mut wave: Vec<_> = (0..20).map(|i| p.fly(i, 1_000)).collect();
        std::thread::sleep(Duration::from_millis(50));
        p.land(wave.remove(0));
        let shown = p.estimate();
        assert!(shown < 3_000, "19 chunks still in flight count as moving at the measured rate, not as done: {shown}");
    }

    #[test]
    fn the_end_waits_for_the_last_confirmation() {
        let p = SendProgress::new(200, 100);
        let first = p.fly(0, 10);
        p.land(first);
        let _last = p.fly(1, 90);
        std::thread::sleep(Duration::from_millis(30));
        assert!(p.estimate() < 200);
    }
}

/// Thumbnail maximum dimension in pixels for generated thumbnails.
const THUMBNAIL_MAX_SIZE: u32 = 320;

/// Number of chunks uploaded concurrently.
/// Balances throughput against relay rate-limits and mobile memory.
pub(crate) const UPLOAD_CONCURRENCY: usize = 24;


/// PER-FILE cap on chunks in flight. A single file may hold at most this many of
/// the GLOBAL chunk slots, so one 300 MB file can never starve small photos/videos.
const DOWNLOAD_CONCURRENCY: usize = 24;

/// Read-ahead window of the ordered streaming download: chunks may finish out
/// of order up to this far ahead, so one slow chunk does not idle the link
/// while it is retried. RAM: O(window × chunk) ≈ 24 MB.
const DOWNLOAD_WINDOW: usize = 96;

/// GLOBAL chunk-slot pool — the shared limit on concurrently-downloading chunks
/// across ALL files at once. This is the download engine's real throttle: every
/// file starts immediately (no file-level queue → nothing sits at 0%), and their
/// chunks compete fairly for these slots, so aggregate CDN load stays bounded no
/// matter how many files download. A freed slot goes to whatever chunk waits next.
pub(crate) const GLOBAL_CHUNK_SLOTS: usize = 32;

fn chunk_slots() -> &'static tokio::sync::Semaphore {
    static SLOTS: std::sync::OnceLock<tokio::sync::Semaphore> = std::sync::OnceLock::new();
    SLOTS.get_or_init(|| tokio::sync::Semaphore::new(GLOBAL_CHUNK_SLOTS))
}

/// GLOBAL upload-slot pool — the shared limit on concurrently-uploading chunks
/// across ALL files and ALL messages at once.
///
/// The limit used to sit at FILE granularity on the Android side: a permit was
/// taken for an entire upload, so a 50 MB video held it for tens of seconds and
/// a small file queued behind it sat at 0% while the link idled between chunks.
///
/// Here the permit wraps ONE request: take it, send the chunk, release. A big
/// file and a small one compete chunk-by-chunk on equal terms, and — crucially —
/// this pool is the ONLY throttle, so `UPLOAD_CONCURRENCY` is now just a per-file
/// ceiling that cannot multiply with it (4 files × 4 chunks stays 4 in flight,
/// not 16). A freed slot goes to whichever chunk has waited longest, so an
/// 8-item album cannot monopolise the network.
/// Each chunk's socket sits idle while the server stores it, so a saturated cellular
/// uplink (16 in flight, ~3.5 s each) still wanted ~22; the link window keeps a slower
/// link to fewer.
pub(crate) const GLOBAL_UPLOAD_SLOTS: usize = 24;

pub(crate) fn upload_slots() -> &'static tokio::sync::Semaphore {
    static SLOTS: std::sync::OnceLock<tokio::sync::Semaphore> = std::sync::OnceLock::new();
    SLOTS.get_or_init(|| tokio::sync::Semaphore::new(GLOBAL_UPLOAD_SLOTS))
}

/// Max attempts for a SINGLE chunk before the whole transfer fails. A flaky
/// chunk is retried on its own (exp backoff + jitter) instead of restarting the
/// entire file — the big win for large uploads/downloads on mobile networks.
const MAX_CHUNK_ATTEMPTS: usize = 4;

/// Time cap on ONE chunk including all its attempts and pauses.
///
/// Why it exists: on a mid-upload disconnect, chunks caught in the outage
/// window failed while one in-flight request hung forever. The chunk stream
/// cannot finish while a single request hangs, so the send neither completed
/// nor failed — it froze forever, and the whole retry machinery never ran.
///
/// Each request is already cut when it stops moving; this is only the last-resort
/// bound, wide enough for a chunk crawling over a very weak link.
const CHUNK_DEADLINE: std::time::Duration = std::time::Duration::from_secs(5 * 60);

/// Sleep between chunk retry attempts: exponential backoff (200ms·2^n, capped
/// at 3s) with up to 50% random jitter to avoid thundering-herd on the relay.
async fn chunk_backoff(attempt: usize) {
    use rand::Rng;
    let shift = attempt.saturating_sub(1).min(5);
    let base_ms = (200u64.saturating_mul(1u64 << shift)).min(3000);
    let jitter = rand::thread_rng().gen_range(0..=(base_ms / 2).max(1));
    tokio::time::sleep(std::time::Duration::from_millis(base_ms + jitter)).await;
}

/// Upload one blob with bounded per-chunk retry. Returns the blob URL.
async fn upload_blob_retry(
    blossom: &BlossomClient,
    data: &[u8],
    signer: &crate::blossom::MediaSigner,
    content_type: &str,
) -> MediaResult<String> {
    let mut attempt = 0usize;
    loop {
        // The permit covers the request and NOTHING else: it is dropped at the
        // end of this block, so the backoff sleep below waits without a slot.
        let result = {
            let _permit = upload_slots()
                .acquire()
                .await
                .map_err(|_| MediaError::UploadFailed("upload slots closed".into()))?;
            blossom.upload(data, signer, content_type).await
        };
        match result {
            Ok(d) => return Ok(d.url),
            Err(e) => {
                attempt += 1;
                if attempt >= MAX_CHUNK_ATTEMPTS {
                    debug!(attempt, "chunk upload failed after retries: {e}");
                    return Err(e);
                }
                debug!(attempt, "chunk upload retry after error: {e}");
                chunk_backoff(attempt).await;
            }
        }
    }
}

/// Replicate ONE blob across mirrors with bounded retry of the whole step.
/// Returns the chunk's [`ChunkLocation`]s (≥ `required`). This is the upload
/// counterpart to [`download_blob_locations`]'s fallback.
async fn upload_blob_mirrored(
    blossom: &BlossomClient,
    data: &[u8],
    signer: &crate::blossom::MediaSigner,
    content_type: &str,
) -> MediaResult<Vec<ChunkLocation>> {
    // Critical path: one copy per usable server. Redundancy is the
    // background replicator's job — it owes no foreign waiting.
    let policy = crate::blossom::ReplicationPolicy::primary();
    let mut attempt = 0usize;
    loop {
        // Same rule as the single-server path: the slot is held for the network
        // step only, never across the backoff.
        let result = {
            let _permit = upload_slots()
                .acquire()
                .await
                .map_err(|_| MediaError::UploadFailed("upload slots closed".into()))?;
            blossom.upload_mirrored(data, signer, content_type, policy).await
        };
        match result {
            Ok(locs) => return Ok(locs),
            Err(e @ MediaError::Refused(_)) => return Err(e),
            Err(e) => {
                attempt += 1;
                if attempt >= MAX_CHUNK_ATTEMPTS {
                    return Err(e);
                }
                chunk_backoff(attempt).await;
            }
        }
    }
}

/// Strategy for choosing among a chunk's mirror [`ChunkLocation`]s on download.
#[derive(Debug, Clone, Copy)]
pub enum DownloadPolicy {
    /// Round-robin from the balanced start mirror; a failure moves to the next
    /// mirror (then cycles) with backoff. Hedged/RTT-aware selection is a deliberate
    /// LATER optimization, layered on top of the global chunk scheduler.
    RoundRobin,
}

/// Download one chunk from ANY of its mirrors, round-robining from `start_at` (the
/// chunk index) so consecutive chunks hit different servers — load is spread across
/// every mirror from the first request instead of hammering `locations[0]`.
async fn download_blob_locations(
    blossom: &BlossomClient,
    explicit_id: Option<&crate::media_ref::TransportObjectId>,
    locations: &[ChunkLocation],
    start_at: usize,
    _policy: DownloadPolicy,
) -> MediaResult<Vec<u8>> {
    use futures::stream::{FuturesUnordered, StreamExt};
    if locations.is_empty() {
        return Err(MediaError::NoChunkUrls);
    }
    let listed = locations.len();

    #[derive(Clone)]
    struct Source {
        url: String,
        relay: String,
        /// Set for a replica found by content hash (not listed in the ref).
        discovered: Option<String>,
    }
    // Listed hints, round-robin from the chunk index so consecutive chunks
    // hit different servers. A server only the SENDER named is never contacted
    // without Tor: whoever runs it would learn every reader's IP address.
    // The copies on servers we know are found by content hash below.
    let anonymous = blossom.via_tor();
    let mut skipped_unknown = false;
    let mut sources: Vec<Source> = (0..listed)
        .map(|k| &locations[(start_at + k) % listed])
        // The project's servers, switched off, are not asked even where a ref names them.
        .filter(|l| crate::blossom::PROJECT_SERVERS_ON || !crate::blossom::project_server(&l.url))
        .filter(|l| {
            let ok = anonymous || blossom.is_known(&l.url);
            if !ok { skipped_unknown = true; }
            ok
        })
        .map(|l| Source { url: l.url.clone(), relay: l.relay.clone(), discovered: None })
        .collect();
    let n = sources.len();
    if skipped_unknown {
        tracing::info!(target: "alpha_ffi", "CHUNK_UNKNOWN_SERVER skipped={} chunk={}", listed - n, start_at);
    }

    // --- TRANSPORT V1 — replica discovery by object identity ---
    //
    // The listed addresses are HINTS, not where the object is bound. The
    // object is addressed by its content: the server stores it under the
    // SHA-256 of the body we uploaded and serves it at `GET {server}/{hash}`.
    // So a dead or stalled server need not stall the chunk: a background
    // replica on any other server KNOWN TO US can serve it.
    //
    // Security is not weakened: a found blob passes the same envelope, AEAD
    // and BLAKE3 checks higher up the stack. Only servers WE configured are
    // probed — an address from a foreign ref cannot reach here.
    // Identity source, strictly by priority: the explicit field of an
    // authenticated ref; else (legacy messages) parsing the address.
    let content_id: Option<String> = match explicit_id {
        Some(id) => Some(id.as_hex().to_string()),
        None => locations.iter().find_map(|l| content_id_from_blossom_url(&l.url)),
    };
    if let Some(cid) = &content_id {
        let already: std::collections::HashSet<String> = locations
            .iter()
            .map(|l| l.relay.trim_end_matches('/').to_ascii_lowercase())
            .collect();
        let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
        for server in blossom.configured_servers() {
            let base = server.trim_end_matches('/').to_string();
            let key = base.to_ascii_lowercase();
            if already.contains(&key) || !seen.insert(key) {
                continue;
            }
            // Absence is a property of the PAIR (server, object): under partial
            // replication a neighbour may still hold the next chunk.
            if blossom.discovery_missing(&base, cid) {
                continue;
            }
            sources.push(Source { url: format!("{base}/{cid}"), relay: base.clone(), discovered: Some(base) });
        }
    }

    // Fastest expected answer first: a listed hint that is slow or crowded
    // must not hold a chunk while a replica elsewhere answers at once.
    if sources.is_empty() {
        return Err(if skipped_unknown { MediaError::UnknownServer } else { MediaError::NoChunkUrls });
    }
    // A server resting after failures in a row is not asked until its rest is over.
    let before_rest = sources.len();
    sources.retain(|s| !host_resting(&s.url));
    let resting = before_rest - sources.len();
    if sources.is_empty() {
        return Err(MediaError::DownloadFailed("every server that may hold it is resting after failures".into()));
    }
    sources.sort_by_key(|s| expected_answer_ms(&s.url, s.discovered.is_some()));
    // Every source once. One that failed without an answer gets one more try after a
    // pause; "not here" is final for the source that said it.
    let mut plan: std::collections::VecDeque<(usize, bool)> = (0..sources.len()).map(|i| (i, false)).collect();
    let mut retried: std::collections::HashSet<usize> = std::collections::HashSet::new();
    // A listed server out of reach means the object exists: a copy on its way to another
    // server that said "not here" may land in a moment, so those are asked once more.
    let mut home_unreached = false;
    let mut attempts = 0usize;

    // Byte movement of every attempt: a duplicate is raced only against a stalled request.
    let watches: std::sync::Mutex<Vec<std::sync::Arc<crate::blossom::Moved>>> = Default::default();
    let launch = |idx: usize, repeat: bool, attempt: usize| {
        let src = sources[idx].clone();
        let moved = std::sync::Arc::new(crate::blossom::Moved::new());
        watches.lock().unwrap_or_else(|p| p.into_inner()).push(moved.clone());
        async move {
            if repeat {
                repeat_pause().await;
            }
            let _host = host_slots(&src.url).acquire_owned().await;
            let turn = crate::link::download_window().enter().await;
            let t0 = std::time::Instant::now();
            // Bounded by the stall watch inside: a slow chunk is let finish, a dead one is cut.
            let r = blossom.download_watched(&src.url, &moved).await;
            turn.finish(match &r {
                Ok(_) => crate::link::Outcome::Delivered,
                Err(_) if moved.answered() => crate::link::Outcome::Answered,
                Err(_) if !moved.reached() => crate::link::Outcome::Unreached,
                Err(_) => crate::link::Outcome::Broken,
            });
            (idx, src, attempt, t0.elapsed(), r)
        }
    };
    // Sources that answered "not here". Once every one has, the object is gone:
    // retrying the same servers cannot bring it back.
    let mut not_held: std::collections::HashSet<usize> = std::collections::HashSet::new();

    // --- Hedged fetch ---
    //
    // A public server sometimes stalls ONE request for tens of seconds while
    // the next request is served instantly. Downloads are written in order,
    // so one stalled chunk froze the whole file. Now, when a chunk is slower
    // than usual, a second request (another replica, or a fresh connection)
    // is raced against it and the first answer wins; the loser is dropped.
    let t_chunk = std::time::Instant::now();
    let mut inflight = FuturesUnordered::new();
    // Requests still waiting that may be raced once by a fresh connection to the same source.
    let mut hedgeable: std::collections::VecDeque<usize> = std::collections::VecDeque::new();
    if let Some((idx, repeat)) = plan.pop_front() {
        attempts += 1;
        if !repeat { hedgeable.push_back(idx); }
        inflight.push(launch(idx, repeat, attempts));
    }
    let mut last_err: Option<MediaError> = None;
    loop {
        let hedge = tokio::time::sleep(hedge_delay());
        tokio::select! {
            done = inflight.next() => {
                let Some((idx, src, attempt, took, result)) = done else { break };
                hedgeable.retain(|&h| h != idx);
                match result {
                    Ok(d) => {
                        note_host(&src.url, HostOutcome::Served(took));
                        if let Some(base) = &src.discovered {
                            tracing::info!(target: "alpha_ffi", "CHUNK_DISCOVER hit on {base}");
                            blossom.discovery_note_hit(base);
                        }
                        note_chunk_time(t_chunk.elapsed());
                        return Ok(d);
                    }
                    Err(e) => {
                        tracing::warn!(target: "alpha_ffi", "CHUNK_RETRY attempt={} waited_ms={} relay={} err={e}", attempt, took.as_millis(), src.relay);
                        let missing = matches!(e, MediaError::NotOnServer(_));
                        note_host(&src.url, if missing { HostOutcome::Missing } else { HostOutcome::Failed });
                        if missing {
                            // Only "not here" is remembered: a timeout says nothing about the object.
                            if let (Some(base), Some(cid)) = (&src.discovered, &content_id) {
                                blossom.discovery_note_missing(base, cid);
                            }
                            not_held.insert(idx);
                            // A resting server was not asked: it may still hold the object.
                            if not_held.len() == sources.len() && resting == 0 {
                                tracing::warn!(target: "alpha_ffi", "CHUNK_GONE chunk={} sources={} unknown={}", start_at, sources.len(), skipped_unknown);
                                // Not on any server we know; the sender's own may still hold it.
                                return Err(if skipped_unknown { MediaError::UnknownServer } else { MediaError::Gone });
                            }
                            if home_unreached && retried.insert(idx) {
                                plan.push_back((idx, true));
                            }
                        } else {
                            // A server that has started resting gets no more tries now.
                            if !host_resting(&src.url) && retried.insert(idx) {
                                plan.push_back((idx, true));
                            }
                            if src.discovered.is_none() && !home_unreached {
                                home_unreached = true;
                                for &held in &not_held {
                                    if retried.insert(held) {
                                        plan.push_back((held, true));
                                    }
                                }
                            }
                        }
                        last_err = Some(e);
                        if let Some((idx, repeat)) = plan.pop_front() {
                            attempts += 1;
                            if !repeat { hedgeable.push_back(idx); }
                            inflight.push(launch(idx, repeat, attempts));
                        } else if inflight.is_empty() {
                            break;
                        }
                    }
                }
            }
            _ = hedge, if !plan.is_empty() || !hedgeable.is_empty() => {
                // Bytes still arriving: the link is slow, and a duplicate would halve it.
                if watches.lock().unwrap_or_else(|p| p.into_inner()).iter().any(|w| w.idle() < hedge_delay()) {
                    continue;
                }
                // The next source if any is left, else one fresh connection to a stalled one.
                if let Some((idx, repeat)) = plan.pop_front() {
                    attempts += 1;
                    if !repeat { hedgeable.push_back(idx); }
                    inflight.push(launch(idx, repeat, attempts));
                } else if let Some(idx) = hedgeable.pop_front() {
                    attempts += 1;
                    inflight.push(launch(idx, false, attempts));
                }
            }
        }
    }
    Err(last_err.unwrap_or(MediaError::NoChunkUrls))
}

/// How a host that just failed a chunk is ranked: as if it answered this slowly.
const FAILED_HOST_MS: u64 = 20_000;

/// Hedge bounds: a healthy 256 KiB chunk lands far below the minimum.
const HEDGE_MIN_MS: u64 = 1_000;
const HEDGE_MAX_MS: u64 = 8_000;

/// Moving average of how long a chunk takes on THIS network (ms; 0 = unknown).
static CHUNK_EWMA_MS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Hedge when a chunk takes 3× longer than usual, so a slow-but-healthy
/// network is not flooded with duplicate requests.
/// Chunk requests in flight to ONE server. A public server meets a burst from one
/// address with silence (two phones × 32 requests: 20 s stalls on blossom.primal.net,
/// the file aborted at 80 %), so no host gets more; other servers take the rest.
const PER_HOST_SLOTS: usize = 8;

fn host_slots(url: &str) -> std::sync::Arc<tokio::sync::Semaphore> {
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex, OnceLock};
    static SLOTS: OnceLock<Mutex<HashMap<String, Arc<tokio::sync::Semaphore>>>> = OnceLock::new();
    let host = host_key(url);
    let mut map = SLOTS.get_or_init(|| Mutex::new(HashMap::new())).lock().unwrap_or_else(|p| p.into_inner());
    map.entry(host).or_insert_with(|| Arc::new(tokio::sync::Semaphore::new(PER_HOST_SLOTS))).clone()
}

enum HostOutcome { Served(std::time::Duration), Missing, Failed }

#[derive(Default, Clone, Copy)]
struct HostStat {
    ewma_ms: u64,
    /// Failures in a row (no answer or a server error); an answer of any kind clears it.
    failures: u32,
    /// Not asked until then: a server failing again and again only burns requests.
    resting_until: Option<std::time::Instant>,
}

/// Failures in a row after which a server rests.
const REST_AFTER_FAILURES: u32 = 3;
/// The first rest; each failure after it doubles the rest, up to [MAX_REST].
const FIRST_REST: std::time::Duration = std::time::Duration::from_secs(120);
const MAX_REST: std::time::Duration = std::time::Duration::from_secs(30 * 60);

/// The rest after [failures] in a row: 2, 4, 8 … minutes, at most 30.
fn rest_for(failures: u32) -> Option<std::time::Duration> {
    let over = failures.checked_sub(REST_AFTER_FAILURES)?;
    Some(FIRST_REST.saturating_mul(1u32 << over.min(5)).min(MAX_REST))
}

fn host_resting(url: &str) -> bool {
    host_stats().lock().unwrap_or_else(|p| p.into_inner())
        .get(&host_key(url))
        .and_then(|s| s.resting_until)
        .is_some_and(|t| std::time::Instant::now() < t)
}

/// A pause before the one more try a failing source gets: long enough for a server
/// that just stumbled, short enough that a reader waits seconds, not minutes.
async fn repeat_pause() {
    use rand::Rng;
    let ms = 2_000 + rand::thread_rng().gen_range(0..=2_000);
    tokio::time::sleep(std::time::Duration::from_millis(ms)).await;
}

fn host_key(url: &str) -> String {
    url.split("://").nth(1).and_then(|r| r.split('/').next()).unwrap_or(url).to_ascii_lowercase()
}

fn host_stats() -> &'static std::sync::Mutex<std::collections::HashMap<String, HostStat>> {
    static STATS: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<String, HostStat>>> = std::sync::OnceLock::new();
    STATS.get_or_init(Default::default)
}

/// What each server did with our chunk requests on this network.
fn note_host(url: &str, outcome: HostOutcome) {
    let mut map = host_stats().lock().unwrap_or_else(|p| p.into_inner());
    let st = map.entry(host_key(url)).or_default();
    if matches!(outcome, HostOutcome::Failed) {
        st.failures += 1;
        st.resting_until = rest_for(st.failures).map(|d| std::time::Instant::now() + d);
    } else {
        st.failures = 0;
        st.resting_until = None;
    }
    let sample = match outcome {
        HostOutcome::Served(t) => Some(t.as_millis().min(60_000) as u64),
        HostOutcome::Missing => None,
        HostOutcome::Failed => Some(FAILED_HOST_MS),
    };
    if let Some(ms) = sample {
        st.ewma_ms = if st.ewma_ms == 0 { ms } else { (st.ewma_ms * 3 + ms) / 4 };
    }
}

/// How long a chunk request to this server is expected to take, queue included.
fn expected_answer_ms(url: &str, discovered: bool) -> u64 {
    let st = host_stats().lock().unwrap_or_else(|p| p.into_inner()).get(&host_key(url)).copied().unwrap_or_default();
    let answer = if st.ewma_ms == 0 { UNKNOWN_HOST_MS } else { st.ewma_ms };
    let busy = (PER_HOST_SLOTS - host_slots(url).available_permits().min(PER_HOST_SLOTS)) as u64;
    let mut ms = answer + answer * busy / PER_HOST_SLOTS as u64;
    // Where the sender put it comes first; a copy guessed by content id is the fallback,
    // raced only when the listed server is failing or slow to move.
    if discovered { ms += GUESSED_COPY_PENALTY_MS; }
    ms
}

/// A server never asked yet: neither preferred nor avoided.
const UNKNOWN_HOST_MS: u64 = 800;
const GUESSED_COPY_PENALTY_MS: u64 = 10_000;

fn hedge_delay() -> std::time::Duration {
    let ewma = CHUNK_EWMA_MS.load(std::sync::atomic::Ordering::Relaxed);
    // Ordered writes stall on the slowest chunk: race a second request early.
    std::time::Duration::from_millis(ewma.saturating_mul(2).clamp(HEDGE_MIN_MS, HEDGE_MAX_MS))
}

fn note_chunk_time(took: std::time::Duration) {
    use std::sync::atomic::Ordering;
    let ms = took.as_millis().min(60_000) as u64;
    let old = CHUNK_EWMA_MS.load(Ordering::Relaxed);
    let new = if old == 0 { ms } else { (old * 7 + ms) / 8 };
    CHUNK_EWMA_MS.store(new.max(1), Ordering::Relaxed);
}

/// Extract the object identity from a Blossom address.
///
/// Strict parsing, not "take everything after the last slash": only the last
/// path segment shaped like a lower-case hex SHA-256 is accepted — exactly
/// what the server returns (verified live). An optional extension (`.json`,
/// `.bin`) is dropped: the server adds it itself and answers the bare hash
/// the same way.
///
/// This is the FALLBACK path for already-sent messages with no explicit
/// identity field. The new format must write it explicitly — guessing from
/// the address must not become a permanent contract.
/// Every storage object id (Blossom sha256) referenced by a MediaRef JSON:
/// chunk replicas, external manifest, posters/thumbnails. Used to purge media.
pub fn blob_ids_in_ref(media_ref_json: &str) -> Vec<String> {
    fn walk(v: &serde_json::Value, key: Option<&str>, out: &mut Vec<String>) {
        match v {
            serde_json::Value::String(s) => {
                if s.starts_with("https://") || s.starts_with("http://") {
                    if let Some(id) = content_id_from_blossom_url(s) { out.push(id); }
                } else if key == Some("transport_object_id")
                    && s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit())
                {
                    out.push(s.to_lowercase());
                }
            }
            serde_json::Value::Array(a) => a.iter().for_each(|x| walk(x, key, out)),
            serde_json::Value::Object(o) => o.iter().for_each(|(k, x)| walk(x, Some(k.as_str()), out)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(media_ref_json) {
        walk(&v, None, &mut out);
    }
    out.sort();
    out.dedup();
    out
}

pub(crate) fn content_id_from_blossom_url(url: &str) -> Option<String> {
    let without_query = url.split(['?', '#']).next()?;
    let last = without_query.rsplit('/').next()?;
    let stem = last.split('.').next()?;
    if stem.len() == 64 && stem.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
        Some(stem.to_string())
    } else {
        None
    }
}

/// Full media upload/download pipeline orchestrator.
///
/// Coordinates encryption, chunking, upload/download, and verification
/// in a single flow. Uses Blossom relays for blob storage and
/// XChaCha20-Poly1305 for end-to-end encryption.
pub struct MediaPipeline {
    blossom: BlossomClient,
}

impl MediaPipeline {
    /// Create a new `MediaPipeline` with the given Blossom client.
    ///
    /// The `MediaSigner` is passed per-operation (upload/delete) rather than
    /// stored in the pipeline, allowing the same pipeline to be used with
    /// different identities.
    pub fn new(blossom: BlossomClient) -> Self {
        Self { blossom }
    }

    /// Upload pipeline: encrypt → chunk → upload → (optional thumbnail) → result.
    ///
    /// Steps:
    /// 1. Generate a random XChaCha20-Poly1305 key
    /// 2. Encrypt the file data
    /// 3. Compute BLAKE3 hash of the original plaintext
    /// 4. Split the encrypted data into chunks
    /// 5. Upload each chunk to the Blossom relay
    /// 6. If the media is an image/video, generate a thumbnail
    /// 7. Encrypt the thumbnail separately
    /// 8. Upload thumbnail chunks to Blossom
    /// 9. Return the complete `UploadResult`
    pub async fn upload(
        &self,
        file_data: &[u8],
        filename: &str,
        media_type: MediaType,
        signer: &crate::blossom::MediaSigner,
    ) -> MediaResult<UploadResult> {
        self.upload_with_progress(file_data, filename, media_type, signer, &|_, _| {}, &|| false).await
    }

    /// Upload reporting byte-level progress via `on_progress(uploaded, total)`
    /// (total = encrypted size). Powers the sender-side "sending…" bar.
    #[instrument(skip(self, file_data, signer, on_progress, is_cancelled), fields(filename = %filename, size = file_data.len()))]
    pub async fn upload_with_progress(
        &self,
        file_data: &[u8],
        filename: &str,
        media_type: MediaType,
        signer: &crate::blossom::MediaSigner,
        on_progress: &(dyn Fn(u64, u64) + Sync),
        is_cancelled: &(dyn Fn() -> bool + Sync),
    ) -> MediaResult<UploadResult> {
        info!(filename, media_type = %media_type, "Starting media upload pipeline");

        // 1. Generate encryption key
        let media_key = generate_key();
        debug!("Generated encryption key");

        // 2. Encrypt the file data
        let encrypted = MediaCrypto::encrypt(file_data, &media_key)?;
        debug!(
            original_size = encrypted.original_size,
            ciphertext_size = encrypted.ciphertext.len(),
            "Encrypted file data"
        );

        // 3. Compute BLAKE3 hash of original plaintext
        let media_hash = blake3_hash(file_data);

        // 4. Split encrypted data into chunks
        let chunks = EncryptedChunker::split_encrypted(&encrypted)?;
        debug!(chunk_count = chunks.len(), "Split encrypted data into chunks");

        // 5. Upload chunks to Blossom IN PARALLEL with per-chunk retry (was a
        //    sequential for-loop — the main sender-side bottleneck for photos,
        //    documents, audio). Order is restored afterwards by the chunk index,
        //    so the media ref / wire format is byte-for-byte unchanged. A flaky
        //    chunk retries on its own instead of failing the whole upload.
        use futures::stream::StreamExt;
        use std::sync::atomic::{AtomicU64, Ordering};
        let total: u64 = chunks.iter().map(|c| c.len() as u64).sum::<u64>().max(1);
        let uploaded = AtomicU64::new(0);
        let mut indexed: Vec<(usize, String)> = futures::stream::iter(chunks.iter().enumerate())
            .map(|(i, chunk)| {
                let blossom = &self.blossom;
                let uploaded = &uploaded;
                async move {
                    // The ✕ is honoured between chunks. Without this the whole
                    // upload ran to completion inside a `block_on` and the message
                    // was published anyway — the user cancelled and the peer still
                    // got the file.
                    if is_cancelled() { return Err(MediaError::Cancelled); }
                    // Legacy path (photos/files) already succeeds — its JSON blob
                    // is recognised by the servers. Keep octet-stream unchanged.
                    let url = upload_blob_retry(blossom, chunk, signer, "application/octet-stream").await?;
                    let len = chunk.len() as u64;
                    let done = uploaded.fetch_add(len, Ordering::Relaxed) + len;
                    on_progress(done.min(total), total);
                    Ok::<(usize, String), MediaError>((i, url))
                }
            })
            .buffer_unordered(UPLOAD_CONCURRENCY)
            .collect::<Vec<_>>()
            .await
            .into_iter()
            .collect::<MediaResult<Vec<_>>>()?;
        indexed.sort_by_key(|(i, _)| *i);
        let blob_urls: Vec<String> = indexed.into_iter().map(|(_, u)| u).collect();
        on_progress(total, total);
        debug!(url_count = blob_urls.len(), "All chunks uploaded (parallel)");

        // 6-8. Generate and upload thumbnail if applicable
        let (thumbnail_key, thumbnail_hash, thumbnail_urls, thumbnail_size) =
            match media_type {
                MediaType::Image => {
                    match ThumbnailGenerator::generate(file_data, &media_type, THUMBNAIL_MAX_SIZE) {
                        Ok(thumbnail) => {
                            debug!(
                                thumb_width = thumbnail.width,
                                thumb_height = thumbnail.height,
                                thumb_data_size = thumbnail.data.len(),
                                "Generated thumbnail"
                            );

                            // Encrypt thumbnail with its own key
                            let t_key = generate_key();
                            let t_encrypted = MediaCrypto::encrypt(&thumbnail.data, &t_key)?;
                            let t_hash = blake3_hash(&thumbnail.data);

                            // Chunk and upload
                            let t_chunks = EncryptedChunker::split_encrypted(&t_encrypted)?;
                            let mut t_urls = Vec::with_capacity(t_chunks.len());
                            for (i, chunk) in t_chunks.iter().enumerate() {
                                debug!(chunk_index = i, "Uploading thumbnail chunk");
                                let descriptor = self.blossom.upload(chunk, signer, "application/octet-stream").await?;
                                t_urls.push(descriptor.url);
                            }

                            (
                                Some(*t_key),
                                Some(t_hash),
                                Some(t_urls),
                                Some(thumbnail.data.len() as u64),
                            )
                        }
                        Err(e) => {
                            debug!(error = %e, "Thumbnail generation failed, skipping thumbnail");
                            (None, None, None, None)
                        }
                    }
                }
                MediaType::Video => {
                    // TODO: Video thumbnail generation not yet implemented
                    debug!("Video thumbnail not yet supported, skipping");
                    (None, None, None, None)
                }
                MediaType::Audio | MediaType::File => {
                    debug!(media_type = %media_type, "No thumbnail for this media type");
                    (None, None, None, None)
                }
            };

        let result = UploadResult {
            media_key: *media_key,
            media_hash,
            blob_urls,
            original_size: encrypted.original_size,
            thumbnail_key,
            thumbnail_hash,
            thumbnail_urls,
            thumbnail_size,
            media_type,
            filename: filename.to_string(),
            stream_chunks: None,
        };

        info!(filename, "Media upload pipeline completed successfully");
        Ok(result)
    }

    /// Download pipeline: download → reassemble → decrypt → verify.
    ///
    /// Steps:
    /// 1. Download all chunks from the provided Blossom URLs
    /// 2. Reassemble chunks into serialized `EncryptedMedia`
    /// 3. Deserialize to `EncryptedMedia`
    /// 4. Decrypt with the provided key
    /// 5. Verify BLAKE3 hash matches
    /// 6. Return the plaintext
    #[instrument(skip(self, media_key, media_hash, blob_urls), fields(url_count = blob_urls.len(), original_size))]
    pub async fn download(
        &self,
        media_key: &[u8; 32],
        media_hash: &[u8; 32],
        blob_urls: &[String],
        original_size: u64,
    ) -> MediaResult<Vec<u8>> {
        self.download_with_progress(media_key, media_hash, blob_urls, original_size, &|_, _| {})
            .await
    }

    /// Same as [`download`] but reports byte-level download progress through
    /// `on_progress(downloaded_bytes, total_bytes)`. `total_bytes` is the
    /// original (plaintext) size — a close-enough denominator for a UI bar.
    pub async fn download_with_progress(
        &self,
        media_key: &[u8; 32],
        media_hash: &[u8; 32],
        blob_urls: &[String],
        original_size: u64,
        on_progress: &(dyn Fn(u64, u64) + Sync),
    ) -> MediaResult<Vec<u8>> {
        if blob_urls.is_empty() {
            return Err(MediaError::NoChunkUrls);
        }

        info!(chunk_count = blob_urls.len(), original_size, "Starting media download pipeline");

        // 1. Download all chunks IN PARALLEL with per-chunk retry (was a
        //    sequential loop). Progress ticks per completed chunk; order is
        //    restored by index before reassembly, so the stored format is
        //    unchanged.
        use futures::stream::StreamExt;
        use std::sync::atomic::{AtomicU64, Ordering};
        let total = original_size.max(1);
        let downloaded = AtomicU64::new(0);
        // Iterate OWNED (index, url) pairs — iterating borrowed `&String` items
        // trips HRTB lifetime inference under buffer_unordered.
        let owned_urls: Vec<(usize, String)> = blob_urls.iter().cloned().enumerate().collect();
        let mut indexed: Vec<(usize, Vec<u8>)> = futures::stream::iter(owned_urls)
            .map(|(i, url)| {
                let blossom = &self.blossom;
                let downloaded = &downloaded;
                async move {
                    // Legacy whole-file path: one URL per blob → wrap as a single
                    // location and reuse the same retry/fallback machinery.
                    let loc = [ChunkLocation { relay: String::new(), url }];
                    let data = download_blob_locations(blossom, None, &loc, i, DownloadPolicy::RoundRobin).await?;
                    let done = downloaded.fetch_add(data.len() as u64, Ordering::Relaxed) + data.len() as u64;
                    on_progress(done.min(total), total);
                    Ok::<(usize, Vec<u8>), MediaError>((i, data))
                }
            })
            .buffer_unordered(DOWNLOAD_CONCURRENCY)
            .collect::<Vec<_>>()
            .await
            .into_iter()
            .collect::<MediaResult<Vec<_>>>()?;
        indexed.sort_by_key(|(i, _)| *i);
        let chunk_data: Vec<Vec<u8>> = indexed.into_iter().map(|(_, d)| d).collect();
        // Ensure a final 100% tick.
        on_progress(total, total);
        debug!(chunk_count = chunk_data.len(), "All chunks downloaded (parallel)");

        // 2-3. Reassemble and deserialize
        let encrypted = EncryptedChunker::join_encrypted(&chunk_data)?;
        debug!(
            original_size = encrypted.original_size,
            ciphertext_size = encrypted.ciphertext.len(),
            "Reassembled encrypted media"
        );

        // Verify original size matches
        if encrypted.original_size != original_size {
            return Err(MediaError::PipelineError(format!(
                "Original size mismatch: expected {original_size}, got {}",
                encrypted.original_size
            )));
        }

        // 4. Decrypt
        let plaintext = MediaCrypto::decrypt(&encrypted, media_key)?;
        debug!(plaintext_size = plaintext.len(), "Decrypted media data");

        // 5. Verify BLAKE3 hash
        let computed_hash = blake3_hash(&plaintext);
        if computed_hash != *media_hash {
            return Err(MediaError::IntegrityCheckFailed {
                expected: hex::encode(media_hash),
                actual: hex::encode(computed_hash),
            });
        }
        debug!("BLAKE3 hash verified successfully");

        info!("Media download pipeline completed successfully");
        Ok(plaintext)
    }

    // --- Streaming E2EE ---

    /// Streaming upload: per-chunk encrypt → upload each chunk independently.
    ///
    /// Unlike the legacy `upload` (encrypt whole file → chunk ciphertext),
    /// this splits plaintext first, encrypts each chunk with its own nonce,
    /// and uploads them individually. Enables:
    /// - Playback after first chunk arrives (ExoPlayer DataSource)
    /// - Seek by downloading only the needed chunk
    /// - Resume after interruption (chunk-level granularity)
    ///
    /// The master key travels inside the MLS-encrypted message, so Blossom
    /// relays and Nostr relays never see it.
    #[instrument(skip(self, file_data, signer, on_progress), fields(filename = %filename, size = file_data.len()))]
    pub async fn upload_streaming(
        &self,
        file_data: &[u8],
        filename: &str,
        media_type: MediaType,
        signer: &crate::blossom::MediaSigner,
        on_progress: &(dyn Fn(u64, u64) + Sync),
    ) -> MediaResult<UploadResult> {
        info!(filename, media_type = %media_type, "Starting streaming media upload");

        // 0. For video, move the MP4 `moov` index to the front (faststart) so
        //    the player can begin rendering from the first chunk instead of
        //    waiting for the whole file. No-op for non-MP4 / already-faststart.
        let prepared: std::borrow::Cow<[u8]> = if media_type == MediaType::Video {
            match crate::faststart::to_faststart(file_data) {
                Some(rewritten) => {
                    info!(
                        original = file_data.len(),
                        rewritten = rewritten.len(),
                        "Applied MP4 faststart (moov → front)"
                    );
                    std::borrow::Cow::Owned(rewritten)
                }
                None => std::borrow::Cow::Borrowed(file_data),
            }
        } else {
            std::borrow::Cow::Borrowed(file_data)
        };
        let file_data: &[u8] = &prepared;

        // 1. Generate master encryption key
        let media_key = generate_key();
        debug!("Generated streaming master key");

        // 2. Compute BLAKE3 hash of the whole plaintext (for overall integrity)
        let media_hash = blake3_hash(file_data);

        // 3. Split plaintext into chunks and encrypt each independently
        let encrypted_chunks = StreamCrypto::encrypt_stream(file_data, &media_key)?;
        debug!(chunk_count = encrypted_chunks.len(), "Encrypted stream into per-chunk AEAD chunks");

 // 4. Upload encrypted chunks to Blossom in PARALLEL.
        //    Sequential upload was the main sender-side bottleneck — with N
        //    parallel uploads the wall-clock time drops ~N×. Order is restored
        //    afterwards by sorting on the original chunk index.
        use futures::stream::StreamExt;
        use std::sync::atomic::{AtomicU64, Ordering};

        let total: u64 = encrypted_chunks.iter()
            .map(|c| c.ciphertext.len() as u64)
            .sum::<u64>().max(1);
        let uploaded = AtomicU64::new(0);
        // Transport shell for every chunk. Media-type agnostic — nothing below
        // branches on photo/video/document; the ciphertext rides verbatim.
        let chunk_total = encrypted_chunks.len() as u32;
        let media_key_ref: &[u8; 32] = &media_key;

        let mut indexed_infos: Vec<(usize, StreamChunkUploadInfo)> =
            futures::stream::iter(encrypted_chunks.iter().enumerate())
                .map(|(i, chunk)| {
                    let blossom = &self.blossom;
                    let uploaded = &uploaded;
                    async move {
                        // Zero-copy: the ciphertext is borrowed, not cloned — at 4
                        // parallel chunks × hundreds of MB that copy was real.
                        let blob = crate::blob_transport::wrap_chunk(media_key_ref, i as u32, &chunk.ciphertext)?;
                        debug!(
                            chunk_index = i, ciphertext_size = chunk.ciphertext.len(),
                            blob_size = blob.len(),
                            "Uploading stream chunk (BlobEnvelope)",
                        );
                        let locations = upload_blob_mirrored(blossom, &blob, signer, crate::blob_transport::CHUNK_CONTENT_TYPE).await?;
                        tracing::info!(target: "alpha_ffi", "CHUNK_UP idx={i} mirrors={}", locations.len());
                        // Progress + manifest stay denominated in CIPHERTEXT bytes:
                        // the MediaRef format is deliberately unchanged.
                        let len = chunk.ciphertext.len() as u64;
                        let done = uploaded.fetch_add(len, Ordering::Relaxed) + len;
                        on_progress(done.min(total), total);
                        Ok::<(usize, StreamChunkUploadInfo), MediaError>((
                            i,
                            StreamChunkUploadInfo {
                                locations,
                                ciphertext_size: len,
                                original_size: chunk.original_size,
                                hash_hex: hex::encode(chunk.hash),
                                transport_object_id: None,
                            },
                        ))
                    }
                })
                .buffer_unordered(UPLOAD_CONCURRENCY)
                .collect::<Vec<_>>()
                .await
                .into_iter()
                .collect::<MediaResult<Vec<_>>>()?;

        // Restore chunk order (parallel uploads complete out of order).
        indexed_infos.sort_by_key(|(i, _)| *i);
        let stream_chunk_infos: Vec<StreamChunkUploadInfo> =
            indexed_infos.into_iter().map(|(_, info)| info).collect();
        on_progress(total, total);
        debug!("All stream chunks uploaded (parallel)");

        // 5. Generate thumbnail if applicable (same as legacy)
        let (thumbnail_key, thumbnail_hash, thumbnail_urls, thumbnail_size) =
            match media_type {
                MediaType::Image => {
                    match ThumbnailGenerator::generate(file_data, &media_type, THUMBNAIL_MAX_SIZE) {
                        Ok(thumbnail) => {
                            let t_key = generate_key();
                            let t_encrypted = MediaCrypto::encrypt(&thumbnail.data, &t_key)?;
                            let t_hash = blake3_hash(&thumbnail.data);
                            let t_chunks = EncryptedChunker::split_encrypted(&t_encrypted)?;
                            let mut t_urls = Vec::with_capacity(t_chunks.len());
                            for (i, chunk) in t_chunks.iter().enumerate() {
                                debug!(chunk_index = i, "Uploading thumbnail chunk");
                                let descriptor = self.blossom.upload(chunk, signer, "application/octet-stream").await?;
                                t_urls.push(descriptor.url);
                            }
                            (Some(*t_key), Some(t_hash), Some(t_urls), Some(thumbnail.data.len() as u64))
                        }
                        Err(e) => {
                            debug!(error = %e, "Thumbnail generation failed, skipping");
                            (None, None, None, None)
                        }
                    }
                }
                _ => (None, None, None, None),
            };

        let result = UploadResult {
            media_key: *media_key,
            media_hash,
            blob_urls: stream_chunk_infos.iter().filter_map(|c| c.locations.first().map(|l| l.url.clone())).collect(),
            original_size: file_data.len() as u64,
            thumbnail_key,
            thumbnail_hash,
            thumbnail_urls,
            thumbnail_size,
            media_type,
            filename: filename.to_string(),
            stream_chunks: Some(stream_chunk_infos),
        };

        info!(filename, "Streaming media upload completed successfully");
        Ok(result)
    }

    /// **True streaming upload — peak memory is O(chunk), not O(file).**
    ///
    /// The older [`Self::upload_streaming`] takes the whole file as a slice, and
    /// the caller had already `fs::read` it; it then produced the *entire*
    /// ciphertext up front. For a 1 GiB video that meant plaintext + faststart
    /// copy + full ciphertext ≈ 3 GiB resident. This variant never materialises
    /// any of that:
    ///
    /// ```text
    ///   read 256 KiB → BLAKE3 update → encrypt chunk → BlobEnvelope → upload → free
    /// ```
    ///
    /// Chunks are read lazily and at most `UPLOAD_CONCURRENCY` are in flight, so
    /// resident bytes ≈ concurrency × (plaintext + ciphertext + encoded blob)
    /// ≈ 4 × ~850 KiB ≈ 3.4 MiB — independent of file size. Reading the next
    /// chunk overlaps with encrypting/uploading the previous ones.
    ///
    /// Crypto, chunk format, MediaRef/manifest and the BlobEnvelope are all
    /// unchanged — this only changes *when* bytes are materialised.
    pub async fn upload_streaming_file(
        &self,
        file_path: &std::path::Path,
        filename: &str,
        media_type: MediaType,
        signer: &crate::blossom::MediaSigner,
        media_key: [u8; 32],
        on_progress: &(dyn Fn(u64, u64) + Sync),
        is_cancelled: &(dyn Fn() -> bool + Sync),
        checkpoint: Option<&dyn crate::resume::ChunkCheckpoint>,
    ) -> MediaResult<UploadResult> {
        self.upload_streaming_chunks(file_path, filename, media_type, signer, media_key, on_progress, is_cancelled, checkpoint, true).await
    }

    /// [`Self::upload_streaming_file`] with the bytes exactly as on disk: chunks of it
    /// were sent while it was still being encoded, and moving the index would move them.
    pub async fn upload_streaming_as_written(
        &self,
        file_path: &std::path::Path,
        filename: &str,
        media_type: MediaType,
        signer: &crate::blossom::MediaSigner,
        media_key: [u8; 32],
        on_progress: &(dyn Fn(u64, u64) + Sync),
        is_cancelled: &(dyn Fn() -> bool + Sync),
        checkpoint: Option<&dyn crate::resume::ChunkCheckpoint>,
    ) -> MediaResult<UploadResult> {
        self.upload_streaming_chunks(file_path, filename, media_type, signer, media_key, on_progress, is_cancelled, checkpoint, false).await
    }

    #[allow(clippy::too_many_arguments)]
    async fn upload_streaming_chunks(
        &self,
        file_path: &std::path::Path,
        filename: &str,
        media_type: MediaType,
        signer: &crate::blossom::MediaSigner,
        // The encryption key of this send.
        //
        // It comes from OUTSIDE and survives retries. Deriving it here, fresh
        // per call, made resume impossible in principle: the same chunk
        // re-encrypted differently on retry, got a different identity, and
        // accepted chunks could not be recognized. Where the key is stored is
        // the caller's business (the core's encrypted DB).
        media_key: [u8; 32],
        on_progress: &(dyn Fn(u64, u64) + Sync),
        is_cancelled: &(dyn Fn() -> bool + Sync),
        // Where to record "this chunk was accepted by storage" and read the
        // same back on the next attempt. `None` — behavior exactly as before:
        // the whole file from scratch. No second send path appears here, only
        // the right not to repeat what is already accepted.
        checkpoint: Option<&dyn crate::resume::ChunkCheckpoint>,
        faststart: bool,
    ) -> MediaResult<UploadResult> {
        use futures::stream::StreamExt;
        use std::io::Read;

        let io_err = |e: std::io::Error| MediaError::EncryptionFailed(format!("stream upload io: {e}"));

        // 0. MP4 faststart (video only) — streamed to a temp file, O(moov) memory.
        //    A no-op when the file is already faststart or isn't a parseable MP4.
        let t_prep = std::time::Instant::now();
        let tmp_faststart = if faststart && media_type == MediaType::Video {
            let tmp = std::env::temp_dir()
                .join(format!("am_faststart_{}.mp4", uuid::Uuid::new_v4()));
            match crate::faststart::to_faststart_file(file_path, &tmp) {
                Ok(true) => {
                    info!("Applied MP4 faststart (moov → front, streamed)");
                    Some(tmp)
                }
                _ => {
                    let _ = std::fs::remove_file(&tmp);
                    None
                }
            }
        } else {
            None
        };
        if t_prep.elapsed() >= std::time::Duration::from_millis(500) {
            tracing::warn!(target: "alpha_ffi", "UPLOAD_PREP faststart={} ms={}", tmp_faststart.is_some(), t_prep.elapsed().as_millis());
        }
        let src: &std::path::Path = tmp_faststart.as_deref().unwrap_or(file_path);
        // Drops the temp file whatever happens below.
        struct TempGuard(Option<std::path::PathBuf>);
        impl Drop for TempGuard {
            fn drop(&mut self) {
                if let Some(p) = &self.0 {
                    let _ = std::fs::remove_file(p);
                }
            }
        }
        let _guard = TempGuard(tmp_faststart.clone());

        let plaintext_len = std::fs::metadata(src).map_err(io_err)?.len();
        if plaintext_len == 0 {
            return Err(MediaError::EncryptionFailed("Cannot encrypt empty stream".into()));
        }
        let chunk_total = plaintext_len.div_ceil(crate::crypto::STREAM_CHUNK_SIZE as u64) as u32;
        info!(
            filename, media_type = %media_type, size = plaintext_len, chunks = chunk_total,
            "Starting TRUE streaming media upload (O(chunk) memory)",
        );

        // What past attempts got accepted. Read once: from then on it is only
        // a "need not send" directory — each chunk's decision is made by
        // identity match, not by the mere presence of a record (see
        // `resume::plan_chunk`).
        let confirmed: std::collections::HashMap<u32, crate::resume::ConfirmedChunk> = checkpoint
            .map(|c| c.load())
            .unwrap_or_default()
            .into_iter()
            .filter(|c| c.index < chunk_total)
            .map(|c| (c.index, c))
            .collect();
        let resume_base: u64 = confirmed.values().map(|c| c.plaintext_bytes).sum();
        let batcher = crate::resume::ConfirmationBatcher::new(checkpoint);

        // The progress count starts from already-confirmed bytes, not zero.
        //
        // Not decoration. Verifying confirmed chunks requires reading and
        // re-encrypting them from disk — seconds at 50 MB. Starting from zero
        // would make every resume look like the send rolled back to the
        // start even though not a byte went to the network.
        let shown = SendProgress::new(plaintext_len, resume_base);
        if resume_base > 0 {
            info!(
                target: "alpha_ffi",
                "RESUME confirmed chunks {} of {chunk_total}, bytes {resume_base} of {plaintext_len}",
                confirmed.len(),
            );
            on_progress(resume_base.min(plaintext_len), plaintext_len);
        }

        // BLAKE3 over the plaintext is folded in as we read — the file is never
        // held whole just to hash it.
        let hasher = std::sync::Arc::new(std::sync::Mutex::new(blake3::Hasher::new()));

        let file = std::fs::File::open(src).map_err(io_err)?;
        let reader = std::io::BufReader::with_capacity(crate::crypto::STREAM_CHUNK_SIZE, file);

        // Lazy chunk producer: reads exactly one chunk at a time, hashes it, and
        // hands the owned buffer downstream. Back-pressured by `buffer_unordered`
        // below — it will not run ahead of the in-flight upload window.
        let h = hasher.clone();
        let chunk_stream = futures::stream::unfold(
            (reader, 0u32),
            move |(mut rdr, idx)| {
                let h = h.clone();
                async move {
                    let mut buf = vec![0u8; crate::crypto::STREAM_CHUNK_SIZE];
                    let mut filled = 0usize;
                    // Read a FULL chunk (BufReader can return short reads).
                    while filled < buf.len() {
                        match rdr.read(&mut buf[filled..]) {
                            Ok(0) => break,
                            Ok(n) => filled += n,
                            Err(e) => return Some((Err(io_err(e)), (rdr, idx))),
                        }
                    }
                    if filled == 0 {
                        return None; // EOF
                    }
                    buf.truncate(filled);
                    h.lock().expect("hasher poisoned").update(&buf);
                    Some((Ok((idx, buf)), (rdr, idx + 1)))
                }
            },
        );

        let chunk_results = chunk_stream
            .map(|item| {
                let blossom = &self.blossom;
                let shown = &shown;
                let key = &media_key;
                let confirmed = &confirmed;
                let batcher = &batcher;
                async move {
                    // Cancelled between chunks → stop reading, stop encrypting,
                    // stop uploading. Nothing is published (see the caller).
                    if is_cancelled() { return Err(MediaError::Cancelled); }
                    // A confirmation sitting in memory reaches the disk here.
                    // On a slow network, tens of seconds pass between two
                    // storage replies; without this point the last reply would
                    // live only in memory — vanishing exactly on disconnect.
                    batcher.flush_if_stale();
                    let (idx, plaintext) = item?;
                    // The chunk is reproduced with the same key from the same
                    // immutable source — otherwise the ciphertext would differ
                    // and accepted bytes could not be recognized.
                    let enc = StreamCrypto::encrypt_chunk(&plaintext, key, idx)?;
                    drop(plaintext); // release the plaintext buffer immediately
                    let blob = crate::blob_transport::wrap_chunk(key, idx, &enc.ciphertext)?;
                    // The object identity is computed HERE — from the very
                    // bytes about to go to storage. Later there is nothing to
                    // compute it from: the buffer is freed right after the send.
                    let oid = crate::media_ref::TransportObjectId::of_bytes(&blob);
                    let hash_hex = hex::encode(enc.hash);

                    // These bytes are already counted in the starting offset
                    // if a record exists — they must not be added twice.
                    let was_counted = confirmed.contains_key(&idx);

                    // The single place where "send or skip" is decided.
                    match crate::resume::plan_chunk(
                        confirmed.get(&idx), &oid, enc.original_size, &hash_hex,
                    ) {
                        crate::resume::ChunkPlan::Skip(info) => {
                            drop(blob);
                            tracing::info!(target: "alpha_ffi", "CHUNK_SKIP idx={idx} out={filename}");
                            return Ok((idx, info));
                        }
                        crate::resume::ChunkPlan::Upload(why) if !why.is_empty() => {
                            // A record exists but is not trusted. Send as
                            // usual: a silent skip would hand the recipient a
                            // ref to chunks that are not in storage.
                            tracing::warn!(
                                target: "alpha_ffi",
                                "CHUNK_RESEND idx={idx} why={why}",
                            );
                        }
                        crate::resume::ChunkPlan::Upload(_) => {}
                    }

                    debug!(
                        chunk_index = idx, ciphertext_size = enc.ciphertext.len(),
                        blob_size = blob.len(), "Uploading stream chunk (BlobEnvelope, streamed)",
                    );
                    // The deadline covers the chunk WHOLESALE, not one request.
                    // reqwest limits cover a single attempt; this one covers
                    // everything — attempts, pauses between them, and any
                    // waiting above. Without it, one request stuck on a broken
                    // link keeps the chunk stream from EVER finishing: the rest
                    // upload the file to the end while the send hangs silently,
                    // never failing — and a send that never fails never reaches the retry queue.
                    let flight = (!was_counted).then(|| shown.fly(idx, enc.original_size));
                    let locations = match tokio::time::timeout(
                        CHUNK_DEADLINE,
                        upload_blob_mirrored(blossom, &blob, signer, crate::blob_transport::CHUNK_CONTENT_TYPE),
                    ).await {
                        Ok(Ok(l)) => l,
                        Ok(Err(e)) => {
                            // Logged at info, not debug: dozens of failed chunks
                            // must not vanish from release logs, where a
                            // disconnect would look like "it went and silently
                            // stopped". The reason is the network error text;
                            // it carries no secrets.
                            tracing::warn!(
                                target: "alpha_ffi",
                                "CHUNK_FAIL idx={idx} out={filename} cause={e}",
                            );
                            return Err(e);
                        }
                        Err(_) => {
                            tracing::warn!(
                                target: "alpha_ffi",
                                "CHUNK_FAIL idx={idx} out={filename} cause=deadline {}s",
                                CHUNK_DEADLINE.as_secs(),
                            );
                            return Err(MediaError::UploadFailed(format!(
                                "chunk {idx}: no copy within {}s", CHUNK_DEADLINE.as_secs(),
                            )));
                        }
                    };
                    drop(blob);
                    // The output name is `<element>.r<revision>` — a safe way
                    // to tie the line to one specific send: without it, two
                    // concurrent sends are indistinguishable in the log, and
                    // "confirmed bytes were not re-sent" cannot be proven. It
                    // carries no secrets — no key, no addresses, no ref.
                    tracing::info!(
                        target: "alpha_ffi",
                        "CHUNK_UP idx={idx} out={filename} mirrors={}", locations.len(),
                    );

                    // The storage accepted EXACTLY these bytes — the chunk need
                    // not be repeated from now on. The confirmation becomes
                    // durable only after the transaction write; until then a retry is safe.
                    batcher.add(crate::resume::ConfirmedChunk {
                        index: idx,
                        total: chunk_total,
                        transport_object_id: oid.clone(),
                        plaintext_bytes: enc.original_size,
                        ciphertext_bytes: enc.ciphertext.len() as u64,
                        chunk_hash: hash_hex.clone(),
                        locations: locations.clone(),
                    });

                    // Progress is denominated in PLAINTEXT bytes so it matches the
                    // file the user picked (the encoded blob is ~33% larger).
                    if let Some(flight) = flight {
                        shown.land(flight);
                        shown.show(on_progress);
                    }

                    Ok::<(u32, StreamChunkUploadInfo), MediaError>((
                        idx,
                        StreamChunkUploadInfo {
                            locations,
                            ciphertext_size: enc.ciphertext.len() as u64,
                            original_size: enc.original_size,
                            hash_hex,
                            transport_object_id: Some(oid),
                        },
                    ))
                }
            })
            .buffer_unordered(UPLOAD_CONCURRENCY);

        // The first permanent chunk failure aborts the whole attempt.
        //
        // The old pattern — collect into a vector, examine errors AFTER — was
        // the root of the hang: the stream kept reading and sending new chunks
        // after the attempt was already doomed, yet could not finish while
        // one request still hung. It looked like a hole mid-file, a
        // read-through to the last chunk and eternal silence — no failure,
        // hence no retry.
        //
        // Here the stream bails on the first error. In-flight chunks are
        // cancelled with it, their send permits are released (ownership),
        // and the attempt ends within bounded time.
        futures::pin_mut!(chunk_results);
        let mut indexed_infos: Vec<(u32, StreamChunkUploadInfo)> = Vec::new();
        let mut terminal_err: Option<MediaError> = None;
        let mut tick = tokio::time::interval(SHOW_EVERY);
        loop {
            tokio::select! {
                next = chunk_results.next() => match next {
                    Some(Ok(v)) => indexed_infos.push(v),
                    Some(Err(e)) => { terminal_err = Some(e); break; }
                    None => break,
                },
                _ = tick.tick() => shown.show(on_progress),
            }
        }
        drop(chunk_results);

        // Confirmed chunks are saved on EVERY outcome, before the error is
        // examined. A disconnect is exactly what the checkpoint exists for:
        // losing a batch of confirmations here would re-send those chunks
        // next attempt — giving up the whole gain on the key case.
        batcher.flush();

        if let Some(e) = terminal_err {
            tracing::warn!(
                target: "alpha_ffi",
                "UPLOAD_ABORT out={filename} accepted={} of {chunk_total} why={e}",
                indexed_infos.len(),
            );
            return Err(e);
        }

        indexed_infos.sort_by_key(|(i, _)| *i);
        let stream_chunk_infos: Vec<StreamChunkUploadInfo> =
            indexed_infos.into_iter().map(|(_, info)| info).collect();
        on_progress(plaintext_len, plaintext_len);

        let media_hash: [u8; 32] = hasher
            .lock()
            .expect("hasher poisoned")
            .finalize()
            .into();

        let result = UploadResult {
            media_key: media_key,
            media_hash,
            blob_urls: stream_chunk_infos.iter().filter_map(|c| c.locations.first().map(|l| l.url.clone())).collect(),
            original_size: plaintext_len,
            // Video posters are supplied by the caller (Android extracts a frame);
            // no thumbnail is generated here, so the file is never decoded whole.
            thumbnail_key: None,
            thumbnail_hash: None,
            thumbnail_urls: None,
            thumbnail_size: None,
            media_type,
            filename: filename.to_string(),
            stream_chunks: Some(stream_chunk_infos),
        };

        info!(filename, "TRUE streaming upload completed successfully");
        Ok(result)
    }

    /// Send the final chunks of a video while the encoder is still writing it.
    ///
    /// Only chunks wholly inside the settled `mdat` range go (see
    /// [`crate::growing`]); each is recorded in [checkpoint] under [media_key],
    /// so the regular upload of the finished file skips it. Best effort: a
    /// network error just ends early — the regular upload sends the rest.
    /// Returns when [is_done] turns true and nothing settled is left, or on cancel.
    #[allow(clippy::too_many_arguments)]
    pub async fn preupload_growing(
        &self,
        growing: &std::path::Path,
        filename: &str,
        signer: &crate::blossom::MediaSigner,
        media_key: [u8; 32],
        checkpoint: &dyn crate::resume::ChunkCheckpoint,
        estimate: u64,
        on_progress: &(dyn Fn(u64, u64) + Sync),
        is_done: &(dyn Fn() -> bool + Sync),
        is_cancelled: &(dyn Fn() -> bool + Sync),
    ) -> MediaResult<u64> {
        use futures::stream::StreamExt;
        use std::sync::atomic::{AtomicU64, Ordering};

        const C: u64 = crate::crypto::STREAM_CHUNK_SIZE as u64;
        let batcher = crate::resume::ConfirmationBatcher::new(Some(checkpoint));
        let sent = AtomicU64::new(0);
        // Never "done" from the receiver's side: the header and the tail are still to come.
        let total_for = |settled: u64| estimate.max(settled + C);

        let ready = futures::stream::unfold(None::<u32>, move |next| async move {
            loop {
                // Done: the finished file goes through the regular upload from here.
                if is_cancelled() || is_done() { return None; }
                if let Some((start, end)) = crate::growing::settled_range(growing) {
                    let idx = next.unwrap_or(start.div_ceil(C) as u32);
                    let lo = idx as u64 * C;
                    if lo >= start && lo + C <= end {
                        if let Some(pt) = crate::growing::read_chunk(growing, idx) {
                            if pt.len() as u64 == C {
                                return Some(((idx, pt, end), Some(idx + 1)));
                            }
                        }
                    }
                }
                tokio::time::sleep(std::time::Duration::from_millis(150)).await;
            }
        });

        let uploads = ready
            .map(|(idx, pt, settled)| {
                let key = &media_key;
                let batcher = &batcher;
                let sent = &sent;
                async move {
                    batcher.flush_if_stale();
                    let enc = StreamCrypto::encrypt_chunk(&pt, key, idx)?;
                    drop(pt);
                    let blob = crate::blob_transport::wrap_chunk(key, idx, &enc.ciphertext)?;
                    let oid = crate::media_ref::TransportObjectId::of_bytes(&blob);
                    let locations = tokio::time::timeout(
                        CHUNK_DEADLINE,
                        upload_blob_mirrored(&self.blossom, &blob, signer, crate::blob_transport::CHUNK_CONTENT_TYPE),
                    )
                    .await
                    .map_err(|_| MediaError::UploadFailed(format!("chunk {idx}: no copy within {}s", CHUNK_DEADLINE.as_secs())))??;
                    tracing::info!(target: "alpha_ffi", "CHUNK_EARLY idx={idx} out={filename}");
                    batcher.add(crate::resume::ConfirmedChunk {
                        index: idx,
                        total: 0,
                        transport_object_id: oid,
                        plaintext_bytes: enc.original_size,
                        ciphertext_bytes: enc.ciphertext.len() as u64,
                        chunk_hash: hex::encode(enc.hash),
                        locations,
                    });
                    let done = sent.fetch_add(enc.original_size, Ordering::Relaxed) + enc.original_size;
                    on_progress(done, total_for(settled));
                    Ok::<(), MediaError>(())
                }
            })
            .buffer_unordered(UPLOAD_CONCURRENCY);
        futures::pin_mut!(uploads);
        let mut failure = None;
        while let Some(r) = uploads.next().await {
            if let Err(e) = r { failure = Some(e); break; }
        }
        drop(uploads);
        batcher.flush();
        if is_cancelled() { return Err(MediaError::Cancelled); }
        if let Some(e) = failure {
            tracing::warn!(target: "alpha_ffi", "EARLY_STOP out={filename} why={e}");
        }
        Ok(sent.load(Ordering::Relaxed))
    }

    /// Download and decrypt a single streaming chunk by index.
    ///
    /// Used by the ExoPlayer `EncryptedDataSource` to fetch individual chunks
    /// for progressive playback. Each chunk is independently verified via
    /// BLAKE3 hash + AEAD tag before returning the plaintext.
    #[instrument(skip(self, media_key, locations), fields(chunk_index))]
    pub async fn download_stream_chunk(
        &self,
        media_key: &[u8; 32],
        locations: &[ChunkLocation],
        chunk_index: u32,
        chunk_hash_hex: &str,
        // Explicit object identity when the ref carries one. None — an
        // old message: identity is recovered from the address.
        explicit_id: Option<&crate::media_ref::TransportObjectId>,
        // Expectations from the AUTHENTICATED manifest. None — nothing to cross-check against.
        context: Option<crate::limits::ChunkContext>,
    ) -> MediaResult<Vec<u8>> {
        debug!("Downloading stream chunk {chunk_index}");

        // Fetch the blob from ANY of its mirrors (fallback between them), then
        // unwrap the transport shell (BlobEnvelope). Legacy blobs (uploaded before
        // the envelope existed) are raw ciphertext and pass through — see `unwrap_blob`.
        if locations.len() > crate::limits::MAX_LOCATIONS_PER_CHUNK {
            return Err(MediaError::InvalidStreamManifest(format!(
                "chunk has {} addresses over the limit {}", locations.len(), crate::limits::MAX_LOCATIONS_PER_CHUNK
            )));
        }
        let blob = download_blob_locations(&self.blossom, explicit_id, locations, chunk_index as usize, DownloadPolicy::RoundRobin).await?;
        if blob.len() as u64 > crate::limits::MAX_ENVELOPE_BYTES {
            return Err(MediaError::InvalidStreamManifest("envelope over the limit".into()));
        }
        // Envelope fields are CROSS-CHECKED against expectations from an
        // authenticated source, not used as truth: they sit outside AEAD.
        if let Some(ctx) = context {
            if let Ok(env) = crate::blob_transport::active_codec().decode(&blob) {
                crate::limits::check_chunk_context(ctx, env.chunk, env.total)?;
            }
        }
        let ciphertext = crate::blob_transport::unwrap_chunk(blob, media_key, chunk_index);

        // Reconstruct the EncryptedStreamChunk with the derived nonce
        let nonce = StreamCrypto::derive_nonce(media_key, chunk_index);
        let expected_hash: [u8; 32] = hex::decode(chunk_hash_hex)
            .map_err(|e| MediaError::InvalidStreamManifest(format!("Invalid chunk hash hex: {e}")))?
            .try_into()
            .map_err(|_| MediaError::InvalidStreamManifest("Chunk hash must be 32 bytes".into()))?;

        let chunk = EncryptedStreamChunk {
            index: chunk_index,
            nonce,
            ciphertext,
            hash: expected_hash,
            original_size: 0, // Not needed for single-chunk decrypt
        };

        // Decrypt and verify (AEAD tag + BLAKE3 hash)
        let plaintext = StreamCrypto::decrypt_chunk(&chunk, media_key)?;
        debug!(chunk_index, plaintext_size = plaintext.len(), "Stream chunk decrypted and verified");
        Ok(plaintext)
    }

    /// Download a range of bytes from a specific stream chunk URL and decrypt it.
    ///
    /// Used for seeking in the streaming player — downloads only the needed
    /// chunk via HTTP Range request, then decrypts it.
    pub async fn download_stream_chunk_range(
        &self,
        media_key: &[u8; 32],
        locations: &[ChunkLocation],
        chunk_index: u32,
        chunk_hash_hex: &str,
        _byte_start: u64,
        _byte_end: u64,
    ) -> MediaResult<Vec<u8>> {
        // For per-chunk E2EE, we must download the ENTIRE encrypted chunk
        // (can't do partial AEAD decrypt). The Range request would only help
        // if the chunk is stored as part of a larger blob. Since each chunk
        // is uploaded as its own Blossom blob, we download the whole thing.
        self.download_stream_chunk(media_key, locations, chunk_index, chunk_hash_hex, None, None).await
    }

    /// Full streaming download: download all chunks, decrypt each, concatenate.
    ///
    /// Progress is reported per-chunk. This is the streaming equivalent of
    /// the legacy `download_with_progress`.
    /// TRUE streaming download: network → decrypt → write → flush → drop, one
    /// chunk at a time, straight to `output_path`. The file is NEVER assembled in
    /// memory — peak RAM is O(DOWNLOAD_CONCURRENCY × chunk), independent of the
    /// file size — so a 150 MB document/APK/ZIP downloads without thrashing the
    /// heap (mirror of `upload_streaming_file` on the send side).
    ///
    /// Chunks are fetched + decrypted with bounded concurrency but yielded IN
    /// INDEX ORDER by `buffered`, so we can write straight through without a
    /// reorder buffer. BLAKE3 is folded in incrementally as each chunk lands, so
    /// integrity is still verified end-to-end without ever holding the whole file.
    /// On ANY error the partial file is removed (a half-written blob must never be
    /// mistaken for a cached file).
    pub async fn download_streaming_to_file(
        &self,
        media_key: &[u8; 32],
        media_hash: &[u8; 32],
        chunk_infos: &[StreamChunkUploadInfo],
        output_path: &std::path::Path,
        on_progress: &(dyn Fn(u64, u64) + Sync),
        // Kept sealed on disk, `.part` included, when given.
        vault: Option<&crate::vault::VaultKey>,
    ) -> MediaResult<()> {
        use futures::stream::StreamExt;
        use std::sync::atomic::{AtomicU64, Ordering};

        if chunk_infos.is_empty() {
            return Err(MediaError::NoChunkUrls);
        }
        // The sizes come from the sender's ref: checked before any byte is
        // fetched or any space is taken.
        if chunk_infos.len() > crate::limits::MAX_CHUNKS as usize {
            return Err(MediaError::SerializationError(format!("limits: {} chunks", chunk_infos.len())));
        }
        for c in chunk_infos {
            crate::limits::check_declared_sizes(c.original_size, c.ciphertext_size)?;
        }
        info!(
            chunk_count = chunk_infos.len(),
            "Starting TRUE streaming download (O(chunk) memory, streamed to disk)",
        );

        let total: u64 = chunk_infos.iter().map(|c| c.original_size).sum::<u64>().max(1);
        let io_err = |e: std::io::Error| MediaError::EncryptionFailed(format!("stream download io: {e}"));

        // The bytes land in `<output>.part`; `output` appears only by an atomic
        // rename after the whole-file hash checks out. A process killed mid-way can
        // therefore never leave a half file under the final name that the app would
        // take for a complete one.
        let part_path = std::path::PathBuf::from(format!("{}.part", output_path.display()));

        // RESUME: a `.part` left by an earlier attempt holds whole chunks — each was
        // written only after its own AEAD + hash checked out. Keep every whole chunk
        // it has, cut a torn tail, continue with the next chunk. A dropped connection
        // at 480 MB of 500 no longer means starting again from zero.
        let mut file = crate::vault::MediaFile::open(&part_path, vault).map_err(io_err)?;
        let (resume_chunks, resume_bytes) = match file.len() {
            Ok(have) => {
                let mut acc = 0u64;
                let mut n = 0usize;
                for c in chunk_infos {
                    if acc + c.original_size > have { break; }
                    acc += c.original_size;
                    n += 1;
                }
                (n, acc)
            }
            Err(_) => (0, 0),
        };
        let downloaded = AtomicU64::new(resume_bytes);
        if resume_chunks > 0 {
            info!(resume_chunks, resume_bytes, "Resuming streaming download from .part");
        }

        // Whole write path in a closure so any early-return (`?`) is handled below.
        let result: MediaResult<()> = async {
            file.truncate(resume_bytes).map_err(io_err)?; // drop a torn last chunk
            // Incremental BLAKE3 — the file is never held whole just to hash it. The
            // kept prefix is re-hashed by reading it back in chunk-sized pieces.
            let mut hasher = blake3::Hasher::new();
            if resume_bytes > 0 {
                let mut buf = vec![0u8; crate::crypto::STREAM_CHUNK_SIZE];
                let mut at = 0u64;
                while at < resume_bytes {
                    let want = (buf.len() as u64).min(resume_bytes - at) as usize;
                    file.read_at(at, &mut buf[..want]).map_err(io_err)?;
                    hasher.update(&buf[..want]);
                    at += want as u64;
                }
                on_progress(resume_bytes.min(total), total);
            }
            let mut written = resume_bytes;

            // TEMP (PIPE_TRACE) stage telemetry: where the wall-clock actually goes,
            // so speed decisions are made on measurement, not feeling. `net`/`dec`
            // accumulate across the concurrent producers (atomics); `write` is the
            // sequential consumer. `net` is wall-time summed over chunks, so with
            // DOWNLOAD_CONCURRENCY in flight it can exceed the real elapsed time.
            let net_us = AtomicU64::new(0);
            let dec_us = AtomicU64::new(0);
            let mut write_us: u64 = 0;
            let t_all = std::time::Instant::now();
            // Per-file cap on chunks in flight (the window only buffers results).
            let file_slots = tokio::sync::Semaphore::new(DOWNLOAD_CONCURRENCY);

            // Lazy, ordered, bounded-concurrency producer. `buffered` keeps at most
            // DOWNLOAD_CONCURRENCY fetch+decrypt futures in flight AND yields their
            // results IN INDEX ORDER, so the write side stays sequential with no
            // reorder buffer — peak RAM is O(DOWNLOAD_CONCURRENCY × chunk).
            let mut chunks = futures::stream::iter(chunk_infos.iter().enumerate().skip(resume_chunks))
                .map(|(i, chunk_info)| {
                    let blossom = &self.blossom;
                    let net_us = &net_us;
                    let dec_us = &dec_us;
                    let file_slots = &file_slots;
                    let downloaded = &downloaded;
                    async move {
                        // GLOBAL chunk-slot pool: hold a slot ONLY for the network
                        // fetch (the scarce resource). Every file runs at once but the
                        // total in-flight chunk downloads across all files is capped,
                        // and the permit frees the instant this chunk's bytes land →
                        // the next waiting chunk of ANY file grabs it.
                        let t_net = std::time::Instant::now();
                        let blob = {
                            let _file_slot = file_slots.acquire().await
                                .map_err(|_| MediaError::PipelineError("file slot pool closed".into()))?;
                            let _slot = chunk_slots().acquire().await
                                .map_err(|_| MediaError::PipelineError("chunk slot pool closed".into()))?;
                            download_blob_locations(blossom, chunk_info.transport_object_id.as_ref(), &chunk_info.locations, i, DownloadPolicy::RoundRobin).await?
                        };
                        net_us.fetch_add(t_net.elapsed().as_micros() as u64, Ordering::Relaxed);
                        let ciphertext = crate::blob_transport::unwrap_chunk(blob, media_key, i as u32);
                        let nonce = StreamCrypto::derive_nonce(media_key, i as u32);
                        let expected_hash: [u8; 32] = hex::decode(&chunk_info.hash_hex)
                            .map_err(|e| MediaError::InvalidStreamManifest(format!("Invalid hash: {e}")))?
                            .try_into()
                            .map_err(|_| MediaError::InvalidStreamManifest("Hash must be 32 bytes".into()))?;
                        let chunk = EncryptedStreamChunk {
                            index: i as u32,
                            nonce,
                            ciphertext,
                            hash: expected_hash,
                            original_size: chunk_info.original_size,
                        };
                        // Decrypt THIS chunk only — the file is never assembled in RAM.
                        let t_dec = std::time::Instant::now();
                        let plaintext = StreamCrypto::decrypt_chunk(&chunk, media_key)?;
                        dec_us.fetch_add(t_dec.elapsed().as_micros() as u64, Ordering::Relaxed);
                        // Progress counts verified bytes as they arrive, not only the
                        // in-order prefix: one late chunk must not freeze the bar.
                        let len = plaintext.len() as u64;
                        on_progress((downloaded.fetch_add(len, Ordering::Relaxed) + len).min(total), total);
                        Ok::<Vec<u8>, MediaError>(plaintext)
                    }
                })
                .buffered(DOWNLOAD_WINDOW);

            tracing::warn!(target: "alpha_ffi", "DL_START chunks={} from={} servers={:?}", chunk_infos.len(), resume_chunks,
                chunk_infos.first().map(|c| c.locations.iter().map(|l| l.relay.clone()).collect::<Vec<_>>()).unwrap_or_default());
            // network → decrypt → hash → write → flush → drop → next chunk.
            loop {
                let Some(item) = chunks.next().await else { break };
                let plaintext = item?;
                let t_w = std::time::Instant::now();
                hasher.update(&plaintext);
                file.write_at(written, &plaintext).map_err(io_err)?;
                written += plaintext.len() as u64;
                write_us += t_w.elapsed().as_micros() as u64;
                drop(plaintext); // release the chunk buffer immediately
            }
            file.sync().map_err(io_err)?;

            // Verify the overall BLAKE3 — computed incrementally above, never over
            // a whole in-memory copy.
            let computed = hasher.finalize();
            if computed.as_bytes() != media_hash {
                return Err(MediaError::IntegrityCheckFailed {
                    expected: hex::encode(media_hash),
                    actual: hex::encode(computed.as_bytes()),
                });
            }
            // Only now the file exists under its real name.
            std::fs::rename(&part_path, output_path).map_err(io_err)?;
            tracing::info!(target: "alpha_ffi", "STAGES chunks={} bytes={} elapsed={}ms net_sum={}ms decrypt={}ms write={}ms",
                chunk_infos.len(), total, t_all.elapsed().as_millis(),
                net_us.load(Ordering::Relaxed) / 1000, dec_us.load(Ordering::Relaxed) / 1000, write_us / 1000);
            on_progress(total, total);
            Ok(())
        }
        .await;

        if let Err(ref e) = result {
            // A wrong whole-file hash means the kept bytes cannot be trusted: start
            // over next time. Any other failure (network, a bad chunk, a kill) keeps
            // the verified chunks in `.part` for the next attempt to resume from.
            let corrupt = matches!(e, MediaError::IntegrityCheckFailed { .. });
            tracing::warn!(target: "alpha_ffi", "DOWNLOAD_ABORT reason={e} downloadedBytes={} total={} chunks={} — {}", downloaded.load(std::sync::atomic::Ordering::Relaxed), total, chunk_infos.len(), if corrupt { "removing .part" } else { "keeping .part to resume" });
            if corrupt {
                let _ = std::fs::remove_file(&part_path);
            }
        }
        result?;
        info!("Streaming download completed and verified (streamed to disk)");
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_test_pipeline() -> MediaPipeline {
        MediaPipeline::new(BlossomClient::new("https://blossom.example.com"))
    }

    #[test]
    fn test_pipeline_new() {
        let _pipeline = make_test_pipeline();
    }

    #[test]
    fn test_download_empty_urls_fails() {
        let pipeline = make_test_pipeline();
        let rt = tokio::runtime::Runtime::new().expect("runtime should create");

        let result = rt.block_on(async {
            pipeline.download(&[0u8; 32], &[0u8; 32], &[], 0).await
        });

        assert!(matches!(result, Err(MediaError::NoChunkUrls)));
    }

    #[test]
    fn test_encrypt_decrypt_pipeline_local() {
        // Test the crypto + chunking parts without network
        let key = generate_key();
        let data = b"Test file content for local pipeline test";
        let hash = blake3_hash(data);

        // Encrypt
        let encrypted = MediaCrypto::encrypt(data, &key).expect("encrypt should work");

        // Chunk
        let chunks = EncryptedChunker::split_encrypted(&encrypted).expect("split should work");

        // Reassemble
        let reassembled = EncryptedChunker::join_encrypted(&chunks).expect("join should work");

        // Decrypt
        let decrypted = MediaCrypto::decrypt(&reassembled, &key).expect("decrypt should work");

        // Verify hash
        let computed_hash = blake3_hash(&decrypted);
        assert_eq!(computed_hash, hash);
        assert_eq!(decrypted, data.to_vec());
    }
}

#[cfg(test)]
mod t2_discovery {
    use super::content_id_from_blossom_url as parse;

    const H: &str = "a01e2b4d620777e41a774c078b3577ce90b5faf88e8f0686f63193aa1083fc3e";

    #[test]
    fn t2_accepts_bare_hash_and_known_extensions() {
        assert_eq!(parse(&format!("https://nostr.download/{H}")).as_deref(), Some(H));
        assert_eq!(parse(&format!("https://nostr.download/{H}.json")).as_deref(), Some(H));
        assert_eq!(parse(&format!("https://nostr.download/{H}.bin")).as_deref(), Some(H));
    }

    #[test]
    fn t2_ignores_query_and_fragment() {
        assert_eq!(parse(&format!("https://s/{H}.json?x=1")).as_deref(), Some(H));
        assert_eq!(parse(&format!("https://s/{H}#frag")).as_deref(), Some(H));
    }

    /// Anything not shaped like a lower-case hex SHA-256 is not an identity.
    ///
    /// Otherwise "discovery" would turn into requesting an arbitrary path
    /// on our servers from a foreign message's string.
    #[test]
    fn t2_rejects_anything_that_is_not_a_hash() {
        for bad in [
            "https://s/media/video.mp4",
            "https://s/upload",
            "https://s/",
            &format!("https://s/{}", &H[..63]),          // too short
            &format!("https://s/{}f", H),                // too long
            &format!("https://s/{}", H.to_uppercase()),  // upper case
        ] {
            assert!(parse(bad).is_none(), "a forbidden address was accepted: {bad}");
        }
    }

    /// Garbage IN THE PATH is safe — and that is not a relaxation.
    ///
    /// Only the object identity is taken from the address; the request is
    /// built by us: `{our server}/{hash}`. So neither `..`, nor a foreign
    /// host, nor any other path from the hint is carried over — only 64 hex
    /// characters. The test pins exactly that property: parsing extracts the
    /// hash, it does not trust the address.
    #[test]
    fn t2_only_the_identity_survives_parsing() {
        assert_eq!(parse(&format!("https://evil.example/../../{H}")).as_deref(), Some(H));
        assert_eq!(parse(&format!("https://evil.example/a/b/c/{H}.json")).as_deref(), Some(H));
    }

    /// Directory traversal via a segment does not pass: the last segment is
    /// taken, and it must be a pure hash.
    #[test]
    fn t2_path_traversal_cannot_smuggle_a_hash() {
        assert!(parse(&format!("https://s/{H}/../../etc/passwd")).is_none());
    }
}

#[cfg(test)]
mod t2_acceptance {
    use super::*;
    use crate::media_ref::TransportObjectId;
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    /// A tiny HTTP server: serves pre-loaded objects by path and counts
    /// requests. Needed because "A is dead, the object is on B" cannot be
    /// proven on live servers: there one can neither kill a node nor swap
    /// the bytes. The request counts prove the search's upper bound.
    struct FakeStore {
        addr: String,
        hits: Arc<AtomicUsize>,
    }

    async fn spawn_store(objects: HashMap<String, Vec<u8>>) -> FakeStore {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = format!("http://{}", listener.local_addr().unwrap());
        let hits = Arc::new(AtomicUsize::new(0));
        let h = hits.clone();
        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else { return };
                let objects = objects.clone();
                let h = h.clone();
                tokio::spawn(async move {
                    use tokio::io::{AsyncReadExt, AsyncWriteExt};
                    let mut buf = [0u8; 2048];
                    let n = sock.read(&mut buf).await.unwrap_or(0);
                    if n == 0 { return; }
                    h.fetch_add(1, Ordering::Relaxed);
                    let req = String::from_utf8_lossy(&buf[..n]).to_string();
                    let path = req.split_whitespace().nth(1).unwrap_or("/").trim_start_matches('/').to_string();
                    let resp = match objects.get(&path) {
                        Some(body) => {
                            let mut r = format!(
                                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                                body.len()
                            ).into_bytes();
                            r.extend_from_slice(body);
                            r
                        }
                        None => b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),
                    };
                    let _ = sock.write_all(&resp).await;
                    let _ = sock.shutdown().await;
                });
            }
        });
        FakeStore { addr, hits }
    }

    /// A server that answers every request with an error, counting them.
    async fn spawn_failing_store() -> FakeStore {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = format!("http://{}", listener.local_addr().unwrap());
        let hits = Arc::new(AtomicUsize::new(0));
        let h = hits.clone();
        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else { return };
                let h = h.clone();
                tokio::spawn(async move {
                    use tokio::io::{AsyncReadExt, AsyncWriteExt};
                    let mut buf = [0u8; 2048];
                    if sock.read(&mut buf).await.unwrap_or(0) == 0 { return; }
                    h.fetch_add(1, Ordering::Relaxed);
                    let _ = sock.write_all(b"HTTP/1.1 502 Bad Gateway
Content-Length: 0
Connection: close

").await;
                });
            }
        });
        FakeStore { addr, hits }
    }

    #[test]
    fn a_failing_server_rests_two_then_four_minutes_and_never_longer_than_thirty() {
        assert_eq!(rest_for(2), None);
        assert_eq!(rest_for(3), Some(std::time::Duration::from_secs(120)));
        assert_eq!(rest_for(4), Some(std::time::Duration::from_secs(240)));
        assert_eq!(rest_for(20), Some(std::time::Duration::from_secs(1800)));
    }

    #[tokio::test]
    async fn a_failing_server_gets_one_more_try_then_rests() {
        let obj = body(21);
        let id = TransportObjectId::of_bytes(&obj);
        let bad = spawn_failing_store().await;
        let client = crate::blossom::BlossomClient::with_servers(&[bad.addr.clone()]).listed_only();
        let at = [loc(&bad.addr, id.as_hex())];

        let first = download_blob_locations(&client, Some(&id), &at, 0, DownloadPolicy::RoundRobin).await;
        assert!(!matches!(first, Ok(_) | Err(MediaError::Gone)), "a server error is not \"gone\"");
        assert_eq!(bad.hits.load(Ordering::Relaxed), 2, "one try and one more");

        let _ = download_blob_locations(&client, Some(&id), &at, 0, DownloadPolicy::RoundRobin).await;
        assert_eq!(bad.hits.load(Ordering::Relaxed), 3, "the third failure in a row starts its rest");

        let resting = download_blob_locations(&client, Some(&id), &at, 0, DownloadPolicy::RoundRobin).await;
        assert!(resting.is_err());
        assert_eq!(bad.hits.load(Ordering::Relaxed), 3, "a resting server is not asked");
    }

    #[tokio::test]
    async fn not_here_everywhere_is_final_after_one_ask_each() {
        let obj = body(22);
        let id = TransportObjectId::of_bytes(&obj);
        let a = spawn_store(HashMap::new()).await;
        let b = spawn_store(HashMap::new()).await;
        let client = crate::blossom::BlossomClient::with_servers(&[a.addr.clone(), b.addr.clone()]).listed_only();
        let got = download_blob_locations(&client, Some(&id), &[loc(&a.addr, id.as_hex())], 0, DownloadPolicy::RoundRobin).await;
        assert!(matches!(got, Err(MediaError::Gone)));
        assert_eq!(a.hits.load(Ordering::Relaxed) + b.hits.load(Ordering::Relaxed), 2, "each server asked once");
    }

    fn body(seed: u8) -> Vec<u8> {
        (0..512u32).map(|i| (i as u8) ^ seed).collect()
    }

    fn loc(base: &str, id: &str) -> ChunkLocation {
        ChunkLocation { relay: base.to_string(), url: format!("{base}/{id}") }
    }

    /// A store whose objects can arrive after it started answering.
    async fn spawn_late_store(objects: Arc<std::sync::Mutex<HashMap<String, Vec<u8>>>>) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else { return };
                let objects = objects.clone();
                tokio::spawn(async move {
                    use tokio::io::{AsyncReadExt, AsyncWriteExt};
                    let mut buf = [0u8; 2048];
                    let n = sock.read(&mut buf).await.unwrap_or(0);
                    let req = String::from_utf8_lossy(&buf[..n]).to_string();
                    let path = req.split_whitespace().nth(1).unwrap_or("/").trim_start_matches('/').to_string();
                    let found = objects.lock().unwrap().get(&path).cloned();
                    let resp = match found {
                        Some(body) => {
                            let mut r = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len()).into_bytes();
                            r.extend_from_slice(&body);
                            r
                        }
                        None => b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),
                    };
                    let _ = sock.write_all(&resp).await;
                    let _ = sock.shutdown().await;
                });
            }
        });
        addr
    }

    /// A just-published file: its listed server is out of reach from this network, and
    /// its copy lands on B a moment after B said "not here". A repeat must find it.
    #[tokio::test]
    async fn a_copy_that_lands_after_not_here_is_found() {
        let obj = body(9);
        let id = TransportObjectId::of_bytes(&obj);
        let late: Arc<std::sync::Mutex<HashMap<String, Vec<u8>>>> = Default::default();
        let b = spawn_late_store(late.clone()).await;
        let dead = "http://127.0.0.1:9".to_string();
        let client = crate::blossom::BlossomClient::with_servers(&[dead.clone(), b]);
        let (key, value) = (id.as_hex().to_string(), obj.clone());
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
            late.lock().unwrap().insert(key, value);
        });
        let got = download_blob_locations(&client, Some(&id), &[loc(&dead, id.as_hex())], 0, DownloadPolicy::RoundRobin)
            .await
            .expect("the late copy must be found");
        assert_eq!(got, obj);
    }

    /// A→B: the ref knows only A, A lacks the object, the object is on B.
    #[tokio::test]
    async fn t2_chunk_discovered_on_second_server() {
        let obj = body(1);
        let id = TransportObjectId::of_bytes(&obj);
        let a = spawn_store(HashMap::new()).await;                                  // empty
        let b = spawn_store(HashMap::from([(id.as_hex().to_string(), obj.clone())])).await;

        let client = crate::blossom::BlossomClient::with_servers(&[a.addr.clone(), b.addr.clone()]);
        let got = download_blob_locations(
            &client, Some(&id), &[loc(&a.addr, id.as_hex())], 0, DownloadPolicy::RoundRobin,
        ).await.expect("the object must be found on the second server");
        assert_eq!(got, obj);
    }

    /// Corruption: B serves OTHER bytes at the right address.
    ///
    /// The check is that object identity and content diverge — accepting
    /// this must not happen. Cryptographic properties (AEAD/BLAKE3) are
    /// proven by the Security Gate; this is the transport layer.
    #[tokio::test]
    async fn t2_corrupted_object_does_not_match_identity() {
        let real = body(2);
        let id = TransportObjectId::of_bytes(&real);
        let tampered = body(3);
        let b = spawn_store(HashMap::from([(id.as_hex().to_string(), tampered.clone())])).await;
        let a = spawn_store(HashMap::new()).await;

        let client = crate::blossom::BlossomClient::with_servers(&[a.addr.clone(), b.addr.clone()]);
        let got = download_blob_locations(
            &client, Some(&id), &[loc(&a.addr, id.as_hex())], 0, DownloadPolicy::RoundRobin,
        ).await.unwrap();
        assert_ne!(got, real, "the stand must serve swapped bytes");
        assert_ne!(
            TransportObjectId::of_bytes(&got), id,
            "a swapped object's identity must differ from the declared one",
        );
    }

    /// Partial replication: B has objects 1 and 3 but not 2. A miss on
    /// object 2 must NOT strike out B — object 3 must still be found.
    #[tokio::test]
    async fn t2_partial_replication_404_does_not_ban_server() {
        let (o1, o2, o3) = (body(11), body(12), body(13));
        let (i1, i2, i3) = (
            TransportObjectId::of_bytes(&o1),
            TransportObjectId::of_bytes(&o2),
            TransportObjectId::of_bytes(&o3),
        );
        let a = spawn_store(HashMap::new()).await;
        let b = spawn_store(HashMap::from([
            (i1.as_hex().to_string(), o1.clone()),
            (i3.as_hex().to_string(), o3.clone()),
        ])).await;
        let client = crate::blossom::BlossomClient::with_servers(&[a.addr.clone(), b.addr.clone()]);

        let g1 = download_blob_locations(&client, Some(&i1), &[loc(&a.addr, i1.as_hex())], 0, DownloadPolicy::RoundRobin).await;
        assert!(g1.is_ok(), "object 1 must be found");
        let g2 = download_blob_locations(&client, Some(&i2), &[loc(&a.addr, i2.as_hex())], 0, DownloadPolicy::RoundRobin).await;
        assert!(g2.is_err(), "object 2 is nowhere");
        let g3 = download_blob_locations(&client, Some(&i3), &[loc(&a.addr, i3.as_hex())], 0, DownloadPolicy::RoundRobin).await;
        assert_eq!(g3.expect("object 3 must be found after the miss on object 2"), o3);
    }

    /// Request upper bound: the search is limited by the number of configured
    /// servers, and a repeated miss on the same (server, object) pair touches no network.
    #[tokio::test]
    async fn t2_discovery_request_count_is_bounded() {
        let obj = body(7);
        let id = TransportObjectId::of_bytes(&obj);
        let a = spawn_store(HashMap::new()).await;
        let b = spawn_store(HashMap::new()).await;
        let client = crate::blossom::BlossomClient::with_servers(&[a.addr.clone(), b.addr.clone()]);

        let _ = download_blob_locations(&client, Some(&id), &[loc(&a.addr, id.as_hex())], 0, DownloadPolicy::RoundRobin).await;
        let after_first = a.hits.load(Ordering::Relaxed) + b.hits.load(Ordering::Relaxed);

        let _ = download_blob_locations(&client, Some(&id), &[loc(&a.addr, id.as_hex())], 0, DownloadPolicy::RoundRobin).await;
        let after_second = a.hits.load(Ordering::Relaxed) + b.hits.load(Ordering::Relaxed);

        // First pass: MAX_CHUNK_ATTEMPTS tries on the hint + at most one
        // request to every unprobed configured server.
        assert!(after_first <= MAX_CHUNK_ATTEMPTS + 2, "the first search made {after_first} requests");
        // The second pass on the same object must not probe B again:
        // the (B, object) miss is already remembered.
        let discovery_second = after_second - after_first;
        assert!(
            discovery_second <= MAX_CHUNK_ATTEMPTS,
            "the second search made {discovery_second} requests: the negative cache did not work",
        );
    }

    /// A server that hangs its FIRST request (a stalled CDN connection) and
    /// answers every later one at once.
    async fn spawn_stalling_store(path: String, obj: Vec<u8>) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = format!("http://{}", listener.local_addr().unwrap());
        let first = Arc::new(std::sync::atomic::AtomicBool::new(true));
        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else { return };
                let (path, obj, first) = (path.clone(), obj.clone(), first.clone());
                tokio::spawn(async move {
                    use tokio::io::{AsyncReadExt, AsyncWriteExt};
                    let mut buf = [0u8; 2048];
                    if sock.read(&mut buf).await.unwrap_or(0) == 0 { return; }
                    if first.swap(false, Ordering::SeqCst) {
                        tokio::time::sleep(std::time::Duration::from_secs(60)).await;
                        return;
                    }
                    let req = String::from_utf8_lossy(&buf).to_string();
                    let ok = req.split_whitespace().nth(1).map(|p| p.trim_start_matches('/') == path).unwrap_or(false);
                    let mut r = if ok {
                        format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", obj.len()).into_bytes()
                    } else {
                        b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec()
                    };
                    if ok { r.extend_from_slice(&obj); }
                    let _ = sock.write_all(&r).await;
                    let _ = sock.shutdown().await;
                });
            }
        });
        addr
    }

    /// A stalled request must not stall the chunk: a hedge (fresh connection)
    /// is raced against it and wins long before the attempt timeout.
    #[tokio::test]
    async fn stalled_request_is_hedged() {
        let obj = body(21);
        let id = TransportObjectId::of_bytes(&obj);
        let a = spawn_stalling_store(id.as_hex().to_string(), obj.clone()).await;
        let client = crate::blossom::BlossomClient::with_servers(&[a.clone()]);
        let t0 = std::time::Instant::now();
        let got = download_blob_locations(&client, Some(&id), &[loc(&a, id.as_hex())], 0, DownloadPolicy::RoundRobin)
            .await
            .expect("the chunk must come through the hedge request");
        assert_eq!(got, obj);
        assert!(t0.elapsed() < std::time::Duration::from_secs(HEDGE_MAX_MS / 1000 + 2), "the hedge did not fire: {:?}", t0.elapsed());
    }
}

#[cfg(test)]
mod upload_budget {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    /// The upload limit is per-process, not per-file.
    ///
    /// The regression this test guards against: a permit was once taken for
    /// a whole file, and one large video held the link for tens of seconds.
    /// We check observed behavior, not a counter — how many requests were
    /// really in flight at once when four times more were requested.
    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn never_more_than_budget_in_flight() {
        let live = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let mut tasks = Vec::new();
        for _ in 0..(GLOBAL_UPLOAD_SLOTS * 8) {
            let live = live.clone();
            let peak = peak.clone();
            tasks.push(tokio::spawn(async move {
                let _permit = upload_slots().acquire().await.unwrap();
                let now = live.fetch_add(1, Ordering::SeqCst) + 1;
                peak.fetch_max(now, Ordering::SeqCst);
                tokio::time::sleep(std::time::Duration::from_millis(5)).await;
                live.fetch_sub(1, Ordering::SeqCst);
            }));
        }
        for t in tasks {
            t.await.unwrap();
        }
        let peak = peak.load(Ordering::SeqCst);
        assert!(
            peak <= GLOBAL_UPLOAD_SLOTS,
            "{peak} uploads at once over the limit {GLOBAL_UPLOAD_SLOTS}",
        );
        assert_eq!(live.load(Ordering::SeqCst), 0, "the permit did not come back");
    }

    /// The pre-retry pause takes NO slot: otherwise a failed chunk of a
    /// large file would block other uploads while transferring nothing.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn backoff_holds_no_permit() {
        let all = upload_slots().acquire_many(GLOBAL_UPLOAD_SLOTS as u32).await.unwrap();
        drop(all);
        let held = { let _p = upload_slots().acquire().await.unwrap(); };
        let _ = held;
        chunk_backoff(1).await;
        // Taking every slot, not counting free ones: neighbouring tests share the pool.
        tokio::time::timeout(
            std::time::Duration::from_secs(30),
            upload_slots().acquire_many(GLOBAL_UPLOAD_SLOTS as u32),
        )
        .await
        .expect("a slot stayed held during the retry pause")
        .expect("upload semaphore closed");
    }
}
