package com.alphamessenger.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.alphamessenger.ui.chat.ChatScreen
import com.alphamessenger.ui.chat.ChatViewModel
import com.alphamessenger.ui.chatlist.ChatListScreen
import com.alphamessenger.ui.chatlist.ChatListViewModel
import com.alphamessenger.ui.contacts.ContactInfoScreen
import com.alphamessenger.ui.contacts.ContactInfoViewModel
import com.alphamessenger.ui.contacts.VerifyScreen
import com.alphamessenger.ui.contacts.VerifyViewModel
import com.alphamessenger.ui.onboarding.AppLockSetupScreen
import com.alphamessenger.ui.onboarding.OnboardingScreen
import com.alphamessenger.ui.onboarding.OnboardingViewModel
import com.alphamessenger.ui.onboarding.PermissionsScreen
import com.alphamessenger.ui.onboarding.ProfileSetupScreen
import com.alphamessenger.ui.onboarding.RecoveryPasswordScreen
import com.alphamessenger.ui.onboarding.RecoveryPasswordViewModel
import com.alphamessenger.ui.onboarding.RestoreScreen
import com.alphamessenger.ui.onboarding.RestoreViewModel
import com.alphamessenger.ui.onboarding.SeedBackupScreen
import com.alphamessenger.ui.settings.PrivacyScreen
import com.alphamessenger.ui.settings.SelfProfileScreen
import com.alphamessenger.ui.settings.BackupScreen
import com.alphamessenger.ui.settings.BackupViewModel
import com.alphamessenger.ui.settings.RelaysScreen
import com.alphamessenger.ui.settings.SettingsScreen
import com.alphamessenger.ui.settings.SettingsViewModel
import com.alphamessenger.ui.settings.StorageUsageScreen
import com.alphamessenger.ui.settings.StorageUsageViewModel
import com.alphamessenger.ui.settings.AutoDownloadScreen
import com.alphamessenger.ui.settings.ThemeSelectorScreen

// --- Route definitions — sealed hierarchy for type-safe navigation ---

sealed class AlphaRoute(val route: String) {
    // --- Onboarding flow ---
    object Onboarding : AlphaRoute("onboarding")
    object SeedBackup : AlphaRoute("seed_backup")
    object SeedVerify : AlphaRoute("seed_verify")
    object RecoveryPassword : AlphaRoute("recovery_password")
    object ProfileSetup : AlphaRoute("profile_setup")
    object Permissions : AlphaRoute("permissions")
    object AppLockSetup : AlphaRoute("app_lock_setup")
    object Restore : AlphaRoute("restore")
    object LinkPhone : AlphaRoute("link_phone")

    // --- Main screens ---
    object ChatList : AlphaRoute("chatlist")
    object Chat : AlphaRoute("chat/{chatId}?focus={focus}&thread={thread}&q={q}") {
        fun create(chatId: String) = "chat/$chatId"
        fun create(chatId: String, focusMessageId: String?) =
            if (focusMessageId.isNullOrBlank()) "chat/$chatId" else "chat/$chatId?focus=$focusMessageId"
        /** Opened from the search: at [messageId], with the in-chat search on [query]. */
        fun found(chatId: String, messageId: String, query: String) =
            if (query.isBlank()) create(chatId, messageId) else "chat/$chatId?focus=$messageId&q=" + android.net.Uri.encode(query)
        /** The discussion of channel post [postId] — the same chat screen, showing that thread. */
        fun thread(chatId: String, postId: String, focusMessageId: String? = null) =
            "chat/$chatId?thread=$postId" + if (focusMessageId.isNullOrBlank()) "" else "&focus=$focusMessageId"
    }
    object ContactInfo : AlphaRoute("contactinfo/{chatId}") {
        fun create(chatId: String) = "contactinfo/$chatId"
    }
    /** Stories: the player ("mine" or an author's chat id) and the editor. */
    object StoryView : AlphaRoute("story/view/{key}") {
        fun create(key: String) = "story/view/$key"
    }
    object StoryCreate : AlphaRoute("story/new")
    /** "Edit group" — the pencil of a group's info page (shares its ViewModel). */
    object GroupEdit : AlphaRoute("groupedit/{chatId}") {
        fun create(chatId: String) = "groupedit/$chatId"
    }
    object Verify : AlphaRoute("verify/{chatId}") {
        fun create(chatId: String) = "verify/$chatId"
    }

    // --- Settings ---
    object Settings : AlphaRoute("settings")
    object SetLanguage : AlphaRoute("settings_language")
    object Scheduled : AlphaRoute("scheduled/{chatId}") {
        fun create(chatId: String) = "scheduled/$chatId"
    }
    object Profile : AlphaRoute("profile")
    object SetRelays : AlphaRoute("settings/relays")
    object SetPrivacy : AlphaRoute("settings/privacy")
    object SetTheme : AlphaRoute("settings/theme")
    object SetStickers : AlphaRoute("settings/stickers")
    object Backup : AlphaRoute("settings/backup")
    object AppLock : AlphaRoute("settings/applock")
    object SetStorage : AlphaRoute("settings/storage")
    object SetFolders : AlphaRoute("settings/folders")
    object SetAutoDownload : AlphaRoute("settings/autodownload")
    object SetDevices : AlphaRoute("settings/devices")
    object Assistant : AlphaRoute("settings/assistant")
    object SetBrowser : AlphaRoute("settings/browser")
    object About : AlphaRoute("settings/about")
    object Legal : AlphaRoute("settings/about/{doc}") {
        fun create(doc: com.alphamessenger.ui.settings.LegalDoc) = "settings/about/${doc.name}"
    }
    object AssistantJournal : AlphaRoute("settings/assistant_journal")
    object AssistantProfile : AlphaRoute("settings/assistant/{id}") {
        fun create(id: String?) = "settings/assistant/" + (id ?: "new")
    }
    /** Export: the whole profile, or one chat. */
    object Export : AlphaRoute("export?chat={chat}") {
        fun profile() = "export"
        fun chat(chatId: String) = "export?chat=$chatId"
    }
}

// --- Animation constants ---

/**
 * Screen open and close, as measured on the reference: about 190 ms, opening a touch
 * quicker. The chat's first frame takes ~50 ms to build, so the curve starts gently —
 * a fast start would spend its movement inside that frame and nothing would be seen.
 */
private const val OPEN_MS = 180
private const val CLOSE_MS = 200
private val SCREEN_EASING = androidx.compose.animation.core.FastOutSlowInEasing

/** The screen underneath: a third of the way to the left, darkened. */
private const val UNDER_SHIFT = 0.3f
private const val UNDER_ALPHA = 0.65f

/** Pops back to the topmost entry [match] accepts; false when none is on the stack. */
private fun NavHostController.returnTo(match: (androidx.navigation.NavBackStackEntry) -> Boolean): Boolean {
    val stack = currentBackStack.value
    val i = stack.indexOfLast(match)
    if (i < 0) return false
    repeat(stack.size - 1 - i) { popBackStack() }
    return true
}

/**
 * A chat already open lower down is returned to, not opened again: going back and
 * forth between a chat, its info and its settings never piles up screens to walk back.
 */
private fun NavHostController.openChat(chatId: String, focus: String? = null) {
    val back = returnTo {
        it.destination.route == AlphaRoute.Chat.route && it.arguments?.getString("chatId") == chatId &&
            it.arguments?.getString("thread") == null
    }
    if (!back) navigate(AlphaRoute.Chat.create(chatId, focus))
    else if (focus != null) currentBackStackEntry?.savedStateHandle?.set("focus", focus)
}

/** The same for a chat's info page. */
private fun NavHostController.openInfo(chatId: String) {
    val back = returnTo { it.destination.route == AlphaRoute.ContactInfo.route && it.arguments?.getString("chatId") == chatId }
    if (!back) navigate(AlphaRoute.ContactInfo.create(chatId))
}

// --- NavHost — the complete navigation graph for AlphaMessenger ---

@Composable
fun AlphaNavHost(
    navController: NavHostController,
    startDestination: String,
    onOnboarded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val swipe = remember { EdgeSwipeState() }
    // Pull from the left edge = back, on every screen that has one to go back to
    // (not the main list, the first-run flow or a story).
    val noSwipe = remember {
        setOf(
            AlphaRoute.ChatList.route, AlphaRoute.StoryView.route, AlphaRoute.StoryCreate.route,
            AlphaRoute.Onboarding.route, AlphaRoute.SeedBackup.route, AlphaRoute.SeedVerify.route,
            AlphaRoute.RecoveryPassword.route, AlphaRoute.Permissions.route, AlphaRoute.AppLockSetup.route,
            AlphaRoute.Restore.route, AlphaRoute.LinkPhone.route,
        )
    }
    EdgeSwipeBack(
        navController = navController,
        state = swipe,
        enabled = {
            navController.previousBackStackEntry != null &&
                navController.currentDestination?.route !in noSwipe
        },
    ) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.background(androidx.compose.ui.graphics.Color.Black),
        // Stories open over the screen (fade + grow) and close by their own
        // animation (bars up / down); the screen under them does not move.
        // Every other screen comes in from the right edge over the whole width while the
        // one under it drifts a third to the left and darkens (it sits on black); closing
        // plays it back — the edge swipe back, played the other way.
        enterTransition = {
            if (targetState.destination.route == AlphaRoute.StoryView.route)
                androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(200), initialScale = 0.94f)
            else androidx.compose.animation.slideInHorizontally(tween(OPEN_MS, easing = SCREEN_EASING)) { it }
        },
        exitTransition = {
            if (targetState.destination.route == AlphaRoute.StoryView.route) androidx.compose.animation.ExitTransition.KeepUntilTransitionsFinished
            else androidx.compose.animation.slideOutHorizontally(tween(OPEN_MS, easing = SCREEN_EASING)) { -(it * UNDER_SHIFT).toInt() } +
                androidx.compose.animation.fadeOut(tween(OPEN_MS, easing = SCREEN_EASING), targetAlpha = UNDER_ALPHA)
        },
        popEnterTransition = {
            if (initialState.destination.route == AlphaRoute.StoryView.route) androidx.compose.animation.EnterTransition.None
            // After a swipe the old screen already slid away and this one's picture
            // lies in place: the real one just takes its spot.
            else if (swipe.popping) androidx.compose.animation.EnterTransition.None
            else androidx.compose.animation.slideInHorizontally(tween(CLOSE_MS, easing = SCREEN_EASING)) { -(it * UNDER_SHIFT).toInt() } +
                androidx.compose.animation.fadeIn(tween(CLOSE_MS, easing = SCREEN_EASING), initialAlpha = UNDER_ALPHA)
        },
        popExitTransition = {
            if (initialState.destination.route == AlphaRoute.StoryView.route || swipe.popping) androidx.compose.animation.ExitTransition.None
            else androidx.compose.animation.slideOutHorizontally(tween(CLOSE_MS, easing = SCREEN_EASING)) { it }
        }
    ) {
        // --- ONBOARDING FLOW ---
        //
        // CRITICAL: every onboarding screen MUST share the SAME
        // OnboardingViewModel instance. Without this, identityCreated /
        // displayName / nsec etc. are lost on navigation and the
        // the "Continue" button stays disabled.
        //
        // The VM is bound to the Onboarding route's NavBackStackEntry,
        // which stays alive in the back stack until the flow completes.
        // All sub-screens reach into that same entry.

        composable(AlphaRoute.Onboarding.route) {
            val viewModel: OnboardingViewModel = hiltViewModel()
            OnboardingScreen(
                viewModel = viewModel,
                onNavigateToSeedBackup = {
                    navController.navigate(AlphaRoute.SeedBackup.route)
                },
                onNavigateToRestore = {
                    navController.navigate(AlphaRoute.Restore.route)
                },
                onLinkPhone = { navController.navigate(AlphaRoute.LinkPhone.route) },
            )
        }

        composable(AlphaRoute.LinkPhone.route) {
            val viewModel: com.alphamessenger.ui.onboarding.LinkPhoneViewModel = hiltViewModel()
            com.alphamessenger.ui.onboarding.LinkPhoneScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                // The account and its profile came with the link: its password next.
                onLinked = { navController.navigate(AlphaRoute.RecoveryPassword.route) },
            )
        }

        composable(AlphaRoute.SeedBackup.route) {
            // Shared VM — scoped to the Onboarding entry. Captured once via
            // remember so a teardown recomposition never re-resolves a popped
            // route (which would crash). identityCreated / nsec survive nav.
            val onboardingEntry = remember { navController.getBackStackEntry(AlphaRoute.Onboarding.route) }
            val viewModel: OnboardingViewModel = hiltViewModel(onboardingEntry)
            SeedBackupScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onContinue = {
                    // A phrase is checked before going on; a bare nsec has nothing to check.
                    val next = if (viewModel.uiState.value.mnemonic != null) AlphaRoute.SeedVerify else AlphaRoute.RecoveryPassword
                    navController.navigate(next.route)
                }
            )
        }

        composable(AlphaRoute.SeedVerify.route) {
            val onboardingEntry = remember { navController.getBackStackEntry(AlphaRoute.Onboarding.route) }
            val viewModel: OnboardingViewModel = hiltViewModel(onboardingEntry)
            com.alphamessenger.ui.onboarding.SeedVerifyScreen(
                viewModel = viewModel,
                wordlist = com.alphamessenger.ui.onboarding.Bip39Words,
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(AlphaRoute.RecoveryPassword.route) },
            )
        }

        composable(AlphaRoute.RecoveryPassword.route) {
            // RecoveryPasswordViewModel is scoped to this route's own entry —
            // it owns transient password input, which must NOT outlive the
            // screen (it isn't part of the shared onboarding VM).
            val viewModel: RecoveryPasswordViewModel = hiltViewModel()
            RecoveryPasswordScreen(
                viewModel = viewModel,
                wordlist = com.alphamessenger.ui.onboarding.Bip39Words,
                onBack = { navController.popBackStack() },
                onContinue = {
                    // A linked phone has its profile already.
                    val next = if (com.alphamessenger.ui.onboarding.LinkFlow.active) AlphaRoute.Permissions else AlphaRoute.ProfileSetup
                    navController.navigate(next.route)
                }
            )
        }

        composable(AlphaRoute.ProfileSetup.route) {
            val onboardingEntry = remember { navController.getBackStackEntry(AlphaRoute.Onboarding.route) }
            val viewModel: OnboardingViewModel = hiltViewModel(onboardingEntry)
            ProfileSetupScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onContinue = {
                    navController.navigate(AlphaRoute.Permissions.route)
                }
            )
        }

        composable(AlphaRoute.Permissions.route) {
            PermissionsScreen(
                onContinue = { navController.navigate(AlphaRoute.AppLockSetup.route) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(AlphaRoute.AppLockSetup.route) {
            // Capture the Onboarding entry ONCE (it's on the stack when this
            // screen is entered). Using remember avoids re-resolving it during
            // the exit-animation recomposition after we pop Onboarding on
            // completion — that re-lookup would throw "No destination with
            // route onboarding is on the back stack" and crash.
            val onboardingEntry = remember { navController.getBackStackEntry(AlphaRoute.Onboarding.route) }
            val viewModel: OnboardingViewModel = hiltViewModel(onboardingEntry)
            AppLockSetupScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onContinue = {
                    onOnboarded()
                    navController.navigate(AlphaRoute.ChatList.route) {
                        popUpTo(AlphaRoute.Onboarding.route) { inclusive = true }
                    }
                },
                onSkip = {
                    onOnboarded()
                    navController.navigate(AlphaRoute.ChatList.route) {
                        popUpTo(AlphaRoute.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(AlphaRoute.Restore.route) {
            // RestoreViewModel is scoped to this route's own entry — the restore
            // wizard owns transient nsec/password state that must NOT outlive the
            // screen, and its multi-step flow is self-contained.
            val viewModel: RestoreViewModel = hiltViewModel()
            val restoreCtx = androidx.compose.ui.platform.LocalContext.current
            RestoreScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRestoreComplete = {
                    onOnboarded()
                    // Relays, settings and the core start again from what was restored.
                    com.alphamessenger.data.AppRestart.afterRestore(restoreCtx)
                }
            )
        }

        // --- MAIN SCREENS ---

        composable(AlphaRoute.ChatList.route) {
            val viewModel: ChatListViewModel = hiltViewModel()
            ChatListScreen(
                viewModel = viewModel,
                onChatClick = { chatId ->
                    navController.navigate(AlphaRoute.Chat.create(chatId))
                },
                onSettingsClick = {
                    navController.navigate(AlphaRoute.Settings.route)
                },
                onProfileScreenClick = {
                    navController.navigate(AlphaRoute.Profile.route)
                },                onContactInfo = { id ->
                    navController.navigate(AlphaRoute.ContactInfo.create(id))
                },
                onOpenStory = { key -> navController.navigate(AlphaRoute.StoryView.create(key)) },
                onCreateStory = { navController.navigate(AlphaRoute.StoryCreate.route) },
                onOpenChatAt = { id, msg -> navController.navigate(AlphaRoute.Chat.create(id, msg)) },
                onOpenFound = { id, msg, q -> navController.navigate(AlphaRoute.Chat.found(id, msg, q)) },
                onOpenRelays = { navController.navigate(AlphaRoute.SetRelays.route) },
            )
        }

        composable(
            route = AlphaRoute.Chat.route,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("thread") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            // A return to this chat may bring a message to show (openChat).
            val focus by backStackEntry.savedStateHandle.getStateFlow<String?>("focus", backStackEntry.arguments?.getString("focus")).collectAsState()
            val searchQuery = backStackEntry.arguments?.getString("q")
            val viewModel: ChatViewModel = hiltViewModel()
            // The chat's own look (mine, else its admins'), over the app's theme.
            com.alphamessenger.ui.chat.look.ChatLookScope(chatId, viewModel.repository) {
            com.alphamessenger.ui.chat.poll.PollScope(chatId, viewModel.repository) {
            ChatScreen(
                chatId = chatId,
                focusMessageId = focus,
                searchQuery = searchQuery,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenScheduled = { id -> navController.navigate(AlphaRoute.Scheduled.create(id)) },
                onExport = { id -> navController.navigate(AlphaRoute.Export.chat(id)) },
                onOpenChat = { id -> navController.openChat(id) },
                onOpenChatAt = { id, msg -> navController.openChat(id, msg) },
                // A post's discussion: a screen of its own above the channel.
                onOpenThread = { postId, focusId -> navController.navigate(AlphaRoute.Chat.thread(chatId, postId, focusId)) },
                // Forward: push a NEW backstack entry (no singleTop) so "Back"
                // from the target returns to THIS (source) chat, not the main list.
                onForwardOpenChat = { id -> navController.navigate(AlphaRoute.Chat.create(id)) },
                onContactInfo = { id -> navController.openInfo(id) },
                onOpenDirectChat = { npub ->
                    viewModel.openDirectChatWith(npub) { newId -> navController.openChat(newId) }
                },
                onOpenMemberInfo = { senderId, npub ->
                    viewModel.memberInfoTarget(senderId, npub) { target -> navController.openInfo(target) }
                },
                onOpenAssistant = { id -> navController.navigate(AlphaRoute.AssistantProfile.create(id)) },
            )
            }
            }
        }

        composable(
            route = AlphaRoute.ContactInfo.route,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            val contactInfoViewModel: ContactInfoViewModel = hiltViewModel()
            val contactState by contactInfoViewModel.uiState.collectAsState()
            val memberScope = androidx.compose.runtime.rememberCoroutineScope()
            ContactInfoScreen(
                onOpenMember = { senderId, npub ->
                    // Open the member's full info page like a 1:1 chat. If a DM
                    // already exists → use it (shared media shows); otherwise open
                    // a peer-only profile (no media, no DM is created).
                    // NB: NO launchSingleTop — navigating ContactInfo→ContactInfo
                    // (same route) with it would REUSE the current entry (old
                    // chatId/ViewModel) and nothing would change on screen. A
                    // plain navigate pushes a fresh entry for the member.
                    memberScope.launch {
                        // Ourselves: our own profile, never a contact page.
                        val own = runCatching { contactInfoViewModel.repo.getIdentityInfo().npub }.getOrNull().orEmpty()
                        val self = npub == own || npub == com.alphamessenger.ui.contacts.SELF_MEMBER
                        val dm = if (self) null else contactInfoViewModel.repo.findDirectChatByNpub(npub)
                        navController.openInfo(dm ?: "peer_${senderId}_${npub}")
                    }
                },
                state = contactState,
                onBack = { navController.popBackStack() },
                onVerify = { navController.navigate(AlphaRoute.Verify.create(chatId)) },
                onLeaveGroup = {
                    // The screen already performed the leave/delete via the
                    // ViewModel; here we just return to the chat list (the chat
                    // route is now gone). Pop back to ChatList.
                    navController.popBackStack(AlphaRoute.ChatList.route, inclusive = false)
                },
                onSetGroupAvatar = contactInfoViewModel::setGroupAvatar,
                onSetGroupName = contactInfoViewModel::setGroupName,
                onSetGroupDescription = contactInfoViewModel::setGroupDescription,
                onToggleMute = { contactInfoViewModel.toggleMute() },
                onOpenChat = { navController.openChat(chatId) },
                onOpenDm = {
                    // Open the 1:1 chat for this peer-info page. If no DM exists
                    // yet (peer_<senderId>_<npub> route) create one (sends Welcome).
                    memberScope.launch {
                        val target = if (chatId.startsWith("peer_")) {
                            val npub = chatId.substringAfterLast("_")
                            runCatching { contactInfoViewModel.repo.addContactByNpub(npub) }.getOrNull()?.id
                        } else chatId
                        if (target != null) navController.openChat(target)
                    }
                },
                onOpenSaved = {
                    memberScope.launch {
                        runCatching { contactInfoViewModel.repo.ensureSavedMessagesChat() }
                        navController.openChat(contactInfoViewModel.repo.savedMessagesChatId)
                    }
                },
                mediaLoader = { contactInfoViewModel.loadMedia(it) },
                repository = contactInfoViewModel.repo,
                viewModel = contactInfoViewModel,
                onOpenInChat = { messageId -> navController.openChat(chatId, messageId) },
                onOpenEdit = { navController.navigate(AlphaRoute.GroupEdit.create(chatId)) },
                onStartSecretChat = {
                    memberScope.launch {
                        val secret = runCatching { contactInfoViewModel.repo.secretChatStart(chatId) }.getOrNull()
                        if (secret != null) navController.openChat(secret)
                    }
                },
            )
        }

        composable(
            route = AlphaRoute.StoryView.route,
            arguments = listOf(navArgument("key") { type = NavType.StringType }),
        ) { entry ->
            val key = entry.arguments?.getString("key") ?: return@composable
            val vm: com.alphamessenger.ui.stories.StoriesViewModel = hiltViewModel()
            com.alphamessenger.ui.stories.StoryViewerScreen(startKey = key, viewModel = vm, onClose = { navController.popBackStack() })
        }

        composable(AlphaRoute.StoryCreate.route) {
            val vm: com.alphamessenger.ui.stories.StoriesViewModel = hiltViewModel()
            com.alphamessenger.ui.stories.StoryCreateScreen(viewModel = vm, onClose = { navController.popBackStack() })
        }

        composable(
            route = AlphaRoute.GroupEdit.route,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) { entry ->
            // The SAME ViewModel as the group's info page below it: one state,
            // so whatever is changed here is already there on the way back.
            val parent = androidx.compose.runtime.remember(entry) {
                navController.getBackStackEntry(AlphaRoute.ContactInfo.route)
            }
            val vm: ContactInfoViewModel = hiltViewModel(parent)
            val st by vm.uiState.collectAsState()
            val editScope = androidx.compose.runtime.rememberCoroutineScope()
            com.alphamessenger.ui.contacts.GroupEditScreen(
                state = st,
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onGroupGone = { navController.popBackStack(AlphaRoute.ChatList.route, inclusive = false) },
                onOpenMember = { senderId, npub ->
                    editScope.launch {
                        val own = runCatching { vm.repo.getIdentityInfo().npub }.getOrNull().orEmpty()
                        val self = npub == own || npub == com.alphamessenger.ui.contacts.SELF_MEMBER
                        val dm = if (self) null else vm.repo.findDirectChatByNpub(npub)
                        navController.openInfo(dm ?: "peer_${senderId}_${npub}")
                    }
                },
            )
        }

        composable(
            route = AlphaRoute.Verify.route,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) {
            val verifyViewModel: VerifyViewModel = hiltViewModel()
            VerifyScreen(
                viewModel = verifyViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // --- SETTINGS ---

        composable(AlphaRoute.Settings.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onProfileClick = { navController.navigate(AlphaRoute.Profile.route) },
                onRelaysClick = { navController.navigate(AlphaRoute.SetRelays.route) },
                onPrivacyClick = { navController.navigate(AlphaRoute.SetPrivacy.route) },
                onThemeClick = { navController.navigate(AlphaRoute.SetTheme.route) },
                onStickersClick = { navController.navigate(AlphaRoute.SetStickers.route) },
                onBackupClick = { navController.navigate(AlphaRoute.Backup.route) },
                onExportClick = { navController.navigate(AlphaRoute.Export.profile()) },
                onAppLockClick = { navController.navigate(AlphaRoute.AppLock.route) },
                onDevicesClick = { navController.navigate(AlphaRoute.SetDevices.route) },
                onStorageClick = { navController.navigate(AlphaRoute.SetStorage.route) },
                onFoldersClick = { navController.navigate(AlphaRoute.SetFolders.route) },
                onAutoDownloadClick = { navController.navigate(AlphaRoute.SetAutoDownload.route) },
                onLanguageClick = { navController.navigate(AlphaRoute.SetLanguage.route) },
                onAssistantClick = { navController.navigate(AlphaRoute.Assistant.route) },
                onBrowserClick = { navController.navigate(AlphaRoute.SetBrowser.route) },
                onAboutClick = { navController.navigate(AlphaRoute.About.route) },
            )
        }

        composable(AlphaRoute.About.route) {
            val vm: SettingsViewModel = hiltViewModel()
            com.alphamessenger.ui.settings.AboutScreen(
                repository = vm.repository,
                onBack = { navController.popBackStack() },
                onDoc = { navController.navigate(AlphaRoute.Legal.create(it)) },
            )
        }

        composable(AlphaRoute.Legal.route, arguments = listOf(navArgument("doc") { type = NavType.StringType })) { entry ->
            val doc = runCatching { com.alphamessenger.ui.settings.LegalDoc.valueOf(entry.arguments?.getString("doc").orEmpty()) }
                .getOrDefault(com.alphamessenger.ui.settings.LegalDoc.LICENSE)
            com.alphamessenger.ui.settings.LegalDocScreen(doc, onBack = { navController.popBackStack() })
        }

        composable(AlphaRoute.SetBrowser.route) {
            com.alphamessenger.ui.browser.BrowserSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(AlphaRoute.Assistant.route) {
            com.alphamessenger.assistant.AssistantSettingsScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(AlphaRoute.AssistantProfile.create(id)) },
                onNewCouncil = { navController.navigate(AlphaRoute.AssistantProfile.create("new-council")) },
                onJournal = { navController.navigate(AlphaRoute.AssistantJournal.route) },
                onOpenChat = { id -> navController.openChat(id) },
            )
        }

        composable(AlphaRoute.AssistantJournal.route) {
            com.alphamessenger.assistant.AssistantJournalScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = AlphaRoute.AssistantProfile.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString("id")
            val id = raw?.takeIf { it != "new" && it != "new-council" }
            val vm: SettingsViewModel = hiltViewModel()
            com.alphamessenger.assistant.AssistantProfileScreen(
                profileId = id,
                repository = vm.repository,
                newCouncil = raw == "new-council",
                onBack = { navController.popBackStack() },
                onOpenChat = { chat ->
                    // A bot made from the bot list opens over that list.
                    if (!navController.returnTo { it.destination.route == AlphaRoute.Chat.route && it.arguments?.getString("chatId") == chat })
                        navController.navigate(AlphaRoute.Chat.create(chat)) { popUpTo(AlphaRoute.Assistant.route) { inclusive = false } }
                },
            )
        }

        composable(
            route = AlphaRoute.Scheduled.route,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("chatId") ?: return@composable
            val vm: ChatViewModel = hiltViewModel()
            ChatScreen(
                chatId = id,
                viewModel = vm,
                onBack = { navController.popBackStack() },
                scheduledMode = true,
            )
        }

        composable(AlphaRoute.SetLanguage.route) {
            com.alphamessenger.ui.settings.LanguageScreen(onBack = { navController.popBackStack() })
        }

        composable(AlphaRoute.SetFolders.route) {
            val viewModel: com.alphamessenger.ui.chatlist.ChatListViewModel = hiltViewModel()
            com.alphamessenger.ui.chatlist.FoldersSettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(AlphaRoute.SetStorage.route) {
            val viewModel: StorageUsageViewModel = hiltViewModel()
            StorageUsageScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(AlphaRoute.SetAutoDownload.route) {
            AutoDownloadScreen(onBack = { navController.popBackStack() })
        }

        composable(AlphaRoute.SetDevices.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            com.alphamessenger.ui.settings.LinkedDevicesScreen(
                repository = viewModel.repository,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = AlphaRoute.Export.route,
            arguments = listOf(navArgument("chat") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            com.alphamessenger.ui.settings.ExportScreen(
                chatId = entry.arguments?.getString("chat"),
                onBack = { navController.popBackStack() },
            )
        }

        composable(AlphaRoute.Profile.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            SelfProfileScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(AlphaRoute.SetRelays.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            RelaysScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(AlphaRoute.SetPrivacy.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            PrivacyScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(AlphaRoute.SetTheme.route) {
            ThemeSelectorScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(AlphaRoute.SetStickers.route) {
            com.alphamessenger.ui.settings.StickersEmojiScreen(onBack = { navController.popBackStack() })
        }

        composable(AlphaRoute.Backup.route) {
            val viewModel: BackupViewModel = hiltViewModel()
            BackupScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(AlphaRoute.AppLock.route) {
            com.alphamessenger.ui.settings.AppLockSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
    } // EdgeSwipeBack
}
