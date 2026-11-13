@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.alphamessenger.ui.media.preview

import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.chat.v2.focusAndShowKeyboard
import com.alphamessenger.ui.chat.v2.hideKeyboard
import com.alphamessenger.ui.chat.v2.insert
import androidx.compose.foundation.systemGestureExclusion
import com.alphamessenger.ui.chat.v2.backspace
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import com.alphamessenger.ui.components.glassSource
import com.alphamessenger.ui.components.glassPanel
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.media.draft.MediaDraftStore
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.SystemBars
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The single pre-send preview screen: gallery, camera, files, audio, video —
 * and, next, Share.
 *
 * Lives in the app's own window, NOT in a Dialog. The former viewer was a
 * dialog with its own window: the caption field slid under the keyboard and
 * the system bars stayed a foreign color — a dialog has its own insets and
 * background. Here plain `imePadding`/`statusBarsPadding` work, and the bar
 * color comes from the single window owner — [SystemBars].
 *
 * The screen holds no state of its own: the list, the current item and the
 * caption live in [MediaDraftStore].
 */
@Composable
fun MediaPreviewScreen(
    store: MediaDraftStore,
    colors: AlphaColorScheme,
    onClose: () -> Unit,
    onSend: (caption: String?) -> Unit,
    /** Long-press on Send: schedule the set (null = not offered here). */
    onScheduleSend: ((caption: String?) -> Unit)? = null,
    /** "Send without sound": armed right before the ordinary send (null = not offered). */
    onArmSilent: (() -> Unit)? = null,
    // Second header line. Empty for plain sending; when Sharing to several
    // chats it shows where the set will go.
    subtitle: String? = null,
    // Add to the set without leaving the viewer: gallery and camera. Empty —
    // the button is not shown (e.g. where the set has a single source).
    onAddMedia: (() -> Unit)? = null,
    onCamera: (() -> Unit)? = null,
    /** The set is still being read in (a Share): show the screen now, the items follow. */
    preparing: Boolean = false,
    /** Leaving drops the set (nothing to go back to), so it is asked first. Back to an
     *  open attachment panel keeps the set, and leaves without a question. */
    closeDiscards: Boolean = true,
    /** At the right inside the caption field: the view timer (null = not offered). */
    timer: (@Composable () -> Unit)? = null,
    /** Opened on a picture of the gallery: the pages run through the whole of it and the
     *  circle in the corner picks; the set is what is picked. Null = the set's own pages. */
    gallery: List<com.alphamessenger.ui.chat.GalleryItem>? = null,
    galleryStart: Int = 0,
    onGalleryToggle: ((com.alphamessenger.ui.chat.GalleryItem) -> Unit)? = null,
) {
    val items = store.items
    val browse = !gallery.isNullOrEmpty() && onGalleryToggle != null
    // Last item removed — nothing to show (browsing, the gallery is still there).
    LaunchedEffect(items.size, preparing) { if (items.isEmpty() && !preparing && !browse) onClose() }
    if (items.isEmpty() && !browse) {
        if (preparing) Preparing(colors, onClose)
        return
    }
    val pageCount = if (browse) gallery!!.size else items.size
    /** The set's item shown on [page], or null for a gallery picture not picked. */
    fun draftAt(page: Int): MediaDraftItem? =
        if (!browse) items.getOrNull(page)
        else gallery!!.getOrNull(page)?.uri?.let { u -> items.firstOrNull { it.originalUri == u || it.uri == u } }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = if (browse) galleryStart.coerceIn(0, pageCount - 1) else store.currentIndex.coerceIn(0, items.lastIndex),
        pageCount = { pageCount },
    )
    LaunchedEffect(pagerState.currentPage, items.size) {
        val i = if (browse) draftAt(pagerState.currentPage)?.let { d -> items.indexOfFirst { it.id == d.id } } ?: -1 else pagerState.currentPage
        if (i >= 0) store.setCurrent(i)
    }
    // A tool or Send on a picture not yet picked picks it first and goes on once it is in.
    var editWhenPicked by remember { mutableStateOf<android.net.Uri?>(null) }
    var sendWhenPicked by remember { mutableStateOf(false) }
    var filesWhenPicked by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // Gallery pictures not picked, each read once for its page (metadata only, no copy).
    val unpicked = remember { androidx.compose.runtime.mutableStateMapOf<android.net.Uri, MediaDraftItem>() }

    // The caption is stored in the set; locally only the caret and selection
    // are kept — that is input-field state, not attachment state.
    var caption by remember { mutableStateOf(TextFieldValue(store.caption.orEmpty())) }

    // The set was assembled by hand — silently dropping it on "back" is not
    // allowed. We ask once, only when leaving really drops it.
    var confirmDiscard by remember { mutableStateOf(false) }
    // Open editor: id of the edited attachment. The edit returns to the set.
    var editing by remember { mutableStateOf<String?>(null) }
    val requestClose: () -> Unit = { if (items.isEmpty() || !closeDiscards) onClose() else confirmDiscard = true }

    BackHandler { requestClose() }

    // The page always follows the set: a snapshot was added — it is the one
    // open, not the one that was there before the camera. The transition is
    // animated, and only while the pager is AT REST: otherwise it fights the
    // swipe gesture and the thumbnail tap, and the switch looks like a jerk.
    var lastCount by remember { mutableStateOf(items.size) }
    LaunchedEffect(store.currentIndex, items.size) {
        // Browsing, the page is the gallery's; the set does not move it.
        if (browse) return@LaunchedEffect
        val target = store.currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val countChanged = items.size != lastCount
        lastCount = items.size
        if (target == pagerState.currentPage) return@LaunchedEffect
        runCatching {
            // The list changed (added or removed) — transition WITHOUT animation.
            // An animation over a changed list breaks off halfway and leaves the pager
            // standing between pages: two half frames.
            if (countChanged) pagerState.scrollToPage(target)
            else if (!pagerState.isScrollInProgress) pagerState.animateScrollToPage(target)
        }
    }

    // The bars are colored by the app-window owner: this screen has no window
    // of its own, and painting them "on top" adds a second layer that later
    // drifts apart.
    DisposableEffect(Unit) {
        // One color for the whole screen, both system bars included: the canvas
        // must read as one piece, not as four different surfaces.
        SystemBars.statusColor = ScreenBackground
        // The bottom bar is see-through: the caption panel's glass runs on under it.
        val navOwner = Any()
        SystemBars.requestNav(navOwner, Color.Transparent)
        SystemBars.lightIcons = false
        onDispose {
            SystemBars.statusColor = null
            SystemBars.releaseNav(navOwner)
            SystemBars.lightIcons = null
        }
    }

    // While the frame is zoomed in, paging through the set is off: the same
    // horizontal gesture moves the picture. A page change resets the state.
    var zoomed by remember { mutableStateOf(false) }
    // Video seeking is the same horizontal gesture as paging: while the finger
    // is on the bar, the pager must stay quiet, or the bar "won't grab".
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) { zoomed = false; scrubbing = false }

    // A double tap on "Send" must not send the set twice.
    var sending by remember { mutableStateOf(false) }
    // The picture runs on under the caption panel, which shows it blurred.
    val previewGlass = remember { com.alphamessenger.ui.components.GlassState() }
    var barHpx by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        // The screen under the viewer is fully isolated: this layer catches
        // everything that misses our own elements, so there are no "through" taps
        // on the chat — not on the header, not on messages, not on the input row.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBackground)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            val current = draftAt(pagerState.currentPage)
            val galleryCurrent = if (browse) gallery!!.getOrNull(pagerState.currentPage) else null
            // A picked picture comes into the set a moment after the tap: then its edit or send goes on.
            LaunchedEffect(items.size, editWhenPicked, sendWhenPicked) {
                editWhenPicked?.let { u ->
                    items.firstOrNull { it.originalUri == u || it.uri == u }?.let { editing = it.id; editWhenPicked = null }
                }
                if (sendWhenPicked && items.isNotEmpty() && !sending) {
                    sendWhenPicked = false
                    if (filesWhenPicked) {
                        filesWhenPicked = false
                        items.toList().forEach { d -> store.update(d.id) { it.copy(sendAsFile = true) } }
                    }
                    sending = true
                    onSend(caption.text.trim().takeIf { it.isNotEmpty() })
                }
            }
            TopBar(
                title = if (browse) galleryTitle(galleryCurrent, pagerState.currentPage, pageCount) else pageTitle(items, pagerState.currentPage),
                subtitle = subtitle,
                onBack = requestClose,
                onRemove = { current?.let { store.removeById(it.id) } },
                // The bar does not know what tools exist: it shows the ones fitting the
                // current attachment. The list lives in PreviewToolbox.
                tools = if (browse && current == null) PreviewToolbox.forGallery(galleryCurrent) else PreviewToolbox.forItem(current),
                onTool = { tool ->
                    val item = current
                    if (item != null) {
                        tool.action?.invoke(store, item)
                        if (tool.opens != null) editing = item.id
                    } else if (galleryCurrent != null) {
                        onGalleryToggle?.invoke(galleryCurrent)
                        if (tool.opens != null) editWhenPicked = galleryCurrent.uri
                    }
                },
                // Browsing: the circle picks this picture into the set, with its number.
                pick = if (browse && galleryCurrent != null) ({
                    PickCircle(order = current?.let { d -> items.indexOfFirst { it.id == d.id } + 1 } ?: 0, colors = colors) {
                        onGalleryToggle?.invoke(galleryCurrent)
                    }
                }) else null,
                quality = (current ?: galleryCurrent?.let { unpicked[it.uri] })
                    ?.takeIf { !it.sendAsFile && (it.kind == MediaDraftItem.Kind.VIDEO || it.kind == MediaDraftItem.Kind.IMAGE) }
                    ?.let { v -> { MediaQualityButton(v) } },
            )
            // Length, resolution, size of this one; the set's size.
            (current ?: galleryCurrent?.let { unpicked[it.uri] })?.let { MediaInfoChip(it, items, Modifier.padding(bottom = 6.dp)) }

            val edgeBandPx = with(LocalDensity.current) { 200.dp.toPx() }
            HorizontalPager(
                state = pagerState,
                // clipToBounds: a zoomed frame must not crawl under the top bar — the
                // "back" and "remove" buttons and the title must stay readable at any
                // zoom.
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .layout { m, cs ->
                        val extra = barHpx
                        val pl = m.measure(cs.copy(minHeight = cs.maxHeight + extra, maxHeight = cs.maxHeight + extra))
                        layout(cs.maxWidth, cs.maxHeight) { pl.place(0, 0) }
                    }
                    // An edge swipe across the picture turns the page instead of going Back.
                    // Android keeps such a band to 200dp, so it sits at the picture's middle.
                    .systemGestureExclusion { c ->
                        val h = c.size.height.toFloat()
                        androidx.compose.ui.geometry.Rect(0f, (h - edgeBandPx) / 2f, c.size.width.toFloat(), (h + edgeBandPx) / 2f)
                    }
                    .clipToBounds()
                    .glassSource(previewGlass),
                // No gap between pages: while paging, the neighboring frame's edge peeked
                // through it — that was the "flicker at the sides".
                pageSpacing = 0.dp,
                // While the seek bar is being dragged or the zoomed frame moved, the
                // horizontal gesture belongs to them, not to paging. A single item has
                // nowhere to page — otherwise the frame "dangles" from empty dragging.
                userScrollEnabled = pageCount > 1 && !zoomed && !scrubbing,
                key = if (browse) ({ page: Int -> gallery!![page].uri.toString() }) else null,
            ) { page ->
                // One page, whether its picture is picked yet or not: picking only hands it
                // another item. Two branches would tear the page down under an open editor.
                val g = if (browse) gallery!!.getOrNull(page) else null
                if (g != null) LaunchedEffect(g.uri) {
                    if (unpicked[g.uri] == null) com.alphamessenger.ui.media.draft.MediaDraftFactory
                        .fromUri(ctx, g.uri, MediaDraftItem.Source.GALLERY).getOrNull()?.let { unpicked[g.uri] = it }
                }
                val shown = draftAt(page) ?: g?.let { unpicked[it.uri] }
                if (shown != null) {
                    PreviewPage(
                        item = shown,
                        isActive = page == pagerState.currentPage,
                        colors = colors,
                        onZoomChange = { if (page == pagerState.currentPage) zoomed = it },
                        onScrubbingChange = { if (page == pagerState.currentPage) scrubbing = it },
                        controlsInset = with(LocalDensity.current) { barHpx.toDp() },
                    )
                } else if (g != null) GalleryPlaceholder(g)
            }

            val sendTap: () -> Unit = {
                if (!sending) {
                    if (items.isEmpty() && galleryCurrent != null) {
                        // Nothing picked: the picture in view goes.
                        onGalleryToggle?.invoke(galleryCurrent)
                        sendWhenPicked = true
                    } else {
                        sending = true
                        onSend(caption.text.trim().takeIf { it.isNotEmpty() })
                    }
                }
            }
            BottomBar(
                glass = previewGlass,
                sendsCurrent = browse && galleryCurrent != null,
                onBarHeight = { barHpx = it },
                items = items,
                currentPage = if (browse) current?.let { d -> items.indexOfFirst { it.id == d.id } } ?: -1 else pagerState.currentPage,
                caption = caption,
                colors = colors,
                sending = sending,
                onAddMedia = onAddMedia,
                onCamera = onCamera,
                timer = timer,
                onCaptionChange = {
                    caption = it
                    store.setCaption(it.text)
                },
                onPick = { index ->
                    // Browsing, a thumbnail leads to its picture's place in the gallery.
                    val target = if (!browse) index else items.getOrNull(index)?.let { d ->
                        gallery!!.indexOfFirst { it.uri == d.originalUri || it.uri == d.uri }
                    } ?: -1
                    if (target >= 0) scope.launch { pagerState.animateScrollToPage(target) }
                },
                onMove = { from, to -> store.move(from, to) },
                onSend = sendTap,
                onSendSilent = onArmSilent?.let { arm -> { if (!sending) { arm(); sendTap() } } },
                onSendLongPress = onScheduleSend?.let { cb -> { cb(caption.text.trim().takeIf { it.isNotEmpty() }) } },
                // The originals go as files: no compression, no edits.
                onSendUncompressed = {
                    if (!sending) {
                        filesWhenPicked = true
                        if (items.isEmpty() && galleryCurrent != null) {
                            onGalleryToggle?.invoke(galleryCurrent)
                            sendWhenPicked = true
                        } else sendWhenPicked = true
                    }
                },
            )
        }

        // Photo editor. Edits arrive as ONE commit — on "Done".
        // "Back" and "Cancel" change nothing in the set at all.
        // Its own window: a composition apart from the viewer's, so what changes
        // under it (the page being picked, the caption field) never shares a tree
        // with the editor's own layers. Back stays the editor's (crop -> adjust).
        editing?.let { id ->
            val target = items.firstOrNull { it.id == id }
            if (target == null) editing = null
            else androidx.compose.ui.window.Dialog(
                onDismissRequest = { editing = null },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = true,
                    decorFitsSystemWindows = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
            ) {
            com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = false)
            if (target.kind == MediaDraftItem.Kind.VIDEO) {
                com.alphamessenger.ui.media.editor.VideoEditorScreen(
                    item = target,
                    colors = colors,
                    onCancel = { editing = null },
                    onApply = { trim ->
                        store.update(id) {
                            com.alphamessenger.ui.media.editor.VideoTrimController.applyTo(it, trim)
                        }
                        // Preparation is NOT started here. It used to make sense: sending waited
                        // for the finished file, and work started early shortened the wait. Now the
                        // message appears immediately and the send queue does the preparing — a
                        // second job over the same clip would only heat the phone.
                        editing = null
                    },
                )
            } else {
                com.alphamessenger.ui.media.editor.PhotoEditorScreen(
                    item = target,
                    colors = colors,
                    onCancel = { editing = null },
                    onApply = { edited, photo ->
                        // A crop comes back as its own file: the draft takes it, and the
                        // rest of the edits apply to it.
                        store.update(id) { cur ->
                            val base = if (photo.uri == cur.uri) cur else cur.copy(
                                uri = photo.uri,
                                originalUri = photo.originalUri,
                                mimeType = photo.mimeType,
                                sizeBytes = photo.sizeBytes,
                                exifRotationDegrees = photo.exifRotationDegrees,
                                exifFlip = photo.exifFlip,
                                deleteAfterUse = cur.deleteAfterUse || photo.deleteAfterUse,
                            )
                            edited.applyTo(base)
                        }
                        editing = null
                    },
                )
            }
            }
        }
        if (confirmDiscard) {
            DiscardDialog(
                count = items.size,
                colors = colors,
                onKeep = { confirmDiscard = false },
                onDiscard = { confirmDiscard = false; onClose() },
            )
        }
    }
}

/** Ask before dropping the assembled set. */
@Composable
private fun DiscardDialog(
    count: Int,
    colors: AlphaColorScheme,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.preview_discard_title), color = colors.textPrimary) },
        text = {
            Text(
                pluralStringResource(R.plurals.preview_remove_attachments, count, count),
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.common_delete), color = Color(0xFFEB5757))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onKeep) {
                Text(stringResource(R.string.preview_stay), color = colors.accent)
            }
        },
        containerColor = colors.bgSecondary,
    )
}

/** Screen background — dark, so the frame reads, but not a black hole. */
private val ScreenBackground = Color(0xFF0D0E11)

/** The review's own background while the set is read in — the chat is never seen behind it. */
@Composable
private fun Preparing(colors: AlphaColorScheme, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun pageTitle(items: List<MediaDraftItem>, page: Int): String {
    val item = items.getOrNull(page) ?: return ""
    val kind = when {
        item.isDocument && item.kind == MediaDraftItem.Kind.AUDIO -> stringResource(R.string.preview_audio)
        item.isDocument -> stringResource(R.string.preview_file)
        item.isVideo -> stringResource(R.string.media_kind_video)
        else -> stringResource(R.string.preview_photo)
    }
    return if (items.size > 1) stringResource(R.string.preview_page_of, kind, page + 1, items.size) else kind
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    tools: List<PreviewTool>,
    onTool: (PreviewTool) -> Unit,
    /** Browsing the gallery: the pick circle in place of "remove". */
    pick: (@Composable () -> Unit)? = null,
    /** The quality choice of a photo or video, left of the pencil. */
    quality: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(onBack) { Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = Color.White) }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                    textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
        // Quality first, left of the pencil (as people expect it).
        if (quality != null) { quality(); Spacer(Modifier.width(6.dp)) }
        // Buttons for as many as fit, the rest under "⋮": the row must not grow
        // together with the tool list.
        tools.take(PreviewToolbox.VISIBLE_TOOLS).forEach { tool ->
            GlassButton({ onTool(tool) }) { Icon(tool.icon, stringResource(tool.labelRes), tint = Color.White) }
            Spacer(Modifier.width(6.dp))
        }
        val overflow = tools.drop(PreviewToolbox.VISIBLE_TOOLS)
        if (overflow.isNotEmpty()) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                GlassButton({ menuOpen = true }) {
                    Icon(AlphaIcons.Outlined.MoreVert, stringResource(R.string.common_more), tint = Color.White)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    overflow.forEach { tool ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(tool.labelRes)) },
                            leadingIcon = { Icon(tool.icon, null) },
                            onClick = { menuOpen = false; onTool(tool) },
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        if (pick != null) pick()
        else GlassButton(onRemove) { Icon(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.preview_remove), tint = Color.White) }
    }
}

/** Round translucent button: reads on both a light and a dark frame. */
@Composable
private fun GlassButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Bottom block: thumbnail strip, caption, send.
 *
 * `imePadding` works properly here because this is the app's window: the
 * block rises with the keyboard as a whole, the field does not slide under
 * it, the send button stays visible. `navigationBarsPadding` keeps it above
 * the system bar when there is no keyboard.
 */
@Composable
private fun BottomBar(
    /** The picture under the panel, blurred through it. */
    glass: com.alphamessenger.ui.components.GlassState,
    /** Nothing picked still sends: the picture in view goes (browsing the gallery). */
    sendsCurrent: Boolean = false,
    /** The panel's own height (without the keyboard / emoji room under it). */
    onBarHeight: (Int) -> Unit,
    items: List<MediaDraftItem>,
    currentPage: Int,
    caption: TextFieldValue,
    colors: AlphaColorScheme,
    sending: Boolean,
    onAddMedia: (() -> Unit)?,
    onCamera: (() -> Unit)?,
    timer: (@Composable () -> Unit)?,
    onCaptionChange: (TextFieldValue) -> Unit,
    onPick: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSend: () -> Unit,
    onSendLongPress: (() -> Unit)? = null,
    onSendUncompressed: (() -> Unit)? = null,
    onSendSilent: (() -> Unit)? = null,
) {
    // The emoji panel is the same as in chat: no separate emoji set, separate
    // "recents" or separate layout for the viewer.
    var emojiOpen by remember { mutableStateOf(false) }
    // The caption's platform field, while it is on screen: the keyboard is driven through it.
    val captionField = remember { object { var value: android.widget.EditText? = null } }

    // --- Keyboard and emoji panel take ONE and the same place ---
    // This used to be `imePadding`, and the switch looked like a jump: the
    // keyboard slid down, the input panel fell after it and immediately rose
    // back — already above the emoji panel. Now the bottom block's height is
    // the maximum of "what the keyboard takes now" and "what the emoji panel
    // will take". While one replaces the other, the maximum does not change
    // and the input panel just stands still.
    val density = LocalDensity.current
    // The keyboard by its slide's own progress (as in the chat), never the raw insets,
    // which overshoot on a quick reopen and make the panel bounce.
    val keyboard = remember { com.alphamessenger.ui.chat.v2.KeyboardMotion() }
    com.alphamessenger.ui.chat.v2.BindKeyboardMotion(keyboard)
    val imeBottomPx = keyboard.heightPx
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val imeHeight = with(density) { (imeBottomPx - navBottomPx).coerceAtLeast(0).toDp() }
    val imeUp = imeHeight > KEYBOARD_MIN_HEIGHT
    var afterKeyboard by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(imeHeight < 10.dp, afterKeyboard) {
        val action = afterKeyboard ?: return@LaunchedEffect
        if (imeHeight < 10.dp) { afterKeyboard = null; action() }
    }
    // The emoji panel is exactly as tall as this device's keyboard. Before
    // the keyboard is first shown we don't know that height — until then, a
    // sane default. We take the MAXIMUM ever seen, not the last value: while
    // the keyboard slides away, the inset passes through intermediate heights,
    // and "the last" is half a frame.
    var keyboardHeight by remember { mutableStateOf(EMOJI_PANEL_FALLBACK_HEIGHT) }
    LaunchedEffect(imeHeight) { if (imeHeight > keyboardHeight) keyboardHeight = imeHeight }

    // Back to the keyboard: the emoji panel is held until the keyboard has
    // risen to its place, otherwise an empty gap opens between them again.
    var awaitingKeyboard by remember { mutableStateOf(false) }
    // The emoji panel holds its room until the keyboard has risen all the way (it shows
    // under the rising keyboard): let go earlier and the caption dips with a half-risen
    // keyboard, then climbs back. A keyboard that stops short is let go once it rests.
    LaunchedEffect(imeHeight, awaitingKeyboard) {
        if (!awaitingKeyboard || !imeUp) return@LaunchedEffect
        if (imeHeight < keyboardHeight - 4.dp) kotlinx.coroutines.delay(250)
        emojiOpen = false; awaitingKeyboard = false
    }
    val switchToKeyboard: () -> Unit = {
        awaitingKeyboard = true
        captionField.value?.focusAndShowKeyboard()
    }
    // The emoji panel opens FIRST and only then the keyboard hides — the
    // space under it is already taken, so the input panel has nowhere to drop.
    LaunchedEffect(emojiOpen) { if (emojiOpen) captionField.value?.hideKeyboard() }
    BackHandler(enabled = emojiOpen) { emojiOpen = false }

    val bottomSpace = maxOf(imeHeight, if (emojiOpen) keyboardHeight else 0.dp)

    val bottomSpacePx = with(androidx.compose.ui.platform.LocalDensity.current) { bottomSpace.roundToPx() }
    Column(
        modifier = Modifier.fillMaxWidth()
            // A touch anywhere on the panel stays here: no zoom or swipe of the picture behind.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            // Bottom panel — frosted glass over the picture, as the chat's input.
            .glassPanel(
                glass, androidx.compose.ui.graphics.RectangleShape,
                tint = colors.bgSecondary.copy(alpha = 0.6f), backdrop = ScreenBackground,
            )
            .onSizeChanged { onBarHeight((it.height - bottomSpacePx).coerceAtLeast(0)) }
            .navigationBarsPadding()
            .padding(top = 10.dp),
    ) {
        if (items.size > 1) {
            ThumbnailStrip(
                items = items,
                currentPage = currentPage,
                colors = colors,
                onPick = onPick,
                onMove = onMove,
            )
            Spacer(Modifier.height(10.dp))
        }

        // Length limit is the same as for a plain chat message
        // (`MAX_MESSAGE_CHARS`), and the same remaining counter: the caption goes
        // out as that same message, so the rule is the same.
        val remaining = com.alphamessenger.ui.chat.MAX_MESSAGE_CHARS - caption.text.length
        val overLimit = remaining < 0
        // Resolved here (not inside the semantics lambda): no composable scope there.
        val sendDescription = pluralStringResource(R.plurals.send_with_attachments, items.size, items.size)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Caption field with its buttons inside: emoji on the left. The buttons
            // sit INSIDE the field, not on a separate strip, or they drift away
            // from the text under an open keyboard.
            Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.bgTertiary)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // One button for two states: panel open — it offers to bring the keyboard
                // back. The chat input row behaves the same way.
                FieldButton(
                    icon = if (emojiOpen) AlphaIcons.Outlined.Keyboard else AlphaIcons.Outlined.SentimentSatisfied,
                    description = if (emojiOpen) stringResource(R.string.preview_keyboard) else stringResource(R.string.preview_emoji),
                    tint = if (emojiOpen) colors.accent else colors.textMuted,
                    onClick = { if (emojiOpen) switchToKeyboard() else emojiOpen = true },
                )
                // The same platform field as the chat's: selection, its menu and scrolling are
                // the system's. Five lines tall at most, then it scrolls.
                com.alphamessenger.ui.chat.v2.PlatformTextField(
                    value = caption,
                    onEdit = onCaptionChange,
                    onSelect = { onCaptionChange(caption.copy(selection = it)) },
                    hint = stringResource(R.string.preview_add_caption),
                    colors = colors,
                    textSizeSp = 16f,
                    lineHeightSp = 22f,
                    minHeight = 44.dp,
                    maxHeight = (5 * 22 + 22).dp,
                    padding = com.alphamessenger.ui.chat.v2.FieldPadding(0.dp, 10.dp, 0.dp, 12.dp),
                    modifier = Modifier.weight(1f),
                    // A tap on the field also returns the keyboard, likewise without a gap:
                    // the emoji panel leaves once the keyboard is in place.
                    onTapped = { if (emojiOpen) awaitingKeyboard = true },
                    onField = { captionField.value = it },
                    // A caption is short by rule; the counter tells where its end is.
                    scrollBar = false,
                )
                if (onAddMedia != null) {
                    FieldButton(
                        icon = AlphaIcons.Outlined.PhotoLibrary,
                        description = stringResource(R.string.preview_add_media),
                        tint = colors.textMuted,
                        onClick = onAddMedia,
                    )
                }
                if (onCamera != null) {
                    FieldButton(
                        icon = AlphaIcons.Outlined.PhotoCamera,
                        description = stringResource(R.string.preview_open_camera),
                        tint = colors.textMuted,
                        onClick = onCamera,
                    )
                }
                if (timer != null) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { timer() }
                }
            }
            // Near the limit the count sits in the corner, leaving the text its full width.
            if (remaining <= 200) {
                Text(
                    "$remaining",
                    color = if (overLimit) Color(0xFFEB5757) else colors.textMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 20.dp),
                )
            }
            }
            Spacer(Modifier.width(10.dp))
            // A hold on Send: without compression, or later.
            var optionsOpen by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape)
                    .background(
                        when {
                            overLimit -> colors.textMuted
                            sending -> colors.accent.copy(alpha = 0.4f)
                            else -> colors.accent
                        }
                    )
                    .combinedClickable(
                        enabled = !sending && !overLimit && (items.isNotEmpty() || sendsCurrent),
                        onClick = onSend,
                        onLongClick = { optionsOpen = true },
                    )
                    .semantics {
                        contentDescription = sendDescription
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AlphaIcons.AutoMirrored.Outlined.Send, null,
                    tint = Color.White, modifier = Modifier.size(22.dp),
                )
                com.alphamessenger.ui.chat.v2.SendOptionsMenu(
                    open = optionsOpen,
                    colors = colors,
                    onDismiss = { optionsOpen = false },
                    onUncompressed = onSendUncompressed,
                    onSilent = onSendSilent,
                    // The time sheet comes once the keyboard has gone, not together with it.
                    onLater = onSendLongPress?.let { later -> {
                        if (!imeUp) later() else { afterKeyboard = later; captionField.value?.hideKeyboard() }
                    } },
                )
                if (items.size > 1) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd)
                            .size(20.dp).clip(CircleShape).background(colors.bgSecondary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            items.size.toString(),
                            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // The space for the keyboard OR for the emoji panel is the same one, so
        // the height is one. The emoji panel is the same as in chat (a separate one
        // it as `emoji_stub` below the strip): no own emoji or own "recents" for
        // the viewer.
        Box(modifier = Modifier.fillMaxWidth().height(bottomSpace)) {
            if (emojiOpen) {
                com.alphamessenger.ui.chat.v2.EmojiPanelV2(
                    modifier = Modifier.fillMaxSize(),
                    colors = colors,
                    onPick = { emoji -> onCaptionChange(caption.insert(emoji)) },
                    onBackspace = { onCaptionChange(caption.backspace()) },
                )
            }
        }
    }
}

/** Until the keyboard has been shown once, its height is unknown — use a sane default. */
private val EMOJI_PANEL_FALLBACK_HEIGHT = 280.dp

/** Below this a "keyboard" is an animation or just the system bar. */
private val KEYBOARD_MIN_HEIGHT = 120.dp

/** Button inside the caption field: its own touch area, no background. */
@Composable
private fun FieldButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/**
 * Thumbnail strip: the active one scrolls itself into view, items can be
 * dragged. It has no list of its own — order changes in the set, and the
 * set is what goes to sending, so "one thing on screen, another sent" is
 * impossible.
 */
@Composable
private fun ThumbnailStrip(
    items: List<MediaDraftItem>,
    currentPage: Int,
    colors: AlphaColorScheme,
    onPick: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentPage, items.size) {
        if (currentPage in items.indices) runCatching { listState.animateScrollToItem(currentPage) }
    }

    // Dragging: the target position is computed from the distance traveled in
    // thumbnail-width steps — without touching screen coordinates.
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { (THUMB_SIZE + THUMB_GAP).toPx() }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(THUMB_GAP),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val dragging = draggingIndex == index
            Thumb(
                item = item,
                active = index == currentPage,
                colors = colors,
                modifier = Modifier
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffsetPx
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                    }
                    .pointerInput(item.id, items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { draggingIndex = index; dragOffsetPx = 0f },
                            onDragCancel = { draggingIndex = null; dragOffsetPx = 0f },
                            onDragEnd = {
                                val from = draggingIndex
                                val shift = (dragOffsetPx / stepPx).roundToInt()
                                draggingIndex = null
                                dragOffsetPx = 0f
                                if (from != null && shift != 0) {
                                    val to = (from + shift).coerceIn(0, items.lastIndex)
                                    if (to != from) onMove(from, to)
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffsetPx += amount.x
                            },
                        )
                    },
                onClick = { onPick(index) },
            )
        }
    }
}

private val THUMB_SIZE = 54.dp
private val THUMB_GAP = 8.dp

/** A strip thumbnail. Reads data from the set, holds no list of its own. */
@Composable
private fun Thumb(
    item: MediaDraftItem,
    active: Boolean,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier.size(THUMB_SIZE).clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .then(if (active) Modifier.border(2.dp, colors.accent, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            item.isDocument -> {
                val ext = (item.filename ?: "").substringAfterLast('.', "").uppercase().take(4)
                Box(
                    modifier = Modifier.fillMaxSize().background(fileTint(ext)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ext.ifBlank { "FILE" }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            item.isVideo -> {
                val context = LocalContext.current
                var frame by remember(item.uri, item.trimStartMs) {
                    mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
                }
                LaunchedEffect(item.uri, item.trimStartMs) {
                    frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        videoFrame(context, item.uri, item.trimStartMs)
                    }
                }
                frame?.let {
                    androidx.compose.foundation.Image(
                        it, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(AlphaIcons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // The thumbnail is the same image and the same transform contract as the
            // page and the send. Otherwise after a crop the strip would keep the old
            // frame, and the person would see in the set something other than what
            // gets sent.
            else -> com.alphamessenger.ui.media.editor.EditorImage(
                uri = item.uri,
                transform = com.alphamessenger.ui.media.editor.EditorTransform.of(item),
                modifier = Modifier.fillMaxSize(),
                maxEdge = 320,
                markup = androidx.compose.runtime.remember(item.markup) { com.alphamessenger.ui.media.editor.PhotoMarkup.fromJson(item.markup) },
            )
        }
    }
}

/** The pick circle of a gallery picture: empty, or filled with its number in the set. */
@Composable
private fun PickCircle(order: Int, colors: AlphaColorScheme, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(28.dp)
                .background(if (order > 0) colors.accent else Color.Black.copy(alpha = 0.28f), CircleShape)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (order > 0) Text(
                "$order", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                    lineHeight = 13.sp,
                    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                        androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun galleryTitle(item: com.alphamessenger.ui.chat.GalleryItem?, page: Int, count: Int): String {
    val kind = if (item?.isVideo == true) stringResource(R.string.media_kind_video) else stringResource(R.string.preview_photo)
    return if (count > 1) stringResource(R.string.preview_page_of, kind, page + 1, count) else kind
}

/** A gallery picture's page while its item is being read: the cell's thumbnail. */
@Composable
private fun GalleryPlaceholder(item: com.alphamessenger.ui.chat.GalleryItem) {
    val ctx = LocalContext.current
    val key = item.uri.toString()
    var image by remember(key) { mutableStateOf(com.alphamessenger.ui.chat.v2.galleryThumbCache.get(key)) }
    LaunchedEffect(key) {
        if (image == null) image = kotlinx.coroutines.withContext(com.alphamessenger.ui.chat.v2.thumbLoader) {
            com.alphamessenger.ui.chat.v2.galleryThumb(ctx, item.uri, item.isVideo)
        }
    }
    image?.let { androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
}
