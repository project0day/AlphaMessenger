package com.alphamessenger.ui.chat.media.v2.chrome

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "🙏")

/**
 * The viewer's action row, under the seek bar. Reply on the left as a wide
 * target, the reaction button on the RIGHT edge — under the right thumb.
 */
@Composable
fun ChromeActionRow(
    hasMyReaction: Boolean,
    onReply: (() -> Unit)?,
    onReact: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onReply == null && onReact == null) return
    val colors = AlphaTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onReply != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onReply)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.AutoMirrored.Outlined.Reply, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.viewer_reply_hint), color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onReact != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onReact),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AlphaIcons.Outlined.AddReaction, stringResource(R.string.viewer_react),
                    tint = if (hasMyReaction) colors.accent else Color.White,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
    }
}

/** Reactions already on this media: right-aligned pills above the seek bar. */
@Composable
fun ChromeReactionPills(
    reactions: Map<String, Int>,
    mine: Set<String>,
    onToggle: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    val colors = AlphaTheme.colorScheme
    // Never more than one line, however many reactions: the most popular
    // (mine first among equals) and a "+N" for the rest.
    val sorted = reactions.entries.sortedWith(
        compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key in mine },
    )
    val shown = sorted.take(4)
    val rest = sorted.size - shown.size
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    ) {
        if (rest > 0) {
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onMore)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("+$rest", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        shown.forEach { (emoji, count) ->
            val isMine = emoji in mine
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (isMine) colors.accent else Color.White.copy(alpha = 0.16f))
                    .clickable { onToggle(emoji) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 16.sp)
                if (count > 1) {
                    Spacer(Modifier.width(4.dp))
                    Text(count.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Reply composer: while it is open the rest of the bottom bar is gone, so the
 * keyboard lifts only this translucent line — never the whole player.
 */
@Composable
fun ChromeReplyComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AlphaTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(stringResource(R.string.viewer_reply_hint), color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp)
            }
            com.alphamessenger.ui.chat.v2.FormattingToolbarHost(text, onTextChange) { fv, onFv ->
                BasicTextField(
                    value = fv,
                    // Never cut silently: a pasted text stays whole, the counter says what is over.
                    onValueChange = onFv,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // The counter appears near the limit; over it: "−N" in red and send is off.
        val left = REPLY_MAX_CHARS - text.length
        val over = left < 0
        val canSend = text.isNotBlank() && !over
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (left <= REPLY_COUNTER_FROM) {
            Text(
                text = if (over) "−${-left}" else left.toString(),
                color = if (over) colors.danger else Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = if (over) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (canSend) colors.accent else Color.White.copy(alpha = 0.14f))
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AlphaIcons.AutoMirrored.Filled.Send, stringResource(R.string.common_send), tint = Color.White.copy(alpha = if (canSend) 1f else 0.5f), modifier = Modifier.size(20.dp))
        }
        }
    }
}

/** One message (limit). */
const val REPLY_MAX_CHARS = 4096
private const val REPLY_COUNTER_FROM = 200

/**
 * Reaction picker as a bottom sheet. It is its own window above the viewer, so
 * scrolling the emoji grid never fights the viewer's swipe/drag gestures.
 */
@Composable
fun ReactionPickerSheet(
    visible: Boolean,
    mine: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    policy: com.alphamessenger.ui.chat.ReactionPolicy = com.alphamessenger.ui.chat.ReactionPolicy.ALL,
) {
    val colors = AlphaTheme.colorScheme
    val quick = policy.quick(QUICK).take(7)
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.slideOutVertically { it },
        ) {
        // ONE colour from the handle down through the navigation-bar area.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(colors.bgSecondary)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textMuted.copy(alpha = 0.5f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                quick.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (emoji in mine) colors.accent.copy(alpha = 0.28f) else colors.bgTertiary)
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 22.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            // All emoji, or — when the group allows only some — the rest of that set.
            if (policy.anyEmoji) {
                com.alphamessenger.ui.chat.v2.EmojiPanelV2(
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    colors = colors,
                    onPick = onPick,
                    onBackspace = {},
                    showTabs = false,
                )
            } else if (policy.allowed.size > quick.size) {
                com.alphamessenger.ui.chat.ReactionGrid(policy.allowed.drop(quick.size), mine, colors, onPick)
            }
        }
        }
    }
}
