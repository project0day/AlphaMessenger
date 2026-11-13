package com.alphamessenger.calls.manager

import com.alphamessenger.calls.CallPhase
import com.alphamessenger.calls.CallSignal
import com.alphamessenger.calls.CallType
import com.alphamessenger.calls.audio.CallAudioRouter
import com.alphamessenger.calls.engine.WebRtcEngine
import com.alphamessenger.calls.signaling.CallSignaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates an OUTGOING call lifecycle: starts the engine, sends the offer,
 * plays ringback, tracks the calling→ringing→connecting transition, and runs
 * the 45s "no answer" timeout.
 *
 * Owned by [CallManager]; never instantiated elsewhere. Pure delegation —
 * holds NO state of its own except the timeout job (the manager owns phase).
 */
@Singleton
class OutgoingCallCoordinator @Inject constructor(
    private val signaling: CallSignaling,
    private val engine: WebRtcEngine,
    private val audio: CallAudioRouter,
    private val timeout: TimeoutCoordinator,
) {
    /** Begin: generate a call id, start the engine, send the offer, ringback.
     *  [onTimeout] fires after 45s with no answer (manager → TIMEOUT). */
    fun start(scope: CoroutineScope, chatId: String, type: CallType, onTimeout: () -> Unit, onCallId: (String) -> Unit) {
        val callId = UUID.randomUUID().toString()
        onCallId(callId)
        audio.start(type == CallType.VIDEO)
        audio.playRingback()
        engine.startCall(type)
        timeout.startOutgoingTimer(scope, onTimeout)
    }

    /** Callee's device acknowledged the offer (ringing). */
    fun onRinging() {
        audio.stopRingback()
    }

    /** Callee answered → stop ringback, feed the answer SDP, → CONNECTING. */
    fun onAnswer(sdp: String, restart: Boolean) {
        audio.stopRingback()
        if (restart) engine.setRemoteOfferAndAnswer(sdp)
        else engine.setRemoteAnswer(sdp)
    }

    /** Caller cancels before answer → send Cancel, stop ringback. */
    fun cancel(chatId: String, callId: String) {
        audio.stopRingback()
        signaling.send(chatId, CallSignal.Cancel(callId))
        timeout.cancel()
    }
}
