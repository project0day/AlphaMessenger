package com.alphamessenger.ui.channel

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.chat.ChatBubblePeer
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.util.TrustedTime

/*
 * A post's discussion is the chat screen showing the post's comment thread:
 * the same bubbles, reactions, replies, media and menus as any chat. This file
 * holds what only a discussion has — the post on top, the reason you may not
 * write, the moderator's actions and the "new comments" mark in the feed.
 */

/** The discussion this chat screen shows. */
data class Discussion(
    val postId: String,
    /** The post itself (shown on top); null until it is found. */
    val post: MessageUi? = null,
    val cfg: ChannelCfg = ChannelCfg(on = true),
    /** The core's answer to "may I write a comment here now?". */
    val status: WriteStatus = WriteStatus.OK,
    /** The same for a voice note (the mic is refused before recording starts). */
    val voice: WriteStatus = WriteStatus.OK,
    /** Owner, admin or moderator: may delete comments and restrict their authors. */
    val canModerate: Boolean = false,
    /** Live comments (not deleted). */
    val count: Int = 0,
    /** The thread has been read from the core (the list may anchor to it). */
    val loaded: Boolean = false,
) {
    /** The composer is replaced by the reason (slow mode keeps it: you may type the next one). */
    val locked: Boolean get() = !status.ok && status.reason != "slow_mode"
}

/** The post handed from the feed to its discussion, so the first frame shows it. */
object DiscussionHandoff {
    private val posts = java.util.concurrent.ConcurrentHashMap<String, MessageUi>()
    fun put(post: MessageUi) { posts[post.id.substringBefore("#al")] = post }
    fun take(postId: String): MessageUi? = posts.remove(postId)
}

/**
 * How many comments by others under each post this device has seen — the feed
 * shows the rest as "+N" (our own are never new). Local only: what one reads
 * is nobody else's business.
 */
class DiscussionSeen(context: Context) {
    private val prefs = context.getSharedPreferences("discussion_seen", Context.MODE_PRIVATE)
    fun seen(chatId: String, postId: String): Int = prefs.getInt("$chatId:$postId", 0)
    fun markSeen(chatId: String, postId: String, count: Int) {
        if (seen(chatId, postId) != count) prefs.edit().putInt("$chatId:$postId", count).apply()
    }
}

/** What a moderator does to a comment or its author. */
sealed interface CommentAction {
    data class Delete(val m: MessageUi) : CommentAction
    /** [hours] = 0 → for good. */
    data class Mute(val m: MessageUi, val hours: Long) : CommentAction
    data class Unmute(val m: MessageUi) : CommentAction
    data class Ban(val m: MessageUi) : CommentAction
    data class DeleteAll(val m: MessageUi) : CommentAction
}

/**
 * Why a comment cannot be written now — drawn in place of the composer, at its
 * height. A restriction that ends today counts down to its end; the composer
 * comes back by itself when the core says so.
 */
@Composable
fun DiscussionLockStrip(colors: AlphaColorScheme, status: WriteStatus) {
    var now by remember { mutableLongStateOf(TrustedTime.nowSeconds()) }
    val counting = status.reason == "muted" && status.value - now in 1..(24 * 3600)
    LaunchedEffect(counting) {
        while (counting) { kotlinx.coroutines.delay(1000); now = TrustedTime.nowSeconds() }
    }
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(colors.bgTertiary).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(AlphaIcons.Outlined.Lock, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            status.text(), color = colors.textSecondary, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (counting) {
            val left = (status.value - now).coerceAtLeast(0)
            Spacer(Modifier.width(10.dp))
            Text(
                "%d:%02d:%02d".format(left / 3600, left % 3600 / 60, left % 60),
                color = colors.accent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The post above its discussion: who and when, its words and its picture. */
@Composable
fun DiscussionPostCard(post: MessageUi, channelName: String, colors: AlphaColorScheme, onClick: () -> Unit) {
    val words = (post.mediaCaption?.takeIf { post.isMedia } ?: post.text).trim()
    val thumb = post.albumItems?.firstOrNull() ?: post.takeIf { it.isMedia }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.accent.copy(alpha = 0.12f))
            .border(1.dp, colors.accent.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$channelName · ${com.alphamessenger.ui.chat.compactTime(post.timestamp)}",
                color = colors.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (words.isNotEmpty()) {
                Spacer(Modifier.size(3.dp))
                Text(words, color = colors.textPrimary, fontSize = 14.5.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else if (thumb != null) {
                Spacer(Modifier.size(3.dp))
                Text(stringResource(R.string.discussion_post_media), color = colors.textSecondary, fontSize = 14.5.sp)
            }
        }
        if (thumb != null && thumb.mediaType in setOf("image", "photo", "video", "album")) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(colors.bgTertiary), contentAlignment = Alignment.Center) {
                val path = thumb.localMediaPath
                if (path != null && thumb.mediaType != "video") {
                    coil3.compose.AsyncImage(java.io.File(path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else com.alphamessenger.ui.chat.rememberPosterBitmap(thumb.mediaPosterB64)?.let {
                    Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (thumb.mediaType == "video") Icon(AlphaIcons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Up to three overlapping avatars of the latest commenters (feed footer). */
@Composable
fun CommenterAvatars(senders: List<String>, profiles: Map<String, ChatBubblePeer>, ring: Color) {
    if (senders.isEmpty()) return
    Box(Modifier.width((22 + 16 * (senders.size - 1)).dp)) {
        senders.forEachIndexed { i, id ->
            val p = profiles[id]
            val (a, b) = com.alphamessenger.ui.chat.avatarGradientFor(id)
            Box(
                Modifier.offset(x = (16 * i).dp).size(22.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(a, b)))
                    .border(1.5.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val path = p?.avatarPath?.takeIf { it.isNotBlank() }
                if (path != null) {
                    coil3.compose.AsyncImage(java.io.File(path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(initialsOf(p?.displayName.orEmpty()), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** The discussion's header: back, "Discussion" and how many comments under which channel. */
@Composable
fun DiscussionTopBar(subtitle: String, colors: AlphaColorScheme, onBack: () -> Unit, accent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth()
            .padding(top = com.alphamessenger.ui.chat.chatStatusPad())
            .height(58.dp)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = colors.textPrimary)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.discussion_title), color = colors.textPrimary, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 1,
            )
            Text(
                subtitle, color = if (accent) colors.accent else colors.textSecondary, fontSize = 12.sp, lineHeight = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A moderator's part of a comment's menu: restrict its author (1 hour, a day,
 * a week, for good) or lift the restriction, remove all of the author's
 * comments here, remove the author from the channel, delete this comment.
 */
@Composable
fun CommentModerationSection(
    authorName: String,
    /** Until when the author may not write (unix secs; 0 = free). */
    mutedUntil: Long,
    colors: AlphaColorScheme,
    onAction: (ModerationChoice) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (!com.alphamessenger.ui.chat.LocalFloatingTop.current) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = colors.alphaBorder)
    Text(
        authorName.uppercase(), color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
    )
    if (mutedUntil > 0) {
        ModRow(AlphaIcons.Outlined.LockOpen, stringResource(R.string.cmt_unmute), colors.accent) { onAction(ModerationChoice.Unmute) }
    } else {
        ModRow(AlphaIcons.Outlined.CommentsDisabled, stringResource(R.string.cmt_mute), colors.textPrimary) { picking = !picking }
        if (picking) Row(
            Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(1L to R.string.cmt_mute_1h, 24L to R.string.cmt_mute_24h, 168L to R.string.cmt_mute_7d, 0L to R.string.cmt_mute_forever)
                .forEach { (hours, label) ->
                    Text(
                        stringResource(label), color = colors.textPrimary, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(colors.bgTertiary)
                            .clickable { onAction(ModerationChoice.Mute(hours)) }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    )
                }
        }
    }
    ModRow(AlphaIcons.Outlined.DeleteSweep, stringResource(R.string.cmt_delete_all), colors.textPrimary) { onAction(ModerationChoice.DeleteAll) }
    ModRow(AlphaIcons.Outlined.Block, stringResource(R.string.cmt_ban), colors.danger) { onAction(ModerationChoice.Ban) }
    ModRow(AlphaIcons.Outlined.Delete, stringResource(R.string.cmt_delete), colors.danger) { onAction(ModerationChoice.Delete) }
}

/** What the moderator picked in [CommentModerationSection]. */
sealed interface ModerationChoice {
    data class Mute(val hours: Long) : ModerationChoice
    data object Unmute : ModerationChoice
    data object DeleteAll : ModerationChoice
    data object Ban : ModerationChoice
    data object Delete : ModerationChoice

    fun on(m: MessageUi): CommentAction = when (this) {
        is Mute -> CommentAction.Mute(m, hours)
        Unmute -> CommentAction.Unmute(m)
        DeleteAll -> CommentAction.DeleteAll(m)
        Ban -> CommentAction.Ban(m)
        Delete -> CommentAction.Delete(m)
    }
}

@Composable
private fun ModRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}

/** "Иван Петров" → "ИП", "Иван" → "И": the first letters of the first two words. */
fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
        .joinToString("")
