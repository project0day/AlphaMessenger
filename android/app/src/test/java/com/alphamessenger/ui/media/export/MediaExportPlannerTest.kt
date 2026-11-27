package com.alphamessenger.ui.media.export

import com.alphamessenger.ui.media.editor.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner is a pure function, so its behaviour is checked whole, with no device
 * and no files. It is the one way to keep the policy from drifting: "HDR goes to SDR
 * whatever the trim" would otherwise turn into a side effect of another condition
 * again, as it once did.
 */
class MediaExportPlannerTest {

    private val sdr = SourceMetadata(
        kind = SourceMetadata.Kind.VIDEO,
        hdr = false,
        videoMime = "video/avc",
        width = 1920, height = 1080, frameRate = 30,
        hasAudio = true, audioBitrate = 128_000,
        fileBytes = 50_000_000, durationMs = 60_000,
    )

    private val dolbyVision = sdr.copy(
        hdr = true,
        videoMime = "video/dolby-vision",
        width = 3840, height = 2160,
        transfer = SourceMetadata.TRANSFER_PQ,
        fileBytes = 350_000_000,
    )

    // ── Nothing changed ──────────────────────────────────────────────────

    @Test
    fun `unedited sdr is reused`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec())
        assertEquals(ExportRoute.REUSE, plan.route)
        assertNull(plan.profile)
        assertEquals(0, plan.videoBitrate)
    }

    // ── Time and sound: pixels untouched ─────────────────────────────────

    @Test
    fun `a time trim is a stream copy`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(trimStartMs = 5_000, trimEndMs = 20_000))
        assertEquals(ExportRoute.REMUX, plan.route)
        assertEquals(5_000L, plan.trimStartMs)
        assertEquals(20_000L, plan.trimEndMs)
        assertTrue(plan.reason.contains("pixels unchanged"))
    }

    @Test
    fun `muting is a stream copy`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(muted = true))
        assertEquals(ExportRoute.REMUX, plan.route)
        assertTrue(plan.removeAudio)
    }

    @Test
    fun `sound that is not there cannot be muted`() {
        val plan = MediaExportPlanner.plan(sdr.copy(hasAudio = false), MediaEditSpec(muted = true))
        assertEquals(ExportRoute.REUSE, plan.route)
    }

    @Test
    fun `jitter at the end of the scale is not a trim`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(trimEndMs = 59_980))
        assertEquals(ExportRoute.REUSE, plan.route)
    }

    // ── Geometry: transcode only ─────────────────────────────────────────

    @Test
    fun `a frame crop needs a transcode`() {
        val plan = MediaExportPlanner.plan(
            sdr, MediaEditSpec(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)),
        )
        assertEquals(ExportRoute.TRANSCODE, plan.route)
        assertNotNull(plan.profile)
        assertTrue(plan.videoBitrate > 0)
    }

    @Test
    fun `a rotation needs a transcode`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(rotationDegrees = 90))
        assertEquals(ExportRoute.TRANSCODE, plan.route)
    }

    // ── HDR: the policy decides, not the trim ────────────────────────────

    @Test
    fun `unedited dolby vision still goes to sdr by default`() {
        val plan = MediaExportPlanner.plan(dolbyVision, MediaEditSpec())
        assertEquals(ExportRoute.TRANSCODE, plan.route)
        assertTrue(plan.toneMapHdrToSdr)
        assertTrue(plan.reason.contains("HDR"))
    }

    @Test
    fun `trimmed dolby vision goes to sdr too`() {
        val plan = MediaExportPlanner.plan(dolbyVision, MediaEditSpec(trimStartMs = 3_000))
        assertEquals(ExportRoute.TRANSCODE, plan.route)
        assertTrue(plan.toneMapHdrToSdr)
    }

    @Test
    fun `with the keep hdr policy an unedited file stays as is`() {
        val plan = MediaExportPlanner.plan(
            dolbyVision, MediaEditSpec(),
            MediaExportPolicy(hdr = MediaExportPolicy.HdrPolicy.KEEP_HDR_WHEN_SUPPORTED),
        )
        assertEquals(ExportRoute.REUSE, plan.route)
        assertTrue(!plan.toneMapHdrToSdr)
    }

    @Test
    fun `with the keep hdr policy a time trim does not transcode`() {
        val plan = MediaExportPlanner.plan(
            dolbyVision, MediaEditSpec(trimStartMs = 1_000),
            MediaExportPolicy(hdr = MediaExportPolicy.HdrPolicy.KEEP_HDR_WHEN_SUPPORTED),
        )
        assertEquals(ExportRoute.REMUX, plan.route)
    }

    @Test
    fun `the original policy never touches colour`() {
        val plan = MediaExportPlanner.plan(
            dolbyVision, MediaEditSpec(crop = CropRect(0f, 0f, 0.5f, 0.5f)),
            MediaExportPolicy(hdr = MediaExportPolicy.HdrPolicy.ORIGINAL),
        )
        assertEquals(ExportRoute.TRANSCODE, plan.route)
        assertTrue(!plan.toneMapHdrToSdr)
    }

    // ── Materialization: an axis apart from the route ────────────────────

    @Test
    fun `reuse still needs its own copy`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec())
        assertEquals(ExportRoute.REUSE, plan.route)
        assertEquals(Materialization.COPY_TO_OWNED_STORAGE, plan.materialization)
    }

    @Test
    fun `processing makes the file itself, nothing to copy`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(trimStartMs = 1_000))
        assertEquals(Materialization.PRODUCED_BY_EXPORT, plan.materialization)
    }

    // ── Video geometry: representable, not executed ──────────────────────

    @Test
    fun `video geometry is a separate flag in the plan`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(rotationDegrees = 90))
        assertTrue(plan.videoGeometryRequested)
    }

    @Test
    fun `a photo never gets the video geometry flag`() {
        val plan = MediaExportPlanner.plan(
            SourceMetadata(kind = SourceMetadata.Kind.IMAGE),
            MediaEditSpec(rotationDegrees = 90),
        )
        assertTrue(!plan.videoGeometryRequested)
    }

    // ── Documents ────────────────────────────────────────────────────────

    @Test
    fun `a file send is never converted, even hdr`() {
        val plan = MediaExportPlanner.plan(dolbyVision, MediaEditSpec(sendAsFile = true))
        assertEquals(ExportRoute.REUSE, plan.route)
        assertTrue(plan.reason.contains("as a file"))
    }

    // ── Photo ────────────────────────────────────────────────────────────

    @Test
    fun `a photo is always rebuilt to the profile`() {
        val plan = MediaExportPlanner.plan(
            SourceMetadata(kind = SourceMetadata.Kind.IMAGE), MediaEditSpec(),
        )
        assertEquals(ExportRoute.TRANSCODE, plan.route)
    }

    // ── Profile ──────────────────────────────────────────────────────────

    @Test
    fun `the bitrate never exceeds the source bitrate`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(rotationDegrees = 90))
        assertTrue(plan.videoBitrate <= sdr.videoBitrate)
    }

    @Test
    fun `an unknown source bitrate takes a quality bitrate by resolution`() {
        // The file is so small that nothing is left for video once sound is taken out:
        // the source bitrate cannot be known, so the profile derives it from the frame.
        val tiny = sdr.copy(fileBytes = 100_000)
        val plan = MediaExportPlanner.plan(tiny, MediaEditSpec(rotationDegrees = 90))
        val expected = (1920L * 1080 * 30 * MediaExportProfile.V1_COMPATIBILITY.bitsPerPixel).toInt()
        assertEquals(expected, plan.videoBitrate)
    }

    @Test
    fun `a light source is not inflated by the profile floor`() {
        // 60 seconds, 5 MB → about 540 kbit/s of video. The profile floor is higher,
        // but the result must not reach the recipient three times heavier than the source.
        val light = sdr.copy(fileBytes = 5_000_000)
        val plan = MediaExportPlanner.plan(light, MediaEditSpec(rotationDegrees = 90))
        assertTrue(
            "bitrate ${plan.videoBitrate} over twice the source ${light.videoBitrate}",
            plan.videoBitrate <= light.videoBitrate * 2,
        )
    }

    @Test
    fun `the compatibility profile does not shrink 4k`() {
        assertNull(MediaExportProfile.V1_COMPATIBILITY.scaledSize(dolbyVision))
    }

    @Test
    fun `a frame over the ceiling shrinks to even sides`() {
        val huge = sdr.copy(width = 7680, height = 4320)
        val scaled = MediaExportProfile.V1_COMPATIBILITY.scaledSize(huge)
        assertNotNull(scaled)
        assertEquals(3840, scaled!!.first)
        assertEquals(0, scaled.second % 2)
    }

    // ── Fingerprint ──────────────────────────────────────────────────────

    @Test
    fun `the fingerprint changes with the edit`() {
        val a = MediaEditSpec(trimStartMs = 0).canonical()
        val b = MediaEditSpec(trimStartMs = 1).canonical()
        assertTrue(a != b)
    }

    @Test
    fun `the fingerprint changes with the quality`() {
        val a = MediaEditSpec(photoQuality = 80).canonical()
        val b = MediaEditSpec(photoQuality = 90).canonical()
        assertTrue(a != b)
    }

    // ── Output key ───────────────────────────────────────────────────────

    private fun revision(
        source: String = "file:///src.mp4",
        spec: MediaEditSpec = MediaEditSpec(),
        profileVersion: Int = MediaExportProfile.V1_COMPATIBILITY.version,
    ) = com.alphamessenger.ui.media.send.MediaSendJob.revisionOf(source, spec, profileVersion)

    @Test
    fun `the same source, edit and profile give one key`() {
        assertEquals(revision(), revision())
    }

    @Test
    fun `a new profile version gives another key`() {
        assertTrue(revision(profileVersion = 1) != revision(profileVersion = 2))
    }

    @Test
    fun `another edit gives another key`() {
        assertTrue(revision() != revision(spec = MediaEditSpec(trimStartMs = 1)))
    }

    @Test
    fun `another source gives another key`() {
        assertTrue(revision() != revision(source = "file:///other.mp4"))
    }

    @Test
    fun `the key is never negative, it goes into a file name`() {
        assertTrue(revision() >= 0L)
    }
}
