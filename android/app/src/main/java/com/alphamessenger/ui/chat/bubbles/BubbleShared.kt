package com.alphamessenger.ui.chat.bubbles

import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Media helpers and caches shared by several bubble files
 * (`internal`, not `private`, so each bubble file can use them).
 */

/** Decoded base64 video/photo posters — keeps the instant preview from being
 *  re-decoded on the UI thread every time a bubble scrolls back into view. */
internal val PosterBitmapCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(64)

/** Decoded video poster frames keyed by decrypted path. */
internal val VideoThumbCache = android.util.LruCache<String, android.graphics.Bitmap>(12)

/** Decoded photo bitmaps for image bubbles, keyed by local path / ref. */
internal val MediaImageCache = android.util.LruCache<String, android.graphics.Bitmap>(48)

/** Document/file poster thumbnails — prevents re-decoding on scroll. */
internal val DocumentThumbCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48)
/** Files that gave no thumbnail: not decoded again each time the bubble scrolls back in. */
internal val DocumentThumbFailed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

// --- Formatting ---

/** Human-readable byte size: "1.4 MB" / "820 KB". */
internal fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/** Decode the base64 JPEG poster carried in a video message → ImageBitmap. */
internal fun decodePosterB64(b64: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
    com.alphamessenger.util.BoundedBitmap.decode(bytes)?.asImageBitmap()
}.getOrNull()

/** Extract the first frame of a video as a poster bitmap (cached by path). */
internal suspend fun extractVideoThumb(path: String): android.graphics.Bitmap? {
    VideoThumbCache[path]?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            com.alphamessenger.data.MediaVault.setSource(r, path)
            val frame = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release()
            frame?.also { VideoThumbCache.put(path, it) }
        }.getOrNull()
    }
}

/** Read a media file's duration in milliseconds (heavy native I/O — call off UI). */
internal fun audioDurationMs(path: String): Int = runCatching {
    val r = android.media.MediaMetadataRetriever()
    com.alphamessenger.data.MediaVault.setSource(r, path)
    val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
    r.release(); d
}.getOrDefault(0)

/**
 * The native size of a message's picture, known BEFORE any pixels are decoded:
 * the poster's header (bounds only — no pixel decode), else the size the message
 * carries. Cached by message id. A bubble sized from this takes its final size
 * in its first frame, so nothing around it moves when the picture arrives.
 */
internal object MediaDims {
    private val cache = android.util.LruCache<String, Long>(4096)
    /** Base64 chars read for the header (a multiple of 4 → whole bytes). */
    private const val HEAD_CHARS = 4096

    fun of(id: String, posterB64: String?, width: Int?, height: Int?): Pair<Int, Int>? {
        cache.get(id)?.let { return (it shr 32).toInt() to (it and 0xffffffffL).toInt() }
        val fromPoster = posterB64?.takeIf { it.isNotBlank() }?.let { b64 ->
            // A JPEG states its size in its first bytes: decode only the head of the
            // base64 (a few KB), not the whole poster — this runs as a bubble scrolls in.
            fun bounds(text: String): Pair<Int, Int>? = runCatching {
                val bytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
            }.getOrNull()
            val clean = b64.filterNot { it == '\n' || it == '\r' }
            (if (clean.length > HEAD_CHARS) bounds(clean.substring(0, HEAD_CHARS)) else null) ?: bounds(clean)
        }
        val w = width ?: 0
        val h = height ?: 0
        val dims = fromPoster ?: if (w > 0 && h > 0) w to h else null
        dims?.let { cache.put(id, (it.first.toLong() shl 32) or it.second.toLong()) }
        return dims
    }
}

/** Decoded small pictures (album tiles, link previews) by key, bounded by bytes:
 *  scrolling back shows them at once instead of decoding again. */
internal val ThumbBitmapCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
}
