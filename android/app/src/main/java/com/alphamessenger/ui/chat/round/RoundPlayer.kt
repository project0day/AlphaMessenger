package com.alphamessenger.ui.chat.round

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.chat.playerPill
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The one player of video messages, for the whole app. A circle keeps playing
 * when its chat is left: the bar on top ([GlobalRoundBar]) says what plays and
 * closes it, and the circle floats over everything ([FloatingRound]).
 */
object RoundPlayer {
    /** What plays: the message, its chat, its file, who sent it and when. */
    data class Now(val messageId: String, val chatId: String, val path: String, val who: String, val at: String)

    var now by mutableStateOf<Now?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** The chat on screen (set by the chat screen): a circle from it plays in
     *  its bubble there; anywhere else it floats. */
    var screenChat by mutableStateOf<String?>(null)

    /** Circles whose bubble is on screen right now (a list keeps only those). */
    var shown by mutableStateOf(setOf<String>())
        private set
    internal fun bubbleShown(id: String, on: Boolean) { shown = if (on) shown + id else shown - id }

    /** Where the floating circle was left (px within the screen's free area). */
    internal var floatAt by mutableStateOf<Offset?>(null)

    private var player: ExoPlayer? = null
    fun player(): ExoPlayer? = player

    fun play(context: Context, messageId: String, path: String, who: String, at: String) {
        if (now?.messageId == messageId) { toggle(); return }
        stop()
        val p = ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(context.applicationContext)).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            setPlaybackSpeed(speed)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(state: Int) {
                    // The end: back to the start, paused — play replays it, ✕ closes.
                    if (state == Player.STATE_ENDED) {
                        playWhenReady = false
                        seekTo(0)
                        positionMs = 0L
                    }
                }
            })
            prepare()
            playWhenReady = true
        }
        player = p
        now = Now(messageId, screenChat.orEmpty(), path, who, at)
        positionMs = 0L
        durationMs = 0L
    }

    fun toggle() {
        val p = player ?: return
        when {
            p.isPlaying -> p.pause()
            p.playbackState == Player.STATE_ENDED -> { p.seekTo(0); p.play() }
            else -> p.play()
        }
    }

    /** Jump to [fraction] of the circle (0..1). */
    fun seek(fraction: Float) {
        val p = player ?: return
        val d = p.duration.takeIf { it > 0 } ?: return
        p.seekTo((d * fraction.coerceIn(0f, 1f)).toLong())
        positionMs = p.currentPosition
    }

    /** 1x → 1.5x → 2x → 1x. */
    fun nextSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.setPlaybackSpeed(speed)
    }

    fun stop() {
        player?.release()
        player = null
        now = null
        playing = false
        positionMs = 0L
        durationMs = 0L
    }

    internal fun tick() {
        val p = player ?: return
        positionMs = p.currentPosition
        p.duration.takeIf { it > 0 }?.let { durationMs = it }
    }
}

/** Diameter of a playing circle — in its bubble and floating alike. */
val ROUND_PLAYING_SIZE = 300.dp

/** How close to the edge (of the radius) a touch winds the video instead of tapping. */
private const val RING_ZONE = 0.72f

/**
 * Gestures of a playing circle, in one place so nothing else (the message's
 * long press, the list's scroll) takes a wind for its own. A touch near the
 * edge winds the video along the ring for as long as the finger moves. A touch
 * in the middle is a tap (play / pause); held, a long press — or, when [onMove]
 * is given (the floating circle), moving the finger moves the circle.
 */
suspend fun androidx.compose.ui.input.pointer.PointerInputScope.roundGestures(
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
    onMove: ((Offset) -> Unit)? = null,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.width / 2f
        fun fraction(o: Offset): Float {
            var a = atan2(o.y - c.y, o.x - c.x) + (PI / 2).toFloat()
            if (a < 0) a += (2 * PI).toFloat()
            return a / (2 * PI).toFloat()
        }
        // Follow the finger until it lets go, swallowing every step.
        suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.follow(step: (androidx.compose.ui.input.pointer.PointerInputChange) -> Unit) {
            while (true) {
                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                ch.consume()
                if (!ch.pressed) break
                step(ch)
            }
        }
        if ((down.position - c).getDistance() >= r * RING_ZONE) {
            RoundPlayer.seek(fraction(down.position))
            follow { RoundPlayer.seek(fraction(it.position)) }
            return@awaitEachGesture
        }
        // The middle: a tap, a move (floating) or a long press — whichever comes first.
        var outcome = 0 // 0 = held long, 1 = tap, 2 = move, 3 = lost
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (ch == null) { outcome = 3; break }
                ch.consume()
                if (!ch.pressed) { outcome = 1; break }
                if (onMove != null && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    onMove(ch.position - down.position)
                    outcome = 2
                    break
                }
            }
        }
        when (outcome) {
            1 -> onTap()
            2 -> follow { ch -> onMove?.invoke(ch.position - ch.previousPosition) }
            0 -> {
                if (onMove == null) onLongPress()
                // A held finger on the floating circle starts moving it too.
                follow { ch -> onMove?.invoke(ch.position - ch.previousPosition) }
            }
            else -> {}
        }
    }
}

/** Keeps the player's position current while something plays. */
@Composable
private fun RoundTicker() {
    val now = RoundPlayer.now
    LaunchedEffect(now) {
        while (RoundPlayer.now != null) { RoundPlayer.tick(); delay(50) }
    }
}

/** The circle's picture, attached to the shared player while it is shown. */
@Composable
fun RoundVideoSurface(modifier: Modifier) {
    AndroidView(
        factory = { ctx -> android.view.LayoutInflater.from(ctx).inflate(R.layout.round_player, null) as PlayerView },
        update = { it.player = RoundPlayer.player() },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/**
 * The ring around a playing circle: how far it is, with a dot at that point.
 * [seekable]: drag along the ring to wind the video.
 */
@Composable
fun RoundProgressRing(size: Dp, stroke: Dp = 3.dp, seekable: Boolean = false) {
    RoundTicker()
    val progress = RoundPlayer.progress
    val seek = if (seekable) Modifier.pointerInput(Unit) {
        fun at(o: Offset) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            // 0 at the top, clockwise.
            var a = atan2(o.y - c.y, o.x - c.x) + (PI / 2).toFloat()
            if (a < 0) a += (2 * PI).toFloat()
            RoundPlayer.seek(a / (2 * PI).toFloat())
        }
        detectDragGestures(onDragStart = { at(it) }) { ch, _ -> ch.consume(); at(ch.position) }
    } else Modifier
    Canvas(Modifier.size(size).then(seek)) {
        val w = stroke.toPx()
        val r = (this.size.width - w * 2.4f) / 2
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val tl = Offset(c.x - r, c.y - r)
        drawArc(Color.White.copy(alpha = 0.22f), -90f, 360f, false, topLeft = tl, size = Size(r * 2, r * 2), style = Stroke(w))
        drawArc(Color.White, -90f, 360f * progress, false, topLeft = tl, size = Size(r * 2, r * 2),
            style = Stroke(w, cap = StrokeCap.Round))
        val ang = (-90f + 360f * progress) * (PI / 180f).toFloat()
        drawCircle(Color.White, radius = w * 1.25f, center = Offset(c.x + r * cos(ang), c.y + r * sin(ang)))
    }
}

/**
 * The bar on top while a video message plays: from whom and when, where it
 * is, play / pause, speed, and ✕ that stops it.
 */
@Composable
fun GlobalRoundBar(onOpenChat: (String) -> Unit = {}) {
    val now = RoundPlayer.now ?: return
    val colors = AlphaTheme.colorScheme
    RoundTicker()
    Column(Modifier.fillMaxWidth().playerPill()) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpenChat(now.chatId) }
                .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AlphaIcons.Outlined.Videocam, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(now.who.ifBlank { stringResource(R.string.round_label) }, color = colors.textPrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    now.at + "  ·  " + com.alphamessenger.ui.chat.fmtMs(RoundPlayer.positionMs.toInt()) +
                        " / " + (if (RoundPlayer.durationMs > 0) com.alphamessenger.ui.chat.fmtMs(RoundPlayer.durationMs.toInt()) else "…"),
                    color = colors.textMuted, fontSize = 11.sp, maxLines = 1,
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(colors.bgTertiary)
                    .clickable { RoundPlayer.nextSpeed() }.padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    when (RoundPlayer.speed) { 1.5f -> "1,5x"; 2f -> "2x"; else -> "1x" },
                    color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = { RoundPlayer.toggle() }, modifier = Modifier.size(36.dp)) {
                Icon(if (RoundPlayer.playing) AlphaIcons.Filled.Pause else AlphaIcons.Filled.PlayArrow,
                    if (RoundPlayer.playing) stringResource(R.string.editor_pause) else stringResource(R.string.media_play),
                    tint = colors.accent, modifier = Modifier.size(26.dp))
            }
            IconButton(onClick = { RoundPlayer.stop() }, modifier = Modifier.size(34.dp)) {
                Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
        LinearProgressIndicator(
            progress = { RoundPlayer.progress }, color = colors.accent, trackColor = colors.bgTertiary,
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

/** Diameter of the floating circle: as big as a playing one in its chat. */
private val FLOAT_SIZE = ROUND_PLAYING_SIZE

/**
 * The circle that keeps playing outside its chat: a layer over the whole
 * screen, the circle can be dragged anywhere between the status bar and the
 * navigation bar — never under them, never out of sight. A tap pauses /
 * plays; the bar on top closes it. Starts at the top right.
 * It shows whenever the playing circle's own bubble is not on screen: the
 * chat was left, or scrolled away from it.
 */
@Composable
fun FloatingRound() {
    val now = RoundPlayer.now ?: return
    if (now.messageId in RoundPlayer.shown) return
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        val density = LocalDensity.current
        val sizePx = with(density) { FLOAT_SIZE.toPx() }
        val maxX = (constraints.maxWidth - sizePx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - sizePx).coerceAtLeast(0f)
        val start = Offset(maxX - with(density) { 12.dp.toPx() }, with(density) { 64.dp.toPx() })
        val at = (RoundPlayer.floatAt ?: start).let { Offset(it.x.coerceIn(0f, maxX), it.y.coerceIn(0f, maxY)) }
        fun move(d: Offset) {
            val cur = RoundPlayer.floatAt ?: start
            RoundPlayer.floatAt = Offset((cur.x + d.x).coerceIn(0f, maxX), (cur.y + d.y).coerceIn(0f, maxY))
        }
        Box(
            Modifier
                .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                .size(FLOAT_SIZE)
                .shadow(12.dp, CircleShape).clip(CircleShape).background(Color.Black)
                // The same gestures as in the chat — the ring winds, a tap pauses —
                // plus moving the circle from its middle.
                .pointerInput(maxX, maxY) { roundGestures(onTap = { RoundPlayer.toggle() }, onMove = { move(it) }) },
            contentAlignment = Alignment.Center,
        ) {
            RoundVideoSurface(Modifier.size(FLOAT_SIZE).clip(CircleShape))
            RoundProgressRing(FLOAT_SIZE)
        }
    }
}
