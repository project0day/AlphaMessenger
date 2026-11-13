package com.alphamessenger.ui.chat.media.v2.hero

import androidx.compose.ui.geometry.Rect

/**
 * Where the frame flies to and from. Nothing but two rects and a point
 * between them: no View, no bubble, no source registry — whoever opens the
 * viewer computes their geometry and hands it over ready-made.
 *
 * The rects are captured ONCE: recomputing mid-flight would mean the frame
 * chasing its own layout.
 */
data class HeroGeometry(
    val startRect: Rect,
    val endRect: Rect,
) {
    /** Rect at a transition fraction: 0 — start, 1 — end. */
    fun currentRect(progress: Float): Rect {
        val t = progress.coerceIn(0f, 1f)
        return Rect(
            left = startRect.left + (endRect.left - startRect.left) * t,
            top = startRect.top + (endRect.top - startRect.top) * t,
            right = startRect.right + (endRect.right - startRect.right) * t,
            bottom = startRect.bottom + (endRect.bottom - startRect.bottom) * t,
        )
    }

    /** Return flight — the same rects, swapped. */
    fun reversed(): HeroGeometry = HeroGeometry(endRect, startRect)

    val isEmpty: Boolean
        get() = startRect.width <= 0f || startRect.height <= 0f ||
            endRect.width <= 0f || endRect.height <= 0f
}
