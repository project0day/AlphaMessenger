package com.alphamessenger.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A short, soft tick from the vibration motor — felt on every phone (the UI
 * "text handle" feedback is too faint on many and silent on some).
 */
object Haptics {
    fun tick(ctx: Context) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (v == null || !v.hasVibrator()) return
        runCatching {
            when {
                // The lightest knock the motor makes.
                Build.VERSION.SDK_INT >= 29 -> v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                Build.VERSION.SDK_INT >= 26 && v.hasAmplitudeControl() -> v.vibrate(VibrationEffect.createOneShot(8, 60))
                else -> @Suppress("DEPRECATION") v.vibrate(6)
            }
        }
    }
}
