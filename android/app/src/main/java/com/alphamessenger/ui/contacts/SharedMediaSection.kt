package com.alphamessenger.ui.contacts

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.chat.media.MediaViewer
import com.alphamessenger.ui.chat.media.toMediaItem
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.parseLinkPreview
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * classic shared-media browser: category tabs (Media / Files / Music /
 * Voice / Links), a pinch-resizable photo/video grid, tap-to-open, and a
 * long-press multi-select mode with a top action bar (close / view-in-chat /
 * forward / delete).
 */
/**
 * Long-press selection in the shared media. Held by the profile screen: its
 * pinned strip shows the actions in place of the tabs.
 */
@androidx.compose.runtime.Stable
class SharedSelection {
    var ids by mutableStateOf(setOf<String>())
        private set
    var forwarding by mutableStateOf(false)
    var deleting by mutableStateOf(false)
    val active: Boolean get() = ids.isNotEmpty()

    fun start(id: String) { ids = ids + id }
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun clear() { ids = emptySet(); forwarding = false; deleting = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedMediaSection(
    state: ContactInfoUiState,
    colors: AlphaColorScheme,
    mediaLoader: suspend (String) -> String?,
    repository: AlphaCoreRepository?,
    viewModel: ContactInfoViewModel? = null,
    onOpenInChat: (messageId: String) -> Unit = {},
    /** The category the host's tabs picked; null = the first one there is. */
    onlyCategory: String? = null,
    selection: SharedSelection = remember { SharedSelection() },
) {
    val cats = remember(state) {
        buildList {
            if (state.sharedMedia.isNotEmpty()) add("media" to state.sharedMedia)
            if (state.sharedFiles.isNotEmpty()) add("files" to state.sharedFiles)
            if (state.sharedMusic.isNotEmpty()) add("music" to state.sharedMusic)
            if (state.sharedVoice.isNotEmpty()) add("voice" to state.sharedVoice)
            if (state.sharedLinks.isNotEmpty()) add("links" to state.sharedLinks)
        }
    }
    if (cats.isEmpty() && state.sharedLoading) {
        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
        return
    }
    if (cats.isEmpty()) {
        Text(
            stringResource(R.string.shared_media_empty),
            color = colors.textMuted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        return
    }
    val sel = onlyCategory?.let { id -> cats.indexOfFirst { it.first == id } }?.takeIf { it >= 0 } ?: 0
    val items = cats[sel].second
    val selectMode = selection.active
    val selectedIds = selection.ids
    val toggleSelect: (MessageUi) -> Unit = { item -> selection.toggle(item.id) }
    val startSelect: (MessageUi) -> Unit = { item -> selection.start(item.id) }

    // Screen-scoped audio player for music + voice rows.
    val scope = rememberCoroutineScope()
    val playerRef = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { runCatching { playerRef.value?.release() }; playerRef.value = null }
    }
    val toggleAudio: (MessageUi) -> Unit = { item ->
        if (playingId == item.id && playerRef.value != null) {
            runCatching { playerRef.value?.release() }
            playerRef.value = null
            playingId = null
        } else {
            runCatching { playerRef.value?.release() }
            playerRef.value = null
            playingId = item.id
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    item.localMediaPath?.takeIf { File(it).exists() }
                        ?: item.mediaRefJson?.let { runCatching { mediaLoader(it) }.getOrNull() }
                }
                if (path == null) { playingId = null; return@launch }
                runCatching {
                    val mp = android.media.MediaPlayer()
                    com.alphamessenger.data.MediaVault.setSource(mp, path)
                    mp.setOnCompletionListener { playingId = null }
                    mp.prepare()
                    mp.start()
                    playerRef.value = mp
                }.onFailure { playingId = null }
            }
        }
    }

    when (cats[sel].first) {
        "media" -> MediaGrid(
            items, colors, mediaLoader, repository,
            selectMode = selectMode, selectedIds = selectedIds,
            onToggleSelect = toggleSelect, onStartSelect = startSelect,
        )
        "links" -> items.forEach { item ->
            LinkRow(
                item, colors,
                selectMode = selectMode, selected = item.id in selectedIds,
                onToggleSelect = { toggleSelect(item) }, onStartSelect = { startSelect(item) },
            )
        }
        else -> items.forEach { item ->
            FileRow(
                item, colors, isPlaying = playingId == item.id,
                selectMode = selectMode, selected = item.id in selectedIds,
                // A video message plays in the chat, as a circle; a voice note or a track here.
                onClick = { if (item.isRoundVideo) onOpenInChat(item.id) else toggleAudio(item) },
                onToggleSelect = { toggleSelect(item) }, onStartSelect = { startSelect(item) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    // --- Delete dialog ("for me" / "for everyone") ---
    if (selection.deleting) {
        val ids = selectedIds.toList()
        val localOnly = state.chatId == "saved_messages_local" ||
            com.alphamessenger.assistant.isAssistantChat(state.chatId)
        if (localOnly) AlertDialog(
            onDismissRequest = { selection.deleting = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.shared_delete_count_title, ids.size), color = colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel?.deleteForMe(ids); selection.clear()
                }) { Text(stringResource(R.string.common_delete), color = Color(0xFFFF5A5A)) }
            },
            dismissButton = {
                TextButton(onClick = { selection.deleting = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                }
            },
        ) else AlertDialog(
            onDismissRequest = { selection.deleting = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.shared_delete_count_title, ids.size), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.shared_delete_choice), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel?.deleteForEveryone(ids); selection.clear()
                }) { Text(stringResource(R.string.shared_delete_for_all), color = Color(0xFFFF5A5A)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel?.deleteForMe(ids); selection.clear()
                    }) { Text(stringResource(R.string.shared_delete_for_me), color = colors.accent) }
                    TextButton(onClick = { selection.deleting = false }) {
                        Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                    }
                }
            },
        )
    }

    // --- Forward: the same sheet as in the chat ---
    if (selection.forwarding && viewModel != null) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val chosen = remember(selectedIds, items) { items.filter { it.id in selectedIds && !it.isViewOnce } }
        androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.loadForwardTargets() }
        val targets by viewModel.forwardTargets.collectAsState()
        val set = targets ?: com.alphamessenger.ui.chat.ForwardTargetSet()
        com.alphamessenger.ui.chat.ChatPickerSheet(
            chats = set.chats,
            profiles = set.profiles,
            channelIds = set.channelIds,
            summary = com.alphamessenger.ui.chat.forwardSummary(chosen),
            colors = colors,
            onDismiss = { selection.forwarding = false },
            onConfirm = { targetIds, comment, plain ->
                viewModel.forwardToTargets(chosen, targetIds, comment, plain)
                selection.clear()
                android.widget.Toast.makeText(
                    ctx,
                    if (targetIds.size > 1) ctx.getString(R.string.chat_forwarded_count, targetIds.size) else ctx.getString(R.string.chat_forwarded),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
}

/**
 * The actions over a selection, in the same slot and size as the tabs it
 * replaces, so nothing below moves when it appears.
 */
@Composable
fun SharedSelectionStrip(
    count: Int,
    colors: AlphaColorScheme,
    onClose: () -> Unit,
    onView: (() -> Unit)?,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(colors.bgSecondary)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StripIcon(AlphaIcons.Filled.Close, stringResource(R.string.common_close), colors.textPrimary, onClose)
        Text(
            "$count", color = colors.textPrimary,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        if (onView != null) StripIcon(AlphaIcons.Outlined.Visibility, stringResource(R.string.shared_find_in_chat), colors.textPrimary, onView)
        StripIcon(AlphaIcons.AutoMirrored.Filled.Forward, stringResource(R.string.common_forward), colors.textPrimary, onForward)
        StripIcon(AlphaIcons.Outlined.Delete, stringResource(R.string.common_delete), Color(0xFFFF5A5A), onDelete)
    }
}

@Composable
private fun StripIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    items: List<MessageUi>,
    colors: AlphaColorScheme,
    mediaLoader: suspend (String) -> String?,
    repository: AlphaCoreRepository?,
    selectMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (MessageUi) -> Unit,
    onStartSelect: (MessageUi) -> Unit,
) {
    // Column count persists across app restarts (SharedPreferences).
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("shared_media_grid", android.content.Context.MODE_PRIVATE) }
    var cols by remember { mutableIntStateOf(prefs.getInt("cols", 3)) }
    LaunchedEffectKey(cols) { prefs.edit().putInt("cols", cols).apply() }
    var viewerStart by remember { mutableStateOf<Int?>(null) }

    // A FINITE max height (viewport) gives the grid a bounded constraint — the
    // parent verticalScroll would otherwise pass Infinity, forcing every tile to
    // be measured + decoded (the 4–6-column lag). Bounded → the grid virtualizes:
    // only visible rows (+ small prefetch) are composed, memory stays flat.
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        modifier = Modifier.fillMaxWidth().heightIn(max = screenH).padding(horizontal = 2.dp)
            // Pinch on 2+ fingers → resize columns.
            .pointerInput(Unit) {
                awaitEachGesture {
                    var zoomAcc = 1f
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            zoomAcc *= event.calculateZoom()
                            if (zoomAcc > 1.18f && cols > 2) { cols--; zoomAcc = 1f }
                            if (zoomAcc < 0.85f && cols < 6) { cols++; zoomAcc = 1f }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }, contentType = { _, item -> item.mediaType }) { index, item ->
            Box(Modifier.aspectRatio(1f)) {
                MediaThumb(
                    item, mediaLoader,
                    selectMode = selectMode, selected = item.id in selectedIds,
                    onTap = {
                        if (selectMode) onToggleSelect(item)
                        else viewerStart = index
                    },
                    onLongPress = { onStartSelect(item) },
                )
            }
        }
    }

    // The attachment gallery opens in the same unified MediaViewer as the chat.
    viewerStart?.let { start ->
        val viewerItems = remember(items) { items.mapNotNull { it.toMediaItem() } }
        val startId = items.getOrNull(start)?.id
        val startIdx = viewerItems.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        if (viewerItems.isNotEmpty()) {
            MediaViewer(
                collection = com.alphamessenger.ui.chat.media.MediaCollection.sharedMedia(viewerItems, startIdx),
                onDismiss = { viewerStart = null },
                onDownload = {},
                repository = repository,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaThumb(
    item: MessageUi,
    mediaLoader: suspend (String) -> String?,
    selectMode: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val ctx = LocalContext.current
    val isVideo = item.mediaType == "video"

    // VIDEO: unchanged (poster/frame via retriever) — only photos go through the grid pipeline.
    // Frames are kept in memory: reopening the page shows them at once.
    var videoBmp by remember(item.id) { mutableStateOf(if (isVideo) VideoThumbs.get(item.id) else null) }
    if (isVideo && videoBmp == null) {
        LaunchedEffectKey(item.id) {
            videoBmp = withContext(Dispatchers.IO) {
                runCatching {
                    item.mediaPosterB64?.let { decodeB64(it) }
                        ?: run {
                            val p = item.localMediaPath?.takeIf { File(it).exists() }
                                ?: item.mediaRefJson?.let { mediaLoader(it) }
                            p?.let { videoFrame(ctx, it) }
                        }
                }.getOrNull()
            }?.also { VideoThumbs.put(item.id, it) }
        }
    }

    // PHOTO: Coil does decode + downsample-to-cell + memory/disk cache. Only the
    // file PATH is resolved here (local instantly; encrypted refs via a one-off
    // loader) — the heavy full-res BitmapFactory.decodeFile + GC churn is gone.
    var photoPath by remember(item.id) {
        mutableStateOf(if (isVideo) null else item.localMediaPath?.takeIf { File(it).exists() })
    }
    if (!isVideo && photoPath == null) {
        LaunchedEffectKey(item.id) {
            photoPath = item.mediaRefJson?.let { runCatching { mediaLoader(it) }.getOrNull() }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { com.alphamessenger.ui.chat.media.MediaSourceRegistry.put(item.id, it.boundsInWindow()) }.alpha(if (com.alphamessenger.ui.chat.media.MediaSourceRegistry.isViewing(item.id)) 0f else 1f).clip(RoundedCornerShape(3.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (isVideo) {
            videoBmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        } else photoPath?.let { p ->
            coil3.compose.AsyncImage(
                model = coil3.request.ImageRequest.Builder(ctx)
                    .data(File(p))
                    .memoryCacheKey(p)
                    .diskCacheKey(p)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The ▶ comes with the video's frame — alone on an empty cell it flickered in first.
        if (item.mediaType == "video" && videoBmp != null) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) { Icon(AlphaIcons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
        if (selectMode) {
            if (selected) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .size(20.dp).clip(CircleShape).background(Color(0xFF34C759)),
                    contentAlignment = Alignment.Center,
                ) { Icon(AlphaIcons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            } else {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .size(20.dp).clip(CircleShape).background(Color(0x66FFFFFF)),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: MessageUi,
    colors: AlphaColorScheme,
    isPlaying: Boolean = false,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onToggleSelect: () -> Unit = {},
    onStartSelect: () -> Unit = {},
) {
    val isAudio = item.mediaType == "audio"
    val isVoice = isAudio && item.mediaFilename?.startsWith("voice") == true
    val isRound = item.isRoundVideo
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) colors.accent.copy(alpha = 0.15f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (selectMode) onToggleSelect() else onClick?.invoke() },
                onLongClick = onStartSelect,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(colors.bgTertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    isPlaying -> AlphaIcons.Filled.Stop
                    isRound -> AlphaIcons.Outlined.Videocam
                    isAudio -> if (isVoice) AlphaIcons.Filled.PlayArrow else AlphaIcons.Outlined.MusicNote
                    else -> AlphaIcons.Outlined.InsertDriveFile
                },
                null, tint = colors.accent, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (isRound) null else item.mediaFilename) ?: when {
                    isRound -> stringResource(R.string.round_label)
                    isVoice -> stringResource(R.string.preview_voice)
                    isAudio -> stringResource(R.string.preview_audio)
                    else -> stringResource(R.string.preview_file)
                },
                color = colors.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                isPlaying -> stringResource(R.string.media_playing)
                item.mediaSizeBytes != null -> humanSize(item.mediaSizeBytes)
                else -> null
            }
            sub?.let { Text(it, color = if (isPlaying) colors.accent else colors.textMuted, fontSize = 12.sp) }
        }
        if (selectMode) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape)
                    .background(if (selected) Color(0xFF34C759) else Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(AlphaIcons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LinkRow(
    item: MessageUi,
    colors: AlphaColorScheme,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onStartSelect: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val link = remember(item.mediaRefJson, item.text) { linkOf(item) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) colors.accent.copy(alpha = 0.15f) else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (selectMode) onToggleSelect()
                    else link?.url?.let {
                        runCatching {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(it)), ctx.getString(R.string.common_open)))
                        }
                    }
                },
                onLongClick = onStartSelect,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinkPicture(link, colors)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                link?.title?.takeIf { it.isNotBlank() } ?: link?.url ?: stringResource(R.string.preview_link),
                color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            link?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = colors.textSecondary, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                link?.site?.takeIf { it.isNotBlank() } ?: hostOf(link?.url.orEmpty()),
                color = colors.accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectMode) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape)
                    .background(if (selected) Color(0xFF34C759) else Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(AlphaIcons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// --- helpers ---

private val URL_IN_TEXT = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")

/** The link a message carries: its preview card, else the first address in its text. */
internal fun linkOf(item: MessageUi): com.alphamessenger.ui.chat.LinkPreviewData? {
    parseLinkPreview(item.mediaRefJson)?.let { return it }
    val text = item.text.ifBlank { item.mediaCaption.orEmpty() }
    val m = URL_IN_TEXT.find(text) ?: return null
    val url = m.value.trimEnd('.', ',', ')', '!', '?').let { if (it.startsWith("www.", true)) "https://$it" else it }
    // The words around the address read as its title; a bare address shows its host.
    val words = com.alphamessenger.ui.chat.text.Markup.plain(text).replace(m.value, " ").replace(Regex("\\s+"), " ").trim()
    return com.alphamessenger.ui.chat.LinkPreviewData(url = url, title = words.take(120), description = "", image = "", site = hostOf(url), imageRef = "", video = false)
}

internal fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull().orEmpty().ifBlank { url }

/** The site's picture when the preview carried one, else its initial on the accent. */
@Composable
private fun LinkPicture(link: com.alphamessenger.ui.chat.LinkPreviewData?, colors: AlphaColorScheme) {
    val pic = remember(link?.image) { link?.image?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { decodeB64(it.substringAfter("base64,")) } }
    Box(
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.accent.copy(alpha = 0.85f), colors.accent.copy(alpha = 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (pic != null) Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(
            hostOf(link?.url.orEmpty()).firstOrNull()?.uppercaseChar()?.toString() ?: "#",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LaunchedEffectKey(key: Any?, block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { block() }
}

private fun decodeB64(b64: String): ImageBitmap? = runCatching {
    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    com.alphamessenger.util.BoundedBitmap.decode(bytes)?.asImageBitmap()
}.getOrNull()

private fun videoFrame(ctx: android.content.Context, path: String): ImageBitmap? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    com.alphamessenger.data.MediaVault.setSource(r, path)
    // A grid cell is small: decode the frame at cell size, not the video's own.
    val b = if (android.os.Build.VERSION.SDK_INT >= 27)
        r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
    else r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    r.release()
    b?.asImageBitmap()
}.getOrNull()

private fun humanSize(bytes: Long): String = com.alphamessenger.media.MediaSizeFormat.short(bytes)

/** Non-empty shared categories of a chat, in display order: (id, label, icon, count). */
@Composable
fun sharedCategories(state: ContactInfoUiState): List<SharedCategory> = buildList {
    if (state.sharedMedia.isNotEmpty()) add(SharedCategory("media", stringResource(R.string.media_title), AlphaIcons.Outlined.PhotoLibrary, state.sharedMedia.size))
    if (state.sharedFiles.isNotEmpty()) add(SharedCategory("files", stringResource(R.string.media_kind_file), AlphaIcons.Outlined.InsertDriveFile, state.sharedFiles.size))
    if (state.sharedMusic.isNotEmpty()) add(SharedCategory("music", stringResource(R.string.media_kind_music), AlphaIcons.Outlined.MusicNote, state.sharedMusic.size))
    if (state.sharedVoice.isNotEmpty()) add(SharedCategory("voice", stringResource(R.string.media_kind_voice), AlphaIcons.Outlined.Mic, state.sharedVoice.size))
    if (state.sharedLinks.isNotEmpty()) add(SharedCategory("links", stringResource(R.string.media_kind_links), AlphaIcons.Outlined.Link, state.sharedLinks.size))
}

class SharedCategory(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val count: Int)

/** Video frames of the media grid, by message id (about 24 MB at most). */
private object VideoThumbs {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    fun get(id: String): ImageBitmap? = cache.get(id)
    fun put(id: String, bmp: ImageBitmap) { cache.put(id, bmp) }
}
