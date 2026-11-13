package com.alphamessenger.ui.chat.media.v2.pager

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.alphamessenger.ui.chat.OsmMap
import com.alphamessenger.ui.chat.media.MediaItem

/** Full-screen map. No transitions, gestures or panels. */
@Composable
fun LocationRenderer(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val lat = item.lat ?: return
    val lng = item.lng ?: return
    OsmMap(lat = lat, lng = lng, modifier = modifier.fillMaxSize())
}
