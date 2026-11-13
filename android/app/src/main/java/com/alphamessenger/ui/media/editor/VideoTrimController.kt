package com.alphamessenger.ui.media.editor

import com.alphamessenger.ui.media.draft.MediaDraftItem

/**
 * The video range that will go to the recipient.
 *
 * Milliseconds here, not fractions: video has its own time scale, and
 * converting it to fractions would lose precision on long clips and require
 * re-multiplying on every read. Fractions were needed for the crop frame
 * because its coordinate system rotated with the frame; time has no such
 * problem.
 */
data class VideoTrim(
    val startMs: Long = 0L,
    val endMs: Long = MediaDraftItem.TIME_UNDEFINED,
    val muted: Boolean = false,
) {
    /** End not set — means until the end of the clip. */
    fun endOr(durationMs: Long): Long = if (endMs == MediaDraftItem.TIME_UNDEFINED) durationMs else endMs

    fun lengthMs(durationMs: Long): Long = (endOr(durationMs) - startMs).coerceAtLeast(0L)

    fun hasTrim(durationMs: Long): Boolean =
        startMs > 0L || endOr(durationMs) < durationMs - END_EPS_MS

    companion object {
        /** A tail shorter than this counts as "to the end": a millisecond of jitter is not a trim. */
        const val END_EPS_MS = 50L

        fun of(item: MediaDraftItem): VideoTrim =
            VideoTrim(item.trimStartMs, item.trimEndMs, item.muted)
    }
}

/**
 * Trim-strip math: pure functions over a range.
 *
 * No state, no UI — same rule as the crop frame: every step is computed from
 * the range passed in, so re-grabbing a handle continues from the current
 * position, not the original one.
 */
object VideoTrimController {

    /** Nothing shorter than this: otherwise it is not a clip but a frame. */
    const val MIN_LENGTH_MS = 500L

    fun moveStart(trim: VideoTrim, deltaMs: Long, durationMs: Long): VideoTrim {
        val end = trim.endOr(durationMs)
        val start = (trim.startMs + deltaMs).coerceIn(0L, (end - MIN_LENGTH_MS).coerceAtLeast(0L))
        return trim.copy(startMs = start)
    }

    fun moveEnd(trim: VideoTrim, deltaMs: Long, durationMs: Long): VideoTrim {
        val end = (trim.endOr(durationMs) + deltaMs)
            .coerceIn((trim.startMs + MIN_LENGTH_MS).coerceAtMost(durationMs), durationMs)
        return trim.copy(endMs = end)
    }

    /** Move the window as a whole, keeping its duration. */
    fun move(trim: VideoTrim, deltaMs: Long, durationMs: Long): VideoTrim {
        val length = trim.lengthMs(durationMs)
        val start = (trim.startMs + deltaMs).coerceIn(0L, (durationMs - length).coerceAtLeast(0L))
        return trim.copy(startMs = start, endMs = start + length)
    }

    fun reset(): VideoTrim = VideoTrim()

    fun toggleMute(trim: VideoTrim): VideoTrim = trim.copy(muted = !trim.muted)

    /** Write the range into the draft item. The single translation into the model. */
    fun applyTo(item: MediaDraftItem, trim: VideoTrim): MediaDraftItem = item.copy(
        trimStartMs = trim.startMs,
        trimEndMs = trim.endMs,
        muted = trim.muted,
        isEdited = item.isEdited || trim.startMs > 0L ||
            trim.endMs != MediaDraftItem.TIME_UNDEFINED || trim.muted,
    )
}
