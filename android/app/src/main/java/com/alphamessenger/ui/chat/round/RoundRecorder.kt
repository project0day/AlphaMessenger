package com.alphamessenger.ui.chat.round

import com.alphamessenger.ui.icons.AlphaIcons
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import java.io.File

/** Longest video message. */
const val ROUND_MAX_SECONDS = 60

/**
 * Records a video message: front camera by default, a small 480p file (the
 * circle crops it on screen — nothing is re-encoded on the phone), sound when
 * the microphone is allowed, at most [ROUND_MAX_SECONDS].
 *
 * One instance per chat screen. [start] binds the camera to [owner]; [finish]
 * hands the file over (or drops it); [release] lets the camera go.
 */
class RoundRecorder(private val context: Context, private val owner: LifecycleOwner) {
    var recording by mutableStateOf(false)
        private set
    var seconds by mutableIntStateOf(0)
        private set
    var locked by mutableStateOf(false)
    var front by mutableStateOf(true)
        private set

    val preview: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // A TextureView: only it can be clipped to the circle.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val capture: VideoCapture<Recorder> = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build(),
    )
    private var provider: ProcessCameraProvider? = null
    private var active: Recording? = null
    private var file: File? = null
    private var keep = false
    private var onDone: ((File?) -> Unit)? = null
    private var startedAt = 0L

    private fun bind() {
        val p = provider ?: return
        val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val lens = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        runCatching {
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.Builder().requireLensFacing(lens).build(), pv, capture)
        }
    }

    /** Start recording; [done] gets the file to send, or null (cancelled / too short / failed). */
    @SuppressLint("MissingPermission")
    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    fun start(done: (File?) -> Unit) {
        if (recording) return
        onDone = done
        keep = false
        locked = false
        seconds = 0
        recording = true
        val f = File(context.cacheDir, "round_${System.currentTimeMillis()}.mp4")
        file = f
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!recording) return@addListener
            provider = runCatching { future.get() }.getOrNull()
            bind()
            val opts = FileOutputOptions.Builder(f).setDurationLimitMillis(ROUND_MAX_SECONDS * 1000L).build()
            // Persistent: the recording lives on when the camera is flipped
            // (re-binding would otherwise end it).
            var pending = capture.output.prepareRecording(context, opts).asPersistentRecording()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            }
            startedAt = System.currentTimeMillis()
            active = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> seconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                    is VideoRecordEvent.Finalize -> {
                        // The time limit ends a recording the normal way: it is sent.
                        val limit = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                        val ok = (!event.hasError() || limit) && f.length() > 0
                        val longEnough = System.currentTimeMillis() - startedAt >= 1000
                        val send = ok && longEnough && (keep || limit)
                        if (!send) f.delete()
                        finished(if (send) f else null)
                    }
                    else -> {}
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun finished(result: File?) {
        recording = false
        locked = false
        active = null
        provider?.unbindAll()
        val cb = onDone
        onDone = null
        cb?.invoke(result)
    }

    /** Stop: [send] = hand the file over, else drop it. */
    fun finish(send: Boolean) {
        if (!recording) return
        keep = send
        val r = active
        if (r != null) r.stop() else finished(null)
    }

    /** Front ↔ back, mid-recording too. */
    fun flip() {
        front = !front
        bind()
    }

    fun release() {
        runCatching { active?.close() }
        active = null
        provider?.unbindAll()
    }
}

/**
 * The recording screen: the chat dims, the camera shows in a big circle with a
 * ring that fills towards the time limit. Double tap flips the camera. When the
 * recording is locked (no finger needed) it carries its own send and cancel.
 */
@Composable
fun RoundRecordOverlay(rec: RoundRecorder, colors: AlphaColorScheme) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(292.dp), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { rec.preview },
                    modifier = Modifier.size(280.dp).clip(CircleShape)
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { rec.flip() }) },
                )
                val progress = (rec.seconds / ROUND_MAX_SECONDS.toFloat()).coerceIn(0f, 1f)
                Canvas(Modifier.size(292.dp)) {
                    val w = 4.dp.toPx()
                    drawArc(
                        color = Color(0xFFE86F6F), startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                        topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                        style = Stroke(width = w, cap = StrokeCap.Round),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFE86F6F)))
                Text("%d:%02d".format(rec.seconds / 60, rec.seconds % 60), color = Color.White, fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.round_hint, ROUND_MAX_SECONDS),
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            )
            if (!rec.locked) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.round_gestures), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
            if (rec.locked) {
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                            .clickable { rec.finish(send = false) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = Color.White) }
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(colors.accent)
                            .clickable { rec.finish(send = true) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.common_send), tint = Color.White) }
                }
            }
        }
    }
}
