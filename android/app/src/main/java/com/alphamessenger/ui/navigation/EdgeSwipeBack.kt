package com.alphamessenger.ui.navigation

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.launch

/**
 * "Back" by pulling the screen to the right, from anywhere on it: the screen
 * follows the finger, the one before it shows underneath (a little to the
 * left, dimmed) and comes into place; let go past a third of the width, or
 * with a flick, and the screen slides away.
 *
 * The screen underneath is a picture of it, taken from the window the moment
 * we went forward from it (navigation keeps only the top screen alive). At
 * the end of the swipe the real screen replaces the picture in the same spot.
 *
 * Back goes through the activity's dispatcher, so a screen's own back handling
 * (closing a selection, a panel) still comes first; if it keeps the screen,
 * the screen slides back. A drag a screen's own content takes (a row that
 * scrolls sideways, a slider) is left to it.
 */
class EdgeSwipeState {
    /** True while a swipe's own pop runs: the nav transition must not slide again. */
    var popping by mutableStateOf(false)
}

/** How far the screen underneath starts to the left, as a share of the width. */
private const val PARALLAX = 0.3f

@Composable
fun EdgeSwipeBack(
    navController: NavHostController,
    state: EdgeSwipeState,
    enabled: () -> Boolean,
    content: @Composable () -> Unit,
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val view = LocalView.current
    val density = LocalDensity.current
    val flick = with(density) { 500.dp.toPx() }
    val shadow = with(density) { 16.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val bg = AlphaTheme.colorScheme.bgPrimary
    // Pictures of the screens below the top one, by back-stack entry id.
    val shots = remember { HashMap<String, ImageBitmap>() }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }

    DisposableEffect(navController) {
        var top: String? = navController.currentBackStackEntry?.id
        val listener = NavController.OnDestinationChangedListener { c, _, _ ->
            val stack = c.currentBackStack.value.map { it.id }.toSet()
            val was = top
            top = c.currentBackStackEntry?.id
            // Went forward: the screen we left is still on screen — picture it.
            if (was != null && was != top && was in stack) snap(view) { shots[was] = it }
            shots.keys.retainAll(stack)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled() || offset.isRunning) return@awaitEachGesture
                    val width = size.width.toFloat()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dx = 0f
                    var dy = 0f
                    var dragging = false
                    while (true) {
                        // Main pass, after the content: what it takes stays its own.
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (ev.changes.size > 1 && !dragging) return@awaitEachGesture
                        if (!dragging && ch.isConsumed) return@awaitEachGesture
                        val d = ch.positionChange()
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        if (!dragging) {
                            dx += d.x; dy += d.y
                            if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                                // Rightward and clearly more sideways than up/down — else not ours.
                                if (dx <= 0f || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f) return@awaitEachGesture
                                dragging = true
                                under = navController.previousBackStackEntry?.id?.let { shots[it] }
                            } else continue
                        }
                        ch.consume()
                        val to = (offset.value + d.x).coerceIn(0f, width)
                        scope.launch { offset.snapTo(to) }
                    }
                    if (!dragging) return@awaitEachGesture
                    val v = tracker.calculateVelocity().x
                    val go = (offset.value > width / 3f && v > -flick) || v > flick
                    scope.launch {
                        if (!go) { offset.animateTo(0f, tween(220)); under = null; return@launch }
                        val left = ((width - offset.value) / width).coerceIn(0f, 1f)
                        offset.animateTo(width, tween((90 + 200 * left).toInt()))
                        val before = navController.currentBackStackEntry
                        state.popping = true
                        dispatcher?.onBackPressed()
                        if (navController.currentBackStackEntry !== before) {
                            // Let the real screen draw where its picture lies, then drop the picture.
                            repeat(2) { withFrameNanos { } }
                            offset.snapTo(0f)
                            under = null
                            kotlinx.coroutines.delay(300)
                            state.popping = false
                        } else {
                            // The screen kept itself (its own back handling ran).
                            state.popping = false
                            offset.animateTo(0f, tween(220))
                            under = null
                        }
                    }
                }
            },
    ) {
        // The screen below: in from the left as this one goes, its shade lifting.
        val shot = under
        if (shot != null) {
            Image(
                shot, null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val w = size.width
                    val p = if (w > 0f) (offset.value / w).coerceIn(0f, 1f) else 0f
                    translationX = -w * PARALLAX * (1f - p)
                },
            )
        }
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val p = if (size.width > 0f) (offset.value / size.width).coerceIn(0f, 1f) else 0f
                if (offset.value > 0f) drawRect(Color.Black.copy(alpha = 0.4f * (1f - p)))
            },
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offset.value }
                .drawWithContent {
                    drawContent()
                    // A soft shadow along the edge being pulled.
                    if (offset.value > 0f) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)), -shadow, 0f),
                            topLeft = Offset(-shadow, 0f), size = Size(shadow, size.height),
                        )
                    }
                }
                .background(bg),
        ) { content() }
    }
}

/** A half-size picture of the window as it is on screen now. */
private fun snap(view: android.view.View, done: (ImageBitmap) -> Unit) {
    val window = (view.context as? Activity)?.window ?: generateSequence(view.context) {
        (it as? android.content.ContextWrapper)?.baseContext
    }.filterIsInstance<Activity>().firstOrNull()?.window ?: return
    val w = view.rootView.width / 2
    val h = view.rootView.height / 2
    if (w <= 0 || h <= 0) return
    val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
    runCatching {
        PixelCopy.request(window, bmp, { r ->
            if (r == PixelCopy.SUCCESS) done(bmp.asImageBitmap()) else bmp.recycle()
        }, Handler(Looper.getMainLooper()))
    }
}
