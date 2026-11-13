package com.alphamessenger

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.service.MessageSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application class — the entry point for Hilt DI and FFI initialisation.
 *
 * ## Startup sequence (NO DB ACCESS BEFORE UNLOCK)
 * 1. Load the native `alpha_ffi` library (JNA/UniFFI bindings).
 * 2. Open the (DB-independent) view-once store.
 * 3. Enqueue a periodic WorkManager sync as a fallback (gated: the worker
 *    early-returns while locked).
 *
 * The Rust core is NOT built here — it opens the encrypted DB in its
 * constructor, so it is created only after unlock by `UnlockManager.unlock()`,
 * which then performs callback wiring + core init and starts the foreground
 * [MessageSyncService]. Application.onCreate has zero DB side effects.
 */
@HiltAndroidApp
class AlphaMessengerApp : Application(), Configuration.Provider, coil3.SingletonImageLoader.Factory {

    // Sealed media (see MediaVault) is decrypted on its way to the screen.
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .components { add(com.alphamessenger.data.VaultImageFetcher.Factory()) }
            .build()

    private val tag = "AlphaMessengerApp"

    @Inject
    lateinit var repository: AlphaCoreRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // WorkManager picks this up automatically (the default initialiser is
    // disabled in the manifest). Without it `@HiltWorker` workers cannot
    // be instantiated.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")

    override fun onCreate() {
        super.onCreate()
        // The Tor process hosts only Tor: none of the app starts there.
        if (processName().endsWith(":tor")) return

        // 1. Load the native library — required for UniFFI/JNA bindings.
        try {
            System.loadLibrary("alpha_ffi")
            Log.i(tag, "Loaded native library alpha_ffi")
        } catch (e: UnsatisfiedLinkError) {
            // Without the .so the app is non-functional. Log loudly and bail.
            Log.e(tag, "FAILED to load native library alpha_ffi — app cannot run", e)
            return
        }

        // 1a. Private mode: start Tor now, it bootstraps while the core unlocks.
        com.alphamessenger.data.EmbeddedTor.startIfEnabled(this)
        // Keystore work: off the main thread.
        Thread { com.alphamessenger.assistant.AssistantStore.init(this) }.start()
        // Tor just finished connecting: bring the relays back now rather than
        // at the pool's next retry.
        appScope.launch {
            com.alphamessenger.data.EmbeddedTor.state.collect { st ->
                val tor = com.alphamessenger.data.EmbeddedTor
                if (st == com.alphamessenger.data.EmbeddedTor.State.ON && (tor.enabled.value || tor.runsForSecretChats)) {
                    runCatching { repository.forceReconnect() }
                    // At start Tor was not up yet: the time could not be checked.
                    if (!com.alphamessenger.util.TrustedTime.isSynced) {
                        launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.alphamessenger.util.TrustedTime.sync(viaTor = true) } }
                    }
                }
            }
        }

        // 1b. Open the encrypted view-once store and sweep expired secrets.
        com.alphamessenger.data.ViewOnceCache.init(this)

        // Load the MediaSizeFormat unit patterns in the app
        // language — covers every process/path that formats sizes.
        com.alphamessenger.media.MediaSizeFormat.install(this)

        // Global string resolver for context-less converters (frozen media code).
        com.alphamessenger.util.AppStrings.install(this)

        // NO DB ACCESS BEFORE UNLOCK.
        // The Rust core opens the encrypted database in its constructor, so it is
        // NOT built here. Callback wiring (`initializeCallbacks`) and core init
        // (`initialize`) both touch the core and therefore live in
        // `UnlockManager.unlock()`, which runs only AFTER the MasterDataKey is
        // available and `AlphaMessengerCore.newUnlocked(...)` has opened the DB.
        // Application.onCreate has ZERO DB side effects: only the native lib
        // load, the (DB-independent) view-once store, and WorkManager scheduling.

        // Enqueue the periodic WorkManager fallback. The worker is resolvable
        //    via the HiltWorkerFactory above. The MessageSyncService itself
        //    starts from [MainActivity] (foreground services may NOT be
        //    started from Application.onCreate on Android 12+; doing so
        //    crashes the process with ForegroundServiceStartNotAllowedException
        //    whenever WorkManager / a BroadcastReceiver wakes us up).
        MessageSyncService.enqueuePeriodicSync(this)
        com.alphamessenger.service.AutoBackupWorker.schedule(this)

        // Eagerly touch the CallManager singleton so it starts listening for
        // incoming call signals (GiftWrap/MLS ChatState) from the very start.
        // Without this, an incoming call wouldn't ring until the user opened
        // a chat (which is too late). Resolved via a Hilt EntryPoint because
        // Application.onCreate can't @Inject yet at this stage.
        runCatching {
            val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                this, com.alphamessenger.calls.CallLauncher.CallsEntryPoint::class.java,
            )
            ep.callManager() // triggers Hilt singleton creation → starts listening
        }.onFailure { Log.w(tag, "CallManager eager init deferred: ${it.message}") }
        // Group calls listen from the start too (a ring must not wait for a screen).
        runCatching { com.alphamessenger.calls.group.GroupCalls.manager(this) }
            .onFailure { Log.w(tag, "GroupCallManager eager init deferred: ${it.message}") }
    }

    /**
     * Called from [com.alphamessenger.ui.MainActivity.onCreate] when an
     * Activity is in the foreground — only then is starting a foreground
     * service permitted on Android 12+.
     */
    fun startSyncServiceFromActivity() {
        val intent = Intent(this, MessageSyncService::class.java).apply {
            action = MessageSyncService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i(tag, "Sync service requested to start")
        } catch (e: Exception) {
            // Defensive: even from an Activity, MIUI / OEM background limits
            // can still throw. Log so we know but don't crash the UI thread.
            Log.e(tag, "startSyncService failed — relays will not sync", e)
        }
    }

    override fun onTerminate() {
        repository.shutdown()
        appScope.cancel()
        super.onTerminate()
    }
}
