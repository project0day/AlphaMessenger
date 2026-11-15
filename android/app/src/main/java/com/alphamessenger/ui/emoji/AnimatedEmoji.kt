package com.alphamessenger.ui.emoji

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.asImageBitmap
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.alphamessenger.data.AlphaCoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Animated emoji: one set of vector animations, announced in the Alpha feed as
 * one encrypted archive, fetched once when first needed and kept on the phone.
 * Nothing is asked of anyone else: the feed is read anonymously and the archive
 * comes as any media blob.
 */
object AnimatedEmoji {
    private const val DIR = "anim_emoji"
    private const val MAX_ENTRIES = 2000
    private const val MAX_ENTRY_BYTES = 1L shl 20
    private const val MAX_TOTAL_BYTES = 120L shl 20
    private val NAME = Regex("^[0-9a-f_]{1,64}\\.json$")

    private val _ready = MutableStateFlow(0)
    /** Bumped when a set has been installed: views waiting for it look again. */
    val ready: StateFlow<Int> = _ready

    /** What the set is fetched with: set once by the app. */
    @Volatile var repo: AlphaCoreRepository? = null

    @Volatile private var codes: Set<String>? = null
    private val installing = Mutex()
    @Volatile private var triedAt = 0L

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("anim_emoji", Context.MODE_PRIVATE)

    private fun codes(ctx: Context): Set<String> = codes ?: (dir(ctx).list()?.filter { it.endsWith(".json") && it != INDEX }
        ?.map { it.removeSuffix(".json") }?.toSet() ?: emptySet()).also { codes = it }

    /** The set's name of [emoji] (code points in hex, "_" between), if the set has it. */
    fun codeOf(ctx: Context, emoji: String): String? = match(emoji, codes(ctx))

    /** [codeOf] without touching the disk: null until the set has been listed once. */
    fun codePeek(emoji: String): String? = codes?.let { match(emoji, it) }

    private fun match(emoji: String, have: Set<String>): String? {
        val key = emoji.codePoints().toArray().joinToString("_") { Integer.toHexString(it) }
        return listOf(key, key.replace("_fe0f", ""), key + "_fe0f").firstOrNull { it in have }
    }

    fun json(ctx: Context, code: String): String? = runCatching { File(dir(ctx), "$code.json").readText() }.getOrNull()

    /**
     * Install every set the feed announces, together: one folder, the older sets
     * first, so where two sets draw the same emoji the newer one wins. Done again
     * only when the announced sets change. Tried at most every few minutes; a
     * failure leaves what is installed (or nothing).
     */
    suspend fun ensure(ctx: Context, repo: AlphaCoreRepository, force: Boolean = false) = withContext(Dispatchers.IO) {
        // Removed in settings: fetched again only when asked there.
        if (force) { prefs(ctx).edit().putBoolean("off", false).apply(); triedAt = 0L }
        else if (prefs(ctx).getBoolean("off", false)) return@withContext
        if (System.currentTimeMillis() - triedAt < RETRY_MS) return@withContext
        installing.withLock {
            if (System.currentTimeMillis() - triedAt < RETRY_MS) return@withLock
            triedAt = System.currentTimeMillis()
            val feed = com.alphamessenger.ui.feed.FeedStore
            if (feed.posts.isEmpty()) feed.refresh(ctx, repo)
            val refs = announced()
            if (refs.isEmpty()) {
                // Not announced yet: look again soon, not after the long pause.
                triedAt = System.currentTimeMillis() - RETRY_MS + NO_SET_RETRY_MS
                return@withLock
            }
            if (installedRefs(ctx) == refs && codes(ctx).isNotEmpty()) return@withLock
            val archives = refs.mapNotNull { ref -> repo.downloadMediaToCache(ref)?.let { File(it) } }
            // A set that will not come now must not take the others down with it.
            if (archives.isEmpty()) return@withLock
            if (install(ctx, archives)) {
                prefs(ctx).edit().putString("ref", if (archives.size == refs.size) refs.joinToString(" ") else "").apply()
                _ready.value++
            }
        }
    }

    /** The sets the feed announces, oldest first. */
    private fun announced(): List<String> =
        com.alphamessenger.ui.feed.FeedStore.posts.filter { it.set != null }.sortedBy { it.at }.mapNotNull { it.set }.distinct()

    private fun installedRefs(ctx: Context): List<String> =
        prefs(ctx).getString("ref", null).orEmpty().split(' ').filter { it.isNotBlank() }

    /** Unpack [archives] (oldest first) into a fresh folder with one index, then swap: a broken archive changes nothing. */
    private fun install(ctx: Context, archives: List<File>): Boolean {
        val fresh = File(ctx.filesDir, "$DIR.new").apply { deleteRecursively(); mkdirs() }
        var total = 0L
        var count = 0
        val merged = LinkedHashMap<String, org.json.JSONObject>()
        var any = false
        for (archive in archives) {
            val ok = runCatching {
                ZipInputStream(com.alphamessenger.data.MediaVault.open(archive)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        // Only flat "<code>.json" names and the index: nothing can land outside the folder.
                        if (e.isDirectory || !(NAME.matches(e.name) || e.name == INDEX)) continue
                        if (++count > MAX_ENTRIES * 2) error("too many entries")
                        val bytes = readLimited(zip, MAX_ENTRY_BYTES) ?: error("entry too big")
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES * 2) error("archives too big")
                        if (e.name == INDEX) {
                            val arr = org.json.JSONArray(String(bytes))
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> o.optString("c").takeIf { it.isNotBlank() }?.let { merged[it] = o } }
                        } else File(fresh, e.name).writeBytes(bytes)
                    }
                }
            }.isSuccess
            any = any || ok
        }
        if (!any || fresh.list().isNullOrEmpty()) { fresh.deleteRecursively(); return false }
        // Codes a set brought without an index entry still show, in "objects".
        if (merged.isNotEmpty()) fresh.list()?.filter { it != INDEX }?.map { it.removeSuffix(".json") }?.filter { it !in merged }
            ?.forEach { merged[it] = org.json.JSONObject().put("c", it).put("g", "objects") }
        if (merged.isNotEmpty()) File(fresh, INDEX).writeText(org.json.JSONArray(merged.values.toList()).toString())
        val old = File(ctx.filesDir, "$DIR.old").apply { deleteRecursively() }
        dir(ctx).renameTo(old)
        val swapped = fresh.renameTo(dir(ctx))
        if (!swapped) { old.renameTo(dir(ctx)); fresh.deleteRecursively(); return false }
        old.deleteRecursively()
        codes = null
        index = null
        return true
    }


    /** Is the set [ref] among those on this phone? */
    fun has(ctx: Context, ref: String): Boolean = codes(ctx).isNotEmpty() && ref in installedRefs(ctx)
    fun anyInstalled(ctx: Context): Boolean = codes(ctx).isNotEmpty()

    private val shown = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * True once for a message that has just been sent or received (it plays then,
     * whatever the setting): its first showing, within moments of its time.
     */
    fun justArrived(id: String, timestamp: String): Boolean {
        if (!shown.add(id)) return false
        val at = runCatching { java.time.OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }.getOrNull() ?: return false
        return kotlin.math.abs(System.currentTimeMillis() - at) < JUST_ARRIVED_MS
    }

    /** The set off this phone; it comes back only when downloaded again in settings. */
    fun remove(ctx: Context) {
        prefs(ctx).edit().putBoolean("off", true).remove("ref").apply()
        dir(ctx).deleteRecursively()
        codes = null
        index = null
        _ready.value++
    }

    /** How much the set takes on this phone, in bytes. */
    fun sizeOnPhone(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** One animation of the set: its emoji, its group and its name. */
    data class Entry(val code: String, val emoji: String, val group: String, val name: String)

    @Volatile private var index: List<Entry>? = null

    /** The set in its own order (most used first), grouped by [Entry.group]; empty until installed. */
    fun index(ctx: Context): List<Entry> = index ?: runCatching {
        val arr = org.json.JSONArray(File(dir(ctx), INDEX).readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("c").takeIf { NAME.matches("$it.json") } ?: return@mapNotNull null
            val emoji = runCatching { code.split("_").joinToString("") { String(Character.toChars(it.toInt(16))) } }.getOrNull() ?: return@mapNotNull null
            Entry(code, emoji, o.optString("g", "objects"), o.optString("n"))
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { index = it }
        // A set published without its index: everything in one group, so it still opens.
        ?: codes(ctx).sorted().mapNotNull { code ->
            runCatching { code.split("_").joinToString("") { String(Character.toChars(it.toInt(16))) } }.getOrNull()
                ?.let { Entry(code, it, "smileys", "") }
        }

    private fun readLimited(input: java.io.InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
    }

    private const val RETRY_MS = 5 * 60_000L
    private const val NO_SET_RETRY_MS = 60_000L
    private const val JUST_ARRIVED_MS = 30_000L
    private const val INDEX = "index.json"
}

/**
 * [emoji] animated, [plays] times, again whenever [replay] changes; the plain
 * emoji ([fallback]) until the set is on the phone or when it lacks this one.
 */
@Composable
fun AnimatedEmojiView(
    emoji: String,
    size: Dp,
    modifier: Modifier = Modifier,
    plays: Int = 1,
    replay: Any? = null,
    /** false: stand still until [replay] changes (a list scrolling past). */
    autoPlay: Boolean = true,
    /** Where the plain emoji goes: it is a font picture, sharp only when not blown up. */
    fallbackModifier: Modifier = modifier,
    fallback: @Composable () -> Unit = { DefaultEmoji(emoji, size) },
) {
    val ctx = LocalContext.current
    val ready by AnimatedEmoji.ready.collectAsState()
    // Kept with the emoji it was found for: a reused cell must not show the previous one.
    // Known at once when the set is listed: the first frame already has the final
    // size, so a bubble never grows under a list gliding past it.
    val found by produceState<Pair<String, String?>?>(null, emoji, ready) {
        value = emoji to withContext(Dispatchers.IO) { AnimatedEmoji.codeOf(ctx, emoji) }
    }
    val c = found?.takeIf { it.first == emoji }?.second ?: AnimatedEmoji.codePeek(emoji) ?: run {
        Box(fallbackModifier, contentAlignment = Alignment.Center) { fallback() }
        return
    }
    // At rest it is a picture drawn once; a live animation exists only while it plays,
    // so a list scrolling past moves no animation at all.
    val firstReplay = remember { replay }
    var playing by remember(c) { androidx.compose.runtime.mutableStateOf(autoPlay) }
    LaunchedEffect(replay) { if (replay != firstReplay) playing = true }
    if (playing) androidx.compose.runtime.key(replay) {
        LiveEmoji(c, size, modifier, plays) { playing = false }
    } else StillEmoji(c, size, modifier)
}

/** [code] playing [plays] times, then [onDone]; its still picture until the animation is parsed. */
@Composable
private fun LiveEmoji(code: String, size: Dp, modifier: Modifier, plays: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    // Read text kept with its code: the cache must never get one animation under another name.
    val read by produceState<Pair<String, String?>?>(null, code) { value = code to withContext(Dispatchers.IO) { AnimatedEmoji.json(ctx, code) } }
    val json = read?.takeIf { it.first == code }?.second ?: run { StillEmoji(code, size, modifier); return }
    val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(json), cacheKey = "anim_emoji_$code")
    val anim = rememberLottieAnimatable()
    LaunchedEffect(composition) {
        val comp = composition ?: return@LaunchedEffect
        anim.animate(comp, iterations = plays)
        onDone()
    }
    if (composition == null) StillEmoji(code, size, modifier)
    else LottieAnimation(composition = composition, progress = { anim.progress }, modifier = modifier)
}

/** [code]'s rest frame as a picture (drawn off the main thread, kept for reuse). */
@Composable
private fun StillEmoji(code: String, size: Dp, modifier: Modifier) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val key = "$code@$px"
    // Kept with its key: a cell moving on to another emoji drops the old picture at once.
    val drawn by produceState(key to EmojiStills.peek(key), key) { value = key to (EmojiStills.peek(key) ?: EmojiStills.load(ctx, code, px, key)) }
    val img = drawn.takeIf { it.first == key }?.second ?: EmojiStills.peek(key)
    img?.let { androidx.compose.foundation.Image(it, null, modifier) } ?: Box(modifier)
}

/** Rest frames of the set's animations, rendered once per size. */
object EmojiStills {
    private val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(400)
    fun peek(key: String) = cache.get(key)

    suspend fun load(ctx: Context, code: String, px: Int, key: String): androidx.compose.ui.graphics.ImageBitmap? =
        cache.get(key) ?: withContext(Dispatchers.IO) {
            runCatching {
                val json = AnimatedEmoji.json(ctx, code) ?: return@runCatching null
                val comp = com.airbnb.lottie.LottieCompositionFactory.fromJsonStringSync(json, "anim_emoji_$code").value
                    ?: return@runCatching null
                val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
                com.airbnb.lottie.LottieDrawable().apply {
                    composition = comp
                    progress = restOf(code, comp)
                    setBounds(0, 0, px, px)
                    draw(android.graphics.Canvas(bmp))
                }
                bmp.asImageBitmap()
            }.getOrNull()
        }?.also { cache.put(key, it) }

    private val rests = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /**
     * The frame an emoji rests on: the fullest of a few across the animation. The first
     * frame of 🎉 is a bare cone and the last of 🍒 is empty; the fullest shows the emoji.
     */
    private fun restOf(code: String, comp: com.airbnb.lottie.LottieComposition): Float = rests.getOrPut(code) {
        val side = 48
        val probe = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(side * side)
        val d = com.airbnb.lottie.LottieDrawable().apply { composition = comp; setBounds(0, 0, side, side) }
        var best = 0f
        var bestCount = -1
        for (i in 0..REST_SAMPLES) {
            val p = i / REST_SAMPLES.toFloat()
            probe.eraseColor(0)
            d.progress = p
            d.draw(android.graphics.Canvas(probe))
            probe.getPixels(pixels, 0, side, 0, 0, side, side)
            val count = pixels.count { (it ushr 24) > 32 }
            if (count > bestCount) { bestCount = count; best = p }
        }
        probe.recycle()
        best
    }

    private const val REST_SAMPLES = 8
}

@Composable
private fun DefaultEmoji(emoji: String, size: Dp) {
    val sp = with(LocalDensity.current) { (size * 0.82f).toSp() }
    Text(emoji, fontSize = sp)
}

/** [text] when it is one emoji and nothing else: such a message is shown large. */
fun bigEmojiOf(text: String): String? = com.alphamessenger.ui.sticker.loneEmoji(text)?.let { text.trim() }

/** How animated emoji behave, chosen in Appearance. */
object EmojiPrefs {
    /** Play by themselves as they come into view; off: only when tapped (default — scrolling stays light). */
    var autoPlay by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var loaded = false

    const val STICKER_DEFAULT = 150
    val STICKER_RANGE = 100..220
    const val EMOJI_DEFAULT = 128
    val EMOJI_RANGE = 80..176

    /** Side of a sticker in the chat, dp. */
    var stickerDp by androidx.compose.runtime.mutableIntStateOf(STICKER_DEFAULT)
        private set

    /** Side of a single big emoji in the chat, dp. */
    var emojiDp by androidx.compose.runtime.mutableIntStateOf(EMOJI_DEFAULT)
        private set

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("anim_emoji", Context.MODE_PRIVATE)
        autoPlay = p.getBoolean("auto_play", false)
        stickerDp = p.getInt("sticker_dp", STICKER_DEFAULT).coerceIn(STICKER_RANGE)
        emojiDp = p.getInt("emoji_dp", EMOJI_DEFAULT).coerceIn(EMOJI_RANGE)
    }

    /** Live while dragging; [persist] once the finger lifts. */
    fun resizeStickers(v: Int) { stickerDp = v.coerceIn(STICKER_RANGE) }
    fun resizeEmoji(v: Int) { emojiDp = v.coerceIn(EMOJI_RANGE) }

    fun persistSizes(ctx: Context) {
        ctx.getSharedPreferences("anim_emoji", Context.MODE_PRIVATE).edit()
            .putInt("sticker_dp", stickerDp).putInt("emoji_dp", emojiDp).apply()
    }

    fun setAutoPlay(ctx: Context, v: Boolean) {
        autoPlay = v
        ctx.getSharedPreferences("anim_emoji", Context.MODE_PRIVATE).edit().putBoolean("auto_play", v).apply()
    }
}
