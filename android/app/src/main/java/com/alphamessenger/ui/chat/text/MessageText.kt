package com.alphamessenger.ui.chat.text

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

/**
 * MessageText — the ONLY renderer of message text (TextRenderer).
 *
 *   Bubble → MessageText → TextParser → List<TextEntity> → AnnotatedString
 *
 * Bubble knows nothing about links/emails/phones/invites. All text logic
 * lives here and only here. EVERY message text goes through this component.
 *
 * Modes:
 *  - Normal ([selectionMode] = false): clickable entities. A link tap is
 *    intercepted INSIDE the text and does NOT reach Bubble (the bubble does
 *    not open the action sheet). A tap on plain text reaches Bubble as usual.
 *  - Selection ([selectionMode] = true): the text is wrapped in a
 *    SelectionContainer, links are temporarily not clickable, the user
 *    selects any range and copies only it. Everything returns to normal on
 *    exit.
 *
 * Future (skeleton already in the model): markdown, spoiler, @mention,
 * #hashtag, search highlighting, translation, AI annotations — added as new
 * TextParser matchers and styles here, WITHOUT changing the MessageText
 * public contract.
 */

/** Standard blue for in-text links. */
val LinkBlue = Color(0xFF53A9FF)

/**
 * The link colour for a bubble of colour [bg] whose text is [text]: the usual
 * blue where it reads (contrast 3:1 or more); on a bubble too close to blue, a
 * pale blue on dark-text-light bubbles' opposite — light when the text is light,
 * a deep blue when the text is dark.
 */
fun linkColorOn(bg: Color, text: Color): Color {
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f; val lb = b.luminance() + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }
    if (contrast(LinkBlue, bg) >= 3f) return LinkBlue
    // A light-blue bubble: a deep blue reads on it and is still clearly "a link".
    val deep = Color(0xFF0B3A8C)
    if (contrast(deep, bg) >= 3f) return deep
    // A dark or saturated bubble: a pale blue, set apart by weight and underline.
    return if (text.luminance() > 0.5f) Color(0xFFCDE6FF) else Color(0xFF0A58B0)
}

/**
 * Where a tap on a link / hashtag in any message text goes when the caller has
 * no handler of its own (captions of photos, videos, files): the chat screen.
 */
val LocalTextEntityTap = androidx.compose.runtime.staticCompositionLocalOf<(TextEntity) -> Unit> { {} }

/** Annotation tag that carries the entity inside AnnotatedString. */
private const val ENTITY_TAG = "alpha_entity"
private const val READ_MORE_TAG = "alpha_read_more"

/** Where a text longer than [limit] is cut for its preview (a word end), or null. */
internal fun previewCut(text: String, limit: Int): Int? {
    if (text.length <= limit) return null
    val space = text.lastIndexOfAny(charArrayOf(' ', '\n'), limit)
    var cut = if (space >= limit * 4 / 5) space else limit
    if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
    return cut
}

/**
 * Build an AnnotatedString from raw text: every clickable entity gets the
 * link style + a string annotation with its index in [entities]. Plain text
 * keeps the [Text] color.
 */
fun buildMessageAnnotated(
    text: String,
    entities: List<TextEntity>,
    linkColor: Color = LinkBlue,
    spans: List<Markup.Span> = emptyList(),
    highlight: String? = null,
): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        entities.forEachIndexed { index, e ->
            if (e.start > last) append(text.substring(last, e.start))
            val clickable = e.clickable
            if (clickable) {
                pushStringAnnotation(tag = ENTITY_TAG, annotation = index.toString())
            }
            withStyle(
                style = if (clickable) {
                    // A hashtag is coloured, not underlined (it is not a web address).
                    if (e is TextEntity.Hashtag) SpanStyle(color = linkColor, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    else SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                } else if (e is TextEntity.Mention) {
                    SpanStyle(color = linkColor, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                } else {
                    SpanStyle()
                },
            ) {
                append(text.substring(e.start, e.end))
            }
            if (clickable) pop()
            last = e.end
        }
        if (last < text.length) append(text.substring(last))
        for (s in spans) addStyle(markupStyle(s.kind, linkColor), s.start, s.end)
        if (!highlight.isNullOrBlank()) for (r in SearchMatch.hits(text, highlight)) addStyle(SEARCH_HIT, r.first, r.last + 1)
    }

/** Letters still fading in, from the end: [k] is the k-th last letter's opacity. */
val LocalTextFade = androidx.compose.runtime.compositionLocalOf<FloatArray?> { null }

/** [text] with its last letters as transparent as [fade] says (a few markup signs off at most). */
private fun fadeTail(text: AnnotatedString, fade: FloatArray, color: Color): AnnotatedString {
    if (fade.isEmpty() || text.isEmpty()) return text
    // Each style run is drawn on its own: a run edge inside an emoji draws it in pieces.
    val clusters = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(text.text) }
    fun whole(i: Int) = if (i <= 0 || i >= text.length || clusters.isBoundary(i)) i else clusters.preceding(i).coerceAtLeast(0)
    return buildAnnotatedString {
        append(text)
        var k = 0
        while (k < fade.size) {
            var j = k
            while (j + 1 < fade.size && fade[j + 1] == fade[k]) j++
            val end = whole(text.length - k)
            val start = whole((text.length - 1 - j).coerceAtLeast(0))
            if (end > start) addStyle(SpanStyle(color = color.copy(alpha = color.alpha * fade[k])), start, end)
            k = j + 1
        }
    }
}

/** What the chat is searching for: every text on screen marks its matches. */
val LocalSearchHighlight = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

private val SEARCH_HIT = SpanStyle(background = Color(0xFFFDE68A), color = Color(0xFF1C1917))


private fun markupStyle(kind: MarkdownKind, accent: Color): SpanStyle = when (kind) {
    MarkdownKind.BOLD -> SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    MarkdownKind.ITALIC -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    MarkdownKind.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    MarkdownKind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    MarkdownKind.CODE, MarkdownKind.CODE_BLOCK -> SpanStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        background = accent.copy(alpha = 0.14f),
    )
}

/**
 * Renders message text.
 *
 * @param text          raw message text
 * @param color         plain-text color (from Bubble)
 * @param fontSize      font size
 * @param lineHeight    line height
 * @param maxLines      line cap (collapsing long messages)
 * @param overflow      overflow behavior
 * @param selectionMode selection mode (partial selection + copy)
 * @param onEntityTap   tap on a clickable entity (link/email/phone/invite)
 * @param onTextLayout  layout result passthrough (collapse etc.)
 * @param modifier      outer modifier
 */
@Composable
fun MessageText(
    text: String,
    /** The text sits on our own (accent) bubble rather than an incoming one. */
    onAccent: Boolean = false,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    selectionMode: Boolean = false,
    /** null = the screen's own handler ([LocalTextEntityTap]). */
    onEntityTap: ((TextEntity) -> Unit)? = null,
    // Taps on PLAIN (non-link) text are passed through — Bubble gets its
    // tap/long-press while links are intercepted inside.
    onTextTap: (() -> Unit)? = null,
    onTextLongPress: (() -> Unit)? = null,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    /** With [onReadMore]: a longer text shows only its start, ending in "Read more". */
    previewChars: Int = Int.MAX_VALUE,
    onReadMore: (() -> Unit)? = null,
) {
    val screenTap = LocalTextEntityTap.current
    val tapEntity: (TextEntity) -> Unit = onEntityTap ?: screenTap
    val highlight = LocalSearchHighlight.current
    // A code block is its own panel: the message is laid out as prose and code in turn.
    if (remember(text) { Markup.hasBlock(text) }) {
        val blocks = remember(text) { Markup.blocks(text) }
        val accent = com.alphamessenger.ui.theme.AlphaTheme.colorScheme.accent
        val fade = LocalTextFade.current
        androidx.compose.foundation.layout.Column(modifier) {
            for ((i, b) in blocks.withIndex()) when (b) {
                is Markup.Block.Prose -> androidx.compose.runtime.CompositionLocalProvider(
                    LocalTextFade provides fade?.takeIf { i == blocks.lastIndex },
                ) { MessageText(
                    text = b.raw, onAccent = onAccent, color = color, fontSize = fontSize, lineHeight = lineHeight,
                    selectionMode = selectionMode, onEntityTap = onEntityTap,
                    onTextTap = onTextTap, onTextLongPress = onTextLongPress,
                ) }
                is Markup.Block.Code -> CodeBlockView(
                    lang = b.lang, code = b.code,
                    accent = accent,
                    fontSize = fontSize * 0.82f, selectionMode = selectionMode,
                )
            }
        }
        return
    }
    // Markers out first: links and mentions are found in the text as it reads.
    val parsed = remember(text) { Markup.parse(text) }
    val cut = remember(parsed.text, previewChars) { if (onReadMore == null) null else previewCut(parsed.text, previewChars) }
    val shown = if (cut == null) parsed.text else parsed.text.substring(0, cut)
    val entities = remember(shown) { TextParser.parse(shown) }
    // Links and hashtags in a colour that reads on THIS bubble.
    val scheme = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
    val linkColor = remember(onAccent, scheme.accent, scheme.msgIn, color) {
        linkColorOn(if (onAccent) scheme.accent else scheme.msgIn, color)
    }
    val readMore = androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.chat_read_more)
    val annotatedWhole = remember(text, linkColor, cut, readMore, highlight) {
        val spans = if (cut == null) parsed.spans
            else parsed.spans.filter { it.start < cut }.map { it.copy(end = minOf(it.end, cut)) }
        val body = buildMessageAnnotated(shown, entities, linkColor, spans, highlight)
        if (cut == null) body else buildAnnotatedString {
            append(body)
            append("… ")
            pushStringAnnotation(READ_MORE_TAG, "")
            withStyle(SpanStyle(color = linkColor, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(readMore) }
            pop()
        }
    }
    val fade = LocalTextFade.current
    val annotated = if (fade == null) annotatedWhole else fadeTail(annotatedWhole, fade, color)
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val baseStyle = TextStyle(
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
    )

    if (selectionMode) {
        // Selection mode: partial selection + range copy. Link clicks are
        // off — SelectionContainer owns the gestures. Selection colors are set
        // EXPLICITLY: the Material default is nearly invisible in Alpha's dark
        // theme (grey on grey). LinkBlue with a translucent background and
        // solid handles reads on both light and dark bubbles.
        val selectionColors = TextSelectionColors(
            handleColor = LinkBlue,
            backgroundColor = LinkBlue.copy(alpha = 0.35f),
        )
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        SelectionContainer {
            Text(
                text = annotated,
                style = baseStyle,
                maxLines = maxLines,
                overflow = overflow,
                onTextLayout = { res ->
                    layoutResult = res
                    onTextLayout(res)
                },
                modifier = modifier,
            )
        }
        }
    } else {
        // The gesture outlives recompositions: it must act with what is shown NOW
        // (a stale copy sent "Read more" to a state no longer on screen).
        val nowAnnotated by androidx.compose.runtime.rememberUpdatedState(annotated)
        val nowReadMore by androidx.compose.runtime.rememberUpdatedState(onReadMore)
        val nowTextTap by androidx.compose.runtime.rememberUpdatedState(onTextTap)
        val nowLongPress by androidx.compose.runtime.rememberUpdatedState(onTextLongPress)
        val nowEntityTap by androidx.compose.runtime.rememberUpdatedState(tapEntity)
        val nowEntities by androidx.compose.runtime.rememberUpdatedState(entities)
        // Normal mode: clickable entities. A link tap is intercepted here
        // and never reaches Bubble; a plain-text tap passes to Bubble.
        Text(
            text = annotated,
            style = baseStyle,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { res ->
                layoutResult = res
                onTextLayout(res)
            },
            modifier = modifier.pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val text = nowAnnotated
                        val layout = layoutResult
                        val offset = layout?.getOffsetForPosition(pos) ?: -1
                        // "Read more" sits last: a tap past its final letter still counts.
                        val readMoreHit = offset >= 0 && text.getStringAnnotations(READ_MORE_TAG, 0, text.length)
                            .any { offset >= it.start && offset <= it.end }
                        val readMore = nowReadMore
                        if (readMoreHit && readMore != null) {
                            readMore()
                            return@detectTapGestures
                        }
                        val entity = if (offset >= 0) {
                            text
                                .getStringAnnotations(offset, offset)
                                .firstOrNull { it.tag == ENTITY_TAG }
                                ?.let { nowEntities.getOrNull(it.item.toIntOrNull() ?: -1) }
                        } else null
                        if (entity != null && entity.clickable) {
                            // Link tap — handled here, Bubble does not get it.
                            nowEntityTap(entity)
                        } else {
                            // Plain-text tap — hand it to Bubble (action sheet).
                            nowTextTap?.invoke()
                        }
                    },
                    onLongPress = {
                        // Long-press ALWAYS goes to Bubble (message menu) — even
                        // on a link. No separate link menu on long-press (UX decision).
                        nowLongPress?.invoke()
                    },
                )
            },
        )
    }
}
