package com.alphamessenger.ui.media.preview

import com.alphamessenger.ui.icons.AlphaIcons
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Viewer pages — one per attachment kind.
 *
 * Each knows only its own [MediaDraftItem] and stores nothing: deletion,
 * order and caption live in the set.
 */
@Composable
internal fun PreviewPage(
    item: MediaDraftItem,
    isActive: Boolean,
    colors: AlphaColorScheme,
    onZoomChange: (Boolean) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The page runs on under the bottom bar; its own controls stay above it. */
    controlsInset: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Hairline,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (item.kind) {
            MediaDraftItem.Kind.IMAGE ->
                if (item.sendAsFile) FileCard(item, colors)
                // Fitted into what is seen above the bar, not the whole page under it: a
                // tall shot centred on the full height sat low, a gap above it.
                else Box(Modifier.fillMaxSize().padding(bottom = controlsInset)) { ZoomableBox(resetKey = item.uri, onZoomChange = onZoomChange) {
                    // The SAME rendering as in the editor, and the same transform order as at
                    // send time: the preview must show exactly what the recipient will get —
                    // crop included.
                    com.alphamessenger.ui.media.editor.EditorImage(
                        uri = item.uri,
                        transform = com.alphamessenger.ui.media.editor.EditorTransform.of(item),
                        markup = androidx.compose.runtime.remember(item.markup) { com.alphamessenger.ui.media.editor.PhotoMarkup.fromJson(item.markup) },
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = item.filename ?: stringResource(R.string.preview_photo),
                    )
                } }

            MediaDraftItem.Kind.VIDEO ->
                if (item.sendAsFile) FileCard(item, colors)
                else VideoPreviewPage(item, isActive, colors, onScrubbingChange, controlsInset)

            MediaDraftItem.Kind.AUDIO -> AudioPage(item, isActive, colors)
            MediaDraftItem.Kind.FILE -> FileCard(item, colors)
        }
    }
}

/** Audio: can be listened to before sending without leaving the viewer. */
@Composable
private fun AudioPage(item: MediaDraftItem, isActive: Boolean, colors: AlphaColorScheme) {
    val context = LocalContext.current
    val player = remember(item.uri, isActive) {
        if (!isActive) null else ExoPlayer.Builder(context).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(context)).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    var playing by remember(player) { mutableStateOf(false) }
    var positionMs by remember(player) { mutableStateOf(0L) }
    var durationMs by remember(player) { mutableStateOf(item.durationMs) }
    LaunchedEffect(player) {
        while (player != null) {
            playing = player.isPlaying
            positionMs = player.currentPosition.coerceAtLeast(0L)
            player.duration.takeIf { it > 0 }?.let { durationMs = it }
            delay(200)
        }
    }

    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = 28.dp)
            .clip(RoundedCornerShape(28.dp)).background(colors.bgSecondary)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Box(
            modifier = Modifier.size(88.dp).clip(CircleShape).background(colors.accent)
                .clickable(enabled = player != null) {
                    if (player?.isPlaying == true) player.pause() else player?.play()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) AlphaIcons.Filled.Pause else AlphaIcons.Filled.PlayArrow,
                if (playing) stringResource(R.string.editor_pause) else stringResource(R.string.audio_listen),
                tint = Color.White, modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            item.filename ?: stringResource(R.string.preview_audio),
            color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(colors.textMuted.copy(alpha = 0.3f)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxSize()
                    .background(colors.accent),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(clock(positionMs), color = colors.textMuted, fontSize = 12.sp)
            Spacer(Modifier.fillMaxWidth(1f).weight(1f))
            Text(clock(durationMs), color = colors.textMuted, fontSize = 12.sp)
        }
    }
}

/** Document: show exactly what the recipient will get. Content is not opened. */
@Composable
internal fun FileCard(item: MediaDraftItem, colors: AlphaColorScheme) {
    val name = item.filename ?: stringResource(R.string.preview_file)
    val ext = name.substringAfterLast('.', "").uppercase().take(4)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = 28.dp)
            .clip(RoundedCornerShape(28.dp)).background(colors.bgSecondary)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(fileTint(ext)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ext.ifBlank { "FILE" },
                color = Color.White, fontSize = if (ext.length > 3) 17.sp else 21.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            name,
            color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            item.sizeBytes.takeIf { it > 0 }?.let { humanSize(it) },
            item.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                meta, color = colors.textMuted, fontSize = 13.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Tile color by file type — a set of several documents reads at a glance. */
internal fun fileTint(ext: String): Color = when (ext.lowercase()) {
    "pdf" -> Color(0xFFE05252)
    "doc", "docx", "rtf", "odt" -> Color(0xFF4A7DE0)
    "xls", "xlsx", "csv", "ods" -> Color(0xFF3FA463)
    "ppt", "pptx" -> Color(0xFFE08A3C)
    "zip", "rar", "7z", "tar", "gz" -> Color(0xFFB08A4A)
    "apk" -> Color(0xFF3EA88F)
    "mp3", "m4a", "ogg", "wav", "flac" -> Color(0xFF8A6FD6)
    "txt", "log", "json", "xml" -> Color(0xFF7A7F8A)
    else -> Color(0xFF5B6270)
}

internal fun humanSize(bytes: Long): String = com.alphamessenger.media.MediaSizeFormat.short(bytes)

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * A video frame for the poster.
 *
 * The metadata rotation is applied BY US: some devices return the frame as it
 * lies in the file, unrotated, while the player rotates it. The poster and
 * the video then had different shapes, and at playback start the picture
 * resized — the frame flicker at the sides.
 */
internal fun videoFrame(
    context: android.content.Context,
    uri: Uri,
    /** Frame timestamp. For a trimmed video this is the START of the span, not of the file. */
    atMs: Long = 0L,
): ImageBitmap? = runCatching {
    val retriever = android.media.MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val frame = retriever.getFrameAtTime(atMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return@runCatching null
        val rotation = retriever
            .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        // The frame is already rotated (width/height match the declared ones) — do
        // not rotate a second time.
        val declaredW = retriever
            .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val needsRotate = rotation % 360 != 0 && declaredW != null && frame.width == declaredW
        val fixed = if (!needsRotate) frame else {
            val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            android.graphics.Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
                .also { if (it != frame) frame.recycle() }
        }
        fixed.asImageBitmap()
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()
