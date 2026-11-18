package com.alphamessenger.assistant

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One configured AI service: where it is, which model, and what it may do. */
data class AssistantProfile(
    val id: String,
    val name: String,
    val endpoint: String,
    val model: String,
    val systemPrompt: String = "",
    /** Model for speech → text; empty = voice notes are not transcribed. */
    val sttModel: String = "",
    val perms: Set<AssistantPerm> = setOf(AssistantPerm.SHARE),
    /** Pictures (/images/generations); empty = it cannot draw. */
    val imageModel: String = "",
    /** Text → speech (/audio/speech) and its voice; empty = it cannot speak. */
    val ttsModel: String = "",
    val ttsVoice: String = "alloy",
    /** Video, for services that will offer it; kept so the profile is ready. */
    val videoModel: String = "",
    /** A council: these profiles answer together. Empty = a single assistant. */
    val members: List<String> = emptyList(),
    /** Council: every member answers on its own; else one common answer. */
    val eachOwn: Boolean = false,
    /** It keeps facts about the user from one conversation to the next. */
    val memory: Boolean = false,
) {
    val chatId: String get() = CHAT_PREFIX + id
    val isCouncil: Boolean get() = members.isNotEmpty()

    companion object {
        /** Mirrors the core: only chats it created on this phone carry it. */
        const val CHAT_PREFIX = "assistant:"
    }
}

/** What a profile may do. Everything but SHARE starts off. */
enum class AssistantPerm { SHARE, CONNECTION, WEB, THEMES }

/** One line of the assistant's journal: facts only, never message text. */
data class JournalEntry(val at: Long, val profile: String, val kind: Kind, val title: String, val detail: String) {
    enum class Kind { ACT, WEB, ERR, PERM }
}

/**
 * Profiles, keys and the journal, in encrypted preferences. They leave the phone
 * only inside our own encrypted backup (the system backup is off). Keys live
 * apart from profiles and are read only to make a request.
 */
object AssistantStore {
    private const val FILE = "alpha_assistant"
    private const val K_PROFILES = "profiles"
    private const val K_JOURNAL = "journal"
    private const val K_APPLIED = "applied"
    const val MEMORY_MAX = 60
    private const val JOURNAL_MAX = 300
    private const val JOURNAL_DAYS = 30L

    @Volatile private var prefs: SharedPreferences? = null
    private val _profiles = MutableStateFlow<List<AssistantProfile>>(emptyList())
    val profiles: StateFlow<List<AssistantProfile>> = _profiles.asStateFlow()
    private val _journal = MutableStateFlow<List<JournalEntry>>(emptyList())
    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()
    private val _applied = MutableStateFlow<Set<String>>(emptySet())
    /** Offers (relay plans) the user already applied, by message id. */
    val applied: StateFlow<Set<String>> = _applied.asStateFlow()

    private val _memory = MutableStateFlow<Map<String, List<MemoryFact>>>(emptyMap())
    /** What each assistant remembers about the user, by profile id (oldest first). */
    val memory: StateFlow<Map<String, List<MemoryFact>>> = _memory.asStateFlow()

    fun memoryOf(id: String): List<MemoryFact> = _memory.value[id].orEmpty()

    /** False when it is already known or the memory is full. */
    fun remember(id: String, text: String): Boolean {
        val now = memoryOf(id)
        if (now.size >= MEMORY_MAX || now.any { it.text.equals(text, ignoreCase = true) }) return false
        putMemory(id, now + MemoryFact(text, System.currentTimeMillis()))
        return true
    }

    /** Removes the facts that contain [words]; returns how many. */
    fun forget(id: String, words: String): Int {
        val now = memoryOf(id)
        val keep = now.filterNot { it.text.contains(words, ignoreCase = true) }
        if (keep.size != now.size) putMemory(id, keep)
        return now.size - keep.size
    }

    fun forgetAt(id: String, at: Long) = putMemory(id, memoryOf(id).filterNot { it.at == at })

    fun clearMemory(id: String) = putMemory(id, emptyList())

    private fun putMemory(id: String, facts: List<MemoryFact>) {
        _memory.value = _memory.value + (id to facts)
        prefs?.edit()?.apply {
            if (facts.isEmpty()) remove("mem:$id")
            else putString("mem:$id", JSONArray(facts.map { JSONObject().put("t", it.text).put("at", it.at) }).toString())
        }?.apply()
    }

    private fun readMemory(id: String): List<MemoryFact> = runCatching {
        val a = JSONArray(prefs?.getString("mem:$id", null) ?: return emptyList())
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { MemoryFact(it.optString("t"), it.optLong("at")) } }.filter { it.text.isNotBlank() }
    }.getOrDefault(emptyList())

    fun markApplied(messageId: String) {
        val next = (_applied.value.toList() + messageId).distinct().takeLast(200)
        _applied.value = next.toSet()
        prefs?.edit()?.putString(K_APPLIED, JSONArray(next).toString())?.apply()
    }

    fun init(ctx: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = runCatching {
                val mk = androidx.security.crypto.MasterKey.Builder(ctx.applicationContext)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
                androidx.security.crypto.EncryptedSharedPreferences.create(
                    ctx.applicationContext, FILE, mk,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrNull()
            _profiles.value = readProfiles()
            _journal.value = readJournal()
            _memory.value = _profiles.value.associate { it.id to readMemory(it.id) }
            _applied.value = runCatching {
                val a = JSONArray(prefs?.getString(K_APPLIED, null) ?: "[]")
                (0 until a.length()).map { a.optString(it) }.toSet()
            }.getOrDefault(emptySet())
        }
    }

    /** Everything stored here (profiles, keys, memory) for the encrypted backup. */
    fun allPrefs(): Map<String, *>? = prefs?.all

    /** Restore: replaces everything stored here; read again on the next start. */
    fun replacePrefs(fill: (SharedPreferences.Editor) -> Unit) {
        prefs?.edit()?.clear()?.also(fill)?.commit()
    }

    fun profile(id: String): AssistantProfile? = _profiles.value.firstOrNull { it.id == id }
    fun profileOfChat(chatId: String): AssistantProfile? =
        // A past conversation is "<profile chat>~<id>".
        chatId.removePrefix(AssistantProfile.CHAT_PREFIX).substringBefore('~')
            .takeIf { chatId.startsWith(AssistantProfile.CHAT_PREFIX) }?.let(::profile)

    fun apiKey(id: String): String = prefs?.getString("key:$id", null).orEmpty()

    /** One web-search API key for every assistant; empty = the keyless search. */
    fun searchKey(): String = prefs?.getString("search_key", null).orEmpty()

    fun setSearchKey(key: String) {
        prefs?.edit()?.apply { if (key.isBlank()) remove("search_key") else putString("search_key", key.trim()) }?.apply()
    }

    fun save(p: AssistantProfile, apiKey: String?) {
        val list = _profiles.value.filterNot { it.id == p.id } + p
        val before = profile(p.id)
        prefs?.edit()?.apply {
            putString(K_PROFILES, JSONArray(list.map { it.toJson() }).toString())
            if (apiKey != null) putString("key:${p.id}", apiKey)
        }?.apply()
        _profiles.value = list
        if (before != null && before.perms != p.perms) {
            (p.perms - before.perms).forEach { log(p.name, JournalEntry.Kind.PERM, "on:${it.name}", "") }
            (before.perms - p.perms).forEach { log(p.name, JournalEntry.Kind.PERM, "off:${it.name}", "") }
        }
    }

    fun delete(id: String) {
        val list = _profiles.value.filterNot { it.id == id }
        prefs?.edit()?.putString(K_PROFILES, JSONArray(list.map { it.toJson() }).toString())?.remove("key:$id")?.remove("mem:$id")?.apply()
        _profiles.value = list
        _memory.value = _memory.value - id
    }

    fun log(profile: String, kind: JournalEntry.Kind, title: String, detail: String) {
        val now = System.currentTimeMillis()
        val cutoff = now - JOURNAL_DAYS * 86_400_000L
        val list = (listOf(JournalEntry(now, profile, kind, title, detail)) + _journal.value)
            .filter { it.at >= cutoff }.take(JOURNAL_MAX)
        _journal.value = list
        prefs?.edit()?.putString(K_JOURNAL, JSONArray(list.map {
            JSONObject().put("at", it.at).put("p", it.profile).put("k", it.kind.name).put("t", it.title).put("d", it.detail)
        }).toString())?.apply()
    }

    fun clearJournal() {
        _journal.value = emptyList()
        prefs?.edit()?.remove(K_JOURNAL)?.apply()
    }

    private fun readProfiles(): List<AssistantProfile> = runCatching {
        val a = JSONArray(prefs?.getString(K_PROFILES, null) ?: return emptyList())
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::profileOf) }
    }.getOrDefault(emptyList())

    private fun readJournal(): List<JournalEntry> = runCatching {
        val a = JSONArray(prefs?.getString(K_JOURNAL, null) ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            JournalEntry(
                o.optLong("at"), o.optString("p"),
                runCatching { JournalEntry.Kind.valueOf(o.optString("k")) }.getOrDefault(JournalEntry.Kind.ACT),
                o.optString("t"), o.optString("d"),
            )
        }
    }.getOrDefault(emptyList())

    private fun AssistantProfile.toJson() = JSONObject()
        .put("id", id).put("name", name).put("endpoint", endpoint).put("model", model)
        .put("system", systemPrompt).put("stt", sttModel)
        .put("perms", JSONArray(perms.map { it.name }))
        .put("image", imageModel).put("tts", ttsModel).put("voice", ttsVoice).put("video", videoModel)
        .put("members", JSONArray(members)).put("each", eachOwn).put("mem", memory)

    private fun profileOf(o: JSONObject): AssistantProfile? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val perms = o.optJSONArray("perms")?.let { a ->
            (0 until a.length()).mapNotNull { runCatching { AssistantPerm.valueOf(a.getString(it)) }.getOrNull() }.toSet()
        } ?: setOf(AssistantPerm.SHARE)
        return AssistantProfile(
            id = id, name = o.optString("name"), endpoint = o.optString("endpoint"), model = o.optString("model"),
            systemPrompt = o.optString("system"), sttModel = o.optString("stt"), perms = perms,
            imageModel = o.optString("image"), ttsModel = o.optString("tts"), ttsVoice = o.optString("voice").ifBlank { "alloy" },
            videoModel = o.optString("video"),
            members = o.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
            eachOwn = o.optBoolean("each"),
            memory = o.optBoolean("mem"),
        )
    }
}

/** One thing an assistant remembers about the user. */
data class MemoryFact(val text: String, val at: Long)
