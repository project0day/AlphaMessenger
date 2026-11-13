package com.alphamessenger.ui.components

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sin

/**
 * "Starry sky": twinkling stars over the main screen, faint and small; it
 * takes no touches. The look follows the theme (white stars on dark, the
 * accent on light).
 */
object StarSky {
    private const val PREFS = "ui_prefs"
    var enabled by mutableStateOf(true)
        private set
    /** Over the whole main screen (true) or the header only. */
    var fullScreen by mutableStateOf(true)
        private set
    /** Set by the app from the current theme. */
    var light by mutableStateOf(false)
    var accent by mutableStateOf(Color.White)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("starry", true)
        fullScreen = p.getBoolean("starry_full", true)
    }

    fun set(ctx: Context, v: Boolean) {
        enabled = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("starry", v).apply()
    }

    fun setFullScreen(ctx: Context, v: Boolean) {
        fullScreen = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("starry_full", v).apply()
    }
}

private class Star(val x: Float, val y: Float, val r: Float, val base: Float, val speed: Float, val phase: Float, val bright: Boolean)

private fun stars(size: Size, density: Float): List<Star> {
    val rnd = java.util.Random(1977)
    // About one star per 70 dp square: a phone screen holds ~130.
    val cell = 70f * density
    val n = ((size.width * size.height) / (cell * cell)).toInt().coerceIn(16, 220)
    return List(n) {
        val bright = rnd.nextFloat() < 0.08f
        Star(
            x = rnd.nextFloat() * size.width,
            y = rnd.nextFloat() * size.height,
            r = (if (bright) 1.5f + rnd.nextFloat() * 0.8f else 0.5f + rnd.nextFloat() * 0.9f) * density,
            base = if (bright) 0.75f else 0.25f + rnd.nextFloat() * 0.45f,
            speed = 0.6f + rnd.nextFloat() * 1.8f,
            phase = rnd.nextFloat() * 6.283f,
            bright = bright,
        )
    }
}

/** The sky over the whole main screen (when chosen); put it last over the app's content. */
@Composable
fun StarrySkyLayer() {
    StarSky.load(LocalContext.current)
    if (!StarSky.enabled || !StarSky.fullScreen) return
    val clock = rememberDecorClock(Unit, stillAt = 3_000L)
    // Laid out once per screen size; a plain holder, not state, so drawing never recomposes.
    val field = remember { arrayOfNulls<Pair<Size, List<Star>>>(1) }
    Canvas(Modifier.fillMaxSize()) { drawStars(field, clock.longValue / 1000f) }
}

/** The sky in the header only (when chosen), behind the header's content. */
fun Modifier.starryHeader(): Modifier = composed {
    StarSky.load(LocalContext.current)
    if (!StarSky.enabled || StarSky.fullScreen) return@composed this
    val clock = rememberDecorClock(Unit, stillAt = 3_000L)
    val field = remember { arrayOfNulls<Pair<Size, List<Star>>>(1) }
    this.drawBehind { drawStars(field, clock.longValue / 1000f) }
}

private fun DrawScope.drawStars(field: Array<Pair<Size, List<Star>>?>, t: Float) {
    val f = field[0]?.takeIf { it.first == size } ?: (size to stars(size, density)).also { field[0] = it }
    val color = if (StarSky.light) StarSky.accent else Color.White
    val fade = if (StarSky.light) 0.55f else 1f
    for (s in f.second) {
        val a = (s.base * (0.55f + 0.45f * sin(t * s.speed + s.phase))).coerceIn(0f, 1f) * fade
        drawCircle(color.copy(alpha = a), s.r, Offset(s.x, s.y))
        if (s.bright) {
            // A soft halo and four short rays.
            drawCircle(color.copy(alpha = a * 0.18f), s.r * 3.2f, Offset(s.x, s.y))
            val ray = s.r * 4.5f * (0.7f + 0.3f * sin(t * s.speed * 1.3f + s.phase))
            val w = s.r * 0.45f
            drawLine(color.copy(alpha = a * 0.55f), Offset(s.x - ray, s.y), Offset(s.x + ray, s.y), w, StrokeCap.Round)
            drawLine(color.copy(alpha = a * 0.55f), Offset(s.x, s.y - ray), Offset(s.x, s.y + ray), w, StrokeCap.Round)
        }
    }
}
