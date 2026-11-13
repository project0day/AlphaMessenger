package com.alphamessenger.ui.chat.media

/**
 * Single source of truth for what can be done with an item. Menus, bars and
 * renderers never check the item type themselves — they all ask here.
 */
data class MediaCapabilities(
    val canZoom: Boolean,
    val canSave: Boolean,
    val canShare: Boolean,
    val canForward: Boolean,
    val canReply: Boolean,
    val canShowInChat: Boolean,
    val canOpenWith: Boolean,
    val canShowAllMedia: Boolean,
    val canShowInfo: Boolean,
    val canShowCaption: Boolean,
    val canDragToDismiss: Boolean,
    /** The window must block screenshots. */
    val requiresSecureWindow: Boolean,
) {
    val hasAnyAction: Boolean
        get() = canSave || canShare || canForward || canReply ||
            canShowInChat || canOpenWith || canShowAllMedia || canShowInfo

    companion object {
        fun of(item: MediaItem): MediaCapabilities = when (item.presentation) {
            // View-once media: view-only, nothing ever leaves the screen.
            MediaItem.Presentation.SECURE -> MediaCapabilities(
                canZoom = item.kind != MediaItem.Kind.VIDEO,
                canSave = false, canShare = false, canForward = false, canReply = false,
                canShowInChat = false, canOpenWith = false, canShowAllMedia = false,
                canShowInfo = false, canShowCaption = false,
                canDragToDismiss = true, requiresSecureWindow = true,
            )
            // Avatar: an image outside the chat context.
            MediaItem.Presentation.AVATAR -> MediaCapabilities(
                canZoom = true,
                canSave = false, canShare = false, canForward = false, canReply = false,
                canShowInChat = false, canOpenWith = false, canShowAllMedia = false,
                canShowInfo = false, canShowCaption = false,
                canDragToDismiss = true, requiresSecureWindow = false,
            )
            MediaItem.Presentation.STANDARD -> when (item.kind) {
                MediaItem.Kind.LOCATION -> MediaCapabilities(
                    canZoom = false,
                    canSave = false, canShare = true, canForward = true, canReply = true,
                    canShowInChat = true, canOpenWith = true, canShowAllMedia = false,
                    canShowInfo = false, canShowCaption = false,
                    canDragToDismiss = false, requiresSecureWindow = false,
                )
                MediaItem.Kind.VIDEO -> MediaCapabilities(
                    canZoom = false,
                    canSave = true, canShare = true, canForward = true, canReply = true,
                    canShowInChat = true, canOpenWith = true, canShowAllMedia = true,
                    canShowInfo = true, canShowCaption = true,
                    canDragToDismiss = true, requiresSecureWindow = false,
                )
                else -> MediaCapabilities(
                    canZoom = true,
                    canSave = true, canShare = true, canForward = true, canReply = true,
                    canShowInChat = true, canOpenWith = false, canShowAllMedia = true,
                    canShowInfo = true, canShowCaption = true,
                    canDragToDismiss = true, requiresSecureWindow = false,
                )
            }
        }
    }
}
