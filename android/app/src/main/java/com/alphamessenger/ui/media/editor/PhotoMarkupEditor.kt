package com.alphamessenger.ui.media.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** The brush colours, light to dark and round the wheel. */
val BRUSH_COLORS = listOf(
    Color.White, Color(0xFFFFCC00), Color(0xFFFF9500), Color(0xFFFF3B30), Color(0xFFFF2D55),
    Color(0xFFAF52DE), Color(0xFF0A84FF), Color(0xFF34C759), Color.Black,
)

/** Brush widths as fractions of the frame's shorter side. */
val BRUSH_WIDTHS = listOf(0.006f, 0.013f, 0.024f)

/**
 * Over the frame: the brush while drawing, the stickers otherwise (one finger
 * moves, two scale and turn). Only the gestures and what is in motion are drawn
 * here — the finished drawing is part of the frame's own rendering.
 */
@Composable
internal fun MarkupLayer(
    markup: PhotoMarkup,
    frame: FittedFrame,
    drawing: Boolean,
    brush: Color,
    brushWidth: Float,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onMarkup: (PhotoMarkup) -> Unit,
) {
    val current by rememberUpdatedState(markup)
    val live = remember { mutableStateListOf<Float>() }
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize().pointerInput(frame, drawing, brush, brushWidth) {
            fun nx(x: Float) = ((x - frame.originX) / frame.width).coerceIn(0f, 1f)
            fun ny(y: Float) = ((y - frame.originY) / frame.height).coerceIn(0f, 1f)
            if (drawing) awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                live.clear(); live.add(nx(down.position.x)); live.add(ny(down.position.y))
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    c.consume()
                    live.add(nx(c.position.x)); live.add(ny(c.position.y))
                }
                if (live.size >= 2) onMarkup(current.copy(strokes = current.strokes + PhotoMarkup.Stroke(brush.toArgb(), brushWidth, live.toList())))
                live.clear()
            } else awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val p = down.position
                // The topmost sticker under the finger.
                val hit = current.stickers.indices.reversed().firstOrNull { i ->
                    val k = current.stickers[i]
                    val half = k.size * frame.width / 2f
                    abs(p.x - (frame.originX + k.x * frame.width)) <= half && abs(p.y - (frame.originY + k.y * frame.height)) <= half
                }
                if (hit == null) { onSelect(null); return@awaitEachGesture }
                down.consume()
                onSelect(hit)
                // Brought to the front while it is being handled.
                var list = current.stickers.toMutableList().apply { add(removeAt(hit)) }
                val at = list.lastIndex
                onSelect(at)
                do {
                    val ev = awaitPointerEvent()
                    val pan = ev.calculatePan()
                    val zoom = ev.calculateZoom()
                    val turn = ev.calculateRotation()
                    val k = list[at]
                    list = list.toMutableList().apply {
                        set(at, k.copy(
                            x = (k.x + pan.x / frame.width).coerceIn(0f, 1f),
                            y = (k.y + pan.y / frame.height).coerceIn(0f, 1f),
                            size = (k.size * zoom).coerceIn(0.06f, 1.4f),
                            rotation = k.rotation + turn,
                        ))
                    }
                    onMarkup(current.copy(stickers = list))
                    ev.changes.forEach { it.consume() }
                } while (ev.changes.any { it.pressed })
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // The line being drawn, until the finger lifts.
            if (live.size >= 2) drawIntoCanvas { c ->
                c.nativeCanvas.save()
                c.nativeCanvas.translate(frame.originX, frame.originY)
                MarkupRenderer.draw(
                    c.nativeCanvas, frame.width, frame.height,
                    PhotoMarkup(strokes = listOf(PhotoMarkup.Stroke(brush.toArgb(), brushWidth, live.toList()))),
                ) { null }
                c.nativeCanvas.restore()
            }
            // The handled sticker's frame.
            val k = selected?.let { markup.stickers.getOrNull(it) }
            if (k != null && !drawing) {
                val cx = frame.originX + k.x * frame.width
                val cy = frame.originY + k.y * frame.height
                val half = k.size * frame.width / 2f
                rotate(k.rotation, Offset(cx, cy)) {
                    drawRect(
                        Color.White, Offset(cx - half, cy - half), androidx.compose.ui.geometry.Size(half * 2, half * 2),
                        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                    )
                }
            }
        }
        val k = selected?.let { markup.stickers.getOrNull(it) }
        if (k != null && !drawing) {
            val half = k.size * frame.width / 2f
            val bx = frame.originX + k.x * frame.width + half
            val by = frame.originY + k.y * frame.height - half
            val r = with(density) { 14.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((bx - r).roundToInt(), (by - r).roundToInt()) }
                    .size(28.dp).clip(CircleShape).background(Color(0xFFFF3B30))
                    .clickable {
                        onMarkup(markup.copy(stickers = markup.stickers.filterIndexed { i, _ -> i != selected }))
                        onSelect(null)
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_delete), tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** The brush: colours, widths, and a step back. */
@Composable
internal fun DrawBar(
    color: Color,
    width: Float,
    canUndo: Boolean,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BRUSH_COLORS.forEach { c ->
                val on = c == color
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                        .padding(if (on) 5.dp else 2.dp).clip(CircleShape).background(c)
                        .clickable { onColor(c) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BRUSH_WIDTHS.forEach { w ->
                val on = w == width
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onWidth(w) },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size((6 + w * 700).dp.coerceAtMost(24.dp)).clip(CircleShape).background(color)) }
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = if (canUndo) 0.12f else 0.05f))
                    .clickable(enabled = canUndo, onClick = onUndo).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.Outlined.Undo, null, tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.35f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.editor_undo), color = if (canUndo) Color.White else Color.White.copy(alpha = 0.35f), fontSize = 14.sp)
            }
        }
    }
}

/** Emoji and the user's stickers, to put on the photo. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPickSheet(
    colors: AlphaColorScheme,
    onEmoji: (String) -> Unit,
    onSticker: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val stickers = remember {
        com.alphamessenger.ui.sticker.StickerStore.packs(ctx).flatMap { it.stickers }.map { it.file }.distinct()
    }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.editor_tab_emoji, R.string.editor_tab_stickers).forEachIndexed { i, label ->
                    val on = tab == i
                    Text(
                        stringResource(label), color = if (on) colors.bgPrimary else colors.textSecondary,
                        fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.accent else colors.bgTertiary)
                            .clickable { tab = i }.padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(380.dp)) {
                if (tab == 0) com.alphamessenger.ui.chat.v2.EmojiPanelV2(
                    modifier = Modifier.fillMaxSize(),
                    colors = colors,
                    onPick = { onEmoji(it) },
                    onBackspace = {},
                ) else if (stickers.isEmpty()) Text(
                    stringResource(R.string.editor_no_stickers), color = colors.textMuted, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) else LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(stickers) { f ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable { onSticker(f) }) {
                            com.alphamessenger.ui.sticker.StickerImage(f, Modifier.fillMaxSize(), side = 192)
                        }
                    }
                }
            }
        }
    }
}
