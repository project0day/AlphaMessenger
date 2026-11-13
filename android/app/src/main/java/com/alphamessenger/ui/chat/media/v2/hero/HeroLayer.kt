package com.alphamessenger.ui.chat.media.v2.hero

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * Draws the flying frame. It computes nothing, remembers nothing and controls
 * nothing: the rect, alpha and rotation are handed to it ready-made.
 *
 * Deliberately free of effects and animations — all animation lives in the
 * transition controller. The layer is fully stateless and can be thrown away
 * and rewritten without touching anything else.
 *
 * Coordinates count from the layer's top-left corner, hence the mandatory own
 * Box: the parent's alignment would otherwise shift the frame.
 */
@Composable
fun HeroLayer(
    bitmap: ImageBitmap?,
    rect: Rect?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    scale: Float = 1f,
    rotation: Float = 0f,
) {
    if (bitmap == null || rect == null) return
    val density = LocalDensity.current
    val width = with(density) { rect.width.toDp() }
    val height = with(density) { rect.height.toDp() }

    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .size(width, height)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                },
        )
    }
}
