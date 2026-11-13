package com.alphamessenger.ui.chat

/**
 * standard conversation snapshot store.
 *
 * Holds the LAST DISPLAYED, fully-prepared bubble list for each chat — the exact
 * `List<MessageUi>` that already went through `foldToBubbles → mergeCallLog →
 * withRunFlags → groupAlbums → applyPendingSending`. It is an immutable, ready-to-
 * render snapshot; NOT raw `MessageInfo`.
 *
 * Purpose: when a chat is re-opened, the ViewModel seeds `_uiState.messages` from
 * this cache on the FIRST frame (with `isLoading = true`) so the user instantly
 * sees the prior history while Rust re-reads the DB in the background. The fresh
 * list then atomically replaces the seed — there is never an intermediate
 * `messages = emptyList()` state, so no "bubbles drawing in" after open.
 *
 * The ViewModel is the ONLY writer: it stores the current list on leave
 * (`onCleared` / screen-pause). The list screen cannot build bubbles, so the
 * snapshot can only come from a previous visit — hence a chatId-keyed store rather
 * than passing the list through the navigation handoff.
 *
 * Bounded LRU (access-ordered) so retained bubble lists can't grow without limit.
 */
object ConversationSnapshotCache {
    private const val MAX_CHATS = 16

    /** Everything needed to paint the chat identically to how it last looked:
     *  the ready bubble list AND the resolved group-sender profiles (so group
     *  bubbles show real nicknames on frame 1 instead of flashing raw pubkeys). */
    data class Snapshot(
        val messages: List<MessageUi>,
        val peerProfiles: Map<String, ChatBubblePeer>,
        /** Which rows are whispers: without it frame 1 would show a whisper's content. */
        val whispers: Map<String, com.alphamessenger.data.WhisperMeta> = emptyMap(),
        /** When the list was read from the DB (see [stamp]). */
        val stamp: Long = 0L,
    )

    private val clock = java.util.concurrent.atomic.AtomicLong()

    /** A fresh, ever-growing mark for a list just read from the DB. */
    fun stamp(): Long = clock.incrementAndGet()

    private val store = object : LinkedHashMap<String, Snapshot>(
        /* initialCapacity = */ MAX_CHATS + 1,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>): Boolean =
            size > MAX_CHATS
    }

    /** Last displayed snapshot for [chatId], or null if never displayed / evicted. */
    @Synchronized
    fun get(chatId: String): Snapshot? = store[chatId]

    /** Store the currently displayed bubble list + resolved sender profiles. Empty
     *  lists and lists older than the stored one are ignored — use [clear] to forget. */
    @Synchronized
    fun put(
        chatId: String,
        messages: List<MessageUi>,
        peerProfiles: Map<String, ChatBubblePeer>,
        whispers: Map<String, com.alphamessenger.data.WhisperMeta>,
        stamp: Long,
    ) {
        if (chatId.isBlank() || messages.isEmpty()) return
        // Two screens of one chat can be alive (one deeper in the back stack): the one
        // that read the DB last wins, so a stale copy never comes back on the next open.
        if (stamp < (store[chatId]?.stamp ?: 0L)) return
        store[chatId] = Snapshot(messages, peerProfiles, whispers, stamp)
    }

    /** Forget a chat's snapshot (history deleted/cleared). */
    @Synchronized
    fun clear(chatId: String) {
        store.remove(chatId)
    }
}
