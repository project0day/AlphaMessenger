package com.alphamessenger.calls

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** How a call ended — drives the history row icon/color. */
enum class CallOutcome {
    ANSWERED,   // connected; durationSec is meaningful
    MISSED,     // incoming, never answered (timeout / caller cancelled)
    REJECTED,   // explicitly declined (by us for incoming, by peer for outgoing)
    BUSY,       // peer was on another call
    NO_ANSWER,  // outgoing, nobody picked up
    CANCELLED,  // outgoing, we hung up before the answer
    FAILED,     // transport/media failure
    REFUSED,    // incoming, not let through by our privacy rules (it never rang)
}

/** One call-history row. */
data class CallHistoryEntry(
    val callId: String,
    val chatId: String,
    val peerName: String,
    val peerAvatarPath: String,
    val video: Boolean,
    val outgoing: Boolean,
    val outcome: CallOutcome,
    /** Epoch ms when the call started (offer sent/received). */
    val atMs: Long,
    /** Talk time in seconds (0 unless ANSWERED). */
    val durationSec: Int,
)

/**
 * THE call history — a local, private journal of calls (who / when / how it
 * ended / how long). Persisted in SharedPreferences as JSON, capped at
 * [MAX_ENTRIES]. Signaling/history never leaves the device.
 */
@Singleton
class CallHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("call_history", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<CallHistoryEntry>> = _entries.asStateFlow()

    /** Missed calls the user has not looked at yet (the Calls tab badge). */
    private val _unseenMissed = MutableStateFlow(countUnseen(_entries.value))
    val unseenMissed: StateFlow<Int> = _unseenMissed.asStateFlow()

    /** Prepend a finished call (newest first) and persist. */
    fun add(entry: CallHistoryEntry) {
        val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = updated
        save(updated)
        _unseenMissed.value = countUnseen(updated)
    }

    /** The Calls tab is open: every missed call so far has been seen. */
    fun markMissedSeen() {
        val newest = _entries.value.maxOfOrNull { it.atMs } ?: return
        if (newest > prefs.getLong(KEY_SEEN, 0L)) prefs.edit().putLong(KEY_SEEN, newest).apply()
        _unseenMissed.value = 0
    }

    private fun countUnseen(list: List<CallHistoryEntry>): Int {
        val seen = prefs.getLong(KEY_SEEN, 0L)
        return list.count { it.outcome == CallOutcome.MISSED && !it.outgoing && it.atMs > seen }
    }

    fun clear() {
        _entries.value = emptyList()
        prefs.edit().remove(KEY).apply()
        _unseenMissed.value = 0
    }

    /** Delete the entries matching the given call ids (multi-select delete). */
    fun delete(callIds: Set<String>) {
        val updated = _entries.value.filterNot { it.callId in callIds }
        _entries.value = updated
        save(updated)
        _unseenMissed.value = countUnseen(updated)
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private fun load(): List<CallHistoryEntry> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    CallHistoryEntry(
                        callId = o.optString("id"),
                        chatId = o.optString("chat"),
                        peerName = o.optString("name"),
                        peerAvatarPath = o.optString("avatar"),
                        video = o.optBoolean("video"),
                        outgoing = o.optBoolean("out"),
                        outcome = runCatching { CallOutcome.valueOf(o.optString("outcome")) }
                            .getOrDefault(CallOutcome.FAILED),
                        atMs = o.optLong("at"),
                        durationSec = o.optInt("dur"),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun save(entries: List<CallHistoryEntry>) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.callId)
                    put("chat", e.chatId)
                    put("name", e.peerName)
                    put("avatar", e.peerAvatarPath)
                    put("video", e.video)
                    put("out", e.outgoing)
                    put("outcome", e.outcome.name)
                    put("at", e.atMs)
                    put("dur", e.durationSec)
                })
            }
            prefs.edit().putString(KEY, arr.toString()).apply()
        }
    }

    private companion object {
        const val KEY = "entries"
        /** Time of the newest call already seen in the Calls tab. */
        const val KEY_SEEN = "missed_seen_at"
        const val MAX_ENTRIES = 200
    }
}
