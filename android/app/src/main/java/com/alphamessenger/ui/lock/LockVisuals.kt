package com.alphamessenger.ui.lock

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.calls.ui.CallBackdrop
import com.alphamessenger.calls.ui.callBackdrop
import com.alphamessenger.ui.theme.AlphaColorScheme

/*
 * The look every lock screen shares — password, PIN, fingerprint: the theme's
 * glow on its deep ground (the same one calls use), the ghost with its halo,
 * the gradient wordmark, a padlock that springs open, glass keys.
 */

/** The theme's ground with its accent glowing from the top. */
fun Modifier.lockBackdrop(colors: AlphaColorScheme): Modifier =
    callBackdrop(colors, light = colors.bgPrimary.luminance() > 0.5f, style = CallBackdrop.AURORA)

/** The ghost with its soft halo, slowly floating; the wordmark under it. */
@Composable
fun AlphaBrand(logo: Dp, word: TextUnit, floating: Boolean = true) {
    val t = rememberInfiniteTransition(label = "brand")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "float")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(logo * 1.45f).drawBehind {
                val k = if (floating) p.value else 0.5f
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFF5EEAD4).copy(alpha = 0.40f + 0.18f * k), Color(0x2014B8A6), Color.Transparent),
                    ),
                    radius = size.minDimension / 2f * (0.94f + 0.06f * k),
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            // The launcher vector carries its own margins: draw it larger than the box.
            Image(
                painterResource(R.drawable.ic_launcher_foreground), null,
                modifier = Modifier.requiredSize(logo * 1.6f)
                    // The ghost stays put (moving it showed a hairline under it);
                    // only its halo breathes.
                    ,
            )
        }
        Text(
            "Alpha",
            style = TextStyle(
                brush = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFC026D3), Color(0xFFEC4899))),
                fontFamily = com.alphamessenger.ui.theme.BricolageGrotesque,
                fontWeight = FontWeight.ExtraBold,
                fontSize = word,
                letterSpacing = (-0.8).sp,
            ),
        )
    }
}

/**
 * The app's outlined padlock (the same drawing as the system "lock" icon:
 * outlined body, keyhole dot, round shackle). [open] 0 = shut, 1 = open: the
 * shackle lifts and its LEFT leg swings free, hinged on the right one.
 */
@Composable
fun Padlock(open: () -> Float, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val o = open().coerceIn(0f, 1.2f)
        val stroke = 2f * u
        // Body: outline + keyhole.
        drawRoundRect(
            tint, topLeft = Offset(5f * u, 9f * u), size = Size(14f * u, 12f * u),
            cornerRadius = CornerRadius(1.2f * u), style = Stroke(width = stroke),
        )
        drawCircle(tint, radius = 2f * u, center = Offset(12f * u, 15f * u))
        // Shackle: lifts out of the body, then the left leg swings up and out.
        val lift = -2.6f * u * o.coerceAtMost(1f)
        rotate(degrees = 24f * o, pivot = Offset(16f * u, 8f * u + lift)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(8f * u, 8f * u + lift)
                lineTo(8f * u, 6f * u + lift)
                cubicTo(8f * u, 3.8f * u + lift, 9.8f * u, 2f * u + lift, 12f * u, 2f * u + lift)
                cubicTo(14.2f * u, 2f * u + lift, 16f * u, 3.8f * u + lift, 16f * u, 6f * u + lift)
                lineTo(16f * u, 8f * u + lift)
            }
            drawPath(path, tint, style = Stroke(width = stroke))
        }
    }
}

/** The shackle springs open once [opened] turns true. */
@Composable
fun rememberPadlockOpening(opened: Boolean): () -> Float {
    val a = remember { Animatable(if (opened) 1f else 0f) }
    LaunchedEffect(opened) {
        a.animateTo(if (opened) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
    }
    return { a.value }
}

/** "Locked" chip with a padlock that opens as the screen unlocks. */
@Composable
fun LockedLine(text: String, opening: Boolean) {
    val open = rememberPadlockOpening(opening)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Padlock(open, Color.White.copy(alpha = 0.78f), 15.dp)
        Text(text, color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
    }
}

/** Six PIN dots; filled ones glow in the accent. */
@Composable
fun PinDots(filled: Int, accent: Color, shake: () -> Float) {
    Row(
        Modifier.graphicsLayer { translationX = shake() * density },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(6) { i ->
            val on = i < filled
            val s by animateFloatAsState(if (on) 1f else 0.85f, spring(dampingRatio = 0.5f), label = "dot")
            Box(
                Modifier.size(14.dp).graphicsLayer { scaleX = s; scaleY = s }
                    .then(
                        if (on) Modifier.drawBehind { drawCircle(accent.copy(alpha = 0.35f), radius = size.minDimension) }
                            .clip(CircleShape).background(accent)
                        else Modifier.border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    ),
            )
        }
    }
}

/** A round glass key of the keypad. */
@Composable
fun GlassKey(onClick: () -> Unit, plain: Boolean = false, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.9f else 1f, label = "key")
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier.size(76.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape)
            .then(
                if (plain) Modifier
                else Modifier.background(Color.White.copy(alpha = if (pressed) 0.18f else 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
            )
            .clickable(interactionSource = src, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 1–9, then fingerprint (or nothing) · 0 · erase. */
@Composable
fun GlassKeypad(onDigit: (String) -> Unit, onErase: () -> Unit, onFingerprint: (() -> Unit)?, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                row.forEach { d -> GlassKey({ onDigit(d) }) { Text(d, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            if (onFingerprint != null) {
                GlassKey(onFingerprint, plain = true) {
                    Icon(AlphaIcons.Filled.Fingerprint, stringResource(R.string.lock_biometric_icon), tint = accent, modifier = Modifier.size(36.dp))
                }
            } else Spacer(Modifier.size(76.dp))
            GlassKey({ onDigit("0") }) { Text("0", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium) }
            GlassKey(onErase, plain = true) {
                Icon(AlphaIcons.AutoMirrored.Outlined.Backspace, stringResource(R.string.pin_pad_backspace), tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Fingerprint in a ring, waves leaving it, a light scanning across. */
@Composable
fun FingerprintPulse(accent: Color, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "fp")
    val w = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200)), label = "wave")
    val scan = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "scan")
    Box(
        Modifier.size(150.dp).drawBehind {
            val r = size.minDimension / 2f
            for (i in 0 until 2) {
                val f = (w.value + i * 0.5f) % 1f
                drawCircle(accent.copy(alpha = 0.30f * (1f - f)), radius = r * (0.55f + 0.45f * f))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f))
                .border(1.5.dp, accent.copy(alpha = 0.5f), CircleShape)
                .clickable(onClick = onClick)
                .drawBehind {
                    val y = size.height * (0.24f + 0.5f * scan.value)
                    drawRoundRect(
                        accent, topLeft = Offset(size.width * 0.24f, y), size = Size(size.width * 0.52f, 2.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) { Icon(AlphaIcons.Filled.Fingerprint, stringResource(R.string.lock_biometric_icon), tint = accent, modifier = Modifier.size(62.dp)) }
    }
}

/** A short horizontal shake (wrong PIN / password). */
suspend fun Animatable<Float, androidx.compose.animation.core.AnimationVector1D>.shake() {
    for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) animateTo(x, tween(45))
}

/** Space helper for the screens. */
@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
