package com.alphamessenger.ui.components

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/**
 * Utility object for generating QR code [Bitmap]s from string content.
 *
 * Uses ZXing [MultiFormatWriter] to encode content into a QR code.
 * The resulting bitmap has a white background with black modules,
 * suitable for displaying on dark-themed UIs with a white rounded container.
 *
 * Usage:
 * ```
 * val bitmap = QRCodeGenerator.generateBitmap("npub1qy352…", size = 256)
 * Image(bitmap = bitmap.asImageBitmap(), …)
 * ```
 */
object QRCodeGenerator {

    /**
     * Generates a QR code [Bitmap] from the given [content] string.
     *
     * @param content  The string data to encode (e.g., npub, safety number, invite URL)
     * @param size     Output bitmap width and height in pixels (default 200)
     * @return         A [Bitmap] containing the QR code image
     * @throws IllegalArgumentException if the content is too long or encoding fails
     */
    fun generateBitmap(content: String, size: Int = 200): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,       // Small quiet zone
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val bitMatrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)

        for (x in 0 until size) {
            for (y in 0 until size) {
                val pixel = if (bitMatrix[x, y]) {
                    0xFF000000.toInt()   // Black module
                } else {
                    0xFFFFFFFF.toInt()   // White background
                }
                bitmap.setPixel(x, y, pixel)
            }
        }

        return bitmap
    }
}
