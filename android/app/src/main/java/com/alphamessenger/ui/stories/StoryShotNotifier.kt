package com.alphamessenger.ui.stories

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.service.MessageNotifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Fil took a screenshot of your story" — once per viewer and story.
 * Runs from the sync service on every metadata change (so it works with the
 * app in the background) and from the stories screen; a lock keeps the two
 * from telling the same thing twice.
 */
object StoryShotNotifier {
    private val lock = Mutex()

    suspend fun check(ctx: Context, repository: AlphaCoreRepository) = lock.withLock {
        val root = runCatching { org.json.JSONObject(repository.storiesJson()) }.getOrNull() ?: return@withLock
        val mine = root.optJSONArray("mine") ?: return@withLock
        val prefs = ctx.getSharedPreferences("stories_audience", Context.MODE_PRIVATE)
        val told = prefs.getStringSet("shots_told", emptySet()).orEmpty().toMutableSet()
        var changed = false
        for (i in 0 until mine.length()) {
            val st = mine.optJSONObject(i) ?: continue
            val viewers = st.optJSONArray("viewers") ?: continue
            for (j in 0 until viewers.length()) {
                val v = viewers.optJSONObject(j) ?: continue
                if (!v.optBoolean("shot")) continue
                val key = st.optString("id") + ":" + v.optString("sid")
                if (key in told) continue
                told += key; changed = true
                val who = runCatching { repository.getPeerProfile(v.optString("sid"))?.displayName }.getOrNull()
                    .orEmpty().ifBlank { ctx.getString(R.string.chat_member_fallback) }
                runCatching {
                    val nm = NotificationManagerCompat.from(ctx)
                    if (!nm.areNotificationsEnabled()) return@runCatching
                    val n = NotificationCompat.Builder(ctx, MessageNotifier.CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setContentTitle(ctx.getString(R.string.stories_shot_title))
                        .setContentText(ctx.getString(R.string.stories_shot_text, who))
                        .setAutoCancel(true)
                        .build()
                    @Suppress("MissingPermission")
                    nm.notify(key.hashCode(), n)
                }
            }
        }
        if (changed) prefs.edit().putStringSet("shots_told", told).apply()
    }
}
