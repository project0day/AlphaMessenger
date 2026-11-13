package com.alphamessenger.ui.chat.media.v2.hero

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.alphamessenger.ui.chat.media.v2.motion.Motion
import com.alphamessenger.ui.chat.media.v2.motion.MotionController
import com.alphamessenger.ui.chat.media.v2.motion.rememberMotionController

/** What the transition reports outward. Notifications, not commands. */
sealed interface HeroEvent {

    /**
     * Flight started. [enabled] = false means no ready frame was found and
     * there is nothing to draw — the subscriber may show the content at once.
     */
    data class Started(val mediaId: String, val enabled: Boolean, val exiting: Boolean) : HeroEvent

    /** Transition fraction, 0..1. */
    data class Progress(val value: Float) : HeroEvent

    /** The frame has landed. What happens next is the subscriber's call. */
    data class Finished(val mediaId: String, val exiting: Boolean) : HeroEvent
}

/**
 * The frame flight — and nothing else.
 *
 * The controller owns three things: the frame, the geometry and the transition
 * fraction. It knows nothing about what lies beneath or who listens; it neither
 * opens nor removes the viewer — it only publishes [HeroEvent], and the
 * subscriber decides.
 *
 * Dimming the source belongs to the controller alone. The order is strict: the
 * source dims BEFORE takeoff and relights ONLY after the return flight lands —
 * otherwise the bubble and the flying image would both be visible for a frame.
 */
@Stable
class HeroController internal constructor(
    private val bitmaps: HeroBitmapProvider,
    private val motion: MotionController,
    val state: HeroState = HeroState(),
) {
    internal var listener: (HeroEvent) -> Unit = {}

    /** Dim the source (id) or restore it (null). How is not our business. */
    internal var placeholder: (String?) -> Unit = {}

    /** Whose source is currently dimmed. Outlives the frame: the frame is released at once. */
    private var activeId: String? = null

    private var lastGeometry: HeroGeometry? = null

    /** Takeoff: from the source to the screen. */
    suspend fun enter(mediaId: String, geometry: HeroGeometry?) {
        val geo = geometry?.takeIf { !it.isEmpty }
        val frame = bitmaps.cached(mediaId)

        activeId = mediaId
        lastGeometry = geo
        placeholder(mediaId)

        state.begin(mediaId, frame, geo, HeroPhase.ENTERING, 0f)
        listener(HeroEvent.Started(mediaId, state.isEnabled, exiting = false))

        // The flight can be cut short (cancelled coroutine, recreated
        // container). The frame MUST disappear in ANY case: otherwise it would
        // freeze over content that stays hidden forever.
        try {
            if (state.isEnabled) {
                motion.play(Motion.HERO_OPEN) { value ->
                    state.progress = value
                    listener(HeroEvent.Progress(value))
                }
            }
        } finally {
            // The frame has landed — release the image. The transition does
            // not exist during viewing; the source stays dimmed meanwhile.
            state.clear()
            listener(HeroEvent.Finished(mediaId, exiting = false))
        }
    }

    /** Return flight: from the screen to the source. Default geometry — the same, reversed. */
    suspend fun exit(geometry: HeroGeometry? = null) {
        val mediaId = activeId ?: return
        val geo = (geometry ?: lastGeometry?.reversed())?.takeIf { !it.isEmpty }
        val frame = bitmaps.cached(mediaId)

        state.begin(mediaId, frame, geo, HeroPhase.EXITING, 0f)
        listener(HeroEvent.Started(mediaId, state.isEnabled, exiting = true))

        try {
            if (state.isEnabled) {
                motion.play(Motion.HERO_CLOSE) { value ->
                    state.progress = value
                    listener(HeroEvent.Progress(value))
                }
            }
        } finally {
            state.clear()
            // The source relights ONLY here — after landing.
            placeholder(null)
            activeId = null
            lastGeometry = null
            listener(HeroEvent.Finished(mediaId, exiting = true))
        }
    }

    /**
     * Switch the source without starting a flight: the user paged to another
     * item, so a different bubble must dim and the return flight will target
     * it. Dimming stays here — outsiders still cannot toggle it.
     */
    fun retarget(mediaId: String) {
        if (activeId == mediaId) return
        activeId = mediaId
        lastGeometry = null
        placeholder(mediaId)
    }

    /** Abort the flight and restore the source. No events: no decisions here. */
    fun cancel() {
        state.clear()
        placeholder(null)
        activeId = null
        lastGeometry = null
    }
}

@Composable
fun rememberHeroController(
    bitmaps: HeroBitmapProvider,
    onEvent: (HeroEvent) -> Unit = {},
    onPlaceholder: (String?) -> Unit = {},
): HeroController {
    val motion = rememberMotionController()
    val controller = remember(bitmaps, motion) { HeroController(bitmaps, motion) }
    SideEffect {
        controller.listener = onEvent
        controller.placeholder = onPlaceholder
    }
    return controller
}
