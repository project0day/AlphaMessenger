package com.alphamessenger.media

import androidx.compose.runtime.Immutable
import com.alphamessenger.ui.chat.MessageUi

/**
 * The KIND of media — decided ONCE from the message at creation and never
 * recomputed from transfer/cache state. This is what keeps a bubble's type stable
 * for the message's whole life (a PNG document stays a FILE bubble, etc.).
 */
enum class MediaKind {
    IMAGE, VIDEO, AUDIO, VOICE, FILE, ALBUM;

    /** Video / audio / voice can play. Independent of transfer state. */
    val isPlayable: Boolean get() = this == VIDEO || this == AUDIO || this == VOICE

    /** Image / video render a visual surface (photo or poster). */
    val isVisual: Boolean get() = this == IMAGE || this == VIDEO

    companion object {
        fun from(message: MessageUi): MediaKind = when {
            message.albumItems != null -> ALBUM
            message.mediaType == "image" -> IMAGE
            message.mediaType == "video" -> VIDEO
            message.mediaType == "audio" &&
                message.mediaFilename?.startsWith("voice") == true -> VOICE
            message.mediaType == "audio" -> AUDIO
            // A PNG/JPG/MP4 sent through the DOCUMENT picker stays mediaType="file"
            // → it is a FILE bubble (with a thumbnail), NOT a photo/video bubble.
            else -> FILE
        }
    }
}

/**
 *  MediaState — THE ONE state a media bubble reads. Nothing else.
 *
 * The rebuild's single source of truth. It REPLACES the three parallel "brains"
 * that caused four weeks of whack-a-mole:
 *
 *   FileUiState        (transfer.FileUiState / fileUiStateOf)   ─┐
 *   TransferPresentation (transfer.rememberPresentation)         ├─ DELETED once
 *   MediaPresentationState (media.MediaPresenter)               ─┘  every bubble
 *                                                                   reads MediaState
 *
 * Produced in ONE place (see MediaStateProducer / rememberMediaState) from the
 * engine's transfer status + the message's content. A bubble does ZERO
 * computation — it only `when (state.stage) { … }` and draws. That is what makes
 * video == album == mp3 == png behave identically.
 *
 * The transport (Rust / network / TransferEngine core) is NOT touched — this is
 * only the state the UI reads. Streaming lives on its OWN track and never mutates
 * a MediaState (opening a video must not look like "downloading again").
 */

/**
 * The single transfer lifecycle every bubble switches on. UI-meaningful stages
 * only — the engine's fine-grained states (Preparing/Encrypting/Publishing/…)
 * collapse into these so a bubble never re-interprets them.
 */
enum class TransferStage {
    /** Nothing happening and nothing to fetch (no bytes, no transfer). */
    Idle,

    /** Our own upload is in flight (preparing → encrypting → publishing → sending).
     *  Draw a ring; [MediaState.progress] is the %, null = indeterminate spinner. */
    Uploading,

    /** Our upload finished (server ACK). The sender holds the bytes → openable/playable. */
    Uploaded,

    /** Incoming bytes are not here yet and no download is running → offer ↓. */
    NeedDownload,

    /** A download is in flight. Ring + [MediaState.progress]. */
    Downloading,

    /** A download was paused by the user → offer ▷ resume. */
    Paused,

    /** The bytes are here (cache OR the sender's original). Openable / playable. */
    Ready,

    /** The last transfer failed → offer ↻ retry. */
    Failed,

    /** No server holds the file any more → say so; a tap asks the servers once more. */
    Gone,

    /** Kept only on a server the sender named: fetched only through Tor, so that server never sees our IP. */
    UnknownServer,

    /** Work could proceed but there is no connectivity → ✕ cancel, ring at the
     *  confirmed threshold, ↻ NOT offered. Separate from [Failed] and from [Idle]. */
    WaitingNetwork,

    /** The user cancelled → terminal (an outgoing message usually disappears). */
    Canceled,
    ;

    /** Ring on the bubble: a transfer is running OR waiting for transport. In
     *  both cases there is no file yet, ✕ is shown, and tapping opens nothing. */
    val isRunning: Boolean get() = this == Uploading || this == Downloading || this == WaitingNetwork

    /** The content can be opened / played right now. */
    val hasBytes: Boolean get() = this == Ready || this == Uploaded
}

// MediaControl was removed as a dead SECOND action model: no screen ever
// read it. The verb for the center button is chosen in one place only — the
// overlay path (stage → toOverlayState → FileUiState → OverlayControl); a
// dead rule is more dangerous than a missing one.

/**
 * THE unified state. A bubble receives exactly this and draws — no lookups, no
 * folds, no per-bubble derivation.
 *
 * @param kind        IMAGE/VIDEO/AUDIO/VOICE/FILE/ALBUM — decided ONCE at creation
 *                    (from the file extension / mediaType), never re-derived. This
 *                    is what stops the "PNG becomes a file then a photo" flip: the
 *                    bubble type is fixed for the message's whole life.
 * @param stage       the ONE transfer lifecycle stage — the bubble's main switch.
 * @param progress    0f‥1f while transferring & determinate; null = indeterminate
 *                    (spinner) or not transferring.
 * @param bytes/total live byte counts (for "2.3 / 4.7 MB"); 0 when unknown.
 * @param localPath   the openable/playable file on disk (sender original OR decrypted
 *                    cache); null while it must still be fetched/streamed.
 * @param playable    can this play — from [kind], INDEPENDENT of transfer. A sent
 *                    video keeps ▶ even mid-upload; streaming never removes it.
 * @param streamable  a streaming video: playable via chunks with no full download.
 * @param poster      decoded thumbnail / video poster for instant preview.
 * @param durationMs  video/audio length (0 when unknown / not applicable).
 * @param sizeBytes   total media size at rest.
 * @param albumItems  per-item state for an album (null for single media).
 * @param albumDone/albumTotal aggregate counts for an album's one overlay.
 */
@Immutable
data class MediaState(
    val kind: MediaKind,
    val stage: TransferStage,
    val progress: Float?,
    val bytes: Long,
    val total: Long,
    val localPath: String?,
    val playable: Boolean,
    val streamable: Boolean,
    val poster: android.graphics.Bitmap?,
    val durationMs: Int,
    val sizeBytes: Long,
    val albumItems: List<MediaState>?,
    val albumDone: Int,
    val albumTotal: Int,
) {
    /** Draw the progress ring (a transfer is in flight). */
    val showRing: Boolean get() = stage.isRunning

    /** The bytes are here — openable / playable. */
    val hasBytes: Boolean get() = stage.hasBytes

    val isAlbum: Boolean get() = kind == MediaKind.ALBUM

    /** "2.3 / 4.7 MB" while transferring & determinate, else "4.7 MB" at rest, else null. */
    val sizeLabel: String?
        get() = when {
            showRing && total > 0L -> "${formatBytes(bytes)} / ${formatBytes(total)}"
            sizeBytes > 0L -> formatBytes(sizeBytes)
            else -> null
        }

    /** "0:15" for video/audio, null when unknown. */
    val durationLabel: String? get() = if (durationMs > 0) formatDuration(durationMs) else null

    /** File / music / voice: the byte readout goes in the card's text row, not the ring. */
    val bytesInRow: Boolean
        get() = kind == MediaKind.FILE || kind == MediaKind.AUDIO || kind == MediaKind.VOICE

    companion object {
        /** A neutral idle state (no transfer, no bytes) for a given kind. */
        fun idle(kind: MediaKind, poster: android.graphics.Bitmap? = null): MediaState = MediaState(
            kind = kind, stage = TransferStage.Idle,
            progress = null, bytes = 0L, total = 0L, localPath = null,
            playable = kind.isPlayable, streamable = false, poster = poster,
            durationMs = 0, sizeBytes = 0L, albumItems = null, albumDone = 0, albumTotal = 0,
        )
    }
}

// --- Self-contained formatting (a bubble never formats bytes/time itself) ---

internal fun formatBytes(b: Long): String = MediaSizeFormat.short(b)

internal fun formatDuration(ms: Int): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
