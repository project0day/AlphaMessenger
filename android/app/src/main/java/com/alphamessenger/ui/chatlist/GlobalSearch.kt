package com.alphamessenger.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.chat.text.SearchMatch
import com.alphamessenger.ui.theme.AlphaColorScheme
import uniffi.alphamessenger.ChatInfo

/** [text] with the query's words marked the way the search found them. */
internal fun searchMarked(text: String, query: String, colors: AlphaColorScheme): AnnotatedString = buildAnnotatedString {
    append(text)
    val mark = SpanStyle(background = colors.accent.copy(alpha = 0.28f), color = colors.textPrimary)
    for (r in SearchMatch.hits(text, query)) addStyle(mark, r.first, r.last + 1)
}

/**
 * The search over everything: chats and channels by name, then the messages that
 * hold the words — across every tab, not only the one the search was opened from.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun GlobalSearchResults(
    query: String,
    kind: SearchKind,
    onKind: (SearchKind) -> Unit,
    from: SearchPerson?,
    onFrom: (SearchPerson?) -> Unit,
    people: () -> List<SearchPerson>,
    days: Pair<java.time.LocalDate, java.time.LocalDate>?,
    onDays: (Pair<java.time.LocalDate, java.time.LocalDate>?) -> Unit,
    chats: List<ChatInfo>,
    channelIds: Set<String>,
    found: List<FoundMessage>,
    loading: Boolean,
    end: Boolean,
    peers: Map<String, ChatPeerSnapshot>,
    colors: AlphaColorScheme,
    topInset: Dp,
    bottomInset: Dp,
    chatRow: @Composable (ChatInfo) -> Unit,
    onOpen: (FoundMessage) -> Unit,
    onLoadMore: () -> Unit,
    /** Fetches a picture for a tile. */
    mediaLoader: suspend (String) -> String?,
    onQuery: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SearchHistory.load(ctx)
    val byId = remember(chats) { chats.associateBy { it.id } }
    val savedName = stringResource(R.string.menu_saved)
    val groupName = stringResource(R.string.group_chat)
    val privateName = stringResource(R.string.private_chat)
    fun nameOf(c: ChatInfo?): String = when {
        c == null -> ""
        c.id == "saved_messages_local" -> savedName
        peers[c.id]?.displayName?.isNotBlank() == true -> peers[c.id]!!.displayName
        c.name?.isNotBlank() == true -> c.name!!
        c.chatType == "group" -> groupName
        else -> privateName
    }
    fun keep(chatId: String): Boolean = when (kind) {
        SearchKind.CHATS -> chatId !in channelIds
        SearchKind.CHANNELS -> chatId in channelIds
        else -> true
    }
    // Chats by name; with no query yet, the recent ones of that kind.
    // An author or a period narrows messages only: chats by name step aside.
    val named = remember(query, chats, peers, kind, from, days) {
        when {
            kind.core != null || from != null || days != null -> emptyList()
            query.isBlank() -> chats.filter { keep(it.id) && it.archivedAt == 0uL }
                .sortedByDescending { it.lastMessageAt.ifBlank { it.createdAt } }.take(30)
            else -> chats.filter { keep(it.id) && SearchMatch.matches(nameOf(it), query) }
        }
    }
    val messages = remember(found, kind) { found.filter { keep(it.chatId) } }
    val labels = mapOf(
        "media" to stringResource(R.string.search_item_media),
        "voice" to stringResource(R.string.search_item_voice),
        "link" to stringResource(R.string.search_item_link),
        "file" to stringResource(R.string.search_item_file),
        "doc" to stringResource(R.string.search_item_file),
        "app" to stringResource(R.string.search_item_file),
        "archive" to stringResource(R.string.search_item_file),
        "music" to stringResource(R.string.search_item_music),
        "round" to stringResource(R.string.search_item_round),
        "poll" to stringResource(R.string.search_item_poll),
    )
    var pickFrom by remember { mutableStateOf(false) }
    var pickDays by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, messages.size, end) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
        }.collect { near -> if (near && !end) onLoadMore() }
    }
    val you = stringResource(R.string.you_prefix)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(colors.bgPrimary),
        contentPadding = PaddingValues(top = topInset, bottom = bottomInset + 16.dp),
    ) {
        item(key = "filters") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchKind.entries.forEach { f ->
                    val on = f == kind
                    Text(
                        stringResource(f.label),
                        color = if (on) colors.bgPrimary else colors.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) colors.accent else colors.bgTertiary)
                            .clickable { onKind(f) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
        item(key = "narrow") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NarrowPill(
                    icon = com.alphamessenger.ui.icons.AlphaIcons.Outlined.Person,
                    label = from?.name ?: stringResource(R.string.search_from),
                    on = from != null,
                    colors = colors,
                    onClick = { pickFrom = true },
                    onClear = { onFrom(null) },
                )
                NarrowPill(
                    icon = com.alphamessenger.ui.icons.AlphaIcons.Outlined.CalendarMonth,
                    label = days?.let { periodLabel(it) } ?: stringResource(R.string.search_date),
                    on = days != null,
                    colors = colors,
                    onClick = { pickDays = true },
                    onClear = { onDays(null) },
                )
            }
        }
        // Nothing asked yet: where they were lately and what they looked for.
        val idle = query.isBlank() && kind == SearchKind.ALL && from == null && days == null
        if (idle) {
            val recent = SearchHistory.chats.mapNotNull { byId[it] }
            if (recent.isNotEmpty()) {
                item(key = "h_recent") { SectionTitle(stringResource(R.string.search_recent_chats), stringResource(R.string.search_clear), colors) { SearchHistory.clear(ctx) } }
                item(key = "recent_chats") {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(recent, key = { "r_" + it.id }) { c ->
                            Column(
                                Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpenChat(c.id) }.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val title = nameOf(c)
                                com.alphamessenger.ui.stories.StoryAvatar(
                                    peers[c.id]?.avatarPath?.takeIf { it.isNotBlank() } ?: c.avatarPath, c.id,
                                    com.alphamessenger.ui.components.avatarInitial(title), 54,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(title, color = colors.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (SearchHistory.queries.isNotEmpty()) {
                item(key = "h_queries") { SectionTitle(stringResource(R.string.search_recent_queries), null, colors) }
                items(SearchHistory.queries, key = { "q_" + it }) { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onQuery(q) }.padding(start = 20.dp, end = 8.dp).height(46.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(com.alphamessenger.ui.icons.AlphaIcons.Outlined.History, null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(q, color = colors.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable { SearchHistory.removeQuery(ctx, q) }, contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(com.alphamessenger.ui.icons.AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = colors.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        if (named.isNotEmpty()) {
            item(key = "h_chats") { SectionTitle(stringResource(R.string.search_section_chats), null, colors) }
            items(named, key = { "c_" + it.id }) { chatRow(it) }
        }
        if (messages.isNotEmpty() || loading) {
            item(key = "h_messages") {
                SectionTitle(
                    stringResource(R.string.search_section_messages),
                    if (messages.isNotEmpty()) stringResource(R.string.search_found_count, messages.size) + if (end) "" else "+" else null,
                    colors,
                )
            }
        }
        if (kind == SearchKind.MEDIA) {
            // Photos and videos as a grid of their pictures, month by month; an album's
            // parts each take a tile.
            val month = java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy")
            val byMonth = messages.mapNotNull { m -> m.ui?.let { m to it } }
                .flatMap { (m, u) -> (if (u.mediaType == "album") u.albumItems.orEmpty() else listOf(u)).map { m to it } }
                .groupBy { (m, _) -> runCatching { java.time.OffsetDateTime.parse(m.createdAt).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(month) }.getOrDefault("") }
            byMonth.forEach { (title, tiles) ->
                item(key = "mm_$title") {
                    Text(
                        title.replaceFirstChar { it.titlecase() }, color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
                items(tiles.chunked(3), key = { row -> "mr_" + row.first().second.id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEach { (m, tile) ->
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(bottom = 2.dp)) {
                                com.alphamessenger.ui.contacts.MediaThumb(
                                    tile, mediaLoader, selectMode = false, selected = false,
                                    onTap = { onOpen(m) }, onLongPress = { onOpen(m) },
                                )
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else items(messages, key = { "m_" + it.id }) { m ->
            val chat = byId[m.chatId]
            val link = m.ui?.takeIf { m.kind == "link" }
            if (link != null) {
                // A link reads as its site's card; the chat and the day under it.
                Column {
                    com.alphamessenger.ui.contacts.LinkRow(link, colors, onStartSelect = { onOpen(m) })
                    Text(
                        nameOf(chat) + " · " + listTime(m.createdAt), color = colors.textMuted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpen(m) }.padding(start = 78.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                return@items
            }
            val title = nameOf(chat)
            val isGroupChat = chat?.chatType == "group" && m.chatId !in channelIds
            val prefix = when {
                m.outgoing -> you
                isGroupChat && m.sender.isNotBlank() -> m.sender + ": "
                else -> ""
            }
            FoundRow(
                chatId = m.chatId,
                avatarPath = peers[m.chatId]?.avatarPath?.takeIf { it.isNotBlank() } ?: chat?.avatarPath.orEmpty(),
                title = title,
                time = listTime(m.createdAt),
                prefix = prefix,
                // A file or a track reads by its name; a bare attachment by what it is.
                text = (KIND_MARK[m.kind] ?: "") + when {
                    m.kind in NAMED_KINDS && m.name.isNotBlank() ->
                        m.name + if (m.text.isNotBlank()) " · " + m.text else ""
                    m.text.isNotBlank() -> SearchMatch.snippet(m.text, query)
                    m.name.isNotBlank() -> m.name
                    else -> labels[m.kind].orEmpty()
                },
                query = query,
                colors = colors,
                onClick = { onOpen(m) },
            )
        }
        if (loading) item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
            }
        } else if (named.isEmpty() && messages.isEmpty() && (query.isNotBlank() || kind.core != null || from != null || days != null)) item(key = "none") {
            Text(
                stringResource(R.string.chat_search_none),
                color = colors.textMuted, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    if (pickFrom) {
        val list = remember { people() }
        val me = stringResource(R.string.search_from_me)
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { pickFrom = false }) {
            Text(
                stringResource(R.string.search_from_title), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                item(key = "self") {
                    PersonRow("", "self", me, colors) { onFrom(SearchPerson("self", me, "", "")); pickFrom = false }
                }
                items(list, key = { it.key }) { p ->
                    PersonRow(p.avatarPath, p.chatId, p.name, colors) { onFrom(p); pickFrom = false }
                }
            }
        }
    }
    if (pickDays) {
        // The picker speaks UTC midnights; days after today hold nothing yet.
        val todayUtc = java.time.LocalDate.now().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val utc = { d: java.time.LocalDate -> d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
        val state = androidx.compose.material3.rememberDateRangePickerState(
            initialSelectedStartDateMillis = days?.first?.let(utc),
            initialSelectedEndDateMillis = days?.second?.let(utc),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
                override fun isSelectableYear(year: Int) = year <= java.time.LocalDate.now().year
            },
        )
        val day = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDays = false },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis?.let(day)
                        if (start != null) onDays(start to (state.selectedEndDateMillis?.let(day) ?: start))
                        pickDays = false
                    },
                ) { Text(stringResource(R.string.common_done), color = colors.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pickDays = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        ) {
            androidx.compose.material3.DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PersonRow(avatarPath: String, seed: String, name: String, colors: AlphaColorScheme, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.alphamessenger.ui.stories.StoryAvatar(avatarPath, seed, com.alphamessenger.ui.components.avatarInitial(name), 40)
        Spacer(Modifier.width(14.dp))
        Text(name, color = colors.textPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val KIND_MARK = mapOf(
    "media" to "🖼 ", "file" to "📎 ", "doc" to "📄 ", "app" to "📦 ", "archive" to "🗜 ",
    "music" to "🎵 ", "voice" to "🎤 ", "round" to "⭕ ", "poll" to "📊 ", "link" to "🔗 ",
)
private val NAMED_KINDS = setOf("file", "doc", "app", "archive", "music")

@Composable
private fun NarrowPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) colors.accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (on) colors.accent.copy(alpha = 0.5f) else colors.alphaBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (on) 4.dp else 12.dp)
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = if (on) colors.accent else colors.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) colors.textPrimary else colors.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        if (on) Box(
            Modifier.padding(start = 2.dp).size(26.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(com.alphamessenger.ui.icons.AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        }
    }
}

/** "3 – 15 Nov", or one day when both ends meet. */
private fun periodLabel(d: Pair<java.time.LocalDate, java.time.LocalDate>): String {
    val day = java.time.format.DateTimeFormatter.ofPattern("d MMM")
    return if (d.first == d.second) d.first.format(day) else d.first.format(java.time.format.DateTimeFormatter.ofPattern("d")) .let { start ->
        if (d.first.month == d.second.month && d.first.year == d.second.year) start + " – " + d.second.format(day)
        else d.first.format(day) + " – " + d.second.format(day)
    }
}

@Composable
private fun SectionTitle(text: String, trailing: String?, colors: AlphaColorScheme, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, modifier = Modifier.weight(1f))
        if (trailing != null) Text(
            trailing, color = if (onTrailing != null) colors.accent else colors.textMuted, fontSize = 13.sp,
            modifier = if (onTrailing != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTrailing).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier,
        )
    }
}

@Composable
private fun FoundRow(
    chatId: String,
    avatarPath: String,
    title: String,
    time: String,
    prefix: String,
    text: String,
    query: String,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.alphamessenger.ui.stories.StoryAvatar(avatarPath, chatId, com.alphamessenger.ui.components.avatarInitial(title), 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(time, color = colors.textMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
            val body = remember(prefix, text, query, colors) {
                buildAnnotatedString {
                    if (prefix.isNotEmpty()) {
                        pushStyle(SpanStyle(color = colors.accent)); append(prefix); pop()
                    }
                    append(searchMarked(text, query, colors))
                }
            }
            Text(body, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
