package com.alphamessenger.ui.media.preview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs

/**
 * Zooming the content is a SCREEN-ONLY action.
 *
 * No rotation, flip or crop lives here: those are frame edits, drawn by the
 * single renderer (`EditorImage`) — rotation here too would give a second
 * place transforming the frame its own way.
 *
 * Gestures: two fingers are always ours; one finger — only for a zoomed-in
 * frame. At normal scale we do not touch events: the pager takes them and
 * flips through the set.
 */
@Composable
internal fun ZoomableBox(
    resetKey: Any?,
    onZoomChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var viewport by remember(resetKey) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Double tap is animated, pinch is not: during a pinch the frame follows the fingers.
    var animate by remember(resetKey) { mutableStateOf(false) }
    val shownScale by animateFloatAsState(scale, tween(if (animate) 220 else 0), label = "zoomScale")
    val shownOffsetX by animateFloatAsState(offset.x, tween(if (animate) 220 else 0), label = "zoomX")
    val shownOffsetY by animateFloatAsState(offset.y, tween(if (animate) 220 else 0), label = "zoomY")

    LaunchedEffect(scale) { onZoomChange(scale > 1.01f) }

    fun clamp(candidate: Offset, forScale: Float): Offset {
        val maxX = (viewport.width * (forScale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (viewport.height * (forScale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        animate = true
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                            val target = Offset(
                                (viewport.width / 2f - tap.x) * (DOUBLE_TAP_SCALE - 1f),
                                (viewport.height / 2f - tap.y) * (DOUBLE_TAP_SCALE - 1f),
                            )
                            offset = clamp(target, DOUBLE_TAP_SCALE)
                        }
                    },
                )
            }
            .pointerInput(resetKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1.01f) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                animate = false
                                val next = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
                                offset = if (next <= 1.01f) Offset.Zero else clamp(offset + panChange, next)
                                scale = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = shownScale
                scaleY = shownScale
                translationX = shownOffsetX
                translationY = shownOffsetY
            },
        ) { content() }
    }

    LaunchedEffect(resetKey) {
        if (abs(scale - 1f) > 0.01f) {
            scale = 1f
            offset = Offset.Zero
        }
    }
}

private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_SCALE = 5f
