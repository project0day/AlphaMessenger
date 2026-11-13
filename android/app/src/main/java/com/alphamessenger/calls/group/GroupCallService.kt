package com.alphamessenger.calls.group

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alphamessenger.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps a group call alive in the background (microphone, and the camera when
 * video is on) with an ongoing notification; also shows an incoming group call
 * over the lock screen.
 */
@AndroidEntryPoint
class GroupCallService : Service() {
    companion object {
        const val ACTION_RING = "group_call_ring"
        const val ACTION_LEAVE = "group_call_leave"
        const val ONGOING_ID = 7781
        const val RING_ID = 7782
        private const val CH_RING = "group_call_ring"
        private const val CH_ONGOING = "group_call_ongoing"
    }

    @Inject lateinit var manager: GroupCallManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels()
        when (intent?.action) {
            ACTION_LEAVE -> { manager.leave(); stopSelf(); return START_NOT_STICKY }
            ACTION_RING -> {
                ring()
                // Not a foreground stay: the notification carries the ring.
                if (manager.state.value.phase != GroupCallPhase.IN_CALL) stopSelf()
                return START_NOT_STICKY
            }
        }
        val video = intent?.getBooleanExtra("video", false) == true
        val n = ongoing()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val cam = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            var mask = 0
            if (mic) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && cam) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            runCatching { if (mask != 0) startForeground(ONGOING_ID, n, mask) else startForeground(ONGOING_ID, n) }
                .onFailure { runCatching { startForeground(ONGOING_ID, n) }.onFailure { stopSelf() } }
        } else {
            startForeground(ONGOING_ID, n)
        }
        return START_NOT_STICKY
    }

    private fun channels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RING, getString(R.string.gcall_channel_ring), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, getString(R.string.gcall_channel_ongoing), NotificationManager.IMPORTANCE_LOW))
    }

    private fun openScreen(): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, GroupCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(): Notification {
        val leave = PendingIntent.getService(
            this, 2, Intent(this, GroupCallService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.gcall_ongoing))
            .setContentText(getString(R.string.gcall_ongoing_text))
            .setContentIntent(openScreen())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, getString(R.string.gcall_leave), leave)
            .build()
    }

    private fun ring() {
        val n = NotificationCompat.Builder(this, CH_RING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.gcall_incoming))
            .setContentText(getString(R.string.gcall_incoming_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(openScreen(), true)
            .setContentIntent(openScreen())
            .setAutoCancel(true)
            .setTimeoutAfter(45_000)
            .build()
        getSystemService(NotificationManager::class.java).notify(RING_ID, n)
    }
}
