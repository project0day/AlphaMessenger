package com.alphamessenger.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * A chat pattern made for a theme from a few words: the figures come from the
 * library by [subject] (or [icons] where they are real names), the colours from
 * the theme, the layout from [PatternLayout] with a fresh seed, so two themes
 * never share one picture. Whatever an assistant sends, the result is valid.
 */
object ThemePattern {
    fun forTheme(
        theme: CustomTheme, subject: String, icons: List<String> = emptyList(),
        density: PatternLayout.Density = PatternLayout.Density.NORMAL,
        size: PatternLayout.Size = PatternLayout.Size.MEDIUM,
        seed: Long = System.nanoTime(),
        /** More background colours (up to four in all) and the gradient's direction ([MESH] for the mesh). */
        more: List<Color> = emptyList(),
        degrees: Float = 180f,
    ): PatternSpec {
        val scheme = theme.scheme()
        val bg = (listOfNotNull(theme.background, theme.background2) + more).take(4).ifEmpty { listOf(scheme.bgChat) }
        return build(theme.name, subject, icons, density, size, seed, bg, scheme.accent, theme.isLight, degrees)
    }

    /** A pattern alone: its own colours where given, else Alpha's. */
    fun standalone(
        name: String, subject: String, icons: List<String>, bg: Color?, ink: Color?, dark: Boolean,
        density: PatternLayout.Density, size: PatternLayout.Size, seed: Long = System.nanoTime(),
    ): PatternSpec {
        val b = bg ?: if (dark) Color(0xFF121019) else Color(0xFFECE7FB)
        val i = ink ?: if (dark) Color(0xFFA78BFA) else Color(0xFF6D4FD6)
        return build(name, subject, icons, density, size, seed, listOf(b), i, b.luminance() > 0.45f, 180f)
    }

    private fun build(
        name: String, subject: String, icons: List<String>, density: PatternLayout.Density, size: PatternLayout.Size,
        seed: Long, bg: List<Color>, ink: Color, light: Boolean, degrees: Float,
    ): PatternSpec {
        val named = icons.map { it.trim().lowercase() }.filter { motifPath(it) != null }.distinct()
        val found = named.ifEmpty { PatternIcons.search(subject, 30) }.ifEmpty { PatternIcons.search(name, 30) }
        val pool = found.takeIf { it.size >= 3 } ?: PATTERN_RECIPES.first().set
        // A different handful each time, so the same subject does not give the same picture.
        val rnd = java.util.Random(seed)
        val set = pool.shuffled(rnd).take(18)
        val items = PatternLayout.scatter(set, 300f, density.fill, size.range, 30f, seed)
        // The figures read on the background in either mode: a stronger tint of the ink on light, a softer one on dark.
        val (lightBg, lightInk, darkBg, darkInk) = if (light) {
            Quad(bg, readable(ink, bg.first(), true), bg.map { lerp(it, Color.Black, 0.86f) }, lerp(ink, Color.White, 0.35f))
        } else {
            Quad(bg.map { lerp(it, Color.White, 0.9f) }, lerp(ink, Color.Black, 0.25f), bg, readable(ink, bg.first(), false))
        }
        return PatternSpec(name.take(32), 300f, lightBg, lightInk, darkBg, darkInk, degrees,
            if (light) 0.2f else 0.22f, 1f, 1.7f, 0f, items)
    }

    /** [ink] moved away from [bg] until a faint stroke of it still shows. */
    private fun readable(ink: Color, bg: Color, light: Boolean): Color {
        var c = ink
        val away = if (light) Color.Black else Color.White
        var n = 0
        while (contrast(c, bg) < 2.2f && n < 12) { c = lerp(c, away, 0.15f); n++ }
        return c
    }

    private data class Quad(val a: List<Color>, val b: Color, val c: List<Color>, val d: Color)

    /** An assistant's word for the gradient's direction. */
    fun degreesOf(s: String?): Float = when (s?.lowercase()) {
        "diagonal" -> 135f
        "horizontal" -> 90f
        "mesh" -> MESH
        else -> 180f
    }

    fun densityOf(s: String?) = when (s?.lowercase()) {
        "sparse" -> PatternLayout.Density.SPARSE
        "dense" -> PatternLayout.Density.DENSE
        else -> PatternLayout.Density.NORMAL
    }

    fun sizeOf(s: String?) = when (s?.lowercase()) {
        "small" -> PatternLayout.Size.SMALL
        "large" -> PatternLayout.Size.LARGE
        else -> PatternLayout.Size.MEDIUM
    }
}
