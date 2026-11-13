package com.alphamessenger.ui.channel

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R

/*
 * A channel post is a chat bubble drawn the channel way: no tail, one width
 * for every post, views next to the time and — inside the same bubble, under
 * a hairline — the line into its comments.
 */

/** Views of the post being drawn; null outside a channel (nothing is shown). */
val LocalPostViews = compositionLocalOf<Int?> { null }

/** Forwards of the post being drawn (shown when there are any). */
val LocalPostForwards = compositionLocalOf { 0 }

/** The comments line of the post; null when comments are off or outside a channel. */
val LocalPostFooter = compositionLocalOf<PostFooter?> { null }

/** Width of a channel post bubble (its maximum); null outside a channel. */
val LocalChannelPostWidth = compositionLocalOf<Dp?> { null }

/** The narrowest a text post gets (so its time, views and comments line fit). */
val LocalChannelPostMinWidth = compositionLocalOf<Dp?> { null }

/** The post's media (photo, video, album) spans the whole post width. */
val LocalChannelPostFill = compositionLocalOf { false }

/** We sent this post ourselves (only then delivery ticks are shown in a channel). */
val LocalPostOwn = compositionLocalOf { false }

/** A post's comments: receipts are off in channels, so a sent comment shows no ticks. */
val LocalInDiscussion = compositionLocalOf { false }

/** In a channel delivery ticks are for the admin who posted it — nobody else. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun channelHidesTicks(): Boolean = LocalChannelPostWidth.current != null && !LocalPostOwn.current

/**
 * The channel bubble's frame: [content] between [minWidth] and [maxWidth]
 * (exactly [maxWidth] when [fill]), then [footer] exactly as wide. A plain
 * measure — no intrinsics, so any bubble works inside.
 */
@Composable
fun PostFrame(
    minWidth: Dp,
    maxWidth: Dp,
    fill: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(contents = listOf(content, footer), modifier = modifier) { (top, bottom), constraints ->
        val maxW = maxWidth.roundToPx().coerceAtMost(constraints.maxWidth)
        val minW = if (fill) maxW else minWidth.roundToPx().coerceAtMost(maxW)
        val tops = top.map { it.measure(androidx.compose.ui.unit.Constraints(minWidth = minW, maxWidth = maxW)) }
        val w = (tops.maxOfOrNull { it.width } ?: minW).coerceIn(minW, maxW)
        val bottoms = bottom.map { it.measure(androidx.compose.ui.unit.Constraints.fixedWidth(w)) }
        layout(w, tops.sumOf { it.height } + bottoms.sumOf { it.height }) {
            var y = 0
            tops.forEach { it.placeRelative(0, y); y += it.height }
            bottoms.forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}

class PostFooter(
    val comments: Int,
    val textColor: Color,
    val onClick: () -> Unit,
    val closed: Boolean = false,
    /** Comments this device has not seen yet. */
    val unread: Int = 0,
    /** Latest commenters (sender ids, newest first) and how to draw them. */
    val senders: List<String> = emptyList(),
    val profiles: Map<String, com.alphamessenger.ui.chat.ChatBubblePeer> = emptyMap(),
    /** The bubble colour (rings the avatars). */
    val bubbleColor: Color = Color.Transparent,
)

/** ↪ forwards and 👁 views before the time in a bubble's footer. */
@Composable
fun PostViews(color: Color) {
    val n = LocalPostViews.current
    val f = LocalPostForwards.current
    if (n != null && f > 0) {
        Icon(AlphaIcons.AutoMirrored.Outlined.Reply, stringResource(R.string.channel_forwards), tint = color,
            modifier = Modifier.size(14.dp).graphicsLayer { scaleX = -1f })
        Spacer(Modifier.width(2.dp))
        Text(compactCount(f), color = color, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
    }
    if (n != null) {
        Icon(AlphaIcons.Outlined.Visibility, stringResource(R.string.channel_views), tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(2.dp))
        Text(compactCount(n), color = color, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
    }
}

/** Inside the bubble, under the time: a hairline, then "N comments ›" — or,
 *  in a narrow post (one emoji, one word), just the icon, the count and ›. */
@Composable
fun PostCommentsRow(footer: PostFooter) {
    HorizontalDivider(thickness = 0.5.dp, color = footer.textColor.copy(alpha = 0.18f))
    androidx.compose.foundation.layout.BoxWithConstraints {
    val narrow = maxWidth < 160.dp
    Row(
        Modifier.fillMaxWidth().clickable(onClick = footer.onClick)
            .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!narrow && !footer.closed && footer.senders.isNotEmpty()) {
            CommenterAvatars(footer.senders, footer.profiles, footer.bubbleColor)
        } else {
            Icon(if (footer.closed) AlphaIcons.Outlined.Lock else AlphaIcons.Outlined.ChatBubbleOutline, null, tint = footer.textColor, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                narrow -> if (footer.comments > 0) compactCount(footer.comments) else ""
                footer.closed -> stringResource(R.string.cmt_closed_short)
                footer.comments > 0 -> pluralStringResource(R.plurals.channel_comments_n, footer.comments, footer.comments)
                else -> stringResource(R.string.channel_comment_action)
            },
            color = footer.textColor, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (footer.unread > 0 && !narrow) {
            Text(
                "+" + compactCount(footer.unread),
                color = footer.bubbleColor.takeIf { it != Color.Transparent } ?: footer.textColor,
                fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(footer.textColor)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Icon(AlphaIcons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.channel_comment_action),
            tint = footer.textColor.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
    }
    }
}

/** Corner of a channel post bubble (no tail). */
val CHANNEL_BUBBLE_RADIUS = 20.dp
