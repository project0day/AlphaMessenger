package com.alphamessenger.calls.group

import android.content.Context
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

/** What the mesh engine needs sent to one member. */
sealed class MeshOut {
    abstract val member: String
    data class Offer(override val member: String, val sdp: String) : MeshOut()
    data class Answer(override val member: String, val sdp: String) : MeshOut()
    data class Ice(override val member: String, val candidate: IceCandidateDto) : MeshOut()
}

/** One remote member's side of the call, as the screen shows it. */
data class MeshPeerState(val connected: Boolean = false, val video: VideoTrack? = null)

/**
 * Group call media: one PeerConnection per member (a full mesh), all fed by
 * the same local microphone and camera. Knows nothing of signaling or the
 * room; it takes SDP / ICE per member and emits what to send to whom.
 * The newcomer offers to everyone already inside; the others only answer,
 * so two members never offer to each other at once.
 */
@Singleton
class GroupWebRtcEngine @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val networkPrivacy: com.alphamessenger.data.NetworkPrivacyPrefs,
) {
    private val _out = MutableSharedFlow<MeshOut>(extraBufferCapacity = 256)
    val out: SharedFlow<MeshOut> = _out.asSharedFlow()

    private val _peers = MutableStateFlow<Map<String, MeshPeerState>>(emptyMap())
    val peers: StateFlow<Map<String, MeshPeerState>> = _peers.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()

    private class Peer(val pc: PeerConnection) {
        val pendingIce = mutableListOf<IceCandidateDto>()
        var remoteSet = false
    }

    private val conns = java.util.concurrent.ConcurrentHashMap<String, Peer>()
    private val early = java.util.concurrent.ConcurrentHashMap<String, MutableList<IceCandidateDto>>()
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturing = false
    private var frontCamera = true
    private val cameraThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "GroupCallCamera") }

    val eglContext: EglBase.Context? get() = eglBase?.eglBaseContext

    /** Open the local microphone (and camera when [video]). Idempotent. */
    fun start(video: Boolean) {
        if (factory != null) return
        eglBase = EglBase.create()
        val egl = eglBase!!.eglBaseContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).setEnableInternalTracer(false).createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
            .createPeerConnectionFactory()
        val cfg = com.alphamessenger.calls.audio.AudioProcessingConfigProvider.current
        val ac = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", cfg.noiseSuppression.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", cfg.echoCancellation.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", cfg.automaticGainControl.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", cfg.highPassFilter.toString()))
        }
        audioSource = factory!!.createAudioSource(ac)
        audioTrack = factory!!.createAudioTrack("g-audio", audioSource)
        // The video track exists from the start (camera closed until enabled):
        // turning video on later needs no renegotiation with anyone.
        videoSource = factory!!.createVideoSource(false)
        videoTrack = factory!!.createVideoTrack("g-video", videoSource).also { it.setEnabled(false) }
        _localVideo.value = videoTrack
        if (video) setVideoEnabled(true)
    }

    private fun iceServers(): Pair<List<PeerConnection.IceServer>, Boolean> {
        val relayOnly = networkPrivacy.callsMustRelay
        val list = if (relayOnly) mutableListOf() else mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        if (networkPrivacy.turnUrl.isNotBlank()) {
            list += PeerConnection.IceServer.builder(networkPrivacy.turnUrl)
                .setUsername(networkPrivacy.turnUser).setPassword(networkPrivacy.turnPass).createIceServer()
        }
        return list to relayOnly
    }

    private fun peerFor(member: String): Peer {
        conns[member]?.let { return it }
        val (servers, relayOnly) = iceServers()
        val rtc = PeerConnection.RTCConfiguration(servers).apply {
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory!!.createPeerConnection(rtc, object : PeerConnection.Observer {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                val up = state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED
                update(member) { it.copy(connected = up) }
            }
            override fun onIceCandidate(c: IceCandidate?) {
                c ?: return
                _out.tryEmit(MeshOut.Ice(member, IceCandidateDto(c.sdpMid, c.sdpMLineIndex, c.sdp)))
            }
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                val t = receiver?.track() as? VideoTrack ?: return
                update(member) { it.copy(video = t) }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(s: org.webrtc.MediaStream?) {}
            override fun onDataChannel(d: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: error("no PeerConnection")
        audioTrack?.let { pc.addTrack(it, listOf("g")) }
        videoTrack?.let { pc.addTrack(it, listOf("g")) }
        val p = Peer(pc)
        early.remove(member)?.let { p.pendingIce += it }
        conns[member] = p
        update(member) { it }
        return p
    }

    private fun update(member: String, f: (MeshPeerState) -> MeshPeerState) {
        val cur = _peers.value
        _peers.value = cur + (member to f(cur[member] ?: MeshPeerState()))
    }

    private abstract class Sdp : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    /** We just joined: offer to [member], who was already inside. */
    fun connectTo(member: String) {
        if (factory == null || conns.containsKey(member)) return
        val p = peerFor(member)
        p.pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                val sdp = p0 ?: return
                p.pc.setLocalDescription(object : Sdp() {}, sdp)
                _out.tryEmit(MeshOut.Offer(member, sdp.description))
            }
        }, MediaConstraints())
    }

    /** [member] joined after us and offers: answer. */
    fun onOffer(member: String, sdp: String) {
        if (factory == null) return
        val p = peerFor(member)
        p.pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                p.remoteSet = true
                flush(p)
                p.pc.createAnswer(object : Sdp() {
                    override fun onCreateSuccess(p0: SessionDescription?) {
                        val ans = p0 ?: return
                        p.pc.setLocalDescription(object : Sdp() {}, ans)
                        _out.tryEmit(MeshOut.Answer(member, ans.description))
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun onAnswer(member: String, sdp: String) {
        val p = conns[member] ?: return
        p.pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() { p.remoteSet = true; flush(p) }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun onIce(member: String, c: IceCandidateDto) {
        val p = conns[member]
        if (p == null) {
            // Ahead of the offer (relays don't keep order): kept for the connection.
            early.getOrPut(member) { mutableListOf() } += c
            return
        }
        if (!p.remoteSet) {
            // Before the offer/answer: kept until the description is set.
            p.pendingIce += c
            return
        }
        runCatching { p.pc.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)) }
    }

    private fun flush(p: Peer) {
        val q = p.pendingIce.toList(); p.pendingIce.clear()
        q.forEach { c -> runCatching { p.pc.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)) } }
    }

    /** [member] left the call: close their connection. */
    fun drop(member: String) {
        val p = conns.remove(member) ?: return
        runCatching { p.pc.close() }
        runCatching { p.pc.dispose() }
        _peers.value = _peers.value - member
    }

    fun setMicEnabled(on: Boolean) { audioTrack?.setEnabled(on) }

    /** Sound levels now (0..1): each member's incoming audio, ours under "". */
    fun levels(onResult: (Map<String, Double>) -> Unit) {
        val out = java.util.concurrent.ConcurrentHashMap<String, Double>()
        val all = conns.entries.toList()
        if (all.isEmpty()) { onResult(emptyMap()); return }
        val left = java.util.concurrent.atomic.AtomicInteger(all.size)
        all.forEach { (member, p) ->
            runCatching {
                p.pc.getStats { report ->
                    for (s in report.statsMap.values) {
                        val kind = s.members["kind"]
                        val level = (s.members["audioLevel"] as? Number)?.toDouble() ?: continue
                        if (kind != "audio") continue
                        when (s.type) {
                            "inbound-rtp" -> out[member] = level
                            "media-source" -> out[""] = maxOf(out[""] ?: 0.0, level)
                        }
                    }
                    if (left.decrementAndGet() == 0) onResult(out)
                }
            }.onFailure { if (left.decrementAndGet() == 0) onResult(out) }
        }
    }

    /** Fewer pixels when many tiles share the uplink. */
    fun setLowQuality(low: Boolean) {
        val cap = capturer as? org.webrtc.CameraVideoCapturer ?: return
        runCatching { if (low) cap.changeCaptureFormat(480, 360, 15) else cap.changeCaptureFormat(640, 480, 24) }
    }

    fun setVideoEnabled(on: Boolean) {
        if (on) startCamera() else stopCamera()
    }

    private fun startCamera() {
        if (capturing || videoSource == null) return
        runCatching {
            val en = Camera2Enumerator(context)
            val name = en.deviceNames.firstOrNull { if (frontCamera) en.isFrontFacing(it) else en.isBackFacing(it) }
                ?: en.deviceNames.firstOrNull() ?: return
            val cap = en.createCapturer(name, null)
            val helper = SurfaceTextureHelper.create("GroupCapture", eglBase!!.eglBaseContext)
            cap.initialize(helper, context, videoSource!!.capturerObserver)
            // Many tiles share the uplink: a modest frame keeps the mesh smooth.
            cap.startCapture(640, 480, 24)
            capturer = cap; surfaceHelper = helper; capturing = true
            videoTrack?.setEnabled(true)
        }
    }

    private fun stopCamera() {
        if (!capturing) return
        capturing = false
        val cap = capturer; val helper = surfaceHelper
        capturer = null; surfaceHelper = null
        runCatching { videoTrack?.setEnabled(false) }
        cameraThread.execute {
            runCatching { cap?.stopCapture() }; runCatching { cap?.dispose() }; runCatching { helper?.dispose() }
        }
    }

    fun switchCamera() {
        val cap = capturer as? org.webrtc.CameraVideoCapturer ?: return
        runCatching {
            cap.switchCamera(object : org.webrtc.CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) { frontCamera = isFront }
                override fun onCameraSwitchError(e: String?) {}
            })
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private val initialized = java.util.WeakHashMap<SurfaceViewRenderer, Boolean>()
    private val attached = java.util.WeakHashMap<SurfaceViewRenderer, VideoTrack>()

    /** Show [track] in [r] (null = nothing). Safe to call on every recomposition. */
    fun bind(r: SurfaceViewRenderer, track: VideoTrack?, mirror: Boolean) {
        val egl = eglBase?.eglBaseContext ?: return
        runCatching {
            if (initialized[r] != true) { r.init(egl, null); initialized[r] = true }
            r.setMirror(mirror)
            r.setEnableHardwareScaler(true)
            val old = attached[r]
            if (old !== track) {
                old?.let { runCatching { it.removeSink(r) } }
                track?.addSink(r)
                if (track != null) attached[r] = track else attached.remove(r)
            }
        }
    }

    fun unbind(r: SurfaceViewRenderer) {
        runCatching { attached.remove(r)?.removeSink(r) }
        initialized.remove(r)
        runCatching { r.release() }
    }

    /** Leave: every connection, the camera and the microphone go. */
    fun dispose() {
        conns.keys.toList().forEach { drop(it) }
        early.clear()
        _peers.value = emptyMap()
        _localVideo.value = null
        attached.clear(); initialized.clear()
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }; capturer = null
        runCatching { surfaceHelper?.dispose() }; surfaceHelper = null
        capturing = false
        runCatching { videoTrack?.dispose() }; videoTrack = null
        runCatching { audioTrack?.dispose() }; audioTrack = null
        runCatching { videoSource?.dispose() }; videoSource = null
        runCatching { audioSource?.dispose() }; audioSource = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { eglBase?.release() }; eglBase = null
        frontCamera = true
    }
}
