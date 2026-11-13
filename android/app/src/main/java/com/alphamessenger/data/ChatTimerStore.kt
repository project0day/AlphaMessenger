package com.alphamessenger.data

import android.content.Context

/**
 * Device-local per-chat "disappearing messages" default timer.
 * Keyed by chatId → seconds (0 = off). When set, the chat's outgoing messages
 * are sent as disappearing with this TTL. Reuses the existing ephemeral message
 * pipeline — no crypto/core changes. SharedPreferences, no DB migration.
 */
class ChatTimerStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("alpha_chat_timer", Context.MODE_PRIVATE)

    /** TTL in seconds for [chatId], or 0 if disappearing is off. */
    fun seconds(chatId: String): Int = prefs.getInt(chatId, 0)

    fun set(chatId: String, seconds: Int) {
        if (seconds <= 0) prefs.edit().remove(chatId).apply()
        else prefs.edit().putInt(chatId, seconds).apply()
    }
}
