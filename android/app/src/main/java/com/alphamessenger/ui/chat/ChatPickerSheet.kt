package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.components.avatarBackdrop
import com.alphamessenger.ui.theme.AlphaColorScheme

/** Which kind of chats the picker lists. */
private enum class PickFilter { ALL, CHATS, GROUPS, CHANNELS }

/**
 * Where to forward: what is being sent on top, the recent chats as one row of
 * circles, a filter (all / chats / groups / channels) instead of sections that
 * repeat each other, the chosen ones as chips, and at the bottom the options,
 * a comment and one "Send · N" button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatPickerSheet(
    chats: List<uniffi.alphamessenger.ChatInfo>,
    profiles: Map<String, Pair<String, String>>,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    /** [plain]: sent as one's own message, without the "Forwarded" mark. */
    onConfirm: (targetIds: List<String>, comment: String, plain: Boolean) -> Unit,
    title: String = stringResource(R.string.chat_forward_to),
    /** What is being forwarded ("2 messages · photo"); null = no line. */
    summary: String? = null,
    // Single-select mode: tapping a row confirms immediately; no comment field
    // or button — the caption is written later on the send screen.
    singleSelect: Boolean = false,
    /** Targets that are channels (filtered and labelled apart). */
    channelIds: Set<String> = emptySet(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var plain by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(PickFilter.ALL) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    val savedMessagesName = stringResource(R.string.menu_saved)
    val contactName = stringResource(R.string.chat_contact)
    val groupLabel = stringResource(R.string.picker_kind_group)
    val channelLabel = stringResource(R.string.picker_kind_channel)

    fun nameOf(chat: uniffi.alphamessenger.ChatInfo): String {
        val isSaved = chat.id == "saved_messages_local"
        if (isSaved) return savedMessagesName
        return profiles[chat.id]?.first?.takeIf { it.isNotBlank() }
            ?: chat.name?.takeIf { it.isNotBlank() && !it.startsWith("npub") }
            ?: if (isSaved) savedMessagesName else contactName
    }
    fun isChannel(c: uniffi.alphamessenger.ChatInfo) = c.id in channelIds
    fun isGroup(c: uniffi.alphamessenger.ChatInfo) = c.chatType == "group" && !isChannel(c)

    // Saved first, then pinned, then by the latest activity.
    val ordered = remember(chats) {
        chats.sortedWith(
            compareByDescending<uniffi.alphamessenger.ChatInfo> { it.id == "saved_messages_local" }
                .thenByDescending { it.pinnedAt.toLong() }
                .thenByDescending { it.lastMessageAt },
        )
    }
    val byId = remember(ordered) { ordered.associateBy { it.id } }
    val recent = remember(ordered) { ordered.take(8) }
    val shown = remember(ordered, query, profiles, filter, channelIds) {
        val q = query.trim().lowercase()
        ordered.filter { c ->
            when (filter) {
                PickFilter.ALL -> true
                PickFilter.CHATS -> c.chatType != "group"
                PickFilter.GROUPS -> isGroup(c)
                PickFilter.CHANNELS -> isChannel(c)
            } && (q.isBlank() || nameOf(c).lowercase().contains(q))
        }
    }
    fun toggle(id: String) {
        if (singleSelect) onConfirm(listOf(id), "", false)
        else if (id in selected) selected.remove(id) else selected.add(id)
    }

    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            // Title + what is being sent.
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (summary != null) Text(summary, color = colors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) {
                    Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted)
                }
            }

            // The chosen ones: chips, a tap removes.
            if (selected.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selected.forEach { id ->
                        val chat = byId[id] ?: return@forEach
                        Row(
                            Modifier.clip(RoundedCornerShape(18.dp)).background(colors.accent.copy(alpha = 0.16f))
                                .clickable { selected.remove(id) }
                                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PickAvatar(chat, nameOf(chat), profiles, 26.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(nameOf(chat), color = colors.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthInMax(140.dp))
                            Spacer(Modifier.width(4.dp))
                            Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_delete), tint = colors.accent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Search.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(44.dp)
                    .clip(RoundedCornerShape(14.dp)).background(colors.bgSecondary).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.Outlined.Search, null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(stringResource(R.string.common_search), color = colors.textMuted, fontSize = 15.sp)
                        inner()
                    },
                )
            }

            // Recent: one row of circles.
            if (query.isBlank() && recent.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    items(recent, key = { it.id }) { chat ->
                        val on = chat.id in selected
                        Column(
                            Modifier.width(66.dp).clip(RoundedCornerShape(14.dp)).clickable { toggle(chat.id) }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box {
                                PickAvatar(chat, nameOf(chat), profiles, 52.dp)
                                if (on) CheckMark(colors, Modifier.align(Alignment.BottomEnd))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(nameOf(chat), color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Filter.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(stringResource(R.string.picker_filter_all), filter == PickFilter.ALL, colors) { filter = PickFilter.ALL }
                FilterChip(stringResource(R.string.tab_chats), filter == PickFilter.CHATS, colors) { filter = PickFilter.CHATS }
                if (ordered.any { isGroup(it) }) FilterChip(stringResource(R.string.tab_groups), filter == PickFilter.GROUPS, colors) { filter = PickFilter.GROUPS }
                if (channelIds.isNotEmpty()) FilterChip(stringResource(R.string.tab_channels), filter == PickFilter.CHANNELS, colors) { filter = PickFilter.CHANNELS }
            }

            // The list.
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(shown, key = { it.id }) { chat ->
                    val checked = chat.id in selected
                    val isSaved = chat.id == "saved_messages_local"
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (checked) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { toggle(chat.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PickAvatar(chat, nameOf(chat), profiles, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nameOf(chat), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = when {
                                isSaved -> stringResource(R.string.picker_saved_messages)
                                isChannel(chat) -> channelLabel
                                isGroup(chat) -> groupLabel
                                else -> chat.lastMessagePreview.takeIf { it.isNotBlank() }
                            }
                            if (sub != null) Text(sub, color = colors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!singleSelect) {
                            Spacer(Modifier.width(10.dp))
                            if (checked) CheckMark(colors)
                            else Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, colors.textMuted, CircleShape))
                        }
                    }
                }
            }

            // Options, comment, send.
            if (!singleSelect) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (plain) colors.accent.copy(alpha = 0.16f) else colors.bgSecondary)
                            .clickable { plain = !plain }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(CircleShape)
                                .background(if (plain) colors.accent else Color.Transparent)
                                .then(if (plain) Modifier else Modifier.border(1.5.dp, colors.textMuted, CircleShape)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.picker_no_forward_mark), color = if (plain) colors.accent else colors.textSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(colors.bgSecondary)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            com.alphamessenger.ui.chat.v2.FormattingToolbarHost(comment, { comment = it }) { fv, onFv ->
                                BasicTextField(
                                    value = fv, onValueChange = onFv, maxLines = 3,
                                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                                    cursorBrush = SolidColor(colors.accent),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner ->
                                        if (comment.isEmpty()) Text(stringResource(R.string.picker_comment_hint), color = colors.textMuted, fontSize = 15.sp)
                                        inner()
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        val ready = selected.isNotEmpty()
                        Box(
                            Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                                .background(if (ready) colors.accent else colors.bgSecondary)
                                .clickable(enabled = ready) { onConfirm(selected.toList(), comment, plain) }
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (ready) stringResource(R.string.picker_send_count, selected.size) else stringResource(R.string.common_send),
                                color = if (ready) Color.White else colors.textMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.widthInMax(max: Dp): Modifier = this.then(Modifier.widthIn(max = max))

@Composable
private fun FilterChip(label: String, on: Boolean, colors: AlphaColorScheme, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (on) colors.accent else colors.bgSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (on) Color.White else colors.textSecondary, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun CheckMark(colors: AlphaColorScheme, modifier: Modifier = Modifier) {
    Box(
        modifier.size(22.dp).clip(CircleShape).background(colors.accent).border(2.dp, colors.bgPrimary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** A chat's avatar: its photo (decoded off the main thread, cached) or initials. */
@Composable
private fun PickAvatar(
    chat: uniffi.alphamessenger.ChatInfo,
    name: String,
    profiles: Map<String, Pair<String, String>>,
    size: Dp,
) {
    val isSaved = chat.id == "saved_messages_local"
    val grad = remember(chat.id) { avatarGradientFor(chat.id) }
    val path = profiles[chat.id]?.second?.takeIf { it.isNotBlank() } ?: chat.avatarPath.takeIf { it.isNotBlank() }
    Box(
        Modifier.size(size).clip(CircleShape).avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second))),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isSaved -> Text("★", color = Color.White, fontSize = (size.value * 0.42f).sp)
            path != null -> AsyncImage(
                model = java.io.File(path), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
            chat.chatType == "group" -> com.alphamessenger.ui.components.GroupAvatarPlaceholder(Modifier.size(size))
            else -> Text(com.alphamessenger.ui.components.avatarInitial(name), color = Color.White,
                fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
