package com.alphamessenger.ui.stories

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * A story video is watched once, on a phone, for a minute at most — so it is
 * sent as messengers usually do stories do: at most 1280 px on the long side,
 * H.264 ~2.5 Mbit/s, AAC, the first 60 s. A 100 MB clip becomes ~15 MB and
 * opens for the viewer in a fraction of the time. Small clips go as they are.
 */
@OptIn(UnstableApi::class)
object StoryVideoCompressor {
    private const val LONG_EDGE = 1280
    private const val BITRATE = 2_500_000
    private const val MAX_MS = 60_000L

    /** The file to upload: [src] itself when it is already light, else a compressed copy. */
    suspend fun prepare(ctx: Context, src: File): File {
        val meta = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(src.absolutePath)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    Meta(if (rot % 180 != 0) h else w, if (rot % 180 != 0) w else h, d, br)
                } finally { r.release() }
            }.getOrNull()
        } ?: return src
        val light = maxOf(meta.w, meta.h) <= LONG_EDGE && meta.bitrate in 1..(BITRATE * 3 / 2) && meta.durationMs <= MAX_MS
        if (light) return src

        val scale = (LONG_EDGE.toFloat() / maxOf(meta.w, meta.h)).coerceAtMost(1f)
        val outW = ((meta.w * scale).toInt() / 2) * 2
        val outH = ((meta.h * scale).toInt() / 2) * 2
        val dest = File(src.parentFile, "story_" + src.nameWithoutExtension + ".mp4")
        dest.delete()
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val item = MediaItem.Builder().setUri(Uri.fromFile(src))
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(minOf(meta.durationMs, MAX_MS).coerceAtLeast(1)).build())
                    .build()
                val edited = EditedMediaItem.Builder(item)
                    .setEffects(Effects(emptyList(), listOf(
                        androidx.media3.effect.Presentation.createForWidthAndHeight(outW, outH, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT),
                    )))
                    .build()
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
                            .build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) { if (cont.isActive) cont.resume(true) }
                        override fun onError(composition: Composition, result: ExportResult, e: ExportException) { if (cont.isActive) cont.resume(false) }
                    })
                    .build()
                transformer.start(edited, dest.absolutePath)
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
            }
        }
        return if (ok && dest.length() > 0) dest else src
    }

    private data class Meta(val w: Int, val h: Int, val durationMs: Long, val bitrate: Long)
}
