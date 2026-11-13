package com.alphamessenger.calls.manager

import com.alphamessenger.calls.CallPhase
import com.alphamessenger.calls.isActive
import com.alphamessenger.calls.isTerminal

/**
 * THE call state machine. Encodes the LEGAL transitions between [CallPhase]s.
 *
 * Every transition goes through [transition] — if the (from → to) pair is not
 * legal, the transition is REJECTED (returns null, current phase unchanged).
 * This makes the call flow deterministic: the manager can't accidentally jump
 * from CONNECTED back to OUTGOING_CALLING, etc.
 *
 * ## Terminal phases
 * ENDED/BUSY/REJECTED/TIMEOUT/FAILED are terminal — once entered, the only
 * legal transition is back to IDLE (the manager lingers ~1.5s for the UI to
 * show the outcome, then collapses to IDLE).
 *
 * ## RECONNECTING
 * Only reachable from CONNECTED (network blip). Returns to CONNECTED on
 * success, or FAILED on timeout.
 */
object CallStateMachine {

    /** Legal (from → to) transitions. Everything else is rejected. */
    private val legal: Set<Pair<CallPhase, CallPhase>> = setOf(
        // Outgoing call lifecycle.
        CallPhase.IDLE to CallPhase.OUTGOING_CALLING,
        CallPhase.OUTGOING_CALLING to CallPhase.OUTGOING_RINGING,
        CallPhase.OUTGOING_CALLING to CallPhase.CONNECTING,
        CallPhase.OUTGOING_RINGING to CallPhase.CONNECTING,
        CallPhase.OUTGOING_CALLING to CallPhase.TIMEOUT,
        CallPhase.OUTGOING_RINGING to CallPhase.TIMEOUT,
        CallPhase.OUTGOING_CALLING to CallPhase.BUSY,
        CallPhase.OUTGOING_RINGING to CallPhase.BUSY,
        CallPhase.OUTGOING_CALLING to CallPhase.REJECTED,
        CallPhase.OUTGOING_RINGING to CallPhase.REJECTED,

        // Incoming call lifecycle.
        CallPhase.IDLE to CallPhase.INCOMING_RINGING,
        CallPhase.INCOMING_RINGING to CallPhase.CONNECTING,  // accepted
        CallPhase.INCOMING_RINGING to CallPhase.REJECTED,    // declined
        CallPhase.INCOMING_RINGING to CallPhase.ENDED,       // caller cancelled
        CallPhase.INCOMING_RINGING to CallPhase.TIMEOUT,
        CallPhase.INCOMING_RINGING to CallPhase.BUSY,

        // Connecting → connected / failed / remote-end.
        CallPhase.CONNECTING to CallPhase.CONNECTED,
        CallPhase.CONNECTING to CallPhase.FAILED,
        CallPhase.CONNECTING to CallPhase.ENDED,

        // Connected lifecycle.
        CallPhase.CONNECTED to CallPhase.RECONNECTING,
        CallPhase.RECONNECTING to CallPhase.CONNECTED,
        CallPhase.RECONNECTING to CallPhase.FAILED,
        CallPhase.CONNECTED to CallPhase.ENDED,      // remote ended
        CallPhase.RECONNECTING to CallPhase.ENDED,

        // Ending → terminal.
        CallPhase.ENDING to CallPhase.ENDED,
    )

    /** Try to move from [current] to [target]. Returns the new phase on
     *  success, or null if the transition is illegal. */
    fun transition(current: CallPhase, target: CallPhase): CallPhase? {
        if (current == target) return current
        // Local hangup from ANY active phase → ENDING.
        if (target == CallPhase.ENDING && current.isActive) return CallPhase.ENDING
        // Transport/media failure from any active phase (or mid-teardown).
        if (target == CallPhase.FAILED && (current.isActive || current == CallPhase.ENDING)) return CallPhase.FAILED
        // ENDING collapses to ENDED even if the peer's End raced us.
        if (current == CallPhase.ENDING && target == CallPhase.ENDED) return CallPhase.ENDED
        // Terminal → IDLE (collapse after the linger).
        if (current.isTerminal && target == CallPhase.IDLE) return CallPhase.IDLE
        // Explicit legal edge.
        if ((current to target) in legal) return target
        return null
    }
}
