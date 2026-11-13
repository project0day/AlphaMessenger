package com.alphamessenger.ui.chat.media.v2.pager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.media3.exoplayer.ExoPlayer
import com.alphamessenger.R
import com.alphamessenger.ui.chat.media.MediaItem

/**
 * Viewer pages — and nothing else.
 *
 * The layer never moves: no offset, scale, rotation or intermediate alpha.
 * All [visible] can do is show the pages or hide them entirely while the
 * separate transition frame flies on top — which is what makes the transition
 * and the window replaceable without touching paging.
 *
 * `userScrollEnabled` is not toggled on the fly: flipping it mid-gesture breaks
 * the pinch. The right to swipe is fixed once by the page set.
 *
 * Stable `key` by [MediaItem.id]: rebuilding the list does not remount a page
 * or recreate its player.
 */
@Composable
fun PagerLayer(
    controller: PagerController,
    source: PagerMediaSource,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    fillScreen: Boolean = false,
    showPlayButton: Boolean = true,
    /** The only item that auto-plays: the one the viewer was opened from. */
    autoPlayId: String? = null,
    onPlayerChanged: (ExoPlayer?) -> Unit = {},
    /**
     * Page wrapper: the coordinator uses it to insert the frame-display layer,
     * which the pager and the renderers know nothing about.
     */
    transform: @Composable (isCurrent: Boolean, content: @Composable () -> Unit) -> Unit =
        { _, content -> content() },
) {
    HorizontalPager(
        state = controller.pagerState,
        modifier = modifier.fillMaxSize().alpha(if (visible) 1f else 0f),
        userScrollEnabled = controller.pages.allowsPaging,
        key = { index -> controller.items.getOrNull(index)?.id ?: index },
    ) { page ->
        val item = controller.items.getOrNull(page) ?: return@HorizontalPager
        val current = controller.currentIndex
        transform(page == current) {
            MediaPage(
                item = item,
                isCurrent = page == controller.currentIndex,
                surfaceVisible = visible,
                autoPlay = autoPlayId != null && item.id == autoPlayId,
                source = source,
                fillScreen = fillScreen,
                showPlayButton = showPlayButton,
                onPlayerChanged = onPlayerChanged,
            )
        }
    }
}

/** Renderer choice by content type. The page owns no chrome and no gestures. */
@Composable
private fun MediaPage(
    item: MediaItem,
    isCurrent: Boolean,
    surfaceVisible: Boolean,
    autoPlay: Boolean,
    source: PagerMediaSource,
    fillScreen: Boolean,
    showPlayButton: Boolean,
    onPlayerChanged: (ExoPlayer?) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (item.kind) {
            MediaItem.Kind.IMAGE, MediaItem.Kind.GIF -> ImageRenderer(item, source)
            MediaItem.Kind.VIDEO -> VideoRenderer(
                item = item,
                isCurrent = isCurrent,
                surfaceVisible = surfaceVisible,
                autoPlay = autoPlay,
                source = source,
                fillScreen = fillScreen,
                showPlayButton = showPlayButton,
                onPlayerChanged = onPlayerChanged,
            )
            MediaItem.Kind.LOCATION -> LocationRenderer(item)
            MediaItem.Kind.DOCUMENT -> Text(stringResource(R.string.media_document), color = Color.White)
        }
    }
}
