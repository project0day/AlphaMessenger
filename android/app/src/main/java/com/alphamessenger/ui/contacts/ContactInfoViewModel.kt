package com.alphamessenger.ui.contacts

import com.alphamessenger.util.isGroupFull
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.chat.MessageUiLabels
import com.alphamessenger.ui.chat.toMessageUi
import com.alphamessenger.ui.chat.markWhispers
import com.alphamessenger.ui.chat.isOneTime
import com.alphamessenger.util.Strings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the classic ContactInfo / Profile-of-peer screen.
 *
 * One screen, two flavours decided by `isGroup`:
 *  - direct chat → shows peer name + bio + npub + media tabs
 *  - group chat → shows group name + description + invite-link + members
 *
 * Both flavours share the avatar, action-button row, and shared-media tabs.
 */
data class ContactInfoUiState(
    val chatId: String = "",
    /** A secret chat: the ordinary chat it came from. "" otherwise. */
    val secretOrigin: String = "",
    val isGroup: Boolean = false,
    /** Non-null when this group is a channel. */
    val channel: com.alphamessenger.ui.channel.ChannelCfg? = null,
    /** A plain group's settings record (forwarding ban); null for channels and 1:1. */
    val groupCfg: com.alphamessenger.ui.channel.ChannelCfg? = null,
    /** We own this group or channel hidden: our own row says so. */
    val ownerHidden: Boolean = false,
    val isSelfProfile: Boolean = false, // tapped own row → "my profile" card
    val isBlocked: Boolean = false,     // G-11: 1:1 peer blocked by us
    val disappearingSeconds: Int = 0,   // chat disappearing timer (0 = off)
    /** Live presence of the 1:1 peer (the line under the name). */
    val presenceStatus: String = "",
    val presenceText: String = "",
    val presenceLastSeen: Long = 0L,
    val isLoading: Boolean = true,
    val error: String? = null,
    // Header
    val displayName: String = "",
    val avatarPath: String = "",
    /** The 1:1 peer's video avatar and cover (for the full-screen viewer). */
    val peerVideoPath: String = "",
    val peerCoverPath: String = "",
    val statusLine: String = "",
    val npub: String = "",
    val bio: String = "",
    val description: String = "",
    // Group-only
    val memberCount: Int = 0,
    val isAdmin: Boolean = false,
    val myRole: String = "member", // G-3: own role in this group
    val rolesEnabled: Boolean = false, // false for legacy groups w/o recorded owner
    val members: List<PeerRow> = emptyList(),
    val bans: List<com.alphamessenger.data.BanEntry> = emptyList(),
    // G-7: pending join-by-link requests (owner/admin sees Accept/Reject).
    val pendingJoins: List<com.alphamessenger.data.PendingJoin> = emptyList(),
    // G-8: group permissions ("all"|"admins"), owner-configurable.
    val whoCanWrite: String = "all",
    val whoCanInvite: String = "admins",
    val whoCanEdit: String = "admins",
    val isOwner: Boolean = false,
    // G-10: unix-secs until which the chat is temporarily closed (0 = open).
    val chatClosedUntil: Long = 0L,
    /** Which reactions the group allows (owner-configurable). */
    val reactionPolicy: com.alphamessenger.ui.chat.ReactionPolicy = com.alphamessenger.ui.chat.ReactionPolicy.ALL,
    // G-9: recent actions (audit log), newest first; shown to owner/admin.
    val auditLog: List<com.alphamessenger.data.AuditEntry> = emptyList(),
    // Chat flags (used by buttons)
    val isMuted: Boolean = false,
    /** A 1:1 whose safety number was compared and marked (for the number that holds now). */
    val isVerified: Boolean = false,
 // Shared media, categorised, newest-first.
    val sharedMediaCount: Int = 0,
    val sharedMedia: List<com.alphamessenger.ui.chat.MessageUi> = emptyList(),  // photos + videos
    val sharedFiles: List<com.alphamessenger.ui.chat.MessageUi> = emptyList(),
    val sharedMusic: List<com.alphamessenger.ui.chat.MessageUi> = emptyList(),
    val sharedVoice: List<com.alphamessenger.ui.chat.MessageUi> = emptyList(),
    val sharedLinks: List<com.alphamessenger.ui.chat.MessageUi> = emptyList(),
    /** The shared media is still being read (the page itself is already shown). */
    val sharedLoading: Boolean = false,
)

/** Chat the forward picker can target. */

/** G-7: one invite link row. */
data class InviteRow(
    val token: String,
    val link: String,
    val expiresAt: Long,   // unix secs, 0 = permanent
    val maxUses: Int,      // 0 = unlimited
    val usedCount: Int,
    val revoked: Boolean,
    val active: Boolean,
)

/** Small projection for the group members tab. */
data class PeerRow(
    val id: String,           // sender_id = hex(signature_key)
    val displayName: String,
    val avatarPath: String,
    val isAdmin: Boolean = false,
    val isSelf: Boolean = false,
    val leafIndex: Int = -1,  // MLS leaf index (for remove/ban)
    val npub: String = "",
    val role: String = "member", // G-3: owner|admin|moderator|member
    val mutedUntil: Long = 0L,   // G-4: unix secs, 0 = not muted
)

@HiltViewModel
class ContactInfoViewModel @Inject constructor(
    private val repository: AlphaCoreRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    savedStateHandle: SavedStateHandle,
    private val strings: Strings,
    private val transfers: com.alphamessenger.transfer.TransferEngine,
) : ViewModel() {

    private val chatId: String = savedStateHandle["chatId"] ?: ""
    private val moderation = com.alphamessenger.data.GroupModeration(appContext)
    // Localized labels embedded into shared-media MessageUi rows.
    private val messageLabels = MessageUiLabels(strings)
    // Guard: send at most one pull-based role-sync request per screen lifetime
    // when the owner is unknown (core also no-ops once the owner is known).
    private var roleSyncRequested = false
    // Member Profile Sync: at most one request per screen lifetime, and at most
    // once per 24h per chat (persisted) so we never spam relays/members.
    private var profileSyncRequested = false
    private val profileSyncPrefs =
        appContext.getSharedPreferences("group_profile_sync", android.content.Context.MODE_PRIVATE)

    private fun profileSyncTtlElapsed(): Boolean {
        val last = profileSyncPrefs.getLong("ts:$chatId", 0L)
        return System.currentTimeMillis() - last > 24L * 60 * 60 * 1000
    }
    private fun markProfileSync() {
        profileSyncPrefs.edit().putLong("ts:$chatId", System.currentTimeMillis()).apply()
    }

    private val _uiState = MutableStateFlow(ContactInfoUiState(chatId = chatId))
    val uiState: StateFlow<ContactInfoUiState> = _uiState.asStateFlow()

    init {
        loadChatInfo()
        observeLiveUpdates()
    }

    /**
     * A single debounced, chat-scoped reload trigger. Unfiltered reloads fire on
     * every metadata/profile event and loop endlessly during control-message
     * bursts, so events are filtered to this chat and coalesced into at most
     * one reload per 1.5 s.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeLiveUpdates() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.merge(
                repository.chatMetadataUpdated.filter { it == chatId },
                repository.peerProfileUpdated, // any peer; debounce guards bursts
            ).debounce(1500).collect { loadChatInfo() }
        }
        // G-7: refresh the pending-requests section the moment one arrives or is
        // accepted/rejected (no debounce — these are rare, deliberate events).
        viewModelScope.launch {
            repository.pendingJoinsChanged.filter { it == chatId }.collect {
                _uiState.value = _uiState.value.copy(
                    pendingJoins = freshPendingJoins(_uiState.value.members),
                )
            }
        }
    }

    /** G-7: pending requests minus anyone already in the group (a repeat
     *  request can arrive after the member joined). Stale entries are also pruned
     *  from the store so they never resurface. */
    private fun freshPendingJoins(members: List<PeerRow>): List<com.alphamessenger.data.PendingJoin> {
        val memberNpubs = members.mapNotNull { it.npub.takeIf { n -> n.isNotBlank() } }.toSet()
        val all = moderation.pendingJoins(chatId)
        val (stale, live) = all.partition { it.npub in memberNpubs }
        stale.forEach { moderation.removePendingJoin(chatId, it.npub) }
        // Any request whose profile wasn't fetched at request time shows as a bare
        // npub. Re-resolve it in the background (fetch Nostr profile), then enrich +
        // emit pendingJoinsChanged → the list shows nick/avatar without the owner
        // having to open the requester's profile first.
        if (live.any { it.name.isBlank() }) {
            // The requester's public Nostr profile may not have propagated yet when
            // the request first lands, so a single fetch often returns nothing (bare
            // npub). RETRY with backoff until the nick resolves — then enrich + emit
            // pendingJoinsChanged so the list fills in without opening the profile.
            viewModelScope.launch(Dispatchers.IO) {
                repeat(6) {
                    val stillBlank = moderation.pendingJoins(chatId).filter { it.name.isBlank() }
                    if (stillBlank.isEmpty()) return@launch
                    stillBlank.forEach { pj ->
                        val (name, avatar) = runCatching { repository.resolveProfileWithAvatar(pj.npub) }
                            .getOrDefault("" to "")
                        if (name.isNotBlank() || avatar.isNotBlank()) {
                            runCatching { repository.enrichPendingJoinProfile(pj.npub, name, avatar) }
                        }
                    }
                    kotlinx.coroutines.delay(2500)
                }
            }
        }
        return live
    }

    /** G-8: owner sets the group permission policy. Optimistically updates the
     *  UI, then broadcasts via core (which enforces owner-only). */
    fun setPermissions(write: String, invite: String, edit: String, onResult: (String) -> Unit = {}) {
        _uiState.value = _uiState.value.copy(whoCanWrite = write, whoCanInvite = invite, whoCanEdit = edit)
        viewModelScope.launch(Dispatchers.IO) {
            val err = runCatching { repository.setGroupPermissions(chatId, write, invite, edit) }
                .fold(onSuccess = { null }, onFailure = { it.message ?: strings.get(R.string.error_generic) })
            if (err == null) runCatching { repository.logGroupEvent(chatId, "perms", "", "") }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onResult(if (err == null) strings.get(R.string.group_perms_updated) else strings.get(R.string.error_failed_reason, err))
            }
            if (err != null) loadChatInfo() // revert optimistic state
        }
    }

    /** The owner changes the channel's settings (comments, protection, joining). */
    /** The owner forbids (or allows) forwarding, copying and screenshots in a group. */
    /** Group calls on/off, and with video (owner). */
    fun setGroupCalls(calls: Boolean, video: Boolean) {
        val before = _uiState.value.groupCfg
        val cfg = (before ?: com.alphamessenger.ui.channel.ChannelCfg()).copy(on = false, calls = calls, videoCalls = video)
        _uiState.value = _uiState.value.copy(groupCfg = cfg)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setChannelCfg(chatId, cfg) }
                .onFailure { _uiState.value = _uiState.value.copy(groupCfg = before) }
        }
    }

    /** Hide or show who owns this group or channel (only when made hidden). */
    fun setOwnerHidden(hidden: Boolean) {
        val channel = _uiState.value.channel
        if (channel != null) { setChannelCfg(channel.copy(anon = hidden)); return }
        val before = _uiState.value.groupCfg
        val cfg = (before ?: com.alphamessenger.ui.channel.ChannelCfg()).copy(on = false, anon = hidden)
        _uiState.value = _uiState.value.copy(groupCfg = cfg, ownerHidden = hidden)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setChannelCfg(chatId, cfg) }
                .onFailure { _uiState.value = _uiState.value.copy(groupCfg = before) }
        }
    }

    fun setGroupHistory(on: Boolean) {
        val before = _uiState.value.groupCfg
        val cfg = (before ?: com.alphamessenger.ui.channel.ChannelCfg()).copy(on = false, history = on)
        _uiState.value = _uiState.value.copy(groupCfg = cfg)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setChannelCfg(chatId, cfg) }
                .onFailure { _uiState.value = _uiState.value.copy(groupCfg = before) }
        }
    }

    fun setGroupProtect(on: Boolean) {
        val before = _uiState.value.groupCfg
        val cfg = (before ?: com.alphamessenger.ui.channel.ChannelCfg()).copy(on = false, protect = on)
        _uiState.value = _uiState.value.copy(groupCfg = cfg)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setChannelCfg(chatId, cfg) }
                .onSuccess { runCatching { repository.logGroupEvent(chatId, "perms", "", "") } }
                .onFailure { _uiState.value = _uiState.value.copy(groupCfg = before) }
        }
    }

    fun setChannelCfg(cfg: com.alphamessenger.ui.channel.ChannelCfg) {
        val before = _uiState.value.channel
        _uiState.value = _uiState.value.copy(channel = cfg)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setChannelCfg(chatId, cfg) }
                .onSuccess { runCatching { repository.logGroupEvent(chatId, "perms", "", "") } }
                .onFailure { _uiState.value = _uiState.value.copy(channel = before) }
        }
    }

    /** The owner sets which reactions members may put. */
    fun setReactions(policy: com.alphamessenger.ui.chat.ReactionPolicy, onResult: (String) -> Unit = {}) {
        val before = _uiState.value.reactionPolicy
        _uiState.value = _uiState.value.copy(reactionPolicy = policy)
        viewModelScope.launch(Dispatchers.IO) {
            val err = runCatching { repository.setGroupReactions(chatId, policy) }
                .fold(onSuccess = { null }, onFailure = { it.message ?: strings.get(R.string.error_generic) })
            if (err == null) runCatching { repository.logGroupEvent(chatId, "perms", "", "") }
            else _uiState.value = _uiState.value.copy(reactionPolicy = before)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (err != null) onResult(strings.get(R.string.error_failed_reason, err))
            }
        }
    }

    /** Disappearing messages for the whole group: stored locally and announced
     *  with the same control notice the chat sends, so every member applies it. */
    fun setDisappearing(seconds: Int) {
        _uiState.value = _uiState.value.copy(disappearingSeconds = seconds)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setChatTimer(chatId, seconds) }
            runCatching { repository.sendText(chatId, com.alphamessenger.ui.chat.buildTimerControl(seconds)) }
        }
    }

    // --- Secret chats ---

    suspend fun secretLinkReady(): Boolean = repository.secretChatReady(chatId)

    suspend fun secretCheck(): String? = repository.secretChatCheck(chatId)

    suspend fun secretNewCircuit(): Boolean = repository.secretChatNewCircuit(chatId)

    /** Delete the secret chat on both phones. */
    fun endSecretChat(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.secretChatEnd(chatId) }
            onDone()
        }
    }

    /** A secret chat is shown as its ordinary chat's peer: a persona has no profile. */
    private suspend fun withSecret(st: ContactInfoUiState): ContactInfoUiState {
        val origin = repository.secretChatOrigin(chatId)
        if (origin.isEmpty()) return st
        val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
        val peer = repository.resolvePeerSenderId(origin, ownNpub)
        val profile = peer.takeIf { it.isNotBlank() }?.let { runCatching { repository.getPeerProfile(it) }.getOrNull() }
        return st.copy(
            secretOrigin = origin,
            displayName = strings.get(R.string.secret_name, profile?.displayName.orEmpty().ifBlank { strings.get(R.string.private_chat) }),
            avatarPath = profile?.avatarPath.orEmpty(),
            statusLine = strings.get(R.string.secret_chat_title),
            npub = "",
            bio = "",
            isBlocked = false,
            peerVideoPath = "",
            peerCoverPath = "",
        )
    }

    /** G-10: temporarily close the chat for [durationSecs] (0 = reopen now). */
    fun setChatClosed(durationSecs: Long, onResult: (String) -> Unit = {}) {
        val until = if (durationSecs <= 0) 0L else com.alphamessenger.util.TrustedTime.nowSeconds() + durationSecs
        viewModelScope.launch(Dispatchers.IO) {
            val err = runCatching { repository.setGroupClosed(chatId, until) }
                .fold(onSuccess = { null }, onFailure = { it.message ?: strings.get(R.string.error_generic) })
            if (err == null) {
                runCatching { repository.logGroupEvent(chatId, if (until > 0) "closed" else "opened", "", "") }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onResult(if (err == null) (if (until > 0) strings.get(R.string.close_chat_done) else strings.get(R.string.open_chat_done)) else strings.get(R.string.error_failed_reason, err))
            }
            loadChatInfo()
        }
    }

    /** G-7: accept a pending join request (adds the member). */
    fun acceptJoin(npub: String, onResult: (String) -> Unit = {}) {
        val reqName = _uiState.value.pendingJoins.firstOrNull { it.npub == npub }?.name ?: ""
        viewModelScope.launch(Dispatchers.IO) {
            val err = repository.acceptJoinRequest(chatId, npub)
            if (err == null) runCatching { repository.logGroupEvent(chatId, "join", npub, reqName) }
            _uiState.value = _uiState.value.copy(pendingJoins = freshPendingJoins(_uiState.value.members))
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onResult(if (err == null) strings.get(R.string.member_added) else strings.get(R.string.error_failed_reason, err))
            }
            if (err == null) loadChatInfo()
        }
    }

    /** G-7: reject a pending join request (requester is not added). */
    fun rejectJoin(npub: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.rejectJoinRequest(chatId, npub)
            _uiState.value = _uiState.value.copy(pendingJoins = freshPendingJoins(_uiState.value.members))
        }
    }

    fun reload() = loadChatInfo()

    /**
     * The chat's shared media, each kind over the whole history (the same kinds the
     * search uses); an album's parts listed one by one. Whispers and view-once never.
     */
    private suspend fun loadShared(ownNpub: String) {
        val whisperIds = repository.whisperIds(chatId)
        suspend fun shared(vararg kinds: String): List<com.alphamessenger.ui.chat.MessageUi> =
            repository.chatMedia(chatId, kinds.toList(), limit = SHARED_PAGE)
                .map { it.toMessageUi(ownNpub, messageLabels) }
                .markWhispers(whisperIds)
                .filter { !it.isDeleted && !it.isOneTime }
                .flatMap { m -> if (m.mediaType == "album") m.albumItems.orEmpty() else listOf(m) }
        val media = shared("media").filter { !it.isSticker }
        val files = shared("file", "doc", "app", "archive")
        val music = shared("music")
        val voice = shared("voice", "round")
        val links = shared("link")
        _uiState.value = _uiState.value.copy(
            sharedMedia = media, sharedFiles = files, sharedMusic = music, sharedVoice = voice, sharedLinks = links,
            sharedMediaCount = media.size + files.size + music.size + voice.size + links.size,
            sharedLoading = false,
        )
    }

    private suspend fun verifiedNow(chatId: String): Boolean = runCatching {
        org.json.JSONObject(repository.verificationInfo(chatId)).optBoolean("verified")
    }.getOrDefault(false)

    /** Back from the safety-number screen: the mark may have changed there. */
    fun refreshVerified() {
        val st = _uiState.value
        if (st.isGroup || st.chatId.isBlank()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val v = verifiedNow(st.chatId)
            if (v != _uiState.value.isVerified) _uiState.value = _uiState.value.copy(isVerified = v)
        }
    }

    /** Ban a member (G-2): MLS Remove+Commit evicts them cryptographically, plus
     *  a local ban record (so they show in "Blocked" and can't be re-added
     *  locally until expiry). [untilMs] <= 0 = permanent. */
    fun banMember(member: PeerRow, untilMs: Long, reason: String = "") {
        if (member.leafIndex < 0 || member.isSelf) return
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Announce the ban in the group BEFORE removing — the member is
            //    still in the group at this epoch, so they receive this notice
            //    (and keep it, since their chat history is preserved on removal).
            val banName = member.displayName.ifBlank { strings.get(R.string.chat_member_fallback) }
            val notice = if (reason.isNotBlank())
                strings.get(R.string.ban_notice_reason, banName, reason.trim())
            else strings.get(R.string.ban_notice, banName)
            // A channel's feed carries posts only: its moderation stays in the
            // admins' "Recent actions".
            if (_uiState.value.channel == null) runCatching { repository.sendText(chatId, notice) }
            // 2. The core drops their role, tells them the reason and removes them;
            //    their phone keeps nothing of the chat.
            repository.banMember(chatId, member.npub, member.leafIndex, reason.trim())
            // 4. Record the ban locally (avatar + reason for the blocklist UI).
            if (member.npub.isNotBlank()) {
                moderation.addBan(
                    chatId, member.npub, member.displayName, untilMs,
                    member.avatarPath, reason.trim(),
                )
            }
            // G-9: audit log.
            runCatching { repository.logGroupEvent(chatId, "ban", member.npub, member.displayName) }
            loadChatInfo()
        }
    }

    fun unbanMember(npub: String) {
        val name = _uiState.value.bans.firstOrNull { it.npub == npub }?.name ?: ""
        moderation.removeBan(chatId, npub)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.logGroupEvent(chatId, "unban", npub, name) }
        }
        loadChatInfo()
    }

    /** Invite a member by npub (G-6). Blocks banned users until unbanned.
     *  [onResult] gets a success flag (drives the dialog auto-close) plus a
     *  ready-to-toast message. */
    fun inviteByNpub(npub: String, onResult: (success: Boolean, message: String) -> Unit) {
        val target = npub.trim()
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            val msg = if (moderation.isBanned(chatId, target)) {
                strings.get(R.string.invite_user_banned)
            } else {
                runCatching { repository.addMemberByNpub(chatId, target) }
                    .fold(
                        onSuccess = { success = true; strings.get(R.string.chat_invite_sent) },
                        onFailure = { e ->
                            val m = e.message ?: strings.get(R.string.error_generic)
                            if (e.isGroupFull()) strings.get(R.string.group_full, com.alphamessenger.util.MAX_GROUP_MEMBERS)
                            else if (m.contains("no KeyPackage", true) || m.contains("keypackage", true))
                                strings.get(R.string.invite_no_keys)
                            else strings.get(R.string.chat_error_invite, m)
                        },
                    )
            }
            loadChatInfo()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(success, msg) }
        }
    }

    /** Our contacts who are not in this group yet (name and photo as we know them). */
    suspend fun pickableContacts(): List<PickContact> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val own = runCatching { repository.ownNpub() }.getOrDefault("")
        val inGroup = _uiState.value.members.map { it.npub }.filter { it.isNotBlank() }.toSet()
        repository.contacts().mapNotNull { (directChat, npub) ->
            if (npub.isBlank() || npub == own || npub in inGroup) return@mapNotNull null
            val peerId = runCatching { repository.resolvePeerSenderId(directChat, own) }.getOrDefault("")
            val prof = if (peerId.isNotBlank()) runCatching { repository.getPeerProfile(peerId) }.getOrNull() else null
            PickContact(npub, prof?.displayName?.takeIf { it.isNotBlank() } ?: npub.take(12) + "…", prof?.avatarPath.orEmpty())
        }.sortedBy { it.name.lowercase() }
    }

    /** Add several people at once; [onResult] gets a ready-to-toast summary. */
    fun inviteMany(npubs: List<String>, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var added = 0
            var full = false
            val failed = mutableListOf<String>()
            for (n in npubs) {
                if (full) { failed += n.take(12); continue }
                if (moderation.isBanned(chatId, n)) { failed += n.take(12); continue }
                runCatching { repository.addMemberByNpub(chatId, n) }
                    .onSuccess { added++ }
                    .onFailure { e -> if (e.isGroupFull()) full = true; failed += n.take(12) }
            }
            loadChatInfo()
            val msg = if (full) strings.get(R.string.group_full, com.alphamessenger.util.MAX_GROUP_MEMBERS)
                else if (failed.isEmpty()) strings.get(R.string.add_members_done, added)
                else strings.get(R.string.add_members_failed, failed.joinToString())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(msg) }
        }
    }

    /** G-3: change a member's role ("admin"|"moderator"|"member"). Permission
     *  is enforced in core; failures surface via [onResult]. */
    fun setMemberRole(member: PeerRow, role: String, onResult: (String) -> Unit = {}) {
        if (member.npub.isBlank() || member.isSelf) {
            onResult(strings.get(R.string.error_member_no_pubkey))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { repository.setMemberRole(chatId, member.npub, role) }
                .fold(
                    onSuccess = {
                        when (role) {
                            "admin" -> strings.get(R.string.role_set_admin)
                            "moderator" -> strings.get(R.string.role_set_moderator)
                            else -> strings.get(R.string.role_updated)
                        }
                    },
                    onFailure = { strings.get(R.string.role_change_failed, it.message ?: strings.get(R.string.error_generic)) },
                )
            runCatching { repository.logGroupEvent(chatId, "role_$role", member.npub, member.displayName) }
            loadChatInfo()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(msg) }
        }
    }

    /** G-4: mute a member until [untilTs] (unix secs; 0 = unmute). */
    fun muteMember(member: PeerRow, untilTs: Long, onResult: (String) -> Unit = {}) {
        if (member.npub.isBlank() || member.isSelf) { onResult(strings.get(R.string.error_no_pubkey)); return }
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { repository.setMemberMute(chatId, member.npub, untilTs) }
                .fold(onSuccess = { if (untilTs > 0) strings.get(R.string.member_muted) else strings.get(R.string.member_unmuted) },
                    onFailure = { strings.get(R.string.error_failed_reason, it.message ?: strings.get(R.string.error_generic)) })
            runCatching { repository.logGroupEvent(chatId, if (untilTs > 0) "mute" else "unmute", member.npub, member.displayName) }
            loadChatInfo()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(msg) }
        }
    }

    /** G-3: transfer group ownership to [member] (owner-only). */
    fun transferOwnership(member: PeerRow, onResult: (String) -> Unit = {}) {
        if (member.npub.isBlank() || member.isSelf) {
            onResult(strings.get(R.string.error_member_no_pubkey))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { repository.transferOwnership(chatId, member.npub) }
                .fold(
                    onSuccess = { strings.get(R.string.ownership_transferred) },
                    onFailure = { strings.get(R.string.ownership_transfer_failed, it.message ?: strings.get(R.string.error_generic)) },
                )
            runCatching { repository.logGroupEvent(chatId, "role_owner", member.npub, member.displayName) }
            loadChatInfo()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(msg) }
        }
    }

    /** Decrypt/cache a media blob for thumbnails + the fullscreen viewer. */
    suspend fun loadMedia(refJson: String): String? = repository.downloadMediaToCache(refJson)

    val repo: AlphaCoreRepository get() = repository

    /** Album children carry ids like "msgId#al0" — the real DB row is the head. */
    private fun realMessageId(id: String): String = id.substringBefore("#al")

    /** Delete locally ("for me") — peers keep their copy. */
    fun deleteForMe(ids: List<String>, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.map { realMessageId(it) }.distinct().forEach {
                runCatching { repository.deleteMessageLocal(it) }
            }
            loadChatInfo()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    /** Delete for everyone — broadcasts a tombstone. */
    fun deleteForEveryone(ids: List<String>, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.map { realMessageId(it) }.distinct().forEach {
                runCatching { repository.sendDelete(chatId, it) }
                runCatching { repository.deleteMessageLocal(it) }
            }
            loadChatInfo()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    /** Chats the forward picker can target (all except the current one). */
    /** Where the forward sheet may send to; null until loaded. */
    val forwardTargets = kotlinx.coroutines.flow.MutableStateFlow<com.alphamessenger.ui.chat.ForwardTargetSet?>(null)

    fun loadForwardTargets() {
        viewModelScope.launch(Dispatchers.IO) {
            forwardTargets.value = com.alphamessenger.ui.chat.loadForwardTargetSet(repository, chatId)
        }
    }

    /**
     * Forward [items] to every chat in [targetIds], then the [comment]. Same rules
     * as forwarding from the chat: the "Forwarded" mark unless [plain], and an
     * already-uploaded file travels on as is.
     */
    fun forwardToTargets(items: List<com.alphamessenger.ui.chat.MessageUi>, targetIds: List<String>, comment: String, plain: Boolean) {
        if (items.isEmpty() || targetIds.isEmpty()) return
        val text = comment.trim()
        repository.runDetached {
            for (target in targetIds) {
                for (m in items) runCatching { forwardOne(target, m, plain) }
                if (text.isNotEmpty()) runCatching { repository.sendText(target, text) }
            }
        }
    }

    private suspend fun forwardOne(target: String, m: com.alphamessenger.ui.chat.MessageUi, plain: Boolean) {
        val body = { s: String -> if (plain) s.removePrefix(com.alphamessenger.ui.chat.FWD_MARK) else com.alphamessenger.ui.chat.buildForwardBody(s) }
        if (m.isViewOnce) return
        when (m.mediaType) {
            "link" -> {
                val d = com.alphamessenger.ui.chat.parseLinkPreview(m.mediaRefJson) ?: return
                repository.sendLinkPreview(target, d.url, d.title, d.description,
                    d.image.takeIf { it.startsWith("data:image/") }.orEmpty(), d.site, body(m.mediaCaption.orEmpty()),
                    imageRef = d.imageRef, video = d.video)
                return
            }
            null -> { if (m.text.isNotBlank()) repository.sendText(target, body(m.text)); return }
        }
        val type = when (m.mediaType) { "image", "video", "audio", "file" -> m.mediaType!!; else -> "file" }
        val cap = body(m.mediaCaption.orEmpty()).trim().takeIf { it.isNotEmpty() }
        val ref = m.mediaRefJson?.takeIf { com.alphamessenger.ui.chat.isReusableMediaRef(it) }
        if (ref != null) {
            val msgId = runCatching { repository.persistOutgoingMediaRef(target, ref, type, cap, 0u) }.getOrNull()
            if (msgId != null) {
                transfers.onUploadPreparing(msgId)
                transfers.onUploadPublishing(msgId)
                if (repository.broadcastMedia(target, msgId, ref, type, cap)) {
                    runCatching { repository.deletePendingMedia(msgId) }
                    transfers.onUploadSent(msgId)
                } else transfers.onUploadFailed(msgId)
                return
            }
        }
        // No reusable ref (an old message): the file goes up again.
        val path = m.localMediaPath?.takeIf { java.io.File(it).exists() }
            ?: m.mediaRefJson?.let { runCatching { repository.downloadMediaToCache(it) }.getOrNull() }
            ?: return
        val msgId = runCatching { repository.persistOutgoingMedia(target, path, type, cap, 0u) }.getOrNull() ?: return
        val up = runCatching { repository.uploadBlobRefOnly(path, type, null, msgId) }.getOrNull() ?: return
        if (repository.broadcastMedia(target, msgId, up, type, cap)) runCatching { repository.deletePendingMedia(msgId) }
    }

    private fun loadChatInfo() {
        if (chatId.isBlank()) {
            _uiState.value = _uiState.value.copy(isLoading = false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Peer-only mode: opened from a group member tap when no 1:1 chat
                // exists. Route id is "peer_<senderId>_<npub>". Show the same
                // profile page as a DM, just without shared media (no chat).
                if (chatId.startsWith("peer_")) {
                    val rest = chatId.removePrefix("peer_")
                    val senderId = rest.substringBefore("_")
                    val npub = rest.substringAfter("_", "")
                    val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
                    // Tapping ourselves → "my profile" mode (avatar/nick/bio +
                    // Saved/Photo/QR). Otherwise a peer-only profile page.
                    if ((npub.isNotBlank() && npub == ownNpub) || npub == SELF_MEMBER) {
                        val me = runCatching { repository.getMyProfile() }.getOrNull()
                        _uiState.value = ContactInfoUiState(
                            chatId = chatId,
                            isGroup = false,
                            isSelfProfile = true,
                            isLoading = false,
                            error = null,
                            displayName = me?.displayName?.takeIf { it.isNotBlank() } ?: strings.get(R.string.notif_you),
                            avatarPath = me?.avatarPath.orEmpty(),
                            statusLine = "",
                            npub = ownNpub,
                            bio = me?.bio.orEmpty(),
                        )
                        return@launch
                    }
                    var profile = if (senderId.isNotBlank())
                        runCatching { repository.getPeerProfile(senderId) }.getOrNull() else null
                    // Cold open from a join request (no senderId yet): fetch the
                    // public kind-0 profile by npub so we still show nick + avatar.
                    var resolvedName = profile?.displayName?.takeIf { it.isNotBlank() }.orEmpty()
                    var resolvedAvatar = profile?.avatarPath.orEmpty()
                    if (npub.isNotBlank() && (resolvedName.isBlank() || resolvedAvatar.isBlank())) {
                        val (n, a) = runCatching { repository.resolveProfileWithAvatar(npub) }.getOrDefault("" to "")
                        if (resolvedName.isBlank()) resolvedName = n
                        if (resolvedAvatar.isBlank()) resolvedAvatar = a
                    }
                    _uiState.value = ContactInfoUiState(
                        chatId = chatId,
                        isGroup = false,
                        isLoading = false,
                        error = null,
                        displayName = resolvedName.ifBlank { strings.get(R.string.profile_block_fallback_user) },
                        avatarPath = resolvedAvatar,
                        peerVideoPath = if (senderId.isNotBlank()) repository.peerAvatarVideo(senderId) else "",
                        peerCoverPath = if (senderId.isNotBlank()) runCatching { repository.getPeerCoverPath(senderId) }.getOrDefault("") else "",
                        statusLine = strings.get(R.string.chat_e2ee_subtitle),
                        npub = npub,
                        isBlocked = npub.isNotBlank() && repository.isUserBlocked(npub),
                        disappearingSeconds = runCatching { repository.chatTimerSeconds(chatId) }.getOrDefault(0),
                        bio = profile?.bio.orEmpty(),
                    )
                    // Route the resolved nick + avatar back into the "Requests"
                    // thumbnail for this npub.
                    if (npub.isNotBlank()) {
                        runCatching { repository.enrichPendingJoinProfile(npub, resolvedName, resolvedAvatar) }
                    }
                    return@launch
                }
                // The row the chat list already holds; the full list only when it has none.
                val chat = repository.chatInfoNow(chatId)
                    ?: repository.listChats(includeRequests = true).find { it.id == chatId }
                if (chat == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = strings.get(R.string.chat_not_found))
                    return@launch
                }
                val isGroup = chat.chatType == "group"
                val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()

                // Resolve peer (for 1:1) and members (for group).
                val peerId = if (!isGroup) repository.resolvePeerSenderId(chatId, ownNpub) else ""
                val peerProfile = if (peerId.isNotBlank())
                    runCatching { repository.getPeerProfile(peerId) }.getOrNull()
                else null

                val peerNpub = if (!isGroup)
                    runCatching { repository.getPeerNpubForChat(chatId) }.getOrNull().orEmpty()
                else ""

                val displayName = when {
                    com.alphamessenger.assistant.isAssistantChat(chatId) ->
                        com.alphamessenger.assistant.AssistantStore.profileOfChat(chatId)?.name?.takeIf { it.isNotBlank() }
                            ?: chat.name?.takeIf { it.isNotBlank() } ?: strings.get(R.string.assistant_title)
                    !isGroup && peerProfile?.displayName?.isNotBlank() == true -> peerProfile.displayName
                    chat.name?.isNotBlank() == true -> chat.name!!
                    isGroup -> strings.get(R.string.group_chat)
                    else -> strings.get(R.string.private_chat)
                }
                val avatarPath = when {
                    !isGroup && peerProfile != null && peerProfile.avatarPath.isNotBlank() -> peerProfile.avatarPath
                    chat.avatarPath.isNotBlank() -> chat.avatarPath
                    else -> ""
                }
                val statusLine = if (isGroup) {
                    val count = runCatching { repository.chatMemberCount(chatId).toInt() }.getOrNull() ?: 0
                    if (repository.channelCfg(chatId).on) strings.plural(R.plurals.channel_subscribers, count, count)
                    else strings.plural(R.plurals.chat_members, count, count)
                } else strings.get(R.string.chat_e2ee_subtitle)

                val bio = peerProfile?.bio.orEmpty()
                val description = chat.description

                // Shared media: each kind over the WHOLE history, sorted by the core
                // (the same kinds the search uses). An album is one message: its parts
                // are listed one by one. A whisper or a view-once is never shared media.
                // Read after the page is shown (below): a chat full of photos and videos
                // must not hold the profile behind a spinner.
                val keep = _uiState.value.takeIf { it.chatId == chatId }
                val media = keep?.sharedMedia.orEmpty()
                val files = keep?.sharedFiles.orEmpty()
                val music = keep?.sharedMusic.orEmpty()
                val voice = keep?.sharedVoice.orEmpty()
                val links = keep?.sharedLinks.orEmpty()
                val mediaCount = keep?.sharedMediaCount ?: 0

                // Real group member list (G-1) via FFI list_group_members → JSON.
                val members = if (isGroup) {
                    runCatching {
                        val json = repository.listGroupMembers(chatId)
                        val arr = org.json.JSONArray(json)
                        (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            val isSelf = o.optBoolean("is_self", false)
                            val name = o.optString("display_name").ifBlank {
                                if (isSelf) strings.get(R.string.notif_you) else strings.get(R.string.chat_member_fallback)
                            }
                            val role = o.optString("role", "member").ifBlank { "member" }
                            PeerRow(
                                id = o.optString("sender_id"),
                                displayName = name,
                                avatarPath = o.optString("avatar_path"),
                                isAdmin = role == "owner" || role == "admin",
                                isSelf = isSelf,
                                leafIndex = o.optInt("leaf_index", -1),
                                npub = o.optString("npub"),
                                role = role,
                                mutedUntil = o.optLong("muted_until", 0L),
                            )
                        }.sortedWith(compareByDescending<PeerRow> { roleRank(it.role) }.thenByDescending { it.isSelf })
                    }.getOrDefault(emptyList())
                } else emptyList()
                // G-3: own role drives which moderation actions the UI offers.
                // NO fallback to admin when the owner is unknown — otherwise a
                // freshly invited member (who hasn't received the role snapshot
                // yet) would wrongly believe they're admin and could even ban
                // the owner. Unknown owner ⇒ treat self as plain member (safe).
                val hasOwner = members.any { it.role == "owner" }
                val rolesEnabled = hasOwner
                val myRole = members.firstOrNull { it.isSelf }?.role ?: "member"
                // G-8: group permission policy (for the owner settings + gating).
                val perms = if (isGroup) runCatching { repository.groupPermissions(chatId) }
                    .getOrDefault(Triple("all", "admins", "admins")) else Triple("all", "admins", "admins")
                // If we're in a group but don't know the owner yet, ask once for
                // the role state (pull-based sync). The owner replies with a
                // snapshot; core no-ops if the owner is already recorded.
                if (isGroup && !hasOwner && !roleSyncRequested) {
                    roleSyncRequested = true
                    runCatching { repository.requestRoleSync(chatId) }
                }
                // Member Profile Sync: if any non-self member has no resolved
                // name/avatar yet, ask the group once (TTL 24h) to (re)broadcast
                // profiles. Members reply with a ProfileUpdate → cache fills.
                if (isGroup && !profileSyncRequested) {
                    val anyUnknown = members.any {
                        !it.isSelf && (it.displayName.isBlank() || it.displayName == strings.get(R.string.chat_member_fallback) || it.avatarPath.isBlank())
                    }
                    if (anyUnknown && profileSyncTtlElapsed()) {
                        profileSyncRequested = true
                        markProfileSync()
                        runCatching { repository.requestGroupProfiles(chatId) }
                    }
                }

                _uiState.value = ContactInfoUiState(
                    chatId = chatId,
                    isGroup = isGroup,
                    isLoading = false,
                    error = null,
                    displayName = displayName,
                    avatarPath = avatarPath,
                    peerVideoPath = if (peerId.isNotBlank()) repository.peerAvatarVideo(peerId) else "",
                    peerCoverPath = if (peerId.isNotBlank()) runCatching { repository.getPeerCoverPath(peerId) }.getOrDefault("") else "",
                    statusLine = statusLine,
                    npub = peerNpub,
                    isBlocked = !isGroup && peerNpub.isNotBlank() && repository.isUserBlocked(peerNpub),
                    disappearingSeconds = runCatching { repository.chatTimerSeconds(chatId) }.getOrDefault(0),
                    bio = bio,
                    description = description,
                    memberCount = runCatching { repository.chatMemberCount(chatId).toInt() }
                        .getOrNull() ?: 0,
                    isAdmin = if (isGroup) (myRole == "owner" || myRole == "admin") else false,
                    myRole = myRole,
                    rolesEnabled = rolesEnabled,
                    ownerHidden = isGroup && myRole == "owner" && repository.channelCfg(chatId).anon,
                    // A hidden owner is the chat itself to everyone else — its name and
                    // face, no key (nothing to write to, call or add).
                    members = if (isGroup && myRole != "owner" && repository.channelCfg(chatId).anon)
                        members.map {
                            if (!it.isSelf && it.role == "owner")
                                it.copy(displayName = displayName, avatarPath = avatarPath, npub = "")
                            else it
                        }
                    else members,
                    // Only show ACTIVE bans — expired temporary bans drop off
                    // the blocklist automatically (the user can be re-added).
                    bans = if (isGroup) moderation.bans(chatId).filter { it.isActive() } else emptyList(),
                    // G-7: pending join requests (minus those already in the group),
                    // shown only to owner/admin in the "Requests" tab.
                    pendingJoins = if (isGroup) freshPendingJoins(members) else emptyList(),
                    whoCanWrite = perms.first,
                    whoCanInvite = perms.second,
                    whoCanEdit = perms.third,
                    channel = if (isGroup) repository.channelCfg(chatId).takeIf { it.on } else null,
                    groupCfg = if (isGroup) repository.channelCfg(chatId).takeIf { !it.on } else null,
                    isOwner = isGroup && myRole == "owner",
                    chatClosedUntil = if (isGroup) runCatching { repository.groupClosedUntil(chatId) }.getOrDefault(0L) else 0L,
                    reactionPolicy = if (isGroup) repository.groupReactionPolicy(chatId) else com.alphamessenger.ui.chat.ReactionPolicy.ALL,
                    auditLog = if (isGroup && (myRole == "owner" || myRole == "admin"))
                        runCatching { repository.groupAuditLog(chatId) }.getOrDefault(emptyList())
                    else emptyList(),
                    isMuted = chat.mutedUntil > 0u,
                    isVerified = !isGroup && verifiedNow(chatId),
                    sharedMediaCount = mediaCount,
                    sharedMedia = media,
                    sharedFiles = files,
                    sharedMusic = music,
                    sharedVoice = voice,
                    sharedLinks = links,
                )
                _uiState.value = withSecret(_uiState.value).copy(sharedLoading = keep == null || keep.sharedMediaCount == 0)
                loadShared(ownNpub)
                if (!isGroup && _uiState.value.secretOrigin.isEmpty()) watchPeerPresence()
                // G-7: a 1:1 info card resolved this peer's nick + avatar — route
                // it into any pending "Requests" thumbnail for the same npub.
                if (!isGroup && peerNpub.isNotBlank() && (displayName.isNotBlank() || avatarPath.isNotBlank())) {
                    runCatching { repository.enrichPendingJoinProfile(peerNpub, displayName, avatarPath) }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.chat_error_load, e.message ?: ""),
                )
            }
        }
    }

    private var presenceJob: kotlinx.coroutines.Job? = null

    private fun watchPeerPresence() {
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch(Dispatchers.IO) {
            val hex = repository.peerHexForChat(chatId)
            if (hex.isBlank()) return@launch
            suspend fun read() {
                _uiState.value = _uiState.value.copy(
                    presenceStatus = runCatching { repository.presenceStatus(hex) }.getOrDefault(""),
                    presenceText = runCatching { repository.presenceCustomStatus(hex) }.getOrDefault(""),
                    presenceLastSeen = runCatching { repository.presenceLastSeen(hex) }.getOrDefault(0L),
                )
            }
            read()
            runCatching { repository.presenceTick() }
            repository.presenceChanged.collect { read() }
        }
    }

    fun toggleMute() {
        viewModelScope.launch(Dispatchers.IO) {
            val cur = _uiState.value.isMuted
            runCatching {
                repository.muteChat(chatId, if (cur) 0L else Long.MAX_VALUE)
            }
            _uiState.value = _uiState.value.copy(isMuted = !cur)
        }
    }

    fun setGroupAvatar(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setGroupMetadata(chatId, avatarPath = path) }
            loadChatInfo()
        }
    }

    fun setGroupName(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setGroupMetadata(chatId, name = name) }
            loadChatInfo()
        }
    }

    fun setGroupDescription(description: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setGroupMetadata(chatId, description = description) }
            loadChatInfo()
        }
    }

    /** A member/admin leaves: post a visible "↩️ X left" notice + audit,
     *  drop out of MLS, then DELETE the chat locally and return home. */
    /** G-11: block / unblock this 1:1 peer (device-local). */
    fun toggleBlock() {
        val np = _uiState.value.npub
        if (np.isBlank()) return
        val nowBlocked = !_uiState.value.isBlocked
        if (nowBlocked) repository.blockUser(np, _uiState.value.displayName, _uiState.value.avatarPath)
        else repository.unblockUser(np)
        _uiState.value = _uiState.value.copy(isBlocked = nowBlocked)
    }

    fun leaveChat(onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.isGroup) {
                val me = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
                val myName = runCatching { repository.getMyProfile()?.displayName }.getOrNull().orEmpty()
                val shown = myName.ifBlank { strings.get(R.string.chat_member_fallback) }
                // Visible chat notice (sent while we're still a member) + audit.
                // A notice in the chat — not in a channel's feed. (The core
                // tells the owner we leave, whichever way we leave.)
                if (_uiState.value.channel == null) runCatching { repository.sendText(chatId, strings.get(R.string.leave_group_message, shown)) }
            }
            runCatching { repository.leaveChat(chatId) }
            // The leaver's own copy of the group closes & is removed.
            runCatching { repository.deleteChat(chatId) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    /**
     * OWNER deletes the group or channel. The core tells every member (no text,
     * no names: their app shows "deleted by the owner" and wipes it), then
     * removes it here.
     */
    fun deleteGroupAsOwner(onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteChat(chatId) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    /** OWNER transfers ownership to [member], then leaves like a member. */
    fun transferAndLeave(member: PeerRow, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            if (member.npub.isNotBlank()) {
                runCatching { repository.transferOwnership(chatId, member.npub) }
                runCatching { repository.logGroupEvent(chatId, "role_owner", member.npub, member.displayName) }
                // Let the ownership transfer propagate to relays + be processed by
                // the new owner BEFORE we send our leave commit (which advances the
                // MLS epoch and would otherwise make the transfer undecryptable if
                // the recipient happened to process the commit first).
                kotlinx.coroutines.delay(3500)
            }
            val me = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
            val myName = runCatching { repository.getMyProfile()?.displayName }.getOrNull().orEmpty()
            // A hidden owner's name never goes into the chat; nor a line into a channel's feed.
            if (!_uiState.value.ownerHidden && _uiState.value.channel == null) {
                runCatching { repository.sendText(chatId, strings.get(R.string.leave_group_message, myName.ifBlank { strings.get(R.string.role_owner) })) }
            }
            runCatching { repository.logGroupEvent(chatId, "leave", me, myName) }
            runCatching { repository.leaveChat(chatId) }
            runCatching { repository.deleteChat(chatId) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    // --- G-7 Invite Links ---
    fun loadInvites(onResult: (List<InviteRow>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching {
                val arr = org.json.JSONArray(repository.listGroupInvites(chatId))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    InviteRow(
                        token = o.optString("token"),
                        link = o.optString("link"),
                        expiresAt = o.optLong("expires_at", 0L),
                        maxUses = o.optInt("max_uses", 0),
                        usedCount = o.optInt("used_count", 0),
                        revoked = o.optBoolean("revoked", false),
                        active = o.optBoolean("active", false),
                    )
                }.sortedByDescending { it.active }
            }.getOrDefault(emptyList())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(list) }
        }
    }

    /** Create an invite; returns the link (or error message) on Main. */
    fun createInvite(expiresAtSecs: Long, maxUses: Int, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { repository.createGroupInvite(chatId, expiresAtSecs, maxUses.toUInt()) }
                .fold(onSuccess = { it }, onFailure = { strings.get(R.string.error_with_reason, it.message ?: "") })
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(res) }
        }
    }

    fun revokeInvite(token: String, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.revokeGroupInvite(chatId, token) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    /** Self profile: update own avatar (path to a saved JPEG). */
    fun setMyAvatar(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setMyProfile(avatarPath = path) }
            loadChatInfo()
        }
    }

    /** Self profile: update own "About" bio. */
    fun setMyBio(text: String) {
        if (text.trim() == _uiState.value.bio.trim()) return
        _uiState.value = _uiState.value.copy(bio = text.trim())
        repository.saveMyBio(text.trim())
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

/** G-3: numeric rank for sorting members by role (owner first). */
internal fun roleRank(role: String): Int = when (role) {
    "owner" -> 3
    "admin" -> 2
    "moderator" -> 1
    else -> 0
}

/** How many of each kind the profile shows (newest first). */
private const val SHARED_PAGE = 600
