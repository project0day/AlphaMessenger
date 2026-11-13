package com.alphamessenger.ui.chat.bubbles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * Reactions of one message, handed to whichever bubble renders it.
 *
 * other messengers puts the reaction pills INSIDE the bubble, next to the time. Every
 * bubble type draws its own footer, so each one places [BubbleReactionsRow]
 * where it fits; [rendered] tells the bubble host whether some footer did, so
 * a type without a slot (contact card, view-once) still shows them under it.
 */
@Stable
class BubbleReactionsState(
    /** The reactions it already had when it came into view: those stand still, newer ones play. */
    val seenAtStart: Set<String> = emptySet(),
    val reactions: Map<String, Int>,
    val mine: Set<String>,
    val onToggle: (String) -> Unit,
    val onOpenPicker: () -> Unit,
    /** Who reacted with what (the "+N" pill and a long-press). */
    val onShowAll: () -> Unit = onOpenPicker,
) {
    var rendered: Boolean = false
}

val LocalBubbleReactions = staticCompositionLocalOf<BubbleReactionsState?> { null }

/**
 * Where the pills' last row ends (px), written while they are measured. A
 * bubble that measures the row reads it right after, to put its time at the
 * end of that row when it fits — the pills themselves use the full width.
 */
class ReactionRowInfo {
    var lastRowEnd: Int = 0
}

val LocalReactionRowInfo = staticCompositionLocalOf<ReactionRowInfo?> { null }

private const val MAX_PILLS = 14

/** How many times a new reaction plays in its pill. */
private const val REACTION_PLAYS = 1

enum class ReactionSurface {
    /** On the bubble colour (text, file, voice, captions). */
    Bubble,
    /** Over a photo or video, on a dark scrim. */
    Media,
}

/** Reaction pills of the current message (no-op when it has none). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BubbleReactionsRow(
    isOutgoing: Boolean,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
    surface: ReactionSurface = ReactionSurface.Bubble,
) {
    // What was there when the message came into view stands still; a reaction that
    // comes while it is on screen plays. Read before anything can return early.
    val state = LocalBubbleReactions.current ?: return
    val seen = state.seenAtStart
    if (state.reactions.isEmpty()) return
    state.rendered = true
    val info = LocalReactionRowInfo.current
    androidx.compose.ui.layout.Layout(
        modifier = modifier,
        content = {
        // Bounded however many people react: the 6 most popular, then "+N".
        val sorted = state.reactions.entries.sortedByDescending { it.value }
        sorted.take(MAX_PILLS).forEach { (emoji, count) ->
            ReactionPill(emoji, count, emoji in state.mine, isOutgoing, colors, surface, fresh = emoji !in seen,
                onClick = { state.onToggle(emoji) }, onLongClick = state.onShowAll)
        }
        if (sorted.size > MAX_PILLS) {
            ReactionPill("+${sorted.size - MAX_PILLS}", 1, false, isOutgoing, colors, surface,
                onClick = state.onShowAll, onLongClick = state.onShowAll)
        }
        },
    ) { measurables, constraints ->
        // Rows filled left to right over the whole width given.
        val gap = 4.dp.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val positions = ArrayList<androidx.compose.ui.unit.IntOffset>(placeables.size)
        var x = 0
        var y = 0
        var rowH = 0
        var width = 0
        placeables.forEach { p ->
            if (x > 0 && x + p.width > constraints.maxWidth) { x = 0; y += rowH + gap; rowH = 0 }
            positions += androidx.compose.ui.unit.IntOffset(x, y)
            x += p.width + gap
            rowH = maxOf(rowH, p.height)
            width = maxOf(width, x - gap)
        }
        info?.lastRowEnd = (x - gap).coerceAtLeast(0)
        layout(width, y + rowH) {
            placeables.forEachIndexed { i, p -> p.placeRelative(positions[i]) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactionPill(
    emoji: String,
    count: Int,
    mine: Boolean,
    isOutgoing: Boolean,
    colors: AlphaColorScheme,
    surface: ReactionSurface,
    /** Came while the message is on screen: plays at once. */
    fresh: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Pills tinted by the THEME, never a flat white blob and never the
    // bubble's own colour: on our bubble a dark glass made of the theme's
    // background tones; on an incoming one our reaction wears the theme's
    // bubble gradient, others a faint accent.
    val grad = { a: Color, b: Color -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(a, b)) }
    val (bg, fg) = when (surface) {
        ReactionSurface.Media ->
            (if (mine) grad(colors.msgOutStart, colors.msgOutEnd)
            else androidx.compose.ui.graphics.SolidColor(Color.Black.copy(alpha = 0.45f))) to Color.White
        ReactionSurface.Bubble -> if (isOutgoing) {
            (if (mine) grad(colors.bgSecondary.copy(alpha = 0.36f), colors.bgTertiary.copy(alpha = 0.22f))
            else grad(colors.bgSecondary.copy(alpha = 0.16f), colors.bgSecondary.copy(alpha = 0.09f))) to Color.White
        } else {
            (if (mine) grad(colors.msgOutStart.copy(alpha = 0.42f), colors.msgOutEnd.copy(alpha = 0.28f))
            else androidx.compose.ui.graphics.SolidColor(colors.accent.copy(alpha = 0.12f))) to colors.accent
        }
    }
    Row(
        modifier = Modifier
            .height(28.dp)
            // Shaped, but not clipping: a reaction growing out of it must not be cut.
            .background(bg, RoundedCornerShape(14.dp))
            .combinedClickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Still in the list; a new one (or one more of it) grows out of the pill, plays, settles back.
        ReactionEmoji(emoji, count, fresh)
        if (count > 1) {
            Spacer(Modifier.width(4.dp))
            Text(count.toString(), color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}


/** How far a new reaction grows out of its pill (drawn at that size, so it stays sharp). */
private val POP_SIZE = 36.dp
private const val POP_HOLD_MS = 1000L
private const val POP_SETTLE_MS = 300

/**
 * The emoji of a pill. When the reaction is new or its count grows, the emoji itself
 * grows out of the pill, plays and settles back into its place. It grows inside the
 * list, so it scrolls with the message and goes under the bars like everything else.
 * Reactions already there when the message came into view stay still.
 */
@Composable
private fun ReactionEmoji(emoji: String, count: Int, fresh: Boolean) {
    val firstCount = androidx.compose.runtime.remember { count }
    val small = 22f / POP_SIZE.value
    var pops by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(if (fresh) 1 else 0) }
    androidx.compose.runtime.LaunchedEffect(count) { if (count > firstCount) pops++ }
    val scale = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(small) }
    // Settling starts after the hold, or at once when the list moves: a grown reaction never scrolls along.
    var settle by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(pops) {
        if (pops == 0) return@LaunchedEffect
        scale.snapTo(small)
        scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f))
        kotlinx.coroutines.delay(POP_HOLD_MS)
        settle++
    }
    androidx.compose.runtime.LaunchedEffect(settle) {
        if (settle > 0) scale.animateTo(small, androidx.compose.animation.core.tween(POP_SETTLE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val lastY = androidx.compose.runtime.remember { floatArrayOf(Float.NaN) }
    Box(
        Modifier.size(22.dp).onGloballyPositioned {
            val y = it.positionInWindow().y
            if (!lastY[0].isNaN() && kotlin.math.abs(y - lastY[0]) > 2f && scale.value > small + 0.01f) settle++
            lastY[0] = y
        },
        contentAlignment = Alignment.Center,
    ) {
        com.alphamessenger.ui.emoji.AnimatedEmojiView(
            emoji, POP_SIZE,
            Modifier.requiredSize(POP_SIZE).graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                // Grows upward out of the pill; at rest (no growth) it sits exactly in the pill's centre.
                translationY = -(scale.value - small) / (1f - small) * POP_SIZE.toPx() * 0.25f
            },
            plays = REACTION_PLAYS, replay = pops, autoPlay = fresh,
            fallbackModifier = Modifier,
            fallback = { Text(emoji, fontSize = 16.sp) },
        )
    }
}
