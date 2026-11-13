package com.alphamessenger.data

import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight in-memory counters for the video/media disk-cache lifecycle —
 * counters, not logs, so cache-hit ratio etc. is measurable at a glance
 *. Process-lifetime only; cheap AtomicLongs.
 */
object MediaMetrics {
    val videoCacheHit = AtomicLong()
    val videoCacheMiss = AtomicLong()
    val materializeStarted = AtomicLong()
    val materializeFinished = AtomicLong()
    val materializeFailed = AtomicLong()
    val singleflightJoin = AtomicLong()
    val resumeUsed = AtomicLong()
    val resumeFromZero = AtomicLong()
    // Streaming playback audit.
    val duplicateChunkRequests = AtomicLong()  // read() joined an in-flight prefetch (avoided a dup)
    val playerBlocked = AtomicLong()           // read() had to wait on the network (cache miss)

    /** One-line snapshot incl. cache-hit ratio. */
    fun snapshot(): String {
        val hit = videoCacheHit.get()
        val miss = videoCacheMiss.get()
        val total = hit + miss
        val ratio = if (total > 0) (hit * 100 / total) else 0
        return "cacheHit=$hit miss=$miss ratio=$ratio% " +
            "materialize(start=${materializeStarted.get()} done=${materializeFinished.get()} fail=${materializeFailed.get()}) " +
            "join=${singleflightJoin.get()} resume(used=${resumeUsed.get()} zero=${resumeFromZero.get()}) " +
            "dupChunk=${duplicateChunkRequests.get()} playerBlocked=${playerBlocked.get()}"
    }
}
