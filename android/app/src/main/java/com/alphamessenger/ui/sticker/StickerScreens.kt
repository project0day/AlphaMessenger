package com.alphamessenger.ui.sticker

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/**
 * Small decoded sticker pictures, kept so grids scroll without decoding again.
 * Keyed by file AND its change time: a sticker re-saved under the same name is
 * never shown with its old picture.
 */
object StickerThumbs {
    private val cache = android.util.LruCache<String, ImageBitmap>(160)
    fun keyOf(path: String): String = "$path#${java.io.File(path).lastModified()}"
    fun peek(key: String): ImageBitmap? = cache.get(key)
    suspend fun load(path: String, key: String, side: Int = 192): ImageBitmap? = cache.get(key) ?: withContext(Dispatchers.IO) {
        readThumb(path, side) ?: runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            com.alphamessenger.data.MediaVault.decodeFile(path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= side) s *= 2
            val bmp = com.alphamessenger.data.MediaVault.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
            // Worth keeping only when it is smaller than the sticker itself.
            if (bmp != null && s > 1) writeThumb(path, side, bmp)
            bmp?.asImageBitmap()
        }.getOrNull()
    }?.also { cache.put(key, it) }

    // Each sticker's picture at the size a grid draws it, kept sealed beside it: after
    // a restart the panel reads these instead of decoding (or rendering) every sticker.
    private fun thumbFile(path: String, side: Int): java.io.File {
        val f = java.io.File(path)
        return java.io.File(java.io.File(f.parentFile, ".thumbs"), "${f.name}_${f.lastModified()}_$side")
    }

    private fun readThumb(path: String, side: Int): ImageBitmap? {
        val t = thumbFile(path, side)
        if (!t.exists()) return null
        return com.alphamessenger.data.MediaVault.decodeFile(t.path)?.asImageBitmap()
    }

    private fun writeThumb(path: String, side: Int, bmp: android.graphics.Bitmap) {
        runCatching {
            val t = thumbFile(path, side)
            t.parentFile?.mkdirs()
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
                else android.graphics.Bitmap.CompressFormat.PNG
            bmp.compress(fmt, 100, out)
            val tmp = java.io.File(t.path + ".tmp")
            com.alphamessenger.data.MediaVault.writeSealed(tmp, out.toByteArray())
            tmp.renameTo(t)
        }
    }

    /** A vector sticker's first frame at [side] px; a broken one gives none (and nothing throws). */
    suspend fun vectorStill(path: String, key: String, side: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        readThumb(path, side)?.let { cache.put(key, it); return@withContext it }
        runCatching {
            val json = AnimatedSticker.vectorJson(com.alphamessenger.data.MediaVault.readBytes(java.io.File(path))) ?: return@runCatching null
            val comp = com.airbnb.lottie.LottieCompositionFactory.fromJsonStringSync(json, "stk_$path").value ?: return@runCatching null
            val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
            com.airbnb.lottie.LottieDrawable().apply {
                setAsyncUpdates(com.airbnb.lottie.AsyncUpdates.DISABLED)
                composition = comp
                progress = 0f
                setBounds(0, 0, side, side)
                draw(android.graphics.Canvas(bmp))
            }
            writeThumb(path, side, bmp)
            bmp.asImageBitmap()
        }.getOrNull()?.also { cache.put(key, it) }
    }

    /** What a sticker file is, from its first bytes. */
    enum class Kind { STILL, MOVING, VECTOR }

    private val kinds = android.util.LruCache<String, Kind>(512)
    fun kindPeek(key: String): Kind? = kinds.get(key)

    /** Animated WebP or GIF moves; JSON (plain or gzipped) is a vector animation. */
    suspend fun kindOf(path: String, key: String): Kind = kinds.get(key) ?: withContext(Dispatchers.IO) {
        runCatching {
            com.alphamessenger.data.MediaVault.open(java.io.File(path)).use { s ->
                val head = ByteArray(32)
                var n = 0
                while (n < head.size) { val r = s.read(head, n, head.size - n); if (r < 0) break; n += r }
                val h = head.copyOf(n)
                when {
                    AnimatedSticker.isGzip(h) || h.firstOrNull { it > 0x20 } == '{'.code.toByte() -> Kind.VECTOR
                    AnimatedSticker.mayMove(h) -> Kind.MOVING
                    else -> Kind.STILL
                }
            }
        }.getOrDefault(Kind.STILL)
    }.also { kinds.put(key, it) }
}

/**
 * [live]: one sticker on its own (a message, a preview) may move. In grids and rows
 * it stays a picture: dozens of animations at once stall scrolling.
 */
/** The list holding a sticker is moving (scrolled, flung, gliding to the bottom). */
val LocalListMoving = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> {
    androidx.compose.runtime.mutableStateOf(false)
}

@Composable
fun StickerImage(path: String, modifier: Modifier, side: Int = 192, contentDescription: String? = null, replay: Int = 0, live: Boolean = false) {
    // Its picture while the list moves, its player once it rests: a player built
    // mid-scroll costs a frame. One that is already alive stays alive.
    val moving = LocalListMoving.current
    var rested by remember(path) { mutableStateOf(!moving.value) }
    if (live && !rested) androidx.compose.runtime.LaunchedEffect(path) {
        androidx.compose.runtime.snapshotFlow { moving.value }.first { !it }
        rested = true
    }
    @Suppress("NAME_SHADOWING") val live = live && rested
    // The picture belongs to THIS file: a grid cell reused for another sticker
    // (switching packs) must not keep showing the previous one's picture.
    val key = remember(path) { StickerThumbs.keyOf(path) }
    // Kept with its file too, so a reused cell never draws the previous sticker's kind.
    val kindOf by androidx.compose.runtime.produceState(key to StickerThumbs.kindPeek(key), key) { value = key to StickerThumbs.kindOf(path, key) }
    val kind = kindOf.takeIf { it.first == key }?.second ?: StickerThumbs.kindPeek(key)
    if (kind == StickerThumbs.Kind.VECTOR) {
        // In a grid it is a picture drawn once; a live animation only while it plays.
        com.alphamessenger.ui.emoji.EmojiPrefs.load(LocalContext.current)
        if (live && (replay > 0 || com.alphamessenger.ui.emoji.EmojiPrefs.autoPlay)) VectorSticker(path, modifier, replay)
        else VectorStill(path, key, side, modifier, contentDescription)
        return
    }
    var img by remember(key) { mutableStateOf(StickerThumbs.peek(key)) }
    androidx.compose.runtime.LaunchedEffect(key) { if (img == null) img = StickerThumbs.load(path, key, side) }
    if (live && kind == StickerThumbs.Kind.MOVING && android.os.Build.VERSION.SDK_INT >= 28) {
        MovingSticker(path, modifier, side, img, contentDescription)
        return
    }
    img?.let { Image(it, contentDescription, modifier = modifier) } ?: Box(modifier)
}

/** A saved animated emoji: plays once as it shows (if chosen in Appearance), else rests on its first frame. */
@Composable
private fun VectorSticker(path: String, modifier: Modifier, replay: Int) {
    // What was read is kept with the file it came from: a cell moving on to another
    // sticker must never hand the old animation to the cache under the new name.
    val loaded by androidx.compose.runtime.produceState<Pair<String, String?>?>(null, path) {
        value = path to withContext(Dispatchers.IO) {
            runCatching { AnimatedSticker.vectorJson(com.alphamessenger.data.MediaVault.readBytes(java.io.File(path))) }.getOrNull()
        }
    }
    val text = loaded?.takeIf { it.first == path }?.second ?: run { Box(modifier); return }
    val parsed by androidx.compose.runtime.produceState<Pair<String, com.airbnb.lottie.LottieComposition?>?>(null, path, text) {
        value = path to withContext(Dispatchers.IO) {
            runCatching { com.airbnb.lottie.LottieCompositionFactory.fromJsonStringSync(text, "stk_$path").value }.getOrNull()
        }
    }
    val composition = parsed?.takeIf { it.first == path }?.second ?: run { Box(modifier); return }
    var broken by remember(path) { mutableStateOf(false) }
    if (broken) { Box(modifier); return }
    com.alphamessenger.ui.emoji.EmojiPrefs.load(LocalContext.current)
    // Plays once as it shows (if chosen) and on each [replay]; rests on its first frame.
    val wanted = replay + if (com.alphamessenger.ui.emoji.EmojiPrefs.autoPlay) 1 else 0
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            SafeLottieView(c).apply {
                addAnimatorListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { progress = 0f }
                })
            }
        },
        modifier = modifier,
        update = { v ->
            v.onBroken = { broken = true }
            if (v.composition !== composition) {
                v.setComposition(composition)
                v.progress = 0f
                v.tag = 0
            }
            if (wanted > 0 && v.tag != wanted) {
                v.tag = wanted
                v.playAnimation()
            }
        },
    )
}

/** A sticker that moves: its first frame until the animation is decoded, then the animation, looping. */
@androidx.annotation.RequiresApi(28)
@Composable
private fun MovingSticker(path: String, modifier: Modifier, side: Int, still: ImageBitmap?, contentDescription: String?) {
    // Kept with its file: a reused cell never plays the previous sticker.
    val decoded by androidx.compose.runtime.produceState<Pair<String, android.graphics.drawable.AnimatedImageDrawable?>?>(null, path) {
        value = path to withContext(Dispatchers.IO) {
            runCatching {
                val bytes = com.alphamessenger.data.MediaVault.readBytes(java.io.File(path))
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, info, _ ->
                    val big = maxOf(info.size.width, info.size.height)
                    if (big > side) {
                        val k = side.toFloat() / big
                        d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                    }
                } as? android.graphics.drawable.AnimatedImageDrawable
            }.getOrNull()
        }
    }
    val d = decoded?.takeIf { it.first == path }?.second
    if (d == null) {
        still?.let { Image(it, contentDescription, modifier = modifier) } ?: Box(modifier)
        return
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            android.widget.ImageView(c).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                this.contentDescription = contentDescription
            }
        },
        modifier = modifier,
        update = { iv ->
            if (iv.drawable !== d) {
                iv.setImageDrawable(d)
                d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                d.start()
            }
        },
        onRelease = { iv ->
            (iv.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
            iv.setImageDrawable(null)
        },
    )
}

private fun shareLink(ctx: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.sticker_share_pack)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val RECENT = "__recent"

/**
 * The Stickers tab: recent and every pack along the top, the chosen one's
 * stickers below; a tap sends. "+" opens my packs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPanel(
    colors: AlphaColorScheme,
    repo: AlphaCoreRepository,
    onSend: (packId: String, sticker: Sticker) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val version by StickerStore.version.collectAsState()
    val packs by produceState(emptyList<StickerPack>(), version) { value = withContext(Dispatchers.IO) { StickerStore.packs(ctx) } }
    val recent by produceState(emptyList<Pair<String, Sticker>>(), version) { value = withContext(Dispatchers.IO) { StickerStore.recent(ctx) } }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { StickerStore.refreshAdded(ctx, repo) }
    val current = chosen ?: if (recent.isNotEmpty()) RECENT else packs.firstOrNull()?.id

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recent.isNotEmpty()) PackTab(current == RECENT, colors, { chosen = RECENT }) {
                Icon(AlphaIcons.Outlined.Schedule, stringResource(R.string.sticker_recent), tint = colors.textSecondary, modifier = Modifier.size(20.dp))
            }
            packs.forEach { p ->
                PackTab(current == p.id, colors, { chosen = p.id }) {
                    // An empty pack shows the start of its name instead of a blank.
                    p.cover?.let { StickerImage(it.file, Modifier.size(30.dp), 96, p.name) }
                        ?: Text(p.name.take(2), color = colors.textSecondary, fontSize = 16.sp, maxLines = 1)
                }
            }
            PackTab(false, colors, { managing = true }) {
                Icon(AlphaIcons.Outlined.Add, stringResource(R.string.sticker_my_packs), tint = colors.accent, modifier = Modifier.size(22.dp))
            }
        }
        val shown: List<Pair<String, Sticker>> = when (current) {
            RECENT -> recent
            null -> emptyList()
            else -> packs.firstOrNull { it.id == current }?.let { p -> p.stickers.map { p.id to it } }.orEmpty()
        }
        val title = when (current) {
            RECENT -> stringResource(R.string.sticker_recent)
            else -> packs.firstOrNull { it.id == current }?.name.orEmpty()
        }
        Text(title.uppercase(), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.sticker_pack_empty), color = colors.textMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        } else LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            items(shown) { (pid, s) ->
                Box(
                    Modifier.height(84.dp).clip(RoundedCornerShape(14.dp)).clickable { onSend(pid, s) },
                    contentAlignment = Alignment.Center,
                ) { StickerImage(s.file, Modifier.size(72.dp), 192, s.emoji) }
            }
        }
    }
    if (managing) MyPacksScreen(colors, repo, onDismiss = { managing = false })
}

@Composable
private fun PackTab(on: Boolean, colors: AlphaColorScheme, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) colors.accent.copy(alpha = 0.18f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** My packs: the built-in one, mine (open to edit) and added ones (removable). */
@Composable
fun MyPacksScreen(colors: AlphaColorScheme, repo: AlphaCoreRepository, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version by StickerStore.version.collectAsState()
    val packs by produceState(emptyList<StickerPack>(), version) { value = withContext(Dispatchers.IO) { StickerStore.packs(ctx) } }
    var editing by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<StickerPack?>(null) }
    var importing by remember { mutableStateOf(false) }
    val importScope = androidx.compose.runtime.rememberCoroutineScope()
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) importScope.launch {
            importing = true
            if (uris.size == 1) {
                val r = StickerStore.importArchive(ctx, uris[0])
                importing = false
                android.widget.Toast.makeText(
                    ctx,
                    when { r.alreadyHere -> ctx.getString(R.string.sticker_import_already); r.count > 0 -> ctx.resources.getQuantityString(R.plurals.sticker_imported, r.count, r.count); else -> ctx.getString(R.string.sticker_import_failed) },
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            var ok = 0
            for (uri in uris) if (StickerStore.importArchive(ctx, uri).packId != null) ok++
            importing = false
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.sticker_imported_packs, ok, uris.size), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Nostr emoji-set import is put aside for now (NostrEmojiImport.kt stays for later).
    FullDialog(onDismiss) {
        TopRow(stringResource(R.string.sticker_my_packs), onDismiss, colors)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.sticker_new_pack), color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                // A whole pack at once from an archive (pack.json + its pictures).
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !importing) { importPicker.launch("application/zip") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        if (importing) androidx.compose.material3.CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        else Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.sticker_import_pack), color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            items(packs, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = p.own) { editing = p.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.bgTertiary), contentAlignment = Alignment.Center) {
                        p.cover?.let { StickerImage(it.file, Modifier.size(40.dp), 96) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            androidx.compose.ui.res.pluralStringResource(R.plurals.sticker_count, p.stickers.size, p.stickers.size) + " · " +
                                stringResource(when { p.builtin -> R.string.sticker_kind_builtin; p.own -> R.string.sticker_kind_own; else -> R.string.sticker_kind_added }) +
                                (if (p.source == StickerPack.SOURCE_NOSTR) " · Nostr" else ""),
                            color = colors.textSecondary, fontSize = 13.sp,
                        )
                    }
                    if (!p.builtin) IconButton(onClick = { removing = p }) {
                        Icon(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.common_delete), tint = colors.textMuted)
                    }
                }
            }
        }
    }
    if (naming) NameDialog(colors, "", onDismiss = { naming = false }) { name ->
        naming = false
        editing = StickerStore.newPack(ctx, name).id
    }
    editing?.let { id -> PackEditor(id, colors, repo, onDismiss = { editing = null }) }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.sticker_remove_pack, p.name), color = colors.textPrimary) },
            confirmButton = { TextButton(onClick = { StickerStore.delete(ctx, p.id); removing = null }) { Text(stringResource(R.string.common_delete), color = colors.danger) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
        )
    }
}

/** One of my packs: its stickers, "+ From photo", rename, share. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PackEditor(packId: String, colors: AlphaColorScheme, repo: AlphaCoreRepository, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by StickerStore.version.collectAsState()
    val pack by produceState<StickerPack?>(null, version) { value = withContext(Dispatchers.IO) { StickerStore.pack(ctx, packId) } }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<Sticker?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var pickingEmoji by remember { mutableStateOf(false) }
    // A picked picture that moves goes in as it is (after its emoji); a still one to the editor.
    var animated by remember { mutableStateOf<ByteArray?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            when (val r = withContext(Dispatchers.IO) { readAnimated(ctx, uri) }) {
                null -> photo = uri
                is AnimatedPick.Ok -> animated = r.bytes
                AnimatedPick.TooBig -> android.widget.Toast.makeText(ctx, ctx.getString(R.string.sticker_animated_too_big), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val p = pack
    // Whoever added the pack gets the changes: on leaving the editor its new
    // version goes out (only if it has been shared or sent at all).
    androidx.compose.runtime.DisposableEffect(packId) {
        onDispose {
            val appCtx = ctx.applicationContext
            if (StickerStore.pack(appCtx, packId)?.pub != null)
                repo.runDetached { StickerStore.publishLatest(appCtx, repo, packId) }
        }
    }

    FullDialog(onDismiss, overlay = {
        if (pickingEmoji) EmojiStickerSheet(colors, onDismiss = { pickingEmoji = false }) { e ->
            pickingEmoji = false
            scope.launch {
                val bmp = withContext(Dispatchers.Default) { emojiSticker(e) }
                StickerStore.addSticker(ctx, packId, bmp, e)
                bmp.recycle()
            }
        }
        animated?.let { bytes ->
            EmojiStickerSheet(colors, onDismiss = { animated = null }, title = stringResource(R.string.sticker_animated_emoji_title)) { e ->
                animated = null
                scope.launch {
                    if (!StickerStore.addAnimated(ctx, packId, bytes, e))
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.sticker_add_failed), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        acting?.let { s -> StickerActions(s, colors,
            onEmoji = { e -> StickerStore.setEmoji(ctx, packId, s.file, e); acting = null },
            onDelete = { StickerStore.removeSticker(ctx, packId, s.file); acting = null },
            onDismiss = { acting = null }) }
    }) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = colors.textPrimary) }
            Text(p?.name.orEmpty(), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { renaming = true }) { Icon(AlphaIcons.Outlined.Edit, stringResource(R.string.sticker_rename), tint = colors.textSecondary) }
        }
        Text(stringResource(R.string.sticker_editor_hint), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "add") {
                Column(
                    Modifier.height(82.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, colors.accent, RoundedCornerShape(16.dp))
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent)
                    Text(stringResource(R.string.sticker_from_photo), color = colors.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
            item(key = "emoji") {
                Column(
                    Modifier.height(82.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, colors.accent, RoundedCornerShape(16.dp))
                        .clickable { pickingEmoji = true },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text("😀", fontSize = 22.sp)
                    Text(stringResource(R.string.sticker_from_emoji), color = colors.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
            items(p?.stickers.orEmpty()) { s ->
                Box(
                    Modifier.height(82.dp).clip(RoundedCornerShape(16.dp)).background(colors.bgSecondary)
                        .combinedClickable(onClick = { acting = s }, onLongClick = { acting = s }),
                    contentAlignment = Alignment.Center,
                ) {
                    StickerImage(s.file, Modifier.size(64.dp), 192, s.emoji)
                    Text(s.emoji, fontSize = 15.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(AlphaIcons.Outlined.Lock, null, tint = colors.textMuted, modifier = Modifier.size(16.dp).padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sticker_privacy_note), color = colors.textMuted, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(colors.bgTertiary)
                    .clickable(enabled = !sharing && (p?.stickers?.isNotEmpty() == true)) {
                        sharing = true
                        scope.launch {
                            val link = StickerStore.linkFor(ctx, repo, packId)
                            sharing = false
                            if (link != null) shareLink(ctx, link)
                            else android.widget.Toast.makeText(ctx, ctx.getString(R.string.sticker_share_failed), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (sharing) CircularProgressIndicator(color = colors.textPrimary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AlphaIcons.Outlined.Share, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.common_share), color = colors.textPrimary, fontWeight = FontWeight.Bold)
                }
            }
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(colors.accent).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.common_done), color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }

    photo?.let { uri -> StickerMaker(uri, packId, colors, onDismiss = { photo = null }, onAdded = { photo = null }) }
    if (renaming && p != null) NameDialog(colors, p.name, onDismiss = { renaming = false }) { StickerStore.rename(ctx, packId, it); renaming = false }
}

@Composable
private fun StickerActions(s: Sticker, colors: AlphaColorScheme, onEmoji: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    InWindowSheet(colors, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally)) { StickerImage(s.file, Modifier.size(120.dp), 256, live = true) }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.sticker_emoji), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STICKER_EMOJI.forEach { e ->
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (e == s.emoji) colors.accent else colors.bgTertiary)
                        .clickable { onEmoji(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onDelete).padding(vertical = 14.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(AlphaIcons.Outlined.DeleteOutline, null, tint = colors.danger)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.sticker_delete), color = colors.danger, fontSize = 15.sp)
            }
        }
    }
}

/**
 * A pack someone shared (a link or a sticker that carries one): its stickers,
 * fetched once, and "Add pack". Built-in packs open from here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackSheet(
    link: String?, builtinId: String?, colors: AlphaColorScheme, repo: AlphaCoreRepository, onDismiss: () -> Unit,
    /** Opened from a chat: a tap on a sticker sends it there (packId, sticker, the pack's link). */
    onSend: ((String, Sticker, String?) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val version by StickerStore.version.collectAsState()
    var failed by remember { mutableStateOf(false) }
    val preview by produceState<StickerStore.Preview?>(null, link, builtinId) {
        if (builtinId != null) {
            value = withContext(Dispatchers.IO) {
                StickerStore.packs(ctx).firstOrNull { it.id == builtinId }?.let { StickerStore.Preview(it.id, it.name, it.stickers) }
            }
            if (value == null) failed = true
            return@produceState
        }
        val l = link ?: run { failed = true; return@produceState }
        // What the phone has (the added pack, or this session's fetch) at once;
        // then the latest version from the network replaces it if it differs.
        value = withContext(Dispatchers.IO) { StickerStore.previewNow(ctx, l) ?: StickerStore.previewStored(ctx, repo, l) }
        val fresh = withContext(Dispatchers.IO) { StickerStore.preview(ctx, repo, l) }
        if (fresh != null && (value == null || fresh.version != value?.version)) value = fresh
        if (value == null) failed = true
    }
    val existing by produceState<StickerPack?>(null, preview?.id, version) {
        value = preview?.id?.let { id -> withContext(Dispatchers.IO) { StickerStore.pack(ctx, id) } }
    }
    androidx.compose.runtime.LaunchedEffect(preview, existing?.version) {
        val pv = preview ?: return@LaunchedEffect
        val ex = existing ?: return@LaunchedEffect
        if (!ex.own && !ex.builtin && link != null && pv.version != null && pv.version != ex.version)
            withContext(Dispatchers.IO) { StickerStore.add(ctx, pv, link) }
    }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preview?.name.orEmpty(), color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    preview?.let {
                        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.sticker_count, it.stickers.size, it.stickers.size),
                            color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
                if (link != null) IconButton(onClick = { shareLink(ctx, link) }) {
                    Icon(AlphaIcons.Outlined.Share, stringResource(R.string.sticker_share_pack), tint = colors.textSecondary)
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 440.dp), contentAlignment = Alignment.Center) {
                val pv = preview
                when {
                    pv != null -> LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        items(pv.stickers) { s ->
                            Box(
                                Modifier.height(86.dp).clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = onSend != null) { onSend?.invoke(pv.id, s, link); onDismiss() },
                                contentAlignment = Alignment.Center,
                            ) { StickerImage(s.file, Modifier.size(70.dp), 192, s.emoji) }
                        }
                    }
                    failed -> Text(stringResource(R.string.sticker_pack_unavailable), color = colors.textMuted, fontSize = 14.sp)
                    else -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Icon(AlphaIcons.Outlined.Lock, null, tint = colors.textMuted, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sticker_pack_note), color = colors.textMuted, fontSize = 12.sp)
            }
            val pv = preview
            val ex = existing
            val label = when {
                ex?.builtin == true || (ex != null && ex.own) -> R.string.sticker_pack_yours
                ex != null -> R.string.sticker_pack_added
                else -> R.string.sticker_add_pack
            }
            val canAdd = pv != null && ex == null && link != null
            Box(
                Modifier.fillMaxWidth().padding(16.dp).height(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (canAdd) colors.accent else colors.bgTertiary)
                    .clickable(enabled = canAdd) {
                        StickerStore.add(ctx, pv!!, link!!)
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.sticker_pack_added), android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(label), color = if (canAdd) Color.White else colors.textSecondary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
        }
    }
}

@Composable
private fun FullDialog(
    onDismiss: () -> Unit,
    /** Drawn over the content inside the same window (a bottom sheet would open under it). */
    overlay: @Composable () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val bg = com.alphamessenger.ui.theme.AlphaTheme.colorScheme.bgPrimary
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = bg.luminance() >= 0.5f)
        Box(Modifier.fillMaxSize().background(bg)) {
            Column(Modifier.fillMaxSize().systemBarsPadding(), content = content)
            overlay()
        }
    }
}

/**
 * A panel rising from the bottom inside the current window, over a dimmed
 * backdrop; a tap on the backdrop (or Back) closes it.
 */
@Composable
private fun InWindowSheet(colors: AlphaColorScheme, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(colors.bgSecondary)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().padding(top = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.textMuted.copy(alpha = 0.4f)))
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun TopRow(title: String, onBack: () -> Unit, colors: AlphaColorScheme) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = colors.textPrimary) }
        Text(title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NameDialog(colors: AlphaColorScheme, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(R.string.sticker_pack_name), color = colors.textPrimary) },
        text = {
            OutlinedTextField(value = v, onValueChange = { v = it.take(48) }, singleLine = true,
                placeholder = { Text(stringResource(R.string.sticker_pack_name_hint), color = colors.textMuted) })
        },
        confirmButton = { TextButton(enabled = v.isNotBlank(), onClick = { onSave(v.trim()) }) { Text(stringResource(R.string.common_save), color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
    )
}

/** A request to show a pack (from a sticker or a link), answered by [StickerPackHost]. */
object StickerUi {
    var request by mutableStateOf<Pair<String?, String?>?>(null)
    fun open(link: String?, builtin: String?) { request = link to builtin }

    /** Set by [StickerPackHost]: what background pack updates fetch with. */
    internal var repo: AlphaCoreRepository? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val preloading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Packs already here, by link: what a pack card shows (its name, first stickers). */
    val preloaded = androidx.compose.runtime.mutableStateMapOf<String, StickerStore.Preview>()

    /**
     * A pack link shown in a chat: its stickers are fetched in the background, so a
     * tap opens the pack at once. Once per link per session.
     */
    fun preload(ctx: Context, link: String, with: AlphaCoreRepository? = null) {
        val r = with ?: repo ?: return
        if (!preloading.add(link)) return
        val app = ctx.applicationContext
        scope.launch {
            val pv = runCatching { StickerStore.previewNow(app, link) ?: StickerStore.previewStored(app, r, link) ?: StickerStore.preview(app, r, link) }.getOrNull()
            if (pv != null) kotlinx.coroutines.withContext(Dispatchers.Main) { preloaded[link] = pv }
            else preloading.remove(link)
        }
    }

    /** A sticker shown in a chat: if its added pack lacks it, update the pack in the background. */
    fun notice(ctx: Context, link: String?, ref: String?) {
        val r = repo ?: return
        if (link.isNullOrBlank() || ref.isNullOrBlank()) return
        val app = ctx.applicationContext
        scope.launch { runCatching { StickerStore.noticeSticker(app, r, link, ref) } }
    }
}

/** Shows the pack sheet whenever something asks for it. */
@Composable
fun StickerPackHost(colors: AlphaColorScheme, repo: AlphaCoreRepository, onSend: ((String, Sticker, String?) -> Unit)? = null) {
    StickerUi.repo = repo
    StickerUi.request?.let { (link, builtin) ->
        PackSheet(link, builtin, colors, repo, onDismiss = { StickerUi.request = null }, onSend = onSend)
    }
}

/** The first sticker pack link in [text], or null. */
fun extractPackLink(text: String): String? =
    Regex(Regex.escape(STICKER_LINK_PREFIX) + """[A-Za-z0-9_\-]+""").find(text)?.value

/** A pack link in a message: a small card instead of the long link; a tap opens the pack. */
@Composable
fun StickerPackCard(link: String, colors: AlphaColorScheme, onAccentBubble: Boolean) {
    val bg = if (onAccentBubble) Color.White.copy(alpha = 0.20f) else colors.accent.copy(alpha = 0.12f)
    val title = if (onAccentBubble) Color.White else colors.accent
    val sub = if (onAccentBubble) Color.White.copy(alpha = 0.85f) else colors.textSecondary
    val ctx = LocalContext.current
    // Fetched as soon as the card is seen: the tap then opens the pack at once.
    androidx.compose.runtime.LaunchedEffect(link) { StickerUi.preload(ctx, link) }
    val pv = StickerUi.preloaded[link]
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable { StickerUi.open(link, null) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(com.alphamessenger.ui.components.AlphaIcons.Expressions, null, tint = title, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(pv?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sticker_pack_card), color = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    pv?.let { androidx.compose.ui.res.pluralStringResource(R.plurals.sticker_count, it.stickers.size, it.stickers.size) }
                        ?: stringResource(R.string.sticker_pack_card_open),
                    color = sub, fontSize = 13.sp,
                )
            }
        }
        // Its first stickers, once the pack is here.
        if (pv != null && pv.stickers.isNotEmpty()) Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            pv.stickers.take(4).forEach { s -> StickerImage(s.file, Modifier.size(44.dp), side = 96) }
        }
    }
}

/** Pick an emoji to turn into a sticker. */
@Composable
private fun EmojiStickerSheet(
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.sticker_from_emoji_title),
    onPick: (String) -> Unit,
) {
    InWindowSheet(colors, onDismiss) {
        Column(Modifier.fillMaxWidth().height(440.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            com.alphamessenger.ui.chat.v2.EmojiPanelV2(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = colors,
                onPick = onPick,
                onBackspace = {},
            )
        }
    }
}

/** A vector sticker at rest: its first frame, drawn off the main thread once and kept. */
@Composable
private fun VectorStill(path: String, key: String, side: Int, modifier: Modifier, contentDescription: String?) {
    val stillKey = "$key@v$side"
    val drawn by androidx.compose.runtime.produceState(stillKey to StickerThumbs.peek(stillKey), stillKey) {
        value = stillKey to (StickerThumbs.peek(stillKey) ?: StickerThumbs.vectorStill(path, stillKey, side))
    }
    val img = drawn.takeIf { it.first == stillKey }?.second ?: StickerThumbs.peek(stillKey)
    img?.let { Image(it, contentDescription, modifier = modifier) } ?: Box(modifier)
}
