package com.alphamessenger.ui.chat.media.v2.transform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.alphamessenger.ui.chat.media.v2.gesture.GestureEvent
import com.alphamessenger.ui.chat.media.v2.motion.Motion
import com.alphamessenger.ui.chat.media.v2.motion.MotionController
import com.alphamessenger.ui.chat.media.v2.motion.rememberMotionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Double-tap zoom factor. */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * All frame-display arithmetic: pinch, panning the zoomed frame, drag-away.
 *
 * The controller receives input events and updates [TransformState] — that is
 * all. It does not close the viewer, page, or start a transition: `Dismiss` to
 * it only means "the drag passed the threshold", with no consequences of its
 * own.
 */
@Stable
class TransformController internal constructor(
    val state: TransformState,
    private val motion: MotionController,
    private val scope: CoroutineScope,
) {
    private var returning: Job? = null


    var canZoom by mutableStateOf(true)
        internal set

    /** Viewport size: needed to keep the frame inside its own edges. */
    var viewport by mutableStateOf(Size.Zero)
        internal set

    fun onEvent(event: GestureEvent) {
        when (event) {
            is GestureEvent.ScaleChanged -> if (canZoom) applyZoom(event.zoom, event.pan)
            is GestureEvent.PanChanged -> applyPan(event.delta)
            is GestureEvent.DragStarted -> {
                state.isDragging = true
                state.dragOffset = 0f
            }
            is GestureEvent.Drag -> state.dragOffset = event.total
            is GestureEvent.DragFinished -> {
                state.isDragging = false
                state.dragOffset = 0f
            }
            is GestureEvent.DoubleTap -> if (canZoom) toggleZoom()
            is GestureEvent.GestureEnded -> clampIntoBounds()
            else -> Unit
        }
    }

    /** Double tap changes only the scale; it opens and closes nothing. */
    fun toggleZoom() {
        if (state.isZoomed) reset() else zoomTo(DOUBLE_TAP_SCALE)
    }

    fun zoomTo(target: Float) {
        state.scale = target.coerceIn(TransformState.MIN_SCALE, TransformState.MAX_SCALE)
        if (!state.isZoomed) state.translation = Offset.Zero
    }

    /** Resets ONLY the frame display: page change, menu close, etc. */
    fun reset() {
        state.scale = TransformState.MIN_SCALE
        state.translation = Offset.Zero
        state.dragOffset = 0f
        state.isDragging = false
    }

    private fun applyZoom(zoomChange: Float, pan: Offset) {
        state.scale = (state.scale * zoomChange)
            .coerceIn(TransformState.MIN_SCALE, TransformState.MAX_SCALE)
        state.translation = if (state.isZoomed) state.translation + pan else Offset.Zero
    }

    private fun applyPan(delta: Offset) {
        if (!state.isZoomed) return
        state.translation += delta
    }

    /**
     * After a gesture the frame does not stay where it was dropped: at base
     * scale it returns to the center; zoomed, it is pulled back to the nearest
     * edge if it crossed one.
     *
     * The controller does not describe the motion itself — it names the kind,
     * and the motion engine knows how the frame gets there.
     */
    private fun clampIntoBounds() {
        val target = if (!state.isZoomed) {
            Offset.Zero
        } else {
            val maxX = (viewport.width * (state.scale - TransformState.MIN_SCALE)) / 2f
            val maxY = (viewport.height * (state.scale - TransformState.MIN_SCALE)) / 2f
            Offset(
                state.translation.x.coerceIn(-maxX, maxX),
                state.translation.y.coerceIn(-maxY, maxY),
            )
        }
        val from = state.translation
        if (from == target) return

        returning?.cancel()
        returning = scope.launch {
            motion.play(Motion.RESET_TRANSFORM) { t ->
                state.translation = Offset(
                    from.x + (target.x - from.x) * t,
                    from.y + (target.y - from.y) * t,
                )
            }
        }
    }
}

@Composable
fun rememberTransformController(
    state: TransformState,
    canZoom: Boolean,
    viewport: Size,
): TransformController {
    val motion = rememberMotionController()
    val scope = rememberCoroutineScope()
    val controller = remember(state, motion) { TransformController(state, motion, scope) }
    SideEffect {
        controller.canZoom = canZoom
        controller.viewport = viewport
    }
    return controller
}
