package com.alphamessenger.ui.chat

import android.net.Uri

/**
 * A gallery grid item shown by the attachment panel.
 *
 * Selection state is NOT held here — it lives in `MediaDraftStore`.
 */
data class GalleryItem(
    val uri: Uri,
    val isVideo: Boolean,
    val durationMs: Long,
    val dateAdded: Long = 0L, // epoch seconds from MediaStore
)

/** Max items selectable per send (gallery grid + "File" multi-pick). */
const val MAX_MEDIA_SELECTION = 30
