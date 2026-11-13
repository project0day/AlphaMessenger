package com.alphamessenger.ui.chat.interaction

import com.alphamessenger.ui.chat.MessageUi

/**
 * What a single message ALLOWS — computed ONCE from [MessageUi]. The action bottom
 * sheet is built purely from these flags, so the UI never re-derives per-type rules
 * (`if (image) … if (video) …`). New message types get correct actions for free by
 * feeding this factory; new actions add one flag here + one row in the sheet.
 */
data class MessageCapabilities(
    val canReact: Boolean,
    val canReply: Boolean,
    val canCopy: Boolean,
    val canForward: Boolean,
    val canSave: Boolean,        // to Saved Messages
    val canSaveGallery: Boolean,
    val canShare: Boolean,
    val canClearCache: Boolean,
    val canSelect: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
) {
    companion object {
        private val GALLERY_TYPES = setOf("image", "video", "album")
        private val SHAREABLE_TYPES = setOf("image", "video", "audio", "file", "album")

        fun of(m: MessageUi): MessageCapabilities {
            val shareable = m.isMedia && m.mediaType in SHAREABLE_TYPES
            return MessageCapabilities(
                canReact = true,
                canReply = true,
                canCopy = m.text.isNotBlank() && !m.isDeleted,
                canForward = true,
                canSave = true,
                canSaveGallery = m.isMedia && m.mediaType in GALLERY_TYPES,
                canShare = shareable,
                canClearCache = shareable,
                canSelect = true,
                // Own, non-deleted, non-forwarded text or media (caption). A forward is
 // anonymous & immutable → never editable.
                canEdit = m.isOutgoing && !m.isDeleted && m.forwardedFrom == null &&
                    (m.text.isNotBlank() || m.isMedia),
                canDelete = true,
            )
        }
    }
}
