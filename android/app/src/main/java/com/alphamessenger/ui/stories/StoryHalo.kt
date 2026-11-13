package com.alphamessenger.ui.stories

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.theme.AlphaTheme

/** Story rings by chat id, for avatars deep in lists (chat rows). */
val LocalStoryRings = androidx.compose.runtime.staticCompositionLocalOf<Map<String, StoryRing>> { emptyMap() }

/**
 * Ring around an avatar that has stories, split into one arc per story: in the
 * theme's colours while any is unseen, a thin quiet grey once all are seen.
 * [content] is the avatar, drawn inset inside the ring.
 */
@Composable
fun StoryHalo(
    ring: StoryRing?,
    size: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (ring == null || ring.count <= 0) {
        Box(modifier.size(size), contentAlignment = Alignment.Center, content = content)
        return
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        StoryRingOverlay(ring, Modifier.matchParentSize())
        Box(Modifier.padding(if (ring.unseen) 5.dp else 4.dp), contentAlignment = Alignment.Center, content = content)
    }
}

/** The ring itself, drawn over a fixed-size avatar (the caller shrinks the avatar). */
@Composable
fun StoryRingOverlay(ring: StoryRing, modifier: Modifier = Modifier) {
    val cs = AlphaTheme.colorScheme
    Canvas(modifier) {
        val strokePx = (if (ring.unseen) 2.4.dp else 1.5.dp).toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(inset, inset)
        val n = ring.count.coerceIn(1, 12)
        val gapDeg = if (n > 1) 9f else 0f
        val seg = 360f / n
        val brush = if (ring.unseen) Brush.linearGradient(listOf(cs.msgOutStart, cs.accent, cs.msgOutEnd))
        else Brush.linearGradient(listOf(Color(0xFF6A6B74), Color(0xFF6A6B74)))
        repeat(n) { i ->
            drawArc(brush, -90f + i * seg + gapDeg / 2f, seg - gapDeg, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
        }
    }
}
