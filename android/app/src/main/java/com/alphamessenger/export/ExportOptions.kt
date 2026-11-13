package com.alphamessenger.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** The folder a kind of attachment goes to inside the export. */
enum class MediaKind(val dir: String) {
    PHOTO("photos"), VIDEO("videos"), VOICE("voice_messages"), ROUND("video_messages"),
    STICKER("stickers"), GIF("gifs"), FILE("files"),
}

/**
 * What an export takes: one chat ([chatId]) or the whole profile by kinds of
 * chats; which attachments and up to what size; which period; which format.
 */
data class ExportOptions(
    val chatId: String? = null,
    val profile: Boolean = true,
    val contacts: Boolean = true,
    val personal: Boolean = true,
    val groups: Boolean = true,
    val channels: Boolean = true,
    val saved: Boolean = true,
    val media: Set<MediaKind> = MediaKind.entries.toSet(),
    /** Larger attachments stay out; 0 = no limit. */
    val maxFileMb: Int = 0,
    /** Unix seconds, 0 = open. */
    val sinceSec: Long = 0,
    val untilSec: Long = 0,
    val html: Boolean = true,
    val json: Boolean = false,
) {
    val singleChat: Boolean get() = chatId != null

    fun takesAnything(): Boolean = (html || json) &&
        (singleChat || profile || contacts || personal || groups || channels || saved)

    fun toJson(): String = JSONObject().apply {
        put("chat", chatId ?: "")
        put("profile", profile); put("contacts", contacts)
        put("personal", personal); put("groups", groups); put("channels", channels); put("saved", saved)
        put("media", media.joinToString(",") { it.name })
        put("max_mb", maxFileMb); put("since", sinceSec); put("until", untilSec)
        put("html", html); put("json", json)
    }.toString()

    companion object {
        fun fromJson(s: String): ExportOptions {
            val o = JSONObject(s)
            return ExportOptions(
                chatId = o.optString("chat").takeIf { it.isNotBlank() },
                profile = o.optBoolean("profile"), contacts = o.optBoolean("contacts"),
                personal = o.optBoolean("personal"), groups = o.optBoolean("groups"),
                channels = o.optBoolean("channels"), saved = o.optBoolean("saved"),
                media = o.optString("media").split(',').mapNotNull { n -> MediaKind.entries.firstOrNull { it.name == n } }.toSet(),
                maxFileMb = o.optInt("max_mb"), sinceSec = o.optLong("since"), untilSec = o.optLong("until"),
                html = o.optBoolean("html", true), json = o.optBoolean("json"),
            )
        }
    }
}

/** Where the running (or last) export stands; the screen and the notification follow it. */
object ExportProgress {
    sealed interface State {
        data object Idle : State
        data class Running(val chat: String, val chatIndex: Int, val chats: Int, val messages: Int, val files: Int) : State
        data class Done(val uri: String, val chats: Int, val messages: Int, val files: Int, val missing: Int) : State
        data class Failed(val reason: String) : State
        data object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = _state

    internal fun set(s: State) { _state.value = s }

    /** The screen was left after a result: the next visit starts clean. */
    fun acknowledge() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }
}
