@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alphamessenger.ui.chat.bubbles

import com.alphamessenger.ui.icons.AlphaIcons
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image as PhotoImage
import com.alphamessenger.ui.chat.hasTail
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.media.toOverlayState
import com.alphamessenger.ui.chat.BubbleFlags
import com.alphamessenger.ui.chat.ForwardedLabel
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.STUB_MEDIA
import com.alphamessenger.ui.chat.compactTime
import com.alphamessenger.ui.theme.AlphaColorScheme

@Composable
internal fun MediaFileBubble(
    message: MessageUi,
    bubbleColor: Color,
    textColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    showSenderName: Boolean,
    senderColor: Color,
    colors: AlphaColorScheme,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onMediaActions: () -> Unit = {},
    mediaLoader: (suspend (String) -> String?)?,
    cachedMediaPath: ((String) -> String?)? = null,
    mediaProgress: kotlinx.coroutines.flow.SharedFlow<Triple<String, Long, Long>>? = null,
    mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    onMediaClick: (path: String, messageId: String) -> Unit = { _, _ -> },
    onCancelUpload: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
    /** Caption text-selection mode — shared with text messages. */
    textSelectActive: Boolean = false,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val refJson = message.mediaRefJson
    if (STUB_MEDIA) { // TEMP: trivial placeholder of the SAME size — isolates mount cost
        val ext0 = message.mediaFilename?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val thumb0 = message.mediaType == "video" || ext0 in setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts")
        androidx.compose.foundation.layout.Box(
            Modifier.width(240.dp).height(if (thumb0) 84.dp else 60.dp).clip(bubbleShape).background(bubbleColor),
        )
        return
    }
    // Unified media state — this bubble is DUMB. The TransferEngine owns policy,
    // disk checks, downloads and eviction; the bubble reads a FileUiState and
    // renders it.
    if (BubbleFlags.STATE) LaunchedEffect(refJson) {
        if (refJson != null) {
            mediaActions?.consider?.invoke(
                com.alphamessenger.transfer.TransferKey.download(refJson),
                message.mediaType, message.mediaFilename, message.mediaSizeBytes ?: 0L,
                message.isOutgoing, message.localMediaPath, message.id,
            )
        }
    }
    val mediaKey = refJson?.let { com.alphamessenger.transfer.TransferKey.download(it) }
    fun dispatchDownload(act: com.alphamessenger.transfer.TransferAction) {
        mediaKey?.let { mediaActions?.dispatch?.invoke(it, act, message.id) }
    }

    val isVideo = message.mediaType == "video"
    val isAudio = message.mediaType == "audio"
    val accent = when {
        isVideo -> Color(0xFFAB55E0)
        isAudio -> Color(0xFFFF8A34)
        else -> Color(0xFF36C46A)
    }
    // The file card's text column is ~160dp → ~22 chars on one line. Budget the
    // ellipsis for that so the ext stays visible AND the line never wraps (which
    // would push the time/status row out of the fixed-height thumbnail card).
    val title = com.alphamessenger.transfer.ellipsizeFileName(
        com.alphamessenger.ui.chat.displayFileName(message.mediaFilename) ?: when { isVideo -> stringResource(R.string.media_kind_video); isAudio -> stringResource(R.string.preview_audio); else -> stringResource(R.string.preview_file) },
        max = 24,
    )
    val subtitle = message.mediaSizeBytes?.let { humanSize(it) }
        ?: when { isVideo -> stringResource(R.string.media_kind_video); isAudio -> stringResource(R.string.preview_audio); else -> stringResource(R.string.media_document) }

    // Image/video files (incl. jpg/png/mp4 sent "as file") open in the INTERNAL
    // viewer/player; real documents open in the system app.
    val ext = message.mediaFilename?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val isImageFile = ext in IMAGE_FILE_EXTS
    val isVideoFile = isVideo || ext in VIDEO_FILE_EXTS
    // ONE source of truth. `playable` (a video file) lets OverlayControl draw the
    // ▶ affordance — the bubble itself never draws a play glyph.
    val state = (if (BubbleFlags.STATE)
        com.alphamessenger.media.rememberMediaState(message, mediaActions).toOverlayState(message.isOutgoing)
    else com.alphamessenger.transfer.FileUiState.Hidden).let { s ->
        // A video sent AS A FILE (.mp4 document) is playable, but MediaKind
        // classifies it FILE (playable=false) → no ▶. Promote a ready video-file
        // to playable so OverlayControl draws the Play affordance, same as a
        // normal video. Transfer states are untouched.
        if (isVideoFile && s is com.alphamessenger.transfer.FileUiState.Ready)
            com.alphamessenger.transfer.FileUiState.Ready(s.path, playable = true)
        else s
    }
    val openScope = androidx.compose.runtime.rememberCoroutineScope()
    fun openCached(path: String) {
        if (isImageFile || isVideoFile) onMediaClick(path, message.id) // internal viewer
        else openScope.launch { com.alphamessenger.ui.chat.openFileExternally(ctx, path, message.mediaType, message.mediaFilename) }
    }
    // "Tap → download then auto-open" preserved: remember the intent and open
    // once the manager reports Cached.
    var pendingOpen by remember(message.id) { mutableStateOf(false) }
    val readyPath = state.readyPath
    LaunchedEffect(readyPath) {
        if (readyPath != null && pendingOpen) { pendingOpen = false; openCached(readyPath) }
    }
    // Open when the bytes are here; else request the download and auto-open on
    // arrival. A running transfer is driven by the overlay ✕ — not this tap.
    fun onOpen() {
        if (state.isActive) return
        val p = readyPath
        if (p != null) { openCached(p); return }
        if (isImageFile || isVideoFile) pendingOpen = true
        dispatchDownload(com.alphamessenger.transfer.TransferAction.Start)
    }

    // Thumbnail preview when sent "as file": peer reads poster from the ref;
    // sender falls back to the local original (image / video frame).
    // Decoded up-front so an image/video file can use a FLUSH thumbnail layout.
    // STABLE cache key = message id (a ref/path-derived key changes once the
    // poster arrives after upload → re-decode + flicker on every scroll). Keyed
    // by id, the thumbnail is decoded ONCE (off the main thread) and reused.
    // Poster b64 (tiny) is tried first, else a SMALL sampled decode of the local
    // file (fast, never the full 12 MP bitmap).
    val posterKey = message.id
    val hasThumbFile = isImageFile || isVideoFile
    // The sender's own original is on this phone from the first second of the send:
    // the preview comes from it at once, not when the upload is done.
    val thumbSource = readyPath ?: message.localMediaPath?.takeIf { message.isOutgoing }
    val posterState = if (BubbleFlags.POSTER) androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        DocumentThumbCache.get(posterKey), message.mediaPosterB64, thumbSource,
    ) {
        if (value != null) return@produceState // already cached — no work, no jank
        // Only a picture or a video has a frame to show; a large archive is never decoded,
        // and a file that gave nothing once is not tried again on every scroll.
        if (message.mediaPosterB64.isNullOrBlank() && (!hasThumbFile || posterKey in DocumentThumbFailed)) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bmp = message.mediaPosterB64?.takeIf { it.isNotBlank() }?.let {
                    val b = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                    com.alphamessenger.util.BoundedBitmap.decode(b)?.asImageBitmap()
                } ?: thumbSource?.takeIf { hasThumbFile }?.let { path ->
                    // Sampled decode: read bounds, then decode down to ~512px so the
                    // thumb is O(small) — never the full 12 MP bitmap (fast + no OOM).
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    com.alphamessenger.data.MediaVault.decodeFile(path, bounds)
                    var s = 1
                    while (bounds.outWidth / s > 512 || bounds.outHeight / s > 512) s *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
                    com.alphamessenger.data.MediaVault.decodeFile(path, opts)?.asImageBitmap()
                        ?: run {
                            val r = android.media.MediaMetadataRetriever()
                            try { com.alphamessenger.data.MediaVault.setSource(r, path); r.getFrameAtTime(0)?.asImageBitmap() }
                            finally { r.release() }
                        }
                }
                if (bmp != null) DocumentThumbCache.put(posterKey, bmp)
                else if (thumbSource != null) DocumentThumbFailed.add(posterKey)
                bmp
            }.getOrNull()
        }
    } else remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val posterBmp = posterState.value
    // Image/video sent "as file" → LARGE thumbnail flush to the bubble's top /
 // left / bottom edges. Plain documents keep the compact
    // 46dp icon disc with normal padding.
    // hasThumb is decided from the EXTENSION (known synchronously), NOT from
    // "poster decoded yet" — so the bubble reserves the 84dp thumb size from its
    // FIRST frame and the decoded image just fills it (no grow/flip on decode).
    val hasThumb = isImageFile || isVideoFile
    val discTap: () -> Unit = { onOpen() }
    if (BubbleFlags.RENDER) {
    // A thumbnail card is a fixed 84dp row: reactions or a caption never fit in
    // it (they were cut, "+N" and the time lost). With either, the footer
    // (reactions, time) goes below the card at the bubble's full width.
    val footerBelow = !hasThumb || message.reactions.isNotEmpty() || !message.mediaCaption.isNullOrBlank()
    Column(
        // ONE shell for shape/clip/background/click/width — see BubbleGeometry.bubbleShell.
        // The file card keeps its OWN compact insets (4dp base, 11dp tail side to
        // clear the tail wedge).
        modifier = Modifier.bubbleShell(
            isOutgoing = message.isOutgoing,
            // The viewer's element flight must start from the THUMBNAIL, not the
            // whole card (different shape/size — the frame would "bounce" at the
            // end of the flight). When a thumbnail exists, it publishes its own bounds.
            registerId = if (hasThumb) null else message.id,
            showTail = hasTail(message),
            bubbleShape = bubbleShape,
            bubbleColor = bubbleColor,
            maxWidth = 274.dp,
            onTap = onMediaActions,
            onLongPress = onLongPress,
            contentPadding = BubbleTokens.compactPadding(
                message.isOutgoing, hasTail(message),
                top = if (hasThumb) 3.dp else 4.dp, bottom = 3.dp,
            ),
        ),
    ) {
        if (message.forwardedFrom != null) ForwardedLabel(message.isOutgoing, colors)
        if (showSenderName) {
            Text(
                text = message.senderName ?: "",
                color = senderColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp, start = if (hasThumb) 10.dp else 0.dp, end = if (hasThumb) 10.dp else 0.dp),
            )
        }
        Row(
            modifier = if (hasThumb) Modifier.height(84.dp) else Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val discSize = if (hasThumb) 84.dp else 46.dp
            val discShape = if (hasThumb)
                // Round ONLY the outer (left) corners; the side facing the
                // description stays square.
                androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = com.alphamessenger.transfer.OverlaySpec.CORNER,
                    bottomStart = com.alphamessenger.transfer.OverlaySpec.CORNER,
                    topEnd = 0.dp, bottomEnd = 0.dp,
                )
            else CircleShape
            // THE one path: MediaSurface(content) + TransferOverlay. This bubble
            // draws NO transfer glyph of its own — ▶ / ↓ / % / ↻ / ▷ / ring all
            // come from OverlayControl. The disc content is the static file
            // representation (poster thumbnail, or the neutral file-type icon),
            // exactly like a photo is content — never a control affordance.
            if (BubbleFlags.DISC) {
            // While the file is open in the viewer, the card renders its shape only.
            val hiddenWhileViewing = com.alphamessenger.ui.chat.media.MediaSourceRegistry.isViewing(message.id)
            com.alphamessenger.transfer.MediaSurface(
                state = state,
                onDownloadAction = { act -> dispatchDownload(act) },
                onUploadAction = { act ->
                    when (act) {
                        com.alphamessenger.transfer.TransferAction.Cancel -> onCancelUpload()
                        com.alphamessenger.transfer.TransferAction.Retry -> onRetryUpload()
                        else -> {}
                    }
                },
                onOpen = { onOpen() },
                modifier = Modifier
                    .then(
                        // The viewer takes exactly these bounds: the thumbnail
                        // square, not the message row.
                        if (hasThumb) {
                            Modifier.onGloballyPositioned { coords ->
                                com.alphamessenger.ui.chat.media.MediaSourceRegistry
                                    .put(message.id, coords.boundsInWindow())
                            }
                        } else {
                            Modifier
                        },
                    )
                    .alpha(if (hiddenWhileViewing) 0f else 1f)
                    .size(discSize)
                    .clip(discShape)
                    .then(if (hasThumb) Modifier else Modifier.background(accent.copy(alpha = 0.20f)))
                    .clickable(onClick = discTap),
                diameter = com.alphamessenger.transfer.OverlaySpec.DIAMETER_FILE,
                idleSizeBytes = 0L,
                showSizeLabel = false,
            ) {
                if (posterBmp != null) {
                    PhotoImage(
                        bitmap = posterBmp,
                        contentDescription = title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Icon(
                        fileTypeIcon(ext),
                        contentDescription = title,
                        tint = accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            } else { // FLAG off: cheap same-size placeholder (no overlay/ring/thumb)
                Box(Modifier.size(discSize).clip(discShape).background(accent.copy(alpha = 0.20f)))
            }
            if (!hasThumb) Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.widthIn(max = 180.dp).then(
                    if (hasThumb) Modifier.fillMaxHeight().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)
                    else Modifier,
                ),
                verticalArrangement = if (hasThumb) Arrangement.SpaceBetween else Arrangement.Top,
            ) {
              Column {
                if (BubbleFlags.TITLE) Text(
                    // ONE line always: a 2-line name in the fixed-height thumbnail
                    // card pushed the time/status row out of view. ellipsizeFileName
                    // already keeps the extension, so a single line is enough.
                    text = title,
                    color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // While uploading (or downloading) show a premium live line:
                // "42% · 1.2 MB/s" over a thin linear bar — fully readable, no
                // overlap with the disc's cancel ✕.
                // FIXED HEIGHT: the card never grows/shrinks during a transfer. The
                // live % lives on the disc (the overlay ring), so this line is
                // ALWAYS the static "size · EXT" — no progress bar that expands.
                if (BubbleFlags.SUBTITLE) {
                if (isVideo) {
                    Text(
                        text = if (state.isReady) stringResource(R.string.media_downloaded) else subtitle,
                        color = textColor.copy(alpha = 0.6f), fontSize = 12.sp,
                    )
                } else {
 // classic doc line: [icon] size · EXT
                    val extLabel = message.mediaFilename?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() }?.uppercase()
                    // Never the running byte count: its changing width made the
                    // bubble breathe during a transfer. Progress is the disc's ring.
                    val sizeStr = message.mediaSizeBytes?.let { humanSize(it) }
                        ?: state.progressOrNull?.takeIf { it.isDeterminate }?.let { humanSize(it.total) }
                    val docLine = listOfNotNull(sizeStr, extLabel).joinToString(" · ")
                        .ifBlank { subtitle }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            fileTypeIcon(message.mediaFilename?.substringAfterLast('.', "")),
                            contentDescription = null,
                            tint = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        // During a transfer: "120.5 MB / 1.6 GB". Tabular digits over a hidden
                        // widest-case line keep the bubble's width still while bytes count up.
                        val live = state.progressOrNull?.takeIf { state.isActive && it.isDeterminate }
                        // ONE style for every state of this line (an explicit line height too):
                        // the transfer text and the size text used to differ in height.
                        val lineStyle = androidx.compose.ui.text.TextStyle(
                            color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum",
                        )
                        // The widest the line will ever be is reserved in EVERY state, so the
                        // bubble has its final width from the first frame of the send.
                        val totalBytes = message.mediaSizeBytes ?: live?.total ?: 0L
                        Box {
                            if (totalBytes > 0L) Text("888.8 MB / " + humanSize(totalBytes), style = lineStyle, maxLines = 1,
                                modifier = Modifier.alpha(0f))
                            if (live != null) Text("${humanSize(live.bytes)} / ${humanSize(live.total)}", style = lineStyle, maxLines = 1)
                            else Text(text = docLine, style = lineStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                }
              }
              // For a thumbnail file the time/status sits at the BOTTOM of the
              // text column (beside the flush thumbnail), so the thumbnail can
              // hug the whole bubble height.
              if (BubbleFlags.TIMESTAMP && hasThumb && !footerBelow) {
                  Row(
                      modifier = Modifier.align(Alignment.End),
                      verticalAlignment = Alignment.CenterVertically,
                  ) {
                      if (message.isEdited) {
                          com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.65f))
                          Spacer(Modifier.width(4.dp))
                      }
                      com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
                      Text(compactTime(message.timestamp), color = textColor.copy(alpha = 0.7f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
                      if (message.isOutgoing) {
                          Spacer(Modifier.width(4.dp))
                          DeliveryTicks(message.status, textColor)
                      }
                  }
              }
            }
        }
        message.mediaCaption?.let { cap ->
            com.alphamessenger.ui.chat.text.BubbleCaption(onAccent = message.isOutgoing,
                text = cap,
                selectionMode = textSelectActive,
                color = textColor, fontSize = 15.sp, lineHeight = 18.sp,
                onTap = onMediaActions, onLongPress = onLongPress,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
            )
        }
        if (footerBelow) BubbleReactionsRow(message.isOutgoing, colors, Modifier.padding(top = 4.dp, start = 4.dp))
        // The time row goes BELOW the card unless a bare thumbnail card holds it.
        if (BubbleFlags.TIMESTAMP && footerBelow) {
            Row(
                modifier = Modifier.align(Alignment.End).padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.isEdited) {
                    com.alphamessenger.ui.chat.bubbles.EditedMark(textColor.copy(alpha = 0.65f))
                    Spacer(Modifier.width(4.dp))
                }
                com.alphamessenger.ui.channel.PostViews(textColor.copy(alpha = 0.7f))
                Text(compactTime(message.timestamp), color = textColor.copy(alpha = 0.7f), fontSize = com.alphamessenger.ui.chat.bubbles.BubbleTokens.footerTimeSp.sp)
                if (message.isOutgoing) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryTicks(message.status, textColor)
                }
            }
        }
    }
    } else {
        Box(Modifier.width(240.dp).height(if (hasThumb) 84.dp else 60.dp).clip(bubbleShape).background(bubbleColor))
    }
}

// --- File-domain helpers (only the file bubble uses these) ---

private val IMAGE_FILE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_FILE_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts")

/** Small file-type glyph for the document bubble's bottom line. */
private fun fileTypeIcon(ext: String?): androidx.compose.ui.graphics.vector.ImageVector = when (ext?.lowercase()) {
    "pdf" -> AlphaIcons.Outlined.PictureAsPdf
    "zip", "rar", "7z", "tar", "gz" -> AlphaIcons.Outlined.FolderZip
    "apk" -> AlphaIcons.Outlined.Android
    "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx" -> AlphaIcons.Outlined.Description
    "jpg", "jpeg", "png", "gif", "webp", "bmp" -> AlphaIcons.Outlined.Image
    "mp4", "mkv", "avi", "mov", "webm" -> AlphaIcons.Outlined.Movie
    else -> AlphaIcons.Outlined.InsertDriveFile
}

