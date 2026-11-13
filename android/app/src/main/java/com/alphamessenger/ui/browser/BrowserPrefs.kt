package com.alphamessenger.ui.browser

import android.content.Context
import org.json.JSONArray

/** Where text typed in the address bar is searched (the person's own searching). */
enum class SearchEngine(val title: String, private val prefix: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    GOOGLE("Google", "https://www.google.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    YANDEX("Yandex", "https://yandex.ru/search/?text="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q="),
    /** The person's own address: %s marks the query, else it goes on the end. */
    CUSTOM("", "");

    fun url(query: String, custom: String = ""): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        if (this != CUSTOM) return prefix + q
        val t = custom.trim()
        if (!t.startsWith("https://") && !t.startsWith("http://")) return DUCKDUCKGO.url(query)
        return if (t.contains("%s")) t.replace("%s", q) else t + q
    }
}

/** The built-in browser's settings and open tabs; only on this phone. */
class BrowserPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("alpha_browser", Context.MODE_PRIVATE)

    /** Web links tapped in chats open here instead of another app. */
    var openLinksInside: Boolean
        get() = prefs.getBoolean("links_inside", true)
        set(v) = prefs.edit().putBoolean("links_inside", v).apply()

    var adBlock: Boolean
        get() = prefs.getBoolean("adblock", true)
        set(v) = prefs.edit().putBoolean("adblock", v).apply()

    /** Closing the browser wipes tabs, cookies, sign-ins and the cache. */
    var wipeOnClose: Boolean
        get() = prefs.getBoolean("wipe_on_close", false)
        set(v) = prefs.edit().putBoolean("wipe_on_close", v).apply()

    var thirdPartyCookies: Boolean
        get() = prefs.getBoolean("third_party_cookies", false)
        set(v) = prefs.edit().putBoolean("third_party_cookies", v).apply()

    /** Page text size, percent. */
    var textZoom: Int
        get() = prefs.getInt("text_zoom", 100)
        set(v) = prefs.edit().putInt("text_zoom", v).apply()

    var searchEngine: SearchEngine
        get() = SearchEngine.entries.firstOrNull { it.name == prefs.getString("search", null) } ?: SearchEngine.DUCKDUCKGO
        set(v) = prefs.edit().putString("search", v.name).apply()

    /** The address used by [SearchEngine.CUSTOM], e.g. https://example.org/search?q=%s */
    var customSearch: String
        get() = prefs.getString("search_custom", "").orEmpty()
        set(v) = prefs.edit().putString("search_custom", v.trim()).apply()

    fun searchUrl(query: String): String = searchEngine.url(query, customSearch)

    var desktopMode: Boolean
        get() = prefs.getBoolean("desktop", false)
        set(v) = prefs.edit().putBoolean("desktop", v).apply()

    /** Addresses of the open tabs, restored when the browser comes back. */
    var tabs: List<String>
        get() = runCatching {
            val a = JSONArray(prefs.getString("tabs", "[]"))
            (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("http") }
        }.getOrDefault(emptyList())
        set(v) = prefs.edit().putString("tabs", JSONArray(v).toString()).apply()

    var activeTab: Int
        get() = prefs.getInt("active", 0)
        set(v) = prefs.edit().putInt("active", v).apply()
}
