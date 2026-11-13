@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alphamessenger.ui.chat.bubbles

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.Image as PhotoImage
import com.alphamessenger.ui.chat.hasTail
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.media.toOverlayState
import com.alphamessenger.ui.chat.ForwardedLabel
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.compactTime
import com.alphamessenger.ui.chat.decodeSampledBitmap
import com.alphamessenger.ui.chat.rememberPosterBitmap
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MediaBubble(
    message: MessageUi,
    bubbleColor: Color,
    textColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    showSenderName: Boolean,
    senderColor: Color,
    colors: AlphaColorScheme,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    mediaLoader: (suspend (String) -> String?)?,
    cachedMediaPath: ((String) -> String?)? = null,
    mediaProgress: kotlinx.coroutines.flow.SharedFlow<Triple<String, Long, Long>>? = null,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    onCancelUpload: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
    /** Caption text-selection mode — shared with text messages. */
    textSelectActive: Boolean = false,
    onMediaClick: (path: String, messageId: String) -> Unit,
) {
    val refJson = message.mediaRefJson
    val cacheKey = message.localMediaPath ?: refJson
    // Unified media state — the single source of truth. This bubble is DUMB: it
    // reads a TransferSnapshot and renders; the TransferEngine owns policy, disk
    // checks, downloads and eviction.
    val state = com.alphamessenger.media.rememberMediaState(message, mediaActions)
        .toOverlayState(message.isOutgoing)
    val mediaKey = refJson?.let { com.alphamessenger.transfer.TransferKey.download(it) }
    fun dispatchDownload(act: com.alphamessenger.transfer.TransferAction) {
        mediaKey?.let { mediaActions?.dispatch?.invoke(it, act, message.id) }
    }
    // The renderable file (decrypted cache OR the sender's own original) — from
    // the single state, never a raw snapshot lookup.
    val readyPath = state.readyPath

    // ── Pure rendering: decode the file into a bitmap (off-thread, cached). ──
    val cachedBitmap = remember(cacheKey) { cacheKey?.let { MediaImageCache.get(it)?.asImageBitmap() } }
    var bitmap by remember(cacheKey) { mutableStateOf(cachedBitmap) }
    var decodeFailed by remember(cacheKey) { mutableStateOf(false) }
    // The file left the cache (→ NeedDownload) → drop the bitmap so the bubble
    // flips to the download placeholder with no scroll/reload.
    LaunchedEffect(state) {
        if (state is com.alphamessenger.transfer.FileUiState.NeedDownload) {
            bitmap = null; decodeFailed = false
        }
    }
    LaunchedEffect(readyPath) {
        if (bitmap != null || readyPath == null) return@LaunchedEffect
        // Sampled to display size — a chat bubble never needs the full 12 MP bitmap;
        // full-res here churned the heap + uploaded a giant texture (draw/GC jank).
        val bmp = withContext(Dispatchers.IO) { decodeSampledBitmap(readyPath, 1280) }
        if (bmp != null) {
            cacheKey?.let { MediaImageCache.put(it, bmp) }
            bitmap = bmp.asImageBitmap()
        } else decodeFailed = true
    }
    // Tap: open when the bytes are here; else request the download. A running
    // transfer is driven by the overlay ✕ — not this tap.
    fun onOpen() {
        if (state.isActive) return
        val p = readyPath
        if (p != null) { onMediaClick(p, message.id); return }
        dispatchDownload(com.alphamessenger.transfer.TransferAction.Start)
    }
    // Aspect comes from the poster (travels in the ref, present before download),
    // so the bubble reserves its final height from the first frame. Full bitmap
    // is the fallback for legacy messages that predate posters.
    val posterBitmap = rememberPosterBitmap(message.mediaPosterB64)
    // A single photo/video keeps its chat size even in a channel; only an
    // album spans the post.
    val channelW = if (com.alphamessenger.ui.channel.LocalChannelPostFill.current) com.alphamessenger.ui.channel.LocalChannelPostWidth.current else null
    // The size is known before any pixels (see MediaDims): the bubble takes its
    // final size in the first frame instead of growing when the poster decodes.
    val dims = remember(message.id, message.mediaPosterB64) {
        MediaDims.of(message.id, message.mediaPosterB64, message.mediaWidth, message.mediaHeight)
    }
    val photoSize = remember(dims, bitmap, channelW) {
        val srcW = dims?.first ?: bitmap?.width ?: return@remember null
        val srcH = dims?.second ?: bitmap?.height ?: return@remember null
        val w = srcW.toFloat().coerceAtLeast(1f)
        val h = srcH.toFloat().coerceAtLeast(1f)
        // Fit INSIDE the bubble: bubble max = 252dp, minus padding 4+4 = 244dp
        // usable. Use 240dp as the photo max so the photo never overflows the
        // bubble edge.
        if (channelW != null) {
            // Channel post: the photo spans the whole post; a tall one is shown up
            // to 5:4 in the feed (centre crop), whole in the viewer.
            val dw = channelW.value
            (dw).dp to (dw * h / w).coerceIn(dw * 0.5f, dw * 1.25f).dp
        } else {
        val maxW = 240f
        val maxH = 320f
        var dw = maxW
        var dh = maxW * h / w
        if (dh > maxH) { dh = maxH; dw = maxH * w / h }
        // A very tall picture (a phone screenshot) would make a strip and squeeze
        // the caption to one word per line: never narrower than this; the extra
        // height is cropped in the bubble (the viewer shows it whole).
        dw = dw.coerceAtLeast(MEDIA_MIN_W)
        dw.dp to dh.dp
        }
    }
    // The bubble's tail (7dp wide, see BubbleShape.tailW) carves a wedge out of
    // one side. To keep an EQUAL visual gap on both sides, the tail side gets
    // `base + tailWidth` padding (the tail eats the 7dp), the other side just
    // `base`. base is uniform top/bottom/flat-side.
    val showTail = hasTail(message)
    Column(
        // ONE geometry mechanism for every bubble — see BubbleGeometry.bubbleShell.
        modifier = Modifier.bubbleShell(
            isOutgoing = message.isOutgoing,
            registerId = message.id, showTail = showTail,
            bubbleShape = bubbleShape, bubbleColor = bubbleColor,
            maxWidth = channelW ?: BubbleTokens.mediaBubbleMaxW, onTap = onTap, onLongPress = onLongPress,
            // Channel: media touches the bubble's edges (the bubble clips the corners).
            contentPadding = if (channelW != null) androidx.compose.foundation.layout.PaddingValues(0.dp)
                else BubbleTokens.contentPadding(message.isOutgoing, showTail),
        ),
    ) {
        // The MEDIA width defines the bubble width. Sender/caption are constrained to
        // it so a name/caption WIDER than a (narrow, portrait) photo cannot widen the
        // bubble and leave the Start-aligned media optically shifted left.
        val ps = photoSize
        val photoW = ps?.first ?: channelW ?: 220.dp
        val photoH = ps?.second ?: channelW?.times(0.75f) ?: 160.dp
        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
        if (showSenderName) {
            Text(
                text = message.senderName ?: "",
                color = senderColor,
                fontSize = BubbleTokens.senderNameSp.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = photoW).padding(horizontal = BubbleTokens.senderNameH, vertical = BubbleTokens.senderNameV),
            )
        }
        // Photo = MediaSurface (content + Overlay 2.0). Time/status ride a translucent
 // pill over the media bottom-end — they never steal a row and
        // align to the media corner.
        Box {
            // While the item is open in the viewer, the bubble renders its shape only.
            val hiddenWhileViewing = com.alphamessenger.ui.chat.media.MediaSourceRegistry.isViewing(message.id)
            com.alphamessenger.transfer.MediaSurface(
                state = state,
                onDownloadAction = { act -> dispatchDownload(act) },
                onUploadAction = { act ->
                    when (act) {
                        com.alphamessenger.transfer.TransferAction.Cancel -> onCancelUpload()
                        com.alphamessenger.transfer.TransferAction.Retry -> onRetryUpload()
                        else -> {}
                    }
                },
                onOpen = { onOpen() },
                modifier = Modifier
                    .alpha(if (hiddenWhileViewing) 0f else 1f)
                    .size(width = photoW, height = photoH)
                    .clip(RoundedCornerShape(if (channelW != null) 0.dp else BubbleTokens.mediaCorner))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .combinedClickable(onClick = { onOpen() }, onLongClick = onLongPress),
                idleSizeBytes = message.mediaSizeBytes ?: 0L,
            ) {
                // MediaContent: decoded bitmap → poster → placeholder (never empty).
                val shown = bitmap ?: posterBitmap
                if (shown != null) {
                    PhotoImage(
                        bitmap = shown,
                        contentDescription = stringResource(R.string.preview_photo),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Icon(
                        AlphaIcons.Outlined.Image, null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
 // Reactions over the media bottom-start, clear of the time pill.
            BubbleReactionsRow(
                isOutgoing = message.isOutgoing,
                colors = colors,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 6.dp, end = 76.dp),
                surface = ReactionSurface.Media,
            )
 // Footer pill: edited · time · ✓ over the media bottom-end.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(BubbleTokens.footerPillInset)
                    .clip(RoundedCornerShape(BubbleTokens.footerPillCorner))
                    .background(BubbleTokens.footerPillBg)
                    .padding(horizontal = BubbleTokens.footerPillPadH, vertical = BubbleTokens.footerPillPadV),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.isEdited) {
                    com.alphamessenger.ui.chat.bubbles.EditedMark(BubbleTokens.footerOnMediaText.copy(alpha = 0.8f))
                    Spacer(Modifier.width(4.dp))
                }
                com.alphamessenger.ui.channel.PostViews(BubbleTokens.footerOnMediaText)
                Text(compactTime(message.timestamp), color = BubbleTokens.footerOnMediaText, fontSize = BubbleTokens.footerTimeSp.sp)
                if (message.isOutgoing) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryTicks(message.status, BubbleTokens.footerOnMediaText)
                }
            }
        }
        // Caption BELOW the media (if present).
        message.mediaCaption?.let { cap ->
            com.alphamessenger.ui.chat.text.BubbleCaption(onAccent = message.isOutgoing,
                text = cap,
                selectionMode = textSelectActive,
                color = textColor,
                fontSize = BubbleTokens.captionSp.sp,
                lineHeight = BubbleTokens.captionLineSp.sp,
                onTap = onTap,
                onLongPress = onLongPress,
                // Constrain the caption to the media width so it never widens the bubble
                // past the photo (which would shift the Start-aligned photo left).
                modifier = Modifier.width(photoW).padding(
                    top = if (channelW != null) 8.dp else 4.dp,
                    start = if (channelW != null) 12.dp else 2.dp, end = if (channelW != null) 12.dp else 2.dp,
                    bottom = if (channelW != null) 8.dp else 0.dp,
                ),
            )
        }
    }
}
