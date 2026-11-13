package com.alphamessenger.ui.chat.media.v2.pager

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.alphamessenger.ui.chat.media.MediaItem

/**
 * Draws the frame. Nothing else: no zoom, no gestures, no transition placeholder.
 *
 * ONE image per show: swapping an already-displayed frame reads as a flash and
 * a quality jump to the eye.
 */
@Composable
fun ImageRenderer(
    item: MediaItem,
    source: PagerMediaSource,
    modifier: Modifier = Modifier,
) {
    var full by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) { full = source.fullBitmap(item) }

    val image = full ?: return
    Image(image, null, contentScale = ContentScale.Fit, modifier = modifier.fillMaxSize())
}
