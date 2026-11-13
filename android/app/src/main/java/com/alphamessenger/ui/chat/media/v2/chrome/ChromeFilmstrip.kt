package com.alphamessenger.ui.chat.media.v2.chrome

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** Exactly what the filmstrip needs to know about an item: how to tell it apart and mark it. */
data class ChromeThumb(val id: String, val isVideo: Boolean)

/**
 * Filmstrip. It never pages on its own — it reports the selected index up and
 * aligns its own scroll to the index it gets back.
 *
 * It does not load thumbnails either: the frame source arrives as a parameter,
 * so the filmstrip knows nothing about storage or decryption.
 */
@Composable
fun ChromeFilmstrip(
    thumbs: List<ChromeThumb>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    thumbnail: suspend (ChromeThumb) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Catch up to the selected item: far away — jump, nearby — ease to center.
    LaunchedEffect(selectedIndex) {
        val layoutInfo = listState.layoutInfo
        val visible = layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == selectedIndex }
        if (item == null) {
            runCatching { listState.animateScrollToItem(selectedIndex) }
        } else {
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val itemCenter = item.offset + item.size / 2
            val delta = (itemCenter - viewportCenter).toFloat()
            if (kotlin.math.abs(delta) > 1f) {
                runCatching { listState.animateScrollBy(delta) }
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(count = thumbs.size, key = { index -> thumbs[index].id }) { index ->
            val thumb = thumbs[index]
            FilmstripThumb(
                thumb = thumb,
                selected = index == selectedIndex,
                thumbnail = thumbnail,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun FilmstripThumb(
    thumb: ChromeThumb,
    selected: Boolean,
    thumbnail: suspend (ChromeThumb) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    var bitmap by remember(thumb.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(thumb.id) { bitmap = thumbnail(thumb) }

    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.5f))
            .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (thumb.isVideo) {
            Icon(
                AlphaIcons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
