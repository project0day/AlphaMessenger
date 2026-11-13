package com.alphamessenger.ui.chat

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import android.util.LruCache
import com.alphamessenger.data.MediaVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * A voice note's waveform: [BARS] loudness values, one byte each (0..255).
 *
 * The sender measures it and ships it inside the media ref ("waveform", base64),
 * so a receiver draws the bars before a byte of audio is fetched. Notes without
 * it (older senders, our own drafts) are measured here once and kept sealed on
 * disk, so scrolling back or restarting never decodes the audio again.
 */
object VoiceWaveform {
    const val BARS = 100
    private const val NOISE_FLOOR_DB = -60.0
    private const val FULL_SCALE_SQ = 32768.0 * 32768.0
    private const val POLL_US = 5_000L
    private const val MAX_IDLE = 50

    private val memory = LruCache<String, FloatArray>(200)

    fun encode(bars: ByteArray): String = Base64.encodeToString(bars, Base64.NO_WRAP)

    fun decode(b64: String?): FloatArray? {
        if (b64.isNullOrBlank()) return null
        val raw = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (raw.isEmpty() || raw.size > 4 * BARS) return null
        return FloatArray(raw.size) { (raw[it].toInt() and 0xFF) / 255f }
    }

    fun cached(path: String?): FloatArray? = path?.let { memory.get(it) }

    /** The bars to ship with a note being sent; also warms the sender's own bubble. */
    suspend fun forSending(cacheDir: File, path: String): String? =
        load(cacheDir, path)?.let { bars -> encode(ByteArray(bars.size) { (bars[it] * 255 + 0.5f).toInt().toByte() }) }

    /** Memory → sealed disk copy → decode the file (once). Off the main thread. */
    suspend fun load(cacheDir: File, path: String): FloatArray? {
        memory.get(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            val src = File(path)
            if (!src.exists()) return@withContext null
            val stored = File(File(cacheDir, "waveforms"), "${src.name}_${src.length()}")
            val bytes = runCatching { MediaVault.readBytes(stored) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: generate(path)?.also { b ->
                    runCatching { stored.parentFile?.mkdirs(); MediaVault.writeSealed(stored, b) }
                }
                ?: return@withContext null
            FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }.also { memory.put(path, it) }
        }
    }

    /**
     * Decodes the audio and turns each bar's mean square into decibels above a
     * -60 dB floor. The decoder is polled, never waited on while it has input to
     * take — blocking on every buffer is what made long notes take seconds.
     */
    fun generate(path: String): ByteArray? = runCatching {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            MediaVault.setSource(extractor, path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@runCatching null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            // Per decoded buffer: its time and sum of squares; bucketed at the end so
            // a missing container duration falls back to buffer order.
            var times = LongArray(256); var sums = DoubleArray(256); var counts = IntArray(256); var n = 0
            var scratch = ShortArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false; var idle = 0
            while (!outputDone && idle < MAX_IDLE) {
                var fed = false
                while (!inputDone) {
                    val i = dec.dequeueInputBuffer(0)
                    if (i < 0) break
                    fed = true
                    val size = extractor.readSampleData(dec.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                var got = false
                while (true) {
                    val o = dec.dequeueOutputBuffer(info, if (fed || got) 0 else POLL_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (o < 0) break
                    got = true
                    val samples = info.size / 2
                    val out = dec.getOutputBuffer(o)
                    if (out != null && samples > 0) {
                        if (scratch.size < samples) scratch = ShortArray(samples)
                        out.order(ByteOrder.nativeOrder()).position(info.offset)
                        out.limit(info.offset + info.size)
                        out.asShortBuffer().get(scratch, 0, samples)
                        var sum = 0L
                        for (k in 0 until samples) { val s = scratch[k].toInt(); sum += s * s }
                        if (n == times.size) {
                            times = times.copyOf(n * 2); sums = sums.copyOf(n * 2); counts = counts.copyOf(n * 2)
                        }
                        times[n] = info.presentationTimeUs; sums[n] = sum.toDouble(); counts[n] = samples; n++
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                }
                idle = if (fed || got) 0 else idle + 1
            }
            if (n == 0) return@runCatching null

            val barSum = DoubleArray(BARS); val barCount = LongArray(BARS)
            for (k in 0 until n) {
                val bar = if (durationUs > 0) (times[k] * BARS / durationUs).toInt() else k * BARS / n
                val b = bar.coerceIn(0, BARS - 1)
                barSum[b] += sums[k]; barCount[b] += counts[k].toLong()
            }
            val db = DoubleArray(BARS) { b ->
                if (barCount[b] == 0L) NOISE_FLOOR_DB
                else (10 * log10((barSum[b] / barCount[b] / FULL_SCALE_SQ).coerceAtLeast(1e-12))).coerceAtLeast(NOISE_FLOOR_DB)
            }
            // A short note has fewer buffers than bars: hold the last level instead of gaps.
            for (b in 1 until BARS) if (barCount[b] == 0L && barCount[b - 1] != 0L) { db[b] = db[b - 1]; barCount[b] = 1 }
            val level = DoubleArray(BARS) { (db[it] - NOISE_FLOOR_DB) / -NOISE_FLOOR_DB }
            // Scaled to the loudest bar: a quiet microphone still draws a readable shape.
            val peak = level.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            ByteArray(BARS) { (level[it] / peak * 255).toInt().coerceIn(0, 255).toByte() }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }.getOrNull()
}
