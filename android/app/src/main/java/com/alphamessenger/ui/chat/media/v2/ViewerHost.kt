package com.alphamessenger.ui.chat.media.v2

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.layout.ime
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.alphamessenger.R
import com.alphamessenger.ui.chat.media.MediaCapabilities
import com.alphamessenger.ui.chat.media.MediaItem
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeActionsMenu
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeBottomBar
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeCaption
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeFilmstrip
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeActionRow
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeReactionPills
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeReplyComposer
import com.alphamessenger.ui.chat.media.v2.chrome.ReactionPickerSheet
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeThumb
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeTopBar
import com.alphamessenger.ui.chat.media.v2.chrome.ScrubFrames
import kotlinx.coroutines.launch
import com.alphamessenger.ui.chat.media.v2.chrome.ChromeTransportBar
import com.alphamessenger.ui.chat.media.v2.gesture.GestureEvent
import com.alphamessenger.ui.chat.media.v2.gesture.rememberGestureController
import com.alphamessenger.ui.chat.media.v2.gesture.viewerGestures
import com.alphamessenger.ui.chat.media.v2.hero.HeroEvent
import com.alphamessenger.ui.chat.media.v2.hero.HeroGeometry
import com.alphamessenger.ui.chat.media.v2.hero.HeroPhase
import com.alphamessenger.ui.chat.media.v2.hero.HeroState
import com.alphamessenger.ui.chat.media.v2.hero.rememberHeroController
import com.alphamessenger.ui.chat.media.v2.motion.Motion
import com.alphamessenger.ui.chat.media.v2.motion.MotionSpec
import com.alphamessenger.ui.chat.media.v2.pager.PagerController
import com.alphamessenger.ui.chat.media.v2.pager.PagerMediaSource
import com.alphamessenger.ui.chat.media.v2.pager.rememberPagerController
import com.alphamessenger.ui.chat.media.v2.transform.TransformLayer
import com.alphamessenger.ui.chat.media.v2.transform.rememberTransformController
import com.alphamessenger.ui.chat.media.v2.transform.rememberTransformState
import com.alphamessenger.ui.chat.media.v2.window.WindowController
import com.alphamessenger.ui.chat.media.v2.window.attach
import com.alphamessenger.ui.chat.media.v2.window.rememberWindowController
import com.alphamessenger.util.AppStrings
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 5_000L
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * The single coordinator of the viewer.
 *
 * Pages, chrome, gestures and the hero transition know nothing about each
 * other — they communicate only via events routed through this file, and no
 * subsystem ever calls a neighbour. The coordinator itself moves and animates
 * nothing: it flips flags and forwards events.
 *
 * Layer order, bottom to top: background → pages → flying frame → chrome.
 * Gestures are a root modifier, not a top layer: an input node stacked above
 * the chrome would swallow its buttons (Box siblings don't share input), so
 * input is read at container level, before the children.
 */
@Composable
fun ViewerHost(
    session: ViewerSession,
    callbacks: ViewerCallbacks,
    modifier: Modifier = Modifier,
    state: ViewerHostState = rememberViewerHostState(session.pages.items.firstOrNull()?.id),
    window: WindowController = rememberWindowController(),
) {
    if (session.pages.items.isEmpty()) return

    val scope = rememberCoroutineScope()
    val pagerController = rememberPagerController(session.pages)
    val current = pagerController.current
    val caps = current?.let { MediaCapabilities.of(it) }

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 140.dp.toPx() }

    // The viewer's ONLY coordinate system: the container's actual size.
    // Read from state at use time, not captured by composition — a captured
    // value lags a frame and can hand zero geometry to the transition.
    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    fun measuredViewport(): Size =
        Size(viewportPx.width.toFloat(), viewportPx.height.toFloat())

    // The window belongs to its owner: the coordinator only reports whether
    // screenshot protection is needed, and knows nothing about the window.
    window.attach(secure = caps?.requiresSecureWindow == true)

    // The page creates the player and hands it up; the seek bar belongs to chrome.
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    // Frame offset at release: Hero must start from where the image actually
    // sits, not from the center.
    var exitDragY by remember { mutableStateOf(0f) }

    // --- Transition: pages hide for the flight and return after landing ---
    val heroController = rememberHeroController(
        bitmaps = session.frames,
        onPlaceholder = { id -> session.placeholder.setActive(id) },
        onEvent = { event ->
            when (event) {
                is HeroEvent.Started -> {
                    state.pagerVisible = !event.enabled
                }
                is HeroEvent.Progress -> Unit
                is HeroEvent.Finished -> {
                    if (event.exiting) {
                        callbacks.onDismiss()
                    } else {
                        state.pagerVisible = true
                        state.phase = ViewerPhase.VIEWING
                    }
                }
            }
        },
    )

    fun requestClose() {
        if (state.phase == ViewerPhase.CLOSING) return
        state.phase = ViewerPhase.CLOSING
        state.closeMenu()
        val id = pagerController.current?.id
        val measured = measuredViewport()
        // Rects are computed ONCE before the start; the frame then flies
        // along them and recomputes nothing.
        val geometry = id?.let { session.geometry.geometryFor(it, measured) }?.reversed()
            ?.let { g ->
                if (exitDragY == 0f) g
                else HeroGeometry(g.startRect.translate(0f, exitDragY), g.endRect)
            }
        scope.launch { heroController.exit(geometry) }
    }

    // --- Frame display: the single owner of scale and pan ---
    val transformState = rememberTransformState(pagerController.currentIndex)
    val transformController = rememberTransformController(
        state = transformState,
        canZoom = caps?.canZoom ?: false,
        viewport = measuredViewport(),
    )

    // --- Gestures: events only, no decisions inside ---
    val gestureController = rememberGestureController(
        canZoom = caps?.canZoom ?: false,
        // Off while the caption is open (its text scrolls vertically) or a
        // reply is being typed.
        canDragToDismiss = (caps?.canDragToDismiss ?: true) && !state.captionExpanded && !state.composing,
        dismissThresholdPx = dismissThresholdPx,
        isZoomed = { transformState.isZoomed },
        onEvent = { event ->
            // The frame is handled by the display layer; the rest by the coordinator.
            transformController.onEvent(event)
            when (event) {
                is GestureEvent.Tap -> when {
                    state.menuOpen -> state.closeMenu()
                    // A tap on the video closes the reply line; the text stays.
                    state.composing -> state.setComposing(false)
                    else -> state.toggleChrome()
                }

                // For video the same gesture means seek: one gesture, two meanings.
                is GestureEvent.DoubleTap -> if (caps?.canZoom != true) {
                    player?.let { exo ->
                        val delta = if (event.forward) SEEK_STEP_MS else -SEEK_STEP_MS
                        exo.seekTo((exo.currentPosition + delta).coerceAtLeast(0L))
                        state.showSeekHint(event.forward)
                    }
                }

                // Chrome leaves BEFORE the frame moves: its background covers
                // the system-bar areas and must not fade with the frame.
                is GestureEvent.DragStarted -> state.onDragStarted()
                is GestureEvent.DragFinished -> state.onDragEnded()
                is GestureEvent.DismissRequested -> {
                    exitDragY = transformState.dragOffset
                    requestClose()
                }
                else -> Unit
            }
        },
    )

    // System back closes via the same path as the gesture: the flight.
    LaunchedEffect(state.closeRequested) {
        if (state.closeRequested) requestClose()
    }

    // Takeoff: once per open, and only once the container is measured — else
    // the frame would fly into zero geometry. No frame → show pages directly.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(viewportPx) {
        // Starting with an unmeasured container is forbidden: geometry would be zero.
        if (opened || viewportPx == IntSize.Zero) return@LaunchedEffect
        opened = true
        val measured = measuredViewport()
        val id = session.pages.items.getOrNull(session.pages.firstPage)?.id
        if (id == null) {
            state.pagerVisible = true
            state.phase = ViewerPhase.VIEWING
            return@LaunchedEffect
        }
        val geometry = session.geometry.geometryFor(id, measured)
        // The flight runs in a scope not tied to container size: running it
        // directly in this effect would let a window remeasure cancel it
        // mid-way, freezing the frame over hidden content.
        scope.launch { heroController.enter(id, geometry) }
    }

    LaunchedEffect(pagerController.currentIndex) {
        state.onPageChanged()
        transformController.reset()
        // A different bubble must hide now — the return flight will target it.
        pagerController.current?.let { heroController.retarget(it.id) }
        callbacks.onPageChanged(pagerController.currentIndex, pagerController.current)
    }

    // --- Playback: values for the bottom bar ---
    LaunchedEffect(player) {
        val exo = player ?: return@LaunchedEffect
        while (true) {
            state.setPlaybackTime(
                position = exo.currentPosition.coerceAtLeast(0L),
                duration = exo.duration.coerceAtLeast(0L),
            )
            delay(200)
        }
    }
    // Seek preview frames: built while the video plays, ready before the first drag.
    val scrubItemId = pagerController.current?.id.takeIf { player != null }
    val scrubFrames = remember(scrubItemId) {
        pagerController.current?.takeIf { scrubItemId != null }?.let { i -> ScrubFrames { session.media.localVideo(i) } }
    }
    androidx.compose.runtime.DisposableEffect(scrubFrames) {
        // Its own scope: the composition's is already cancelled when this runs.
        onDispose { scrubFrames?.let { f -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { f.close() } } }
    }
    LaunchedEffect(scrubFrames) { scrubFrames?.warm() }
    LaunchedEffect(player, state.speed) { player?.playbackParameters = PlaybackParameters(state.speed) }
    LaunchedEffect(player, state.muted) { player?.volume = if (state.muted) 0f else 1f }

    state.seekHint?.let { forward ->
        LaunchedEffect(forward, pagerController.currentIndex) {
            delay(600)
            state.clearSeekHint()
        }
    }

    // The background follows the frame's motion: it appears and fades by the
    // same fraction of the transition, and dissolves with the drag.
    val hero = heroController.state
    val heroFade = when {
        !hero.isRunning -> if (state.phase == ViewerPhase.CLOSING) 0f else 1f
        // On exit the dim fades faster than the flight: by landing the screen
        // already looks like chat.
        hero.phase == HeroPhase.EXITING -> (1f - hero.progress).let { it * it }
        // On open the background is opaque from the very first frame; fading
        // it in mid-flight would show the chat through and read as a flash.
        // The flying frame provides continuity, not a transparent background.
        else -> 1f
    }
    // By the dismiss threshold the background must already be transparent:
    // otherwise the frame flies off while a black screen remains.
    val dragFraction = (kotlin.math.abs(transformState.dragOffset) / dismissThresholdPx)
        .coerceIn(0f, 1f)
    val backdrop = heroFade * (1f - dragFraction)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportPx = it }
            .viewerGestures(
                controller = gestureController,
                key = pagerController.currentIndex,
                enabled = state.gesturesEnabled,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BackgroundLayer(alpha = backdrop)

        PagerLayer(
            controller = pagerController,
            source = session.media,
            visible = state.pagerVisible,
            fillScreen = state.fillScreen,
            showPlayButton = state.chromeShown,
            autoPlayId = session.pages.items.getOrNull(session.pages.firstPage)?.id,
            onPlayerChanged = { player = it },
            transform = { isCurrent, content ->
                TransformLayer(
                    state = transformState,
                    visible = state.pagerVisible && isCurrent,
                    content = content,
                )
            },
        )

        HeroLayer(heroController.state)

        SeekHintLayer(state.seekHint)

        ChromeLayer(
            visible = state.chromeShown,
            top = {
                ChromeTopBar(
                    title = current?.senderName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.media_title),
                    subtitle = current?.timestamp?.let { viewerSentAtLabel(it) },
                    counter = if (session.pages.size > 1) {
                        stringResource(R.string.media_page_of, pagerController.currentIndex + 1, pagerController.count)
                    } else {
                        null
                    },
                    showMenuButton = caps?.hasAnyAction == true,
                    onBack = { requestClose() },
                    onMenu = { state.openMenu() },
                    menu = { ActionsMenu(state, current, caps, callbacks) },
                )
            },
            bottom = {
                if (state.composing) {
                    ReplyBar(state, pagerController, callbacks)
                } else {
                    BottomBar(
                        state = state,
                        session = session,
                        controller = pagerController,
                        player = player,
                        caps = caps,
                        callbacks = callbacks,
                        scrubFrames = scrubFrames,
                    )
                }
            },
        )

        ReactionPickerSheet(
            visible = state.reactionSheetOpen,
            mine = current?.let { callbacks.myReactions(it) } ?: emptySet(),
            policy = callbacks.reactionPolicy,
            onPick = { emoji ->
                current?.let { callbacks.onReact?.invoke(it, emoji) }
                state.setReactionSheet(false)
            },
            onDismiss = { state.setReactionSheet(false) },
        )
    }
}

/** Viewer background. Knows nothing about the transition or the chrome. */
@Composable
fun BoxScope.BackgroundLayer(alpha: Float = 1f, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
}

/**
 * Pages. The host only gives them their place on screen: the controller
 * decides what to show, the renderer how to draw it.
 */
@Composable
fun BoxScope.PagerLayer(
    controller: PagerController,
    source: PagerMediaSource,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    fillScreen: Boolean = false,
    showPlayButton: Boolean = true,
    autoPlayId: String? = null,
    onPlayerChanged: (ExoPlayer?) -> Unit = {},
    transform: @Composable (isCurrent: Boolean, content: @Composable () -> Unit) -> Unit =
        { _, content -> content() },
) {
    com.alphamessenger.ui.chat.media.v2.pager.PagerLayer(
        controller = controller,
        source = source,
        modifier = modifier,
        visible = visible,
        fillScreen = fillScreen,
        showPlayButton = showPlayButton,
        autoPlayId = autoPlayId,
        onPlayerChanged = onPlayerChanged,
        transform = transform,
    )
}

/**
 * The flying frame. The layer does not exist while no transition runs: the
 * host only reads the transition state and delegates the drawing.
 */
@Composable
fun BoxScope.HeroLayer(state: HeroState) {
    if (!state.isRunning || !state.isEnabled) return
    com.alphamessenger.ui.chat.media.v2.hero.HeroLayer(
        bitmap = state.bitmap,
        rect = state.geometry?.currentRect(state.progress),
    )
}

/**
 * Chrome: top bar above, bottom bar below. The layer does not know whether a
 * transition is running — it receives a ready-made "show or hide".
 *
 * Show/hide is ONE rule for both bars: one duration, one curve, both edges
 * move symmetrically.
 */
@Composable
fun BoxScope.ChromeLayer(
    visible: Boolean,
    top: @Composable () -> Unit = {},
    bottom: @Composable () -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(chromeSpec()) + slideInVertically(chromeSpec()) { -it },
        exit = fadeOut(chromeSpec()) + slideOutVertically(chromeSpec()) { -it },
        modifier = Modifier.align(Alignment.TopCenter),
    ) { top() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(chromeSpec()) + slideInVertically(chromeSpec()) { it },
        exit = fadeOut(chromeSpec()) + slideOutVertically(chromeSpec()) { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) { bottom() }
}

/** Chrome show/hide: name the motion kind; its internals are not our business. */
private fun <T> chromeSpec(): FiniteAnimationSpec<T> = MotionSpec.of(Motion.CHROME_FADE)

/** Seek hint shown after a double tap on video. */
@Composable
private fun BoxScope.SeekHintLayer(forward: Boolean?) {
    if (forward == null) return
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 44.dp),
        contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                if (forward) AlphaIcons.Filled.KeyboardDoubleArrowRight
                else AlphaIcons.Filled.KeyboardDoubleArrowLeft,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
            Text(pluralStringResource(R.plurals.call_seconds_abbrev, 5, 5), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Menu contents: an unavailable action is passed down as `null`. Availability
 * rules are consulted in ONE place — here, not inside the chrome.
 */
@Composable
private fun ActionsMenu(
    state: ViewerHostState,
    item: MediaItem?,
    caps: MediaCapabilities?,
    callbacks: ViewerCallbacks,
) {
    if (item == null || caps == null) return
    ChromeActionsMenu(
        expanded = state.menuOpen,
        onDismissMenu = { state.closeMenu() },
        onReply = callbacks.onReply.takeIf { caps.canReply }?.let { cb -> { cb(item) } },
        onForward = callbacks.onForward.takeIf { caps.canForward }?.let { cb -> { cb(item) } },
        onShowInChat = callbacks.onShowInChat.takeIf { caps.canShowInChat }?.let { cb -> { cb(item) } },
        onShare = callbacks.onShare.takeIf { caps.canShare }?.let { cb -> { cb(item) } },
        saveLabel = if (caps.canSave && callbacks.onSave != null) {
            if (item.isVideo) stringResource(R.string.media_save_video) else stringResource(R.string.media_save_photo)
        } else {
            null
        },
        onSave = callbacks.onSave.takeIf { caps.canSave }?.let { cb -> { cb(item) } },
        onOpenWith = callbacks.onOpenWith.takeIf { caps.canOpenWith }?.let { cb -> { cb(item) } },
        onShowAllMedia = callbacks.onShowAllMedia.takeIf { caps.canShowAllMedia },
        onInfo = callbacks.onInfo.takeIf { caps.canShowInfo }?.let { cb -> { cb(item) } },
    )
}

/** Bottom bar contents: absent parts are simply absent; no empty strip is drawn. */
@Composable
private fun BottomBar(
    state: ViewerHostState,
    session: ViewerSession,
    controller: PagerController,
    player: ExoPlayer?,
    caps: MediaCapabilities?,
    callbacks: ViewerCallbacks,
    scrubFrames: ScrubFrames?,
    modifier: Modifier = Modifier,
) {
    val caption = if (caps?.canShowCaption == true) controller.current?.caption.orEmpty() else ""
    val byId = remember(session.pages) { session.pages.items.associateBy { it.id } }

    // The filmstrip shows the current element's ALBUM, not the whole chat
    // feed: items of one album share the id prefix before "#al".
    val albumIndices = remember(session.pages, controller.currentIndex) {
        val head = controller.current?.id?.substringBefore("#al")
        if (head == null) {
            emptyList()
        } else {
            session.pages.items.mapIndexedNotNull { index, item ->
                index.takeIf { item.id.substringBefore("#al") == head }
            }
        }
    }
    val thumbs = remember(albumIndices, session.pages) {
        albumIndices.mapNotNull { index ->
            session.pages.items.getOrNull(index)?.let { ChromeThumb(it.id, it.isVideo) }
        }
    }

    ChromeBottomBar(
        modifier = modifier,
        caption = if (caption.isNotBlank()) {
            {
                ChromeCaption(
                    text = caption,
                    expanded = state.captionExpanded,
                    onToggle = { state.toggleCaption() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        } else {
            null
        },
        filmstrip = if (thumbs.size > 1) {
            {
                ChromeFilmstrip(
                    thumbs = thumbs,
                    selectedIndex = albumIndices.indexOf(controller.currentIndex).coerceAtLeast(0),
                    onSelect = { index -> albumIndices.getOrNull(index)?.let { controller.jumpTo(it) } },
                    thumbnail = { thumb -> byId[thumb.id]?.let { session.media.poster(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            null
        },
        transport = player?.let {
            {
                // How much of the file is here while it still downloads (null = all).
                val item = controller.current
                val dlFlow = remember(item?.id) { item?.let { callbacks.downloadOf(it) } }
                val dl = dlFlow?.collectAsState()?.value
                val downloading = dl != null && !dl.isReady && (dl.isActive || dl.isPaused || dl.isFailed || dl.isWaitingNetwork)
                ChromeTransportBar(
                    downloaded = if (downloading) dl!!.progress.fraction else null,
                    downloadNote = when {
                        !downloading -> null
                        dl!!.isActive -> "↓ " + dl.progress.percent + "%"
                        dl.isWaitingNetwork -> androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.viewer_download_waiting)
                        else -> androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.viewer_download_stopped)
                    },
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    speed = state.speed,
                    muted = state.muted,
                    fillScreen = state.fillScreen,
                    onSeek = { ms -> it.seekTo(ms) },
                    frames = scrubFrames,
                    onSpeedChange = { value -> state.setSpeed(value) },
                    onMuteToggle = { state.toggleMuted() },
                    onFitToggle = { state.toggleFillScreen() },
                )
            }
        },
        reactions = controller.current?.let { item ->
            val all = callbacks.reactions(item)
            if (all.isEmpty() || callbacks.onReact == null) null else {
                {
                    ChromeReactionPills(
                        reactions = all,
                        mine = callbacks.myReactions(item),
                        onToggle = { emoji -> callbacks.onReact.invoke(item, emoji) },
                        onMore = { state.setReactionSheet(true) },
                    )
                }
            }
        },
        reply = controller.current?.takeIf { caps?.canReply == true }?.let { item ->
            {
                ChromeActionRow(
                    hasMyReaction = callbacks.myReactions(item).isNotEmpty(),
                    onReply = callbacks.onQuickReply?.let { { state.setComposing(true) } },
                    onReact = callbacks.onReact?.let { { state.setReactionSheet(true) } },
                )
            }
        },
    )
}

/** Reply mode: the bottom bar is ONLY the input line, lifted by the keyboard. */
@Composable
private fun ReplyBar(
    state: ViewerHostState,
    controller: PagerController,
    callbacks: ViewerCallbacks,
) {
    val item = controller.current ?: return
    val send = callbacks.onQuickReply ?: return
    // System back hides the keyboard first; the line goes with it.

    val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var sawIme by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) sawIme = true else if (sawIme) state.setComposing(false)
    }
    ChromeBottomBar(
        reply = {
            ChromeReplyComposer(
                text = state.replyDraft,
                onTextChange = { state.setReplyDraft(it) },
                onSend = {
                    send(item, state.replyDraft.trim())
                    state.setReplyDraft("")
                    state.setComposing(false)
                },
            )
        },
    )
}

/** "Today, 14:03" / "12 March, 14:03" — sent date and time for the top bar. */
internal fun viewerSentAtLabel(timestamp: String): String = runCatching {
    val zdt = java.time.OffsetDateTime.parse(timestamp)
        .atZoneSameInstant(java.time.ZoneId.systemDefault())
    val date = zdt.toLocalDate()
    val today = java.time.LocalDate.now()
    val day = when {
        date == today -> AppStrings.get(R.string.common_today)
        date == today.minusDays(1) -> AppStrings.get(R.string.common_yesterday)
        else -> {
            // Resolved per call so a language switch applies without a restart.
            val month = AppStrings.getStringArray(R.array.chat_months_genitive)[date.monthValue - 1]
            if (date.year == today.year) "${date.dayOfMonth} $month"
            else "${date.dayOfMonth} $month ${date.year}"
        }
    }
    "%s, %02d:%02d".format(day, zdt.hour, zdt.minute)
}.getOrDefault("")
