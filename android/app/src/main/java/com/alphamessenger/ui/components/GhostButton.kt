package com.alphamessenger.ui.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Show the ghost-mode button next to search on the main screen (device setting). */
object GhostButtonPref {
    private const val PREFS = "ui_prefs"
    private const val KEY = "ghost_button"
    var visible by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        visible = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(ctx: Context, value: Boolean) {
        visible = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}

/** Mirror of the core's ghost mode for every screen + toggle requests from the UI. */
object GhostModeState {
    val on = kotlinx.coroutines.flow.MutableStateFlow(false)
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    fun request(value: Boolean) { on.value = value; requests.tryEmit(value) }
}

val GhostEyesOn = Color(0xFF34D399)
val GhostEyesOff = Color(0xFFF87171)

/**
 * Our ghost: a rounded body with a wavy hem. The eyes glow green while ghost
 * mode is on and red while it is off; the change is animated.
 */
@Composable
fun GhostIcon(on: Boolean, body: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val eyes by animateColorAsState(if (on) GhostEyesOn else GhostEyesOff, tween(300), label = "ghostEyes")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.075f
        val left = w * 0.18f
        val right = w * 0.82f
        val top = h * 0.12f
        val bottom = h * 0.88f
        val r = (right - left) / 2f
        val path = Path().apply {
            moveTo(left, bottom)
            lineTo(left, top + r)
            // Round head.
            arcTo(androidx.compose.ui.geometry.Rect(left, top, right, top + 2 * r), 180f, 180f, false)
            lineTo(right, bottom)
            // Wavy hem: three scallops.
            val step = (right - left) / 3f
            for (i in 0 until 3) {
                val x0 = right - step * i
                quadraticBezierTo(x0 - step / 2f, bottom - h * 0.12f, x0 - step, bottom)
            }
            close()
        }
        drawPath(path, body, style = Stroke(width = stroke, join = StrokeJoin.Round))
        val eyeR = w * 0.075f
        val eyeY = top + r * 1.05f
        drawCircle(eyes, eyeR, Offset(w * 0.39f, eyeY))
        drawCircle(eyes, eyeR, Offset(w * 0.61f, eyeY))
    }
}
