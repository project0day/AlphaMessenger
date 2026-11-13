package com.alphamessenger.ui.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * "Matrix": columns of glyphs raining down the main screen. Over the whole
 * screen it is drawn under the chats, so it never covers what is written; in
 * the header it sits behind the header's content. It takes no touches.
 */
object MatrixRain {
    private const val PREFS = "ui_prefs"
    var enabled by mutableStateOf(false)
        private set
    /** Over the whole main screen (true) or the header only. */
    var fullScreen by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("matrix", false)
        fullScreen = p.getBoolean("matrix_full", true)
    }

    fun set(ctx: Context, v: Boolean) {
        enabled = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("matrix", v).apply()
    }

    fun setFullScreen(ctx: Context, v: Boolean) {
        fullScreen = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("matrix_full", v).apply()
    }
}

// Half-width katakana, digits and a few signs, as the rain is usually drawn.
private val GLYPHS: CharArray = (
    "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ" + "0123456789" + "Z:=*+-<>¦"
).toCharArray()

private val RAIN_GREEN = Color(0xFF00FF41)

private class Drop(val speed: Float, val trail: Int, val cycle: Int, val offset: Float, val change: Float)

private class Rain(val size: Size, val cell: Float, val colW: Float, val rows: Int, val drops: Array<Drop>, val paint: android.graphics.Paint)

private fun rain(size: Size, density: Float, textDp: Float): Rain {
    val text = textDp * density
    val cell = text * 1.18f
    val colW = text * 0.92f
    val rows = (size.height / cell).toInt() + 1
    val cols = (size.width / colW).toInt() + 1
    val rnd = java.util.Random(1999)
    val drops = Array(cols) {
        val trail = 8 + rnd.nextInt(18)
        Drop(
            speed = 5f + rnd.nextFloat() * 9f,
            trail = trail,
            // A pause between passes, so the columns never fall in step.
            cycle = rows + trail + 4 + rnd.nextInt(rows.coerceAtLeast(1)),
            offset = rnd.nextFloat() * 400f,
            change = 0.6f + rnd.nextFloat() * 1.8f,
        )
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = text
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = android.graphics.Paint.Align.CENTER
    }
    return Rain(size, cell, colW, rows, drops, paint)
}

/** The rain over the whole main screen (when chosen), drawn behind the lists: the
 *  glass of the header and the dock frosts it like the rest of the content. */
fun Modifier.matrixScreen(): Modifier = composed {
    MatrixRain.load(LocalContext.current)
    if (!MatrixRain.enabled || !MatrixRain.fullScreen) return@composed this
    val clock = rememberDecorClock(Unit, stillAt = 3_000L)
    val field = remember { arrayOfNulls<Rain>(1) }
    this.drawBehind { drawRain(field, clock.longValue / 1000f, strength = 0.42f) }
}

/** The rain in the header only (when chosen), behind the header's content. */
fun Modifier.matrixHeader(): Modifier = composed {
    MatrixRain.load(LocalContext.current)
    if (!MatrixRain.enabled || MatrixRain.fullScreen) return@composed this
    val clock = rememberDecorClock(Unit, stillAt = 3_000L)
    val field = remember { arrayOfNulls<Rain>(1) }
    this.drawBehind { drawRain(field, clock.longValue / 1000f, strength = 0.55f) }
}

private fun DrawScope.drawRain(field: Array<Rain?>, t: Float, strength: Float) {
    val r = field[0]?.takeIf { it.size == size } ?: rain(size, density, 13f).also { field[0] = it }
    val light = StarSky.light
    val tint = if (light) StarSky.accent else RAIN_GREEN
    val head = if (light) StarSky.accent else Color(0xFFD8FFE0)
    val fade = strength * if (light) 0.6f else 1f
    val tintArgb = tint.toArgb() and 0x00FFFFFF
    val headArgb = head.toArgb() and 0x00FFFFFF
    val glyph = CharArray(1)
    drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        for (i in r.drops.indices) {
            val d = r.drops[i]
            val headRow = ((t * d.speed + d.offset).toInt()) % d.cycle
            val x = i * r.colW + r.colW / 2f
            for (k in 0..d.trail) {
                val row = headRow - k
                if (row < 0 || row >= r.rows) continue
                // Each cell flickers to a new glyph now and then, at its own moment.
                val tick = (t * d.change + row * 0.37f + i * 0.71f).toInt()
                var h = i * 73856093 xor row * 19349663 xor tick * 83492791
                h = h xor (h ushr 13)
                glyph[0] = GLYPHS[(h and 0x7fffffff) % GLYPHS.size]
                val a = if (k == 0) 0.95f else {
                    val f = 1f - k.toFloat() / (d.trail + 1)
                    0.85f * f * f
                }
                val alpha = (a * fade * 255).toInt().coerceIn(0, 255)
                r.paint.color = (alpha shl 24) or (if (k == 0) headArgb else tintArgb)
                canvas.drawText(glyph, 0, 1, x, (row + 1) * r.cell, r.paint)
            }
        }
    }
}
