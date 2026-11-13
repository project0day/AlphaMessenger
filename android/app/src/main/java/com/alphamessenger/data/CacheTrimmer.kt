package com.alphamessenger.data

import android.content.Context
import java.io.File

/**
 * LRU / TTL cache maintenance — the mature-messenger cache policy.
 *
 * Rules (per product spec):
 *  - Operates ONLY on `cacheDir` (message-media cache + notification
 *    thumbnails). NEVER touches SQLite / MLS storage / key packages / avatars /
 *    covers / profile / metadata / anything outside cacheDir.
 *  - Size mode = true LRU: trim oldest-first, by category priority, until under
 *    the limit — not a blanket "delete all".
 *  - Never deletes a file younger than 24h (a just-received blob must survive).
 *  - TTL for temporary media (voice 7d, image/video 14d); documents never
 *    auto-expire.
 *
 * Reuses the exact classification the Storage screen uses (DB media_ref join),
 * so what the user sees is what gets trimmed. Callable both from the settings
 * ViewModel and the background service.
 */
class CacheTrimmer(
    private val context: Context,
    private val repository: AlphaCoreRepository,
) {
    companion object {
        private const val PREFS = "storage_auto_cleanup"
        private const val MIN_AGE_MS = 24L * 3600_000            // protect <24h
        private const val CHECK_INTERVAL_MS = 12L * 3600_000     // background cadence
        // Deletion order: cheapest-to-regenerate first, documents last.
        private val PRIORITY = listOf("thumbnail", "image", "video", "voice", "file")
    }

    private data class Cand(val type: String, val file: File, val size: Long, val mtime: Long)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun mediaDir() = File(context.cacheDir, "media")
    private fun thumbsDir() = File(context.cacheDir, "notif_thumbs")

    /** Background entry point: run at most once per [CHECK_INTERVAL_MS]. */
    suspend fun runIfDue() {
        val last = prefs().getLong("last_check_ms", 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        runAutoClean()
    }

    /** Apply the user's saved policy. Cheap no-op when nothing qualifies. */
    suspend fun runAutoClean() {
        val p = prefs()
        p.edit().putLong("last_check_ms", System.currentTimeMillis()).apply()
        // Always: sweep orphan partial-download artifacts (>24h) left by crashes.
        // Runs regardless of trigger — it's pure junk, never a real cached file.
        cleanupOrphanParts()
        val trigger = p.getString("trigger", "never") ?: "never"
        if (trigger == "never") return

        val cats = enabledCats(p)
        var deleted = false

        // Only the rule the user chose — no hidden expiry on top of it.
        when (trigger) {
            "size_2gb" -> deleted = trimToLimit(cats, 2L * 1024 * 1024 * 1024) || deleted
            "size_5gb" -> deleted = trimToLimit(cats, 5L * 1024 * 1024 * 1024) || deleted
            "days_30"  -> deleted = deleteOlderThan(cats, 30) || deleted
            "days_90"  -> deleted = deleteOlderThan(cats, 90) || deleted
        }

        if (deleted) p.edit().putLong("last_cleanup_ms", System.currentTimeMillis()).apply()
    }

    /** Delete stale partial-download artifacts from the media cache: per-chunk
     *  `<hash>_c{i}.part`, `<hash>.assemble.tmp`, `*.part.tmp`. Only >24h old,
     *  so an in-progress download (fresh files) is never touched. The finished
     *  `cacheDir/media/<hash>` is a real file — left to the normal LRU/age pass. */
    private fun cleanupOrphanParts() {
        val now = System.currentTimeMillis()
        mediaDir().listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val n = f.name
            val isPartial = n.endsWith(".part") || n.endsWith(".part.tmp") || n.endsWith(".assemble.tmp")
            if (isPartial && now - f.lastModified() > MIN_AGE_MS) safeDelete(f)
        }
        // Working files of stickers, GIFs and the feed (a pack being previewed,
        // a pack list, a picture being posted) — leftovers once a day old.
        val tempPrefixes = listOf("stickerpack_", "feed_img_", "gif_make_")
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && tempPrefixes.any { f.name.startsWith(it) } && now - f.lastModified() > MIN_AGE_MS) f.delete()
        }
        java.io.File(context.cacheDir, "sticker_stage").listFiles()?.forEach { d ->
            if (now - d.lastModified() > MIN_AGE_MS) d.deleteRecursively()
        }
    }

    // --- Policy passes ---

    /** True LRU: delete oldest-first, by category priority, until ≤ limit. */
    private suspend fun trimToLimit(cats: Set<String>, limit: Long): Boolean {
        val cands = collectCandidates(cats)
        var total = cands.sumOf { it.size }
        if (total <= limit) return false
        val now = System.currentTimeMillis()
        var any = false
        for (type in PRIORITY) {
            if (total <= limit) break
            val bucket = cands.filter { it.type == type }.sortedBy { it.mtime } // oldest first
            for (c in bucket) {
                if (total <= limit) break
                if (now - c.mtime < MIN_AGE_MS) continue        // protect recent
                if (safeDelete(c.file)) { total -= c.size; any = true }
            }
        }
        return any
    }

    private suspend fun deleteOlderThan(cats: Set<String>, days: Int): Boolean {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000L
        var any = false
        for (c in collectCandidates(cats)) {
            if (c.mtime < cutoff && safeDelete(c.file)) any = true
        }
        return any
    }

    // --- Candidate collection (cacheDir-only) ---

    private suspend fun collectCandidates(cats: Set<String>): List<Cand> {
        val out = ArrayList<Cand>()
        val cachePrefix = context.cacheDir.absolutePath

        // Thumbnails are always eligible (cheap to regenerate).
        thumbsDir().listFiles()?.forEach { f ->
            if (f.isFile) out.add(Cand("thumbnail", f, f.length(), f.lastModified()))
        }

        // Typed message media via the DB media_ref join. Only files inside
        // cacheDir are ever candidates — a sender's own copy elsewhere is left.
        for (item in allMediaItems()) {
            if (item.type !in cats) continue
            for (f in filesFor(item)) {
                if (!f.absolutePath.startsWith(cachePrefix)) continue
                out.add(Cand(item.type, f, f.length(), f.lastModified()))
            }
        }
        return out
    }

    /** media types enabled for auto-cleanup (photo→image toggle mapping). */
    private fun enabledCats(p: android.content.SharedPreferences): Set<String> = buildSet {
        if (p.getBoolean("photo", true)) add("image")
        if (p.getBoolean("video", true)) add("video")
        if (p.getBoolean("voice", true)) add("voice")
        if (p.getBoolean("file", true)) add("file")
    }

    private fun safeDelete(f: File): Boolean {
        // Defence-in-depth: only ever delete inside our own cache dir.
        if (!f.absolutePath.startsWith(context.cacheDir.absolutePath)) return false
        // OUTBOX inside the cache occurs for older sends (`send_<time>_<name>`) and
        // is not cache: it's the send's source, the resume-download and the user's
        // own bubble. Auto-trim must never see it.
        if (MediaCacheRoots.isOutbox(context, f.absolutePath)) return false
        val gone = runCatching { f.isFile && f.delete() }.getOrDefault(false)
        // The "deleted" mark — otherwise auto-download would re-fetch the file
        // over the network with no user action at all.
        if (gone) MediaCacheRoots.markEvicted(f)
        return gone
    }

    // --- Classification (mirrors StorageUsageViewModel) ---

    private data class MediaItem(val type: String, val hash: String, val localPath: String)

    private suspend fun allMediaItems(): List<MediaItem> {
        // The core lists the attachments itself: whole messages never cross for this.
        return repository.storedMedia().map { s -> toItem(org.json.JSONObject().put("media_type", s.mediaType).put("hash", s.hash).put("local_path", s.localPath)) }
    }

    private fun toItem(o: org.json.JSONObject): MediaItem {
        val raw = o.optString("media_type", "file").ifBlank { "file" }
        val type = when (raw) {
            "image" -> "image"
            "video" -> "video"
            "audio" -> "voice"
            else -> "file"
        }
        return MediaItem(type, o.optString("hash"), o.optString("local_path"))
    }

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
}
