package com.alphamessenger.ui.chat.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * Last known on-screen bounds of the bubbles. Bubbles publish them on
 * placement; the viewer takes them at open/close so the frame flies out of
 * the thumbnail and lands back exactly into it.
 *
 * A registry, not a callback parameter — otherwise the open() signature would
 * change for every bubble type and source (album, attachments, avatar).
 */
object MediaSourceRegistry {
    /**
     * The message currently open in the viewer. Its bubble hides ONLY the
     * media (shape, background, tail and highlight stay) so the same frame is
     * never visible twice and lands back into the empty bubble shape on close.
     */
    var activeMediaId by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun setActive(id: String?) { activeMediaId = id }

    /**
     * Whether exactly this element is open. Exact comparison: in a multi-photo
     * album only the open tile hides, not the whole album.
     */
    fun isViewing(id: String?): Boolean = id != null && activeMediaId == id

    private const val MAX = 128
    private val bounds = object : LinkedHashMap<String, Rect>(MAX + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Rect>) = size > MAX
    }

    /** While a transition is running, geometry is frozen: updates are ignored. */
    @Volatile
    var frozen = false
        private set

    @Synchronized
    fun freeze() { frozen = true }

    @Synchronized
    fun unfreeze() { frozen = false }

    @Synchronized
    fun put(id: String, rect: Rect) {
        if (frozen) return
        if (id.isBlank() || rect.width <= 0f || rect.height <= 0f) return
        bounds[id] = rect
    }

    /** Bubble bounds; for an album item "id#alN" the whole album's bounds will do. */
    @Synchronized
    fun get(id: String?): Rect? {
        if (id.isNullOrBlank()) return null
        return bounds[id] ?: bounds[id.substringBefore("#al")]
    }
}
