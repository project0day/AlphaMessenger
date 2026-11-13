package com.alphamessenger.ui.media.send

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import com.alphamessenger.ui.chat.AlbumItemMetadata
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.media.editor.EditorTransform
import com.alphamessenger.ui.media.editor.VideoTrim

/**
 * Attachment preview and dimensions — BEFORE the finished file exists.
 *
 * Separate path on purpose: the bubble must appear the instant "send" is
 * pressed, while the file is not ready yet — waiting for it would bring back
 * the very lag this pipeline exists to remove. The poster is taken from the
 * SOURCE in tens of milliseconds and dimensions are computed arithmetically
 * from the edits, so the bubble has the right picture and height from the
 * first frame.
 *
 * The video frame is taken at [MediaDraftItem.trimStartMs] — the start of the
 * selected range, not of the file: the intro was trimmed precisely because
 * it should not be on the cover.
 */
internal object MediaSendPreview {

    private const val POSTER_EDGE = 320
    private const val POSTER_QUALITY = 70

    /** Poster as base64 (JPEG ≤320px) or null. A missing poster never breaks the send. */
    fun poster(context: Context, draft: MediaDraftItem): String? =
        runCatching {
            val bitmap = if (draft.isVideo) videoFrame(context, draft) else imageFrame(context, draft)
            bitmap?.let { bm ->
                val out = java.io.ByteArrayOutputStream()
                bm.compress(Bitmap.CompressFormat.JPEG, POSTER_QUALITY, out)
                bm.recycle()
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            }
        }.getOrNull()

    /**
     * Metadata for the manifest: duration of the selected range and the FRAME
     * SIZE the edits will produce.
     *
     * Computed, not measured — there is nothing to measure yet. The bubble
     * needs the size for its height, so pixel accuracy matters less than the
     * right aspect ratio — otherwise the tile lands as a square first and
     * then jumps.
     */
    fun metadata(context: Context, draft: MediaDraftItem, poster: String?): AlbumItemMetadata =
        if (draft.isVideo) videoMetadata(context, draft, poster)
        else imageMetadata(context, draft, poster)

    // --- Video ---

    private fun videoFrame(context: Context, draft: MediaDraftItem): Bitmap? {
        // The gallery's own thumbnail is cached by the system and comes at once; opening a
        // gigabyte clip with the retriever took ~10 s before the bubble could appear.
        if (draft.trimStartMs <= 0L) galleryThumb(context, draft)?.let { return it }
        var r: MediaMetadataRetriever? = null
        return try {
            r = MediaMetadataRetriever()
            r.setDataSource(context, draft.uri)
            val atUs = draft.trimStartMs.coerceAtLeast(0L) * 1000L
            // getScaledFrameAtTime returns a small frame directly — a full 4K
            // frame is not decoded just for a 320px thumbnail.
            val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(
                    atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, POSTER_EDGE, POSTER_EDGE,
                )
            } else {
                r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::downscale)
            }
            frame ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::downscale)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r?.release() }
        }
    }

    private fun galleryThumb(context: Context, draft: MediaDraftItem): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 29 || draft.uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.loadThumbnail(draft.uri, android.util.Size(POSTER_EDGE, POSTER_EDGE), null)
        }.getOrNull()?.let(::downscale)
    }

    /** (coded width, height) and rotation from the gallery's index, or null if it has none. */
    private fun galleryVideoSize(context: Context, draft: MediaDraftItem): Pair<Pair<Int, Int>, Int>? {
        if (android.os.Build.VERSION.SDK_INT < 29 || draft.uri.scheme != "content") return null
        val cols = arrayOf(
            android.provider.MediaStore.MediaColumns.WIDTH,
            android.provider.MediaStore.MediaColumns.HEIGHT,
            android.provider.MediaStore.MediaColumns.ORIENTATION,
        )
        return runCatching {
            context.contentResolver.query(draft.uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val w = c.getInt(0); val h = c.getInt(1)
                if (w <= 0 || h <= 0) null else (w to h) to (if (c.isNull(2)) 0 else c.getInt(2))
            }
        }.getOrNull()
    }

    private fun videoMetadata(context: Context, draft: MediaDraftItem, poster: String?): AlbumItemMetadata {
        var r: MediaMetadataRetriever? = null
        var coded = 0 to 0
        var rotation = 0
        // The gallery already holds the coded size and rotation (its scanner read them with
        // the same retriever); only a file it does not know is opened here.
        galleryVideoSize(context, draft)?.let { (size, rot) -> coded = size; rotation = rot }
        if (coded.first <= 0 || coded.second <= 0) try {
            r = MediaMetadataRetriever()
            r.setDataSource(context, draft.uri)
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            coded = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) to
                (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0)
        } catch (t: Throwable) {
            // Dimensions unreadable — the bubble will take them from the poster.
        } finally {
            runCatching { r?.release() }
        }
        // Display dimensions, not coded ones: a quarter turn swaps the sides.
        // Storing coded ones would desync the bubble from the player and open
        // rotated videos with the wrong aspect ratio.
        val quarterTurn = ((rotation % 360) + 360) % 360 % 180 != 0
        val trim = VideoTrim.of(draft)
        return AlbumItemMetadata(
            durationMs = trim.lengthMs(draft.durationMs),
            width = if (quarterTurn) coded.second else coded.first,
            height = if (quarterTurn) coded.first else coded.second,
            rotation = rotation,
            mime = draft.mimeType.takeIf { it.startsWith("video") } ?: "video/mp4",
            size = 0L,
            poster = poster.orEmpty(),
        )
    }

    // --- Photo ---

    private fun imageFrame(context: Context, draft: MediaDraftItem): Bitmap? {
        val transform = EditorTransform.of(draft)
        val crop = transform.crop
        val cropShare = minOf(
            (crop.right - crop.left).coerceAtLeast(0.01f),
            (crop.bottom - crop.top).coerceAtLeast(0.01f),
        )
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(draft.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val target = (POSTER_EDGE / cropShare).toInt().coerceAtLeast(POSTER_EDGE)
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = context.contentResolver.openInputStream(draft.uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val matrix = Matrix().apply {
            if (transform.exifRotationDegrees % 360 != 0) postRotate(transform.exifRotationDegrees.toFloat())
            if (transform.exifFlip and MediaDraftItem.FLIP_HORIZONTAL != 0) postScale(-1f, 1f)
            if (transform.exifFlip and MediaDraftItem.FLIP_VERTICAL != 0) postScale(1f, -1f)
            if (transform.rotationDegrees % 360 != 0) postRotate(transform.rotationDegrees.toFloat())
            if (transform.flipHorizontal) postScale(-1f, 1f)
            if (transform.flipVertical) postScale(1f, -1f)
        }
        val oriented = if (matrix.isIdentity) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            .also { if (it != src) src.recycle() }

        val cropped = if (!transform.hasCrop) oriented else {
            val left = (crop.left * oriented.width).toInt().coerceIn(0, oriented.width - 1)
            val top = (crop.top * oriented.height).toInt().coerceIn(0, oriented.height - 1)
            val right = (crop.right * oriented.width).toInt().coerceIn(left + 1, oriented.width)
            val bottom = (crop.bottom * oriented.height).toInt().coerceIn(top + 1, oriented.height)
            Bitmap.createBitmap(oriented, left, top, right - left, bottom - top)
                .also { if (it != oriented) oriented.recycle() }
        }
        // The poster carries the drawing too: it is what the bubble shows first.
        return com.alphamessenger.ui.media.editor.MarkupRenderer.bake(downscale(cropped), draft.markup)
    }

    private fun imageMetadata(context: Context, draft: MediaDraftItem, poster: String?): AlbumItemMetadata {
        val transform = EditorTransform.of(draft)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(draft.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        var w = bounds.outWidth
        var h = bounds.outHeight
        if ((transform.exifRotationDegrees + transform.rotationDegrees) % 180 != 0) {
            val t = w; w = h; h = t
        }
        val crop = transform.crop
        if (transform.hasCrop) {
            w = (w * (crop.right - crop.left)).toInt()
            h = (h * (crop.bottom - crop.top)).toInt()
        }
        return AlbumItemMetadata(
            width = w.coerceAtLeast(0),
            height = h.coerceAtLeast(0),
            mime = "image/jpeg",
            size = 0L,
            poster = poster.orEmpty(),
        )
    }

    private fun downscale(bm: Bitmap): Bitmap {
        val scale = minOf(1f, POSTER_EDGE.toFloat() / maxOf(bm.width, bm.height).coerceAtLeast(1))
        if (scale >= 1f) return bm
        return Bitmap.createScaledBitmap(
            bm,
            (bm.width * scale).toInt().coerceAtLeast(1),
            (bm.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it != bm) bm.recycle() }
    }
}
