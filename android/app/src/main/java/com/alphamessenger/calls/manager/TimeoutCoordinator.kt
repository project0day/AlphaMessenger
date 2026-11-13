package com.alphamessenger.calls.manager

import com.alphamessenger.calls.CallPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the call timeouts:
 *  - Outgoing: 45s (caller) — if no answer, → TIMEOUT.
 *  - Incoming: 60s (callee) — if not answered, → TIMEOUT.
 *  - Terminal linger: 1.5s — show the outcome, then → IDLE.
 *  - Duration ticker: 1s — counts up while CONNECTED.
 *
 * Owned by [CallManager]; never instantiated elsewhere.
 */
@Singleton
class TimeoutCoordinator @Inject constructor() {
    private var outgoingTimer: Job? = null
    private var incomingTimer: Job? = null
    private var durationTicker: Job? = null
    private var terminalLinger: Job? = null

    /** Outgoing 45s — fires [onTimeout] if still in CALLING/RINGING. */
    fun startOutgoingTimer(scope: CoroutineScope, onTimeout: () -> Unit) {
        outgoingTimer?.cancel()
        outgoingTimer = scope.launch {
            delay(45_000)
            onTimeout()
        }
    }

    /** Incoming 60s — fires [onTimeout] if still in INCOMING_RINGING. */
    fun startIncomingTimer(scope: CoroutineScope, onTimeout: () -> Unit) {
        incomingTimer?.cancel()
        incomingTimer = scope.launch {
            delay(60_000)
            onTimeout()
        }
    }

    /** Duration ticker — fires [onTick] every 1s while CONNECTED. */
    fun startDurationTicker(scope: CoroutineScope, onTick: () -> Unit) {
        durationTicker?.cancel()
        durationTicker = scope.launch {
            while (true) {
                onTick()
                delay(1000)
            }
        }
    }

    /** Terminal linger — fires [onCollapse] after [ms] to return to IDLE. */
    fun startTerminalLinger(scope: CoroutineScope, ms: Long, onCollapse: () -> Unit) {
        terminalLinger?.cancel()
        terminalLinger = scope.launch {
            delay(ms)
            onCollapse()
        }
    }

    /** Cancel the outgoing + incoming + duration timers (call ended). */
    fun cancel() {
        outgoingTimer?.cancel(); outgoingTimer = null
        incomingTimer?.cancel(); incomingTimer = null
        durationTicker?.cancel(); durationTicker = null
    }

    /** Cancel everything (including the terminal linger). */
    fun cancelAll() {
        cancel()
        terminalLinger?.cancel(); terminalLinger = null
    }
}
