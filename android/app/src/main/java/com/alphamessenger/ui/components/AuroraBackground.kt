package com.alphamessenger.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.Canvas
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Animated aurora-style background — 3 blurred colour orbs that slowly drift
 * on sinusoidal paths.
 *
 * Reads all colours from [AlphaTheme.colorScheme] so it works for every
 * theme variant:
 *   - Hacktivist: green/amber aurora
 *   - Veil:       teal/indigo/coral aurora
 *   - Paranoid:   red/dark-orange aurora
 *   - Ghost:      purple/violet aurora
 *   - Glass:      teal/indigo/coral aurora (specific glass-optimized colours)
 *
 * CRITICAL: Without this, glass surfaces have nothing to blur and the
 * liquid-glass effect collapses into "flat coloured rectangles".
 *
 * Design spec:
 *   - Dark base (bgPrimary) + 3 blurred "aurora" orbs
 *   - Each orb drifts slowly on a unique sinusoidal path
 *   - Radial gradient per orb: vivid centre → transparent edge
 *   - Animation cycles: 20–35 s per axis, reverse on completion
 *
 * @param modifier   Layout modifier (default: fillMaxSize)
 * @param orbAlpha   Base alpha multiplier for all orbs (0–1). Lower values
 *                   produce subtler effects; default 0.3f.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    orbAlpha: Float = 0.3f
) {
    val colors = AlphaTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "aurora")

    // ── Orb 1 — Primary aurora colour ──────────────────────────────
    val orb1X by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 25_000, delayMillis = 0),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb1X"
    )
    val orb1Y by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30_000, delayMillis = 2_000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb1Y"
    )

    // ── Orb 2 — Secondary aurora colour ────────────────────────────
    val orb2X by infiniteTransition.animateFloat(
        initialValue = 0.70f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22_000, delayMillis = 1_000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb2X"
    )
    val orb2Y by infiniteTransition.animateFloat(
        initialValue = 0.60f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28_000, delayMillis = 3_000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb2Y"
    )

    // ── Orb 3 — Tertiary aurora colour ─────────────────────────────
    val orb3X by infiniteTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 0.80f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 35_000, delayMillis = 4_000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb3X"
    )
    val orb3Y by infiniteTransition.animateFloat(
        initialValue = 0.80f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, delayMillis = 5_000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb3Y"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        // ── Dark base fill from theme ──────────────────────────────
        drawRect(color = colors.bgPrimary)

        val w = size.width
        val h = size.height

        // ── Orb 1 — Largest, full alpha ────────────────────────────
        drawAuroraOrb(
            centerX = w * orb1X,
            centerY = h * orb1Y,
            radius = w * 0.45f,
            color = colors.auroraOrb1,
            baseAlpha = orbAlpha
        )

        // ── Orb 2 — Medium, 80% alpha ─────────────────────────────
        drawAuroraOrb(
            centerX = w * orb2X,
            centerY = h * orb2Y,
            radius = w * 0.40f,
            color = colors.auroraOrb2,
            baseAlpha = orbAlpha * 0.8f
        )

        // ── Orb 3 — Smallest, 60% alpha ───────────────────────────
        drawAuroraOrb(
            centerX = w * orb3X,
            centerY = h * orb3Y,
            radius = w * 0.35f,
            color = colors.auroraOrb3,
            baseAlpha = orbAlpha * 0.6f
        )
    }
}

/**
 * Draws a single aurora orb as a radial gradient circle.
 * The gradient goes from the [color] at full [baseAlpha] in the centre,
 * through 40% alpha at the midpoint, to transparent at the edge.
 */
private fun DrawScope.drawAuroraOrb(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    baseAlpha: Float
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = baseAlpha),
                color.copy(alpha = baseAlpha * 0.4f),
                Color.Transparent
            ),
            center = Offset(centerX, centerY),
            radius = radius
        ),
        radius = radius,
        center = Offset(centerX, centerY)
    )
}
