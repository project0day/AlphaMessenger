package com.alphamessenger.data

import android.content.Context
import com.alphamessenger.R
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.alphamessenger.AlphaMessengerCore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UnlockManager — the SINGLE entry point to the database lifecycle.
 *
 * It is the only place that calls [AlphaMessengerCore.newUnlocked]. Given the
 * unlocked MasterDataKey (from biometric Keystore or the Recovery-Password path),
 * it:
 *   1. reads the pre-open migration hint + one-shot recovery flag from
 *      [MigrationStateStore] (plaintext, readable before the DB is open);
 *   2. builds the core via `newUnlocked`, which runs the Rust deterministic
 *      resolver to pick the right key and open the DB;
 *   3. installs the core into [CoreHolder] (flips the unlocked state);
 *   4. reconciles the authoritative DB KV state back into the pre-open mirror.
 *
 * # Orchestration only — no cryptography
 *
 * Android never decides which key to use or replicates the resolver: it passes
 * the hint + flag + master key in and reads `dbRekeyState()` /
 * `dbDidRecoveryRestore()` back out. The chicken-and-egg of the cold-start
 * password path is solved by `deriveMasterKeyFromPublic`, which runs
 * BEFORE this — the caller passes the resulting master key here.
 *
 * # Scope
 *
 * `initializeCallbacks()` + `initialize()` were moved here from `Application.onCreate`:
 * they touch the core, so they may run only after `newUnlocked` opened the DB.
 * This class is still NOT wired into the App/Activity lifecycle — the unlock
 * gate that calls it lives in the lock flow. The `migrateDbToRecoveryKey()`
 * trigger + confirm coordinator are driven from here too. `dbPath` and the
 * default relays live here (not in `CoreModule`).
 */
@Singleton
class UnlockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coreHolder: CoreHolder,
    private val migrationStateStore: MigrationStateStore,
    private val repository: AlphaCoreRepository,
    private val recoveryKeyManager: RecoveryKeyManager,
    // Unfinished media sends resume HERE, not in Application: the queue works
    // with the DB, and the DB isn't open before unlock.
    private val mediaSendQueue: com.alphamessenger.ui.media.send.MediaSendQueue,
    private val recoveryKdfPublicStore: RecoveryKdfPublicStore,
) {
    private val gson = Gson()

    // The gate, the sync service and a worker may all try at once: one core only.
    private val opening = kotlinx.coroutines.sync.Mutex()

    /**
     * Opens the database with no one at the screen: after a restart, from the sync
     * service, a worker or the boot broadcast. Possible when no Recovery Password is
     * set, or when its key is remembered on this phone ([RememberedDbKey]).
     */
    suspend fun unlockWithoutUser(): Boolean {
        if (coreHolder.isUnlocked()) return true
        // Before the account exists the onboarding opens the database itself.
        val onboarded = context.getSharedPreferences("alpha_onboarding", Context.MODE_PRIVATE).getBoolean("onboarding_completed", false)
        if (!onboarded || !context.getDatabasePath(DB_NAME).exists()) return false
        val key = if (recoveryKdfPublicStore.has()) RememberedDbKey.get(context) ?: return false else null
        return try {
            when (unlock(key)) {
                is UnlockResult.Failed -> {
                    // A key that no longer opens it (password changed elsewhere): ask once.
                    if (key != null) RememberedDbKey.clear(context)
                    false
                }
                else -> true
            }
        } finally {
            key?.fill(0)
        }
    }

    /**
     * Build and install the core for this session.
     *
     * @param masterKey the 32-byte MasterDataKey, or null/empty to force a legacy
     *   open (brand-new install or pre-password onboarding, where there is no
     *   key and nothing sensitive yet).
     * @return [UnlockResult] describing the outcome.
     */
    suspend fun unlock(masterKey: ByteArray?): UnlockResult = opening.withLock { open(masterKey) }

    private suspend fun open(masterKey: ByteArray?): UnlockResult = withContext(Dispatchers.IO) {
        if (coreHolder.isUnlocked()) return@withContext UnlockResult.AlreadyUnlocked

        val dbPath = context.getDatabasePath(DB_NAME).absolutePath
        File(dbPath).parentFile?.mkdirs()

        val relaysJson = gson.toJson(DEFAULT_RELAY_URLS)
        val hint = migrationStateStore.getHint()
        val recoveryAttempted = migrationStateStore.getRecoveryAttempted()
        val masterUBytes = (masterKey ?: ByteArray(0)).map { it.toUByte() }
        val deviceKey = DeviceDbKey.get(context)
        // No keystore key and no database yet: none is created without a key.
        if (deviceKey.isEmpty() && masterKey == null && !File(dbPath).exists()) {
            return@withContext UnlockResult.Failed(IllegalStateException(context.getString(R.string.unlock_no_device_key)))
        }

        val core = try {
            AlphaMessengerCore.newUnlocked(dbPath, masterUBytes, relaysJson, hint, recoveryAttempted, deviceKey.map { it.toUByte() })
        } catch (e: Exception) {
            return@withContext UnlockResult.Failed(e)
        } finally {
            deviceKey.fill(0)
        }

        coreHolder.install(core)
        // It opened the database: the next restart needs no password.
        if (masterKey != null && masterKey.size == 32) RememberedDbKey.save(context, masterKey)

        // Migration state as the DB was actually opened. "migrated" here means the
        // DB just opened with the production key, so the pre-rekey backup is no
        // longer needed and must be deleted (it is an unencrypted copy).
        val stateAtOpen = runCatching { core.`dbRekeyState`() }.getOrNull()

        // Reconcile the authoritative DB KV state back into the pre-open mirror.
        runCatching { migrationStateStore.setHint(core.`dbRekeyState`()) }
        runCatching { if (core.`dbDidRecoveryRestore`()) migrationStateStore.setRecoveryAttempted(true) }

        // The core now exists, so perform the one-time setup
        // that used to run eagerly in Application.onCreate. Both touch the core and
        // therefore MUST run only here, after newUnlocked opened the DB.
        runCatching { repository.initializeCallbacks() }
        runCatching { repository.initialize() }
        // Received media is sealed under this key; it is held only in memory.
        runCatching { MediaVault.install(core.`mediaVaultKey`()) }
        sealOldCache()

        // Private mode is the core's setting; the app's mirror (read before unlock
        // to start Tor early) follows it, or the screen could say "private" while
        // traffic goes direct.
        runCatching {
            val coreTor = core.`isTorEnabled`()
            val prefs = NetworkPrivacyPrefs(context)
            if (prefs.torEnabled != coreTor) {
                prefs.torEnabled = coreTor
                if (coreTor) EmbeddedTor.start(context) else EmbeddedTor.stop(context)
            }
        }

        // Secret chats reach their relays only through Tor, private mode or not.
        // A core that cannot answer leaves the mirror as it was.
        repository.hasSecretChats()?.let { EmbeddedTor.needForSecretChats(context, it) }

        // Sends left unfinished by the previous process: preparation resumes from
        // the finished files, upload restarts from scratch. Their messages are
        // already in the DB, so the user sees their bubbles right after unlock —
        // instead of discovering "it never sent".
        runCatching { mediaSendQueue.resume() }

        // C2 migration trigger: if we have a real key and the DB is still
        // legacy, migrate it to the production DatabaseKey now — after init, before
        // heavy use. A real (32-byte) master key means a Recovery Password is set
        // (an onboarding legacy open passes null and is skipped). Best-effort: a
        // failure leaves the DB usable under the legacy key and the migration
        // retries next launch (Rust restored the pre-rekey backup). Android makes
        // NO state decision here — it only reads dbRekeyState() and calls migrate.
        if (masterKey != null && masterKey.size == 32 && runCatching { core.`dbRekeyState`() }.getOrNull() == "legacy") {
            runCatching { repository.migrateDbToRecoveryKey(masterKey) }
        }

        if (stateAtOpen == "migrated") {
            runCatching { repository.confirmDbMigration() }
        }

        // NOTE: biometric master-key caching is NOT done here. The Keystore key is
        // auth-per-use (CryptoObject-bound), so caching MUST happen behind a
        // BiometricPrompt — handled by the explicit "enable biometric" flow in
        // AppLockSettings. A bare ENCRYPT here would always fail.

        UnlockResult.Unlocked
    }

    /** Once: media cached before the vault is sealed in the background. */
    private fun sealOldCache() {
        val prefs = context.getSharedPreferences("media_vault", Context.MODE_PRIVATE)
        if (prefs.getBoolean("sealed_v1", false)) return
        repository.detachedScope.launch(Dispatchers.IO) {
            val n = MediaVault.sealExisting(listOf(File(context.cacheDir, "media"), File(context.filesDir, "gifs")))
            android.util.Log.i("MediaVault", "sealed $n cached file(s)")
            prefs.edit().putBoolean("sealed_v1", true).apply()
        }
    }

    sealed interface UnlockResult {
        /** Core built and installed; the DB is open under the resolved key. */
        data object Unlocked : UnlockResult
        /** A core was already installed for this session. */
        data object AlreadyUnlocked : UnlockResult
        /** Open failed (e.g. wrong key for a confirmed DB). Caller surfaces it. */
        data class Failed(val cause: Throwable) : UnlockResult
    }

    companion object {
        private const val DB_NAME = "alpha_messenger.db"

        // NOTE: these moved out of CoreModule, which drops its
        // eager core provider. Kept identical to avoid changing the relay set.
        // The same as the core's DEFAULT_RELAYS: no single relay that
        // throttles or bans may decide whether a message arrives.
        private val DEFAULT_RELAY_URLS = listOf(
            "wss://relay.primal.net",
            "wss://nostr.oxtr.dev",
            "wss://relay.nostr.net",
            "wss://nostr.data.haus",
            "wss://nostr-01.yakihonne.com",
        )
    }
}
