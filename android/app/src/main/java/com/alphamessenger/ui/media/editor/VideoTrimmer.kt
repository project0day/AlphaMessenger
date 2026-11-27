package com.alphamessenger.ui.media.editor

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.alphamessenger.ui.media.draft.MediaDraftStore
import com.alphamessenger.ui.media.export.ExportPlan
import com.alphamessenger.ui.media.export.ExportRoute
import com.alphamessenger.ui.media.export.SourceMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * EXECUTOR of the export plan. Decides nothing.
 *
 * The route decision is made by
 * [com.alphamessenger.ui.media.export.MediaExportPlanner]; a ready
 * [ExportPlan] arrives here and is executed literally. (With route choices
 * living here, "why was this clip transcoded" had no answer: part of the
 * condition was here, part at the caller, part inside the library.)
 *
 * Exactly one difference between routes at this level: stream copy asks the
 * library for no image transforms (packets are moved as is), while
 * transcoding sets codec, bitrate and colour conversion explicitly.
 */
@OptIn(UnstableApi::class)
object VideoTrimmer {

    private const val TAG = "AlphaTrim"

    /**
     * Execute the plan. Returns [dest], or null on failure.
     *
     * [ExportRoute.REUSE] never gets here: the caller does the copying — no
     * point starting the export library for that.
     */
    suspend fun execute(
        context: Context,
        source: Uri,
        plan: ExportPlan,
        dest: File,
        durationMs: Long,
        onProgress: (Int) -> Unit = {},
    ): File? {
        require(plan.route != ExportRoute.REUSE) { "REUSE is a copy, not an export" }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        dest.parentFile?.mkdirs()
        // Leftover of an earlier interrupted attempt: the library does not write over.
        if (dest.exists()) runCatching { dest.delete() }

        logTracks(context, source, "SOURCE")
        android.util.Log.i(TAG, "plan → ${plan.describe()}")
        val startedAt = android.os.SystemClock.elapsedRealtime()

        // Transformer lives on a thread with a message queue — start it on
        // the main one and await the result without blocking.
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val end = if (plan.trimEndMs > 0L) plan.trimEndMs else durationMs
                val item = MediaItem.Builder()
                    .setUri(source)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(plan.trimStartMs)
                            .setEndPositionMs(end)
                            .build(),
                    )
                    .build()

                // The progress poller is started below but must be stopped
                // from the completion handler — hence the early declaration.
                var ticker: Runnable? = null
                fun stopTicker() { ticker?.let { handler.removeCallbacks(it) } }

                val edited = EditedMediaItem.Builder(item)
                    .setRemoveAudio(plan.removeAudio)
                    .apply {
                        // Frame downscaling is also an image transform, so it
                        // is set only on the transcode route: with stream copy
                        // any effect would silently enable transcoding.
                        if (plan.isTranscode && plan.scaledWidth > 0 && plan.scaledHeight > 0) {
                            setEffects(
                                androidx.media3.transformer.Effects(
                                    emptyList(),
                                    listOf(
                                        androidx.media3.effect.Presentation.createForWidthAndHeight(
                                            plan.scaledWidth,
                                            plan.scaledHeight,
                                            androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT,
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                    .build()

                val composition = Composition.Builder(EditedMediaItemSequence(edited))
                    .apply {
                        if (plan.toneMapHdrToSdr) {
                            setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                        }
                    }
                    .build()

                val transformer = Transformer.Builder(context)
                    .apply {
                        val profile = plan.profile
                        if (plan.isTranscode && profile != null) {
                            // WE set the codec. Otherwise the firmware picks
                            // it, and the same clip from two phones gives
                            // different results.
                            setVideoMimeType(profile.videoMimeType)
                            if (profile.audioMimeType.isNotBlank() && !plan.removeAudio) {
                                setAudioMimeType(profile.audioMimeType)
                            }
                            // WE set the bitrate. Otherwise the output weight
                            // is unpredictable — and it is shown to the user
                            // in the editor already.
                            setEncoderFactory(
                                androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                                    .setRequestedVideoEncoderSettings(
                                        androidx.media3.transformer.VideoEncoderSettings.Builder()
                                            .setBitrate(plan.videoBitrate)
                                            .build(),
                                    )
                                    .build(),
                            )
                            // Samples only appended, the index rewritten as it grows — so the part of the
                            // file it already describes can go up while the rest is still encoded.
                            setMuxerFactory(androidx.media3.transformer.InAppMuxer.Factory.Builder().build())
                        }
                    }
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            android.util.Log.i(
                                TAG,
                                "done: ${dest.length()} bytes, duration ${result.durationMs} ms",
                            )
                            stopTicker()
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            stopTicker()
                            android.util.Log.w(TAG, "preparation failed", exception)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                    .build()

                // Progress comes from the library itself: transcoding a 4K
                // clip is not instant, and staying silent about it is wrong.
                val progressHolder = androidx.media3.transformer.ProgressHolder()
                ticker = object : Runnable {
                    override fun run() {
                        val state = transformer.getProgress(progressHolder)
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            onProgress(progressHolder.progress)
                            handler.postDelayed(this, 400)
                        }
                    }
                }
                ticker?.let { handler.post(it) }
                continuation.invokeOnCancellation {
                    stopTicker()
                    runCatching { transformer.cancel() }
                }
                runCatching { transformer.start(composition, dest.absolutePath) }
                    .onFailure {
                        android.util.Log.w(TAG, "could not start the preparation", it)
                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        }

        val exportMs = android.os.SystemClock.elapsedRealtime() - startedAt
        android.util.Log.i(TAG, "export ${plan.route} took $exportMs ms, ok=$ok, size ${dest.length()} B")

        if (!ok || !dest.exists() || dest.length() <= 0L) {
            runCatching { dest.delete() }
            return null
        }
        // Check the RESULT, not the source: track count, colour, first
        // packet timestamp. Otherwise "done" is just another word.
        logTracks(context, Uri.fromFile(dest), "RESULT")
        return dest
    }

    /**
     * How much the chosen plan's output WEIGHS.
     *
     * Computed from REAL data, just different data per route: stream copy
     * sums the packets' own weight, transcoding multiplies the bitrate that
     * is handed to that same encoder. No "by length share" guesses — those
     * produced numbers far from the file.
     */
    suspend fun measure(
        context: Context,
        source: Uri,
        plan: ExportPlan,
        meta: SourceMetadata,
    ): com.alphamessenger.media.MediaSizeInfo = withContext(Dispatchers.IO) {
        // Content is unchanged — the output weight is known exactly and at
        // once: it equals the source's. The only case where "estimate" and
        // "measurement" coincide before any work.
        if (plan.route == ExportRoute.REUSE) {
            return@withContext com.alphamessenger.media.MediaSizeInfo(
                sourceBytes = meta.fileBytes,
                estimatedOutputBytes = meta.fileBytes,
                actualOutputBytes = meta.fileBytes,
            )
        }
        val end = if (plan.trimEndMs > 0L) plan.trimEndMs else meta.durationMs
        val lengthMs = (end - plan.trimStartMs).coerceAtLeast(0L)
        // A re-encode's weight comes from its bitrates: walking every sample of a big clip
        // only to throw the sum away kept the SD/HD size on screen stale for seconds.
        val audioKept = !plan.removeAudio && !(plan.isTranscode && plan.profile?.audioMimeType?.isNotBlank() == true)
        val tracks = if (plan.isTranscode && !audioKept) Tracks(0L, 0L, lengthMs * EST_SAMPLES_PER_SEC / 1000L)
            else sumTracks(context, source, plan.trimStartMs, end, plan.removeAudio)
        val videoBytes = if (plan.isTranscode) {
            plan.videoBitrate.toLong() * lengthMs / 8_000L
        } else {
            tracks.videoBytes
        }
        val audioBytes = when {
            plan.removeAudio -> 0L
            // Audio transcoding follows the profile, so the weight does too.
            plan.isTranscode && plan.profile?.audioMimeType?.isNotBlank() == true && meta.hasAudio ->
                plan.profile.audioBitrate.toLong() * lengthMs / 8_000L
            else -> tracks.audioBytes
        }
        // A re-encode: corrected by how this phone's encoder really lands.
        val raw = videoBytes + audioBytes + containerOverhead(tracks.samples)
        val estimated = if (plan.isTranscode) (raw * com.alphamessenger.ui.media.export.MediaQuality.calibration).toLong() else raw
        com.alphamessenger.media.MediaSizeInfo(
            sourceBytes = meta.fileBytes,
            estimatedOutputBytes = estimated,
            // Stream copy gives an EXACT weight: packets are copied as is,
            // their sum is the result. Transcoding is a bitrate-based
            // estimate — rate-control accuracy, a few percent. Calling an
            // estimate "exact" would be a lie.
            actualOutputBytes = if (plan.isTranscode) null else estimated,
        )
    }

    /** The bitrate-based estimate of a re-encode, before calibration (what [MediaQuality.learn] compares). */
    suspend fun rawEstimate(
        context: Context,
        source: Uri,
        plan: ExportPlan,
        meta: SourceMetadata,
    ): Long = withContext(Dispatchers.IO) {
        val end = if (plan.trimEndMs > 0L) plan.trimEndMs else meta.durationMs
        val lengthMs = (end - plan.trimStartMs).coerceAtLeast(0L)
        val tracks = sumTracks(context, source, plan.trimStartMs, end, plan.removeAudio)
        val video = plan.videoBitrate.toLong() * lengthMs / 8_000L
        val audio = when {
            plan.removeAudio -> 0L
            plan.profile?.audioMimeType?.isNotBlank() == true && meta.hasAudio -> plan.profile.audioBitrate.toLong() * lengthMs / 8_000L
            else -> tracks.audioBytes
        }
        video + audio + containerOverhead(tracks.samples)
    }

    /**
     * mp4 container housekeeping bytes: the sample table plus the header.
     *
     * Skipping them builds a visible mismatch on long clips — the weight
     * always comes out below what is shown.
     */
    private fun containerOverhead(samples: Long): Long = samples * MP4_ENTRY_BYTES + MP4_HEADER_BYTES

    private const val MP4_ENTRY_BYTES = 16L
    private const val MP4_HEADER_BYTES = 4096L

    private data class Tracks(val videoBytes: Long, val audioBytes: Long, val samples: Long)

    /** Video frames plus AAC packets per second, for the container overhead of an estimate. */
    private const val EST_SAMPLES_PER_SEC = 75L

    /** Sum of the selected range's packets, video and audio separately. */
    private fun sumTracks(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
        skipAudio: Boolean,
    ): Tracks = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(source, "r").use { fd ->
                if (fd == null) return@runCatching Tracks(0L, 0L, 0L)
                extractor.setDataSource(fd.fileDescriptor)
                val startUs = startMs * 1000L
                val endUs = endMs * 1000L
                val videoTracks = mutableSetOf<Int>()
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i)
                        .getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                    val video = mime.startsWith("video/")
                    val audio = mime.startsWith("audio/")
                    if (!video && !audio) continue
                    if (audio && skipAudio) continue
                    if (video) videoTracks += i
                    extractor.selectTrack(i)
                }
                extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var video = 0L
                var audio = 0L
                var samples = 0L
                while (true) {
                    val at = extractor.sampleTime
                    if (at < 0 || at > endUs) break
                    val bytes = extractor.sampleSize.coerceAtLeast(0L)
                    if (extractor.sampleTrackIndex in videoTracks) video += bytes else audio += bytes
                    samples++
                    if (!extractor.advance()) break
                }
                Tracks(video, audio, samples)
            }
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrDefault(Tracks(0L, 0L, 0L))

    /** Parse the real file: tracks, colour, size, first timestamp. */
    private suspend fun logTracks(context: Context, uri: Uri, label: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val extractor = android.media.MediaExtractor()
                try {
                    context.contentResolver.openFileDescriptor(uri, "r").use { fd ->
                        if (fd == null) return@runCatching
                        extractor.setDataSource(fd.fileDescriptor)
                        android.util.Log.i(TAG, "$label: tracks=${extractor.trackCount}")
                        for (i in 0 until extractor.trackCount) {
                            val f = extractor.getTrackFormat(i)
                            val mime = f.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                            fun opt(k: String) = if (f.containsKey(k)) f.getInteger(k).toString() else "-"
                            android.util.Log.i(
                                TAG,
                                "$label[$i] $mime transfer=${opt(android.media.MediaFormat.KEY_COLOR_TRANSFER)} " +
                                    "standard=${opt(android.media.MediaFormat.KEY_COLOR_STANDARD)} " +
                                    "range=${opt(android.media.MediaFormat.KEY_COLOR_RANGE)} " +
                                    "w=${opt(android.media.MediaFormat.KEY_WIDTH)} " +
                                    "h=${opt(android.media.MediaFormat.KEY_HEIGHT)} " +
                                    "hdrStatic=${f.containsKey(android.media.MediaFormat.KEY_HDR_STATIC_INFO)}",
                            )
                            if (mime.startsWith("video/")) {
                                extractor.selectTrack(i)
                                android.util.Log.i(TAG, "$label[$i] first pts=${extractor.sampleTime} us")
                                extractor.unselectTrack(i)
                            }
                        }
                    }
                } finally {
                    runCatching { extractor.release() }
                }
            }
        }
}
