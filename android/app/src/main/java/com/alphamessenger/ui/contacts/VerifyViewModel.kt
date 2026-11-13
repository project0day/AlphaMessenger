package com.alphamessenger.ui.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphamessenger.data.AlphaCoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

data class VerifyUiState(
    val chatId: String = "",
    /** 60 digits in 12 groups of 5 — identical on both phones. */
    val safetyNumber: String = "",
    val peerName: String = "",
    val isVerified: Boolean = false,
    val isLoading: Boolean = true,
    /** Marked verified for a number that no longer holds. */
    val keyChanged: Boolean = false,
    /** The peer's key in this chat is signed by their account key. */
    val proven: Boolean = false,
    /** Result of the last QR scan: true = match, false = mismatch, null = none. */
    val scanMatch: Boolean? = null,
    val error: String? = null,
)

/**
 * Safety number: both people derive the same number from their two account
 * keys. Comparing it — in person or by scanning each other's QR — proves
 * nobody sits in the middle. The core computes it and keeps the verified mark
 * in the encrypted database, with the number it was given for.
 */
@HiltViewModel
class VerifyViewModel @Inject constructor(
    private val repository: AlphaCoreRepository,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val chatId: String = savedStateHandle["chatId"] ?: ""

    private val _uiState = MutableStateFlow(VerifyUiState(chatId = chatId))
    val uiState: StateFlow<VerifyUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        if (chatId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                adoptOldMark()
                repository.refreshLeafBinding(chatId)
                val info = JSONObject(repository.verificationInfo(chatId))
                val number = info.optString("number")
                require(number.isNotBlank()) { "no peer key yet" }
                _uiState.value = _uiState.value.copy(
                    safetyNumber = number,
                    isVerified = info.optBoolean("verified"),
                    keyChanged = info.optBoolean("changed"),
                    proven = info.optBoolean("proven"),
                    isLoading = false,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, error = it.message)
            }
        }
    }

    /** Marks made before the core kept them move into the database once. */
    private suspend fun adoptOldMark() {
        val prefs = appContext.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(chatId, null) ?: return
        val current = JSONObject(repository.verificationInfo(chatId)).optString("number")
        if (current.isNotBlank() && old == current) repository.setContactVerified(chatId, true)
        prefs.edit().remove(chatId).apply()
    }

    fun setVerified(verified: Boolean) {
        if (_uiState.value.safetyNumber.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setContactVerified(chatId, verified) }
                .onSuccess { _uiState.value = _uiState.value.copy(isVerified = verified, keyChanged = false) }
        }
    }

    /** A scanned QR equal to our number proves the keys match: mark verified. */
    fun onScanned(code: String) {
        val match = code.filter { it.isDigit() } == _uiState.value.safetyNumber.filter { it.isDigit() }
        _uiState.value = _uiState.value.copy(scanMatch = match)
        if (match) setVerified(true)
    }

    fun clearScan() { _uiState.value = _uiState.value.copy(scanMatch = null) }

    private companion object {
        const val OLD_PREFS = "verified_contacts"
    }
}
