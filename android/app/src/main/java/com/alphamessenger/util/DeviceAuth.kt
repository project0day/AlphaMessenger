package com.alphamessenger.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

/**
 * "Is it really you?" before something that gives the account away (the
 * secret key): fingerprint, face or the screen lock. A phone with no screen
 * lock cannot ask, so it lets the action through.
 */
object DeviceAuth {
    private val allowed: Int
        get() = if (android.os.Build.VERSION.SDK_INT >= 30)
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAsk(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS

    fun confirm(activity: FragmentActivity, title: String, onOk: () -> Unit) {
        if (!canAsk(activity)) { onOk(); return }
        val prompt = BiometricPrompt(
            activity, androidx.core.content.ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }
}
