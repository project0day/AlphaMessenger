package com.alphamessenger.ui.chat.media

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.chat.bubbles.DocumentThumbCache
import com.alphamessenger.ui.chat.bubbles.MediaImageCache
import com.alphamessenger.ui.chat.bubbles.PosterBitmapCache
import java.io.File

/**
 * MediaLoader — loads media bytes for the viewer.
 *
 * The viewer never knows where bytes come from (sender's local file, downloaded
 * blob cache, base64 poster) — every path funnels here. No network in the UI:
 * downloads go through [AlphaCoreRepository] (transfer); this layer only calls
 * existing suspend functions.
 */

/**
 * Full-size image: sender's local path or a downloaded blob.
 *
 * Decoding reuses the existing [MediaImageCache] (the same cache photo bubbles
 * use), so album paging never re-decodes a frame and swipes stay smooth. The
 * media framework keeps no cache of its own.
 */
internal suspend fun loadFullBitmap(
    item: MediaItem,
    repository: AlphaCoreRepository?,
): ImageBitmap? = runCatching {
    val local = item.localPath?.takeIf { File(it).exists() }
    val path = local ?: item.refJson?.let { ref ->
        repository?.downloadMediaToCache(ref)
    } ?: return@runCatching null

    MediaImageCache.get(path)?.let { return@runCatching it.asImageBitmap() }
    decodeForViewer(path)?.also { MediaImageCache.put(path, it) }?.asImageBitmap()
}.getOrNull()

/**
 * Target frame size for the viewer, by the long edge.
 *
 * Sized from real zoom usage: worst case ~2.4 source pixels per screen pixel.
 * 2560 px covers that with margin, keeping a frame at ~17 MB instead of the
 * ~124 MB of a 6997×4670 original — texture weight, not decode cost, is what
 * freezes first draw.
 */
private const val VIEWER_MAX_EDGE = 2560

/**
 * Decode straight at the target resolution: `inSampleSize` is applied by the
 * decoder, so a full-size bitmap never exists in memory, even briefly.
 */
private fun decodeForViewer(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    com.alphamessenger.data.MediaVault.decodeFile(path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return com.alphamessenger.data.MediaVault.decodeFile(path)

    // Shrink while the long edge still exceeds the target — a closed-form
    // expression here tends to leave 4K-class frames at full size.
    var sample = 1
    while (longest / sample > VIEWER_MAX_EDGE) sample *= 2
    return com.alphamessenger.data.MediaVault.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Local video file: sender's path, else the full streaming cache by hash. */
internal fun localVideoFile(ctx: Context, item: MediaItem): File? {
    item.localPath?.let { val f = File(it); if (f.exists() && f.length() > 0) return f }
    val hash = com.alphamessenger.data.blobHashOf(item.refJson ?: return null)
    val f = File(File(ctx.cacheDir, "media"), hash)
    return if (f.exists() && f.length() > 0) f else null
}

/** Ensure a local video file exists (downloading if needed). */
internal suspend fun ensureVideoFile(
    ctx: Context,
    item: MediaItem,
    repository: AlphaCoreRepository?,
): File? {
    localVideoFile(ctx, item)?.let { return it }
    val ref = item.refJson ?: return null
    return runCatching { repository?.downloadMediaToCache(ref) }.getOrNull()
        ?.let { File(it) }?.takeIf { it.exists() }
}

/**
 * Filmstrip thumbnail: base64 poster, else a frame from the local file.
 * Stored in the existing [PosterBitmapCache] so the filmstrip never re-decodes
 * frames on scroll or page changes.
 */
internal fun loadThumb(item: MediaItem): ImageBitmap? = runCatching {
    val key = "mv-thumb:${item.id}"
    PosterBitmapCache.get(key)?.let { return@runCatching it }
    val bmp = item.posterB64?.let { decodeB64(it) }
        ?: item.localPath?.takeIf { File(it).exists() }?.let { path ->
            if (item.kind == MediaItem.Kind.VIDEO) videoFrame(path)
            else com.alphamessenger.data.MediaVault.decodeFile(path)?.asImageBitmap()
        }
    bmp?.also { PosterBitmapCache.put(key, it) }
}.getOrNull()

internal fun decodeB64(b64: String): ImageBitmap? = runCatching {
    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    com.alphamessenger.util.BoundedBitmap.decode(bytes)?.asImageBitmap()
}.getOrNull()

private fun videoFrame(path: String): ImageBitmap? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    com.alphamessenger.data.MediaVault.setSource(r, path)
    val b = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    r.release(); b?.asImageBitmap()
}.getOrNull()

/** A frame from already-warm caches: no decoding (used by the transition). */
internal fun cachedFrame(item: MediaItem): ImageBitmap? {
    // Probes the same keys the bubbles store frames under: filmstrip thumb,
    // document card, photo/video (`localMediaPath ?: refJson`).
    PosterBitmapCache.get("mv-thumb:${item.id}")?.let { return it }
    DocumentThumbCache.get(item.id)?.let { return it }
    for (key in listOfNotNull(item.localPath, item.refJson)) {
        MediaImageCache.get(key)?.let { return it.asImageBitmap() }
    }
    return null
}
