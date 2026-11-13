package com.alphamessenger.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

/** The ONLY thing in the chat allowed to move the message list. Callers own the
 *  decision ("should I scroll?"); the coordinator only knows how to execute an
 *  intent. It holds no state and no message/media/typing logic. */
sealed interface ScrollIntent {
    /** Keep the newest message pinned to the bottom (index 0). */
    object PinToBottom : ScrollIntent
    /** A message came in while the reader is at the bottom: the list slides up by it. */
    object Arrive : ScrollIntent
    /** The down button: a glide when the bottom is near, else straight there. */
    object ToBottom : ScrollIntent
    /** The start of the history, once all of it is in the list. */
    object ToTop : ScrollIntent
    /** Animate to a message by index (menu, deep-link, reply quote). */
    data class JumpTo(val index: Int) : ScrollIntent
}

object ScrollCoordinator {
    suspend fun execute(listState: LazyListState, intent: ScrollIntent) {
        when (intent) {
            ScrollIntent.PinToBottom -> {
                awaitFrame(); awaitFrame()
                runCatching { listState.scrollToItem(0) }
            }
            ScrollIntent.Arrive -> {
                // The list keeps its anchor, so the new message is laid out below it, under
                // the panel; the list then moves up by exactly that much in one short slide.
                awaitFrame()
                val newest = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                if (newest != null && newest.offset < 0) runCatching {
                    listState.animateScrollBy(newest.offset.toFloat(), androidx.compose.animation.core.tween(ARRIVE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
                runCatching { listState.scrollToItem(0) }
            }
            ScrollIntent.ToBottom -> {
                // A glide to an item not laid out yet goes by an estimate and corrects
                // itself at the end — a stop on the last bubbles. So the list is put a
                // known distance above the newest message at once and glides exactly that
                // far: the way is one screen of bubbles already built, nothing to find.
                val viewport = listState.layoutInfo.viewportSize.height
                val glide = (viewport * TO_BOTTOM_GLIDE_SCREENS).toInt()
                // The newest message on screen: the way left is known exactly (as for Arrive).
                val newest = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                val left = if (newest != null) -newest.offset else {
                    runCatching { listState.scrollToItem(0, glide) }
                    awaitFrame()
                    glide
                }
                if (left > 0) runCatching {
                    // Length-scaled: a short way is quick, a screen takes a little longer.
                    val ms = (((left.toFloat() / viewport.coerceAtLeast(1)) + 1f) * 200f).toInt().coerceIn(300, 1300)
                    // Bubbles on the way can change height mid-glide (a picture arrives, a
                    // preview opens): a fixed distance then ends short and the rest is a
                    // jump. The way is re-measured each frame the newest message is seen.
                    var total = left.toFloat()
                    var done = 0f
                    listState.scroll {
                        androidx.compose.animation.core.Animatable(0f).animateTo(1f, androidx.compose.animation.core.tween(ms, easing = EASE_OUT_QUINT)) {
                            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                                ?.let { total = done - it.offset }
                            val step = value * total - done
                            if (step != 0f) done -= scrollBy(-step)
                        }
                    }
                }
                runCatching { listState.scrollToItem(0) }
            }
            ScrollIntent.ToTop -> {
                // Called as the grown list is committed: it is laid out a frame later.
                awaitFrame()
                runCatching { listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
            }
            is ScrollIntent.JumpTo -> {
                // Gliding over many messages builds every bubble on the way — a heavy
                // one (album, video) stalls the glide for a moment. Far away: jump to
                // a few messages short of the target, then glide the rest.
                val target = intent.index.coerceAtLeast(0)
                val from = listState.firstVisibleItemIndex
                if (kotlin.math.abs(from - target) > GLIDE_ITEMS) {
                    val near = if (from > target) target + GLIDE_ITEMS else (target - GLIDE_ITEMS).coerceAtLeast(0)
                    runCatching { listState.scrollToItem(near) }
                }
                runCatching { listState.animateScrollToItem(target) }
                // The message lands in the middle of the screen, not at its edge under
                // the composer: in the reversed list the start is the bottom.
                runCatching {
                    val info = listState.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return@runCatching
                    val view = info.viewportEndOffset - info.viewportStartOffset - info.beforeContentPadding - info.afterContentPadding
                    val want = ((view - item.size) / 2).coerceAtLeast(0)
                    val delta = item.offset - want
                    if (kotlin.math.abs(delta) > 4) listState.animateScrollBy(delta.toFloat())
                }
            }
        }
    }

    private suspend fun awaitFrame() = withFrameNanos { }

    /** How many messages a jump still glides over at its end. */
    private const val GLIDE_ITEMS = 6
    /** How far the down button glides at its end, in screens. */
    private const val TO_BOTTOM_GLIDE_SCREENS = 1f
    /** Fast, then a long gentle settle. */
    private val EASE_OUT_QUINT = androidx.compose.animation.core.CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    /** How long an incoming message slides in. */
    private const val ARRIVE_MS = 180
}
