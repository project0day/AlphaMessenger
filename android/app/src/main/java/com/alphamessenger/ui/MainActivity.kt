package com.alphamessenger.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.alphamessenger.R
import com.alphamessenger.ui.chat.ChatViewModel
import com.alphamessenger.ui.navigation.AlphaNavHost
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.ThemeViewModel
import com.alphamessenger.ui.theme.scaledBy
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : androidx.appcompat.app.AppCompatActivity() {
    // Flipped true once the identity probe has resolved — keeps the system
    // splash (the animated ghost) on screen until we know where to land, so
    // there is exactly ONE splash and no Splash→Main transition overlap.
    private var splashReady = false

    @javax.inject.Inject
    lateinit var coreRepository: com.alphamessenger.data.AlphaCoreRepository

    // Unlock-gate dependencies. The gate is the SINGLE entry point
    // to the DB lifecycle; none of these open the DB by themselves.
    @javax.inject.Inject
    lateinit var coreHolder: com.alphamessenger.data.CoreHolder

    @javax.inject.Inject
    lateinit var unlockManager: com.alphamessenger.data.UnlockManager

    @javax.inject.Inject
    lateinit var recoveryKeyManager: com.alphamessenger.data.RecoveryKeyManager

    @javax.inject.Inject
    lateinit var recoveryKdfPublicStore: com.alphamessenger.data.RecoveryKdfPublicStore

    /** Deep-link invite captured before unlock; processed once the DB is open. */
    private var pendingInviteIntent: Intent? = null

    /** chatId from a tapped notification — the NavHost observes this and opens
 * that chat/group. */
    private val pendingNotifChatId = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Read a notification's chatId extra (if any) into [pendingNotifChatId]. */
    private fun captureNotifChatId(intent: Intent?) {
        intent?.getStringExtra("chatId")?.takeIf { it.isNotBlank() }?.let {
            pendingNotifChatId.value = it
        }
        if (intent?.getBooleanExtra(com.alphamessenger.service.MusicService.EXTRA_OPEN_MUSIC, false) == true) {
            intent.removeExtra(com.alphamessenger.service.MusicService.EXTRA_OPEN_MUSIC)
            com.alphamessenger.ui.chat.MusicPlayerHolder.get(this).requestSheet()
        }
    }

    /** Handle an `alpha://group/invite/<token>` deep link by sending a join
     *  request to the inviter. The chat appears once the admin approves. */
    private fun handleInviteIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val link = data.toString()
        // alpha://channel/<code>, alpha://group/<code>, and the first format
        // alpha://group/invite/<code>.
        if (data.scheme != "alpha" || (data.host != "group" && data.host != "channel")) return
        // NO DB ACCESS BEFORE UNLOCK. If the DB isn't open yet, stash
        // the invite and process it once the unlock gate completes.
        if (!coreHolder.isUnlocked()) {
            pendingInviteIntent = intent
            return
        }
        // A link from outside (a web page, another app) only asks: joining sends
        // our name, photo and key, so the person decides, never the link.
        inviteToConfirm.value = link
    }

    /** An invite link waiting for the person's yes. */
    private val inviteToConfirm = androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun joinConfirmed(link: String) {
        run {
            lifecycleScope.launch {
                val err = coreRepository.sendJoinRequest(link)
                Toast.makeText(
                    this@MainActivity,
                    err?.let { this@MainActivity.getString(R.string.error_join_failed, it) } ?: this@MainActivity.getString(
                        if (com.alphamessenger.ui.chat.parseInvite(link).byRequest) R.string.join_request_sent_owner else R.string.join_joining),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * The system Sharesheet. The Activity only receives the event: parsing is
     * done by [com.alphamessenger.ui.share.ShareIntentHandler], recipients are
     * awaited by [com.alphamessenger.ui.share.PendingShare], and the files are
     * read by the shared attachment factory — after the choice, the same way
     * as gallery and camera.
     *
     * Grants for foreign URIs live as long as the task does, so copying "just
     * in case" right here is not needed.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (!com.alphamessenger.ui.share.ShareIntentHandler.isShare(intent)) return
        val shared = intent ?: return
        // The mark sits on the Intent itself: Activity recreation will not take the same Share twice.
        com.alphamessenger.ui.share.ShareIntentHandler.markConsumed(shared)
        val payload = com.alphamessenger.ui.share.ShareIntentHandler.receive(shared, this)
        com.alphamessenger.ui.share.PendingShare.offer(payload)
        if (payload == null) {
            Toast.makeText(this, this@MainActivity.getString(R.string.error_read_attachment), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInviteIntent(intent)
        captureNotifChatId(intent)
        handleShareIntent(intent)
    }

    // Single choke point for EVERY in-app ActivityResult launch (gallery/crop/
    // camera/file pickers). Arming the exemption here stops the secondary AppLock
    // from re-locking when the user returns from a picker (which otherwise dumped
    // them back on the chat list). Covers all screens — this is a single-Activity
    // app, so every launcher.launch() routes through here.
    @Deprecated("Routed through by the AndroidX ActivityResult APIs")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        com.alphamessenger.data.AppLockGuard.armPickerExemption()
        super.startActivityForResult(intent, requestCode, options)
    }

    @Deprecated("Routed through by the AndroidX ActivityResult APIs")
    override fun startIntentSenderForResult(
        intent: android.content.IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?,
    ) {
        com.alphamessenger.data.AppLockGuard.armPickerExemption()
        super.startIntentSenderForResult(
            intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate
        val splashScreen = installSplashScreen()

        // Hold the system splash until the identity probe finished.
        splashScreen.setKeepOnScreenCondition { !splashReady }

        super.onCreate(savedInstanceState)
        // Animated emoji fetch their set through the core, wherever they first show.
        com.alphamessenger.ui.emoji.AnimatedEmoji.repo = coreRepository
        WindowCompat.setDecorFitsSystemWindows(window, false)
        com.alphamessenger.ui.components.ScreenSecurity.load(this)
        com.alphamessenger.ui.components.ScreenSecurity.apply(this)

        // The relay sync foreground service is NOT started here.
        // Starting it pre-unlock would touch the DB pool. It is started only after
        // the unlock gate completes (see the LaunchedEffect on `unlocked` below).
        // An invite deep link is stashed if the DB isn't open yet and
        // processed post-unlock (handleInviteIntent self-guards on isUnlocked()).
        handleInviteIntent(intent)
        captureNotifChatId(intent)
        handleShareIntent(intent)
        // Anchor trusted time so timed bans/mutes/chat-close can't be bypassed by
        // changing the device clock. Best-effort, off the main thread.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val viaTor = com.alphamessenger.data.NetworkPrivacyPrefs(this@MainActivity).torEnabled
            runCatching { com.alphamessenger.util.TrustedTime.sync(viaTor) }
        }
        setContent {
            // Pull the persisted theme variant so a user choice in Settings
            // takes effect immediately and survives process death.
            val themeViewModel: ThemeViewModel = hiltViewModel()
            // No theme chosen yet → follow the device (dark → Grey, light → Daylight).
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            androidx.compose.runtime.LaunchedEffect(systemDark) { themeViewModel.onSystemDarkChanged(systemDark) }
            val variant by themeViewModel.themeVariant.collectAsState()
            val msgFontScale by themeViewModel.messageFontScale.collectAsState()
            val bubbleRadius by themeViewModel.bubbleRadius.collectAsState()
            val chatWallpaper by themeViewModel.chatWallpaper.collectAsState()
            val customTheme by themeViewModel.activeCustom.collectAsState()
            AlphaTheme(
                variant = variant,
                custom = customTheme,
                // Interface text follows the system font size; only messages have their own.
                typography = com.alphamessenger.ui.theme.AlphaTypography,
            ) {
              com.alphamessenger.ui.theme.ProvideAppearance(
                messageFontScale = msgFontScale,
                chatWallpaper = chatWallpaper,
                bubbleRadius = bubbleRadius,
              ) {
                // Joining through a link always waits for a yes.
                inviteToConfirm.value?.let { link ->
                    val info = remember(link) { com.alphamessenger.ui.chat.parseInvite(link) }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { inviteToConfirm.value = null },
                        title = { androidx.compose.material3.Text(getString(if (info.channel) R.string.invite_confirm_channel else R.string.invite_confirm_group)) },
                        text = {
                            androidx.compose.material3.Text(
                                listOf(info.name, info.description, getString(R.string.invite_confirm_note))
                                    .filter { it.isNotBlank() }.joinToString("\n\n"),
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { inviteToConfirm.value = null; joinConfirmed(link) }) {
                                androidx.compose.material3.Text(getString(R.string.invite_confirm_join))
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { inviteToConfirm.value = null }) {
                                androidx.compose.material3.Text(getString(R.string.common_cancel))
                            }
                        },
                    )
                }
                // The starry sky follows the theme (it is drawn in its own layer).
                val skyAccent = AlphaTheme.colorScheme.accent
                androidx.compose.runtime.SideEffect {
                    com.alphamessenger.ui.components.StarSky.light = variant.isLight
                    com.alphamessenger.ui.components.StarSky.accent = skyAccent
                }
                // Unlock gate: the SINGLE UI entry to the DB
                // lifecycle. Until the core is unlocked we show UnlockGate and
                // NEVER compose the app content (MainViewModel etc.), which would
                // touch the DB. Theme is applied above so the gate is themed too.
                val dbUnlocked by coreHolder.unlocked.collectAsState()
                androidx.compose.runtime.LaunchedEffect(dbUnlocked) {
                    splashReady = true // dismiss the system splash → show gate/content
                    if (dbUnlocked) {
                        // DB is open → now safe to start sync + process a stashed invite.
                        (application as? com.alphamessenger.AlphaMessengerApp)
                            ?.startSyncServiceFromActivity()
                        pendingInviteIntent?.let { handleInviteIntent(it); pendingInviteIntent = null }
                        // One-time prompt to exempt the app from Doze battery
                        // optimization so background delivery stays prompt (no FCM).
                        com.alphamessenger.util.BatteryOptimization.maybePrompt(this@MainActivity)
                    }
                }
                if (!dbUnlocked) {
                    com.alphamessenger.ui.lock.UnlockGate(
                        activity = this@MainActivity,
                        unlockManager = unlockManager,
                        recoveryKeyManager = recoveryKeyManager,
                        recoveryKdfPublicStore = recoveryKdfPublicStore,
                    )
                } else {
                val mainViewModel: com.alphamessenger.ui.navigation.MainViewModel = hiltViewModel()
                val checked by mainViewModel.identityChecked.collectAsState()
                val hasIdentity by mainViewModel.hasIdentity.collectAsState()
                val onboarded by mainViewModel.onboarded.collectAsState()

                // Dismiss the system splash once we know the destination.
                androidx.compose.runtime.LaunchedEffect(checked) {
                    if (checked) splashReady = true
                }

                if (checked) {
                    val goMain = hasIdentity && onboarded
                    val start = if (goMain)
                        com.alphamessenger.ui.navigation.AlphaRoute.ChatList.route
                    else
                        com.alphamessenger.ui.navigation.AlphaRoute.Onboarding.route

                    // App-lock gate: only for the registered/main path (never
                    // during onboarding). Re-locks when the app goes to the
                    // background and is brought forward again.
                    val lockManager = remember { com.alphamessenger.data.AppLockManager(this@MainActivity) }
                    val lockActive = goMain && lockManager.isLockEnabled
                    // Honour the auto-lock delay on a FRESH Activity too: if the
                    // app was backgrounded less than the configured delay ago, start
                    // unlocked instead of always re-locking on every recreation.
                    var unlocked by remember {
                        mutableStateOf(
                            !lockActive || lockManager.mayStartUnlocked() ||
                                // The fingerprint that just opened the database counts, when
                                // the lock itself accepts a fingerprint (a PIN-only lock still asks).
                                (lockManager.biometricEnabled && com.alphamessenger.ui.lock.FreshAuth.consume()),
                        )
                    }

                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    var backgroundedAt by remember { mutableStateOf(0L) }
                    fun sendSyncAction(action: String) {
                        runCatching {
                            startService(
                                android.content.Intent(this@MainActivity, com.alphamessenger.service.MessageSyncService::class.java)
                                    .setAction(action)
                            )
                        }
                    }
                    androidx.compose.runtime.DisposableEffect(lifecycleOwner, lockActive) {
                        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                                    val t = android.os.SystemClock.elapsedRealtime()
                                    // Only leaving an OPEN app counts. While locked, the system's
                                    // fingerprint sheet itself stops us on some phones; counted, it
                                    // re-locked right after a good fingerprint (asked twice).
                                    backgroundedAt = if (unlocked) t else 0L
                                    // Only an OPEN app starts its auto-lock window; a locked
                                    // one stays locked whatever happens to the process.
                                    if (lockActive) lockManager.lastBackgroundedElapsed = if (unlocked) t else 0L
                                    sendSyncAction(com.alphamessenger.service.MessageSyncService.ACTION_APP_BACKGROUND)
                                }
                                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                                    // Returning from an in-app picker must not re-lock.
                                    val pickerExempt = com.alphamessenger.data.AppLockGuard.consumeExemption()
                                    if (lockActive && backgroundedAt > 0L && !pickerExempt) {
                                        val away = android.os.SystemClock.elapsedRealtime() - backgroundedAt
                                        if (away >= lockManager.autoLockDelayMs) unlocked = false
                                    }
                                    sendSyncAction(com.alphamessenger.service.MessageSyncService.ACTION_APP_FOREGROUND)
                                    // Reconnect relays only after a real absence (>30 s).
                                    val awayMs = if (backgroundedAt > 0L)
                                        android.os.SystemClock.elapsedRealtime() - backgroundedAt
                                    else Long.MAX_VALUE
                                    if (awayMs >= 30_000L) {
                                        sendSyncAction(com.alphamessenger.service.MessageSyncService.ACTION_RECONNECT)
                                    }
                                }
                                else -> {}
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(obs)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                    }

                    // "Lock now" from the chat list.
                    androidx.compose.runtime.LaunchedEffect(lockActive) {
                        com.alphamessenger.ui.lock.AppLockRequests.events.collect {
                            if (lockActive) {
                                // Locked by hand: no auto-lock window may reopen it, even
                                // after the app is swiped away and started again.
                                lockManager.lastBackgroundedElapsed = 0L
                                unlocked = false
                            }
                        }
                    }
                    if (lockActive && !unlocked) {
                        com.alphamessenger.ui.lock.LockScreen(
                            activity = this@MainActivity,
                            manager = lockManager,
                            onUnlocked = { unlocked = true },
                            onPanic = { com.alphamessenger.data.PanicWipe.wipe(this@MainActivity) },
                        )
                    } else {
                        val navController = rememberNavController()
                        // Tapped a notification → jump straight into that chat/group.
                        val pendingChat = pendingNotifChatId.value
                        androidx.compose.runtime.LaunchedEffect(pendingChat) {
                            if (pendingChat != null) {
                                runCatching {
                                    navController.navigate(
                                        com.alphamessenger.ui.navigation.AlphaRoute.Chat.create(pendingChat)
                                    ) { launchSingleTop = true }
                                }
                                pendingNotifChatId.value = null
                            }
                        }
                        // A Share may have arrived while a chat was open: recipients are chosen
                        // in the list, so we return to it. "Back" from the viewer leads here too —
                        // the set is waiting for a choice again.
                        val shareActive = com.alphamessenger.ui.share.PendingShare.active
                        androidx.compose.runtime.LaunchedEffect(shareActive) {
                            if (shareActive) {
                                runCatching {
                                    navController.navigate(
                                        com.alphamessenger.ui.navigation.AlphaRoute.ChatList.route
                                    ) {
                                        popUpTo(com.alphamessenger.ui.navigation.AlphaRoute.ChatList.route)
                                        launchSingleTop = true
                                    }
                                }
                            }
                        }
                        // A video message that keeps playing floats over every screen and its bars.
                        // A folded browser lies under every screen; they end above it.
                        val browserFolded = com.alphamessenger.ui.browser.foldedBarShown().takeIf { it }
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxWidth().weight(1f).then(
                                if (browserFolded != null) Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                                else Modifier
                            )
                        ) {
                        AlphaNavHost(
                            navController = navController,
                            startDestination = start,
                            onOnboarded = {
                                mainViewModel.markOnboarded()
                                // The sync started when the database opened, before this
                                // account existed — the core refused it ("identity not
                                // initialized"). Start it now: until then the new account
                                // heard nothing and published no KeyPackage, so nobody
                                // could add it until the app was restarted.
                                (application as? com.alphamessenger.AlphaMessengerApp)?.startSyncServiceFromActivity()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        com.alphamessenger.ui.chat.round.FloatingRound()
                        com.alphamessenger.ui.theme.ThemeReveal.Overlay()
                        }
                        com.alphamessenger.ui.browser.FoldedBrowserBar()
                        }
                    }
                }
                } // end of the unlock-gate else-branch (dbUnlocked)
              }
            }
        }
    }
}
