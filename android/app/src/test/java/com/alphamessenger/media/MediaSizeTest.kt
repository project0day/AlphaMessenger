package com.alphamessenger.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт веса. Проверяется здесь, а не на устройстве, потому что именно
 * подмена одной величины другой давала «сначала 0 / 350 МБ, потом 0 / 65 МБ»:
 * в знаменатель сетевого прогресса попадал вес исходников.
 */
class MediaSizeTest {

    // ── Три величины не подменяют друг друга ──────────────────────────────

    @Test
    fun `пока файла нет сетевой вес равен нулю`() {
        val info = MediaSizeInfo(sourceBytes = 350_000_000, estimatedOutputBytes = 65_000_000)
        assertEquals(0L, info.forTransfer)
    }

    @Test
    fun `после измерения сетевой вес — измеренный, а не исходный`() {
        val info = MediaSizeInfo(sourceBytes = 350_000_000, estimatedOutputBytes = 65_000_000)
            .measured(63_900_000)
        assertEquals(63_900_000L, info.forTransfer)
    }

    @Test
    fun `до измерения показывается оценка`() {
        val info = MediaSizeInfo(sourceBytes = 100, estimatedOutputBytes = 42)
        assertEquals(42L, info.display)
        assertFalse(info.isExact)
    }

    @Test
    fun `после измерения показывается измеренное`() {
        val info = MediaSizeInfo(sourceBytes = 100, estimatedOutputBytes = 42).measured(40)
        assertEquals(40L, info.display)
        assertTrue(info.isExact)
    }

    // ── Формат ────────────────────────────────────────────────────────────

    @Test
    fun `ноль и отрицательное показываются одинаково`() {
        assertEquals("0 Б", MediaSizeFormat.short(0))
        assertEquals("0 Б", MediaSizeFormat.short(-1))
    }

    @Test
    fun `байты килобайты мегабайты гигабайты`() {
        assertEquals("512 Б", MediaSizeFormat.short(512))
        assertEquals("2 КБ", MediaSizeFormat.short(2048))
        // Запятая, а не точка: слова русские — значит и число русское. И оно не
        // должно меняться вместе с языком телефона.
        assertEquals("1,5 МБ", MediaSizeFormat.short((1.5 * 1024 * 1024).toLong()))
        assertEquals("2,00 ГБ", MediaSizeFormat.short(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `формат не зависит от локали системы`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("1,5 МБ", MediaSizeFormat.short((1.5 * 1024 * 1024).toLong()))
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1,5 МБ", MediaSizeFormat.short((1.5 * 1024 * 1024).toLong()))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `оценка помечается знаком около`() {
        val info = MediaSizeInfo(estimatedOutputBytes = 2048)
        assertEquals("≈ 2 КБ", MediaSizeFormat.of(info))
    }

    @Test
    fun `измеренное показывается без знака около`() {
        val info = MediaSizeInfo(estimatedOutputBytes = 2048).measured(2048)
        assertEquals("2 КБ", MediaSizeFormat.of(info))
    }
}
