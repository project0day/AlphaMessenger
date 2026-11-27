package com.alphamessenger.ui.media.export

/**
 * What is done to the CONTENT. Axis one.
 *
 * Only about the media bytes: whether we change them. Where the file lives
 * is a separate question, see [Materialization].
 */
enum class ExportRoute {
    /** Content is not changed at all. */
    REUSE,

    /** Stream copy: time trim and audio. Pixels are untouched. */
    REMUX,

    /** Transcode: frame crop, rotation, colour conversion. */
    TRANSCODE,
}

/**
 * Where the file ends up. Axis two, INDEPENDENT of [ExportRoute].
 *
 * The split is fundamental. A gallery source is a `content://`, while upload
 * reads a path — so an own copy is needed even where the content does not
 * change by a byte. While the two axes were one, copying looked like
 * processing, and "why was the unedited clip still written somewhere" had
 * no answer.
 */
enum class Materialization {
    /** The source is already our file — work on it directly. */
    NONE,

    /** Copy byte for byte into our storage. Content is unchanged. */
    COPY_TO_OWNED_STORAGE,

    /** The file is produced by the processing itself — nothing to copy. */
    PRODUCED_BY_EXPORT,
}

/**
 * The planner's decision — the executor must follow it LITERALLY.
 *
 * The executor chooses nothing. That is the point of the split: while the
 * decision lived inside the trimmer, "why was this clip transcoded" had no
 * answer — the condition was smeared across three levels, the third one
 * inside a third-party library.
 */
data class ExportPlan(
    val route: ExportRoute,
    /** Human-readable reason. Written to the trace for every send. */
    val reason: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val removeAudio: Boolean,
    val toneMapHdrToSdr: Boolean,
    /** Filled only for [ExportRoute.TRANSCODE]. */
    val profile: MediaExportProfile? = null,
    /** The bitrate that will be HANDED to the encoder. 0 — no transcoding. */
    val videoBitrate: Int = 0,
    /** Target frame size, if the profile demands downscaling. */
    val scaledWidth: Int = 0,
    val scaledHeight: Int = 0,
    /**
     * Whether VIDEO frame geometry (crop/rotation) is requested.
     *
     * In V1 this is representable but never executed: the UI has no such
     * controls for video. The field exists so the contract need not change
     * when they appear, and so the executor can say "cannot do that" loudly
     * instead of silently delivering an unrotated frame.
     */
    val videoGeometryRequested: Boolean = false,
) {
    val isTranscode: Boolean get() = route == ExportRoute.TRANSCODE

    /** Whether the CONTENT changes. Not to be confused with needing an own copy. */
    val changesContent: Boolean get() = route != ExportRoute.REUSE

    /**
     * How the file gets into our storage. Derived from the route, not set
     * separately: processing always writes its own file, and reuse always
     * needs a copy while the message manifest cannot reference an external
     * source.
     */
    val materialization: Materialization
        get() = if (changesContent) Materialization.PRODUCED_BY_EXPORT
        else Materialization.COPY_TO_OWNED_STORAGE

    fun describe(): String = buildString {
        append(route).append('+').append(materialization).append(": ").append(reason)
        if (trimStartMs > 0 || trimEndMs > 0) append(" trim=").append(trimStartMs).append("..").append(trimEndMs)
        if (removeAudio) append(" mute")
        if (toneMapHdrToSdr) append(" tone-map")
        if (videoBitrate > 0) append(" bitrate=").append(videoBitrate)
        if (scaledWidth > 0) append(" scale=").append(scaledWidth).append("x").append(scaledHeight)
        profile?.let { append(" profile=").append(it.describe()) }
    }
}

/**
 * The ONE place where REUSE / REMUX / TRANSCODE is decided.
 *
 * A pure function: source properties + edits + policy → plan. No files, no
 * context, no network — so the decision is fully testable and fully
 * explainable by the [ExportPlan.reason] string.
 *
 * The order of checks is the rule priority, and it is not accidental:
 *
 * 1. document — no transforms;
 * 2. pixel redraw (crop, rotation) — transcode only;
 * 3. colour per policy — transcode;
 * 4. time and audio — stream copy;
 * 5. nothing changed — the file goes as is.
 *
 * Rule 3 sits ABOVE rule 4 deliberately. Colour conversion used to be a
 * side effect of trimming: a trimmed clip became SDR, an untrimmed one
 * stayed HDR. Now it is a policy decision, independent of whether the user
 * touched the handles.
 */
object MediaExportPlanner {

    fun plan(
        source: SourceMetadata,
        spec: MediaEditSpec,
        policy: MediaExportPolicy = MediaExportPolicy(),
    ): ExportPlan {
        val duration = source.durationMs
        val trimEnd = spec.endOr(duration)
        val hasTrim = spec.hasTrim(duration)
        val mute = spec.muted && source.hasAudio

        // 1. A document goes byte for byte. Size, colour, codec are not ours.
        if (spec.sendAsFile && !policy.convertDocuments) {
            return reuse(spec, trimEnd, "sent as a file, content unchanged")
        }

        // A photo is always rebuilt in V1: compression by the send profile
        // is what makes it a "photo" rather than a "file". REUSE will come
        // with a "send as original" setting — a user choice, not a guess.
        if (source.kind == SourceMetadata.Kind.IMAGE) {
            return ExportPlan(
                route = ExportRoute.TRANSCODE,
                reason = "photo rebuilt to the send profile " +
                    "(${spec.photoMaxEdge}px q${spec.photoQuality})",
                trimStartMs = 0L, trimEndMs = 0L,
                removeAudio = false, toneMapHdrToSdr = false,
                profile = policy.profile,
            )
        }

        val needsToneMap = when (policy.hdr) {
            MediaExportPolicy.HdrPolicy.STANDARD_SDR -> source.hdr
            MediaExportPolicy.HdrPolicy.KEEP_HDR_WHEN_SUPPORTED -> source.hdr && spec.changesPixels
            MediaExportPolicy.HdrPolicy.ORIGINAL -> false
        }

        // 2. Pixel redraw — transcode only, there is no other way.
        if (spec.changesPixels) {
            return transcode(
                source, spec, policy, trimEnd, mute, needsToneMap,
                reason = "frame geometry changed (crop/rotate), redraw needed",
                geometry = true,
            )
        }

        // 3. Colour — a policy decision, not a consequence of trimming.
        if (needsToneMap) {
            return transcode(
                source, spec, policy, trimEnd, mute, true,
                reason = "HDR source (transfer=${source.transfer}, ${source.videoMime}), " +
                    "policy ${policy.hdr} requires SDR",
            )
        }

        // 3b. "SD": a video larger than the profile is brought down to it (a
        // product choice of the sender, like the colour promise above).
        // 3b. A chosen quality (360p…1080p): a video larger or heavier than it goes
        // out at it. The sender picks the resolution; nothing is guessed here.
        if (policy.shrinkToProfile && source.kind == SourceMetadata.Kind.VIDEO) {
            val profile = policy.profile
            val bigFrame = maxOf(source.displayWidth, source.displayHeight) > profile.maxLongEdge
            val heavy = source.videoBitrate > 0 && source.videoBitrate > profile.maxVideoBitrate * 1.15
            if (bigFrame || heavy) {
                return transcode(
                    source, spec, policy, trimEnd, mute, needsToneMap,
                    reason = "quality ${profile.maxLongEdge}px: ${source.width}x${source.height}, ${source.videoBitrate / 1000} kbit/s → " +
                        "up to ${profile.maxVideoBitrate / 1000} kbit/s",
                )
            }
        }

        // 4. Time and audio do not touch pixels — stream copy is enough.
        if (hasTrim || mute) {
            return ExportPlan(
                route = ExportRoute.REMUX,
                reason = buildString {
                    append("stream copy: ")
                    append(if (hasTrim) "time trim" else "")
                    if (hasTrim && mute) append(" and ")
                    append(if (mute) "mute" else "")
                    append(", pixels unchanged")
                },
                trimStartMs = spec.trimStartMs,
                trimEndMs = trimEnd,
                removeAudio = mute,
                toneMapHdrToSdr = false,
            )
        }

        // 5. No edits.
        return reuse(spec, trimEnd, "no edits, the file goes as is")
    }

    private fun reuse(spec: MediaEditSpec, trimEnd: Long, reason: String) = ExportPlan(
        route = ExportRoute.REUSE,
        reason = reason,
        trimStartMs = spec.trimStartMs,
        trimEndMs = trimEnd,
        removeAudio = false,
        toneMapHdrToSdr = false,
    )

    private fun transcode(
        source: SourceMetadata,
        spec: MediaEditSpec,
        policy: MediaExportPolicy,
        trimEnd: Long,
        mute: Boolean,
        toneMap: Boolean,
        reason: String,
        geometry: Boolean = false,
    ): ExportPlan {
        val profile = policy.profile
        val scaled = profile.scaledSize(source)
        return ExportPlan(
            route = ExportRoute.TRANSCODE,
            reason = reason,
            trimStartMs = spec.trimStartMs,
            trimEndMs = trimEnd,
            removeAudio = mute,
            toneMapHdrToSdr = toneMap,
            profile = profile,
            videoBitrate = profile.videoBitrateFor(source, scaled),
            scaledWidth = scaled?.first ?: 0,
            scaledHeight = scaled?.second ?: 0,
            videoGeometryRequested = geometry && source.kind == SourceMetadata.Kind.VIDEO,
        )
    }
}
