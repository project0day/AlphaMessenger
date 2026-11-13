package com.alphamessenger.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE call quality metrics collector — gathers WebRTC stats for diagnostics.
 *
 * ## Why now (auditor's recommendation)
 *  Even if the UI doesn't show them yet, COLLECTING them from day one means
 *  "show call statistics" is a UI-only change later — the data is already
 *  flowing. Without this, you'd have to thread stats through the engine +
 *  manager retroactively.
 *
 * ## What's collected
 *  - ICE connected / failed timestamps
 *  - Reconnect count (how many ICE restarts happened)
 *  - Packet loss (%), bitrate (kbps), codec name
 *  - Round-trip time (ms), jitter (ms), frames dropped
 *
 * ## Source
 *  The WebRTC engine calls [update] on each RTCStatsReport (polled ~1s during
 *  a connected call). The UI reads [snapshot] for a live stats panel.
 */
@Singleton
class CallMetrics @Inject constructor() {

    data class Snapshot(
        val iceConnectedAtMs: Long = 0L,
        val iceFailedAtMs: Long = 0L,
        val reconnectCount: Int = 0,
        val packetLossPercent: Float = 0f,
        val bitrateKbps: Int = 0,
        val codec: String = "",
        val roundTripTimeMs: Int = 0,
        val jitterMs: Int = 0,
        val framesDropped: Int = 0,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Called by the engine when ICE reaches connected. */
    fun onIceConnected() {
        _snapshot.value = _snapshot.value.copy(iceConnectedAtMs = System.currentTimeMillis())
    }

    /** Called by the engine when ICE fails. */
    fun onIceFailed() {
        _snapshot.value = _snapshot.value.copy(iceFailedAtMs = System.currentTimeMillis())
    }

    /** Called by the manager on each ICE restart attempt. */
    fun onReconnect() {
        _snapshot.value = _snapshot.value.copy(reconnectCount = _snapshot.value.reconnectCount + 1)
    }

    /** Called by the engine ~1s with fresh RTCStatsReport values. */
    fun update(
        packetLossPercent: Float,
        bitrateKbps: Int,
        codec: String,
        roundTripTimeMs: Int,
        jitterMs: Int,
        framesDropped: Int,
    ) {
        _snapshot.value = _snapshot.value.copy(
            packetLossPercent = packetLossPercent,
            bitrateKbps = bitrateKbps,
            codec = codec,
            roundTripTimeMs = roundTripTimeMs,
            jitterMs = jitterMs,
            framesDropped = framesDropped,
        )
    }

    /** Reset for a new call. */
    fun reset() {
        _snapshot.value = Snapshot()
    }
}
