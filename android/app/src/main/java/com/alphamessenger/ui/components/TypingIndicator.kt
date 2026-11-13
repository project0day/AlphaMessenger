package com.alphamessenger.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Animated typing indicator — three pulsing dots with scale + alpha animation.
 *
 * Uses the theme's accent colour for visual coherence.
 * Each dot is staggered by 150ms for a natural wave effect.
 *
 * @param modifier Layout modifier
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val accentColor = AlphaTheme.colorScheme.accent

    Row(modifier = modifier) {
        repeat(3) { index ->
            var alpha by remember { mutableFloatStateOf(0.3f) }
            var scale by remember { mutableFloatStateOf(0.8f) }

            // Staggered alpha animation
            LaunchedEffect(key1 = index) {
                val delay = index * 150L
                animate(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 500, delayMillis = delay.toInt()),
                        repeatMode = RepeatMode.Reverse
                    )
                ) { value, _ ->
                    alpha = value
                }
            }

            // Staggered scale animation
            LaunchedEffect(key1 = index) {
                val delay = index * 150L
                animate(
                    initialValue = 0.8f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 500, delayMillis = delay.toInt()),
                        repeatMode = RepeatMode.Reverse
                    )
                ) { value, _ ->
                    scale = value
                }
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scale)
                    .alpha(alpha)
                    .background(
                        color = accentColor,
                        shape = CircleShape
                    )
            )
            if (index < 2) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}
