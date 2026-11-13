package com.alphamessenger.ui.chat.v2

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every emoji of the bundled Unicode list (res/raw/emoji_catalog.txt, built from the
 * official emoji-test.txt) that this phone's font can draw — a newer emoji than the
 * font knows would show as an empty box. Read once per process.
 */
private object EmojiCatalog {
    data class Category(val key: String, val icon: String, @StringRes val labelRes: Int, val emojis: List<String>)

    private val meta = mapOf(
        "smileys" to ("😀" to R.string.emoji_cat_smileys),
        "people" to ("👋" to R.string.emoji_cat_people),
        "hearts" to ("❤️" to R.string.emoji_cat_hearts),
        "animals" to ("🐶" to R.string.emoji_cat_animals),
        "food" to ("🍔" to R.string.emoji_cat_food),
        "activity" to ("⚽" to R.string.emoji_cat_activity),
        "travel" to ("✈️" to R.string.emoji_cat_travel),
        "objects" to ("💡" to R.string.emoji_cat_objects),
        "symbols" to ("➕" to R.string.emoji_cat_symbols),
        "flags" to ("🏁" to R.string.emoji_cat_flags),
    )

    @Volatile private var cached: List<Category>? = null

    fun peek(): List<Category>? = cached

    fun load(ctx: Context): List<Category> = cached ?: synchronized(this) {
        cached ?: read(ctx).also { cached = it }
    }

    private fun read(ctx: Context): List<Category> {
        val paint = android.graphics.Paint()
        return ctx.resources.openRawResource(R.raw.emoji_catalog).bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") && '\t' in it }.mapNotNull { line ->
                val key = line.substringBefore('\t')
                val (icon, label) = meta[key] ?: return@mapNotNull null
                val emojis = line.substringAfter('\t').split(' ').filter { it.isNotEmpty() && paint.hasGlyph(it) }
                Category(key, icon, label, emojis).takeIf { emojis.isNotEmpty() }
            }.toList()
        }
    }
}

/** One section of the grid — either "Recent" or a fixed category. */
private data class EmojiSection(val key: String, val icon: String, @StringRes val labelRes: Int, val emojis: List<String>)

// --- Recent-emoji persistence ---

private const val RECENTS_PREFS = "emoji_recents_v2"
private const val RECENTS_KEY = "recents"
private const val RECENTS_MAX = 32

private fun loadRecents(ctx: Context): List<String> =
    ctx.getSharedPreferences(RECENTS_PREFS, Context.MODE_PRIVATE)
        .getString(RECENTS_KEY, "")!!.split(' ').filter { it.isNotBlank() }

private fun saveRecent(ctx: Context, emoji: String) {
    val prefs = ctx.getSharedPreferences(RECENTS_PREFS, Context.MODE_PRIVATE)
    val updated = (listOf(emoji) + loadRecents(ctx)).distinct().take(RECENTS_MAX)
    prefs.edit().putString(RECENTS_KEY, updated.joinToString(" ")).apply()
}

/**
 * Emoji panel V2 — classic content. Sticky top tab bar + one
 * big scrolling grid with section headers. Scroll-to-switch (the active tab
 * follows the viewport). NO bottom bar (per user request).
 *
 * This is CONTENT ONLY — the height is owned by [BottomPanelV2] (the slot).
 * The emoji panel does NOT know about the keyboard; it just fills its slot.
 *
 * ## "Recent" (new)
 * The most recently used emojis appear as the FIRST section (🕐). Persisted in
 * SharedPreferences; the list is snapshotted once per panel open so the grid
 * never restructures mid-session (picks update storage, the UI updates on the
 * next open — usual behaviour).
 */
@Composable
fun EmojiPanelV2(
    modifier: Modifier = Modifier,
    colors: AlphaColorScheme,
    onPick: (String) -> Unit,
    onBackspace: () -> Unit,
    /** false → one plain vertical grid, no category tab row (reaction picker). */
    showTabs: Boolean = true,
    /** Emoji per row (fewer in a narrow window). */
    columns: Int = 8,
    /** The panel's own background; transparent on frosted glass. */
    background: Color? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Snapshot the recents ONCE per open (stable grid during the session).
    val recents = remember { loadRecents(ctx) }
    // Read off the main thread the first time: ~2000 glyph checks; recents show meanwhile.
    val catalog by produceState(EmojiCatalog.peek()) {
        if (value == null) value = withContext(Dispatchers.Default) { EmojiCatalog.load(ctx.applicationContext) }
    }
    val sections = remember(catalog) {
        buildList {
            if (recents.isNotEmpty()) add(EmojiSection("RECENT", "🕐", R.string.emoji_recents, recents))
            catalog?.forEach { add(EmojiSection(it.key, it.icon, it.labelRes, it.emojis)) }
        }
    }

    data class GridEntry(val section: EmojiSection, val isHeader: Boolean, val emoji: String?, val indexInSection: Int)
    val flat = remember(sections) {
        buildList {
            sections.forEach { s ->
                add(GridEntry(s, isHeader = true, emoji = null, indexInSection = -1))
                s.emojis.forEachIndexed { i, e -> add(GridEntry(s, false, e, i)) }
            }
        }
    }
    val sectionStartIndex = remember(sections) {
        var idx = 0
        sections.associate { s -> val start = idx; idx += 1 + s.emojis.size; s.key to start }
    }

    val gridState = rememberLazyGridState()
    val tabRowState = rememberLazyListState()

    val activeSection by remember(sections) {
        derivedStateOf {
            val firstVisible = gridState.firstVisibleItemIndex
            var current = sections.firstOrNull()
            sections.forEach { s -> if ((sectionStartIndex[s.key] ?: 0) <= firstVisible) current = s }
            current
        }
    }
    LaunchedEffect(activeSection) {
        if (!showTabs) return@LaunchedEffect
        val tabIdx = sections.indexOf(activeSection)
        if (tabIdx >= 0) tabRowState.animateScrollToItem(tabIdx)
    }

    val pick: (String) -> Unit = { e ->
        saveRecent(ctx, e)
        onPick(e)
    }

    Column(modifier = modifier.fillMaxWidth().background(background ?: colors.bgSecondary)) {
        // --- Sticky top tab bar ---
        if (showTabs) {
        LazyRow(
            state = tabRowState,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(sections, key = { it.key }) { s ->
                val active = s == activeSection
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(if (active) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable {
                            val target = sectionStartIndex[s.key] ?: 0
                            scope.launch { gridState.animateScrollToItem(target) }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(s.icon, fontSize = 20.sp, textAlign = TextAlign.Center) }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.alphaBorder))
        }

        // --- One big grid: headers + emojis (fills the slot) ---
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp),
        ) {
            flat.forEach { entry ->
                if (entry.isHeader) {
                    item(span = { GridItemSpan(columns) }, key = "header-${entry.section.key}") {
                        Text(
                            stringResource(entry.section.labelRes), color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
                        )
                    }
                } else {
                    item(key = "${entry.section.key}-${entry.indexInSection}") {
                        Box(
                            modifier = Modifier.padding(2.dp).clip(RoundedCornerShape(8.dp))
                                .clickable { pick(entry.emoji!!) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(entry.emoji!!, fontSize = 24.sp, textAlign = TextAlign.Center) }
                    }
                }
            }
        }
    }
}
