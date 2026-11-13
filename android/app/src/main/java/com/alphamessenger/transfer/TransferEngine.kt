package com.alphamessenger.transfer

import com.alphamessenger.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The ONLY thing that may decide whether media auto-downloads.
 *
 * Bubbles must never call this — it is invoked exclusively from
 * [TransferEngine.consider]. Keeping the policy in here is what makes "delete
 * cache" behave: eviction returns the item to [TransferState.Idle] without the
 * policy getting a second vote.
 */
fun interface TransferPolicy {
    fun allowAutoDownload(mediaType: String?, filename: String?, sizeBytes: Long): Boolean
}

/**
 * Everything the engine needs from the data layer. Keeps it free of any hard
 * dependency on the repository — and, per rule 3, free of Compose entirely.
 */
interface TransferRepository {
    /** Pure disk facts about a blob — never downloads. */
    fun cacheEntry(ref: String): CacheEntry

    /** THE "where are the bytes" resolver — the ONLY disk-truth entry the engine
     *  uses to fold availability into a snapshot. Original (sender file) > Cache >
     *  Streaming > Remote > Missing. Never downloads. */
    fun mediaAvailability(ref: String, senderLocalPath: String?): FileSource

    /** Download → decrypt → verify → cache. Reports bytes via [progress], keyed by
     *  [opId]. Returns the local path on success, null on failure. */
    suspend fun downloadTracked(ref: String, opId: String): String?

    /** Stop the native download running under [opId]. Cancelling the coroutine
     *  alone left it running to the end — the ✕ did nothing. */
    fun cancelDownload(opId: String) {}

    /** Remove the disposable bytes and return how many really went away. Zero
     *  means there was nothing to delete; the result must be reported truthfully,
     *  not inferred from someone else's bytes.
     *
     *  [deleteLocalCopy] — the send has finished, so its cached copy is
     *  disposable again. The caller decides: it needs a queue query, which must
     *  not run on the main thread. */
    fun deleteCache(ref: String, deleteLocalCopy: Boolean): Long

    /** Mark a ref as "user deliberately evicted from cache" — creates a marker
     *  file on disk (the Repository's domain). Survives ViewModel recreation.
     *  consider() checks [isCacheEvicted] and refuses to auto-download. */
    fun markCacheEvicted(ref: String)

    /** Remove the eviction marker — the user tapped ↓ to re-download. */
    fun unmarkCacheEvicted(ref: String)

    /** Was this ref deliberately evicted by the user? Checks the marker file. */
    fun isCacheEvicted(ref: String): Boolean

    /** Byte-level progress: (opId, done, total). Collected ONCE — by the engine. */
    val progress: SharedFlow<Triple<String, Long, Long>>

    /** Which blob server the transport is pointed at (for the Inspector). */
    val blobServer: String get() = "-"
}

/**
 * An in-flight transfer that is INDEPENDENT of the Compose lifecycle: it lives in
 * the engine's PROCESS-scoped coroutine scope (the engine is an app @Singleton),
 * so leaving the chat, scrolling the bubble off screen, or a recomposition never
 * kills or restarts it — and on return the ring picks up exactly where it was.
 */
private class Session(val key: TransferKey, val opId: String, val job: Job)

/**
 * The Unified Transfer Engine.
 *
 * Sole writer of transfer state, for **both** directions and **all** media types.
 * It owns the state map, the download sessions, the EMA speed and ETA, retry
 * bookkeeping, the auto-download policy, album grouping, the UI update throttle,
 * and the throughput measurement that drives adaptive parallelism.
 *
 * It knows nothing about Compose, bubbles, or media semantics — only
 * [TransferKey]s and [TransferGroup]s (rule 3).
 *
 * The UI reads [snapshots] / [groups] and dispatches a [TransferAction]. That is
 * the entire contract.
 */
class TransferEngine(
    private val scope: CoroutineScope,
    private val repo: TransferRepository,
    private val policy: TransferPolicy,
    private val codecName: String = "json-v1",
    private val envelopeVersion: Int = 1,
) {
    // --- Observable truth (immutable snapshots, throttled) ---

    private val _snapshots = MutableStateFlow<Map<String, TransferSnapshot>>(emptyMap())

    /** What every bubble reads. Emissions are coalesced — see [UI_TICK_MS]. */
    val snapshots: StateFlow<Map<String, TransferSnapshot>> = _snapshots.asStateFlow()

    private val _groups = MutableStateFlow<Map<String, TransferGroup>>(emptyMap())

    /** What every album reads. */
    val groups: StateFlow<Map<String, TransferGroup>> = _groups.asStateFlow()

    /**
 * Per-key flows.
     *
     * One [StateFlow] PER transfer key. A bubble subscribes to ONLY its own key,
     * so when file A ticks, only bubble A recomposes — every other bubble's
     * collector sees no emission at all. This replaces "publish a whole new Map
     * every 80 ms → every bubble's collectAsState fires" (the source of the
     * scroll-during-download lag).
     *
     * Nullable so the value is byte-for-byte what `snapshots.value[key]` was:
     * null when the engine holds nothing for the key (matches [downloadOf] /
     * [uploadOf]). Created lazily on first subscription and updated on the SAME
     * throttled [publish] tick — never at byte rate.
     */
    private val keyFlows = ConcurrentHashMap<String, MutableStateFlow<TransferSnapshot?>>()

    /** The single per-key window a bubble subscribes to. Idempotent per key. */
    fun flowFor(key: TransferKey): StateFlow<TransferSnapshot?> =
        keyFlows.getOrPut(key.value) { MutableStateFlow(live[key.value]) }.asStateFlow()

    /**
     * The live map. Progress ticks land here at transport speed (hundreds/sec);
     * the publisher below copies it into [_snapshots] at most [UI_TICK_MS] apart.
     * Terminal transitions are published immediately, so ✓ / ✗ never lag.
     */
    private val live = ConcurrentHashMap<String, TransferSnapshot>()
    private val dirty = java.util.concurrent.atomic.AtomicBoolean(false)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val opToKey = ConcurrentHashMap<String, String>()

    /**
     * Keys the user PAUSED. A native download cannot be interrupted mid-chunk, so
     * we keep the single background job running and merely detach the UI; Resume
     * re-attaches to the SAME job rather than starting a second one — that is what
     * stopped progress from bouncing around after a resume.
     */
    private val paused = ConcurrentHashMap.newKeySet<String>()

    /** Keys the user CANCELLED a download — terminal, policy must not revive.
     *  In-memory only (session scope). For cache eviction (which must survive
     *  ViewModel recreation), the Repository's marker file is the source of
     *  truth — see [TransferRepository.isCacheEvicted]. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** key → (lastBytes, lastSampleMs, emaBytesPerSec). */
    private val speed = ConcurrentHashMap<String, Triple<Long, Long, Double>>()

    /** key → last logged progress decile (debug tracing only). */
    private val decile = ConcurrentHashMap<String, Int>()

    /** groupId → member keys. */
    private val groupMembers = ConcurrentHashMap<String, List<TransferKey>>()
    /** groupId → fixed total bytes (sum of member sizes), registered up-front so the album denominator doesn't jump. */
    private val groupTotals = ConcurrentHashMap<String, Long>()

    // --- Auto-download re-trigger support ---
    // consider() is called once per bubble mount (LaunchedEffect(refJson)) and
    // never re-evaluates when the auto-download prefs or network change. This
    // map remembers every considered key + its arguments so reconsiderVisible()
    // can re-run the policy check when an invalidation signal fires.
    private data class ConsideredEntry(
        val mediaType: String?,
        val filename: String?,
        val sizeBytes: Long,
        val isOutgoing: Boolean,
        val senderLocalPath: String?,
        val opId: String,
    )
    private val considered = ConcurrentHashMap<String, ConsideredEntry>()

    // --- Inspector counters (rule 10) ---
    private val retries = AtomicInteger(0)
    private val peakBps = AtomicLong(0)
    private val emaLinkBps = AtomicLong(0)
    @Volatile private var lastPolicyDecision: String = "-"

    /**
     * Per-key state timeline (rule 7). Only *transitions* are recorded — progress
     * ticks would drown it — so a transfer reads back as
     * `Downloading → Paused → Downloading → Failed → Retrying → Completed`.
     * Bounded, so a long session can't grow it without limit.
     */
    private val timeline = ConcurrentHashMap<String, ArrayDeque<String>>()

    /** Ring buffer of the last [EVENT_LOG_MAX] events across all transfers (rule 8). */
    private val events = ArrayDeque<TransferEvent>()

    private fun logEvent(key: String, kind: TransferEvent.Kind, detail: String = "") {
        synchronized(events) {
            events.addLast(TransferEvent(System.currentTimeMillis(), key, kind, detail))
            while (events.size > EVENT_LOG_MAX) events.removeFirst()
        }
        // --- The log gets a reference to the transfer, not the transfer itself ---
        //
        // A download key is `dn:` plus the WHOLE MediaRef — which contains the
        // attachment encryption key and the storage addresses. Writing the string
        // as-is (also in release) put the key of every received file into the
        // system log, readable by anyone with access to the device.
        //
        // What goes out is a short fingerprint: it tells transfers apart in debug
        // and reveals nothing. The direction stays — it makes the event readable.
        if (BuildConfig.DEBUG) {
            android.util.Log.i(LOG_TAG, "$kind ${safeKey(key)} ${detail.ifBlank { "" }}".trim())
        }
    }

    /** `up:`/`dn:` + the first 8 chars of the fingerprint. Contains no secrets. */
    private fun safeKey(key: String): String {
        val prefix = key.substringBefore(':', "")
        val raw = key.substringAfter(':')
        val digest = Integer.toHexString(raw.hashCode()).padStart(8, '0')
        return if (prefix.isEmpty()) digest else "$prefix$digest"
    }

    /** Record a state TRANSITION (not a progress tick) on the key's timeline. */
    private fun trace(key: String, state: TransferState) {
        val name = state::class.simpleName ?: return
        val dq = timeline.getOrPut(key) { ArrayDeque() }
        synchronized(dq) {
            if (dq.lastOrNull() == name) return // same stage, just new bytes
            dq.addLast(name)
            while (dq.size > TIMELINE_MAX) dq.removeFirst()
        }
    }

    init {
        // The byte stream is collected exactly ONCE, here — never per bubble.
        scope.launch {
            repo.progress.collect { (opId, done, total) ->
                if (total <= 0L) return@collect
                // Resolution order matters. A download registers its opId in
                // [opToKey] when the session starts, so it always wins. Otherwise
                // the opId IS the upload's message id (album items report as
                // `"$msgId#N"`, each its own transfer) and the key is derivable —
                // but only if that upload actually exists, so a stray opId can
                // never conjure a phantom bubble. This second branch is what makes
                // progress immune to the ordering race between the first byte from
                // Rust and the UI registering the stage: an album's five items used
                // to fold onto ONE key here and show a spinner with no %.
                val key = opToKey[opId]
                    ?: TransferKey.upload(opId).value.takeIf { live.containsKey(it) }
                    ?: return@collect
                onBytes(key, done, total)
            }
        }
        // The UI clock. Coalescing here — rather than in Compose — is what keeps a
        // 300-chunk upload from trying to recompose the list a thousand times a
        // second. ~12 fps is smooth to the eye and cheap.
        scope.launch {
            while (isActive) {
                delay(UI_TICK_MS)
                if (dirty.getAndSet(false)) publish()
            }
        }
    }

    private fun publish() {
        _snapshots.value = HashMap(live)
        // Push each subscribed key its own value. StateFlow dedups by structural
        // equality (TransferSnapshot is an @Immutable data class), so an unchanged
        // key emits NOTHING — only the bubble whose bytes moved recomposes. The
        // loop itself is a handful of cheap map lookups per tick, off the UI thread.
        if (keyFlows.isNotEmpty()) {
            for ((k, f) in keyFlows) f.value = live[k]
        }
        if (groupMembers.isNotEmpty()) {
            _groups.value = groupMembers.keys.associateWith { computeGroup(it) }
        }
    }

    /** Write a state. [immediate] bypasses the throttle for terminal transitions. */
    private fun put(key: String, snap: TransferSnapshot, immediate: Boolean = false) {
        live[key] = snap
        trace(key, snap.state)
        if (immediate) publish() else dirty.set(true)
    }

    private fun put(key: TransferKey, state: TransferState, immediate: Boolean = false) {
        val path = (state as? TransferState.Completed)?.localPath
            ?: live[key.value]?.localPath.takeIf { state is TransferState.Completed }
        // The byte counter is NOT zeroed on stages that come AFTER the upload.
        //
        // Publishing and Replicating have no progress of their own; mapping them
        // to NONE made the ring jump back to "0 B of 50 MB" for several seconds
        // right at the end of an upload. The bytes are already on the server —
        // the message itself is being published. Carry the previous progress.
        // Encrypting too: the finished video's upload continues what went up while it was encoded.
        val carried = when (state) {
            TransferState.Publishing, TransferState.Replicating, TransferState.Encrypting -> live[key.value]?.progress
            else -> null
        }
        put(
            key.value,
            TransferSnapshot(
                key = key,
                state = state,
                progress = state.progressOrNull ?: carried ?: TransferProgress.NONE,
                localPath = path,
            ),
            immediate || state.isTerminal,
        )
    }

    // --- Reads ---

    fun snapshotOf(key: TransferKey): TransferSnapshot =
        live[key.value] ?: TransferSnapshot.idle(key)

    fun stateOf(key: TransferKey): TransferState = snapshotOf(key).state

    // --- Download: resolve + policy ---

    /**
     * Fold what's on disk into an initial state. Never starts anything.
     *
     * ARCHITECTURE: resolve() does NOT call File.exists() and does NOT use
     * senderLocalPath to decide Completed. The Repository is the SOLE owner
     * of disk truth — it answers "is the blob cached?" via [cacheEntry].
     * senderLocalPath is passed to the Repository (which may use it internally
     * to resolve the sender's own file), but the Engine never touches the
     * filesystem directly. This is what makes DeleteCache work identically
     * for incoming AND outgoing: after eviction, cacheEntry returns ABSENT
     * → the Engine sets Idle/CacheEvicted, NOT Completed. The old code
     * checked senderLocalPath BEFORE the eviction marker → outgoing video
     * always showed ▶ after DeleteCache because the camera-roll original
     * was still on disk. */
    fun resolve(key: TransferKey, senderLocalPath: String?) {
        val cur = live[key.value]?.state
        // Never clobber an in-flight or terminal state.
        if (cur != null && cur != TransferState.Idle) return

        // THE Repository is the ONLY source of disk truth (no File.exists() in the
        // engine or the UI). It answers "where are the bytes": the sender's own
        // original wins, then the cache, then streaming, then a remote fetch. The
        // resolved availability is folded straight into the snapshot.
        when (val avail = repo.mediaAvailability(key.ref, senderLocalPath)) {
            is FileSource.Original -> put(key, TransferState.Completed(avail.path))
            is FileSource.Cache -> put(key, TransferState.Completed(avail.path))
            // Streaming / Remote / Missing: nothing on disk. A persistent eviction
            // marker (user deleted this before) → CacheEvicted so consider() won't
            // auto-download; otherwise Idle. The bubble's `streamable` flag turns a
            // streamable ref into a Ready(▶) — that is message metadata, not disk.
            else -> {
                if (repo.isCacheEvicted(key.ref)) put(key, TransferState.CacheEvicted)
                else if (cur == null) put(key, TransferState.Idle)
            }
        }
    }

    /**
     * THE single entry point for auto-download. Nothing else — and no bubble,
     * ever — may start a prefetch.
     *
     * Video is NO LONGER excluded — the user's auto-download policy decides.
     * If the user has "download videos without limit", the policy returns true
     * and the video is fetched. The previous hardcode skip ("video streams on
     * demand") overrode the user's settings, which is why videos arrived with
     * a ↓ badge even when auto-download was set to "no limit".
     */
    fun consider(
        key: TransferKey,
        mediaType: String?,
        filename: String?,
        sizeBytes: Long,
        isOutgoing: Boolean,
        senderLocalPath: String?,
        opId: String,
    ) {
        resolve(key, senderLocalPath)
        // Track every considered key so reconsiderVisible() can re-evaluate it
        // when the auto-download policy or network state changes (Block 5).
        // consider() has all guards, so stale entries are no-ops.
        considered[key.value] = ConsideredEntry(mediaType, filename, sizeBytes, isOutgoing, senderLocalPath, opId)
        if (isOutgoing) return
        val s = stateOf(key)
        if (s is TransferState.Completed) return
        // Already active (downloading/queued/etc.) — never start a duplicate.
        if (s.isActive) return
        // User paused this download — don't auto-resume on re-trigger.
        if (s is TransferState.Paused) return
        // Session-level cancel (user tapped ✕ on a download this session).
        if (cancelled.contains(key.value)) {
            lastPolicyDecision = "skip: user cancelled"
            logEvent(key.value, TransferEvent.Kind.PolicyDenied, "user cancelled")
            return
        }
        // PERSISTENT eviction: the user deleted this from cache in a PREVIOUS
        // session. The Repository's marker file is the single source of truth —
        // NOT SharedPreferences, NOT in-memory. Survives ViewModel recreation,
        // process death, everything. consider() checks it here and STOPS.
        if (repo.isCacheEvicted(key.ref)) {
            // Set the snapshot to CacheEvicted so the UI shows ↓ (not ▶).
            if (stateOf(key) != TransferState.CacheEvicted) {
                put(key, TransferState.CacheEvicted)
            }
            lastPolicyDecision = "skip: cache evicted by user"
            logEvent(key.value, TransferEvent.Kind.PolicyDenied, "cache evicted")
            return
        }
        // NO video skip — let the policy decide.
        // "Don't keep on servers" media is deleted from storage soon after
        // delivery, so it is fetched at once regardless of the auto-download policy.
        val noStore = DownloadRefs.noStore(key.raw)
        val allow = noStore || policy.allowAutoDownload(mediaType, filename, sizeBytes)
        lastPolicyDecision = if (allow) "auto: $mediaType ${sizeBytes}B" else "manual: $mediaType ${sizeBytes}B"
        logEvent(
            key.value,
            if (allow) TransferEvent.Kind.PolicyAllowed else TransferEvent.Kind.PolicyDenied,
            lastPolicyDecision,
        )
        if (allow) start(key, opId, inOrder = true)
    }

    /**
     * Re-run [consider] for every key that bubbles have presented. Called when
     * the auto-download policy (SharedPreferences) or the network state changes
     * — the initial [consider] fired once per bubble mount and never
     * re-evaluated, so a bubble that mounted while offline (or while a toggle
     * was off) was stuck at ↓ forever. (UI_AUDIT_2 Block 5 / TRANSFER_UI_2.0 §8-A)
     *
     * SAFE: consider() has guards for Completed / isActive / Paused / cancelled
     * / CacheEvicted, so this never starts a duplicate download, never revives
     * a user-stopped or evicted one, and never resets a running transfer's EMA.
     */
    fun reconsiderVisible() {
        for ((keyValue, entry) in considered.toList()) {
            consider(
                key = TransferKey(keyValue),
                mediaType = entry.mediaType,
                filename = entry.filename,
                sizeBytes = entry.sizeBytes,
                isOutgoing = entry.isOutgoing,
                senderLocalPath = entry.senderLocalPath,
                opId = entry.opId,
            )
        }
    }

    /** Clear the considered-key map when the chat changes, so stale media refs
     *  from the previous chat are not re-evaluated. */
    fun clearConsidered() {
        considered.clear()
    }

    // --- The one way to change anything ---

    /**
     * Every UI gesture funnels through here, so Pause / Cancel / Retry mean
     * exactly the same thing for a photo, a video, a document and an album.
     *
     * [opId] correlates the native byte stream; it matters only for a download
     * [TransferAction.Start].
     */
    fun dispatch(key: TransferKey, action: TransferAction, opId: String = key.raw) {
        when (action) {
            TransferAction.Start -> start(key, opId, inOrder = false)
            TransferAction.Pause -> pause(key)
            TransferAction.Cancel -> cancel(key)
            TransferAction.Retry -> retry(key, opId)
            // The generic action path does NOT delete our own copy: it can't tell
            // whether the send has finished, and tearing down an active resume
            // source is the worst outcome. The chat menu uses deleteCacheOf, where
            // the decision is already computed in the background.
            TransferAction.DeleteCache -> deleteCache(key, deleteLocalCopy = false)
        }
    }

    /** Album-wide action. Cancel/Retry/Pause/Resume-album fall out of this for free. */
    fun dispatchGroup(groupId: String, action: TransferAction) {
        logEvent(groupId, TransferEvent.Kind.GroupAction, action.name)
        groupMembers[groupId]?.forEach {
            if (action == TransferAction.Start) start(it, it.raw, inOrder = true) else dispatch(it, action)
        }
    }

    /** Downloads waiting for the album item before them; a direct start lets one go at once. */
    private val waitingTurn = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    private fun previousInGroup(key: TransferKey): TransferKey? {
        val members = groupMembers.values.firstOrNull { key in it } ?: return null
        return members.getOrNull(members.indexOf(key) - 1)
    }

    /** Wait until [prev] is no longer downloading (done, failed, paused or never started), or [go]. */
    private suspend fun awaitTurn(prev: TransferKey, go: kotlinx.coroutines.CompletableDeferred<Unit>) =
        kotlinx.coroutines.coroutineScope {
            val settled = async { _snapshots.first { m -> m[prev.value]?.state?.isActive != true } }
            kotlinx.coroutines.selects.select<Unit> {
                settled.onAwait {}
                go.onAwait {}
            }
            coroutineContext.cancelChildren()
        }

    // Load is bounded GLOBALLY at the chunk level (a shared chunk-slot pool in the
    // Rust pipeline), NOT by a file-count cap here — every file starts at once and
    // its chunks compete fairly for the global slots. See MediaPipeline chunk_slots.
    /** [inOrder]: an album-wide or automatic start — the item waits for the one before it. */
    private fun start(key: TransferKey, opId: String, inOrder: Boolean) {
        if (key.isUpload) return // uploads are driven by the send pipeline, not by taps
        if (!inOrder) waitingTurn.remove(key.value)?.complete(Unit)
        if (stateOf(key) is TransferState.Completed) return
        cancelled.remove(key.value)
        // The user explicitly tapped ↓ — clear the eviction marker so future
        // consider() calls (after a re-download completes and is evicted again)
        // work correctly.
        repo.unmarkCacheEvicted(key.ref)

        // The sender's own ORIGINAL (or an already-decrypted CACHE blob) needs NO
        // network fetch — materialise it instantly. THE fix for "outgoing ↓ after
        // DeleteCache spins then fails with ↻": downloadTracked() would try to
        // re-pull the sender's blob from the server (which the sender cannot fetch
        // back) → Failed. mediaAvailability resolves the original still on disk, so
        // tap-↓ behaves identically for outgoing (instant) and incoming (fetch).
        val senderLocalPath = considered[key.value]?.senderLocalPath
        when (val avail = repo.mediaAvailability(key.ref, senderLocalPath)) {
            is FileSource.Original -> { put(key, TransferState.Completed(avail.path)); return }
            is FileSource.Cache -> { put(key, TransferState.Completed(avail.path)); return }
            else -> Unit
        }

        // Already running (possibly paused) → RE-ATTACH. Never start a second
        // download for the same blob.
        if (sessions.containsKey(key.value)) {
            paused.remove(key.value)
            speed.remove(key.value) // reset the EMA so the resumed rate reads smoothly
            decile.remove(key.value)
            // Completed is TERMINAL — re-attach must never regress it. A race between
            // this start() (guard at the top read Downloading) and the running job's
            // completion (put(Completed)) could re-read Completed here and push it back
            // to Downloading, orphaning a finished item at "100% + ✕" (STATE_REGRESS
            // Completed→Downloading, caller :501). One read of the state closes the gap.
            val state = stateOf(key)
            if (state !is TransferState.Downloading && state !is TransferState.Completed) {
                put(key, TransferState.Downloading())
            }
            return
        }

        // An album downloads in its own order, one item at a time: the first item is
        // ready to view soonest, and one file's parallel chunks already fill the line.
        // Anything else starts at once; load is bounded at the CHUNK level in Rust.
        launchDownload(key, opId, if (inOrder) previousInGroup(key) else null)
    }

    private fun launchDownload(key: TransferKey, opId: String, after: TransferKey? = null) {
        // Resuming a paused download: the ring starts where it stood.
        val from = (live[key.value]?.state as? TransferState.Paused)?.progress ?: TransferProgress.NONE
        paused.remove(key.value)
        put(key, TransferState.Downloading(from))
        logEvent(key.value, TransferEvent.Kind.Started)
        opToKey[opId] = key.value
        val job = scope.launch {
            if (after != null) {
                val go = kotlinx.coroutines.CompletableDeferred<Unit>()
                waitingTurn[key.value] = go
                try { awaitTurn(after, go) } finally { waitingTurn.remove(key.value, go) }
            }
            val path = runCatching { repo.downloadTracked(key.ref, opId) }.getOrNull()
            // Paused or cancelled while it ran: they already wrote the state.
            if (!isActive) return@launch
            opToKey.remove(opId)
            sessions.remove(key.value)
            speed.remove(key.value)
            decile.remove(key.value)
            val wasPaused = paused.remove(key.value)
            val wasCancelled = cancelled.contains(key.value)
            when {
                // The bytes landed. Even if the user paused meanwhile, the file IS
                // here — saying so is the honest thing.
                path != null -> {
                    put(key, TransferState.Completed(path))
                    logEvent(key.value, TransferEvent.Kind.Completed, path)
                }
                wasCancelled -> put(key, TransferState.Cancelled)
                wasPaused -> put(key, TransferState.Idle)
                else -> {
                    put(key, TransferState.Failed())
                    logEvent(key.value, TransferEvent.Kind.Failed)
                }
            }
        }
        sessions[key.value] = Session(key, opId, job)
    }

    /**
     * Pause STOPS the download: the native one is told to stop (it keeps its
     * `.part`), the job ends, the position stays on screen. Resume starts it
     * again and the core carries on from the `.part`.
     *
     * It used to keep the job running and only detach the UI (the core could
     * not be stopped then): the ✕ showed "paused" while the file kept coming
     * in the background and then turned up downloaded by itself.
     */
    private fun pause(key: TransferKey) {
        paused.add(key.value)
        sessions.remove(key.value)?.let { s ->
            if (key.isDownload) repo.cancelDownload(s.opId)
            s.job.cancel()
            opToKey.remove(s.opId)
        }
        speed.remove(key.value)
        decile.remove(key.value)
        put(key, TransferState.Paused(snapshotOf(key).progress), immediate = true)
        logEvent(key.value, TransferEvent.Kind.Paused)
    }

    /** Cancel is terminal and deliberate: the policy must never resurrect it. */
    private fun cancel(key: TransferKey) {
        cancelled.add(key.value)
        paused.remove(key.value)
        speed.remove(key.value)
        decile.remove(key.value)
        sessions.remove(key.value)?.let { s ->
            if (key.isDownload) repo.cancelDownload(s.opId)
            s.job.cancel()
        }
        opToKey.entries.removeIf { it.value == key.value }
        put(key, TransferState.Cancelled, immediate = true)
        logEvent(key.value, TransferEvent.Kind.Cancelled)
    }

    private fun retry(key: TransferKey, opId: String) {
        cancelled.remove(key.value)
        retries.incrementAndGet()
        put(key, TransferState.Retrying(1))
        logEvent(key.value, TransferEvent.Kind.Retried)
        start(key, opId, inOrder = false)
    }

    /**
     * Cache eviction ends in [TransferState.Idle] and nothing else — no policy
     * re-entry, so the file stays gone until the user asks for it again. This is
     * the fix for "I deleted it from the cache and it downloaded itself back".
     */
    /**
     * Remove the cache for several keys at once and return the total bytes freed.
     *
     * [deleteLocalCopy] comes from ABOVE, already computed off-thread: whether
     * the send finished needs a queue query, and this runs on the main thread.
     */
    fun deleteCacheOf(keys: Collection<TransferKey>, deleteLocalCopy: Boolean): Long =
        keys.sumOf { deleteCache(it, deleteLocalCopy) }

    private fun deleteCache(key: TransferKey, deleteLocalCopy: Boolean): Long {
        sessions.remove(key.value)?.job?.cancel()
        paused.remove(key.value)
        speed.remove(key.value)
        decile.remove(key.value)
        val freed = repo.deleteCache(key.ref, deleteLocalCopy)
        // The marker survives screen recreation and process death: it's what
        // keeps auto-download from re-fetching the deleted file on its own.
        repo.markCacheEvicted(key.ref)
        // --- Live own bytes outweigh the marker ---
        //
        // The marker is CACHE policy — it may not declare unavailable something
        // that sits right there and never went away. Unconditional "evicted"
        // put a ↓ on the sender's own photo that snapped back to ▶ the moment it
        // was tapped: the UI promised a download that could never happen.
        //
        // Ask the single owner of disk truth.
        val senderLocalPath = considered[key.value]?.senderLocalPath
        live[key.value] = when (val avail = repo.mediaAvailability(key.ref, senderLocalPath)) {
            is FileSource.Original ->
                TransferSnapshot(key, TransferState.Completed(avail.path), localPath = avail.path)
            is FileSource.Cache ->
                TransferSnapshot(key, TransferState.Completed(avail.path), localPath = avail.path)
            else -> TransferSnapshot(key, TransferState.CacheEvicted, localPath = null)
        }
        publish()  // immediate — no throttle delay
        logEvent(key.value, TransferEvent.Kind.CacheDeleted)
        return freed
    }

    // --- Upload lifecycle (mirrored in from the send pipeline) ---

    /**
     * Bind the transport's opId to this upload so the shared byte stream lands on
     * it. Without this the uploader's `on_progress` callbacks had nowhere to go
     * and each bubble re-derived speed/ETA in Compose — the very thing the engine
     * exists to prevent. Album items report as `"$msgId#N"`, which the collector
     * already normalises.
     */
    private fun bindUploadOp(msgId: String) {
        opToKey[msgId] = TransferKey.upload(msgId).value
    }

    fun onUploadQueued(msgId: String) {
        bindUploadOp(msgId)
        put(TransferKey.upload(msgId), TransferState.Queued)
        logEvent(TransferKey.upload(msgId).value, TransferEvent.Kind.Started, "upload queued")
    }

    /**
     * Restore a send that only the DB knows about.
     *
     * Transfer state lives in memory and dies with the process; the queue row
     * doesn't. After an app restart the upload kept going, but the screen
     * didn't know: the message had NO transfer state at all, and the "own copy
     * on disk → ready" rule rendered the bubble with no glyph — no clock, no ✕,
     * no ring; there was no way to see or cancel the work.
     *
     * Set ONLY when the engine knows nothing about the key: live state (an
     * upload in flight, a failure, waiting for network) beats the restored one,
     * otherwise every list refresh would reset the work back to the queue.
     *
     * The core refines it later — it's the only one that knows what the row is
     * doing: within seconds it reports "uploading" or "waiting for network".
     */
    fun seedQueuedIfUnknown(msgId: String) {
        val key = TransferKey.upload(msgId)
        if (live.containsKey(key.value)) return
        if (cancelled.contains(key.value)) return
        onUploadQueued(msgId)
    }

    fun onUploadPreparing(msgId: String) {
        bindUploadOp(msgId)
        // Publish IMMEDIATELY so the sender's bubble shows the upload ring on its
        // very first frame (single media only — the album bulk overload below stays
        // batched, one recomposition per album). Without this, reloadMessages() draws
        // the bubble a throttled tick before the upload state lands, flashing the
        // local ▶ a moment before the ring appears.
        put(TransferKey.upload(msgId), TransferState.Preparing, immediate = true)
    }
    // Every stage binds, not just the first: an album goes straight to Encrypting
    // without passing through Preparing, and an unbound opId means the transport's
    // bytes land nowhere and the ring spins with no %.
    fun onUploadEncrypting(msgId: String) {
        bindUploadOp(msgId)
        put(TransferKey.upload(msgId), TransferState.Encrypting)
    }
    fun onUploadPublishing(msgId: String) {
        bindUploadOp(msgId)
        put(TransferKey.upload(msgId), TransferState.Publishing)
    }
    fun onUploadReplicating(msgId: String) {
        bindUploadOp(msgId)
        put(TransferKey.upload(msgId), TransferState.Replicating)
    }

    /**
     * The bytes are gone and the message is out.
     *
     * The transfer is now OVER, so its overlay retires immediately and the
     * message's own delivery clock (✓ → ✓✓) owns the bubble from here. There is
     * deliberately no "hold the tick for 1.2 s" timer: a transfer overlay that
     * outlives its transfer is precisely the bug where the tick stayed glued to
     * the bubble until you left the chat and came back. Delivery state is the
     * truth; we get out of its way.
     */
    fun onUploadSent(msgId: String) {
        val key = TransferKey.upload(msgId)
        unbindUploadOp(msgId)
        cancelled.remove(key.value)
        put(key, TransferState.Completed(null))
        logEvent(key.value, TransferEvent.Kind.Completed, "sent")
        // The state STAYS (an album counts its completed members from it) — but
        // the overlay stops drawing it. Handoff, not a timer.
    }

    /**
     * The core says: work is possible, but there is no transport.
     *
     * Neither terminal nor a failure. The last confirmed resume position is
     * KEPT — the send continues from it on its own once connectivity returns,
     * so the ring stays where it is instead of dropping to zero.
     *
     * Cancelled and Completed are not resurrected: ✕ on a wait removes the core
     * queue row, but the waiting report may already have been in flight.
     */
    fun onUploadWaitingNetwork(msgId: String) {
        val key = TransferKey.upload(msgId)
        if (cancelled.contains(key.value)) return
        val cur = live[key.value]?.state
        if (cur is TransferState.Completed || cur is TransferState.Cancelled) return
        if (cur is TransferState.WaitingNetwork) return  // no flapping
        bindUploadOp(msgId)
        val kept = live[key.value]?.progress ?: TransferProgress.NONE
        // Speed and ETA were computed from moving bytes; the bytes didn't stop
        // because it got slow — drop the samples, or after connectivity returns
        // the smoothed speed would drift from a dead value.
        speed.remove(key.value)
        decile.remove(key.value)
        put(key, TransferState.WaitingNetwork(kept), immediate = true)
        logEvent(key.value, TransferEvent.Kind.Paused, "ожидание сети")
    }

    /**
     * Connectivity is back (the core took the row and reported "uploading").
     *
     * Progress is NOT zeroed: the upload resumes from the confirmed position,
     * and the first byte report will arrive as an absolute number.
     */
    fun onUploadResumed(msgId: String) {
        val key = TransferKey.upload(msgId)
        val cur = live[key.value]?.state ?: return
        if (cur !is TransferState.WaitingNetwork) return
        bindUploadOp(msgId)
        put(
            key.value,
            TransferSnapshot(key = key, state = TransferState.Uploading(cur.progress), progress = cur.progress),
            immediate = true,
        )
        logEvent(key.value, TransferEvent.Kind.Resumed, "сеть вернулась")
    }

    fun onUploadFailed(msgId: String, reason: String = "") {
        // Waiting for network beats the caller's pessimism.
        //
        // The app-side sender only learns "succeeded or not", and honestly reports
        // "not" when connectivity is gone. But the core is the only one that knows
        // WHY, and it already said: waiting for transport. Letting a generic "not"
        // overwrite the precise answer would bring back exactly the defect this
        // state exists to fix: ↻ where there is nothing to retry.
        if (live[TransferKey.upload(msgId).value]?.state is TransferState.WaitingNetwork) return
        unbindUploadOp(msgId)
        // Clear from cancelled set so a retried upload's progress bytes
        // are not silently dropped by onBytes (which checks cancelled).
        cancelled.remove(TransferKey.upload(msgId).value)
        put(TransferKey.upload(msgId), TransferState.Failed(reason))
        logEvent(TransferKey.upload(msgId).value, TransferEvent.Kind.Failed, reason)
    }

    /** Byte progress from the uploader — shares the EMA/ETA maths with downloads.
     *  There is exactly one implementation of that arithmetic (rule 6). */
    fun onUploadBytes(msgId: String, done: Long, total: Long) =
        onBytes(TransferKey.upload(msgId).value, done, total)

    /** Bulk-mark an album's items so a 10-photo album costs one recomposition. */
    fun onUploadPreparing(msgIds: Collection<String>) {
        if (msgIds.isEmpty()) return
        msgIds.forEach {
            bindUploadOp(it)
            val k = TransferKey.upload(it)
            live[k.value] = TransferSnapshot(k, TransferState.Preparing)
        }
        publish()
    }

    /** Terminal upload states release the opId binding — no stale entries. */
    private fun unbindUploadOp(msgId: String) {
        opToKey.remove(msgId)
        speed.remove(TransferKey.upload(msgId).value)
        decile.remove(TransferKey.upload(msgId).value)
    }

    // --- Progress: the ONE place speed, ETA and link throughput are computed ---

    private fun onBytes(rawKey: String, done: Long, total: Long) {
        if (paused.contains(rawKey) || cancelled.contains(rawKey)) return
        val cur = live[rawKey]?.state
        if (cur is TransferState.Completed) return
        if (cur is TransferState.CacheEvicted) return  // user deleted cache — ignore stray bytes
        val isUpload = rawKey.startsWith(TransferKey.UP)
        if (!isUpload && !sessions.containsKey(rawKey)) return

        val now = System.currentTimeMillis()
        val prev = speed[rawKey]
        var ema = prev?.third ?: 0.0
        var lastBytes = prev?.first ?: 0L
        var lastT = prev?.second ?: now
        val dt = now - lastT
        if (prev == null) {
            speed[rawKey] = Triple(done, now, 0.0)
        } else if (dt >= SPEED_SAMPLE_MS) {
            val inst = ((done - lastBytes).coerceAtLeast(0L) * 1000.0) / dt
            // A raw instantaneous rate jitters far too much to display.
            ema = if (ema == 0.0) inst else ema * EMA_DECAY + inst * (1 - EMA_DECAY)
            lastBytes = done
            lastT = now
            speed[rawKey] = Triple(lastBytes, lastT, ema)
            observeLinkSpeed(ema)
        }
        val bps = ema.toLong().coerceAtLeast(0L)
        val remaining = (total - done).coerceAtLeast(0L)
        val p = TransferProgress(
            bytes = done, total = total, speedBps = bps,
            etaSeconds = if (bps > 0L) remaining / bps else 0L,
        )
        val key = TransferKey(rawKey)
        put(
            rawKey,
            TransferSnapshot(
                key = key,
                state = if (isUpload) TransferState.Uploading(p) else TransferState.Downloading(p),
                progress = p,
                localPath = live[rawKey]?.localPath,
            ),
        )
    }

    /** Feeds the adaptive-parallelism estimate from real transfers (rule 12). */
    private fun observeLinkSpeed(bps: Double) {
        val b = bps.toLong()
        peakBps.updateAndGet { maxOf(it, b) }
        emaLinkBps.updateAndGet { cur ->
            if (cur == 0L) b else (cur * 0.8 + b * 0.2).toLong()
        }
    }

    /** Streams to run in parallel, chosen from what the link ACTUALLY delivers. */
    fun currentParallelism(): Int =
        TransferTuning.parallelismFor(TransferTuning.mbpsOf(emaLinkBps.get()))

    // --- Groups: an album is a first-class transfer ---

    /** Per-member known byte size (from the media ref / metadata), so the group
     *  aggregate can count a COMPLETED member at its full size and keep a STABLE
     *  denominator (Σ sizes) instead of jumping to the current file's total. */
    private val keySizes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun registerGroup(
        groupId: String,
        members: List<TransferKey>,
        totalBytes: Long = 0L,
        immediate: Boolean = false,
        memberSizes: List<Long> = emptyList(),
    ) {
        // The group key is the messageId — one slot per message, written by BOTH
        // directions: the send stores `up:` members, the post-delivery
        // re-registration stores `dn:`. The write is a plain put, so the second
        // overwrites the first. Ownership (who is sending now) is checked by the
        // caller; a typed "message + direction" key would remove the collision
        // entirely — still owed.
        groupMembers[groupId] = members
        // The denominator is either set anew or REMOVED. Leaving it untouched on
        // zero let the old value survive re-registration — hence "0 B / N MB"
        // where N was left over from the group's previous life.
        if (totalBytes > 0L) groupTotals[groupId] = totalBytes else groupTotals.remove(groupId)
        members.forEachIndexed { i, k ->
            memberSizes.getOrNull(i)?.takeIf { it > 0L }?.let { keySizes[k.value] = it }
        }
        if (immediate) {
            // BLOCK T1: publish THIS group right away (a single album send) so the
            // bubble's first render sees the aggregate overlay. Cheap — recompute ONLY
            // this group and copy the map, never recompute the whole map.
            _groups.value = _groups.value + (groupId to computeGroup(groupId))
        } else {
            // Batch registration (registerAlbumGroups runs on EVERY reloadMessages) —
            // throttle to the next UI tick. Publishing the whole map here, inside a
            // loop over many albums, was an O(N²) recomposition storm that delayed
            // dialog open, flashed overlays on every media row, and starved the
            // per-key upload/download publishes (video ring / play button lagged
            // until the chat was re-opened; the album upload group never settled).
            dirty.set(true)
        }
    }

    /**
     * Remove the group entirely: members, denominator, sizes and the map entry.
     *
     * Without this the group simply had no end: the send registered it at
     * start, marked the members completed — and that was it. `groupTotals`
     * kept the denominator and the entry stayed in the map forever, so the
     * bubble showed a ring with ✕ and "0 B / N MB" after a successful
     * delivery (numerator zeroed, denominator outliving completion).
     *
     * Called AFTER the publication is confirmed, not after the last byte:
     * until confirmation the publication can still fail, and the group is
     * needed for the retry.
     */
    /**
     * An outgoing transfer is PUBLISHED — the end of the work, uniform for all.
     *
     * It exists because "the end" used to exist for only one sender: the set
     * queue marked the transfer completed and removed the group, while a lone
     * document did neither — not out of forgetfulness, but because the CORE can
     * publish it: on a connectivity drop the row goes into the core queue, which
     * finishes the upload and publishes on its own, reporting only "delivered".
     * The app never learned the upload had ended.
     *
     * What the user saw: a full ring with ✕ over an already-sent file, next to
     * the delivery checkmark — the transfer state stuck at its last known value
     * forever.
     *
     * Hiding the ✕ with a bubble condition would be the wrong cure: the state
     * owner would remain, and any other rendering of the same transfer would lie
     * exactly the same way. The end must live in the state, not the picture.
     */
    fun onOutgoingTransferPublished(messageId: String) {
        if (messageId.isBlank()) return
        // The signal also arrives for messages that never had a transfer (plain
        // text, a peer's delivery). Inventing a state for them out of thin air is
        // forbidden: the bubble would get a completed transfer that never existed.
        val hadTransfer = groupMembers.containsKey(messageId) ||
            live.containsKey(TransferKey.upload(messageId).value)
        if (!hadTransfer) return
        // The group's members (set items) end with it: the group is removed below,
        // but their own entries outlive it.
        groupMembers[messageId]?.forEach { key ->
            if (key.isUpload) {
                cancelled.remove(key.value)
                put(key, TransferState.Completed(null))
            }
        }
        onUploadSent(messageId)
        removeGroup(messageId)
    }

    fun removeGroup(groupId: String) {
        val members = groupMembers.remove(groupId)
        groupTotals.remove(groupId)
        members?.forEach { keySizes.remove(it.value) }
        _groups.value = _groups.value - groupId
    }

    private fun computeGroup(groupId: String): TransferGroup {
        val members = groupMembers[groupId] ?: return TransferGroup.empty(groupId)
        if (members.isEmpty()) return TransferGroup.empty(groupId)
        val all = members.map { snapshotOf(it).state }
        val done = all.count { it is TransferState.Completed }

        val state: TransferState = when {
            done == all.size -> TransferState.Completed(null)
            all.any { it is TransferState.Failed } ->
                all.first { it is TransferState.Failed }
            all.none { it.isActive } ->
                if (all.any { it is TransferState.Cancelled }) TransferState.Cancelled
                else TransferState.Idle
            // The set waits for the network when no member is moving and at least one
            // waits for transport. Checked ABOVE the sum computation: otherwise the
            // branch below would report "Uploading" with a frozen number, with ✕ next
            // to a ring that goes nowhere.
            all.none { it is TransferState.Uploading || it is TransferState.Downloading } &&
                all.any { it is TransferState.WaitingNetwork } ->
                TransferState.WaitingNetwork(
                    all.filterIsInstance<TransferState.WaitingNetwork>()
                        .fold(TransferProgress.NONE) { acc, w ->
                            TransferProgress(acc.bytes + w.progress.bytes, acc.total + w.progress.total)
                        },
                )
            // The bytes are done and publication is running — that is NOT an upload.
            //
            // Without this branch the group stayed "Uploading" to the very end, and
            // the user saw an upload ring while the message was actually being
            // encrypted and confirmed. For an album this tail takes up to three
            // seconds — reporting it as a zeroed ring is worse than honestly saying
            // "sending".
            all.none { it is TransferState.Uploading || it is TransferState.Downloading } &&
                all.any { it == TransferState.Publishing || it == TransferState.Replicating } ->
                TransferState.Publishing
            else -> {
                // TRUE album aggregate: Σ downloaded / Σ sizes across ALL members.
                // A COMPLETED member counts its FULL size (its progress snapshot has
                // none), an in-flight member counts progress.bytes. The denominator
                // is Σ per-member known sizes (stable) — never the current file's.
                var bytes = 0L; var summed = 0L; var bps = 0L
                for (m in members) {
                    val snap = snapshotOf(m)
                    val known = keySizes[m.value]?.takeIf { it > 0L }
                    val memberSize = known ?: snap.progress.total
                    summed += memberSize
                    // A member that has already handed over its bytes (done or publishing)
                    // counts IN FULL. Otherwise a mixed album — part still uploading, part
                    // publishing — would show an under-count and the ring would creep
                    // backwards.
                    val delivered = snap.state is TransferState.Completed ||
                        snap.state == TransferState.Publishing ||
                        snap.state == TransferState.Replicating
                    bytes += if (delivered) memberSize else snap.progress.bytes
                    bps += snap.progress.speedBps
                }
                val total = groupTotals[groupId]?.takeIf { it > 0L } ?: summed
                val remaining = (total - bytes).coerceAtLeast(0L)
                val p = TransferProgress(
                    bytes = bytes, total = total, speedBps = bps,
                    etaSeconds = if (bps > 0L) remaining / bps else 0L,
                )
                // BLOCK T1/T6: direction comes from the members' KEY namespace
                // (up:… vs dn:…), NOT by guessing from the state. An upload group
                // stays an upload while its items are Encrypting/Publishing/
                // Replicating (no per-item progress yet); the old "not Uploading ⇒
                // Downloading" guess mis-reported an uploading album as a download.
                if (members.first().isUpload) TransferState.Uploading(p)
                else TransferState.Downloading(p)
            }
        }
        return TransferGroup(
            id = groupId,
            items = members,
            state = state,
            progress = state.progressOrNull ?: TransferProgress.NONE,
            completed = done,
        )
    }

    fun groupOf(groupId: String): TransferGroup =
        _groups.value[groupId] ?: computeGroup(groupId)

    // --- Housekeeping + Inspector ---

    /** Drop bookkeeping for messages that no longer exist.
     *  Called on reload to clean up stale upload/download snapshots left
     *  over from a crash or force-kill. Without this, a Cancelled or
     *  Encrypting snapshot for a deleted message would "hang" in the
     *  snapshot map forever, and the UI would show a stale overlay. */
    fun retainOnly(keys: Set<String>) {
        // SINGLETON-SAFE keep-set: the engine is process-scoped, so retainOnly is
        // called with only the CURRENT chat's message ids every time its list ticks.
        // It must NEVER prune a transfer that is still in flight (or paused) in
        // ANOTHER chat — dropping its keyFlow, its opId→key routing (byte-progress
        // events!) or its speed/ETA would freeze that ring the moment you switch
        // chats. Keep those keys regardless of the current chat's set.
        val keep = keys + live.entries
            .filter { it.value.isActive || it.value.state is TransferState.Paused }
            .map { it.key }
        // Remove ONLY a Cancelled/Failed snapshot whose message is truly gone —
        // the crash-recovery cleanup its comment intends. NEVER remove Completed
        // (= "the file is downloaded"): losing it makes a cached bubble fall back
        // to a NeedDownload ↓ badge on the next scroll/reload ("download badge
        // resurrection"). Never remove anything in-flight/paused/ready either,
        // even if the keep-set missed its key (optimistic send not yet in the DB).
        live.entries.removeAll {
            it.key !in keep &&
                (it.value.state is TransferState.Cancelled || it.value.isFailed)
        }
        cancelled.retainAll(keep)
        paused.retainAll(keep)
        speed.keys.retainAll(keep)
        decile.keys.retainAll(keep)
        timeline.keys.retainAll(keep)
        // A flow a bubble still collects is never dropped: the bubble would keep a
        // flow nobody updates and freeze until it is composed again.
        keyFlows.entries.removeIf { it.key !in keep && it.value.subscriptionCount.value == 0 }
        // NOTE: keySizes is NOT pruned here — it is tiny (a few longs), keyed by the
        // huge media-ref, and losing it mid-album makes the aggregate under-count
        // completed members. It is refreshed on every registerGroup anyway.
        // Clean up upload opId bindings for removed keys
        opToKey.entries.removeIf { it.value !in keep }
        publish()
    }

    /** The debug read-out (rule 10). Cheap — safe to poll from a debug screen. */
    fun inspect(): TransferInspectorInfo {
        val active = live.values.filter { it.isActive }
        val cur = active.sumOf { it.progress.speedBps }
        return TransferInspectorInfo(
            codec = codecName,
            envelopeVersion = envelopeVersion,
            blobServer = repo.blobServer,
            uploadQueue = active.count { it.key.isUpload },
            downloadQueue = active.count { it.key.isDownload },
            activeGroups = groupMembers.size,
            parallelism = currentParallelism(),
            measuredMbps = TransferTuning.mbpsOf(emaLinkBps.get()),
            currentSpeedBps = cur,
            averageSpeedBps = emaLinkBps.get(),
            peakSpeedBps = peakBps.get(),
            totalRetries = retries.get(),
            lastPolicyDecision = lastPolicyDecision,
        )
    }

    /**
     * The FULL diagnostic bundle the engine produces about itself (rules 7-9):
     * current counters, every transfer's state timeline, and the event ring
     * buffer. Any UI can render this — it needs no new plumbing to do so.
     */
    fun diagnostics(): TransferDiagnostics = TransferDiagnostics(
        info = inspect(),
        timelines = timeline.mapValues { (_, dq) -> synchronized(dq) { dq.toList() } },
        events = synchronized(events) { events.toList() },
    )

    private companion object {
        const val LOG_TAG = "Transfer"
        /** UI clock. ~12 fps: smooth to the eye, and it stops a 300-chunk transfer
         *  from asking Compose to recompose a thousand times a second. */
        const val UI_TICK_MS = 80L

        /** Don't resample the rate faster than this — the EMA just gets noisy. */
        const val SPEED_SAMPLE_MS = 300L

        /** Higher = smoother but laggier. 0.7 tracks real changes within a second
         *  while killing per-chunk jitter. */
        const val EMA_DECAY = 0.7

        /** Transitions kept per transfer — enough to read the whole story back. */
        const val TIMELINE_MAX = 16

        /** Events kept across all transfers. */
        const val EVENT_LOG_MAX = 100

        /** How long the upload ✓ lingers before the message's own delivery clock
         *  takes the bubble back. Long enough to register, short enough not to
         *  look stuck. */
        const val SENT_OVERLAY_MS = 1200L
    }
}
