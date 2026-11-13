package com.alphamessenger.calls.group

import android.content.Context
import android.content.Intent
import com.alphamessenger.calls.CallHistoryEntry
import com.alphamessenger.calls.CallHistoryStore
import com.alphamessenger.calls.CallOutcome
import com.alphamessenger.calls.IceCandidateDto
import com.alphamessenger.data.AlphaCoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Where we are in a group call. */
enum class GroupCallPhase { IDLE, RINGING, IN_CALL }

/** A member inside the call, as they announced themselves. */
data class GroupCallMember(val id: String, val mic: Boolean, val cam: Boolean)

data class GroupCallState(
    val phase: GroupCallPhase = GroupCallPhase.IDLE,
    val chatId: String = "",
    val callId: String = "",
    /** Our own member id in that chat. */
    val self: String = "",
    /** Who rang us (RINGING), and whether with video. */
    val ringFrom: String = "",
    val ringVideo: Boolean = false,
    val mic: Boolean = true,
    val cam: Boolean = false,
    val speaker: Boolean = false,
    val since: Long = 0L,
    /** We started it (for the history). */
    val outgoing: Boolean = false,
    /** Video was on at some point (for the history). */
    val hadVideo: Boolean = false,
    /** Everyone else inside, by member id. */
    val members: Map<String, GroupCallMember> = emptyMap(),
)

/** A call going on in a chat we are not in: for the "Join" banner. */
data class ActiveGroupCall(val callId: String, val count: Int, val video: Boolean)

/** Something to tell the person on the call screen. */
enum class GroupCallNotice { MUTED_BY_ADMIN, CALLS_OFF, ASKED_TO_UNMUTE }

/**
 * Group calls: a room in the group chat ("call") with a full mesh of WebRTC
 * connections between those inside.
 *
 * - Joining announces us in the room (repeated well inside the room TTL) and
 *   offers a connection to everyone already in it; later arrivals offer to us.
 * - SDP and ICE go to one member each as addressed chat signals:
 *   `gcall:<callId>:<offer|answer|ice>:<base64 json>`.
 * - Starting a call rings the group (`gcall:<callId>:ring:<1|0>`); "Invite"
 *   rings one member the same way, addressed. Our privacy and the group's
 *   call settings decide whether a ring reaches us; the rest see the banner.
 * - An admin may switch a member's microphone off (`gcall:<callId>:mute:1`).
 * - A member who leaves or goes silent drops out of the room, and their
 *   connection is closed. The call lasts while anyone is inside.
 */
@Singleton
class GroupCallManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val core: AlphaCoreRepository,
    private val engine: GroupWebRtcEngine,
    private val history: CallHistoryStore,
) {
    companion object {
        const val ROOM = "call"
        const val MAX_MEMBERS = 8
        private const val ANNOUNCE_EVERY_MS = 15_000L
        private const val RING_TIMEOUT_MS = 45_000L
        /** A level above this means the member is talking. */
        private const val TALKING = 0.04
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(GroupCallState())
    val state: StateFlow<GroupCallState> = _state.asStateFlow()

    /** Calls going on in our chats, by chat id (only those we are not in). */
    private val _active = MutableStateFlow<Map<String, ActiveGroupCall>>(emptyMap())
    val active: StateFlow<Map<String, ActiveGroupCall>> = _active.asStateFlow()

    /** Who is talking now (member ids; ours is [GroupCallState.self]). */
    private val _speaking = MutableStateFlow<Set<String>>(emptySet())
    val speaking: StateFlow<Set<String>> = _speaking.asStateFlow()

    private val _notices = MutableSharedFlow<GroupCallNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<GroupCallNotice> = _notices.asSharedFlow()

    /** Calls we said "Not now" to: their rings stay quiet (no ring spam). */
    private val quietCalls = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** Offers from members not (yet) seen in this call's room: answered only once
     *  they announce themselves, so nobody can listen in from outside the call. */
    private val heldOffers = java.util.concurrent.ConcurrentHashMap<String, String>()

    private var announcer: Job? = null
    private var meter: Job? = null
    private var ringTimeout: Job? = null

    init {
        // Off the main thread: the core is never called from it.
        scope.launch(Dispatchers.IO) { core.groupCallStates.collect { (chat, from, st) -> onState(chat, from, st) } }
        scope.launch { engine.out.collect { send(it) } }
    }

    // ── Our actions ───────────────────────────────────────────────────────

    /** Start a call in [chatId] and ring the group. [ringAgain]: ring twice more
     *  (a group made just now, whose members may not be in it yet). */
    fun start(chatId: String, video: Boolean, ringAgain: Boolean = false) {
        if (_state.value.phase == GroupCallPhase.IN_CALL) return
        scope.launch {
            val (calls, videoOk) = callSettings(chatId)
            if (!calls) { _notices.tryEmit(GroupCallNotice.CALLS_OFF); return@launch }
            val withVideo = video && videoOk
            val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            join(chatId, callId, withVideo, outgoing = true)
            val ring = "gcall:$callId:ring:${if (withVideo) 1 else 0}"
            core.sendChatStateDetached(chatId, ring)
            if (ringAgain) {
                for (wait in listOf(4_000L, 8_000L)) {
                    delay(wait)
                    if (_state.value.callId != callId) break
                    core.sendChatStateDetached(chatId, ring)
                }
            }
        }
    }

    /** Join the call going on in [chatId] (or the one ringing us). */
    fun join(chatId: String, callId: String, video: Boolean, outgoing: Boolean = false) {
        if (_state.value.phase == GroupCallPhase.IN_CALL) return
        // In the call at once (the screen shows it); the rest follows.
        _state.value = GroupCallState(
            phase = GroupCallPhase.IN_CALL, chatId = chatId, callId = callId,
            mic = true, cam = video, speaker = video, since = System.currentTimeMillis(),
            outgoing = outgoing, hadVideo = video,
        )
        scope.launch {
            val self = core.ownMemberId(chatId)
            engine.start(video)
            _state.value = _state.value.copy(self = self)
            routeAudio(video)
            startService(chatId, video)
            announce()
            // Everyone already inside gets our offer; later arrivals offer to us.
            val inside = core.roomMembers(chatId, ROOM).filterKeys { it != self }
                .mapNotNull { (id, js) -> parse(id, js)?.takeIf { it.second == callId }?.first }
            setMembers(inside.associateBy { it.id })
            inside.forEach { engine.connectTo(it.id) }
            announcer?.cancel()
            announcer = scope.launch {
                while (_state.value.phase == GroupCallPhase.IN_CALL) {
                    delay(ANNOUNCE_EVERY_MS)
                    announce()
                    prune()
                }
            }
            startMeter()
        }
    }

    /** "Not now": the call goes on for the others. */
    fun decline() {
        val s = _state.value
        if (s.phase != GroupCallPhase.RINGING) return
        ringTimeout?.cancel()
        _state.value = GroupCallState()
        cancelRingNotification()
        quietCalls += s.callId
        record(s, CallOutcome.REJECTED, 0)
    }

    fun accept(video: Boolean) {
        val s = _state.value
        if (s.phase != GroupCallPhase.RINGING) return
        ringTimeout?.cancel()
        cancelRingNotification()
        _state.value = GroupCallState()
        join(s.chatId, s.callId, video)
    }

    /** Leave: our room entry goes and every connection closes. */
    fun leave() {
        val s = _state.value
        if (s.phase != GroupCallPhase.IN_CALL) { decline(); _state.value = GroupCallState(); return }
        announcer?.cancel(); announcer = null
        meter?.cancel(); meter = null
        _speaking.value = emptySet()
        scope.launch { core.roomAnnounce(s.chatId, ROOM, "") }
        engine.dispose()
        heldOffers.clear()
        routeAudio(null)
        context.stopService(Intent(context, GroupCallService::class.java))
        _state.value = GroupCallState()
        record(s, CallOutcome.ANSWERED, ((System.currentTimeMillis() - s.since) / 1000).toInt())
        scope.launch { refreshActive(s.chatId) }
    }

    fun setMic(on: Boolean) {
        engine.setMicEnabled(on)
        _state.value = _state.value.copy(mic = on)
        scope.launch { announce() }
    }

    fun setCam(on: Boolean) {
        engine.setVideoEnabled(on)
        _state.value = _state.value.copy(cam = on, hadVideo = _state.value.hadVideo || on)
        if (on) engine.setLowQuality(_state.value.members.size + 1 > 3)
        scope.launch { announce() }
    }

    fun switchCamera() = engine.switchCamera()

    fun setSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speaker = on)
        routeAudio(on)
    }

    /** Ring one member of the group into the call. */
    fun invite(member: String) {
        val s = _state.value
        if (s.phase != GroupCallPhase.IN_CALL) return
        core.sendChatSignalDetached(s.chatId, member, "gcall:${s.callId}:ring:${if (s.cam) 1 else 0}")
    }

    /** An admin switches a member's microphone off (they can switch it back on). */
    fun muteMember(member: String) {
        val s = _state.value
        if (s.phase != GroupCallPhase.IN_CALL) return
        core.sendChatSignalDetached(s.chatId, member, "gcall:${s.callId}:mute:1")
    }

    /** An admin asks a member to switch their microphone back on (only they can). */
    fun askToUnmute(member: String) {
        val s = _state.value
        if (s.phase != GroupCallPhase.IN_CALL) return
        core.sendChatSignalDetached(s.chatId, member, "gcall:${s.callId}:askmic:1")
    }

    // ── Incoming ──────────────────────────────────────────────────────────

    private suspend fun onState(chatId: String, from: String, st: String) {
        if (st.startsWith("room:call:")) {
            onRoom(chatId, from, st.removePrefix("room:call:"))
            return
        }
        // gcall:<callId>:<kind>:<payload>
        val parts = st.removePrefix("gcall:").split(':', limit = 3)
        if (parts.size < 3) return
        val (callId, kind, payload) = parts
        val s = _state.value
        when (kind) {
            "ring" -> {
                if (s.phase != GroupCallPhase.IDLE || from == core.ownMemberId(chatId)) return
                if (callId in quietCalls) return
                val video = payload == "1"
                val (calls, videoOk) = callSettings(chatId)
                if (!calls) return
                // Our privacy decides who may ring us; the rest see the banner only.
                if (!core.callAllowed(chatId, from, video && videoOk)) return
                _state.value = GroupCallState(GroupCallPhase.RINGING, chatId, callId, ringFrom = from, ringVideo = video && videoOk)
                showRing(chatId)
                ringTimeout?.cancel()
                ringTimeout = scope.launch {
                    delay(RING_TIMEOUT_MS)
                    val now = _state.value
                    if (now.phase == GroupCallPhase.RINGING && now.callId == callId) {
                        _state.value = GroupCallState()
                        cancelRingNotification()
                        record(now, CallOutcome.MISSED, 0)
                    }
                }
            }
            "mute" -> {
                if (s.phase != GroupCallPhase.IN_CALL || s.chatId != chatId || s.callId != callId) return
                if (roleOf(chatId, from) !in setOf("owner", "admin")) return
                scope.launch { setMic(false) }
                _notices.tryEmit(GroupCallNotice.MUTED_BY_ADMIN)
            }
            "askmic" -> {
                if (s.phase != GroupCallPhase.IN_CALL || s.chatId != chatId || s.callId != callId || s.mic) return
                if (roleOf(chatId, from) !in setOf("owner", "admin")) return
                _notices.tryEmit(GroupCallNotice.ASKED_TO_UNMUTE)
            }
            "offer", "answer", "ice" -> {
                if (s.phase != GroupCallPhase.IN_CALL || s.chatId != chatId || s.callId != callId) return
                val body = runCatching { String(android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)) }.getOrNull() ?: return
                when (kind) {
                    "offer" -> if (inCall(chatId, from, callId)) engine.onOffer(from, body) else heldOffers[from] = body
                    "answer" -> engine.onAnswer(from, body)
                    else -> runCatching {
                        val o = JSONObject(body)
                        engine.onIce(from, IceCandidateDto(o.optString("mid").ifEmpty { null }, o.optInt("idx"), o.optString("c")))
                    }
                }
            }
        }
    }

    private suspend fun onRoom(chatId: String, from: String, body: String) {
        val s = _state.value
        if (s.phase == GroupCallPhase.IN_CALL && s.chatId == chatId && from != s.self) {
            val m = parse(from, body)
            if (m == null || m.second != s.callId) {
                // Left (or went to another call): their connection closes.
                engine.drop(from)
                setMembers(_state.value.members - from)
            } else {
                setMembers(_state.value.members + (from to m.first))
                heldOffers.remove(from)?.let { engine.onOffer(from, it) }
            }
        }
        // The ringing call ended before we answered: a missed call.
        if (s.phase == GroupCallPhase.RINGING && s.chatId == chatId && core.roomMembers(chatId, ROOM).isEmpty()) {
            ringTimeout?.cancel()
            _state.value = GroupCallState()
            cancelRingNotification()
            record(s, CallOutcome.MISSED, 0)
        }
        refreshActive(chatId)
    }

    /** The banner's view of a chat: a call is on while someone is in its room. */
    suspend fun refreshActive(chatId: String) {
        val inRoom = core.roomMembers(chatId, ROOM)
        val calls = inRoom.mapNotNull { (id, js) -> parse(id, js) }
        val mine = _state.value.phase == GroupCallPhase.IN_CALL && _state.value.chatId == chatId
        _active.value = if (calls.isEmpty() || mine) _active.value - chatId else {
            val callId = calls.groupingBy { it.second }.eachCount().maxByOrNull { it.value }!!.key
            val here = calls.filter { it.second == callId }
            _active.value + (chatId to ActiveGroupCall(callId, here.size, here.any { it.first.cam }))
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private fun setMembers(m: Map<String, GroupCallMember>) {
        _state.value = _state.value.copy(members = m)
        // Many tiles share the uplink: fewer pixels past three people.
        if (_state.value.cam) engine.setLowQuality(m.size + 1 > 3)
    }

    /** [member] announced themselves in this call (the room, not just the group). */
    private suspend fun inCall(chatId: String, member: String, callId: String): Boolean =
        member in _state.value.members ||
            core.roomMembers(chatId, ROOM)[member]?.let { parse(member, it)?.second == callId } == true

    /** The group's call settings: (calls on, video calls on). */
    private suspend fun callSettings(chatId: String): Pair<Boolean, Boolean> {
        val cfg = core.channelCfg(chatId)
        return cfg.calls to (cfg.calls && cfg.videoCalls)
    }

    private suspend fun roleOf(chatId: String, member: String): String = runCatching {
        val a = org.json.JSONArray(core.listGroupMembers(chatId))
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("sender_id") == member }?.optString("role").orEmpty()
    }.getOrDefault("")

    private fun parse(id: String, json: String): Pair<GroupCallMember, String>? = runCatching {
        if (json.isBlank()) return null
        val o = JSONObject(json)
        GroupCallMember(id, o.optBoolean("mic", true), o.optBoolean("cam", false)) to o.optString("id")
    }.getOrNull()

    private suspend fun announce() {
        val s = _state.value
        if (s.phase != GroupCallPhase.IN_CALL) return
        val body = JSONObject().put("id", s.callId).put("mic", s.mic).put("cam", s.cam).toString()
        core.roomAnnounce(s.chatId, ROOM, body)
    }

    /** Members who went silent (left without a word): close their connections. */
    private suspend fun prune() {
        val s = _state.value
        val here = core.roomMembers(s.chatId, ROOM).keys
        val gone = s.members.keys - here
        gone.forEach { engine.drop(it) }
        if (gone.isNotEmpty()) setMembers(_state.value.members - gone)
    }

    /** Who is talking: sound levels a few times a second. */
    private fun startMeter() {
        meter?.cancel()
        meter = scope.launch {
            while (_state.value.phase == GroupCallPhase.IN_CALL) {
                delay(600)
                engine.levels { lv ->
                    val s = _state.value
                    _speaking.value = lv.filterValues { it > TALKING }.keys
                        .map { if (it.isEmpty()) s.self else it }
                        .filter { it.isNotEmpty() && (it != s.self || s.mic) }
                        .toSet()
                }
            }
        }
    }

    private fun send(o: MeshOut) {
        val s = _state.value
        if (s.phase != GroupCallPhase.IN_CALL) return
        val (kind, body) = when (o) {
            is MeshOut.Offer -> "offer" to o.sdp
            is MeshOut.Answer -> "answer" to o.sdp
            is MeshOut.Ice -> "ice" to JSONObject().put("mid", o.candidate.sdpMid ?: "")
                .put("idx", o.candidate.sdpMLineIndex).put("c", o.candidate.candidate).toString()
        }
        val b64 = android.util.Base64.encodeToString(body.toByteArray(), android.util.Base64.NO_WRAP)
        core.sendChatSignalDetached(s.chatId, o.member, "gcall:${s.callId}:$kind:$b64")
    }

    /** The call goes into the calls history (and so into the chat's call rows). */
    private fun record(s: GroupCallState, outcome: CallOutcome, seconds: Int) {
        if (s.chatId.isBlank()) return
        scope.launch {
            val chat = runCatching { core.listChats(includeRequests = true).firstOrNull { it.id == s.chatId } }.getOrNull()
            history.add(
                CallHistoryEntry(
                    callId = "g_" + s.callId + "_" + System.currentTimeMillis(),
                    chatId = s.chatId,
                    peerName = chat?.name.orEmpty(),
                    peerAvatarPath = chat?.avatarPath.orEmpty(),
                    video = s.hadVideo || s.ringVideo,
                    outgoing = s.outgoing,
                    outcome = outcome,
                    atMs = if (s.since > 0) s.since else System.currentTimeMillis(),
                    durationSec = seconds,
                ),
            )
        }
    }

    /** Speaker for video (or when asked), earpiece otherwise; null = back to normal. */
    private fun routeAudio(speaker: Boolean?) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        runCatching {
            if (speaker == null) {
                am.mode = android.media.AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
            } else {
                am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = speaker
            }
        }
    }

    private fun startService(chatId: String, video: Boolean) {
        val i = Intent(context, GroupCallService::class.java).putExtra("chat_id", chatId).putExtra("video", video)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, i) }
    }

    private fun showRing(chatId: String) {
        val i = Intent(context, GroupCallService::class.java).setAction(GroupCallService.ACTION_RING).putExtra("chat_id", chatId)
        runCatching { context.startService(i) }
        // In the app: straight to the ringing screen.
        runCatching {
            context.startActivity(Intent(context, GroupCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun cancelRingNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(GroupCallService.RING_ID)
    }
}
