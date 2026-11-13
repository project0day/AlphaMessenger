package com.alphamessenger.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Encrypted-at-rest store for view-once plaintext (Secret Note text / view-once
 * media ref).
 *
 * ## Why this exists
 * The Rust core deliberately NEVER persists view-once content to disk (security
 * The Rust core deliberately NEVER persists view-once content to disk — a
 * security rule. The plaintext only arrives in the realtime/catch-up
 * is the *safe* failure mode but a *bad* UX one: Android kills the process under
 * memory pressure and the recipient can never open a secret they were notified
 * about.
 *
 * ## How we keep it rock-solid
 * - Storage is [EncryptedSharedPreferences] backed by a hardware-backed Keystore
 *   master key (`AES256_GCM`). On disk there is only ciphertext — plaintext
 *   never touches the filesystem in the clear, never goes to logs, never to the
 *   normal message DB.
 * - Every entry carries an `expiresAt`. [sweepExpired] wipes stale entries at
 *   startup and periodically (driven by MessageSyncService's sweep). Even if the
 *   recipient never opens it, it self-destructs after [TTL_MS].
 * - On view ([clear]) we remove the entry synchronously (`commit`, not `apply`).
 *
 * The public API ([put]/[get]/[clear]) is unchanged so callers (CallbackBridge,
 * the fullscreen viewer, ChatViewModel) need no edits. If [init] was never
 * called (e.g. very early in process start) we transparently fall back to an
 * in-memory map so nothing crashes — the old safe behaviour.
 */
object ViewOnceCache {
    private const val TAG = "ViewOnceCache"
    private const val PREFS = "view_once_secure"
    /** 7 days — matches the maximum disappearing timer. */
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    data class Entry(val text: String?, val mediaRefJson: String?, val mediaType: String?)

    // Fallback when encrypted prefs are unavailable / not yet initialised.
    private val mem = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Call once from Application.onCreate. Safe to call more than once. */
    fun init(context: Context) {
        if (prefs != null) return
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            sweepExpired()
        } catch (e: Throwable) {
            // Keystore can fail on broken OEM ROMs — degrade to RAM-only.
            Log.w(TAG, "EncryptedSharedPreferences unavailable — using RAM fallback", e)
            prefs = null
        }
    }

    fun put(id: String, text: String?, mediaRefJson: String?, mediaType: String?) {
        if (id.isBlank()) return
        Log.i(TAG, "PUT id=$id textLen=${text?.length ?: -1} hasRef=${mediaRefJson != null} mtype=$mediaType backend=${if (prefs == null) "MEM" else "ENC"}")
        val entry = Entry(text, mediaRefJson, mediaType)
        val p = prefs
        if (p == null) {
            mem[id] = entry
            return
        }
        try {
            val json = JSONObject().apply {
                put("text", text ?: JSONObject.NULL)
                put("ref", mediaRefJson ?: JSONObject.NULL)
                put("mtype", mediaType ?: JSONObject.NULL)
                put("exp", System.currentTimeMillis() + TTL_MS)
            }
            p.edit().putString(id, json.toString()).apply()
        } catch (e: Throwable) {
            mem[id] = entry
        }
    }

    fun get(id: String): Entry? {
        if (id.isBlank()) return null
        val p = prefs
        if (p == null) {
            val m = mem[id]
            Log.i(TAG, "GET id=$id backend=MEM hit=${m != null}")
            return m
        }
        val raw = p.getString(id, null)
        if (raw == null) {
            val m = mem[id]
            Log.i(TAG, "GET id=$id backend=ENC miss(prefs) memHit=${m != null}")
            return m
        }
        return try {
            val o = JSONObject(raw)
            if (o.optLong("exp", 0L) in 1 until System.currentTimeMillis()) {
                // Expired — wipe and report missing (the safe one-view failure).
                Log.i(TAG, "GET id=$id EXPIRED → wipe")
                p.edit().remove(id).apply()
                return null
            }
            Log.i(TAG, "GET id=$id backend=ENC hit")
            Entry(
                text = o.optStringOrNull("text"),
                mediaRefJson = o.optStringOrNull("ref"),
                mediaType = o.optStringOrNull("mtype"),
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(id: String) {
        mem.remove(id)
        // Synchronous wipe on view — do not let the secret linger.
        try { prefs?.edit()?.remove(id)?.commit() } catch (_: Throwable) {}
    }

    /** Remove every entry whose TTL has elapsed. Cheap; safe to call often. */
    fun sweepExpired() {
        val p = prefs ?: return
        try {
            val now = System.currentTimeMillis()
            val dead = p.all.keys.filter { key ->
                val raw = p.getString(key, null) ?: return@filter true
                val exp = try { JSONObject(raw).optLong("exp", 0L) } catch (_: Throwable) { 0L }
                exp in 1 until now
            }
            if (dead.isNotEmpty()) {
                val ed = p.edit()
                dead.forEach { ed.remove(it) }
                ed.apply()
            }
        } catch (_: Throwable) { /* best effort */ }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val s = optString(key, "")
        return s.ifBlank { null }
    }
}
