package com.alphamessenger.ui.chatlist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What this phone searched for and which chats it opened lately — local only. */
object SearchHistory {
    private const val PREFS = "search_history"
    private const val MAX_QUERIES = 8
    private const val MAX_CHATS = 12

    var queries by mutableStateOf(emptyList<String>())
        private set
    var chats by mutableStateOf(emptyList<String>())
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        queries = p.getString("q", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        chats = p.getString("c", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    }

    fun addQuery(ctx: Context, query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.length < 2) return
        queries = (listOf(q) + queries.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_QUERIES)
        save(ctx)
    }

    fun removeQuery(ctx: Context, query: String) {
        queries = queries - query
        save(ctx)
    }

    fun addChat(ctx: Context, chatId: String) {
        if (chatId.isBlank()) return
        chats = (listOf(chatId) + chats.filterNot { it == chatId }).take(MAX_CHATS)
        save(ctx)
    }

    fun clear(ctx: Context) {
        queries = emptyList(); chats = emptyList()
        save(ctx)
    }

    private fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("q", queries.joinToString("\n"))
            .putString("c", chats.joinToString("\n"))
            .apply()
    }
}
