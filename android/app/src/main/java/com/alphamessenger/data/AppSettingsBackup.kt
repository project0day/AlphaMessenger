package com.alphamessenger.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own settings inside a backup: what the user chose (appearance, folders,
 * privacy and media choices, assistants with their keys…). The core keeps them in
 * the database before each backup and hands them back after a restore.
 *
 * Only user choices travel: never the app lock, recovery or database keys, caches,
 * or anything that describes this one phone.
 */
object AppSettingsBackup {
    private const val VERSION = 1

    /** Plain preference files that hold user choices. */
    private val FILES = listOf(
        "ui_prefs", "chat_folders", "chat_looks", "chat_panels", "chat_patterns", "gif_library",
        "media_quality", "privacy_media", "saved_tags", "screen_security", "view_once_secure",
        "auto_download", "storage_auto_cleanup", "stories_audience", "emoji_recents_v2", "profile_status",
        "stickers", "anim_emoji", "alpha_network_privacy", "alpha_playback", "alpha_alerts",
        "alpha_chat_timer", "alpha_group_mod", "alpha_user_block", "alpha_browser", "pinned_hidden",
        "discussion_seen", "chat_history_cleared", "shared_media_grid", "verified_contacts", "alpha_feed",
    )
    /** Settings-store keys that belong to this phone alone. */
    private val DATASTORE_SKIP = setOf("chat_wallpaper_uri")
    private const val MAX_FILE_CHARS = 2 * 1024 * 1024

    suspend fun collect(ctx: Context): String {
        val app = ctx.applicationContext
        val root = JSONObject().put("v", VERSION)
        val files = JSONObject()
        for (name in FILES) {
            val all = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            val o = encode(all)
            if (o.toString().length <= MAX_FILE_CHARS) files.put(name, o)
        }
        root.put("files", files)
        com.alphamessenger.assistant.AssistantStore.init(app)
        com.alphamessenger.assistant.AssistantStore.allPrefs()?.let { root.put("assistant", encode(it)) }
        val ds = com.alphamessenger.di.AppModule.provideDataStore(app).data.first().asMap()
        root.put("settings", encode(ds.filterKeys { it.name !in DATASTORE_SKIP }.mapKeys { it.key.name }))
        return root.toString()
    }

    /** Writes the settings back. The app restarts afterwards: screens read them once. */
    suspend fun apply(ctx: Context, json: String) {
        if (json.isBlank()) return
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        val app = ctx.applicationContext
        root.optJSONObject("files")?.let { files ->
            for (name in files.keys()) {
                if (name !in FILES) continue
                val p = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                p.edit().clear().also { decodeInto(files.getJSONObject(name), it) }.commit()
            }
        }
        root.optJSONObject("assistant")?.let { o ->
            com.alphamessenger.assistant.AssistantStore.init(app)
            com.alphamessenger.assistant.AssistantStore.replacePrefs { e -> decodeInto(o, e) }
        }
        root.optJSONObject("settings")?.let { o ->
            com.alphamessenger.di.AppModule.provideDataStore(app).edit { prefs ->
                for (k in o.keys()) {
                    if (k in DATASTORE_SKIP) continue
                    val e = o.getJSONObject(k)
                    when (e.optString("t")) {
                        "s" -> prefs[stringPreferencesKey(k)] = e.getString("v")
                        "b" -> prefs[booleanPreferencesKey(k)] = e.getBoolean("v")
                        "i" -> prefs[intPreferencesKey(k)] = e.getInt("v")
                        "l" -> prefs[longPreferencesKey(k)] = e.getLong("v")
                        "f" -> prefs[floatPreferencesKey(k)] = e.getDouble("v").toFloat()
                        "d" -> prefs[doublePreferencesKey(k)] = e.getDouble("v")
                        "ss" -> prefs[stringSetPreferencesKey(k)] = e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    }
                }
            }
        }
    }

    private fun encode(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) {
            val e = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Double -> JSONObject().put("t", "d").put("v", v)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>()))
                else -> null
            }
            if (e != null) o.put(k, e)
        }
        return o
    }

    private fun decodeInto(o: JSONObject, e: SharedPreferences.Editor) {
        for (k in o.keys()) {
            val x = o.getJSONObject(k)
            when (x.optString("t")) {
                "s" -> e.putString(k, x.getString("v"))
                "b" -> e.putBoolean(k, x.getBoolean("v"))
                "i" -> e.putInt(k, x.getInt("v"))
                "l" -> e.putLong(k, x.getLong("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                "ss" -> e.putStringSet(k, x.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
    }
}

/** A fresh process after a restore: settings, relays and the core all start from what was restored. */
object AppRestart {
    /** After a restore: says so, then starts the app anew. */
    fun afterRestore(ctx: Context) {
        val app = ctx.applicationContext
        // Written now: the restart must not find the welcome screen again.
        app.getSharedPreferences("alpha_onboarding", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true).commit()
        android.widget.Toast.makeText(app, com.alphamessenger.R.string.restore_restarting, android.widget.Toast.LENGTH_LONG).show()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ now(app) }, 1500)
    }

    fun now(ctx: Context) {
        val app = ctx.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(android.content.Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}
