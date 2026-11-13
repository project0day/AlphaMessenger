package com.alphamessenger.ui.onboarding

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Step 3 of 5: the Recovery Password. It encrypts the backup (and keys the
 * local database); it cannot be reset. "Make one up for me" writes a strong
 * passphrase from dictionary words and shows it, so it can be written down.
 */
@Composable
fun RecoveryPasswordScreen(
    viewModel: RecoveryPasswordViewModel,
    wordlist: List<String>,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val cs = AlphaTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    var revealed by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }
    LaunchedEffect(uiState.completed) {
        if (uiState.completed) {
            viewModel.consumeCompleted()
            onContinue()
        }
    }

    Box {
        OnbScreen(
            title = stringResource(R.string.onb_pw_title),
            subtitle = stringResource(R.string.onb_pw_sub),
            step = 3,
            onBack = onBack,
            bottom = {
                OnbPrimaryButton(
                    stringResource(R.string.common_continue),
                    { viewModel.submit() },
                    enabled = uiState.canSubmit,
                    loading = uiState.isLoading,
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OnbField(
                    value = uiState.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = stringResource(R.string.onb_pw_label),
                    password = true,
                    revealed = revealed,
                    trailing = {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).clickable { revealed = !revealed },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (revealed) AlphaIcons.Outlined.VisibilityOff else AlphaIcons.Outlined.Visibility,
                                stringResource(if (revealed) R.string.common_hide else R.string.common_show),
                                tint = cs.textMuted, modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
                StrengthMeter(uiState.strength)
            }
            OnbField(
                value = uiState.confirmation,
                onValueChange = viewModel::onConfirmationChange,
                label = stringResource(R.string.onb_pw_repeat),
                password = true,
                revealed = revealed,
                ok = uiState.confirmed,
                error = if (uiState.confirmation.isNotEmpty() && !uiState.confirmed &&
                    uiState.confirmation.length >= uiState.password.length
                ) stringResource(R.string.onb_pw_mismatch) else null,
            )
            OnbTextButton(
                stringResource(R.string.onb_pw_generate),
                onClick = {
                    val generated = generatePassphrase(wordlist)
                    viewModel.onPasswordChange(generated)
                    viewModel.onConfirmationChange(generated)
                    revealed = true
                },
                accent = true,
                leading = AlphaIcons.Outlined.AutoAwesome,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun StrengthMeter(strength: PasswordStrength) {
    val cs = AlphaTheme.colorScheme
    if (strength == PasswordStrength.Blank) return
    val filled = when (strength) {
        PasswordStrength.VeryWeak, PasswordStrength.Weak -> 1
        PasswordStrength.Fair -> 2
        PasswordStrength.Good -> 3
        PasswordStrength.Strong -> 4
        PasswordStrength.Blank -> 0
    }
    val color = when (filled) {
        1 -> cs.danger
        2 -> cs.warning
        else -> cs.success
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < filled) color else cs.alphaBorder),
                )
            }
        }
        Text(stringResource(strength.labelRes), color = color, fontSize = 13.sp)
    }
}

/** Four dictionary words + two digits: strong, and easy to write down. */
private fun generatePassphrase(wordlist: List<String>): String {
    val rnd = java.security.SecureRandom()
    if (wordlist.isEmpty()) return (1..20).map { "abcdefghjkmnpqrstuvwxyz23456789"[rnd.nextInt(31)] }.joinToString("")
    val words = (1..4).map { wordlist[rnd.nextInt(wordlist.size)] }
    return words.joinToString("-") + "-" + (10 + rnd.nextInt(90))
}
