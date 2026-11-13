package com.alphamessenger.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.alphamessenger.data.AlphaCoreRepository
import uniffi.alphamessenger.MessengerException
import javax.inject.Inject
import com.alphamessenger.R

/**
 * UI state for the entire onboarding flow.
 *
 * Shared across OnboardingScreen, SeedBackupScreen, ProfileSetupScreen,
 * AppLockSetupScreen, and RestoreScreen so that data entered in one screen
 * (e.g., seed phrase, display name) persists when navigating back.
 */
data class OnboardingUiState(
    // ── Loading / error ────────────────────────────────────────
    val isLoading: Boolean = false,
    val error: String? = null,

    // ── Seed phrase ────────────────────────────────────────────
    // The Nostr identity secret key in bech32 `nsec1…` form. This is the
    // IDENTITY / login key (RoadmapE2EE §1) — it proves ownership of the
    // account and lets the user recover their npub on a new device. It is
    // NOT the data-encryption key (that's the Recovery Password, Phase 1+).
    // Null until the identity is created and the secret is fetched from FFI.
    val nsec: String? = null,

    // ── Secret phrase ──────────────────────────────────────────
    // The 12 words the account key derives from (NIP-06). Null only if the
    // phrase could not be made — the account then falls back to its nsec.
    val mnemonic: List<String>? = null,

    // ── Identity creation ──────────────────────────────────────
    // True after the FFI core has successfully generated an identity
    // (MLS keys + Nostr keypair). Used by SeedBackupScreen to show
    // an appropriate message when no mnemonic is available.
    val identityCreated: Boolean = false,

    // ── Profile ────────────────────────────────────────────────
    val displayName: String = "",
    val avatarUri: Uri? = null,

    // ── Navigation events (one-shot) ───────────────────────────
    val navigateToSeedBackup: Boolean = false,
    val navigateToRestore: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val strings: com.alphamessenger.util.Strings,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: AlphaCoreRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    // ══════════════════════════════════════════════════════════
    // Identity creation
    // ══════════════════════════════════════════════════════════

    /**
     * Generates a new cryptographic identity via the FFI core.
     *
     * The Rust core auto-creates a dual identity (Nostr + MLS keys) on first
     * `initialize()`. We then fetch the Nostr secret key (`nsec1…`) so the
     * user can back it up. On success, [OnboardingUiState.identityCreated]
     * is set to true and [OnboardingUiState.navigateToSeedBackup] is set to true.
     */
    fun createIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                repository.initialize()
                // The account IS its 12 words: derive the key from a fresh
                // phrase (NIP-06) and adopt it in place of the random key the
                // core made on first start. Any failure keeps the random key
                // and its nsec — the account still works.
                val words = runCatching {
                    val phrase = repository.mnemonicGenerate()
                    repository.importIdentity(repository.mnemonicToNsec(phrase))
                    repository.storeMnemonic(phrase)
                    phrase.split(' ')
                }.getOrNull()
                val nsec = repository.exportNsec()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    identityCreated = true,
                    nsec = nsec,
                    mnemonic = words,
                    navigateToSeedBackup = true
                )
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.onboarding_error_create, e.message ?: "")
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.error_with_reason, e.message ?: "")
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // Navigation triggers
    // ══════════════════════════════════════════════════════════

    /** Private mode before the account exists: nothing leaves the phone directly. */
    suspend fun setTorEnabled(enabled: Boolean) = repository.setTorEnabled(enabled)

    /** Request navigation to the Restore screen. */
    fun requestRestore() {
        _uiState.value = _uiState.value.copy(navigateToRestore = true)
    }

    /** Consume one-shot navigation events after they've been handled. */
    fun onNavigationConsumed() {
        _uiState.value = _uiState.value.copy(
            navigateToSeedBackup = false,
            navigateToRestore = false
        )
    }

    // ══════════════════════════════════════════════════════════
    // State updates
    // ══════════════════════════════════════════════════════════

    fun updateDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name)
    }

    fun updateAvatarUri(uri: Uri) {
        _uiState.value = _uiState.value.copy(avatarUri = uri)
    }

    fun saveProfileAndNavigate(onDone: () -> Unit) {
        viewModelScope.launch {
            val uri = _uiState.value.avatarUri
            val avatarPath = if (uri != null) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { com.alphamessenger.ui.settings.saveAvatarToInternal(appContext, uri) }.getOrNull()
                }
            } else null
            runCatching {
                if (avatarPath != null) {
                    repository.setMyProfile(displayName = _uiState.value.displayName, avatarPath = avatarPath)
                } else {
                    repository.setMyProfile(displayName = _uiState.value.displayName)
                }
            }
            onDone()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

}
