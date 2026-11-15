@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alphamessenger.ui.chat.bubbles

import com.alphamessenger.ui.theme.outBubble
import com.alphamessenger.ui.theme.outBubbleBrush
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * THE single bubble-shell geometry: EVERY bubble (text / image / video / file /
 * voice / album) applies this and nothing else for its outer geometry. No per-bubble
 * width / clip / background / padding calculation — tailed & tailless, photo &
 * video, 1x1 & group bubbles get IDENTICAL containers computed in ONE place.
 *
 *   BubbleTokens → contentPadding(isOutgoing, showTail) → bubbleShell
 *
 * The outer run tail-shift (aligning a tailless body edge with the tailed body)
 * lives ONCE in MessageLayout and is applied uniformly to every bubble, so it is
 * intentionally NOT duplicated here.
 */
@androidx.compose.runtime.Composable
fun Modifier.bubbleShell(
    isOutgoing: Boolean,
    showTail: Boolean,
    bubbleShape: Shape,
    bubbleColor: Color,
    maxWidth: Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    // Inner content padding. Defaults to the standard text/media rule; a bubble
    // whose padding legitimately differs (e.g. the file card's compact insets)
    // passes its own.
    contentPadding: PaddingValues = BubbleTokens.contentPadding(isOutgoing, showTail),
    /** Message id — publishes the bubble bounds for the viewer's element flight. */
    registerId: String? = null,
): Modifier = bubbleShellOf(
    isOutgoing, bubbleShape, bubbleColor, maxWidth, onTap, onLongPress, contentPadding, registerId,
    // An own bubble in its usual colour gets the theme's gradient (own themes).
    if (isOutgoing) com.alphamessenger.ui.theme.LocalAlphaColorScheme.current.let { cs ->
        cs.outBubbleBrush?.takeIf { bubbleColor == cs.outBubble }
    } else null,
)

private fun Modifier.bubbleShellOf(
    @Suppress("UNUSED_PARAMETER") isOutgoing: Boolean,
    bubbleShape: Shape,
    bubbleColor: Color,
    maxWidth: Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    contentPadding: PaddingValues,
    registerId: String?,
    fill: androidx.compose.ui.graphics.Brush?,
): Modifier = this
    .widthIn(max = maxWidth)
    .then(
        if (registerId != null) Modifier.onGloballyPositioned {
            com.alphamessenger.ui.chat.media.MediaSourceRegistry.put(registerId, it.boundsInWindow())
        } else Modifier,
    )
    .clipDrawing(bubbleShape)
    .then(if (fill != null) Modifier.background(fill) else Modifier.background(bubbleColor))
    .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    .padding(contentPadding)

/**
 * Clips what is drawn to [shape] without a clipping layer. A layer clip also
 * decides touches by the shape's path, and on a bubble many screens tall that
 * test misses: every tap inside a long text ("Read more", links) fell through
 * to the row and opened the menu. Touches are judged by the plain bounds here.
 */
private fun Modifier.clipDrawing(shape: Shape): Modifier = drawWithCache {
    val path = androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    onDrawWithContent { clipPath(path) { this@onDrawWithContent.drawContent() } }
}
