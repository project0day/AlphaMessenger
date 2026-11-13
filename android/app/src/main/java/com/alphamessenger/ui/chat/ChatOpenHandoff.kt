package com.alphamessenger.ui.chat

import uniffi.alphamessenger.ChatInfo

/** Hands the already-resolved conversation preview from the chat list to the
 *  ChatViewModel so the header (name + avatar) is present on the FIRST frame,
 *  instead of resolving it asynchronously after ChatScreen is composed. */
object ChatOpenHandoff {
    data class Preview(
        val chatInfo: ChatInfo,
        val peerDisplayName: String,
        val peerAvatarPath: String,
    )

    @Volatile private var pending: Preview? = null

    fun offer(p: Preview) { pending = p }

    fun take(chatId: String): Preview? {
        val p = pending ?: return null
        return if (p.chatInfo.id == chatId) { pending = null; p } else null
    }
}
