package com.alphamessenger.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.composed
import kotlin.math.abs

/**
 * A seek bar's own gesture: a tap seeks there, a drag scrubs along. The bar takes
 * the touch from its first moment, so holding a finger on it never becomes the
 * message's long press; a mostly vertical move is let go to the list's scroll.
 */
internal fun Modifier.seekGesture(onSeek: (Float) -> Unit): Modifier = composed {
    val slop = LocalViewConfiguration.current.touchSlop
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val width = size.width.coerceAtLeast(1).toFloat()
            fun at(x: Float) = (x / width).coerceIn(0f, 1f)
            var scrubbing = false
            var travelX = 0f
            var travelY = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (!scrubbing) onSeek(at(change.position.x))
                    change.consume()
                    break
                }
                val delta = change.positionChange()
                if (!scrubbing) {
                    travelX += delta.x
                    travelY += delta.y
                    if (abs(travelY) > slop && abs(travelY) > abs(travelX)) break
                    if (abs(travelX) > slop) scrubbing = true
                }
                if (scrubbing) {
                    change.consume()
                    onSeek(at(change.position.x))
                }
            }
        }
    }
}
