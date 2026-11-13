package com.alphamessenger.ui.chat.interaction

import com.alphamessenger.ui.chat.MessageUi

/**
 * The SINGLE event vocabulary a message bubble can raise (fat
 * `EventListener` model, adapted to Compose). A bubble no longer receives ~15
 * individual callbacks — it holds one [MessageInteractionScope] and calls
 * `scope.onEvent(MessageEvent.X(...))`. All routing happens in
 * [MessageInteractionController]; the business op runs in ChatViewModel.
 *
 * Adding a future interaction (text selection, link menu, new reaction, …) means
 * adding ONE case here + one branch in the controller — never re-threading bubbles.
 */
sealed interface MessageEvent {
    val message: MessageUi

    // --- Raw gestures ---
    data class Tap(override val message: MessageUi) : MessageEvent
    data class LongPress(override val message: MessageUi) : MessageEvent
    data class DoubleTap(override val message: MessageUi) : MessageEvent
    data class SwipeReply(override val message: MessageUi) : MessageEvent
    data class AvatarClick(override val message: MessageUi) : MessageEvent
    data class QuoteClick(override val message: MessageUi, val quotedId: String) : MessageEvent

    // --- Content open ---
    data class OpenMedia(override val message: MessageUi, val path: String) : MessageEvent
    data class OpenLink(override val message: MessageUi, val url: String) : MessageEvent
    /** Tap on a media bubble's chrome (caption/empty area, not the media itself). */
    data class MediaActions(override val message: MessageUi) : MessageEvent
    data class ToggleExpand(override val message: MessageUi, val key: String, val flip: () -> Unit) : MessageEvent
    data class JoinInvite(override val message: MessageUi, val link: String) : MessageEvent
    data class ViewOnceConsumed(override val message: MessageUi) : MessageEvent
    data class CancelUpload(override val message: MessageUi) : MessageEvent
    data class RetryUpload(override val message: MessageUi) : MessageEvent

    // --- Bottom-sheet actions (reactions on top, actions below) ---
    data class React(override val message: MessageUi, val emoji: String) : MessageEvent
    data class Reply(override val message: MessageUi) : MessageEvent
    data class Copy(override val message: MessageUi) : MessageEvent
    data class Edit(override val message: MessageUi) : MessageEvent
    data class Delete(override val message: MessageUi) : MessageEvent
    data class Forward(override val message: MessageUi) : MessageEvent
    /** Save to Saved Messages. */
    data class Save(override val message: MessageUi) : MessageEvent
    data class SelectToggle(override val message: MessageUi) : MessageEvent
    data class SaveGallery(override val message: MessageUi) : MessageEvent
    /** Save a single album child to the gallery (from inside the album viewer). */
    data class SaveMediaItem(override val message: MessageUi, val item: MessageUi) : MessageEvent
    data class Share(override val message: MessageUi) : MessageEvent
    data class ClearCache(override val message: MessageUi) : MessageEvent
}
