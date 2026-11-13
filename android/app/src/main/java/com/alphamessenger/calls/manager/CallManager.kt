package com.alphamessenger.calls.manager

import android.content.Context
import com.alphamessenger.calls.AudioRoute
import com.alphamessenger.calls.CallCapabilitiesProvider
import com.alphamessenger.calls.CallMetrics
import com.alphamessenger.calls.CallPhase
import com.alphamessenger.calls.CallPeer
import com.alphamessenger.calls.CallRecorder
import com.alphamessenger.calls.CallSignal
import com.alphamessenger.calls.CallType
import com.alphamessenger.calls.CallUiState
import com.alphamessenger.calls.CallRepository
import com.alphamessenger.calls.engine.WebRtcEngine
import com.alphamessenger.calls.engine.WebRtcEvent
import com.alphamessenger.calls.isActive
import com.alphamessenger.calls.isTerminal
import com.alphamessenger.calls.service.CallNotificationHelper
import com.alphamessenger.calls.signaling.CallSignaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE call orchestrator (Hilt singleton) — the ONE entry point for the entire
 * calls module.
 *
 * ## Thin orchestrator
 * The heavy lifting is delegated to coordinators:
 *  - [outgoing] — outgoing call lifecycle (offer, ringback, cancel).
 *  - [incoming] — incoming call lifecycle (ringtone, accept, reject).
 *  - [reconnect] — ICE disconnect watchdog + forced restart.
 *  - [timeout] — all call timers (45s/60s/1.5s/duration ticker).
 *  - [audio] — wraps CallAudioRouter + AudioProcessingConfig.
 *
 * The manager itself owns ONLY: the [CallUiState] snapshot, the call id/chat
 * id, the busy guard, and the state-machine transitions. ~250 lines, stable.
 *
 * ## Public API (UI + integration)
 *  - [startOutgoing] / [acceptIncoming] / [rejectIncoming] / [cancelOutgoing] / [hangup]
 *  - [toggleMute] / [setRoute] / [toggleVideo] / [switchCamera] / [toggleHold]
 *  - [uiState] — the ONE observable snapshot for the UI.
 *
 * ## Busy guard
 *  Only ONE call at a time. An Offer arriving while a call is active → BUSY.
 *
 * ## Foreground service
 *  Starts [CallForegroundService] when a call becomes active; stops on end.
 */
@Singleton
class CallManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val signaling: CallSignaling,
    private val engine: WebRtcEngine,
    private val repo: CallRepository,
    private val notifier: CallNotificationHelper,
    private val metrics: CallMetrics,
    private val recorder: CallRecorder,
    private val devices: com.alphamessenger.calls.media.MediaDeviceManager,
    private val deviceCaps: com.alphamessenger.calls.DeviceCapabilities,
    private val outgoing: OutgoingCallCoordinator,
    private val incoming: IncomingCallCoordinator,
    private val reconnect: ReconnectCoordinator,
    private val timeout: TimeoutCoordinator,
    private val audio: AudioCoordinator,
    private val history: com.alphamessenger.calls.CallHistoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    /** The last call error (for the UI to show a toast). Null = no error. */
    private val _lastError = MutableStateFlow<com.alphamessenger.calls.CallError?>(null)
    val lastError: StateFlow<com.alphamessenger.calls.CallError?> = _lastError.asStateFlow()

    /** The current call session (identity + history). Null when IDLE. */
    private var currentSession: com.alphamessenger.calls.CallSession? = null
    val session: com.alphamessenger.calls.CallSession? get() = currentSession

    private var currentCallId: String = ""
    private var currentChatId: String = ""
    private var connectedSinceMs: Long = 0L
    /** ICE reported connected. Tracked separately from `phase` to survive the
     *  race where ICE connects before the phase reaches CONNECTING. */
    private var iceConnected: Boolean = false

    init {
        // WebRTC engine events → state transitions.
        scope.launch { engine.events.collect { onWebRtcEvent(it) } }
        // Incoming signaling → route to the right coordinator.
        scope.launch {
            signaling.incoming.collect { (chatId, senderId, signal, ageMs, elsewhere) ->
                if (elsewhere) onElsewhere(signal) else onIncomingSignal(chatId, senderId, signal, ageMs)
            }
        }
    }

    // --- Public API: lifecycle ---

    /** Place an outgoing call. */
    fun startOutgoing(chatId: String, type: CallType) {
        if (_uiState.value.phase != CallPhase.IDLE) return
        currentChatId = chatId
        outgoing.start(scope, chatId, type, onTimeout = { onOutgoingTimeout() }) { callId ->
            currentCallId = callId
            // A NEW call starts from a clean state (nothing of the last one —
            // mute, duration, video flags — leaks in), and its phase is set right
            // away: the screen must never see IDLE between "dial" and "calling".
            // A video call means both cameras are on from the start, so the
            // peer's picture is shown as soon as it arrives.
            update {
                CallUiState(
                    phase = CallPhase.OUTGOING_CALLING, callId = callId, chatId = chatId,
                    type = type, outgoing = true, videoEnabled = type == CallType.VIDEO,
                    remoteVideoActive = type == CallType.VIDEO, frontCamera = true,
                )
            }
            scope.launch {
                val peer = repo.resolvePeer(chatId)
                currentSession = com.alphamessenger.calls.CallSession(
                    callId = callId, chatId = chatId, peerPubKey = peer.npub,
                    callType = type, direction = com.alphamessenger.calls.CallSession.Direction.OUTGOING,
                    startedAt = System.currentTimeMillis(),
                )
                update {
                    if (it.callId != callId) it
                    else it.copy(peerName = peer.displayName, peerAvatarPath = peer.avatarPath)
                }
            }
        }
        startService(video = type == CallType.VIDEO)
    }

    /** Accept an incoming call. */
    fun acceptIncoming() {
        val s = _uiState.value
        if (s.phase != CallPhase.INCOMING_RINGING) return
        audio.hapticAccept()
        incoming.accept(s.type)
        moveTo(CallPhase.CONNECTING)
        startService(video = s.type == CallType.VIDEO)
        launchCallUi()
    }

    /** Reject an incoming call. */
    fun rejectIncoming() {
        val s = _uiState.value
        if (s.phase != CallPhase.INCOMING_RINGING) return
        incoming.reject(currentChatId, currentCallId)
        moveTo(CallPhase.REJECTED)
        finishTerminal()
    }

    /** Decline and answer in the chat instead ("Can't talk right now"…). */
    fun rejectWithMessage(text: String) {
        if (_uiState.value.phase != CallPhase.INCOMING_RINGING) return
        val chat = currentChatId
        rejectIncoming()
        scope.launch { runCatching { repo.sendText(chat, text) } }
    }

    /** Decline now, and remind me to call back in a few minutes. */
    fun remindLater() {
        val s = _uiState.value
        if (s.phase != CallPhase.INCOMING_RINGING) return
        val chat = currentChatId
        rejectIncoming()
        notifier.scheduleCallReminder(chat, s.peerName, s.type == CallType.VIDEO, s.peerAvatarPath)
    }

    /** "Call back" from the call-ended screen: the same person, the same kind of call. */
    fun redial(): Boolean {
        val s = _uiState.value
        if (!s.phase.isTerminal || s.chatId.isBlank()) return false
        timeout.cancelAll()
        collapseToIdle()
        startOutgoing(s.chatId, s.type)
        return true
    }

    /** Caller cancels before answer. */
    fun cancelOutgoing() {
        val s = _uiState.value
        if (s.phase != CallPhase.OUTGOING_CALLING && s.phase != CallPhase.OUTGOING_RINGING) return
        outgoing.cancel(currentChatId, currentCallId)
        moveTo(CallPhase.ENDING)
        finishTerminal()
    }

    /** End any active call. */
    fun hangup() {
        val s = _uiState.value
        if (s.phase == CallPhase.IDLE || s.phase.isTerminal) return
        if (s.phase == CallPhase.INCOMING_RINGING) { rejectIncoming(); return }
        if (s.phase == CallPhase.OUTGOING_CALLING || s.phase == CallPhase.OUTGOING_RINGING) { cancelOutgoing(); return }
        signaling.send(currentChatId, CallSignal.End(currentCallId))
        moveTo(CallPhase.ENDING)
        finishTerminal()
    }

    // --- Public API: in-call controls ---

    fun toggleMute() {
        val s = _uiState.value
        // Our own mic: ours to mute from the moment we dial or accept.
        if (!s.phase.isActive || s.phase == CallPhase.INCOMING_RINGING) return
        val muted = !s.muted
        audio.setMicMuted(muted)
        engine.setMicEnabled(!muted)
        update { it.copy(muted = muted) }
    }

    fun setRoute(route: AudioRoute) {
        audio.setRoute(route)
        update { it.copy(audioRoute = route) }
    }

    // --- Video ⇄ audio: NO renegotiation ---
    // Audio+video are negotiated ONCE at call start (video track present,
    // camera closed), so switching is just "flip my camera + tell the peer".
    // Crash-proof: the encoder is configured once, never re-configured mid-call
    // (mid-call re-configuration aborts the EncoderQueue). "Show my camera" and
    // "accept the peer's video" are DIFFERENT operations — accepting NEVER turns
    // on your own camera.

    /** THE camera button = show / hide MY OWN video. Instant, no SDP exchange. */
    fun toggleVideo() {
        val s = _uiState.value
        if (s.phase != CallPhase.CONNECTED && s.phase != CallPhase.RECONNECTING) return
        val enabled = !s.videoEnabled
        engine.setVideoEnabled(enabled)
        signaling.send(currentChatId, CallSignal.VideoState(currentCallId, on = enabled))
        update {
            it.copy(
                videoEnabled = enabled,
                type = if (enabled || it.remoteVideoActive) CallType.VIDEO else CallType.AUDIO,
            )
        }
        startService(video = enabled || s.remoteVideoActive)
    }

    /** User consented to VIEW the peer's video. Does NOT turn
     *  on our own camera. The peer's frames are already arriving (video was
     *  negotiated up front); we simply start rendering them. */
    fun acceptVideoRequest() {
        if (!_uiState.value.incomingVideoRequest) return
        update { it.copy(incomingVideoRequest = false, remoteVideoActive = true, type = CallType.VIDEO) }
        startService(video = true)
    }

    /** User tapped "Not now" — stay on audio; ask the peer to stop sending. */
    fun declineVideoRequest() {
        if (!_uiState.value.incomingVideoRequest) return
        update { it.copy(incomingVideoRequest = false) }
        signaling.send(currentChatId, CallSignal.VideoDecline(currentCallId))
    }

    fun switchCamera() {
        engine.switchCamera()
        update { it.copy(frontCamera = !it.frontCamera) }
    }

    fun toggleHold() {
        val s = _uiState.value
        if (s.phase != CallPhase.CONNECTED) return
        val onHold = !s.localOnHold
        signaling.send(currentChatId, if (onHold) CallSignal.Hold(currentCallId) else CallSignal.Resume(currentCallId))
        engine.setMicEnabled(!onHold && !s.muted)
        engine.setVideoEnabled(if (onHold) false else s.videoEnabled)
        update { it.copy(localOnHold = onHold) }
    }

    // --- Incoming signal routing ---

    private suspend fun onIncomingSignal(chatId: String, senderId: String, signal: CallSignal, ageMs: Long) {
        val s = _uiState.value
        // Older than the caller's ring: nobody waits on this call any more. An
        // offer becomes a missed call (it came late — the app was locked or
        // offline); anything else about a call long over is dropped.
        if (ageMs > com.alphamessenger.calls.CallPolicy().outgoingTimeoutMs) {
            if (signal is CallSignal.Offer && !signal.restart) recordLateOffer(chatId, senderId, signal, ageMs)
            return
        }
        // Our privacy rules first: a call they do not let through never rings.
        // The caller is told nothing — for them it just goes unanswered.
        if (signal is CallSignal.Offer && !signal.restart &&
            !repo.callAllowed(chatId, senderId, signal.media == CallType.VIDEO)
        ) {
            val peer = runCatching { repo.resolvePeer(chatId) }.getOrNull()
            history.add(
                com.alphamessenger.calls.CallHistoryEntry(
                    callId = signal.callId, chatId = chatId,
                    peerName = peer?.displayName.orEmpty(), peerAvatarPath = peer?.avatarPath.orEmpty(),
                    video = signal.media == CallType.VIDEO, outgoing = false,
                    outcome = com.alphamessenger.calls.CallOutcome.REFUSED,
                    atMs = System.currentTimeMillis(), durationSec = 0,
                ),
            )
            return
        }
        // Fresh Offer when IDLE → INCOMING_RINGING (or BUSY if already in a call).
        if (signal is CallSignal.Offer && !signal.restart) {
            if (s.phase != CallPhase.IDLE) {
                signaling.send(chatId, CallSignal.Busy(signal.callId))
                return
            }
            currentCallId = signal.callId
            currentChatId = chatId
            incoming.onOffer(scope, chatId, signal.callId, signal.sdp, signal.media, onTimeout = { onIncomingTimeout() }) { peer ->
                currentSession = com.alphamessenger.calls.CallSession(
                    callId = signal.callId, chatId = chatId, peerPubKey = peer.npub,
                    callType = signal.media, direction = com.alphamessenger.calls.CallSession.Direction.INCOMING,
                    startedAt = System.currentTimeMillis(),
                )
                update {
                    CallUiState(
                        phase = CallPhase.INCOMING_RINGING, callId = signal.callId, chatId = chatId,
                        peerName = peer.displayName, peerAvatarPath = peer.avatarPath,
                        type = signal.media, outgoing = false, videoEnabled = signal.media == CallType.VIDEO,
                        // A video call: the caller's camera is on from the start.
                        remoteVideoActive = signal.media == CallType.VIDEO,
                    )
                }
                // If the app is in the foreground, open the incoming-call
                // screen right away — NO heads-up notification (avoids the
                // duplicate-buttons problem). Only show the
                // full-screen-intent notification when the app is backgrounded
                // (the system blocks Activity starts from the background).
                if (isAppForegrounded()) {
                    launchCallUi()
                } else {
                    notifier.showIncomingCall(peer.displayName, currentCallId, signal.media == CallType.VIDEO, peer.avatarPath)
                }
            }
            return
        }
        // ICE-restart Offer on an established call (network blip). Video is NOT
        // renegotiated anymore (negotiated once up front), so this path is
        // ICE-restart only.
        if (signal is CallSignal.Offer && signal.restart) {
            if (signal.callId != currentCallId) return
            engine.setRemoteOfferAndAnswer(signal.sdp)
            return
        }
        // Everything else must be for the current call.
        if (signal.callId != currentCallId) return
        when (signal) {
            is CallSignal.Ringing -> if (s.phase == CallPhase.OUTGOING_CALLING) {
                outgoing.onRinging(); moveTo(CallPhase.OUTGOING_RINGING)
            }
            is CallSignal.Answer -> when {
                s.phase == CallPhase.OUTGOING_CALLING || s.phase == CallPhase.OUTGOING_RINGING -> {
                    outgoing.onAnswer(signal.sdp, signal.restart); moveTo(CallPhase.CONNECTING)
                }
                // ICE-restart answer on an established call.
                s.phase == CallPhase.CONNECTED || s.phase == CallPhase.RECONNECTING -> {
                    engine.setRemoteAnswer(signal.sdp)
                }
                else -> {}
            }
            is CallSignal.Ice -> signal.candidates.forEach { engine.addRemoteIce(it) }
            is CallSignal.Reject -> { moveTo(CallPhase.REJECTED); finishTerminal() }
            is CallSignal.Busy -> { moveTo(CallPhase.BUSY); finishTerminal() }
            is CallSignal.Cancel -> when {
                s.phase == CallPhase.INCOMING_RINGING -> {
                    incoming.onCallerCancelled(); moveTo(CallPhase.ENDED); finishTerminal()
                }
                // RACE: the caller gave up (still OUTGOING on their side) AFTER we
                // already accepted and moved to CONNECTING/CONNECTED. Their Cancel
                // must still tear us down — otherwise we hang on "Connecting…"
                // forever.
                s.phase.isActive || s.phase == CallPhase.CONNECTING -> {
                    moveTo(CallPhase.ENDED); finishTerminal()
                }
            }
            is CallSignal.End -> if (s.phase.isActive || s.phase == CallPhase.CONNECTING) {
                moveTo(CallPhase.ENDED); finishTerminal()
            }
            is CallSignal.Hold -> update { it.copy(remoteOnHold = true) }
            is CallSignal.Resume -> update { it.copy(remoteOnHold = false) }
            is CallSignal.VideoState -> if (s.phase == CallPhase.CONNECTING || s.phase == CallPhase.CONNECTED || s.phase == CallPhase.RECONNECTING) {
                android.util.Log.i("CallFlow", "peer camera ${if (signal.on) "on" else "off"} (ours ${if (s.videoEnabled) "on" else "off"})")
                if (signal.on) {
                    // Peer turned their camera ON. If WE already show video →
                    // render them right away (mutual, no dialog). Otherwise ask
                    // for consent; we keep NOT rendering until the user accepts.
                    if (s.videoEnabled) {
                        update { it.copy(remoteVideoActive = true, type = CallType.VIDEO) }
                        startService(video = true)
                    } else {
                        update { it.copy(incomingVideoRequest = true) }
                    }
                } else {
                    // Peer turned their camera OFF.
                    update {
                        it.copy(
                            remoteVideoActive = false,
                            incomingVideoRequest = false,
                            type = if (it.videoEnabled) CallType.VIDEO else CallType.AUDIO,
                        )
                    }
                    if (!_uiState.value.videoEnabled) startService(video = false)
                }
            }
            is CallSignal.VideoDecline -> {
                // Peer declined our video → turn our camera back off.
                engine.setVideoEnabled(false)
                update {
                    it.copy(
                        videoEnabled = false,
                        type = if (it.remoteVideoActive) CallType.VIDEO else CallType.AUDIO,
                    )
                }
                if (!_uiState.value.remoteVideoActive) startService(video = false)
            }
            is CallSignal.Offer -> {} // handled above
        }
    }

    // --- WebRTC event routing ---

    private fun onWebRtcEvent(ev: WebRtcEvent) {
        when (ev) {
            is WebRtcEvent.OfferCreated -> {
                val restart = _uiState.value.phase == CallPhase.RECONNECTING
                signaling.send(currentChatId, CallSignal.Offer(currentCallId, ev.sdp, _uiState.value.type, restart))
            }
            is WebRtcEvent.AnswerCreated -> {
                val restart = _uiState.value.phase == CallPhase.RECONNECTING
                signaling.send(currentChatId, CallSignal.Answer(currentCallId, ev.sdp, restart))
            }
            is WebRtcEvent.IceCandidate -> signaling.send(currentChatId, CallSignal.Ice(currentCallId, listOf(ev.candidate)))
            is WebRtcEvent.IceStateChanged -> if (ev.connected) {
                // Remember ICE is up, THEN try to promote to CONNECTED. On a fast
                // local network ICE can connect BEFORE the phase reaches CONNECTING
                // (the answer is still being applied) — without remembering it, the
                // event gets dropped and the call hangs on "Connecting…" forever even
                // though audio flowed. `tryPromoteToConnected` is also called right
                // after we enter CONNECTING, so whichever happens last wins.
                iceConnected = true
                tryPromoteToConnected()
            } else {
                iceConnected = false
                if (_uiState.value.phase == CallPhase.CONNECTED) {
                    moveTo(CallPhase.RECONNECTING)
                    reconnect.start(scope) { moveTo(CallPhase.FAILED); finishTerminal() }
                }
            }
            // The remote video TRACK exists for the whole call (video negotiated
            // up front) — its presence does NOT mean the peer's camera is on.
            // Visibility is driven by the peer's VideoState signal, not this.
            is WebRtcEvent.RemoteVideoTrack -> {}
            is WebRtcEvent.Failed -> {
                if (_uiState.value.phase.isActive) {
                    metrics.onIceFailed(); moveTo(CallPhase.FAILED); finishTerminal()
                }
            }
        }
    }

    // --- State machine + timers ---

    private fun moveTo(target: CallPhase) {
        val from = _uiState.value.phase
        val next = CallStateMachine.transition(from, target)
        if (next == null) {
            android.util.Log.w("CallFlow", "moveTo REJECTED $from → $target")
            return
        }
        android.util.Log.i("CallFlow", "phase $from → $next")
        update { it.copy(phase = next) }
        // Reconcile the ICE-connected race: if we just entered CONNECTING but ICE
        // already reported connected, promote to CONNECTED immediately.
        if (next == CallPhase.CONNECTING) tryPromoteToConnected()
    }

    /** Promote CONNECTING/RECONNECTING → CONNECTED once ICE is up. Idempotent
     *  and race-safe: called both when ICE connects and when we enter CONNECTING,
     *  so the ordering of those two events doesn't matter. */
    private fun tryPromoteToConnected() {
        if (!iceConnected) return
        val ph = _uiState.value.phase
        if (ph != CallPhase.CONNECTING && ph != CallPhase.RECONNECTING) return
        val firstConnect = ph == CallPhase.CONNECTING
        moveTo(CallPhase.CONNECTED)
        reconnect.cancel()
        metrics.onIceConnected()
        // Tell the peer our camera is on — the explicit truth behind the
        // up-front assumption, so their screen shows us even if they joined
        // a voice call and we switched on before the line was up.
        if (firstConnect && _uiState.value.videoEnabled) {
            signaling.send(currentChatId, CallSignal.VideoState(currentCallId, on = true))
        }
        currentSession = currentSession?.copy(connectedAt = System.currentTimeMillis())
        if (CallCapabilitiesProvider.current.supportsRecording) recorder.start(currentCallId)
        notifier.updateOngoingConnected(
            _uiState.value.peerName, currentCallId,
            _uiState.value.type == CallType.VIDEO, _uiState.value.peerAvatarPath,
        )
        startDurationTicker()
    }

    /** 45s with no answer (caller side) → send Cancel + TIMEOUT. */
    private fun onOutgoingTimeout() {
        val s = _uiState.value
        if (s.phase != CallPhase.OUTGOING_CALLING && s.phase != CallPhase.OUTGOING_RINGING) return
        outgoing.cancel(currentChatId, currentCallId)
        moveTo(CallPhase.TIMEOUT)
        finishTerminal()
    }

    /** Our other phone answered, declined or ended the call ringing here: it
     *  stops ringing, and no missed call is kept (the call was taken). */
    private fun onElsewhere(signal: CallSignal) {
        if (_uiState.value.phase != CallPhase.INCOMING_RINGING || signal.callId != currentCallId) return
        when (signal) {
            is CallSignal.Answer, is CallSignal.Reject, is CallSignal.Busy, is CallSignal.End -> {
                incoming.onCallerCancelled()
                currentSession = null
                moveTo(CallPhase.ENDED)
                finishTerminal()
            }
            else -> {}
        }
    }

    /** 60s unanswered (callee side) → stop ringing + TIMEOUT (missed call). */
    private fun onIncomingTimeout() {
        if (_uiState.value.phase != CallPhase.INCOMING_RINGING) return
        incoming.onCallerCancelled()
        moveTo(CallPhase.TIMEOUT)
        finishTerminal()
    }

    private fun startDurationTicker() {
        connectedSinceMs = android.os.SystemClock.elapsedRealtime()
        timeout.startDurationTicker(scope) {
            val sec = ((android.os.SystemClock.elapsedRealtime() - connectedSinceMs) / 1000).toInt()
            update { it.copy(durationSec = sec) }
        }
    }

    /** Tear down the call after a terminal phase lingers ~1.5s. */
    private fun finishTerminal() {
        // A local hangup arrives here as ENDING — collapse it to the terminal
        // ENDED so the UI shows the outcome card during the linger.
        if (_uiState.value.phase == CallPhase.ENDING) moveTo(CallPhase.ENDED)
        timeout.cancel()
        if (CallCapabilitiesProvider.current.supportsRecording) recorder.stop()
        // Release WebRTC audio FIRST, then restore the audio mode: switching the
        // mode/route under a live voice stream is what produced the "click" on
        // both ends. No end/busy tones — the 200-400 ms tone was cut by the
        // teardown anyway and only added a pop; the outcome card + haptic remain.
        engine.dispose()
        audio.hapticEnd()
        audio.stop()
        iceConnected = false
        reconnect.cancel()
        stopService()
        // The incoming-call notification is posted directly (before the FGS
        // runs) — cancel it explicitly so a rejected/missed ring doesn't
        // leave a stale "Incoming call" card.
        notifier.cancel()
        // Freeze the session's endedAt + metrics snapshot for history.
        currentSession = currentSession?.copy(
            endedAt = System.currentTimeMillis(),
            metrics = metrics.snapshot.value,
        )
        // Record the call in the local history journal (the "Calls" tab).
        recordHistory()
        // Emit a typed CallError for the terminal phase (UI shows a toast).
        _lastError.value = when (_uiState.value.phase) {
            CallPhase.BUSY -> com.alphamessenger.calls.CallError.PeerBusy
            CallPhase.REJECTED -> com.alphamessenger.calls.CallError.PeerRejected
            CallPhase.TIMEOUT -> com.alphamessenger.calls.CallError.Timeout
            CallPhase.FAILED -> com.alphamessenger.calls.CallError.IceFailed
            else -> null
        }
        // A call we made, or one that was answered, stays a moment longer:
        // the screen offers "Message" and "Call back".
        val s = _uiState.value
        val linger = if (s.outgoing || s.durationSec > 0) FOLLOW_UP_LINGER_MS else 1500L
        timeout.startTerminalLinger(scope, linger) { collapseToIdle() }
    }

    /** The terminal card is done: back to IDLE (the call screen closes). */
    private fun collapseToIdle() {
        currentCallId = ""
        currentChatId = ""
        connectedSinceMs = 0L
        currentSession = null
        metrics.reset()
        // Reset to IDLE but KEEP peerName/avatarPath so the terminal card
        // doesn't flash a "?" placeholder during the last frame.
        update { it.copy(phase = CallPhase.IDLE) }
    }

    // --- Service control ---

    private fun startService(video: Boolean) =
        notifier.startForegroundService(currentCallId, _uiState.value.peerName, video, _uiState.value.peerAvatarPath)
    private fun stopService() = notifier.stopForegroundService()

    /** Open the call Activity (works when the app is foregrounded or from a
     *  notification-action exemption window; silently blocked otherwise). */
    private fun launchCallUi() {
        runCatching {
            context.startActivity(
                android.content.Intent(context, com.alphamessenger.calls.ui.CallActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /** Check if the app process is in the foreground (importance == FOREGROUND).
     *  Used to decide: show the full-screen Activity directly (foreground) vs
     *  post a full-screen-intent notification (background). */
    private fun isAppForegrounded(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val processes = am.runningAppProcesses ?: return false
        return processes.any { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
    }

    /** A call that rang while we could not answer: history and a missed-call notification, once per call. */
    private suspend fun recordLateOffer(chatId: String, senderId: String, offer: CallSignal.Offer, ageMs: Long) {
        if (history.entries.value.any { it.callId == offer.callId }) return
        if (!repo.callAllowed(chatId, senderId, offer.media == CallType.VIDEO)) return
        val peer = runCatching { repo.resolvePeer(chatId) }.getOrNull()
        val at = System.currentTimeMillis() - ageMs
        val video = offer.media == CallType.VIDEO
        history.add(
            com.alphamessenger.calls.CallHistoryEntry(
                callId = offer.callId, chatId = chatId,
                peerName = peer?.displayName.orEmpty(), peerAvatarPath = peer?.avatarPath.orEmpty(),
                video = video, outgoing = false,
                outcome = com.alphamessenger.calls.CallOutcome.MISSED,
                atMs = at, durationSec = 0,
            ),
        )
        notifier.showMissedCall(chatId, peer?.displayName.orEmpty(), video, peer?.avatarPath.orEmpty(), at)
    }

    /** Derive the history outcome from the terminal phase + direction. */
    private fun recordHistory() {
        val s = _uiState.value
        val session = currentSession ?: return
        if (s.callId.isBlank()) return
        val outcome = when {
            session.wasConnected -> com.alphamessenger.calls.CallOutcome.ANSWERED
            s.phase == CallPhase.BUSY -> com.alphamessenger.calls.CallOutcome.BUSY
            s.phase == CallPhase.FAILED -> com.alphamessenger.calls.CallOutcome.FAILED
            s.phase == CallPhase.REJECTED -> com.alphamessenger.calls.CallOutcome.REJECTED
            s.outgoing && s.phase == CallPhase.TIMEOUT -> com.alphamessenger.calls.CallOutcome.NO_ANSWER
            s.outgoing -> com.alphamessenger.calls.CallOutcome.CANCELLED
            else -> com.alphamessenger.calls.CallOutcome.MISSED
        }
        history.add(
            com.alphamessenger.calls.CallHistoryEntry(
                callId = s.callId,
                chatId = s.chatId,
                peerName = s.peerName,
                peerAvatarPath = s.peerAvatarPath,
                video = s.type == CallType.VIDEO,
                outgoing = s.outgoing,
                outcome = outcome,
                atMs = session.startedAt,
                durationSec = session.durationSec.toInt(),
            ),
        )
        // Missed → a persistent notification with "Call back" / "Message".
        if (outcome == com.alphamessenger.calls.CallOutcome.MISSED) {
            notifier.showMissedCall(s.chatId, s.peerName, s.type == CallType.VIDEO, s.peerAvatarPath, System.currentTimeMillis())
        }
    }

    // --- Helpers ---

    companion object {
        /** How long the call-ended screen with "Message" / "Call back" stays. */
        const val FOLLOW_UP_LINGER_MS = 4000L
    }

    private fun update(transform: (CallUiState) -> CallUiState) {
        _uiState.value = transform(_uiState.value).copy(
            audioRoute = audio.route.value,
            bluetoothAvailable = audio.bluetoothAvailable.value,
        )
        syncProximity(_uiState.value)
    }

    /** Screen off at the ear: held while a voice call plays through the
     *  earpiece (we dialled, or answered). Speaker, headset, Bluetooth, a video
     *  call or a ringing phone keep the screen on. */
    private var proximityLock: android.os.PowerManager.WakeLock? = null

    @Synchronized
    private fun syncProximity(s: CallUiState) {
        val want = s.type == CallType.AUDIO && s.audioRoute == AudioRoute.EARPIECE &&
            s.phase.isActive && s.phase != CallPhase.INCOMING_RINGING
        val held = proximityLock?.isHeld == true
        if (want && !held) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximityLock = (proximityLock ?: pm.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "alpha:call-ear")
                .apply { setReferenceCounted(false) })
                .also { runCatching { it.acquire(4 * 60 * 60 * 1000L) } }
        } else if (!want && held) {
            // Wait until the phone leaves the ear before lighting the screen.
            runCatching { proximityLock?.release(android.os.PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
        }
    }
}
