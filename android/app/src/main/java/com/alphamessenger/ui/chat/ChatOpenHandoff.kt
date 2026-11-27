package com.alphamessenger.ui.chat

import com.alphamessenger.ui.chatlist.ChatPeerSnapshot
import uniffi.alphamessenger.ChatInfo

/** Hands the already-resolved conversation preview from the chat list to the
 *  ChatViewModel so the header (name + avatar) is present on the FIRST frame,
 *  instead of resolving it asynchronously after ChatScreen is composed. */
object ChatOpenHandoff {
    data class Preview(
        val chatInfo: ChatInfo,
        val peerDisplayName: String,
        val peerAvatarPath: String,
        /** A secret chat: the chat it came from and its state, so it opens as one. */
        val secretOrigin: String = "",
        val secretState: String = "",
    )

    @Volatile private var pending: Preview? = null

    fun offer(p: Preview) { pending = p }

    /** What the list's row knows about [chat]: a secret chat hands over its peer's own name. */
    fun preview(chat: ChatInfo, snap: ChatPeerSnapshot?) = Preview(
        chatInfo = chat,
        peerDisplayName = (if (snap?.secret == true) snap.peerName else snap?.displayName).orEmpty(),
        peerAvatarPath = snap?.avatarPath.orEmpty(),
        secretOrigin = snap?.secretOrigin.orEmpty(),
        secretState = snap?.secretState.orEmpty(),
    )

    fun take(chatId: String): Preview? {
        val p = pending ?: return null
        return if (p.chatInfo.id == chatId) { pending = null; p } else null
    }
}
