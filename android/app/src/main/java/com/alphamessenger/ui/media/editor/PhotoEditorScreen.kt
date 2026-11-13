package com.alphamessenger.ui.media.editor

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.SystemBars

/** Editor background. Also feeds both system bars — one canvas. */
private val EditorBackground = Color(0xFF0D0E11)

/**
 * Photo editor: drawing, stickers, rotate and flip here; cropping on the
 * cropper's own screen, whose result becomes the photo the rest applies to.
 *
 * Transactional: edits live in the working copy [PhotoEditState] (and a cropped
 * source in [source]) and reach the draft EXACTLY ONCE — on "Done". "Back" and
 * "Cancel" leave no trace: a cropped file not taken is only a cache file.
 */
@Composable
fun PhotoEditorScreen(
    item: MediaDraftItem,
    colors: AlphaColorScheme,
    onCancel: () -> Unit,
    /** The edits and the photo they apply to (the draft's own, or a cropped copy). */
    onApply: (PhotoEditState, MediaDraftItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var source by remember(item.id) { mutableStateOf(item) }
    var state by remember(item.id) { mutableStateOf(PhotoEditState.of(item)) }
    var drawing by remember(item.id) { mutableStateOf(false) }
    // The brush and the sticker in hand.
    var brush by remember { mutableStateOf(BRUSH_COLORS[3]) }
    var brushWidth by remember { mutableStateOf(BRUSH_WIDTHS[1]) }
    var picked by remember(item.id) { mutableStateOf<Int?>(null) }
    var pickSticker by remember { mutableStateOf(false) }

    // The cropper bakes the frame, turn and flip into a new file: from then on
    // that file is the photo, and those edits start over from it.
    // The drawing goes into the picture handed to the cropper (so it is seen
    // there and cut with it), and leaves the editable layer once cropped.
    var bakedDrawing by remember(item.id) { mutableStateOf(false) }
    val cropper = rememberLauncherForActivityResult(com.canhub.cropper.CropImageContract()) { result ->
        val cropped = result.uriContent
        if (result.isSuccessful && cropped != null) {
            source = source.croppedTo(cropped)
            state = state.copy(
                crop = CropRect.FULL, rotationDegrees = 0, flipHorizontal = false, flipVertical = false, ratio = CropRatio.Free,
                markup = if (bakedDrawing) PhotoMarkup() else state.markup,
            )
        }
        bakedDrawing = false
    }
    val openCropper: () -> Unit = {
        if (state.markup.isEmpty) {
            cropper.launch(cropOptions(context, source.uri, state))
        } else scope.launch {
            val baked = withContext(Dispatchers.IO) { renderForCrop(context, state.applyTo(source)) }
            if (baked != null) {
                bakedDrawing = true
                // Turn and flip are already in the picture.
                cropper.launch(cropOptions(context, baked, PhotoEditState()))
            }
        }
    }

    // Bars are painted by the window's single owner. Restored when the screen closes.
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

    BackHandler {
        if (drawing) drawing = false else onCancel()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorBackground)
            // The whole window belongs to the editor: the screen beneath
            // gets no touches. Events are NOT consumed — otherwise its own
            // panels and scrolling would break; the layer only pins hits
            // to itself.
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                title = if (drawing) stringResource(R.string.editor_draw_title) else null,
                accent = colors.accent,
                onBack = { if (drawing) drawing = false else onCancel() },
                onDone = { if (drawing) drawing = false else onApply(state, source) },
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                var frame by remember { mutableStateOf(FittedFrame(0f, 0f, 0f, 0f)) }

                EditorImage(
                    uri = source.uri,
                    transform = state.transformFor(source),
                    // NO padding: the drawing layer is placed in THIS Box's coordinates.
                    modifier = Modifier.fillMaxSize(),
                    onFrame = { frame = it },
                    markup = state.markup,
                )

                if (frame.width > 0f) {
                    MarkupLayer(
                        markup = state.markup,
                        frame = frame,
                        drawing = drawing,
                        brush = brush,
                        brushWidth = brushWidth,
                        selected = picked,
                        onSelect = { picked = it },
                        onMarkup = { state = state.withMarkup(it) },
                    )
                }
            }

            if (drawing) {
                DrawBar(
                    color = brush,
                    width = brushWidth,
                    canUndo = state.markup.strokes.isNotEmpty(),
                    onColor = { brush = it },
                    onWidth = { brushWidth = it },
                    onUndo = { state = state.withMarkup(state.markup.copy(strokes = state.markup.strokes.dropLast(1))) },
                    modifier = Modifier.navigationBarsPadding(),
                )
            } else {
                EditorActionBar(
                    actions = listOf(
                        EditorAction("draw", EditorIcons.Draw, stringResource(R.string.editor_action_draw)) { picked = null; drawing = true },
                        EditorAction("sticker", EditorIcons.Sticker, stringResource(R.string.editor_action_sticker)) { pickSticker = true },
                        EditorAction("crop", EditorIcons.Crop, stringResource(R.string.editor_action_crop)) {
                            picked = null
                            openCropper()
                        },
                        EditorAction("rotate", EditorIcons.Rotate, stringResource(R.string.editor_action_rotate)) { state = state.rotatedRight() },
                        EditorAction("flipH", EditorIcons.FlipHorizontal, stringResource(R.string.editor_action_flip_h)) {
                            state = state.flippedHorizontally()
                        },
                        EditorAction("flipV", EditorIcons.FlipVertical, stringResource(R.string.editor_action_flip_v)) {
                            state = state.flippedVertically()
                        },
                        EditorAction("reset", EditorIcons.Reset, stringResource(R.string.editor_action_reset)) {
                            source = item
                            state = state.reset()
                        },
                    ),
                    colors = colors,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }

    if (pickSticker) {
        // A new one lands in the middle, a little turned, and is the one in hand.
        val add = { k: PhotoMarkup.Sticker ->
            state = state.withMarkup(state.markup.copy(stickers = state.markup.stickers + k))
            picked = state.markup.stickers.lastIndex
            pickSticker = false
        }
        StickerPickSheet(
            colors = colors,
            onEmoji = { e -> add(PhotoMarkup.Sticker(emoji = e, size = 0.28f, rotation = -6f)) },
            onSticker = { f -> add(PhotoMarkup.Sticker(path = f, size = 0.34f, rotation = -6f)) },
            onDismiss = { pickSticker = false },
        )
    }
}

/**
 * The cropper opens on the photo as it is shown here: the user's turn and
 * flip are handed over, so the frame is chosen on what the person sees.
 */
private fun cropOptions(context: android.content.Context, uri: android.net.Uri, state: PhotoEditState) =
    com.canhub.cropper.CropImageContractOptions(
        uri = uri,
        cropImageOptions = com.canhub.cropper.CropImageOptions(
            imageSourceIncludeCamera = false,
            imageSourceIncludeGallery = false,
            initialRotation = state.rotationDegrees,
            flipHorizontally = state.flipHorizontal,
            flipVertically = state.flipVertical,
            outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG,
            outputCompressQuality = 95,
            autoZoomEnabled = true,
            showProgressBar = true,
            activityTitle = context.getString(R.string.editor_crop_title),
            toolbarColor = android.graphics.Color.BLACK,
            toolbarBackButtonColor = android.graphics.Color.WHITE,
            toolbarTintColor = android.graphics.Color.WHITE,
            activityMenuIconColor = android.graphics.Color.WHITE,
            activityMenuTextColor = android.graphics.Color.WHITE,
            activityBackgroundColor = android.graphics.Color.BLACK,
            cropMenuCropButtonTitle = context.getString(R.string.common_done),
        ),
    )

/** The photo with its drawing, upright, as a cache file for the cropper. */
private fun renderForCrop(context: android.content.Context, draft: MediaDraftItem): android.net.Uri? {
    val bitmap = com.alphamessenger.ui.media.send.MediaOutputFactory.renderImage(
        context, draft, com.alphamessenger.ui.media.send.MediaOutputFactory.MAX_DECODE_EDGE,
    ) ?: return null
    val file = java.io.File(java.io.File(context.cacheDir, "media").apply { mkdirs() }, "crop_${System.nanoTime()}.jpg")
    val ok = runCatching { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } }.isSuccess
    bitmap.recycle()
    return if (ok) android.net.Uri.fromFile(file) else null
}

/** The draft's photo replaced by a cropped copy (JPEG, upright, ours to delete). */
private fun MediaDraftItem.croppedTo(cropped: android.net.Uri): MediaDraftItem = copy(
    uri = cropped,
    originalUri = originalUri ?: uri,
    mimeType = "image/jpeg",
    sizeBytes = MediaDraftItem.SIZE_UNKNOWN,
    exifRotationDegrees = 0,
    exifFlip = MediaDraftItem.FLIP_NONE,
    deleteAfterUse = true,
)

@Composable
private fun EditorTopBar(title: String?, accent: Color, onBack: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(10.dp),
        ) {
            Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = Color.White)
        }
        Text(
            title.orEmpty(),
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.common_done),
            color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onDone)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
