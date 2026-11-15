package com.alphamessenger.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * The project's network health map: which public relays and media servers
 * answer, shut a prober out, or lose blobs. Signed by the project's worker and
 * read anonymously from relays, at most every few hours; kept on the phone.
 * Advice only: the user's relays are never changed by it.
 */
object NetworkHealth {
    /** [ok]: true answers, false down, null unknown (the relay shuts the prober's network out). */
    data class Relay(val url: String, val ok: Boolean?, val ms: Int, val why: String)
    data class Media(val url: String, val ok: Boolean, val checked: Int, val kept: Int) {
        /** Down, or it no longer has blobs it was given. */
        val bad: Boolean get() = !ok || (checked > 0 && kept == 0)
    }

    var relays by mutableStateOf<Map<String, Relay>>(emptyMap())
        private set
    var media by mutableStateOf<List<Media>>(emptyList())
        private set
    /** When the map was made (epoch s); 0 = none yet. */
    var at by mutableStateOf(0L)
        private set
    private var loaded = false
    private var askedAt = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("network_health", Context.MODE_PRIVATE)

    fun key(url: String) = url.trim().trimEnd('/').lowercase(java.util.Locale.ROOT)

    fun relay(url: String): Relay? = relays[key(url)]

    /** Relays the map saw answering, the fastest first. */
    fun working(): List<Relay> = relays.values.filter { it.ok == true }.sortedBy { it.ms }

    fun load(ctx: Context, repo: AlphaCoreRepository) {
        if (loaded) return
        loaded = true
        prefs(ctx).getString("map", null)?.let { apply(it, repo) }
    }

    /** Reads the map again when the last one is older than [maxAgeMs]. */
    suspend fun refreshIfStale(ctx: Context, repo: AlphaCoreRepository, maxAgeMs: Long = 3 * 3600_000L) {
        load(ctx, repo)
        val now = System.currentTimeMillis()
        if (now - askedAt < maxAgeMs) return
        askedAt = now
        val json = repo.alphaHealthFetch()?.takeIf { it.isNotBlank() } ?: return
        if (apply(json, repo)) prefs(ctx).edit().putString("map", json).apply()
    }

    private fun apply(json: String, repo: AlphaCoreRepository): Boolean = runCatching {
        val o = JSONObject(json)
        val r = o.getJSONArray("relays")
        relays = (0 until r.length()).map { r.getJSONObject(it) }.associate { x ->
            val ok = if (x.isNull("ok")) null else x.optBoolean("ok")
            key(x.optString("u")) to Relay(x.optString("u"), ok, x.optInt("ms"), x.optString("why"))
        }
        val m = o.getJSONArray("media")
        media = (0 until m.length()).map { m.getJSONObject(it) }.map { x ->
            Media(x.optString("u"), x.optBoolean("ok"), x.optInt("checked"), x.optInt("kept"))
        }
        at = o.optLong("at")
        repo.setMediaHealthDown(media.filter { it.bad }.map { it.url })
        true
    }.getOrDefault(false)
}
