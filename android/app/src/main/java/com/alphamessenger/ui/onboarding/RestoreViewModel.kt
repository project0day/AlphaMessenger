package com.alphamessenger.ui.onboarding

import com.alphamessenger.util.shortReason
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
 * RestoreViewModel — orchestrates the full account-restore wizard
 * (RoadmapE2EE Phase 4) on a new/clean device.
 *
 * Flow (each step runs off-UI):
 *  0. **MethodSelect** — user chooses nsec/seed or local file.
 *  1. **Nsec** — user pastes `nsec1…`. We validate, call `import_identity`
 *     so the device now owns that npub, then derive the npub for lookup.
 *  2. **Lookup** — `lookup_backup_url(npub)` finds the cloud backup via the
 *     user's Nostr kind 30078 event. If none, fall back to local file import.
 *  3. **Download** — `download_backup(url)` fetches the AEAD container bytes.
 *  4. **Password** — user enters the Recovery Password.
 *  5. **Restore** — `import_backup(bytes, password)` decrypts (AEAD tag check
 *     catches a wrong password before any SQL runs) and replays the dump
 *     atomically. On success the flow signals completion.
 *
 * State is a [Step] enum so the UI renders exactly one screen per step
 * and can show a progress spinner for the async transitions.
 */
@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val strings: com.alphamessenger.util.Strings,
    private val repository: AlphaCoreRepository,
) : ViewModel() {

    /** Wizard steps, in order. [Step.Done] is terminal. */
    enum class Step {
        METHOD_SELECT, ENTER_NSEC, LOOKUP, DOWNLOAD, ENTER_PASSWORD, RESTORE, DONE
    }

    /** How the account key is entered: the 12-word phrase or a raw nsec. */
    enum class InputMode { WORDS, KEY }

    data class UiState(
        val step: Step = Step.ENTER_NSEC,
        val mode: InputMode = InputMode.WORDS,
        /** The 12 phrase cells, in order. */
        val words: List<String> = List(12) { "" },
        /** Index of a cell whose word is not in the BIP-39 list (shown red). */
        val badWordIndex: Int? = null,
        val nsecInput: String = "",
        val passwordInput: String = "",
        /** npub derived from the imported nsec (shown for confirmation). */
        val npub: String = "",
        /** Backup blob URL found via Nostr lookup (empty until step 2 succeeds). */
        val backupUrl: String = "",
        /** Downloaded container bytes (held until the password step restores them). */
        val containerBytes: ByteArray? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        /** Whether the lookup found a cloud backup (false ⇒ offer local import). */
        val hasCloudBackup: Boolean = false,
    ) {
        val canSubmitNsec: Boolean get() = !isLoading && when (mode) {
            InputMode.WORDS -> words.all { it.isNotBlank() }
            InputMode.KEY -> nsecInput.isNotBlank()
        }
        val canSubmitPassword: Boolean get() = passwordInput.isNotBlank() && !isLoading
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onNsecChange(value: String) {
        _uiState.value = _uiState.value.copy(nsecInput = value.trim(), error = null)
    }

    fun setMode(mode: InputMode) {
        _uiState.value = _uiState.value.copy(mode = mode, error = null, badWordIndex = null)
    }

    /** The English BIP-39 list, for suggestions and for spotting a typo early. */
    val wordlist: List<String> get() = repository.mnemonicWordlist

    /**
     * One cell changed. Pasting the whole phrase into any cell spreads it over
     * the cells from that one on.
     */
    fun onWordChange(index: Int, value: String) {
        if (value.trim().startsWith("nsec1")) { pastePhrase(value); return }
        val parts = value.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }.map { it.lowercase() }
        val cells = _uiState.value.words.toMutableList()
        if (parts.size > 1) {
            parts.forEachIndexed { k, w -> if (index + k < cells.size) cells[index + k] = w }
        } else {
            cells[index] = value.trim().lowercase()
        }
        _uiState.value = _uiState.value.copy(words = cells, error = null, badWordIndex = null)
    }

    /** The "Paste" button: the clipboard text, whatever the separators. */
    fun pastePhrase(text: String) {
        // A pasted private key goes to the key field as is (it has digits).
        val trimmed = text.trim()
        if (trimmed.startsWith("nsec1")) {
            _uiState.value = _uiState.value.copy(mode = InputMode.KEY, nsecInput = trimmed, error = null)
            return
        }
        val parts = text.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }.map { it.lowercase() }
        if (parts.isEmpty()) return
        val cells = List(12) { i -> parts.getOrElse(i) { "" } }
        _uiState.value = _uiState.value.copy(words = cells, error = null, badWordIndex = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(passwordInput = value, error = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ══════════════════════════════════════════════════════════════
    // Step transitions
    // ══════════════════════════════════════════════════════════════

    /**
     * Step 0 → 1: user chose the seed/nsec path. Move to the nsec entry step.
     */
    fun selectSeedMethod() {
        _uiState.value = _uiState.value.copy(step = Step.ENTER_NSEC, error = null)
    }

    /**
     * Step 1 → 2: import the nsec (so this device owns the npub), then move
     * to the lookup step. If the nsec is invalid the FFI throws and we stay
     * on step 1 with an error.
     */
    fun submitNsec() {
        val state = _uiState.value
        if (!state.canSubmitNsec) return
        // The phrase: every word must be in the list, then the checksum must hold.
        var phrase: String? = null
        val secret: String = if (state.mode == InputMode.WORDS) {
            val list = repository.mnemonicWordlist.toHashSet()
            val bad = state.words.indexOfFirst { it !in list }
            if (bad >= 0) {
                _uiState.value = state.copy(
                    badWordIndex = bad,
                    error = strings.get(R.string.onb_restore_bad_word, state.words[bad]),
                )
                return
            }
            phrase = state.words.joinToString(" ")
            try {
                repository.mnemonicToNsec(phrase)
            } catch (e: Exception) {
                _uiState.value = state.copy(error = strings.get(R.string.onb_restore_bad_phrase))
                return
            }
        } else {
            if (!isValidNsecFormat(state.nsecInput)) {
                _uiState.value = state.copy(error = strings.get(R.string.restore_error_bad_key))
                return
            }
            state.nsecInput
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = state.copy(isLoading = true, error = null)
            try {
                repository.importIdentity(secret)
                phrase?.let { runCatching { repository.storeMnemonic(it) } }
                val info = repository.getIdentityInfo()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    npub = info.npub,
                    step = Step.LOOKUP,
                )
                // Chain into the lookup automatically.
                lookupBackup()
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.restore_error_bad_identity, e.shortReason()),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.error_with_reason, e.shortReason()),
                )
            }
        }
    }

    /**
     * Step 2: find the cloud backup URL via the user's Nostr kind 30078 event.
     * On success → download. On "no backup found" → stay on lookup and let the
     * UI offer local file import (Phase 2 export). Other errors surface to UI.
     */
    private fun lookupBackup() {
        val npub = _uiState.value.npub.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, step = Step.LOOKUP)
            try {
                val url = repository.lookupBackupUrl(npub)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    backupUrl = url,
                    hasCloudBackup = true,
                )
                downloadBackup(url)
            } catch (e: MessengerException) {
                // No link event = user never enabled cloud backup. Not fatal —
                // the UI offers local file import as a fallback.
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasCloudBackup = false,
                    error = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.restore_error_search, e.shortReason()),
                )
            }
        }
    }

    /** Retry the cloud lookup (e.g. after the user fixed their network). */
    fun retryLookup() = lookupBackup()

    /** The backup was kept on a relay a clean install does not know: add it, then look again. */
    fun searchOnRelay(input: String) {
        val url = input.trim().let { if (it.contains("://")) it else "wss://$it" }
        if (!url.startsWith("wss://") || url.length < 10) {
            _uiState.value = _uiState.value.copy(error = strings.get(R.string.restore_own_relay_bad))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.addRelay(url) }
            lookupBackup()
        }
    }

    /**
     * Step 3: download the AEAD container from Blossom. On success → ask for
     * the Recovery Password.
     */
    private fun downloadBackup(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, step = Step.DOWNLOAD, error = null)
            try {
                val bytes = repository.downloadBackup(url)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    containerBytes = bytes,
                    step = Step.ENTER_PASSWORD,
                )
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.restore_error_download, e.shortReason()),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.error_with_reason, e.shortReason()),
                )
            }
        }
    }

    /**
     * Step 5: decrypt + replay the container into the live DB. Wrong password
     * is caught by the AEAD tag BEFORE any SQL runs; on failure we stay on the
     * password step with an error and let the user retry.
     */
    fun submitPassword() {
        val state = _uiState.value
        val bytes = state.containerBytes
        if (!state.canSubmitPassword || bytes == null) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = state.copy(isLoading = true, step = Step.RESTORE, error = null)
            try {
                repository.importBackup(bytes, state.passwordInput)
                repository.restoreAppSettings()
                runCatching { repository.claimThisDevice() }
                // Pull fresh group state (roles + metadata/avatars + member
                // profiles) — an old backup may be stale; owners/members reply
                // over MLS so the restored device catches up.
                runCatching { repository.pullFreshGroupState() }
                // The restored DB carries the Recovery-Password KDF: mirror it and
                // re-key the database now so it is not left unencrypted.
                runCatching {
                    repository.mirrorRecoveryKdf()
                    if (repository.verifyRecoveryPassword(state.passwordInput) &&
                        repository.dbRekeyState() == "legacy"
                    ) {
                        val master = repository.deriveMasterKey(state.passwordInput)
                        try {
                            repository.migrateDbToRecoveryKey(master.get())
                        } finally {
                            master.close()
                        }
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = Step.DONE,
                )
            } catch (e: MessengerException) {
                val msg = if (e.message?.contains("decrypt", ignoreCase = true) == true) {
                    strings.get(R.string.restore_error_wrong_password)
                } else {
                    strings.get(R.string.restore_error_restore, e.shortReason())
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = Step.ENTER_PASSWORD,
                    error = msg,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = Step.ENTER_PASSWORD,
                    error = strings.get(R.string.error_with_reason, e.shortReason()),
                )
            }
        }
    }

    /**
     * Fallback path when no cloud backup exists: the user picks a local
     * `*.enc` file (Phase 2 export) and we go straight to the password step.
     * Works regardless of current step.
     */
    fun importLocalContainer(bytes: ByteArray) {
        _uiState.value = _uiState.value.copy(
            containerBytes = bytes,
            hasCloudBackup = false,
            step = Step.ENTER_PASSWORD,
            error = null,
        )
    }
}

/**
 * Cheap pre-check before calling the FFI: a valid nsec starts with `nsec1`,
 * or is a 64-char hex secret. The authoritative parse happens in Rust.
 */
private fun isValidNsecFormat(input: String): Boolean {
    if (input.isBlank()) return false
    if (input.startsWith("nsec1")) return input.length > 10
    return input.length == 64 && input.all { it.isHexChar() }
}

private fun Char.isHexChar(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
