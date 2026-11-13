package com.alphamessenger.ui.browser

import android.content.Context
import com.alphamessenger.data.EmbeddedTor
import com.alphamessenger.data.NetworkPrivacyPrefs
import com.alphamessenger.util.PublicHttp
import java.io.File
import java.net.Proxy

/**
 * The browser's ad and tracker blocking: public filter lists kept on the phone,
 * refreshed weekly (through Tor in private mode). Until they are loaded nothing
 * is blocked; pages are never held back waiting for them.
 */
object AdBlock {
    private val LISTS = listOf(
        "easylist" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy" to "https://easylist.to/easylist/easyprivacy.txt",
        "adguard-ru" to "https://filters.adtidy.org/extension/chromium/filters/1.txt",
    )
    private const val REFRESH_MS = 7L * 24 * 3600_000
    private const val MAX_LIST_BYTES = 8L * 1024 * 1024

    private val engine = AdBlockEngine()
    @Volatile var ready = false
        private set
    @Volatile private var loading = false
    private val cssCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 16
    })

    fun warmUp(context: Context) {
        if (ready || loading) return
        loading = true
        val app = context.applicationContext
        Thread({
            try {
                val dir = File(app.filesDir, "adblock").apply { mkdirs() }
                val proxy = if (NetworkPrivacyPrefs(app).torEnabled) EmbeddedTor.proxy() else Proxy.NO_PROXY
                for ((name, url) in LISTS) {
                    val file = File(dir, "$name.txt")
                    if (!file.exists() || System.currentTimeMillis() - file.lastModified() > REFRESH_MS) {
                        runCatching { download(url, file, proxy) }
                    }
                    if (file.exists()) runCatching { engine.addList(file.readText()) }
                }
                ready = engine.size() > 0
            } finally {
                loading = false
            }
        }, "adblock-load").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun download(url: String, into: File, proxy: Proxy) {
        val tmp = File(into.parentFile, into.name + ".part")
        PublicHttp.get(url, proxy, mapOf("User-Agent" to "AlphaMessenger"), 20_000, 60_000).use { r ->
            if (r.code !in 200..299) return
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val input = r.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_LIST_BYTES) return
                    out.write(buf, 0, n)
                }
            }
        }
        if (tmp.length() > 0 && !tmp.renameTo(into)) { into.delete(); tmp.renameTo(into) }
    }

    fun shouldBlock(requestUrl: String, pageUrl: String): Boolean = ready && engine.shouldBlock(requestUrl, pageUrl)

    fun css(pageHost: String): String {
        if (!ready || pageHost.isEmpty()) return ""
        return cssCache.getOrPut(pageHost) { engine.elementHideCss(pageHost) }
    }
}
