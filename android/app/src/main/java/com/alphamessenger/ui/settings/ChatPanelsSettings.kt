package com.alphamessenger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.chat.ChatPanels
import androidx.compose.animation.core.animateFloat
import com.alphamessenger.ui.chat.hazeBottom
import com.alphamessenger.ui.chat.hazeTop
import com.alphamessenger.ui.components.glassPanel
import com.alphamessenger.ui.components.glassSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import com.alphamessenger.ui.theme.AlphaColorScheme

/** Appearance → the chat's bars: floating glass pills or full width, and how the glass and the haze look. */
@Composable
fun ChatPanelsSettings(colors: AlphaColorScheme) {
    val ctx = LocalContext.current
    ChatPanels.load(ctx)
    com.alphamessenger.ui.components.OptionGroup {
        Text(stringResource(R.string.chat_panels), color = colors.textPrimary, fontSize = 15.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
        Text(stringResource(R.string.chat_panels_hint), color = colors.textSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp))
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to R.string.chat_panels_floating, false to R.string.chat_panels_full).forEach { (f, label) ->
                val on = ChatPanels.floating == f
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) colors.accent else colors.bgTertiary)
                        .clickable { ChatPanels.set(ctx, floating = f) },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(label), color = if (on) Color.White else colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
        if (ChatPanels.floating) PanelsPreview(colors)
        if (ChatPanels.floating) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            Setting(stringResource(R.string.chat_panels_blur), ChatPanels.blur, 4f..32f, colors, "%.0f dp") { ChatPanels.set(ctx, blur = it) }
            Setting(stringResource(R.string.chat_panels_haze), ChatPanels.haze, 0f..1f, colors, null) { ChatPanels.set(ctx, haze = it) }
            Setting(stringResource(R.string.chat_panels_opacity), ChatPanels.opacity, 0.2f..0.9f, colors, null) { ChatPanels.set(ctx, opacity = it) }
        }
    }
}

/**
 * A small chat as the settings make it: the chat background, bubbles drifting up
 * under the real glass pills, and the haze, all following the sliders as they move.
 */
@Composable
private fun PanelsPreview(colors: AlphaColorScheme) {
    val glass = remember { com.alphamessenger.ui.components.GlassState() }
    val drift = androidx.compose.animation.core.rememberInfiniteTransition(label = "drift")
    val y by drift.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(9000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "y",
    )
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(260.dp).clip(shape).background(colors.bgChat)) {
        // What the glass blurs: the chat background and the bubbles.
        Box(Modifier.matchParentSize().glassSource(glass)) {
            com.alphamessenger.ui.theme.ChatWallpaperBackground(com.alphamessenger.ui.theme.LocalChatWallpaper.current, Modifier.matchParentSize())
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                    .graphicsLayer { translationY = size.height * 0.5f - y * size.height * 0.5f },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(2) {
                    PreviewBubble(stringResource(R.string.wp_preview_in), false, colors)
                    PreviewBubble(stringResource(R.string.wp_preview_out), true, colors)
                    PreviewBubble(stringResource(R.string.chat_panels_preview_msg), false, colors)
                    PreviewBubble("🔥", true, colors)
                }
            }
        }
        // The top pill over its haze, and the input pill over the bottom haze.
        Column(Modifier.fillMaxWidth().hazeTop(com.alphamessenger.ui.chat.wallpaperEdge(com.alphamessenger.ui.theme.LocalChatWallpaper.current, colors, top = true), ChatPanels.haze).padding(start = 8.dp, end = 8.dp, top = 8.dp)
            .glassPanel(glass, RoundedCornerShape(22.dp), tint = com.alphamessenger.ui.chat.tintOf(colors.bgPrimary, ChatPanels.opacity), backdrop = colors.bgChat, blur = ChatPanels.blur.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = colors.textPrimary, fontSize = 18.sp)
                Box(Modifier.padding(start = 10.dp).height(28.dp).fillMaxWidth(0.1f).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(colors.accent, colors.msgOutEnd))))
                Column(Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.chat_panels_preview_name), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.chat_panels_preview_online), color = colors.accent, fontSize = 10.5.sp)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().hazeBottom(com.alphamessenger.ui.chat.wallpaperEdge(com.alphamessenger.ui.theme.LocalChatWallpaper.current, colors, top = false), ChatPanels.haze, 0.dp)
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
            .glassPanel(glass, RoundedCornerShape(29.dp), tint = com.alphamessenger.ui.chat.tintOf(colors.bgSecondary, ChatPanels.opacity), backdrop = colors.bgChat, blur = ChatPanels.blur.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_message_hint_short), color = colors.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Box(Modifier.height(32.dp).fillMaxWidth(0.12f).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(colors.accent, colors.msgOutEnd))))
            }
        }
    }
    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
}

@Composable
private fun PreviewBubble(text: String, out: Boolean, colors: AlphaColorScheme) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (out) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text, color = if (out) colors.msgOutText else colors.textPrimary, fontSize = 12.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (out) Brush.linearGradient(listOf(colors.msgOutStart, colors.msgOutEnd)) else Brush.linearGradient(listOf(colors.msgIn, colors.msgIn)))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** One slider: moves live, kept when let go. [fmt] null = shown as percent. */
@Composable
private fun Setting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, colors: AlphaColorScheme, fmt: String?, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (fmt != null) fmt.format(v) else "${(v * 100).toInt()}%", color = colors.textPrimary, fontSize = 12.5.sp)
    }
    Slider(
        value = v, onValueChange = { v = it; onDone(it) }, valueRange = range,
        colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.bgTertiary),
    )
}
