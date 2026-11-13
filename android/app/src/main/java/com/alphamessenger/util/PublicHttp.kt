package com.alphamessenger.util

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Addresses that come from other people (a pasted link, an emoji set's image
 * URLs) reach only the public internet over https: never this phone, the local
 * network or a reserved range — otherwise a link could make the phone probe the
 * LAN. Through Tor the exit resolves names and cannot reach our network, so
 * only the name itself is judged there.
 */
object PublicHttp {
    /** One response; closing it releases the connection. */
    class Response(
        val code: Int,
        val contentType: String?,
        /** Where the answer finally came from (after redirects). */
        val url: String,
        private val body: okhttp3.ResponseBody?,
    ) : java.io.Closeable {
        val inputStream: java.io.InputStream get() = body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
        override fun close() { body?.close() }
    }

    /**
     * GET [url], following at most [maxRedirects] redirects, each hop checked
     * like the first. A direct connection goes only to the addresses [PublicDns]
     * approved — the name is resolved once, so it cannot point elsewhere for the
     * connection than it did for the check.
     */
    fun get(
        url: String,
        proxy: Proxy,
        headers: Map<String, String>,
        connectMs: Long,
        readMs: Long,
        maxRedirects: Int = 3,
        /** Sent as a POST to the first address; redirects are not followed then. */
        postBody: okhttp3.RequestBody? = null,
    ): Response {
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .dns(PublicDns())
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .build()
        var target = URL(url)
        repeat(maxRedirects + 1) {
            requirePublicName(target)
            val request = Request.Builder().url(target).apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (postBody != null) post(postBody)
            }.build()
            val response = client.newCall(request).execute()
            val location = response.header("Location")
            if (postBody != null || response.code !in 300..399 || location == null) {
                return Response(response.code, response.header("Content-Type"), target.toString(), response.body)
            }
            response.close()
            target = URL(target, location)
        }
        throw java.io.IOException("too many redirects")
    }

    /** The checks that need no DNS: https, no credentials, no local name, no private literal. */
    fun requirePublicName(u: URL) {
        if (u.protocol != "https" || !u.userInfo.isNullOrEmpty()) throw java.io.IOException("not a public https address")
        val host = u.host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (host.isEmpty() || host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) {
            throw java.io.IOException("local name")
        }
        if ((host.contains(':') || host.matches(IPV4)) && !isPublic(InetAddress.getByName(host))) {
            throw java.io.IOException("not a public address")
        }
    }

    /** Full check for callers on plain `URLConnection` (names resolved here). */
    fun requirePublic(u: URL, proxy: Proxy) {
        requirePublicName(u)
        if (proxy.type() == Proxy.Type.DIRECT) PublicDns().lookup(u.host)
    }

    /** Resolves a name and refuses it when any of its addresses is not public. */
    internal class PublicDns(private val resolve: (String) -> List<InetAddress> = Dns.SYSTEM::lookup) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = resolve(hostname)
            if (all.isEmpty() || all.any { !isPublic(it) }) throw java.net.UnknownHostException("not a public address: $hostname")
            return all
        }
    }

    internal fun isPublic(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) {
            return false
        }
        val b = a.address.map { it.toInt() and 0xFF }
        return if (b.size == 4) {
            !(b[0] == 0 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 198 && b[1] in 18..19) ||
                (b[0] == 192 && b[1] == 0 && b[2] == 0) || b[0] >= 240)
        } else {
            (b[0] and 0xFE) != 0xFC // unique-local fc00::/7
        }
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".internal", ".home.arpa")
}
