package com.alphamessenger.ui.chatlist

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlinx.coroutines.flow.first
import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.togetherWith
import com.alphamessenger.ui.components.isDarkSurface
import com.alphamessenger.ui.components.seasonalDecor
import com.alphamessenger.ui.components.starryHeader
import com.alphamessenger.ui.components.matrixHeader
import com.alphamessenger.ui.components.matrixScreen
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.ExperimentalFoundationApi
import com.alphamessenger.ui.components.avatarBackdrop
import androidx.compose.foundation.background
import android.net.Uri
import com.alphamessenger.ui.chat.ProfileScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import coil3.compose.AsyncImage
import com.alphamessenger.ui.profile.ProfileScreen
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.alphamessenger.ui.components.glassPanel
import com.alphamessenger.ui.components.glassSource
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.bgBrush
import com.alphamessenger.ui.theme.themeBackground
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.launch
import uniffi.alphamessenger.ChatInfo
import com.alphamessenger.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/**
 * ChatListScreen — classic conversation list.
 *
 * Layout follows other messengers Android (Material 3):
 *  - CenterAlignedTopAppBar with own-avatar leading and search/menu trailing
 *  - LazyColumn of conversation rows (avatar + name + preview + time + unread)
 *  - NavigationBar at the bottom (Chats / Groups / Calls)
 *  - FAB at bottom-end (compose new message)
 *  - ModalBottomSheet for "Add contact by npub" and "New group"
 *
 * Colours and typography come from [AlphaTheme] so all 5 theme variants
 * (Veil / Hacktivist / Paranoid / Casual / Ghost) just work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onChatClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onProfileScreenClick: () -> Unit = onSettingsClick,
    onContactInfo: (String) -> Unit = {},
    /** Stories: open a contact's ("mine" for ours) / make a new one. */
    onOpenStory: (String) -> Unit = {},
    onCreateStory: () -> Unit = {},
    /** Open a chat at a message, lit up (the music player's "show in chat"). */
    onOpenChatAt: (chatId: String, messageId: String) -> Unit = { c, _ -> onChatClick(c) },
    /** A found message: its chat, at that message, searching for [query]. */
    onOpenFound: (chatId: String, messageId: String, query: String) -> Unit = { c, m, _ -> onOpenChatAt(c, m) },
) {
    val uiState by viewModel.uiState.collectAsState()
    val colors = AlphaTheme.colorScheme
    val storiesVm: com.alphamessenger.ui.stories.StoriesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val storiesState by storiesVm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Notifications are asked for in onboarding only; a reinstall (or a "not now")
    // leaves them off and nothing ever arrives. Ask again once per launch while off.
    val notifCtx = androidx.compose.ui.platform.LocalContext.current
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !notifAskedThisLaunch &&
            androidx.core.content.ContextCompat.checkSelfPermission(notifCtx, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifAskedThisLaunch = true
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Refresh the list whenever this screen comes back to the foreground (e.g.
    // returning from a chat after sending a message). The ViewModel survives
    // navigation, so without this the last-message preview/status stays stale
    // until the next push/restart.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.loadChats()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    var addContactSheet by remember { mutableStateOf(false) }
    var newGroupSheet by remember { mutableStateOf(false) }
    var contactsSheet by remember { mutableStateOf(false) }
    // Lives with the query in the view model: coming back from a chat keeps both.
    val searchActive = uiState.showSearch
    var profileMenuOpen by remember { mutableStateOf(false) }
    var fabSheet by remember { mutableStateOf(false) }
    var pendingChatActions by remember { mutableStateOf<ChatInfo?>(null) }
    // Folders: the one being made/edited (blank id = new), the chat being filed.
    var folderEditing by remember { mutableStateOf<ChatFolder?>(null) }
    var folderPickFor by remember { mutableStateOf<Set<String>?>(null) }
    var muteSheetFor by remember { mutableStateOf<ChatInfo?>(null) }
    var confirmDeleteFor by remember { mutableStateOf<ChatInfo?>(null) }
    var archiveOpen by remember { mutableStateOf(false) }
    var requestsOpen by remember { mutableStateOf(false) }
    var shareSheet by remember { mutableStateOf(false) }
    var scanSheet by remember { mutableStateOf(false) }
    var avatarPreview by remember { mutableStateOf<ChatInfo?>(null) }
    // The Alpha feed: its row on top of Channels, the screen it opens.
    var feedOpen by remember { mutableStateOf(false) }
    val feedCtx = androidx.compose.ui.platform.LocalContext.current
    val feedScope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.alphamessenger.ui.feed.FeedStore.load(feedCtx)
        com.alphamessenger.ui.feed.FeedStore.loadOwner(feedCtx)
        com.alphamessenger.ui.update.AppUpdate.cleanUp(feedCtx)
        // While the app stays open, a notice or a new build still shows up within a quarter hour.
        while (true) {
            kotlinx.coroutines.delay(15 * 60_000L)
            com.alphamessenger.ui.feed.FeedStore.refreshIfStale(feedCtx, viewModel.repository)
        }
    }
    // Coming back to the app looks again: a notice or an update must not wait for the next restart.
    val feedLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(feedLifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) feedScope.launch {
                com.alphamessenger.ui.feed.FeedStore.refreshIfStale(feedCtx, viewModel.repository)
            }
        }
        feedLifecycle.lifecycle.addObserver(obs)
        onDispose { feedLifecycle.lifecycle.removeObserver(obs) }
    }
    val alphaTop = com.alphamessenger.ui.feed.FeedStore.alerts.isNotEmpty() ||
        remember(com.alphamessenger.ui.feed.FeedStore.posts, com.alphamessenger.ui.update.AppUpdate.laterTick) { com.alphamessenger.ui.update.AppUpdate.offerInList(feedCtx) != null }
    if (feedOpen) com.alphamessenger.ui.feed.FeedScreen(colors, viewModel.repository, onDismiss = { feedOpen = false })

    // --- Share from another app ---
    // Recipients are picked right in the list: tap opens a chat and sends to it,
    // a LONG press checks the avatar and gathers several. There is no separate
    // selection screen — and there never was.
    val shareActive = com.alphamessenger.ui.share.PendingShare.active
    // A list, not a set: selection order = recipient order; the first one
    // takes the set and shows the preview.
    var shareSelected by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(shareActive) { if (!shareActive) shareSelected = emptyList() }

    fun toggleShare(id: String) {
        shareSelected = if (id in shareSelected) shareSelected - id else shareSelected + id
    }

    // Several chats picked with a long press, to delete them at once.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    val toggleSelected: (String) -> Unit = { id -> selected = if (id in selected) selected - id else selected + id }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
    val openChat: (String) -> Unit = { id ->
        // Gathering recipients — tap continues the gathering, it doesn't open a chat.
        if (shareActive && shareSelected.isNotEmpty()) toggleShare(id)
        else if (selecting) toggleSelected(id)
        else {
            if (shareActive) com.alphamessenger.ui.share.PendingShare.aim(listOf(id))
            onChatClick(id)
        }
    }
    val onRowLongClick: (ChatInfo) -> Unit = { chat ->
        if (shareActive) toggleShare(chat.id) else toggleSelected(chat.id)
    }
    val confirmShare: () -> Unit = {
        val targets = shareSelected
        if (targets.isNotEmpty()) {
            com.alphamessenger.ui.share.PendingShare.aim(targets)
            val host = targets.first()
            // Same head-start as a plain row tap: the chat opens with a ready header,
            // not an empty one.
            (uiState.chats + uiState.groups).firstOrNull { it.id == host }?.let { chat ->
                val snap = uiState.peerByChat[chat.id]
                com.alphamessenger.ui.chat.ChatOpenHandoff.offer(
                    com.alphamessenger.ui.chat.ChatOpenHandoff.Preview(
                        chatInfo = chat,
                        peerDisplayName = snap?.displayName.orEmpty(),
                        peerAvatarPath = snap?.avatarPath.orEmpty(),
                    )
                )
            }
            onChatClick(host)
        }
    }

    // Calls tab selection state — lifted here so the top bar can swap.
    var callSelectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCallDeleteConfirm by remember { mutableStateOf(false) }

 // FAB hide-on-scroll: hide while scrolling down, reveal
    // when scrolling up. Driven by a nested-scroll listener so it works for
    // whichever tab's list is on screen without threading a LazyListState.
    var fabVisible by remember { mutableStateOf(true) }
    var callPicker by remember { mutableStateOf(false) }
    var createChannelSheet by remember { mutableStateOf(false) }
    val fabScrollConnection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            // Use onPostScroll/`consumed`: it's non-zero ONLY when the list
            // actually scrolled. A short list (e.g. 2 chats) can't scroll, so
            // dragging on it consumes 0 → the FAB stays put (was hiding before
            // because onPreScroll/`available` fires even on non-scrollable drag).
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (consumed.y < -3f) fabVisible = false   // really scrolled down
                else if (consumed.y > 3f) fabVisible = true // really scrolled up
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    // System-back behaviour:
    //  • If the user is anywhere other than the Chats tab → return to Chats.
    //  • Only on the Chats tab does back fall through to the OS (exit app).
 // Same UX as messengers usually do.
    androidx.activity.compose.BackHandler(
        enabled = uiState.selectedTab != ChatListViewModel.BottomTab.CHATS,
    ) {
        viewModel.selectTab(ChatListViewModel.BottomTab.CHATS)
    }
    androidx.activity.compose.BackHandler(enabled = uiState.showSearch) { viewModel.hideSearch() }
    // Kept across the screen's rebuild on return: read afresh, the first frames would
    // show an empty name (a "?" in the avatar) until the profile loads again.
    var ownNpub by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var ownAvatarPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var ownDisplayName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val contactFallback = stringResource(R.string.fallback_contact_name)
    val scope = rememberCoroutineScope()
    // Load own npub + avatar once for the share/copy buttons and TopBar.
    LaunchedEffect(Unit) {
        runCatching {
            val id = viewModel.fetchIdentityNpub()
            ownNpub = id
        }
        runCatching {
            val p = viewModel.fetchMyProfile()
            if (p != null) {
                if (ownNpub.isBlank()) ownNpub = p.npub
                ownAvatarPath = p.avatarPath
                ownDisplayName = p.displayName
            }
        }
    }
    // Re-read profile every time a sheet that could change it closes so the
    // TopBar miniature updates after the user picks a new avatar.
    LaunchedEffect(shareSheet, scanSheet) {
        if (!shareSheet && !scanSheet) {
            runCatching { viewModel.fetchMyProfile() }.getOrNull()?.let {
                ownAvatarPath = it.avatarPath
                ownDisplayName = it.displayName
            }
        }
    }
    // Subscribe to profile-change events so the TopBar avatar refreshes
    // when the user changes the photo on the Profile screen.
    LaunchedEffect(Unit) {
        viewModel.profileChangedFlow().collect {
            runCatching { viewModel.fetchMyProfile() }.getOrNull()?.let { p ->
                ownAvatarPath = p.avatarPath
                ownDisplayName = p.displayName
                if (p.npub.isNotBlank()) ownNpub = p.npub
            }
        }
    }

    val dockCtx = androidx.compose.ui.platform.LocalContext.current
    TabDockPrefs.load(dockCtx)
    // The system navigation bar is see-through here too: the list runs under it.
    val navTint = colors.bgPrimary.copy(alpha = 0.5f)
    androidx.compose.runtime.DisposableEffect(navTint) {
        val owner = Any()
        com.alphamessenger.ui.theme.SystemBars.requestNav(owner, navTint)
        // Only undo our own tint: the next screen may already have set its own.
        onDispose {
            com.alphamessenger.ui.theme.SystemBars.releaseNav(owner)
        }
    }
    val dockShown = !TabDockPrefs.autoHide || fabVisible
    // Missed calls not looked at yet; opening the Calls tab marks them seen.
    val callHistory = remember(dockCtx) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            dockCtx.applicationContext,
            com.alphamessenger.calls.CallLauncher.CallsEntryPoint::class.java,
        ).callHistoryStore()
    }
    val unseenMissed by callHistory.unseenMissed.collectAsState()
    LaunchedEffect(uiState.selectedTab, unseenMissed) {
        if (uiState.selectedTab == ChatListViewModel.BottomTab.CALLS && unseenMissed > 0) callHistory.markMissedSeen()
    }
    val dockTabs = listOf(
        DockTab(ChatListViewModel.BottomTab.CHATS, AlphaIcons.Outlined.ChatBubbleOutline, stringResource(R.string.tab_chats),
            uiState.chats.count { it.archivedAt == 0uL && (it.unreadCount > 0u || it.unreadOverride) }),
        DockTab(ChatListViewModel.BottomTab.GROUPS, AlphaIcons.Outlined.Group, stringResource(R.string.tab_groups),
            uiState.groups.count { it.id !in uiState.channelIds && it.archivedAt == 0uL && (it.unreadCount > 0u || it.unreadOverride) }),
        DockTab(ChatListViewModel.BottomTab.CHANNELS, AlphaIcons.Outlined.Campaign, stringResource(R.string.tab_channels),
            uiState.groups.count { it.id in uiState.channelIds && it.archivedAt == 0uL && (it.unreadCount > 0u || it.unreadOverride) }),
        DockTab(ChatListViewModel.BottomTab.STORIES, AlphaIcons.Outlined.AutoStories, stringResource(R.string.tab_stories),
            storiesState.authors.count { it.unseen }),
        DockTab(ChatListViewModel.BottomTab.CALLS, AlphaIcons.Outlined.Phone, stringResource(R.string.tab_calls), unseenMissed),
    )
    val dockAction = when (uiState.selectedTab) {
        ChatListViewModel.BottomTab.CHATS -> DockAction(AlphaIcons.Outlined.Edit, stringResource(R.string.common_create)) { fabSheet = true }
        ChatListViewModel.BottomTab.GROUPS -> DockAction(AlphaIcons.Outlined.GroupAdd, stringResource(R.string.menu_new_group)) { newGroupSheet = true }
        ChatListViewModel.BottomTab.CHANNELS -> DockAction(AlphaIcons.Outlined.Add, stringResource(R.string.channel_new_title)) { createChannelSheet = true }
        ChatListViewModel.BottomTab.STORIES -> DockAction(AlphaIcons.Outlined.AddCircleOutline, stringResource(R.string.common_create)) { onCreateStory() }
        ChatListViewModel.BottomTab.CALLS -> DockAction(AlphaIcons.Outlined.Phone, stringResource(R.string.call_new_call)) { callPicker = true }
    }
    // A new tab starts with the dock in view.
    LaunchedEffect(uiState.selectedTab) { fabVisible = true }
    val musicCtx = androidx.compose.ui.platform.LocalContext.current
    val musicController = androidx.compose.runtime.remember {
        com.alphamessenger.ui.chat.MusicPlayerHolder.get(musicCtx)
    }
    val voiceController = androidx.compose.runtime.remember {
        com.alphamessenger.ui.chat.VoicePlayerHolder.get(musicCtx)
    }
    val dockHaze = remember { com.alphamessenger.ui.components.GlassState() }
    Scaffold(
        modifier = Modifier.themeBackground(colors),
        containerColor = if (colors.bgBrush != null) Color.Transparent else colors.bgPrimary,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            androidx.compose.foundation.layout.Column {
            // The header is frosted glass over the list running under it.
            androidx.compose.foundation.layout.Column(Modifier.glassPanel(
                dockHaze, androidx.compose.ui.graphics.RectangleShape,
                tint = colors.bgPrimary.copy(alpha = if (colors.bgPrimary.luminance() > 0.5f) 0.78f else 0.6f), backdrop = colors.bgPrimary,
            // Seasonal decorations over the glass, behind the header's content.
            ).starryHeader().matrixHeader().seasonalDecor(isDarkSurface(colors.bgPrimary))) {
            // When the Calls tab is in selection mode, REPLACE the main top bar
            // (Alpha title + search + avatar) with a selection bar.
            if (selecting) {
                androidx.compose.material3.TopAppBar(
                    title = { Text(stringResource(R.string.sel_count_title, selected.size), color = colors.textPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted)
                        }
                    },
                    actions = {
                        // One chat: its usual actions (pin, mute, archive…) stay one tap away.
                        if (selected.size == 1) {
                            IconButton(onClick = {
                                pendingChatActions = (uiState.chats + uiState.groups).firstOrNull { it.id in selected }
                                selected = emptySet()
                            }) { Icon(AlphaIcons.Outlined.MoreVert, stringResource(R.string.chat_actions_more), tint = colors.textPrimary) }
                        }
                        IconButton(onClick = {
                            folderPickFor = selected
                            selected = emptySet()
                        }) { Icon(AlphaIcons.Outlined.Folder, stringResource(R.string.folders_add_to), tint = colors.textPrimary) }
                        IconButton(onClick = { confirmDeleteSelected = true }) {
                            Icon(AlphaIcons.Filled.Delete, stringResource(R.string.common_delete), tint = colors.danger)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            } else if (callSelectedIds.isNotEmpty() && uiState.selectedTab == ChatListViewModel.BottomTab.CALLS) {
                androidx.compose.material3.TopAppBar(
                    title = { Text(stringResource(R.string.sel_count_title, callSelectedIds.size), color = colors.textPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { callSelectedIds = emptySet() }) {
                            Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showCallDeleteConfirm = true }) {
                            Icon(AlphaIcons.Filled.Delete, stringResource(R.string.common_delete), tint = colors.danger)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            } else {
                TopBar(
                syncStatus = viewModel.syncStatus.collectAsState().value,
                ownAvatarPath = ownAvatarPath,
                ownDisplayName = ownDisplayName,
                colors = colors,
                searchActive = searchActive,
                searchQuery = uiState.searchQuery,
                onSearchClick = { viewModel.openSearch() },
                onSetTor = { viewModel.repository.setTorEnabled(it) },
                onCloseSearch = viewModel::hideSearch,
                onSearchChange = viewModel::updateSearchQuery,
                profileMenuOpen = profileMenuOpen,
                onProfileClick = { profileMenuOpen = true },
                onProfileMenuDismiss = { profileMenuOpen = false },
                onSettings = {
                    profileMenuOpen = false
                    onSettingsClick()
                },
                onNewGroup = {
                    profileMenuOpen = false
                    newGroupSheet = true
                },
                onOpenContacts = {
                    profileMenuOpen = false
                    contactsSheet = true
                },
                onOpenSavedMessages = {
                    profileMenuOpen = false
                    onChatClick("saved_messages_local")
                },
                onOpenArchive = {
                    profileMenuOpen = false
                    archiveOpen = true
                },
                onMarkAllRead = {
                    profileMenuOpen = false
                    viewModel.markAllRead(uiState.selectedTab)
                },
                onShareNpub = {
                    profileMenuOpen = false
                    shareSheet = true
                },
                ownNpub = ownNpub,
            )
            } // end else (normal TopBar)
            if (TabDockPrefs.top) {
                TopTabDock(dockShown, dockTabs, uiState.selectedTab, viewModel::selectTab, colors)
            }
            // Active-call banner — shows when a call is minimized.
            com.alphamessenger.calls.ui.CallBanner()
            com.alphamessenger.calls.group.GroupCallReturnBar()
            // Share from another app: the chat list itself is the recipient picker.
            ShareTargetBar(
                active = shareActive,
                selectedCount = shareSelected.size,
                colors = colors,
                onCancel = {
                    if (shareSelected.isNotEmpty()) shareSelected = emptyList()
                    else com.alphamessenger.ui.share.PendingShare.clear()
                },
                onConfirm = confirmShare,
            )
            } // the glass header
            // Mini-players (music, voice, video messages): pills of glass under the
            // header, no panel of their own.
            androidx.compose.runtime.CompositionLocalProvider(com.alphamessenger.ui.chat.LocalPlayerHaze provides dockHaze) {
            com.alphamessenger.ui.chat.GlobalMusicBar(
                controller = musicController,
                onOpenChat = onChatClick,
                onOpenMessage = onOpenChatAt,
            )
            com.alphamessenger.ui.chat.GlobalVoiceBar(
                controller = voiceController,
                onOpenChat = onChatClick,
            )
            com.alphamessenger.ui.chat.round.GlobalRoundBar(onOpenChat = onChatClick)
            }
            }
        },
        // Above the floating tab dock and the system bar, never under them.
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                Modifier.navigationBarsPadding().padding(bottom = if (dockShown && !TabDockPrefs.top) 84.dp else 12.dp),
            )
        },
    ) { inner ->
        val folderCtx = androidx.compose.ui.platform.LocalContext.current
        ChatFolders.load(folderCtx)
        val filed = ChatFolders.filed()
        // A search still finds a filed chat from "All".
        val allChatsActive = uiState.chats
            .filter { it.archivedAt == 0uL && (uiState.searchQuery.isNotBlank() || it.id !in filed) }
            .filterByQuery(uiState.searchQuery, uiState.peerByChat)
        val openFolder = ChatFolders.folders.firstOrNull { it.id == ChatFolders.selected }
        val chatsShown = if (openFolder == null) allChatsActive else {
            val recency = compareByDescending<ChatInfo> { it.pinnedAt > 0u }.thenByDescending { it.lastMessageAt.ifBlank { it.createdAt } }
            (uiState.chats + uiState.groups).filter { it.id in openFolder.chats }
                .filterByQuery(uiState.searchQuery, uiState.peerByChat).sortedWith(recency)
        }
        val unreadIn: (ChatFolder?) -> Int = { f ->
            (uiState.chats + uiState.groups).filter { (if (f == null) it.id !in filed else it.id in f.chats) && it.archivedAt == 0uL && it.id !in uiState.blockedChatIds }
                .count { it.unreadCount > 0u || it.unreadOverride }
        }
        val allGroupsActive = uiState.groups
            .filter { it.archivedAt == 0uL && it.id !in uiState.channelIds }
            .filterByQuery(uiState.searchQuery, uiState.peerByChat)
        val allChannelsActive = uiState.groups
            .filter { it.archivedAt == 0uL && it.id in uiState.channelIds }
            .filterByQuery(uiState.searchQuery, uiState.peerByChat)
        // Dock tap → the pager follows; a swipe that settles → the dock follows.
        val tabs = remember { ChatListViewModel.BottomTab.entries }
        val tabPager = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = tabs.indexOf(uiState.selectedTab),
        ) { tabs.size }
        LaunchedEffect(uiState.selectedTab) {
            val target = tabs.indexOf(uiState.selectedTab)
            if (tabPager.currentPage != target) tabPager.animateScrollToPage(target)
        }
        LaunchedEffect(tabPager) {
            androidx.compose.runtime.snapshotFlow { tabPager.settledPage }.collect { page ->
                if (tabs[page] != uiState.selectedTab) viewModel.selectTab(tabs[page])
            }
        }

        Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDockInset provides dockInset(), LocalDockHaze provides dockHaze,
            LocalListTopInset provides inner.calculateTopPadding(),
            LocalChatsLoaded provides uiState.chatsLoaded,
        ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .nestedScroll(fabScrollConnection)
                // The source of the header's and the dock's frosted glass.
                .glassSource(dockHaze)
                .matrixScreen(),
        ) {
            // The tabs side by side: a swipe moves between them like a tap on the dock.
            HorizontalPager(
                state = tabPager,
                modifier = Modifier.weight(1f),
                key = { tabs[it].name },
            ) { pageIndex ->
                val pageTab = tabs[pageIndex]
                if (pageTab == ChatListViewModel.BottomTab.STORIES) {
                    com.alphamessenger.ui.stories.StoriesTab(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = storiesVm,
                        colors = colors,
                        onOpen = onOpenStory,
                        onCreate = onCreateStory,
                    )
                } else androidx.compose.runtime.CompositionLocalProvider(
                    com.alphamessenger.ui.stories.LocalStoryRings provides storiesState.rings,
                ) {
                // Requests sit above the chats (Chats tab only), one row for all of them.
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalRequestsBanner provides if (pageTab == ChatListViewModel.BottomTab.CHATS) ({
                        androidx.compose.foundation.layout.Column {
                            FolderTabs(
                                colors = colors,
                                unreadIn = unreadIn,
                                onNew = { folderEditing = ChatFolder("", "", emptySet()) },
                                onEdit = { folderEditing = it },
                            )
                            // Alpha's notices and an app update come first, above requests and chats.
                            if (alphaTop) androidx.compose.foundation.layout.Column(
                                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            ) {
                                com.alphamessenger.ui.update.AlertBanners(colors, viewModel.repository)
                                com.alphamessenger.ui.update.UpdateCard(colors, viewModel.repository)
                            }
                            if (uiState.requests.isNotEmpty()) RequestsBanner(uiState.requests, uiState.peerByChat, colors) { requestsOpen = true }
                        }
                    }) else if (pageTab == ChatListViewModel.BottomTab.CHANNELS && !com.alphamessenger.ui.feed.FeedStore.hidden) ({
                        com.alphamessenger.ui.feed.AlphaFeedRow(colors) { feedOpen = true }
                    }) else null,
                ) {
                Body(
                    modifier = Modifier.fillMaxSize(),
                    tab = pageTab,
                    chats = chatsShown,
                    groups = allGroupsActive,
                    channels = allChannelsActive,
                    onCreateChannel = { createChannelSheet = true },
                    onChatClick = openChat,
                    onLongClick = onRowLongClick,
                    shareSelectedIds = if (shareActive) shareSelected.toSet() else selected,
                    // While picking recipients the avatar is part of the row, not an entry
                    // into the contact card.
                    onAvatarClick = {
                        when {
                            shareActive || selecting -> openChat(it.id)
                            // Unseen stories: the avatar opens them; all seen → the profile sheet again.
                            storiesState.rings[it.id]?.unseen == true -> onOpenStory(it.id)
                            else -> avatarPreview = it
                        }
                    },
                    onComposeClick = { addContactSheet = true },
                    onCreateGroup = { newGroupSheet = true },
                    colors = colors,
                    peerByChat = uiState.peerByChat,
                    presenceByChat = uiState.presenceByChat,
                    statusByChat = uiState.statusByChat,
                    statusColorByChat = uiState.statusColorByChat,
                    profileTickByChat = uiState.profileTickByChat,
                    draftByChat = uiState.draftByChat,
                    // Calls tab selection.
                    callSelectedIds = callSelectedIds,
                    onCallSelectionChange = { callSelectedIds = it },
                )
                }
                }
            }
        }
        }
        // A query searches everything at once, over the tabs.
        val ctxHistory = androidx.compose.ui.platform.LocalContext.current
        if (uiState.showSearch) {
            GlobalSearchResults(
                query = uiState.searchQuery.trim(),
                kind = uiState.searchKind,
                onKind = viewModel::setSearchKind,
                from = uiState.searchFrom,
                onFrom = viewModel::setSearchFrom,
                people = viewModel::searchPeople,
                days = uiState.searchDays,
                onDays = viewModel::setSearchDays,
                chats = uiState.chats + uiState.groups,
                channelIds = uiState.channelIds,
                found = uiState.foundMessages,
                loading = uiState.foundLoading,
                end = uiState.foundEnd,
                peers = uiState.peerByChat,
                colors = colors,
                topInset = inner.calculateTopPadding(),
                bottomInset = dockInset(),
                chatRow = { c ->
                    ConversationRow(
                        chat = c,
                        onClick = {
                            SearchHistory.addQuery(ctxHistory, uiState.searchQuery)
                            SearchHistory.addChat(ctxHistory, c.id)
                            openChat(c.id)
                        },
                        onLongClick = { onRowLongClick(c) },
                        colors = colors,
                        peer = uiState.peerByChat[c.id],
                        highlight = uiState.searchQuery.trim(),
                    )
                },
                onOpen = { m ->
                    SearchHistory.addQuery(ctxHistory, uiState.searchQuery)
                    SearchHistory.addChat(ctxHistory, m.chatId)
                    onOpenFound(m.chatId, m.id, uiState.searchQuery.trim())
                },
                onLoadMore = viewModel::loadMoreFound,
                mediaLoader = { ref -> viewModel.repository.downloadMediaToCache(ref) },
                onQuery = viewModel::updateSearchQuery,
                onOpenChat = { id -> SearchHistory.addChat(ctxHistory, id); openChat(id) },
            )
        }
        if (TabDockPrefs.top) {
            FloatingDockAction(dockShown, dockAction, colors, Modifier.align(Alignment.BottomEnd))
        } else {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDockHaze provides dockHaze,
                LocalTabPosition provides { tabPager.currentPage + tabPager.currentPageOffsetFraction },
            ) {
                BottomTabDock(dockShown, dockTabs, uiState.selectedTab, viewModel::selectTab, dockAction, colors, Modifier.align(Alignment.BottomCenter))
            }
        }
        }
    }
    // The main screen's night sky: twinkling stars over it, the bars included.
    com.alphamessenger.ui.components.StarrySkyLayer()
    // The season over the whole screen, when chosen instead of the header.
    com.alphamessenger.ui.components.SeasonalDecorLayer(isDarkSurface(colors.bgPrimary))

    // --- Folders: make / edit one, file a chat ---
    val folderCtx = androidx.compose.ui.platform.LocalContext.current
    val folderTitle: (ChatInfo) -> String = { c ->
        when {
            c.id == "saved_messages_local" -> folderCtx.getString(R.string.menu_saved)
            com.alphamessenger.assistant.isAssistantChat(c.id) -> c.name?.takeIf { it.isNotBlank() } ?: folderCtx.getString(R.string.assistant_title)
            c.chatType == "group" -> c.name?.takeIf { it.isNotBlank() } ?: folderCtx.getString(R.string.unnamed_group)
            else -> uiState.peerByChat[c.id]?.displayName?.takeIf { it.isNotBlank() } ?: folderCtx.getString(R.string.fallback_contact_name)
        }
    }
    folderEditing?.let { f ->
        FolderEditor(
            folder = f.takeIf { it.id.isNotBlank() },
            chats = (uiState.chats + uiState.groups).filter { it.archivedAt == 0uL }
                .sortedByDescending { it.lastMessageAt.ifBlank { it.createdAt } },
            titleOf = folderTitle,
            avatarOf = { c -> if (c.chatType == "group") c.avatarPath else uiState.peerByChat[c.id]?.avatarPath.orEmpty() },
            colors = colors,
            onDismiss = { folderEditing = null },
        )
    }
    folderPickFor?.let { ids ->
        FolderPicker(
            chatIds = ids, colors = colors,
            onNew = { folderPickFor = null; folderEditing = ChatFolder("", "", ids) },
            onDismiss = { folderPickFor = null },
        )
    }

    if (createChannelSheet) {
        com.alphamessenger.ui.channel.CreateChannelSheet(
            colors = colors,
            onDismiss = { createChannelSheet = false },
            onCreate = { name, description, avatar, cfg ->
                viewModel.createChannel(name, description, avatar, cfg) { id ->
                    createChannelSheet = false
                    onChatClick(id)
                }
            },
        )
    }

    // --- New call: pick who to call ---
    if (callPicker) {
        val pickerChats = remember(uiState.chats) {
            uiState.chats.filter { it.chatType != "group" && it.id != "saved_messages_local" && !com.alphamessenger.assistant.isAssistantChat(it.id) }
        }
        // Each peer's own privacy rules (from their profile): who may be called, and how.
        val rights by androidx.compose.runtime.produceState<Map<String, Pair<Boolean, Boolean>>?>(null, pickerChats) {
            value = viewModel.callRights(pickerChats.map { it.id })
        }
        val pickerContacts = remember(pickerChats, uiState.peerByChat, rights) {
            rights?.let { r ->
                pickerChats.mapNotNull { chat ->
                    val (voice, video) = r[chat.id] ?: (true to true)
                    if (!voice && !video) return@mapNotNull null
                    val snap = uiState.peerByChat[chat.id]
                    ContactRow(
                        chatId = chat.id,
                        name = snap?.displayName?.takeIf { it.isNotBlank() } ?: chat.name?.takeIf { it.isNotBlank() } ?: "",
                        avatarPath = snap?.avatarPath.orEmpty(),
                        npub = snap?.npub.orEmpty(),
                        voice = voice,
                        video = video,
                    )
                }.sortedBy { it.name.lowercase() }
            }
        }
        ContactsSheet(
            contacts = pickerContacts,
            onDismiss = { callPicker = false },
            onCall = { id, type ->
                callPicker = false
                com.alphamessenger.calls.CallLauncher.start(ctx, id, type)
            },
            colors = colors,
        )
    }

    // --- Requests: chats from people not in our contacts ---
    if (requestsOpen) {
        RequestsSheet(
            chats = uiState.requests,
            channelIds = uiState.channelIds,
            peerByChat = uiState.peerByChat,
            colors = colors,
            onDismiss = { requestsOpen = false },
            onChatClick = { id -> requestsOpen = false; onChatClick(id) },
            onDeleteAll = { requestsOpen = false; viewModel.declineAllRequests() },
        )
    }

    // --- Archive: dedicated sheet listing archived chats ---
    if (archiveOpen) {
        val archived = (uiState.chats + uiState.groups).filter { it.archivedAt > 0uL }
        ArchiveSheet(
            chats = archived,
            colors = colors,
            onDismiss = { archiveOpen = false },
            onChatClick = { id -> archiveOpen = false; onChatClick(id) },
            onLongClick = { pendingChatActions = it },
        )
    }

    // --- FAB sheet: tab-aware actions menu ---
    if (fabSheet) {
        FabActionsSheet(
            tab = uiState.selectedTab,
            colors = colors,
            onDismiss = { fabSheet = false },
            onAddContact = { fabSheet = false; addContactSheet = true },
            onCreateGroup = { fabSheet = false; newGroupSheet = true },
            onShareNpub = { fabSheet = false; shareSheet = true },
            onCopyNpub = {
                fabSheet = false
                if (ownNpub.isNotBlank()) {
                    copyNpubToClipboard(ctx, ownNpub)
                    scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.chatlist_copied_npub, ownNpub.take(16))) }
                }
            },
            onScanQr = { fabSheet = false; scanSheet = true },
        )
    }

    if (shareSheet) {
        ShareNpubSheet(
            npub = ownNpub,
            colors = colors,
            onDismiss = { shareSheet = false },
            loadCode = { viewModel.repository.contactCode() },
        )
    }

    if (scanSheet) {
        QrScannerSheet(
            colors = colors,
            onDismiss = { scanSheet = false },
            onResult = { code ->
                scanSheet = false
                viewModel.createDirectChat(code)
                scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.npub_found_adding)) }
            },
        )
    }

    avatarPreview?.let { chat ->
        if (chat.chatType == "group") {
            // The same profile sheet as a chat's, in its group form.
            val members by androidx.compose.runtime.produceState(0, chat.id) { value = viewModel.memberCount(chat.id) }
            ProfileScreen(
                displayName = chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.fallback_contact_name),
                avatarPath = chat.avatarPath,
                npub = "",
                avatarSeed = chat.id,
                colors = colors,
                groupSubtitle = androidx.compose.ui.res.pluralStringResource(R.plurals.chat_members, members, members),
                onMessage = { val id = chat.id; avatarPreview = null; onChatClick(id) },
                onInfo = { val id = chat.id; avatarPreview = null; onContactInfo(id) },
                onDismiss = { avatarPreview = null },
            )
        } else {
            val snap = uiState.peerByChat[chat.id]
            // Fallback: opening the profile wakes an on-demand profile pull
            // (rate-limited) so name/avatar/cover are current without chatting.
            androidx.compose.runtime.LaunchedEffect(chat.id) {
                viewModel.requestPeerProfileIfStale(chat.id)
            }
            // Resolve the peer's cover (stored by MLS sender_id). Re-fetches
            // when the peer snapshot changes (a ProfileUpdate arrived).
            val peerTick = uiState.profileTickByChat[chat.id] ?: 0
            // Seed with the cached path (instant, no gradient flash) and refresh
            // in place; only reset when the chat itself changes.
            var peerCover by androidx.compose.runtime.remember(chat.id) {
                androidx.compose.runtime.mutableStateOf(viewModel.cachedCover(chat.id))
            }
            androidx.compose.runtime.LaunchedEffect(chat.id, peerTick) {
                peerCover = viewModel.peerCoverPath(chat.id)
            }
            val peerVideo by androidx.compose.runtime.produceState("", chat.id, peerTick) {
                if (chat.chatType != "group") value = viewModel.peerVideoPath(chat.id)
            }
            val peerBio by androidx.compose.runtime.produceState("", chat.id, peerTick) {
                if (chat.chatType != "group") value = viewModel.peerBio(chat.id)
            }
            // The peer's privacy rules: a call button only where calling is allowed.
            val callRights by androidx.compose.runtime.produceState(true to true, chat.id, peerTick) {
                if (chat.chatType != "group") value = viewModel.peerCallRights(chat.id)
            }
            val isSavedChat = chat.id == "saved_messages_local"
            val secret = snap?.secret == true
            val bot = com.alphamessenger.assistant.isAssistantChat(chat.id)
            ProfileScreen(
                displayName = if (isSavedChat) stringResource(R.string.menu_saved)
                    else if (bot) com.alphamessenger.assistant.assistantNameOf(chat.id) ?: chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.assistant_title)
                    else snap?.displayName?.takeIf { it.isNotBlank() }
                    ?: chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.fallback_contact_name),
                isSaved = isSavedChat,
                isBot = bot,
                avatarPath = snap?.avatarPath.orEmpty(),
                npub = snap?.npub.orEmpty(),
                avatarSeed = chat.id,
                colors = colors,
                bannerPath = peerCover,
                videoPath = peerVideo,
                mediaVersion = peerTick,
                status = com.alphamessenger.ui.chat.profileStatusOf(
                    uiState.statusByChat[chat.id] ?: "offline"
                ),
                statusText = uiState.customStatusByChat[chat.id].orEmpty(),
                statusColor = uiState.statusColorByChat[chat.id]?.takeIf { it != 0 },
                subtitle = if (secret) stringResource(R.string.secret_tor_on) else null,
                bio = if (isSavedChat) "" else peerBio,
                isSelf = false,
                secret = secret,
                onMessage = { val id = chat.id; avatarPreview = null; onChatClick(id) },
                onVoiceCall = if (secret || !callRights.first || com.alphamessenger.assistant.isAssistantChat(chat.id)) null else ({
                    avatarPreview = null
                    if (chat.chatType != "group") {
                        com.alphamessenger.calls.CallLauncher.start(ctx, chat.id, com.alphamessenger.calls.CallType.AUDIO)
                    }
                }),
                onVideoCall = if (secret || !callRights.second || com.alphamessenger.assistant.isAssistantChat(chat.id)) null else ({
                    avatarPreview = null
                    if (chat.chatType != "group") {
                        com.alphamessenger.calls.CallLauncher.start(ctx, chat.id, com.alphamessenger.calls.CallType.VIDEO)
                    }
                }),
                onInfo = { val id = chat.id; avatarPreview = null; onContactInfo(id) },
                onDeleteChat = { avatarPreview = null; viewModel.deleteChat(chat) },
                onDeleteChatForBoth = if (bot) null else ({ avatarPreview = null; viewModel.deleteChat(chat, forBoth = true) }),
                onBlock = {
                    avatarPreview = null
                    viewModel.blockPeer(snap?.npub.orEmpty(), snap?.displayName.orEmpty(), snap?.avatarPath.orEmpty())
                },
                onDismiss = { avatarPreview = null },
            )
        }
    }

    // Share: "back" first clears the picked recipients, then cancels the
    // Share itself — one step back in the flow, not out of it in one move.
    if (shareActive) {
        androidx.activity.compose.BackHandler {
            if (shareSelected.isNotEmpty()) shareSelected = emptyList()
            else com.alphamessenger.ui.share.PendingShare.clear()
        }
    }

    // --- Long-press: chat actions sheet ---
    if (confirmDeleteSelected) {
        val picked = (uiState.chats + uiState.groups).filter { it.id in selected }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.delete_selected_title, picked.size), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.delete_selected_body), color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.deleteOrLeave(picked)
                    selected = emptySet()
                    confirmDeleteSelected = false
                }) { Text(stringResource(R.string.common_delete), color = Color(0xFFFF5A5A)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteSelected = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }

    pendingChatActions?.let { chat ->
        val snap = uiState.peerByChat[chat.id]
        ChatActionsSheet(
            chat = chat,
            displayName = when {
                chat.id == "saved_messages_local" -> stringResource(R.string.menu_saved)
                com.alphamessenger.assistant.isAssistantChat(chat.id) -> chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.assistant_title)
                chat.chatType == "group" -> chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_group)
                else -> snap?.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.fallback_contact_name)
            },
            avatarPath = if (chat.chatType == "group") chat.avatarPath else snap?.avatarPath.orEmpty(),
            colors = colors,
            onDismiss = { pendingChatActions = null },
            onFolder = { pendingChatActions = null; folderPickFor = setOf(chat.id) },
            onPin = {
                viewModel.togglePin(chat); pendingChatActions = null
            },
            onMute = {
                pendingChatActions = null
                muteSheetFor = chat
            },
            onArchive = { viewModel.toggleArchive(chat); pendingChatActions = null },
            onMarkRead = { viewModel.markRead(chat); pendingChatActions = null },
            onMarkUnread = { viewModel.markUnread(chat); pendingChatActions = null },
            onDelete = { pendingChatActions = null; confirmDeleteFor = chat },
        )
    }

    // --- Delete/Leave-chat confirmation ---
    confirmDeleteFor?.let { chat ->
        val colors = AlphaTheme.colorScheme
        val isGroup = chat.chatType == "group"
        val isChannel = chat.id in uiState.channelIds
        // Our own group or channel: leaving it means deleting it for everyone.
        val owner by androidx.compose.runtime.produceState(false, chat.id) {
            value = isGroup && viewModel.amOwner(chat.id)
        }
        val chatName = chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_group)
        val title = when {
            owner && isChannel -> stringResource(R.string.channel_leave_owner_title)
            owner -> stringResource(R.string.leave_owner_title)
            isChannel -> stringResource(R.string.leave_channel_title)
            isGroup -> stringResource(R.string.leave_group_title)
            else -> stringResource(R.string.delete_chat_title)
        }
        val body = if (owner) {
            stringResource(if (isChannel) R.string.owner_delete_body_channel else R.string.owner_delete_body_group, chatName)
        } else if (isChannel) {
            stringResource(R.string.leave_channel_body, chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_group))
        } else if (isGroup) {
            stringResource(R.string.leave_group_body, chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_group))
        } else {
            stringResource(R.string.delete_chat_body, chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_group))
        }
        val confirmLabel = if (isGroup && !owner) stringResource(R.string.leave_action) else stringResource(R.string.common_delete)
        if (!isGroup) {
            val peer = chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.fallback_contact_name)
            com.alphamessenger.ui.chat.DeleteChatDialog(
                title = title, body = body, colors = colors,
                peerName = if (uiState.peerByChat[chat.id]?.secret == true) null else peer,
                onConfirm = { forBoth -> viewModel.deleteChat(chat, forBoth); confirmDeleteFor = null },
                onDismiss = { confirmDeleteFor = null },
            )
        } else androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteFor = null },
            containerColor = colors.bgSecondary,
            title = { Text(title, color = colors.textPrimary) },
            text = { Text(body, color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (isGroup) viewModel.leaveGroup(chat) else viewModel.deleteChat(chat)
                    confirmDeleteFor = null
                }) { Text(confirmLabel, color = Color(0xFFFF5A5A)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteFor = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }

    // Calls tab: delete confirmation dialog.
    if (showCallDeleteConfirm) {
        val ctx2 = androidx.compose.ui.platform.LocalContext.current
        val callStore = androidx.compose.runtime.remember(ctx2) {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                ctx2.applicationContext,
                com.alphamessenger.calls.CallLauncher.CallsEntryPoint::class.java,
            ).callHistoryStore()
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCallDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_calls_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.delete_calls_body, callSelectedIds.size), color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    callStore.delete(callSelectedIds)
                    callSelectedIds = emptySet()
                    showCallDeleteConfirm = false
                }) { Text(stringResource(R.string.common_delete), color = colors.danger) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCallDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }

    // --- Mute duration sub-sheet ---
    muteSheetFor?.let { chat ->
        MuteDurationSheet(
            isMuted = chat.mutedUntil > 0u,
            colors = colors,
            onDismiss = { muteSheetFor = null },
            onPick = { seconds ->
                viewModel.setMute(chat, seconds)
                muteSheetFor = null
            },
        )
    }

    if (addContactSheet) {
        // Track whether we've actually started a loading cycle inside this
        // sheet. Without this, the sheet would auto-dismiss the instant it
        // opens (because isAddingContact=false and peerKey="" are also the
        // post-success state). We only dismiss AFTER seeing isAddingContact
        // transition true → false with no error.
        var sawLoading by remember { mutableStateOf(false) }
        LaunchedEffect(uiState.isAddingContact) {
            if (uiState.isAddingContact) {
                sawLoading = true
            } else if (sawLoading && uiState.error == null) {
                addContactSheet = false
                viewModel.updateNewChatPeerKey("")
            }
        }
        AddContactSheet(
            value = uiState.newChatPeerKey,
            onValueChange = viewModel::updateNewChatPeerKey,
            isLoading = uiState.isAddingContact,
            onDismiss = {
                addContactSheet = false
                viewModel.updateNewChatPeerKey("")
            },
            onConfirm = {
                viewModel.createDirectChat(uiState.newChatPeerKey)
                // Close the sheet immediately — the network roundtrip (fetch
                // KeyPackage + publish Welcome) can take several seconds on a
                // slow relay, and leaving the sheet open with a spinner just
                // looks broken. The chat row appears on the main screen as soon
                // as the core finishes; any error surfaces in a Snackbar.
                addContactSheet = false
            },
            colors = colors,
        )
    }

    if (newGroupSheet) {
        val groupFriends = remember(uiState.chats, uiState.peerByChat) {
            uiState.chats.mapNotNull { chat ->
                val snap = uiState.peerByChat[chat.id]
                val np = snap?.npub.orEmpty()
                if (np.isNotBlank()) GroupFriend(
                    npub = np,
                    name = snap?.displayName?.takeIf { it.isNotBlank() }
                        ?: chat.name?.takeIf { it.isNotBlank() } ?: contactFallback,
                    avatarPath = snap?.avatarPath.orEmpty(),
                ) else null
            }.distinctBy { it.npub }
        }
        NewGroupSheet(
            value = uiState.groupName,
            onValueChange = viewModel::updateGroupName,
            friends = groupFriends,
            onDismiss = {
                newGroupSheet = false
                viewModel.updateGroupName("")
            },
            onConfirm = { members, avatarUri, description, hiddenOwner, protect, history ->
                viewModel.createGroup(uiState.groupName, members, avatarUri, description, hiddenOwner, protect, history)
                newGroupSheet = false
            },
            colors = colors,
        )
    }

    // --- Contacts (avatar menu): who may reach me by my privacy rules ---
    if (contactsSheet) {
        val direct = uiState.chats.filter { it.chatType != "group" && it.id != "saved_messages_local" && !com.alphamessenger.assistant.isAssistantChat(it.id) }
        MyContactsSheet(
            contacts = direct.filter { it.id in uiState.contactChats },
            others = direct.filter { it.id !in uiState.contactChats },
            peerByChat = uiState.peerByChat,
            colors = colors,
            onDismiss = { contactsSheet = false },
            onOpen = { id -> contactsSheet = false; onChatClick(id) },
            onAdd = { contactsSheet = false; addContactSheet = true },
            onScan = { contactsSheet = false; scanSheet = true },
            onSetContact = { chat, on -> viewModel.setContact(chat, on) },
        )
    }
}

private fun List<ChatInfo>.filterByQuery(
    q: String,
    peers: Map<String, com.alphamessenger.ui.chatlist.ChatPeerSnapshot> = emptyMap(),
): List<ChatInfo> {
    if (q.isBlank()) return this
    val needle = q.trim().lowercase()
    return filter { c ->
        // Match the DISPLAYED name (peer broadcast) first — that's what the user
        // sees and types; the raw chat.name is usually an npub placeholder.
        (peers[c.id]?.displayName?.lowercase()?.contains(needle) == true) ||
            (c.name?.lowercase()?.contains(needle) == true) ||
            c.id.lowercase().contains(needle) ||
            c.lastMessagePreview.lowercase().contains(needle)
    }
}

// --- Top bar — center-aligned, avatar leading, search + overflow trailing ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    syncStatus: ChatListViewModel.SyncStatus?,
    ownAvatarPath: String,
    ownNpub: String,
    ownDisplayName: String,
    colors: AlphaColorScheme,
    searchActive: Boolean,
    searchQuery: String,
    onSearchClick: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchChange: (String) -> Unit,
    profileMenuOpen: Boolean,
    onProfileClick: () -> Unit,
    onProfileMenuDismiss: () -> Unit,
    onSettings: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSavedMessages: () -> Unit,
    onOpenArchive: () -> Unit,
    onMarkAllRead: () -> Unit,
    onShareNpub: () -> Unit,
    onSetTor: suspend (Boolean) -> Unit,
) {
    if (searchActive) {
        androidx.compose.material3.TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = colors.textPrimary,
                navigationIconContentColor = colors.textPrimary,
                actionIconContentColor = colors.textSecondary,
            ),
            title = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text(stringResource(R.string.common_search), color = colors.textMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            navigationIcon = {
                IconButton(onClick = onCloseSearch) {
                    Icon(
                        imageVector = AlphaIcons.Outlined.Close,
                        contentDescription = stringResource(R.string.chatlist_close_search),
                    )
                }
            },
        )
        return
    }

    androidx.compose.material3.TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = colors.textPrimary,
            navigationIconContentColor = colors.textPrimary,
            actionIconContentColor = colors.textSecondary,
        ),
        title = {
            // The logo, or what the receiving side is busy with right now.
            androidx.compose.animation.AnimatedContent(
                targetState = syncStatus,
                transitionSpec = { androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut() },
                label = "sync-status",
            ) { status ->
                if (status == null) {
                    Text(
                        "Alpha",
                        style = androidx.compose.ui.text.TextStyle(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(0xFF8B5CF6), Color(0xFFC026D3), Color(0xFFEC4899)),
                            ),
                            fontFamily = com.alphamessenger.ui.theme.BricolageGrotesque,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 26.sp,
                            letterSpacing = (-0.5).sp,
                        ),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(
                                when (status) {
                                    ChatListViewModel.SyncStatus.WAITING_NETWORK -> R.string.sync_waiting_network
                                    ChatListViewModel.SyncStatus.CONNECTING -> R.string.sync_connecting
                                    ChatListViewModel.SyncStatus.UPDATING -> R.string.sync_updating
                                },
                            ),
                            color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        actions = {
            // Private mode at a glance: green = through Tor, red = direct. Tap toggles.
            val torCtx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) { com.alphamessenger.ui.components.TorButtonPref.load(torCtx) }
            if (com.alphamessenger.ui.components.TorButtonPref.visible) {
                com.alphamessenger.ui.components.TorQuickButton(onSetTor, Modifier.size(40.dp))
            }
            // Ghost mode at a glance: green eyes = on, red = off. Tap toggles.
            val ghostCtx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) { com.alphamessenger.ui.components.GhostButtonPref.load(ghostCtx) }
            if (com.alphamessenger.ui.components.GhostButtonPref.visible) {
                val ghostOn by com.alphamessenger.ui.components.GhostModeState.on.collectAsState()
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    com.alphamessenger.ui.components.GhostModeState.request(!ghostOn)
                    android.widget.Toast.makeText(
                        ghostCtx,
                        ghostCtx.getString(if (!ghostOn) R.string.ghost_on_toast else R.string.ghost_off_toast),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    com.alphamessenger.ui.components.GhostIcon(on = ghostOn, body = colors.textSecondary)
                }
            }
            // Lock now — only when a lock is set up.
            val lockCtx = androidx.compose.ui.platform.LocalContext.current
            val lockOn = remember { com.alphamessenger.data.AppLockManager(lockCtx).isLockEnabled }
            if (lockOn) {
                // Open while the app is open: it springs open as the list appears
                // (right after unlocking) and snaps shut when tapped — then locks.
                var appeared by remember { mutableStateOf(false) }
                var closing by remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(Unit) { appeared = true }
                androidx.compose.runtime.LaunchedEffect(closing) {
                    if (closing) {
                        kotlinx.coroutines.delay(380)
                        com.alphamessenger.ui.lock.AppLockRequests.lockNow()
                    }
                }
                val open = com.alphamessenger.ui.lock.rememberPadlockOpening(appeared && !closing)
                val lockNowLabel = stringResource(R.string.lock_now)
                IconButton(onClick = { closing = true }, modifier = Modifier.size(40.dp)) {
                    com.alphamessenger.ui.lock.Padlock(
                        open, if (closing) colors.textSecondary else colors.accent, 24.dp,
                        modifier = Modifier.semantics { contentDescription = lockNowLabel },
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            // Profile avatar — replaces the kebab. Tap → popup menu.
            val gradSelf = remember(ownNpub) {
                com.alphamessenger.ui.chat.avatarGradientFor(
                    ownNpub.ifBlank { "self" }
                )
            }
            val selfCtx = androidx.compose.ui.platform.LocalContext.current
            val selfAvatarRequest = remember(ownAvatarPath) {
                if (ownAvatarPath.isBlank()) null
                else coil3.request.ImageRequest.Builder(selfCtx)
                    .data(java.io.File(ownAvatarPath))
                    .build()
            }
            val themeVm: com.alphamessenger.ui.theme.ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val darkTheme by themeVm.isDark.collectAsState()
            val revealScope = androidx.compose.runtime.rememberCoroutineScope()
            var avatarCenter by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            Box {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(36.dp)
                        .onGloballyPositioned { avatarCenter = it.boundsInWindow().center }
                        .clip(CircleShape)
                        .avatarBackdrop(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(gradSelf.first, gradSelf.second)
                            )
                        )
                        .clickable(onClick = onProfileClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        com.alphamessenger.ui.components.avatarInitial(ownDisplayName),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (selfAvatarRequest != null) {
                        AsyncImage(
                            model = selfAvatarRequest,
                            contentDescription = stringResource(R.string.my_profile_cd),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                        )
                    }
                }
                // Custom popup card — DropdownMenu's internal Surface can't be
                // reshaped in this Compose version (its sharp corners poked
                // through the rounded clip), so we draw our own rounded Surface.
                if (profileMenuOpen) {
                    val dens = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.ui.window.Popup(
                        alignment = Alignment.TopEnd,
                        offset = androidx.compose.ui.unit.IntOffset(
                            x = with(dens) { (-8).dp.roundToPx() },
                            y = with(dens) { 46.dp.roundToPx() },
                        ),
                        onDismissRequest = onProfileMenuDismiss,
                        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
                    ) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = colors.bgSecondary,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.alphaBorder),
                            shadowElevation = 0.dp,
                            modifier = Modifier.width(208.dp),
                        ) {
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                ProfileMenuRow(AlphaIcons.Outlined.Search, stringResource(R.string.common_search), colors) {
                                    onProfileMenuDismiss(); onSearchClick()
                                }
                                ProfileMenuRow(AlphaIcons.Outlined.QrCode, stringResource(R.string.menu_my_npub_qr), colors) {
                                    onProfileMenuDismiss(); onShareNpub()
                                }
                                ProfileMenuRow(AlphaIcons.Outlined.Group, stringResource(R.string.menu_new_group), colors) {
                                    onProfileMenuDismiss(); onNewGroup()
                                }
                                ProfileMenuRow(AlphaIcons.Outlined.Contacts, stringResource(R.string.menu_contacts), colors) {
                                    onProfileMenuDismiss(); onOpenContacts()
                                }
                                ProfileMenuRow(com.alphamessenger.ui.icons.AlphaIcons.Filled.BookmarkBorder, stringResource(R.string.menu_saved), colors) {
                                    onProfileMenuDismiss(); onOpenSavedMessages()
                                }
                                ProfileMenuRow(AlphaIcons.Outlined.Archive, stringResource(R.string.menu_archive), colors) {
                                    onProfileMenuDismiss(); onOpenArchive()
                                }
                                ProfileMenuRow(AlphaIcons.Outlined.DoneAll, stringResource(R.string.menu_read_all), colors) {
                                    onProfileMenuDismiss(); onMarkAllRead()
                                }
                                ProfileMenuRow(
                                    if (darkTheme) AlphaIcons.Outlined.Sun else AlphaIcons.Outlined.Moon,
                                    stringResource(if (darkTheme) R.string.menu_light_theme else R.string.menu_dark_theme), colors,
                                ) {
                                    onProfileMenuDismiss()
                                    val toDark = !darkTheme
                                    val act = selfCtx.findActivity()
                                    if (act == null) themeVm.toggleDayNight()
                                    else com.alphamessenger.ui.theme.ThemeReveal.start(act, avatarCenter, revealScope) {
                                        themeVm.toggleDayNight()
                                        kotlinx.coroutines.withTimeoutOrNull(1000) { themeVm.isDark.first { it == toDark } }
                                    }
                                }
                                ProfileMenuRow(AlphaIcons.Outlined.Settings, stringResource(R.string.settings_title), colors) {
                                    onProfileMenuDismiss(); onSettings()
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** One row in the avatar profile popup — icon + label, comfortably padded. */
@Composable
private fun ProfileMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = colors.textPrimary, fontSize = 15.sp)
    }
}

// --- Body — conversation list or empty state ---

@Composable
private fun Body(
    modifier: Modifier,
    tab: ChatListViewModel.BottomTab,
    chats: List<ChatInfo>,
    groups: List<ChatInfo>,
    channels: List<ChatInfo>,
    onCreateChannel: () -> Unit,
    onChatClick: (String) -> Unit,
    onLongClick: (ChatInfo) -> Unit,
    onAvatarClick: (ChatInfo) -> Unit,
    onComposeClick: () -> Unit,
    onCreateGroup: () -> Unit,
    colors: AlphaColorScheme,
    peerByChat: Map<String, com.alphamessenger.ui.chatlist.ChatPeerSnapshot> = emptyMap(),
    presenceByChat: Map<String, String> = emptyMap(),
    statusByChat: Map<String, String> = emptyMap(),
    statusColorByChat: Map<String, Int> = emptyMap(),
    profileTickByChat: Map<String, Int> = emptyMap(),
    draftByChat: Map<String, String> = emptyMap(),
    /** Recipients gathered for the Share: their avatars get a checkmark. */
    shareSelectedIds: Set<String> = emptySet(),
    // Calls tab selection state.
    callSelectedIds: Set<String> = emptySet(),
    onCallSelectionChange: (Set<String>) -> Unit = {},
) {
    when (tab) {
        ChatListViewModel.BottomTab.CHATS -> ConversationList(
            modifier = modifier,
            items = chats,
            emptyMessage = stringResource(R.string.empty_chats),
            emptyAction = stringResource(R.string.new_chat_action) to onComposeClick,
            onClick = onChatClick,
            onLongClick = onLongClick,
            onAvatarClick = onAvatarClick,
            colors = colors,
            peerByChat = peerByChat,
            presenceByChat = presenceByChat,
            statusByChat = statusByChat,
            statusColorByChat = statusColorByChat,
            profileTickByChat = profileTickByChat,
            draftByChat = draftByChat,
            selectedIds = shareSelectedIds,
        )
        ChatListViewModel.BottomTab.GROUPS -> ConversationList(
            modifier = modifier,
            items = groups,
            emptyMessage = stringResource(R.string.empty_groups),
            emptyAction = stringResource(R.string.create_group_action) to onCreateGroup,
            onClick = onChatClick,
            onLongClick = onLongClick,
            onAvatarClick = onAvatarClick,
            colors = colors,
            peerByChat = peerByChat,
            presenceByChat = presenceByChat,
            draftByChat = draftByChat,
            selectedIds = shareSelectedIds,
        )
        ChatListViewModel.BottomTab.CHANNELS -> ConversationList(
            modifier = modifier,
            items = channels,
            emptyMessage = stringResource(R.string.channels_empty),
            emptyAction = stringResource(R.string.channel_new_title) to onCreateChannel,
            onClick = onChatClick,
            onLongClick = onLongClick,
            onAvatarClick = { onChatClick(it.id) },
            colors = colors,
            peerByChat = peerByChat,
            presenceByChat = presenceByChat,
            draftByChat = draftByChat,
            selectedIds = shareSelectedIds,
        )
        ChatListViewModel.BottomTab.STORIES -> Unit // drawn by StoriesTab above
        ChatListViewModel.BottomTab.CALLS -> com.alphamessenger.calls.ui.CallsHistoryTab(
            modifier = modifier,
            colors = colors,
            onOpenChat = onChatClick,
            selectedIds = callSelectedIds,
            onSelectionChange = onCallSelectionChange,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationList(
    modifier: Modifier,
    items: List<ChatInfo>,
    emptyMessage: String,
    emptyAction: Pair<String, () -> Unit>,
    onClick: (String) -> Unit,
    onLongClick: (ChatInfo) -> Unit,
    onAvatarClick: (ChatInfo) -> Unit,
    colors: AlphaColorScheme,
    peerByChat: Map<String, com.alphamessenger.ui.chatlist.ChatPeerSnapshot> = emptyMap(),
    presenceByChat: Map<String, String> = emptyMap(),
    statusByChat: Map<String, String> = emptyMap(),
    statusColorByChat: Map<String, Int> = emptyMap(),
    profileTickByChat: Map<String, Int> = emptyMap(),
    draftByChat: Map<String, String> = emptyMap(),
    /** Checked rows (Share recipient selection). */
    selectedIds: Set<String> = emptySet(),
) {
    val requestsBanner = LocalRequestsBanner.current
    if (items.isEmpty()) {
        // Not read yet (right after unlocking): nothing, rather than a flash of
        // "no chats — New dialog" before the real list arrives.
        if (requestsBanner != null) androidx.compose.foundation.layout.Column(
            modifier.fillMaxSize().padding(top = 4.dp + LocalListTopInset.current),
        ) {
            requestsBanner()
            if (LocalChatsLoaded.current) EmptyState(Modifier.weight(1f), emptyMessage, emptyAction.first, emptyAction.second, colors)
        } else if (LocalChatsLoaded.current) EmptyState(modifier, emptyMessage, emptyAction.first, emptyAction.second, colors)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp + LocalListTopInset.current, bottom = 4.dp + LocalDockInset.current),
    ) {
        requestsBanner?.let { banner -> item(key = "__requests_banner__") { banner() } }
        items(items, key = { it.id }) { chat ->
            ConversationRow(
                chat = chat,
                onClick = {
                    val snap = peerByChat[chat.id]
                    com.alphamessenger.ui.chat.ChatOpenHandoff.offer(
                        com.alphamessenger.ui.chat.ChatOpenHandoff.Preview(
                            chatInfo = chat,
                            peerDisplayName = snap?.displayName.orEmpty(),
                            peerAvatarPath = snap?.avatarPath.orEmpty(),
                        )
                    )
                    onClick(chat.id)
                },
                onLongClick = { onLongClick(chat) },
                onAvatarClick = { onAvatarClick(chat) },
                colors = colors,
                peer = peerByChat[chat.id],
                presence = presenceByChat[chat.id],
                status = statusByChat[chat.id],
                statusColor = statusColorByChat[chat.id],
                profileTick = profileTickByChat[chat.id] ?: 0,
                draft = draftByChat[chat.id],
                selected = chat.id in selectedIds,
                modifier = Modifier.animateItemPlacement(),
            )
        }
    }
}

/** The chat lists were read at least once (an empty list is then really empty). */
private val LocalChatsLoaded = androidx.compose.runtime.compositionLocalOf { true }

/** The "Requests" row above the chats of the Chats tab; null when there are none. */
private val LocalRequestsBanner = androidx.compose.runtime.compositionLocalOf<(@Composable () -> Unit)?> { null }

@Composable
private fun RequestsBanner(
    requests: List<ChatInfo>,
    peerByChat: Map<String, com.alphamessenger.ui.chatlist.ChatPeerSnapshot>,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    val names = requests.take(3).map { c ->
        peerByChat[c.id]?.displayName?.takeIf { it.isNotBlank() } ?: c.name?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.requests_unknown)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AlphaIcons.Outlined.MarkEmailUnread, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.requests_title), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(names.joinToString(", "), color = colors.textMuted, fontSize = 13.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Box(
            Modifier.clip(CircleShape).background(colors.accent).padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) { Text(requests.size.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
    if (TabDockPrefs.dividers) HorizontalDivider(thickness = 0.5.dp, color = colors.alphaBorder, modifier = Modifier.padding(start = 70.dp))
}

/**
 * My contacts — people whose personal chat I started or accepted — and the
 * ones I took out of contacts (their chat stays). Contacts are who the privacy
 * rules mean by "My contacts": calls, "last seen", photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyContactsSheet(
    contacts: List<ChatInfo>,
    others: List<ChatInfo>,
    peerByChat: Map<String, com.alphamessenger.ui.chatlist.ChatPeerSnapshot>,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onSetContact: (ChatInfo, Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<ChatInfo?>(null) }
    fun nameOf(c: ChatInfo) = peerByChat[c.id]?.displayName?.takeIf { it.isNotBlank() } ?: c.name.orEmpty()
    val q = query.trim()
    val shown = contacts.filter { q.isEmpty() || nameOf(it).contains(q, ignoreCase = true) }.sortedBy { nameOf(it).lowercase() }
    val shownOthers = others.filter { q.isEmpty() || nameOf(it).contains(q, ignoreCase = true) }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.Outlined.Contacts, null, tint = colors.accent)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.contacts_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary, modifier = Modifier.weight(1f))
                Text(contacts.size.toString(), color = colors.textMuted, fontSize = 15.sp)
            }
            Text(
                stringResource(R.string.contacts_note), color = colors.textMuted, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp)).background(colors.bgTertiary).padding(horizontal = 16.dp, vertical = 11.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(stringResource(R.string.contacts_search), color = colors.textMuted, fontSize = 15.sp)
                    inner()
                },
            )
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                item(key = "__add__") {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        SheetAction(AlphaIcons.Outlined.PersonAdd, stringResource(R.string.contacts_add),
                            stringResource(R.string.fab_chat_desc), colors, onAdd)
                        SheetAction(AlphaIcons.Outlined.QrCodeScanner, stringResource(R.string.fab_scan_qr),
                            stringResource(R.string.fab_scan_desc), colors, onScan)
                    }
                }
                if (shown.isEmpty()) item(key = "__empty__") {
                    Text(stringResource(R.string.contacts_empty), color = colors.textMuted, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                }
                items(shown, key = { it.id }) { chat ->
                    ConversationRow(
                        chat = chat, colors = colors, peer = peerByChat[chat.id],
                        onClick = { onOpen(chat.id) }, onLongClick = { confirm = chat },
                    )
                }
                if (shownOthers.isNotEmpty()) {
                    item(key = "__others__") {
                        Text(stringResource(R.string.contacts_others), color = colors.accent, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp))
                    }
                    items(shownOthers, key = { "o_" + it.id }) { chat ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                ConversationRow(chat = chat, colors = colors, peer = peerByChat[chat.id],
                                    onClick = { onOpen(chat.id) }, onLongClick = { onOpen(chat.id) })
                            }
                            androidx.compose.material3.TextButton(onClick = { onSetContact(chat, true) }) {
                                Text(stringResource(R.string.contact_restore), color = colors.accent, fontSize = 13.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.contact_remove_title, nameOf(c)), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.contact_remove_body), color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onSetContact(c, false); confirm = null }) {
                    Text(stringResource(R.string.contact_remove), color = colors.danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.accent)
                }
            },
        )
    }
}

/** Requests: who wrote to us without being a contact; open one to answer it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestsSheet(
    chats: List<ChatInfo>,
    channelIds: Set<String>,
    peerByChat: Map<String, com.alphamessenger.ui.chatlist.ChatPeerSnapshot>,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onChatClick: (String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.Outlined.MarkEmailUnread, null, tint = colors.accent)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.requests_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (chats.isNotEmpty()) androidx.compose.material3.TextButton(onClick = onDeleteAll) {
                    Text(stringResource(R.string.requests_delete_all), color = colors.danger, fontSize = 14.sp)
                }
            }
            Text(
                stringResource(R.string.requests_note),
                color = colors.textMuted, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(chats, key = { it.id }) { chat ->
                    // What is asking: a channel, a group or a person.
                    Text(
                        stringResource(when {
                            chat.id in channelIds -> R.string.picker_kind_channel
                            chat.chatType == "group" -> R.string.picker_kind_group
                            else -> R.string.private_chat
                        }),
                        color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 24.dp, top = 8.dp),
                    )
                    ConversationRow(
                        chat = chat,
                        onClick = { onChatClick(chat.id) },
                        onLongClick = { onChatClick(chat.id) },
                        colors = colors,
                        peer = peerByChat[chat.id],
                    )
                }
            }
        }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && (mod100 < 10 || mod100 >= 20) -> few
        else -> many
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    chat: ChatInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAvatarClick: () -> Unit = onClick,
    colors: AlphaColorScheme,
    peer: com.alphamessenger.ui.chatlist.ChatPeerSnapshot? = null,
    /** Live activity label ("typing…") — overrides the preview when set. */
    presence: String? = null,
    /** Online presence status ("online"/"away"/"busy"/"offline") for the dot. */
    status: String? = null,
    /** Peer's custom indicator colour (ARGB int; 0/null = none). */
    statusColor: Int? = null,
    /** Bumped on each ProfileUpdate — cache-bust key for the avatar (file path
     *  is reused). */
    profileTick: Int = 0,
    /** Unsent draft text — shown as "Draft: …" when no one is typing. */
    draft: String? = null,
    /** Marked as a Share recipient — checkmark on the avatar. */
    selected: Boolean = false,
    /** The search query: its words are marked in the name. */
    highlight: String? = null,
    /** Modifier for the row container (used by animateItemPlacement). */
    modifier: Modifier = Modifier,
) {
    // 8.8 v2 priority for direct chats: peer-broadcast display_name >
    // local chat.name (e.g. "npub…ab12") > generic placeholder.
    val title = when {
        // Saved messages: named in the app's language (the core stores one name).
        chat.id == "saved_messages_local" -> stringResource(R.string.menu_saved)
        com.alphamessenger.assistant.isAssistantChat(chat.id) -> com.alphamessenger.assistant.assistantNameOf(chat.id)
            ?: chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.assistant_title)
        peer?.displayName?.isNotBlank() == true -> peer.displayName
        chat.name?.isNotBlank() == true -> chat.name!!
        chat.chatType == "direct" -> stringResource(R.string.private_chat)
        chat.chatType == "group" -> stringResource(R.string.group_chat)
        else -> stringResource(R.string.dialog_label)
    }
    val initial = com.alphamessenger.ui.components.avatarInitial(title)
    // For 1:1 chats: peer's avatar from ProfileUpdate. For groups:
    // the admin-broadcast group avatar lives on chat.avatarPath directly.
    val effectiveAvatarPath: String = peer?.avatarPath?.takeIf { it.isNotBlank() } ?: chat.avatarPath
    val rowCtx = androidx.compose.ui.platform.LocalContext.current
    val avatarRequest = androidx.compose.runtime.remember(effectiveAvatarPath, profileTick) {
        if (effectiveAvatarPath.isBlank()) null
        else coil3.request.ImageRequest.Builder(rowCtx)
            .data(java.io.File(effectiveAvatarPath))
            .memoryCacheKey("$effectiveAvatarPath#$profileTick")
            .diskCacheKey("$effectiveAvatarPath#$profileTick")
            .build()
    }
    val now = remember { System.currentTimeMillis() / 1000 }
    val muted = chat.mutedUntil > 0u && chat.mutedUntil.toLong() > now
    val pinned = chat.pinnedAt > 0u
    val hasUnread = chat.unreadCount > 0u || chat.unreadOverride
    // A live typing/recording signal takes over the preview line; otherwise an
 // unsent draft does.
    val isActive = !presence.isNullOrBlank()
    val isDraft = !isActive && !draft.isNullOrBlank()
    val previewIcon = if (isActive || isDraft) null else previewIconFor(chat)
    val draftPrefix = stringResource(R.string.draft_prefix)
    val previewText: androidx.compose.ui.text.AnnotatedString = when {
        isActive -> androidx.compose.ui.text.AnnotatedString(presence!!)
        isDraft -> androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFFE53935)))
            append(draftPrefix)
            pop()
            append(draft!!.replace("\n", " ").trim())
        }
        else -> androidx.compose.ui.text.AnnotatedString(previewTextFor(chat))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val isSavedMessages = chat.id == "saved_messages_local"
        val isGroup = chat.chatType == "group"
        val avatarGrad = remember(chat.id) { com.alphamessenger.ui.chat.avatarGradientFor(chat.id) }
        val storyRing = com.alphamessenger.ui.stories.LocalStoryRings.current[chat.id]
        Box(modifier = Modifier.size(52.dp)) {
            if (storyRing != null) com.alphamessenger.ui.stories.StoryRingOverlay(storyRing, Modifier.matchParentSize())
            Box(
                modifier = Modifier
                    .size(52.dp)
                    // With a story halo the avatar steps inside the ring.
                    .graphicsLayer { if (storyRing != null) { scaleX = 0.8f; scaleY = 0.8f } }
                    .clip(CircleShape)
                    .avatarBackdrop(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            if (isSavedMessages)
                                listOf(colors.accent, colors.accent.copy(alpha = 0.55f).compositeOver(Color(0xFF101418)))   // our accent, not other messengers blue
                            else
                                listOf(avatarGrad.first, avatarGrad.second)
                        )
                    )
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    com.alphamessenger.assistant.isAssistantChat(chat.id) -> com.alphamessenger.assistant.AssistantAvatar(52.dp)
                    isSavedMessages -> Icon(
                        imageVector = com.alphamessenger.ui.components.AlphaIcons.Vault,
                        contentDescription = stringResource(R.string.menu_saved),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                    else -> {
                        // Initial is the base layer (fallback while Coil loads / on
                        // failure); the avatar fades in over it once decoded.
                        // A group or channel without a picture: the ghost placeholder.
                        if (chat.chatType == "group") com.alphamessenger.ui.components.GroupAvatarPlaceholder(Modifier.matchParentSize())
                        else Text(
                            initial,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (avatarRequest != null) {
                            AsyncImage(
                                model = avatarRequest,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.matchParentSize().clip(CircleShape),
                            )
                        }
                    }
                }
            }
            // Status dot (1:1 only — not groups/Saved). Drawn OUTSIDE the clipped
            // avatar so it's fully visible at the bottom-right edge. Colour is
            // driven by live presence (kind 10777); hidden when offline/unknown.
            // Single unified rule: custom colour overrides presence colour.
            val dotColor = com.alphamessenger.ui.chat.statusDotColor(
                com.alphamessenger.ui.chat.profileStatusOf(status ?: "offline"),
                statusColor,
            )
            if (peer?.secret == true && !selected) {
                // A secret chat shows how it travels, never whether its peer is online.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.bgPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    com.alphamessenger.ui.components.OnionIcon(com.alphamessenger.ui.secret.SecretViolet, size = 13.dp)
                }
            } else if (!isSavedMessages && !isGroup && !selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(colors.bgPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
            // The Share-recipient mark sits where the status dot would be: two circles
            // in one corner would read as an error.
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colors.bgPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(16.dp).clip(CircleShape).background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AlphaIcons.Filled.Check,
                            contentDescription = stringResource(R.string.cd_selected_by),
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (com.alphamessenger.assistant.isAssistantChat(chat.id)) com.alphamessenger.assistant.BotMark(16.dp, Modifier.padding(end = 4.dp))
                Text(
                    text = if (highlight.isNullOrBlank()) androidx.compose.ui.text.AnnotatedString(title) else searchMarked(title, highlight, colors),
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (com.alphamessenger.assistant.isAssistantChat(chat.id)) com.alphamessenger.assistant.VerifiedBadge(16.dp, Modifier.padding(start = 4.dp))
                Spacer(Modifier.width(6.dp))
                if (pinned) {
                    Icon(
                        AlphaIcons.Filled.PushPin,
                        contentDescription = stringResource(R.string.cd_pinned),
                        tint = colors.textMuted,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (muted) {
                    Icon(
                        AlphaIcons.Filled.NotificationsOff,
                        contentDescription = stringResource(R.string.cd_muted),
                        tint = colors.textMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
 // classic status ticks for our own last message (clock /
                // ✓ / ✓✓). Hidden while a live typing/recording preview shows.
                val outStatus = if (!isActive && !isDraft && chat.lastMessageOutgoing) chat.lastMessageStatus else ""
                // The same mark and read colour as in the bubbles.
                com.alphamessenger.ui.chat.bubbles.tickStateOf(outStatus)?.let { state ->
                    com.alphamessenger.ui.chat.bubbles.TickMark(
                        state = state,
                        color = if (state == com.alphamessenger.ui.chat.bubbles.TickState.READ) {
                            com.alphamessenger.ui.chat.bubbles.readTickColor(colors.bgPrimary)
                        } else colors.textMuted,
                        description = "",
                    )
                    Spacer(Modifier.width(3.dp))
                }
                if (previewIcon != null) {
                    Icon(
                        imageVector = previewIcon,
                        contentDescription = null,
                        // Unplayed incoming voice note → accent colour (matches the
                        // in-bubble "unplayed" dot); otherwise muted.
                        tint = if (chat.lastMessageUnplayed) colors.accent else colors.textMuted,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = previewText,
                    color = if (isActive) colors.accent else colors.textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = relativeTimeFor(chat),
                color = if (hasUnread) colors.accent else colors.textMuted,
                fontSize = 12.sp,
                fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A message is waiting to be sent later in this chat.
                // Indicators end flush with the time's right edge: spacing only BETWEEN items.
                val heldAll by com.alphamessenger.data.HeldMessages.byChat.collectAsState()
                val showHeld = heldAll[chat.id]?.isNotEmpty() == true
                val showTimer = chat.ephemeralSeconds > 0u
                if (showHeld) {
                    Box(
                        modifier = Modifier.size(18.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AlphaIcons.Outlined.ScheduleSend,
                            contentDescription = stringResource(R.string.schedule_list_title),
                            tint = colors.accent,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (showTimer || hasUnread) Spacer(Modifier.width(6.dp))
                }
                // Disappearing-messages timer indicator, left of the unread badge.
                if (showTimer) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(colors.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AlphaIcons.Outlined.Disappearing,
                            contentDescription = stringResource(R.string.cd_disappearing),
                            tint = colors.accent,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (hasUnread) Spacer(Modifier.width(6.dp))
                }
                if (hasUnread) {
                    UnreadBadge(
                        count = chat.unreadCount.toInt(),
                        muted = muted,
                        colors = colors,
                    )
                } else {
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
    if (TabDockPrefs.dividers) HorizontalDivider(
        thickness = 0.5.dp,
        color = colors.alphaBorder,
        modifier = Modifier.padding(start = 80.dp),
    )
}

@Composable
private fun UnreadBadge(count: Int, muted: Boolean, colors: AlphaColorScheme) {
    val text = when {
        count <= 0 -> "•"
        count > 999 -> "999+"
        else -> count.toString()
    }
    // Fixed square size ensures a perfect circle after CircleShape clip.
    // Single-digit: 22dp, double-digit / "99+": 26dp.
    val size = if (text.length > 1) 26.dp else 22.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (muted) colors.textMuted else colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.bgPrimary,
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = androidx.compose.ui.text.TextStyle(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}

private fun previewIconFor(chat: ChatInfo): androidx.compose.ui.graphics.vector.ImageVector? {
    return when (chat.lastMessageKind) {
        "image", "media", "album" -> AlphaIcons.Outlined.Image
        "video", "round" -> AlphaIcons.Outlined.Videocam
        "gif" -> AlphaIcons.Outlined.Gif
        "voice", "disappearing" -> AlphaIcons.Outlined.Mic
        "audio" -> AlphaIcons.Outlined.MusicNote
        "file" -> AlphaIcons.Outlined.InsertDriveFile
        "location" -> AlphaIcons.Outlined.LocationOn
        "link" -> AlphaIcons.Outlined.Link
        "poll" -> null
        "reaction" -> null
        else -> null
    }
}

@Composable
private fun previewTextFor(chat: ChatInfo): String {
    var raw = chat.lastMessagePreview
    if (com.alphamessenger.assistant.isAssistantChat(chat.id)) {
        if (raw.startsWith(com.alphamessenger.assistant.AssistantMarks.ERROR)) return stringResource(R.string.assistant_preview_error)
        raw = com.alphamessenger.assistant.AssistantMarks.answerOf(raw)
    }
    // Forwarded last message: the content carries the invisible FWD marker
 //. Strip it so the preview doesn't show
    // control bytes, and remember it to prepend a "↪" forwarded hint below.
    val forwarded = raw.startsWith(com.alphamessenger.ui.chat.FWD_MARK)
    if (forwarded) raw = raw.substring(com.alphamessenger.ui.chat.FWD_MARK.length)
    // Formatting markers are not part of what the message says.
    raw = com.alphamessenger.ui.chat.text.Markup.plain(raw)
    // Resolve the typed label FIRST — a photo with no caption has a blank
    // preview but should still read stringResource(R.string.media_kind_photo), not the empty-chat fallback.
    val typed = when (chat.lastMessageKind) {
        "image", "media" -> if (raw.isBlank()) stringResource(R.string.media_kind_photo) else raw
        "album" -> if (raw.isBlank()) stringResource(R.string.preview_album) else raw
        "video" -> if (raw.isBlank()) stringResource(R.string.media_kind_video) else raw
        "gif" -> "GIF"
        "sticker" -> listOfNotNull(
            com.alphamessenger.ui.sticker.parseStickerCaption(raw)?.emoji?.takeIf { it.isNotBlank() },
            stringResource(R.string.sticker_label),
        ).joinToString(" ")
        "round" -> stringResource(R.string.round_label)
        "voice" -> stringResource(R.string.preview_voice)
        "whisper" -> stringResource(R.string.whisper_notification)
        "glance_image" -> stringResource(R.string.glance_preview_photo)
        "glance_video" -> stringResource(R.string.glance_preview_video)
        "audio" -> if (raw.isBlank()) stringResource(R.string.preview_audio) else raw
        "file" -> if (raw.isBlank()) stringResource(R.string.preview_file) else raw
        "location" -> stringResource(R.string.preview_location)
        "link" -> if (raw.isBlank()) stringResource(R.string.preview_link) else raw
        "poll" -> "📊 " + raw.ifBlank { stringResource(R.string.poll_label) }
        "reaction" -> if (raw.isBlank()) "" else stringResource(R.string.preview_reaction, raw)
        "edit" -> raw
        else -> raw
    }
    if (typed.isBlank()) {
        // Empty chat: a clean "no messages" line — never leak internal details
        // like the MLS epoch. Saved Messages shows no subtitle at all.
        return if (chat.id == "saved_messages_local") "" else stringResource(R.string.preview_none)
    }
    val prefix = if (chat.lastMessageOutgoing) stringResource(R.string.you_prefix) else ""
    val fwd = if (forwarded) "↪ " else ""
    return prefix + fwd + typed
}

@Composable
private fun relativeTimeFor(chat: ChatInfo): String = listTime(chat.lastMessageAt.ifBlank { chat.createdAt })

/** A list row's time: today HH:mm, yesterday, a date this year, else a full date. */
@Composable
internal fun listTime(rfc: String): String {
    if (rfc.isBlank()) return ""
    // Convert to the DEVICE-LOCAL zone (the raw string is UTC — slicing it
    // showed the wrong, UTC time). Today → HH:mm, yesterday → stringResource(R.string.common_yesterday), older → date.
    return runCatching {
        val local = java.time.OffsetDateTime.parse(rfc)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val d = local.toLocalDate()
        when {
            d == today -> local.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            d == today.minusDays(1) -> stringResource(R.string.common_yesterday)
            d.year == today.year -> local.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
            else -> local.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy"))
        }
    }.getOrElse {
        val idx = rfc.indexOf('T')
        if (idx in 0..rfc.length - 6) rfc.substring(idx + 1, idx + 6) else ""
    }
}

// --- Empty state ---

@Composable
private fun EmptyState(
    modifier: Modifier,
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    colors: AlphaColorScheme,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = LocalListTopInset.current)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = colors.textSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.chats_mls_note),
            color = colors.textMuted,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAction,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.bgPrimary,
            ),
        ) {
            Icon(AlphaIcons.Outlined.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(actionLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CallsEmpty(modifier: Modifier, colors: AlphaColorScheme) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = LocalListTopInset.current)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            AlphaIcons.Outlined.Phone,
            null,
            tint = colors.textMuted,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.calls_soon),
            color = colors.textSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.calls_tech_note),
            color = colors.textMuted,
            fontSize = 13.sp,
        )
    }
}

// --- Add Contact bottom sheet ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactSheet(
    value: String,
    onValueChange: (String) -> Unit,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    colors: AlphaColorScheme,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = { if (!isLoading) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp)
                .imePadding(),
        ) {
            Text(
                stringResource(R.string.new_chat_title),
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.new_chat_hint),
                color = colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.npub_hex_label)) },
                placeholder = { Text("npub1…", color = colors.textMuted) },
                singleLine = true,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderLight,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedLabelColor = colors.accent,
                    unfocusedLabelColor = colors.textMuted,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isLoading,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.textSecondary,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderLight),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = value.trim().isNotBlank() && !isLoading,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.bgPrimary,
                        disabledContainerColor = colors.bgTertiary,
                        disabledContentColor = colors.textMuted,
                    ),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.bgPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.searching_key))
                    } else {
                        Text(stringResource(R.string.common_add))
                    }
                }
            }
        }
    }
}

// --- New Group bottom sheet ---

private data class GroupFriend(val npub: String, val name: String, val avatarPath: String)
private data class ContactRow(
    val chatId: String,
    val name: String,
    val avatarPath: String,
    val npub: String,
    /** The peer takes voice / video calls from us (their privacy rules). */
    val voice: Boolean = true,
    val video: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/** "New call": only the people who take calls from us, with the kinds they take. */
private fun ContactsSheet(
    /** null while their rules are being read. */
    contacts: List<ContactRow>?,
    onDismiss: () -> Unit,
    onCall: (String, com.alphamessenger.calls.CallType) -> Unit,
    colors: AlphaColorScheme,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 12.dp)
                .imePadding(),
        ) {
            Text(stringResource(R.string.call_new_call), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_contacts_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                leadingIcon = { Icon(AlphaIcons.Outlined.Search, contentDescription = null, tint = colors.textMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderLight,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedLabelColor = colors.accent,
                    unfocusedLabelColor = colors.textMuted,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            val filtered = contacts.orEmpty().filter {
                query.isBlank() || it.name.contains(query, true) || it.npub.contains(query, true)
            }
            if (contacts == null) {
                // Still reading who takes calls: nothing yet rather than a wrong list.
            } else if (filtered.isEmpty()) {
                Text(
                    if (contacts.isEmpty()) stringResource(R.string.call_nobody) else stringResource(R.string.nothing_found),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = colors.textMuted,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtered, key = { it.chatId }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onCall(c.chatId, if (c.voice) com.alphamessenger.calls.CallType.AUDIO else com.alphamessenger.calls.CallType.VIDEO)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GroupFriendAvatar(GroupFriend(c.npub, c.name, c.avatarPath), colors)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    c.name,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (c.npub.isNotBlank()) {
                                    Text(
                                        c.npub.take(18) + "…",
                                        color = colors.textMuted,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                            // Only the kinds of call this person takes.
                            if (c.voice) IconButton(onClick = { onCall(c.chatId, com.alphamessenger.calls.CallType.AUDIO) }) {
                                Icon(AlphaIcons.Outlined.Phone, contentDescription = stringResource(R.string.chat_voice_call), tint = colors.accent)
                            }
                            if (c.video) IconButton(onClick = { onCall(c.chatId, com.alphamessenger.calls.CallType.VIDEO) }) {
                                Icon(AlphaIcons.Outlined.Videocam, contentDescription = stringResource(R.string.chat_video_call), tint = colors.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupFriendAvatar(f: GroupFriend, colors: AlphaColorScheme) {
    if (f.avatarPath.isNotBlank()) {
        AsyncImage(
            model = java.io.File(f.avatarPath),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                com.alphamessenger.ui.components.avatarInitial(f.name),
                color = colors.accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewGroupSheet(
    value: String,
    onValueChange: (String) -> Unit,
    friends: List<GroupFriend>,
    onDismiss: () -> Unit,
    onConfirm: (members: List<String>, avatarUri: String?, description: String, hiddenOwner: Boolean, protect: Boolean, history: Boolean) -> Unit,
    colors: AlphaColorScheme,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var description by remember { mutableStateOf("") }
    var membersText by remember { mutableStateOf("") }
    val selectedNpubs = remember { mutableStateListOf<String>() }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    var hiddenOwner by remember { mutableStateOf(false) }
    var protect by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> if (uri != null) avatarUri = uri }
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().imePadding()
                .padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 16.dp),
        ) {
            Text(stringResource(R.string.menu_new_group), color = colors.textPrimary, fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(colors.bgSecondary)
                        .border(1.dp, colors.alphaBorder, RoundedCornerShape(24.dp))
                        .clickable { avatarLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    val picked = avatarUri
                    if (picked != null) AsyncImage(picked, stringResource(R.string.group_photo_cd),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(72.dp))
                    else Icon(AlphaIcons.Outlined.AddAPhoto, stringResource(R.string.add_group_photo_cd), tint = colors.accent, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetField(value, { onValueChange(it.take(64)) }, stringResource(R.string.group_name_hint), colors)
                    SheetField(description, { description = it.take(255) }, stringResource(R.string.channel_desc_hint), colors)
                }
            }

            com.alphamessenger.ui.components.OptionSection(stringResource(R.string.group_members_section))
            com.alphamessenger.ui.components.OptionGroup {
                friends.forEachIndexed { i, f ->
                    if (i > 0) com.alphamessenger.ui.components.OptionDivider()
                    val checked = selectedNpubs.contains(f.npub)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (checked) selectedNpubs.remove(f.npub) else selectedNpubs.add(f.npub) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GroupFriendAvatar(f, colors)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, color = colors.textPrimary, fontSize = 15.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (checked) Icon(AlphaIcons.Filled.CheckCircle, stringResource(R.string.cd_chosen), tint = colors.accent)
                        else Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, colors.borderLight, CircleShape))
                    }
                }
                if (friends.isNotEmpty()) com.alphamessenger.ui.components.OptionDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (membersText.isEmpty()) Text(stringResource(R.string.group_npub_hint), color = colors.textMuted, fontSize = 14.sp)
                    androidx.compose.foundation.text.BasicTextField(
                        membersText, { membersText = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
                    )
                }
            }
            val typed = membersText.lines().map { it.trim() }.filter { it.isNotBlank() }
            val total = (selectedNpubs.toList() + typed).distinct().size
            if (total > 0) Text(stringResource(R.string.invite_count, total), color = colors.textMuted, fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp))

            com.alphamessenger.ui.components.OptionSection(stringResource(R.string.channel_privacy_section))
            com.alphamessenger.ui.components.OptionGroup {
                com.alphamessenger.ui.components.OptionSwitchRow(stringResource(R.string.group_owner_hidden), stringResource(R.string.group_owner_hidden_hint),
                    checked = hiddenOwner, onCheckedChange = { hiddenOwner = it })
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionSwitchRow(stringResource(R.string.group_protect), stringResource(R.string.group_protect_hint),
                    checked = protect, onCheckedChange = { protect = it })
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionSwitchRow(stringResource(R.string.history_newcomers), stringResource(R.string.history_newcomers_group_hint),
                    checked = history, onCheckedChange = { history = it })
            }
            Spacer(Modifier.height(20.dp))
            val ready = value.isNotBlank() && !busy
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp))
                    .background(if (ready) colors.accent else colors.bgTertiary)
                    .clickable(enabled = ready) {
                        busy = true
                        onConfirm((selectedNpubs.toList() + typed).distinct(), avatarUri?.toString(), description, hiddenOwner, protect, history)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.group_create), color = if (ready) colors.bgPrimary else colors.textMuted,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SheetField(value: String, onChange: (String) -> Unit, hint: String, colors: AlphaColorScheme) {
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(colors.bgSecondary)
            .border(1.dp, colors.alphaBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = colors.textMuted, fontSize = 15.sp)
        androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent), modifier = Modifier.fillMaxWidth())
    }
}

// --- Avatar palette — classic flat single-colour circles ---

private val AvatarPalette = listOf(
    Color(0xFF6E80B5), // muted blue
    Color(0xFFB17BA6), // mauve
    Color(0xFF8FA98F), // sage
    Color(0xFFC1834B), // terracotta
    Color(0xFF7AA89F), // teal-grey
    Color(0xFFB9836E), // clay
    Color(0xFF8C81B0), // lavender
    Color(0xFF9BAA73), // olive
)

private fun avatarColor(seed: String, @Suppress("UNUSED_PARAMETER") colors: AlphaColorScheme): Color {
    val h = seed.hashCode().let { if (it < 0) -it else it }
    return AvatarPalette[h % AvatarPalette.size]
}

// FAB actions sheet
// Tab-aware: shows "New chat / QR / Share / Copy / Manual"
// for the Chats tab and "Create group / Share / Add participant"
// for the Groups tab.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FabActionsSheet(
    tab: ChatListViewModel.BottomTab,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onAddContact: () -> Unit,
    onCreateGroup: () -> Unit,
    onShareNpub: () -> Unit,
    onCopyNpub: () -> Unit,
    onScanQr: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            val title = when (tab) {
                ChatListViewModel.BottomTab.GROUPS -> stringResource(R.string.tab_groups)
                ChatListViewModel.BottomTab.CALLS -> stringResource(R.string.tab_actions)
                else -> stringResource(R.string.tab_chats)
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Spacer(Modifier.height(12.dp))
            when (tab) {
                ChatListViewModel.BottomTab.GROUPS -> {
                    SheetAction(AlphaIcons.Outlined.GroupAdd, stringResource(R.string.create_group_action),
                        stringResource(R.string.fab_group_desc), colors, onCreateGroup)
                }
                else -> {
                    SheetAction(AlphaIcons.Outlined.Edit, stringResource(R.string.new_chat_title),
                        stringResource(R.string.fab_chat_desc), colors, onAddContact)
                    SheetAction(AlphaIcons.Outlined.QrCodeScanner, stringResource(R.string.fab_scan_qr),
                        stringResource(R.string.fab_scan_desc), colors, onScanQr)
                    SheetAction(AlphaIcons.Outlined.QrCode, stringResource(R.string.fab_share_qr),
                        stringResource(R.string.fab_share_desc), colors, onShareNpub)
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (destructive) Color(0x33EF4444) else colors.bgTertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null,
                tint = if (destructive) Color(0xFFEF4444) else colors.accent,
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title,
                color = if (destructive) Color(0xFFEF4444) else colors.textPrimary,
                fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = colors.textMuted, fontSize = 12.sp)
            }
        }
    }
}

// --- Long-press chat actions sheet ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatActionsSheet(
    chat: ChatInfo,
    displayName: String,
    avatarPath: String,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onFolder: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pinned = chat.pinnedAt > 0u
    val nowSec = remember { System.currentTimeMillis() / 1000 }
    val muted = chat.mutedUntil > 0u && chat.mutedUntil.toLong() > nowSec
    val archived = chat.archivedAt > 0u
    val hasUnread = chat.unreadCount > 0u || chat.unreadOverride

    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            // Who this is: avatar + name (never a key).
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (com.alphamessenger.assistant.isAssistantChat(chat.id)) com.alphamessenger.assistant.AssistantAvatar(44.dp)
                else com.alphamessenger.ui.stories.StoryAvatar(avatarPath, chat.id, com.alphamessenger.ui.components.avatarInitial(displayName), 44)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(displayName, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        if (chat.chatType == "group") stringResource(R.string.group_chat) else stringResource(R.string.private_chat),
                        fontSize = 13.sp, color = colors.textMuted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SheetAction(
                icon = if (pinned) AlphaIcons.Filled.PushPin else AlphaIcons.Outlined.PushPin,
                title = if (pinned) stringResource(R.string.unpin_action) else stringResource(R.string.pin_action),
                subtitle = if (pinned) stringResource(R.string.unpin_desc) else stringResource(R.string.pin_desc),
                colors = colors,
                onClick = onPin,
            )
            SheetAction(
                icon = AlphaIcons.Outlined.Folder,
                title = stringResource(R.string.folders_add_to),
                subtitle = stringResource(R.string.folders_add_to_desc),
                colors = colors,
                onClick = onFolder,
            )
            SheetAction(
                icon = if (muted) AlphaIcons.Outlined.VolumeUp else AlphaIcons.Outlined.NotificationsOff,
                title = if (muted) stringResource(R.string.unmute_action) else stringResource(R.string.cd_muted),
                subtitle = if (muted) stringResource(R.string.unmute_desc) else stringResource(R.string.mute_desc),
                colors = colors,
                onClick = onMute,
            )
            SheetAction(
                icon = if (hasUnread) AlphaIcons.Outlined.MarkChatRead else AlphaIcons.Outlined.MarkChatUnread,
                title = if (hasUnread) stringResource(R.string.mark_read) else stringResource(R.string.mark_unread),
                subtitle = "",
                colors = colors,
                onClick = if (hasUnread) onMarkRead else onMarkUnread,
            )
            SheetAction(
                icon = if (archived) AlphaIcons.Outlined.Unarchive else AlphaIcons.Outlined.Archive,
                title = if (archived) stringResource(R.string.unarchive_action) else stringResource(R.string.archive_action),
                subtitle = if (archived) stringResource(R.string.unarchive_desc) else stringResource(R.string.archive_desc),
                colors = colors,
                onClick = onArchive,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 0.5.dp,
                color = colors.alphaBorder,
            )
            SheetAction(
                icon = AlphaIcons.Outlined.Delete,
                title = stringResource(R.string.delete_chat_action),
                subtitle = stringResource(R.string.delete_chat_desc),
                colors = colors,
                onClick = onDelete,
                destructive = true,
            )
        }
    }
}

// --- Mute duration picker sub-sheet ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuteDurationSheet(
    isMuted: Boolean,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.cd_muted), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary)
            Spacer(Modifier.height(12.dp))
            if (isMuted) {
                SheetAction(AlphaIcons.Outlined.VolumeUp, stringResource(R.string.unmute_action),
                    stringResource(R.string.restore_notifications), colors, { onPick(0L) })
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp),
                    thickness = 0.5.dp, color = colors.alphaBorder)
            }
            SheetAction(AlphaIcons.Outlined.NotificationsOff, stringResource(R.string.mute_for_1h), "", colors, { onPick(3600) })
            SheetAction(AlphaIcons.Outlined.NotificationsOff, stringResource(R.string.mute_for_8h), "", colors, { onPick(28_800) })
            SheetAction(AlphaIcons.Outlined.NotificationsOff, stringResource(R.string.mute_for_1d), "", colors, { onPick(86_400) })
            SheetAction(AlphaIcons.Outlined.NotificationsOff, stringResource(R.string.mute_for_1w), "", colors, { onPick(604_800) })
            SheetAction(AlphaIcons.Outlined.NotificationsOff, stringResource(R.string.mute_forever),
                stringResource(R.string.mute_forever_desc), colors, { onPick(Long.MAX_VALUE) })
        }
    }
}

// Archive sheet — list of archived chats. Tap opens the chat; long-press
// → ChatActionsSheet which lets the user unarchive.

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ArchiveSheet(
    chats: List<ChatInfo>,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onChatClick: (String) -> Unit,
    onLongClick: (ChatInfo) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.Outlined.Archive, null, tint = colors.accent)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.menu_archive), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary)
            }
            if (chats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.archive_empty), color = colors.textMuted, fontSize = 14.sp)
                }
            } else {
                Text(
                    stringResource(R.string.archive_hint),
                    color = colors.textMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(chats, key = { it.id }) { chat ->
                        ConversationRow(
                            chat = chat,
                            onClick = { onChatClick(chat.id) },
                            onLongClick = { onLongClick(chat) },
                            colors = colors,
                        )
                    }
                }
            }
        }
    }
}

// --- Avatar lightbox (tap on the chat row's avatar — classic preview) ---

@Composable
private fun AvatarPreviewDialog(
    chat: ChatInfo,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onOpenChat: () -> Unit,
    peer: com.alphamessenger.ui.chatlist.ChatPeerSnapshot? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val title = when {
        // Saved messages: named in the app's language (the core stores one name).
        chat.id == "saved_messages_local" -> stringResource(R.string.menu_saved)
        com.alphamessenger.assistant.isAssistantChat(chat.id) -> com.alphamessenger.assistant.assistantNameOf(chat.id)
            ?: chat.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.assistant_title)
        peer?.displayName?.isNotBlank() == true -> peer.displayName
        chat.name?.isNotBlank() == true -> chat.name!!
        chat.chatType == "group" -> stringResource(R.string.group_chat)
        else -> stringResource(R.string.private_chat)
    }
    val initial = com.alphamessenger.ui.components.avatarInitial(title)
    val npub = peer?.npub.orEmpty()

    // Peer avatar (direct) or group avatar (admin broadcast) wins over
    // the gradient placeholder.
    val previewAvatarPath: String = peer?.avatarPath?.takeIf { it.isNotBlank() } ?: chat.avatarPath
    val previewBitmap = androidx.compose.runtime.remember(previewAvatarPath) {
        if (previewAvatarPath.isBlank()) null
        else runCatching {
            val f = java.io.File(previewAvatarPath)
            if (f.exists() && f.length() > 0L) {
                com.alphamessenger.data.MediaVault.decodeFile(previewAvatarPath)?.asImageBitmap()
            } else null
        }.getOrNull()
    }
    var fullscreenOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    // Swallow clicks here so tapping the avatar / npub box
                    // doesn't bubble up to the outer Box's dismiss handler.
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                // Circular preview — the peer's real JPEG (full-fit, not
                // crop) wins over the deterministic gradient placeholder.
                val previewGrad = remember(chat.id) { com.alphamessenger.ui.chat.avatarGradientFor(chat.id) }
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(CircleShape)
                        .avatarBackdrop(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(previewGrad.first, previewGrad.second)
                            )
                        )
                        .clickable(enabled = previewBitmap != null) { fullscreenOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = previewBitmap,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(CircleShape),
                        )
                    } else if (chat.chatType == "group") {
                        com.alphamessenger.ui.components.GroupAvatarPlaceholder(Modifier.matchParentSize())
                    } else {
                        Text(
                            initial,
                            color = Color.White,
                            fontSize = 96.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (chat.chatType == "group") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.group_mls),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                } else if (chat.id != "saved_messages_local" && npub.isNotBlank()) {
                    // --- npub + Copy button in a single horizontal row ---
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.text.selection.SelectionContainer(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = npub,
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = 14.sp,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText("npub", npub)
                                    )
                                    android.widget.Toast.makeText(
                                        ctx, ctx.getString(R.string.npub_copied_toast), android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = com.alphamessenger.ui.icons.AlphaIcons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.copy_npub_cd),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // --- Bottom action buttons: rectangular rounded with icons ---
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DialogActionButton(
                        modifier = Modifier.weight(1f),
                        icon = com.alphamessenger.ui.icons.AlphaIcons.Outlined.Close,
                        label = stringResource(R.string.common_close),
                        onClick = onDismiss,
                        primary = false,
                        accent = colors.accent,
                    )
                    DialogActionButton(
                        modifier = Modifier.weight(1f),
                        icon = com.alphamessenger.ui.icons.AlphaIcons.Outlined.Edit,
                        label = stringResource(R.string.open_chat_label),
                        onClick = onOpenChat,
                        primary = true,
                        accent = colors.accent,
                    )
                }
            }
        }
    }

    if (fullscreenOpen && previewAvatarPath.isNotBlank()) {
        com.alphamessenger.ui.chat.media.MediaViewer(
            collection = com.alphamessenger.ui.chat.media.MediaCollection.single(
                com.alphamessenger.ui.chat.media.MediaItem.avatar(previewAvatarPath),
            ),
            onDismiss = { fullscreenOpen = false },
            onDownload = {},
        )
    }
}

/** Rectangular rounded action button for the avatar dialog footer.
 *  Icon above label-ish: actually icon + label side by side, centered. */
@Composable
private fun DialogActionButton(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    accent: Color,
) {
    val bg = if (primary) accent else Color.White.copy(alpha = 0.12f)
    val fg = if (primary) Color.Black else Color.White
    // Icon on top, centered label below — prevents label wrapping.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Tap-to-zoom + pan fullscreen photo viewer. Pinch to scale 1×-5×,
 *  drag to pan when zoomed. Back gesture / X closes. */

/**
 * Strip above the list while an accepted Share waits for recipients.
 *
 * There is no dedicated recipient-picker screen: the list itself is the
 * picker. Tap sends to one, a long press gathers several — the strip shows
 * the counter and lets the user confirm the selection.
 */
@Composable
private fun ShareTargetBar(
    active: Boolean,
    selectedCount: Int,
    colors: com.alphamessenger.ui.theme.AlphaColorScheme,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!active) return
    // The strip's height is fixed: exactly two text lines, with the button
    // beside them — never pushing the label to a third line. Both lines are
    // single-line with ellipsis, so the panel doesn't "breathe" with the counter.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accent.copy(alpha = 0.12f))
            .height(58.dp)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = if (selectedCount > 0) stringResource(R.string.sel_count_title, selectedCount) else stringResource(R.string.select_chat),
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.multi_select_hint),
                color = colors.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AlphaIcons.Outlined.Close,
                contentDescription = if (selectedCount > 0) stringResource(R.string.clear_selection_cd) else stringResource(R.string.cancel_send_cd),
                tint = colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        if (selectedCount > 0) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = stringResource(R.string.send_to_count, selectedCount),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.accent)
                    .clickable(onClick = onConfirm)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}


/** The notification permission was already asked for in this process. */
private var notifAskedThisLaunch = false


private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
