package com.alphamessenger.ui.media.send

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.media.editor.EditorTransform
import com.alphamessenger.ui.media.editor.VideoTrimmer
import com.alphamessenger.ui.media.export.ExportPlan
import com.alphamessenger.ui.media.export.MediaEditSpec
import com.alphamessenger.ui.media.export.MediaExportPlanner
import com.alphamessenger.ui.media.export.MediaExportPolicy
import com.alphamessenger.ui.media.export.MediaTrace
import com.alphamessenger.ui.media.export.SourceMetadata
import com.alphamessenger.ui.media.export.SourceProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Produces the file that actually goes to the recipient. ONE place.
 *
 * Split production (editor applies edits, send recompresses separately)
 * would mean two passes over one image and two rule sets that must match
 * with nowhere to verify it. Here edits and compression apply to a single
 * bitmap, in the order described by [EditorTransform] — the same one the
 * user sees on screen.
 *
 * Publishing is atomic: work goes into `<file>.part` and only a validated
 * result is renamed to the target path, so the target's existence proves
 * it is ready; no killed process leaves half a video behind.
 */
object MediaOutputFactory {

    private const val TAG = "AlphaSendQueue"

    /**
     * Produce the finished file at [item.outputPath].
     *
     * Returns the file or null. A repeat call with the same edit fingerprint
     * does NOT redo the work: the finished file is already in place and
     * validated.
     */
    suspend fun produce(
        context: Context,
        item: MediaSendItem,
        maxEdge: Int,
        quality: Int,
        sendId: String = "",
        policy: MediaExportPolicy = MediaExportPolicy(),
        /** A re-encode: what the result should weigh (calibrated), told before it starts. */
        onEstimate: (Long) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val target = File(item.outputPath)
        val spec = MediaEditSpec.of(item.draft, maxEdge, quality)
        val meta = if (item.isVideo) {
            SourceProbe.read(context, item.draft.uri)
                // Duration from the draft item is more precise: it was measured
                // on receive, while some container tracks say nothing about it.
                .let { if (it.durationMs <= 0L) it.copy(durationMs = item.draft.durationMs) else it }
        } else {
            SourceMetadata(kind = SourceMetadata.Kind.IMAGE)
        }
        // The single decision point. Everything after is execution.
        val plan = MediaExportPlanner.plan(meta, spec, policy)
        MediaTrace.event(
            sendId.ifBlank { "-" }, item.index, MediaTrace.PLAN,
            "route" to plan.route, "reason" to plan.reason,
            "source" to meta.describe(),
            "profile" to (plan.profile?.describe() ?: "-"),
            "bitrate" to plan.videoBitrate.takeIf { it > 0 },
        )

        // What we EXPECT from the result comes from the plan, not re-derived.
        val expect = expectationFrom(plan, meta, spec, item.isVideo)
        if (validate(target, item.isVideo, expect)) {
            android.util.Log.i(TAG, "item ${item.index}: the output exists, not redone")
            onProgress(100)
            return@withContext target
        }
        // Leftovers of older edit revisions: the file name carries the
        // fingerprint, so anything else in the item directory is stale.
        dropOtherRevisions(target)

        val part = File(target.parentFile, "${target.name}.part")
        runCatching { part.delete() }

        MediaTrace.event(sendId.ifBlank { "-" }, item.index, MediaTrace.PROCESS_START, "route" to plan.route)
        val produced = runCatching {
            if (item.isVideo) produceVideo(context, item.draft, plan, meta, part, onProgress, onEstimate)
            else produceImage(context, item.draft, spec, part)
        }.onFailure { android.util.Log.e(TAG, "item ${item.index}: preparation failed", it) }
            .getOrDefault(false)
        MediaTrace.event(
            sendId.ifBlank { "-" }, item.index, MediaTrace.PROCESS_END,
            "ok" to produced, "bytes" to part.length(),
        )

        if (!produced || !validate(part, item.isVideo, expect)) {
            runCatching { part.delete() }
            return@withContext null
        }
        // Publish: from this moment the file exists ONLY whole.
        if (!part.renameTo(target)) {
            runCatching { part.copyTo(target, overwrite = true) }
            runCatching { part.delete() }
        }
        val done = target.takeIf { validate(it, item.isVideo, expect) }
        MediaTrace.event(
            sendId.ifBlank { "-" }, item.index, MediaTrace.VALIDATION_END,
            "ok" to (done != null), "bytes" to target.length(),
        )
        done
    }

    /** Whether producing [item] re-encodes its video: a long, heavy job, not a copy or a rewrap. */
    suspend fun reencodes(
        context: Context,
        item: MediaSendItem,
        maxEdge: Int,
        quality: Int,
        policy: MediaExportPolicy,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!item.isVideo) return@withContext false
        val meta = SourceProbe.read(context, item.draft.uri)
            .let { if (it.durationMs <= 0L) it.copy(durationMs = item.draft.durationMs) else it }
        MediaExportPlanner.plan(meta, MediaEditSpec.of(item.draft, maxEdge, quality), policy).route ==
            com.alphamessenger.ui.media.export.ExportRoute.TRANSCODE
    }

    /** What to expect from the finished file. Empty — nothing to check (photo). */
    data class Expectation(
        val durationMs: Long = 0L,
        /** The result MUST end up in a standard (SDR) range. */
        val requireSdr: Boolean = false,
    )

    /**
     * Expectations are derived FROM THE PLAN, not from re-parsing the source.
     *
     * This matters: exactly what was promised must be checked. A separately
     * computed expectation could diverge from what the library was actually
     * asked for — and validation would fail correct work.
     */
    private fun expectationFrom(
        plan: ExportPlan,
        meta: SourceMetadata,
        spec: MediaEditSpec,
        isVideo: Boolean,
    ): Expectation {
        if (!isVideo) return Expectation()
        val duration = meta.durationMs
        return Expectation(
            durationMs = if (plan.changesContent) spec.lengthMs(duration) else duration,
            requireSdr = plan.toneMapHdrToSdr,
        )
    }

    /**
     * Validation BEFORE upload: the file must not just exist but read as media.
     *
     * Without it a zero-length or truncated file would reach the recipient,
     * and "won't open" would surface on their side — where nothing can be
     * fixed anymore.
     */
    fun validate(file: File?, isVideo: Boolean, expect: Expectation = Expectation()): Boolean {
        if (file == null || !file.exists() || file.length() <= 0L) return false
        return if (isVideo) checkVideo(file, expect) else hasImageBounds(file)
    }

    /**
     * Substantive video check, not just "the file opened".
     *
     * A track proves structure and nothing more. A clip can have a track and
     * still be zero-length, start with a broken timestamp, or stay HDR where
     * an ordinary range was promised — the case that cranked up the
     * recipient's screen brightness. So we check: codec, duration, first
     * timestamp, colour.
     */
    private fun checkVideo(file: File, expect: Expectation): Boolean = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video: android.media.MediaFormat? = null
            var index = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) {
                    video = f; index = i; break
                }
            }
            if (video == null) {
                android.util.Log.e(TAG, "check: no video track in ${file.name}")
                return@runCatching false
            }
            val mime = video.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
            fun opt(k: String) = if (video.containsKey(k)) runCatching { video.getInteger(k) }.getOrDefault(-1) else -1
            val durationMs = (
                if (video.containsKey(android.media.MediaFormat.KEY_DURATION))
                    runCatching { video.getLong(android.media.MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                else 0L
                ) / 1000L
            val transfer = opt(android.media.MediaFormat.KEY_COLOR_TRANSFER)
            val hdrLeft = mime.contains("dolby", ignoreCase = true) ||
                video.containsKey(android.media.MediaFormat.KEY_HDR_STATIC_INFO) ||
                transfer == TRANSFER_HLG || transfer == TRANSFER_PQ
            extractor.selectTrack(index)
            val firstPts = extractor.sampleTime

            android.util.Log.i(
                TAG,
                "check ${file.name}: $mime ${opt(android.media.MediaFormat.KEY_WIDTH)}x" +
                    "${opt(android.media.MediaFormat.KEY_HEIGHT)} duration=$durationMs ms " +
                    "first pts=$firstPts us transfer=$transfer " +
                    "standard=${opt(android.media.MediaFormat.KEY_COLOR_STANDARD)} " +
                    "range=${opt(android.media.MediaFormat.KEY_COLOR_RANGE)} HDR=$hdrLeft " +
                    "size=${file.length()}",
            )

            when {
                !mime.startsWith("video/") -> false
                durationMs <= 0L -> {
                    android.util.Log.e(TAG, "check: zero duration"); false
                }
                firstPts < 0L -> {
                    android.util.Log.e(TAG, "check: no first packet"); false
                }
                // Off by more than a second and more than a quarter of the
                // expected length — not encoder rounding but a wrong trim.
                expect.durationMs > 0 && kotlin.math.abs(durationMs - expect.durationMs) > 1000L &&
                    kotlin.math.abs(durationMs - expect.durationMs) > expect.durationMs / 4 -> {
                    android.util.Log.e(
                        TAG, "check: duration $durationMs ms instead of the expected ${expect.durationMs} ms",
                    )
                    false
                }
                expect.requireSdr && hdrLeft -> {
                    android.util.Log.e(TAG, "check: the result is still HDR although SDR was required")
                    false
                }
                else -> true
            }
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrDefault(false)

    private const val TRANSFER_PQ = 6
    private const val TRANSFER_HLG = 7

    private fun hasImageBounds(file: File): Boolean = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        com.alphamessenger.data.MediaVault.decodeFile(file.absolutePath, o)
        o.outWidth > 0 && o.outHeight > 0
    }.getOrDefault(false)

    // --- Video ---

    private suspend fun produceVideo(
        context: Context,
        draft: MediaDraftItem,
        plan: ExportPlan,
        meta: SourceMetadata,
        part: File,
        onProgress: (Int) -> Unit,
        onEstimate: (Long) -> Unit,
    ): Boolean {
        // VIDEO frame geometry is representable in a V1 plan but never
        // executed: the UI has no such controls for video. If the plan
        // requested it, an unknown path appeared — staying silent would ship
        // an unrotated frame while we believe everything is done.
        if (plan.videoGeometryRequested) {
            android.util.Log.e(
                TAG,
                "the plan needs video frame geometry, not implemented: the result goes without it",
            )
        }
        if (plan.changesContent) {
            val duration = meta.durationMs.takeIf { it > 0L } ?: draft.durationMs
            val estimate = if (plan.isTranscode) VideoTrimmer.rawEstimate(context, draft.uri, plan, meta) else 0L
            if (estimate > 0L) onEstimate((estimate * com.alphamessenger.ui.media.export.MediaQuality.calibration).toLong())
            val ok = VideoTrimmer.execute(context, draft.uri, plan, part, duration, onProgress) != null
            if (ok && plan.isTranscode) com.alphamessenger.ui.media.export.MediaQuality.learn(estimate, part.length())
            return ok
        }
        // REUSE + COPY_TO_OWNED_STORAGE. Content is unchanged byte for byte,
        // but an OWN file is required: upload reads a path, while the source
        // is a foreign `content://` whose permission may be revoked tomorrow.
        // Copying does not turn reuse into processing — different plan axes,
        // see Materialization.
        onProgress(0)
        val copied = runCatching {
            context.contentResolver.openInputStream(draft.uri)?.use { input ->
                part.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            } != null
        }.getOrDefault(false)
        onProgress(100)
        return copied
    }

    // --- Photo ---

    /**
     * One pass: rotate → flip → crop → downscale → compress.
     *
     * The order is not re-chosen here — it comes from [EditorTransform], the
     * same contract the viewer and editor render by. That is why the frame
     * on screen and the frame the recipient gets match.
     */
    private fun produceImage(
        context: Context,
        draft: MediaDraftItem,
        spec: MediaEditSpec,
        part: File,
    ): Boolean {
        val drawn = renderImage(context, draft, spec.photoMaxEdge) ?: return false
        part.parentFile?.mkdirs()
        val ok = runCatching {
            part.outputStream().use { drawn.compress(Bitmap.CompressFormat.JPEG, spec.photoQuality, it) }
            true
        }.getOrDefault(false)
        drawn.recycle()
        return ok
    }

    /**
     * The photo exactly as it is sent: orientation, the user's turn and flip,
     * crop, size within [maxEdge], then the drawing. The editor shows the
     * cropper this same picture.
     */
    fun renderImage(context: Context, draft: MediaDraftItem, maxEdge: Int): Bitmap? {
        val transform = EditorTransform.of(draft)
        val crop = transform.crop
        // Downscale with headroom for the crop: if a quarter of the frame is
        // cut out, the source must be four times larger for the final maxEdge
        // pixels — otherwise the cropped photo would go out blurry.
        val cropShare = minOf(
            (crop.right - crop.left).coerceAtLeast(0.01f),
            (crop.bottom - crop.top).coerceAtLeast(0.01f),
        )
        val decodeEdge = (maxEdge / cropShare).toInt().coerceAtMost(MAX_DECODE_EDGE)
        val source = decodeSampled(context, draft.uri, decodeEdge) ?: return null

        val matrix = Matrix().apply {
            if (transform.exifRotationDegrees % 360 != 0) postRotate(transform.exifRotationDegrees.toFloat())
            if (transform.exifFlip and MediaDraftItem.FLIP_HORIZONTAL != 0) postScale(-1f, 1f)
            if (transform.exifFlip and MediaDraftItem.FLIP_VERTICAL != 0) postScale(1f, -1f)
            if (transform.rotationDegrees % 360 != 0) postRotate(transform.rotationDegrees.toFloat())
            if (transform.flipHorizontal) postScale(-1f, 1f)
            if (transform.flipVertical) postScale(1f, -1f)
        }
        val oriented =
            if (matrix.isIdentity) source
            else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                .also { if (it != source) source.recycle() }

        // Crop AFTER rotation: the fractions are relative to the frame the user saw.
        val cropped = if (!transform.hasCrop) oriented else {
            val left = (crop.left * oriented.width).toInt().coerceIn(0, oriented.width - 1)
            val top = (crop.top * oriented.height).toInt().coerceIn(0, oriented.height - 1)
            val right = (crop.right * oriented.width).toInt().coerceIn(left + 1, oriented.width)
            val bottom = (crop.bottom * oriented.height).toInt().coerceIn(top + 1, oriented.height)
            Bitmap.createBitmap(oriented, left, top, right - left, bottom - top)
                .also { if (it != oriented) oriented.recycle() }
        }

        val scale = minOf(1f, maxEdge.toFloat() / maxOf(cropped.width, cropped.height).coerceAtLeast(1))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).toInt().coerceAtLeast(1),
                (cropped.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != cropped) cropped.recycle() }
        } else cropped

        // The drawing goes on last, over the frame exactly as it will be sent.
        return com.alphamessenger.ui.media.editor.MarkupRenderer.bake(scaled, draft.markup)
    }

    /** Sampled decode: a full 12-megapixel bitmap is not needed here. */
    private fun decodeSampled(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /**
     * Remove outputs of PAST edits of the same item.
     *
     * The edit fingerprint is in the name, so "stale" is decided by name,
     * not by time: the user may redraw the crop and resend — from that
     * moment the old file is good to nobody.
     */
    private fun dropOtherRevisions(target: File) {
        val dir = target.parentFile ?: return
        val prefix = target.name.substringBefore(".r")
        dir.listFiles()?.forEach { f ->
            if (f.name != target.name && f.name.startsWith("$prefix.r")) runCatching { f.delete() }
        }
    }

    /** Sensible decode ceiling: a heavy crop must not pull in a 100-megapixel bitmap. */
    const val MAX_DECODE_EDGE = 4096
}
