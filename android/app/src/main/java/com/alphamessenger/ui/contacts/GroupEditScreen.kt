package com.alphamessenger.ui.contacts

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.activity.compose.BackHandler
import com.alphamessenger.ui.components.avatarBackdrop
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.chat.CHAT_TIMER_OPTIONS
import com.alphamessenger.ui.chat.ReactionGrid
import com.alphamessenger.ui.chat.ReactionPolicy
import com.alphamessenger.ui.chat.avatarGradientFor
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.settings.saveAvatarToInternal
import com.alphamessenger.ui.theme.AlphaTheme

/** The pages of "Edit group"; MAIN lists them, each other one is a page of its own. */
private enum class GroupPage { MAIN, LOOK, COMMENTS, PERMISSIONS, REACTIONS, DISAPPEARING, CLOSE, REQUESTS, ADMINS, RESTRICTED, BLACKLIST, AUDIT, TRANSFER }

/**
 * "Edit group" — opened by the pencil of the group's info page. Photo, name
 * and description on top; below, every group setting grouped like the app's
 * settings (Access, Chat, Management) and the owner's danger zone. What a row
 * offers follows the viewer's rights: the owner sees everything, an admin the
 * moderation, a member allowed to edit only the photo/name/description.
 */
@Composable
fun GroupEditScreen(
    state: ContactInfoUiState,
    viewModel: ContactInfoViewModel,
    onBack: () -> Unit,
    onGroupGone: () -> Unit,
    onOpenMember: (senderId: String, npub: String) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(GroupPage.MAIN) }
    BackHandler(enabled = page != GroupPage.MAIN) { page = GroupPage.MAIN }
    val back: () -> Unit = { if (page == GroupPage.MAIN) onBack() else page = GroupPage.MAIN }
    when (page) {
        GroupPage.MAIN -> EditMain(state, viewModel, onBack, onGroupGone, open = { page = it })
        GroupPage.COMMENTS -> state.channel?.let { cfg ->
            com.alphamessenger.ui.channel.CommentSettingsPage(cfg, AlphaTheme.colorScheme, back) { viewModel.setChannelCfg(it) }
        } ?: run { page = GroupPage.MAIN }
        GroupPage.PERMISSIONS -> PermissionsPage(state, viewModel, back)
        GroupPage.LOOK -> LookPage(state, viewModel, back)
        GroupPage.REACTIONS -> ReactionsPage(state, viewModel, back)
        GroupPage.DISAPPEARING -> DisappearingPage(state, viewModel, back)
        GroupPage.CLOSE -> CloseChatPage(state, viewModel, back)
        GroupPage.REQUESTS -> RequestsPage(state, viewModel, back, onOpenMember)
        GroupPage.ADMINS -> AdminsPage(state, viewModel, back, onOpenMember)
        GroupPage.RESTRICTED -> RestrictedPage(state, viewModel, back)
        GroupPage.BLACKLIST -> BlacklistPage(state, viewModel, back)
        GroupPage.AUDIT -> AuditPage(state, back)
        GroupPage.TRANSFER -> TransferPage(state, viewModel, back)
    }
}

// --- The look every member sees ---------------------------------------------

@Composable
private fun LookPage(state: ContactInfoUiState, viewModel: ContactInfoViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    val chatId = state.chatId
    val current by androidx.compose.runtime.produceState<com.alphamessenger.ui.chat.look.ChatLook?>(null, chatId) {
        value = com.alphamessenger.ui.chat.look.ChatLooks.admins(ctx, viewModel.repo, chatId) ?: com.alphamessenger.ui.chat.look.ChatLook()
    }
    val start = current ?: return
    com.alphamessenger.ui.chat.look.ChatLookEditor(
        title = stringResource(if (state.channel != null) R.string.look_title_channel else R.string.look_title_group),
        note = stringResource(R.string.look_note_admin),
        initial = start,
        applyLabel = stringResource(R.string.look_apply_all),
        onBack = back,
        onApply = { look ->
            val ok = com.alphamessenger.ui.chat.look.ChatLooks.applyForAll(ctx, viewModel.repo, chatId, look)
            android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.look_applied else R.string.look_failed), android.widget.Toast.LENGTH_SHORT).show()
            if (ok) back()
        },
        photoPrefix = "a_${chatId.take(12)}",
    )
}

// --- Main page ---------------------------------------------------------------

@Composable
private fun EditMain(
    state: ContactInfoUiState,
    viewModel: ContactInfoViewModel,
    onBack: () -> Unit,
    onGroupGone: () -> Unit,
    open: (GroupPage) -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    val canEditInfo = state.isAdmin || state.whoCanEdit == "all"
    val canInvite = state.isAdmin || state.whoCanInvite == "all"
    var name by rememberSaveable(state.displayName) { mutableStateOf(state.displayName) }
    var description by rememberSaveable(state.description) { mutableStateOf(state.description) }
    val changed = canEditInfo && (name.trim() != state.displayName || description.trim() != state.description)
    var showInviteLinks by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val path = saveAvatarToInternal(ctx, uri, "group_${state.chatId}.jpg", maxBytes = 190 * 1024)
            if (!path.isNullOrBlank()) viewModel.setGroupAvatar(path)
        }
    }

    OptionScreen(
        title = stringResource(R.string.group_edit_title),
        onBack = onBack,
        actions = {
            if (changed) {
                IconButton(onClick = {
                    if (name.trim().isNotEmpty() && name.trim() != state.displayName) viewModel.setGroupName(name.trim())
                    if (description.trim() != state.description) viewModel.setGroupDescription(description.trim())
                    onBack()
                }, modifier = Modifier.size(48.dp)) {
                    Icon(AlphaIcons.Outlined.Check, stringResource(R.string.common_save), tint = cs.accent)
                }
            }
        },
    ) {
        // Photo: the camera sits INSIDE the circle (a band at its bottom), and
        // "Change photo" under it — nothing sticks out of the round shape.
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GroupPhoto(state, size = 104, onClick = if (canEditInfo) ({ photoPicker.launch("image/*") }) else null)
            if (canEditInfo) {
                TextButton(onClick = { photoPicker.launch("image/*") }) {
                    Text(stringResource(R.string.group_change_photo), color = cs.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (canEditInfo) {
            Spacer(Modifier.height(6.dp))
            OptionGroup {
                LabeledField(stringResource(if (state.channel != null) R.string.channel_name_label else R.string.group_name_label), name, singleLine = true, max = 64) { name = it }
                OptionDivider()
                LabeledField(stringResource(R.string.group_description_label), description, singleLine = false, max = 400) { description = it }
            }
        }

        if (canInvite || state.isAdmin) {
            OptionSection(stringResource(R.string.group_section_access))
            OptionGroup {
                if (canInvite) {
                    OptionRow(
                        title = stringResource(R.string.group_invite_links), icon = AlphaIcons.Outlined.Link,
                        onClick = { showInviteLinks = true },
                    )
                }
                if (state.isAdmin) {
                    if (canInvite) OptionDivider()
                    OptionRow(
                        title = stringResource(R.string.group_requests),
                        subtitle = if (state.pendingJoins.isEmpty()) stringResource(R.string.requests_empty)
                        else stringResource(R.string.group_requests_count, state.pendingJoins.size),
                        icon = AlphaIcons.Outlined.PersonAdd,
                        trailing = if (state.pendingJoins.isNotEmpty()) ({ CountBadge(state.pendingJoins.size) }) else null,
                        onClick = { open(GroupPage.REQUESTS) },
                    )
                }
            }
        }

        // The owner's own privacy: hidden behind the chat, or shown.
        val ownerCfg = state.channel ?: state.groupCfg
        if (state.isOwner && ownerCfg?.persona == true) {
            var confirmShow by remember { mutableStateOf(false) }
            OptionSection(stringResource(R.string.owner_privacy_section))
            OptionGroup {
                com.alphamessenger.ui.components.OptionSwitchRow(
                    stringResource(R.string.owner_hidden), stringResource(if (ownerCfg.anon) R.string.owner_hidden_on_hint else R.string.owner_hidden_off_hint),
                    checked = ownerCfg.anon,
                    onCheckedChange = { on -> if (on) viewModel.setOwnerHidden(true) else confirmShow = true },
                )
            }
            if (confirmShow) {
                AlertDialog(
                    onDismissRequest = { confirmShow = false },
                    title = { Text(stringResource(R.string.owner_show_title)) },
                    text = { Text(stringResource(R.string.owner_show_text)) },
                    confirmButton = { TextButton(onClick = { confirmShow = false; viewModel.setOwnerHidden(false) }) { Text(stringResource(R.string.owner_show_confirm)) } },
                    dismissButton = { TextButton(onClick = { confirmShow = false }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
        }

        val channel = state.channel
        if (channel != null && state.isOwner) {
            OptionSection(stringResource(R.string.channel_section))
            OptionGroup {
                com.alphamessenger.ui.components.OptionChoiceRow(stringResource(R.string.channel_join_link),
                    stringResource(R.string.channel_join_link_hint), channel.join == com.alphamessenger.ui.channel.ChannelCfg.JOIN_LINK) {
                    viewModel.setChannelCfg(channel.copy(join = com.alphamessenger.ui.channel.ChannelCfg.JOIN_LINK))
                }
                OptionDivider()
                com.alphamessenger.ui.components.OptionChoiceRow(stringResource(R.string.channel_join_request),
                    stringResource(R.string.channel_join_request_hint), channel.join == com.alphamessenger.ui.channel.ChannelCfg.JOIN_REQUEST) {
                    viewModel.setChannelCfg(channel.copy(join = com.alphamessenger.ui.channel.ChannelCfg.JOIN_REQUEST))
                }
                OptionDivider()
                com.alphamessenger.ui.components.OptionSwitchRow(stringResource(R.string.channel_protect),
                    stringResource(R.string.channel_protect_hint), checked = channel.protect,
                    onCheckedChange = { viewModel.setChannelCfg(channel.copy(protect = it)) })
                OptionDivider()
                com.alphamessenger.ui.components.OptionSwitchRow(stringResource(R.string.history_newcomers),
                    stringResource(R.string.history_newcomers_channel_hint), checked = channel.historyOn,
                    onCheckedChange = { viewModel.setChannelCfg(channel.copy(history = it)) })
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.cmt_settings_title),
                    subtitle = stringResource(if (channel.comments) R.string.cmt_on_summary else R.string.cmt_off_summary),
                    icon = AlphaIcons.Outlined.ChatBubbleOutline,
                    onClick = { open(GroupPage.COMMENTS) },
                )
            }
        }

        if (state.isOwner || state.isAdmin) {
            OptionSection(stringResource(R.string.group_section_chat))
            OptionGroup {
                var first = true
                val divider: @Composable () -> Unit = { if (!first) OptionDivider(); first = false }
                if (state.isOwner && state.channel == null) {
                    divider()
                    OptionRow(
                        title = stringResource(R.string.group_permissions),
                        subtitle = permissionsSummary(state),
                        icon = AlphaIcons.Outlined.Key,
                        onClick = { open(GroupPage.PERMISSIONS) },
                    )
                    divider()
                    OptionRow(
                        title = stringResource(R.string.group_reactions),
                        subtitle = reactionsSummary(state.reactionPolicy),
                        icon = AlphaIcons.Outlined.AddReaction,
                        onClick = { open(GroupPage.REACTIONS) },
                    )
                    divider()
                    com.alphamessenger.ui.components.OptionSwitchRow(
                        stringResource(R.string.group_protect), stringResource(R.string.group_protect_hint),
                        checked = state.groupCfg?.protect == true,
                        onCheckedChange = { viewModel.setGroupProtect(it) },
                    )
                    divider()
                    com.alphamessenger.ui.components.OptionSwitchRow(
                        stringResource(R.string.history_newcomers), stringResource(R.string.history_newcomers_group_hint),
                        checked = state.groupCfg?.historyOn == true,
                        onCheckedChange = { viewModel.setGroupHistory(it) },
                    )
                    divider()
                    val calls = state.groupCfg?.calls != false
                    val videoCalls = state.groupCfg?.videoCalls != false
                    com.alphamessenger.ui.components.OptionSwitchRow(
                        stringResource(R.string.group_calls), stringResource(R.string.group_calls_hint),
                        checked = calls,
                        onCheckedChange = { viewModel.setGroupCalls(it, videoCalls && it) },
                    )
                    divider()
                    com.alphamessenger.ui.components.OptionSwitchRow(
                        stringResource(R.string.group_video_calls), stringResource(R.string.group_video_calls_hint),
                        checked = calls && videoCalls,
                        onCheckedChange = { viewModel.setGroupCalls(calls || it, it) },
                    )
                }
                if (state.isOwner && state.channel != null) {
                    divider()
                    OptionRow(
                        title = stringResource(R.string.group_reactions),
                        subtitle = reactionsSummary(state.reactionPolicy),
                        icon = AlphaIcons.Outlined.AddReaction,
                        onClick = { open(GroupPage.REACTIONS) },
                    )
                }
                divider()
                // The look every member sees (theme + background).
                OptionRow(
                    title = stringResource(R.string.look_admin_row),
                    subtitle = stringResource(R.string.look_admin_row_hint),
                    icon = AlphaIcons.Outlined.Palette,
                    onClick = { open(GroupPage.LOOK) },
                )
                // Join / leave lines for every member (a channel never shows them).
                if (state.channel == null) {
                    val noticesScope = androidx.compose.runtime.rememberCoroutineScope()
                    var notices by remember { mutableStateOf(true) }
                    androidx.compose.runtime.LaunchedEffect(state.chatId) { notices = viewModel.repo.groupNotices(state.chatId) }
                    divider()
                    com.alphamessenger.ui.components.OptionSwitchRow(
                        stringResource(R.string.group_notices), stringResource(R.string.group_notices_hint),
                        checked = notices,
                        onCheckedChange = { on ->
                            notices = on
                            noticesScope.launch {
                                if (runCatching { viewModel.repo.setGroupNotices(state.chatId, on) }.isFailure) notices = !on
                            }
                        },
                    )
                }
                divider()
                OptionRow(
                    title = stringResource(R.string.chat_disappearing),
                    subtitle = timerLabel(state.disappearingSeconds),
                    icon = AlphaIcons.Outlined.Disappearing,
                    onClick = { open(GroupPage.DISAPPEARING) },
                )
                val closed = state.chatClosedUntil > com.alphamessenger.util.TrustedTime.nowSeconds()
                if (state.channel == null) divider()
                if (state.channel == null) OptionRow(
                    title = stringResource(R.string.close_chat_title),
                    subtitle = if (closed) stringResource(R.string.group_closed_until, closedUntilLabel(state.chatClosedUntil))
                    else stringResource(R.string.group_chat_open),
                    icon = AlphaIcons.Outlined.Lock,
                    onClick = { open(GroupPage.CLOSE) },
                )
            }

            OptionSection(stringResource(R.string.group_section_manage))
            OptionGroup {
                val roleHolders = state.members.count { roleRank(it.role) > 0 }
                OptionRow(
                    title = stringResource(R.string.group_admins), icon = AlphaIcons.Outlined.AdminPanelSettings,
                    value = roleHolders.toString(), onClick = { open(GroupPage.ADMINS) },
                )
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.group_restricted), icon = AlphaIcons.Outlined.CommentsDisabled,
                    value = restrictedOf(state).size.toString(), onClick = { open(GroupPage.RESTRICTED) },
                )
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.group_tab_blacklist), icon = AlphaIcons.Outlined.Block,
                    value = state.bans.size.toString(), onClick = { open(GroupPage.BLACKLIST) },
                )
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.group_audit), icon = AlphaIcons.Outlined.History,
                    onClick = { open(GroupPage.AUDIT) },
                )
            }
        }

        if (state.isOwner) {
            Spacer(Modifier.height(22.dp))
            OptionGroup {
                OptionRow(title = stringResource(R.string.group_transfer), danger = true, onClick = { open(GroupPage.TRANSFER) })
                OptionDivider()
                OptionRow(title = stringResource(if (state.channel != null) R.string.channel_delete else R.string.group_delete), danger = true, onClick = { confirmDelete = true })
            }
        }
    }

    if (showInviteLinks) {
        InviteLinksDialog(
            colors = cs,
            viewModel = viewModel,
            onDismiss = { showInviteLinks = false },
            onShare = { link ->
                runCatching {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, link)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(send, ctx.getString(R.string.link_share)))
                }
            },
            onCopy = { copyClipboard(ctx, "invite", it); toast(ctx, ctx.getString(R.string.invite_link_copied)) },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = cs.bgSecondary,
            title = { Text(stringResource(if (state.channel != null) R.string.channel_delete_confirm_title else R.string.group_delete_confirm_title), color = cs.textPrimary) },
            text = { Text(stringResource(if (state.channel != null) R.string.channel_delete_confirm_text else R.string.group_delete_confirm_text), color = cs.textSecondary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteGroupAsOwner { onGroupGone() } }) {
                    Text(stringResource(R.string.common_delete), color = cs.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel), color = cs.textMuted) }
            },
        )
    }
}

@Composable
private fun GroupPhoto(state: ContactInfoUiState, size: Int, onClick: (() -> Unit)?) {
    val ctx = LocalContext.current
    val grad = remember(state.chatId) { avatarGradientFor(state.chatId) }
    val request = remember(state.avatarPath) {
        val p = state.avatarPath
        if (p.isBlank()) null else java.io.File(p).let { f ->
            coil3.request.ImageRequest.Builder(ctx).data(f)
                .memoryCacheKey("$p#${f.lastModified()}").diskCacheKey("$p#${f.lastModified()}").build()
        }
    }
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second)))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
com.alphamessenger.ui.components.GroupAvatarPlaceholder(Modifier.matchParentSize())
        if (request != null) {
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        if (onClick != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((size * 0.32f).dp).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.change_avatar_a11y), tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, singleLine: Boolean, max: Int, onChange: (String) -> Unit) {
    val cs = AlphaTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, color = cs.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(max)) },
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 6,
            textStyle = TextStyle(color = cs.textPrimary, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(cs.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(stringResource(R.string.add_description_hint), color = cs.textMuted, fontSize = 16.sp)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun CountBadge(n: Int) {
    val cs = AlphaTheme.colorScheme
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(cs.accent),
        contentAlignment = Alignment.Center,
    ) { Text(if (n > 99) "99+" else n.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun permissionsSummary(state: ContactInfoUiState): String {
    val write = if (state.whoCanWrite == "admins") stringResource(R.string.group_perm_write_admins) else stringResource(R.string.group_perm_write_all)
    return write
}

@Composable
private fun reactionsSummary(p: ReactionPolicy): String = when (p.mode) {
    ReactionPolicy.MODE_NONE -> stringResource(R.string.group_reactions_none)
    ReactionPolicy.MODE_SOME -> stringResource(R.string.group_reactions_some_count, p.allowed.size)
    else -> stringResource(R.string.group_reactions_all)
}

@Composable
private fun timerLabel(seconds: Int): String =
    CHAT_TIMER_OPTIONS.firstOrNull { it.seconds == seconds }?.let { stringResource(it.labelRes) }
        ?: stringResource(R.string.chat_timer_hours, seconds / 3600)

private fun closedUntilLabel(untilSec: Long): String =
    runCatching {
        java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(untilSec * 1000))
    }.getOrDefault("")

// --- Permissions -------------------------------------------------------------

@Composable
private fun PermissionsPage(state: ContactInfoUiState, viewModel: ContactInfoViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    var write by rememberSaveable { mutableStateOf(state.whoCanWrite) }
    var invite by rememberSaveable { mutableStateOf(state.whoCanInvite) }
    var edit by rememberSaveable { mutableStateOf(state.whoCanEdit) }
    // Applied once, on leaving the page — one broadcast for all the changes.
    val leave = {
        if (write != state.whoCanWrite || invite != state.whoCanInvite || edit != state.whoCanEdit) {
            viewModel.setPermissions(write, invite, edit) { toast(ctx, it) }
        }
        back()
    }
    BackHandler(onBack = leave)
    OptionScreen(stringResource(R.string.group_permissions), onBack = leave) {
        PermissionChoice(stringResource(R.string.perm_who_can_write), write) { write = it }
        PermissionChoice(stringResource(R.string.perm_who_can_invite), invite) { invite = it }
        PermissionChoice(stringResource(R.string.perm_who_can_edit), edit) { edit = it }
        OptionNote(stringResource(R.string.group_perms_note))
    }
}

@Composable
private fun PermissionChoice(title: String, value: String, onChange: (String) -> Unit) {
    OptionSection(title)
    OptionGroup {
        OptionChoiceRow(stringResource(R.string.group_perm_all), selected = value == "all") { onChange("all") }
        OptionDivider()
        OptionChoiceRow(stringResource(R.string.group_perm_admins), selected = value == "admins") { onChange("admins") }
    }
}

// --- Reactions ---------------------------------------------------------------

@Composable
private fun ReactionsPage(state: ContactInfoUiState, viewModel: ContactInfoViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    val cs = AlphaTheme.colorScheme
    var mode by rememberSaveable { mutableStateOf(state.reactionPolicy.mode) }
    var chosen by rememberSaveable {
        mutableStateOf(state.reactionPolicy.allowed.ifEmpty { ReactionPolicy.CHOOSABLE.take(6) })
    }
    val leave = {
        // "Some" with nothing chosen means none.
        val policy = if (mode == ReactionPolicy.MODE_SOME && chosen.isEmpty()) ReactionPolicy(ReactionPolicy.MODE_NONE)
        else ReactionPolicy(mode, if (mode == ReactionPolicy.MODE_SOME) chosen else emptyList())
        if (policy != state.reactionPolicy) viewModel.setReactions(policy) { toast(ctx, it) }
        back()
    }
    BackHandler(onBack = leave)
    OptionScreen(stringResource(R.string.group_reactions), onBack = leave) {
        OptionSection(stringResource(R.string.group_reactions_allowed))
        OptionGroup {
            OptionChoiceRow(stringResource(R.string.group_reactions_all), stringResource(R.string.group_reactions_all_sub), mode == ReactionPolicy.MODE_ALL) { mode = ReactionPolicy.MODE_ALL }
            OptionDivider()
            OptionChoiceRow(stringResource(R.string.group_reactions_some), stringResource(R.string.group_reactions_some_sub), mode == ReactionPolicy.MODE_SOME) { mode = ReactionPolicy.MODE_SOME }
            OptionDivider()
            OptionChoiceRow(stringResource(R.string.group_reactions_none), stringResource(R.string.group_reactions_none_sub), mode == ReactionPolicy.MODE_NONE) { mode = ReactionPolicy.MODE_NONE }
        }
        if (mode == ReactionPolicy.MODE_SOME) {
            OptionSection(stringResource(R.string.group_reactions_pick))
            OptionGroup {
                // Order follows the owner's taps: the first ones fill the quick row.
                ReactionGrid(
                    emoji = ReactionPolicy.CHOOSABLE,
                    mine = chosen.toSet(),
                    colors = cs,
                    onPick = { e -> chosen = if (e in chosen) chosen - e else chosen + e },
                )
            }
            OptionNote(stringResource(R.string.group_reactions_note))
        }
    }
}

// --- Disappearing messages ---------------------------------------------------

@Composable
private fun DisappearingPage(state: ContactInfoUiState, viewModel: ContactInfoViewModel, back: () -> Unit) {
    BackHandler(onBack = back)
    OptionScreen(stringResource(R.string.chat_disappearing), onBack = back) {
        Spacer(Modifier.height(8.dp))
        OptionGroup {
            CHAT_TIMER_OPTIONS.forEachIndexed { i, opt ->
                if (i > 0) OptionDivider()
                OptionChoiceRow(stringResource(opt.labelRes), selected = state.disappearingSeconds == opt.seconds) {
                    if (state.disappearingSeconds != opt.seconds) viewModel.setDisappearing(opt.seconds)
                    back()
                }
            }
        }
        OptionNote(stringResource(R.string.chat_disappearing_hint))
    }
}

// --- Close the chat for a while ----------------------------------------------

@Composable
private fun CloseChatPage(state: ContactInfoUiState, viewModel: ContactInfoViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = back)
    val closed = state.chatClosedUntil > com.alphamessenger.util.TrustedTime.nowSeconds()
    OptionScreen(stringResource(R.string.close_chat_title), onBack = back) {
        Spacer(Modifier.height(8.dp))
        OptionGroup {
            if (closed) {
                OptionRow(
                    title = stringResource(R.string.open_chat_label),
                    subtitle = stringResource(R.string.group_closed_until, closedUntilLabel(state.chatClosedUntil)),
                    icon = AlphaIcons.Outlined.Lock,
                    onClick = { viewModel.setChatClosed(0) { toast(ctx, it) }; back() },
                )
            } else {
                listOf(
                    R.string.close_chat_1h to 3600L, R.string.close_chat_8h to 28800L,
                    R.string.close_chat_24h to 86400L, R.string.close_chat_7d to 604800L,
                ).forEachIndexed { i, (label, secs) ->
                    if (i > 0) OptionDivider()
                    OptionRow(title = stringResource(label), chevron = false, onClick = { viewModel.setChatClosed(secs) { toast(ctx, it) }; back() })
                }
            }
        }
        OptionNote(stringResource(R.string.close_chat_hint))
    }
}

// --- Join requests -----------------------------------------------------------

@Composable
private fun RequestsPage(
    state: ContactInfoUiState,
    viewModel: ContactInfoViewModel,
    back: () -> Unit,
    onOpenMember: (String, String) -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    BackHandler(onBack = back)
    OptionScreen(stringResource(R.string.group_requests), onBack = back) {
        Spacer(Modifier.height(8.dp))
        if (state.pendingJoins.isEmpty()) {
            OptionNote(stringResource(R.string.requests_empty))
        } else OptionGroup {
            state.pendingJoins.forEachIndexed { i, req ->
                if (i > 0) OptionDivider()
                val label = req.name.ifBlank { if (req.npub.length > 16) "${req.npub.take(10)}…${req.npub.takeLast(4)}" else req.npub }
                PersonRow(
                    name = label, avatarPath = req.avatarPath, seed = req.npub,
                    subtitle = stringResource(R.string.join_request_hint),
                    onClick = { onOpenMember("", req.npub) },
                ) {
                    RoundAction(AlphaIcons.Outlined.Check, stringResource(R.string.request_accept), cs.accent) { viewModel.acceptJoin(req.npub) }
                    Spacer(Modifier.width(8.dp))
                    RoundAction(AlphaIcons.Outlined.Close, stringResource(R.string.call_decline), cs.danger) { viewModel.rejectJoin(req.npub) }
                }
            }
        }
    }
}

// --- Administrators ----------------------------------------------------------

@Composable
private fun AdminsPage(
    state: ContactInfoUiState,
    viewModel: ContactInfoViewModel,
    back: () -> Unit,
    onOpenMember: (String, String) -> Unit,
) {
    var target by remember { mutableStateOf<PeerRow?>(null) }
    MemberActionsHost(state, viewModel, target) { target = null }
    BackHandler(onBack = back)
    val holders = state.members.filter { roleRank(it.role) > 0 }
    val others = state.members.filter { roleRank(it.role) == 0 && !it.isSelf }
    val canManage = { p: PeerRow ->
        !p.isSelf && (canRemove(state.myRole, p.role) || canChangeRole(state.myRole, p.role, "admin") ||
            canChangeRole(state.myRole, p.role, "moderator") || canChangeRole(state.myRole, p.role, "member"))
    }
    OptionScreen(stringResource(R.string.group_admins), onBack = back) {
        OptionSection(stringResource(R.string.group_admins_roles))
        OptionGroup {
            holders.forEachIndexed { i, p ->
                if (i > 0) OptionDivider()
                MemberRow(p, AlphaTheme.colorScheme, selfHidden = state.ownerHidden, showMenu = canManage(p), onMenuClick = { target = p }, onClick = { if (p.isSelf) onOpenMember(p.id, SELF_MEMBER) else if (p.npub.isNotBlank()) onOpenMember(p.id, p.npub) })
            }
        }
        if (state.rolesEnabled && others.isNotEmpty() && (state.myRole == "owner" || state.myRole == "admin")) {
            OptionSection(stringResource(R.string.group_admins_assign))
            OptionGroup {
                others.forEachIndexed { i, p ->
                    if (i > 0) OptionDivider()
                    MemberRow(p, AlphaTheme.colorScheme, showMenu = canManage(p), onMenuClick = { target = p }, onClick = { target = p })
                }
            }
            OptionNote(stringResource(R.string.group_admins_note))
        }
    }
}

// --- Restricted --------------------------------------------------------------

/** Members who may not write right now (a restriction that has not ended). */
private fun restrictedOf(state: ContactInfoUiState): List<PeerRow> {
    val now = com.alphamessenger.util.TrustedTime.nowSeconds()
    return state.members.filter { it.mutedUntil > now }.sortedBy { it.mutedUntil }
}

/** Who is restricted and until when; a moderator lifts it here. */
@Composable
private fun RestrictedPage(state: ContactInfoUiState, viewModel: ContactInfoViewModel, back: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    BackHandler(onBack = back)
    val list = restrictedOf(state)
    OptionScreen(stringResource(R.string.group_restricted), onBack = back) {
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            OptionNote(stringResource(R.string.restricted_empty))
        } else OptionGroup {
            list.forEachIndexed { i, p ->
                if (i > 0) OptionDivider()
                // "For good" is stored as a hundred years ahead.
                val forever = p.mutedUntil > com.alphamessenger.util.TrustedTime.nowSeconds() + 50L * 365 * 24 * 3600
                PersonRow(
                    name = p.displayName.ifBlank { "peer…${p.npub.takeLast(8)}" },
                    avatarPath = p.avatarPath, seed = p.npub.ifBlank { p.id },
                    subtitle = if (forever) stringResource(R.string.restricted_forever)
                        else stringResource(R.string.restricted_until, formatBanUntil(p.mutedUntil * 1000)),
                    onClick = null,
                ) {
                    TextButton(onClick = {
                        viewModel.muteMember(p, 0L) { msg -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }
                    }) {
                        Text(stringResource(R.string.restricted_lift), color = cs.accent, fontSize = 14.sp)
                    }
                }
            }
        }
        OptionNote(stringResource(R.string.restricted_note))
    }
}

// --- Blacklist ---------------------------------------------------------------

@Composable
private fun BlacklistPage(state: ContactInfoUiState, viewModel: ContactInfoViewModel, back: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    BackHandler(onBack = back)
    OptionScreen(stringResource(R.string.group_tab_blacklist), onBack = back) {
        Spacer(Modifier.height(8.dp))
        if (state.bans.isEmpty()) {
            OptionNote(stringResource(R.string.blacklist_empty))
        } else OptionGroup {
            state.bans.forEachIndexed { i, ban ->
                if (i > 0) OptionDivider()
                PersonRow(
                    name = ban.name.ifBlank { "peer…${ban.npub.takeLast(8)}" },
                    avatarPath = ban.avatarPath, seed = ban.npub,
                    subtitle = when {
                        ban.reason.isNotBlank() -> stringResource(R.string.ban_reason_value, ban.reason)
                        ban.isPermanent -> stringResource(R.string.ban_permanent_label)
                        else -> stringResource(R.string.ban_until, formatBanUntil(ban.untilMs))
                    },
                    onClick = null,
                ) {
                    TextButton(onClick = { viewModel.unbanMember(ban.npub) }) {
                        Text(stringResource(R.string.chat_unblock), color = cs.accent, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// --- Audit log ---------------------------------------------------------------

@Composable
private fun AuditPage(state: ContactInfoUiState, back: () -> Unit) {
    BackHandler(onBack = back)
    OptionScreen(stringResource(R.string.group_audit), onBack = back) {
        Spacer(Modifier.height(8.dp))
        if (state.auditLog.isEmpty()) {
            OptionNote(stringResource(R.string.audit_empty))
        } else OptionGroup {
            state.auditLog.forEachIndexed { i, e ->
                if (i > 0) OptionDivider()
                AuditRow(e, AlphaTheme.colorScheme)
            }
        }
    }
}

// --- Transfer ownership ------------------------------------------------------

@Composable
private fun TransferPage(state: ContactInfoUiState, viewModel: ContactInfoViewModel, back: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf<PeerRow?>(null) }
    BackHandler(onBack = back)
    val candidates = state.members.filter { !it.isSelf && it.npub.isNotBlank() }
    OptionScreen(stringResource(R.string.group_transfer), onBack = back) {
        OptionNote(stringResource(R.string.transfer_pick_hint))
        Spacer(Modifier.height(8.dp))
        if (candidates.isEmpty()) {
            OptionNote(stringResource(R.string.transfer_no_candidates))
        } else OptionGroup {
            candidates.forEachIndexed { i, p ->
                if (i > 0) OptionDivider()
                MemberRow(p, cs, onClick = { pick = p })
            }
        }
    }
    pick?.let { p ->
        AlertDialog(
            onDismissRequest = { pick = null },
            containerColor = cs.bgSecondary,
            title = { Text(stringResource(R.string.transfer_confirm_title), color = cs.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.transfer_confirm_text, p.displayName.ifBlank { stringResource(R.string.chat_member_fallback) }),
                    color = cs.textSecondary, fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { pick = null; viewModel.transferOwnership(p) { toast(ctx, it) }; back() }) {
                    Text(stringResource(R.string.transfer_action), color = cs.danger)
                }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text(stringResource(R.string.common_cancel), color = cs.textMuted) } },
        )
    }
}

// --- Small pieces ------------------------------------------------------------

@Composable
private fun PersonRow(
    name: String,
    avatarPath: String,
    seed: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    val grad = remember(seed) { avatarGradientFor(seed) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second))),
            contentAlignment = Alignment.Center,
        ) {
            Text(com.alphamessenger.ui.components.avatarInitial(name), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (avatarPath.isNotBlank()) {
                AsyncImage(model = java.io.File(avatarPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = cs.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = cs.textMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

