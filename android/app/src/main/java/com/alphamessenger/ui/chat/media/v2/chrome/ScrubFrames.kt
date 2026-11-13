package com.alphamessenger.ui.chat.media.v2.chrome

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.alphamessenger.data.MediaVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Frames for the seek preview, read from the video on disk.
 *
 * Every frame the retriever returns costs a fresh decoder, too slow to follow a
 * finger. So a storyboard of small frames across the video is built as soon as
 * it opens, the preview shows the nearest one at once, and the exact key frame
 * replaces it when it arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrubFrames(private val file: suspend () -> File?) {
    // A retriever is not thread-safe: every call goes through one thread.
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var retriever: MediaMetadataRetriever? = null
    private var unavailable = false
    private var durationMs = 0L
    private var step = 0L
    private val board = ConcurrentHashMap<Int, ImageBitmap>()

    /** Bumped as storyboard frames land, so a showing preview can pick them up. */
    var built by mutableIntStateOf(0)
        private set

    /** Opens the video and fills the storyboard, coarse to fine. Call once the viewer shows it. */
    suspend fun warm() {
        // A video still downloading has no file yet: wait for it instead of giving up for good.
        var total: Long? = null
        while (total == null) {
            total = withContext(io) { open()?.let { durationMs } }
            if (total == null) { if (unavailable) return; kotlinx.coroutines.delay(2_000) }
        }
        if (total <= 0) return
        val n = (total / 1000).toInt().coerceIn(2, BOARD)
        step = total / n
        for (i in coarseToFine(n)) {
            if (board.containsKey(i)) continue
            // One frame per dispatch: a drag's own request slips in between.
            val f = withContext(io) { decode(i * step) } ?: continue
            board[i] = f
            built++
        }
    }

    /** The storyboard frame closest to [ms], if one is built yet. Instant. */
    fun nearest(ms: Long): ImageBitmap? {
        if (step <= 0) return null
        val at = (ms / step).toInt()
        for (d in 0..BOARD) {
            board[at - d]?.let { return it }
            board[at + d]?.let { return it }
        }
        return null
    }

    /** The key frame at or before [ms]. */
    suspend fun frameAt(ms: Long): ImageBitmap? = withContext(io) { open(); decode(ms) }

    private fun decode(ms: Long): ImageBitmap? {
        val r = retriever ?: return null
        val us = ms * 1000
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, EDGE, EDGE)
            else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)?.let(::shrink)
        }.getOrNull()
        return bmp?.asImageBitmap()
    }

    private suspend fun open(): MediaMetadataRetriever? {
        retriever?.let { return it }
        if (unavailable) return null
        val f = file()?.takeIf { it.exists() && it.length() > 0 }
        if (f == null) return null // may still be downloading: ask again next time
        return runCatching {
            MediaMetadataRetriever().also {
                MediaVault.setSource(it, f.path)
                durationMs = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.onFailure { unavailable = true }.getOrNull()?.also { retriever = it }
    }

    suspend fun close() = withContext(io) {
        runCatching { retriever?.release() }
        retriever = null
        board.clear()
    }

    private fun shrink(b: Bitmap): Bitmap {
        val k = EDGE.toFloat() / maxOf(b.width, b.height)
        if (k >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
            .also { if (it !== b) b.recycle() }
    }

    private companion object {
        const val EDGE = 240
        const val BOARD = 60

        /** 0, n/2, n/4, 3n/4, …: the whole video is roughly covered after a few frames. */
        fun coarseToFine(n: Int): List<Int> {
            val out = LinkedHashSet<Int>()
            var parts = 1
            while (out.size < n && parts <= n * 2) {
                for (k in 0 until parts) out.add(((k.toLong() * n) / parts).toInt().coerceIn(0, n - 1))
                parts *= 2
            }
            for (i in 0 until n) out.add(i)
            return out.toList()
        }
    }
}
