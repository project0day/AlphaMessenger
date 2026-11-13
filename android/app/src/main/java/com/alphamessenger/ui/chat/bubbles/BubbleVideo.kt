@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alphamessenger.ui.chat.bubbles

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalContext
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
import com.alphamessenger.ui.chat.fmtMs
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VideoBubble(
    message: MessageUi,
    bubbleColor: Color,
    textColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    showSenderName: Boolean,
    senderColor: Color,
    colors: AlphaColorScheme,
    onLongPress: () -> Unit,
    onMediaActions: () -> Unit = {},
    mediaLoader: (suspend (String) -> String?)?,
    mediaProgress: kotlinx.coroutines.flow.SharedFlow<Triple<String, Long, Long>>? = null,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    onCancelUpload: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
    /** Caption text-selection mode — shared with text messages. */
    textSelectActive: Boolean = false,
    repository: com.alphamessenger.data.AlphaCoreRepository? = null,
    /** Open the ready file in the shared MediaViewer. The bubble does NOT open
     *  the viewer itself — it only reports "this file is ready" upward (like the
     *  photo bubble), so the video joins the viewer's chat gallery, thumbnail
     *  strip and caption. */
    onOpenViewer: (path: String, messageId: String) -> Unit = { _, _ -> },
) {
    val refJson = message.mediaRefJson
    // Instant preview: base64 poster carried in the msg (survives cache
    // eviction). The video thumb from the local file is pulled in the
    // LaunchedEffect(localPath) below once the engine says the file is here.
    // Instant preview: seed from the LRU cache (no work), then decode the base64
    // poster OFF the UI thread and adopt it. Never decodes in composition.
    var poster by remember(message.id) {
        mutableStateOf(message.mediaPosterB64?.let { PosterBitmapCache.get(it) })
    }
    LaunchedEffect(message.mediaPosterB64) {
        val b64 = message.mediaPosterB64
        if (poster != null || b64.isNullOrBlank()) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) {
            PosterBitmapCache.get(b64) ?: decodePosterB64(b64)?.also { PosterBitmapCache.put(b64, it) }
        }
        if (bmp != null) poster = bmp
    }
    var durationMs by remember(message.id) { mutableStateOf(message.mediaDurationMs?.toInt() ?: 0) }

    // SINGLE SOURCE OF TRUTH: the ONE MediaState. The bubble is DUMB — it reads
    // state.stage / state.localPath and draws. The overlay view is fed a dumb 1:1
    // adapter (no second brain). Streaming is a SEPARATE track (below): opening a
    // video never touches the download.
    val mstate = com.alphamessenger.media.rememberMediaState(message, mediaActions)
    val state = mstate.toOverlayState(message.isOutgoing)
    val mediaKey = refJson?.let { com.alphamessenger.transfer.TransferKey.download(it) }
    fun dispatchDownload(act: com.alphamessenger.transfer.TransferAction) {
        mediaKey?.let { mediaActions?.dispatch?.invoke(it, act, message.id) }
    }
    // The playable local file (cache OR sender original) — from the ONE state.
    val readyPath = mstate.localPath
    // Once we have the file, pull a poster frame + duration (off the UI thread).
    LaunchedEffect(readyPath) {
        val p = readyPath ?: return@LaunchedEffect
        extractVideoThumb(p)?.asImageBitmap()?.let { poster = it }
        // MediaMetadataRetriever is heavy native I/O — MUST run off the main
        // thread or frames drop during scroll.
        durationMs = withContext(Dispatchers.IO) { audioDurationMs(p) }
    }
    // Auto-download incoming video in the background (poster shows meanwhile).
    // For streaming refs: skip full download — the StreamingVideoPlayer will
    // fetch chunks on demand via EncryptedDataSource.
    // Gated by the auto-download policy (network/type/size); when blocked the
    // poster still shows and tapping it downloads + plays (see onTapped).
    // downloadStore is driven by loadMediaTracked (markCompleted/markFailed).
    // The bubble does NOT decide whether to fetch and does NOT fetch. It tells the
    // engine an attachment appeared; the engine owns the policy and the session.
    LaunchedEffect(message.id, refJson) {
        if (refJson != null) {
            mediaActions?.consider?.invoke(
                com.alphamessenger.transfer.TransferKey.download(refJson),
                message.mediaType, message.mediaFilename, message.mediaSizeBytes ?: 0L,
                message.isOutgoing, message.localMediaPath, message.id,
            )
        }
    }

    // A single photo/video keeps its chat size even in a channel; only an
    // album spans the post.
    val channelW = if (com.alphamessenger.ui.channel.LocalChannelPostFill.current) com.alphamessenger.ui.channel.LocalChannelPostWidth.current else null
    // The size is known before any pixels (see MediaDims): the bubble takes its
    // final size in the first frame instead of growing when the poster decodes.
    val dims = remember(message.id, message.mediaPosterB64) {
        MediaDims.of(message.id, message.mediaPosterB64, message.mediaWidth, message.mediaHeight)
    }
    val photoSize = remember(dims, poster, channelW) {
        val bm = poster
        val w = (dims?.first?.toFloat() ?: bm?.width?.toFloat() ?: 0f).coerceAtLeast(1f)
        val h = (dims?.second?.toFloat() ?: bm?.height?.toFloat() ?: 0f).coerceAtLeast(1f)
        if (channelW != null) {
            // Channel post: across the whole post; tall videos up to 5:4 in the feed.
            val dw = channelW.value
            return@remember dw.dp to (if (dims == null && bm == null) dw * 0.5625f else (dw * h / w).coerceIn(dw * 0.5f, dw * 1.25f)).dp
        }
        if (dims == null && bm == null) return@remember null
        var dw = 248f; var dh = 248f * h / w
        if (dh > 320f) { dh = 320f; dw = 320f * w / h }
        // A very tall picture (a phone screenshot) would make a strip and squeeze
        // the caption to one word per line: never narrower than this; the extra
        // height is cropped in the bubble (the viewer shows it whole).
        dw = dw.coerceAtLeast(MEDIA_MIN_W)
        dw.dp to dh.dp
    }

    fun onOpen() {
        when {
            // Have the full file (cache OR our own original) → open in the shared Viewer.
            readyPath != null -> onOpenViewer(readyPath, message.id)
            // No full file yet: the viewer opens at once and plays what has arrived
            // (a chunked video streams; any other waits there for the file), while the
            // download keeps going for offline viewing. A big video is watchable at once.
            mstate.stage.isRunning -> onOpenViewer("", message.id)
            else -> {
                dispatchDownload(com.alphamessenger.transfer.TransferAction.Start)
                onOpenViewer("", message.id)
            }
        }
    }

    val showTail = hasTail(message)
    // Media width defines the bubble width (same rule as BubbleImage) so a caption/
    // name wider than a portrait poster can't widen the bubble and shift the media.
    val photoW = photoSize?.first ?: 230.dp
    Column(
        // ONE geometry mechanism for every bubble — see BubbleGeometry.bubbleShell.
        modifier = Modifier.bubbleShell(
            isOutgoing = message.isOutgoing,
            registerId = message.id, showTail = showTail,
            bubbleShape = bubbleShape, bubbleColor = bubbleColor,
            maxWidth = channelW ?: BubbleTokens.mediaBubbleMaxW, onTap = onMediaActions, onLongPress = onLongPress,
            contentPadding = if (channelW != null) androidx.compose.foundation.layout.PaddingValues(0.dp)
                else BubbleTokens.contentPadding(message.isOutgoing, showTail),
        ),
    ) {
        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
        if (showSenderName) {
            Text(
                text = message.senderName ?: "",
                color = senderColor, fontSize = BubbleTokens.senderNameSp.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = photoW).padding(bottom = 3.dp),
            )
        }
        // Video = MediaSurface (MediaContent + the ONE MediaOverlay). Start routes
        // through onOpen so a fresh download auto-opens the player when it lands.
        // While the item is open in the viewer, the bubble renders its shape only.
        val hiddenWhileViewing = com.alphamessenger.ui.chat.media.MediaSourceRegistry.isViewing(message.id)
        com.alphamessenger.transfer.MediaSurface(
            state = state,
            onDownloadAction = { act -> 
                when (act) {
                    com.alphamessenger.transfer.TransferAction.Start -> onOpen()
                    else -> dispatchDownload(act)
                }
            },
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
                .then(if (photoSize != null) Modifier.size(photoSize.first, photoSize.second) else Modifier.size(230.dp, 150.dp))
                .clip(RoundedCornerShape(if (channelW != null) 0.dp else 14.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .combinedClickable(onClick = { onOpen() }, onLongClick = onLongPress),
            durationLabel = if (durationMs > 0) fmtMs(durationMs) else null,
            idleSizeBytes = message.mediaSizeBytes ?: 0L,
        ) {
            // MediaContent: extracted frame / poster → placeholder (never empty).
            // Duration is drawn by the overlay (bottom-end), so it survives transfer.
            poster?.let {
                Image(it, stringResource(R.string.media_kind_video), contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } ?: Icon(
                AlphaIcons.Outlined.Movie, null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(48.dp),
            )
        }
        message.mediaCaption?.let { cap ->
            // Constrain caption to the poster width so it never widens the bubble.
            com.alphamessenger.ui.chat.text.BubbleCaption(onAccent = message.isOutgoing, text = cap, color = textColor, fontSize = BubbleTokens.captionSp.sp, lineHeight = BubbleTokens.captionLineSp.sp, selectionMode = textSelectActive,
                onTap = onMediaActions, onLongPress = onLongPress,
                modifier = Modifier.width(photoW).padding(top = if (channelW != null) 8.dp else 4.dp,
                    start = if (channelW != null) 12.dp else 2.dp, end = if (channelW != null) 12.dp else 2.dp))
        }
        BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp, start = if (channelW != null) 10.dp else 2.dp))
        Row(
            modifier = Modifier.align(Alignment.End).padding(top = 3.dp, end = if (channelW != null) 12.dp else 2.dp,
                bottom = if (channelW != null) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (message.isEdited) { com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.65f)); Spacer(Modifier.width(4.dp)) }
            com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
            Text(compactTime(message.timestamp), color = textColor.copy(alpha = 0.7f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
            if (message.isOutgoing) { Spacer(Modifier.width(4.dp)); DeliveryTicks(message.status, textColor) }
        }
    }
    // The viewer is NOT opened here: the bubble reports upward (onOpenViewer)
    // and the shared MediaViewer owns opening — same as photos.
}
