package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alphamessenger.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlin.coroutines.suspendCoroutine

// --- Public model ---

/** A shot of the camera, or a picture picked from its gallery strip ([fromGallery]). */
data class StudioMedia(val uri: Uri, val isVideo: Boolean, val fromGallery: Boolean = false)

private val Green = Color(0xFF34C759)

// --- Camera studio — live preview → capture (photo/video) → review + compose. ---

/**
 * The camera is a capture SOURCE, not a second send screen.
 *
 * Captures go straight into the shared draft set ([onCaptured]) and are shown
 * in the single review view (caption, strip, delete and send all live there).
 * The camera deliberately has no review/send of its own — a second composer
 * would strand the gallery draft in the first one. Same approach as the media draft:
 * `CameraActivity` only returns a result; `SendMediaActivity` composes and sends.
 */
@Composable
fun CameraStudioScreen(
    onClose: () -> Unit,
    /** The pictures picked in the strip, then the shot. */
    onCaptured: (List<StudioMedia>) -> Unit,
    /** Pictures from the strip, without a shot. */
    onPickRecent: (List<StudioMedia>) -> Unit = {},
    /** The whole gallery (the attach panel). */
    onGallery: (() -> Unit)? = null,
    /** "Video message" mode: the round recorder takes over. */
    onVideoNote: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // SD / HD is one remembered choice, the same as in the review screen.
    val mediaQuality = com.alphamessenger.ui.media.export.MediaQuality
    LaunchedEffect(Unit) { mediaQuality.load(context) }
    // Strip pictures picked by a long press; they go together with the next shot.
    val picked = remember { androidx.compose.runtime.mutableStateListOf<GalleryItem>() }
    fun pickedMedia() = picked.map { StudioMedia(it.uri, it.isVideo, fromGallery = true) }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(StudioMode.PHOTO) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        VideoCapture.withOutput(recorder)
    }
    var recording by remember { mutableStateOf<Recording?>(null) }

    // Zoom: 0..1 across the lens' range. Pinch on the picture, or slide the
    // finger up / down while holding the shutter for video.
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var zoom by remember { mutableStateOf(0f) }
    var zoomRatio by remember { mutableStateOf(1f) }
    fun setZoom(z: Float) {
        zoom = z.coerceIn(0f, 1f)
        camera?.cameraControl?.setLinearZoom(zoom)
    }
    LaunchedEffect(camera) {
        val cam = camera ?: return@LaunchedEffect
        // The ratio the lens actually reached, for the "2.3×" label.
        kotlinx.coroutines.flow.callbackFlow {
            val obs = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { trySend(it.zoomRatio) }
            cam.cameraInfo.zoomState.observe(lifecycleOwner, obs)
            awaitClose { cam.cameraInfo.zoomState.removeObserver(obs) }
        }.collect { zoomRatio = it }
    }

    val hasAudio = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    // (Re)bind the camera whenever the lens flips. Flash mode is applied live.
    LaunchedEffect(lensFacing) {
        val provider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, videoCapture)
            zoom = 0f
        }
    }
    LaunchedEffect(flashOn) {
        imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }
    // Recording timer.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordSeconds = 0
            while (isRecording) { delay(1000); recordSeconds++ }
        }
    }

    // A shot is handed over exactly once: two quick shutter taps must not
    // deliver the same capture twice.
    var handedOver by remember { mutableStateOf(false) }
    fun handOver(media: StudioMedia) {
        if (handedOver) return
        handedOver = true
        onCaptured(pickedMedia() + media)
    }

    fun takePhoto() {
        val file = File(context.cacheDir, "studio_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            opts, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    handOver(StudioMedia(Uri.fromFile(file), false))
                }
                override fun onError(exc: ImageCaptureException) {}
            },
        )
    }

    fun startVideo() {
        val file = File(context.cacheDir, "studio_${System.currentTimeMillis()}.mp4")
        val opts = FileOutputOptions.Builder(file).build()
        var pending = videoCapture.output.prepareRecording(context, opts)
        if (hasAudio) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                isRecording = false
                if (!event.hasError()) handOver(StudioMedia(Uri.fromFile(file), true))
            }
        }
        isRecording = true
    }

    fun stopVideo() {
        recording?.stop()
        recording = null
    }

    val recent by androidx.compose.runtime.produceState(emptyList<GalleryItem>()) {
        if (com.alphamessenger.ui.chat.v2.hasGalleryPermission(context)) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.alphamessenger.ui.chat.v2.loadRecentMediaV2(context, limit = 40)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Consume all touches so they don't fall through to chat bubbles behind.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
    ) {
        androidx.compose.ui.viewinterop.AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Pinch to zoom over the whole picture.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) setZoom(zoom + (zoomChange - 1f) * 0.8f)
                }
            },
        )
        // A video message is a circle: show what will be in it.
        val noteMask by androidx.compose.animation.core.animateFloatAsState(
            if (mode == StudioMode.NOTE) 1f else 0f, label = "noteMask",
        )
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxSize().graphicsLayer { alpha = noteMask; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
        ) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            drawCircle(Color.Transparent, radius = size.minDimension * 0.42f, center = center.copy(y = size.height * 0.4f),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        }

        // Top bar: close · quality · flash
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIcon(AlphaIcons.Filled.Close, stringResource(R.string.common_close)) { onClose() }
            Row(
                Modifier.graphicsLayer { alpha = if (isRecording) 0f else 1f },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.background(Color(0x66000000), RoundedCornerShape(14.dp)).padding(3.dp)) {
                    listOf(false to "SD", true to "HD").forEach { (v, label) ->
                        val on = mediaQuality.hd == v
                        Text(
                            label, color = if (on) Color.Black else Color.White, fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(11.dp))
                                .background(if (on) Color.White else Color.Transparent)
                                .clickable(enabled = !isRecording) { mediaQuality.setHd(context, v) }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                        )
                    }
                }
                CircleIcon(if (flashOn) AlphaIcons.Filled.FlashOn else AlphaIcons.Filled.FlashOff, stringResource(R.string.camera_flash_desc)) {
                    flashOn = !flashOn
                }
            }
        }
        // Recording timer pill
        Box(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp)
                .graphicsLayer { alpha = if (isRecording) 1f else 0f }
                .background(Color(0xCCE53935), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text("● ${"%d:%02d".format(recordSeconds / 60, recordSeconds % 60)}", color = Color.White, fontSize = 14.sp)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (zoomRatio > 1.05f) {
                Box(
                    Modifier.padding(bottom = 10.dp).background(Color(0x99000000), RoundedCornerShape(16.dp))
                        .clickable { setZoom(0f) }.padding(horizontal = 12.dp, vertical = 5.dp),
                ) { Text("%.1f×".format(zoomRatio), color = Color.White, fontSize = 13.sp) }
            }
            // Picked pictures go on without a shot.
            Box(Modifier.fillMaxWidth().height(44.dp).padding(end = 12.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
                val count = picked.size
                Row(
                    Modifier.graphicsLayer { alpha = if (count > 0 && !isRecording) 1f else 0f }
                        .clip(RoundedCornerShape(19.dp)).background(Color(0xFF3B82F6))
                        .clickable(enabled = count > 0 && !isRecording) { onPickRecent(pickedMedia()) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.camera_picked_next, count), color = Color.White, fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
            // The latest pictures: a tap opens one, a long press picks several.
            RecentStrip(
                items = recent,
                picked = picked,
                visible = !isRecording && mode != StudioMode.NOTE,
                onOpen = { onPickRecent(listOf(StudioMedia(it.uri, it.isVideo, fromGallery = true))) },
                onToggle = { if (!picked.remove(it)) picked.add(it) },
            )
            Spacer(Modifier.size(14.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.graphicsLayer { alpha = if (isRecording || onGallery == null) 0f else 1f }) {
                    CircleIcon(AlphaIcons.Outlined.PhotoLibrary, stringResource(R.string.camera_gallery), big = true) {
                        if (!isRecording) onGallery?.invoke()
                    }
                }
                ShutterButton(
                    mode = mode,
                    isRecording = isRecording,
                    onTakePhoto = { takePhoto() },
                    onStartVideo = { startVideo() },
                    onStopVideo = { stopVideo() },
                    onVideoNote = { onVideoNote?.invoke() },
                    scope = scope,
                    zoomNow = { zoom },
                    onZoom = { setZoom(it) },
                )
                CircleIcon(AlphaIcons.Filled.Cameraswitch, stringResource(R.string.camera_switch_desc), big = true) {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                        CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                }
            }
            Spacer(Modifier.size(14.dp))
            ModeTabs(
                mode = mode,
                hasNote = onVideoNote != null,
                enabled = !isRecording,
                onMode = { m ->
                    mode = m
                    // A video message is filmed with the front camera, like the recorder in the chat.
                    if (m == StudioMode.NOTE) lensFacing = CameraSelector.LENS_FACING_FRONT
                },
            )
            Spacer(Modifier.size(10.dp))
        }
    }

    DisposableEffect(Unit) { onDispose { runCatching { recording?.stop() } } }
}

// --- Pieces ---

/** What the shutter does: a photo (hold = video), a video by taps, or a video message. */
enum class StudioMode(@StringRes val labelRes: Int) {
    VIDEO(R.string.camera_mode_video),
    PHOTO(R.string.camera_mode_photo),
    NOTE(R.string.camera_mode_note),
}

@Composable
private fun CircleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(if (big) 50.dp else 42.dp).background(Color(0x66000000), CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(if (big) 26.dp else 22.dp))
    }
}

/**
 * The latest pictures of the gallery in one row; hidden (not removed) while
 * recording. A tap opens one; a long press starts picking, and then taps pick too.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RecentStrip(
    items: List<GalleryItem>,
    picked: List<GalleryItem>,
    visible: Boolean,
    onOpen: (GalleryItem) -> Unit,
    onToggle: (GalleryItem) -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val ctx = LocalContext.current
    val shown by androidx.compose.animation.core.animateFloatAsState(if (visible && items.isNotEmpty()) 1f else 0f, label = "strip")
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().height(78.dp).graphicsLayer { alpha = shown },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        userScrollEnabled = visible,
    ) {
        items(items.size, key = { items[it].uri.toString() }) { n ->
            val item = items[n]
            val key = item.uri.toString()
            var thumb by remember(key) { mutableStateOf(com.alphamessenger.ui.chat.v2.galleryThumbCache.get(key)) }
            LaunchedEffect(key) {
                if (thumb == null) thumb = kotlinx.coroutines.withContext(com.alphamessenger.ui.chat.v2.thumbLoader) {
                    com.alphamessenger.ui.chat.v2.galleryThumb(ctx, item.uri, item.isVideo)
                }
            }
            val order = picked.indexOf(item) + 1
            Box(
                Modifier.size(78.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))
                    .then(if (order > 0) Modifier.border(2.5.dp, Color(0xFF3B82F6), RoundedCornerShape(8.dp)) else Modifier)
                    .combinedClickable(
                        enabled = visible,
                        onClick = { if (picked.isEmpty()) onOpen(item) else onToggle(item) },
                        onLongClick = {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onToggle(item)
                        },
                    ),
            ) {
                thumb?.let {
                    androidx.compose.foundation.Image(
                        it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                        .graphicsLayer { alpha = if (picked.isNotEmpty()) 1f else 0f }
                        .clip(CircleShape).background(if (order > 0) Color(0xFF3B82F6) else Color.Black.copy(alpha = 0.35f))
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(if (order > 0) "$order" else "", color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                if (item.isVideo) {
                    Text(
                        com.alphamessenger.ui.chat.v2.fmtDurationV2(item.durationMs), color = Color.White, fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** Video · Photo · Video message, the chosen one on a soft pill. */
@Composable
private fun ModeTabs(mode: StudioMode, hasNote: Boolean, enabled: Boolean, onMode: (StudioMode) -> Unit) {
    Row(
        Modifier.graphicsLayer { alpha = if (enabled) 1f else 0f },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudioMode.entries.filter { it != StudioMode.NOTE || hasNote }.forEach { m ->
            val on = m == mode
            Text(
                stringResource(m.labelRes),
                color = if (on) Color.White else Color.White.copy(alpha = 0.75f),
                fontSize = 15.sp,
                fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(enabled = enabled) { onMode(m) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The big round shutter. Photo: tap → photo, hold (>300 ms) → video until
 * release. Video: tap starts, tap stops. Video message: tap hands over to the
 * round recorder.
 */
@Composable
private fun ShutterButton(
    mode: StudioMode,
    isRecording: Boolean,
    onTakePhoto: () -> Unit,
    onStartVideo: () -> Unit,
    onStopVideo: () -> Unit,
    onVideoNote: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    zoomNow: () -> Float = { 0f },
    onZoom: (Float) -> Unit = {},
) {
    val slideRange = with(androidx.compose.ui.platform.LocalDensity.current) { 280.dp.toPx() }
    val modeNow by androidx.compose.runtime.rememberUpdatedState(mode)
    val recordingNow by androidx.compose.runtime.rememberUpdatedState(isRecording)
    Box(
        Modifier.size(80.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    when (modeNow) {
                        StudioMode.NOTE -> {
                            waitForUpOrCancellation()
                            onVideoNote()
                        }
                        StudioMode.VIDEO -> {
                            waitForUpOrCancellation()
                            if (recordingNow) onStopVideo() else onStartVideo()
                        }
                        StudioMode.PHOTO -> {
                            val startY = down.position.y
                            val startZoom = zoomNow()
                            var started = false
                            val job = scope.launch {
                                delay(300)
                                started = true
                                onStartVideo()
                            }
                            do {
                                val event = awaitPointerEvent()
                                // Recording: finger up zooms in, down zooms out — without letting go.
                                if (started) {
                                    event.changes.firstOrNull { it.id == down.id }?.let { c ->
                                        onZoom(startZoom + (startY - c.position.y) / slideRange)
                                        c.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            job.cancel()
                            if (started) onStopVideo() else onTakePhoto()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(72.dp).border(4.dp, Color.White, CircleShape))
        val red = isRecording || mode == StudioMode.VIDEO
        Box(
            Modifier.size(if (isRecording) 32.dp else 60.dp)
                .background(if (red) Color(0xFFE53935) else Color.White,
                    if (isRecording) RoundedCornerShape(8.dp) else CircleShape),
        )
        if (mode == StudioMode.NOTE) {
            Box(Modifier.size(30.dp).border(2.5.dp, Color.Black.copy(alpha = 0.75f), CircleShape))
        }
    }
}

// --- Helpers ---

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
    }
