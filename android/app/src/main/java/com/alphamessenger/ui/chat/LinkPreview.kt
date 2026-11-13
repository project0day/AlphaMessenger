package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

// ══════════════════════════════════════════════════════════════════════
// Link preview (Open Graph) — generated on the SENDER, sent as metadata.
// ══════════════════════════════════════════════════════════════════════

data class LinkPreviewData(
    val url: String,
    val title: String,
    val description: String,
    /** The small inline picture (data URI), shown at once. */
    val image: String,
    val site: String,
    /** The full picture's encrypted blob ref (fetched from our storage). */
    val imageRef: String = "",
    /** The link is a video (a ▶ over the picture). */
    val video: Boolean = false,
    /** Sender only: the full picture on disk, until it is uploaded. */
    val fullPath: String = "",
)

/** First URL in [text] (http(s):// or bare www.), normalized to https://…,
 *  or null. */
fun extractFirstUrl(text: String): String? {
    val m = Regex("""(https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)?.value
        ?.trimEnd('.', ',', ')', ']', '!', '?') ?: return null
    return if (m.startsWith("www.", ignoreCase = true)) "https://$m" else m
}

/** Standard link blue used for URLs inside message text. */
val LinkBlue = Color(0xFF53A9FF)

/** Build an AnnotatedString where every URL is blue + underlined. Non-link text
 *  keeps the Text's own colour. */
/** First invite link (`alpha://channel/…`, `alpha://group/…`, or the first
 *  format `alpha://group/invite/…`) in [text], or null. */
fun extractInviteLink(text: String): String? =
    Regex("""alpha://(?:group/invite|group|channel)/[A-Za-z0-9_\-]+""").find(text)?.value

/**
 * What an invite link says about itself, read the same way the core writes it
 * (`alpha-ffi/src/invite_link.rs` — keep the two in step): a binary record
 * `[1][kind][n][group id n][token 16][inviter 32][name …]`; the first format
 * is base64 JSON with `n` (name) and `d` (description).
 */
data class InviteInfo(val channel: Boolean, val name: String, val description: String, val byRequest: Boolean = true)

fun parseInvite(link: String): InviteInfo = runCatching {
    val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    if (link.contains("/invite/")) {
        val o = org.json.JSONObject(String(android.util.Base64.decode(link.substringAfterLast("/invite/"), flags)))
        InviteInfo(false, o.optString("n"), o.optString("d"))
    } else {
        val b = android.util.Base64.decode(link.substringAfterLast('/'), flags)
        val head = 3 + (b[2].toInt() and 0xFF) + 16 + 32
        val name = if (b[0].toInt() == 1 && b.size >= head) String(b, head, b.size - head, Charsets.UTF_8) else ""
        InviteInfo(b[1].toInt() and 1 != 0, name, "", b[1].toInt() and 2 != 0)
    }
}.getOrDefault(InviteInfo(link.startsWith("alpha://channel/"), "", ""))

@Deprecated(
    "Заменено единым TextRenderer: com.alphamessenger.ui.chat.text.MessageText",
    ReplaceWith("MessageText(text, ...)", "com.alphamessenger.ui.chat.text.MessageText"),
)
fun linkify(text: String): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        val regex = Regex("""(https?://|www\.|alpha://(?:group/invite|group|channel)/)[^\s<>"']+""", RegexOption.IGNORE_CASE)
        var last = 0
        for (m in regex.findAll(text)) {
            if (m.range.first > last) append(text.substring(last, m.range.first))
            withStyle(
                style = androidx.compose.ui.text.SpanStyle(
                    color = LinkBlue,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
            ) { append(m.value) }
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

/** Parse the link payload stored in a media message's media_ref JSON. */
fun parseLinkPreview(refJson: String?): LinkPreviewData? {
    if (refJson == null) return null
    return runCatching {
        val o = org.json.JSONObject(refJson)
        if (!o.optBoolean("link", false)) return null
        LinkPreviewData(
            url = o.optString("url"),
            title = o.optString("title"),
            description = o.optString("description"),
            image = o.optString("image"),
            site = o.optString("site"),
            imageRef = o.optString("image_ref"),
            video = o.optBoolean("video", false),
        )
    }.getOrNull()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InviteJoinCard(
    link: String,
    colors: AlphaColorScheme,
    onAccentBubble: Boolean = false,
    onJoin: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    // Hoisted so the remember{} lambda below can use the localized fallback.
    val info = remember(link) { parseInvite(link) }
    val fallback = stringResource(if (info.channel) R.string.invite_channel_fallback else R.string.invite_group_fallback)
    val name = info.name.ifBlank { fallback }
    val description = info.description

    // On the sender's accent-coloured (blue) bubble, accent-on-accent is
    // invisible — use white/contrasting colours there; on incoming bubbles use
    // the theme accent. Works across all themes.
    val cardBg = if (onAccentBubble) Color.White.copy(alpha = 0.20f) else colors.accent.copy(alpha = 0.12f)
    val titleColor = if (onAccentBubble) Color.White else colors.accent
    val nameColor = if (onAccentBubble) Color.White.copy(alpha = 0.9f) else colors.textPrimary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .clickable { showSheet = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AlphaIcons.Outlined.Link, null, tint = titleColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(if (info.channel) R.string.invite_card_title_channel else R.string.invite_card_title),
                color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(name, color = nameColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    if (showSheet) {
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(colors.accentDim),
                    contentAlignment = Alignment.Center,
                ) { Icon(AlphaIcons.Outlined.Link, null, tint = colors.accent, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.height(12.dp))
                Text(name, color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (description.isNotBlank()) {
                    Text(
                        description, color = colors.textSecondary, fontSize = 13.sp,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    Text(stringResource(R.string.invite_join_question), color = colors.textMuted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(20.dp))
                // Two buttons in one row — half each.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(colors.bgTertiary).clickable { showSheet = false }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(colors.accent).clickable { showSheet = false; onJoin() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // By link: one tap and you are in. By request: the owner decides.
                        Text(stringResource(if (info.byRequest) R.string.invite_send_request else R.string.invite_join),
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun LinkMenuRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = colors.textPrimary, fontSize = 15.sp)
    }
}
