package com.alphamessenger.ui.media.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SOURCE properties — what the planner must know before deciding.
 *
 * Read ONCE and handed to everyone: planner, size estimate, result
 * validation. (When "is this HDR" was determined separately in preparation
 * and in size counting, that meant two passes over the file and two chances
 * to disagree.)
 *
 * Only FACTS about the file. Not a single decision: decisions belong to
 * [MediaExportPlanner], and that split is the main reason this class exists.
 */
data class SourceMetadata(
    val kind: Kind = Kind.VIDEO,
    val hdr: Boolean = false,
    val videoMime: String = "",
    val audioMime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Int = 30,
    val hasAudio: Boolean = false,
    val audioBitrate: Int = 0,
    val audioChannels: Int = 0,
    /** MediaFormat values: 6 = PQ, 7 = HLG. -1 — not specified. */
    val transfer: Int = -1,
    val standard: Int = -1,
    val range: Int = -1,
    val rotationDegrees: Int = 0,
    val fileBytes: Long = 0L,
    val durationMs: Long = 0L,
) {
    enum class Kind { IMAGE, VIDEO }

    /** DISPLAY dimensions: a quarter turn swaps the sides. */
    val displayWidth: Int get() = if (rotationDegrees % 180 != 0) height else width
    val displayHeight: Int get() = if (rotationDegrees % 180 != 0) width else height

    /**
     * VIDEO-track bitrate, bits/s.
     *
     * Computed from the file, not taken from the header: `KEY_BIT_RATE` in
     * mp4 tracks is more often absent than present, and relying on it means
     * getting zero every other time.
     */
    val videoBitrate: Int
        get() {
            if (durationMs <= 0L || fileBytes <= 0L) return 0
            val overall = (fileBytes * 8.0 * 1000.0 / durationMs).toLong()
            val audio = if (hasAudio) audioBitrate.coerceAtLeast(FALLBACK_AUDIO_BPS).toLong() else 0L
            return (overall - audio).coerceAtLeast(0L).toInt()
        }

    /** Short string for the trace. No file name, no URI — properties only. */
    fun describe(): String =
        "$kind $videoMime ${width}x$height@$frameRate rot=$rotationDegrees hdr=$hdr " +
            "transfer=$transfer standard=$standard range=$range " +
            "audio=${if (hasAudio) audioMime else "none"} ${durationMs}ms ${fileBytes}B"

    companion object {
        const val FALLBACK_AUDIO_BPS = 128_000
        const val TRANSFER_PQ = 6
        const val TRANSFER_HLG = 7
    }
}

/** Reads source properties. The single parser for the whole send path. */
object SourceProbe {

    suspend fun read(context: Context, uri: Uri): SourceMetadata = withContext(Dispatchers.IO) {
        runCatching {
            val extractor = MediaExtractor()
            try {
                context.contentResolver.openFileDescriptor(uri, "r").use { fd ->
                    if (fd == null) return@runCatching SourceMetadata()
                    extractor.setDataSource(fd.fileDescriptor)
                    var result = SourceMetadata(fileBytes = fd.statSize)
                    for (i in 0 until extractor.trackCount) {
                        val f = extractor.getTrackFormat(i)
                        val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                        fun int(k: String, fallback: Int) =
                            if (f.containsKey(k)) runCatching { f.getInteger(k) }.getOrDefault(fallback) else fallback
                        fun long(k: String, fallback: Long) =
                            if (f.containsKey(k)) runCatching { f.getLong(k) }.getOrDefault(fallback) else fallback
                        when {
                            mime.startsWith("video/") -> {
                                val transfer = int(MediaFormat.KEY_COLOR_TRANSFER, -1)
                                result = result.copy(
                                    // Three independent HDR signs: stream
                                    // type (Dolby Vision), static metadata and
                                    // the transfer characteristic. Any one suffices.
                                    hdr = mime.contains("dolby", ignoreCase = true) ||
                                        f.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) ||
                                        transfer == SourceMetadata.TRANSFER_PQ ||
                                        transfer == SourceMetadata.TRANSFER_HLG,
                                    videoMime = mime,
                                    width = int(MediaFormat.KEY_WIDTH, 0),
                                    height = int(MediaFormat.KEY_HEIGHT, 0),
                                    frameRate = int(MediaFormat.KEY_FRAME_RATE, 30),
                                    transfer = transfer,
                                    standard = int(MediaFormat.KEY_COLOR_STANDARD, -1),
                                    range = int(MediaFormat.KEY_COLOR_RANGE, -1),
                                    rotationDegrees = int(KEY_ROTATION, 0),
                                    durationMs = long(MediaFormat.KEY_DURATION, 0L) / 1000L,
                                )
                            }
                            mime.startsWith("audio/") -> result = result.copy(
                                hasAudio = true,
                                audioMime = mime,
                                audioBitrate = int(MediaFormat.KEY_BIT_RATE, SourceMetadata.FALLBACK_AUDIO_BPS),
                                audioChannels = int(MediaFormat.KEY_CHANNEL_COUNT, 2),
                            )
                        }
                    }
                    result
                }
            } finally {
                runCatching { extractor.release() }
            }
        }.getOrDefault(SourceMetadata())
    }

    /** `MediaFormat.KEY_ROTATION` — API 23+, the constant as a string for compatibility. */
    private const val KEY_ROTATION = "rotation-degrees"
}
