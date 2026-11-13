package com.alphamessenger.ui.stories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Background of a text story: ready gradients, your saved ones (hold to
 * remove), and your own — two colours, each set by hue and brightness.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GradientPickerSheet(
    current: List<Long>,
    custom: List<List<Long>>,
    onPick: (List<Long>) -> Unit,
    onSave: (List<Long>) -> Unit,
    onDelete: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    // The gradient being made: two colours as (hue 0..360, brightness 0..1).
    var slot by remember { mutableIntStateOf(0) }
    val hue = remember { floatArrayOf(hueOf(current[0]), hueOf(current[1])) }
    val light = remember { floatArrayOf(lightOf(current[0]), lightOf(current[1])) }
    var tick by remember { mutableIntStateOf(0) } // redraw after the arrays change
    fun colorAt(i: Int): Long {
        @Suppress("UNUSED_EXPRESSION") tick
        val v = 0.35f + 0.65f * light[i]
        return android.graphics.Color.HSVToColor(floatArrayOf(hue[i], 0.78f, v)).toLong() and 0xFFFFFFFFL
    }
    val made = listOf(colorAt(0), colorAt(1))

    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.stories_bg_ready), color = cs.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(StoriesViewModel.BACKGROUNDS) { g -> Swatch(g, g == current) { onPick(g) } }
            }
            if (custom.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.stories_bg_mine), color = cs.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(custom) { g ->
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(g.map { Color(it) }))
                                .then(if (g == current) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                .combinedClickable(onClick = { onPick(g) }, onLongClick = { onDelete(g) }),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.stories_bg_make), color = cs.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(made.map { Color(it) })))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(0, 1).forEach { i ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(made[i]))
                            .border(if (slot == i) 3.dp else 1.dp, if (slot == i) Color.White else cs.alphaBorder, CircleShape)
                            .combinedClickable(onClick = { slot = i }),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(stringResource(if (slot == 0) R.string.stories_bg_color1 else R.string.stories_bg_color2), color = cs.textSecondary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(14.dp))
            // Hue: the whole rainbow.
            SliderBar(
                brush = Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.8f, 1f))) }),
                fraction = hue[slot] / 360f,
            ) { f -> hue[slot] = f * 360f; tick++ }
            Spacer(Modifier.height(12.dp))
            // Brightness of that colour.
            SliderBar(
                brush = Brush.horizontalGradient(listOf(
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue[slot], 0.78f, 0.35f))),
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue[slot], 0.78f, 1f))),
                )),
                fraction = light[slot],
            ) { f -> light[slot] = f; tick++ }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onSave(made); onPick(made) }) {
                    Text(stringResource(R.string.stories_bg_save), color = cs.accent, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { onPick(made); onDismiss() }) {
                    Text(stringResource(R.string.stories_bg_apply), color = cs.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Swatch(g: List<Long>, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(g.map { Color(it) }))
            .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .pointerInput(g) { detectTapGestures { onClick() } },
    )
}

/** A colour bar with a round thumb; tap or drag sets the fraction 0..1. */
@Composable
internal fun SliderBar(brush: Brush, fraction: Float, onChange: (Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
        val w = constraints.maxWidth.toFloat()
        var f by remember { mutableFloatStateOf(fraction) }
        f = fraction
        // The gesture handlers live as long as the bar; they must call the
        // CURRENT onChange (the colour chosen now), not the one of the first frame.
        val change by androidx.compose.runtime.rememberUpdatedState(onChange)
        Box(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(16.dp)).background(brush)
                .pointerInput(w) {
                    detectTapGestures { o -> change((o.x / w).coerceIn(0f, 1f)) }
                }
                .pointerInput(w) {
                    detectDragGestures { c, _ -> change((c.position.x / w).coerceIn(0f, 1f)) }
                },
        )
        val thumb = 26.dp
        Box(
            Modifier.offset(x = maxWidth * f - thumb / 2, y = 3.dp).size(thumb).clip(CircleShape)
                .background(Color.White).border(2.dp, Color.Black.copy(alpha = 0.25f), CircleShape),
        )
    }
}

private fun hueOf(c: Long): Float {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c.toInt(), hsv); return hsv[0]
}

private fun lightOf(c: Long): Float {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c.toInt(), hsv)
    return ((hsv[2] - 0.35f) / 0.65f).coerceIn(0f, 1f)
}
