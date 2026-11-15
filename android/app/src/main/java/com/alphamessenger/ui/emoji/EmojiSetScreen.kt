package com.alphamessenger.ui.emoji

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.feed.FeedPost
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The set's groups in the order the emoji keyboard has them. */
private val GROUPS = listOf(
    "smileys" to R.string.emoji_group_smileys,
    "people" to R.string.emoji_group_people,
    "nature" to R.string.emoji_group_nature,
    "food" to R.string.emoji_group_food,
    "activities" to R.string.emoji_group_activities,
    "travel" to R.string.emoji_group_travel,
    "objects" to R.string.emoji_group_objects,
    "symbols" to R.string.emoji_group_symbols,
    "flags" to R.string.emoji_group_flags,
)

/** The set as this phone has it, loaded off the main thread (empty until downloaded). */
@Composable
private fun rememberSet(): List<AnimatedEmoji.Entry> {
    val ctx = LocalContext.current
    val ready by AnimatedEmoji.ready.collectAsState()
    return produceState(emptyList<AnimatedEmoji.Entry>(), ready) {
        value = withContext(Dispatchers.IO) { AnimatedEmoji.index(ctx) }
    }.value
}

/**
 * The animated emoji post in the feed. Nothing comes unasked: "Download" fetches the
 * set, "Update" a newer one; once here, a few stand in the card and "Open" shows all.
 */
@Composable
fun EmojiSetCard(post: FeedPost, colors: AlphaColorScheme, repo: AlphaCoreRepository, modifier: Modifier, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val set = rememberSet()
    val ready by AnimatedEmoji.ready.collectAsState()
    val hasThis = remember(ready, set, post.set) { post.set?.let { AnimatedEmoji.has(ctx, it) } ?: true }
    var fetching by remember { mutableStateOf(false) }
    // Another set is on the phone but not this one: one tap adds it to them.
    val behind = set.isNotEmpty() && !hasThis
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.bgSecondary).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(post.title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        if (post.body.isNotBlank()) Text(post.body, color = colors.textSecondary, fontSize = 14.sp)
        if (set.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(set.take(PREVIEW), key = { it.code }) { e ->
                AnimatedEmojiView(e.emoji, 44.dp, Modifier.size(44.dp), plays = 1, autoPlay = false)
            }
        }
        val label = when {
            fetching -> stringResource(R.string.emoji_set_loading)
            set.isEmpty() -> stringResource(R.string.emoji_set_download)
            behind -> stringResource(R.string.emoji_set_update)
            else -> pluralStringResource(R.plurals.emoji_set_open, set.size, set.size)
        }
        Row(
            Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(colors.accent)
                .clickable(enabled = !fetching) {
                    if (set.isNotEmpty() && !behind) onOpen()
                    else {
                        fetching = true
                        scope.launch { AnimatedEmoji.ensure(ctx.applicationContext, repo, force = true); fetching = false }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (fetching) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (set.isEmpty() && !fetching) Text(stringResource(R.string.emoji_set_download_hint), color = colors.textMuted, fontSize = 12.sp)
    }
}

/** The whole set by groups; each emoji stands still until tapped, then plays; a hold keeps it as a sticker. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EmojiSetScreen(colors: AlphaColorScheme, repo: AlphaCoreRepository, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val set = rememberSet()
    val groups = remember(set) { GROUPS.filter { (g, _) -> set.any { it.group == g } } }
    var group by remember { mutableStateOf("smileys") }
    val shown = remember(set, group) { set.filter { it.group == group } }
    Column(Modifier.fillMaxSize().background(colors.bgPrimary).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = colors.textPrimary) }
            Text(stringResource(R.string.emoji_set_title), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(groups, key = { it.first }) { (g, label) ->
                val on = g == group
                Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) colors.accent else colors.bgTertiary)
                        .clickable { group = g }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(label), color = if (on) Color.White else colors.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
            }
        }
        if (set.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(68.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(shown, key = { it.code }) { e ->
                var plays by remember(e.code) { mutableIntStateOf(0) }
                Box(
                    // Tap: plays. Hold: kept among my stickers.
                    Modifier.size(68.dp).combinedClickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = { plays++ },
                        onLongClick = {
                            scope.launch {
                                val ok = com.alphamessenger.ui.sticker.StickerStore.saveAnimatedEmoji(ctx, e.emoji, ctx.getString(R.string.sticker_saved_pack))
                                android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.sticker_saved else R.string.chat_file_prepare_failed), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedEmojiView(e.emoji, 56.dp, Modifier.size(56.dp), plays = 1, replay = plays, autoPlay = false)
                }
            }
        }
    }
}

/** How many play in the feed card. */
private const val PREVIEW = 12

/** [EmojiSetScreen] in its own full-screen window (opened from settings). */
@Composable
fun EmojiSetDialog(colors: AlphaColorScheme, repo: AlphaCoreRepository, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
    ) {
        // Edge to edge like every other screen: its own background under the status and navigation bars.
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = colors.bgPrimary.luminance() >= 0.5f)
        EmojiSetScreen(colors, repo, onDismiss)
    }
}
