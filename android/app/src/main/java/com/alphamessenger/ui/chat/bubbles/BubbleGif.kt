@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alphamessenger.ui.chat.bubbles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.media.toOverlayState
import com.alphamessenger.transfer.TransferAction
import com.alphamessenger.transfer.TransferKey
import com.alphamessenger.ui.chat.ForwardedLabel
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.compactTime
import com.alphamessenger.ui.gif.GifLibrary
import com.alphamessenger.ui.profile.PlayerSurface
import com.alphamessenger.ui.gif.GifPlayback
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val GIF_WIDTH = 230.dp
private val GIF_MAX_H = 260.dp

/**
 * A GIF: a short silent clip without a bubble, playing by itself in a loop
 * once fetched. It fetches like any video (the shared overlay shows it moving);
 * fetched, it joins "From chats" in the GIF panel.
 */
@Composable
internal fun GifBubble(
    message: MessageUi,
    showSenderName: Boolean,
    senderColor: Color,
    colors: AlphaColorScheme,
    onLongPress: () -> Unit,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    onCancelUpload: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
) {
    val context = LocalContext.current
    val refJson = message.mediaRefJson
    var poster by remember(message.id) { mutableStateOf(message.mediaPosterB64?.let { PosterBitmapCache.get(it) }) }
    LaunchedEffect(message.mediaPosterB64) {
        val b64 = message.mediaPosterB64
        if (poster != null || b64.isNullOrBlank()) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) { PosterBitmapCache.get(b64) ?: decodePosterB64(b64)?.also { PosterBitmapCache.put(b64, it) } }
        if (bmp != null) poster = bmp
    }
    val mstate = com.alphamessenger.media.rememberMediaState(message, mediaActions)
    val state = mstate.toOverlayState(message.isOutgoing)
    val mediaKey = refJson?.let { TransferKey.download(it) }
    fun dispatchDownload(act: TransferAction) {
        mediaKey?.let { mediaActions?.dispatch?.invoke(it, act, message.id) }
    }
    LaunchedEffect(message.id, refJson) {
        if (refJson != null) {
            mediaActions?.consider?.invoke(
                TransferKey.download(refJson),
                message.mediaType, message.mediaFilename, message.mediaSizeBytes ?: 0L,
                message.isOutgoing, message.localMediaPath, message.id,
            )
        }
    }
    val readyPath = mstate.localPath?.takeIf { !mstate.stage.isRunning }
    LaunchedEffect(readyPath) {
        val p = readyPath ?: return@LaunchedEffect
        if (poster == null) extractVideoThumb(p)?.let { poster = it.asImageBitmap() }
        // Our own sends are already among the saved ones.
        if (!message.isOutgoing) withContext(Dispatchers.IO) { GifLibrary.noteSeen(context, p, message.mediaCaption.orEmpty()) }
    }

    // Plays only from the shared pool, and only once the list stands still:
    // a scroll shows posters and builds nothing.
    LaunchedEffect(readyPath, GifPlayback.scrolling) {
        val p = readyPath ?: return@LaunchedEffect
        if (GifPlayback.scrolling) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        GifPlayback.acquire(context, message.id, p)
    }
    androidx.compose.runtime.DisposableEffect(message.id) { onDispose { GifPlayback.release(message.id) } }
    val player = GifPlayback.playerOf(message.id)

    // The size is known in the first frame (poster header or sent dimensions),
    // so the row never changes height as things load.
    val channelW = com.alphamessenger.ui.channel.LocalChannelPostWidth.current
    val dims = remember(message.id, message.mediaPosterB64) {
        MediaDims.of(message.id, message.mediaPosterB64, message.mediaWidth, message.mediaHeight)
    }
    val width = channelW ?: GIF_WIDTH
    val aspect = dims?.let { (w, h) -> (w.toFloat() / h).coerceIn(0.56f, 1.8f) } ?: 1.4f
    val inPost = channelW != null
    // In a channel post the post's frame rounds the corners and carries the
    // comments line under the picture.
    val shape = if (inPost) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(16.dp)
    // A tall GIF stays modest: in a chat it narrows to its shape under a height
    // cap; in a post it keeps the post's width and is cropped to a square at most.
    val (boxW, boxH) = if (inPost) width to minOf(width / aspect, width)
        else if (width / aspect > GIF_MAX_H) (GIF_MAX_H * aspect) to GIF_MAX_H
        else width to width / aspect
    val box = Modifier.size(boxW, boxH).clip(shape).background(Color.Black.copy(alpha = 0.4f))
        .combinedClickable(
            onClick = { if (readyPath == null && !mstate.stage.isRunning) dispatchDownload(TransferAction.Start) },
            onLongClick = onLongPress,
        )

    val picture: @Composable () -> Unit = {
        Box {
            val content: @Composable () -> Unit = {
                poster?.let { Image(it, "GIF", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (player != null) PlayerSurface(player, Modifier.fillMaxSize())
            }
            if (readyPath != null) {
                Box(box) { content() }
            } else {
                com.alphamessenger.transfer.MediaSurface(
                    state = state,
                    onDownloadAction = { act -> dispatchDownload(act) },
                    onUploadAction = { act ->
                        when (act) {
                            TransferAction.Cancel -> onCancelUpload()
                            TransferAction.Retry -> onRetryUpload()
                            else -> {}
                        }
                    },
                    onOpen = {},
                    modifier = box,
                    durationLabel = null,
                    idleSizeBytes = message.mediaSizeBytes ?: 0L,
                ) { content() }
            }
            Text("GIF", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (inPost) com.alphamessenger.ui.channel.PostViews(Color.White)
                if (message.isEdited) com.alphamessenger.ui.chat.bubbles.EditedMark(Color.White)
                Text(compactTime(message.timestamp), color = Color.White, fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
                if (message.isOutgoing) DeliveryTicks(message.status, Color.White)
            }
        }
    }

    if (inPost) {
        // The post's frame holds it; reactions go under, like any post.
        Column {
            picture()
            BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp, start = 10.dp))
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
        if (showSenderName) {
            Text(message.senderName.orEmpty(), color = senderColor, fontSize = BubbleTokens.senderNameSp.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
        }
        val caption = message.mediaCaption?.takeIf { it.isNotBlank() }
        if (caption == null) picture()
        else {
            // With words it is a bubble like a captioned photo: the clip, the text under it.
            val fill = if (message.isOutgoing) androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.msgOutStart, colors.msgOutEnd))
                else androidx.compose.ui.graphics.SolidColor(colors.msgIn)
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(fill).padding(3.dp).width(boxW)) {
                picture()
                com.alphamessenger.ui.chat.text.BubbleCaption(
                    onAccent = message.isOutgoing,
                    text = caption,
                    color = if (message.isOutgoing) colors.msgOutText else colors.textPrimary,
                    fontSize = BubbleTokens.captionSp.sp,
                    lineHeight = BubbleTokens.captionLineSp.sp,
                    selectionMode = false,
                    onTap = onLongPress,
                    onLongPress = onLongPress,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 4.dp),
                )
            }
        }
        BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp))
    }
}
