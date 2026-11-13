package com.alphamessenger.ui.chat.media.v2.pager

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.alphamessenger.R
import com.alphamessenger.ui.chat.SEEK_BACK_MS
import com.alphamessenger.ui.chat.SEEK_FORWARD_MS
import com.alphamessenger.ui.chat.media.MediaItem
import kotlinx.coroutines.delay

/** Playback state of THIS page. Only the player is exposed to the outside. */
enum class VideoPlayback { IDLE, BUFFERING, PLAYING, PAUSED, ENDED }

/**
 * Video page. The player is created only for the current page and dies with it
 * — neighbors show a poster, so paging does not spin up three decoders.
 *
 * The page does not know whether chrome is visible or where the transition
 * flies: the center button is a flag, and the created player is simply handed
 * up — the seek bar belongs to the bottom bar, not the page.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoRenderer(
    item: MediaItem,
    isCurrent: Boolean,
    source: PagerMediaSource,
    modifier: Modifier = Modifier,
    /**
     * Whether to show the LIVE player surface. During the frame flight it must
     * not exist: a video surface ignores layer alpha and would hang on top of
     * the flying snapshot. The player itself is not released.
     */
    surfaceVisible: Boolean = true,
    /**
     * Whether it starts playing on its own. True only for the item the viewer
     * was opened from: paging to a neighboring video is browsing, not playback.
     */
    autoPlay: Boolean = true,
    fillScreen: Boolean = false,
    showPlayButton: Boolean = true,
    onPlayerChanged: (ExoPlayer?) -> Unit = {},
) {
    val ctx = LocalContext.current

    // ONE poster for all page states: neighbor, current, and player warm-up.
    // Loading it in separate composition branches made the subtree die on
    // neighbor→current and the poster reload from zero — the blank gap read as
    // a flash.
    var poster by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) { poster = source.poster(item) }

    // "Displayed" and "playing" are DIFFERENT states. No player exists without
    // intent: either the item the viewer was opened from, or the user pressed ▶.
    // Otherwise paging onto a video would download the file and show waiting
    // instead of a frame.
    var started by remember(item.id) { mutableStateOf(autoPlay && isCurrent) }

    if (!isCurrent || !started) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            poster?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            when {
                // Neighbor page: a "video" icon, no actions.
                !isCurrent -> Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(AlphaIcons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp)) }

                showPlayButton -> IconButton(
                    onClick = { started = true },
                    modifier = Modifier.size(68.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                ) {
                    Icon(
                        AlphaIcons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.media_play),
                        tint = Color.White,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
        }
        return
    }

    var file by remember(item.id) { mutableStateOf<java.io.File?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
 // Not on disk yet → play it as a stream: first chunks in, picture
    // on screen; the rest arrives while it plays. A stream that errors falls
    // back to the old path — the whole file, then play.
    var stream by remember(item.id) { mutableStateOf<androidx.media3.datasource.DataSource.Factory?>(null) }
    var streamFailed by remember(item.id) { mutableStateOf(false) }
    // Look up (and download if needed) the file only after the intent to play.
    LaunchedEffect(item.id, started, streamFailed) {
        if (file != null || stream != null) return@LaunchedEffect
        source.localVideo(item)?.let { file = it; return@LaunchedEffect }
        if (!streamFailed) source.videoStream(item)?.let { stream = it; return@LaunchedEffect }
        if (file != null) return@LaunchedEffect
        val f = source.videoFile(item)
        if (f != null) file = f else failed = true
    }

    val playFile = file
    val playStream = stream
    when {
        playFile == null && playStream == null && failed -> Text(stringResource(R.string.media_video_load_failed), color = Color.White)
        // Waiting AFTER the ▶ press is deliberate: the frame stays in place
        // with preparation on top. This state never happens before the press.
        playFile == null && playStream == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            poster?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(44.dp))
        }
        else -> {
            val exo = remember(playFile, playStream) {
                ExoPlayer.Builder(ctx).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(ctx))
                    .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
                    .setSeekBackIncrementMs(SEEK_BACK_MS)
                    .build().apply {
                        if (playFile != null) {
                            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(playFile)))
                        } else if (playStream != null) {
                            setMediaSource(
                                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(playStream)
                                    .createMediaSource(androidx.media3.common.MediaItem.fromUri("alpha-stream://${item.id}")),
                            )
                        }
                        // The player only exists with intent to play, so this
                        // is always true: both auto-start and the ▶ press.
                        playWhenReady = true
                        prepare()
                    }
            }
            // The subscriber can change between frames — keep it fresh,
            // otherwise the unsubscribe would go to a stale closure.
            val notify by rememberUpdatedState(onPlayerChanged)
            DisposableEffect(exo) {
                notify(exo)
                onDispose {
                    notify(null)
                    exo.release()
                }
            }

            // The player surface shows its own black stub until the first
            // rendered frame — that reads as a flash on video open. Keep the
 // poster on top until the frame arrives (same trick as messengers usually do's
            // frame snapshot).
            var renderedFirstFrame by remember(exo) { mutableStateOf(false) }
            DisposableEffect(exo) {
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onRenderedFirstFrame() { renderedFirstFrame = true }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // Streaming broke (server, network): fall back to the full file.
                        if (stream != null) { stream = null; streamFailed = true }
                    }
                }
                exo.addListener(listener)
                onDispose { exo.removeListener(listener) }
            }

            var playback by remember(exo) { mutableStateOf(VideoPlayback.IDLE) }
            LaunchedEffect(exo) {
                while (true) {
                    playback = when {
                        exo.playbackState == ExoPlayer.STATE_BUFFERING -> VideoPlayback.BUFFERING
                        exo.playbackState == ExoPlayer.STATE_ENDED -> VideoPlayback.ENDED
                        exo.isPlaying -> VideoPlayback.PLAYING
                        else -> VideoPlayback.PAUSED
                    }
                    delay(200)
                }
            }

            // While the frame flies there is no live surface: only the snapshot
 // in the transition layer — same as messengers usually do hiding the TextureView
            // behind a frame snapshot. Playback is NOT paused: pausing for the
            // flight would leave the video stalled after opening.

            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (surfaceVisible) {
                    AndroidView(
                        factory = { c ->
                            PlayerView(c).apply {
                                useController = false
                                setControllerAutoShow(false)
                                player = exo
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { pv ->
                            pv.resizeMode =
                                if (fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!renderedFirstFrame) {
                        poster?.let {
                            Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                when {
                    !surfaceVisible -> Unit
                    playback == VideoPlayback.BUFFERING ->
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(44.dp))
                    // While paused the ▶ stays on screen even with the chrome
 // hidden; only a playing video hides it.
                    showPlayButton || playback != VideoPlayback.PLAYING -> IconButton(
                        onClick = {
                            when (playback) {
                                VideoPlayback.ENDED -> { exo.seekTo(0); exo.play() }
                                VideoPlayback.PLAYING -> exo.pause()
                                else -> exo.play()
                            }
                        },
                        modifier = Modifier.size(68.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                    ) {
                        Icon(
                            imageVector = when (playback) {
                                VideoPlayback.ENDED -> AlphaIcons.Filled.Replay
                                VideoPlayback.PLAYING -> AlphaIcons.Filled.Pause
                                else -> AlphaIcons.Filled.PlayArrow
                            },
                            contentDescription = if (playback == VideoPlayback.PLAYING) stringResource(R.string.editor_pause) else stringResource(R.string.media_play),
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
            }
        }
    }
}
