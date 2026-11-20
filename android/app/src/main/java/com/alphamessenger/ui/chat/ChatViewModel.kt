package com.alphamessenger.ui.chat

import com.alphamessenger.util.isGroupFull
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import com.alphamessenger.config.AttachmentLimits
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import uniffi.alphamessenger.ChatInfo
import uniffi.alphamessenger.MessengerException
import java.io.File
import javax.inject.Inject

// --- Chat state — single source of truth for the chat screen ---

/** Pre-send attachment rejection shown as a bottom sheet. `kind` ∈
 *  too_big|unreadable|empty|wrong_type|no_space. */
data class AttachmentRejectionUi(
    val mediaType: String,
    val kind: String,
    val selectedBytes: Long,
    val limitBytes: Long,
)

data class ChatState(
    val messages: List<MessageUi> = emptyList(),
    val inputText: String = "",
    /** Link preview shown above the composer while typing (null = none / failed). */
    val composerPreview: LinkPreviewData? = null,
    val composerPreviewUrl: String? = null,
    val composerPreviewLoading: Boolean = false,
    val replyTo: MessageUi? = null,
    /** The part of [replyTo] being answered (a selected fragment); null = the whole message. */
    val replyQuote: String? = null,
    val isEphemeral: Boolean = false,
    val ephemeralTimer: String = "off",
    val isViewOnce: Boolean = false,
 /** classic per-chat auto-delete timer (seconds, 0 = off). When >0,
     *  outgoing messages in this chat are sent as disappearing. */
    val chatTimerSeconds: Int = 0,
    /** "Don't keep on servers" is in effect for this chat (own choice or the global default). */
    val noStore: Boolean = false,
    val showReactionPicker: Boolean = false,
    val reactionTarget: String? = null,
    val showAttachPanel: Boolean = false,
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0,
    // Tap-to-record locked panel (mic / equalizer / pause / cancel / send).
    val isRecordingLocked: Boolean = false,
    val recordingPaused: Boolean = false,
    val showEphemeralSheet: Boolean = false,
    val showChatMenu: Boolean = false,
    val chatInfo: ChatInfo? = null,
    val ownId: String = "",
    val isLoading: Boolean = false,
    /** Who I am here and what the header says are known (loaded, or kept from last time). */
    val chromeKnown: Boolean = false,
    /** The list holds the history as read from the core (not the snapshot of the last visit). */
    val historyFresh: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
    /** Pre-send attachment rejection (size/type/space) → shown as a bottom
     *  sheet. Null = hidden. Carries the picked media type + sizes for the text. */
    val attachmentRejection: AttachmentRejectionUi? = null,
    /** In-app dialog for invite/join outcomes: (title, message). Null = hidden.
     *  Covers "link expired" and "already a member" instead of raw toasts. */
    val joinDialog: Pair<String, String>? = null,
    // --- Group-specific ---
    val isGroup: Boolean = false,
    val memberCount: Int = 0,
    val isAdmin: Boolean = false,
    /** Owner, admin or moderator: may delete others' messages in a group or discussion. */
    val canModerate: Boolean = false,
    /** True when WE were removed/banned from this group (no longer a member). */
    val removedFromGroup: Boolean = false,
    /** Unix secs until which WE are muted in this group (0 = not muted). */
    val mutedUntilMe: Long = 0L,
    /** True when the group is in "only admins can write" mode and WE are a
     *  plain member — the composer is locked with an announcement-mode notice. */
    val writeRestricted: Boolean = false,
    /** Which reactions this chat allows (a group owner's setting). */
    val reactionPolicy: ReactionPolicy = ReactionPolicy.ALL,
    /** Non-null when this group is a channel (its feed; null on a post's discussion). */
    val channel: com.alphamessenger.ui.channel.ChannelCfg? = null,
    /** Channel feed: post id → its discussion (comment count, newest authors, unseen). */
    val threads: Map<String, com.alphamessenger.ui.channel.ThreadSummary> = emptyMap(),
    /** Non-null when this screen is a post's discussion. */
    val discussion: com.alphamessenger.ui.channel.Discussion? = null,
    /** A channel's owner and admins (MLS sender ids): they speak as the channel —
     *  its name and picture, no profile, no call, no direct message. */
    val channelVoices: Set<String> = emptySet(),
    /** Channel admins by sender id (their badge in comments). */
    val adminSenders: Set<String> = emptySet(),
    /** Join / leave / ban lines in a group (its admins decide; never in channels). */
    val showNotices: Boolean = true,
    /** The group allows calls, and video calls (its owner decides). */
    val groupCalls: Boolean = true,
    val groupVideoCalls: Boolean = true,
    /** Whispers of this chat by message id. */
    val whispers: Map<String, com.alphamessenger.data.WhisperMeta> = emptyMap(),
    /** A chat from someone not in our contacts, not answered yet (Requests). */
    val isRequest: Boolean = false,
    /** We are banned here, or the owner deleted it. Nothing else of the chat is shown. */
    val ban: com.alphamessenger.data.AlphaCoreRepository.ChatGone? = null,
    /** 1:1: may we call the peer (voice / video)? Their privacy rules, from their profile. */
    val peerCanCall: Boolean = true,
    val peerCanVideo: Boolean = true,
    /** A plain group whose owner forbade forwarding, copying and screenshots. */
    val groupProtect: Boolean = false,
    /** Channel post id → how many subscribers saw it. */
    val channelViews: Map<String, Int> = emptyMap(),
    /** Channel post id → how many times it was forwarded. */
    val channelForwards: Map<String, Int> = emptyMap(),
    /** We own this group / channel. */
    val isOwner: Boolean = false,
    /** Pinned messages (shared with the chat), newest pin first. */
    val pinnedIds: List<String> = emptyList(),
    /** May we pin here (always in a direct chat; in a group by its rights). */
    val canPin: Boolean = true,
    /** Unix-secs until which the chat is temporarily closed FOR US (a plain
     *  member). 0 = not closed for us. Drives the "🔒 chat closed until …" strip. */
    val chatClosedUntilMe: Long = 0L,
    val isAddingMember: Boolean = false,
    val addMemberOk: Boolean = false,
    // --- Peer profile (direct chats only) ---
    /** MLS leaf signature key hex of the 1:1 peer, if we've seen
     *  any message from them. Empty until first inbound message. */
    val peerId: String = "",
    /** Peer's npub (1:1) — used for the user blocklist (G-11). */
    val peerNpub: String = "",
    /** Presence of the 1:1 peer for the top-bar subtitle. */
    val peerStatus: String = "",
    val peerStatusText: String = "",
    val peerLastSeen: Long = 0L,
    /** True when WE blocked this 1:1 peer → composer replaced with an
     *  "Unblock" strip and their incoming messages are ignored. */
    val peerBlocked: Boolean = false,
    /** True when this chat's notifications are muted (mutedUntil in the future). */
    val isMuted: Boolean = false,
    /** A secret chat: the 1:1 chat it came from (its peer is who we talk to). "" otherwise. */
    val secretOrigin: String = "",
    /** The secret chat is not open for writing yet (any state but "active"). */
    val secretWaiting: Boolean = false,
    /** invited / accepted / waiting / declined / active. */
    val secretState: String = "",
    /** The invitation to answer while [secretState] is "invited". */
    val secretInvite: String = "",
    /** In a 1:1 chat: secret-chat invitations from this peer awaiting our answer. */
    val secretOffers: List<String> = emptyList(),
    /** Peer's display_name from contacts (set via ProfileUpdate). */
    val peerDisplayName: String = "",
    /** Absolute path to peer's cached avatar JPEG. */
    val peerAvatarPath: String = "",
    /** sender_id → (displayName, avatarPath) cache. Used by group bubbles
     *  so we render the real peer name + avatar instead of "peer…XX" +
     *  gradient. Populated whenever messages reload and refreshed when a
     *  `ProfileUpdate` lands for one of these senders. */
    val peerProfiles: Map<String, ChatBubblePeer> = emptyMap(),
 /** classic: messages staged to forward INTO this chat (preview above
     *  the composer). Empty = no pending forward. */
    val pendingForward: List<MessageUi> = emptyList(),
    /** Live "typing…/recording…" activity of the peer in THIS chat, or null
     *  when nobody is active. Drives the top-bar subtitle + the bouncing
     *  indicator bubble. */
    val peerActivity: PeerActivity? = null,
)

/** One mic sample for the recording equalizer. A model (not a bare Float) so
 *  future signals (peak, VAD, AGC level) just add fields without an API change. */
data class RecorderTick(
    val amplitude: Float,
)

/** A peer's transient activity in the open chat. */
data class PeerActivity(
    /** "typing" or "recording". */
    val state: String,
    /** Display name of the active member — empty for 1:1 chats (the top bar
     *  already shows the peer's name). Populated for groups. */
    val senderName: String = "",
)

/** Per-sender peer projection used by bubble headers + avatars. */
data class ChatBubblePeer(
    val displayName: String,
    val avatarPath: String,
    /** Real nostr npub (bech32) — empty if not yet resolved. Used by the
     *  group avatar dialog's "Chat"/"Info" actions. */
    val npub: String = "",
)

// --- Ephemeral timer options (matching web demo) ---

/** Legacy 1:1 ephemeral timer option. `label` is an internal id, not UI text. */
data class EphemeralTimerOption(
    val label: String,
    val seconds: Int,  // 0 = off
)

/** classic per-chat auto-delete timer option (1:1). Label resolved
 *  at the UI via [labelRes]. */
data class ChatTimerOption(
    @androidx.annotation.StringRes val labelRes: Int,
    val seconds: Int,  // 0 = off
)

/** classic per-chat auto-delete timer options (1:1). */
val CHAT_TIMER_OPTIONS = listOf(
    ChatTimerOption(R.string.chat_timer_off, 0),
    ChatTimerOption(R.string.chat_timer_10s, 10),
    ChatTimerOption(R.string.chat_timer_5m, 300),
    ChatTimerOption(R.string.chat_timer_6h, 21_600),
    ChatTimerOption(R.string.chat_timer_12h, 43_200),
    ChatTimerOption(R.string.chat_timer_24h, 86_400),
    ChatTimerOption(R.string.chat_timer_7d, 604_800),
    ChatTimerOption(R.string.chat_timer_90d, 7_776_000),
)

/** Legacy 1:1 ephemeral timer options. `label` is an INTERNAL round-trip id
 *  stored in ChatState.ephemeralTimer (never rendered) — not a UI string. */
val EPHEMERAL_TIMER_OPTIONS = listOf(
    EphemeralTimerOption("off", 0),
    EphemeralTimerOption("30s", 30),
    EphemeralTimerOption("5m", 300),
    EphemeralTimerOption("1h", 3600),
    EphemeralTimerOption("8h", 28800),
    EphemeralTimerOption("1d", 86400),
    EphemeralTimerOption("1w", 604800),
)

// --- ChatViewModel — production FFI integration ---

/** BATTERY/PERF: compile-time gate for hot-path trace logs (R8 strips them in
 *  release). Flip to `true` only when profiling; never ship it `true`. */
private const val CHAT_TRACE = false

/** The biggest text body sent as one message (UTF-8 bytes; see splitLongText). */
private const val MAX_TEXT_BYTES = 30_000

/** A secret chat's messages disappear after a day unless set otherwise. */
private const val SECRET_DEFAULT_TIMER = 86_400

/** What "Save" in the selection bar can write out: photos and videos to the gallery, the rest to Downloads. */
internal val SAVEABLE_MEDIA = setOf("image", "video", "file", "audio")

@HiltViewModel
class ChatViewModel @Inject constructor(
    val repository: AlphaCoreRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val callHistory: com.alphamessenger.calls.CallHistoryStore,
    // THE engine — a process @Singleton (see TransferModule), NOT built here. This
    // is what lets an in-flight ring survive leaving+re-entering a chat: the live
    // state and download/upload jobs outlive this NavBackStackEntry-scoped VM.
    val transfers: com.alphamessenger.transfer.TransferEngine,
    // Media send queue — also a process @Singleton. The message is created
    // BEFORE file preparation; preparation/upload live here and survive
    // leaving the screen and process death.
    private val sendQueue: com.alphamessenger.ui.media.send.MediaSendQueue,
    // Network state — one shared source with the send queue: the screen and
    // the send must not answer this question differently at the same moment.
    networkMonitor: com.alphamessenger.data.NetworkMonitor,
    private val privacyPrefs: com.alphamessenger.data.PrivacyPrefs,
    private val strings: com.alphamessenger.util.Strings,
    /** Answers in assistant chats (a process singleton: an answer outlives the screen). */
    val assistant: com.alphamessenger.assistant.AssistantEngine,
) : ViewModel() {

    /** The assistant wrote to this chat: show it. */
    fun refreshAfterAssistant() = reloadMessages(coalesce = false)

    val networkOnline: kotlinx.coroutines.flow.StateFlow<Boolean> = networkMonitor.online

    private val _uiState = MutableStateFlow(ChatState())
    val uiState: StateFlow<ChatState> = _uiState.asStateFlow()

    // Localized labels embedded into MessageUi fields by the FFI→UI conversion
    // (system notices, reply quotes, sender fallbacks). Resolves per access, so
    // an in-app locale switch is picked up on the next reload.
    private val messageLabels = MessageUiLabels(strings)

    // --- Auto-download re-trigger observers ---
    // consider() fires once per bubble mount and never re-evaluates. These two
    // observers fire when the policy inputs change (prefs toggle / network
    // switch) and tell the engine to re-run consider() for every on-screen key.
    // consider() has all guards (Completed/isActive/Paused/cancelled/CacheEvicted)
    // so this never starts a duplicate or revives a user-stopped download.
    private val autoDownloadPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            transfers.reconsiderVisible()
        }
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { transfers.reconsiderVisible() }
        override fun onLost(network: android.net.Network) { transfers.reconsiderVisible() }
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            caps: android.net.NetworkCapabilities,
        ) { transfers.reconsiderVisible() } // Wi-Fi ↔ mobile switch changes the policy
       }

    init {
        // A call just ended (the local journal got a new entry) → refresh the
        // timeline so the call-log chip appears in the open dialog right away.
        viewModelScope.launch {
            var first = true
            callHistory.entries.collect {
                if (first) { first = false; return@collect }
                if (currentChatId.isNotBlank()) reloadMessages()
            }
        }
        // The send queue lives outside the screen and itself reports that
        // messages in this chat changed. This is how the screen learns about
        // delivery without polling it.
        viewModelScope.launch {
            sendQueue.changed.collect { chatId ->
                if (chatId == currentChatId) reloadMessages()
            }
        }
        // A send failure speaks in words. A silent ↻ gave no way to tell "no
        // network" from "file deleted" from "long preparation" — the user
        // could only guess and press a button that in some cases could not help.
        viewModelScope.launch {
            sendQueue.problems.collect { problem ->
                if (problem.chatId == currentChatId) {
                    _uiState.value = _uiState.value.copy(error = problem.text)
                }
            }
        }
        // NOTE: auto-download observer registration is in the init block AFTER
        // `transfers` is declared (NetworkCallback.onAvailable can fire
        // immediately, so `transfers` must be initialized first).
    }

    /** Merge the LOCAL call journal for this chat into the message timeline
     *  as synthetic call-log rows (id "call-<uuid>", sorted by time). */
    private fun mergeCallLog(bubbles: List<MessageUi>): List<MessageUi> {
        val chatId = currentChatId
        if (chatId.isBlank()) return bubbles
        // Calls from before the chat's history was wiped are not part of it any more.
        val clearedAt = repository.historyClearedAt(chatId)
        val calls = callHistory.entries.value.filter { it.chatId == chatId && it.atMs > clearedAt }
        if (calls.isEmpty()) return bubbles
        fun epochOf(ts: String): Long =
            runCatching { java.time.OffsetDateTime.parse(ts).toEpochSecond() }.getOrDefault(Long.MAX_VALUE)
        val callRows = calls.map { e ->
            MessageUi(
                id = "call-${e.callId}",
                text = "",
                timestamp = java.time.OffsetDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(e.atMs), java.time.ZoneId.systemDefault(),
                ).toString(),
                isOutgoing = e.outgoing,
                senderId = if (e.outgoing) "self" else "call-peer",
                callLog = e,
            )
        }
        return (bubbles + callRows).sortedBy { epochOf(it.timestamp) }
    }

    // Live mic samples for the recording equalizer — a hot stream, NOT part of
    // ChatState, so the ~10 Hz updates never recompose ChatScreen. Only the
    // VoiceRecordPanel collects it and accumulates the waveform locally.
    private val _recorderTicks = kotlinx.coroutines.flow.MutableSharedFlow<RecorderTick>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val recorderTicks: kotlinx.coroutines.flow.SharedFlow<RecorderTick> = _recorderTicks

    private var currentChatId: String = ""

    /** The channel post whose discussion this screen shows (route arg), or null for the chat itself. */
    private val thread: String? = savedStateHandle.get<String>("thread")?.takeIf { it.isNotBlank() }
    /** Opened at a message (search, "show in chat"): the first load already reaches it. */
    private val openAt: String? = savedStateHandle.get<String>("focus")?.substringBefore("#al")?.takeIf { it.isNotBlank() }

    /** The comment thread of a message sent to [target]: this screen's discussion
     *  when [target] is its channel, none anywhere else. */
    private fun threadFor(target: String): String? = thread?.takeIf { target == currentChatId }

    /** The newest [limit] messages of what this screen shows: a post's discussion or the chat. */
    private suspend fun fetchWindow(chatId: String, limit: UInt = windowSize): List<uniffi.alphamessenger.MessageInfo> =
        thread?.let { repository.getThread(chatId, it, limit, 0u) } ?: repository.getMessages(chatId, limit, 0u)

    private val discussionSeen = com.alphamessenger.ui.channel.DiscussionSeen(appContext)

    /** Feed: comment summaries with what this device has not seen yet. */
    private suspend fun threadSummaries(chatId: String): Map<String, com.alphamessenger.ui.channel.ThreadSummary> =
        repository.threadSummaries(chatId).mapValues { (post, t) ->
            t.copy(unread = (t.others - discussionSeen.seen(chatId, post)).coerceAtLeast(0))
        }

    /** Discussion: the post, the rules and our answer from the core. Comments seen. */
    private suspend fun discussionOf(chatId: String, ownId: String, cfg: com.alphamessenger.ui.channel.ChannelCfg): com.alphamessenger.ui.channel.Discussion? {
        val post = thread ?: return null
        val prev = _uiState.value.discussion
        val role = myGroupRole(chatId)
        val summary = repository.threadSummaries(chatId)[post]
        val count = summary?.count ?: 0
        discussionSeen.markSeen(chatId, post, summary?.others ?: 0)
        return com.alphamessenger.ui.channel.Discussion(
            postId = post,
            post = prev?.post ?: findPost(chatId, ownId),
            cfg = cfg,
            status = repository.writeStatus(chatId, post, "text"),
            voice = repository.writeStatus(chatId, post, "voice"),
            canModerate = role in MODERATOR_ROLES,
            count = count,
            loaded = true,
        )
    }

    /** The discussed post, looked up in the channel's feed. */
    private suspend fun findPost(chatId: String, ownId: String): MessageUi? {
        val id = thread ?: return null
        return runCatching {
            repository.getMessages(chatId, 2000u, 0u).foldToBubbles(ownId, messageLabels).markWhispers(repository.whisperIds(chatId), strings.get(R.string.whisper_title)).groupAlbums()
                .firstOrNull { it.id.substringBefore("#al") == id }
        }.getOrNull()
    }

    /** How many of the newest messages are loaded; grows as you scroll up. */
    private var windowSize: UInt = PAGE
    /** False once the whole history is loaded. */
    private var hasOlder = true
    private var loadingOlder = false
    /** Rows the last window read returned: fewer than asked means the history ended. */
    @Volatile private var fetchedRows = -1

    /** The deep-link message this chat already scrolled to: once per opening, kept
     *  here so a screen rebuilt mid-chat never jumps back to it. */
    var focusShown: String? = null

    /** Scrolled to the oldest loaded message: load the next page. */
    fun loadOlder() {
        if (!hasOlder || loadingOlder || currentChatId.isBlank()) return
        if (fetchedRows in 0 until windowSize.toInt()) { hasOlder = false; return }
        loadingOlder = true
        viewModelScope.launch(Dispatchers.IO) {
            windowSize += PAGE
            reloadNow()
            if (fetchedRows < windowSize.toInt()) hasOlder = false
            loadingOlder = false
        }
    }

    /**
     * Make sure [messageId] is loaded (a pin, a reply far up): grow the window
     * until it is there or the history ends. [onDone] tells whether it was found.
     */
    private var revealing: String? = null

    fun revealMessage(messageId: String, onDone: (Boolean) -> Unit) {
        val id = messageId.substringBefore("#al")
        // The screen asks again as the list grows: one load per target is enough.
        if (revealing == id) return
        revealing = id
        viewModelScope.launch(Dispatchers.IO) {
            var found = _uiState.value.messages.any { it.id.substringBefore("#al") == id }
            if (!found) {
                // The core knows where the message sits: one load reaches it, however old.
                val rank = repository.messageRank(currentChatId, id)
                if (rank != null) {
                    val want = (rank.toUInt() + PAGE)
                    if (want > windowSize) windowSize = want
                    reloadNow()
                    if (fetchedRows < windowSize.toInt()) hasOlder = false
                    found = _uiState.value.messages.any { it.id.substringBefore("#al") == id }
                }
            }
            revealing = null
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(found) }
        }
    }

    /**
     * The whole history (up to the cap) for a jump to its start: a jump to the oldest
     * LOADED message would only page in more above it. [onDone] gets the loaded count.
     */
    fun revealStart(onDone: (Int) -> Unit) {
        if (currentChatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            loadingOlder = true
            try {
                while (hasOlder && windowSize < MAX_WINDOW) {
                    windowSize += PAGE * 4u
                    reloadNow()
                    if (fetchedRows < windowSize.toInt()) hasOlder = false
                }
            } finally {
                loadingOlder = false
            }
            val count = _uiState.value.messages.size
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(count) }
        }
    }

    /** reloadMessages without the burst coalescing — awaited by paging. */
    private suspend fun reloadNow() {
        reloadMessages(coalesce = false)
        // An incoming message may replace this reload; wait for the one that ran last.
        while (true) { val j = reloadJob ?: break; j.join(); if (j === reloadJob) break }
    }
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingJob: Job? = null

    // Active upload jobs keyed by msgId — used for cancel support.
    private val activeUploadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // Typing-indicator throttling. We re-emit "typing" at most every ~3s
    // while the user keeps typing, and schedule a "paused" 4s after the last
    // keystroke so a peer never sees a stuck indicator.
    private var lastTypingSentMs = 0L
    private var typingStopJob: Job? = null
    private var presenceJob: Job? = null
    private var peerPresenceJob: Job? = null

    init {
        // Seed the header (name + avatar) from the list's already-resolved
        // preview so the first frame is correct — no "Direct chat" fallback.
        savedStateHandle.get<String>("chatId")?.let { seedId ->
            ChatOpenHandoff.take(seedId)?.let { p ->
                _uiState.value = _uiState.value.copy(
                    chatInfo = p.chatInfo,
                    isGroup = p.chatInfo.chatType == "group",
                    peerDisplayName = p.peerDisplayName,
                    peerAvatarPath = p.peerAvatarPath,
                )
            }
 // standard: paint the LAST DISPLAYED history INSTANTLY from
            // the snapshot cache on frame 1 — with isLoading = true while Rust re-reads
            // the DB in the background. The fresh list atomically replaces this seed in
            // loadMessages(); there is NEVER a messages = emptyList() state, so no
            // "bubbles drawing in" after open. First-ever open of a chat has no snapshot
            // → seed stays empty (unavoidable), every re-open is instant.
            ConversationSnapshotCache.get(seedId)?.takeIf { thread == null }?.let { snapshot ->
                _uiState.value = _uiState.value.copy(
                    messages = snapshot.messages,
                    peerProfiles = snapshot.peerProfiles,
                    whispers = snapshot.whispers,
                    isLoading = true,
                )
            }
            // The chat's row from the core (my role, members, channel settings): the
            // first frame has the real header and composer, never a stranger's defaults.
            repository.chatInfoNow(seedId)?.takeIf { thread == null }?.let { info ->
                val group = info.chatType == "group"
                val role = info.myRole
                _uiState.value = _uiState.value.copy(
                    chatInfo = info,
                    isGroup = group,
                    memberCount = info.memberCount.toInt(),
                    isOwner = group && role == "owner",
                    isAdmin = group && (role == "owner" || role == "admin"),
                    canModerate = group && role in MODERATOR_ROLES,
                    isMuted = info.mutedUntil > (System.currentTimeMillis() / 1000L).toULong(),
                    channel = if (group) com.alphamessenger.ui.channel.ChannelCfg.parse(info.channelSettings).takeIf { it.on } else null,
                    chromeKnown = true,
                )
            }
            // A discussion opened from the feed shows its post on the first frame.
            thread?.let { t ->
                com.alphamessenger.ui.channel.DiscussionHandoff.take(t)?.let { post ->
                    _uiState.value = _uiState.value.copy(discussion = com.alphamessenger.ui.channel.Discussion(postId = t, post = post))
                }
                // Who may write changes with time (a restriction ends, slow mode
                // counts down): the core is asked again every second.
                viewModelScope.launch(Dispatchers.IO) {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        val d = _uiState.value.discussion ?: continue
                        if (currentChatId.isBlank()) continue
                        val st = repository.writeStatus(currentChatId, d.postId, "text")
                        val voice = repository.writeStatus(currentChatId, d.postId, "voice")
                        val now = _uiState.value.discussion
                        if (now != null && (st != now.status || voice != now.voice)) {
                            _uiState.value = _uiState.value.copy(discussion = now.copy(status = st, voice = voice))
                        }
                    }
                }
            }
        }
        // Resolve ownId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val npub = repository.ownNpub()
                if (_uiState.value.ownId.isBlank())
                    _uiState.value = _uiState.value.copy(ownId = npub)
            } catch (_: Exception) {
                // ownId stays empty
            }
        }
    }

    // --- Collect repository callback flows for real-time updates ---
    private var messageJob: Job? = null
    private var deliveryJob: Job? = null
    /** Statuses that arrived before their message was on screen (a reply sent from
     *  the viewer, a send racing its own reload). Applied the moment it shows up. */
    private val earlyStatus = java.util.concurrent.ConcurrentHashMap<String, MessageStatus>()

    /** The freshest status we know for each own message: from the DB read, from
     *  what is already shown, or from an event that came first. Never lower —
     *  a reload that read the DB a moment too early must not turn ✓ back into 🕓. */
    private fun List<MessageUi>.withKnownStatus(): List<MessageUi> {
        val shown = _uiState.value.messages.associateBy({ it.id }, { it.status })
        if (shown.isEmpty() && earlyStatus.isEmpty()) return this
        return map { m ->
            if (!m.isOutgoing) return@map m
            var best = m.status
            shown[m.id]?.let { if (it.ordinal > best.ordinal) best = it }
            earlyStatus.remove(m.id)?.let { if (it.ordinal > best.ordinal) best = it }
            if (best != m.status) m.copy(status = best) else m
        }
    }
    /** When this screen last took its list from the DB: a stale copy must not overwrite a newer snapshot. */
    private var listStamp = 0L
    private var syncJob: Job? = null
    private var memberJob: Job? = null

    // True only while the ChatScreen itself is on top (resumed). When the user
    // opens the group/contact info screen the ChatViewModel stays alive (it's
    // scoped to the chat backstack entry), so without this flag incoming messages
    // would keep getting auto-marked-read — the sender would see their ticks turn
    // "read" even though we're not actually looking at the chat.
    // Kept per screen instance: when the screen is rebuilt, the new one is up before
    // the old one is gone, and a single flag was left "off" by the old one's exit.
    private val activeScreens = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
    /** Windows over the chat in the same activity (media viewer): the lifecycle stays
     *  resumed, yet the messages are not seen. */
    private val coveringWindows = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
    private val screenActive: Boolean get() = activeScreens.isNotEmpty() && coveringWindows.isEmpty()
    /** Some instance of the chat screen is on top right now. */
    val isScreenActive: Boolean get() = screenActive

    /** Called by each ChatScreen instance ([owner]) as it resumes, pauses or leaves.
     *  Marking everything read on (re)activation keeps "read while viewing" intact. */
    fun setScreenActive(owner: Any, active: Boolean) =
        changeActivity { if (active) activeScreens.add(owner) else activeScreens.remove(owner) }

    /** A full-screen window ([owner]) opens over the chat or closes. */
    fun setScreenCovered(owner: Any, covered: Boolean) =
        changeActivity { if (covered) coveringWindows.add(owner) else coveringWindows.remove(owner) }

    private fun changeActivity(change: () -> Unit) {
        val was = screenActive
        change()
        // Back on screen: what was seen meanwhile is read now.
        if (screenActive && !was) { lastReadThrough = null; readSeen() }
        if (was && !screenActive) {
            // The instant the chat screen is paused or navigated away from, stop
            // advertising presence and send a single best-effort "paused" so the
            // peer's "typing…/recording…" clears immediately. emitChatState()
            // suppresses any further "typing"/"recording" while screenActive ==
            // false, so a still-running recording ticker cannot refresh the
            // indicator behind the user's back.
            typingStopJob?.cancel(); typingStopJob = null
            lastTypingSentMs = 0L
            emitChatState("paused")
            // Refresh the snapshot cache with what's currently on screen, so re-open
 // paints the last displayed history instantly. onCleared
            // does the same on real destruction; this also covers pause-without-destroy.
            if (thread == null) ConversationSnapshotCache.put(currentChatId, _uiState.value.messages, _uiState.value.peerProfiles, _uiState.value.whispers, listStamp)
        }
    }

    /** The newest message the reader has on screen, and the last one read up to. */
    private var seenThrough: String? = null
    private var lastReadThrough: String? = null

    /** The screen reports the newest message it shows ([key]: its list key). Read goes
     *  up to it and never past: what arrives while they read history stays unread. */
    fun onSeen(key: String) {
        val all = _uiState.value.messages
        // A row of the screen's own ("#think", "#wallpaper") stands for its message.
        val m = all.firstOrNull { it.id == key } ?: all.firstOrNull { it.id == key.substringBefore('#') } ?: return
        // An album is one stored message: its parts' "#al" ids exist only on screen.
        seenThrough = m.id
        readSeen()
    }

    /** The first message written on [day] (the device's day) or later, or null. */
    suspend fun messageAtDate(day: java.time.LocalDate): String? {
        val chat = currentChatId.takeIf { it.isNotBlank() } ?: return null
        val at = com.alphamessenger.ui.chat.text.SearchMatch.dayStart(day)
        return runCatching { repository.messageAtDate(chat, at) }.getOrNull()
    }

    /** One match of the in-chat search, as its list shows it. */
    data class InChatHit(val id: String, val text: String, val at: String, val outgoing: Boolean, val sender: String, val kind: String)

    private val hitNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** This chat's messages matching [query], newest first (the core's index). */
    suspend fun searchHere(query: String): List<InChatHit> {
        val chat = currentChatId.takeIf { it.isNotBlank() } ?: return emptyList()
        val q = query.trim()
        val match = com.alphamessenger.ui.chat.text.SearchMatch
        return runCatching { repository.searchMessages(q, chatId = chat, limit = 1000) }.getOrDefault(emptyList())
            .mapNotNull { h ->
                val text = match.visible(h.content)
                // A file is found by its name as well as by its caption.
                if (!match.matches(text + " " + h.mediaName, q)) return@mapNotNull null
                val own = h.senderId == "self"
                val sender = if (own) "" else hitNames.getOrPut(h.senderId) {
                    runCatching { repository.getPeerProfile(h.senderId).displayName }.getOrDefault("")
                }
                InChatHit(h.messageId, text.ifBlank { h.mediaName }, h.createdAt, own, sender, h.mediaKind)
            }
    }

    private fun readSeen() {
        val through = seenThrough ?: return
        // Never send read receipts to a blocked 1:1 peer (no "read").
        if (!screenActive || _uiState.value.peerBlocked || through == lastReadThrough) return
        lastReadThrough = through
        val chat = currentChatId
        viewModelScope.launch { runCatching { repository.markChatReadThrough(chat, through) } }
    }

    fun startObservingEvents() {
        if (messageJob != null) return // already observing
        messageJob = viewModelScope.launch {
            repository.messageReceived.collect { message ->
                if (message.chatId == currentChatId) {
                    // "Typing…" is cleared by the repository, its one owner; clearing it
                    // here too left it gone while they went on typing.
                    // Read once its bubble is on screen (onSeen), not on arrival.
                    reloadMessages()
                }
            }
        }
        deliveryJob = viewModelScope.launch {
            repository.deliveryUpdate.collect { (messageId, status) ->
                // The ONLY completion signal that comes from ALL senders —
                // including the core's own queue.
                //
                // On a connection break a document goes to the core queue,
                // which finishes the upload and publishes it by itself, reporting
                // only the delivery. Without this branch a filled ring with ✕
                // would stay forever over the sent file — next to the checkmark.
                if (status == "sent" || status == "delivered" || status == "read") {
                    transfers.onOutgoingTransferPublished(messageId)
                }
                // Three DISTINCT outcomes, not two.
                //
                // "waiting_network" comes from the queue's actual state in the
                // core — the only one that knows why work is stalled. The UI must
                // not derive it as "failed && offline": a real error that happened
                // at the moment the connection dropped would masquerade as waiting.
                //
                // An UNKNOWN status never touches the transfer: treating an
                // unfamiliar word as failure is exactly how a network wait gets
                // shown as a "retry" icon.
                when (status) {
                    "waiting_network" -> transfers.onUploadWaitingNetwork(messageId)
                    "sending" -> transfers.onUploadResumed(messageId)
                    else -> Unit
                }
                val current = _uiState.value.messages.toMutableList()
                val idx = current.indexOfFirst { it.id == messageId }
                val mapped = when (status) {
                    "sending" -> MessageStatus.SENDING
                    "sent" -> MessageStatus.SENT
                    "delivered" -> MessageStatus.DELIVERED
                    "read" -> MessageStatus.READ
                    else -> null
                }
                if (idx < 0 && mapped != null) {
                    // Not on screen yet: keep it for the reload that brings it in.
                    earlyStatus.merge(messageId, mapped) { a, b -> if (b.ordinal > a.ordinal) b else a }
                    if (earlyStatus.size > 256) earlyStatus.clear()
                }
                if (idx >= 0) {
                    val msg = current[idx]
                    // Monotonic: only upgrade, never downgrade.
                    // SENDING(0) < SENT(1) < DELIVERED(2) < READ(3)
                    // This prevents a late "sent" callback from reverting
                    // an already-DELIVERED status back to SENT.
                    val newStatus = if (mapped != null && mapped.ordinal > msg.status.ordinal) {
                        mapped
                    } else {
                        msg.status
                    }
                    current[idx] = msg.copy(status = newStatus)
                    // Receipts are batched: "read" covers our earlier messages too.
                    if (mapped == MessageStatus.READ) {
                        val upTo = runCatching { java.time.OffsetDateTime.parse(msg.timestamp).toInstant() }.getOrNull()
                        if (upTo != null) {
                            for (i in current.indices) {
                                val m = current[i]
                                if (!m.isOutgoing || m.status.ordinal >= MessageStatus.READ.ordinal) continue
                                val t = runCatching { java.time.OffsetDateTime.parse(m.timestamp).toInstant() }.getOrNull() ?: continue
                                if (!t.isAfter(upTo)) current[i] = m.copy(status = MessageStatus.READ)
                            }
                        }
                    }
                    _uiState.value = _uiState.value.copy(messages = current)
                }
            }
        }
        syncJob = viewModelScope.launch {
            repository.syncComplete.collect { newMessageCount ->
                if (newMessageCount > 0u) {
                    reloadMessages()
                }
            }
        }
        // Re-add to a group we were banned/removed from: the Welcome arrives as
        // chatCreated / chatMetadataUpdated. Reload so the read-only ban panel
        // turns back into the composer WITHOUT needing to leave & reopen.
        viewModelScope.launch {
            repository.chatCreated.collect { info ->
                if (info.id == currentChatId) reloadMessages()
            }
        }
        viewModelScope.launch {
            repository.chatMetadataUpdated.collect { id ->
                if (id == currentChatId) {
                    // Pins, allowed reactions and our rights change only with the
                    // chat's metadata — read them here, not on every reload.
                    refreshChatSettings(id)
                    val st = _uiState.value
                    _uiState.value = withSecretState(st, id, st.isGroup, runCatching { repository.chatMemberCount(id) }.getOrDefault(st.memberCount))
                    // Name / photo / description changed: the header reads chatInfo.
                    runCatching { repository.listChats(includeRequests = true).find { it.id == id } }.getOrNull()?.let { info ->
                        _uiState.value = _uiState.value.copy(chatInfo = info)
                    }
                    reloadMessages()
                }
            }
        }
        memberJob = viewModelScope.launch {
            repository.memberAdded.collect { (chatId, _) ->
                if (chatId == currentChatId) reloadMessages()
            }
        }
        // NOTE: mediaProgress is collected by TransferEngine — exactly once and
        // nowhere else; progress, speed and ETA are computed only inside the engine.
        // Refresh peer name/avatar in the TopBar + group bubbles when a
        // ProfileUpdate lands.
        viewModelScope.launch {
            repository.peerProfileUpdated.collect { senderId ->
                val state = _uiState.value
                val fresh = runCatching { repository.getPeerProfile(senderId) }.getOrNull()
                    ?: return@collect
                // Patch peerProfiles map regardless of whether this is the 1:1
                // peer or a group member.
                if (senderId in state.channelVoices) return@collect
                val newProfiles = state.peerProfiles.toMutableMap().also {
                    it[senderId] = ChatBubblePeer(fresh.displayName, fresh.avatarPath)
                }
                _uiState.value = state.copy(
                    peerProfiles = newProfiles,
                    peerDisplayName = if (state.peerId == senderId) fresh.displayName else state.peerDisplayName,
                    peerAvatarPath = if (state.peerId == senderId) fresh.avatarPath else state.peerAvatarPath,
                )
            }
        }
        // Map the global presence map → this chat's PeerActivity. Ignores our
        // own signals; resolves the member name for group chats.
        peerPresenceJob?.cancel()
        peerPresenceJob = viewModelScope.launch(Dispatchers.IO) {
            val chatId = currentChatId
            val hex = repository.peerHexForChat(chatId)
            if (hex.isBlank()) return@launch
            suspend fun read() {
                val st = runCatching { repository.presenceStatus(hex) }.getOrDefault("")
                val txt = runCatching { repository.presenceCustomStatus(hex) }.getOrDefault("")
                val seen = runCatching { repository.presenceLastSeen(hex) }.getOrDefault(0L)
                if (chatId == currentChatId) {
                    _uiState.value = _uiState.value.copy(peerStatus = st, peerStatusText = txt, peerLastSeen = seen)
                }
            }
            read()
            runCatching { repository.presenceTick() }
            repository.presenceChanged.collect { read() }
        }
        presenceJob = viewModelScope.launch {
            // A discussion shows who is writing in IT, the chat its own.
            val presence = if (thread != null) repository.threadPresence.map { it[currentChatId to thread] }
                else repository.chatPresence.map { it[currentChatId] }
            // Re-judged when the history changes too: a late signal can land while the
            // message it came before is still being loaded.
            val newest = _uiState.map { it.messages.lastOrNull()?.id }.distinctUntilChanged()
            presence.combine(newest) { p, _ -> p }.collect { p ->
                // A blocked 1:1 peer must not surface "typing…"/"recording".
                val activity = if (p != null && p.senderId != _uiState.value.ownId && !_uiState.value.peerBlocked &&
                    !sentBeforeTheirLastMessage(p)
                ) {
                    val name = if (_uiState.value.isGroup) {
                        _uiState.value.peerProfiles[p.senderId]?.displayName ?: strings.get(R.string.chat_member_fallback)
                    } else ""
                    PeerActivity(p.state, name)
                } else null
                if (activity != _uiState.value.peerActivity) {
                    _uiState.value = _uiState.value.copy(peerActivity = activity)
                }
            }
        }
    }

    /**
     * A "typing" sent before their newest message is a late copy of what that message
     * ended (signals take their own way through the relays). Both times are on the
     * sender's clock; an unknown send time is never judged stale.
     */
    private fun sentBeforeTheirLastMessage(p: com.alphamessenger.data.ChatPresence): Boolean {
        if (p.sentMs <= 0L) return false
        val last = _uiState.value.messages.lastOrNull { !it.isOutgoing && it.senderId == p.senderId } ?: return false
        val at = runCatching { java.time.OffsetDateTime.parse(last.timestamp).toInstant().toEpochMilli() }.getOrNull() ?: return false
        return p.sentMs < at
    }

    // --- Load messages for a chat ---

    fun loadMessages(chatId: String) {
        // Clear the considered-key map when switching chats so stale media refs
        // from the previous chat are not re-evaluated by reconsiderVisible().
        val switchingChat = currentChatId != chatId
        if (switchingChat) { windowSize = PAGE; hasOlder = true; fetchedRows = -1 }
        if (switchingChat) transfers.clearConsidered()
        currentChatId = chatId
        viewModelScope.launch(Dispatchers.IO) {
            // First render depends on EXACTLY two inputs: messages + ownId. The draft
            // is composer state (secondary) — it is restored AFTER the list is painted,
            // off the history critical path (see below), so it never delays the bubbles.
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Resolve the two IO-backed snapshot inputs BEFORE the DB read, so the
                // getMessages → fold → EARLY-emit segment contains NO suspension other
                // than the message read itself (pure-CPU fold in between):
                //  • ownId — needed to fold each message on the correct side (mine vs
                //    incoming) + ticks. Memoized (≈free after first open); resolving it
                //    here also kills the wrong-side flash from an empty-ownId race.
                //  • pendingSendIds — marks the "sending" ring; part of the first render.
                val ownId = _uiState.value.ownId.ifBlank {
                    repository.ownNpub().also { _uiState.value = _uiState.value.copy(ownId = it) }
                }
                val pendingIds = repository.pendingSendIds()
                repository.refreshHeld()
                val __ms0 = System.nanoTime()
                // Opened at a message: one read takes the history down to it (the core
                // says how far back it is), never the latest page and a jump into nothing.
                if (switchingChat && thread == null && openAt != null) {
                    repository.messageRank(chatId, openAt)?.let { rank ->
                        val want = rank.toUInt() + PAGE
                        if (want > windowSize) windowSize = want
                    }
                }
                val messages = fetchWindow(chatId)
                val uiMessages = messages.foldToBubbles(ownId, messageLabels).markWhispers(repository.whisperIds(chatId), strings.get(R.string.whisper_title)).let { mergeCallLog(it) }.withRunFlags().groupAlbums().applyPendingSending(pendingIds)
                seedPendingUploads(uiMessages, pendingIds)

                // EARLY EMIT: the local history is READY here — show it NOW, before ANY
                // transfer-engine bookkeeping or metadata lookups. Uses the chatInfo/
                // isGroup/peer already seeded from ChatOpenHandoff. The metadata (members,
                // profiles, block/write state) refines in the FINAL emit below WITHOUT
                // rebuilding the list (identical ids → LazyColumn keeps its items).
                // Whispers come with the list, in the same frame: drawn without them for one
                // frame, a whisper shows its content as an ordinary bubble.
                _uiState.value = _uiState.value.copy(messages = uiMessages, whispers = repository.whispers(chatId), isLoading = false, historyFresh = true)
                fetchedRows = messages.size
                if (fetchedRows < windowSize.toInt()) hasOlder = false
                listStamp = ConversationSnapshotCache.stamp()
                // An old leaf in a 1:1 is re-signed when it is our turn (the core decides).
                if (switchingChat && thread == null) viewModelScope.launch(Dispatchers.IO) { repository.refreshLeafBinding(chatId) }

                // Draft restore — AFTER the history is on screen (secondary composer
                // state, off the first-render path). Guard against clobbering anything
                // the user already typed in the brief window.
                viewModelScope.launch(Dispatchers.IO) {
                    val draft = if (thread != null) "" else runCatching { repository.getDraft(chatId) }.getOrDefault("")
                    if (draft.isNotEmpty() && _uiState.value.inputText.isEmpty())
                        _uiState.value = _uiState.value.copy(inputText = draft)
                }

                // Transfer-engine side effects — OFF the display critical path. Registering
                // album groups + firing consider() for EVERY album item costs ~64ms on a
                // 200-item album (per-item JSON size parse + disk check) and used to block
                // the list emit AND every reload. NOT needed to render: visible tiles self-
                // register via their own consider(); this adds the aggregate group + the
                // hidden 5+ items a frame later. Async so neither emit waits for it.
                viewModelScope.launch(Dispatchers.IO) {
                    registerAlbumGroups(uiMessages)
                    reconcileFinishedUploads(uiMessages)
                    transfers.retainOnly(transferKeysOf(uiMessages))
                }

                // Determine if this is a group chat
                val chatInfo = try {
                    repository.listChats(includeRequests = true).find { it.id == chatId }
                } catch (_: Exception) { null }

                val isGroup = chatInfo?.chatType == "group"
                val memberCount = try {
                    repository.chatMemberCount(chatId)
                } catch (_: Exception) { 0 }

                // Peer profile (direct chats): find the first inbound sender_id
                // and look up the cached profile. For groups we fall back to
                // the existing bubble-per-sender rendering — there's no single peer.
                // Stable 1:1 peer from the MLS member list (the non-self member).
                // Available from group state IMMEDIATELY (from chat creation),
                // independent of inbound messages. Fall back to the inbound-sender
                // heuristic only if unavailable.
                val peerMember = if (!isGroup) runCatching {
                    val arr = org.json.JSONArray(repository.listGroupMembers(chatId))
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { !it.optBoolean("is_self", false) && it.optString("sender_id").isNotBlank() }
                }.getOrNull() else null
                val peerId = (peerMember?.optString("sender_id").orEmpty())
                    .ifBlank { if (!isGroup) repository.firstInboundSenderId(chatId, ownId) else "" }
                val (peerName, peerAvatar) = when {
                    peerMember != null ->
                        peerMember.optString("display_name") to peerMember.optString("avatar_path")
                    peerId.isNotBlank() -> runCatching { repository.getPeerProfile(peerId) }.getOrNull()
                        ?.let { it.displayName to it.avatarPath } ?: ("" to "")
                    else -> "" to ""
                }
                // 1:1 block state.
                val peerNpub = if (!isGroup)
                    runCatching { repository.getPeerNpubForChat(chatId) }.getOrNull().orEmpty() else ""
                val peerBlocked = peerNpub.isNotBlank() && repository.isUserBlocked(peerNpub)
 // classic forward: consume any staged messages targeted here.
                val staged = if (ForwardBuffer.sourceChatId != chatId) ForwardBuffer.consume().orEmpty() else emptyList()
                val blockedAtSec = if (peerBlocked) repository.blockedAtFor(peerNpub) else 0L
                val visibleMessages = uiMessages.hideBlockedIncoming(peerBlocked, blockedAtSec)

                val profiles = resolvePeerProfilesFor(visibleMessages, ownId)
                // Banned/removed read-only state must be computed HERE too (on
                // first open), not only in reloadMessages — otherwise the banned
                // user sees the composer until some event triggers a reload.
                val removed = if (isGroup) {
                    val persisted = repository.isRemovedFromGroup(chatId)
                    val live = runCatching {
                        val arr = org.json.JSONArray(repository.listGroupMembers(chatId))
                        (0 until arr.length()).none { arr.getJSONObject(it).optBoolean("is_self", false) }
                    }.getOrDefault(false)
                    val unavailable = repository.isGroupUnavailable(chatId)
                    persisted || live || unavailable
                } else false
                val mutedUntil = if (isGroup && ownId.isNotBlank())
                    runCatching { repository.groupMuteUntil(chatId, ownId) }.getOrDefault(0L)
                else 0L
                val (writeRestricted, closedForMe) = computeWriteState(chatId, isGroup, removed)
                val amAdmin = isGroup && isGroupAdmin(chatId)
                val reactionPolicy = if (isGroup) repository.groupReactionPolicy(chatId) else ReactionPolicy.ALL
                val pinnedIds = if (thread != null) emptyList() else repository.pinnedMessages(chatId)
                val canPin = thread == null && (!isGroup || amAdmin || repository.groupPermissions(chatId).third == "all")
                val channelCfg = if (isGroup) repository.channelCfg(chatId).takeIf { it.on } else null
                val myRole = if (isGroup) myGroupRole(chatId) else ""
                val amOwner = myRole == "owner"
                val groupProtect = isGroup && channelCfg == null && repository.channelCfg(chatId).protect
                // A post's discussion: everyone may write by the comment rules
                // (the core's answer), and it is drawn like a group chat.
                val discussion = channelCfg?.let { discussionOf(chatId, ownId, it) }
                val threads = if (thread == null && channelCfg != null) threadSummaries(chatId) else emptyMap()
                val voices = voicesFor(chatId, isGroup, channelCfg != null)
                _uiState.value = _uiState.value.copy(
                    isOwner = amOwner,
                    groupProtect = groupProtect || (discussion != null && channelCfg.protect),
                    discussion = discussion,
                    threads = threads,
                    reactionPolicy = reactionPolicy,
                    pinnedIds = pinnedIds,
                    canPin = canPin,
                    channel = if (thread != null) null else channelCfg,
                    channelViews = if (channelCfg != null && thread == null) repository.channelViews(chatId, visibleMessages.map { it.id.substringBefore("#al") }.distinct().takeLast(500)) else emptyMap(),
                    channelForwards = if (channelCfg != null && thread == null) repository.channelForwards(chatId, visibleMessages.map { it.id.substringBefore("#al") }.distinct().takeLast(500)) else emptyMap(),
                    messages = visibleMessages,
                    isLoading = false,
                    chatInfo = chatInfo,
                    isGroup = isGroup,
                    isAdmin = amAdmin,
                    canModerate = myRole in MODERATOR_ROLES,
                    memberCount = memberCount,
                    chromeKnown = true,
                    peerId = peerId,
                    peerNpub = peerNpub,
                    peerBlocked = peerBlocked,
                    isMuted = (chatInfo?.mutedUntil ?: 0u) > (System.currentTimeMillis() / 1000L).toULong(),
                    // Comments do not disappear by the channel's timer.
                    chatTimerSeconds = if (thread != null) 0 else effectiveChatTimer(chatId, uiMessages),
                    noStore = repository.chatNoStore(chatId),
                    // Keep the staged "Forwarded" preview alive: only a FRESH staged
                    // batch (or a genuine chat switch) may change it. A re-fire of
                    // loadMessages for the SAME chat (rotation, re-entry) must NOT wipe
                    // an already-staged forward with the now-consumed (empty) buffer.
                    pendingForward = when {
                        staged.isNotEmpty() -> staged
                        switchingChat -> emptyList()
                        else -> _uiState.value.pendingForward
                    },
                    peerDisplayName = peerName,
                    peerAvatarPath = peerAvatar,
                    peerProfiles = voiced(profiles + commenterProfiles(threads, profiles, ownId), voices, chatInfo),
                    channelVoices = voices,
                    adminSenders = if (channelCfg != null) adminSendersOf(chatId) else emptySet(),
                    showNotices = !isGroup || repository.groupNotices(chatId),
                    groupCalls = !isGroup || repository.channelCfg(chatId).calls,
                    groupVideoCalls = !isGroup || repository.channelCfg(chatId).let { it.calls && it.videoCalls },
                    whispers = repository.whispers(chatId),
                    writeRestricted = writeRestricted && thread == null,
                    chatClosedUntilMe = if (thread != null) 0L else closedForMe,
                    removedFromGroup = removed,
                    mutedUntilMe = if (thread != null) 0L else mutedUntil,
                    isRequest = repository.isChatRequest(chatId),
                    ban = if (isGroup) repository.chatBan(chatId) else null,
                ).let { st ->
                    if (isGroup || peerId.isBlank()) st else repository.peerCallRights(peerId)
                        .let { (voice, video) -> st.copy(peerCanCall = voice, peerCanVideo = video) }
                }.let { st -> withSecretState(st, chatId, isGroup, memberCount) }
                startExpirySweep()
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.chat_error_load, e.message ?: "")
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.error_with_reason, e.message ?: "")
                )
            }
        }
    }

    /**
     * Walk the loaded messages, collect every distinct non-self `senderId`
     * and fetch their cached profile via Repository. Returns a flat map
     * for the UI layer to look up bubble headers + avatars.
     */
    private suspend fun resolvePeerProfilesFor(
        messages: List<MessageUi>,
        ownId: String,
    ): Map<String, ChatBubblePeer> {
        val ids = messages.asSequence()
            .map { it.senderId }
            .filter { it.isNotBlank() && it != "self" && it != ownId }
            .toSet()
        return resolveProfiles(ids)
    }

    /** Feed: the latest commenters drawn under the posts (names and photos). */
    /** Who speaks as the chat — one rule for the first load and every refresh. */
    private suspend fun voicesFor(chatId: String, isGroup: Boolean, channelOrDiscussion: Boolean): Set<String> = when {
        channelOrDiscussion -> channelVoicesOf(chatId, ownerOnly = true)
        isGroup && repository.channelCfg(chatId).anon -> channelVoicesOf(chatId, ownerOnly = true)
        else -> emptySet()
    }

    /** Admins of a channel, by sender id: their comments carry the admin badge. */
    private suspend fun adminSendersOf(chatId: String): Set<String> = runCatching {
        val arr = org.json.JSONArray(repository.listGroupMembers(chatId))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("role") == "admin" }
            .map { if (it.optBoolean("is_self", false)) "self" else it.optString("sender_id") }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrDefault(emptySet())

    /** Who speaks as the chat: a channel's owner and admins, or a group's hidden owner. */
    private suspend fun channelVoicesOf(chatId: String, ownerOnly: Boolean = false): Set<String> = runCatching {
        val arr = org.json.JSONArray(repository.listGroupMembers(chatId))
        val voices = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("role") == "owner" || (!ownerOnly && it.optString("role") == "admin") }
        // When WE speak as the channel, our own comments wear its face too
        // (they are stored as "self").
        val selfIsVoice = voices.any { it.optBoolean("is_self", false) }
        (voices.map { it.optString("sender_id") } + if (selfIsVoice) listOf("self") else emptyList())
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrDefault(emptySet())

    /** Every place that names a sender (bubbles, reactions, feed footers, quotes)
     *  reads [ChatState.peerProfiles]: the channel's voices get its face there. */
    private fun voiced(profiles: Map<String, ChatBubblePeer>, voices: Set<String>, info: ChatInfo?): Map<String, ChatBubblePeer> {
        if (voices.isEmpty()) return profiles
        val face = ChatBubblePeer(info?.name.orEmpty(), info?.avatarPath.orEmpty(), "")
        return profiles + voices.associateWith { face }
    }

    private suspend fun commenterProfiles(
        threads: Map<String, com.alphamessenger.ui.channel.ThreadSummary>,
        known: Map<String, ChatBubblePeer>,
        ownId: String,
    ): Map<String, ChatBubblePeer> {
        val ids = threads.values.flatMap { it.senders }.filter { it.isNotBlank() && it !in known }.toSet()
        val out = resolveProfiles(ids.filter { it != "self" && it != ownId }.toSet()).toMutableMap()
        // Our own comments: our own name and photo.
        if ("self" in ids) runCatching { repository.getMyProfile() }.getOrNull()?.let {
            out["self"] = ChatBubblePeer(it.displayName, it.avatarPath, it.npub)
        }
        return out
    }

    private suspend fun resolveProfiles(ids: Set<String>): Map<String, ChatBubblePeer> {
        if (ids.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, ChatBubblePeer>()
        // A member we never exchanged profiles with (a channel subscriber) is
        // still known to the group: its directory carries the name and photo.
        var directory: Map<String, org.json.JSONObject>? = null
        suspend fun member(id: String): org.json.JSONObject? {
            val d = directory ?: runCatching {
                val arr = org.json.JSONArray(repository.listGroupMembers(currentChatId))
                (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.optString("sender_id") }
            }.getOrDefault(emptyMap()).also { directory = it }
            return d[id]
        }
        for (id in ids) {
            val p = runCatching { repository.getPeerProfile(id) }.getOrNull()
            val npub = runCatching { repository.getMemberNpub(id) }.getOrNull().orEmpty()
            var name = p?.displayName.orEmpty()
            var avatar = p?.avatarPath.orEmpty()
            if (name.isBlank() || avatar.isBlank()) member(id)?.let { m ->
                if (name.isBlank()) name = m.optString("display_name")
                if (avatar.isBlank()) avatar = m.optString("avatar_path")
            }
            if (name.isNotBlank() || avatar.isNotBlank() || npub.isNotBlank()) {
                out[id] = ChatBubblePeer(name, avatar, npub)
            }
        }
        return out
    }

    /** Block a group member. The blocklist is keyed by npub, so no 1:1 chat is
     *  needed. */
    fun blockMember(npub: String, name: String, avatarPath: String) {
        if (npub.isBlank()) return
        repository.blockUser(npub, name, avatarPath)
    }

    /**
     * Group member profile opened by tapping an avatar: cover + LIVE presence.
     * Presence is tracked by nostr-hex while a message only carries the MLS
     * sender_id — the hex comes from `memberHex`. Group listeners are already in
     * the presence audience, so the data arrives on its own.
     */
    data class MemberProfile(
        val senderId: String,
        val coverPath: String = "",
        val videoPath: String = "",
        val status: String = "offline",
        val statusText: String = "",
        val statusColor: Int = 0,
        val lastSeen: Long = 0L,
        /** Their privacy rules: may we call them (voice / video)? */
        val canCall: Boolean = true,
        val canVideo: Boolean = true,
        val bio: String = "",
    )

    private val _memberProfile = MutableStateFlow<MemberProfile?>(null)
    val memberProfile: StateFlow<MemberProfile?> = _memberProfile.asStateFlow()
    private var memberProfileJob: Job? = null

    fun openMemberProfile(senderId: String) {
        memberProfileJob?.cancel()
        _memberProfile.value = MemberProfile(senderId)
        if (senderId.isBlank()) return
        memberProfileJob = viewModelScope.launch(Dispatchers.IO) {
            val cover = runCatching { repository.getPeerCoverPath(senderId) }.getOrDefault("")
            val clip = repository.peerAvatarVideo(senderId)
            val (voice, video) = repository.peerCallRights(senderId)
            val bio = runCatching { repository.getPeerProfile(senderId).bio }.getOrDefault("")
            _memberProfile.update { it?.copy(coverPath = cover, videoPath = clip, canCall = voice, canVideo = video, bio = bio) }

            val hex = runCatching { repository.memberHex(senderId) }.getOrDefault("")
            if (hex.isBlank()) return@launch
            readMemberPresence(hex)
            // Opening the profile wakes one poll — like the chat list, so the
            // status is fresh, not whatever the background cycle last saw.
            runCatching { repository.presenceTick() }
            repository.presenceChanged.collect { readMemberPresence(hex) }
        }
    }

    fun closeMemberProfile() {
        memberProfileJob?.cancel()
        memberProfileJob = null
        _memberProfile.value = null
    }

    private suspend fun readMemberPresence(hex: String) {
        val status = repository.presenceStatus(hex)
        val custom = repository.presenceCustomStatus(hex)
        val color = repository.presenceColor(hex).toInt()
        val seen = repository.presenceLastSeen(hex)
        _memberProfile.update {
            it?.copy(status = status, statusText = custom, statusColor = color, lastSeen = seen)
        }
    }

    /**
     * Create (or reuse) a 1:1 chat with the given npub and invoke [onReady]
     * with the resulting chat id on the main thread. Used by the group
     * avatar dialog's "Chat" / "Info" buttons to DM a member.
     */
    /** Where a member's info opens: our own profile for ourselves, their dialog's
     *  page when one exists, else a profile page without a chat. Looking must not
     *  create a chat: that would invite the member without our asking. */
    fun memberInfoTarget(senderId: String, npub: String, onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val own = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
            val target = if (own.isNotBlank() && npub == own) "peer__$npub"
                else runCatching { repository.findDirectChatByNpub(npub) }.getOrNull() ?: "peer_${senderId}_$npub"
            kotlinx.coroutines.withContext(Dispatchers.Main) { onReady(target) }
        }
    }

    private val _mentionCandidates = MutableStateFlow<List<com.alphamessenger.ui.chat.v2.MentionCandidate>>(emptyList())
    /** Group members (not us) offered after "@" in the composer. */
    val mentionCandidates: StateFlow<List<com.alphamessenger.ui.chat.v2.MentionCandidate>> = _mentionCandidates.asStateFlow()

    fun loadMentionCandidates() {
        val chatId = currentChatId
        viewModelScope.launch(Dispatchers.IO) {
            val arr = runCatching { org.json.JSONArray(repository.listGroupMembers(chatId)) }.getOrNull() ?: return@launch
            val fallback = strings.get(R.string.chat_member_fallback)
            _mentionCandidates.value = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("display_name").trim()
                if (o.optBoolean("is_self", false) || name.isBlank() || name == fallback) null
                else com.alphamessenger.ui.chat.v2.MentionCandidate(name, o.optString("avatar_path"))
            }.filter { it.token.isNotBlank() }.distinctBy { it.token }
        }
    }

    fun openDirectChatWith(npub: String, onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val chat = runCatching { repository.addContactByNpub(npub) }.getOrNull()
            if (chat != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onReady(chat.id) }
            }
        }
    }

    /** Load chats that the forward picker can target (all except current). Also resolves
     *  the 1:1 peer's real displayName + avatar (same resolver as ChatList) so the picker
     *  never shows a raw public key. */
    fun loadForwardTargets() {
        viewModelScope.launch(Dispatchers.IO) { _forwardSet.value = loadForwardTargetSet(repository, currentChatId) }
    }

    // Apart from the chat state: its many quick copy-writes (a bot answering) used to drop the list, so the picker opened empty.
    private val _forwardSet = MutableStateFlow<ForwardTargetSet?>(null)
    val forwardSet: StateFlow<ForwardTargetSet?> = _forwardSet

    /** Forward selected messages (by text content) to a target chat. */
    fun forwardMessages(messageIds: List<String>, targetChatId: String) {
        val msgs = _uiState.value.messages.filter { it.id in messageIds && !it.isDeleted }
        // Run on the repository's application-lived scope (NOT viewModelScope):
        // forwarding media re-uploads a blob, which takes a while — if the chat
        // ViewModel is cleared (exit selection / navigate) mid-upload, a
        // viewModelScope job is cancelled ("Job was cancelled") and the forward
        // silently fails. The repo scope survives the screen.
        repository.runDetached {
            for (m in msgs) forwardOne(targetChatId, m)
        }
    }

 /** ONE forward dispatcher for every message type. standard: fully anonymous
     *  (author-less FWD mark only). View-once is never forwarded (blocked at the UI,
     *  and defended here). Returns the local message id (for reply-quote) or null. */
    /** [plain]: sent as one's own message, without the "Forwarded" mark. */
    private fun fwdBody(body: String, plain: Boolean): String =
        if (plain) body.removePrefix(FWD_MARK) else buildForwardBody(body)

    private suspend fun forwardOne(target: String, m: MessageUi, plain: Boolean = false): String? = when {
        m.isViewOnce -> null                                   // one-time media never forwards
        m.albumItems != null -> forwardAlbum(target, m, plain)
        m.isMedia && m.mediaType == "location" -> forwardLocation(target, m)
        m.isMedia && m.mediaType == "link" -> forwardLink(target, m, plain)
        m.isMedia -> forwardOneMedia(target, m, m.mediaCaption.orEmpty(), plain)
        // An assistant's reply leaves without its stored reasoning (only the answer was shown).
        com.alphamessenger.assistant.AssistantMarks.answerOf(m.text).isNotBlank() ->
            runCatching { repository.sendText(target, fwdBody(com.alphamessenger.assistant.AssistantMarks.answerOf(m.text), plain), thread = threadFor(target)) }.getOrNull()
        else -> null
    }

    /** Forward a link-preview message by RE-SENDING the same preview (no blob). The
     *  author-less FWD mark rides the caption so the receiver shows the "Forwarded"
     *  badge. */
    private suspend fun forwardLink(target: String, m: MessageUi, plain: Boolean = false): String? {
        val d = parseLinkPreview(m.mediaRefJson) ?: return null
        val caption = fwdBody(m.mediaCaption.orEmpty(), plain)
        return runCatching {
            // The same picture blob travels on (no upload).
            repository.sendLinkPreview(target, d.url, d.title, d.description, d.image.inlineOnly(), d.site, caption, thread = threadFor(target),
                imageRef = d.imageRef, video = d.video)
        }.getOrNull()
    }

    /** Forward a location message by RE-SENDING the same pin. */
    private suspend fun forwardLocation(target: String, m: MessageUi): String? {
        val d = parseLocation(m.mediaRefJson) ?: return null
        return runCatching {
            repository.sendLocation(target, d.lat, d.lng, d.name, d.address, thread = threadFor(target))
        }.getOrNull()
    }

    /** Forward one media message via the UNIFIED upload pipeline (same as
     *  Photo/Video/File/Voice/Music): persist → enqueue → uploadBlobRefOnly
     *  (suspend, cancellable) → broadcastMedia (suspend, separate publish).
     *  Replaces the legacy sendMediaFile (blocking FFI, upload+publish in one
     *  shot, not cancellable, no TransferEngine tracking, no progress).
     *
     *  The blob key lives in the ref, so re-upload under the new chat is
     *  correct E2EE. Carries the author-less forward mark via buildForwardBody.
     *  Returns the local message id (for the reply-quote), or null on failure. */
    /** A stored MediaRef is REUSABLE for forward when it is a real, self-contained ref
     *  (blob urls / stream chunks / large-media manifest) and NOT the optimistic
     *  pending placeholder. Such a ref already carries the decryption key, download
     *  locations, filename, media_type and poster — everything the target needs. */
    private fun reusableRef(json: String): Boolean = isReusableMediaRef(json)

    /**
     * Publish an ALREADY-UPLOADED MediaRef into [target]. The ref string carries the
     * decryption key, the blob locations, filename, media_type and poster — so this
     * needs NO file, NO re-encryption and NO second upload. The echo is persisted
     * DIRECTLY from that ref (persistOutgoingMediaRef), so the bubble shows its FINAL
     * content from the FIRST frame; only the delivery status advances afterwards.
     * broadcast_media writes the SAME ref and publishes a small MLS event; for
     * saved_messages_local it writes the local ref instead of publishing. ONE path for
     * every target.
     *
     * The publish is DETACHED: the caller (a trailing comment, the next recipient)
     * must never wait on a relay ACK.
     *
     * Used by BOTH re-senders: forward, and Share to several recipients. Returns the
     * local message id, or null if the row could not be persisted (caller falls back
     * to a full upload).
     */
    private suspend fun publishMediaRef(
        target: String,
        ref: String,
        type: String,
        caption: String?,
        ttl: UInt,
        replyTo: String? = null,
    ): String? {
        val msgId = runCatching {
            repository.persistOutgoingMediaRef(target, ref, type, caption, ttl, thread = threadFor(target))
        }.getOrNull() ?: return null
        replyTo?.let { repository.setMediaReply(msgId, it) }
        transfers.onUploadPreparing(msgId)
        if (target == currentChatId) reloadMessages()
        transfers.onUploadPublishing(msgId)
        repository.runDetached {
            val ok = repository.broadcastMedia(target, msgId, ref, type, caption)
            if (ok) { runCatching { repository.deletePendingMedia(msgId) }; transfers.onUploadSent(msgId) }
            else transfers.onUploadFailed(msgId)
        }
        return msgId
    }

    /**
     * Publish ALREADY-UPLOADED album refs into [target] — the album twin of
     * [publishMediaRef]. [echoJson] is the local-file item list the bubble renders
     * instantly; [refsJson] is what the peer receives. No item is re-uploaded.
     */
    private suspend fun publishAlbumRefs(
        target: String,
        refsJson: String,
        echoJson: String,
        caption: String?,
        ttl: UInt,
    ): String? = sendQueue.publishAlbumRefs(target, refsJson, echoJson, caption, ttl)

    /**
     * Arm the fan-out to the other Share recipients.
     *
     * The wait lives on the REPOSITORY's scope, not the screen's. Links appear
     * only after the upload — for videos that takes tens of seconds, and the
     * chat screen can close within that time, which would kill the not-yet-
     * started fan-out with it. Only the outcome arrives here: links ready →
     * fan out; cancelled or broken → the wait is dropped via [ShareFanOut.abandon].
     */
    private fun armShareFanOut(
        targets: List<String>,
        publish: suspend (target: String, refs: String) -> String?,
    ): ShareFanOut? {
        val extras = targets.filter { it != currentChatId && it.isNotBlank() }.distinct()
        if (extras.isEmpty()) return null
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        repository.runDetached {
            val refs = gate.await()
            if (refs == null) {
                android.util.Log.i("AlphaShare", "fan-out cancelled, targets=${extras.size}")
                return@runDetached
            }
            android.util.Log.i("AlphaShare", "fan-out start targets=${extras.size} refs=${refs.length}B")
            for (extra in extras) {
                val id = runCatching { publish(extra, refs) }.getOrNull()
                android.util.Log.i("AlphaShare", "fan-out → $extra: ${id ?: "FAILED"}")
            }
        }
        return ShareFanOut(gate)
    }

    /** Links for the other recipients: either ready, or there will be no fan-out. */
    private class ShareFanOut(private val gate: kotlinx.coroutines.CompletableDeferred<String?>) {
        fun ready(refs: String) { gate.complete(refs) }
        fun abandon() { gate.complete(null) }
    }

    private suspend fun forwardOneMedia(target: String, m: MessageUi, caption: String, plain: Boolean = false): String? {
        val type = m.mediaType ?: "image"
        val cap = fwdBody(caption, plain).trim().takeIf { it.isNotEmpty() }
        val ttl: UInt = _uiState.value.chatTimerSeconds.takeIf { it > 0 }?.toUInt() ?: 0u

        // FAST PATH: reuse the existing self-contained MediaRef — no re-upload.
        // Any failure falls through to the full-upload fallback.
        val existingRef = m.mediaRefJson?.takeIf { reusableRef(it) }
        if (existingRef != null) {
            publishMediaRef(target, existingRef, type, cap, ttl)?.let { return it }
            // persist failed → fall through to the upload path
        }

        // FALLBACK: no reusable ref → full upload (original behaviour). Needs a file.
        val path = m.localMediaPath?.takeIf { java.io.File(it).exists() }
            ?: m.mediaRefJson?.let { runCatching { repository.downloadMediaToCache(it) }.getOrNull() }
        if (path.isNullOrBlank()) return null
        // Step 1: persist the local message row (appears as a sending bubble).
        val msgId = runCatching {
            repository.persistOutgoingMedia(target, path, type, cap, ttl, thread = threadFor(target))
        }.getOrNull() ?: return null
        // Poster + metadata — same extraction as sendMediaOptimistic so the
        // receiver gets the preview + duration badge without downloading.
        val poster = when (type) {
            "video" -> makeVideoPosterB64(path)
            "image" -> makeImagePosterB64(path)
            else -> null
        }
        val meta = displayMeta(path, type)
        val streaming = type == "video"
        // The core queue is armed ONLY on failure — see [queueAfterFailure].
        suspend fun queueForRetry() = queueAfterFailure {
            repository.enqueuePendingMediaSingle(target, msgId, path, type, cap, poster, streaming, ttl, meta)
        }
        // Step 3: drive the TransferEngine lifecycle (Preparing → Encrypting →
        // Uploading → Publishing → Sent|Failed). The overlay reads the snapshot.
        transfers.onUploadPreparing(msgId)
 // standard: echo instant (bubble + upload ring), upload/publish in the bg.
        if (target == currentChatId) reloadMessages()
        // Step 4 + 5: upload blob (suspend, cancellable) then publish (suspend,
        // SEPARATE). CancellationException at the next suspend point →
        // broadcastMedia is never reached → receiver never gets the file.
        // Registered in activeUploadJobs so cancelUpload can cancel — same
        // pattern as forwardAlbum (currentCoroutineContext()[Job]).
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let {
            activeUploadJobs[msgId] = it
        }
        try {
            transfers.onUploadEncrypting(msgId)
            val ref = repository.uploadBlobRefOnly(path, type, poster, msgId, meta)
            if (ref == null) {
                queueForRetry()
                transfers.onUploadFailed(msgId)
                return msgId
            }
            transfers.onUploadPublishing(msgId)
            val ok = repository.broadcastMedia(target, msgId, ref, type, cap)
            if (ok) {
                repository.deletePendingMedia(msgId)
                transfers.onUploadSent(msgId)
            } else {
                queueForRetry()
                transfers.onUploadFailed(msgId)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Throwable) {
            queueForRetry()
            transfers.onUploadFailed(msgId)
        } finally {
            activeUploadJobs.remove(msgId)
        }
        return msgId
    }

    /** Forward an album as ONE grouped album message (not scattered singles).
     *  Re-materializes each item to a local file, then runs the SAME album
     *  pipeline as sendMediaBatch (persist → parallel upload → sendAlbum) for
     *  [target], so the receiver folds it back into a single album bubble.
     *  Returns the local album message id (for the reply-quote), or null. */
    private suspend fun forwardAlbum(target: String, album: MessageUi, plain: Boolean = false): String? {
        val items = album.albumItems ?: return null

        // FAST PATH: every item already has a reusable self-contained ref → reuse
        // them, NO re-upload. sendAlbum publishes the album manifest from the existing
        // item refs and writes it locally (same pipeline as a normal album send). The
        // echo carries poster/local_path/aspect so tiles render instantly. Fallback to
        // the per-item upload below if ANY item lacks a reusable ref.
        val reuseRefs = items.map { it.mediaRefJson?.takeIf { r -> reusableRef(r) } }
        if (reuseRefs.all { it != null }) {
            val ttl0: UInt = _uiState.value.chatTimerSeconds.takeIf { it > 0 }?.toUInt() ?: 0u
            val cap0 = fwdBody(album.mediaCaption.orEmpty(), plain).trim().takeIf { it.isNotEmpty() }
            val echoJson = org.json.JSONArray().apply {
                items.forEach { item ->
                    put(org.json.JSONObject().apply {
                        item.localMediaPath?.takeIf { java.io.File(it).exists() }?.let { put("local_path", it) }
                        put("media_type", item.mediaType ?: "image")
                        item.mediaPosterB64?.takeIf { it.isNotBlank() }?.let { put("poster_b64", it) }
                        (item.mediaDurationMs ?: 0L).takeIf { it > 0 }?.let { put("duration_ms", it) }
                        (item.mediaWidth ?: 0).takeIf { it > 0 }?.let { put("width", it) }
                        (item.mediaHeight ?: 0).takeIf { it > 0 }?.let { put("height", it) }
                        (item.mediaRotation ?: 0).takeIf { it != 0 }?.let { put("rotation", it) }
                    })
                }
            }.toString()
            val refsJson = "[" + reuseRefs.filterNotNull().joinToString(",") + "]"
            publishAlbumRefs(target, refsJson, echoJson, cap0, ttl0)?.let { return it }
            // persist failed → fall through to the per-item upload below
        }

        // FALLBACK: re-upload each item (original behaviour).
        data class Prep(val path: String, val type: String, val meta: com.alphamessenger.ui.chat.AlbumItemMetadata?)
        // PARALLEL metadata extraction — N videos = 1× time, not N×.
        // Each item runs on its own IO coroutine; extractImage (BitmapFactory)
        // is used for images so MediaMetadataRetriever isn't paid for JPEGs.
        val preps = kotlinx.coroutines.coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    val type = item.mediaType ?: "image"
                    val path = item.localMediaPath?.takeIf { java.io.File(it).exists() }
                        ?: item.mediaRefJson?.let { runCatching { repository.downloadMediaToCache(it) }.getOrNull() }
                    path?.takeIf { java.io.File(it).exists() }?.let {
                        val meta = if (type == "video") com.alphamessenger.ui.chat.AlbumItemMetadata.extract(it, "video")
                                   else com.alphamessenger.ui.chat.AlbumItemMetadata.extractImage(it)
                        Prep(it, type, meta)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (preps.isEmpty()) return null
        val itemsJson = org.json.JSONArray().apply {
            preps.forEach { p ->
                put(org.json.JSONObject().apply {
                    put("local_path", p.path); put("media_type", p.type)
                    p.meta?.let { meta ->
                        meta.toJson().keys().forEach { key ->
                            put(key, meta.toJson().get(key))
                        }
                    }
                })
            }
        }.toString()
        val ttl: UInt = _uiState.value.chatTimerSeconds.takeIf { it > 0 }?.toUInt() ?: 0u
        val cap = fwdBody(album.mediaCaption.orEmpty(), plain).trim().takeIf { it.isNotEmpty() }
        val msgId = repository.persistOutgoingAlbum(target, itemsJson, cap, ttl, thread = threadFor(target)) ?: return null
        // Drive the transfer lifecycle for the forwarded album too — same
        // states as sendMediaBatch so the receiver-side overlay is consistent.
        // Single blob → indeterminate from the start (the per-item step only
        // fires after the blob finishes, so a determinate value would freeze).
        transfers.onUploadEncrypting(msgId)
 // standard: echo instant, per-item upload/publish in the bg.
        if (target == currentChatId) reloadMessages()
        // The core queue — only on failure: see [queueAfterFailure].
        suspend fun queueForRetry() = queueAfterFailure {
            repository.enqueuePendingMediaAlbum(target, msgId, itemsJson, cap, ttl)
        }
        val totalFwd = preps.size
        val doneFwd = java.util.concurrent.atomic.AtomicInteger(0)
        val okRefs = kotlinx.coroutines.coroutineScope {
            val sem = kotlinx.coroutines.sync.Semaphore(4)
            preps.mapIndexed { i, p ->
                async {
                    sem.withPermit {
                        var ref: String? = null; var attempt = 0
                        while (ref == null && attempt < 3) {
                            ref = repository.uploadBlobRefOnly(p.path, p.type, p.meta?.poster, "$msgId#$i")
                            if (ref == null) { attempt++; delay(400L * attempt) }
                        }
                        if (ref != null) {
                            doneFwd.incrementAndGet()
                        }
                        // Carry the item metadata to the receiver (duration badge).
                        ref?.let { mergeAlbumMeta(it, p.meta) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (okRefs.isEmpty()) {
            queueForRetry()
            transfers.onUploadFailed(msgId)
            return null
        }
        transfers.onUploadPublishing(msgId)
        val refsJson = "[" + okRefs.joinToString(",") + "]"
        var ok = false; var attempt = 0
        while (!ok && attempt < 5) {
            ok = repository.sendAlbum(target, msgId, refsJson, cap, ttl)
            if (!ok) { attempt++; delay(500L * attempt) }
        }
        if (ok) {
            repository.deletePendingMedia(msgId)
            transfers.onUploadSent(msgId)
            viewModelScope.launch {
                kotlinx.coroutines.delay(1500)
            }
        } else {
            queueForRetry()
            transfers.onUploadFailed(msgId)
        }
        return msgId
    }

 /** classic forward: send the staged messages INTO the current chat and,
     *  if [comment] is non-blank, send it as a reply quoting the forwarded message.
     *  Order: forwarded message(s) first, then the comment quoting the last one. */
    fun sendForward(comment: String) {
        val msgs = _uiState.value.pendingForward
        if (msgs.isEmpty()) return
        val target = currentChatId
        val text = comment.trim()
        // Clear the preview + input immediately for a snappy UX.
        _uiState.value = _uiState.value.copy(pendingForward = emptyList(), inputText = "")
        // The comment was persisted as a draft while typing (setDraftAsync), so
        // clearing only the in-memory inputText would keep the chat list showing
        // "Draft" and re-entering would restore the already-sent text. Clear the
        // persisted draft too — same as the normal send path.
        viewModelScope.launch(Dispatchers.IO) { runCatching { repository.clearDraft(target) } }
        repository.runDetached {
            var lastForwardedId: String? = null
            var lastSnippet = ""
            for (m in msgs) {
                forwardOne(target, m)?.let { lastForwardedId = it }
                lastSnippet = if (m.albumItems != null || m.isMedia) mediaLabel(m) else m.text.take(120)
            }
            if (text.isNotEmpty()) {
                val replyId = lastForwardedId
                if (replyId != null) {
                    runCatching { repository.sendReply(target, replyId, text, lastSnippet, thread = threadFor(target)) }
                } else {
                    runCatching { repository.sendText(target, text, thread = threadFor(target)) }
                }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { reloadMessages() }
        }
    }

    /** Cancel a staged forward (✕ on the preview). */
    fun cancelForward() {
        _uiState.value = _uiState.value.copy(pendingForward = emptyList())
    }

    /** Multi-recipient forward (new picker): send the selected messages to EVERY chosen
     *  target (user / group / Saved) via the shared forwardOne pipeline (reuse, no
     *  re-upload), then an optional comment as a trailing text. Fully anonymous. */
    fun forwardToTargets(messageIds: List<String>, targetIds: List<String>, comment: String, plain: Boolean = false) {
        val msgs = _uiState.value.messages.filter { it.id in messageIds && !it.isDeleted }
        if (msgs.isEmpty() || targetIds.isEmpty()) return
        // Forwarded out of a channel: the channel counts it (never learns where to).
        if (_uiState.value.channel != null) {
            val from = currentChatId
            val posts = msgs.map { it.id.substringBefore("#al") }.distinct()
            viewModelScope.launch(Dispatchers.IO) { repository.channelForwarded(from, posts) }
        }
        val text = comment.trim()
        repository.runDetached {
            for (target in targetIds) {
                for (m in msgs) forwardOne(target, m, plain)
                if (text.isNotEmpty()) runCatching { repository.sendText(target, text, thread = threadFor(target)) }
            }
            // One of the targets may be the chat we're viewing → refresh it.
            kotlinx.coroutines.withContext(Dispatchers.Main) { reloadMessages() }
        }
    }

    /** Join a group via an invite link tapped in chat. */
    fun joinViaLink(link: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val err = repository.sendJoinRequest(link)
            val byRequest = com.alphamessenger.ui.chat.parseInvite(link).byRequest
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                when {
                    err == null -> onResult(strings.get(if (byRequest) R.string.join_request_sent_owner else R.string.join_joining))
                    // Used / expired one-time link → in-app dialog (not a toast).
                    err.contains("недействительна", ignoreCase = true) ->
                        _uiState.value = _uiState.value.copy(
                            joinDialog = strings.get(R.string.join_expired_title) to
                                strings.get(R.string.join_expired_text),
                        )
                    // Already a member (e.g. the owner tapping their own link).
                    err.contains("уже участник", ignoreCase = true) ->
                        _uiState.value = _uiState.value.copy(
                            joinDialog = strings.get(R.string.join_already_title) to
                                strings.get(R.string.join_already_text),
                        )
                    else -> onResult(strings.get(R.string.error_failed_reason, err))
                }
            }
        }
    }

    fun dismissJoinDialog() {
        _uiState.value = _uiState.value.copy(joinDialog = null)
    }

    /** Forward selected messages into Saved Messages. */
    fun forwardToSaved(messageIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { repository.ensureSavedMessagesChat() }.getOrNull() ?: return@launch
            forwardMessages(messageIds, saved.id)
        }
    }

    /** Bulk delete selected messages. */
    fun deleteMessages(messageIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (id in messageIds) runCatching { repository.sendDelete(currentChatId, id) }
            reloadMessages()
        }
    }

    private var reloadJob: Job? = null
    private fun reloadMessages(coalesce: Boolean = true) {
        if (currentChatId.isBlank()) return
        // Coalesce reload bursts: a flood of incoming messages/presence would
        // otherwise fire get_messages+list per event, hammering the MLS lock/DB.
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(Dispatchers.IO) {
            if (coalesce) kotlinx.coroutines.delay(80)
            try {
                // Scheduled messages of this chat (only when it has any — a cheap
                // check; the full list is read on open and after every change).
                if (repository.held.value[currentChatId].orEmpty().isNotEmpty()) repository.refreshHeld()
                val messages = fetchWindow(currentChatId)
                fetchedRows = messages.size
                val ownId = _uiState.value.ownId
                val pendingIds = repository.pendingSendIds()
                val uiMessages = messages.foldToBubbles(ownId, messageLabels).markWhispers(repository.whisperIds(currentChatId), strings.get(R.string.whisper_title)).let { mergeCallLog(it) }.withRunFlags().groupAlbums().applyPendingSending(pendingIds)
                seedPendingUploads(uiMessages, pendingIds)
                registerAlbumGroups(uiMessages)
                reconcileFinishedUploads(uiMessages)
                // Clean up stale TransferEngine snapshots for messages that no
                // longer exist.
                transfers.retainOnly(transferKeysOf(uiMessages))
                // Refresh member count too — after a successful add_member,
                // the realtime listener applies the Commit and the count
                // bumps; without re-reading here the subtitle stays stale.
                val memberCount = try {
                    repository.chatMemberCount(currentChatId)
                } catch (_: Exception) { _uiState.value.memberCount }

                // Late-resolve peer id once a first inbound message arrives —
                // when the chat was opened with no history yet, peerId was
                // empty; now we can finally bind to the peer's profile.
                val cur = _uiState.value
                val (resolvedPeerId, resolvedName, resolvedAvatar) = if (!cur.isGroup && cur.peerId.isBlank()) {
                    val pid = repository.resolvePeerSenderId(currentChatId, ownId)
                    if (pid.isNotBlank()) {
                        val p = runCatching { repository.getPeerProfile(pid) }.getOrNull()
                        Triple(pid, p?.displayName.orEmpty(), p?.avatarPath.orEmpty())
                    } else Triple("", cur.peerDisplayName, cur.peerAvatarPath)
                } else Triple(cur.peerId, cur.peerDisplayName, cur.peerAvatarPath)

                // Hide a blocked peer's incoming messages received after the block.
                val blockedAtSec = if (cur.peerBlocked && cur.peerNpub.isNotBlank())
                    repository.blockedAtFor(cur.peerNpub) else 0L
                val visibleMessages = uiMessages.hideBlockedIncoming(cur.peerBlocked, blockedAtSec)

                val profiles = resolvePeerProfilesFor(visibleMessages, ownId)

                // Detect "removed/banned": for a group, if our own leaf is no
                // longer among the members (the MLS group was cleaned after a
                // remove-Commit evicted us), we can't post → show the read-only
                // strip instead of the composer.
                val removed = if (cur.isGroup) {
                    // Persistent flag (set on self-removal, cleared on genuine
                    // re-add) is authoritative so the notice ALWAYS shows; OR with
                    // the live check as a fast path.
                    val persisted = repository.isRemovedFromGroup(currentChatId)
                    val live = runCatching {
                        val arr = org.json.JSONArray(repository.listGroupMembers(currentChatId))
                        (0 until arr.length()).none { arr.getJSONObject(it).optBoolean("is_self", false) }
                    }.getOrDefault(false)
                    val unavailable = repository.isGroupUnavailable(currentChatId)
                    persisted || live || unavailable
                } else false
                val mutedUntil = if (cur.isGroup && ownId.isNotBlank())
                    runCatching { repository.groupMuteUntil(currentChatId, ownId) }.getOrDefault(0L)
                else 0L
                val (writeRestricted, closedForMe) = computeWriteState(currentChatId, cur.isGroup, removed)

                val discussion = cur.discussion?.let { d -> discussionOf(currentChatId, ownId, d.cfg) }
                val threads = if (thread == null && cur.channel != null) threadSummaries(currentChatId) else cur.threads
                val voices = voicesFor(currentChatId, cur.isGroup, cur.channel != null || cur.discussion != null)
                listStamp = ConversationSnapshotCache.stamp()
                _uiState.value = _uiState.value.copy(
                    messages = visibleMessages.withKnownStatus(),
                    memberCount = memberCount,
                    chromeKnown = true,
                    peerId = resolvedPeerId,
                    // A secret chat's peer is a persona with no profile: keep the
                    // ordinary chat's name and photo.
                    peerDisplayName = if (cur.secretOrigin.isNotEmpty()) cur.peerDisplayName else resolvedName,
                    peerAvatarPath = if (cur.secretOrigin.isNotEmpty()) cur.peerAvatarPath else resolvedAvatar,
                    peerProfiles = voiced(profiles + commenterProfiles(threads, profiles, ownId), voices, cur.chatInfo),
                    channelVoices = voices,
                    adminSenders = if (cur.channel != null || cur.discussion != null) adminSendersOf(currentChatId) else emptySet(),
                    showNotices = !cur.isGroup || repository.groupNotices(currentChatId),
                    groupCalls = !cur.isGroup || repository.channelCfg(currentChatId).calls,
                    groupVideoCalls = !cur.isGroup || repository.channelCfg(currentChatId).let { it.calls && it.videoCalls },
                    whispers = repository.whispers(currentChatId),
                    removedFromGroup = removed,
                    mutedUntilMe = if (thread != null) 0L else mutedUntil,
                    writeRestricted = writeRestricted && thread == null,
                    chatClosedUntilMe = if (thread != null) 0L else closedForMe,
                    isAdmin = cur.isGroup && isGroupAdmin(currentChatId),
                    canModerate = cur.isGroup && myGroupRole(currentChatId) in MODERATOR_ROLES,
                    // Shared disappearing timer: store is source of truth; a control
                    // notice (from either party) updates it. Display is separate.
                    chatTimerSeconds = if (thread != null) 0 else effectiveChatTimer(currentChatId, uiMessages),
                    discussion = discussion ?: cur.discussion,
                    threads = threads,
                    isRequest = repository.isChatRequest(currentChatId),
                    ban = if (cur.isGroup) repository.chatBan(currentChatId) else null,
                    peerCanCall = if (cur.isGroup || resolvedPeerId.isBlank()) true else repository.peerCallRights(resolvedPeerId).first,
                    peerCanVideo = if (cur.isGroup || resolvedPeerId.isBlank()) true else repository.peerCallRights(resolvedPeerId).second,
                )
            } catch (_: Exception) {
                // Silent reload failure
            }
        }
    }

    // --- Input / reply state ---

    // True while the user is editing an already-sent message. Editing reuses the
    // same input field, but it must NOT emit "typing" to the peer (and the edit
    // itself must not push) — it's not a new outgoing message.
    private var editingMode = false
    fun setEditing(editing: Boolean) { editingMode = editing }

    fun updateInput(text: String) {
        val prev = _uiState.value.inputText
        _uiState.value = _uiState.value.copy(inputText = text)
        if (!editingMode) refreshComposerPreview(text)
        // Persist as a draft so it survives leaving the chat (shows "Draft" in
        // the list). Encrypted + debounced in the repository. Not while editing.
        if (!editingMode && thread == null) repository.setDraftAsync(currentChatId, text)
        if (editingMode) return            // don't broadcast typing while editing
        if (text.isBlank()) {
            // Field cleared → stop typing immediately.
            if (prev.isNotBlank()) stopTyping()
        } else {
            maybeSendTyping()
        }
    }

    // --- Composer link preview ---

    private var composerPreviewJob: kotlinx.coroutines.Job? = null
    /** The composer link's preview in flight (picture upload included); Send awaits it. */
    private var composerPreviewTask: Pair<String, kotlinx.coroutines.Deferred<LinkPreviewData?>>? = null

    /** The ready card's full picture going up as a blob, by URL. */
    private var composerPictureTask: Pair<String, kotlinx.coroutines.Deferred<LinkPreviewData>>? = null

    private fun cancelComposerPreviewTask() {
        composerPreviewJob?.cancel()
        composerPreviewTask?.second?.cancel()
        composerPreviewTask = null
        composerPictureTask?.second?.cancel()
        composerPictureTask = null
    }
    /** URL whose preview the user closed with ✕ — sent without a card. */
    private var dismissedPreviewUrl: String? = null

    /** Where a preview's full picture waits until it is sent. */
    private fun linkPictureDir() = java.io.File(appContext.cacheDir, "link_previews")

    /** The preview's full picture uploaded as an encrypted blob (the small copy alone if that fails). */
    private suspend fun withPictureRef(d: LinkPreviewData): LinkPreviewData {
        if (d.imageRef.isNotBlank() || d.fullPath.isBlank() || !java.io.File(d.fullPath).exists()) return d
        val ref = repository.uploadBlobRefOnly(d.fullPath, "image", null, "lp_${System.currentTimeMillis()}") ?: return d
        return d.copy(imageRef = ref)
    }

    private fun linkPreviewViaTor(): Boolean =
        appContext.getSharedPreferences("alpha_network_privacy", android.content.Context.MODE_PRIVATE)
            .getBoolean("tor_enabled", false)

    private fun linkPreviewsOn(): Boolean =
        appContext.getSharedPreferences("alpha_network_privacy", android.content.Context.MODE_PRIVATE)
            .getBoolean("link_previews", true)

    private fun refreshComposerPreview(text: String) {
        // A secret chat fetches nothing about what is typed in it; an assistant chat neither
        // (its card would upload the picture, and the service reads the link itself).
        val url = extractFirstUrl(text)?.takeIf { linkPreviewsOn() && _uiState.value.secretOrigin.isEmpty() && !com.alphamessenger.assistant.isAssistantChat(currentChatId) }
        val st = _uiState.value
        if (url == null || url == dismissedPreviewUrl) {
            cancelComposerPreviewTask()
            if (st.composerPreviewUrl != null || st.composerPreviewLoading) {
                _uiState.value = st.copy(composerPreview = null, composerPreviewUrl = null, composerPreviewLoading = false)
            }
            return
        }
        if (url == st.composerPreviewUrl) return
        cancelComposerPreviewTask()
        _uiState.value = st.copy(composerPreviewUrl = url, composerPreview = null, composerPreviewLoading = true)
        val task = viewModelScope.async(Dispatchers.IO) {
            delay(400) // a typed link may still be growing
            com.alphamessenger.ui.chat.preview.LinkPreviewFetcher.fetch(url, viaTor = linkPreviewViaTor(), dir = linkPictureDir())
        }
        composerPreviewTask = url to task
        composerPreviewJob = viewModelScope.launch {
            val data = runCatching { task.await() }.getOrNull()
            if (_uiState.value.composerPreviewUrl != url) return@launch
            // The card shows at once; its full picture goes up meanwhile, so a
            // send right away does not wait for the upload.
            _uiState.value = _uiState.value.copy(composerPreview = data, composerPreviewLoading = false)
            if (data != null) {
                val up = viewModelScope.async(Dispatchers.IO) { withPictureRef(data) }
                composerPictureTask = url to up
                val withRef = runCatching { up.await() }.getOrNull() ?: return@launch
                if (_uiState.value.composerPreviewUrl == url) {
                    _uiState.value = _uiState.value.copy(composerPreview = withRef)
                }
            }
        }
    }

    /** ✕ on the composer card: this link is sent as plain text. */
    fun dismissComposerPreview() {
        dismissedPreviewUrl = _uiState.value.composerPreviewUrl
        cancelComposerPreviewTask()
        _uiState.value = _uiState.value.copy(composerPreview = null, composerPreviewUrl = null, composerPreviewLoading = false)
    }

    private fun resetComposerPreview() {
        cancelComposerPreviewTask()
        dismissedPreviewUrl = null
    }

    /** Throttled "typing" emit + auto-stop scheduler. */
    private fun maybeSendTyping() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSentMs > 5000) {
            lastTypingSentMs = now
            emitChatState("typing")
        }
        typingStopJob?.cancel()
        typingStopJob = viewModelScope.launch {
            delay(5000)
            stopTyping()
        }
    }

    private fun stopTyping() {
        typingStopJob?.cancel()
        typingStopJob = null
        lastTypingSentMs = 0L
        emitChatState("paused")
    }

    private fun emitChatState(state: String) {
        if (!privacyPrefs.typingIndicators.value) return
        // A secret chat says nothing about when we type: each signal would be one
        // more event through Tor, marking when we are active.
        if (_uiState.value.secretOrigin.isNotEmpty()) return
        val id = currentChatId
        if (id.isBlank()) return
        // Never broadcast an ACTIVE presence ("typing"/"recording") once the
        // chat screen is backgrounded or navigated away. The recording duration
        // ticker keeps running for the local UI and would otherwise re-emit
        // "recording" every 3s into a chat the user already left — leaving the
        // peer's "recording…"/"typing…" stuck forever. "paused" is always
        // allowed through so the peer's indicator clears promptly.
        if (state != "paused" && !screenActive) return
        // In a discussion: seen by the people in that discussion only.
        viewModelScope.launch(Dispatchers.IO) { repository.sendChatState(id, state, thread) }
    }

    /**
     * Split a text too big for ONE message at paragraph / line / word
     * boundaries: at most [MAX_MESSAGE_CHARS] characters per bubble. The byte
     * bound guards the wire: the core accepts up to 32 000 bytes, and after
     * padding and encryption a message grows ~1.4× into its relay event, so
     * 30 000 bytes stays under the relays' 64 KiB event limit.
     */
    private fun splitLongText(text: String, limitBytes: Int = MAX_TEXT_BYTES, limitChars: Int = MAX_MESSAGE_CHARS): List<String> {
        fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size
        fun fits(s: String) = s.length <= limitChars && bytes(s) <= limitBytes
        if (fits(text)) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (!fits(rest)) {
            // The longest prefix that fits (never cutting a surrogate pair).
            var end = 0
            var used = 0
            while (end < rest.length) {
                val cp = rest.codePointAt(end)
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (used + n > limitBytes || end + Character.charCount(cp) > limitChars) break
                used += n
                end += Character.charCount(cp)
            }
            val window = rest.substring(0, end)
            val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf('\n'), window.lastIndexOf(' '))
                .firstOrNull { it > end / 2 } ?: end
            out += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    // --- Scheduled messages (held by the core until their time) ---

    /** The next message sent in this chat goes out at [atMillis]. */
    fun armSilent() {
        if (currentChatId.isNotBlank()) repository.armSilent(currentChatId)
    }

    fun armSchedule(atMillis: Long) {
        if (currentChatId.isNotBlank()) repository.armSchedule(currentChatId, atMillis)
    }

    fun sendHeldNow(id: String) = viewModelScope.launch { repository.sendHeldNow(id); reloadMessages() }

    fun rescheduleHeld(id: String, atMillis: Long) = viewModelScope.launch { repository.rescheduleHeld(id, atMillis) }

    fun cancelHeld(id: String) = viewModelScope.launch { repository.cancelHeld(id); reloadMessages() }

    /** Reply typed in the media viewer: sent at once, the composer draft is untouched. */
    fun sendQuickReply(target: MessageUi, text: String, onDone: (Boolean) -> Unit = {}) {
        val body = text.trim()
        val chatId = currentChatId
        if (body.isEmpty() || chatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                repository.sendReply(chatId, target.id.substringBefore("#al"), body, replyQuoteSnippet(target), thread = threadFor(chatId))
            }.isSuccess
            if (ok) reloadMessages()
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun setReplyTo(message: MessageUi?, quote: String? = null) {
        _uiState.value = _uiState.value.copy(
            replyTo = message,
            replyQuote = if (message == null) null else quote?.trim()?.takeIf { it.isNotEmpty() }?.take(QUOTE_MAX),
        )
    }

    /** The open reply, taken by a media send: the bar closes as the message goes out. */
    private fun takeReplyId(): String? {
        val r = _uiState.value.replyTo ?: return null
        _uiState.value = _uiState.value.copy(replyTo = null, replyQuote = null)
        return r.id.substringBefore("#al")
    }

    /** Build the quoted-snippet text for a reply. Uses the message text when
     *  present; for media (no text) returns a typed label so the quote reads
     *  "🎵 Music" / "📷 Photo" etc. instead of a blank "Message". */
    private fun replyQuoteSnippet(m: MessageUi): String {
        // A fragment chosen in "Select text" is what the reply answers.
        _uiState.value.replyQuote?.takeIf { _uiState.value.replyTo?.id == m.id }?.let { return it }
        if (m.isSticker) return listOfNotNull(m.stickerEmoji, strings.get(R.string.sticker_label)).joinToString(" ")
        val t = m.text.removePrefix(FWD_MARK).trim()
        if (t.isNotEmpty()) return t.take(120)
        if (m.mediaType == "file") m.mediaFilename?.takeIf { it.isNotBlank() }?.let { return it }
        return mediaLabel(m)
    }

    /** Typed media label (shared by quote + preview). Albums inspect their items
     *  so a video album reads "Video", a photo album "Photo", mixed "Media". */
    private fun mediaLabel(m: MessageUi): String = when {
        m.isVoiceNote || m.isVoice -> strings.get(R.string.chat_reply_voice)
        m.mediaType == "album" -> {
            val items = m.albumItems.orEmpty()
            val allVideo = items.isNotEmpty() && items.all { it.mediaType == "video" }
            val allImage = items.isNotEmpty() && items.all { it.mediaType == "image" }
            when {
                allVideo -> strings.get(R.string.chat_reply_video)
                allImage -> strings.get(R.string.chat_reply_photo)
                else -> strings.get(R.string.chat_reply_media)
            }
        }
        m.mediaType == "poll" -> "📊 " + (com.alphamessenger.ui.chat.poll.PollSpec.parse(m.mediaRefJson)?.question ?: strings.get(R.string.poll_label))
        m.mediaType == "image" -> strings.get(R.string.chat_reply_photo)
        m.mediaType == "video" -> strings.get(R.string.chat_reply_video)
        m.mediaType == "audio" -> strings.get(R.string.chat_reply_music)
        m.mediaType == "file" -> strings.get(R.string.chat_reply_file)
        m.isMedia -> strings.get(R.string.chat_reply_attachment)
        else -> strings.get(R.string.msg_fallback_title)
    }

    // --- Media sending ---


    /**
 * classic optimistic media send:
     *  1. persist the message instantly with the LOCAL file path → the bubble
     *     appears immediately, rendered from the local file (no download);
     *  2. upload to Blossom in the background, reporting % via mediaProgress;
     *  3. swap media_ref for the real ref + broadcast to peers.
     * The local file is kept as the sender's cache (clearable in settings).
     */
    /**
     * Arm the core-queue retry — ONLY after a failure.
     *
     * The core queue has its own sweeper: every twelve seconds it takes rows and
     * sends them itself. Arming a row BEFORE the upload would give one message
     * two independent senders — both honestly working: double traffic, double
     * battery, and the peer receiving the message twice.
     *
     * The queue stays what it was meant to be: a safety net. If the send cannot
     * go through now, the row is laid down and the sweeper delivers it when
     * network returns. While a send is in flight it has exactly one owner.
     *
     * A cancel never lands here: a cancelled message has nothing left to send.
     */
    /** Enqueue into the core queue after a failure.
     *
     *  Runs in [NonCancellable]: it is a suspending call invoked at the moment
     *  the sending job is already dying; without the guard it would throw the
     *  cancellation at the first suspension point and enqueue NOTHING — in the
     *  one case it exists for. User cancels are unaffected (the cancellation
     *  exception is rethrown above), and a user cancel cleans the queue
     *  separately. */
    private suspend fun queueAfterFailure(enqueue: suspend () -> Unit) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { enqueue() }
        }
    }

    private suspend fun sendMediaOptimistic(
        localPath: String,
        mediaType: String,
        caption: String?,
        asFile: Boolean = false,
        /** Other Share recipients — see [sendMediaBatch]. */
        alsoTo: List<String> = emptyList(),
        /** The message this one answers (marked before it goes out). */
        replyTo: String? = null,
    ) {
        // The send source stays where it was: `cacheDir/send_<time>_<name>`.
        //
        // Protect the PHASE, not the location: an active send is touched by no
        // one, and once finished the copy is disposable again. This is handled in
        // [clearMediaCache] by asking the queue.
        val fileSize = runCatching { java.io.File(localPath).length() }.getOrDefault(0L)
        val limit = AttachmentLimits.limitForMediaType(mediaType)
        if (fileSize > limit) {
            _uiState.value = _uiState.value.copy(
                error = strings.get(
                    R.string.attachment_limit_with_current,
                    strings.get(
                        AttachmentLimits.humanReadableLimitForMediaType(mediaType),
                        limit / (1024 * 1024),
                    ),
                    fileSize / (1024 * 1024),
                ),
                showAttachPanel = false,
            )
            return
        }
        // Only EXPLICIT per-message ephemeral / view-once routes through the
        // ephemeral pipeline (user tapped 👁 / Secret). The per-chat disappearing
        // timer does NOT reroute media here: that path renders the sender's bubble
        // by downloading from Blossom (no local_path) and intermittently failed
        // with "failed to load" when Blossom lagged. Chat-timer media keeps
        // the reliable optimistic pipeline below (local-file render); disappearing
        // for media under the chat timer will be reworked with local_path support.
        val st = _uiState.value
        if (st.isViewOnce || st.isEphemeral) {
            val expiryMode = if (st.isViewOnce) "view_once" else "after_send"
            val ttl: UInt = if (st.isViewOnce) 0u
                else (EPHEMERAL_TIMER_OPTIONS.find { it.label == st.ephemeralTimer }?.seconds ?: 3600).toUInt()
            sendEphemeralMedia(localPath, mediaType, expiryMode, ttl, caption?.trim()?.takeIf { it.isNotEmpty() })
            return
        }
        val cap = caption?.trim()?.takeIf { it.isNotEmpty() }
        // "As file": send uncompressed as a document but keep a thumbnail poster
        // generated from the original image/video.
        val sendType = if (asFile) "file" else mediaType
        val ttl: UInt = _uiState.value.chatTimerSeconds.takeIf { it > 0 }?.toUInt() ?: 0u
        // The other Share recipients wait for the link separately from this screen.
        val fanOut = armShareFanOut(alsoTo) { target, ref ->
            publishMediaRef(target, ref, sendType, cap, ttl)
        }
        val persisted = runCatching {
            repository.persistOutgoingMedia(currentChatId, localPath, sendType, cap, ttl, thread = threadFor(currentChatId))
        }
        val msgId = persisted.getOrNull()
        if (msgId != null && replyTo != null) repository.setMediaReply(msgId, replyTo)
        if (msgId == null) {
            fanOut?.abandon()
            // The rules refused it (a comment of a forbidden kind, a restriction): say which.
            _uiState.value = _uiState.value.copy(
                error = com.alphamessenger.ui.channel.WriteStatus.fromError(persisted.exceptionOrNull()?.message)?.text()
                    ?: strings.get(R.string.chat_media_send_failed),
            )
            return
        }
        stopTyping()
        // Transfer lifecycle: Preparing → Uploading → (Sent|Failed). Set the upload
        // state BEFORE reloadMessages() so the bubble's first frame already shows the
        // upload ring (onUploadPreparing publishes immediately) — no ▶ flash before it.
        transfers.onUploadPreparing(msgId)
        reloadMessages()                         // bubble appears (ring + "sending")
        _uiState.value = _uiState.value.copy(showAttachPanel = false)
        val poster = when (mediaType) {
            "video" -> makeVideoPosterB64(localPath)
            "image" -> makeImagePosterB64(localPath)
            else -> null
        }
        val meta = displayMeta(localPath, mediaType)
        val streaming = sendType == "video"
        // The core queue is a failure safety net, not a second sender: see
        // [queueAfterFailure]. Documents go through exactly this path.
        val chatForQueue = currentChatId
        suspend fun queueForRetry() = queueAfterFailure {
            repository.enqueuePendingMediaSingle(
                chatForQueue, msgId, localPath, sendType, cap, poster, streaming, ttl, meta,
            )
        }
        // UNIFIED UPLOAD PIPELINE — same as album: uploadBlobRefOnly (suspend,
        // cancellable) + broadcastMedia (suspend, SEPARATE publish step). With
        // two separate suspend steps, job.cancel() throws CancellationException
        // at the NEXT suspension point (between chunks inside uploadBlobRefOnly,
        // or before broadcastMedia if upload finished) → publish NEVER runs →
        // the receiver NEVER gets the file.
        // Application-lived, like the album queue: leaving the chat or swiping the
        // app away must not cancel an upload under way.
        val uploadJob = repository.detachedScope.launch(Dispatchers.IO) {
            try {
                // Cancel or screen death before the link is ready — drop the wait.
                if (fanOut != null) {
                    kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion { fanOut.abandon() }
                }
                // The intent is recorded BEFORE the first sent byte.
                //
                // If the process is killed mid-upload, no failure handler ever
                // runs — the bubble would keep its clock forever. The queue row
                // created here is what the core's sweeper picks up after a
                // restart. While the process lives, the "already sending" flag
                // makes the core queue refuse the row (no second sender); when the
                // process dies, the flag dies with it and the row becomes claimable
                // by exactly the party that should take it.
                queueAfterFailure {
                    repository.enqueuePendingMediaSingle(
                        chatForQueue, msgId, localPath, sendType, cap, poster, streaming, ttl, meta,
                    )
                }
                transfers.onUploadEncrypting(msgId)
                // Step 1: upload the blob (suspend, cancellable).
                val ref = repository.uploadBlobRefOnly(localPath, sendType, poster, msgId, meta)
                if (ref == null) {
                    queueForRetry()
                    transfers.onUploadFailed(msgId)
                    return@launch
                }
                // Link ready — the fan-out to the others proceeds on its own.
                fanOut?.ready(ref)
                // Link diagnostics: NO SECRETS.
                //
                // Logs must not carry the media key or the full ref — device logs
                // are readable by other apps and land in bug reports, and the
                // key+blob-url pair is enough to decrypt the attachment.
                //
                // Only what shows the link was assembled stays: hash presence,
                // url count, pending flag. The hash is cut to 8 chars — enough to
                // correlate two log lines, not enough for anything else.
                runCatching {
                    if (!com.alphamessenger.BuildConfig.DEBUG) return@runCatching
                    val j = org.json.JSONObject(ref)
                    val hashPrefix = j.optString("hash").takeIf { it.isNotBlank() }?.take(8)
                    val urlCount = j.optJSONArray("blob_urls")?.length()
                        ?: if (j.optString("blob_urls").isNotBlank()) 1 else 0
                    android.util.Log.d(
                        "AlphaMediaRef",
                        "post-send msgId=$msgId type=$sendType pending=${j.optBoolean("pending", false)} " +
                            "hash=${hashPrefix ?: "<НЕТ>"}… urls=$urlCount hasKey=${j.has("key")}",
                    )
                }.onFailure { }
                // Step 2: publish the MLS message (suspend, cancellable, SEPARATE).
                // If the user cancelled during step 1, this line is NEVER reached.
                transfers.onUploadPublishing(msgId)
                val ok = repository.broadcastMedia(currentChatId, msgId, ref, sendType, cap)
                if (ok) {
                    repository.deletePendingMedia(msgId)
                    transfers.onOutgoingTransferPublished(msgId)
                } else {
                    queueForRetry()
                    transfers.onUploadFailed(msgId)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // User cancelled — cancelUpload already set snapshot to Cancelled
                // and cleaned up. Don't overwrite with Failed.
                throw ce
            } catch (_: Throwable) {
                // Real error (network, FFI, etc).
                queueForRetry()
                transfers.onUploadFailed(msgId)
            } finally {
                activeUploadJobs.remove(msgId)
                reloadMessages()
            }
        }
        activeUploadJobs[msgId] = uploadJob
    }

    /**
     * Cancel an in-progress upload.
     * Stops the coroutine (interrupts between suspend points), removes the
     * message from the pending queue, and deletes the local bubble so the UI
     * is clean. The underlying FFI call (native code) cannot be force-stopped
     * mid-chunk, but no further chunks will be attempted after cancellation.
     */
    /** User tapped "Retry" on a permanently-failed media send. Flips the
     *  parked pending-queue row back to active and re-drains it. */
    fun retryPendingMedia(msgId: String) {
        // A queue-owned send has its ready files and edits, so retry continues
        // from where it stopped instead of starting over.
        if (sendQueue.owns(msgId)) {
            sendQueue.retry(msgId)
            return
        }
        // Guard: if an upload job is ALREADY active for this msgId, don't
        // start a second one. The user might double-tap Retry, or the 12s
        // flushPendingMedia loop might race with the manual retry.
        if (activeUploadJobs.containsKey(msgId)) {
            return
        }
        transfers.onUploadEncrypting(msgId)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = runCatching { repository.retryPendingMedia(msgId) }.getOrDefault(false)
                if (ok) transfers.onUploadSent(msgId) else transfers.onUploadFailed(msgId)
            } finally {
                activeUploadJobs.remove(msgId)
                reloadMessages()
            }
        }
    }

    fun cancelUpload(msgId: String) {
        // A queue-owned send: ✕ must abort file PREPARATION too, not just the
        // upload — otherwise re-encoding keeps heating the phone for a message
        // that no longer exists.
        if (sendQueue.owns(msgId)) {
            sendQueue.cancel(msgId)
            return
        }
        // 0. CORE — first and synchronously. Cancelling the coroutine cannot
        //    interrupt work running inside FFI: the call is synchronous, bytes
        //    kept flowing, and at the end the message was even published — hence
        //    "pressed ✕ but the file went anyway" and a returned clock bubble.
        //    The core checks this flag between chunks and before publishing.
        repository.cancelMediaUpload(msgId)
        // 1. Engine: snapshot → Cancelled (immediate, UI drops overlay).
        transfers.dispatch(
            com.alphamessenger.transfer.TransferKey.upload(msgId),
            com.alphamessenger.transfer.TransferAction.Cancel,
        )
        // 2. Cancel the coroutine Job SYNCHRONOUSLY (before DB cleanup), so no
        //    in-flight uploadBlobRefOnly or broadcastMedia can complete after we
        //    delete the pending row. Cancel the Job FIRST, then clean up in the
        //    background — otherwise the 12s flushPendingMedia loop could pick
        //    the row up again.
        activeUploadJobs.remove(msgId)?.cancel()
        // 3. Clean up DB + snapshot in background (after Job is cancelled).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deletePendingMedia(msgId) }
            runCatching { repository.deleteMessageLocal(msgId) }
            reloadMessages()
        }
    }

    /** Extract a small JPEG poster from a video file, base64-encoded (≤~40KB).
     *  Embedded in the message so the receiver shows a preview instantly. */
    private fun makeVideoPosterB64(path: String): String? = try {
        val r = android.media.MediaMetadataRetriever()
        com.alphamessenger.data.MediaVault.setSource(r, path)
        val frame = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        r.release()
        frame?.let { bm ->
            val maxEdge = 320
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(bm.width, bm.height).coerceAtLeast(1))
            val small = if (scale < 1f)
                android.graphics.Bitmap.createScaledBitmap(bm, (bm.width * scale).toInt().coerceAtLeast(1), (bm.height * scale).toInt().coerceAtLeast(1), true)
            else bm
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            if (small !== bm) small.recycle(); bm.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        android.util.Log.w("ChatViewModel", "poster extract failed: ${e.message}"); null
    }

    /** What only the sender can measure (duration, size, a voice note's bars),
     *  shipped inside the ref: the blob is E2EE, so no receiver can probe it. */
    private suspend fun displayMeta(path: String, type: String): String? = when (type) {
        "video" -> com.alphamessenger.ui.chat.AlbumItemMetadata.extract(path, "video")
        "image" -> com.alphamessenger.ui.chat.AlbumItemMetadata.extractImage(path)
        "audio" -> com.alphamessenger.ui.chat.AlbumItemMetadata.extract(path, "audio")?.let { m ->
            if (!File(path).name.startsWith("voice")) m
            else VoiceWaveform.forSending(appContext.cacheDir, path)?.let { m.copy(waveform = it) } ?: m
        }
        else -> null
    }?.toJson()?.toString()

    /** Extract video duration in ms via MediaMetadataRetriever — used by the
     *  SENDER to embed duration_ms in the album itemsJson so the RECEIVER can
     *  show the duration badge immediately (without downloading the file). */
    private fun extractVideoDurationMs(path: String): Long = try {
        val r = android.media.MediaMetadataRetriever()
        com.alphamessenger.data.MediaVault.setSource(r, path)
        val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    } catch (e: Exception) { 0L }

    /** Make a small JPEG thumbnail (base64) from an already-prepared image file,
     *  so the receiver can preview the photo instantly (in-chat + notifications)
     *  without downloading the full blob — same idea as the video poster. */
    private fun makeImagePosterB64(path: String): String? = try {
        val bm = com.alphamessenger.data.MediaVault.decodeFile(path)
        bm?.let {
            val maxEdge = 320
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(it.width, it.height).coerceAtLeast(1))
            val small = if (scale < 1f)
                android.graphics.Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1), (it.height * scale).toInt().coerceAtLeast(1), true)
            else it
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            if (small !== it) small.recycle(); it.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        android.util.Log.w("ChatViewModel", "image poster failed: ${e.message}"); null
    }

    /** Decode the picked image, downscale to ≤1600px on the long edge and
     *  re-encode as JPEG q80 into a cache file ready for upload. */
    private fun prepareImageForSend(
        uri: android.net.Uri,
        maxEdge: Int = 1600,
        jpegQuality: Int = 80,
    ): File? {
        return try {
            val src = appContext.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: return null
            // Read EXIF orientation (a SEPARATE stream — decodeStream consumed
            // the first one). The camera writes orientation as EXIF metadata
            // instead of physically rotating pixels; BitmapFactory ignores it,
            // so without applying it the photo sends rotated 90°/180°.
            val rotationDeg = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { exifStream ->
                    when (android.media.ExifInterface(exifStream).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL,
                    )) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            }.getOrDefault(0f)
            val scale = minOf(
                1f,
                maxEdge.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1),
            )
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    src, (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1), true,
                )
            } else src
            // Bake the EXIF rotation into the pixels so the receiver (and our
            // re-encoded JPEG, which drops EXIF) shows it upright.
            val upright = if (rotationDeg != 0f) {
                val m = android.graphics.Matrix().apply { postRotate(rotationDeg) }
                android.graphics.Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
            } else scaled
            val out = File(appContext.cacheDir, "img_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { os ->
                upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, os)
            }
            if (upright !== scaled) upright.recycle()
            if (scaled !== src) scaled.recycle()
            src.recycle()
            out
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "prepareImageForSend failed", e)
            null
        }
    }

    /** Save one or more media items (a single bubble or a whole album) into the
     *  device gallery (Pictures/Movies → AlphaMessenger). Materializes the full
     *  decrypted file first (handles streaming video by assembling chunks). */
    fun saveMediaToGallery(items: List<MessageUi>) {
        viewModelScope.launch(Dispatchers.IO) {
            var toGallery = 0
            var toDownloads = 0
            for (m in items) {
                val type = m.mediaType ?: "image"
                if (type !in SAVEABLE_MEDIA) continue
                val ref = m.mediaRefJson ?: continue
                val path = m.localMediaPath?.takeIf { File(it).exists() }
                    ?: repository.materializeMedia(ref)
                    ?: continue
                // Photos and videos go to the gallery, everything else to Downloads.
                if (type == "image" || type == "video") {
                    if (com.alphamessenger.util.GallerySaver.save(appContext, File(path), type == "video", m.mediaFilename)) toGallery++
                } else if (com.alphamessenger.util.GallerySaver.saveFile(appContext, File(path), m.mediaFilename)) toDownloads++
            }
            _uiState.value = _uiState.value.copy(
                error = when {
                    toGallery > 0 && toDownloads > 0 -> strings.get(R.string.chat_saved_mixed_count, toGallery + toDownloads)
                    toDownloads > 0 -> strings.get(R.string.chat_saved_to_downloads_count, toDownloads)
                    toGallery > 0 -> strings.get(R.string.chat_saved_to_gallery_count, toGallery)
                    else -> strings.get(R.string.chat_save_gallery_failed)
                }
            )
        }
    }

    /** Download+decrypt a media blob into cache, returning the local path.
     *  Used by media bubbles to render the photo. */
    suspend fun loadMedia(mediaRefJson: String): String? =
        repository.downloadMediaToCache(mediaRefJson)

    /** Fast synchronous check: returns the already-decrypted local path if the
     *  blob is on disk, else null — never downloads. Lets the UI avoid a stale
     *  "tap to download" badge over media that's actually already cached. */
    fun cachedMediaPath(mediaRefJson: String?): String? =
        repository.cachedMediaPathIfPresent(mediaRefJson)

    /** Like [loadMedia] but reports byte-level progress via [mediaProgress]
     *  The TERMINAL state is set by [TransferEngine] (it owns the session and
     *  sees this function's result), so nothing here writes transfer state. */
    suspend fun loadMediaTracked(mediaRefJson: String, opId: String): String? {
        val path = repository.downloadMediaToCache(mediaRefJson, opId)
        if (path != null) {
            // Cache the path in-memory so the next open is instant (no disk lookup,
            // no re-download).
            com.alphamessenger.ui.chat.MediaPathCache[mediaRefJson] = path
        }
        return path
    }

    /** Byte-level download progress stream: (messageId, downloaded, total). */
    val mediaProgress get() = repository.mediaProgress

    /** SENDER-side transfer lifecycle — single source of truth for the upload
     *  overlay. Rust does NOT emit `mediaProgress` during uploads (only
     *  downloads), so we drive the UI from REAL pipeline events here:
     *  Preparing → Uploading(progress) → Publishing → Sent / Failed.
     *  See [TransferState] for the full rationale. */
    // The Unified Transfer Engine is the SOLE writer of transfer state.
    //
    // Everything (upload lifecycle, download sessions, policy, progress, speed,
    // ETA, retries, album groups) lives in the engine. The ViewModel does not
    // compute any of it — it only forwards user intent and pipeline events.
    //

    // The engine lives in a process @Singleton (see di/TransferModule) so
    // transfer state survives chat navigation; `transfers` is an injected
    // constructor param.

    /** Immutable snapshots the migrated UI reads. */
    val transferSnapshots = transfers.snapshots

    /** Album groups (aggregate progress / ETA / Cancel / Retry). */
    val transferGroups = transfers.groups

    // Register the auto-download invalidation observers AFTER `transfers` is
    // initialized — NetworkCallback.onAvailable can fire immediately on a
    // binder thread, so the engine must be ready.
    init {
        appContext.getSharedPreferences(
            com.alphamessenger.data.MediaAutoDownload.PREFS, Context.MODE_PRIVATE,
        ).registerOnSharedPreferenceChangeListener(autoDownloadPrefsListener)
        (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
            ?.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), networkCallback)
    }

    // NOTE: mediaProgress is collected by TransferEngine — exactly once, in its
    // init block. Nothing else may feed it: double samples make the upload %
    // jitter.

    /**
     * An album becomes a first-class [com.alphamessenger.transfer.TransferGroup]:
     * its members are the per-item DOWNLOAD transfers (each item is its own blob),
     * while the album's UPLOAD is a single transfer keyed by the head message.
     *
     * Registering the group is what gives the album aggregate progress / speed /
     * ETA and a working Cancel-album, Retry-album and Pause-album — the UI just
     * calls `dispatchGroup(headId, action)`; it never loops over messages.
     */
    /** Byte size of a media blob from its ref JSON (`original_size`), for album
     *  aggregate totals when the item's mediaSizeBytes metadata is missing. */
    private fun mediaRefSize(refJson: String?): Long = refJson?.let {
        runCatching { org.json.JSONObject(it).optLong("original_size", 0L) }.getOrDefault(0L)
    } ?: 0L

    /**
     * Bring back on screen the sends that outlived the process (the process
     * died; the queue rows did not).
     *
     * The send queue lives in the DB; transfer state lives in memory. Kill the
     * app mid-upload and after a restart the core honestly resumes from the
     * checkpoint — but the screen would show a clock and NO indicator, having
     * no other way to know about the transfer.
     *
     * The source is the same queue that drives the bubble clock, not a guess
     * from message traits. Seeded as "queued"; within seconds the core says
     * what the row is actually doing: "sending" or "waiting for network".
     */
    /**
     * "Delete from cache" as a whole, including the decision about the
     * sender's own copy.
     *
     * Lives here, not on the screen, for one reason: deciding whether the
     * sender's copy may be touched means asking the queue, which is DB + FFI —
     * not askable from the main thread. The orchestration runs in the
     * background and the screen receives the final byte count.
     *
     * An active send is untouched: while its row sits in the queue — uploading,
     * waiting for network or awaiting retry — its source is needed by the
     * resume. Once finished, the same copy is disposable again and MUST be
     * deletable.
     */
    fun clearMediaCache(msg: MessageUi, onDone: (Long) -> Unit) {
        val items = msg.albumItems ?: listOf(msg)
        val pairs = items.mapNotNull { item -> item.mediaRefJson?.let { it to item.id } }
        if (pairs.isEmpty()) { onDone(0L); return }
        viewModelScope.launch {
            val freed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val pending = runCatching { repository.pendingSendIds() }.getOrDefault(emptySet())
                pairs.sumOf { (ref, id) ->
                    transfers.deleteCacheOf(
                        listOf(com.alphamessenger.transfer.TransferKey.download(ref)),
                        deleteLocalCopy = id !in pending,
                    )
                }
            }
            onDone(freed)
        }
    }

    private fun seedPendingUploads(messages: List<MessageUi>, pendingIds: Set<String>) {
        if (pendingIds.isEmpty()) return
        for (m in messages) {
            if (!m.isOutgoing || !m.isMedia) continue
            if (m.id !in pendingIds) continue
            transfers.seedQueuedIfUnknown(m.id)
        }
    }

    /**
     * The transfer keys of [messages] (up:<id>, dn:<ref>, album up:<head>#<i>) —
     * what the engine keeps. Bare message ids matched none of them: the engine
     * then dropped every idle file's per-key flow, a bubble kept a flow nobody
     * updated, and its ✕ / cache delete showed only after scrolling it away and back.
     */
    private fun transferKeysOf(messages: List<MessageUi>): Set<String> = buildSet {
        messages.forEach { m ->
            add(com.alphamessenger.transfer.TransferKey.upload(m.id).value)
            m.mediaRefJson?.let { add(com.alphamessenger.transfer.TransferKey.download(it).value) }
            m.albumItems?.forEachIndexed { i, item ->
                add(com.alphamessenger.transfer.TransferKey.upload("${m.id}#$i").value)
                item.mediaRefJson?.let { add(com.alphamessenger.transfer.TransferKey.download(it).value) }
            }
        }
    }

    /**
     * A published row (final ref, no send job) whose transfer is still shown as
     * in flight lost its end report: finish it from the row. Publishing is left
     * to its own report.
     */
    private fun reconcileFinishedUploads(messages: List<MessageUi>) {
        for (m in messages) {
            if (!m.isOutgoing || m.mediaPending) continue
            if (m.mediaRefJson == null && m.albumItems.isNullOrEmpty()) continue
            if (sendQueue.owns(m.id)) continue
            val st = transfers.stateOf(com.alphamessenger.transfer.TransferKey.upload(m.id))
            val stuck = st is com.alphamessenger.transfer.TransferState.Uploading ||
                st is com.alphamessenger.transfer.TransferState.Encrypting ||
                st is com.alphamessenger.transfer.TransferState.Preparing ||
                st is com.alphamessenger.transfer.TransferState.Queued
            if (!stuck) continue
            android.util.Log.w("ChatViewModel", "UPLOAD_RECONCILE ${m.id.take(8)}: published row, transfer was ${st::class.simpleName}")
            transfers.onOutgoingTransferPublished(m.id)
        }
    }

    private fun registerAlbumGroups(messages: List<MessageUi>) {
        for (m in messages) {
            val items = m.albumItems ?: continue
            // --- What gets registered as a download here ---
            //
            // One rule, and it is NOT about message direction: local copy exists
            // → use it; none → download. FILE presence is checked, not the
            // instantaneous transfer state. The decision is per FILE and per
            // item — one missing album item must not drag the others that are on
            // disk, and a blanket "never download your own" would break old
            // media once cache cleanup removes the local file.
            // --- OWNERSHIP — the first and main question ---
            //
            // While the send queue owns the message, it is NOT downloadable in
            // any sense. Otherwise the just-appeared bubble (ref in the manifest,
            // local files not ready yet) gets registered as a DOWNLOAD group — a
            // download icon on our own queued message and two denominators in one
            // cell. Ownership is ON-DISK state (a queue row), so it survives a
            // restart unlike in-memory transfer state; it is released exactly
            // when the send finishes.
            if (sendQueue.owns(m.id)) continue

            // --- Group membership is a MESSAGE fact, not a disk fact ---
            //
            // A member is an item that has a remote ref — an immutable trait of
            // the message. Whether the bytes are on disk is the member's STATE:
            // computed outside composition, refreshed immediately on cache
            // deletion. All members ready → no icon; bytes gone → the icon
            // appears that instant. The "sender never downloads their own set"
            // guard is the queue-ownership check above, not this filter.
            val withRef = items.filter { it.mediaRefJson != null }
            val members = withRef.map { com.alphamessenger.transfer.TransferKey.download(it.mediaRefJson!!) }
            // Per-item size: metadata first, else parsed from the ref's original_size.
            // A stable per-member size = stable Σ denominator + completed items counted.
            val sizes = withRef.map { it.mediaSizeBytes?.takeIf { s -> s > 0L } ?: mediaRefSize(it.mediaRefJson) }
            if (members.isNotEmpty()) transfers.registerGroup(
                m.id, members,
                totalBytes = sizes.sum(),
                memberSizes = sizes,
            )
            // Auto-download policy for EVERY item — not just the ≤4 VISIBLE tiles.
            // The tiles fire consider() from their own LaunchedEffect, but items
            // 5+ are collapsed under "+N" and never composed, so they never
            // auto-downloaded → the group stuck at 4/5 and a ↓ badge came back.
            // consider() is idempotent (guards Completed/active/paused/evicted).
            if (!_uiState.value.isRequest) withRef.forEach { item ->
                item.mediaRefJson?.let { ref ->
                    transfers.consider(
                        com.alphamessenger.transfer.TransferKey.download(ref),
                        item.mediaType, item.mediaFilename, item.mediaSizeBytes ?: 0L,
                        item.isOutgoing, item.localMediaPath, item.id,
                    )
                }
            }
        }
    }

    /** Album-wide action (Cancel / Retry / Pause / Resume) — one engine call. */
    /**
     * An album's ✕ must behave exactly like a single bubble's: an aborted SEND
     * leaves nothing behind. Stopping the transfer but keeping the bubble is the
     * "I cancelled it and the file is still hanging there" bug — the group only
     * ever stopped the engine and never touched the message.
     *
     * A cancelled DOWNLOAD keeps its bubble, of course: the message is the peer's,
     * not ours, and only the fetch was abandoned.
     */
    /**
     * The message is still in flight (preparing or uploading) — cancellable.
     *
     * The queue is asked, not the transfer engine: the engine's live state is
     * all in memory and does not survive an app restart, while the work sits
     * on disk.
     */
    /** Sends what is still queued (texts under the clock) now, not at the next pass. */
    fun resendNow() {
        viewModelScope.launch { repository.replayOutbox() }
    }

    fun sendInFlight(msgId: String): Boolean =
        sendQueue.stateOf(msgId).let {
            it == com.alphamessenger.ui.media.send.MediaSendState.QUEUED ||
                it == com.alphamessenger.ui.media.send.MediaSendState.PROCESSING ||
                it == com.alphamessenger.ui.media.send.MediaSendState.UPLOADING
        } || activeUploadJobs[msgId]?.isActive == true

    /**
     * Our own attachment that has NOT gone out yet — by any of the senders.
     *
     * Asking one queue is not enough: documents bypass it (the screen leads
     * them; the core takes over on a connection break), and both questions
     * would answer "no" for such a message — opening the full menu (forward,
     * edit caption, save, react) over a file the receiver does not have yet.
     * So the final trait comes from the message itself: outgoing, media, still
     * sending. It does not depend on who carries the bytes and survives a
     * restart, coming from the DB.
     */
    private fun notSentYet(msgId: String): Boolean =
        _uiState.value.messages.firstOrNull { it.id == msgId }
            ?.let { it.isOutgoing && it.isMedia && it.status == MessageStatus.SENDING } == true

    /**
     * Retry is offered ALWAYS until the message has gone out — not only after
     * a failure. The user need not distinguish "stuck waiting for network"
     * from "stuck because it failed": they see nothing happening and want to
     * press the button. If work is already running, the queue says so in words
     * instead of staying silent.
     */
    fun sendFailed(msgId: String): Boolean = sendQueue.owns(msgId) || notSentYet(msgId)

    fun dispatchAlbum(headMsgId: String, action: com.alphamessenger.transfer.TransferAction) {
        // The queue is asked FIRST, by the message's persistent key.
        //
        // The engine's live state is all in memory; after an app restart it
        // has no members at all, so ↻ on an interrupted album would silently do
        // NOTHING. The work sits on disk and knows nothing of the restart — ask it.
        if (sendQueue.owns(headMsgId)) {
            transfers.dispatchGroup(headMsgId, action)
            when (action) {
                com.alphamessenger.transfer.TransferAction.Cancel -> cancelUpload(headMsgId)
                com.alphamessenger.transfer.TransferAction.Retry -> retryPendingMedia(headMsgId)
                else -> Unit
            }
            return
        }
        val isOutgoingUpload = transfers.groupOf(headMsgId).items.any { it.isUpload }
        transfers.dispatchGroup(headMsgId, action)
        if (!isOutgoingUpload) return
        when (action) {
            com.alphamessenger.transfer.TransferAction.Cancel -> cancelUpload(headMsgId)
            // ↻ on an album must restart the work, not just reset the ring —
            // the send continues from where it stopped; that is the queue's job.
            com.alphamessenger.transfer.TransferAction.Retry -> retryPendingMedia(headMsgId)
            else -> Unit
        }
    }

    /**
     * The bubble's ENTIRE window onto the engine — one parameter, read-only state
     */
    val transferUi = com.alphamessenger.transfer.TransferUi(
        snapshots = transfers.snapshots,
        groups = transfers.groups,
        flowFor = { key -> transfers.flowFor(key) },
        consider = { key, mt, fn, sz, out, local, opId ->
            // A message owned by the send queue is not a download. The check
            // sits HERE, in the single policy entry point, so no bubble can
            // bypass it: `opId` is the message id, ownership is decided by it.
            // A request (not accepted yet) downloads nothing by itself: a
            // stranger's media waits for a tap.
            if (!sendQueue.owns(opId) && !_uiState.value.isRequest) {
                transfers.consider(key, mt, fn, sz, out, local, opId)
            }
        },
        dispatch = { key, action, opId -> transfers.dispatch(key, action, opId) },
        dispatchGroup = { gid, action -> transfers.dispatchGroup(gid, action) },
    )

    /** Send a picked content:// URI (video / audio / document) as media. The
     *  bytes are copied to a cache file (keeping the original display name so
     *  the receiver sees a sensible filename), then uploaded+encrypted. */
    /** Send a location card (lat/lng + reverse-geocoded name/address). */
    /** Send a poll made in "New poll" (its JSON). */
    fun sendPoll(json: String) {
        if (currentChatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.sendPoll(currentChatId, json, thread = threadFor(currentChatId)) }
                .onFailure { _uiState.value = _uiState.value.copy(error = strings.get(R.string.poll_send_failed)) }
            reloadMessages()
        }
    }

    fun sendLocation(lat: Double, lng: Double, name: String, address: String) {
        if (currentChatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.sendLocation(currentChatId, lat, lng, name, address, thread = threadFor(currentChatId)) }
                .onFailure { _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_location_send_failed)) }
            reloadMessages()
        }
    }

    fun sendMediaUri(
        uri: android.net.Uri,
        mediaType: String,
        caption: String? = null,
        /** Other Share recipients — see [sendMediaBatch]. */
        alsoTo: List<String> = emptyList(),
    ) {
        if (currentChatId.isBlank()) return
        if (!passesAttachmentCheck(uri, mediaType)) return   // early: no copy / message / upload
        viewModelScope.launch(Dispatchers.IO) {
            val file = copyUriToCache(uri)
            if (file == null) {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.attach_reject_title_unreadable))
                return@launch
            }
            // Picking an image/video via "File" → send as a document but with a
            // thumbnail preview (as-file + poster from the original).
            if (mediaType == "file") {
                val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull().orEmpty()
                val visual = when {
                    mime.startsWith("image/") -> "image"
                    mime.startsWith("video/") -> "video"
                    else -> null
                }
                if (visual != null) {
                    sendMediaOptimistic(file.absolutePath, visual, caption, asFile = true, alsoTo = alsoTo)
                    return@launch
                }
            }
            sendMediaOptimistic(file.absolutePath, mediaType, caption, alsoTo = alsoTo)
        }
    }

    /**
     * Validate a whole selection BEFORE anything is copied, persisted or sent.
     *
     * All or nothing. It used to keep the files that passed and send them, so a
     * selection with one oversized video quietly delivered the other two and then
     * showed an error — the user was told the send had failed while it was
     * happening. A batch is one action: if any file in it cannot be sent, none of
     * it is sent, and the user decides what to drop.
     *
     * Returns the URIs to send, or an empty list if the selection is rejected.
     */
    private fun filterValidOrReject(items: List<Pair<android.net.Uri, String>>): List<android.net.Uri> {
        var firstReject: AttachmentRejectionUi? = null
        for ((uri, type) in items) {
            val r = com.alphamessenger.config.AttachmentLimits.validateUri(appContext, uri, type)
            if (r != null && firstReject == null) {
                firstReject = AttachmentRejectionUi(type, r.kind, r.selectedBytes, r.limitBytes)
            }
        }
        if (firstReject != null) {
            _uiState.value = _uiState.value.copy(attachmentRejection = firstReject, showAttachPanel = false)
            return emptyList()
        }
        return items.map { it.first }
    }

    /** Merge an item's [AlbumItemMetadata] (duration/width/height/rotation/mime/
     *  size) into an uploaded ref JSON so the RECEIVER can render the duration
     *  badge + correct aspect without downloading the blob. Never overwrites the
     *  ref's own keys (hash/key/blob urls/poster); best-effort — returns the
     *  original ref unchanged on any parse error. */
    private fun mergeAlbumMeta(ref: String, meta: com.alphamessenger.ui.chat.AlbumItemMetadata?): String {
        if (meta == null) return ref
        return runCatching {
            val o = org.json.JSONObject(ref)
            val m = meta.toJson()
            m.keys().forEach { k ->
                // Don't clobber a key the ref already carries (e.g. poster_b64).
                if (!o.has(k)) o.put(k, m.get(k))
            }
            o.toString()
        }.getOrDefault(ref)
    }

    /**
     * Send a set of attachments.
     *
     * The ORDER here is the point. The message is created FIRST, before any
     * file preparation, and from that second the bubble exists on screen:
     * poster, correct height, work ring. Preparation (crop, rotation,
     * compression), upload and publish happen afterwards, unbound from the
     * screen — handled by [com.alphamessenger.ui.media.send.MediaSendQueue].
     *
     * Every mature messenger does it this way for one reason: 4K transcode
     * time does not depend on us, but waiting for the bubble does. The fast
     * case (a photo with no edits) goes the same way — its preparation stage
     * is just short. There is deliberately no separate "simple" branch.
     */
    fun sendDraftMedia(
        drafts: List<com.alphamessenger.ui.media.draft.MediaDraftItem>,
        caption: String?,
        photoMaxEdge: Int = 1600,
        photoQuality: Int = 80,
        /** Other Share recipients: the same blobs go to them as refs, no second upload. */
        alsoTo: List<String> = emptyList(),
        /** A timed view: each photo or video goes alone, shown for this long (0 = until closed). */
        glanceSecs: Int? = null,
    ) {
        if (currentChatId.isBlank() || drafts.isEmpty()) return
        // Limits are checked on the SOURCES and before the message is inserted:
        // reject earlier than a bubble appears that would have to be removed.
        val validSet = filterValidOrReject(drafts.map { it.uri to it.sendType }).toSet()
        val checked = drafts.filter { it.uri in validSet }
        if (checked.isEmpty()) return
        val cap = caption?.trim()?.takeIf { it.isNotEmpty() }
        val ttl: UInt = _uiState.value.chatTimerSeconds.takeIf { it > 0 }?.toUInt() ?: 0u
        val chatId = currentChatId
        _uiState.value = _uiState.value.copy(showAttachPanel = false)
        val replyTo = takeReplyId()
        stopTyping()
        // Submission goes on the app scope: the screen can close the same
        // second, and the message must still appear and go out.
        repository.detachedScope.launch(Dispatchers.IO) {
            // A comment: ask the core first — a forbidden kind must not be
            // prepared and uploaded at all, and the user is told why.
            thread?.let { post ->
                for (kind in checked.map { if (it.isVideo) "video" else "photo" }.distinct()) {
                    val st = repository.writeStatus(chatId, post, kind)
                    if (!st.ok) { _uiState.value = _uiState.value.copy(error = st.text()); return@launch }
                }
            }
            // The whole submission is one call. The "job → message → closure"
            // order lives inside the queue — it is the protection against a
            // "message exists but nobody remembers its work" state.
            com.alphamessenger.ui.media.export.MediaQuality.load(appContext)
            val hd = com.alphamessenger.ui.media.export.MediaQuality.hd
            // A timed view is one picture per message: what it shows is what burns.
            val sets = if (glanceSecs != null) checked.filter { it.isVideo || it.isImage }.map { listOf(it) } else listOf(checked)
            var msgId: String? = null
            for ((i, set) in sets.withIndex()) {
                msgId = sendQueue.submit(
                    chatId = chatId,
                    drafts = set,
                    caption = cap.takeIf { i == 0 },
                    // A timed view's own row has no timer: it burns once seen.
                    ttlSecs = if (glanceSecs != null) 0 else ttl.toInt(),
                    // HD lifts photos too; a camera's own choice (a higher value) stays.
                    photoMaxEdge = maxOf(photoMaxEdge, com.alphamessenger.ui.media.export.MediaQuality.photoEdge(hd)),
                    photoQuality = maxOf(photoQuality, com.alphamessenger.ui.media.export.MediaQuality.photoQuality(hd)),
                    hd = hd,
                    videoEdge = com.alphamessenger.ui.media.export.MediaQuality.videoEdge,
                    alsoTo = if (glanceSecs != null) emptyList() else alsoTo,
                    thread = threadFor(chatId),
                    glanceSecs = glanceSecs,
                    replyTo = replyTo.takeIf { i == 0 },
                ) ?: break
            }
            if (msgId == null) {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_media_send_failed))
                return@launch
            }
            if (chatId == currentChatId) reloadMessages()
        }
    }

    // Copying lives in AttachmentCache — Share receive uses the same mechanism
    // so the name and sanitizing never diverge between the two paths.
    private fun copyUriToCache(uri: android.net.Uri): File? =
        AttachmentCache.copyToCache(appContext, uri)

    private fun queryDisplayName(uri: android.net.Uri): String? =
        AttachmentCache.displayName(appContext, uri)

    // --- Ephemeral / view-once ---

    fun setEphemeral(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            isEphemeral = enabled,
            isViewOnce = if (enabled) false else _uiState.value.isViewOnce
        )
    }

    fun setViewOnce(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            isViewOnce = enabled,
            isEphemeral = if (enabled) false else _uiState.value.isEphemeral
        )
    }

    fun setEphemeralTimer(timerOption: EphemeralTimerOption) {
        _uiState.value = _uiState.value.copy(
            ephemeralTimer = timerOption.label,
            isEphemeral = timerOption.seconds > 0,
            showEphemeralSheet = false
        )
    }

    fun setShowEphemeralSheet(show: Boolean) {
        _uiState.value = _uiState.value.copy(showEphemeralSheet = show)
    }

    // --- Send message — dispatches to correct FFI method ---

    /**
     * [alsoTo] — the other Share recipients: the same text goes to them via
     * the same core calls. THIS chat's properties (reply, view-once,
     * disappearing timer) do not carry over: they are chat settings, not text
     * settings.
     */
    fun sendMessage(alsoTo: List<String> = emptyList()) {
        val text = _uiState.value.inputText
        if (text.isBlank()) {
            return
        }
        // The composer preview, if any, is reused: no second request to the site.
        val readyPreviewUrl = _uiState.value.composerPreviewUrl
            ?.takeIf { !_uiState.value.composerPreviewLoading }
        val readyPreview = _uiState.value.composerPreview
        val inflight = composerPreviewTask?.takeIf { readyPreviewUrl == null && it.first == _uiState.value.composerPreviewUrl }
        if (inflight != null) composerPreviewTask = null  // handed over, so reset won't cancel it
        val pictureUp = composerPictureTask?.takeIf { it.first == readyPreviewUrl }
        if (pictureUp != null) composerPictureTask = null  // handed over too
        val previewDismissedUrl = dismissedPreviewUrl
        resetComposerPreview()
        _uiState.value = _uiState.value.copy(composerPreview = null, composerPreviewUrl = null, composerPreviewLoading = false)
        if (currentChatId.isBlank()) {
            android.util.Log.e("ChatViewModel", "sendMessage: currentChatId is BLANK — loadMessages was never called?")
            _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_not_loaded))
            return
        }
        // The text is committed to being sent (the queue is offline-tolerant) →
        // drop the draft so the chat list stops showing "Draft".
        val chatForDraft = currentChatId
        if (thread == null) viewModelScope.launch(Dispatchers.IO) { runCatching { repository.clearDraft(chatForDraft) } }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            try {
                val replyTo = _uiState.value.replyTo
                // The link card for the whole Share: the URL is parsed ONCE and
                // the other recipients reuse the result — no second network
                // round-trip.
                var linkCard: LinkPreviewData? = null
                // A long text is one message read whole on its own screen; only past
                // what one relay event carries does it go out in parts.
                val parts = splitLongText(text, limitChars = Int.MAX_VALUE)
                if (parts.size > 1) {
                    // Too long for one message: sent as consecutive messages,
                    // the first one keeps the reply.
                    parts.forEachIndexed { i, part ->
                        when {
                            i == 0 && replyTo != null ->
                                repository.sendReply(currentChatId, replyTo.id, part, replyQuoteSnippet(replyTo), thread = threadFor(currentChatId))
                            _uiState.value.chatTimerSeconds > 0 ->
                                repository.sendDisappearing(currentChatId, part, _uiState.value.chatTimerSeconds.toUInt())
                            else -> repository.sendText(currentChatId, part, thread = threadFor(currentChatId))
                        }
                    }
                } else when {
                    replyTo != null -> {
                        // NB: signature is (chatId, replyToId, text, quotedText).
                        // Carry the quoted snippet inline so the recipient
                        // renders it even without the original message. For media
                        // (no text) build a descriptive label so the quote shows
                        // "🎵 Music" / "📷 Photo" etc. instead of a blank "Message".
                        repository.sendReply(currentChatId, replyTo.id, text, replyQuoteSnippet(replyTo), thread = threadFor(currentChatId))
                    }
                    // A comment is never one-time or disappearing.
                    _uiState.value.isViewOnce && thread == null -> {
                        repository.sendEphemeralText(currentChatId, text, "view_once", 0u)
                    }
                    _uiState.value.isEphemeral && thread == null -> {
                        val timerOption = EPHEMERAL_TIMER_OPTIONS.find { it.label == _uiState.value.ephemeralTimer }
                        val ttl = timerOption?.seconds?.toUInt() ?: 3600u
                        repository.sendDisappearing(currentChatId, text, ttl)
                    }
                    _uiState.value.chatTimerSeconds > 0 -> {
 // classic per-chat auto-delete timer.
                        repository.sendDisappearing(currentChatId, text, _uiState.value.chatTimerSeconds.toUInt())
                    }
                    else -> {
                        // ONE bubble: if the text has a URL, fetch Open Graph and
                        // send a single link-preview message with the text as its
                        // caption (card below the text). No metadata → plain text.
                        // Sending never waits on a site: only a card already built (or
                        // finishing within a moment) goes along; otherwise plain text.
                        val url = extractFirstUrl(text)
                        val og = when {
                            url == null || url == previewDismissedUrl || !linkPreviewsOn() -> null
                            url == readyPreviewUrl -> readyPreview?.let { card ->
                                // Its full picture, if the upload ends within a moment;
                                // else the card goes with its small inline copy only.
                                if (card.imageRef.isNotBlank() || pictureUp == null) card
                                else kotlinx.coroutines.withTimeoutOrNull(400) { runCatching { pictureUp.second.await() }.getOrNull() } ?: card
                            }
                            url == inflight?.first -> kotlinx.coroutines.withTimeoutOrNull(400) {
                                runCatching { inflight.second.await() }.getOrNull()
                            }
                            else -> null
                        }
                        linkCard = og
                        if (og != null) {
                            repository.sendLinkPreview(
                                currentChatId, og.url, og.title, og.description, og.image.inlineOnly(), og.site, text,
                                thread = threadFor(currentChatId), imageRef = og.imageRef, video = og.video,
                            )
                        } else {
                            repository.sendText(currentChatId, text, thread = threadFor(currentChatId))
                        }
                    }
                }
                // Other Share recipients: same text (and the same link card if
                // already parsed) — on the repository scope so leaving the screen
                // does not cut the fan-out.
                val extras = alsoTo.filter { it != currentChatId && it.isNotBlank() }.distinct()
                if (extras.isNotEmpty()) {
                    val card = linkCard
                    repository.runDetached {
                        for (extra in extras) runCatching {
                            if (card != null) {
                                repository.sendLinkPreview(
                                    extra, card.url, card.title, card.description, card.image.inlineOnly(), card.site, text,
                                    imageRef = card.imageRef, video = card.video,
                                )
                            } else {
                                repository.sendText(extra, text)
                            }
                        }
                    }
                }
                // Reload messages after sending (a scheduled one moves out of the chat).
                repository.refreshHeld()
                val messages = fetchWindow(currentChatId)
                val ownId = _uiState.value.ownId
                android.util.Log.i(
                    "ChatViewModel",
                    "sendMessage OK: reloaded ${messages.size} message(s) (ownId='${ownId.take(12)}…')"
                )
                // Message sent → we're no longer typing.
                stopTyping()
                _uiState.value = _uiState.value.copy(
                    messages = messages.foldToBubbles(ownId, messageLabels).markWhispers(repository.whisperIds(currentChatId), strings.get(R.string.whisper_title)).let { mergeCallLog(it) }.withRunFlags().groupAlbums().applyPendingSending(repository.pendingSendIds()).withKnownStatus(),
                    inputText = "",
                    replyTo = null,
                    replyQuote = null,
                    isEphemeral = false,
                    isViewOnce = false,
                    isSending = false
                )
            } catch (e: MessengerException) {
                android.util.Log.e("ChatViewModel", "sendMessage MessengerException", e)
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = com.alphamessenger.ui.channel.WriteStatus.fromError(e.message)?.text()
                        ?: strings.get(R.string.chat_error_send, e.message ?: "")
                )
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "sendMessage generic exception", e)
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = strings.get(R.string.error_with_reason, e.message ?: "")
                )
            }
        }
    }

    // --- Reactions ---

    /**
     * Toggle a reaction: if the message already shows this emoji we send a
     * removal, otherwise an add. (Approximation — we key off whether the
     * aggregated reactions map contains the emoji, which is correct for 1:1
     * and good-enough for groups until per-user reactor lists exist.)
     */
    fun sendReaction(messageId: String, emoji: String) {
        // A scheduled message has not been sent yet: nothing to react to.
        if (repository.held.value[currentChatId]?.containsKey(messageId.substringBefore("#al")) == true) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = _uiState.value.messages.firstOrNull { it.id == messageId }
                // Toggle on MY reaction, not the aggregate — otherwise tapping a
                // heart the peer already placed was treated as a removal and my
                // own heart never got added (count stayed at 1).
                val alreadyReacted = current?.myReactions?.contains(emoji) == true
                // The group does not allow this one: nothing is sent (removing stays possible).
                if (!alreadyReacted && !_uiState.value.reactionPolicy.allows(emoji)) return@launch
                repository.sendReaction(currentChatId, messageId, emoji, remove = alreadyReacted)
                val messages = fetchWindow(currentChatId)
                val ownId = _uiState.value.ownId
                _uiState.value = _uiState.value.copy(
                    messages = messages.foldToBubbles(ownId, messageLabels).markWhispers(repository.whisperIds(currentChatId), strings.get(R.string.whisper_title)).let { mergeCallLog(it) }.withRunFlags().groupAlbums().applyPendingSending(repository.pendingSendIds()),
                    showReactionPicker = false,
                    reactionTarget = null
                )
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    error = strings.get(R.string.chat_error_reaction, e.message ?: ""),
                    showReactionPicker = false
                )
            }
        }
    }

    private suspend fun refreshChatSettings(chatId: String) {
        val isGroup = _uiState.value.isGroup
        val policy = if (isGroup) repository.groupReactionPolicy(chatId) else ReactionPolicy.ALL
        // Pins belong to the whole chat, so a discussion neither shows nor sets them.
        val pins = if (thread != null) emptyList() else repository.pinnedMessages(chatId)
        val canPin = thread == null && (!isGroup || isGroupAdmin(chatId) || repository.groupPermissions(chatId).third == "all")
        val channel = if (isGroup) repository.channelCfg(chatId).also { com.alphamessenger.ui.channel.ChannelCfgCache.put(chatId, it) }.takeIf { it.on } else null
        val postIds = _uiState.value.messages.map { it.id.substringBefore("#al") }.distinct().takeLast(500)
        val views = if (channel != null) repository.channelViews(chatId, postIds) else emptyMap()
        val forwards = if (channel != null) repository.channelForwards(chatId, postIds) else emptyMap()
        val owner = isGroup && myGroupRole(chatId) == "owner"
        val groupProtect = isGroup && channel == null && repository.channelCfg(chatId).protect
        if (chatId == currentChatId) {
            // A post's discussion is never the channel's feed: its rules (and our
            // rights, which a restriction just changed) refresh the discussion.
            val discussion = _uiState.value.discussion?.let { d ->
                channel?.let { discussionOf(chatId, _uiState.value.ownId, it) } ?: d
            }
            _uiState.value = _uiState.value.copy(reactionPolicy = policy, pinnedIds = pins, canPin = canPin,
                channel = if (thread != null) null else channel,
                channelViews = if (thread != null) emptyMap() else views,
                channelForwards = if (thread != null) emptyMap() else forwards,
                isOwner = owner,
                groupProtect = groupProtect || (thread != null && channel?.protect == true),
                discussion = discussion)
        }
    }

    // --- Channels ---

    /** These posts were on screen: count them as seen (once each, not in ghost mode). */
    fun markChannelSeen(postIds: List<String>) {
        val chatId = currentChatId
        if (_uiState.value.channel == null || postIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { repository.channelSeen(chatId, postIds) }
    }

    /** The owner opens or closes the comments under one post (the default stays). */
    fun setPostCommentsOpen(postId: String, open: Boolean) {
        val chatId = currentChatId
        val cfg = _uiState.value.channel ?: return
        val closed = if (open) cfg.closed - postId else (cfg.closed + postId).distinct()
        val next = cfg.copy(closed = closed)
        _uiState.value = _uiState.value.copy(channel = next)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setChannelCfg(chatId, next) }
                .onFailure { _uiState.value = _uiState.value.copy(channel = cfg, error = it.message) }
        }
    }

    /** A moderator's action on a comment of this discussion or on its author. */
    fun moderateComment(action: com.alphamessenger.ui.channel.CommentAction) {
        val chatId = currentChatId
        fun nameOf(m: MessageUi) = _uiState.value.peerProfiles[m.senderId]?.displayName?.takeIf { it.isNotBlank() }
            ?: m.senderName.orEmpty()
        when (action) {
            is com.alphamessenger.ui.channel.CommentAction.Delete -> deleteForEveryone(listOf(action.m.id))
            is com.alphamessenger.ui.channel.CommentAction.DeleteAll -> {
                // This screen holds exactly the discussion: every comment of the author in it.
                val ids = _uiState.value.messages.filter { it.senderId == action.m.senderId && !it.isDeleted }.map { it.id }
                if (ids.isNotEmpty()) deleteForEveryone(ids)
            }
            is com.alphamessenger.ui.channel.CommentAction.Mute -> viewModelScope.launch(Dispatchers.IO) {
                val now = System.currentTimeMillis() / 1000
                // "For good" = a hundred years: the mute record has no separate forever flag.
                val until = if (action.hours <= 0) now + 100L * 365 * 24 * 3600 else now + action.hours * 3600
                val ok = repository.muteCommenter(chatId, action.m.senderId, nameOf(action.m), until)
                _uiState.value = _uiState.value.copy(
                    error = strings.get(if (ok) R.string.member_muted else R.string.error_generic),
                )
            }
            is com.alphamessenger.ui.channel.CommentAction.Unmute -> viewModelScope.launch(Dispatchers.IO) {
                val ok = repository.unmuteCommenter(chatId, action.m.senderId, nameOf(action.m))
                _uiState.value = _uiState.value.copy(
                    error = strings.get(if (ok) R.string.member_unmuted else R.string.error_generic),
                )
            }
            is com.alphamessenger.ui.channel.CommentAction.Ban -> viewModelScope.launch(Dispatchers.IO) {
                val ok = repository.banCommenter(chatId, action.m.senderId, nameOf(action.m), action.m.senderAvatarPath.orEmpty())
                if (!ok) _uiState.value = _uiState.value.copy(error = strings.get(R.string.error_generic))
                reloadMessages()
            }
        }
    }

    /** Until when the author of [m] may not write here (unix secs; 0 = free). */
    suspend fun commenterMutedUntil(m: MessageUi): Long = repository.commenterMutedUntil(currentChatId, m.senderId)

    /** The core's answer for this chat (see [com.alphamessenger.ui.channel.WriteStatus]). */
    suspend fun writeStatus(postId: String, kind: String): com.alphamessenger.ui.channel.WriteStatus =
        repository.writeStatus(currentChatId, postId, kind)

    /** Requests: the user lets this chat in — an ordinary chat from now on. */
    fun acceptRequest() {
        val chatId = currentChatId
        viewModelScope.launch(Dispatchers.IO) {
            repository.acceptChatRequest(chatId)
            _uiState.value = _uiState.value.copy(isRequest = false)
        }
    }

    /** Requests: forget this chat (nobody is told); [block] also blocks its sender. */
    fun declineRequest(block: Boolean, onDone: () -> Unit) {
        val chatId = currentChatId
        viewModelScope.launch(Dispatchers.IO) {
            repository.declineChatRequest(chatId, block)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** Open a post's discussion: hand the post over so its screen shows it at once. */
    fun prepareDiscussion(post: MessageUi) = com.alphamessenger.ui.channel.DiscussionHandoff.put(post)

    /** Pin a message for everyone in the chat, or unpin it. */
    fun togglePin(messageId: String) {
        val chatId = currentChatId
        val id = messageId.substringBefore("#al")
        if (chatId.isBlank() || !_uiState.value.canPin) return
        val before = _uiState.value.pinnedIds
        val pin = id !in before
        _uiState.value = _uiState.value.copy(pinnedIds = if (pin) listOf(id) + before else before - id)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.pinMessage(chatId, id, pin) }.onFailure {
                _uiState.value = _uiState.value.copy(pinnedIds = before, error = it.message)
            }
        }
    }

    fun showReactionPicker(messageId: String) {
        _uiState.value = _uiState.value.copy(
            showReactionPicker = true,
            reactionTarget = messageId
        )
    }

    fun hideReactionPicker() {
        _uiState.value = _uiState.value.copy(showReactionPicker = false, reactionTarget = null)
    }

    // --- Delete / Edit ---

    /** Delete for everyone — broadcasts a tombstone; bubble disappears
     *  on both sides. */
    fun deleteForEveryone(messageIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (id in messageIds) runCatching { repository.sendDelete(currentChatId, id) }
            // Also drop locally so it vanishes immediately for the sender.
            for (id in messageIds) runCatching { repository.deleteMessageLocal(id) }
            reloadMessages()
        }
    }

    /** Delete for me — local-only; the peer keeps their copy. */
    fun deleteForMe(messageIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (id in messageIds) runCatching { repository.deleteMessageLocal(id) }
            reloadMessages()
        }
    }

    /** Legacy single-message delete (for everyone). */
    fun deleteMessage(messageId: String) = deleteForEveryone(listOf(messageId))

    fun editMessage(messageId: String, newText: String) {
        // A scheduled message has not left the phone: change it in place.
        if (repository.held.value[currentChatId]?.containsKey(messageId) == true) {
            viewModelScope.launch { repository.editHeldText(messageId, newText); reloadMessages() }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Model-level: a forwarded message MUST stay forwarded after an edit.
                // The "Forwarded" state lives in the author-less FWD marker on the content,
                // so re-apply it when editing a forwarded message's caption/text —
                // otherwise the edit rewrites plain content and the badge silently drops.
                // buildForwardBody is idempotent (won't stack a second marker).
                val wasForwarded = _uiState.value.messages.find { it.id == messageId }?.forwardedFrom != null
                val body = if (wasForwarded) buildForwardBody(newText) else newText
                repository.sendEdit(currentChatId, messageId, body)
                reloadMessages()
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_error_edit, e.message ?: ""))
            }
        }
    }

    // --- Read receipts ---

    fun markRead(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Mark last incoming message as read
                val lastIncoming = _uiState.value.messages.lastOrNull { !it.isOutgoing }
                if (lastIncoming != null) {
                    repository.markMessageRead(chatId, lastIncoming.id)
                }
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    // --- View-once ---

    /** Secret Note: send a one-view text (view-once) — opens fullscreen on the
     *  recipient, then self-destructs. Independent of the chat's auto-timer. */
    fun sendSecretNote(text: String) {
        val id = currentChatId
        val body = text.trim()
        if (id.isBlank() || body.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.sendEphemeralText(id, body, "view_once", 0u) }
            reloadMessages()
        }
    }

    // --- Whispers ---

    /** Send words as a whisper: read only under the finger, for [secs] seconds. */
    fun sendWhisperText(text: String, secs: Int) {
        val id = currentChatId
        val body = text.trim()
        if (id.isBlank() || body.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.sendEphemeralText(id, body, "whisper", secs.toUInt()) }
                .onFailure { _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_media_send_failed)) }
            reloadMessages()
        }
    }

    /** Send a photo as a whisper (copied and cleaned like any photo). */
    fun sendWhisperPhoto(uri: android.net.Uri, secs: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = copyUriToCache(uri) ?: run {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_media_send_failed))
                return@launch
            }
            sendEphemeralMedia(file.absolutePath, "image", "whisper", secs.toUInt())
        }
    }

    suspend fun whisperSecret(messageId: String) = repository.whisperSecret(messageId)

    /** A photo whisper's picture, decrypted to the cache for the reader. */
    suspend fun whisperPhoto(mediaRef: String): String? = repository.downloadMediaToCache(mediaRef)

    fun burnWhisper(messageId: String, usedSecs: Int, shot: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.whisperBurn(messageId, usedSecs, shot)
            reloadMessages()
        }
    }

    /** The decrypted copy of a burned photo whisper leaves the disk at once. */
    fun forgetWhisperPhoto(mediaRef: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { repository.deleteMediaCacheFiles(mediaRef) } }
    }

    fun markViewOnceConsumed(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.markViewOnceConsumed(messageId)
                com.alphamessenger.data.ViewOnceCache.clear(messageId)
                // Wipe the blob from disk — a consumed view-once must leave no
                // file / thumbnail / cache behind.
                val msg = _uiState.value.messages.firstOrNull { it.id == messageId }
                if (msg != null) {
                    runCatching { msg.mediaRefJson?.let { repository.deleteMediaCacheFiles(it) } }
                    msg.localMediaPath?.let { p ->
                        val f = java.io.File(p)
                        if (f.exists() && f.absolutePath.startsWith(appContext.cacheDir.absolutePath)) f.delete()
                    }
                }
                // Update local state
                val current = _uiState.value.messages.toMutableList()
                val idx = current.indexOfFirst { it.id == messageId }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(isViewOnceConsumed = true)
                    _uiState.value = _uiState.value.copy(messages = current)
                }
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    // --- Voice recording — MediaRecorder wrapper ---

    fun startRecording() {
        // A discussion where voice notes are not allowed: say so before recording anything.
        _uiState.value.discussion?.voice?.takeIf { !it.ok }?.let { st ->
            _uiState.value = _uiState.value.copy(error = st.text())
            return
        }
        try {
            val file = File(appContext.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
            recordingFile = file

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(24000)
                setAudioEncodingBitRate(24000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            _uiState.value = _uiState.value.copy(
                isRecording = true,
                isRecordingLocked = false,
                recordingPaused = false,
                recordingDuration = 0,
            )

            // Tell the peer we're recording, then refresh the signal every
            // few seconds so it doesn't expire mid-recording.
            emitChatState("recording")
            startRecordingTicker()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                error = strings.get(R.string.chat_error_recording, e.message ?: ""),
                isRecording = false
            )
        }
    }

    /** Ticks the duration (1s) + samples the mic amplitude (~10/s) for the
     *  live equalizer. Restarted after a pause/resume. */
    private fun startRecordingTicker() {
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            var ms = 0
            while (isActive) {
                delay(100)
                ms += 100
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                // Normalize: 32767 max → 0..1, with a gentle curve.
                val norm = (amp / 9000f).coerceIn(0f, 1f)
                // 10 Hz waveform → hot stream (panel accumulates locally), NOT ChatState.
                _recorderTicks.tryEmit(RecorderTick(norm))
                if (ms % 1000 == 0) {
                    val secs = _uiState.value.recordingDuration + 1
                    _uiState.value = _uiState.value.copy(recordingDuration = secs)
                    if (secs % 6 == 0) emitChatState("recording")
                }
            }
        }
    }

    fun stopRecording() {
        val rec = recorder
        // Nothing actually recording (e.g. we were still asking for the mic
        // permission, or a stray release) → just reset quietly, no error toast.
        if (rec == null) {
            recordingJob?.cancel(); recordingJob = null
            recordingFile?.delete(); recordingFile = null
            _uiState.value = _uiState.value.copy(
                isRecording = false, isRecordingLocked = false, recordingDuration = 0,
            )
            return
        }
        // MediaRecorder.stop() throws if called before any frame was captured
        // (super-short tap). Treat that as a discard, NOT a crash/error.
        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        recorder = null
        recordingJob?.cancel(); recordingJob = null
        emitChatState("paused")
        val duration = _uiState.value.recordingDuration
        val file = recordingFile
        _uiState.value = _uiState.value.copy(
            isRecording = false, isRecordingLocked = false, recordingDuration = 0,
        )
        if (stopped && duration > 0 && file != null && file.exists() && currentChatId.isNotBlank()) {
            val replyTo = takeReplyId()
            viewModelScope.launch(Dispatchers.IO) {
                sendMediaOptimistic(file.absolutePath, "audio", null, replyTo = replyTo)
            }
        } else {
            file?.delete()   // too short / failed → discard silently
        }
        recordingFile = null
    }

    /** A recorded video message goes out like any video (its "round_" file name
     *  marks it as a circle). */
    fun sendRoundVideo(path: String) {
        if (currentChatId.isBlank()) { java.io.File(path).delete(); return }
        val replyTo = takeReplyId()
        viewModelScope.launch(Dispatchers.IO) { sendMediaOptimistic(path, "video", null, replyTo = replyTo) }
    }

    /**
     * A sticker goes out as its already-uploaded blob (uploaded once, the first
     * time), tagged with its emoji and the pack it belongs to.
     */
    /** [link]: the pack's link when it isn't added here (sent from a pack someone shared). */
    fun sendSticker(packId: String, sticker: com.alphamessenger.ui.sticker.Sticker, link: String? = null) {
        if (currentChatId.isBlank()) return
        val target = currentChatId
        val replyTo = takeReplyId()
        viewModelScope.launch(Dispatchers.IO) {
            val store = com.alphamessenger.ui.sticker.StickerStore
            val ref = store.refFor(appContext, repository, packId, sticker)
            if (ref == null) {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_media_send_failed))
                return@launch
            }
            // An own pack gets its lasting link now (keys only, no network), so
            // this sticker already leads to the pack; "Saved" stays private.
            val shareable = packId != com.alphamessenger.ui.sticker.StickerStore.SAVED_ID
            val pack = if (shareable) store.ensureKeys(appContext, repository, packId) ?: store.pack(appContext, packId)
                else store.pack(appContext, packId)
            val origin = when {
                pack?.builtin == true -> "builtin:" + pack.id
                !shareable -> ""
                else -> pack?.link ?: link.orEmpty()
            }
            val caption = com.alphamessenger.ui.sticker.STICKER_MARK + sticker.emoji + "\n" + origin
            val ttl: UInt = _uiState.value.chatTimerSeconds.takeIf { it > 0 }?.toUInt() ?: 0u
            store.noteUsed(appContext, packId, sticker)
            publishMediaRef(target, ref, "image", caption, ttl, replyTo)
            // The pack's latest version goes out in the background (nothing, if unchanged).
            if (pack != null && pack.own && shareable) repository.runDetached { store.publishLatest(appContext, repository, packId) }
        }
    }

    /** A GIF goes out as a copy of its clip (the "gif_" name marks it). */
    fun sendGif(entry: com.alphamessenger.ui.gif.GifEntry) {
        if (currentChatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val copy = com.alphamessenger.ui.gif.GifLibrary.copyForSend(appContext, entry) ?: return@launch
            sendMediaOptimistic(copy.absolutePath, "video", null)
        }
    }

    /** Tap-to-record: enter the locked recording panel (recording continues). */
    fun lockRecording() {
        if (recorder != null) _uiState.value = _uiState.value.copy(isRecordingLocked = true)
    }

    fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { recorder?.pause() }
            recordingJob?.cancel(); recordingJob = null
            _uiState.value = _uiState.value.copy(recordingPaused = true)
        }
    }

    fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { recorder?.resume() }
            _uiState.value = _uiState.value.copy(recordingPaused = false)
            startRecordingTicker()
        }
    }

    /** Abort an in-progress recording without sending (slide-to-cancel). */
    fun cancelRecording() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        recordingJob?.cancel(); recordingJob = null
        emitChatState("paused")
        recordingFile?.delete(); recordingFile = null
        _uiState.value = _uiState.value.copy(
            isRecording = false, isRecordingLocked = false, recordingPaused = false,
            recordingDuration = 0,
        )
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "${m}:${s.toString().padStart(2, '0')}"
    }

    // --- UI state toggles ---

    fun setShowAttachPanel(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAttachPanel = show)
    }

    fun setShowChatMenu(show: Boolean) {
        _uiState.value = _uiState.value.copy(showChatMenu = show)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun dismissAttachmentRejection() {
        _uiState.value = _uiState.value.copy(attachmentRejection = null)
    }

    /**
     * Gate AT PICK time: the item simply does not enter the set; the panel
     * stays open. Differs from [passesAttachmentCheck] in exactly this — that
     * one dismisses the panel, because it checks at send time, when the panel
     * is already gone.
     */
    fun acceptsAttachment(uri: android.net.Uri, mediaType: String): Boolean {
        val r = com.alphamessenger.config.AttachmentLimits.validateUri(appContext, uri, mediaType)
            ?: return true
        _uiState.value = _uiState.value.copy(
            attachmentRejection = AttachmentRejectionUi(mediaType, r.kind, r.selectedBytes, r.limitBytes),
        )
        return false
    }

    /** Early pre-send gate (reuses AttachmentLimits). true = OK to proceed;
     *  false = rejected → a bottom sheet is shown and NOTHING is copied/sent.
     *  Runs before copy / Message / upload. */
    private fun passesAttachmentCheck(uri: android.net.Uri, mediaType: String): Boolean {
        val r = com.alphamessenger.config.AttachmentLimits.validateUri(appContext, uri, mediaType)
            ?: return true
        _uiState.value = _uiState.value.copy(
            attachmentRejection = AttachmentRejectionUi(mediaType, r.kind, r.selectedBytes, r.limitBytes),
            showAttachPanel = false,
        )
        return false
    }

    // --- Group management — admin FFI calls ---

    fun addMember(chatId: String, peerKeyPackageBase64: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.addMember(chatId, peerKeyPackageBase64)
                reloadMessages()
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_error_add_member, e.message ?: ""))
            }
        }
    }

    /**
     * High-level "Add participant" used by the group info screen / chat top
     * bar action. The FFI fetches the peer's KeyPackage from the relays,
     * applies an MLS Commit + Welcome, and broadcasts both — we just react
     * to success/failure in the UI.
     */
    fun addMemberByNpub(chatId: String, npub: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isAddingMember = true)
            try {
                repository.addMemberByNpub(chatId, npub)
                _uiState.value = _uiState.value.copy(isAddingMember = false, addMemberOk = true)
                reloadMessages()
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isAddingMember = false,
                    error = if (e.isGroupFull()) strings.get(R.string.group_full, com.alphamessenger.util.MAX_GROUP_MEMBERS)
                        else strings.get(R.string.chat_error_invite, e.message ?: ""),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAddingMember = false,
                    error = strings.get(R.string.error_with_reason, e.message ?: ""),
                )
            }
        }
    }

    fun clearAddMemberOk() {
        _uiState.value = _uiState.value.copy(addMemberOk = false)
    }


    fun removeMember(chatId: String, leafIndex: UInt) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.removeMember(chatId, leafIndex)
                reloadMessages()
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_error_remove_member, e.message ?: ""))
            }
        }
    }

    /** G-8: true when the group's write permission is "admins" and WE are a plain
     *  member → composer locked (announcement mode). Member role read from the
     *  MLS member list (is_self entry). */
    /** Returns (writeRestricted, chatClosedUntilMe). Both apply only to plain
     *  members: announcement mode (write=="admins") and temp-close (closed_until). */
    /** True if WE are owner/admin of this group (drives admin-only actions like
     *  the disappearing-messages timer). */
    private suspend fun myGroupRole(chatId: String): String = runCatching {
        val arr = org.json.JSONArray(repository.listGroupMembers(chatId))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.optBoolean("is_self", false) }
            ?.optString("role", "member") ?: "member"
    }.getOrDefault("member")

    /** Leave a channel and remove it from this device. */
    fun leaveChannelAndExit(onDone: () -> Unit) {
        val id = currentChatId
        if (id.isBlank()) { onDone(); return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.leaveChat(id) }
            runCatching { repository.deleteChat(id) }
            ConversationSnapshotCache.clear(id)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
        }
    }

    private suspend fun isGroupAdmin(chatId: String): Boolean = runCatching {
        val arr = org.json.JSONArray(repository.listGroupMembers(chatId))
        val role = (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.optBoolean("is_self", false) }
            ?.optString("role", "member") ?: "member"
        role == "owner" || role == "admin"
    }.getOrDefault(false)

    private suspend fun computeWriteState(chatId: String, isGroup: Boolean, removed: Boolean): Pair<Boolean, Long> {
        if (!isGroup || removed) return false to 0L
        val myRole = runCatching {
            val arr = org.json.JSONArray(repository.listGroupMembers(chatId))
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optBoolean("is_self", false) }
                ?.optString("role", "member") ?: "member"
        }.getOrDefault("member")
        val isAdmin = myRole == "owner" || myRole == "admin"
        if (isAdmin) return false to 0L
        val write = repository.groupPermissions(chatId).first
        val closedUntil = repository.groupClosedUntil(chatId)
        val nowSec = com.alphamessenger.util.TrustedTime.nowSeconds()
        val closedForMe = if (closedUntil > nowSec) closedUntil else 0L
        return (write == "admins") to closedForMe
    }

    fun leaveChat(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.leaveChat(chatId)
                // Instantly switch the composer to the "Group unavailable" strip
                // (don't wait for a re-open/reload to re-detect availability).
                _uiState.value = _uiState.value.copy(removedFromGroup = true)
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_error_leave, e.message ?: ""))
            }
        }
    }

    /** G-11: block / unblock the 1:1 peer (device-local). */
    fun blockPeer() {
        val np = _uiState.value.peerNpub
        if (np.isBlank()) return
        repository.blockUser(np, _uiState.value.peerDisplayName, _uiState.value.peerAvatarPath)
        _uiState.value = _uiState.value.copy(peerBlocked = true)
    }
    fun unblockPeer() {
        val np = _uiState.value.peerNpub
        if (np.isBlank()) return
        repository.unblockUser(np)
        _uiState.value = _uiState.value.copy(peerBlocked = false)
    }

    /** Top-bar menu: toggle this chat's mute (Notifications). */
    fun toggleMute() {
        val id = currentChatId
        if (id.isBlank()) return
        val nowMuted = _uiState.value.isMuted
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // 0 = unmute; ~10 years = effectively permanent mute.
                repository.muteChat(id, if (nowMuted) 0L else 10L * 365 * 24 * 3600)
            }
            _uiState.value = _uiState.value.copy(isMuted = !nowMuted)
        }
    }

    /** Three-dots menu: set the per-chat disappearing-messages timer (seconds,
     *  0 = off). Persisted device-locally; applied to subsequent outgoing msgs. */
    /** Resolve the chat's disappearing timer treating the device-local store as
     *  the SOURCE OF TRUTH (survives deleting the system notice). A control
     *  message in [uiMessages] updates that state; the notice is display-only. */
    private fun effectiveChatTimer(chatId: String, uiMessages: List<MessageUi>): Int {
        val fromHistory = uiMessages.lastOrNull { it.timerSeconds != null }?.timerSeconds
        if (fromHistory != null) runCatching { repository.setChatTimer(chatId, fromHistory) }
        return runCatching { repository.chatTimerSeconds(chatId) }.getOrDefault(fromHistory ?: 0)
    }

    // --- Secret chats ---

    /** A secret chat speaks as the peer of its ordinary chat, never calls, and
     *  disappears in a day unless set otherwise; a 1:1 chat shows the peer's
     *  pending invitations. */
    private suspend fun withSecretState(st: ChatState, chatId: String, isGroup: Boolean, memberCount: Int): ChatState {
        if (isGroup) return st
        val origin = repository.secretChatOrigin(chatId)
        if (origin.isEmpty()) {
            val offers = repository.secretChatOffers().filter { it.chatId == chatId }.map { it.inviteId }
            return st.copy(secretOrigin = "", secretOffers = offers)
        }
        val peer = runCatching {
            val arr = org.json.JSONArray(repository.listGroupMembers(origin))
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { !it.optBoolean("is_self", false) }
        }.getOrNull()
        val timer = st.chatTimerSeconds.takeIf { it > 0 } ?: SECRET_DEFAULT_TIMER.also {
            runCatching { repository.setChatTimer(chatId, it) }
        }
        val (secretState, secretInvite) = repository.secretChatStatus(chatId)
        return st.copy(
            secretOrigin = origin,
            secretWaiting = secretState != "active",
            secretState = secretState,
            secretInvite = secretInvite,
            // Nothing leaves a secret chat: no forwarding, copying or screenshots.
            groupProtect = true,
            secretOffers = emptyList(),
            peerDisplayName = peer?.optString("display_name").orEmpty().ifBlank { st.peerDisplayName },
            peerAvatarPath = peer?.optString("avatar_path").orEmpty(),
            peerCanCall = false,
            peerCanVideo = false,
            chatTimerSeconds = timer,
        )
    }

    suspend fun secretLinkReady(): Boolean = repository.secretChatReady(currentChatId)

    suspend fun secretCheck(): String? = repository.secretChatCheck(currentChatId)

    suspend fun secretNewCircuit(): Boolean = repository.secretChatNewCircuit(currentChatId)

    fun acceptSecretOffer(inviteId: String) {
        viewModelScope.launch {
            runCatching { repository.secretChatAccept(inviteId) }
            val st = _uiState.value
            _uiState.value = st.copy(
                secretOffers = st.secretOffers - inviteId,
                secretState = if (st.secretState == "invited") "accepted" else st.secretState,
            )
        }
    }

    fun declineSecretOffer(inviteId: String, onGone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.secretChatDecline(inviteId) }
            _uiState.value = _uiState.value.copy(secretOffers = _uiState.value.secretOffers - inviteId)
            if (_uiState.value.secretState == "invited") onGone()
        }
    }

    /** Delete the secret chat on both phones. */
    fun endSecretChat(onDone: () -> Unit) {
        val id = currentChatId
        viewModelScope.launch {
            runCatching { repository.secretChatEnd(id) }
            onDone()
        }
    }

    /** Per-chat "don't keep on servers" (overrides the global default for this chat). */
    fun toggleNoStore() {
        val chatId = currentChatId
        if (chatId.isBlank()) return
        val enabled = !_uiState.value.noStore
        _uiState.value = _uiState.value.copy(noStore = enabled)
        viewModelScope.launch {
            val ok = runCatching { repository.setChatNoStore(chatId, enabled) }.isSuccess
            if (!ok) _uiState.value = _uiState.value.copy(noStore = !enabled)
        }
    }

    fun setChatTimer(seconds: Int) {
        val id = currentChatId
        if (id.isBlank()) return
        // Optimistic local apply…
        runCatching { repository.setChatTimer(id, seconds) }
        _uiState.value = _uiState.value.copy(chatTimerSeconds = seconds)
        // …then broadcast a control message so the change syncs to the peer
 // (shared classic setting) and shows a centered notice on BOTH
        // sides. reloadMessages re-derives the value from history.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.sendText(id, buildTimerControl(seconds)) }
            reloadMessages()
        }
    }

    /** Top-bar menu: clear history locally (Clear history). Keeps the chat. */
    fun clearHistoryLocal() {
        val id = currentChatId
        if (id.isBlank()) return
        repository.markHistoryCleared(id)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val messages = repository.getAllStoredMessages(id, 10000u)
                for (msg in messages) runCatching { repository.deleteMessageLocal(msg.id) }
            }
            ConversationSnapshotCache.clear(id)
            _uiState.value = _uiState.value.copy(messages = emptyList())
        }
    }

    /** A bot's chat: start a new conversation, the current one is kept in its list. */
    fun newAssistantConversation(onDone: (Boolean) -> Unit = {}) {
        val id = currentChatId
        val profile = com.alphamessenger.assistant.AssistantStore.profileOfChat(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // What the stopped answer keeps lands in this conversation, before it moves to the list.
            assistant.stopAndSettle(id)
            val moved = runCatching { repository.assistantNewConversation(profile.id) }.getOrDefault(false)
            ConversationSnapshotCache.clear(id)
            // From the database, after any reload already under way: one begun before the
            // move would otherwise put the old conversation back on screen.
            if (moved) reloadNow()
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(moved) }
        }
    }

    fun openAssistantConversation(conversationId: String) {
        val id = currentChatId
        val profile = com.alphamessenger.assistant.AssistantStore.profileOfChat(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            assistant.stopAndSettle(id)
            runCatching { repository.assistantOpenConversation(profile.id, conversationId) }
            ConversationSnapshotCache.clear(id)
            reloadNow()
        }
    }

    suspend fun assistantConversations(): List<uniffi.alphamessenger.AssistantConversation> {
        val profile = com.alphamessenger.assistant.AssistantStore.profileOfChat(currentChatId) ?: return emptyList()
        return runCatching { repository.assistantConversations(profile.id) }.getOrDefault(emptyList())
    }

    suspend fun deleteAssistantConversation(conversationId: String) {
        if (!conversationId.startsWith(currentChatId + "~")) return
        runCatching { repository.deleteChat(conversationId, false) }
    }

    /** Top-bar menu: delete this chat entirely (Delete chat). */
    fun deleteChatAndExit(forBoth: Boolean = false, onDone: () -> Unit) {
        val id = currentChatId
        if (id.isBlank()) { onDone(); return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteChat(id, forBoth) }
            ConversationSnapshotCache.clear(id)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun clearChat(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete all messages in this chat locally
                val messages = repository.getMessages(chatId, 10000u, 0u)
                for (msg in messages) {
                    try {
                        repository.sendDelete(chatId, msg.id)
                    } catch (_: Exception) {
                        // Best effort — skip messages that can't be deleted
                    }
                }
                ConversationSnapshotCache.clear(chatId)
                _uiState.value = _uiState.value.copy(messages = emptyList())
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = strings.get(R.string.chat_error_clear_chat, e.message ?: ""))
            }
        }
    }

    // --- Media sending — photo, video, files via FFI ---

    fun sendEphemeralMedia(
        filePath: String,
        mediaType: String,
        expiryMode: String,
        ttlSecs: UInt,
        caption: String? = null,
    ) {
        if (currentChatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            try {
                val cap = caption?.trim()?.takeIf { it.isNotEmpty() }
                // --- UNIFIED PIPELINE (same as Photo/Video/File/Voice/Music/Forward) ---
                // persist → enqueue → uploadBlobRefOnly → broadcastMedia(with expiry)
                val glance = expiryMode == "glance"
                val whisper = expiryMode == "whisper" || glance
                val msgId = runCatching {
                    // A whisper's own row has no timer: the reading limit is the whisper's.
                    repository.persistOutgoingMedia(currentChatId, filePath, mediaType, cap, if (whisper) 0u else ttlSecs, thread = threadFor(currentChatId))
                }.getOrNull()
                if (msgId != null && whisper) {
                    repository.whisperMarkOutgoing(currentChatId, msgId, mediaType, ttlSecs.toInt(), glance)
                }
                if (msgId == null) {
                    _uiState.value = _uiState.value.copy(isSending = false, error = strings.get(R.string.chat_media_send_failed))
                    return@launch
                }
                stopTyping()
                reloadMessages()
                _uiState.value = _uiState.value.copy(showAttachPanel = false)
                // Transfer lifecycle: Preparing → Encrypting → Publishing → (Sent|Failed).
                transfers.onUploadPreparing(msgId)
                val poster = when (mediaType) {
                    "video" -> makeVideoPosterB64(filePath)
                    "image" -> makeImagePosterB64(filePath)
                    else -> null
                }
                val meta = displayMeta(filePath, mediaType)
                val streaming = mediaType == "video"
                // The core queue — only on failure: see [queueAfterFailure].
                val chatForQueue = currentChatId
                suspend fun queueForRetry() = queueAfterFailure {
                    repository.enqueuePendingMediaSingle(
                        chatForQueue, msgId, filePath, mediaType, cap, poster, streaming, if (whisper) 0u else ttlSecs, meta,
                    )
                }
                // Register job for cancellation (same pattern as forwardAlbum).
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let {
                    activeUploadJobs[msgId] = it
                }
                try {
                    transfers.onUploadEncrypting(msgId)
                    val ref = repository.uploadBlobRefOnly(filePath, mediaType, poster, msgId, meta)
                    if (ref == null) {
                        queueForRetry()
                        transfers.onUploadFailed(msgId)
                        return@launch
                    }
                    transfers.onUploadPublishing(msgId)
                    val ok = repository.broadcastMedia(currentChatId, msgId, ref, mediaType, cap, expiryMode, ttlSecs)
                    if (ok) {
                        repository.deletePendingMedia(msgId)
                        transfers.onUploadSent(msgId)
                    } else {
                        queueForRetry()
                        transfers.onUploadFailed(msgId)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    queueForRetry()
                    transfers.onUploadFailed(msgId)
                } finally {
                    activeUploadJobs.remove(msgId)
                }
                reloadMessages()
                _uiState.value = _uiState.value.copy(isSending = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = strings.get(R.string.error_with_reason, e.message ?: "")
                )
            }
        }
    }

    // --- Expiry sweep — periodic cleanup of expired ephemeral messages ---

    private var sweepJob: Job? = null

    fun startExpirySweep() {
        if (sweepJob?.isActive == true) return
        // The background sweep may take an expired message first: whoever
        // removes it, the screen drops it too.
        viewModelScope.launch { repository.messagesExpired.collect { reloadMessages() } }
        sweepJob = viewModelScope.launch {
            while (isActive) {
                delay(4_000) // Every ~4s so disappearance is visible live in the open chat
                try {
                    // Snapshot media before the sweep so we can purge the blobs
                    // (file + thumbnails/chunks) of whatever disappears — leaving
                    // them on disk would defeat the feature.
                    val beforeMedia = _uiState.value.messages.flatMap { collectMediaForCleanup(it) }
                    val swept = repository.sweepExpiredMessages()
                    if (swept > 0u) {
                        val survivingIds = runCatching {
                            fetchWindow(currentChatId, 200u).map { it.id }.toSet()
                        }.getOrDefault(emptySet())
                        for ((msgId, localPath, ref) in beforeMedia) {
                            if (msgId !in survivingIds) {
                                ref?.let { repository.deleteMediaCacheFiles(it) }
                                localPath?.let { p ->
                                    val f = java.io.File(p)
                                    if (f.exists() && f.absolutePath.startsWith(appContext.cacheDir.absolutePath)) f.delete()
                                }
                            }
                        }
                        reloadMessages()
                    }
                } catch (_: Exception) {
                    // Best effort
                }
            }
        }
    }

    /** (msgId, localMediaPath, mediaRefJson) tuples to purge if a message
     *  disappears — covers the bubble's own media and any album children. */
    private fun collectMediaForCleanup(m: MessageUi): List<Triple<String, String?, String?>> {
        val out = ArrayList<Triple<String, String?, String?>>()
        if (m.isMedia) out.add(Triple(m.id, m.localMediaPath, m.mediaRefJson))
        m.albumItems?.forEach { out.add(Triple(m.id, it.localMediaPath, it.mediaRefJson)) }
        return out
    }

    fun stopExpirySweep() {
        sweepJob?.cancel()
        sweepJob = null
    }

    // --- Cleanup ---

    override fun onCleared() {
        super.onCleared()
 // standard: persist the last displayed bubble list so the next open of
        // this chat paints instantly from the snapshot cache (no empty state).
        if (thread == null) ConversationSnapshotCache.put(currentChatId, _uiState.value.messages, _uiState.value.peerProfiles, _uiState.value.whispers, listStamp)
        // Unregister the auto-download invalidation observers.
        try {
            appContext.getSharedPreferences(
                com.alphamessenger.data.MediaAutoDownload.PREFS, Context.MODE_PRIVATE,
            ).unregisterOnSharedPreferenceChangeListener(autoDownloadPrefsListener)
        } catch (_: Exception) {}
        try {
            (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        // Best-effort "paused" so peers clear our indicator promptly even
        // though viewModelScope is about to be cancelled (the detached send
        // runs on the repository's own scope).
        if (currentChatId.isNotBlank()) {
            repository.sendChatStateDetached(currentChatId, "paused")
        }
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recordingJob?.cancel()
        stopExpirySweep()
    }
}

/**
 * classic forward staging. When the user picks a target chat in the
 * forward picker we stash the selected messages here and navigate into that
 * chat; the target's ViewModel consumes the buffer on load and shows a
 * "Forwarded" preview above the composer.
 */
object ForwardBuffer {
    @Volatile var pending: List<MessageUi>? = null
    @Volatile var sourceChatId: String = ""
    fun set(msgs: List<MessageUi>, source: String) { pending = msgs; sourceChatId = source }
    fun consume(): List<MessageUi>? { val p = pending; pending = null; sourceChatId = ""; return p }
}

/**
 * G-11: hide a blocked 1:1 peer's INCOMING messages. We can't stop a serverless
 * peer from publishing, but once blocked we drop their messages locally. Outgoing
 * (our own) messages and any incoming received before the block timestamp stay
 * visible, so history is preserved; only their new messages disappear.
 */
private fun List<MessageUi>.hideBlockedIncoming(blocked: Boolean, blockedAtSec: Long): List<MessageUi> {
    if (!blocked) return this
    return filter { msg ->
        if (msg.isOutgoing) return@filter true
        if (blockedAtSec <= 0L) return@filter false // legacy entry: block all incoming
        val sec = runCatching { java.time.OffsetDateTime.parse(msg.timestamp).toEpochSecond() }
            .getOrDefault(Long.MAX_VALUE)
        sec < blockedAtSec
    }
}

/** Only inline (data URI) preview images may travel; a remote URL would make
 *  every recipient contact the site. */
private fun String.inlineOnly(): String = if (startsWith("data:image/")) this else ""

/** Messages per page of history. */
private val PAGE: UInt = 100u
/** History a jump may load at most (to find a far pin). */
private val MAX_WINDOW: UInt = 5000u


private val MODERATOR_ROLES = setOf("owner", "admin", "moderator")

/** The longest fragment a reply quotes. */
private const val QUOTE_MAX = 300
