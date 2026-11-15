package com.alphamessenger.transfer

import androidx.compose.runtime.Immutable

/**
 * THE single source of truth for what a media bubble draws.
 *
 * A bubble (Photo / Video / Audio / File) reads ONE [FileUiState] and renders it.
 * It performs NO transfer reasoning of its own: no `if (snap…)`, `if (ownTransfer…)`,
 * `if (cachedPath…)`, `if (localPath…)`, `if (isDownloading…)`, `if (isDone…)`,
 * `if (canStart…)`. All of that is decided ONCE by [fileUiStateOf].
 *
 * These seven states are the ENTIRE vocabulary — there are no others.
 */
@Immutable
sealed interface FileUiState {
    /** Bytes are available (sender original OR decrypted cache OR streamable).
     *  [path] is the file to open (null ⇒ streaming, fetched on tap). [playable]
     *  ⇒ this is video: draw a ▶ affordance; else the content itself is shown. */
    @Immutable data class Ready(val path: String?, val playable: Boolean) : FileUiState

    /** Bytes coming down. Draw ✕ + ring; %/speed from [progress]. */
    @Immutable data class Downloading(val progress: TransferProgress) : FileUiState

    /** Bytes going up (own message). Draw ✕ + ring; %/speed from [progress]. */
    @Immutable data class Uploading(val progress: TransferProgress) : FileUiState

    /** Download paused. Draw ▷ (resume), no ring. */
    @Immutable data class Paused(val progress: TransferProgress) : FileUiState

    /** Failed. Draw ↻. [direction] decides whether ↻ retries the upload or the
     *  download — the two are NOT interchangeable. */
    @Immutable data class Failed(val direction: TransferDirection) : FileUiState

    /**
     * The work could run, but there is no connectivity. Draw ✕ and the ring at
     * the LAST confirmed position — the resume continues from it on its own.
     *
     * A separate state, not a shade of [Failed]: ↻ is harmful here. There is
     * nothing to retry, and pressing it would just return the same icon half a
     * second later.
     */
    @Immutable data class WaitingNetwork(val progress: TransferProgress) : FileUiState

    /** No server holds the file any more. Draw the "gone" mark, never ↻ —
     *  a tap still asks the servers once more, in case a copy reappeared. */
    @Immutable data class Gone(val unknownServer: Boolean = false) : FileUiState

    /** Album item uploaded, the album not sent yet (others still going up): draw ✓. */
    data object Sent : FileUiState

    /** Nothing here, nothing running: draw ↓ (receiver, or a sender whose own
     *  original is gone and the cache was evicted). */
    data object NeedDownload : FileUiState

    /** Draw no transfer affordance at all (voice auto-download in flight, or a
     *  state with nothing to show). The bubble's own content stands alone. */
    data object Hidden : FileUiState

    // --- Read-only helpers so the UI never pattern-matches for these. ---
    val progressOrNull: TransferProgress?
        get() = when (this) {
            is Downloading -> progress
            is Uploading -> progress
            is Paused -> progress
            is WaitingNetwork -> progress
            else -> null
        }

    /** The transfer occupies the bubble: bytes are moving OR we're waiting for
     *  transport. In both cases ✕ is shown, the ring stands at its place, and
     *  tapping the bubble opens nothing — there is no file yet. */
    val isActive: Boolean get() = this is Downloading || this is Uploading || this is WaitingNetwork

    /** The file that can be opened right now (null when not [Ready] or streaming). */
    val readyPath: String? get() = (this as? Ready)?.path

    /** The bytes exist (openable or streamable). */
    val isReady: Boolean get() = this is Ready
}

/**
 * Where the bytes come from. The ONLY answer to "where to get the bytes".
 *
 * Computed ONLY by the Repository (`AlphaCoreRepository.mediaAvailability`) — the
 * sole owner of disk truth — and folded into the download snapshot by the
 * [TransferEngine]. No UI ever re-derives it. The order encodes the
 * other messengers precedence: the sender's own original wins over the cache, the
 * cache over streaming, streaming over a fresh remote fetch.
 */
sealed interface FileSource {
    /** The user's original or our OUTBOX — our own bytes, nothing to download.
     *  Both are "local and not cache"; for the "what to show" decision they are
     *  indistinguishable, and the ownership distinction lives where it matters —
     *  in the deletion rules ([AlphaCoreRepository.deleteMediaCacheBytes] touches
     *  neither). Two states here would force every screen to tell apart things
     *  that look identical to it. */
    @Immutable data class Original(val path: String) : FileSource

    /** The decrypted blob in the app cache (`cacheDir/media/<hash>`). */
    @Immutable data class Cache(val path: String) : FileSource

    /** Video that plays via progressive chunks without a full download. */
    data object Streaming : FileSource

    /** Not here, but a remote ref exists → it can be downloaded. */
    data object Remote : FileSource

    /** Nothing to show and nothing to fetch. */
    data object Missing : FileSource
}

// NOTE: there is no `fileUiStateOf(...)` fold here — computing the
// transfer UI state is the job of the ONE producer `media.rememberMediaState`,
// and `MediaState.toOverlayState(...)` maps that into the [FileUiState] this
// overlay view renders. FileUiState is purely the overlay's view-model.

/**
 * classic filename shortening for display ONLY.
 *
 * Keeps the extension and the tail of the base name, collapsing the middle:
 *   `A_very_long_file_name_report.pdf` → `A_very_lo….pdf`
 *
 * The extension is NEVER dropped. The full name is untouched everywhere else
 * (long-press, details, sending) — this only trims what is shown in the bubble.
 */
fun ellipsizeFileName(name: String, max: Int = 34): String {
    if (name.length <= max) return name
    val dot = name.lastIndexOf('.')
    // No real extension (no dot, or a dot at the very start/end) → trim the tail.
    if (dot <= 0 || dot == name.length - 1) return name.take(max - 1).trimEnd() + "…"
    val ext = name.substring(dot) // includes the dot, e.g. ".pdf"
    // Absurdly long "extension" (not a real one) → treat the whole thing as a name.
    if (ext.length > 8) return name.take(max - 1).trimEnd() + "…"
    val base = name.substring(0, dot)
    val keep = (max - ext.length - 1).coerceAtLeast(1) // room for base + "…" + ext
    return base.take(keep).trimEnd() + "…" + ext
}
