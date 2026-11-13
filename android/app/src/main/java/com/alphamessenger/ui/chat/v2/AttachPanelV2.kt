@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.alphamessenger.ui.chat.v2

import com.alphamessenger.ui.icons.AlphaIcons
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.alphamessenger.ui.chat.GalleryItem
import com.alphamessenger.ui.chat.MAX_MESSAGE_CHARS
import androidx.compose.animation.core.animate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Attach panel V2 (from scratch) — classic.
 *
 * ## Drag
 *  - ANY position via continuous drag (20% → 100% of screen).
 *  - On release: spring to the NEAREST detent OR leave as-is — smooth, no
 *    hard 2-position snap.
 *  - Detents: 20%, 35%, 50%, 70%, 85%, 100%.
 *  - Drag updates [BottomSurfaceState.attachDragHeight] directly → the slot
 *    height follows 1:1 (the parent reads it via bottomPanelHeight).
 *
 * ## Caption INSIDE (per spec — one layout)
 *  - Media Grid (weight 1f) + Caption bar at the bottom — ONE Column, ONE
 *    layout. No separate caption outside the attach panel.
 *
 * ## Layout
 * ```
 * [grabber + folder dropdown + close]
 * [action row: Camera/Files/Music/Geo/Secret]
 * [folder dropdown (with mini thumbnails)]
 * [ media grid (weight 1f — fills the leftover) ]
 * [caption bar (when items selected)]
 * ```
 *
 * This is CONTENT ONLY — the height is owned by [BottomPanelV2] (the slot).
 * The attach panel fills its slot; the drag changes the SLOT height (via
 * state.attachDragHeight), not its own internal height.
 */
@Composable
fun AttachPanelV2(
    state: BottomSurfaceState,
    modifier: Modifier = Modifier,
    colors: AlphaColorScheme,
    // The selection lives in MediaDraftStore — the panel only displays it and
    // asks to toggle. It keeps no selected list of its own: two selection
    // states inevitably diverge.
    selectedCount: Int,
    orderOf: (GalleryItem) -> Int,
    onToggle: (GalleryItem) -> Unit,
    /** Tap on the picture itself: open it in the editor. */
    onOpenItem: (GalleryItem, List<GalleryItem>) -> Unit,
    caption: TextFieldValue,
    captionKeys: AttachCaptionKeys,
    onCaptionChange: (TextFieldValue) -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onMusic: () -> Unit,
    onSecretNote: () -> Unit,
    onLocation: () -> Unit,
    onClose: () -> Unit,
    onSend: () -> Unit,
    /** "Send later" in the hold menu: schedule the set. */
    onSendLongPress: (() -> Unit)? = null,
    /** "Send without compression" in the hold menu: the set goes as files. */
    onSendUncompressed: (() -> Unit)? = null,
    /** "Send without sound" in the hold menu. */
    onSendSilent: (() -> Unit)? = null,
    /** Beside Send in the caption bar: the view timer (null = not offered). */
    timer: (@Composable () -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val sh = state.screenHeightDp
    var folderDropdownOpen by remember { mutableStateOf(false) }
    var activeBucket by remember { mutableStateOf<MediaBucketV2?>(null) }

    val hasPermission = remember { mutableStateOf(hasGalleryPermission(ctx)) }
    var allItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var buckets by remember { mutableStateOf<List<MediaBucketV2>>(emptyList()) }
    val gridState = rememberLazyGridState()

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> hasPermission.value = result.values.any { it } }

    LaunchedEffect(hasPermission.value) {
        if (!hasPermission.value) return@LaunchedEffect
        val (imgs, vids) = withContext(Dispatchers.IO) { loadRawMediaV2(ctx) }
        allItems = (imgs + vids).sortedByDescending { it.dateAdded }.take(500)
        buckets = withContext(Dispatchers.IO) { loadBucketsV2(ctx) }
    }

    var filteredItems by remember { mutableStateOf<List<GalleryItem>?>(null) }
    LaunchedEffect(activeBucket, allItems) {
        filteredItems = if (activeBucket == null) null
        else withContext(Dispatchers.IO) { loadRecentMediaV2(ctx, bucketId = activeBucket?.id) }
    }
    val displayItems = filteredItems ?: allItems
    LaunchedEffect(activeBucket) { gridState.scrollToItem(0) }

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

 // Release → settle as messengers usually do: a flick moves to the NEXT detent in its
    // direction (a flick down from the lowest one closes); a slow release
    // glides to the nearest detent. `velocity` is in dp/s, positive = up.
    fun settleDrag(velocity: Float = 0f) {
        val current = state.attachDragHeight
        val anchors = state.attachAnchors().sortedBy { it.value }
        val flick = 500f
        val target = when {
            velocity > flick -> anchors.firstOrNull { it > current + 1.dp } ?: anchors.last()
            velocity < -flick -> anchors.lastOrNull { it < current - 1.dp }
            current < state.closeThreshold -> null
            else -> anchors.minByOrNull { d -> abs((d - current).value) }
        }
        if (target == null) {
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            onClose()
            return
        }
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        scope.launch {
            animate(current.value, target.value, initialVelocity = velocity) { v, _ -> state.attachDragHeight = v.dp }
        }
    }

    fun isAtDetent(): Boolean =
        state.attachAnchors().any { abs((it - state.attachDragHeight).value) < 1f }

 // The gallery drives the panel first: dragging up grows the panel
    // to full height before the grid scrolls; dragging down at the top of the
    // grid shrinks it; the release settles with the fling velocity.
    val panelScroll = remember(state, density) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (available.y >= 0f || state.attachDragHeight >= state.maxPanelHeight) {
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                val before = state.attachDragHeight
                state.attachDragHeight = (before + with(density) { (-available.y).toDp() })
                    .coerceAtMost(state.maxPanelHeight)
                val used = with(density) { (state.attachDragHeight - before).toPx() }
                return androidx.compose.ui.geometry.Offset(0f, -used)
            }

            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (available.y <= 0f || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.Drag) {
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                val before = state.attachDragHeight
                state.attachDragHeight = (before - with(density) { available.y.toDp() })
                    .coerceAtLeast(state.minPanelHeight)
                val used = with(density) { (before - state.attachDragHeight).toPx() }
                return androidx.compose.ui.geometry.Offset(0f, used)
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (isAtDetent()) return androidx.compose.ui.unit.Velocity.Zero
                settleDrag(with(density) { -available.y.toDp().value })
                return available
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.bgSecondary)) {
        // --- Draggable header: grabber + folder dropdown + close ---
        Box(
            modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                // The header moves with the panel, so velocity is tracked on the
                // accumulated drag distance, not on the (moving) local position.
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                var travelled = 0f
                detectDragGestures(
                    onDragStart = { tracker.resetTracking(); travelled = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        travelled += dragAmount.y
                        tracker.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, travelled))
                        // Continuous 1:1 drag — moves the slot height directly.
                        val dDp = with(density) { -dragAmount.y.toDp() }
                        state.attachDragHeight = (state.attachDragHeight + dDp)
                            .coerceIn(state.minPanelHeight, state.maxPanelHeight)
                    },
                    onDragEnd = {
                        val vy = tracker.calculateVelocity().y
                        settleDrag(with(density) { -vy.toDp().value })
                    },
                    onDragCancel = { settleDrag() },
                )
            },
        ) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp, bottom = 8.dp)
                    .width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(colors.textMuted.copy(alpha = 0.4f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .clickable { folderDropdownOpen = !folderDropdownOpen }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            activeBucket?.name ?: stringResource(R.string.attach_all_photos), color = colors.textPrimary,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(AlphaIcons.Outlined.ArrowDropDown, stringResource(R.string.attach_folders), tint = colors.textMuted, modifier = Modifier.size(18.dp))
                    }
                }
                Box(
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(colors.bgTertiary).clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted, modifier = Modifier.size(16.dp)) }
            }
        }

        // --- Folder dropdown (with mini thumbnails) ---
        // Same inline dropdown as always — just capped in height, scrollable
        // up/down, with a slim scrollbar thumb on the right.
        AnimatedVisibility(visible = folderDropdownOpen) {
            val folderListState = rememberLazyListState()
            Box(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .background(colors.bgTertiary),
            ) {
                LazyColumn(
                    state = folderListState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                ) {
                    item(key = "bucket-all") {
                        FolderDropdownItem(stringResource(R.string.attach_all_photos), allItems.size, allItems.firstOrNull()?.uri, activeBucket == null, colors) { activeBucket = null; folderDropdownOpen = false }
                    }
                    lazyItems(buckets, key = { it.id }) { bucket ->
                        FolderDropdownItem(bucket.name, bucket.count, bucket.firstItemUri, activeBucket?.id == bucket.id, colors) { activeBucket = bucket; folderDropdownOpen = false }
                    }
                }
                FolderScrollbar(folderListState, colors)
            }
        }

        // --- Media grid (weight 1f — fills the leftover) ---
        if (!hasPermission.value) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.attach_permission_media), color = colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(colors.accent)
                        .clickable { permLauncher.launch(galleryPermissionsV2()) }.padding(horizontal = 22.dp, vertical = 11.dp),
                ) { Text(stringResource(R.string.perm_allow), color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    state = gridState, columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 3.dp, end = 3.dp, bottom = 3.dp),
                    modifier = Modifier.fillMaxSize().nestedScroll(panelScroll),
                ) {
                    gridItems(displayItems, key = { it.uri.toString() }, contentType = { "cell" }) { item ->
                        GalleryCellV2(item, orderOf(item), colors, onOpen = { onOpenItem(item, displayItems) }, onToggle = { onToggle(item) })
                    }
                }
            }
        }

        // --- Bottom: the kinds of attachment, or — once something is picked — its
        // caption and Send in the same place. ---
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.alphaBorder))
        AnimatedContent(
            targetState = selectedCount > 0,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 3 })
            },
            label = "attachBottom",
        ) { picked ->
            if (picked) {
                CaptionBarV2(colors = colors, caption = caption, count = selectedCount, keys = captionKeys, onCaptionChange = onCaptionChange, onSend = onSend, onSendLongPress = onSendLongPress, onSendUncompressed = onSendUncompressed, onSendSilent = onSendSilent, timer = timer)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().background(colors.bgSecondary).padding(horizontal = 4.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AttachAction(stringResource(R.string.perm_camera), AlphaIcons.Outlined.CameraAlt, Color(0xFF3D8BFD), colors, onCamera)
                    AttachAction(stringResource(R.string.media_kind_file), AlphaIcons.Outlined.Folder, Color(0xFF2FB36B), colors, onFile)
                    AttachAction(stringResource(R.string.media_kind_music), AlphaIcons.Outlined.MusicNote, Color(0xFFF2832C), colors, onMusic)
                    AttachAction(stringResource(R.string.attach_geo), AlphaIcons.Outlined.LocationOn, Color(0xFF22A6D6), colors, onLocation)
                    AttachAction(stringResource(R.string.poll_label), AlphaIcons.Outlined.Poll, Color(0xFFDDA21C), colors) { com.alphamessenger.ui.chat.poll.PollUi.compose = true }
                    AttachAction(stringResource(R.string.attach_whisper), AlphaIcons.Outlined.GraphicEq, Color(0xFF8B5CF6), colors, onSecretNote)
                }
            }
        }
    }
}

@Composable
private fun AttachAction(label: String, icon: ImageVector, tint: Color, colors: AlphaColorScheme, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(58.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Slim scrollbar thumb for the folder dropdown — shows position
 *  and size of the visible window; hidden when everything fits. */
@Composable
private fun BoxScope.FolderScrollbar(listState: LazyListState, colors: AlphaColorScheme) {
    val info = listState.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total > visible && visible > 0) {
        BoxWithConstraints(
            modifier = Modifier.matchParentSize().padding(vertical = 6.dp, horizontal = 3.dp),
        ) {
            val trackH = maxHeight
            val thumbH = (trackH * (visible.toFloat() / total)).coerceAtLeast(24.dp)
            val progress = listState.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (trackH - thumbH) * progress.coerceIn(0f, 1f))
                    .width(3.dp)
                    .height(thumbH)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(colors.textMuted.copy(alpha = 0.45f)),
            )
        }
    }
}

@Composable
private fun FolderDropdownItem(name: String, count: Int, thumbnailUri: Uri?, isSelected: Boolean, colors: AlphaColorScheme, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(colors.bgPrimary), contentAlignment = Alignment.Center) {
            if (thumbnailUri != null) {
                AsyncImage(model = thumbnailUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(AlphaIcons.Outlined.Folder, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(name, color = if (isSelected) colors.accent else colors.textPrimary, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text("$count", color = colors.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun GalleryCellV2(item: GalleryItem, order: Int, colors: AlphaColorScheme, onOpen: () -> Unit, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    // A picked picture eases back inside its cell, so the pick reads at a glance.
    val inset by androidx.compose.animation.core.animateFloatAsState(
        if (order > 0) 0.86f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f), label = "pick",
    )
    Box(
        modifier = Modifier.aspectRatio(1f).graphicsLayer { scaleX = inset; scaleY = inset }
            .clip(RoundedCornerShape(if (order > 0) 10.dp else 4.dp))
            .background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onOpen),
    ) {
        // The system's own small thumbnail (photo AND video), loaded off the main
        // thread, a few at a time, cancelled when the cell scrolls away, and kept in
        // a shared cache. Until it is here the cell shows a quiet placeholder.
        val key = item.uri.toString()
        var thumb by remember(key) { mutableStateOf(galleryThumbCache.get(key)) }
        LaunchedEffect(key) {
            if (thumb == null) thumb = withContext(thumbLoader) { galleryThumb(ctx, item.uri, item.isVideo) }
        }
        val shown = thumb
        if (shown == null) {
            Icon(
                if (item.isVideo) AlphaIcons.Outlined.Movie else AlphaIcons.Outlined.Image, null,
                tint = Color.White.copy(alpha = 0.28f),
                modifier = Modifier.align(Alignment.Center).size(26.dp),
            )
        } else {
            val appear = remember(key) { androidx.compose.animation.core.Animatable(if (galleryThumbCache.get(key) != null && thumb === shown) 1f else 0f) }
            LaunchedEffect(shown) { appear.animateTo(1f, androidx.compose.animation.core.tween(140)) }
            Image(
                shown, null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = appear.value },
            )
        }
        if (item.isVideo) {
            Box(
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 1.dp),
            ) { Text(fmtDurationV2(item.durationMs), color = Color.White, fontSize = 9.sp) }
        }
        if (order > 0) Box(modifier = Modifier.matchParentSize().background(colors.accent.copy(alpha = 0.28f)))
        // The circle selects; its touch area is larger than the drawn circle.
        Box(
            modifier = Modifier.align(Alignment.TopEnd).size(44.dp).clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape)
                    .background(if (order > 0) colors.accent else Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (order > 0) Text("$order", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CaptionBarV2(
    colors: AlphaColorScheme,
    caption: TextFieldValue,
    count: Int,
    keys: AttachCaptionKeys,
    onCaptionChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendLongPress: (() -> Unit)?,
    onSendUncompressed: (() -> Unit)?,
    onSendSilent: (() -> Unit)?,
    timer: (@Composable () -> Unit)?,
) {
    val remaining = MAX_MESSAGE_CHARS - caption.text.length
    val overLimit = remaining < 0
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.bgSecondary).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // The field takes the width; emoji on its left, the view timer on its right,
        // and near the limit the count in its top corner — the same field as the review's.
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                    .background(colors.bgTertiary).padding(start = 2.dp, end = if (timer != null) 2.dp else 14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClick = keys.onEmoji),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (keys.emojiOpen) AlphaIcons.Outlined.Keyboard else com.alphamessenger.ui.components.AlphaIcons.Expressions,
                        stringResource(if (keys.emojiOpen) R.string.preview_keyboard else R.string.chat_emoji),
                        tint = if (keys.emojiOpen) colors.accent else colors.textMuted, modifier = Modifier.size(24.dp),
                    )
                }
                PlatformTextField(
                    value = caption,
                    onEdit = onCaptionChange,
                    onSelect = { onCaptionChange(caption.copy(selection = it)) },
                    hint = stringResource(R.string.attach_add_caption),
                    colors = colors,
                    textSizeSp = 15f,
                    lineHeightSp = 20f,
                    minHeight = 48.dp,
                    maxHeight = (5 * 20 + 26).dp,
                    padding = FieldPadding(2.dp, 13.dp, 4.dp, 13.dp),
                    modifier = Modifier.weight(1f),
                    scrollBar = false,
                    onTapped = keys.onFieldTapped,
                    onField = keys.onField,
                )
                if (timer != null) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { timer() }
            }
            if (remaining <= 200) Text(
                "$remaining",
                color = if (overLimit) Color(0xFFEB5757) else colors.textMuted, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        // Send carries how many go; a hold on it offers sending without compression or later.
        var optionsOpen by remember { mutableStateOf(false) }
        Box(Modifier.size(48.dp)) {
            SendOptionsMenu(
                open = optionsOpen,
                colors = colors,
                onDismiss = { optionsOpen = false },
                onUncompressed = onSendUncompressed,
                // The time sheet comes once the keyboard has gone, not together with it.
                onLater = onSendLongPress?.let { later -> { keys.afterKeyboard(later) } },
                onSilent = onSendSilent,
            )
            Box(
                modifier = Modifier.matchParentSize().clip(CircleShape).background(if (overLimit) colors.textMuted else colors.accent)
                    .combinedClickable(enabled = !overLimit, onClick = onSend, onLongClick = { optionsOpen = true }),
                contentAlignment = Alignment.Center,
            ) { Icon(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.common_send), tint = Color.White, modifier = Modifier.size(21.dp)) }
            // Two filled shapes, not a clipped border: the ring of the panel's colour
            // around the badge stays smooth at any density.
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp)
                    .background(colors.bgSecondary, RoundedCornerShape(50)).padding(2.dp),
            ) {
                Box(
                    Modifier.height(20.dp).widthIn(min = 20.dp).background(Color.White, RoundedCornerShape(50)).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // No font padding, the line centred on its own height: the digit sits in the middle.
                    Text(
                        "$count", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 12.sp,
                            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

/** What the sheet hands its caption: the emoji panel switch and the field's keyboard hooks. */
class AttachCaptionKeys(
    val emojiOpen: Boolean,
    val onEmoji: () -> Unit,
    val onFieldTapped: () -> Unit,
    val onField: (android.widget.EditText?) -> Unit,
    /** Runs [action] once the keyboard is down, putting it down first if it is up. */
    val afterKeyboard: (action: () -> Unit) -> Unit,
)

// --- Gallery loading helpers ---

data class MediaBucketV2(val id: String, val name: String, val count: Int, val firstItemUri: Uri? = null)

private fun galleryPermissionsV2(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

internal fun hasGalleryPermission(ctx: Context): Boolean =
    galleryPermissionsV2().any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

private fun loadRawMediaV2(ctx: Context): Pair<List<GalleryItem>, List<GalleryItem>> {
    val imgs = ArrayList<GalleryItem>(); val vids = ArrayList<GalleryItem>()
    val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    runCatching {
        ctx.contentResolver.query(imgUri, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID); val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) imgs.add(GalleryItem(ContentUris.withAppendedId(imgUri, c.getLong(idCol)), false, 0L, c.getLong(dateCol)))
        }
    }
    val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    runCatching {
        ctx.contentResolver.query(vidUri, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION), null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID); val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED); val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) vids.add(GalleryItem(ContentUris.withAppendedId(vidUri, c.getLong(idCol)), true, c.getLong(durCol), c.getLong(dateCol)))
        }
    }
    return imgs to vids
}

internal fun loadRecentMediaV2(ctx: Context, bucketId: String? = null, limit: Int = 500): List<GalleryItem> {
    data class Raw(val uri: Uri, val isVideo: Boolean, val durationMs: Long, val dateAdded: Long)
    val raw = ArrayList<Raw>(limit * 2)
    val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    runCatching {
        val sel = if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID}=?" else null
        val args = if (bucketId != null) arrayOf(bucketId) else null
        ctx.contentResolver.query(imgUri, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED), sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID); val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) raw.add(Raw(ContentUris.withAppendedId(imgUri, c.getLong(idCol)), false, 0L, c.getLong(dateCol)))
        }
    }
    val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    runCatching {
        val sel = if (bucketId != null) "${MediaStore.Video.Media.BUCKET_ID}=?" else null
        val args = if (bucketId != null) arrayOf(bucketId) else null
        ctx.contentResolver.query(vidUri, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION), sel, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID); val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED); val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) raw.add(Raw(ContentUris.withAppendedId(vidUri, c.getLong(idCol)), true, c.getLong(durCol), c.getLong(dateCol)))
        }
    }
    return raw.sortedByDescending { it.dateAdded }.take(limit).map { GalleryItem(it.uri, it.isVideo, it.durationMs, it.dateAdded) }
}

private fun loadBucketsV2(ctx: Context): List<MediaBucketV2> {
    val map = LinkedHashMap<String, Triple<String, Int, Uri?>>()
    val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    runCatching {
        ctx.contentResolver.query(imgUri, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID); val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME); val itemCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val bid = c.getString(idCol) ?: continue; val name = c.getString(nameCol) ?: ctx.getString(R.string.preview_photo)
                val itemUri = ContentUris.withAppendedId(imgUri, c.getLong(itemCol))
                val prev = map[bid]
                if (prev == null) map[bid] = Triple(name, 1, itemUri) else map[bid] = Triple(prev.first, prev.second + 1, prev.third)
            }
        }
    }
    val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    runCatching {
        ctx.contentResolver.query(vidUri, arrayOf(MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media._ID), null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID); val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME); val itemCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (c.moveToNext()) {
                val bid = c.getString(idCol) ?: continue; val name = c.getString(nameCol) ?: ctx.getString(R.string.media_kind_video)
                val itemUri = ContentUris.withAppendedId(vidUri, c.getLong(itemCol))
                val prev = map[bid]
                if (prev == null) map[bid] = Triple(name, 1, itemUri) else map[bid] = Triple(prev.first, prev.second + 1, prev.third ?: itemUri)
            }
        }
    }
    return map.entries.map { (id, v) -> MediaBucketV2(id, v.first, v.second, v.third) }.sortedByDescending { it.count }
}

// Gallery thumbnails (photo and video) by uri, bounded by bytes: scrolling the
// grid back and forth shows them at once, and a huge camera roll can't grow it.
internal val galleryThumbCache = object : LruCache<String, ImageBitmap>(20 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

// A few thumbnails at a time: a fast fling must not queue hundreds of loads
// that compete with the ones on screen (the cancelled ones never start).
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal val thumbLoader = Dispatchers.IO.limitedParallelism(4)

/** A cell-sized thumbnail: the system's own (already small, disk-cached), else a
 *  sampled decode / a first frame on old Android. */
internal fun galleryThumb(ctx: Context, uri: Uri, isVideo: Boolean): ImageBitmap? {
    val key = uri.toString()
    galleryThumbCache.get(key)?.let { return it }
    val bmp: Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        } else if (isVideo) {
            val r = MediaMetadataRetriever(); r.setDataSource(ctx, uri)
            val b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC); r.release(); b
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()
    val img = bmp?.asImageBitmap()
    if (img != null) galleryThumbCache.put(key, img)
    return img
}

internal fun fmtDurationV2(ms: Long): String {
    if (ms <= 0) return "0:00"; val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60)
}
