package com.alphamessenger.ui.stories

import com.alphamessenger.ui.icons.AlphaIcons
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.alphamessenger.ui.theme.BlackSystemBars
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.alphamessenger.R

/**
 * Full-screen story player. [startKey] = "mine" for our own stories, else the
 * author's chat id; contacts' stories play on to the next author. Tap right /
 * left = next / previous, hold = pause, swipe down = close. When the author
 * did not allow screenshots, the window is marked secure (screenshots and
 * screen recording show black).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewerScreen(
    startKey: String,
    viewModel: StoriesViewModel,
    onClose: () -> Unit,
) {
    val st by viewModel.state.collectAsState()
    val ctx = LocalContext.current
    BlackSystemBars()
    // Close exactly once: a swipe reports many moves, and every extra "back"
    // would pop the screen under the stories too (a blank screen).
    var closed by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (!closed) { closed = true; onClose() } }
    // Swipe up or down to close: the story follows the finger, then the top bar
    // leaves upward, the bottom bar downward and the picture fades.
    val dragY = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    val anim = androidx.compose.runtime.rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    val closeAnimated: () -> Unit = {
        if (!leaving && !closed) {
            leaving = true
            anim.launch {
                leave.animateTo(1f, tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                close()
            }
        }
    }
    val haptic = LocalHapticFeedback.current
    val mineMode = startKey == KEY_MINE
    // The queue is fixed when the viewer opens (stories marked seen must not reorder it).
    val queue = remember(st.loaded) {
        if (mineMode) listOf(StoryAuthor(KEY_MINE, "", st.myName, st.myAvatar, st.mine))
        else st.authors.dropWhile { it.chatId != startKey }.ifEmpty { st.authors }
    }
    var authorIdx by remember { mutableIntStateOf(0) }
    var storyIdx by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var replying by remember { mutableStateOf(false) }
    var showViewers by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    val author = queue.getOrNull(authorIdx)
    val story = author?.stories?.getOrNull(storyIdx)
    if (author == null || story == null) {
        // Still reading the stories: wait; read and nothing there: close.
        if (st.loaded) LaunchedEffect(Unit) { close() }
        else Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    fun next() {
        if (storyIdx + 1 < author.stories.size) storyIdx++
        else if (authorIdx + 1 < queue.size) { authorIdx++; storyIdx = 0 }
        else close()
    }
    var restart by remember { mutableIntStateOf(0) }
    fun prev() {
        when {
            storyIdx > 0 -> storyIdx--
            authorIdx > 0 -> { authorIdx--; storyIdx = (queue[authorIdx].stories.size - 1).coerceAtLeast(0) }
            else -> restart++ // the very first story: from its beginning
        }
    }

    // Android 14+: a screenshot of someone's story is reported to its author.
    DisposableEffect(story.id, mineMode) {
        val act = ctx as? Activity
        if (act == null || mineMode || android.os.Build.VERSION.SDK_INT < 34) return@DisposableEffect onDispose { }
        val cb = Activity.ScreenCaptureCallback {
            android.util.Log.w("StoryShot", "screenshot of story ${story.id}")
            viewModel.screenshot(story)
        }
        runCatching { act.registerScreenCaptureCallback(act.mainExecutor, cb) }
            .onFailure { android.util.Log.w("StoryShot", "capture callback not registered", it) }
        onDispose { runCatching { act.unregisterScreenCaptureCallback(cb) } }
    }
    // No screenshots unless the author allowed them (our own: always allowed).
    val secure = !mineMode && !story.allowScreenshots
    com.alphamessenger.ui.components.SecureWindow(secure)

    var mediaPath by remember(story.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(story.id) {
        // The next one downloads while this one plays.
        val nextStory = author.stories.getOrNull(storyIdx + 1) ?: queue.getOrNull(authorIdx + 1)?.stories?.firstOrNull()
        nextStory?.let { viewModel.prefetch(listOf(it)) }
        if (!mineMode) viewModel.markSeen(story)
        if (story.kind != "text" && story.mediaRef.isNotBlank()) mediaPath = viewModel.mediaPath(story.mediaRef)
    }
    val isVideo = story.kind == "video"
    val ready = story.kind == "text" || mediaPath != null
    var typing by remember { mutableStateOf(false) }
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    // The story waits while you answer (field focused or keyboard up).
    val hold = paused || replying || typing || imeOpen || showViewers || !ready
    // Photo and text: 6 s on a timer.
    LaunchedEffect(story.id, restart) { progress.snapTo(0f) }
    LaunchedEffect(story.id, hold, restart) {
        if (isVideo || hold) { progress.stop(); return@LaunchedEffect }
        val left = ((1f - progress.value) * 6000).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        next()
    }
    // Video: its whole length (≤60 s), driven by the player itself; it fills
    // its own bar and moves on when the video ends.
    val player = if (isVideo && mediaPath != null) rememberStoryPlayer(mediaPath!!) else null
    var vPos by remember(story.id) { androidx.compose.runtime.mutableLongStateOf(0L) }
    var vDur by remember(story.id) { androidx.compose.runtime.mutableLongStateOf(0L) }
    if (player != null) {
        LaunchedEffect(player, restart) { if (restart > 0) player.seekTo(0) }
        LaunchedEffect(player, hold) { player.playWhenReady = !hold }
        LaunchedEffect(player) {
            while (true) {
                if (player.duration > 0) vDur = player.duration
                vPos = player.currentPosition
                if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) { next(); break }
                kotlinx.coroutines.delay(80)
            }
        }
    }
    // Taps always act on the CURRENT story (the gesture is installed once).
    val onTapNow by androidx.compose.runtime.rememberUpdatedState { back: Boolean -> if (back) prev() else next() }
    BackHandler { closeAnimated() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val closeAt = with(density) { 110.dp.toPx() }
    // 0..1: how far the bars are pushed out (the drag, then the closing).
    val barsOut = { maxOf(leave.value, (kotlin.math.abs(dragY.value) / (closeAt * 1.6f)).coerceIn(0f, 1f)) }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = (1f - leave.value) * (1f - (kotlin.math.abs(dragY.value) / (closeAt * 4f)).coerceIn(0f, 0.6f))))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { paused = true },
                    onDragEnd = {
                        if (kotlin.math.abs(dragY.value) > closeAt) closeAnimated()
                        else anim.launch { dragY.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f)); paused = false }
                    },
                    onDragCancel = { anim.launch { dragY.animateTo(0f); paused = false } },
                ) { change, drag ->
                    change.consume()
                    anim.launch { dragY.snapTo(dragY.value + drag) }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // Hold = pause; letting go just goes on (a hold is not a tap).
                    onPress = { paused = true; tryAwaitRelease(); paused = false },
                    onLongPress = { },
                    // Left third = back, the rest = forward.
                    onTap = { o -> onTapNow(o.x < size.width / 3f) },
                )
            },
    ) {
        // --- Content ---
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val pull = (kotlin.math.abs(dragY.value) / size.height).coerceIn(0f, 1f)
                translationY = dragY.value * 0.6f
                val sc = 1f - pull * 0.25f - leave.value * 0.06f
                scaleX = sc; scaleY = sc
                alpha = 1f - leave.value
                clip = pull > 0f
                shape = RoundedCornerShape((pull * 120f).coerceAtMost(28f).dp)
            },
        ) {
        when (story.kind) {
            "text" -> Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(story.bg.map { Color(it) })).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(story.text, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 38.sp,
                    fontFamily = StoriesViewModel.fontFamily(story.font))
            }
            "image" -> mediaPath?.let { p ->
                AsyncImage(model = java.io.File(p), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            "video" -> player?.let { pl ->
                AndroidView(
                    factory = { c -> androidx.media3.ui.PlayerView(c).apply { useController = false; this.player = pl } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (ready && story.stickers.isNotEmpty()) StickerLayer(story.stickers)
        if (!ready) Text(stringResource(R.string.common_loading), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
        }

        // --- Top: progress, author, close ---
        Column(
            Modifier.fillMaxWidth()
                .graphicsLayer { val o = barsOut(); translationY = -size.height * o; alpha = 1f - o }
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // One bar per story, all of them at once.
                author.stories.forEachIndexed { i, _ ->
                    val f = when {
                        i < storyIdx -> 1f
                        i == storyIdx -> if (isVideo) (if (vDur > 0) (vPos.toFloat() / vDur).coerceIn(0f, 1f) else 0f) else progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(f).height(3.dp).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoryAvatar(author.avatarPath, author.chatId, com.alphamessenger.ui.components.avatarInitial(author.name), 38)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (mineMode) stringResource(R.string.stories_my) else author.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(agoLabel(story.createdAt), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
                if (mineMode) {
                    IconButton(onClick = { viewModel.deleteMine(story); next() }) {
                        Icon(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.common_delete), tint = Color.White)
                    }
                }
                IconButton(onClick = closeAnimated) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = Color.White) }
            }
        }

        // --- Bottom ---
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { val o = barsOut(); translationY = size.height * o; alpha = 1f - o }
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .navigationBarsPadding().imePadding().padding(12.dp),
        ) {
            if (story.caption.isNotBlank()) {
                Text(story.caption, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp))
            }
            if (mineMode) {
                val viewers = story.viewers
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.14f))
                        .clickable { showViewers = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Outlined.Visibility, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.stories_views_n, viewers.size), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (story.allowReplies) {
                var reply by remember(story.id) { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                            .background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 18.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (reply.isEmpty()) Text(stringResource(R.string.stories_reply_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                        BasicTextField(
                            value = reply,
                            onValueChange = { reply = it.take(2000); replying = it.isNotEmpty() },
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(Color.White),
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    val focus = androidx.compose.ui.platform.LocalFocusManager.current
                    if (reply.isNotBlank()) {
                        IconButton(onClick = {
                            viewModel.reply(story, reply, mediaPath); reply = ""; replying = false
                            focus.clearFocus()
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.stories_reply_sent), android.widget.Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
                            Icon(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.common_send), tint = Color.White)
                        }
                    } else {
                        val liked = st.authors.asSequence().flatMap { it.stories.asSequence() }
                            .firstOrNull { it.id == story.id }?.myReaction.orEmpty().isNotEmpty()
                        IconButton(onClick = {
                            com.alphamessenger.util.Haptics.tick(ctx)
                            viewModel.react(story, "❤️")
                        }, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
                            Icon(if (liked) AlphaIcons.Filled.Favorite else AlphaIcons.Outlined.FavoriteBorder, stringResource(R.string.viewer_react),
                                tint = if (liked) Color(0xFFFF5A79) else Color.White)
                        }
                    }
                }
                Text(
                    stringResource(R.string.stories_reply_note, author.name),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showViewers) {
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { showViewers = false }) {
            val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(stringResource(R.string.stories_views_n, story.viewers.size), color = cs.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (story.viewers.isEmpty()) {
                    Text(stringResource(R.string.stories_no_views), color = cs.textMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(story.viewers, key = { it.sid }) { v ->
                        ViewerRow(v, viewModel)
                    }
                }
                Text(stringResource(R.string.stories_views_note), color = cs.textMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ViewerRow(v: StoryViewer, viewModel: StoriesViewModel) {
    val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
    var who by remember(v.sid) { mutableStateOf("" to "") }
    LaunchedEffect(v.sid) {
        val p = runCatching { viewModel.repository.getPeerProfile(v.sid) }.getOrNull()
        who = (p?.displayName.orEmpty()) to (p?.avatarPath.orEmpty())
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        StoryAvatar(who.second, v.chatId, com.alphamessenger.ui.components.avatarInitial(who.first), 42)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(who.first.ifBlank { stringResource(R.string.chat_member_fallback) }, color = cs.textPrimary, fontSize = 15.sp)
            Text(agoLabel(v.at), color = cs.textMuted, fontSize = 12.sp)
        }
        if (v.shot) {
            Icon(com.alphamessenger.ui.icons.AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.stories_shot_title),
                tint = cs.textMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        if (v.reaction.isNotEmpty()) Text(v.reaction, fontSize = 20.sp)
    }
}

/** The player of a video story (released when the story changes). */
@Composable
private fun rememberStoryPlayer(path: String): androidx.media3.exoplayer.ExoPlayer {
    val ctx = LocalContext.current
    val player = remember(path) {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(ctx)).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path))))
            prepare()
        }
    }
    DisposableEffect(path) { onDispose { player.release() } }
    return player
}

