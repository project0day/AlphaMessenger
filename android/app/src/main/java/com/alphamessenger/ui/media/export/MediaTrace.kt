package com.alphamessenger.ui.media.export

import android.os.SystemClock

/**
 * One trace for the whole attachment journey: from file pick to the first
 * frame on the recipient's side.
 *
 * Exists now rather than "later" for a practical reason: the next tasks
 * (is faststart needed, where do the seconds before the first frame go,
 * what to do about upload resume) all start with "measure". Without a shared
 * place each would grow its own throwaway logs, and there would be nothing
 * to compare between them.
 *
 * An event's key is the (sendId, itemIndex) pair: it stitches one send's
 * events into a chain even when four attachments interleave.
 *
 * WHAT WILL NEVER BE HERE: URIs, file names, keys, signatures, content.
 * Only properties and durations. The trace is written in release builds too,
 * so the rule is hard, not "best effort".
 */
object MediaTrace {

    const val TAG = "AlphaMediaTrace"

    // --- Stages. Names are short: they are read by eye in logcat ---
    const val IMPORT = "IMPORT"
    const val SEND_TAP = "SEND_TAP"
    const val BUBBLE_VISIBLE = "BUBBLE_VISIBLE"
    const val PLAN = "PLAN"
    const val PROCESS_START = "PROCESS_START"
    const val PROCESS_END = "PROCESS_END"
    const val VALIDATION_END = "VALIDATION_END"
    const val UPLOAD_FIRST_BYTE = "UPLOAD_FIRST_BYTE"
    const val UPLOAD_END = "UPLOAD_END"
    const val MESSAGE_PUBLISH = "MESSAGE_PUBLISH"
    const val PLAYER_PREPARE = "PLAYER_PREPARE"
    const val PLAYER_READY = "PLAYER_READY"
    const val FIRST_FRAME = "FIRST_FRAME"

    /** Reference points: durations between stages are computed from them. */
    private val marks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun key(sendId: String, item: Int?) = if (item == null) sendId else "$sendId#$item"

    /**
     * Record an event. [fields] — properties and numbers only.
     *
     * The duration is computed automatically from the previous event of the
     * same chain, so "how long preparation took" needs no eyeballing of
     * line timestamps.
     */
    fun event(
        sendId: String,
        itemIndex: Int? = null,
        stage: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val k = key(sendId, itemIndex)
        val now = SystemClock.elapsedRealtime()
        val previous = marks.put(k, now)
        val sinceMs = previous?.let { now - it }
        val body = fields.filter { it.second != null }
            .joinToString(" ") { "${it.first}=${it.second}" }
        android.util.Log.i(
            TAG,
            buildString {
                append(k).append(' ').append(stage)
                if (sinceMs != null) append(" +").append(sinceMs).append("ms")
                if (body.isNotEmpty()) append(' ').append(body)
            },
        )
    }

    /** Chain finished — no reason to keep the reference point. */
    fun finish(sendId: String, itemIndex: Int? = null) {
        marks.remove(key(sendId, itemIndex))
    }
}
