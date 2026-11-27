package com.alphamessenger.ui.chat

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.components.GlassState
import com.alphamessenger.ui.components.glassPanel
import com.alphamessenger.ui.theme.AlphaTheme

/** A bubble on screen (root coordinates) and its outline: the menu leaves exactly it undimmed. */
class BubbleSpot(val bounds: Rect, val shape: androidx.compose.ui.graphics.Shape)

/** Where a message's bubble sits on screen is reported here. */
val LocalBubbleBounds = androidx.compose.runtime.staticCompositionLocalOf<((BubbleSpot) -> Unit)?> { null }

private val SCRIM = Color.Black.copy(alpha = 0.45f)

/** "Message menu" (Appearance): beside the message (default) or the bottom panel. */
object MessageMenuPrefs {
    private const val PREFS = "ui_prefs"
    var popup by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        popup = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("msg_menu_popup", true)
    }

    fun set(ctx: Context, v: Boolean) {
        popup = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("msg_menu_popup", v).apply()
    }
}

/**
 * The menu beside a message: the chat dims except the message itself, one
 * frosted window opens next to it — the reactions in its top row (sliding
 * sideways), then every action. Below the message when it fits, else above,
 * else from the bottom of the screen. A tap outside or Back closes it.
 */
@Composable
fun MessageMenuPopup(
    anchor: Rect,
    /** The bubble's outline at [anchor], left undimmed (null = dim everything). */
    hole: androidx.compose.ui.graphics.Shape?,
    haze: GlassState,
    outgoing: Boolean,
    onDismiss: () -> Unit,
    reactions: (@Composable () -> Unit)?,
    /** The emoji are showing in place of the actions: the window keeps its size,
     *  and the body handles its own scrolling. */
    keepSize: Boolean = false,
    body: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var cardHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val colors = AlphaTheme.colorScheme
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(170), label = "menuAppear")

    val dark = colors.bgSecondary.luminance() <= 0.5f
    val tint = colors.bgSecondary.copy(alpha = if (dark) 0.55f else 0.74f)
    val edge = Color.White.copy(alpha = if (dark) 0.10f else 0.35f)
    val cardShape = RoundedCornerShape(20.dp)
    val statusTop = WindowInsets.statusBars.getTop(density)
    // An open keyboard stays open: the menu keeps to the chat above it.
    val navBottom = maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.ime.getBottom(density))

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = appear }
                .drawWithCache {
                    val cut = hole?.let { shape ->
                        val spot = anchor.translate(-origin.x, -origin.y)
                        Path().apply {
                            addOutline(shape.createOutline(spot.size, layoutDirection, this@drawWithCache))
                            translate(spot.topLeft)
                        }
                    }
                    onDrawBehind {
                        if (cut == null) drawRect(SCRIM)
                        else clipPath(cut, ClipOp.Difference) { drawRect(SCRIM) }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Column(
                    Modifier
                        .width(264.dp)
                        // Opened straight on the emoji: no actions were measured, so a set height.
                        .then(if (keepSize) Modifier.height(if (cardHeight > 0) with(density) { cardHeight.toDp() } else 360.dp) else Modifier)
                        .onSizeChanged { if (!keepSize) cardHeight = it.height }
                        .glassPanel(haze, cardShape, tint = tint, backdrop = colors.bgChat, blur = 22.dp)
                        .clip(cardShape)
                        .border(1.dp, edge, cardShape)
                        .graphicsLayer { alpha = appear; scaleX = 0.94f + 0.06f * appear; scaleY = 0.94f + 0.06f * appear },
                ) {
                    if (reactions != null) {
                        reactions()
                        androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = edge)
                    }
                    if (keepSize) Box(Modifier.fillMaxSize()) { body() }
                    else Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) { body() }
                }
            },
        ) { measurables, c ->
            val w = c.maxWidth
            val h = c.maxHeight
            val margin = 12.dp.roundToPx()
            val gap = 8.dp.roundToPx()
            val top = statusTop + gap
            val bottom = h - navBottom - gap
            val hole = anchor.translate(-origin.x, -origin.y)
            val card = measurables.first().measure(Constraints(maxWidth = w - 2 * margin, maxHeight = (bottom - top).coerceAtLeast(0)))
            val x = if (outgoing) w - margin - card.width else margin
            val below = hole.bottom.toInt() + gap
            val above = hole.top.toInt() - gap - card.height
            val y = when {
                below + card.height <= bottom -> below
                above >= top -> above
                else -> (bottom - card.height).coerceAtLeast(top)
            }
            layout(w, h) { card.place(x, y) }
        }
    }
}
