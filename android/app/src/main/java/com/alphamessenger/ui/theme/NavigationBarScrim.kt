package com.alphamessenger.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * What lies under the navigation buttons. The system bar is transparent in every
 * window ([SystemBars]); here the app draws the screen's own colour, fading in from a
 * tenth a third above the bar to full near the bottom. Content scrolls under it and
 * fades out, and every screen meets the bar without a seam. A gesture bar is too thin
 * to need it.
 */
@Composable
internal fun NavigationBarScrim(base: Color, modifier: Modifier = Modifier) {
    val color = if (SystemBars.fullscreenOverlay) Color.Transparent else SystemBars.navColor ?: base
    val bar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    if (color.alpha == 0f || bar < MIN_BUTTON_BAR) return
    Spacer(
        modifier.drawBehind {
            val height = bar.toPx() * 1.33f
            val top = size.height - height
            drawRect(
                Brush.verticalGradient(
                    0f to color.copy(alpha = color.alpha * 0.1f),
                    0.88f to color,
                    startY = top,
                    endY = size.height,
                ),
                topLeft = Offset(0f, top),
                size = Size(size.width, height),
            )
        },
    )
}

/** Narrower than this is a gesture bar. */
private val MIN_BUTTON_BAR = 32.dp
