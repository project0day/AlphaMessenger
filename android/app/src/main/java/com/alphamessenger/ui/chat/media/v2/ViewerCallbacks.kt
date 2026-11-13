package com.alphamessenger.ui.chat.media.v2

import com.alphamessenger.ui.chat.media.MediaItem

/**
 * Everything the viewer reports outward. It performs nothing itself: saving,
 * forwarding, jump-to-chat and closing itself are someone else's job.
 *
 * A missing action is passed as `null` and simply never shows in the menus —
 * no `if (isVideo)` checks in the bar layouts.
 */
data class ViewerCallbacks(
    /** Flight finished; the window may be removed. Not earlier. */
    val onDismiss: () -> Unit = {},
    val onPageChanged: (index: Int, item: MediaItem?) -> Unit = { _, _ -> },
    val onSave: ((MediaItem) -> Unit)? = null,
    val onForward: ((MediaItem) -> Unit)? = null,
    val onShare: ((MediaItem) -> Unit)? = null,
    val onReply: ((MediaItem) -> Unit)? = null,
    val onShowInChat: ((MediaItem) -> Unit)? = null,
    val onOpenWith: ((MediaItem) -> Unit)? = null,
    val onShowAllMedia: (() -> Unit)? = null,
    val onInfo: ((MediaItem) -> Unit)? = null,
    /** Reply typed in the viewer's bottom row (sent without leaving it). */
    val onQuickReply: ((MediaItem, String) -> Unit)? = null,
    /** Toggle my reaction on the shown media. */
    val onReact: ((MediaItem, String) -> Unit)? = null,
    /** Which reactions the chat allows (null onReact = none at all). */
    val reactionPolicy: com.alphamessenger.ui.chat.ReactionPolicy = com.alphamessenger.ui.chat.ReactionPolicy.ALL,
    val myReactions: (MediaItem) -> Set<String> = { emptySet() },
    /** All reactions on the media (emoji → count). */
    val reactions: (MediaItem) -> Map<String, Int> = { emptyMap() },
    /** The item's download (null = not tracked): the seek bar shows how much is here. */
    val downloadOf: (MediaItem) -> kotlinx.coroutines.flow.StateFlow<com.alphamessenger.transfer.TransferSnapshot?>? = { null },
)
