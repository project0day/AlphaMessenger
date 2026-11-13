package com.alphamessenger.ui.profile

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Longest video avatar. */
const val AVATAR_VIDEO_MAX_MS = 10_000L
private const val AVATAR_SIDE = 480
private const val AVATAR_BITRATE = 900_000

/** A picked video turned into an avatar: a small square silent mp4 and its still cover. */
object VideoAvatarExport {
    /** The fragment [startMs]..[endMs] of [source] as a silent H.264 mp4 —
     *  480×480 cropped by default, or as [presentation] shapes it. */
    suspend fun export(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
        dest: File,
        presentation: androidx.media3.effect.Presentation = androidx.media3.effect.Presentation.createForWidthAndHeight(
            AVATAR_SIDE, AVATAR_SIDE, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
        ),
        bitrate: Int = AVATAR_BITRATE,
    ): File? {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val item = MediaItem.Builder().setUri(source)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
                    ).build()
                val edited = EditedMediaItem.Builder(item)
                    .setRemoveAudio(true)
                    .setEffects(
                        androidx.media3.transformer.Effects(
                            emptyList(),
                            listOf(presentation),
                        ),
                    ).build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                    .setEncoderFactory(
                        androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(bitrate).build(),
                            ).build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            android.util.Log.w("AVATAR", "video avatar export failed", exception)
                            if (cont.isActive) cont.resume(false)
                        }
                    }).build()
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                runCatching { transformer.start(Composition.Builder(EditedMediaItemSequence(edited)).build(), dest.absolutePath) }
                    .onFailure { if (cont.isActive) cont.resume(false) }
            }
        }
        return dest.takeIf { ok && it.exists() && it.length() > 0 }
    }

    /** The frame at [atMs], cropped square, as the still avatar (JPEG). */
    suspend fun cover(context: Context, source: Uri, atMs: Long, dest: File): File? = withContext(Dispatchers.IO) {
        val bmp = frameAt(context, source, atMs, 640) ?: return@withContext null
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 512, 512, true)
        dest.parentFile?.mkdirs()
        runCatching { dest.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }; dest }.getOrNull()
    }

    fun frameAt(context: Context, source: Uri, atMs: Long, maxSide: Int): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, source)
            val f = if (android.os.Build.VERSION.SDK_INT >= 27)
                r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
            else r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            f
        } finally { r.release() }
    }.getOrNull()

    fun durationMs(context: Context, source: Uri): Long = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(context, source); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        finally { r.release() }
    }.getOrDefault(0L)
}

/**
 * A video avatar playing in its circle: silent, looping. Shown on profiles;
 * lists keep the still cover.
 */
@Composable
fun LoopingVideoAvatar(path: String, size: Dp, modifier: Modifier = Modifier) =
    LoopingVideo(path, modifier.size(size).clip(CircleShape))

/** A short clip playing silently in a loop, filling (cropping to) its box. */
@Composable
fun LoopingVideo(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(context.applicationContext)).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    PlayerSurface(player, modifier)
}

/** A player's picture, filling (cropping to) its box. */
@Composable
fun PlayerSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> android.view.LayoutInflater.from(ctx).inflate(R.layout.round_player, null) as PlayerView },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/** A silent player looping [startMs]..+[window] of [source] (reloads once a drag settles). */
@Composable
fun rememberFragmentPlayer(source: Uri, startMs: Long, window: Long): Player {
    val context = LocalContext.current
    val player = remember(source) {
        ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(context.applicationContext)).build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ALL }
    }
    LaunchedEffect(startMs, window) {
        kotlinx.coroutines.delay(200) // settle after a drag
        player.setMediaItem(
            MediaItem.Builder().setUri(source).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(startMs + window).build(),
            ).build(),
        )
        player.prepare()
        player.playWhenReady = true
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * Frames across the whole video with a window [window] ms wide dragged along
 * them: which fragment to keep.
 */
@Composable
fun FragmentStrip(source: Uri, duration: Long, window: Long, startMs: Long, onStartMs: (Long) -> Unit) {
    val context = LocalContext.current
    val strip by produceState<List<ImageBitmap?>>(emptyList(), source, duration) {
        if (duration <= 0) return@produceState
        value = withContext(Dispatchers.IO) {
            (0 until 8).map { i -> VideoAvatarExport.frameAt(context, source, duration * i / 8 + duration / 16, 160)?.asImageBitmap() }
        }
    }
    val latestStart by androidx.compose.runtime.rememberUpdatedState(startMs)
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp))) {
        val wPx = constraints.maxWidth.toFloat()
        val frac = if (duration > 0) window.toFloat() / duration else 1f
        val winPx = (wPx * frac).coerceAtLeast(24f)
        val leftPx = if (duration > 0) wPx * startMs / duration else 0f
        Row(Modifier.fillMaxSize()) {
            strip.forEach { b ->
                Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFF2A2B30))) {
                    b?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        // The chosen window, dragged along the strip.
        Box(
            Modifier.offset { IntOffset(leftPx.roundToInt(), 0) }
                .width(with(androidx.compose.ui.platform.LocalDensity.current) { winPx.toDp() })
                .fillMaxSize()
                .border(3.dp, Color(0xFFF5C451), RoundedCornerShape(10.dp))
                .pointerInput(duration, window, wPx) {
                    detectHorizontalDragGestures { ch, dx ->
                        ch.consume()
                        if (duration > 0) {
                            val ms = latestStart + (dx / wPx * duration).toLong()
                            onStartMs(ms.coerceIn(0L, (duration - window).coerceAtLeast(0L)))
                        }
                    }
                },
        )
    }
}

/**
 * The video avatar editor (picked video in): the circle loops the chosen
 * fragment; a strip of frames with a window up to [AVATAR_VIDEO_MAX_MS] picks
 * the fragment; five frames of it pick the still cover. Done prepares both.
 */
@Composable
fun VideoAvatarEditor(
    source: Uri,
    colors: AlphaColorScheme,
    onDone: (video: File, cover: File) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val duration by produceState(0L, source) { value = withContext(Dispatchers.IO) { VideoAvatarExport.durationMs(context, source) } }
    val window = minOf(duration, AVATAR_VIDEO_MAX_MS).coerceAtLeast(1L)
    var startMs by remember { mutableStateOf(0L) }
    var coverIdx by remember { mutableIntStateOf(2) }
    var busy by remember { mutableStateOf(false) }
    // Five cover candidates inside the chosen fragment.
    val covers by produceState<List<ImageBitmap?>>(emptyList(), source, startMs, window) {
        kotlinx.coroutines.delay(300) // settle after a drag
        value = withContext(Dispatchers.IO) {
            (0 until 5).map { i -> VideoAvatarExport.frameAt(context, source, startMs + window * i / 5 + window / 10, 160)?.asImageBitmap() }
        }
    }
    val player = rememberFragmentPlayer(source, startMs, window)

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        // Edge to edge: the bars sit on the editor's own dark background.
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = false)
        Column(
            Modifier.fillMaxSize().background(Color(0xFF111214)).systemBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!busy) onDismiss() }) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = Color.White) }
                Text(stringResource(R.string.video_avatar_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(colors.accent)
                        .clickable(enabled = !busy && duration > 0) {
                            busy = true
                            // One hardware decoder for a 4K source: the preview lets go of it.
                            player.stop()
                            scope.launch {
                                val dir = File(context.filesDir, "profile")
                                val stamp = System.currentTimeMillis()
                                val video = VideoAvatarExport.export(context, source, startMs, startMs + window, File(dir, "avatar_video_$stamp.mp4"))
                                val coverAt = startMs + window * coverIdx / 5 + window / 10
                                val cover = VideoAvatarExport.cover(context, source, coverAt, File(dir, "avatar_cover_$stamp.jpg"))
                                busy = false
                                if (video != null && cover != null) onDone(video, cover)
                                else {
                                    player.prepare()
                                    player.playWhenReady = true
                                    android.widget.Toast.makeText(context, context.getString(R.string.video_avatar_failed), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else Text(stringResource(R.string.common_done), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(20.dp))
            PlayerSurface(player, Modifier.size(280.dp).clip(CircleShape))
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.video_avatar_hint), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp))
            Spacer(Modifier.height(26.dp))

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.video_avatar_fragment), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("%.1f – %.1f".format(startMs / 1000f, (startMs + window) / 1000f), color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                FragmentStrip(source, duration, window, startMs) { startMs = it }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.video_avatar_cover), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    covers.forEachIndexed { i, b ->
                        Box(
                            Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF2A2B30))
                                .then(if (i == coverIdx) Modifier.border(3.dp, Color(0xFFF5C451), CircleShape) else Modifier)
                                .clickable { coverIdx = i },
                        ) { b?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
                    }
                }
            }
        }
    }
}
