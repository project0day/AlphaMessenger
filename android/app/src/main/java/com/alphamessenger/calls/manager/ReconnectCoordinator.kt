package com.alphamessenger.calls.manager

import com.alphamessenger.calls.CallMetrics
import com.alphamessenger.calls.engine.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates ICE reconnection: when the WebRTC ICE connection drops during a
 * connected call, start a watchdog. If ICE doesn't recover on its own within
 * 10s, force an ICE restart. If 30s pass with no recovery, declare FAILED.
 *
 * Owned by [CallManager]; never instantiated elsewhere.
 */
@Singleton
class ReconnectCoordinator @Inject constructor(
    private val engine: WebRtcEngine,
    private val metrics: CallMetrics,
) {
    private var watchdog: Job? = null
    private var failTimer: Job? = null

    /** ICE disconnected — start the reconnect watchdog. */
    fun start(scope: CoroutineScope, onFailed: () -> Unit) {
        metrics.onReconnect()
        cancel()
        // 10s watchdog — if ICE hasn't recovered, force a restart.
        watchdog = scope.launch {
            delay(10_000)
            engine.restartIce()
        }
        // 30s hard limit — if still not recovered, declare FAILED.
        failTimer = scope.launch {
            delay(30_000)
            onFailed()
        }
    }

    /** ICE recovered (connected) — cancel both timers. */
    fun cancel() {
        watchdog?.cancel(); watchdog = null
        failTimer?.cancel(); failTimer = null
    }
}
