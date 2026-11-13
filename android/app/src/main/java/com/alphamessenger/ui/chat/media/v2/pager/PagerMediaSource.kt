package com.alphamessenger.ui.chat.media.v2.pager

import androidx.compose.ui.graphics.ImageBitmap
import com.alphamessenger.ui.chat.media.MediaItem
import java.io.File

/**
 * Where renderers get their bytes. The implementation comes from whoever opens
 * the viewer, so the pager knows nothing about repositories, caches or
 * decryption — no network detail leaks into the UI layer.
 */
interface PagerMediaSource {
    /** Full-size frame for photos. */
    suspend fun fullBitmap(item: MediaItem): ImageBitmap?

    /** Video poster: shown on non-current pages. */
    suspend fun poster(item: MediaItem): ImageBitmap?

    /** Local video file; null = nothing to play. */
    suspend fun videoFile(item: MediaItem): File?

    /** The video already on disk (no network); null = not downloaded yet. */
    suspend fun localVideo(item: MediaItem): File? = null

    /**
     * Streaming playback straight from storage, decrypting chunk by chunk:
     * playback starts after the first chunks instead of after the whole file.
     * null = this video cannot stream (fall back to [videoFile]).
     */
    suspend fun videoStream(item: MediaItem): androidx.media3.datasource.DataSource.Factory? = null

    companion object {
        /** Empty source: handy for previews and tests; loads nothing. */
        val Empty = object : PagerMediaSource {
            override suspend fun fullBitmap(item: MediaItem): ImageBitmap? = null
            override suspend fun poster(item: MediaItem): ImageBitmap? = null
            override suspend fun videoFile(item: MediaItem): File? = null
        }
    }
}
