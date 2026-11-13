package com.alphamessenger.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs an export in the foreground (a notification with its progress), so a
 * large archive keeps being written while the app is in the background.
 */
@AndroidEntryPoint
class ExportService : Service() {

    @Inject lateinit var repository: AlphaCoreRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> if (job?.isActive == true) job?.cancel() else stopSelf()
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    /** The system's daily limit for this kind of work ran out: stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        if (job?.isActive == true) return
        val uri = intent.data
        val options = intent.getStringExtra(EXTRA_OPTIONS)?.let { runCatching { ExportOptions.fromJson(it) }.getOrNull() }
        if (uri == null || options == null) { stopSelf(); return }
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIF_PROGRESS, progress(getString(R.string.export_preparing), 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        ExportProgress.set(ExportProgress.State.Running("", 0, 0, 0, 0))
        job = scope.launch {
            var shown = 0L
            val result = runCatching {
                val out = contentResolver.openOutputStream(uri, "w") ?: error("no output")
                out.use {
                    ChatExporter(this@ExportService, repository, options) { p ->
                        ExportProgress.set(p)
                        val now = SystemClock.elapsedRealtime()
                        if (now - shown > 700) {
                            shown = now
                            notifySafe(NOTIF_PROGRESS, progress(
                                getString(R.string.export_running, p.chat),
                                p.chatIndex, p.chats,
                                getString(R.string.export_progress, p.messages, p.files),
                            ))
                        }
                    }.run(it)
                }
            }
            result.onSuccess { s ->
                ExportProgress.set(ExportProgress.State.Done(uri.toString(), s.chats, s.messages, s.files, s.missing))
                notifySafe(NOTIF_DONE, done(uri, getString(R.string.export_done_detail, s.chats, s.messages, s.files)))
            }.onFailure { e ->
                // A half-written archive is no use to anyone.
                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                ExportProgress.set(
                    if (e is CancellationException) ExportProgress.State.Cancelled
                    else ExportProgress.State.Failed(e.message ?: e.javaClass.simpleName),
                )
            }
            ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun progress(title: String, done: Int, total: Int, text: String? = null) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alpha)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(total, done, total == 0)
            .addAction(0, getString(R.string.common_cancel), PendingIntent.getService(
                this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()

    private fun done(uri: Uri, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alpha)
            .setContentTitle(getString(R.string.export_done))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 2,
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()

    private fun notifySafe(id: Int, n: android.app.Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    companion object {
        private const val CHANNEL_ID = "alpha_export"
        private const val NOTIF_PROGRESS = 7301
        private const val NOTIF_DONE = 7302
        private const val ACTION_START = "com.alphamessenger.export.START"
        private const val ACTION_CANCEL = "com.alphamessenger.export.CANCEL"
        private const val EXTRA_OPTIONS = "options"

        fun start(context: Context, uri: Uri, options: ExportOptions) {
            val i = Intent(context, ExportService::class.java)
                .setAction(ACTION_START).setData(uri).putExtra(EXTRA_OPTIONS, options.toJson())
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(ACTION_CANCEL))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.export_notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
