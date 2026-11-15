package com.alphamessenger.ui.feed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.sticker.StickerStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a batch of packs going into the catalog is. */
sealed interface PackPublishState {
    data object Idle : PackPublishState
    /** [name] is the pack on its way, or null while old pack posts move out of the feed. */
    data class Running(val done: Int, val total: Int, val name: String?, val moving: Boolean = false) : PackPublishState
    data class Done(val done: Int, val total: Int, val failed: Set<String>) : PackPublishState
}

/**
 * Publishes the owner's sticker packs into [StickerCatalog] in the foreground,
 * so hundreds of packs keep going while the owner reads chats or leaves the app.
 * Pack posts left in the feed from before the catalog move into it first.
 */
@AndroidEntryPoint
class PackPublishService : Service() {

    @Inject lateinit var repository: AlphaCoreRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> if (job?.isActive == true) job?.cancel() else stopSelf()
            ACTION_START -> start(intent.getStringArrayExtra(EXTRA_IDS)?.toList().orEmpty())
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun start(ids: List<String>) {
        if (job?.isActive == true || ids.isEmpty()) { if (job?.isActive != true) stopSelf(); return }
        ensureChannel(this)
        ServiceCompat.startForeground(this, NOTIF, progress(0, ids.size, null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        job = scope.launch {
            val failed = LinkedHashSet<String>()
            var done = 0
            try {
                val key = FeedStore.ownerKey(this@PackPublishService) ?: run { failed += ids; return@launch }
                moveFeedPacks(key, ids.size)
                val batch = ArrayList<Pair<String, CatalogPack>>()
                suspend fun flush() {
                    if (batch.isEmpty()) return
                    if (StickerCatalog.add(this@PackPublishService, repository, key, batch.map { it.second })) done += batch.size
                    else failed += batch.map { it.first }
                    batch.clear()
                }
                val base = System.currentTimeMillis() / 1000
                for ((i, id) in ids.withIndex()) {
                    val pack = StickerStore.pack(this@PackPublishService, id)
                    set(PackPublishState.Running(done + batch.size, ids.size, pack?.name))
                    val link = pack?.let { StickerStore.publishLatest(this@PackPublishService, repository, id) }
                    if (pack == null || link == null) { failed += id; continue }
                    batch += id to CatalogPack(link, pack.name, pack.stickers.size, base + i)
                    if (batch.size >= 10) flush()
                }
                flush()
            } finally {
                set(PackPublishState.Done(done, ids.size, failed))
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, NotificationCompat.Builder(this@PackPublishService, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_alpha)
                        .setContentTitle(getString(R.string.pack_publish_done, done, ids.size))
                        .setAutoCancel(true).build())
                }
                ServiceCompat.stopForeground(this@PackPublishService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Pack posts from before the catalog: into it, then out of the feed (tombstones at their own time). */
    private suspend fun moveFeedPacks(key: String, total: Int) {
        val old = FeedStore.fetchAll(repository)?.filter { it.kind == FeedKind.PACK && it.pack != null }.orEmpty()
        if (old.isEmpty()) return
        set(PackPublishState.Running(0, old.size, null, moving = true))
        val entries = old.sortedBy { it.at }.map { CatalogPack(it.pack!!, it.title, 0, it.at) }
        if (!StickerCatalog.add(this, repository, key, entries)) return
        for ((i, p) in old.withIndex()) {
            set(PackPublishState.Running(i, old.size, null, moving = true))
            FeedStore.removeQuietly(this, repository, p)
            // Paced: a burst of hundreds of events is what gets a key rate-limited.
            delay(200)
        }
    }

    private var shownAt = 0L

    private fun set(s: PackPublishState) {
        _state.value = s
        val now = System.currentTimeMillis()
        if (s is PackPublishState.Running && now - shownAt > 700) {
            shownAt = now
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF, progress(s.done, s.total, s.name, s.moving)) }
        }
    }

    private fun progress(done: Int, total: Int, name: String?, moving: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alpha)
            .setContentTitle(getString(if (moving) R.string.pack_publish_moving_of else R.string.pack_publish_running, done, total))
            .setContentText(name ?: getString(R.string.pack_publish_moving))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setProgress(total, done, false)
            .addAction(0, getString(R.string.common_cancel), PendingIntent.getService(
                this, 1, Intent(this, PackPublishService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()

    companion object {
        private const val CHANNEL_ID = "alpha_pack_publish"
        private const val NOTIF = 7311
        private const val NOTIF_DONE = 7312
        private const val ACTION_START = "com.alphamessenger.packs.START"
        private const val ACTION_CANCEL = "com.alphamessenger.packs.CANCEL"
        private const val EXTRA_IDS = "ids"

        private val _state = MutableStateFlow<PackPublishState>(PackPublishState.Idle)
        val state: StateFlow<PackPublishState> = _state

        fun start(context: Context, packIds: Collection<String>) {
            _state.value = PackPublishState.Running(0, packIds.size, null)
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, PackPublishService::class.java).setAction(ACTION_START).putExtra(EXTRA_IDS, packIds.toTypedArray()))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, PackPublishService::class.java).setAction(ACTION_CANCEL))
        }

        /** The result was shown: back to idle. */
        fun consume() { if (_state.value is PackPublishState.Done) _state.value = PackPublishState.Idle }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.pack_publish_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
