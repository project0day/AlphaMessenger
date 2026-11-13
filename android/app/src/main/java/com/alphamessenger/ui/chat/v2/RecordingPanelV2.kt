package com.alphamessenger.ui.chat.v2

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.alphamessenger.R
import com.alphamessenger.ui.chat.RecorderTick
import com.alphamessenger.ui.chat.fmtMs
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.flow.SharedFlow

/**
 * Recording panel V2 (from scratch) — now an OVERLAY (per spec), NOT a panel.
 *
 * The InputBar stays composed underneath (disabled); this panel appears ON
 * TOP of it (the host renders it inside the InputBar's Box via AnimatedVisibility).
 * The visual is preserved (timer pill + live equalizer + 3 round action buttons).
 *
 * ## Waveform
 * Collected HERE from the 10 Hz mic stream; the last 56 samples kept locally,
 * so only this panel repaints.
 */
@Composable
fun RecordingPanelV2(
    seconds: Int,
    paused: Boolean,
    ticks: SharedFlow<RecorderTick>,
    colors: AlphaColorScheme,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    /** On the chat's frosted glass: no fill of its own. */
    onGlass: Boolean = false,
) {
    val waveform = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        ticks.collect { t ->
            waveform.add(t.amplitude)
            while (waveform.size > 56) waveform.removeAt(0)
        }
    }
    val blink = rememberInfiniteTransition(label = "rec")
    val micAlpha by blink.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "mic",
    )
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onGlass) Modifier else Modifier.background(colors.bgSecondary)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.bgTertiary).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFEB5757).copy(alpha = if (paused) 0.4f else micAlpha)))
            Text(fmtMs(seconds * 1000), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Canvas(modifier = Modifier.weight(1f).height(32.dp)) {
            val slots = 56; val barW = size.width / slots; val minH = 2.dp.toPx()
            waveform.takeLast(slots).forEachIndexed { i, a ->
                val h = (a * size.height).coerceAtLeast(minH); val x = i * barW + barW / 2
                drawLine(color = colors.accent, start = Offset(x, (size.height - h) / 2), end = Offset(x, (size.height + h) / 2), strokeWidth = barW * 0.5f, cap = StrokeCap.Round)
            }
        }
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.bgTertiary).clickable(onClick = onPauseResume), contentAlignment = Alignment.Center) {
            Icon(if (paused) AlphaIcons.Filled.PlayArrow else AlphaIcons.Filled.Pause, contentDescription = if (paused) stringResource(R.string.call_resume) else stringResource(R.string.editor_pause), tint = colors.textPrimary, modifier = Modifier.size(22.dp))
        }
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.bgTertiary).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
            Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = colors.textMuted, modifier = Modifier.size(20.dp))
        }
        Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF34C759)).clickable(onClick = onSend), contentAlignment = Alignment.Center) {
            Icon(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.common_send), tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
