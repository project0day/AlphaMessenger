package com.alphamessenger.ui.chatlist

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaColorScheme
import org.json.JSONArray
import org.json.JSONObject
import uniffi.alphamessenger.ChatInfo

/** A folder of chats the user put together; kept on this phone only. */
data class ChatFolder(val id: String, val name: String, val chats: Set<String>)

object ChatFolders {
    private const val PREFS = "chat_folders"

    var folders by mutableStateOf(emptyList<ChatFolder>())
        private set
    /** The folder the chat list shows (null = all chats). */
    var selected by mutableStateOf<String?>(null)
    /** Folders shown above the chats at all. */
    var enabled by mutableStateOf(true)
        private set
    /** A chat put in a folder leaves "All" and shows only in its folder. */
    var hideFiled by mutableStateOf(true)
        private set
    private var loaded = false

    /** Chats "All" leaves out: those filed in some folder, when that is wanted. */
    fun filed(): Set<String> =
        if (!enabled || !hideFiled) emptySet() else folders.flatMapTo(HashSet()) { it.chats }

    fun setHideFiled(ctx: Context, on: Boolean) {
        hideFiled = on
        prefs(ctx).edit().putBoolean("hide", on).apply()
    }

    /** Put all [chatIds] in the folder, or take them all out when every one is already there. */
    fun toggleAll(ctx: Context, folderId: String, chatIds: Set<String>) {
        folders = folders.map { f ->
            if (f.id != folderId) f else f.copy(chats = if (f.chats.containsAll(chatIds)) f.chats - chatIds else f.chats + chatIds)
        }
        save(ctx)
    }

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        folders = runCatching {
            val arr = JSONArray(p.getString("f", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val c = o.getJSONArray("c")
                ChatFolder(o.getString("id"), o.getString("n"), (0 until c.length()).map { c.getString(it) }.toSet())
            }
        }.getOrDefault(emptyList())
        selected = p.getString("sel", null)?.takeIf { s -> folders.any { it.id == s } }
        enabled = p.getBoolean("on", true)
        hideFiled = p.getBoolean("hide", true)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        if (!on) selected = null
        prefs(ctx).edit().putBoolean("on", on).putString("sel", selected).apply()
    }

    /** Move a folder one place left ([by] < 0) or right. */
    fun move(ctx: Context, id: String, by: Int) {
        val i = folders.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in folders.indices) return
        folders = folders.toMutableList().apply { add(j, removeAt(i)) }
        save(ctx)
    }

    fun select(ctx: Context, id: String?) {
        selected = id
        prefs(ctx).edit().putString("sel", id).apply()
    }

    /** Create ([folder] id blank) or replace a folder. */
    fun put(ctx: Context, folder: ChatFolder): ChatFolder {
        val f = if (folder.id.isBlank()) folder.copy(id = java.util.UUID.randomUUID().toString().take(8)) else folder
        folders = if (folders.any { it.id == f.id }) folders.map { if (it.id == f.id) f else it } else folders + f
        save(ctx)
        return f
    }

    fun delete(ctx: Context, id: String) {
        folders = folders.filterNot { it.id == id }
        if (selected == id) selected = null
        save(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(ctx: Context) {
        val arr = JSONArray()
        folders.forEach { f -> arr.put(JSONObject().put("id", f.id).put("n", f.name).put("c", JSONArray(f.chats.toList()))) }
        prefs(ctx).edit().putString("f", arr.toString()).putString("sel", selected).apply()
    }
}

/**
 * "All" and the user's folders above the chats, with what is unread in each; a
 * long press edits a folder, "+" makes one.
 */
@Composable
internal fun FolderTabs(
    colors: AlphaColorScheme,
    unreadIn: (ChatFolder?) -> Int,
    onNew: () -> Unit,
    onEdit: (ChatFolder) -> Unit,
) {
    val ctx = LocalContext.current
    ChatFolders.load(ctx)
    if (!ChatFolders.enabled) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FolderChip(stringResource(R.string.folders_all), ChatFolders.selected == null, unreadIn(null), colors,
                onClick = { ChatFolders.select(ctx, null) }, onLongClick = null)
        }
        items(ChatFolders.folders, key = { it.id }) { f ->
            FolderChip(f.name, ChatFolders.selected == f.id, unreadIn(f), colors,
                onClick = { ChatFolders.select(ctx, f.id) }, onLongClick = { onEdit(f) })
        }
        item(key = "new") {
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                    .border(1.dp, colors.alphaBorder, RoundedCornerShape(17.dp))
                    .clickable(onClick = onNew).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AlphaIcons.Filled.Add, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                    if (ChatFolders.folders.isEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.folders_new), color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderChip(
    label: String,
    on: Boolean,
    unread: Int,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val bg by animateColorAsState(if (on) colors.accent else colors.bgTertiary, label = "folder")
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (on) colors.bgPrimary else colors.textPrimary, fontSize = 14.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        if (unread > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.heightIn(min = 18.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) colors.bgPrimary.copy(alpha = 0.9f) else colors.accent)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (unread > 99) "99+" else "$unread", color = if (on) colors.accent else colors.bgPrimary,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Make or change a folder: its name and which chats it holds. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FolderEditor(
    folder: ChatFolder?,
    chats: List<ChatInfo>,
    titleOf: (ChatInfo) -> String,
    avatarOf: (ChatInfo) -> String,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(folder?.name.orEmpty()) }
    var picked by remember { mutableStateOf(folder?.chats.orEmpty()) }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(if (folder == null) R.string.folders_new else R.string.folders_edit),
                color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp),
            )
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(colors.bgTertiary).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (name.isEmpty()) Text(stringResource(R.string.folders_name_hint), color = colors.textMuted, fontSize = 16.sp)
                BasicTextField(
                    value = name, onValueChange = { name = it.take(24) }, singleLine = true,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(R.string.folders_chats_count, picked.size),
                color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(chats, key = { it.id }) { c ->
                    val on = c.id in picked
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (on) picked - c.id else picked + c.id }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val title = titleOf(c)
                        if (com.alphamessenger.assistant.isAssistantChat(c.id)) com.alphamessenger.assistant.AssistantAvatar(40.dp)
                        else com.alphamessenger.ui.stories.StoryAvatar(avatarOf(c), c.id, com.alphamessenger.ui.components.avatarInitial(title), 40)
                        Spacer(Modifier.width(14.dp))
                        Text(title, color = colors.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .background(if (on) colors.accent else colors.bgTertiary)
                                .border(1.5.dp, if (on) colors.accent else colors.textMuted.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(AlphaIcons.Filled.Check, null, tint = colors.bgPrimary, modifier = Modifier.size(15.dp)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (folder != null) Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(colors.danger.copy(alpha = 0.14f))
                        .clickable { ChatFolders.delete(ctx, folder.id); onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.folders_delete), color = colors.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                val ok = name.isNotBlank()
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (ok) colors.accent else colors.bgTertiary)
                        .clickable(enabled = ok) {
                            val f = ChatFolders.put(ctx, ChatFolder(folder?.id.orEmpty(), name.trim(), picked))
                            ChatFolders.select(ctx, f.id)
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.common_save), color = if (ok) colors.bgPrimary else colors.textMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Which folders the picked chats are in: tap to put them in or take them out; or start a new one with them. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FolderPicker(chatIds: Set<String>, colors: AlphaColorScheme, onNew: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ChatFolders.load(ctx)
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.folders_add_to), color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
            ChatFolders.folders.forEach { f ->
                val on = f.chats.containsAll(chatIds)
                Row(
                    Modifier.fillMaxWidth().clickable { ChatFolders.toggleAll(ctx, f.id, chatIds) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Outlined.Folder, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(f.name, color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (on) Icon(AlphaIcons.Filled.Check, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onNew).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.Filled.Add, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.folders_new), color = colors.accent, fontSize = 16.sp)
            }
        }
    }
}
