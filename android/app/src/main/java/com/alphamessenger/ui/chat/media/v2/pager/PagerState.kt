package com.alphamessenger.ui.chat.media.v2.pager

import com.alphamessenger.ui.chat.media.MediaItem

/**
 * What exactly the viewer pages through. The pager never decides this on its
 * own: the set, the start page and the right to swipe come from outside.
 *
 * No phase, zoom or chrome visibility here — pages only.
 */
data class PagerPages(
    val items: List<MediaItem>,
    val startIndex: Int = 0,
    val allowsPaging: Boolean = items.size > 1,
) {
    val size: Int get() = items.size

    /** Start page clamped to the valid range. */
    val firstPage: Int get() = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
}
