package com.alphamessenger.ui.chat.media.v2.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** All viewer motion kinds; there is no other motion in the system. */
enum class Motion {
    /** Frame flies from the source onto the screen. */
    HERO_OPEN,

    /** Frame flies from the screen back to its source. */
    HERO_CLOSE,

    /** Close by finger swipe. */
    DISMISS,

    /** Frame released — return it to the allowed bounds. */
    RESET_TRANSFORM,

    /** Zoom change without a finger (double tap, reset). */
    ZOOM,

    /** Edge resistance: the frame goes no further. */
    OVERSCROLL,

    /** Background dimming. */
    BACKGROUND_FADE,

    /** Show/hide of chrome panels. */
    CHROME_FADE,
}

/**
 * The single place in the project with durations, curves and stiffnesses:
 * scattered `tween(300)` values would make every subsystem move differently.
 *
 * Rule: what the user "throws" with a finger returns via a spring; guided
 * transitions are time-based.
 */
object MotionSpec {

    /** Hero flight duration: open, close, dismiss. */
    const val HERO_MS = 300

    /** Chrome panels show/hide. */
    const val CHROME_MS = 180

    /** Fast start, noticeably longer settle into the landing point. */
    private val HeroEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> of(motion: Motion): FiniteAnimationSpec<T> = when (motion) {
        Motion.HERO_OPEN, Motion.HERO_CLOSE, Motion.DISMISS ->
            tween(HERO_MS, easing = HeroEasing)

        Motion.RESET_TRANSFORM -> spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )

        Motion.ZOOM -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )

        Motion.OVERSCROLL -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )

        Motion.BACKGROUND_FADE -> tween(HERO_MS, easing = HeroEasing)

        Motion.CHROME_FADE -> tween(CHROME_MS, easing = FastOutSlowInEasing)
    }
}
