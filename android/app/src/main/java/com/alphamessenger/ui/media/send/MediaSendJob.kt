package com.alphamessenger.ui.media.send

import android.net.Uri
import androidx.annotation.StringRes
import com.alphamessenger.R
import com.alphamessenger.ui.media.draft.MediaDraftItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * State of ONE media send — from pressing "send" to delivery.
 *
 * ```
 *   QUEUED ──► PROCESSING ──► UPLOADING ──► SENT
 *                  │              │
 *                  └──────┬───────┘
 *                         ▼
 *                  FAILED (↻ retry)   CANCELED (✕, terminal)
 * ```
 *
 * A separate state machine rather than "make the file, then send": while
 * preparation stood BEFORE message creation, the bubble appeared seconds
 * after the press — exactly the transcode duration. The message is created
 * first and lives in the DB, and preparation becomes one of its states.
 * Cancel, retry and process-death survival all follow from the work having
 * a persistent key instead of a screen-scoped variable.
 */
/**
 * Why a send stalled. Each value is something that can be told to the user
 * in words and acted upon — instead of a silent ↻ that could mean "no
 * network", "broken file" or "working, just slow".
 */
enum class MediaSendFailure(@StringRes val messageRes: Int) {
    /** Source vanished: deleted from the gallery, or permissions revoked after a restart. */
    SOURCE_UNAVAILABLE(R.string.send_fail_source_unavailable),

    /** Not enough disk space for the prepared output. */
    NO_DISK_SPACE(R.string.send_fail_no_disk_space),

    /** Encoder failed: unsupported format or device glitch. Wording avoids the
     *  word "video": this branch also ends photo preparation, and "could not
     *  prepare video" about a photo read like an app error, not a prep refusal. */
    ENCODE_FAILED(R.string.send_fail_encode),

    /** Output failed validation — must not be sent. */
    OUTPUT_INVALID(R.string.send_fail_output_invalid),

    /** Blobs failed to upload while the network is up. */
    UPLOAD_FAILED(R.string.send_fail_upload),

    /** Upload done, publish failed. */
    PUBLISH_FAILED(R.string.send_fail_publish),

    /** Message could not be created. */
    MESSAGE_FAILED(R.string.send_fail_message),
    ;
}

enum class MediaSendState {
    QUEUED,
    /** Preparing files: trim, rotate, compress. No network needed. */
    PROCESSING,
    /** Files ready and validated; blobs go to the server. */
    UPLOADING,
    SENT,
    FAILED,
    /** User cancel. Terminal: cannot be resumed. */
    CANCELED,
}

/**
 * One send item: source + edits + path of the finished file.
 *
 * Edits are stored whole ([draft]), not as a "result": after process death
 * the work must be REPEATED, not guessed. The same model travels through
 * picker, editor and send — there is no second copy of edit fields.
 */
data class MediaSendItem(
    val index: Int,
    val draft: MediaDraftItem,
    /** Where the finished file goes. Known BEFORE message insert — doubles as local_path. */
    val outputPath: String,
    /**
     * Fingerprint of the edits. Part of the file name, so re-preparing the
     * same edits never happens (the finished file is simply found), and any
     * edit change invalidates the old output by itself — no staleness checks.
     */
    val editRevision: Long,
    /** File produced AND validated. Only then may it be uploaded. */
    val ready: Boolean = false,
    /**
     * Reference to an ALREADY uploaded blob.
     *
     * Written right after the upload, so resuming after process death never
     * sends the same bytes twice; without it "resume" would re-upload the
     * whole album on every restart.
     */
    val uploadedRef: String? = null,
) {
    val isVideo: Boolean get() = draft.isVideo
    val mediaType: String get() = if (isVideo) "video" else "image"
}

data class MediaSendJob(
    /**
     * Key of the work and of the temp-file directory.
     *
     * Not messageId: the message id is issued by the core at insert, while
     * finished-file paths are needed EARLIER — they go into that very
     * message's manifest. A persistent key of our own avoids a second DB pass.
     */
    val sendId: String,
    val chatId: String,
    /** The channel post this album comments on (null = the chat itself). */
    val thread: String? = null,
    /** The message this one answers (null = not a reply). */
    val replyTo: String? = null,
    /** Persistent message id. Bubble, progress, cancel — all keyed by it. */
    val messageId: String,
    val caption: String?,
    val ttlSecs: Int,
    /** A timed view of one photo or video: seconds on screen (0 = until closed). */
    val glanceSecs: Int? = null,
    val photoMaxEdge: Int,
    val photoQuality: Int,
    /** "HD": videos as they are; else "SD" — re-encoded down to the economy profile. */
    val hd: Boolean = false,
    /** Videos go out at this long edge (360p…1080p); [MediaQuality.ORIGINAL]: as they are. */
    val videoEdge: Int = com.alphamessenger.ui.media.export.MediaQuality.ORIGINAL,
    /** Other Share recipients: same blobs go to them by reference, no second upload. */
    val alsoTo: List<String>,
    val state: MediaSendState,
    val items: List<MediaSendItem>,
    /**
     * Local-echo manifest (`local_path` + posters + metadata) — the same one
     * sent with the message. Needed by the fan-out to the other recipients
     * so their bubbles render the same; after process death it exists
     * nowhere else.
     */
    val echoJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Why it stalled — persisted together with the state.
     *
     * On disk, not in memory: after a restart the user must see the same
     * reason, not a mute ↻ that gives no clue whether to fix the network,
     * free up space, or resend.
     */
    val failure: MediaSendFailure? = null,
    /** Attempts so far. Keeps a hopeless send from retrying forever. */
    val attempts: Int = 0,
) {
    val ttl: UInt get() = ttlSecs.coerceAtLeast(0).toUInt()

    fun withState(next: MediaSendState) = copy(state = next, failure = null)

    fun failedWith(reason: MediaSendFailure) =
        copy(state = MediaSendState.FAILED, failure = reason, attempts = attempts + 1)

    fun withItem(index: Int, transform: (MediaSendItem) -> MediaSendItem) =
        copy(items = items.map { if (it.index == index) transform(it) else it })

    /** Transfer key of one item — same as the album's: `<msgId>#<i>`. */
    fun opId(index: Int): String = "$messageId#$index"

    companion object {
        /**
         * Fingerprint of an item's edits. Changes exactly when the result
         * changes: other source, other crop, other sound, other quality.
         * Computed from a string, not from field `hashCode()`: hashCode of
         * Float/String is not guaranteed stable across platform versions,
         * while this key must survive restarts.
         */
        fun revisionOf(
            d: MediaDraftItem,
            maxEdge: Int,
            quality: Int,
            profileVersion: Int = com.alphamessenger.ui.media.export.MediaExportProfile.V1_COMPATIBILITY.version,
        ): Long = revisionOf(
            sourceIdentity = d.uri.toString(),
            spec = com.alphamessenger.ui.media.export.MediaEditSpec.of(d, maxEdge, quality),
            profileVersion = profileVersion,
        )

        /**
         * Core of the key: source + edits + profile.
         *
         * Split out because this is the ONLY place deciding "same work or
         * different", and it must be test-covered. With `Uri` in the
         * signature the test would need an emulator — what must be checked
         * here is determinism, not URI parsing.
         *
         * The third part is mandatory: changing the output profile changes
         * the file itself; without it a finished old-profile result would
         * silently pass as new.
         */
        fun revisionOf(
            sourceIdentity: String,
            spec: com.alphamessenger.ui.media.export.MediaEditSpec,
            profileVersion: Int,
        ): Long {
            val canonical = "$sourceIdentity|${spec.canonical()}|profile=$profileVersion"
            // FNV-1a 64: short, stable, no dependencies.
            var h = -0x340d631b7bdddcdbL
            for (ch in canonical) {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return h and Long.MAX_VALUE
        }
    }
}

// --- Serialization ---
// Hand-rolled, not Parcel: Parcel bytes do not survive an app update, and the
// job must. Gson is avoided too: R8 has eaten reflection on media models
// before (see the keep rules) — not a risk worth repeating on the send path.

internal object MediaSendJson {

    fun jobToJson(job: MediaSendJob): JSONObject = JSONObject().apply {
        put("send_id", job.sendId)
        put("chat_id", job.chatId)
        job.thread?.let { put("thread", it) }
        job.replyTo?.let { put("reply_to", it) }
        put("message_id", job.messageId)
        job.caption?.let { put("caption", it) }
        put("ttl", job.ttlSecs)
        job.glanceSecs?.let { put("glance", it) }
        put("max_edge", job.photoMaxEdge)
        put("quality", job.photoQuality)
        put("hd", job.hd)
        put("video_edge", job.videoEdge)
        put("also_to", JSONArray(job.alsoTo))
        put("state", job.state.name)
        put("echo", job.echoJson)
        put("created_at", job.createdAt)
        job.failure?.let { put("failure", it.name) }
        put("attempts", job.attempts)
        put("items", JSONArray().apply { job.items.forEach { put(itemToJson(it)) } })
    }

    fun jobFromJson(o: JSONObject): MediaSendJob? = runCatching {
        MediaSendJob(
            sendId = o.getString("send_id"),
            chatId = o.getString("chat_id"),
            thread = o.optString("thread", "").takeIf { it.isNotEmpty() },
            replyTo = o.optString("reply_to", "").takeIf { it.isNotEmpty() },
            messageId = o.getString("message_id"),
            caption = o.optString("caption", "").takeIf { it.isNotEmpty() },
            ttlSecs = o.optInt("ttl", 0),
            glanceSecs = if (o.has("glance")) o.optInt("glance") else null,
            photoMaxEdge = o.optInt("max_edge", 1600),
            photoQuality = o.optInt("quality", 80),
            // Jobs saved before SD/HD existed sent videos as they were.
            hd = o.optBoolean("hd", true),
            // Saved before the quality choice: HD sent as is, SD at 720p.
            videoEdge = o.optInt("video_edge", if (o.optBoolean("hd", true)) com.alphamessenger.ui.media.export.MediaQuality.ORIGINAL else 1280),
            alsoTo = o.optJSONArray("also_to")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
            } ?: emptyList(),
            state = runCatching { MediaSendState.valueOf(o.optString("state")) }
                .getOrDefault(MediaSendState.QUEUED),
            echoJson = o.optString("echo", "[]"),
            createdAt = o.optLong("created_at", System.currentTimeMillis()),
            failure = o.optString("failure", "").takeIf { it.isNotEmpty() }
                ?.let { name -> runCatching { MediaSendFailure.valueOf(name) }.getOrNull() },
            attempts = o.optInt("attempts", 0),
            items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::itemFromJson) }
            } ?: emptyList(),
        ).takeIf { it.items.isNotEmpty() }
    }.getOrNull()

    private fun itemToJson(item: MediaSendItem): JSONObject = JSONObject().apply {
        put("index", item.index)
        put("output", item.outputPath)
        put("revision", item.editRevision)
        put("ready", item.ready)
        item.uploadedRef?.let { put("uploaded_ref", it) }
        put("draft", draftToJson(item.draft))
    }

    private fun itemFromJson(o: JSONObject): MediaSendItem? {
        val draft = draftFromJson(o.optJSONObject("draft") ?: return null) ?: return null
        return MediaSendItem(
            index = o.optInt("index"),
            draft = draft,
            outputPath = o.optString("output"),
            editRevision = o.optLong("revision"),
            ready = o.optBoolean("ready", false),
            uploadedRef = o.optString("uploaded_ref", "").takeIf { it.isNotEmpty() },
        )
    }

    fun draftToJson(d: MediaDraftItem): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("uri", d.uri.toString())
        d.originalUri?.let { put("original_uri", it.toString()) }
        put("kind", d.kind.name)
        put("source", d.source.name)
        put("mime", d.mimeType)
        d.filename?.let { put("filename", it) }
        put("size", d.sizeBytes)
        put("duration", d.durationMs)
        put("trim_start", d.trimStartMs)
        put("trim_end", d.trimEndMs)
        put("muted", d.muted)
        put("rotation", d.rotationDegrees)
        put("flip", d.flip)
        put("exif_rotation", d.exifRotationDegrees)
        put("exif_flip", d.exifFlip)
        put("crop_l", d.cropLeft.toDouble())
        put("crop_t", d.cropTop.toDouble())
        put("crop_r", d.cropRight.toDouble())
        put("crop_b", d.cropBottom.toDouble())
        put("edited", d.isEdited)
        d.markup?.let { put("markup", it) }
        put("as_file", d.sendAsFile)
        put("delete_after_use", d.deleteAfterUse)
    }

    fun draftFromJson(o: JSONObject): MediaDraftItem? = runCatching {
        MediaDraftItem(
            id = o.getString("id"),
            uri = Uri.parse(o.getString("uri")),
            originalUri = o.optString("original_uri", "").takeIf { it.isNotEmpty() }?.let(Uri::parse),
            kind = MediaDraftItem.Kind.valueOf(o.getString("kind")),
            source = MediaDraftItem.Source.valueOf(o.getString("source")),
            mimeType = o.optString("mime", "application/octet-stream"),
            filename = o.optString("filename", "").takeIf { it.isNotEmpty() },
            sizeBytes = o.optLong("size", MediaDraftItem.SIZE_UNKNOWN),
            durationMs = o.optLong("duration", 0L),
            trimStartMs = o.optLong("trim_start", 0L),
            trimEndMs = o.optLong("trim_end", MediaDraftItem.TIME_UNDEFINED),
            muted = o.optBoolean("muted", false),
            rotationDegrees = o.optInt("rotation", 0),
            flip = o.optInt("flip", MediaDraftItem.FLIP_NONE),
            exifRotationDegrees = o.optInt("exif_rotation", 0),
            exifFlip = o.optInt("exif_flip", MediaDraftItem.FLIP_NONE),
            cropLeft = o.optDouble("crop_l", 0.0).toFloat(),
            cropTop = o.optDouble("crop_t", 0.0).toFloat(),
            cropRight = o.optDouble("crop_r", 1.0).toFloat(),
            cropBottom = o.optDouble("crop_b", 1.0).toFloat(),
            isEdited = o.optBoolean("edited", false),
            markup = o.optString("markup", "").takeIf { it.isNotEmpty() },
            sendAsFile = o.optBoolean("as_file", false),
            deleteAfterUse = o.optBoolean("delete_after_use", false),
        )
    }.getOrNull()
}
