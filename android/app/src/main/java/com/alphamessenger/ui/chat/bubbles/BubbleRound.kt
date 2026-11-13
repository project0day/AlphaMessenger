@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alphamessenger.ui.chat.bubbles

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.media.toOverlayState
import com.alphamessenger.ui.chat.ForwardedLabel
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.compactTime
import com.alphamessenger.ui.chat.fmtMs
import com.alphamessenger.ui.chat.round.RoundPlayer
import com.alphamessenger.ui.chat.round.roundGestures
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Diameter of a video message in the chat. */
private val ROUND_SIZE = 220.dp

/**
 * A video message: a circle without a bubble. It fetches by itself like any
 * video (the transfer ring and ✕ come from the shared overlay while it moves).
 * Ready, a tap plays it right here with sound — the circle grows a little, a
 * ring with a dot shows how far it is and winds it when dragged — and the next
 * tap pauses. It goes on (floating) when the chat is left.
 */
@Composable
internal fun RoundVideoBubble(
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
    var durationMs by remember(message.id) { mutableStateOf(message.mediaDurationMs?.toInt() ?: 0) }

    val mstate = com.alphamessenger.media.rememberMediaState(message, mediaActions)
    val state = mstate.toOverlayState(message.isOutgoing)
    val mediaKey = refJson?.let { com.alphamessenger.transfer.TransferKey.download(it) }
    fun dispatchDownload(act: com.alphamessenger.transfer.TransferAction) {
        mediaKey?.let { mediaActions?.dispatch?.invoke(it, act, message.id) }
    }
    val readyPath = mstate.localPath
    LaunchedEffect(message.id, refJson) {
        if (refJson != null) {
            mediaActions?.consider?.invoke(
                com.alphamessenger.transfer.TransferKey.download(refJson),
                message.mediaType, message.mediaFilename, message.mediaSizeBytes ?: 0L,
                message.isOutgoing, message.localMediaPath, message.id,
            )
        }
    }
    LaunchedEffect(readyPath) {
        val p = readyPath ?: return@LaunchedEffect
        if (poster == null) extractVideoThumb(p)?.let { poster = it.asImageBitmap() }
        if (durationMs <= 0) durationMs = withContext(Dispatchers.IO) { audioDurationMs(p) }
    }

    val isCurrent = RoundPlayer.now?.messageId == message.id
    val who = message.senderName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.notif_you)
    val at = remember(message.timestamp) {
        runCatching {
            java.time.OffsetDateTime.parse(message.timestamp).atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.getDefault()))
        }.getOrDefault(compactTime(message.timestamp))
    }
    var wantPlay by remember(message.id) { mutableStateOf(false) }
    // On screen: the playing circle shows here; scrolled away, it floats.
    androidx.compose.runtime.DisposableEffect(message.id) {
        RoundPlayer.bubbleShown(message.id, true)
        onDispose { RoundPlayer.bubbleShown(message.id, false) }
    }
    fun start(path: String) = RoundPlayer.play(context, message.id, path, who, at)
    // A tapped circle whose file just landed starts playing.
    LaunchedEffect(readyPath) {
        if (readyPath != null && wantPlay) { wantPlay = false; start(readyPath) }
    }
    fun onTap() {
        android.util.Log.i("ROUND", "tap ${message.id.take(8)} ready=${readyPath != null} running=${mstate.stage.isRunning} current=$isCurrent")
        when {
            isCurrent -> RoundPlayer.toggle()
            readyPath != null -> start(readyPath)
            mstate.stage.isRunning -> wantPlay = true
            else -> { wantPlay = true; dispatchDownload(com.alphamessenger.transfer.TransferAction.Start) }
        }
    }
    // Playing, the circle grows to be watched (the floating one is as big).
    val side by androidx.compose.animation.core.animateDpAsState(
        if (isCurrent) com.alphamessenger.ui.chat.round.ROUND_PLAYING_SIZE else ROUND_SIZE,
        spring(dampingRatio = 0.75f), label = "roundSize",
    )

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
        if (showSenderName) {
            Text(message.senderName.orEmpty(), color = senderColor, fontSize = BubbleTokens.senderNameSp.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
        }
        Box(contentAlignment = Alignment.Center) {
            // Playing: the ring winds the video, the middle taps / opens the
            // menu — one gesture owner, so a wind never becomes a long press.
            val gestures = if (isCurrent) Modifier.pointerInput(Unit) {
                roundGestures(onTap = { onTap() }, onLongPress = onLongPress)
            } else Modifier.combinedClickable(onClick = { onTap() }, onLongClick = onLongPress)
            val circle = Modifier.size(side).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).then(gestures)
            val content: @Composable () -> Unit = {
                poster?.let { Image(it, stringResource(R.string.round_label), contentScale = ContentScale.Crop, modifier = Modifier.size(side)) }
                if (isCurrent) com.alphamessenger.ui.chat.round.RoundVideoSurface(Modifier.size(side).clip(CircleShape))
            }
            if (readyPath != null && !mstate.stage.isRunning) {
                // Ready: just the picture — a tap plays, no button on it.
                Box(circle, contentAlignment = Alignment.Center) { content() }
            } else {
                // On its way (either direction): the shared transfer overlay.
                com.alphamessenger.transfer.MediaSurface(
                    state = state,
                    onDownloadAction = { act ->
                        when (act) {
                            com.alphamessenger.transfer.TransferAction.Start -> onTap()
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
                    onOpen = { onTap() },
                    modifier = circle,
                    durationLabel = null,
                    idleSizeBytes = message.mediaSizeBytes ?: 0L,
                    circular = true,
                ) { content() }
            }
            if (isCurrent) com.alphamessenger.ui.chat.round.RoundProgressRing(side)
        }
        Row(
            Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.28f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val shownMs = if (isCurrent && RoundPlayer.durationMs > 0) (RoundPlayer.durationMs - RoundPlayer.positionMs).toInt() else durationMs
            if (shownMs > 0) {
                if (isCurrent && RoundPlayer.playing) Icon(AlphaIcons.Outlined.VolumeUp, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(fmtMs(shownMs), color = Color.White, fontSize = 11.sp)
                Spacer(Modifier.width(2.dp))
            }
            if (message.isEdited) com.alphamessenger.ui.chat.bubbles.EditedMark(Color.White.copy(alpha = 0.85f))
            Text(compactTime(message.timestamp), color = Color.White.copy(alpha = 0.85f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
            if (message.isOutgoing) DeliveryTicks(message.status, Color.White)
        }
        BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp))
    }
}
