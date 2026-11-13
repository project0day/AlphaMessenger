package com.alphamessenger.ui.chat.preview

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.chat.bubbles.ThumbBitmapCache
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches an encrypted blob (by its ref JSON) into a local file; provided by the chat screen. */
val LocalBlobFetcher = staticCompositionLocalOf<(suspend (String) -> String?)?> { null }

/**
 * LinkPreviewRenderer — a SEPARATE renderer for link previews.
 *
 * The card: the picture across its whole width (a ▶ over a video), then
 * "Site · Title", the description and the domain. The picture shows its small
 * inline copy at once and the full one (an encrypted blob of our storage) as
 * soon as it is here — the size never jumps: both have the same proportions.
 *
 * The card tap is NOT handled here — [onTap] is exposed and the caller decides.
 */
@Composable
fun LinkPreviewRenderer(
    model: LinkPreviewModel,
    colors: AlphaColorScheme,
    textColor: Color,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    /** Non-null: the ▶ plays the video in the app; gets the picture's bounds on screen. */
    onPlay: ((android.graphics.Rect) -> Unit)? = null,
) {
    val domain = LinkPreviewModel.domainOf(model.url).ifBlank { model.domain }
    val site = model.domain.ifBlank { domain }

    Column(
        modifier = modifier
            .width(268.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.12f))
            .clickable(onClick = onTap),
    ) {
        val inlineKey = model.image.takeIf { it.isNotBlank() }
        val dims = remember(inlineKey) { inlineKey?.let { LinkPreviewFetcher.inlineSize(it) } }
        if (inlineKey != null && dims != null) {
            val small by produceState(ThumbBitmapCache.get(cacheKey(inlineKey)), inlineKey) {
                if (value != null) return@produceState
                value = withContext(Dispatchers.IO) {
                    LinkPreviewFetcher.decodeInline(inlineKey)?.asImageBitmap()?.also { ThumbBitmapCache.put(cacheKey(inlineKey), it) }
                }
            }
            val fetcher = LocalBlobFetcher.current
            val ref = model.imageRef.takeIf { it.isNotBlank() }
            val full by produceState<ImageBitmap?>(ref?.let { ThumbBitmapCache.get("lpf:" + it.hashCode()) }, ref) {
                if (value != null || ref == null || fetcher == null) return@produceState
                val path = fetcher(ref) ?: return@produceState
                value = withContext(Dispatchers.IO) { decodeFile(path, 900)?.also { ThumbBitmapCache.put("lpf:" + ref.hashCode(), it) } }
            }
            // From very wide to tall, like the picture; a tall cover is cut a little.
            val ratio = (dims.second.toFloat() / dims.first.coerceAtLeast(1)).coerceIn(0.5f, 1.25f)
            val view = androidx.compose.ui.platform.LocalView.current
            val picture = remember { android.graphics.Rect() }
            Box(
                Modifier.fillMaxWidth().height((268f * ratio).dp).background(Color.Black.copy(alpha = 0.1f))
                    .onGloballyPositioned { c ->
                        val at = IntArray(2).also(view::getLocationOnScreen)
                        val b = c.boundsInWindow()
                        picture.set(at[0] + b.left.toInt(), at[1] + b.top.toInt(), at[0] + b.right.toInt(), at[1] + b.bottom.toInt())
                    },
                contentAlignment = Alignment.Center,
            ) {
                (full ?: small)?.let {
                    androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                if (model.video) Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                        .then(if (onPlay != null) Modifier.clickable { onPlay(android.graphics.Rect(picture)) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Icon(AlphaIcons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp)) {
            // "Site · Title" (the site alone when there is no title).
            val head = when {
                model.title.isBlank() -> site
                site.isBlank() || model.title.startsWith(site, ignoreCase = true) -> model.title
                else -> "$site · ${model.title}"
            }
            if (head.isNotBlank()) Text(
                head, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
            )
            if (model.description.isNotBlank()) Text(
                model.description, color = textColor.copy(alpha = 0.72f), fontSize = 12.5.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (domain.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AlphaIcons.Outlined.Link, null, tint = textColor.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(domain, color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun cacheKey(inline: String) = "lp:" + inline.hashCode() + ":" + inline.length

/** A picture file decoded down to about [side] px (off the main thread). */
private fun decodeFile(path: String, side: Int): ImageBitmap? = runCatching {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    com.alphamessenger.data.MediaVault.decodeFile(path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= side) s *= 2
    com.alphamessenger.data.MediaVault.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()
