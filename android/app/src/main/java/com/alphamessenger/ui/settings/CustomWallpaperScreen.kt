package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.stories.SliderBar
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.ChatWallpaper
import com.alphamessenger.ui.theme.customWallpaper
import com.alphamessenger.ui.theme.customWallpaperBackground

/** One colour of the background being made, as hue / saturation / brightness. */
private class Hsv(h: Float, s: Float, v: Float) {
    var h by mutableFloatStateOf(h)
    var s by mutableFloatStateOf(s)
    var v by mutableFloatStateOf(v)
    val color: Color get() = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

    fun set(c: Color) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), out)
        h = out[0]; s = out[1]; v = out[2]
    }

    companion object {
        fun of(c: Color) = Hsv(0f, 0f, 0f).also { it.set(c) }
    }
}

/** Deep tones that read well under bubbles, then a few bright ones. */
private val PALETTE = listOf(
    0xFF0F172A, 0xFF1E293B, 0xFF1F2A44, 0xFF1E3A5F, 0xFF0F3B4C, 0xFF0F2A2E, 0xFF12372A, 0xFF1F3A2E,
    0xFF3B2A6B, 0xFF4C1D95, 0xFF5B21B6, 0xFF6D28D9, 0xFF831843, 0xFF3A1C2E, 0xFF5A2B1B, 0xFF7C2D12,
    0xFF0EA5E9, 0xFF6366F1, 0xFF8B5CF6, 0xFFEC4899, 0xFFF97316, 0xFFEAB308, 0xFF10B981, 0xFF14B8A6,
).map { Color(it) }

/** CSS angle → the arrow's turn (the arrow points down at 0°). */
private val ANGLES = listOf(
    180f to 0f to R.string.wp_dir_down,
    135f to -45f to R.string.wp_dir_diag,
    90f to -90f to R.string.wp_dir_right,
    45f to -135f to R.string.wp_dir_up,
)

/**
 * Appearance → Chat background → your own: one colour, or a gradient of
 * 2–4 colours at an angle. Each colour is set from the palette or by hue,
 * saturation and brightness; the preview shows it under real bubbles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomWallpaperScreen(
    startWithGradient: Boolean,
    onBack: () -> Unit,
    onSave: (ChatWallpaper.Custom) -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    var gradient by remember { mutableStateOf(startWithGradient) }
    val stops = remember {
        mutableStateListOf(Hsv.of(Color(0xFF3B2A6B)), Hsv.of(Color(0xFF1F3A5F)), Hsv.of(Color(0xFF0F2A2E)))
    }
    val single = remember { Hsv.of(Color(0xFF1F2A44)) }
    var sel by remember { mutableIntStateOf(0) }
    var angle by remember { mutableFloatStateOf(135f) }
    val current = if (gradient) stops[sel.coerceIn(0, stops.lastIndex)] else single
    val made = if (gradient) stops.map { it.color } else listOf(single.color)

    OptionScreen(stringResource(R.string.wp_custom_title), onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            // Colour / Gradient.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.bgSecondary).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(false to R.string.wp_color, true to R.string.wp_gradient).forEach { (g, label) ->
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (gradient == g) cs.bgTertiary else Color.Transparent)
                            .clickable { gradient = g },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(label), color = cs.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(14.dp))
            // The background under real bubbles.
            Column(
                Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(20.dp))
                    .customWallpaperBackground(made, angle).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                Text(
                    stringResource(R.string.wp_preview_in), color = cs.textPrimary, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)).background(cs.msgIn)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    stringResource(R.string.wp_preview_out), color = cs.msgOutText, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                        .background(Brush.linearGradient(listOf(cs.msgOutStart, cs.msgOutEnd)))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (gradient) {
                Spacer(Modifier.height(14.dp))
                // The colours (tap to choose, hold to remove) and the direction.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    stops.forEachIndexed { i, st ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(st.color)
                                .border(if (i == sel) 3.dp else 1.dp, if (i == sel) Color.White else cs.alphaBorder, CircleShape)
                                .combinedClickable(
                                    onClick = { sel = i },
                                    onLongClick = {
                                        if (stops.size > 2) { stops.removeAt(i); sel = sel.coerceAtMost(stops.lastIndex) }
                                    },
                                    onLongClickLabel = stringResource(R.string.wp_remove_color),
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (stops.size < 4) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).border(1.dp, cs.textMuted, CircleShape)
                                .clickable { stops.add(Hsv.of(stops.last().color)); sel = stops.lastIndex },
                            contentAlignment = Alignment.Center,
                        ) { Text("+", color = cs.textPrimary, fontSize = 20.sp, modifier = Modifier.padding(bottom = 2.dp)) }
                    }
                    Spacer(Modifier.weight(1f))
                    ANGLES.forEach { (pair, label) ->
                        val (deg, turn) = pair
                        Box(
                            Modifier.padding(start = 6.dp).size(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (angle == deg) cs.bgTertiary else cs.bgSecondary)
                                .clickable { angle = deg },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                AlphaIcons.Filled.ArrowDownward, stringResource(label),
                                tint = if (angle == deg) cs.accent else cs.textSecondary,
                                modifier = Modifier.size(18.dp).rotate(turn),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Quick picks.
            PALETTE.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c ->
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(c)
                                .clickable { current.set(c) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Any colour at all: hue, saturation, brightness.
            SliderLabel(stringResource(R.string.wp_hue))
            SliderBar(
                brush = Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 1f))) }),
                fraction = current.h / 360f,
            ) { current.h = it * 360f }
            SliderLabel(stringResource(R.string.wp_saturation))
            SliderBar(
                brush = Brush.horizontalGradient(listOf(
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(current.h, 0f, current.v.coerceAtLeast(0.3f)))),
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(current.h, 1f, current.v.coerceAtLeast(0.3f)))),
                )),
                fraction = current.s,
            ) { current.s = it }
            SliderLabel(stringResource(R.string.wp_brightness))
            SliderBar(
                brush = Brush.horizontalGradient(listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(current.h, current.s, 1f))))),
                fraction = current.v,
            ) { current.v = it.coerceAtLeast(0.04f) }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(cs.accent, cs.msgOutEnd)))
                    .clickable { onSave(customWallpaper(made, if (gradient) angle else 180f)) },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.wp_save), color = cs.msgOutText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SliderLabel(text: String) {
    Text(
        text, color = AlphaTheme.colorScheme.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}
