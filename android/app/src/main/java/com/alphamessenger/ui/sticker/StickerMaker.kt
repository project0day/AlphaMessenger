package com.alphamessenger.ui.sticker

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The shape a photo is cut to. AS_IS keeps the picture's own transparency. */
enum class StickerShape { SQUARE, CIRCLE, AS_IS }

/** Emoji a sticker can be filed under (the first is the default). */
val STICKER_EMOJI = listOf("😂", "😍", "👍", "😮", "😢", "😠", "😴", "🎉", "❤️", "🔥", "🤔", "👋", "😎", "🙏", "😅", "🤣")

private const val OUT = 512
private const val PAD = 18f      // room for the outline
private const val OUTLINE = 12f

object StickerRender {
    /** [src] decoded no bigger than ~1600 px a side. */
    fun decode(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /**
     * The 512×512 sticker: [src] fitted to the shape (cover for square/circle,
     * whole for "as is"), then the user's zoom [scale] and shift ([dx],[dy] in
     * output pixels), cut to [shape], with a white outline when [outline].
     */
    fun render(src: Bitmap, shape: StickerShape, scale: Float, dx: Float, dy: Float, outline: Boolean): Bitmap {
        val inner = OUT - 2 * PAD
        val art = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val c = Canvas(art)
        val fit = if (shape == StickerShape.AS_IS) minOf(inner / src.width, inner / src.height)
            else maxOf(inner / src.width, inner / src.height)
        val w = src.width * fit * scale
        val h = src.height * fit * scale
        val left = OUT / 2f - w / 2f + dx
        val top = OUT / 2f - h / 2f + dy
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bounds = RectF(PAD, PAD, OUT - PAD, OUT - PAD)
        c.save()
        when (shape) {
            StickerShape.CIRCLE -> c.clipPath(android.graphics.Path().apply { addOval(bounds, android.graphics.Path.Direction.CW) })
            StickerShape.SQUARE -> c.clipPath(android.graphics.Path().apply { addRoundRect(bounds, 56f, 56f, android.graphics.Path.Direction.CW) })
            StickerShape.AS_IS -> c.clipRect(bounds)
        }
        c.drawBitmap(src, null, RectF(left, top, left + w, top + h), paint)
        c.restore()
        if (!outline) return art
        // The outline: the picture's own outline grown and painted white, under it.
        val out = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(AColor.WHITE, PorterDuff.Mode.SRC_IN) }
        val steps = 24
        for (k in 0 until steps) {
            val a = 2 * Math.PI * k / steps
            oc.drawBitmap(art, (OUTLINE * Math.cos(a)).toFloat(), (OUTLINE * Math.sin(a)).toFloat(), white)
        }
        oc.drawBitmap(art, 0f, 0f, null)
        art.recycle()
        return out
    }
}

/** A sticker from one emoji: drawn large, with the same white outline. */
fun emojiSticker(emoji: String): Bitmap {
    val src = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
    val c = Canvas(src)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 380f; textAlign = Paint.Align.CENTER }
    val fm = p.fontMetrics
    c.drawText(emoji, OUT / 2f, OUT / 2f - (fm.ascent + fm.descent) / 2f, p)
    return StickerRender.render(src, StickerShape.AS_IS, 1f, 0f, 0f, true).also { src.recycle() }
}

/**
 * A sticker from a photo: move and zoom it with two fingers, pick a shape,
 * an outline and the emoji it is filed under; "Add" puts it into the pack.
 */
@Composable
fun StickerMaker(
    source: Uri,
    packId: String,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val src by produceState<Bitmap?>(null, source) { value = withContext(Dispatchers.IO) { StickerRender.decode(ctx, source) } }
    var shape by remember { mutableStateOf(StickerShape.CIRCLE) }
    var outline by remember { mutableStateOf(true) }
    var emoji by remember { mutableStateOf(STICKER_EMOJI.first()) }
    var scale by remember { mutableFloatStateOf(1f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    // The preview IS the sticker, rendered off the main thread as things change.
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(src, shape, outline, scale, dx, dy) {
        val s = src ?: return@LaunchedEffect
        kotlinx.coroutines.delay(24) // only the latest gesture step gets rendered
        val b = withContext(Dispatchers.Default) { StickerRender.render(s, shape, scale, dx, dy, outline) }
        preview = b.asImageBitmap()
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = false)
        var pickingEmoji by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color(0xFF111214)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!busy) onDismiss() }) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = Color.White) }
                Text(stringResource(R.string.sticker_new_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(colors.accent)
                        .clickable(enabled = !busy && src != null) {
                            busy = true
                            scope.launch {
                                val s = src
                                val ok = s != null && run {
                                    val bmp = withContext(Dispatchers.Default) { StickerRender.render(s, shape, scale, dx, dy, outline) }
                                    StickerStore.addSticker(ctx, packId, bmp, emoji).also { bmp.recycle() }
                                }
                                busy = false
                                if (ok) onAdded()
                                else android.widget.Toast.makeText(ctx, ctx.getString(R.string.sticker_add_failed), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else Text(stringResource(R.string.sticker_add), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            // The sticker on a checkerboard (transparency shows), moved and zoomed in place.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth - 40.dp, maxHeight - 20.dp)
                Box(
                    Modifier.size(side).clip(RoundedCornerShape(28.dp)).background(Color(0xFF1F2024))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val k = OUT / size.width.toFloat()
                                scale = (scale * zoom).coerceIn(0.4f, 6f)
                                dx = (dx + pan.x * k).coerceIn(-OUT.toFloat(), OUT.toFloat())
                                dy = (dy + pan.y * k).coerceIn(-OUT.toFloat(), OUT.toFloat())
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Checkerboard()
                    preview?.let { Image(it, null, modifier = Modifier.fillMaxSize()) }
                        ?: CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                }
            }
            Text(stringResource(R.string.sticker_gesture_hint), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))

            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sticker_shape), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(StickerShape.SQUARE to R.string.sticker_shape_square, StickerShape.CIRCLE to R.string.sticker_shape_circle,
                        StickerShape.AS_IS to R.string.sticker_shape_as_is).forEach { (s, label) ->
                        val on = s == shape
                        Box(
                            Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(if (on) colors.accent else Color(0xFF2A2B30))
                                .clickable { shape = s; scale = 1f; dx = 0f; dy = 0f }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(label), color = if (on) Color.White else Color.White.copy(alpha = 0.7f), fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sticker_outline), color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = outline, onCheckedChange = { outline = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.accent))
                }
                Text(stringResource(R.string.sticker_emoji), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // "+" first: any emoji from the full set.
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2B30))
                            .clickable { pickingEmoji = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AlphaIcons.Outlined.Add, stringResource(R.string.sticker_emoji_more), tint = colors.accent) }
                    // One picked from the full set leads the row.
                    (if (emoji in STICKER_EMOJI) STICKER_EMOJI else listOf(emoji) + STICKER_EMOJI).forEach { e ->
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (e == emoji) colors.accent else Color(0xFF2A2B30)).clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        // The full emoji set, rising inside this window (a sheet would open under the dialog).
        if (pickingEmoji) {
            androidx.activity.compose.BackHandler { pickingEmoji = false }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { pickingEmoji = false })
            com.alphamessenger.ui.chat.v2.EmojiPanelV2(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(380.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .navigationBarsPadding(),
                colors = colors,
                onPick = { emoji = it; pickingEmoji = false },
                onBackspace = {},
                background = Color(0xFF1C1D21),
            )
        }
        }
    }
}

/** A checkerboard: where a sticker is transparent. */
@Composable
fun Checkerboard(modifier: Modifier = Modifier.fillMaxSize()) {
    androidx.compose.foundation.Canvas(modifier) {
        val cell = 12.dp.toPx()
        var y = 0f; var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < size.width) {
                drawRect(Color(0xFF2A2B30), topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(cell, cell))
                x += 2 * cell
            }
            y += cell; row++
        }
    }
}
