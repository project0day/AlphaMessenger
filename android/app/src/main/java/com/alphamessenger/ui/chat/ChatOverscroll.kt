package com.alphamessenger.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.sign

/**
 * The chat's pull-past-the-end stretch, applied to the list AND what is drawn
 * over it (the group avatars), the way a list that draws its avatars itself
 * stretches them with its rows. The list's own effect stretched only the rows.
 * Only a layer property changes while pulling: nothing is laid out or recomposed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatOverscroll(content: @Composable () -> Unit) {
    // Written in the scroll callbacks themselves: each event sees the one before it.
    val pull = remember { mutableFloatStateOf(0f) }
    val connection = remember {
        object : NestedScrollConnection {
            // Scrolling back first undoes the stretch, then moves the list.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val p = pull.floatValue
                if (p == 0f || source != NestedScrollSource.UserInput || sign(available.y) == sign(p)) return Offset.Zero
                val next = if (abs(available.y) >= abs(p)) 0f else p + available.y
                pull.floatValue = next
                return Offset(0f, next - p)
            }

            // What the list could not take at its end becomes stretch, with growing resistance.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                val p = pull.floatValue
                val resist = 1f / (1f + abs(p) / 120f)
                pull.floatValue = (p + available.y * 0.5f * resist).coerceIn(-MAX_PULL, MAX_PULL)
                return Offset(0f, available.y)
            }

            // Let go: back to rest; the fling is spent on that.
            override suspend fun onPreFling(available: Velocity): Velocity {
                val p = pull.floatValue
                if (p == 0f) return Velocity.Zero
                animate(p, 0f, animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { v, _ -> pull.floatValue = v }
                return available
            }

            // A fling that hits the end gives a short stretch and lets go.
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (abs(available.y) > 400f && pull.floatValue == 0f) {
                    val bump = (available.y / 60f).coerceIn(-40f, 40f)
                    animate(0f, bump, animationSpec = spring(stiffness = Spring.StiffnessHigh)) { v, _ -> pull.floatValue = v }
                    animate(bump, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> pull.floatValue = v }
                }
                return Velocity.Zero
            }
        }
    }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Box(
            Modifier.fillMaxSize().nestedScroll(connection).graphicsLayer {
                val p = pull.floatValue
                if (p != 0f && size.height > 0f) {
                    // Pulled down: the top edge stays and the rest stretches toward the finger; up: the bottom.
                    transformOrigin = TransformOrigin(0.5f, if (p > 0f) 0f else 1f)
                    scaleY = 1f + abs(p) / size.height
                    translationY = p * 0.35f
                } else {
                    scaleY = 1f
                    translationY = 0f
                }
            },
        ) { content() }
    }
}

private const val MAX_PULL = 360f
