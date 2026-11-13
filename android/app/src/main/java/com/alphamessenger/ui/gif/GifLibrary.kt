package com.alphamessenger.ui.gif

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A GIF goes out as a short silent looping video whose file is named "gif_…". */
const val GIF_PREFIX = "gif_"

/** Longest GIF made from a video (the maker's length slider ends here). */
const val GIF_MAX_MS = 15_000L

/** What a GIF made from a video starts at; the maker lets you choose. */
const val GIF_DEFAULT_MS = 6_000L

/** One GIF on this phone: its clip and what it was called (for search). */
data class GifEntry(val path: String, val name: String, val at: Long)

/**
 * The GIFs this phone has, all local — nothing is ever searched for outside:
 *  - saved: kept for good in `filesDir/gifs` (made from a video or saved from a chat);
 *  - from chats: GIFs that came in and were fetched, pointing at their cached
 *    file (gone with the cache, then left out).
 */
object GifLibrary {
    private const val PREFS = "gif_library"
    private const val KEY_SAVED = "saved"
    private const val KEY_SEEN = "seen"
    private const val SEEN_MAX = 60

    private val _version = MutableStateFlow(0)
    /** Bumped on every change, so open panels reload. */
    val version: StateFlow<Int> = _version

    private fun dir(ctx: Context) = File(ctx.filesDir, "gifs").apply { mkdirs() }

    private fun read(ctx: Context, key: String): List<GifEntry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            GifEntry(o.optString("f"), o.optString("n"), o.optLong("t"))
        }
    }

    private fun write(ctx: Context, key: String, list: List<GifEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("f", it.path).put("n", it.name).put("t", it.at)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, arr.toString()).apply()
        _version.value++
    }

    @Synchronized
    fun saved(ctx: Context): List<GifEntry> = read(ctx, KEY_SAVED).filter { File(it.path).length() > 0 }

    @Synchronized
    fun fromChats(ctx: Context): List<GifEntry> = read(ctx, KEY_SEEN).filter { File(it.path).length() > 0 }

    private fun hashOf(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        md.digest().take(10).joinToString("") { "%02x".format(it) }
    }.getOrDefault(System.currentTimeMillis().toString())

    /** Keep [source] among the saved GIFs (the same clip twice is kept once). */
    suspend fun save(ctx: Context, source: File, name: String): Boolean = withContext(Dispatchers.IO) {
        if (source.length() <= 0) return@withContext false
        val dest = File(dir(ctx), "${hashOf(source)}.mp4")
        if (!dest.exists() && runCatching { source.copyTo(dest, overwrite = true) }.isFailure) return@withContext false
        synchronized(this@GifLibrary) {
            val rest = read(ctx, KEY_SAVED).filter { it.path != dest.absolutePath }
            write(ctx, KEY_SAVED, listOf(GifEntry(dest.absolutePath, name.trim(), System.currentTimeMillis())) + rest)
        }
        true
    }

    fun isSaved(ctx: Context, path: String): Boolean = path.startsWith(dir(ctx).absolutePath)

    @Synchronized
    fun remove(ctx: Context, entry: GifEntry) {
        if (isSaved(ctx, entry.path)) {
            write(ctx, KEY_SAVED, read(ctx, KEY_SAVED).filter { it.path != entry.path })
            File(entry.path).delete()
        } else {
            write(ctx, KEY_SEEN, read(ctx, KEY_SEEN).filter { it.path != entry.path })
        }
    }

    /** A GIF from a chat was fetched: remember it for "From chats". */
    @Synchronized
    fun noteSeen(ctx: Context, path: String, name: String) {
        val list = read(ctx, KEY_SEEN)
        if (list.firstOrNull()?.path == path) return
        val rest = list.filter { it.path != path }
        write(ctx, KEY_SEEN, (listOf(GifEntry(path, name.trim(), System.currentTimeMillis())) + rest).take(SEEN_MAX))
    }

    /** A copy to send: the send pipeline owns (and later clears) its source. */
    suspend fun copyForSend(ctx: Context, entry: GifEntry): File? = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "$GIF_PREFIX${System.currentTimeMillis()}.mp4")
        runCatching { com.alphamessenger.data.MediaVault.copyPlain(File(entry.path), out); out }.getOrNull()
    }

    /** Width × height of [source] as shown (rotation applied). */
    fun shownSize(ctx: Context, source: Uri): Pair<Int, Int> = runCatching {
        val r = MediaMetadataRetriever()
        try {
            val path = source.path.takeIf { source.scheme == null || source.scheme == "file" }
            if (path != null) com.alphamessenger.data.MediaVault.setSource(r, path) else r.setDataSource(ctx, source)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } finally { r.release() }
    }.getOrDefault(0 to 0)
}
