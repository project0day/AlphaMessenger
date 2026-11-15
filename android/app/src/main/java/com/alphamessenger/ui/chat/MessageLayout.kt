package com.alphamessenger.ui.chat

import com.alphamessenger.ui.theme.outBubble
import com.alphamessenger.ui.theme.outBubbleBrush
import androidx.compose.animation.core.animate
import com.alphamessenger.ui.components.avatarBackdrop
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.launch

/**
 * MessageLayout — the single scaffold for EVERY message type (other messengers
 * pattern). Only [content] differs (text, photo, video, file, voice, album); the
 * frame owns:
 *
 * - Row (full width) with selection highlight + swipe-to-reply gesture
 * - Avatar slot (group chats, incoming)
 * - Tail-shift Box (mid-run bubbles align with tailed bubbles)
 * - content lambda — the only per-type part
 *
 * One scaffold instead of every bubble duplicating its own Row + avatar Box +
 * Column + tail-shift Box.
 *
 * Tree:
 * ```
 * MessageLayout
 * ├── Row [fillMaxWidth, background(selection), pointerInput(swipe), offset, padding]
 * │   ├── Box [avatar — 32dp, clip CircleShape, bg gradient]
 * │   │   └── Image OR Text (initial)
 * │   └── Column [weight 1f]
 * │       └── Box [tail shift padding]
 * │           └── content()  ← differs per message type
 * ```
 */
/** Incoming text here is plain on the chat, not in a bubble (an assistant's answers). */
internal val LocalPlainIncoming = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun MessageLayout(
    message: MessageUi,
    showSender: Boolean,
    isGroup: Boolean,
    colors: AlphaColorScheme,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    isHighlighted: Boolean = false,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onSwipeReply: () -> Unit = {},
    onAvatarClick: () -> Unit = {},
    reactions: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    // Avatar: only for group chats, incoming, first-in-run.
    val showAvatar = isGroup && !message.isOutgoing
    // The chat's avatar layer draws this run's avatar (it moves with the run).
    val showAvatarVisible = showAvatar && showSender && message.id !in LocalLayerAvatars.current

    val onTapNow by androidx.compose.runtime.rememberUpdatedState(onTap)
    val onLongPressNow by androidx.compose.runtime.rememberUpdatedState(onLongPress)
    val onSwipeReplyNow by androidx.compose.runtime.rememberUpdatedState(onSwipeReply)

    // Swipe-to-reply state.
    val swipeOffset = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val swipeScope = rememberCoroutineScope()
    val replyThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    // Crossing the reply threshold is felt, as in the reference apps; once per crossing.
    val swipeView = androidx.compose.ui.platform.LocalView.current
    val replyArmed = remember { booleanArrayOf(false) }

    // A message jumped to lights up, then fades out slowly. Only that one row
    // animates; every other row stays at 0 and draws nothing.
    val glow = androidx.compose.animation.core.animateFloatAsState(
        if (isHighlighted) 1f else 0f,
        androidx.compose.animation.core.tween(if (isHighlighted) 220 else 1200),
        label = "highlight",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Only draw a highlight rect when actually selected/highlighted — a
            // Color.Transparent background still records a drawRect on the
            // RenderThread for EVERY row (issue-draw cost on a big list).
            .then(
                when {
                    isSelected -> Modifier.background(colors.accent.copy(alpha = 0.15f))
                    isHighlighted || glow.value > 0f -> Modifier.drawBehind {
                        drawRect(colors.accent.copy(alpha = 0.24f * glow.value))
                    }
                    else -> Modifier
                }
            )
            .then(
                if (selectionMode) Modifier.pointerInput(message.id) {
                    detectTapGestures(
                        onTap = { onTapNow() },
                        onLongPress = { onLongPressNow() },
                    )
                }
                else Modifier.pointerInput(message.id) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // A down the bubble took is its own (its long press selects); the empty
                        // part of the row selects on a hold here, like a tap beside it opens.
                        val beside = !down.isConsumed
                        val longAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        var last = down.uptimeMillis
                        var held = false
                        var accumX = 0f
                        var accumY = 0f
                        var horizontal: Boolean? = null
                        var released: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        while (true) {
                            val event = if (beside && horizontal == null && !held)
                                withTimeoutOrNull((longAt - last).coerceAtLeast(1L)) { awaitPointerEvent() }
                            else awaitPointerEvent()
                            if (event == null) { held = true; onLongPressNow(); continue }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change.uptimeMillis
                            if (!change.pressed) { released = change; break }
                            if (held) { change.consume(); continue }
                            val delta = change.positionChange()
                            if (horizontal == null) {
                                accumX += delta.x
                                accumY += delta.y
                                if (kotlin.math.abs(accumX) > touchSlop || kotlin.math.abs(accumY) > touchSlop) {
                                    horizontal = kotlin.math.abs(accumX) > kotlin.math.abs(accumY)
                                    if (horizontal == false) return@awaitEachGesture
                                    // Reply is a swipe to the LEFT; a swipe to the right
                                    // belongs to "back" (the screen's left edge).
                                    if (accumX > 0f) return@awaitEachGesture
                                }
                            }
                            if (horizontal == true) {
                                swipeOffset.floatValue =
                                    (swipeOffset.floatValue + delta.x).coerceIn(-replyThresholdPx * 1.5f, 0f)
                                val past = swipeOffset.floatValue < -replyThresholdPx
                                if (past != replyArmed[0]) {
                                    replyArmed[0] = past
                                    if (past) swipeView.performHapticFeedback(
                                        if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                        else android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                                    )
                                }
                                change.consume()
                            }
                        }
                        // A tap BESIDE the bubble (the empty part of the row) acts as a tap on
                        // it: the whole row is the target, easier to hit one-handed. A tap the
                        // bubble itself took (text, link, photo, ▶, reaction) is consumed by it
                        // and does not come here twice.
                        if (horizontal == null && released != null && !released.isConsumed &&
                            released.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                        ) {
                            onTapNow()
                        }
                        if (horizontal == true) {
                            if (swipeOffset.floatValue < -replyThresholdPx) onSwipeReplyNow()
                            replyArmed[0] = false
                            swipeScope.launch {
                                animate(swipeOffset.floatValue, 0f) { v, _ -> swipeOffset.floatValue = v }
                            }
                        }
                    }
                }
            )
            .offset { IntOffset(swipeOffset.floatValue.toInt(), 0) }
            .padding(
                // EQUAL outer margin on both sides so incoming and outgoing sit the same
                // distance from the screen edge — corners never touch the edge.
                start = 10.dp,
                end = 10.dp,
                top = if (com.alphamessenger.ui.channel.LocalChannelPostWidth.current != null) 10.dp
                    else if (message.isConsecutive) 2.dp else 8.dp,
                bottom = if (message.isLastInRun) 3.dp else 2.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        if (showAvatar) {
            if (showAvatarVisible) {
                val grad = remember(message.senderId) { avatarGradientFor(message.senderId) }
                // Decode the peer avatar OFF the UI thread with an LRU cache — a
                // group bubble scrolling into view never blocks a frame on I/O.
                val avatarPath = message.senderAvatarPath
                val avatarBitmap = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                    avatarPath?.let { AvatarBitmapCache.get(it) }, avatarPath,
                ) {
                    val path = avatarPath
                    if (value != null || path.isNullOrBlank()) return@produceState
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        AvatarBitmapCache.get(path) ?: runCatching {
                            val f = java.io.File(path)
                            if (f.exists() && f.length() > 0L)
                                com.alphamessenger.data.MediaVault.decodeFile(path)?.asImageBitmap()
                            else null
                        }.getOrNull()?.also { AvatarBitmapCache.put(path, it) }
                    }
                }.value
                Box(
                    modifier = Modifier
                        .padding(start = 0.dp, end = 4.dp, top = 2.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second)))
                        .pointerInput(Unit) { detectTapGestures(onTap = { onAvatarClick() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().clip(CircleShape),
                        )
                    } else {
                        Text(
                            text = message.senderInitial ?: "?",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else {
                Spacer(Modifier.width(36.dp))
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
        ) {
            // Classic tail lives INSIDE the bubble shape (BubbleShape, one glued Path —
            // tail + body are a single outline, never a detached "comma"). The tail eats
            // tailWidth on its side, so a tailless (mid-run) bubble shifts inward by the
            // same amount here to keep every body edge in one aligned column.
            val postWidth = com.alphamessenger.ui.channel.LocalChannelPostWidth.current
            // The bubble's place on screen, for the menu that opens beside it.
            val reportBounds = LocalBubbleBounds.current
            val tail = hasTail(message)
            val plain = LocalPlainIncoming.current && !message.isOutgoing
            val bubbleRadius = com.alphamessenger.ui.theme.LocalBubbleRadius.current
            val spotShape = remember(postWidth != null, message.isOutgoing, tail, plain, bubbleRadius) {
                if (plain) androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                else if (postWidth != null) androidx.compose.foundation.shape.RoundedCornerShape(com.alphamessenger.ui.channel.CHANNEL_BUBBLE_RADIUS)
                else bubbleShape(outgoing = message.isOutgoing, showTail = tail, radius = bubbleRadius)
            }
            val boundsModifier = if (reportBounds != null)
                Modifier.onGloballyPositioned { reportBounds(BubbleSpot(it.boundsInRoot(), spotShape)) } else Modifier
            if (postWidth != null) {
                // Channel post: no tail, one width for every post, and the comments
                // line inside the same bubble (same colour, a hairline above it).
                val footer = com.alphamessenger.ui.channel.LocalPostFooter.current
                com.alphamessenger.ui.channel.PostFrame(
                    minWidth = com.alphamessenger.ui.channel.LocalChannelPostMinWidth.current ?: postWidth,
                    maxWidth = postWidth,
                    fill = com.alphamessenger.ui.channel.LocalChannelPostFill.current,
                    modifier = boundsModifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(com.alphamessenger.ui.channel.CHANNEL_BUBBLE_RADIUS))
                        .then(
                            colors.outBubbleBrush?.takeIf { message.isOutgoing }?.let { Modifier.background(it) }
                                ?: Modifier.background(if (message.isOutgoing) colors.outBubble else colors.msgIn),
                        ),
                    content = { Box(propagateMinConstraints = true) { content() } },
                    footer = { if (footer != null) Column { com.alphamessenger.ui.channel.PostCommentsRow(footer) } },
                )
            } else Box(
                modifier = Modifier.padding(
                    start = if (!message.isOutgoing && message.isConsecutive && !plain) 7.dp else 0.dp,
                    end = if (message.isOutgoing && message.isConsecutive) 7.dp else 0.dp,
                ).then(boundsModifier),
            ) {
                content()
            }

            // Aggregated reaction chips render UNDER the bubble, inside the same
            // aligned Column so they hug the message's side (End/Start).
            reactions()
        }
    }
}
