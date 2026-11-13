package com.alphamessenger.ui.onboarding

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.alphamessenger.R
import com.alphamessenger.data.AppLockManager
import com.alphamessenger.ui.lock.PinSetupDialog
import com.alphamessenger.ui.lock.biometricAvailable
import com.alphamessenger.ui.lock.promptBiometric
import com.alphamessenger.ui.theme.AlphaTheme

private enum class LockChoice { Biometric, Pin, None }

/**
 * Last onboarding screen: protect access. Biometrics always come with a PIN
 * behind them (a fingerprint can fail; the PIN is the way back in).
 */
@Composable
fun AppLockSetupScreen(
    @Suppress("UNUSED_PARAMETER") viewModel: OnboardingViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = AlphaTheme.colorScheme
    val lockManager = remember { AppLockManager(ctx) }
    val activity = ctx as? androidx.fragment.app.FragmentActivity
    val canBiometric = remember { activity != null && biometricAvailable(ctx) }
    var choice by remember { mutableStateOf(if (canBiometric) LockChoice.Biometric else LockChoice.Pin) }
    var pinDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var snackMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snackMsg) { snackMsg?.let { snackbar.showSnackbar(it); snackMsg = null } }

    if (pinDialog) {
        PinSetupDialog(
            onConfirm = { pin ->
                if (!lockManager.setPin(pin)) {
                    pinDialog = false
                    snackMsg = ctx.getString(R.string.applock_pin_save_failed)
                    return@PinSetupDialog
                }
                pinDialog = false
                if (choice == LockChoice.Biometric && activity != null) {
                    promptBiometric(
                        activity,
                        title = ctx.getString(R.string.applock_biometric_confirm_title),
                        subtitle = ctx.getString(R.string.applock_biometric_confirm_subtitle),
                        onSuccess = {
                            lockManager.biometricEnabled = true
                            onContinue()
                        },
                        // The PIN is already set: the lock works, only the fingerprint is off.
                        onError = { snackMsg = ctx.getString(R.string.applock_biometric_confirm_failed, it) },
                    )
                } else {
                    onContinue()
                }
            },
            onDismiss = { pinDialog = false },
        )
    }

    Box {
        OnbScreen(
            title = stringResource(R.string.onb_lock_title),
            subtitle = stringResource(R.string.onb_lock_sub),
            onBack = onBack,
            bottom = {
                OnbPrimaryButton(
                    stringResource(if (choice == LockChoice.None) R.string.onb_lock_start else R.string.onb_lock_enable),
                    { if (choice == LockChoice.None) onSkip() else pinDialog = true },
                )
                if (choice != LockChoice.None) {
                    OnbTextButton(stringResource(R.string.onb_lock_later), onSkip, Modifier.align(Alignment.CenterHorizontally))
                }
            },
        ) {
            if (canBiometric) {
                OnbCardRow(
                    icon = AlphaIcons.Outlined.Fingerprint,
                    title = stringResource(R.string.onb_lock_bio),
                    note = stringResource(R.string.onb_lock_bio_sub),
                    selected = choice == LockChoice.Biometric,
                    onClick = { choice = LockChoice.Biometric },
                    trailing = { OnbRadio(choice == LockChoice.Biometric) },
                )
            }
            OnbCardRow(
                icon = AlphaIcons.Outlined.Dialpad,
                title = stringResource(R.string.onb_lock_pin),
                note = stringResource(R.string.onb_lock_pin_sub),
                iconTint = if (choice == LockChoice.Pin) cs.accent else cs.textSecondary,
                selected = choice == LockChoice.Pin,
                onClick = { choice = LockChoice.Pin },
                trailing = { OnbRadio(choice == LockChoice.Pin) },
            )
            OnbCardRow(
                icon = AlphaIcons.Outlined.LockOpen,
                title = stringResource(R.string.onb_lock_none),
                note = stringResource(R.string.onb_lock_none_sub),
                iconTint = cs.textSecondary,
                selected = choice == LockChoice.None,
                onClick = { choice = LockChoice.None },
                trailing = { OnbRadio(choice == LockChoice.None) },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
