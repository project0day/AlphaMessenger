package com.alphamessenger.ui.chat

/**
 * Which reactions a chat allows — set by a group's owner, the same for every
 * member (the core enforces it on send and on receive; the UI only offers
 * what is allowed). Direct chats and groups without a setting allow all.
 */
data class ReactionPolicy(
    /** "all" | "some" | "none". */
    val mode: String = MODE_ALL,
    /** The allowed emoji when [mode] is "some", in the owner's order. */
    val allowed: List<String> = emptyList(),
) {
    val enabled: Boolean get() = mode != MODE_NONE
    /** Any emoji may be picked (the full emoji panel is offered). */
    val anyEmoji: Boolean get() = mode == MODE_ALL

    fun allows(emoji: String): Boolean = when (mode) {
        MODE_NONE -> false
        MODE_SOME -> emoji in allowed
        else -> true
    }

    /** The quick-reaction row: the allowed set, or [defaults] when all are allowed. */
    fun quick(defaults: List<String>): List<String> = if (mode == MODE_SOME) allowed else defaults

    companion object {
        const val MODE_ALL = "all"
        const val MODE_SOME = "some"
        const val MODE_NONE = "none"
        val ALL = ReactionPolicy()

        /** Emoji the owner can choose from (screen "Reactions"). */
        val CHOOSABLE = listOf(
            "👍", "❤️", "😂", "🔥", "😮", "😢", "🙏", "👏", "🎉", "🤔", "😡", "💯",
            "🥰", "😎", "🤝", "👀", "💔", "🤯", "😁", "🤣", "😍", "👎", "😱", "🤗",
        )
    }
}
