package com.alphamessenger.ui.chat.media.v2.gesture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity

/**
 * Everything the viewer learns about the user's fingers.
 *
 * An event is a FACT of input, not a command. `DismissRequested` does not mean
 * "close": it means "the finger dragged the frame past the threshold". What to
 * do with it is the subscriber's decision; the sender neither knows nor should.
 *
 * Events carry no accumulated scale or total offset: those belong to whoever
 * displays the frame, and there must be no second copy of those numbers.
 */
sealed interface GestureEvent {

    /** A finger touched the screen. */
    data class Pressed(val position: Offset) : GestureEvent

    /** Single tap: no second touch within the timeout. */
    data class Tap(val position: Offset) : GestureEvent

    /** Double tap. [forward] — right half of the input area. */
    data class DoubleTap(val position: Offset, val forward: Boolean) : GestureEvent

    /** Finger held past the system timeout without moving beyond slop. */
    data class LongPress(val position: Offset) : GestureEvent

    /** Pinch: [zoom] — per-frame factor, [pan] — pinch-center shift. */
    data class ScaleChanged(val zoom: Float, val pan: Offset) : GestureEvent

    /** One-finger pan of the frame. */
    data class PanChanged(val delta: Offset) : GestureEvent

    /** Start of a vertical drag-away of the frame. */
    object DragStarted : GestureEvent

    /** Drag step: [delta] — per frame, [total] — since the gesture start. */
    data class Drag(val delta: Float, val total: Float) : GestureEvent

    /** Finger released: drag over, threshold NOT crossed. */
    data class DragFinished(val total: Float, val velocity: Velocity) : GestureEvent

    /** Drag threshold crossed. A message, not a close. */
    data class DismissRequested(val total: Float, val velocity: Velocity) : GestureEvent

    /** Fast fling at the end of a gesture. The subscriber decides on the threshold. */
    data class Fling(val velocity: Velocity) : GestureEvent

    /** Gesture fully finished — the frame may be clamped back into bounds. */
    object GestureEnded : GestureEvent
}
