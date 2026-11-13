@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alphamessenger.ui.chat.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.media.toOverlayState
import com.alphamessenger.transfer.TransferAction
import com.alphamessenger.transfer.TransferKey
import com.alphamessenger.ui.chat.ForwardedLabel
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.compactTime
import com.alphamessenger.ui.sticker.StickerImage
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * A sticker: the picture alone, no bubble. Small, so it is fetched right away;
 * a tap opens the pack it came from, the long press the usual menu.
 */
@Composable
internal fun StickerBubble(
    message: MessageUi,
    showSenderName: Boolean,
    senderColor: Color,
    colors: AlphaColorScheme,
    onLongPress: () -> Unit,
    onOpenPack: () -> Unit,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
) {
    // Just sent or received: a vector sticker plays once whatever the setting, then on a tap.
    var replay by androidx.compose.runtime.remember(message.id) {
        androidx.compose.runtime.mutableIntStateOf(if (com.alphamessenger.ui.emoji.AnimatedEmoji.justArrived(message.id, message.timestamp)) 1 else 0)
    }
    com.alphamessenger.ui.emoji.EmojiPrefs.load(androidx.compose.ui.platform.LocalContext.current)
    val side = com.alphamessenger.ui.emoji.EmojiPrefs.stickerDp.dp
    // Decoded at the size it is drawn: a big sticker stays sharp, a small one light.
    val decodePx = with(androidx.compose.ui.platform.LocalDensity.current) { side.roundToPx() }.coerceIn(256, 512)
    val refJson = message.mediaRefJson
    // The same single state a photo reads: the file once it is here.
    val state = com.alphamessenger.media.rememberMediaState(message, mediaActions).toOverlayState(message.isOutgoing)
    val needs = state is com.alphamessenger.transfer.FileUiState.NeedDownload
    LaunchedEffect(message.id, needs) {
        val ref = refJson ?: return@LaunchedEffect
        // A sticker is a few dozen KB: it comes with the message, like text.
        if (needs) mediaActions?.dispatch?.invoke(TransferKey.download(ref), TransferAction.Start, message.id)
    }
    val path = state.readyPath
    // A sticker from an added pack that the pack does not have yet: the pack grew —
    // it updates in the background, no need to open it and wait.
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(message.id) {
        if (!message.isOutgoing) com.alphamessenger.ui.sticker.StickerUi.notice(appCtx, message.stickerLink, refJson)
    }

    // In a channel post the post's frame holds it: no stretching across the post.
    val inPost = com.alphamessenger.ui.channel.LocalChannelPostWidth.current != null
    Column(
        if (inPost) Modifier.padding(8.dp) else Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
        if (showSenderName) {
            Text(message.senderName.orEmpty(), color = senderColor, fontSize = BubbleTokens.senderNameSp.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
        }
        Box(
            // A tap plays a vector sticker again and opens the pack it came from; no pressed square.
            Modifier.size(side).combinedClickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { replay++; onOpenPack() },
                onLongClick = onLongPress,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (path != null) StickerImage(path, Modifier.size(side), decodePx, message.stickerEmoji, replay, live = true)
            else CircularProgressIndicator(color = colors.textMuted, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        }
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.28f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (message.isEdited) com.alphamessenger.ui.chat.bubbles.EditedMark(Color.White.copy(alpha = 0.85f))
            Text(compactTime(message.timestamp), color = Color.White.copy(alpha = 0.85f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
            if (message.isOutgoing) DeliveryTicks(message.status, Color.White)
        }
        BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp))
    }
}
