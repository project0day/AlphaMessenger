package com.alphamessenger.ui.components

/**
 * The single source of the fallback avatar letter.
 *
 * Takes the first GRAPHEME CLUSTER, not the first char: `first()`/`take(1)`
 * cut composed characters (emoji, letters with diacritics) in half and
 * produce garbage instead of a letter.
 */
fun avatarInitial(name: String?): String {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return "?"
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(trimmed)
    val end = it.next()
    val cluster = if (end == java.text.BreakIterator.DONE) trimmed else trimmed.substring(0, end)
    return cluster.uppercase()
}
