package com.alphamessenger.ui.chat

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.data.MediaMetrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * ExoPlayer DataSource that transparently downloads and decrypts per-chunk
 * E2EE streaming media from Blossom relays via the Rust FFI core.
 *
 * Architecture:
 * - The media was uploaded with per-chunk XChaCha20-Poly1305 encryption
 *   (each 256 KB plaintext chunk encrypted independently with its own nonce).
 * - The master key + per-chunk metadata travel inside the MLS-encrypted
 *   message, so Blossom relays never see the key or plaintext.
 * - This DataSource maps ExoPlayer byte-position requests to chunk indices,
 *   downloads the encrypted chunk from Blossom, decrypts it via FFI
 *   (`downloadStreamChunk`), and serves the plaintext to the player.
 *
 * Security: The decryption happens inside the Rust core (FFI). The master
 * key is never exposed to Kotlin — it's extracted from the media_ref JSON
 * inside the Rust-side `download_stream_chunk` function.
 *
 * Caching: Decrypted chunks are cached in memory during playback. For video
 * files up to ~50 MB this is fine. For very large files, an LRU eviction
 * policy could be added (keeping only a sliding window of chunks).
 */
class EncryptedDataSource(
    private val repository: AlphaCoreRepository,
    private val mediaRefJson: String,
    private val cacheDir: File,
) : DataSource {

    companion object {
        private const val STREAM_CHUNK_SIZE = 256 * 1024  // 256 KB — must match Rust
        private const val MAX_CACHE_BYTES   = 64L * 1024 * 1024  // 64 MB in-memory cap
    }

    /**
     * Thread-safe LRU cache bounded by total byte size, not item count.
     * Uses LinkedHashMap in access-order mode: least-recently-used entry is
     * evicted first when the cache exceeds [maxBytes].
     */
    private class LruChunkCache(private val maxBytes: Long) {
        private val map = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Int, ByteArray>) = false
        }
        private var currentBytes = 0L

        @Synchronized operator fun get(index: Int): ByteArray? = map[index]

        @Synchronized fun put(index: Int, data: ByteArray) {
            map.remove(index)?.let { currentBytes -= it.size }
            map[index] = data
            currentBytes += data.size
            evict()
        }

        @Synchronized fun containsKey(index: Int) = map.containsKey(index)

        @Synchronized fun size(): Int = map.size

        @Synchronized fun clear() { map.clear(); currentBytes = 0L }

        private fun evict() {
            val iter = map.entries.iterator()
            while (currentBytes > maxBytes && iter.hasNext()) {
                currentBytes -= iter.next().value.size
                iter.remove()
            }
        }
    }

    private var currentUri: Uri? = null
    private var currentPosition: Long = 0L
    private var totalLength: Long = -1L

    // Decrypted chunk cache — LRU, bounded by MAX_CACHE_BYTES to prevent OOM on large videos.
    private val chunkCache = LruChunkCache(MAX_CACHE_BYTES)
    // chunk index → shared download (chunk-level SINGLE-FLIGHT). read() joins an
    // in-flight prefetch via await() instead of starting a duplicate download.
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    // Scope for prefetching upcoming chunks
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // ADAPTIVE prefetch window: grows when the player stalls (cache miss),
    // capped — no protocol change, just a bigger read-ahead buffer.
    private var readAhead = 6
    // Streamed chunks are persisted to the shared media cache so the SAME
    // download populates disk (no separate competing materialize). Same naming
    // as materializeMedia's resume: cacheDir/media/<hash>_c{i}.part.
    private val hash: String = runCatching { JSONObject(mediaRefJson).optString("hash", "") }.getOrNull().orEmpty()
    private val mediaDir: File = File(cacheDir.parentFile ?: cacheDir, "media")
    // Current read buffer within a decrypted chunk
    private var currentChunkBytes: ByteArray? = null
    private var currentChunkOffset: Int = 0
    private var currentChunkIndex: Int = -1

    // Parsed metadata
    private var chunkCount: Int = 0
    private var chunkCumulativeSizes: LongArray = longArrayOf()

    init {
        parseMediaRef()
    }

    private fun parseMediaRef() {
        try {
            val ref = JSONObject(mediaRefJson)
            val isStreaming = ref.optBoolean("streaming", false)
            if (!isStreaming) {
                throw IOException("MediaRef is not streaming (legacy format)")
            }
            totalLength = ref.getLong("original_size")

            val chunks = ref.optJSONArray("chunks")
            if (chunks == null || chunks.length() == 0) {
                throw IOException("No streaming chunks in MediaRef")
            }

            chunkCount = chunks.length()
            chunkCumulativeSizes = LongArray(chunkCount)

            var cumulative = 0L
            for (i in 0 until chunkCount) {
                val chunk = chunks.getJSONObject(i)
                val origSize = chunk.getLong("original_size")
                cumulative += origSize
                chunkCumulativeSizes[i] = cumulative
            }
        } catch (e: Exception) {
            throw IOException("Failed to parse streaming MediaRef: ${e.message}", e)
        }
    }

    private fun positionToChunkIndex(position: Long): Int {
        if (position <= 0 || chunkCount == 0) return 0
        for (i in 0 until chunkCount) {
            if (position < chunkCumulativeSizes[i]) return i
        }
        return chunkCount - 1
    }

    private fun chunkStartPosition(chunkIndex: Int): Long {
        if (chunkIndex <= 0) return 0L
        return chunkCumulativeSizes[chunkIndex - 1]
    }

    /** Download + decrypt one chunk. Chunk-level single-flight: cache →
     *  in-flight join → download. */
    private fun fetchChunk(chunkIndex: Int): ByteArray {
        chunkCache.get(chunkIndex)?.let { return it }
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw IOException("Chunk index $chunkIndex out of range [0, $chunkCount)")
        }
        // SINGLE-FLIGHT: if this chunk is already downloading, JOIN it.
        val mine = CompletableDeferred<ByteArray>()
        val owner = inFlight.putIfAbsent(chunkIndex, mine)
        if (owner != null) {
            MediaMetrics.duplicateChunkRequests.incrementAndGet()
            return runBlocking { owner.await() }   // wait for the in-progress download, no dup
        }
        // We own it. The download running for this file writes the same bytes, in order,
        // checked chunk by chunk: what it already has is read from disk, not fetched twice.
        localChunk(chunkIndex)?.let { bytes ->
            chunkCache.put(chunkIndex, bytes)
            mine.complete(bytes)
            inFlight.remove(chunkIndex)
            return bytes
        }
        val chunkFile = File(cacheDir, "stream_chunk_${chunkIndex}_${System.nanoTime()}.dec")
        try {
            runBlocking(Dispatchers.IO) {
                repository.downloadStreamChunk(mediaRefJson, chunkIndex, chunkFile.absolutePath)
            }
            val plaintext = com.alphamessenger.data.MediaVault.readBytes(chunkFile)
            chunkCache.put(chunkIndex, plaintext)
            persistPart(chunkIndex, plaintext)     // same download feeds the disk cache
            mine.complete(plaintext)
            return plaintext
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            throw IOException("Failed to download/decrypt chunk $chunkIndex: ${e.message}", e)
        } finally {
            chunkFile.delete()
            inFlight.remove(chunkIndex)
        }
    }


    /**
     * Chunk [index] from disk, if it is there whole: the finished file, the download's
     * growing `.part` (written in order; every byte in it already passed its checks), or a
     * chunk this player kept earlier. Null: it has to come from the network.
     */
    private fun localChunk(index: Int): ByteArray? {
        if (hash.isBlank()) return null
        val start = chunkStartPosition(index)
        val len = (chunkCumulativeSizes[index] - start).toInt()
        fun read(f: File): ByteArray? = runCatching {
            if (!f.exists() || com.alphamessenger.data.MediaVault.length(f) < start + len) return null
            com.alphamessenger.data.MediaVault.open(f, start).use { s ->
                val out = ByteArray(len)
                var n = 0
                while (n < len) { val r = s.read(out, n, len - n); if (r < 0) return null; n += r }
                out
            }
        }.getOrNull()
        return read(File(mediaDir, hash)) ?: read(File(mediaDir, "$hash.part"))
            ?: File(mediaDir, "${hash}_c$index.part").takeIf { it.exists() }?.let { f ->
                runCatching { com.alphamessenger.data.MediaVault.readBytes(f) }.getOrNull()?.takeIf { it.size == len }
            }
    }

    /** Persist a decrypted chunk to the shared media cache (atomic) so the
     *  streamed download doubles as the disk cache — no separate materialize. */
    private fun persistPart(chunkIndex: Int, bytes: ByteArray) {
        if (hash.isBlank()) return
        val part = File(mediaDir, "${hash}_c$chunkIndex.part")
        if (part.exists() && com.alphamessenger.data.MediaVault.length(part) == bytes.size.toLong()) return
        runCatching {
            mediaDir.mkdirs()
            val tmp = File(mediaDir, "${hash}_c$chunkIndex.part.wtmp")
            com.alphamessenger.data.MediaVault.writeSealed(tmp, bytes)
            if (!tmp.renameTo(part)) tmp.delete()
        }
    }

    /** Prefetch the next [readAhead] chunks in the background so the player
     *  never blocks on a synchronous per-chunk download. */
    private fun prefetchAhead(fromIndex: Int) {
        for (i in (fromIndex + 1)..(fromIndex + readAhead)) {
            if (i >= chunkCount) break
            if (chunkCache.containsKey(i)) continue
            if (inFlight.containsKey(i)) continue        // single-flight: already downloading
            prefetchScope.launch {
                try { fetchChunk(i) } catch (_: Exception) { /* player re-requests it synchronously */ }
            }
        }
    }

    private fun ensureChunk(chunkIndex: Int): ByteArray {
        val cached = chunkCache[chunkIndex]
        val bytes = if (cached != null) cached else {
            // Player stalled on a miss → widen the prefetch window (capped).
            MediaMetrics.playerBlocked.incrementAndGet()
            readAhead = minOf(12, readAhead + 1)
            fetchChunk(chunkIndex)
        }
        prefetchAhead(chunkIndex)
        return bytes
    }

    // --- DataSource interface ---

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        val position = dataSpec.position

        val chunkIndex = positionToChunkIndex(position)
        currentChunkIndex = chunkIndex
        currentPosition = position

        val chunkBytes = ensureChunk(chunkIndex)
        currentChunkBytes = chunkBytes
        currentChunkOffset = (position - chunkStartPosition(chunkIndex)).toInt()

        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalLength - position
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (currentPosition >= totalLength) return C.RESULT_END_OF_INPUT

        var totalRead = 0
        var remaining = length

        while (remaining > 0 && currentPosition < totalLength) {
            val chunkBytes = currentChunkBytes ?: run {
                val idx = positionToChunkIndex(currentPosition)
                val bytes = ensureChunk(idx)
                currentChunkIndex = idx
                currentChunkBytes = bytes
                currentChunkOffset = (currentPosition - chunkStartPosition(idx)).toInt()
                bytes
            }

            if (currentChunkOffset >= chunkBytes.size) {
                currentChunkIndex++
                if (currentChunkIndex >= chunkCount) break
                currentChunkBytes = ensureChunk(currentChunkIndex)
                currentChunkOffset = 0
                continue
            }

            val available = chunkBytes.size - currentChunkOffset
            val toRead = minOf(remaining, available)
            System.arraycopy(chunkBytes, currentChunkOffset, buffer, offset + totalRead, toRead)

            currentChunkOffset += toRead
            currentPosition += toRead
            totalRead += toRead
            remaining -= toRead
        }

        return if (totalRead == 0) C.RESULT_END_OF_INPUT else totalRead
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentChunkBytes = null
        currentChunkOffset = 0
        currentChunkIndex = -1
    }

    /** Write all decrypted chunks to disk for reuse. Called when the video is
     *  fully cached in memory. */
    fun flushToDisk(outputPath: String): Boolean = runCatching {
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            for (i in 0 until chunkCount) {
                val bytes = chunkCache.get(i) ?: ensureChunk(i)
                out.write(bytes)
            }
        }
        true
    }.getOrDefault(false)

    /** Clear ONLY the in-memory cache (disk untouched) and stop prefetching. */
    fun clearMemoryCache() {
        prefetchScope.cancel()
        inFlight.clear()
        chunkCache.clear()
    }

    /** Number of chunks cached in memory (for UI progress). */
    fun getCachedChunkCount(): Int = chunkCache.size()

    /** Total number of chunks in the video. */
    fun getTotalChunkCount(): Int = chunkCount
}
