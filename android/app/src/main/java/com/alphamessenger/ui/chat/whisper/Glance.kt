package com.alphamessenger.ui.chat.whisper

import com.alphamessenger.ui.icons.AlphaIcons
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.alphamessenger.R
import com.alphamessenger.data.WhisperSecret
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Timed view — a photo shown whole for a few seconds (or until closed), a
 * video played once. Opening it is the one view: closing, the time running
 * out or leaving the app burns it on both phones, and its file leaves the
 * storage servers.
 */

/** Choices of the timer, in seconds; 0 = until the viewer closes it. */
val GLANCE_SECS = listOf(1, 3, 5, 10, 0)

private val GlanceAccent = Color(0xFFA78BFA)

/** Label of a timer choice: "5 с" or "∞". */
@Composable
fun glanceLabel(secs: Int): String =
    if (secs == 0) "∞" else stringResource(R.string.whisper_secs, secs)

/**
 * The timer at the right of the caption field: off, or one of [GLANCE_SECS].
 * The choices open above it in the same window — no dialog over the panel.
 */
@Composable
fun GlanceTimerButton(
    secs: Int?,
    onChange: (Int?) -> Unit,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    var open by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(if (secs != null) GlanceAccent else colors.bgTertiary)
                .clickable { open = !open },
            contentAlignment = Alignment.Center,
        ) {
            if (secs == null) {
                Icon(AlphaIcons.Outlined.ViewOnce, stringResource(R.string.glance_timer), tint = colors.textSecondary, modifier = Modifier.size(21.dp))
            } else {
                Text(
                    if (secs == 0) "∞" else "$secs",
                    color = Color.White, fontSize = if (secs == 0) 18.sp else 15.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        if (open) {
            val lift = with(density) { 8.dp.roundToPx() }
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, -with(density) { 48.dp.roundToPx() } - lift),
                onDismissRequest = { open = false },
                // Not focusable: taking focus would close the keyboard under it. An outside
                // tap still closes it; Back is handled here.
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                androidx.activity.compose.BackHandler { open = false }
                Column(
                    Modifier.width(196.dp).clip(RoundedCornerShape(18.dp)).background(colors.bgSecondary)
                        .border(0.5.dp, colors.alphaBorder, RoundedCornerShape(18.dp)).padding(vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.glance_timer), color = colors.textPrimary, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Text(
                        stringResource(R.string.glance_timer_hint), color = colors.textMuted, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
                    (listOf<Int?>(null) + GLANCE_SECS).forEach { option ->
                        val on = option == secs
                        Row(
                            Modifier.fillMaxWidth().clickable { onChange(option); open = false }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (option) {
                                    null -> stringResource(R.string.glance_off)
                                    0 -> stringResource(R.string.glance_until_closed)
                                    else -> stringResource(R.string.whisper_secs, option)
                                },
                                color = if (on) GlanceAccent else colors.textPrimary, fontSize = 15.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f),
                            )
                            if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(GlanceAccent))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The viewer of an incoming timed view. [loadSecret] gives it (null: already
 * seen), [loadMedia] its decrypted file, [onBurn] burns it on both phones,
 * [onForgetMedia] wipes the decrypted copy.
 */
@Composable
fun GlanceReader(
    senderName: String,
    loadSecret: suspend () -> WhisperSecret?,
    loadMedia: suspend (String) -> String?,
    onBurn: (usedSecs: Int, shot: Boolean) -> Unit,
    onForgetMedia: (String) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        // The dialog re-applies its secure policy on every recomposition, so the
        // protection must be the policy itself, not a flag set beside it.
        properties = DialogProperties(
            usePlatformDefaultWidth = true, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = false)
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.setDimAmount(0f)
            onDispose { }
        }
        val ctx = LocalContext.current
        var secret by remember { mutableStateOf<WhisperSecret?>(null) }
        var path by remember { mutableStateOf<String?>(null) }
        var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var failed by remember { mutableStateOf(false) }
        var shown by remember { mutableStateOf(false) }
        var shownAt by remember { mutableLongStateOf(0L) }
        var elapsedMs by remember { mutableLongStateOf(0L) }
        var burned by remember { mutableStateOf(false) }
        var shot by remember { mutableStateOf(false) }

        fun burnAndClose() {
            if (!burned && shown) {
                burned = true
                onBurn(((elapsedMs + 999) / 1000).toInt(), shot)
                secret?.mediaRef?.let(onForgetMedia)
                bitmap = null
                path = null
            }
            onClose()
        }
        val close by rememberUpdatedState(::burnAndClose)

        LaunchedEffect(Unit) {
            val s = loadSecret()
            if (s?.mediaRef == null) { onClose(); return@LaunchedEffect }
            secret = s
            val p = loadMedia(s.mediaRef)
            if (p == null) { failed = true; return@LaunchedEffect }
            if (s.kind != "video") {
                bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        com.alphamessenger.data.MediaVault.decodeFile(p, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 2160 || bounds.outHeight / sample > 2160) sample *= 2
                        com.alphamessenger.data.MediaVault.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })
                    }.getOrNull()
                }
                if (bitmap == null) { failed = true; return@LaunchedEffect }
                shown = true
            }
            path = p
        }
        // A photo's seconds run from the moment it is on screen.
        LaunchedEffect(shown) {
            if (!shown) return@LaunchedEffect
            shownAt = System.currentTimeMillis()
            val limit = (secret?.secs ?: 0) * 1000L
            while (!burned) {
                androidx.compose.runtime.withFrameMillis { }
                elapsedMs = System.currentTimeMillis() - shownAt
                if (secret?.kind != "video" && limit > 0 && elapsedMs >= limit) { close(); break }
            }
        }
        // Leaving the app ends the view the same way closing does.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) close() }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        DisposableEffect(Unit) {
            val activity = ctx as? android.app.Activity
            if (android.os.Build.VERSION.SDK_INT >= 34 && activity != null) {
                val cb = android.app.Activity.ScreenCaptureCallback { shot = true }
                runCatching { activity.registerScreenCaptureCallback(activity.mainExecutor, cb) }
                onDispose { runCatching { activity.unregisterScreenCaptureCallback(cb) } }
            } else onDispose { }
        }
        androidx.activity.compose.BackHandler { close() }

        val s = secret
        val video = s?.kind == "video"
        val limit = (s?.secs ?: 0) * 1000f
        val timed = shown && !video && limit > 0f
        val chrome by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "chrome")
        // One fixed tree: parts fade in and out, nothing is inserted while the view is open.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bmp = bitmap
            val p = path
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    failed -> Text(stringResource(R.string.glance_unavailable), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    bmp != null -> Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    video && p != null -> GlanceVideo(p, onStarted = { shown = true }, onEnded = { close() })
                    else -> CircularProgressIndicator(color = GlanceAccent, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
            }
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                // The time left, as a line across the top (none for "until closed" and for video).
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(3.dp)
                        .graphicsLayer { alpha = if (timed) 1f else 0f }
                        .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = if (limit > 0f) (1f - elapsedMs / limit).coerceIn(0f, 1f) else 1f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }.background(Color.White),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(senderName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            when {
                                video -> stringResource(R.string.glance_video_once)
                                (s?.secs ?: 0) == 0 -> stringResource(R.string.glance_until_closed_sub)
                                else -> stringResource(R.string.glance_photo_for, s?.secs ?: 0)
                            },
                            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                        )
                    }
                    val left = if (limit > 0f) ((limit - elapsedMs).coerceAtLeast(0f) / 1000f).toInt() + 1 else 0
                    Text(
                        stringResource(R.string.whisper_secs, left.coerceAtMost(s?.secs ?: 0)),
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 12.dp).graphicsLayer { alpha = if (timed) 1f else 0f },
                    )
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable { close() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AlphaIcons.Filled.Close, stringResource(R.string.common_close), tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
                    .graphicsLayer { alpha = chrome }
                    .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.Outlined.CameraAlt, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.glance_one_view_footer), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
    }
}

/** The video, played once from the start; the end of it is the end of the view. */
@Composable
private fun GlanceVideo(path: String, onStarted: () -> Unit, onEnded: () -> Unit) {
    val ctx = LocalContext.current
    val started by rememberUpdatedState(onStarted)
    val ended by rememberUpdatedState(onEnded)
    val player = remember(path) {
        ExoPlayer.Builder(ctx.applicationContext).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(ctx.applicationContext)).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path))))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() = started()
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended() }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    AndroidView(
        // A texture view draws into the secure window itself, so it is never captured.
        factory = { c ->
            (android.view.LayoutInflater.from(c).inflate(R.layout.round_player, null) as PlayerView)
                .apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
