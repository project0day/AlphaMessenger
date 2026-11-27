package com.alphamessenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.alphamessenger.R

/**
 * Foreground service that keeps the MLS sync loop alive and shows
 * notifications for incoming messages.
 *
 * ## Lifecycle
 * - Started by [com.alphamessenger.AlphaMessengerApp] when the app launches.
 * - Calls [AlphaCoreRepository.startSync] which starts the Rust-core relay
 *   subscription loop.
 * - Collects [AlphaCoreRepository.messageReceived] and posts an Android
 *   notification for each incoming message.
 * - Stopped (and sync halted) when the user swipes the app from recents.
 *
 * ## WorkManager fallback
 * Even when this service is not running (e.g. after a force-stop), a
 * [PeriodicSyncWorker] enqueued with WorkManager will attempt a lightweight
 * [AlphaCoreRepository.catchUp] every 15 minutes to pull missed messages.
 */
@AndroidEntryPoint
class MessageSyncService : Service() {

    companion object {
        /** The core already reconnected for the new network: catch up and flush only. */
        private const val REASON_NETWORK = "network-available"
        private const val CHANNEL_ID = "alpha_sync_channel"
        private const val CHANNEL_NAME = "Message Sync"
        private const val NOTIFICATION_ID_FOREGROUND = 1001
        private const val NOTIFICATION_ID_MESSAGES = 2000
        private const val PERIODIC_SYNC_WORK_NAME = "alpha_periodic_sync"
        private const val OUTBOX_FLUSH_WORK_NAME = "alpha_outbox_flush"

        /**
         * OFFLINE-DELIVERY RELIABILITY: schedule an EXPEDITED, network-gated
         * one-time flush of the offline outbox (pending sends + media + queued
         * MLS envelopes + catch-up).
         *
         * Why this exists: the always-on foreground service + its 12s outbox
         * loop + network callback only run while the service is ALIVE and the
         * device is NOT in deep Doze. When the screen is off and the OS suspends
         * or kills the service (battery optimisation / Doze / OEM), a message
         * composed offline used to sit in the queue until the user reopened the
         * app or the 15-min periodic worker happened to fire. This closes that
         * gap: an EXPEDITED WorkManager job is scheduled the moment a message is
         * sent. WorkManager persists across app/service death and the OS runs an
         * expedited job AS SOON AS the network constraint is met — within seconds
         * of regaining connectivity, even in Doze, with no app interaction.
         *
         * Unique + KEEP so a burst of rapid sends coalesces into ONE pending job
         * (no quota storm). RUN_AS_NON_EXPEDITED fallback if the expedited quota
         * is exhausted, so enqueue never throws.
         */
        fun enqueueOutboxFlush(context: Context) {
            val request = OneTimeWorkRequestBuilder<PeriodicSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    OUTBOX_FLUSH_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
            }
        }

        /** Action to start the sync service. */
        const val ACTION_START = "com.alphamessenger.action.START_SYNC"

        /** Action to stop the sync service. */
        const val ACTION_STOP = "com.alphamessenger.action.STOP_SYNC"

        /** Action to force a clean relay reconnect (app returned to foreground). */
        const val ACTION_RECONNECT = "com.alphamessenger.action.RECONNECT"

        /** BATTERY: app became visible → presence at the fast foreground cadence. */
        const val ACTION_APP_FOREGROUND = "com.alphamessenger.action.APP_FOREGROUND"

        /** BATTERY: app backgrounded → presence at the slow background cadence. */
        const val ACTION_APP_BACKGROUND = "com.alphamessenger.action.APP_BACKGROUND"

        /**
         * Enqueue a periodic WorkManager sync as a battery-safe fallback.
         * Should be called once during app initialisation.
         */
        fun enqueuePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    @Inject
    lateinit var repository: AlphaCoreRepository

    // Gate every command on the unlock state. The service must never
    // touch the DB pool before the core is unlocked.
    @Inject
    lateinit var coreHolder: com.alphamessenger.data.CoreHolder

    @Inject
    lateinit var unlockManager: com.alphamessenger.data.UnlockManager

 // Notification privacy preference.
    @Inject
    lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    private val notifier by lazy { MessageNotifier(applicationContext) }
    @Volatile private var notifPreview: MessageNotifier.Preview = MessageNotifier.Preview.FULL

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var shotJob: kotlinx.coroutines.Job? = null
    private var messageCollectionJob: Job? = null
    private var outboxJob: Job? = null
    private var sweepJob: Job? = null
    private var presenceJob: Job? = null
    private var notificationJob: Job? = null
    private var prefsJob: Job? = null
    private var watchdogJob: Job? = null
    private var cacheJob: Job? = null
    private var feedJob: Job? = null
    private var notificationIdCounter = NOTIFICATION_ID_MESSAGES

    // --- BATTERY: screen + app-visibility state drives the presence cadence ---
    // Screen OFF → presence fully stopped (no publish, no fetch, ZERO network/
    // CPU). Screen ON + app foreground → 30s. Screen ON + app background → 180s.
    // This is the single biggest heat/drain win: the device is idle with the
    // screen off most of the time.
    private val screenOnFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
    @Volatile private var appForeground = true
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            screenOnFlow.value = pm.isInteractive
        }
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON -> screenOnFlow.value = true
                    Intent.ACTION_SCREEN_OFF -> screenOnFlow.value = false
                }
            }
        }
        runCatching {
            registerReceiver(r, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            screenReceiver = r
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    // --- Service lifecycle ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onBind(intent: Intent?) = null

    /** Foreground with the declared type: Android 14+ requires it to match the manifest. */
    private fun startForegroundTyped() {
        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID_FOREGROUND, buildForegroundNotification(), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NO DB ACCESS BEFORE UNLOCK. The service is only meant to be
        // started post-unlock (from MainActivity). If it is invoked while still
        // locked (process restart, OEM wake, a stale intent), bail out WITHOUT
        // touching the repository/pool. We do not call startForeground here: the
        // start contract is satisfied only on the post-unlock path, where the
        // `when` below runs.
        if (!coreHolder.isUnlocked()) {
            if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
            // Restarted by the system or after a reboot, with no one at the screen:
            // the database opens with the key remembered on this phone, if there is one.
            startForegroundTyped()
            appForeground = false
            serviceScope.launch {
                if (unlockManager.unlockWithoutUser()) startOrResume()
                else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSyncAndSelf()
                return START_NOT_STICKY
            }
            ACTION_APP_FOREGROUND -> {
                // Lightweight flag flip — the presence loop reads it next tick.
                // The service is already foregrounded (started at unlock).
                appForeground = true
                return START_STICKY
            }
            ACTION_APP_BACKGROUND -> {
                appForeground = false
                return START_STICKY
            }
            ACTION_RECONNECT -> {
                // App returned to foreground → resume realtime delivery at once.
                startForegroundTyped()
                serviceScope.launch { forceReconnectAndCatchUp("foreground") }
            }
            else -> {
                // ACTION_START, or a sticky restart without an intent.
                startForegroundTyped()
                startOrResume()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCatchUp()
        unregisterScreenReceiver()
        messageCollectionJob?.cancel()
        outboxJob?.cancel()
        sweepJob?.cancel()
        presenceJob?.cancel()
        notificationJob?.cancel()
        prefsJob?.cancel()
        watchdogJob?.cancel()
        cacheJob?.cancel()
        feedJob?.cancel()
        serviceScope.cancel()
    }

    // --- Sync control ---

    @Volatile private var loopsStarted = false

    /** An Activity created again (back from recents, a theme switch) finds the
     *  sync already running: it only pulls what it missed. Restarting re-sent
     *  every subscription and re-armed every loop each time. */
    private fun startOrResume() {
        serviceScope.launch {
            val h = withContext(Dispatchers.IO) { repository.syncHealth() }
            if (loopsStarted && h != null && h.heartbeatAge in 0..150) {
                forceReconnectAndCatchUp("foreground")
            } else {
                lastReconnectAtMs = android.os.SystemClock.elapsedRealtime()
                startSync()
            }
        }
    }

    private fun startSync() {
        loopsStarted = true
 // Keep the notification-preview preference live (classic privacy:
        // full text / sender name only / nothing on screen).
        // Guard with a job handle: startSync() can run more than once
        // (ACTION_START, START_STICKY redelivery); without the cancel a NEW
        // dataStore.data collector is spawned each time and the old ones are
        // never cancelled — they accumulate for the life of the process.
        prefsJob?.cancel()
        prefsJob = serviceScope.launch {
            runCatching {
                dataStore.data.collect { prefs ->
                    notifPreview = MessageNotifier.Preview.from(
                        prefs[androidx.datastore.preferences.core.stringPreferencesKey("notif_preview")]
                    )
                    MessageNotifier.currentPreview = notifPreview
                }
            }
        }
        serviceScope.launch {
            try {
                repository.startSync()
                Log.i("MessageSyncService", "Sync started: realtime listener live, KeyPackage published")
                // Pull any backlog that piled up on relays while we were offline
                // (realtime subscription only covers events from "now" onward).
                runCatching { repository.catchUp() }
                    .onSuccess { Log.i("MessageSyncService", "Initial catch-up pulled $it events") }
                // Defer-encryption: flush offline-composed messages AFTER catch-up
                // so they encrypt on the latest MLS state, in order.
                runCatching { repository.flushPendingSends() }
                    .onSuccess { if (it > 0u) Log.i("MessageSyncService", "Flushed $it pending send(s)") }
                // Drain the outbox (queued Welcomes + envelopes that a relay
                // rejected): re-publish them now that we're connected. Without
                // this a failed Welcome/message sat in the queue forever.
                runCatching { repository.replayOutbox() }
                    .onSuccess { if (it > 0u) Log.i("MessageSyncService", "Replayed $it outbox envelope(s)") }
                runCatching { repository.flushPendingMedia() }
                    .onSuccess { if (it > 0u) Log.i("MessageSyncService", "Flushed $it pending media") }
            } catch (e: Exception) {
                Log.e("MessageSyncService", "startSync() failed — relays unreachable?", e)
            }
        }
        // Someone screenshotted our story: tell us even with the app closed.
        shotJob?.cancel()
        shotJob = serviceScope.launch(Dispatchers.IO) {
            runCatching {
                repository.chatMetadataUpdated.collect { chatId ->
                    com.alphamessenger.ui.stories.StoryShotNotifier.check(this@MessageSyncService, repository)
                    // Read on another phone of ours: its card here goes as well.
                    if (MessageNotifier.hasActiveCard(this@MessageSyncService, chatId)) {
                        val chat = runCatching { repository.listChats().firstOrNull { it.id == chatId } }.getOrNull()
                        if (chat != null && chat.unreadCount == 0u && !chat.unreadOverride) notifier.cancelChat(chatId)
                    }
                }
            }
        }
        // Re-catch-up whenever connectivity returns (plane mode off, wifi back).
        registerNetworkCatchUp()

        // The fixed 12s outbox drain: a failed media upload / offline-composed
        // message retries within 12s — this is what keeps photo/voice sends prompt.
        // Battery deviation: while the screen is OFF the loop suspends (0 CPU) —
        // that window is covered by the expedited WorkManager flush on send +
        // ntfy wake + network-available events, each of which flushes all three
        // queues immediately.
        outboxJob?.cancel()
        outboxJob = serviceScope.launch {
            while (true) {
                screenOnFlow.first { it }            // suspend (0 CPU) while screen off
                kotlinx.coroutines.delay(12_000)
                if (!screenOnFlow.value) continue
                runCatching { repository.flushPendingSends() }
                runCatching { repository.replayOutbox() }
                runCatching { repository.flushPendingMedia() }
            }
        }

        // Start the Rust ntfy WakeService so push notifications can wake the
        // device while the foreground service is alive.  Non-fatal if it
        // fails (e.g. no network yet) — the 12s outbox loop is the fallback.
        serviceScope.launch(Dispatchers.IO) {
            runCatching { repository.startNotifications() }
                .onFailure { Log.w("TRANSPORT", "startNotifications failed (non-fatal): ${it.message}") }
        }

        // Collect ntfy wake signals fired by the Rust WakeService and run a
        // full force-reconnect + catch-up cycle.  This is the path that makes
        // push notifications actually useful: ntfy arrives → Rust fires
        // on_notification_received() → this collector → forceReconnectAndCatchUp.
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            repository.notificationReceived
                .catch { /* never let collection failure kill the service */ }
                .collect {
                    Log.i("TRANSPORT", "ntfy wake signal → forceReconnectAndCatchUp")
                    forceReconnectAndCatchUp("ntfy-wake")
                }
        }

        // Background expiry sweep: disappearing messages must vanish on BOTH
        // sides on time, even when the recipient isn't looking at the chat.
        // If it ran only while a chat screen was open, the peer's copy would
        // linger until they reopened it. Run it here in the always-on service
        // every ~15s.
        sweepJob?.cancel()
        // Presence: arm the heartbeat scheduler. The mode is now driven live by
        // the lifecycle-aware presenceJob below (foreground/background/stopped)
        // instead of being pinned to "foreground" forever.
        repository.presenceArm()
        // Re-apply the manually-set status so it keeps broadcasting after a
        // process restart (presence engine state is in-memory).
        runCatching {
            val (busy, custom, color) = com.alphamessenger.ui.settings.loadStatus(applicationContext)
            if (busy || custom.isNotBlank() || color != 0) {
                repository.presenceSetMyStatus(busy, custom, color.toLong() and 0xFFFFFFFFL)
            }
        }
        // Cache maintenance (LRU/TTL) — runs at most once per 12h, cheap no-op
        // when under the limit. Only touches cacheDir; never DB/MLS/media pipeline.
        // Cache upkeep: looked at every 12 hours (runIfDue keeps the cadence).
        cacheJob?.cancel()
        cacheJob = serviceScope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { com.alphamessenger.data.CacheTrimmer(applicationContext, repository).runIfDue() }
                kotlinx.coroutines.delay(3_600_000)
            }
        }
        // BATTERY: local expiry sweep — DB-only (no network). Every 60s, and it
        // suspends entirely while the screen is off (nobody is watching a
        // disappearing message vanish then anyway; it's swept on the next
        // screen-on). 4x fewer SQLCipher decrypt passes at idle.
        // The Alpha feed: a look every few hours (anonymous, one short connection);
        // new posts are told on their own quiet channel.
        feedJob?.cancel()
        feedJob = serviceScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(60_000)
            while (true) {
                runCatching { com.alphamessenger.ui.feed.FeedNotifier.check(applicationContext, repository) }
                kotlinx.coroutines.delay(com.alphamessenger.ui.feed.FeedNotifier.EVERY_MS)
            }
        }
        sweepJob = serviceScope.launch {
            while (true) {
                // Screen on: every minute. Screen off: every 15 minutes — a
                // disappearing message must not outlive its time just because
                // the phone is locked (and its files go with it).
                if (screenOnFlow.value) kotlinx.coroutines.delay(60_000)
                else kotlinx.coroutines.withTimeoutOrNull(15 * 60_000L) { screenOnFlow.first { it } }
                runCatching { repository.sweepExpiredMessages() }
                    .onSuccess { if (it > 0u) Log.i("TRANSPORT", "background sweep removed $it expired msg(s)") }
                runCatching { com.alphamessenger.data.ViewOnceCache.sweepExpired() }
            }
        }

        // RECEIVING WATCHDOG — from outside the core. Sending and receiving live
        // apart: once the core's own heartbeat task stopped (a network wait that
        // never returned), the phone kept sending but heard nothing for hours,
        // calls included, and nothing noticed. Here the heartbeat's pulse is
        // checked every minute while the screen is on; a stopped pulse restarts
        // the sync and pulls what was missed. The check itself never blocks
        // (it reads counters, and is awaited with a deadline).
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (true) {
                screenOnFlow.first { it }
                kotlinx.coroutines.delay(60_000)
                if (!screenOnFlow.value) continue
                val probe = async(Dispatchers.IO) { repository.syncHealth() }
                val h = kotlinx.coroutines.withTimeoutOrNull(10_000) { probe.await() }
                if (h == null) {
                    Log.w("TRANSPORT", "WATCHDOG: the core did not answer its health check in 10s")
                    // Who holds the core locks: answers without taking any of them.
                    runCatching { uniffi.alphamessenger.lockReport() }
                        .onSuccess { Log.w("TRANSPORT", "CORE_LOCKS\n$it") }
                    // Which calls into the core are stuck, and where: every thread
                    // that is inside the core right now, with its stack.
                    Thread.getAllStackTraces().forEach { (t, st) ->
                        if (st.any { it.className.startsWith("uniffi.") }) {
                            Log.w("TRANSPORT", "CORE_STUCK thread=" + t.name + " state=" + t.state + "\n" +
                                st.take(14).joinToString("\n") { "  at " + it })
                        }
                    }
                    continue
                }
                if (h.pausedRelays > 0) Log.i("TRANSPORT", "WATCHDOG: ${h.pausedRelays} relay(s) paused (they asked us to slow down)")
                // The heartbeat ticks every 30s (10s when unhealthy): 150s of no
                // pulse means it is stuck, -1 means it never started.
                // Never stop the sync from here (a stop + restart from this thread
                // failed and LEFT receiving off): only the proven reconnect +
                // catch-up, and a start when the heartbeat never started at all.
                if (h.heartbeatAge > 150) {
                    Log.w("TRANSPORT", "WATCHDOG: receiving heartbeat stalled (${h.heartbeatAge}s) — reconnect + catch-up")
                    forceReconnectAndCatchUp("watchdog")
                } else if (h.heartbeatAge < 0) {
                    Log.w("TRANSPORT", "WATCHDOG: receiving heartbeat never started — starting sync")
                    runCatching { repository.startSync() }
                        .onFailure { Log.w("TRANSPORT", "WATCHDOG: start failed: ${it.message}") }
                    forceReconnectAndCatchUp("watchdog")
                }
            }
        }

        // BATTERY: presence heartbeat + peer-pull loop — THE main network drain.
        // Lifecycle-aware cadence:
        //   • screen OFF        → mode "stopped", NO publish, NO fetch, suspend.
        //   • screen ON + fg    → 30s.
        //   • screen ON + bg    → 180s.
        // presenceTick() itself is what hits the network (publish our heartbeat +
        // fetch peers), so gating IT is what removes the constant radio wakeups.
        presenceJob?.cancel()
        presenceJob = serviceScope.launch {
            while (true) {
                if (!screenOnFlow.value) {
                    runCatching { repository.presenceSetMode("stopped") }
                    // One last beat: contacts see us leave now, not a minute later.
                    runCatching { repository.presenceTick() }
                    screenOnFlow.first { it }        // suspend until the screen turns on
                    continue
                }
                val fg = appForeground
                runCatching { repository.presenceSetMode(if (fg) "foreground" else "background") }
                runCatching { repository.presenceTick() }
                val cadence = if (fg) 30_000L else 180_000L
                // Sleep up to the cadence, but wake immediately if the screen
                // turns off (so we set "stopped" and stop touching the network).
                kotlinx.coroutines.withTimeoutOrNull(cadence) { screenOnFlow.first { !it } }
            }
        }

        // Collect incoming messages and show notifications
        messageCollectionJob?.cancel()
        messageCollectionJob = serviceScope.launch {
            repository.messageReceived
                .catch { /* Collection should never fail — errors are handled per emit */ }
                .collect { message ->
                    // Only NEW messages push. Edits/reactions/deletes/read-signals
                    // update the UI but must not fire a notification.
                    val notifiable = message.msgType == uniffi.alphamessenger.MessageType.TEXT ||
                        message.msgType == uniffi.alphamessenger.MessageType.REPLY ||
                        message.msgType == uniffi.alphamessenger.MessageType.MEDIA ||
                        message.msgType == uniffi.alphamessenger.MessageType.DISAPPEARING
                    // Don't notify for the chat the user is actively viewing.
                    val isActiveChat = message.chatId != null &&
                        message.chatId == MessageNotifier.activeChatId
                    if (notifiable && !isActiveChat) {
                        // The realtime callback hands us a bare placeholder
                        // (senderId/content are empty by design — see CallbackBridge).
                        // The real row is already committed to the DB, so we read
                        // the actual sender + text from there.
                        runCatching { showSignalNotification(message.chatId) }
                    } else if (message.msgType == uniffi.alphamessenger.MessageType.EDIT ||
                               message.msgType == uniffi.alphamessenger.MessageType.DELETE) {
                        // Reflect an edit/delete in the ALREADY-shown card so the
                        // peer's notification matches reality (no second ping). If
                        // the chat has no active card, this is a no-op.
                        runCatching { refreshEditedNotification(message.chatId) }
                    }
                    // A message deleted for everyone takes its file off this phone too.
                    if (message.msgType == uniffi.alphamessenger.MessageType.DELETE) {
                        serviceScope.launch { runCatching { repository.cleanUpDeletedMedia() } }
                    }
                }
        }
    }

    @Volatile private var cachedOwnNpub: String? = null
    private suspend fun ownNpub(): String {
        cachedOwnNpub?.let { return it }
        val n = runCatching { repository.getIdentityInfo().npub }.getOrNull().orEmpty()
        if (n.isNotBlank()) cachedOwnNpub = n
        return n
    }

    // Re-read each time (cheap) so a freshly-set avatar shows in reply echoes.
    private suspend fun ownAvatarPath(): String =
        runCatching { repository.getMyProfile().avatarPath }.getOrDefault("")

    /** Read the actual newest inbound message for [chatId] from the DB, resolve
     *  the sender's real name/avatar (same source as the chat list), and post a
 * classic threaded notification. */
    private suspend fun showSignalNotification(chatId: String?) {
        val cid = chatId?.takeIf { it.isNotBlank() } ?: return

        // Never leak a blocked peer's message.
        val blocked = runCatching {
            val npub = repository.getPeerNpubForChat(cid)
            npub.isNotBlank() && repository.isUserBlocked(npub)
        }.getOrDefault(false)
        if (blocked) return
        // A stranger's request shows neither who nor what until it is accepted.
        val request = runCatching { repository.isChatRequest(cid) }.getOrDefault(false)
        // A secret chat's card names nobody and shows nothing.
        val secret = repository.secretChatOrigin(cid).isNotEmpty()

        val own = ownNpub()
        val recent = runCatching { repository.getMessages(cid, 15u, 0u) }.getOrDefault(emptyList())
        // Newest inbound, non-deleted message — the one that just arrived.
        val msg = recent
            .filter {
                !it.isDeleted && it.senderId.isNotBlank() &&
                    it.senderId != "self" && it.senderId != own
            }
            .maxByOrNull { it.createdAt }
            ?: return

        // Disappearing-timer control messages are silent — never notify.
        if (msg.content?.startsWith(com.alphamessenger.ui.chat.TIMER_MARK) == true) return
        // Nor do group events (someone left, was banned): they are read in the chat.
        if (msg.content?.startsWith(com.alphamessenger.ui.chat.NOTICE_MARK) == true) return
        // Comments under channel posts are not in `getMessages` (they live in
        // their post's discussion), so they never notify; the post itself does.

        // Muted chat/group → NO notification at all. The user explicitly silenced
        // it (notifications off), so we suppress entirely, not just make it quiet.
        val chat = runCatching { repository.listChats().firstOrNull { it.id == cid } }.getOrNull()
        val nowSec = System.currentTimeMillis() / 1000L
        // A mention of us gets through a muted group: it was meant for us.
        if ((chat?.mutedUntil ?: 0u).toLong() > nowSec && !mentionsMe(msg.content)) return

        // Durable dedup: the same message can be delivered again later (catch-up
        // reprocessing). Never pop a second notification for it.
        if (!MessageNotifier.markNotified(msg.id)) return

        val isViewOnce = msg.expiryPolicy is uniffi.alphamessenger.ExpiryPolicy.ViewOnce
        var thumbUri: String? = null
        // A forwarded message carries an invisible marker: never shown, it becomes
        // the same "↪" the chat list puts before a forwarded message.
        val forwarded = msg.content?.startsWith(com.alphamessenger.ui.chat.FWD_MARK) == true
        val content = msg.content?.removePrefix(com.alphamessenger.ui.chat.FWD_MARK)
        val glance = repository.whisperSecret(msg.id)?.takeIf { it.glance }
        val assistant = com.alphamessenger.assistant.isAssistantChat(cid)
        val text = when {
            // An assistant's reply: the answer itself, without the reasoning it carries.
            assistant && msg.msgType != uniffi.alphamessenger.MessageType.MEDIA -> msg.content.orEmpty().let { b ->
                if (com.alphamessenger.assistant.AssistantMarks.errorOf(b) != null) this.getString(R.string.assistant_preview_error)
                else notificationText(com.alphamessenger.assistant.AssistantMarks.answerOf(b))
            }
            com.alphamessenger.ui.chat.parseSecretInvite(msg.content) != null -> this.getString(R.string.secret_invite_in)
            request || secret -> this.getString(R.string.notif_new_message)
            // A whisper or a timed view is named, never shown.
            glance != null -> this.getString(if (glance.kind == "video") R.string.glance_preview_video else R.string.glance_preview_photo)
            msg.expiryPolicy is uniffi.alphamessenger.ExpiryPolicy.Whisper -> this.getString(R.string.whisper_notification)
            isViewOnce -> this.getString(R.string.sync_secret_message)
            msg.msgType == uniffi.alphamessenger.MessageType.MEDIA -> {
                val mj = msg.mediaRef?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                thumbUri = runCatching { buildThumbUri(mj) }.getOrNull()
                val (icon, name) = mediaDescriptor(mj)
                val sticker = com.alphamessenger.ui.sticker.parseStickerCaption(content)
                // "📷 caption" when there's a caption, else "📷 photo"; a sticker: "😂 Sticker".
                if (sticker != null) "${sticker.emoji.ifBlank { icon }} $name"
                else if (!content.isNullOrBlank()) "$icon ${notificationText(content)}" else "$icon $name"
            }
            content?.startsWith(com.alphamessenger.ui.chat.STORY_MARK) == true ->
                this.getString(R.string.stories_preview_reply, com.alphamessenger.ui.chat.parseStoryReply(content)?.second.orEmpty())
            !content.isNullOrBlank() -> notificationText(content)
            else -> this.getString(R.string.notif_new_message)
        }
        val body = if (forwarded) "↪ $text" else text

        val isGroup = chat?.chatType == "group"
        // A channel post comes from the channel, not from the admin who wrote it.
        val isChannel = isGroup && runCatching { repository.channelCfg(cid).on }.getOrDefault(false)
        val unread = (chat?.unreadCount ?: 1u).toInt().coerceAtLeast(1)

        // Sender display name + avatar — same resolution as the chat list
        // (peer profile keyed by the message's MLS sender_id).
        val profile = runCatching { repository.getPeerProfile(msg.senderId) }.getOrNull()
        val senderName = (if (assistant) chat?.name?.takeIf { it.isNotBlank() } ?: this.getString(R.string.assistant_title) else null)
            ?: (if (request) this.getString(R.string.requests_unknown) else null)
            ?: (if (secret) this.getString(R.string.secret_chat_title) else null)
            ?: (if (isChannel) chat?.name?.takeIf { it.isNotBlank() } else null)
            ?: profile?.displayName?.takeIf { it.isNotBlank() }
            ?: chat?.name?.takeIf { it.isNotBlank() }
            ?: if (isGroup) this.getString(R.string.participant_fallback) else this.getString(R.string.notif_new_message)
        val senderAvatar = if (request || secret || assistant) "" else {
            (if (isChannel) chat?.avatarPath?.takeIf { it.isNotBlank() } else null)
                ?: profile?.avatarPath?.takeIf { it.isNotBlank() }
                ?: chat?.avatarPath.orEmpty()
        }

        // Conversation title: group name, or the peer's name for 1:1.
        val chatTitle = if (request) this.getString(R.string.requests_title)
            else if (isGroup) (chat?.name?.takeIf { it.isNotBlank() } ?: this.getString(R.string.group_chat))
            else senderName

        MessageNotifier.appendLine(
            cid,
            MessageNotifier.Line(
                senderName = senderName,
                avatarPath = senderAvatar,
                text = body,
                timeMs = System.currentTimeMillis(),
                isSelf = false,
                imageUri = thumbUri,
                msgId = msg.id,
            ),
        )
        notifier.post(
            chatId = cid,
            chatTitle = chatTitle,
            // A channel reads like one sender (its name once, then the post),
            // not like a group conversation ("channel: channel").
            isGroup = isGroup && !isChannel,
            unreadCount = unread,
            // Muted chats returned early above; this one was sent without sound.
            muted = repository.isMessageSilent(msg.id),
            preview = if (secret) MessageNotifier.Preview.NONE else notifPreview,
            selfAvatarPath = ownAvatarPath(),
            badge = if (isGroup) MessageNotifier.ChatBadge(isChannel, chat?.avatarPath.orEmpty()) else null,
        )
    }

    /** "@OurName" in [text], as the composer writes it (spaces → "_"). */
    private suspend fun mentionsMe(text: String?): Boolean {
        if (text.isNullOrBlank() || '@' !in text) return false
        val name = runCatching { repository.getMyProfile().displayName }.getOrNull().orEmpty()
        val token = com.alphamessenger.ui.chat.v2.MentionCandidate(name, "").token
        if (token.isBlank()) return false
        return Regex("""(?<![\p{L}\p{N}_.@])@""" + Regex.escape(token) + """(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    /** EDIT/DELETE reflected in an already-posted notification card. Reads the
     *  freshly-edited (or deleted) inbound message from the DB and rewrites its
     *  exact line in the card, then re-posts SILENTLY (no new ping). No-op when
     *  the chat has no active card or the edited message isn't shown in it — so
     *  an edit never resurrects a dismissed notification. */
    private suspend fun refreshEditedNotification(chatId: String?) {
        val cid = chatId?.takeIf { it.isNotBlank() } ?: return
        if (!MessageNotifier.hasActiveCard(applicationContext, cid)) return
        if (runCatching { repository.isChatRequest(cid) }.getOrDefault(false)) return

        val own = ownNpub()
        val recent = runCatching { repository.getMessages(cid, 25u, 0u) }.getOrDefault(emptyList())
        val inbound = recent.filter {
            it.senderId.isNotBlank() && it.senderId != "self" && it.senderId != own
        }
        // The message just edited = the inbound one with the most recent editedAt.
        val edited = inbound.filter { it.editedAt != null && !it.isDeleted }
            .maxByOrNull { it.editedAt ?: "" }
        // Deleted messages vanish from the card; with nothing of theirs left, the card goes.
        val deletedIds = inbound.filter { it.isDeleted }.map { it.id }.toSet()
        val left = MessageNotifier.removeLines(cid, deletedIds)
        if (left == 0) {
            notifier.cancelChat(cid)
            return
        }

        val changed = left != null || (edited != null && MessageNotifier.editLine(
            cid, edited.id, edited.content?.takeIf { it.isNotBlank() }?.let(::notificationText) ?: return,
        ))
        if (!changed) return

        val chat = runCatching { repository.listChats().firstOrNull { it.id == cid } }.getOrNull()
        val isGroup = chat?.chatType == "group"
        val title = if (isGroup) {
            chat?.name?.takeIf { it.isNotBlank() } ?: this.getString(R.string.group_chat)
        } else {
            val sid = (edited ?: inbound.firstOrNull { !it.isDeleted })?.senderId.orEmpty()
            val prof = runCatching { repository.getPeerProfile(sid) }.getOrNull()
            prof?.displayName?.takeIf { it.isNotBlank() }
                ?: chat?.name?.takeIf { it.isNotBlank() } ?: this.getString(R.string.msg_fallback_title)
        }
        notifier.post(
            chatId = cid,
            chatTitle = title,
            isGroup = isGroup,
            unreadCount = (chat?.unreadCount ?: 1u).toInt().coerceAtLeast(1),
            muted = false,
            preview = if (repository.secretChatOrigin(cid).isNotEmpty()) MessageNotifier.Preview.NONE else notifPreview,
            selfAvatarPath = ownAvatarPath(),
            silent = true,
        )
    }

    /** Human label + emoji for a media message, derived from its MediaRef JSON.
     *  Mirrors the chat bubble's type resolution (image/video/audio/voice/file/
     *  album) so the notification says e.g. "🎤 voice message", not "Media". */
    /** (emoji-icon, human type name) for a media message, e.g. ("📷", "photo")
     *  or ("🎤", "voice"). The icon is shown before the caption so the
     *  notification reads "nick: 📷 caption". Single-item albums resolve to their
     *  one item (the picker wraps even one photo as an album). */
    /** A sign that says what kind of file it is, by its extension. */
    private fun fileEmoji(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk", "apks", "xapk", "aab" -> "📲"
        "pdf" -> "📕"
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz" -> "🗜"
        "doc", "docx", "odt", "rtf", "txt", "md", "pages" -> "📝"
        "xls", "xlsx", "ods", "csv", "numbers" -> "📊"
        "ppt", "pptx", "odp", "key" -> "📽"
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac" -> "🎵"
        "mp4", "mkv", "mov", "avi", "webm", "3gp" -> "🎬"
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "svg" -> "🖼"
        "kt", "java", "rs", "py", "js", "ts", "json", "xml", "html", "css", "sh", "c", "cpp", "h", "go", "swift" -> "💻"
        "epub", "fb2", "mobi" -> "📚"
        "ics" -> "📅"
        "vcf" -> "👤"
        "exe", "msi", "dmg", "deb", "rpm", "iso", "img" -> "💿"
        else -> "📄"
    }

    /** What a message says, as a notification shows it: no internal marker, no formatting syntax. */
    private fun notificationText(content: String): String =
        com.alphamessenger.ui.chat.text.Markup.plain(content.removePrefix(com.alphamessenger.ui.chat.FWD_MARK))

    private fun mediaDescriptor(mj: org.json.JSONObject?): Pair<String, String> {
        if (mj == null) return "📎" to this.getString(R.string.attachment_fallback)
        if (mj.optBoolean("album", false)) {
            val items = mj.optJSONArray("items")
            val n = items?.length() ?: 0
            if (n == 1) return mediaDescriptor(items!!.optJSONObject(0))
            return "🖼" to (if (n > 0) getString(R.string.album_count, n) else this.getString(R.string.preview_album))
        }
        val type = mj.optString("media_type", "").ifBlank { "file" }
        val fn = mj.optString("filename", "")
        return when (type) {
            "image" -> if (fn.startsWith("sticker_")) "🏷" to this.getString(R.string.sticker_label)
                else "📷" to this.getString(R.string.media_kind_photo)
            "video" -> when {
                fn.startsWith("gif_") -> "🎞" to "GIF"
                fn.startsWith("round_") -> "📹" to this.getString(R.string.round_label)
                else -> "🎬" to this.getString(R.string.media_kind_video)
            }
            "audio" -> if (fn.startsWith("voice")) "🎤" to this.getString(R.string.preview_voice) else "🎵" to this.getString(R.string.preview_audio)
            "file" -> fileEmoji(fn) to fn.ifBlank { this.getString(R.string.preview_file) }
            else -> "📎" to this.getString(R.string.attachment_fallback)
        }
    }

    /** Build a FileProvider content URI for a small image/video thumbnail so the
 * notification can show it inline. Uses the inline
     *  `poster_b64` carried with the message (no download needed); falls back to a
     *  downscaled local file. Returns null for non-visual media or on failure. */
    private fun buildThumbUri(mj: org.json.JSONObject?): String? {
        if (mj == null) return null
        // For albums, preview the first item.
        val node = if (mj.optBoolean("album", false))
            mj.optJSONArray("items")?.optJSONObject(0) ?: return null
        else mj
        val type = node.optString("media_type", "")
        if (type != "image" && type != "video") return null

        val bmp: android.graphics.Bitmap? =
            node.optString("poster_b64").takeIf { it.isNotBlank() }?.let { b64 ->
                runCatching {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    com.alphamessenger.util.BoundedBitmap.decode(bytes)
                }.getOrNull()
            } ?: node.optString("local_path").takeIf {
                it.isNotBlank() && java.io.File(it).exists()
            }?.let { p ->
                runCatching {
                    com.alphamessenger.data.MediaVault.decodeFile(
                        p, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    )
                }.getOrNull()
            }
        bmp ?: return null

        return runCatching {
            val dir = java.io.File(cacheDir, "notif_thumbs").apply { mkdirs() }
            // Drop stale thumbnails so the cache dir doesn't grow unbounded.
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val f = java.io.File(dir, "thumb_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(f).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f
            )
            // SystemUI renders the notification image in its own process.
            runCatching {
                grantUriPermission(
                    "com.android.systemui", uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            uri.toString()
        }.getOrNull()
    }

    /** TRANSPORT hardening: force a clean relay reconnect, then resubscribe +
     *  catch-up + flush the outbox. Retries briefly because the OS "network
     *  available" event can precede actual routable connectivity. */
    @Volatile private var lastReconnectAtMs: Long = 0L
    // COALESCING: several independent sources — ntfy wake, network onAvailable,
    // network onAvailable, watchdog, relay-connected — can each trigger a full
    // (heavy, 165-547 event) catch-up almost simultaneously. Even with a healthy
    // watermark that's a lot of duplicate work. Collapse them: only ONE catch-up
    // runs at a time; triggers that arrive while one is in flight set a single
    // `pending` flag → exactly ONE trailing run after it finishes (so 10 wakes →
    // at most 2 runs, never 10). Plus a 12s min interval for background triggers
    // so a sustained ntfy flood (every ~5s) can't fire a heavy catch-up more than
    // once per 12s. Overlap window is UNCHANGED (300s) — separate experiment.
    @Volatile private var catchUpInFlight = false
    @Volatile private var catchUpPending = false
    @Volatile private var catchUpStartedAtMs = 0L
    private val catchUpLock = Any()
    // If an in-flight catch-up hasn't finished in this long it's considered hung
    // (blocking FFI / relay stall) and is RECLAIMED — otherwise a single stuck
    // run would coalesce every future trigger forever = permanent silence.
    private val CATCHUP_STALE_MS = 45_000L

    private suspend fun forceReconnectAndCatchUp(reason: String) {
        synchronized(catchUpLock) {
            if (catchUpInFlight) {
                val age = android.os.SystemClock.elapsedRealtime() - catchUpStartedAtMs
                if (age < CATCHUP_STALE_MS) {
                    catchUpPending = true
                    Log.i("TRANSPORT", "catch-up coalesced — in-flight ${age}ms (reason=$reason → pending)")
                    return
                }
                // Previous run is stuck — reclaim and start fresh (don't block forever).
                Log.w("TRANSPORT", "catch-up in-flight STALE ${age}ms — reclaiming (previous run hung), reason=$reason")
            }
            val since = android.os.SystemClock.elapsedRealtime() - lastReconnectAtMs
            // Every network change catches up: what arrived during the gap is behind it.
            val minInterval = when (reason) {
                "foreground" -> 60_000L
                REASON_NETWORK -> 0L
                else -> 12_000L
            }
            if (since < minInterval) {
                Log.i("TRANSPORT", "catch-up coalesced ($reason, ${since}ms < ${minInterval}ms)")
                return
            }
            catchUpInFlight = true
            catchUpStartedAtMs = android.os.SystemClock.elapsedRealtime()
            lastReconnectAtMs = android.os.SystemClock.elapsedRealtime()
        }
        try {
            do {
                synchronized(catchUpLock) { catchUpPending = false }
                runReconnectCatchUpOnce(reason)
            } while (synchronized(catchUpLock) { catchUpPending })
        } finally {
            synchronized(catchUpLock) { catchUpInFlight = false }
        }
    }

    private suspend fun runReconnectCatchUpOnce(reason: String) {
        // An ntfy wake must NOT tear down the live relay subscription: a burst of
        // wakes (a peer creating a dozen groups → a dozen pushes) would fire a
        // force_reconnect each time → the subscription is repeatedly torn down and
        // left SILENT → the device stops receiving. For a wake we only PULL
        // (catch-up + flush) on the EXISTING subscription. If that subscription
        // genuinely goes silent, the Rust heartbeat self-heals it (resubscribe,
        // cooldown-gated) within STALE_SECS; a network change reconnects in the
        // core itself (setNetworkAvailable). Full teardown+reconnect stays ONLY for
        // the foreground, where it is actually warranted.
        // Back in the foreground with every relay still connected: the live
        // subscription never stopped — pull, don't tear five healthy sockets
        // down (each reconnect is a TLS handshake + a fresh REQ per relay).
        val healthyForeground = reason == "foreground" &&
            repository.syncHealth()?.let { it.relays > 0 && it.connected == it.relays } == true
        if (healthyForeground) Log.i("TRANSPORT", "foreground: all relays connected — catch-up only")
        if (reason != "ntfy-wake" && reason != REASON_NETWORK && !healthyForeground) {
            var ok = false
            for (attempt in 1..3) {
                val r = runCatching { repository.forceReconnect() }
                if (r.isSuccess) { ok = true; Log.i("TRANSPORT", "force_reconnect ok ($reason, attempt $attempt)"); break }
                Log.w("TRANSPORT", "force_reconnect failed ($reason, attempt $attempt): ${r.exceptionOrNull()?.message}")
                kotlinx.coroutines.delay(1500L * attempt)
            }
            if (!ok) runCatching { repository.startSync() } // last resort
        }
        runCatching { repository.catchUp() }
            .onSuccess { Log.i("TRANSPORT", "catch-up pulled $it events ($reason)") }
        runCatching { repository.flushPendingSends() }
            .onSuccess { if (it > 0u) Log.i("TRANSPORT", "flushed $it pending send(s)") }
        runCatching { repository.replayOutbox() }
            .onSuccess { if (it > 0u) Log.i("TRANSPORT", "replayed $it outbox envelope(s)") }
        runCatching { repository.flushPendingMedia() }
            .onSuccess { if (it > 0u) Log.i("TRANSPORT", "flushed $it pending media") }
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    /** The default network as the system reports it, and the one the core was told about. */
    @Volatile private var defaultNetwork: android.net.Network? = null
    private var appliedNetwork: android.net.Network? = null
    private val networkLock = kotlinx.coroutines.sync.Mutex()

    /** The core follows the default network: a new one (Wi-Fi ↔ mobile, or back
     *  from none) reconnects every relay at once and catches up; losing it
     *  closes them until it returns. */
    private fun registerNetworkCatchUp() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        // Registering replays the current network: that one the core already uses.
        defaultNetwork = cm.activeNetwork
        appliedNetwork = defaultNetwork
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                defaultNetwork = network
                applyDefaultNetwork()
            }
            override fun onLost(network: android.net.Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    applyDefaultNetwork()
                }
            }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                repository.setNetworkMetered(!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
        // Started without a network: the core must not dial until one appears.
        if (defaultNetwork == null) serviceScope.launch {
            networkLock.withLock { runCatching { repository.setNetworkAvailable(false) } }
        }
    }

    /** Serialised, and always toward the latest network: a burst of callbacks
     *  (lost → available within a second) ends in one consistent state. */
    private fun applyDefaultNetwork() {
        serviceScope.launch {
            val reconnected = networkLock.withLock {
                val network = defaultNetwork
                if (network == appliedNetwork) return@withLock false
                appliedNetwork = network
                Log.w("TRANSPORT", "default network ${if (network != null) "changed" else "lost"}")
                runCatching { repository.setNetworkAvailable(network != null) }
                    .onFailure { Log.w("TRANSPORT", "setNetworkAvailable failed: ${it.message}") }
                network != null
            }
            if (reconnected) forceReconnectAndCatchUp(REASON_NETWORK)
        }
    }

    private fun unregisterNetworkCatchUp() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    private fun stopSyncAndSelf() {
        serviceScope.launch {
            try {
                repository.stopSync()
            } catch (_: Exception) {
                // Best-effort
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --- Notifications ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sync_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MessageSyncService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AlphaMessenger")
            .setContentText(this.getString(R.string.sync_notification_text))
            .setSmallIcon(R.drawable.ic_stat_alpha)
            .setColor(MessageNotifier.ACCENT)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                this.getString(R.string.sync_stop),
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

}

/**
 * WorkManager worker that performs a lightweight [AlphaCoreRepository.catchUp]
 * to pull messages that were missed while the foreground service was not running.
 *
 * Runs at most once every 15 minutes (WorkManager minimum interval) and
 * requires a network connection.
 */
@HiltWorker
class PeriodicSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: AlphaCoreRepository,
    private val coreHolder: com.alphamessenger.data.CoreHolder,
    private val unlockManager: com.alphamessenger.data.UnlockManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // The process was gone: open the database with the remembered key and bring
        // the live connection back. Without such a key (password asked after every
        // restart) this cycle is skipped and the next unlock resumes the sync.
        if (!coreHolder.isUnlocked()) {
            if (!unlockManager.unlockWithoutUser()) return Result.success()
            // Background starts of a foreground service may be refused: the pull below still runs.
            runCatching { (applicationContext as com.alphamessenger.AlphaMessengerApp).startSyncServiceFromActivity() }
        }
        return try {
            // The live connection already delivers: the pull is a backstop, once an hour at most.
            val h = repository.syncHealth()
            val live = h != null && h.heartbeatAge in 0..150 && h.relays > 0 && h.connected >= h.relays
            if (!(live && h!!.catchupAge in 0..3600)) repository.catchUp()
            repository.flushPendingSends()
            repository.flushPendingMedia()
            // Re-publish Commit/Welcome envelopes that were queued while the
            // foreground service was not running (app killed, force-stopped).
            repository.replayOutbox()
            Result.success()
        } catch (_: Exception) {
            // WorkManager will retry with exponential backoff
            Result.retry()
        }
    }

    /**
     * Required so this worker can run as an EXPEDITED job on Android 8–11
     * (pre-12 runs expedited work as a short foreground service, which needs a
     * notification). On Android 12+ short expedited jobs don't surface this.
     *  Reuses a dedicated low-importance channel so the brief "delivering…" notice
     * is silent and unobtrusive.
     */
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val ctx = applicationContext
        val channelId = "alpha_delivery_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, ctx.getString(R.string.sync_delivery_channel), NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val notif = NotificationCompat.Builder(ctx, channelId)
            .setContentTitle(ctx.getString(R.string.sync_sending))
            .setSmallIcon(R.drawable.ic_stat_alpha)
            .setColor(MessageNotifier.ACCENT)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        return androidx.work.ForegroundInfo(3001, notif)
    }
}
