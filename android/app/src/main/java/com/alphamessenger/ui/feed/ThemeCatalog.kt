package com.alphamessenger.ui.feed

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alphamessenger.data.AlphaCoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A theme in Alpha's channel: its link (colours and pattern), name, and when it was added (epoch s). */
data class CatalogTheme(val link: String, val name: String, val at: Long)

/**
 * Alpha's themes, beside the sticker packs in the same signed catalog, on pages
 * of their own ("theme-N"). A theme with its pattern is a few kilobytes, so pages
 * are cut by size, kept well under what relays take for one event.
 */
object ThemeCatalog {
    private const val PAGE_BYTES = 24_000
    private const val PREFIX = "theme-"
    private const val FRESH_MS = 120_000L

    fun has(link: String): Boolean = themes.any { it.link == link }

    /** Newest first. */
    var themes by mutableStateOf<List<CatalogTheme>>(emptyList())
        private set
    private var loaded = false
    private val writing = Mutex()
    @Volatile private var readAt = 0L

    private fun cache(ctx: Context) = File(ctx.filesDir, "alpha_themes.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        themes = runCatching { parse(cache(ctx).readText()) }.getOrDefault(emptyList())
    }

    /** Takes the theme pages out of a catalog fetch (the sticker catalog reads the same events). */
    fun ingest(ctx: Context, catalogJson: String) {
        val fresh = runCatching { parse(catalogJson) }.getOrNull() ?: return
        themes = fresh
        readAt = System.currentTimeMillis()
        runCatching { cache(ctx).writeText(catalogJson) }
    }

    suspend fun refresh(ctx: Context, repo: AlphaCoreRepository): Boolean {
        load(ctx)
        val json = repo.alphaCatalogFetch() ?: return false
        withContext(Dispatchers.IO) { ingest(ctx, json) }
        readAt = System.currentTimeMillis()
        return true
    }

    private fun parse(json: String): List<CatalogTheme> {
        val arr = JSONArray(json)
        val byLink = LinkedHashMap<String, CatalogTheme>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.optString("d").startsWith(PREFIX)) continue
            val c = runCatching { JSONObject(o.optString("c")) }.getOrNull() ?: continue
            val list = c.optJSONArray("t") ?: continue
            for (j in 0 until list.length()) {
                val t = list.optJSONObject(j) ?: continue
                val link = t.optString("l").takeIf { com.alphamessenger.ui.theme.isThemeLink(it) } ?: continue
                byLink[link] = CatalogTheme(link, t.optString("n"), t.optLong("a"))
            }
        }
        return byLink.values.sortedByDescending { it.at }
    }

    suspend fun add(ctx: Context, repo: AlphaCoreRepository, key: String, link: String, name: String): Boolean =
        change(ctx, repo, key) { list -> if (list.any { it.link == link }) list else list + CatalogTheme(link, name.take(40), System.currentTimeMillis() / 1000) }

    suspend fun remove(ctx: Context, repo: AlphaCoreRepository, key: String, link: String): Boolean =
        change(ctx, repo, key) { list -> list.filter { it.link != link } }

    /** Built on what the relays hold now; pages that changed are written, pages left over emptied. */
    private suspend fun change(ctx: Context, repo: AlphaCoreRepository, key: String, edit: (List<CatalogTheme>) -> List<CatalogTheme>): Boolean = writing.withLock {
        // A catalog read a moment ago is current enough: reading all relays again costs seconds.
        if (System.currentTimeMillis() - readAt > FRESH_MS && !refresh(ctx, repo)) return@withLock false
        val next = edit(themes)
        val before = pages(themes)
        val after = pages(next)
        var ok = true
        for (i in 0 until maxOf(before.size, after.size)) {
            val page = after.getOrElse(i) { EMPTY }
            if (before.getOrNull(i) != page && !repo.alphaCatalogPublish(key, "$PREFIX$i", page)) ok = false
        }
        if (ok) themes = next.sortedByDescending { it.at }
        ok
    }

    private val EMPTY = JSONObject().put("k", "themes").put("t", JSONArray()).toString()

    /** Oldest first, a page as full as [PAGE_BYTES] allows. */
    private fun pages(list: List<CatalogTheme>): List<String> {
        val out = ArrayList<String>()
        var cur = JSONArray()
        var size = 0
        for (t in list.sortedBy { it.at }) {
            val o = JSONObject().put("l", t.link).put("n", t.name).put("a", t.at)
            val s = o.toString().length
            if (cur.length() > 0 && size + s > PAGE_BYTES) {
                out += JSONObject().put("k", "themes").put("t", cur).toString()
                cur = JSONArray(); size = 0
            }
            cur.put(o); size += s
        }
        if (cur.length() > 0) out += JSONObject().put("k", "themes").put("t", cur).toString()
        return out
    }
}
