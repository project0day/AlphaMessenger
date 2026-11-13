package com.alphamessenger.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A ban record. [untilMs] <= 0 means permanent; otherwise an epoch-millis expiry. */
data class BanEntry(
    val npub: String,
    val name: String,
    val untilMs: Long,
    val avatarPath: String = "",
    val reason: String = "",
) {
    val isPermanent: Boolean get() = untilMs <= 0L
    fun isActive(now: Long = com.alphamessenger.util.TrustedTime.nowMillis()): Boolean = isPermanent || now < untilMs
}

/** A pending join request (G-7): someone asked to join via an invite
 *  link and awaits manual Accept/Reject by an owner/admin. [token] is the
 *  invite token the core needs to actually add the member on accept. */
data class PendingJoin(
    val npub: String,
    val token: String,
    val name: String = "",
    val avatarPath: String = "",
    val requestedAt: Long = System.currentTimeMillis(),
)

/** G-9: one audit-log entry (recent actions). */
data class AuditEntry(
    val actorPubkey: String,
    val actorName: String,
    val action: String,       // ban|unban|mute|unmute|role_*|join|leave|perms|metadata
    val targetPubkey: String,
    val targetName: String,
    val ts: String,           // RFC3339
)

/**
 * Local group moderation store (G-2): per-chat ban list (mutes will live here
 * too). Device-local for now — the MLS Remove+Commit is the real cryptographic
 *  eviction; this list just powers the "Blocked" UI and blocks local
 *  re-add. Cross-device ban-list sync is planned (G-5).
 */
class GroupModeration(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("alpha_group_mod", Context.MODE_PRIVATE)

    fun bans(chatId: String): List<BanEntry> {
        val raw = prefs.getString(banKey(chatId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BanEntry(
                    o.optString("npub"), o.optString("name"), o.optLong("until", 0L),
                    o.optString("avatar"), o.optString("reason"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addBan(chatId: String, npub: String, name: String, untilMs: Long, avatarPath: String = "", reason: String = "") {
        if (npub.isBlank()) return
        val list = bans(chatId).filter { it.npub != npub } +
            BanEntry(npub, name, untilMs, avatarPath, reason)
        save(chatId, list)
    }

    fun removeBan(chatId: String, npub: String) {
        save(chatId, bans(chatId).filter { it.npub != npub })
    }

    /** True if [npub] is currently banned (active, non-expired) in [chatId]. */
    fun isBanned(chatId: String, npub: String): Boolean =
        bans(chatId).firstOrNull { it.npub == npub }?.isActive() == true

    private fun save(chatId: String, list: List<BanEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("npub", it.npub).put("name", it.name).put("until", it.untilMs)
                    .put("avatar", it.avatarPath).put("reason", it.reason),
            )
        }
        prefs.edit().putString(banKey(chatId), arr.toString()).apply()
    }

    private fun banKey(chatId: String) = "bans:$chatId"

    // --- Pending join requests (G-7) ---

    fun pendingJoins(chatId: String): List<PendingJoin> {
        val raw = prefs.getString(joinKey(chatId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PendingJoin(
                    o.optString("npub"), o.optString("token"),
                    o.optString("name"), o.optString("avatar"), o.optLong("at", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addPendingJoin(chatId: String, npub: String, token: String, name: String = "", avatarPath: String = "") {
        if (npub.isBlank()) return
        // De-dup by npub; keep the freshest token.
        val list = pendingJoins(chatId).filter { it.npub != npub } +
            PendingJoin(npub, token, name, avatarPath)
        saveJoins(chatId, list)
    }

    fun removePendingJoin(chatId: String, npub: String) {
        saveJoins(chatId, pendingJoins(chatId).filter { it.npub != npub })
    }

    private fun saveJoins(chatId: String, list: List<PendingJoin>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("npub", it.npub).put("token", it.token)
                    .put("name", it.name).put("avatar", it.avatarPath).put("at", it.requestedAt),
            )
        }
        prefs.edit().putString(joinKey(chatId), arr.toString()).apply()
    }

    private fun joinKey(chatId: String) = "joins:$chatId"
}
