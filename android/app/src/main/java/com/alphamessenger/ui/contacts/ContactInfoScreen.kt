@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alphamessenger.ui.contacts

import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.overscroll
import androidx.compose.ui.draw.clipToBounds
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.components.glass
import com.alphamessenger.ui.components.glowFill
import androidx.compose.ui.draw.shadow
import com.alphamessenger.ui.chat.text.toLinkAction
import android.content.ClipData
import com.alphamessenger.ui.components.avatarBackdrop
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import coil3.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.chat.avatarGradientFor
import com.alphamessenger.ui.settings.saveAvatarToInternal
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * classic info screen — same scaffold for direct chats (peer
 * profile) and group chats (group metadata + members), decided by
 * `state.isGroup`.
 */
@Composable
fun ContactInfoScreen(
    state: ContactInfoUiState,
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onLeaveGroup: () -> Unit,
    onSetGroupAvatar: (path: String) -> Unit = {},
    onSetGroupName: (String) -> Unit = {},
    onSetGroupDescription: (String) -> Unit = {},
    onToggleMute: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    mediaLoader: suspend (String) -> String? = { null },
    repository: com.alphamessenger.data.AlphaCoreRepository? = null,
    viewModel: ContactInfoViewModel? = null,
    onOpenInChat: (messageId: String) -> Unit = {},
    onOpenMember: (senderId: String, npub: String) -> Unit = { _, _ -> },
    onOpenDm: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    /** Group: the pencil — photo, name, description and every group setting. */
    onOpenEdit: () -> Unit = {},
    /** 1:1: send this contact an invitation to a secret chat. */
    onStartSecretChat: () -> Unit = {},
) {
    val colors = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    var memberAction by remember { mutableStateOf<PeerRow?>(null) }
    MemberActionsHost(state, viewModel, memberAction) { memberAction = null }
    // Back from the safety-number screen: the tile follows the mark made there.
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel?.refreshVerified() }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    var confirmLeave by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    if (showQr && state.npub.isNotBlank()) {
        com.alphamessenger.ui.chatlist.ShareNpubSheet(npub = state.npub, colors = colors, onDismiss = { showQr = false })
    }
    var showInviteLinks by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    if (showSecret) {
        com.alphamessenger.ui.secret.SecretChatStartSheet(
            peerName = state.displayName,
            onStart = { showSecret = false; onStartSecretChat() },
            onDismiss = { showSecret = false },
        )
    }
    var showTransferLeave by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var groupTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Members, 1 = Shared media
    var inviting by remember { mutableStateOf(false) }

    // Add members: contacts to tick, or anyone by public key.
    if (showInvite && viewModel != null) {
        AddMembersSheet(
            title = stringResource(if (state.channel != null) R.string.channel_add_subscribers else R.string.add_members_title),
            colors = colors,
            loadContacts = { viewModel.pickableContacts() },
            busy = inviting,
            onAdd = { npubs ->
                inviting = true
                viewModel.inviteMany(npubs) { msg ->
                    inviting = false
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    showInvite = false
                }
            },
            onDismiss = { showInvite = false },
        )
    }

    // --- G-7: invite links management ---
    if (showInviteLinks) {
        InviteLinksDialog(
            colors = colors,
            viewModel = viewModel,
            onDismiss = { showInviteLinks = false },
            onShare = { link ->
                runCatching {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, link)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(send, ctx.getString(R.string.link_share)))
                }
            },
            onCopy = { copyClipboard(ctx, "invite", it); toast(ctx, ctx.getString(R.string.invite_link_copied)) },
        )
    }

    // --- Leave-group confirmation ---
    if (confirmLeave) {
        if (state.isOwner) {
            // Owner: choose to delete the group or transfer ownership and leave.
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmLeave = false },
                containerColor = colors.bgSecondary,
                title = { Text(stringResource(if (state.channel != null) R.string.channel_leave_owner_title else R.string.leave_owner_title), color = colors.textPrimary) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.leave_owner_hint),
                            color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(if (state.channel != null) R.string.channel_leave_owner_delete else R.string.leave_owner_delete),
                            color = Color(0xFFFF5A5A), fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { confirmLeave = false; viewModel?.deleteGroupAsOwner { onLeaveGroup() } }
                                .padding(vertical = 12.dp),
                        )
                        Text(
                            stringResource(R.string.leave_owner_transfer),
                            color = colors.accent, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { confirmLeave = false; showTransferLeave = true }
                                .padding(vertical = 12.dp),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmLeave = false }) {
                        Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                    }
                },
            )
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmLeave = false },
                containerColor = colors.bgSecondary,
                title = { Text(stringResource(if (state.channel != null) R.string.channel_leave_title else R.string.leave_group_title), color = colors.textPrimary) },
                text = {
                    Text(
                        stringResource(if (state.channel != null) R.string.channel_leave_confirm_text else R.string.leave_confirm_text),
                        color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmLeave = false
                        viewModel?.leaveChat { onLeaveGroup() }
                    }) { Text(stringResource(R.string.leave_action), color = Color(0xFFFF5A5A)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmLeave = false }) {
                        Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                    }
                },
            )
        }
    }

    // --- Owner: pick the new owner before leaving ---
    if (showTransferLeave) {
        val candidates = state.members.filter { !it.isSelf && it.npub.isNotBlank() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTransferLeave = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.member_action_transfer_ownership), color = colors.textPrimary) },
            text = {
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.transfer_no_candidates), color = colors.textSecondary, fontSize = 13.sp)
                } else {
                    Column {
                        Text(stringResource(R.string.transfer_pick_hint), color = colors.textSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        candidates.forEach { m ->
                            Text(
                                m.displayName.ifBlank { "…${m.npub.takeLast(6)}" },
                                color = colors.accent, fontSize = 15.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { showTransferLeave = false; viewModel?.transferAndLeave(m) { onLeaveGroup() } }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTransferLeave = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                }
            },
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.bgPrimary),
    ) {
        // NB: do NOT early-return from inside a composable lambda — it
        // corrupts Compose's group stack and crashes with
        // ArrayIndexOutOfBoundsException in IntStack.peek2. Use if/else.
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = colors.accent,
            )
        } else if (state.isSelfProfile) {
            SelfProfileContent(state, colors, onBack, onOpenSaved, viewModel, ctx)
        } else {
        val scroll = rememberScrollState()
        val headerRangePx = with(androidx.compose.ui.platform.LocalDensity.current) { HEADER_COLLAPSE_RANGE.toPx() }
        val headerProgress = { (scroll.value / headerRangePx).coerceIn(0f, 1f) }
        // The header never rests half-collapsed: once a drag/fling ends inside
        // the collapse range, it finishes to the nearer end (open or collapsed).
        androidx.compose.runtime.LaunchedEffect(scroll.isScrollInProgress) {
            if (!scroll.isScrollInProgress) {
                val v = scroll.value
                val range = headerRangePx.toInt()
                if (v in 1 until range) scroll.animateScrollTo(if (v < range / 2) 0 else range)
            }
        }
        // A short page (few members, little media) is still tall enough for the
        // header to collapse fully: at least the screen + the collapse range.
        // The stretch at either end belongs to the page AND the pinned tab strip drawn over
        // it: the drag and the overscroll sit on their common parent, not on the column.
        val overscroll = androidx.compose.foundation.gestures.ScrollableDefaults.overscrollEffect()
        val reverse = androidx.compose.foundation.gestures.ScrollableDefaults.reverseDirection(
            androidx.compose.ui.platform.LocalLayoutDirection.current, androidx.compose.foundation.gestures.Orientation.Vertical, false,
        )
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize()
                .clipToBounds()
                .overscroll(overscroll)
                .scrollable(scroll, androidx.compose.foundation.gestures.Orientation.Vertical, overscrollEffect = overscroll, reverseDirection = reverse),
        ) {
        val minPageHeight = maxHeight + HEADER_COLLAPSE_RANGE
        // One tab strip for every profile: members (groups) + the shared categories.
        // A channel's subscribers don't see each other: the list is for admins.
        val categories = sharedCategories(state)
        val pageTabs = buildList {
            if (state.isGroup && (state.channel == null || state.isAdmin)) add(
                Triple("members", stringResource(if (state.channel != null) R.string.channel_tab_subscribers else R.string.group_tab_members), AlphaIcons.Outlined.Group),
            )
            categories.forEach { add(Triple("cat:" + it.id, "${it.label} ${it.count}", it.icon)) }
        }
        // Keep the selected index valid if the tab set shrinks (e.g. role lost).
        if (groupTab > pageTabs.lastIndex) groupTab = pageTabs.lastIndex.coerceAtLeast(0)
        // The member list tab appears or goes with a role change: show it from the start.
        val firstTab = pageTabs.firstOrNull()?.first
        androidx.compose.runtime.LaunchedEffect(firstTab) { groupTab = 0 }
        val selection = remember { SharedSelection() }
        androidx.compose.runtime.LaunchedEffect(groupTab) { selection.clear() }
        // Where the strip's slot sits in the page; the strip itself is drawn pinned over it.
        var tabsY by remember { mutableIntStateOf(-1) }
        val tabSlot = Modifier.fillMaxWidth().height(TAB_STRIP)
            .onGloballyPositioned { tabsY = it.positionInParent().y.toInt() }
        val sharedCategory = pageTabs.getOrNull(groupTab)?.first?.takeIf { it.startsWith("cat:") }?.removePrefix("cat:")
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll, enabled = false).heightIn(min = minPageHeight)) {
            Spacer(
                Modifier
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .height(56.dp),
            )
            Header(state, colors, headerProgress)
            Spacer(Modifier.height(16.dp))
            // "Chat" opens the conversation: for a group it pops back to the
            // group chat (onOpenChat); for a direct/peer profile it opens (or
            // creates) the 1:1 chat (onOpenDm).
            ActionsRow(
                state, colors,
                if (state.isGroup) onOpenChat else onOpenDm,
                onToggleMute, onVerify,
                onShowQr = { showQr = true },
                onAddMembers = { showInvite = true },
                onInviteLinks = { showInviteLinks = true },
            )
            if (state.secretOrigin.isNotEmpty() && viewModel != null) {
                Spacer(Modifier.height(16.dp))
                SecretInfoBlock(state, viewModel, onEnded = onLeaveGroup)
            }
            if (!state.isGroup && !state.isSaved && !state.isBot && !state.chatId.startsWith("peer_") && state.secretOrigin.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                com.alphamessenger.ui.secret.SecretChatCard(
                    onClick = { showSecret = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.disappearingSeconds > 0 && state.secretOrigin.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                val accent = Color(0xFF9B6CF2)
                val label = when (state.disappearingSeconds) {
                    10 -> stringResource(R.string.chat_timer_10s); 300 -> stringResource(R.string.chat_timer_5m); 21_600 -> stringResource(R.string.chat_timer_6h)
                    43_200 -> stringResource(R.string.chat_timer_12h); 86_400 -> stringResource(R.string.chat_timer_24h); 604_800 -> stringResource(R.string.chat_timer_7d)
                    7_776_000 -> stringResource(R.string.chat_timer_90d); else -> stringResource(R.string.chat_timer_hours, state.disappearingSeconds / 3600)
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Outlined.Disappearing, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.chat_disappearing), color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(label, color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            BioOrDescriptionCard(
                state, colors,
                onEdit = null,
            )
            // The key itself is behind the QR tile above: no second copy of it here.
            Spacer(Modifier.height(10.dp))
            if (state.isGroup) {
                val groupTabs = pageTabs
                Box(tabSlot)
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().pointerInput(groupTabs.size) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -60f && groupTab < groupTabs.lastIndex) groupTab++
                                else if (total > 60f && groupTab > 0) groupTab--
                            },
                        ) { _, dragAmount -> total += dragAmount }
                    },
                ) {
                when (groupTabs.getOrNull(groupTab)?.first) {
                "members" -> {
                // Add members — whoever may invite (admins, or everyone if allowed).
                if (state.isAdmin || state.whoCanInvite == "all") {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { showInvite = true }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(46.dp).clip(CircleShape).background(colors.accentDim),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AlphaIcons.Outlined.PersonAdd, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(if (state.channel != null) R.string.channel_add_subscribers else R.string.group_add_members), color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (state.members.isEmpty()) {
                    Text(
                        stringResource(R.string.members_loading),
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                } else {
                    state.members.forEach { peer ->
                        val hasActions = !peer.isSelf && (
                            canRemove(state.myRole, peer.role) ||
                                canChangeRole(state.myRole, peer.role, "admin") ||
                                canChangeRole(state.myRole, peer.role, "moderator") ||
                                canChangeRole(state.myRole, peer.role, "member") ||
                                (state.myRole == "owner" && peer.role != "owner")
                            )
                        MemberRow(
                            peer, colors,
                            selfHidden = state.ownerHidden,
                            showMenu = hasActions,
                            onMenuClick = { memberAction = peer },
                            onClick = { if (peer.isSelf) onOpenMember(peer.id, SELF_MEMBER) else if (peer.npub.isNotBlank()) onOpenMember(peer.id, peer.npub) },
                        )
                    }
                }
                } // end Members
                else -> {
                    // --- Shared media: the category picked in the tabs above ---
                    SharedMediaSection(state, colors, mediaLoader, repository, viewModel, onOpenInChat,
                        onlyCategory = sharedCategory, selection = selection)
                }
                } // end when (tab)
                } // end swipe Column
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.bgTertiary)
                        .clickable { confirmLeave = true }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Outlined.ExitToApp, null, tint = colors.danger, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(if (state.channel != null) R.string.channel_leave_title else R.string.leave_group_title), color = colors.danger, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                if (pageTabs.isNotEmpty()) {
                    Box(tabSlot)
                    Spacer(Modifier.height(8.dp))
                }
                SharedMediaSection(state, colors, mediaLoader, repository, viewModel, onOpenInChat,
                    onlyCategory = sharedCategory, selection = selection)
            }
            // Bottom breathing room + clear the system navigation area so the
            // last row (e.g. in the log tab) is never hidden behind it.
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        if (pageTabs.isNotEmpty()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val pinTop = WindowInsets.statusBars.getTop(density) + with(density) { 56.dp.roundToPx() }
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(0, if (tabsY < 0) -100_000 else maxOf(tabsY - scroll.value, pinTop)) }
                    .fillMaxWidth()
                    .height(TAB_STRIP)
                    .background(colors.bgPrimary),
                contentAlignment = Alignment.Center,
            ) {
                if (selection.active) {
                    SharedSelectionStrip(
                        count = selection.ids.size,
                        colors = colors,
                        onClose = { selection.clear() },
                        onView = if (selection.ids.size == 1) {
                            { val id = selection.ids.first(); selection.clear(); onOpenInChat(id) }
                        } else null,
                        onForward = { selection.forwarding = true },
                        onDelete = { selection.deleting = true },
                    )
                } else {
                    ProfileTabs(pageTabs, groupTab, colors) { groupTab = it }
                }
            }
        }
        }
        // Pinned bar that fades in once the big header scrolls away:
 // back + small avatar + name + status, as messengers usually do.
        FlyingHeader(
            state = state,
            colors = colors,
            scroll = scroll,
            rangePx = headerRangePx,
            onBack = onBack,
            canBlock = !state.isGroup && !state.isSelfProfile && !state.isBot && state.npub.isNotBlank(),
            onToggleBlock = { viewModel?.toggleBlock() },
            onEdit = if (state.isGroup && (state.isAdmin || state.whoCanEdit == "all")) onOpenEdit else null,
        )
        }
    }
}

private val HEADER_COLLAPSE_RANGE = 170.dp
/** Height of the tab strip and of the selection actions that take its place. */
private val TAB_STRIP = 48.dp

/** The profile's tabs: icon chips in one scrollable row. */
@Composable
private fun ProfileTabs(
    tabs: List<Triple<String, String, androidx.compose.ui.graphics.vector.ImageVector>>,
    selected: Int,
    colors: AlphaColorScheme,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, (_, label, icon) ->
            val on = i == selected
            Row(
                modifier = Modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
                    .background(if (on) colors.accent else colors.bgSecondary)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, null, tint = if (on) Color.White else colors.textSecondary, modifier = Modifier.size(17.dp))
                Text(
                    label,
                    color = if (on) Color.White else colors.textSecondary,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
private val BIG_AVATAR = 140.dp
private val BAR_AVATAR = 38.dp

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * classic header: as the page scrolls, the big avatar shrinks and flies
 * into the pinned bar next to "back", the name follows and shrinks, the status
 * line appears under it and the bar background fades in. The real avatar and
 * name in the page are invisible placeholders (they keep their tap targets);
 * these are the ones drawn, at positions computed from the scroll.
 */
@Composable
private fun FlyingHeader(
    state: ContactInfoUiState,
    colors: AlphaColorScheme,
    scroll: androidx.compose.foundation.ScrollState,
    rangePx: Float,
    onBack: () -> Unit,
    canBlock: Boolean,
    onToggleBlock: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    val px = { d: androidx.compose.ui.unit.Dp -> with(density) { d.toPx() } }
    val grad = remember(state.chatId) { avatarGradientFor(state.chatId) }
    val ease = androidx.compose.animation.core.FastOutSlowInEasing

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()

        // Bar background: comes in over the second half of the travel.
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { (statusTop + px(56.dp)).toDp() })
                .graphicsLayer {
                    val t = (scroll.value / rangePx).coerceIn(0f, 1f)
                    alpha = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
                }
                .background(colors.bgPrimary),
        ) {
            HorizontalDivider(Modifier.align(Alignment.BottomCenter), thickness = 0.5.dp, color = colors.alphaBorder)
        }

        TopBar(colors, onBack, canBlock = canBlock, isBlocked = state.isBlocked, onToggleBlock = onToggleBlock, onEdit = onEdit)

        // The avatar.
        Box(
            Modifier
                .size(BIG_AVATAR)
                .graphicsLayer {
                    val t = ease.transform((scroll.value / rangePx).coerceIn(0f, 1f))
                    val startX = (widthPx - px(BIG_AVATAR)) / 2f
                    val startY = statusTop + px(56.dp) + px(8.dp) - scroll.value
                    val endX = px(52.dp)
                    val endY = statusTop + (px(56.dp) - px(BAR_AVATAR)) / 2f
                    val scale = lerp(1f, BAR_AVATAR.value / BIG_AVATAR.value, t)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    translationX = lerp(startX, endX, t)
                    translationY = lerp(startY, endY, t)
                    scaleX = scale; scaleY = scale
                }
                .clip(CircleShape)
                .avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second))),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isSaved) {
                SavedAvatar(colors, 70.dp, Modifier.matchParentSize())
            } else if (state.isBot) com.alphamessenger.assistant.AssistantAvatar(BIG_AVATAR)
            else if (state.isGroup) com.alphamessenger.ui.components.GroupAvatarPlaceholder(Modifier.matchParentSize())
            else Text(com.alphamessenger.ui.components.avatarInitial(state.displayName), color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.SemiBold)
            if (!state.isSaved && !state.isBot && state.avatarPath.isNotBlank()) {
                AsyncImage(
                    model = java.io.File(state.avatarPath), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().clip(CircleShape),
                )
            }
            // A video avatar plays over its still cover, as on the profile card.
            val video = state.peerVideoPath
            val hasVideo = remember(video) { video.isNotBlank() && java.io.File(video).length() > 0 }
            if (!state.isSaved && hasVideo) {
                com.alphamessenger.ui.profile.LoopingVideo(video, Modifier.matchParentSize().clip(CircleShape))
            }
        }

        // The name: centred under the avatar → left-aligned beside it in the bar.
        // While the avatar is big the name rides just BELOW its bottom edge (so
        // the two never overlap); in the last part of the travel, when the
        // avatar is already small and at the left, it moves up into the bar.
        val tAll = (scroll.value / rangePx).coerceIn(0f, 1f)
        val tName = ((tAll - 0.55f) / 0.45f).coerceIn(0f, 1f).let { ease.transform(it) }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val t = ease.transform((scroll.value / rangePx).coerceIn(0f, 1f))
                    val avatarTop = lerp(statusTop + px(56.dp) + px(8.dp) - scroll.value, statusTop + (px(56.dp) - px(BAR_AVATAR)) / 2f, t)
                    val avatarH = px(BIG_AVATAR) * lerp(1f, BAR_AVATAR.value / BIG_AVATAR.value, t)
                    val underAvatar = avatarTop + avatarH + px(14.dp)
                    val k = ((((scroll.value / rangePx).coerceIn(0f, 1f)) - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    translationY = lerp(underAvatar, statusTop + px(7.dp), ease.transform(k))
                }
                .padding(start = lerp(24f, 102f, tName).dp, end = lerp(24f, 56f, tName).dp),
        ) {
            Row(
                Modifier.align(androidx.compose.ui.BiasAlignment(lerp(0f, -1f, tName), 0f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isBot) com.alphamessenger.assistant.BotMark(lerp(20f, 16f, tName).dp, Modifier.padding(end = 5.dp))
                Text(
                    (if (state.isSaved) stringResource(R.string.menu_saved) else state.displayName), color = colors.textPrimary,
                    fontSize = lerp(22f, 17f, tName).sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.isBot) com.alphamessenger.assistant.VerifiedBadge(lerp(20f, 16f, tName).dp, Modifier.padding(start = 5.dp))
            }
        }

        // Status line in the bar.
        com.alphamessenger.ui.chat.AnimatedStatusText(
            contactSubtitle(state), subtitleColor(state, colors), 13.sp,
            modifier = Modifier
                .padding(start = 102.dp, end = 56.dp)
                .graphicsLayer {
                    val t = (scroll.value / rangePx).coerceIn(0f, 1f)
                    alpha = ((t - 0.7f) / 0.3f).coerceIn(0f, 1f)
                    translationY = statusTop + px(31.dp)
                },
        )
    }
}

// --- TopBar ---

@Composable
private fun TopBar(
    colors: AlphaColorScheme,
    onBack: () -> Unit,
    canBlock: Boolean = false,
    isBlocked: Boolean = false,
    onToggleBlock: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = colors.textPrimary)
        }
        Spacer(Modifier.weight(1f))
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(AlphaIcons.Outlined.Edit, stringResource(R.string.group_edit_title), tint = colors.textPrimary)
            }
        }
        if (canBlock) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(AlphaIcons.Outlined.MoreVert, stringResource(R.string.common_menu), tint = colors.textPrimary)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(colors.bgSecondary),
                ) {
                    val danger = Color(0xFFFF5A5A)
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isBlocked) stringResource(R.string.chat_unblock) else stringResource(R.string.profile_block_action),
                                color = danger,
                            )
                        },
                        leadingIcon = {
                            Icon(AlphaIcons.Outlined.Block, null, tint = danger, modifier = Modifier.size(20.dp))
                        },
                        onClick = { menuOpen = false; onToggleBlock() },
                    )
                }
            }
        }
    }
}

private val ContactInfoUiState.isSaved: Boolean get() = chatId == "saved_messages_local"
private val ContactInfoUiState.isBot: Boolean get() = com.alphamessenger.assistant.isAssistantChat(chatId)

/** The vault avatar of Saved Messages (same as in the chat list). */
@Composable
private fun SavedAvatar(colors: AlphaColorScheme, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(CircleShape).background(
            Brush.linearGradient(listOf(colors.accent, colors.accent.copy(alpha = 0.55f).compositeOver(Color(0xFF101418)))),
        ),
        contentAlignment = Alignment.Center,
    ) { Icon(com.alphamessenger.ui.components.AlphaIcons.Vault, null, tint = Color.White, modifier = Modifier.size(iconSize)) }
}

private fun subtitleColor(state: ContactInfoUiState, colors: AlphaColorScheme): Color =
    if (!state.isGroup && !state.isBot && com.alphamessenger.ui.chat.presenceIsLive(state.presenceStatus)) colors.accent else colors.textMuted

/** Under the name: members for a group, live presence for a person. */
@Composable
private fun contactSubtitle(state: ContactInfoUiState): String =
    if (state.isSaved) stringResource(R.string.saved_subtitle)
    else if (state.isBot) stringResource(R.string.profile_bot)
    else if (state.isGroup) state.statusLine
    else com.alphamessenger.ui.chat.presenceSubtitle(state.presenceStatus, state.presenceText, state.presenceLastSeen)

// --- Header — big avatar + name + status ---

@Composable
private fun Header(
    state: ContactInfoUiState,
    colors: AlphaColorScheme,
    progress: () -> Float = { 0f },
) {
    val ctx = LocalContext.current
    val grad = remember(state.chatId) { avatarGradientFor(state.chatId) }
    val initial = com.alphamessenger.ui.components.avatarInitial(state.displayName)

    val avatarRequest = remember(state.avatarPath) {
        val p = state.avatarPath
        if (p.isBlank()) null
        else java.io.File(p).let { f ->
            coil3.request.ImageRequest.Builder(ctx).data(f)
                .memoryCacheKey("$p#${f.lastModified()}")
                .diskCacheKey("$p#${f.lastModified()}")
                .build()
        }
    }

    // A tap on the avatar opens it (and the video avatar, the cover) full screen.
    val pages = remember(state.avatarPath, state.peerVideoPath, state.peerCoverPath) {
        if (state.isSaved) emptyList()
        else com.alphamessenger.ui.profile.avatarPages(state.avatarPath, state.peerVideoPath, state.peerCoverPath)
    }
    var viewer by remember { mutableStateOf(false) }
    if (viewer) com.alphamessenger.ui.profile.AvatarViewer(state.displayName, pages, 0, onDismiss = { viewer = false })

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(140.dp)) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    // Invisible placeholder: FlyingHeader draws the avatar; the tap lands here.
                    .clip(CircleShape)
                    .then(if (pages.isNotEmpty()) Modifier.clickable { viewer = true } else Modifier)
                    .graphicsLayer { alpha = 0f }
                    .clip(CircleShape)
                    .avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second))),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isGroup) com.alphamessenger.ui.components.GroupAvatarPlaceholder(Modifier.matchParentSize())
                else Text(initial, color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.SemiBold)
                if (avatarRequest != null) {
                    AsyncImage(
                        model = avatarRequest, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().clip(CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                (if (state.isSaved) stringResource(R.string.menu_saved) else state.displayName), color = colors.textPrimary,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = 0f },
            )
        }
        Spacer(Modifier.height(4.dp))
        // A person's own status with a link: the link opens, like in a message.
        if (!state.isGroup && state.presenceText.isNotBlank() && com.alphamessenger.ui.chat.text.hasTappable(state.presenceText)) {
            com.alphamessenger.ui.chat.text.LinkedLine(
                state.presenceText, colors.textSecondary, 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp).graphicsLayer { alpha = (1f - progress() * 2f).coerceIn(0f, 1f) },
            )
        } else com.alphamessenger.ui.chat.AnimatedStatusText(
            contactSubtitle(state), subtitleColor(state, colors), 13.sp,
            modifier = Modifier.graphicsLayer { alpha = (1f - progress() * 2f).coerceIn(0f, 1f) },
        )
    }
}

// --- Secret chat ---

@Composable
private fun SecretInfoBlock(state: ContactInfoUiState, viewModel: ContactInfoViewModel, onEnded: () -> Unit) {
    var confirmEnd by remember { mutableStateOf(false) }
    val linkUp by androidx.compose.runtime.produceState(false, state.chatId) {
        while (true) {
            value = viewModel.secretLinkReady()
            kotlinx.coroutines.delay(if (value) 10_000 else 1_500)
        }
    }
    if (confirmEnd) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmEnd = false },
            text = { Text(stringResource(R.string.secret_end_confirm)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmEnd = false; viewModel.endSecretChat(onEnded) }) {
                    Text(stringResource(R.string.secret_end), color = AlphaTheme.colorScheme.danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.secret_not_now)) }
            },
        )
    }
    com.alphamessenger.ui.secret.SecretInfoSection(
        linkUp = linkUp,
        onCheck = { viewModel.secretCheck() },
        onNewCircuit = { viewModel.secretNewCircuit() },
        timerSeconds = state.disappearingSeconds,
        // A secret chat always disappears: every choice but "off".
        timerOptions = com.alphamessenger.ui.chat.CHAT_TIMER_OPTIONS.filter { it.seconds > 0 }
            .map { it.seconds to stringResource(it.labelRes) },
        onTimer = { viewModel.setDisappearing(it) },
        onEnd = { confirmEnd = true },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

// --- Action row ---

@Composable
private fun ActionsRow(
    state: ContactInfoUiState,
    colors: AlphaColorScheme,
    onOpenChat: () -> Unit,
    onToggleMute: () -> Unit,
    onVerify: () -> Unit,
    onShowQr: () -> Unit = {},
    onAddMembers: () -> Unit = {},
    onInviteLinks: () -> Unit = {},
) {
    val buttons = mutableListOf<Triple<ImageVector, String, () -> Unit>>().apply {
        // A channel opens as the channel, not as a chat.
        if (state.channel != null) add(Triple(AlphaIcons.Outlined.Campaign, stringResource(R.string.channel_section), onOpenChat))
        else add(Triple(
            AlphaIcons.AutoMirrored.Outlined.Chat,
            stringResource(if (state.chatId.startsWith("peer_")) R.string.profile_action_write else R.string.profile_action_chat),
            onOpenChat,
        ))
        if (state.isSaved) return@apply
        // A secret chat: its persona has no QR, code or contact card; a bot has no key at all.
        if (state.secretOrigin.isNotEmpty() || state.isBot) {
            add(Triple(
                if (state.isMuted) AlphaIcons.Outlined.NotificationsOff else AlphaIcons.Outlined.NotificationsActive,
                if (state.isMuted) stringResource(R.string.profile_action_unmute) else stringResource(R.string.editor_sound),
                onToggleMute,
            ))
            return@apply
        }
        // A member seen from a group, no chat with them yet: write to them or
        // show their QR. Sound and verification belong to a chat.
        if (state.chatId.startsWith("peer_")) {
            add(Triple(AlphaIcons.Outlined.QrCode, "QR", onShowQr))
            return@apply
        }
        add(
            Triple(
                if (state.isMuted) AlphaIcons.Outlined.NotificationsOff else AlphaIcons.Outlined.NotificationsActive,
                if (state.isMuted) stringResource(R.string.profile_action_unmute) else stringResource(R.string.editor_sound),
                onToggleMute,
            ),
        )
        if (state.isGroup) {
            if (state.isAdmin || state.whoCanInvite == "all") {
                add(Triple(AlphaIcons.Outlined.PersonAdd, stringResource(R.string.group_action_add), onAddMembers))
                add(Triple(AlphaIcons.Outlined.Link, stringResource(R.string.group_action_link), onInviteLinks))
            }
        } else {
            // Compared and marked: the tile says so, in the colour of a kept promise.
            add(Triple(AlphaIcons.Outlined.VerifiedUser, stringResource(if (state.isVerified) R.string.verify_done_action else R.string.verify_action), onVerify))
            add(Triple(AlphaIcons.Outlined.QrCode, "QR", onShowQr))
        }
    }
    // A person's page has the glass tiles; groups and channels keep the plain ones.
    val glassy = !state.isGroup
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (glassy) 12.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val verifiedLabel = stringResource(R.string.verify_done_action)
        buttons.forEach { (icon, label, onClick) ->
            ActionPill(Modifier.weight(1f), icon, label, onClick, colors, glassy = glassy,
                tint = if (state.isVerified && label == verifiedLabel) colors.success else null)
        }
    }
}

@Composable
private fun ActionPill(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    colors: AlphaColorScheme,
    glassy: Boolean = false,
    primary: Boolean = false,
    tint: Color? = null,
) {
    val shape = RoundedCornerShape(if (glassy) 20.dp else 16.dp)
    Column(
        modifier = modifier
            .then(
                if (primary) Modifier.glowFill(shape)
                else if (glassy) Modifier.glass(shape, tint = 0.3f)
                else Modifier.clip(shape).background(colors.bgTertiary),
            )
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (primary) Color.White else tint ?: colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (primary) Color.White else colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

// --- Self profile — opened by tapping your own row in a group ---

@Composable
private fun SelfProfileContent(
    state: ContactInfoUiState,
    colors: AlphaColorScheme,
    onBack: () -> Unit,
    onOpenSaved: () -> Unit,
    viewModel: ContactInfoViewModel?,
    ctx: Context,
) {
    var showQr by remember { mutableStateOf(false) }
    var bioDraft by remember(state.bio) { mutableStateOf(state.bio) }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val path = com.alphamessenger.ui.settings.saveOwnPhoto(ctx, uri)
            if (!path.isNullOrBlank()) viewModel?.setMyAvatar(path)
        }
    }
    val bmp = remember(state.avatarPath) {
        if (state.avatarPath.isBlank()) null
        else runCatching { com.alphamessenger.data.MediaVault.decodeFile(state.avatarPath)?.asImageBitmap() }.getOrNull()
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(colors, onBack)
        Spacer(Modifier.height(8.dp))
        // Avatar (tap to change) + name.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(104.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(colors.accent, colors.accentDim)))
                    .clickable { avatarPicker.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bmp, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().clip(CircleShape))
                } else {
                    Text(state.displayName.takeIf { it.isNotBlank() }?.let { com.alphamessenger.ui.components.avatarInitial(it) } ?: stringResource(R.string.self_avatar_initial),
                        color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(state.displayName.ifBlank { stringResource(R.string.notif_you) }, color = colors.textPrimary,
                fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(18.dp))
        // Actions: Saved / Photo / QR.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionPill(Modifier.weight(1f), com.alphamessenger.ui.components.AlphaIcons.Vault, stringResource(R.string.menu_saved), onOpenSaved, colors)
            ActionPill(Modifier.weight(1f), AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.preview_photo), { avatarPicker.launch("image/*") }, colors)
            ActionPill(Modifier.weight(1f), AlphaIcons.Outlined.QrCode, "QR", { showQr = true }, colors)
        }
        Spacer(Modifier.height(18.dp))
        // Editable "About" bio.
        Text(stringResource(R.string.profile_about_label), color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
        // Kept as typed: saved on "Done" and whenever the screen is left.
        val latestBio by androidx.compose.runtime.rememberUpdatedState(bioDraft)
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { viewModel?.setMyBio(latestBio) }
        }
        OutlinedTextField(
            value = bioDraft,
            onValueChange = { bioDraft = it.take(200) },
            placeholder = { Text(stringResource(R.string.profile_bio_hint), color = colors.textMuted) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { viewModel?.setMyBio(bioDraft) }),
            shape = RoundedCornerShape(16.dp),
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.alphaBorder,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        )
        if (bioDraft.trim() != state.bio.trim()) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = { viewModel?.setMyBio(bioDraft.trim()) },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(stringResource(R.string.common_save), color = colors.accent) }
        }
        if (state.npub.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            NpubCard(state.npub, colors) { copyClipboard(ctx, "npub", it) }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showQr) {
        com.alphamessenger.ui.chatlist.ShareNpubSheet(state.npub, colors, onDismiss = { showQr = false })
    }
}

// --- G-7 Invite links dialog ---

@Composable
internal fun InviteLinksDialog(
    colors: AlphaColorScheme,
    viewModel: ContactInfoViewModel?,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    var invites by remember { mutableStateOf<List<InviteRow>?>(null) }
    fun reload() { viewModel?.loadInvites { invites = it } }
    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.bgSecondary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(stringResource(R.string.invite_links_title), color = colors.textPrimary,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                        stringResource(R.string.invite_links_hint),
                        color = colors.textMuted, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                Spacer(Modifier.height(14.dp))

                // --- Create row ---
                Text(stringResource(R.string.invite_create), color = colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InviteCreateChip(stringResource(R.string.invite_permanent), colors, Modifier.weight(1f)) {
                        viewModel?.createInvite(0L, 0) { reload() }
                    }
                    InviteCreateChip(stringResource(R.string.chat_timer_7d), colors, Modifier.weight(1f)) {
                        val exp = (System.currentTimeMillis() / 1000) + 7L * 24 * 3600
                        viewModel?.createInvite(exp, 0) { reload() }
                    }
                    InviteCreateChip(stringResource(R.string.invite_one_use), colors, Modifier.weight(1f)) {
                        viewModel?.createInvite(0L, 1) { reload() }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.invite_links_label), color = colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val list = invites
                when {
                    list == null -> Text(stringResource(R.string.common_loading), color = colors.textMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp))
                    list.isEmpty() -> Text(stringResource(R.string.invite_empty), color = colors.textMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp))
                    else -> Column(
                        Modifier.verticalScroll(rememberScrollState()).heightIn(max = 300.dp)
                    ) {
                        list.forEach { inv ->
                            InviteRowItem(inv, colors,
                                onCopy = { onCopy(inv.link) },
                                onShare = { onShare(inv.link) },
                                onRevoke = { viewModel?.revokeInvite(inv.token) { reload() } })
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.common_close), color = colors.accent) }
            }
        }
    }
}

@Composable
private fun InviteCreateChip(label: String, colors: AlphaColorScheme, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(colors.accentDim)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InviteRowItem(
    inv: InviteRow,
    colors: AlphaColorScheme,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRevoke: () -> Unit,
) {
    val status = when {
        inv.revoked -> stringResource(R.string.invite_status_revoked)
        inv.expiresAt > 0 && inv.expiresAt < System.currentTimeMillis() / 1000 -> stringResource(R.string.invite_status_expired)
        inv.maxUses > 0 && inv.usedCount >= inv.maxUses -> stringResource(R.string.invite_status_used_up)
        inv.maxUses > 0 -> stringResource(R.string.invite_uses_left, inv.maxUses - inv.usedCount, inv.maxUses)
        inv.expiresAt > 0 -> stringResource(R.string.invite_valid_until, formatBanUntil(inv.expiresAt * 1000))
        else -> stringResource(R.string.invite_status_permanent)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // Each link is unique (random token); they only LOOK alike because the
        // shared prefix (group id) dominates. Show a short distinguishing code.
        Text(
            stringResource(R.string.invite_link_masked, inv.token.takeLast(8)),
            color = if (inv.active) colors.textPrimary else colors.textMuted,
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(status, color = if (inv.active) colors.accent else Color(0xFFFF5A5A), fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (inv.active) {
                Text(stringResource(R.string.contact_copy), color = colors.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onCopy))
                Text(stringResource(R.string.common_share), color = colors.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onShare))
            }
            if (!inv.revoked) {
                Text(stringResource(R.string.invite_revoke), color = Color(0xFFFF5A5A), fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onRevoke))
            }
        }
    }
}

// --- Cards ---

@Composable
private fun BioOrDescriptionCard(
    state: ContactInfoUiState,
    colors: AlphaColorScheme,
    onEdit: ((String) -> Unit)?,
) {
    val text = if (state.isGroup) state.description else state.bio
    val captionLabel = if (state.isGroup) stringResource(R.string.group_description_label) else stringResource(R.string.profile_bio_label)
    val hasText = text.isNotBlank()
    if (!hasText && onEdit == null) return

    var editOpen by remember { mutableStateOf(false) }
    var draft by remember(text) { mutableStateOf(text) }
    // Links, emails and numbers in it open like in a message.
    var linkAction by remember { mutableStateOf<com.alphamessenger.ui.chat.text.LinkAction?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // A person's "About" is a glass card with its title on top; a group's
    // description keeps the plain card with the caption below.
    val glassy = !state.isGroup
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (glassy) Modifier.padding(horizontal = 12.dp).glass(RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                else Modifier.padding(horizontal = 14.dp).clip(RoundedCornerShape(16.dp)).background(colors.bgTertiary),
            )
            .clickable(enabled = onEdit != null) { draft = text; editOpen = true }
            .padding(horizontal = 16.dp, vertical = if (glassy) 14.dp else 12.dp),
    ) {
        if (glassy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AlphaIcons.Outlined.Info, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(captionLabel, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (hasText) {
            com.alphamessenger.ui.chat.text.MessageText(
                text = text,
                color = colors.textPrimary,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                onEntityTap = { e -> linkAction = e.toLinkAction() },
                onTextTap = if (onEdit != null) ({ draft = text; editOpen = true }) else null,
            )
        } else {
            Text(stringResource(R.string.add_description_hint), color = colors.textMuted, fontSize = 15.sp)
        }
        if (!glassy) {
            Spacer(Modifier.height(4.dp))
            Text(captionLabel, color = colors.textMuted, fontSize = 12.sp)
        }
    }

    linkAction?.let { action ->
        com.alphamessenger.ui.chat.text.LinkActionSheet(
            action = action,
            colors = colors,
            onDismiss = { linkAction = null },
            onOpenInvite = { link ->
                linkAction = null
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).setPackage(ctx.packageName))
                }
            },
        )
    }

    if (editOpen) {
        AlertDialog(
            onDismissRequest = { editOpen = false },
            containerColor = colors.bgSecondary,
            title = { Text(captionLabel, color = colors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(400) },
                    minLines = 2, maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.borderLight,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onEdit?.invoke(draft.trim()); editOpen = false }) {
                    Text(stringResource(R.string.common_save), color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editOpen = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun NpubCard(npub: String, colors: AlphaColorScheme, onCopy: (String) -> Unit) {
    var full by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .glass(RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .clickable { full = !full }
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AlphaIcons.Outlined.Key, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.profile_public_key), color = colors.textPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { onCopy(npub) }, modifier = Modifier.size(40.dp)) {
                Icon(AlphaIcons.Outlined.ContentCopy, stringResource(R.string.contact_copy), tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        }
        if (full) {
            SelectionContainer {
                Text(npub, color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp, modifier = Modifier.padding(end = 10.dp))
            }
        } else {
            Text("${npub.take(14)}…${npub.takeLast(8)}", color = colors.textSecondary, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

/** G-9: one recent-actions (audit) row — human text (who → whom) + local date. */
@Composable
internal fun AuditRow(e: com.alphamessenger.data.AuditEntry, colors: AlphaColorScheme) {
    val who = e.targetName.ifBlank { stringResource(R.string.member_fallback) }
    val by = e.actorName.ifBlank { stringResource(R.string.role_admin) }
    val text = when (e.action) {
        "ban" -> stringResource(R.string.audit_ban, by, who)
        "unban" -> stringResource(R.string.audit_unban, by, who)
        "mute" -> stringResource(R.string.audit_mute, by, who)
        "unmute" -> stringResource(R.string.audit_unmute, by, who)
        "role_admin" -> stringResource(R.string.audit_role_admin, by, who)
        "role_moderator" -> stringResource(R.string.audit_role_moderator, by, who)
        "role_member" -> stringResource(R.string.audit_role_member, by, who)
        "role_owner" -> stringResource(R.string.audit_role_owner, by, who)
        "join" -> stringResource(R.string.audit_join, who)
        "leave" -> stringResource(R.string.audit_leave, who)
        "perms" -> stringResource(R.string.audit_perms, by)
        "closed" -> stringResource(R.string.audit_closed, by)
        "opened" -> stringResource(R.string.audit_opened, by)
        "metadata" -> stringResource(R.string.audit_metadata, by)
        else -> stringResource(R.string.audit_unknown, e.action, who)
    }
    val date = remember(e.ts) {
        runCatching {
            // Render in the device's LOCAL zone (the ts is UTC) so it matches the
            // user's wall clock.
            java.time.OffsetDateTime.parse(e.ts)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        }.getOrDefault(e.ts.take(16))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = colors.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(date, color = colors.textMuted, fontSize = 12.sp)
    }
}

/** Accent colour for a role badge. */
internal fun roleBadgeColor(role: String): Color = when (role) {
    "owner" -> Color(0xFFE2B23A)     // gold
    "admin" -> Color(0xFF4C8DFF)     // blue
    "moderator" -> Color(0xFF34C759) // green
    else -> Color.Transparent
}

@Composable
internal fun MemberRow(
    peer: PeerRow,
    colors: AlphaColorScheme,
    /** Our own row in a chat we own hidden. */
    selfHidden: Boolean = false,
    showMenu: Boolean = false,
    onMenuClick: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val grad = remember(peer.id) { avatarGradientFor(peer.id) }
    val initial = com.alphamessenger.ui.components.avatarInitial(peer.displayName)
    val memberCtx = androidx.compose.ui.platform.LocalContext.current
    val avatarRequest = remember(peer.avatarPath) {
        val p = peer.avatarPath
        if (p.isBlank()) null
        else java.io.File(p).let { f ->
            coil3.request.ImageRequest.Builder(memberCtx).data(f)
                .memoryCacheKey("$p#${f.lastModified()}")
                .diskCacheKey("$p#${f.lastModified()}")
                .build()
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(46.dp).clip(CircleShape)
                .avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second))),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (avatarRequest != null) {
                AsyncImage(
                    model = avatarRequest, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    peer.displayName.ifBlank { "peer…${peer.id.takeLast(8)}" },
                    color = colors.textPrimary,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                com.alphamessenger.ui.components.RolePill(peer.role)
            }
            // Secondary line: "You" for self, otherwise a neutral member label.
            // Presence / "member since …" needs a synced join timestamp (not available yet).
            Text(
                when {
                    peer.isSelf && selfHidden -> stringResource(R.string.member_self_hidden)
                    peer.isSelf -> stringResource(R.string.notif_you)
                    else -> stringResource(R.string.member_fallback)
                },
                color = colors.textMuted,
                fontSize = 12.sp,
            )
        }
        // Three-dots action menu (only when the viewer has rights here).
        // Reserve the SAME width (an IconButton is 48dp) whether or not the
        // button is shown, so role badges line up across all rows — incl. the
        // owner's row, which has no menu.
        if (showMenu) {
            IconButton(onClick = onMenuClick) {
                Icon(AlphaIcons.Outlined.MoreVert, stringResource(R.string.tab_actions), tint = colors.textMuted)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

// --- G-3: roles — labels, permission mirror, action item, toast ---

/** Stands for our own row when a member is opened (its key may be a hidden owner's). */
const val SELF_MEMBER = "self"

/** Human label for a role. */
@Composable
internal fun roleLabel(role: String): String = when (role) {
    "owner" -> stringResource(R.string.role_owner)
    "admin" -> stringResource(R.string.role_admin)
    "moderator" -> stringResource(R.string.role_moderator)
    else -> stringResource(R.string.chat_member_fallback)
}

/** Mirrors `alpha_messages::can_change_role` for UI gating (core re-validates). */
internal fun canChangeRole(actor: String, targetCurrent: String, newRole: String): Boolean {
    if (newRole == "owner") return actor == "owner" && targetCurrent != "owner"
    return when (actor) {
        "owner" -> targetCurrent != "owner"
        "admin" -> roleRank(targetCurrent) < 2 && roleRank(newRole) < 2
        else -> false
    }
}

/** Mirrors `alpha_messages::can_remove`. */
internal fun canRemove(actor: String, target: String): Boolean {
    if (target == "owner") return false
    val a = roleRank(actor); val t = roleRank(target)
    val allowed = when (actor) {
        "owner" -> true
        "admin" -> t < 2
        "moderator" -> t < 1
        else -> false
    }
    return allowed && a > t
}

@Composable
internal fun MemberActionItem(
    label: String,
    colors: AlphaColorScheme,
    icon: ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) Color(0xFFFF5A5A) else colors.accent
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            color = if (danger) Color(0xFFFF5A5A) else colors.textPrimary,
            fontSize = 15.sp,
        )
    }
}

internal fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

// --- Clipboard helper ---

internal fun formatBanUntil(untilMs: Long): String =
    runCatching {
        java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(untilMs))
    }.getOrDefault("")

internal fun copyClipboard(ctx: Context, label: String, value: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(ctx, ctx.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
}
