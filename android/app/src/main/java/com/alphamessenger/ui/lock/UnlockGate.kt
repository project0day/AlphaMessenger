package com.alphamessenger.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.alphamessenger.data.MasterDataKey
import com.alphamessenger.data.RecoveryKdfPublicStore
import com.alphamessenger.data.RecoveryKeyManager
import com.alphamessenger.data.UnlockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.alphamessenger.R

/**
 * UnlockGate — the SINGLE UI entry point to the database lifecycle (C2 Stage 3).
 *
 * Shown before any app content while [com.alphamessenger.data.CoreHolder] is
 * locked. It obtains the MasterDataKey (or decides there is none yet) and hands
 * it to [UnlockManager.unlock], which is the only place that opens the DB. The
 * UI never inspects the migration state (`legacy`/`migrated`/`confirmed`) and
 * never replicates the Rust resolver — it only routes the unlock input.
 *
 * Three pre-DB scenarios (decided from plaintext signals, before any DB access):
 *  1. No public KDF mirror yet  → brand-new install / pre-password onboarding →
 *     `unlock(masterKey = null)` → Rust opens legacy.
 *  2. KDF present + cached key   → crypto-bound BiometricPrompt → master key.
 *  3. KDF present + no cache     → password screen → `deriveMasterKeyFromPublic`.
 *
 * The secondary AppLock PIN ([LockScreen]) is a SEPARATE layer that runs ABOVE
 * this, only after a successful unlock — it is not merged here.
 */
@Composable
fun UnlockGate(
    activity: FragmentActivity,
    unlockManager: UnlockManager,
    recoveryKeyManager: RecoveryKeyManager,
    recoveryKdfPublicStore: RecoveryKdfPublicStore,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Force the password screen if biometric fails / is dismissed (scenario 3).
    var forcePassword by remember { mutableStateOf(false) }

    val hasKdf = remember { recoveryKdfPublicStore.has() }
    val hasCache = remember { recoveryKeyManager.hasCachedKey() }

    fun doUnlock(masterKey: ByteArray?) {
        scope.launch {
            busy = true
            error = null
            try {
                when (val r = unlockManager.unlock(masterKey)) {
                    is UnlockManager.UnlockResult.Failed -> {
                        busy = false
                        error = r.cause.message ?: activity.getString(R.string.unlock_failed_db)
                        if (hasKdf) forcePassword = true
                    }
                    else -> Unit
                }
            } finally {
                masterKey?.fill(0)
            }
        }
    }

    // Scenario 1: no password yet → open legacy immediately (nothing sensitive).
    LaunchedEffect(Unit) {
        if (!hasKdf) doUnlock(null)
    }

    // The key remembered on this phone opens it with no question; the app lock
    // above still guards the screen. Only if that fails is the password asked.
    var tryingRemembered by remember { mutableStateOf(hasKdf && com.alphamessenger.data.RememberedDbKey.has(activity)) }
    LaunchedEffect(Unit) {
        if (tryingRemembered && !unlockManager.unlockWithoutUser()) tryingRemembered = false
    }

    // Scenario 2: cached key → auto-trigger crypto-bound biometric once.
    LaunchedEffect(tryingRemembered) {
        if (!tryingRemembered && hasKdf && hasCache && !forcePassword) {
            promptBiometricForKey(
                activity = activity,
                recoveryKeyManager = recoveryKeyManager,
                onMasterKey = { FreshAuth.mark(); doUnlock(it) },
                onError = {
                    // Dismissed / hardware issue → offer the password path.
                    forcePassword = true
                },
            )
        }
    }

    val colors = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
    val showPassword = hasKdf && (forcePassword || !hasCache)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().lockBackdrop(colors)) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .imePadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VSpace(if (showPassword) 64.dp else 36.dp)
            AlphaBrand(logo = if (showPassword) 96.dp else 68.dp, word = if (showPassword) 40.sp else 30.sp)
            VSpace(10.dp)
            // While the database opens, the padlock springs open.
            LockedLine(
                stringResource(if (busy || tryingRemembered || !hasKdf) R.string.gate_title_unlocking else R.string.lock_closed_line),
                opening = busy || tryingRemembered || !hasKdf,
            )
            Spacer(Modifier.weight(1f))
            when {
                busy || tryingRemembered || !hasKdf -> Unit
                showPassword -> {
                    var pw by remember { mutableStateOf("") }
                    var visible by remember { mutableStateOf(false) }
                    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                    androidx.compose.foundation.text.BasicTextField(
                        value = pw,
                        onValueChange = { pw = it; error = null },
                        singleLine = true,
                        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 17.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { field ->
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxWidth().height(58.dp).clip(shape)
                                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f), shape)
                                    .padding(start = 18.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Icon(
                                    com.alphamessenger.ui.icons.AlphaIcons.Outlined.Key, null,
                                    tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    if (pw.isEmpty()) Text(stringResource(R.string.gate_password_label), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f), fontSize = 16.sp)
                                    field()
                                }
                                androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                                    androidx.compose.material3.Icon(
                                        if (visible) com.alphamessenger.ui.icons.AlphaIcons.Outlined.VisibilityOff else com.alphamessenger.ui.icons.AlphaIcons.Outlined.Visibility,
                                        stringResource(if (visible) R.string.lock_hide_password else R.string.lock_show_password),
                                        tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    val canOpen = pw.isNotBlank()
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxWidth().height(56.dp).clip(shape)
                            .background(
                                if (canOpen) androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.accent, colors.msgOutEnd))
                                else androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f)),
                            )
                            .clickable(enabled = canOpen) {
                                val json = recoveryKdfPublicStore.get()
                                if (json.isNullOrBlank()) {
                                    error = activity.getString(R.string.gate_no_kdf)
                                    return@clickable
                                }
                                val throttle = com.alphamessenger.data.UnlockThrottle(activity)
                                val wait = throttle.secondsLeft()
                                if (wait > 0) {
                                    error = activity.getString(R.string.lock_too_many_attempts, wait)
                                    return@clickable
                                }
                                scope.launch {
                                    busy = true
                                    error = null
                                    val master = try {
                                        withContext(Dispatchers.IO) {
                                            // Pre-open password path — no DB. Throws on a
                                            // wrong password (verifier mismatch).
                                            uniffi.alphamessenger
                                                .deriveMasterKeyFromPublic(pw, json)
                                                .map { it.toByte() }
                                                .toByteArray()
                                        }
                                    } catch (e: Exception) {
                                        busy = false
                                        throttle.registerFailure()
                                        error = activity.getString(R.string.gate_wrong_password)
                                        null
                                    }
                                    if (master != null) {
                                        throttle.registerSuccess()
                                        doUnlock(master)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.lock_open),
                            color = if (canOpen) colors.msgOutText else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
                            fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                    }
                    if (hasCache) {
                        Spacer(Modifier.height(18.dp))
                        GatePill(stringResource(R.string.action_use_biometric), com.alphamessenger.ui.icons.AlphaIcons.Filled.Fingerprint, colors.accent) {
                            forcePassword = false
                            promptBiometricForKey(
                                activity = activity,
                                recoveryKeyManager = recoveryKeyManager,
                                onMasterKey = { FreshAuth.mark(); doUnlock(it) },
                                onError = { forcePassword = true },
                            )
                        }
                    }
                }
                else -> {
                    FingerprintPulse(colors.accent) {
                        promptBiometricForKey(
                            activity = activity,
                            recoveryKeyManager = recoveryKeyManager,
                            onMasterKey = { FreshAuth.mark(); doUnlock(it) },
                            onError = { forcePassword = true },
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.lock_place_finger), color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.lock_place_finger_sub), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    GatePill(stringResource(R.string.gate_biometric_negative), null, colors.accent) { forcePassword = true }
                }
            }
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(text = it, color = androidx.compose.ui.graphics.Color(0xFFFF8A8A), fontSize = 14.sp)
            }
            Spacer(Modifier.height(if (showPassword) 24.dp else 8.dp))
        }
    }
}

/** A glass pill: "Use fingerprint" / "Enter password". */
@Composable
private fun GatePill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    androidx.compose.foundation.layout.Row(
        Modifier.height(48.dp).clip(shape)
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f))
            .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f), shape)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp)
    }
}

/**
 * Crypto-bound biometric unlock: authenticates the user against the Keystore key,
 * then decrypts the cached MasterDataKey. Distinct from [promptBiometric] (the
 * AppLock UX barrier, which uses no [BiometricPrompt.CryptoObject]).
 */
private fun promptBiometricForKey(
    activity: FragmentActivity,
    recoveryKeyManager: RecoveryKeyManager,
    onMasterKey: (ByteArray) -> Unit,
    onError: (String) -> Unit,
) {
    val cipher = recoveryKeyManager.getUnlockCipher()
    if (cipher == null) {
        onError(activity.getString(R.string.biometric_no_saved_key))
        return
    }
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authed = result.cryptoObject?.cipher
                if (authed == null) {
                    onError(activity.getString(R.string.biometric_no_cipher))
                    return
                }
                val master: MasterDataKey? = recoveryKeyManager.decryptCachedKey(authed)
                if (master == null) {
                    onError(activity.getString(R.string.biometric_decrypt_failed))
                    return
                }
                // Copy out before close (close zeroizes the wrapped bytes).
                val bytes = master.get().copyOf()
                master.close()
                onMasterKey(bytes)
            }

            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                onError(msg.toString())
            }
        },
    )
    // The system's own sheet (it alone releases the key): short, and it closes
    // by itself on a match. "Password" takes the user to the field.
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Alpha")
        .setNegativeButtonText(activity.getString(R.string.gate_biometric_negative))
        .setConfirmationRequired(false)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}
