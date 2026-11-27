package com.alphamessenger.ui.feed

import com.alphamessenger.ui.icons.AlphaIcons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.sticker.StickerImage
import com.alphamessenger.ui.sticker.StickerStore
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.launch
import java.io.File

private enum class FeedTab { NEWS, PACKS, THEMES, GIFS, EMOJI }

/** The brand's wordmark gradient (as on the chat list header). */
private val AlphaBrand = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFC026D3), Color(0xFFEC4899)))

/** A row on top of Channels: the Alpha feed, its newest post, a dot when unread. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlphaFeedRow(colors: AlphaColorScheme, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    FeedStore.load(ctx)
    var confirmHide by remember { mutableStateOf(false) }
    val latest = FeedStore.posts.firstOrNull()
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { confirmHide = true })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(AlphaBrand), contentAlignment = Alignment.Center) {
            Text("α", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alpha", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Icon(AlphaIcons.Filled.Verified, stringResource(R.string.feed_signed), tint = colors.accent, modifier = Modifier.size(16.dp))
            }
            Text(latest?.title?.ifBlank { latest.body } ?: stringResource(R.string.feed_row_subtitle),
                color = colors.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (FeedStore.unread) Box(Modifier.size(10.dp).clip(CircleShape).background(colors.accent))
    }
    if (confirmHide) AlertDialog(
        onDismissRequest = { confirmHide = false },
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(R.string.feed_hide_title), color = colors.textPrimary) },
        text = { Text(stringResource(R.string.feed_hide_text), color = colors.textSecondary) },
        confirmButton = { TextButton(onClick = { FeedStore.setHidden(ctx, true); confirmHide = false }) { Text(stringResource(R.string.feed_hide), color = colors.accent) } },
        dismissButton = { TextButton(onClick = { confirmHide = false }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
    )
}

/**
 * The Alpha feed: news, new sticker packs and GIFs, signed by Alpha's key and
 * read anonymously. On the owner's phone (holding the key) posts are written
 * and removed here too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(colors: AlphaColorScheme, repo: AlphaCoreRepository, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    FeedStore.load(ctx)
    StickerCatalog.load(ctx)
    ThemeCatalog.load(ctx)
    var removingTheme by remember { mutableStateOf<CatalogTheme?>(null) }
    var packQuery by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(FeedTab.NEWS) }
    var loading by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<FeedPost?>(null) }
    var openSet by remember { mutableStateOf(false) }
    fun reload() { scope.launch { loading = true; FeedStore.refresh(ctx, repo); loading = false; FeedStore.markSeen(ctx) } }
    // The posts on this phone show at once; the feed is asked quietly for new ones.
    LaunchedEffect(Unit) {
        FeedStore.loadOwner(ctx)
        // Nothing on the phone yet: say it is loading, not that the feed is empty.
        loading = FeedStore.posts.isEmpty()
        FeedStore.refreshIfStale(ctx, repo, maxAgeMs = 60_000L)
        // The archive list waits until the channel is open and settled: it can be thousands of files.
        if (FeedStore.isOwner) scope.launch { kotlinx.coroutines.delay(30_000); ArchiveKeeper.sync(ctx.applicationContext, repo) }
        loading = false
        FeedStore.markSeen(ctx)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = colors.bgPrimary.luminance() >= 0.5f)
        Box(Modifier.fillMaxSize().background(colors.bgPrimary)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = colors.textPrimary) }
                    Text("Alpha", style = androidx.compose.ui.text.TextStyle(
                        brush = AlphaBrand, fontFamily = com.alphamessenger.ui.theme.BricolageGrotesque,
                        fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.5).sp,
                    ))
                    Spacer(Modifier.width(6.dp))
                    Icon(AlphaIcons.Filled.Verified, stringResource(R.string.feed_signed), tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(end = 4.dp))
                    else IconButton(onClick = { reload() }) { Icon(AlphaIcons.Outlined.Refresh, stringResource(R.string.feed_refresh), tint = colors.textSecondary) }
                    if (FeedStore.isOwner) IconButton(onClick = { keyDialog = true }) { Icon(AlphaIcons.Outlined.Key, stringResource(R.string.feed_key_title), tint = colors.textMuted) }
                }
                Text(stringResource(R.string.feed_header_note), color = colors.textSecondary, fontSize = 12.5.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(FeedTab.NEWS to R.string.feed_tab_news, FeedTab.PACKS to R.string.feed_tab_stickers, FeedTab.THEMES to R.string.feed_tab_themes, FeedTab.EMOJI to R.string.feed_tab_emoji, FeedTab.GIFS to R.string.feed_tab_gif).forEach { (t, label) ->
                        val on = t == tab
                        Box(
                            Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) colors.accent else colors.bgTertiary)
                                .clickable { tab = t }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(label), color = if (on) Color.White else colors.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                val posts = FeedStore.posts
                val catalog = StickerCatalog.packs
                // The catalog, then pack posts from before it that it does not hold yet.
                val allPacks = remember(catalog, posts) {
                    val known = catalog.mapTo(HashSet()) { it.link }
                    catalog.map { FeedPost(CATALOG_ID + it.link, it.at, FeedKind.PACK, it.name, "", pack = it.link) } +
                        posts.filter { it.kind == FeedKind.PACK && it.pack != null && it.pack !in known }
                }
                when (tab) {
                    FeedTab.NEWS -> LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val packs = allPacks
                        if (packs.isNotEmpty()) item(key = "packs") {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.feed_new_packs), color = colors.textPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    Text(stringResource(R.string.feed_all), color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                        modifier = Modifier.clickable { tab = FeedTab.PACKS })
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(packs.take(10), key = { it.id }) { p -> PackCard(p, colors, repo, Modifier.width(132.dp)) }
                                }
                            }
                        }
                        val news = posts.filter { it.kind == FeedKind.NEWS || it.kind == FeedKind.UPDATE || it.kind == FeedKind.ALERT }
                        if (news.isEmpty() && packs.isEmpty()) item { Empty(colors, loading) }
                        items(news, key = { it.id }) { p ->
                            NewsCard(p, colors, repo, Modifier.combinedClickable(onClick = {}, onLongClick = { if (FeedStore.isOwner) removing = p }))
                        }
                    }
                    FeedTab.PACKS -> {
                        val q = packQuery.trim()
                        val packs = if (q.isEmpty()) allPacks else allPacks.filter { it.title.contains(q, ignoreCase = true) }
                        if (allPacks.size > 12) OutlinedTextField(value = packQuery, onValueChange = { packQuery = it.take(60) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            placeholder = { Text(stringResource(R.string.feed_search_packs), color = colors.textMuted) })
                        if (packs.isEmpty()) Box(Modifier.weight(1f)) { Empty(colors, loading) }
                        else LazyVerticalGrid(
                            columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(packs, key = { it.id }) { p ->
                                // Catalog packs are not posts: nothing to remove by holding them.
                                PackCard(p, colors, repo, Modifier.combinedClickable(onClick = {},
                                    onLongClick = { if (FeedStore.isOwner && !p.id.startsWith(CATALOG_ID)) removing = p }))
                            }
                        }
                    }
                    FeedTab.THEMES -> {
                        val list = ThemeCatalog.themes
                        if (list.isEmpty()) Box(Modifier.weight(1f)) { Empty(colors, loading) }
                        else LazyVerticalGrid(
                            columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(list, key = { it.link }) { t ->
                                CatalogThemeCard(t, colors, Modifier.combinedClickable(
                                    onClick = { com.alphamessenger.ui.theme.ThemeUi.open(t.link) },
                                    onLongClick = { if (FeedStore.isOwner) removingTheme = t },
                                ))
                            }
                        }
                    }
                    FeedTab.EMOJI -> {
                        val sets = posts.filter { it.kind == FeedKind.EMOJI && it.set != null }.sortedByDescending { it.at }
                        if (sets.isEmpty()) Box(Modifier.weight(1f)) { Empty(colors, loading) }
                        else LazyColumn(
                            Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(sets, key = { it.id }) { p ->
                                com.alphamessenger.ui.emoji.EmojiSetCard(p, colors, repo,
                                    Modifier.combinedClickable(onClick = {}, onLongClick = { if (FeedStore.isOwner) removing = p }),
                                    onOpen = { openSet = true })
                            }
                        }
                    }
                    FeedTab.GIFS -> {
                        val gifs = posts.filter { it.kind == FeedKind.GIF && it.gif != null }
                        if (gifs.isEmpty()) Box(Modifier.weight(1f)) { Empty(colors, loading) }
                        else LazyVerticalGrid(
                            columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(gifs, key = { it.id }) { p ->
                                GifCard(p, colors, repo, Modifier.combinedClickable(onClick = {}, onLongClick = { if (FeedStore.isOwner) removing = p }))
                            }
                        }
                    }
                }
            }
            if (FeedStore.isOwner) Box(
                Modifier.align(Alignment.BottomEnd).systemBarsPadding().padding(20.dp).size(58.dp).clip(CircleShape)
                    .background(colors.accent).clickable { composing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(AlphaIcons.Outlined.Add, stringResource(R.string.feed_new_post), tint = Color.White) }
            if (composing) FeedComposer(colors, repo, onClose = { composing = false })
            if (openSet) com.alphamessenger.ui.emoji.EmojiSetScreen(colors, repo, onDismiss = { openSet = false })
            // The channel is a window over the app: a theme opened here is previewed here, not in the next chat.
            com.alphamessenger.ui.theme.ThemeLinkHost()
        }
    }

    if (keyDialog) OwnerKeyDialog(colors, repo, onDismiss = { keyDialog = false })
    removingTheme?.let { t ->
        AlertDialog(
            onDismissRequest = { removingTheme = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.feed_theme_remove, t.name), color = colors.textPrimary) },
            confirmButton = { TextButton(onClick = {
                removingTheme = null
                scope.launch { FeedStore.ownerKey(ctx)?.let { k -> ThemeCatalog.remove(ctx, repo, k, t.link) } }
            }) { Text(stringResource(R.string.common_delete), color = colors.danger) } },
            dismissButton = { TextButton(onClick = { removingTheme = null }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
        )
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.feed_remove_title), color = colors.textPrimary) },
            confirmButton = { TextButton(onClick = { val id = p.id; removing = null; scope.launch { FeedStore.delete(ctx, repo, id) } }) {
                Text(stringResource(R.string.common_delete), color = colors.danger) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
        )
    }
}

/** A theme of the channel, two to a row: its look on a small chat, its name, light or dark. */
@Composable
private fun CatalogThemeCard(t: CatalogTheme, colors: AlphaColorScheme, modifier: Modifier) {
    val theme = remember(t.link) { com.alphamessenger.ui.theme.CustomTheme.fromLink(t.link) }
    val scheme = remember(theme) { theme?.scheme() }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(colors.bgSecondary).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(12.dp)).background(scheme?.bgChat ?: colors.bgTertiary)) {
            theme?.background?.let { b -> Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(b, theme.background2 ?: b)))) }
            theme?.pattern?.let { com.alphamessenger.ui.theme.PatternWallpaper(it.copy(scale = it.scale * 0.6f), Modifier.matchParentSize(), dark = !theme.isLight) }
            if (scheme != null) Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.Bottom)) {
                Box(Modifier.fillMaxWidth(0.62f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(scheme.msgIn))
                Box(Modifier.align(Alignment.End).fillMaxWidth(0.7f).height(16.dp).clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(scheme.msgOutStart, scheme.msgOutEnd))))
            }
        }
        Text(t.name.ifBlank { theme?.name.orEmpty() }, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp, start = 2.dp))
        if (theme != null) Text(stringResource(if (theme.isLight) R.string.theme_card_light else R.string.theme_card_dark),
            color = colors.textSecondary, fontSize = 11.5.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun Empty(colors: AlphaColorScheme, loading: Boolean) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(if (loading) R.string.feed_loading else R.string.feed_empty), color = colors.textMuted, fontSize = 14.sp)
    }
}

/** A blob ref, fetched to a local file (null until it is here). */
@Composable
private fun rememberFetched(repo: AlphaCoreRepository, ref: String?): String? =
    // A picture already fetched is drawn on the first frame, never fetched again.
    produceState(remember(ref) { repo.cachedMediaPathIfPresent(ref) }, ref) {
        if (value == null) value = ref?.let { repo.downloadMediaToCache(it) }
    }.value

private fun dateOf(at: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(at * 1000))

@Composable
private fun NewsCard(p: FeedPost, colors: AlphaColorScheme, repo: AlphaCoreRepository, modifier: Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.bgSecondary)) {
        if (p.image != null) {
            val path = rememberFetched(repo, p.image)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(colors.bgTertiary)) {
                path?.let { AsyncImage(model = File(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(stringResource(when (p.kind) {
                FeedKind.UPDATE -> R.string.feed_kind_app_update
                FeedKind.ALERT -> R.string.feed_kind_alert
                else -> R.string.feed_kind_update
            }) + " · " + dateOf(p.at), color = colors.textSecondary, fontSize = 12.sp)
            if (p.title.isNotBlank()) Text(p.title, color = colors.textPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 4.dp))
            if (p.body.isNotBlank()) com.alphamessenger.ui.chat.text.MessageText(
                text = p.body, color = colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            // The build this post brings, while it is newer than the one installed: installed from here.
            if (p.kind == FeedKind.UPDATE) {
                val ctx = LocalContext.current
                if (com.alphamessenger.ui.update.AppUpdate.available(ctx)?.versionCode == p.versionCode) {
                    com.alphamessenger.ui.update.UpdateCard(colors, repo, Modifier.padding(top = 10.dp), expanded = true, notes = false)
                }
            }
        }
    }
}

/** A pack offered in the feed: its cover, name, size and Add / Added. */
@Composable
private fun PackCard(p: FeedPost, colors: AlphaColorScheme, repo: AlphaCoreRepository, modifier: Modifier) {
    val ctx = LocalContext.current
    val version by StickerStore.version.collectAsState()
    // Resolved once per session, outside the card: a card scrolled away and back
    // shows the same pack and state at once, never "Add" flickering to "Added".
    val link = p.pack
    if (link != null) androidx.compose.runtime.LaunchedEffect(link) { com.alphamessenger.ui.sticker.StickerUi.preload(ctx, link, repo) }
    val preview = link?.let { com.alphamessenger.ui.sticker.StickerUi.preloaded[it] ?: remember(it, version) { StickerStore.previewNow(ctx, it) } }
    val added = preview?.id?.let { id -> remember(id, version) { StickerStore.has(ctx, id) } } == true
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(colors.bgSecondary).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            preview?.stickers?.firstOrNull()?.let { StickerImage(it.file, Modifier.size(64.dp), 192) }
                ?: CircularProgressIndicator(color = colors.textMuted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        Text(preview?.name ?: p.title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        preview?.let { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.sticker_count, it.stickers.size, it.stickers.size),
            color = colors.textSecondary, fontSize = 11.5.sp) }
        Box(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(10.dp))
                .background(if (added) colors.bgTertiary else colors.accent)
                .clickable(enabled = !added && preview != null) { preview?.let { StickerStore.add(ctx, it, p.pack!!) } },
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(if (added) R.string.feed_pack_added else R.string.feed_pack_add),
                color = if (added) colors.textSecondary else Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A GIF offered in the feed: it plays, and Save keeps it among my GIFs. */
@Composable
private fun GifCard(p: FeedPost, colors: AlphaColorScheme, repo: AlphaCoreRepository, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val path = rememberFetched(repo, p.gif)
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(colors.bgSecondary)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(colors.bgTertiary), contentAlignment = Alignment.Center) {
            if (path != null) com.alphamessenger.ui.profile.LoopingVideo(path, Modifier.fillMaxSize())
            else CircularProgressIndicator(color = colors.textMuted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.common_save), color = colors.accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = path != null) {
                    scope.launch {
                        val ok = path != null && com.alphamessenger.ui.gif.GifLibrary.save(ctx, File(path), p.title)
                        android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.gif_saved else R.string.gif_make_failed),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }.padding(4.dp))
        }
    }
}

/** The owner's key: enter it once (checked against Alpha's key), or forget it. */
@Composable
internal fun OwnerKeyDialog(colors: AlphaColorScheme, repo: AlphaCoreRepository, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(R.string.feed_key_title), color = colors.textPrimary) },
        text = {
            Column {
                Text(stringResource(if (FeedStore.isOwner) R.string.feed_key_is_set else R.string.feed_key_hint), color = colors.textSecondary, fontSize = 13.sp)
                if (!FeedStore.isOwner) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = key, onValueChange = { key = it; wrong = false }, singleLine = true,
                        placeholder = { Text("nsec1…", color = colors.textMuted) }, isError = wrong)
                    if (wrong) Text(stringResource(R.string.feed_key_wrong), color = colors.danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (FeedStore.isOwner) TextButton(onClick = { FeedStore.forgetOwnerKey(ctx); onDismiss() }) {
                Text(stringResource(R.string.feed_key_forget), color = colors.danger) }
            else TextButton(enabled = key.isNotBlank(), onClick = {
                scope.launch { if (FeedStore.setOwnerKey(ctx, repo, key)) onDismiss() else wrong = true }
            }) { Text(stringResource(R.string.common_save), color = colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
    )
}

/** Writing a post (owner only): news with a picture, one of my sticker packs, or a saved GIF. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FeedComposer(colors: AlphaColorScheme, repo: AlphaCoreRepository, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(FeedKind.NEWS) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<Uri?>(null) }
    var packId by remember { mutableStateOf<String?>(null) }
    var gif by remember { mutableStateOf<String?>(null) }
    // The animated emoji set: one archive of "<code>.json" animations.
    var setFile by remember { mutableStateOf<File?>(null) }
    var setCount by remember { mutableStateOf(0) }
    val setPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val (f, n) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val f = File(ctx.cacheDir, "feed_set_${System.currentTimeMillis()}.zip")
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } }
                // -1: a sticker pack's archive (it belongs on the Stickers tab).
                val n = runCatching {
                    java.util.zip.ZipFile(f).use { z ->
                        val count = z.entries().asSequence().count { it.name.matches(SET_ENTRY) }
                        val stickers = z.getEntry("pack.json") != null || z.entries().asSequence().any { e -> e.name.substringAfterLast('.').lowercase() in STICKER_KINDS }
                        if (count == 0 && stickers) -1 else count
                    }
                }.getOrDefault(0)
                f to n
            }
            setFile?.delete()
            if (n > 0) { setFile = f; setCount = n } else {
                f.delete(); setFile = null; setCount = 0
                android.widget.Toast.makeText(ctx, ctx.getString(if (n < 0) R.string.feed_emoji_is_pack else R.string.feed_emoji_not_set),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    // An app build: this app, signed like the installed one (else no phone would take it).
    var apkFile by remember { mutableStateOf<File?>(null) }
    var apkInfo by remember { mutableStateOf<com.alphamessenger.ui.update.AppUpdate.Apk?>(null) }
    var apkReading by remember { mutableStateOf(false) }
    // The APK and, picked with it, its compile profile (.dm) from the same build.
    var dmFile by remember { mutableStateOf<File?>(null) }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            apkReading = true
            var foundApk: Pair<File, com.alphamessenger.ui.update.AppUpdate.Apk>? = null
            var foundDm: File? = null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for ((i, uri) in uris.withIndex()) {
                    val f = File(ctx.cacheDir, "feed_pick_${System.currentTimeMillis()}_$i")
                    runCatching { ctx.contentResolver.openInputStream(uri)?.use { s -> f.outputStream().use { s.copyTo(it) } } }
                    val info = com.alphamessenger.ui.update.AppUpdate.inspect(ctx, f)
                    when {
                        info != null && foundApk == null -> foundApk = f to info
                        foundDm == null && com.alphamessenger.ui.update.AppUpdate.isDexMetadata(f) -> foundDm = f
                        else -> f.delete()
                    }
                }
            }
            apkReading = false
            val apk = foundApk
            if (apk != null) {
                apkFile?.delete(); dmFile?.delete()
                apkFile = apk.first; apkInfo = apk.second; dmFile = foundDm
                if (title.isBlank()) title = "Alpha " + apk.second.versionName
            } else {
                foundDm?.delete()
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.feed_apk_wrong), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    var alertLevel by remember { mutableStateOf(FeedPost.ALERT_IMPORTANT) }
    var alertDays by remember { mutableStateOf(3) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) image = it }
    val stickerVersion by StickerStore.version.collectAsState()
    val ownPacks by produceState(emptyList<com.alphamessenger.ui.sticker.StickerPack>(), stickerVersion) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            StickerStore.packs(ctx).filter { it.own && it.id != StickerStore.SAVED_ID && it.stickers.isNotEmpty() }
        }
    }
    val gifs = remember { com.alphamessenger.ui.gif.GifLibrary.saved(ctx) }
    var deletingPack by remember { mutableStateOf<com.alphamessenger.ui.sticker.StickerPack?>(null) }
    // Several packs at once (a hold starts picking): each goes out as its own post, under its own name.
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var removingPicked by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    val publishing by PackPublishService.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(publishing) {
        (publishing as? PackPublishState.Done)?.let { r ->
            android.widget.Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.feed_published_packs, r.done, r.done, r.total), android.widget.Toast.LENGTH_LONG).show()
            // What did not go out stays picked, for one more tap.
            picked = r.failed; packId = null
            PackPublishService.consume()
        }
    }
    val packRunning = publishing as? PackPublishState.Running
    // Several packs at once out of the channel's catalog, and off this phone too if asked.
    if (removingPicked) {
        val chosen = ownPacks.filter { it.id in picked }
        fun remove(alsoPhone: Boolean) {
            removingPicked = false
            scope.launch {
                busy = true
                val key = FeedStore.ownerKey(ctx)
                val links = chosen.mapNotNull { it.link }.toSet()
                val ok = key != null && (links.isEmpty() || StickerCatalog.remove(ctx, repo, key, links))
                if (ok && alsoPhone) chosen.forEach { StickerStore.delete(ctx, it.id) }
                if (ok) picked = emptySet()
                busy = false
                android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.feed_removed_packs else R.string.feed_publish_failed, chosen.size),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog(
            onDismissRequest = { removingPicked = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.feed_remove_packs_title, chosen.size), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.feed_remove_packs_body), color = colors.textSecondary, fontSize = 14.sp) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { remove(false) }) { Text(stringResource(R.string.feed_remove_from_channel), color = colors.danger) }
                    TextButton(onClick = { remove(true) }) { Text(stringResource(R.string.feed_remove_everywhere), color = colors.danger) }
                    TextButton(onClick = { removingPicked = false }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) }
                }
            },
        )
    }
    deletingPack?.let { p ->
        AlertDialog(
            onDismissRequest = { deletingPack = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.sticker_remove_pack, p.name), color = colors.textPrimary) },
            confirmButton = { TextButton(onClick = {
                StickerStore.delete(ctx, p.id)
                if (packId == p.id) packId = null
                deletingPack = null
            }) { Text(stringResource(R.string.common_delete), color = colors.danger) } },
            dismissButton = { TextButton(onClick = { deletingPack = null }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
        )
    }
    // Sticker packs from archives: made mine and chosen for the post at once. One archive
    // becomes the post's pack; several are all picked, as a long press picks them.
    var importing by remember { mutableStateOf(false) }
    val packImport = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true
            val ids = ArrayList<String>()
            var already = 0
            for ((i, uri) in uris.withIndex()) {
                if (uris.size > 1) progress = ctx.getString(R.string.sticker_importing_of, i + 1, uris.size)
                val r = StickerStore.importArchive(ctx, uri)
                if (r.alreadyHere) already++
                r.packId?.let { ids += it }
            }
            progress = null
            importing = false
            if (uris.size == 1) {
                ids.firstOrNull()?.let { id -> packId = id; if (title.isBlank()) title = StickerStore.pack(ctx, id)?.name.orEmpty() }
            } else if (ids.isNotEmpty()) {
                packId = null
                picked = picked + ids
            }
            val text = when {
                uris.size > 1 -> ctx.getString(R.string.sticker_imported_packs, ids.size, uris.size)
                already > 0 -> ctx.getString(R.string.sticker_import_already)
                ids.isNotEmpty() -> ctx.getString(R.string.sticker_import_one)
                else -> ctx.getString(R.string.sticker_import_failed)
            }
            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(colors.bgPrimary).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = colors.textPrimary) }
            Text(stringResource(R.string.feed_new_post), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val ready = !busy && when (kind) {
                FeedKind.NEWS -> title.isNotBlank() || body.isNotBlank()
                FeedKind.PACK -> (packId != null || picked.isNotEmpty()) && packRunning == null
                FeedKind.GIF -> gif != null
                FeedKind.EMOJI -> setFile != null && title.isNotBlank()
                FeedKind.UPDATE -> apkInfo != null
                FeedKind.ALERT -> title.isNotBlank()
            }
            Box(
                Modifier.padding(end = 10.dp).clip(RoundedCornerShape(20.dp)).background(if (ready) colors.accent else colors.bgTertiary)
                    .clickable(enabled = ready) {
                        busy = true
                        scope.launch {
                            // Packs go into the catalog, in the background: the owner may leave meanwhile.
                            if (kind == FeedKind.PACK) {
                                PackPublishService.start(ctx, picked.ifEmpty { setOfNotNull(packId) })
                                busy = false
                                android.widget.Toast.makeText(ctx, ctx.getString(R.string.pack_publish_background), android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val imgFile = image?.let { uri ->
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val f = File(ctx.cacheDir, "feed_img_${System.currentTimeMillis()}.jpg")
                                    runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }; f }.getOrNull()
                                }
                            }
                            val apk = if (kind == FeedKind.UPDATE) apkInfo else null
                            val post = FeedPost("p" + System.currentTimeMillis(), 0, kind, title.trim(), body.trim(),
                                versionCode = apk?.versionCode ?: 0, versionName = apk?.versionName, sha256 = apk?.sha256,
                                level = if (kind == FeedKind.ALERT) alertLevel else null,
                                until = if (kind == FeedKind.ALERT && alertDays > 0) System.currentTimeMillis() / 1000 + alertDays * 86_400L else 0)
                            val ok = FeedStore.publish(ctx, repo, post,
                                if (kind == FeedKind.NEWS) imgFile else null,
                                if (kind == FeedKind.GIF) gif?.let { File(it) } else null,
                                if (kind == FeedKind.EMOJI) setFile else null,
                                if (kind == FeedKind.UPDATE) apkFile else null,
                                if (kind == FeedKind.UPDATE) dmFile else null)
                            imgFile?.delete()
                            if (ok) { setFile?.delete(); apkFile?.delete(); dmFile?.delete() }
                            busy = false
                            android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.feed_published else R.string.feed_publish_failed),
                                android.widget.Toast.LENGTH_SHORT).show()
                            if (ok) onClose()
                        }
                    }.padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                val running = packRunning?.takeIf { kind == FeedKind.PACK }
                if (busy || running != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    (running?.let { stringResource(if (it.moving) R.string.pack_publish_moving_short else R.string.feed_publishing_of, it.done, it.total) } ?: progress)?.let { Spacer(Modifier.width(8.dp)); Text(it, color = Color.White, fontSize = 13.sp) }
                    // Stop from here too, not only from the notification.
                    if (running != null) Text("  ✕", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { PackPublishService.cancel(ctx) }.padding(start = 6.dp))
                }
                else Text(stringResource(R.string.feed_publish), color = if (ready) Color.White else colors.textMuted, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(FeedKind.NEWS to R.string.feed_tab_news, FeedKind.ALERT to R.string.feed_tab_alert, FeedKind.UPDATE to R.string.feed_tab_app_update,
                FeedKind.PACK to R.string.feed_tab_stickers, FeedKind.GIF to R.string.feed_tab_gif, FeedKind.EMOJI to R.string.feed_tab_emoji).forEach { (k, label) ->
                val on = k == kind
                Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) colors.accent else colors.bgTertiary)
                    .clickable { kind = k }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(label), color = if (on) Color.White else colors.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val newsScroll = androidx.compose.foundation.rememberScrollState()
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
                .then(if (kind == FeedKind.NEWS) Modifier.verticalScroll(newsScroll) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = title, onValueChange = { title = it.take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.feed_field_title), color = colors.textMuted) })
            when (kind) {
                FeedKind.NEWS -> {
                    // The picture first, small until chosen; the text below grows and the page scrolls.
                    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    if (image == null) Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(colors.bgTertiary).clickable { pick() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.feed_field_image), color = colors.textPrimary, fontSize = 14.sp)
                    } else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).clickable { pick() }) {
                        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)).clickable { image = null },
                            contentAlignment = Alignment.Center,
                        ) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_delete), tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                    OutlinedTextField(value = body, onValueChange = { body = it.take(4000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        placeholder = { Text(stringResource(R.string.feed_field_body), color = colors.textMuted) })
                    Spacer(Modifier.height(12.dp))
                }
                FeedKind.PACK -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !importing) { packImport.launch("application/zip") }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (importing) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            else Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.sticker_import_pack), color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (ownPacks.isEmpty()) item { Text(stringResource(R.string.feed_no_own_packs), color = colors.textMuted, fontSize = 13.sp) }
                    // After a mass import: everything, or only what the feed does not have yet.
                    val inFeed = StickerCatalog.packs.mapTo(HashSet()) { it.link }.apply { FeedStore.posts.mapNotNullTo(this) { it.pack } }
                    val fresh = ownPacks.filter { it.link == null || it.link !in inFeed }
                    if (ownPacks.size > 1) item {
                        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Choice(colors, picked.size == ownPacks.size, stringResource(R.string.feed_pick_all, ownPacks.size), colors.accent) {
                                picked = ownPacks.map { it.id }.toSet(); packId = null
                            }
                            if (fresh.isNotEmpty() && fresh.size < ownPacks.size)
                                Choice(colors, picked == fresh.map { it.id }.toSet(), stringResource(R.string.feed_pick_fresh, fresh.size), colors.accent) {
                                    picked = fresh.map { it.id }.toSet(); packId = null
                                }
                            if (picked.isNotEmpty()) Choice(colors, false, stringResource(R.string.feed_pick_none), colors.accent) { picked = emptySet() }
                            if (picked.isNotEmpty()) Choice(colors, false, stringResource(R.string.feed_pick_remove, picked.size), colors.danger) { removingPicked = true }
                        }
                    }
                    if (picked.isNotEmpty()) item {
                        Text(pluralStringResource(R.plurals.feed_packs_picked, picked.size, picked.size),
                            color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    items(ownPacks, key = { it.id }) { p ->
                        val on = if (picked.isEmpty()) packId == p.id else p.id in picked
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (on) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    if (picked.isNotEmpty()) picked = if (p.id in picked) picked - p.id else picked + p.id
                                    else { packId = p.id; if (title.isBlank()) title = p.name }
                                },
                                onLongClick = { picked = picked + p.id + listOfNotNull(packId); packId = null },
                            ).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (picked.isNotEmpty()) {
                                Box(
                                    Modifier.size(22.dp).clip(CircleShape).background(if (on) colors.accent else colors.bgTertiary),
                                    contentAlignment = Alignment.Center,
                                ) { if (on) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.width(10.dp))
                            }
                            p.cover?.let { StickerImage(it.file, Modifier.size(44.dp), 96) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = colors.textPrimary, fontSize = 15.sp)
                                if (p.link != null && p.link in inFeed) Text(stringResource(R.string.feed_pack_in_feed), color = colors.textMuted, fontSize = 12.sp)
                            }
                            // A pack made by mistake or twice goes from here, with the question first.
                            IconButton(onClick = { deletingPack = p }) {
                                Icon(AlphaIcons.Outlined.Delete, stringResource(R.string.common_delete), tint = colors.textMuted)
                            }
                        }
                    }
                }
                FeedKind.EMOJI -> {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(colors.bgTertiary)
                            .clickable { setPicker.launch("application/zip") }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (setFile == null) stringResource(R.string.feed_emoji_pick)
                            else pluralStringResource(R.plurals.feed_emoji_count, setCount, setCount),
                            color = colors.textPrimary, fontSize = 14.sp,
                        )
                    }
                    OutlinedTextField(value = body, onValueChange = { body = it.take(4000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        placeholder = { Text(stringResource(R.string.feed_emoji_body_hint), color = colors.textMuted) })
                    Text(stringResource(R.string.feed_emoji_note), color = colors.textMuted, fontSize = 13.sp)
                }
                FeedKind.UPDATE -> {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(colors.bgTertiary)
                            .clickable(enabled = !apkReading) { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (apkReading) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        val info = apkInfo
                        Text(
                            if (info == null) stringResource(R.string.feed_apk_pick)
                            else stringResource(R.string.feed_apk_info, info.versionName, "%.1f".format((apkFile?.length() ?: 0L) / 1_048_576.0)) +
                                (if (dmFile != null) " · " + stringResource(R.string.feed_apk_with_dm) else ""),
                            color = colors.textPrimary, fontSize = 14.sp,
                        )
                    }
                    OutlinedTextField(value = body, onValueChange = { body = it.take(4000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        placeholder = { Text(stringResource(R.string.feed_apk_notes_hint), color = colors.textMuted) })
                    Text(stringResource(R.string.feed_apk_note), color = colors.textMuted, fontSize = 13.sp)
                }
                FeedKind.ALERT -> {
                    OutlinedTextField(value = body, onValueChange = { body = it.take(600) }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        placeholder = { Text(stringResource(R.string.feed_alert_body_hint), color = colors.textMuted) })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Choice(colors, alertLevel == FeedPost.ALERT_IMPORTANT, stringResource(R.string.feed_alert_important), Color(0xFFF5A524)) { alertLevel = FeedPost.ALERT_IMPORTANT }
                        Choice(colors, alertLevel == FeedPost.ALERT_CRITICAL, stringResource(R.string.feed_alert_critical), colors.danger) { alertLevel = FeedPost.ALERT_CRITICAL }
                    }
                    Text(stringResource(R.string.feed_alert_for), color = colors.textSecondary, fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 3, 7, 0).forEach { d ->
                            Choice(colors, alertDays == d, if (d == 0) stringResource(R.string.feed_alert_forever) else pluralStringResource(R.plurals.feed_alert_days, d, d), colors.accent) { alertDays = d }
                        }
                    }
                    Text(stringResource(R.string.feed_alert_note), color = colors.textMuted, fontSize = 13.sp)
                }
                FeedKind.GIF -> LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(gifs, key = { it.path }) { g ->
                        val poster = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, g.path) {
                            value = com.alphamessenger.ui.chat.bubbles.extractVideoThumb(g.path)?.asImageBitmap()
                        }.value
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(colors.bgTertiary)
                            .then(if (gif == g.path) Modifier.background(colors.accent.copy(alpha = 0.3f)) else Modifier)
                            .clickable { gif = g.path }) {
                            poster?.let { androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            if (gif == g.path) Box(Modifier.fillMaxSize().background(colors.accent.copy(alpha = 0.35f)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(colors: AlphaColorScheme, on: Boolean, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) tint else colors.bgTertiary)
            .clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Color.White else colors.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
}

/** An animation in the emoji set's archive: "<code points>.json". */
private val SET_ENTRY = Regex("""^[0-9a-f_]{1,64}\.json$""")

/** Files that make an archive a sticker pack (for the Stickers tab, not the emoji set). */
private val STICKER_KINDS = setOf("tgs", "lottie", "json", "webp", "gif", "png")
private const val CATALOG_ID = "cat:"
