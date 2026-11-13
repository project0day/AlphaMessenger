package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * Quick reactions beside the one selected message. Place it inside the message's row:
 * the card rides with the row while the list scrolls, above the bubble when there is
 * room under the selection bar, else below it. It takes no focus and no outside taps,
 * so the selection keeps working around it.
 */
@Composable
fun SelectionReactions(
    visible: Boolean,
    outgoing: Boolean,
    emoji: List<String>,
    mine: Collection<String>,
    colors: AlphaColorScheme,
    onReact: (String) -> Unit,
    onMore: (() -> Unit)?,
) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    val side = with(density) { 10.dp.roundToPx() }
    val gap = with(density) { 6.dp.roundToPx() }
    // Status bar plus the selection bar: the card never covers them.
    val minTop = WindowInsets.statusBars.getTop(density) + with(density) { 56.dp.roundToPx() }
    val place = remember(outgoing, side, gap, minTop) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = if (outgoing) anchorBounds.right - side - popupContentSize.width else anchorBounds.left + side
                val above = anchorBounds.top - gap - popupContentSize.height
                val y = if (above >= minTop) above else anchorBounds.bottom + gap
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y)
            }
        }
    }
    Popup(popupPositionProvider = place, properties = PopupProperties(focusable = false)) {
        val origin = TransformOrigin(if (outgoing) 1f else 0f, 1f)
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(tween(150)) + scaleIn(tween(180), initialScale = 0.85f, transformOrigin = origin),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f, transformOrigin = origin),
        ) {
            val dark = colors.bgSecondary.luminance() <= 0.5f
            val shape = RoundedCornerShape(24.dp)
            Row(
                Modifier.clip(shape)
                    .background(colors.bgSecondary.copy(alpha = 0.96f))
                    .border(0.5.dp, Color.White.copy(alpha = if (dark) 0.12f else 0.4f), shape)
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                emoji.forEach { e ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (e in mine) colors.accent.copy(alpha = 0.28f) else Color.Transparent)
                            .clickable { onReact(e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 23.sp) }
                }
                if (onMore != null) Box(
                    Modifier.padding(start = 2.dp).size(32.dp).clip(CircleShape).background(colors.bgTertiary).clickable(onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AlphaIcons.Filled.Add, stringResource(R.string.chat_reaction_more), tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
