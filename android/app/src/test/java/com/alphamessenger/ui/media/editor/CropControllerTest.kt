package com.alphamessenger.ui.media.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Геометрия рамки обрезки. Проверяется без телефона — именно здесь ломалось.
 *
 * Главный тест — [repeatedResizeContinues]: повторный захват той же ручки должен
 * продолжаться с текущей рамки, а не возвращать её в исходное положение.
 */
class CropControllerTest {

    private val start = CropRect(0.1f, 0.1f, 0.9f, 0.9f)
    private val eps = 0.0005f

    private fun assertRect(expected: CropRect, actual: CropRect) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    @Test
    fun moveShiftsWholeRect() {
        assertRect(CropRect(0.15f, 0.1f, 0.95f, 0.9f), CropController.move(start, 0.05f, 0f))
    }

    @Test
    fun moveStopsAtBounds() {
        val moved = CropController.move(start, 0.5f, 0.5f)
        assertRect(CropRect(0.2f, 0.2f, 1f, 1f), moved)
        assertEquals(start.width, moved.width, eps)
        assertEquals(start.height, moved.height, eps)
    }

    @Test
    fun resizeLeftChangesOnlyLeft() {
        val r = CropController.resize(start, CropController.Handle.LEFT, 0.1f, 0.3f)
        assertRect(CropRect(0.2f, 0.1f, 0.9f, 0.9f), r)
    }

    @Test
    fun resizeRightChangesOnlyRight() {
        val r = CropController.resize(start, CropController.Handle.RIGHT, -0.2f, 0.3f)
        assertRect(CropRect(0.1f, 0.1f, 0.7f, 0.9f), r)
    }

    @Test
    fun resizeTopChangesOnlyTop() {
        val r = CropController.resize(start, CropController.Handle.TOP, 0.3f, 0.1f)
        assertRect(CropRect(0.1f, 0.2f, 0.9f, 0.9f), r)
    }

    @Test
    fun resizeBottomChangesOnlyBottom() {
        val r = CropController.resize(start, CropController.Handle.BOTTOM, 0.3f, -0.1f)
        assertRect(CropRect(0.1f, 0.1f, 0.9f, 0.8f), r)
    }

    @Test
    fun resizeCornersMoveBothSides() {
        assertRect(
            CropRect(0.2f, 0.2f, 0.9f, 0.9f),
            CropController.resize(start, CropController.Handle.TOP_LEFT, 0.1f, 0.1f),
        )
        assertRect(
            CropRect(0.1f, 0.2f, 0.8f, 0.9f),
            CropController.resize(start, CropController.Handle.TOP_RIGHT, -0.1f, 0.1f),
        )
        assertRect(
            CropRect(0.2f, 0.1f, 0.9f, 0.8f),
            CropController.resize(start, CropController.Handle.BOTTOM_LEFT, 0.1f, -0.1f),
        )
        assertRect(
            CropRect(0.1f, 0.1f, 0.8f, 0.8f),
            CropController.resize(start, CropController.Handle.BOTTOM_RIGHT, -0.1f, -0.1f),
        )
    }

    /** Тот самый баг: второй заход должен продолжать, а не начинать заново. */
    @Test
    fun repeatedResizeContinues() {
        val first = CropController.resize(start, CropController.Handle.TOP_LEFT, 0.1f, 0.1f)
        val second = CropController.resize(first, CropController.Handle.TOP_LEFT, 0.1f, 0.1f)
        assertRect(CropRect(0.3f, 0.3f, 0.9f, 0.9f), second)
        val third = CropController.resize(second, CropController.Handle.BOTTOM_RIGHT, -0.1f, -0.1f)
        assertRect(CropRect(0.3f, 0.3f, 0.8f, 0.8f), third)
    }

    @Test
    fun resizeNeverCollapsesBelowMinimum() {
        val r = CropController.resize(start, CropController.Handle.LEFT, 5f, 0f)
        assertTrue(r.width >= CropController.MIN_SIDE - eps)
        assertTrue(r.left < r.right)
    }

    @Test
    fun resizeStaysInsideFrame() {
        val r = CropController.resize(start, CropController.Handle.BOTTOM_RIGHT, 5f, 5f)
        assertTrue(r.right <= 1f + eps)
        assertTrue(r.bottom <= 1f + eps)
    }

    @Test
    fun applyRatioGivesRequestedShape() {
        for (ratio in CropRatio.ALL.mapNotNull { it.value }) {
            val r = CropController.applyRatio(start, ratio)
            assertEquals("ratio $ratio", ratio, r.width / r.height, 0.01f)
            assertTrue(r.left >= -eps && r.top >= -eps && r.right <= 1f + eps && r.bottom <= 1f + eps)
        }
    }

    /** Соотношение обязано держаться и ПОСЛЕ движения ручки, а не только при выборе. */
    @Test
    fun ratioSurvivesResize() {
        for (ratio in CropRatio.ALL.mapNotNull { it.value }) {
            var r = CropController.applyRatio(start, ratio)
            r = CropController.resize(r, CropController.Handle.BOTTOM_RIGHT, -0.05f, -0.05f, ratio)
            assertEquals("ratio $ratio after resize", ratio, r.width / r.height, 0.02f)
            r = CropController.resize(r, CropController.Handle.TOP_LEFT, 0.03f, 0.03f, ratio)
            assertEquals("ratio $ratio after second resize", ratio, r.width / r.height, 0.02f)
            assertTrue(r.left >= -eps && r.top >= -eps && r.right <= 1f + eps && r.bottom <= 1f + eps)
        }
    }

    @Test
    fun resetReturnsFullFrame() {
        assertTrue(CropController.reset().isFull)
    }

    @Test
    fun stateAppliesAndReadsBack() {
        val state = PhotoEditState(crop = CropRect(0.2f, 0.1f, 0.8f, 0.7f), rotationDegrees = 90, flipHorizontal = true)
        assertTrue(state.isDirty)
        assertEquals(1, state.flipMask)
    }

    // ── Последовательности жестов: именно они ломались, а не отдельные вызовы ──

    @Test
    fun repeatedResizeUsesLatestCropState() {
        var r = CropController.resize(start, CropController.Handle.TOP_LEFT, 0.05f, 0.05f)
        r = CropController.resize(r, CropController.Handle.TOP_LEFT, 0.05f, 0.05f)
        r = CropController.resize(r, CropController.Handle.TOP_LEFT, 0.05f, 0.05f)
        assertRect(CropRect(0.25f, 0.25f, 0.9f, 0.9f), r)
    }

    @Test
    fun repeatedResizeWithRatioUsesLatestCropState() {
        val ratio = 1f
        var r = CropController.applyRatio(start, ratio)
        val first = CropController.resize(r, CropController.Handle.BOTTOM_RIGHT, -0.05f, -0.05f, ratio)
        val second = CropController.resize(first, CropController.Handle.BOTTOM_RIGHT, -0.05f, -0.05f, ratio)
        assertTrue("вторая правка должна уменьшать дальше", second.width < first.width)
        assertEquals(ratio, second.width / second.height, 0.02f)
    }

    @Test
    fun moveThenResizeUsesMovedRect() {
        val moved = CropController.move(start, 0.05f, 0.05f)
        val resized = CropController.resize(moved, CropController.Handle.RIGHT, -0.1f, 0f)
        assertRect(CropRect(0.15f, 0.15f, 0.85f, 0.95f), resized)
    }

    @Test
    fun resizeThenMoveUsesResizedRect() {
        val resized = CropController.resize(start, CropController.Handle.LEFT, 0.1f, 0f)
        val moved = CropController.move(resized, -0.05f, 0f)
        assertRect(CropRect(0.15f, 0.1f, 0.85f, 0.9f), moved)
    }

    @Test
    fun ratioThenResizeThenResizeKeepsRatio() {
        for (ratio in CropRatio.ALL.mapNotNull { it.value }) {
            var r = CropController.applyRatio(start, ratio)
            r = CropController.resize(r, CropController.Handle.LEFT, 0.02f, 0f, ratio)
            r = CropController.resize(r, CropController.Handle.BOTTOM, 0f, -0.02f, ratio)
            assertEquals("ratio $ratio", ratio, r.width / r.height, 0.03f)
        }
    }

    // ── Сброс: обрезка и весь редактор — разные операции ───────────────────

    @Test
    fun cropResetDoesNotResetRotationOrFlip() {
        val state = PhotoEditState(
            crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f),
            rotationDegrees = 90,
            flipHorizontal = true,
            flipVertical = true,
            ratio = CropRatio.Square,
        )
        val afterCropReset = state.copy(crop = CropController.reset(), ratio = CropRatio.Free)
        assertTrue(afterCropReset.crop.isFull)
        assertEquals(90, afterCropReset.rotationDegrees)
        assertTrue(afterCropReset.flipHorizontal)
        assertTrue(afterCropReset.flipVertical)
    }

    @Test
    fun fullEditorResetResetsEverything() {
        val state = PhotoEditState(
            crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f),
            rotationDegrees = 270,
            flipHorizontal = true,
            flipVertical = true,
            ratio = CropRatio.Landscape169,
        ).reset()
        assertTrue(state.crop.isFull)
        assertEquals(0, state.rotationDegrees)
        assertTrue(!state.flipHorizontal && !state.flipVertical)
        assertEquals(CropRatio.Free, state.ratio)
        assertTrue(!state.isDirty)
    }

    @Test
    fun rotateResetsCropBecauseFrameChanges() {
        val rotated = PhotoEditState(crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)).rotatedRight()
        assertEquals(90, rotated.rotationDegrees)
        assertTrue("после поворота рамка считается от другого кадра", rotated.crop.isFull)
    }

    @Test
    fun transformKeepsOrderAndSwapsSidesOnQuarterTurn() {
        val t = EditorTransform(rotationDegrees = 90, crop = CropRect(0f, 0f, 1f, 1f))
        assertTrue(t.quarterTurn)
        assertEquals(1080f / 1920f, t.displayAspect(1920, 1080), 0.001f)
        val cropped = t.copy(crop = CropRect(0f, 0f, 0.5f, 1f))
        assertEquals(0.5f * 1080f / 1920f, cropped.displayAspect(1920, 1080), 0.001f)
    }
}