package com.alphamessenger.ui.sticker

import android.content.Context
import android.graphics.Bitmap
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.data.blobHashOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.Inflater

/** A sticker goes out as a picture whose file is named "sticker_…". */
const val STICKER_PREFIX = "sticker_"

/**
 * Marks a sticker message's caption: STICKER_MARK + emoji + "\n" + what the
 * sticker belongs to (a pack link, "builtin:<id>", or nothing). Never shown.
 * Built from Char(3) so the source stays pure ASCII.
 */
val STICKER_MARK: String = Char(3) + "STK" + Char(3)

/** How long an uploaded sticker is reused before it is uploaded afresh. */
private const val REF_MAX_AGE_MS = 30L * 24 * 3600 * 1000

data class Sticker(
    val file: String,
    val emoji: String,
    /** Its uploaded (encrypted) blob ref — sent again without uploading. */
    val ref: String? = null,
    val refAt: Long = 0L,
)

data class StickerPack(
    val id: String,
    val name: String,
    /** Made on this phone (can be edited and shared). */
    val own: Boolean,
    /** Ships with the app: everyone has it. */
    val builtin: Boolean = false,
    val stickers: List<Sticker> = emptyList(),
    /** The pack's current link (null until shared / for built-ins). */
    val link: String? = null,
    /** What the published version was built from — a change means publishing again. */
    val linkOf: String? = null,
    /** A pack made before its signing key lived in the core's database: moved there on first use. */
    val sign: String? = null,
    /** Updating packs: where its versions are found, and the key that opens them. */
    val pub: String? = null,
    val read: String? = null,
    /** The version (list ref) this phone has. */
    val version: String? = null,
    /** When this phone last looked for a newer version. */
    val checkedAt: Long = 0L,
    /** Where it came from when not made here: [SOURCE_NOSTR]. */
    val source: String? = null,
) {
    val cover: Sticker? get() = stickers.firstOrNull()

    companion object {
        /** Imported from a public Nostr emoji set (Nostr emoji import). */
        const val SOURCE_NOSTR = "nostr"
    }
}

/** What a received sticker says about itself. */
data class StickerTag(val emoji: String, val link: String?, val builtin: String?)

fun parseStickerCaption(caption: String?): StickerTag? {
    if (caption == null) return null
    val i = caption.indexOf(STICKER_MARK)
    if (i < 0) return null
    val rest = caption.substring(i + STICKER_MARK.length)
    val emoji = rest.substringBefore('\n')
    val tail = rest.substringAfter('\n', "").trim()
    return StickerTag(
        emoji = emoji,
        link = tail.takeIf { it.startsWith(STICKER_LINK_PREFIX) },
        builtin = tail.takeIf { it.startsWith("builtin:") }?.removePrefix("builtin:"),
    )
}

const val STICKER_LINK_PREFIX = "alpha://stickers/"

/**
 * The sticker packs on this phone, all in `filesDir/stickers/<pack>/`.
 *
 * A pack is shared the way end-to-end encrypted messengers usually do it: each
 * sticker is an encrypted blob; the pack itself is an encrypted list of those
 * blobs' refs; the link carries that list's ref — key included. Servers see
 * only ciphertext; whoever holds the link (or a sticker, which carries it) can
 * add the pack. A sticker once uploaded is sent again as the same ref.
 */
object StickerStore {
    private val _version = MutableStateFlow(0)
    /** Bumped on every change, so open screens reload. */
    val version: StateFlow<Int> = _version

    const val SAVED_ID = "saved"

    private fun root(ctx: Context) = File(ctx.filesDir, "stickers").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id).apply { mkdirs() }
    private fun meta(ctx: Context, id: String) = File(dir(ctx, id), "pack.json")

    // --- Reading / writing packs ---

    private fun toJson(p: StickerPack): JSONObject {
        val arr = JSONArray()
        p.stickers.forEach { s ->
            arr.put(JSONObject().put("f", File(s.file).name).put("e", s.emoji)
                .apply { s.ref?.let { put("r", it); put("t", s.refAt) } })
        }
        return JSONObject().put("id", p.id).put("n", p.name).put("own", p.own).put("b", p.builtin)
            .put("s", arr).put("ca", p.checkedAt).apply {
                p.link?.let { put("l", it) }; p.linkOf?.let { put("lo", it) }
                p.sign?.let { put("sg", it) }; p.pub?.let { put("pk", it) }; p.read?.let { put("rk", it) }
                p.version?.let { put("v", it) }
                p.source?.let { put("src", it) }
            }
    }

    private fun fromJson(ctx: Context, o: JSONObject): StickerPack {
        val id = o.getString("id")
        val d = dir(ctx, id)
        val arr = o.optJSONArray("s") ?: JSONArray()
        val stickers = (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            Sticker(File(d, s.optString("f")).absolutePath, s.optString("e"),
                s.optString("r").takeIf { it.isNotBlank() }, s.optLong("t"))
        }.filter { File(it.file).length() > 0 }
        fun opt(k: String) = o.optString(k).takeIf { it.isNotBlank() }
        return StickerPack(id, o.optString("n"), o.optBoolean("own"), o.optBoolean("b"), stickers,
            opt("l"), opt("lo"), opt("sg"), opt("pk"), opt("rk"), opt("v"), o.optLong("ca"), opt("src"))
    }

    @Synchronized
    fun save(ctx: Context, p: StickerPack) {
        meta(ctx, p.id).writeText(toJson(p).toString())
        _version.value++
    }

    @Synchronized
    fun pack(ctx: Context, id: String): StickerPack? =
        // Read without [dir], which would make an empty folder for a pack that is not here.
        File(File(root(ctx), id), "pack.json").takeIf { it.exists() }?.let { runCatching { fromJson(ctx, JSONObject(it.readText())) }.getOrNull() }

    // The packs as last read, for the version they were read at: every write bumps
    // [version], so a screen asking again (a list scrolling past) reads no disk.
    private var loaded: Pair<Int, List<StickerPack>>? = null

    /** Every pack: built-ins first, then own, then added ones. */
    @Synchronized
    fun packs(ctx: Context): List<StickerPack> {
        val v = _version.value
        val all = loaded?.takeIf { it.first == v }?.second ?: run {
            AlphaGhostPack.ensure(ctx)
            root(ctx).listFiles().orEmpty().mapNotNull { pack(ctx, it.name) }.also { loaded = _version.value to it }
        }
        val order = orderOf(ctx)
        return all.sortedWith(compareBy({ !it.builtin }, { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }))
    }

    private fun orderOf(ctx: Context): List<String> =
        ctx.getSharedPreferences("stickers", Context.MODE_PRIVATE).getString("order", "")!!.split(',').filter { it.isNotBlank() }

    private fun touchOrder(ctx: Context, id: String) {
        val list = listOf(id) + orderOf(ctx).filter { it != id }
        ctx.getSharedPreferences("stickers", Context.MODE_PRIVATE).edit().putString("order", list.joinToString(",")).apply()
        _version.value++
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
        _version.value++
    }

    fun newPack(ctx: Context, name: String): StickerPack {
        val p = StickerPack(UUID.randomUUID().toString().replace("-", "").take(16), name, own = true)
        save(ctx, p)
        touchOrder(ctx, p.id)
        return p
    }

    // --- Stickers ---

    /** Write [bmp] (512×512) into own pack [packId] as the next sticker. */
    suspend fun addSticker(ctx: Context, packId: String, bmp: Bitmap, emoji: String): Boolean = withContext(Dispatchers.IO) {
        val p = pack(ctx, packId) ?: return@withContext false
        val f = File(dir(ctx, packId), "$STICKER_PREFIX${packId}_${System.currentTimeMillis()}.webp")
        val ok = runCatching {
            f.outputStream().use {
                @Suppress("DEPRECATION")
                val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bmp.compress(fmt, 90, it)
            }
        }.isSuccess && f.length() > 0
        if (ok) save(ctx, p.copy(stickers = p.stickers + Sticker(f.absolutePath, emoji)))
        ok
    }

    /** An animated sticker, kept as the file it is ([bytes] already cleaned). */
    suspend fun addAnimated(ctx: Context, packId: String, bytes: ByteArray, emoji: String): Boolean = withContext(Dispatchers.IO) {
        val p = pack(ctx, packId) ?: return@withContext false
        val ext = when {
            AnimatedSticker.isGzip(bytes) -> "tgs"
            AnimatedSticker.isGif(bytes) -> "gif"
            else -> "webp"
        }
        // Unique even for many added in the same millisecond (an import).
        val f = File(dir(ctx, packId), "$STICKER_PREFIX${packId}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.$ext")
        val ok = runCatching { f.writeBytes(bytes) }.isSuccess && f.length() > 0
        if (ok) save(ctx, p.copy(stickers = p.stickers + Sticker(f.absolutePath, emoji)))
        ok
    }

    fun removeSticker(ctx: Context, packId: String, file: String) {
        val p = pack(ctx, packId) ?: return
        save(ctx, p.copy(stickers = p.stickers.filter { it.file != file }))
        File(file).delete()
    }

    fun setEmoji(ctx: Context, packId: String, file: String, emoji: String) {
        val p = pack(ctx, packId) ?: return
        save(ctx, p.copy(stickers = p.stickers.map { if (it.file == file) it.copy(emoji = emoji) else it }))
    }

    fun rename(ctx: Context, packId: String, name: String) {
        val p = pack(ctx, packId) ?: return
        save(ctx, p.copy(name = name.trim().ifBlank { p.name }))
    }

    /**
     * The sticker's blob ref, uploading it when it has none (or an old one).
     * The file is also put where the chat looks for downloaded media, so our
     * own bubble shows it at once.
     */
    suspend fun refFor(ctx: Context, repo: AlphaCoreRepository, packId: String, s: Sticker): String? = withContext(Dispatchers.IO) {
        val fresh = s.ref?.takeIf { s.refAt == 0L || System.currentTimeMillis() - s.refAt < REF_MAX_AGE_MS }
        val ref = fresh ?: run {
            val p = pack(ctx, packId)
            // Someone else's pack: their refs are the only ones there are.
            if (p != null && !p.own && s.ref != null) return@run s.ref
            val r = repo.uploadBlobRefOnly(s.file, "image", null, "stk_${File(s.file).name}_${System.currentTimeMillis()}") ?: return@withContext null
            synchronized(this@StickerStore) {
                pack(ctx, packId)?.let { cur ->
                    save(ctx, cur.copy(stickers = cur.stickers.map { if (it.file == s.file) it.copy(ref = r, refAt = System.currentTimeMillis()) else it }))
                }
            }
            r
        }
        prefillCache(ctx, ref, s.file)
        ref
    }

    private fun prefillCache(ctx: Context, ref: String, file: String) {
        val hash = blobHashOf(ref).takeIf { it.isNotBlank() } ?: return
        val out = File(File(ctx.cacheDir, "media").apply { mkdirs() }, hash)
        if (!out.exists() || out.length() == 0L) runCatching { File(file).copyTo(out, overwrite = true) }
    }

    // --- Recent ---

    fun recent(ctx: Context): List<Pair<String, Sticker>> {
        val raw = ctx.getSharedPreferences("stickers", Context.MODE_PRIVATE).getString("recent", "")!!
        return raw.split('|').filter { it.contains('/') }.mapNotNull { key ->
            val (pid, name) = key.split('/', limit = 2)
            pack(ctx, pid)?.stickers?.firstOrNull { File(it.file).name == name }?.let { pid to it }
        }
    }

    fun noteUsed(ctx: Context, packId: String, s: Sticker) {
        val key = "$packId/${File(s.file).name}"
        val prefs = ctx.getSharedPreferences("stickers", Context.MODE_PRIVATE)
        val list = (listOf(key) + prefs.getString("recent", "")!!.split('|').filter { it.isNotBlank() && it != key }).take(16)
        prefs.edit().putString("recent", list.joinToString("|")).apply()
        touchOrder(ctx, packId)
    }

    // --- Saving a received sticker ---

    /** Keep a received sticker in "Saved" (its ref travels with it). */
    suspend fun saveReceived(ctx: Context, path: String, ref: String?, emoji: String, savedName: String): Boolean = withContext(Dispatchers.IO) {
        val p = pack(ctx, SAVED_ID) ?: StickerPack(SAVED_ID, savedName, own = true).also { save(ctx, it); touchOrder(ctx, SAVED_ID) }
        val f = File(dir(ctx, SAVED_ID), "$STICKER_PREFIX${SAVED_ID}_${System.currentTimeMillis()}.webp")
        if (runCatching { File(path).copyTo(f, overwrite = true) }.isFailure) return@withContext false
        save(ctx, p.copy(stickers = p.stickers + Sticker(f.absolutePath, emoji, ref, if (ref != null) System.currentTimeMillis() else 0L)))
        true
    }

    /** The pack an import made (null: none) and how many stickers it took. */
    data class Imported(val packId: String?, val count: Int, val alreadyHere: Boolean = false)

    /**
     * A new own pack from an archive of stickers: vector animations (.tgs, .json,
     * .lottie — kept as gzipped Lottie), animated WebP/GIF (cleaned of metadata) or
     * still pictures, each held to the sticker limits. An optional `pack.json`
     * (`{"name", "items": [{"f": file, "e": emoji}]}`) names the pack and gives emoji;
     * without it the pack takes the archive's name and its files in number order.
     * Nothing usable: no pack is made.
     */
    suspend fun importArchive(ctx: Context, uri: android.net.Uri): Imported = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, ByteArray>()
        var spec: org.json.JSONObject? = null
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                java.util.zip.ZipInputStream(input).use { zip ->
                    var total = 0L
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory || e.name.contains('/') || e.name.contains('\\')) continue
                        // Read no further than an entry may be: an oversized one is skipped, not loaded.
                        val bytes = readAtMost(zip, AnimatedSticker.VECTOR_MAX_JSON + 1)
                        if (bytes.size > AnimatedSticker.VECTOR_MAX_JSON) continue
                        total += bytes.size
                        if (total > IMPORT_MAX_BYTES || files.size >= IMPORT_MAX_ITEMS + 2) break
                        if (e.name == "pack.json") spec = org.json.JSONObject(String(bytes)) else files[e.name] = bytes
                    }
                }
            }
        }
        val listed: List<Pair<String, String>> = spec?.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { it.optString("f") to it.optString("e") } }
        } ?: files.keys.filter { it.substringAfterLast('.').lowercase() in STICKER_FILES }
            .sortedWith(compareBy({ numberIn(it) }, { it })).map { it to "" }
        if (listed.isEmpty()) return@withContext Imported(null, 0)
        // The same archive again is the same pack: it is found, not made twice.
        val print = IMPORT_SOURCE + fingerprint(listed.mapNotNull { files[it.first] })
        packs(ctx).firstOrNull { it.own && it.source == print }?.let { have ->
            // Same stickers, maybe now with their emoji (and title): those go into the pack there is.
            val emoji = listed.map { it.second }
            val title = spec?.optString("name")?.takeIf { it.isNotBlank() }
            if (emoji.any { it.isNotBlank() } || title != null) save(ctx, have.copy(
                name = title?.take(64) ?: have.name,
                stickers = have.stickers.mapIndexed { i, s -> emoji.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { s.copy(emoji = it.take(16)) } ?: s },
            ))
            return@withContext Imported(have.id, have.stickers.size, alreadyHere = true)
        }
        val name = spec?.optString("name")?.takeIf { it.isNotBlank() } ?: archiveName(ctx, uri)
        val pack = newPack(ctx, name.take(64)).copy(source = print).also { save(ctx, it) }
        var added = 0
        for ((file, emoji) in listed.take(IMPORT_MAX_ITEMS)) {
            val raw = files[file] ?: continue
            val vec = AnimatedSticker.vector(raw)
            // An animation the renderer cannot draw through would crash wherever it shows: left out.
            if (vec != null && AnimatedSticker.vectorJson(vec)?.let { drawsSafely(it, "import_${pack.id}_$file") } != true) continue
            val bytes = vec
                ?: raw.takeIf { it.size <= ANIMATED_MAX_BYTES }?.let { AnimatedSticker.clean(it) ?: it.takeIf { stillPictureFits(it) } }
                ?: continue
            if (addAnimated(ctx, pack.id, bytes, emoji.take(16))) added++
        }
        if (added == 0) delete(ctx, pack.id)
        Imported(pack.id.takeIf { added > 0 }, added)
    }

    /** What an imported pack is made of, so the same archive is recognised. */
    private fun fingerprint(parts: List<ByteArray>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    private const val IMPORT_SOURCE = "import:"

    /** The file kinds a sticker archive brings. */
    private val STICKER_FILES = setOf("tgs", "json", "lottie", "webp", "gif", "png")

    /** "Name_12.tgs" → 12: stickers keep their pack's order. */
    private fun numberIn(file: String): Int =
        Regex("(\\d+)(?=\\.[^.]+$)").find(file)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    /** The archive's own name, without ".zip" and a copy mark like " (2)". */
    private fun archiveName(ctx: Context, uri: android.net.Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.')?.replace(Regex("\\s*\\(\\d+\\)$"), "")?.trim()?.takeIf { it.isNotBlank() } ?: "Pack"

    /** A still picture a sticker may be: it decodes, within the largest side. */
    private fun stillPictureFits(b: ByteArray): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return o.outWidth in 1..ANIMATED_MAX_SIDE && o.outHeight in 1..ANIMATED_MAX_SIDE
    }

    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private const val IMPORT_MAX_ITEMS = 120
    private const val IMPORT_MAX_BYTES = 64L shl 20

    /** An animated emoji kept as a sticker in "Saved" (its animation file, as it is). */
    suspend fun saveAnimatedEmoji(ctx: Context, emoji: String, savedName: String): Boolean = withContext(Dispatchers.IO) {
        val code = com.alphamessenger.ui.emoji.AnimatedEmoji.codeOf(ctx, emoji) ?: return@withContext false
        val json = com.alphamessenger.ui.emoji.AnimatedEmoji.json(ctx, code) ?: return@withContext false
        val p = pack(ctx, SAVED_ID) ?: StickerPack(SAVED_ID, savedName, own = true).also { save(ctx, it); touchOrder(ctx, SAVED_ID) }
        val f = File(dir(ctx, SAVED_ID), "$STICKER_PREFIX${SAVED_ID}_${System.currentTimeMillis()}.json")
        if (runCatching { f.writeText(json) }.isFailure) return@withContext false
        save(ctx, p.copy(stickers = p.stickers + Sticker(f.absolutePath, emoji)))
        true
    }

    // --- Links ---

    private val B64 = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    private fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, B64)

    private fun inflate(b: ByteArray): String {
        val i = Inflater().apply { setInput(b) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!i.finished()) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; out.write(buf, 0, n) }
        i.end()
        return out.toString(Charsets.UTF_8.name())
    }

    private fun hexBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** The pack's lasting link: `[2][pub 32][read 32]` — where its versions are, and the key to them. */
    private fun linkOf(pub: String, read: String) = STICKER_LINK_PREFIX + b64(byteArrayOf(2) + hexBytes(pub) + hexBytes(read))

    /** What a link points at: an updating pack (pub, read), or a fixed list (its ref). */
    private sealed class Target {
        data class Updating(val pub: String, val read: String) : Target()
        data class Fixed(val listRef: String) : Target()
    }

    private fun parse(link: String): Target? = runCatching {
        val raw = android.util.Base64.decode(link.removePrefix(STICKER_LINK_PREFIX).trim(), B64)
        if (raw.size == 65 && raw[0].toInt() == 2) Target.Updating(hexOf(raw.copyOfRange(1, 33)), hexOf(raw.copyOfRange(33, 65)))
        else Target.Fixed(inflate(raw)) // the first links: the list's ref itself
    }.getOrNull()

    private val publishing = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** An own pack's keys (made once: then its link never changes). */
    suspend fun ensureKeys(ctx: Context, repo: AlphaCoreRepository, packId: String): StickerPack? = withContext(Dispatchers.IO) {
        val p = pack(ctx, packId) ?: return@withContext null
        if (!p.own) return@withContext p
        if (p.pub != null && p.read != null) {
            if (p.sign == null) return@withContext p
            if (!repo.stickerPackAdoptKey(p.sign)) return@withContext null
            return@withContext synchronized(this@StickerStore) {
                pack(ctx, packId)?.copy(sign = null)?.also { save(ctx, it) }
            }
        }
        val parts = repo.stickerPackKeys()?.split(':') ?: return@withContext null
        if (parts.size != 2) return@withContext null
        synchronized(this@StickerStore) {
            val cur = pack(ctx, packId) ?: return@withContext null
            val next = cur.copy(pub = parts[0], read = parts[1], link = linkOf(parts[0], parts[1]))
            save(ctx, next)
            next
        }
    }

    /**
     * Publish the own pack's current stickers as its latest version (every
     * sticker uploaded once, the list uploaded encrypted, its ref published under
     * the pack's key). Nothing is sent when nothing changed. Returns its link.
     */
    suspend fun publishLatest(ctx: Context, repo: AlphaCoreRepository, packId: String): String? = withContext(Dispatchers.IO) {
        val lock = publishing.getOrPut(packId) { kotlinx.coroutines.sync.Mutex() }
        lock.lock()
        try {
            var p = ensureKeys(ctx, repo, packId) ?: return@withContext null
            if (!p.own || p.builtin) return@withContext null
            val gate = kotlinx.coroutines.sync.Semaphore(6)
            val refs = kotlinx.coroutines.coroutineScope {
                p.stickers.map { s -> async { gate.withPermit { refFor(ctx, repo, packId, s) } } }.map { it.await() }
            }.map { it ?: return@withContext null }
            val sig = (refs + p.stickers.map { it.emoji } + p.name).joinToString("|").hashCode().toString()
            p = pack(ctx, packId) ?: return@withContext null
            if (p.linkOf == sig && p.version != null) return@withContext p.link
            val manifest = JSONObject().put("v", 1).put("id", p.id).put("n", p.name).put("s",
                JSONArray().apply { p.stickers.zip(refs).forEach { (s, r) -> put(JSONObject().put("r", r).put("e", s.emoji)) } })
            val mf = File(ctx.cacheDir, "stickerpack_${p.id}.json").apply { writeText(manifest.toString()) }
            val listRef = repo.uploadBlobRefOnly(mf.absolutePath, "file", null, "stkpack_${p.id}_${System.currentTimeMillis()}")
            mf.delete()
            listRef ?: return@withContext null
            val ok = repo.stickerPackPublish(p.pub!!, p.read!!, listRef)
            if (!ok) return@withContext null
            pack(ctx, packId)?.let { save(ctx, it.copy(linkOf = sig, version = listRef)) }
            p.link
        } finally { lock.unlock() }
    }

    /** An own pack's link for sharing: published first, so whoever opens it finds the stickers. */
    suspend fun linkFor(ctx: Context, repo: AlphaCoreRepository, packId: String): String? = publishLatest(ctx, repo, packId)

    /** A pack as a link describes it — its latest version — fetched into a staging folder. */
    data class Preview(val id: String, val name: String, val stickers: List<Sticker>,
                       val version: String? = null, val pub: String? = null, val read: String? = null)

    /**
     * What the phone already has of the pack behind [link], shown at once: the
     * added pack itself, or this session's last fetch of it. The network is
     * asked afterwards ([preview]) only to bring it up to date.
     */
    fun previewNow(ctx: Context, link: String): Preview? {
        val t = parse(link)
        val local = packs(ctx).firstOrNull { p ->
            !p.own && (p.link == link || (t is Target.Updating && p.pub == t.pub && p.read == t.read))
        }
        if (local != null) return Preview(local.id, local.name, local.stickers, local.version, local.pub, local.read)
        return synchronized(fetched) { fetched[link] }?.second
    }

    // The last fetch of each pack this session: a second tap shows it at once.
    private val fetched = HashMap<String, Pair<Long, Preview>>()
    private const val FETCHED_FRESH_MS = 10 * 60_000L

    suspend fun preview(ctx: Context, repo: AlphaCoreRepository, link: String): Preview? = withContext(Dispatchers.IO) {
        synchronized(fetched) { fetched[link] }?.let { (at, pv) ->
            if (System.currentTimeMillis() - at < FETCHED_FRESH_MS) return@withContext pv
        }
        val pv = when (val t = parse(link)) {
            is Target.Fixed -> fetchList(ctx, repo, t.listRef)
            is Target.Updating -> {
                val listRef = repo.stickerPackLatest(t.pub, t.read).takeIf { it.isNotBlank() }
                    ?: return@withContext null
                fetchList(ctx, repo, listRef)?.copy(pub = t.pub, read = t.read)
            }
            null -> null
        }
        if (pv != null) {
            synchronized(fetched) { fetched[link] = System.currentTimeMillis() to pv }
            pv.version?.let { ctx.getSharedPreferences(SEEN_PACKS, Context.MODE_PRIVATE).edit().putString(link, it).apply() }
        }
        pv
    }

    private const val SEEN_PACKS = "sticker_packs_seen"

    /**
     * The version of [link] last fetched on this phone (any earlier session), built
     * from the stickers already cached: a pack opened again shows at once while
     * [preview] asks the network whether it changed.
     */
    suspend fun previewStored(ctx: Context, repo: AlphaCoreRepository, link: String): Preview? {
        val ref = ctx.getSharedPreferences(SEEN_PACKS, Context.MODE_PRIVATE).getString(link, null) ?: return null
        val t = parse(link) as? Target.Updating
        return fetchList(ctx, repo, ref)?.let { if (t != null) it.copy(pub = t.pub, read = t.read) else it }
    }

    private suspend fun fetchList(ctx: Context, repo: AlphaCoreRepository, listRef: String): Preview? {
        val path = repo.downloadMediaToCache(listRef) ?: return null
        val m = runCatching { JSONObject(String(com.alphamessenger.data.MediaVault.readBytes(File(path)))) }.getOrNull() ?: return null
        val id = m.optString("id").filter { it.isLetterOrDigit() }.take(32).ifBlank { return null }
        val arr = m.optJSONArray("s") ?: return null
        val stage = File(ctx.cacheDir, "sticker_stage/$id").apply { mkdirs() }
        // File names first (the same picture twice — an emoji added twice — gets its
        // own file each time), then the downloads, several at once.
        val seen = HashMap<String, Int>()
        val wanted = (0 until minOf(arr.length(), 120)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val r = o.optString("r").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Named by the sticker's content, so a version that keeps it keeps its file.
            val base = blobHashOf(r).take(16).ifBlank { "$i" }
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            Triple(File(stage, "$STICKER_PREFIX${id}_$base${if (n > 1) "_$n" else ""}.webp"), o.optString("e"), r)
        }
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        val stickers = kotlinx.coroutines.coroutineScope {
            wanted.map { (f, emoji, r) ->
                async {
                    if (!f.exists()) gate.withPermit {
                        val got = repo.downloadMediaToCache(r) ?: return@async null
                        runCatching { File(got).copyTo(f, overwrite = true) }
                    }
                    if (f.exists()) Sticker(f.absolutePath, emoji, r, 0L) else null
                }
            }.mapNotNull { it.await() }
        }
        return Preview(id, m.optString("n").take(64), stickers, version = listRef)
    }

    /** Add a previewed pack, or bring an added one up to this version. */
    @Synchronized
    fun add(ctx: Context, pv: Preview, link: String) {
        val d = dir(ctx, pv.id)
        val moved = pv.stickers.mapNotNull { s ->
            val f = File(d, File(s.file).name)
            if (f.exists() || runCatching { File(s.file).copyTo(f, overwrite = true) }.isSuccess) s.copy(file = f.absolutePath) else null
        }
        val keep = moved.map { File(it.file).name }.toSet() + "pack.json"
        d.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        save(ctx, StickerPack(pv.id, pv.name, own = false, stickers = moved, link = link,
            pub = pv.pub, read = pv.read, version = pv.version, checkedAt = System.currentTimeMillis()))
        touchOrder(ctx, pv.id)
    }

    /**
     * Bring added updating packs up to date — at most every [everyMs] each.
     * Called when the stickers tab opens; a sticker tapped in a chat looks at once.
     */
    suspend fun refreshAdded(ctx: Context, repo: AlphaCoreRepository, everyMs: Long = 15 * 60_000L, only: String? = null) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        packs(ctx).filter {
            (only == null || it.id == only) &&
                !it.own && !it.builtin && it.pub != null && it.read != null && now - it.checkedAt > everyMs
        }.forEach { p ->
            val latest = repo.stickerPackLatest(p.pub!!, p.read!!)
            if (latest.isNotBlank() && latest != p.version) {
                fetchList(ctx, repo, latest)?.let { add(ctx, it.copy(pub = p.pub, read = p.read), p.link ?: linkOf(p.pub, p.read)) }
            } else pack(ctx, p.id)?.let { save(ctx, it.copy(checkedAt = now)) }
        }
    }

    /**
     * A sticker seen in a chat, from an added pack that does not have it yet:
     * the pack has a newer version — fetch it now, in the background (at most
     * once a minute per pack).
     */
    suspend fun noticeSticker(ctx: Context, repo: AlphaCoreRepository, link: String, ref: String) = withContext(Dispatchers.IO) {
        val t = parse(link) as? Target.Updating ?: return@withContext
        val p = packs(ctx).firstOrNull { !it.own && it.pub == t.pub && it.read == t.read } ?: return@withContext
        val h = blobHashOf(ref)
        if (h.isBlank() || p.stickers.any { blobHashOf(it.ref.orEmpty()) == h }) return@withContext
        refreshAdded(ctx, repo, everyMs = 60_000L, only = p.id)
    }

    /** Added on this phone (asking never creates anything on disk). */
    fun has(ctx: Context, id: String) = packs(ctx).any { it.id == id }
}
