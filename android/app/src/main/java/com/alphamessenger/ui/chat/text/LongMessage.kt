package com.alphamessenger.ui.chat.text

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionTopBar
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.LocalMessageFontScale
import com.alphamessenger.ui.theme.themeBackground

/** How much of a long text a chat bubble shows before "Read more". */
const val LONG_TEXT_PREVIEW_CHARS = 3000

/** Where a bubble cuts a long text: a channel post is read whole in its feed. */
/** Texts here are shown whole (an assistant's answers: one is still being written). */
val LocalWholeText = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun previewLimit(): Int =
    if (com.alphamessenger.ui.channel.LocalChannelPostWidth.current != null || LocalWholeText.current) Int.MAX_VALUE
    else LONG_TEXT_PREVIEW_CHARS

/**
 * A media bubble's caption: shown up to "Read more" like any long text, and a tap or
 * hold on its words acts as one on the bubble.
 */
@Composable
fun BubbleCaption(
    text: String,
    onAccent: Boolean,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var readWhole by remember(text) { mutableStateOf(false) }
    MessageText(
        text = text,
        onAccent = onAccent,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        modifier = modifier,
        selectionMode = selectionMode,
        onTextTap = onTap,
        onTextLongPress = onLongPress,
        previewChars = previewLimit(),
        onReadMore = { readWhole = true },
    )
    if (readWhole) LongMessageScreen(text) { readWhole = false }
}

/**
 * A long message read whole on its own screen; the bubble keeps only its start,
 * so the chat never lays out or unfolds the full text. It opens where the bubble
 * left off, the read part above, and the place to go on from glows for a moment.
 */
@Composable
fun LongMessageScreen(text: String, onDismiss: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = cs.bgPrimary.luminance() >= 0.5f)
        val density = LocalDensity.current
        val scroll = rememberScrollState()
        val resume = remember(text) { previewCut(Markup.parse(text).text, LONG_TEXT_PREVIEW_CHARS) }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val glow = remember { Animatable(0f) }
        val topPad = 8.dp
        LaunchedEffect(layout != null) {
            val l = layout ?: return@LaunchedEffect
            val at = resume?.coerceAtMost(l.layoutInput.text.length) ?: return@LaunchedEffect
            val line = l.getLineForOffset(at)
            // One line of what was read stays in view above the place to go on from.
            val above = if (line > 0) l.getLineBottom(line - 1) - l.getLineTop(line - 1) else 0f
            scroll.scrollTo((with(density) { topPad.toPx() } + l.getLineTop(line) - above).toInt())
            glow.snapTo(1f)
            glow.animateTo(0f, tween(1800))
        }
        Column(Modifier.fillMaxSize().themeBackground(cs)) {
            OptionTopBar(stringResource(R.string.long_message_title), onDismiss)
            Column(
                Modifier.weight(1f).verticalScroll(scroll).navigationBarsPadding()
                    .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            ) {
                val scale = LocalMessageFontScale.current
                MessageText(
                    text = text,
                    color = cs.textPrimary,
                    fontSize = (16f * scale).sp,
                    lineHeight = (23f * scale).sp,
                    selectionMode = true,
                    onTextLayout = { layout = it },
                    modifier = Modifier.padding(top = topPad).drawBehind {
                        val l = layout ?: return@drawBehind
                        val at = resume ?: return@drawBehind
                        if (glow.value <= 0f || at > l.layoutInput.text.length) return@drawBehind
                        val first = l.getLineForOffset(at)
                        val last = (first + 2).coerceAtMost(l.lineCount - 1)
                        val pad = 4.dp.toPx()
                        val top = l.getLineTop(first) - pad
                        drawRoundRect(
                            cs.accent.copy(alpha = 0.18f * glow.value),
                            Offset(-pad * 2, top),
                            Size(size.width + pad * 4, l.getLineBottom(last) + pad - top),
                            CornerRadius(pad * 2, pad * 2),
                        )
                    },
                )
            }
        }
    }
}
