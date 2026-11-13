package com.alphamessenger.calls.manager

import com.alphamessenger.calls.CallPeer
import com.alphamessenger.calls.CallRepository
import com.alphamessenger.calls.CallSignal
import com.alphamessenger.calls.CallType
import com.alphamessenger.calls.audio.CallAudioRouter
import com.alphamessenger.calls.engine.WebRtcEngine
import com.alphamessenger.calls.service.CallNotificationHelper
import com.alphamessenger.calls.signaling.CallSignaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates an INCOMING call lifecycle: stores the remote offer, plays the
 * ringtone, shows the full-screen notification, runs the 60s timeout, and on
 * accept/reject drives the engine + signaling.
 *
 * Owned by [CallManager]; never instantiated elsewhere.
 */
@Singleton
class IncomingCallCoordinator @Inject constructor(
    private val signaling: CallSignaling,
    private val engine: WebRtcEngine,
    private val audio: CallAudioRouter,
    private val repo: CallRepository,
    private val notifier: CallNotificationHelper,
    private val timeout: TimeoutCoordinator,
) {
    /** The remote offer SDP captured on arrival — fed to the engine on accept. */
    var pendingRemoteOffer: String = ""
        private set

    /** A fresh Offer arrived — ring + notify + start the incoming timeout.
     *  [onTimeout] fires after 60s unanswered (manager → TIMEOUT). */
    fun onOffer(scope: CoroutineScope, chatId: String, @Suppress("UNUSED_PARAMETER") callId: String, sdp: String, media: CallType, onTimeout: () -> Unit, onPeer: (CallPeer) -> Unit) {
        pendingRemoteOffer = sdp
        audio.start(media == CallType.VIDEO)
        audio.playRingtone()
        timeout.startIncomingTimer(scope, onTimeout)
        scope.launch {
            val peer = repo.resolvePeer(chatId)
            onPeer(peer)
        }
    }

    /** User accepted → stop ringtone, feed the offer to the engine, answer. */
    fun accept(type: CallType) {
        audio.stopRingtone()
        engine.acceptCall(type, pendingRemoteOffer)
        timeout.cancel()
    }

    /** User declined → stop ringtone, send Reject. */
    fun reject(chatId: String, callId: String) {
        audio.stopRingtone()
        signaling.send(chatId, CallSignal.Reject(callId))
        timeout.cancel()
    }

    /** Caller cancelled before answer → stop ringtone. */
    fun onCallerCancelled() {
        audio.stopRingtone()
        timeout.cancel()
    }
}
