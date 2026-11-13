package com.alphamessenger.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Alpha Glass: a picture behind a screen and panels of frosted glass over it.
 *
 * The frost is made once: a small, softened copy of the picture. Each panel
 * draws its own slice of that copy (where it sits over the picture), tinted,
 * with a fine light rim. Nothing is blurred while scrolling — a panel that
 * moves only shows another slice — so it costs a bitmap draw per panel.
 */
class GlassSky internal constructor(
    val frost: ImageBitmap,
    /** Where the backdrop sits on screen and its size, and how the picture fills it. */
    val origin: Offset,
    val size: Size,
)

val LocalGlassSky = staticCompositionLocalOf<androidx.compose.runtime.MutableState<GlassSky?>?> { null }

private fun loadSampled(path: String, maxSide: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    com.alphamessenger.data.MediaVault.decodeFile(path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    com.alphamessenger.data.MediaVault.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

/** A soft copy: shrunk in steps (each a bilinear average) — drawn large, it reads as a blur. */
private fun frostOf(src: Bitmap): Bitmap {
    var b = src
    var w = src.width; var h = src.height
    while (w > 48 && h > 48) {
        w = maxOf(24, w / 2); h = maxOf(24, h / 2)
        b = Bitmap.createScaledBitmap(b, w, h, true)
        if (w <= 40 || h <= 40) break
    }
    return b
}

private class Pictures(val sharp: ImageBitmap, val frost: ImageBitmap)

/**
 * The screen's backdrop: [imagePath] filling the screen (cropped), darkened
 * toward the bottom with the theme's background so text reads, and the frost
 * for the panels on it. No picture: the theme background alone.
 */
@Composable
fun GlassBackdrop(imagePath: String?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cs = AlphaTheme.colorScheme
    val pics by produceState<Pictures?>(null, imagePath) {
        value = if (imagePath.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            loadSampled(imagePath, 1920)?.let { Pictures(it.asImageBitmap(), frostOf(it).asImageBitmap()) }
        }
    }
    val sky = remember { mutableStateOf<GlassSky?>(null) }
    var place by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .background(cs.bgPrimary)
            // The picture is cropped wider than the screen: never let it spill out
            // (a sliding screen would paint it over its neighbour).
            .clipToBounds()
            .onGloballyPositioned { place = it.positionInRoot() }
            .drawBehind {
                val p = pics ?: run { sky.value = null; return@drawBehind }
                drawCropped(p.sharp, size)
                // Light at the top, deep toward the content.
                drawRect(Brush.verticalGradient(
                    0f to cs.bgPrimary.copy(alpha = 0.15f),
                    0.5f to cs.bgPrimary.copy(alpha = 0.35f),
                    1f to cs.bgPrimary.copy(alpha = 0.75f),
                ))
                val now = GlassSky(p.frost, place, size)
                if (sky.value?.let { it.frost !== now.frost || it.size != now.size || it.origin != now.origin } != false) sky.value = now
            },
    ) {
        CompositionLocalProvider(LocalGlassSky provides sky) { content() }
    }
}

/** [img] filling [area] like ContentScale.Crop, centred. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropped(img: ImageBitmap, area: Size, at: Offset = Offset.Zero) {
    val scale = maxOf(area.width / img.width, area.height / img.height)
    val w = img.width * scale; val h = img.height * scale
    drawImage(
        img,
        srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset((at.x + (area.width - w) / 2f).toInt(), (at.y + (area.height - h) / 2f).toInt()),
        dstSize = IntSize(w.toInt() + 1, h.toInt() + 1),
        filterQuality = FilterQuality.High,
    )
}

/**
 * A frosted panel: its slice of the backdrop's frost, a tint of the theme's
 * background and a light rim. Without a backdrop picture, just the tint.
 */
fun Modifier.glass(shape: Shape, tint: Float = 0.42f): Modifier = composed {
    val cs = AlphaTheme.colorScheme
    val sky = LocalGlassSky.current
    var at by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { at = it.positionInRoot() }
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val s = sky?.value
            if (s != null) {
                val clip = Path().apply { addOutline(outline) }
                clipPath(clip) {
                    // The frost laid exactly as the picture lies under this panel.
                    drawCropped(s.frost, s.size, s.origin - at)
                }
                drawOutline(outline, cs.bgPrimary.copy(alpha = tint))
            } else {
                drawOutline(outline, cs.bgSecondary.copy(alpha = 0.85f))
            }
            drawOutline(outline, Color.White.copy(alpha = 0.035f))
        }
        .border(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))),
            shape,
        )
}

/** The one strong control on a screen: the accent's gradient with a soft glow. */
fun Modifier.glowFill(shape: Shape): Modifier = composed {
    val cs = AlphaTheme.colorScheme
    this
        .shadow(18.dp, shape, ambientColor = cs.accent, spotColor = cs.accent)
        .background(Brush.linearGradient(listOf(cs.accent, cs.msgOutEnd)), shape)
        .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
}
