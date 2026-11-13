package com.alphamessenger.ui.media.editor

import com.alphamessenger.ui.icons.AlphaIcons
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.SystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

private val EditorBackground = Color(0xFF0D0E11)

/**
 * Video editor: range and sound.
 *
 * A separate screen from the photo editor on purpose. They share only the
 * shell; the content differs: photos are pixels and a frame, video is time
 * and tracks. Mixing them would produce a "universal" screen where half the
 * tools are always off. The entry point is ONE — the same pencil in the
 * viewer: the attachment type decides which editor opens.
 *
 * Edits are transactional: the working copy lives here, the draft gets it
 * on "Done".
 */
@Composable
fun VideoEditorScreen(
    item: MediaDraftItem,
    colors: AlphaColorScheme,
    onCancel: () -> Unit,
    onApply: (VideoTrim) -> Unit,
) {
    val context = LocalContext.current
    val duration = item.durationMs.coerceAtLeast(1L)
    var trim by remember(item.id) { mutableStateOf(VideoTrim.of(item)) }

    DisposableEffect(Unit) {
        SystemBars.statusColor = EditorBackground
        val navOwner = Any()
        SystemBars.requestNav(navOwner, EditorBackground)
        SystemBars.lightIcons = false
        onDispose {
            SystemBars.statusColor = null
            SystemBars.releaseNav(navOwner)
            SystemBars.lightIcons = null
        }
    }

    BackHandler { onCancel() }

    // Strip frames appear ONE BY ONE as they are ready: waiting for all
    // eight means staring at an empty strip for seconds. The retriever is
    // opened once for the whole strip — opening it per frame costs more
    // than the read itself.
    val strip = remember(item.uri) { mutableStateListOf<ImageBitmap>() }
    val density = LocalDensity.current
    // How many frames are needed is decided by the STRIP, not a constant:
    // on a narrow screen extra frames are invisible but decode at full
    // price. A cell is roughly square.
    val stripCount = remember(density) {
        val screenPx = with(density) { 360.dp.toPx() }
        (screenPx / with(density) { STRIP_CELL.toPx() }).toInt().coerceIn(5, 10)
    }
    val cellPx = remember(density) { with(density) { STRIP_CELL.toPx() }.toInt().coerceAtLeast(64) }
    LaunchedEffect(item.uri, stripCount) {
        strip.clear()
        // Leaving the screen cancels this effect — the retriever is closed
        // in finally; unread frames simply never appear.
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri)
                for (index in 0 until stripCount) {
                    val atMs = duration * index / stripCount
                    val cached = FrameCache.get(item.uri, atMs, cellPx)
                    if (cached != null) {
                        withContext(Dispatchers.Main) { strip.add(cached) }
                        continue
                    }
                    val frame = runCatching {
                        // Decode STRAIGHT to the cell size: a full 4K frame
                        // for a 56-px strip is exactly those seconds.
                        retriever.getScaledFrameAtTime(
                            atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            cellPx, cellPx,
                        )
                    }.getOrNull()?.asImageBitmap() ?: continue
                    FrameCache.put(item.uri, atMs, frame, cellPx)
                    withContext(Dispatchers.Main) { strip.add(frame) }
                }
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    // The large frame shows the spot currently being dragged: without it
    // trimming is blind — you see the strip but not what you are cutting.
    var previewAtMs by remember(item.id) { mutableStateOf(trim.startMs) }
    val preview by produceState<ImageBitmap?>(null, item.uri, previewAtMs) {
        // While the finger drags a handle, the point changes dozens of times
        // per second. Decoding a frame per change is pointless: it would be
        // stale on arrival. Wait for a short pause — then read. Anything
        // already read comes from the cache.
        FrameCache.get(item.uri, previewAtMs)?.let { value = it }
        kotlinx.coroutines.delay(PREVIEW_DEBOUNCE_MS)
        val frame = withContext(Dispatchers.IO) { frameAt(context, item, previewAtMs) }
        if (frame != null) {
            FrameCache.put(item.uri, previewAtMs, frame)
            value = frame
        }
    }

    // The range's weight is computed from the file itself and recomputed
    // after the handle is released: computing per move is pointless, and
    // lying with a proportion even more so.
    var size by remember(item.id) { mutableStateOf<com.alphamessenger.media.MediaSizeInfo?>(null) }
    LaunchedEffect(item.uri, trim.startMs, trim.endMs, trim.muted) {
        kotlinx.coroutines.delay(SIZE_DEBOUNCE_MS)
        // The weight is computed by the SAME plan the send path will later
        // execute. That is the point of the extracted planner: with the
        // editor guessing by its own rules and the send path by its own,
        // the shown number had no obligation to match the file — and did not.
        val meta = com.alphamessenger.ui.media.export.SourceProbe.read(context, item.uri)
            .let { if (it.durationMs <= 0L) it.copy(durationMs = duration) else it }
        val spec = com.alphamessenger.ui.media.export.MediaEditSpec.of(item).copy(
            trimStartMs = trim.startMs, trimEndMs = trim.endMs, muted = trim.muted,
        )
        com.alphamessenger.ui.media.export.MediaQuality.load(context)
        val plan = com.alphamessenger.ui.media.export.MediaExportPlanner.plan(
            meta, spec, com.alphamessenger.ui.media.export.MediaQuality.videoPolicy(com.alphamessenger.ui.media.export.MediaQuality.videoEdge),
        )
        size = VideoTrimmer.measure(context, item.uri, plan, meta)
    }

    // The range must not only be seen but HEARD and checked on the fly: the
    // player plays exactly the selected piece with the selected sound —
    // exactly what goes to the recipient. It starts only on ▶, as in the
    // viewer: scrolling and dragging handles must not wake the decoder.
    var started by remember(item.id) { mutableStateOf(false) }
    var playing by remember(item.id) { mutableStateOf(false) }
    // Pause does NOT recreate the player: a new player is a new surface, and
    // its appearance is the edge flicker. Stopping is playWhenReady.
    val player = remember(item.uri, started, trim.startMs, trim.endMs, trim.muted) {
        if (!started) null else androidx.media3.exoplayer.ExoPlayer.Builder(context).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(context)).build().apply {
            setMediaItem(
                androidx.media3.common.MediaItem.Builder()
                    .setUri(item.uri)
                    .setClippingConfiguration(
                        androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(trim.startMs)
                            .setEndPositionMs(trim.endOr(duration))
                            .build(),
                    )
                    .build(),
            )
            volume = if (trim.muted) 0f else 1f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(player, playing) { player?.playWhenReady = playing }
    // Handles are being dragged — stop playback: otherwise the player plays
    // one range while the strip already selects another.
    LaunchedEffect(trim.startMs, trim.endMs) { playing = false }
    // The frame's aspect for the surface is known in advance — from the captured frame.
    val frameAspect = preview?.let { if (it.height > 0) it.width.toFloat() / it.height else 0f } ?: 0f

    // Where the clip currently sounds. The player counts time FROM THE RANGE
    // START (we gave it the clip), while the strip lives on the whole file's
    // scale — the conversion happens here, in one place.
    var positionMs by remember(item.id) { mutableStateOf(trim.startMs) }
    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        while (true) {
            positionMs = trim.startMs + active.currentPosition.coerceAtLeast(0L)
            kotlinx.coroutines.delay(60)
        }
    }
    // Not playing — the playhead stands where the large frame is looking.
    LaunchedEffect(playing, previewAtMs) { if (!playing) positionMs = previewAtMs }

    Box(
        modifier = Modifier.fillMaxSize().background(EditorBackground)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(10.dp)) {
                    Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = Color.White)
                }
                Text(
                    if (playing) "${clockOf(positionMs - trim.startMs)} / ${clockOf(trim.lengthMs(duration))}"
                    else clockOf(trim.lengthMs(duration)),
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.common_done),
                    color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { onApply(trim) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (player != null) {
                    FittedPlayerSurface(player, frameAspect, Modifier.fillMaxSize())
                } else {
                    preview?.let {
                        Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { started = true; playing = !playing },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) AlphaIcons.Filled.Pause else AlphaIcons.Filled.PlayArrow,
                        if (playing) stringResource(R.string.editor_pause) else stringResource(R.string.editor_play),
                        tint = Color.White, modifier = Modifier.size(40.dp),
                    )
                }
            }

            TrimStrip(
                frames = strip,
                trim = trim,
                duration = duration,
                positionMs = positionMs,
                cells = stripCount,
                accent = colors.accent,
                onTrimChange = { updated, at ->
                    trim = updated
                    previewAtMs = at
                },
                onSeek = { atMs ->
                    // A strip tap is a seek: both the frame and the player go to that point.
                    previewAtMs = atMs
                    positionMs = atMs
                    player?.seekTo((atMs - trim.startMs).coerceAtLeast(0L))
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        "${clockOf(trim.startMs)} — ${clockOf(trim.endOr(duration))}",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                    )
                    // The range's weight. Exact — no "about": promising
                    // precision where there is none is wrong, but hiding it
                    // where it exists is too. The weight contract decides,
                    // not the screen.
                    // Always laid out, empty until measured: the line appearing later
                    // must not push the frame and the strip up.
                    Text(
                        size?.takeIf { it.display > 0 }?.let { com.alphamessenger.media.MediaSizeFormat.of(it) }.orEmpty(),
                        color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, minLines = 1,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (trim.muted) colors.accent else Color.White.copy(alpha = 0.12f))
                        .clickable { trim = VideoTrimController.toggleMute(trim) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Icon(
                        if (trim.muted) AlphaIcons.AutoMirrored.Outlined.VolumeOff
                        else AlphaIcons.AutoMirrored.Outlined.VolumeUp,
                        stringResource(R.string.editor_sound),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (trim.muted) stringResource(R.string.editor_muted) else stringResource(R.string.editor_with_sound),
                        color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Frame strip with two handles.
 *
 * No math here — it lives in [VideoTrimController]; the strip only converts
 * pixels to milliseconds. The range is read by function at touch-down, so
 * re-grabbing a handle continues from the current spot.
 */
@Composable
private fun TrimStrip(
    frames: List<ImageBitmap>,
    trim: VideoTrim,
    duration: Long,
    positionMs: Long,
    cells: Int,
    accent: Color,
    onTrimChange: (VideoTrim, Long) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val slop = with(density) { 24.dp.toPx() }
    var current by remember { mutableStateOf(trim) }
    current = trim

    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
            // There are always as many cells as there will be frames: the
            // strip does not jump while they load — missing ones show a flat
            // background.
            repeat(cells) { index ->
                val frame = frames.getOrNull(index)
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f)
                        .background(Color.White.copy(alpha = 0.08f)),
                ) {
                    if (frame != null) {
                        Image(
                            frame, null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture
                    val startX = current.startMs.toFloat() / duration * width
                    val endX = current.endOr(duration).toFloat() / duration * width
                    val grabStart = abs(down.position.x - startX) <= slop
                    val grabEnd = abs(down.position.x - endX) <= slop
                    val inside = down.position.x in startX..endX
                    if (!grabStart && !grabEnd && !inside) return@awaitEachGesture
                    down.consume()

                    var working = current
                    var moved = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val dx = change.position.x - change.previousPosition.x
                        moved += abs(dx)
                        val deltaMs = (dx / width * duration).roundToLong()
                        working = when {
                            grabStart -> VideoTrimController.moveStart(working, deltaMs, duration)
                            grabEnd -> VideoTrimController.moveEnd(working, deltaMs, duration)
                            else -> VideoTrimController.move(working, deltaMs, duration)
                        }
                        val at = if (grabEnd) working.endOr(duration) else working.startMs
                        onTrimChange(working, at)
                    }
                    // No finger movement — it is a tap: seek to the touch
                    // point, not a zero-millisecond window shift.
                    if (moved < TAP_SLOP_PX && !grabStart && !grabEnd) {
                        val atMs = (down.position.x / width * duration).roundToLong()
                            .coerceIn(current.startMs, current.endOr(duration))
                        onSeek(atMs)
                    }
                }
            },
        ) {
            val startX = current.startMs.toFloat() / duration * size.width
            val endX = current.endOr(duration).toFloat() / duration * size.width

            val shade = Color.Black.copy(alpha = 0.6f)
            drawRect(shade, Offset(0f, 0f), Size(startX, size.height))
            drawRect(shade, Offset(endX, 0f), Size(size.width - endX, size.height))

            drawRect(
                accent, Offset(startX, 0f), Size(endX - startX, size.height),
                style = Stroke(width = 6f),
            )
            // Handles — wide bars at the window edges: visible and easy to hit.
            drawRect(accent, Offset(startX - 6f, 0f), Size(12f, size.height))
            drawRect(accent, Offset(endX - 6f, 0f), Size(12f, size.height))

            // Playhead: where the clip sounds now. White so it does not mix
            // with the range borders, with a cap on top so the eye knows
            // where to look.
            val playX = (positionMs.toFloat() / duration * size.width).coerceIn(startX, endX)
            drawRect(Color.White, Offset(playX - 2f, 0f), Size(4f, size.height))
            drawCircle(Color.White, radius = 7f, center = Offset(playX, 6f))
        }
    }
}

/** Frames for the strip. Eight: more is not read by the eye but costs time. */
private fun extractStrip(
    context: android.content.Context,
    item: MediaDraftItem,
    duration: Long,
): List<ImageBitmap> = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, item.uri)
        (0 until STRIP_FRAMES).mapNotNull { index ->
            val atUs = (duration * index / STRIP_FRAMES) * 1000L
            retriever.getScaledFrameAtTime(
                atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, STRIP_FRAME_PX, STRIP_FRAME_PX,
            )?.asImageBitmap()
        }
    } finally {
        runCatching { retriever.release() }
    }
}.getOrDefault(emptyList())

/** The large frame at the given point — what the user is choosing right now. */
private fun frameAt(
    context: android.content.Context,
    item: MediaDraftItem,
    atMs: Long,
): ImageBitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, item.uri)
        val frame: Bitmap? = retriever.getFrameAtTime(
            atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        )
        frame?.asImageBitmap()
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

/** Debounce before computing the weight: while a handle is dragged there is nothing to count. */
private const val SIZE_DEBOUNCE_MS = 250L

private fun clockOf(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Frames already read from the file. Key — file, time and size: the same
 * frame must not be fetched twice, and handle movement must not re-read
 * anything.
 */
private object FrameCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(64)

    private fun key(uri: android.net.Uri, atMs: Long, size: Int) = "$uri|$atMs|$size"

    fun get(uri: android.net.Uri, atMs: Long, size: Int = 0): ImageBitmap? = cache.get(key(uri, atMs, size))

    fun put(uri: android.net.Uri, atMs: Long, frame: ImageBitmap, size: Int = 0) {
        cache.put(key(uri, atMs, size), frame)
    }
}

/** Debounce before reading a frame: while the finger drags, there is nothing to read. */
private const val PREVIEW_DEBOUNCE_MS = 120L

/** Strip cell width. The decode size is derived from it too. */
private val STRIP_CELL = 56.dp

/** Movement below this counts as a tap, not a drag. */
private const val TAP_SLOP_PX = 12f

private const val STRIP_FRAMES = 8
private const val STRIP_FRAME_PX = 160
