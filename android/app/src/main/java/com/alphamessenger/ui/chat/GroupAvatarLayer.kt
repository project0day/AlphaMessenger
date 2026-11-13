package com.alphamessenger.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.components.avatarBackdrop

/**
 * Ids of the messages whose avatar the layer draws (the oldest of each run):
 * their own row leaves the avatar's place empty.
 */
val LocalLayerAvatars = compositionLocalOf<Set<String>> { emptySet() }

/** Height of the date divider above a message, where it has one (set as it lays out). */
object AvatarLayerDividers {
    val heightPx = java.util.concurrent.ConcurrentHashMap<String, Int>()
}


/** The runs that get a moving avatar: incoming ones a bubble row starts. */
fun avatarRuns(rendered: List<MessageUi>, eligible: (MessageUi) -> Boolean): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    var i = 0
    while (i < rendered.size) {
        var j = i
        while (j + 1 < rendered.size && rendered[j].isConsecutive) j++
        if (eligible(rendered[j])) out.add(i to j)
        i = j + 1
    }
    return out
}

/**
 * The avatars of a group chat, over the list: each one starts beside the first
 * message of its run, stays under the header while the run passes, and when the
 * run ends it shrinks into the run's last message like a drop. Positions are
 * read from the list's layout during placement, so scrolling never recomposes.
 */
@Composable
fun GroupAvatarLayer(
    listState: LazyListState,
    rendered: List<MessageUi>,
    runs: List<Pair<Int, Int>>,
    patch: (MessageUi) -> MessageUi,
    topLimit: Dp,
    onAvatarClick: (MessageUi) -> Unit,
) {
    val density = LocalDensity.current
    val avPx = with(density) { AVATAR.toPx() }
    val leftPx = with(density) { 10.dp.roundToPx() }
    val headGapPx = with(density) { 10.dp.toPx() }  // row top padding 8dp + avatar 2dp
    val tailGapPx = with(density) { 3.dp.toPx() }   // last row's bottom padding
    val limitPx = with(density) { (topLimit + 6.dp).toPx() }
    val idToIndex = remember(rendered) { HashMap<Any, Int>(rendered.size * 2).also { m -> rendered.forEachIndexed { i, x -> m[x.id] = i } } }
    val runOf = remember(runs, rendered.size) {
        IntArray(rendered.size) { -1 }.also { a -> runs.forEachIndexed { r, (n, o) -> for (k in n..o) a[k] = r } }
    }
    val click by rememberUpdatedState(onAvatarClick)

    // Which runs are on screen: changes only when one comes or goes.
    val visible by remember(runs, idToIndex, runOf) {
        derivedStateOf {
            val set = sortedSetOf<Int>()
            for (item in listState.layoutInfo.visibleItemsInfo) {
                val idx = idToIndex[item.key] ?: continue
                val r = runOf.getOrElse(idx) { -1 }
                if (r >= 0) set.add(r)
            }
            set.toList()
        }
    }

    for (r in visible) {
        val (newest, oldest) = runs.getOrNull(r) ?: continue
        val head = rendered.getOrNull(oldest) ?: continue
        key(head.id) {
            val shown = patch(head)
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, p.height) {
                            val info = listState.layoutInfo
                            val h = info.viewportSize.height.toFloat()
                            val before = info.beforeContentPadding
                            var top = Float.NEGATIVE_INFINITY   // run start above the screen
                            var bottom = Float.POSITIVE_INFINITY // run end below the screen
                            for (it in info.visibleItemsInfo) {
                                val idx = idToIndex[it.key] ?: continue
                                val itemTop = h - before - it.offset - it.size
                                if (idx == oldest) top = itemTop + (AvatarLayerDividers.heightPx[head.id] ?: 0) + headGapPx
                                if (idx == newest) bottom = itemTop + it.size - tailGapPx
                            }
                            var y = maxOf(top, limitPx)
                            // How far the run's end has gone past the header, in avatar heights.
                            val p0 = ((limitPx - (bottom - avPx)) / avPx).coerceAtLeast(0f)
                            var s = 1f
                            if (p0 > 0f) {
                                s = (1f - p0).coerceAtLeast(0f)
                                y = bottom - avPx * s.coerceAtLeast(0.05f)
                            } else {
                                y = minOf(y, bottom - avPx)
                            }
                            if (s <= 0.02f || y.isInfinite()) return@layout
                            p.placeWithLayer(leftPx, y.toInt()) {
                                scaleX = s; scaleY = s
                                alpha = (0.4f + 0.6f * s)
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                        }
                    }
                    .size(AVATAR)
                    .clip(CircleShape)
                    .avatarBackdrop(Brush.linearGradient(avatarGradientFor(shown.senderId).let { listOf(it.first, it.second) }))
                    .pointerInput(shown.id) { detectTapGestures(onTap = { click(shown) }) },
                contentAlignment = Alignment.Center,
            ) {
                val path = shown.senderAvatarPath
                val bitmap = produceState(path?.let { AvatarBitmapCache.get(it) }, path) {
                    if (value != null || path.isNullOrBlank()) return@produceState
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        AvatarBitmapCache.get(path) ?: runCatching {
                            val f = java.io.File(path)
                            if (f.exists() && f.length() > 0L) com.alphamessenger.data.MediaVault.decodeFile(path)?.asImageBitmap() else null
                        }.getOrNull()?.also { AvatarBitmapCache.put(path, it) }
                    }
                }.value
                if (bitmap != null) {
                    Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.size(AVATAR).clip(CircleShape))
                } else {
                    Text(shown.senderInitial ?: "?", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private val AVATAR = 32.dp
