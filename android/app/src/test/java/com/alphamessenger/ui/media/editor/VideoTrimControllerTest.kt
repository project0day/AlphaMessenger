package com.alphamessenger.ui.media.editor

import com.alphamessenger.ui.media.draft.MediaDraftItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Математика отрезка. Те же правила, что и у рамки кадра: шаг считается от текущего. */
class VideoTrimControllerTest {

    private val duration = 20_000L
    private val full = VideoTrim()

    @Test
    fun endDefaultsToDuration() {
        assertEquals(duration, full.endOr(duration))
        assertEquals(duration, full.lengthMs(duration))
        assertFalse(full.hasTrim(duration))
    }

    @Test
    fun moveStartShiftsOnlyStart() {
        val t = VideoTrimController.moveStart(full, 3_000L, duration)
        assertEquals(3_000L, t.startMs)
        assertEquals(duration, t.endOr(duration))
        assertTrue(t.hasTrim(duration))
    }

    @Test
    fun moveEndShiftsOnlyEnd() {
        val t = VideoTrimController.moveEnd(full, -5_000L, duration)
        assertEquals(0L, t.startMs)
        assertEquals(15_000L, t.endOr(duration))
    }

    @Test
    fun repeatedMovesContinueFromCurrent() {
        var t = VideoTrimController.moveStart(full, 2_000L, duration)
        t = VideoTrimController.moveStart(t, 2_000L, duration)
        t = VideoTrimController.moveStart(t, 1_000L, duration)
        assertEquals(5_000L, t.startMs)
        t = VideoTrimController.moveEnd(t, -3_000L, duration)
        t = VideoTrimController.moveEnd(t, -2_000L, duration)
        assertEquals(15_000L, t.endOr(duration))
    }

    @Test
    fun startNeverCrossesEnd() {
        val t = VideoTrimController.moveStart(full, 999_000L, duration)
        assertTrue(t.startMs <= duration - VideoTrimController.MIN_LENGTH_MS)
        assertTrue(t.lengthMs(duration) >= VideoTrimController.MIN_LENGTH_MS)
    }

    @Test
    fun endNeverCrossesStart() {
        val started = VideoTrimController.moveStart(full, 10_000L, duration)
        val t = VideoTrimController.moveEnd(started, -999_000L, duration)
        assertTrue(t.endOr(duration) >= t.startMs + VideoTrimController.MIN_LENGTH_MS)
    }

    @Test
    fun moveKeepsLengthAndStaysInside() {
        val window = VideoTrimController.moveEnd(full, -12_000L, duration) // 0..8000
        val moved = VideoTrimController.move(window, 30_000L, duration)
        assertEquals(window.lengthMs(duration), moved.lengthMs(duration))
        assertEquals(duration, moved.endOr(duration))
        assertTrue(moved.startMs >= 0L)
    }

    @Test
    fun muteToggles() {
        assertTrue(VideoTrimController.toggleMute(full).muted)
        assertFalse(VideoTrimController.toggleMute(VideoTrimController.toggleMute(full)).muted)
    }

    @Test
    fun tinyTailIsNotTrim() {
        val t = VideoTrim(0L, duration - 10L)
        assertFalse("хвост в 10 мс — не обрезка", t.hasTrim(duration))
    }

    // Перевод отрезка в элемент набора здесь не проверяем: он требует android.net.Uri,
    // а в обычных тестах системных классов нет. Логика перевода тривиальна —
    // копирование трёх полей; ошибиться в ней негде, а тащить ради неё Robolectric
    // в сборку дороже, чем польза.
}