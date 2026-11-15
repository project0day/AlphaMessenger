package com.alphamessenger.transfer

import androidx.compose.runtime.Immutable

/**
 * Unified Transfer Engine: the model.
 *
 * ONE state machine for **every** byte that moves, in either direction: photo,
 * video, audio, document, archive — upload or download, single item or album.
 *
 * ## Why this exists
 *
 * Transfer state used to live in three parallel systems — upload keyed by
 * msgId, download keyed by msgId, a newer download keyed by mediaRef — that
 * drifted apart, with bubbles improvising the gaps via local Compose flags.
 * That is where the whole family of bugs came from — video with no
 * percentage, albums with no Cancel, "delete cache → it downloads itself
 * again", Retry meaning something different per bubble, a spinner that
 * outlived its own transfer.
 *
 * ## The contract (non-negotiable)
 *
 * * [TransferEngine] is the **only writer** of transfer state. Nothing else.
 * * The UI is **dumb**: a bubble reads a [TransferSnapshot] and draws it. It
 *   holds no `loading`/`progress`/`speed`/`eta` state, performs no disk checks,
 *   consults no policy, and starts nothing on its own.
 * * Every mutation is a [TransferAction] dispatched to the engine.
 * * Every clock — EMA, ETA, throttling, retry backoff — ticks **inside** the
 *   engine. Compose never computes any of it.
 * * The engine knows nothing about Compose, bubbles, albums-as-UI, or media
 *   types. It knows [TransferItem]s and [TransferGroup]s.
 *
 * ```
 *   Repository (pure data)
 *      ↓
 *   TransferEngine ──── sole writer: sessions, policy, speed, ETA, retries
 *      ↓
 *   StateFlow<Map<TransferKey, TransferSnapshot>>   (immutable, throttled)
 *      ↓
 *   Bubble / Overlay (read-only)
 * ```
 */

/**
 * Stable identity of ONE transfer.
 *
 * Uploads are identified by the message they belong to; downloads by the blob's
 * content id, because the same blob can appear in several messages (a forward, an
 * album) and must never be fetched twice. The prefix keeps the two namespaces
 * from colliding in a single map.
 *
 * A download key is short on purpose: a sender's ref to a big file lists every
 * chunk (megabytes), and a key made of it was built, hashed and compared on
 * every frame a bubble was composed. The ref waits in [DownloadRefs].
 */
@JvmInline
value class TransferKey(val value: String) {
    val isUpload: Boolean get() = value.startsWith(UP)
    val isDownload: Boolean get() = value.startsWith(DOWN)

    /** The msgId (upload) or the blob id (download), without the namespace prefix. */
    val raw: String get() = value.substringAfter(':')

    /** The media ref of a download key (registered when the key was made). */
    val ref: String get() = DownloadRefs.refOf(raw) ?: raw

    companion object {
        const val UP = "up:"
        const val DOWN = "dn:"
        fun upload(msgId: String) = TransferKey("$UP$msgId")
        fun download(mediaRef: String) = TransferKey(DOWN + DownloadRefs.idOf(mediaRef))
    }
}

/** Blob id → the media ref behind it, and what the engine asks of it, read once. */
object DownloadRefs {
    private class Entry(val ref: String, val noStore: Boolean)
    private val refs = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun idOf(mediaRef: String): String {
        val id = com.alphamessenger.data.blobHashOf(mediaRef)
        if (!refs.containsKey(id)) refs.putIfAbsent(id, Entry(mediaRef, mediaRef.contains("\"ns\":true")))
        return id
    }

    fun refOf(id: String): String? = refs[id]?.ref

    /** "Don't keep on servers": such media is fetched at once, whatever the policy. */
    fun noStore(id: String): Boolean = refs[id]?.noStore == true
}

/** Which way the bytes are going. */
enum class TransferDirection { UPLOAD, DOWNLOAD }

/**
 * Everything the UI needs to draw progress — and nothing it has to compute.
 * Speed is EMA-smoothed and the ETA derived from it **inside the engine**; no
 * bubble ever does this arithmetic again.
 */
@Immutable
data class TransferProgress(
    val bytes: Long = 0L,
    val total: Long = 0L,
    /** EMA-smoothed bytes/second. 0 until there are enough samples. */
    val speedBps: Long = 0L,
    /** Seconds remaining at the smoothed speed. 0 when unknown. */
    val etaSeconds: Long = 0L,
) {
    /** 0f..1f. Zero while the total is unknown — check [isDeterminate] first. */
    val fraction: Float
        get() = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f

    val percent: Int get() = (fraction * 100f).toInt()

    /** False → draw an indeterminate spinner, never a frozen 0% ring. */
    val isDeterminate: Boolean get() = total > 0L

    companion object {
        val NONE = TransferProgress()
    }
}

/**
 * THE transfer lifecycle — one automaton for both directions, so Retry, Cancel
 * and the progress overlay behave identically for a photo, a video, a document
 * or an entire album.
 *
 * ```
 *   upload:    Queued → Preparing → Encrypting → Uploading → Publishing → Replicating ─┐
 *   download:  Queued → Locating  → Downloading → Verifying → Decrypting → Assembling ─┤
 *                                                                                      ▼
 *   Idle ◄── DeleteCache ──────────────────────────────────────────────────────► Completed
 *     ▲
 *     ├── Paused ◄── Pause (bytes kept; Resume continues, never restarts)
 *     ├── Retrying(n) ── transient failure, engine backs off and re-enters the flow
 *     ├── Failed(reason) ── terminal, offers ↻
 *     └── Cancelled ── terminal AND deliberate: policy must never revive it
 * ```
 *
 * The fine-grained stages ([Locating], [Decrypting], [Assembling],
 * [Replicating]) exist from day one so the pipeline can report them later
 * without a second automaton being bolted on — see rule 8.
 *
 * [Idle] doubles as "nothing is happening and the file is not here", i.e. the
 * bubble offers ↓. There is deliberately no separate `NeedDownload` state:
 * fewer states, one meaning.
 */
sealed interface TransferState {

    /** Nothing in flight, nothing cached → the bubble offers ↓ Download. */
    data object Idle : TransferState

    /** Accepted by the engine; waiting for its turn or for a resource. NOT the
     *  same thing as [WaitingNetwork] — see that state's note. */
    data object Queued : TransferState

    /**
     * The work could run right now, but there is no transport.
     *
     * A third state, not a shade of the other two — and that matters:
     *
     * * [Queued] — waiting for its turn or a resource; the network may be fine.
     * * [WaitingNetwork] — its turn came, the resource is free, connectivity is gone.
     * * [Failed] — the attempt ended in a REAL error and needs ↻.
     *
     * Without this state, "no relays" surfaced as failure: ↻ instead of ✕, and
     * pressing ↻ brought the same icon back half a second later — there was
     * nothing to retry, we were waiting for the network.
     *
     * Not terminal: [progress] is the last CONFIRMED resume position; work
     * continues from it on its own once connectivity returns. Cancel is available,
     * Retry is not: there is nothing to retry.
     *
     * The source is the core's actual send state. Deriving it in the UI as
     * "failed && no network" is not allowed: then any real error that happened
     * during a connectivity drop would masquerade as waiting.
     */
    data class WaitingNetwork(val progress: TransferProgress = TransferProgress.NONE) : TransferState

    // --- upload stages ---

    /** Local prep: copy from the picker, extract metadata, downscale. No network. */
    data object Preparing : TransferState

    /** Chunking + AEAD. Streamed, so it overlaps with [Uploading]. */
    data object Encrypting : TransferState

    data class Uploading(val progress: TransferProgress = TransferProgress.NONE) : TransferState

    /** Blobs are up; the MLS/GiftWrap message is being published. */
    data object Publishing : TransferState

    /** Published; waiting for the relays to confirm replication. */
    data object Replicating : TransferState

    // --- download stages ---

    /** Resolving which blob server actually holds the chunks. */
    data object Locating : TransferState

    data class Downloading(val progress: TransferProgress = TransferProgress.NONE) : TransferState

    /** All bytes in — checking BLAKE3 / the AEAD tag before the file is trusted. */
    data object Verifying : TransferState

    /** Verified — decrypting the chunks. */
    data object Decrypting : TransferState

    /** Decrypted — stitching the chunks into the final file. */
    data object Assembling : TransferState

    // --- shared ---

    /** User paused. The byte position is kept, so Resume continues, not restarts. */
    data class Paused(val progress: TransferProgress = TransferProgress.NONE) : TransferState

    /** A transient failure is being retried; the backoff lives in the engine. */
    data class Retrying(val attempt: Int) : TransferState

    /**
     * Terminal success. For a download [localPath] is the decrypted file; for an
     * upload it is null (the bytes went out — there is nothing local to open).
     */
    data class Completed(val localPath: String? = null) : TransferState

    /** Terminal failure. The UI shows ↻ — the same affordance in both directions. */
    data class Failed(val reason: String = "") : TransferState

    /** Terminal AND user-initiated. Distinct from [Failed] so we never offer ↻ for
     *  something the user deliberately stopped, and the policy can't revive it. */
    data object Cancelled : TransferState

    /** The user deliberately deleted the file from cache. DISTINCT from [Idle]:
     *  Idle = "never downloaded, policy can auto-download". CacheEvicted =
     *  "was downloaded, user removed it, policy must NOT auto-download". The
     *  user can still tap ↓ to re-download manually. Persisted via the
     *  Repository (marker file on disk) — NOT SharedPreferences. */
    data object CacheEvicted : TransferState

    /** Terminal: every server answered that it no longer holds the file. Not a
     *  network fault — the bubble says the file is gone instead of offering ↻. */
    data class Gone(val unknownServer: Boolean = false) : TransferState
}

// --- Derived helpers — the UI asks these instead of pattern-matching everywhere ---

/** Bytes are moving (or about to): the overlay and its ✕ Cancel must be shown. */
val TransferState.isActive: Boolean
    get() = when (this) {
        is TransferState.Queued, is TransferState.Preparing, is TransferState.Encrypting,
        is TransferState.Uploading, is TransferState.Publishing, is TransferState.Replicating,
        is TransferState.Locating, is TransferState.Downloading, is TransferState.Verifying,
        is TransferState.Decrypting, is TransferState.Assembling, is TransferState.Retrying,
        // Waiting for the network occupies the bubble like active work: ✕ must be
        // there, the file isn't ready, and no "start over" is offered.
        is TransferState.WaitingNetwork,
        -> true
        else -> false
    }

/** Nothing more happens without user input. */
val TransferState.isTerminal: Boolean
    get() = this is TransferState.Completed || this is TransferState.Failed ||
        this is TransferState.Cancelled || this is TransferState.CacheEvicted || this is TransferState.Gone

/** The file is on disk and openable. */
val TransferState.isReady: Boolean
    get() = this is TransferState.Completed && localPath != null

/** Progress if this state carries any, else null → draw an indeterminate ring. */
val TransferState.progressOrNull: TransferProgress?
    get() = when (this) {
        is TransferState.Uploading -> progress
        is TransferState.Downloading -> progress
        is TransferState.Paused -> progress
        // The last confirmed position survives a connectivity loss — the resume
        // continues from it. Zeroing it would falsely claim that the transfer
        // starts over.
        is TransferState.WaitingNetwork -> progress
        else -> null
    }

// No TransferGlyph / TransferState.glyph rule here: the bubble's glyphs come
// from FileUiState → TransferOverlay → OverlayControl — the only live path.

/**
 * What a bubble reads. The ONLY thing it reads.
 *
 * Immutable and self-contained: everything needed to render is precomputed by
 * the engine, so Compose never derives, never remembers, never times anything.
 * Being `@Immutable` also lets Compose skip recomposition when an unrelated
 * transfer ticks.
 */
@Immutable
data class TransferSnapshot(
    val key: TransferKey,
    val state: TransferState,
    /** Never null — [TransferProgress.NONE] when the state carries none. */
    val progress: TransferProgress = TransferProgress.NONE,
    /** The decrypted file, once there is one. */
    val localPath: String? = null,
    /** Video: playable by streaming chunks, without a full download first. */
    val isStreaming: Boolean = false,
) {
    // --- What is happening ---
    val isActive: Boolean get() = state.isActive
    val isTerminal: Boolean get() = state.isTerminal
    val isPaused: Boolean get() = state is TransferState.Paused
    val isCancelled: Boolean get() = state is TransferState.Cancelled
    val isFailed: Boolean get() = state is TransferState.Failed
    /** Waiting for transport. NOT [isFailed] — so ↻ is not offered (see canRetry). */
    val isWaitingNetwork: Boolean get() = state is TransferState.WaitingNetwork
    val isFinished: Boolean get() = state is TransferState.Completed
    val isCached: Boolean get() = localPath != null
    val isUpload: Boolean get() = key.isUpload

    /** The bytes are here and the file can be opened right now. */
    val isReady: Boolean get() = isCached && isFinished

    /** THE single "can the user press ▶?" answer. The UI reads THIS — it does
     *  NOT compute `localPath != null` or `File.exists()` or any other logic.
     *  True when the file is cached and the transfer is complete. */
    val isPlayable: Boolean get() = isReady

    // --- What the user may do. The bubble asks; it never reasons. ---
    /** ✕ must be offered at EVERY active stage — no exceptions (rule: Cancel). */
    val canCancel: Boolean get() = isActive
    val canPause: Boolean get() = state is TransferState.Downloading
    val canResume: Boolean get() = isPaused
    val canRetry: Boolean get() = isFailed
    /** Only a download that actually produced a file can be evicted. */
    val canDeleteCache: Boolean get() = isCached && key.isDownload
    /** Tapping opens it: it's cached, or it's a video we can stream. */
    val canOpen: Boolean get() = isReady || (isStreaming && !isActive)
    /** ↓ — nothing here, nothing running: offer to fetch. */
    val canStart: Boolean get() = !isActive && !isCached && !isUpload

    companion object {
        fun idle(key: TransferKey) = TransferSnapshot(key, TransferState.Idle)
    }
}

/**
 * One entry in the engine's ring-buffer event log. Not Android `Log` — an
 * in-memory trail the [TransferInspector] can show, so a bug reported six months
 * from now is diagnosed from what actually happened rather than from a guess.
 */
@Immutable
data class TransferEvent(
    val atMs: Long,
    val key: String,
    val kind: Kind,
    val detail: String = "",
) {
    enum class Kind {
        Started, Progress, Paused, Resumed, Cancelled, Retried,
        Completed, Failed, CacheDeleted, PolicyAllowed, PolicyDenied, GroupAction,
    }
}

/**
 * Everything the Inspector needs, produced by the engine itself (rule 9) so any
 * UI — debug screen, bug report, test — can render it without new plumbing.
 */
@Immutable
data class TransferDiagnostics(
    val info: TransferInspectorInfo,
    /** Per-transfer state timeline: the last transitions, oldest → newest. */
    val timelines: Map<String, List<String>>,
    /** The last ~100 events across all transfers, oldest → newest. */
    val events: List<TransferEvent>,
)

/**
 * An album is a first-class transfer, not a pile of ids.
 *
 * Because the group aggregates its members' bytes, Cancel-album, Retry-album,
 * Pause-album, album progress, album speed and album ETA all fall out for free —
 * no album-specific transfer code exists anywhere.
 */
@Immutable
data class TransferGroup(
    val id: String,
    val items: List<TransferKey>,
    val state: TransferState,
    val progress: TransferProgress = TransferProgress.NONE,
    /** How many members have landed — the "3 / 10" the album header shows. */
    val completed: Int = 0,
) {
    val isActive: Boolean get() = state.isActive
    val size: Int get() = items.size

    companion object {
        fun empty(id: String) = TransferGroup(id, emptyList(), TransferState.Idle)
    }
}

/**
 * Every mutation the UI may request. The engine decides what each one means for
 * the current state, so behaviour cannot drift between media types: a bubble
 * never calls `download()` / `cancel()` / `evict()` directly.
 */
enum class TransferAction {
    /** Begin, or resume from the kept byte position. */
    Start,
    /** Keep the bytes, stop consuming network. Resume continues where it stopped. */
    Pause,
    /** Stop and discard. Terminal — no auto-restart, no policy re-trigger. */
    Cancel,
    /** After a failure — identical behaviour for upload and download. */
    Retry,
    /**
     * Drop the cached file. Ends in [TransferState.Idle] and nothing else — in
     * particular it does NOT re-trigger auto-download (no "deleted from cache,
     * but it came back").
     */
    DeleteCache,
}

/**
 * The bubble's entire window onto the engine.
 *
 * One parameter per bubble, and it is read-only plus two verbs. A bubble cannot
 * reach the policy, the repository, the filesystem or a session through this —
 * which is the point: there is no way for it to invent its own state.
 *
 * @Stable: a single long-lived holder built once by the ViewModel — its fields
 * (StateFlows + verb lambdas) never change identity. Marking it stable lets the
 * bubbles that take it be `skippable`.
 */
@androidx.compose.runtime.Stable
class TransferUi(
    /** Keyed by [TransferKey.value]. The only state a bubble may read. */
    val snapshots: kotlinx.coroutines.flow.StateFlow<Map<String, TransferSnapshot>>,
    /** Album aggregates, keyed by the album's head message id. */
    val groups: kotlinx.coroutines.flow.StateFlow<Map<String, TransferGroup>>,
    /**
 * A per-key window: a bubble subscribes to ONLY its own key,
     * so an unrelated transfer's tick never recomposes it. Prefer this over
     * collecting the whole [snapshots] map in a scrolling list. Nullable value
     * mirrors `snapshots.value[key]` exactly (null when absent).
     */
    val flowFor: (key: TransferKey) -> kotlinx.coroutines.flow.StateFlow<TransferSnapshot?>,
    /** Called when an attachment appears. The ENGINE decides whether to prefetch. */
    val consider: (
        key: TransferKey, mediaType: String?, filename: String?, sizeBytes: Long,
        isOutgoing: Boolean, senderLocalPath: String?, opId: String,
    ) -> Unit,
    /** The only way a bubble changes anything. */
    val dispatch: (key: TransferKey, action: TransferAction, opId: String) -> Unit,
    /** Album-wide Cancel / Retry / Pause — never a loop over messages in the UI. */
    val dispatchGroup: (groupId: String, action: TransferAction) -> Unit,
)

/** Look up a DOWNLOAD snapshot by media ref in an already-collected map. */
fun Map<String, TransferSnapshot>.downloadOf(mediaRef: String?): TransferSnapshot? =
    mediaRef?.let { this[TransferKey.download(it).value] }

/** Look up an UPLOAD snapshot by message id in an already-collected map. */
fun Map<String, TransferSnapshot>.uploadOf(msgId: String): TransferSnapshot? =
    this[TransferKey.upload(msgId).value]

/**
 * What the Repository knows about a blob on disk — pure data, no policy.
 * The Repository is the only thing that touches the filesystem.
 */
data class CacheEntry(
    val exists: Boolean = false,
    /** Full decrypted file path when [exists]; null otherwise. */
    val localPath: String? = null,
    /** BLAKE3-verified at download time (the pipeline verifies before caching). */
    val verified: Boolean = false,
    val size: Long = 0L,
    /** A poster/thumbnail is available — it survives cache eviction, so an evicted
     *  photo still shows its preview behind the ↓ button. */
    val thumbExists: Boolean = false,
    /** Streaming manifest present → video plays without a full download. */
    val streamReady: Boolean = false,
    val lastAccessMs: Long = 0L,
) {
    companion object {
        val ABSENT = CacheEntry()
    }
}

/**
 * Transfer Inspector: the debug read-out.
 *
 * Not for users. This is the thing that turns "a video failed for someone, six
 * months from now" into a thirty-second diagnosis: it shows which codec and
 * envelope version were used, which blob server answered, what the policy
 * decided and why, what the measured link speed was, and how the queues looked.
 */
@Immutable
data class TransferInspectorInfo(
    val codec: String,
    val envelopeVersion: Int,
    val blobServer: String,
    val uploadQueue: Int,
    val downloadQueue: Int,
    val activeGroups: Int,
    val parallelism: Int,
    /** Measured link throughput — what [TransferTuning] actually adapts on. */
    val measuredMbps: Double,
    val currentSpeedBps: Long,
    val averageSpeedBps: Long,
    val peakSpeedBps: Long,
    val totalRetries: Int,
    val lastPolicyDecision: String,
)

/**
 * Adaptive parallelism, by MEASURED throughput.
 *
 * Deliberately not keyed on the network *name*: a congested Wi-Fi is slower than
 * good LTE, and "Wi-Fi ⇒ 6 streams" is simply wrong on a hotel connection. The
 * engine measures what the link actually delivers and adapts to that.
 */
object TransferTuning {
    fun parallelismFor(mbps: Double): Int = when {
        mbps >= 120.0 -> 8
        mbps >= 60.0 -> 6
        mbps >= 25.0 -> 4
        mbps >= 8.0 -> 2
        mbps > 0.0 -> 1
        else -> 4 // nothing measured yet — a sane middle, not a guess at the SSID
    }

    fun mbpsOf(bytesPerSecond: Long): Double = bytesPerSecond * 8.0 / 1_000_000.0
}
