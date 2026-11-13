package com.alphamessenger.ui.chat.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.icons.AlphaIcons

/**
 * Copies a code block's text. The chat screen puts its own here, which keeps a
 * protected chat's rule (no copying) and confirms with a toast.
 */
val LocalCodeCopy = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Boolean)?> { null }

// One dark panel on every bubble and theme: the token colours are made for it.
private val PANEL = Color(0xFF0E0E16)
private val HEADER = Color(0xFF17172A)
private val EDGE = Color(0xFF262640)
private val PLAIN = Color(0xFFD6D6E2)
private val LABEL = Color(0xFF9A9AB0)
private val KEYWORD = Color(0xFFC792EA)
private val STRING = Color(0xFFC3E88D)
private val NUMBER = Color(0xFFF78C6C)
private val COMMENT = Color(0xFF7A7A92)
private val FUNCTION = Color(0xFF82AAFF)
private val TYPE = Color(0xFFFFCB6B)

/** A code block inside a message: language, a copy button, highlighted code that scrolls sideways. */
@Composable
fun CodeBlockView(
    lang: String,
    code: String,
    accent: Color,
    fontSize: TextUnit,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val screenCopy = LocalCodeCopy.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(2000); copied = false }
    }
    val highlighted = remember(code, lang) { highlight(code, lang) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PANEL)
            .border(1.dp, EDGE, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(HEADER).padding(start = 12.dp, end = 4.dp).height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                lang.ifBlank { stringResource(R.string.code_label) }.lowercase(),
                color = LABEL, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp,
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (copied) accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable {
                        val done = screenCopy?.invoke(code) ?: run {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                            true
                        }
                        if (done) copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (copied) AlphaIcons.Filled.Check else AlphaIcons.Outlined.ContentCopy,
                    contentDescription = null, tint = accent, modifier = Modifier.size(15.dp),
                )
                Text(
                    stringResource(if (copied) R.string.code_copied else R.string.code_copy),
                    color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        val style = TextStyle(color = PLAIN, fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * 1.5f)
        val body: @Composable () -> Unit = {
            Text(highlighted, style = style, softWrap = false, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp))
        }
        Row(Modifier.fillMaxWidth().sidewaysDragIsNotTap().horizontalScroll(rememberScrollState())) {
            if (selectionMode) SelectionContainer { body() } else body()
        }
    }
}

private val KEYWORDS = setOf(
    "abstract", "and", "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
    "crate", "data", "def", "default", "defer", "del", "do", "elif", "else", "enum", "except", "export",
    "extends", "extern", "false", "final", "finally", "fn", "for", "from", "fun", "func", "function",
    "go", "if", "impl", "implements", "import", "in", "init", "interface", "internal", "is", "lambda",
    "let", "loop", "match", "mod", "module", "mut", "new", "nil", "None", "not", "null", "object", "open",
    "or", "override", "package", "pass", "private", "protected", "pub", "public", "raise", "return",
    "sealed", "select", "self", "static", "struct", "super", "suspend", "switch", "this", "throw",
    "throws", "trait", "true", "True", "False", "try", "type", "typeof", "undefined", "use", "val",
    "var", "void", "when", "where", "while", "with", "yield", "SELECT", "FROM", "WHERE", "INSERT",
    "UPDATE", "DELETE", "INTO", "VALUES", "JOIN", "ON", "AND", "OR", "NOT", "NULL", "CREATE", "TABLE",
    "ORDER", "BY", "GROUP", "LIMIT", "echo", "then", "fi", "done", "esac",
)

// Languages where "#" starts a comment.
private val HASH_COMMENTS = setOf(
    "python", "py", "bash", "sh", "shell", "zsh", "ruby", "rb", "yaml", "yml", "toml", "r", "perl",
    "powershell", "ps1", "dockerfile", "makefile", "conf", "ini", "nix",
)

private val TOKEN = Regex(
    """(/\*[\s\S]*?\*/|//[^\n]*)""" +
        """|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)""" +
        """|(\b0[xX][0-9a-fA-F_]+\b|\b\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?[fFLuU]?\b)""" +
        """|([A-Za-z_][A-Za-z0-9_]*)""" +
        """|(#[^\n]*)"""
)

/** Light, language-agnostic highlighting: comments, strings, numbers, keywords, calls, types. */
internal fun highlight(code: String, lang: String): AnnotatedString = buildAnnotatedString {
    val hash = lang.lowercase() in HASH_COMMENTS
    var last = 0
    for (m in TOKEN.findAll(code)) {
        val g = m.groups
        val style: SpanStyle? = when {
            g[1] != null -> SpanStyle(color = COMMENT, fontStyle = FontStyle.Italic)
            g[2] != null -> SpanStyle(color = STRING)
            g[3] != null -> SpanStyle(color = NUMBER)
            g[4] != null -> {
                val w = m.value
                val next = code.indexOfFirst(m.range.last + 1) { !it.isWhitespace() }
                when {
                    w in KEYWORDS -> SpanStyle(color = KEYWORD)
                    next >= 0 && code[next] == '(' -> SpanStyle(color = FUNCTION)
                    w[0].isUpperCase() -> SpanStyle(color = TYPE)
                    else -> null
                }
            }
            g[5] != null && hash -> SpanStyle(color = COMMENT, fontStyle = FontStyle.Italic)
            else -> null
        }
        if (style == null) continue
        append(code, last, m.range.first)
        pushStyle(style); append(m.value); pop()
        last = m.range.last + 1
    }
    append(code, last, code.length)
}

private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (i in from until length) if (predicate(this[i])) return i
    return -1
}

/**
 * A sideways drag over the code is the code's own, even where it cannot scroll
 * further (at an edge): it never reaches the bubble as a tap. Vertical drags are
 * left to the list.
 */
private fun Modifier.sidewaysDragIsNotTap(): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // The direction is settled once, when the finger first leaves the slop: a
        // vertical drag then stays the list's for good, however it wavers later.
        var sideways: Boolean? = null
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (sideways == null) {
                val d = change.position - down.position
                if (d.getDistance() > slop) sideways = kotlin.math.abs(d.x) > kotlin.math.abs(d.y)
                if (sideways == false) break
            }
            if (sideways == true) event.changes.forEach { it.consume() }
            if (!change.pressed) break
        }
    }
}
