package com.alphamessenger.data

import android.content.Context
import com.alphamessenger.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.alphamessenger.AlphaMessengerCore
import uniffi.alphamessenger.ChatInfo
import uniffi.alphamessenger.IdentityInfo
import uniffi.alphamessenger.MessageInfo
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized FFI wrapper — single source of truth for all core interactions.
 *
 * Every ViewModel and Service should talk to the core exclusively through this
 * repository so that callback wiring, error mapping, and lifecycle are handled
 * in exactly one place.
 */
@androidx.compose.runtime.Stable
@Singleton
class AlphaCoreRepository @Inject constructor(
    private val coreHolder: CoreHolder,
    private val callbackBridge: CallbackBridge,
    private val recoveryKdfPublicStore: RecoveryKdfPublicStore,
    private val migrationStateStore: MigrationStateStore,
    @ApplicationContext private val context: Context,
    private val strings: com.alphamessenger.util.Strings
) {
    /**
     * The live core, resolved through [CoreHolder]. Throws if
     * accessed before unlock — which makes a pre-unlock DB access impossible.
     * All existing `core.xxx()` call sites keep working unchanged; the core is
     * just no longer injected directly.
     */
    // @get:JvmName avoids the synthesized getter clashing with the existing
    // public `fun getCore()` below (both would otherwise be getCore()).
    @get:JvmName("coreOrThrow")
    private val core: AlphaMessengerCore get() = coreHolder.require()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    /** Run [block] on the repository's application-lived scope (survives a screen
     *  being destroyed). Used for fire-and-forget work that must finish even if
     *  the originating ViewModel is cleared — e.g. forwarding media (re-upload). */
    fun runDetached(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** The same application-lived scope for work that needs ITS OWN job: a send
     *  cancellable by ✕ that outlives leaving the screen. [runDetached] doesn't fit
     *  here — it doesn't hand the job out. */
    val detachedScope: CoroutineScope get() = scope

    /** Every transfer into the core runs under it, whoever started it. */
    val keepAwake = KeepAwake(context, "alpha:transfer")

    private val initialized = AtomicBoolean(false)
    private val callbacksInitialized = AtomicBoolean(false)

    // --- Callback flows ---

    private val _messageReceived = MutableSharedFlow<MessageInfo>(extraBufferCapacity = 64)
    val messageReceived: SharedFlow<MessageInfo> = _messageReceived.asSharedFlow()

    private val _deliveryUpdate = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val deliveryUpdate: SharedFlow<Pair<String, String>> = _deliveryUpdate.asSharedFlow()

    private val _syncComplete = MutableSharedFlow<ULong>(extraBufferCapacity = 8)
    val syncComplete: SharedFlow<ULong> = _syncComplete.asSharedFlow()

    /** The receiving side's progress (see [CallbackBridge.syncState]). */
    val syncState: StateFlow<SyncState?> get() = callbackBridge.syncState

    /** (chat id, message JSON) of every media message as it arrives. */
    val incomingMedia: SharedFlow<Pair<String, String>> get() = callbackBridge.incomingMedia

    // The core joined or left the reserve relay: our KeyPackage goes there and
    // contacts learn where we listen now.
    init {
        scope.launch {
            var wasReserve: Boolean? = null
            callbackBridge.syncState.collect { s ->
                if (s == null) return@collect
                val reserve = s.link == "reserve"
                if (wasReserve != null && reserve != wasReserve) {
                    runCatching { core.`reserveRelayChanged`() }
                    announceRelaysSoon()
                }
                wasReserve = reserve
            }
        }
    }

    private val _chatCreated = MutableSharedFlow<ChatInfo>(extraBufferCapacity = 16)
    val chatCreated: SharedFlow<ChatInfo> = _chatCreated.asSharedFlow()

    /** Notify subscribers (e.g. the chat list) that some chat row was mutated
     *  outside the normal incoming-message path: pinned/muted/archived/marked
     *  read. Subscribers re-fetch listChats() to refresh badges and ordering. */
    private val _chatListDirty = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val chatListDirty: SharedFlow<Unit> = _chatListDirty.asSharedFlow()
    fun notifyChatListDirty() { _chatListDirty.tryEmit(Unit) }

    private val _messagesExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    /** Some disappearing messages were removed — by any sweep, in or out of the chat. */
    val messagesExpired: SharedFlow<Unit> = _messagesExpired.asSharedFlow()

    /** Profile (display_name / bio / avatar_path) just changed. Listeners
     *  re-read via `getMyProfile()` so the new avatar shows up in TopBar,
     *  Settings header, Profile screen, etc. */
    private val _profileChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val profileChanged: SharedFlow<Unit> = _profileChanged.asSharedFlow()

    private val _memberAdded = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val memberAdded: SharedFlow<Pair<String, String>> = _memberAdded.asSharedFlow()

    private val _memberRemoved = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val memberRemoved: SharedFlow<Pair<String, String>> = _memberRemoved.asSharedFlow()

    private val _notificationReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val notificationReceived: SharedFlow<Unit> = _notificationReceived.asSharedFlow()

    /** Peer-profile updates from incoming `AlphaMessage::ProfileUpdate` events.
     *  Carries the MLS leaf-signature hex id. Consumers refresh `getPeerProfile`. */
    private val _peerProfileUpdated = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val peerProfileUpdated: SharedFlow<String> = _peerProfileUpdated.asSharedFlow()

    private val _chatMetadataUpdated = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val chatMetadataUpdated: SharedFlow<String> = _chatMetadataUpdated.asSharedFlow()

    /** Presence: emits (userHex → status) for peers whose effective status just
     *  changed. Driven by the presence tick; the UI collects it. */
    private val _presenceChanged = MutableSharedFlow<Map<String, String>>(extraBufferCapacity = 16)
    val presenceChanged: SharedFlow<Map<String, String>> = _presenceChanged.asSharedFlow()

    /** G-7: emits the chatId whenever its pending join-request list changes
     *  (a new request arrived, or one was accepted/rejected) so the group-info
     *  screen can refresh the "Join requests" section live. */
    private val _pendingJoinsChanged = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val pendingJoinsChanged: SharedFlow<String> = _pendingJoinsChanged.asSharedFlow()

    /** G-11: emits whenever the 1:1 user blocklist changes (block/unblock). */
    private val _blockListChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val blockListChanged: SharedFlow<Unit> = _blockListChanged.asSharedFlow()

    /** Live typing/recording presence, keyed by chatId. Holds at most one
     *  active peer per chat (latest wins). Entries auto-expire after
     *  [PRESENCE_TTL_MS] via a background pruning ticker, so a lost "paused"
     *  signal never leaves an indicator stuck on screen. Consumed by the
     *  chat screen (top-bar subtitle + bubble) and the chat list (preview). */
    private val _chatPresence = MutableStateFlow<Map<String, ChatPresence>>(emptyMap())
    val chatPresence: StateFlow<Map<String, ChatPresence>> = _chatPresence.asStateFlow()

    /** The same for channel discussions, keyed by (chatId, post id): typing in a
     *  post's comments shows there only — never in the channel or the chat list. */
    private val _threadPresence = MutableStateFlow<Map<Pair<String, String>, ChatPresence>>(emptyMap())
    val threadPresence: StateFlow<Map<Pair<String, String>, ChatPresence>> = _threadPresence.asStateFlow()

    // --- Calls module: incoming call signals (GiftWrap/MLS ChatState) ---
    /** A dedicated flow for the calls module — filters ChatState signals that
     *  start with the "call:" prefix (encoded [com.alphamessenger.calls.CallSignal]s).
     *  Kept SEPARATE from [chatPresence] so the calls module is fully decoupled
     *  from chat presence logic. Triple = (chatId, senderId, encodedSignal). */
    /** Group call traffic: (chatId, sender member id, state) for "gcall:" and "room:call:". */
    private val _groupCallStates = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 256)
    val groupCallStates: SharedFlow<Triple<String, String, String>> = _groupCallStates.asSharedFlow()

    private val _callSignals = MutableSharedFlow<ChatStateSignal>(extraBufferCapacity = 64)
    /** 1:1 call signals as they came, with their age. */
    val callSignals: SharedFlow<ChatStateSignal> = _callSignals.asSharedFlow()

    // --- Callback bridge wiring ---

    /**
     * Registers [CallbackBridge] as the FFI callback receiver and wires its
     * events into this repository's SharedFlows.
     *
     * Must be called exactly once, early in the application lifecycle
     * (typically [com.alphamessenger.AlphaMessengerApp.onCreate]).
     */
    fun initializeCallbacks() {
        // The core is built only after unlock. If called before the
        // DB is unlocked (e.g. a legacy Application.onCreate call), no-op — the
        // single DB-lifecycle entrypoint (UnlockManager) invokes this again right
        // after the core is installed. Guard BEFORE the once-flag so the real
        // post-unlock call is not skipped.
        if (!coreHolder.isUnlocked()) return
        if (callbacksInitialized.getAndSet(true)) return

        // Register the bridge with the Rust core
        core.`setCallback`(callbackBridge)

        // The core enforces the block list too (invitations, calls): hand it the
        // people blocked before it knew about them. Idempotent.
        runCatching { UserBlocklist(context).blocked().forEach { core.`setUserBlocked`(it.npub, true) } }

        // Forward bridge events into the repository flows
        scope.launch {
            callbackBridge.messageReceived.collect { message ->
                // A real incoming message supersedes any transient
                // typing/recording signal for that chat, even if the sender's
                // final "paused" arrives late or is dropped. A read mark, reaction,
                // edit or delete does not: those come while they keep typing.
                if (message.msgType !in NOT_A_NEW_MESSAGE) {
                    val currentPresence = _chatPresence.value
                    if (message.chatId in currentPresence) {
                        _chatPresence.value = currentPresence.toMutableMap().apply {
                            remove(message.chatId)
                        }
                    }
                    if (_threadPresence.value.keys.any { it.first == message.chatId }) {
                        _threadPresence.value = _threadPresence.value.filterKeys { it.first != message.chatId }
                    }
                }
                _messageReceived.tryEmit(message)
            }
        }
        scope.launch {
            callbackBridge.deliveryUpdate.collect { (messageId, status) ->
                _deliveryUpdate.tryEmit(messageId to status)
                // The chat list shows the last message's checks too: it refreshes
                // on "delivered" / "read", not only once the chat is opened.
                if (status == "delivered" || status == "read") notifyChatListDirty()
            }
        }
        scope.launch {
            callbackBridge.syncComplete.collect { newMessageCount ->
                _syncComplete.tryEmit(newMessageCount)
            }
        }
        scope.launch {
            callbackBridge.chatCreated.collect { (chatId, chatType) ->
                // Construct a ChatInfo from the callback data.
                // The full ChatInfo will be available when ViewModels
                // call listChats(), but this is sufficient for
                // immediate UI feedback (e.g. navigating to a new chat).
                val chatInfo = ChatInfo(
                    id = chatId,
                    chatType = chatType,
                    name = null,
                    epoch = 0u,
                    createdAt = "",
                    lastMessagePreview = "",
                    lastMessageAt = "",
                    lastMessageKind = "",
                    lastMessageOutgoing = false,
                    lastMessageStatus = "",
                    unreadCount = 0u,
                    unreadOverride = false,
                    pinnedAt = 0u,
                    mutedUntil = 0u,
                    archivedAt = 0u,
                    avatarPath = "",
                    description = "",
                    ephemeralSeconds = 0u,
                    lastMessageUnplayed = false,
                    memberCount = 0u,
                    myRole = "",
                    channelSettings = "",
                )
                _chatCreated.tryEmit(chatInfo)
                // A secret chat carries nothing of our profile, and re-sending it
                // everywhere made every peer re-fetch our avatar.
                if (chatType == "secret") return@collect

                // 8.8 v2: a brand-new chat just appeared — either we just
                // added a contact (and Welcome was sent) or we just joined
                // via someone else's Welcome. In both cases push our
                // current profile so the peer sees our name + avatar in
                // their first render of the chat. Without this, the
                // realtime/catch-up paths don't have access to `self` in
                // the spawned task and the auto-broadcast we tried to wire
                // on the Rust side never fires.
                runCatching {
                    withContext(Dispatchers.IO) { core.`broadcastMyProfileNow`() }
                    // A new contact also gets our stories meant for all contacts.
                    withContext(Dispatchers.IO) { runCatching { core.`shareStoriesWithContacts`() } }
                }.onFailure { e ->
                    android.util.Log.w("AlphaRepo",
                        "post-chat-created broadcast failed (non-fatal): ${e.message}")
                }

                // Asymmetric-avatar fix: ALSO ask the peer for their profile.
                // The creator's broadcast goes out BEFORE the joiner has joined
                // (MLS epoch), so the joiner would never decrypt the creator's
                // avatar. Each side requesting → the peer replies with a fresh
                // ProfileUpdate at the current epoch → both sides get the avatar.
                runCatching {
                    withContext(Dispatchers.IO) { core.`requestGroupProfiles`(chatId) }
                }.onFailure { e ->
                    android.util.Log.w("AlphaRepo",
                        "post-chat-created profile request failed (non-fatal): ${e.message}")
                }
            }
        }
        scope.launch {
            callbackBridge.memberAdded.collect { (chatId, memberId) ->
                _memberAdded.tryEmit(chatId to memberId)
            }
        }
        scope.launch {
            callbackBridge.memberRemoved.collect { (chatId, memberId) ->
                _memberRemoved.tryEmit(chatId to memberId)
            }
        }
        scope.launch {
            callbackBridge.notificationReceived.collect {
                _notificationReceived.tryEmit(Unit)
            }
        }
        scope.launch {
            callbackBridge.peerProfileUpdated.collect { senderId ->
                _peerProfileUpdated.tryEmit(senderId)
            }
        }
        scope.launch {
            callbackBridge.chatMetadataUpdated.collect { chatId ->
                _chatMetadataUpdated.tryEmit(chatId)
                notifyChatListDirty()
            }
        }
        // Our other phones: their notes are applied as they come; one that
        // unlinked this phone wipes it.
        scope.launch {
            callbackBridge.devicesEvent.collect { kind ->
                when (kind) {
                    "inbox" -> launch { processDeviceInbox() }
                    "unlinked" -> com.alphamessenger.data.PanicWipe.wipe(context)
                    else -> {
                        if (kind == "history" || kind == "profile") {
                            _profileChanged.tryEmit(Unit)
                            notifyChatListDirty()
                        }
                        _devicesChanged.tryEmit(kind)
                    }
                }
            }
        }
        // G-7: incoming join-by-link request. We (OWNER/ADMIN) drop it
        // outright if the requester is banned; otherwise we store it as a PENDING
        // request and surface it in group-info for a MANUAL Accept/Reject instead
        // of a silent auto-approve.
        scope.launch {
            callbackBridge.joinRequested.collect { (chatId, requesterNpub, token) ->
                val moderation = GroupModeration(context)
                if (moderation.isBanned(chatId, requesterNpub)) {
                    android.util.Log.i("AlphaCoreRepository", "join request from banned $requesterNpub — dropped")
                    return@collect
                }
                // Who asks, as their request says (name and photo they chose to
                // show) — at once, no lookup that first shows a bare key. A
                // request from an older app carries none: look them up then.
                val (name, avatarPath) = joinRequestProfile(chatId, requesterNpub).takeIf { it.first.isNotBlank() }
                    ?: runCatching { resolveProfileWithAvatar(requesterNpub) }.getOrDefault("" to "")
                moderation.addPendingJoin(chatId, requesterNpub, token, name, avatarPath)
                // A channel joined "by link" lets anyone with the link in at once;
                // "by request" (and every group) waits for the owner's approval.
                val cfg = channelCfg(chatId)
                // …and so does the persona answering our admin invitation.
                if (adminAnswers.remove(chatId to requesterNpub) ||
                    (cfg.on && cfg.join == com.alphamessenger.ui.channel.ChannelCfg.JOIN_LINK)) {
                    acceptJoinRequest(chatId, requesterNpub)
                    return@collect
                }
                _pendingJoinsChanged.tryEmit(chatId)
                notifyChatListDirty()
                android.util.Log.i("AlphaCoreRepository", "join request from $requesterNpub queued for approval")
            }
        }
        // Leave flow: when a member announces they left, an OWNER/ADMIN performs
        // the real MLS Remove+Commit so the leaver drops out for everyone (their
        // own self-leave is only a proposal nobody would otherwise commit).
        scope.launch {
            callbackBridge.memberLeaveRequested.collect { (chatId, leaverNpub) ->
                runCatching {
                    val arr = org.json.JSONArray(core.`listGroupMembers`(chatId))
                    val members = (0 until arr.length()).map { arr.getJSONObject(it) }
                    // Am I owner/admin here?
                    val myRole = members.firstOrNull { it.optBoolean("is_self", false) }
                        ?.optString("role", "member") ?: "member"
                    if (myRole != "owner" && myRole != "admin") return@collect
                    // Find the leaver's leaf and remove them (skip self / owner row).
                    val leaver = members.firstOrNull {
                        it.optString("npub") == leaverNpub && !it.optBoolean("is_self", false)
                    } ?: return@collect
                    val leaf = leaver.optInt("leaf_index", -1)
                    if (leaf >= 0) {
                        core.`removeMember`(chatId, leaf.toUInt())
                        android.util.Log.i("AlphaCoreRepository", "auto-removed leaver $leaverNpub (leaf $leaf) from $chatId")
                        notifyChatListDirty()
                    }
                }.onFailure { android.util.Log.w("AlphaCoreRepository", "auto-remove leaver failed: ${it.message}") }
            }
        }
        // Incoming typing/recording signals → presence map.
        scope.launch {
            callbackBridge.chatState.collect { signal ->
                val (chatId, senderId, state, thread) = signal
                // Route call signals to the dedicated calls flow (decoupled
                // from presence). Plain typing/recording signals feed presence.
                if (state.startsWith("gcall:") || state.startsWith("room:call:")) {
                    _groupCallStates.tryEmit(Triple(chatId, senderId, state))
                } else if (state.startsWith("call:") || state.startsWith("call-elsewhere:")) {
                    _callSignals.tryEmit(signal)
                } else if (thread.isNotEmpty()) {
                    val key = chatId to thread
                    _threadPresence.value = _threadPresence.value.toMutableMap().apply {
                        if (state == "paused") remove(key)
                        else put(key, ChatPresence(chatId, senderId, state, System.currentTimeMillis(), signal.sentMs))
                    }
                } else {
                    _chatPresence.value = _chatPresence.value.toMutableMap().apply {
                        if (state == "paused") {
                            remove(chatId)
                        } else {
                            put(chatId, ChatPresence(chatId, senderId, state, System.currentTimeMillis(), signal.sentMs))
                        }
                    }
                }
            }
        }
        // Prune stale typing/recording indicators so a dropped "paused" doesn't
        // leave one stuck. BATTERY: event-driven, NOT a fixed 2.5s heartbeat.
        // It suspends with ZERO CPU while nobody is typing (the normal case),
        // wakes only when a presence entry actually exists, prunes at the TTL
        // cadence until the map empties, then suspends again.
        scope.launch {
            while (true) {
                // Cheap suspend until there is at least one entry to age out.
                kotlinx.coroutines.flow.combine(_chatPresence, _threadPresence) { a, b -> a.isNotEmpty() || b.isNotEmpty() }
                    .first { it }
                kotlinx.coroutines.delay(PRESENCE_PRUNE_MS)
                val now = System.currentTimeMillis()
                val current = _chatPresence.value
                val pruned = current.filterValues { now - it.atMs < PRESENCE_TTL_MS }
                if (pruned.size != current.size) _chatPresence.value = pruned
                val threads = _threadPresence.value
                val prunedThreads = threads.filterValues { now - it.atMs < PRESENCE_TTL_MS }
                if (prunedThreads.size != threads.size) _threadPresence.value = prunedThreads
            }
        }
    }

    /** Broadcast a transient typing / recording signal. `state` is "typing",
     *  "recording" or "paused". Fire-and-forget — never throws to the caller. */
    suspend fun sendChatState(chatId: String, state: String, thread: String? = null) = withContext(Dispatchers.IO) {
        runCatching { core.`sendChatState`(chatId, state, thread) }
        Unit
    }

    /** Like [sendChatState] but launched on the repository's own scope, so it
     *  survives a caller (e.g. a ViewModel) being torn down. Used to emit a
     *  final "paused" when leaving a chat. */
    // --- Rooms (group calls) ---

    suspend fun roomAnnounce(chatId: String, room: String, state: String) = withContext(Dispatchers.IO) {
        runCatching { core.`roomAnnounce`(chatId, room, state) }
    }

    /** Who is in the room now: member id → their announced state. */
    suspend fun roomMembers(chatId: String, room: String): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val a = org.json.JSONArray(core.`roomMembers`(chatId, room))
            (0 until a.length()).associate { i -> a.getJSONObject(i).let { it.optString("member") to it.optString("state") } }
        }.getOrDefault(emptyMap())
    }

    /** A transient signal for one member only. */
    fun sendChatSignalDetached(chatId: String, to: String, payload: String) {
        scope.launch { runCatching { core.`sendChatSignal`(chatId, to, payload) } }
    }

    suspend fun ownMemberId(chatId: String): String = withContext(Dispatchers.IO) {
        runCatching { core.`ownMemberId`(chatId) }.getOrDefault("")
    }

    fun sendChatStateDetached(chatId: String, state: String) {
        scope.launch { runCatching { core.`sendChatState`(chatId, state, null) } }
    }

    // --- Group metadata ---

    /** Update group name / description / avatar locally + broadcast to
     *  all members through MLS. Null = leave field untouched. Empty
     *  string = clear. */
    suspend fun setGroupMetadata(
        chatId: String,
        name: String? = null,
        description: String? = null,
        avatarPath: String? = null,
    ) = withContext(Dispatchers.IO) {
        core.`setGroupMetadata`(chatId, name, description, avatarPath)
        notifyChatListDirty()
        // Our own change: every open screen of this chat refreshes like on a peer's.
        _chatMetadataUpdated.tryEmit(chatId)
    }

    // --- Identity ---

    suspend fun getIdentityInfo(): IdentityInfo = withContext(Dispatchers.IO) {
        core.`getIdentityInfo`()
    }

    // Own npub is stable for the whole session. Memoize it so the chat-open path
    // (foldToBubbles needs it to decide message side/ticks) never races an async
    // identity lookup — the first render always folds on the correct side.
    @Volatile private var cachedOwnNpub: String = ""
    fun ownNpubCached(): String = cachedOwnNpub
    suspend fun ownNpub(): String {
        cachedOwnNpub.takeIf { it.isNotBlank() }?.let { return it }
        return withContext(Dispatchers.IO) {
            core.`getIdentityInfo`().npub.also { cachedOwnNpub = it }
        }
    }

    suspend fun exportPublicKeyPackage(): String = withContext(Dispatchers.IO) {
        core.`exportPublicKeyPackage`()
    }

    /**
     * Export the Nostr identity secret key as a bech32 `nsec1…` string.
     *
     * This is the **identity / login** key (RoadmapE2EE §1). It proves
     * ownership of the account and lets the user recover their npub on a new
     * device. It is **not** the data-encryption key: history, MLS state and
     * backups are encrypted with keys derived from the Recovery Password
     * — so a leaked nsec never exposes message history.
     *
     * The caller MUST treat the returned string as a password-grade secret:
     * never log it, never persist it unencrypted, let the user copy/save it
     * and wipe the clipboard after a timeout.
     */
    suspend fun exportNsec(): String = withContext(Dispatchers.IO) {
        core.`exportNsec`()
    }

    /**
     * Import a Nostr identity from an `nsec1…` (or 64-char hex) secret key.
     *
     * Rebuilds the dual identity, persists it, and swaps the active identity.
     * After import the caller should re-initialize the MLS session / rejoin
     * groups (the Nostr npub is stable from here on).
     *
     * Throws [uniffi.alphamessenger.MessengerException] on an invalid key.
     */
    suspend fun importIdentity(nsecOrHex: String) = withContext(Dispatchers.IO) {
        core.`importIdentity`(nsecOrHex.trim())
    }

    // --- Secret phrase (BIP-39 / NIP-06) ---

    /** 12 fresh words; the account key derives from them. */
    fun mnemonicGenerate(): String = uniffi.alphamessenger.mnemonicGenerate()

    /** The nsec the phrase stands for; throws on a wrong word or a bad checksum. */
    fun mnemonicToNsec(words: String): String = uniffi.alphamessenger.mnemonicToNsec(words)

    /** The 2048 English BIP-39 words (cached), for input suggestions. */
    val mnemonicWordlist: List<String> by lazy { uniffi.alphamessenger.mnemonicWordlist() }

    suspend fun storeMnemonic(words: String) = withContext(Dispatchers.IO) {
        core.`storeMnemonic`(words)
    }

    suspend fun storedMnemonic(): String? = withContext(Dispatchers.IO) {
        runCatching { core.`storedMnemonic`() }.getOrNull()
    }

    // --- Recovery Password ---

    /** Has the user already set a Recovery Password on this device? */
    fun hasRecoveryPassword(): Boolean = core.`hasRecoveryPassword`()

    /**
     * Set (or replace) the Recovery Password. Argon2id-heavy — always call
     * off the UI thread (this suspend wrapper runs on Dispatchers.IO).
     *
     * Persists the public salt/params + a non-reversible verifier. The
     * password itself and the derived master key are NEVER stored.
     */
    suspend fun setRecoveryPassword(password: String) = withContext(Dispatchers.IO) {
        core.`setRecoveryPassword`(password)
        // Mirror the freshly-written PUBLIC KDF material (salt/params/
        // verifier) outside the encrypted DB so a future launch can derive the
        // DatabaseKey before the DB is open. Best-effort — a mirror failure must
        // not break setting the password.
        runCatching { recoveryKdfPublicStore.put(core.`exportRecoveryKdfPublic`()) }
        // The database moves to this password's key at the next start: remember that
        // key now, or the very first restart would stop and ask for it.
        runCatching {
            val json = recoveryKdfPublicStore.get() ?: return@runCatching
            val key = uniffi.alphamessenger.deriveMasterKeyFromPublic(password, json).map { it.toByte() }.toByteArray()
            RememberedDbKey.save(context, key)
            key.fill(0)
        }
    }

    /** Mirror the public Recovery-Password KDF material (salt/params/verifier) out of the DB. */
    suspend fun mirrorRecoveryKdf() = withContext(Dispatchers.IO) {
        runCatching { recoveryKdfPublicStore.put(core.`exportRecoveryKdfPublic`()) }
    }

    /**
     * Change the Recovery Password. Verifies [old], re-keys the live DB to
     * the new DatabaseKey (backup + rekey + verify + rollback in Rust), updates
     * the public KDF mirror + migration hint, and returns the NEW 32-byte
     * MasterDataKey so the caller can re-cache it for biometric unlock (then
     * zeroize it). Throws if [old] is wrong or the re-key fails.
     */
    suspend fun changeRecoveryPassword(old: String, new: String): ByteArray =
        withContext(Dispatchers.IO) {
            val u = core.`changeRecoveryPassword`(old, new)
            val bytes = ByteArray(u.size) { u[it].toByte() }
            // KDF material changed (new salt/verifier) → refresh the pre-open
            // mirror and the migration hint so the next launch derives the new key.
            runCatching { recoveryKdfPublicStore.put(core.`exportRecoveryKdfPublic`()) }
            runCatching { migrationStateStore.setHint(core.`dbRekeyState`()) }
            runCatching { migrationStateStore.setRecoveryAttempted(false) }
            RememberedDbKey.save(context, bytes)
            bytes
        }

    /**
     * Verify a candidate password against the stored verifier (yes/no only).
     * Returns false (not an exception) if no password is set yet, or if the
     * password is wrong. Used as a "current password" gate.
     */
    suspend fun verifyRecoveryPassword(password: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { core.`verifyRecoveryPassword`(password) }.getOrDefault(false)
        }

    /**
     * Derive the 32-byte MasterDataKey from the password.
     *
     * The returned [MasterDataKey] wraps the raw bytes and wipes them on
     * [close]. The caller caches it in the Android Keystore under biometrics
     * and must explicitly close it when done.
     */
    suspend fun deriveMasterKey(password: String): MasterDataKey =
        withContext(Dispatchers.IO) {
            val ubytes = core.`deriveMasterKey`(password)
            val bytes = ByteArray(ubytes.size)
            for (i in ubytes.indices) bytes[i] = ubytes[i].toByte()
            MasterDataKey(bytes)
        }

    // --- DB rekey migration ---

    /** Current authoritative migration state from the DB KV: legacy/in_progress/
     *  migrated/confirmed. */
    fun dbRekeyState(): String = core.`dbRekeyState`()

    /**
     * Migrate the live SQLCipher DB to the production DatabaseKey derived from
     * [masterKey] (the 32-byte MasterDataKey). Returns true if migrated (or
     * already). Throws on failure — on failure the Rust side has restored the
     * pre-rekey backup and the app should be restarted.
     *
     * Keeps the plaintext pre-open HINT in sync with the DB KV truth: in_progress
     * before, migrated on success, legacy on failure (so a crash mid-migration is
     * resolved correctly by the next startup's recovery).
     *
     * Argon2id-independent but does a full-DB rekey — call off the UI thread.
     */
    suspend fun migrateDbToRecoveryKey(masterKey: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            migrationStateStore.setHint(MigrationStateStore.IN_PROGRESS)
            try {
                val ubytes = masterKey.map { it.toUByte() }
                val ok = core.`migrateDbToRecoveryKey`(ubytes)
                migrationStateStore.setHint(MigrationStateStore.MIGRATED)
                ok
            } catch (e: Exception) {
                // Rust restored the backup; reflect the rolled-back truth.
                migrationStateStore.setHint(MigrationStateStore.LEGACY)
                throw e
            }
        }

    /**
     * Finalize a completed migration after a proven working session (start +
     * read history + send + receive). Deletes the backup + recovery flag and
     * advances state to confirmed. Best-effort; safe to call more than once.
     */
    suspend fun confirmDbMigration() = withContext(Dispatchers.IO) {
        core.`confirmDbMigration`()
        migrationStateStore.setHint(MigrationStateStore.CONFIRMED)
    }

    // --- Backup container ---

    /**
     * Export a full encrypted backup of the user data, sealed with the
     * BackupFileKey derived from the [password]. Returns the complete
     * `header || ciphertext` container bytes, ready to write to disk as
     * `mybackup.enc`.
     *
     * Requires a Recovery Password to have been set. CPU-heavy (Argon2id +
     * full DB dump) — always call off the UI thread.
     */
    suspend fun exportBackup(password: String): ByteArray =
        withContext(Dispatchers.IO) {
            // ByteArray across the FFI (UDL `bytes`): a List<UByte> boxed every
            // byte and a multi-MB backup ran the app out of memory.
            snapshotAppSettings()
            core.`exportBackup`(password)
        }

    /** The app's settings go into the database first, so the backup carries them. */
    private suspend fun snapshotAppSettings() {
        runCatching { core.`setAppSettings`(AppSettingsBackup.collect(context)) }
            .onFailure { android.util.Log.w("AlphaBackup", "app settings not saved: ${it.message}") }
    }

    /** After a restore: the settings the backup carried, back where the app reads them. */
    suspend fun restoreAppSettings() = withContext(Dispatchers.IO) {
        runCatching { AppSettingsBackup.apply(context, core.`appSettings`()) }
            .onFailure { android.util.Log.w("AlphaBackup", "app settings not restored: ${it.message}") }
    }

    /**
     * Decrypt + summarize a backup WITHOUT touching the live database. The UI
     * uses this to show "this backup contains N tables" before the user
     * commits to a destructive restore.
     *
     * Wrong password ⇒ throws [uniffi.alphamessenger.MessengerException]
     * (CryptoError); no data is mutated.
     */
    suspend fun inspectBackup(container: ByteArray, password: String): String =
        withContext(Dispatchers.IO) {
            core.`inspectBackup`(container, password)
        }

    /**
     * Decrypt + replay a backup into the live database (restore path).
     *
     * Atomic: a wrong password is rejected via the AEAD tag BEFORE any SQL
     * runs, and a mid-restore failure rolls the transaction back, so the live
     * DB is never left half-overwritten. Intended for an empty DB.
     */
    /** After a restore: this phone now holds the account's place in the groups; the old one stops. */
    suspend fun claimThisDevice() = withContext(Dispatchers.IO) { core.`claimThisDevice`() }

    /** Since when another phone holds this account (unix seconds), 0 = still this one. */
    suspend fun deviceReplacedAt(): Long = withContext(Dispatchers.IO) { core.`deviceReplacedAt`() }

    suspend fun importBackup(container: ByteArray, password: String) =
        withContext(Dispatchers.IO) {
            core.`importBackup`(container, password)
        }

    // --- Cloud backup via Blossom ---

    /**
     * Upload an encrypted backup to Blossom, authenticated by the user's own
     * nsec (kind 24242 auth event). Returns the blob URL.
     *
     * Builds the AEAD backup container, uploads it, persists the URL+sha in
     * KV, and (optionally) publishes a Nostr replaceable event so other devices
     * can discover the backup by npub. CPU + network heavy — call off-UI.
     */
    fun autoBackupReady(): Boolean = runCatching { core.`autoBackupReady`() }.getOrDefault(false)

    suspend fun uploadBackupAuto(): String = withContext(Dispatchers.IO) {
        snapshotAppSettings()
        core.`uploadBackupAuto`()
    }

    suspend fun uploadBackup(password: String, publishNostrLink: Boolean): String =
        withContext(Dispatchers.IO) {
            snapshotAppSettings()
            core.`uploadBackup`(password, publishNostrLink)
        }

    /**
     * Look up a backup blob URL for the given npub by fetching the user's
     * kind 30078 replaceable event from relays. Restore on a new device:
     * knows the npub (from the imported nsec) and uses this to find the backup.
     *
     * Throws [uniffi.alphamessenger.MessengerException] if no link event exists.
     */
    suspend fun lookupBackupUrl(npubOrHex: String): String =
        withContext(Dispatchers.IO) {
            core.`lookupBackupUrl`(npubOrHex.trim())
        }

    /**
     * Download a backup container from a known Blossom URL. Returns the raw
     * bytes (`header || ciphertext`) — feed to [importBackup] with the Recovery
     * Password to restore. Does NOT decrypt.
     */
    suspend fun downloadBackup(blobUrl: String): ByteArray =
        withContext(Dispatchers.IO) {
            core.`downloadBackup`(blobUrl)
        }

    /**
     * JSON info about the last uploaded cloud backup, for the UI status card.
     * Shape: `{"has":bool,"url":"","sha":"","at":"","size":0}`.
     */
    fun getBackupInfo(): String = core.`getBackupInfo`()

    /** JSON list of group members (G-1): [{leaf_index, sender_id, npub, display_name, avatar_path, is_self}]. */
    suspend fun listGroupMembers(chatId: String): String = withContext(Dispatchers.IO) {
        runCatching { core.`listGroupMembers`(chatId) }.getOrDefault("[]")
    }

    /** Delete the cloud backup (Blossom blob + local reference). */
    suspend fun deleteCloudBackup() = withContext(Dispatchers.IO) {
        core.`deleteCloudBackup`()
    }

    // --- Chat ---

    suspend fun createDirectChat(peerKeyPackageBase64: String): ChatInfo =
        withContext(Dispatchers.IO) {
            core.`createDirectChat`(peerKeyPackageBase64)
        }

    /**
     * Add a contact by their Nostr public identity (npub bech32 or 64-char hex).
     *
     * End-to-end: fetches their published MLS KeyPackage from relays, builds a
     * Welcome, gift-wraps it to them via NIP-59, and persists a new 1:1 chat
     * locally. The peer's realtime listener will receive the gift-wrap and
     * auto-join the chat.
     *
     * Throws [uniffi.alphamessenger.MessengerException] with a real reason
     * (`InvalidArgument`, `NetworkError`, `CryptoError`) — surface it to the UI.
     */
    suspend fun addContactByNpub(npubOrHex: String): ChatInfo =
        withContext(Dispatchers.IO) {
            core.`addContactByNpub`(npubOrHex.trim())
        }

    /** True if a direct chat with this peer already exists (UI "already added"). */
    suspend fun directChatExists(npubOrHex: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { core.`directChatExists`(npubOrHex.trim()) }.getOrDefault(false)
        }

    suspend fun createGroup(name: String): ChatInfo = withContext(Dispatchers.IO) {
        core.`createGroup`(name)
    }

    /**
     * Create a group and immediately invite N peers by their npub.
     *
     * `npubs` is serialised to a JSON array on the wire because UniFFI's
     * sequence<string> support adds friction for the Kotlin side.
     * Throws if ANY peer couldn't be added (group still exists locally).
     */
    suspend fun createGroupWithMembers(name: String, npubs: List<String>): ChatInfo =
        withContext(Dispatchers.IO) {
            val json = com.google.gson.Gson().toJson(npubs)
            core.`createGroupWithMembers`(name, json)
        }

    /** Create a group (optionally with members) and set its avatar from a picked
     *  content Uri in one shot. The avatar is copied into the app's group-avatars
     *  dir and broadcast via setGroupMetadata. */
    suspend fun createGroupFull(
        name: String,
        npubs: List<String>,
        avatarUri: String?,
        anonymous: Boolean = false,
        description: String = "",
        protect: Boolean = false,
        history: Boolean = false,
    ): ChatInfo =
        withContext(Dispatchers.IO) {
            val info = when {
                // A hidden owner: we own it under a persona, members are added after.
                anonymous -> core.`createGroupAnonymous`(name).also { g ->
                    npubs.forEach { runCatching { core.`addMemberByNpub`(g.id, it.trim()) } }
                }
                npubs.isEmpty() -> core.`createGroup`(name)
                else -> core.`createGroupWithMembers`(name, com.google.gson.Gson().toJson(npubs))
            }
            if (description.isNotBlank()) runCatching { core.`setGroupMetadata`(info.id, null, description.trim(), null) }
            // Group settings travel to every member: the hidden-owner flag (the
            // core sets it from how the group was made) and forwarding protection.
            if (anonymous || protect || history) runCatching {
                core.`setChannelSettings`(info.id, com.alphamessenger.ui.channel.ChannelCfg(on = false, protect = protect, history = history).toJson())
            }
            if (!avatarUri.isNullOrBlank()) {
                runCatching {
                    // Use the SAME helper as the group-info screen: it resizes /
                    // compresses and writes to the canonical filesDir/group_<id>.jpg
                    // path that the renderer + setGroupMetadata expect. The earlier
                    // raw copy to a different dir is why the photo didn't apply.
                    val path = com.alphamessenger.ui.settings.saveAvatarToInternal(
                        context, android.net.Uri.parse(avatarUri), "group_${info.id}.jpg",
                        maxBytes = 190 * 1024,
                    )
                    if (!path.isNullOrBlank()) {
                        core.`setGroupMetadata`(info.id, null, null, path)
                    }
                }
            }
            info
        }

    /**
     * Add a participant to an existing group by their npub.
     * The peer needs to have published a KeyPackage on the configured relays.
     */
    suspend fun addMemberByNpub(chatId: String, npubOrHex: String) =
        withContext(Dispatchers.IO) {
            core.`addMemberByNpub`(chatId, npubOrHex.trim())
        }

    /** G-3: assign a group role ("owner"|"admin"|"moderator"|"member"). */
    suspend fun setMemberRole(chatId: String, npubOrHex: String, role: String) =
        withContext(Dispatchers.IO) {
            core.`setMemberRole`(chatId, npubOrHex.trim(), role)
        }

    /** G-3: transfer ownership to another member (owner-only). */
    suspend fun transferOwnership(chatId: String, npubOrHex: String) =
        withContext(Dispatchers.IO) {
            core.`transferOwnership`(chatId, npubOrHex.trim())
        }

    /** G-4: mute (untilTs>0 unix secs) or unmute (<=0) a member. */
    suspend fun setMemberMute(chatId: String, npubOrHex: String, untilTs: Long) =
        withContext(Dispatchers.IO) { core.`setMemberMute`(chatId, npubOrHex.trim(), untilTs) }

    /** G-4: mute expiry (unix secs) for a member, 0 if not muted. */
    suspend fun groupMuteUntil(chatId: String, npubOrHex: String): Long =
        withContext(Dispatchers.IO) {
            runCatching { core.`groupMuteUntil`(chatId, npubOrHex.trim()) }.getOrDefault(0L)
        }

    /** G-8: current group permissions (whoCanWrite, whoCanInvite, whoCanEdit),
     *  each "all"|"admins". Defaults on error: write=all, invite/edit=admins. */
    suspend fun groupPermissions(chatId: String): Triple<String, String, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val o = org.json.JSONObject(core.`groupPermissions`(chatId))
                Triple(o.optString("write", "all"), o.optString("invite", "admins"), o.optString("edit", "admins"))
            }.getOrDefault(Triple("all", "admins", "admins"))
        }

    /** G-8: set the group permission policy (owner only; throws otherwise). */
    suspend fun setGroupPermissions(chatId: String, write: String, invite: String, edit: String) =
        withContext(Dispatchers.IO) {
            core.`setGroupPermissions`(chatId, write, invite, edit)
        }

    // --- Channels (a group where only admins post) ---

    /** Create a channel: an empty group, its description and avatar, then the
     *  channel settings (which also make writing admins-only for everyone). */
    suspend fun createChannel(
        name: String,
        description: String,
        avatarUri: String?,
        cfg: com.alphamessenger.ui.channel.ChannelCfg,
    ): ChatInfo = withContext(Dispatchers.IO) {
        val info = createGroupFull(name, emptyList(), avatarUri, anonymous = cfg.anon)
        if (description.isNotBlank()) runCatching { core.`setGroupMetadata`(info.id, null, description.trim(), null) }
        core.`setChannelSettings`(info.id, cfg.copy(on = true).toJson())
        _chatMetadataUpdated.tryEmit(info.id)
        info
    }

    /** May we write here (a post: [postId] empty; else a comment of [kind]) — and why not. */
    suspend fun writeStatus(chatId: String, postId: String, kind: String): com.alphamessenger.ui.channel.WriteStatus =
        withContext(Dispatchers.IO) {
            com.alphamessenger.ui.channel.WriteStatus.parse(runCatching { core.`writeStatus`(chatId, postId, kind) }.getOrNull())
        }

    suspend fun channelCfg(chatId: String): com.alphamessenger.ui.channel.ChannelCfg = withContext(Dispatchers.IO) {
        com.alphamessenger.ui.channel.ChannelCfg.parse(runCatching { core.`channelSettings`(chatId) }.getOrNull())
    }

    suspend fun setChannelCfg(chatId: String, cfg: com.alphamessenger.ui.channel.ChannelCfg) = withContext(Dispatchers.IO) {
        core.`setChannelSettings`(chatId, cfg.toJson())
        _chatMetadataUpdated.tryEmit(chatId)
    }

    /** We saw these posts (sent once per post; nothing in ghost mode). */
    suspend fun channelSeen(chatId: String, postIds: List<String>) = withContext(Dispatchers.IO) {
        if (postIds.isEmpty()) return@withContext
        runCatching { core.`channelSeen`(chatId, org.json.JSONArray(postIds.take(100)).toString()) }
        Unit
    }

    /** We forwarded these posts somewhere (the channel only learns the count). */
    suspend fun channelForwarded(chatId: String, postIds: List<String>) = withContext(Dispatchers.IO) {
        if (postIds.isEmpty()) return@withContext
        runCatching { core.`channelForwarded`(chatId, org.json.JSONArray(postIds.take(100)).toString()) }
        Unit
    }

    /** Forwards of each post. */
    suspend fun channelForwards(chatId: String, postIds: List<String>): Map<String, Int> = withContext(Dispatchers.IO) {
        runCatching {
            val o = org.json.JSONObject(core.`channelForwards`(chatId, org.json.JSONArray(postIds.take(500)).toString()))
            postIds.associateWith { o.optInt(it, 0) }
        }.getOrDefault(emptyMap())
    }

    /** A member of [chatId] by their message sender key: (npub, leaf index, role). */
    private suspend fun memberBySender(chatId: String, senderId: String): Triple<String, Int, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val arr = org.json.JSONArray(core.`listGroupMembers`(chatId))
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("sender_id") == senderId }
                ?.let { Triple(it.optString("npub"), it.optInt("leaf_index", -1), it.optString("role", "member")) }
        }.getOrNull()
    }

    /** A channel admin forbids a commenter to comment until [untilTs] (0 = for good, mapped far ahead). */
    suspend fun muteCommenter(chatId: String, senderId: String, name: String, untilTs: Long): Boolean = withContext(Dispatchers.IO) {
        val (npub, _, _) = memberBySender(chatId, senderId) ?: return@withContext false
        if (npub.isBlank()) return@withContext false
        val ok = runCatching { core.`setMemberMute`(chatId, npub, untilTs) }.isSuccess
        if (ok) runCatching { logGroupEvent(chatId, "mute", npub, name) }
        ok
    }

    /** A moderator lets a restricted commenter write again. */
    suspend fun unmuteCommenter(chatId: String, senderId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val (npub, _, _) = memberBySender(chatId, senderId) ?: return@withContext false
        if (npub.isBlank()) return@withContext false
        val ok = runCatching { core.`setMemberMute`(chatId, npub, 0L) }.isSuccess
        if (ok) runCatching { logGroupEvent(chatId, "unmute", npub, name) }
        ok
    }

    /** Until when this commenter may not write in [chatId] (unix secs; 0 = free). */
    suspend fun commenterMutedUntil(chatId: String, senderId: String): Long = withContext(Dispatchers.IO) {
        val npub = memberBySender(chatId, senderId)?.first?.takeIf { it.isNotBlank() } ?: return@withContext 0L
        val until = runCatching { core.`groupMuteUntil`(chatId, npub) }.getOrDefault(0L)
        if (until > System.currentTimeMillis() / 1000) until else 0L
    }

    /** Ban for good (the core sends the reason, then removes the member). */
    suspend fun banMember(chatId: String, npub: String, leaf: Int, reason: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`banMember`(chatId, npub, leaf.toUInt(), reason) }.isSuccess
            .also { if (it) _chatMetadataUpdated.tryEmit(chatId) }
    }

    /** Our role in a group or channel ("owner", "admin", …; "member" when unknown). */
    suspend fun myRoleIn(chatId: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val arr = org.json.JSONArray(core.`listGroupMembers`(chatId))
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optBoolean("is_self", false) }?.optString("role", "member")
        }.getOrNull() ?: "member"
    }

    /** We are banned here, or its owner deleted it: then nothing of it is left. */
    data class ChatGone(val reason: String, val at: Long, val disbanded: Boolean)

    /** Our ban in this chat (or the owner's deletion of it), or null. */
    suspend fun chatBan(chatId: String): ChatGone? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = core.`chatBan`(chatId)
            if (raw.isBlank()) null else org.json.JSONObject(raw).let {
                ChatGone(it.optString("reason"), it.optLong("at"), it.optBoolean("disbanded"))
            }
        }.getOrNull()
    }

    /** A channel admin removes and bans a commenter (same steps as from the member list). */
    suspend fun banCommenter(chatId: String, senderId: String, name: String, avatarPath: String): Boolean = withContext(Dispatchers.IO) {
        val (npub, leaf, role) = memberBySender(chatId, senderId) ?: return@withContext false
        if (npub.isBlank() || leaf < 0) return@withContext false
        val ok = runCatching { core.`banMember`(chatId, npub, leaf.toUInt(), "") }.isSuccess
        if (ok) {
            GroupModeration(context).addBan(chatId, npub, name, 0L, avatarPath, "")
            runCatching { logGroupEvent(chatId, "ban", npub, name) }
            _chatMetadataUpdated.tryEmit(chatId)
        }
        ok
    }

    /** Views of each post. */
    suspend fun channelViews(chatId: String, postIds: List<String>): Map<String, Int> = withContext(Dispatchers.IO) {
        runCatching {
            val o = org.json.JSONObject(core.`channelViews`(chatId, org.json.JSONArray(postIds.take(500)).toString()))
            postIds.associateWith { o.optInt(it, 0) }
        }.getOrDefault(emptyMap())
    }

    // --- Stories (end-to-end, per contact, they expire by themselves) ---

    suspend fun publishStory(paramsJson: String): String = withContext(Dispatchers.IO) {
        core.`publishStory`(paramsJson)
    }

    suspend fun storiesJson(): String = withContext(Dispatchers.IO) {
        runCatching { core.`storiesJson`() }.getOrDefault("{}")
    }

    /** Seen (reaction "") or reacted — the author learns it unless ghost mode is on. */
    suspend fun storySeen(storyId: String, reaction: String) = withContext(Dispatchers.IO) {
        runCatching { core.`storySeen`(storyId, reaction) }
        Unit
    }

    suspend fun deleteMyStory(storyId: String) = withContext(Dispatchers.IO) {
        core.`deleteMyStory`(storyId)
    }

    /** We took a screenshot of a contact's story: its author is told. */
    suspend fun storyScreenshot(storyId: String) = withContext(Dispatchers.IO) {
        runCatching { core.`storyScreenshot`(storyId) }
        Unit
    }

    /** Pinned messages of a chat (shared with its members), newest pin first. */
    suspend fun pinnedMessages(chatId: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val a = org.json.JSONArray(core.`pinnedMessagesJson`(chatId))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** Pin / unpin for everyone in the chat (throws when we may not pin). */
    suspend fun pinMessage(chatId: String, messageId: String, pin: Boolean) = withContext(Dispatchers.IO) {
        core.`pinMessage`(chatId, messageId, pin)
    }

    /** Which reactions the group allows (all for direct chats / unset groups). */
    suspend fun groupReactionPolicy(chatId: String): com.alphamessenger.ui.chat.ReactionPolicy =
        withContext(Dispatchers.IO) {
            runCatching {
                val o = org.json.JSONObject(core.`groupPermissions`(chatId))
                val arr = o.optJSONArray("reactions")
                com.alphamessenger.ui.chat.ReactionPolicy(
                    mode = o.optString("reactions_mode", com.alphamessenger.ui.chat.ReactionPolicy.MODE_ALL),
                    allowed = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) },
                )
            }.getOrDefault(com.alphamessenger.ui.chat.ReactionPolicy.ALL)
        }

    /** Set the group's reactions (owner only; throws otherwise). */
    suspend fun setGroupReactions(chatId: String, policy: com.alphamessenger.ui.chat.ReactionPolicy) =
        withContext(Dispatchers.IO) {
            core.`setGroupReactions`(chatId, policy.mode, org.json.JSONArray(policy.allowed).toString())
        }

    /** G-10: unix-secs until which the chat is temporarily closed (0 = open). */
    suspend fun groupClosedUntil(chatId: String): Long =
        withContext(Dispatchers.IO) {
            runCatching {
                org.json.JSONObject(core.`groupPermissions`(chatId)).optLong("closed_until", 0L)
            }.getOrDefault(0L)
        }

    /** G-10: close the chat until [untilTs] (unix secs; 0 = reopen). Owner/admin. */
    suspend fun setGroupClosed(chatId: String, untilTs: Long) =
        withContext(Dispatchers.IO) { core.`setGroupClosed`(chatId, untilTs) }

    /** G-9: the group audit log, newest first. */
    suspend fun groupAuditLog(chatId: String): List<AuditEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                val arr = org.json.JSONArray(core.`groupAuditLog`(chatId))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    AuditEntry(
                        o.optString("actor"), o.optString("actor_name"), o.optString("action"),
                        o.optString("target"), o.optString("target_name"), o.optString("ts"),
                    )
                }.asReversed()
            }.getOrDefault(emptyList())
        }

    /** G-9: record + broadcast a group audit event (best-effort). */
    suspend fun logGroupEvent(chatId: String, action: String, targetNpub: String, targetName: String) =
        withContext(Dispatchers.IO) {
            runCatching { core.`logGroupEvent`(chatId, action, targetNpub, targetName) }
        }

    /** G-3 (pull-based role sync): ask the group for role state if we don't
     *  know the owner yet. No-op in core when the owner is already known. */
    suspend fun requestRoleSync(chatId: String) =
        withContext(Dispatchers.IO) {
            runCatching { core.`requestRoleSync`(chatId) }
        }

    /** Member Profile Sync: ask group members to (re)broadcast their profile.
     *  Caller gates this behind a TTL + "some member unknown" check. */
    suspend fun requestGroupProfiles(chatId: String) =
        withContext(Dispatchers.IO) {
            runCatching { core.`requestGroupProfiles`(chatId) }
        }

    /** G-7: create an invite link (expiresAtSecs=0 → permanent, maxUses=0 → unlimited). */
    suspend fun createGroupInvite(chatId: String, expiresAtSecs: Long, maxUses: UInt): String =
        withContext(Dispatchers.IO) { core.`createGroupInvite`(chatId, expiresAtSecs, maxUses) }

    /** G-7: list a group's invites as JSON. */
    suspend fun listGroupInvites(chatId: String): String =
        withContext(Dispatchers.IO) {
            runCatching { core.`listGroupInvites`(chatId) }.getOrDefault("[]")
        }

    /** G-7: revoke an invite. */
    suspend fun revokeGroupInvite(chatId: String, token: String) =
        withContext(Dispatchers.IO) { runCatching { core.`revokeGroupInvite`(chatId, token) } }

    /** G-7: join a group via an invite link (sends a request to the inviter).
     *  Returns null on success, or an error message. */
    suspend fun sendJoinRequest(link: String): String? =
        withContext(Dispatchers.IO) {
            // Our photo, small, for the owner to see who asks (the core sends it
            // only if our privacy shows the photo to everyone).
            val avatar = runCatching { core.`getMyProfile`().avatarPath }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { smallAvatarB64(it) }.orEmpty()
            runCatching { core.`sendJoinRequest`(link, avatar) }
                .fold(onSuccess = { null }, onFailure = { it.message ?: strings.get(R.string.error_generic) })
        }

    /** A photo as a small square JPEG (base64) — what a join request carries. */
    private fun smallAvatarB64(path: String): String? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        com.alphamessenger.data.MediaVault.decodeFile(path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 160) sample *= 2
        val src = com.alphamessenger.data.MediaVault.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = minOf(src.width, src.height)
        val square = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = android.graphics.Bitmap.createScaledBitmap(square, 160, 160, true)
        for (q in intArrayOf(80, 65, 50)) {
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= 16 * 1024) return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
        null
    }.getOrNull()

    /** Join requests that answer our admin invitation (a persona of someone we chose). */
    private val adminAnswers = java.util.Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())

    /** The name and photo a join request brought (photo saved as a file); empty if none. */
    private fun joinRequestProfile(chatId: String, npub: String): Pair<String, String> = runCatching {
        val o = org.json.JSONObject(core.`joinRequestProfile`(chatId, npub))
        if (o.optBoolean("admin", false)) adminAnswers.add(chatId to npub)
        val name = o.optString("name").trim()
        val avatar = o.optString("avatar").takeIf { it.isNotBlank() }?.let { b64 ->
            val dir = java.io.File(context.filesDir, "join_avatars").apply { mkdirs() }
            val f = java.io.File(dir, npub.takeLast(24) + ".jpg")
            f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            f.absolutePath
        }.orEmpty()
        name to avatar
    }.getOrDefault("" to "")

    /** G-7 stage 3: pending join requests awaiting manual approval for [chatId]. */
    fun pendingJoins(chatId: String): List<PendingJoin> =
        GroupModeration(context).pendingJoins(chatId)

    // --- G-11: 1:1 user blocklist ---
    fun blockedUsers(): List<BlockedUser> = UserBlocklist(context).blocked()
    fun isUserBlocked(npub: String): Boolean = UserBlocklist(context).isBlocked(npub)
    /** Epoch seconds when [npub] was blocked, or 0 if not blocked. */
    fun blockedAtFor(npub: String): Long = UserBlocklist(context).blockedAt(npub)

 // --- Per-chat disappearing-messages timer ---
    fun chatTimerSeconds(chatId: String): Int = ChatTimerStore(context).seconds(chatId)
    fun setChatTimer(chatId: String, seconds: Int) {
        ChatTimerStore(context).set(chatId, seconds)
        // Mirror to the Rust KV so the chat-list ChatInfo.ephemeralSeconds (→ the
        // disappearing-messages badge) reflects it, then refresh the list.
        runCatching { core.`setChatEphemeral`(chatId, seconds.toUInt()) }
        notifyChatListDirty()
    }
    fun blockUser(npub: String, name: String = "", avatarPath: String = "") {
        UserBlocklist(context).block(npub, name, avatarPath)
        // The core enforces it too: a blocked person can neither bring us into a chat nor call.
        runCatching { core.`setUserBlocked`(npub, true) }
        _blockListChanged.tryEmit(Unit)
        notifyChatListDirty()
    }
    fun unblockUser(npub: String) {
        UserBlocklist(context).unblock(npub)
        runCatching { core.`setUserBlocked`(npub, false) }
        _blockListChanged.tryEmit(Unit)
        notifyChatListDirty()
    }

    /** G-7: back-fill a pending join request's nick + avatar from a profile that
     *  was resolved elsewhere (e.g. when the owner opened the requester's info
     *  card). Updates every group that has a pending request from [npub] and
     *  notifies the UI so the "Requests" thumbnail fills in. Keeps the token. */
    suspend fun enrichPendingJoinProfile(npub: String, name: String, avatarPath: String) =
        withContext(Dispatchers.IO) {
            if (npub.isBlank() || (name.isBlank() && avatarPath.isBlank())) return@withContext
            val mod = GroupModeration(context)
            runCatching {
                core.`listChats`().filter { it.chatType == "group" }.forEach { c ->
                    val existing = mod.pendingJoins(c.id).firstOrNull { it.npub == npub } ?: return@forEach
                    mod.addPendingJoin(
                        c.id, npub, existing.token,
                        name.ifBlank { existing.name },
                        avatarPath.ifBlank { existing.avatarPath },
                    )
                    _pendingJoinsChanged.tryEmit(c.id)
                }
            }
        }

    /** Fetch a peer's public Nostr profile (kind-0) by npub. Returns
     *  (displayName, pictureUrl); both may be empty if none is published. */
    suspend fun fetchProfileByNpub(npub: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = core.`fetchProfileByNpub`(npub)
                val o = org.json.JSONObject(json)
                o.optString("name") to o.optString("picture")
            }.getOrDefault("" to "")
        }

    /** Resolve a join requester's nick + a LOCAL avatar path: fetch their kind-0
     *  profile, then download the avatar image to internal storage (the UI loads
     *  avatars from files, not URLs). Best-effort — returns whatever it can. */
    suspend fun resolveProfileWithAvatar(npub: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val (name, pictureUrl) = fetchProfileByNpub(npub)
            // The picture URL is chosen by the requester: fetching it directly
            // would hand them our IP and the moment we looked. Only through Tor,
            // https only, at most 2 MB read; otherwise initials are shown.
            val viaTor = com.alphamessenger.data.NetworkPrivacyPrefs(context).torEnabled
            val avatarPath = if (pictureUrl.isBlank() || !viaTor || !pictureUrl.startsWith("https://")) "" else runCatching {
                val safe = npub.filter { it.isLetterOrDigit() }.takeLast(24)
                val target = java.io.File(context.filesDir, "join_$safe.jpg")
                val proxy = com.alphamessenger.data.EmbeddedTor.proxy()
                val conn = (java.net.URL(pictureUrl).openConnection(proxy) as java.net.HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 15000; instanceFollowRedirects = false
                }
                val limit = 2 * 1024 * 1024
                val ok = conn.inputStream.use { input ->
                    java.io.FileOutputStream(target).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > limit) return@use false
                            out.write(buf, 0, n)
                        }
                        total > 0
                    }
                }
                if (ok) target.absolutePath else { target.delete(); "" }
            }.getOrDefault("")
            name to avatarPath
        }

    /** Accept a pending join request: add the member via the stored invite token,
     *  then drop it from the pending list. Returns null on success, else an error. */
    suspend fun acceptJoinRequest(chatId: String, npub: String): String? =
        withContext(Dispatchers.IO) {
            val mod = GroupModeration(context)
            val pending = mod.pendingJoins(chatId).firstOrNull { it.npub == npub }
                ?: return@withContext strings.get(R.string.join_request_not_found)
            val result = runCatching { core.`approveJoinRequest`(chatId, npub, pending.token) }
                .fold(onSuccess = { null }, onFailure = { it.message ?: strings.get(R.string.error_generic) })
            if (result == null) {
                mod.removePendingJoin(chatId, npub)
                _pendingJoinsChanged.tryEmit(chatId)
                notifyChatListDirty()
            }
            result
        }

    /** Reject a pending join request: just drop it (the requester is not added). */
    suspend fun rejectJoinRequest(chatId: String, npub: String) =
        withContext(Dispatchers.IO) {
            GroupModeration(context).removePendingJoin(chatId, npub)
            _pendingJoinsChanged.tryEmit(chatId)
        }

    /** After a backup restore: ask every group for fresh roles + metadata +
     *  member profiles (avatars). Owners/members reply over MLS so the restored
     *  device catches up to the CURRENT state (an old backup may be stale). */
    suspend fun pullFreshGroupState() = withContext(Dispatchers.IO) {
        runCatching {
            core.`listChats`().filter { it.chatType == "group" }.forEach { c ->
                runCatching { core.`requestRoleSync`(c.id) }
                runCatching { core.`requestGroupProfiles`(c.id) }
            }
        }
    }

    /** Persistent "removed/banned from group" flag (survives restart). */
    suspend fun isRemovedFromGroup(chatId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { core.`isRemovedFromGroup`(chatId) }.getOrDefault(false)
        }

    /** True when the group can no longer be posted to (MLS state gone / inactive
     *  — left, deleted, removed or disbanded). Drives the "Group unavailable" strip. */
    suspend fun isGroupUnavailable(chatId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { core.`isGroupUnavailable`(chatId) }.getOrDefault(false)
        }

    /** Find an EXISTING 1:1 chat with the given npub (no creation). Returns the
     *  chat id, or null if there's no direct chat with that peer yet. Used to
     *  open a group member's full info page (with shared media) when a DM
     *  already exists. */
    suspend fun findDirectChatByNpub(npub: String): String? =
        withContext(Dispatchers.IO) {
            val target = npub.trim()
            if (target.isBlank()) return@withContext null
            runCatching {
                core.`listChats`().firstOrNull { c ->
                    c.chatType != "group" &&
                        runCatching { core.`getPeerNpubForChat`(c.id) }.getOrNull()
                            ?.trim()?.equals(target, ignoreCase = true) == true
                }?.id
            }.getOrNull()
        }

    /** Live member count from MLS state — 0 if the group isn't joined yet. */
    suspend fun chatMemberCount(chatId: String): Int =
        withContext(Dispatchers.IO) {
            core.`chatMemberCount`(chatId).toInt()
        }

    /** Saved Messages chat id (notes-to-self, local-only). */
    val savedMessagesChatId: String = "saved_messages_local"

    suspend fun ensureSavedMessagesChat(): ChatInfo = withContext(Dispatchers.IO) {
        core.`ensureSavedMessagesChat`()
    }

    /** The local chat of one assistant profile (created on first use). */
    suspend fun ensureAssistantChat(profileId: String, name: String): ChatInfo = withContext(Dispatchers.IO) {
        core.`ensureAssistantChat`(profileId, name)
    }

    /** A picture or voice the assistant made, stored in its chat from a local file. */
    suspend fun insertAssistantMedia(chatId: String, path: String, mediaType: String, caption: String?, metaJson: String?): String =
        withContext(Dispatchers.IO) { core.`insertAssistantMedia`(chatId, path, mediaType, caption, metaJson) }

    /** A message written on this phone (an assistant's reply) goes the way a received one does: notifications included. */
    suspend fun announceLocalMessage(chatId: String) {
        runCatching { getMessages(chatId, 1u, 0u).lastOrNull() }.getOrNull()?.let { _messageReceived.tryEmit(it) }
    }

    /** A reply of the assistant, stored in its chat; returns the message id. */
    suspend fun insertAssistantMessage(chatId: String, body: String, id: String? = null, replyTo: String? = null): String = withContext(Dispatchers.IO) {
        core.`insertAssistantMessage`(chatId, body, id, replyTo)
    }

    /** The bot's reaction to a message of its chat (its one there, replaced by a new one). */
    suspend fun insertAssistantReaction(chatId: String, targetId: String, emoji: String) = withContext(Dispatchers.IO) {
        core.`insertAssistantReaction`(chatId, targetId, emoji)
    }

    /** Puts the bot's current conversation aside; false when it was empty. */
    suspend fun assistantNewConversation(profileId: String): Boolean = withContext(Dispatchers.IO) {
        core.`assistantNewConversation`(profileId).also { notifyChatListDirty() }
    }

    suspend fun assistantOpenConversation(profileId: String, chatId: String) = withContext(Dispatchers.IO) {
        core.`assistantOpenConversation`(profileId, chatId)
        notifyChatListDirty()
    }

    suspend fun assistantConversations(profileId: String): List<uniffi.alphamessenger.AssistantConversation> = withContext(Dispatchers.IO) {
        core.`assistantConversations`(profileId)
    }

    // --- Unsent drafts (ENCRYPTED in the core DB under draft:<chatId>) ---
    // A draft is content the user is typing but hasn't sent — never goes to MLS
    // or a relay, but also must not sit in plaintext on disk. It lives in the
    // SQLCipher KV store (same master key as everything else, wiped by panic).
    private val draftScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val draftJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    suspend fun getDraft(chatId: String): String = withContext(Dispatchers.IO) {
        if (chatId.isBlank()) "" else runCatching { core.`getDraft`(chatId) }.getOrDefault("")
    }

    /** Persist a draft — debounced, off the main thread, and on a scope that
     *  OUTLIVES the ViewModel so it's still saved if the user leaves right after
     *  typing. Coalesces rapid keystrokes into ~one encrypted write per pause. */
    fun setDraftAsync(chatId: String, text: String) {
        if (chatId.isBlank()) return
        draftJobs.remove(chatId)?.cancel()
        draftJobs[chatId] = draftScope.launch {
            kotlinx.coroutines.delay(500)
            runCatching { core.`setDraft`(chatId, text) }
        }
    }

    suspend fun clearDraft(chatId: String) = withContext(Dispatchers.IO) {
        if (chatId.isNotBlank()) {
            draftJobs.remove(chatId)?.cancel()
            runCatching { core.`clearDraft`(chatId) }
        }
        Unit
    }

    /** chatId → draft text, for the chat-list "Draft" preview. */
    suspend fun allDrafts(): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val obj = org.json.JSONObject(core.`listDrafts`())
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
    }

    // --- Profile (local only) ---

    suspend fun getMyProfile(): uniffi.alphamessenger.ProfileInfo =
        withContext(Dispatchers.IO) { core.`getMyProfile`() }

    /** Save our "About" text in the app's own scope: leaving the screen that
     *  edits it must not cut the save short. */
    fun saveMyBio(text: String) {
        scope.launch { runCatching { setMyProfile(bio = text) } }
    }

    suspend fun setMyProfile(
        displayName: String? = null,
        bio: String? = null,
        avatarPath: String? = null,
    ): uniffi.alphamessenger.ProfileInfo = withContext(Dispatchers.IO) {
        val info = core.`setMyProfile`(displayName, bio, avatarPath)
        _profileChanged.tryEmit(Unit)
        info
    }

    // --- Peer profiles ---

    suspend fun getPeerProfile(senderId: String): uniffi.alphamessenger.PeerProfile =
        withContext(Dispatchers.IO) { core.`getPeerProfile`(senderId) }

    suspend fun listPeerProfiles(): List<uniffi.alphamessenger.PeerProfile> =
        withContext(Dispatchers.IO) { core.`listPeerProfiles`() }

    suspend fun getPeerNpubForChat(chatId: String): String =
        withContext(Dispatchers.IO) { core.`getPeerNpubForChat`(chatId) }

    /** Safety number state as JSON `{number, verified, changed, proven}`. */
    /** Re-sign our old leaf in a direct chat when it is our turn; true = done now. */
    suspend fun refreshLeafBinding(chatId: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { core.`refreshLeafBinding`(chatId) }.getOrDefault(false) }

    suspend fun verificationInfo(chatId: String): String =
        withContext(Dispatchers.IO) { core.`verificationInfo`(chatId) }

    suspend fun setContactVerified(chatId: String, verified: Boolean) =
        withContext(Dispatchers.IO) { core.`setContactVerified`(chatId, verified) }

    // --- Secret chats ---

    data class SecretOffer(val inviteId: String, val chatId: String)

    /** Start a secret chat with the peer of the 1:1 chat [chatId]; returns its id. */
    suspend fun secretChatStart(chatId: String): String =
        withContext(Dispatchers.IO) { core.`secretChatStart`(chatId) }.also { EmbeddedTor.needForSecretChats(context, true) }

    suspend fun secretChatOffers(): List<SecretOffer> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = org.json.JSONArray(core.`secretChatOffers`())
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { SecretOffer(it.optString("invite_id"), it.optString("chat_id")) }
        }.getOrDefault(emptyList())
    }

    suspend fun secretChatAccept(inviteId: String) = withContext(Dispatchers.IO) {
        core.`secretChatAccept`(inviteId)
        EmbeddedTor.needForSecretChats(context, true)
    }

    suspend fun secretChatDecline(inviteId: String) = withContext(Dispatchers.IO) { core.`secretChatDecline`(inviteId) }

    /** The 1:1 chat a secret chat came from; "" when [chatId] is not secret. */
    suspend fun secretChatOrigin(chatId: String): String =
        withContext(Dispatchers.IO) { runCatching { core.`secretChatOrigin`(chatId) }.getOrDefault("") }

    suspend fun secretChatEnd(chatId: String) = withContext(Dispatchers.IO) {
        core.`secretChatEnd`(chatId)
        hasSecretChats()?.let { EmbeddedTor.needForSecretChats(context, it) }
    }

    /** The secret chat's own Tor connection is up. */
    suspend fun secretChatReady(chatId: String): Boolean = withContext(Dispatchers.IO) {
        EmbeddedTor.needForSecretChats(context, true)
        runCatching { core.`secretChatReady`(chatId) }.getOrDefault(false)
    }

    /** New Tor circuits for this secret chat alone. */
    suspend fun secretChatNewCircuit(chatId: String): Boolean = withContext(Dispatchers.IO) {
        EmbeddedTor.needForSecretChats(context, true)
        runCatching { core.`secretChatNewCircuit`(chatId) }.isSuccess
    }

    /** Tor check through the chat's own route: JSON `{tor, ip, country, country_code}`, null on failure. */
    suspend fun secretChatCheck(chatId: String): String? =
        withContext(Dispatchers.IO) { runCatching { core.`secretChatCheck`(chatId) }.getOrNull() }

    /** (state, inviteId) of a secret chat: invited / accepted / waiting / declined / active / none. */
    suspend fun secretChatStatus(chatId: String): Pair<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val o = org.json.JSONObject(core.`secretChatStatus`(chatId))
            o.optString("state", "none") to o.optString("invite_id")
        }.getOrDefault("none" to "")
    }

    /** Whether any secret chat exists; null when the core could not say. */
    suspend fun hasSecretChats(): Boolean? = withContext(Dispatchers.IO) {
        runCatching { org.json.JSONArray(core.`secretChatIds`()).length() > 0 }.getOrNull()
    }

    suspend fun secretChatIds(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = org.json.JSONArray(core.`secretChatIds`())
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Raw Nostr pubkey hex of a direct chat's peer — matches presence keys. */
    suspend fun peerHexForChat(chatId: String): String =
        withContext(Dispatchers.IO) { runCatching { core.`peerHexForChat`(chatId) }.getOrDefault("") }

    /** Set (or clear with "") my cover/banner path + re-broadcast to peers. */
    suspend fun setMyCover(coverPath: String) = withContext(Dispatchers.IO) {
        runCatching { core.`setMyCover`(coverPath) }
        _profileChanged.tryEmit(Unit)
    }

    // --- The Alpha feed ---

    /** The feed's posts as `[{"d","at","c"}]`, newest first, or null when offline. */
    suspend fun alphaFeedFetch(limit: Int = 100): String? =
        withContext(Dispatchers.IO) { runCatching { core.`alphaFeedFetch`(limit.toUInt()) }.getOrNull() }

    /** Is [key] the Alpha feed's publishing key? */
    suspend fun alphaFeedKeyOk(key: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { core.`alphaFeedKeyOk`(key) }.getOrDefault(false) }

    /** Publish (or replace) feed post [d]. True when it went out. */
    suspend fun alphaFeedPublish(key: String, d: String, content: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { core.`alphaFeedPublish`(key, d, content) }.isSuccess }

    /** Remove feed post [d] published at [at] (epoch s): its tombstone keeps that time. */
    suspend fun alphaFeedRemove(key: String, d: String, at: Long): Boolean =
        withContext(Dispatchers.IO) { runCatching { core.`alphaFeedRemove`(key, d, at.coerceAtLeast(0).toULong()) }.isSuccess }

    /** The sticker catalog's pages as `[{"d","at","c"}]`, or null when offline. */
    suspend fun alphaCatalogFetch(): String? =
        withContext(Dispatchers.IO) { runCatching { core.`alphaCatalogFetch`() }.getOrNull() }

    /** The project's network health map (JSON), or null when none could be read. */
    suspend fun alphaHealthFetch(): String? =
        withContext(Dispatchers.IO) { runCatching { core.`alphaHealthFetch`() }.getOrNull() }

    /** Media servers the health map finds bad: uploads try them last. */
    fun setMediaHealthDown(urls: List<String>) { runCatching { core.`setMediaHealthDown`(urls) } }

    /** Every stored blob of [ref] as `[{"sha","urls"}]` (resolves a large file's manifest). */
    suspend fun mediaRefBlobs(ref: String): String? =
        withContext(Dispatchers.IO) { runCatching { core.`mediaRefBlobs`(ref) }.getOrNull() }

    /** Publish (or replace) catalog page [d]. True when it went out. */
    suspend fun alphaCatalogPublish(key: String, d: String, content: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { core.`alphaCatalogPublish`(key, d, content) }.isSuccess }

    // --- Sticker packs that update themselves ---

    /** New pack keys: "sign:pub:read" (hex), or null. */
    suspend fun stickerPackKeys(): String? =
        withContext(Dispatchers.IO) { runCatching { core.`stickerPackKeys`() }.getOrNull() }

    /** Hand a pack's signing key over to the core's database. True when it is stored. */
    suspend fun stickerPackAdoptKey(sign: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { core.`stickerPackAdoptKey`(sign) }.isSuccess }

    /** Publish the pack's latest version (its list's ref). True when it went out. */
    suspend fun stickerPackPublish(pub: String, read: String, listRef: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { core.`stickerPackPublish`(pub, read, listRef) }.isSuccess }

    /** The pack's latest version (its list's ref), or "" when none is found. */
    suspend fun stickerPackLatest(pub: String, read: String): String =
        withContext(Dispatchers.IO) { runCatching { core.`stickerPackLatest`(pub, read) }.getOrDefault("") }

    /** Set (or clear with "") my video avatar + re-broadcast to peers. */
    suspend fun setMyAvatarVideo(path: String) = withContext(Dispatchers.IO) {
        runCatching { core.`setMyAvatarVideo`(path) }
        _profileChanged.tryEmit(Unit)
    }

    /** My video avatar's local path ("" = none). */
    suspend fun myAvatarVideo(): String =
        withContext(Dispatchers.IO) { runCatching { core.`myAvatarVideo`() }.getOrDefault("") }

    /** A peer's video avatar local path ("" = none), keyed by MLS sender_id. */
    suspend fun peerAvatarVideo(senderId: String): String =
        withContext(Dispatchers.IO) { runCatching { core.`peerAvatarVideo`(senderId) }.getOrDefault("") }

    /** A peer's cover/banner local path ("" = none), keyed by MLS sender_id. */
    suspend fun getPeerCoverPath(senderId: String): String =
        withContext(Dispatchers.IO) { runCatching { core.`getPeerCoverPath`(senderId) }.getOrDefault("") }

    /** Fallback profile pull: ask the chat's peer(s) to (re)send their
     *  ProfileUpdate. Reply is deduped by hash, so no redundant reload. */
    suspend fun requestPeerProfile(chatId: String) = withContext(Dispatchers.IO) {
        runCatching { core.`requestGroupProfiles`(chatId) }
    }

    /** Resolve a group member's real nostr npub from their MLS sender_id.
     *  Empty if we haven't decrypted a message from them yet. */
    suspend fun getMemberNpub(senderId: String): String =
        withContext(Dispatchers.IO) { core.`getMemberNpub`(senderId) }

    /** Group member's Nostr hex — the key the engine tracks presence under. */
    suspend fun memberHex(senderId: String): String =
        withContext(Dispatchers.IO) { runCatching { core.`memberHex`(senderId) }.getOrDefault("") }

    /**
     * Find the first inbound message's `senderId` for a direct chat — i.e.
     * the peer's MLS leaf-signature hex. Returns empty string if no inbound
     * message has arrived yet (chat is brand-new or peer never replied).
     *
     * Used by every UI surface that needs to bind a chat row to a peer
     * profile (top bar, chat list row, avatar preview). Centralized here
     * so the "skip self / skip own npub" predicate isn't re-derived in
     * each ViewModel.
     */
    suspend fun firstInboundSenderId(chatId: String, ownNpub: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                core.`getMessages`(chatId, 30u, 0u)
                    .asSequence()
                    .map { it.senderId }
                    .firstOrNull { it.isNotBlank() && it != "self" && it != ownNpub }
            }.getOrNull().orEmpty()
        }

    /**
     * Canonical 1:1 peer sig-key resolver. Prefers the MLS member list (the
     * non-self member) — available from group state immediately, independent of
     * inbound messages — and only falls back to [firstInboundSenderId]. This is
     * why the peer avatar/nick used to vanish (on the chat list, the dialog and
     * the info screen) whenever firstInboundSenderId went blank (no inbound
     * message yet, or the history was swept). Use this everywhere instead of
     * firstInboundSenderId for direct chats.
     */
    suspend fun resolvePeerSenderId(chatId: String, ownNpub: String): String =
        withContext(Dispatchers.IO) {
            val fromMembers = runCatching {
                val arr = org.json.JSONArray(core.`listGroupMembers`(chatId))
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { !it.optBoolean("is_self", false) && it.optString("sender_id").isNotBlank() }
                    ?.optString("sender_id").orEmpty()
            }.getOrNull().orEmpty()
            fromMembers.ifBlank { firstInboundSenderId(chatId, ownNpub) }
        }

    // --- Relay management (Settings → Relays) ---

    /**
     * Singleton-scoped cache of relay statuses so that the Relays screen
     * doesn't flash yellow→green every time the user re-enters it. The
     * cache survives ViewModel re-creation; we only re-ping on explicit
     * user pull-to-refresh.
     */
    private val _relayStates = MutableStateFlow<List<uniffi.alphamessenger.RelayInfo>>(emptyList())
    val relayStates: StateFlow<List<uniffi.alphamessenger.RelayInfo>> = _relayStates.asStateFlow()

    private val _relayPinging = MutableStateFlow(false)
    val relayPinging: StateFlow<Boolean> = _relayPinging.asStateFlow()

    private val _relayLastPingedAt = MutableStateFlow(0L)
    val relayLastPingedAt: StateFlow<Long> = _relayLastPingedAt.asStateFlow()

    /** Cheap read of metadata (URLs + flags + connected) — no ping. */
    suspend fun refreshRelayStatuses() = withContext(Dispatchers.IO) {
        runCatching {
            val list = core.`listRelayStatuses`()
            // Preserve previously measured ping values; FFI returns ping_ms=0
            // for the list_relay_statuses path.
            val prev = _relayStates.value.associateBy { it.url }
            _relayStates.value = list.map { fresh ->
                val old = prev[fresh.url]
                if (old != null && fresh.pingMs == 0u) fresh.copy(pingMs = old.pingMs) else fresh
            }
        }.onFailure {
            android.util.Log.w("AlphaRepo", "listRelayStatuses failed", it)
        }
        Unit
    }

    /** Full TCP ping in parallel + status. Honours `_relayPinging` guard. */
    suspend fun pingAllRelaysCached(timeoutMs: Int = 3000) = withContext(Dispatchers.IO) {
        if (_relayPinging.value) return@withContext
        _relayPinging.value = true
        try {
            val list = core.`pingAllRelays`(timeoutMs.toUInt())
            _relayStates.value = list
            _relayLastPingedAt.value = System.currentTimeMillis()
        } catch (e: Exception) {
            android.util.Log.w("AlphaRepo", "pingAllRelays failed", e)
        } finally {
            _relayPinging.value = false
        }
    }

    suspend fun addRelay(url: String, read: Boolean = true, write: Boolean = true) =
        withContext(Dispatchers.IO) {
            core.`addRelay`(url, read, write)
            refreshRelayStatuses()
            announceRelaysSoon()
        }

    suspend fun removeRelay(url: String) =
        withContext(Dispatchers.IO) {
            core.`removeRelay`(url)
            refreshRelayStatuses()
            announceRelaysSoon()
        }

    private var relayAnnounce: kotlinx.coroutines.Job? = null

    /** Contacts write where we listen: once the list stops changing, they are told. */
    private fun announceRelaysSoon() {
        relayAnnounce?.cancel()
        relayAnnounce = scope.launch {
            kotlinx.coroutines.delay(15_000)
            runCatching { core.`broadcastMyProfileNow`() }
        }
    }

    /** Our npub with a few of our relays, for QR codes and shared links. */
    suspend fun contactCode(): String = withContext(Dispatchers.IO) {
        runCatching { core.`contactCode`() }.getOrElse { core.`getIdentityInfo`().npub }
    }

    suspend fun setReadReceiptsEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        core.`setReadReceiptsEnabled`(enabled)
    }

    /** Ghost mode: no online / typing / read ticks from us; we still see others'. */
    suspend fun setGhostMode(enabled: Boolean) = withContext(Dispatchers.IO) {
        core.`setGhostMode`(enabled)
        com.alphamessenger.ui.components.GhostModeState.on.value = enabled
    }

    suspend fun ghostMode(): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`ghostMode`() }.getOrDefault(false)
            .also { com.alphamessenger.ui.components.GhostModeState.on.value = it }
    }

    suspend fun readReceiptsEnabled(): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`readReceiptsEnabled`() }.getOrDefault(true)
    }

    /** Route all traffic through Orbot's SOCKS5 proxy. Applies after an app restart. */
    suspend fun setTorEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        core.`setTorEnabled`(enabled)
    }

    /** Send a poll (JSON: see core `send_poll`); its message id. */
    suspend fun sendPoll(chatId: String, pollJson: String, thread: String? = null): String = withContext(Dispatchers.IO) {
        val id = core.`sendPoll`(chatId, pollJson, thread)
        scheduleReliableDelivery()
        id
    }

    /** Vote in a poll (empty = withdraw); throws when the vote is not accepted. */
    suspend fun pollVote(chatId: String, pollId: String, options: List<Int>) = withContext(Dispatchers.IO) {
        core.`pollVote`(chatId, pollId, options.map { it.toUInt() })
        scheduleReliableDelivery()
    }

    /** Close a poll (its author or an admin). */
    suspend fun pollClose(chatId: String, pollId: String) = withContext(Dispatchers.IO) {
        core.`pollClose`(chatId, pollId)
        scheduleReliableDelivery()
    }

    /** A poll's results (JSON, "" = unknown). */
    fun pollResults(chatId: String, pollId: String): String = runCatching { core.`pollResults`(chatId, pollId) }.getOrDefault("")

    /** The look a group's / channel's admins set (JSON, "" = none). */
    /** Join / leave lines shown in this group (default on). */
    suspend fun groupNotices(chatId: String): Boolean = withContext(Dispatchers.IO) { runCatching { core.`groupNotices`(chatId) }.getOrDefault(true) }

    suspend fun setGroupNotices(chatId: String, on: Boolean) = withContext(Dispatchers.IO) {
        core.`setGroupNotices`(chatId, on)
        _chatMetadataUpdated.tryEmit(chatId)
    }

    suspend fun chatLook(chatId: String): String = withContext(Dispatchers.IO) { runCatching { core.`chatLook`(chatId) }.getOrDefault("") }

    /** Set the group's / channel's look for every member (admins only; "" clears). */
    suspend fun setChatLook(chatId: String, look: String) = withContext(Dispatchers.IO) { core.`setChatLook`(chatId, look) }

    /** Public Nostr emoji sets (JSON) by a set's link or its author; throws on bad input. */
    suspend fun nostrEmojiSets(input: String): String = withContext(Dispatchers.IO) { core.`nostrEmojiSets`(input) }

    suspend fun isTorEnabled(): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`isTorEnabled`() }.getOrDefault(false)
    }

    suspend fun setRelayEnabled(url: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            core.`setRelayEnabled`(url, enabled)
            refreshRelayStatuses()
            announceRelaysSoon()
        }

    /** A whole set of relay changes with one reconnect; returns how many steps failed. */
    suspend fun applyRelayChanges(add: List<String>, enable: List<String>, disable: List<String>, remove: List<String>): Int =
        withContext(Dispatchers.IO) {
            val failed = core.`applyRelayChanges`(add, enable, disable, remove).toInt()
            refreshRelayStatuses()
            announceRelaysSoon()
            failed
        }

    suspend fun setRelayFlags(url: String, read: Boolean, write: Boolean) =
        withContext(Dispatchers.IO) {
            core.`setRelayFlags`(url, read, write)
            refreshRelayStatuses()
        }

    /** "Don't keep on servers": media of this chat is deleted from storage after delivery. */
    suspend fun setChatNoStore(chatId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        core.`setChatNoStore`(chatId, enabled)
    }

    suspend fun chatNoStore(chatId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`chatNoStore`(chatId) }.getOrDefault(false)
    }

    /** Default for all chats and groups; resets every per-chat choice. */
    suspend fun setNoStoreDefault(enabled: Boolean) = withContext(Dispatchers.IO) {
        core.`setNoStoreDefault`(enabled)
    }

    suspend fun noStoreDefault(): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`noStoreDefault`() }.getOrDefault(false)
    }

    // --- Chat-list UX ---

    suspend fun pinChat(chatId: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        core.`pinChat`(chatId, pinned); notifyChatListDirty()
    }

    suspend fun muteChat(chatId: String, seconds: Long) = withContext(Dispatchers.IO) {
        core.`muteChat`(chatId, seconds); notifyChatListDirty()
    }

    suspend fun archiveChat(chatId: String, archived: Boolean) = withContext(Dispatchers.IO) {
        core.`archiveChat`(chatId, archived); notifyChatListDirty()
    }

    /**
     * When the user last wiped this chat's history (deleting the chat or
     * clearing it), epoch ms; 0 = never. The local call log stays complete in
     * the Calls tab, but calls from before this moment are no longer part of
     * the chat — a chat that comes back when the person writes again starts
     * clean.
     */
    fun historyClearedAt(chatId: String): Long =
        context.getSharedPreferences("chat_history_cleared", Context.MODE_PRIVATE).getLong(chatId, 0L)

    fun markHistoryCleared(chatId: String) {
        context.getSharedPreferences("chat_history_cleared", Context.MODE_PRIVATE).edit()
            .putLong(chatId, System.currentTimeMillis()).apply()
    }

    /** [forBoth]: a 1:1 goes at the other side too, history and all. */
    suspend fun deleteChat(chatId: String, forBoth: Boolean = false) = withContext(Dispatchers.IO) {
        markHistoryCleared(chatId)
        if (forBoth) core.`deleteChatForBoth`(chatId) else core.`deleteChat`(chatId)
        notifyChatListDirty()
    }

    suspend fun setChatEphemeral(chatId: String, seconds: Int) = withContext(Dispatchers.IO) {
        runCatching { core.`setChatEphemeral`(chatId, seconds.toUInt()) }; notifyChatListDirty()
    }

    suspend fun getChatEphemeral(chatId: String): Int = withContext(Dispatchers.IO) {
        runCatching { core.`getChatEphemeral`(chatId).toInt() }.getOrDefault(0)
    }

    // --- Presence: thin wrappers over the FFI. Best-effort; a
    //    presence hiccup never affects chat. Heartbeat loop + UI land later.
    fun presenceSetMode(mode: String) = runCatching { core.`presenceSetMode`(mode) }
    /** A changed status text is sent out in the profile (network): always off the caller's thread. */
    fun presenceSetMyStatus(busy: Boolean, custom: String, color: Long) {
        scope.launch { runCatching { core.`presenceSetMyStatus`(busy, custom, color.toUInt()) } }
    }

    /** May we call this member? (voice, video) — from their profile; their phone decides anyway. */
    suspend fun peerCallRights(senderId: String): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val o = org.json.JSONObject(core.`peerCallRights`(senderId))
            o.optBoolean("voice", true) to o.optBoolean("video", true)
        }.getOrDefault(true to true)
    }
    fun presenceArm() = runCatching { core.`presenceArm`() }
    suspend fun presenceStatus(userId: String): String = withContext(Dispatchers.IO) {
        runCatching { core.`presenceStatus`(userId) }.getOrDefault("offline")
    }
    suspend fun presenceLastSeen(userId: String): Long = withContext(Dispatchers.IO) {
        runCatching { core.`presenceLastSeen`(userId) }.getOrDefault(0L)
    }
    suspend fun presenceCustomStatus(userId: String): String = withContext(Dispatchers.IO) {
        runCatching { core.`presenceCustomStatus`(userId) }.getOrDefault("")
    }
    suspend fun presenceColor(userId: String): Long = withContext(Dispatchers.IO) {
        runCatching { core.`presenceColor`(userId).toLong() }.getOrDefault(0L)
    }
    suspend fun presenceCapabilities(userId: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { core.`presenceCapabilities`(userId) }.getOrDefault(emptyList())
    }

    /** Run ONE presence tick (publish our heartbeat if due + pull peers) and
     *  emit the changed (userHex → status) map. Best-effort; never throws. */
    suspend fun presenceTick() = withContext(Dispatchers.IO) {
        val json = runCatching { core.`presenceTick`() }.getOrDefault("[]")
        val changes = runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).associate {
                val o = arr.getJSONObject(it)
                o.getString("user") to o.getString("status")
            }
        }.getOrDefault(emptyMap())
        if (changes.isNotEmpty()) _presenceChanged.tryEmit(changes)
    }

    suspend fun markChatRead(chatId: String) = withContext(Dispatchers.IO) {
        core.`markChatRead`(chatId); notifyChatListDirty()
    }

    /** Read up to [messageId], the newest message on screen. */
    suspend fun markChatReadThrough(chatId: String, messageId: String) = withContext(Dispatchers.IO) {
        core.`markChatReadThrough`(chatId, messageId); notifyChatListDirty()
    }

    suspend fun markChatUnread(chatId: String) = withContext(Dispatchers.IO) {
        core.`markChatUnread`(chatId); notifyChatListDirty()
    }

    /**
     * The user's chats. A chat still waiting in Requests is left out unless
     * [includeRequests]: it shows in no list, picker or search — only on the
     * Requests screen and its own screen.
     */
    /** The last known row of every chat (who I am there, how many members, channel
     *  settings), so a chat opens with its real header and composer on the first frame. */
    private val chatInfos = java.util.concurrent.ConcurrentHashMap<String, ChatInfo>()
    fun chatInfoNow(chatId: String): ChatInfo? = chatInfos[chatId]

    suspend fun listChats(includeRequests: Boolean = false): List<ChatInfo> = withContext(Dispatchers.IO) {
        val r = core.`listChats`()
        r.forEach { chatInfos[it.id] = it }
        if (includeRequests) r else {
            val pending = chatRequests().mapTo(HashSet()) { it.chatId }
            if (pending.isEmpty()) r else r.filter { it.id !in pending }
        }
    }

    suspend fun leaveChat(chatId: String) = withContext(Dispatchers.IO) {
        core.`leaveChat`(chatId)
    }

    suspend fun addMember(chatId: String, peerKeyPackageBase64: String): String =
        withContext(Dispatchers.IO) {
            core.`addMember`(chatId, peerKeyPackageBase64)
        }

    suspend fun removeMember(chatId: String, leafIndex: UInt): String =
        withContext(Dispatchers.IO) {
            core.`removeMember`(chatId, leafIndex)
        }

    // --- Messaging ---

    /**
     * NEUTRALIZED (was: schedule an expedited WorkManager flush on every send).
     * It fired an IMMEDIATE flushPendingSends/Media right after each send, which
     * RACED the still-in-flight send and re-published it → extra relay traffic
     * that amplified the load storms (and, with seq-dedup briefly off, surfaced
     * as duplicate media). Offline delivery is already covered by the always-on
     * service's 12s outbox loop, the ConnectivityManager onAvailable callback,
     * and the 15-min PeriodicSyncWorker — none of which race an in-flight send.
     * Kept as a no-op so the call sites stay readable; re-introduce ONLY with a
     * delay/“still-pending” guard if Doze-window offline latency proves to matter.
     */
    private fun scheduleReliableDelivery() {
        // intentionally no-op — see doc above
    }

    /** [thread]: the channel post this text comments on (null = the chat itself). */
    suspend fun sendText(chatId: String, text: String, thread: String? = null): String =
        withContext(Dispatchers.IO) {
            val id = core.`sendText`(chatId, text, thread)
            scheduleReliableDelivery()
            id
        }

    suspend fun sendLocation(chatId: String, lat: Double, lng: Double, name: String, address: String, thread: String? = null): String =
        withContext(Dispatchers.IO) {
            val id = core.`sendLocation`(chatId, lat, lng, name, address, thread)
            scheduleReliableDelivery()
            id
        }

    suspend fun sendLinkPreview(
        chatId: String, url: String, title: String, description: String,
        image: String, site: String, caption: String?, thread: String? = null,
        imageRef: String? = null, video: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val id = core.`sendLinkPreview`(chatId, url, title, description, image, site, caption, thread, imageRef?.takeIf { it.isNotBlank() }, video)
        scheduleReliableDelivery()
        id
    }

    suspend fun sendReply(chatId: String, replyToId: String, text: String, quotedText: String, thread: String? = null): String =
        withContext(Dispatchers.IO) {
            val id = core.`sendReply`(chatId, text, replyToId, quotedText, thread)
            scheduleReliableDelivery()
            id
        }

    suspend fun sendEdit(chatId: String, messageId: String, newText: String): String =
        withContext(Dispatchers.IO) {
            val id = core.`sendEdit`(chatId, messageId, newText)
            // The original row's content was rewritten → refresh the chat-list
            // preview so it shows the edited text immediately.
            notifyChatListDirty()
            id
        }

    suspend fun sendReaction(chatId: String, messageId: String, emoji: String, remove: Boolean = false): String =
        withContext(Dispatchers.IO) {
            core.`sendReaction`(chatId, messageId, emoji, remove)
        }

    suspend fun sendDelete(chatId: String, messageId: String): String =
        withContext(Dispatchers.IO) {
            core.`sendDelete`(chatId, messageId)
        }

    /** Local-only delete ("for me") — bubble disappears, peers unaffected. */
    suspend fun deleteMessageLocal(messageId: String) =
        withContext(Dispatchers.IO) { core.`deleteMessageLocal`(messageId) }

    suspend fun sendEphemeralText(
        chatId: String,
        text: String,
        expiryMode: String,
        ttlSecs: UInt
    ): String = withContext(Dispatchers.IO) {
        val id = core.`sendEphemeralText`(chatId, text, expiryMode, ttlSecs)
        scheduleReliableDelivery()
        id
    }

    // --- Whispers: read under the finger, then burned on both sides ---

    /** The whispers of a chat by message id (never their secrets). */
    /** Ids of this chat's whisper rows. */
    suspend fun whisperIds(chatId: String): Set<String> = whispers(chatId).keys

    suspend fun whispers(chatId: String): Map<String, WhisperMeta> = withContext(Dispatchers.IO) {
        runCatching {
            val a = org.json.JSONArray(core.`whispersJson`(chatId))
            (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
                o.optString("message_id") to WhisperMeta(
                    messageId = o.optString("message_id"),
                    outgoing = o.optBoolean("outgoing"),
                    kind = o.optString("kind", "text"),
                    secs = o.optInt("secs", 15),
                    burned = o.optBoolean("burned"),
                    usedSecs = if (o.isNull("used_secs")) null else o.optInt("used_secs"),
                    shot = o.optBoolean("shot"),
                    expired = o.optBoolean("expired"),
                    glance = o.optBoolean("glance"),
                )
            }
        }.getOrDefault(emptyMap())
    }

    /** The secret of an incoming whisper that has not burned yet. */
    suspend fun whisperSecret(messageId: String): WhisperSecret? = withContext(Dispatchers.IO) {
        runCatching {
            val o = org.json.JSONObject(core.`whisperSecret`(messageId) ?: return@runCatching null)
            WhisperSecret(
                kind = o.optString("kind", "text"),
                body = if (o.isNull("body")) null else o.optString("body"),
                mediaRef = if (o.isNull("media_ref")) null else o.optString("media_ref"),
                secs = o.optInt("secs", 15),
                glance = o.optBoolean("glance"),
            )
        }.getOrNull()
    }

    /** Burn an incoming whisper and tell its sender (network: IO). */
    suspend fun whisperBurn(messageId: String, usedSecs: Int, shot: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`whisperBurn`(messageId, usedSecs.coerceAtLeast(0).toUInt(), shot) }.getOrDefault(false)
            .also { if (it) scheduleReliableDelivery() }
    }

    /** Our media message is a whisper (or a timed view): its publish and any retry go out as one. */
    suspend fun whisperMarkOutgoing(chatId: String, messageId: String, kind: String, secs: Int, glance: Boolean = false) =
        withContext(Dispatchers.IO) {
            runCatching { core.`whisperMarkOutgoing`(chatId, messageId, kind, secs.coerceAtLeast(0).toUInt(), glance) }
        }

    suspend fun sendDisappearing(chatId: String, text: String, ttlSeconds: UInt): String =
        withContext(Dispatchers.IO) {
            val id = core.`sendDisappearing`(chatId, text, ttlSeconds)
            scheduleReliableDelivery()
            id
        }

    suspend fun markMessageRead(chatId: String, messageId: String) =
        withContext(Dispatchers.IO) {
            core.`markMessageRead`(chatId, messageId)
        }

    suspend fun markViewOnceConsumed(messageId: String) = withContext(Dispatchers.IO) {
        core.`markViewOnceConsumed`(messageId)
    }

    suspend fun getMessages(
        chatId: String,
        limit: UInt = 200u,
        offset: UInt = 0u
    ): List<MessageInfo> = withContext(Dispatchers.IO) {
        core.`getMessages`(chatId, limit, offset)
    }

    /**
     * Every message the device keeps for [chatId]: the chat itself and, in a
     * channel, every post's discussion. For storage accounting and cache
     * trimming — what a screen shows is [getMessages] / [getThread].
     */
    suspend fun getAllStoredMessages(chatId: String, limit: UInt): List<MessageInfo> = withContext(Dispatchers.IO) {
        val feed = core.`getMessages`(chatId, limit, 0u)
        val posts = runCatching { com.alphamessenger.ui.channel.ThreadSummary.parse(core.`threadSummariesJson`(chatId)).keys }
            .getOrDefault(emptySet())
        feed + posts.flatMap { runCatching { core.`getThread`(chatId, it, limit, 0u) }.getOrDefault(emptyList()) }
    }

    // --- Privacy: who may reach me (the core decides, on this phone) ---

    /** Current privacy rules: setting name → value (see core `privacy_settings_json`). */
    fun privacySettings(): Map<String, String> = runCatching {
        val o = org.json.JSONObject(core.`privacySettingsJson`())
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap()).also { syncLastSeenReciprocity(it["last_seen"]) }

    /** Change a rule; a change to what others see is sent to them at once (network: IO). */
    suspend fun setPrivacy(name: String, value: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { core.`setPrivacy`(name, value) }.getOrDefault(false)
                .also { ok -> if (ok && name == "last_seen") syncLastSeenReciprocity(value) }
        }

    /** Hidden from everyone → we see no one's time either. */
    private fun syncLastSeenReciprocity(value: String?) {
        com.alphamessenger.ui.chat.LastSeenReciprocity.hideOthers = value == "nobody"
    }

    /** My contacts: direct chat id → the person's npub. */
    fun contacts(): Map<String, String> = runCatching {
        val a = org.json.JSONArray(core.`contactsJson`())
        (0 until a.length()).associate { i -> a.getJSONObject(i).let { it.optString("chat_id") to it.optString("npub") } }
    }.getOrDefault(emptyMap())

    /** A page of [chatId] for an export: oldest first, after (afterAt, afterId). */
    suspend fun exportMessagesPage(
        chatId: String, afterAt: String, afterId: String, since: Long, until: Long, limit: UInt,
    ): List<uniffi.alphamessenger.MessageInfo> = withContext(Dispatchers.IO) {
        core.`exportMessagesPage`(chatId, afterAt, afterId, since, until, limit)
    }

    /** {"<message id>": {"<emoji>": count}} for every reaction in [chatId]. */
    fun exportReactionsJson(chatId: String): String =
        runCatching { core.`exportReactionsJson`(chatId) }.getOrDefault("{}")

    /** Take someone out of my contacts (the chat stays) or back in. */
    suspend fun setContact(npub: String, contact: Boolean) = withContext(Dispatchers.IO) {
        runCatching { core.`setContact`(npub, contact) }
        notifyChatListDirty()
    }

    fun callAllowed(chatId: String, senderId: String, video: Boolean): Boolean =
        runCatching { core.`callAllowed`(chatId, senderId, video) }.getOrDefault(false)

    /** Chats waiting in Requests, newest first. */
    fun chatRequests(): List<ChatRequest> = runCatching {
        val a = org.json.JSONArray(core.`chatRequestsJson`())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ChatRequest(o.optString("chat_id"), o.optString("from"), o.optString("kind"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun isChatRequest(chatId: String): Boolean = runCatching { core.`isChatRequest`(chatId) }.getOrDefault(false)

    /** Accept: an ordinary chat from now on (the other side gets our profile). */
    suspend fun acceptChatRequest(chatId: String) = withContext(Dispatchers.IO) {
        runCatching { core.`acceptChatRequest`(chatId) }
        notifyChatListDirty()
    }

    /** Refuse: the chat is removed without telling anyone; [block] also blocks its sender. */
    suspend fun declineChatRequest(chatId: String, block: Boolean) = withContext(Dispatchers.IO) {
        val from = chatRequests().firstOrNull { it.chatId == chatId }?.from
        // The core forgets the chat completely (history, keys, the person→chat link).
        runCatching { core.`declineChatRequest`(chatId, block) }
        if (block && !from.isNullOrBlank()) UserBlocklist(context).block(from, "", "")
        if (block) _blockListChanged.tryEmit(Unit)
        notifyChatListDirty()
    }

    /** The discussion of channel post [postId]: its comments, paged like [getMessages]. */
    suspend fun getThread(chatId: String, postId: String, limit: UInt = 200u, offset: UInt = 0u): List<MessageInfo> =
        withContext(Dispatchers.IO) { core.`getThread`(chatId, postId, limit, offset) }

    /** Comments under each post of [chatId] (posts without comments are absent). */
    suspend fun threadSummaries(chatId: String): Map<String, com.alphamessenger.ui.channel.ThreadSummary> =
        withContext(Dispatchers.IO) {
            com.alphamessenger.ui.channel.ThreadSummary.parse(runCatching { core.`threadSummariesJson`(chatId) }.getOrNull())
        }

    // --- Media ---

    /** Optimistic send phase 1 — persist immediately (local file), returns id. */
    /** One attachment kept in the history (album parts one by one). */
    data class StoredMedia(val chatId: String, val mediaType: String, val hash: String, val localPath: String)

    suspend fun storedMedia(): List<StoredMedia> = withContext(Dispatchers.IO) {
        runCatching { core.`storedMedia`().chunked(4) { StoredMedia(it[0], it[1], it[2], it[3]) } }.getOrDefault(emptyList())
    }

    /** [chatId]'s messages of these kinds (the core's media_kind), newest first, whole history. */
    suspend fun chatMedia(chatId: String, kinds: List<String>, limit: Int = 2000, offset: Int = 0): List<uniffi.alphamessenger.MessageInfo> =
        withContext(Dispatchers.IO) { runCatching { core.`chatMedia`(chatId, kinds, limit.toUInt(), offset.toUInt()) }.getOrDefault(emptyList()) }

    suspend fun messagesByIds(ids: List<String>): List<uniffi.alphamessenger.MessageInfo> =
        withContext(Dispatchers.IO) { if (ids.isEmpty()) emptyList() else runCatching { core.`messagesByIds`(ids) }.getOrDefault(emptyList()) }

    /** Rows of [chatId]'s history newer than [messageId], or null when it is gone. */
    suspend fun messageRank(chatId: String, messageId: String): Int? =
        withContext(Dispatchers.IO) { runCatching { core.`messageRank`(chatId, messageId)?.toInt() }.getOrNull() }

    /** The first message of [chatId] written at or after [at] (RFC 3339), or null. */
    suspend fun messageAtDate(chatId: String, at: String): String? =
        withContext(Dispatchers.IO) { core.`messageAtDate`(chatId, at) }

    /** Messages matching [query] word by word, newest first; [kind] narrows to one kind of
     *  attachment ("media" | "file" | "link" | "music" | "voice"); [chatId] null = every chat. */
    suspend fun searchMessages(
        query: String,
        kind: String? = null,
        chatId: String? = null,
        /** "self" or a person's pubkey hex. */
        sender: String? = null,
        /** RFC 3339 bounds of the period, [since, until). */
        since: String? = null,
        until: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<uniffi.alphamessenger.SearchHit> = withContext(Dispatchers.IO) {
        core.`searchMessages`(query, kind, chatId, sender, since, until, limit.toUInt(), offset.toUInt())
    }

    // --- Scheduled messages (held by the core until their time) ---------

    /** Scheduled messages per chat: chatId → (messageId → send time, millis). */
    val held: kotlinx.coroutines.flow.StateFlow<Map<String, Map<String, Long>>> get() = HeldMessages.byChat
    private val _held get() = HeldMessages.byChat

    /**
     * The next message sent in [chatId] — of ANY kind: text, photo, video,
     * album, voice, music, file, location — is created as usual but published
     * only at [atMillis]. Text is held inside the core; media is held by id as
     * soon as its message exists (below).
     */
    fun armSchedule(chatId: String, atMillis: Long) {
        runCatching { core.`armScheduledSend`(chatId, atMillis / 1000) }
        com.alphamessenger.service.ReleaseHeldWorker.schedule(context, atMillis)
    }

    // --- Linked devices: this account on several phones ---

    private val _devicesChanged = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** The list of our phones or a link in progress changed. */
    val devicesChanged: SharedFlow<String> = _devicesChanged.asSharedFlow()
    private val deviceInbox = Mutex()

    /** How this phone is listed on the others. */
    val thisDeviceName: String
        get() = (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim()

    data class LinkedDevice(val id: String, val name: String, val addedAt: Long, val isThis: Boolean)

    suspend fun linkedDevices(): List<LinkedDevice> = withContext(Dispatchers.IO) {
        runCatching {
            val a = org.json.JSONArray(core.`devicesJson`())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LinkedDevice(o.optString("id"), o.optString("name"), o.optLong("added_at"), o.optBoolean("this"))
            }
        }.getOrDefault(emptyList())
    }

    /** Notes from our other phones and device requests to us as a group's owner. */
    suspend fun processDeviceInbox() = withContext(Dispatchers.IO) {
        deviceInbox.withLock {
            val n = runCatching { core.`processDeviceInbox`() }.getOrDefault(0u)
            if (n > 0u) notifyChatListDirty()
        }
    }

    /** The new phone: the QR text that the linked phone scans. */
    suspend fun linkBegin(): String = withContext(Dispatchers.IO) { core.`linkBegin`() }

    /** The new phone: `{"state": "waiting" | "offer", "name", "npub"}`. */
    suspend fun linkPoll(): String = withContext(Dispatchers.IO) { runCatching { core.`linkPoll`() }.getOrDefault("{}") }

    /** The new phone: take the account offered. */
    suspend fun linkAccept() = withContext(Dispatchers.IO) { core.`linkAccept`(thisDeviceName) }

    /** A linked phone: send our account to the phone that shows [qr]. */
    suspend fun linkDevice(qr: String) = withContext(Dispatchers.IO) { core.`linkDevice`(qr, thisDeviceName) }

    /** The link this phone runs: `{stage, total, done, asked}`. */
    suspend fun linkStatus(): String = withContext(Dispatchers.IO) { runCatching { core.`linkStatusJson`() }.getOrDefault("{}") }

    suspend fun unlinkDevice(id: String) = withContext(Dispatchers.IO) {
        core.`unlinkDevice`(id)
        notifyChatListDirty()
    }

    /** The next message sent in [chatId], of any kind, goes without sound. */
    fun armSilent(chatId: String) {
        runCatching { core.`armSilentSend`(chatId) }
    }

    suspend fun isMessageSilent(msgId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`isMessageSilent`(msgId) }.getOrDefault(false)
    }

    /** A media message was just created: if a schedule is armed for its chat, hold it. */
    private fun holdIfArmed(chatId: String, msgId: String?) {
        if (msgId.isNullOrBlank()) return
        // The core owns the arm: whichever message comes first takes it.
        val at = runCatching { core.`takeArmedSend`(chatId) }.getOrDefault(0L)
        if (at <= 0L) return
        runCatching { core.`holdMessage`(msgId, chatId, at) }
        refreshHeld()
    }

    fun refreshHeld() {
        val raw = runCatching { core.`heldMessagesJson`("") }.getOrDefault("[]")
        val map = HashMap<String, HashMap<String, Long>>()
        runCatching {
            val a = org.json.JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                map.getOrPut(o.getString("chat")) { HashMap() }[o.getString("id")] = o.optLong("nb") * 1000
            }
        }
        _held.value = map
    }

    suspend fun releaseDueHeld() = withContext(Dispatchers.IO) {
        val n = runCatching { core.`releaseDueHeld`() }.getOrDefault(0u)
        refreshHeld()
        if (n > 0u) notifyChatListDirty()
    }

    suspend fun rescheduleHeld(msgId: String, atMillis: Long) = withContext(Dispatchers.IO) {
        runCatching { core.`rescheduleHeld`(msgId, atMillis / 1000) }
        com.alphamessenger.service.ReleaseHeldWorker.schedule(context, atMillis)
        refreshHeld(); notifyChatListDirty()
    }

    suspend fun sendHeldNow(msgId: String) = rescheduleHeld(msgId, System.currentTimeMillis())

    suspend fun cancelHeld(msgId: String) = withContext(Dispatchers.IO) {
        runCatching { core.`cancelHeld`(msgId) }
        refreshHeld(); notifyChatListDirty()
    }

    suspend fun editHeldText(msgId: String, text: String) = withContext(Dispatchers.IO) {
        runCatching { core.`editHeldText`(msgId, text) }
        Unit
    }

    suspend fun persistOutgoingMedia(
        chatId: String, filePath: String, mediaType: String, caption: String? = null,
        ttlSecs: UInt = 0u, thread: String? = null,
    ): String = withContext(Dispatchers.IO) {
        core.`persistOutgoingMedia`(chatId, plainForSend(filePath), mediaType, caption, ttlSecs, thread)
            .also { refreshHeld() }
    }

    /**
     * What goes up is the content, never our sealed form: a cached (sealed)
     * file is sent from a plain copy. The copy's name follows the source, so
     * the local echo and the upload of one send point at the same file.
     */
    private fun plainForSend(path: String): String {
        val src = java.io.File(path)
        if (!MediaVault.isSealed(src)) return path
        val out = java.io.File(context.cacheDir, "send_plain_" + Integer.toHexString(path.hashCode()) + "_" + src.length())
        if (!(out.exists() && out.length() == MediaVault.length(src))) {
            val started = android.os.SystemClock.elapsedRealtime()
            val tmp = java.io.File(out.path + ".tmp")
            MediaVault.copyPlain(src, tmp)
            tmp.renameTo(out)
            val ms = android.os.SystemClock.elapsedRealtime() - started
            if (ms >= 500) android.util.Log.w("AlphaMediaTrace", "UPLOAD_PREP unseal ms=$ms bytes=${out.length()}")
        }
        return out.absolutePath
    }

    /** Persist a local echo DIRECTLY from an existing MediaRef (forward/reuse) so the
     *  bubble shows its final content (filename/poster/size) from the first frame. */
    suspend fun persistOutgoingMediaRef(
        chatId: String, mlsRef: String, mediaType: String, caption: String? = null,
        ttlSecs: UInt = 0u, thread: String? = null,
    ): String = withContext(Dispatchers.IO) {
        core.`persistOutgoingMediaRef`(chatId, mlsRef, mediaType, caption, ttlSecs, thread)
    }

    /** Uploads the finished part of a video while the encoder writes [growingPath]; returns on [endPreupload]. */
    suspend fun preuploadGrowing(opId: String, growingPath: String, finalPath: String, estimate: Long): Long =
        withContext(Dispatchers.IO) {
            runCatching { core.`preuploadGrowing`(opId, growingPath, finalPath, estimate.coerceAtLeast(0L).toULong()).toLong() }
                .getOrDefault(0L)
        }

    fun endPreupload(opId: String) {
        runCatching { core.`endPreupload`(opId) }
    }

    /** Marks a persisted outgoing media row as answering [replyTo]; set before it is broadcast. */
    suspend fun setMediaReply(msgId: String, replyTo: String) = withContext(Dispatchers.IO) {
        runCatching { core.`setMediaReply`(msgId, replyTo) }
    }

    /** Fast-batch phase B — broadcast with confirmed publish (awaits relay OK).
     *  Returns true on success so the caller can retry on failure. */
    suspend fun broadcastMedia(
        chatId: String, msgId: String, mlsRef: String, mediaType: String, caption: String?,
        expiryMode: String? = null, ttlSecs: UInt? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { core.`broadcastMedia`(chatId, msgId, mlsRef, mediaType, caption, expiryMode, ttlSecs) }.isSuccess
    }

    // --- Album send (ONE MLS message per batch) ---

    /** Persist a pending album locally → one bubble. items = JSON array of
     *  {local_path, media_type, poster_b64?}. Returns the message id. */
    suspend fun persistOutgoingAlbum(
        chatId: String, itemsJson: String, caption: String?, ttlSecs: UInt = 0u, thread: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        runCatching { core.`persistOutgoingAlbum`(chatId, itemsJson, caption, ttlSecs, thread) }.getOrNull()
            .also { refreshHeld() }
    }

    /** Upload ONE blob, return its ref JSON (parallelizable). Null on failure. */
    suspend fun uploadBlobRefOnly(
        filePath: String, mediaType: String, posterB64: String?, opId: String,
        metaJson: String? = null,
    ): String? = uploadBlobRef(filePath, mediaType, posterB64, opId, metaJson).getOrNull()

    /** As [uploadBlobRefOnly], keeping the core's reason: a weak link is retried, a refusal is not. */
    suspend fun uploadBlobRef(
        filePath: String, mediaType: String, posterB64: String?, opId: String,
        metaJson: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        keepAwake.during { runCatching { core.`uploadBlobRefOnly`(plainForSend(filePath), mediaType, posterB64, opId, metaJson) } }
    }

    /** Upload a file of a set still being composed, over the chat's route; null if it failed. */
    suspend fun preUploadMedia(chatId: String, opId: String, filePath: String, mediaType: String): String? =
        withContext(Dispatchers.IO) {
            keepAwake.during { runCatching { core.`preUploadMedia`(chatId, opId, filePath, mediaType) }.getOrNull() }
        }

    /** Stop a pre-upload no message used and delete what of it reached the servers. */
    suspend fun discardPreUpload(chatId: String, opId: String, filePath: String, refJson: String) = withContext(Dispatchers.IO) {
        runCatching { core.`discardPreUpload`(chatId, opId, filePath, refJson) }
        Unit
    }

    /** Broadcast the finished album as ONE confirmed MLS message. itemRefs =
     *  JSON array of refs from [uploadBlobRefOnly]. True on success. */
    suspend fun sendAlbum(
        chatId: String, msgId: String, itemRefsJson: String, caption: String?, ttlSecs: UInt = 0u,
    ): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { core.`sendAlbum`(chatId, msgId, itemRefsJson, caption, ttlSecs) }.isSuccess
        scheduleReliableDelivery()
        ok
    }

    /** Byte-level media download progress: (opId, downloaded, total). */
    val mediaProgress: SharedFlow<Triple<String, Long, Long>> get() = callbackBridge.mediaProgress

    /** Download + decrypt a media blob into the local cache, keyed by its
     *  content hash so the same blob is fetched at most once. Returns the
     *  absolute path of the decrypted file, or null on failure.
     *  When `opId` is non-null, byte-level progress is reported via
     *  [mediaProgress] keyed by that id (use the message id). */
    /** Stop the native download running under [opId] (the ✕ on a downloading file). */
    fun cancelMediaDownload(opId: String) {
        opHash[opId]?.let { stoppedHashes.add(it) }
        runCatching { core.`cancelMediaDownload`(opId) }
    }

    /** Which blob each tracked download op fetches. */
    private val opHash = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Blobs whose running download was told to stop and is still winding down. */
    private val stoppedHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun downloadMediaToCache(mediaRefJson: String, opId: String? = null): String? {
        val lite = runCatching { gson.fromJson(mediaRefJson, MediaRefLite::class.java) }.getOrNull()
        // Sender's own media → use the local file directly, never download.
        lite?.local_path?.takeIf { it.isNotBlank() && java.io.File(it).exists() }?.let { return it }
        // The same name every reader looks for (see blobHashOf).
        val hash = blobHashOf(mediaRefJson)
        // Already fully decrypted on disk → return at once (no download, no dedup).
        val cached = java.io.File(java.io.File(context.cacheDir, "media"), hash)
        if (cached.exists() && cached.length() > 0L) return cached.absolutePath
        // SINGLE-FLIGHT keyed by the output hash: only ONE native download ever
        // writes cacheDir/media/<hash> at a time. Concurrent callers for the same
        // blob join the in-flight download instead of racing writes to that file
        // (the size-oscillation / backward-progress / endless-re-download bug).
        // Runs detached in materializeScope so a caller leaving does NOT kill the
        // fetch; the map entry clears when the job completes (single-flight stays
        // correct for its whole lifetime).
        //
        // A download that was just stopped (✕) winds down within ~0.1 s: joining
        // it would hand this caller its "stopped" result. Let it end, then start a
        // fresh one — it resumes from the `.part` the stopped one left.
        if (hash in stoppedHashes) {
            downloadMutex.withLock { inFlightDownload[hash] }?.join()
            stoppedHashes.remove(hash)
        }
        if (opId != null) opHash[opId] = hash
        val deferred = downloadMutex.withLock {
            inFlightDownload[hash]?.takeIf { it.isActive } ?: materializeScope.async {
                downloadMediaToCacheInner(mediaRefJson, hash, opId)
            }.also { d ->
                inFlightDownload[hash] = d
                d.invokeOnCompletion {
                    materializeScope.launch {
                        downloadMutex.withLock { if (inFlightDownload[hash] === d) inFlightDownload.remove(hash) }
                    }
                }
            }
        }
        return deferred.await()
    }

    /** The actual per-blob download body — only ever run ONE-at-a-time per hash by
     *  [downloadMediaToCache]'s single-flight. */
    private suspend fun downloadMediaToCacheInner(
        mediaRefJson: String, hash: String, opId: String?,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(context.cacheDir, "media").apply { mkdirs() }
            val out = java.io.File(dir, hash)
            val existed = out.exists() && out.length() > 0L
            if (!existed) keepAwake.during {
                if (opId != null) core.`downloadMediaWithProgress`(mediaRefJson, out.absolutePath, opId)
                else core.`downloadMedia`(mediaRefJson, out.absolutePath)
            }
            goneMark(hash).delete()
            out.absolutePath.takeIf { out.exists() && out.length() > 0L }
        }.onFailure {
            // Every server said the copies are gone: remembered, so the bubble says so
            // instead of offering a retry that cannot work.
            val why = when (it) {
                is uniffi.alphamessenger.MessengerException.MediaGone -> "gone"
                is uniffi.alphamessenger.MessengerException.MediaUnknownServer -> "unknown"
                else -> null
            }
            if (why != null) goneMark(hash).apply { parentFile?.mkdirs(); writeText(why) }
        }.getOrNull()
    }

    private fun goneMark(hash: String) = java.io.File(java.io.File(context.cacheDir, "media"), "$hash.gone")

    /** No server holds this file any more (the last download attempt was told so). */
    fun isMediaGone(mediaRefJson: String): Boolean = goneMark(blobHashOf(mediaRefJson)).exists()

    /** Not on any server we know; only the sender's own server, reached through Tor alone. */
    fun isMediaOnUnknownServer(mediaRefJson: String): Boolean =
        runCatching { goneMark(blobHashOf(mediaRefJson)).readText() == "unknown" }.getOrDefault(false)

    suspend fun ownMediaServers(): List<String> = withContext(Dispatchers.IO) { runCatching { core.ownMediaServers() }.getOrDefault(emptyList()) }

    suspend fun setOwnMediaServers(urls: List<String>) = withContext(Dispatchers.IO) { runCatching { core.setOwnMediaServers(urls) } }

    /** "" when [url] stores and returns a test file, else the reason. */
    suspend fun probeMediaServer(url: String): String = withContext(Dispatchers.IO) {
        runCatching { core.probeMediaServer(url) }.getOrElse { it.message ?: "error" }
    }

    /** A deliberate new attempt: forget the "gone" verdict until the servers repeat it. */
    fun forgetMediaGone(mediaRefJson: String) { goneMark(blobHashOf(mediaRefJson)).delete() }

    /** Fast, synchronous "is this blob already decrypted on disk?" check — the
     *  SAME path logic as [downloadMediaToCache] but WITHOUT ever downloading.
     *  Lets the UI skip a stale "tap to download" badge when the file is in fact
     *  already materialized (the in-memory path cache is wiped when the process
     *  dies overnight, so a badge would otherwise reappear over cached media). */
    /**
     * A delivered attachment becomes a cache entry, sealed like every other: the
     * send's own files are swept a day later, and the sender must not then fetch
     * their own photo or video back from storage.
     */
    suspend fun adoptSentFile(path: String, mediaRefJson: String) = withContext(Dispatchers.IO) {
        runCatching {
            val src = java.io.File(path)
            if (!src.exists() || src.length() <= 0L) return@runCatching
            val dir = java.io.File(context.cacheDir, "media").apply { mkdirs() }
            val dst = java.io.File(dir, blobHashOf(mediaRefJson))
            if (dst.exists() && dst.length() > 0L) return@runCatching
            val tmp = java.io.File(dir, dst.name + ".adopt.tmp")
            com.alphamessenger.data.MediaVault.open(src).use { input ->
                com.alphamessenger.data.MediaVault.sealedOutput(tmp).use { input.copyTo(it, 256 * 1024) }
            }
            if (!tmp.renameTo(dst)) tmp.delete()
            // A "deleted by the user" mark from before would keep it hidden.
            java.io.File(dir, dst.name + ".evicted").delete()
        }.onFailure { android.util.Log.w("AlphaCache", "adopt failed: ${it.message}") }
    }

    fun cachedMediaPathIfPresent(mediaRefJson: String?): String? {
        if (mediaRefJson.isNullOrBlank()) return null
        return runCatching {
            // ONLY check the cache directory — NOT lite.local_path (the sender's
            // original camera-roll file). The sender's original is NOT a cache
            // entry; DeleteCache doesn't touch it, so returning it here would
            // make cacheEntry() say "exists" even after the user evicted the
            // cache → resolve() would set Completed → ▶ would stay after
            // DeleteCache. The cache blob lives at cacheDir/media/<hash>.
            val hash = blobHashOf(mediaRefJson)
            val out = java.io.File(java.io.File(context.cacheDir, "media"), hash)
            out.absolutePath.takeIf { out.exists() && out.length() > 0L }
        }.getOrNull()
    }

    /** Pure DATA about a blob on disk — the Repository's contribution to the
     *  unified media-state architecture. Never downloads. Fed to [TransferEngine],
     *  which folds it (with policy + progress) into a single TransferSnapshot. */
    /**
     * THE single "where are the bytes" resolver. Disk truth lives ONLY here.
     *
 * The precedence is other messengers's: the sender's own original wins over the
     * decrypted cache, the cache over a streamable ref, streaming over a remote
     * fetch. The [TransferEngine] folds the result into the download snapshot, so
     * no UI layer ever calls `File.exists()` to decide a file's state.
     */
    fun mediaAvailability(
        mediaRefJson: String?, senderLocalPath: String?,
    ): com.alphamessenger.transfer.FileSource {
        return mediaAvailabilityInner(mediaRefJson, senderLocalPath)
    }

    private fun mediaAvailabilityInner(
        mediaRefJson: String?, senderLocalPath: String?,
    ): com.alphamessenger.transfer.FileSource {
        if (mediaRefJson.isNullOrBlank()) return com.alphamessenger.transfer.FileSource.Missing
        // 1. Sender's own original (outgoing) — always usable, never fetched.
        senderLocalPath?.takeIf {
            it.isNotBlank() && runCatching {
                val f = java.io.File(it); f.exists() && f.length() > 0L
            }.getOrDefault(false)
        }?.let { return com.alphamessenger.transfer.FileSource.Original(it) }
        // 2. Decrypted cache blob (cacheDir/media/<hash>).
        cachedMediaPathIfPresent(mediaRefJson)?.let {
            return com.alphamessenger.transfer.FileSource.Cache(it)
        }
        // 3. Streamable ref (progressive chunks) → no full download needed.
        if (cacheEntry(mediaRefJson).streamReady) return com.alphamessenger.transfer.FileSource.Streaming
        // 4. A remote ref exists → it can be downloaded.
        return com.alphamessenger.transfer.FileSource.Remote
    }

    fun cacheEntry(mediaRefJson: String?): com.alphamessenger.transfer.CacheEntry {
        if (mediaRefJson.isNullOrBlank()) return com.alphamessenger.transfer.CacheEntry.ABSENT
        return runCatching {
            val path = cachedMediaPathIfPresent(mediaRefJson)
            val f = path?.let { java.io.File(it) }
            val exists = f != null && f.exists() && f.length() > 0L
            // Poster/thumbnail travels inside the ref → survives cache eviction.
            val thumb = mediaRefJson.contains("poster_b64") || mediaRefJson.contains("thumbnail_urls")
            val stream = mediaRefJson.contains("stream_chunks")
            com.alphamessenger.transfer.CacheEntry(
                exists = exists,
                localPath = if (exists) path else null,
                verified = exists, // the pipeline verifies BLAKE3 before caching
                size = if (exists) f!!.length() else 0L,
                thumbExists = thumb,
                streamReady = stream,
                lastAccessMs = if (exists) f!!.lastModified() else 0L,
            )
        }.getOrDefault(com.alphamessenger.transfer.CacheEntry.ABSENT)
    }

    suspend fun downloadMedia(
        mediaRefJson: String,
        outputPath: String
    ) = withContext(Dispatchers.IO) {
        keepAwake.during { core.`downloadMedia`(mediaRefJson, outputPath) }
    }

    /** Delete the decrypted/streamed cache files for a media ref (used when a
     *  disappearing message expires — the blob must not linger on disk). Also
     *  removes the sender's local copy if it lives in our cache. Best-effort. */
    fun deleteMediaCacheFiles(mediaRefJson: String?) {
        deleteMediaCacheBytes(mediaRefJson, deleteLocalCopy = false)
    }

    /**
     * Remove ONLY the disposable cache and report how many bytes really went away.
     *
     * Byte ownership is split, and exactly one kind may be deleted:
     *
     * * **SOURCE** — the user's original. Not ours. Never.
     * * **Set OUTBOX** — `filesDir/outbox/work`. Never touched.
     * * **CACHE** — `cacheDir/media`, `cacheDir/stream_chunks`, partial-download
     *   leftovers and our own copy of a finished single send `cacheDir/send_*`.
     *
     * The last one matters: it has TWO phases that need different treatment —
     *
     * | phase | what it is | delete |
     * |---|---|---|
     * | send in progress (queued, uploading, waiting for network/retry) | active resume source | NO |
     * | send finished | disposable local copy | YES |
     *
     * The decision is made by the CALLER ([deleteLocalCopy]): it needs a queue
     * query, which must not run on the main thread.
     * The byte count is returned so the result can be reported truthfully —
     * the original's existence proves nothing about deletion.
     *
     * @param deleteLocalCopy the send has finished, so its cached copy is
     *                        disposable again. Checked by the caller off-thread.
     * @return bytes deleted (0 — there was nothing to delete).
     */
    fun deleteMediaCacheBytes(mediaRefJson: String?, deleteLocalCopy: Boolean): Long {
        if (mediaRefJson.isNullOrBlank()) return 0L
        var freed = 0L
        runCatching {
            val lite = gson.fromJson(mediaRefJson, MediaRefLite::class.java)
            val dir = java.io.File(context.cacheDir, "media")
            val hash = blobHashOf(mediaRefJson)
            if (dir.exists()) {
                // Everything starting with the hash:
                //   <hash>              — whole file in the cache
                //   <hash>.evicted      — deletion marker (no bytes, not counted)
                //   <hash>.assemble.tmp — assembly temp
                //   <hash>_c0.part      — streaming chunk
                //   <hash>_c0.part.wtmp — working temp
                dir.listFiles { f -> f.name == hash || f.name.startsWith("$hash") }?.forEach {
                    val n = it.length()
                    if (it.delete() && !it.name.endsWith(".evicted")) freed += n
                }
            }
            val streamDir = java.io.File(context.cacheDir, "stream_chunks")
            if (streamDir.exists()) {
                streamDir.listFiles { f -> f.name.startsWith(hash) }?.forEach {
                    val n = it.length()
                    if (it.delete()) freed += n
                }
            }
            // --- Sender's own copy ---
            //
            // Deleted when the send has FINISHED (the caller decides) and the copy
            // was made by US. There are two of our locations, because there are two
            // send pipelines:
            //
            //   document / "send as file" / Share intake → cacheDir/send_*
            //   photo/video/set from the panel            → filesDir/outbox/work
            //
            // Set files are already treated as disposable by the product —
            // `MediaSendStore.sweep` removes them after a day; this is the same
            // rule, just triggered by a tap instead of a timer.
            //
            // The user's original (gallery, `content://`) matches neither location
            // and stays untouched.
            if (deleteLocalCopy) {
                lite?.local_path?.takeIf { it.isNotBlank() }?.let { p ->
                    val f = java.io.File(p)
                    val ours = f.absolutePath.startsWith(context.cacheDir.absolutePath) ||
                        f.absolutePath.startsWith(
                            java.io.File(java.io.File(context.filesDir, "outbox"), "work").absolutePath,
                        )
                    if (f.exists() && ours) {
                        val n = f.length()
                        if (f.delete()) freed += n
                    }
                }
            }
        }
        return freed
    }

    // --- Single unified media commit (materializeMedia) ---
    // The ONE place that produces a full decrypted file at cacheDir/media/<hash>.
    // Playback, save-to-gallery and forward all funnel through it — no second
    // assembly path. Single-flight (concurrent opens of the same hash share one
    // download), resumable (per-chunk .part), atomic (assemble.tmp → rename).
    private val materializeMutex = Mutex()
    private val inFlightMaterialize = HashMap<String, Deferred<String?>>()
    // Single-flight for downloadMediaToCache, keyed by the OUTPUT hash (the cache
    // filename). Without it, several callers for the SAME blob — the chat bubble's
    // auto-download, a re-tapped ↓, the album viewer, share, contact-info — each
    // ran their own native download that TRUNCATED and rewrote cacheDir/media/<hash>
    // concurrently. Proven in logs: one hash re-downloaded in a loop with the file
    // size oscillating 141↔184↔221 MB (progress "jumps backward", stalls, endless
    // re-download). Now N concurrent callers share ONE download; the rest await it.
    private val downloadMutex = Mutex()
    private val inFlightDownload = HashMap<String, Deferred<String?>>()
    private val materializeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun materializeKey(mediaRefJson: String): String = blobHashOf(mediaRefJson)

    /** Produce a FULL decrypted local file for a media ref (any kind): sender's
     *  local file, a non-streaming download, or — for streaming video — all
     *  chunks assembled into one file. Returns the path or null. THE single
     *  commit point (single-flight + resume + atomic). */
    suspend fun materializeMedia(mediaRefJson: String): String? {
        val key = materializeKey(mediaRefJson)
        val deferred = materializeMutex.withLock {
            val existing = inFlightMaterialize[key]
            if (existing != null && existing.isActive) {
                MediaMetrics.singleflightJoin.incrementAndGet()
                existing
            } else {
                // Runs in materializeScope, NOT the caller's scope — so it keeps
                // downloading even if the viewer closes (caller's await cancels,
                // the job doesn't). Map entry cleared when the JOB completes, so
                // single-flight stays correct for its whole lifetime.
                materializeScope.async { materializeMediaInner(mediaRefJson, key) }.also { d ->
                    inFlightMaterialize[key] = d
                    d.invokeOnCompletion {
                        materializeScope.launch {
                            materializeMutex.withLock {
                                if (inFlightMaterialize[key] === d) inFlightMaterialize.remove(key)
                            }
                        }
                    }
                }
            }
        }
        return deferred.await()
    }

    /** Fire-and-forget finalize of a streamed video's disk cache: assemble the
     *  chunks the player already persisted (+ fetch any unwatched tail via
     *  resume) → atomic commit. Single-flight, runs detached (survives the
     *  viewer closing). Next open plays locally, no network. */
    fun commitStreamedVideo(mediaRefJson: String) {
        materializeScope.launch { runCatching { materializeMedia(mediaRefJson) } }
    }

    private suspend fun materializeMediaInner(mediaRefJson: String, hash: String): String? =
        withContext(Dispatchers.IO) {
            val dir = java.io.File(context.cacheDir, "media").apply { mkdirs() }
            val out = java.io.File(dir, hash)
            // Already committed → never re-download (no network).
            if (out.exists() && out.length() > 0L) return@withContext out.absolutePath

            val ref = runCatching { org.json.JSONObject(mediaRefJson) }.getOrNull()
                ?: return@withContext null
            // Non-streaming / sender-local goes through the whole-file path.
            if (!ref.optBoolean("streaming", false)) {
                return@withContext downloadMediaToCache(mediaRefJson)
            }
            val chunks = ref.optJSONArray("chunks") ?: return@withContext null

            MediaMetrics.materializeStarted.incrementAndGet()
            runCatching {
                var resumed = false
                var expectedTotal = 0L
                // 1. Fetch each chunk to a persistent .part (resume: skip complete).
                for (i in 0 until chunks.length()) {
                    val origSize = chunks.getJSONObject(i).optLong("original_size", -1L)
                    if (origSize >= 0) expectedTotal += origSize
                    val part = java.io.File(dir, "${hash}_c$i.part")
                    if (part.exists() && origSize >= 0 && MediaVault.length(part) == origSize) { resumed = true; continue }
                    val tmp = java.io.File(dir, "${hash}_c$i.part.tmp")
                    keepAwake.during { core.`downloadStreamChunk`(mediaRefJson, i.toUInt(), tmp.absolutePath) } // per-chunk hash verified in Rust
                    if (!tmp.exists() || tmp.length() <= 0L) { tmp.delete(); throw java.io.IOException("chunk $i empty") }
                    if (!tmp.renameTo(part)) { tmp.delete(); throw java.io.IOException("chunk $i rename") }
                }
                if (resumed) MediaMetrics.resumeUsed.incrementAndGet() else MediaMetrics.resumeFromZero.incrementAndGet()

                // 2. Concatenate parts (streaming to disk — never the whole file in RAM).
                val assemble = java.io.File(dir, "$hash.assemble.tmp")
                MediaVault.sealedOutput(assemble).use { o ->
                    for (i in 0 until chunks.length()) {
                        MediaVault.open(java.io.File(dir, "${hash}_c$i.part")).use { it.copyTo(o) }
                    }
                }
                // 3. Verify by total size (per-chunk BLAKE3 already checked in Rust;
                //    ref.hash is BLAKE3, not SHA-256, so no full re-hash here).
                if (expectedTotal > 0 && MediaVault.length(assemble) != expectedTotal) {
                    assemble.delete()
                    throw java.io.IOException("size ${MediaVault.length(assemble)} != $expectedTotal")
                }
                // 4. Atomic publish + cleanup parts.
                if (!assemble.renameTo(out)) { assemble.delete(); throw java.io.IOException("final rename failed") }
                for (i in 0 until chunks.length()) java.io.File(dir, "${hash}_c$i.part").delete()

                MediaMetrics.materializeFinished.incrementAndGet()
                // Full content path only in debug builds: by itself it's not a key, but
                // it's a stable file fingerprint, and it has no business being in a
                // release build's log.
                if (com.alphamessenger.BuildConfig.DEBUG) {
                    android.util.Log.i("MediaCache", "materialized $hash — ${MediaMetrics.snapshot()}")
                } else {
                    android.util.Log.i("MediaCache", "materialized ${hash.take(8)}… — ${MediaMetrics.snapshot()}")
                }
                out.absolutePath
            }.getOrElse { e ->
                MediaMetrics.materializeFailed.incrementAndGet()
                android.util.Log.w("MediaCache", "materialize ${hash.take(8)}… failed: ${e.message}")
                null
            }
        }

    // --- Streaming E2EE ---

    /** Send a media file with per-chunk streaming E2EE (for progressive video playback). */
    /** Download and decrypt a single streaming chunk. Returns the plaintext size. */
    /** Full ref with the chunk list (external manifest resolved); null on failure. */
    suspend fun resolveMediaRef(mediaRefJson: String): String? = withContext(Dispatchers.IO) {
        runCatching { core.`resolveMediaRef`(mediaRefJson) }.getOrNull()
    }

    suspend fun downloadStreamChunk(
        mediaRefJson: String,
        chunkIndex: Int,
        outputPath: String
    ): String = withContext(Dispatchers.IO) {
        keepAwake.during { core.`downloadStreamChunk`(mediaRefJson, chunkIndex.toUInt(), outputPath) }
    }

    /** Get the number of streaming chunks in a media ref (0 for legacy). */
    fun getStreamChunkCount(mediaRefJson: String): Int {
        return runCatching { core.`getStreamChunkCount`(mediaRefJson).toInt() }.getOrDefault(0)
    }

    /** Get chunk info JSON for a specific streaming chunk. */
    fun getStreamChunkInfo(mediaRefJson: String, chunkIndex: Int): String {
        return core.`getStreamChunkInfo`(mediaRefJson, chunkIndex.toUInt())
    }

    /** Access the raw FFI core for DataSource use (streaming ExoPlayer). */
    fun getCore(): AlphaMessengerCore = core

    // --- Sync ---

    suspend fun startSync() = withContext(Dispatchers.IO) {
        core.`startSync`()
    }

    suspend fun stopSync() = withContext(Dispatchers.IO) {
        core.`stopSync`()
    }

    /** Pulse of the receiving side (see core `sync_health_json`). Reads only
     *  counters — never waits on the database or the network. */
    data class SyncHealth(
        val heartbeatAge: Long, val realtimeSilent: Long, val catchupAge: Long, val pausedRelays: Int,
        /** Enabled relays, and how many of them have a live socket right now. */
        val relays: Int = 0, val connected: Int = 0,
    )

    fun syncHealth(): SyncHealth? = runCatching {
        val o = org.json.JSONObject(core.`syncHealthJson`())
        SyncHealth(
            heartbeatAge = o.optLong("heartbeat_age", -1),
            realtimeSilent = o.optLong("realtime_silent", -1),
            catchupAge = o.optLong("catchup_age", -1),
            pausedRelays = o.optJSONArray("paused")?.length() ?: 0,
            relays = o.optInt("relays", 0),
            connected = o.optInt("connected", 0),
        )
    }.getOrNull()

    /** TRANSPORT hardening: force a clean relay reconnect + re-subscribe. Call on
     *  network-available / app-foreground so realtime delivery resumes at once. */
    suspend fun forceReconnect() = withContext(Dispatchers.IO) {
        core.`forceReconnect`()
    }

    /** The device's default network appeared, changed or went away. Returns once
     *  a relay is up again (a few seconds at most) when [available]. */
    suspend fun setNetworkAvailable(available: Boolean) = withContext(Dispatchers.IO) {
        core.`setNetworkAvailable`(available)
    }

    suspend fun catchUp(): ULong = withContext(Dispatchers.IO) {
        core.`catchUp`()
    }

    /** Re-publish any undelivered outbox messages (guaranteed delivery). Safe to
     *  call repeatedly — does nothing when the outbox is empty. */
    suspend fun replayOutbox(): UInt = withContext(Dispatchers.IO) {
        runCatching { core.`replayOutbox`() }.getOrDefault(0u)
    }

    /** Encrypt+publish messages composed while offline (defer-encryption).
     *  MUST be called AFTER catchUp so encryption uses the latest MLS state. */
    suspend fun flushPendingSends(): UInt = withContext(Dispatchers.IO) {
        runCatching { core.`flushPendingSends`() }.getOrDefault(0u)
    }

    /** IDs of outgoing messages still in the send queue (show "sending" clock). */
    suspend fun pendingSendIds(): Set<String> = withContext(Dispatchers.IO) {
        runCatching { core.`pendingSendIds`().toSet() }.getOrDefault(emptySet())
    }

    /** Retry a permanently-failed pending-media upload (user tapped "Retry").
     *  Flips the queued row back to active and re-drains. Returns true if sent. */
    suspend fun retryPendingMedia(msgId: String): Boolean = withContext(Dispatchers.IO) {
        keepAwake.during { runCatching { core.`retryPendingMedia`(msgId) }.getOrDefault(false) }
    }


    // --- Media send encryption keys for an unfinished upload ---
    //
    // Kept for ONE property: after process death, re-encrypting the same
    // unchanged source must yield a byte-identical ciphertext object.
    // Without it, resuming a partially-uploaded replica set is impossible —
    // the copy would get a different identifier, and there'd be nothing to
    // match it with the already-published ref.
    //
    // The key lives in the core DB, i.e. under SQLCipher: encrypted on disk,
    // readable only after unlock, wiped by panic erase. There is deliberately
    // no extra encryption here — it would be weaker and would create a second
    // place where the secret is stored.
    //
    // The CORE assembles the identifier from three fields — an arbitrary string
    // can't be passed from here, so nobody can read or clobber someone else's record.

    suspend fun storeMediaSendKey(
        sendId: String,
        itemIndex: Int,
        revision: Long,
        keyMaterial: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // The UDL sequence<u8> arrives as List<UByte> — converted HERE, at the
            // boundary, so a plain ByteArray sticks out.
            core.`storeMediaSendKey`(
                sendId, itemIndex.toUInt(), revision.toULong(),
                keyMaterial.map { it.toUByte() },
            )
            true
        }.getOrDefault(false)
    }

    suspend fun loadMediaSendKey(
        sendId: String,
        itemIndex: Int,
        revision: Long,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            core.`loadMediaSendKey`(sendId, itemIndex.toUInt(), revision.toULong())
                ?.map { it.toByte() }?.toByteArray()
        }.getOrNull()
    }

    suspend fun deleteMediaSendKey(sendId: String, itemIndex: Int, revision: Long) =
        withContext(Dispatchers.IO) {
            runCatching { core.`deleteMediaSendKey`(sendId, itemIndex.toUInt(), revision.toULong()) }
            Unit
        }

    /** The send finished or was cancelled — the secrets have no reason to exist. */
    suspend fun deleteMediaSendKeys(sendId: String): Int = withContext(Dispatchers.IO) {
        runCatching { core.`deleteMediaSendKeys`(sendId).toInt() }.getOrDefault(0)
    }

    // --- Offline media queue ---
    suspend fun enqueuePendingMediaSingle(
        chatId: String, msgId: String, filePath: String, mediaType: String,
        caption: String?, posterB64: String?, streaming: Boolean, ttlSecs: UInt = 0u,
        metaJson: String? = null,
    ) = withContext(Dispatchers.IO) {
        runCatching { core.`enqueuePendingMediaSingle`(chatId, msgId, filePath, mediaType, caption, posterB64, streaming, ttlSecs, metaJson) }
        scheduleReliableDelivery()
        Unit
    }

    suspend fun enqueuePendingMediaAlbum(
        chatId: String, msgId: String, itemsJson: String, caption: String?, ttlSecs: UInt = 0u,
    ) = withContext(Dispatchers.IO) {
        runCatching { core.`enqueuePendingMediaAlbum`(chatId, msgId, itemsJson, caption, ttlSecs) }
        scheduleReliableDelivery()
        Unit
    }

    suspend fun deletePendingMedia(msgId: String) = withContext(Dispatchers.IO) {
        runCatching { core.`deletePendingMedia`(msgId) }; Unit
    }

    /**
     * Stop the media upload IN THE CORE. Cancelling the coroutine never
     * reaches the core: the FFI call is synchronous and the work runs inside
     * `block_on`, so bytes would keep flowing and the message would still get
     * published at the end. The core checks this flag between chunks and before
     * publishing; for an album, cancelling the head cancels all its items
     * (`<msg_id>#<n>`).
     *
     * NOT a suspend fun: the cancel must happen immediately, in the same frame,
     * before the send coroutine is cancelled.
     */
    fun cancelMediaUpload(msgId: String) {
        val t = android.os.SystemClock.elapsedRealtime()
        val r = runCatching { core.`cancelMediaUpload`(msgId) }
    }

    /** Upload+send any media composed offline. Call AFTER catchUp. */
    suspend fun flushPendingMedia(): UInt = withContext(Dispatchers.IO) {
        keepAwake.during { runCatching { core.`flushPendingMedia`() }.getOrDefault(0u) }
    }

    // --- Expiry sweep ---

    suspend fun sweepExpiredMessages(): ULong = withContext(Dispatchers.IO) {
        val swept = core.`sweepExpiredMessages`()
        // Their decrypted files go with them (the core knows the refs, we own the cache).
        deleteExpiredMediaFiles()
        // When an expired (disappearing) message is purged, refresh the chat list
        // so its stale preview drops immediately instead of lingering until the
        // next manual reload.
        if (swept > 0uL) {
            notifyChatListDirty()
            _messagesExpired.tryEmit(Unit)
        }
        swept
    }

    /** Delete the files of media whose messages expired: the cached copy (by
     *  content hash) and our own sent copy, album items included. */
    private fun deleteExpiredMediaFiles() {
        val refs = runCatching { core.`drainExpiredMedia`() }.getOrDefault(emptyList())
        if (refs.isEmpty()) return
        val media = java.io.File(context.cacheDir, "media")
        val chunks = java.io.File(context.cacheDir, "stream_chunks")
        val ours = listOf(context.cacheDir.absolutePath, context.filesDir.absolutePath)
        fun one(o: org.json.JSONObject) {
            val hash = o.optString("hash").ifBlank { o.optString("blake3") }
            if (hash.isNotBlank()) {
                media.listFiles { f -> f.name.startsWith(hash) }?.forEach { it.delete() }
                chunks.listFiles { f -> f.name.startsWith(hash) }?.forEach { it.deleteRecursively() }
            }
            val local = o.optString("local_path")
            if (local.isNotBlank() && ours.any { local.startsWith(it) }) java.io.File(local).delete()
        }
        for (ref in refs) {
            val o = runCatching { org.json.JSONObject(ref) }.getOrNull() ?: continue
            val items = o.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) items.optJSONObject(i)?.let { one(it) } else one(o)
        }
    }

    // --- Ephemeral media ---


    // --- Notifications ---

    suspend fun startNotifications(ntfyServerUrl: String = "https://ntfy.sh") =
        withContext(Dispatchers.IO) {
            core.`startNotifications`(ntfyServerUrl)
        }

    suspend fun stopNotifications() = withContext(Dispatchers.IO) {
        core.`stopNotifications`()
    }

    // --- Lifecycle ---

    /**
     * Initialize the Rust core. Must be called after [initializeCallbacks].
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    suspend fun initialize() {
        // C2 Stage 3: no-op until the core exists (post-unlock). Guard BEFORE the
        // once-flag so the real post-unlock call from UnlockManager runs.
        if (!coreHolder.isUnlocked()) return
        if (initialized.getAndSet(true)) return
        withContext(Dispatchers.IO) {
            core.`initialize`()
            // C2 Stage 0 backfill: existing users already have a Recovery
            // Password whose public KDF material lives only inside the DB. While
            // the DB is still open (legacy passphrase), mirror it out ONCE so
            // Stage 2/3 can derive the DatabaseKey pre-open. Idempotent and
            // best-effort: a blank result (no password set) is ignored by put().
            runCatching {
                if (!recoveryKdfPublicStore.has()) {
                    recoveryKdfPublicStore.put(core.`exportRecoveryKdfPublic`())
                }
            }
        }
    }

    /**
     * Gracefully shut down the Rust core. Call when the application is
     * being terminated (e.g. in [com.alphamessenger.AlphaMessengerApp.onTerminate]).
     */
    fun shutdown() {
        // C2 Stage 3: nothing to shut down if the core was never unlocked.
        if (!coreHolder.isUnlocked()) return
        if (initialized.get()) {
            try {
                core.`shutdown`()
            } catch (_: Exception) {
                // Best-effort — the process may be dying already
            }
            initialized.set(false)
        }
    }

    // --- Utility ---

    /**
     * Parse a JSON list of relay URLs (used by the ViewModel for relay management).
     */
    fun parseRelayUrls(json: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    /**
     * Serialize a list of relay URLs to JSON (for core constructor).
     */
    fun serializeRelayUrls(urls: List<String>): String {
        return gson.toJson(urls)
    }

    companion object {
        /** A presence signal is considered live for this long after arrival. */
        const val PRESENCE_TTL_MS = 6_000L
        /** How often the pruning ticker sweeps stale presence entries. */
        const val PRESENCE_PRUNE_MS = 2_500L
        /** Events that change an existing message or say it was read: no new message. */
        private val NOT_A_NEW_MESSAGE = setOf(
            uniffi.alphamessenger.MessageType.EDIT,
            uniffi.alphamessenger.MessageType.REACTION,
            uniffi.alphamessenger.MessageType.DELETE,
            uniffi.alphamessenger.MessageType.READ_SIGNAL,
            uniffi.alphamessenger.MessageType.DISAPPEARING,
        )
    }
}

/** Minimal projection of the MediaRef JSON — we only need the content hash
 *  to derive a stable cache filename. */
private data class MediaRefLite(val hash: String?, val local_path: String? = null)

/**
 * THE single hash extraction function — used by cacheEntry, deleteMediaCacheFiles,
 * marker file operations, and the UI's streamingDiskCacheFile. Everyone MUST use
 * this; no JSONObject, no Gson, no separate hashCode() fallbacks: divergent
 * extraction made the engine and the UI derive different hashes from the
 * same MediaRef, so one side would miss cache files the other found.
 */
fun blobHashOf(refJson: String): String {
    blobHashes.get(refJson)?.let { return it }
    val h = try {
        val o = org.json.JSONObject(refJson)
        o.optString("hash", "").takeIf { it.isNotBlank() }
            ?: o.optString("blake3", "").takeIf { it.isNotBlank() }
            ?: refJson.hashCode().toString()
    } catch (_: Exception) {
        refJson.hashCode().toString()
    }
    blobHashes.put(refJson, h)
    return h
}

/** A sender's ref to a big file lists every chunk (megabytes of JSON): it is parsed once, not on every frame. */
private val blobHashes = android.util.LruCache<String, String>(512)

/** A live typing / recording signal from a peer in a chat. */
data class ChatPresence(
    val chatId: String,
    val senderId: String,
    /** "typing" or "recording". */
    val state: String,
    /** When this signal last arrived (epoch millis) — used for expiry. */
    val atMs: Long,
    /** When the sender sent it, on their clock (0 = unknown). */
    val sentMs: Long = 0L,
)

/**
 * The 32-byte MasterDataKey derived from the Recovery Password (Phase 1).
 *
 * Wraps the raw key bytes and **overwrites them with zeros on [close]** so the
 * key doesn't linger on the heap after use. Always use inside a
 * `MasterDataKey.use { ... }` block, or pass to the Keystore cache (which then
 * owns its lifecycle). Never log [bytes] or hand it to long-lived structures
 * that outlive the use-block.
 *
 * Sub-keys (DatabaseKey, BackupFileKey, …) are derived from this in Rust; on
 * the Kotlin side we only ever need the raw bytes to (a) cache in the Keystore
 * and (b) hand to FFI for container encryption.
 */
class MasterDataKey(val bytes: ByteArray) : AutoCloseable {
    @Volatile private var closed = false

    /** Read-only view of the key bytes. Throws if already closed. */
    fun get(): ByteArray {
        check(!closed) { "MasterDataKey already closed" }
        return bytes
    }

    /** Overwrite the key bytes with zeros. Idempotent. */
    override fun close() {
        if (closed) return
        bytes.fill(0)
        closed = true
    }

    companion object {
        /** Run [block] with the key and guarantee zeroization afterwards. */
        inline fun <T> use(key: MasterDataKey, block: (MasterDataKey) -> T): T =
            try { block(key) } finally { key.close() }
    }
}

/** Scheduled (held) messages per chat, for every screen: chatId → (id → send time). */
object HeldMessages {
    val byChat = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
}

/** A chat that waits in Requests: who brought us in ([from], an npub) and when. */
data class ChatRequest(val chatId: String, val from: String, val kind: String, val at: Long)
