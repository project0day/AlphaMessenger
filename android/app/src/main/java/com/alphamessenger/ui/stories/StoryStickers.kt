package com.alphamessenger.ui.stories

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** An emoji laid over a story: centre as fractions of the area, [s] = size factor. */
data class StorySticker(val e: String, val x: Float, val y: Float, val s: Float)

fun stickersToJson(list: List<StorySticker>): String =
    if (list.isEmpty()) "" else org.json.JSONArray(list.map {
        org.json.JSONObject().put("e", it.e).put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("s", it.s.toDouble())
    }).toString()

fun parseStickers(raw: String): List<StorySticker> = runCatching {
    val a = org.json.JSONArray(raw)
    (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { o ->
            StorySticker(o.optString("e"), o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(), o.optDouble("s", 1.0).toFloat())
        }?.takeIf { it.e.isNotBlank() }
    }.take(30)
}.getOrDefault(emptyList())

private const val BASE_DP = 64f
const val STICKER_MIN = 0.4f
const val STICKER_MAX = 12f

/**
 * Move / resize gesture for the stickers, put on the STORY AREA itself (the
 * parent): it sees every touch without taking any from the text field or the
 * buttons. The first finger picks the sticker it lands on or near; a drag
 * moves it, a pinch (the second finger anywhere) resizes it.
 */
@Composable
fun Modifier.stickerGestures(stickers: List<StorySticker>, onChange: (Int, StorySticker) -> Unit): Modifier {
    val latest by rememberUpdatedState(stickers)
    val change by rememberUpdatedState(onChange)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = size.width.toFloat(); val h = size.height.toFloat()
            val list = latest
            fun dist2(i: Int): Float {
                val st = list[i]; val dx = st.x * w - down.position.x; val dy = st.y * h - down.position.y
                return dx * dx + dy * dy
            }
            val idx = list.indices.minByOrNull { dist2(it) }?.takeIf { i ->
                val reach = (BASE_DP * list[i].s).dp.toPx() * 0.6f + 28.dp.toPx()
                dist2(i) <= reach * reach
            } ?: return@awaitEachGesture
            do {
                val event = awaitPointerEvent()
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != androidx.compose.ui.geometry.Offset.Zero) {
                    val c = latest.getOrNull(idx) ?: break
                    change(idx, c.copy(
                        x = (c.x + pan.x / w).coerceIn(0f, 1f),
                        y = (c.y + pan.y / h).coerceIn(0f, 1f),
                        s = (c.s * zoom).coerceIn(STICKER_MIN, STICKER_MAX),
                    ))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

/**
 * The stickers of a story, drawn at their places and sizes. Each emoji is
 * rendered into a picture of the exact on-screen size (the phone's emoji font
 * is vector), so it stays sharp however big it gets. Editable: double tap
 * removes one.
 */
@Composable
fun StickerLayer(
    stickers: List<StorySticker>,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onRemove: (Int) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        stickers.forEachIndexed { i, st ->
            val sizeDp = (BASE_DP * st.s).dp
            val sizePx = with(density) { sizeDp.toPx() }
            // Re-drawn in 32 px steps while pinching: sharp, without a redraw per frame.
            val bucket = (((sizePx / 32f).roundToInt().coerceAtLeast(1)) * 32).coerceAtMost(512)
            val bmp = remember(st.e, bucket) { renderEmoji(st.e, bucket) }
            Box(
                Modifier
                    .offset { IntOffset((st.x * wPx - sizePx / 2f).roundToInt(), (st.y * hPx - sizePx / 2f).roundToInt()) }
                    .size(sizeDp)
                    .then(if (!editable) Modifier else Modifier.pointerInput(i) { detectTapGestures(onDoubleTap = { onRemove(i) }) }),
                contentAlignment = Alignment.Center,
            ) {
                Image(bmp, contentDescription = st.e, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** The emoji drawn by the CPU at [px]×[px] — the vector font at full resolution. */
private fun renderEmoji(e: String, px: Int): androidx.compose.ui.graphics.ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = px * 0.82f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val fm = paint.fontMetrics
    val y = px / 2f - (fm.ascent + fm.descent) / 2f
    canvas.drawText(e, px / 2f, y, paint)
    return bmp.asImageBitmap()
}
