package com.alphamessenger.ui.chat.media.v2.transform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/** The only place that applies scale and translation; the content knows nothing of them. */
@Composable
fun TransformLayer(
    state: TransformState,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    content: @Composable () -> Unit,
) {
    // One call site for content: two branches would recreate the page on every
    // visibility change. clip = true is mandatory — otherwise the zoomed page
    // bleeds onto the neighbor and a piece of another photo shows in zoom.
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                val on = visible
                scaleX = if (on) state.scale else 1f
                scaleY = if (on) state.scale else 1f
                translationX = if (on) state.translation.x else 0f
                translationY = if (on) state.translation.y + state.dragOffset else 0f
                transformOrigin = TransformOrigin.Center
                clip = true
            },
    ) { content() }
}
