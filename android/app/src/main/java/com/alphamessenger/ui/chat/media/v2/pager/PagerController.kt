package com.alphamessenger.ui.chat.media.v2.pager

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.alphamessenger.ui.chat.media.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The single owner of "which page is current".
 *
 * The scroll position physically lives in Compose [PagerState] — otherwise the
 * swipe and programmatic navigation would be two sources of truth. Only the
 * controller may read or change it; everyone else gets the index and three
 * commands.
 *
 * The controller knows nothing about the transition, window or chrome — all it
 * can do is page.
 */
@Stable
class PagerController internal constructor(
    val pages: PagerPages,
    internal val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    val items: List<MediaItem> get() = pages.items
    val count: Int get() = pages.size

    val currentIndex: Int get() = pagerState.currentPage
    val current: MediaItem? get() = items.getOrNull(currentIndex)

    fun next() = jumpTo(currentIndex + 1)

    fun prev() = jumpTo(currentIndex - 1)

    /** Stay within the set bounds; no looping. */
    fun jumpTo(index: Int) {
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        if (target == currentIndex) return
        scope.launch { runCatching { pagerState.animateScrollToPage(target) } }
    }
}

@Composable
fun rememberPagerController(pages: PagerPages): PagerController {
    val pagerState = rememberPagerState(initialPage = pages.firstPage) { pages.size }
    val scope = rememberCoroutineScope()
    // Recreate only when the page SET changes, otherwise recomposition would
    // reset the current page to the initial one.
    val key = pages.items.firstOrNull()?.id.orEmpty() + "/" + pages.size
    return remember(key) { PagerController(pages, pagerState, scope) }
}
