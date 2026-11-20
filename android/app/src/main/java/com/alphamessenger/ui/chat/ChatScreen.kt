@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.alphamessenger.ui.chat

// bubbles-split: shared media helpers extracted to chat/bubbles/BubbleShared.kt
import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import com.alphamessenger.ui.theme.outBubble
import com.alphamessenger.ui.theme.outBubbleBrush
import com.alphamessenger.ui.chat.bubbles.BubbleTokens
import com.alphamessenger.ui.components.avatarBackdrop
import com.alphamessenger.ui.chat.bubbles.bubbleShell
import com.alphamessenger.ui.chat.bubbles.bubbleFill
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import com.alphamessenger.ui.chat.text.MessageText
import com.alphamessenger.ui.chat.text.LinkAction
import com.alphamessenger.ui.chat.text.LinkActionSheet
import com.alphamessenger.ui.chat.text.TextEntity
import com.alphamessenger.ui.chat.text.toLinkAction
import com.alphamessenger.ui.chat.preview.LinkPreviewModel
import com.alphamessenger.ui.chat.preview.LinkPreviewRenderer
import com.alphamessenger.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
// Media Framework — the shared viewer (alias: not androidx.media3.common.MediaItem).
import com.alphamessenger.ui.chat.media.MediaItem as AlphaMediaItem
import com.alphamessenger.ui.chat.media.MediaViewer
import com.alphamessenger.ui.chat.media.toMediaItem
import com.alphamessenger.ui.chat.bubbles.VideoBubble
import com.alphamessenger.ui.chat.bubbles.MediaBubble
import com.alphamessenger.ui.chat.bubbles.MediaFileBubble
import com.alphamessenger.ui.chat.bubbles.PosterBitmapCache
import com.alphamessenger.ui.chat.bubbles.MediaImageCache
import com.alphamessenger.ui.chat.bubbles.DocumentThumbCache
import com.alphamessenger.ui.chat.bubbles.humanSize
import com.alphamessenger.ui.chat.bubbles.decodePosterB64
import com.alphamessenger.ui.chat.bubbles.extractVideoThumb
import com.alphamessenger.ui.chat.bubbles.audioDurationMs

import com.alphamessenger.transfer.downloadOf
import com.alphamessenger.transfer.uploadOf
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import com.alphamessenger.media.toOverlayState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.luminance
import com.alphamessenger.ui.components.glassPanel
import com.alphamessenger.ui.components.glassSource
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.TextLayoutResult
import com.alphamessenger.ui.chat.v2.BottomPanelV2
import com.alphamessenger.ui.chat.v2.BottomSurfaceState
import com.alphamessenger.ui.chat.v2.BottomSurfaceV2
import com.alphamessenger.ui.chat.v2.InputBarV2
import com.alphamessenger.ui.chat.v2.PanelMode as PanelModeV2
import com.alphamessenger.ui.chat.v2.bindImeInset
import com.alphamessenger.ui.chat.v2.rememberBottomSurfaceState
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.bgBrush
import com.alphamessenger.ui.theme.themeBackground
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ChatScreen — classic conversation view.
 *
 * Works for both 1×1 chats and groups. Group-only chrome (member count,
 * sender names above bubbles) toggles on [ChatState.isGroup].
 *
 * Layout:
 *  - TopAppBar: back, contact avatar, name + presence, phone, video, profile
 *  - Messages: reverse-scrolled LazyColumn with date dividers and bubbles
 *  - Reply preview (above input) when [ChatState.replyTo] is set
 *  - Input row: emoji, text field, attach, mic→send (icon morphs on input)
 *
 * All actions wired to [ChatViewModel] which talks to the real FFI.
 */
/** Max characters per text bubble / media caption. A longer text is sent as
 *  several messages; a caption is held to it in the input. */
internal const val MAX_MESSAGE_CHARS = 4096

/**
 * V2 bottom-surface switch. When true, ChatScreen uses the new independent
 * BottomSurfaceV2 system (BottomSurfaceV2 + InputBarV2 + BottomPanelV2 +
 * BottomSurfaceState in `ui.chat.v2`). When false, the legacy bottom surface
 * (BottomSurface + InputBar + the inline panels defined later in this file)
 * is used — byte-for-byte unchanged.
 *
 * Flip this to `false` to instantly revert to the old implementation for
 * testing; the old code is kept intact until V2 is fully validated.
 */
internal const val USE_BOTTOM_SURFACE_V2 = true

/** BATTERY/PERF: compile-time gate for the per-frame/per-recomposition trace
 *  logs (KBTRACE/CompTrace/MsgTrace). `false` → R8 dead-code-eliminates every
 *  guarded Log.i in release, so no string building or I/O on the hot paths
 *  (keyboard animation frames, layout passes, every bubble compose). Flip to
 *  `true` locally when profiling the composition; never ship it `true`. */
private const val CHAT_TRACE = false

// --- TEMP "wave/lag" probe — counts ROW recompositions during scroll ---
//  Symptom: text/elements lag behind the bubble while scrolling. If a row RECOMPOSES
//  during a pure scroll it is re-laid-out a frame late → the lag/wave. A stable
//  (skippable) row is only RE-PLACED by LazyColumn, never recomposed → ~0 hits here.
//  Flip WAVE_DIAG=false to remove all overhead.
internal const val WAVE_DIAG = false

// Feature-flag bisection of MediaFileBubble: flip ONE part to `false` (→ replaced by
// a cheap same-size placeholder) and re-scroll to find which part costs the wave.
// This catches DRAW cost (glyphs, the overlay Canvas ring, the thumbnail) that the
// composition-only profiler missed. First test: DISC off.
internal object BubbleFlags {
    const val DISC = true       // MediaSurface: overlay Canvas ring + thumbnail draw
    const val TITLE = true      // filename Text
    const val SUBTITLE = true   // size/type Row (icon + text)
    const val TIMESTAMP = true  // time + status Row
    // NON-VISUAL setup (STUB removed these; test 2 kept them & still waved → suspect):
    const val STATE = true      // rememberMediaState (transfer subscribe + consider×2)
    const val POSTER = true     // produceState thumbnail decode
    const val CLICK = true      // combinedClickable on the card (gesture + ripple + semantics)
    const val RENDER = true     // the ENTIRE card render tree (Column>Row>Column + content)
    // Item-level stub: replace the WHOLE media item (MessageLayout + MessageBubble +
    // MediaFileBubble) with a Box — tests the PARENT wrappers the internal STUB kept.
    const val ITEM_STUB = false
    const val WALLPAPER = true  // chat wallpaper drawn full-screen behind everything (overdraw)
}
// TEMP bisection: render file bubbles as a trivial fixed-size Box (mount cost ~0).
// PROVEN: stub ON = scroll "very good" → the wave IS media-row composition cost.
internal const val STUB_MEDIA = false

internal object RecompProbe {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var t0 = System.nanoTime()
    fun hit(tag: String) {
        counts.merge(tag, 1) { a, b -> a + b }
        if (System.nanoTime() - t0 > 2_000_000_000L) dump()
    }
    @Synchronized private fun dump() {
        if (counts.isEmpty()) { t0 = System.nanoTime(); return }
        val total = counts.values.sum()
        val top = counts.entries.sortedByDescending { it.value }.take(8)
            .joinToString(" ") { "${it.key}=${it.value}" }
        android.util.Log.e("WAVE", "2s: $total row-recomps over ${counts.size} rows │ $top")
        counts.clear(); t0 = System.nanoTime()
    }
}

/** Non-composable side-effect call — runs once per (re)composition of the enclosing
 *  scope, so it counts recompositions without being skipped like a child composable. */
private fun waveTag(m: MessageUi): String = when {
    m.albumItems != null -> "album"
    m.isMedia && m.mediaType == "image" -> "photo"
    m.isMedia && m.mediaType == "video" -> "video"
    m.isMedia && m.mediaType == "audio" -> "audio"
    m.isMedia -> "file"
    m.callLog != null -> "call"
    else -> "text"
}

// --- TEMP phase probe — WHICH frame phase eats the time during scroll? ---
//  Answers the ONE question: is the lag born in Composition, Measure, Placement or
//  Draw. Plus `enters` = how many item slots (re)entered composition in 2s — if it
//  dwarfs the visible count, LazyColumn is destroying+recreating rows (keys/reuse).
internal object PhaseProbe {
    private val lock = Any()
    private val sum = HashMap<String, Long>()
    private val mx = HashMap<String, Long>()
    private val cnt = HashMap<String, Int>()
    private var enters = 0
    private var leaves = 0
    private val enterType = HashMap<String, Int>()
    // Scroll distance in item-indices — one item scrolled past should = ~one enter.
    private var lastIdx = -1
    private var scrolled = 0
    private var t0 = System.nanoTime()
    fun rec(phase: String, ns: Long) = synchronized(lock) {
        sum[phase] = (sum[phase] ?: 0L) + ns
        if (ns > (mx[phase] ?: 0L)) mx[phase] = ns
        cnt[phase] = (cnt[phase] ?: 0) + 1
        if (System.nanoTime() - t0 > 2_000_000_000L) dump()
    }
    // frameActive = we are INSIDE a display frame's Compose traversal → this mount
    // is ON-FRAME (LazyColumn couldn't prefetch it). Outside = prefetched during idle.
    private var frameActive = false
    private var onFrameMounts = 0
    private var prefetchMounts = 0
    private val frameTypes = ArrayList<String>(16)
    fun enter(tag: String) = synchronized(lock) {
        enters++; enterType[tag] = (enterType[tag] ?: 0) + 1
        if (frameActive) { onFrameMounts++; frameTypes.add(tag) } else prefetchMounts++
    }
    fun leave() = synchronized(lock) { leaves++ }
    // How many rows MOUNT in a single display frame — the agent's key question. If a
    // fling mounts 5-8 heavy rows in one frame, no single-bubble optimisation helps.
    private var frameLastEnters = 0
    private var maxMountsPerFrame = 0
    private val mountHist = IntArray(9) // frames that mounted 0,1,2,...,8+ rows
    fun frameStart() = synchronized(lock) {
        frameActive = true
        val d = (enters - frameLastEnters).coerceAtLeast(0)
        frameLastEnters = enters
        if (d > maxMountsPerFrame) maxMountsPerFrame = d
        mountHist[d.coerceAtMost(8)]++
    }
    fun frameEnd() = synchronized(lock) {
        if (frameTypes.size >= 3) android.util.Log.e("PHASE", "  BURST ${frameTypes.size}: ${frameTypes.joinToString(",")}")
        frameTypes.clear(); frameActive = false
    }
    fun scroll(idx: Int) = synchronized(lock) {
        if (lastIdx >= 0) scrolled += kotlin.math.abs(idx - lastIdx)
        lastIdx = idx
    }
    private fun dump() {
        // scrolled = item-indices crossed. enters/scrolled ≈ 1 = reuse OK; >>1 = churn.
        val ratio = if (scrolled > 0) enters.toDouble() / scrolled else 0.0
        val sb = StringBuilder("2s: scrolled=%d enters=%d leaves=%d churn=%.1fx".format(scrolled, enters, leaves, ratio))
        for (ph in listOf("compose", "measure", "place", "draw")) {
            val n = cnt[ph] ?: 0
            if (n > 0) sb.append(" │ %s n=%d avg=%.2f max=%.2f".format(ph, n, (sum[ph]!! / 1e6) / n, mx[ph]!! / 1e6))
        }
        sb.append(" │ maxMounts/frame=%d hist=%s │ onFrame=%d prefetched=%d".format(
            maxMountsPerFrame, mountHist.joinToString(","), onFrameMounts, prefetchMounts))
        android.util.Log.e("PHASE", sb.toString())
        maxMountsPerFrame = 0; mountHist.fill(0); frameLastEnters = 0; onFrameMounts = 0; prefetchMounts = 0
        // compose broken down BY ROW TYPE — which bubble is expensive to build?
        val types = cnt.keys.filter { it.startsWith("c/") }.sortedByDescending { sum[it] ?: 0L }
        if (types.isNotEmpty()) {
            val tb = StringBuilder("     compose-by-type:")
            for (k in types) {
                val n = cnt[k]!!; val ent = enterType[k.drop(2)] ?: 0
                tb.append(" %s[composes=%d enters=%d recompose=%d avg=%.1f max=%.1f]".format(
                    k.drop(2), n, ent, (n - ent).coerceAtLeast(0), (sum[k]!! / 1e6) / n, mx[k]!! / 1e6))
            }
            android.util.Log.e("PHASE", tb.toString())
        }
        // sub-part breakdown INSIDE the expensive bubble (what eats the mount ms?).
        val subs = cnt.keys.filter { it.startsWith("sub") }.sortedByDescending { sum[it] ?: 0L }
        if (subs.isNotEmpty()) {
            val sbb = StringBuilder("     sub-parts:")
            for (k in subs) { val n = cnt[k]!!; sbb.append(" %s[n=%d avg=%.2f max=%.2f]".format(k.substringAfter('/'), n, (sum[k]!! / 1e6) / n, mx[k]!! / 1e6)) }
            android.util.Log.e("PHASE", sbb.toString())
        }
        sum.clear(); mx.clear(); cnt.clear(); enters = 0; leaves = 0; scrolled = 0; enterType.clear(); t0 = System.nanoTime()
    }
}

/** Times Measure / Placement / Draw of the element it wraps (the whole row). */
internal fun Modifier.phaseProbe(): Modifier = this
    .layout { measurable, constraints ->
        val t = System.nanoTime()
        val p = measurable.measure(constraints)
        PhaseProbe.rec("measure", System.nanoTime() - t)
        layout(p.width, p.height) {
            val tp = System.nanoTime()
            p.place(0, 0)
            PhaseProbe.rec("place", System.nanoTime() - tp)
        }
    }
    .drawWithContent {
        val t = System.nanoTime()
        drawContent()
        PhaseProbe.rec("draw", System.nanoTime() - t)
    }

/** Times the Composition of the row content it wraps, tagged by row type. */
@Composable
internal fun TimeCompose(tag: String, content: @Composable () -> Unit) {
    if (!WAVE_DIAG) { content(); return }
    val t = System.nanoTime()
    content()
    val ns = System.nanoTime() - t
    PhaseProbe.rec("compose", ns)
    PhaseProbe.rec("c/$tag", ns)
}

/** Decode [path] downsampled so neither side exceeds [maxPx]. A bubble/tile shows a
 *  thumbnail — decoding the full 12 MP image and drawing it into a small box uploads
 *  a huge GPU texture AND churns the heap. Sampling to display size cuts memory +
 *  draw cost by ~50×. */
internal fun decodeSampledBitmap(path: String, maxPx: Int): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    com.alphamessenger.data.MediaVault.decodeFile(path, bounds)
    val ow = bounds.outWidth; val oh = bounds.outHeight
    if (ow <= 0 || oh <= 0) return@runCatching null
    var s = 1
    while (ow / s > maxPx || oh / s > maxPx) s *= 2
    com.alphamessenger.data.MediaVault.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

/** Media-player seek step (single source of truth — used by every ExoPlayer
 *  that shows the default controls, so voice / music / video are consistent).
 *  ExoPlayer's default is 15s forward / 5s back; we make both a symmetric 5s. */
internal const val SEEK_FORWARD_MS = 5_000L
internal const val SEEK_BACK_MS = 5_000L

/** Bottom panel mode — only ONE is active at a time.
 *  READ_ONLY covers blocked / removed-from-group / chat-closed / write-restricted /
 *  muted-by-admin strips: the input bar is hidden and a status strip replaces it. */
private enum class PanelMode { NONE, KEYBOARD, EMOJI, ATTACH, RECORDING, READ_ONLY }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    chatId: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    /** When set, scroll to + highlight this message once the list is loaded
     *  (used by "show in chat" from the shared-media browser). */
    focusMessageId: String? = null,
    /** Opened from the global search: the in-chat search starts with this query. */
    searchQuery: String? = null,
    /** Navigate to another chat by id (used by the music bar's "show in chat"). */
    onOpenChat: (String) -> Unit = {},
    /** Open another chat at a message, lit up (the music bar's "show in chat"). */
    onOpenChatAt: (chatId: String, messageId: String) -> Unit = { c, _ -> onOpenChat(c) },
    /** Forward: open a target chat as a NEW backstack entry so Back returns
     *  to the source chat (not the main list). */
    onForwardOpenChat: (String) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onContactInfo: (String) -> Unit = {},
    /** The chat's scheduled messages, shown as a chat of their own. */
    onOpenScheduled: (String) -> Unit = {},
    /** A channel post's discussion (post id, message to show there or null). */
    onOpenThread: (postId: String, focusId: String?) -> Unit = { _, _ -> },
 /** This screen shows ONLY the chat's scheduled messages (scheduled view). */
    scheduledMode: Boolean = false,
    /** Open (creating if needed) a 1:1 chat with the given npub. */
    onOpenDirectChat: (npub: String) -> Unit = {},
    /** Open the info screen for a peer identified by npub (creates the DM
     *  chat first so ContactInfo has a chat_id to bind to). */
    onOpenMemberInfo: (senderId: String, npub: String) -> Unit = { _, _ -> },
    /** An assistant chat's settings (its ⋮ menu, an error card). */
    onOpenAssistant: (profileId: String) -> Unit = {},
    /** The export screen for this chat. */
    onExport: (chatId: String) -> Unit = {},
) {
    val __csEnter = System.nanoTime()
    val __t0 = remember { System.nanoTime() }
    val __firstBubble = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val state by viewModel.uiState.collectAsState()
    val networkOnline by viewModel.networkOnline.collectAsState()
    if (WAVE_DIAG) RecompProbe.hit("SCREEN") // TEMP: does the whole ChatScreen recompose per publish?
    val colors = AlphaTheme.colorScheme
    // Banned, or deleted by its owner: only that, nothing of the chat.
    state.ban?.let { gone ->
        com.alphamessenger.ui.chat.BanScreen(
            name = state.chatInfo?.name.orEmpty(),
            reason = gone.reason,
            atSec = gone.at,
            isChannel = state.channel != null,
            disbanded = gone.disbanded,
            onBack = onBack,
            onRemove = { viewModel.deleteChatAndExit(onDone = onBack) },
        )
        return
    }
    val chatCtx = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    // GIFs start only when the list stands still (see GifPlayback).
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
            .collect { com.alphamessenger.ui.gif.GifPlayback.scrolling = it }
    }
    DisposableEffect(Unit) { onDispose { com.alphamessenger.ui.gif.GifPlayback.scrolling = false } }
    // A sticker's pack, or a pack link, opens here.
    com.alphamessenger.ui.sticker.StickerPackHost(com.alphamessenger.ui.theme.AlphaTheme.colorScheme, viewModel.repository,
        onSend = { pid, s, link -> viewModel.sendSticker(pid, s, link) })
    // Polls: voters named the way this chat names its members; "New poll".
    val pollHost = com.alphamessenger.ui.chat.poll.LocalPollHost.current
    val youLabel = stringResource(R.string.notif_you)
    androidx.compose.runtime.SideEffect {
        val profiles = state.peerProfiles
        pollHost?.let { h -> h.names(youLabel, profiles) }
    }
    if (com.alphamessenger.ui.chat.poll.PollUi.compose) {
        LaunchedEffect(Unit) { viewModel.setShowAttachPanel(false) }
        com.alphamessenger.ui.chat.poll.PollComposer(
            channel = state.channel != null,
            onDismiss = { com.alphamessenger.ui.chat.poll.PollUi.compose = false },
            onSend = { json -> viewModel.sendPoll(json); com.alphamessenger.ui.chat.poll.PollUi.compose = false },
        )
    }
    com.alphamessenger.ui.theme.ThemeLinkHost()
    if (WAVE_DIAG) LaunchedEffect(listState) { // TEMP: measure scroll distance vs mounts
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> PhaseProbe.scroll(idx) }
    }
    if (WAVE_DIAG) DisposableEffect(Unit) { // TEMP: mounts/frame + on-frame vs prefetch
        val ch = android.view.Choreographer.getInstance()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val endFrame = Runnable { PhaseProbe.frameEnd() } // runs AFTER Compose traversal
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(t: Long) { PhaseProbe.frameStart(); h.post(endFrame); ch.postFrameCallback(this) }
        }
        ch.postFrameCallback(cb)
        onDispose { ch.removeFrameCallback(cb); h.removeCallbacks(endFrame) }
    }
    // In-chat search (top-bar menu → "Search"). null = inactive.
    var chatSearchQuery by remember { mutableStateOf(searchQuery) }
    // The core's matches in this chat (newest first) and which one is shown.
    var searchHits by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchFound by remember { mutableStateOf<List<ChatViewModel.InChatHit>>(emptyList()) }
    var searchListOpen by remember { mutableStateOf(false) }
    var searchPos by remember { mutableStateOf(0) }
    var searchLoaded by remember { mutableStateOf(false) }
    var pickSearchDate by remember { mutableStateOf(false) }
    // Disappearing-messages timer picker (three-dots → "Disappearing messages").
    var showTimerDialog by remember { mutableStateOf(false) }
    // Secret Note composer (attach → "Secret").
    var showSecretNoteDialog by remember { mutableStateOf(false) }
    /** The incoming whisper being read (message id). */
    var whisperOpen by remember { mutableStateOf<String?>(null) }
    // --- The single attachment draft set ---
    // Gallery, camera, files, audio, video and Share all put items HERE and
    // only here. Owner is the chat-scoped ViewModel: the set survives rotation
    // and recreation but never leaks to other screens.
    val draftVm: com.alphamessenger.ui.media.draft.MediaDraftViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val draft = draftVm.store
    // Camera: live frame → shot → the same set. It has no send screen of its
    // own; the review sends.
    var showCameraStudio by remember { mutableStateOf(false) }
    // Camera-selected quality: the shot was already taken with it, so the send
    // must respect it. Gallery stays "Auto".
    // Nothing is sent at pick time: set first, then review. Saveable: after
    // an Activity recreation the set is alive (ViewModel), so the review must
    // come back too instead of leaving an invisible set hanging.
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    // A picture tapped in the attach panel: open the editor on it once it's in the set.
    var openPickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // The gallery the review pages through when it was opened on one of its pictures.
    var previewGallery by remember { mutableStateOf<Pair<List<GalleryItem>, Int>?>(null) }
    LaunchedEffect(previewOpen) { if (!previewOpen) previewGallery = null }
    LaunchedEffect(openPickedUri, draft.size) {
        val uri = openPickedUri ?: return@LaunchedEffect
        val order = draftVm.orderOf(uri)
        if (order > 0) {
            draft.setCurrent(order - 1)
            openPickedUri = null
            previewOpen = true
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCameraStudio = true
        // Permission denied — return the user to where they tapped the camera,
        // otherwise the set stays assembled but invisible.
        else previewOpen = draft.size > 0
    }
    val onCameraTap: () -> Unit = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                chatCtx, android.Manifest.permission.CAMERA,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) showCameraStudio = true
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    // --- Location sharing ---
    // pendingLocation != null → show the confirmation sheet (mini-map + Send).
    var pendingLocation by remember { mutableStateOf<LocationData?>(null) }
    var showLocationOffDialog by remember { mutableStateOf(false) }
    // Map picker (choose any point to send).
    var showLocationPicker by remember { mutableStateOf(false) }
    var pickerStartLat by remember { mutableStateOf(20.0) }
    var pickerStartLng by remember { mutableStateOf(0.0) }
    /**
     * Limit check BEFORE an item enters the set. A rejection means only "this
     * item is not taken" — the attachment panel stays open and the user keeps
     * selecting.
     */
    val acceptDraft: (com.alphamessenger.ui.media.draft.MediaDraftItem) -> Boolean = { item ->
        viewModel.acceptsAttachment(item.uri, item.sendType)
    }
    // Camera and strip pictures join the set in the order they were picked;
    // the review then opens on the last one.
    val ingestStudio: (List<StudioMedia>) -> Unit = { items ->
        val fresh = items.filter { draftVm.orderOf(it.uri) == 0 }
        val open = {
            items.lastOrNull()?.let { draftVm.orderOf(it.uri) }?.takeIf { it > 0 }?.let { draft.setCurrent(it - 1) }
            previewOpen = draft.size > 0
        }
        val gallery = fresh.filter { it.fromGallery }.map { it.uri }
        val shots = fresh.filter { !it.fromGallery }.map { it.uri }
        if (fresh.isEmpty()) open()
        if (gallery.isNotEmpty()) draftVm.ingest(
            gallery, com.alphamessenger.ui.media.draft.MediaDraftItem.Source.GALLERY,
            limit = MAX_MEDIA_SELECTION, accept = acceptDraft,
            onComplete = if (shots.isEmpty()) open else null,
        )
        if (shots.isNotEmpty()) draftVm.ingest(
            shots, com.alphamessenger.ui.media.draft.MediaDraftItem.Source.CAMERA,
            limit = MAX_MEDIA_SELECTION, accept = acceptDraft, onComplete = open,
        )
    }

    // "+media" from the review: adds to the SAME set, does not replace it.
    val addMediaPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(MAX_MEDIA_SELECTION)
    ) { uris ->
        if (uris.isNotEmpty()) {
            draftVm.ingest(
                uris, com.alphamessenger.ui.media.draft.MediaDraftItem.Source.GALLERY,
                limit = MAX_MEDIA_SELECTION, accept = acceptDraft,
                // Show the newly added item immediately — otherwise it is
                // unclear what was added.
                onComplete = { draft.setCurrent(draft.size - 1) },
            )
        }
    }
    // Scheduled view: the media of a waiting message is being replaced —
    // (old id, send time). The new set goes through the normal review screen.
    var replaceHeld by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var replaceChooserMsg by remember { mutableStateOf<MessageUi?>(null) }
    val replaceVisualPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(MAX_MEDIA_SELECTION)
    ) { uris ->
        if (uris.isNotEmpty()) {
            draftVm.ingest(
                uris, com.alphamessenger.ui.media.draft.MediaDraftItem.Source.GALLERY,
                limit = MAX_MEDIA_SELECTION, accept = acceptDraft,
            )
            previewOpen = true
        } else if (replaceHeld != null) { replaceHeld = null; draft.setCaption(null) }
    }
    val audioPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty() && replaceHeld != null) { replaceHeld = null; draft.setCaption(null) }
        if (uris.isNotEmpty()) {
            draftVm.ingest(uris, com.alphamessenger.ui.media.draft.MediaDraftItem.Source.FILE_PICKER,
                limit = MAX_MEDIA_SELECTION, accept = acceptDraft)
            previewOpen = true
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty() && replaceHeld != null) { replaceHeld = null; draft.setCaption(null) }
        if (uris.isNotEmpty()) {
            // asFile: items picked via "File" go as documents, even photos.
            draftVm.ingest(uris, com.alphamessenger.ui.media.draft.MediaDraftItem.Source.FILE_PICKER,
                asFile = true, limit = MAX_MEDIA_SELECTION, accept = acceptDraft)
            previewOpen = true
        }
    }
    // Microphone permission for voice messages — requested on first hold.
    val micPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startRecording() }
    // Video messages: holding the camera in the field records one.
    val roundLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val roundRec = remember { com.alphamessenger.ui.chat.round.RoundRecorder(chatCtx, roundLifecycle) }
    DisposableEffect(roundRec) { onDispose { roundRec.release() } }
    DisposableEffect(chatId) {
        com.alphamessenger.ui.chat.round.RoundPlayer.screenChat = chatId
        onDispose {
            if (com.alphamessenger.ui.chat.round.RoundPlayer.screenChat == chatId) com.alphamessenger.ui.chat.round.RoundPlayer.screenChat = null
        }
    }
    val roundPermissions = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[android.Manifest.permission.CAMERA] != true) {
            android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.round_need_camera), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // [locked]: started from the camera, so it records hands-free with its own send / cancel.
    val startRound: (Boolean) -> Unit = { locked ->
        val camOk = androidx.core.content.ContextCompat.checkSelfPermission(
            chatCtx, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (camOk) {
            roundRec.start { f -> f?.let { viewModel.sendRoundVideo(it.absolutePath) } }
            if (locked) roundRec.locked = true
        } else roundPermissions.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
    }
    // Shared voice player — app-scoped singleton so a voice note keeps playing
    // when you leave the chat (the global voice bar shows it everywhere). Not
    // released on dispose anymore.
    val voiceController = remember { VoicePlayerHolder.get(chatCtx) }
    LaunchedEffect(state.messages) {
        val seq = state.messages.map { m ->
            val voice = m.isMedia && m.mediaType == "audio" && (m.mediaFilename?.startsWith("voice") == true)
            VoiceSeqItem(m.id, voice, m.mediaRefJson, chatId)
        }
        voiceController.configure(seq) { ref -> viewModel.loadMedia(ref) }
    }
    // Shared MUSIC player → app-scoped singleton so playback continues when
    // you switch to another chat/group (only the ✕ close button stops it).
    val musicController = remember { MusicPlayerHolder.get(chatCtx) }
    LaunchedEffect(state.messages) {
        val tracks = state.messages
            .filter { it.isMedia && it.mediaType == "audio" && (it.mediaFilename?.startsWith("voice") != true) }
            .mapNotNull { m -> m.mediaRefJson?.let { MusicTrack(m.id, it, displayFileName(m.mediaFilename)?.substringBeforeLast('.')?.replace('_', ' ')?.trim()?.takeIf { t -> t.isNotEmpty() } ?: chatCtx.getString(R.string.preview_audio), chatId) } }
        musicController.configure(tracks) { ref -> viewModel.loadMedia(ref) }
    }
    // Pause any voice/music playback when a recording starts.
    LaunchedEffect(state.isRecording) {
        if (state.isRecording) { voiceController.pausePlayback(); musicController.pausePlayback() }
    }
    // --- Share from another app ---
    // The user picked the recipients in the chat list. This chat is the first
    // of them: it takes the set EXACTLY ONCE and behaves like a normal send —
    // attachments to the review, text to the input field. The other recipients
    // wait in [shareTargets] and receive the same send result without a second
    // upload.
    var shareTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var shareMode by remember { mutableStateOf(false) }
    // Known on the first frame: this chat takes a Share with files, so the review
    // covers the chat from the start instead of appearing once the files are read.
    var sharePreparing by remember {
        mutableStateOf(
            com.alphamessenger.ui.share.PendingShare.host == chatId &&
                com.alphamessenger.ui.share.PendingShare.payload?.uris?.isNotEmpty() == true,
        )
    }
    val sharePayload = com.alphamessenger.ui.share.PendingShare.payload
    LaunchedEffect(sharePayload, chatId) {
        val delivery = com.alphamessenger.ui.share.PendingShare.takeFor(chatId) ?: return@LaunchedEffect
        shareTargets = delivery.extraTargets
        shareMode = true
        val text = delivery.payload.text?.takeIf { it.isNotBlank() }
        if (delivery.payload.uris.isNotEmpty()) {
            // Text arrived WITH attachments — it is their caption, not a
            // separate message: one send, one recipient set.
            if (text != null) draft.setCaption(text)
            draftVm.ingest(
                delivery.payload.uris.take(MAX_MEDIA_SELECTION),
                com.alphamessenger.ui.media.draft.MediaDraftItem.Source.SHARE,
                limit = MAX_MEDIA_SELECTION,
                accept = acceptDraft,
                // Nothing could be read: a silent "send without a set" is not
                // acceptable — the recipients must be dropped together with it.
                onComplete = {
                    sharePreparing = false
                    if (draft.isEmpty) {
                        previewOpen = false
                        shareMode = false
                        shareTargets = emptyList()
                        com.alphamessenger.ui.share.PendingShare.clear()
                        android.widget.Toast.makeText(
                            chatCtx, chatCtx.getString(R.string.error_read_attachment), android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            previewOpen = true
        } else if (text != null) {
            val current = viewModel.uiState.value.inputText
            viewModel.updateInput(if (current.isBlank()) text else "$current\n$text")
        }
    }

    var showAddMemberSheet by remember { mutableStateOf(false) }
    var avatarPreview by remember { mutableStateOf<MessageUi?>(null) }
    // A post or reply from the channel itself: it has no person to open.
    var channelVoice by remember { mutableStateOf<MessageUi?>(null) }
 // --- Inline gallery picker ---
    // Selection + caption are hoisted here so the caption bar can live in the
    // main window (lifts with the keyboard reliably). Two positions via weights.
    var pickerCaption by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }
    var pickerDiscard by remember { mutableStateOf(false) }
    val pickerCaptionFocus = remember { FocusRequester() }
    LaunchedEffect(state.showAttachPanel) {
        if (!state.showAttachPanel) {
            // Let the close glide finish before wiping the panel content —
            // clearing instantly makes the caption bar vanish mid-slide.
            delay(300)
            // Clear the set only if it did not go to review or send.
            if (!previewOpen) draftVm.discard()
            pickerCaption = androidx.compose.ui.text.input.TextFieldValue()
        }
    }

    /**
     * The single send boundary of the set: gallery, camera, files and Share
     * all converge here. Core albums are photo/video only, so documents go
     * through the existing "File" path instead of inventing their own.
     */
    // The view timer of the set being prepared: null = an ordinary send.
    var glanceSecs by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(draft.size) { if (draft.size == 0) glanceSecs = null }
    val glanceTimer: (@Composable () -> Unit)? =
        if (draft.items.any { it.isImage || it.isVideo }) {
            { com.alphamessenger.ui.chat.whisper.GlanceTimerButton(glanceSecs, { glanceSecs = it }, colors, size = 36.dp) }
        } else null
    val sendDraft: (String?) -> Unit = { caption ->
        // The other Share recipients, taken here in one motion with the set:
        // one send — one recipient list; the same set never goes anywhere twice.
        val alsoTo = shareTargets
        shareTargets = emptyList()
        if (shareMode) { shareMode = false; com.alphamessenger.ui.share.PendingShare.clear() }
        // The set is taken AT ONCE and whole — with its edits but WITHOUT
        // applying them to the files. The send queue applies them after the
        // message is already in the chat: waiting for a transcode here would
        // keep the user in front of an empty screen for its whole duration.
        val items = draftVm.consume()
        val media = items.filter { !it.isDocument }
        val docs = items.filter { it.isDocument }
        val glance = glanceSecs
        glanceSecs = null
        if (media.isNotEmpty()) {
            viewModel.sendDraftMedia(media, caption, alsoTo = alsoTo, glanceSecs = glance)
        }
        docs.forEachIndexed { i, doc ->
            viewModel.sendMediaUri(doc.uri, doc.sendType, if (media.isEmpty() && i == 0) caption else null, alsoTo)
        }
    }

    /**
     * Send the composer text. When Sharing to several chats, the same text
     * goes to the other recipients — via the same core call, no second path.
     */
    val sendComposerText: () -> Unit = {
        val alsoTo = shareTargets
        shareTargets = emptyList()
        if (shareMode) { shareMode = false; com.alphamessenger.ui.share.PendingShare.clear() }
        viewModel.sendMessage(alsoTo)
    }

    /** Gallery cell tap: deselect or add to the set. */
    val onGalleryToggle: (GalleryItem) -> Unit = { item ->
        if (draftVm.orderOf(item.uri) > 0) {
            draftVm.removeByUri(item.uri)
        } else if (draft.size < MAX_MEDIA_SELECTION) {
            draftVm.ingest(
                listOf(item.uri),
                com.alphamessenger.ui.media.draft.MediaDraftItem.Source.GALLERY,
                limit = MAX_MEDIA_SELECTION,
                accept = acceptDraft,
            )
        }
    }
    // Locally-owned keyboard/emoji choice (KEYBOARD vs EMOJI vs NONE). ATTACH,
    // RECORDING and READ_ONLY are driven by the ViewModel truth and folded into
    // `panelMode` below. `inputMode` is the SINGLE writer of local input state;
    // WindowInsets.ime is read-only measurement only.
    // --- Bottom panel: ONE owner of the bottom surface ---
    // The bottom surface is a SINGLE permanent Layout (BottomSurface) that always
    // contains: Divider + InputBar (never destroyed) + BottomPanel (one slot whose
    // content changes with `panelMode`). There is exactly ONE spacer
    // (BottomPanel.height = bottomPanelHeight) and ONE derived height variable.
    //
    // `panelMode` is the single source of truth for which panel is up. It is
    // derived from the ViewModel truth (showAttachPanel / isRecording / read-only
    // flags) PLUS the locally-owned keyboard/emoji choice. It NEVER needs the IME
    // inset to be set — it switches instantly, and the layout follows imeContentDp
    // atomically (no 0-frame, no hold, no latch).
    val attachDensity = androidx.compose.ui.platform.LocalDensity.current
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val imeBottomPx = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(attachDensity)
    val imeDp = with(attachDensity) { imeBottomPx.toDp() }
    // The IME inset INCLUDES the navigation-bar region; the Column already adds
    // navigationBarsPadding, so we use the keyboard height ABOVE the nav bar to
    // avoid a nav-bar-tall gap between the keyboard and the input bar.
    val navBarDp = with(attachDensity) {
        WindowInsets.navigationBars.getBottom(attachDensity).toDp()
    }
    val imeContentDp = (imeDp - navBarDp).coerceAtLeast(0.dp)

    // Locally-owned keyboard/emoji choice (KEYBOARD vs EMOJI vs NONE). ATTACH,
    // RECORDING and READ_ONLY are driven by the ViewModel truth and folded into
    // `panelMode` below. This is the ONLY local input state; WindowInsets.ime is
    // read-only measurement.
    var inputMode by remember { mutableStateOf(PanelMode.NONE) }
    val inputFocusRequester = remember { FocusRequester() }

    // Drag-resizable panel height for EMOJI and ATTACH (shared — both panels snap
 // to the same detents, classic). Seeded to a screen-proportional
    // estimate so the first open (before the keyboard has been shown) is full
    // height, not a tiny sliver.
    var panelHeight by remember(screenHeightDp) { mutableStateOf(screenHeightDp * 0.42f) }
    // True while a finger actively drags the panel grabber: the height then
    // tracks 1:1 (snap), and the spring only smooths the release-to-detent glide.
    var isPanelDragging by remember { mutableStateOf(false) }
    val glideSpec = androidx.compose.animation.core.spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
    )
    // Fast, non-bouncy spring for discrete jumps INSIDE ime-driven modes (e.g.
    // emoji dragged high → keyboard): glides the mismatch instead of snapping.
    val trackSpec = androidx.compose.animation.core.spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
        stiffness = 2500f,
    )
    // THE one animated bottom height (Animatable so the target formula below can
    // read the CURRENT value — needed for ride-vs-hold decisions).
    val bottomPanel = remember { androidx.compose.animation.core.Animatable(0.dp, androidx.compose.ui.unit.Dp.VectorConverter) }
    val bottomPanelHeight = bottomPanel.value

    // READ_ONLY is true whenever the composer must be replaced by a status strip
    // (blocked / removed-from-group / chat-closed / write-restricted / muted).
    val secretWaiting = state.secretOrigin.isNotEmpty() && state.secretWaiting
    val isReadOnly = secretWaiting || state.isRequest || state.discussion?.locked == true || state.peerBlocked || state.removedFromGroup ||
        state.chatClosedUntilMe > com.alphamessenger.util.TrustedTime.nowSeconds() ||
        state.writeRestricted ||
        (state.channel != null && !state.isAdmin) ||
        state.mutedUntilMe > com.alphamessenger.util.TrustedTime.nowSeconds()

    // THE single panel mode: ONE derived value that drives actuation + the bottom
    // height. ViewModel truth wins over the local keyboard/emoji choice; READ_ONLY
    // wins over everything (no input allowed).
    val panelMode = when {
        isReadOnly -> PanelMode.READ_ONLY
        state.showAttachPanel -> PanelMode.ATTACH
        state.isRecordingLocked -> PanelMode.RECORDING
        else -> inputMode
    }

    // --- V2 bottom-surface state ---
    // Independent of the legacy declarations above. Created ONCE per
    // ChatScreen; lives until the screen leaves composition. Owns its own
    // FocusRequester / ScrollState / TextFieldValue / panelHeight / inputMode
    // / single Animatable bottom height — none of the legacy compensators
    // (measuredKbHeight, imeUpThisSession, imeCollapsing, kbRiseFloor) are
    // consulted. IME motion is ridden purely from the live inset.
    val v2State = rememberBottomSurfaceState()
    v2State.bindImeInset()
    // Fold the ViewModel truth (attach open / locked recording) into the ONE
    // derived panelMode. Guarded writes — idempotent, cheap.
    v2State.syncExternal(
        attach = state.showAttachPanel,
        recording = state.isRecordingLocked,
    )
    val v2PanelMode = v2State.panelMode
    // NOTE: no animateBottomPanel — the ONE bottomPanelHeight is driven by
    // state.bottomPanelHeight() (animateDpAsState) inside BottomPanelV2.
    // Read-only is handled INSIDE the InputBar (enabled=false + readOnlyContent),
    // NOT as a separate panel mode.
    // --- end V2 bottom-surface state ---

    // Last settled keyboard height — grows instantly with the live IME, then a
    // 200ms debounce below corrects it DOWN to the true settled value (spring
    // overshoot otherwise records a too-high peak → gap). Seeded to 0 on purpose:
    // the FIRST-ever open rides up with the IME; every later open pre-reserves
    // this floor so InputBar stands still while the keyboard slides in under it.
    var measuredKbHeight by remember(screenHeightDp) { mutableStateOf(0.dp) }

    // Per-session latch: false at the START of every KEYBOARD session (reset by
    // LaunchedEffect(inputMode)), true once the IME has actually risen > 120dp.
    // Guards the NONE-dismiss condition so it cannot fire on the very first frame
    // (when imeBottomPx is still 0 and the IME hasn't moved yet).
    var imeUpThisSession by remember { mutableStateOf(false) }
    // IME direction latch: false while rising (hold the measured floor so the
    // InputBar never dips mid-rise), true while descending (follow the live inset
    // so the bar rides the keyboard down instead of snapping to 0 at the end).
    var imeCollapsing by remember { mutableStateOf(false) }
    // Whether the keyboard was up at the moment locked recording started —
    // captured synchronously in onTapLockRecord. Only then does RECORDING keep
    // the IME alive and hold its space; otherwise nothing moves and the
    // keyboard is NOT summoned.
    var recordingKeepsKb by remember { mutableStateOf(false) }
    var prevImeDp by remember { mutableStateOf(0.dp) }
    LaunchedEffect(imeContentDp) {
        if (imeContentDp > prevImeDp) imeCollapsing = false
        else if (imeContentDp < prevImeDp) imeCollapsing = true
        prevImeDp = imeContentDp
    }
    // Fresh-session latch reset. NOTE: this effect runs AFTER the frame that
    // switched the mode — the click handlers below ALSO reset both latches
    // synchronously, otherwise a stale imeCollapsing=true (left over from the
    // IME descent that opened the emoji panel) makes the very first KEYBOARD
    // frame target ime(=0) → one-frame collapse: InputBar hits the floor and
    // a bubble flashes at the list top. Kept as a safety net only.
    LaunchedEffect(inputMode) {
        if (inputMode == PanelMode.KEYBOARD) {
            imeUpThisSession = false  // fresh session
            imeCollapsing = false
        }
    }
    // A reply (swipe or menu) goes straight to typing: the quote lands in the
    // field and the keyboard comes up — through the live bottom surface (V2),
    // the same path a tap on the field takes. (The legacy inputMode drives no
    // field any more: switching it showed a keyboard with nothing focused.)
    val replyAndType: (MessageUi) -> Unit = { m ->
        viewModel.setReplyTo(m)
        if (v2State.panelMode != PanelModeV2.KEYBOARD) v2State.enterKeyboard()
        else v2State.focusInput()
    }
    // A reply to a chosen fragment: the quote is already set, only the keyboard comes up.
    val replyAndTypeKeepQuote: (MessageUi, String) -> Unit = { _, _ ->
        if (v2State.panelMode != PanelModeV2.KEYBOARD) v2State.enterKeyboard()
        else v2State.focusInput()
    }
    LaunchedEffect(imeContentDp, panelMode, state.isRecording) {
        // Track upward immediately so the floor never lags behind a rising keyboard.
        if (imeContentDp > measuredKbHeight) measuredKbHeight = imeContentDp
        if (imeContentDp > 120.dp) imeUpThisSession = true
        // Suppress the IME-collapse watchdog while recording: a mic press can
        // cause a transient imeContentDp==0 (OS re-measures the window during
        // the touch), which would otherwise set inputMode=NONE → hide()+clearFocus()
        // → keyboard + panel collapse under the user's finger. The watchdog
        // resumes when isRecording returns to false.
        if (panelMode == PanelMode.KEYBOARD && !state.isRecording
            && imeBottomPx == 0 && imeContentDp == 0.dp && imeUpThisSession) {
            inputMode = PanelMode.NONE
            imeUpThisSession = false
        }
    }
    // Debounce: correct measuredKbHeight DOWNWARD after the keyboard fully settles.
    // Fixes the 1-in-10 gap caused by spring-animation overshoot recording a peak
    // value higher than the true settled height.
    LaunchedEffect(imeContentDp) {
        if (imeContentDp > 120.dp) {
            delay(200)
            if (imeContentDp > 120.dp) measuredKbHeight = imeContentDp
        }
    }

 // ATTACH drag detents: compact / half / tall. Drag is
    // continuous; release snaps to the nearest detent (or closes below the exit
    // threshold). When the caption keyboard is up the VISIBLE part of the panel
    // is clamped so panel + IME never overflow the column (caption stays on
    // screen); the IME share is added back below.
    val attachVisibleTarget = panelHeight.coerceAtMost(screenHeightDp - imeContentDp - 120.dp)

    // THE single bottom height target for the ONE BottomPanel slot.
    // KEYBOARD rise RIDES the live IME up from below (guide: InputBar rises
    // together with the keyboard). `minOf(current, floor)` makes it hold when a
    // panel already stands at/above keyboard height (emoji→keyboard swap) and
    // ride once the IME catches up; descent (imeCollapsing) follows the inset
    // down. RECORDING holds the keyboard space ONLY when the keyboard was up at
    // lock time (recordingKeepsKb).
    val bottomPanelTarget = when (panelMode) {
        PanelMode.KEYBOARD  -> if (imeCollapsing) imeContentDp
            else maxOf(imeContentDp, minOf(bottomPanelHeight, measuredKbHeight))
        PanelMode.EMOJI     -> maxOf(panelHeight, imeContentDp)
        PanelMode.ATTACH    -> attachVisibleTarget + imeContentDp
        PanelMode.RECORDING -> if (recordingKeepsKb) {
            if (imeCollapsing) imeContentDp else maxOf(imeContentDp, measuredKbHeight)
        } else 0.dp
        PanelMode.READ_ONLY -> 0.dp
        PanelMode.NONE      -> 0.dp
    }
    val imeDriven = panelMode == PanelMode.KEYBOARD ||
        panelMode == PanelMode.RECORDING || panelMode == PanelMode.READ_ONLY ||
        (panelMode == PanelMode.ATTACH && imeContentDp > 0.dp)
    // Actuation: finger drag → 1:1 snap. Ime-driven CONTINUOUS motion (target
    // moved < 32dp since the last frame and nothing is mid-glide) → snap-ride
    // the inset. Everything else (panel open/close, drag-release detents,
    // discrete jumps inside ime modes) → spring glide. NEVER flip a running
    // glide to snap — that flip was a visible jolt.
    var prevBottomTarget by remember { mutableStateOf(0.dp) }
    LaunchedEffect(bottomPanelTarget, isPanelDragging) {
        val jump = kotlin.math.abs((bottomPanelTarget - prevBottomTarget).value)
        prevBottomTarget = bottomPanelTarget
        when {
            isPanelDragging -> bottomPanel.snapTo(bottomPanelTarget)
            imeDriven && jump < 32f && !bottomPanel.isRunning -> bottomPanel.snapTo(bottomPanelTarget)
            imeDriven -> bottomPanel.animateTo(bottomPanelTarget, trackSpec)
            else -> bottomPanel.animateTo(bottomPanelTarget, glideSpec)
        }
    }

    // While a panel glides shut (mode already NONE, height still > 0) keep
    // rendering the LAST panel content so the surface visibly slides down
    // instead of vanishing into an empty shrinking box.
    var lastVisiblePanel by remember { mutableStateOf(PanelMode.NONE) }
    if (panelMode == PanelMode.EMOJI || panelMode == PanelMode.ATTACH) lastVisiblePanel = panelMode
    val displayMode =
        if (panelMode == PanelMode.NONE && bottomPanelHeight > 4.dp) lastVisiblePanel else panelMode
    // KBTRACE (temporary): track the single driver of the bottom height.
    androidx.compose.runtime.LaunchedEffect(bottomPanelHeight, imeContentDp, imeBottomPx, panelMode) {
    }
    // --- Message actions (Batch A) ---
    // Single-tap → action sheet (reactions + actions). Long-press →
    // selection mode (multi-select + bulk forward/delete).
    var actionSheetMsg by remember { mutableStateOf<MessageUi?>(null) }
    // Where the message with the menu open is right now. The bounds maps below are
    // not observed: a new message moving the list would leave the menu behind.
    var menuSpot by remember { mutableStateOf<Pair<String, BubbleSpot>?>(null) }
    var menuRow by remember { mutableStateOf<Pair<String, androidx.compose.ui.geometry.Rect>?>(null) }
    // The menu opens straight on the full emoji set ("+" of the selection's reactions).
    var actionSheetPicker by remember { mutableStateOf(false) }
    val selectHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(actionSheetMsg == null) { if (actionSheetMsg == null) actionSheetPicker = false }
    // Channel: leaving asks first.
    var confirmLeaveChannel by remember { mutableStateOf(false) }
    if (confirmLeaveChannel) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLeaveChannel = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.channel_leave_title) + "?", color = colors.textPrimary) },
            text = { Text(stringResource(R.string.channel_leave_confirm_text), color = colors.textSecondary, fontSize = 14.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmLeaveChannel = false; viewModel.leaveChannelAndExit { onBack() } }) {
                    Text(stringResource(R.string.leave_action), color = colors.danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmLeaveChannel = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                }
            },
        )
    }
    val chatCtxForSecure = androidx.compose.ui.platform.LocalContext.current
    // A channel subscriber only reads: no reply, edit or delete of posts.
    val readerOnly = state.channel != null && !state.isAdmin
    // A protected channel: subscribers can't screenshot or record it.
    // Forwarding, copying and screenshots forbidden by the owner (a channel or a group).
    val protectedChannel = (state.channel?.protect == true || state.groupProtect) && !state.isAdmin ||
        state.secretOrigin.isNotEmpty()
    com.alphamessenger.ui.components.SecureWindow(protectedChannel)
    var reactionsMsg by remember { mutableStateOf<MessageUi?>(null) }
    // --- Scheduled messages: ordinary messages, published at their time ---
    // Any send can be scheduled: the action is kept until a time is picked,
    // then it runs with the schedule armed (text, media, album, voice, music,
    // files, location — all through their normal paths).
    val heldAll by viewModel.repository.held.collectAsState()
    val heldHere = heldAll[chatId].orEmpty()
    var pendingScheduled by remember { mutableStateOf<(() -> Unit)?>(null) }
    var rescheduleId by remember { mutableStateOf<String?>(null) }
    // Pinned bar: which pin it shows, and a request to scroll to a message.
    var pinnedIndex by remember { mutableStateOf(0) }
    var jumpRequest by remember { mutableStateOf<String?>(null) }
    var lastPinJump by remember { mutableStateOf<String?>(null) }
    var scheduledActionsMsg by remember { mutableStateOf<MessageUi?>(null) }
    val scheduleCtx = androidx.compose.ui.platform.LocalContext.current
    // A short soft tick whenever a reaction is put or taken off.
    val react: (String, String) -> Unit = { id, emoji ->
        com.alphamessenger.util.Haptics.tick(scheduleCtx)
        viewModel.sendReaction(id, emoji)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.repository.refreshHeld() }
    /** In the scheduled view every send asks for a time first. */
    val schedulable: (() -> Unit) -> Unit = { action -> if (scheduledMode) pendingScheduled = action else action() }
    pendingScheduled?.let { action ->
        ScheduleSendSheet(
            colors = AlphaTheme.colorScheme,
            onPick = { at ->
                pendingScheduled = null
                viewModel.armSchedule(at)
                action()
                android.widget.Toast.makeText(scheduleCtx, scheduleCtx.getString(R.string.schedule_done), android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { pendingScheduled = null },
        )
    }
    rescheduleId?.let { id ->
        ScheduleSendSheet(
            colors = AlphaTheme.colorScheme,
            onPick = { at -> rescheduleId = null; viewModel.rescheduleHeld(id, at) },
            onDismiss = { rescheduleId = null },
        )
    }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingMsg by remember { mutableStateOf<MessageUi?>(null) }
    // Tap on a link/email/phone in text → link-action bottom sheet.
    var pendingLinkAction by remember { mutableStateOf<LinkAction?>(null) }
    // Partial text-selection mode for a specific message (from the action sheet).
    var textSelectMsgId by remember { mutableStateOf<String?>(null) }
    val chatCtxText = androidx.compose.ui.platform.LocalContext.current
    // "Select text": the message text in its own window — select any part, copy.
    var selectTextOf by remember { mutableStateOf<String?>(null) }
    var selectTextMsg by remember { mutableStateOf<MessageUi?>(null) }
    selectTextOf?.let { full ->
        SelectTextSheet(
            text = full,
            canReply = selectTextMsg != null && !readerOnly,
            colors = colors,
            onReply = { part ->
                val msg = selectTextMsg
                selectTextOf = null; selectTextMsg = null
                if (msg != null) { viewModel.setReplyTo(msg, part); replyAndTypeKeepQuote(msg, part) }
            },
            onCopy = { piece ->
                val cm = chatCtxText.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", piece))
                android.widget.Toast.makeText(chatCtxText, chatCtxText.getString(R.string.chat_copied), android.widget.Toast.LENGTH_SHORT).show()
                selectTextOf = null; selectTextMsg = null
            },
            onDismiss = { selectTextOf = null; selectTextMsg = null },
        )
    }
    // Tell the ViewModel when we're editing so it suppresses the "typing"
    // indicator (the edit reuses the normal input field).
    LaunchedEffect(editingMsg) { viewModel.setEditing(editingMsg != null) }
    var forwardIds by remember { mutableStateOf<List<String>?>(null) }
    // Pending delete — non-null shows the "for me / for everyone" dialog.
    var deleteDialogIds by remember { mutableStateOf<List<String>?>(null) }
    // Message briefly highlighted after tapping a reply quote (scroll-to).
    var highlightedId by remember { mutableStateOf<String?>(null) }
    // Non-null → fullscreen photo viewer open for this decrypted file path.
    var fullscreenMediaPath by remember { mutableStateOf<String?>(null) }
    /** The message whose media was opened: it defines the position in the
     *  gallery strip. */
    var fullscreenMediaMessageId by remember { mutableStateOf<String?>(null) }
    // "Show in chat" from the viewer menu: the message id to scroll the list
    // to (handled where the laid-out list is available).
    var mediaScrollToId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            kotlinx.coroutines.delay(2500)
            highlightedId = null
        }
    }
    LaunchedEffect(chatSearchQuery) {
        val q = chatSearchQuery?.trim().orEmpty()
        if (q.isEmpty()) { searchHits = emptyList(); searchPos = 0; searchLoaded = false; return@LaunchedEffect }
        searchLoaded = false
        kotlinx.coroutines.delay(250)
        val found = viewModel.searchHere(q)
        searchFound = found
        val hits = found.map { it.id }
        searchHits = hits
        searchLoaded = true
        val opened = focusMessageId?.substringBefore("#al")?.let { hits.indexOf(it) } ?: -1
        searchPos = if (opened >= 0) opened else 0
        // Opened at a found message: that message is the place; the search only counts.
        if (opened < 0 && (focusMessageId == null || q != searchQuery?.trim())) hits.firstOrNull()?.let { jumpRequest = it }
    }
    androidx.activity.compose.BackHandler(enabled = chatSearchQuery != null) { chatSearchQuery = null }
    if (searchListOpen) {
        ChatSearchList(
            hits = searchFound,
            query = chatSearchQuery.orEmpty(),
            current = searchHits.getOrNull(searchPos),
            colors = colors,
            onPick = { i -> searchListOpen = false; searchPos = i; jumpRequest = searchHits[i] },
            onDismiss = { searchListOpen = false },
        )
    }
    if (pickSearchDate) {
        val dateCtx = androidx.compose.ui.platform.LocalContext.current
        val dateScope = rememberCoroutineScope()
        // The picker speaks UTC midnights; days after today hold nothing yet.
        val todayUtc = java.time.LocalDate.now().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = todayUtc,
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
                override fun isSelectableYear(year: Int) = year <= java.time.LocalDate.now().year
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickSearchDate = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pickSearchDate = false
                    val millis = dateState.selectedDateMillis ?: return@TextButton
                    val day = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    dateScope.launch {
                        val id = viewModel.messageAtDate(day)
                        if (id != null) jumpRequest = id
                        else android.widget.Toast.makeText(dateCtx, dateCtx.getString(R.string.chat_search_no_date), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.chat_search_go_date), color = colors.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pickSearchDate = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        ) { androidx.compose.material3.DatePicker(state = dateState) }
    }
    // Leave selection mode helper.
    fun exitSelection() { selectionMode = false; selectedIds = emptySet() }
    // Back exits selection mode first (before leaving the chat).
    androidx.activity.compose.BackHandler(enabled = selectionMode) { exitSelection() }
    // Back exits the per-message text-selection mode (partial selection).
    androidx.activity.compose.BackHandler(enabled = textSelectMsgId != null) { textSelectMsgId = null }
    // Prefill the input when entering edit mode. For media we edit the
    // caption (stored in mediaCaption), for text the body.
    LaunchedEffect(editingMsg?.id) {
        editingMsg?.let { viewModel.updateInput(if (it.isMedia) it.mediaCaption ?: "" else it.text) }
    }
    LaunchedEffect(state.addMemberOk) {
        if (state.addMemberOk) {
            snackbarHostState.showSnackbar(chatCtx.getString(R.string.chat_invite_sent))
            viewModel.clearAddMemberOk()
            showAddMemberSheet = false
        }
    }

    LaunchedEffect(chatId) {
        viewModel.loadMessages(chatId)
        // Hook the realtime callback flows so incoming messages refresh the
        // list automatically — without this the user has to leave and re-enter
        // the chat to see anything the relay delivered.
        viewModel.startObservingEvents()
        // Pre-load forward targets so the forward picker opens instantly.
        viewModel.loadForwardTargets()
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
 // Pre-send attachment rejection → bottom sheet, reusing the
    // existing limits. Auto-dismisses when a valid file is picked next.
    state.attachmentRejection?.let { rej ->
        AttachmentRejectionSheet(
            rej = rej,
            colors = colors,
            onPickAnother = { viewModel.dismissAttachmentRejection(); viewModel.setShowAttachPanel(true) },
            onDismiss = { viewModel.dismissAttachmentRejection() },
        )
    }
    // Mark the chat inactive when we leave composition (e.g. opening the info
    // screen) OR when the app is backgrounded, so incoming messages stop being
    // auto-marked-read while we're away. Binding to the real Android lifecycle
    // (not just Compose composition) is what stops the "read" ticks from being
    // sent the instant you switch to another app while the chat is still open —
    // the ChatScreen stays composed in that case, so composition-only tracking
    // wrongly kept the chat "active".
    // The newest message on screen (reverse layout: the lowest index shown). Read goes
    // up to it, so what arrives while they read history waits until it is seen.
    // With the newest stored message too: a row may be on screen before its message is stored
    // (an answer being written keeps its key), and is read once it is.
    val newestStored = androidx.compose.runtime.rememberUpdatedState(state.messages.lastOrNull()?.id)
    LaunchedEffect(listState, chatId) {
        androidx.compose.runtime.snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { (it.key as? String)?.takeIf { k -> k != TYPING_ROW_KEY } } to newestStored.value
        }.collect { (key, _) -> if (key != null) viewModel.onSeen(key) }
    }
    val readLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val notifCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    DisposableEffect(readLifecycleOwner, chatId) {
        // This observer's own mark: when it is replaced, the new one is in before the
        // old one leaves, so the old one may only take back its own.
        val readToken = Any()
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    viewModel.setScreenActive(readToken, true)
                    // Viewing this chat: suppress its notifications and clear any
 // already-posted card.
                    com.alphamessenger.service.MessageNotifier.activeChatId = chatId
                    runCatching {
                        com.alphamessenger.service.MessageNotifier(notifCtx).cancelChat(chatId)
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    viewModel.setScreenActive(readToken, false)
                    if (!viewModel.isScreenActive && com.alphamessenger.service.MessageNotifier.activeChatId == chatId) {
                        com.alphamessenger.service.MessageNotifier.activeChatId = null
                    }
                }
                else -> {}
            }
        }
        readLifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            readLifecycleOwner.lifecycle.removeObserver(obs)
            viewModel.setScreenActive(readToken, false)
            if (!viewModel.isScreenActive && com.alphamessenger.service.MessageNotifier.activeChatId == chatId) {
                com.alphamessenger.service.MessageNotifier.activeChatId = null
            }
        }
    }
    // --- Scroll-to-bottom FAB + auto-scroll rules ---
    //
    // Whether the last message is currently fully in view. We use
    // `derivedStateOf` so this is only recomputed when the list scrolls;
    // touching it in a side-effect doesn't cause extra recompositions.
    // With reverseLayout=true the newest message lives at index 0 and IS the
    // bottom of the chat. "At bottom" therefore means the first (index-0) item
    // is the one anchored to the viewport bottom.
    // Following the bottom is ended only by intent — the user's drag, or a
    // programmatic jump up the history — never by layout (inserts, padding).
    var followBottom by remember(chatId) { mutableStateOf(true) }
    val listDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(listState, chatId) {
        androidx.compose.runtime.snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.isScrollInProgress, listDragged)
        }.collect { (index, scrolling, dragged) ->
            when {
                index == 0 -> followBottom = true
                dragged -> followBottom = false
                scrolling && index > 1 -> followBottom = false
            }
        }
    }
    val isAtBottom = followBottom
    // The newest message is in view but its end is not (a long answer held at its start).
    val endSlackPx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
    val newestEndHidden by remember(listState, endSlackPx) {
        // From the laid-out rows: a held answer re-requests its position every frame, so the
        // requested index is the row above it, never what is on screen.
        androidx.compose.runtime.derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.index == 0 && it.offset < -endSlackPx } == true
        }
    }
    // Assistant: the answer's start is held in view (see the list below).
    var answerFollow by remember(chatId) { mutableStateOf(false) }
    val answerPin = remember(chatId) { com.alphamessenger.assistant.AnswerPin() }
    // Counter of messages that arrived while the user was scrolled up. Resets
    // when the user reaches the bottom again (either via the FAB or scroll).
    var unreadWhileScrolled by remember(chatId) { mutableStateOf(0) }
    // ID of the newest message we've already processed — changes on every new
    // message regardless of whether the list is capped at a fixed size.
    // Saved with the list position: back from the info screen is not a "first fill".
    var prevNewestId by androidx.compose.runtime.saveable.rememberSaveable(chatId) { mutableStateOf<String?>(null) }
    // The key is the newest message's ID, not `messages.size`: the list is
    // capped at a fixed count (always 99), so a size-keyed effect would never
    // fire for new messages. The newest message's ID fires on every actual
    // new message regardless of the list cap.
    //
    // With reverseLayout=true, when message M is prepended as IDX=0, the old
    // IDX=0 becomes IDX=1: by the time this LaunchedEffect runs
    // (post-recomposition), firstVisibleItemIndex is already 1. Checking
    // <= 1 detects "user was at the bottom before the prepend".
    // state.messages is ordered oldest→newest; lastOrNull() = newest message.
    // rendered = state.messages.asReversed() so rendered[0] = newest at bottom.
    // Comments are not in a channel's feed (the core keeps them in their post's
    // discussion), so the newest message here is always the feed's own.
    val feedNewest = state.messages.lastOrNull()
    LaunchedEffect(feedNewest?.id) {
        val newest = feedNewest ?: return@LaunchedEffect
        val isInitialFill = prevNewestId == null
        val isSameMessage = prevNewestId == newest.id
        prevNewestId = newest.id
        // On first fill, reverseLayout USUALLY renders the newest at the bottom —
        // but variable-height group bubbles (sender headers/avatars that resolve
        // a frame later) can leave the list parked mid-conversation. Snap to the
        // newest once, unless we're deep-linking to a specific message.
        if (isInitialFill) {
            if (focusMessageId == null || viewModel.focusShown == focusMessageId) ScrollCoordinator.execute(listState, ScrollIntent.PinToBottom)
            return@LaunchedEffect
        }
        // Skip spurious re-triggers (same newest message).
        if (isSameMessage) return@LaunchedEffect
        // The assistant's rows land under an answer whose start is held: they must not pull the list down.
        if (!newest.isOutgoing && newest.id == answerPin.releasedFor) return@LaunchedEffect
        if (answerFollow && !newest.isOutgoing) {
            if (answerPin.key == null) listState.requestScrollToItem(0)
            return@LaunchedEffect
        }

        val newestIsOwn = newest.isOutgoing
        val atBottom = followBottom
        // At the bottom the list slides up as the message comes in; from far up, straight there.
        if (atBottom) ScrollCoordinator.execute(listState, ScrollIntent.Arrive)
        else if (newestIsOwn) ScrollCoordinator.execute(listState, ScrollIntent.PinToBottom)
        else unreadWhileScrolled++
    }
    // "To the start": the jump waits until the whole history is in the list.
    var topJumpAt by remember(chatId) { mutableStateOf(-1) }
    val topJumpScope = rememberCoroutineScope()
    LaunchedEffect(topJumpAt, state.messages.size) {
        if (topJumpAt < 0 || state.messages.size < topJumpAt) return@LaunchedEffect
        // An instant jump is no scroll in progress, so the intent is ended here.
        followBottom = false
        // Outside this effect: clearing [topJumpAt] restarts it and would cancel the jump.
        topJumpScope.launch { ScrollCoordinator.execute(listState, ScrollIntent.ToTop) }
        topJumpAt = -1
    }
    // "Typing… / recording…" is the list's bottom row, so it scrolls with the history.
    // It is kept while it folds away, and a reader at the bottom is kept there so the
    // row shows as it unfolds.
    var typingShown by remember(chatId) { mutableStateOf<PeerActivity?>(null) }
    val typingFold = remember(chatId) { androidx.compose.animation.core.Animatable(0f) }
    // The newest message while they were typing: a newer incoming one is what they typed.
    var typingSince by remember(chatId) { mutableStateOf<String?>(null) }
    var typingFullPx by remember(chatId) { androidx.compose.runtime.mutableIntStateOf(0) }
    // Their message takes the row's place, in the same frame (decided here, in
    // composition), and grows from the row's height to its own: the history above
    // only rises, it never first drops with a folding row.
    val typingReplaced = typingShown != null && state.peerActivity == null &&
        feedNewest != null && !feedNewest.isOutgoing && feedNewest.id != typingSince
    var growId by remember(chatId) { mutableStateOf<String?>(null) }
    var growFromPx by remember(chatId) { androidx.compose.runtime.mutableIntStateOf(0) }
    val grow = remember(chatId) { androidx.compose.animation.core.Animatable(1f) }
    val growScope = rememberCoroutineScope()
    LaunchedEffect(typingReplaced) {
        if (!typingReplaced) return@LaunchedEffect
        growFromPx = kotlin.math.round(typingFullPx * typingFold.value).toInt()
        growId = feedNewest?.id
        grow.snapTo(0f)
        typingFold.snapTo(0f)
        // Outside this effect: clearing the row restarts it and would cut the growth short.
        growScope.launch {
            grow.animateTo(1f, androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            growId = null
        }
        typingShown = null
    }
    LaunchedEffect(state.peerActivity) {
        val now = state.peerActivity
        if (now != null) {
            val entering = typingShown == null
            typingShown = now
            typingSince = feedNewest?.id
            if (entering && followBottom) {
                // The list keeps its anchor message, so the new row lands below the screen
                // until the list is put back on index 0 — once the row is laid out.
                kotlinx.coroutines.withTimeoutOrNull(500) {
                    androidx.compose.runtime.snapshotFlow {
                        listState.firstVisibleItemIndex >= 1 ||
                            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == TYPING_ROW_KEY
                    }.first { it }
                }
                runCatching { listState.scrollToItem(0) }
            }
            typingFold.animateTo(1f, androidx.compose.animation.core.tween(200))
        } else if (typingShown != null) {
            // Their message is usually a moment behind the end of "typing": wait for it,
            // and fold only when nothing came (it then takes the row's place instead).
            kotlinx.coroutines.delay(TYPING_REPLACE_WAIT_MS)
            if (typingShown == null) return@LaunchedEffect
            typingFold.animateTo(0f, androidx.compose.animation.core.tween(160))
            typingShown = null
        }
    }
    // List positions of messages sit one further while the typing row is in the list.
    val typingRow = typingShown != null && !typingReplaced
    val typingLead = if (typingRow) 1 else 0
    // The message growing in the row's place (see above), read at layout time only.
    val growInPlace: (String) -> Modifier = { id ->
        Modifier.clipToBounds().layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            val growing = growId == id
            val from = if (growing) growFromPx else kotlin.math.round(typingFullPx * typingFold.value).toInt()
            val f = if (growing) grow.value else 0f
            val h = kotlin.math.round(from + (p.height - from) * f).toInt().coerceIn(0, p.height)
            layout(p.width, h) { p.place(0, h - p.height) }
        }
    }
    // The newest bubble comes out of the input panel's top edge and is never drawn
    // under its glass: whatever of it still sits below that edge is cut off. Not with the
    // floating input: every bubble passes under that pill and into the haze alike.
    val panelGapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    val newestClip = Modifier.drawWithContent {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == typingLead }
        val hidden = if (item == null || ChatPanels.floating) 0 else (-item.offset - panelGapPx).coerceAtLeast(0)
        if (hidden == 0) drawContent()
        else clipRect(bottom = size.height - hidden) { this@drawWithContent.drawContent() }
    }
    // The moment the list is scrolled to the bottom, badge clears.
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) unreadWhileScrolled = 0
    }
    val scope = rememberCoroutineScope()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // THE single actuation effect: panelMode is the only trigger, and this is the
    // only place that shows/hides the IME, moves focus, and decides which panel is
    // up. WindowInsets.ime is never written from here — no loop. No hold, no
    // latch: InputBar is never destroyed, so the IME connection survives swaps and
    // there is no 0-frame to bridge.
    LaunchedEffect(panelMode) {
        when (panelMode) {
            PanelMode.KEYBOARD -> {
                val __rf = runCatching { inputFocusRequester.requestFocus() }
                keyboardController?.show()
            }
            PanelMode.EMOJI, PanelMode.ATTACH, PanelMode.NONE, PanelMode.READ_ONLY -> {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
            }
            PanelMode.RECORDING -> {
                // Keep the field focused so the reserved keyboard space stays up —
                // but ONLY if the keyboard was up when the lock started. A focus
                // grab without that summons the IME out of nowhere.
                if (recordingKeepsKb) {
                    val __rf = runCatching { inputFocusRequester.requestFocus() }
                }
            }
        }
    }

    // Location fetch + permission (declared after `scope`, which it uses).
    val fetchLocation: () -> Unit = {
        // If the OS location toggle is off, there's nothing to fetch → offer to
        // open settings (professional, instead of a hidden error toast).
        val lm = chatCtx.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager
        val locEnabled = lm != null &&
            androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
        if (!locEnabled) {
            showLocationOffDialog = true
        } else {
            // The picker opens at once and finds the current position itself.
            runCatching { lastKnownFix(chatCtx) }.getOrNull()?.let { pickerStartLat = it.latitude; pickerStartLng = it.longitude }
            showLocationPicker = true
        }
    }
    val locationPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.any { it }) fetchLocation() }
    // Back from the location settings: switched on, the tap that sent us there goes on.
    val locationSettings = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        val lm = chatCtx.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (lm != null && androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)) fetchLocation()
    }
    val onLocationTap: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            chatCtx, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                chatCtx, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) fetchLocation()
        else locationPermission.launch(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }
    // Opening the in-app camera must dismiss the keyboard, otherwise it stays
    // floating over the live preview.
    LaunchedEffect(showCameraStudio) {
        if (showCameraStudio) { keyboardController?.hide(); focusManager.clearFocus(force = true) }
    }

    // Title priority for 1:1 direct chat: peer-broadcast display_name >
    // chat's local name (e.g. "npub…ab12") > generic placeholder.
    // For groups: chat's name > placeholder.
    val isSavedChat = chatId == "saved_messages_local"
    val isAssistant = com.alphamessenger.assistant.isAssistantChat(chatId)
    val assistantProfile = if (isAssistant) {
        val all by com.alphamessenger.assistant.AssistantStore.profiles.collectAsState()
        all.firstOrNull { it.chatId == chatId }
    } else null
    val liveRaw = if (isAssistant) viewModel.assistant.live.collectAsState().value[chatId] else null
    // An answer still being written (a written one only waits for its rows).
    val assistantLive = liveRaw?.takeIf { !it.done }
    // What the list shows: the answer, kept once written until its stored rows are in the list.
    val assistantShown = com.alphamessenger.assistant.shownLive(liveRaw, state.messages)
    val answerReveal = remember(chatId) { com.alphamessenger.assistant.AnswerReveal() }
    if (isAssistant) {
        // A new message of the user may want an answer; a stored answer must show.
        LaunchedEffect(state.messages.lastOrNull()?.id) { viewModel.assistant.onChatChanged(chatId) }
        LaunchedEffect(chatId) { viewModel.assistant.stored.collect { if (it == chatId) viewModel.refreshAfterAssistant() } }
    }
    // Saved Messages: the tag shown (null = everything); a tag needs the whole history.
    var savedTag by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedTag) { if (savedTag != null) viewModel.revealStart { } }
    val isSecret = state.secretOrigin.isNotEmpty()
    val torState by com.alphamessenger.data.EmbeddedTor.state.collectAsState()
    // The chat's own connection: checked until it is up, then now and then.
    val secretLinkUp by androidx.compose.runtime.produceState(false, isSecret, state.secretWaiting) {
        while (isSecret && !state.secretWaiting) {
            value = viewModel.secretLinkReady()
            kotlinx.coroutines.delay(if (value) 10_000 else 1_500)
        }
    }
    val linkDots by androidx.compose.runtime.produceState("", secretLinkUp) {
        var n = 0
        while (!secretLinkUp) { value = ".".repeat(n % 4); n++; kotlinx.coroutines.delay(400) }
        value = ""
    }
    val torPercent = if (isSecret) com.alphamessenger.ui.secret.rememberTorProgress(secretLinkUp) else null
    var confirmSecretEnd by remember { mutableStateOf(false) }
    var confirmDeleteChat by remember { mutableStateOf(false) }
    if (confirmDeleteChat) {
        val who = state.peerDisplayName.ifBlank { stringResource(R.string.profile_delete_fallback_contact) }
        DeleteChatDialog(
            title = stringResource(R.string.delete_chat_title),
            body = stringResource(R.string.profile_delete_dialog_text, who),
            colors = AlphaTheme.colorScheme,
            peerName = if (state.isGroup) null else who,
            onConfirm = { forBoth -> confirmDeleteChat = false; viewModel.deleteChatAndExit(forBoth) { onBack() } },
            onDismiss = { confirmDeleteChat = false },
        )
    }
    if (confirmSecretEnd) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSecretEnd = false },
            text = { Text(stringResource(R.string.secret_end_confirm)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmSecretEnd = false
                    viewModel.endSecretChat { onBack() }
                }) { Text(stringResource(R.string.secret_end), color = colors.danger) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmSecretEnd = false }) {
                    Text(stringResource(R.string.secret_not_now))
                }
            },
        )
    }
    val title = when {
        isSavedChat -> stringResource(R.string.menu_saved)
        isAssistant -> assistantProfile?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.assistant_title)
        isSecret -> stringResource(R.string.secret_name, state.peerDisplayName.ifBlank { stringResource(R.string.private_chat) })
        !state.isGroup && state.peerDisplayName.isNotBlank() -> state.peerDisplayName
        state.chatInfo?.name?.isNotBlank() == true -> state.chatInfo!!.name!!
        state.isGroup -> stringResource(R.string.group_chat)
        else -> stringResource(R.string.private_chat)
    }
    // Smart subtitle:
    //  • peer typing/recording → live activity (accent-coloured)
    //  • group  → "N members"
    //  • direct → "protected by end-to-end encryption"
    val activity = state.peerActivity
    val subtitle = when {
        isSavedChat -> stringResource(R.string.saved_subtitle)
        isAssistant -> when (assistantLive?.phase) {
            com.alphamessenger.assistant.AssistantEngine.Phase.CONNECTING -> stringResource(R.string.assistant_connecting)
            com.alphamessenger.assistant.AssistantEngine.Phase.THINKING -> stringResource(R.string.assistant_status_thinking)
            com.alphamessenger.assistant.AssistantEngine.Phase.WRITING -> stringResource(R.string.assistant_status_writing)
            null -> listOfNotNull(assistantProfile?.model?.takeIf { it.isNotBlank() })
                .joinToString(" · ").ifBlank { stringResource(R.string.assistant_not_set_up) }
        }
        isSecret && activity == null -> when {
            state.secretState == "invited" -> stringResource(R.string.secret_invited_text, state.peerDisplayName)
            state.secretState == "accepted" -> stringResource(R.string.secret_accepted_text)
            state.secretState == "declined" -> stringResource(R.string.secret_declined_text)
            state.secretWaiting -> stringResource(R.string.secret_waiting_peer, state.peerDisplayName)
            secretLinkUp -> stringResource(R.string.secret_tor_on)
            torState == com.alphamessenger.data.EmbeddedTor.State.ON -> stringResource(R.string.secret_linking) + linkDots
            torPercent != null -> stringResource(R.string.secret_tor_progress, torPercent)
            else -> stringResource(R.string.secret_tor_connecting)
        }
        activity != null -> {
            val named = state.isGroup && activity.senderName.isNotBlank()
            if (activity.state == "recording") {
                if (named) stringResource(R.string.chat_recording_named, activity.senderName)
                else stringResource(R.string.chat_recording)
            } else {
                if (named) stringResource(R.string.chat_typing_named, activity.senderName)
                else stringResource(R.string.chat_typing)
            }
        }
        // Not known yet: nothing rather than "0 subscribers" a moment before the real count.
        (state.channel != null || state.isGroup) && !state.chromeKnown -> ""
        state.channel != null -> pluralStringResource(R.plurals.channel_subscribers, state.memberCount, state.memberCount)
        state.isGroup -> pluralStringResource(R.plurals.chat_members, state.memberCount, state.memberCount)
 // 1:1: online / their status / last seen; encryption is a
        // given in this app and needs no permanent label.
        else -> presenceSubtitle(state.peerStatus, state.peerStatusText, state.peerLastSeen)
    }
    val subtitleAccent = if (isAssistant) assistantLive != null else activity != null || (!state.isGroup && presenceIsLive(state.peerStatus))

    // The messages (and the wallpaper) run under the header; the header and the
    // pinned pill show them through frosted glass.
    val chatHaze = remember { com.alphamessenger.ui.components.GlassState() }
    // Where each message on screen sits (root coordinates): the menu opens beside it.
    val messageBounds = remember { HashMap<String, androidx.compose.ui.geometry.Rect>() }
    // The bubble itself (tighter than the row) when the bubble reports it.
    val bubbleBounds = remember { HashMap<String, BubbleSpot>() }
    val menuCtx = androidx.compose.ui.platform.LocalContext.current
    MessageMenuPrefs.load(menuCtx)
    // Sizes the list runs under: the window, the strips above the bar, the bar.
    var rootHpx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var aboveBarHpx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var inputBarHpx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val underBarPx = aboveBarHpx + inputBarHpx
    val underBarDp = with(androidx.compose.ui.platform.LocalDensity.current) { underBarPx.toDp() }
    val navBarPx = WindowInsets.navigationBars.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    // The navigation bar is see-through here: our own frosted strip is under it.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val owner = Any()
        com.alphamessenger.ui.theme.SystemBars.requestNav(owner, Color.Transparent)
        onDispose { com.alphamessenger.ui.theme.SystemBars.releaseNav(owner) }
    }
    // Pins this reader closed (local only; a newly pinned message brings the bar back).
    val pinPrefs = remember { chatCtx.getSharedPreferences("pinned_hidden", android.content.Context.MODE_PRIVATE) }
    var hiddenPins by remember(chatId) { mutableStateOf(pinPrefs.getStringSet(chatId, emptySet()).orEmpty().toSet()) }
    android.os.Trace.beginSection("CT_Scaffold"); val __csf = System.nanoTime()
    Scaffold(
        modifier = if (colors.bgBrush != null) Modifier.themeBackground(colors) else Modifier,
        containerColor = if (colors.bgBrush != null) Color.Transparent else colors.bgChat,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
            // Floating glass pill over the haze, or the full-width glass (Appearance).
            ChatTopGlass(chatHaze, colors) {
            // The network status banner goes at the VERY TOP. At the bottom it
            // would fight the input bar: the bottom surface is the single owner
            // of its layout, and a foreign element there either steals space or
            // overlaps. At the top there is nothing to cover.
            com.alphamessenger.ui.components.NetworkBanner(online = networkOnline)
            if (selectionMode) {
                val selPost = if (state.channel?.comments == true && state.isOwner && selectedIds.size == 1)
                    selectedIds.first().substringBefore("#al").takeIf { id ->
                        state.messages.any { it.id.substringBefore("#al") == id }
                    } else null
                // An album part can be picked on its own; a whole album brings all its parts.
                val selMedia = if (protectedChannel) emptyList() else selectedIds.flatMap { id ->
                    state.messages.find { it.id == id }?.let { it.albumItems ?: listOf(it) }
                        ?: state.messages.firstNotNullOfOrNull { m -> m.albumItems?.find { it.id == id } }?.let(::listOf)
                        ?: emptyList()
                }.filter { it.mediaType in SAVEABLE_MEDIA && it.mediaRefJson != null && !it.isViewOnce }
                    .distinctBy { it.id }
                SelectionTopBar(
                    count = selectedIds.size,
                    colors = colors,
                    onDownload = if (selMedia.isEmpty()) null else ({
                        viewModel.saveMediaToGallery(selMedia)
                        exitSelection()
                    }),
                    commentsOpen = selPost?.let { state.channel?.commentsOpen(it) == true },
                    onToggleComments = {
                        selPost?.let { id ->
                            val nowOpen = state.channel?.commentsOpen(id) != true
                            viewModel.setPostCommentsOpen(id, nowOpen)
                            android.widget.Toast.makeText(chatCtx, chatCtx.getString(if (nowOpen) R.string.cmt_opened_toast else R.string.cmt_closed_toast), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        exitSelection()
                    },
                    onClose = { exitSelection() },
                    onForward = {
                        if (protectedChannel) {
                            android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.channel_protected_toast), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
 // one-time (view-once) messages are never forwarded.
                        // Forward the rest; if any were dropped, tell the user why.
                        val allowed = selectedIds.filter { id ->
                            state.messages.find { it.id == id }?.isViewOnce != true
                        }
                        if (allowed.size < selectedIds.size)
                            android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_viewonce_no_forward), android.widget.Toast.LENGTH_SHORT).show()
                        if (allowed.isNotEmpty()) forwardIds = allowed
                        }
                    },
                    onSaved = {
                        if (protectedChannel) {
                            android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.channel_protected_toast), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                        // Quick forward to Saved Messages — no picker, no menu.
                        val allowed = selectedIds.filter { id ->
                            state.messages.find { it.id == id }?.isViewOnce != true
                        }
                        if (allowed.size < selectedIds.size)
                            android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_viewonce_no_forward), android.widget.Toast.LENGTH_SHORT).show()
                        if (allowed.isNotEmpty()) {
                            viewModel.forwardToSaved(allowed)
                            exitSelection()
                            android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_saved_toast), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        }
                    },
                    onDelete = if (state.deleteScope(selectedIds) == DeleteScope.NONE) null
                        else ({ deleteDialogIds = selectedIds.toList() }),
                    onCopy = {
                        if (protectedChannel) {
                            android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.channel_protected_toast), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                        val combined = state.messages
                            .filter { it.id in selectedIds }
                            .joinToString("\n") { com.alphamessenger.assistant.AssistantMarks.answerOf(it.text).ifBlank { it.mediaCaption ?: "" } }
                        val cm = chatCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("msgs", combined))
                        android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_copied), android.widget.Toast.LENGTH_SHORT).show()
                        exitSelection()
                        }
                    },
                )
            } else {
            android.os.Trace.beginSection("CT_TopBar"); val __ctb = System.nanoTime()
            if (scheduledMode) com.alphamessenger.ui.components.OptionTopBar(stringResource(R.string.schedule_list_title), onBack)
            else if (state.discussion != null) com.alphamessenger.ui.channel.DiscussionTopBar(
                // Who is writing in this discussion, else how many comments under which channel.
                accent = activity != null,
                subtitle = if (activity != null) subtitle else listOfNotNull(
                    state.discussion?.count?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.channel_comments_n, it, it) },
                    state.chatInfo?.name?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                colors = colors,
                onBack = onBack,
            )
            else ChatTopBar(
                // The avatar colour seed is the CHAT ID, same as everywhere.
                //
                // The colour is hash(seed) % palette size, so different seeds
                // give different colours — each screen is stable on its own, so
                // seeding with the peer npub here made the same friend lilac in
                // the chat list and red in the open dialog.
                //
                // Of the four places that draw this circle (chat list, chat
                // picker, large preview, this header) the first three seed with
                // the chat id. Match them: the list row does not even know the
                // peer — `ChatInfo` has no such field, and adding one would mean
                // changing the UDL and bindings for a circle colour.
                seed = chatId,
                title = title,
                subtitle = subtitle,
                subtitleAccent = subtitleAccent,
                colors = colors,
                onBack = onBack,
                // A group calls everyone in it; a dialog calls the person.
                onPhone = {
                    if (state.isGroup) com.alphamessenger.calls.group.GroupCalls.start(chatCtx, chatId, video = false)
                    else com.alphamessenger.calls.CallLauncher.start(chatCtx, chatId, com.alphamessenger.calls.CallType.AUDIO)
                },
                onVideo = {
                    if (state.isGroup) com.alphamessenger.calls.group.GroupCalls.start(chatCtx, chatId, video = true)
                    else com.alphamessenger.calls.CallLauncher.start(chatCtx, chatId, com.alphamessenger.calls.CallType.VIDEO)
                },
                // A secret chat has no profile page: its peer is a persona.
                onAvatar = { onContactInfo(chatId) },
                onTitleRow = { onContactInfo(chatId) },
                // A bot's settings sit in the ⋮ menu; the header opens its info like any chat's.
                onMenu = { if (isAssistant) onOpenAssistant(chatId.removePrefix(com.alphamessenger.assistant.AssistantProfile.CHAT_PREFIX)) else onContactInfo(chatId) },
                isMuted = state.isMuted,
                onToggleMute = { viewModel.toggleMute() },
                onSearch = { chatSearchQuery = "" },
                onScrollToTop = { viewModel.revealStart { count -> topJumpAt = count } },
                onClearHistory = { viewModel.clearHistoryLocal() },
                // Not a secret chat, and not one whose owner forbade copying.
                onExport = if (!isSecret && !protectedChannel && !scheduledMode && !isAssistant) ({ onExport(chatId) }) else null,
                onDeleteChat = { if (isSecret) confirmSecretEnd = true else confirmDeleteChat = true },
                // Disappearing timer: 1:1 anyone; groups OWNER/ADMIN only.
                showTimer = !state.isGroup || state.isAdmin,
                timerActive = state.chatTimerSeconds > 0,
                onDisappearing = { showTimerDialog = true },
                noStore = state.noStore,
                onToggleNoStore = { viewModel.toggleNoStore() },
                secret = isSecret,
                isSaved = isSavedChat || isAssistant,
                assistant = isAssistant,
                assistantVm = if (isAssistant) viewModel else null,
                assistantMemory = assistantProfile?.memory == true,
                showCalls = state.channel == null && !isSecret,
                showVoice = if (state.isGroup) state.groupCalls else state.peerCanCall,
                showVideo = if (state.isGroup) state.groupVideoCalls else state.peerCanVideo,
                channelMenu = if (state.channel == null) null else ChannelMenu(
                    reader = !state.isAdmin,
                    onLeave = if (state.isOwner) null else ({ confirmLeaveChannel = true }),
                ),
                isGroup = state.isGroup,
                onAddMember = { showAddMemberSheet = true },
                // 1:1 → peer's broadcast avatar; group → admin's group avatar.
                avatarPath = state.peerAvatarPath
                    .takeIf { it.isNotBlank() }
                    ?: state.chatInfo?.avatarPath
                        ?.takeIf { it.isNotBlank() }
                    ?: "",
            )
            }
            // In-chat search bar (top-bar menu → "Search"): the matches come from the core,
            // the list stays whole and the bar at the bottom steps through them.
            chatSearchQuery?.let { q ->
                val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { if (searchQuery == null) runCatching { searchFocus.requestFocus() } }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.bgTertiary)
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Outlined.Search, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = q,
                        onValueChange = { chatSearchQuery = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        modifier = Modifier.weight(1f).focusRequester(searchFocus),
                        decorationBox = { inner ->
                            if (q.isEmpty()) Text(stringResource(R.string.chat_search_hint), color = colors.textMuted, fontSize = 16.sp)
                            inner()
                        },
                    )
                    IconButton(onClick = { chatSearchQuery = null }, modifier = Modifier.size(32.dp)) {
                        Icon(AlphaIcons.Outlined.Close, stringResource(R.string.chat_search_close), tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = colors.alphaBorder)
            }
            }
            // Mini-players (music, voice, video messages) float under the header
            // as pills of glass, like the pinned message — no panel of their own.
            androidx.compose.runtime.CompositionLocalProvider(LocalPlayerHaze provides chatHaze) {
            GlobalMusicBar(
                controller = musicController, onOpenChat = onOpenChat,
                // This chat's own track: scroll to it right here; another chat's: open it there.
                onOpenMessage = { c, m -> if (c == chatId) jumpRequest = m else onOpenChatAt(c, m) },
            )
            // Active-call banner (#3) — shows in a chat dialog too, not just
            // the chat list. Lets the user surf chats while on a call.
            com.alphamessenger.calls.ui.CallBanner()
            com.alphamessenger.calls.group.GroupCallReturnBar()
            // Voice note mini-player (mic icon). Hidden for THIS chat's own voice
            // (the bubble already shows the player); shows only if a voice from a
            // DIFFERENT chat is playing — tap opens that chat.
            val voiceSt = voiceController.state.collectAsState().value
            if (voiceSt.currentId != null && voiceController.sourceChatId() != chatId) {
                GlobalVoiceBar(controller = voiceController, onOpenChat = onOpenChat)
            }
            // A video message playing (from this chat or another): its bar.
            com.alphamessenger.ui.chat.round.GlobalRoundBar(onOpenChat = onOpenChat)
            }
            // A call going on in this group: who is in, and "Join" — a card of its
            // own under the header, like the pinned bar (inside the header's glass it
            // stretched the glass behind it).
            if (state.isGroup && state.channel == null && state.discussion == null && !scheduledMode) {
                com.alphamessenger.calls.group.GroupCallBanner(chatId)
            }
            if (isSecret && !state.secretWaiting && !secretLinkUp && torState != com.alphamessenger.data.EmbeddedTor.State.ON) {
                com.alphamessenger.ui.secret.SecretTorBanner()
            }
            // Saved Messages: the tags in use, to show one at a time.
            if (isSavedChat && !scheduledMode) {
                val tags = remember(state.messages) { savedTagCounts(state.messages) }
                if (tags.isNotEmpty() || savedTag != null) SavedTagBar(tags, savedTag, colors) { savedTag = it }
            }
            // Pinned messages: the shown one, tap = go to it (and to the next pin).
            if (!scheduledMode && !selectionMode && state.pinnedIds.isNotEmpty() && !state.pinnedIds.all { it in hiddenPins }) {
                val pins = state.pinnedIds
                val at = pinnedIndex.coerceIn(0, pins.lastIndex)
                val shownId = pins[at]
                val shown = state.messages.firstOrNull { it.id.substringBefore("#al") == shownId }
                androidx.compose.runtime.CompositionLocalProvider(LocalPinnedHaze provides chatHaze) {
                PinnedBar(
                    text = shown?.let { replyPreviewLabel(it) } ?: stringResource(R.string.chat_pinned_title),
                    index = at, count = pins.size, colors = colors,
                    onClick = {
                        // The bar always shows the message it leads to: the first tap
                        // goes to the shown pin; tapping again moves on to the next one.
                        val next = if (lastPinJump == shownId) (if (at + 1 > pins.lastIndex) 0 else at + 1) else at
                        pinnedIndex = next
                        lastPinJump = pins[next]
                        jumpRequest = pins[next]
                    },
                    onUnpin = if (state.canPin) ({ viewModel.togglePin(shownId) }) else ({
                        hiddenPins = state.pinnedIds.toSet()
                        pinPrefs.edit().putStringSet(chatId, hiddenPins).apply()
                    }),
                    closeLabel = stringResource(if (state.canPin) R.string.chat_unpin else R.string.chat_pinned_hide),
                )
                }
            }
            }
        },
        snackbarHost = {
            // Keep error/snackbar above the nav bar AND the keyboard (some were
            // hidden behind the navigation bar before).
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.navigationBarsPadding().imePadding(),
            )
        },
    ) { inner ->
        val chatTopPad = inner.calculateTopPadding()
        androidx.compose.runtime.SideEffect { v2State.topReserve = chatTopPad }
        Box(modifier = Modifier.fillMaxSize()
            .onSizeChanged {
                rootHpx = it.height
                if (CHAT_TRACE) android.util.Log.i("KBTRACE", "ROOT h=${it.height} t=${System.nanoTime() / 1_000_000}")
            }
        ) {
        // Chat wallpaper as a FIXED full-area background — drawn behind EVERYTHING
        // (bars + list + input) so it never shifts when the music/voice mini-player
        // appears at the top. (Default = no layer → Scaffold bgChat shows through.)
        // The box no longer depends on the header (it runs under it), so the
        // picture keeps its place when the header changes (selection, pins).
        Column(
            modifier = Modifier
                .fillMaxSize()
                // No imePadding: in this window setup it doesn't lift content
                // reliably. Both the normal input and the picker caption bar use
                // a MANUAL keyboard spacer (height = WindowInsets.ime) instead.
                .navigationBarsPadding()
                .onSizeChanged { if (CHAT_TRACE) android.util.Log.i("KBTRACE", "COL h=${it.height} t=${System.nanoTime() / 1_000_000}") },
        ) {
            // Message list + scroll-to-bottom FAB overlay. Always weight(1f) —
            // the BottomPanel's animated height is what shrinks/releases it.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { if (CHAT_TRACE) android.util.Log.i("KBTRACE", "LIST h=${it.height} t=${System.nanoTime() / 1_000_000}") }
                    // Runs on under the strips and the input bar (they are glass), in the
                    // same layout pass — the keyboard still moves it without a lag.
                    .layout { m, cs ->
                        val extra = underBarPx + navBarPx
                        val p = m.measure(cs.copy(minHeight = cs.maxHeight + extra, maxHeight = cs.maxHeight + extra))
                        layout(cs.maxWidth, cs.maxHeight) { p.place(0, 0) }
                    }
                    // What the header, the pinned pill and the input bar blur.
                    .glassSource(chatHaze),
            ) {
            // The wallpaper: the whole window's size, pinned to the top (the list's
            // top never moves), so neither the header nor the keyboard re-crop it.
            if (BubbleFlags.WALLPAPER) com.alphamessenger.ui.theme.ChatWallpaperBackground(
                wallpaper = com.alphamessenger.ui.theme.LocalChatWallpaper.current,
                modifier = Modifier.layout { m, cs ->
                    // The window's height ONLY — never the list's (for a frame the list can be taller
                    // while the bar changes height, and the picture would zoom and snap back).
                    val h = if (rootHpx > 0) rootHpx else cs.maxHeight
                    val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(cs.maxWidth, h))
                    layout(cs.maxWidth, cs.maxHeight) { p.place(0, 0) }
                },
            )
            // (The wallpaper is a fixed full-area background above — see the outer
            // Box — so it never shifts when a mini-player appears.)
            // reverseLayout=true → index 0 is the NEWEST message, rendered at
            // the bottom. The LazyListState starts at index 0, so the FIRST
            // frame is already anchored to the latest message — no post-render
            // scroll, no flicker. We feed the data reversed so index 0 == newest.
            // Hidden album members are folded into their head's grid — drop them.
            val channelMode = state.channel != null
            val rendered = state.messages.asReversed()
                .filter { !it.hiddenInAlbum }
                .let { if (isAssistant) com.alphamessenger.assistant.assistantRows(it, assistantShown, java.time.OffsetDateTime.now().toString()) else it }
                // Join / leave / ban lines: never in a channel; in a group as its admins set.
                .filter { m -> m.isMedia || !(channelMode || !state.showNotices) || !(m.text.startsWith("➕") || m.text.startsWith("↩️") || m.text.startsWith("🚫")) }
                .filter { (it.id.substringBefore("#al") in heldHere) == scheduledMode }
                // Scheduled view: ordered and dated by the time each will be SENT
                // (the day dividers and the bubble time show the send time).
                .let { list ->
                    if (!scheduledMode) list
                    else list.map { m ->
                        val at = heldHere[m.id.substringBefore("#al")] ?: 0L
                        if (at > 0L) m.copy(timestamp = java.time.Instant.ofEpochMilli(at).toString()) else m
                    }.sortedByDescending { heldHere[it.id.substringBefore("#al")] ?: 0L }
                }
                .let { list -> savedTag?.let { t -> list.filter { t in it.myReactions } } ?: list }
            // Assistant: a reversed list keeps its bottom still, so a growing answer pushes
            // its own start up and away. Once that start reaches the top, the row above it
            // is placed in the measure pass itself (no correction a frame later), so the
            // answer grows downward under a start that stays put — also while its stored
            // rows take the live ones' place. Any scroll not ours hands the list back.
            val answering = assistantLive != null
            // Letters of a written answer may still be on their way to the screen.
            val revealing = answerReveal.busy
            val holdAtPx = with(androidx.compose.ui.platform.LocalDensity.current) { (chatTopPad + 8.dp).toPx() }
            // The same lower padding the list is given below, as it is in this frame.
            val listBottomPadPx = with(androidx.compose.ui.platform.LocalDensity.current) { (8.dp + underBarDp + navBarDp).toPx() }
            if (isAssistant) {
                LaunchedEffect(listState) {
                    listState.interactionSource.interactions.collect {
                        if (it is androidx.compose.foundation.interaction.DragInteraction.Start) answerFollow = false
                    }
                }
                LaunchedEffect(answering) {
                    if (!answering) return@LaunchedEffect
                    answerPin.key = null
                    answerFollow = true
                    // New rows go in below the message the list was holding on to: bring them up.
                    answerPin.ownScroll = true
                    try { listState.scrollToItem(0) } finally { answerPin.ownScroll = false }
                }
                // Run where the list is composed and on every frame that lets letters in: those
                // frames grow the answer without composing the screen.
                val holdAnswer: () -> Unit = hold@{
                    if (answerFollow && listState.isScrollInProgress && !answerPin.ownScroll) answerFollow = false
                    if (!answerFollow) {
                        answerPin.key = null
                        return@hold
                    }
                    val info = listState.layoutInfo
                    val viewport = info.viewportSize.height - listBottomPadPx
                    // Held from the first frame, decided from the rows alone (no look at the last
                    // layout: a fast service delivers the whole answer between two frames). While the
                    // answer is short the list clamps it to the bottom; once taller, its start holds.
                    if (answerPin.key == null) {
                        val top = rendered.indexOfLast { it.assistantWriting }
                        if (top >= 0) answerPin.key = rendered.getOrNull(top + 1)?.id
                    }
                    val i = answerPin.key?.let { k -> rendered.indexOfFirst { it.id == k } } ?: -1
                    if (i >= 0) {
                        // Negative: the anchor sits that far above the list's start, the rows below it fill the gap.
                        listState.requestScrollToItem(typingLead + i, kotlin.math.round(holdAtPx - viewport).toInt())
                    } else answerPin.key = null
                }
                val holdNow = androidx.compose.runtime.rememberUpdatedState(holdAnswer)
                LaunchedEffect(answering || revealing) {
                    if (!answering && !revealing) return@LaunchedEffect
                    while (true) androidx.compose.runtime.withFrameNanos { t ->
                        answerReveal.tick(t)
                        holdNow.value()
                    }
                }
                androidx.compose.runtime.SideEffect {
                    holdAnswer()
                    // Written and shown: held for this frame once more, then the list is the user's. The
                    // "new message" effect runs after this, so it is told which message not to scroll to.
                    if (answerFollow && !answering && !revealing && assistantShown == null) {
                        answerPin.releasedFor = state.messages.lastOrNull()?.id
                        answerFollow = false
                    }
                }
            }
            // Group avatars ride with their run of messages (GroupAvatarLayer).
            val avatarRuns = remember(rendered, state.isGroup, channelMode) {
                if (!state.isGroup || channelMode) emptyList()
                else avatarRuns(rendered) { m ->
                    !m.isOutgoing && m.callLog == null && m.secretInvite == null && m.timerSeconds == null &&
                        (m.isMedia || !(m.text.startsWith("🚫") || m.text.startsWith("↩️") || m.text.startsWith("➕") || m.text.startsWith("🗑")))
                }
            }
            val layerAvatars = remember(avatarRuns) { avatarRuns.mapNotNull { rendered.getOrNull(it.second)?.id }.toHashSet() }
            // Channel posts: ~88% of the screen, one width for all.
            val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
            val channelPostWidth = (screenW * 0.88f).coerceAtMost(420.dp)
            // A short post hugs its text; this floor keeps the time, views and the
            // (compact) comments line legible.
            val channelPostMinWidth = 176.dp
            // Channel: posts on screen count as seen.
            val renderedNow by androidx.compose.runtime.rememberUpdatedState(rendered)
            if (channelMode) {
                LaunchedEffect(listState, chatId) {
                    androidx.compose.runtime.snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
                    }.collect { keys ->
                        val ids = keys.filter { k -> renderedNow.firstOrNull { it.id == k }?.isOutgoing == false }
                            .map { it.substringBefore("#al") }.distinct()
                        viewModel.markChannelSeen(ids)
                    }
                }
            }
            // Localized day labels (weekdays/months/Today/Yesterday) for the
            // date dividers and the floating date badge.
            val dayLabels = rememberDayLabels()
            // "Show in chat" from the viewer: scroll the list to the message
            // (the laid-out items are available exactly here), then reset.
            LaunchedEffect(mediaScrollToId) {
                val id = mediaScrollToId ?: return@LaunchedEffect
                val idx = rendered.indexOfFirst { it.id == id }
                if (idx >= 0) ScrollCoordinator.execute(listState, ScrollIntent.JumpTo(idx + typingLead))
                mediaScrollToId = null
            }
            // Deep-link focus: scroll to + highlight a message opened from the
            // shared-media browser ("show in chat"). Album children (#alN) map
            // to their head bubble. Runs once per focus id, after data loads.
            LaunchedEffect(jumpRequest, rendered.size, state.historyFresh) {
                val target = jumpRequest ?: return@LaunchedEffect
                // The snapshot of the last visit is about to be replaced: a jump into
                // it lands on rows that vanish a moment later.
                if (!state.historyFresh) return@LaunchedEffect
                val idx = rendered.indexOfFirst { it.id.substringBefore("#al") == target }
                if (idx >= 0) {
                    // The list must have laid out the rows just loaded: a jump to an
                    // index it does not hold yet stops at its edge, somewhere else.
                    val at = idx + typingLead
                    androidx.compose.runtime.snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > at }
                    ScrollCoordinator.execute(listState, ScrollIntent.JumpTo(at))
                    highlightedId = rendered[idx].id
                    if (focusMessageId?.substringBefore("#al") == target) viewModel.focusShown = focusMessageId
                    jumpRequest = null
                } else {
                    // Older than what is loaded: load history up to it, then this runs again.
                    viewModel.revealMessage(target) { found ->
                        if (!found) {
                            jumpRequest = null
                            if (focusMessageId?.substringBefore("#al") == target) viewModel.focusShown = focusMessageId
                            android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_jump_gone), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            // History loads a buffer ahead of the reader, so a fling never reaches the
            // oldest loaded message and stops there waiting for the next page.
            LaunchedEffect(listState, rendered.size) {
                androidx.compose.runtime.snapshotFlow {
                    val info = listState.layoutInfo
                    (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - HISTORY_PREFETCH
                }.collect { nearTop -> if (nearTop) viewModel.loadOlder() }
            }
            if (focusMessageId != null) {
                LaunchedEffect(focusMessageId) {
                    // Marked shown once reached: until then the first fill does not pin to the bottom.
                    if (viewModel.focusShown == focusMessageId) return@LaunchedEffect
                    jumpRequest = focusMessageId.substringBefore("#al")
                }
            }
            // A tap on a link or hashtag in any text of a message (captions too).
            val screenEntityTap: (com.alphamessenger.ui.chat.text.TextEntity) -> Unit = remember {
                { e ->
                    e.toLinkAction()?.let { a ->
                        when {
                            a is LinkAction.Hashtag -> chatSearchQuery = a.display
                            a is LinkAction.Invite && com.alphamessenger.ui.theme.isThemeLink(a.link) ->
                                com.alphamessenger.ui.theme.ThemeUi.open(a.link)
                            a is LinkAction.Invite && com.alphamessenger.ui.sticker.isPackLink(a.link) ->
                                com.alphamessenger.ui.sticker.StickerUi.open(a.link, null)
                            else -> pendingLinkAction = a
                        }
                    }
                }
            }
            // A sticker that first shows while the list moves waits for it to stop before it
            // comes alive: building its player mid-glide is what stalled the down button.
            val listMoving = remember(listState) { androidx.compose.runtime.derivedStateOf { listState.isScrollInProgress } }
            val codeCopy: (String) -> Boolean = remember(protectedChannel) {
                { code ->
                    if (protectedChannel) {
                        android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.channel_protected_toast), android.widget.Toast.LENGTH_SHORT).show()
                        false
                    } else {
                        val cm = chatCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                        android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.code_copied_toast), android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            }
            // The list and the avatars over it stretch together at the ends.
            ChatOverscroll {
            androidx.compose.runtime.CompositionLocalProvider(
                com.alphamessenger.ui.sticker.LocalListMoving provides listMoving,
                com.alphamessenger.ui.chat.text.LocalCodeCopy provides codeCopy,
                com.alphamessenger.ui.chat.text.LocalSearchHighlight provides chatSearchQuery?.trim()?.takeIf { it.isNotEmpty() },
                LocalLayerAvatars provides layerAvatars,
                com.alphamessenger.ui.chat.text.LocalWholeText provides isAssistant,
                LocalPlainIncoming provides isAssistant,
            ) {
            LazyColumn(
                state = listState,
                // A touch that lands while the list still glides only stops it: its lift
                // is taken here, so the bubble under the finger does not open its menu.
                modifier = Modifier.fillMaxSize().pointerInput(listState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (!listState.isScrollInProgress) return@awaitEachGesture
                        // A finger that moves on is a new swipe and keeps its fling.
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                            if (!ch.pressed) { ch.consume(); break }
                        }
                    }
                },
                // Smaller left inset so group members' avatars + bubbles sit
                // closer to the screen edge (right stays as-is for our bubbles).
                contentPadding = PaddingValues(top = chatTopPad + 8.dp, bottom = 8.dp + underBarDp + navBarDp),
                reverseLayout = true,
            ) {
                typingShown?.takeIf { typingRow }?.let { activity ->
                    item(key = TYPING_ROW_KEY, contentType = "typing") {
                        // Unfolds from the bottom edge and folds back when they stop.
                        Box(
                            Modifier.fillMaxWidth().clipToBounds()
                                .layout { measurable, constraints ->
                                    val p = measurable.measure(constraints)
                                    val h = kotlin.math.round(p.height * typingFold.value).toInt()
                                    layout(p.width, h) { p.place(0, h - p.height) }
                                }
                                .graphicsLayer { alpha = typingFold.value },
                        ) { Box(Modifier.onSizeChanged { typingFullPx = it.height }) { PresenceBar(activity, state.isGroup, colors) } }
                    }
                }
                itemsIndexed(
                    rendered,
                    key = { _, m -> m.id },
                    // contentType lets LazyColumn REUSE composition slots between
                    // items of the same kind. Without it, scrolling from a text
                    // bubble to a photo bubble forces a full recomposition instead
 // of reusing the slot.
                    contentType = { _, m ->
                        when {
                            m.callLog != null -> "call"
                            m.secretInvite != null -> "secret"
                            m.timerSeconds != null -> "timer"
                            m.isViewOnce -> "viewonce"
                            m.albumItems != null -> "album"
                            m.isMedia && m.mediaType == "location" -> "location"
                            m.isMedia && m.mediaType == "link" -> "link"
                            m.isMedia && m.mediaType == "audio" -> "audio"
                            // Built nothing like a photo or a video: sharing their slots rebuilt them whole.
                            m.isStickerLike -> "sticker"
                            m.isGif -> "gif"
                            m.isRoundVideo -> "round"
                            m.isMedia && m.mediaType == "video" -> "video"
                            m.isMedia && m.mediaType != "image" -> "file"
                            m.isMedia -> "photo"
                            else -> "text"
                        }
                    },
                ) { index, row ->
                    val revealed = if (isAssistant && !row.isOutgoing && !row.isMedia && row.assistantThinking == null)
                        answerReveal.of(row.id, row.text, row.assistantWriting) else null
                    val listed = if (revealed != null) row.copy(text = revealed.text) else row
                    // A quote names its author as everywhere else (a group member's
                    // name, the channel for its voices) — never a generic "peer".
                    val quotedName = listed.replyTo?.senderId?.let { state.peerProfiles[it]?.displayName }?.takeIf { it.isNotBlank() }
                    val msg = if (quotedName != null) listed.copy(replyTo = listed.replyTo?.copy(senderName = quotedName)) else listed
                    if (BubbleFlags.ITEM_STUB) { // TOP: skip ALL per-item work (dateKeyOf/patched/override)
                        Box(
                            Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp, horizontal = 8.dp)
                                .clip(RoundedCornerShape(12.dp)).background(colors.msgIn),
                        )
                        return@itemsIndexed
                    }
                    if (index == 0 && __firstBubble.compareAndSet(false, true)) {
                    }
 // In-history date divider: shown above the
                    // OLDEST message of each calendar day. The "older" neighbour is the
                    // NEXT item in the reversed list. Same MessageDayClassifier the
                    // floating badge uses — one source of truth, no divergent logic.
                    val older = rendered.getOrNull(index + 1)
                    val showDateDivider = MessageDayClassifier.needsDivider(older?.timestamp, msg.timestamp)
                    // Sender header / avatar only on the first bubble of a run
                    // from this sender (incoming groups only).
                    val showSender = !msg.isConsecutive && !channelMode
                    // Apply real peer name / avatar from contacts table if
                    // we have one — overrides "peer…XX" + gradient.
                    val override = state.peerProfiles[msg.senderId]
                    val patched = if (override != null) {
                        val realName = override.displayName.takeIf { it.isNotBlank() }
                        msg.copy(
                            senderName = realName ?: msg.senderName,
                            senderInitial = realName?.takeIf { it.isNotBlank() }?.let { com.alphamessenger.ui.components.avatarInitial(it) }
                                ?: msg.senderInitial,
                            senderAvatarPath = override.avatarPath.takeIf { it.isNotBlank() },
                            senderNpub = override.npub.takeIf { it.isNotBlank() },
                        )
                    } else msg
                    if (BubbleFlags.ITEM_STUB) { // all messages → Box (isolate screen vs bubbles)
                        Box(
                            Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp, horizontal = 8.dp)
                                .clip(RoundedCornerShape(12.dp)).background(colors.msgIn),
                        )
                        return@itemsIndexed
                    }
                    // Explicit Column so the date divider ALWAYS renders ABOVE
                    // the bubble. Emitting two children straight into a
                    // reverseLayout item placed them bottom-up (divider ended up
                    // BELOW the message); a Column lays top→bottom regardless.
                    // No per-row animateItemPlacement(): a reverseLayout chat mostly
                    // PREPENDS (new message at index 0); with the stable `key = m.id`
                    // + contentType slot-reuse above, the list already inserts at the top
                    // WITHOUT animating every existing row. animateItemPlacement() on EVERY
                    // row forced a placement/subcompose pass per visible row. If reorder
                    // animation is ever wanted (pin / delete / edit), gate it to those ops,
                    // not every item.
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (WAVE_DIAG) Modifier.phaseProbe() else Modifier),
                    ) {
                    if (WAVE_DIAG) {
                        DisposableEffect(Unit) { PhaseProbe.enter(waveTag(msg)); onDispose { PhaseProbe.leave() } }
                        RecompProbe.hit(waveTag(msg))
                    }
                    TimeCompose(waveTag(msg)) {
                    if (showDateDivider) {
                        val day = MessageDayClassifier.label(msg.timestamp, dayLabels)
                        // Its height lets the avatar layer find where the bubble row starts.
                        Box(Modifier.onSizeChanged { AvatarLayerDividers.heightPx[msg.id] = it.height }) {
                            DateDivider(label = if (scheduledMode) stringResource(R.string.schedule_divider, day) else day, colors = colors)
                        }
                    } else AvatarLayerDividers.heightPx.remove(msg.id)
                    // Service notices (ban / leave / join / group-deleted) render
                    // as a centered system chip, not a left/right bubble.
                    val isServiceNotice = !msg.isMedia && (
                        msg.text.startsWith("🚫") || msg.text.startsWith("↩️") ||
                            msg.text.startsWith("➕") || msg.text.startsWith("🗑")
                        )
                    if (msg.assistantThinking != null) {
                        com.alphamessenger.assistant.AssistantThinkingRow(msg.assistantThinking, colors)
                    } else if (isAssistant && com.alphamessenger.assistant.liveGenOf(msg.id) != null) {
                        com.alphamessenger.assistant.AssistantGenPlaceholder(com.alphamessenger.assistant.liveGenOf(msg.id)!!, colors)
                    } else if (isAssistant && com.alphamessenger.assistant.wallpaperRowPath(msg) != null) {
                        com.alphamessenger.assistant.AssistantWallpaperChip(com.alphamessenger.assistant.wallpaperRowPath(msg)!!, colors)
                    } else if (msg.assistantRelays != null) {
                        com.alphamessenger.assistant.AssistantRelayCard(msg.id, msg.assistantRelays, colors) { done ->
                            viewModel.assistant.applyRelays(chatId, msg.id, msg.assistantRelays, done)
                        }
                    } else if (msg.assistantError != null) {
                        com.alphamessenger.assistant.AssistantErrorCard(
                            msg.assistantError, colors,
                            onRetry = { viewModel.assistant.retry(chatId, msg.id) },
                            onSettings = { onOpenAssistant(chatId.removePrefix(com.alphamessenger.assistant.AssistantProfile.CHAT_PREFIX)) },
                        )
                    } else if (msg.callLog != null) {
                        // LOCAL call-log row (missed / incoming / outgoing) —
                        // tap = call back with the same type.
                        CallLogNotice(
                            entry = msg.callLog,
                            colors = colors,
                            onCallBack = {
                                com.alphamessenger.calls.CallLauncher.start(
                                    chatCtx, chatId,
                                    if (msg.callLog.video) com.alphamessenger.calls.CallType.VIDEO
                                    else com.alphamessenger.calls.CallType.AUDIO,
                                )
                            },
                        )
                    } else if (msg.secretInvite != null) {
                        val invite = msg.secretInvite
                        com.alphamessenger.ui.secret.SecretInviteNotice(
                            text = msg.text,
                            pending = !msg.isOutgoing && invite in state.secretOffers,
                            onAccept = { viewModel.acceptSecretOffer(invite) },
                            onDecline = { viewModel.declineSecretOffer(invite) },
                        )
                    } else if (msg.timerSeconds != null) {
                        TimerNotice(text = msg.text, active = msg.timerSeconds > 0, colors = colors)
                    } else if (isServiceNotice) {
                        SystemNotice(text = msg.text, colors = colors)
                    } else if (state.whispers[msg.id] != null || msg.whisperSecs != null || msg.isWhisper) {
                        // The row itself says it is a whisper, so the very first frame
                        // is right even before the whispers of the chat are read.
                        com.alphamessenger.ui.chat.whisper.WhisperBubble(
                            meta = state.whispers[msg.id] ?: com.alphamessenger.data.WhisperMeta(
                                messageId = msg.id, outgoing = msg.isOutgoing,
                                kind = if (msg.isMedia) "image" else "text", secs = msg.whisperSecs ?: 15,
                                burned = msg.isViewOnceConsumed, usedSecs = null, shot = false, expired = false,
                            ),
                            colors = colors,
                            onOpen = { if (!selectionMode) whisperOpen = msg.id },
                            onLongPress = {
                                selectionMode = true
                                selectedIds = selectedIds + msg.id
                            },
                        )
                    } else if (BubbleFlags.ITEM_STUB && patched.isMedia) {
                        Box(
                            Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 8.dp, vertical = 3.dp)
                                .clip(RoundedCornerShape(14.dp)).background(colors.msgIn),
                        )
                    } else {
                        // In a channel every post is the channel's: one side and one look
                        // for admins and subscribers alike, in the channel theme's colours.
                        val shown = if (channelMode) patched.copy(isOutgoing = false, isConsecutive = false, isLastInRun = true) else patched
                        val bubble: @Composable () -> Unit = {
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.alphamessenger.ui.chat.text.LocalTextEntityTap provides screenEntityTap,
                            com.alphamessenger.ui.channel.LocalInDiscussion provides (state.discussion != null),
                            com.alphamessenger.ui.components.LocalAdminSenders provides (if (state.discussion != null) state.adminSenders else emptySet()),
                            com.alphamessenger.ui.chat.text.LocalTextFade provides revealed?.fade,
                        ) {
                        val reportBubble = remember(msg.id) {
                            { s: BubbleSpot ->
                                bubbleBounds[msg.id] = s
                                if (actionSheetMsg?.id == msg.id && menuSpot?.let { it.first == msg.id && it.second.bounds == s.bounds } != true) menuSpot = msg.id to s
                            }
                        }
                        androidx.compose.runtime.CompositionLocalProvider(LocalBubbleBounds provides reportBubble) {
                        Box(Modifier.onGloballyPositioned {
                            val r = it.boundsInRoot()
                            messageBounds[msg.id] = r
                            if (actionSheetMsg?.id == msg.id && menuRow != (msg.id to r)) menuRow = msg.id to r
                        }.then(if (index == 0 && !channelMode) newestClip else Modifier)
                            .then(if (index == 0 && (growId == msg.id || typingReplaced)) growInPlace(msg.id) else Modifier)) {
                        MessageBubble(
                            message = shown,
                            showSender = showSender,
                            isGroup = state.isGroup && !channelMode,
                            onTap = {
                                if (selectionMode) {
                                    selectedIds = selectedIds.toMutableSet().apply {
                                        if (!add(msg.id)) remove(msg.id)
                                    }
                                    if (selectedIds.isEmpty()) selectionMode = false
                                } else {
                                    actionSheetMsg = patched
                                }
                            },
                            onLongPress = {
                                if (scheduledMode) {
                                    // A waiting message: its own actions (send now, reschedule, …).
                                    actionSheetMsg = patched
                                } else {
                                    // Consistent everywhere: long-press → multi-select.
                                    if (msg.id !in selectedIds) selectHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    selectionMode = true
                                    selectedIds = selectedIds + msg.id
                                }
                            },
                            // Single tap on a media bubble's chrome (caption/empty
                            // area, not the photo itself) → actions sheet. Tapping
                            // the photo/tile opens the viewer instead.
                            onMediaActions = { if (!selectionMode) actionSheetMsg = patched },
                            // The channel's voices have no profile to open — only the channel.
                            onAvatarClick = {
                                if (msg.senderId in state.channelVoices) channelVoice = patched else avatarPreview = patched
                            },
                            onReact = { emoji -> react(msg.id, emoji) },
                            onOpenReactions = { if (!selectionMode) actionSheetMsg = patched },
                            onShowReactions = { if (!selectionMode) reactionsMsg = patched },
                            colors = colors,
                            selectionMode = selectionMode,
                            isSelected = selectedIds.contains(msg.id),
                            onSwipeReply = { if (!readerOnly) replyAndType(patched) },
                            onQuoteTap = { quotedId ->
                                // Index is in the reversed render list.
                                val target = rendered.indexOfFirst { it.id == quotedId }
                                if (target >= 0) {
                                    scope.launch { ScrollCoordinator.execute(listState, ScrollIntent.JumpTo(target + typingLead)) }
                                    highlightedId = quotedId
                                }
                            },
                            isHighlighted = highlightedId == msg.id,
                            mediaLoader = { ref -> viewModel.loadMediaTracked(ref, msg.id) },
                            cachedMediaPath = { ref -> viewModel.cachedMediaPath(ref) },
                            mediaActions = viewModel.transferUi,
                            mediaProgress = viewModel.mediaProgress,
                            voiceController = voiceController,
                            musicController = musicController,
                            // The opened message is remembered EXPLICITLY: a
                            // forwarded or re-sent photo has the same file path,
                            // and locating the position by path would find the
                            // oldest message — the viewer would open at the wrong
                            // place.
                            onMediaClick = { path, messageId ->
                                fullscreenMediaMessageId = messageId
                                fullscreenMediaPath = path
                            },
                            onMediaSaveItem = { item -> viewModel.saveMediaToGallery(listOf(item)) },
                            repository = viewModel.repository,
                            onJoinInvite = { link ->
                                viewModel.joinViaLink(link) { msg ->
                                    android.widget.Toast.makeText(chatCtx, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            onViewOnceConsumed = { id -> viewModel.markViewOnceConsumed(id) },
                            // Cancel = stop the send properly: kill the job, drop it
                            // from the pending queue, remove the bubble — never
                            // leave the upload running or the queue entry behind.
                            onCancelUpload = { id -> viewModel.cancelUpload(id) },
                            onAlbumAction = { head, act -> viewModel.dispatchAlbum(head, act) },
                            onEntityTap = { action ->
                                // A hashtag searches this chat; other links get the link sheet.
                                if (action is LinkAction.Hashtag) chatSearchQuery = action.display
                                else pendingLinkAction = action
                            },
                            textSelectActive = textSelectMsgId == msg.id,
                            onRetryUpload = { id -> viewModel.retryPendingMedia(id) },
                        )
                        // One message selected: its quick reactions beside it (only one message can be reacted to).
                        if (selectionMode && msg.id in selectedIds && !scheduledMode && !msg.isDeleted && msg.callLog == null &&
                            (state.reactionPolicy.enabled || msg.myReactions.isNotEmpty()) &&
                            !(msg.isOutgoing && (viewModel.sendInFlight(msg.id) || viewModel.sendFailed(msg.id)))
                        ) {
                            val policy = state.reactionPolicy
                            val quick = policy.quick(QUICK_REACTIONS)
                            SelectionReactions(
                                visible = selectedIds.size == 1,
                                outgoing = shown.isOutgoing,
                                emoji = (quick + msg.myReactions.filter { it !in quick }).take(7),
                                mine = msg.myReactions,
                                colors = colors,
                                onReact = { e -> react(msg.id, e); exitSelection() },
                                onMore = if (policy.anyEmoji || policy.allowed.size > quick.size) ({
                                    exitSelection(); actionSheetPicker = true; actionSheetMsg = patched
                                }) else null,
                            )
                        }
                        }
                        }
                        }
                        }
                        if (channelMode) {
                            // A channel post: an ordinary bubble with views by the
                            // time and the comments strip attached under it.
                            val postId = msg.id.substringBefore("#al")
                            val singleMedia = (msg.albumItems?.size ?: 0) == 1 ||
                                (msg.albumItems == null && msg.isMedia && (msg.mediaType == "image" || msg.mediaType == "video"))
                            val postOpen = state.channel?.commentsOpen(postId) == true
                            val thread = state.threads[postId]
                            val footer = if (postOpen || (state.isOwner && state.channel?.comments == true) || thread != null) com.alphamessenger.ui.channel.PostFooter(
                                comments = thread?.count ?: 0,
                                textColor = if (state.isAdmin) colors.msgOutText else colors.accent,
                                onClick = { viewModel.prepareDiscussion(patched); onOpenThread(postId, null) },
                                closed = !postOpen,
                                unread = thread?.unread ?: 0,
                                senders = thread?.senders.orEmpty(),
                                profiles = state.peerProfiles,
                                bubbleColor = if (state.isAdmin) colors.outBubble else colors.msgIn,
                            ) else null
                            androidx.compose.runtime.CompositionLocalProvider(
                                com.alphamessenger.ui.channel.LocalPostViews provides (state.channelViews[postId] ?: 0),
                                com.alphamessenger.ui.channel.LocalPostForwards provides (state.channelForwards[postId] ?: 0),
                                com.alphamessenger.ui.channel.LocalPostFooter provides footer,
                                com.alphamessenger.ui.channel.LocalChannelPostWidth provides channelPostWidth,
                                // Text: as wide as the text (120dp floor). A single photo/video: its chat size.
                                // An album of several: the whole post width.
                                com.alphamessenger.ui.channel.LocalChannelPostMinWidth provides
                                    (if (singleMedia) 0.dp else channelPostMinWidth),
                                com.alphamessenger.ui.channel.LocalChannelPostFill provides ((msg.albumItems?.size ?: 0) > 1),
                                com.alphamessenger.ui.channel.LocalPostOwn provides msg.isOutgoing,
                            ) { bubble() }
                        } else bubble()
                    }
                    } // TimeCompose
                    }
                }
                // E2EE notice — emitted LAST so reverseLayout places it at the very
 // TOP of history (above the oldest message), classic.
                // Gated on !isLoading (not on "messages exist") so it's present from
                // the first open — including a brand-new empty chat — while still
                // avoiding the load-time anchor bug (messages + banner appear in the
                // SAME frame, so the list never anchors to a lone banner).
                val discussedPost = state.discussion?.post
                if (state.discussion != null) {
                    // Only once the thread is read: a lone card would be what the
                    // list anchors to, and the comments would then slide in under it.
                    if (discussedPost != null && state.discussion?.loaded == true) item(key = "__discussed_post__") {
                        androidx.compose.foundation.layout.Column {
                            com.alphamessenger.ui.channel.DiscussionPostCard(
                                post = discussedPost,
                                channelName = state.chatInfo?.name.orEmpty(),
                                colors = colors,
                                onClick = onBack,
                            )
                            if (rendered.isEmpty() && !state.isLoading) SystemNotice(
                                text = stringResource(R.string.discussion_empty), colors = colors,
                            )
                        }
                    }
                } else if (state.isRequest) {
                    item(key = "__request_notice__") { ChatRequestNotice(colors) }
                } else if (!state.isLoading) {
                    item(key = "__e2ee_banner__") {
                        if (state.secretOrigin.isNotEmpty()) {
                            com.alphamessenger.ui.secret.SecretChatNotice(
                                state = state.secretState,
                                peerName = state.peerDisplayName,
                            )
                        } else {
                            E2eeNotice(isGroup = state.isGroup, colors = colors, isSaved = chatId == "saved_messages_local", isChannel = state.channel != null)
                        }
                    }
                }
            }
            }
            if (avatarRuns.isNotEmpty()) {
                GroupAvatarLayer(
                    listState = listState,
                    rendered = rendered,
                    runs = avatarRuns,
                    patch = { m ->
                        val o = state.peerProfiles[m.senderId]
                        if (o == null) m else {
                            val realName = o.displayName.takeIf { it.isNotBlank() }
                            m.copy(
                                senderName = realName ?: m.senderName,
                                senderInitial = realName?.let { com.alphamessenger.ui.components.avatarInitial(it) } ?: m.senderInitial,
                                senderAvatarPath = o.avatarPath.takeIf { it.isNotBlank() },
                            )
                        }
                    },
                    topLimit = chatTopPad,
                    onAvatarClick = { m -> if (m.senderId in state.channelVoices) channelVoice = m else avatarPreview = m },
                )
            }
            } // ChatOverscroll
            // Scroll-to-bottom FAB — appears only when the user is reading
            // history. Smooth scroll + clear the unread badge on tap.
            androidx.compose.animation.AnimatedVisibility(
                visible = !isAtBottom || newestEndHidden,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(180), initialScale = 0.85f),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                ScrollToBottomFab(
                    badge = unreadWhileScrolled,
                    writing = assistantLive != null || answerReveal.busy,
                    colors = colors,
                    onClick = {
                        scope.launch {
                            ScrollCoordinator.execute(listState, ScrollIntent.ToBottom)
                            unreadWhileScrolled = 0
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp + underBarDp + navBarDp),
                )
            }

 // --- Floating sticky date badge ---
            // Shows the day of the TOPMOST visible message. reverseLayout=true → the
            // visually-top item is the LARGEST visible index. Appears while scrolling,
            // fades out shortly after the scroll settles. NOT a LazyColumn item → it
            // never scrolls with content and never duplicates an in-list divider.
            val topDateLabel by remember(rendered, typingLead) {
                derivedStateOf {
                    val topIdx = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    topIdx?.let { rendered.getOrNull(it - typingLead)?.timestamp }?.let { MessageDayClassifier.label(it, dayLabels) }.orEmpty()
                }
            }
            var dateBadgeVisible by remember { mutableStateOf(false) }
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) {
                    dateBadgeVisible = true
                } else {
                    kotlinx.coroutines.delay(1000)
                    dateBadgeVisible = false
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = dateBadgeVisible && topDateLabel.isNotBlank(),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = chatTopPad + 8.dp),
            ) {
                FloatingDateBadge(label = topDateLabel, colors = colors)
            }
                if (roundRec.recording) {
                    com.alphamessenger.ui.chat.round.RoundRecordOverlay(roundRec, colors)
                }
            }  // end Box wrapper around LazyColumn

            // Bottom transient-strip layers in ONE place: peer/system presence
            // (typing…) above local-user context (reply / edit / forward). The
            // composer follows as the fixed input anchor (it shares vertical
            // weight with the attach panel, so it stays a Column child).
            // ONE frosted glass under everything at the bottom — the strips, the input
            // bar, the recording bar — and on down under the navigation bar: no seams.
            // Opaque underneath, so nothing dark flashes through while screens change.
            Column(
                Modifier.fillMaxWidth().chatBottomGlass(chatHaze, colors, navBarDp),
            ) {
            Column(Modifier.fillMaxWidth().onSizeChanged { aboveBarHpx = it.height }) {
            BottomArea(
                // "typing…" is drawn over the list, above this panel (see the list box).
                presence = {},
                // Reply / edit / forward / link preview live INSIDE the input field.
                context = {},
            )
            }


            // --- BottomSurface: the SINGLE permanent bottom-area owner ---
            // Always in composition. Always renders: Divider + InputBar (never
            // destroyed, hidden only in READ_ONLY) + BottomPanel (ONE slot whose
            // height = bottomPanelHeight and whose content follows panelMode).
            // No mutually-exclusive if/else if around InputBar; no second spacer;
            // no compensators. panelMode is the only driver.
            //
            // V2 switch: when USE_BOTTOM_SURFACE_V2 is true, the bottom surface
            // is owned by the new independent V2 system (BottomSurfaceV2 +
            // InputBarV2 + BottomPanelV2 + BottomSurfaceState). The old code
            // below stays intact and is reached by flipping the flag to false.
            if (USE_BOTTOM_SURFACE_V2) {
                val mentionCandidates by viewModel.mentionCandidates.collectAsState()
                LaunchedEffect(state.isGroup) { if (state.isGroup) viewModel.loadMentionCandidates() }
                BottomSurfaceV2(
                    state = v2State,
                    colors = colors,
                    onGlass = true,
                    panelModifier = Modifier.chatPanelGlass(chatHaze, colors, navBarDp, v2State),
                    // Measured with the pill's margins: the list and the wallpaper run on under all of it.
                    barModifier = Modifier.onSizeChanged { inputBarHpx = it.height }.chatInputPill(chatHaze, colors),
                    lockedRecording = state.isRecordingLocked,
                    lockedRecordingBar = {
                        com.alphamessenger.ui.chat.v2.RecordingPanelV2(
                            onGlass = true,
                            seconds = state.recordingDuration,
                            paused = state.recordingPaused,
                            ticks = viewModel.recorderTicks,
                            colors = colors,
                            onPauseResume = {
                                if (state.recordingPaused) viewModel.resumeRecording() else viewModel.pauseRecording()
                            },
                            onCancel = { viewModel.cancelRecording() },
                            onSend = { schedulable { viewModel.stopRecording() } },
                        )
                    },
                    // Above the input row: the recording bars cover the row, never this.
                    header = {
                    // Edit banner (if editing) — prefill input, override send.
                    editingMsg?.let { em ->
                        ReplyPreview(
                            senderName = "",
                            title = if (em.isMedia) stringResource(R.string.chat_edit_caption_title) else stringResource(R.string.chat_editing_title),
                            text = if (em.isMedia) (em.mediaCaption ?: stringResource(R.string.preview_add_caption)) else em.text,
                            onClose = { editingMsg = null; viewModel.updateInput("") },
                            colors = colors,
                            message = em.takeIf { it.isMedia || it.albumItems != null },
                        )
                    }
                    // Forward preview (staged messages to forward here).
                    if (state.pendingForward.isNotEmpty()) {
                        val first = state.pendingForward.first()
                        val label = if (state.pendingForward.size > 1)
                            pluralStringResource(R.plurals.chat_messages_count, state.pendingForward.size, state.pendingForward.size)
                        else replyPreviewLabel(first)
                        ForwardPreview(text = label, onClose = { viewModel.cancelForward() }, colors = colors)
                    }
 // Link preview of the URL being typed.
                    if (editingMsg == null) {
                        com.alphamessenger.ui.chat.preview.ComposerLinkPreview(
                            data = state.composerPreview,
                            loading = state.composerPreviewLoading,
                            colors = colors,
                            onClose = { viewModel.dismissComposerPreview() },
                        )
                    }
                    // A lone emoji typed: the stickers that carry it, one tap sends instead.
                    if (editingMsg == null && state.pendingForward.isEmpty()) {
                        com.alphamessenger.ui.sticker.StickerSuggestions(state.inputText) { pid, s ->
                            viewModel.sendSticker(pid, s)
                            viewModel.updateInput("")
                        }
                    }
                    // Reply preview (if any).
                    if (editingMsg == null) state.replyTo?.let { reply ->
                        ReplyPreview(
                            senderName = reply.senderName ?: stringResource(R.string.notif_you),
                            text = state.replyQuote?.let { "❝ $it" } ?: replyPreviewLabel(reply),
                            onClose = { viewModel.setReplyTo(null) },
                            colors = colors,
                            message = reply,
                        )
                    }
                    },
                    inputBar = {
                        Box {
                        InputBarV2(
                            onGlass = true,
                            onStop = if (isAssistant && assistantLive != null) ({ viewModel.assistant.stop(chatId) }) else null,
                            mentionCandidates = mentionCandidates,
                            // Messages waiting for their time: calendar in the field.
                            onOpenScheduled = if (!scheduledMode && heldHere.isNotEmpty()) ({ onOpenScheduled(chatId) }) else null,
                            state = v2State,
                            value = state.inputText,
                            onValueChange = viewModel::updateInput,
                            charLimit = if (editingMsg?.isMedia == true) MAX_MESSAGE_CHARS else null,
                            isSending = state.isSending,
                            // While editing, Send only saves: no hold menu.
                            onSendLongPress = if (editingMsg != null) null else ({ if (state.inputText.isNotBlank()) pendingScheduled = { sendComposerText() } }),
                            onSendSilent = if (editingMsg != null) null else ({
                                if (state.inputText.isNotBlank() && state.pendingForward.isEmpty()) {
                                    schedulable { viewModel.armSilent(); sendComposerText() }
                                }
                            }),
                            onSend = {
                                val em = editingMsg
                                when {
                                    em != null -> {
                                        viewModel.editMessage(em.id, state.inputText)
                                        editingMsg = null
                                        viewModel.updateInput("")
                                    }
                                    state.pendingForward.isNotEmpty() -> {
                                        viewModel.sendForward(state.inputText)
                                    }
                                    else -> schedulable { sendComposerText() }
                                }
                            },
                            onAttach = {
                                v2State.enterAttach()
                                viewModel.setShowAttachPanel(true)
                            },
                            emojiOpen = v2PanelMode == PanelModeV2.EMOJI,
                            onEmoji = {
                                if (v2PanelMode == PanelModeV2.EMOJI) v2State.enterKeyboard()
                                else v2State.enterEmoji()
                            },
                            onInputFocused = {
                                if (v2State.panelMode != PanelModeV2.KEYBOARD) v2State.enterKeyboard()
                            },
                            onCamera = onCameraTap,
                            forceSend = state.pendingForward.isNotEmpty(),
                            colors = colors,
                            isRecording = state.isRecording,
                            recordingSeconds = state.recordingDuration,
                            onRoundStart = { startRound(false) },
                            onRoundEnd = { end ->
                                when (end) {
                                    com.alphamessenger.ui.chat.v2.RoundEnd.SEND -> roundRec.finish(send = true)
                                    com.alphamessenger.ui.chat.v2.RoundEnd.CANCEL -> roundRec.finish(send = false)
                                    com.alphamessenger.ui.chat.v2.RoundEnd.LOCK -> roundRec.locked = true
                                }
                            },
                            onStartRecord = {
                                // Capture keyboard-up AT PRESS START, not at release.
                                v2State.recordingKeepsKb = v2State.imeContentDp > 120.dp
                                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        chatCtx, android.Manifest.permission.RECORD_AUDIO,
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) viewModel.startRecording()
                                else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                            },
                            onFinishRecord = {
                                if (roundRec.recording) roundRec.finish(send = true)
                                else schedulable { viewModel.stopRecording() }
                            },
                            onCancelRecord = { if (roundRec.recording) roundRec.finish(send = false) else viewModel.cancelRecording() },
                            onTapLockRecord = {
                                if (roundRec.recording) { roundRec.locked = true; return@InputBarV2 }
                                // recordingKeepsKb was captured at ACTION_DOWN above.
                                viewModel.lockRecording()
                            },
                            // Read-only handled INSIDE the InputBar (per spec):
                            // enabled=false + dimmed + send area → ReadOnlyStrip.
                            readOnly = isReadOnly || chatSearchQuery != null,
                            cooldownSeconds = state.discussion?.status
                                ?.takeIf { it.reason == "slow_mode" }?.value?.coerceAtLeast(0) ?: 0L,
                            readOnlyContent = if (chatSearchQuery != null) {
                                {
                                    ChatSearchSteps(
                                        position = searchPos,
                                        count = searchHits.size,
                                        searching = !searchLoaded && !chatSearchQuery.isNullOrBlank(),
                                        colors = colors,
                                        onOlder = { if (searchPos + 1 < searchHits.size) { searchPos++; jumpRequest = searchHits[searchPos] } },
                                        onNewer = { if (searchPos > 0) { searchPos--; jumpRequest = searchHits[searchPos] } },
                                        onCalendar = { pickSearchDate = true },
                                        onList = { if (searchFound.isNotEmpty()) searchListOpen = true },
                                    )
                                }
                            } else if (secretWaiting && state.secretState == "invited") {
                                {
                                    com.alphamessenger.ui.secret.SecretAnswerBar(
                                        onAccept = { viewModel.acceptSecretOffer(state.secretInvite) },
                                        onDecline = { viewModel.declineSecretOffer(state.secretInvite) { onBack() } },
                                    )
                                }
                            } else if (secretWaiting) {
                                { Spacer(Modifier.fillMaxWidth().navigationBarsPadding()) }
                            } else if (state.isRequest) {
                                {
                                    ChatRequestBar(
                                        colors = colors,
                                        onBlock = { viewModel.declineRequest(block = true, onDone = onBack) },
                                        onDelete = { viewModel.declineRequest(block = false, onDone = onBack) },
                                        onAccept = { viewModel.acceptRequest() },
                                    )
                                }
                            } else if (isReadOnly && state.discussion?.locked == true && !state.removedFromGroup) {
                                { state.discussion?.let { com.alphamessenger.ui.channel.DiscussionLockStrip(colors, it.status) } }
                            } else if (state.channel != null && !state.chromeKnown) {
                                // My role is not known yet: no subscriber bar that turns into a composer.
                                { Spacer(Modifier.fillMaxWidth().navigationBarsPadding()) }
                            } else if (isReadOnly && state.channel != null && !state.isAdmin && !state.removedFromGroup) {
                                {
                                    com.alphamessenger.ui.channel.ChannelSubscriberBar(
                                        muted = state.isMuted,
                                        colors = colors,
                                        onToggleMute = { viewModel.toggleMute() },
                                        compact = true,
                                    )
                                }
                            } else if (isReadOnly) {
                                {
                                    com.alphamessenger.ui.chat.v2.ReadOnlyStripV2(
                                        colors = colors,
                                        model = com.alphamessenger.ui.chat.v2.ReadOnlyModel(
                                            peerBlocked = state.peerBlocked,
                                            removedFromGroup = state.removedFromGroup,
                                            chatClosedUntilMe = state.chatClosedUntilMe,
                                            writeRestricted = state.writeRestricted,
                                            mutedUntilMe = state.mutedUntilMe,
                                            onUnblock = { viewModel.unblockPeer() },
                                        ),
                                    )
                                }
                            } else null,
                        )
                        }
                    },
                    bottomPanel = {
                        BottomPanelV2(
                            state = v2State,
                            colors = colors,
                            onEmojiPick = { e -> viewModel.updateInput(state.inputText + e) },
                            onGifSend = { g -> viewModel.sendGif(g) },
                            onStickerSend = { pid, s -> viewModel.sendSticker(pid, s) },
                            repository = viewModel.repository,
                            onEmojiBackspace = {
                                val text = state.inputText
                                if (text.isNotEmpty()) {
                                    val cpCount = text.codePointCount(0, text.length)
                                    if (cpCount > 0) {
                                        val newEnd = text.offsetByCodePoints(0, cpCount - 1)
                                        viewModel.updateInput(text.substring(0, newEnd))
                                    }
                                }
                            },
                        )
                    },
                )
            } else {
            BottomSurface {
                HorizontalDivider(thickness = 0.5.dp, color = colors.alphaBorder)

                // InputBar is ALWAYS in composition. Hidden (0-height clipped
                // wrapper, NOT removed) in READ_ONLY and ATTACH so FocusRequester /
                // BasicTextField / ScrollState survive across mode transitions.
                val inputBarHidden = panelMode == PanelMode.READ_ONLY || panelMode == PanelMode.ATTACH
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (inputBarHidden) Modifier.height(0.dp).clipToBounds() else Modifier),
                ) {
                    android.os.Trace.beginSection("CT_InputBar"); val __cib = System.nanoTime()
                    InputBar(
                        value = state.inputText,
                        onValueChange = viewModel::updateInput,
                        isSending = state.isSending,
                        onSend = {
                            val em = editingMsg
                            when {
                                em != null -> {
                                    viewModel.editMessage(em.id, state.inputText)
                                    editingMsg = null
                                    viewModel.updateInput("")
                                }
                                state.pendingForward.isNotEmpty() -> {
                                    // Forward staged message(s) + optional quoting comment.
                                    viewModel.sendForward(state.inputText)
                                }
                                else -> sendComposerText()
                            }
                        },
                        onAttach = {
                            // Local part → NONE; ViewModel truth drives ATTACH. The
                            // single actuation effect hides the IME. Open at the
                            // half detent; the user drags from there.
                            inputMode = PanelMode.NONE
                            panelHeight = screenHeightDp * 0.55f
                            viewModel.setShowAttachPanel(true)
                        },
                        emojiOpen = panelMode == PanelMode.EMOJI,
                        inputFocusRequester = inputFocusRequester,
                        onInputFocused = {
                            // Tapping the field → keyboard (from NONE or EMOJI).
                            // Reset the IME latches IN THE SAME FRAME (see note at
                            // the latch declarations) so the first KEYBOARD frame
                            // holds the measured floor instead of collapsing to 0.
                            imeCollapsing = false
                            imeUpThisSession = false
                            inputMode = PanelMode.KEYBOARD
                        },
                        onEmoji = {
 // other messengers swap — set the mode only; actuation is centralized.
                            // Seed the panel height from the live keyboard so the emoji
                            // grid opens at the same height the keyboard occupied.
                            if (panelMode == PanelMode.EMOJI) {
                                imeCollapsing = false
                                imeUpThisSession = false
                                inputMode = PanelMode.KEYBOARD
                            } else {
                                if (imeContentDp > 0.dp) panelHeight = imeContentDp
                                inputMode = PanelMode.EMOJI
                            }
                        },
                        onCamera = onCameraTap,
                        forceSend = state.pendingForward.isNotEmpty(),
                        colors = colors,
                        isRecording = state.isRecording,
                        recordingSeconds = state.recordingDuration,
                        onStartRecord = {
                            // Capture whether the keyboard is up AT PRESS START,
                            // not at release: the IME may dip during the press and
                            // sampling at release (onTapLockRecord) would latch
                            // false, dropping the reserved keyboard space.
                            recordingKeepsKb = imeContentDp > 120.dp
                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    chatCtx, android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) viewModel.startRecording()
                            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onFinishRecord = { viewModel.stopRecording() },
                        onCancelRecord = { viewModel.cancelRecording() },
                        onTapLockRecord = {
                            // recordingKeepsKb was captured at ACTION_DOWN above.
                            viewModel.lockRecording()
                        },
                    )
                }

                // --- BottomPanel ---
                // ONE fixed-height slot (height = bottomPanelHeight) for EVERY
                // mode, ATTACH included — no weight branch, no second spacer.
                // `displayMode` (not panelMode) picks the content so a closing
                // panel keeps rendering while the slot glides to 0. NO clip here:
                // READ_ONLY / RECORDING overlay the 0-height slot upward.
                Box(modifier = Modifier.fillMaxWidth().height(bottomPanelHeight)) {
                    when (displayMode) {
                        PanelMode.NONE, PanelMode.KEYBOARD -> {
                            // Empty — the keyboard (or nothing) occupies this space.
                        }
                        PanelMode.EMOJI -> {
                            EmojiPanel(
                                modifier = Modifier.fillMaxSize(),
                                colors = colors,
                                onPick = { e -> viewModel.updateInput(state.inputText + e) },
                                onDrag = { deltaPx ->
                                    isPanelDragging = true
                                    val dDp = with(attachDensity) { deltaPx.toDp() }
                                    panelHeight = (panelHeight - dDp).coerceIn(screenHeightDp * 0.15f, screenHeightDp * 0.80f)
                                },
                                onDragRelease = {
                                    isPanelDragging = false
                                    if (panelHeight < screenHeightDp * 0.18f) {
                                        inputMode = PanelMode.NONE
                                        panelHeight = screenHeightDp * 0.42f  // sane height for next open
                                    } else if (panelHeight > screenHeightDp * 0.6f) panelHeight = screenHeightDp * 0.78f
                                },
                            )
                        }
                        PanelMode.ATTACH -> Column(modifier = Modifier.fillMaxSize()) {
                            MediaPickerContent(
                                colors = colors,
                                orderOf = { item -> draftVm.orderOf(item.uri) },
                                onToggle = onGalleryToggle,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                isFullScreen = panelHeight > screenHeightDp * 0.65f,
                                onCamera = { viewModel.setShowAttachPanel(false); onCameraTap() },
                                onFile = { viewModel.setShowAttachPanel(false); filePicker.launch("*/*") },
                                onMusic = { viewModel.setShowAttachPanel(false); audioPicker.launch("audio/*") },
                                onSecretNote = {
                                    viewModel.setShowAttachPanel(false)
                                    showSecretNoteDialog = true
                                },
                                onLocation = {
                                    viewModel.setShowAttachPanel(false)
                                    onLocationTap()
                                },
                                onClose = {
                                    if (draft.size > 0) pickerDiscard = true
                                    else viewModel.setShowAttachPanel(false)
                                },
                                onDrag = { deltaPx ->
                                    isPanelDragging = true
                                    val dDp = with(attachDensity) { deltaPx.toDp() }
                                    panelHeight = (panelHeight - dDp).coerceIn(screenHeightDp * 0.20f, screenHeightDp * 0.85f)
                                },
                                onDragRelease = {
                                    isPanelDragging = false
                                    if (panelHeight < screenHeightDp * 0.25f) {
                                        // Dragged low → close; with a pending selection
                                        // snap back to compact instead of losing it.
                                        if (draft.size > 0) panelHeight = screenHeightDp * 0.35f
                                        else viewModel.setShowAttachPanel(false)
                                    } else {
                                        panelHeight = listOf(0.35f, 0.55f, 0.80f)
                                            .map { screenHeightDp * it }
                                            .minByOrNull { d -> kotlin.math.abs((d - panelHeight).value) }!!
                                    }
                                },
                            )
                            if (draft.size > 0) {
                                PickerCaptionBar(
                                    colors = colors,
                                    caption = pickerCaption,
                                    count = draft.size,
                                    focusRequester = pickerCaptionFocus,
                                    onCaptionChange = { pickerCaption = it },
                                    // No height jump on focus: the slot target already
                                    // adds the IME share, so the panel rides up with
                                    // the keyboard and settles back when it closes.
                                    onFocused = {},
                                    onSend = {
                                        draft.setCaption(pickerCaption.text.trim().takeIf { it.isNotEmpty() })
                                        viewModel.setShowAttachPanel(false)
                                        previewOpen = true
                                    },
                                )
                            }
                            // Keyboard spacer: lifts the caption bar above the IME —
                            // its share is already included in bottomPanelHeight.
                            Box(modifier = Modifier.fillMaxWidth().height(imeContentDp))
                        }
                        PanelMode.RECORDING -> {
                            VoiceRecordPanel(
                                seconds = state.recordingDuration,
                                paused = state.recordingPaused,
                                ticks = viewModel.recorderTicks,
                                colors = colors,
                                keepKeyboard = state.isRecordingLocked && recordingKeepsKb,
                                onPauseResume = {
                                    if (state.recordingPaused) viewModel.resumeRecording() else viewModel.pauseRecording()
                                },
                                onCancel = { viewModel.cancelRecording() },
                                onSend = { viewModel.stopRecording() },
                            )
                        }
                        PanelMode.READ_ONLY -> {
                            // Read-only status strip. Wrap-content (the slot's
                            // height is 0.dp, so we overlay the strip with its own
                            // height via wrapContentHeight).
                            Column(
                                modifier = Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Bottom)
                                    .background(colors.bgSecondary),
                            ) {
                                when {
                                    state.peerBlocked -> {
                                        Column(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Text(
                                                stringResource(R.string.chat_blocked_notice),
                                                color = colors.textMuted, fontSize = 14.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                stringResource(R.string.chat_unblock),
                                                color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { viewModel.unblockPeer() }
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                            )
                                        }
                                    }
                                    state.removedFromGroup -> {
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 18.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                stringResource(R.string.chat_group_unavailable),
                                                color = colors.textMuted, fontSize = 14.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                        }
                                    }
                                    state.channel != null && !state.isAdmin -> {
                                        com.alphamessenger.ui.channel.ChannelSubscriberBar(
                                            muted = state.isMuted,
                                            colors = colors,
                                            onToggleMute = { viewModel.toggleMute() },
                                        )
                                    }
                                    state.chatClosedUntilMe > com.alphamessenger.util.TrustedTime.nowSeconds() -> {
                                        val until = remember(state.chatClosedUntilMe) {
                                            java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                                                .format(java.util.Date(state.chatClosedUntilMe * 1000))
                                        }
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 18.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                stringResource(R.string.chat_closed_until, until),
                                                color = colors.textMuted, fontSize = 14.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                        }
                                    }
                                    state.writeRestricted -> {
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 18.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                stringResource(R.string.chat_admins_only),
                                                color = colors.textMuted, fontSize = 14.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                        }
                                    }
                                    state.mutedUntilMe > com.alphamessenger.util.TrustedTime.nowSeconds() -> {
                                        val until = remember(state.mutedUntilMe) {
                                            java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                                                .format(java.util.Date(state.mutedUntilMe * 1000))
                                        }
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 18.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                stringResource(R.string.chat_muted_until, until),
                                                color = colors.textMuted, fontSize = 14.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } // ── end BottomSurface
            } // ── end else (V2 switch)
            }
        }
        } // end wallpaper Box
    }

    // Back: picker open → discard-if-selected / close; else emoji → none.
    // Flag-aware: the emoji check must read the ACTIVE architecture's mode,
    // otherwise Back with the V2 emoji panel open exits the chat.
    val emojiOpenForBack =
        if (USE_BOTTOM_SURFACE_V2) v2PanelMode == PanelModeV2.EMOJI else panelMode == PanelMode.EMOJI
    androidx.activity.compose.BackHandler(enabled = state.showAttachPanel || emojiOpenForBack) {
        when {
            state.showAttachPanel -> {
                if (draft.size > 0) pickerDiscard = true
                else viewModel.setShowAttachPanel(false)
            }
            USE_BOTTOM_SURFACE_V2 -> v2State.dismiss()
            else -> inputMode = PanelMode.NONE
        }
    }

    if (pickerDiscard) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickerDiscard = false },
            title = { Text(stringResource(R.string.chat_attach_discard_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.chat_attach_discard_text), color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pickerDiscard = false
                    draftVm.discard()
                    viewModel.setShowAttachPanel(false)
                }) { Text(stringResource(R.string.chat_attach_discard_reset), color = colors.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pickerDiscard = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
            containerColor = colors.bgSecondary,
        )
    }

    state.joinDialog?.let { (dlgTitle, dlgText) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissJoinDialog() },
            title = { Text(dlgTitle, color = colors.textPrimary) },
            text = { Text(dlgText, color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissJoinDialog() }) {
                    Text(stringResource(R.string.common_ok), color = colors.accent)
                }
            },
            containerColor = colors.bgSecondary,
        )
    }

    if (showAddMemberSheet) {
        AddMemberSheet(
            isLoading = state.isAddingMember,
            colors = colors,
            onDismiss = { if (!state.isAddingMember) showAddMemberSheet = false },
            onConfirm = { npub -> viewModel.addMemberByNpub(chatId, npub) },
        )
    }

    // The attach sheet, over the chat (the chat stays where it is beneath it).
    // Picked in the sheet with a caption → sent right away; the editor is one tap on a
    // picture away, not a forced step.
    val sendFromSheet: () -> Unit = {
        val caption = pickerCaption.text.trim().takeIf { it.isNotEmpty() }
        draft.setCaption(caption)
        viewModel.setShowAttachPanel(false)
        val send = { sendDraft(caption) }
        val replacing = replaceHeld
        if (replacing != null) {
            replaceHeld = null
            viewModel.cancelHeld(replacing.first)
            viewModel.armSchedule(replacing.second)
            send()
        } else schedulable(send)
    }
    val closeAttach = {
        if (draft.size > 0) pickerDiscard = true
        else viewModel.setShowAttachPanel(false)
    }
    com.alphamessenger.ui.chat.v2.AttachSheet(
        visible = state.showAttachPanel,
        state = v2State,
        colors = colors,
        caption = pickerCaption,
        onCaptionChange = { pickerCaption = it },
        onDismiss = closeAttach,
    ) { sheetModifier, captionKeys ->
        com.alphamessenger.ui.chat.v2.AttachPanelV2(
            state = v2State,
            modifier = sheetModifier,
            colors = colors,
            selectedCount = draft.size,
            orderOf = { item -> draftVm.orderOf(item.uri) },
            onToggle = onGalleryToggle,
            // Tap on a picture: our viewer and editor open on it and page through the whole
            // gallery; its circle picks. Back returns to the sheet with the set as it is.
            onOpenItem = { item, all ->
                previewGallery = all to all.indexOf(item).coerceAtLeast(0)
                previewOpen = true
            },
            caption = pickerCaption,
            captionKeys = captionKeys,
            onCaptionChange = { pickerCaption = it },
            onCamera = { viewModel.setShowAttachPanel(false); onCameraTap() },
            onFile = { viewModel.setShowAttachPanel(false); filePicker.launch("*/*") },
            onMusic = { viewModel.setShowAttachPanel(false); audioPicker.launch("audio/*") },
            onSecretNote = {
                viewModel.setShowAttachPanel(false)
                showSecretNoteDialog = true
            },
            onLocation = {
                viewModel.setShowAttachPanel(false)
                onLocationTap()
            },
            onClose = closeAttach,
            onSend = sendFromSheet,
            // The originals go as files: no compression, no edits.
            onSendUncompressed = {
                draft.items.toList().forEach { d -> draft.update(d.id) { it.copy(sendAsFile = true) } }
                sendFromSheet()
            },
            onSendSilent = {
                val caption = pickerCaption.text.trim().takeIf { it.isNotEmpty() }
                draft.setCaption(caption)
                viewModel.setShowAttachPanel(false)
                schedulable { viewModel.armSilent(); sendDraft(caption) }
            },
            // "Send later": the same time sheet, then the normal send of the set.
            onSendLongPress = {
                pendingScheduled = {
                    val caption = pickerCaption.text.trim().takeIf { it.isNotEmpty() }
                    draft.setCaption(caption)
                    viewModel.setShowAttachPanel(false)
                    sendDraft(caption)
                }
            },
            timer = glanceTimer.takeIf { !shareMode },
        )
    }

    // Pre-send review of the set — a single screen in the app window. It has
    // no state of its own: it reads and edits the same set.
    if (sharePreparing || (previewOpen && (draft.size > 0 || previewGallery != null))) {
        com.alphamessenger.ui.media.preview.MediaPreviewScreen(
            store = draft,
            colors = colors,
            preparing = sharePreparing,
            // More than one recipient — show it right in the header: the user
            // must see where the set will go before pressing "send".
            subtitle = (shareTargets.size + 1).takeIf { shareMode && it > 1 }?.let { pluralStringResource(R.plurals.chat_recipients, it, it) },
            onClose = {
                previewOpen = false
                sharePreparing = false
                when {
                    // Back inside Share is a step back in the flow, not an exit:
                    // return to recipient selection with the same set.
                    shareMode -> {
                        shareMode = false
                        shareTargets = emptyList()
                        draftVm.discard()
                        com.alphamessenger.ui.share.PendingShare.rearm()
                    }
                    // Returned to the open attachment panel — the set stays; left
                    // the review entirely — the set and our temp files are removed.
                    !state.showAttachPanel -> draftVm.discard()
                }
            },
            onSend = { caption ->
                val send = {
                    previewOpen = false
                    sendDraft(caption)
                }
                val replacing = replaceHeld
                if (replacing != null) {
                    replaceHeld = null
                    viewModel.cancelHeld(replacing.first)
                    viewModel.armSchedule(replacing.second)
                    send()
                } else schedulable(send)
            },
            // Long-press Send in the preview: the same time sheet, then the normal send.
            onArmSilent = if (shareMode || scheduledMode) null else ({ viewModel.armSilent() }),
            onScheduleSend = if (shareMode) null else ({ caption ->
                pendingScheduled = {
                    previewOpen = false
                    sendDraft(caption)
                }
            }),
            // No gallery/camera buttons in the caption field: the set is picked
            // in the attach panel; the field is for the text.
            timer = glanceTimer.takeIf { !shareMode },
            // Back to the attachment panel keeps the set; back from Share returns to its targets.
            closeDiscards = !shareMode && !state.showAttachPanel,
            gallery = previewGallery?.first,
            galleryStart = previewGallery?.second ?: 0,
            onGalleryToggle = if (previewGallery != null) onGalleryToggle else null,
        )
    }

    // In-app camera studio (full-screen). Captures photo/video with a live
    // preview, lets the user stack several shots, edit, then send as a batch.
    // System Back must close the camera overlay — NOT fall through to the
    // NavController (which would pop the whole chat and land on the home list).
    androidx.activity.compose.BackHandler(enabled = showCameraStudio) {
        showCameraStudio = false
        // Left the camera — return to where the set remains.
        previewOpen = draft.size > 0
    }
    if (showCameraStudio) {
        CameraStudioScreen(
            onClose = {
                showCameraStudio = false
                previewOpen = draft.size > 0
            },
            // A shot is NOT sent from the camera: it joins the same set and is
            // shown immediately in the shared review — caption, strip, delete and
            // send all live there. One composer for every source: shoot → (edit)
            // → send.
            // A shot is NOT sent from the camera: it joins the same set (with the
            // strip pictures picked beside it) and is shown in the shared review.
            onCaptured = { items ->
                showCameraStudio = false
                ingestStudio(items)
            },
            onPickRecent = { items ->
                showCameraStudio = false
                ingestStudio(items)
            },
            onGallery = {
                showCameraStudio = false
                viewModel.setShowAttachPanel(true)
            },
            // Not inside a set being prepared: a video message goes alone, at once.
            onVideoNote = if (draft.size > 0) null else ({
                showCameraStudio = false
                startRound(true)
            }),
        )
    }

    if (showLocationOffDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLocationOffDialog = false },
            title = { Text(stringResource(R.string.chat_location_off_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.chat_location_off_text), color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showLocationOffDialog = false
                    runCatching {
                        locationSettings.launch(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }) { Text(stringResource(R.string.chat_open_settings), color = colors.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLocationOffDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
            containerColor = colors.bgSecondary,
        )
    }

    // Map picker — choose any point on the map and send it.
    if (showLocationPicker) {
        LocationPickerScreen(
            startLat = pickerStartLat,
            startLng = pickerStartLng,
            colors = colors,
            onDismiss = { showLocationPicker = false },
            onPick = { lat, lng, name, address ->
                showLocationPicker = false
                schedulable { viewModel.sendLocation(lat, lng, name, address) }
            },
        )
    }

    // Location confirmation sheet (mini-map + Send) before sending.
    pendingLocation?.let { loc ->
        LocationConfirmSheet(
            data = loc,
            colors = colors,
            onDismiss = { pendingLocation = null },
            onSend = {
                schedulable { viewModel.sendLocation(loc.lat, loc.lng, loc.name, loc.address) }
                pendingLocation = null
            },
        )
    }

    // Fullscreen internal viewer for a tapped photo/video (incl. image/video
 // sent "as file"). classic: opens the album viewer over ALL photos/
    // videos in the chat, starting at the tapped one — swipe left/right between
    // them, download button, pinch-zoom, video streaming. Falls back to the
    // single pinch-zoom / ExoPlayer viewer if the tapped file can't be located
    // in the gallery.
    // THE single entry into the viewer (Media Framework). The bubble hands
    // over only a path — here it becomes the chat-wide MediaItem list (Album
    // Framework: opening any item yields the whole set for swiping + the
    // thumbnail strip). MediaViewer knows nothing of MessageUi, Bubble or
    // ChatScreen.
    fullscreenMediaPath?.let { path ->
        // Flat list of the chat's media messages (album items expanded),
        // oldest→newest — the swipe order matches the message feed.
        // A whisper or a view-once is opened only from its own row, under its own rules:
        // paging through the chat's media must never reach it.
        // A photo folded into an album already comes with the album: listing its own row too repeats it.
        val galleryMsgs = remember(state.messages, state.whispers) {
            state.messages.filterNot { it.hiddenInAlbum || it.isDeleted || it.isOneTime || it.id in state.whispers }.flatMap { m ->
                // The album caption belongs to the album: every item shows it.
                m.albumItems?.map { it.copy(mediaCaption = it.mediaCaption ?: m.mediaCaption) } ?: listOf(m)
            }
        }
        // A place opens its own map, not a page here: paging over it would show black.
        val galleryItems = remember(galleryMsgs) {
            galleryMsgs.mapNotNull { it.toMediaItem() }.filter { it.kind != com.alphamessenger.ui.chat.media.MediaItem.Kind.LOCATION }
        }
        // MediaItem.id == MessageUi.id → reverse mapping for Overlay actions.
        val msgById = remember(galleryMsgs) { galleryMsgs.associateBy { it.id } }
        val startIndex = remember(path, fullscreenMediaMessageId, galleryItems) {
            // First the EXACT message that was tapped. A forwarded copy shares
            // the original's file path, so a path lookup would land on the first
            // (oldest) message with the same file.
            val byMessage = fullscreenMediaMessageId?.let { id ->
                galleryItems.indexOfFirst { it.id == id }
            }?.takeIf { it >= 0 }
            byMessage ?: run {
                val base = path.substringAfterLast('/').substringAfterLast('\\')
                galleryItems.indexOfFirst { item ->
                    item.localPath == path || run {
                        val cached = viewModel.repository.cachedMediaPathIfPresent(item.refJson)
                        cached == path || (cached != null && base.isNotEmpty() &&
                            cached.substringAfterLast('/').substringAfterLast('\\') == base)
                    }
                }
            }
        }
        // The SET defines the SOURCE, not the viewer. We page through the
        // WHOLE chat media feed: swiping left/right moves to the neighbouring
 // photo or video instead of hitting the bounds of a
        // single message.
        val opened = if (startIndex >= 0) galleryItems.getOrNull(startIndex) else null
        val ownerMsg = opened?.let { msgById[it.id] }
        val albumSiblings = ownerMsg?.id?.substringBefore("#al")?.let { headId ->
            state.messages.firstOrNull { it.id == headId }?.albumItems
        }
        // A stable set: recreating it on every recomposition cut the animation
        // short (the Animatable was rebuilt, the hero lived ~120ms instead of 300).
        // Fixed for the whole open: a reaction or reply reloads the message list,
        // and a rebuilt set restarted the viewer (black screen, video still playing).
        val collection = remember(path, fullscreenMediaMessageId) { when {
            // Item not in the gallery (an image-as-file, not-yet-decrypted
            // media). Find the message owning the path and take ITS id — else the
            // Hero cannot find the bubble bounds and the transition degrades to
            // a fade.
            opened == null -> {
                val ownerId = galleryMsgs.firstOrNull { m ->
                    m.localMediaPath == path ||
                        m.mediaRefJson?.let { r -> viewModel.repository.cachedMediaPathIfPresent(r) } == path
                }?.id
                com.alphamessenger.ui.chat.media.MediaCollection.single(
                    AlphaMediaItem(
                        id = ownerId ?: path,
                        kind = AlphaMediaItem.kindOf("file", path),
                        localPath = path,
                    ),
                )
            }
            // The whole chat feed, starting at the opened item. Album items are
            // already expanded in galleryItems, so an album pages like any other
            // item — and continues into the neighbouring messages.
            else -> com.alphamessenger.ui.chat.media.MediaCollection.chat(galleryItems, startIndex)
        } }
        // The viewer covers the chat: what arrives meanwhile is read on return.
        DisposableEffect(Unit) {
            val cover = Any()
            viewModel.setScreenCovered(cover, true)
            onDispose { viewModel.setScreenCovered(cover, false) }
        }
        MediaViewer(
            collection = collection,
            downloadOf = { item -> item.refJson?.let { viewModel.transferUi.flowFor(com.alphamessenger.transfer.TransferKey.download(it)) } },
            source = com.alphamessenger.ui.chat.media.MediaSource(
                messageId = collection.items.getOrNull(collection.startIndex)?.id,
            ),
            onDismiss = {
                fullscreenMediaPath = null
                fullscreenMediaMessageId = null
                // The viewer may have fetched the file itself (▶ on an
                // undownloaded video) — the transfer engine does not know, and the
                // bubble keeps a "download" badge. Ask for a re-check: consider()
                // first asks the repository what is on disk, and all its guards
                // (active/completed/cancelled transfer) stay in force — this does
                // not spawn duplicate downloads.
                viewModel.transferUi.let { ui ->
                    galleryMsgs.forEach { m ->
                        val ref = m.mediaRefJson ?: return@forEach
                        val key = com.alphamessenger.transfer.TransferKey.download(ref)
                        val before = ui.snapshots.value[key.value]?.state
                        if (before is com.alphamessenger.transfer.TransferState.CacheEvicted) {
                            // The user once deleted this from the cache and the
                            // eviction marker pins the state — consider() must
                            // respect it. But the bytes are back, fetched by the
                            // viewer itself. Same path as tapping ↓: start() clears
                            // the marker and re-asks the disk, and with the file
                            // already there, nothing is downloaded.
                            ui.dispatch(key, com.alphamessenger.transfer.TransferAction.Start, m.id)
                        } else {
                            ui.consider(
                                key, m.mediaType, m.mediaFilename, m.mediaSizeBytes ?: 0L,
                                m.isOutgoing, m.localMediaPath, m.id,
                            )
                        }
                    }
                }
            },
            onDownload = { item ->
                msgById[item.id]?.let { viewModel.saveMediaToGallery(listOf(it)) }
            },
            repository = viewModel.repository,
            // The "three dots" menu actions — the same operations as in chat.
            onForward = { item ->
                msgById[item.id]?.let { m ->
                    fullscreenMediaPath = null
                    forwardIds = listOf(m.id)
                }
            },
            onReply = { item ->
                msgById[item.id]?.let { m ->
                    fullscreenMediaPath = null
                    viewModel.setReplyTo(m)
                }
            },
 // Answer / react right from the viewer — the viewer stays open.
            onQuickReply = { item, text ->
                msgById[item.id]?.let { m ->
                    viewModel.sendQuickReply(m, text) { ok ->
                        android.widget.Toast.makeText(
                            chatCtx,
                            chatCtx.getString(if (ok) R.string.viewer_reply_sent else R.string.chat_media_send_failed),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onReact = if (state.reactionPolicy.enabled) ({ item, emoji -> react(item.id.substringBefore("#al"), emoji) }) else null,
            reactionPolicy = state.reactionPolicy,
            myReactions = { item ->
                state.messages.firstOrNull { it.id == item.id.substringBefore("#al") }?.myReactions ?: emptySet()
            },
            reactions = { item ->
                state.messages.firstOrNull { it.id == item.id.substringBefore("#al") }?.reactions ?: emptyMap()
            },
            onShowInChat = { item ->
                msgById[item.id]?.let { m ->
                    fullscreenMediaPath = null
                    // An album child id of the form "<msg>#alN" → jump to the
                    // head message itself.
                    val target = m.id.substringBefore("#al")
                    highlightedId = target
                    mediaScrollToId = target
                }
            },
            onShare = { item ->
                msgById[item.id]?.let { m ->
                    fullscreenMediaPath = null
                    scope.launch {
                        val ref = m.mediaRefJson
                        val path = m.localMediaPath?.takeIf { java.io.File(it).exists() }
                            ?: ref?.let { MediaPathCache[it] }?.takeIf { java.io.File(it).exists() }
                            ?: ref?.let { viewModel.repository.downloadMediaToCache(it) }
                        if (path != null) shareMediaExternally(chatCtx, path, m.mediaFilename, m.mediaType)
                        else android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_file_prepare_failed), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onOpenWith = { item ->
                msgById[item.id]?.let { m ->
                    scope.launch {
                        val ref = m.mediaRefJson
                        val path = m.localMediaPath?.takeIf { java.io.File(it).exists() }
                            ?: ref?.let { MediaPathCache[it] }?.takeIf { java.io.File(it).exists() }
                            ?: ref?.let { viewModel.repository.downloadMediaToCache(it) }
                        if (path != null) openFileExternally(chatCtx, path, m.mediaType, m.mediaFilename)
                        else android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_file_not_downloaded), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    channelVoice?.let { msg ->
        com.alphamessenger.ui.channel.ChannelVoiceSheet(
            name = msg.senderName.orEmpty(),
            avatarPath = msg.senderAvatarPath.orEmpty(),
            anonymous = state.channel?.anon == true || state.discussion?.cfg?.anon == true || (state.channel == null && state.discussion == null),
            colors = colors,
            inGroup = state.channel == null && state.discussion == null,
            onReply = if (readerOnly) null else ({ replyAndType(msg); channelVoice = null }),
            onDismiss = { channelVoice = null },
        )
    }

    // Tapping a group avatar opens the SAME profile as in the dialogs — one
    // screen for both cases (ChatListScreen shows it too).
    avatarPreview?.let { msg ->
        val npub = msg.senderNpub?.takeIf { it.isNotBlank() }
        // Cover and presence live in the ViewModel exactly as long as the sheet
        // is open.
        val member by viewModel.memberProfile.collectAsState()
        DisposableEffect(msg.senderId) {
            viewModel.openMemberProfile(msg.senderId)
            onDispose { viewModel.closeMemberProfile() }
        }
        ProfileScreen(
            displayName = msg.senderName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_member_fallback),
            avatarPath = msg.senderAvatarPath.orEmpty(),
            npub = npub.orEmpty(),
            avatarSeed = msg.senderId,
            colors = colors,
            bannerPath = member?.coverPath.orEmpty(),
            videoPath = member?.videoPath.orEmpty(),
            status = profileStatusOf(member?.status ?: "offline"),
            statusText = member?.statusText.orEmpty(),
            statusColor = member?.statusColor?.takeIf { it != 0 },
            bio = member?.bio.orEmpty(),
            lastSeen = lastSeenLabel(member?.lastSeen ?: 0L),
            // A 1:1 chat with this member may not exist yet — nothing to delete.
            showDeleteChat = false,
            onMessage = { avatarPreview = null; npub?.let(onOpenDirectChat) },
            onVoiceCall = if (member?.canCall == false) null else ({
                avatarPreview = null
                npub?.let { n ->
                    viewModel.openDirectChatWith(n) { id ->
                        com.alphamessenger.calls.CallLauncher.start(chatCtx, id, com.alphamessenger.calls.CallType.AUDIO)
                    }
                }
            }),
            onVideoCall = if (member?.canVideo == false) null else ({
                avatarPreview = null
                npub?.let { n ->
                    viewModel.openDirectChatWith(n) { id ->
                        com.alphamessenger.calls.CallLauncher.start(chatCtx, id, com.alphamessenger.calls.CallType.VIDEO)
                    }
                }
            }),
            onInfo = { avatarPreview = null; npub?.let { onOpenMemberInfo(msg.senderId, it) } },
            onBlock = {
                avatarPreview = null
                viewModel.blockMember(npub.orEmpty(), msg.senderName.orEmpty(), msg.senderAvatarPath.orEmpty())
            },
            onDismiss = { avatarPreview = null },
        )
    }

    // --- Link-action bottom sheet (tap on a link in text) ---
    pendingLinkAction?.let { action ->
        LinkActionSheet(
            action = action,
            colors = colors,
            onDismiss = { pendingLinkAction = null },
            onOpenInvite = { link ->
                pendingLinkAction = null
                viewModel.joinViaLink(link) { m ->
                    android.widget.Toast.makeText(chatCtx, m, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    // --- Who reacted (from a bubble's "+N" / long-press on a pill) ---
    reactionsMsg?.let { m ->
        val live = state.messages.firstOrNull { it.id == m.id } ?: m
        val me by androidx.compose.runtime.produceState<uniffi.alphamessenger.ProfileInfo?>(null) {
            value = runCatching { viewModel.repository.getMyProfile() }.getOrNull()
        }
        ReactionsListSheet(
            message = live,
            ownId = state.ownId,
            myName = me?.displayName.orEmpty(),
            myAvatar = me?.avatarPath.orEmpty(),
            nameOf = { sid ->
                state.peerProfiles[sid]?.displayName?.takeIf { it.isNotBlank() }
                    ?: (if (!state.isGroup) state.peerDisplayName.takeIf { it.isNotBlank() } else null)
                    ?: stringResource(R.string.chat_member_fallback)
            },
            avatarOf = { sid -> state.peerProfiles[sid]?.avatarPath.orEmpty().ifBlank { if (!state.isGroup) state.peerAvatarPath else "" } },
            colors = colors,
            onToggle = { emoji -> react(live.id, emoji) },
            onDismiss = { reactionsMsg = null },
        )
    }

    // --- Scheduled view: replace the content of a waiting message ---
    replaceChooserMsg?.let { m ->
        val start = {
            val id = m.id.substringBefore("#al")
            replaceChooserMsg = null
            replaceHeld = id to (heldHere[id] ?: System.currentTimeMillis())
            // The caption stays: the new content opens in the review with it.
            draft.setCaption(m.mediaCaption ?: m.text.takeIf { it.isNotBlank() })
        }
        ReplaceKindSheet(
            colors = colors,
            onGallery = {
                start()
                replaceVisualPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                    ),
                )
            },
            onFile = { start(); filePicker.launch("*/*") },
            onMusic = { start(); audioPicker.launch("audio/*") },
            onDismiss = { replaceChooserMsg = null },
        )
    }

    // --- Scheduled view: actions on a waiting message ---
    if (scheduledMode) actionSheetMsg?.let { msg ->
        val id = msg.id.substringBefore("#al")
        ScheduledActionsSheet(
            atMillis = heldHere[id] ?: System.currentTimeMillis(),
            canEdit = (msg.text.isNotBlank() || msg.isMedia) && !msg.isStickerLike,
            colors = colors,
            onSendNow = { actionSheetMsg = null; viewModel.sendHeldNow(id) },
            onReschedule = { actionSheetMsg = null; rescheduleId = id },
            onEdit = { actionSheetMsg = null; editingMsg = msg },
            // Replace = the waiting message is dropped and the new media is sent
            // through the usual review, held for the same time.
            // Replace with anything: photo/video, a file (apk, pdf...) or music.
            onReplace = if (msg.isMedia) ({ actionSheetMsg = null; replaceChooserMsg = msg }) else null,
            onDelete = { actionSheetMsg = null; viewModel.cancelHeld(id) },
            onDismiss = { actionSheetMsg = null },
        )
    }

    // --- Single-tap action sheet (reactions bar + actions) ---
    if (!scheduledMode) actionSheetMsg?.let { msg ->
        // A discussion's moderator on someone else's comment: the author's
        // restriction as it is right now, from the core.
        val moderated = state.discussion?.canModerate == true && !msg.isOutgoing && !msg.isDeleted &&
            msg.callLog == null && msg.timerSeconds == null
        val mutedUntil by androidx.compose.runtime.produceState(0L, msg.id, moderated) {
            if (moderated) value = viewModel.commenterMutedUntil(msg)
        }
        MessageActionSheet(
            message = msg,
            colors = colors,
            anchor = menuSpot?.takeIf { it.first == msg.id }?.second?.bounds
                ?: menuRow?.takeIf { it.first == msg.id }?.second
                ?: bubbleBounds[msg.id]?.bounds ?: messageBounds[msg.id],
            hole = menuSpot?.takeIf { it.first == msg.id }?.second?.shape ?: bubbleBounds[msg.id]?.shape,
            haze = chatHaze,
            moderation = if (moderated) ({
                com.alphamessenger.ui.channel.CommentModerationSection(
                    authorName = msg.senderName.orEmpty(),
                    mutedUntil = mutedUntil,
                    colors = colors,
                    onAction = { choice -> viewModel.moderateComment(choice.on(msg)); actionSheetMsg = null },
                )
            }) else null,
            reactionPolicy = state.reactionPolicy,
            startWithPicker = actionSheetPicker,
            readerOnly = readerOnly,
            canDelete = state.deleteScope(msg) != DeleteScope.NONE,
            // Only while the channel has comments on: closing or opening one post.
            onToggleComments = if (state.channel?.comments == true && state.isOwner) ({
                val pid = msg.id.substringBefore("#al")
                viewModel.setPostCommentsOpen(pid, state.channel?.commentsOpen(pid) != true)
                actionSheetMsg = null
            }) else null,
            commentsOpen = state.channel?.commentsOpen(msg.id.substringBefore("#al")) == true,
            onTogglePin = if (state.canPin && !msg.isDeleted && msg.callLog == null && msg.timerSeconds == null)
                ({ viewModel.togglePin(msg.id); actionSheetMsg = null }) else null,
            isPinned = msg.id.substringBefore("#al") in state.pinnedIds,
            onDismiss = { actionSheetMsg = null },
            onSelectText = if ((msg.text.isNotBlank() || !msg.mediaCaption.isNullOrBlank()) && !msg.isDeleted && !msg.isStickerLike) {
                { selectTextOf = msg.text.ifBlank { msg.mediaCaption.orEmpty() }; selectTextMsg = msg; actionSheetMsg = null }
            } else null,
            onReact = { emoji -> react(msg.id, emoji); actionSheetMsg = null },
            onReply = { replyAndType(msg); actionSheetMsg = null },
            onCopy = {
                if (protectedChannel) {
                    android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.channel_protected_toast), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                val cm = chatCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", msg.text.ifBlank { msg.mediaCaption ?: "" }))
                android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_copied), android.widget.Toast.LENGTH_SHORT).show()
                }
                actionSheetMsg = null
            },
            onEdit = { editingMsg = msg; actionSheetMsg = null },
            onDelete = { deleteDialogIds = listOf(msg.id); actionSheetMsg = null },
            // Our own message that has not gone out yet: the queue knows whether
            // work is running or has failed, and the offers differ. Ask the queue,
            // not the transfer engine: its state lives in memory and does not
            // survive a restart, while the work sits on disk.
            onCancelSend = if (msg.isOutgoing && viewModel.sendInFlight(msg.id))
                { { viewModel.cancelUpload(msg.id); actionSheetMsg = null } } else null,
            onRetrySend = if (msg.isOutgoing && viewModel.sendFailed(msg.id))
                { { viewModel.retryPendingMedia(msg.id); actionSheetMsg = null } }
            // A text still under the clock: it is not out, so nothing else applies to it yet.
            else if (msg.isOutgoing && msg.status == MessageStatus.SENDING && !msg.isMedia && !msg.isDeleted)
                { { viewModel.resendNow(); actionSheetMsg = null } }
            else null,
            onForward = {
 // one-time (view-once) messages are never forwarded;
                // neither are a protected channel's posts.
                if (protectedChannel)
                    android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.channel_protected_toast), android.widget.Toast.LENGTH_SHORT).show()
                else if (msg.isViewOnce)
                    android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_viewonce_no_forward), android.widget.Toast.LENGTH_SHORT).show()
                else forwardIds = listOf(msg.id)
                actionSheetMsg = null
            },
            onSaved = {
                if (protectedChannel)
                    android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.channel_protected_toast), android.widget.Toast.LENGTH_SHORT).show()
                else if (msg.isViewOnce)
                    android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_viewonce_no_forward), android.widget.Toast.LENGTH_SHORT).show()
                else viewModel.forwardToSaved(listOf(msg.id))
                actionSheetMsg = null
            },
            onSelect = {
                selectionMode = true
                selectedIds = selectedIds + msg.id
                actionSheetMsg = null
            },
            onSaveGallery = {
                viewModel.saveMediaToGallery(msg.albumItems ?: listOf(msg))
                actionSheetMsg = null
            },
            onSaveSticker = if (protectedChannel) null
            else if (!msg.isMedia && com.alphamessenger.ui.emoji.bigEmojiOf(msg.text) != null &&
                com.alphamessenger.ui.emoji.AnimatedEmoji.codeOf(chatCtx, msg.text.trim()) != null) ({
                // A big animated emoji: kept among my stickers as its animation.
                actionSheetMsg = null
                scope.launch {
                    val ok = com.alphamessenger.ui.sticker.StickerStore.saveAnimatedEmoji(
                        chatCtx, msg.text.trim(), chatCtx.getString(R.string.sticker_saved_pack))
                    android.widget.Toast.makeText(chatCtx, chatCtx.getString(if (ok) R.string.sticker_saved else R.string.chat_file_prepare_failed),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            })
            else if (!msg.isSticker) null else ({
                actionSheetMsg = null
                scope.launch {
                    val ref = msg.mediaRefJson
                    val path = msg.localMediaPath?.takeIf { java.io.File(it).exists() }
                        ?: ref?.let { MediaPathCache[it] }?.takeIf { java.io.File(it).exists() }
                        ?: ref?.let { viewModel.repository.downloadMediaToCache(it) }
                    val ok = path != null && com.alphamessenger.ui.sticker.StickerStore.saveReceived(
                        chatCtx, path, ref, msg.stickerEmoji.orEmpty(), chatCtx.getString(R.string.sticker_saved_pack))
                    android.widget.Toast.makeText(chatCtx, chatCtx.getString(if (ok) R.string.sticker_saved else R.string.chat_file_prepare_failed),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }),
            onSaveGif = if (!msg.isGif || protectedChannel || msg.isViewOnce) null else ({
                actionSheetMsg = null
                scope.launch {
                    val ref = msg.mediaRefJson
                    val path = msg.localMediaPath?.takeIf { java.io.File(it).exists() }
                        ?: ref?.let { MediaPathCache[it] }?.takeIf { java.io.File(it).exists() }
                        ?: ref?.let { viewModel.repository.downloadMediaToCache(it) }
                    val ok = path != null &&
                        com.alphamessenger.ui.gif.GifLibrary.save(chatCtx, java.io.File(path), msg.mediaCaption.orEmpty())
                    android.widget.Toast.makeText(chatCtx, chatCtx.getString(if (ok) R.string.gif_saved else R.string.chat_file_prepare_failed),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }),
            onShare = {
                actionSheetMsg = null
                scope.launch {
                    // An album goes out whole; a single message as itself.
                    val items = (msg.albumItems ?: listOf(msg)).mapNotNull { m ->
                        val ref = m.mediaRefJson
                        val path = m.localMediaPath?.takeIf { java.io.File(it).exists() }
                            ?: ref?.let { MediaPathCache[it] }?.takeIf { java.io.File(it).exists() }
                            ?: ref?.let { viewModel.repository.downloadMediaToCache(it) }
                        path?.let { com.alphamessenger.util.ShareOut.Item(it, m.mediaFilename, m.mediaType) }
                    }
                    val ok = items.isNotEmpty() &&
                        com.alphamessenger.util.ShareOut.share(chatCtx, items, chatCtx.getString(R.string.common_share))
                    if (!ok) android.widget.Toast.makeText(chatCtx, chatCtx.getString(R.string.chat_file_prepare_failed), android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onClearCache = {
                val items = msg.albumItems ?: listOf(msg)
                val refs = items.mapNotNull { it.mediaRefJson }
                refs.forEach { ref ->
                    MediaPathCache.remove(ref)
                    runCatching { MediaImageCache.remove(ref) }
                    runCatching { DocumentThumbCache.remove(ref) }
                    MediaCacheEvictions.evict(ref)
                }
                // Deletion and the sender-copy decision happen in the ViewModel,
                // in the background: telling whether the send has finished needs a
                // queue query. The screen only shows the result, and success means
                // bytes REALLY freed — not "the file exists somewhere", which is
                // exactly the case where nothing was deleted.
                viewModel.clearMediaCache(msg) { freed ->
                    android.widget.Toast.makeText(
                        chatCtx,
                        if (freed > 0L)
                            chatCtx.getString(R.string.chat_cache_cleared, com.alphamessenger.media.MediaSizeFormat.short(freed))
                        else chatCtx.getString(R.string.chat_cache_empty),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                actionSheetMsg = null
            },
        )
    }

    // --- Secret Note composer ---
    if (showSecretNoteDialog) {
        com.alphamessenger.ui.chat.whisper.WhisperComposeSheet(
            onDismiss = { showSecretNoteDialog = false },
            onSendText = { text, secs -> viewModel.sendWhisperText(text, secs) },
            onSendPhoto = { uri, secs -> viewModel.sendWhisperPhoto(uri, secs) },
        )
    }
    // An incoming whisper, read under the finger.
    whisperOpen?.let { id ->
        if (state.whispers[id]?.glance == true) {
            com.alphamessenger.ui.chat.whisper.GlanceReader(
                senderName = state.peerDisplayName.ifBlank { stringResource(R.string.chat_member_fallback) },
                loadSecret = { viewModel.whisperSecret(id) },
                loadMedia = { ref -> viewModel.whisperPhoto(ref) },
                onBurn = { used, shot ->
                    com.alphamessenger.ui.chat.whisper.WhisperBurnFx.mark(id)
                    viewModel.burnWhisper(id, used, shot)
                },
                onForgetMedia = { ref -> viewModel.forgetWhisperPhoto(ref) },
                onClose = { whisperOpen = null },
            )
        } else {
            com.alphamessenger.ui.chat.whisper.WhisperReader(
                senderName = state.peerDisplayName.ifBlank { stringResource(R.string.chat_member_fallback) },
                loadSecret = { viewModel.whisperSecret(id) },
                loadPhoto = { ref -> viewModel.whisperPhoto(ref) },
                onBurn = { used, shot ->
                    com.alphamessenger.ui.chat.whisper.WhisperBurnFx.mark(id)
                    viewModel.burnWhisper(id, used, shot)
                },
                onForgetPhoto = { ref -> viewModel.forgetWhisperPhoto(ref) },
                onClose = { whisperOpen = null },
            )
        }
    }

    // --- Disappearing-messages timer picker ---
    if (showTimerDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimerDialog = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.chat_disappearing), color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.chat_disappearing_hint),
                        color = colors.textSecondary, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    CHAT_TIMER_OPTIONS.forEach { opt ->
                        val selected = state.chatTimerSeconds == opt.seconds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    viewModel.setChatTimer(opt.seconds)
                                    showTimerDialog = false
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (selected) AlphaIcons.Filled.RadioButtonChecked else AlphaIcons.Outlined.RadioButtonUnchecked,
                                null,
                                tint = if (selected) colors.accent else colors.textMuted,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(opt.labelRes),
                                color = if (selected) colors.accent else colors.textPrimary,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showTimerDialog = false }) {
                    Text(stringResource(R.string.common_close), color = colors.accent)
                }
            },
        )
    }

    // --- Forward target picker ---
    forwardIds?.let { ids ->
        ForwardTargetsPicker(
            viewModel = viewModel,
            summary = forwardSummary(state.messages.filter { it.id in ids }),
            colors = colors,
            onDismiss = { forwardIds = null },
            onConfirm = { targetIds, comment, plain ->
 // classic multi-forward: send the selected messages to
                // EVERY chosen recipient at once (reuse pipeline, no re-upload), plus an
                // optional comment. Stay in the current screen; a toast confirms.
                viewModel.forwardToTargets(ids, targetIds, comment, plain)
                forwardIds = null
                exitSelection()
                android.widget.Toast.makeText(
                    chatCtx,
                    if (targetIds.size > 1) chatCtx.getString(R.string.chat_forwarded_count, targetIds.size) else chatCtx.getString(R.string.chat_forwarded),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    // --- Delete confirmation (for me / for everyone) ---
    deleteDialogIds?.let { ids ->
        // Saved and assistant chats live only on this phone: there is no one else to delete for.
        val localOnly = isSavedChat || isAssistant
        val allOwn = !localOnly && state.deleteScope(ids) == DeleteScope.FOR_EVERYONE
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteDialogIds = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.chat_delete_count_title, ids.size), color = colors.textPrimary) },
            text = if (localOnly) null else {
                {
                    Text(
                        if (allOwn) stringResource(R.string.chat_delete_choice_own)
                        else stringResource(R.string.chat_delete_choice_other),
                        color = colors.textSecondary,
                    )
                }
            },
            confirmButton = {
                if (localOnly) {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.deleteForMe(ids); deleteDialogIds = null; exitSelection()
                    }) { Text(stringResource(R.string.common_delete), color = colors.danger, fontWeight = FontWeight.SemiBold) }
                } else Column {
                    if (allOwn) {
                        androidx.compose.material3.TextButton(onClick = {
                            viewModel.deleteForEveryone(ids); deleteDialogIds = null; exitSelection()
                        }) { Text(stringResource(R.string.chat_delete_for_everyone), color = colors.danger, fontWeight = FontWeight.SemiBold) }
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.deleteForMe(ids); deleteDialogIds = null; exitSelection()
                    }) { Text(stringResource(R.string.chat_delete_for_me), color = colors.textPrimary) }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteDialogIds = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun ScrollToBottomFab(
    badge: Int,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** An answer is being written below: a ring turns around the button. */
    writing: Boolean = false,
) {
    Box(modifier = modifier) {
        // Main circular button — opaque accent at the bottom, arrow-down icon.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(colors.bgSecondary)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AlphaIcons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_scroll_to_latest),
                tint = colors.textPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
        // Over the button's rim: under it, the opaque button hid it.
        if (writing) {
            val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "fabRing")
            val turn by spin.animateFloat(
                0f, 360f,
                androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
                label = "turn",
            )
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val w = 2.2.dp.toPx()
                drawArc(
                    colors.accent, startAngle = turn - 90f, sweepAngle = 100f, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
        // Unread badge on top-right of the FAB (only when count > 0).
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badge > 99) "99+" else badge.toString(),
                    color = colors.bgPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberSheet(
    isLoading: Boolean,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var npub by remember { mutableStateOf("") }
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = { if (!isLoading) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp)
                .imePadding(),
        ) {
            Text(
                stringResource(R.string.chat_invite_title),
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.chat_invite_hint),
                color = colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = npub,
                onValueChange = { npub = it },
                label = { Text(stringResource(R.string.chat_invite_field_label)) },
                placeholder = { Text("npub1…", color = colors.textMuted) },
                singleLine = true,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderLight,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedLabelColor = colors.accent,
                    unfocusedLabelColor = colors.textMuted,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isLoading,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderLight),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = { onConfirm(npub.trim()) },
                    enabled = npub.trim().isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.bgPrimary,
                        disabledContainerColor = colors.bgTertiary,
                        disabledContentColor = colors.textMuted,
                    ),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.bgPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_invite_searching))
                    } else {
                        Text(stringResource(R.string.chat_invite_action))
                    }
                }
            }
        }
    }
}

// --- Top app bar — back, avatar, name+status, phone, video, profile ---

/**
 * classic activity indicator shown as a small incoming bubble at the
 * bottom of the message list: three bouncing dots for "typing", a slowly
 * blinking microphone for "recording". For groups, the member's name is
 * prefixed so you can tell who is active.
 */
/** The bottom region's transient-strip layers, ordered in one place so the
 *  presence-vs-context order is decided once. Emitted straight
 *  into the caller's ColumnScope (no wrapping layout) so nothing below — the
 *  weight-sharing composer / attach panel — is affected. */
@Composable
private fun ColumnScope.BottomArea(
    presence: @Composable () -> Unit,
    context: @Composable () -> Unit,
) {
    presence()   // peer / system status (typing…), closest to the conversation
    context()    // local-user context (reply / edit / forward), closest to input
}

/** Container for peer/system status shown above the composer. Today it renders
 *  typing/recording; tomorrow it can host reconnecting…/syncing…/"sending photo…".
 *  TypingIndicator is just one [PresenceContent] implementation. */
@Composable
private fun PresenceBar(activity: PeerActivity?, isGroup: Boolean, colors: AlphaColorScheme) {
    activity?.let { PresenceContent(it, isGroup, colors) }
}

@Composable
private fun PresenceContent(activity: PeerActivity, isGroup: Boolean, colors: AlphaColorScheme) {
    TypingIndicatorBubble(activity = activity, isGroup = isGroup, colors = colors)
}

@Composable
private fun TypingIndicatorBubble(activity: PeerActivity, isGroup: Boolean, colors: AlphaColorScheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The same side inset as a message row.
            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // In group chats match the 36 dp avatar column (32 dp avatar + 4 dp gap)
        // so the bubble left-edge aligns with other incoming group bubbles.
        if (isGroup) Spacer(Modifier.width(36.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(colors.msgIn)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activity.senderName.isNotBlank()) {
                    Text(
                        text = "${activity.senderName} ",
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (activity.state == "recording") {
                    BlinkingMic(colors)
                } else {
                    BouncingDots(colors)
                }
            }
        }
    }
}

/** Three dots bouncing in sequence — the classic "is typing" animation. */
@Composable
private fun BouncingDots(colors: AlphaColorScheme) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(colors.textMuted.copy(alpha = alpha)),
            )
        }
    }
}

/** A microphone icon pulsing its opacity — shown while a peer records audio. */
@Composable
private fun BlinkingMic(colors: AlphaColorScheme) {
    val transition = rememberInfiniteTransition(label = "recording")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            AlphaIcons.Outlined.Mic,
            contentDescription = stringResource(R.string.chat_recording_a11y),
            tint = colors.accent.copy(alpha = alpha),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.chat_recording),
            color = colors.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    seed: String,
    title: String,
    subtitle: String,
    subtitleAccent: Boolean = false,
    colors: AlphaColorScheme,
    onBack: () -> Unit,
    onPhone: () -> Unit,
    onVideo: () -> Unit,
    onAvatar: () -> Unit,
    onTitleRow: () -> Unit,
    onMenu: () -> Unit,
    isGroup: Boolean = false,
    onAddMember: () -> Unit = {},
    avatarPath: String = "",
    isMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    onSearch: () -> Unit = {},
    onScrollToTop: () -> Unit = {},
    /** In a channel: null = owner (deletes it in its settings), else "leave". */
    channelMenu: ChannelMenu? = null,
    onClearHistory: () -> Unit = {},
    onExport: (() -> Unit)? = null,
    onDeleteChat: () -> Unit = {},
    showTimer: Boolean = false,
    timerActive: Boolean = false,
    onDisappearing: () -> Unit = {},
    noStore: Boolean = false,
    onToggleNoStore: () -> Unit = {},
    /** A secret chat: never kept on servers (no switch), deleted for both. */
    secret: Boolean = false,
    isSaved: Boolean = false,
    /** The assistant's own chat: its avatar and the verified seal. */
    assistant: Boolean = false,
    /** A bot's chat: its conversations live here, not in the (already huge) screen function. */
    assistantVm: ChatViewModel? = null,
    assistantMemory: Boolean = false,
    /** Call buttons (not in Saved Messages, not in a channel). */
    showCalls: Boolean = true,
    /** The peer's privacy rules let us call (voice / video). */
    showVoice: Boolean = true,
    showVideo: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var conversationsOpen by remember { mutableStateOf(false) }
    val barCtx = androidx.compose.ui.platform.LocalContext.current
    val onNewConversation: () -> Unit = {
        assistantVm?.newAssistantConversation { moved ->
            if (!moved) android.widget.Toast.makeText(barCtx, barCtx.getString(R.string.assistant_already_new), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val onConversations: () -> Unit = { conversationsOpen = true }
    if (conversationsOpen && assistantVm != null) {
        AssistantConversationsSheet(
            viewModel = assistantVm,
            memoryOn = assistantMemory,
            colors = colors,
            onDismiss = { conversationsOpen = false },
            onNew = { conversationsOpen = false; onNewConversation() },
            onOpen = { id -> conversationsOpen = false; assistantVm.openAssistantConversation(id) },
        )
    }
 // Compose 1:1 with other messengers layout:
    //  [<back] [avatar] [title+subtitle (clickable)] ... [video] [voice] [⋮]
    // Built as a manual Row instead of TopAppBar so we can tighten the
    // start padding, drop the default Material gap between nav-icon and
    // title, and make the title block one big tap target.
    val grad = remember(seed) { avatarGradientFor(seed) }
    val initial = com.alphamessenger.ui.components.avatarInitial(title)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = chatStatusPad()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    AlphaIcons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = colors.textPrimary,
                )
            }
            // Real peer avatar (if cached from a ProfileUpdate) wins over the
            // deterministic gradient placeholder. Coil decodes off the Main
            // thread + downsamples + caches — the old remember{ decodeFile }
            // ran on Main during the open-slide.
            val topBarCtx = androidx.compose.ui.platform.LocalContext.current
            val avatarRequest = remember(avatarPath) {
                if (avatarPath.isBlank()) null
                else java.io.File(avatarPath).takeIf { it.exists() && it.length() > 0L }?.let { f ->
                    coil3.request.ImageRequest.Builder(topBarCtx).data(f)
                        .memoryCacheKey("$avatarPath#${f.lastModified()}")
                        .diskCacheKey("$avatarPath#${f.lastModified()}")
                        .build()
                }
            }
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .avatarBackdrop(
                        if (isSaved) Brush.linearGradient(listOf(colors.accent, colors.accent.copy(alpha = 0.55f).compositeOver(Color(0xFF101418))))
                        else Brush.linearGradient(listOf(grad.first, grad.second)),
                    )
                    .clickable(onClick = onAvatar),
                contentAlignment = Alignment.Center,
            ) {
                if (assistant) {
                    com.alphamessenger.assistant.AssistantAvatar(38.dp)
                } else if (isSaved) {
                    Icon(com.alphamessenger.ui.components.AlphaIcons.Vault, null, tint = Color.White, modifier = Modifier.size(22.dp))
                } else if (isGroup) com.alphamessenger.ui.components.GroupAvatarPlaceholder(Modifier.matchParentSize())
                else Text(initial, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (!isSaved && avatarRequest != null) {
                    coil3.compose.AsyncImage(
                        model = avatarRequest,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .clip(CircleShape),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onTitleRow)
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Disappearing-messages indicator: violet clock by the name so
                    // the user always sees the mode is on.
                    if (timerActive) {
                        Icon(
                            AlphaIcons.Outlined.Disappearing,
                            contentDescription = stringResource(R.string.chat_disappearing_on_a11y),
                            tint = Color(0xFF9B6CF2),
                            modifier = Modifier.size(20.dp).padding(end = 5.dp),
                        )
                    }
                    if (assistant) com.alphamessenger.assistant.BotMark(16.dp, Modifier.padding(end = 5.dp))
                    Text(
                        text = title,
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (assistant) com.alphamessenger.assistant.VerifiedBadge(16.dp, Modifier.padding(start = 5.dp))
                }
                AnimatedStatusText(
                    text = subtitle,
                    color = if (subtitleAccent) colors.accent else colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 13.sp,
                    // Nudge the subtitle up to sit snugly under the name.
                    modifier = Modifier.offset(y = (-1).dp),
                )
            }
            // (No add-member icon on the group top bar — invites live in group
            // info → "Settings".)
            if (!isSaved && showCalls && showVideo) {
                IconButton(onClick = onVideo, modifier = Modifier.size(44.dp)) {
                    Icon(
                        AlphaIcons.Outlined.Videocam,
                        contentDescription = stringResource(R.string.calls_incoming_video),
                        tint = colors.textSecondary,
                    )
                }
            }
            if (!isSaved && showCalls && showVoice) {
                IconButton(onClick = onPhone, modifier = Modifier.size(44.dp)) {
                    Icon(
                        AlphaIcons.Outlined.Phone,
                        contentDescription = stringResource(R.string.chat_voice_call),
                        tint = colors.textSecondary,
                    )
                }
            }
            if (assistant) {
                IconButton(onClick = onNewConversation, modifier = Modifier.size(44.dp)) {
                    Icon(
                        AlphaIcons.Outlined.AddComment,
                        contentDescription = stringResource(R.string.assistant_new_conversation),
                        tint = colors.textSecondary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        AlphaIcons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.chat_menu_a11y),
                        tint = colors.textSecondary,
                    )
                }
                if (menuOpen) {
                    val dens = LocalDensity.current
                    val offsetX = with(dens) { (-8).dp.roundToPx() }
                    val offsetY = with(dens) { 48.dp.roundToPx() }
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(x = offsetX, y = offsetY),
                        onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = colors.bgSecondary,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.alphaBorder),
                            shadowElevation = 8.dp,
                            modifier = Modifier.width(252.dp),
                        ) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                if (!isSaved) ChatMenuRow(
                                    icon = if (isMuted) AlphaIcons.Outlined.Notifications else AlphaIcons.Outlined.NotificationsOff,
                                    label = if (isMuted) stringResource(R.string.chat_unmute) else stringResource(R.string.chat_mute),
                                    colors = colors,
                                ) { menuOpen = false; onToggleMute() }
                                ChatMenuRow(AlphaIcons.Outlined.Search, stringResource(R.string.common_search), colors) {
                                    menuOpen = false; onSearch()
                                }
                                if (showTimer && !isSaved) {
                                    ChatMenuRow(
                                        icon = AlphaIcons.Outlined.Disappearing,
                                        label = if (timerActive) stringResource(R.string.chat_disappearing_on) else stringResource(R.string.chat_disappearing),
                                        colors = colors,
                                        tintOverride = if (timerActive) colors.accent else null,
                                    ) { menuOpen = false; onDisappearing() }
                                }
                                if (!isSaved && !secret && channelMenu?.reader != true) ChatMenuRow(
                                    icon = AlphaIcons.Outlined.CloudOff,
                                    label = stringResource(R.string.chat_no_store_title),
                                    colors = colors,
                                    tintOverride = if (noStore) colors.accent else null,
                                    checked = noStore,
                                ) { menuOpen = false; onToggleNoStore() }
                                ChatMenuRow(AlphaIcons.Outlined.VerticalAlignTop, stringResource(R.string.chat_scroll_to_top), colors) {
                                    menuOpen = false; onScrollToTop()
                                }
                                if (!isSaved) ChatMenuRow(AlphaIcons.Outlined.Palette, stringResource(R.string.look_menu), colors) {
                                    menuOpen = false; com.alphamessenger.ui.chat.look.ChatLooks.editMine = true
                                }
                                if (assistant) ChatMenuRow(AlphaIcons.Outlined.History, stringResource(R.string.assistant_conversations), colors) {
                                    menuOpen = false; onConversations()
                                }
                                if (assistant) ChatMenuRow(AlphaIcons.Outlined.AutoAwesome, stringResource(R.string.assistant_settings_menu), colors) {
                                    menuOpen = false; onMenu()
                                }
                                if (onExport != null) ChatMenuRow(AlphaIcons.Outlined.FolderZip, stringResource(R.string.export_title_chat), colors) {
                                    menuOpen = false; onExport()
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    thickness = 0.5.dp,
                                    color = colors.alphaBorder,
                                )
                                if (channelMenu?.reader != true) ChatMenuRow(AlphaIcons.Outlined.DeleteSweep, stringResource(R.string.chat_clear_history), colors) {
                                    menuOpen = false; onClearHistory()
                                }
                                if (channelMenu != null) {
                                    // A channel is left (an owner deletes it in its settings).
                                    channelMenu.onLeave?.let { leave ->
                                        ChatMenuRow(
                                            icon = AlphaIcons.AutoMirrored.Outlined.ExitToApp,
                                            label = stringResource(R.string.channel_leave_title),
                                            colors = colors,
                                            tintOverride = colors.danger,
                                            labelColorOverride = colors.danger,
                                        ) { menuOpen = false; leave() }
                                    }
                                } else if (!isSaved) ChatMenuRow(
                                    icon = AlphaIcons.Outlined.Delete,
                                    label = stringResource(if (secret) R.string.secret_end else R.string.chat_delete_chat),
                                    colors = colors,
                                    tintOverride = colors.danger,
                                    labelColorOverride = colors.danger,
                                ) { menuOpen = false; onDeleteChat() }
                            }
                        }
                    }
                }
            }
        }
        // Thin divider under the full-width top bar — separates it from the chat area.
        // The floating pill has its own edge: a straight line under its rounded corners would show.
        if (!LocalFloatingTop.current) HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomCenter),
            thickness = 0.5.dp,
            color = colors.alphaBorder,
        )
    }
}

// --- Chat top-bar popup menu row ---

@Composable
private fun ChatMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    colors: com.alphamessenger.ui.theme.AlphaColorScheme,
    tintOverride: androidx.compose.ui.graphics.Color? = null,
    labelColorOverride: androidx.compose.ui.graphics.Color? = null,
    /** Non-null → the row is an on/off option and shows a check mark when on. */
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tintOverride ?: colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            color = labelColorOverride ?: colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (checked == true) {
            Icon(AlphaIcons.Outlined.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
    }
}

// --- Date divider — small chip in the middle ---

/** Floating sticky date badge — a centered glass pill that hovers
 *  just under the TopBar showing the current day while the user scrolls history. */
@Composable
private fun FloatingDateBadge(label: String, colors: AlphaColorScheme) {
    Box(
        modifier = Modifier
            .shadow(4.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(colors.bgTertiary)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DateDivider(label: String, colors: AlphaColorScheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        val (chipBg, chipFg) = serviceChip(colors.bgTertiary, colors.textMuted)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(chipBg)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                color = chipFg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** End-to-end-encryption notice shown at the very top of every chat's history
 *. Reassures the user that messages &
 *  calls are E2EE and only chat participants can read them. */
@Composable
private fun E2eeNotice(isGroup: Boolean, colors: AlphaColorScheme, isSaved: Boolean = false, isChannel: Boolean = false) {
    val text = if (isSaved) {
        stringResource(R.string.chat_e2ee_saved)
    } else if (isChannel) {
        stringResource(R.string.chat_e2ee_channel)
    } else if (isGroup) {
        stringResource(R.string.chat_e2ee_group)
    } else {
        stringResource(R.string.chat_e2ee_direct)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accent.copy(alpha = 0.10f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AlphaIcons.Outlined.Lock, null,
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** classic forward label, rendered INSIDE a bubble at the top (text + every
 * media type) — classic: a double-arrow icon + italic text. */
@Composable
internal fun ForwardedLabel(isOutgoing: Boolean, colors: AlphaColorScheme) {
    // Use the bubble's OWN text colour for outgoing (was a hardcoded white → invisible
    // on the light-accent themes, e.g. msgOutText 0xFF04181F). Guaranteed contrast.
    val c = if (isOutgoing) colors.msgOutText.copy(alpha = 0.85f) else colors.accent
    Row(
        modifier = Modifier.padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AlphaIcons.Filled.KeyboardDoubleArrowRight, null, tint = c, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            stringResource(R.string.chat_forwarded),
            color = c,
            fontSize = 11.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        )
    }
}

// --- View Once Media / Secret Note — in-chat card + fullscreen secure viewer ---

private val ViewOnceAccent = Color(0xFF9B6CF2)

/** In-chat card for a view-once message. Never reveals the content inline:
 *  tapping opens a fullscreen, screenshot-protected viewer; closing it consumes
 *  the message (→ "Viewed", blob wiped). Text view-once == Secret Note. */
@Composable
private fun ViewOnceContent(
    message: MessageUi,
    bubbleColor: Color,
    textColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    colors: AlphaColorScheme,
    isGroup: Boolean,
    showSender: Boolean,
    senderColor: Color,
    mediaLoader: (suspend (String) -> String?)?,
    onLongPress: () -> Unit,
    onConsumed: () -> Unit,
) {
    val isSecret = !message.isMedia
    val label = when {
        isSecret -> stringResource(R.string.chat_secret_note)
        message.mediaType == "image" -> stringResource(R.string.preview_photo)
        message.mediaType == "video" -> stringResource(R.string.media_kind_video)
        message.mediaType == "audio" -> stringResource(R.string.voice_note_label)
        else -> stringResource(R.string.preview_file)
    }
    val consumed = message.isViewOnceConsumed
    val openable = !consumed && !message.isOutgoing
    var opened by remember(message.id) { mutableStateOf(false) }

    Column {
        if (showSender && isGroup && !message.isOutgoing) {
            Text(
                message.senderName ?: "", color = senderColor, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 3.dp),
            )
        }
        Row(
            // ONE shell — see BubbleGeometry.bubbleShell. min-width stays the receiver;
            // a consumed view-once passes the dimmed colour in.
            modifier = Modifier
                .widthIn(min = 190.dp)
                .bubbleShell(
                    isOutgoing = message.isOutgoing,
                    showTail = hasTail(message),
                    bubbleShape = bubbleShape,
                    bubbleColor = if (consumed) bubbleColor.copy(alpha = 0.5f) else bubbleColor,
                    maxWidth = 250.dp,
                    onTap = { if (openable) opened = true },
                    onLongPress = onLongPress,
                    contentPadding = BubbleTokens.compactPadding(
                        message.isOutgoing, hasTail(message), top = 8.dp, bottom = 8.dp,
                    ),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (consumed) textColor.copy(alpha = 0.55f) else ViewOnceAccent
            Icon(
                if (consumed) AlphaIcons.Outlined.Visibility
                else if (isSecret) AlphaIcons.Outlined.Lock else AlphaIcons.Outlined.Visibility,
                contentDescription = null, tint = tint, modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (consumed) stringResource(R.string.chat_viewonce_viewed) else if (isSecret) stringResource(R.string.chat_secret_note) else stringResource(R.string.chat_viewonce_open),
                    color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                if (!consumed) {
                    Text(
                        if (message.isOutgoing) label else stringResource(R.string.chat_viewonce_tap_hint, label),
                        color = textColor.copy(alpha = 0.7f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (message.isOutgoing) {
                Spacer(Modifier.width(4.dp))
                com.alphamessenger.ui.chat.bubbles.DeliveryTicks(message.status, textColor)
            }
        }
    }

    if (opened) {
        SecureFullscreenViewer(onDismiss = { opened = false; onConsumed() }) {
            ViewOnceViewerContent(message = message, isSecret = isSecret, label = label, mediaLoader = mediaLoader)
        }
    }
}

/** Fullscreen Dialog with FLAG_SECURE (blocks screenshots / screen recording on
 *  most devices). Black backdrop + a close button; content fills the screen. */
@Composable
private fun SecureFullscreenViewer(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn,
        ),
    ) {
        com.alphamessenger.ui.chat.media.MediaWindowEffect(secure = true)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            content()
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = Color.White)
            }
        }
    }
}

/** The content shown inside the secure viewer, per message kind. */
@Composable
private fun ViewOnceViewerContent(
    message: MessageUi,
    isSecret: Boolean,
    label: String,
    mediaLoader: (suspend (String) -> String?)?,
) {
    // View-once plaintext lives only in memory (never on disk) — read it from
    // the cache populated by the realtime callback; fall back to the (usually
    // empty) DB content.
    val cached = remember(message.id) { com.alphamessenger.data.ViewOnceCache.get(message.id) }
    if (isSecret) {
        val secretText = cached?.text?.takeIf { it.isNotBlank() } ?: message.text
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 96.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = secretText.ifBlank { stringResource(R.string.chat_secret_unavailable) },
                color = Color.White,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }

    // Resolve a local file for the blob (sender file or download). For view-once
    // the ref isn't on disk → use the in-memory cached ref from the live event.
    val refJson = cached?.mediaRefJson ?: message.mediaRefJson
    var path by remember(message.id) { mutableStateOf(message.localMediaPath?.takeIf { java.io.File(it).exists() }) }
    var failed by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) {
        if (path != null) return@LaunchedEffect
        val p = if (refJson != null && mediaLoader != null) mediaLoader(refJson) else null
        if (p != null) path = p else failed = true
    }

    when {
        failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chat_media_load_failed), color = Color.White)
        }
        path == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = ViewOnceAccent)
        }
        message.mediaType == "image" -> {
            val bmp = remember(path) {
                runCatching { com.alphamessenger.data.MediaVault.decodeFile(path) }.getOrNull()?.asImageBitmap()
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }
        message.mediaType == "video" -> {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        setVideoPath(path)
                        setOnPreparedListener { it.isLooping = false; start() }
                    }
                },
            )
        }
        else -> {
            // Voice / audio / file → big play affordance; auto-play audio.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            DisposableEffect(path) {
                val mp = if (message.mediaType == "audio")
                    runCatching { android.media.MediaPlayer().apply { com.alphamessenger.data.MediaVault.setSource(this, path); prepare(); start() } }.getOrNull()
                else null
                onDispose { runCatching { mp?.release() } }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (message.mediaType == "audio") AlphaIcons.Outlined.Mic else AlphaIcons.Outlined.InsertDriveFile,
                        null, tint = ViewOnceAccent, modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(label, color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}

/** Service chips over a chat wallpaper use a dark translucent pill with light
 * text so they read on any photo/gradient; without a wallpaper the
 *  theme colors are kept. */
@Composable
private fun serviceChip(bg: Color, fg: Color, overWallpaperFg: Color = Color.White): Pair<Color, Color> =
    if (com.alphamessenger.ui.theme.LocalChatWallpaper.current !is com.alphamessenger.ui.theme.ChatWallpaper.Default)
        Color.Black.copy(alpha = 0.40f) to overWallpaperFg
    else bg to fg

/** Centered system/moderation notice. Background is risk-coded:
 *  ban / group-deleted → red (danger); join → soft green; neutral events
 *  (leave, etc.) → subtle translucent. Distinct from chat bubbles. */
@Composable
private fun SystemNotice(text: String, colors: AlphaColorScheme) {
    val (bg0, fg0) = when {
        text.startsWith("🚫") || text.startsWith("🗑") ->
            colors.danger.copy(alpha = 0.14f) to colors.danger
        text.startsWith("➕") ->
            Color(0xFF2E7D32).copy(alpha = 0.16f) to colors.textSecondary
        else ->
            colors.textMuted.copy(alpha = 0.12f) to colors.textSecondary
    }
    val (bg, fg) = serviceChip(bg0, fg0)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                color = fg,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Centered system notice for the disappearing-messages timer — its own accent
 *  colour (violet) so it stands apart from ban/join service chips. */
@Composable
private fun TimerNotice(text: String, active: Boolean, colors: AlphaColorScheme) {
    val accent = Color(0xFF9B6CF2) // signature violet for the timer feature
    val (bg, fg) = serviceChip(
        if (active) accent.copy(alpha = 0.14f) else colors.textMuted.copy(alpha = 0.12f),
        if (active) accent else colors.textSecondary,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AlphaIcons.Outlined.Disappearing, null, tint = fg,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                color = fg,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Centered call-log chip — a LOCAL journal row for a finished/missed call.
 *  Missed calls render red; tap = call back with the same type. */
@Composable
private fun CallLogNotice(
    entry: com.alphamessenger.calls.CallHistoryEntry,
    colors: AlphaColorScheme,
    onCallBack: () -> Unit,
) {
    val missed = entry.outcome == com.alphamessenger.calls.CallOutcome.MISSED
    val label = when (entry.outcome) {
        com.alphamessenger.calls.CallOutcome.ANSWERED -> {
            val d = entry.durationSec
            val dur = if (d >= 60) "%d:%02d".format(d / 60, d % 60)
                else pluralStringResource(R.plurals.call_seconds_abbrev, d, d)
            when {
                entry.outgoing && entry.video -> stringResource(R.string.chat_call_out_video, dur)
                entry.outgoing -> stringResource(R.string.chat_call_out_voice, dur)
                entry.video -> stringResource(R.string.chat_call_in_video, dur)
                else -> stringResource(R.string.chat_call_in_voice, dur)
            }
        }
        com.alphamessenger.calls.CallOutcome.MISSED ->
            if (entry.video) stringResource(R.string.calls_missed_video) else stringResource(R.string.calls_missed_voice)
        com.alphamessenger.calls.CallOutcome.REJECTED ->
            if (entry.outgoing) stringResource(R.string.call_error_peer_rejected) else stringResource(R.string.chat_call_rejected_in)
        com.alphamessenger.calls.CallOutcome.BUSY -> stringResource(R.string.call_busy)
        com.alphamessenger.calls.CallOutcome.NO_ANSWER -> stringResource(R.string.call_error_timeout)
        com.alphamessenger.calls.CallOutcome.CANCELLED -> stringResource(R.string.chat_call_cancelled)
        com.alphamessenger.calls.CallOutcome.FAILED -> stringResource(R.string.chat_call_failed)
        com.alphamessenger.calls.CallOutcome.REFUSED -> stringResource(R.string.call_refused_by_rules)
    }
    val (bg, fg) = serviceChip(
        if (missed) colors.danger.copy(alpha = 0.14f) else colors.textMuted.copy(alpha = 0.12f),
        if (missed) colors.danger else colors.textSecondary,
        overWallpaperFg = if (missed) Color(0xFFFF8A80) else Color.White,
    )
    val timeStr = remember(entry.atMs) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.atMs))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .clickable(onClick = onCallBack)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.video) AlphaIcons.Outlined.Videocam else AlphaIcons.Outlined.Phone,
                null, tint = fg, modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_call_log_line, label, timeStr),
                color = fg,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Parse an ISO-8601 timestamp and convert it to the DEVICE-LOCAL zone.
 *  All day-grouping and time labels must use this — slicing the raw UTC string
 *  showed UTC time (hours off) and split a single local day across two UTC
 *  dates and would split one local day across two UTC dates. */
/**
 * THE single instant resolver for the whole chat UI. Every time/date shown by any
 * bubble type + the date dividers + the floating badge derives from this one
 * function — so there is exactly ONE source of truth and no per-bubble drift.
 *
 * It resolves ANY timestamp shape the backend can produce to the viewer's local
 * zone, so a message can NEVER render raw-UTC:
 *   1. RFC3339 with an offset ("…+00:00" / "…Z")  — the normal path.
 *   2. Naive ISO with NO offset ("2026-08-04T09:47:00") — the backend stores UTC,
 *      so it's interpreted as UTC and converted to local.
 *   3. Epoch seconds / millis — converted from the instant.
 */
internal fun localZdt(timestamp: String): java.time.ZonedDateTime? {
    val zone = java.time.ZoneId.systemDefault()
    runCatching {
        return java.time.OffsetDateTime.parse(timestamp).atZoneSameInstant(zone)
    }
    runCatching {
        // No offset in the string → treat wall-clock as UTC (backend now_rfc3339 is UTC).
        return java.time.LocalDateTime.parse(timestamp)
            .atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(zone)
    }
    runCatching {
        val n = timestamp.trim().toLong()
        val instant = if (n > 100_000_000_000L) java.time.Instant.ofEpochMilli(n)
        else java.time.Instant.ofEpochSecond(n)
        return instant.atZone(zone)
    }
    return null
}

/** Localized pieces [MessageDayClassifier.label] needs: short weekday and
 *  genitive month names plus the Today/Yesterday words and date templates. */
internal class DayLabels(
    val weekdays: Array<String>,
    val months: Array<String>,
    val today: String,
    val yesterday: String,
    val thisYear: String,
    val withYear: String,
)

/** Resolve [DayLabels] from resources in the current app locale. */
@Composable
private fun rememberDayLabels(): DayLabels {
    val weekdays = stringArrayResource(R.array.chat_weekdays_short)
    val months = stringArrayResource(R.array.chat_months_genitive)
    val today = stringResource(R.string.common_today)
    val yesterday = stringResource(R.string.common_yesterday)
    val thisYear = stringResource(R.string.chat_date_this_year)
    val withYear = stringResource(R.string.chat_date_with_year)
    return remember(weekdays, months, today, yesterday, thisYear, withYear) {
        DayLabels(weekdays, months, today, yesterday, thisYear, withYear)
    }
}

/**
 * SINGLE source of truth for message-day sectioning.
 * Both the in-history [DateDivider] and the scroll-time [FloatingDateBadge] derive
 * from this — no two divergent implementations.
 *
 * "Today"/"Yesterday" are decided ONLY by the local CALENDAR date (rolls at
 * local 00:00), never by an elapsed-24h heuristic:
 *   LocalDate(msg) == LocalDate(now)            → "Today"
 *   LocalDate(msg) == LocalDate(now.minusDays1) → "Yesterday"
 *   else                                        → weekday (this week) / date.
 */
private object MessageDayClassifier {
    private fun localDate(timestamp: String): java.time.LocalDate? =
        localZdt(timestamp)?.toLocalDate()

    /** Stable per-day key for divider boundaries (local calendar day). */
    fun dayKey(timestamp: String): String =
        localDate(timestamp)?.toString()
            ?: if (timestamp.length >= 10) timestamp.substring(0, 10) else timestamp

    /** A divider belongs before [current] when it opens a new calendar day
     *  relative to the [older] neighbour (or is the oldest message). */
    fun needsDivider(olderTimestamp: String?, currentTimestamp: String): Boolean =
        olderTimestamp == null || dayKey(olderTimestamp) != dayKey(currentTimestamp)

    /** Human label shown by BOTH the divider and the floating badge.
     *  [res] carries the localized weekday/month names and templates. */
    fun label(timestamp: String, res: DayLabels): String {
        val date = localDate(timestamp) ?: return dayKey(timestamp)
        val today = java.time.LocalDate.now()
 // standard: Today / Yesterday, then an UNAMBIGUOUS "Sun, 2 August"
        // (short weekday + day + month; + year when older) — never a bare weekday
        // name, which doesn't say WHICH Sunday.
        val dow = res.weekdays[(date.dayOfWeek.value - 1).coerceIn(0, 6)]
        val month = res.months[(date.monthValue - 1).coerceIn(0, 11)]
        return when {
            date == today -> res.today
            date == today.minusDays(1) -> res.yesterday
            date.year == today.year -> res.thisYear.format(dow, date.dayOfMonth, month)
            else -> res.withYear.format(dow, date.dayOfMonth, month, date.year)
        }
    }
}

// --- Message bubble — left/right, status, reactions, long-press menu ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactCardBubble(
    npub: String,
    isOutgoing: Boolean,
    colors: AlphaColorScheme,
    repository: com.alphamessenger.data.AlphaCoreRepository?,
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isOutgoing) colors.msgOutEnd else colors.msgIn)
                .clickable { showMenu = true }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AlphaIcons.Outlined.Person, contentDescription = null, tint = colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.chat_contact), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    npub.take(12) + "…" + npub.takeLast(6),
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
    if (showMenu) {
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { showMenu = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                ContactCardAction(AlphaIcons.Outlined.PersonAdd, stringResource(R.string.chat_add_friend), colors) {
                    showMenu = false
                    scope.launch { runCatching { repository?.addContactByNpub(npub) } }
                }
                ContactCardAction(AlphaIcons.Outlined.ContentCopy, stringResource(R.string.chat_copy_public_key), colors) {
                    showMenu = false
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("npub", npub))
                }
            }
        }
    }
}

@Composable
private fun ContactCardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent)
        Spacer(Modifier.width(16.dp))
        Text(label, color = colors.textPrimary, fontSize = 16.sp)
    }
}

/**
 * Per-key transfer subscription — the standard. A bubble subscribes to
 * ONLY its own key's [kotlinx.coroutines.flow.StateFlow], so when some OTHER
 * file ticks this bubble sees no emission and does not recompose. Returns null
 * when the engine holds no snapshot for the key, byte-for-byte matching the old
 * `snapshots.value.downloadOf(...)` / `.uploadOf(...)` map lookup — so the
 * bubble logic downstream is unchanged.
 */
@Composable
private fun rememberTransferSnapshot(
    ui: com.alphamessenger.transfer.TransferUi?,
    key: com.alphamessenger.transfer.TransferKey?,
): com.alphamessenger.transfer.TransferSnapshot? {
    if (ui == null || key == null) return null
    val flow = remember(key.value) { ui.flowFor(key) }
    return flow.collectAsState().value
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageUi,
    showSender: Boolean,
    isGroup: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onAvatarClick: () -> Unit,
    colors: AlphaColorScheme,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSwipeReply: () -> Unit = {},
    onQuoteTap: (String) -> Unit = {},
    isHighlighted: Boolean = false,
    mediaLoader: (suspend (String) -> String?)? = null,
    cachedMediaPath: ((String) -> String?)? = null,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    mediaProgress: kotlinx.coroutines.flow.SharedFlow<Triple<String, Long, Long>>? = null,
    voiceController: VoicePlaybackController? = null,
    musicController: MusicPlaybackController? = null,
    onMediaClick: (path: String, messageId: String) -> Unit = { _, _ -> },
    onMediaActions: () -> Unit = {},
    onMediaSaveItem: (MessageUi) -> Unit = {},
    repository: com.alphamessenger.data.AlphaCoreRepository? = null,
    // Toggle long-text expansion while keeping the chat from jumping. The host
    // (which owns the LazyListState) records the bubble's position, runs `flip`,
    // and compensates the scroll. Default just flips (no re-anchor).
    onJoinInvite: (String) -> Unit = {},
    onViewOnceConsumed: (String) -> Unit = {},
    onCancelUpload: (String) -> Unit = {},
    onAlbumAction: (String, com.alphamessenger.transfer.TransferAction) -> Unit = { _, _ -> },
    onRetryUpload: (String) -> Unit = {},
    // Tap on a link/email/phone inside the text (TextRenderer → Bubble host).
    onEntityTap: (LinkAction) -> Unit = {},
    // Partial text-selection mode for THIS message (from the action sheet).
    textSelectActive: Boolean = false,
    // Tap on a reaction pill toggles MY reaction; long-press opens the picker.
    onReact: (String) -> Unit = {},
    onOpenReactions: () -> Unit = {},
    onShowReactions: () -> Unit = {},
) {
    if (WAVE_DIAG) RecompProbe.hit("BUBBLE:${waveTag(message)}") // TEMP wave/lag probe
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) colors.outBubble else colors.msgIn
    val textColor = if (message.isOutgoing) colors.msgOutText else colors.textPrimary
    // AlphaMessenger's classic TOP tail on the FIRST bubble of a same-sender run.
    // The BODY is a symmetric rounded rect (same width for every bubble → clean
    // vertical column). The tail is drawn OUTSIDE the body by MessageLayout
    // (drawBubbleTail). `showTail` here only squares the body's top-outer corner where
    // that tail attaches — it never changes the body width, so the column stays aligned.
    val showTail = hasTail(message)
    val inChannel = com.alphamessenger.ui.channel.LocalChannelPostWidth.current != null
    val bubbleRadius = com.alphamessenger.ui.theme.LocalBubbleRadius.current
    val bubbleShape = remember(message.isOutgoing, showTail, inChannel, bubbleRadius) {
        if (inChannel) androidx.compose.foundation.shape.RoundedCornerShape(com.alphamessenger.ui.channel.CHANNEL_BUBBLE_RADIUS)
        else bubbleShape(outgoing = message.isOutgoing, showTail = showTail, radius = bubbleRadius)
    }
    val senderColor = remember(message.senderId) { senderColorFor(message.senderId) }
    // MediaKind is fixed at creation and never flips → a stable bubble type.
    val mediaKind = remember(message.id) { com.alphamessenger.media.MediaKind.from(message) }

    // CONTACT CARD: a message that is exactly a peer's npub renders as a tappable
    // contact card (avatar + "Contact" + key) instead of raw text. Tap → bottom
    // sheet to add to friends / copy the key.
    val contactNpub = message.text.trim()
    if (contactNpub.startsWith("npub1") && contactNpub.length in 59..64 &&
        contactNpub.none { it.isWhitespace() }
    ) {
        ContactCardBubble(
            npub = contactNpub,
            isOutgoing = message.isOutgoing,
            colors = colors,
            repository = repository,
        )
        return
    }

    val onReactNow by androidx.compose.runtime.rememberUpdatedState(onReact)
    val onOpenReactionsNow by androidx.compose.runtime.rememberUpdatedState(onOpenReactions)
    val onShowReactionsNow by androidx.compose.runtime.rememberUpdatedState(onShowReactions)
    // What reactions it had when it came into view: kept here, where the message lives,
    // because a bubble builds its reaction row only once the first reaction comes.
    val reactionsAtStart = remember(message.id) { message.reactions.keys.toSet() }
    val reactionsState = remember(message.reactions, message.myReactions) {
        com.alphamessenger.ui.chat.bubbles.BubbleReactionsState(
            seenAtStart = reactionsAtStart,
            reactions = message.reactions,
            mine = message.myReactions,
            onToggle = { onReactNow(it) },
            onOpenPicker = { onOpenReactionsNow() },
            onShowAll = { onShowReactionsNow() },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.alphamessenger.ui.chat.bubbles.LocalBubbleReactions provides reactionsState,
    ) {
    MessageLayout(
        message = message,
        showSender = showSender,
        isGroup = isGroup,
        colors = colors,
        selectionMode = selectionMode,
        isSelected = isSelected,
        isHighlighted = isHighlighted,
        onTap = onTap,
        onLongPress = onLongPress,
        onSwipeReply = onSwipeReply,
        onAvatarClick = onAvatarClick,
        reactions = {
 // Reactions live INSIDE the bubble footer. Only a bubble
            // type without a footer slot (contact card, view-once) shows them here.
            if (!reactionsState.rendered) {
                com.alphamessenger.ui.chat.bubbles.BubbleReactionsRow(
                    isOutgoing = message.isOutgoing,
                    colors = colors,
                    modifier = Modifier.padding(top = 3.dp),
                    surface = com.alphamessenger.ui.chat.bubbles.ReactionSurface.Media,
                )
            }
        },
    ) {
            if (message.isViewOnce) {
                // View Once Media / Secret Note — never reveal content inline.
                // A card opens a fullscreen, screenshot-protected viewer; after
                // viewing the message is consumed and its blob is wiped.
                ViewOnceContent(
                    message = message,
                    bubbleColor = bubbleColor,
                    textColor = textColor,
                    bubbleShape = bubbleShape,
                    colors = colors,
                    isGroup = isGroup,
                    showSender = showSender,
                    senderColor = senderColor,
                    mediaLoader = mediaLoader,
                    onLongPress = onLongPress,
                    onConsumed = { onViewOnceConsumed(message.id) },
                )
            } else if (message.albumItems != null) {
                // Photo/video album (UI grouping of consecutive media).
                AlbumBubble(
                    items = message.albumItems!!,
                    head = message,
                    bubbleColor = bubbleColor,
                    textColor = textColor,
                    bubbleShape = bubbleShape,
                    showSenderName = showSender && isGroup && !message.isOutgoing,
                    senderColor = senderColor,
                    colors = colors,
                    onLongPress = onLongPress,
                    onActions = onMediaActions,
                    onSaveItem = onMediaSaveItem,
                    onOpenItem = { m -> openAlbumItem(m, cachedMediaPath, onMediaClick) },
                    mediaLoader = mediaLoader,
                    repository = repository,
                    mediaProgress = mediaProgress,
                    mediaActions = mediaActions,
                    onAlbumAction = onAlbumAction,
                )
            } else if (message.isMedia && message.mediaType == "location") {
                // Location → map card wrapped in a normal message bubble (colour,
                // time, status ticks). Tap opens the fullscreen interactive map.
                val loc = remember(message.mediaRefJson) { parseLocation(message.mediaRefJson) }
                if (loc != null) {
                    var locFull by remember { mutableStateOf(false) }
                    Column(
                        // ONE shell — see BubbleGeometry.bubbleShell. No width cap
                        // (Dp.Infinity == the previous no-widthIn); compact insets from
                        // BubbleTokens. Pixel-for-pixel identical.
                        modifier = Modifier.bubbleShell(
                            isOutgoing = message.isOutgoing,
                            showTail = hasTail(message),
                            bubbleShape = bubbleShape,
                            bubbleColor = bubbleColor,
                            maxWidth = androidx.compose.ui.unit.Dp.Infinity,
                            onTap = onMediaActions,
                            onLongPress = onLongPress,
                            contentPadding = BubbleTokens.compactPadding(message.isOutgoing, hasTail(message)),
                        ),
                    ) {
                        // Unified "Forwarded" badge — same as every other bubble type.
                        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
                        if (showSender && isGroup && !message.isOutgoing) {
                            Text(
                                message.senderName ?: "", color = senderColor, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                            )
                        }
                        LocationCard(loc, colors, textColor = textColor, onOpen = { locFull = true }, autoLoadMap = message.isOutgoing, mapKey = message.id)
                        com.alphamessenger.ui.chat.bubbles.BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 3.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
                            if (message.isEdited) com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.7f), endGap = 4.dp)
                            Text(compactTime(message.timestamp), color = textColor.copy(alpha = 0.7f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
                            if (message.isOutgoing) {
                                Spacer(Modifier.width(4.dp))
                                com.alphamessenger.ui.chat.bubbles.DeliveryTicks(message.status, textColor)
                            }
                        }
                    }
                    if (locFull) {
                        com.alphamessenger.ui.chat.map.LocationScreen(
                            lat = loc.lat,
                            lng = loc.lng,
                            name = loc.name,
                            address = loc.address,
                            onDismiss = { locFull = false },
                        )
                    }
                }
            } else if (message.isMedia && message.mediaType == "poll") {
                // A poll in a normal bubble (colour, sender, reactions, time, status).
                run {
                    Column(
                        // ONE shell — see BubbleGeometry.bubbleShell. Pixel-for-pixel identical.
                        modifier = Modifier.bubbleShell(
                            isOutgoing = message.isOutgoing,
                            showTail = hasTail(message),
                            bubbleShape = bubbleShape,
                            bubbleColor = bubbleColor,
                            maxWidth = 290.dp,
                            onTap = {},
                            onLongPress = onLongPress,
                            contentPadding = BubbleTokens.compactPadding(message.isOutgoing, hasTail(message)),
                        ),
                    ) {
                        // Unified "Forwarded" badge — same as every other bubble type.
                        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
                        if (showSender && isGroup && !message.isOutgoing) {
                            Text(
                                message.senderName ?: "", color = senderColor, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                            )
                        }
                        com.alphamessenger.ui.chat.poll.PollCard(
                            messageId = message.id,
                            ref = message.mediaRefJson,
                            outgoing = message.isOutgoing,
                            textColor = textColor,
                            accent = colors.accent,
                            voterName = com.alphamessenger.ui.chat.poll.LocalPollHost.current?.voterName ?: { it.take(8) },
                        )
                        com.alphamessenger.ui.chat.bubbles.BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 3.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
                            if (message.isEdited) com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.7f), endGap = 4.dp)
                            Text(compactTime(message.timestamp), color = textColor.copy(alpha = 0.7f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
                            if (message.isOutgoing) {
                                Spacer(Modifier.width(4.dp))
                                com.alphamessenger.ui.chat.bubbles.DeliveryTicks(message.status, textColor)
                            }
                        }
                    }
                }
            } else if (message.isMedia && message.mediaType == "link") {
                // Link preview card wrapped in a normal bubble (colour, caption,
                // time, status). Tap opens the URL in the browser.
                val link = remember(message.mediaRefJson) { parseLinkPreview(message.mediaRefJson) }
                if (link != null) {
                    Column(
                        // ONE shell — see BubbleGeometry.bubbleShell. Pixel-for-pixel identical.
                        modifier = Modifier.bubbleShell(
                            isOutgoing = message.isOutgoing,
                            showTail = hasTail(message),
                            bubbleShape = bubbleShape,
                            bubbleColor = bubbleColor,
                            maxWidth = 290.dp,
                            onTap = onMediaActions,
                            onLongPress = onLongPress,
                            contentPadding = BubbleTokens.compactPadding(message.isOutgoing, hasTail(message)),
                        ),
                    ) {
                        // Unified "Forwarded" badge — same as every other bubble type.
                        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
                        if (showSender && isGroup && !message.isOutgoing) {
                            Text(
                                message.senderName ?: "", color = senderColor, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                            )
                        }
                        message.mediaCaption?.takeIf { it.isNotBlank() }?.let { cap ->
                            // One shared TextRenderer for captions (links clickable).
                            MessageText(onAccent = message.isOutgoing, 
                                text = cap, color = textColor,
                                fontSize = (BubbleTokens.captionSp * com.alphamessenger.ui.theme.LocalMessageFontScale.current).sp,
                                lineHeight = (BubbleTokens.captionLineSp * com.alphamessenger.ui.theme.LocalMessageFontScale.current).sp,
                                selectionMode = textSelectActive,
                                onEntityTap = { entity ->
                                    entity.toLinkAction()?.let { action ->
                                        when (action) {
                                            is LinkAction.Invite -> if (com.alphamessenger.ui.sticker.isPackLink(action.link)) com.alphamessenger.ui.sticker.StickerUi.open(action.link, null)
                                                else if (com.alphamessenger.ui.theme.isThemeLink(action.link)) com.alphamessenger.ui.theme.ThemeUi.open(action.link) else onJoinInvite(action.link)
                                            else -> onEntityTap(action)
                                        }
                                    }
                                },
                                onTextTap = onMediaActions,
                                onTextLongPress = onLongPress,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                        val linkCtx = androidx.compose.ui.platform.LocalContext.current
                        // The bubble does not know how a preview is built — it only renders.
                        LinkPreviewRenderer(
                            model = LinkPreviewModel(
                                url = link.url,
                                title = link.title,
                                description = link.description,
                                image = link.image,
                                domain = link.site,
                                imageRef = link.imageRef,
                                video = link.video,
                            ),
                            colors = colors,
                            textColor = textColor,
                            onTap = { onEntityTap(LinkAction.OpenUrl(link.url)) },
                            onPlay = if (com.alphamessenger.ui.video.VideoPlayerActivity.canPlay(linkCtx, link.url)) {
                                { from -> com.alphamessenger.ui.video.VideoPlayerActivity.open(linkCtx, link.url, from) }
                            } else null,
                        )
                        com.alphamessenger.ui.chat.bubbles.BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 3.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
                            if (message.isEdited) com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.7f), endGap = 4.dp)
                            Text(compactTime(message.timestamp), color = textColor.copy(alpha = 0.7f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
                            if (message.isOutgoing) {
                                Spacer(Modifier.width(4.dp))
                                com.alphamessenger.ui.chat.bubbles.DeliveryTicks(message.status, textColor)
                            }
                        }
                    }
                }
            } else if (message.isMedia) {
                MediaReplyFrame(message, bubbleColor, bubbleShape, showTail, colors, onQuoteTap, onLongPress) {
                // One dispatch on the immutable kind (fixed at creation). The bubble
                // type is stable for the message's life — no mediaType re-derivation.
                when (mediaKind) {
                    com.alphamessenger.media.MediaKind.ALBUM -> AlbumBubble(
                        items = message.albumItems ?: emptyList(),
                        head = message,
                        bubbleColor = bubbleColor,
                        textColor = textColor,
                        bubbleShape = bubbleShape,
                        showSenderName = showSender && isGroup && !message.isOutgoing,
                        senderColor = senderColor,
                        colors = colors,
                        onLongPress = onLongPress,
                        onActions = onMediaActions,
                        onSaveItem = onMediaSaveItem,
                        onOpenItem = { m -> openAlbumItem(m, cachedMediaPath, onMediaClick) },
                        mediaLoader = mediaLoader,
                        repository = repository,
                        mediaProgress = mediaProgress,
                        mediaActions = mediaActions,
                        onAlbumAction = onAlbumAction,
                    )
                    com.alphamessenger.media.MediaKind.AUDIO, com.alphamessenger.media.MediaKind.VOICE -> AudioPlayerBubble(
                        message = message,
                        bubbleColor = bubbleColor,
                        textColor = textColor,
                        bubbleShape = bubbleShape,
                        showSenderName = showSender && isGroup && !message.isOutgoing,
                        senderColor = senderColor,
                        colors = colors,
                        onLongPress = onLongPress,
                        onMediaActions = onMediaActions,
                        mediaLoader = mediaLoader,
                        mediaProgress = mediaProgress,
                        mediaActions = mediaActions,
                        onCancelUpload = { onCancelUpload(message.id) },
                        onRetryUpload = { onRetryUpload(message.id) },
                        voiceController = voiceController,
                        musicController = musicController,
                    )
                    com.alphamessenger.media.MediaKind.VIDEO -> if (message.isGif) com.alphamessenger.ui.chat.bubbles.GifBubble(
                        message = message,
                        showSenderName = showSender && isGroup && !message.isOutgoing,
                        senderColor = senderColor,
                        colors = colors,
                        onLongPress = onLongPress,
                        mediaActions = mediaActions,
                        onCancelUpload = { onCancelUpload(message.id) },
                        onRetryUpload = { onRetryUpload(message.id) },
                    ) else if (message.isRoundVideo) com.alphamessenger.ui.chat.bubbles.RoundVideoBubble(
                        message = message,
                        showSenderName = showSender && isGroup && !message.isOutgoing,
                        senderColor = senderColor,
                        colors = colors,
                        onLongPress = onLongPress,
                        mediaActions = mediaActions,
                        onCancelUpload = { onCancelUpload(message.id) },
                        onRetryUpload = { onRetryUpload(message.id) },
                    ) else VideoBubble(
                        message = message,
                        bubbleColor = bubbleColor,
                        textColor = textColor,
                        bubbleShape = bubbleShape,
                        showSenderName = showSender && isGroup && !message.isOutgoing,
                        senderColor = senderColor,
                        colors = colors,
                        onLongPress = onLongPress,
                        onMediaActions = onMediaActions,
                        mediaLoader = mediaLoader,
                        mediaProgress = mediaProgress,
                        mediaActions = mediaActions,
                        onCancelUpload = { onCancelUpload(message.id) },
                        onRetryUpload = { onRetryUpload(message.id) },
                        textSelectActive = textSelectActive,
                        repository = repository,
                        // Video opens the same way as photo: the bubble hands the
                        // path up and ChatScreen opens the shared MediaViewer.
                        onOpenViewer = onMediaClick,
                    )
                    com.alphamessenger.media.MediaKind.FILE -> MediaFileBubble(
                        message = message,
                        bubbleColor = bubbleColor,
                        textColor = textColor,
                        bubbleShape = bubbleShape,
                        showSenderName = showSender && isGroup && !message.isOutgoing,
                        senderColor = senderColor,
                        colors = colors,
                        onTap = onTap,
                        onLongPress = onLongPress,
                        onMediaActions = onMediaActions,
                        mediaLoader = mediaLoader,
                        cachedMediaPath = cachedMediaPath,
                        mediaProgress = mediaProgress,
                        mediaActions = mediaActions,
                        onMediaClick = onMediaClick,
                        onCancelUpload = { onCancelUpload(message.id) },
                        onRetryUpload = { onRetryUpload(message.id) },
                        textSelectActive = textSelectActive,
                    )
                    com.alphamessenger.media.MediaKind.IMAGE -> if (message.isSticker) com.alphamessenger.ui.chat.bubbles.StickerBubble(
                        message = message,
                        showSenderName = showSender && isGroup && !message.isOutgoing,
                        senderColor = senderColor,
                        colors = colors,
                        onLongPress = onLongPress,
                        onOpenPack = {
                            if (message.stickerLink != null || message.stickerBuiltin != null)
                                com.alphamessenger.ui.sticker.StickerUi.open(message.stickerLink, message.stickerBuiltin)
                        },
                        mediaActions = mediaActions,
                    ) else MediaBubble(
                        message = message,
                        bubbleColor = bubbleColor,
                        textColor = textColor,
                        bubbleShape = bubbleShape,
                        showSenderName = showSender && isGroup && !message.isOutgoing,
                        senderColor = senderColor,
                        colors = colors,
                        onTap = onTap,
                        onLongPress = onLongPress,
                        mediaLoader = mediaLoader,
                        cachedMediaPath = cachedMediaPath,
                        mediaProgress = mediaProgress,
                        mediaActions = mediaActions,
                        onCancelUpload = { onCancelUpload(message.id) },
                        onRetryUpload = { onRetryUpload(message.id) },
                        textSelectActive = textSelectActive,
                        onMediaClick = onMediaClick,
                    )
                }
                }
            } else if (!message.isDeleted && message.replyTo == null && message.forwardedFrom == null &&
                com.alphamessenger.ui.emoji.bigEmojiOf(message.text) != null
            ) {
                // One emoji and nothing else: large, alone, animated when the set has it.
                com.alphamessenger.ui.chat.bubbles.EmojiBubble(
                    message = message,
                    emoji = message.text.trim(),
                    showSenderName = showSender && isGroup && !message.isOutgoing,
                    senderColor = senderColor,
                    colors = colors,
                    onLongPress = onLongPress,
                )
            } else {
            val textBody: @Composable (Color, Float, Float, (TextLayoutResult) -> Unit) -> Unit = { bodyColor, sizeSp, lineSp, onTextLayout ->
                    if (message.isDeleted) {
                        Text(
                            text = stringResource(R.string.chat_message_deleted),
                            color = bodyColor.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Light,
                            onTextLayout = onTextLayout,
                        )
                    } else {
                        // Nothing unfolds in place, so the list never moves under the
                        // reader: a long text opens whole on its own screen.
                        var readWhole by remember(message.id) { mutableStateOf(false) }
                        val inviteLink = extractInviteLink(message.text)
                        // If the message is ONLY an invite link, hide the ugly raw
                        // URL and show just the clean join card below.
                        val textIsOnlyInvite = inviteLink != null &&
                            message.text.trim() == inviteLink
                        // A sticker pack link: a card, never the long raw link.
                        val packLink = com.alphamessenger.ui.sticker.extractPackLink(message.text)
                        val textIsOnlyPack = packLink != null && message.text.trim() == packLink
                        // A theme link: a card with its colours, never the long raw link.
                        val themeLink = com.alphamessenger.ui.theme.extractThemeLink(message.text)
                        val textIsOnlyTheme = themeLink != null && message.text.trim() == themeLink
                        Column {
                            if (!textIsOnlyInvite && !textIsOnlyPack && !textIsOnlyTheme) {
                            // ONE TextRenderer: links/email/phone/invite are
                            // clickable inside the text (text tap ≠ bubble tap); in
                            // selection mode — partial selection + range copy.
                            MessageText(onAccent = message.isOutgoing, 
                                text = message.text,
                                color = bodyColor,
                                fontSize = sizeSp.sp,
                                lineHeight = lineSp.sp,
                                selectionMode = textSelectActive,
                                onEntityTap = { entity ->
                                    entity.toLinkAction()?.let { action ->
                                        when (action) {
                                            is LinkAction.Invite -> if (com.alphamessenger.ui.sticker.isPackLink(action.link)) com.alphamessenger.ui.sticker.StickerUi.open(action.link, null)
                                                else if (com.alphamessenger.ui.theme.isThemeLink(action.link)) com.alphamessenger.ui.theme.ThemeUi.open(action.link) else onJoinInvite(action.link)
                                            else -> onEntityTap(action)
                                        }
                                    }
                                },
                                onTextTap = onTap,
                                onTextLongPress = onLongPress,
                                onTextLayout = onTextLayout,
                                previewChars = com.alphamessenger.ui.chat.text.previewLimit(),
                                onReadMore = { readWhole = true },
                            )
                            }
                            if (readWhole) com.alphamessenger.ui.chat.text.LongMessageScreen(message.text) { readWhole = false }
                            packLink?.let { link ->
                                if (!textIsOnlyPack) Spacer(Modifier.height(6.dp))
                                com.alphamessenger.ui.sticker.StickerPackCard(link, colors, onAccentBubble = message.isOutgoing)
                            }
                            themeLink?.let { link ->
                                if (!textIsOnlyTheme) Spacer(Modifier.height(6.dp))
                                com.alphamessenger.ui.theme.ThemeLinkCard(link, colors, onAccentBubble = message.isOutgoing)
                            }
                            // A group invite link → tappable join card.
                            inviteLink?.let { invite ->
                                if (!textIsOnlyInvite) Spacer(Modifier.height(6.dp))
                                InviteJoinCard(invite, colors, onAccentBubble = message.isOutgoing) {
                                    onJoinInvite(invite)
                                }
                            }
                        }
                    }
            }
            val fontScale = com.alphamessenger.ui.theme.LocalMessageFontScale.current
            if (LocalPlainIncoming.current && !message.isOutgoing) {
                PlainAnswer(message, colors, onTap, onLongPress) {
                    textBody(colors.textPrimary, PLAIN_TEXT_SP * fontScale, PLAIN_LINE_SP * fontScale) {}
                }
            } else
            CompactBubbleLayout(
                // ONE shell for shape/clip/background/click/width/padding — see
                // BubbleGeometry.bubbleShell. Default contentPadding is exactly the
                // previous rule, so the text bubble is pixel-for-pixel unchanged.
                modifier = Modifier.bubbleShell(
                    isOutgoing = message.isOutgoing,
                    showTail = showTail,
                    bubbleShape = bubbleShape,
                    bubbleColor = bubbleColor,
                    maxWidth = com.alphamessenger.ui.channel.LocalChannelPostWidth.current ?: BubbleTokens.textBubbleMaxW,
                    onTap = onTap,
                    onLongPress = onLongPress,
                ),
                senderName = run {
                    val showName = showSender && isGroup && !message.isOutgoing
                    val fwd = message.forwardedFrom != null
                    if (showName || fwd) {
                        {
                            Column {
                                if (fwd) ForwardedLabel(message.isOutgoing, colors)
                                if (showName) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = message.senderName ?: "",
                                            color = senderColor,
                                            fontSize = BubbleTokens.senderNameSp.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        // A channel admin's comment carries the badge (the owner stays hidden).
                                        if (message.senderId in com.alphamessenger.ui.components.LocalAdminSenders.current) {
                                            com.alphamessenger.ui.components.RolePill("admin")
                                        }
                                    }
                                }
                            }
                        }
                    } else null
                },
                quotedReply = message.replyTo?.let { reply ->
                    {
                        QuotedReply(
                            reply.senderName, reply.text,
                            posterB64 = reply.posterB64,
                            thumbPath = reply.thumbPath,
                            isVideoQuote = reply.isVideoQuote,
                            kind = reply.kind,
                            fileName = reply.fileName,
                            isOutgoing = message.isOutgoing, colors = colors,
                            onClick = message.quotedMessageId?.let { qid -> { onQuoteTap(qid) } },
                        )
                    }
                },
                messageText = { onTextLayout ->
                    textBody(textColor, BubbleTokens.textBodySp * fontScale, BubbleTokens.textBodyLineSp * fontScale, onTextLayout)
                },
                reactions = if (message.reactions.isNotEmpty()) {
                    { com.alphamessenger.ui.chat.bubbles.BubbleReactionsRow(isOutgoing = message.isOutgoing, colors = colors) }
                } else null,
                statusRow = {
                    // An answer still being written has no time yet: the time hopping between
                    // the last line and a line of its own made the growing bubble jump.
                    if (!message.assistantWriting) Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (message.isEdited) {
                            com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.65f))
                            Spacer(Modifier.width(6.dp))
                        }
                        com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
                        Text(
                            text = compactTime(message.timestamp),
                            color = textColor.copy(alpha = 0.7f),
                            fontSize = BubbleTokens.footerTimeSp.sp,
                        )
                        if (message.isOutgoing) {
                            Spacer(Modifier.width(4.dp))
                            com.alphamessenger.ui.chat.bubbles.DeliveryTicks(message.status, textColor)
                        }
                    }
                }
            )
            }

            // In selection mode: transparent overlay on top of the bubble that
            // consumes all touches, preventing media/file opens.
            if (selectionMode) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
                        }
                )
            }
        }
    }
    }

private const val PLAIN_TEXT_SP = 16.5f
private const val PLAIN_LINE_SP = 24f

/**
 * An assistant's answer: text on the chat itself, full width, no bubble; a tap or a hold
 * acts as on any message.
 */
@Composable
private fun PlainAnswer(
    message: MessageUi,
    colors: AlphaColorScheme,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    body: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
    ) {
        body()
        if (message.reactions.isNotEmpty()) {
            com.alphamessenger.ui.chat.bubbles.BubbleReactionsRow(isOutgoing = false, colors = colors, modifier = Modifier.padding(top = 6.dp))
        }
        // Still being written: no time yet, so the end of the text never moves.
        if (!message.assistantWriting) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (message.isEdited) {
                com.alphamessenger.ui.chat.bubbles.EditedMark(colors.textMuted)
                Spacer(Modifier.width(6.dp))
            }
            Text(compactTime(message.timestamp), color = colors.textMuted, fontSize = BubbleTokens.footerTimeSp.sp)
        }
    }
}

/**
 * A media reply. Media in a bubble (voice, music, file, photo, video, album) takes the
 * quote at the top of that same bubble, as a text reply does. A sticker, a GIF or a
 * circle has no bubble: its quote rides above it as a small card in the bubble's colour.
 */
@Composable
private fun MediaReplyFrame(
    message: MessageUi,
    bubbleColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    showTail: Boolean,
    colors: AlphaColorScheme,
    onQuoteTap: (String) -> Unit,
    onLongPress: () -> Unit,
    media: @Composable () -> Unit,
) {
    val reply = message.replyTo
    val free = message.isStickerLike || message.isGif || message.isRoundVideo
    if (reply != null && !message.isDeleted && !free) {
        val pad = com.alphamessenger.ui.chat.bubbles.BubbleTokens.contentPadding(message.isOutgoing, showTail)
        val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
        androidx.compose.ui.layout.Layout(
            content = {
                Box(Modifier.padding(start = pad.calculateStartPadding(ld), end = pad.calculateEndPadding(ld), top = pad.calculateTopPadding())) {
                    QuotedReply(
                        reply.senderName, reply.text,
                        posterB64 = reply.posterB64,
                        thumbPath = reply.thumbPath,
                        isVideoQuote = reply.isVideoQuote,
                        kind = reply.kind,
                        fileName = reply.fileName,
                        isOutgoing = message.isOutgoing, colors = colors,
                        onClick = message.quotedMessageId?.let { qid -> { onQuoteTap(qid) } },
                    )
                }
                // One fill and one tail for the whole message: the media's own shell paints none.
                androidx.compose.runtime.CompositionLocalProvider(com.alphamessenger.ui.chat.bubbles.LocalBubbleFilled provides true) { media() }
            },
            modifier = Modifier.bubbleFill(message.isOutgoing, bubbleShape, bubbleColor)
                .pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) },
        ) { parts, constraints ->
            // The media sets the width (as in any media bubble); the quote fits it.
            val body = parts[1].measure(constraints)
            val quote = parts[0].measure(androidx.compose.ui.unit.Constraints.fixedWidth(body.width))
            layout(body.width, quote.height + body.height) {
                quote.place(0, 0)
                body.place(0, quote.height)
            }
        }
        return
    }
    // A sticker, a GIF or a circle stands free with its own side inset: the card lines
    // up with that edge, so the row (and its highlight) hold both as one.
    val freeInset = if (free) 8.dp else 0.dp
    if (reply == null || message.isDeleted) media() else Column(
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.padding(horizontal = freeInset).widthIn(max = BubbleTokens.textBubbleMaxW).width(IntrinsicSize.Max)
                .clip(RoundedCornerShape(12.dp)).background(bubbleColor).padding(4.dp),
        ) {
            QuotedReply(
                reply.senderName, reply.text,
                posterB64 = reply.posterB64,
                thumbPath = reply.thumbPath,
                isVideoQuote = reply.isVideoQuote,
                kind = reply.kind,
                fileName = reply.fileName,
                isOutgoing = message.isOutgoing, colors = colors,
                onClick = message.quotedMessageId?.let { qid -> { onQuoteTap(qid) } },
            )
        }
        media()
    }
}

@Composable
private fun QuotedReply(
    senderName: String,
    text: String,
    isOutgoing: Boolean,
    colors: AlphaColorScheme,
    posterB64: String? = null,
    thumbPath: String? = null,
    isVideoQuote: Boolean = false,
    kind: String? = null,
    fileName: String? = null,
    onClick: (() -> Unit)? = null,
) {
    // Incoming: a tint of the accent, never a surface colour of the theme — in
    // Grey that one equals the bubble and the quote vanished.
    val bg = if (isOutgoing) Color.White.copy(alpha = 0.15f) else colors.accent.copy(alpha = 0.14f)
    val barColor = if (isOutgoing) Color.White.copy(alpha = 0.85f) else colors.accent
    val nameColor = if (isOutgoing) Color.White else colors.accent
    val textColor = if (isOutgoing) Color.White.copy(alpha = 0.85f) else colors.textSecondary
    // Decode the quoted media thumbnail (video poster or local image), if any —
    // OFF the UI thread with an LRU cache, so a reply bubble never decodes a
    // bitmap during composition/scroll.
    val quoteCtx = androidx.compose.ui.platform.LocalContext.current
    val thumbPath = thumbPath?.let { p ->
        if (p.startsWith("hash:")) java.io.File(quoteCtx.cacheDir, "media/" + p.removePrefix("hash:").filter { it.isLetterOrDigit() })
            .takeIf { it.length() > 0 }?.absolutePath else p
    }
    val thumbKey = posterB64?.takeIf { it.isNotBlank() }?.let { "b:$it" }
        ?: thumbPath?.takeIf { it.isNotBlank() }?.let { "f:$it" }
    val thumb = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        thumbKey?.let { QuoteThumbCache.get(it) }, thumbKey,
    ) {
        val k = thumbKey
        if (value != null || k == null) return@produceState
        value = withContext(Dispatchers.IO) {
            QuoteThumbCache.get(k) ?: runCatching {
                when {
                    !posterB64.isNullOrBlank() -> {
                        val b = android.util.Base64.decode(posterB64, android.util.Base64.DEFAULT)
                        com.alphamessenger.util.BoundedBitmap.decode(b)?.asImageBitmap()
                    }
                    !thumbPath.isNullOrBlank() ->
                        com.alphamessenger.data.MediaVault.decodeFile(thumbPath)?.asImageBitmap()
                    else -> null
                }
            }.getOrNull()?.also { QuoteThumbCache.put(k, it) }
        }
    }.value
    // For media without an image thumbnail (file / voice / music), show a typed
    // icon tile so every media reply looks consistent.
    val tileIcon: androidx.compose.ui.graphics.vector.ImageVector? = when {
        thumb != null -> null
        kind == "voice" -> AlphaIcons.Outlined.Mic
        kind == "audio" -> AlphaIcons.Outlined.MusicNote
        kind == "file" -> com.alphamessenger.ui.chat.bubbles.fileTypeIcon(fileName?.substringAfterLast('.', ""))
        else -> null
    }
    Row(
        modifier = Modifier
            // Fills the bubble width (the layout hands it the final width); the
            // thumbnail sits at the right edge, the text takes the middle.
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min)
            .padding(start = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(barColor),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(senderName, color = nameColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = text,
                color = textColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (thumb != null) {
            Box(
                modifier = Modifier
                    .padding(end = 5.dp, top = 4.dp, bottom = 4.dp)
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                if (isVideoQuote) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AlphaIcons.Filled.PlayArrow, null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
        } else if (tileIcon != null) {
            Box(
                modifier = Modifier
                    .padding(end = 5.dp, top = 4.dp, bottom = 4.dp)
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(barColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tileIcon, null, tint = nameColor, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// Built ONCE. `DateTimeFormatter.ofPattern(...)` parses the pattern + allocates a
// formatter on every call — and compactTime runs on EVERY bubble mount during scroll.
private val HH_MM_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
internal fun compactTime(timestamp: String): String {
    localZdt(timestamp)?.let {
        return it.format(HH_MM_FORMATTER)
    }
    val idx = timestamp.indexOf('T')
    return if (idx in 0..timestamp.length - 6) timestamp.substring(idx + 1, idx + 6) else timestamp
}

// --- Reply preview row above the input ---

@Composable
/** Media-aware label for the reply preview bar (mirrors the sent quote): text
 *  when present, otherwise a typed media label instead of a blank line. */
private fun replyPreviewLabel(m: MessageUi): String {
    val t = m.text.trim()
    if (t.isNotEmpty()) return t
    return when {
        m.isVoiceNote || m.isVoice -> stringResource(R.string.chat_reply_voice)
        m.mediaType == "album" -> {
            val items = m.albumItems.orEmpty()
            when {
                items.isNotEmpty() && items.all { it.mediaType == "video" } -> stringResource(R.string.chat_reply_video)
                items.isNotEmpty() && items.all { it.mediaType == "image" } -> stringResource(R.string.chat_reply_photo)
                else -> stringResource(R.string.chat_reply_media)
            }
        }
        m.mediaType == "image" -> stringResource(R.string.chat_reply_photo)
        m.isGif -> "GIF"
        m.isSticker -> listOfNotNull(m.stickerEmoji, stringResource(R.string.sticker_label)).joinToString(" ")
        m.mediaType == "poll" -> "📊 " + (com.alphamessenger.ui.chat.poll.PollSpec.parse(m.mediaRefJson)?.question ?: stringResource(R.string.poll_label))
        m.isRoundVideo -> stringResource(R.string.round_label)
        m.mediaType == "video" -> stringResource(R.string.chat_reply_video)
        m.mediaType == "audio" -> stringResource(R.string.chat_reply_music)
        m.mediaType == "file" -> stringResource(R.string.chat_reply_file)
        // A link with a picture is a link, not an "attachment".
        m.mediaType == "link" -> {
            val d = remember(m.mediaRefJson) { parseLinkPreview(m.mediaRefJson) }
            m.mediaCaption?.removePrefix(FWD_MARK)?.trim()?.takeIf { it.isNotEmpty() }
                ?: d?.title?.takeIf { it.isNotBlank() } ?: d?.url ?: stringResource(R.string.chat_reply_link)
        }
        m.mediaType == "location" -> {
            val d = remember(m.mediaRefJson) { parseLocation(m.mediaRefJson) }
            d?.name?.takeIf { it.isNotBlank() } ?: d?.address?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_reply_location)
        }
        m.isMedia -> stringResource(R.string.chat_reply_attachment)
        else -> stringResource(R.string.msg_fallback_title)
    }
}

@Composable
private fun ReplyPreview(
    senderName: String,
    text: String,
    onClose: () -> Unit,
    colors: AlphaColorScheme,
    /** What is quoted: gives the full line (track, file name, length) and the thumbnail. */
    message: MessageUi? = null,
    /** A ready title (editing); null = "Reply to <sender>". */
    title: String? = null,
) {
    val text = com.alphamessenger.ui.chat.text.Markup.plain(text)
    val detail = message?.let { replyDetail(it) } ?: text
    // A quote card at the top of the input field (not a strip of its own).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.accent.copy(alpha = 0.10f))
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title ?: stringResource(R.string.chat_reply_to, senderName),
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A place gets two tight lines (name, address, country) in the same height.
            val twoLines = message?.mediaType == "location"
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = if (twoLines) 11.sp else 12.sp,
                lineHeight = if (twoLines) 13.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                maxLines = if (twoLines) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // On the right: what is quoted — its picture, or a tile with its kind —
        // and the ✕ on top of it.
        Box(Modifier.size(44.dp)) {
            if (message != null && (message.isMedia || message.albumItems != null)) {
                ReplyThumb(message, colors, Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)))
            }
            Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AlphaIcons.Outlined.Close, contentDescription = stringResource(R.string.chat_cancel_reply_a11y), tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** The full line for a quoted message: text, or what the attachment is. */
@Composable
private fun replyDetail(m: MessageUi): String {
    val caption = m.mediaCaption?.removePrefix(FWD_MARK)?.trim().orEmpty()
    val text = m.text.removePrefix(FWD_MARK).trim()
    val dur = m.mediaDurationMs?.takeIf { it > 0 }?.let { fmtMs(it.toInt()) }
    val size = m.mediaSizeBytes?.takeIf { it > 0 }?.let { com.alphamessenger.ui.chat.bubbles.humanSize(it) }
    return when {
        m.isVoiceNote || m.isVoice -> listOfNotNull(stringResource(R.string.chat_reply_voice), dur).joinToString(" · ")
        // How many only when there is more than one.
        m.albumItems != null -> caption.ifBlank {
            replyPreviewLabel(m) + if (m.albumItems.size > 1) " · " + m.albumItems.size else ""
        }
        m.mediaType == "audio" -> {
            // Artist — title from the file's tags when it is here, else its name.
            val path = m.localMediaPath ?: m.mediaRefJson?.let { MediaPathCache[it] }
            val tags = path?.let { MusicTagCache[it] }
            val name = tags?.let { (t, a) -> listOfNotNull(a, t).joinToString(" — ").ifBlank { null } }
                ?: m.mediaFilename?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.chat_reply_music)
            listOfNotNull(name, dur).joinToString(" · ")
        }
        m.mediaType == "file" -> listOfNotNull(m.mediaFilename?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_reply_file), size).joinToString(" · ")
        m.isGif -> "GIF"
        m.isSticker -> listOfNotNull(m.stickerEmoji, stringResource(R.string.sticker_label)).joinToString(" ")
        m.mediaType == "poll" -> "📊 " + (com.alphamessenger.ui.chat.poll.PollSpec.parse(m.mediaRefJson)?.question ?: stringResource(R.string.poll_label))
        m.isRoundVideo -> listOfNotNull(stringResource(R.string.round_label), dur).joinToString(" · ")
        m.mediaType == "video" -> caption.ifBlank { listOfNotNull(stringResource(R.string.chat_reply_video), dur).joinToString(" · ") }
        m.mediaType == "image" -> caption.ifBlank { stringResource(R.string.chat_reply_photo) }
        m.mediaType == "location" -> {
            // Place, then the address and the country — all of it, two short lines.
            val d = remember(m.mediaRefJson) { parseLocation(m.mediaRefJson) }
            listOfNotNull(
                d?.name?.takeIf { it.isNotBlank() },
                d?.address?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { stringResource(R.string.chat_reply_location) }
        }
        m.mediaType == "link" -> {
            val d = remember(m.mediaRefJson) { parseLinkPreview(m.mediaRefJson) }
            listOfNotNull(
                d?.title?.takeIf { it.isNotBlank() },
                (d?.site?.takeIf { it.isNotBlank() } ?: d?.url?.let { com.alphamessenger.ui.chat.preview.LinkPreviewModel.domainOf(it) })?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { caption.ifBlank { d?.url ?: stringResource(R.string.chat_reply_link) } }
        }
        text.isNotEmpty() -> text
        else -> replyPreviewLabel(m)
    }
}

/** The quoted attachment's picture, or a tile naming its kind (APK, PDF, music…). */
@Composable
private fun ReplyThumb(m: MessageUi, colors: AlphaColorScheme, modifier: Modifier) {
    val visual = m.albumItems?.firstOrNull() ?: m
    val linkImage = if (m.mediaType == "link") remember(m.mediaRefJson) { parseLinkPreview(m.mediaRefJson)?.image?.takeIf { it.isNotBlank() } } else null
    val linkBitmap = linkImage?.let { img ->
        val key = "lp:" + img.hashCode() + ":" + img.length
        androidx.compose.runtime.produceState(com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.get(key), key) {
            if (value != null) return@produceState
            value = withContext(Dispatchers.IO) {
                com.alphamessenger.ui.chat.preview.LinkPreviewFetcher.decodeInline(img)?.asImageBitmap()
                    ?.also { com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache.put(key, it) }
            }
        }.value
    }
    val poster = linkBitmap ?: if (visual.mediaType == "image" || visual.mediaType == "video") rememberPosterBitmap(visual.mediaPosterB64) else null
    Box(modifier.background(colors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        if (poster != null) {
            androidx.compose.foundation.Image(poster, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
            if (visual.mediaType == "video") {
                Icon(AlphaIcons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        } else {
            val ext = m.mediaFilename?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 4 }?.uppercase()
            when {
                m.isVoiceNote || m.isVoice -> Icon(AlphaIcons.Outlined.Mic, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                m.mediaType == "link" -> Icon(AlphaIcons.Outlined.Link, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                m.mediaType == "location" -> Icon(AlphaIcons.Outlined.Place, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                m.mediaType == "audio" -> Icon(AlphaIcons.Outlined.MusicNote, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                visual.mediaType == "video" -> Icon(AlphaIcons.Filled.PlayArrow, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                visual.mediaType == "image" -> Icon(AlphaIcons.Outlined.Image, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                ext != null -> Text(ext, color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                else -> Icon(AlphaIcons.Outlined.InsertDriveFile, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** classic "Forwarded" preview above the composer when a forward is staged. */
@Composable
private fun ForwardPreview(
    text: String,
    onClose: () -> Unit,
    colors: AlphaColorScheme,
) {
    // A quote card at the top of the input field (not a strip of its own).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.accent.copy(alpha = 0.10f))
            .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AlphaIcons.Filled.KeyboardDoubleArrowRight,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.common_forward),
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = text,
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(AlphaIcons.Outlined.Close, contentDescription = stringResource(R.string.chat_cancel_forward_a11y), tint = colors.textMuted)
        }
    }
}

// --- Gallery-picker caption + send bar (MAIN window → rises with the keyboard). ---

@Composable
private fun PickerCaptionBar(
    colors: AlphaColorScheme,
    caption: androidx.compose.ui.text.input.TextFieldValue,
    count: Int,
    focusRequester: FocusRequester,
    onCaptionChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onFocused: () -> Unit,
    onSend: () -> Unit,
) {
    val remaining = MAX_MESSAGE_CHARS - caption.text.length
    val overLimit = remaining < 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(colors.bgPrimary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            com.alphamessenger.ui.chat.v2.PlatformTextField(
                value = caption,
                onEdit = onCaptionChange,
                onSelect = { onCaptionChange(caption.copy(selection = it)) },
                hint = stringResource(R.string.chat_caption_hint),
                colors = colors,
                textSizeSp = 15f,
                lineHeightSp = 20f,
                minHeight = 20.dp,
                maxHeight = 80.dp,
                padding = com.alphamessenger.ui.chat.v2.FieldPadding(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier.fillMaxWidth(),
                scrollBar = false,
                onTapped = onFocused,
            )
        }
        if (remaining <= 200) {
            Text(
                "$remaining",
                color = if (overLimit) Color(0xFFEB5757) else colors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (overLimit) colors.textMuted else colors.accent)
                .clickable(enabled = !overLimit, onClick = onSend)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Icon(
                AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.common_send),
                tint = Color.White, modifier = Modifier.size(20.dp),
            )
        }
    }
}

// --- Tap-to-record voice panel — blinking mic + live equalizer + pause/cancel/send ---

@Composable
private fun VoiceRecordPanel(
    seconds: Int,
    paused: Boolean,
    ticks: kotlinx.coroutines.flow.SharedFlow<RecorderTick>,
    colors: AlphaColorScheme,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    keepKeyboard: Boolean = false,
) {
    // Waveform lives HERE (not in ChatState): collect the 10 Hz mic stream and
    // keep the last 48 samples locally, so only this panel repaints.
    val waveform = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        ticks.collect { t ->
            waveform.add(t.amplitude)
            while (waveform.size > 48) waveform.removeAt(0)
        }
    }
    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val micAlpha by blink.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "mic",
    )
    Box(modifier = Modifier.fillMaxWidth()) {
      if (keepKeyboard) {
        // Invisible focus anchor keeps the IME up during hands-free recording —
        // this panel has no text field to hold focus.
        val anchorFocus = remember { FocusRequester() }
        val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        androidx.compose.foundation.text.BasicTextField(
            value = "", onValueChange = {},
            modifier = Modifier.size(1.dp).graphicsLayer(alpha = 0f).focusRequester(anchorFocus),
        )
        LaunchedEffect(Unit) { runCatching { anchorFocus.requestFocus() }; kb?.show() }
      }
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.bgSecondary)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AlphaIcons.Outlined.Mic, contentDescription = null,
            tint = Color(0xFFEB5757).copy(alpha = if (paused) 0.35f else micAlpha),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(fmtMs(seconds * 1000), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        // Live equalizer (recent amplitudes, scrolling right).
        androidx.compose.foundation.Canvas(modifier = Modifier.weight(1f).height(30.dp)) {
            val slots = 48
            val barW = size.width / slots
            val minH = 2.dp.toPx()
            waveform.takeLast(slots).forEachIndexed { i, a ->
                val h = (a * size.height).coerceAtLeast(minH)
                val x = i * barW + barW / 2
                drawLine(
                    color = colors.accent,
                    start = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2),
                    end = androidx.compose.ui.geometry.Offset(x, (size.height + h) / 2),
                    strokeWidth = barW * 0.55f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onPauseResume, modifier = Modifier.size(40.dp)) {
            Icon(
                if (paused) AlphaIcons.Filled.PlayArrow else AlphaIcons.Filled.Pause,
                contentDescription = if (paused) stringResource(R.string.common_continue) else stringResource(R.string.editor_pause),
                tint = colors.textPrimary, modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
            Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = colors.textMuted, modifier = Modifier.size(22.dp))
        }
        Box(
            modifier = Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF34C759))
                .clickable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.common_send), tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
    }
}

// --- Input bar — emoji, text field, attach, mic→send ---

/**
 * Architectural seam (Step 1 of the bottom-area refactor). NOT the owner yet.
 *
 * No layout. No measurements. No state. No behavior. It adds NO Compose layout
 * node — [content] runs in the SAME [ColumnScope], so weight / constraints /
 * placement are byte-for-byte unchanged. This is only a named place into which
 * InputBar / Attach / Caption / Emoji / Recording will migrate in later steps;
 * it will BECOME the single bottom-area owner then, not now.
 *
 * ALLOWED here: `content()` and nothing else. FORBIDDEN until later steps:
 * Column / Box / Layout / SubcomposeLayout / AnimatedVisibility / remember /
 * rememberSaveable / LaunchedEffect / DisposableEffect / WindowInsets / any
 * Modifier (padding / offset / clip / background / height / weight). Keep empty.
 */
// BottomSurface is now a real permanent Column Layout that owns the bottom area.
// It always renders Divider + InputBar (never destroyed) + BottomPanel (one slot
// whose height = bottomPanelHeight). The trailing-lambda form below keeps the
// call site in ColumnScope and lets the children participate in the parent
// Column's weight/constraints — same as before, but the children are now
// permanent (no if/else if around them).
@Composable
private fun ColumnScope.BottomSurface(content: @Composable ColumnScope.() -> Unit) {
    content()
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onEmoji: () -> Unit,
    onCamera: () -> Unit = {},
    /** Force the SEND button (not mic) even with empty text — used for forwarding
     *  staged messages with no comment. */
    forceSend: Boolean = false,
    colors: AlphaColorScheme,
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    onStartRecord: () -> Unit = {},
    onFinishRecord: () -> Unit = {},
    onCancelRecord: () -> Unit = {},
    onTapLockRecord: () -> Unit = {},
    /** True when the inline emoji panel is open → show the ⌨️ icon instead of 😊. */
    emojiOpen: Boolean = false,
    /** Lets the host pop the keyboard (emoji → keyboard swap). */
    inputFocusRequester: FocusRequester? = null,
    /** The text field gained focus (host switches emoji→keyboard). */
    onInputFocused: () -> Unit = {},
) {
    val hasText = value.isNotBlank() || forceSend
    // A blinking microphone shared by the recording strip and the held button.
    val blink = rememberInfiniteTransition(label = "rec")
    val micAlpha by blink.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "mic",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      // Keep the text field composed while recording so it keeps IME focus and
      // the keyboard stays up; the recording strip overlays it (no jump).
      Box(modifier = Modifier.weight(1f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.bgTertiary)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onEmoji, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (emojiOpen) AlphaIcons.Outlined.Keyboard else AlphaIcons.Outlined.SentimentSatisfied,
                    contentDescription = if (emojiOpen) stringResource(R.string.preview_keyboard) else stringResource(R.string.chat_emoji),
                    tint = colors.textMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
            // BasicTextField with `maxLines` caps the visible row count;
            // once you hit the cap the field stops growing and the cursor
            // keeps writing as the content scrolls internally.
            // We further wrap the inner area in a scrollable column so the
            // user can drag through old lines too.
            val scrollState = rememberScrollState()
            // Use TextFieldValue so a programmatic prefill (editing a caption)
            // lands the cursor at the END, not at position 0. We mirror the
            // external String `value`; when it changes from the outside we
            // re-seed the cursor to the text end.
            var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
            LaunchedEffect(value) {
                if (value != tfv.text) tfv = TextFieldValue(value, TextRange(value.length))
            }
            BasicTextField(
                value = tfv,
                onValueChange = {
                    // Allow typing/pasting OVER the limit (no silent truncation /
                    // data loss). The counter turns red and the send button is
 // disabled until the user trims it — as messengers usually do.
                    tfv = it
                    if (it.text != value) onValueChange(it.text)
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 140.dp)
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp, horizontal = 4.dp)
                    .then(if (inputFocusRequester != null) Modifier.focusRequester(inputFocusRequester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) onInputFocused()
                    },
                textStyle = LocalTextStyle.current.copy(
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                ),
                cursorBrush = SolidColor(colors.accent),
                // Auto-capitalize the first letter of each sentence.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 6,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                stringResource(R.string.msg_fallback_title),
                                color = colors.textMuted,
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    }
                },
            )
 // Char counter near the cap — shows how many left.
            val remaining = MAX_MESSAGE_CHARS - value.length
            if (remaining <= 200) {
                Text(
                    text = "$remaining",
                    color = if (remaining <= 0) Color(0xFFEB5757) else colors.textMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            IconButton(onClick = onAttach, modifier = Modifier.size(36.dp)) {
                Icon(
                    AlphaIcons.Outlined.AttachFile,
                    contentDescription = stringResource(R.string.chat_attach_a11y),
                    tint = colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Camera only when the field is empty (hide once user is typing).
            if (!hasText) {
                IconButton(
                    onClick = onCamera,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        AlphaIcons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.perm_camera),
                        tint = colors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (isRecording) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.bgSecondary)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AlphaIcons.Outlined.Mic,
                    contentDescription = null,
                    tint = Color(0xFFEB5757).copy(alpha = micAlpha),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(fmtMs(recordingSeconds * 1000), color = colors.textPrimary, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.chat_swipe_to_cancel), color = colors.textMuted, fontSize = 13.sp)
            }
        }
      }
        Spacer(Modifier.width(8.dp))
        // Send button when there's text; otherwise a hold-to-record mic
        // (press = start, release = send, swipe left past threshold = cancel).
        // The button stays mounted while recording (so the gesture isn't lost)
        // and grows + turns red with a blinking mic.
        val recScale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isRecording) 1.4f else 1f, label = "micScale",
        )
        val overLimit = value.length > MAX_MESSAGE_CHARS
        val micModifier = if (hasText) Modifier else Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown()
                    val downTime = System.currentTimeMillis()
                    onStartRecord()
                    var cancelled = false
                    var locked = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) break
 // Slide LEFT → cancel; slide UP → lock hands-free.
                        if (ch.position.x - down.position.x < -130f) cancelled = true
                        if (ch.position.y - down.position.y < -120f) locked = true
                    }
                    val heldMs = System.currentTimeMillis() - downTime
                    when {
                        cancelled -> onCancelRecord()
                        // Slide-up lock OR a quick tap → hands-free locked panel
                        // (recording keeps going; send/cancel via the buttons).
                        locked || heldMs < 350 -> onTapLockRecord()
                        else -> onFinishRecord()
                    }
                }
            }
        }
        Box(
            modifier = micModifier
                .size(48.dp)
                .graphicsLayer(scaleX = recScale, scaleY = recScale)
                .clip(CircleShape)
                .background(
                    when {
                        isRecording -> Color(0xFFEB5757)
                        // Over the char limit → greyed-out (send disabled).
                        hasText && overLimit -> colors.textMuted
                        // Send + mic share the same green background.
                        else -> Color(0xFF34C759)
                    }
                )
                .then(if (hasText) Modifier.clickable(enabled = !isSending && !overLimit) { onSend() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (hasText) AlphaIcons.AutoMirrored.Outlined.Send else AlphaIcons.Outlined.Mic,
                contentDescription = if (hasText) stringResource(R.string.common_send) else stringResource(R.string.voice_note_label),
                tint = when {
                    isRecording -> Color.White.copy(alpha = micAlpha)
                    else -> Color.White // white icon on green bg (send + mic)
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// --- Avatar palette — flat single-colour circles as messengers usually do ---

private val ChatAvatars = listOf(
    Color(0xFF6E80B5),
    Color(0xFFB17BA6),
    Color(0xFF8FA98F),
    Color(0xFFC1834B),
    Color(0xFF7AA89F),
    Color(0xFFB9836E),
    Color(0xFF8C81B0),
    Color(0xFF9BAA73),
)

private fun avatarPalette(seed: Int): Color {
    val h = if (seed < 0) -seed else seed
    return ChatAvatars[h % ChatAvatars.size]
}

// Compile-time helper to mute unused-warning from `TextStyle` import (used via
// LocalTextStyle.current.copy() above).
@Suppress("unused")
private val _textStyleAnchor: TextStyle? = null

@Composable
private fun CompactBubbleLayout(
    modifier: Modifier = Modifier,
    senderName: @Composable (() -> Unit)? = null,
    quotedReply: @Composable (() -> Unit)? = null,
    messageText: @Composable (onTextLayout: (TextLayoutResult) -> Unit) -> Unit,
    /** Reaction pills: a row under the text, the time at its right end. */
    reactions: (@Composable () -> Unit)? = null,
    statusRow: @Composable () -> Unit,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val reactionRow = remember { com.alphamessenger.ui.chat.bubbles.ReactionRowInfo() }

    Layout(
        content = {
            if (senderName != null) {
                Box(modifier = Modifier.layoutId("sender")) { senderName() }
            }
            if (quotedReply != null) {
                Box(modifier = Modifier.layoutId("reply")) { quotedReply() }
            }
            Box(modifier = Modifier.layoutId("text")) {
                messageText { textLayoutResult = it }
            }
            if (reactions != null) {
                Box(modifier = Modifier.layoutId("reactions")) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.alphamessenger.ui.chat.bubbles.LocalReactionRowInfo provides reactionRow,
                    ) { reactions() }
                }
            }
            Box(modifier = Modifier.layoutId("status")) { statusRow() }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val senderPlaceable = measurables.firstOrNull { it.layoutId == "sender" }?.measure(constraints)
        // The reply quote spans the WHOLE bubble (thumbnail at the right edge):
        // its natural width only votes for the bubble width; it is measured to
        // the final width once that is known.
        val replyMeasurable = measurables.firstOrNull { it.layoutId == "reply" }
        val replyNaturalW = replyMeasurable?.maxIntrinsicWidth(Constraints.Infinity)
            ?.coerceAtMost(minOf(constraints.maxWidth, BubbleTokens.quoteMaxNaturalWidth.roundToPx())) ?: 0
        val replyH = replyMeasurable?.minIntrinsicHeight(replyNaturalW) ?: 0
        var replyPlaceable: androidx.compose.ui.layout.Placeable? = null
        fun measureReply(w: Int) {
            replyPlaceable = replyMeasurable?.measure(Constraints(minWidth = w, maxWidth = w))
        }
        
        val textPlaceable = measurables.firstOrNull { it.layoutId == "text" }?.measure(constraints)
        val statusPlaceable = measurables.firstOrNull { it.layoutId == "status" }?.measure(Constraints())

        val textWidth = textPlaceable?.width ?: 0
        val textHeight = textPlaceable?.height ?: 0
        val statusWidth = statusPlaceable?.width ?: 0
        val statusHeight = statusPlaceable?.height ?: 0

        val spacing = BubbleTokens.textStatusGap.roundToPx()

        var totalHeight = 0
        var maxContentWidth = 0

        if (senderPlaceable != null) {
            totalHeight += senderPlaceable.height + BubbleTokens.blockGapSender.roundToPx()
            maxContentWidth = maxOf(maxContentWidth, senderPlaceable.width)
        }
        if (replyMeasurable != null) {
            totalHeight += replyH + BubbleTokens.blockGapReply.roundToPx()
            maxContentWidth = maxOf(maxContentWidth, replyNaturalW)
        }

        val textY = totalHeight

        // --- With reactions: the pills over the whole width under the text, the
        // time at the end of their last row — or on a line of its own when that
        // row is full. ---
        val reactMeasurable = measurables.firstOrNull { it.layoutId == "reactions" }
        if (reactMeasurable != null) {
            val reactPlaceable = reactMeasurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val gap = 5.dp.roundToPx()
            val lastRowEnd = reactionRow.lastRowEnd
            val timeInRow = lastRowEnd + spacing + statusWidth <= constraints.maxWidth
            val rowH = if (timeInRow) maxOf(reactPlaceable.height, statusHeight)
                else reactPlaceable.height + statusHeight
            val w = maxOf(maxContentWidth, textWidth, reactPlaceable.width,
                if (timeInRow) lastRowEnd + spacing + statusWidth else statusWidth)
                .coerceIn(BubbleTokens.minBubbleWidth.roundToPx(), constraints.maxWidth)
            val h = textY + textHeight + gap + rowH
            measureReply(w)
            return@Layout layout(w, h) {
                var y = 0
                if (senderPlaceable != null) {
                    senderPlaceable.placeRelative(0, y)
                    y += senderPlaceable.height + BubbleTokens.blockGapSender.roundToPx()
                }
                replyPlaceable?.placeRelative(0, y)
                textPlaceable?.placeRelative(0, textY)
                reactPlaceable.placeRelative(0, if (timeInRow) h - reactPlaceable.height else h - rowH)
                statusPlaceable?.placeRelative(w - statusWidth, h - statusHeight)
            }
        }

        val layoutResult = textLayoutResult
        val fitsOnLastLine = if (layoutResult != null) {
            val lastLine = layoutResult.lineCount - 1
            val lastLineWidth = layoutResult.getLineRight(lastLine)
            val lastLineStart = layoutResult.getLineLeft(lastLine)
            val actualLastLineWidth = lastLineWidth - lastLineStart
            if (layoutResult.lineCount == 1) {
                (textWidth + spacing + statusWidth) <= constraints.maxWidth
            } else {
                (constraints.maxWidth - actualLastLineWidth) >= (statusWidth + spacing)
            }
        } else {
            false
        }

        val finalWidth: Int
        val finalHeight: Int
        val statusX: Int
        val statusY: Int
        val minBubbleWidth = BubbleTokens.minBubbleWidth.roundToPx()

        if (fitsOnLastLine && layoutResult != null) {
            val lastLine = layoutResult.lineCount - 1
            val lastLineRight = layoutResult.getLineRight(lastLine).toInt()
            // Drop the inline time half its own height so it straddles the last
            // text line (half on the baseline, half below) instead of sitting
            // flush with the text bottom / slightly above it.
            val statusDrop = statusHeight / 2
            if (layoutResult.lineCount == 1) {
                finalWidth = maxOf(maxContentWidth, textWidth + spacing + statusWidth).coerceAtLeast(minBubbleWidth)
                finalHeight = textY + maxOf(textHeight, statusHeight) + statusDrop
            } else {
                val lastLineWithStatusWidth = (lastLineRight + spacing + statusWidth)
                finalWidth = maxOf(maxContentWidth, textWidth, lastLineWithStatusWidth).coerceIn(minBubbleWidth, constraints.maxWidth)
                finalHeight = textY + textHeight + statusDrop
            }
            statusX = finalWidth - statusWidth
            statusY = textY + maxOf(0, textHeight - statusHeight) + statusDrop
        } else {
            finalWidth = maxOf(maxContentWidth, textWidth, statusWidth).coerceIn(minBubbleWidth, constraints.maxWidth)
            finalHeight = textY + textHeight + statusHeight + 2.dp.roundToPx()
            statusX = finalWidth - statusWidth
            statusY = finalHeight - statusHeight
        }

        measureReply(finalWidth)
        layout(finalWidth, finalHeight) {
            var currentY = 0
            if (senderPlaceable != null) {
                senderPlaceable.placeRelative(0, currentY)
                currentY += senderPlaceable.height + BubbleTokens.blockGapSender.roundToPx()
            }
            replyPlaceable?.let {
                it.placeRelative(0, currentY)
                currentY += it.height + BubbleTokens.blockGapReply.roundToPx()
            }
            textPlaceable?.placeRelative(0, currentY)
            statusPlaceable?.placeRelative(statusX, statusY)
        }
    }
}

private val EmojiCodes = listOf(
    0x1F600, 0x1F601, 0x1F602, 0x1F923, 0x1F60A, 0x1F607, 0x1F642, 0x1F643,
    0x1F609, 0x1F60C, 0x1F60D, 0x1F970, 0x1F618, 0x1F617, 0x1F619, 0x1F61A,
    0x1F60B, 0x1F61B, 0x1F61D, 0x1F61C, 0x1F92A, 0x1F928, 0x1F9D0, 0x1F913,
    0x1F60E, 0x1F973, 0x1F929, 0x1F97A, 0x1F60F, 0x1F612, 0x1F61E, 0x1F614,
    0x1F61F, 0x1F615, 0x1F623, 0x1F616, 0x1F62B, 0x1F629, 0x1F624, 0x1F620,
    0x1F621, 0x1F92C, 0x1F633, 0x1F631, 0x1F628, 0x1F630, 0x1F625, 0x1F622,
    0x1F62D, 0x1F613, 0x1F917, 0x1F914, 0x1F92D, 0x1F92B, 0x1F925, 0x1F62C,
    0x1F644, 0x1F62F, 0x1F634, 0x1F924, 0x1F62A, 0x1F635, 0x1F910, 0x1F974,
    0x1F922, 0x1F92E, 0x1F927, 0x1F637, 0x1F912, 0x1F915, 0x1F44D, 0x1F44E,
    0x1F44C, 0x1F44F, 0x1F64C, 0x1F64F, 0x1F4AA, 0x1F525, 0x2764, 0x1F9E1,
    0x1F49B, 0x1F49A, 0x1F499, 0x1F49C, 0x1F5A4, 0x1F90D, 0x1F389, 0x1F38A,
    0x2728, 0x1F4AF,
)

/** Inline emoji panel: slides up under the input bar, pushing
 *  it up like a keyboard would. Height is host-controlled and drag-resizable
 *  via the top grabber. Tapping an emoji inserts it into the message field. */
@Composable
private fun EmojiPanel(
    modifier: Modifier,
    colors: AlphaColorScheme,
    onPick: (String) -> Unit,
    onDrag: (Float) -> Unit,
    onDragRelease: () -> Unit,
) {
    val emojis = remember { EmojiCodes.map { String(Character.toChars(it)) } }
    Column(
        modifier = modifier
            .fillMaxWidth()
 // Flush with the input bar (same bg, no rounded notch) — other messengers
            // shows the emoji panel as a seamless keyboard replacement.
            .background(colors.bgSecondary),
    ) {
        // Drag grabber.
        Box(
            modifier = Modifier.fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, delta -> onDrag(delta) },
                        onDragEnd = { onDragRelease() },
                        onDragCancel = { onDragRelease() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 6.dp)
                    .width(36.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textMuted.copy(alpha = 0.35f)),
            )
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp),
        ) {
            emojis.forEach { e ->
                item {
                    Text(
                        e, fontSize = 26.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(e) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Emoji picker as a bottom sheet (opens ~half, draggable taller) — same UX as
 *  the media picker. Tapping an emoji inserts it into the message field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiSheet(
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val emojis = remember {
        val codes = listOf(
            0x1F600, 0x1F601, 0x1F602, 0x1F923, 0x1F60A, 0x1F607, 0x1F642, 0x1F643,
            0x1F609, 0x1F60C, 0x1F60D, 0x1F970, 0x1F618, 0x1F617, 0x1F619, 0x1F61A,
            0x1F60B, 0x1F61B, 0x1F61D, 0x1F61C, 0x1F92A, 0x1F928, 0x1F9D0, 0x1F913,
            0x1F60E, 0x1F973, 0x1F929, 0x1F97A, 0x1F60F, 0x1F612, 0x1F61E, 0x1F614,
            0x1F61F, 0x1F615, 0x1F623, 0x1F616, 0x1F62B, 0x1F629, 0x1F624, 0x1F620,
            0x1F621, 0x1F92C, 0x1F633, 0x1F631, 0x1F628, 0x1F630, 0x1F625, 0x1F622,
            0x1F62D, 0x1F613, 0x1F917, 0x1F914, 0x1F92D, 0x1F92B, 0x1F925, 0x1F62C,
            0x1F644, 0x1F62F, 0x1F634, 0x1F924, 0x1F62A, 0x1F635, 0x1F910, 0x1F974,
            0x1F922, 0x1F92E, 0x1F927, 0x1F637, 0x1F912, 0x1F915, 0x1F44D, 0x1F44E,
            0x1F44C, 0x1F44F, 0x1F64C, 0x1F64F, 0x1F4AA, 0x1F525, 0x2764, 0x1F9E1,
            0x1F49B, 0x1F49A, 0x1F499, 0x1F49C, 0x1F5A4, 0x1F90D, 0x1F389, 0x1F38A,
            0x2728, 0x1F4AF,
        )
        codes.map { String(Character.toChars(it)) }
    }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.chat_emoji), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 18.dp, bottom = 8.dp),
        )
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).navigationBarsPadding()
                .padding(horizontal = 6.dp),
        ) {
            emojis.forEach { e ->
                item {
                    Text(
                        e, fontSize = 26.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(e) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// --- ─ ---
// Media bubble — photo with rounded corners, optional caption, status row.
// The blob is downloaded+decrypted lazily via `mediaLoader` and cached.
// --- ─ ---

// In-process caches keyed by the MediaRef JSON. Keep decoded photos alive
// across scroll so a bubble that leaves and re-enters the viewport renders at
// the same height instantly (no decode → no placeholder → no scroll jump).
internal val MediaPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()
/** Cache for document/file poster thumbnails — prevents re-decoding on scroll (#1). */
/** Decoded reply-quote thumbnails (base64 poster or local thumb file). */
private val QuoteThumbCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(64)
/** Decoded peer avatar bitmaps, keyed by the avatar file path. */
internal val AvatarBitmapCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48)

/** Off-thread decode of a base64 poster with an LRU cache — never blocks the UI
 *  thread. Returns the cached bitmap immediately if present, else decodes on IO.
 *  THE single helper every "instant poster" call site uses (rule: no sync decode). */
@Composable
internal fun rememberPosterBitmap(b64: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (b64.isNullOrBlank()) return null
    val key = b64
    return androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        PosterBitmapCache.get(key), key,
    ) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            PosterBitmapCache.get(key) ?: decodePosterB64(key)?.also { PosterBitmapCache.put(key, it) }
        }
    }.value
}

/** Reactive "this ref was evicted from cache" signal. Bubbles key their
 *  downloaded-state `remember` on [observe] so that "Delete from cache" flips the
 *  bubble back to the download button IMMEDIATELY (no scroll / re-enter needed),
 * as messengers usually do. */
internal object MediaCacheEvictions {
    private val bumps = androidx.compose.runtime.mutableStateMapOf<String, Int>()
    fun evict(ref: String) { bumps[ref] = (bumps[ref] ?: 0) + 1 }
    @androidx.compose.runtime.Composable
    fun observe(ref: String?): Int = if (ref == null) 0 else bumps[ref] ?: 0
}

/** Deterministic path of a fully disk-cached video for a streaming ref
 *  (`cacheDir/media/<hash>`). Returns the file only if it really exists and is
 *  non-empty. Survives an app restart — so an already-cached video is never
 *  streamed again. */
private fun streamingDiskCacheFile(
    ctx: android.content.Context,
    refJson: String,
): java.io.File? = try {
    val hash = com.alphamessenger.data.blobHashOf(refJson)
    if (hash.isBlank()) null else {
        val f = java.io.File(java.io.File(ctx.cacheDir, "media"), hash)
        if (f.exists() && f.length() > 0L) {
            MediaPathCache[refJson] = f.absolutePath // warm the in-memory cache
            f
        } else null
    }
} catch (_: Exception) { null }
// Voice notes the user has played this session (reactive → bubbles update the
// "unplayed" indicator live). Session-scoped for now.
private val PlayedVoices = androidx.compose.runtime.mutableStateListOf<String>()

/** How many messages ahead of the reader older history starts loading (three pages of 25). */
private const val HISTORY_PREFETCH = 75
/** The typing row's key in the message list. */
private const val TYPING_ROW_KEY = "__typing__"
/** How long the typing row waits for their message before it folds away. */
private const val TYPING_REPLACE_WAIT_MS = 600L

/** Share a decrypted blob to other apps (other messengers/…) via ACTION_SEND. */
/** "Open with…": hand the decrypted file to the system app (ACTION_VIEW).
 *  Mirror of [shareMediaExternally] — same FileProvider and MIME detection. */
internal suspend fun openFileExternally(
    ctx: android.content.Context,
    decryptedPath: String,
    mediaType: String?,
    filename: String? = null,
) {
    // A named copy with the right extension: the cache file is a bare hash.
    val named = com.alphamessenger.util.ShareOut.namedCopy(ctx, com.alphamessenger.util.ShareOut.Item(decryptedPath, filename, mediaType))
    runCatching {
        val file = named ?: java.io.File(decryptedPath).takeIf { !com.alphamessenger.data.MediaVault.isSealed(it) }
            ?: error("no plain copy")
        if (com.alphamessenger.util.ApkInstall.isApk(file)) { com.alphamessenger.util.ApkInstall.open(ctx, file); return@runCatching }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file,
        )
        val mime = com.alphamessenger.util.ShareOut.mimeOf(file).takeIf { it != "application/octet-stream" }
            ?: when (mediaType) { "video" -> "video/*"; "audio" -> "audio/*"; "image" -> "image/*"; else -> "*/*" }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(android.content.Intent.createChooser(intent, ctx.getString(R.string.chat_open_with)))
    }.onFailure {
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.chat_no_app_for_file), android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Share one message's media with other apps: a properly named copy, exact type. */
private suspend fun shareMediaExternally(
    ctx: android.content.Context,
    decryptedPath: String,
    filename: String?,
    mediaType: String?,
) {
    com.alphamessenger.util.ShareOut.share(
        ctx, listOf(com.alphamessenger.util.ShareOut.Item(decryptedPath, filename, mediaType)),
        ctx.getString(R.string.common_share),
    )
}

/** Drop the decrypted/downloaded cache copies for a message (frees disk).
 *  Never touches the sender's own original file (localMediaPath). */
private fun clearMediaCacheFor(
    message: MessageUi,
    wasCached: (String) -> Boolean,
    deleteFromCore: (String) -> Unit,
): Boolean {
    val refs = message.albumItems?.mapNotNull { it.mediaRefJson }
        ?: listOfNotNull(message.mediaRefJson)
    var freed = false
    refs.forEach { ref ->
        // Was the blob actually on disk (persistent cache OR the path we cached)?
        if (wasCached(ref) || MediaPathCache[ref]?.let { java.io.File(it).exists() } == true) freed = true
        // In-memory path cache + the file it points to.
        MediaPathCache.remove(ref)?.let { p -> runCatching { java.io.File(p).delete() } }
        // Persistent decrypted-blob cache (chunks + assembled file) via the core.
        deleteFromCore(ref)
        // Decoded-image / thumbnail in-memory caches.
        runCatching { MediaImageCache.remove(ref) }
        runCatching { DocumentThumbCache.remove(ref) }
        // Reactive signal → bubble flips back to the download button immediately.
        MediaCacheEvictions.evict(ref)
    }
    return freed
}

/**
 * Video bubble: poster frame + play overlay + duration; tap downloads (with %)
 * then plays inline (fullscreen ExoPlayer). Adaptive size from the poster.
 */
@OptIn(ExperimentalFoundationApi::class)
// VideoBubble was extracted → chat/bubbles/BubbleVideo.kt (bubbles-split; behaviour unchanged)



/**
 * Card-style bubble for non-image media (video / audio / document). Shows a
 * type icon, filename and size; tap downloads+decrypts the blob and opens it
 * in the system app (video player / music player / document viewer).
 */
// MediaFileBubble was extracted → chat/bubbles/BubbleFile.kt (bubbles-split; behaviour unchanged)

/** Read an audio file's duration (ms) via MediaMetadataRetriever — lets us
 *  show the length of a voice note before it's played. */
/** Format milliseconds → "m:ss". */
internal fun fmtMs(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** classic voice waveform that doubles as a seek bar. Bars up to the
 *  played `fraction` are accent-coloured; tapping seeks to that position. */
@Composable
private fun WaveformSeek(
    fraction: Float,
    playedColor: Color,
    restColor: Color,
    bars: FloatArray,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) }
            },
    ) {
        val gap = 2.dp.toPx()
        val n = ((size.width + gap) / (2.5.dp.toPx() + gap)).toInt().coerceAtLeast(1)
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val playedX = size.width * fraction
        for (i in 0 until n) {
            // Bars not known yet draw flat, so the bubble never sits empty.
            val h = if (bars.isEmpty()) 0.12f else {
                val from = i * bars.size / n
                val to = ((i + 1) * bars.size / n).coerceIn(from + 1, bars.size)
                var sum = 0f
                for (k in from until to) sum += bars[k]
                (sum / (to - from)).coerceIn(0.12f, 1f)
            }
            val x = i * (barW + gap)
            val bh = size.height * h
            val top = (size.height - bh) / 2f
            drawRoundRect(
                color = if (x <= playedX) playedColor else restColor,
                topLeft = androidx.compose.ui.geometry.Offset(x, top),
                size = androidx.compose.ui.geometry.Size(barW, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/**
 * Music / audio bubble with an in-bubble player: a play/pause disc (showing
 * download % first time), a seek slider and elapsed/total time. Uses the
 * platform MediaPlayer — no extra deps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioPlayerBubble(
    message: MessageUi,
    bubbleColor: Color,
    textColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    showSenderName: Boolean,
    senderColor: Color,
    colors: AlphaColorScheme,
    onLongPress: () -> Unit,
    onMediaActions: () -> Unit = {},
    mediaLoader: (suspend (String) -> String?)?,
    mediaProgress: kotlinx.coroutines.flow.SharedFlow<Triple<String, Long, Long>>? = null,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    onCancelUpload: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
    voiceController: VoicePlaybackController? = null,
    musicController: MusicPlaybackController? = null,
) {
    val isVoice = message.mediaFilename?.startsWith("voice") == true
    // Both voice and music play through a SHARED controller owned by the
    // screen, so playback survives the bubble scrolling off-screen.
    val accent = if (isVoice) colors.accent else Color(0xFFFF8A34)
    // On an OUTGOING bubble the background IS the accent colour, so an accent
    // play button / waveform would be invisible (the "purple on purple" issue).
    // Use the bubble's text colour (white) for contrast there.
    val playerAccent = if (message.isOutgoing) textColor else accent
    val audioCtx = androidx.compose.ui.platform.LocalContext.current
    val refJson = message.mediaRefJson
    // ── Unified media state — download decisions/policy owned by TransferEngine.
    //    Voice always auto-downloads (the policy's voice exception); music obeys
    //    the settings. This bubble only reads state + does playback/waveform. ──
    // ── ONE source of truth: read a FileUiState, render it. No if(snap/…). Voice
    //    auto-downloads silently (isVoice ⇒ never a ↓, upload UI suppressed); the
    //    play disc is the affordance, so playable stays false (no overlay ▶). ──
    // Audio / voice: the round play/pause disc IS the play affordance, so the
    // transfer overlay must NOT draw its own ▶ on Ready (a playable overlay would
    // sit on top and swallow the tap → the disc never toggled → "voice won't
    // play"). Force the overlay's Ready to non-playable; it self-hides and the
    // disc receives the tap.
    val state = com.alphamessenger.media.rememberMediaState(message, mediaActions)
        .toOverlayState(message.isOutgoing)
        .let { if (it is com.alphamessenger.transfer.FileUiState.Ready) com.alphamessenger.transfer.FileUiState.Ready(it.path, playable = false) else it }
    LaunchedEffect(refJson) {
        if (refJson != null) {
            mediaActions?.consider?.invoke(
                com.alphamessenger.transfer.TransferKey.download(refJson),
                "audio", message.mediaFilename, message.mediaSizeBytes ?: 0L,
                message.isOutgoing, message.localMediaPath, message.id,
            )
        }
    }
    val mediaKey = refJson?.let { com.alphamessenger.transfer.TransferKey.download(it) }
    fun dispatchDownload(act: com.alphamessenger.transfer.TransferAction) {
        mediaKey?.let { mediaActions?.dispatch?.invoke(it, act, message.id) }
    }

    var bars by remember(message.id, message.mediaWaveform) {
        mutableStateOf(
            VoiceWaveform.decode(message.mediaWaveform)
                ?: VoiceWaveform.cached(refJson?.let { MediaPathCache[it] } ?: message.localMediaPath)
                ?: FloatArray(0),
        )
    }
    // The player's one speed, not the bubble's: a new bubble must never reset it.
    val speed by (if (isVoice) voiceController?.speed else musicController?.speed)
        ?.collectAsState() ?: remember { mutableStateOf(1f) }
    val setSpeed: (Float) -> Unit = { if (isVoice) voiceController?.setSpeed(it) else musicController?.setSpeed(it) }
    var displayDurationMs by remember(message.id) { mutableStateOf(0) }

    // Active controller's state for this message.
    val vSt = voiceController?.state?.collectAsState()?.value
    val mSt = musicController?.state?.collectAsState()?.value
    val st = if (isVoice) vSt else mSt
    val isCurrent = st?.currentId == message.id
    val isPlaying = isCurrent && st?.isPlaying == true
    val positionMs = if (isCurrent) (st?.positionMs ?: 0) else 0
    val durationMs = when {
        isCurrent && (st?.durationMs ?: 0) > 0 -> st!!.durationMs
        displayDurationMs > 0 -> displayDurationMs
        else -> (message.mediaDurationMs ?: 0L).toInt()
    }
 // Music, classic: the track's title and artist (from its tags when
    // the file is here; else the file name). The seek line takes the artist's
    // place while the track is playing or paused mid-way.
    val musicPath = (state as? com.alphamessenger.transfer.FileUiState.Ready)?.path ?: message.localMediaPath
    var musicTags by remember(message.id) { mutableStateOf(musicPath?.let { MusicTagCache[it] }) }
    LaunchedEffect(musicPath) {
        if (!isVoice && musicTags == null && !musicPath.isNullOrBlank() && java.io.File(musicPath).exists()) {
            musicTags = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        com.alphamessenger.data.MediaVault.setSource(r, musicPath)
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                            ?.takeIf { it > 0 && displayDurationMs == 0 }?.let { displayDurationMs = it }
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() } to
                            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
                    } finally { r.release() }
                }.getOrNull()
            }
            musicTags?.let { MusicTagCache[musicPath] = it }
            // The player's list learns the same tags and the cover.
            if (!isVoice) musicController?.learnFile(message.id, musicPath)
        }
    }
    val trackTitle = musicTags?.first
        ?: displayFileName(message.mediaFilename)?.substringBeforeLast('.')?.replace('_', ' ')?.trim().orEmpty()
    val trackArtist = musicTags?.second ?: stringResource(R.string.music_unknown_artist)
    val musicActive = isCurrent && (isPlaying || positionMs > 0)

    LaunchedEffect(isPlaying) {
        if (isPlaying && isVoice && message.id !in PlayedVoices) PlayedVoices.add(message.id)
    }
    val unplayed = isVoice && !message.isOutgoing && message.id !in PlayedVoices
    // Single shared transfer-progress source (Part 4) — sender upload OR
    // receiver download of the audio blob.
    // SENDER transfer lifecycle (single source of truth) + RECEIVER download.
    // Voice notes are tiny and must feel INSTANT — never show the upload
    // progress UI for them (the blob still uploads in the background and the
    // ✓/✓✓ status reflects delivery). Only music keeps the progress readout.

    // Once the file is cached (TransferEngine), extract the waveform + duration.
    // This is post-processing / rendering — NOT a download decision. Auto-
    // download itself is driven by the TransferEngine (voice always,
    // music per policy) via `consider` above.
    //
    // SINGLE RENDERER for incoming + outgoing + replay + forward:
    // - Incoming: the download cache path (snap.localPath) — auto-downloaded.
    // - Outgoing: the sender's own local file (message.localMediaPath) — the
    //   sender never downloads their own blob, so cachedPath is null.
    // - Forward: localMediaPath if the source was local, else cachedPath.
    // Only the source path differs; the renderer (WaveformSeek) is the same.
    // The single state already folds cache OR the sender's original into readyPath
    // — no second disk probe.
    val waveformPath = state.readyPath
    LaunchedEffect(waveformPath) {
        val path = waveformPath ?: return@LaunchedEffect
        // Only a voice note draws bars; decoding a whole song for them would cost seconds.
        if (isVoice && bars.isEmpty()) VoiceWaveform.load(audioCtx.cacheDir, path)?.let { bars = it }
        if (displayDurationMs == 0 && (message.mediaDurationMs ?: 0L) <= 0L) {
            // Off the main thread — MMR blocks the frame (proven by MAINSTALL logs).
            displayDurationMs = withContext(Dispatchers.IO) { audioDurationMs(path) }
        }
    }

    fun toggle() {
        if (state.isActive) return
        if (isVoice) voiceController?.toggle(message.id) else musicController?.toggle(message.id)
    }

    Column(
        // ONE shell for shape/clip/background/click/width — see BubbleGeometry.bubbleShell.
        // Tap the bubble → actions menu; play/seek use their own controls. The voice card
        // keeps its OWN compact insets (4dp base, 11dp tail side), passed in verbatim so
        // this migration is pixel-for-pixel identical.
        modifier = Modifier.bubbleShell(
            isOutgoing = message.isOutgoing,
            showTail = hasTail(message),
            bubbleShape = bubbleShape,
            bubbleColor = bubbleColor,
            maxWidth = if (isVoice) 248.dp else 300.dp,
            onTap = onMediaActions,
            onLongPress = onLongPress,
            contentPadding = BubbleTokens.compactPadding(
                message.isOutgoing, hasTail(message), top = 4.dp, bottom = 3.dp,
            ),
        ),
    ) {
        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
        if (showSenderName) {
            Text(
                text = message.senderName ?: "",
                color = senderColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val onSeekFrac: (Float) -> Unit = { v ->
            if (isVoice) voiceController?.seek(message.id, v) else musicController?.seek(message.id, v)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Play/pause disc — ONLY the play/pause affordance. No transfer
            // overlay here; the overlay moved to the waveform area below.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(playerAccent.copy(alpha = 0.20f))
                    .clickable { toggle() },
                contentAlignment = Alignment.Center,
            ) {
                // The play disc IS the transfer button while a transfer
                // is active — ○ → ⭕% → ✕ live HERE, never over the waveform / seek /
                // duration. The play/pause glyph shows only when no transfer governs
                // (Ready/Hidden); the TransferOverlay self-hides then, revealing ▶/❚❚.
                if (state is com.alphamessenger.transfer.FileUiState.Ready ||
                    state is com.alphamessenger.transfer.FileUiState.Hidden) {
                    Icon(
                        if (isPlaying) AlphaIcons.Filled.Pause else AlphaIcons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.editor_pause) else stringResource(R.string.media_play),
                        tint = playerAccent, modifier = Modifier.size(24.dp),
                    )
                }
                com.alphamessenger.transfer.TransferOverlay(
                    state = state,
                    onDownloadAction = { act -> dispatchDownload(act) },
                    onUploadAction = { act ->
                        when (act) {
                            com.alphamessenger.transfer.TransferAction.Cancel -> onCancelUpload()
                            com.alphamessenger.transfer.TransferAction.Retry -> onRetryUpload()
                            else -> {}
                        }
                    },
                    onOpen = {},
                    modifier = Modifier.matchParentSize(),
                    diameter = 40.dp,
                    idleSizeBytes = 0L,
                    scrim = false,
                    // Keep the round button glyphs-only (○ → ⭕ → ✕).
                    // The byte readout moves to the bottom info row — never cramped
                    // inside the small disc.
                    showSizeLabel = false,
                )
            }
            Spacer(Modifier.width(8.dp))
            // Seek area (waveform for voice, line+ball for music) — THE overlay
            // sits ON TOP of this area, centered. Scrim dims the waveform while
            // a transfer is in flight; on Completed the overlay fades out (~200ms)
            // revealing the interactive waveform underneath.
            Box(modifier = Modifier.weight(1f)) {
                if (isVoice) {
                    WaveformSeek(frac, playerAccent, textColor.copy(alpha = 0.25f), bars, onSeekFrac)
                } else {
                    Column(Modifier.padding(start = 2.dp)) {
                        Text(
                            trackTitle, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
                            if (musicActive) {
                                LineSeek(frac, playerAccent, textColor.copy(alpha = 0.25f), onSeekFrac)
                            } else {
                                Text(
                                    trackArtist, color = textColor.copy(alpha = 0.7f), fontSize = 13.sp,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                // NO transfer overlay over the waveform: progress lives in
                // the play disc (PlayButton → TransferButton); the waveform / seek /
                // duration show content only — never a ring / % / ✕ during transfer.
            }
            // Round speed button AFTER the scale, vertically centred.
            if (durationMs > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(29.dp)
                        .clip(CircleShape)
                        .background(playerAccent.copy(alpha = 0.18f))
                        .clickable { setSpeed(when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (speed == 1f) "1×" else if (speed == 1.5f) "1.5×" else "2×",
                        color = playerAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        message.mediaCaption?.let { cap ->
            MessageText(onAccent = message.isOutgoing, text = cap, color = textColor, fontSize = 15.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
        }
        // Unified bottom info row (ALWAYS shown): type icon · played/duration ·
        // format · [live "25kb / 5mb" while bytes move] · (spacer) · time · status.
        // The disc carries the round progress (ring + ✕ — NO digits inside
        // the circle); the byte readout lives HERE, bottom-left after duration+format,
        // exactly like the file bubble, and disappears when the transfer settles.
        val format = (message.mediaFilename?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
            ?: if (isVoice) "m4a" else "audio").uppercase()
        com.alphamessenger.ui.chat.bubbles.BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isVoice) AlphaIcons.Outlined.Mic else AlphaIcons.Outlined.MusicNote,
                contentDescription = null,
                // Bright accent mic for an UNPLAYED incoming voice; muted once heard.
                tint = if (unplayed) accent else if (isVoice) textColor.copy(alpha = 0.55f) else accent,
                modifier = Modifier.size(13.dp),
            )
            if (unplayed) {
                Spacer(Modifier.width(3.dp))
                Box(
                    modifier = Modifier.size(7.dp).clip(CircleShape).background(accent),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "${fmtMs(positionMs)} / ${if (durationMs > 0) fmtMs(durationMs) else "…"}",
                color = textColor.copy(alpha = 0.7f), fontSize = 10.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(format, color = textColor.copy(alpha = 0.5f), fontSize = 10.sp)
            // Live byte progress while a transfer is in flight — "25kb / 5mb".
            val tp = state.progressOrNull
            if (state.isActive && tp != null && tp.isDeterminate) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${humanSize(tp.bytes)} / ${humanSize(tp.total)}",
                    color = playerAccent, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            if (message.isEdited) {
                com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.65f))
                Spacer(Modifier.width(4.dp))
            }
            com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
            Text(compactTime(message.timestamp), color = textColor.copy(alpha = 0.7f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
            if (message.isOutgoing) {
                Spacer(Modifier.width(4.dp))
                com.alphamessenger.ui.chat.bubbles.DeliveryTicks(message.status, textColor)
            }
        }
    }
}

/** Plain seek line with a draggable thumb — used for music (no waveform). */
@Composable
private fun LineSeek(
    fraction: Float,
    color: Color,
    trackColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> onSeek((o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { ch, _ ->
                    onSeek((ch.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val r = 7.dp.toPx()
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            drawLine(trackColor, androidx.compose.ui.geometry.Offset(r, cy),
                androidx.compose.ui.geometry.Offset(size.width - r, cy), 3.dp.toPx(), cap)
            val px = r + (size.width - 2 * r) * fraction
            drawLine(color, androidx.compose.ui.geometry.Offset(r, cy),
                androidx.compose.ui.geometry.Offset(px, cy), 3.dp.toPx(), cap)
            drawCircle(color, r, androidx.compose.ui.geometry.Offset(px, cy))
        }
    }
}

// --- ─ ---
// Music mini-player — pinned under the top bar while a track
// is loaded. prev / play-pause / next / close + tap → track list.
// --- ─ ---

@Composable
private fun MusicMiniPlayer(
    state: PlaybackSnapshot,
    colors: AlphaColorScheme,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onOpenList: () -> Unit,
) {
    val accent = Color(0xFFFF8A34)
    val frac = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth().playerPill()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenList() }
                .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AlphaIcons.Outlined.MusicNote, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                state.title.ifBlank { stringResource(R.string.preview_audio) },
                color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${fmtMs(state.positionMs)} / ${if (state.durationMs > 0) fmtMs(state.durationMs) else "…"}",
                color = colors.textMuted, fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onPrev, modifier = Modifier.size(34.dp)) {
                Icon(AlphaIcons.Filled.SkipPrevious, stringResource(R.string.common_back), tint = accent, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (state.isPlaying) AlphaIcons.Filled.Pause else AlphaIcons.Filled.PlayArrow,
                    if (state.isPlaying) stringResource(R.string.editor_pause) else stringResource(R.string.media_play), tint = accent, modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                Icon(AlphaIcons.Filled.SkipNext, stringResource(R.string.media_next), tint = accent, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
        LinearProgressIndicator(
            progress = { frac },
            color = accent,
            trackColor = colors.bgTertiary,
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicCover(cover: android.graphics.Bitmap?, size: androidx.compose.ui.unit.Dp, accent: Color, active: Boolean, isPlaying: Boolean) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 5)).background(accent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            androidx.compose.foundation.Image(
                cover.asImageBitmap(), null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (cover == null || active) {
            Box(
                Modifier.matchParentSize().background(if (cover != null) Color.Black.copy(alpha = 0.35f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when { active && isPlaying -> AlphaIcons.Filled.Pause; active -> AlphaIcons.Filled.PlayArrow; else -> AlphaIcons.Outlined.MusicNote },
                    null, tint = if (cover != null) Color.White else accent, modifier = Modifier.size(size * 0.45f),
                )
            }
        }
    }
}

/** The music of the playing chat: the current track with its controls, then the list. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MusicListSheet(
    controller: MusicPlaybackController,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onShowInChat: (chatId: String, messageId: String) -> Unit,
) {
    val accent = Color(0xFFFF8A34)
    val st by controller.state.collectAsState()
    val info by controller.info.collectAsState()
    val titles by controller.titles.collectAsState()
    val mode by controller.mode.collectAsState()
    val tracks = controller.tracks()
    val current = tracks.firstOrNull { it.id == st.currentId }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp)) {
            if (current != null) {
                val ci = info[current.id]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MusicCover(ci?.cover, 64.dp, accent, active = false, isPlaying = false)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ci?.title ?: current.title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ci?.artist ?: stringResource(R.string.music_unknown_artist), color = colors.textMuted, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val frac = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
                com.alphamessenger.ui.components.AlphaSlider(
                    value = frac,
                    onValueChange = { f -> st.currentId?.let { controller.seek(it, f) } },
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = accent.copy(alpha = 0.2f)),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(fmtMs(st.positionMs), color = colors.textMuted, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (st.durationMs > 0) fmtMs(st.durationMs) else "…", color = colors.textMuted, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.toggleShuffle() }) {
                        Icon(AlphaIcons.Filled.Shuffle, stringResource(R.string.music_shuffle), tint = if (mode.shuffle) accent else colors.textMuted)
                    }
                    IconButton(onClick = { controller.prev() }) { Icon(AlphaIcons.Filled.SkipPrevious, null, tint = colors.textPrimary, modifier = Modifier.size(30.dp)) }
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(accent).clickable { st.currentId?.let { controller.toggle(it) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (st.isPlaying) AlphaIcons.Filled.Pause else AlphaIcons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    IconButton(onClick = { controller.next() }) { Icon(AlphaIcons.Filled.SkipNext, null, tint = colors.textPrimary, modifier = Modifier.size(30.dp)) }
                    IconButton(onClick = { controller.cycleRepeat() }) {
                        Icon(
                            if (mode.repeat == MusicMode.REPEAT_ONE) AlphaIcons.Filled.RepeatOne else AlphaIcons.Filled.Repeat,
                            stringResource(R.string.music_repeat),
                            tint = if (mode.repeat != MusicMode.REPEAT_OFF) accent else colors.textMuted,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                stringResource(R.string.chat_music_in_chat),
                color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
        }
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        ) {
            items(tracks, key = { it.id }) { t ->
                val active = t.id == st.currentId
                val ti = info[t.id]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { controller.toggle(t.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicCover(ti?.cover, 44.dp, accent, active = active, isPlaying = st.isPlaying)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ti?.title ?: titles[t.id] ?: t.title,
                            color = if (active) accent else colors.textPrimary,
                            fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        ti?.artist?.let { Text(it, color = colors.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    // Eye → open the chat this track was sent in.
                    IconButton(onClick = { onShowInChat(t.chatId, t.id) }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            AlphaIcons.Outlined.Visibility, stringResource(R.string.chat_show_in_chat),
                            tint = colors.textMuted, modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Global music bar — rendered ABOVE the whole navigation host so it shows on
 * every screen while a track plays, and survives chat switches. `onOpenChat`
 * navigates to the track's source chat (for "show in chat").
 */
@Composable
fun GlobalMusicBar(
    controller: MusicPlaybackController,
    onOpenChat: (String) -> Unit,
    /** "Show in chat": the track's own message, scrolled to and lit up. */
    onOpenMessage: (chatId: String, messageId: String) -> Unit = { c, _ -> onOpenChat(c) },
) {
    val colors = AlphaTheme.colorScheme
    val st = controller.state.collectAsState().value
    var showList by remember { mutableStateOf(false) }
    // The system player was tapped: show this sheet.
    val sheetAsk by controller.sheetPending.collectAsState()
    LaunchedEffect(sheetAsk, st.currentId) {
        if (sheetAsk && st.currentId != null) { showList = true; controller.sheetShown() }
    }
    if (st.currentId == null) return
    MusicMiniPlayer(
        state = st,
        colors = colors,
        onPlayPause = { controller.toggle(st.currentId!!) },
        onPrev = { controller.prev() },
        onNext = { controller.next() },
        onClose = { controller.close() },
        onOpenList = { showList = true },
    )
    if (showList) {
        MusicListSheet(
            controller = controller,
            colors = colors,
            onDismiss = { showList = false },
            onShowInChat = { chatId, msgId -> showList = false; onOpenMessage(chatId, msgId) },
        )
    }
}

/**
 * Global voice bar — classic mini-player for voice notes, mirrors the
 * music bar but with a microphone icon. Bound to the app-scoped voice player so
 * a voice note keeps playing while you browse other chats / the main screen.
 */
@Composable
fun GlobalVoiceBar(controller: VoicePlaybackController, onOpenChat: (String) -> Unit = {}) {
    val colors = AlphaTheme.colorScheme
    val st = controller.state.collectAsState().value
    if (st.currentId == null) return
    val accent = Color(0xFF34C759) // voice green (distinct from music orange)
    val frac = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth().playerPill()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { controller.sourceChatId()?.let(onOpenChat) }
                .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AlphaIcons.Outlined.Mic, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                st.title.ifBlank { stringResource(R.string.voice_note_label) },
                color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${fmtMs(st.positionMs)} / ${if (st.durationMs > 0) fmtMs(st.durationMs) else "…"}",
                color = colors.textMuted, fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { controller.prev() }, modifier = Modifier.size(34.dp)) {
                Icon(AlphaIcons.Filled.SkipPrevious, stringResource(R.string.media_previous), tint = accent, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { controller.toggle(st.currentId!!) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (st.isPlaying) AlphaIcons.Filled.Pause else AlphaIcons.Filled.PlayArrow,
                    if (st.isPlaying) stringResource(R.string.editor_pause) else stringResource(R.string.media_play), tint = accent, modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = { controller.next() }, modifier = Modifier.size(34.dp)) {
                Icon(AlphaIcons.Filled.SkipNext, stringResource(R.string.media_next), tint = accent, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { controller.close() }, modifier = Modifier.size(34.dp)) {
                Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
        LinearProgressIndicator(
            progress = { frac }, color = accent, trackColor = colors.bgTertiary,
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
// MediaBubble was extracted → chat/bubbles/BubbleImage.kt (bubbles-split; behaviour unchanged)

// --- Message action sheet — classic: quick-reactions bar + actions ---

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
/** More reactions in the window beside the message (its row slides sideways). */
private val MORE_REACTIONS = listOf("🔥", "👏", "😍", "🎉", "🤔", "😁", "💯", "👌", "🙈", "😎", "🥰", "😡")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: MessageUi,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit,
    onSaved: () -> Unit,
    onSelect: () -> Unit,
    onSaveGallery: () -> Unit,
    /** A GIF: keep it among my GIFs (null = not a GIF). */
    onSaveGif: (() -> Unit)? = null,
    /** A sticker: keep it among my stickers (null = not a sticker). */
    onSaveSticker: (() -> Unit)? = null,
    /** Pin / unpin (null = not allowed here). */
    onTogglePin: (() -> Unit)? = null,
    /** Channel owner: open/close the comments under this post (null = not offered). */
    onToggleComments: (() -> Unit)? = null,
    commentsOpen: Boolean = true,
    isPinned: Boolean = false,
    onShare: () -> Unit = {},
    onClearCache: () -> Unit = {},
    // Partial text selection for the message (null → row hidden).
    onSelectText: (() -> Unit)? = null,
    /** The message has not gone out yet: preparing/uploading, or the send failed. */
    onCancelSend: (() -> Unit)? = null,
    onRetrySend: (() -> Unit)? = null,
    /** Which reactions the chat allows (a group owner's setting). */
    reactionPolicy: ReactionPolicy = ReactionPolicy.ALL,
    /** A channel subscriber: no reply or edit. */
    readerOnly: Boolean = false,
    canDelete: Boolean = true,
    /** A discussion moderator's actions on this comment and its author (null = none). */
    moderation: (@Composable () -> Unit)? = null,
    /** Where the message sits on screen (for the menu beside it), if known. */
    anchor: androidx.compose.ui.geometry.Rect? = null,
    hole: androidx.compose.ui.graphics.Shape? = null,
    /** The chat's glass source (the menu beside the message is frosted with it). */
    haze: com.alphamessenger.ui.components.GlassState? = null,
    startWithPicker: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // "+" swaps the action list for the full emoji grid (any emoji as a reaction).
    var pickAnyEmoji by remember { mutableStateOf(startWithPicker) }
 // Hide keyboard so the menu slides up from the bottom.
    val imeController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        imeController?.hide()
    }
    val hasReactions = reactionPolicy.enabled || message.myReactions.isNotEmpty()
    val notSentYet = onCancelSend != null || onRetrySend != null
    // The parts of the menu — the same in the bottom panel and beside the
    // message, so the two never offer different things.
    val reactionsRow: @Composable (Boolean, Modifier) -> Unit = { popup, rowModifier ->
            // --- Quick reactions row (+ any emoji) — none if the group turned reactions off ---
            val baseQuick = reactionPolicy.quick(if (popup) QUICK_REACTIONS + MORE_REACTIONS else QUICK_REACTIONS)
            Row(
                modifier = rowModifier,
                horizontalArrangement = if (popup) Arrangement.spacedBy(2.dp) else Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Own reactions that are not in the quick set stay visible here
                // too, so they can be removed with one tap.
                val quick = (baseQuick + message.myReactions.filter { it !in baseQuick }).take(if (popup) 20 else 7)
                quick.forEach { emoji ->
                    val mine = emoji in message.myReactions
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (mine) colors.accent.copy(alpha = 0.28f) else if (popup) Color.Transparent else colors.bgTertiary)
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 21.sp)
                    }
                }
                if (reactionPolicy.anyEmoji || reactionPolicy.allowed.size > quick.size) Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colors.bgTertiary)
                        .clickable { pickAnyEmoji = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AlphaIcons.Filled.Add, stringResource(R.string.chat_reaction_more), tint = colors.textSecondary, modifier = Modifier.size(22.dp))
                }
            }
    }
    val pendingList: @Composable () -> Unit = {
            onRetrySend?.let {
                ActionRow(AlphaIcons.Filled.Refresh, stringResource(R.string.chat_retry_send), colors, it)
            }
            if ((message.text.isNotBlank() || !message.mediaCaption.isNullOrBlank()) && !message.isDeleted) {
                ActionRow(AlphaIcons.Outlined.ContentCopy, stringResource(R.string.common_copy), colors, onCopy)
            }
            onCancelSend?.let {
                ActionRow(AlphaIcons.Filled.Close, stringResource(R.string.chat_cancel_send), colors, it, danger = true)
            }
            if (canDelete) ActionRow(AlphaIcons.Outlined.Delete, stringResource(R.string.common_delete), colors, onDelete, danger = true)
    }
    val emojiPicker: @Composable () -> Unit = {
            if (reactionPolicy.anyEmoji) {
                com.alphamessenger.ui.chat.v2.EmojiPanelV2(
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                    colors = colors,
                    onPick = { onReact(it) },
                    onBackspace = {},
                )
            } else {
                // The group allows only these.
                ReactionGrid(reactionPolicy.allowed, message.myReactions, colors, onPick = { onReact(it) })
            }
    }
    val actionList: @Composable () -> Unit = {
            // --- Action list ---
            if (!readerOnly) ActionRow(AlphaIcons.AutoMirrored.Outlined.Reply, stringResource(R.string.notif_reply), colors, onReply)
            if ((message.text.isNotBlank() || !message.mediaCaption.isNullOrBlank()) && !message.isDeleted) {
                ActionRow(AlphaIcons.Outlined.ContentCopy, stringResource(R.string.common_copy), colors, onCopy)
            }
            ActionRow(AlphaIcons.Filled.KeyboardDoubleArrowRight, stringResource(R.string.common_forward), colors, onForward)
            onToggleComments?.let {
                ActionRow(
                    if (commentsOpen) AlphaIcons.Outlined.CommentsDisabled else AlphaIcons.Outlined.AddComment,
                    stringResource(if (commentsOpen) R.string.cmt_close_post else R.string.cmt_open_post),
                    colors, it,
                )
            }
            onTogglePin?.let {
                ActionRow(
                    AlphaIcons.Outlined.PushPin,
                    stringResource(if (isPinned) R.string.chat_unpin else R.string.chat_pin),
                    colors, it,
                )
            }
            ActionRow(AlphaIcons.Outlined.BookmarkAdd, stringResource(R.string.chat_save_to_saved), colors, onSaved)
            // Save photos/videos to the device gallery.
            // A GIF has its own "Save GIF": the gallery row would only repeat it.
            if (onSaveGif == null && message.isMedia && (message.mediaType == "image" || message.mediaType == "video" || message.mediaType == "album")) {
                ActionRow(AlphaIcons.Outlined.Download, stringResource(R.string.chat_save_to_gallery), colors, onSaveGallery)
            }
            onSaveGif?.let { ActionRow(AlphaIcons.Outlined.Gif, stringResource(R.string.gif_save), colors, it) }
            // A big emoji goes to my stickers as an animated one: said plainly, not "save sticker".
            onSaveSticker?.let { ActionRow(AlphaIcons.Outlined.AddReaction, stringResource(if (message.isSticker) R.string.sticker_save else R.string.emoji_to_stickers), colors, it) }
            // Share any real media/file (not link/location) to other apps.
            val isShareable = message.isMedia && message.mediaType in
                listOf("image", "video", "audio", "file", "album")
            if (isShareable) {
                ActionRow(AlphaIcons.Outlined.Share, stringResource(R.string.common_share), colors, onShare)
                ActionRow(AlphaIcons.Outlined.DeleteSweep, stringResource(R.string.chat_clear_cache), colors, onClearCache)
            }
            ActionRow(AlphaIcons.Outlined.CheckCircle, stringResource(R.string.chat_select), colors, onSelect)
 // Partial text selection: a range, not the
            // whole message. Sits next to "Select" (multi-select of messages).
            onSelectText?.let { ActionRow(AlphaIcons.Outlined.TextFields, stringResource(R.string.chat_select_text), colors, it) }
            // Edit own text messages, or the caption of own photos. NOT forwarded
 // messages — a forward is anonymous & immutable; editing it
            // is disallowed so it can never lose its "Forwarded" state.
            if (!readerOnly && message.isOutgoing && !message.isDeleted && message.forwardedFrom == null &&
                (message.text.isNotBlank() || message.isMedia) && !message.isStickerLike) { // a sticker's caption is its hidden tag
                ActionRow(
                    AlphaIcons.Outlined.Edit,
                    if (message.isMedia) stringResource(R.string.chat_edit_caption) else stringResource(R.string.common_edit),
                    colors, onEdit,
                )
            }
            if (canDelete) ActionRow(AlphaIcons.Outlined.Delete, stringResource(R.string.common_delete), colors, onDelete, danger = true)
            moderation?.invoke()
    }

    if (anchor != null && haze != null && MessageMenuPrefs.popup) {
        // Beside the message: reactions above it, the actions below — frosted glass.
        MessageMenuPopup(
            anchor = anchor,
            hole = hole,
            haze = haze,
            outgoing = message.isOutgoing,
            // Showing the emoji, a tap outside (or Back) returns to the actions.
            onDismiss = { if (pickAnyEmoji && !startWithPicker) pickAnyEmoji = false else onDismiss() },
            keepSize = pickAnyEmoji,
            reactions = if (!notSentYet && !pickAnyEmoji && hasReactions) ({
                reactionsRow(true, Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp))
            }) else null,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalCompactMenu provides true) {
                when {
                    notSentYet -> pendingList()
                    // The emoji in the same window, on the glass.
                    pickAnyEmoji -> if (reactionPolicy.anyEmoji) com.alphamessenger.ui.chat.v2.EmojiPanelV2(
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                        onPick = { onReact(it) },
                        onBackspace = {},
                        columns = 6,
                        background = Color.Transparent,
                    ) else Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReactionGrid(reactionPolicy.allowed, message.myReactions, colors, onPick = { onReact(it) }, columns = 5)
                    }
                    else -> actionList()
                }
            }
        }
        return
    }

    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        imeAware = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            when {
                notSentYet -> pendingList()
                pickAnyEmoji -> emojiPicker()
                else -> {
                    if (hasReactions) {
                        reactionsRow(false, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp))
                        HorizontalDivider(thickness = 0.5.dp, color = colors.alphaBorder)
                    }
                    actionList()
                }
            }
        }
    }
}

/** Rows of the menu shown beside the message are tighter than in the bottom panel. */
internal val LocalCompactMenu = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val tint = if (danger) colors.danger else colors.textPrimary
    val compact = LocalCompactMenu.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 11.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}


// --- Selection-mode top bar (long-press multi-select) ---

@Composable
private fun SelectionTopBar(
    count: Int,
    colors: AlphaColorScheme,
    onClose: () -> Unit,
    onForward: () -> Unit,
    onSaved: () -> Unit,
    /** Null when the selection holds something this side may not delete. */
    onDelete: (() -> Unit)?,
    onCopy: () -> Unit,
    /** Photos and videos among the selection go to the gallery (null = none to save). */
    onDownload: (() -> Unit)? = null,
    /** One channel post selected by the owner: are its comments open (null = no button). */
    commentsOpen: Boolean? = null,
    onToggleComments: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = chatStatusPad())
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = colors.textPrimary)
        }
        Text(
            "$count",
            color = colors.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (commentsOpen != null) IconButton(onClick = onToggleComments, modifier = Modifier.size(44.dp)) {
            Icon(
                if (commentsOpen) AlphaIcons.Outlined.CommentsDisabled else AlphaIcons.Outlined.AddComment,
                stringResource(if (commentsOpen) R.string.cmt_close_post else R.string.cmt_open_post),
                tint = colors.textSecondary,
            )
        }
        if (onDownload != null) IconButton(onClick = onDownload, modifier = Modifier.size(44.dp)) {
            Icon(AlphaIcons.Outlined.Download, stringResource(R.string.chat_save_to_device), tint = colors.textSecondary)
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(44.dp)) {
            Icon(AlphaIcons.Outlined.ContentCopy, stringResource(R.string.common_copy), tint = colors.textSecondary)
        }
        IconButton(onClick = onForward, modifier = Modifier.size(44.dp)) {
            Icon(AlphaIcons.Filled.KeyboardDoubleArrowRight, stringResource(R.string.common_forward), tint = colors.textSecondary)
        }
        // Quick "to Saved Messages" — one tap, no action sheet.
        IconButton(onClick = onSaved, modifier = Modifier.size(44.dp)) {
            Text("★", color = colors.textSecondary, fontSize = 20.sp)
        }
        if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
            Icon(AlphaIcons.Outlined.Delete, stringResource(R.string.common_delete), tint = colors.danger)
        }
    }
}

/** classic bottom sheet for a rejected attachment. Human text, sizes
 *  side-by-side, "Pick another file" / "Cancel". Strings from resources. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentRejectionSheet(
    rej: AttachmentRejectionUi,
    colors: com.alphamessenger.ui.theme.AlphaColorScheme,
    onPickAnother: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = { b: Long -> com.alphamessenger.ui.settings.StorageUsageViewModel.formatBytes(b) }
    val titleRes = when (rej.kind) {
        "too_big" -> com.alphamessenger.R.string.attach_reject_title_too_big
        "empty" -> com.alphamessenger.R.string.attach_reject_title_empty
        "wrong_type" -> com.alphamessenger.R.string.attach_reject_title_wrong_type
        "no_space" -> com.alphamessenger.R.string.attach_reject_title_no_space
        else -> com.alphamessenger.R.string.attach_reject_title_unreadable
    }
    val descRes = when {
        rej.kind == "too_big" -> when (rej.mediaType) {
            "image" -> com.alphamessenger.R.string.attach_reject_desc_too_big_photo
            "video" -> com.alphamessenger.R.string.attach_reject_desc_too_big_video
            "audio" -> com.alphamessenger.R.string.attach_reject_desc_too_big_audio
            "voice" -> com.alphamessenger.R.string.attach_reject_desc_too_big_voice
            else -> com.alphamessenger.R.string.attach_reject_desc_too_big_file
        }
        rej.kind == "empty" -> com.alphamessenger.R.string.attach_reject_desc_empty
        rej.kind == "wrong_type" -> com.alphamessenger.R.string.attach_reject_desc_wrong_type
        rej.kind == "no_space" -> com.alphamessenger.R.string.attach_reject_desc_no_space
        else -> com.alphamessenger.R.string.attach_reject_desc_unreadable
    }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(androidx.compose.ui.res.stringResource(titleRes), color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(androidx.compose.ui.res.stringResource(descRes), color = colors.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            if (rej.kind == "too_big") {
                Text(androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.attach_reject_selected, fmt(rej.selectedBytes)), color = colors.textPrimary, fontSize = 14.sp)
                Text(androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.attach_reject_max, fmt(rej.limitBytes)), color = colors.textSecondary, fontSize = 14.sp)
                Text(androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.attach_reject_over_by, fmt(rej.selectedBytes - rej.limitBytes)), color = colors.danger, fontSize = 13.sp)
            } else if (rej.kind == "no_space") {
                Text(androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.attach_reject_selected, fmt(rej.selectedBytes)), color = colors.textPrimary, fontSize = 14.sp)
                Text(androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.attach_reject_free, fmt(rej.limitBytes)), color = colors.textSecondary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.accent)
                    .clickable(onClick = onPickAnother).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.attach_reject_pick_another), color = colors.bgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onDismiss).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.attach_reject_cancel), color = colors.textMuted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private val LocalPinnedHaze = androidx.compose.runtime.compositionLocalOf<com.alphamessenger.ui.components.GlassState?> { null }

/** The pinned message: a frosted pill floating under the header. */
@Composable
private fun PinnedBar(
    text: String,
    index: Int,
    count: Int,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
    onUnpin: (() -> Unit)?,
    closeLabel: String = "",
) {
    val text = com.alphamessenger.ui.chat.text.Markup.plain(text)
    val pill = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (ChatPanels.floating) 16.dp else 8.dp).padding(top = 6.dp)
            .then(
                LocalPinnedHaze.current?.let { haze ->
                    Modifier.glassPanel(haze, pill, tint = tintOf(colors.bgSecondary, ChatPanels.opacity), backdrop = colors.bgChat, blur = ChatPanels.blur.dp)
                } ?: Modifier.clip(pill).background(colors.bgSecondary)
            )
            .clip(pill)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
 // One segment per pin; the shown one is lit.
        Column(Modifier.width(3.dp).height(36.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val segs = count.coerceAtMost(4)
            repeat(segs) { i ->
                val lit = if (count <= 4) i == index else i == (index * segs / count)
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(2.dp))
                        .background(if (lit) colors.accent else colors.accent.copy(alpha = 0.3f)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (count > 1) stringResource(R.string.chat_pinned_count, index + 1, count) else stringResource(R.string.chat_pinned_title),
                color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                text, color = colors.textPrimary, fontSize = 14.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (onUnpin != null) {
            IconButton(onClick = onUnpin, modifier = Modifier.size(40.dp)) {
                Icon(AlphaIcons.Filled.Close, closeLabel, tint = colors.textMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Title/artist read from a music file — once per file, not on every scroll-in. */
private val MusicTagCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

/** Channel entries of the chat menu: [reader] = a subscriber; [onLeave] null for the owner. */
class ChannelMenu(val reader: Boolean, val onLeave: (() -> Unit)?)

/** Does this bubble carry the tail? Never in a channel (its posts are plain rounded). */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun hasTail(m: MessageUi): Boolean =
    !m.isConsecutive && com.alphamessenger.ui.channel.LocalChannelPostWidth.current == null

/** "2 messages · photo, video" — what the forward picker is about to send. */
@Composable
internal fun forwardSummary(msgs: List<MessageUi>): String {
    val n = msgs.sumOf { it.albumItems?.size ?: 1 }
    val kinds = buildList {
        if (msgs.any { it.albumItems != null || (it.isMedia && it.mediaType == "image") }) add(stringResource(R.string.fwd_kind_photo))
        if (msgs.any { it.isMedia && it.mediaType == "video" }) add(stringResource(R.string.fwd_kind_video))
        if (msgs.any { it.isMedia && it.mediaType == "voice" }) add(stringResource(R.string.fwd_kind_voice))
        if (msgs.any { it.isMedia && it.mediaType in setOf("file", "audio") }) add(stringResource(R.string.fwd_kind_file))
    }
    val head = androidx.compose.ui.res.pluralStringResource(R.plurals.fwd_messages, n, n)
    return if (kinds.isEmpty()) head else head + " · " + kinds.joinToString(", ")
}

/**
 * A tapped album item. Here already: it opens. A video still on its way opens too: the
 * viewer plays it as it arrives (the download, if any, goes on), like a lone video does.
 */
private fun openAlbumItem(
    m: MessageUi,
    cachedMediaPath: ((String) -> String?)?,
    onMediaClick: (path: String, messageId: String) -> Unit,
) {
    val path = m.localMediaPath ?: m.mediaRefJson?.let { r -> cachedMediaPath?.invoke(r) }
    when {
        path != null -> onMediaClick(path, m.id)
        m.mediaType == "video" && m.mediaRefJson != null -> onMediaClick("", m.id)
    }
}

/** Every match of the in-chat search, day by day; a tap goes to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSearchList(
    hits: List<ChatViewModel.InChatHit>,
    query: String,
    current: String?,
    colors: AlphaColorScheme,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val you = stringResource(R.string.chat_you)
    val today = java.time.LocalDate.now()
    val dayFmt = java.time.format.DateTimeFormatter.ofPattern("d MMMM")
    val yearFmt = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")
    val todayLabel = stringResource(R.string.common_today)
    val yesterdayLabel = stringResource(R.string.common_yesterday)
    val zone = java.time.ZoneId.systemDefault()
    val dated = remember(hits) {
        hits.mapIndexed { i, h -> Triple(i, h, runCatching { java.time.OffsetDateTime.parse(h.at).atZoneSameInstant(zone) }.getOrNull()) }
    }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.chat_search_found, hits.size, hits.size),
            color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
        )
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            dated.groupBy { it.third?.toLocalDate() }.forEach { (day, rows) ->
                item(key = "d_" + day) {
                    Text(
                        when (day) {
                            null -> ""
                            today -> todayLabel
                            today.minusDays(1) -> yesterdayLabel
                            else -> day.format(if (day.year == today.year) dayFmt else yearFmt)
                        },
                        color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(rows.size, key = { "h_" + rows[it].second.id }) { k ->
                    val (i, h, at) = rows[k]
                    val on = h.id == current
                    Row(
                        Modifier.fillMaxWidth().background(if (on) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onPick(i) }.padding(horizontal = 20.dp, vertical = 9.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (h.outgoing) you else h.sender.ifBlank { stringResource(R.string.participant_fallback) },
                                color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            )
                            Text(
                                com.alphamessenger.ui.chatlist.searchMarked(com.alphamessenger.ui.chat.text.SearchMatch.snippet(h.text, query), query, colors),
                                color = colors.textSecondary, fontSize = 14.sp, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            at?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")).orEmpty(),
                            color = colors.textMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp, top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The in-chat search steps: "3 of 14" and the arrows to older / newer matches. */
@Composable
private fun ChatSearchSteps(
    position: Int,
    count: Int,
    searching: Boolean,
    colors: AlphaColorScheme,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onCalendar: () -> Unit,
    onList: () -> Unit,
) {
    Row(
        // The bar itself opens every match as a list, by day.
        Modifier.fillMaxWidth().clickable(enabled = count > 0, onClick = onList)
            .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCalendar, modifier = Modifier.size(44.dp)) {
            Icon(AlphaIcons.Outlined.CalendarMonth, stringResource(R.string.chat_search_date), tint = colors.textSecondary)
        }
        Text(
            when {
                searching -> ""
                count == 0 -> stringResource(R.string.chat_search_none)
                else -> stringResource(R.string.chat_search_position, position + 1, count)
            },
            color = colors.textPrimary, fontSize = 15.sp,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        val step: @Composable (Boolean, androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit) -> Unit = { on, icon, label, click ->
            IconButton(
                onClick = click, enabled = on,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(colors.bgTertiary),
            ) { Icon(icon, label, tint = if (on) colors.accent else colors.textMuted) }
        }
        step(position + 1 < count, AlphaIcons.Filled.KeyboardArrowUp, stringResource(R.string.chat_search_older), onOlder)
        Spacer(Modifier.width(8.dp))
        step(position > 0, AlphaIcons.Filled.KeyboardArrowDown, stringResource(R.string.chat_search_newer), onNewer)
    }
}

/** No floating menu over the selection: the sheet's own buttons do copy and reply. */
private object NoTextToolbar : androidx.compose.ui.platform.TextToolbar {
    override val status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {}
}

/**
 * "Select text": the message in a card to select from, and under it what can be
 * done — copy all; or, with a part selected, that part as a quote and Copy / Reply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectTextSheet(
    text: String,
    canReply: Boolean,
    colors: AlphaColorScheme,
    onReply: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // What it reads as (no markers), so a quoted part is the words they see.
    val body = remember(text) { com.alphamessenger.ui.chat.text.Markup.plain(text.removePrefix(FWD_MARK)) }
    var picked by remember(text) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(body)) }
    val part = picked.selection.takeIf { !it.collapsed }?.let { body.substring(it.min, it.max).trim() }.orEmpty()
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_select_text), color = colors.textPrimary, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textSecondary)
                }
            }
            Box(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(18.dp))
                    .background(colors.bgTertiary).padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalTextToolbar provides NoTextToolbar) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = picked,
                        onValueChange = { picked = it.copy(text = body) },
                        readOnly = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 16.sp, lineHeight = 23.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                }
            }
            androidx.compose.animation.AnimatedContent(
                targetState = part.isNotEmpty(),
                label = "select-actions",
                modifier = Modifier.padding(top = 12.dp),
            ) { has ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (has) {
                        // The part that will be quoted, as it will read.
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.accent.copy(alpha = 0.10f))
                                .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                        ) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accent))
                            Text(
                                "\u275D " + part, color = colors.textPrimary, fontSize = 14.sp, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.chat_select_hint), color = colors.textMuted, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SheetButton(
                            label = stringResource(if (has) R.string.common_copy else R.string.chat_copy_all),
                            icon = AlphaIcons.Outlined.ContentCopy,
                            filled = !(has && canReply),
                            colors = colors,
                            modifier = Modifier.weight(1f),
                        ) { onCopy(part.ifEmpty { body }) }
                        if (has && canReply) SheetButton(
                            label = stringResource(R.string.chat_reply_quote),
                            icon = AlphaIcons.AutoMirrored.Outlined.Reply,
                            filled = true,
                            colors = colors,
                            modifier = Modifier.weight(1f),
                        ) { onReply(part) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(15.dp))
            .background(if (filled) colors.accent else colors.bgTertiary)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (filled) colors.bgPrimary else colors.textPrimary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (filled) colors.bgPrimary else colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A bot's past conversations: start a new one, go back to one, or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantConversationsSheet(
    viewModel: ChatViewModel,
    memoryOn: Boolean,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<uniffi.alphamessenger.AssistantConversation>?>(null) }
    LaunchedEffect(Unit) { items = viewModel.assistantConversations() }
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.assistant_conversations), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ChatMenuRow(AlphaIcons.Outlined.AddComment, stringResource(R.string.assistant_new_conversation), colors, tintOverride = colors.accent, onClick = onNew)
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() -> Text(
                    stringResource(R.string.assistant_conversations_empty), color = colors.textMuted, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
                else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(list.size, key = { list[it].chatId }) { i ->
                        val c = list[i]
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(c.chatId) }.padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.title.ifBlank { stringResource(R.string.assistant_conversation_untitled) },
                                    color = colors.textPrimary, fontSize = 15.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    compactTime(c.lastAt) + " · " + pluralStringResource(R.plurals.assistant_conversation_messages, c.count.toInt(), c.count.toInt()),
                                    color = colors.textMuted, fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    viewModel.deleteAssistantConversation(c.chatId)
                                    items = viewModel.assistantConversations()
                                }
                            }) {
                                Icon(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.common_delete), tint = colors.textMuted)
                            }
                        }
                    }
                }
            }
            if (memoryOn) Text(
                stringResource(R.string.assistant_conversations_memory), color = colors.textMuted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}
