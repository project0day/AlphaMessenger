package com.alphamessenger.calls.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alphamessenger.calls.ui.CallActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.alphamessenger.R

// --- Channels ---
// Incoming calls: HIGH importance (full-screen intent, heads-up allowed).
// Ongoing calls:  LOW importance (silent, no heads-up over the call screen).
// Missed calls:   DEFAULT importance (sound + pop, auto-cancel).
private const val CHANNEL_INCOMING = "alpha_calls_incoming"
private const val CHANNEL_ONGOING = "alpha_calls_ongoing"
private const val CHANNEL_MISSED = "alpha_calls_missed"
private const val NOTIF_ID = 7771

/**
 * Builds + posts call notifications.
 *
 * ## Three channels (heads-up behaviour)
 *  - INCOMING (HIGH) — full-screen intent + heads-up. Only used while ringing.
 *  - ONGOING (LOW) — silent, NO heads-up. The connected-call notification
 *    stays in the shade only; it does NOT pop over the call screen.
 *  - MISSED (DEFAULT) — sound + pop, auto-cancel on tap.
 *
 * ## Avatar
 *  Loads the peer's avatar bitmap (if [avatarPath] is set) and attaches it as
 *  the notification large icon + the Person icon. The video/audio distinction
 *  (video vs audio) is conveyed via the title prefix + the small icon.
 */
@Singleton
class CallNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.calls_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.calls_channel_incoming_desc)
                    setSound(null, null); enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.calls_channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.calls_channel_ongoing_desc)
                    setSound(null, null); enableVibration(false); setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MISSED, context.getString(R.string.calls_channel_missed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.calls_channel_missed)
                }
            )
        }
    }

    /** Start the foreground service that hosts the ongoing notification. */
    fun startForegroundService(callId: String, peerName: String, video: Boolean, avatarPath: String = "") {
        val svc = Intent(context, CallForegroundService::class.java).apply {
            putExtra("call_id", callId)
            putExtra("peer_name", peerName)
            putExtra("video", video)
            putExtra("avatar_path", avatarPath)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, svc)
    }

    fun stopForegroundService() {
        context.stopService(Intent(context, CallForegroundService::class.java))
    }

    /** Show the incoming-call notification (full-screen + heads-up). */
    fun showIncomingCall(peerName: String, callId: String, video: Boolean, avatarPath: String = "") {
        val fullScreen = PendingIntent.getActivity(
            context, 0,
            Intent(context, CallActivity::class.java).apply {
                putExtra("call_id", callId); flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answerPI = actionPI(CallActions.ACTION_ANSWER, callId)
        val rejectPI = actionPI(CallActions.ACTION_REJECT, callId)
        val title = if (video) context.getString(R.string.calls_incoming_video) else context.getString(R.string.calls_incoming_voice)
        val avatar = loadAvatar(avatarPath)
        val b = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(if (video) android.R.drawable.ic_menu_camera else android.R.drawable.ic_menu_call)
            .setContentTitle(peerName.ifBlank { context.getString(R.string.calls_peer_fallback) })
            .setContentText(title)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
        if (avatar != null) b.setLargeIcon(avatar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = androidx.core.app.Person.Builder()
                .setName(peerName.ifBlank { context.getString(R.string.calls_peer_fallback) })
                .setImportant(true)
                .apply { if (avatar != null) setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(avatar)) }
                .build()
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(person, rejectPI, answerPI))
            b.addPerson(person)
        } else {
            b.addAction(0, coloredLabel(context.getString(R.string.calls_decline), 0xFFEB5757.toInt()), rejectPI)
            b.addAction(0, coloredLabel(context.getString(R.string.calls_answer), 0xFF34C759.toInt()), answerPI)
        }
        post(b.build())
    }

    /** The ongoing connected-call notification (LOW channel — no heads-up). */
    fun buildOngoing(peerName: String, callId: String, connected: Boolean, video: Boolean, avatarPath: String = ""): Notification {
        val text = if (connected) context.getString(R.string.calls_ongoing_text) else context.getString(R.string.calls_connecting_text)
        val hangupPI = actionPI(CallActions.ACTION_HANGUP, callId)
        val avatar = loadAvatar(avatarPath)
        // Tapping the notification opens the call screen — critical for the
        // lockscreen case where the user accepted via notification, unlocked,
        // and needs to get back to the call UI.
        val openCallPI = PendingIntent.getActivity(
            context, 0,
            Intent(context, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(if (video) android.R.drawable.ic_menu_camera else android.R.drawable.ic_menu_call)
            .setContentTitle(peerName)
            .setContentText(text)
            .setContentIntent(openCallPI)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (avatar != null) b.setLargeIcon(avatar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = androidx.core.app.Person.Builder()
                .setName(peerName.ifBlank { context.getString(R.string.calls_peer_fallback) })
                .setImportant(true)
                .apply { if (avatar != null) setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(avatar)) }
                .build()
            b.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangupPI))
            b.addPerson(person)
        } else {
            b.addAction(0, coloredLabel(context.getString(R.string.calls_hangup), 0xFFEB5757.toInt()), hangupPI)
        }
        return b.build()
    }

    /** Refresh the ongoing notification once the call actually connects, so it
     *  stops saying the connecting text and shows the ongoing text. Re-posts under
     *  the SAME id as the foreground notification, replacing it in place (the
     *  foreground notification is built with connected=false and would never
     *  update itself). */
    fun updateOngoingConnected(peerName: String, callId: String, video: Boolean, avatarPath: String = "") {
        post(buildOngoing(peerName, callId, connected = true, video = video, avatarPath = avatarPath))
    }

    /** Missed-call notification (DEFAULT channel — sound + pop). */
    fun showMissedCall(chatId: String, peerName: String, video: Boolean, avatarPath: String = "", atMs: Long = System.currentTimeMillis()) {
        val callBack = PendingIntent.getActivity(
            context, chatId.hashCode(),
            Intent(context, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(CallActivity.EXTRA_OUTGOING_CHAT, chatId)
                putExtra(CallActivity.EXTRA_OUTGOING_VIDEO, video)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openChat = PendingIntent.getActivity(
            context, chatId.hashCode() + 1,
            Intent(context, com.alphamessenger.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", chatId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(atMs))
        val avatar = loadAvatar(avatarPath)
        val b = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(peerName)
            .setContentText("${if (video) context.getString(R.string.calls_missed_video) else context.getString(R.string.calls_missed_voice)} · $time")
            .setContentIntent(openChat)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(atMs)
            .setShowWhen(true)
            .addAction(0, context.getString(R.string.calls_callback), callBack)
            .addAction(0, context.getString(R.string.calls_message), openChat)
        if (avatar != null) b.setLargeIcon(avatar)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(3000 + (chatId.hashCode() and 0xFFFF), b.build())
    }

    /** "Remind me": a call-back notification [minutes] from now. */
    fun scheduleCallReminder(chatId: String, peerName: String, video: Boolean, avatarPath: String, minutes: Long = 10) {
        val data = androidx.work.Data.Builder()
            .putString("chat", chatId).putString("name", peerName)
            .putBoolean("video", video).putString("avatar", avatarPath)
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "call_reminder_$chatId",
            androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<CallReminderWorker>()
                .setInitialDelay(minutes, java.util.concurrent.TimeUnit.MINUTES)
                .setInputData(data)
                .build(),
        )
        android.widget.Toast.makeText(context, context.getString(R.string.call_reminder_set), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** The reminder itself: "Call back <name>" with Call back / Message. */
    fun showCallReminder(chatId: String, peerName: String, video: Boolean, avatarPath: String) {
        val callBack = PendingIntent.getActivity(
            context, chatId.hashCode() + 2,
            Intent(context, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(CallActivity.EXTRA_OUTGOING_CHAT, chatId)
                putExtra(CallActivity.EXTRA_OUTGOING_VIDEO, video)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openChat = PendingIntent.getActivity(
            context, chatId.hashCode() + 3,
            Intent(context, com.alphamessenger.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", chatId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(context.getString(R.string.call_reminder_title, peerName))
            .setContentText(context.getString(R.string.call_reminder_text))
            .setContentIntent(openChat)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, context.getString(R.string.calls_callback), callBack)
            .addAction(0, context.getString(R.string.calls_message), openChat)
        loadAvatar(avatarPath)?.let { b.setLargeIcon(it) }
        context.getSystemService(NotificationManager::class.java)
            .notify(5000 + (chatId.hashCode() and 0xFFFF), b.build())
    }

    // --- Helpers ---

    /** Load the peer's avatar as a Bitmap (null if missing/unreadable). */
    private fun loadAvatar(path: String): Bitmap? {
        if (path.isBlank()) return null
        return runCatching {
            val f = File(path)
            if (!f.exists()) return null
            com.alphamessenger.data.MediaVault.decodeFile(path)
        }.getOrNull().also { if (it == null) Log.w("CallNotif", "avatar load failed: $path") }
    }

    private fun coloredLabel(text: String, color: Int): CharSequence {
        val s = android.text.SpannableString(text)
        s.setSpan(android.text.style.ForegroundColorSpan(color), 0, s.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun actionPI(action: String, callId: String): PendingIntent {
        val intent = Intent(context, CallForegroundService::class.java).apply {
            this.action = action; putExtra("call_id", callId)
        }
        return PendingIntent.getService(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun post(n: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, n)
    }

    fun cancel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_ID)
    }
}

object CallActions {
    const val ACTION_ANSWER = "com.alphamessenger.calls.ANSWER"
    const val ACTION_REJECT = "com.alphamessenger.calls.REJECT"
    const val ACTION_HANGUP = "com.alphamessenger.calls.HANGUP"
}
