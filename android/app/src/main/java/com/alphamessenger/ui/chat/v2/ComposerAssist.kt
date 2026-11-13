package com.alphamessenger.ui.chat.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.chat.text.MarkdownKind
import com.alphamessenger.ui.chat.text.Markup
import com.alphamessenger.ui.theme.AlphaColorScheme

/** A group member offered after "@". [token] is what lands in the text. */
data class MentionCandidate(val name: String, val avatarPath: String) {
    val token: String = name.trim().replace(Regex("\\s+"), "_")
        .filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(40)
}

/** "@que|" right before the cursor: the index of "@" and the typed part. */
fun mentionQuery(v: TextFieldValue): Pair<Int, String>? {
    if (!v.selection.collapsed) return null
    val before = v.text.substring(0, v.selection.start.coerceIn(0, v.text.length))
    val m = Regex("""(?:^|\s)@([\p{L}\p{N}_.]{0,40})$""").find(before) ?: return null
    val q = m.groupValues[1]
    return (before.length - q.length - 1) to q
}

/** Replace "@que" with "@Token " and put the cursor after it. */
fun insertMention(v: TextFieldValue, at: Int, c: MentionCandidate): TextFieldValue {
    val cursor = v.selection.start
    val ins = "@${c.token} "
    val text = v.text.substring(0, at) + ins + v.text.substring(cursor)
    return TextFieldValue(text, TextRange(at + ins.length))
}

/** The formatting entries of every selection menu, in their order. */
val COMPOSER_FORMATS = listOf(
    MarkdownKind.BOLD, MarkdownKind.ITALIC, MarkdownKind.UNDERLINE,
    MarkdownKind.STRIKETHROUGH, MarkdownKind.CODE, MarkdownKind.CODE_BLOCK,
)

@Composable
fun composerFormatLabels(): List<String> = COMPOSER_FORMATS.map {
    androidx.compose.ui.res.stringResource(
        when (it) {
            MarkdownKind.BOLD -> com.alphamessenger.R.string.format_bold
            MarkdownKind.ITALIC -> com.alphamessenger.R.string.format_italic
            MarkdownKind.UNDERLINE -> com.alphamessenger.R.string.format_underline
            MarkdownKind.STRIKETHROUGH -> com.alphamessenger.R.string.format_strike
            MarkdownKind.CODE -> com.alphamessenger.R.string.format_mono
            MarkdownKind.CODE_BLOCK -> com.alphamessenger.R.string.format_code_block
        },
    )
}

/** Put the selection in a code block, each fence on its own line. */
private fun applyCodeBlock(v: TextFieldValue): TextFieldValue {
    val t = v.text
    val s = v.selection.min
    val e = v.selection.max
    if (s == e) return v
    val before = if (s > 0 && t[s - 1] != '\n') "\n" else ""
    val after = if (e < t.length && t[e] != '\n') "\n" else ""
    val open = before + "```\n"
    val text = t.substring(0, s) + open + t.substring(s, e) + "\n```" + after + t.substring(e)
    return TextFieldValue(text, TextRange(s + open.length, e + open.length))
}

/** Wrap the selection in [kind]'s markers (or unwrap if it already is). */
fun applyMarkup(v: TextFieldValue, kind: MarkdownKind): TextFieldValue {
    if (kind == MarkdownKind.CODE_BLOCK) return applyCodeBlock(v)
    val m = Markup.marker(kind)
    val t = v.text
    // Spaces / line breaks at the edges of a selection stay outside the markers:
    // "__word __" is not markup and would arrive with the underscores showing.
    var s = v.selection.min
    var e = v.selection.max
    while (s < e && t[s].isWhitespace()) s++
    while (e > s && t[e - 1].isWhitespace()) e--
    if (s == e) return v
    val wrapped = s >= m.length && e + m.length <= t.length &&
        t.regionMatches(s - m.length, m, 0, m.length) && t.regionMatches(e, m, 0, m.length)
    return if (wrapped) {
        val text = t.substring(0, s - m.length) + t.substring(s, e) + t.substring(e + m.length)
        TextFieldValue(text, TextRange(s - m.length, e - m.length))
    } else {
        val text = t.substring(0, s) + m + t.substring(s, e) + m + t.substring(e)
        TextFieldValue(text, TextRange(s + m.length, e + m.length))
    }
}

/**
 * The system selection menu (Copy / Paste / Cut / Select all) with the
 * formatting entries of [COMPOSER_FORMATS] added; [labels] are their titles.
 */
class FormattingTextToolbar(
    private val view: android.view.View,
    private val labels: List<String>,
    private val onFormat: (MarkdownKind) -> Unit,
) : androidx.compose.ui.platform.TextToolbar {
    private var mode: android.view.ActionMode? = null
    private var rect = androidx.compose.ui.geometry.Rect.Zero
    private var onCopy: (() -> Unit)? = null
    private var onPaste: (() -> Unit)? = null
    private var onCut: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null

    override var status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.rect = rect
        onCopy = onCopyRequested; onPaste = onPasteRequested; onCut = onCutRequested; onSelectAll = onSelectAllRequested
        val m = mode
        if (m == null) {
            status = androidx.compose.ui.platform.TextToolbarStatus.Shown
            mode = view.startActionMode(callback, android.view.ActionMode.TYPE_FLOATING)
        } else {
            m.invalidate()
        }
    }

    override fun hide() {
        status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
        mode?.finish()
        mode = null
    }

    private val formats = COMPOSER_FORMATS

    private val callback = object : android.view.ActionMode.Callback2() {
        override fun onCreateActionMode(m: android.view.ActionMode, menu: android.view.Menu): Boolean = true

        override fun onPrepareActionMode(m: android.view.ActionMode, menu: android.view.Menu): Boolean {
            menu.clear()
            var order = 0
            onCut?.let { menu.add(0, ID_CUT, order++, android.R.string.cut) }
            onCopy?.let { menu.add(0, ID_COPY, order++, android.R.string.copy) }
            onPaste?.let { menu.add(0, ID_PASTE, order++, android.R.string.paste) }
            onSelectAll?.let { menu.add(0, ID_SELECT_ALL, order++, android.R.string.selectAll) }
            // Formatting only makes sense on a selection, which is when Copy is offered.
            if (onCopy != null) formats.forEachIndexed { i, _ ->
                menu.add(0, ID_FORMAT + i, order++, labels.getOrElse(i) { "" })
                    .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            return true
        }

        override fun onActionItemClicked(m: android.view.ActionMode, item: android.view.MenuItem): Boolean {
            when (val id = item.itemId) {
                ID_CUT -> onCut?.invoke()
                ID_COPY -> onCopy?.invoke()
                ID_PASTE -> onPaste?.invoke()
                ID_SELECT_ALL -> { onSelectAll?.invoke(); return true }
                else -> formats.getOrNull(id - ID_FORMAT)?.let(onFormat) ?: return false
            }
            m.finish()
            return true
        }

        override fun onDestroyActionMode(m: android.view.ActionMode) {
            mode = null
            status = androidx.compose.ui.platform.TextToolbarStatus.Hidden
        }

        override fun onGetContentRect(m: android.view.ActionMode, v: android.view.View, outRect: android.graphics.Rect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    private companion object {
        const val ID_CUT = 1
        const val ID_COPY = 2
        const val ID_PASTE = 3
        const val ID_SELECT_ALL = 4
        const val ID_FORMAT = 100
    }
}

/**
 * Gives the text field inside [content] the system selection menu with the
 * formatting entries. [value] / [onValueChange] are that field's own state.
 */
@Composable
fun FormattingToolbarHost(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    content: @Composable () -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val labels = composerFormatLabels()
    val latestValue = androidx.compose.runtime.rememberUpdatedState(value)
    val latestChange = androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val toolbar = androidx.compose.runtime.remember(view, labels) {
        FormattingTextToolbar(view, labels) { kind -> latestChange.value(applyMarkup(latestValue.value, kind)) }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalTextToolbar provides toolbar,
        content = content,
    )
}

/**
 * The same for a field that keeps a plain String: the selection lives here,
 * the text goes out through [onValueChange].
 */
@Composable
fun FormattingToolbarHost(
    value: String,
    onValueChange: (String) -> Unit,
    content: @Composable (TextFieldValue, (TextFieldValue) -> Unit) -> Unit,
) {
    val held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Changed from outside (sent, cleared): take the new text, cursor at its end.
    val shown = if (held.value.text == value) held.value else TextFieldValue(value, TextRange(value.length))
    val change: (TextFieldValue) -> Unit = { v -> held.value = v; if (v.text != value) onValueChange(v.text) }
    FormattingToolbarHost(shown, change) { content(shown, change) }
}

/** Members matching what follows "@", at most five. */
@Composable
fun MentionList(items: List<MentionCandidate>, colors: AlphaColorScheme, onPick: (MentionCandidate) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (items.isEmpty()) 0.dp else 4.dp)) {
        items.forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(com.alphamessenger.ui.components.avatarInitial(c.name), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (c.avatarPath.isNotBlank()) {
                        coil3.compose.AsyncImage(
                            model = java.io.File(c.avatarPath), contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(28.dp).clip(CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(c.name, color = colors.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("@${c.token}", color = colors.textMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/** Insert at the caret: the selection is replaced, the caret lands after the insert. */
internal fun TextFieldValue.insert(text: String): TextFieldValue {
    val start = minOf(selection.start, selection.end).coerceIn(0, this.text.length)
    val end = maxOf(selection.start, selection.end).coerceIn(0, this.text.length)
    val updated = this.text.substring(0, start) + text + this.text.substring(end)
    return TextFieldValue(updated, TextRange(start + text.length))
}

/** Delete the character before the caret (or the selection), like ⌫ on the emoji panel. */
internal fun TextFieldValue.backspace(): TextFieldValue {
    val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
    val end = maxOf(selection.start, selection.end).coerceIn(0, text.length)
    if (start != end) return TextFieldValue(text.substring(0, start) + text.substring(end), TextRange(start))
    if (start == 0) return this
    // An emoji is several code units: deleting them one by one would leave half a character.
    val prev = text.offsetByCodePoints(start, -1)
    return TextFieldValue(text.substring(0, prev) + text.substring(start), TextRange(prev))
}
