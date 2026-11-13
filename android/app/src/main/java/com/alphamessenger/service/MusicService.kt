package com.alphamessenger.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.alphamessenger.ui.MainActivity
import com.alphamessenger.ui.chat.MusicPlayerHolder

/**
 * The system side of the music player: a media session over the app's music
 * player, so it shows in the notification shade and on the lock screen and
 * answers headset buttons. Media3 posts the notification and keeps the
 * service in the foreground while music plays.
 */
class MusicService : MediaSessionService() {
    companion object {
        /** Tapping the system player opens the app on the music sheet. */
        const val EXTRA_OPEN_MUSIC = "open_music"
    }

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_MUSIC, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, MusicPlayerHolder.get(this).sessionPlayer())
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
