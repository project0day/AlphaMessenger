package com.alphamessenger.ui.components

import android.content.Context
import android.os.PowerManager
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The seasons that decorate the top of the chat list in their first days. */
enum class Season { WINTER, SPRING, SUMMER, AUTUMN;

    companion object {
        /** The season decorating [day], or null: New Year Dec 20 – Jan 10, the others the 1st–7th. */
        fun on(day: LocalDate): Season? {
            val md = MonthDay.from(day)
            return when {
                !md.isBefore(MonthDay.of(12, 20)) || !md.isAfter(MonthDay.of(1, 10)) -> WINTER
                md.monthValue == 3 && md.dayOfMonth <= 7 -> SPRING
                md.monthValue == 6 && md.dayOfMonth <= 7 -> SUMMER
                md.monthValue == 9 && md.dayOfMonth <= 7 -> AUTUMN
                else -> null
            }
        }
    }
}

/** "Seasonal decorations" switch (Appearance), on by default; [preview] shows one now (until restart). */
object SeasonPrefs {
    private const val PREFS = "ui_prefs"
    var enabled by mutableStateOf(true)
        private set
    /** Over the whole main screen (true) or the header only. */
    var fullScreen by mutableStateOf(false)
        private set
    /** A season shown right now from Appearance, whatever the date (not saved). */
    var preview by mutableStateOf<Season?>(null)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("seasonal", true)
        fullScreen = p.getBoolean("seasonal_full", false)
    }

    fun set(ctx: Context, v: Boolean) {
        enabled = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("seasonal", v).apply()
    }

    fun setFullScreen(ctx: Context, v: Boolean) {
        fullScreen = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("seasonal_full", v).apply()
    }
}

/** The season to show now, or null. */
@Composable
private fun currentSeason(): Season? {
    SeasonPrefs.load(LocalContext.current)
    val today = remember { LocalDate.now() }
    return SeasonPrefs.preview ?: Season.on(today)?.takeIf { SeasonPrefs.enabled }
}

/**
 * A clock in ms for drifting particles, ticking ~40 times a second; frozen
 * (one still frame) in battery saver or with animations turned off.
 */
@Composable
internal fun rememberDecorClock(key: Any?, stillAt: Long = 4_000L): androidx.compose.runtime.LongState {
    val ctx = LocalContext.current
    val still = remember {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        pm.isPowerSaveMode || scale == 0f
    }
    val clock = remember { mutableLongStateOf(stillAt) }
    LaunchedEffect(key, still) {
        if (still) { clock.longValue = stillAt; return@LaunchedEffect }
        val start = withFrameNanos { it }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val ms = (now - start) / 1_000_000
                // ~40 fps is plenty for slow drifting; the GPU idles between.
                if (ms - last >= 24) { last = ms; clock.longValue = ms + stillAt }
            }
        }
    }
    return clock
}

/**
 * The season over the whole main screen (when chosen in Appearance); put it
 * last over the screen's content. It takes no touches.
 */
@Composable
fun SeasonalDecorLayer(dark: Boolean) {
    val season = currentSeason() ?: return
    if (!SeasonPrefs.fullScreen) return
    val clock = rememberDecorClock(season)
    val parts = remember(season) { particles(season, full = true) }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawSeason(season, parts, clock.longValue / 1000f, dark, full = true)
    }
}

/**
 * Seasonal particles drawn behind a header's content: snowflakes for the New
 * Year, cherry petals in spring, fireflies in summer, leaves in autumn.
 *
 * Every particle's place is a pure function of time (no per-frame state), and
 * the clock is read only while drawing — a frame redraws, nothing recomposes.
 * Paused (one still frame) in battery saver or with animations turned off.
 * Draws nothing outside its season unless previewed.
 */
fun Modifier.seasonalDecor(dark: Boolean, always: Boolean = false): Modifier = composed {
    val season = currentSeason()
    // Over the whole screen it is drawn by SeasonalDecorLayer instead.
    if (season == null || (SeasonPrefs.fullScreen && !always)) return@composed this
    val clock = rememberDecorClock(season)
    val parts = remember(season) { particles(season, full = false) }
    this.drawBehind {
        val t = clock.longValue / 1000f
        drawSeason(season, parts, t, dark, full = false)
    }
}

private class P(val x0: Float, val y0: Float, val size: Float, val speed: Float, val phase: Float, val spin: Float, val hue: Int)

private fun particles(season: Season, full: Boolean): List<P> {
    val rnd = java.util.Random(season.ordinal * 7919L + 17)
    val n = when (season) { Season.WINTER -> 34; Season.SUMMER -> 16; else -> 20 }.let { if (full) it * 2 else it }
    return List(n) {
        P(
            x0 = rnd.nextFloat(), y0 = rnd.nextFloat(),
            // Depth: a few big near ones, many small far ones.
            size = 0.35f + rnd.nextFloat().let { it * it } * 0.65f,
            speed = 0.6f + rnd.nextFloat() * 0.8f,
            phase = rnd.nextFloat() * 2f * PI.toFloat(),
            spin = (rnd.nextFloat() - 0.5f) * 1.6f,
            hue = rnd.nextInt(3),
        )
    }
}

private fun DrawScope.drawSeason(season: Season, parts: List<P>, t: Float, dark: Boolean, full: Boolean) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val dp = density
    val margin = 14f * dp
    // Across a whole screen things fall faster, or they would take minutes.
    val pace = if (full) 3.2f else 1f
    parts.forEach { p ->
        // Fall (or rise) through the band, wrapping; sway sideways.
        val fall = when (season) { Season.SUMMER -> -5f; Season.SPRING -> 13f; Season.AUTUMN -> 15f; Season.WINTER -> 10f } * p.speed * dp * pace
        val span = h + 2 * margin
        var y = ((p.y0 * span + fall * t) % span + span) % span - margin
        val drift = when (season) { Season.SPRING -> 9f; Season.AUTUMN -> 5f; else -> 0f } * dp * t
        val sway = sin(t * (0.6f + p.speed * 0.4f) + p.phase) * (if (season == Season.SUMMER) 10f else 14f) * dp
        val x = (((p.x0 * (w + 2 * margin) + drift + sway) % (w + 2 * margin)) + (w + 2 * margin)) % (w + 2 * margin) - margin
        // Fade out towards the lower edge so nothing is cut off sharply
        // (a header fades over its lower part, a screen only at the very bottom).
        val edge = if (full) (1f - ((y - h * 0.9f) / (h * 0.1f)).coerceIn(0f, 1f))
        else (1f - ((y - h * 0.62f) / (h * 0.38f)).coerceIn(0f, 1f))
        val depth = 0.45f + 0.55f * p.size
        when (season) {
            Season.WINTER -> snowflake(Offset(x, y), (2.6f + 4.2f * p.size) * dp, t * p.spin + p.phase, depth * edge, dark, p.hue == 0)
            Season.SPRING -> petal(Offset(x, y), (3.5f + 4f * p.size) * dp, t * (0.9f + p.spin) + p.phase, depth * edge, p.hue)
            Season.SUMMER -> {
                y = y.coerceAtLeast(-margin)
                firefly(Offset(x, y), (2f + 3f * p.size) * dp, (0.35f + 0.65f * abs(sin(t * 1.7f * p.speed + p.phase))) * edge, dark)
            }
            Season.AUTUMN -> leaf(Offset(x, y), (4f + 4.5f * p.size) * dp, t * (0.8f + p.spin) + p.phase, depth * edge, p.hue)
        }
    }
}

/** A six-armed flake with small branches; the far ones are soft round dots. */
private fun DrawScope.snowflake(c: Offset, r: Float, angle: Float, alpha: Float, dark: Boolean, round: Boolean) {
    val base = if (dark) Color.White else Color(0xFF8DBCEB)
    if (round || r < 3.4f * density) {
        drawCircle(Brush.radialGradient(listOf(base.copy(alpha = 0.9f * alpha), base.copy(alpha = 0f)), c, r * 0.9f), r * 0.9f, c)
        return
    }
    val stroke = (r * 0.13f).coerceAtLeast(0.8f * density)
    val col = base.copy(alpha = 0.92f * alpha)
    rotate(angle * 57.2958f, c) {
        for (i in 0 until 6) {
            rotate(i * 60f, c) {
                drawLine(col, c, Offset(c.x, c.y - r), stroke, StrokeCap.Round)
                val b = r * 0.55f
                val l = r * 0.32f
                drawLine(col, Offset(c.x, c.y - b), Offset(c.x - l * 0.7f, c.y - b - l * 0.7f), stroke * 0.85f, StrokeCap.Round)
                drawLine(col, Offset(c.x, c.y - b), Offset(c.x + l * 0.7f, c.y - b - l * 0.7f), stroke * 0.85f, StrokeCap.Round)
            }
        }
        drawCircle(col, stroke * 1.1f, c)
    }
}

private val PETALS = listOf(Color(0xFFFFC7DD), Color(0xFFF8A5C2), Color(0xFFFFDDE9))

/** A cherry petal: rounded with a notch at the tip, fluttering (its width breathes). */
private fun DrawScope.petal(c: Offset, r: Float, angle: Float, alpha: Float, hue: Int) {
    val col = PETALS[hue]
    val path = Path().apply {
        moveTo(0f, r)
        cubicTo(-r * 1.05f, r * 0.35f, -r * 0.8f, -r * 0.85f, -r * 0.18f, -r * 0.95f)
        lineTo(0f, -r * 0.7f)
        lineTo(r * 0.18f, -r * 0.95f)
        cubicTo(r * 0.8f, -r * 0.85f, r * 1.05f, r * 0.35f, 0f, r)
        close()
    }
    translate(c.x, c.y) {
        rotate(angle * 40f, Offset.Zero) {
            scale(0.35f + 0.65f * abs(cos(angle * 1.3f)), 1f, Offset.Zero) {
                drawPath(path, Brush.verticalGradient(listOf(col.copy(alpha = alpha), Color(0xFFF06292).copy(alpha = 0.85f * alpha)), -r, r))
            }
        }
    }
}

/** A firefly: a warm glow that breathes. */
private fun DrawScope.firefly(c: Offset, r: Float, glow: Float, dark: Boolean) {
    val core = if (dark) Color(0xFFFFE9A8) else Color(0xFFF2A93B)
    drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.55f * glow), core.copy(alpha = 0f)), c, r * 3.2f), r * 3.2f, c)
    drawCircle(core.copy(alpha = 0.95f * glow), r * 0.55f, c)
}

private val LEAVES = listOf(Color(0xFFF0A04B), Color(0xFFD9622B), Color(0xFFE8C04A))

/** A leaf with its midrib, tumbling (it turns over as it falls). */
private fun DrawScope.leaf(c: Offset, r: Float, angle: Float, alpha: Float, hue: Int) {
    val col = LEAVES[hue].copy(alpha = alpha)
    val path = Path().apply {
        moveTo(0f, -r * 1.25f)
        quadraticBezierTo(r * 1.0f, -r * 0.2f, 0f, r * 1.1f)
        quadraticBezierTo(-r * 1.0f, -r * 0.2f, 0f, -r * 1.25f)
        close()
    }
    translate(c.x, c.y) {
        rotate(angle * 45f, Offset.Zero) {
            scale(1f, 0.25f + 0.75f * abs(cos(angle * 1.1f)), Offset.Zero) {
                drawPath(path, col)
                drawLine(Color(0xFF7A3B12).copy(alpha = 0.45f * alpha), Offset(0f, -r * 1.05f), Offset(0f, r * 1.35f), r * 0.09f, StrokeCap.Round)
            }
        }
    }
}

/** Whether the theme behind the decor is dark (snow is white there, blue-grey on light). */
fun isDarkSurface(c: Color) = c.luminance() <= 0.5f
