package com.alphamessenger.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * A theme the user made: a few picks, from which every colour of the scheme is
 * derived (like the built-in themes, it is a copy of the Grey or Daylight base
 * with its own colours). Text is always kept readable: the text on own bubbles
 * takes white or near-black, whichever reads better, and an incoming bubble too
 * close to the text colour is pushed away from it.
 *
 * Shared as `alpha://theme/<code>`: colours and a name only.
 */
data class CustomTheme(
    val id: String,
    val name: String,
    val light: Boolean,
    /** 0 = neutral grey background, 100 = background tinted with the accent. */
    val tint: Int,
    val accent: Color,
    val gradient: Boolean,
    val out1: Color,
    val out2: Color,
    /** Incoming bubbles; null = derived from the background. */
    val incoming: Color? = null,
    /** "Read" ticks; null = the base theme's. */
    val tick: Color? = null,
    /** Screen background; null = from the base and the tint. */
    val background: Color? = null,
    /** Second background colour (a top-to-bottom gradient); null = one colour. */
    val background2: Color? = null,
    /** Bars, cards, menus; null = from the background. */
    val panels: Color? = null,
) {
    /** Light or dark: a chosen background decides, so text always reads on it. */
    val isLight: Boolean get() = background?.let { it.luminance() > 0.45f } ?: light

    /** The variant this theme is built on (light/dark decisions follow it). */
    val base: AlphaThemeVariant get() = if (isLight) AlphaThemeVariant.DAYLIGHT else AlphaThemeVariant.GREY

    fun scheme(): AlphaColorScheme {
        val light = isLight
        val b = if (light) daylightColorScheme() else greyColorScheme()
        val t = tint.coerceIn(0, 100) / 100f
        val neutral = if (light) Color(0xFFF4F5F7) else Color(0xFF1C1D21)
        val bg = background ?: lerp(neutral, lerp(neutral, accent, if (light) 0.14f else 0.22f), t)
        val bg2 = panels ?: if (light) lerp(bg, Color.White, 0.65f) else lerp(bg, Color.Black, 0.2f)
        val bg3 = if (light) lerp(bg, Color.Black, 0.06f) else lerp(bg, Color.White, 0.07f)
        val outEnd = if (gradient) out2 else out1
        val outText = readableOn(lerp(out1, outEnd, 0.5f))
        val inc = readableBubble(incoming ?: bg3, b.textPrimary, light)
        return b.copy(
            bgPrimary = bg,
            bgSecondary = bg2,
            bgTertiary = bg3,
            bgChat = bg,
            accent = accent,
            accentDim = accent.copy(alpha = 0.15f),
            accentGlow = accent.copy(alpha = 0.4f),
            msgOutStart = out1,
            msgOutEnd = outEnd,
            msgOutText = outText,
            msgIn = inc,
            bubbleOut = out1,
            bubbleOutEnd = if (gradient) out2 else Color.Unspecified,
            bgEnd = background2 ?: Color.Unspecified,
            success = tick ?: b.success,
            badgeBg = accent,
            badgeText = readableOn(accent),
            avatarGradient1Start = accent,
            avatarGradient1End = outEnd,
            auroraOrb1 = accent,
            auroraOrb2 = outEnd,
        )
    }

    /** How readable the weakest text is (WCAG ratio): own bubble, incoming bubble. */
    fun readability(): Pair<Float, Float> {
        val s = scheme()
        return contrast(s.msgOutText, lerp(s.msgOutStart, s.msgOutEnd, 0.5f)) to contrast(s.textPrimary, s.msgIn)
    }

    /** The share code: 2;l|d;tint;accent;g|s;out1;out2;incoming|-;tick|-;bg|-;bg2|-;panels|-;name. */
    fun encode(): String {
        val raw = listOf(
            "2", if (light) "l" else "d", tint.toString(), hex(accent), if (gradient) "g" else "s",
            hex(out1), hex(out2), incoming?.let { hex(it) } ?: "-", tick?.let { hex(it) } ?: "-",
            background?.let { hex(it) } ?: "-", background2?.let { hex(it) } ?: "-", panels?.let { hex(it) } ?: "-",
            name.take(32),
        ).joinToString(";")
        return android.util.Base64.encodeToString(raw.toByteArray(), B64)
    }

    fun link(): String = THEME_LINK_PREFIX + encode()

    companion object {
        private const val B64 = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP

        fun decode(code: String, id: String = newId()): CustomTheme? = runCatching {
            val text = String(android.util.Base64.decode(code, B64))
            val v2 = text.startsWith("2;")
            val p = text.split(";", limit = if (v2) 13 else 10)
            if (p.size < (if (v2) 13 else 10) || (p[0] != "1" && !v2)) return null
            fun opt(i: Int) = p[i].takeIf { it != "-" }?.let { color(it) }
            CustomTheme(
                id = id, name = p[if (v2) 12 else 9].take(32), light = p[1] == "l", tint = p[2].toInt().coerceIn(0, 100),
                accent = color(p[3]), gradient = p[4] == "g", out1 = color(p[5]), out2 = color(p[6]),
                incoming = opt(7), tick = opt(8),
                background = if (v2) opt(9) else null, background2 = if (v2) opt(10) else null, panels = if (v2) opt(11) else null,
            )
        }.getOrNull()

        fun fromLink(link: String): CustomTheme? =
            link.takeIf { it.startsWith(THEME_LINK_PREFIX) }?.let { decode(it.removePrefix(THEME_LINK_PREFIX)) }

        fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

        private fun hex(c: Color) = "%06x".format(c.toArgb() and 0xFFFFFF)
        private fun color(h: String) = Color(0xFF000000L or h.toLong(16))

        /** Ready starting points for "Make your own". */
        val PRESETS: List<CustomTheme> = listOf(
            CustomTheme("p-ocean", "Ocean", false, 45, Color(0xFF14B8C9), true, Color(0xFF0F8FB0), Color(0xFF2F7FE0)),
            CustomTheme("p-sunset", "Sunset", false, 30, Color(0xFFEF8A3A), true, Color(0xFFD9467A), Color(0xFFEA7A2F)),
            CustomTheme("p-mint", "Mint", true, 35, Color(0xFF2FB37A), false, Color(0xFF1F9D6A), Color(0xFF1F9D6A)),
            CustomTheme("p-graphite", "Graphite", false, 0, Color(0xFF9AA1AB), false, Color(0xFF3A3F47), Color(0xFF3A3F47)),
            CustomTheme("p-lavender", "Lavender", true, 55, Color(0xFF7C5CF0), true, Color(0xFF7B5CF0), Color(0xFFD9467A)),
            CustomTheme("p-sand", "Sand", true, 20, Color(0xFFE0A21F), false, Color(0xFFEA7A2F), Color(0xFFEA7A2F)),
        )
    }
}

const val THEME_LINK_PREFIX = "alpha://theme/"

/** WCAG contrast ratio of two colours (1…21). */
fun contrast(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}

/** White or near-black, whichever reads better on [bg]. */
fun readableOn(bg: Color): Color {
    val dark = Color(0xFF15171B)
    return if (contrast(Color.White, bg) >= contrast(dark, bg)) Color.White else dark
}

/** [bubble] moved away from [text] until the text reads on it (≥ 4.5:1). */
private fun readableBubble(bubble: Color, text: Color, light: Boolean): Color {
    var c = bubble
    val away = if (light) Color.White else Color.Black
    var i = 0
    while (contrast(text, c) < 4.5f && i < 20) { c = lerp(c, away, 0.12f); i++ }
    return c
}
