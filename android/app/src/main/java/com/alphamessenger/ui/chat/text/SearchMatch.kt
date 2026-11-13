package com.alphamessenger.ui.chat.text

/**
 * How a search query meets text — the same rule the core's index follows: every
 * word of the query must start a word of the text, in any case.
 */
object SearchMatch {

    /** The start of [day] on this device, as strict RFC 3339 (seconds always written). */
    fun dayStart(day: java.time.LocalDate): String =
        day.atStartOfDay(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    fun words(query: String): List<String> =
        query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Ranges of [text] that start a word with one of the query's words. */
    fun hits(text: String, query: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (w in words(query)) {
            var i = text.indexOf(w, ignoreCase = true)
            while (i >= 0) {
                if (i == 0 || !text[i - 1].isLetterOrDigit()) out += i until i + w.length
                i = text.indexOf(w, i + w.length, ignoreCase = true)
            }
        }
        return out.sortedBy { it.first }
    }

    fun matches(text: String, query: String): Boolean {
        val ws = words(query)
        return ws.isNotEmpty() && ws.all { w -> hits(text, w).isNotEmpty() }
    }

    /** The text as a reader sees it: no markers, no forward mark, one line. */
    fun visible(content: String): String =
        Markup.plain(content.removePrefix(com.alphamessenger.ui.chat.FWD_MARK)).replace('\n', ' ').trim()

    /** A piece of [text] around its first match, short enough for one or two rows. */
    fun snippet(text: String, query: String, max: Int = 120): String {
        val first = hits(text, query).firstOrNull()?.first ?: 0
        if (text.length <= max || first < 30) return text
        var start = first - 30
        val space = text.lastIndexOf(' ', start)
        if (space >= 0 && start - space < 15) start = space + 1
        return "…" + text.substring(start)
    }
}
