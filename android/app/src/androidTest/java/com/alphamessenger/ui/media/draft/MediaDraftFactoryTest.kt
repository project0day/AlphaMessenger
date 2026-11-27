package com.alphamessenger.ui.media.draft

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C3: taking in a URI. The factory is checked on real URIs: type, name, size, EXIF,
 * grants and the local copy.
 */
@RunWith(AndroidJUnit4::class)
class MediaDraftFactoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val created = mutableListOf<File>()

    @Before
    fun setUp() {
        File(context.cacheDir, "draft").deleteRecursively()
    }

    @After
    fun tearDown() {
        created.forEach { runCatching { it.delete() } }
        File(context.cacheDir, "draft").deleteRecursively()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun file(name: String, bytes: ByteArray = ByteArray(64) { it.toByte() }): File {
        val f = File(context.cacheDir, name)
        f.writeBytes(bytes)
        created += f
        return f
    }

    private fun jpeg(name: String, orientation: Int? = null): File {
        val f = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            val exif = android.media.ExifInterface(f.absolutePath)
            exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
        created += f
        return f
    }

    private fun ingest(
        uri: Uri,
        source: MediaDraftItem.Source = MediaDraftItem.Source.SHARE,
    ): Result<MediaDraftItem> = runBlocking { MediaDraftFactory.fromUri(context, uri, source) }

    private fun uri(case: String, f: File) = DraftTestProvider.uriFor(case, f)

    // ── T1..T5: type from the provider MIME ─────────────────────────────

    @Test
    fun t1_jpegIsImage() {
        val item = ingest(uri(DraftTestProvider.CASE_FULL, jpeg("t1.jpg"))).getOrThrow()
        assertEquals(MediaDraftItem.Kind.IMAGE, item.kind)
        assertEquals("image/jpeg", item.mimeType)
    }

    @Test
    fun t2_pngIsImage() {
        val f = File(context.cacheDir, "t2.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        created += f
        val item = ingest(uri(DraftTestProvider.CASE_FULL, f)).getOrThrow()
        assertEquals(MediaDraftItem.Kind.IMAGE, item.kind)
    }

    @Test
    fun t3_mp4IsVideo() {
        val item = ingest(uri(DraftTestProvider.CASE_FULL, file("t3.mp4"))).getOrThrow()
        assertEquals(MediaDraftItem.Kind.VIDEO, item.kind)
    }

    @Test
    fun t4_m4aIsAudio() {
        val item = ingest(uri(DraftTestProvider.CASE_FULL, file("t4.m4a"))).getOrThrow()
        assertEquals(MediaDraftItem.Kind.AUDIO, item.kind)
    }

    @Test
    fun t5_pdfIsFile() {
        val item = ingest(uri(DraftTestProvider.CASE_FULL, file("t5.pdf"))).getOrThrow()
        assertEquals(MediaDraftItem.Kind.FILE, item.kind)
        assertEquals("application/pdf", item.mimeType)
    }

    // ── T6, T7: generic MIME → type from the extension ─────────────────

    @Test
    fun t6_genericMimeWithJpgExtensionIsImage() {
        val item = ingest(uri(DraftTestProvider.CASE_GENERIC, jpeg("t6.jpg"))).getOrThrow()
        assertEquals(MediaDraftItem.Kind.IMAGE, item.kind)
        assertEquals("image/jpeg", item.mimeType)
    }

    @Test
    fun t7_genericMimeWithMp4ExtensionIsVideo() {
        val item = ingest(uri(DraftTestProvider.CASE_GENERIC, file("t7.mp4"))).getOrThrow()
        assertEquals(MediaDraftItem.Kind.VIDEO, item.kind)
        assertEquals("video/mp4", item.mimeType)
    }

    // ── T8, T9: the provider withholds name and size ────────────────────

    @Test
    fun t8_noDisplayNameFallsBackToSafeName() {
        val item = ingest(uri(DraftTestProvider.CASE_NO_NAME, file("t8.pdf"))).getOrThrow()
        val name = item.filename
        assertNotNull(name)
        assertTrue("the name must not be empty: $name", !name.isNullOrBlank())
        assertFalse("the name must hold no path separators: $name", name!!.contains('/'))
    }

    @Test
    fun t9_noSizeReportsUnknownNotZero() {
        val f = file("t9.pdf", ByteArray(4096))
        val item = ingest(uri(DraftTestProvider.CASE_NO_SIZE, f)).getOrThrow()
        assertTrue(
            "size unknown → SIZE_UNKNOWN or the descriptor size, never 0",
            item.sizeBytes == MediaDraftItem.SIZE_UNKNOWN || item.sizeBytes == 4096L,
        )
    }

    // ── T10: EXIF ─────────────────────────────────────────────────────────

    @Test
    fun t10_exifRotationIsRead() {
        val rotated = jpeg("t10.jpg", android.media.ExifInterface.ORIENTATION_ROTATE_90)
        val item = ingest(uri(DraftTestProvider.CASE_FULL, rotated)).getOrThrow()
        assertEquals(90, item.exifRotationDegrees)
        // The user edit is separate and empty by default.
        assertEquals(0, item.rotationDegrees)
        assertEquals(90, item.effectiveRotation)
    }

    // ── T11, T12: grants and the copy ───────────────────────────────────

    @Test
    fun t11_inAppSourceIsNotCopied() {
        val f = jpeg("t11.jpg")
        val src = uri(DraftTestProvider.CASE_FULL, f)
        val item = ingest(src, MediaDraftItem.Source.GALLERY).getOrThrow()
        assertEquals("an own source needs no copy", src, item.uri)
        assertFalse(item.deleteAfterUse)
    }

    @Test
    fun t12_shareWithoutPersistablePermissionIsCopied() {
        val f = jpeg("t12.jpg")
        val item = ingest(uri(DraftTestProvider.CASE_FULL, f), MediaDraftItem.Source.SHARE).getOrThrow()
        assertTrue("the copy must be marked for deletion", item.deleteAfterUse)
        assertEquals("file", item.uri.scheme)
        val copy = File(requireNotNull(item.uri.path))
        assertTrue("the copy must exist", copy.exists())
        assertTrue("the copy must not be empty", copy.length() > 0)
        assertNotNull("the original URI must be kept", item.originalUri)
    }

    // ── T13, T14 ──────────────────────────────────────────────────────────

    @Test
    fun t13_missingUriFailsWithoutCrash() {
        val result = ingest(uri(DraftTestProvider.CASE_GONE, File(context.cacheDir, "missing.pdf")))
        assertTrue("expected Result.failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaDraftFactory.Failure.Unreadable)
    }

    @Test
    fun t14_eachUriIsIngestedIndependently() {
        val good1 = uri(DraftTestProvider.CASE_FULL, jpeg("t14a.jpg"))
        val gone = uri(DraftTestProvider.CASE_GONE, File(context.cacheDir, "t14b.pdf"))
        val good2 = uri(DraftTestProvider.CASE_FULL, file("t14c.pdf"))

        val results = runBlocking {
            MediaDraftFactory.fromUris(context, listOf(good1, gone, good2), MediaDraftItem.Source.SHARE)
        }
        assertEquals(3, results.size)
        assertTrue(results[0].isSuccess)
        assertTrue(results[1].isFailure)
        assertTrue(results[2].isSuccess)
        assertEquals(MediaDraftItem.Kind.IMAGE, results[0].getOrThrow().kind)
        assertEquals(MediaDraftItem.Kind.FILE, results[2].getOrThrow().kind)
        // Ids are independent: the set can be reordered and pruned by them.
        assertFalse(results[0].getOrThrow().id == results[2].getOrThrow().id)
    }
}
