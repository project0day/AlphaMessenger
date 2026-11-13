package com.alphamessenger.ui.media.export

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "SD / HD" of sent photos and videos — one choice, remembered.
 *
 *  - SD (default): videos larger than 720p or ~2.5 Mbit/s are re-encoded to
 *    the [MediaExportProfile.V1_ECONOMY] profile; photos 1600 px, q80;
 *  - HD: videos go as they are (unless an edit needs re-encoding); photos
 *    2560 px, q90.
 *
 * Also keeps how this phone's encoder really lands against the bitrate it is
 * asked for ([calibration]): hardware encoders often deliver less than asked,
 * so a "bitrate × length" estimate overshoots. Every real re-encode updates it.
 */
object MediaQuality {
    private const val PREFS = "media_quality"
    var hd by mutableStateOf(false)
        private set
    private var calib = 1.0
    private var loaded = false
    private var appCtx: Context? = null

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hd = p.getBoolean("hd", false)
        videoEdge = p.getInt("video_edge", if (hd) ORIGINAL else 1280)
        calib = p.getFloat("calib", 1f).toDouble()
    }

    fun setHd(ctx: Context, v: Boolean) {
        load(ctx)
        hd = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hd", v).apply()
    }

    fun photoEdge(hd: Boolean) = if (hd) 2560 else MediaEditSpec.DEFAULT_PHOTO_MAX_EDGE
    fun photoQuality(hd: Boolean) = if (hd) 90 else MediaEditSpec.DEFAULT_PHOTO_QUALITY

    /** The send policy for [hd] (photos; a video takes [videoPolicy]). */
    fun policy(hd: Boolean) = if (hd) MediaExportPolicy()
    else MediaExportPolicy(profile = MediaExportProfile.V1_ECONOMY, shrinkToProfile = true)

    // --- Video quality: chosen as a resolution, the way a viewer thinks of it ---

    /** A video's quality: the long edge it goes out with, or [ORIGINAL]. */
    class VideoQuality(val longEdge: Int, val label: String, val maxBitrate: Int)

    const val ORIGINAL = 0
    val VIDEO_QUALITIES = listOf(
        VideoQuality(426, "240p", 500_000),
        VideoQuality(640, "360p", 900_000),
        VideoQuality(854, "480p", 1_400_000),
        VideoQuality(1280, "720p", 2_500_000),
        VideoQuality(1920, "1080p", 4_500_000),
    )

    /** The long edge chosen for videos (remembered); [ORIGINAL] sends them as they are. */
    var videoEdge by mutableStateOf(1280)
        private set

    fun setVideoEdge(ctx: Context, edge: Int) {
        load(ctx)
        videoEdge = edge
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("video_edge", edge).apply()
    }

    /** Qualities worth offering for a clip whose long edge is [sourceLongEdge]: none above it. */
    fun qualitiesFor(sourceLongEdge: Int): List<VideoQuality> =
        VIDEO_QUALITIES.filter { sourceLongEdge <= 0 || it.longEdge < sourceLongEdge }

    /** The send policy for a video going out at [edge] ([ORIGINAL]: as it is). */
    fun videoPolicy(edge: Int): MediaExportPolicy {
        val q = VIDEO_QUALITIES.firstOrNull { it.longEdge == edge } ?: return MediaExportPolicy()
        val base = MediaExportProfile.V1_ECONOMY
        return MediaExportPolicy(
            // The floor never above the ceiling: 240p's 500 kbit/s under the 600 kbit/s floor
            // made the bitrate range empty, and the whole plan failed (no size, no re-encode).
            profile = base.copy(
                maxLongEdge = q.longEdge, maxVideoBitrate = q.maxBitrate,
                minVideoBitrate = minOf(base.minVideoBitrate, q.maxBitrate * 6 / 10),
            ),
            shrinkToProfile = true,
        )
    }

    /** How the encoder's real output compares with the bitrate asked (1.0 = exactly). */
    val calibration: Double get() = calib

    /** A re-encode finished: [estimated] by bitrate, [actual] on disk. */
    fun learn(estimated: Long, actual: Long) {
        if (estimated <= 0L || actual <= 0L) return
        val ratio = (actual.toDouble() / estimated).coerceIn(0.25, 1.6)
        calib = if (calib == 1.0) ratio else calib * 0.6 + ratio * 0.4
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putFloat("calib", calib.toFloat())?.apply()
    }
}
