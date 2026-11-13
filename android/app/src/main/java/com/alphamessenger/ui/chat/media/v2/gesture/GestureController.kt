package com.alphamessenger.ui.chat.media.v2.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Faster than this, a release counts as a fling, not just a lift. */
private const val FLING_MIN_PX_PER_SEC = 400f

/**
 * The SINGLE input parser: touch, tap, double tap, long press, pinch, pan of
 * a zoomed frame, and vertical drag-to-dismiss.
 *
 * The controller decides nothing and keeps nothing between gestures. It only
 * publishes [GestureEvent]s — no scale, no offset; it merely ASKS whether the
 * frame is zoomed, to tell pan apart from drag.
 *
 * Everything lives in one [awaitEachGesture] because separate detectors
 * compete: the double-tap detector holds the touch awaiting a second press
 * and eats the start of a pinch.
 *
 * Events are read at [PointerEventPass.Initial] — earlier than the horizontal
 * pager scroll sees them in the Main pass. While the gesture is "ours", all
 * changes are consumed so nothing reaches the pager; hence the pager never
 * needs to be recreated by flipping flags mid-touch.
 */
@Stable
class GestureController internal constructor() {

    var canZoom by mutableStateOf(true)
        internal set

    var canDragToDismiss by mutableStateOf(true)
        internal set

    /** Past this drag distance [GestureEvent.DismissRequested] is emitted. */
    var dismissThresholdPx by mutableStateOf(0f)
        internal set

    /** Whether the frame is zoomed NOW. The gesture does not own the scale. */
    internal var isZoomed: () -> Boolean = { false }

    internal var listener: (GestureEvent) -> Unit = {}

    internal fun emit(event: GestureEvent) = listener(event)
}

@Composable
fun rememberGestureController(
    canZoom: Boolean,
    canDragToDismiss: Boolean,
    dismissThresholdPx: Float,
    isZoomed: () -> Boolean,
    onEvent: (GestureEvent) -> Unit,
): GestureController {
    val controller = remember { GestureController() }
    // Subscriber and settings are refreshed between frames: a stale closure
    // here would mean a gesture reporting to the wrong place.
    SideEffect {
        controller.canZoom = canZoom
        controller.canDragToDismiss = canDragToDismiss
        controller.dismissThresholdPx = dismissThresholdPx
        controller.isZoomed = isZoomed
        controller.listener = onEvent
    }
    return controller
}

/**
 * Input area. It draws nothing and moves nothing — it only reads fingers.
 *
 * [key] restarts the parser when the target of the gesture changes.
 */
fun Modifier.viewerGestures(
    controller: GestureController,
    key: Any,
    enabled: Boolean,
): Modifier = composed {
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlop = viewConfiguration.touchSlop
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis

    pointerInput(key, enabled, controller.canZoom, controller.canDragToDismiss) {
        if (!enabled) return@pointerInput
        val tracker = VelocityTracker()
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                tracker.resetTracking()
                tracker.addPosition(down.uptimeMillis, down.position)
                controller.emit(GestureEvent.Pressed(down.position))

                var pinching = false
                var dragging = false
                var movedBeyondSlop = false
                var longPressed = false
                var totalDx = 0f
                var totalDy = 0f
                var totalDrag = 0f
                var pointerCount = 1

                // Long press fires during the hold, not after release —
                // otherwise it's a slow tap, not a long press.
                val longPress = launch {
                    delay(longPressTimeout)
                    if (!movedBeyondSlop && pointerCount == 1) {
                        longPressed = true
                        controller.emit(GestureEvent.LongPress(down.position))
                    }
                }

                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    pointerCount = maxOf(pointerCount, pressed)

                    if (pressed >= 2 && controller.canZoom) pinching = true

                    val pan = event.calculatePan()
                    totalDx += pan.x
                    totalDy += pan.y

                    event.changes.firstOrNull { it.pressed }?.let {
                        tracker.addPosition(it.uptimeMillis, it.position)
                    }

                    if (!movedBeyondSlop &&
                        (kotlin.math.abs(totalDx) > touchSlop || kotlin.math.abs(totalDy) > touchSlop)
                    ) {
                        movedBeyondSlop = true
                        longPress.cancel()
                        // Vertical one-finger movement on an unzoomed frame
                        // is a drag-away: report the start BEFORE the first step.
                        if (!pinching && !controller.isZoomed() && controller.canDragToDismiss &&
                            kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                        ) {
                            dragging = true
                            controller.emit(GestureEvent.DragStarted)
                        }
                    }
                    if (pointerCount > 1) longPress.cancel()

                    when {
                        pinching -> {
                            controller.emit(GestureEvent.ScaleChanged(event.calculateZoom(), pan))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        controller.isZoomed() -> {
                            controller.emit(GestureEvent.PanChanged(pan))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        dragging -> {
                            totalDrag += pan.y
                            controller.emit(GestureEvent.Drag(pan.y, totalDrag))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })

                // The release was seen in the Initial pass, before the children.
                // Wait for the same event's Final pass: if a control under the
                // finger consumed it, the tap was that control's, not ours.
                val handledByChild = awaitPointerEvent(PointerEventPass.Final)
                    .changes.any { it.isConsumed }

                longPress.cancel()
                val velocity = runCatching { tracker.calculateVelocity() }.getOrDefault(Velocity.Zero)
                when {
                    // Long press already fired — this does not count as a tap.
                    longPressed -> controller.emit(GestureEvent.GestureEnded)

                    dragging -> {
                        if (kotlin.math.abs(velocity.y) > FLING_MIN_PX_PER_SEC) {
                            controller.emit(GestureEvent.Fling(velocity))
                        }
                        if (kotlin.math.abs(totalDrag) > controller.dismissThresholdPx) {
                            controller.emit(GestureEvent.DismissRequested(totalDrag, velocity))
                        } else {
                            controller.emit(GestureEvent.DragFinished(totalDrag, velocity))
                            controller.emit(GestureEvent.GestureEnded)
                        }
                    }

                    // Pinch or pan of a zoomed frame. The movement condition
                    // is mandatory: without it any touch on a zoomed frame
                    // would land here and the tap branch would never run.
                    pinching || (controller.isZoomed() && movedBeyondSlop) ->
                        controller.emit(GestureEvent.GestureEnded)

                    // No movement, no second finger — a tap. Wait for a
                    // possible second press; if none, emit a single tap.
                    handledByChild && !movedBeyondSlop -> controller.emit(GestureEvent.GestureEnded)

                    !movedBeyondSlop && pointerCount == 1 -> {
                        val second = withTimeoutOrNull(doubleTapTimeout) {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        }
                        if (second != null) {
                            second.consume()
                            controller.emit(
                                GestureEvent.DoubleTap(
                                    position = second.position,
                                    forward = second.position.x > size.width / 2f,
                                ),
                            )
                        } else {
                            controller.emit(GestureEvent.Tap(down.position))
                        }
                        controller.emit(GestureEvent.GestureEnded)
                    }

                    else -> controller.emit(GestureEvent.GestureEnded)
                }
            }
        }
    }
}
