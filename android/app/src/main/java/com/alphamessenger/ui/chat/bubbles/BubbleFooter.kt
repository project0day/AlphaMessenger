package com.alphamessenger.ui.chat.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.chat.MessageStatus
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.compactTime
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * Alpha Bubble 2.0 — the ONE footer for EVERY bubble.
 *
 * Time · "edited" · delivery ticks, rendered identically for text / image / video /
 * album / voice / file / reply / forward. No bubble builds its own footer row and
 * no bubble draws its own ticks — they all call [BubbleFooter] (which owns
 * [DeliveryTicks]). Two placements only:
 *   • inline  — a plain row under the content (uses the bubble's content colour);
 *   • onMedia — a translucent pill over the media's bottom-end corner.
 *
 * Every size/gap/alpha comes from [BubbleTokens]; there are no magic numbers here.
 */
@Composable
fun BubbleFooter(
    message: MessageUi,
    colors: AlphaColorScheme,
    contentColor: Color,
    onMedia: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val row: @Composable RowScope.() -> Unit = {
        if (message.isEdited) {
            com.alphamessenger.ui.chat.bubbles.EditedMark(contentColor.copy(alpha = if (onMedia) BubbleTokens.footerOnMediaEditedAlpha else BubbleTokens.footerEditedAlpha))
            Spacer(Modifier.width(BubbleTokens.footerEditedGap))
        }
        com.alphamessenger.ui.channel.PostViews(if (onMedia) contentColor else contentColor.copy(alpha = BubbleTokens.footerTimeAlpha))
        Text(
            text = compactTime(message.timestamp),
            color = if (onMedia) contentColor else contentColor.copy(alpha = BubbleTokens.footerTimeAlpha),
            fontSize = BubbleTokens.footerTimeSp.sp,
        )
        if (message.isOutgoing) {
            Spacer(Modifier.width(BubbleTokens.footerTickGap))
            DeliveryTicks(message.status, contentColor)
        }
    }
    if (onMedia) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(BubbleTokens.footerPillCorner))
                .background(BubbleTokens.footerPillBg)
                .padding(horizontal = BubbleTokens.footerPillPadH, vertical = BubbleTokens.footerPillPadV),
            verticalAlignment = Alignment.CenterVertically,
            content = row,
        )
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, content = row)
    }
}

/**
 * The SINGLE delivery-state indicator. Every bubble routes
 * through this — there is no per-type copy. [contentColor] is the bubble's footer
 * colour while pending; read ticks take the read colour (see [readTickColor]).
 */
@Composable
fun DeliveryTicks(status: MessageStatus, contentColor: Color) {
    // A channel post: ticks only for the admin who posted it.
    if (com.alphamessenger.ui.channel.channelHidesTicks()) return
    if (com.alphamessenger.ui.channel.LocalInDiscussion.current && status != MessageStatus.SENDING) return
    val label = when (status) {
        MessageStatus.SENDING -> R.string.status_sending
        MessageStatus.SENT -> R.string.status_sent
        MessageStatus.DELIVERED -> R.string.status_delivered
        MessageStatus.READ -> R.string.status_read
    }
    val colors = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
    TickMark(
        state = status.tickState(),
        color = if (status == MessageStatus.READ) {
            readTickColor(androidx.compose.ui.graphics.lerp(colors.msgOutStart, colors.msgOutEnd, 0.5f))
        } else {
            contentColor.copy(alpha = BubbleTokens.tickAlpha)
        },
        description = stringResource(label),
    )
}

/** "Edited": a small pencil by the time; read aloud as the word. */
@Composable
fun EditedMark(color: androidx.compose.ui.graphics.Color, endGap: androidx.compose.ui.unit.Dp = 0.dp) {
    androidx.compose.material3.Icon(
        com.alphamessenger.ui.icons.AlphaIcons.Outlined.Edit,
        contentDescription = stringResource(R.string.channel_edited),
        tint = color,
        modifier = Modifier.padding(end = endGap).size(BubbleTokens.footerEditIcon),
    )
}
