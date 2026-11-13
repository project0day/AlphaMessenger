package com.alphamessenger.ui.media

import android.content.Context
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.ByteBuffer

/**
 * Removes what a photo or video says about where, when and with what it was
 * taken — GPS position, capture date, phone make/model, serial numbers, owner
 * name, software — before it is sent.
 *
 * It works on OUR copy of the attachment (made when it is picked), never on the
 * user's original in the gallery, and the file keeps its name. Photos keep only
 * what is needed to show them correctly (orientation, size); a video is
 * re-packed track by track without re-encoding (fast, no quality loss) with its
 * rotation kept. If something cannot be cleaned (an unusual format) the file is
 * left as it is.
 */
object MetadataScrubber {
    private const val TAG = "MetadataScrubber"
    private const val PREFS = "privacy_media"
    private const val KEY = "strip_metadata"

    /** On by default. Observable for the Privacy screen. */
    var enabled by mutableStateOf(true)
        private set

    fun load(ctx: Context) {
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    private val EXIF_WRITABLE = setOf("jpg", "jpeg", "png", "webp")
    private val VIDEO = setOf("mp4", "m4v", "mov", "3gp")

    /** EXIF tags that identify the place, time, device or person. */
    private val PRIVATE_TAGS: List<String> by lazy {
        val named = listOf(
            "Make", "Model", "Software", "Artist", "Copyright", "ImageDescription", "UserComment",
            "MakerNote", "DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime",
            "OffsetTimeOriginal", "OffsetTimeDigitized", "SubSecTime", "SubSecTimeOriginal",
            "SubSecTimeDigitized", "BodySerialNumber", "CameraOwnerName", "LensMake", "LensModel",
            "LensSerialNumber", "LensSpecification", "ImageUniqueID", "HostComputer", "Xmp",
        )
        // Every GPS tag the platform knows, whatever its version.
        val gps = ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_GPS_") && it.type == String::class.java }
            .mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
        named + gps
    }

    /** Clean [file] in place if it is a photo or video and the setting is on.
     *  [finalName] gives the type when [file] is still a temporary (".part") copy. */
    fun scrubInPlace(ctx: Context, file: File, finalName: String = file.name) {
        if (!enabled) { load(ctx); if (!enabled) return }
        val ext = finalName.substringAfterLast('.', "").lowercase()
        runCatching {
            when (ext) {
                in EXIF_WRITABLE -> scrubExif(file)
                in VIDEO -> remux(file)
            }
        }.onFailure { Log.w(TAG, "not cleaned (${file.name}): ${it.message}") }
    }

    private fun scrubExif(file: File) {
        val exif = ExifInterface(file.absolutePath)
        var changed = false
        for (tag in PRIVATE_TAGS) {
            if (exif.getAttribute(tag) != null) { exif.setAttribute(tag, null); changed = true }
        }
        if (changed) exif.saveAttributes()
    }

    /** Re-pack the tracks into a new container: none of the old metadata boxes come along. */
    private fun remux(file: File) {
        val rotation = MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally { release() }
        }
        val tmp = File(file.parentFile, file.name + ".clean")
        tmp.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            extractor.setDataSource(file.absolutePath)
            val m = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val trackMap = HashMap<Int, Int>()
            var maxSample = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                trackMap[i] = m.addTrack(format)
                extractor.selectTrack(i)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxSample = maxOf(maxSample, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (trackMap.isEmpty()) return
            m.setOrientationHint(rotation)
            m.start()
            val buffer = ByteBuffer.allocate(maxOf(maxSample, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val out = trackMap[extractor.sampleTrackIndex]
                if (out != null) {
                    info.set(
                        0, size, extractor.sampleTime,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    m.writeSampleData(out, buffer, info)
                }
                extractor.advance()
            }
            m.stop()
            ok = true
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
        if (ok && tmp.length() > 0 && tmp.renameTo(file)) return
        tmp.delete()
    }
}
