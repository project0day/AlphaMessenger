package com.alphamessenger.ui.lock

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.alphamessenger.R
import androidx.compose.ui.res.stringResource
import com.alphamessenger.data.AppLockManager
import com.alphamessenger.ui.theme.AlphaTheme

// ── Biometric helpers ────────────────────────────────────────────────

/** True when the device has an enrolled strong biometric we can use. */
fun biometricAvailable(context: Context): Boolean =
    BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** Launch the system BiometricPrompt. Must be called with a FragmentActivity. */
fun promptBiometric(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negative: String = activity.getString(R.string.common_cancel),
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(code: Int, msg: CharSequence) = onError(msg.toString())
        },
    )
    // The sheet is the system's own: we keep it short, and it closes by itself
    // on a match (no extra "Confirm" tap).
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { if (subtitle.isNotBlank()) setSubtitle(subtitle) }
        .setNegativeButtonText(negative)
        .setConfirmationRequired(false)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info)
}

// ── Launch-time lock screen ───────────────────────────────────────────

/**
 * Full-screen gate shown before the app content when an app lock is set.
 * Offers biometric (auto-triggered if enabled) and/or a PIN pad.
 */
@Composable
fun LockScreen(
    activity: FragmentActivity,
    manager: AppLockManager,
    onUnlocked: () -> Unit,
    onPanic: () -> Unit = {},
) {
    val colors = AlphaTheme.colorScheme
    val hasBiometric = remember { manager.biometricEnabled && biometricAvailable(activity) }
    val hasPin = remember { manager.isPinSet }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var verifying by remember { mutableStateOf(false) }
    // Right answer: the padlock springs open and the screen dissolves into the app.
    var opening by remember { mutableStateOf(false) }
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    val leave by androidx.compose.animation.core.animateFloatAsState(
        if (opening) 0f else 1f, androidx.compose.animation.core.tween(200, delayMillis = 90), label = "leave",
    )

    fun succeed() {
        if (opening) return
        manager.registerSuccess()
        opening = true
        scope.launch { kotlinx.coroutines.delay(300); onUnlocked() }
    }

    fun tryBiometric() {
        promptBiometric(
            activity,
            title = "Alpha",
            subtitle = "",
            negative = activity.getString(if (hasPin) R.string.lock_use_pin else R.string.common_cancel),
            onSuccess = { succeed() },
            onError = { /* the PIN stays right here */ },
        )
    }

    // The fingerprint sheet comes up by itself.
    LaunchedEffect(Unit) { if (hasBiometric) tryBiometric() }

    Box(Modifier.fillMaxSize().lockBackdrop(colors)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .graphicsLayer { alpha = leave; val k = 0.94f + 0.06f * leave; scaleX = k; scaleY = k },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VSpace(36.dp)
            AlphaBrand(logo = 68.dp, word = 30.sp)
            VSpace(10.dp)
            LockedLine(stringResource(R.string.lock_closed_line), opening)
            if (hasPin) {
                VSpace(26.dp)
                Text(stringResource(R.string.lock_enter_pin), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                VSpace(18.dp)
                PinDots(if (opening) 6 else pin.length.coerceAtMost(6), colors.accent) { shake.value }
                VSpace(12.dp)
                Text(error.orEmpty(), color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.height(18.dp))
                Spacer(Modifier.weight(1f))
                GlassKeypad(
                    onDigit = { digit ->
                        if (verifying || opening) return@GlassKeypad
                        val blocked = manager.lockoutRemainingMs()
                        if (blocked > 0L) {
                            error = activity.getString(R.string.lock_too_many_attempts, (blocked / 1000) + 1)
                            return@GlassKeypad
                        }
                        if (pin.length < 6) {
                            pin += digit
                            error = null
                            // Known lengths: a check only there, and every miss counts
                            // (typing 4 digits and erasing must not be a free guess).
                            val lengths = manager.pinLengths()
                            val checkHere = if (lengths.isEmpty()) pin.length >= 4 else pin.length in lengths
                            if (checkHere) {
                                val candidate = pin
                                verifying = true
                                scope.launch {
                                    val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        when {
                                            manager.isPanicPinSet && manager.verifyPanicPin(candidate) -> 2
                                            manager.verifyPin(candidate) -> 1
                                            else -> 0
                                        }
                                    }
                                    verifying = false
                                    when {
                                        outcome == 2 -> onPanic()
                                        outcome == 1 -> succeed()
                                        lengths.isNotEmpty() && candidate.length < lengths.max() -> manager.registerFailure()
                                        lengths.isNotEmpty() || candidate.length == 6 -> {
                                            manager.registerFailure()
                                            error = activity.getString(R.string.lock_wrong_pin)
                                            pin = ""
                                            shake.shake()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onErase = { if (pin.isNotEmpty() && !opening) pin = pin.dropLast(1) },
                    onFingerprint = if (hasBiometric) ({ tryBiometric() }) else null,
                    accent = colors.accent,
                )
                VSpace(8.dp)
            } else {
                Spacer(Modifier.weight(1f))
                FingerprintPulse(colors.accent) { tryBiometric() }
                VSpace(24.dp)
                Text(stringResource(R.string.lock_place_finger), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                VSpace(6.dp)
                Text(stringResource(R.string.lock_biometric_tap_hint), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                Spacer(Modifier.weight(1.3f))
            }
        }
    }
}

// ── PIN setup dialog (used during onboarding / settings) ──────────────

/**
 * Two-step PIN setup: enter a 4–6 digit PIN, then confirm it.
 * Calls [onConfirm] with the validated PIN, or [onDismiss] if cancelled.
 */
@Composable
fun PinSetupDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val confirming = first != null

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            color = colors.bgSecondary, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (confirming) stringResource(R.string.pin_setup_repeat) else stringResource(R.string.pin_setup_create),
                    color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.pin_setup_hint),
                    color = colors.textSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val shown = pin.length.coerceAtMost(6)
                    repeat(6) { i ->
                        Box(
                            modifier = Modifier.size(14.dp).clip(CircleShape)
                                .background(if (i < shown) colors.accent else colors.bgTertiary),
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color(0xFFFF5A5A), fontSize = 13.sp)
                }
                Spacer(Modifier.height(18.dp))
                PinPad(
                    onDigit = {
                        if (pin.length < 6) { pin += it; error = null }
                        // Auto-advance only via the explicit OK row below.
                    },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onOk = {
                        if (pin.length < 4) { error = ctx.getString(R.string.pin_setup_min); return@PinPad }
                        if (!confirming) {
                            first = pin; pin = ""; error = null
                        } else {
                            if (pin == first) onConfirm(pin)
                            else { error = ctx.getString(R.string.pin_setup_mismatch); first = null; pin = "" }
                        }
                    },
                    showOk = true,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.common_cancel), color = colors.textMuted, fontSize = 14.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp),
                )
            }
        }
    }
}

// ── Numeric keypad ────────────────────────────────────────────────────

@Composable
private fun PinPad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onOk: (() -> Unit)? = null,
    showOk: Boolean = false,
) {
    val colors = AlphaTheme.colorScheme
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", if (showOk) "OK" else ""),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier.size(66.dp).clip(CircleShape)
                            .then(
                                if (key.isBlank()) Modifier
                                else Modifier.background(colors.bgTertiary).border(1.dp, colors.alphaBorder, CircleShape)
                            )
                            .then(
                                when (key) {
                                    "" -> Modifier
                                    "⌫" -> Modifier.clickable { onBackspace() }
                                    "OK" -> Modifier.clickable { onOk?.invoke() }
                                    else -> Modifier.clickable { onDigit(key) }
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            "" -> {}
                            "⌫" -> Icon(AlphaIcons.AutoMirrored.Filled.Backspace, stringResource(R.string.pin_pad_backspace), tint = colors.textPrimary)
                            "OK" -> Text("OK", color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            else -> Text(key, color = colors.textPrimary, fontSize = 24.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** "Lock now" requests (the lock button on the chat list) → MainActivity re-locks. */
/**
 * A fingerprint just given to open the database: the app lock's own fingerprint
 * would ask the same person again a second later. Valid once, for a short while.
 */
object FreshAuth {
    private var at = 0L
    fun mark() { at = android.os.SystemClock.elapsedRealtime() }
    fun consume(): Boolean {
        val fresh = at > 0L && android.os.SystemClock.elapsedRealtime() - at < 30_000L
        at = 0L
        return fresh
    }
}

object AppLockRequests {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: kotlinx.coroutines.flow.SharedFlow<Unit> = _events
    fun lockNow() { _events.tryEmit(Unit) }
}
