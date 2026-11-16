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

/** A pack in Alpha's sticker catalog: its link, name, size and when it was added (epoch s). */
data class CatalogPack(val link: String, val name: String, val count: Int, val at: Long)

/**
 * Alpha's sticker packs, kept apart from the feed as a few signed pages of
 * [PAGE] packs each: hundreds of packs never push news and app updates out of
 * the feed, and readers fetch a handful of events instead of a post per pack.
 */
object StickerCatalog {
    private const val PAGE = 80

    /** Newest first. */
    var packs by mutableStateOf<List<CatalogPack>>(emptyList())
        private set
    private var loaded = false
    private val writing = Mutex()

    private fun cache(ctx: Context) = File(ctx.filesDir, "alpha_catalog.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        packs = runCatching { parse(cache(ctx).readText()) }.getOrDefault(emptyList())
    }

    suspend fun refresh(ctx: Context, repo: AlphaCoreRepository): Boolean {
        load(ctx)
        val json = repo.alphaCatalogFetch() ?: return false
        // Theme pages travel in the same events: read once, for both.
        ThemeCatalog.ingest(ctx, json)
        val fresh = runCatching { parse(json) }.getOrNull() ?: return false
        withContext(Dispatchers.IO) { runCatching { cache(ctx).writeText(json) } }
        packs = fresh
        return true
    }

    private fun parse(json: String): List<CatalogPack> {
        val arr = JSONArray(json)
        val byLink = LinkedHashMap<String, CatalogPack>()
        for (i in 0 until arr.length()) {
            val c = runCatching { JSONObject(arr.getJSONObject(i).optString("c")) }.getOrNull() ?: continue
            val list = c.optJSONArray("p") ?: continue
            for (j in 0 until list.length()) {
                val o = list.optJSONObject(j) ?: continue
                val link = o.optString("l").takeIf { it.isNotBlank() } ?: continue
                byLink[link] = CatalogPack(link, o.optString("n"), o.optInt("c"), o.optLong("a"))
            }
        }
        return byLink.values.sortedByDescending { it.at }
    }

    /**
     * Owner: add (or rename) [entries] and publish the pages that changed. Pages
     * are filled oldest first, so new packs only ever touch the last pages.
     */
    suspend fun add(ctx: Context, repo: AlphaCoreRepository, key: String, entries: List<CatalogPack>): Boolean = writing.withLock {
        // Built on what the relays hold now, never on a stale copy that would drop packs.
        if (!refresh(ctx, repo)) return@withLock false
        val before = pages(packs)
        val merged = LinkedHashMap<String, CatalogPack>()
        packs.sortedBy { it.at }.forEach { merged[it.link] = it }
        entries.forEach { e -> merged[e.link] = merged[e.link]?.let { it.copy(name = e.name, count = e.count.takeIf { c -> c > 0 } ?: it.count) } ?: e }
        val all = merged.values.toList()
        val after = pages(all)
        var ok = true
        after.forEachIndexed { i, page ->
            if (before.getOrNull(i) != page && !repo.alphaCatalogPublish(key, "cat-$i", page)) ok = false
        }
        if (ok) packs = all.sortedByDescending { it.at }
        ok
    }

    /**
     * Owner: take the packs at [links] out of the catalog. The pages after the
     * first change are written again; pages left over become empty ones.
     */
    suspend fun remove(ctx: Context, repo: AlphaCoreRepository, key: String, links: Set<String>): Boolean = writing.withLock {
        if (!refresh(ctx, repo)) return@withLock false
        val before = pages(packs)
        val rest = packs.filter { it.link !in links }
        if (rest.size == packs.size) return@withLock true
        val after = pages(rest)
        var ok = true
        for (i in 0 until maxOf(before.size, after.size)) {
            val page = after.getOrElse(i) { EMPTY_PAGE }
            if (before.getOrNull(i) != page && !repo.alphaCatalogPublish(key, "cat-$i", page)) ok = false
        }
        if (ok) packs = rest.sortedByDescending { it.at }
        ok
    }

    private val EMPTY_PAGE = JSONObject().put("k", "cat").put("p", JSONArray()).toString()

    private fun pages(list: List<CatalogPack>): List<String> =
        list.sortedBy { it.at }.chunked(PAGE).map { chunk ->
            JSONObject().put("k", "cat").put("p", JSONArray().apply {
                chunk.forEach { put(JSONObject().put("l", it.link).put("n", it.name).put("c", it.count).put("a", it.at)) }
            }).toString()
        }
}
