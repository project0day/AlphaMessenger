package com.alphamessenger.ui.chatlist

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaTheme
import uniffi.alphamessenger.ChatInfo

/** Settings › Chat folders: on or off, their order, each one's chats. */
@Composable
fun FoldersSettingsScreen(viewModel: ChatListViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = AlphaTheme.colorScheme
    ChatFolders.load(ctx)
    val ui by viewModel.uiState.collectAsState()
    var editing by remember { mutableStateOf<ChatFolder?>(null) }
    val saved = stringResource(R.string.menu_saved)
    val unnamed = stringResource(R.string.unnamed_group)
    val contact = stringResource(R.string.fallback_contact_name)
    val assistant = stringResource(R.string.assistant_title)

    OptionScreen(title = stringResource(R.string.folders_settings_title), onBack = onBack) {
        OptionGroup {
            OptionSwitchRow(
                title = stringResource(R.string.folders_show),
                subtitle = stringResource(R.string.folders_show_hint),
                checked = ChatFolders.enabled,
                onCheckedChange = { ChatFolders.setEnabled(ctx, it) },
                icon = AlphaIcons.Outlined.Folder,
            )
            if (ChatFolders.enabled) {
                OptionDivider()
                OptionSwitchRow(
                    title = stringResource(R.string.folders_hide_filed),
                    subtitle = stringResource(R.string.folders_hide_filed_hint),
                    checked = ChatFolders.hideFiled,
                    onCheckedChange = { ChatFolders.setHideFiled(ctx, it) },
                    icon = AlphaIcons.Outlined.VisibilityOff,
                )
            }
        }
        OptionSection(stringResource(R.string.folders_section))
        OptionGroup {
            ChatFolders.folders.forEachIndexed { i, f ->
                if (i > 0) OptionDivider()
                OptionRow(
                    title = f.name,
                    subtitle = pluralStringResource(R.plurals.folders_chat_count, f.chats.size, f.chats.size),
                    icon = AlphaIcons.Outlined.Folder,
                    onClick = { editing = f },
                    trailing = {
                        Row {
                            IconButton(onClick = { ChatFolders.move(ctx, f.id, -1) }, enabled = i > 0, modifier = Modifier.size(40.dp)) {
                                Icon(AlphaIcons.Filled.KeyboardArrowUp, stringResource(R.string.folders_up),
                                    tint = if (i > 0) colors.textSecondary else colors.textMuted.copy(alpha = 0.4f))
                            }
                            IconButton(onClick = { ChatFolders.move(ctx, f.id, 1) }, enabled = i < ChatFolders.folders.lastIndex, modifier = Modifier.size(40.dp)) {
                                Icon(AlphaIcons.Filled.KeyboardArrowDown, stringResource(R.string.folders_down),
                                    tint = if (i < ChatFolders.folders.lastIndex) colors.textSecondary else colors.textMuted.copy(alpha = 0.4f))
                            }
                        }
                    },
                )
            }
            if (ChatFolders.folders.isNotEmpty()) OptionDivider()
            OptionRow(
                title = stringResource(R.string.folders_new),
                icon = AlphaIcons.Filled.Add,
                chevron = false,
                onClick = { editing = ChatFolder("", "", emptySet()) },
            )
        }
        OptionNote(stringResource(R.string.folders_note))
    }

    editing?.let { f ->
        val title: (ChatInfo) -> String = { c ->
            when {
                c.id == "saved_messages_local" -> saved
                com.alphamessenger.assistant.isAssistantChat(c.id) -> c.name?.takeIf { it.isNotBlank() } ?: assistant
                c.chatType == "group" -> c.name?.takeIf { it.isNotBlank() } ?: unnamed
                else -> ui.peerByChat[c.id]?.displayName?.takeIf { it.isNotBlank() } ?: contact
            }
        }
        FolderEditor(
            folder = f.takeIf { it.id.isNotBlank() },
            chats = (ui.chats + ui.groups).filter { it.archivedAt == 0uL }
                .sortedByDescending { it.lastMessageAt.ifBlank { it.createdAt } },
            titleOf = title,
            avatarOf = { c -> if (c.chatType == "group") c.avatarPath else ui.peerByChat[c.id]?.avatarPath.orEmpty() },
            colors = colors,
            onDismiss = { editing = null },
        )
    }
}
