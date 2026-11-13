package com.alphamessenger.ui.media.preview

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.media.MediaSizeFormat
import com.alphamessenger.ui.media.draft.MediaDraftItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the preview tells about an attachment: size, resolution, length. */
private data class MediaFacts(val bytes: Long, val width: Int, val height: Int, val durationMs: Long)

private val factsCache = android.util.LruCache<String, MediaFacts>(64)

/** Read size, resolution and length (the provider's answer first, the file itself when it is silent). */
private suspend fun factsOf(ctx: Context, item: MediaDraftItem): MediaFacts = withContext(Dispatchers.IO) {
    val key = item.uri.toString()
    factsCache.get(key)?.let { return@withContext it }
    val bytes = item.sizeBytes.takeIf { it > 0 } ?: sizeOf(ctx, item.uri)
    var w = 0
    var h = 0
    var dur = item.durationMs
    runCatching {
        if (item.kind == MediaDraftItem.Kind.VIDEO) {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, item.uri)
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val vw = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val vh = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { w = vh; h = vw } else { w = vw; h = vh }
                if (dur <= 0) dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            } finally { r.release() }
        } else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(item.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            w = o.outWidth.coerceAtLeast(0); h = o.outHeight.coerceAtLeast(0)
        }
    }
    MediaFacts(bytes, w, h, dur).also { factsCache.put(key, it) }
}

private fun sizeOf(ctx: Context, uri: Uri): Long = runCatching {
    if (uri.scheme == "file") return@runCatching java.io.File(uri.path ?: "").length()
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    } ?: 0L
}.getOrDefault(0L)


/** What this attachment will weigh when sent with [hd] (the same plan the send runs; a photo really compressed). */
internal suspend fun sentSize(ctx: Context, item: MediaDraftItem, hd: Boolean, videoEdge: Int): Long = withContext(Dispatchers.IO) {
    runCatching {
        val q = com.alphamessenger.ui.media.export.MediaQuality
        if (item.kind == MediaDraftItem.Kind.VIDEO) {
            val meta = com.alphamessenger.ui.media.export.SourceProbe.read(ctx, item.uri)
                .let { if (it.durationMs <= 0L) it.copy(durationMs = item.durationMs) else it }
            val spec = com.alphamessenger.ui.media.export.MediaEditSpec.of(item, q.photoEdge(hd), q.photoQuality(hd))
            val plan = com.alphamessenger.ui.media.export.MediaExportPlanner.plan(meta, spec, q.videoPolicy(videoEdge))
            com.alphamessenger.ui.media.editor.VideoTrimmer.measure(ctx, item.uri, plan, meta).estimatedOutputBytes
        } else if (item.kind == MediaDraftItem.Kind.IMAGE && !item.sendAsFile) {
            val edge = q.photoEdge(hd)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(item.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= edge) sample *= 2
            val bmp = ctx.contentResolver.openInputStream(item.uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching 0L
            val k = minOf(1f, edge.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q.photoQuality(hd), out)
            out.size().toLong()
        } else 0L
    }.getOrDefault(0L)
}

/**
 * The line under the preview's header: this attachment's length (and the
 * trimmed part), resolution and size → what it will weigh when sent, the
 * set's size, and the SD / HD choice (remembered).
 */
@Composable
fun MediaInfoChip(item: MediaDraftItem, all: List<MediaDraftItem>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val q = com.alphamessenger.ui.media.export.MediaQuality
    q.load(ctx)
    val hd = q.hd
    val facts by produceState<MediaFacts?>(null, item.uri) { value = factsOf(ctx, item) }
    val total by produceState(0L, all.map { it.uri }) {
        // A copy: the set grows while this suspends on each file (several files
        // picked at once arrive one by one) — iterating the live list crashed.
        val items = all.toList()
        value = items.sumOf { factsOf(ctx, it).bytes.coerceAtLeast(0) }
    }
    val videoEdge = q.videoEdge
    val sent by produceState(0L, item.uri, item.trimStartMs, item.trimEndMs, item.muted, hd, videoEdge) { value = sentSize(ctx, item, hd, videoEdge) }
    val f = facts ?: return
    val parts = buildList {
        if (item.kind == MediaDraftItem.Kind.VIDEO && f.durationMs > 0) {
            val end = if (item.trimEndMs > 0) item.trimEndMs else f.durationMs
            val trimmed = item.trimStartMs > 0 || end < f.durationMs
            add(if (trimmed) stringResource(R.string.media_info_trimmed, clock(end - item.trimStartMs), clock(f.durationMs)) else clock(f.durationMs))
        }
        if (f.width > 0 && f.height > 0) add("${f.width}×${f.height}")
        if (f.bytes > 0) {
            // "86 MB → ≈ 14 MB" when sending changes the weight noticeably.
            val changes = sent > 0 && kotlin.math.abs(sent - f.bytes) > f.bytes / 20
            add(MediaSizeFormat.short(f.bytes) + if (changes) " → ≈ " + MediaSizeFormat.short(sent) else "")
        }
    }
    val line = parts.joinToString(" · ") +
        if (all.size > 1 && total > 0) "   " + stringResource(R.string.media_info_total, all.size, MediaSizeFormat.short(total)) else ""
    if (line.isBlank()) return
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            line, color = Color.White, fontSize = 12.5.sp, textAlign = TextAlign.Center, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    }
}
