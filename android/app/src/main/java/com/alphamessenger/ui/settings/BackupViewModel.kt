package com.alphamessenger.ui.settings

import com.alphamessenger.util.shortReason
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import uniffi.alphamessenger.MessengerException
import javax.inject.Inject

/**
 * BackupViewModel — drives the export/restore backup UI (Phase 2).
 *
 * The export path is password-gated: the user must retype their Recovery
 * Password, which we (a) verify against the stored verifier and (b) use as
 * the key for the AEAD container. The export itself is Argon2id + full DB
 * dump, so it runs off-UI and the screen shows a progress spinner.
 *
 * One-shot UI events (success snackbar, exported-bytes-for-SAF) are exposed
 * via [events] so the screen can react without holding a long-lived state.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val repository: AlphaCoreRepository,
    private val strings: com.alphamessenger.util.Strings,
) : ViewModel() {

    data class UiState(
        val password: String = "",
        val isExporting: Boolean = false,
        val isImporting: Boolean = false,
        val isUploadingCloud: Boolean = false,
        val isDeletingCloud: Boolean = false,
        val error: String? = null,
        /** Last successful cloud backup info (parsed from getBackupInfo JSON). */
        val cloud: CloudBackupInfo = CloudBackupInfo(),
        /** Shown after the password was checked: the phrase, or the nsec of an older account. */
        val revealedWords: List<String>? = null,
        val revealedNsec: String? = null,
        val isRevealing: Boolean = false,
    ) {
        val canExport: Boolean get() = password.isNotBlank() && !isExporting
        val canUploadCloud: Boolean get() = password.isNotBlank() && !isUploadingCloud
    }

    /** Parsed view of the `get_backup_info` JSON (null-safe defaults). */
    data class CloudBackupInfo(
        val has: Boolean = false,
        val url: String = "",
        val sha: String = "",
        val at: String = "",
        val size: Long = 0L,
    )

    /** One-shot signals to the screen. */
    sealed class Event {
        /** Export succeeded; write these bytes to the chosen file. */
        data class Exported(val bytes: ByteArray) : Event()
        /** Import succeeded; refresh the UI. */
        data object Imported : Event()
        /** A backup file was inspected; [summary] is human-readable. */
        data class Inspected(val summary: String) : Event()
        /** Cloud upload succeeded; refresh the status card. */
        data object CloudUploaded : Event()
        /** Cloud backup deleted; refresh the status card. */
        data object CloudDeleted : Event()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** True iff a Recovery Password has been set (i.e. backup is possible). */
    fun canBackup(): Boolean = repository.hasRecoveryPassword()

    /**
     * Verify the password and produce an encrypted backup container. The
     * resulting bytes are emitted via [events] (Event.Exported) so the screen
     * can hand them to SAF.
     */
    fun exportBackup() {
        val state = _uiState.value
        if (!state.canExport) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = state.copy(isExporting = true, error = null)
            try {
                // 1. Verify the password before doing the heavy export — avoids
                //    producing a container the user can't later decrypt with a
                //    typo'd password that happened to match their memory.
                if (!repository.verifyRecoveryPassword(state.password)) {
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        error = strings.get(R.string.restore_error_wrong_password),
                    )
                    return@launch
                }
                // 2. Produce the encrypted container.
                val bytes = repository.exportBackup(state.password)
                _uiState.value = _uiState.value.copy(isExporting = false)
                _events.tryEmit(Event.Exported(bytes))
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    error = strings.get(R.string.backup_export_error, e.shortReason()),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    error = strings.get(R.string.error_with_reason, e.shortReason()),
                )
            }
        }
    }

    /**
     * Restore a backup file into the live database. The container is decrypted
     * with [password]; on success the SQL is replayed atomically.
     *
     * Intended for an empty DB (Phase 4 restore flow). On the current device
     * the user should understand this OVERWRITES local data.
     */
    fun importBackup(container: ByteArray, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isImporting = true, error = null)
            try {
                repository.importBackup(container, password)
                repository.restoreAppSettings()
                runCatching { repository.claimThisDevice() }
                runCatching { repository.pullFreshGroupState() }
                _uiState.value = _uiState.value.copy(isImporting = false)
                _events.tryEmit(Event.Imported)
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isImporting = false,
                    error = if (e.toString().contains("another account")) strings.get(R.string.backup_restore_other_account)
                        else strings.get(R.string.backup_restore_error, e.shortReason()),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isImporting = false,
                    error = strings.get(R.string.error_with_reason, e.shortReason()),
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Cloud backup via Blossom (Phase 3)
    // ══════════════════════════════════════════════════════════════

    /** Refresh the cloud-backup status card from KV. Safe to call anytime. */
    fun refreshCloudInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(cloud = parseCloudInfo(repository.getBackupInfo()))
        }
    }

    /**
     * Upload an encrypted backup to Blossom. Verifies the password first
     * (so a typo doesn't lock the user out of their cloud backup), uploads
     * the container, and optionally publishes a Nostr link event so other
     * devices can discover it by npub.
     */
    fun uploadCloudBackup(publishNostrLink: Boolean = true) {
        val state = _uiState.value
        if (!state.canUploadCloud) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = state.copy(isUploadingCloud = true, error = null)
            try {
                if (!repository.verifyRecoveryPassword(state.password)) {
                    _uiState.value = _uiState.value.copy(
                        isUploadingCloud = false,
                        error = strings.get(R.string.restore_error_wrong_password),
                    )
                    return@launch
                }
                val url = repository.uploadBackup(state.password, publishNostrLink)
                _uiState.value = _uiState.value.copy(
                    isUploadingCloud = false,
                    cloud = parseCloudInfo(repository.getBackupInfo()),
                )
                _events.tryEmit(Event.CloudUploaded)
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isUploadingCloud = false,
                    error = strings.get(R.string.backup_cloud_error, e.shortReason()),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingCloud = false,
                    error = strings.get(R.string.error_with_reason, e.shortReason()),
                )
            }
        }
    }

    /** Delete the cloud backup (Blossom blob + local reference) and refresh. */
    fun deleteCloudBackup() {
        if (_uiState.value.isDeletingCloud) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isDeletingCloud = true, error = null)
            try {
                repository.deleteCloudBackup()
                _uiState.value = _uiState.value.copy(
                    isDeletingCloud = false,
                    cloud = parseCloudInfo(repository.getBackupInfo()),
                )
                _events.tryEmit(Event.CloudDeleted)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDeletingCloud = false,
                    error = strings.get(R.string.backup_delete_error, e.shortReason()),
                )
            }
        }
    }

    /** Show the account's secret phrase (or nsec) after checking the backup password. */
    fun revealKeys() {
        val password = _uiState.value.password
        if (password.isBlank() || _uiState.value.isRevealing) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isRevealing = true, error = null)
            if (!repository.verifyRecoveryPassword(password)) {
                _uiState.value = _uiState.value.copy(isRevealing = false, error = strings.get(R.string.restore_error_wrong_password))
                return@launch
            }
            val words = repository.storedMnemonic()?.split(' ')
            val nsec = if (words == null) runCatching { repository.exportNsec() }.getOrNull() else null
            _uiState.value = _uiState.value.copy(isRevealing = false, password = "", revealedWords = words, revealedNsec = nsec)
        }
    }

    fun hideKeys() {
        _uiState.value = _uiState.value.copy(revealedWords = null, revealedNsec = null)
    }

    private fun parseCloudInfo(json: String): CloudBackupInfo {
        return runCatching {
            val o = org.json.JSONObject(json)
            CloudBackupInfo(
                has = o.optBoolean("has", false),
                url = o.optString("url"),
                sha = o.optString("sha"),
                at = o.optString("at"),
                size = o.optLong("size", 0L),
            )
        }.getOrDefault(CloudBackupInfo())
    }
}
