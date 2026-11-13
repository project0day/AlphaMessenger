package com.alphamessenger.ui.media.draft

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

/**
 * One attachment being prepared for sending.
 *
 * A single model for all sources — gallery, camera, file picker, system
 * Share — and all consumers: preview, editors, sending. Before it, every
 * input had its own model (`GalleryItem`, `MediaPickResult`, `SharePayload`),
 * so a new property had to be added in three places.
 *
 * Parcelable deliberately: the set must survive process death,
 * where it travels between Activities and is saved in SavedStateHandle.
 */
data class MediaDraftItem(
    /** Stable key: Compose lists reorder and delete by it, not by URI. */
    val id: String,

    /** The file that will actually be sent. After an edit — the edit result. */
    val uri: Uri,

    /** Original before our copy and before edits. Needed to undo an edit. */
    val originalUri: Uri? = null,

    val kind: Kind,

    /** Where it came from. Affects temp-file cleanup policy, not sending. */
    val source: Source,

    val mimeType: String,
    val filename: String? = null,

    /**
     * Size in bytes; [SIZE_UNKNOWN] — the provider did not say. Zero would mean
     * an empty file. Limits are still checked by `AttachmentLimits` at send time
     * with its own size query, so an unknown size does not bypass them.
     */
    val sizeBytes: Long = SIZE_UNKNOWN,

    /** Caption. One per set — kept on the first element. */
    val caption: String? = null,

    val durationMs: Long = 0L,

    /** Video trim. [trimEndMs] == [TIME_UNDEFINED] — until the end. */
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = TIME_UNDEFINED,
    val muted: Boolean = false,

    /** Rotation and flip set by the USER in the editor. */
    val rotationDegrees: Int = 0,
    val flip: Int = FLIP_NONE,

    /** Orientation read from the file itself (EXIF). Not user-set. */
    val exifRotationDegrees: Int = 0,
    val exifFlip: Int = FLIP_NONE,

    /**
     * Crop is in frame FRACTIONS (0..1), not pixels.
     *
     * Fractions are relative to the frame ALREADY rotated by EXIF and by the
     * user's edit — i.e. to what the person sees. Pixels would be a trap: the
     * screen, the downscaled preview and the original have different sizes, and
     * any of the three coordinate systems would sooner or later be applied to the
     * wrong image. Fractions survive rotation, rescaling and reopening.
     *
     * Default is the full frame. [hasCrop] says whether cropping is needed at all.
     */
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,

    val isEdited: Boolean = false,

    /** Brush strokes, emoji and stickers over the photo ([com.alphamessenger.ui.media.editor.PhotoMarkup] JSON). */
    val markup: String? = null,

    /** Send as a document, not as a photo/video. */
    val sendAsFile: Boolean = false,

    /** Our temp copy in the cache — delete after send or cancel. */
    val deleteAfterUse: Boolean = false,
) : Parcelable {

    enum class Kind { IMAGE, VIDEO, AUDIO, FILE }

    enum class Source { GALLERY, CAMERA, FILE_PICKER, SHARE }

    val isImage: Boolean get() = kind == Kind.IMAGE
    val isVideo: Boolean get() = kind == Kind.VIDEO

    /** Shown as a card, not as a frame: documents and audio have no frame preview. */
    val isDocument: Boolean get() = sendAsFile || kind == Kind.FILE || kind == Kind.AUDIO

    val hasTrim: Boolean get() = trimStartMs > 0L || trimEndMs != TIME_UNDEFINED

    /** Something to trim: the frame differs from the full frame noticeably, not by a pixel. */
    val hasCrop: Boolean
        get() = cropLeft > CROP_EPS || cropTop > CROP_EPS ||
            cropRight < 1f - CROP_EPS || cropBottom < 1f - CROP_EPS

    /** Final frame rotation: file orientation plus user edit. */
    val effectiveRotation: Int get() = ((exifRotationDegrees + rotationDegrees) % 360 + 360) % 360

    /**
     * Type for the existing senders (`sendMediaUri`). The only place where the
     * model knows their vocabulary, so this knowledge is not duplicated at call
     * sites.
     */
    val sendType: String
        get() = when {
            sendAsFile -> "file"
            kind == Kind.IMAGE -> "image"
            kind == Kind.VIDEO -> "video"
            kind == Kind.AUDIO -> "audio"
            else -> "file"
        }

    // --- Parcelable ---
    // By hand, without kotlin-parcelize: not pulling the plugin in for one class.

    constructor(parcel: Parcel) : this(
        id = parcel.readString().orEmpty(),
        uri = requireNotNull(parcel.readParcelableCompat()),
        originalUri = parcel.readParcelableCompat(),
        kind = Kind.entries[parcel.readInt()],
        source = Source.entries[parcel.readInt()],
        mimeType = parcel.readString().orEmpty(),
        filename = parcel.readString(),
        sizeBytes = parcel.readLong(),
        caption = parcel.readString(),
        durationMs = parcel.readLong(),
        trimStartMs = parcel.readLong(),
        trimEndMs = parcel.readLong(),
        muted = parcel.readInt() != 0,
        rotationDegrees = parcel.readInt(),
        flip = parcel.readInt(),
        exifRotationDegrees = parcel.readInt(),
        exifFlip = parcel.readInt(),
        cropLeft = parcel.readFloat(),
        cropTop = parcel.readFloat(),
        cropRight = parcel.readFloat(),
        cropBottom = parcel.readFloat(),
        isEdited = parcel.readInt() != 0,
        markup = parcel.readString(),
        sendAsFile = parcel.readInt() != 0,
        deleteAfterUse = parcel.readInt() != 0,
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeParcelable(uri, flags)
        dest.writeParcelable(originalUri, flags)
        dest.writeInt(kind.ordinal)
        dest.writeInt(source.ordinal)
        dest.writeString(mimeType)
        dest.writeString(filename)
        dest.writeLong(sizeBytes)
        dest.writeString(caption)
        dest.writeLong(durationMs)
        dest.writeLong(trimStartMs)
        dest.writeLong(trimEndMs)
        dest.writeInt(if (muted) 1 else 0)
        dest.writeInt(rotationDegrees)
        dest.writeInt(flip)
        dest.writeInt(exifRotationDegrees)
        dest.writeInt(exifFlip)
        dest.writeFloat(cropLeft)
        dest.writeFloat(cropTop)
        dest.writeFloat(cropRight)
        dest.writeFloat(cropBottom)
        dest.writeInt(if (isEdited) 1 else 0)
        dest.writeString(markup)
        dest.writeInt(if (sendAsFile) 1 else 0)
        dest.writeInt(if (deleteAfterUse) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** "End not set" — so 0 stays valid. */
        const val TIME_UNDEFINED: Long = Long.MIN_VALUE

        /** Size unknown. Synonym of `AttachmentCache.SIZE_UNKNOWN`. */
        const val SIZE_UNKNOWN: Long = -1L

        /** Below this the frame counts as "full frame": a pixel of jitter is not a crop. */
        const val CROP_EPS = 0.001f

        const val FLIP_NONE = 0
        const val FLIP_HORIZONTAL = 1
        const val FLIP_VERTICAL = 2

        @JvmField
        val CREATOR = object : Parcelable.Creator<MediaDraftItem> {
            override fun createFromParcel(parcel: Parcel) = MediaDraftItem(parcel)
            override fun newArray(size: Int) = arrayOfNulls<MediaDraftItem>(size)
        }

        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Parcel.readParcelableCompat(): T? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        readParcelable(T::class.java.classLoader, T::class.java)
    } else {
        readParcelable(T::class.java.classLoader)
    }
