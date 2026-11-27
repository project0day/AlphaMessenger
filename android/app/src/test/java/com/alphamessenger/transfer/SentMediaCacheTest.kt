package com.alphamessenger.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A sent video's bytes have one home once the send finished: the blob's cache entry,
 * shared by every message that carries the blob. "Delete from cache" then leaves
 * nothing to play, and a re-check after the viewer closes never downloads by itself.
 */
class SentMediaCacheTest {

    /** The engine's automatic-download turns (its private AUTO_DOWNLOADS_AT_ONCE). */
    private val AUTO_AT_ONCE = 4

    @get:Rule val tmp = TemporaryFolder()

    private class FakeRepo : TransferRepository {
        val cache = HashMap<String, File>()
        val evicted = HashSet<String>()
        var downloads = 0
        /** What a download writes into the cache. */
        var fetched: (String) -> File? = { null }

        override fun cacheEntry(ref: String) = CacheEntry.ABSENT

        override fun mediaAvailability(ref: String, senderLocalPath: String?): FileSource {
            senderLocalPath?.let(::File)?.takeIf { it.exists() }?.let { return FileSource.Original(it.absolutePath) }
            cache[ref]?.takeIf { it.exists() }?.let { return FileSource.Cache(it.absolutePath) }
            return FileSource.Remote
        }

        /** Held until opened: lets a test see how many downloads run at once. */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        val started = mutableListOf<String>()

        override suspend fun downloadTracked(ref: String, opId: String): String? {
            downloads++
            started.add(ref)
            running.incrementAndGet()
            try { gate?.await() } finally { running.decrementAndGet() }
            return fetched(ref)?.also { cache[ref] = it }?.absolutePath
        }

        override fun deleteCache(ref: String, deleteLocalCopy: Boolean): Long {
            val f = cache[ref]?.takeIf { it.exists() } ?: return 0L
            val n = f.length()
            return if (f.delete()) n else 0L
        }

        override fun markCacheEvicted(ref: String) { evicted.add(ref) }
        override fun unmarkCacheEvicted(ref: String) { evicted.remove(ref) }
        override fun isCacheEvicted(ref: String) = ref in evicted
        override val progress: SharedFlow<Triple<String, Long, Long>> = MutableSharedFlow()
    }

    private lateinit var repo: FakeRepo

    private fun engine(autoDownload: Boolean = false): TransferEngine {
        repo = FakeRepo()
        return TransferEngine(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repo = repo,
            policy = { _, _, _ -> autoDownload },
        )
    }

    private fun file(name: String, bytes: Int = 1024): File =
        File(tmp.root, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    private fun TransferEngine.show(ref: String, localPath: String?, outgoing: Boolean, msgId: String) =
        consider(TransferKey.download(ref), "video", "v.mp4", 26L * 1024 * 1024, outgoing, localPath, msgId)

    /** The send finished: its outbox file moves into the blob's cache entry. */
    private fun TransferEngine.adopt(ref: String, outbox: File) {
        val cached = file("media/${ref}_blob", outbox.length().toInt())
        outbox.delete()
        repo.cache[ref] = cached
        onSentFileAdopted(ref)
    }

    @Test
    fun sentVideoClearedFromCacheOffersDownloadAndComesBack() {
        val e = engine()
        val ref = "ref-sent"
        val key = TransferKey.download(ref)
        val outbox = file("outbox/work/s1/0.r1.mp4", 4096)
        e.show(ref, outbox.absolutePath, outgoing = true, msgId = "m1")
        e.adopt(ref, outbox)
        assertTrue("plays from the cache after the send", e.snapshotOf(key).isReady)

        assertEquals("the one copy is deleted", 4096L, e.deleteCacheOf(listOf(key), deleteLocalCopy = true))
        assertTrue("offers a download", e.stateOf(key) is TransferState.CacheEvicted)
        assertEquals("a second delete finds nothing", 0L, e.deleteCacheOf(listOf(key), deleteLocalCopy = true))
        assertTrue(e.stateOf(key) is TransferState.CacheEvicted)

        repo.fetched = { file("media/${it}_again", 4096) }
        e.dispatch(key, TransferAction.Start, "m1")
        assertTrue("downloading again restores playback", e.snapshotOf(key).isReady)
    }

    @Test
    fun twoMessagesOfOneBlobHaveOneCopyToDelete() {
        val e = engine()
        val ref = "ref-shared"
        val key = TransferKey.download(ref)
        val first = file("outbox/work/s1/0.r1.mp4", 2048)
        val second = file("outbox/work/s2/0.r1.mp4", 2048)
        e.show(ref, first.absolutePath, outgoing = true, msgId = "m1")
        e.adopt(ref, first)
        second.delete()
        e.show(ref, second.absolutePath, outgoing = true, msgId = "m2")

        e.deleteCacheOf(listOf(key), deleteLocalCopy = true)

        assertTrue("whichever message showed last, nothing is left to play", e.stateOf(key) is TransferState.CacheEvicted)
    }

    @Test
    fun aDeliveryReportKeepsTheAlbumsDownloadGroup() {
        val e = engine()
        val album = "m-album"
        val sendMembers = (0..1).map { TransferKey.upload("$album#$it") }
        e.registerGroup(album, sendMembers, immediate = true)
        e.onOutgoingTransferPublished(album)
        assertFalse("the send's group ends with the send", album in e.groups.value)

        val downloads = (0..1).map { TransferKey.download("ref-al-$it") }
        e.registerGroup(album, downloads, immediate = true)
        e.onOutgoingTransferPublished(album)
        assertTrue("a later delivered/read report leaves the album's ↓ group", album in e.groups.value)
    }

    @Test
    fun automaticDownloadsTakeTurnsAndATapGoesPastThem() {
        val e = engine(autoDownload = true)
        repo.gate = kotlinx.coroutines.CompletableDeferred()
        val refs = (1..6).map { "ref-auto-$it" }
        refs.forEach { e.show(it, null, outgoing = false, msgId = it) }
        assertEquals("four automatic downloads at once", AUTO_AT_ONCE, repo.running.get())

        e.dispatch(TransferKey.download("ref-tapped"), TransferAction.Start, "m-tap")
        assertTrue("a tap does not wait for the queue", "ref-tapped" in repo.started)
        assertEquals(AUTO_AT_ONCE + 1, repo.running.get())
        repo.gate?.complete(Unit)
    }

    @Test
    fun receivedVideoClearedFromCacheOffersDownload() {
        val e = engine()
        val ref = "ref-received"
        val key = TransferKey.download(ref)
        repo.cache[ref] = file("media/received", 4096)
        e.show(ref, null, outgoing = false, msgId = "m3")
        assertTrue(e.snapshotOf(key).isReady)

        assertEquals(4096L, e.deleteCacheOf(listOf(key), deleteLocalCopy = true))
        assertTrue(e.stateOf(key) is TransferState.CacheEvicted)

        repo.fetched = { file("media/received_again", 4096) }
        e.dispatch(key, TransferAction.Start, "m3")
        assertTrue(e.snapshotOf(key).isReady)
    }

    @Test
    fun recheckAfterTheViewerNeverDownloadsAnEvictedVideo() {
        val e = engine(autoDownload = true)
        val ref = "ref-below"
        val key = TransferKey.download(ref)
        repo.cache[ref] = file("media/below", 4096)
        e.show(ref, null, outgoing = false, msgId = "m4")
        e.deleteCacheOf(listOf(key), deleteLocalCopy = true)
        val before = repo.downloads

        e.show(ref, null, outgoing = false, msgId = "m4")

        assertEquals("no download started", before, repo.downloads)
        assertTrue(e.stateOf(key) is TransferState.CacheEvicted)
    }

    @Test
    fun recheckAfterTheViewerShowsBytesTheViewerFetched() {
        val e = engine()
        val ref = "ref-watched"
        val key = TransferKey.download(ref)
        repo.cache[ref] = file("media/watched", 4096)
        e.show(ref, null, outgoing = false, msgId = "m5")
        e.deleteCacheOf(listOf(key), deleteLocalCopy = true)
        repo.cache[ref] = file("media/watched", 4096)

        e.show(ref, null, outgoing = false, msgId = "m5")

        assertTrue("the bytes are back, so it plays", e.snapshotOf(key).isReady)
        assertFalse("the eviction mark is gone", repo.isCacheEvicted(ref))
    }
}
