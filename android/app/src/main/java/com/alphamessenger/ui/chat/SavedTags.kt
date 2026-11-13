package com.alphamessenger.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * Tags of Saved Messages: a reaction put on a saved message is its tag. Each
 * emoji may carry a name ("Work", "Ideas"); names stay on this phone.
 */
object SavedTags {
    private const val PREFS = "saved_tags"
    val names = mutableStateMapOf<String, String>()
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) -> (v as? String)?.let { names[k] = it } }
    }

    fun rename(ctx: Context, emoji: String, name: String) {
        val n = name.trim().take(20)
        if (n.isEmpty()) names.remove(emoji) else names[emoji] = n
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (n.isEmpty()) remove(emoji) else putString(emoji, n)
        }.apply()
    }
}

/** The tags in use, most used first, with how many messages carry each. */
fun savedTagCounts(messages: List<MessageUi>): List<Pair<String, Int>> =
    messages.flatMap { it.myReactions }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key to it.value }

/** "All" and each tag; a tap shows only that tag, a long press names it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SavedTagBar(
    tags: List<Pair<String, Int>>,
    selected: String?,
    colors: AlphaColorScheme,
    onSelect: (String?) -> Unit,
) {
    val ctx = LocalContext.current
    SavedTags.load(ctx)
    var naming by remember { mutableStateOf<String?>(null) }
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            TagChip(stringResource(R.string.saved_tags_all), null, selected == null, colors, onClick = { onSelect(null) }, onLongClick = null)
        }
        items(tags, key = { it.first }) { (emoji, count) ->
            TagChip(
                emoji + (SavedTags.names[emoji]?.let { " $it" } ?: ""), count, selected == emoji, colors,
                onClick = { onSelect(if (selected == emoji) null else emoji) },
                onLongClick = { naming = emoji },
            )
        }
    }
    naming?.let { emoji ->
        var text by remember(emoji) { mutableStateOf(SavedTags.names[emoji].orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.saved_tag_name_title, emoji), color = colors.textPrimary) },
            text = {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.bgTertiary).padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (text.isEmpty()) Text(stringResource(R.string.saved_tag_name_hint), color = colors.textMuted, fontSize = 16.sp)
                    BasicTextField(
                        value = text, onValueChange = { text = it.take(20) }, singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { SavedTags.rename(ctx, emoji, text); naming = null }) {
                    Text(stringResource(R.string.common_save), color = colors.accent)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { naming = null }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TagChip(label: String, count: Int?, on: Boolean, colors: AlphaColorScheme, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) colors.accent else colors.bgTertiary.copy(alpha = 0.92f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (on) colors.bgPrimary else colors.textPrimary, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text("$count", color = if (on) colors.bgPrimary.copy(alpha = 0.8f) else colors.textMuted, fontSize = 12.sp)
        }
    }
}
