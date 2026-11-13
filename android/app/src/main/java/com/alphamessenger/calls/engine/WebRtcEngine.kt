package com.alphamessenger.calls.engine

import android.content.Context
import com.alphamessenger.calls.CallType
import com.alphamessenger.calls.IceCandidateDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Inject
import javax.inject.Singleton

/** Events emitted by the WebRTC engine for the call manager to react to. */
sealed class WebRtcEvent {
    /** The local SDP offer is ready (caller). */
    data class OfferCreated(val sdp: String) : WebRtcEvent()
    /** The local SDP answer is ready (callee). */
    data class AnswerCreated(val sdp: String) : WebRtcEvent()
    /** A trickle-ICE candidate was generated locally (to be signaled). */
    data class IceCandidate(val candidate: IceCandidateDto) : WebRtcEvent()
    /** ICE connection state changed. */
    data class IceStateChanged(val connected: Boolean) : WebRtcEvent()
    /** The remote video track arrived (or null when removed/ended). */
    data class RemoteVideoTrack(val track: VideoTrack?) : WebRtcEvent()
    /** A fatal WebRTC failure. */
    data class Failed(val reason: String) : WebRtcEvent()
}

/**
 * THE WebRTC engine — one [PeerConnection], local A/V tracks, ICE.
 *
 * ## Lifecycle
 *  - [startCall] (caller): init factory + tracks, create an offer.
 *  - [acceptCall] (callee): init factory + tracks, set the remote offer,
 *    create an answer.
 *  - [setRemoteSdp] / [addRemoteIce]: feed remote signaling in.
 *  - [restartIce]: ICE restart on network blip.
 *  - [dispose]: tear everything down.
 *
 * ## Threading
 *  All WebRTC calls happen on the WebRTC thread (PeerConnectionFactory
 *  internally serializes). The engine is safe to call from any thread —
 *  SDP observers marshal back via the event flow.
 *
 * ## Decoupled
 *  Knows NOTHING about signaling, the state machine, or UI. Just emits
 *  [WebRtcEvent]s and accepts SDP/ICE inputs.
 */
@Singleton
class WebRtcEngine @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val networkPrivacy: com.alphamessenger.data.NetworkPrivacyPrefs,
) : CallRendererBridge {
    private val _events = MutableSharedFlow<WebRtcEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WebRtcEvent> = _events.asSharedFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    /** Renderers currently attached as sinks — guards against double-addSink
     *  (which throws) and ensures we removeSink on dispose. WeakHashMap keys
     *  so GC'd renderers don't leak. */
    private val rendererSinks = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<SurfaceViewRenderer, Boolean>())
    )

    // Lazy-initialized WebRTC state.
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var isVideo = false
    private var frontCamera = true
    private var videoEnabled = true
    private var micEnabled = true
    private var disposed = false
    /** Camera hardware actually running (startCapture called, not stopped). The
     *  video TRACK exists for the whole call; the camera only opens on the first
     *  [setVideoEnabled] true. */
    private var capturing = false
    /** A camera front↔back switch is in flight. Firing another switch before the
     *  previous completes wedges the camera HAL on some devices (needs a reboot
     *  to recover) — so we drop overlapping switch requests. */
    private var switchingCamera = false
    /** Serializes the blocking camera stop/dispose off the main thread. */
    private val cameraThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CallCameraRelease")
    }

    // ── Initialization ────────────────────────────────────────────────────

    private fun initFactory() {
        if (factory != null) return
        disposed = false
        eglBase = EglBase.create()
        val eglCtx = eglBase!!.eglBaseContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglCtx, true, true)
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglCtx)
            )
            .createPeerConnectionFactory()
    }

    private fun createPeer(): PeerConnection {
        // STUN (always) + TURN (if configured). TURN is read from BuildConfig
        // fields ALPHA_TURN_URL / ALPHA_TURN_USER / ALPHA_TURN_PASS — these are
        // empty by default (no TURN), but adding TURN later is a build-config
        // change ONLY, no code/CallManager rewrite. Architecture-ready.
        // Relay-only (privacy setting): no STUN, no host/srflx candidates —
        // media flows through TURN only, so the peer never sees our IP.
        val relayOnly = networkPrivacy.callsMustRelay
        val iceServers = if (relayOnly) mutableListOf() else mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
        // Optional TURN — populated from build config or a runtime config source.
        // Kept here (in the engine, NOT in CallManager) per the auditor's rule:
        // the manager must not know about ICE servers.
        val turnUrl = networkPrivacy.turnUrl.ifBlank { readTurnUrl() }
        val turnUser = if (networkPrivacy.turnUrl.isNotBlank()) networkPrivacy.turnUser else readTurnUser()
        val turnPass = if (networkPrivacy.turnUrl.isNotBlank()) networkPrivacy.turnPass else readTurnPass()
        if (turnUrl.isNotBlank()) {
            iceServers += PeerConnection.IceServer.builder(turnUrl)
                .setUsername(turnUser)
                .setPassword(turnPass)
                .createIceServer()
        }
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY
                else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }
        return factory!!.createPeerConnection(cfg, constraints, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                val connected = state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                _events.tryEmit(WebRtcEvent.IceStateChanged(connected))
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    _events.tryEmit(WebRtcEvent.Failed("ICE failed"))
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                _events.tryEmit(WebRtcEvent.IceCandidate(
                    IceCandidateDto(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                ))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track() ?: return
                if (track is VideoTrack) {
                    android.util.Log.i("CallFlow", "remote video track arrived")
                    _remoteVideoTrack.value = track
                    // A screen that bound its remote view before the track
                    // existed gets the picture now (its update() won't rerun).
                    remoteRenderer?.let { r ->
                        runCatching {
                            if (!rendererSinks.contains(r)) { track.addSink(r); rendererSinks.add(r) }
                        }
                    }
                    _events.tryEmit(WebRtcEvent.RemoteVideoTrack(track))
                }
            }
        }) ?: error("Failed to create PeerConnection")
    }

    private fun createAudioTrack() {
        // Apply the AudioProcessingConfig (NS/AEC/AGC/HPF) as audio-source
        // constraints. All ON by default — sensible for voice calls.
        val cfg = com.alphamessenger.calls.audio.AudioProcessingConfigProvider.current
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", cfg.noiseSuppression.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", cfg.echoCancellation.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", cfg.automaticGainControl.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", cfg.highPassFilter.toString()))
        }
        audioSource = factory!!.createAudioSource(constraints)
        audioTrack = factory!!.createAudioTrack("audio-local", audioSource)
        audioTrack?.setEnabled(micEnabled)
        peer?.addTrack(audioTrack)
    }

    private val cameraEvents = object : org.webrtc.CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(err: String?) { android.util.Log.e("CamEvt", "onCameraError: $err") }
        override fun onCameraDisconnected() { android.util.Log.w("CamEvt", "onCameraDisconnected") }
        override fun onCameraFreezed(err: String?) { android.util.Log.e("CamEvt", "onCameraFreezed: $err") }
        override fun onCameraOpening(name: String?) { android.util.Log.i("CamEvt", "onCameraOpening: $name") }
        override fun onFirstFrameAvailable() { android.util.Log.i("CamEvt", "onFirstFrameAvailable") }
        override fun onCameraClosed() { android.util.Log.i("CamEvt", "onCameraClosed") }
    }

    /** Create the video SOURCE + TRACK (NOT the camera) and add the track to the
     *  peer connection UP FRONT — so audio+video are negotiated ONCE at call
     *  start and switching to video later needs no renegotiation (that mid-call
     *  reconfigure is what aborted the encoder). The CAMERA itself is opened
     *  lazily by [startCamera] only when video is actually enabled, and fully
 * released by [stopCamera] when it's turned off — as messengers usually do. So an
     *  AUDIO call never touches the camera HAL, and the camera is freed the
     *  moment video stops (no leaked handle → no ERROR_MAX_CAMERAS_IN_USE). */
    private fun createVideoTrack(startCapturing: Boolean) {
        if (videoTrack == null) {
            runCatching {
                videoSource = factory!!.createVideoSource(false)
                videoTrack = factory!!.createVideoTrack("video-local", videoSource)
                videoTrack!!.setEnabled(false)
                _localVideoTrack.value = videoTrack
                peer?.addTrack(videoTrack)
            }
        }
        if (startCapturing) startCamera()
    }

    /** Open the camera and attach it to the existing video source. Lazy: the
     *  capturer + SurfaceTextureHelper are created here, NOT at call start.
     *  Idempotent. */
    private fun startCamera() {
        if (capturing || videoSource == null) return
        runCatching {
            val enumerator: CameraEnumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames
            val target = if (frontCamera) {
                deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: deviceNames.firstOrNull()
            } else {
                deviceNames.firstOrNull { enumerator.isBackFacing(it) } ?: deviceNames.firstOrNull()
            } ?: return
            val cap = enumerator.createCapturer(target, cameraEvents)
            val helper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
            cap.initialize(helper, context, videoSource!!.capturerObserver)
            cap.startCapture(1280, 720, 30)
            videoCapturer = cap
            surfaceHelper = helper
            capturing = true
            videoTrack?.setEnabled(true)
        }
    }

    /** Fully RELEASE the camera (stop + dispose capturer + surface helper) so the
     *  HAL frees the device — critical on fragile HALs that otherwise leak the
     *  handle (ERROR_MAX_CAMERAS_IN_USE). stopCapture BLOCKS up to seconds, so it
     *  runs off the main thread. The video source/track stay (m-line unchanged),
     *  so re-enabling just re-opens the camera. */
    private fun stopCamera() {
        if (!capturing) return
        capturing = false
        val cap = videoCapturer; val helper = surfaceHelper
        videoCapturer = null; surfaceHelper = null
        runCatching { videoTrack?.setEnabled(false) }
        cameraThread.execute {
            runCatching { cap?.stopCapture() }
            runCatching { cap?.dispose() }
            runCatching { helper?.dispose() }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Caller: init + create an offer. */
    fun startCall(type: CallType) {
        initFactory()
        isVideo = type == CallType.VIDEO
        peer = createPeer()
        createAudioTrack()
        // Negotiate video UP FRONT even for an audio call (camera closed until
        // enabled) → switching to video later needs no renegotiation.
        createVideoTrack(startCapturing = isVideo)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("iceRestart", "false"))
        }
        peer!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                peer!!.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
                _events.tryEmit(WebRtcEvent.OfferCreated(sdp.description))
            }
            override fun onCreateFailure(error: String?) { _events.tryEmit(WebRtcEvent.Failed("offer: $error")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /** Callee: init + set the remote offer + create an answer. */
    fun acceptCall(type: CallType, remoteOfferSdp: String) {
        initFactory()
        isVideo = type == CallType.VIDEO
        peer = createPeer()
        createAudioTrack()
        // Negotiate video up front here too (see startCall).
        createVideoTrack(startCapturing = isVideo)
        peer!!.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSdpSet = true
                flushPendingIce()
                peer!!.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp ?: return
                        peer!!.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, sdp)
                        _events.tryEmit(WebRtcEvent.AnswerCreated(sdp.description))
                    }
                    override fun onCreateFailure(error: String?) { _events.tryEmit(WebRtcEvent.Failed("answer: $error")) }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) { _events.tryEmit(WebRtcEvent.Failed("set remote offer: $error")) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp))
    }

    /** Caller: feed the callee's answer SDP in. */
    fun setRemoteAnswer(sdp: String) {
        peer?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSdpSet = true
                flushPendingIce()
            }
            override fun onSetFailure(error: String?) { _events.tryEmit(WebRtcEvent.Failed("set remote answer: $error")) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /** Callee (on ICE restart OR audio→video upgrade): feed the caller's
     *  re-offer, then re-answer. GUARDED against wrong-state calls: if the peer
     *  connection is not STABLE (e.g. we have a local offer in flight), the
     *  setRemoteDescription(OFFER) would fail with "Called in wrong state:
     *  have-local-offer" → call dies. We queue the offer and apply it once the
     *  in-flight offer is resolved (answer received or rolled back). */
    fun setRemoteOfferAndAnswer(sdp: String) {
        val p = peer
        if (p == null) {
            _events.tryEmit(WebRtcEvent.Failed("setRemoteOfferAndAnswer: no peer"))
            return
        }
        // Guard: only apply a remote OFFER when stable. If we have a local offer
        // in flight (glare / racing renegotiation), queue this offer and apply
        // it after the rollback.
        val state = p.signalingState()
        if (state != org.webrtc.PeerConnection.SignalingState.STABLE) {
            android.util.Log.w("WebRtcEngine", "setRemoteOfferAndAnswer queued — signalingState=$state")
            pendingRemoteOffer = sdp
            return
        }
        applyRemoteOfferAndAnswer(p, sdp)
    }

    /** The actual offer→answer exchange, called once signalingState == STABLE. */
    private fun applyRemoteOfferAndAnswer(p: PeerConnection, sdp: String) {
        p.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSdpSet = true
                flushPendingIce()
                p.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(ans: SessionDescription?) {
                        ans ?: return
                        p.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                // If a remote offer was queued during our
                                // have-local-offer window, apply it now.
                                pendingRemoteOffer?.let { queued ->
                                    pendingRemoteOffer = null
                                    applyRemoteOfferAndAnswer(p, queued)
                                }
                            }
                            override fun onSetFailure(e: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, ans)
                        _events.tryEmit(WebRtcEvent.AnswerCreated(ans.description))
                    }
                    override fun onCreateFailure(e: String?) { _events.tryEmit(WebRtcEvent.Failed("restart answer: $e")) }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(e: String?) {}
                }, MediaConstraints())
            }
            override fun onSetFailure(e: String?) { _events.tryEmit(WebRtcEvent.Failed("restart set: $e")) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    /** A remote offer that arrived while we had a local offer in flight (glare).
     *  Applied once our local offer is rolled back or answered. */
    private var pendingRemoteOffer: String? = null

    /** Feed a remote ICE candidate in. Queued until the peer connection exists
     *  AND the remote SDP is set — candidates arriving before that (e.g. while
     *  the callee is still ringing and hasn't accepted) would be silently
     *  dropped by WebRTC, breaking connectivity. THE connectivity fix. */
    private val pendingIce = mutableListOf<IceCandidateDto>()
    private var remoteSdpSet = false

    fun addRemoteIce(c: IceCandidateDto) {
        val p = peer
        if (p == null || !remoteSdpSet) {
            // Queue — will be flushed once the peer + remote description are ready.
            pendingIce += c
            return
        }
        runCatching { p.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)) }
    }

    /** Flush the pending ICE queue after the remote SDP is set. */
    private fun flushPendingIce() {
        val p = peer ?: return
        if (!remoteSdpSet) return
        val queued = pendingIce.toList()
        pendingIce.clear()
        queued.forEach { c ->
            runCatching { p.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)) }
        }
    }

    /** Toggle the local mic. */
    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        audioTrack?.setEnabled(enabled)
    }

    /** Turn the local camera ON/OFF. Video is ALREADY negotiated (track added at
     *  call start), so this just opens the camera on first enable + flips the
     *  track. NO renegotiation (that crashed the encoder). We do NOT stopCapture
     *  on disable — keeping the camera session alive avoids the HAL getting
     *  wedged by rapid start/stop cycles; the disabled track simply sends no
     *  frames and the UI hides our preview. */
    fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        if (enabled) {
            if (videoTrack == null) createVideoTrack(startCapturing = true) else startCamera()
        } else {
            stopCamera()
        }
    }

    private var lastSwitchMs = 0L

    /** Switch front ↔ back camera in place (no track/PeerConnection recreation).
     *  GUARDED three ways because rapid camera re-acquire crashes the vendor
     *  camera HAL on some (custom-ROM) devices — the QCOM camera provider
     *  SIGABRTs in CSLAcquireDevice, killing the camera app-wide until reboot:
     *    1. drop overlapping switches (previous not done),
     *    2. 1.5s cooldown between switches (no rapid toggling),
     *    3. only when actually capturing. */
    fun switchCamera() {
        val cap = videoCapturer as? org.webrtc.CameraVideoCapturer ?: return
        if (!capturing || switchingCamera) return
        val now = System.currentTimeMillis()
        if (now - lastSwitchMs < 1500) return
        lastSwitchMs = now
        switchingCamera = true
        runCatching {
            cap.switchCamera(object : org.webrtc.CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    frontCamera = isFront
                    switchingCamera = false
                }
                override fun onCameraSwitchError(error: String?) {
                    android.util.Log.w("WebRtcEngine", "switchCamera error: $error")
                    switchingCamera = false
                }
            })
        }.onFailure { switchingCamera = false }
    }

    /** ICE restart — caller creates a re-offer with iceRestart=true. GUARDED:
     *  only when signalingState == STABLE (have-local-offer would throw
     *  "Called in wrong state"). If a renegotiation offer is in flight, the
     *  restart is skipped — the reconnect watcher will retry. */
    fun restartIce() {
        val p = peer ?: return
        val state = p.signalingState()
        if (state != org.webrtc.PeerConnection.SignalingState.STABLE) {
            android.util.Log.w("WebRtcEngine", "restartIce skipped — signalingState=$state")
            return
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("iceRestart", "true"))
        }
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                p.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {}
                    override fun onSetFailure(e: String?) {
                        _events.tryEmit(WebRtcEvent.Failed("restart setLocal failed: $e"))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
                _events.tryEmit(WebRtcEvent.OfferCreated(sdp.description))
            }
            override fun onCreateFailure(e: String?) { _events.tryEmit(WebRtcEvent.Failed("restart offer: $e")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, constraints)
    }

    /** Tear down everything. Safe to call multiple times. Every field is
     *  nulled so the NEXT call re-initializes cleanly (the engine is a
     *  singleton — it must be fully reusable after dispose). */
    fun dispose() {
        if (disposed) return
        disposed = true
        // Detach the UI first so no renderer touches a dying track.
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        remoteSdpSet = false
        pendingIce.clear()
        // Clear renderer tracking so the next call's bindLocal starts fresh.
        rendererSinks.clear()
        initRenderers.clear()
        remoteRenderer = null
        pendingRemoteOffer = null
        runCatching { videoCapturer?.stopCapture() }
        runCatching { peer?.close() }
        runCatching { videoCapturer?.dispose() }; videoCapturer = null
        runCatching { surfaceHelper?.dispose() }; surfaceHelper = null
        runCatching { videoTrack?.dispose() }; videoTrack = null
        runCatching { audioTrack?.dispose() }; audioTrack = null
        runCatching { videoSource?.dispose() }; videoSource = null
        runCatching { audioSource?.dispose() }; audioSource = null
        runCatching { peer?.dispose() }; peer = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { eglBase?.release() }; eglBase = null
        micEnabled = true
        videoEnabled = true
        frontCamera = true
        isVideo = false
        capturing = false
        switchingCamera = false
        disposed = false  // ← allow re-init for the next call
    }

    /** The EGL base context — kept for the renderer bridge implementation. */
    val eglBaseContext: EglBase.Context? get() = eglBase?.eglBaseContext

    // ── CallRendererBridge implementation (UI uses ONLY these) ────────────

    override val eglContext: Any?
        get() = eglBase?.eglBaseContext

    /** Tracks which renderers have already been init'd — `SurfaceViewRenderer.init()`
     *  is NOT idempotent (calling it twice throws an IllegalStateException in
     *  stream-webrtc-android → swallowed by runCatching → addSink skipped → broken
     *  preview + eventual native EGL crash). We must init EXACTLY ONCE per
     *  renderer instance. The Compose `AndroidView.update` block fires on every
     *  recomposition (e.g. the duration ticker every second), so without this
     *  guard init() would be called repeatedly. WeakHashMap so disposed
     *  renderers don't leak. */
    private val initRenderers = java.util.WeakHashMap<SurfaceViewRenderer, Boolean>()

    override fun bindLocal(renderer: SurfaceViewRenderer) {
        val ctx = eglBase?.eglBaseContext ?: return
        runCatching {
            // Init ONLY on first bind for this renderer instance.
            if (initRenderers[renderer] != true) {
                renderer.init(ctx, null)
                initRenderers[renderer] = true
            }
            renderer.setMirror(true)
            renderer.setEnableHardwareScaler(true)
            // Guard double-add: addSink throws if the sink is already attached.
            val track = _localVideoTrack.value
            if (track != null && !rendererSinks.contains(renderer)) {
                track.addSink(renderer)
                rendererSinks.add(renderer)
            }
        }
    }

    /** The full-screen remote view on screen now (gets a late-arriving track). */
    @Volatile private var remoteRenderer: SurfaceViewRenderer? = null

    override fun bindRemote(renderer: SurfaceViewRenderer) {
        val ctx = eglBase?.eglBaseContext ?: return
        remoteRenderer = renderer
        runCatching {
            if (initRenderers[renderer] != true) {
                renderer.init(ctx, null)
                initRenderers[renderer] = true
            }
            renderer.setMirror(false)
            renderer.setEnableHardwareScaler(true)
            val track = _remoteVideoTrack.value
            if (track != null && !rendererSinks.contains(renderer)) {
                track.addSink(renderer)
                rendererSinks.add(renderer)
            }
        }
    }

    override fun unbind(renderer: SurfaceViewRenderer) {
        if (remoteRenderer === renderer) remoteRenderer = null
        runCatching {
            _localVideoTrack.value?.removeSink(renderer)
            _remoteVideoTrack.value?.removeSink(renderer)
            rendererSinks.remove(renderer)
            initRenderers.remove(renderer)
            renderer.release()
        }
    }

    // ── TURN config (read from build config or env; empty = no TURN) ──────
    //  Override these via BuildConfig fields or a runtime config source when
    //  deploying TURN servers. Kept in the engine (not CallManager) so the
    //  orchestrator stays ICE-agnostic.

    private fun readTurnUrl(): String =
        readBuildConfig("ALPHA_TURN_URL")

    private fun readTurnUser(): String =
        readBuildConfig("ALPHA_TURN_USER")

    private fun readTurnPass(): String =
        readBuildConfig("ALPHA_TURN_PASS")

    /** Read a BuildConfig field by reflection (empty if missing). Avoids a
     *  hard dependency on the generated BuildConfig class (which the lite
     *  backup doesn't ship). */
    private fun readBuildConfig(name: String): String = runCatching {
        val appId = context.packageName
        val cls = Class.forName("$appId.BuildConfig")
        val field = cls.getDeclaredField(name)
        field.get(null) as? String ?: ""
    }.getOrDefault("")
}
