package com.alphamessenger.ui.onboarding

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
 * RecoveryPasswordViewModel — handles the "Create Recovery Password" step of
 * onboarding (RoadmapE2EE Phase 1).
 *
 * The Recovery Password is the **data** secret — it derives the MasterDataKey
 * (Argon2id) which in turn derives every sub-key (DatabaseKey, BackupFileKey…).
 * Losing it means an irreversible loss of history, so the UI enforces a
 * minimum-strength bar and a confirmation field before [submit] is enabled.
 *
 * All Argon2id work happens off the UI thread inside the repository
 * ([Dispatchers.IO]); this VM only owns the input state + one-shot events.
 */
@HiltViewModel
class RecoveryPasswordViewModel @Inject constructor(
    private val strings: com.alphamessenger.util.Strings,
    private val repository: AlphaCoreRepository
) : ViewModel() {

    data class UiState(
        val password: String = "",
        val confirmation: String = "",
        /** Real-time strength estimate derived from [passwordStrengthOf]. */
        val strength: PasswordStrength = PasswordStrength.Blank,
        /** True once the user has confirmed the password matches. */
        val confirmed: Boolean = false,
        val isLoading: Boolean = false,
        val error: String? = null,
        /** Fired once [set_recovery_password] succeeded in Rust. */
        val completed: Boolean = false,
    ) {
        /** The submit button is enabled only when strength ≥ Weak+1 and the
         *  two fields match, with no operation in flight. */
        val canSubmit: Boolean
            get() = !isLoading &&
                strength.ordinal > PasswordStrength.Weak.ordinal &&
                confirmed
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onPasswordChange(value: String) {
        val strength = passwordStrengthOf(value)
        val confirmed = value.isNotBlank() && value == _uiState.value.confirmation
        _uiState.value = _uiState.value.copy(
            password = value,
            strength = strength,
            confirmed = confirmed,
            error = null,
        )
    }

    fun onConfirmationChange(value: String) {
        val confirmed = value.isNotBlank() && value == _uiState.value.password
        _uiState.value = _uiState.value.copy(
            confirmation = value,
            confirmed = confirmed,
            error = null,
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun consumeCompleted() {
        _uiState.value = _uiState.value.copy(completed = false)
    }

    /**
     * Persist the Recovery Password via Argon2id in Rust.
     *
     * Pre-checks (length, confirmation) are defensive — the UI also gates the
     * button on [UiState.canSubmit] — so a stray call here is harmless.
     */
    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        // Final sanity: the two fields must match exactly.
        if (state.password != state.confirmation) {
            _uiState.value = state.copy(error = strings.get(R.string.recovery_mismatch))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                repository.setRecoveryPassword(state.password)
                // Re-key the database right away instead of waiting for the next cold start.
                runCatching {
                    if (repository.dbRekeyState() == "legacy") {
                        val master = repository.deriveMasterKey(state.password)
                        try {
                            repository.migrateDbToRecoveryKey(master.get())
                        } finally {
                            master.close()
                        }
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    completed = true,
                )
            } catch (e: MessengerException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.recovery_error_save, e.message ?: ""),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = strings.get(R.string.error_with_reason, e.message ?: ""),
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// Password strength estimation
// ════════════════════════════════════════════════════════════════════

/**
 * Coarse password-strength buckets. Ordered weakest → strongest so [ordinal]
 * comparisons work (see [RecoveryPasswordViewModel.UiState.canSubmit]).
 *
 * The estimator is deliberately dependency-free (no zxcvbn lib): it rewards
 * length, character-class diversity and penalises pure dictionary words and
 * sequences. It's a UX guardrail, NOT a security control — the actual
 * brute-force resistance comes from Argon2id's memory hardness in Rust.
 */
enum class PasswordStrength(@androidx.annotation.StringRes val labelRes: Int) {
    Blank(0),
    VeryWeak(R.string.password_strength_very_weak),
    Weak(R.string.password_strength_weak),
    Fair(R.string.password_strength_fair),
    Good(R.string.password_strength_good),
    Strong(R.string.password_strength_strong),
}

/**
 * Estimate [password]'s strength. Pure function, no allocation beyond a regex
 * check — safe to call on every keystroke.
 *
 * Heuristics (tuned so a typical 8-char mixed password lands at Fair/Good):
 *  - length contribution (the dominant factor)
 *  - + diversity across lower / upper / digit / symbol classes
 *  - - penalties for pure digits, common keyboard sequences, repeated chars
 */
fun passwordStrengthOf(password: String): PasswordStrength {
    if (password.isEmpty()) return PasswordStrength.Blank

    var score = 0

    // Length — diminishing returns past ~16 chars.
    score += when {
        password.length >= 20 -> 5
        password.length >= 14 -> 4
        password.length >= 10 -> 3
        password.length >= 8 -> 2
        password.length >= 6 -> 1
        else -> 0
    }

    // Character-class diversity (1 point each, up to 4).
    var classes = 0
    if (password.any { it.isLowerCase() }) classes++
    if (password.any { it.isUpperCase() }) classes++
    if (password.any { it.isDigit() }) classes++
    if (password.any { !it.isLetterOrDigit() }) classes++
    score += classes

    // Penalties.
    if (password.all { it.isDigit() }) score -= 2          // pin-codes
    if (isKeyboardSequence(password)) score -= 2           // "qwerty", "1234"
    if (hasLongRun(password)) score -= 1                   // "aaaaaaa", "11111"

    return when {
        score <= 1 -> PasswordStrength.VeryWeak
        score <= 3 -> PasswordStrength.Weak
        score <= 5 -> PasswordStrength.Fair
        score <= 7 -> PasswordStrength.Good
        else -> PasswordStrength.Strong
    }
}

/** Detects common keyboard / numeric sequences (lowercased comparison). */
private fun isKeyboardSequence(s: String): Boolean {
    val lower = s.lowercase()
    val sequences = listOf(
        "qwerty", "qwertz", "azerty", "asdf", "zxcv",
        "1234", "2345", "3456", "4567", "5678", "6789",
        "abcd", "password", "qwertyuiop",
    )
    return sequences.any { lower.contains(it) }
}

/** True if the string has a run of the same char of length ≥ 4. */
private fun hasLongRun(s: String): Boolean {
    var run = 1
    for (i in 1 until s.length) {
        run = if (s[i] == s[i - 1]) run + 1 else 1
        if (run >= 4) return true
    }
    return false
}
