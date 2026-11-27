package com.alphamessenger.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import uniffi.alphamessenger.IdentityInfo
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.alphamessenger.data.RecoveryKeyManager
import com.alphamessenger.data.RecoveryKdfPublicStore
import kotlinx.coroutines.withContext
import javax.inject.Inject

// --- Data models ---

data class RelayInfo(
    val url: String,
    val isRead: Boolean = true,
    val isWrite: Boolean = true,
    val isEnabled: Boolean = true,
    val isConnected: Boolean = false,
    /** 0 = unknown / not yet measured / unreachable. */
    val pingMs: Int = 0,
    val isTor: Boolean = false,
    /** The core's word: connected, connecting, unreachable, limited or disabled. */
    val state: String = "connecting",
    /** Seconds to the next try (unreachable) or to the end of its pause (limited). */
    val retrySecs: Int = 0,
    /** Serves our inbox only after sign-in: other relays bring our private messages. */
    val inboxAuth: Boolean = false,
    val accepted: Int = 0,
    val rejected: Int = 0,
    /** The relay's own words for its last refusal; empty when the last event went in. */
    val lastError: String = "",
)

/** Health bucket derived from connectivity + ping. */
enum class RelayHealth { GREEN, YELLOW, RED, UNKNOWN }

/**
 * Curated list of healthy Nostr relays vetted for AlphaMessenger.
 *
 * Update this list as relays come and go. For now it lives in-app; a future
 * iteration can fetch it from a signed remote JSON.
 */
object CuratedRelays {
    /** Reliable clearnet relays — large, well-operated public relays. */
    // Probed 2026-11: open to new keys, our kinds stored and served back, 30 days kept.
    val clearnet: List<String> = listOf(
        "wss://relay.primal.net",
        "wss://nostr.oxtr.dev",
        "wss://relay.nostr.net",
        "wss://nostr.data.haus",
        "wss://nostr-01.yakihonne.com",
    )

    /** Tor onion-routed relays — require Orbot / SOCKS5 to reach. */
    val tor: List<String> = listOf(
        "ws://nostrf6lqd7vsnmppzkqipnz6mvr3ahyjekoxerf3svtdkagrtelfjid.onion",
    )
}

/** Map the FFI relay snapshot to the UI model. */
fun uniffi.alphamessenger.RelayInfo.toUi(): RelayInfo = RelayInfo(
    url = url,
    isRead = read,
    isWrite = write,
    isEnabled = enabled,
    isConnected = connected,
    pingMs = pingMs.toInt(),
    isTor = isTor,
    state = state,
    retrySecs = retrySecs.toInt(),
    inboxAuth = inboxAuth,
    accepted = accepted.toInt(),
    rejected = rejected.toInt(),
    lastError = lastError,
)

/** From what the relay really does with us, not from a ping: a slow relay that takes our events is fine. */
fun RelayInfo.health(): RelayHealth = when {
    !isEnabled -> RelayHealth.UNKNOWN
    state == "unreachable" -> RelayHealth.RED
    state == "connected" && rejected > 0 && accepted == 0 -> RelayHealth.RED
    state == "connected" && lastError.isEmpty() && !inboxAuth -> RelayHealth.GREEN
    else -> RelayHealth.YELLOW
}

data class SettingsUiState(
    // --- Identity ---
    val identityInfo: IdentityInfo? = null,
    val displayName: String = "",
    val statusMessage: String = "",

    // --- Network / Relays ---
    val relays: List<RelayInfo> = emptyList(),
    val torEnabled: Boolean = false,
    val newRelayUrl: String = "",
    val pinging: Boolean = false,
    val lastPingedAt: Long = 0L,

    // --- Privacy ---
    val typingIndicatorsEnabled: Boolean = true,
    /** Notification preview: "full" (name+text) | "name" | "none". */
    val notifPreview: String = "full",

    // --- UI state ---
    val isLoading: Boolean = false,
    val error: String? = null
)

// --- DataStore preference keys ---

private object Prefs {
    val TOR_ENABLED = booleanPreferencesKey("tor_enabled")
    val TYPING_INDICATORS = booleanPreferencesKey("typing_indicators")
    val DISPLAY_NAME = stringPreferencesKey("display_name")
    val STATUS_MESSAGE = stringPreferencesKey("status_message")
    val NOTIF_PREVIEW = stringPreferencesKey("notif_preview")
}

// --- SettingsViewModel — full DataStore persistence for all toggles ---

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val repository: AlphaCoreRepository,
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val appContext: Context,
    private val recoveryKeyManager: RecoveryKeyManager,
    private val recoveryKdfPublicStore: RecoveryKdfPublicStore,
    private val strings: com.alphamessenger.util.Strings,
    private val networkPrivacy: com.alphamessenger.data.NetworkPrivacyPrefs,
) : ViewModel() {

    // --- "Don't keep on servers" default for all chats (owned by the core) ---
    private val _noStoreDefault = MutableStateFlow(false)
    val noStoreDefault: StateFlow<Boolean> = _noStoreDefault.asStateFlow()

    fun toggleNoStoreDefault(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setNoStoreDefault(enabled) }
                .onSuccess { _noStoreDefault.value = enabled }
        }
    }

    // --- Ghost mode (owned by the core) ---
    private val _ghost = com.alphamessenger.ui.components.GhostModeState.on
    val ghost: StateFlow<Boolean> = _ghost.asStateFlow()

    fun toggleGhost(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setGhostMode(enabled) }.onSuccess { _ghost.value = enabled }
        }
    }

    // --- Read receipts (owned by the core) ---
    private val _readReceipts = MutableStateFlow(true)
    val readReceipts: StateFlow<Boolean> = _readReceipts.asStateFlow()

    fun toggleReadReceipts(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setReadReceiptsEnabled(enabled) }
                .onSuccess { _readReceipts.value = enabled }
        }
    }

    // --- Calls privacy ---
    private val _callsRelayOnly = MutableStateFlow(networkPrivacy.callsRelayOnly)
    val callsRelayOnly: StateFlow<Boolean> = _callsRelayOnly.asStateFlow()
    private val _turn = MutableStateFlow(Triple(networkPrivacy.turnUrl, networkPrivacy.turnUser, networkPrivacy.turnPass))
    val turn: StateFlow<Triple<String, String, String>> = _turn.asStateFlow()

    private val _linkPreviews = MutableStateFlow(networkPrivacy.linkPreviews)
    val linkPreviews: StateFlow<Boolean> = _linkPreviews.asStateFlow()

    fun toggleLinkPreviews(enabled: Boolean) {
        networkPrivacy.linkPreviews = enabled
        _linkPreviews.value = enabled
    }

    private val _inAppVideo = MutableStateFlow(networkPrivacy.inAppVideo)
    val inAppVideo: StateFlow<Boolean> = _inAppVideo.asStateFlow()

    fun toggleInAppVideo(enabled: Boolean) {
        networkPrivacy.inAppVideo = enabled
        _inAppVideo.value = enabled
    }

    fun toggleCallsRelayOnly(enabled: Boolean) {
        networkPrivacy.callsRelayOnly = enabled
        _callsRelayOnly.value = enabled
    }

    fun saveTurn(url: String, user: String, pass: String) {
        networkPrivacy.turnUrl = url
        networkPrivacy.turnUser = user
        networkPrivacy.turnPass = pass
        _turn.value = Triple(networkPrivacy.turnUrl, networkPrivacy.turnUser, networkPrivacy.turnPass)
    }

    /** True if a Recovery Password is set (status row in Security settings). */
    fun hasRecoveryPassword(): Boolean =
        runCatching { repository.hasRecoveryPassword() }.getOrDefault(false)

    /** Last cloud-backup status for the security status block. */
    data class BackupStatus(val has: Boolean, val at: String)

    fun backupStatus(): BackupStatus = runCatching {
        val o = org.json.JSONObject(repository.getBackupInfo())
        BackupStatus(o.optBoolean("has", false), o.optString("at", ""))
    }.getOrDefault(BackupStatus(false, ""))

    /**
     * Step 1 of biometric activation (C2): derive the master key from the
     * Recovery Password using the public KDF mirror (no DB). [onResult] returns
     * the 32-byte master key (caller must zeroize) or an error. The caller then
     * binds an ENCRYPT cipher ([cacheCipher]) to a BiometricPrompt and calls
     * [finishBiometricCache] — this is the reliable, auth-bound caching path.
     */
    fun deriveMasterForBiometric(password: String, onResult: (ByteArray?, String?) -> Unit) {
        viewModelScope.launch {
            val json = recoveryKdfPublicStore.get()
            if (json.isNullOrBlank()) { onResult(null, strings.get(R.string.settings_kdf_missing)); return@launch }
            try {
                val master = withContext(Dispatchers.IO) {
                    // Throws on a wrong password (verifier mismatch). No DB access.
                    uniffi.alphamessenger.deriveMasterKeyFromPublic(password, json)
                        .map { it.toByte() }.toByteArray()
                }
                onResult(master, null)
            } catch (e: Exception) {
                onResult(null, strings.get(R.string.gate_wrong_password))
            }
        }
    }

    /** ENCRYPT cipher for biometric-bound master-key caching (pass to CryptoObject). */
    fun cacheCipher(): javax.crypto.Cipher? = recoveryKeyManager.getCacheCipher()

    /** Drop the cached biometric key (when the user disables biometric). */
    fun clearBiometricCache() = recoveryKeyManager.clearCachedKey()

    /** Finish caching with the biometric-authenticated cipher. Returns success. */
    fun finishBiometricCache(cipher: javax.crypto.Cipher, master: ByteArray): Boolean {
        val mk = com.alphamessenger.data.MasterDataKey(master.copyOf())
        val ok = recoveryKeyManager.finishCacheMasterKey(cipher, mk)
        mk.close()
        return ok
    }

    /**
     * Change the Recovery Password (C2). Re-keys the DB in Rust, then re-caches
     * the new MasterDataKey for biometric unlock if biometric is enabled (the old
     * cache holds the stale key). [onResult] gets (success, errorMessage?).
     */
    fun changeRecoveryPassword(old: String, new: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val newMaster = repository.changeRecoveryPassword(old, new)
                runCatching {
                    val lock = com.alphamessenger.data.AppLockManager(appContext)
                    if (lock.biometricEnabled) {
                        // Replace the now-stale cached key with the new one.
                        recoveryKeyManager.clearCachedKey()
                        val mk = com.alphamessenger.data.MasterDataKey(newMaster.copyOf())
                        recoveryKeyManager.cacheMasterKey(mk)
                        mk.close()
                    }
                }
                newMaster.fill(0)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    private val _identityState = MutableStateFlow<IdentityInfo?>(null)
    private val _newRelayUrl = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    // --- Relays are sourced from Repository (singleton cache) ---
    // so they don't flash yellow→green every time we re-enter the screen.
    private val _relaysState = repository.relayStates
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _pinging = repository.relayPinging
    private val _lastPingedAt = repository.relayLastPingedAt

    // G-11: 1:1 user blocklist (device-local).
    private val _blockedUsers = kotlinx.coroutines.flow.MutableStateFlow<List<com.alphamessenger.data.BlockedUser>>(emptyList())
    val blockedUsers: StateFlow<List<com.alphamessenger.data.BlockedUser>> = _blockedUsers.asStateFlow()
    fun refreshBlocked() { _blockedUsers.value = repository.blockedUsers() }

    // --- Who may reach me (rules the core enforces on this phone) ---
    private val _privacy = MutableStateFlow<Map<String, String>>(emptyMap())
    val privacy: StateFlow<Map<String, String>> = _privacy.asStateFlow()
    // Off the main thread: the core may be busy with sync, and a screen must never wait for it.
    fun refreshPrivacy() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { _privacy.value = repository.privacySettings() }
    }
    fun setPrivacy(name: String, value: String) {
        // Optimistic: the page answers at once; the core then tells the others.
        _privacy.value = _privacy.value + (name to value)
        viewModelScope.launch { if (!repository.setPrivacy(name, value)) refreshPrivacy() }
    }
    fun unblockUser(npub: String) { repository.unblockUser(npub); refreshBlocked() }

    // --- Persisted preferences flow ---
    private val prefsFlow = dataStore.data.map { prefs ->
        PrefsSnapshot(
            torEnabled = prefs[Prefs.TOR_ENABLED] ?: false,
            typingIndicators = prefs[Prefs.TYPING_INDICATORS] ?: true,
            displayName = prefs[Prefs.DISPLAY_NAME] ?: "",
            statusMessage = prefs[Prefs.STATUS_MESSAGE] ?: "",
            notifPreview = prefs[Prefs.NOTIF_PREVIEW] ?: "full"
        )
    }

    private data class PrefsSnapshot(
        val torEnabled: Boolean,
        val typingIndicators: Boolean,
        val displayName: String,
        val statusMessage: String,
        val notifPreview: String
    )

    // --- Combined UI state ---
    val uiState: StateFlow<SettingsUiState> = combine(
        _identityState,
        _relaysState,
        _newRelayUrl,
        _isLoading,
        _error,
        prefsFlow,
        _pinging,
        _lastPingedAt
    ) { args ->
        val identity = args[0] as? IdentityInfo
        @Suppress("UNCHECKED_CAST")
        val relays = args[1] as? List<RelayInfo> ?: emptyList()
        val newRelayUrl = args[2] as? String ?: ""
        val loading = args[3] as? Boolean ?: false
        val error = args[4] as? String?
        val pinging = args[6] as? Boolean ?: false
        val lastPingedAt = args[7] as? Long ?: 0L
        val prefs = args[5] as? PrefsSnapshot ?: PrefsSnapshot(
            torEnabled = false, typingIndicators = true,
            displayName = "", statusMessage = "", notifPreview = "full"
        )

        SettingsUiState(
            identityInfo = identity,
            relays = relays,
            newRelayUrl = newRelayUrl,
            isLoading = loading,
            error = error,
            pinging = pinging,
            lastPingedAt = lastPingedAt,
            displayName = prefs.displayName,
            statusMessage = prefs.statusMessage,
            torEnabled = prefs.torEnabled,
            typingIndicatorsEnabled = prefs.typingIndicators,
            notifPreview = prefs.notifPreview,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState()
    )

    init {
        loadIdentity()
        viewModelScope.launch { _readReceipts.value = repository.readReceiptsEnabled() }
        viewModelScope.launch { _ghost.value = repository.ghostMode() }
        viewModelScope.launch { _noStoreDefault.value = repository.noStoreDefault() }
        viewModelScope.launch {
            val tor = repository.isTorEnabled()
            networkPrivacy.torEnabled = tor
            dataStore.edit { it[Prefs.TOR_ENABLED] = tor }
        }
        // Lazy: only load relays from FFI if cache is empty. Otherwise
        // the singleton cache in Repository is already populated and we
        // avoid flashing the screen on every navigation.
        if (repository.relayStates.value.isEmpty()) {
            loadRelays()
            viewModelScope.launch(Dispatchers.IO) { pingRelays() }
        }
    }

    // --- Identity ---

    fun loadIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = repository.getIdentityInfo()
                _identityState.value = info
            } catch (_: Exception) {
                // Identity may not exist yet
            }
        }
    }

    // --- Profile ---

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            dataStore.edit { it[Prefs.DISPLAY_NAME] = name }
            // Mirror into the core's KV so other parts of the app (and the
            // future MLS-broadcast ProfileUpdate flow) can pick it up.
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { repository.setMyProfile(displayName = name) }
            }
        }
    }

    fun updateStatusMessage(status: String) {
        viewModelScope.launch {
            dataStore.edit { it[Prefs.STATUS_MESSAGE] = status }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { repository.setMyProfile(bio = status) }
            }
        }
    }

    // --- Relay management ---

    /** Pull URLs/flags/connected from FFI into the singleton cache. */
    fun loadRelays() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshRelayStatuses()
        }
    }

    /** Measure TCP ping in parallel + refresh connection state. */
    fun pingRelays(timeoutMs: Int = 3000) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.pingAllRelaysCached(timeoutMs)
        }
    }

    fun toggleRelayEnabled(index: Int) {
        val relay = _relaysState.value.getOrNull(index) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setRelayEnabled(relay.url, !relay.isEnabled) }
                .onFailure { e -> _error.value = strings.get(R.string.relay_switch_error, e.message ?: "") }
        }
    }

    fun removeRelay(index: Int) {
        val relay = _relaysState.value.getOrNull(index) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.removeRelay(relay.url) }
                .onFailure { e -> _error.value = strings.get(R.string.relay_delete_error, e.message ?: "") }
        }
    }

    fun updateNewRelayUrl(url: String) { _newRelayUrl.value = url }

    fun addRelay() {
        val url = _newRelayUrl.value.trim()
        if (url.isBlank() || (!url.startsWith("wss://") && !url.startsWith("ws://"))) return
        addRelayUrl(url)
        _newRelayUrl.value = ""
    }

    /** Add an arbitrary relay URL. Used by both the manual input and presets. */
    fun addRelayUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.addRelay(url, read = true, write = true) }
                .onSuccess { repository.pingAllRelaysCached() }
                .onFailure { e -> _error.value = strings.get(R.string.relay_add_error, e.message ?: "") }
        }
    }

    fun addPresetRelay(url: String) {
        if (_relaysState.value.any { it.url.equals(url, ignoreCase = true) }) return
        addRelayUrl(url)
    }

    // --- Network toggles ---

    /** The core owns the setting (it builds the network clients at startup);
     *  DataStore only mirrors it for the UI. */
    fun toggleTor(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setTorEnabled(enabled) }
                .onSuccess {
                    networkPrivacy.torEnabled = enabled
                    if (enabled) com.alphamessenger.data.EmbeddedTor.start(appContext)
                    else com.alphamessenger.data.EmbeddedTor.stop(appContext)
                    dataStore.edit { it[Prefs.TOR_ENABLED] = enabled }
                }
        }
    }

    // --- Privacy toggles ---

    /** Notification preview: "full" (name+text) | "name" | "none". */
    fun setNotifPreview(mode: String) {
        viewModelScope.launch {
            dataStore.edit { it[Prefs.NOTIF_PREVIEW] = mode }
        }
    }

    fun toggleTypingIndicators(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[Prefs.TYPING_INDICATORS] = enabled }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
