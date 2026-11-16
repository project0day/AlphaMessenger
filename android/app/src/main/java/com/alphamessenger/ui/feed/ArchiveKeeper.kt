package com.alphamessenger.ui.feed

import android.content.Context
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.sticker.StickerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner only: tells the owner's own media server which blobs the Alpha channel
 * needs (every file of its posts and of the owner's sticker packs), so the
 * server's keeper holds them all and copies back any it lacks. Only blob hashes
 * and the mirrors that hold them go out, never keys. A ref is sent once.
 */
object ArchiveKeeper {
    private val running = Mutex()
    private const val BATCH = 400
    // Bumped when what is sent per blob grows (v2: the mirrors too), so everything goes once more.
    private const val SENT = "sent_v2"
    private const val FULL_EVERY_MS = 24 * 3600_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("archive_keeper", Context.MODE_PRIVATE)

    /** [full]: walk every post the relays hold, not only those on the phone (daily, or after a publish). */
    suspend fun sync(ctx: Context, repo: AlphaCoreRepository, full: Boolean = false) = withContext(Dispatchers.IO) {
        if (FeedStore.ownerKey(ctx) == null) return@withContext
        // The owner's server is the one whose address carries its secret upload path.
        val base = repo.ownMediaServers().firstOrNull { it.contains("/u/") }?.trimEnd('/') ?: return@withContext
        running.withLock {
            val p = prefs(ctx)
            val wantFull = full || System.currentTimeMillis() - p.getLong("full_at", 0) > FULL_EVERY_MS
            val posts = (if (wantFull) FeedStore.fetchAll(repo) else null) ?: FeedStore.posts
            val refs = LinkedHashSet<String>()
            posts.forEach { post -> listOfNotNull(post.image, post.gif, post.set, post.apk, post.dm).forEach { refs += it } }
            StickerStore.packs(ctx).filter { it.own }.forEach { pack ->
                pack.stickers.mapNotNullTo(refs) { it.ref }
                pack.version?.let { refs += it }
            }
            val sent = p.getStringSet(SENT, emptySet())!!.toMutableSet()
            val fresh = refs.filter { mark(it) !in sent }
            val items = ArrayList<JSONObject>()
            val done = ArrayList<String>()
            for (ref in fresh) {
                // Plain refs are read here; only a large file's separate chunk list needs the core.
                val arr = localBlobs(ref) ?: repo.mediaRefBlobs(ref)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: continue
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items += it }
                done += mark(ref)
                if (items.size >= BATCH) {
                    if (!post(base, items)) return@withLock
                    sent += done; done.clear(); items.clear()
                    p.edit().putStringSet(SENT, HashSet(sent)).apply()
                }
            }
            if (items.isNotEmpty() && !post(base, items)) return@withLock
            sent += done
            val e = p.edit().putStringSet(SENT, HashSet(sent))
            if (wantFull) e.putLong("full_at", System.currentTimeMillis())
            e.apply()
        }
    }

    /** A self-contained ref's blobs (its URLs end in the blob's sha256), or null when its chunk list lives in a manifest. */
    private fun localBlobs(ref: String): JSONArray? = runCatching {
        val o = JSONObject(ref)
        if (o.optInt("format_version", 1) == 2 && o.has("manifest_url")) return null
        val urls = ArrayList<String>()
        o.optJSONArray("blob_urls")?.let { a -> for (i in 0 until a.length()) urls += a.optString(i) }
        o.optJSONArray("thumbnail_urls")?.let { a -> for (i in 0 until a.length()) urls += a.optString(i) }
        o.optJSONArray("chunks")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONArray("locations")?.let { l ->
                for (j in 0 until l.length()) l.optJSONObject(j)?.optString("url")?.let { urls += it }
            }
        }
        val bySha = LinkedHashMap<String, MutableList<String>>()
        for (u in urls) {
            if (!u.startsWith("https://")) continue
            val sha = u.substringAfterLast('/').substringBefore('.').lowercase()
            if (sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }) bySha.getOrPut(sha) { ArrayList() }.add(u)
        }
        JSONArray().apply { bySha.forEach { (sha, us) -> put(JSONObject().put("sha", sha).put("urls", JSONArray(us.distinct()))) } }
    }.getOrNull()

    private fun mark(ref: String) = Integer.toHexString(ref.hashCode()) + ":" + ref.length

    private fun post(base: String, items: List<JSONObject>): Boolean = runCatching {
        val c = java.net.URL("$base/keep").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("items", JSONArray(items)).toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        c.disconnect()
        ok
    }.getOrDefault(false)
}
