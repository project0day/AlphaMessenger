package com.alphamessenger.calls

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Audio-only or video call. */
enum class CallType { AUDIO, VIDEO }

/**
 * THE call state machine phases (per spec).
 *
 * Terminal phases (ENDED/BUSY/REJECTED/TIMEOUT/FAILED) linger ~1.5s so the
 * UI can show the outcome, then collapse to IDLE.
 */
enum class CallPhase {
    IDLE,
    OUTGOING_CALLING,   // offer sent, waiting for the peer device to see it
    OUTGOING_RINGING,   // peer device acknowledged → "Ringing..."
    INCOMING_RINGING,   // we received an offer and are ringing
    CONNECTING,         // answer exchanged, ICE in progress
    CONNECTED,          // media flowing
    RECONNECTING,       // network blip — ICE restart in progress
    ENDING,             // local hangup sent, tearing down
    ENDED,              // normal end
    BUSY,               // peer is on another call
    REJECTED,           // peer declined
    TIMEOUT,            // nobody answered
    FAILED,             // transport/media failure
}

val CallPhase.isTerminal: Boolean
    get() = this == CallPhase.ENDED || this == CallPhase.BUSY || this == CallPhase.REJECTED ||
        this == CallPhase.TIMEOUT || this == CallPhase.FAILED

val CallPhase.isActive: Boolean
    get() = this != CallPhase.IDLE && !isTerminal

/** Available audio output routes. */
enum class AudioRoute { EARPIECE, SPEAKER, WIRED_HEADSET, BLUETOOTH }

/**
 * The ONE observable snapshot of the current call — everything the UI needs.
 */
data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String = "",
    val chatId: String = "",
    val peerName: String = "",
    val peerAvatarPath: String = "",
    val type: CallType = CallType.AUDIO,
    val outgoing: Boolean = false,
    /** Seconds since CONNECTED. */
    val durationSec: Int = 0,
    val muted: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.EARPIECE,
    val bluetoothAvailable: Boolean = false,
    /** Local camera streaming. */
    val videoEnabled: Boolean = false,
    val frontCamera: Boolean = true,
    /** Peer's video track arrived and is enabled. */
    val remoteVideoActive: Boolean = false,
    val localOnHold: Boolean = false,
    val remoteOnHold: Boolean = false,
    /** Peer asked to go video and we haven't answered → show the consent card. */
    val incomingVideoRequest: Boolean = false,
    /** We asked to go video and are waiting for the peer to accept/decline. */
    val awaitingVideoResponse: Boolean = false,
)

// ═══════════════════════════════════════════════════════════════════════
//  Signaling messages — ride the MLS-encrypted transient ChatState channel
//  (GiftWrap / NIP-59 underneath). Encoded as "call:" + base64(JSON).
// ═══════════════════════════════════════════════════════════════════════

sealed class CallSignal {
    abstract val callId: String

    /** New call (or ICE-restart re-offer when [restart] is true). */
    data class Offer(
        override val callId: String,
        val sdp: String,
        val media: CallType,
        val restart: Boolean = false,
    ) : CallSignal()

    data class Answer(override val callId: String, val sdp: String, val restart: Boolean = false) : CallSignal()

    /** Batched trickle-ICE candidates. */
    data class Ice(override val callId: String, val candidates: List<IceCandidateDto>) : CallSignal()

    /** Callee device saw the offer and started ringing. */
    data class Ringing(override val callId: String) : CallSignal()

    data class Reject(override val callId: String) : CallSignal()
    data class Busy(override val callId: String) : CallSignal()

    /** Caller cancelled before the callee answered. */
    data class Cancel(override val callId: String) : CallSignal()

    /** Either side hangs up an established call. */
    data class End(override val callId: String) : CallSignal()

    data class Hold(override val callId: String) : CallSignal()
    data class Resume(override val callId: String) : CallSignal()

    /** "My camera just turned on/off." Audio+video are negotiated up front at
     *  call start (video track present but idle), so switching to video needs NO
     *  renegotiation — each side just flips its camera and announces it here. */
    data class VideoState(override val callId: String, val on: Boolean) : CallSignal()

    /** "Not now" — recipient of a [VideoState] on=true declined; the sender
     *  turns its camera back off and the call stays audio. */
    data class VideoDecline(override val callId: String) : CallSignal()
}

data class IceCandidateDto(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)

object CallSignalCodec {

    const val PREFIX = "call:"

    fun encode(signal: CallSignal): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("id", signal.callId)
        when (signal) {
            is CallSignal.Offer -> {
                o.put("t", "offer")
                o.put("sdp", signal.sdp)
                o.put("media", if (signal.media == CallType.VIDEO) "video" else "audio")
                if (signal.restart) o.put("restart", true)
            }
            is CallSignal.Answer -> {
                o.put("t", "answer")
                o.put("sdp", signal.sdp)
                if (signal.restart) o.put("restart", true)
            }
            is CallSignal.Ice -> {
                o.put("t", "ice")
                val arr = JSONArray()
                signal.candidates.forEach { c ->
                    arr.put(JSONObject().apply {
                        put("m", c.sdpMid ?: JSONObject.NULL)
                        put("i", c.sdpMLineIndex)
                        put("c", c.candidate)
                    })
                }
                o.put("cands", arr)
            }
            is CallSignal.Ringing -> o.put("t", "ringing")
            is CallSignal.Reject -> o.put("t", "reject")
            is CallSignal.Busy -> o.put("t", "busy")
            is CallSignal.Cancel -> o.put("t", "cancel")
            is CallSignal.End -> o.put("t", "end")
            is CallSignal.Hold -> o.put("t", "hold")
            is CallSignal.Resume -> o.put("t", "resume")
            is CallSignal.VideoState -> { o.put("t", "vstate"); o.put("on", signal.on) }
            is CallSignal.VideoDecline -> o.put("t", "vdecline")
        }
        return PREFIX + Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** Returns null if [state] is not a call signal (e.g. plain typing). */
    fun decode(state: String): CallSignal? {
        if (!state.startsWith(PREFIX)) return null
        return runCatching {
            val json = String(Base64.decode(state.removePrefix(PREFIX), Base64.NO_WRAP), Charsets.UTF_8)
            val o = JSONObject(json)
            val id = o.getString("id")
            when (o.getString("t")) {
                "offer" -> CallSignal.Offer(
                    callId = id,
                    sdp = o.getString("sdp"),
                    media = if (o.optString("media") == "video") CallType.VIDEO else CallType.AUDIO,
                    restart = o.optBoolean("restart", false),
                )
                "answer" -> CallSignal.Answer(id, o.getString("sdp"), o.optBoolean("restart", false))
                "ice" -> {
                    val arr = o.getJSONArray("cands")
                    val cands = buildList {
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            add(IceCandidateDto(
                                sdpMid = if (c.isNull("m")) null else c.getString("m"),
                                sdpMLineIndex = c.getInt("i"),
                                candidate = c.getString("c"),
                            ))
                        }
                    }
                    CallSignal.Ice(id, cands)
                }
                "ringing" -> CallSignal.Ringing(id)
                "reject" -> CallSignal.Reject(id)
                "busy" -> CallSignal.Busy(id)
                "cancel" -> CallSignal.Cancel(id)
                "end" -> CallSignal.End(id)
                "hold" -> CallSignal.Hold(id)
                "resume" -> CallSignal.Resume(id)
                "vstate" -> CallSignal.VideoState(id, o.optBoolean("on", false))
                "vdecline" -> CallSignal.VideoDecline(id)
                else -> null
            }
        }.getOrNull()
    }
}
