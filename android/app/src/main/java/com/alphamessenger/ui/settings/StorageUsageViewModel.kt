package com.alphamessenger.ui.settings

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * "Storage usage" — scans what AlphaMessenger keeps on disk and lets the
 * user reclaim space. The breakdown is computed in the background (never on the
 * UI thread, never on every recomposition).
 *
 * Local media is cached by content hash with NO extension (`cacheDir/media/<hash>`),
 * so the type (photo/video/voice/file) is recovered by joining each cache file
 * against the DB's `media_ref` entries. Deletion removes only the local cache —
 * the message stays in history and the blob is re-downloadable on demand.
 */
@HiltViewModel
class StorageUsageViewModel @Inject constructor(
    private val repository: AlphaCoreRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class Report(
        val photos: Long = 0, val videos: Long = 0, val files: Long = 0, val voice: Long = 0,
        val avatars: Long = 0, val cache: Long = 0, val database: Long = 0,
        /** Own sticker packs, added packs and saved GIFs. */
        val stickers: Long = 0,
        val total: Long = 0,
        val appBytes: Long = 0, val dataBytes: Long = 0, val cacheBytes: Long = 0,
        /** What each chat keeps in the cache (removable), largest first. */
        val byChat: List<ChatUsage> = emptyList(),
    )

    /** One chat's share of the cache. */
    data class ChatUsage(
        val chatId: String, val name: String, val avatarPath: String,
        val bytes: Long, val photos: Long, val videos: Long, val files: Long, val voice: Long,
    )

    data class UiState(
        val loading: Boolean = true,
        val busy: Boolean = false,
        val report: Report = Report(),
        // Auto-cleanup settings
        val autoTrigger: String = "never",   // "never"|"days_30"|"days_90"|"size_2gb"|"size_5gb"
        val autoPhoto: Boolean = true,
        val autoVideo: Boolean = true,
        val autoVoice: Boolean = true,
        val autoFile: Boolean = true,
        /** Epoch ms of the last successful auto-cleanup (0 = never). */
        val autoLastCleanupMs: Long = 0L,
    )

    companion object {
        private const val DB_NAME = "alpha_messenger.db"
        private const val PREFS_AUTO = "storage_auto_cleanup"

        val AUTO_TRIGGER_OPTIONS = listOf(
            "never"    to R.string.cleanup_policy_never,
            "days_30"  to R.string.cleanup_policy_days_30,
            "days_90"  to R.string.cleanup_policy_days_90,
            "size_2gb" to R.string.cleanup_policy_size_2gb,
            "size_5gb" to R.string.cleanup_policy_size_5gb,
        )

        fun formatBytes(b: Long): String {
            if (b <= 0) return "0 B"
            val u = arrayOf("B", "KB", "MB", "GB", "TB")
            var v = b.toDouble(); var i = 0
            while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
            return if (i == 0) "${b} B"
                else String.format(java.util.Locale.US, "%.1f %s", v, u[i])
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadAutoSettings()
        rescan()
    }

    fun rescan() {
        viewModelScope.launch {
            // The last count shows at once; the fresh one replaces it when ready.
            val last = withContext(Dispatchers.IO) { lastReport() }
            _state.update { if (last != null) it.copy(loading = false, report = last) else it.copy(loading = true) }
            val r = withContext(Dispatchers.IO) { scan().also { keepReport(it) } }
            _state.update { it.copy(loading = false, report = r) }
            maybeAutoClean(r)
        }
    }

    private fun reportPrefs() = context.getSharedPreferences("storage_report", Context.MODE_PRIVATE)

    private fun lastReport(): Report? = runCatching {
        val o = org.json.JSONObject(reportPrefs().getString("r", null) ?: return null)
        Report(
            o.getLong("photos"), o.getLong("videos"), o.getLong("files"), o.getLong("voice"),
            o.getLong("avatars"), o.getLong("cache"), o.getLong("database"), o.getLong("stickers"),
            o.getLong("total"), o.getLong("app"), o.getLong("data"), o.getLong("cacheB"),
        )
    }.getOrNull()

    private fun keepReport(r: Report) {
        val o = org.json.JSONObject()
            .put("photos", r.photos).put("videos", r.videos).put("files", r.files).put("voice", r.voice)
            .put("avatars", r.avatars).put("cache", r.cache).put("database", r.database).put("stickers", r.stickers)
            .put("total", r.total).put("app", r.appBytes).put("data", r.dataBytes).put("cacheB", r.cacheBytes)
        reportPrefs().edit().putString("r", o.toString()).apply()
    }

    /** Remove one chat's cached attachments (the messages stay; each can be fetched again). */
    fun clearChat(chatId: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            withContext(Dispatchers.IO) {
                for (item in allMediaItems().filter { it.chatId == chatId }) {
                    for (f in filesFor(item)) {
                        if (!removable(f)) continue
                        if (f.delete()) com.alphamessenger.data.MediaCacheRoots.markEvicted(f)
                    }
                }
            }
            val r = withContext(Dispatchers.IO) { scan().also { keepReport(it) } }
            _state.update { it.copy(busy = false, report = r) }
        }
    }

    /** Ours to delete: inside the cache, never a send's own source. */
    private fun removable(f: File): Boolean =
        f.absolutePath.startsWith(context.cacheDir.absolutePath) &&
            !com.alphamessenger.data.MediaCacheRoots.isOutbox(context, f.absolutePath)

    private suspend fun chatUsage(items: List<MediaItem>): List<ChatUsage> {
        data class Acc(var photos: Long = 0, var videos: Long = 0, var files: Long = 0, var voice: Long = 0)
        val acc = HashMap<String, Acc>()
        val seen = HashSet<String>()
        for (item in items) {
            if (item.chatId.isBlank()) continue
            for (f in filesFor(item)) {
                if (!removable(f) || !seen.add(f.absolutePath)) continue
                val a = acc.getOrPut(item.chatId) { Acc() }
                when (item.type) {
                    "image" -> a.photos += f.length()
                    "video" -> a.videos += f.length()
                    "voice" -> a.voice += f.length()
                    else -> a.files += f.length()
                }
            }
        }
        if (acc.isEmpty()) return emptyList()
        val chats = runCatching { repository.listChats() }.getOrDefault(emptyList()).associateBy { it.id }
        val own = runCatching { repository.ownNpub() }.getOrDefault("")
        return acc.mapNotNull { (id, a) ->
            val total = a.photos + a.videos + a.files + a.voice
            if (total <= 0L) return@mapNotNull null
            val chat = chats[id]
            // A 1:1 is named after the person; a group by its own name.
            val peer = if (chat != null && chat.chatType != "group" && id != "saved_messages_local")
                runCatching { repository.getPeerProfile(repository.resolvePeerSenderId(id, own)) }.getOrNull() else null
            val name = when {
                id == "saved_messages_local" -> context.getString(R.string.menu_saved)
                peer?.displayName?.isNotBlank() == true -> peer.displayName
                chat?.name?.isNotBlank() == true -> chat.name!!
                else -> context.getString(if (chat?.chatType == "group") R.string.group_chat else R.string.private_chat)
            }
            ChatUsage(id, name, peer?.avatarPath?.takeIf { it.isNotBlank() } ?: chat?.avatarPath.orEmpty(), total, a.photos, a.videos, a.files, a.voice)
        }.sortedByDescending { it.bytes }
    }

    // --- Deletion (local cache only; history is untouched) ---

    /** type ∈ {image, video, voice, file}; [olderThanDays] null = all. */
    fun deleteType(type: String, olderThanDays: Int?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            withContext(Dispatchers.IO) {
                val cutoff = olderThanDays?.let { System.currentTimeMillis() - it * 24L * 3600_000L }
                val cachePrefix = context.cacheDir.absolutePath
                for (item in allMediaItems().filter { it.type == type }) {
                    for (f in filesFor(item)) {
                        if (cutoff != null && f.lastModified() >= cutoff) continue
                        // The user's original — not ours.
                        if (!f.absolutePath.startsWith(cachePrefix)) continue
                        // OUTBOX lives in the cache only for old sends, and it is not a cache
                        // but the source for sending/resuming — a path inside the cache is not
                        // a reason to delete it.
                        if (com.alphamessenger.data.MediaCacheRoots.isOutbox(context, f.absolutePath)) continue
                        if (f.delete()) com.alphamessenger.data.MediaCacheRoots.markEvicted(f)
                    }
                }
            }
            val r = withContext(Dispatchers.IO) { scan() }
            _state.update { it.copy(busy = false, report = r) }
        }
    }

    /** Wipe the whole downloadable cache (media + notification thumbnails). Does
     *  NOT touch the DB or avatars. */
    fun clearCache() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            withContext(Dispatchers.IO) {
                // Only disposable directories — the project-wide list.
                // OUTBOX is excluded from it by definition, so no extra guard is needed.
                for (root in com.alphamessenger.data.MediaCacheRoots.disposableRoots(context)) {
                    root.listFiles()?.forEach { f ->
                        if (!f.isFile) return@forEach
                        // The "deleted by user" mark — otherwise auto-download would silently
                        // fetch the deleted item back over the network without any user action.
                        if (f.delete()) com.alphamessenger.data.MediaCacheRoots.markEvicted(f)
                    }
                }
                com.alphamessenger.data.MediaCacheRoots.notificationThumbs(context)
                    .listFiles()?.forEach { it.delete() }
            }
            val r = withContext(Dispatchers.IO) { scan() }
            _state.update { it.copy(busy = false, report = r) }
        }
    }

    // --- Auto-cleanup settings ---

    private fun prefs() = context.getSharedPreferences(PREFS_AUTO, Context.MODE_PRIVATE)

    private fun loadAutoSettings() {
        val p = prefs()
        _state.update {
            it.copy(
                autoTrigger = p.getString("trigger", "never") ?: "never",
                autoPhoto   = p.getBoolean("photo", true),
                autoVideo   = p.getBoolean("video", true),
                autoVoice   = p.getBoolean("voice", true),
                autoFile    = p.getBoolean("file",  true),
                autoLastCleanupMs = p.getLong("last_cleanup_ms", 0L),
            )
        }
    }

    fun setAutoTrigger(trigger: String) {
        prefs().edit().putString("trigger", trigger).apply()
        _state.update { it.copy(autoTrigger = trigger) }
    }

    fun setAutoCategory(type: String, enabled: Boolean) {
        prefs().edit().putBoolean(type, enabled).apply()
        _state.update {
            when (type) {
                "photo" -> it.copy(autoPhoto = enabled)
                "video" -> it.copy(autoVideo = enabled)
                "voice" -> it.copy(autoVoice = enabled)
                "file"  -> it.copy(autoFile  = enabled)
                else    -> it
            }
        }
    }

    /** Called after rescan — delegates to the shared [CacheTrimmer] (true LRU +
     *  TTL + 24h protection + priority), then refreshes the displayed sizes. */
    private fun maybeAutoClean(@Suppress("UNUSED_PARAMETER") report: Report) {
        if (_state.value.autoTrigger == "never") return
        viewModelScope.launch(Dispatchers.IO) {
            com.alphamessenger.data.CacheTrimmer(context, repository).runAutoClean()
            val r = scan()
            _state.update {
                it.copy(report = r, autoLastCleanupMs = prefs().getLong("last_cleanup_ms", 0L))
            }
        }
    }

    // --- Scan ---

    private fun mediaDir() = File(context.cacheDir, "media")

    private data class MediaItem(val type: String, val hash: String, val localPath: String, val chatId: String = "")

    private suspend fun scan(): Report {
        // Typed media: walk the DB so BOTH sent (local_path) and received
        // (cache/media/<hash>) files are counted. Dedup by absolute path so a
        // forwarded/duplicate blob isn't counted twice.
        var photos = 0L; var videos = 0L; var files = 0L; var voice = 0L
        val counted = HashSet<String>()
        val allItems = allMediaItems()
        for (item in allItems) {
            for (f in filesFor(item)) {
                if (!counted.add(f.absolutePath)) continue
                when (item.type) {
                    "image" -> photos += f.length()
                    "video" -> videos += f.length()
                    "voice" -> voice += f.length()
                    else -> files += f.length()
                }
            }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        val dbParent = dbFile.parentFile
        val avatars = dirSize(File(dbParent, "contact_avatars")) +
            dirSize(File(dbParent, "group_avatars"))
        val database = fileSize(dbFile) +
            fileSize(File(dbFile.path + "-wal")) + fileSize(File(dbFile.path + "-shm"))

        // Cache = every other file under cacheDir that we didn't already count as
        // typed media (thumbnails, temp files, orphaned downloads).
        var cache = 0L
        context.cacheDir.walkTopDown().forEach { f ->
            if (f.isFile && f.absolutePath !in counted) cache += f.length()
        }

        val stickers = dirSize(File(context.filesDir, "stickers")) + dirSize(File(context.filesDir, "gifs"))
        val total = photos + videos + files + voice + avatars + cache + database + stickers

        var appBytes = 0L; var dataBytes = 0L; var cacheBytes = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val ssm = context.getSystemService(StorageStatsManager::class.java)
                val stats = ssm.queryStatsForUid(StorageManager.UUID_DEFAULT, android.os.Process.myUid())
                appBytes = stats.appBytes
                dataBytes = stats.dataBytes
                cacheBytes = stats.cacheBytes
            }
        }

        return Report(
            photos = photos, videos = videos, files = files, voice = voice,
            avatars = avatars, cache = cache, database = database, stickers = stickers, total = total,
            appBytes = appBytes, dataBytes = dataBytes, cacheBytes = cacheBytes,
            byChat = chatUsage(allItems),
        )
    }

    /** Every media item across all chats (album items flattened). */
    private suspend fun allMediaItems(): List<MediaItem> {
        // The core lists the attachments itself: whole messages never cross for this.
        return repository.storedMedia().map { s -> toItem(org.json.JSONObject().put("media_type", s.mediaType).put("hash", s.hash).put("local_path", s.localPath)).copy(chatId = s.chatId) }
    }

    private fun toItem(o: org.json.JSONObject): MediaItem {
        val raw = o.optString("media_type", "file").ifBlank { "file" }
        val type = when (raw) {
            "image" -> "image"
            "video" -> "video"
            "audio" -> "voice"   // voice notes + any audio -> the voice bucket
            else -> "file"
        }
        return MediaItem(type, o.optString("hash"), o.optString("local_path"))
    }

    /** On-disk files for a media item: the sender's local copy and/or the
     *  received cache blob (single file or streaming chunks). */
    private fun filesFor(item: MediaItem): List<File> {
        val res = ArrayList<File>()
        if (item.localPath.isNotBlank()) {
            val f = File(item.localPath)
            if (f.exists() && f.isFile) res.add(f)
        }
        if (item.hash.isNotBlank()) {
            val main = File(mediaDir(), item.hash)
            if (main.exists()) res.add(main)
            else mediaDir().listFiles { f -> f.isFile && f.name.startsWith(item.hash) }
                ?.let { res.addAll(it) }
        }
        return res
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun fileSize(f: File): Long = if (f.exists()) f.length() else 0L

}
