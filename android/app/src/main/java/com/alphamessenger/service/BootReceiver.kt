package com.alphamessenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the phone restarts or the app is updated, messages keep arriving without
 * anyone opening the app: the sync service opens the database by itself
 * ([com.alphamessenger.data.UnlockManager.unlockWithoutUser]) and stops if it cannot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val start = Intent(context, MessageSyncService::class.java).setAction(MessageSyncService.ACTION_START)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, start) }
            .onFailure { android.util.Log.w("BootReceiver", "sync not started: ${it.javaClass.simpleName}") }
    }
}
