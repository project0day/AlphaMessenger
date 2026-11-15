package com.alphamessenger.transfer

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R

/**
 * THE transfer overlay. Exactly one. For every media type.
 *
 * Photo · Video · Voice · Music · File · Forward · ViewOnce · Album — upload or
 * download — all draw this component and nothing else. It reads a
 * [TransferSnapshot] (or a [TransferGroup] for an album) and renders; every tap
 * goes through [TransferEngine.dispatch] — the UI decides nothing.
 *
 * ## Visual consistency
 *
 * - ONE ring style: CircularProgressIndicator, White, strokeWidth 3.dp.
 * - Overlay 2.0: NO rectangular full-media scrim (it broke rounded-media corners).
 *   The ONLY dark backing is the circular disc under the glyph (fully round).
 * - ONE overlay background: Color.Black copy(alpha = 0.45f) on the ring disc.
 * - ONE detail line style: White, 11.sp, Medium, pill background.
 * - ONE animation set: fadeIn(150ms) on enter, fadeOut(200ms) on exit.
 * - ONE diameter per surface: 54.dp for visual media (photo/video/voice/music/
 *   album), 44.dp for file disc.
 *
 * ## Completed fade-out
 *
 * On [TransferState.Completed] / [TransferState.Cancelled] the overlay does NOT
 * vanish instantly — it fades out over ~200ms via [AnimatedVisibility], so the
 * transition to the media's own ▶ affordance (or the delivery ✓/✓✓) is smooth.
 * Callers must pass the Completed snapshot (not null) for the fade to play.
 *
 * ## Paused
 *
 * Paused → ↓ over a still arc of how far it got. Tap → Start: the download
 * carries on from the `.part` the stopped one left.
 */
/**
 * THE Media Surface. ONE surface for Photo · Video · Album.
 *
 * `MediaSurface = MediaContent + MediaOverlay`. Every visual-media bubble whose
 * content fills a box (photo, video poster, album tile) composes THIS: the caller
 * supplies only the [content] (the image / poster / placeholder — "MediaContent")
 * and the surface stamps the single [TransferOverlay] on top ("MediaOverlay").
 * There is no Photo/Video/Album-specific overlay — this is the only one.
 *
 * File (a card, not a filled box) and Voice (a waveform) are NOT MediaSurfaces,
 * but they draw the SAME [TransferOverlay] — visually one system, the surface
 * layout differs by intent.
 */
@Composable
fun MediaSurface(
    state: FileUiState,
    onDownloadAction: (TransferAction) -> Unit,
    onUploadAction: (TransferAction) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = OverlaySpec.DIAMETER,
    /** Bottom-END length (video/audio) — shown ALWAYS, even during transfer. */
    durationLabel: String? = null,
    idleSizeBytes: Long = 0L,
    showSizeLabel: Boolean = true,
    /** Round media (video messages): corners lie outside the circle. */
    circular: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        content()
        TransferOverlay(
            state = state,
            onDownloadAction = onDownloadAction,
            onUploadAction = onUploadAction,
            onOpen = onOpen,
            modifier = Modifier.matchParentSize(),
            diameter = diameter,
            durationLabel = durationLabel,
            idleSizeBytes = idleSizeBytes,
            scrim = true,
            showSizeLabel = showSizeLabel,
            circular = circular,
        )
    }
}

/**
 * THE dumb overlay. Renders a [FileUiState] and nothing else.
 *
 * It computes NOTHING about the transfer — the decision already happened in
 * [fileUiStateOf]. It maps the seven states to the single ring/glyph vocabulary
 * and routes each tap to the correct sink ([onDownloadAction] / [onUploadAction]
 * / [onOpen]) — the bubble never decides upload-vs-download itself.
 */
/**
 * Universal overlay geometry. ONE spec for every media type.
 * (per the design contract — no per-type sizes exist.)
 */
object OverlaySpec {
    val DIAMETER = 56.dp          // standard control disc (photo/video/voice/music)
    val DIAMETER_SMALL = 42.dp    // small album tiles
    val DIAMETER_FILE = 44.dp     // file-card disc
    val RING = 3.dp               // ring thickness
    val ICON_FRACTION = 0.34f     // centre glyph ≈ 19dp on a 56dp disc — modest, never fills the circle (BLOCK G)
 // No % digits inside the circle: the ring arc conveys progress;
    // the centre shows ✕ (cancel) / ▷ / ↻ / ↓ / ▶.
    val SIZE_SP = 11              // top-start size / EXT badge
    val DURATION_SP = 12          // bottom-end duration label
    val CORNER = 12.dp            // thumbnail corner radius
    val LABEL_PAD = 8.dp          // badge inset
    val WHITE = Color.White.copy(alpha = 0.90f)
    val SHADOW = androidx.compose.ui.graphics.Shadow(
        Color.Black.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, 1f), 3f,
    )
}

@Composable
fun TransferOverlay(
    state: FileUiState,
    onDownloadAction: (TransferAction) -> Unit,
    onUploadAction: (TransferAction) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = OverlaySpec.DIAMETER,
    /** Bottom-END label (video/audio length) — shown ALWAYS, even mid-transfer. */
    durationLabel: String? = null,
    /** Drives the bottom-START size label (download bytes/total, or ↓ size). */
    idleSizeBytes: Long = 0L,
    scrim: Boolean = false,
    /** File cards render bytes in the text row, so the corner size
     *  badge is suppressed there (button area stays glyphs-only). Default true
     *  keeps the badge for photo/video/album surfaces. */
    showSizeLabel: Boolean = true,
    /** Round media: the size goes under the control, inside the circle — a
     *  corner badge would be cut off by the round clip. */
    circular: Boolean = false,
) {
    val hasControl = when (state) {
        is FileUiState.Ready -> state.playable   // Ready video → ▶; Ready photo → nothing
        FileUiState.Hidden -> false
        else -> true
    }
    val sizeLabel = if (showSizeLabel) overlaySizeLabel(state, idleSizeBytes, compact = diameter < OverlaySpec.DIAMETER) else null
    val visible = hasControl || durationLabel != null || sizeLabel != null
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // NO rectangular full-media scrim — it painted sharp corners over rounded
            // media and broke the image's rounding. Glyph contrast comes SOLELY from
            // the circular disc backing in OverlayControl (fully round → never sharp
            // corners, always respects the media's rounded shape). `scrim` is kept for
            // source-compat; a shaped media-clipped dim may return later as a
            // progressive enhancement.
            if (hasControl) {
                OverlayControl(state, diameter, onDownloadAction, onUploadAction, onOpen, Modifier.align(Alignment.Center))
            }
            // Size badge — TOP-START, a neat corner label the SAME size
            // as the duration (bottom-END). "1.2 / 5 MB" while downloading, the
            // total when idle/uploading. NEVER inside the circle.
            sizeLabel?.let {
                if (circular) {
                    // Centred just below the control disc, on a soft pill so it
                    // reads over any frame.
                    androidx.compose.foundation.layout.Box(
                        Modifier.align(Alignment.Center)
                            .offset(y = diameter / 2 + 16.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { OverlayLabel(it, OverlaySpec.DURATION_SP, FontWeight.Normal, Modifier) }
                } else {
                    OverlayLabel(it, OverlaySpec.DURATION_SP, FontWeight.Normal, Modifier.align(Alignment.TopStart).padding(OverlaySpec.LABEL_PAD))
                }
            }
            // Duration — ALWAYS bottom-end, survives the whole transfer.
            durationLabel?.let {
                OverlayLabel(it, OverlaySpec.DURATION_SP, FontWeight.Medium, Modifier.align(Alignment.BottomEnd).padding(OverlaySpec.LABEL_PAD))
            }
        }
    }
}

/** The centre disc: ring + (% | ▷ | ↻ | ↓ | ▶). Same geometry for every type. */
@Composable
private fun OverlayControl(
    state: FileUiState,
    diameter: Dp,
    onDownloadAction: (TransferAction) -> Unit,
    onUploadAction: (TransferAction) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val tap: (() -> Unit)? = when (state) {
 // Active → the centre ✕ cancels the transfer. Same gesture
        // both directions; the ring conveys the %.
        // Sender ✕ CANCELS the upload (terminal — the message is removed).
        is FileUiState.Uploading -> ({ onUploadAction(TransferAction.Cancel) })
        // Waiting for the network — ✕ cancels the send the same way as with live connectivity.
        // ↻ is deliberately not offered here: there is nothing to retry, the work
        // resumes on its own.
        is FileUiState.WaitingNetwork -> ({ onUploadAction(TransferAction.Cancel) })
        // Receiver ✕ PAUSES the download — bytes are kept, ▷ resumes.
        // It must NOT cancel / delete / fail the peer's message. Same gesture,
        // correct verb per direction; uniform for Photo/Video/Album/Music/File/
        // Voice because every type funnels through this one OverlayControl.
        is FileUiState.Downloading -> ({ onDownloadAction(TransferAction.Pause) })
        is FileUiState.Paused -> ({ onDownloadAction(TransferAction.Start) })
        is FileUiState.Failed -> when (state.direction) {
            TransferDirection.UPLOAD -> ({ onUploadAction(TransferAction.Retry) })
            TransferDirection.DOWNLOAD -> ({ onDownloadAction(TransferAction.Retry) })
        }
        FileUiState.NeedDownload -> ({ onDownloadAction(TransferAction.Start) })
        // Asks the servers once more: a copy may have come back.
        is FileUiState.Gone -> ({ onDownloadAction(TransferAction.Start) })
        is FileUiState.Ready -> if (state.playable) ({ onOpen() }) else null
        FileUiState.Sent, FileUiState.Hidden -> null
    }
    val iconSize = diameter * OverlaySpec.ICON_FRACTION
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .let {
                if (tap != null) {
                    it.clickable {
                        tap()
                    }
                } else {
                    it
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val p = state.progressOrNull
        // A spinning ring means work. While waiting for the network there is no
        // work: the position shows as a motionless arc, and with no known position
        // there is no ring at all — spinning it would lie that something happens.
        val waiting = state is FileUiState.WaitingNetwork
        // Stopped part-way: a still arc shows how far it got; ↓ carries on from there.
        if (state is FileUiState.Paused && p != null && p.isDeterminate) {
            CircularProgressIndicator(
                progress = { p.fraction.coerceAtLeast(0.02f) },
                color = Color.White.copy(alpha = 0.55f), strokeWidth = OverlaySpec.RING,
                modifier = Modifier.size(diameter * 0.86f),
            )
        }
        if (state.isActive && p != null && !(waiting && !p.isDeterminate)) {
            if (p.isDeterminate) {
                CircularProgressIndicator(
                    progress = { p.fraction.coerceAtLeast(0.02f) },
                    color = Color.White, strokeWidth = OverlaySpec.RING,
                    modifier = Modifier.size(diameter * 0.86f),
                )
            } else {
                CircularProgressIndicator(
                    color = Color.White, strokeWidth = OverlaySpec.RING,
                    modifier = Modifier.size(diameter * 0.86f),
                )
            }
        }
        // Centre content: ✕ while transferring (the ring shows the %), else the glyph.
        when {
            state.isActive -> Icon(
                AlphaIcons.Outlined.Close,
                when (state) {
                    is FileUiState.Uploading -> stringResource(R.string.transfer_cancel)
                    is FileUiState.WaitingNetwork -> stringResource(R.string.transfer_cancel_offline)
                    else -> stringResource(R.string.transfer_pause)
                },
                tint = Color.White, modifier = Modifier.size(iconSize),
            )
            // ↓, not ▷: a paused download is not something to play, it is one to fetch on.
            state is FileUiState.Paused -> Icon(AlphaIcons.Outlined.Download, stringResource(R.string.transfer_download), tint = Color.White, modifier = Modifier.size(iconSize))
            state is FileUiState.Failed -> Icon(AlphaIcons.Filled.Refresh, stringResource(R.string.transfer_retry), tint = Color.White, modifier = Modifier.size(iconSize))
            state is FileUiState.NeedDownload -> Icon(AlphaIcons.Outlined.Download, stringResource(R.string.transfer_download), tint = Color.White, modifier = Modifier.size(iconSize))
            state is FileUiState.Gone -> Icon(AlphaIcons.Outlined.CloudOff, stringResource(if (state.unknownServer) R.string.media_unknown_server else R.string.media_gone), tint = Color.White, modifier = Modifier.size(iconSize))
            state is FileUiState.Ready && state.playable -> Icon(AlphaIcons.Filled.PlayArrow, stringResource(R.string.editor_play), tint = Color.White, modifier = Modifier.size(iconSize))
            state is FileUiState.Sent -> Icon(AlphaIcons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(iconSize))
            else -> Unit
        }
    }
}

/** A bottom-corner label (size / duration) — white 90% + shadow, no pill. */
@Composable
private fun OverlayLabel(text: String, sizeSp: Int, weight: FontWeight, modifier: Modifier) {
    Text(
        text,
        color = OverlaySpec.WHITE,
        fontSize = sizeSp.sp,
        fontWeight = weight,
        maxLines = 1,
        style = androidx.compose.ui.text.TextStyle(shadow = OverlaySpec.SHADOW),
        modifier = modifier,
    )
}

/** Bottom-START size label: download "1.2 / 5 MB", upload/idle total, ↓ size.
 *  Neat corner badge — NEVER inside the circle. */
@androidx.compose.runtime.Composable
private fun overlaySizeLabel(state: FileUiState, idleSizeBytes: Long, compact: Boolean = false): String? = when {
    // A small tile (album grid) has no room for "12 MB / 50 MB": it shows the %.
    compact && state.isActive -> state.progressOrNull?.takeIf { it.isDeterminate }?.let { "${(it.fraction * 100).toInt()}%" }
    else -> overlayBytesLabel(state, idleSizeBytes)
}

@androidx.compose.runtime.Composable
private fun overlayBytesLabel(state: FileUiState, idleSizeBytes: Long): String? = when (state) {
    is FileUiState.Downloading ->
        if (state.progress.isDeterminate) "${humanBytes(state.progress.bytes)} / ${humanBytes(state.progress.total)}"
        else idleSizeBytes.takeIf { it > 0 }?.let(::humanBytes)
    is FileUiState.Uploading ->
        // Show sent / total ("98 MB / 355 MB") while a determinate upload runs —
        // same as Downloading — instead of only the total file size.
        if (state.progress.isDeterminate) "${humanBytes(state.progress.bytes)} / ${humanBytes(state.progress.total)}"
        else state.progress.total.takeIf { it > 0 }?.let(::humanBytes) ?: idleSizeBytes.takeIf { it > 0 }?.let(::humanBytes)
    is FileUiState.WaitingNetwork ->
        if (state.progress.isDeterminate) "${humanBytes(state.progress.bytes)} / ${humanBytes(state.progress.total)}"
        else idleSizeBytes.takeIf { it > 0 }?.let(::humanBytes)
    FileUiState.NeedDownload -> idleSizeBytes.takeIf { it > 0 }?.let(::humanBytes)
    is FileUiState.Gone -> stringResource(if (state.unknownServer) R.string.media_unknown_server else R.string.media_gone)
    else -> null
}

// ONE weight format for the whole app. A private copy here used to print
// "MB" where the rest of the UI printed the localized unit: the same file
// looked like two different numbers about different things.
private fun humanBytes(b: Long): String = com.alphamessenger.media.MediaSizeFormat.short(b)

