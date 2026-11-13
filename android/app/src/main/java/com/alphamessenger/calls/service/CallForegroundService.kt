package com.alphamessenger.calls.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.alphamessenger.R
import com.alphamessenger.calls.manager.CallManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * THE ongoing-call foreground service. Keeps the WebRTC session alive when
 * the app is backgrounded / the screen is locked / the device rotates.
 *
 * Started by [CallNotificationHelper.startForegroundService] when a call
 * becomes active; stopped when the call ends. The notification it hosts
 * carries Answer / Reject / Hangup actions (routed back to [CallManager]).
 *
 * ## Foreground service type
 * On Android 14+ we declare `microphone|camera` so the system keeps the mic +
 * camera accessible while the app is in the background. The manifest already
 * declares the matching FOREGROUND_SERVICE_MICROPHONE / _CAMERA permissions.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject lateinit var callManager: CallManager
    @Inject lateinit var notifier: CallNotificationHelper

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra("call_id") ?: ""
        val peerName = intent?.getStringExtra("peer_name") ?: baseContext.getString(R.string.peer_fallback)
        val video = intent?.getBooleanExtra("video", false) ?: false
        val avatarPath = intent?.getStringExtra("avatar_path") ?: ""
        // Handle notification actions first.
        when (intent?.action) {
            CallActions.ACTION_ANSWER -> {
                // A call private mode cannot place is explained on the call screen instead.
                if (!com.alphamessenger.calls.TorCallWarning.needed(this)) callManager.acceptIncoming()
                // Launch the call UI — critical for lockscreen: the user
                // accepted via the notification but the screen is still locked.
                // The Activity has showWhenLocked + turnScreenOn in the manifest,
                // but we also need to explicitly start it here (#3).
                val ui = android.content.Intent(this, com.alphamessenger.calls.ui.CallActivity::class.java).apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
                runCatching { startActivity(ui) }
                return START_NOT_STICKY
            }
            CallActions.ACTION_REJECT -> { callManager.rejectIncoming(); return START_NOT_STICKY }
            CallActions.ACTION_HANGUP -> { callManager.hangup(); return START_NOT_STICKY }
        }
        // Start foreground with the call notification.
        //
        // Android 14 enforces that each declared FGS type is backed by its
        // GRANTED runtime permission: starting with type `camera` without the
        // CAMERA permission throws a SecurityException (this crashed voice
        // calls — camera permission is never asked for them). Build the type
        // mask from what's actually granted right now.
        val notif: Notification = notifier.buildOngoing(peerName, callId, connected = false, video = video, avatarPath = avatarPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val camGranted = checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            var mask = 0
            if (micGranted) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && camGranted) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            try {
                if (mask != 0) startForeground(7771, notif, mask)
                else startForeground(7771, notif)
            } catch (e: Exception) {
                // Last resort: try the untyped overload; if that fails too,
                // stop cleanly instead of the 5s "did not call startForeground" crash.
                runCatching { startForeground(7771, notif) }
                    .onFailure { stopSelf() }
            }
        } else {
            startForeground(7771, notif)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        notifier.cancel()
    }
}
