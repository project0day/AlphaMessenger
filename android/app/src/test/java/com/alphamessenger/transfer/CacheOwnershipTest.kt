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
 * Byte ownership: cache versus source and OUTBOX.
 *
 * Three kinds of bytes, and only one that "Delete from cache" may touch:
 *
 * * **Source**: the user's original and the set OUTBOX `filesDir/outbox`. Not ours,
 *   or needed by the send. Never touched.
 * * **Cache**: `cacheDir/media`, `cacheDir/stream_chunks`, and our own copy of a
 *   FINISHED single send `cacheDir/send_*`.
 *
 * That copy has two phases: while the send runs it is the T4 resume source and must
 * stay; once the send is done it is disposable again and must go. Forbidding both
 * once broke the main case: "Delete from cache" on a document deleted nothing.
 *
 * The caller decides ([deleteLocalCopy]): it needs a queue lookup, which must not
 * run on the main thread.
 */
class CacheOwnershipTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── disk stub: mirrors the ownership split, not its implementation ─────

    private class FakeRepo(private val root: File) : TransferRepository {
        /** ref → cache path (disposable bytes). */
        val cache = HashMap<String, File>()
        /** ref → source or OUTBOX path (owned bytes). */
        val local = HashMap<String, File>()
        val evicted = HashSet<String>()
        /** What the last cache clear deleted, to check nothing extra went. */
        val deleted = ArrayList<File>()

        override fun cacheEntry(ref: String) = CacheEntry.ABSENT

        override fun mediaAvailability(ref: String, senderLocalPath: String?): FileSource {
            local[ref]?.takeIf { it.exists() }?.let { return FileSource.Original(it.absolutePath) }
            cache[ref]?.takeIf { it.exists() }?.let { return FileSource.Cache(it.absolutePath) }
            return FileSource.Remote
        }

        override suspend fun downloadTracked(ref: String, opId: String): String? = null

        /**
         * The store's rule: disposable cache always, our own copy only inside the
         * cache and only once the send is done.
         */
        override fun deleteCache(ref: String, deleteLocalCopy: Boolean): Long {
            var freed = 0L
            cache[ref]?.takeIf { it.exists() }?.let {
                freed += it.length(); it.delete(); deleted.add(it)
            }
            if (deleteLocalCopy) {
                // Two places are ours, one per send pipeline: the document copy in the
                // cache and the set's output in `outbox/work`. Path separators differ by
                // system, so directories are compared, not substrings.
                local[ref]?.takeIf { it.exists() && ours(it) }?.let {
                    freed += it.length(); it.delete(); deleted.add(it)
                }
            }
            return freed
        }

        /** A copy we made: a document cache file or a set output. */
        private fun ours(f: File): Boolean =
            f.parentFile?.name == "cache" ||
                generateSequence(f.parentFile) { it.parentFile }.any { it.name == "work" }

        override fun markCacheEvicted(ref: String) { evicted.add(ref) }
        override fun unmarkCacheEvicted(ref: String) { evicted.remove(ref) }
        override fun isCacheEvicted(ref: String) = ref in evicted
        override val progress: SharedFlow<Triple<String, Long, Long>> = MutableSharedFlow()
    }

    private lateinit var repo: FakeRepo

    private fun engine(): TransferEngine {
        repo = FakeRepo(tmp.root)
        return TransferEngine(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repo = repo,
            policy = { _, _, _ -> false },
        )
    }

    private fun file(name: String, bytes: Int = 1024): File =
        File(tmp.root, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    /** The bubble tells the engine about the attachment, or the owner never learns of its file. */
    private fun TransferEngine.present(ref: String, ownPath: String?, outgoing: Boolean) =
        consider(TransferKey.download(ref), "image", "f.jpg", 1024L, outgoing, ownPath, "op")

    // ── a finished send: the copy is disposable wherever it was made ───────

    /**
     * An own photo behaves like a document.
     *
     * The document copy lives in `cacheDir/send_*`, the photo copy in
     * `filesDir/outbox/work`, and the rule once knew only the first place: the document
     * was deleted, the photo was not, though the screen promised the same.
     */
    @Test
    fun OUTGOING_PHOTO_AFTER_SENT_IS_DISPOSABLE() {
        val e = engine()
        val ref = "ref-out"
        val sent = file("outbox/work/s1/0.r1.jpg", bytes = 4096)
        repo.local[ref] = sent

        e.present(ref, sent.absolutePath, outgoing = true)
        val key = TransferKey.download(ref)
        assertTrue("before deleting, the photo is local", e.snapshotOf(key).isReady)

        val freed = e.deleteCacheOf(listOf(key), deleteLocalCopy = true)

        assertEquals("real bytes were deleted", 4096L, freed)
        assertFalse("the finished send copy is deleted", sent.exists())
        assertTrue("a new download is offered", e.stateOf(key) is TransferState.CacheEvicted)
    }

    /** A set follows the same rule per item; an album has no model of its own. */
    @Test
    fun OUTGOING_ALBUM_AFTER_SENT_IS_DISPOSABLE() {
        val e = engine()
        val refs = (0..2).map { "ref-album-$it" }
        val files = refs.mapIndexed { i, r ->
            file("outbox/work/s1/$i.r1.jpg", bytes = 2048).also { repo.local[r] = it }
        }
        refs.forEach { e.present(it, repo.local[it]!!.absolutePath, outgoing = true) }

        val freed = e.deleteCacheOf(refs.map { TransferKey.download(it) }, deleteLocalCopy = true)

        assertEquals(3 * 2048L, freed)
        files.forEach { assertFalse("the member copy is deleted", it.exists()) }
    }

    /** While the send runs, the resume source is kept. */
    @Test
    fun ACTIVE_ALBUM_SOURCE_NOT_DELETED() {
        val e = engine()
        val ref = "ref-album-active"
        val src = file("outbox/work/s2/0.r1.jpg", bytes = 2048)
        repo.local[ref] = src
        e.present(ref, src.absolutePath, outgoing = true)

        val freed = e.deleteCacheOf(listOf(TransferKey.download(ref)), deleteLocalCopy = false)

        assertEquals(0L, freed)
        assertTrue("the resume source survived", src.exists())
        assertTrue("stays available", e.snapshotOf(TransferKey.download(ref)).isReady)
    }

    /** The user's original is not ours: never deleted, even after the send. */
    @Test
    fun USER_SOURCE_NEVER_DELETED() {
        val e = engine()
        val ref = "ref-gallery"
        val gallery = file("DCIM/IMG_1.jpg", bytes = 4096)
        repo.local[ref] = gallery
        e.present(ref, gallery.absolutePath, outgoing = true)

        val freed = e.deleteCacheOf(listOf(TransferKey.download(ref)), deleteLocalCopy = true)

        assertEquals(0L, freed)
        assertTrue("the gallery original is in place", gallery.exists())
        assertTrue("and stays available", e.snapshotOf(TransferKey.download(ref)).isReady)
    }

    /**
     * A FINISHED document is disposable again: the behaviour from before the cache
     * ownership work, once broken by treating any cache copy as untouchable.
     */
    @Test
    fun DOCUMENT_AFTER_SENT_IS_DISPOSABLE() {
        val e = engine()
        val ref = "ref-doc"
        val copy = file("cache/send_1_report.pdf", bytes = 8192)
        repo.local[ref] = copy
        e.present(ref, copy.absolutePath, outgoing = true)
        val key = TransferKey.download(ref)
        assertTrue("before deleting, the document is local", e.snapshotOf(key).isReady)

        // The send is done → the caller allows the copy.
        val freed = e.deleteCacheOf(listOf(key), deleteLocalCopy = true)

        assertEquals("real bytes were deleted", 8192L, freed)
        assertFalse("the finished send copy is deleted", copy.exists())
        assertTrue("a new download is offered", e.stateOf(key) is TransferState.CacheEvicted)
    }

    /** An ACTIVE send is the T4 resume source: not to be touched. */
    @Test
    fun ACTIVE_SEND_SOURCE_NOT_DELETED() {
        val e = engine()
        val ref = "ref-doc-active"
        val copy = file("cache/send_2_active.pdf", bytes = 8192)
        repo.local[ref] = copy
        e.present(ref, copy.absolutePath, outgoing = true)
        val key = TransferKey.download(ref)

        // The row is still queued → the caller forbids it.
        val freed = e.deleteCacheOf(listOf(key), deleteLocalCopy = false)

        assertEquals(0L, freed)
        assertTrue("the resume source survived", copy.exists())
        assertTrue("the document stays available", e.snapshotOf(key).isReady)
    }

    // ── incoming: the cache really goes ────────────────────────────────────

    @Test
    fun INCOMING_SINGLE_CLEAR_CACHE_BECOMES_REMOTE() {
        val e = engine()
        val ref = "ref-in"
        val cached = file("media/hash1", bytes = 4096)
        repo.cache[ref] = cached
        e.present(ref, null, outgoing = false)
        val key = TransferKey.download(ref)
        assertTrue(e.snapshotOf(key).isReady)

        val freed = e.deleteCacheOf(listOf(key), deleteLocalCopy = true)

        assertEquals("real bytes were deleted", 4096L, freed)
        assertFalse(cached.exists())
        assertTrue("a new download is offered", e.stateOf(key) is TransferState.CacheEvicted)
    }

    @Test
    fun INCOMING_ALBUM_CLEAR_CACHE_BECOMES_REMOTE() {
        val e = engine()
        val refs = (0..2).map { "ref-in-al-$it" }
        refs.forEachIndexed { i, r -> repo.cache[r] = file("media/h$i", bytes = 2048) }
        refs.forEach { e.present(it, null, outgoing = false) }

        val freed = e.deleteCacheOf(refs.map { TransferKey.download(it) }, deleteLocalCopy = true)

        assertEquals(3 * 2048L, freed)
        refs.forEach {
            assertTrue(
                "every member offers a download",
                e.stateOf(TransferKey.download(it)) is TransferState.CacheEvicted,
            )
        }
    }

    // ── an honest result ───────────────────────────────────────────────────

    @Test
    fun NO_FAKE_DELETE_SUCCESS() {
        val e = engine()
        val ref = "ref-own"
        repo.local[ref] = file("DCIM/own.jpg")
        e.present(ref, repo.local[ref]!!.absolutePath, outgoing = true)

        // An original being there is NOT proof of a deletion.
        assertEquals(
            "only deleted cache bytes count as success",
            0L, e.deleteCacheOf(listOf(TransferKey.download(ref)), deleteLocalCopy = true),
        )
    }

    // ── a clear never starts a download by itself ──────────────────────────

    @Test
    fun NO_AUTO_REDOWNLOAD_AFTER_CLEAR() {
        val e = engine()
        val ref = "ref-auto"
        repo.cache[ref] = file("media/h-auto")
        e.present(ref, null, outgoing = false)
        val key = TransferKey.download(ref)

        e.deleteCacheOf(listOf(key), deleteLocalCopy = true)
        assertTrue("the mark is set", repo.isCacheEvicted(ref))

        // The policy looks at this attachment again (scrolling, a network change, a
        // settings switch) and must stay silent.
        e.present(ref, null, outgoing = false)

        assertTrue(
            "a deleted file does not come back over the network by itself",
            e.stateOf(key) is TransferState.CacheEvicted,
        )
    }
}
