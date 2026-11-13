package com.alphamessenger.ui.chat.media.v2.chrome

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R

private val SPEED_OPTIONS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

/**
 * Transport bar — a bottom-bar element like the caption and the filmstrip,
 * not a separate overlay on top of the video.
 *
 * No player is passed in: the bar receives position, duration and flags, and
 * reports user intents up. Which player plays is not its business.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChromeTransportBar(
    /** 0..1 of the file already downloaded while it still downloads; null = all here. */
    downloaded: Float? = null,
    /** "↓ 45%" / "download stopped" next to the time while downloading. */
    downloadNote: String? = null,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    muted: Boolean,
    fillScreen: Boolean,
    onSeek: (Long) -> Unit,
    /** A frame of the video at a position, for the preview while dragging; null = none. */
    frames: ScrubFrames? = null,
    onSpeedChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onFitToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var speedMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // While the finger drags, the video keeps playing: only the release seeks.
        var scrub by remember { mutableStateOf<Float?>(null) }
        val fraction = scrub ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        var barWidth by remember { mutableStateOf(0) }
        Box(Modifier.fillMaxWidth().onSizeChanged { barWidth = it.width }) {
        scrub?.let { v -> SeekPreview(v, durationMs, barWidth, frames) }
        com.alphamessenger.ui.components.AlphaSlider(
            value = fraction,
            onValueChange = { v -> if (durationMs > 0) scrub = v },
            onValueChangeFinished = {
                scrub?.let { v -> if (durationMs > 0) onSeek((v * durationMs).toLong()) }
                scrub = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
            // The track shows three things, as video players do: played (white),
            // downloaded (light) and not yet here (faint).
            track = {
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val y = size.height / 2f
                    val w = size.width
                    val stroke = size.height
                    val cap = androidx.compose.ui.graphics.StrokeCap.Round
                    drawLine(Color.White.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), stroke, cap)
                    val here = downloaded ?: 1f
                    if (here > 0f) drawLine(Color.White.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w * here, y), stroke, cap)
                    if (fraction > 0f) drawLine(Color.White, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w * fraction, y), stroke, cap)
                }
            },
        )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Position and duration as one group — the eye doesn't jump between edges.
            Text(
                text = chromeTime(scrub?.let { (it * durationMs).toLong() } ?: positionMs) + " / " +
                    (if (durationMs > 0) chromeTime(durationMs) else "…"),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (downloadNote != null) {
                Spacer(Modifier.width(10.dp))
                Text(downloadNote, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { speedMenuOpen = true }) {
                        Icon(AlphaIcons.Filled.Speed, stringResource(R.string.media_speed), tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        "${speed}x",
                        color = if (speed != 1f) Color.White else Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = if (speed != 1f) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                    SPEED_OPTIONS.forEach { s ->
                        DropdownMenuItem(
                            text = { Text("${s}x", fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { speedMenuOpen = false; onSpeedChange(s) },
                        )
                    }
                }
            }
            IconButton(onClick = onMuteToggle) {
                Icon(
                    if (muted) AlphaIcons.AutoMirrored.Filled.VolumeOff else AlphaIcons.AutoMirrored.Filled.VolumeUp,
                    stringResource(R.string.editor_sound), tint = Color.White, modifier = Modifier.size(22.dp),
                )
            }
            // Fit ↔ fill screen.
            IconButton(onClick = onFitToggle) {
                Icon(
                    if (fillScreen) AlphaIcons.Filled.FullscreenExit else AlphaIcons.Filled.Fullscreen,
                    if (fillScreen) stringResource(R.string.media_fit_screen) else stringResource(R.string.media_fill_screen),
                    tint = Color.White, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * The frame and time the release would land on, floating above the thumb.
 * A popup: the bottom panel clips what is drawn outside it, and the frame must cover the video.
 */
@Composable
private fun SeekPreview(
    value: Float,
    durationMs: Long,
    barWidth: Int,
    frames: ScrubFrames?,
) {
    val ms = (value * durationMs).toLong()
    val latest by androidx.compose.runtime.rememberUpdatedState(ms)
    // The storyboard's nearest frame at once; the exact key frame when the finger slows.
    var exact by remember { mutableStateOf<Pair<Long, androidx.compose.ui.graphics.ImageBitmap>?>(null) }
    val step = if (durationMs > 0) (durationMs / 120).coerceAtLeast(200) else 500
    val nearest = frames?.let { f -> f.built; f.nearest(ms) }
    val frame = exact?.takeIf { kotlin.math.abs(it.first - ms) <= step }?.second ?: nearest ?: exact?.second
    if (frames != null) androidx.compose.runtime.LaunchedEffect(frames) {
        // Only the newest position matters: a slow frame is dropped when the finger moves on.
        // Only a move past a step asks again: every pixel would queue decodes behind each other.
        androidx.compose.runtime.snapshotFlow { latest / step }.collectLatest {
            val at = latest
            frames.frameAt(at)?.let { exact = at to it }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thumbHalf = with(density) { 8.dp.toPx() }
    val cx = (thumbHalf + value * (barWidth - 2 * thumbHalf)).toInt()
    // The slider is taller than its track: this lands the preview just above the thumb.
    val gap = with(density) { (-14).dp.roundToPx() }
    val edge = with(density) { 8.dp.roundToPx() }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = AboveThumb(cx, gap, edge),
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = false),
    ) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val time = @Composable { m: Modifier ->
        Text(
            chromeTime(ms),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = m
                .background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    val f = frame
    if (f == null) {
        time(Modifier)
    } else {
        val portrait = f.height > f.width
        val w = if (portrait) 110.dp else 180.dp
        val h = (w * (f.height.toFloat() / f.width)).coerceAtMost(200.dp)
        // The time sits inside the frame, along its bottom edge.
        Box(
            Modifier.size(w, h).clip(shape).border(1.dp, Color.White.copy(alpha = 0.8f), shape),
            contentAlignment = Alignment.BottomCenter,
        ) {
            androidx.compose.foundation.Image(
                f, null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            time(Modifier.padding(bottom = 5.dp))
        }
    }
    }
}

/** Centred over the thumb, kept on screen, its bottom just above the bar. */
private data class AboveThumb(val cx: Int, val gap: Int, val edge: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val x = (anchorBounds.left + cx - popupContentSize.width / 2)
            .coerceIn(edge, (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge))
        return androidx.compose.ui.unit.IntOffset(x, anchorBounds.top - popupContentSize.height - gap)
    }
}

/** ms → "M:SS" / "H:MM:SS". */
internal fun chromeTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = (totalSec / 3600).toInt()
    val m = ((totalSec % 3600) / 60).toInt()
    val s = (totalSec % 60).toInt()
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
