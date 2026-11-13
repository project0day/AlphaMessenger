package com.alphamessenger.ui.media.preview

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.alphamessenger.R
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.delay

/**
 * Video preview before sending: playback, pause, seek, time, loading and
 * error states.
 *
 * The player exists ONLY on the visible page ([isActive]). A set of five
 * videos must not hold five decoders — that is memory and extra threads.
 * Leaving the page, backgrounding the app and closing the screen release
 * the resources.
 */
@Composable
internal fun VideoPreviewPage(
    item: MediaDraftItem,
    isActive: Boolean,
    colors: AlphaColorScheme,
    onScrubbingChange: (Boolean) -> Unit,
    controlsInset: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current

    // ONE poster frame for all page states: neighboring page, current one, and
    // while the player is being prepared. It used to live in two different
    // composition branches — on the "neighbor -> current" transition the subtree
    // was disposed, the frame cleared and re-read from the file; that gap was
    // the flash. Same trick as in the album viewer.
    var poster by remember(item.uri, item.trimStartMs) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(item.uri, item.trimStartMs) {
        poster = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Start of the SPAN, not of the file: the preview must show the edit.
            videoFrame(context, item.uri, item.trimStartMs)
        }
    }
    // Trim span and mute come from the set — sending will apply the same.
    val trim = com.alphamessenger.ui.media.editor.VideoTrim.of(item)
    val trimmedLength = trim.lengthMs(item.durationMs)

    // "Shown" and "playing" are different states. No player exists until ▶ is
    // pressed: flipping through the set is viewing, not playing. So page
    // switches do not spin up a decoder and cannot flash with either the player
    // surface or a wait state.
    var started by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(isActive) { if (!isActive) started = false }
    // The point to start from: it can be chosen with the bar BEFORE playback.
    var startAtMs by remember(item.uri) { mutableLongStateOf(0L) }

    val player = remember(item.uri, started, item.trimStartMs, item.trimEndMs, item.muted) {
        if (!started) null else ExoPlayer.Builder(context).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(context)).build().apply {
            // The span is given to the player, not "play and stop by ourselves": then
            // both duration and seeking are computed over it right away.
            setMediaItem(
                ExoMediaItem.Builder()
                    .setUri(item.uri)
                    .setClippingConfiguration(
                        androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(trim.startMs)
                            .setEndPositionMs(trim.endOr(item.durationMs))
                            .build(),
                    )
                    .build(),
            )
            volume = if (item.muted) 0f else 1f
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            // Seeked before playback — start from there, not from zero.
            if (startAtMs > 0) seekTo(startAtMs)
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    if (player == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            poster?.let {
                androidx.compose.foundation.Image(
                    it, null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // A neighboring page is just an icon; the current one is a working button.
            if (isActive) {
                PlayButton(playing = false) { started = true }
            } else {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AlphaIcons.Filled.PlayArrow, null,
                        tint = Color.White, modifier = Modifier.size(40.dp),
                    )
                }
            }
            // The bar exists IMMEDIATELY, not after playback: it belongs to the frame,
            // not to the player. Drag it before ▶ — the video starts at that point.
            if (isActive && trimmedLength > 0) {
                var preFraction by remember(item.uri) { mutableFloatStateOf(0f) }
                VideoControls(
                    visible = true,
                    shownMs = (preFraction * trimmedLength).toLong(),
                    durationMs = trimmedLength,
                    fraction = preFraction,
                    accent = colors.accent,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = controlsInset),
                    onScrubStart = { onScrubbingChange(true) },
                    onScrub = { preFraction = it },
                    onScrubEnd = {
                        startAtMs = (preFraction * trimmedLength).toLong().coerceAtLeast(0L)
                        onScrubbingChange(false)
                        started = true
                    },
                )
            }
        }
        return
    }

    var playing by remember(player) { mutableStateOf(false) }
    var buffering by remember(player) { mutableStateOf(true) }
    var failed by remember(player) { mutableStateOf(false) }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(trimmedLength) }
    // While the finger is on the bar, the position comes from the gesture; otherwise the player would yank it back.
    var scrubbing by remember(player) { mutableStateOf(false) }
    var scrubFraction by remember(player) { mutableFloatStateOf(0f) }
    // The point a seek was requested for. The player does not report its
    // position instantly: right after release it still reports the old one for
    // a while, and the thumb would bounce back and then jump forward. Until the
    // player reaches the requested point, show exactly that point.
    var seekTargetMs by remember(player) { mutableLongStateOf(NO_SEEK) }
    var controlsVisible by remember(player) { mutableStateOf(true) }

    // Until the player yields its first frame, a frame from the file lies on
    // top of it. Otherwise a video switch looked like this: the stub frame
    // disappears, an empty player surface takes its place (a flash), a spinner,
    // and only then the picture. The player is recreated on every page — that
    // is unavoidable; showing it empty is not.
    var firstFrame by remember(player) { mutableStateOf(false) }
    // The frame still hasn't appeared — now honestly show a wait state.
    var slowStart by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        delay(SLOW_START_MS)
        slowStart = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    player.duration.takeIf { it > 0 }?.let { durationMs = it }
                }
                if (state == Player.STATE_ENDED) controlsVisible = true
            }
            override fun onPlayerError(error: PlaybackException) { failed = true; buffering = false }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // The position is polled only when there is something to show.
    LaunchedEffect(player) {
        var waited = 0
        while (true) {
            if (!scrubbing) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val target = seekTargetMs
                when {
                    target == NO_SEEK -> positionMs = pos
                    // The player reached the requested point — it leads from here.
                    kotlin.math.abs(pos - target) <= SEEK_SETTLED_MS || waited > SEEK_WAIT_TICKS -> {
                        seekTargetMs = NO_SEEK
                        waited = 0
                        positionMs = pos
                    }
                    else -> { positionMs = target; waited++ }
                }
            }
            delay(120)
        }
    }

    // App backgrounded — preview audio must not keep playing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // During playback the control bar hides itself so it does not cover the
    // frame; pause and seeking bring it back.
    LaunchedEffect(playing, controlsVisible, scrubbing) {
        if (playing && controlsVisible && !scrubbing) {
            delay(2500)
            controlsVisible = false
        }
    }

    val fraction = when {
        scrubbing -> scrubFraction
        durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // No backdrop of its own: the surface is fitted to the frame, so a 16:9 clip
            // sits on the screen's background like its poster did, not in a black box.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
        contentAlignment = Alignment.Center,
    ) {
        // The surface is shared with the editor: one rule, one implementation.
        val frameAspect = poster?.let { if (it.height > 0) it.width.toFloat() / it.height else 0f } ?: 0f
        com.alphamessenger.ui.media.editor.FittedPlayerSurface(
            player = player,
            aspect = frameAspect,
            modifier = Modifier.fillMaxSize(),
        )
        // The very same frame that was shown before ▶ — kept on top until the
        // player yields its first frame. No re-render and no re-reading of the
        // file here: the poster is already in memory.
        if (!firstFrame && !failed) {
            poster?.let {
                androidx.compose.foundation.Image(
                    it, null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        when {
            failed -> Text(
                stringResource(R.string.video_open_failed),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )

            // The spinner only if we wait LONGER than usual: on page switches the
            // preparation takes fractions of a second, and flashing a wheel on every
            // flip is needless — the frame is already shown.
            buffering && !playing && !firstFrame && slowStart ->
                CircularProgressIndicator(color = Color.White)

            else -> AnimatedVisibility(
                visible = controlsVisible || !playing,
                enter = fadeIn(), exit = fadeOut(),
            ) {
                PlayButton(playing) {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                    controlsVisible = true
                }
            }
        }

        VideoControls(
            visible = controlsVisible && !failed,
            shownMs = if (scrubbing) (scrubFraction * durationMs).toLong() else positionMs,
            durationMs = durationMs,
            fraction = fraction,
            accent = colors.accent,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = controlsInset),
            onScrubStart = {
                scrubbing = true
                onScrubbingChange(true)
                controlsVisible = true
            },
            onScrub = { scrubFraction = it },
            onScrubEnd = {
                if (durationMs > 0) {
                    val target = (scrubFraction * durationMs).toLong().coerceIn(0L, durationMs)
                    // Show the requested point immediately and hold it until the player gets
                    // there: otherwise the thumb bounces back.
                    positionMs = target
                    seekTargetMs = target
                    player.seekTo(target)
                }
                scrubbing = false
                onScrubbingChange(false)
            },
        )
    }
}

/**
 * Player bar: time, seek, time.
 *
 * The same before playback and during it: the bar is part of the FRAME, not
 * of the player. While only the started player drew it, a non-playing video
 * had an empty lower screen, and the bar "appeared after pressing ▶".
 */
@Composable
private fun VideoControls(
    visible: Boolean,
    shownMs: Long,
    durationMs: Long,
    fraction: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(), exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    )
                )
                // The bar has NO interception of its own: the thumb owns the gesture and
                // consumes it. A former full-row interceptor disabled flipping on any touch
                // of the lower screen and, if the "release" never reached it, left flipping
                // disabled forever — swiping stopped working until the page changed.
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                clock(shownMs),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.width(44.dp),
            )
            SeekBar(
                fraction = fraction,
                accent = accent,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                onScrubStart = onScrubStart,
                onScrub = onScrub,
                onScrubEnd = onScrubEnd,
            )
            Text(
                clock(durationMs),
                color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                modifier = Modifier.width(44.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}
/** How long to wait for the first frame silently — on page switches it arrives faster. */
private const val SLOW_START_MS = 600L

/** No seek. A separate value rather than nullable: the position is polled every 120 ms. */
private const val NO_SEEK = -1L

/** The player is considered to have reached the requested point. */
private const val SEEK_SETTLED_MS = 400L

/** Wait limit (120 ms ticks): beyond it the player leads, even if it is stuck. */
private const val SEEK_WAIT_TICKS = 20

/**
 * A seek bar that owns its gesture.
 *
 * The stock `Slider` hands horizontal movement outward, and the pager
 * intercepted it halfway: the thumb "released" by itself, the position
 * jumped back to playback — seeking went in jerks. Here the gesture is
 * handled and CONSUMED in place, so the finger leads the bar continuously
 * and nothing leaks upward.
 */
@Composable
private fun SeekBar(
    fraction: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    // Resolved here (not inside the semantics lambda): no composable scope there.
    val seekDescription = stringResource(R.string.video_seek_desc)
    Box(
        modifier = modifier
            .height(36.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .semantics { contentDescription = seekDescription }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onScrubStart()
                    if (widthPx > 0f) onScrub((down.position.x / widthPx).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        if (widthPx > 0f) onScrub((change.position.x / widthPx).coerceIn(0f, 1f))
                    }
                    onScrubEnd()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(3.dp)
                .clip(CircleShape).background(Color.White.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp)
                .clip(CircleShape).background(accent),
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((widthPx * fraction.coerceIn(0f, 1f) - 7.dp.toPx()).toInt(), 0) }
                .size(14.dp).clip(CircleShape).background(Color.White),
        )
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(72.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) AlphaIcons.Filled.Pause else AlphaIcons.Filled.PlayArrow,
            if (playing) stringResource(R.string.editor_pause) else stringResource(R.string.editor_play),
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun VideoFramePoster(uri: android.net.Uri, modifier: Modifier) {
    val context = LocalContext.current
    var frame by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { videoFrame(context, uri) }
    }
    frame?.let {
        androidx.compose.foundation.Image(
            it, null,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = modifier,
        )
    }
}
