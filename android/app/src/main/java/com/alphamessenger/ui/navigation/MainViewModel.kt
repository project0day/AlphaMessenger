package com.alphamessenger.ui.navigation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.alphamessenger.data.AlphaCoreRepository
import javax.inject.Inject

/**
 * Top-level ViewModel deciding the launch destination.
 *
 * The Rust core auto-creates a cryptographic identity the first time it is
 * initialised, so "an identity exists" is NOT enough to know the user has
 * finished registration. We additionally require an explicit
 * `onboarding_completed` flag set when the welcome/profile flow finishes.
 *
 *   identity present AND onboarded  → ChatList
 *   otherwise                       → Onboarding
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: AlphaCoreRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("alpha_onboarding", Context.MODE_PRIVATE)

    private val _hasIdentity = MutableStateFlow(false)
    val hasIdentity: StateFlow<Boolean> = _hasIdentity.asStateFlow()

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    /** True once the identity probe has finished — gate the splash on this. */
    private val _identityChecked = MutableStateFlow(false)
    val identityChecked: StateFlow<Boolean> = _identityChecked.asStateFlow()

    init {
        checkIdentity()
    }

    private fun checkIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = repository.getIdentityInfo()
                _hasIdentity.value = info != null && info.npub.isNotBlank()
            } catch (_: Exception) {
                _hasIdentity.value = false
            } finally {
                _identityChecked.value = true
            }
        }
    }

    /** Persist that the user finished the welcome/registration flow. */
    fun markOnboarded() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        _onboarded.value = true
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarding_completed"
    }
}
