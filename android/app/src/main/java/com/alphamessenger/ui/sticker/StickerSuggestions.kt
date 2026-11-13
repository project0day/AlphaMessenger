package com.alphamessenger.ui.sticker

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How many stickers an emoji brings up at most. */
private const val MAX_SUGGESTIONS = 24

/** [text] when it is one emoji and nothing else (spaces aside), compared without variation selectors. */
internal fun loneEmoji(text: String): String? {
    val t = text.trim()
    if (t.isEmpty() || t.length > 32) return null
    val it = android.icu.text.BreakIterator.getCharacterInstance()
    it.setText(t)
    if (it.next() != t.length) return null
    val cp = t.codePointAt(0)
    val emoji = Character.getType(cp) == Character.OTHER_SYMBOL.toInt() ||
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF
    return if (emoji) bare(t) else null
}

private fun bare(e: String) = e.replace(VARIATION_SELECTOR, "")

/** U+FE0F: the same emoji is typed with and without it. */
private val VARIATION_SELECTOR = Char(0xFE0F).toString()

/** Stickers tagged with [want]: recently used first, then in pack order. */
internal fun stickersFor(ctx: Context, want: String): List<Pair<String, Sticker>> {
    val seen = HashSet<String>()
    fun Sticker.fits() = bare(emoji).contains(want)
    return (StickerStore.recent(ctx).filter { it.second.fits() } +
        StickerStore.packs(ctx).flatMap { p -> p.stickers.filter { it.fits() }.map { p.id to it } })
        .filter { seen.add(File(it.second.file).name + it.first) }
        .take(MAX_SUGGESTIONS)
}

/**
 * A row of the stickers that match the one emoji typed, inside the field above the
 * text; a tap sends one in its place. Nothing shows for anything but a lone emoji.
 */
@Composable
fun StickerSuggestions(text: String, onSend: (packId: String, sticker: Sticker) -> Unit) {
    val emoji = remember(text) { loneEmoji(text) } ?: return
    val ctx = LocalContext.current
    val version by StickerStore.version.collectAsState()
    val found by produceState(emptyList<Pair<String, Sticker>>(), emoji, version) {
        value = withContext(Dispatchers.IO) { stickersFor(ctx, emoji) }
    }
    if (found.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(found, key = { (pid, s) -> pid + "/" + File(s.file).name }) { (pid, s) ->
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onSend(pid, s) },
                contentAlignment = Alignment.Center,
            ) { StickerImage(s.file, Modifier.size(56.dp), 192, s.emoji) }
        }
    }
}
