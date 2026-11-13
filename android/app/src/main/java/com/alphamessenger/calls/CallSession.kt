package com.alphamessenger.calls

/**
 * THE call session — a single value object describing the CURRENT call.
 * Everything call-specific (ids, timestamps, direction, metrics, capabilities
 * in effect) lives here. [CallManager] owns `currentSession` and the UI reads
 * it via [CallUiState].
 *
 * ## Why now (auditor's recommendation)
 *  Right now CallManager holds callId/peer/phase as loose fields. As the
 *  module grows (group calls, transfer, etc.), those fields would multiply.
 *  Extracting them into [CallSession] keeps CallManager thin + makes a future
 *  "call history" feature trivial (just persist the session).
 *
 * ## Lifecycle
 *  - Created when a call starts (outgoing or incoming).
 *  - Mutated as the call progresses (connectedAt, endedAt filled in).
 *  - Discarded when the call returns to IDLE (after the terminal linger).
 *
 * ## NOT here
 *  - Phase (transient UI state — lives in [CallUiState]).
 *  - Mute/route/video toggles (transient — lives in [CallUiState]).
 *  These are UI-facing controls; the session is the call's identity + history.
 */
data class CallSession(
    /** Stable id (used in all signaling messages). */
    val callId: String,
    /** The chat this call belongs to. */
    val chatId: String,
    /** The peer's npub (for re-routing / blocking / history). */
    val peerPubKey: String,
    /** Audio or video. */
    val callType: CallType,
    /** Outgoing (we placed) or incoming (we received). */
    val direction: Direction,
    /** Epoch ms — when the call was initiated (offer sent or received). */
    val startedAt: Long,
    /** Epoch ms — when media started flowing (CONNECTED). 0 until then. */
    val connectedAt: Long = 0L,
    /** Epoch ms — when the call ended (terminal phase). 0 until then. */
    val endedAt: Long = 0L,
    /** The metrics snapshot at the end (for history). Live stats are in
     *  [CallMetrics]; this is a frozen copy for the session record. */
    val metrics: CallMetrics.Snapshot = CallMetrics.Snapshot(),
    /** The capabilities that were in effect for THIS call (frozen — flipping
     *  a capability mid-call doesn't retroactively change the session). */
    val capabilities: CallCapabilities = CallCapabilitiesProvider.current,
) {
    enum class Direction { OUTGOING, INCOMING }

    /** Duration in seconds (from connectedAt to endedAt, or now if ongoing). */
    val durationSec: Long
        get() {
            val end = if (endedAt > 0) endedAt else System.currentTimeMillis()
            return if (connectedAt > 0) (end - connectedAt) / 1000 else 0L
        }

    /** Did this call ever connect? */
    val wasConnected: Boolean get() = connectedAt > 0L
}
