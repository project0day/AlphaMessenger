package com.alphamessenger.ui.stories

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import com.alphamessenger.ui.components.avatarBackdrop
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.chat.avatarGradientFor
import com.alphamessenger.ui.theme.AlphaColorScheme

/** The "Stories" tab: my story on top, then contacts' — new ones first. */
@Composable
fun StoriesTab(
    modifier: Modifier,
    viewModel: StoriesViewModel,
    colors: AlphaColorScheme,
    onOpen: (key: String) -> Unit,
    onCreate: () -> Unit,
) {
    val st by viewModel.state.collectAsState()
    val fresh = st.authors.filter { it.unseen }
    val seen = st.authors.filter { !it.unseen }
    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = com.alphamessenger.ui.chatlist.LocalListTopInset.current, bottom = com.alphamessenger.ui.chatlist.LocalDockInset.current)) {
        item {
            val mine = st.mine
            StoryRow(
                name = stringResource(R.string.stories_my),
                subtitle = if (mine.isEmpty()) stringResource(R.string.stories_add_hint)
                else pluralStringResource(R.plurals.stories_count, mine.size, mine.size) + " · " +
                    pluralStringResource(R.plurals.stories_views, mine.flatMap { it.viewers }.distinctBy { it.sid }.size,
                        mine.flatMap { it.viewers }.distinctBy { it.sid }.size),
                avatarPath = st.myAvatar, seed = "me", initial = st.myName.takeIf { it.isNotBlank() }?.let { com.alphamessenger.ui.components.avatarInitial(it) } ?: stringResource(R.string.self_avatar_initial),
                ring = if (mine.isEmpty()) null else StoryRing(mine.size, false),
                colors = colors,
                onClick = { if (mine.isEmpty()) onCreate() else onOpen(KEY_MINE) },
                trailing = {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)).clickable(onClick = onCreate),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AlphaIcons.Outlined.Add, stringResource(R.string.stories_new), tint = colors.accent, modifier = Modifier.size(22.dp)) }
                },
            )
        }
        if (fresh.isNotEmpty()) {
            item { Section(stringResource(R.string.stories_section_new), colors) }
            items(fresh, key = { "n" + it.chatId }) { a -> AuthorRow(a, colors) { onOpen(a.chatId) } }
        }
        if (seen.isNotEmpty()) {
            item { Section(stringResource(R.string.stories_section_seen), colors) }
            items(seen, key = { "s" + it.chatId }) { a -> AuthorRow(a, colors) { onOpen(a.chatId) } }
        }
        if (st.authors.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(AlphaIcons.Outlined.AutoStories, null, tint = colors.textMuted, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.stories_empty), color = colors.textMuted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                }
            }
        }
    }
}

const val KEY_MINE = "mine"

@Composable
private fun Section(text: String, colors: AlphaColorScheme) {
    Text(
        text.uppercase(), color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun AuthorRow(a: StoryAuthor, colors: AlphaColorScheme, onClick: () -> Unit) {
    val last = a.stories.maxOf { it.createdAt }
    StoryRow(
        name = a.name.ifBlank { stringResource(R.string.chat_member_fallback) },
        subtitle = pluralStringResource(R.plurals.stories_count, a.stories.size, a.stories.size) + " · " + agoLabel(last),
        avatarPath = a.avatarPath, seed = a.chatId, initial = com.alphamessenger.ui.components.avatarInitial(a.name),
        ring = StoryRing(a.stories.size, a.unseen), colors = colors, onClick = onClick,
    )
}

@Composable
private fun StoryRow(
    name: String,
    subtitle: String,
    avatarPath: String,
    seed: String,
    initial: String,
    ring: StoryRing?,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoryHalo(ring, 62.dp) { StoryAvatar(avatarPath, seed, initial, 52) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = colors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun StoryAvatar(path: String, seed: String, initial: String, sizeDp: Int) {
    val g = remember(seed) { avatarGradientFor(seed) }
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).avatarBackdrop(Brush.linearGradient(listOf(g.first, g.second))),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = Color.White, fontSize = (sizeDp * 0.38f).sp, fontWeight = FontWeight.SemiBold)
        if (path.isNotBlank()) {
            AsyncImage(model = java.io.File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/** "5 min ago" / "2 h ago" from unix seconds. */
@Composable
fun agoLabel(at: Long): String {
    val mins = ((System.currentTimeMillis() / 1000 - at) / 60).coerceAtLeast(0)
    return when {
        mins < 1 -> stringResource(R.string.stories_just_now)
        mins < 60 -> stringResource(R.string.stories_min_ago, mins)
        else -> stringResource(R.string.stories_h_ago, mins / 60)
    }
}
