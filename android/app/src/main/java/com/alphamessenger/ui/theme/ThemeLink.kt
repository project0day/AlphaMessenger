package com.alphamessenger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable

/** Is [link] a shared theme (`alpha://theme/…`)? */
fun isThemeLink(link: String) = link.startsWith(THEME_LINK_PREFIX)

/** A request to show a shared theme, answered by [ThemeLinkHost]. */
object ThemeUi {
    var request by mutableStateOf<String?>(null)
    fun open(link: String) { request = link }
}

/** Shows a shared theme (its look on a chat preview) with "Apply". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThemeLinkHost() {
    val link = ThemeUi.request ?: return
    val ctx = LocalContext.current
    val vm: ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val wallpaper by vm.chatWallpaper.collectAsState()
    val theme = remember(link) { CustomTheme.fromLink(link) }
    val colors = AlphaTheme.colorScheme
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { ThemeUi.request = null }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(
                theme?.name ?: stringResource(R.string.theme_link_bad),
                color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (theme != null) {
                val scheme = remember(theme) { theme.scheme() }
                // The theme's own pattern, when it brings one, instead of the background set now.
                val shown = theme.pattern?.let { ChatWallpaper.Pattern(it.id(), it) } ?: wallpaper
                com.alphamessenger.ui.settings.LiveChatPreview(scheme = scheme, wallpaper = shown, msgFontFactor = 1f)
                Box(
                    Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp).clip(RoundedCornerShape(16.dp)).background(scheme.accent)
                        .clickable {
                            vm.saveCustomTheme(theme)
                            theme.pattern?.let { p -> PatternStore.save(ctx, p); vm.setChatWallpaper(ChatWallpaper.Pattern(p.id(), p)) }
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.theme_applied), android.widget.Toast.LENGTH_SHORT).show()
                            ThemeUi.request = null
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.theme_link_apply), color = readableOn(scheme.accent), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                // The channel's owner can put the theme into Alpha's channel for everyone.
                if (com.alphamessenger.ui.feed.FeedStore.isOwner) {
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    var sending by remember { mutableStateOf(false) }
                    val inChannel = com.alphamessenger.ui.feed.ThemeCatalog.has(link)
                    Box(
                        Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp).clip(RoundedCornerShape(16.dp)).background(colors.bgTertiary)
                            .clickable(enabled = !sending && !inChannel) {
                                val repo = com.alphamessenger.ui.emoji.AnimatedEmoji.repo ?: return@clickable
                                val key = com.alphamessenger.ui.feed.FeedStore.ownerKey(ctx) ?: return@clickable
                                sending = true
                                scope.launch {
                                    val ok = com.alphamessenger.ui.feed.ThemeCatalog.add(ctx, repo, key, link, theme.name)
                                    sending = false
                                    android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.theme_to_channel_done else R.string.feed_publish_failed),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(when { inChannel -> R.string.theme_in_channel; sending -> R.string.theme_to_channel_sending; else -> R.string.theme_to_channel }),
                            color = if (inChannel) colors.textMuted else colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}


/** The first theme link in [text], if any. */
fun extractThemeLink(text: String): String? =
    Regex(Regex.escape(THEME_LINK_PREFIX) + """[A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_\-]+)?""").find(text)?.value

/**
 * A theme in a message: its colours on a tiny chat, its name, and "View" (the
 * preview with Apply) and "Share" — never the long raw link.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ThemeLinkCard(link: String, colors: AlphaColorScheme, onAccentBubble: Boolean, onLongClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val theme = remember(link) { CustomTheme.fromLink(link) }
    val scheme = remember(theme) { theme?.scheme() }
    val bg = if (onAccentBubble) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.20f) else colors.accent.copy(alpha = 0.12f)
    val title = if (onAccentBubble) androidx.compose.ui.graphics.Color.White else colors.textPrimary
    val sub = if (onAccentBubble) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f) else colors.textSecondary
    Column(
        Modifier.widthIn(min = 220.dp, max = 280.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .combinedClickable(onClick = { ThemeUi.open(link) }, onLongClick = onLongClick).padding(10.dp),
    ) {
        if (scheme != null && theme != null) {
            val chatBg = theme.background?.let { b1 ->
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(b1, theme.background2 ?: b1))
            } ?: androidx.compose.ui.graphics.SolidColor(scheme.bgChat)
            Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(10.dp)).background(chatBg)) {
            theme.pattern?.let { PatternWallpaper(it, Modifier.matchParentSize(), dark = !theme.isLight) }
            Column(
                Modifier.fillMaxWidth().height(92.dp).padding(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.width(118.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(scheme.msgIn))
                Box(
                    Modifier.align(Alignment.End).width(136.dp).height(20.dp).clip(RoundedCornerShape(10.dp))
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(scheme.msgOutStart, scheme.msgOutEnd))),
                )
                Box(Modifier.width(84.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(scheme.msgIn))
            }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            theme?.name?.takeIf { it.isNotBlank() } ?: stringResource(if (theme == null) R.string.theme_link_bad else R.string.theme_card_title),
            color = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
        if (theme != null) {
            Text(
                stringResource(if (theme.isLight) R.string.theme_card_light else R.string.theme_card_dark),
                color = sub, fontSize = 12.5.sp,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                CardPill(stringResource(R.string.theme_card_view), filled = true, onAccentBubble, colors) { ThemeUi.open(link) }
                CardPill(stringResource(R.string.theme_editor_share), filled = false, onAccentBubble, colors) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, link)
                    ctx.startActivity(android.content.Intent.createChooser(send, null))
                }
            }
        }
    }
}

@Composable
private fun CardPill(text: String, filled: Boolean, onAccentBubble: Boolean, colors: AlphaColorScheme, onClick: () -> Unit) {
    val fill = when {
        onAccentBubble && filled -> androidx.compose.ui.graphics.Color.White
        onAccentBubble -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f)
        filled -> colors.accent
        else -> colors.bgTertiary
    }
    val ink = when {
        onAccentBubble && filled -> colors.accent
        onAccentBubble -> androidx.compose.ui.graphics.Color.White
        filled -> readableOn(colors.accent)
        else -> colors.textPrimary
    }
    Text(
        text, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(fill).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
