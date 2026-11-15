package com.alphamessenger.ui.chat

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * classic chat bubble with an optional triangular tail.
 *
 * The tail is drawn as a separate triangle on the side where the avatar
 * sits (left for incoming, right for outgoing). When `showTail == false`
 * (mid-run bubble) the shape becomes a regular rounded rectangle of the
 * same body width so the column stays aligned visually.
 *
 * Callers MUST add padding equal to `tailW + breathing` on the tail side
 * so text content doesn't overlap with the tail area.
 */
class BubbleShape(
    private val outgoing: Boolean,
    private val showTail: Boolean,
    private val cornerR: Dp = 18.dp,
    private val tailW: Dp = 7.dp,
    private val tailH: Dp = 14.dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path()
        with(density) {
            val r = cornerR.toPx().coerceAtMost(size.height / 2f)
            val tw = tailW.toPx()
            val th = tailH.toPx().coerceAtMost(size.height / 2f)
            // Small rounding applied to the tail's outer tip so it isn't a
 // razor-sharp point (softer, more modern classic look).
            val tipR = 3.dp.toPx()
            if (outgoing) {
                val bodyRight = if (showTail) size.width - tw else size.width
                if (showTail) {
                    path.apply {
                        moveTo(r, 0f)
                        lineTo(bodyRight, 0f)                       // top edge to tail join
                        lineTo(size.width - tipR, 0f)              // approach the tip
                        quadraticBezierTo(size.width, 0f, size.width, tipR) // rounded tip
                        lineTo(bodyRight, th)                       // tail base on body edge
                        lineTo(bodyRight, size.height - r)
                        quadraticBezierTo(bodyRight, size.height, bodyRight - r, size.height)
                        lineTo(r, size.height)
                        quadraticBezierTo(0f, size.height, 0f, size.height - r)
                        lineTo(0f, r)
                        quadraticBezierTo(0f, 0f, r, 0f)
                        close()
                    }
                } else {
                    path.roundedRect(size.width, size.height, r)
                }
            } else {
                val bodyLeft = if (showTail) tw else 0f
                if (showTail) {
                    path.apply {
                        moveTo(bodyLeft, 0f)                        // top-left of body
                        lineTo(tipR, 0f)                            // approach the tip
                        quadraticBezierTo(0f, 0f, 0f, tipR)         // rounded tip
                        lineTo(bodyLeft, th)                        // tail base on body edge
                        lineTo(bodyLeft, size.height - r)
                        quadraticBezierTo(bodyLeft, size.height, bodyLeft + r, size.height)
                        lineTo(size.width - r, size.height)
                        quadraticBezierTo(size.width, size.height, size.width, size.height - r)
                        lineTo(size.width, r)
                        quadraticBezierTo(size.width, 0f, size.width - r, 0f)
                        lineTo(bodyLeft, 0f)
                        close()
                    }
                } else {
                    path.roundedRect(size.width, size.height, r)
                }
            }
        }
        return Outline.Generic(path)
    }
}

private fun Path.roundedRect(w: Float, h: Float, r: Float) {
    moveTo(r, 0f)
    lineTo(w - r, 0f)
    quadraticBezierTo(w, 0f, w, r)
    lineTo(w, h - r)
    quadraticBezierTo(w, h, w - r, h)
    lineTo(r, h)
    quadraticBezierTo(0f, h, 0f, h - r)
    lineTo(0f, r)
    quadraticBezierTo(0f, 0f, r, 0f)
    close()
}

/** Friendly factory mirroring the helper-function name used at call sites. */
fun bubbleShape(outgoing: Boolean, showTail: Boolean, radius: Dp = 18.dp): Shape =
    BubbleShape(outgoing = outgoing, showTail = showTail, cornerR = radius)
