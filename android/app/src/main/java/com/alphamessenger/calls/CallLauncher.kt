package com.alphamessenger.calls

import android.content.Context
import android.content.Intent
import com.alphamessenger.calls.manager.CallManager
import com.alphamessenger.calls.ui.CallActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The single entry point for launching a call from anywhere in the app
 * (ChatScreen buttons, notification actions, deep links). Resolves the
 * [CallManager] singleton via a Hilt [EntryPoint] (no need for the caller to
 * be a @HiltViewModel / @AndroidEntryPoint) + opens [CallActivity].
 *
 * Usage:
 * ```
 * CallLauncher.start(context, chatId, CallType.AUDIO)
 * CallLauncher.start(context, chatId, CallType.VIDEO)
 * ```
 */
object CallLauncher {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CallsEntryPoint {
        fun callManager(): CallManager
        fun callHistoryStore(): com.alphamessenger.calls.CallHistoryStore
    }

    /** Open the call Activity in "outgoing" mode. The Activity requests the
     *  mic/camera permissions FIRST and only then asks the manager to dial —
     *  dialing before the permissions are granted would start the camera FGS
     *  without the CAMERA permission (a SecurityException on Android 14). */
    fun start(context: Context, chatId: String, type: CallType) {
        val intent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(CallActivity.EXTRA_OUTGOING_CHAT, chatId)
            putExtra(CallActivity.EXTRA_OUTGOING_VIDEO, type == CallType.VIDEO)
        }
        context.startActivity(intent)
    }
}
