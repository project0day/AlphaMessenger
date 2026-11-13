package com.alphamessenger.data

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import uniffi.alphamessenger.AlphaMessengerCallback
import uniffi.alphamessenger.CoreEvent
import uniffi.alphamessenger.MessageInfo
import uniffi.alphamessenger.MessageType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements [AlphaMessengerCallback] from UniFFI bindings.
 *
 * The Rust core calls these methods on an arbitrary background thread
 * managed by the JNA callback infrastructure. Each implementation emits
 * the event into a cold [SharedFlow] that [AlphaCoreRepository] collects
 * on its own [CoroutineScope], ensuring downstream consumers never touch
 * the FFI thread directly.
 *
 * **Thread safety**: every [MutableSharedFlow.tryEmit] is lock-free and
 * safe to call from any thread.
 */
@Singleton
class CallbackBridge @Inject constructor() : AlphaMessengerCallback {

    private val gson = Gson()

    // ──────────────────────────── Flows ────────────────────────────

    private val _messageReceived = MutableSharedFlow<MessageInfo>(extraBufferCapacity = 64)
    val messageReceived: SharedFlow<MessageInfo> = _messageReceived.asSharedFlow()

    // TEMP (BLOCK K verify) — batch counters for the EVENTS_BATCH log.
    private val _kBatches = java.util.concurrent.atomic.AtomicInteger(0)
    private val _kEvents = java.util.concurrent.atomic.AtomicLong(0)

    private val _deliveryUpdate = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val deliveryUpdate: SharedFlow<Pair<String, String>> = _deliveryUpdate.asSharedFlow()

    private val _syncComplete = MutableSharedFlow<ULong>(extraBufferCapacity = 8)
    val syncComplete: SharedFlow<ULong> = _syncComplete.asSharedFlow()

    private val _chatCreated = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val chatCreated: SharedFlow<Pair<String, String>> = _chatCreated.asSharedFlow()

    private val _memberAdded = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val memberAdded: SharedFlow<Pair<String, String>> = _memberAdded.asSharedFlow()

    private val _memberRemoved = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val memberRemoved: SharedFlow<Pair<String, String>> = _memberRemoved.asSharedFlow()

    private val _notificationReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val notificationReceived: SharedFlow<Unit> = _notificationReceived.asSharedFlow()

    private val _peerProfileUpdated = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits the sender_id (MLS leaf hex) whose profile we just received. */
    val peerProfileUpdated: SharedFlow<String> = _peerProfileUpdated.asSharedFlow()

    private val _chatMetadataUpdated = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits the chat_id whose name/description/avatar was just applied
     *  from an incoming `GroupMetadataUpdate`. */
    val chatMetadataUpdated: SharedFlow<String> = _chatMetadataUpdated.asSharedFlow()

    private val _chatState = MutableSharedFlow<ChatStateSignal>(extraBufferCapacity = 64)
    /** Transient typing/recording signals (and call signalling riding on them). */
    val chatState: SharedFlow<ChatStateSignal> = _chatState.asSharedFlow()

    private val _mediaProgress = MutableSharedFlow<Triple<String, Long, Long>>(extraBufferCapacity = 256)
    /** Emits (opId, downloaded, total) byte counts during a media download. */
    val mediaProgress: SharedFlow<Triple<String, Long, Long>> = _mediaProgress.asSharedFlow()

    private val _joinRequested = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 16)
    /** G-7: emits (chatId, requesterNpub, token) when someone wants to join via
     *  an invite link. The repository validates the ban list + approves. */
    val joinRequested: SharedFlow<Triple<String, String, String>> = _joinRequested.asSharedFlow()

    private val _devicesEvent = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Our phones: "inbox", "devices", "link", "history", "profile", "unlinked". */
    val devicesEvent: SharedFlow<String> = _devicesEvent.asSharedFlow()

    private val _memberLeaveRequested = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    /** Leave flow: emits (chatId, leaverNpub) when a member announced they left.
     *  An owner/admin performs the real MLS removal. */
    val memberLeaveRequested: SharedFlow<Pair<String, String>> = _memberLeaveRequested.asSharedFlow()

    // ──────────────────────────── AlphaMessengerCallback ────────────────────────────

    /**
     * Called when a new message arrives from a relay.
     *
     * @param chatId   The chat the message belongs to.
     * @param messageJson  JSON-serialised [MessageInfo] from the Rust core.
     */
    override fun `onMessageReceived`(chatId: kotlin.String, messageJson: kotlin.String) {
        // Rust serialises the inner AlphaMessage enum, NOT the FFI MessageInfo
        // dictionary — the two share no field names so Gson would return a
        // MessageInfo with every field empty (and `chatId == ""`). That made
        // ChatViewModel.startObservingEvents skip every realtime emission
        // because `message.chatId != currentChatId`. We trust the callback's
        // explicit chatId/senderId arguments and emit a minimal MessageInfo —
        // ChatViewModel only uses chatId to decide whether to reload from the
        // DB, where the full row already lives.
        // The JSON is the serde-tagged AlphaMessage ({"type":"edit", …}). Parse
        // just the discriminator so downstream (the notification service) can
        // tell a real new message from an edit/reaction/delete/read-signal —
        // those must update the UI but must NOT push or show "typing".
        val parsedType = try {
            when (org.json.JSONObject(messageJson).optString("type")) {
                "edit" -> MessageType.EDIT
                "reaction" -> MessageType.REACTION
                "delete" -> MessageType.DELETE
                "read_signal" -> MessageType.READ_SIGNAL
                "media" -> MessageType.MEDIA
                "reply" -> MessageType.REPLY
                "disappearing" -> MessageType.DISAPPEARING
                else -> MessageType.TEXT
            }
        } catch (_: Throwable) { MessageType.TEXT }
        // View-once plaintext is NEVER persisted (core rule D3) — it only exists
        // in this callback JSON. Stash it in memory so the fullscreen viewer can
        // reveal it once. Keyed by the message id (same UUID as the DB row).
        try {
            val obj = org.json.JSONObject(messageJson)
            if (obj.optJSONObject("expiry")?.optString("mode") == "view_once") {
                val id = obj.optString("id")
                android.util.Log.i("ViewOnceCache", "BRIDGE view_once recv id=$id type=$parsedType chat=$chatId")
                when (parsedType) {
                    MessageType.MEDIA -> ViewOnceCache.put(
                        id, obj.optString("caption").takeIf { it.isNotBlank() },
                        obj.optString("media_ref").takeIf { it.isNotBlank() }, "image",
                    )
                    else -> ViewOnceCache.put(id, obj.optString("body"), null, null)
                }
            }
        } catch (_: Throwable) { /* best effort */ }
        val placeholder = try {
            MessageInfo(
                id = "",
                chatId = chatId,
                senderId = "",
                msgType = parsedType,
                content = null,
                replyTo = null,
                mediaRef = null,
                ttlSeconds = null as kotlin.UInt?,
                expiresAt = null,
                expiryPolicy = null,
                readAt = null,
                consumed = false,
                createdAt = "",
                editedAt = null,
                isDeleted = false,
                deliveredAt = null,
            )
        } catch (_: Throwable) {
            android.util.Log.w("CallbackBridge", "Failed to construct MessageInfo placeholder for chat=$chatId")
            return
        }
        android.util.Log.d("CallbackBridge", "onMessageReceived: chatId=$chatId json.len=${messageJson.length}")
        _messageReceived.tryEmit(placeholder)
    }

    /**
     * Called when the delivery status of a previously sent message changes.
     *
     * @param messageId  The message whose status changed.
     * @param status     New status string (e.g. "sent", "delivered", "read").
     */
    override fun `onDeliveryUpdate`(messageId: kotlin.String, status: kotlin.String) {
        _deliveryUpdate.tryEmit(messageId to status)
    }

    /**
     * Called when a sync batch completes.
     *
     * @param newMessageCount  Number of new messages received during this sync cycle.
     */
    override fun `onSyncComplete`(newMessageCount: kotlin.ULong) {
        _syncComplete.tryEmit(newMessageCount)
    }

    /**
     * Called when a new chat is created (either by us or by a peer's welcome message).
     *
     * @param chatId    The newly created chat ID.
     * @param chatType  Type of chat ("direct" or "group").
     */
    override fun `onChatCreated`(chatId: kotlin.String, chatType: kotlin.String) {
        _chatCreated.tryEmit(chatId to chatType)
    }

    override fun `onDevicesEvent`(kind: kotlin.String) {
        _devicesEvent.tryEmit(kind)
    }

    /**
     * Called when a member is added to a group chat.
     *
     * @param chatId    The group chat ID.
     * @param memberId  The npub/key-package of the added member.
     */
    override fun `onMemberAdded`(chatId: kotlin.String, memberId: kotlin.String) {
        _memberAdded.tryEmit(chatId to memberId)
    }

    /**
     * Called when a member is removed from a group chat.
     *
     * @param chatId    The group chat ID.
     * @param memberId  The npub/key-package of the removed member.
     */
    override fun `onMemberRemoved`(chatId: kotlin.String, memberId: kotlin.String) {
        _memberRemoved.tryEmit(chatId to memberId)
    }

    /**
     * Called when a push notification is received via the ntfy channel.
     */
    override fun `onNotificationReceived`() {
        _notificationReceived.tryEmit(Unit)
    }

    /**
     * Called when an `AlphaMessage::ProfileUpdate` arrives for a peer.
     * UI consumers re-read `getPeerProfile(senderId)` to refresh bubble
     * headers / chat list rows / chat top bar.
     */
    override fun `onPeerProfileUpdated`(senderId: kotlin.String) {
        android.util.Log.d("CallbackBridge", "onPeerProfileUpdated: $senderId")
        _peerProfileUpdated.tryEmit(senderId)
    }

    override fun `onChatMetadataUpdated`(chatId: kotlin.String) {
        android.util.Log.d("CallbackBridge", "onChatMetadataUpdated: $chatId")
        _chatMetadataUpdated.tryEmit(chatId)
    }

    override fun `onChatState`(chatId: kotlin.String, senderId: kotlin.String, state: kotlin.String, thread: kotlin.String, ageMs: kotlin.ULong) {
        val age = ageMs.toLong()
        // The age was taken from the raw send time against our clock a moment ago, so this
        // gives that send time back, unaffected by clock skew. 0: their clock is ahead.
        val sent = if (age > 0) System.currentTimeMillis() - age else 0L
        _chatState.tryEmit(ChatStateSignal(chatId, senderId, state, thread, age, sent))
    }

    override fun `onMediaProgress`(opId: kotlin.String, downloaded: kotlin.ULong, total: kotlin.ULong) {
        // Legacy per-event path — kept for interface completeness. The Rust core
        // now routes media progress through the Unified Event Dispatcher, so this
        // arrives via [onEventsBatch] instead (coalesced). Harmless if ever called.
        _mediaProgress.tryEmit(Triple(opId, downloaded.toLong(), total.toLong()))
    }

    /**
     * BLOCK K — Unified Event Dispatcher. ONE coalesced batch of high-frequency
     * events, replacing the per-event FFI flood. We ONLY fan each event out to
     * the SAME SharedFlows as before — Rust already deduped/coalesced, so the
     * Kotlin side never deduplicates (design principle #3). Downstream
     * (TransferEngine, ChatViewModel) is unchanged.
     */
    override fun `onEventsBatch`(events: List<CoreEvent>) {
        // TEMP (BLOCK K verify): prove the flood is coalesced. Each line = ONE
        // FFI crossing carrying `size` events, ~40/s max instead of ~300 raw
        // on_media_progress calls. Remove after we confirm on device.
        run {
            val n = _kBatches.incrementAndGet()
            val ev = _kEvents.addAndGet(events.size.toLong())
            android.util.Log.e("EVENTS_BATCH", "size=${events.size} batches=$n totalEvents=$ev")
        }
        for (e in events) {
            when (e) {
                is CoreEvent.MediaProgress ->
                    _mediaProgress.tryEmit(Triple(e.opId, e.downloaded.toLong(), e.total.toLong()))
                is CoreEvent.ChatState ->
                    _chatState.tryEmit(ChatStateSignal(e.chatId, e.senderId, e.state))
                is CoreEvent.DeliveryUpdate ->
                    _deliveryUpdate.tryEmit(e.messageId to e.status)
            }
        }
    }

    override fun `onJoinRequested`(chatId: kotlin.String, requesterNpub: kotlin.String, token: kotlin.String) {
        android.util.Log.i("CallbackBridge", "onJoinRequested chat=$chatId npub=$requesterNpub")
        _joinRequested.tryEmit(Triple(chatId, requesterNpub, token))
    }

    override fun `onMemberLeaveRequested`(chatId: kotlin.String, leaverNpub: kotlin.String) {
        android.util.Log.i("CallbackBridge", "onMemberLeaveRequested chat=$chatId npub=$leaverNpub")
        _memberLeaveRequested.tryEmit(chatId to leaverNpub)
    }

    private val _relayStatusChanged = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 32)
    /** Emits (relayUrl, "connected"|"disconnected") — pure UI indicator signal.
     *  NO transport side effects hang off this (the relay pool self-heals on
     *  its own with cooldowns; see the core's realtime listener). */
    val relayStatusChanged: SharedFlow<Pair<String, String>> = _relayStatusChanged.asSharedFlow()

    override fun `onRelayStatusChange`(relayUrl: kotlin.String, status: kotlin.String) {
        _relayStatusChanged.tryEmit(relayUrl to status)
    }

    private val _syncState = MutableStateFlow<SyncState?>(null)
    /** The receiving side's progress as the core last reported it; null until the first report. */
    val syncState: StateFlow<SyncState?> = _syncState.asStateFlow()

    override fun `onSyncState`(connected: kotlin.UInt, relays: kotlin.UInt, catchingUp: kotlin.Boolean) {
        _syncState.value = SyncState(connected.toInt(), relays.toInt(), catchingUp)
    }
}

/** Live relays out of the enabled ones, and whether a catch-up is running. */
data class SyncState(val connected: Int, val relays: Int, val catchingUp: Boolean)

/**
 * A peer's transient activity: `state` is "typing", "recording" or "paused"
 * (or a call signal, "call:…"). [thread] is the channel post whose discussion
 * it happens in — empty for the chat itself.
 */
data class ChatStateSignal(
    val chatId: String,
    val senderId: String,
    val state: String,
    val thread: String = "",
    /** How long ago the sender published it (a late one came with catch-up). */
    val ageMs: Long = 0,
    /** When the sender sent it, on their clock: our now minus the age (0 = unknown). */
    val sentMs: Long = 0,
)
