package com.alphamessenger.ui.channel

import org.json.JSONObject

/**
 * Channels: a group where only the owner and admins post; subscribers read,
 * react and (when allowed) comment. Every member's core enforces who may
 * write, so this file only holds the shared model and the comment format.
 */
data class ChannelCfg(
    val on: Boolean = false,
    /** Subscribers may comment under posts. */
    val comments: Boolean = true,
    /** Posts can't be forwarded, copied or screenshotted by subscribers. */
    val protect: Boolean = false,
    /** "link" = anyone with the invite link joins; "request" = the owner approves. */
    val join: String = JOIN_LINK,
    /** Seconds between two comments of one person (0 = no limit). */
    val slow: Int = 0,
    /** What comments may carry: text, link, photo, video, voice, file → allowed. */
    val allow: Map<String, Boolean> = KINDS.associateWith { true },
    /** Posts whose comments are closed (the [comments] switch is the default). */
    val closed: List<String> = emptyList(),
    /** The channel hides its admins: posts and their replies come from the
     *  channel itself. Decided once at creation; the core keeps it true to
     *  how the channel was made. */
    val anon: Boolean = false,
    /** Newcomers get the earlier messages; null = the default (channels yes, groups no). */
    val history: Boolean? = null,
    /** Group calls allowed here, and with video. */
    val calls: Boolean = true,
    val videoCalls: Boolean = true,
    /** We own it under a persona: only then can the owner be hidden (read only). */
    val persona: Boolean = false,
) {
    /** What newcomers get, with the default applied. */
    val historyOn: Boolean get() = history ?: on

    fun toJson(): String = JSONObject()
        .put("on", on).put("comments", comments).put("protect", protect).put("join", join).put("anon", anon)
        .put("slow", slow)
        .put("allow", JSONObject().apply { KINDS.forEach { put(it, allow[it] ?: true) } })
        .put("closed", org.json.JSONArray(closed.take(500)))
        .apply { history?.let { put("history", it) } }
        .put("calls", calls).put("video_calls", videoCalls)
        .toString()

    /** Comments are open under this post. */
    fun commentsOpen(postId: String): Boolean = comments && postId !in closed

    companion object {
        const val JOIN_LINK = "link"
        const val JOIN_REQUEST = "request"
        /** Same set as the core's COMMENT_KINDS. */
        val KINDS = listOf("text", "link", "photo", "video", "voice", "file")

        fun parse(json: String?): ChannelCfg = runCatching {
            val o = JSONObject(json ?: "{}")
            ChannelCfg(
                on = o.optBoolean("on", false),
                comments = o.optBoolean("comments", true),
                protect = o.optBoolean("protect", false),
                join = if (o.optString("join") == JOIN_REQUEST) JOIN_REQUEST else JOIN_LINK,
                slow = o.optInt("slow", 0),
                allow = o.optJSONObject("allow").let { a -> KINDS.associateWith { a?.optBoolean(it, true) ?: true } },
                closed = o.optJSONArray("closed")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList(),
                anon = o.optBoolean("anon", false),
                history = if (o.has("history")) o.optBoolean("history") else null,
                calls = o.optBoolean("calls", true),
                videoCalls = o.optBoolean("video_calls", true),
                persona = o.optBoolean("persona", false),
            )
        }.getOrDefault(ChannelCfg())
    }
}

/**
 * The discussion under one post, as the feed shows it: how many comments,
 * when the newest came and who wrote last (up to three sender ids). Comments
 * themselves are ordinary messages of the post's thread (core `get_thread`).
 */
data class ThreadSummary(
    val postId: String,
    val count: Int,
    /** Of them, our own — never "new" for us. */
    val own: Int = 0,
    val lastAt: String,
    val senders: List<String>,
    /** Comments by others this device has not seen yet (see [DiscussionSeen]). */
    val unread: Int = 0,
) {
    /** Comments by others: what "new" is counted from. */
    val others: Int get() = (count - own).coerceAtLeast(0)

    companion object {
        fun parse(json: String?): Map<String, ThreadSummary> = runCatching {
            val a = org.json.JSONArray(json ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val s = o.optJSONArray("senders")
                ThreadSummary(
                    postId = o.optString("post"),
                    count = o.optInt("count"),
                    own = o.optInt("own"),
                    lastAt = o.optString("last_at"),
                    senders = s?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
                )
            }.filter { it.postId.isNotEmpty() }.associateBy { it.postId }
        }.getOrDefault(emptyMap())
    }
}

/**
 * The core's answer to "may I write here?" — the SAME rule every receiver
 * applies and our own send path enforces (core `write_status`). The screen
 * never decides this on its own.
 */
data class WriteStatus(val ok: Boolean, val reason: String = "", val value: Long = 0) {
    companion object {
        val OK = WriteStatus(true)

        fun parse(json: String?): WriteStatus = runCatching {
            val o = JSONObject(json ?: "{}")
            WriteStatus(o.optBoolean("ok", true), o.optString("reason"), o.optLong("value"))
        }.getOrDefault(OK)

        /** A send the core refused: its error reads `write_denied:<reason>:<value>`. */
        fun fromError(message: String?): WriteStatus? {
            val m = message ?: return null
            val i = m.indexOf("write_denied:")
            if (i < 0) return null
            val parts = m.substring(i + "write_denied:".length).split(':')
            return WriteStatus(false, parts.getOrNull(0).orEmpty(), parts.getOrNull(1)?.trim()?.takeWhile { it.isDigit() }?.toLongOrNull() ?: 0)
        }
    }

    /** What to tell the user, in their language. */
    fun text(): String {
        val s = com.alphamessenger.util.AppStrings
        return when (reason) {
            "muted" -> if (value > System.currentTimeMillis() / 1000 + 50L * 365 * 24 * 3600) s.get(com.alphamessenger.R.string.write_muted_forever)
                else s.get(com.alphamessenger.R.string.write_muted_until, untilLabel(value))
            "post_closed" -> s.get(com.alphamessenger.R.string.cmt_closed)
            "comments_off" -> s.get(com.alphamessenger.R.string.write_comments_off)
            "kind_not_allowed" -> s.get(com.alphamessenger.R.string.write_kind_not_allowed)
            "slow_mode" -> s.get(com.alphamessenger.R.string.cmt_slow_wait, "%d:%02d".format(value / 60, value % 60))
            "unknown_sender" -> s.get(com.alphamessenger.R.string.write_unknown_sender)
            "chat_closed" -> s.get(com.alphamessenger.R.string.write_chat_closed, untilLabel(value))
            else -> s.get(com.alphamessenger.R.string.write_admins_only)
        }
    }
}

/** "18:00" today, "12.10 18:00" another day. */
private fun untilLabel(unix: Long): String {
    val z = java.time.Instant.ofEpochSecond(unix).atZone(java.time.ZoneId.systemDefault())
    return if (z.toLocalDate() == java.time.LocalDate.now()) "%02d:%02d".format(z.hour, z.minute)
    else "%02d.%02d %02d:%02d".format(z.dayOfMonth, z.monthValue, z.hour, z.minute)
}

/**
 * The last known settings of each channel, kept for the process. Opening a channel
 * reads them on the first frame: its posts stand on their own side from the start,
 * not on ours until the settings arrive from the core.
 */
object ChannelCfgCache {
    private val cfgs = java.util.concurrent.ConcurrentHashMap<String, ChannelCfg>()
    fun put(chatId: String, cfg: ChannelCfg) { cfgs[chatId] = cfg }
    fun get(chatId: String): ChannelCfg? = cfgs[chatId]
}
