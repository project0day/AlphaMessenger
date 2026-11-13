package com.alphamessenger.ui.chat.media.v2.transform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * The single storage of "how the frame is currently shown": zoom level,
 * translation, and how far it is being dragged away.
 *
 * A data holder and nothing more — no coroutines, animations or math. The
 * controller computes, the layer draws; there is no second owner of the scale.
 */
@Stable
class TransformState {

    var scale by mutableStateOf(MIN_SCALE)
        internal set

    var translation by mutableStateOf(Offset.Zero)
        internal set

    /** Vertical drag-away. Separate from [translation]: a different gesture. */
    var dragOffset by mutableStateOf(0f)
        internal set

    var isDragging by mutableStateOf(false)
        internal set

    val isZoomed: Boolean get() = scale > MIN_SCALE

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}

@Composable
fun rememberTransformState(key: Any? = Unit): TransformState = remember(key) { TransformState() }
