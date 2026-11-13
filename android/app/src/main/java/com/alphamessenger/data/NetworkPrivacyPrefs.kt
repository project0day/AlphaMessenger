package com.alphamessenger.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network privacy settings that live outside the encrypted core: they are read
 * by the call engine, which runs independently of the Rust core.
 */
@Singleton
class NetworkPrivacyPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("alpha_network_privacy", Context.MODE_PRIVATE)

    /** Calls go only through a TURN relay: the peer never learns our IP. */
    var callsRelayOnly: Boolean
        get() = prefs.getBoolean(KEY_RELAY_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_RELAY_ONLY, value) }

    /** Build a card for links we send (our phone opens the link). Off: links
     *  go as plain text and no site learns we sent them. */
    var linkPreviews: Boolean
        get() = prefs.getBoolean(KEY_LINK_PREVIEWS, true)
        set(value) = prefs.edit { putBoolean(KEY_LINK_PREVIEWS, value) }

    /** Our IP may not reach the peer: the user asked for it, or private mode is on. */
    val callsMustRelay: Boolean
        get() = callsRelayOnly || torEnabled

    /** Videos from links play in the app's player; off: the link opens outside. */
    var inAppVideo: Boolean
        get() = prefs.getBoolean(KEY_IN_APP_VIDEO, true)
        set(value) = prefs.edit { putBoolean(KEY_IN_APP_VIDEO, value) }

    /** Mirror of the core's Tor setting for Kotlin-side clients (maps). */
    var torEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOR, false)
        set(value) = prefs.edit { putBoolean(KEY_TOR, value) }

    /** Mirror of "secret chats exist": they reach their relays only through Tor. */
    var secretChats: Boolean
        get() = prefs.getBoolean(KEY_SECRET_CHATS, false)
        set(value) = prefs.edit { putBoolean(KEY_SECRET_CHATS, value) }

    var turnUrl: String
        get() = prefs.getString(KEY_TURN_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_TURN_URL, value.trim()) }

    var turnUser: String
        get() = prefs.getString(KEY_TURN_USER, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_TURN_USER, value.trim()) }

    var turnPass: String
        get() = prefs.getString(KEY_TURN_PASS, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_TURN_PASS, value) }

    private companion object {
        const val KEY_RELAY_ONLY = "calls_relay_only"
        const val KEY_TOR = "tor_enabled"
        const val KEY_SECRET_CHATS = "secret_chats"
        const val KEY_LINK_PREVIEWS = "link_previews"
        const val KEY_IN_APP_VIDEO = "in_app_video"
        const val KEY_TURN_URL = "turn_url"
        const val KEY_TURN_USER = "turn_user"
        const val KEY_TURN_PASS = "turn_pass"
    }
}

/**
 * Throttles Recovery-Password guesses at the unlock gate: from the 5th wrong
 * password on, input is blocked for 30 s, doubling each time (max 1 h).
 * Stored in plain prefs because it must work before the database is open.
 */
class UnlockThrottle(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("alpha_unlock_throttle", Context.MODE_PRIVATE)

    /** Seconds left before the next attempt is allowed (0 = allowed). */
    fun secondsLeft(): Long {
        val until = prefs.getLong(KEY_UNTIL, 0L)
        val left = until - System.currentTimeMillis()
        return if (left > 0) left / 1000 + 1 else 0
    }

    fun registerFailure() {
        val n = prefs.getInt(KEY_FAILS, 0) + 1
        prefs.edit {
            putInt(KEY_FAILS, n)
            if (n >= 5) {
                val delay = (30_000L shl (n - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
                putLong(KEY_UNTIL, System.currentTimeMillis() + delay)
            }
        }
    }

    fun registerSuccess() = prefs.edit { remove(KEY_FAILS); remove(KEY_UNTIL) }

    private companion object {
        const val KEY_FAILS = "fails"
        const val KEY_UNTIL = "until"
    }
}
