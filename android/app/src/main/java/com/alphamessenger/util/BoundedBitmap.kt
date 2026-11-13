package com.alphamessenger.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Pictures that come from other people (posters, thumbnails inside refs) are
 * decoded with a ceiling: the size is read first and the picture is sampled
 * down, so a declared 20000×20000 poster cannot exhaust memory on every open.
 */
object BoundedBitmap {
    fun decode(bytes: ByteArray, maxSide: Int = 1024): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
