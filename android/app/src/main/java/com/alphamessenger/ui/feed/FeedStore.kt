package com.alphamessenger.ui.feed

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.gif.GifEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What a feed post is about. */
/**
 * [EMOJI]: the animated emoji set, one archive. [UPDATE]: a new build of the app.
 * [ALERT]: a notice on top of the chat list until removed or [FeedPost.until].
 * Older apps show any kind they don't know as news.
 */
enum class FeedKind(val code: String) { NEWS("news"), PACK("pack"), GIF("gif"), EMOJI("emoji"), UPDATE("update"), ALERT("alert");
    companion object { fun of(code: String) = entries.firstOrNull { it.code == code } ?: NEWS }
}

/** One post of the Alpha feed. Media are blob refs (fetched like any media). */
data class FeedPost(
    val id: String,
    val at: Long,
    val kind: FeedKind,
    val title: String,
    val body: String,
    val image: String? = null,
    val pack: String? = null,
    val gif: String? = null,
    /** The animated emoji archive's blob ref. */
    val set: String? = null,
    /** [FeedKind.UPDATE]: the APK's blob ref, its version code and name, and its SHA-256 (hex). */
    val apk: String? = null,
    val versionCode: Long = 0,
    val versionName: String? = null,
    val sha256: String? = null,
    /** The build's compile profile (.dm): installed with the APK, hot code is compiled at install. */
    val dm: String? = null,
    /** [FeedKind.ALERT]: [ALERT_CRITICAL] or [ALERT_IMPORTANT], and when it stops showing (epoch s, 0 = never). */
    val level: String? = null,
    val until: Long = 0,
) {
    companion object {
        const val ALERT_IMPORTANT = "important"
        const val ALERT_CRITICAL = "critical"
    }
}

/**
 * The Alpha feed on this phone: the posts last fetched (kept for offline
 * reading), what has been seen, whether its row is shown in Channels, and —
 * on the owner's phone only — the key that publishes.
 */
object FeedStore {
    private const val PREFS = "alpha_feed"

    var posts by mutableStateOf<List<FeedPost>>(emptyList())
        private set
    var hidden by mutableStateOf(false)
        private set
    var seenAt by mutableStateOf(0L)
        private set
    var isOwner by mutableStateOf(false)
        private set
    private var loaded = false

    private fun cache(ctx: Context) = File(ctx.filesDir, "alpha_feed.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The owner's key lives encrypted (Keystore-backed), never in plain prefs. */
    private fun secure(ctx: Context): SharedPreferences? = runCatching {
        val mk = androidx.security.crypto.MasterKey.Builder(ctx.applicationContext)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx.applicationContext, "alpha_feed_owner", mk,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        hidden = prefs(ctx).getBoolean("hidden", false)
        seenAt = prefs(ctx).getLong("seen", 0L)
        posts = runCatching { parse(cache(ctx).readText()) }.getOrDefault(emptyList())
    }

    /** Whether this phone holds the publishing key (read off the main thread). */
    suspend fun loadOwner(ctx: Context) {
        isOwner = withContext(Dispatchers.IO) { secure(ctx)?.getString("k", null) != null }
    }

    private fun parse(json: String): List<FeedPost> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val c = runCatching { JSONObject(o.optString("c")) }.getOrNull() ?: return@mapNotNull null
            if (c.optBoolean("del")) return@mapNotNull null
            fun opt(k: String) = c.optString(k).takeIf { it.isNotBlank() }
            FeedPost(o.optString("d"), o.optLong("at"), FeedKind.of(c.optString("k")),
                c.optString("t"), c.optString("b"), opt("img"), opt("pack"), opt("gif"), opt("set"),
                opt("apk"), c.optLong("vc"), opt("vn"), opt("sha"), opt("dm"), opt("lvl"), c.optLong("until"))
        }
    }

    /** Fetch the latest posts (anonymously); false when offline — the cached ones stay. */
    suspend fun refresh(ctx: Context, repo: AlphaCoreRepository): Boolean = kotlinx.coroutines.coroutineScope {
        load(ctx)
        val catalog = async { StickerCatalog.refresh(ctx, repo) }
        // Apart from the feed: a slow map read must not hold its refresh up.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { com.alphamessenger.data.NetworkHealth.refreshIfStale(ctx, repo) }
        val json = repo.alphaFeedFetch()
        catalog.await()
        val fresh = json?.let { runCatching { parse(it) }.getOrNull() } ?: return@coroutineScope false
        withContext(Dispatchers.IO) { runCatching { cache(ctx).writeText(json) } }
        posts = fresh
        true
    }

    /** Owner: every post the relays hold (more than the readers' first page), for clean-ups. */
    suspend fun fetchAll(repo: AlphaCoreRepository): List<FeedPost>? =
        repo.alphaFeedFetch(3000)?.let { runCatching { parse(it) }.getOrNull() }

    /** Owner: remove [post] with a tombstone at its own time, then forget it here. */
    suspend fun removeQuietly(ctx: Context, repo: AlphaCoreRepository, post: FeedPost): Boolean {
        val key = ownerKey(ctx) ?: return false
        val ok = repo.alphaFeedRemove(key, post.id, post.at)
        if (ok) posts = posts.filter { it.id != post.id }
        return ok
    }

    private var refreshedAt = 0L

    /** [refresh], unless the feed was fetched less than [maxAgeMs] ago (coming back to the app). */
    suspend fun refreshIfStale(ctx: Context, repo: AlphaCoreRepository, maxAgeMs: Long = 5 * 60_000L) {
        if (System.currentTimeMillis() - refreshedAt < maxAgeMs) return
        if (refresh(ctx, repo)) refreshedAt = System.currentTimeMillis()
    }

    /** Notices to show now, the critical first. */
    val alerts: List<FeedPost> get() {
        val now = System.currentTimeMillis() / 1000
        return posts.filter { it.kind == FeedKind.ALERT && (it.until == 0L || it.until > now) }
            .sortedWith(compareByDescending<FeedPost> { it.level == FeedPost.ALERT_CRITICAL }.thenByDescending { it.at })
    }

    val latestAt: Long get() = posts.maxOfOrNull { it.at } ?: 0L
    val unread: Boolean get() = latestAt > seenAt

    fun markSeen(ctx: Context) {
        seenAt = latestAt
        prefs(ctx).edit().putLong("seen", seenAt).apply()
    }

    fun setHidden(ctx: Context, v: Boolean) {
        hidden = v
        prefs(ctx).edit().putBoolean("hidden", v).apply()
    }

    // --- The owner ---

    /** Keep the publishing key (only if it IS Alpha's feed key). */
    suspend fun setOwnerKey(ctx: Context, repo: AlphaCoreRepository, key: String): Boolean {
        if (!repo.alphaFeedKeyOk(key.trim())) return false
        withContext(Dispatchers.IO) { secure(ctx)?.edit()?.putString("k", key.trim())?.commit() }
        isOwner = true
        return true
    }

    fun forgetOwnerKey(ctx: Context) {
        secure(ctx)?.edit()?.remove("k")?.apply()
        isOwner = false
    }

    internal fun ownerKey(ctx: Context): String? = secure(ctx)?.getString("k", null)

    /**
     * Publish a post (or replace [post.id]): a picture or GIF is uploaded once
     * as a blob and travels as its ref.
     */
    suspend fun publish(ctx: Context, repo: AlphaCoreRepository, post: FeedPost, imageFile: File?, gifFile: File?, setFile: File? = null, apkFile: File? = null, dmFile: File? = null): Boolean =
        withContext(Dispatchers.IO) {
            val key = ownerKey(ctx) ?: return@withContext false
            val set = setFile?.let { repo.uploadBlobRefOnly(it.absolutePath, "file", null, "feed_set_${System.currentTimeMillis()}") ?: return@withContext false }
            val img = imageFile?.let { repo.uploadBlobRefOnly(it.absolutePath, "image", null, "feed_img_${System.currentTimeMillis()}") ?: return@withContext false }
            val gif = gifFile?.let { repo.uploadBlobRefOnly(it.absolutePath, "video", null, "feed_gif_${System.currentTimeMillis()}") ?: return@withContext false }
            val apk = apkFile?.let { repo.uploadBlobRefOnly(it.absolutePath, "file", null, "feed_apk_${System.currentTimeMillis()}") ?: return@withContext false }
            val dm = dmFile?.let { repo.uploadBlobRefOnly(it.absolutePath, "file", null, "feed_dm_${System.currentTimeMillis()}") }
            val c = JSONObject().put("k", post.kind.code).put("t", post.title).put("b", post.body)
            (img ?: post.image)?.let { c.put("img", it) }
            post.pack?.let { c.put("pack", it) }
            (gif ?: post.gif)?.let { c.put("gif", it) }
            (set ?: post.set)?.let { c.put("set", it) }
            (apk ?: post.apk)?.let { c.put("apk", it) }
            if (post.versionCode > 0) c.put("vc", post.versionCode)
            post.versionName?.let { c.put("vn", it) }
            post.sha256?.let { c.put("sha", it) }
            (dm ?: post.dm)?.let { c.put("dm", it) }
            post.level?.let { c.put("lvl", it) }
            if (post.until > 0) c.put("until", post.until)
            val ok = repo.alphaFeedPublish(key, post.id, c.toString())
            if (ok) {
                refresh(ctx, repo)
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { ArchiveKeeper.sync(ctx, repo) }
            }
            ok
        }

    /** Remove a post: its id is published again as deleted. */
    suspend fun delete(ctx: Context, repo: AlphaCoreRepository, id: String): Boolean = withContext(Dispatchers.IO) {
        val key = ownerKey(ctx) ?: return@withContext false
        val ok = repo.alphaFeedPublish(key, id, JSONObject().put("del", true).toString())
        if (ok) refresh(ctx, repo)
        ok
    }

    /** The feed's GIFs, fetched, for the GIF panel's "Alpha packs". */
    suspend fun gifs(ctx: Context, repo: AlphaCoreRepository): List<GifEntry> = withContext(Dispatchers.IO) {
        load(ctx)
        if (posts.isEmpty()) refresh(ctx, repo)
        posts.filter { it.gif != null }.mapNotNull { p ->
            repo.downloadMediaToCache(p.gif!!)?.let { GifEntry(it, p.title, p.at * 1000) }
        }
    }
}
