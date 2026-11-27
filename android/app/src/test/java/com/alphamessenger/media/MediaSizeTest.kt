package com.alphamessenger.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weight contract. Checked here, not on a device: substituting one quantity
 * for another gave "first 0 / 350 MB, then 0 / 65 MB", the sources' weight in the
 * network progress denominator.
 */
class MediaSizeTest {

    @Test
    fun `no file yet means no network weight`() {
        val info = MediaSizeInfo(sourceBytes = 350_000_000, estimatedOutputBytes = 65_000_000)
        assertEquals(0L, info.forTransfer)
    }

    @Test
    fun `after measuring the network weight is the measured one, not the source`() {
        val info = MediaSizeInfo(sourceBytes = 350_000_000, estimatedOutputBytes = 65_000_000)
            .measured(63_900_000)
        assertEquals(63_900_000L, info.forTransfer)
    }

    @Test
    fun `before measuring the estimate is shown`() {
        val info = MediaSizeInfo(sourceBytes = 100, estimatedOutputBytes = 42)
        assertEquals(42L, info.display)
        assertFalse(info.isExact)
    }

    @Test
    fun `after measuring the measured weight is shown`() {
        val info = MediaSizeInfo(sourceBytes = 100, estimatedOutputBytes = 42).measured(40)
        assertEquals(40L, info.display)
        assertTrue(info.isExact)
    }

    @Test
    fun `zero and negative read the same`() {
        assertEquals("0 B", MediaSizeFormat.short(0))
        assertEquals("0 B", MediaSizeFormat.short(-1))
    }

    @Test
    fun `bytes kilobytes megabytes gigabytes`() {
        assertEquals("512 B", MediaSizeFormat.short(512))
        assertEquals("2 KB", MediaSizeFormat.short(2048))
        assertEquals("1.5 MB", MediaSizeFormat.short((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", MediaSizeFormat.short(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `the format does not follow the system locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.5 MB", MediaSizeFormat.short((1.5 * 1024 * 1024).toLong()))
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("1.5 MB", MediaSizeFormat.short((1.5 * 1024 * 1024).toLong()))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `an estimate is marked as about`() {
        val info = MediaSizeInfo(estimatedOutputBytes = 2048)
        assertEquals("≈ 2 KB", MediaSizeFormat.of(info))
    }

    @Test
    fun `a measured weight has no about mark`() {
        val info = MediaSizeInfo(estimatedOutputBytes = 2048).measured(2048)
        assertEquals("2 KB", MediaSizeFormat.of(info))
    }
}
