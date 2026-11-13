package com.alphamessenger.ui.chat.bubbles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.chat.MessageStatus

enum class TickState { SENDING, SENT, DELIVERED, READ }

fun MessageStatus.tickState(): TickState = when (this) {
    MessageStatus.SENDING -> TickState.SENDING
    MessageStatus.SENT -> TickState.SENT
    MessageStatus.DELIVERED -> TickState.DELIVERED
    MessageStatus.READ -> TickState.READ
}

private val ReadBlue = Color(0xFF4FC3F7)
private val ReadGreen = Color(0xFF4ADE80)

/** The read colour: blue, or green when that stands out more against [behind] (a bluish bubble). */
fun readTickColor(behind: Color): Color {
    fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }
    return if (contrast(ReadBlue, behind) >= contrast(ReadGreen, behind)) ReadBlue else ReadGreen
}

/** The chat list keeps the last message's status as text. */
fun tickStateOf(status: String): TickState? = when (status) {
    "sending" -> TickState.SENDING
    "sent" -> TickState.SENT
    "delivered" -> TickState.DELIVERED
    "read" -> TickState.READ
    else -> null
}

/**
 * The delivery mark, drawn in thin rounded strokes: a clock while sending, one
 * tick when sent, two overlapping ticks when delivered or read. Every state
 * takes the same box, so the time beside it never shifts when the state changes.
 */
@Composable
fun TickMark(state: TickState, color: Color, description: String, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(BubbleTokens.tickWidth, BubbleTokens.tickHeight)
            .semantics { contentDescription = description },
    ) {
        // Drawn on an 18 × 11 grid, scaled to the box.
        fun p(x: Float, y: Float) = Offset(x * size.width / 18f, y * size.height / 11f)
        fun Path.at(x: Float, y: Float) = p(x, y).let { moveTo(it.x, it.y) }
        fun Path.to(x: Float, y: Float) = p(x, y).let { lineTo(it.x, it.y) }
        val line = 1.35.dp.toPx()
        if (state == TickState.SENDING) {
            val c = p(9f, 5.5f)
            drawCircle(color, radius = 4.7f * size.height / 11f, center = c, style = Stroke(width = line))
            drawLine(color, c, p(9f, 3f), strokeWidth = line, cap = StrokeCap.Round)
            drawLine(color, c, p(10.8f, 6.6f), strokeWidth = line, cap = StrokeCap.Round)
            return@Canvas
        }
        val stroke = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { at(1.2f, 5.9f); to(4.6f, 9.3f); to(11f, 1.8f) }, color, style = stroke)
        if (state == TickState.DELIVERED || state == TickState.READ) {
            // Shifted right with a clear gap: its short arm stops short of the
            // first tick instead of touching it.
            drawPath(Path().apply { at(8.6f, 7.9f); to(9.9f, 9.3f); to(16.6f, 1.8f) }, color, style = stroke)
        }
    }
}
