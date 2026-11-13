package com.alphamessenger.ui.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/*
 * Opt-in import of public Nostr emoji sets (NIP-30 emoji, NIP-51 sets).
 *
 * Off by default. A set is read anonymously (core), its pictures are fetched
 * ONCE on this phone (through Tor when it is on) and become an ordinary own
 * pack: encrypted, sent by our scheme — a recipient never reaches the outside
 * server. The whole feature lives in this file plus a few hooks marked
 * "Nostr emoji import".
 */

/** Links that open the import: `nostr:naddr1…`. */
const val NOSTR_SET_PREFIX = "nostr:naddr1"

/** A pack link of either kind (ours, or a Nostr emoji set). */
fun isPackLink(link: String) = link.startsWith(STICKER_LINK_PREFIX) || link.startsWith(NOSTR_SET_PREFIX, ignoreCase = true)

/** "Nostr emoji sets" switch (My packs). */
object NostrEmojiPrefs {
    private const val PREFS = "ui_prefs"
    var enabled by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("nostr_emoji", false)
    }

    fun set(ctx: Context, v: Boolean) {
        enabled = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("nostr_emoji", v).apply()
    }
}

data class NostrEmojiSet(val name: String, val author: String, val emoji: List<Pair<String, String>>)

object NostrEmojiImport {
    /** Imported pictures stand in for this emoji (previews, notifications). */
    private const val EMOJI = "✨"
    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val SIDE = 512

    sealed class Found {
        data class Sets(val sets: List<NostrEmojiSet>) : Found()
        object BadInput : Found()
        object Failed : Found()
    }

    suspend fun find(repo: AlphaCoreRepository, input: String): Found = withContext(Dispatchers.IO) {
        val json = try {
            repo.nostrEmojiSets(input)
        } catch (e: uniffi.alphamessenger.MessengerException.InvalidArgument) {
            return@withContext Found.BadInput
        } catch (e: Exception) {
            return@withContext Found.Failed
        }
        runCatching {
            val arr = JSONArray(json)
            Found.Sets((0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val em = o.getJSONArray("emoji")
                NostrEmojiSet(o.optString("name"), o.optString("author"),
                    (0 until em.length()).map { j -> em.getJSONArray(j).let { it.getString(0) to it.getString(1) } })
            })
        }.getOrDefault(Found.Failed)
    }

    private fun tmpDir(ctx: Context) = File(ctx.cacheDir, "nostr_emoji")

    fun clearTmp(ctx: Context) { tmpDir(ctx).deleteRecursively() }

    /**
     * Fetch the set's pictures once (through Tor when it is on), each fitted into
     * a 512×512 sticker; [onProgress] counts them. Files are temporary until [add].
     */
    suspend fun download(ctx: Context, repo: AlphaCoreRepository, set: NostrEmojiSet, onProgress: (Int) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            val tor = repo.isTorEnabled()
            val dir = tmpDir(ctx).apply { deleteRecursively(); mkdirs() }
            val gate = Semaphore(4)
            var done = 0
            set.emoji.mapIndexed { i, (_, url) ->
                async {
                    gate.withPermit {
                        val f = fetch(url, tor)?.let { normalize(it) }?.let { bmp ->
                            File(dir, "e_$i.webp").takeIf { out -> save(bmp, out) }
                        }
                        synchronized(this@NostrEmojiImport) { done++; onProgress(done) }
                        f
                    }
                }
            }.awaitAll().filterNotNull()
        }

    /** The fetched pictures become an own pack (editable, shareable by our link). */
    fun add(ctx: Context, set: NostrEmojiSet, files: List<File>): StickerPack {
        val p = StickerStore.newPack(ctx, set.name.ifBlank { "Nostr" })
        val dir = StickerStore.dir(ctx, p.id)
        val stickers = files.mapIndexed { i, f ->
            val dst = File(dir, "$STICKER_PREFIX${p.id}_$i.webp")
            f.copyTo(dst, overwrite = true)
            Sticker(dst.absolutePath, EMOJI)
        }
        val done = p.copy(stickers = stickers, source = StickerPack.SOURCE_NOSTR)
        StickerStore.save(ctx, done)
        clearTmp(ctx)
        return done
    }

    private fun fetch(url: String, tor: Boolean): ByteArray? = runCatching {
        val u = java.net.URL(url)
        val proxy = if (tor) com.alphamessenger.data.EmbeddedTor.proxy() else java.net.Proxy.NO_PROXY
        // The set's author picks these URLs: public https only, no redirect elsewhere.
        com.alphamessenger.util.PublicHttp.requirePublic(u, proxy)
        val conn = u.openConnection(proxy) as java.net.HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
        } finally { conn.disconnect() }
    }.getOrNull()

    /** Decode (first frame of an animation) and fit into a transparent 512×512. */
    private fun normalize(bytes: ByteArray): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= SIDE && o.outHeight / (s * 2) >= SIDE) s *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val k = minOf(SIDE.toFloat() / src.width, SIDE.toFloat() / src.height)
        val w = (src.width * k).toInt().coerceAtLeast(1)
        val h = (src.height * k).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val dst = android.graphics.Rect((SIDE - w) / 2, (SIDE - h) / 2, (SIDE - w) / 2 + w, (SIDE - h) / 2 + h)
        android.graphics.Canvas(out).drawBitmap(src, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        out
    }.getOrNull()

    private fun save(bmp: Bitmap, f: File): Boolean = runCatching {
        f.outputStream().use {
            @Suppress("DEPRECATION")
            val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(fmt, 90, it)
        }
        bmp.recycle()
        f.length() > 0
    }.getOrDefault(false)
}

/**
 * Find a Nostr emoji set (by its link or its author's npub), look at it, add it.
 * Container-agnostic: My packs shows it in its own window, a chat in a sheet.
 */
@Composable
fun NostrImportContent(colors: AlphaColorScheme, repo: AlphaCoreRepository, initial: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    NostrEmojiPrefs.load(ctx)
    var input by remember { mutableStateOf(initial) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var sets by remember { mutableStateOf<List<NostrEmojiSet>?>(null) }
    var chosen by remember { mutableStateOf<NostrEmojiSet?>(null) }
    var files by remember { mutableStateOf<List<File>?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { NostrEmojiImport.clearTmp(ctx) } }

    fun search() {
        if (input.isBlank() || searching) return
        searching = true; error = null; sets = null; chosen = null; files = null
        scope.launch {
            when (val r = NostrEmojiImport.find(repo, input)) {
                is NostrEmojiImport.Found.Sets -> {
                    sets = r.sets
                    if (r.sets.isEmpty()) error = R.string.nostr_emoji_none
                    else if (r.sets.size == 1) chosen = r.sets.first()
                }
                NostrEmojiImport.Found.BadInput -> error = R.string.nostr_emoji_bad
                NostrEmojiImport.Found.Failed -> error = R.string.nostr_emoji_failed
            }
            searching = false
        }
    }
    // A set chosen: its pictures are fetched once, for the preview and the pack.
    LaunchedEffect(chosen) {
        val set = chosen ?: return@LaunchedEffect
        files = null; progress = 0
        val got = NostrEmojiImport.download(ctx, repo, set) { n -> progress = n }
        files = got
        if (got.isEmpty()) error = R.string.nostr_emoji_failed
    }
    LaunchedEffect(Unit) { if (initial.isNotBlank() && NostrEmojiPrefs.enabled) search() }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Text(stringResource(R.string.nostr_emoji_title), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        // One branch per state, no early return: an early return out of a
        // composable's inline content breaks Compose's bookkeeping (crash).
        val set = chosen
        if (!NostrEmojiPrefs.enabled) {
            // Opened from a link while the import is off: say so, offer to switch it on.
            Text(stringResource(R.string.nostr_emoji_off), color = colors.textSecondary, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            NostrWarning(colors)
            PrimaryButton(stringResource(R.string.nostr_emoji_turn_on), colors, true) { NostrEmojiPrefs.set(ctx, true); search() }
        } else if (set == null) {
            OutlinedTextField(
                value = input, onValueChange = { input = it; error = null },
                placeholder = { Text(stringResource(R.string.nostr_emoji_input_hint), color = colors.textMuted) },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent, unfocusedBorderColor = colors.borderLight, cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(stringResource(R.string.nostr_emoji_help), color = colors.textMuted, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            error?.let { Text(stringResource(it), color = colors.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            // An author with several sets: pick one.
            sets?.takeIf { it.size > 1 }?.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { chosen = s }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.name, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.nostr_emoji_n, s.emoji.size), color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            NostrWarning(colors)
            if (searching) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            } else PrimaryButton(stringResource(R.string.nostr_emoji_find), colors, input.isNotBlank()) { search() }
        } else {
        // The chosen set: name, author, the pictures as they arrive.
        Text(set.name, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Text(set.author.take(14) + "… · " + stringResource(R.string.nostr_emoji_n, set.emoji.size),
            color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        val got = files
        if (got == null) {
            LinearProgressIndicator(
                progress = { if (set.emoji.isEmpty()) 0f else progress.toFloat() / set.emoji.size },
                color = colors.accent, trackColor = colors.bgTertiary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Text(stringResource(R.string.nostr_emoji_progress, progress, set.emoji.size), color = colors.textMuted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                items(got) { f ->
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(colors.bgTertiary), contentAlignment = Alignment.Center) {
                        StickerImage(f.absolutePath, Modifier.size(38.dp), 96)
                    }
                }
            }
            error?.let { Text(stringResource(it), color = colors.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        PrimaryButton(stringResource(R.string.nostr_emoji_add_set), colors, !got.isNullOrEmpty()) {
            NostrEmojiImport.add(ctx, set, got.orEmpty())
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.sticker_pack_added), android.widget.Toast.LENGTH_SHORT).show()
            onDone()
        }
        }
    }
}

@Composable
private fun NostrWarning(colors: AlphaColorScheme) {
    Text(
        stringResource(R.string.nostr_emoji_warning), color = colors.warning, fontSize = 12.5.sp, lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(colors.warning.copy(alpha = 0.10f)).padding(12.dp),
    )
}

@Composable
private fun PrimaryButton(text: String, colors: AlphaColorScheme, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(52.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) colors.accent else colors.bgTertiary)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else colors.textSecondary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
}

/** The import in a chat (a `nostr:naddr…` link was tapped). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NostrImportSheet(link: String, colors: AlphaColorScheme, repo: AlphaCoreRepository, onDismiss: () -> Unit) {
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            NostrImportContent(colors, repo, link, onDone = onDismiss)
        }
    }
}
