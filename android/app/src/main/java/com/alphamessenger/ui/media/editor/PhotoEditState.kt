package com.alphamessenger.ui.media.editor

import com.alphamessenger.ui.media.draft.MediaDraftItem

/** What the editor shows right now. One screen — two modes. */
enum class PhotoEditorMode { ADJUST, CROP, DRAW }

/**
 * Working copy of the edits — what changes while the user is in the editor.
 *
 * The committed truth lives in `MediaDraftItem` inside `MediaDraftStore`.
 * This state is NOT written there on every finger move: the editor is
 * transactional — open a copy, change it as long as you like, commit it all
 * at once on "Done". "Cancel" and "back" leave no trace.
 *
 * File orientation (EXIF) is not part of this — the user did not set it; it
 * travels separately in [EditorTransform] and is applied by the loader for
 * display.
 */
data class PhotoEditState(
    val crop: CropRect = CropRect.FULL,
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val ratio: CropRatio = CropRatio.Free,
    val markup: PhotoMarkup = PhotoMarkup(),
) {
    /** Anything to save: otherwise "Done" must not touch the draft. */
    val isDirty: Boolean
        get() = !crop.isFull || rotationDegrees != 0 || flipHorizontal || flipVertical || !markup.isEmpty

    /** A quarter turn changes the frame's shape — the crop resets to the full frame. */
    fun rotatedRight(): PhotoEditState = copy(
        rotationDegrees = (rotationDegrees + 90) % 360,
        crop = CropRect.FULL,
        ratio = CropRatio.Free,
    )

    fun flippedHorizontally(): PhotoEditState = copy(flipHorizontal = !flipHorizontal)

    fun flippedVertically(): PhotoEditState = copy(flipVertical = !flipVertical)

    fun reset(): PhotoEditState = PhotoEditState()

    fun withMarkup(m: PhotoMarkup): PhotoEditState = copy(markup = m)

    /** Flip bitmask — in the form the draft item stores it. */
    val flipMask: Int
        get() = (if (flipHorizontal) MediaDraftItem.FLIP_HORIZONTAL else 0) or
            (if (flipVertical) MediaDraftItem.FLIP_VERTICAL else 0)

    /** Full transform for display and for the file: edits plus file data. */
    fun transformFor(item: MediaDraftItem): EditorTransform = EditorTransform(
        exifRotationDegrees = item.exifRotationDegrees,
        exifFlip = item.exifFlip,
        rotationDegrees = rotationDegrees,
        flipHorizontal = flipHorizontal,
        flipVertical = flipVertical,
        crop = crop,
    )

    /** Write the edits into the draft item. The single translation into the model. */
    fun applyTo(item: MediaDraftItem): MediaDraftItem = item.copy(
        cropLeft = crop.left,
        cropTop = crop.top,
        cropRight = crop.right,
        cropBottom = crop.bottom,
        rotationDegrees = rotationDegrees,
        flip = flipMask,
        markup = markup.takeIf { !it.isEmpty }?.toJson(),
        isEdited = item.isEdited || isDirty,
    )

    companion object {
        /** Open the editor on what is already accumulated in the draft. */
        fun of(item: MediaDraftItem): PhotoEditState = PhotoEditState(
            crop = CropRect(item.cropLeft, item.cropTop, item.cropRight, item.cropBottom),
            rotationDegrees = ((item.rotationDegrees % 360) + 360) % 360,
            flipHorizontal = item.flip and MediaDraftItem.FLIP_HORIZONTAL != 0,
            flipVertical = item.flip and MediaDraftItem.FLIP_VERTICAL != 0,
            ratio = CropRatio.Free,
            markup = PhotoMarkup.fromJson(item.markup),
        )
    }
}
