package com.alphamessenger.util

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Trusted wall-clock time, immune to the user changing the device date/time.
 *
 * We query an NTP server once (and periodically), then anchor it to
 * [SystemClock.elapsedRealtime] — a MONOTONIC counter since boot that the user
 * cannot change. From then on `now = ntpAtSync + (elapsedRealtime − elapsedAtSync)`,
 * so moving the wall clock backward/forward has no effect on our time.
 *
 * This is what bans / mutes / temporary chat-close compare against, so a member
 * can't escape a timed restriction by rewinding the clock. If we never managed
 * to sync (offline), we fall back to `System.currentTimeMillis()` — no worse
 * than before.
 *
 * Note: `elapsedRealtime` resets on reboot, so we re-sync on app start.
 */
object TrustedTime {
    private const val NTP_PORT = 123
    private const val NTP_PACKET_SIZE = 48
    private const val NTP_MODE_CLIENT = 3
    private const val NTP_VERSION = 3
    private const val OFFSET_1900_TO_1970 = 2_208_988_800L

    @Volatile private var baseNtpMs = 0L
    @Volatile private var baseElapsedMs = 0L
    @Volatile var isSynced = false
        private set

    /** Trusted epoch-millis (network-anchored if synced, else device clock). */
    fun nowMillis(): Long =
        if (isSynced) baseNtpMs + (SystemClock.elapsedRealtime() - baseElapsedMs)
        else System.currentTimeMillis()

    /** Trusted epoch-seconds. */
    fun nowSeconds(): Long = nowMillis() / 1000L

    /**
     * Query the time and update the monotonic base. Returns true on success.
     * Call off the main thread. In private mode NTP is never used: it is UDP,
     * which cannot go through Tor and would show the real address.
     */
    fun sync(
        viaTor: Boolean,
        hosts: List<String> = listOf("time.google.com", "pool.ntp.org", "time.cloudflare.com"),
    ): Boolean {
        if (viaTor) return syncOverTor()
        for (h in hosts) {
            val r = runCatching { queryOne(h) }.getOrNull()
            if (r != null) {
                baseNtpMs = r.first
                baseElapsedMs = r.second
                isSynced = true
                return true
            }
        }
        return false
    }

    /** Two independent HTTPS sources through Tor must agree; one alone could lie. */
    private val TOR_SOURCES = listOf("https://check.torproject.org/", "https://www.wikipedia.org/")
    private const val AGREE_MS = 5_000L

    private fun syncOverTor(): Boolean {
        val samples = TOR_SOURCES.mapNotNull { runCatching { httpDate(it) }.getOrNull() }
        if (samples.size < 2) return false
        // Both readings brought to the same monotonic instant before comparing.
        val (a, b) = samples
        val bAtA = b.first + (a.second - b.second)
        if (kotlin.math.abs(a.first - bAtA) > AGREE_MS) return false
        baseNtpMs = a.first
        baseElapsedMs = a.second
        isSynced = true
        return true
    }

    /** The server's `Date` (second precision) anchored to the round trip's midpoint. */
    private fun httpDate(url: String): Pair<Long, Long>? {
        val conn = java.net.URL(url).openConnection(com.alphamessenger.data.EmbeddedTor.proxy()) as java.net.HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = false
        try {
            val t0 = SystemClock.elapsedRealtime()
            conn.responseCode
            val t3 = SystemClock.elapsedRealtime()
            val date = conn.date.takeIf { it > 0L } ?: return null
            return (date + 500L) to (t0 + (t3 - t0) / 2)
        } finally {
            conn.disconnect()
        }
    }

    private fun queryOne(host: String): Pair<Long, Long> {
        DatagramSocket().use { socket ->
            socket.soTimeout = 4000
            val addr = InetAddress.getByName(host)
            val buf = ByteArray(NTP_PACKET_SIZE)
            buf[0] = (NTP_MODE_CLIENT or (NTP_VERSION shl 3)).toByte()
            val t0 = SystemClock.elapsedRealtime()
            socket.send(DatagramPacket(buf, buf.size, addr, NTP_PORT))
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val t3 = SystemClock.elapsedRealtime()
            // Transmit timestamp is at byte offset 40 (seconds) + 44 (fraction).
            val seconds = read32(buf, 40)
            val fraction = read32(buf, 44)
            val ntpMs = (seconds - OFFSET_1900_TO_1970) * 1000L + (fraction * 1000L / 0x1_0000_0000L)
            // Anchor the server instant to the midpoint of the round trip.
            val elapsedAtServer = t0 + (t3 - t0) / 2
            return ntpMs to elapsedAtServer
        }
    }

    private fun read32(buf: ByteArray, offset: Int): Long {
        val b0 = buf[offset].toLong() and 0xff
        val b1 = buf[offset + 1].toLong() and 0xff
        val b2 = buf[offset + 2].toLong() and 0xff
        val b3 = buf[offset + 3].toLong() and 0xff
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }
}
