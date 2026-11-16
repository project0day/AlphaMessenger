package com.alphamessenger.ui.theme

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One figure of a pattern: a contour in a 24×24 box placed at [x],[y] (tile units), or a [raw] contour in tile units. */
data class PatternItem(val d: String, val x: Float, val y: Float, val s: Float, val r: Float, val raw: Boolean = false, val motif: String? = null, val a: Float = 1f)

/**
 * A chat wallpaper pattern: figures repeated over a colour or gradient. It is
 * only numbers and contours, never an image, SVG or code: [parse] accepts a
 * narrow JSON with hard limits, and the app draws it with its own path code.
 */
data class PatternSpec(
    val name: String,
    val tile: Float,
    val lightBg: List<Color>,
    val lightInk: Color,
    val darkBg: List<Color>,
    val darkInk: Color,
    val degrees: Float,
    val opacity: Float,
    val scale: Float,
    val stroke: Float,
    val blur: Float,
    val items: List<PatternItem>,
) {
    fun bg(dark: Boolean) = if (dark) darkBg else lightBg
    fun ink(dark: Boolean) = if (dark) darkInk else lightInk

    fun toJson(): String = JSONObject().apply {
        put("v", 1).put("name", name).put("tile", tile.toDouble())
        put("light", JSONObject().put("bg", hexes(lightBg)).put("ink", hex(lightInk)))
        put("dark", JSONObject().put("bg", hexes(darkBg)).put("ink", hex(darkInk)))
        put("deg", degrees.toDouble()).put("opacity", opacity.toDouble()).put("scale", scale.toDouble())
        put("stroke", stroke.toDouble()).put("blur", blur.toDouble())
        put("items", JSONArray().apply {
            items.forEach { i ->
                put(JSONObject().apply {
                    if (i.motif != null && !i.raw) put("m", i.motif) else put("d", i.d)
                    put("x", i.x.toDouble()).put("y", i.y.toDouble()).put("s", i.s.toDouble()).put("r", i.r.toDouble())
                    if (i.raw) put("raw", true)
                    if (i.a < 1f) put("a", i.a.toDouble())
                })
            }
        })
    }.toString()

    /** The persisted wallpaper id: the whole spec, so it needs no storage of its own. */
    fun id(): String = PATTERN_PREFIX + Base64.encodeToString(toJson().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val MAX_ITEMS = 96
        const val MAX_PATH = 600
        const val MAX_RAW_PATH = 4000
        const val MAX_JSON = 12_000
        private val PATH_CHARS = Regex("^[MmLlHhVvCcSsQqTtAaZz0-9eE.,\\s+-]+$")
        private val HEX = Regex("^#?[0-9a-fA-F]{6}$")

        private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)
        private fun hexes(l: List<Color>) = JSONArray().apply { l.forEach { put(hex(it)) } }

        /** A contour is accepted only if it is plain geometry and actually parses. */
        fun pathOk(d: String, max: Int = MAX_PATH): Boolean =
            d.length in 1..max && PATH_CHARS.matches(d) && runCatching { PathParser().parsePathString(d) }.isSuccess

        /** Anything off (size, keys, colours, contours) refuses the whole pattern. */
        fun parse(json: String): PatternSpec? = runCatching {
            if (json.length > MAX_JSON) return null
            val o = JSONObject(json)
            fun num(k: String, lo: Float, hi: Float, def: Float): Float {
                val v = o.optDouble(k, def.toDouble()).toFloat()
                return if (v.isNaN()) def else v.coerceIn(lo, hi)
            }
            fun colour(v: String?): Color? = v?.trim()?.takeIf { HEX.matches(it) }?.removePrefix("#")?.toLong(16)?.let { Color(0xFF000000 or it) }
            fun side(k: String): Pair<List<Color>, Color>? {
                val s = o.optJSONObject(k) ?: return null
                val bgRaw = s.opt("bg")
                val bg = when (bgRaw) {
                    is JSONArray -> (0 until bgRaw.length()).mapNotNull { colour(bgRaw.optString(it)) }
                    else -> listOfNotNull(colour(s.optString("bg")))
                }
                if (bg.isEmpty() || bg.size > 4) return null
                return bg to (colour(s.optString("ink")) ?: return null)
            }
            val light = side("light") ?: return null
            val dark = side("dark") ?: light
            val arr = o.optJSONArray("items") ?: return null
            if (arr.length() == 0 || arr.length() > MAX_ITEMS) return null
            val tile = num("tile", 80f, 400f, 200f)
            val items = (0 until arr.length()).map { i ->
                val it = arr.getJSONObject(i)
                val raw = it.optBoolean("raw", false)
                val motif = it.optString("m").takeIf { m -> m.isNotBlank() }
                val d = if (motif != null && !raw) motifPath(motif) ?: return null else it.optString("d")
                if (!pathOk(d, if (raw) MAX_RAW_PATH else MAX_PATH)) return null
                PatternItem(
                    d,
                    it.optDouble("x", 0.0).toFloat().coerceIn(-tile, tile * 2),
                    it.optDouble("y", 0.0).toFloat().coerceIn(-tile, tile * 2),
                    it.optDouble("s", 1.0).toFloat().coerceIn(0.2f, 4f),
                    it.optDouble("r", 0.0).toFloat().coerceIn(-360f, 360f),
                    raw, motif.takeIf { !raw }, it.optDouble("a", 1.0).toFloat().coerceIn(0.05f, 1f),
                )
            }
            PatternSpec(
                o.optString("name").take(32), tile, light.first, light.second, dark.first, dark.second,
                num("deg", MESH, 360f, 180f), num("opacity", 0.03f, 0.7f, 0.18f), num("scale", 0.5f, 2f, 1f),
                num("stroke", 0.6f, 5f, 1.8f), num("blur", 0f, 16f, 0f), items,
            )
        }.getOrNull()

        fun fromId(id: String): PatternSpec? = runCatching {
            if (!id.startsWith(PATTERN_PREFIX)) return null
            val b = id.removePrefix(PATTERN_PREFIX)
            if (b.length > MAX_JSON * 2) return null
            parse(String(Base64.decode(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        }.getOrNull()
    }
}

const val PATTERN_PREFIX = "pattern:"

/** [PatternSpec.degrees] for a mesh: soft patches of the colours in the corners (older apps draw it as a plain gradient). */
const val MESH = -1f

/** A pattern's ground: flat, a gradient at its angle, or the mesh of up to four colours. */
fun Modifier.patternBackground(colors: List<Color>, degrees: Float): Modifier =
    if (degrees >= 0f || colors.size < 2) customWallpaperBackground(colors, degrees.coerceAtLeast(0f)) else drawBehind {
        drawRect(colors[0])
        val r = maxOf(size.width, size.height) * 0.85f
        val spots = listOf(androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height * 0.15f),
            androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height))
        spots.forEachIndexed { i, at ->
            val c = colors[i % colors.size]
            drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = at, radius = r))
        }
    }

/** The figures the app ships, in a 24×24 box; a pattern names them or brings its own contour. */
val MOTIFS: Map<String, String> = mapOf(
    "alpha" to "M18 7c-1.2 5.5-3.4 10-7.4 10C7.6 17 5.5 14.6 5.5 11.6S7.6 6 10.6 6c4 0 5.6 6 7.9 11",
    "bubble" to "M5 5h14a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3h-8l-5 4v-4H5a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z",
    "lines" to "M7 10h10M7 13.5h6",
    "lock" to "M6 11h12v10H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3M12 15v2.5",
    "key" to "M3 10a4 4 0 1 0 8 0a4 4 0 1 0-8 0zM11 10h10M18 10v3.5M21 10v4",
    "shield" to "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
    "star" to "M12 3l2 7 7 2-7 2-2 7-2-7-7-2 7-2z",
    "plus" to "M12 7v10M7 12h10",
    "dot" to "M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0z",
    "ring" to "M8.5 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0z",
    "heart" to "M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z",
    "signal" to "M12 20v-7M8.5 9.5a5 5 0 0 1 7 0M5.5 6.5a9.2 9.2 0 0 1 13 0",
    "moon" to "M19 14.5A7.5 7.5 0 1 1 9.5 5a6 6 0 0 0 9.5 9.5z",
    "wave" to "M2 12c2.5-3.5 4.5-3.5 7 0s4.5 3.5 7 0 4.5-3.5 6-1.5",
    "smile" to "M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0zM9 14.5c1.6 1.6 4.4 1.6 6 0M9.5 10h0M14.5 10h0",
    "leaf" to "M5 19C5 10 10 5 19 5c0 9-5 14-14 14zM5 19l7-7",
    // Glyphs for the falling-code pattern.
    "g0" to "M10 5h4a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
    "g1" to "M10 7l3-2v14M10 19h6",
    "g2" to "M8 8h8M6 16h12",
    "g3" to "M9 6v8M15 5v9c0 3-2 5-5 6",
    "g4" to "M7 7l1.5 3M11 6l1.5 3M18 6c-1 7-5 11-11 13",
    "g5" to "M10 6c-1 5-3 9-5 12M14 6c1 5 3 9 5 12",
    "g6" to "M7 7h10l-3 4M9 11h7c-1 4-4 7-8 8",
    "g7" to "M12 5v14M7 9h10M8 15l4-3 4 3",
    // Alpha's own drawn figures.
    "d_ghost" to "M6 21V11a6 6 0 0 1 12 0v10l-2-1.6-2 1.6-2-1.6-2 1.6-2-1.6zM9.5 11.2v1.4M14.5 11.2v1.4M11 15.4c.6.5 1.4.5 2 0",
    "d_ghostwave" to "M5 21V12a5.5 5.5 0 0 1 11 0v9l-1.8-1.5-1.9 1.5-1.8-1.5-1.8 1.5-1.9-1.5zM8.3 12v1.2M12.7 12v1.2M9.6 15.6c.5.4 1.3.4 1.8 0M16 13.5c1.6-.3 2.8-1.5 3.2-3.2M18.2 8.8l1 1.5 1.6-.6",
    "d_onion" to "M12 3.5c-.4 1.5-1.2 2.4-1.2 4M12 3.5c.4 1.5 1.2 2.4 1.2 4M12 7.5c4 2 7 5 7 8.8 0 2.6-3.1 4.4-7 4.4s-7-1.8-7-4.4C5 12.5 8 9.5 12 7.5zM12 9.3c-2.3 1.8-3.6 4.2-3.6 7.6M12 9.3c2.3 1.8 3.6 4.2 3.6 7.6M12 9.6v11",
    "d_bubbleheart" to "M4 4.5h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9l-4 3v-3H4a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2zM12 14.6s-3.4-2-3.4-4.3a1.7 1.7 0 0 1 3.4-.8 1.7 1.7 0 0 1 3.4.8c0 2.3-3.4 4.3-3.4 4.3z",
    "d_locksmile" to "M6 11h12a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 18 21H6a1.5 1.5 0 0 1-1.5-1.5v-7A1.5 1.5 0 0 1 6 11zM8.5 11V8a3.5 3.5 0 0 1 7 0v3M9.5 14.6v.8M14.5 14.6v.8M10 17.4c1.1.9 2.9.9 4 0",
    "d_keyheart" to "M7.5 6a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM10.8 12.3l9.2 6.2M17.2 16.6l-1.5 2.2M19.4 18.1l-1.4 2M7.5 11.6s-1.6-.9-1.6-2a.8.8 0 0 1 1.6-.4.8.8 0 0 1 1.6.4c0 1.1-1.6 2-1.6 2z",
    "d_wingmail" to "M7 9h10v8H7zM7 9l5 4 5-4M7 10.5C4.5 9 2.6 9.6 2 11.4c1.6-.1 3 .4 5 1.8M7 13c-2-.6-3.4 0-4 1.4 1.4.2 2.7.4 4 .1M17 10.5c2.5-1.5 4.4-.9 5 .9-1.6-.1-3 .4-5 1.8M17 13c2-.6 3.4 0 4 1.4-1.4.2-2.7.4-4 .1",
    "d_tower" to "M12 10v11M9 21h6M12 10l-3 11M12 10l3 11M10.3 16h3.4M11 9a1 1 0 1 0 2 0 1 1 0 1 0-2 0zM8.3 6.6a5 5 0 0 1 7.4 0M5.8 4.2a8.5 8.5 0 0 1 12.4 0",
    "d_planet" to "M7 12a5 5 0 1 0 10 0 5 5 0 1 0-10 0zM4.2 15.6c-1.3-1.6 1.9-3.7 7.8-5.3s9.8-1.5 8.6.3M19.5 3.5v2.4M18.3 4.7h2.4M4.5 6.5v1.6M3.7 7.3h1.6",
    "d_rocket" to "M12 2.8c3 2 4.5 5 4.5 9l-2 3.6h-5L7.5 11.8c0-4 1.5-7 4.5-9zM10.5 10a1.5 1.5 0 1 0 3 0 1.5 1.5 0 1 0-3 0zM9.5 15.4 7 18.2l3-.6M14.5 15.4 17 18.2l-3-.6M11 18.6c0 1.2.5 2 1 2.6.5-.6 1-1.4 1-2.6",
    "d_cat" to "M5 8.2 6 4l3.5 3a8 8 0 0 1 5 0L18 4l1 4.2c1 1.3 1.5 2.8 1.5 4.4 0 4.4-3.8 7.4-8.5 7.4s-8.5-3-8.5-7.4c0-1.6.5-3.1 1.5-4.4zM9 11.8v1M15 11.8v1M11.2 14.4h1.6l-.8.9zM12 15.3c0 .9-1 1.5-2 1.2M12 15.3c0 .9 1 1.5 2 1.2M4.2 14.3l-2.6-.4M4.6 16l-2.3.9M19.8 14.3l2.6-.4M19.4 16l2.3.9",
    "d_coffee" to "M5 10h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5zM16 11.5h1.4a2.2 2.2 0 0 1 0 4.4H15.7M8.5 7.5c0-1 1-1.5 1-2.6M12 7.5c0-1 1-1.5 1-2.6M10.5 15.3s-1.7-1-1.7-2.1a.85.85 0 0 1 1.7-.4.85.85 0 0 1 1.7.4c0 1.1-1.7 2.1-1.7 2.1z",
    "d_headphones" to "M4 15v-3a8 8 0 0 1 16 0v3M4 14h2.5a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1zM20 14h-2.5a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1H19a1 1 0 0 0 1-1z",
    "d_camera" to "M4 8h3l1.5-2h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM8.5 13a3.5 3.5 0 1 0 7 0 3.5 3.5 0 1 0-7 0zM17.5 10.5h1",
    "d_crown" to "M4 19h16M4.5 16.5 3 8l5 3.6L12 5l4 6.6L21 8l-1.5 8.5zM12 13.6v.4",
    "d_bolt" to "M13 3 6 13.5h5L10 21l8-11.5h-5.2z",
    "d_rain" to "M7 16a4 4 0 0 1-.6-8 5.5 5.5 0 0 1 10.6 1.3A3.4 3.4 0 0 1 17 16zM9 19l-.8 2M12.5 19l-.8 2M16 19l-.8 2",
    "d_moonstars" to "M15 4.5a7.5 7.5 0 1 0 4.5 13.4A8 8 0 0 1 15 4.5zM18.5 5v2.4M17.3 6.2h2.4M20.5 10.2v1.6M19.7 11h1.6",
    "d_plant" to "M8 15h8l-1 6H9zM12 15V9M12 11.5c-1-3-4-4.2-6-3.2 1 2.6 3.5 3.6 6 3.2zM12 9.5c1-3 3.5-4.6 6-4-.6 2.6-3 4.2-6 4z",
    "d_cactus" to "M10 20V7a2 2 0 0 1 4 0v13M10 14H8a2 2 0 0 1-2-2V9M14 11.5h2a2 2 0 0 0 2-2V7.5M7 20h10M11.2 9.5h.3M12.8 12.5h.3",
    "d_icecream" to "M8 11.5h8L12 21zM8 11.5a4 4 0 0 1 8 0M9.4 7a3 3 0 0 1 5.4.5M10 14l2 2M13.4 13.4l.8.8",
    "d_pizza" to "M4 6c5-2 11-2 16 0L12 21zM5.6 8.8c4.3-1.4 8.5-1.4 12.8 0M9.5 11.5a1 1 0 1 0 2 0 1 1 0 1 0-2 0zM12.5 14.5a1 1 0 1 0 2 0 1 1 0 1 0-2 0zM13.5 10.8h.4",
    "d_donut" to "M4 12a8 8 0 1 0 16 0 8 8 0 1 0-16 0zM10 12a2 2 0 1 0 4 0 2 2 0 1 0-4 0zM6.6 9.4l1 .6M15.8 7.3l.5 1M17.6 13l1-.3M8 15.6l-.6.9M13 17l.3 1",
    "d_notes" to "M9 17V6l10-2v11M9 17a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM19 15a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM9 9.5l10-2",
    "d_gamepad" to "M7 9h10a4 4 0 0 1 3.9 4.9l-.7 3.2a2 2 0 0 1-3.5.8L15 16H9l-1.7 1.9a2 2 0 0 1-3.5-.8l-.7-3.2A4 4 0 0 1 7 9zM7.5 11.5v3M6 13h3M15.4 12.2h.2M17 14h.2",
    "d_cool" to "M3.5 12a8.5 8.5 0 1 0 17 0 8.5 8.5 0 1 0-17 0zM6.5 10h4.5l-.5 2a1.6 1.6 0 0 1-3.2 0zM13 10h4.5l-.5 2a1.6 1.6 0 0 1-3.2 0zM11 10.5h2M9 16c1.8 1.4 4.2 1.4 6 0",
    "d_sparkle2" to "M12 3c.6 4.5 2.4 6.3 7 7-4.6.7-6.4 2.5-7 7-.6-4.5-2.4-6.3-7-7 4.6-.7 6.4-2.5 7-7zM19 16.5v3M17.5 18h3",
    "d_shieldok" to "M12 3l7 2.8v5.4c0 4.6-3 8-7 9.8-4-1.8-7-5.2-7-9.8V5.8zM9 12l2.2 2.2L15.5 10",
)


/** A figure by name: one of [MOTIFS], or an icon of the library. */
fun motifPath(name: String): String? = MOTIFS[name] ?: PatternIcons.path(name)

/**
 * The library of pattern figures shipped in assets (`pattern_icons.json`, open
 * icons in the same 24×24 line style): name → contour and search words.
 * Read once, on first use.
 */
object PatternIcons {
    @Volatile private var app: Context? = null
    private val all: Map<String, Pair<String, String>> by lazy {
        val ctx = app ?: return@lazy emptyMap()
        runCatching {
            val o = JSONObject(ctx.assets.open("pattern_icons.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { it.getString(0) to it.optString(1) } }
        }.getOrDefault(emptyMap())
    }

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun path(name: String): String? = all[name]?.first

    /** Icons whose name or words match [query] (English words), the closest first. */
    fun search(query: String, limit: Int = 60): List<String> {
        val words = query.lowercase().split(Regex("[^a-z0-9-]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        return all.entries.mapNotNull { (name, v) ->
            val tags = v.second.split(',')
            val parts = name.split('-')
            var score = 0
            for (w in words) score += when {
                name == w -> 5
                w in parts -> 3
                tags.any { it == w } -> 2
                tags.any { it.contains(w) } || name.contains(w) -> 1
                else -> 0
            }
            if (score > 0) name to score else null
        }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length }).take(limit).map { it.first }
    }
}

/**
 * Lays figures out over a tile the way a drawn wallpaper looks: evenly spread,
 * never touching (distances measured across the tile edges too, so the repeat
 * has no seams or clumps), the larger ones placed first, the figures dealt in
 * turn so each appears about as often, each with a little turn.
 */
object PatternLayout {
    enum class Density(val fill: Float) { SPARSE(0.16f), NORMAL(0.25f), DENSE(0.34f) }
    enum class Size(val range: ClosedFloatingPointRange<Float>) { SMALL(0.6f..0.9f), MEDIUM(0.8f..1.2f), LARGE(1.1f..1.6f) }

    fun scatter(icons: List<String>, tile: Float, fill: Float, size: ClosedFloatingPointRange<Float>, rotate: Float, seed: Long): List<PatternItem> {
        val usable = icons.filter { motifPath(it) != null }.distinct()
        if (usable.isEmpty()) return emptyList()
        var s = seed and 0xFFFFFFFFL
        fun r(): Float { s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL; return s / 4294967296f }
        // A figure fills about this much of its 24-unit box.
        fun reach(sc: Float) = 12f * 1.15f * sc * 0.9f
        val avg = (size.start + size.endInclusive) / 2f
        val count = (fill * tile * tile / (Math.PI.toFloat() * reach(avg) * reach(avg))).roundToInt().coerceIn(6, 90)
        val sizes = List(count) { size.start + r() * (size.endInclusive - size.start) }.sortedDescending()
        val bag = ArrayList<String>()
        fun next(): String {
            if (bag.isEmpty()) {
                bag += usable
                for (i in bag.size - 1 downTo 1) { val j = (r() * (i + 1)).toInt(); val t = bag[i]; bag[i] = bag[j]; bag[j] = t }
            }
            return bag.removeAt(bag.size - 1)
        }
        val placed = ArrayList<FloatArray>() // x, y, reach
        val out = ArrayList<PatternItem>()
        val gap = 3f
        for (sc in sizes) {
            val rr = reach(sc)
            for (attempt in 0 until 60) {
                val x = r() * tile
                val y = r() * tile
                val free = placed.none { p ->
                    var dx = kotlin.math.abs(x - p[0])
                    var dy = kotlin.math.abs(y - p[1])
                    dx = minOf(dx, tile - dx); dy = minOf(dy, tile - dy)
                    hypot(dx, dy) < rr + p[2] + gap
                }
                if (!free) continue
                placed += floatArrayOf(x, y, rr)
                val m = next()
                out += PatternItem(motifPath(m)!!, x, y, sc, ((r() * 2f - 1f) * rotate).roundToInt().toFloat(), motif = m)
                break
            }
        }
        fillGaps(out, placed, tile, size, rotate, gap, ::reach, ::next, ::r)
        return out
    }

    /**
     * Random throws leave holes. Each round puts a figure into the largest empty
     * spot left (on a grid of candidate points, measured across the tile edges),
     * sized to fit it, down to a small filler, until no hole that big remains.
     * Clearances are updated only against the newest figure, so it stays fast.
     */
    private fun fillGaps(
        out: MutableList<PatternItem>, placed: MutableList<FloatArray>, tile: Float,
        size: ClosedFloatingPointRange<Float>, rotate: Float, gap: Float,
        reach: (Float) -> Float, next: () -> String, r: () -> Float,
    ) {
        val n = 40
        val step = tile / n
        val minReach = reach(size.start * 0.6f)
        val maxReach = reach(size.endInclusive)
        fun dist(x: Float, y: Float, p: FloatArray): Float {
            var dx = kotlin.math.abs(x - p[0]); var dy = kotlin.math.abs(y - p[1])
            dx = minOf(dx, tile - dx); dy = minOf(dy, tile - dy)
            return hypot(dx, dy) - p[2]
        }
        val clear = FloatArray(n * n) { i ->
            val x = (i % n + 0.5f) * step; val y = (i / n + 0.5f) * step
            placed.minOfOrNull { dist(x, y, it) } ?: Float.MAX_VALUE
        }
        while (out.size < PatternSpec.MAX_ITEMS - 2) {
            var best = -1
            for (i in clear.indices) if (best < 0 || clear[i] > clear[best]) best = i
            val room = clear[best] - gap
            if (room < minReach) break
            val rr = minOf(room, maxReach)
            val x = (best % n + 0.5f) * step
            val y = (best / n + 0.5f) * step
            val p = floatArrayOf(x, y, rr)
            placed += p
            val m = next()
            val sc = rr / (12f * 1.15f * 0.9f)
            out += PatternItem(motifPath(m)!!, x, y, sc, ((r() * 2f - 1f) * rotate).roundToInt().toFloat(), motif = m)
            for (i in clear.indices) {
                val d = dist((i % n + 0.5f) * step, (i / n + 0.5f) * step, p)
                if (d < clear[i]) clear[i] = d
            }
        }
    }
}

/** A brand pattern: which figures, how they lie, and its colour pairs (light bg, ink, dark bg, ink). */
class PatternRecipe(
    val key: String, val name: String, val tile: Float, val set: List<String>,
    val size: ClosedFloatingPointRange<Float>, val palettes: List<List<Long>>,
    val fill: Float = PatternLayout.Density.NORMAL.fill, val rotate: Float = 30f, val mesh: Boolean = false,
    /** Columns of glyphs ([columns] of them), each a streak whose head is bright and whose tail fades. */
    val rain: Boolean = false, val columns: Int = 8, val opacity: Float = 0.18f,
) {
    /** Small and a little brighter, for a picker tile. */
    val thumb: PatternSpec by lazy { spec(0).let { it.copy(scale = 0.42f, opacity = (it.opacity * 1.8f).coerceAtMost(0.6f)) } }

    /** The same seed always gives the same pattern. */
    fun spec(palette: Int = 0, seed: Long = defaultSeed): PatternSpec {
        val p = palettes[palette.coerceIn(0, palettes.size - 1)]
        fun made(items: List<PatternItem>, stroke: Float) = PatternSpec(name, tile, listOf(Color(p[0])), Color(p[1]), listOf(Color(p[2])), Color(p[3]),
            180f, opacity, 1f, stroke, 0f, items.take(PatternSpec.MAX_ITEMS))
        if (rain) {
            var s = seed and 0xFFFFFFFFL
            fun r(): Float { s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL; return s / 4294967296f }
            val items = ArrayList<PatternItem>()
            val cell = tile / columns
            for (col in 0 until columns) {
                val len = 3 + (r() * 6).toInt()
                val head = (r() * columns).toInt()
                for (k in 0 until len) {
                    val row = ((head - k) % columns + columns) % columns
                    val m = set[(r() * set.size).toInt().coerceAtMost(set.size - 1)]
                    val sc = size.start + r() * (size.endInclusive - size.start)
                    // Brightest at the head, fading up the tail; wrapped rows keep the streak seamless.
                    val d = motifPath(m) ?: continue
                    items += PatternItem(d, (col + 0.5f) * cell, (row + 0.5f) * cell, sc, 0f, motif = m,
                        a = if (k == 0) 1f else (0.85f - k * (0.75f / len)).coerceAtLeast(0.12f))
                }
            }
            return made(items, 1.6f)
        }
        val items = ArrayList(PatternLayout.scatter(set, tile, fill, size, rotate, seed))
        if (mesh) meshOf(items.filter { it.motif == "ring" || it.motif == "dot" })?.let { items.add(0, it) }
        return made(items, 1.7f)
    }

    private val defaultSeed: Long get() = 7L + PATTERN_RECIPES.indexOf(this) * 31L

    /** Relay lines between near nodes (across tile edges too), stopping short of them. */
    private fun meshOf(nodes: List<PatternItem>): PatternItem? {
        val d = StringBuilder()
        nodes.forEachIndexed { i, a ->
            nodes.drop(i + 1).forEach { b ->
                for (ox in -1..1) for (oy in -1..1) {
                    val bx = b.x + ox * tile
                    val by = b.y + oy * tile
                    val dist = hypot(bx - a.x, by - a.y)
                    if (dist < tile * 0.32f && dist > 30f) {
                        val k = 14f / dist
                        d.append("M%.1f %.1fL%.1f %.1f".format(java.util.Locale.ROOT,
                            a.x + (bx - a.x) * k, a.y + (by - a.y) * k, bx - (bx - a.x) * k, by - (by - a.y) * k))
                    }
                }
            }
        }
        return d.takeIf { it.isNotEmpty() && it.length <= PatternSpec.MAX_RAW_PATH }?.let { PatternItem(it.toString(), 0f, 0f, 1f, 0f, raw = true) }
    }
}

private fun pal(vararg p: Long): List<List<Long>> = p.toList().chunked(4)

val PATTERN_RECIPES: List<PatternRecipe> = listOf(
    PatternRecipe("drawn", "Рисунки Альфы", 300f, listOf("d_ghost", "d_ghostwave", "d_onion", "d_bubbleheart", "d_locksmile", "d_keyheart", "d_wingmail", "d_tower", "d_planet", "d_rocket", "d_cat", "d_coffee", "d_headphones", "d_camera", "d_crown", "d_bolt", "d_rain", "d_moonstars", "d_plant", "d_cactus", "d_icecream", "d_pizza", "d_donut", "d_notes", "d_gamepad", "d_cool", "d_sparkle2", "d_shieldok"), 0.95f..1.35f,
        pal(0xFFECE7FB, 0xFF6D4FD6, 0xFF121019, 0xFFA78BFA, 0xFFE8F1EC, 0xFF2F7A5A, 0xFF0E1612, 0xFF8FD6B4, 0xFFF6ECE4, 0xFFA35A2C, 0xFF1B130E, 0xFFEAB08A),
        fill = 0.3f),
    PatternRecipe("matrix", "Матрица", 192f, listOf("g0", "g1", "g2", "g3", "g4", "g5", "g6", "g7", "alpha"), 0.62f..0.78f,
        pal(0xFFE7F5EA, 0xFF138A36, 0xFF030A05, 0xFF3DFF7A, 0xFFE6F3F7, 0xFF0E7490, 0xFF02090C, 0xFF5EEBFF, 0xFFF1EAFB, 0xFF7C3AED, 0xFF07030D, 0xFFC084FC),
        rain = true, opacity = 0.42f),
    PatternRecipe("alpha", "Альфа", 240f, listOf("alpha", "alpha", "sparkles", "star", "message-circle", "lock", "shield", "key-round", "send", "smile"), 0.8f..1.4f,
        pal(0xFFECE7FB, 0xFF6D4FD6, 0xFF121019, 0xFFA78BFA, 0xFFFBE9F4, 0xFFC0267A, 0xFF1A0F1A, 0xFFF0ABFC, 0xFFE9F1FB, 0xFF3B5BD6, 0xFF0E1420, 0xFF93B4FF)),
    PatternRecipe("net", "Связь", 260f, listOf("ring", "ring", "ring", "dot", "signal", "radio-tower", "satellite-dish", "wifi", "globe"), 0.75f..1.2f,
        pal(0xFFE6F2F1, 0xFF2F8A84, 0xFF0F1B1D, 0xFF6FD3C6, 0xFFEEF0FB, 0xFF5560D0, 0xFF10121E, 0xFF9AA5FF, 0xFFF3EFE6, 0xFF9A6B23, 0xFF1B160E, 0xFFE2B56A),
        fill = 0.2f, mesh = true),
    PatternRecipe("doodle", "Каракули", 300f, listOf("smile", "heart", "leaf", "bubble", "star", "music", "coffee", "camera", "cat", "sun", "cloud", "gift",
        "plane", "rocket", "flower", "ghost", "headphones", "umbrella", "anchor", "paw-print", "gamepad-2", "lightbulb", "glasses", "crown", "moon"), 0.75f..1.15f,
        pal(0xFFEEE9DC, 0xFF568D8C, 0xFF172A31, 0xFF75AAA5, 0xFFF4EAD9, 0xFFB0663A, 0xFF21160F, 0xFFE0A37A, 0xFFE9ECF1, 0xFF5A6270, 0xFF14171C, 0xFFA9B1BF),
        fill = 0.32f),
    PatternRecipe("space", "Космос", 300f, listOf("rocket", "orbit", "satellite", "moon", "moon-star", "sparkles", "sparkle", "sun", "telescope", "earth", "star", "cloud-moon", "dot", "plus"), 0.6f..1.3f,
        pal(0xFFE8EBF7, 0xFF3F4AA8, 0xFF0B0D1C, 0xFFC7CDFA, 0xFFF3EAFA, 0xFF8B3FB8, 0xFF120A1A, 0xFFE3B5FF, 0xFFE6F0F5, 0xFF22708F, 0xFF08141A, 0xFF9FE0F7),
        fill = 0.28f, rotate = 40f),
    PatternRecipe("cafe", "Кофейня", 300f, listOf("coffee", "cup-soda", "croissant", "cookie", "cake-slice", "donut", "ice-cream-cone", "sandwich", "popcorn",
        "candy", "cherry", "apple", "citrus", "grape", "banana", "dessert", "milk", "wine"), 0.75f..1.15f,
        pal(0xFFF4ECE1, 0xFF8A5A34, 0xFF1C1611, 0xFFD9A774, 0xFFFCEFF1, 0xFFB4446A, 0xFF1E1015, 0xFFF0A3BC, 0xFFEFF5EA, 0xFF4F7F3A, 0xFF121A0E, 0xFFA9D68F),
        fill = 0.32f),
    PatternRecipe("nature", "Природа", 300f, listOf("leaf", "trees", "tree-pine", "tree-deciduous", "flower", "flower-2", "sprout", "mountain", "sun", "cloud",
        "bird", "fish", "bug", "snail", "rabbit", "squirrel", "feather", "clover", "shell", "rainbow", "snowflake", "droplet"), 0.7f..1.2f,
        pal(0xFFEAF3E6, 0xFF3F7D3A, 0xFF0F1A0F, 0xFF8FD18A, 0xFFE8F1F5, 0xFF2C6E8A, 0xFF0B151A, 0xFF8CCBE6, 0xFFF6F0E2, 0xFF8A6A1F, 0xFF1A160B, 0xFFE0C36A),
        fill = 0.3f),
    PatternRecipe("pets", "Котики", 280f, listOf("cat", "dog", "paw-print", "paw-print", "fish", "bone", "heart", "rabbit", "bird", "turtle", "squirrel", "rat", "egg"), 0.75f..1.25f,
        pal(0xFFF6EEE6, 0xFFA0603A, 0xFF1E140F, 0xFFE8AE85, 0xFFF0ECF8, 0xFF6A4FB5, 0xFF130F1D, 0xFFB9A4F2, 0xFFEAF2EE, 0xFF3D7A63, 0xFF0F1915, 0xFF8ED4B8),
        fill = 0.3f),
    PatternRecipe("music", "Музыка", 280f, listOf("music", "music-2", "music-3", "music-4", "headphones", "guitar", "mic", "disc", "disc-3", "radio", "drum", "speaker", "audio-lines"), 0.75f..1.25f,
        pal(0xFFF1ECFA, 0xFF6B3FC0, 0xFF120D1C, 0xFFC4A8FF, 0xFFFBEDEA, 0xFFC0452D, 0xFF1E100D, 0xFFF4A28F, 0xFFE8F2F4, 0xFF20707E, 0xFF0A1517, 0xFF84D6E4),
        fill = 0.28f),
    PatternRecipe("travel", "Путешествия", 300f, listOf("plane", "map", "compass", "tent", "mountain-snow", "camera", "luggage", "ship", "train-front", "car",
        "map-pin", "ticket", "bike", "sailboat", "anchor", "caravan", "sun", "tree-palm"), 0.75f..1.2f,
        pal(0xFFE6F0F7, 0xFF2D6A9A, 0xFF0B141B, 0xFF8EC2EA, 0xFFF7EFE3, 0xFFA3662A, 0xFF1C150D, 0xFFEAB878, 0xFFEAF4EC, 0xFF3A7D52, 0xFF0E1911, 0xFF92D7A8),
        fill = 0.3f),
    PatternRecipe("party", "Праздник", 280f, listOf("gift", "party-popper", "cake", "candy", "sparkles", "star", "heart", "crown", "wine", "music", "cake-slice", "lollipop"), 0.75f..1.25f,
        pal(0xFFFBEEF4, 0xFFC0267A, 0xFF1C0F16, 0xFFF39AC7, 0xFFFFF4E0, 0xFFB7791F, 0xFF1D160A, 0xFFF2C66B, 0xFFEEF0FC, 0xFF4F5BD5, 0xFF0F1120, 0xFFA5ADFF),
        fill = 0.3f, rotate = 35f),
    PatternRecipe("tech", "Техника", 300f, listOf("cpu", "wifi", "smartphone", "laptop", "gamepad-2", "headphones", "satellite-dish", "battery", "bluetooth", "code",
        "terminal", "rocket", "bot", "keyboard", "mouse", "joystick", "radio-tower", "router"), 0.75f..1.2f,
        pal(0xFFE9EEF4, 0xFF3E5C7E, 0xFF0C1118, 0xFF93B6DD, 0xFFE7F5EA, 0xFF178A45, 0xFF06120A, 0xFF5EE59A, 0xFFF0EAFB, 0xFF7240D0, 0xFF0F0A19, 0xFFC1A3FF),
        fill = 0.28f, rotate = 20f),
    PatternRecipe("winter", "Зима", 300f, listOf("snowflake", "snowflake", "snowman", "christmas-tree", "gift", "candle", "bell", "star", "sparkles", "sleigh",
        "snowboarding", "mug", "cookie", "candy-cane", "deer", "ice-skating", "cloud-snow", "tree-pine"), 0.7f..1.25f,
        pal(0xFFE8F1F8, 0xFF2F6E9E, 0xFF0A1219, 0xFF9CCBF0, 0xFFF7ECEC, 0xFFB03A3A, 0xFF1C0E0E, 0xFFF0A0A0, 0xFFEAF4EE, 0xFF2E7550, 0xFF0C1710, 0xFF8FD6AE),
        fill = 0.3f),
    PatternRecipe("keys", "Ключи", 260f, listOf("lock", "key", "shield", "key-round", "fingerprint", "eye", "dot", "plus"), 0.8f..1.3f,
        pal(0xFFEEF0F3, 0xFF4A5568, 0xFF111318, 0xFFA0AEC0, 0xFFEFE9FB, 0xFF7C3AED, 0xFF130F1D, 0xFFC4B5FD, 0xFFFBF1E6, 0xFFB45309, 0xFF1C140C, 0xFFFBBF6A)),
)

/**
 * One tile of [spec] drawn into a bitmap, with every figure also drawn at its
 * eight wrapped copies, so figures crossing an edge continue across it and the
 * repeat has no seams. Strokes keep their width whatever a figure's size.
 */
fun renderPatternTile(spec: PatternSpec, ink: Color, density: Float): ImageBitmap {
    val t = (spec.tile * spec.scale * density).coerceIn(32f, 1024f)
    val px = t.roundToInt()
    val k = px / spec.tile
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = ink.toArgb()
    }
    val paths = HashMap<String, android.graphics.Path>()
    for (it in spec.items) {
        val path = paths.getOrPut(it.d) { PathParser().parsePathString(it.d).toPath().asAndroidPath() }
        val unit = if (it.raw) k else k * it.s * 1.15f
        paint.strokeWidth = spec.stroke * density / unit
        paint.alpha = (it.a * 255).roundToInt()
        for (ox in -1..1) for (oy in -1..1) {
            c.save()
            if (it.raw) {
                c.translate(ox * px.toFloat(), oy * px.toFloat())
                c.scale(k, k)
            } else {
                c.translate((it.x + ox * spec.tile) * k, (it.y + oy * spec.tile) * k)
                c.rotate(it.r)
                c.scale(unit, unit)
                c.translate(-12f, -12f)
            }
            c.drawPath(path, paint)
            c.restore()
        }
    }
    return bmp.asImageBitmap()
}

/** Paints [spec] over its background: light or dark by the theme, the figures repeated, softened by its blur. */
@Composable
fun PatternWallpaper(spec: PatternSpec, modifier: Modifier = Modifier, dark: Boolean = AlphaTheme.colorScheme.bgPrimary.luminance() < 0.5f) {
    val density = LocalDensity.current.density
    val ink = spec.ink(dark)
    val tile by produceState<ImageBitmap?>(null, spec, ink, density) {
        value = withContext(Dispatchers.Default) { runCatching { renderPatternTile(spec, ink, density) }.getOrNull() }
    }
    Box(modifier.clipToBounds().patternBackground(spec.bg(dark), spec.degrees)) {
        val bmp = tile ?: return@Box
        // The layer reaches past the edges by the blur, so the softened edge falls outside the screen.
        val reach = (spec.blur * 2.5f).dp
        Box(
            Modifier.fillMaxSize()
                .layout { m, c ->
                    val e = reach.roundToPx()
                    val p = m.measure(c.copy(maxWidth = c.maxWidth + 2 * e, minWidth = c.maxWidth + 2 * e,
                        maxHeight = c.maxHeight + 2 * e, minHeight = c.maxHeight + 2 * e))
                    layout(c.maxWidth, c.maxHeight) { p.place(-e, -e) }
                }
                .then(if (spec.blur > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(spec.blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier)
                .drawBehind {
                    drawRect(ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated)), alpha = spec.opacity)
                },
        )
    }
}

/** Patterns the user keeps (made in the editor or offered by the assistant), newest first, at most [MAX]. */
object PatternStore {
    private const val MAX = 12
    var mine by mutableStateOf<List<PatternSpec>>(emptyList())
        private set
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chat_patterns", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        mine = prefs(ctx).getString("mine", "").orEmpty().split('\n').filter { it.isNotBlank() }.mapNotNull { PatternSpec.fromId(it) }
    }

    fun save(ctx: Context, spec: PatternSpec) {
        load(ctx)
        val id = spec.id()
        mine = (listOf(spec) + mine.filter { it.id() != id }).take(MAX)
        prefs(ctx).edit().putString("mine", mine.joinToString("\n") { it.id() }).apply()
    }

    fun delete(ctx: Context, spec: PatternSpec) {
        load(ctx)
        val id = spec.id()
        mine = mine.filter { it.id() != id }
        prefs(ctx).edit().putString("mine", mine.joinToString("\n") { it.id() }).apply()
    }
}
