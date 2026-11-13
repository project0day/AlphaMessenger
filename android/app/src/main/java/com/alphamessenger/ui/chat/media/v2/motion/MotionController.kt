package com.alphamessenger.ui.chat.media.v2.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * The single owner of motion: no animation is started anywhere else in the
 * viewer. Subsystems name the KIND of motion; duration, curve, spring and
 * velocity live only in this layer.
 *
 * Motion is always normalized 0 -> 1; what changes along that fraction
 * (alpha, rect, offset) is decided by the caller in its callback, so one
 * engine serves both the hero flight and the zoom-bounds return.
 *
 * Retargeting mid-flight does not restart the motion — it continues from the
 * current position and velocity, otherwise the gesture would look like a jerk.
 */
@Stable
class MotionController internal constructor(val state: MotionState = MotionState()) {

    private val animatable = Animatable(0f)

    /** Play [motion] from [from] to [to]. [onFrame] fires every frame with the current fraction. */
    suspend fun play(
        motion: Motion,
        from: Float = 0f,
        to: Float = 1f,
        onFrame: (Float) -> Unit = {},
    ) {
        state.running = true
        state.finished = false
        state.target = to
        animatable.snapTo(from)
        state.current = from
        onFrame(from)
        animatable.animateTo(to, MotionSpec.of(motion), initialVelocity = state.velocity) {
            state.current = value
            state.velocity = velocity
            onFrame(value)
        }
        state.current = animatable.value
        state.velocity = 0f
        state.running = false
        state.finished = true
    }

    /** Change the target without interrupting the motion; velocity is preserved. */
    suspend fun retarget(
        motion: Motion,
        to: Float,
        onFrame: (Float) -> Unit = {},
    ) {
        state.target = to
        state.running = true
        state.finished = false
        animatable.animateTo(to, MotionSpec.of(motion), initialVelocity = state.velocity) {
            state.current = value
            state.velocity = velocity
            onFrame(value)
        }
        state.velocity = 0f
        state.running = false
        state.finished = true
    }

    /** Stop at the current point: no catch-up animation, no jumps. */
    suspend fun cancel() {
        animatable.stop()
        state.running = false
        state.velocity = 0f
    }
}

@Composable
fun rememberMotionController(): MotionController = remember { MotionController() }
