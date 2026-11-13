package com.alphamessenger.ui.chat.text

/**
 * Inline markup carried inside the message text itself:
 *
 *   **bold**   __italic__   ++underline++   `mono`   ~~strike~~
 *   ```lang
 *   a code block
 *   ```
 *
 * The markers travel as plain characters, so an older client simply shows
 * them. [parse] removes them and returns the visible text with style ranges
 * over it; links, hashtags and mentions are then found in the visible text.
 * [blocks] splits a message into prose and code blocks for the bubble.
 * Pure Kotlin — no Android/Compose dependencies.
 */
object Markup {

    data class Span(val start: Int, val end: Int, val kind: MarkdownKind)

    data class Parsed(val text: String, val spans: List<Span>)

    /** A piece of a message: prose (still with its inline markers) or a code block. */
    sealed interface Block {
        data class Prose(val raw: String) : Block
        data class Code(val lang: String, val code: String) : Block
    }

    // A fence opens with ```; a word alone on that line names the language.
    private val FENCE = Regex("""```(?:([A-Za-z0-9_+#.-]+)[ \t]*\n|\n)?([\s\S]*?)\n?```""")

    // Code first: its content is taken literally. Content never starts or ends
    // with a space, so "2 ** 3 ** 4" stays as typed; a styled part may span lines.
    private val RE = Regex(
        FENCE.pattern +
            """|`([^`]+)`""" +
            """|\*\*(\S(?:[\s\S]*?\S)?)\*\*""" +
            """|__(\S(?:[\s\S]*?\S)?)__""" +
            """|~~(\S(?:[\s\S]*?\S)?)~~""" +
            // "++" only at word edges: "C++/C++" or "i++ + j++" stay as written.
            """|(?<![\p{L}\p{N}+])\+\+(\S(?:[\s\S]*?\S)?)\+\+(?![\p{L}\p{N}+])"""
    )

    // Group 1 is the fence's language; groups 2..7 are the contents.
    private val KINDS = arrayOf(
        MarkdownKind.CODE, MarkdownKind.CODE, MarkdownKind.BOLD,
        MarkdownKind.ITALIC, MarkdownKind.STRIKETHROUGH, MarkdownKind.UNDERLINE,
    )

    fun parse(raw: String): Parsed {
        if (raw.indexOfAny(charArrayOf('*', '_', '`', '~', '+')) < 0) return Parsed(raw, emptyList())
        val out = StringBuilder(raw.length)
        val spans = ArrayList<Span>()
        var last = 0
        for (m in RE.findAll(raw)) {
            val g = (2..7).first { m.groups[it] != null }
            val inner = m.groups[g]!!.value
            out.append(raw, last, m.range.first)
            val s = out.length
            if (g <= 3) {
                out.append(inner)
            } else {
                // Markup inside bold/italic/strike (e.g. **bold `code`**) nests one level.
                val nested = parse(inner)
                nested.spans.forEach { spans += Span(it.start + s, it.end + s, it.kind) }
                out.append(nested.text)
            }
            if (out.length > s) spans += Span(s, out.length, KINDS[g - 2])
            last = m.range.last + 1
        }
        out.append(raw, last, raw.length)
        return Parsed(out.toString(), spans)
    }

    /** True when the text holds a code block (the bubble then lays it out in parts). */
    fun hasBlock(raw: String): Boolean = raw.contains("```") && FENCE.containsMatchIn(raw)

    fun blocks(raw: String): List<Block> {
        val out = ArrayList<Block>()
        var last = 0
        for (m in FENCE.findAll(raw)) {
            val code = m.groupValues[2]
            if (code.isBlank()) continue
            raw.substring(last, m.range.first).trim('\n').takeIf { it.isNotBlank() }?.let { out += Block.Prose(it) }
            out += Block.Code(m.groupValues[1], code)
            last = m.range.last + 1
        }
        raw.substring(last).trim('\n').takeIf { it.isNotBlank() }?.let { out += Block.Prose(it) }
        return out
    }

    /** Text as it reads, without the markers (previews, notifications, quotes). */
    fun plain(raw: String): String = parse(raw).text

    /** Markers for each inline kind, used by the composer's formatting menu. */
    fun marker(kind: MarkdownKind): String = when (kind) {
        MarkdownKind.BOLD -> "**"
        MarkdownKind.ITALIC -> "__"
        MarkdownKind.CODE -> "`"
        MarkdownKind.STRIKETHROUGH -> "~~"
        MarkdownKind.UNDERLINE -> "++"
        MarkdownKind.CODE_BLOCK -> "```"
    }
}
