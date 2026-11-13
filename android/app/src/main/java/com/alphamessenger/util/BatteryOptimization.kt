package com.alphamessenger.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization (Doze) exemption helper.
 *
 * Without an exemption, Android suspends this app's foreground service and its
 * ntfy long-poll connection once the device enters deep Doze, so push wakes
 * can't be delivered until an OS maintenance window (minutes later) — the
 * "messages arrive late in the background" problem. Since we don't use Google
 * FCM, whitelisting the app is the only way to keep delivery prompt. This
 * mirrors what other messengers do.
 */
object BatteryOptimization {

    private const val PREFS = "battery_opt"
    private const val KEY_ASKED = "asked"

    /** True if the app is already exempt from Doze battery optimization. */
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Prompt the user to exempt the app, at most once (unless already exempt).
     * Shows the system "Allow app to run in background?" dialog directly so it's
     * a single tap. Safe to call on every unlock — it self-gates.
     */
    /** The user tapped "Allow": show the system dialog now, no once-only gate. */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isExempt(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    @SuppressLint("BatteryLife")
    fun maybePrompt(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isExempt(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            // Some ROMs reject the direct request — fall back to the settings list.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Open the exemption dialog/settings unconditionally (e.g. a Settings row). */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
