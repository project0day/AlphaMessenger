package com.alphamessenger.ui.theme

import androidx.annotation.StringRes
import com.alphamessenger.R
import androidx.compose.foundation.background
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// ══════════════════════════════════════════════════════════════════════
// Font size — discrete classic scale
// ══════════════════════════════════════════════════════════════════════

/**
 * Discrete font-size tier. Used both for the overall UI scale and for the
 * message-text scale independently.
 *
 * Multipliers are deliberately conservative so layouts don't break:
 *  - SMALL  ≈ 92 % of the design baseline
 *  - NORMAL = the design baseline (1.0)
 *  - LARGE  ≈ 112 % of the design baseline
 */
enum class FontSize(@StringRes val labelRes: Int, val factor: Float) {
    SMALL(R.string.font_size_small, 0.92f),
    NORMAL(R.string.font_size_normal, 1.0f),
    LARGE(R.string.font_size_large, 1.12f);

    companion object {
        val DEFAULT = NORMAL
    }
}

/** Multiplies an sp size by a raw factor. */
fun Float.spScaled(factor: Float): TextUnit = (this * factor).sp

/** Scales an sp size by a [FontSize] factor. */
fun Float.spScaled(tier: FontSize): TextUnit = (this * tier.factor).sp

/**
 * Returns a copy of a Material 3 [Typography] where every [TextStyle.fontSize]
 * is multiplied by [factor]. Used to globally rescale the interface text.
 *
 * We touch only `fontSize` (not lineHeight/letterSpacing) so vertical rhythm
 * stays close to the design — large text gets slightly tighter line spacing
 * relative to its size, which reads better than a naive uniform scale.
 */
fun Typography.scaledBy(factor: Float): Typography = copy(
    displayLarge     = displayLarge.scaleFont(factor),
    displayMedium    = displayMedium.scaleFont(factor),
    displaySmall     = displaySmall.scaleFont(factor),
    headlineLarge    = headlineLarge.scaleFont(factor),
    headlineMedium   = headlineMedium.scaleFont(factor),
    headlineSmall    = headlineSmall.scaleFont(factor),
    titleLarge       = titleLarge.scaleFont(factor),
    titleMedium      = titleMedium.scaleFont(factor),
    titleSmall       = titleSmall.scaleFont(factor),
    bodyLarge        = bodyLarge.scaleFont(factor),
    bodyMedium       = bodyMedium.scaleFont(factor),
    bodySmall        = bodySmall.scaleFont(factor),
    labelLarge       = labelLarge.scaleFont(factor),
    labelMedium      = labelMedium.scaleFont(factor),
    labelSmall       = labelSmall.scaleFont(factor),
)

private fun TextStyle.scaleFont(factor: Float): TextStyle =
    copy(fontSize = (fontSize.value * factor).sp)

// ══════════════════════════════════════════════════════════════════════
// Chat wallpaper — classic backgrounds (default / solid / gradient / gallery)
// ══════════════════════════════════════════════════════════════════════

/**
 * One chat-background option. Sealed so the renderer can exhaustive-`when`
 * on the four kinds of background.
 *
 *  - [ChatWallpaper.Default]  → the theme's [AlphaColorScheme.bgChat] colour
 *  - [ChatWallpaper.Solid]    → a flat colour picked from a small palette
 *  - [ChatWallpaper.Gradient] → a two-stop linear gradient preset
 *  - [ChatWallpaper.Gallery]  → the user's own photo (a content:// uri copied
 *                               into app-private storage)
 *
 * `id` uniquely identifies every option (persisted to DataStore as a String).
 */
sealed class ChatWallpaper {
    abstract val id: String

    /** Use the current theme's chat colour — no extra layer. */
    data object Default : ChatWallpaper() { const val ID = "default"
        override val id: String get() = ID
    }

    /** A flat colour. */
    data class Solid(override val id: String, val color: Color) : ChatWallpaper()

    /**
     * A diagonal two-stop linear gradient. [degrees] is 0–360, where
     * 0° is bottom→top and 90° is left→right (CSS convention).
     */
    data class Gradient(
        override val id: String,
        val start: Color,
        val end: Color,
        val degrees: Float = 135f,
    ) : ChatWallpaper()

    /**
     * One the user made: a single colour, or a gradient of 2–4 colours at an
     * angle (CSS convention: 180° = top→bottom, 90° = left→right). Everything
     * lives in its [id] ("custom:RRGGBB,RRGGBB@135"), so it needs no storage.
     */
    data class Custom(override val id: String, val colors: List<Color>, val degrees: Float) : ChatWallpaper()

    /** A user-picked photo. [uri] points at a file in app-private storage. */
    data class Gallery(override val id: String, val uri: String) : ChatWallpaper()

    companion object {
        /** Sentinel persisted to DataStore when no wallpaper is chosen. */
        const val DEFAULT_ID = "default"
    }
}

/**
 * The 12 gradient presets offered in the wallpaper picker.
 *
 * Each pairs well on both dark and light themes — the chat list overlays
 * a translucent scrim, so legibility is preserved regardless of accent.
 */
val WALLPAPER_PRESETS: List<ChatWallpaper.Gradient> = listOf(
    ChatWallpaper.Gradient("g1", Color(0xFF2193B0), Color(0xFF6DD5ED)),  // Aqua
    ChatWallpaper.Gradient("g2", Color(0xFF13547A), Color(0xFF80D0C7)),  // Teal mist
    ChatWallpaper.Gradient("g3", Color(0xFF0F2027), Color(0xFF2C5364)),  // Deep sea
    ChatWallpaper.Gradient("g4", Color(0xFF42275A), Color(0xFF734B6D)),  // Plum
    ChatWallpaper.Gradient("g5", Color(0xFF1A2980), Color(0xFF26D0CE)),  // Ocean neon
    ChatWallpaper.Gradient("g6", Color(0xFF16222A), Color(0xFF3A6073)),  // Steel
    ChatWallpaper.Gradient("g7", Color(0xFF8E2DE2), Color(0xFF4A00E0)),  // Violet dream
    ChatWallpaper.Gradient("g8", Color(0xFFFF512F), Color(0xFFDD2476)),  // Sunset
    ChatWallpaper.Gradient("g9", Color(0xFFF12711), Color(0xFFF5AF19)),  // Ember
    ChatWallpaper.Gradient("g10", Color(0xFF11998E), Color(0xFF38EF7D)),  // Emerald glow
    ChatWallpaper.Gradient("g11", Color(0xFFFC466B), Color(0xFF3F5EFB)), // Neon bloom
    ChatWallpaper.Gradient("g12", Color(0xFF0B486B), Color(0xFFF56217)),  // Tropical dusk
)

/** The few ready ones the picker shows; the rest stay resolvable for old choices. */
val WALLPAPER_SHOWN: List<ChatWallpaper.Gradient>
    get() = listOf("g3", "g4", "g8").mapNotNull { id -> WALLPAPER_PRESETS.firstOrNull { it.id == id } }

/** A background made of [colors] (one = flat) at [degrees]. */
fun customWallpaper(colors: List<Color>, degrees: Float): ChatWallpaper.Custom {
    val hex = colors.joinToString(",") { "%06X".format(it.toArgb() and 0xFFFFFF) }
    return ChatWallpaper.Custom("custom:$hex@${degrees.toInt()}", colors, degrees)
}

/** The inverse of [customWallpaper]; null for anything malformed. */
fun parseCustomWallpaper(id: String): ChatWallpaper.Custom? = runCatching {
    if (!id.startsWith("custom:")) return null
    val (hex, deg) = id.removePrefix("custom:").split("@").let { it[0] to it.getOrElse(1) { "180" } }
    val colors = hex.split(",").map { Color(0xFF000000 or it.toLong(16)) }
    if (colors.isEmpty() || colors.size > 4) return null
    ChatWallpaper.Custom(id, colors, deg.toFloat())
}.getOrNull()

/** Paints a made background: flat, or the gradient at its CSS angle across the whole area. */
fun Modifier.customWallpaperBackground(colors: List<Color>, degrees: Float): Modifier =
    if (colors.size == 1) background(colors[0]) else drawBehind {
        val rad = Math.toRadians(degrees.toDouble())
        val dx = kotlin.math.sin(rad).toFloat()
        val dy = -kotlin.math.cos(rad).toFloat()
        val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2f
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        drawRect(
            Brush.linearGradient(
                colors,
                start = androidx.compose.ui.geometry.Offset(c.x - dx * half, c.y - dy * half),
                end = androidx.compose.ui.geometry.Offset(c.x + dx * half, c.y + dy * half),
            ),
        )
    }

/** Flat colour fallbacks for the "Solid" row in the picker. */
val WALLPAPER_SOLID_PRESETS: List<ChatWallpaper.Solid> = listOf(
    ChatWallpaper.Solid("s1", Color(0xFF1C1C1E)),
    ChatWallpaper.Solid("s2", Color(0xFF2C2C2E)),
    ChatWallpaper.Solid("s3", Color(0xFF0A2540)),
    ChatWallpaper.Solid("s4", Color(0xFF1B3A2F)),
)

/**
 * Resolves a persisted wallpaper id (and optional gallery uri) back into a
 * [ChatWallpaper]. Falls back to [ChatWallpaper.Default] for anything
 * unknown, so an old/corrupt preference can never blank the chat.
 */
fun resolveWallpaper(id: String?, galleryUri: String?): ChatWallpaper = when {
    id.isNullOrBlank() || id == ChatWallpaper.DEFAULT_ID -> ChatWallpaper.Default
    id.startsWith("gallery:") -> {
        val uri = galleryUri?.takeIf { it.isNotBlank() }
            ?: id.removePrefix("gallery:")
        ChatWallpaper.Gallery(id, uri)
    }
    WALLPAPER_PRESETS.firstOrNull { it.id == id } != null ->
        WALLPAPER_PRESETS.first { it.id == id }
    WALLPAPER_SOLID_PRESETS.firstOrNull { it.id == id } != null ->
        WALLPAPER_SOLID_PRESETS.first { it.id == id }
    id.startsWith("custom:") -> parseCustomWallpaper(id) ?: ChatWallpaper.Default
    else -> ChatWallpaper.Default
}

/** Stable id to persist for a given wallpaper (incl. the gallery uri suffix). */
fun ChatWallpaper.persistId(): String = when (this) {
    is ChatWallpaper.Default -> ChatWallpaper.DEFAULT_ID
    is ChatWallpaper.Gallery -> "gallery:" + this.id.removePrefix("gallery:")
    else -> this.id
}

// ══════════════════════════════════════════════════════════════════════
// CompositionLocals — message font scale + chat wallpaper
// ══════════════════════════════════════════════════════════════════════

/**
 * Multiplier applied only to message-bubble text (independent from the
 * global UI typography scale). Read it in [ChatScreen]'s text bubbles:
 * `fontSize = 15.spScaled(LocalMessageFontScale.current)`.
 */
val LocalMessageFontScale = staticCompositionLocalOf { 1.0f }

/**
 * The currently selected chat wallpaper. Read it where the chat surface
 * is rendered so the background updates instantly when the user picks a
 * new one in Settings.
 */
val LocalChatWallpaper = staticCompositionLocalOf<ChatWallpaper> { ChatWallpaper.Default }

/** Convenience accessor for the message font-scale multiplier. */
object Appearance {
    val messageFontScale: Float
        @Composable
        @ReadOnlyComposable
        get() = LocalMessageFontScale.current

    val chatWallpaper: ChatWallpaper
        @Composable
        @ReadOnlyComposable
        get() = LocalChatWallpaper.current
}

/** Provides both appearance locals down the tree. */
@Composable
fun ProvideAppearance(
    messageFontScale: Float,
    chatWallpaper: ChatWallpaper,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMessageFontScale provides messageFontScale,
        LocalChatWallpaper provides chatWallpaper,
        content = content,
    )
}

// ══════════════════════════════════════════════════════════════════════
// ChatWallpaperBackground — the composable that paints the chat surface
// ══════════════════════════════════════════════════════════════════════

/**
 * Paints the chosen [ChatWallpaper] as the background of the chat surface.
 *
 * - Default → transparent (lets the parent's `bgChat` show through)
 * - Solid   → flat colour fill
 * - Gradient→ a two-stop linear gradient at the wallpaper's angle
 * - Gallery → the user's photo, cropped to cover the surface; a translucent
 *             scrim is layered on top to keep bubbles legible
 *
 * [modifier] should size the layer — typically `Modifier.fillMaxSize()`
 * behind the message list, or `Modifier.matchParentSize()` inside a Box.
 */
@Composable
fun ChatWallpaperBackground(
    wallpaper: ChatWallpaper,
    modifier: Modifier = Modifier,
) {
    when (wallpaper) {
        is ChatWallpaper.Default -> {
            // No extra layer — the Scaffold's containerColor (bgChat) shows.
        }

        is ChatWallpaper.Solid -> {
            androidx.compose.foundation.layout.Box(
                modifier = modifier.background(wallpaper.color)
            )
        }

        is ChatWallpaper.Gradient -> {
            androidx.compose.foundation.layout.Box(
                modifier = modifier.background(
                    brush = gradientBrush(wallpaper.start, wallpaper.end, wallpaper.degrees)
                )
            )
        }

        is ChatWallpaper.Custom -> {
            androidx.compose.foundation.layout.Box(
                modifier = modifier.customWallpaperBackground(wallpaper.colors, wallpaper.degrees)
            )
        }

        is ChatWallpaper.Gallery -> {
            // Coil decodes off the Main thread + downsamples to the viewport +
            // caches — the old remember{ decodeStream } ran a FULL-SCREEN decode
            // synchronously on Main during chat open (jank under the slide).
            coil3.compose.AsyncImage(
                model = android.net.Uri.parse(wallpaper.uri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
            // Legibility scrim — adapts to theme luminosity so bubbles stay
            // readable over busy photos.
            // Dark themes get a light dim so bubbles stay readable; light themes
            // show the photo as is (a white veil washed it out). Service chips
            // carry their own dark pill over any wallpaper.
            val colors = AlphaTheme.colorScheme
            if (colors.bgPrimary.luminance() < 0.5f) {
                androidx.compose.foundation.layout.Box(
                    modifier = modifier.background(Color.Black.copy(alpha = 0.25f))
                )
            }
        }
    }
}

/** Builds a CSS-style angled linear-gradient brush from two colours. */
private fun gradientBrush(start: Color, end: Color, degrees: Float): Brush {
    // To properly draw a gradient across the full bounds without knowing the size
    // in pixels ahead of time, we use the default which maps to the top-left -> bottom-right
    // diagonal (equivalent to 135 degrees CSS).
    return Brush.linearGradient(
        colors = listOf(start, end)
    )
}
