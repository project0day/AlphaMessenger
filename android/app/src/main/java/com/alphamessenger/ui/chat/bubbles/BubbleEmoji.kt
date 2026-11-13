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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.compactTime
import com.alphamessenger.ui.emoji.AnimatedEmojiView
import com.alphamessenger.ui.theme.AlphaColorScheme

/** How many times a big emoji plays when it comes into view (a tap plays it again). */
private const val EMOJI_PLAYS = 3

/** The plain emoji while no animation is here: as large as the emoji font stays sharp. */
private const val PLAIN_EMOJI_SP = 56

/**
 * A message that is one emoji and nothing else: the emoji alone and large, no
 * bubble, animated when the set has it. A tap plays it again, the long press
 * opens the usual menu.
 */
@Composable
internal fun EmojiBubble(
    message: MessageUi,
    emoji: String,
    showSenderName: Boolean,
    senderColor: Color,
    colors: AlphaColorScheme,
    onLongPress: () -> Unit,
) {
    var replay by remember(message.id) { mutableIntStateOf(0) }
    com.alphamessenger.ui.emoji.EmojiPrefs.load(androidx.compose.ui.platform.LocalContext.current)
    val side = com.alphamessenger.ui.emoji.EmojiPrefs.emojiDp
    // Just sent or received: it plays once whatever the setting, then waits for a tap.
    val arrived = remember(message.id) { com.alphamessenger.ui.emoji.AnimatedEmoji.justArrived(message.id, message.timestamp) }
    val inPost = com.alphamessenger.ui.channel.LocalChannelPostWidth.current != null
    Column(
        if (inPost) Modifier.padding(8.dp) else Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        if (showSenderName) {
            Text(message.senderName.orEmpty(), color = senderColor, fontSize = BubbleTokens.senderNameSp.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
        }
        Box(
            // No pressed square over it: the emoji answers a tap by playing again.
            Modifier.combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { replay++ },
                onLongClick = onLongPress,
            ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedEmojiView(
                emoji, side.dp, Modifier.size(side.dp), plays = EMOJI_PLAYS, replay = replay,
                autoPlay = com.alphamessenger.ui.emoji.EmojiPrefs.autoPlay || arrived,
                fallbackModifier = Modifier.padding(4.dp),
                fallback = { Text(emoji, fontSize = (PLAIN_EMOJI_SP * side / com.alphamessenger.ui.emoji.EmojiPrefs.EMOJI_DEFAULT).sp) },
            )
        }
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.28f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (message.isEdited) com.alphamessenger.ui.chat.bubbles.EditedMark(Color.White.copy(alpha = 0.85f))
            Text(compactTime(message.timestamp), color = Color.White.copy(alpha = 0.85f), fontSize = BubbleTokens.footerTimeSp.sp)
            if (message.isOutgoing) DeliveryTicks(message.status, Color.White)
        }
        BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp))
    }
}
