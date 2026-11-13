package com.alphamessenger.ui.chatlist

import com.alphamessenger.util.isGroupFull
import com.alphamessenger.ui.chat.toMessageUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.alphamessenger.data.AlphaCoreRepository
import uniffi.alphamessenger.ChatInfo
import uniffi.alphamessenger.MessengerException
import javax.inject.Inject
import com.alphamessenger.R

// --- UI state — matches the web demo's chatlist state ---

/** Minimal peer profile snapshot per chat — what ConversationRow needs to
 *  render real name + avatar instead of the npub-based gradient placeholder. */
data class ChatPeerSnapshot(
    val displayName: String,
    val avatarPath: String,
    /** Peer's bech32 Nostr public key (full `npub1...`). Empty if unknown. */
    val npub: String = "",
    /** Content hash of the avatar — used to detect a REAL change (so an
     *  identical re-broadcast doesn't trigger a needless image reload/flicker). */
    val avatarHash: String = "",
    /** A secret chat: no presence (it would tell when we are active), no calls. */
    val secret: Boolean = false,
)

data class ChatListState(
    val chats: List<ChatInfo> = emptyList(),
    val groups: List<ChatInfo> = emptyList(),
    /** Groups that are channels (shown in their own tab, not under Groups). */
    val channelIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedTab: ChatListViewModel.BottomTab = ChatListViewModel.BottomTab.CHATS,
    val isFabExpanded: Boolean = false,
    val showSearch: Boolean = false,
    val isLoading: Boolean = false,
    /** The lists have been read at least once: until then "empty" means "not known yet". */
    val chatsLoaded: Boolean = false,
    val error: String? = null,
    // New-chat dialog fields
    val showNewChatDialog: Boolean = false,
    val newChatPeerKey: String = "",
    val groupName: String = "",
    val isGroupCreation: Boolean = false,
    val isAddingContact: Boolean = false,
    /** chat.id → cached peer profile (only direct chats with at least one
     *  inbound message). Empty entry = no peer profile resolved yet. */
    val peerByChat: Map<String, ChatPeerSnapshot> = emptyMap(),
    /** chat.id → live activity label (strings.get(R.string.typing_label), "N is recording audio…").
     *  When present, the row shows this in place of the last-message preview. */
    val presenceByChat: Map<String, String> = emptyMap(),
    /** chat.id → peer hex pubkey (direct chats only). Reverse-keyed for the
     *  presence status dot. */
    val peerHexByChat: Map<String, String> = emptyMap(),
    /** chat.id → online presence status ("online"/"away"/"busy"/"offline")
     *  for the avatar status dot. */
    val statusByChat: Map<String, String> = emptyMap(),
    /** chat.id → peer's custom indicator colour (ARGB int; 0 = none). Overrides
     *  the presence-derived dot colour. */
    val statusColorByChat: Map<String, Int> = emptyMap(),
    /** chat.id → peer's custom status text ("" = none). */
    val customStatusByChat: Map<String, String> = emptyMap(),
    /** chat.id → counter bumped on each ProfileUpdate from that peer. Used as an
     *  image cache-bust key: the avatar/cover file path is reused (same name,
     *  new bytes) so this is what forces a re-decode/reload in the UI. */
    val profileTickByChat: Map<String, Int> = emptyMap(),
    /** chat.id → unsent draft text. When present, the row shows "Draft: …". */
    val draftByChat: Map<String, String> = emptyMap(),
    /** What the search looks through: everything, a kind of chat, or one kind of attachment. */
    val searchKind: SearchKind = SearchKind.ALL,
    /** Only messages by this person (null = anyone). */
    val searchFrom: SearchPerson? = null,
    /** Only messages written within these days, inclusive (null = any time). */
    val searchDays: Pair<java.time.LocalDate, java.time.LocalDate>? = null,
    /** Messages matching the search query, from the core's index, newest first. */
    val foundMessages: List<FoundMessage> = emptyList(),
    /** A page of [foundMessages] is on its way. */
    val foundLoading: Boolean = false,
    /** Every match has been loaded. */
    val foundEnd: Boolean = true,
    /** Direct chats whose 1:1 peer is blocked (G-11) — preview/unread/presence
     *  are suppressed for these rows. */
    val blockedChatIds: Set<String> = emptySet(),
    /** Chats from people not in our contacts, waiting for an answer (newest first). */
    val requests: List<ChatInfo> = emptyList(),
    /** Who brought each request (npub) — for "Block". */
    val requestFrom: Map<String, String> = emptyMap(),
    /** Direct chats with my contacts: chat id → npub. */
    val contactChats: Map<String, String> = emptyMap(),
)

// --- ViewModel — production FFI integration ---

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val strings: com.alphamessenger.util.Strings,
    private val networkMonitor: com.alphamessenger.data.NetworkMonitor,
    val repository: AlphaCoreRepository
) : ViewModel() {

    /** What the list header says instead of the logo; null — nothing to say. */
    enum class SyncStatus { WAITING_NETWORK, CONNECTING, UPDATING }

    /** From the device's network and the core's own report. A state shorter
     *  than [STATUS_SHOW_AFTER_MS] never shows, so a quick reconnect or a short
     *  catch-up doesn't flash the header. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val syncStatus: StateFlow<SyncStatus?> = kotlinx.coroutines.flow.combine(networkMonitor.online, repository.syncState) { online, s ->
        when {
            !online -> SyncStatus.WAITING_NETWORK
            s == null || s.relays == 0 -> null
            s.connected == 0 -> SyncStatus.CONNECTING
            s.catchingUp -> SyncStatus.UPDATING
            else -> null
        }
    }
        .distinctUntilChanged()
        .transformLatest { status ->
            if (status != null) kotlinx.coroutines.delay(STATUS_SHOW_AFTER_MS)
            emit(status)
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), null)


    /** Members of a group (its profile sheet's line). */
    suspend fun memberCount(chatId: String): Int =
        runCatching { repository.chatMemberCount(chatId) }.getOrDefault(0)


    // --- Tab enum ---
    enum class BottomTab { CHATS, GROUPS, CHANNELS, STORIES, CALLS }

    init {
        // Ghost mode: read the core's value, then apply taps on the top-bar ghost.
        // Our own "last seen" rule decides whether we see others' times.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { repository.privacySettings() } }
        viewModelScope.launch {
            runCatching { repository.ghostMode() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { repository.refreshHeld() } }
            com.alphamessenger.ui.components.GhostModeState.requests.collect { v ->
                runCatching { repository.setGhostMode(v) }
            }
        }
    }

    private val _uiState = MutableStateFlow(ChatListState())
    val uiState: StateFlow<ChatListState> = _uiState.asStateFlow()

    init {
        // Make sure the Saved Messages row exists. First call
        // inserts it pinned at the top; subsequent calls just return the
        // existing row. Cheap and idempotent.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.ensureSavedMessagesChat() }
            loadChats()
        }
        loadChats()
        // React to realtime callbacks so the chat list reflects new chats
        // (Welcome auto-join) and last-message previews without the user
        // having to leave and re-enter the screen.
        viewModelScope.launch {
            repository.chatCreated.collect { loadChats() }
        }
        viewModelScope.launch {
            repository.messageReceived.collect { loadChats() }
        }
        // Any pin/mute/archive/delete/markRead from anywhere in the app
        // re-emits here, so badges and ordering refresh immediately.
        viewModelScope.launch {
            repository.chatListDirty.collect { loadChats() }
        }
        // Peer profile updates → refresh the row's avatar+name without
        // doing the full N-chats-N-messages walk.
        viewModelScope.launch {
            repository.peerProfileUpdated.collect { senderId ->
                refreshPeerProfileFor(senderId)
            }
        }
        // Live typing/recording → per-row activity labels.
        viewModelScope.launch {
            repository.chatPresence.collect { map ->
                val labels = HashMap<String, String>(map.size)
                val blocked = _uiState.value.blockedChatIds
                for ((chatId, p) in map) {
                    if (chatId in blocked) continue   // G-11: hide blocked peer's typing
                    val verb = if (p.state == "recording") strings.get(R.string.recording_label) else strings.get(R.string.typing_label)
                    val isGroup = _uiState.value.groups.any { it.id == chatId }
                    labels[chatId] = if (isGroup) {
                        val name = runCatching { repository.getPeerProfile(p.senderId).displayName }
                            .getOrNull()?.takeIf { it.isNotBlank() } ?: strings.get(R.string.participant_fallback)
                        "$name $verb"
                    } else verb
                }
                _uiState.update { it.copy(presenceByChat = labels) }
            }
        }
        // Online presence (kind 10777) → avatar status dots. AUTHORITATIVE pull:
        // a delta stream drops the very first "online" if it arrives before the
        // peer-hex map is built (and poll_changes won't re-send the same state),
        // so instead we query the engine's current state for every peer. Driven
        // both by change signals (immediacy) and a steady timer (correctness).
        viewModelScope.launch {
            repository.presenceChanged.collect { refreshPresence() }
        }
        // BATTERY: the push signal above (presenceChanged) drives immediacy; this
        // timer is only a correctness backstop for the rare first-"online" race.
        // It's 30s on purpose (not faster): the VM outlives the screen, so a fast
        // timer would keep firing while backgrounded. It's a local engine read,
        // no network, so the slower backstop costs nothing in freshness.
        viewModelScope.launch {
            while (true) {
                refreshPresence()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    // Rate-limit fallback ProfileRequests: chatId → last-sent epoch ms.
    private val lastProfileReq = HashMap<String, Long>()

    /** Fallback profile pull when opening a peer's profile — only if we haven't
     *  asked this chat in the last 60s. Auto ProfileUpdate remains the primary
     *  path; this just wakes on-demand sync so opening a profile shows current
     *  name/avatar/cover without needing to exchange messages. */
    fun requestPeerProfileIfStale(chatId: String) {
        val now = System.currentTimeMillis()
        val last = lastProfileReq[chatId] ?: 0L
        if (now - last < 60_000L) return
        lastProfileReq[chatId] = now
        viewModelScope.launch { repository.requestPeerProfile(chatId) }
    }

    // Last resolved cover path per chat — lets the sheet show it INSTANTLY on
    // reopen instead of flashing the gradient while the async lookup runs.
    private val coverCache = HashMap<String, String>()
    fun cachedCover(chatId: String): String = coverCache[chatId] ?: ""

    /** Resolve a direct chat peer's cover/banner local path ("" = none). */
    /** 1:1: may we call the peer (voice / video)? Their privacy rules, from their profile. */
    suspend fun peerCallRights(chatId: String): Pair<Boolean, Boolean> {
        val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
        val sid = runCatching { repository.resolvePeerSenderId(chatId, ownNpub) }.getOrNull().orEmpty()
        return if (sid.isBlank()) true to true else repository.peerCallRights(sid)
    }

    /** [peerCallRights] for several direct chats at once (the "New call" list). */
    suspend fun callRights(chatIds: List<String>): Map<String, Pair<Boolean, Boolean>> {
        val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
        return chatIds.associateWith { id ->
            val sid = runCatching { repository.resolvePeerSenderId(id, ownNpub) }.getOrNull().orEmpty()
            if (sid.isBlank()) true to true else repository.peerCallRights(sid)
        }
    }

    /** The peer's video avatar ("" = none). */
    suspend fun peerBio(chatId: String): String {
        val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
        val sid = runCatching { repository.resolvePeerSenderId(chatId, ownNpub) }.getOrNull().orEmpty()
        return if (sid.isBlank()) "" else runCatching { repository.getPeerProfile(sid).bio }.getOrDefault("")
    }

    suspend fun peerVideoPath(chatId: String): String {
        val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
        val sid = runCatching { repository.resolvePeerSenderId(chatId, ownNpub) }.getOrNull().orEmpty()
        return if (sid.isBlank()) "" else repository.peerAvatarVideo(sid)
    }

    suspend fun peerCoverPath(chatId: String): String {
        val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
        val sid = runCatching { repository.resolvePeerSenderId(chatId, ownNpub) }.getOrNull().orEmpty()
        val path = if (sid.isBlank()) "" else runCatching { repository.getPeerCoverPath(sid) }.getOrDefault("")
        coverCache[chatId] = path
        return path
    }

    /** Read the engine's current presence for every known peer and publish the
     *  full status/colour/custom-status maps. Non-delta → always correct. */
    private suspend fun refreshPresence() {
        val hexByChat = _uiState.value.peerHexByChat
        if (hexByChat.isEmpty()) return
        val st = HashMap<String, String>(hexByChat.size)
        val col = HashMap<String, Int>(hexByChat.size)
        val cust = HashMap<String, String>(hexByChat.size)
        for ((chatId, hex) in hexByChat) {
            st[chatId] = runCatching { repository.presenceStatus(hex) }.getOrDefault("offline")
            col[chatId] = runCatching { repository.presenceColor(hex) }.getOrDefault(0L).toInt()
            cust[chatId] = runCatching { repository.presenceCustomStatus(hex) }.getOrDefault("")
        }
        _uiState.update {
            it.copy(statusByChat = st, statusColorByChat = col, customStatusByChat = cust)
        }
    }

    /** Lightweight: lookup the updated profile and patch peerByChat for any
     *  chat where this senderId is the resolved peer. */
    private fun refreshPeerProfileFor(senderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val freshProfile = runCatching { repository.getPeerProfile(senderId) }
                .getOrNull() ?: return@launch
            // Walk the current map: any chat whose first inbound senderId
            // matches gets the new snapshot. Cheap because peerByChat is
            // already keyed by chat.id; we just need a reverse check.
            val currentChats = _uiState.value.chats
            val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
            val patched = _uiState.value.peerByChat.toMutableMap()
            val ticks = _uiState.value.profileTickByChat.toMutableMap()
            for (chat in currentChats) {
                if (chat.id == "saved_messages_local") continue
                val peerId = repository.resolvePeerSenderId(chat.id, ownNpub)
                if (peerId == senderId) {
                    val existing = patched[chat.id]
                    // Only react to a REAL change — an identical re-broadcast
                    // (e.g. our fallback ProfileRequest reply) must NOT bump the
                    // tick, or the avatar/cover would needlessly reload = flicker.
                    val changed = existing == null ||
                        existing.displayName != freshProfile.displayName ||
                        existing.avatarPath != freshProfile.avatarPath ||
                        existing.avatarHash != freshProfile.avatarHash
                    if (!changed) continue
                    val npub = (existing?.npub ?: "").ifBlank {
                        runCatching { repository.getPeerNpubForChat(chat.id) }
                            .getOrNull().orEmpty()
                    }
                    patched[chat.id] = ChatPeerSnapshot(
                        freshProfile.displayName, freshProfile.avatarPath, npub, freshProfile.avatarHash,
                    )
                    // Real change → bump cache-bust tick so avatar/cover reload
                    // even though the file path is unchanged.
                    ticks[chat.id] = (ticks[chat.id] ?: 0) + 1
                }
            }
            _uiState.update { it.copy(peerByChat = patched, profileTickByChat = ticks) }
        }
    }

    // --- Load chats from FFI on IO dispatcher ---

    /** Out of my contacts (the chat stays) or back in. */
    fun setContact(chat: ChatInfo, contact: Boolean) {
        val npub = _uiState.value.contactChats[chat.id] ?: _uiState.value.peerByChat[chat.id]?.npub
        if (npub.isNullOrBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.setContact(npub, contact)
            loadChats()
        }
    }

    /** A request answered "Delete" for each one still waiting (nobody is told). */
    fun declineAllRequests() {
        val ids = _uiState.value.requests.map { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) repository.declineChatRequest(id, block = false)
            loadChats()
        }
    }

    fun loadChats() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val allChats = repository.listChats()
                // Order both lists by recent activity (newest first), pinned on top
                // — so a chat/GROUP that just received a message jumps to the top.
                // lastMessageAt is RFC3339, so string compare == chronological.
                val recency = compareByDescending<ChatInfo> { it.pinnedAt > 0u }
                    .thenByDescending { it.lastMessageAt.ifBlank { it.createdAt } }
                val directChats = allChats.filter { it.chatType != "group" }.sortedWith(recency)
                var channelIds = allChats.filter { it.chatType == "group" && repository.channelCfg(it.id).also { cfg -> com.alphamessenger.ui.channel.ChannelCfgCache.put(it.id, cfg) }.on }.map { it.id }.toSet()
                // A comment is never a chat's last message (the core keeps it in its post's discussion).
                val groupChats = allChats.filter { it.chatType == "group" }.sortedWith(recency)
                val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
                // Requests: left out of every list above; listed on their own.
                val pending = repository.chatRequests()
                val requestChats = if (pending.isEmpty()) emptyList() else {
                    val byId = repository.listChats(includeRequests = true).associateBy { it.id }
                    pending.mapNotNull { byId[it.chatId] }
                }
                // A channel waiting in Requests is still shown as a channel.
                channelIds = channelIds + requestChats.filter { it.chatType == "group" && repository.channelCfg(it.id).on }.map { it.id }
                val peerByChat = resolvePeerProfiles(directChats + requestChats.filter { it.chatType != "group" }, ownNpub)
                // Every chat is listed: a second chat with the same person is never
                // hidden behind the first (mutual-scan duplicates are resolved in the core).
                // G-11: a blocked 1:1 peer must show NOTHING in the list — blank the
                // preview + zero the unread badge (groups are never affected).
                val blockedChatIds = peerByChat.filter { (_, p) ->
                    p.npub.isNotBlank() && repository.isUserBlocked(p.npub)
                }.keys
                val timerMark = com.alphamessenger.ui.chat.TIMER_MARK
                val directChatsClean = directChats.map { c ->
                    when {
                        c.id in blockedChatIds ->
                            c.copy(lastMessagePreview = "", lastMessageKind = "", unreadCount = 0u, unreadOverride = false)
                        // Timer-control message → show a friendly preview, not the raw marker.
                        c.lastMessagePreview.startsWith(com.alphamessenger.ui.chat.SECRET_MARK) ->
                            c.copy(lastMessagePreview = strings.get(R.string.secret_invite_in), lastMessageKind = "")
                        c.lastMessagePreview.startsWith(timerMark) ->
                            c.copy(lastMessagePreview = strings.get(R.string.preview_disappearing_updated), lastMessageKind = "")
                        c.lastMessagePreview.startsWith(com.alphamessenger.ui.chat.STORY_MARK) ->
                            c.copy(lastMessagePreview = strings.get(R.string.stories_preview_reply,
                                com.alphamessenger.ui.chat.parseStoryReply(c.lastMessagePreview)?.second.orEmpty()), lastMessageKind = "")
                        else -> c
                    }
                }
                // Banned: nothing of the chat is shown, only that we are banned.
                val bannedText = strings.get(R.string.ban_list_line)
                val groupsShown = groupChats.map { c ->
                    val gone = repository.chatBan(c.id)
                    if (gone == null) {
                        // A group event (left, banned): worded in this phone's language.
                        val at = c.lastMessagePreview.indexOf(com.alphamessenger.ui.chat.NOTICE_MARK)
                        val worded = if (at < 0) null else com.alphamessenger.ui.chat.noticeText(c.lastMessagePreview.substring(at), strings)
                        if (worded == null) c else c.copy(lastMessagePreview = worded, lastMessageKind = "")
                    } else {
                        com.alphamessenger.ui.chat.ConversationSnapshotCache.clear(c.id)
                        val line = if (!gone.disbanded) bannedText
                            else strings.get(if (c.id in channelIds) R.string.disband_title_channel else R.string.disband_title_group)
                        c.copy(lastMessagePreview = line, lastMessageKind = "", avatarPath = "", unreadCount = 0u, unreadOverride = false)
                    }
                }
                val drafts = runCatching { repository.allDrafts() }.getOrDefault(emptyMap())
                // Reverse-key peer hex per direct chat for the presence dot.
                val peerHexByChat = HashMap<String, String>(directChatsClean.size)
                for (c in directChatsClean) {
                    if (c.id == "saved_messages_local") continue
                    // Peer's raw Nostr pubkey hex — the SAME key presence is
                    // authored under (NOT the MLS sender_id from resolvePeerSenderId).
                    val hex = runCatching { repository.peerHexForChat(c.id) }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                    peerHexByChat[c.id] = hex
                }
                _uiState.update {
                    it.copy(
                        chats = directChatsClean,
                        groups = groupsShown,
                        channelIds = channelIds,
                        isLoading = false,
                        chatsLoaded = true,
                        peerByChat = peerByChat,
                        peerHexByChat = peerHexByChat,
                        blockedChatIds = blockedChatIds,
                        draftByChat = drafts,
                        requests = requestChats,
                        requestFrom = pending.associate { it.chatId to it.from },
                        contactChats = repository.contacts(),
                    )
                }
                // Peer-hex map just changed → refresh presence immediately so
                // dots don't wait for the next timer tick.
                refreshPresence()
            } catch (e: MessengerException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        chatsLoaded = true,
                        error = strings.get(R.string.chats_load_error, e.message ?: "")
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        chatsLoaded = true,
                        error = strings.get(R.string.error_with_reason, e.message ?: "")
                    )
                }
            }
        }
    }

    /**
     * For each direct chat, find the first inbound `senderId` (the peer's
     * MLS leaf hex) and look up their cached profile in the `contacts`
     * table. Saved Messages is skipped — it's notes-to-self.
     */
    private suspend fun resolvePeerProfiles(
        directChats: List<ChatInfo>,
        ownNpub: String,
    ): Map<String, ChatPeerSnapshot> {
        val out = mutableMapOf<String, ChatPeerSnapshot>()
        val secrets = repository.secretChatIds()
        for (chat in directChats) {
            if (chat.id == "saved_messages_local") continue
            // A secret chat is named after the peer of the chat it came from.
            if (chat.id in secrets) {
                val origin = repository.secretChatOrigin(chat.id)
                val peer = if (origin.isEmpty()) "" else repository.resolvePeerSenderId(origin, ownNpub)
                val prof = peer.takeIf { it.isNotBlank() }?.let { runCatching { repository.getPeerProfile(it) }.getOrNull() }
                out[chat.id] = ChatPeerSnapshot(
                    strings.get(R.string.secret_name, prof?.displayName.orEmpty().ifBlank { strings.get(R.string.private_chat) }),
                    prof?.avatarPath.orEmpty(), "", prof?.avatarHash.orEmpty(), secret = true,
                )
                continue
            }
            val peerId = repository.resolvePeerSenderId(chat.id, ownNpub)
            if (peerId.isBlank()) continue
            val prof = runCatching { repository.getPeerProfile(peerId) }.getOrNull()
            val npub = runCatching { repository.getPeerNpubForChat(chat.id) }.getOrNull().orEmpty()
            if (prof != null && (prof.displayName.isNotBlank() || prof.avatarPath.isNotBlank() || npub.isNotBlank())) {
                out[chat.id] = ChatPeerSnapshot(prof.displayName, prof.avatarPath, npub, prof.avatarHash)
            } else if (npub.isNotBlank()) {
                out[chat.id] = ChatPeerSnapshot("", "", npub)
            }
        }
        return out
    }

    // --- Search ---

    private val messageLabels = com.alphamessenger.ui.chat.MessageUiLabels(strings)
    private var searchJob: kotlinx.coroutines.Job? = null
    private val senderNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun setSearchFrom(person: SearchPerson?) {
        _uiState.update { it.copy(searchFrom = person, foundMessages = emptyList()) }
        updateSearchQuery(_uiState.value.searchQuery)
    }

    fun setSearchDays(days: Pair<java.time.LocalDate, java.time.LocalDate>?) {
        _uiState.update { it.copy(searchDays = days, foundMessages = emptyList()) }
        updateSearchQuery(_uiState.value.searchQuery)
    }

    /** People whose messages the search can narrow to: me, then my 1:1 peers. */
    fun searchPeople(): List<SearchPerson> {
        val st = _uiState.value
        return st.chats.filter { it.chatType == "direct" && it.id != "saved_messages_local" && !com.alphamessenger.assistant.isAssistantChat(it.id) }
            .sortedByDescending { it.lastMessageAt.ifBlank { it.createdAt } }
            .mapNotNull { c ->
                val hex = st.peerHexByChat[c.id]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val peer = st.peerByChat[c.id]
                SearchPerson(hex, peer?.displayName?.takeIf { it.isNotBlank() } ?: c.name.orEmpty(), peer?.avatarPath.orEmpty(), c.id)
            }
            .distinctBy { it.key }
    }

    fun setSearchKind(kind: SearchKind) {
        if (_uiState.value.searchKind == kind) return
        _uiState.update { it.copy(searchKind = kind, foundMessages = emptyList()) }
        updateSearchQuery(_uiState.value.searchQuery)
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank() && !_uiState.value.listsWithoutQuery()) {
            _uiState.update { it.copy(foundMessages = emptyList(), foundLoading = false, foundEnd = true) }
            return
        }
        _uiState.update { it.copy(foundLoading = true) }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            val (page, end) = foundPage(query, 0)
            _uiState.update { it.copy(foundMessages = page, foundLoading = false, foundEnd = end) }
        }
    }

    /** The next page of found messages, when the list nears its end. */
    fun loadMoreFound() {
        val st = _uiState.value
        if (st.foundLoading || st.foundEnd || st.searchQuery.isBlank() || searchJob?.isActive == true) return
        val query = st.searchQuery
        if (query.isBlank() && !st.listsWithoutQuery()) return
        _uiState.update { it.copy(foundLoading = true) }
        searchJob = viewModelScope.launch {
            val (page, end) = foundPage(query, foundOffset)
            _uiState.update { cur ->
                if (cur.searchQuery != query) cur
                else cur.copy(foundMessages = cur.foundMessages + page, foundLoading = false, foundEnd = end)
            }
        }
    }

    private var foundOffset = 0

    private suspend fun foundPage(query: String, offset: Int): Pair<List<FoundMessage>, Boolean> {
        val st = _uiState.value
        val kind = st.searchKind
        val q = query.trim()
        // A voice note has no words: the query picks it by its chat or its author.
        val byName = (kind == SearchKind.VOICE || kind == SearchKind.ROUND) && q.isNotEmpty()
        val hits = runCatching {
            repository.searchMessages(
                query = if (byName) "" else q,
                kind = kind.core,
                sender = st.searchFrom?.key,
                since = st.searchDays?.first?.let { com.alphamessenger.ui.chat.text.SearchMatch.dayStart(it) },
                until = st.searchDays?.second?.plusDays(1)?.let { com.alphamessenger.ui.chat.text.SearchMatch.dayStart(it) },
                limit = FOUND_PAGE,
                offset = offset,
            )
        }.getOrDefault(emptyList())
        foundOffset = offset + hits.size
        // Chats waiting in Requests show nowhere else, the search included.
        val chats = (st.chats + st.groups).associateBy { it.id }
        val match = com.alphamessenger.ui.chat.text.SearchMatch
        val found = hits.mapNotNull { h ->
            val chat = chats[h.chatId] ?: return@mapNotNull null
            val text = match.visible(h.content)
            val sender = if (h.senderId == "self") "" else senderNames.getOrPut(h.senderId) {
                runCatching { repository.getPeerProfile(h.senderId).displayName }.getOrDefault("")
            }
            val ok = when {
                q.isEmpty() -> true
                byName -> match.matches(st.peerByChat[h.chatId]?.displayName.orEmpty() + " " + chat.name.orEmpty() + " " + sender, q)
                else -> match.matches(text + " " + h.mediaName, q)
            }
            if (!ok) return@mapNotNull null
            FoundMessage(h.messageId, h.chatId, h.senderId == "self", sender, text, h.createdAt, h.mediaKind, h.mediaName)
        }.let { list ->
            // Photos, videos and links are drawn as tiles and cards: they need the whole message.
            val drawn = list.filter { it.kind == "media" || it.kind == "link" }
            if (drawn.isEmpty()) list else {
                val own = runCatching { repository.ownNpub() }.getOrDefault("")
                val full = repository.messagesByIds(drawn.map { it.id })
                    .associate { it.id to it.toMessageUi(own, messageLabels) }
                list.map { f -> full[f.id]?.let { f.copy(ui = it) } ?: f }
            }
        }
        return found to (hits.size < FOUND_PAGE)
    }

    // --- Tab selection ---

    fun selectTab(tab: BottomTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    // --- Search toggle ---

    fun toggleSearch() {
        _uiState.update {
            if (it.showSearch) {
                it.copy(showSearch = false, searchQuery = "")
            } else {
                it.copy(showSearch = true)
            }
        }
    }

    fun openSearch() {
        _uiState.update { it.copy(showSearch = true) }
    }

    fun hideSearch() {
        _uiState.update { it.copy(showSearch = false, searchKind = SearchKind.ALL, searchFrom = null, searchDays = null) }
        updateSearchQuery("")
    }

    // --- FAB expand/collapse ---

    fun toggleFab() {
        _uiState.update { it.copy(isFabExpanded = !it.isFabExpanded) }
    }

    fun collapseFab() {
        _uiState.update { it.copy(isFabExpanded = false) }
    }

    // --- New-chat dialog ---

    fun showNewChatDialog() {
        _uiState.update { it.copy(showNewChatDialog = true, isFabExpanded = false) }
    }

    fun hideNewChatDialog() {
        _uiState.update {
            it.copy(
                showNewChatDialog = false,
                newChatPeerKey = "",
                groupName = "",
                isGroupCreation = false
            )
        }
    }

    fun updateNewChatPeerKey(key: String) {
        _uiState.update { it.copy(newChatPeerKey = key) }
    }

    fun setGroupCreation(isGroup: Boolean) {
        _uiState.update { it.copy(isGroupCreation = isGroup) }
    }

    fun updateGroupName(name: String) {
        _uiState.update { it.copy(groupName = name) }
    }

    // --- FFI: create direct chat ---

    /**
     * Add a contact by their Nostr identity (npub bech32 or 64-char hex pubkey).
     *
     * Calls the dedicated FFI `add_contact_by_npub` which:
     *  - parses npub/hex,
     *  - fetches the peer's MLS KeyPackage from configured relays,
     *  - builds and gift-wraps a Welcome to the peer,
     *  - persists the resulting 1:1 chat locally.
     *
     * Errors from the core surface via [MessengerException] with a real reason
     * (e.g. "no KeyPackage published by <npub>") which we show in the UI.
     */
    fun createDirectChat(npub: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val peerKey = npub.ifBlank { _uiState.value.newChatPeerKey }
            if (peerKey.isBlank()) {
                _uiState.update { it.copy(error = strings.get(R.string.enter_npub_error)) }
                return@launch
            }
            _uiState.update { it.copy(isAddingContact = true, error = null) }
            try {
                // Duplicate guard: tell the user instead of silently re-opening.
                if (repository.directChatExists(peerKey)) {
                    _uiState.update { it.copy(
                        isAddingContact = false,
                        showNewChatDialog = false,
                        newChatPeerKey = "",
                        error = strings.get(R.string.contact_exists)
                    ) }
                    return@launch
                }
                repository.addContactByNpub(peerKey)
                val allChats = repository.listChats()
                _uiState.update {
                    it.copy(
                        chats = allChats.filter { c -> c.chatType != "group" },
                        groups = allChats.filter { c -> c.chatType == "group" },
                        showNewChatDialog = false,
                        newChatPeerKey = "",
                        isAddingContact = false
                    )
                }
            } catch (e: MessengerException) {
                // No keys found where we look: we share no relay with them.
                val noKeys = e.message?.contains("no KeyPackage available", ignoreCase = true) == true
                _uiState.update { it.copy(
                    isAddingContact = false,
                    error = if (noKeys) strings.get(R.string.contact_add_no_shared_relay)
                        else strings.get(R.string.contact_add_failed, e.message.orEmpty().take(300))
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isAddingContact = false,
                    error = strings.get(R.string.error_with_reason, e.message ?: "")
                ) }
            }
        }
    }

    // --- Channels ---

    fun createChannel(name: String, description: String, avatarUri: String?, cfg: com.alphamessenger.ui.channel.ChannelCfg, onCreated: (String) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = repository.createChannel(name.trim(), description, avatarUri, cfg)
                loadChats()
                kotlinx.coroutines.withContext(Dispatchers.Main) { onCreated(info.id) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = strings.get(R.string.error_with_reason, e.message ?: "")) }
            }
        }
    }

    // --- FFI: create group ---

    // Rate-limit stopgap (until the single-MLS-lock bottleneck is removed):
    // creating many groups in a short burst hammers the one MLS lock — each
    // group = create + add commit + Welcome + publish — and can freeze the UI.
    // Allow a small burst, then require a short cooldown. Tunable:
    // MAX_GROUPS_PER_WINDOW / window.
    private val recentGroupCreations = java.util.ArrayDeque<Long>()
    private val maxGroupsPerWindow = 5
    private val groupWindowMs = 120_000L // 2 minutes

    fun createGroup(
        name: String,
        members: List<String> = emptyList(),
        avatarUri: String? = null,
        description: String = "",
        hiddenOwner: Boolean = false,
        protect: Boolean = false,
        history: Boolean = false,
    ) {
        // Burst guard — checked synchronously before any heavy work.
        val now = System.currentTimeMillis()
        synchronized(recentGroupCreations) {
            while (recentGroupCreations.isNotEmpty() && now - recentGroupCreations.peekFirst() > groupWindowMs) {
                recentGroupCreations.pollFirst()
            }
            if (recentGroupCreations.size >= maxGroupsPerWindow) {
                val waitSec = ((groupWindowMs - (now - recentGroupCreations.peekFirst())) / 1000) + 1
                _uiState.update {
                    it.copy(error = strings.get(R.string.group_rate_limited, maxGroupsPerWindow, waitSec))
                }
                return
            }
            recentGroupCreations.addLast(now)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val groupName = name.ifBlank { _uiState.value.groupName }
            if (groupName.isBlank()) return@launch
            try {
                repository.createGroupFull(groupName, members, avatarUri, anonymous = hiddenOwner, description = description, protect = protect, history = history)
                val allChats = repository.listChats()
                _uiState.update {
                    it.copy(
                        chats = allChats.filter { c -> c.chatType != "group" },
                        groups = allChats.filter { c -> c.chatType == "group" },
                        showNewChatDialog = false,
                        groupName = "",
                        isGroupCreation = false
                    )
                }
            } catch (e: MessengerException) {
                // Partial failure (some members couldn't be added) still leaves
                // the group on the local list — show the warning but refresh.
                viewModelScope.launch {
                    runCatching {
                        val allChats = repository.listChats()
                        _uiState.update {
                            it.copy(
                                chats = allChats.filter { c -> c.chatType != "group" },
                                groups = allChats.filter { c -> c.chatType == "group" },
                                showNewChatDialog = false,
                                groupName = "",
                                isGroupCreation = false,
                            )
                        }
                    }
                }
                _uiState.update { it.copy(error = strings.get(R.string.group_created_partial, e.message ?: "")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = strings.get(R.string.error_with_reason, e.message ?: "")) }
            }
        }
    }

    /**
     * Invite a peer by npub into an existing group. UI calls this from the
     * group info screen / FAB action "Add participant".
     */
    fun addMemberToGroup(chatId: String, npub: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.addMemberByNpub(chatId, npub)
                _uiState.update { it.copy(error = null) }
            } catch (e: MessengerException) {
                _uiState.update {
                    it.copy(error = if (e.isGroupFull()) strings.get(R.string.group_full, com.alphamessenger.util.MAX_GROUP_MEMBERS)
                        else strings.get(R.string.member_add_failed, e.message ?: ""))
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = strings.get(R.string.error_with_reason, e.message ?: "")) }
            }
        }
    }

    // --- Chat-list UX actions ---

    fun togglePin(chat: ChatInfo) {
        val pinned = chat.pinnedAt > 0u
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.pinChat(chat.id, !pinned) }
                .onSuccess { loadChats() }
                .onFailure { e -> _uiState.update { it.copy(error = "Pin failed: ${e.message}") } }
        }
    }

    /** seconds: 0 unmutes; Long.MAX_VALUE mutes forever; otherwise duration. */
    fun setMute(chat: ChatInfo, seconds: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.muteChat(chat.id, seconds) }
                .onSuccess { loadChats() }
                .onFailure { e -> _uiState.update { it.copy(error = "Mute failed: ${e.message}") } }
        }
    }

    fun toggleArchive(chat: ChatInfo) {
        val archived = chat.archivedAt > 0u
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.archiveChat(chat.id, !archived) }
                .onSuccess { loadChats() }
                .onFailure { e -> _uiState.update { it.copy(error = "Archive failed: ${e.message}") } }
        }
    }

    fun deleteChat(chat: ChatInfo, forBoth: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteChat(chat.id, forBoth) }
                .onSuccess { loadChats() }
                .onFailure { e -> _uiState.update { it.copy(error = "Delete failed: ${e.message}") } }
        }
    }

    /** Leave a GROUP — mirrors ContactInfoViewModel.leaveChat() which is the
     *  working path from the Group Info screen. Groups can't be "deleted";
     *  leaving sends a notice, marks MlsGroupRow.is_active=false (catch_up
     *  then DROPs further messages — no Direct fallback / no Welcome storm),
     *  and removes the chat locally so it vanishes from the list. */
    suspend fun amOwner(chatId: String): Boolean = repository.myRoleIn(chatId) == "owner"

    /** Delete direct chats and leave groups/channels (our own ones are deleted for everyone). */
    fun deleteOrLeave(chats: List<ChatInfo>) {
        for (c in chats) if (c.chatType == "group") leaveGroup(c) else deleteChat(c)
    }

    fun leaveGroup(chat: ChatInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            // The owner does not leave a group behind: it is deleted for everyone
            // (the core tells the members; no text, no names).
            if (chat.chatType == "group" && amOwner(chat.id)) {
                runCatching { repository.deleteChat(chat.id) }
                loadChats()
                return@launch
            }
            if (chat.chatType == "group") {
                val me = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
                val myName = runCatching { repository.getMyProfile()?.displayName }.getOrNull().orEmpty()
                val shown = myName.ifBlank { strings.get(R.string.participant_fallback) }
                // Visible notice in a group (never in a channel's feed); the core
                // itself tells the owner we leave.
                if (!repository.channelCfg(chat.id).on) runCatching { repository.sendText(chat.id, com.alphamessenger.ui.chat.buildLeaveNotice(shown)) }
            }
            runCatching { repository.leaveChat(chat.id) }
            // The leaver's own copy of the group closes & is removed.
            runCatching { repository.deleteChat(chat.id) }
                .onSuccess { loadChats() }
                .onFailure { e -> _uiState.update { it.copy(error = "Leave failed: ${e.message}") } }
            loadChats()
        }
    }

    /** Block a 1:1 peer by npub (from the profile sheet). */
    fun blockPeer(npub: String, name: String, avatar: String) {
        if (npub.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.blockUser(npub, name, avatar) }.onSuccess { loadChats() }
        }
    }

    fun markRead(chat: ChatInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.markChatRead(chat.id) }.onSuccess { loadChats() }
        }
    }

    fun markUnread(chat: ChatInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.markChatUnread(chat.id) }.onSuccess { loadChats() }
        }
    }

    fun markAllRead(tab: BottomTab) {
        viewModelScope.launch(Dispatchers.IO) {
            val st = _uiState.value
            val list = when (tab) {
                BottomTab.GROUPS -> st.groups.filter { it.id !in st.channelIds }
                BottomTab.CHANNELS -> st.groups.filter { it.id in st.channelIds }
                else -> st.chats
            }
            list.filter { it.unreadCount > 0u || it.unreadOverride }
                .forEach { runCatching { repository.markChatRead(it.id) } }
            loadChats()
        }
    }

    /** Fetch own npub for the share / copy / scan buttons in the FAB sheet. */
    suspend fun fetchIdentityNpub(): String = try {
        repository.getIdentityInfo().npub
    } catch (_: Exception) {
        ""
    }

    /** Fetch own profile (display_name, avatar_path) for the TopBar avatar. */
    suspend fun fetchMyProfile(): uniffi.alphamessenger.ProfileInfo? = try {
        repository.getMyProfile()
    } catch (_: Exception) {
        null
    }

    /** Profile-change broadcast — subscribers reload `fetchMyProfile()`. */
    fun profileChangedFlow() = repository.profileChanged

    // --- Error handling ---

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // --- Filtered lists based on search query ---

    fun getFilteredChats(): List<ChatInfo> {
        val query = _uiState.value.searchQuery
        return if (query.isBlank()) {
            _uiState.value.chats
        } else {
            _uiState.value.chats.filter { chat ->
                chat.name?.contains(query, ignoreCase = true) == true ||
                        chat.id.contains(query, ignoreCase = true)
            }
        }
    }

    fun getFilteredGroups(): List<ChatInfo> {
        val query = _uiState.value.searchQuery
        return if (query.isBlank()) {
            _uiState.value.groups
        } else {
            _uiState.value.groups.filter { chat ->
                chat.name?.contains(query, ignoreCase = true) == true ||
                        chat.id.contains(query, ignoreCase = true)
            }
        }
    }
}

/** A sync state shorter than this never reaches the header. */
private const val STATUS_SHOW_AFTER_MS = 700L

/** A message found by the search, ready for its row. */
data class FoundMessage(
    val id: String,
    val chatId: String,
    val outgoing: Boolean,
    /** The author's name (groups and channels); empty for my own. */
    val sender: String,
    val text: String,
    val createdAt: String,
    /** "media" | "file" | "link" | "music" | "voice", or empty for plain text. */
    val kind: String,
    /** A file's name, a link's title; empty otherwise. */
    val name: String,
    /** The whole message, for the kinds drawn as tiles and cards (media, links). */
    val ui: com.alphamessenger.ui.chat.MessageUi? = null,
)

/** What the search looks through. Chats and channels filter the same results; the
 *  attachment kinds ask the core for that kind and list it even without a query. */
enum class SearchKind(val core: String?, val label: Int) {
    ALL(null, com.alphamessenger.R.string.search_filter_all),
    CHATS(null, com.alphamessenger.R.string.search_filter_chats),
    CHANNELS(null, com.alphamessenger.R.string.search_filter_channels),
    MEDIA("media", com.alphamessenger.R.string.search_filter_media),
    LINKS("link", com.alphamessenger.R.string.search_filter_links),
    FILES("file", com.alphamessenger.R.string.search_filter_files),
    DOCS("doc", com.alphamessenger.R.string.search_filter_docs),
    APPS("app", com.alphamessenger.R.string.search_filter_apps),
    ARCHIVES("archive", com.alphamessenger.R.string.search_filter_archives),
    MUSIC("music", com.alphamessenger.R.string.search_filter_music),
    VOICE("voice", com.alphamessenger.R.string.search_filter_voice),
    ROUND("round", com.alphamessenger.R.string.search_filter_round),
    POLLS("poll", com.alphamessenger.R.string.search_filter_polls),
}

/** A person the search narrows to: [key] is "self" or their pubkey hex. */
data class SearchPerson(val key: String, val name: String, val avatarPath: String, val chatId: String)

/** A kind, an author or a period lists what it keeps even before a word is typed. */
fun ChatListState.listsWithoutQuery(): Boolean = searchKind.core != null || searchFrom != null || searchDays != null

private const val FOUND_PAGE = 50
