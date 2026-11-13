package com.alphamessenger.ui.theme

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A theme change shown as the new theme opening in a circle from where it was asked
 * for: the screen as it was is kept as a still on top, the theme changes under it,
 * and a growing hole in the still uncovers the new look.
 */
object ThemeReveal {
    private var shot by mutableStateOf<ImageBitmap?>(null)
    private var center by mutableStateOf(Offset.Zero)
    private val radius = Animatable(0f)
    private var busy = false

    /** [from]: window pixels. [switch] changes the theme and returns once it is applied. */
    fun start(activity: Activity, from: Offset, scope: CoroutineScope, switch: suspend () -> Unit) {
        val window = activity.window
        val view = window.decorView
        if (busy) return
        if (view.width == 0 || view.height == 0) { scope.launch { switch() }; return }
        busy = true
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bmp, { result ->
            scope.launch {
                try {
                    if (result != PixelCopy.SUCCESS) { switch(); return@launch }
                    radius.snapTo(0f)
                    center = from
                    shot = bmp.asImageBitmap()
                    withFrameNanos { }; withFrameNanos { }
                    switch()
                    // The new theme is drawn under the still before the hole opens.
                    withFrameNanos { }; withFrameNanos { }
                    val far = listOf(
                        Offset(0f, 0f), Offset(view.width.toFloat(), 0f),
                        Offset(0f, view.height.toFloat()), Offset(view.width.toFloat(), view.height.toFloat()),
                    ).maxOf { (it - from).getDistance() }
                    radius.animateTo(far, tween(REVEAL_MS, easing = FastOutSlowInEasing))
                } finally {
                    shot = null
                    busy = false
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    /** Placed last over the whole window. */
    @Composable
    fun Overlay() {
        val img = shot ?: return
        Canvas(
            Modifier.fillMaxSize()
                // Nothing under the still takes a touch until the new theme is out.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        ) {
            val hole = Path().apply { addOval(Rect(center, radius.value)) }
            clipPath(hole, ClipOp.Difference) { drawImage(img) }
        }
    }

    private const val REVEAL_MS = 500
}
