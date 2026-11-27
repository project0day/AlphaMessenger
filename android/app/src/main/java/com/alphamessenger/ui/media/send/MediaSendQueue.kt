package com.alphamessenger.ui.media.send

import android.content.Context
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.transfer.TransferAction
import com.alphamessenger.transfer.TransferEngine
import com.alphamessenger.transfer.TransferKey
import com.alphamessenger.ui.chat.AlbumItemMetadata
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.media.export.MediaQuality
import com.alphamessenger.ui.media.export.MediaTrace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media send queue — ONE state machine for all attachments.
 *
 * The message is created FIRST, before any processing. It then lives in the
 * DB, and file preparation, upload and publish are its states — not
 * something the "send" button waits for:
 *
 * ```
 *   tap ─► job on disk ─► message in DB ─► bubble on screen
 *                             │
 *                             └─► QUEUED ─► PROCESSING ─► UPLOADING ─► SENT
 * ```
 *
 * --- Guarantees ---
 *
 * 1. **The source never disappears.** The job TAKES our temp copies for
 *    itself at submit time (instant rename). The hourly draft sweep can no
 *    longer reach them: a file's age must not decide whether a message
 *    arrives.
 * 2. **No "message exists, job doesn't" state.** The job is written BEFORE
 *    the message, and a message already created after a crash is found by
 *    its file path: it contains the job id. So a retry never creates a
 *    second message.
 * 3. **A blob is never uploaded twice.** The uploaded-blob ref is written to
 *    the job immediately, so resuming after process death uploads only what
 *    is still missing.
 * 4. **Cancelled work stays cancelled.** Every state write passes through an
 *    in-memory guard set synchronously with the ✕ tap.
 * 5. **A network drop is not a failure.** Preparation runs to the end
 *    locally, upload waits for the network and resumes on its own. The red
 *    "retry" stays for real failures, not for the subway.
 *
 * What the queue does NOT do: it does not run while the app is closed or the
 * DB is locked. Not an omission but a consequence of the model: the DB is
 * encrypted and opens only at login, so a background process has nothing to
 * publish with. Resume happens right after the DB opens.
 */
@Singleton
class MediaSendQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlphaCoreRepository,
    private val transfers: TransferEngine,
    private val network: com.alphamessenger.data.NetworkMonitor,
) {

    private val store = MediaSendStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Transcoding — strictly one at a time for the whole app.
     *
     * A hardware limit, not an architectural one: the phone has one encoder,
     * and two clips in parallel slow both down and heat the device.
     */
    private val encoderSlot = Semaphore(1)

    /**
     * How many sends run at once.
     *
     * Two, not "as many as possible": while the first uploads bytes, the
     * second has time to prepare, so network and encoder work in parallel
     * instead of queueing. More than two is pointless — the same two
     * resources become the bottleneck.
     */
    private val messageSlots = Semaphore(2)

    // No network-wide upload counter here — on purpose. A file-level permit
    // would hold a slot for the whole file while the line idles between
    // chunks. The limit lives where the real request happens: in the core,
    // around ONE chunk upload (GLOBAL_UPLOAD_SLOTS in
    // alpha-media/pipeline.rs). A permit lasts the milliseconds of a
    // request, not the minutes of a file, and is not taken during
    // transcoding, between chunks, on a retry pause, or at publish. One
    // limit per process also means limits do not multiply: four files with
    // four chunks each are four requests in flight, not sixteen.

    /** Job writes during parallel uploads: updates are serialized. */
    private val writeLock = Mutex()

    /** The core's last upload failure per send: tells a weak link from a server that says no. */
    private val lastFailure = ConcurrentHashMap<String, Throwable>()

    /** Automatic re-runs per send, for the pause before the next. */
    private val retries = ConcurrentHashMap<String, Int>()

    /** What is running now: lets ✕ abort the work itself, not just the spinner. */
    private val running = ConcurrentHashMap<String, Job>()


    /**
     * Cancelled ids — in memory and SYNCHRONOUSLY with the ✕ tap.
     *
     * On-disk state alone is not enough: the work may have passed its check
     * and be about to write the next step. Such a write would resurrect the
     * cancelled send — the job file would reappear and the queue would
     * dutifully finish what the user just cancelled. This set is the only
     * guard EVERY job write passes through.
     */
    private val canceled = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Chat whose messages changed. The screen reloads the list on this signal. */
    private val _changed = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 32)
    val changed: SharedFlow<String> = _changed.asSharedFlow()

    /**
     * What went wrong — in words, to show to the user.
     *
     * Deliberately separate from [changed]: reloading the list and saying
     * "upload failed, no network" are different events, and a silent ↻ was
     * exactly what was missing. The bubble stays in place: a failure is not
     * a reason to hide it.
     */
    data class Problem(val chatId: String, val messageId: String, val text: String)

    private val _problems = MutableSharedFlow<Problem>(replay = 0, extraBufferCapacity = 16)
    val problems: SharedFlow<Problem> = _problems.asSharedFlow()

    /**
     * Job state per message — in memory.
     *
     * The UI asks for this on every menu open and action render, i.e. from
     * the main thread. Going to disk and parsing JSON there is exactly how
     * "just read a file" becomes list jank. Disk stays the source of truth
     * and is read at startup; the map is its reflection.
     */
    private val states = ConcurrentHashMap<String, MediaSendState>()

    /** Job state for a message — for the UI: offer ✕ or ↻. */
    fun stateOf(messageId: String): MediaSendState? = states[messageId]

    fun failureOf(messageId: String): MediaSendFailure? = store.find(messageId)?.failure

    init {
        watchNetwork()
    }

    // --- Public entry points ---

    /**
     * Queue a set for sending and return the created message id.
     *
     * The step order is the desync protection: first the INTENT lands on
     * disk (a job without a message), only then is the message itself
     * created. The reverse order would leave, after a crash, a message
     * nobody knows about — an eternal clock bubble.
     */
    suspend fun submit(
        chatId: String,
        drafts: List<MediaDraftItem>,
        caption: String?,
        ttlSecs: Int,
        photoMaxEdge: Int,
        photoQuality: Int,
        alsoTo: List<String>,
        thread: String? = null,
        replyTo: String? = null,
        hd: Boolean = true,
        videoEdge: Int = com.alphamessenger.ui.media.export.MediaQuality.ORIGINAL,
        /** A timed view: [drafts] must be one photo or video. */
        glanceSecs: Int? = null,
    ): String? {
        if (chatId.isBlank() || drafts.isEmpty()) return null
        // The composed set becomes the send: the same job, files and upload ops. What
        // the send does not take leaves the servers.
        var job = composeLock.withLock {
            val (queued, unused) = writeLock.withLock {
                val composed = draftJobOf(chatId)
                val sendId = composed?.sendId ?: java.util.UUID.randomUUID().toString()
                MediaTrace.event(sendId, null, MediaTrace.SEND_TAP, "items" to drafts.size)
                // Keyed on the drafts as composed: claiming may move their files.
                val keys = drafts.map { keyOf(it, photoMaxEdge, photoQuality, hd, videoEdge) }
                val slots = composed?.items.orEmpty()
                val (reused, left) = ComposedSet.take(keys, slots.map { it.slot() })
                val byOp = slots.associateBy { it.uploadOp }
                val items = claimSources(sendId, drafts, photoMaxEdge, photoQuality, keys, reused.map { s -> s?.let { byOp[it.op] } })
                val leftOps = left.map { it.op }.toSet()
                val unused = slots.filter { it.uploadOp in leftOps }
                if (items.isEmpty()) return@withLock null to unused
                val job = MediaSendJob(
                    sendId = sendId,
                    chatId = chatId,
                    thread = thread,
                    replyTo = replyTo,
                    messageId = "",
                    caption = caption,
                    ttlSecs = ttlSecs,
                    glanceSecs = glanceSecs?.takeIf { drafts.size == 1 },
                    photoMaxEdge = photoMaxEdge,
                    photoQuality = photoQuality,
                    hd = hd,
                    videoEdge = videoEdge,
                    alsoTo = alsoTo.filter { it.isNotBlank() && it != chatId }.distinct(),
                    state = MediaSendState.QUEUED,
                    items = items,
                    echoJson = "",
                )
                persist(job)
                job to unused
            }
            unused.forEach { dropComposed(chatId, it) }
            reserved.removeAll(drafts.map { it.id }.toSet())
            // An upload waiting for the network is not going: the send takes the wait over.
            if (!hasNetwork()) queued?.items?.forEach { item -> item.uploadOp?.let { composedUploads[it]?.cancel() } }
            queued
        } ?: return null
        val sendId = job.sendId
        val items = job.items

        job = createMessage(job) ?: run {
            store.delete(job)
            _problems.tryEmit(Problem(chatId, "", context.getString(MediaSendFailure.MESSAGE_FAILED.messageRes)))
            return null
        }
        markInFlight(job)
        // The message is in the DB and the spinner is up: from this second
        // the bubble is on screen. Everything between SEND_TAP and this
        // event is what the user waits for after pressing "send" — the very
        // quantity this pipeline minimizes.
        MediaTrace.event(sendId, null, MediaTrace.BUBBLE_VISIBLE, "items" to items.size)
        pump()
        return job.messageId
    }

    // --- The set being composed ---
    //
    // A set goes up while it is composed, as a DRAFT job of this same queue: one owner,
    // one op per item for the item's whole life, so Send only publishes and a network
    // switch or a restart after Send resumes from the chunks already stored. Drafts a
    // previous process left are dropped at start, their bytes deleted from the servers.

    /** Composer changes of one set, applied in order. */
    private val composeLock = Mutex()

    /** DRAFT jobs this process composed; any other DRAFT on disk is abandoned. */
    private val composedHere = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Draft items handed to Send: no composer change may drop them any more. */
    private val reserved = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** The upload running for a composed item, by upload op: one per set at a time. */
    private val composedUploads = ConcurrentHashMap<String, Job>()

    /** Each composed set's upload lane, by send id. */
    private val composeLanes = ConcurrentHashMap<String, Job>()

    /** The composer's latest order of each set's drafts, by send id: the lane follows it. */
    private val composeOrder = ConcurrentHashMap<String, List<String>>()

    /** The set in [chatId] as it now stands: new items start, edited ones restart, removed ones go. */
    fun compose(chatId: String, drafts: List<MediaDraftItem>) {
        if (chatId.isBlank()) return
        scope.launch {
            composeLock.withLock {
                MediaQuality.load(context)
                val hd = MediaQuality.hd
                val maxEdge = photoEdge(hd)
                val quality = photoQuality(hd)
                val videoEdge = MediaQuality.videoEdge
                val wanted = drafts.filter { (it.isImage || it.isVideo) && !it.isDocument }
                    .associateBy { keyOf(it, maxEdge, quality, hd, videoEdge) }
                val current = draftJobOf(chatId)
                val slots = current?.items.orEmpty()
                val change = ComposedSet.change(slots.map { it.slot() }, wanted.keys, reserved.toSet())
                val keepOps = change.keep.map { it.op }.toSet()
                val keep = slots.filter { it.uploadOp in keepOps }
                slots.filter { it.uploadOp !in keepOps }.forEach { dropComposed(chatId, it) }
                val sendId = current?.sendId ?: java.util.UUID.randomUUID().toString()
                var next = (keep.maxOfOrNull { it.index } ?: -1) + 1
                val added = change.add.map { key ->
                    val draft = wanted.getValue(key)
                    val revision = MediaSendJob.revisionOf(draft, maxEdge, quality)
                    MediaSendItem(
                        index = next,
                        draft = draft,
                        outputPath = File(store.workDir(sendId), "${next++}.r$revision.${if (draft.isVideo) "mp4" else "jpg"}").absolutePath,
                        editRevision = revision,
                        preUploadKey = key,
                        uploadOp = newUploadOp(),
                    )
                }
                val job = writeLock.withLock {
                    // Re-read: an upload may have landed its ref since [current] was taken.
                    val now = current?.let { fresh(it) }
                    val items = now?.items.orEmpty().filter { it.uploadOp in keepOps } + added
                    if (items.isEmpty()) {
                        now?.let { store.delete(it) }
                        return@withLock null
                    }
                    (now ?: MediaSendJob(
                        sendId = sendId, chatId = chatId, messageId = "", caption = null, ttlSecs = 0,
                        photoMaxEdge = maxEdge, photoQuality = quality, hd = hd, videoEdge = videoEdge,
                        alsoTo = emptyList(), state = MediaSendState.DRAFT, items = emptyList(), echoJson = "",
                    )).copy(items = items).also {
                        composedHere.add(sendId)
                        persist(it)
                    }
                } ?: return@withLock
                composeOrder[job.sendId] = drafts.map { it.id }
                runComposedLane(job.sendId)
            }
        }
    }

    /** The set goes to Send: its items are the send's now, whatever the composer reports. */
    fun reserve(drafts: List<MediaDraftItem>) {
        reserved.addAll(drafts.map { it.id })
    }

    /** The set was dropped without sending: its uploads stop and leave the servers. */
    fun discardComposed(drafts: List<MediaDraftItem>) {
        val ids = drafts.map { it.id }.toSet()
        if (ids.isEmpty()) return
        scope.launch {
            composeLock.withLock {
                store.all().filter { it.state == MediaSendState.DRAFT }.forEach { job ->
                    val gone = job.items.filter { it.draft.id in ids && it.draft.id !in reserved }
                    if (gone.isEmpty()) return@forEach
                    gone.forEach { dropComposed(job.chatId, it) }
                    val goneOps = gone.map { it.uploadOp }.toSet()
                    writeLock.withLock {
                        val now = fresh(job)
                        val left = now.items.filter { it.uploadOp !in goneOps }
                        if (left.isEmpty()) store.delete(now) else persist(now.copy(items = left))
                    }
                }
            }
        }
    }

    private fun draftJobOf(chatId: String): MediaSendJob? =
        store.all().firstOrNull { it.state == MediaSendState.DRAFT && it.chatId == chatId && it.sendId in composedHere }

    /**
     * The set's one upload lane: its items go up one at a time, in the set's order, each
     * with every chunk the link carries. The first item is done soonest and the tiles
     * finish in order. A send takes the lane over: it stops before the next item.
     */
    private fun runComposedLane(sendId: String) {
        if (composeLanes[sendId]?.isActive == true) return
        composeLanes[sendId] = scope.launch {
            val skipped = mutableSetOf<String>()
            while (isActive) {
                val job = store.all().firstOrNull { it.sendId == sendId && it.state == MediaSendState.DRAFT } ?: return@launch
                val order = composeOrder[sendId].orEmpty()
                val item = job.items
                    .sortedWith(compareBy({ order.indexOf(it.draft.id).let { p -> if (p < 0) Int.MAX_VALUE else p } }, { it.index }))
                    .firstOrNull { it.uploadedRef == null && it.uploadOp != null && it.uploadOp !in skipped } ?: return@launch
                val op = job.uploadOpOf(item)
                val upload = scope.launch { uploadComposed(job, item, op) }
                composedUploads[op] = upload
                upload.join()
                composedUploads.remove(op, upload)
                if (fresh(job).items.firstOrNull { it.uploadOp == op }?.uploadedRef == null) skipped.add(op)
            }
        }
    }

    private suspend fun uploadComposed(job: MediaSendJob, item: MediaSendItem, op: String) {
        val policy = if (item.isVideo) MediaQuality.videoPolicy(job.videoEdge) else MediaQuality.policy(job.hd)
        // A video that would be re-encoded waits for Send: the encoder is the phone's
        // heaviest work, and the set may still change.
        if (File(item.outputPath).length() <= 0L) {
            if (MediaOutputFactory.reencodes(context, item, job.photoMaxEdge, job.photoQuality, policy)) return
            val produced = MediaOutputFactory.produce(context, item, job.photoMaxEdge, job.photoQuality, job.sendId, policy = policy)
            if (produced == null || produced.length() <= 0L) return
            rememberByOp(job.sendId, op, null)
        }
        var pause = COMPOSED_RETRY_FIRST_MS
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            // Only a draft waits here; a sent job waits for the network by its own, durable state.
            if (!network.isOnline && fresh(job).state != MediaSendState.DRAFT) return
            network.online.first { it }
            val attempt = repository.uploadMedia(job.chatId, op, item.outputPath, item.mediaType)
            attempt.getOrNull()?.let { ref ->
                // Dropped while it went up: nothing will use it, so it leaves the servers.
                if (!rememberByOp(job.sendId, op, mergeMeta(ref, measured(item)))) {
                    repository.discardPreUpload(job.chatId, op, item.outputPath, ref)
                }
                return
            }
            if (attempt.exceptionOrNull() is uniffi.alphamessenger.MessengerException.MediaRefused) return
            // Sent meanwhile: the send retries by its own rules from here.
            if (fresh(job).state != MediaSendState.DRAFT) return
            delay(pause)
            pause = (pause * 2).coerceAtMost(COMPOSED_RETRY_MAX_MS)
        }
    }

    /** Stop a composed item and delete what of it reached the servers, once it has stopped. */
    private suspend fun dropComposed(chatId: String, item: MediaSendItem) {
        val op = item.uploadOp ?: return
        repository.discardPreUpload(chatId, op, item.outputPath, "")
        composedUploads.remove(op)?.let { it.cancel(); it.join() }
        val ref = store.all().firstNotNullOfOrNull { j -> j.items.firstOrNull { it.uploadOp == op }?.uploadedRef }
            ?: item.uploadedRef
        repository.discardPreUpload(chatId, op, item.outputPath, ref.orEmpty())
        File(item.outputPath).delete()
    }

    /** Abandoned drafts of a previous process: their uploads leave the servers, their files the disk. */
    private suspend fun dropAbandonedDrafts() {
        composeLock.withLock {
            store.all().filter { it.state == MediaSendState.DRAFT && it.sendId !in composedHere }.forEach { job ->
                job.items.forEach { dropComposed(job.chatId, it) }
                store.delete(job)
            }
            // Pre-uploads an earlier version kept apart, each with its chat and ref beside the file.
            File(context.cacheDir, "preupload").listFiles()?.forEach { dir ->
                val file = dir.listFiles()?.firstOrNull { it.name.startsWith("0.r") && !it.name.endsWith(".part") }
                if (file != null) {
                    val ref = File(dir, "ref.json").takeIf { it.exists() }?.readText().orEmpty()
                    val chat = File(dir, "chat").takeIf { it.exists() }?.readText().orEmpty()
                    repository.discardPreUpload(chat, dir.name, file.path, ref)
                }
                dir.deleteRecursively()
            }
        }
    }

    /** Do we own work for this message (otherwise cancel/retry goes the legacy path). */
    fun owns(messageId: String): Boolean =
        messageId.isNotBlank() && states[messageId].let { it != null && it != MediaSendState.SENT }

    /** App start: finish whatever survived the previous process life. */
    fun resume() {
        scope.launch {
            runCatching { store.sweep() }
            runCatching { dropAbandonedDrafts() }
            val all = store.all().filter { it.state != MediaSendState.SENT && it.state != MediaSendState.DRAFT }
            // The state map is restored FIRST: what the UI offers on the
            // bubble — cancel, retry or the plain menu — depends on it.
            all.forEach { if (it.messageId.isNotBlank()) states[it.messageId] = it.state }
            if (all.isEmpty()) return@launch

            // FAILED jobs are registered too — not to run, but so the "retry"
            // button has something to work with. Otherwise a restart left
            // the album without a single transfer member, the ↻ send was cut
            // off by the "is this even our upload?" check, and the tap did
            // nothing — silently.
            val failed = all.filter { it.state == MediaSendState.FAILED }
            failed.forEach { job ->
                markInFlight(job)
                transfers.onUploadFailed(job.messageId, job.failure?.let { context.getString(it.messageRes) }.orEmpty())
                job.items.forEach { transfers.onUploadFailed(job.opId(it.index)) }
                android.util.Log.i(
                    TAG, "${job.sendId}: failure restored (${job.failure ?: "no reason recorded"})",
                )
            }

            val left = all.filter {
                it.state == MediaSendState.QUEUED ||
                    it.state == MediaSendState.PROCESSING ||
                    it.state == MediaSendState.UPLOADING
            }
            if (left.isEmpty()) return@launch
            android.util.Log.i(TAG, "resuming ${left.size} unfinished sends")
            for (job in left) {
                // A job without a message: the crash happened between writing
                // the intent and creating the message. Create it now — or find
                // the already-created one by its file path; no duplicate.
                val ready = if (job.messageId.isBlank()) createMessage(job) else job
                if (ready == null) {
                    android.util.Log.w(TAG, "${job.sendId}: could not create the message, job kept")
                    continue
                }
                // The spinner must appear on the bubble at once, not after the first byte.
                markInFlight(ready)
            }
            pump()
        }
    }

    /**
     * User cancel (✕ on the bubble). Terminal: the work is aborted, files
     * are deleted, the message is removed — as if the send never happened.
     */
    fun cancel(messageId: String) {
        val job = store.find(messageId) ?: return
        android.util.Log.i(TAG, "cancel ${job.sendId} (${job.messageId})")
        canceled.add(job.sendId)
        retries.remove(job.sendId)
        lastFailure.remove(job.sendId)
        // The core checks its flag between chunks — otherwise bytes would keep flowing.
        runCatching { repository.cancelMediaUpload(messageId) }
        val work = running.remove(job.sendId)
        work?.cancel()
        transfers.dispatch(TransferKey.upload(messageId), TransferAction.Cancel)
        job.items.forEach { transfers.dispatch(TransferKey.upload(job.opId(it.index)), TransferAction.Cancel) }
        scope.launch {
            // Files are deleted ONLY after the work has actually stopped.
            // Otherwise a transcode that finished in time would drop its
            // output into an already-deleted directory, forever dangling.
            // The core stops each item's upload between chunks; what of it reached the
            // servers leaves them once it stopped: a cancelled send is never used.
            job.items.forEach { runCatching { repository.discardPreUpload(job.chatId, job.uploadOpOf(it), it.outputPath, "") } }
            job.items.forEach { item -> composedUploads.remove(job.uploadOpOf(item))?.cancel() }
            runCatching { work?.join() }
            val landed = fresh(job)
            landed.items.forEach { item ->
                val op = job.uploadOpOf(item)
                transfers.stopFollowing(op)
                runCatching { repository.discardPreUpload(job.chatId, op, item.outputPath, item.uploadedRef.orEmpty()) }
            }
            runCatching { repository.deletePendingMedia(messageId) }
            runCatching { repository.deleteMessageLocal(messageId) }
            // A cancelled send must clean up its transfer group too:
            // otherwise the engine would keep a transfer with no bubble
            // corresponding to it.
            transfers.removeGroup(messageId)
            store.delete(job)
            forget(messageId)
            _changed.tryEmit(job.chatId)
        }
    }

    /** Retry after failure (↻ on the bubble). Finished files and refs are reused. */
    fun retry(messageId: String) {
        val job = store.find(messageId) ?: return
        if (running.containsKey(job.sendId)) {
            // The button was tapped while the work is already running. Silence
            // is not an option: a silent button looks exactly like a broken one.
            _problems.tryEmit(
                Problem(
                    job.chatId, messageId,
                    if (!hasNetwork()) context.getString(R.string.send_waiting_offline)
                    else context.getString(R.string.send_already_running),
                ),
            )
            return
        }
        android.util.Log.i(TAG, "retry ${job.sendId} (${job.messageId})")
        canceled.remove(job.sendId)
        retries.remove(job.sendId)
        lastFailure.remove(job.sendId)
        persist(job.withState(MediaSendState.QUEUED))
        markInFlight(job)
        pump()
    }

    /**
     * The single job-state write. Cancelled work is never written — see
     * [canceled]. Returns false if the job no longer exists: to the worker
     * that means "stop".
     */
    private fun persist(job: MediaSendJob): Boolean {
        if (job.sendId in canceled) return false
        store.save(job)
        // The state map mirrors the disk and is updated ONLY here: one
        // write, one place — nothing can diverge.
        if (job.messageId.isNotBlank()) states[job.messageId] = job.state
        return true
    }

    /** No job left for the message: cancelled or delivered. */
    private fun forget(messageId: String) {
        if (messageId.isNotBlank()) states.remove(messageId)
    }

    // --- Message creation ---

    /**
     * Create a message for the job — or pick up an already created one.
     *
     * The second case is not theoretical: the process may have died between
     * inserting the message and writing its id into the job. The message is
     * identified by its file path — it contains the job id, so correlating
     * needs neither an extra DB field nor core changes.
     */
    private suspend fun createMessage(job: MediaSendJob): MediaSendJob? {
        val echo = job.echoJson.takeIf { it.isNotBlank() } ?: echoManifest(context, job.items)
        existingMessageFor(job)?.let { found ->
            android.util.Log.i(TAG, "${job.sendId}: message $found already exists, no second one")
            markReply(job, found)
            markGlance(job, found)
            return job.copy(messageId = found, echoJson = echo).also { persist(it) }
        }
        val msgId = runCatching {
            repository.persistOutgoingAlbum(job.chatId, echo, job.caption, job.ttl, thread = job.thread)
        }.getOrNull() ?: return null
        markReply(job, msgId)
        markGlance(job, msgId)
        return job.copy(messageId = msgId, echoJson = echo).also { persist(it) }
    }

    /** Before any publish, so the peer gets the quote with the media. */
    private suspend fun markReply(job: MediaSendJob, messageId: String) {
        job.replyTo?.let { repository.setMediaReply(messageId, it) }
    }

    /** Before any publish: the core then sends it as a timed view, never as a plain photo. */
    private suspend fun markGlance(job: MediaSendJob, messageId: String) {
        val secs = job.glanceSecs ?: return
        val kind = if (job.items.firstOrNull()?.isVideo == true) "video" else "image"
        repository.whisperMarkOutgoing(job.chatId, messageId, kind, secs, glance = true)
    }

    /** Find our message by the job id embedded in the file path. */
    private suspend fun existingMessageFor(job: MediaSendJob): String? = runCatching {
        val marker = "/${job.sendId}/"
        (if (job.thread != null) repository.getThread(job.chatId, job.thread, limit = 60u)
            else repository.getMessages(job.chatId, limit = 60u))
            .firstOrNull { it.mediaRef?.contains(marker) == true }
            ?.id
    }.getOrNull()

    // --- Execution ---

    private enum class Outcome { DONE, FAILED, WAIT_NETWORK, STOPPED }

    /**
     * Start work on every send that has something to do.
     *
     * No global lock over the whole path. One mutex around all of `execute`
     * (prep + upload + publish) would let a message block the next from
     * starting ANYTHING; serialization is only needed for the encoder, not
     * for upload and publish.
     *
     * Each RESOURCE is limited separately now:
     *  - [encoderSlot] — transcoding, strictly one;
     *  - [messageSlots] — how many sends run at once;
     *  - network — a shared counter in the core, around a single chunk.
     *
     * The network limit lives in the core because only there the real
     * request boundary is visible; at file level a permit would be held for
     * the whole upload and a big clip would hog the channel. Publish takes
     * no slot — it touches neither encoder nor bandwidth.
     */
    private fun pump() {
        scope.launch {
            // FAILED and CANCELED are deliberately excluded: the former
            // waits for ↻, the latter is terminal.
            val actionable = store.all().filter {
                it.messageId.isNotBlank() && it.sendId !in canceled && (
                    it.state == MediaSendState.QUEUED ||
                        it.state == MediaSendState.PROCESSING ||
                        it.state == MediaSendState.UPLOADING
                    )
            }
            for (job in actionable) {
                // Already running — do not start twice.
                if (running.containsKey(job.sendId)) continue
                val work = scope.launch {
                    // A slot is held for ONE send and released right after
                    // it, not after the whole queue is published.
                    messageSlots.withPermit {
                        // Re-encoding, upload and publish: the CPU must not sleep through it.
                        repository.keepAwake.during { runCatching { execute(job) } }
                            .onFailure { android.util.Log.e(TAG, "job ${job.sendId} failed", it) }
                    }
                }
                running[job.sendId] = work
                work.invokeOnCompletion { running.remove(job.sendId) }
            }
        }
    }

    private suspend fun execute(initial: MediaSendJob): Outcome {
        var job = initial
        android.util.Log.i(TAG, "start ${job.sendId}: ${job.items.size} items, state ${job.state}")

        // --- PROCESSING + UPLOADING: one pipeline per item ---
        // Items are encoded one at a time (encoder slot) and each goes up as soon as
        // it is ready — a re-encoded video already while it is being encoded. So the
        // next item's encode overlaps this one's upload, and the album's tiles finish
        // one after another instead of all waiting for the last encode.
        job = job.withState(MediaSendState.PROCESSING)
        if (!persist(job)) return Outcome.STOPPED
        // Denominators known now; a video still to be re-encoded reports its own as it goes.
        val sizes = job.items.map { runCatching { File(it.outputPath).length() }.getOrDefault(0L) }
        transfers.registerGroup(
            job.messageId,
            job.items.map { TransferKey.upload(job.opId(it.index)) },
            immediate = true,
            memberSizes = sizes,
        )
        MediaTrace.event(job.sendId, null, MediaTrace.UPLOAD_FIRST_BYTE, "items" to sizes.size)
        // Album tiles waiting their turn read "0%", not a spinner that suggests work.
        if (job.items.size > 1) job.items.forEachIndexed { i, item ->
            if (item.uploadedRef == null) transfers.onUploadBytes(job.opId(item.index), 0L, sizes[i].coerceAtLeast(1L))
        }

        val running = job
        val results = try {
            kotlinx.coroutines.coroutineScope {
                // In the order picked: item i is encoded after item i-1 and finishes its
                // upload after it, so the tiles complete left to right.
                val gates = running.items.map { ItemGates() }
                running.items.mapIndexed { i, item ->
                    async { sendItem(running, item, gates[i], gates.getOrNull(i - 1)) }
                }.awaitAll()
            }
        } catch (stop: ItemStop) {
            val reason = stop.reason
            if (reason == null || running.sendId in canceled) return Outcome.STOPPED
            fail(running, reason)
            return Outcome.FAILED
        }

        if (job.sendId in canceled) return Outcome.STOPPED
        val okRefs = results.sortedBy { it.first }.mapNotNull { it.second }
        MediaTrace.event(
            job.sendId, null, MediaTrace.UPLOAD_END,
            "ok" to okRefs.size, "total" to job.items.size,
        )
        // ALL OR NOTHING. An album is one message; publishing it without part
        // of the attachments means silently losing what the user sent.
        //
        // Uploaded blobs are not lost either way: their refs are written to
        // the job, and the next attempt uploads only what is missing.
        if (okRefs.size < job.items.size) {
            if (!hasNetwork()) {
                android.util.Log.w(TAG, "${job.sendId}: uploaded ${okRefs.size} of ${job.items.size}, waiting for the network")
                persist(fresh(job).withState(MediaSendState.UPLOADING))
                return Outcome.WAIT_NETWORK
            }
            val why = lastFailure[job.sendId]?.message.orEmpty()
            if (!refused(job) && young(job)) {
                android.util.Log.w(TAG, "${job.sendId}: uploaded ${okRefs.size} of ${job.items.size}, retrying later: $why")
                retryLater(job)
                return Outcome.WAIT_NETWORK
            }
            android.util.Log.e(TAG, "${job.sendId}: uploaded ${okRefs.size} of ${job.items.size}, giving up: $why")
            fail(job, MediaSendFailure.UPLOAD_FAILED)
            return Outcome.FAILED
        }
        job = job.withState(MediaSendState.UPLOADING)

        // --- Publish ---
        // The bytes are done but the work is not. Next come steps the byte
        // counter does not see: the large ref moves to a separate encrypted
        // manifest (yet ANOTHER upload), the message is MLS-encrypted and
        // published to the relay with confirmation. Without surfacing this,
        // the spinner sat at "25 of 26 MB" with an ✕ and looked stuck —
        // though work was going on.
        //
        // The engine's "publishing" state exists for this; it is set here,
        // and the trace measures how long this tail really takes — instead
        // of "about a minute".
        transfers.onUploadPublishing(job.messageId)
        job.items.forEach { transfers.onUploadPublishing(job.opId(it.index)) }
        MediaTrace.event(job.sendId, null, "PUBLISH_START", "refs" to okRefs.size)

        val refsJson = "[" + okRefs.joinToString(",") + "]"
        var ok = false
        var attempt = 0
        while (!ok && attempt < 5) {
            if (job.sendId in canceled) return Outcome.STOPPED
            // sendAlbum fuses: manifest upload (for a large ref this is yet
            // ANOTHER upload), MLS encryption and relay confirmation.
            // Measured, this tail takes 0.5–3.3 s — the time the bubble
            // spends in "sending" after the last byte.
            ok = repository.sendAlbum(job.chatId, job.messageId, refsJson, job.caption, job.ttl)
            if (!ok) { attempt++; delay(500L * attempt) }
        }
        if (!ok) {
            if (!hasNetwork()) {
                android.util.Log.i(TAG, "${job.sendId}: publish failed with no network, waiting for it")
                return Outcome.WAIT_NETWORK
            }
            if (young(job)) {
                android.util.Log.w(TAG, "${job.sendId}: publish failed, retrying later")
                retryLater(job)
                return Outcome.WAIT_NETWORK
            }
            android.util.Log.e(TAG, "${job.sendId}: publish failed")
            fail(job, MediaSendFailure.PUBLISH_FAILED)
            return Outcome.FAILED
        }
        runCatching { repository.deletePendingMedia(job.messageId) }
        job.items.forEach {
            transfers.stopFollowing(job.uploadOpOf(it))
            transfers.onUploadSent(job.opId(it.index))
        }
        // Publish confirmed — the work is over, and the transfer group must
        // disappear with it. HERE, not after the last byte: until confirmed,
        // publish can still fail and the retry needs the group.
        //
        // Otherwise the group would live forever: members done, numerator
        // zero, denominator still there — a spinner with ✕ and "0 B / N MB"
        // after successful delivery.
        //
        // There is ONE end for all senders — see the primitive's docs.
        transfers.onOutgoingTransferPublished(job.messageId)
        persist(job.withState(MediaSendState.SENT))
        MediaTrace.event(job.sendId, null, MediaTrace.MESSAGE_PUBLISH, "refs" to okRefs.size)
        MediaTrace.finish(job.sendId)
        android.util.Log.i(TAG, "${job.sendId}: delivered")

        // Other Share recipients: same blobs, no second upload.
        fanOut(job, refsJson)

        store.deleteRecord(job)
        // A sent file has one home from now on: the cache, keyed by its blob, shared by
        // every message that carries it. The outbox copy goes once the cache holds it.
        scope.launch {
            var allAdopted = true
            results.forEach { (index, ref) ->
                val item = job.items.firstOrNull { it.index == index } ?: return@forEach
                if (ref != null && repository.adoptSentFile(item.outputPath, ref)) {
                    File(item.outputPath).delete()
                    transfers.onSentFileAdopted(ref)
                } else {
                    allAdopted = false
                }
            }
            if (allAdopted) store.workDir(job.sendId).deleteRecursively()
        }
        forget(job.messageId)
        retries.remove(job.sendId)
        lastFailure.remove(job.sendId)
        // Told only now that the queue let go of the message: the chat's reload then
        // treats it as a published album (its download group, its cache states).
        _changed.tryEmit(job.chatId)
        return Outcome.DONE
    }

    /** Stops the whole send from inside one item: [reason] null = cancelled, not a failure. */
    private class ItemStop(val reason: MediaSendFailure?) : Exception()

    /**
     * One item: prepare (if needed), then upload. Returns its ref, or null when
     * the upload could not finish (no network / storage refused) — the job then
     * waits or fails as a whole. A preparation problem stops the send ([ItemStop]).
     */
    private suspend fun sendItem(job: MediaSendJob, item: MediaSendItem, own: ItemGates, prev: ItemGates?): Pair<Int, String?> =
        try { sendItemInOrder(job, item, own, prev) } finally { own.encoded.complete(Unit); own.uploaded.complete(Unit) }

    /** Per-item order marks: the next item waits on them, never on this item's success. */
    private class ItemGates {
        val encoded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val uploaded = kotlinx.coroutines.CompletableDeferred<Unit>()
    }

    private suspend fun sendItemInOrder(job: MediaSendJob, initial: MediaSendItem, own: ItemGates, prev: ItemGates?): Pair<Int, String?> {
        val opId = job.opId(initial.index)
        val uploadOp = job.uploadOpOf(initial)
        // Went up, or is going up, while the set was composed: that same upload is the
        // send's, under the same op; its ref lands in the job.
        composedUploads[uploadOp]?.let {
            transfers.onUploadEncrypting(opId)
            it.join()
        }
        val item = fresh(job).items.firstOrNull { it.index == initial.index } ?: initial
        // Already-uploaded items are not uploaded again — the double-send protection after a restart.
        // It still finishes in its turn, so the album's tiles complete in order.
        item.uploadedRef?.let {
            own.encoded.complete(Unit)
            prev?.uploaded?.await()
            transfers.onUploadPublishing(opId)
            return item.index to it
        }
        if (File(item.outputPath).length() <= 0L) {
            prev?.encoded?.await()
            prepare(job, item, uploadOp, opId)
        }
        own.encoded.complete(Unit)
        // Its early upload ran while it was encoded; the rest goes after the previous item's.
        prev?.uploaded?.await()
        if (job.sendId in canceled) throw ItemStop(null)
        // Retrying over a dead network only burns the attempt budget: the queue
        // waits for the network itself (WAIT_NETWORK) and connectivity wakes it.
        if (!hasNetwork()) return item.index to null
        // No network permit here: the shared limit lives in the core, around ONE
        // chunk, so files start at once and share the line by chunk.
        transfers.onUploadEncrypting(opId)
        return kotlinx.coroutines.coroutineScope {
            // Duration and poster are measured while the bytes go up: only the finished
            // ref carries them (the poster among the metadata), so the upload never waits.
            val measuring = async(Dispatchers.IO) {
                measured(item).also { MediaTrace.event(job.sendId, item.index, "METADATA") }
            }
            var ref: String? = null
            var attempt = 0
            while (ref == null && attempt < 3 && job.sendId !in canceled && !refused(job)) {
                if (!hasNetwork()) {
                    android.util.Log.w(TAG, "${job.sendId}#${item.index}: no network, retry deferred (attempt ${attempt + 1})")
                    break
                }
                ref = repository.uploadMedia(job.chatId, uploadOp, item.outputPath, item.mediaType)
                    .onFailure { lastFailure[job.sendId] = it }.getOrNull()
                if (ref == null) { attempt++; delay(400L * attempt) }
            }
            if (job.sendId in canceled) throw ItemStop(null)
            // Uploaded, not yet sent: the album goes out as one message once every item is up.
            if (ref != null) transfers.onUploadPublishing(opId) else transfers.onUploadFailed(opId)
            val merged = ref?.let { mergeMeta(it, measuring.await()) }
            // The ref goes to disk IMMEDIATELY: otherwise process death would force
            // the same bytes to be re-uploaded.
            if (merged != null) rememberRef(job.sendId, item.index, merged)
            item.index to merged
        }
    }

    /** Produce the item's file. A re-encoded video starts going up (under [uploadOp]) while it is written. */
    private suspend fun prepare(job: MediaSendJob, item: MediaSendItem, uploadOp: String, opId: String) {
        // Checks BEFORE the work: each says in words why there is no point starting —
        // free space or resend the file are different answers.
        if (!sourceReadable(item)) {
            android.util.Log.e(TAG, "${job.sendId} item ${item.index}: source unavailable")
            throw ItemStop(MediaSendFailure.SOURCE_UNAVAILABLE)
        }
        if (!enoughDiskFor(item)) {
            android.util.Log.e(TAG, "${job.sendId} item ${item.index}: low disk space")
            throw ItemStop(MediaSendFailure.NO_DISK_SPACE)
        }
        val target = File(item.outputPath)
        val produced = kotlinx.coroutines.coroutineScope {
            var early: kotlinx.coroutines.Job? = null
            // Preparation runs under the ENCODER slot, not a global send lock: while
            // this item transcodes, the network stays free for others' bytes.
            // The core's early upload blocks until it is told the encode ended — on every path.
            val file = try { encoderSlot.withPermit {
                MediaOutputFactory.produce(
                    context, item, job.photoMaxEdge, job.photoQuality, job.sendId,
                    // A video at its chosen quality; a photo by SD/HD (its edge and quality ride in the job).
                    policy = if (item.isVideo) com.alphamessenger.ui.media.export.MediaQuality.videoPolicy(job.videoEdge)
                        else com.alphamessenger.ui.media.export.MediaQuality.policy(job.hd),
                    // Told only before a re-encode: its output goes up while it is encoded,
                    // so there is no separate "compressing" phase.
                    onEstimate = { est ->
                        if (hasNetwork()) {
                            transfers.onUploadEncrypting(opId)
                            early = launch(Dispatchers.IO) {
                                repository.preuploadGrowing(uploadOp, "${target.path}.part", target.path, est)
                            }
                        }
                    },
                ) { percent ->
                    if (percent % 20 == 0) android.util.Log.i(TAG, "${job.sendId} item ${item.index}: preparing $percent%")
                }
            } } finally { repository.endPreupload(uploadOp) }
            early?.join()
            file
        }
        if (produced == null) {
            // A cancel during preparation looks like a failure — but work interrupted on request is not one.
            if (job.sendId in canceled) throw ItemStop(null)
            android.util.Log.e(TAG, "${job.sendId} item ${item.index}: no file produced")
            // The source may have vanished mid-work — then "retry" is pointless.
            throw ItemStop(if (!sourceReadable(item)) MediaSendFailure.SOURCE_UNAVAILABLE else MediaSendFailure.ENCODE_FAILED)
        }
        markReady(job.sendId, item.index)
    }

    private suspend fun markReady(sendId: String, index: Int) {
        writeLock.withLock {
            val fresh = store.all().firstOrNull { it.sendId == sendId } ?: return
            persist(fresh.withItem(index) { it.copy(ready = true) })
        }
    }

    /**
     * As [rememberRef] and [markReady], for the item that goes up under [op]: Send may
     * have moved it. False when no job holds the item any more.
     */
    private suspend fun rememberByOp(sendId: String, op: String, ref: String?): Boolean =
        writeLock.withLock {
            val fresh = store.all().firstOrNull { it.sendId == sendId } ?: return@withLock false
            val item = fresh.items.firstOrNull { it.uploadOp == op } ?: return@withLock false
            persist(fresh.withItem(item.index) { it.copy(uploadedRef = ref ?: it.uploadedRef, ready = true) })
        }

    /** Write a ref into the job. Under a lock: uploads run in parallel. */
    private suspend fun rememberRef(sendId: String, index: Int, ref: String) {
        writeLock.withLock {
            val fresh = store.all().firstOrNull { it.sendId == sendId } ?: return
            persist(fresh.withItem(index) { it.copy(uploadedRef = ref, ready = true) })
        }
    }

    /** Fan out to other Share recipients — by refs to already uploaded blobs. */
    private fun fanOut(job: MediaSendJob, refsJson: String) {
        val extras = job.alsoTo.filter { it.isNotBlank() && it != job.chatId }.distinct()
        if (extras.isEmpty()) return
        scope.launch {
            android.util.Log.i(TAG, "fan-out ${job.sendId}: ${extras.size} recipients")
            for (target in extras) {
                val id = runCatching {
                    publishAlbumRefs(target, refsJson, job.echoJson, job.caption, job.ttl)
                }.getOrNull()
                android.util.Log.i(TAG, "fan-out → $target: ${id ?: "failed"}")
            }
        }
    }

    /**
     * Publish ALREADY uploaded album refs to chat [target].
     *
     * The app's only implementation: forwarding and Share go through it too.
     * The file is not read, there is no second upload — the echo is built
     * straight from the refs, so the recipient's bubble is final at once.
     */
    suspend fun publishAlbumRefs(
        target: String,
        refsJson: String,
        echoJson: String,
        caption: String?,
        ttl: UInt,
    ): String? {
        val msgId = runCatching {
            repository.persistOutgoingAlbum(target, echoJson, caption, ttl)
        }.getOrNull() ?: return null
        transfers.onUploadPublishing(msgId)
        _changed.tryEmit(target)
        repository.runDetached {
            var ok = false
            var attempt = 0
            while (!ok && attempt < 5) {
                ok = repository.sendAlbum(target, msgId, refsJson, caption, ttl)
                if (!ok) { attempt++; delay(500L * attempt) }
            }
            if (ok) {
                runCatching { repository.deletePendingMedia(msgId) }
                transfers.onUploadSent(msgId)
            } else {
                transfers.onUploadFailed(msgId)
            }
            _changed.tryEmit(target)
        }
        return msgId
    }

    // --- Network ---

    private fun hasNetwork(): Boolean = network.isOnline

    /**
     * Network is back — continue what was waiting.
     *
     * Without this, "connection dropped" and "broken file" would be treated
     * the same: a red mark and a plea to press ↻. The app can wait for the
     * network by itself.
     */
    private fun watchNetwork() {
        scope.launch {
            var wasOnline = network.isOnline
            network.online.collect { online ->
                if (online && !wasOnline) {
                    android.util.Log.i(TAG, "network back, resuming sends")
                    pump()
                }
                wasOnline = online
            }
        }
    }

    // --- Execution details ---

    /** The job as on disk: uploads write their refs there, past the copy a worker holds. */
    private fun fresh(job: MediaSendJob): MediaSendJob =
        store.all().firstOrNull { it.sendId == job.sendId } ?: job

    /** A refusal no retry can change: rights, a format no server takes. */
    private fun refused(job: MediaSendJob): Boolean =
        lastFailure[job.sendId] is uniffi.alphamessenger.MessengerException.MediaRefused

    /** Sends keep trying for a day, then ask the user. */
    private fun young(job: MediaSendJob): Boolean =
        System.currentTimeMillis() - job.createdAt < RETRY_LIFESPAN_MS

    /**
     * A weak link is not a failure: the send waits as for the network and comes
     * back on its own, with growing pauses, from what is already uploaded.
     */
    private fun retryLater(job: MediaSendJob) {
        val now = fresh(job)
        persist(now.withState(MediaSendState.UPLOADING))
        now.items.forEach { if (it.uploadedRef == null) transfers.onUploadWaitingNetwork(job.opId(it.index)) }
        val n = retries.merge(job.sendId, 1, Int::plus) ?: 1
        val base = (RETRY_FIRST_MS shl (n - 1).coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS)
        // Spread out, so sends that failed together do not all return at the same instant.
        val pause = base + kotlin.random.Random.nextLong(base / 4 + 1)
        MediaTrace.event(job.sendId, null, "RETRY_LATER", "attempt" to n, "pause_ms" to pause)
        scope.launch {
            delay(pause)
            if (job.sendId !in canceled) pump()
        }
    }

    private fun fail(job: MediaSendJob, reason: MediaSendFailure) {
        persist(job.failedWith(reason))
        job.items.forEach { transfers.onUploadFailed(job.opId(it.index)) }
        transfers.onUploadFailed(job.messageId, context.getString(reason.messageRes))
        _changed.tryEmit(job.chatId)
        // The bubble stays in place — the reason goes to the screen in words.
        _problems.tryEmit(Problem(job.chatId, job.messageId, context.getString(reason.messageRes)))
        MediaTrace.event(job.sendId, null, "FAILED", "reason" to reason.name)
    }

    /**
     * Whether the source is USABLE, not merely openable.
     *
     * A must after a restart: permissions on a foreign `content://` live
     * only as long as the process, and the gallery file may be deleted. A
     * stream-open check alone let an empty file through, so a dead source
     * (a half-written draft copy) reached preparation, produced a 0×0 image
     * and failed as "encoder error" — with a retry offered that could never
     * work.
     *
     * The first byte is enough: proving there is something to read does not
     * require reading a hundred megabytes.
     */
    private fun sourceReadable(item: MediaSendItem): Boolean = runCatching {
        context.contentResolver.openInputStream(item.draft.uri)?.use { it.read() >= 0 } ?: false
    }.getOrDefault(false)

    /**
     * Whether there is room for the output.
     *
     * With a 1.5× margin: preparation writes a temp file next to the target,
     * and the export library may keep its own intermediate. Disk running out
     * mid-way through a 300 MB album otherwise looks like an unexplained
     * encoder failure.
     */
    private fun enoughDiskFor(item: MediaSendItem): Boolean = runCatching {
        val need = (item.draft.sizeBytes.coerceAtLeast(0L) * 3) / 2
        if (need <= 0L) return true
        val free = File(item.outputPath).parentFile?.usableSpace ?: return true
        (free > need).also {
            if (!it) android.util.Log.e(TAG, "need $need B, free $free B")
        }
    }.getOrDefault(true)

    /**
     * Show that work is going on: the bubble's spinner appears BEFORE the
     * first byte.
     *
     * NO NUMBERS. There are no finished files yet, hence no weight of what
     * will travel. Substituting the sources' weight is wrong: that is how
     * "0 / 350 MB" turned into "0 / 65 MB" half a minute later — the
     * denominator visibly changed because it first showed one quantity, then
     * another. Until a weight exists the spinner runs without a caption —
     * honest, and nothing to re-explain later.
     */
    private fun markInFlight(job: MediaSendJob) {
        if (job.messageId.isBlank()) return
        // Already-finished items (retry, resume after restart) do have a
        // weight — it is measured. The denominator appears when ALL items
        // have one: a partial sum would be a third number in the same spot.
        val measured = job.items.map { runCatching { File(it.outputPath).length() }.getOrDefault(0L) }
        val allReady = measured.all { it > 0L }
        job.items.forEach {
            // An item's bytes go up under its own op (from the composer on); the bubble reads them as the message's.
            val op = job.uploadOpOf(it)
            if (op != job.opId(it.index)) transfers.followProgress(op, job.opId(it.index))
            transfers.onUploadEncrypting(job.opId(it.index))
        }
        transfers.registerGroup(
            job.messageId,
            job.items.map { TransferKey.upload(job.opId(it.index)) },
            totalBytes = if (allReady) measured.sum() else 0L,
            immediate = true,
            memberSizes = if (allReady) measured else emptyList(),
        )
    }

    /** Measured metadata of the FINISHED file — what the recipient gets. */
    private fun measured(item: MediaSendItem): AlbumItemMetadata? =
        if (item.isVideo) AlbumItemMetadata.extract(item.outputPath, "video")
        else AlbumItemMetadata.extractImage(item.outputPath)

    private fun mergeMeta(ref: String, meta: AlbumItemMetadata?): String {
        if (meta == null) return ref
        return runCatching {
            val o = JSONObject(ref)
            val m = meta.toJson()
            m.keys().forEach { k -> if (!o.has(k)) o.put(k, m.get(k)) }
            o.toString()
        }.getOrDefault(ref)
    }

    /** Manifest for the core queue: finished-file paths and measured metadata. */
    private fun manifestOf(job: MediaSendJob): String =
        JSONArray().apply {
            job.items.forEach { item ->
                put(JSONObject().apply {
                    put("local_path", item.outputPath)
                    put("media_type", item.mediaType)
                    measured(item)?.toJson()?.let { m -> m.keys().forEach { put(it, m.get(it)) } }
                })
            }
        }.toString()

    /**
     * Take ownership of the sources and lay out the output paths.
     *
     * Our temp copies MOVE into the job directory. Not an optimization but
     * correctness: draft copies are swept by age, and an active send would
     * lose its only source merely because an hour passed. Renaming is
     * instant — the file stays on the same partition.
     *
     * Foreign sources (gallery, `content://`) are left alone: they are not
     * ours, and our sweep cannot reach them anyway.
     */
    private fun claimSources(
        sendId: String,
        drafts: List<MediaDraftItem>,
        photoMaxEdge: Int,
        photoQuality: Int,
        keys: List<String>,
        /** Per draft, the composed item it reuses (see [ComposedSet.take]). */
        composed: List<MediaSendItem?>,
    ): List<MediaSendItem> {
        val dir = store.workDir(sendId)
        val cacheRoot = context.cacheDir.absolutePath
        return drafts.mapIndexed { i, draft ->
            // Composed under the same key: its file and upload are this item's.
            val owned = draft.takeIf { it.deleteAfterUse }
                ?.uri?.takeIf { it.scheme == "file" }
                ?.path?.let(::File)
                ?.takeIf { it.absolutePath.startsWith(cacheRoot) && it.exists() }
            val claimed = if (owned == null) draft else {
                val target = File(dir, "src_$i.${owned.extension.ifBlank { "bin" }}")
                val moved = runCatching { owned.renameTo(target) }.getOrDefault(false) ||
                    runCatching { owned.copyTo(target, overwrite = true); owned.delete(); true }
                        .getOrDefault(false)
                if (moved) draft.copy(uri = android.net.Uri.fromFile(target)) else draft
            }
            // Composed under the same key: its file and upload are this item's.
            composed[i]?.let { return@mapIndexed it.copy(index = i, draft = claimed) }
            val revision = MediaSendJob.revisionOf(claimed, photoMaxEdge, photoQuality)
            val ext = if (claimed.isVideo) "mp4" else "jpg"
            MediaSendItem(
                index = i,
                draft = claimed,
                outputPath = File(dir, "$i.r$revision.$ext").absolutePath,
                editRevision = revision,
                preUploadKey = keys[i],
                uploadOp = newUploadOp(),
            )
        }
    }

    /** What shapes an item's file under the given settings: a composed item serves a send only under the same key. */
    private fun keyOf(draft: MediaDraftItem, photoMaxEdge: Int, photoQuality: Int, hd: Boolean, videoEdge: Int): String {
        val revision = MediaSendJob.revisionOf(draft, photoMaxEdge, photoQuality)
        return "${draft.id}|$revision|" + if (draft.isVideo) "v$videoEdge" else "p$hd"
    }

    /** The photo settings a chat's send uses (see [com.alphamessenger.ui.chat.ChatViewModel.sendDraftMedia]). */
    private fun photoEdge(hd: Boolean) = maxOf(DEFAULT_PHOTO_EDGE, MediaQuality.photoEdge(hd))
    private fun photoQuality(hd: Boolean) = maxOf(DEFAULT_PHOTO_QUALITY, MediaQuality.photoQuality(hd))

    private fun newUploadOp() = "up-" + java.util.UUID.randomUUID()

    companion object {
        private const val TAG = "AlphaSendQueue"
        private const val DEFAULT_PHOTO_EDGE = 1600
        private const val DEFAULT_PHOTO_QUALITY = 80
        private const val COMPOSED_RETRY_FIRST_MS = 1_000L
        private const val COMPOSED_RETRY_MAX_MS = 30_000L
        private const val RETRY_FIRST_MS = 5_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val RETRY_LIFESPAN_MS = 24 * 60 * 60 * 1000L

        /**
         * Local-echo manifest — what the bubble is drawn from IMMEDIATELY.
         *
         * `local_path` points to a file that does not exist yet, and that is
         * not an error: the core takes only the name from it, and the bubble
         * picture comes from the poster. When the file appears, the finished
         * attachment lands at the same path — no second DB write needed.
         *
         * Posters are taken in parallel: ten clips would otherwise delay the
         * bubble by the sum of their reads.
         */
        suspend fun echoManifest(context: Context, items: List<MediaSendItem>): String =
            kotlinx.coroutines.coroutineScope {
                val described = items.map { item ->
                    async(Dispatchers.IO) {
                        val poster = MediaSendPreview.poster(context, item.draft)
                        val meta = MediaSendPreview.metadata(context, item.draft, poster)
                        JSONObject().apply {
                            put("local_path", item.outputPath)
                            put("media_type", item.mediaType)
                            meta.toJson().let { m -> m.keys().forEach { put(it, m.get(it)) } }
                        }
                    }
                }.awaitAll()
                JSONArray().apply { described.forEach { put(it) } }.toString()
            }
    }
}
