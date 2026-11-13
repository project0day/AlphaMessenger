package com.alphamessenger.ui.chat.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.alphamessenger.ui.chat.LinkPreviewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Link previews, private by construction:
 *  - only the SENDER contacts the site (through Tor when private mode is on);
 *  - the preview image travels twice: a small copy INSIDE the end-to-end
 *    encrypted message (shown at once) and the full picture as an encrypted
 *    blob of our storage (see [fetch]'s `dir`) — the recipient never touches the site;
 *  - a generic browser User-Agent; the whole fetch is time-boxed so sending a
 *    message never waits more than a few seconds.
 */
object LinkPreviewFetcher {
    private const val TOTAL_BUDGET_MS = 9_000L
    /** The full picture: its long side at most this, JPEG. */
    private const val FULL_SIDE = 1280
    private const val HTML_LIMIT = 600_000
    private const val IMAGE_LIMIT = 4 * 1024 * 1024
    /** Keeps the whole preview message inside the 32 KiB MLS bucket (~45 KB
     *  event — public relays take it; a 64 KiB bucket they refuse). */
    private const val THUMB_MAX_BYTES = 18 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    /** Some sites give a phone an empty app shell; their desktop page has the tags. */
    private const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** Where the full pictures of the previews being built go (set by [fetch]). */
    @Volatile private var outDir: java.io.File? = null

    /**
     * The preview of [url]. With [dir], its picture is also kept there in full
     * ([LinkPreviewData.fullPath]) for sending as a blob.
     */
    suspend fun fetch(url: String, viaTor: Boolean, dir: java.io.File? = null): LinkPreviewData? = withContext(Dispatchers.IO) {
        dir?.let { it.mkdirs(); outDir = it }
        withTimeoutOrNull(TOTAL_BUDGET_MS) {
            runCatching { build(url, if (viaTor) torProxy() else Proxy.NO_PROXY) }.getOrNull()
        }
    }

    /** Sites that answer oEmbed (small JSON: title, author, cover), and whether they are video. */
    private val OEMBED = listOf(
        Triple(listOf("vimeo.com", "player.vimeo.com"), "https://vimeo.com/api/oembed.json?url=", true),
        Triple(listOf("dailymotion.com", "dai.ly"), "https://www.dailymotion.com/services/oembed?format=json&url=", true),
        Triple(listOf("open.spotify.com"), "https://open.spotify.com/oembed?url=", false),
        Triple(listOf("soundcloud.com", "on.soundcloud.com"), "https://soundcloud.com/oembed?format=json&url=", false),
    )

    private fun torProxy() = com.alphamessenger.data.EmbeddedTor.proxy()

    /** Public https only, each redirect hop checked like the first (see [com.alphamessenger.util.PublicHttp]). */
    private fun open(url: String, proxy: Proxy, accept: String, ua: String = UA): com.alphamessenger.util.PublicHttp.Response =
        com.alphamessenger.util.PublicHttp.get(
            url, proxy,
            mapOf(
                "User-Agent" to ua,
                "Accept" to accept,
                "Accept-Language" to java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8",
            ),
            connectMs = 4000, readMs = 5000,
        )

    private fun build(url: String, proxy: Proxy): LinkPreviewData? {
        val u = URL(url)
        if (u.protocol != "https" && u.protocol != "http") return null
        val host = u.host.lowercase().removePrefix("www.").removePrefix("m.")
        if (host == "youtube.com" || host == "youtu.be" || host == "music.youtube.com") {
            youtube(url, proxy)?.let { return it }
        }
        // TikTok: no Open Graph tags; the video's data sits in a JSON block in the
        // page body. Its oEmbed is the fallback (it rate-limits quickly).
        if (host == "tiktok.com" || host.endsWith(".tiktok.com")) {
            tiktok(url, proxy)?.let { return it }
        }
        OEMBED.firstOrNull { (hosts, _, _) -> hosts.any { host == it || host.endsWith(".$it") } }?.let { (_, api, video) ->
            oembed(api + java.net.URLEncoder.encode(url, "UTF-8"), url, proxy, host)?.let { return it.copy(video = video) }
        }

        fun page(ua: String): String? = open(url, proxy, "text/html,application/xhtml+xml", ua).use { conn ->
            if (conn.code !in 200..299) return null
            val charset = conn.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "UTF-8"
            conn.inputStream.use { input ->
                val bytes = readLimited(input, HTML_LIMIT, stopAt = "</head>".toByteArray())
                String(bytes, runCatching { charset(charset) }.getOrDefault(Charsets.UTF_8))
            }
        }
        var html = page(UA) ?: return null
        if (!html.contains("og:title", ignoreCase = true) && !html.contains("og:image", ignoreCase = true)) {
            // A phone got an empty shell: the page itself may name its oEmbed…
            Regex("""<link[^>]+type=["']application/json\+oembed["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.let { resolve(u, decodeEntities(it)) }
                ?.let { oembed(it, url, proxy, host) }?.let { return it }
            // …or its desktop page carries the tags.
            page(UA_DESKTOP)?.let { html = it }
        }
        fun meta(prop: String): String? {
            val p = Regex.escape(prop)
            val a = Regex("""<meta[^>]+(?:property|name)=["']$p["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            if (!a.isNullOrBlank()) return a
            return Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']$p["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
        val title = (meta("og:title") ?: meta("twitter:title")
            ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
            ?: "").let(::decodeEntities).trim().take(200)
        val desc = (meta("og:description") ?: meta("twitter:description") ?: meta("description") ?: "")
            .let(::decodeEntities).trim().take(300)
        val imageUrl = (meta("og:image:secure_url") ?: meta("og:image") ?: meta("twitter:image") ?: meta("twitter:image:src"))
            ?.let { resolve(u, decodeEntities(it.trim())) }
        val site = (meta("og:site_name")?.let(::decodeEntities) ?: host).take(80)
        val pic = imageUrl?.let { picture(it, proxy) }
        if (title.isBlank() && pic == null) return null
        val video = meta("og:type")?.startsWith("video", ignoreCase = true) == true ||
            !meta("og:video").isNullOrBlank() || !meta("og:video:url").isNullOrBlank() || meta("twitter:card") == "player"
        return LinkPreviewData(url, title, desc, pic?.inline.orEmpty(), site, fullPath = pic?.full.orEmpty(), video = video)
    }

    /** An oEmbed answer (JSON): title, author, cover. */
    private fun oembed(api: String, url: String, proxy: Proxy, site: String): LinkPreviewData? {
        val json = open(api, proxy, "application/json").use { conn ->
            if (conn.code !in 200..299) return null
            conn.inputStream.use { String(readLimited(it, 64 * 1024), Charsets.UTF_8) }
        }
        val o = org.json.JSONObject(json)
        val title = decodeEntities(o.optString("title")).trim().take(200)
        val author = o.optString("author_name").trim().take(120)
        val pic = o.optString("thumbnail_url").takeIf { it.startsWith("https://") }?.let { picture(it, proxy) }
        if (title.isBlank() && pic == null) return null
        return LinkPreviewData(url, title.ifBlank { author }, if (title.isBlank()) "" else author, pic?.inline.orEmpty(),
            o.optString("provider_name").takeIf { it.isNotBlank() } ?: site, fullPath = pic?.full.orEmpty())
    }

    /**
     * A TikTok video: short links (vm. / vt. / /t/) are followed to the video page,
     * whose `__UNIVERSAL_DATA_FOR_REHYDRATION__` JSON carries the caption, the
     * author and the cover.
     */
    private fun tiktok(url: String, proxy: Proxy): LinkPreviewData? {
        val conn = open(url, proxy, "text/html,application/xhtml+xml", UA_DESKTOP)
        val (finalUrl, html) = conn.use { c ->
            if (c.code !in 200..299) return null
            c.url to c.inputStream.use { String(readLimited(it, 2 * 1024 * 1024), Charsets.UTF_8) }
        }
        val item = runCatching {
            val json = Regex("""<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return@runCatching null
            org.json.JSONObject(json).getJSONObject("__DEFAULT_SCOPE__")
                .getJSONObject("webapp.video-detail").getJSONObject("itemInfo").getJSONObject("itemStruct")
        }.getOrNull()
        if (item != null) {
            val desc = item.optString("desc").trim()
            val author = item.optJSONObject("author")
            val name = author?.optString("nickname")?.trim().orEmpty()
                .ifBlank { author?.optString("uniqueId")?.let { "@$it" }.orEmpty() }
            val video = item.optJSONObject("video")
            val cover = listOf("cover", "originCover", "dynamicCover")
                .firstNotNullOfOrNull { k -> video?.optString(k)?.takeIf { it.startsWith("https://") } }
            val pic = cover?.let { picture(it, proxy) }
            if (desc.isNotBlank() || pic != null) {
                return LinkPreviewData(url, desc.ifBlank { name }.take(200), if (desc.isBlank()) "" else name,
                    pic?.inline.orEmpty(), "TikTok", fullPath = pic?.full.orEmpty(), video = true)
            }
        }
        return runCatching {
            oembed("https://www.tiktok.com/oembed?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8"), url, proxy, "TikTok")
        }.getOrNull()?.copy(video = true)
    }

    /** YouTube pages are huge and gated by consent walls; oEmbed is small and stable. */
    private fun youtube(url: String, proxy: Proxy): LinkPreviewData? {
        val api = "https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8")
        val json = open(api, proxy, "application/json").use { conn ->
            if (conn.code !in 200..299) return null
            conn.inputStream.use { String(readLimited(it, 64 * 1024), Charsets.UTF_8) }
        }
        val o = org.json.JSONObject(json)
        // The wide cover when the video has one (no letterbox bars), else oEmbed's.
        val pic = youtubeId(url)?.let { picture("https://i.ytimg.com/vi/$it/maxresdefault.jpg", proxy) }
            ?: o.optString("thumbnail_url").takeIf { it.startsWith("https://") }?.let { picture(it, proxy) }
        return LinkPreviewData(url, o.optString("title").take(200), o.optString("author_name").take(120), pic?.inline.orEmpty(), "YouTube",
            fullPath = pic?.full.orEmpty(), video = true)
    }

    private fun youtubeId(url: String): String? =
        Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.getOrNull(1)

    /** A preview picture: a small inline copy and the full one on disk ("" when no folder). */
    private class Pic(val inline: String, val full: String)

    /**
     * Fetch a picture, cut away black letterbox bars, keep it full (long side
     * ≤ [FULL_SIDE]) and make a small inline copy that always fits the message:
     * shrunk by AREA, so a tall cover (9:16) fits as surely as a wide one.
     */
    private fun picture(imageUrl: String, proxy: Proxy): Pic? {
        if (!imageUrl.startsWith("https://") && !imageUrl.startsWith("http://")) return null
        val bytes = open(imageUrl, proxy, "image/*").use { conn ->
            if (conn.code !in 200..299) return null
            conn.inputStream.use { readLimited(it, IMAGE_LIMIT) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FULL_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val img = trimBars(decoded)
        val full = outDir?.let { dir ->
            runCatching {
                val k = minOf(1f, FULL_SIDE.toFloat() / maxOf(img.width, img.height))
                val big = if (k < 1f) Bitmap.createScaledBitmap(img, (img.width * k).toInt(), (img.height * k).toInt(), true) else img
                val f = java.io.File(dir, "lp_${System.currentTimeMillis()}_${imageUrl.hashCode().toUInt()}.jpg")
                f.outputStream().use { big.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                f.absolutePath.takeIf { f.length() > 0 }
            }.getOrNull()
        }.orEmpty()
        for (side in intArrayOf(360, 280, 220, 160, 120, 90)) {
            val k = minOf(1f, side.toFloat() / maxOf(img.width, img.height))
            val small = if (k < 1f) Bitmap.createScaledBitmap(img, (img.width * k).toInt().coerceAtLeast(1), (img.height * k).toInt().coerceAtLeast(1), true) else img
            for (q in intArrayOf(62, 48, 34)) {
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= THUMB_MAX_BYTES) {
                    return Pic("data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP), full)
                }
            }
        }
        return null
    }

    /** Cut black bars (letterbox / pillarbox) off the edges of [b]. */
    private fun trimBars(b: Bitmap): Bitmap {
        fun dark(x: Int, y: Int): Boolean {
            val c = b.getPixel(x, y)
            return ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) < 3 * 22
        }
        val stepX = maxOf(1, b.width / 40)
        val stepY = maxOf(1, b.height / 40)
        fun rowDark(y: Int) = (0 until b.width step stepX).all { dark(it, y) }
        fun colDark(x: Int, y0: Int, y1: Int) = (y0 until y1 step stepY).all { dark(x, it) }
        var top = 0
        while (top < b.height / 3 && rowDark(top)) top++
        var bottom = b.height - 1
        while (bottom > b.height * 2 / 3 && rowDark(bottom)) bottom--
        var left = 0
        while (left < b.width / 3 && colDark(left, top, bottom)) left++
        var right = b.width - 1
        while (right > b.width * 2 / 3 && colDark(right, top, bottom)) right--
        val w = right - left + 1
        val h = bottom - top + 1
        // Only real bars (a few percent at least), never a dark photo eaten away.
        val cut = top > b.height / 25 || (b.height - 1 - bottom) > b.height / 25 || left > b.width / 25 || (b.width - 1 - right) > b.width / 25
        return if (cut && w > b.width / 3 && h > b.height / 3) Bitmap.createBitmap(b, left, top, w, h) else b
    }

    private fun readLimited(input: java.io.InputStream, limit: Int, stopAt: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (stopAt != null && out.size() > stopAt.size &&
                String(out.toByteArray(), Charsets.ISO_8859_1).contains(String(stopAt, Charsets.ISO_8859_1), ignoreCase = true)
            ) break
        }
        return out.toByteArray()
    }

    private fun resolve(base: URL, ref: String): String? =
        runCatching { URL(base, ref).toString() }.getOrNull()

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }

    /** Decode an inline preview image (data URI) — never a network URL. */
    /** The inline image's pixel size from its header only (no pixel decode). */
    fun inlineSize(image: String): Pair<Int, Int>? {
        if (!image.startsWith("data:image/")) return null
        val b64 = image.substringAfter(",", "")
        return runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }.getOrNull()
    }

    fun decodeInline(image: String): android.graphics.Bitmap? {
        if (!image.startsWith("data:image/")) return null
        val b64 = image.substringAfter(",", "")
        return runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
