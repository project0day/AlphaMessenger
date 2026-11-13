package com.alphamessenger.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A blocked 1:1 user (device-local). */
data class BlockedUser(
    val npub: String,
    val name: String = "",
    val avatarPath: String = "",
    /** Epoch seconds when blocked; incoming messages newer than this are hidden. */
    val blockedAt: Long = 0L,
)

/**
 * Device-local blocklist for 1:1 chats (G-11). Serverless + E2EE means we can't
 * stop a blocked peer from publishing to relays, but we CAN ignore them: hide
 * their incoming messages/notifications and lock the composer in their chat.
 * Keyed by the peer's Nostr npub. SharedPreferences, no DB migration.
 */
class UserBlocklist(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("alpha_user_block", Context.MODE_PRIVATE)

    fun blocked(): List<BlockedUser> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BlockedUser(o.optString("npub"), o.optString("name"), o.optString("avatar"), o.optLong("at", 0L))
            }
        }.getOrDefault(emptyList())
    }

    fun isBlocked(npub: String): Boolean =
        npub.isNotBlank() && blocked().any { it.npub == npub }

    /** Epoch seconds when [npub] was blocked, or 0 if not blocked. */
    fun blockedAt(npub: String): Long =
        blocked().firstOrNull { it.npub == npub }?.blockedAt ?: 0L

    fun block(npub: String, name: String = "", avatarPath: String = "") {
        if (npub.isBlank()) return
        val now = System.currentTimeMillis() / 1000L
        val list = blocked().filter { it.npub != npub } + BlockedUser(npub, name, avatarPath, now)
        save(list)
    }

    fun unblock(npub: String) {
        save(blocked().filter { it.npub != npub })
    }

    private fun save(list: List<BlockedUser>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("npub", it.npub).put("name", it.name).put("avatar", it.avatarPath).put("at", it.blockedAt))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "blocked_users_v1" }
}
