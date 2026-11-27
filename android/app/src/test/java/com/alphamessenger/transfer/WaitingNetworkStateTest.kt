package com.alphamessenger.transfer

import com.alphamessenger.media.TransferStage
import com.alphamessenger.media.MediaKind
import com.alphamessenger.media.MediaState
import com.alphamessenger.media.toOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transfer state contract: waiting for the network.
 *
 * Three DIFFERENT outcomes, not two. The defect behind this file: with Wi-Fi off a
 * large send showed ↻, a tap brought the same icon back half a second later, and
 * there was no ✕ to cancel the stuck send. Meanwhile the core reported a failure 23
 * times a minute, though nothing failed: it was waiting for the transport.
 *
 * The LIVE chain is checked, the same one the bubble draws:
 *
 *   core (on_delivery_update "waiting_network")
 *     → TransferEngine.onUploadWaitingNetwork
 *     → TransferState.WaitingNetwork
 *     → MediaState.stage
 *     → toOverlayState
 *     → FileUiState
 *
 * There is no second state model here and there must not be one: see the notes
 * where `TransferGlyph` and `MediaControl` were removed.
 */
class WaitingNetworkStateTest {

    // ── test stubs: the engine must touch neither disk nor network ─────────

    private class FakeRepo : TransferRepository {
        override fun cacheEntry(ref: String) = CacheEntry.ABSENT
        override fun mediaAvailability(ref: String, senderLocalPath: String?) = FileSource.Missing
        override suspend fun downloadTracked(ref: String, opId: String): String? = null
        override fun deleteCache(ref: String, deleteLocalCopy: Boolean): Long = 0L
        override fun markCacheEvicted(ref: String) = Unit
        override fun unmarkCacheEvicted(ref: String) = Unit
        override fun isCacheEvicted(ref: String) = false
        override val progress: SharedFlow<Triple<String, Long, Long>> = MutableSharedFlow()
    }

    private fun engine() = TransferEngine(
        scope = CoroutineScope(Dispatchers.Unconfined),
        repo = FakeRepo(),
        policy = { _, _, _ -> false },
    )

    private val msg = "msg-1"
    private val key = TransferKey.upload(msg)

    /** Bring the send to a confirmed mark of 40 of 100 bytes. */
    private fun TransferEngine.uploadedTo(bytes: Long, total: Long = 100L) {
        onUploadEncrypting(msg)
        onUploadBytes(msg, bytes, total)
    }

    private fun overlayOf(stage: TransferStage, bytes: Long = 0L, total: Long = 0L): FileUiState =
        MediaState(
            kind = MediaKind.FILE, stage = stage, progress = null,
            bytes = bytes, total = total, localPath = null, playable = false,
            streamable = false, poster = null, durationMs = 0, sizeBytes = 0L,
            albumItems = null, albumDone = 0, albumTotal = 0,
        ).toOverlayState(isOutgoing = true)

    // ── 1. QUEUED is not waiting for the network ───────────────────────────

    @Test
    fun QUEUED_IS_NOT_WAITING_NETWORK() {
        val e = engine()
        e.onUploadQueued(msg)
        val s = e.stateOf(key)
        assertTrue("a queue stays a queue", s is TransferState.Queued)
        assertFalse("a queue must not pose as waiting for the network", s is TransferState.WaitingNetwork)
    }

    // ── 2. Waiting for the network is not a failure ────────────────────────

    @Test
    fun WAITING_NETWORK_NOT_FAILED() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        val snap = e.snapshotOf(key)
        assertTrue(snap.isWaitingNetwork)
        assertFalse("waiting is not a failure", snap.isFailed)
        assertFalse("waiting is not terminal: the work goes on by itself", snap.isTerminal)
    }

    // ── 3. The core announces waiting, the engine takes it ─────────────────

    @Test
    fun OFFLINE_EMITS_WAITING_NETWORK() {
        val e = engine()
        e.uploadedTo(40L)
        // exactly what the delivery bridge does on status "waiting_network"
        e.onUploadWaitingNetwork(msg)
        assertTrue(e.stateOf(key) is TransferState.WaitingNetwork)
    }

    // ── 4. The resume mark survives a lost connection ──────────────────────

    @Test
    fun WAITING_NETWORK_PRESERVES_PROGRESS() {
        val e = engine()
        e.uploadedTo(40L, total = 100L)
        e.onUploadWaitingNetwork(msg)
        val p = e.snapshotOf(key).progress
        assertEquals("confirmed bytes are not reset", 40L, p.bytes)
        assertEquals(100L, p.total)
    }

    // ── 5. ✕ is shown ──────────────────────────────────────────────────────

    @Test
    fun WAITING_NETWORK_SHOWS_CANCEL() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        assertTrue("✕ must be on every busy state", e.snapshotOf(key).canCancel)

        // and the same at the very end of the chain, where the icon is drawn
        val ui = overlayOf(TransferStage.WaitingNetwork, 40L, 100L)
        assertTrue(ui is FileUiState.WaitingNetwork)
        assertTrue("a busy overlay → the centre icon is ✕", ui.isActive)
    }

    // ── 6. ↻ is NOT shown ──────────────────────────────────────────────────

    @Test
    fun WAITING_NETWORK_NO_RETRY() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        assertFalse("nothing to retry: the work goes on by itself", e.snapshotOf(key).canRetry)

        // The overlay draws ↻ on exactly one branch, Failed. It must not be here.
        val ui = overlayOf(TransferStage.WaitingNetwork, 40L, 100L)
        assertFalse("waiting must not reach the ↻ branch", ui is FileUiState.Failed)
    }

    // ── 7. The connection is back: resume, not restart ─────────────────────

    @Test
    fun NETWORK_RETURN_RESUMES() {
        val e = engine()
        e.uploadedTo(40L, total = 100L)
        e.onUploadWaitingNetwork(msg)
        // the core claimed the row and announced "sending"
        e.onUploadResumed(msg)
        val s = e.stateOf(key)
        assertTrue("waiting turns into uploading", s is TransferState.Uploading)
        assertEquals("the resume starts at the mark, not at zero", 40L, e.snapshotOf(key).progress.bytes)
    }

    // ── 8. A cancel is not revived by a waiting report ─────────────────────

    @Test
    fun CANCEL_WAITING_NO_RESURRECTION() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        e.dispatch(key, TransferAction.Cancel)
        assertTrue(e.stateOf(key) is TransferState.Cancelled)

        // A waiting report may already be on its way when ✕ is tapped; it must not
        // revive what was cancelled.
        e.onUploadWaitingNetwork(msg)
        assertTrue("cancelled stays cancelled", e.stateOf(key) is TransferState.Cancelled)
    }

    // ── 9. A real failure still gives ↻ ────────────────────────────────────

    @Test
    fun FAILED_SHOWS_RETRY() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadFailed(msg, "server refused")
        val snap = e.snapshotOf(key)
        assertTrue(snap.isFailed)
        assertTrue("a real error needs ↻", snap.canRetry)

        val ui = overlayOf(TransferStage.Failed)
        assertTrue("↻ is drawn on the Failed branch", ui is FileUiState.Failed)
    }

    // ── 10. A retry without network waits, no failure loop ─────────────────

    @Test
    fun RETRY_OFFLINE_WAITING() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadFailed(msg, "cut")
        assertTrue(e.snapshotOf(key).isFailed)

        // The user taps ↻. The core queues the row again, the connection gate does
        // not let it through and reports waiting.
        e.onUploadEncrypting(msg)
        e.onUploadWaitingNetwork(msg)
        assertTrue("a retry without network waits", e.stateOf(key) is TransferState.WaitingNetwork)

        // And the sender's pessimistic "it failed" must not overwrite the core's exact
        // answer, or the ↻ loop comes back.
        e.onUploadFailed(msg)
        assertTrue(
            "a general 'no' does not override the exact 'waiting for transport'",
            e.stateOf(key) is TransferState.WaitingNetwork,
        )
    }

    // ── 11. A retry has one owner ──────────────────────────────────────────

    @Test
    fun RETRY_SINGLE_OWNER() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        // Sending belongs to the send pipeline, not to a tap. A retry from the UI
        // cannot start a SECOND sender: the engine refuses any Start on a send key.
        e.dispatch(key, TransferAction.Start)
        assertTrue(
            "a tap does not start a second sender",
            e.stateOf(key) is TransferState.WaitingNetwork,
        )
    }

    // ── 12. Exactly one publish ────────────────────────────────────────────

    @Test
    fun PUBLISH_ONCE() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        e.onUploadResumed(msg)
        e.onUploadBytes(msg, 100L, 100L)
        e.onUploadSent(msg)
        assertTrue(e.stateOf(key) is TransferState.Completed)

        // A late waiting report (the gate announced it before the publish was
        // confirmed) does not roll a finished send back into work.
        e.onUploadWaitingNetwork(msg)
        assertTrue("a finished send does not go back to waiting", e.stateOf(key) is TransferState.Completed)
    }

    // ── T4: resume regression ──────────────────────────────────────────────

    @Test
    fun T4_RESUME_REGRESSION() {
        val e = engine()
        // 40 % sent, the connection is lost
        e.uploadedTo(40L, total = 100L)
        e.onUploadWaitingNetwork(msg)
        assertEquals(40L, e.snapshotOf(key).progress.bytes)

        // the connection is back, the core goes on FROM THE MARK (absolute numbers from the core)
        e.onUploadResumed(msg)
        e.onUploadBytes(msg, 41L, 100L)
        assertTrue(e.stateOf(key) is TransferState.Uploading)
        assertTrue(
            "the resume does not start at zero",
            e.snapshotOf(key).progress.bytes >= 40L,
        )

        e.onUploadBytes(msg, 100L, 100L)
        e.onUploadSent(msg)
        assertTrue(e.stateOf(key) is TransferState.Completed)
        assertNull("a send has no local destination file", e.snapshotOf(key).localPath)
    }

    // ── the overlay state map in full (nothing lost) ───────────────────────

    @Test
    fun ALBUM_AND_STAGE_MAPPING() {
        assertTrue(overlayOf(TransferStage.Uploading, 1, 2) is FileUiState.Uploading)
        assertTrue(overlayOf(TransferStage.Downloading, 1, 2) is FileUiState.Downloading)
        assertTrue(overlayOf(TransferStage.Paused, 1, 2) is FileUiState.Paused)
        assertTrue(overlayOf(TransferStage.WaitingNetwork, 1, 2) is FileUiState.WaitingNetwork)
        assertTrue(overlayOf(TransferStage.Failed) is FileUiState.Failed)
        assertTrue(overlayOf(TransferStage.NeedDownload) is FileUiState.NeedDownload)
        assertTrue(overlayOf(TransferStage.Ready) is FileUiState.Ready)
        assertTrue(overlayOf(TransferStage.Uploaded) is FileUiState.Ready)
        assertTrue(overlayOf(TransferStage.Idle) is FileUiState.Hidden)
        assertTrue(overlayOf(TransferStage.Canceled) is FileUiState.Hidden)
    }

    // ── recovery after the process dies ────────────────────────────────────

    /**
     * A queue row survived the process: the screen must show it.
     *
     * Seen on a device: the app was killed mid-send, after start the core resumed from
     * the mark (`RESUME 192 of 241`, one publish), and the bubble showed only a clock,
     * no ✕ and no ring.
     */
    @Test
    fun SEED_RESTORES_QUEUED_AFTER_RESTART() {
        val e = engine()  // a fresh engine = the state after a restart
        assertTrue("the engine remembers nothing", e.stateOf(key) is TransferState.Idle)

        e.seedQueuedIfUnknown(msg)
        val snap = e.snapshotOf(key)
        assertTrue(snap.state is TransferState.Queued)
        assertTrue("✕ must appear at once", snap.canCancel)
        assertFalse("not a failure: no ↻ offered", snap.canRetry)
    }

    /** A live state beats a restored one: a list refresh does not reset the work. */
    @Test
    fun SEED_NEVER_CLOBBERS_LIVE() {
        val e = engine()
        e.uploadedTo(40L, total = 100L)
        e.seedQueuedIfUnknown(msg)
        assertTrue("a running upload does not fall back to the queue", e.stateOf(key) is TransferState.Uploading)

        e.onUploadWaitingNetwork(msg)
        e.seedQueuedIfUnknown(msg)
        assertTrue("waiting for the network is not replaced by the queue", e.stateOf(key) is TransferState.WaitingNetwork)

        e.dispatch(key, TransferAction.Cancel)
        e.seedQueuedIfUnknown(msg)
        assertTrue("cancelled is not revived from the queue", e.stateOf(key) is TransferState.Cancelled)
    }

    /** Publishing draws no byte ring: the bytes are already gone. */
    @Test
    fun PUBLISHING_NO_BYTE_PROGRESS() {
        val e = engine()
        e.uploadedTo(100L, total = 100L)
        e.onUploadPublishing(msg)
        assertTrue(e.stateOf(key) is TransferState.Publishing)
        assertNull("publishing has no progress of its own", e.stateOf(key).progressOrNull)
        // and no ring at the end of the chain either
        assertFalse(overlayOf(TransferStage.Uploaded, 100L, 100L).isActive)
    }
}
