package com.alphamessenger.ui.media.editor

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.alphamessenger.ui.media.draft.MediaDraftItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Where exactly the frame landed inside the given area — in pixels. */
data class FittedFrame(val originX: Float, val originY: Float, val width: Float, val height: Float)

/**
 * The single rendering of the edited frame.
 *
 * Draws a SOURCE REGION straight onto the canvas: the crop rectangle in frame
 * pixels maps to the window rectangle. No fitting, no intermediate boxes, no
 * guessing how the system placed the image inside its container — a box-based
 * approach diverged by a few percent and everything measured from the image
 * top drifted down, magnified by zoom on wide crops.
 *
 * The frame is read the SAME way the send path reads it
 * (`MediaOutputFactory`): own decoder, file orientation and user edits in
 * one matrix, same order. So "display = file" here is not a convention but
 * the construction.
 */
@Composable
fun EditorImage(
    uri: Uri,
    transform: EditorTransform,
    modifier: Modifier = Modifier,
    /** Show it cropped. False — the full frame is visible (crop mode). */
    applyCrop: Boolean = true,
    /** Edge cap for reading: a thumbnail need not hold the full frame. */
    maxEdge: Int = 2048,
    contentDescription: String? = null,
    /** Where the frame landed: needed by whoever draws on top (the crop frame). */
    onFrame: (FittedFrame) -> Unit = {},
    /** Strokes and stickers over the frame (not shown while the whole frame is in view for crop). */
    markup: PhotoMarkup? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val oriented by produceState<ImageBitmap?>(
        initialValue = null,
        uri,
        maxEdge,
        transform.exifRotationDegrees,
        transform.exifFlip,
        transform.rotationDegrees,
        transform.flipHorizontal,
        transform.flipVertical,
    ) {
        value = withContext(Dispatchers.IO) { loadOriented(context, uri, transform, maxEdge) }
    }

    // Sticker pictures of the drawing, read off the main thread.
    val markupPictures = remember { androidx.compose.runtime.mutableStateMapOf<String, android.graphics.Bitmap>() }
    val wanted = markup?.stickers?.mapNotNull { it.path }?.distinct().orEmpty()
    androidx.compose.runtime.LaunchedEffect(wanted) {
        for (p in wanted) if (markupPictures[p] == null) MarkupRenderer.picture(p, 512)?.let { markupPictures[p] = it }
    }

    // Measured, not BoxWithConstraints: the editor swaps its layers in one
    // frame, and a subcomposition taken down with them fails to remove.
    var area by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    Box(modifier = modifier.onSizeChanged { area = it }, contentAlignment = Alignment.Center) {
        val image = oriented
        if (image != null && area.width > 0 && area.height > 0) {
        val crop = if (applyCrop) transform.crop else CropRect.FULL

        // Source region in pixels of the already-oriented frame.
        val srcX = (crop.left * image.width).roundToInt().coerceIn(0, image.width - 1)
        val srcY = (crop.top * image.height).roundToInt().coerceIn(0, image.height - 1)
        val srcW = (crop.width * image.width).roundToInt().coerceIn(1, image.width - srcX)
        val srcH = (crop.height * image.height).roundToInt().coerceIn(1, image.height - srcY)

        val areaW = area.width.toFloat()
        val areaH = area.height.toFloat()

        // The window — that region fitted wholly into the given area.
        val aspect = srcW.toFloat() / srcH.toFloat()
        val windowW = min(areaW, areaH * aspect)
        val windowH = windowW / aspect
        val fitted = FittedFrame((areaW - windowW) / 2f, (areaH - windowH) / 2f, windowW, windowH)
        SideEffect { onFrame(fitted) }

        Canvas(
            modifier = Modifier
                .size(with(density) { windowW.toDp() }, with(density) { windowH.toDp() })
                .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        ) {
            drawImage(
                image = image,
                srcOffset = IntOffset(srcX, srcY),
                srcSize = IntSize(srcW, srcH),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            if (markup != null && applyCrop && !markup.isEmpty) drawIntoCanvas { c ->
                MarkupRenderer.draw(c.nativeCanvas, size.width, size.height, markup) { p -> markupPictures[p] }
            }
        }
        }
    }
}

/**
 * Reads the frame with orientation applied — same order as
 * `MediaOutputFactory`: file orientation, then the user's rotation and flip.
 * Crop is NOT applied here: rendering does it, otherwise the frame would be
 * re-read on every crop-frame move.
 */
private fun loadOriented(
    context: Context,
    uri: Uri,
    transform: EditorTransform,
    maxEdge: Int,
): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxEdge || bounds.outHeight / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null

    val matrix = Matrix().apply {
        if (transform.exifRotationDegrees % 360 != 0) postRotate(transform.exifRotationDegrees.toFloat())
        if (transform.exifFlip and MediaDraftItem.FLIP_HORIZONTAL != 0) postScale(-1f, 1f)
        if (transform.exifFlip and MediaDraftItem.FLIP_VERTICAL != 0) postScale(1f, -1f)
        if (transform.rotationDegrees % 360 != 0) postRotate(transform.rotationDegrees.toFloat())
        if (transform.flipHorizontal) postScale(-1f, 1f)
        if (transform.flipVertical) postScale(1f, -1f)
    }
    val oriented = if (matrix.isIdentity) decoded else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it != decoded) decoded.recycle() }
    }
    oriented.asImageBitmap()
}.getOrNull()
