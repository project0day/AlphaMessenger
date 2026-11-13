package com.alphamessenger.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copying a secret (the account key): marked sensitive, so the system keeps it
 * out of clipboard previews and suggestions, and cleared after a minute if it
 * is still what the clipboard holds.
 */
object SecretClipboard {
    private const val CLEAR_AFTER_MS = 60_000L

    fun copy(context: Context, label: String, secret: String) {
        val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, secret).apply {
            description.extras = PersistableBundle().apply {
                val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE
                    else "android.content.extra.IS_SENSITIVE"
                putBoolean(key, true)
            }
        }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            val still = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() == secret }.getOrDefault(false)
            if (still) runCatching { cm.clearPrimaryClip() }
        }, CLEAR_AFTER_MS)
    }
}
