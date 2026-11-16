package com.alphamessenger.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.chat.v2.BottomSurfaceState
import com.alphamessenger.ui.chat.v2.PanelMode
import com.alphamessenger.ui.components.GlassState
import com.alphamessenger.ui.components.glassPanel
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * How the chat's bars look: floating glass pills (the top bar, the input field)
 * over a haze that the messages melt into under the status bar and above the
 * input, or the earlier full-width glass. Set in Appearance.
 */
object ChatPanels {
    private const val PREFS = "chat_panels"
    var floating by mutableStateOf(true)
        private set
    /** Glass blur, dp. */
    var blur by mutableFloatStateOf(18f)
        private set
    /** Haze strength 0–1 under the status bar and above the input. */
    var haze by mutableFloatStateOf(0.8f)
        private set
    /** How much of the panel colour lies over the blur, 0.2–0.9. */
    var opacity by mutableFloatStateOf(0.55f)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        floating = p.getBoolean("floating", true)
        blur = p.getFloat("blur", 18f)
        haze = p.getFloat("haze", 0.8f)
        opacity = p.getFloat("opacity", 0.55f)
    }

    fun set(ctx: Context, floating: Boolean = this.floating, blur: Float = this.blur, haze: Float = this.haze, opacity: Float = this.opacity) {
        this.floating = floating; this.blur = blur; this.haze = haze; this.opacity = opacity
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("floating", floating).putFloat("blur", blur).putFloat("haze", haze).putFloat("opacity", opacity).apply()
    }
}

/** Inside the floating top pill: the bars leave the status bar to the pill's own margin. */
val LocalFloatingTop = compositionLocalOf { false }

/** The status bar's height for a top bar, or none when it sits in the floating pill. */
@Composable
fun chatStatusPad(): Dp = if (LocalFloatingTop.current) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

private val TOP_PILL = RoundedCornerShape(29.dp)
private val INPUT_PILL = RoundedCornerShape(26.dp)

/** The chat's top: a floating glass pill over the haze, or the full-width glass. */
@Composable
fun ChatTopGlass(haze: GlassState, colors: AlphaColorScheme, content: @Composable ColumnScope.() -> Unit) {
    ChatPanels.load(LocalContext.current)
    val floating = ChatPanels.floating
    val modifier = if (floating) {
        Modifier.hazeTop(wallpaperEdge(com.alphamessenger.ui.theme.LocalChatWallpaper.current, colors, top = true), ChatPanels.haze)
            .statusBarsPadding()
            .padding(start = 10.dp, end = 10.dp, top = 6.dp)
            .glassPanel(haze, TOP_PILL, tint = tintOf(colors.bgPrimary, ChatPanels.opacity), backdrop = colors.bgChat, blur = ChatPanels.blur.dp)
    } else {
        Modifier.glassPanel(haze, RectangleShape, tint = colors.bgPrimary.copy(alpha = if (colors.bgPrimary.luminance() > 0.5f) 0.78f else 0.6f), backdrop = colors.bgChat)
    }
    CompositionLocalProvider(LocalFloatingTop provides floating) {
        Column(modifier) { content() }
    }
}

/** The bottom block: the haze only while the input floats; the full-width glass when a panel (emoji, attach) is open. */
fun Modifier.chatBottomGlass(haze: GlassState, colors: AlphaColorScheme, navBar: Dp, surface: BottomSurfaceState): Modifier = composed {
    ChatPanels.load(LocalContext.current)
    if (ChatPanels.floating && floats(surface)) {
        hazeBottom(wallpaperEdge(com.alphamessenger.ui.theme.LocalChatWallpaper.current, colors, top = false), ChatPanels.haze, navBar)
    } else {
        glassPanel(haze, RectangleShape, tint = colors.bgSecondary.copy(alpha = if (colors.bgSecondary.luminance() > 0.5f) 0.74f else 0.6f),
            backdrop = colors.bgChat, extendBottom = navBar)
    }
}

/** The input field as a floating glass pill (while nothing is open under it). */
fun Modifier.chatInputPill(haze: GlassState, colors: AlphaColorScheme, surface: BottomSurfaceState): Modifier = composed {
    if (ChatPanels.floating && floats(surface)) {
        padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)
            .glassPanel(haze, INPUT_PILL, tint = tintOf(colors.bgSecondary, ChatPanels.opacity), backdrop = colors.bgChat, blur = ChatPanels.blur.dp)
    } else this
}

/**
 * The colour the chat background has at its top or bottom edge, for the haze to
 * melt into: a grey band over a purple wallpaper would show as a band.
 */
@Composable
fun wallpaperEdge(w: com.alphamessenger.ui.theme.ChatWallpaper, colors: AlphaColorScheme, top: Boolean): Color {
    val dark = colors.bgPrimary.luminance() < 0.5f
    return when (w) {
        is com.alphamessenger.ui.theme.ChatWallpaper.Solid -> w.color
        is com.alphamessenger.ui.theme.ChatWallpaper.Gradient -> if (top) w.start else w.end
        is com.alphamessenger.ui.theme.ChatWallpaper.Custom -> if (top) w.colors.first() else w.colors.last()
        is com.alphamessenger.ui.theme.ChatWallpaper.Pattern -> w.spec.bg(dark).let { if (top) it.first() else it.last() }
        // A photo has no one colour: a soft dark veil reads over any of it.
        is com.alphamessenger.ui.theme.ChatWallpaper.Gallery -> Color.Black.copy(alpha = 0.55f)
        else -> colors.bgChat
    }
}

/** A panel's colour over the blur: lighter themes need a little more to stay readable. */
fun tintOf(c: Color, opacity: Float): Color =
    c.copy(alpha = (opacity + if (c.luminance() > 0.5f) 0.12f else 0f).coerceIn(0.15f, 0.95f))

private fun floats(surface: BottomSurfaceState): Boolean =
    surface.panelMode == PanelMode.NONE || surface.panelMode == PanelMode.KEYBOARD || surface.panelMode == PanelMode.RECORDING

/** A haze from the top edge down past the bar, where the messages fade out. */
internal fun Modifier.hazeTop(c: Color, strength: Float): Modifier = if (strength <= 0f) this else drawBehind {
    val reach = 36.dp.toPx()
    drawRect(
        Brush.verticalGradient(0f to c.copy(alpha = strength), 0.45f to c.copy(alpha = strength * 0.65f), 1f to Color.Transparent,
            startY = 0f, endY = size.height + reach),
        size = Size(size.width, size.height + reach),
    )
}

/** A haze from the bottom edge (under the navigation bar too) up past the input. */
internal fun Modifier.hazeBottom(c: Color, strength: Float, navBar: Dp): Modifier = if (strength <= 0f) this else drawBehind {
    val reach = 36.dp.toPx()
    val below = navBar.toPx()
    val top = -reach
    val h = size.height + reach + below
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 0.55f to c.copy(alpha = strength * 0.65f), 1f to c.copy(alpha = strength),
            startY = top, endY = top + h),
        topLeft = Offset(0f, top), size = Size(size.width, h),
    )
}
