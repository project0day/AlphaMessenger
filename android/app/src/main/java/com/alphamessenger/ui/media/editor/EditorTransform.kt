package com.alphamessenger.ui.media.editor

import com.alphamessenger.ui.media.draft.MediaDraftItem

/**
 * Crop rectangle in FRACTIONS of the frame.
 *
 * Only `0f..1f` and only relative to the VISUALLY oriented frame — the one
 * the user sees after the file's rotation and their own. No pixels in the
 * model at all: the screen, the thumbnail and the original have three
 * different sizes, and any pixel value eventually lands on the wrong image.
 */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** The frame differs from full noticeably, not by a finger's jitter. */
    val isFull: Boolean
        get() = left <= EPS && top <= EPS && right >= 1f - EPS && bottom >= 1f - EPS

    companion object {
        val FULL = CropRect()
        const val EPS = 0.001f
    }
}

/**
 * Full description of what has been done to the frame.
 *
 * The project's ONE transform contract. Both display and file writing must
 * follow it — otherwise the screen shows one thing and the recipient gets
 * another.
 *
 * The order is fixed and must not be broken:
 *
 * ```
 * EXIF → user rotation → flip → crop → size/quality
 * ```
 *
 * Crop goes LAST among the geometry steps precisely because its fractions
 * are relative to the already-rotated, already-flipped frame — i.e. to what
 * was on screen.
 */
data class EditorTransform(
    val exifRotationDegrees: Int = 0,
    val exifFlip: Int = MediaDraftItem.FLIP_NONE,
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val crop: CropRect = CropRect.FULL,
) {
    /** Frame turned a quarter — the sides swap. */
    val quarterTurn: Boolean get() = ((exifRotationDegrees + rotationDegrees) % 180 + 180) % 180 != 0

    /** Rotation applied by DISPLAY (file orientation is applied by the loader). */
    val displayRotation: Int get() = ((rotationDegrees % 360) + 360) % 360

    val hasCrop: Boolean get() = !crop.isFull

    /**
     * Proportions of what the user will see: from the file's dimensions,
     * accounting for both rotations and the crop. One place for every
     * "how does the frame fit" computation.
     */
    fun displayAspect(sourceWidth: Int, sourceHeight: Int): Float {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 0f
        val w = (if (quarterTurn) sourceHeight else sourceWidth) * crop.width
        val h = (if (quarterTurn) sourceWidth else sourceHeight) * crop.height
        return if (h <= 0f) 0f else w / h
    }

    companion object {
        fun of(item: MediaDraftItem): EditorTransform = EditorTransform(
            exifRotationDegrees = item.exifRotationDegrees,
            exifFlip = item.exifFlip,
            rotationDegrees = item.rotationDegrees,
            flipHorizontal = item.flip and MediaDraftItem.FLIP_HORIZONTAL != 0,
            flipVertical = item.flip and MediaDraftItem.FLIP_VERTICAL != 0,
            crop = CropRect(item.cropLeft, item.cropTop, item.cropRight, item.cropBottom),
        )
    }
}
