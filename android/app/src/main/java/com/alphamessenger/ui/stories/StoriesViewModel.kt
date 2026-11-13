package com.alphamessenger.ui.stories

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphamessenger.data.AlphaCoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One story (ours or a contact's). Times are unix seconds. */
data class Story(
    val id: String,
    val chatId: String,
    val authorSid: String,
    val kind: String,          // image | video | text
    val mediaRef: String,
    val text: String,
    val bg: List<Long>,        // text story background, two ARGB colours
    val font: String = "sans", // text story font: sans | serif | mono | hand
    val stickers: List<StorySticker> = emptyList(),
    val caption: String,
    val createdAt: Long,
    val expiresAt: Long,
    val allowReplies: Boolean,
    val allowScreenshots: Boolean,
    val seen: Boolean,
    val myReaction: String = "",
    val viewers: List<StoryViewer> = emptyList(),
)

data class StoryViewer(val sid: String, val chatId: String, val at: Long, val reaction: String, val shot: Boolean = false)

/** Everything one contact currently shows, oldest first (the order it plays in). */
data class StoryAuthor(
    val chatId: String,
    val sid: String,
    val name: String,
    val avatarPath: String,
    val stories: List<Story>,
) {
    val unseen: Boolean get() = stories.any { !it.seen }
}

/** The ring an avatar wears: how many stories, and whether any is new. */
data class StoryRing(val count: Int, val unseen: Boolean)

/** A person a story can be shown to (one of our direct chats). */
data class StoryContact(val chatId: String, val name: String, val avatarPath: String)

/** Who sees a story. Close friends and the exclusions live on this phone only. */
enum class StoryAudience { CONTACTS, CLOSE, EXCEPT, ONLY }

/** Any change to stories anywhere in the app: every screen re-reads. */
object StoriesBus {
    val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
}

@HiltViewModel
class StoriesViewModel @Inject constructor(
    val repository: AlphaCoreRepository,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    data class UiState(
        val mine: List<Story> = emptyList(),
        val authors: List<StoryAuthor> = emptyList(),
        val rings: Map<String, StoryRing> = emptyMap(),
        val contacts: List<StoryContact> = emptyList(),
        val publishing: Boolean = false,
        val myName: String = "",
        val myAvatar: String = "",
        /** The first read is done (a viewer must not close on "nothing yet"). */
        val loaded: Boolean = false,
        /** Gradients the user made, newest first. */
        val customBgs: List<List<Long>> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val prefs = ctx.getSharedPreferences("stories_audience", Context.MODE_PRIVATE)
    private val names = HashMap<String, Pair<String, String>>()

    init {
        refresh()
        viewModelScope.launch { repository.chatMetadataUpdated.collect { refresh() } }
        viewModelScope.launch { StoriesBus.changed.collect { refresh() } }
        // Expiry is local time: re-read once a minute so finished stories go.
        viewModelScope.launch { while (true) { delay(60_000); refresh() } }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val json = repository.storiesJson()
            val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@launch
            val mine = parse(root.optJSONArray("mine")).sortedBy { it.createdAt }
            val others = parse(root.optJSONArray("others"))
            val authors = others.groupBy { it.chatId }.map { (chatId, list) ->
                val sid = list.first().authorSid
                val (name, avatar) = nameOf(sid)
                StoryAuthor(chatId, sid, name, avatar, list.sortedBy { it.createdAt })
            }.sortedWith(compareByDescending<StoryAuthor> { it.unseen }.thenByDescending { a -> a.stories.maxOf { it.createdAt } })
            StoryShotNotifier.check(ctx, repository)
            // New stories start downloading quietly right away, so they open at once.
            prefetch(authors.filter { it.unseen }.flatMap { a -> a.stories.filter { !it.seen } })
            val me = runCatching { repository.getMyProfile() }.getOrNull()
            _state.value = _state.value.copy(
                mine = mine,
                authors = authors,
                rings = authors.associate { it.chatId to StoryRing(it.stories.size, it.unseen) },
                myName = me?.displayName.orEmpty(),
                myAvatar = me?.avatarPath.orEmpty(),
                loaded = true,
                customBgs = loadCustomBgs(),
            )
        }
    }

    private fun parse(a: org.json.JSONArray?): List<Story> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val viewers = o.optJSONArray("viewers")?.let { v ->
                (0 until v.length()).mapNotNull { j ->
                    v.optJSONObject(j)?.let { w ->
                        StoryViewer(w.optString("sid"), w.optString("chat_id"), w.optLong("at"), w.optString("reaction"), w.optBoolean("shot"))
                    }
                }
            }.orEmpty()
            Story(
                id = o.optString("id"),
                chatId = o.optString("chat_id"),
                authorSid = o.optString("author_sid"),
                kind = o.optString("kind"),
                mediaRef = o.optString("media_ref"),
                text = o.optString("text"),
                bg = parseBg(o.optString("bg").substringBefore('|')),
                font = o.optString("bg").substringAfter('|', "sans"),
                stickers = parseStickers(o.optString("stickers")),
                caption = o.optString("caption"),
                createdAt = o.optLong("created_at"),
                expiresAt = o.optLong("expires_at"),
                allowReplies = o.optBoolean("allow_replies", true),
                allowScreenshots = o.optBoolean("allow_screenshots", false),
                seen = o.optBoolean("seen", false),
                myReaction = o.optString("my_reaction"),
                viewers = viewers,
            )
        }
    }

    private suspend fun nameOf(sid: String): Pair<String, String> {
        names[sid]?.let { return it }
        val p = runCatching { repository.getPeerProfile(sid) }.getOrNull()
        val pair = (p?.displayName.orEmpty()) to (p?.avatarPath.orEmpty())
        if (pair.first.isNotBlank()) names[sid] = pair
        return pair
    }

    /** The people a story can go to: our direct chats. */
    fun loadContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            val own = runCatching { repository.ownNpub() }.getOrDefault("")
            // My contacts — the same people the privacy rules mean.
            val contacts = runCatching { repository.contacts() }.getOrDefault(emptyMap())
            val chats = runCatching { repository.listChats() }.getOrDefault(emptyList())
                .filter { it.chatType != "group" && it.id in contacts && it.archivedAt == 0uL }
            val list = chats.map { c ->
                val sid = runCatching { repository.resolvePeerSenderId(c.id, own) }.getOrDefault("")
                val (n, a) = if (sid.isNotBlank()) nameOf(sid) else ("" to "")
                StoryContact(c.id, n.ifBlank { c.name.orEmpty() }, a)
            }
            _state.value = _state.value.copy(contacts = list)
        }
    }

    private fun loadCustomBgs(): List<List<Long>> =
        prefs.getString("custom_bgs", "").orEmpty().split(';').filter { it.isNotBlank() }.map { parseBg(it) }

    /** Keep a gradient the user made (at most 12, newest first). */
    fun saveCustomBg(bg: List<Long>) {
        val list = (listOf(bg) + loadCustomBgs().filter { it != bg }).take(12)
        prefs.edit().putString("custom_bgs", list.joinToString(";") { g -> g.joinToString(",") { "#%08X".format(it) } }).apply()
        _state.value = _state.value.copy(customBgs = list)
    }

    fun deleteCustomBg(bg: List<Long>) {
        val list = loadCustomBgs().filter { it != bg }
        prefs.edit().putString("custom_bgs", list.joinToString(";") { g -> g.joinToString(",") { "#%08X".format(it) } }).apply()
        _state.value = _state.value.copy(customBgs = list)
    }

    fun savedList(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty()
    fun saveList(key: String, ids: Set<String>) = prefs.edit().putStringSet(key, ids).apply()

    /** The chats a story goes to for [audience] ([picked] for ONLY/CLOSE/EXCEPT edits). */
    fun audienceChats(audience: StoryAudience, picked: Set<String>): List<String> {
        val all = _state.value.contacts.map { it.chatId }
        return when (audience) {
            StoryAudience.CONTACTS -> all
            StoryAudience.CLOSE -> all.filter { it in picked }
            StoryAudience.EXCEPT -> all.filter { it !in picked }
            StoryAudience.ONLY -> all.filter { it in picked }
        }
    }

    /**
     * Publish: the media (already cleaned of metadata when it was copied) is
     * uploaded ONCE; its encrypted ref goes to every chosen contact in their
     * own encrypted chat with us.
     */
    fun publish(
        kind: String,
        localPath: String?,
        text: String,
        bg: List<Long>,
        font: String,
        caption: String,
        ttlHours: Int,
        chats: List<String>,
        allowReplies: Boolean,
        allowScreenshots: Boolean,
        stickers: List<StorySticker>,
        audience: StoryAudience,
        picked: Set<String>,
        onDone: (Boolean) -> Unit,
    ) {
        if (chats.isEmpty()) { onDone(false); return }
        _state.value = _state.value.copy(publishing = true)
        viewModelScope.launch(Dispatchers.IO) {
            // A story video is made light first (≤1280 px, ~2.5 Mbit/s, ≤60 s).
            val upload = if (kind == "video" && localPath != null)
                StoryVideoCompressor.prepare(ctx, java.io.File(localPath)).absolutePath else localPath
            val ref = if (kind == "text" || upload == null) "" else
                repository.uploadBlobRefOnly(upload, kind, null, "story-${System.currentTimeMillis()}").orEmpty()
            val ok = (kind == "text" || ref.isNotBlank()) && runCatching {
                val params = org.json.JSONObject()
                    .put("kind", kind).put("media_ref", ref).put("text", text)
                    .put("bg", bg.joinToString(",") { "#%08X".format(it) } + "|" + font)
                    .put("caption", caption).put("ttl_secs", ttlHours * 3600)
                    .put("allow_replies", allowReplies).put("allow_screenshots", allowScreenshots)
                    .put("chats", org.json.JSONArray(chats))
                    .put("stickers", stickersToJson(stickers))
                    // Who it is for: "all contacts" (or all but some) also reaches
                    // people who become contacts while it is up.
                    .put("audience", audience.name.lowercase())
                    .put("except", org.json.JSONArray(if (audience == StoryAudience.EXCEPT) picked.toList() else emptyList<String>()))
                repository.publishStory(params.toString())
            }.isSuccess
            _state.value = _state.value.copy(publishing = false)
            if (ok) StoriesBus.changed.tryEmit(Unit)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun markSeen(story: Story) {
        if (story.seen) return
        viewModelScope.launch(Dispatchers.IO) { repository.storySeen(story.id, ""); StoriesBus.changed.tryEmit(Unit) }
    }

    /** Put [emoji] on the story, or take our reaction back when it is already there. */
    fun react(story: Story, emoji: String) {
        val current = currentReaction(story.id)
        viewModelScope.launch(Dispatchers.IO) {
            repository.storySeen(story.id, if (current == emoji) "-" else emoji)
            StoriesBus.changed.tryEmit(Unit)
        }
    }

    /** Our reaction on a story as it is NOW (the player holds an older copy). */
    fun currentReaction(id: String): String =
        _state.value.authors.asSequence().flatMap { it.stories.asSequence() }.firstOrNull { it.id == id }?.myReaction.orEmpty()

    private val prefetched = java.util.Collections.synchronizedSet(HashSet<String>())

    /** We took a screenshot of [story] (Android 14+ tells us): its author learns it. */
    fun screenshot(story: Story) {
        viewModelScope.launch(Dispatchers.IO) { repository.storyScreenshot(story.id) }
    }


    /** Download these stories' media in the background, one after another. */
    fun prefetch(stories: List<Story>) {
        val todo = stories.filter { it.kind != "text" && it.mediaRef.isNotBlank() && prefetched.add(it.id) }
        if (todo.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (st in todo) runCatching { repository.downloadMediaToCache(st.mediaRef) }
        }
    }

    /**
     * A reply goes to the author's chat as an ordinary encrypted message that
     * quotes the story: its kind, a text snippet and a tiny thumbnail (made
     * here from the story we are looking at), so the bubble shows what was
     * answered.
     */
    fun reply(story: Story, text: String, mediaPath: String?) {
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val thumb = when (story.kind) {
                "image" -> mediaPath?.let { thumbOfImage(it) }
                "video" -> mediaPath?.let { thumbOfVideo(it) }
                else -> null
            }
            val quote = com.alphamessenger.ui.chat.StoryQuote(story.kind, if (story.kind == "text") story.text else story.caption, thumb)
            runCatching { repository.sendText(story.chatId, com.alphamessenger.ui.chat.buildStoryReply(quote, body)) }
        }
    }

    private fun thumbOfImage(path: String): String? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        com.alphamessenger.data.MediaVault.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 192) sample *= 2
        val bmp = com.alphamessenger.data.MediaVault.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        jpegB64(bmp!!)
    }.getOrNull()

    private fun thumbOfVideo(path: String): String? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try { com.alphamessenger.data.MediaVault.setSource(r, path); r.getFrameAtTime(0)?.let { jpegB64(it) } } finally { r.release() }
    }.getOrNull()

    /** A ≤96 px JPEG, base64 — a few KB in the message. */
    private fun jpegB64(src: android.graphics.Bitmap): String {
        val scale = 96f / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun deleteMine(story: Story) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteMyStory(story.id) }
            StoriesBus.changed.tryEmit(Unit)
        }
    }

    suspend fun mediaPath(ref: String): String? = repository.downloadMediaToCache(ref)

    companion object {
        /** Text-story backgrounds (pairs of colours). */
        val BACKGROUNDS: List<List<Long>> = listOf(
            listOf(0xFF6C5CE7, 0xFFFF4FA3), listOf(0xFF14B8A6, 0xFF3F8FD9), listOf(0xFFFF8A34, 0xFFE0567A),
            listOf(0xFF1B3A5C, 0xFFC9785A), listOf(0xFF2BB3A3, 0xFF7A5CE0), listOf(0xFF222831, 0xFF5B6CF0),
            listOf(0xFFF7971E, 0xFFFFD200), listOf(0xFF00C6FF, 0xFF0072FF), listOf(0xFFEE0979, 0xFFFF6A00),
            listOf(0xFF11998E, 0xFF38EF7D), listOf(0xFF8E2DE2, 0xFF4A00E0), listOf(0xFF434343, 0xFF000000),
        )
        val FONTS = listOf("sans", "serif", "mono", "hand")

        fun fontFamily(font: String): androidx.compose.ui.text.font.FontFamily = when (font) {
            "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
            "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
            "hand" -> androidx.compose.ui.text.font.FontFamily.Cursive
            else -> androidx.compose.ui.text.font.FontFamily.SansSerif
        }

        fun parseBg(raw: String): List<Long> = raw.split(',').mapNotNull { part ->
            val h = part.trim().removePrefix("#")
            h.toLongOrNull(16)?.let { v -> if (h.length <= 6) v or 0xFF000000 else v }
        }.takeIf { it.size == 2 } ?: BACKGROUNDS.first()
    }
}
