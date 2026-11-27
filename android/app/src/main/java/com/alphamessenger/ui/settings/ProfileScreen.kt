package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.alphamessenger.R
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.chat.avatarGradientFor
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.launch

/**
 * ProfileScreen — own profile editor.
 *
 * Design rules following the user's feedback:
 *  - **No auto-save** while typing. The user explicitly hits "Save"
 *    after picking photo / typing name. Avoids the confused «I typed but
 *    nothing changed» perception.
 *  - **Remove photo** clears the avatar back to gradient+initial.
 *  - npub loaded directly via FFI (with `getIdentityInfo` fallback) so
 *    "Loading…" disappears as soon as Identity is initialised.
 *  - Avatar reliably persisted: save fails loudly via snackbar if
 *    `openInputStream` returned null instead of silently storing a path
 *    to a non-existent file.
 */
/**
 * Secret nsec key block: hidden by default, reveal toggle + copy, with a hard
 * warning. nsec is the account login key — anyone with it can impersonate you,
 * so it stays masked until explicitly revealed.
 */
@Composable
internal fun SecretKeyBlock(
    revealed: Boolean,
    value: String,
    colors: AlphaColorScheme,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(colors.bgTertiary)
            .border(1.dp, Color(0x55FF5A5A), shape)
            .padding(14.dp),
    ) {
        Column {
            Text(
                stringResource(R.string.secret_key_warning),
                color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (revealed) (value.ifBlank { stringResource(R.string.profile_npub_loading) })
                    else "•••••••••••••••••••••••••••••••••",
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBtn(
                    if (revealed) AlphaIcons.Outlined.VisibilityOff else AlphaIcons.Outlined.Visibility,
                    if (revealed) stringResource(R.string.common_hide) else stringResource(R.string.common_show),
                    onToggle, colors, false, Modifier.weight(1f),
                )
                ActionBtn(
                    AlphaIcons.Outlined.ContentCopy, stringResource(R.string.common_copy),
                    onCopy, colors, true, Modifier.weight(1f),
                )
            }
        }
    }
}

// --- Top bar with explicit Save button ---

@Composable
internal fun textFieldColors(colors: AlphaColorScheme) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.borderLight,
        cursorColor = colors.accent,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
    )

@Composable
internal fun ActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    colors: AlphaColorScheme,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (primary) colors.accent else colors.bgTertiary
    val fg = if (primary) colors.bgPrimary else colors.textPrimary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Run [block] with a timeout. Returns null if it didn't complete in time. */
private suspend fun <T> withTimeoutSafe(timeoutMs: Long, block: suspend () -> T): T? =
    try {
        kotlinx.coroutines.withTimeout(timeoutMs) { block() }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        null
    }

/** Our own photo, stored under its content name (see [com.alphamessenger.data.OwnPictureFiles]). */
internal fun saveOwnPhoto(ctx: Context, uri: Uri): String? {
    val files = com.alphamessenger.data.OwnPictureFiles
    val scratch = files.scratch(ctx, files.PHOTO)
    val written = saveAvatarToInternal(ctx, uri, "${scratch.parentFile!!.name}/${scratch.name}") ?: return null
    return files.adopt(ctx, java.io.File(written), files.PHOTO)
}

/**
 * Copy the user-picked photo to internal storage at a stable path AND
 * downscale + recompress it so the broadcast payload fits the 200 KB cap
 * enforced by `broadcast_my_profile` on the Rust side.
 *
 * Target: 256×256 center-crop JPEG q=85 (typically lands around 15-30 KB).
 *
 * **Implementation details**:
 *  - PhotoPicker URIs are often single-read streams (decoding bounds and
 *    then the pixels requires opening twice → second open fails). So we
 *    cache the bytes to a temp file first, then decode from disk.
 *  - We catch `Throwable` (not just `Exception`) because Bitmap decoders
 *    can throw `OutOfMemoryError` on huge images.
 */
internal fun saveAvatarToInternal(
    ctx: Context,
    uri: Uri,
    /** Relative to filesDir; each use has its own, so a group photo never replaces ours. */
    fileName: String,
    /** Byte budget: a group/channel photo travels inside the group's metadata (≤ 200 KB). */
    maxBytes: Int = Int.MAX_VALUE,
): String? {
    val target = java.io.File(ctx.filesDir, fileName)
    val tmp = java.io.File(ctx.filesDir, "$fileName.tmp")
    return try {

        // --- Copy URI → temp file (allows multiple decode passes) ---
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(tmp).use { out -> input.copyTo(out) }
        } ?: run {
            return null
        }
        if (!tmp.exists() || tmp.length() <= 0L) {
            return null
        }

        // --- First pass: read bounds from disk ---
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        com.alphamessenger.data.MediaVault.decodeFile(tmp.absolutePath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) {
            return null
        }

        // --- inSampleSize so decoded bitmap is just above 256 px ---
        // 256×256 q=85 lands around ~20-30 KB; well under the 200 KB cap
        // and crisp at 280dp preview. HD avatar 1024² q90 — images travel
        // via the blob/chunk pipeline (only a tiny MediaRef rides in
        // ProfileUpdate), so size is unconstrained.
        val targetEdge = 1024
        var sample = 1
        while ((srcW / (sample * 2)) >= targetEdge && (srcH / (sample * 2)) >= targetEdge) {
            sample *= 2
        }

        // --- Second pass: actual decode ---
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = com.alphamessenger.data.MediaVault.decodeFile(tmp.absolutePath, opts)
        if (raw == null) {
            return null
        }

        // --- Center-crop to square ---
        val side = minOf(raw.width, raw.height)
        val cropX = (raw.width - side) / 2
        val cropY = (raw.height - side) / 2
        val square = android.graphics.Bitmap.createBitmap(raw, cropX, cropY, side, side)
        if (square !== raw) raw.recycle()

        // --- Scale to 256×256 ---
        val scaled = android.graphics.Bitmap.createScaledBitmap(square, targetEdge, targetEdge, true)
        if (scaled !== square) square.recycle()

        // --- Compress JPEG → target file, within the byte budget ---
        var bmp = scaled
        var edge = targetEdge
        var quality = 90
        while (true) {
            val bytes = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bytes)
            if (bytes.size() <= maxBytes || edge <= 256) {
                java.io.FileOutputStream(target).use { bytes.writeTo(it) }
                break
            }
            if (quality > 70) quality -= 10
            else {
                edge = edge * 3 / 4
                val smaller = android.graphics.Bitmap.createScaledBitmap(bmp, edge, edge, true)
                if (smaller !== bmp) bmp.recycle()
                bmp = smaller
                quality = 85
            }
        }
        bmp.recycle()

        if (!target.exists() || target.length() <= 0L) {
            return null
        }
        android.util.Log.i(
            "ProfileScreen",
            "saveAvatar: OK (${target.length()} bytes, src=${srcW}x${srcH}, sample=$sample)"
        )
        target.absolutePath
    } catch (t: Throwable) {
        // Includes OutOfMemoryError, IOException, SecurityException, …
        null
    } finally {
        runCatching { if (tmp.exists()) tmp.delete() }
    }
}

internal fun copyText(ctx: Context, label: String, value: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

