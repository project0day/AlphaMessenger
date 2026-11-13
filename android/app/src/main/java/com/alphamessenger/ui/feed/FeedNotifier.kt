package com.alphamessenger.ui.feed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository

/**
 * New posts in the Alpha feed, told quietly. The feed knows no readers and can
 * push to no one, so the phone looks every few hours (over the same anonymous
 * connection as reading) and on its own channel says what is new.
 */
object FeedNotifier {
    private const val CHANNEL = "alpha_feed"
    private const val NOTIFICATION_ID = 0xA1F

    /** How often the background looks. */
    const val EVERY_MS = 3 * 3600_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alpha_feed", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("notify", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notify", v).apply()

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.feed_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.feed_channel_desc)
            setSound(null, null)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Look for new posts; tell about them once. The first look only remembers where the feed is. */
    suspend fun check(ctx: Context, repo: AlphaCoreRepository) {
        if (!enabled(ctx) || FeedStore.hidden) return
        FeedStore.load(ctx)
        if (!FeedStore.refresh(ctx, repo)) return
        val told = prefs(ctx).getLong("told", 0L)
        // A build this phone already has (or a newer one) is nothing to tell about.
        val mine = com.alphamessenger.ui.update.AppUpdate.installedCode(ctx)
        val fresh = FeedStore.posts.filter { it.at > told && !(it.kind == FeedKind.UPDATE && it.versionCode <= mine) }
        val newest = FeedStore.posts.maxOfOrNull { it.at } ?: return
        if (told == 0L) { prefs(ctx).edit().putLong("told", newest).apply(); return }
        if (fresh.isEmpty() || newest <= FeedStore.seenAt) { prefs(ctx).edit().putLong("told", newest).apply(); return }
        prefs(ctx).edit().putLong("told", newest).apply()

        ensureChannel(ctx)
        val first = fresh.maxBy { it.at }
        val text = when (first.kind) {
            FeedKind.PACK -> ctx.getString(R.string.feed_notif_pack, first.title)
            FeedKind.GIF -> ctx.getString(R.string.feed_notif_gif)
            FeedKind.UPDATE -> ctx.getString(R.string.feed_notif_update, first.versionName ?: first.title)
            FeedKind.NEWS, FeedKind.EMOJI, FeedKind.ALERT -> first.title.ifBlank { first.body }.take(140)
        }
        val open = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID,
            Intent(ctx, com.alphamessenger.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (fresh.size > 1) ctx.getString(R.string.feed_notif_title_many, fresh.size) else "Alpha")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n) } // no permission → nothing
    }
}
