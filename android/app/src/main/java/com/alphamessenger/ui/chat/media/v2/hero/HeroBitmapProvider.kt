package com.alphamessenger.ui.chat.media.v2.hero

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The only way the transition obtains a frame: CACHE LOOKUP.
 *
 * Deliberately NOT suspend — decoding mid-transition is forbidden: a large
 * snapshot would visibly freeze the exact moment of the flight. No ready
 * frame, no transition; the frame simply doesn't fly.
 */
fun interface HeroBitmapProvider {

    /** A ready frame from cache, or null. No decoding, no loading. */
    fun cached(mediaId: String): ImageBitmap?

    companion object {
        /** No frames — transition disabled. */
        val None = HeroBitmapProvider { null }
    }
}
