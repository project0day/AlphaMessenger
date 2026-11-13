package com.alphamessenger.assistant

import com.alphamessenger.util.PublicHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The assistant's own look at the web: a search and a page read, made by this
 * phone, never by the AI service. Only
 * public https addresses are reached (see [PublicHttp]).
 */
object AssistantWeb {
    data class Hit(val title: String, val url: String, val snippet: String)

    private const val UA = "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    /** Wikimedia asks API clients to name themselves. */
    private const val WIKI_UA = "AlphaMessenger-Assistant/1.0"
    private const val MAX_PAGE_CHARS = 12_000
    private const val MAX_BYTES = 1_500_000

    /** [limited]: the keyless search refused us for now, so the hits may be partial. */
    data class Found(val hits: List<Hit>, val limited: Boolean, val why: String = "")

    private const val DDG_PAUSE_MS = 3 * 60_000L
    private const val DDG_GAP_MS = 1_500L
    private const val CACHE_MS = 10 * 60_000L
    @Volatile private var ddgLast = 0L
    /** Why the keyless search last failed, for the journal: "refused 202", "unreachable …". */
    @Volatile private var ddgWhy = ""
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Pair<Long, Found>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, Found>>) = size > 32
    })

    /**
     * The user's search API when a key is set; otherwise the keyless search, used
     * gently (one query at a time, paused for minutes once it says "too many"),
     * with Wikipedia's own API added when that search has nothing to give.
     */
    suspend fun search(query: String): Found = withContext(Dispatchers.IO) {
        val q = query.trim().take(200)
        val now = System.currentTimeMillis()
        cache[q.lowercase()]?.let { (at, found) -> if (now - at < CACHE_MS) return@withContext found }
        val refusals = ArrayList<String>()
        val viaKey = LinkedHashMap<String, Hit>()
        for (source in AssistantStore.searchKey().split(Regex("""[\s,]+""")).filter { it.isNotBlank() }.distinct()) {
            if (viaKey.size >= 6) break
            val label = sourceLabel(source)
            if (System.currentTimeMillis() < (sourcePause[source] ?: 0L)) { refusals += "$label paused"; continue }
            runCatching { keyed(q, source) }
                .onSuccess { hits -> hits.forEach { viaKey.putIfAbsent(it.url.trimEnd('/'), it) } }
                .onFailure {
                    if (it is Refused) sourcePause[source] = System.currentTimeMillis() + DDG_PAUSE_MS
                    refusals += "$label " + (it.message ?: it.javaClass.simpleName)
                }
        }
        // Free agent-search services, best first; each one's refusal pauses only it.
        for ((name, run) in listOf<Pair<String, (String) -> List<Hit>>>(KEENABLE to ::keenable, PARALLEL to ::parallel, PURILI to ::purili)) {
            if (viaKey.isNotEmpty()) break
            if (System.currentTimeMillis() < (sourcePause[name] ?: 0L)) { refusals += "${sourceLabel(name)} paused"; continue }
            runCatching { run(q) }
                .onSuccess { hits -> hits.forEach { viaKey.putIfAbsent(it.url.trimEnd('/'), it) } }
                .onFailure {
                    if (it is Refused) sourcePause[name] = System.currentTimeMillis() + DDG_PAUSE_MS
                    refusals += sourceLabel(name) + " " + (it.message ?: it.javaClass.simpleName)
                }
        }
        val found = if (viaKey.isNotEmpty()) Found(viaKey.values.toList(), false) else {
            val ddg = ddg(q)
            val hits = ddg.orEmpty().ifEmpty { wikipedia(q) }
            Found(hits, limited = ddg == null, why = (refusals + if (ddg == null) listOf(ddgWhy) else emptyList()).joinToString("; "))
        }
        if (found.hits.isNotEmpty() && !found.limited) cache[q.lowercase()] = now to found
        found
    }

    private const val PURILI = "https://puri.li"
    private const val PARALLEL = "https://search.parallel.ai/mcp"
    private const val KEENABLE = "https://api.keenable.ai"
    /** Keyless calls must name the app (1,000 an hour per address). */
    private val keenableHeaders = mapOf("User-Agent" to WIKI_UA, "Accept" to "application/json", "X-Keenable-Title" to "AlphaMessenger")

    private fun keenable(q: String): List<Hit> {
        val json = PublicHttp.get("$KEENABLE/v1/search/public?query=" + URLEncoder.encode(q, "UTF-8"), Proxy.NO_PROXY,
            keenableHeaders, 15_000, 20_000).use { r ->
            check(r.code)
            String(readCapped(r.inputStream), Charsets.UTF_8)
        }
        val a = org.json.JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map {
            val text = it.optString("snippet").ifBlank { it.optString("description") }
            // Snippets come as markdown: links reduced to their words.
            val clean = text.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1").replace(Regex("""\s+"""), " ").take(600)
            Hit(plain(it.optString("title")), it.optString("url"), clean)
        }.filter { it.title.isNotBlank() && it.url.startsWith("https://") }.take(6)
    }

    /** A page's readable text from Keenable's fetcher: for pages built by scripts. */
    private fun keenableFetch(url: String): String? = runCatching {
        val json = PublicHttp.get("$KEENABLE/v1/fetch/public?url=" + URLEncoder.encode(url, "UTF-8"), Proxy.NO_PROXY,
            keenableHeaders, 15_000, 30_000).use { r ->
            if (r.code !in 200..299) return@runCatching null
            String(readCapped(r.inputStream), Charsets.UTF_8)
        }
        val o = org.json.JSONObject(json)
        val text = o.optString("content").trim()
        if (text.isBlank()) null else (o.optString("title").let { if (it.isBlank()) "" else "$it\n\n" } + text).take(MAX_PAGE_CHARS)
    }.getOrNull()

    /** Its free tier is rate-limited per session id: one random id for this app run. */
    private val parallelSession = java.util.UUID.randomUUID().toString().replace("-", "")

    /** A hosted search service for agents (MCP over plain JSON-RPC, keyless free tier). */
    private fun parallel(q: String): List<Hit> {
        val call = org.json.JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", org.json.JSONObject().put("name", "web_search").put("arguments", org.json.JSONObject()
                .put("objective", q)
                .put("search_queries", org.json.JSONArray().put(q.take(120)))
                .put("session_id", parallelSession)))
        val text = PublicHttp.get(PARALLEL, Proxy.NO_PROXY,
            mapOf("User-Agent" to WIKI_UA, "Accept" to "application/json, text/event-stream"), 15_000, 30_000,
            postBody = call.toString().toRequestBody("application/json".toMediaType())).use { r ->
            check(r.code)
            String(readCapped(r.inputStream), Charsets.UTF_8)
        }
        // A streamed reply carries the same JSON on its "data:" line.
        val json = text.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: text
        val result = org.json.JSONObject(json).optJSONObject("result") ?: throw java.io.IOException("no result")
        if (result.optBoolean("isError")) throw java.io.IOException("tool error")
        val sc = result.optJSONObject("structuredContent")
            ?: result.optJSONArray("content")?.optJSONObject(0)?.optString("text")?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            ?: return emptyList()
        val a = sc.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { o ->
            val ex = o.optJSONArray("excerpts")
            val snippet = (0 until (ex?.length() ?: 0)).joinToString(" … ") { ex!!.optString(it) }.replace(Regex("""\s+"""), " ").take(600)
            val date = o.optString("publish_date").takeIf { it.isNotBlank() && it != "null" }?.let { " ($it)" }.orEmpty()
            Hit(o.optString("title").ifBlank { o.optString("url") } + date, o.optString("url"), snippet)
        }.filter { it.url.startsWith("https://") }.take(6)
    }

    /** An independent index with a free, keyless JSON API (its own crawler, small for Russian). */
    private fun purili(q: String): List<Hit> {
        val json = PublicHttp.get("$PURILI/api/search?q=" + URLEncoder.encode(q, "UTF-8"), Proxy.NO_PROXY,
            mapOf("User-Agent" to WIKI_UA, "Accept" to "application/json"), 15_000, 20_000).use { r ->
            check(r.code)
            String(readCapped(r.inputStream), Charsets.UTF_8)
        }
        val a = org.json.JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }
            .map { Hit(plain(it.optString("title")), it.optString("url"), plain(it.optString("description")).take(400)) }
            .filter { it.title.isNotBlank() && it.url.startsWith("https://") }
            .take(6)
    }

    /** The text Purili's crawler stored for a page: for pages that need scripts or refuse us. */
    private fun puriliContext(url: String): String? = runCatching {
        val json = PublicHttp.get("$PURILI/api/context?url=" + URLEncoder.encode(url, "UTF-8"), Proxy.NO_PROXY,
            mapOf("User-Agent" to WIKI_UA, "Accept" to "application/json"), 15_000, 20_000).use { r ->
            if (r.code !in 200..299) return@runCatching null
            String(readCapped(r.inputStream), Charsets.UTF_8)
        }
        val o = org.json.JSONObject(json)
        val text = o.optString("content").ifBlank { o.optString("summary") }.trim()
        if (text.isBlank()) null else (o.optString("title").let { if (it.isBlank()) "" else "$it\n\n" } + text).take(MAX_PAGE_CHARS)
    }.getOrNull()

    /** A search source said "no" (403/429): left alone for a few minutes. */
    private class Refused(code: Int) : java.io.IOException("refused $code")

    private val sourcePause = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun check(code: Int) {
        if (code == 403 || code == 429) throw Refused(code)
        if (code !in 200..299) throw java.io.IOException("http $code")
    }

    /** Never the key itself in the journal. */
    private fun sourceLabel(source: String): String = when {
        source == PARALLEL -> "parallel"
        source == KEENABLE -> "keenable"
        source.startsWith("https://") -> runCatching { java.net.URL(source).host }.getOrDefault("searxng")
        source.startsWith("tvly-") -> "tavily"
        else -> "brave"
    }

    /**
     * An https address = the user's own SearXNG (its JSON API must be on); Tavily
     * keys start with "tvly-"; any other key is taken as a Brave Search key.
     */
    private fun keyed(q: String, key: String): List<Hit> {
        val enc = URLEncoder.encode(q, "UTF-8")
        if (key.startsWith("https://")) {
            val base = key.trimEnd('/').removeSuffix("/search")
            val json = PublicHttp.get("$base/search?format=json&q=$enc", Proxy.NO_PROXY,
                mapOf("User-Agent" to UA, "Accept" to "application/json"), 15_000, 30_000).use { r ->
                check(r.code)
                String(readCapped(r.inputStream), Charsets.UTF_8)
            }
            val a = org.json.JSONObject(json).optJSONArray("results") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }
                .map { Hit(plain(it.optString("title")), it.optString("url"), plain(it.optString("content")).take(400)) }
                .filter { it.title.isNotBlank() && it.url.startsWith("https://") }
                .take(8)
        }
        if (key.startsWith("tvly-")) {
            val body = org.json.JSONObject().put("query", q).put("max_results", 6).toString()
                .toRequestBody("application/json".toMediaType())
            val json = PublicHttp.get("https://api.tavily.com/search", Proxy.NO_PROXY,
                mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"), 15_000, 30_000, postBody = body).use { r ->
                check(r.code)
                String(readCapped(r.inputStream), Charsets.UTF_8)
            }
            val a = org.json.JSONObject(json).optJSONArray("results") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }
                .map { Hit(it.optString("title"), it.optString("url"), it.optString("content").take(400)) }
                .filter { it.title.isNotBlank() && it.url.startsWith("https://") }
        }
        val json = PublicHttp.get("https://api.search.brave.com/res/v1/web/search?count=6&q=$enc", Proxy.NO_PROXY,
            mapOf("X-Subscription-Token" to key, "Accept" to "application/json"), 15_000, 30_000).use { r ->
            check(r.code)
            String(readCapped(r.inputStream), Charsets.UTF_8)
        }
        val a = org.json.JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }
            .map { Hit(plain(it.optString("title")), it.optString("url"), plain(it.optString("description"))) }
            .filter { it.title.isNotBlank() && it.url.startsWith("https://") }
    }

    /** One public results page of the keyless search; each keeps its own refusal pause. */
    private class Page(val name: String, val lite: Boolean) {
        @Volatile var pausedUntil = 0L
        @Volatile var why = ""
    }
    private val pages = listOf(Page("html", lite = false), Page("lite", lite = true))

    /** Null = every page refused or was unreachable (not "found nothing"). */
    private fun ddg(query: String): List<Hit>? {
        for (page in pages) {
            if (System.currentTimeMillis() < page.pausedUntil) continue
            val hits = ddgPage(page, query)
            if (hits != null) return hits
        }
        ddgWhy = pages.joinToString("; ") { "${it.name} ${it.why}" }
        return null
    }

    private fun ddgPage(page: Page, query: String): List<Hit>? {
        synchronized(this) {
            val wait = ddgLast + DDG_GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            ddgLast = System.currentTimeMillis()
        }
        val headers = mapOf("User-Agent" to UA, "Accept-Language" to "en,ru;q=0.8")
        val html = runCatching {
            val r = if (page.lite) {
                PublicHttp.get("https://lite.duckduckgo.com/lite/", Proxy.NO_PROXY, headers, 20_000, 30_000,
                    postBody = okhttp3.FormBody.Builder().add("q", query).build())
            } else {
                PublicHttp.get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"), Proxy.NO_PROXY, headers, 20_000, 30_000)
            }
            r.use {
                // 202 comes with its "are you a robot" page: a refusal, honoured, not retried.
                if (r.code == 202 || r.code == 429 || r.code == 403) {
                    page.pausedUntil = System.currentTimeMillis() + DDG_PAUSE_MS
                    page.why = "refused ${r.code}"
                    return null
                }
                if (r.code !in 200..299) { page.why = "http ${r.code}"; return null }
                String(readCapped(r.inputStream), Charsets.UTF_8)
            }
        }.onFailure { page.why = "unreachable: " + it.javaClass.simpleName }.getOrNull() ?: return null
        val marker = if (page.lite) "result-link" else "result__a"
        if (!html.contains(marker) && html.contains("anomaly", ignoreCase = true)) {
            page.pausedUntil = System.currentTimeMillis() + DDG_PAUSE_MS
            page.why = "refused (check page)"
            return null
        }
        page.why = ""
        return if (page.lite) parseLite(html) else parseHtml(html)
    }

    private fun parseHtml(html: String): List<Hit> {
        val hits = ArrayList<Hit>()
        val block = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>(.*?)(?=<a[^>]+class="result__a"|$)""", RegexOption.DOT_MATCHES_ALL)
        for (m in block.findAll(html)) {
            val href = realUrl(m.groupValues[1]) ?: continue
            val title = plain(m.groupValues[2])
            val snippet = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(m.groupValues[3])?.groupValues?.get(1)?.let(::plain).orEmpty()
            if (title.isNotBlank()) hits += Hit(title, href, snippet)
            if (hits.size >= 6) break
        }
        return hits
    }

    /** The light page: a table, each link row followed by its snippet row. */
    private fun parseLite(html: String): List<Hit> {
        val hits = ArrayList<Hit>()
        val block = Regex("""<a[^>]+href="([^"]+)"[^>]*class='result-link'[^>]*>(.*?)</a>(.*?)(?=class='result-link'|$)""", RegexOption.DOT_MATCHES_ALL)
        for (m in block.findAll(html)) {
            val href = realUrl(m.groupValues[1]) ?: continue
            val title = plain(m.groupValues[2])
            val snippet = Regex("""class='result-snippet'[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
                .find(m.groupValues[3])?.groupValues?.get(1)?.let(::plain)?.replace(Regex("""\s+"""), " ").orEmpty()
            if (title.isNotBlank()) hits += Hit(title, href, snippet)
            if (hits.size >= 6) break
        }
        return hits
    }

    /** Wikipedia's search API, in Russian when the query is. */
    private fun wikipedia(q: String): List<Hit> = runCatching {
        val lang = if (q.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' }) "ru" else "en"
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=5&srsearch=" +
            URLEncoder.encode(q, "UTF-8")
        val json = PublicHttp.get(url, Proxy.NO_PROXY, mapOf("User-Agent" to WIKI_UA, "Accept" to "application/json"), 15_000, 20_000).use { r ->
            if (r.code !in 200..299) return@runCatching emptyList()
            String(readCapped(r.inputStream), Charsets.UTF_8)
        }
        val a = org.json.JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: return@runCatching emptyList()
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map {
            val title = it.optString("title")
            Hit("$title — Wikipedia", "https://$lang.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"), plain(it.optString("snippet")))
        }
    }.getOrDefault(emptyList())

    /** A page as plain text (scripts and styles out), shortened. */
    suspend fun read(url: String): String? = withContext(Dispatchers.IO) {
        xPost(url)?.let { return@withContext it }
        val direct = fetch(url)?.let { html ->
            val body = html
                .replace(Regex("(?is)<(script|style|noscript|svg|header|footer|nav)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
            plain(body).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim().take(MAX_PAGE_CHARS)
        }
        // A page built by scripts gives almost no text: the crawler's stored copy instead.
        if (direct != null && direct.length >= 400) direct else keenableFetch(url) ?: puriliContext(url) ?: direct
    }

    /**
     * Does a relay answer? Its info document (NIP-11) over https, the way pages are
     * fetched: any reply counts, a refusal or silence does not.
     */
    suspend fun relayAnswers(url: String): Boolean = withContext(Dispatchers.IO) {
        val https = when {
            url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
            else -> return@withContext true // onion ws://: Tor only, not probed here
        }
        runCatching {
            PublicHttp.get(https, Proxy.NO_PROXY, mapOf("User-Agent" to UA, "Accept" to "application/nostr+json"), 8_000, 8_000).use { true }
        }.getOrDefault(false)
    }

    /**
     * X pages need sign-in and scripts; a single post is public through X's own
     * embed endpoint (oEmbed). Null = not an X address.
     */
    private fun xPost(url: String): String? {
        val u = runCatching { java.net.URL(url) }.getOrNull() ?: return null
        val host = u.host.lowercase().removePrefix("www.").removePrefix("mobile.")
        if (host != "x.com" && host != "twitter.com") return null
        val id = Regex("/status(?:es)?/(\\d+)").find(u.path)?.groupValues?.get(1)
            ?: return "X shows only single posts without sign-in; this address is not a post (…/status/<id>)."
        val user = u.path.trim('/').substringBefore('/').ifBlank { "i" }
        val canonical = "https://twitter.com/$user/status/$id"
        return runCatching {
            val json = PublicHttp.get("https://publish.twitter.com/oembed?omit_script=1&url=" + URLEncoder.encode(canonical, "UTF-8"),
                Proxy.NO_PROXY, mapOf("User-Agent" to UA, "Accept" to "application/json"), 15_000, 20_000).use { r ->
                if (r.code !in 200..299) return@runCatching "This X post is not public or was deleted."
                String(readCapped(r.inputStream), Charsets.UTF_8)
            }
            val o = org.json.JSONObject(json)
            val text = plain(o.optString("html").replace(Regex("(?i)<br\\s*/?>"), "\n").replace("&mdash;", "—"))
            "X post by ${o.optString("author_name")} (${o.optString("author_url")}):\n$text"
        }.getOrDefault("The X post could not be read.")
    }

    private fun fetch(url: String): String? = runCatching {
        PublicHttp.get(url, Proxy.NO_PROXY, mapOf("User-Agent" to UA, "Accept-Language" to "en,ru;q=0.8"), 20_000, 30_000).use { r ->
            if (r.code !in 200..299) return null
            val type = r.contentType.orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text")) return null
            String(readCapped(r.inputStream), Charsets.UTF_8)
        }
    }.getOrNull()

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < MAX_BYTES) {
            val n = input.read(buf, 0, minOf(buf.size, MAX_BYTES - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The search page links through a redirect; the target is in `uddg`. */
    private fun realUrl(href: String): String? {
        val h = href.replace("&amp;", "&")
        val target = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: h
        val full = if (target.startsWith("//")) "https:$target" else target
        return full.takeIf { it.startsWith("https://") }
    }

    private fun plain(html: String): String = html
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&nbsp;", " ")
        .replace(Regex("&#(x[0-9a-fA-F]+|\\d+);")) { m ->
            val v = m.groupValues[1]
            val cp = if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else " "
        }
        .trim()
}
