package com.alphamessenger.ui.chatlist

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

// ──────────────────────────────────────────────────────────────────────
// ShareNpubSheet — full QR + selectable npub + Copy + Share buttons
// ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareNpubSheet(
    npub: String,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    /** Our own code: the npub plus where we listen, so the first message finds us. */
    loadCode: (suspend () -> String)? = null,
) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var copyToast by remember { mutableStateOf(false) }
    LaunchedEffect(copyToast) {
        if (copyToast) {
            kotlinx.coroutines.delay(1500)
            copyToast = false
        }
    }

    val code by androidx.compose.runtime.produceState(npub, npub) { loadCode?.let { value = runCatching { it() }.getOrDefault(npub) } }
    val qrBitmap = remember(code) { generateQrBitmap(code, 720) }

    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AlphaIcons.Outlined.QrCode, null, tint = colors.accent)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.qr_my_code), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(AlphaIcons.Outlined.Close, null, tint = colors.textMuted)
                }
            }
            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(R.string.qr_share_hint),
                color = colors.textMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.size(20.dp))
            // QR
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qrBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_with_npub_desc),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(stringResource(R.string.qr_build_failed), color = Color.Red, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.size(16.dp))
            // Selectable, monospaced npub
            SelectionContainer {
                Text(
                    text = npub,
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.bgTertiary)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.size(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        copyNpubToClipboard(ctx, code)
                        copyToast = true
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderLight),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(AlphaIcons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (copyToast) stringResource(R.string.common_copied) else stringResource(R.string.common_copy))
                }
                Button(
                    onClick = { shareNpub(ctx, code) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.bgPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(AlphaIcons.Outlined.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.common_share))
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// QrScannerSheet — CameraX preview + MLKit barcode analyzer
// ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrScannerSheet(
    colors: AlphaColorScheme,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Which codes this scan is for (a contact by default); any other is read past. */
    accept: (String) -> Boolean = { isContactKey(it) },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ctx = LocalContext.current

    // Camera permission. We request at runtime if needed.
    var hasPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.CAMERA,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AlphaIcons.Outlined.QrCodeScanner, null, tint = colors.accent)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.fab_scan_qr), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(AlphaIcons.Outlined.Close, null, tint = colors.textMuted)
                }
            }
            Spacer(Modifier.size(12.dp))

            if (!hasPermission) {
                Text(
                    stringResource(R.string.qr_camera_permission_hint),
                    color = colors.textMuted, fontSize = 13.sp,
                )
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = { permissionLauncher.launch(android.Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent, contentColor = colors.bgPrimary,
                    ),
                ) { Text(stringResource(R.string.qr_grant_camera)) }
            } else {
                Text(
                    stringResource(R.string.qr_scan_hint),
                    color = colors.textMuted, fontSize = 13.sp,
                )
                Spacer(Modifier.size(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .border(2.dp, colors.accent, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    CameraPreviewWithScan(accept = { accept(it.trim()) }, onCode = { code -> onResult(code.trim()) })
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewWithScan(accept: (String) -> Boolean, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val seen = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // Hold the actual CameraProvider so we can explicitly release the
    // camera when the sheet closes. Without this, `provider.unbindAll()`
    // never runs, the camera stays bound to the activity lifecycle, and
    // the user sees the camera-in-use indicator persist after dismiss.
    val providerHolder = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val view = PreviewView(c).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(c)
            providerFuture.addListener({
                val provider = providerFuture.get()
                providerHolder.set(provider)
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, ZxingQrAnalyzer { text ->
                    // A code of another kind is read past: the camera keeps looking for this one.
                    if (accept(text) && seen.compareAndSet(false, true)) {
                        androidx.core.content.ContextCompat.getMainExecutor(c).execute { onCode(text) }
                    }
                })
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(c))
            view
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            // Order matters: unbind camera FIRST so the sensor releases,
            // then tear down our async helpers.
            runCatching { providerHolder.get()?.unbindAll() }
            runCatching { executor.shutdown() }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────

/** QR decoder on the camera Y plane using zxing (no Google services). */
private class ZxingQrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = com.google.zxing.MultiFormatReader().apply {
        setHints(
            mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                com.google.zxing.DecodeHintType.TRY_HARDER to true,
            )
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = com.google.zxing.PlanarYUVLuminanceSource(
                data, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
            )
            val result = reader.decodeWithState(
                com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
            )
            result.text?.takeIf { it.isNotBlank() }?.let(onCode)
        } catch (_: com.google.zxing.NotFoundException) {
        } catch (_: Exception) {
        } finally {
            reader.reset()
            image.close()
        }
    }
}

/** An npub, a contact code (nprofile, with our relays) or a 64-hex key. */
internal fun isContactKey(text: String): Boolean {
    val t = text.trim().removePrefix("nostr:")
    return t.startsWith("npub1") || t.startsWith("nprofile1") || (t.length == 64 && t.all { it.isLetterOrDigit() })
}

internal fun copyNpubToClipboard(ctx: Context, npub: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("npub", npub))
}

internal fun shareNpub(ctx: Context, npub: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, npub)
        putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.qr_share_npub_subject))
    }
    ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.qr_share_npub_chooser)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

internal fun generateQrBitmap(text: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width; val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    } catch (e: Exception) {
        null
    }
}
