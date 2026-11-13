package com.alphamessenger.ui.chat

/**
 * THE single source of truth for album item metadata — extracted ONCE on the
 * sender side, serialized into the album itemsJson, and deserialized by the
 * receiver.
 *
 * ## Reliability rules (CRITICAL)
 *  1. **Metadata (duration, width, height) MUST be extracted even if the
 *     poster fails.** A broken poster is a cosmetic issue; a missing duration
 *     badge is a UX regression. → metadata and poster live in SEPARATE
 *     try/catch blocks, and the poster uses `catch (Throwable)` so OOM /
 *     native decoder crashes never kill the duration.
 *  2. **`MediaMetadataRetriever` is always released in `finally`.** Leaked
 *     native instances accumulate and eventually cause the 2nd/3rd video in a
 *     batch to fail.
 *  3. **Images do NOT go through `MediaMetadataRetriever`** — it is ~5× slower
 *     than `BitmapFactory` for JPEG/PNG. Use [extractImage] instead.
 *
 * ## Architecture
 *  - SENDER: `extract(path, "video")` / `extractImage(path)` → [AlbumItemMetadata]
 *    → itemsJson → GiftWrap
 *  - RECEIVER: itemsJson → [MessageUi.mediaMetadata] → UI reads `durationMs`, etc.
 *  - UI NEVER calls `MediaMetadataRetriever` itself (no duplicate parsing).
 */
data class AlbumItemMetadata(
    /** Video/audio duration in ms. */
    val durationMs: Long = 0L,
    /** Native video width (px). */
    val width: Int = 0,
    /** Native video height (px). */
    val height: Int = 0,
    /** Display rotation in degrees (0/90/180/270). */
    val rotation: Int = 0,
    /** MIME type (e.g. "video/mp4", "image/jpeg"). */
    val mime: String = "",
    /** File size in bytes. */
    val size: Long = 0L,
    /** Codec name (e.g. "h264", "aac"). */
    val codec: String = "",
    /** Base64 JPEG poster (for video — instant preview). */
    val poster: String = "",
    /** True for streaming refs (E2EE chunked playback). */
    val streaming: Boolean = false,
    /** Voice note bars, base64 (see [VoiceWaveform]). */
    val waveform: String = "",
) {
    /** Aspect ratio (width / height) — 0f if unknown. Used for bubble sizing
 * BEFORE the file is downloaded. */
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 0f

    /** Serialize to a JSON object for the album itemsJson manifest. */
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        if (durationMs > 0) put("duration_ms", durationMs)
        if (width > 0) put("width", width)
        if (height > 0) put("height", height)
        if (rotation != 0) put("rotation", rotation)
        if (mime.isNotBlank()) put("mime", mime)
        if (size > 0) put("size", size)
        if (codec.isNotBlank()) put("codec", codec)
        if (poster.isNotBlank()) put("poster_b64", poster)
        if (streaming) put("streaming", true)
        if (waveform.isNotBlank()) put("waveform", waveform)
    }

    companion object {
        private const val TAG = "AlbumItemMetadata"

        /** Deserialize from a JSON object (album item from itemsJson). */
        fun fromJson(json: org.json.JSONObject): AlbumItemMetadata = AlbumItemMetadata(
            durationMs = json.optLong("duration_ms", 0L).takeIf { it > 0 } ?: 0L,
            width = json.optInt("width", 0),
            height = json.optInt("height", 0),
            rotation = json.optInt("rotation", 0),
            mime = json.optString("mime", ""),
            size = json.optLong("size", 0L).takeIf { it > 0 } ?: 0L,
            codec = json.optString("codec", ""),
            poster = json.optString("poster_b64", "").takeIf { it.isNotBlank() } ?: "",
            streaming = json.optBoolean("streaming", false),
            waveform = json.optString("waveform", ""),
        )

        /**
         * Extract ALL metadata from a media file.
         *
         * **Reliability:** metadata (duration, dims) is extracted FIRST in its
         * own try/catch. If it succeeds, the poster is extracted SEPARATELY —
         * a poster failure (OOM, codec unsupported, no keyframe) NEVER discards
         * the duration; the metadata alone is still returned.
         *
         * @param path local file path
         * @param mediaType "video" / "image" / "audio" — controls what to extract
         * @param posterMaxEdge max edge for the poster thumbnail (video only)
         */
        fun extract(path: String, mediaType: String, posterMaxEdge: Int = 320): AlbumItemMetadata? {
            // Step 1: metadata (fast, reliable). MUST succeed — this carries
            // the duration badge. Uses `catch (Throwable)` so even OOM in the
            // native retriever is caught instead of crashing the coroutine.
            val meta = extractMetadataOnly(path) ?: return null

            // Step 2: poster (slow, may fail). Video only. Never breaks
            // duration — if it throws, we just return `meta` without a poster.
            if (mediaType != "video") return meta
            val poster = try {
                extractVideoPoster(path, posterMaxEdge)
            } catch (e: Throwable) {
                null
            }
            return if (!poster.isNullOrBlank()) meta.copy(poster = poster) else meta
        }

        /**
         * Fast, reliable metadata-only extraction via ONE [MediaMetadataRetriever]
         * pass. NO `getFrameAtTime` (that's the slow / crash-prone part).
         * `release()` is in `finally` so native resources never leak across a
         * batch of videos.
         */
        private fun extractMetadataOnly(path: String): AlbumItemMetadata? {
            var r: android.media.MediaMetadataRetriever? = null
            try {
                r = android.media.MediaMetadataRetriever()
                com.alphamessenger.data.MediaVault.setSource(r, path)
                val rotation = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                )?.toIntOrNull() ?: 0
                val codedWidth = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull() ?: 0
                val codedHeight = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull() ?: 0
                // Width/height are stored AS SEEN, not as coded: a file may be
                // coded landscape and rotated only by metadata, in which case the
                // coded dimensions do not describe what a person sees. Consumers
                // (bubble aspect ratio, frame flight into the viewer) must NOT
                // apply rotation — it is already folded in here, in one place.
                // The raw rotation value is kept below for anyone who needs the
                // fact itself.
                val quarterTurn = ((rotation % 360) + 360) % 360 % 180 != 0
                return AlbumItemMetadata(
                    durationMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    mime = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "",
                    size = java.io.File(path).length(),
                    rotation = rotation,
                    width = if (quarterTurn) codedHeight else codedWidth,
                    height = if (quarterTurn) codedWidth else codedHeight,
                )
            } catch (e: Throwable) {
                return null
            } finally {
                try { r?.release() } catch (_: Throwable) { /* ignore */ }
            }
        }

        /**
         * Extract a video poster (base64 JPEG) at t=0. SLOW and may throw —
         * always called inside its own try/catch by [extract]. Uses
         * [OPTION_CLOSEST_SYNC] (fastest reliable option) and downscales before
         * JPEG-compressing to bound memory. All bitmaps + the retriever are
         * released in `finally`.
         */
        private fun extractVideoPoster(path: String, maxEdge: Int): String? {
            var r: android.media.MediaMetadataRetriever? = null
            var frame: android.graphics.Bitmap? = null
            var scaled: android.graphics.Bitmap? = null
            try {
                r = android.media.MediaMetadataRetriever()
                com.alphamessenger.data.MediaVault.setSource(r, path)
                frame = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
                val maxDim = maxOf(frame.width, frame.height).coerceAtLeast(1)
                val scale = minOf(1f, maxEdge.toFloat() / maxDim)
                scaled = if (scale < 1f)
                    android.graphics.Bitmap.createScaledBitmap(
                        frame,
                        (frame.width * scale).toInt().coerceAtLeast(1),
                        (frame.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                else frame
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } finally {
                if (scaled != null && scaled !== frame) scaled.recycle()
                frame?.recycle()
                try { r?.release() } catch (_: Throwable) { /* ignore */ }
            }
        }

        /**
         * Fast image-only extraction via [android.graphics.BitmapFactory].
         * Skips [MediaMetadataRetriever] entirely (it is ~5× slower for JPEG/PNG
         * and unnecessary — images have no duration). Reads dims via
         * `inJustDecodeBounds` (zero decode), then builds a downscaled JPEG
         * poster in one pass. `catch (Throwable)` so a corrupt image never
         * breaks the album.
         *
         * @param posterMaxEdge max edge for the poster thumbnail
         */
        fun extractImage(path: String, posterMaxEdge: Int = 320): AlbumItemMetadata? {
            try {
                // Step 1: dims (no decode — just header parse).
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                com.alphamessenger.data.MediaVault.decodeFile(path, bounds)
                val width = bounds.outWidth.coerceAtLeast(0)
                val height = bounds.outHeight.coerceAtLeast(0)
                val mime = bounds.outMimeType ?: ""
                val size = java.io.File(path).length()

                // Step 2: poster (downscaled thumbnail). Separate try so a
                // decode failure still returns width/height (no layout jumps).
                val poster = try {
                    decodeImagePoster(path, posterMaxEdge)
                } catch (e: Throwable) {
                    null
                }
                return AlbumItemMetadata(
                    width = width,
                    height = height,
                    mime = mime,
                    size = size,
                    poster = poster ?: "",
                )
            } catch (e: Throwable) {
                return null
            }
        }

        /** Decode + downscale an image to a base64 JPEG poster. */
        private fun decodeImagePoster(path: String, maxEdge: Int): String? {
            // First pass: read dims (cheap).
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            com.alphamessenger.data.MediaVault.decodeFile(path, bounds)
            val srcW = bounds.outWidth.coerceAtLeast(1)
            val srcH = bounds.outHeight.coerceAtLeast(1)
            // inSampleSize = power-of-two downscale → much less memory.
            var sample = 1
            while (srcW / sample > maxEdge && srcH / sample > maxEdge) sample *= 2

            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bm = com.alphamessenger.data.MediaVault.decodeFile(path, opts) ?: return null
            try {
                // Fine-tune to exactly maxEdge (inSampleSize is coarse / power-of-2).
                val maxDim = maxOf(bm.width, bm.height).coerceAtLeast(1)
                val scale = minOf(1f, maxEdge.toFloat() / maxDim)
                val small = if (scale < 1f)
                    android.graphics.Bitmap.createScaledBitmap(
                        bm,
                        (bm.width * scale).toInt().coerceAtLeast(1),
                        (bm.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                else bm
                try {
                    val out = java.io.ByteArrayOutputStream()
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                    return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                } finally {
                    if (small !== bm) small.recycle()
                }
            } finally {
                bm.recycle()
            }
        }
    }
}
