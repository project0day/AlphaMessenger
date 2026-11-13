package com.alphamessenger.ui.chat.preview

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.chat.LinkPreviewData
import com.alphamessenger.ui.chat.text.LinkBlue
import com.alphamessenger.ui.theme.AlphaColorScheme

/** Link preview above the composer: shown while typing, ✕ sends the
 *  message without a preview. The sender is the only one who contacts the site. */
@Composable
fun ComposerLinkPreview(
    data: LinkPreviewData?,
    loading: Boolean,
    colors: AlphaColorScheme,
    onClose: () -> Unit,
) {
    if (data == null && !loading) return
    // Decoded off the main thread (it is a whole JPEG).
    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, data?.image) {
        val img = data?.image ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LinkPreviewFetcher.decodeInline(img)?.asImageBitmap() }
    }
    // A quote card at the top of the input field (not a strip of its own).
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LinkBlue.copy(alpha = 0.10f))
            .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(LinkBlue))
        Spacer(Modifier.width(8.dp))
        if (thumb != null) {
            Image(
                thumb!!, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Icon(AlphaIcons.Outlined.Link, null, tint = LinkBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            if (data == null) {
                Text(stringResource(R.string.chat_link_preview_loading), color = colors.textMuted, fontSize = 13.sp)
            } else {
                Text(
                    data.site.ifBlank { data.url }, color = LinkBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (data.title.isNotBlank()) {
                    Text(
                        data.title, color = colors.textPrimary, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}
