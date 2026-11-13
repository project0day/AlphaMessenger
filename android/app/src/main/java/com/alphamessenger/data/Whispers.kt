package com.alphamessenger.data

/** What the chat shows about a whisper (never its secret). */
data class WhisperMeta(
    val messageId: String,
    val outgoing: Boolean,
    /** "text" or the media type. */
    val kind: String,
    val secs: Int,
    val burned: Boolean,
    val usedSecs: Int?,
    val shot: Boolean,
    /** Burned unread (not opened in time). */
    val expired: Boolean,
    /** A timed view: the whole media for [secs] seconds (0 = until closed). */
    val glance: Boolean = false,
)

/** The secret of an incoming whisper, while it is still there. */
data class WhisperSecret(
    val kind: String,
    val body: String?,
    val mediaRef: String?,
    val secs: Int,
    val glance: Boolean = false,
)
