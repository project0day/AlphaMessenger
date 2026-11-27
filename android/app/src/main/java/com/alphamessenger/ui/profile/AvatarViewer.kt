package com.alphamessenger.ui.profile

import com.alphamessenger.ui.icons.AlphaIcons
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.alphamessenger.R
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/** One picture of a profile: the video avatar, the photo or the cover. */
data class AvatarPage(val path: String, val isVideo: Boolean, val label: Int)

/** A profile's pictures in the order they are shown: video avatar (else photo), then the cover. */
fun avatarPages(avatarPath: String, videoPath: String, coverPath: String): List<AvatarPage> = buildList {
    fun ok(p: String) = p.isNotBlank() && File(p).length() > 0
    when {
        ok(videoPath) -> add(AvatarPage(videoPath, true, R.string.video_avatar_title))
        ok(avatarPath) -> add(AvatarPage(avatarPath, false, R.string.avatar_viewer_photo))
    }
    if (ok(coverPath)) add(AvatarPage(coverPath, false, R.string.common_cover))
}

/**
 * A profile's pictures, full screen: swipe sideways between them, down to
 * close; save to the gallery or share, and — on our own profile ([onRemove]) —
 * remove one. Videos play silently in a loop.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AvatarViewer(
    name: String,
    pages: List<AvatarPage>,
    start: Int = 0,
    onDismiss: () -> Unit,
    onRemove: ((AvatarPage) -> Unit)? = null,
) {
    if (pages.isEmpty()) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // A removed picture leaves the list while the viewer stays open.
    val current by androidx.compose.runtime.rememberUpdatedState(pages)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, pages.lastIndex)) { current.size }
    val drag = remember { Animatable(0f) }
    var confirmRemove by remember { androidx.compose.runtime.mutableStateOf<AvatarPage?>(null) }
    val shown = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = false)
        val fade = (1f - abs(drag.value) / 900f).coerceIn(0.3f, 1f)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade))
                // Down (or up) far enough closes it; less springs back.
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(drag.value) > 260f) onDismiss() else drag.animateTo(0f)
                            }
                        },
                        onVerticalDrag = { ch, dy -> ch.consume(); scope.launch { drag.snapTo(drag.value + dy) } },
                    )
                },
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().graphicsLayer { translationY = drag.value }) { i ->
                val page = pages[i]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (page.isVideo) {
                        // Only the page in view plays.
                        if (pager.settledPage == i) FittedLoop(page.path)
                    } else {
                        AsyncImage(model = File(page.path), contentDescription = stringResource(page.label),
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            Column(Modifier.fillMaxWidth().systemBarsPadding().graphicsLayer { alpha = fade }) {
                // One segment per picture, the current one lit.
                if (pages.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pages.indices.forEach { k ->
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (k == pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val p = shown
                        val date = remember(p.path) {
                            java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(File(p.path).lastModified()))
                        }
                        Text(
                            stringResource(R.string.avatar_viewer_position, pages.indexOf(p) + 1, pages.size) + " · " + stringResource(p.label) + " · " + date,
                            color = Color.White.copy(alpha = 0.7f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp)
                    .graphicsLayer { alpha = fade },
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (pages.size > 1) Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.forEachIndexed { k, p ->
                        Box(
                            Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.1f))
                                .then(if (k == pager.currentPage) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                                .clickable { scope.launch { pager.animateScrollToPage(k) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (p.isVideo) {
                                val thumb = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.path) {
                                    value = com.alphamessenger.ui.chat.bubbles.extractVideoThumb(p.path)?.asImageBitmap()
                                }.value
                                thumb?.let { androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                Icon(AlphaIcons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp).align(Alignment.BottomEnd).padding(2.dp))
                            } else {
                                AsyncImage(model = File(p.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ViewerButton(AlphaIcons.Outlined.Download, stringResource(R.string.common_save), Modifier.weight(1f)) {
                        val p = shown
                        scope.launch {
                            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.alphamessenger.util.GallerySaver.save(ctx, File(p.path), p.isVideo)
                            }
                            android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.avatar_viewer_saved else R.string.chat_save_gallery_failed),
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    ViewerButton(AlphaIcons.Outlined.Share, stringResource(R.string.common_share), Modifier.weight(1f)) {
                        val p = shown
                        scope.launch {
                            com.alphamessenger.util.ShareOut.share(ctx,
                                listOf(com.alphamessenger.util.ShareOut.Item(p.path, null, if (p.isVideo) "video" else "image")),
                                ctx.getString(R.string.common_share))
                        }
                    }
                    if (onRemove != null) {
                        ViewerButton(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.common_delete), Modifier.weight(1f)) {
                            confirmRemove = shown
                        }
                    }
                }
            }
        }
        confirmRemove?.let { page ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmRemove = null },
                title = { Text(stringResource(if (page.label == R.string.common_cover) R.string.avatar_viewer_remove_cover else R.string.avatar_viewer_remove_avatar)) },
                text = { Text(stringResource(R.string.avatar_viewer_remove_text)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmRemove = null; onRemove?.invoke(page) }) {
                        Text(stringResource(R.string.common_delete), color = Color(0xFFE5484D))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun ViewerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
    }
}

/**
 * A video shown whole (not cropped), silent, looping. Its shape is read from
 * the file first, so the picture takes its final place in the first frame; the
 * still first frame shows until the player has drawn its own — never a frame
 * stretched over the screen, never a black one.
 */
@Composable
private fun FittedLoop(path: String) {
    val ctx = LocalContext.current
    val ratio = remember(path) { videoRatio(path) }
    val drawn = remember(path) { androidx.compose.runtime.mutableStateOf(false) }
    val player = remember(path) {
        ExoPlayer.Builder(ctx.applicationContext).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(ctx.applicationContext)).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onRenderedFirstFrame() { drawn.value = true } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    val poster = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = com.alphamessenger.ui.chat.bubbles.extractVideoThumb(path)?.asImageBitmap()
    }.value
    // The largest box of the video's shape that fits the screen.
    Box(Modifier.aspectRatio(ratio), contentAlignment = Alignment.Center) {
        if (!drawn.value) poster?.let { androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        AndroidView(
            factory = { c ->
                (android.view.LayoutInflater.from(c).inflate(R.layout.round_player, null) as PlayerView)
                    .apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            // The texture is transparent until its first frame; hidden till then all the same.
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (drawn.value) 1f else 0f },
        )
    }
}

/** Width / height of a video as shown (rotation applied). */
private fun videoRatio(path: String): Float = runCatching {
    val r = android.media.MediaMetadataRetriever()
    try {
        com.alphamessenger.data.MediaVault.setSource(r, path)
        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
        val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (sw, sh) = if (rot == 90 || rot == 270) h to w else w to h
        if (sw > 0f && sh > 0f) sw / sh else 1f
    } finally { r.release() }
}.getOrDefault(1f)
