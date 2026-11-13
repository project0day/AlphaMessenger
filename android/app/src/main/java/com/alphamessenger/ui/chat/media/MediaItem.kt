package com.alphamessenger.ui.chat.media

import com.alphamessenger.ui.chat.MessageUi

/** A single content unit for the viewer. The viewer knows nothing about MessageUi/Bubble/ChatScreen. */
data class MediaItem(
    val id: String,
    val kind: Kind,
    val presentation: Presentation = Presentation.STANDARD,
    val caption: String = "",
    val refJson: String? = null,
    val localPath: String? = null,
    val posterB64: String? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val senderName: String? = null,
    val timestamp: String? = null,
    /** Only for [Kind.LOCATION]. */
    val lat: Double? = null,
    val lng: Double? = null,
    val placeName: String? = null,
    val placeAddress: String? = null,
) {
    /** Content kind → picks the Renderer. */
    enum class Kind { IMAGE, VIDEO, GIF, DOCUMENT, LOCATION }

    /** Presentation mode → drives capabilities and window flags. */
    enum class Presentation { STANDARD, AVATAR, SECURE }

    val isVideo: Boolean get() = kind == Kind.VIDEO

    companion object {
        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts")
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

        fun kindOf(mediaType: String?, filename: String?): Kind {
            val ext = filename?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when {
                mediaType == "video" -> Kind.VIDEO
                mediaType == "gif" || ext == "gif" -> Kind.GIF
                mediaType == "image" -> Kind.IMAGE
                mediaType == "location" -> Kind.LOCATION
                mediaType == "file" && ext in VIDEO_EXTS -> Kind.VIDEO
                mediaType == "file" && ext in IMAGE_EXTS -> Kind.IMAGE
                else -> Kind.DOCUMENT
            }
        }

        /** Avatar: the same image, but without chat actions. */
        fun avatar(path: String) = MediaItem(
            id = "avatar:$path",
            kind = Kind.IMAGE,
            presentation = Presentation.AVATAR,
            localPath = path,
        )

        /** A local file that is not in the chat gallery yet (still decrypting, etc.). */
        fun localFile(path: String) = MediaItem(
            id = path,
            kind = kindOf("file", path),
            localPath = path,
        )
    }
}

fun MessageUi.toMediaItem(): MediaItem? {
    if (!isMedia) return null
    val kind = MediaItem.kindOf(mediaType, mediaFilename)
    if (kind == MediaItem.Kind.DOCUMENT) return null
    if (mediaRefJson == null && localMediaPath == null) return null
    return MediaItem(
        id = id,
        kind = kind,
        presentation = if (isViewOnce) MediaItem.Presentation.SECURE else MediaItem.Presentation.STANDARD,
        caption = mediaCaption ?: "",
        refJson = mediaRefJson,
        localPath = localMediaPath,
        posterB64 = mediaPosterB64,
        durationMs = mediaDurationMs,
        width = mediaWidth,
        height = mediaHeight,
        filename = mediaFilename,
        sizeBytes = mediaSizeBytes,
        senderName = if (isOutgoing) com.alphamessenger.util.AppStrings.get(com.alphamessenger.R.string.notif_you) else senderName,
        timestamp = timestamp,
    )
}

/** Album → all items; standalone message → one. */
fun MessageUi.toMediaItems(): List<MediaItem> =
    (albumItems ?: listOf(this)).mapNotNull { it.toMediaItem() }
