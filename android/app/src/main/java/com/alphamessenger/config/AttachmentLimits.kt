package com.alphamessenger.config

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.alphamessenger.R

object AttachmentLimits {

    /** Pre-send validation outcome. `null` = OK. `selectedBytes`/`limitBytes`
     *  fill the human dialog ("Selected X · Max Y"). `kind` picks the text. */
    data class Rejection(val kind: String, val selectedBytes: Long, val limitBytes: Long)

    /** Validate a picked URI BEFORE copying / creating a message / uploading.
     *  Reuses the single-source limits below. Returns a [Rejection] or null. */
    fun validateUri(context: Context, uri: Uri, mediaType: String): Rejection? {
        val cr = context.contentResolver
        val mime = runCatching { cr.getType(uri) }.getOrNull().orEmpty()
        val size = queryUriSize(cr, uri)
        if (size == -1L) return Rejection("unreadable", 0L, 0L)   // gone / not readable
        if (size == 0L) return Rejection("empty", 0L, 0L)          // 0 bytes / corrupt
        // Lenient MIME sanity: a renamed file (rename.exe → .mp4) whose provider
        // MIME contradicts the picked category is rejected.
        if (mediaType in setOf("image", "video", "audio") && mime.isNotEmpty() && !mime.startsWith("$mediaType/")) {
            return Rejection("wrong_type", if (size > 0) size else 0L, 0L)
        }
        if (size > 0L) {
            val limit = limitForMediaType(mediaType, mime)
            if (size > limit) return Rejection("too_big", size, limit)
            // Enough room to copy the file locally (+ small headroom).
            val free = runCatching { android.os.StatFs(context.cacheDir.path).availableBytes }
                .getOrDefault(Long.MAX_VALUE)
            if (free < size + 16L * 1024 * 1024) return Rejection("no_space", size, free)
        }
        return null
    }

    /** File size for a content URI: OpenableColumns → AFD length → readability.
     *  -1 = unreadable, 0 = empty, >0 = bytes (or -2 kept internal → treated OK). */
    private fun queryUriSize(cr: android.content.ContentResolver, uri: Uri): Long {
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        }
        runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { if (it.length >= 0) return it.length } }
        val readable = runCatching { cr.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)
        return if (readable) 1L else -1L   // readable but unknown size → let it through (fallback check on copy)
    }


    const val IMAGE_MAX_BYTES    = 25L  * 1024 * 1024   // 25 MB
    // Video, files and archives up to just under the core's 2 GiB transport cap.
    const val VIDEO_MAX_BYTES    = 2000L * 1024 * 1024  // 2000 MB
    const val AUDIO_MAX_BYTES    = 100L * 1024 * 1024   // 100 MB
    const val VOICE_MAX_BYTES    = 50L  * 1024 * 1024   // 50 MB
    const val DOCUMENT_MAX_BYTES = 2000L * 1024 * 1024  // 2000 MB
    const val ARCHIVE_MAX_BYTES  = 2000L * 1024 * 1024  // 2000 MB

    private val ARCHIVE_MIME_TYPES = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-7z-compressed",
        "application/x-rar-compressed",
        "application/x-tar",
        "application/gzip",
        "application/x-bzip2",
    )

    fun limitFor(mimeType: String): Long = when {
        mimeType.startsWith("image/")  -> IMAGE_MAX_BYTES
        mimeType.startsWith("video/")  -> VIDEO_MAX_BYTES
        mimeType.startsWith("audio/")  -> AUDIO_MAX_BYTES
        mimeType in ARCHIVE_MIME_TYPES -> ARCHIVE_MAX_BYTES
        else                           -> DOCUMENT_MAX_BYTES
    }

    fun limitForMediaType(mediaType: String, mimeType: String = ""): Long = when (mediaType) {
        "image"  -> IMAGE_MAX_BYTES
        "video"  -> VIDEO_MAX_BYTES
        "audio"  -> AUDIO_MAX_BYTES
        "voice"  -> VOICE_MAX_BYTES
        else     -> if (mimeType.isNotEmpty()) limitFor(mimeType) else DOCUMENT_MAX_BYTES
    }

    /** @return the localized limit sentence; the MB value is a %1$d arg. */
    @StringRes
    fun humanReadableLimit(mimeType: String): Int {
        return when {
            mimeType.startsWith("image/")  -> R.string.limit_photo_max
            mimeType.startsWith("video/")  -> R.string.limit_video_max
            mimeType.startsWith("audio/")  -> R.string.limit_audio_max
            mimeType in ARCHIVE_MIME_TYPES -> R.string.limit_archive_max
            else                           -> R.string.limit_document_max
        }
    }

    /** @return the localized limit sentence; the MB value is a %1$d arg. */
    @StringRes
    fun humanReadableLimitForMediaType(mediaType: String, mimeType: String = ""): Int {
        return when (mediaType) {
            "image" -> R.string.limit_photo_max
            "video" -> R.string.limit_video_max
            "audio" -> R.string.limit_audio_max
            "voice" -> R.string.limit_voice_max
            else    -> if (mimeType.isNotEmpty()) humanReadableLimit(mimeType)
                       else R.string.limit_file_max
        }
    }
}
