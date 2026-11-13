package com.alphamessenger.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.alphamessenger.transfer.TransferKey
import com.alphamessenger.transfer.TransferProgress
import com.alphamessenger.transfer.TransferSnapshot
import com.alphamessenger.transfer.TransferState
import com.alphamessenger.transfer.TransferUi
import com.alphamessenger.ui.chat.MessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * rememberMediaState — THE ONE producer. The only thing a media bubble calls.
 *
 * Folds the engine's transfer status + the message's content into ONE [MediaState]
 * in ONE place. A bubble does `when (state.stage)` and draws — zero derivation.
 *
 * Per-key subscription: a bubble watches ONLY its own upload+download keys, so a
 * tick on transfer A never recomposes bubble B (scroll stays smooth).
 *
 * Streaming is NOT here: this producer only sets [MediaState.streamable] /
 * [MediaState.playable]. The player streams from its own source and NEVER changes
 * a MediaState — opening a video can no longer look like "downloading again".
 */
@Composable
fun rememberMediaState(
    message: MessageUi,
    mediaActions: TransferUi?,
    // Album tiles upload under `up:<head>#<index>`, not `up:<id>` — pass it here.
    uploadKeyOverride: TransferKey? = null,
): MediaState {
    val kind = remember(message.id, message.mediaType, message.mediaFilename, message.albumItems) {
        MediaKind.from(message)
    }
    return if (kind == MediaKind.ALBUM) {
        rememberAlbumState(message, mediaActions, kind)
    } else {
        rememberSingleState(message, mediaActions, kind, uploadKeyOverride)
    }
}

// --- Single media (photo / video / audio / voice / file) ---

@Composable
private fun rememberSingleState(
    message: MessageUi,
    mediaActions: TransferUi?,
    kind: MediaKind,
    uploadKeyOverride: TransferKey? = null,
): MediaState {
    // Subscribe unconditionally (Compose rule), then drop the upload for voice
    // notes — their send is quick + silent (same as the old includeUpload=false).
    val __t1 = System.nanoTime()
    val uploadSnap = subscribe(mediaActions, uploadKeyOverride ?: TransferKey.upload(message.id))
    __rec("subUpload", __t1)
    val upload = if (kind == MediaKind.VOICE) null else uploadSnap
    val __t2 = System.nanoTime()
    val download = subscribe(mediaActions, message.mediaRefJson?.let { TransferKey.download(it) })
    __rec("subDownload", __t2)

    // The prefetch policy side effect (was scattered in every bubble): tell the
    // engine an attachment appeared; the engine owns the decision + the session.
    LaunchedEffect(message.mediaRefJson) {
        val ref = message.mediaRefJson ?: return@LaunchedEffect
        // --- Live own copy — nothing to ask ---
        //
        // One cheap `stat` and an own attachment's work ends here: without this
        // exit, scrolling falls into `mediaAvailability` — on the main thread,
        // several full ref/JSON parses and disk hits per list-row mount. The FILE
        // is tested, not the message direction: once cleanup removes the local
        // copy, the condition stops holding and one's own old video honestly
        // re-downloads from storage.
        val localAlive = message.localMediaPath
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.io.File(it).let { f -> f.exists() && f.length() > 0L } }.getOrDefault(false) }
            ?: false
        if (localAlive) return@LaunchedEffect
        mediaActions?.consider?.invoke(
            TransferKey.download(ref), message.mediaType, message.mediaFilename,
            message.mediaSizeBytes ?: 0L, message.isOutgoing, message.localMediaPath, message.id,
        )
    }

    val streamable = remember(message.mediaRefJson) {
        val __t = System.nanoTime(); val r = parseStreaming(message.mediaRefJson); __rec("parseStreaming", __t); r
    }
    val __t3 = System.nanoTime()
    val poster = rememberPosterBitmap(message)
    __rec("posterLookup", __t3)
    val durationMs = message.mediaDurationMs?.toInt() ?: 0
    val sizeBytes = message.mediaSizeBytes ?: 0L
    // The sender's own original on disk — always openable/playable, so an outgoing
    // bubble is never blank after its upload settles (no reload needed).
    val ownLocalPath = remember(message.localMediaPath, message.isOutgoing) { liveLocalPath(message) }

    return remember(kind, upload, download, streamable, poster, durationMs, sizeBytes, ownLocalPath) {
        val __t = System.nanoTime()
        val r = resolveSingle(kind, upload, download, streamable, poster, durationMs, sizeBytes, ownLocalPath)
        __rec("resolveSingle", __t)
        r
    }
}

/**
 * Our own copy of the file, if it is here. Outgoing: trusted as is (the sender
 * holds the original). Otherwise only when the file really exists — a channel
 * shows the admin's own posts as the channel's (not "outgoing"), and their
 * sticker or photo must not wait for a download that the live local copy
 * rightly never starts.
 */
private fun liveLocalPath(message: MessageUi): String? {
    val p = message.localMediaPath?.takeIf { it.isNotBlank() } ?: return null
    if (message.isOutgoing) return p
    return p.takeIf { runCatching { java.io.File(it).let { f -> f.exists() && f.length() > 0L } }.getOrDefault(false) }
}

/** TEMP micro-profiler bridge into the chat's PhaseProbe (module-internal). */
private fun __rec(tag: String, startNs: Long) {
    if (com.alphamessenger.ui.chat.WAVE_DIAG)
        com.alphamessenger.ui.chat.PhaseProbe.rec("sub2/$tag", System.nanoTime() - startNs)
}

/**
 * The single pure fold: (upload, download, own file, streaming) → one [MediaState].
 * Fixed precedence — every conflict resolved HERE, once:
 *   own upload publishing → Uploaded      (bytes are up, ring gone)
 *   own upload waits net  → WaitingNetwork (✕, threshold held, ↻ NOT offered)
 *   own upload in flight  → Uploading
 *   own upload failed     → Failed
 *   download paused       → Paused
 *   download in flight    → Downloading
 *   download failed       → Failed
 *   bytes here (cache/own)→ Ready
 *   streamable video      → Ready                       (plays via chunks, no full DL)
 *   sender's own original → Ready                       (never blank after send)
 *   otherwise             → NeedDownload / Idle
 *
 * The center button's verb is NOT chosen here: there is exactly one, and it
 * lives in OverlayControl.
 */
private fun resolveSingle(
    kind: MediaKind,
    upload: TransferSnapshot?,
    download: TransferSnapshot?,
    streamable: Boolean,
    poster: Bitmap?,
    durationMs: Int,
    sizeBytes: Long,
    ownLocalPath: String?,
): MediaState {
    val playable = kind.isPlayable

    fun state(
        stage: TransferStage,
        progress: TransferProgress? = null,
        localPath: String? = null,
    ) = MediaState(
        kind = kind, stage = stage,
        progress = progress?.takeIf { it.isDeterminate }?.fraction,
        bytes = progress?.bytes ?: 0L, total = progress?.total ?: 0L,
        localPath = localPath, playable = playable, streamable = streamable,
        poster = poster, durationMs = durationMs, sizeBytes = sizeBytes,
        albumItems = null, albumDone = 0, albumTotal = 0,
    )

    // 1. Our own UPLOAD governs first.
    if (upload != null) {
        // Publishing is ALREADY not an upload. The bytes are on the server; what
        // remains is the message being encrypted and confirmed — showing a ring
        // over that is dishonest: the filled ring was seen returning to zero and
        // hanging for seconds, as if the send had restarted. No ring here — the
        // send finishes in the background; a failure returns ↻ via the line below.
        val publishing = upload.state == TransferState.Publishing ||
            upload.state == TransferState.Replicating
        if (publishing) return state(TransferStage.Uploaded, upload.progress)
        // Waiting for the network is checked BEFORE the generic "in flight": it
        // counts as isActive (the bubble is busy, ✕ must be there), but the ring
        // stands at the confirmed threshold — calling it an upload would again hide
        // the cause. The path to our own file SURVIVES the upload: the bytes sit on
        // the sender's disk from the start, so ▶ stays playable the whole time
        // instead of lighting up one tile at a time.
        if (upload.isWaitingNetwork)
            return state(TransferStage.WaitingNetwork, upload.progress, ownLocalPath)
        if (upload.isActive) return state(TransferStage.Uploading, upload.progress, ownLocalPath)
        if (upload.isFailed) return state(TransferStage.Failed)
        if (upload.state is TransferState.Cancelled) return state(TransferStage.Canceled)
        // Completed upload falls through — the sender still holds the bytes below.
    }
    // 2. Download in flight / settled.
    if (download != null) {
        if (download.isPaused) return state(TransferStage.Paused, download.progress)
        if (download.isActive) return state(TransferStage.Downloading, download.progress)
        if (download.isFailed) return state(TransferStage.Failed)
        if (download.isReady) {
            return state(TransferStage.Ready, localPath = download.localPath)
        }
        // Cache cleared — reported by the single owner of disk truth, which on
        // deletion re-asked where the bytes lie. If our own file were in place the
        // state would stay Ready; since this arrived, nothing is local and ↓ is
        // honest. The mark must not cover a live original: the CACHE policy has no
        // right to declare unavailable what still lies next to us.
        if (download.state is TransferState.CacheEvicted) {
            return state(TransferStage.NeedDownload)
        }
    }
    // 3. The sender's own original is always Ready (openable/playable) — no blank.
    if (ownLocalPath != null) {
        return state(TransferStage.Ready, localPath = ownLocalPath)
    }
    // 4. Nothing here yet. ONE model for video too: it downloads (with a real
    //    progress badge) and then plays the LOCAL file — no separate streaming
    //    fetch competing with the download (that duplicate caused the jumping
    //    "35/350 then 178/350" progress and the two-player flicker). Voice
    //    auto-downloads silently (no ↓); everything else offers ↓.
    if (download == null) return state(TransferStage.Idle)
    return if (kind == MediaKind.VOICE) state(TransferStage.Idle)
    else state(TransferStage.NeedDownload)
}

/**
 * The non-composable twin of [rememberSingleState] for callers that already hold
 * ONE shared [TransferUi.snapshots] map (album tiles) instead of a per-tile flow.
 * Folds (upload, download, own file, streaming) → one [MediaState] with the SAME
 * precedence as [resolveSingle]. This is what lets an album grid subscribe ONCE at
 * the bubble level and hand each tile a ready state — no per-tile collector.
 */
fun mediaStateFromSnapshots(
    message: MessageUi,
    uploadKey: TransferKey,
    snapshots: Map<String, TransferSnapshot>,
    poster: Bitmap? = null,
): MediaState {
    val kind = MediaKind.from(message)
    val upload = if (kind == MediaKind.VOICE) null else snapshots[uploadKey.value]
    val download = message.mediaRefJson?.let { snapshots[TransferKey.download(it).value] }
    val streamable = parseStreaming(message.mediaRefJson)
    val durationMs = message.mediaDurationMs?.toInt() ?: 0
    val sizeBytes = message.mediaSizeBytes ?: 0L
    val ownLocalPath = liveLocalPath(message)
    return resolveSingle(kind, upload, download, streamable, poster, durationMs, sizeBytes, ownLocalPath)
}

// --- Album (ONE aggregate overlay + per-tile content) ---

@Composable
private fun rememberAlbumState(
    message: MessageUi,
    mediaActions: TransferUi?,
    kind: MediaKind,
): MediaState {
    val items = message.albumItems ?: emptyList()
    val group = mediaActions?.groups?.collectAsState()?.value?.get(message.id)
    val hasUpload = group?.items?.any { it.isUpload } == true

    val posters = rememberPosterBitmaps(items)
    val tiles = remember(items, posters) {
        items.mapIndexed { i, item ->
            MediaState.idle(MediaKind.from(item), posters.getOrNull(i)).copy(
                durationMs = item.mediaDurationMs?.toInt() ?: 0,
                sizeBytes = item.mediaSizeBytes ?: 0L,
                streamable = parseStreaming(item.mediaRefJson),
            )
        }
    }

    val (stage, progress) = remember(group, hasUpload) {
        albumStage(group, hasUpload)
    }
    return MediaState(
        kind = kind, stage = stage,
        progress = progress?.takeIf { it.isDeterminate }?.fraction,
        bytes = progress?.bytes ?: 0L, total = progress?.total ?: 0L,
        localPath = null, playable = false, streamable = false, poster = null,
        durationMs = 0, sizeBytes = 0L,
        albumItems = tiles,
        albumDone = group?.completed ?: 0,
        albumTotal = group?.items?.size ?: items.size,
    )
}

private fun albumStage(
    group: com.alphamessenger.transfer.TransferGroup?,
    hasUpload: Boolean,
): Pair<TransferStage, TransferProgress?> {
    if (group == null || group.items.isEmpty()) return TransferStage.Ready to null
    return when (val s = group.state) {
        is TransferState.Uploading -> TransferStage.Uploading to s.progress
        is TransferState.Downloading -> TransferStage.Downloading to s.progress
        is TransferState.Paused -> TransferStage.Paused to s.progress
        is TransferState.WaitingNetwork -> TransferStage.WaitingNetwork to s.progress
        is TransferState.Failed -> TransferStage.Failed to null
        is TransferState.Idle ->
            if (!hasUpload && group.completed < group.items.size)
                TransferStage.NeedDownload to null
            else TransferStage.Ready to null
        else -> TransferStage.Ready to null
    }
}

// --- shared subscription + poster helpers (single-owner; local to this file) ---

@Composable
private fun subscribe(ui: TransferUi?, key: TransferKey?): TransferSnapshot? {
    if (ui == null || key == null) return null
    val flow = remember(key.value) { ui.flowFor(key) }
    return flow.collectAsState().value
}

// Parsing the WHOLE ref JSON (blob_urls, hashes, sizes) just to read one boolean
// cost up to ~5ms per media-row mount on scroll (proven). Cache the result per
// ref (each unique ref parsed ONCE), and skip the JSON parse entirely when the
// cheap substring scan proves "streaming" isn't even present (the common case).
private val streamCache = object : android.util.LruCache<String, Boolean>(512) {}
private fun parseStreaming(refJson: String?): Boolean {
    if (refJson == null) return false
    streamCache.get(refJson)?.let { return it }
    val r = if (!refJson.contains("streaming")) false
    else runCatching { org.json.JSONObject(refJson).optBoolean("streaming", false) }.getOrDefault(false)
    streamCache.put(refJson, r)
    return r
}

private val stPosterCache = object : android.util.LruCache<String, Bitmap>(24) {}

@Composable
private fun rememberPosterBitmap(message: MessageUi): Bitmap? {
    var poster by remember(message.id) { mutableStateOf(message.mediaPosterB64?.let { stPosterCache.get(it) }) }
    LaunchedEffect(message.mediaPosterB64) {
        val b64 = message.mediaPosterB64
        if (poster != null || b64.isNullOrBlank()) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) { stPosterCache.get(b64) ?: decodeB64(b64)?.also { stPosterCache.put(b64, it) } }
        if (bmp != null) poster = bmp
    }
    return poster
}

@Composable
private fun rememberPosterBitmaps(items: List<MessageUi>): List<Bitmap?> {
    var posters by remember(items) { mutableStateOf(items.map { it.mediaPosterB64?.let { b -> stPosterCache.get(b) } }) }
    LaunchedEffect(items) {
        posters = withContext(Dispatchers.IO) {
            items.map { item ->
                item.mediaPosterB64?.let { b64 -> stPosterCache.get(b64) ?: decodeB64(b64)?.also { stPosterCache.put(b64, it) } }
            }
        }
    }
    return posters
}

private fun decodeB64(b64: String): Bitmap? =
    runCatching { val by = Base64.decode(b64, Base64.DEFAULT); com.alphamessenger.util.BoundedBitmap.decode(by) }.getOrNull()
