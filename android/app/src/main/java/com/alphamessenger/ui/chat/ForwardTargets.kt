package com.alphamessenger.ui.chat

import androidx.compose.runtime.collectAsState
import com.alphamessenger.data.AlphaCoreRepository
import uniffi.alphamessenger.ChatInfo

/** Where a forward may go, as the forward sheet shows it. */
data class ForwardTargetSet(
    val chats: List<ChatInfo> = emptyList(),
    /** 1:1 chat id → the peer's (display name, avatar path). */
    val profiles: Map<String, Pair<String, String>> = emptyMap(),
    /** Channels we may post in (owner / admin). */
    val channelIds: Set<String> = emptySet(),
)

/**
 * Every chat a forward can target, except [excludeChatId]: channels only where
 * we may post, 1:1 peers with their real name and avatar (never a raw key).
 */
suspend fun loadForwardTargetSet(repository: AlphaCoreRepository, excludeChatId: String?): ForwardTargetSet {
    val all = runCatching { repository.listChats() }.getOrNull().orEmpty().filter { it.id != excludeChatId }
        .filter { c -> !com.alphamessenger.assistant.isAssistantChat(c.id) || com.alphamessenger.assistant.AssistantStore.profileOfChat(c.id)?.perms?.contains(com.alphamessenger.assistant.AssistantPerm.SHARE) == true }
    val channelIds = mutableSetOf<String>()
    val readOnly = mutableSetOf<String>()
    for (g in all) {
        if (g.chatType != "group") continue
        if (runCatching { repository.channelCfg(g.id).on }.getOrDefault(false)) {
            channelIds += g.id
            if (ownRole(repository, g.id) !in setOf("owner", "admin")) readOnly += g.id
        }
    }
    val targets = all.filter { it.id !in readOnly }
    val ownId = runCatching { repository.ownNpub() }.getOrDefault("")
    val profiles = mutableMapOf<String, Pair<String, String>>()
    for (chat in targets) {
        if (chat.chatType == "group" || chat.id == "saved_messages_local" || com.alphamessenger.assistant.isAssistantChat(chat.id)) continue
        val peerId = runCatching { repository.resolvePeerSenderId(chat.id, ownId) }.getOrNull().orEmpty()
        if (peerId.isBlank()) continue
        val prof = runCatching { repository.getPeerProfile(peerId) }.getOrNull()
        if (prof != null && (prof.displayName.isNotBlank() || prof.avatarPath.isNotBlank())) {
            profiles[chat.id] = prof.displayName to prof.avatarPath
        }
    }
    return ForwardTargetSet(targets, profiles, channelIds - readOnly)
}

private suspend fun ownRole(repository: AlphaCoreRepository, chatId: String): String = runCatching {
    val arr = org.json.JSONArray(repository.listGroupMembers(chatId))
    (0 until arr.length()).map { arr.getJSONObject(it) }
        .firstOrNull { it.optBoolean("is_self", false) }
        ?.optString("role", "member") ?: "member"
}.getOrDefault("member")

/**
 * A stored MediaRef can be forwarded as is (no download, no second upload)
 * when it is a real, self-contained ref and not the optimistic placeholder:
 * it already carries the key, the locations, the name, the type and the poster.
 */
fun isReusableMediaRef(json: String): Boolean = runCatching {
    val o = org.json.JSONObject(json)
    if (o.optBoolean("pending", false)) return@runCatching false
    val hasBlobs = (o.optJSONArray("blob_urls")?.length() ?: 0) > 0
    hasBlobs || o.has("chunks") || o.has("stream_chunks") || o.has("manifest_url")
}.getOrDefault(false)

/** The forward picker over [ChatViewModel.forwardSet], refreshed each time it opens (chats may have come since). */
@androidx.compose.runtime.Composable
fun ForwardTargetsPicker(
    viewModel: ChatViewModel,
    summary: String?,
    colors: com.alphamessenger.ui.theme.AlphaColorScheme,
    onDismiss: () -> Unit,
    onConfirm: (targetIds: List<String>, comment: String, plain: Boolean) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.loadForwardTargets() }
    val set = viewModel.forwardSet.collectAsState().value ?: ForwardTargetSet()
    ChatPickerSheet(chats = set.chats, profiles = set.profiles, channelIds = set.channelIds,
        summary = summary, colors = colors, onDismiss = onDismiss, onConfirm = onConfirm)
}
