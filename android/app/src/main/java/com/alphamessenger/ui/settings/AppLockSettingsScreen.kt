package com.alphamessenger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.alphamessenger.data.AppLockManager
import com.alphamessenger.ui.components.GlassTopBar
import com.alphamessenger.ui.lock.PinSetupDialog
import com.alphamessenger.ui.lock.biometricAvailable
import com.alphamessenger.ui.lock.promptBiometric
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.R
import androidx.compose.ui.res.stringResource

/**
 * App-lock + Panic PIN settings (Security Milestone).
 *
 * Pure local-gate settings — no change to OpenMLS / Recovery Password / Backup.
 */
@Composable
fun AppLockSettingsScreen(onBack: () -> Unit) {
    val colors = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    val manager = remember { AppLockManager(ctx) }
    val vm: SettingsViewModel = hiltViewModel()

    // Bump to recompose after manager mutations (it's plain SharedPreferences).
    var rev by remember { mutableIntStateOf(0) }
    val biometricOn = remember(rev) { manager.biometricEnabled }
    val pinSet = remember(rev) { manager.isPinSet }
    val panicSet = remember(rev) { manager.isPanicPinSet }
    val delayMs = remember(rev) { manager.autoLockDelayMs }
    val hasRecovery = remember(rev) { vm.hasRecoveryPassword() }
    val backup = remember(rev) { vm.backupStatus() }

    var showPinSetup by remember { mutableStateOf(false) }
    var showPanicSetup by remember { mutableStateOf(false) }
    var panicError by remember { mutableStateOf<String?>(null) }
    var showChangeRecovery by remember { mutableStateOf(false) }
    // After enabling biometric we prompt for the Recovery Password once to cache
    // the master key (so the NEXT reboot opens via biometric, not the password).
    var showBiometricPassword by remember { mutableStateOf(false) }

    if (showPinSetup) {
        PinSetupDialog(
            onConfirm = { pin ->
                if (!manager.setPin(pin)) {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.applock_pin_save_failed), android.widget.Toast.LENGTH_LONG).show()
                }
                showPinSetup = false; rev++
            },
            onDismiss = { showPinSetup = false },
        )
    }
    if (showPanicSetup) {
        PinSetupDialog(
            onConfirm = { pin ->
                // Panic PIN MUST differ from the normal PIN.
                if (manager.isPinSet && manager.verifyPin(pin)) {
                    panicError = ctx.getString(R.string.applock_panic_must_differ)
                } else {
                    panicError = if (manager.setPanicPin(pin)) null else ctx.getString(R.string.applock_pin_save_failed)
                }
                showPanicSetup = false; rev++
            },
            onDismiss = { showPanicSetup = false },
        )
    }
    if (showChangeRecovery) {
        RecoveryPasswordDialog(
            title = stringResource(R.string.applock_change_recovery_title),
            note = stringResource(R.string.applock_rekey_note),
            needsCurrent = true,
            onSubmit = { current, new, cb -> vm.changeRecoveryPassword(current, new, cb) },
            onDone = { Toast.makeText(ctx, ctx.getString(R.string.applock_recovery_changed), Toast.LENGTH_LONG).show(); showChangeRecovery = false; rev++ },
            onDismiss = { showChangeRecovery = false },
        )
    }
    if (showBiometricPassword) {
        BiometricCacheDialog(
            onSubmit = { pw, cb ->
                vm.deriveMasterForBiometric(pw) { master, derr ->
                    if (master == null) {
                        cb(false, derr ?: ctx.getString(R.string.gate_wrong_password))
                        return@deriveMasterForBiometric
                    }
                    val cipher = vm.cacheCipher()
                    if (cipher == null || activity == null) {
                        master.fill(0)
                        cb(false, ctx.getString(R.string.applock_keystore_unavailable))
                        return@deriveMasterForBiometric
                    }
                    // Bind the ENCRYPT op to a biometric auth — the reliable path.
                    promptBiometricEncrypt(
                        activity, cipher,
                        onOk = { authed ->
                            val ok = vm.finishBiometricCache(authed, master)
                            master.fill(0)
                            cb(ok, if (ok) null else ctx.getString(R.string.applock_save_key_failed))
                        },
                        onErr = { msg -> master.fill(0); cb(false, msg) },
                    )
                }
            },
            onSuccess = {
                manager.biometricEnabled = true; rev++
                showBiometricPassword = false
                Toast.makeText(ctx, ctx.getString(R.string.applock_biometric_activated), Toast.LENGTH_LONG).show()
            },
            onCancel = { showBiometricPassword = false },
        )
    }

    Scaffold(
        containerColor = colors.bgPrimary,
        topBar = { GlassTopBar(title = stringResource(R.string.applock_topbar), onBack = onBack) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // --- Security state ---
            SectionLabel(stringResource(R.string.applock_section_state), colors)
            com.alphamessenger.ui.components.OptionGroup {
                StatusRow(stringResource(R.string.applock_biometry), if (biometricOn) stringResource(R.string.common_enabled) else stringResource(R.string.common_disabled), colors)
                com.alphamessenger.ui.components.OptionDivider()
                StatusRow(stringResource(R.string.applock_recovery_label), if (hasRecovery) stringResource(R.string.applock_set) else stringResource(R.string.applock_not_set), colors)
                com.alphamessenger.ui.components.OptionDivider()
                StatusRow(
                    stringResource(R.string.applock_last_backup),
                    if (backup.has && backup.at.isNotBlank()) backup.at else stringResource(R.string.common_none),
                    colors,
                )
            }
            Spacer(Modifier.height(18.dp))

            SectionLabel(stringResource(R.string.settings_applock_title), colors)

            ToggleRow(
                title = stringResource(R.string.applock_biometry),
                subtitle = stringResource(R.string.applock_biometry_subtitle),
                checked = biometricOn,
                colors = colors,
                onCheckedChange = { want ->
                    if (want) {
                        if (activity == null || !biometricAvailable(ctx)) return@ToggleRow
                        if (hasRecovery) {
                            // Enable via the reliable crypto-bound caching flow:
                            // password → ENCRYPT cipher → BiometricPrompt → store.
                            // biometricEnabled is set only on success.
                            showBiometricPassword = true
                        } else {
                            manager.biometricEnabled = true; rev++
                        }
                    } else {
                        manager.biometricEnabled = false
                        vm.clearBiometricCache()
                        rev++
                    }
                },
            )

            Spacer(Modifier.height(10.dp))
            ActionRow(
                title = if (pinSet) stringResource(R.string.applock_pin_set) else stringResource(R.string.applock_pin_title),
                subtitle = if (pinSet) stringResource(R.string.applock_tap_to_change) else stringResource(R.string.applock_pin_digits),
                colors = colors,
                onClick = { showPinSetup = true },
            )
            if (pinSet) {
                Spacer(Modifier.height(6.dp))
                DangerText(stringResource(R.string.applock_delete_pin), colors) { manager.clearPin(); rev++ }
            }

            // --- Recovery password ---
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.applock_recovery_label), colors)
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.bgSecondary)
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.applock_recovery_explainer),
                    color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            ActionRow(
                title = if (hasRecovery) stringResource(R.string.applock_change_recovery_title) else stringResource(R.string.applock_password_not_set),
                subtitle = if (hasRecovery) stringResource(R.string.applock_change_recovery_sub) else stringResource(R.string.applock_password_set_at_signup),
                colors = colors,
                onClick = {
                    if (hasRecovery) showChangeRecovery = true
                    else Toast.makeText(ctx, ctx.getString(R.string.applock_password_not_set_yet), Toast.LENGTH_SHORT).show()
                },
            )
            if (hasRecovery) {
                Spacer(Modifier.height(10.dp))
                val strict = remember(rev) { com.alphamessenger.data.RememberedDbKey.askAfterRestart(ctx) }
                ToggleRow(
                    title = stringResource(R.string.applock_ask_after_restart),
                    subtitle = stringResource(R.string.applock_ask_after_restart_sub),
                    checked = strict,
                    colors = colors,
                ) { com.alphamessenger.data.RememberedDbKey.setAskAfterRestart(ctx, it); rev++ }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.applock_autolock), colors)
            Text(
                stringResource(R.string.applock_autolock_question),
                color = colors.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val options = listOf(
                stringResource(R.string.applock_immediately) to 0L,
                stringResource(R.string.applock_30s) to 30_000L,
                stringResource(R.string.applock_1m) to 60_000L,
                stringResource(R.string.applock_5m) to 300_000L,
                stringResource(R.string.applock_15m) to 900_000L,
                stringResource(R.string.applock_30m) to 1_800_000L,
                stringResource(R.string.applock_1h) to 3_600_000L,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { (label, ms) ->
                    val on = delayMs == ms
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) colors.accent else colors.bgTertiary)
                            .clickable { manager.autoLockDelayMs = ms; rev++ }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(label, color = if (on) Color.White else colors.textSecondary, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            val replyOn = remember(rev) { manager.replyFromNotifications }
            ToggleRow(
                title = stringResource(R.string.applock_notif_reply_title),
                subtitle = stringResource(R.string.applock_notif_reply_sub),
                checked = replyOn,
                colors = colors,
            ) { manager.replyFromNotifications = it; rev++ }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Panic PIN", colors)
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x14FF5A5A))
                    .border(1.dp, Color(0x55FF5A5A), RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Text(
                    stringResource(R.string.applock_panic_pin_explainer),
                    color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            ActionRow(
                title = if (panicSet) stringResource(R.string.applock_panic_set) else stringResource(R.string.applock_set_panic),
                subtitle = if (panicSet) stringResource(R.string.applock_tap_to_change) else stringResource(R.string.applock_panic_rule),
                colors = colors,
                onClick = { panicError = null; showPanicSetup = true },
            )
            panicError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color(0xFFFF5A5A), fontSize = 13.sp)
            }
            if (panicSet) {
                Spacer(Modifier.height(6.dp))
                DangerText(stringResource(R.string.applock_delete_panic), colors) { manager.clearPanicPin(); rev++ }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, @Suppress("UNUSED_PARAMETER") colors: AlphaColorScheme) {
    com.alphamessenger.ui.components.OptionSection(text)
}

@Composable
private fun ToggleRow(
    title: String, subtitle: String, checked: Boolean,
    colors: AlphaColorScheme, onCheckedChange: (Boolean) -> Unit,
) {
    com.alphamessenger.ui.components.OptionGroup {
        com.alphamessenger.ui.components.OptionSwitchRow(title, subtitle, checked, onCheckedChange)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, colors: AlphaColorScheme, onClick: () -> Unit) {
    com.alphamessenger.ui.components.OptionGroup {
        com.alphamessenger.ui.components.OptionRow(title = title, subtitle = subtitle, onClick = onClick)
    }
}

@Composable
private fun DangerText(text: String, colors: AlphaColorScheme, onClick: () -> Unit) {
    Text(
        text, color = Color(0xFFFF5A5A), fontSize = 14.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun StatusRow(label: String, value: String, colors: AlphaColorScheme) {
    com.alphamessenger.ui.components.OptionRow(title = label, value = value)
}

@Composable
private fun RecoveryPasswordDialog(
    title: String,
    note: String,
    needsCurrent: Boolean,
    onSubmit: (current: String, new: String, cb: (Boolean, String?) -> Unit) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var current by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(note, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                if (needsCurrent) {
                    OutlinedTextField(
                        value = current, onValueChange = { current = it; err = null },
                        label = { Text(stringResource(R.string.applock_current_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = newPw, onValueChange = { newPw = it; err = null },
                    label = { Text(stringResource(R.string.applock_new_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it; err = null },
                    label = { Text(stringResource(R.string.applock_repeat_new)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFFF5A5A), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (!needsCurrent || current.isNotBlank()) && newPw.isNotBlank() && newPw == confirm,
                onClick = {
                    busy = true; err = null
                    onSubmit(current, newPw) { ok, msg ->
                        busy = false
                        if (ok) onDone() else err = msg ?: ctx.getString(R.string.applock_change_failed)
                    }
                },
            ) { Text(if (busy) stringResource(R.string.applock_changing) else stringResource(R.string.applock_change)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun BiometricCacheDialog(
    onSubmit: (pw: String, cb: (Boolean, String?) -> Unit) -> Unit,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.applock_activate_biometric)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.applock_biometric_boot_hint),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it; err = null },
                    label = { Text(stringResource(R.string.recovery_password_label)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFFF5A5A), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && pw.isNotBlank(), onClick = {
                busy = true; err = null
                onSubmit(pw) { ok, msg ->
                    busy = false
                    if (ok) onSuccess() else err = msg ?: ctx.getString(R.string.common_error)
                }
            }) { Text(if (busy) stringResource(R.string.applock_confirming) else stringResource(R.string.applock_activate)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** ENCRYPT-bound biometric prompt for caching the master key. */
private fun promptBiometricEncrypt(
    activity: FragmentActivity,
    cipher: javax.crypto.Cipher,
    onOk: (javax.crypto.Cipher) -> Unit,
    onErr: (String) -> Unit,
) {
    val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
    val prompt = androidx.biometric.BiometricPrompt(
        activity, executor,
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c == null) onErr(activity.getString(R.string.biometric_no_cipher)) else onOk(c)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) = onErr(msg.toString())
        },
    )
    val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.applock_activate_biometric))
        .setSubtitle(activity.getString(R.string.applock_biometric_prompt_sub))
        .setNegativeButtonText(activity.getString(R.string.common_cancel))
        .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info, androidx.biometric.BiometricPrompt.CryptoObject(cipher))
}
