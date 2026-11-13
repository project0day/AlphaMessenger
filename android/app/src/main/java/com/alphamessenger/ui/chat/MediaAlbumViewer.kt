package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.chat.bubbles.BubbleTokens
import com.alphamessenger.ui.chat.bubbles.bubbleShell
import com.alphamessenger.ui.chat.media.toMediaItem

import com.alphamessenger.transfer.downloadOf
import com.alphamessenger.transfer.uploadOf
import com.alphamessenger.media.toOverlayState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photo/video album bubble. Lays the head message's [items] out
 * as a grid: 1 → single, 2–4 → tight grid, 5+ → grid with a "+N" overlay on the
 * last visible tile. Tapping any tile opens a fullscreen swipeable viewer
 * starting at that item. UI-only — each item is still an independent message.
 */
/** Process-level cache of the computed display width for single-item album
 *  bubbles, keyed by message id. Without this the width is recomputed after the
 *  thumbnail re-decodes every time the bubble scrolls back into view, causing a
 *  width jump / layout thrash (visible as scroll artifacts). */
private val singleTileWidthCache = HashMap<String, androidx.compose.ui.unit.Dp>()
// Deterministic per-item display size (w,h) for a single-photo album tile —
// computed once from the poster's aspect (or a fixed fallback) so a recycle
// never re-measures the tile and the list can't jump. Keyed by item id.
private val singleTileSizeCache =
    HashMap<String, Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>>()

/** A single album tile's display size — from the picture's size known before any
 *  pixels (MediaDims), so the bubble AND the tile have their final size in the
 *  first frame. */
private fun singleTileSize(item: MessageUi, edgeWidth: androidx.compose.ui.unit.Dp?): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val dims = com.alphamessenger.ui.chat.bubbles.MediaDims.of(item.id, item.mediaPosterB64, item.mediaWidth, item.mediaHeight)
    val w = (dims?.first?.toFloat() ?: 4f).coerceAtLeast(1f)
    val h = (dims?.second?.toFloat() ?: 3f).coerceAtLeast(1f)
    if (edgeWidth != null) {
        // Across the whole post; tall ones up to 5:4 in the feed (centre crop).
        val dw = edgeWidth.value
        return dw.dp to (dw * h / w).coerceIn(dw * 0.5f, dw * 1.25f).dp
    }
    return singleTileSizeCache[item.id] ?: run {
        val maxW = 246f
        val maxH = 330f
        var dw = maxW
        var dh = maxW * h / w
        if (dh > maxH) { dh = maxH; dw = maxH * w / h }
        // A very tall picture (a phone screenshot) would make a strip and squeeze
        // the caption to one word per line: never narrower than this; the extra
        // height is cropped in the bubble (the viewer shows it whole).
        dw = dw.coerceAtLeast(com.alphamessenger.ui.chat.bubbles.MEDIA_MIN_W)
        (dw.dp to dh.dp).also { if (dims != null) singleTileSizeCache[item.id] = it }
    }
}

/** Fold an album's [com.alphamessenger.transfer.TransferGroup] into the SAME
 *  [com.alphamessenger.transfer.FileUiState] every bubble renders — so the album
 *  uses the ONE overlay too. Hidden ⇒ no group control (all done, or a single
 *  outgoing item that owns its own tile overlay). */
private fun albumGroupState(
    group: com.alphamessenger.transfer.TransferGroup?,
): com.alphamessenger.transfer.FileUiState {
    if (group == null || group.items.isEmpty()) return com.alphamessenger.transfer.FileUiState.Hidden
    val hasUpload = group.items.any { it.isUpload }
    return when (val s = group.state) {
        is com.alphamessenger.transfer.TransferState.Uploading ->
            com.alphamessenger.transfer.FileUiState.Uploading(s.progress)
        is com.alphamessenger.transfer.TransferState.Downloading ->
            com.alphamessenger.transfer.FileUiState.Downloading(s.progress)
        // Album waits for transport: ✕ stays, no ↻ — nothing to retry.
        is com.alphamessenger.transfer.TransferState.WaitingNetwork ->
            com.alphamessenger.transfer.FileUiState.WaitingNetwork(s.progress)
        is com.alphamessenger.transfer.TransferState.Failed ->
            com.alphamessenger.transfer.FileUiState.Failed(
                if (hasUpload) com.alphamessenger.transfer.TransferDirection.UPLOAD
                else com.alphamessenger.transfer.TransferDirection.DOWNLOAD,
            )
        // Idle with items still missing ⇒ offer "download all" (incoming only —
        // an outgoing album whose upload finished is simply done).
        is com.alphamessenger.transfer.TransferState.Idle ->
            if (!hasUpload && group.completed < group.items.size)
                com.alphamessenger.transfer.FileUiState.NeedDownload
            else com.alphamessenger.transfer.FileUiState.Hidden
        else -> com.alphamessenger.transfer.FileUiState.Hidden // Completed / Cancelled ⇒ no group control
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumBubble(
    items: List<MessageUi>,
    head: MessageUi,
    bubbleColor: Color,
    textColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    showSenderName: Boolean,
    senderColor: Color,
    colors: AlphaColorScheme,
    onLongPress: () -> Unit,
    onActions: () -> Unit,
    onSaveItem: (MessageUi) -> Unit,
    /** Tile tapped — the screen owner opens the viewer. */
    onOpenItem: (MessageUi) -> Unit = {},
    mediaLoader: (suspend (String) -> String?)?,
    repository: AlphaCoreRepository?,
    mediaProgress: kotlinx.coroutines.flow.SharedFlow<Triple<String, Long, Long>>? = null,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    /** Cancels an album send: engine dispatch + core stop. */
    onAlbumAction: (String, com.alphamessenger.transfer.TransferAction) -> Unit = { _, _ -> },
) {
    var openIndex by remember { mutableStateOf<Int?>(null) }
    // How many tiles to show before "+N". The cap is purely visual: there is
    // no album entity in the protocol — the set is assembled on-screen from
    // consecutive messages, so nothing upstream limits its size.
    val maxTiles = 8
    val visible = items.take(maxTiles)
    val extra = items.size - visible.size

    // Only the tailed bubble (first/standalone in a run) needs the wider inset
    // on the tail side so the grid corner isn't clipped by the pointed tail.
    // Consecutive bubbles have no tail → keep the symmetric small inset.
    val hasTail = hasTail(head)
    // EQUAL inner border on ALL four sides of the body. The tail (BubbleShape) carves
    // tailWidth out of its side, so the tail side needs base + tailWidth of LAYOUT
    // padding — this widens the container so the tail sits OUTSIDE the media instead of
    // the media sliding under the tail (which killed the tail-side border). Widen the
    // container, never shrink the border.
    val base = BubbleTokens.compactPad
    val tailPad = base + BubbleTokens.tailWidth   // clears the carved tail
    // A single photo/video in a channel post spans the whole post: no inset.
    // A single photo/video keeps its chat size even in a channel; only an
    // album spans the post.
    val channelW = if (com.alphamessenger.ui.channel.LocalChannelPostFill.current) com.alphamessenger.ui.channel.LocalChannelPostWidth.current else null
    val edge = channelW != null && items.size == 1
    val startPad = if (edge) 0.dp else if (!head.isOutgoing && hasTail) tailPad else base
    val endPad = if (edge) 0.dp else if (head.isOutgoing && hasTail) tailPad else base
    // For a single media item the bubble hugs the media width so a long caption
    // wraps to the photo width (no empty gap beside a narrow portrait).
    // Known in the first frame (not reported back by the tile a frame later).
    val singleWidth: androidx.compose.ui.unit.Dp? = if (items.size == 1 && !edge)
        remember(items[0].id, items[0].mediaPosterB64) { singleTileSize(items[0], null).first } else null
    val widthMod = if (edge) {
        Modifier.width(channelW!!)
    } else if (items.size == 1 && singleWidth != null) {
        // Bubble width = tile + BOTH paddings EXACTLY, so the fixed-size tile fills the
        // content area with an equal border on every side and NO empty strip — at ANY
        // aspect (no min clamp). Max cap only, as a safety bound.
        Modifier.width((singleWidth!! + startPad + endPad).coerceAtMost(264.dp))
    } else {
        Modifier.widthIn(max = com.alphamessenger.ui.channel.LocalChannelPostWidth.current ?: 252.dp)
    }
    Column(
        // ONE shell for clip/background/click/width — see BubbleGeometry.bubbleShell.
        // widthMod (exact .width for a single tile, .widthIn for the grid) stays the
        // receiver; maxWidth=264 matches the single-tile cap and never tightens the grid
        // (its own widthIn(252) already wins). Tap on the chrome → actions; long-press →
        // multi-select. The album's own insets (base + tail-side reserve) are
        // passed verbatim.
        modifier = widthMod.bubbleShell(
            isOutgoing = head.isOutgoing,
            registerId = head.id,
            showTail = hasTail,
            bubbleShape = bubbleShape,
            bubbleColor = bubbleColor,
            maxWidth = if (edge) channelW!! else 264.dp,
            onTap = onActions,
            onLongPress = onLongPress,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = startPad, end = endPad, top = if (edge) 0.dp else 3.dp, bottom = if (edge) 8.dp else 3.dp,
            ),
        ),
    ) {
        // Unified "Forwarded" badge — same as every other bubble type.
        if (head.forwardedFrom != null) ForwardedLabel(head.isOutgoing, colors)
        if (showSenderName) {
            Text(
                head.senderName ?: "", color = senderColor, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 3.dp),
            )
        }
 // Album GROUP overlay: ONE aggregate control over the whole
        // grid — ⭕% + ✕ for the outgoing send, ↓ "download all" for an un-fetched
        // incoming album. While it governs, each tile shows content only (no
        // per-tile ring/↓), so the user acts on the album as a whole. Single-item
        // albums keep their own tile control.
        val group = mediaActions?.groups?.collectAsState()?.value?.get(head.id)
        val groupOverlay = albumGroupState(group)
        // Our own album going up: each tile shows its own ring / % / ✓ (items go up one
        // after another), no single ring over the grid. Ends when the album is published.
        val sending = group != null && group.items.any { it.isUpload }
        val groupActive = items.size >= 2 && !sending &&
            groupOverlay != com.alphamessenger.transfer.FileUiState.Hidden
        val gap = 3.dp
        // ONE transfer subscription for the WHOLE album (instead of 2 collectors
        // per tile). Each tile reads its state from this shared slice via
        // mediaStateFromSnapshots — the tiles are dumb renderers. The slice is
        // gated by derivedStateOf to only THIS album's visible-tile keys, so a
        // tick on an unrelated transfer elsewhere in the chat never recomposes
        // the bubble.
        val snapshotsState = mediaActions?.snapshots?.collectAsState()
        val albumKeys = remember(visible) {
            buildSet {
                visible.forEachIndexed { idx, item ->
                    val i = item.id.indexOf("#al")
                    val headId = if (i > 0) item.id.substring(0, i) else item.id
                    add(com.alphamessenger.transfer.TransferKey.upload("$headId#$idx").value)
                    item.mediaRefJson?.let { add(com.alphamessenger.transfer.TransferKey.download(it).value) }
                }
            }
        }
        val snapshots by remember(albumKeys, snapshotsState) {
            derivedStateOf { (snapshotsState?.value ?: emptyMap()).filterKeys { it in albumKeys } }
        }
        // ONE prefetch pass for the album (not N per-tile LaunchedEffects). The
        // engine owns the policy; considering ALL members (not just the 4 visible)
        // keeps a 5+ album auto-downloading every item.
        LaunchedEffect(items) {
            items.forEach { item ->
                val ref = item.mediaRefJson ?: return@forEach
                mediaActions?.consider?.invoke(
                    com.alphamessenger.transfer.TransferKey.download(ref),
                    item.mediaType, item.mediaFilename, item.mediaSizeBytes ?: 0L,
                    item.isOutgoing, item.localMediaPath, item.id,
                )
            }
        }
        // Per-tile state, folded from the shared map using the album upload-key
        // scheme (`up:<head>#<index>`). Pure — no collector per call.
        fun tileState(item: MessageUi, idx: Int): com.alphamessenger.transfer.FileUiState {
            val i = item.id.indexOf("#al")
            val headId = if (i > 0) item.id.substring(0, i) else item.id
            val uploadKey = com.alphamessenger.transfer.TransferKey.upload("$headId#$idx")
            val ms = com.alphamessenger.media.mediaStateFromSnapshots(item, uploadKey, snapshots)
            if (sending && items.size >= 2) {
                return if (ms.stage == com.alphamessenger.media.TransferStage.Uploaded) com.alphamessenger.transfer.FileUiState.Sent
                else ms.toOverlayState(item.isOutgoing)
            }
            // ▶ belongs to the CONTENT, not the transfer: the album's transfer is
            // owned by the ONE central group ring, so a tile must not hide playback
            // for its own upload progress — the bytes sit on the sender's disk the
            // whole time.
            // A single-item album has no group ring, so its tile must show its
            // own upload/download state.
            if (items.size >= 2 && ms.playable && ms.localPath != null) {
                return com.alphamessenger.transfer.FileUiState.Ready(ms.localPath, playable = true)
            }
            return ms.toOverlayState(item.isOutgoing)
        }
        @Composable
        fun cell(idx: Int, mod: Modifier) {
            Box(mod.clip(RoundedCornerShape(14.dp)).background(colors.bgPrimary.copy(alpha = 0.5f))) {
                // Downloading, a multi-item album is ONE session: only the central group
                // overlay shows ring / ✕ / ↓, so no tile can sit stuck with a ✕ after the
                // album's bytes are on disk. Sending, each tile shows its own upload.
                AlbumTile(visible[idx], idx, tileState(visible[idx], idx), mediaLoader, mediaProgress, mediaActions, onAlbumAction = onAlbumAction, onTap = { openIndex = idx }, onLongPress = onLongPress, overlayEnabled = sending)
                if (idx == visible.lastIndex && extra > 0) {
                    // "+N" → open the viewer at the very first item; long-press
                    // still opens the message actions sheet.
                    Box(
                        modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f))
                            .combinedClickable(onClick = { openIndex = 0 }, onLongClick = onLongPress),
                        contentAlignment = Alignment.Center,
                    ) { Text("+$extra", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
 // Each tile is its OWN MediaSurface with its OWN overlay:
        // per-tile upload % / download % / ↓ / ▶. No shared "1/N" group ring.
        Box(contentAlignment = Alignment.Center) {
            when (visible.size) {
                1 -> SingleAlbumTile(
                    item = visible[0],
                    edgeWidth = if (edge) channelW else null,
                    state = tileState(visible[0], 0),
                    mediaLoader = mediaLoader,
                    mediaProgress = mediaProgress,
                    mediaActions = mediaActions,
                    onAlbumAction = onAlbumAction,
                    onTap = { openIndex = 0 },
                    onLongPress = onLongPress,
                    onSized = { w -> singleTileWidthCache[head.id] = w },
                )
                2 -> Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    cell(0, Modifier.weight(1f).fillMaxHeight())
                    cell(1, Modifier.weight(1f).fillMaxHeight())
                }
                3 -> Row(Modifier.fillMaxWidth().height(220.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    // One large on the left, two stacked on the right.
                    cell(0, Modifier.weight(1.45f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        cell(1, Modifier.fillMaxWidth().weight(1f))
                        cell(2, Modifier.fillMaxWidth().weight(1f))
                    }
                }
                // Four and more: rows of equal height. The layout is a list of
                // rows, not a branch per count — all tile sets share the same code.
                // Split: six → three per row, eight → two; five and seven do not
                // divide evenly and get the nearest calm look with no lone
                // trailing tile. Row height drops as the count grows: eight 124dp
                // tiles would make a half-screen bubble and the set would stop
                // reading as one message.
                else -> {
                    val rows: List<List<Int>> = when (visible.size) {
                        4 -> listOf(listOf(0, 1), listOf(2, 3))
                        5 -> listOf(listOf(0, 1), listOf(2, 3, 4))
                        6 -> listOf(listOf(0, 1, 2), listOf(3, 4, 5))
                        7 -> listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5, 6))
                        else -> listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5), listOf(6, 7))
                    }
                    val rowHeight = when (rows.size) {
                        2 -> 124.dp
                        3 -> 106.dp
                        else -> 92.dp
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        rows.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().height(rowHeight),
                                horizontalArrangement = Arrangement.spacedBy(gap),
                            ) {
                                row.forEach { idx -> cell(idx, Modifier.weight(1f).fillMaxHeight()) }
                            }
                        }
                    }
                }
            }
            // THE album-wide control, centred over the whole grid. Same overlay as
            // every other type; the group state drives it. Tap ✕ cancels the send,
            // tap ↓ fetches every item. Individual items stream in the viewer.
            if (groupActive) {
                com.alphamessenger.transfer.TransferOverlay(
                    state = groupOverlay,
                    onDownloadAction = { act -> mediaActions?.dispatchGroup?.invoke(head.id, act) },
                    onUploadAction = { act -> onAlbumAction(head.id, act) },
                    onOpen = { openIndex = 0 },
                    modifier = Modifier.matchParentSize(),
                    diameter = com.alphamessenger.transfer.OverlaySpec.DIAMETER,
                    scrim = true,
                )
            }
        }
        head.mediaCaption?.takeIf { it.isNotBlank() }?.let { cap ->
            // The one text renderer: formatting, links, mentions — as in every bubble.
            com.alphamessenger.ui.chat.text.BubbleCaption(onAccent = head.isOutgoing,
                text = cap, color = textColor, fontSize = 15.sp, lineHeight = 18.sp,
                selectionMode = false, onTap = onActions, onLongPress = onLongPress,
                modifier = Modifier.padding(top = if (edge) 8.dp else 4.dp, start = if (edge) 12.dp else 4.dp, end = if (edge) 12.dp else 4.dp))
        }
        com.alphamessenger.ui.chat.bubbles.BubbleReactionsRow(head.isOutgoing, colors, Modifier.padding(top = 4.dp, start = if (edge) 10.dp else 4.dp))
        Row(
            modifier = Modifier.align(Alignment.End).padding(top = if (edge) 6.dp else 2.dp, end = if (edge) 12.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (head.isEdited) {
                com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.65f))
                Spacer(Modifier.width(4.dp))
            }
            com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
            Text(compactTime(head.timestamp), color = textColor.copy(alpha = 0.7f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
            if (head.isOutgoing) {
                Spacer(Modifier.width(3.dp))
                com.alphamessenger.ui.chat.bubbles.DeliveryTicks(head.status, textColor)
            }
        }
    }

    // The album does NOT open the viewer: it reports upward which item was
    // tapped. ChatScreen owns opening — otherwise the action set would depend
    // on the source.
    openIndex?.let { start ->
        LaunchedEffect(start) {
            items.getOrNull(start)?.let(onOpenItem)
            openIndex = null
        }
    }
}

/** One grid tile: shows a decrypted image / video poster, lazily loaded. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(
    item: MessageUi,
    index: Int,
    /** Pre-folded by the ONE album-level subscription — the tile is a dumb renderer. */
    state: com.alphamessenger.transfer.FileUiState,
    mediaLoader: (suspend (String) -> String?)?,
    mediaProgress: kotlinx.coroutines.flow.SharedFlow<Triple<String, Long, Long>>?,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    onAlbumAction: (String, com.alphamessenger.transfer.TransferAction) -> Unit = { _, _ -> },
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    /** False while the ALBUM group overlay governs — the tile shows content only,
     *  no per-tile ring/↓, so the album has ONE aggregate control. */
    overlayEnabled: Boolean = true,
) {
    val ctx = LocalContext.current
    val refJson = item.mediaRefJson
    var poster by remember(item.id) { mutableStateOf(com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.get("p:" + item.id)) }
    var full by remember(item.id) { mutableStateOf(com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.get("f:" + item.id)) }
    // The album's per-item UPLOAD is keyed `up:<head>#<index>` — kept only to
    // dispatch this tile's upload actions. Its STATE arrives pre-folded via [state].
    val headId = remember(item.id) {
        val i = item.id.indexOf("#al"); if (i > 0) item.id.substring(0, i) else item.id
    }
    val uploadKey = remember(headId, index) { com.alphamessenger.transfer.TransferKey.upload("$headId#$index") }
    val mediaKey = refJson?.let { com.alphamessenger.transfer.TransferKey.download(it) }
    LaunchedEffect(item.id) {
        if (poster == null) poster = withContext(Dispatchers.IO) {
            loadAlbumThumb(ctx, item)?.also { com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.put("p:" + item.id, it) }
        }
    }
    // The renderable file (cache OR original) — from the single state, no File.exists.
    val readyPath = state.readyPath
    // Decode the full image once the bytes are here.
    LaunchedEffect(readyPath) {
        if (full != null || readyPath == null) return@LaunchedEffect
        // Sampled to tile size — a 124–246px tile never needs the full 12 MP bitmap;
        // full-res churned the heap + uploaded a giant texture (draw/GC jank source).
        full = withContext(Dispatchers.IO) {
            com.alphamessenger.ui.chat.decodeSampledBitmap(readyPath, 1024)?.asImageBitmap()
                ?.also { com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.put("f:" + item.id, it) }
        }
    }
    LaunchedEffect(state) {
        if (state is com.alphamessenger.transfer.FileUiState.NeedDownload) full = null
    }
    val needsDl = item.mediaType != "video" && state is com.alphamessenger.transfer.FileUiState.NeedDownload
    val needsFetchVideo = item.mediaType == "video" && state is com.alphamessenger.transfer.FileUiState.NeedDownload
    val shown = full ?: poster
    // Duration (video) → the overlay's bottom-end label, so it survives transfer.
    // MMR is heavy native I/O → compute OFF the main thread or frames drop.
    // Seed from the DB duration; fall back to MMR only in a coroutine.
    var dur by remember(item.id) {
        mutableStateOf(if (item.mediaType == "video") item.mediaDurationMs?.takeIf { it > 0 } ?: 0L else 0L)
    }
    LaunchedEffect(item.id, readyPath) {
        if (item.mediaType == "video" && dur <= 0L && readyPath != null)
            dur = withContext(Dispatchers.IO) { audioDurationMs(readyPath) }
    }
    // Album tile = the SAME MediaSurface as Photo/Video. When the album's GROUP
    // overlay governs (multi-tile upload / download-all), the tile hands its
    // control to the group and shows content only (state → Hidden).
    // ▶ is a CONTENT affordance, not a transfer control: a ready video keeps its
    // Play even when the GROUP overlay governs transfer — only the transfer states
    // (ring / ✕ / ↓) fold to Hidden, so no tile sits stuck with a ✕ while a ready
    // video still shows its ▶.
    val tileState = when {
        overlayEnabled -> state
        state is com.alphamessenger.transfer.FileUiState.Ready && state.playable -> state
        else -> com.alphamessenger.transfer.FileUiState.Hidden
    }
            // While the item is open in the viewer, the tile renders its shape only.
        val hiddenWhileViewing = com.alphamessenger.ui.chat.media.MediaSourceRegistry.isViewing(item.id)
com.alphamessenger.transfer.MediaSurface(
        state = tileState,
        onDownloadAction = { act -> mediaKey?.let { mediaActions?.dispatch?.invoke(it, act, item.id) } },
        onUploadAction = { act -> if (act == com.alphamessenger.transfer.TransferAction.Cancel) onAlbumAction(item.id.substringBefore("#al"), act) else mediaActions?.dispatch?.invoke(uploadKey, act, item.id) },
        onOpen = { onTap() },
        modifier = Modifier.onGloballyPositioned { com.alphamessenger.ui.chat.media.MediaSourceRegistry.put(item.id, it.boundsInWindow()) }.alpha(if (hiddenWhileViewing) 0f else 1f).fillMaxSize().combinedClickable(
            onClick = {
                if (overlayEnabled && (needsDl || needsFetchVideo)) {
                    mediaKey?.let { mediaActions?.dispatch?.invoke(it, com.alphamessenger.transfer.TransferAction.Start, item.id) }
                } else onTap()
            },
            onLongClick = onLongPress,
        ),
        diameter = com.alphamessenger.transfer.OverlaySpec.DIAMETER_SMALL,
        durationLabel = if (dur > 0) fmtMsAlbum(dur) else null,
        idleSizeBytes = item.mediaSizeBytes ?: 0L,
    ) {
        // MediaContent: image → placeholder (never empty).
        shown?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } ?: Icon(AlphaIcons.Outlined.Image, null, tint = Color.White.copy(alpha = 0.30f), modifier = Modifier.size(28.dp))
    }
}

/** Format ms → "M:SS" for album tiles. */
private fun fmtMsAlbum(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Extract duration from a media file (lightweight — MediaMetadataRetriever). */
private fun audioDurationMs(path: String): Long = runCatching {
    val r = android.media.MediaMetadataRetriever()
    com.alphamessenger.data.MediaVault.setSource(r, path)
    val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    r.release()
    d
}.getOrDefault(0L)

/** A single photo/video "album" — render at its TRUE aspect ratio (capped) and
 *  letterbox-free (ContentScale.Fit), so a 9:16 shot isn't crushed into a square.
 * classic caps keep portraits from dominating the screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SingleAlbumTile(
    item: MessageUi,
    /** Pre-folded by the ONE album-level subscription — the tile is a dumb renderer. */
    state: com.alphamessenger.transfer.FileUiState,
    mediaLoader: (suspend (String) -> String?)?,
    mediaProgress: kotlinx.coroutines.flow.SharedFlow<Triple<String, Long, Long>>?,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    onTap: () -> Unit,
    onAlbumAction: (String, com.alphamessenger.transfer.TransferAction) -> Unit = { _, _ -> },
    onLongPress: () -> Unit,
    onSized: (androidx.compose.ui.unit.Dp) -> Unit = {},
    /** Channel post: the tile spans this width edge to edge (no corners). */
    edgeWidth: androidx.compose.ui.unit.Dp? = null,
) {
    val ctx = LocalContext.current
    val refJson = item.mediaRefJson
    var poster by remember(item.id) { mutableStateOf(com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.get("p:" + item.id)) }
    var full by remember(item.id) { mutableStateOf(com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.get("f:" + item.id)) }
    // A single-item album uploads under `up:<head>#0` — kept only to dispatch this
    // tile's upload actions. Its STATE arrives pre-folded via [state].
    val headId = remember(item.id) {
        val i = item.id.indexOf("#al"); if (i > 0) item.id.substring(0, i) else item.id
    }
    val uploadKey = remember(headId) { com.alphamessenger.transfer.TransferKey.upload("$headId#0") }
    val mediaKey = refJson?.let { com.alphamessenger.transfer.TransferKey.download(it) }
    LaunchedEffect(item.id) {
        if (poster == null) poster = withContext(Dispatchers.IO) {
            loadAlbumThumb(ctx, item)?.also { com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.put("p:" + item.id, it) }
        }
    }
    val readyPath = state.readyPath
    LaunchedEffect(readyPath) {
        if (full != null || readyPath == null) return@LaunchedEffect
        // Sampled to tile size — a 124–246px tile never needs the full 12 MP bitmap;
        // full-res churned the heap + uploaded a giant texture (draw/GC jank source).
        full = withContext(Dispatchers.IO) {
            com.alphamessenger.ui.chat.decodeSampledBitmap(readyPath, 1024)?.asImageBitmap()
                ?.also { com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.put("f:" + item.id, it) }
        }
    }
    LaunchedEffect(state) {
        if (state is com.alphamessenger.transfer.FileUiState.NeedDownload) full = null
    }
    val needsDl = item.mediaType != "video" && state is com.alphamessenger.transfer.FileUiState.NeedDownload
    val needsFetchVideo = item.mediaType == "video" && state is com.alphamessenger.transfer.FileUiState.NeedDownload
    val thumb = full ?: poster
    // Height is computed ONCE per item — from the poster's aspect if present,
    // else a fixed 4:3 fallback — and cached. poster/thumb/full only swap the
    // pixels inside this fixed box; a recycle re-reads the SAME size, so the
    // tile never re-measures and the list never jumps.
    // No pixel decode here: the size comes from MediaDims (header only, cached).
    val size = remember(item.id, item.mediaPosterB64, edgeWidth) { singleTileSize(item, edgeWidth) }
    LaunchedEffect(size) { onSized(size.first) }
    val mod = Modifier.size(size.first, size.second)
    // Video duration for the label. MMR is heavy native I/O → compute OFF the
    // main thread or frames drop. Seed from the DB duration; fall back to MMR
    // only in a coroutine.
    var dur by remember(item.id) {
        mutableStateOf(if (item.mediaType == "video") item.mediaDurationMs?.takeIf { it > 0 } ?: 0L else 0L)
    }
    LaunchedEffect(item.id, readyPath) {
        if (item.mediaType == "video" && dur <= 0L && readyPath != null)
            dur = withContext(Dispatchers.IO) { audioDurationMs(readyPath) }
    }
    // Single-item album = the SAME MediaSurface as Photo/Video.
    val hiddenWhileViewing = com.alphamessenger.ui.chat.media.MediaSourceRegistry.isViewing(item.id)
    com.alphamessenger.transfer.MediaSurface(
        state = state,
        onDownloadAction = { act -> mediaKey?.let { mediaActions?.dispatch?.invoke(it, act, item.id) } },
        onUploadAction = { act -> if (act == com.alphamessenger.transfer.TransferAction.Cancel) onAlbumAction(item.id.substringBefore("#al"), act) else mediaActions?.dispatch?.invoke(uploadKey, act, item.id) },
        onOpen = { onTap() },
        modifier = mod.onGloballyPositioned { com.alphamessenger.ui.chat.media.MediaSourceRegistry.put(item.id, it.boundsInWindow()) }.alpha(if (hiddenWhileViewing) 0f else 1f).clip(RoundedCornerShape(if (edgeWidth != null) 0.dp else 10.dp))
            .combinedClickable(
                onClick = { if (needsDl || needsFetchVideo) mediaKey?.let { mediaActions?.dispatch?.invoke(it, com.alphamessenger.transfer.TransferAction.Start, item.id) } else onTap() },
                onLongClick = onLongPress,
            ),
        durationLabel = if (dur > 0) fmtMsAlbum(dur) else null,
        idleSizeBytes = item.mediaSizeBytes ?: 0L,
    ) {
        // MediaContent: image → placeholder (never empty).
        thumb?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } ?: Icon(AlphaIcons.Outlined.Image, null, tint = Color.White.copy(alpha = 0.30f), modifier = Modifier.size(36.dp))
    }
}

// --- Loading helpers ---

/** Video thumbnail: prefer the tiny base64 poster, else a frame from a local file. */
private fun loadAlbumThumb(ctx: android.content.Context, item: MessageUi): ImageBitmap? = runCatching {
    item.mediaPosterB64?.let { decodeB64Bitmap(it) }
        ?: item.localMediaPath?.takeIf { File(it).exists() }?.let { videoFrameAt(ctx, it) }
}.getOrNull()


private fun videoFrameAt(ctx: android.content.Context, path: String): ImageBitmap? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    com.alphamessenger.data.MediaVault.setSource(r, path)
    val b = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    r.release(); b?.asImageBitmap()
}.getOrNull()

private fun decodeB64Bitmap(b64: String): ImageBitmap? = runCatching {
    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    com.alphamessenger.util.BoundedBitmap.decode(bytes)?.asImageBitmap()
}.getOrNull()
