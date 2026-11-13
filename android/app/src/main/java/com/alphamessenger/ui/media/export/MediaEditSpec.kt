package com.alphamessenger.ui.media.export

import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.media.editor.CropRect

/**
 * The ONE description of what the user did to an attachment.
 *
 * The edits used to live in [MediaDraftItem] fields with three interpreters:
 * the frame-transform contract, the video range, and compression params that
 * were not even stored nearby — they travelled as a separate argument
 * through four layers. With several interpreters, every new property must be
 * added to each, and sooner or later they diverge: display computes one
 * thing, the file comes out another.
 *
 * Here a property is declared once, and its meaning is given by methods of
 * this same class. The screen, the planner and the executor do not derive
 * "does this need cutting" by their own arithmetic — they ask.
 *
 * Immutable: an edit is a new value, not a mutation. That is exactly why a
 * fingerprint can be taken from it, without guessing which version a
 * finished file belongs to.
 */
data class MediaEditSpec(
    // --- Time (video) ---
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = MediaDraftItem.TIME_UNDEFINED,
    val muted: Boolean = false,

    // --- Geometry from the file (EXIF). The user did not set it ---
    val exifRotationDegrees: Int = 0,
    val exifFlip: Int = MediaDraftItem.FLIP_NONE,

    // --- Geometry from the user ---
    val rotationDegrees: Int = 0,
    val flip: Int = MediaDraftItem.FLIP_NONE,
    val crop: CropRect = CropRect.FULL,

    // --- How to send ---
    /** As a document, not photo/video. Conversion does not apply. */
    val sendAsFile: Boolean = false,
    /** Photo long edge after compression, pixels. */
    val photoMaxEdge: Int = DEFAULT_PHOTO_MAX_EDGE,
    /** JPEG quality, 1..100. */
    val photoQuality: Int = DEFAULT_PHOTO_QUALITY,
    /** Drawing over the photo (JSON), empty = none. */
    val markup: String = "",
) {

    // --- Time ---

    /** End not set — means until the end of the clip. */
    fun endOr(durationMs: Long): Long =
        if (trimEndMs == MediaDraftItem.TIME_UNDEFINED) durationMs else trimEndMs

    fun lengthMs(durationMs: Long): Long = (endOr(durationMs) - trimStartMs).coerceAtLeast(0L)

    /** A tail shorter than [END_EPS_MS] is finger jitter, not a trim. */
    fun hasTrim(durationMs: Long): Boolean =
        trimStartMs > 0L || endOr(durationMs) < durationMs - END_EPS_MS

    // --- Geometry ---

    val hasCrop: Boolean get() = !crop.isFull

    val hasUserRotation: Boolean get() = rotationDegrees % 360 != 0

    val hasUserFlip: Boolean get() = flip != MediaDraftItem.FLIP_NONE

    val hasExifGeometry: Boolean
        get() = exifRotationDegrees % 360 != 0 || exifFlip != MediaDraftItem.FLIP_NONE

    /**
     * Whether the pixels must be REDRAWN.
     *
     * The key question of all planning: crop, rotation and flip change the
     * dots themselves, while a time range and muted audio do not. The former
     * can only be done by transcoding; the latter by stream copy, which is
     * several times cheaper and loses no quality.
     */
    val changesPixels: Boolean get() = hasCrop || hasUserRotation || hasUserFlip

    /** Final frame orientation: capture plus user rotation. */
    val effectiveRotation: Int
        get() = ((exifRotationDegrees + rotationDegrees) % 360 + 360) % 360

    /** A quarter turn swaps the sides. */
    val swapsSides: Boolean get() = effectiveRotation % 180 != 0

    // --- Fingerprint ---

    /**
     * Canonical fingerprint of the edits.
     *
     * A string, not `hashCode()`: hashes of Float and String are not
     * guaranteed stable across platform versions, while this fingerprint is
     * part of the finished file's NAME and must survive an app update —
     * otherwise every finished file "goes stale" at once and is redone.
     */
    fun canonical(): String = buildString {
        append(trimStartMs).append(',').append(trimEndMs).append(',').append(muted).append('|')
        append(exifRotationDegrees).append(',').append(exifFlip).append('|')
        append(rotationDegrees).append(',').append(flip).append('|')
        append(crop.left).append(',').append(crop.top).append(',')
        append(crop.right).append(',').append(crop.bottom).append('|')
        append(sendAsFile).append(',').append(photoMaxEdge).append(',').append(photoQuality)
        if (markup.isNotEmpty()) append('|').append(java.util.zip.CRC32().apply { update(markup.toByteArray()) }.value)
    }

    companion object {
        const val DEFAULT_PHOTO_MAX_EDGE = 1600
        const val DEFAULT_PHOTO_QUALITY = 80
        const val END_EPS_MS = 50L

        /**
         * Build the edit description from a draft item.
         *
         * The ONE translation point. Quality arrives as a parameter because
         * it is a send setting, not a file property: the same photo can be
         * sent compressed or as the original.
         */
        fun of(
            item: MediaDraftItem,
            photoMaxEdge: Int = DEFAULT_PHOTO_MAX_EDGE,
            photoQuality: Int = DEFAULT_PHOTO_QUALITY,
        ): MediaEditSpec = MediaEditSpec(
            trimStartMs = item.trimStartMs,
            trimEndMs = item.trimEndMs,
            muted = item.muted,
            exifRotationDegrees = item.exifRotationDegrees,
            exifFlip = item.exifFlip,
            rotationDegrees = item.rotationDegrees,
            flip = item.flip,
            crop = CropRect(item.cropLeft, item.cropTop, item.cropRight, item.cropBottom),
            sendAsFile = item.sendAsFile,
            photoMaxEdge = photoMaxEdge,
            photoQuality = photoQuality,
            markup = item.markup.orEmpty(),
        )
    }
}
