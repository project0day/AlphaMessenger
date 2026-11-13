package com.alphamessenger.ui.chat

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.alphamessenger.R
import com.alphamessenger.util.Strings
import uniffi.alphamessenger.ExpiryPolicy
import uniffi.alphamessenger.MessageInfo
import uniffi.alphamessenger.MessageType

// --- Data model for the MessageBubble composable ---

// @Immutable: every field is an immutable snapshot (the Map/Set/List are treated
// as stable via compose_stability.conf — they are never mutated after build).
// This lets MessageBubble be `skippable` so an unrelated state change no longer
// recomposes every visible row.
@Immutable
data class MessageUi(
    val id: String,
    val text: String,
    val timestamp: String,
    val isOutgoing: Boolean,
    val senderId: String = "",                    // raw sender id (hex sig key); used for deterministic color
    val senderName: String? = null,
    val senderInitial: String? = null,
    val senderGradient: Pair<Color, Color>? = null,
    val status: MessageStatus = MessageStatus.SENT,
    val reaction: String? = null,
    /** Aggregated reactions: emoji → count. Folded from REACTION rows. */
    val reactions: Map<String, Int> = emptyMap(),
    /** Emojis the CURRENT user reacted with (for correct toggle behaviour —
     *  an aggregated count can't tell whose reaction it is). */
    val myReactions: Set<String> = emptySet(),
    /** emoji → sender ids, in the order the reactions arrived (who reacted). */
    val reactors: Map<String, List<String>> = emptyMap(),
    val isVoice: Boolean = false,
    val voiceDuration: String? = null,
    val isViewOnce: Boolean = false,
    /** A whisper's reading limit (seconds); null = not a whisper row. */
    val whisperSecs: Int? = null,
    /** A whisper row (text or media): never in an album, never in the chat gallery. */
    val isWhisper: Boolean = false,
    val isViewOnceConsumed: Boolean = false,
    /** Non-null when this message is a disappearing-timer control notice. The
     *  value is the TTL in seconds (0 = turned off). Rendered as a centered
     *  system chip; also syncs the per-chat timer for BOTH parties. */
    val timerSeconds: Int? = null,
    /** Non-null when this row is an invitation to a secret chat: its invite id. */
    val secretInvite: String? = null,
    val isEphemeral: Boolean = false,
    val ephemeralExpiry: String? = null,
    val replyTo: ReplyInfo? = null,
    /** Stable id of the quoted message (for tap-to-scroll). */
    val quotedMessageId: String? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    /** Same sender as previous visible message within ~5 sec. */
    val isConsecutive: Boolean = false,
    /** Last bubble in a same-sender run — gets the bubble tail. */
    val isLastInRun: Boolean = true,
    val mediaUrl: String? = null,
    /** True when this is a media message (photo/video/file). */
    val isMedia: Boolean = false,
    /** "image" | "video" | "audio" | "file" — drives the bubble renderer. */
    val mediaType: String? = null,
    /** True when this "audio" was a recorded voice note (media_type "voice" on
     *  the wire). Kept separate from [isVoice] (which drives the renderer) so we
     *  can label replies/quotes as "Voice message" without changing the bubble. */
    val isVoiceNote: Boolean = false,
    /** A video message (a circle): video whose file is named "round_…". */
    val isRoundVideo: Boolean = false,
    /** A GIF: a short silent looping video whose file is named "gif_…". */
    val isGif: Boolean = false,
    /** A sticker: a picture whose file is named "sticker_…" (no bubble). */
    val isSticker: Boolean = false,
    /** The emoji a sticker is filed under. */
    val stickerEmoji: String? = null,
    /** The pack a sticker came from: its link, or a built-in pack's id. */
    val stickerLink: String? = null,
    val stickerBuiltin: String? = null,
    /** MediaRef JSON (key+hash+blob URLs) used to download+decrypt the blob. */
    val mediaRefJson: String? = null,
    /** Optional caption shown under the media. */
    val mediaCaption: String? = null,
    /** Original filename (for video/file/audio cards). */
    val mediaFilename: String? = null,
    /** Original size in bytes (for file/audio cards). */
    val mediaSizeBytes: Long? = null,
    /** Video/audio duration in ms — from the album itemsJson (sender extracts
     *  it via MediaMetadataRetriever before upload). Lets the receiver show
     *  the duration badge immediately without downloading the file. */
    val mediaDurationMs: Long? = null,
    /** Native video width (px) — for aspect-ratio sizing before download. */
    val mediaWidth: Int? = null,
    /** Native video height (px) — for aspect-ratio sizing before download. */
    val mediaHeight: Int? = null,
    /** Display rotation in degrees (0/90/180/270). */
    val mediaRotation: Int? = null,
    /** Voice note bars from the sender (base64), drawn before the file arrives. */
    val mediaWaveform: String? = null,
    /** Sender's local device path — render straight from it (no re-download). */
    val localMediaPath: String? = null,
    /** True while the optimistic upload is still in flight (show "sending" %). */
    val mediaPending: Boolean = false,
    /** Base64 JPEG poster for video — preview shown instantly, no download. */
    val mediaPosterB64: String? = null,
    /** Optional path to peer's avatar JPEG (filled by the ViewModel from
     *  the contacts table — overrides the deterministic gradient). */
    val senderAvatarPath: String? = null,
    /** Real nostr npub of the sender (group members) — empty/null until
     *  resolved. Powers the avatar dialog's "Chat"/"Info" actions. */
    val senderNpub: String? = null,
    /** When non-null, this bubble is the HEAD of a photo/video album and holds
     *  every member (including itself). Rendered as a grid. UI-only grouping. */
    val albumItems: List<MessageUi>? = null,
    /** True for non-head album members — skipped by the renderer (folded into
     *  the head's grid). */
    val hiddenInAlbum: Boolean = false,
    /** Non-null when this message was forwarded — the original author's name,
     *  shown as a "↪️ Forwarded from X" header above the content. */
    val forwardedFrom: String? = null,
    /** Non-null when this row is a LOCAL call-log entry (missed / incoming /
     *  outgoing call) merged into the timeline from the calls module. Rendered
     *  as a tappable system chip ("Missed call" → call back). */
    val callLog: com.alphamessenger.calls.CallHistoryEntry? = null,
    /** An assistant's reasoning, drawn as its own collapsible row above the answer. */
    val assistantThinking: AssistantThinkingUi? = null,
    /** A failed assistant request, drawn as an error card. */
    val assistantError: com.alphamessenger.assistant.AssistantError? = null,
    /** A row of an assistant's answer still being written (no time yet; the list holds its start). */
    val assistantWriting: Boolean = false,
    /** Relay changes the assistant offers, drawn as a card with Apply. */
    val assistantRelays: com.alphamessenger.assistant.RelayPlan? = null,
) {
    /** Anything filed as a sticker: its text is the pack's hidden tag, never words to select or edit. */
    val isStickerLike: Boolean get() = isSticker || stickerEmoji != null || stickerLink != null || stickerBuiltin != null
}

data class ReplyInfo(
    val senderName: String,
    /** Who wrote the quoted message (its sender id): the screen names them
     *  the way it names that sender everywhere else. */
    val senderId: String? = null,
    val text: String,
    /** Base64 JPEG poster (video) for the quoted media thumbnail, if any. */
    val posterB64: String? = null,
    /** Local image/video file path for the quoted media thumbnail, if present. */
    val thumbPath: String? = null,
    /** True when the quoted media is a video (overlay a small ▶ on the thumb). */
    val isVideoQuote: Boolean = false,
    /** Quoted media kind: "image"|"video"|"audio"|"voice"|"file"|"album"|null.
     *  Drives the right-side icon tile when there's no image thumbnail. */
    val kind: String? = null,
)

enum class MessageStatus { SENDING, SENT, DELIVERED, READ }

// classic forward marker (author-less): an invisible SOH-delimited prefix
// on the forwarded message's text/caption. Built from Char(1) so the SOURCE
// stays pure ASCII (raw control bytes can corrupt the file's encoding).
val FWD_MARK: String = Char(1) + "FWD" + Char(1)

/** Prefix [body] with the forward marker (author-less, classic).
 *  Idempotent: re-forwarding must never stack a second marker (which would leave a
 *  raw control-byte `\x01FWD\x01` in the visible text/caption). Strip any existing
 *  leading mark first, then add exactly one. */
fun buildForwardBody(body: String): String {
    val clean = if (body.startsWith(FWD_MARK)) body.substring(FWD_MARK.length) else body
    return FWD_MARK + clean
}

// Disappearing-timer control marker. Carried inside a normal E2EE text message
// so the change syncs to the peer. Built
// from Char(2) so the SOURCE stays pure ASCII (see the FWD_MARK note above).
// Payload = seconds (0 = off).
val TIMER_MARK: String = Char(2) + "TMR" + Char(2)

fun buildTimerControl(seconds: Int): String = TIMER_MARK + seconds.toString()

// An invitation to a secret chat, written by the core into the ordinary chat:
// the mark, then the invite id.
val SECRET_MARK: String = Char(2) + "SEC" + Char(2)

fun parseSecretInvite(content: String?): String? =
    content?.takeIf { it.startsWith(SECRET_MARK) }?.substring(SECRET_MARK.length)?.trim()?.takeIf { it.isNotEmpty() }

// Reply to a story: an ordinary E2EE text whose first line (after the mark)
// is a small quote of the story — kind, a text snippet and a tiny JPEG
// thumbnail — so the bubble shows WHAT was answered, as messengers usually do.
val STORY_MARK: String = Char(2) + "STR" + Char(2)

/** The story a reply quotes. */
data class StoryQuote(val kind: String, val text: String, val thumbB64: String?)

fun buildStoryReply(quote: StoryQuote, body: String): String {
    val head = org.json.JSONObject().put("k", quote.kind).put("t", quote.text.take(80))
    quote.thumbB64?.let { head.put("p", it) }
    return STORY_MARK + head.toString() + "\n" + body
}

/** (quote, reply text) for a story reply, or null. */
fun parseStoryReply(content: String?): Pair<StoryQuote, String>? {
    if (content == null || !content.startsWith(STORY_MARK)) return null
    val rest = content.substring(STORY_MARK.length)
    val nl = rest.indexOf('\n')
    val head = runCatching { org.json.JSONObject(if (nl < 0) rest else rest.substring(0, nl)) }.getOrNull() ?: return null
    val quote = StoryQuote(head.optString("k", "text"), head.optString("t"), head.optString("p").takeIf { it.isNotBlank() })
    return quote to (if (nl < 0) "" else rest.substring(nl + 1))
}

/** Parse a timer-control message → seconds (0 = off), or null if not one. */
fun parseTimerControl(content: String?): Int? {
    if (content == null || !content.startsWith(TIMER_MARK)) return null
    return content.substring(TIMER_MARK.length).trim().toIntOrNull()
}

/** Localized labels [toMessageUi]/[foldToBubbles] embed into [MessageUi]
 *  fields (system-notice texts, reply-quote sender labels, sender fallbacks).
 *  Resolves through [Strings] on each access so an in-app locale switch is
 *  picked up on the next conversion. */
class MessageUiLabels(private val s: Strings) {
    /** "You" — own-message sender label. */
    val you: String get() = s.get(R.string.notif_you)
    /** Quote of a story in a reply to it. */
    val storyQuoteLabel: String get() = s.get(R.string.stories_quote_label)
    val storyPhoto: String get() = s.get(R.string.preview_photo)
    val storyVideo: String get() = s.get(R.string.media_kind_video)

    /** "Peer" — 1:1 peer fallback for a reply quote. */
    val peer: String get() = s.get(R.string.peer_fallback)

    /** "In reply to" — quoted original not loaded. */
    val inReplyTo: String get() = s.get(R.string.chat_in_reply_to)

    /** "Message" — blank quote text fallback. */
    val messageFallback: String get() = s.get(R.string.msg_fallback_title)

    /** "Sticker" — a quoted sticker. */
    val sticker: String get() = s.get(R.string.sticker_label)

    /** A quoted photo, video, file… without words: named by its kind. */
    fun mediaKind(kind: String?, allVideo: Boolean, allImage: Boolean): String = s.get(
        when (kind) {
            "image" -> R.string.chat_reply_photo
            "video" -> R.string.chat_reply_video
            "voice" -> R.string.chat_reply_voice
            "audio" -> R.string.chat_reply_music
            "file" -> R.string.chat_reply_file
            "album" -> if (allVideo) R.string.chat_reply_video else if (allImage) R.string.chat_reply_photo else R.string.chat_reply_media
            else -> R.string.chat_reply_attachment
        },
    )

    fun secretInvite(outgoing: Boolean): String =
        s.get(if (outgoing) R.string.secret_invite_out else R.string.secret_invite_in)

    /** Human label for a disappearing-timer system notice. Works for 1:1 and
     *  groups: outgoing names "You", incoming stays actor-neutral. */
    fun timerNotice(seconds: Int, outgoing: Boolean): String {
        if (seconds <= 0) return s.get(
            if (outgoing) R.string.chat_timer_off_by_you else R.string.chat_timer_off_notice
        )
        val human = when (seconds) {
            10 -> s.get(R.string.chat_timer_10s)
            300 -> s.get(R.string.chat_timer_5m)
            21_600 -> s.get(R.string.chat_timer_6h)
            43_200 -> s.get(R.string.chat_timer_12h)
            86_400 -> s.get(R.string.chat_timer_24h)
            604_800 -> s.get(R.string.chat_timer_7d)
            7_776_000 -> s.get(R.string.chat_timer_90d)
            else -> s.get(R.string.chat_timer_hours, seconds / 3600)
        }
        return s.get(
            if (outgoing) R.string.chat_timer_on_by_you else R.string.chat_timer_on_notice,
            human,
        )
    }
}

// --- Conversion from FFI MessageInfo → MessageUi ---

/**
 * Converts a [MessageInfo] from the Rust/UNIFFI layer into a [MessageUi]
 * suitable for rendering by [MessageBubble].
 *
 * @param ownId The current user's ID, used to determine outgoing direction.
 */
fun MessageInfo.toMessageUi(ownId: String, labels: MessageUiLabels): MessageUi {
    // The Rust core writes locally-originated messages with sender_id="self"
    // (the FK target row in `contacts` for the current user), while remote
    // messages carry the peer's npub. Treat both as "outgoing" when the
    // sender matches our own identity, so the user sees their own messages
    // aligned to the right in the chat bubble layout.
    val isOwn = senderId == ownId || senderId == "self"

    // Derive delivery status for our own messages from the read receipt.
    // The peer's ReadSignal sets `read_at` on our copy (see
    // event_processor::process_read_signal) → double green check. Until then
    // a single check ("sent"). `consumed` covers view-once/ephemeral reads.
    val status = when {
        !isOwn -> MessageStatus.SENT
        readAt != null || consumed -> MessageStatus.READ
        deliveredAt != null -> MessageStatus.DELIVERED
        else -> MessageStatus.SENT
    }

    // Determine if this is a view-once or ephemeral message
    val isViewOnce = expiryPolicy is ExpiryPolicy.ViewOnce
    val isEphemeral = when(expiryPolicy) {
        is ExpiryPolicy.AfterSend,
        is ExpiryPolicy.AfterRead -> true
        else -> false
    }

    // Extract ephemeral expiry text
    val ephemeralExpiry: String? = when (expiryPolicy) {
        is ExpiryPolicy.AfterSend -> {
            val policy = expiryPolicy as ExpiryPolicy.AfterSend
            "${policy.ttlSecs}s"
        }
        is ExpiryPolicy.AfterRead -> {
            val policy = expiryPolicy as ExpiryPolicy.AfterRead
            "${policy.ttlSecs}s after read"
        }
        else -> null
    }

    parseSecretInvite(this.content)?.let { invite ->
        return MessageUi(
            id = id,
            text = labels.secretInvite(isOwn),
            timestamp = createdAt,
            isOutgoing = isOwn,
            senderId = senderId,
            secretInvite = invite,
        )
    }

    // Disappearing-timer control message → render as a centered system notice
    // (and let the ViewModel derive the shared chat timer from history).
    val timerCtl = parseTimerControl(this.content)
    if (timerCtl != null) {
        return MessageUi(
            id = id,
            text = labels.timerNotice(timerCtl, isOwn),
            timestamp = createdAt,
            isOutgoing = isOwn,
            senderId = senderId,
            timerSeconds = timerCtl,
        )
    }

 // classic forward: strip the marker prefix; flag the message so the
    // bubble shows a "Forwarded message" header (no author disclosed).
    val isForwarded = this.content?.startsWith(FWD_MARK) == true
    val storyReply = parseStoryReply(this.content)
    val content = when {
        storyReply != null -> storyReply.second
        isForwarded -> this.content!!.substring(FWD_MARK.length)
        else -> this.content
    }

    val isMediaMsg = msgType == MessageType.MEDIA
    val stickerTag = if (isMediaMsg) com.alphamessenger.ui.sticker.parseStickerCaption(content) else null
    // The stored media_ref is a MediaRef JSON that already carries the real
    // media_type / filename / original_size — parse them so the bubble can pick
    // the right renderer (photo / video / file) instead of assuming image.
    val mediaJson = if (isMediaMsg) mediaRef?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } else null

    // --- Album message (one message carrying N photo/video items) ---
    // The media_ref is an album manifest: {album:true, items:[<itemRef>...]}.
    // Build the child bubbles right here so the existing AlbumBubble renders
    // it directly — no client-side time-grouping needed.
    if (mediaJson?.optBoolean("album", false) == true) {
        val arr = mediaJson.optJSONArray("items")
        val albumPending = mediaJson.optBoolean("pending", false)
        val children = ArrayList<MessageUi>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val itType = it.optString("media_type", "image").ifBlank { "image" }
                // A path names a file on the sender's phone: only ours are real.
                val itLocal = it.optString("local_path", "").takeIf { p -> isOwn && p.isNotBlank() }
                val itPoster = it.optString("poster_b64", "").takeIf { p -> p.isNotBlank() }
                val itMeta = com.alphamessenger.ui.chat.AlbumItemMetadata.fromJson(it)
                // Read its id here, off the main thread: a big item's ref is megabytes.
                val itRef = it.toString().also { r -> if (r.length > 16 * 1024) com.alphamessenger.data.blobHashOf(r) }
                children.add(
                    MessageUi(
                        id = "$id#al$i",
                        text = "",
                        timestamp = createdAt,
                        isOutgoing = isOwn,
                        senderId = senderId,
                        isMedia = true,
                        mediaType = itType,
                        mediaRefJson = itRef,
                        mediaPosterB64 = itPoster,
                        localMediaPath = itLocal,
                        mediaDurationMs = itMeta.durationMs.takeIf { it > 0 },
                        // Its size (the auto-download limit and the ↓ label need it).
                        mediaSizeBytes = it.optLong("original_size", 0L).takeIf { s -> s > 0L },
                        mediaWidth = itMeta.width.takeIf { it > 0 },
                        mediaHeight = itMeta.height.takeIf { it > 0 },
                        mediaRotation = itMeta.rotation.takeIf { it != 0 },
                        mediaPending = albumPending,
                        // A part that is a file or a track is listed by its name.
                        mediaFilename = it.optString("filename", "").takeIf { n -> n.isNotBlank() },
                    )
                )
            }
        }
        return MessageUi(
            id = id,
            text = "",
            timestamp = createdAt,
            isOutgoing = isOwn,
            senderId = senderId,
            senderName = if (isOwn) labels.you else "peer…${senderId.takeLast(6)}",
            senderGradient = com.alphamessenger.ui.chat.avatarGradientFor(senderId),
            // While the album's blobs are still uploading show the "sending"
            // clock; once broadcast it follows the normal sent/read status.
            status = if (albumPending && isOwn && status == MessageStatus.SENT) MessageStatus.SENDING else status,
            isMedia = true,
            mediaType = "album",
            mediaCaption = content?.takeIf { it.isNotBlank() },
            mediaPending = albumPending,
            albumItems = children,
            forwardedFrom = if (isForwarded) "1" else null,
            isEdited = editedAt != null,
            isDeleted = isDeleted,
        )
    }

    val resolvedMediaType = mediaJson?.optString("media_type")?.takeIf { it.isNotBlank() } ?: "image"
    val resolvedFilename = mediaJson?.optString("filename")?.takeIf { it.isNotBlank() }
    // Voice notes are audio whose file is named "voice_…" (recorder convention);
    // flag them so replies/quotes can say "Voice message" instead of "Music".
    val isVoiceNote = resolvedMediaType == "audio" && (resolvedFilename?.startsWith("voice") == true)
    val resolvedSize = mediaJson?.optLong("original_size", 0L)?.takeIf { it > 0L }
    val resolvedLocalPath = mediaJson?.optString("local_path")?.takeIf { isOwn && it.isNotBlank() }
    val resolvedPending = mediaJson?.optBoolean("pending", false) ?: false
    val resolvedPoster = mediaJson?.optString("poster_b64")?.takeIf { it.isNotBlank() }
    // Sender-measured display metadata, carried inside the ref (the sender is the
    // only party that can measure it — the blob is E2EE).
    val resolvedMeta = mediaJson?.let { com.alphamessenger.ui.chat.AlbumItemMetadata.fromJson(it) }
    val shortName = if (isOwn) labels.you else {
        val tail = senderId.takeLast(6)
        "peer…$tail"
    }
    val initial = com.alphamessenger.ui.components.avatarInitial(shortName)
    // Deterministic gradient per sender — required so the bubble's mini
    // avatar renders at all (the block is gated on senderGradient != null).
    // ChatScreen later overrides the name/initial/photo with the real
    // profile from the contacts table when available.
    val gradient = com.alphamessenger.ui.chat.avatarGradientFor(senderId)
    // A big file's ref is megabytes of JSON: read its hash here, off the main thread, not on the first frame.
    if (isMediaMsg) mediaRef?.takeIf { it.length > 16 * 1024 }?.let { com.alphamessenger.data.blobHashOf(it) }

    return MessageUi(
        id = id,
        // For media, content holds the caption; show it under the media.
        text = if (isMediaMsg) "" else (content ?: ""),
        forwardedFrom = if (isForwarded) "1" else null,
        isMedia = isMediaMsg,
        mediaType = if (isMediaMsg) resolvedMediaType else null,
        isVoiceNote = isMediaMsg && isVoiceNote,
        isRoundVideo = isMediaMsg && resolvedMediaType == "video" && (resolvedFilename?.startsWith("round_") == true),
        isGif = isMediaMsg && resolvedMediaType == "video" &&
            (resolvedFilename?.startsWith(com.alphamessenger.ui.gif.GIF_PREFIX) == true),
        mediaRefJson = if (isMediaMsg) mediaRef else null,
        // A sticker's caption is its hidden tag, never shown.
        mediaCaption = if (isMediaMsg && stickerTag == null) content?.takeIf { it.isNotBlank() } else null,
        isSticker = isMediaMsg && resolvedMediaType == "image" &&
            (resolvedFilename?.startsWith(com.alphamessenger.ui.sticker.STICKER_PREFIX) == true),
        stickerEmoji = stickerTag?.emoji?.takeIf { it.isNotBlank() },
        stickerLink = stickerTag?.link,
        stickerBuiltin = stickerTag?.builtin,
        mediaFilename = if (isMediaMsg) resolvedFilename else null,
        mediaSizeBytes = if (isMediaMsg) resolvedSize else null,
        localMediaPath = if (isMediaMsg) resolvedLocalPath else null,
        mediaPending = isMediaMsg && resolvedPending,
        mediaPosterB64 = if (isMediaMsg) resolvedPoster else null,
        mediaDurationMs = resolvedMeta?.durationMs?.takeIf { it > 0 },
        mediaWidth = resolvedMeta?.width?.takeIf { it > 0 },
        mediaHeight = resolvedMeta?.height?.takeIf { it > 0 },
        mediaRotation = resolvedMeta?.rotation?.takeIf { it != 0 },
        mediaWaveform = resolvedMeta?.waveform?.takeIf { it.isNotBlank() },
        timestamp = createdAt,
        isOutgoing = isOwn,
        senderId = senderId,
        senderName = shortName,
        senderInitial = initial,
        senderGradient = gradient,
        // While a single media item is still queued/uploading show the "sending"
        // clock (same as albums); once delivered it follows sent/read status.
        status = if (isMediaMsg && resolvedPending && isOwn && status == MessageStatus.SENT) MessageStatus.SENDING else status,
        isVoice = false, // TODO: Implement voice message detection from msgType
        isViewOnce = isViewOnce,
        whisperSecs = (expiryPolicy as? ExpiryPolicy.Whisper)?.secs?.toInt(),
        isViewOnceConsumed = (isViewOnce || expiryPolicy is ExpiryPolicy.Whisper) && consumed,
        isEphemeral = isEphemeral,
        ephemeralExpiry = ephemeralExpiry,
        // Reply quote is resolved in foldToBubbles (needs the full message
        // list to look up the quoted text) — except a story reply, which
        // carries its quote with it.
        replyTo = storyReply?.first?.let { q ->
            ReplyInfo(
                senderName = labels.storyQuoteLabel,
                text = q.text.ifBlank { if (q.kind == "video") labels.storyVideo else labels.storyPhoto },
                posterB64 = q.thumbB64,
                isVideoQuote = q.kind == "video",
                kind = if (q.kind == "text") null else (if (q.kind == "video") "video" else "image"),
            )
        },
        isEdited = editedAt != null,
        isDeleted = isDeleted,
        reaction = null, // reactions come from a separate field if available
    )
}

/**
 * Fold a raw FFI message list into renderable bubbles.
 *
 * The Rust core stores Edit / Reaction / Delete as their own rows; the UI
 * must apply them to the target message and NOT show them as standalone
 * bubbles:
 *  - **Reaction** (replyTo = target id) → add emoji to target.reactions.
 *  - **Edit**     (same id as original) → replace content, mark edited.
 *  - **Delete**   (replyTo = target id) → mark target deleted (tombstone).
 *
 * Base message types (Text / Reply / Media / Disappearing) pass through.
 */
fun List<MessageInfo>.foldToBubbles(ownId: String, labels: MessageUiLabels): List<MessageUi> {
    val reactionsByTarget = HashMap<String, LinkedHashMap<String, Int>>()
    val myReactionsByTarget = HashMap<String, HashSet<String>>()
    val reactorsByTarget = HashMap<String, LinkedHashMap<String, MutableList<String>>>()
    val deletedTargets = HashSet<String>()
    val editsByTarget = HashMap<String, String>()

    // Index base messages by stable id so replies can resolve the quoted
    // text + sender.
    val baseById = HashMap<String, MessageInfo>()
    for (m in this) {
        if (m.msgType != MessageType.REACTION && m.msgType != MessageType.DELETE &&
            m.msgType != MessageType.EDIT
        ) baseById[m.id] = m
    }

    for (m in this) {
        when (m.msgType) {
            MessageType.REACTION -> {
                val target = m.replyTo ?: continue
                val emoji = m.content ?: continue
                val map = reactionsByTarget.getOrPut(target) { LinkedHashMap() }
                map[emoji] = (map[emoji] ?: 0) + 1
                reactorsByTarget.getOrPut(target) { LinkedHashMap() }.getOrPut(emoji) { ArrayList() }.add(m.senderId)
                if (m.senderId == ownId || m.senderId == "self") {
                    myReactionsByTarget.getOrPut(target) { HashSet() }.add(emoji)
                }
            }
            MessageType.DELETE -> { m.replyTo?.let { deletedTargets.add(it) } }
            MessageType.EDIT -> {
                val target = m.replyTo ?: continue
                m.content?.let { editsByTarget[target] = it }
            }
            else -> {}
        }
    }

    return this.asSequence()
        .filter { it.msgType != MessageType.REACTION && it.msgType != MessageType.DELETE && it.msgType != MessageType.EDIT }
 // Deleted messages disappear entirely — no tombstone.
        .filter { it.id !in deletedTargets && !it.isDeleted }
        .map { info ->
            var ui = info.toMessageUi(ownId, labels)
            editsByTarget[info.id]?.let {
                ui = if (ui.isMedia) ui.copy(mediaCaption = it.ifBlank { null }, isEdited = true)
                     else ui.copy(text = it, isEdited = true)
            }
            reactionsByTarget[info.id]?.let { ui = ui.copy(reactions = it) }
            myReactionsByTarget[info.id]?.let { ui = ui.copy(myReactions = it) }
            reactorsByTarget[info.id]?.let { ui = ui.copy(reactors = it) }
            // Resolve reply quote: replyTo is the quoted message's stable id.
            // Show its real text + a sensible sender label (works for both
            // REPLY type and a Text carrying reply_to).
            val quotedId = info.replyTo
            if (!quotedId.isNullOrBlank()) {
                val quoted = baseById[quotedId]

                // Pull a real thumbnail from the quoted media:
                // video poster (travels in the ref → works cross-device) or a
                // local image path (sender side / already-downloaded).
                var qPoster: String? = null
                var qThumb: String? = null
                var qIsVideo = false
                var qKind: String? = null
                var qAllVideo = false
                var qAllImage = false
                if (quoted != null && quoted.msgType == MessageType.MEDIA) {
                    val mj = quoted.mediaRef?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                    val isAlbum = mj?.optBoolean("album", false) == true
                    val node = if (isAlbum) mj?.optJSONArray("items")?.optJSONObject(0) else mj
                    if (node != null) {
                        val mt = node.optString("media_type", "image")
                        val fn = node.optString("filename", "")
                        qIsVideo = mt == "video"
                        qPoster = node.optString("poster_b64", "").takeIf { it.isNotBlank() }
                        qThumb = node.optString("local_path", "").takeIf { it.isNotBlank() && (mt == "image" || mt == "video") }
                            // Received pictures: the downloaded copy, found by its content hash.
                            ?: node.optString("hash", "").takeIf { it.isNotBlank() && mt == "image" }?.let { "hash:$it" }
                        val items = if (isAlbum) mj?.optJSONArray("items") else null
                        val types = items?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("media_type").orEmpty() } }.orEmpty()
                        qAllVideo = types.isNotEmpty() && types.all { it == "video" }
                        qAllImage = types.isNotEmpty() && types.all { it == "image" }
                        qKind = when {
                            isAlbum -> "album"
                            mt == "audio" && fn.startsWith("voice") -> "voice"
                            else -> mt
                        }
                    }
                }
                // Prefer the inline quote snippet carried in the reply
                // (mediaRef) — works even if the original isn't loaded.
                // Fall back to looking up the original's content.
                // Only a text reply carries one; a media reply's mediaRef is its own file.
                val inlineQuote = info.mediaRef?.takeIf { it.isNotBlank() && info.msgType == MessageType.REPLY }
                // A captioned photo/video is quoted by its caption ("🎬 caption"),
                // not just its type label.
                val quotedCaption = if (quoted?.msgType == MessageType.MEDIA) {
                    (editsByTarget[quotedId] ?: quoted.content)?.trim()?.takeIf { it.isNotBlank() }
                } else null
                // A sticker's caption is its hidden tag: it is quoted as "😀 Sticker"
                // (replies sent before this fix carry the tag too).
                fun stickerQuote(s: String?): String? = com.alphamessenger.ui.sticker.parseStickerCaption(s)
                    ?.let { tag -> listOf(tag.emoji, labels.sticker).filter { it.isNotBlank() }.joinToString(" ") }
                val quotedText = stickerQuote(quotedCaption) ?: stickerQuote(inlineQuote) ?: quotedCaption?.let { cap ->
                    val icon = inlineQuote?.substringBefore(' ')?.takeIf { it.isNotBlank() && it.first().code > 0x2000 }
                    if (icon != null) "$icon $cap" else cap
                }
                    ?: inlineQuote
                    ?: quoted?.content?.takeIf { it.isNotBlank() }
                    ?: qKind?.let { labels.mediaKind(it, qAllVideo, qAllImage) }
                    ?: labels.messageFallback
                val senderLabel = when {
                    quoted != null -> {
                        val own = quoted.senderId == ownId || quoted.senderId == "self"
                        if (own) labels.you else labels.peer
                    }
                    else -> labels.inReplyTo
                }
                ui = ui.copy(
                    replyTo = ReplyInfo(
                        senderName = senderLabel,
                        senderId = quoted?.senderId?.takeIf { it.isNotBlank() && it != "self" && it != ownId },
                        // A quote is one plain line: the formatting markers are not its words.
                        // A fragment of the message (not its start) is marked as a quote.
                        text = com.alphamessenger.ui.chat.text.Markup.plain(quotedText).take(80).let { t ->
                            val whole = quoted?.content?.let { com.alphamessenger.ui.chat.text.Markup.plain(it).trim() }.orEmpty()
                            val part = inlineQuote?.trim().orEmpty()
                            if (part.isNotEmpty() && whole.length > part.length && !whole.startsWith(part) && whole.contains(part)) "❝ $t" else t
                        },
                        posterB64 = qPoster,
                        thumbPath = qThumb,
                        isVideoQuote = qIsVideo,
                        kind = qKind,
                    ),
                    quotedMessageId = quotedId,
                )
            }
            ui
        }
        .toList()
}

// --- ═ ---
// Sender-color palette (matches ChatListScreen avatar palette so the
// same npub gets the same colour everywhere in the app).
// --- ═ ---

private val SenderPalette = listOf(
    Color(0xFFA1C4F2), // soft blue
    Color(0xFF93D4B7), // mint
    Color(0xFFF4B4A0), // peach
    Color(0xFFD8B6E0), // lilac
    Color(0xFFE5C57F), // warm yellow
    Color(0xFFF29CB8), // pink
    Color(0xFF8C81B0), // lavender
    Color(0xFF9BAA73), // olive
)

/** Deterministic colour for a sender id, used for group bubble headings
 *  and the mini avatar tile. */
fun senderColorFor(seed: String): Color {
    val h = seed.hashCode().let { if (it < 0) -it else it }
    return SenderPalette[h % SenderPalette.size]
}

// --- Gradient palette for avatar tiles (8 nice 2-stop gradients) ---
//
// Each pair is a (top-left, bottom-right) colour stop that the renderer
// blends across the circle / square. We picked vibrant but not garish
// combinations — same energy as messengers usually do default avatars.
private val AvatarGradientPalette = listOf(
    Pair(Color(0xFF6E8EFB), Color(0xFFA777E3)), // blue → violet
    Pair(Color(0xFF11998E), Color(0xFF38EF7D)), // teal → green
    Pair(Color(0xFFEB3349), Color(0xFFF45C43)), // red → orange
    Pair(Color(0xFFFF8008), Color(0xFFFFC837)), // orange → yellow
    Pair(Color(0xFFEC008C), Color(0xFFFC6767)), // magenta → coral
    Pair(Color(0xFF536976), Color(0xFF292E49)), // steel → indigo
    Pair(Color(0xFF8E2DE2), Color(0xFF4A00E0)), // purple → deep violet
    Pair(Color(0xFF00C9FF), Color(0xFF92FE9D)), // cyan → mint
)

/** Deterministic 2-color gradient for an avatar tile. */
fun avatarGradientFor(seed: String): Pair<Color, Color> {
    val h = seed.hashCode().let { if (it < 0) -it else it }
    return AvatarGradientPalette[h % AvatarGradientPalette.size]
}

// --- ═ ---
// Bubble grouping: walk the list and tag every message with
// isConsecutive / isLastInRun so the view layer can pick the right shape
// and decide whether to draw the sender header + mini avatar.
// --- ═ ---

fun List<MessageUi>.withRunFlags(): List<MessageUi> {
    if (isEmpty()) return this
    val out = ArrayList<MessageUi>(size)
    for (i in indices) {
        val msg = this[i]
        val prev = if (i > 0) this[i - 1] else null
        val next = if (i < size - 1) this[i + 1] else null
        // Same-sender + within 5 minutes (300s) of previous → consecutive.
        val isConsecutive = prev != null
            && prev.senderId == msg.senderId
            && secondsBetween(prev.timestamp, msg.timestamp) <= 300L
        val isLast = next == null
            || next.senderId != msg.senderId
            || secondsBetween(msg.timestamp, next.timestamp) > 300L
        out.add(msg.copy(isConsecutive = isConsecutive, isLastInRun = isLast))
    }
    return out
}

// --- ═ ---
// Album grouping (UI-only): collapse a run of consecutive photo/video
// messages from the same sender into a single album bubble. Nothing about
// the underlying messages/transport changes — each item is still its own
// message; this only affects how they're laid out in the chat.
// --- ═ ---

/** Mark outgoing messages still in the send queue as SENDING (clock). Everything
 *  not pending stays at its computed status (SENT/DELIVERED/READ). */
fun List<MessageUi>.applyPendingSending(pendingIds: Set<String>): List<MessageUi> {
    if (pendingIds.isEmpty()) return this
    return map {
        // Only set SENDING if the message is still at SENT (not yet delivered
        // or read). This prevents a stale pendingIds entry from reverting
        // DELIVERED/READ back to SENDING on reload.
        if (it.isOutgoing && it.status == MessageStatus.SENT && it.id in pendingIds)
            it.copy(status = MessageStatus.SENDING) else it
    }
}

/**
 * Marks the rows the core keeps as whispers, before albums are formed from the list.
 * A reply quoting a whisper says only that: no picture of it, no words of it.
 */
fun List<MessageUi>.markWhispers(ids: Set<String>, whisperLabel: String = ""): List<MessageUi> =
    if (ids.isEmpty()) this else map { m ->
        var r = if (m.id in ids && !m.isWhisper) m.copy(isWhisper = true) else m
        val q = r.replyTo
        if (q != null && r.quotedMessageId in ids) {
            r = r.copy(replyTo = q.copy(text = whisperLabel, posterB64 = null, thumbPath = null, isVideoQuote = false, kind = null))
        }
        r
    }

/** Opened once or under a time limit: shown only in its own row, never alongside other media. */
val MessageUi.isOneTime: Boolean get() = isWhisper || whisperSecs != null || isViewOnce

fun List<MessageUi>.groupAlbums(): List<MessageUi> {
    if (isEmpty()) return this
    fun groupable(m: MessageUi) =
        m.isMedia && (m.mediaType == "image" || m.mediaType == "video") && !m.isDeleted && !m.isOneTime &&
            !m.isGif && !m.isRoundVideo && !m.isSticker // a GIF, a circle or a sticker always stands alone
    val out = ArrayList<MessageUi>(size)
    var i = 0
    while (i < size) {
        val head = this[i]
        if (!groupable(head)) { out.add(head); i++; continue }
        val group = arrayListOf(head)
        var j = i + 1
        while (j < size) {
            val n = this[j]
            if (!groupable(n) || n.senderId != head.senderId) break
            // Only items from the SAME send batch (persisted within ~1s) should
            // merge. A tight window keeps separate manual sends as their own
            // bubbles instead of swallowing everything into the first album.
            if (secondsBetween(group.last().timestamp, n.timestamp) > 8L) break
            group.add(n); j++
        }
        if (group.size >= 2) {
            out.add(head.copy(albumItems = group.toList()))
            for (k in 1 until group.size) out.add(group[k].copy(hiddenInAlbum = true))
        } else {
            out.add(head)
        }
        i = j
    }
    return out
}

private fun secondsBetween(a: String, b: String): Long = try {
    val ta = java.time.OffsetDateTime.parse(a).toEpochSecond()
    val tb = java.time.OffsetDateTime.parse(b).toEpochSecond()
    kotlin.math.abs(tb - ta)
} catch (_: Throwable) {
    Long.MAX_VALUE
}

/** The file name to SHOW: older sends carried a "send_<timestamp>_" copy prefix. */
fun displayFileName(raw: String?): String? =
    raw?.replace(Regex("^send_\\d{10,}_"), "")

/** Reasoning of an assistant reply; [live] while the model still thinks. */
data class AssistantThinkingUi(
    val text: String,
    val seconds: Int,
    val live: Boolean = false,
    val connecting: Boolean = false,
    /** The council member it belongs to; empty for a single assistant. */
    val agent: String = "",
)
