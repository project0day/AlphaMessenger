package com.alphamessenger.ui.media.export

/**
 * Output profile — what a video turns into WHEN transcoding is really needed.
 *
 * A separate versioned value for one reason: before it, encoding parameters
 * were whatever the firmware defaulted to — the same clip sent from two
 * phones gave a different codec, bitrate and colour, with nothing to explain
 * to the recipient why theirs would not open.
 *
 * The profile applies ONLY on the [ExportRoute.TRANSCODE] route. Stream copy
 * and reuse are untouched — otherwise "pin the profile" would turn into
 * "transcode everything".
 *
 * [version] is part of the finished file's key, so changing any parameter
 * here automatically invalidates all previously prepared files — forgetting
 * to recompute them is impossible.
 */
data class MediaExportProfile(
    val version: Int,
    val name: String,

    /** Video codec. Explicit, not "whatever the device picks". */
    val videoMimeType: String,
    /** Long-edge cap, pixels. 0 — do not downscale. */
    val maxLongEdge: Int,
    /** Video bitrate ceiling, bits/s. */
    val maxVideoBitrate: Int,
    /** Floor: below it the picture falls apart at any resolution. */
    val minVideoBitrate: Int,
    /** Bits per frame pixel — the ceiling of a "useful" bitrate. */
    val bitsPerPixel: Double,

    /** Audio codec. Empty — audio is carried as is. */
    val audioMimeType: String,
    val audioBitrate: Int,
    val maxAudioChannels: Int,

    /** The result must be in the ordinary BT.709 range. */
    val requireSdrBt709: Boolean,
) {

    /**
     * Bitrate for a specific source.
     *
     * Takes the source video's bitrate — converting to an ordinary range
     * needs no MORE bits than there were — and clamps it into the profile's
     * bounds. The same number is handed to the encoder, so the output weight
     * is computed by multiplication, not guesswork: without such a number the
     * shown size once diverged from the file.
     */
    fun videoBitrateFor(source: SourceMetadata, outSize: Pair<Int, Int>? = null): Int {
        // A downscaled frame needs the bits of ITS pixels, not the source's.
        val pixels = (outSize?.let { it.first.toLong() * it.second } ?: (source.width.toLong() * source.height)).coerceAtLeast(1)
        val useful = (pixels * source.frameRate.coerceIn(1, 120) * bitsPerPixel).toLong()
            .coerceIn(minVideoBitrate.toLong(), maxVideoBitrate.toLong())
        val fromSource = source.videoBitrate.toLong()
        val wanted = if (fromSource > 0L) fromSource else useful
        // The floor guards against quality loss on re-compression, but it
        // must NOT inflate an already light file: a heavily compressed source
        // at 500 kbit/s would otherwise arrive three times heavier than it
        // came. So with a known bitrate the floor is capped at a 2× margin —
        // enough against quality loss, and it does not turn the sender's
        // economy into the recipient's traffic overuse.
        val floor = if (fromSource > 0L) minOf(minVideoBitrate.toLong(), fromSource * 2)
        else minVideoBitrate.toLong()
        return wanted.coerceIn(floor, useful).toInt()
    }

    /** Whether the frame must be downscaled under this profile. */
    fun scaledSize(source: SourceMetadata): Pair<Int, Int>? {
        if (maxLongEdge <= 0) return null
        // The frame as it is SHOWN: a phone records a portrait clip as a
        // landscape frame + "rotate 90". The encoder rotates first and then fits
        // into this size, so a coded-orientation size turns 9:16 into 16:9.
        val w = source.displayWidth
        val h = source.displayHeight
        val longEdge = maxOf(w, h)
        if (longEdge <= maxLongEdge) return null
        val k = maxLongEdge.toDouble() / longEdge
        // Encoders require even sides.
        fun even(v: Int) = (v / 2) * 2
        return even((w * k).toInt()) to even((h * k).toInt())
    }

    fun describe(): String =
        "$name v$version [$videoMimeType ≤${maxLongEdge}px ${minVideoBitrate}..${maxVideoBitrate} " +
            "bpp=$bitsPerPixel audio=$audioMimeType/${audioBitrate} sdr=$requireSdrBt709]"

    companion object {

        /**
         * The V1 compatibility profile.
         *
         * H.264 High + AAC — what opens everywhere, including old devices and
         * the web. The frame is not downscaled up to and including 4K: the
         * goal is colour and codec compatibility, not traffic saving;
         * resolution downscaling will be a separate "economy" profile once a
         * video-quality setting exists.
         */
        val V1_COMPATIBILITY = MediaExportProfile(
            version = 1,
            name = "compat",
            videoMimeType = "video/avc",
            maxLongEdge = 3840,
            maxVideoBitrate = 40_000_000,
            minVideoBitrate = 1_500_000,
            bitsPerPixel = 0.12,
            audioMimeType = "audio/mp4a-latm",
            audioBitrate = 128_000,
            maxAudioChannels = 2,
            requireSdrBt709 = true,
        )

        /**
         * "SD": 720p at up to ~2.5 Mbit/s — a minute of video is about 18 MB
         * instead of 100+. Used only with [MediaExportPolicy.shrinkToProfile].
         */
        val V1_ECONOMY = MediaExportProfile(
            version = 1,
            name = "economy",
            videoMimeType = "video/avc",
            maxLongEdge = 1280,
            maxVideoBitrate = 2_500_000,
            minVideoBitrate = 600_000,
            bitsPerPixel = 0.08,
            audioMimeType = "audio/mp4a-latm",
            audioBitrate = 96_000,
            maxAudioChannels = 2,
            requireSdrBt709 = true,
        )
    }
}

/**
 * Send policy — product decisions, not file properties.
 *
 * Deliberately outside the planner: "should HDR be brought down to an
 * ordinary range" is a question of what the app promises the recipient, and
 * it must not be decided as a side effect — say, of whether the user trimmed
 * the clip. It used to be exactly that: a trimmed video became SDR, an
 * untrimmed one stayed HDR, with nothing to explain the difference.
 */
data class MediaExportPolicy(
    val hdr: HdrPolicy = HdrPolicy.STANDARD_SDR,
    val profile: MediaExportProfile = MediaExportProfile.V1_COMPATIBILITY,
    /** Documents are never converted: the file must arrive byte for byte. */
    val convertDocuments: Boolean = false,
    /** "SD": a video larger than [profile] (frame or bitrate) is re-encoded down to it. */
    val shrinkToProfile: Boolean = false,
) {
    /**
     * The names here are PRODUCT names, not mechanical ones. Not "do
     * tone-mapping" but "what the app promises the recipient": the promise
     * is chosen once; how it is fulfilled is the planner's business.
     */
    enum class HdrPolicy {
        /**
         * The V1 default. Sent video carries no HDR and does not force the
         * recipient's screen to change brightness — **regardless** of
         * whether the sender edited the clip.
         *
         * The price is honest and must be known: a two-minute 4K Dolby Vision
         * with no edits at all is transcoded whole, so the send stops being
         * instant. One cannot promise "brightness never jumps" while letting
         * unedited HDR through for speed — that would be a sometimes-promise.
         */
        STANDARD_SDR,

        /**
         * Keep HDR where the recipient can handle it; bring it down to an
         * ordinary range only if transcoding is required anyway for another
         * reason. An unedited send stays instant.
         */
        KEEP_HDR_WHEN_SUPPORTED,

        /** Never touch colour. For debugging and comparing measurements. */
        ORIGINAL,
    }
}
