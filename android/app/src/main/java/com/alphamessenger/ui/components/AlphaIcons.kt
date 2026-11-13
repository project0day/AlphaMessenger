package com.alphamessenger.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Our own icons (not borrowed from other messengers). */
object AlphaIcons {

    /**
     * Saved Messages: a small vault — personal and encrypted, unlike a generic
     * bookmark. Rounded safe body, combination dial, hinges, feet.
     */
    val Vault: ImageVector by lazy {
        ImageVector.Builder("Vault", 24.dp, 24.dp, 24f, 24f).apply {
            val stroke = SolidColor(Color.White)
            // Body.
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 4f); lineTo(18f, 4f)
                arcTo(2f, 2f, 0f, false, true, 20f, 6f)
                lineTo(20f, 17f)
                arcTo(2f, 2f, 0f, false, true, 18f, 19f)
                lineTo(6f, 19f)
                arcTo(2f, 2f, 0f, false, true, 4f, 17f)
                lineTo(4f, 6f)
                arcTo(2f, 2f, 0f, false, true, 6f, 4f)
                close()
            }
            // Dial.
            path(stroke = stroke, strokeLineWidth = 1.8f) {
                moveTo(15.5f, 11.5f)
                arcTo(3.5f, 3.5f, 0f, true, true, 8.5f, 11.5f)
                arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 11.5f)
                close()
            }
            // Dial pointer + hinges + feet.
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 11.5f); lineTo(13.6f, 9.9f)
                moveTo(4f, 8f); lineTo(3f, 8f)
                moveTo(4f, 15f); lineTo(3f, 15f)
                moveTo(7f, 19f); lineTo(7f, 20.5f)
                moveTo(17f, 19f); lineTo(17f, 20.5f)
            }
        }.build()
    }

    /**
     * Emoji, GIF and stickers behind one button: a smiling face drawn as a
     * sticker whose lower-right corner is peeling off.
     */
    val Expressions: ImageVector by lazy {
        ImageVector.Builder("Expressions", 24.dp, 24.dp, 24f, 24f).apply {
            val stroke = SolidColor(Color.White)
            val round = StrokeCap.Round
            // The face, open where the corner peels.
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, true, false, 12f, 21f)
                // The peeled flap, folded inwards.
                lineTo(12.4f, 16.6f)
                quadTo(12.8f, 12.8f, 16.6f, 12.4f)
                lineTo(21f, 12f)
            }
            // Eyes and a smile.
            path(stroke = stroke, strokeLineWidth = 2.4f, strokeLineCap = round) {
                moveTo(9f, 9.4f); lineTo(9f, 9.6f)
                moveTo(15f, 9.4f); lineTo(15f, 9.6f)
            }
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = round) {
                moveTo(7.6f, 13.2f)
                quadTo(8.8f, 15.6f, 10.6f, 16.1f)
            }
        }.build()
    }
}
