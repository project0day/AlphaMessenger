package com.alphamessenger.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Auto-download policy.
 *
 * Decides whether an incoming media file should be fetched automatically when
 * its bubble appears, based on (a) the current network class, (b) the media
 * type, and (c) a per-network size cap. Settings are device-local and read
 * synchronously (SharedPreferences) because the check sits in a UI hot path.
 *
 * A tap on a not-auto-downloaded item always force-downloads it, regardless of
 * this policy.
 */
object MediaAutoDownload {

    enum class Net { WIFI, MOBILE, ROAMING, NONE }

    const val PREFS = "auto_download"

    /** Media categories shown in the settings UI. */
    val TYPES = listOf("photo", "video", "gif", "round", "music", "voice", "file")

    // --- Defaults (used until the user changes anything) ---
 // Documents/files are MANUAL by default — a tap downloads
    // them. This keeps behaviour consistent across file types (a small JPG sent
    // "as file" doesn't silently auto-download while a CSV of unknown size
    // doesn't either). Photos/video/music/voice keep sensible auto defaults.
    // Wi-Fi: media auto, files manual, no cap. Mobile: photo+voice, 10 MB.
    // GIFs and video messages are small and meant to be seen at once: on by
    // default on Wi-Fi and mobile alike (within the size cap).
    private fun defaultEnabled(net: Net, type: String): Boolean = when (net) {
        Net.WIFI -> type != "file"
        Net.MOBILE -> type == "photo" || type == "voice" || type == "gif" || type == "round"
        else -> false
    }

    /** Default size cap in MB (0 = unlimited). */
    private fun defaultMaxMb(net: Net): Int = when (net) {
        Net.WIFI -> 0
        Net.MOBILE -> 10
        else -> 1
    }

    fun prefix(net: Net): String? = when (net) {
        Net.WIFI -> "wifi"; Net.MOBILE -> "mobile"; Net.ROAMING -> "roaming"; Net.NONE -> null
    }

    // --- Reads / writes (for the settings screen) ---

    fun isEnabled(ctx: Context, net: Net, type: String): Boolean {
        val p = prefix(net) ?: return false
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("${p}_$type", defaultEnabled(net, type))
    }

    fun setEnabled(ctx: Context, net: Net, type: String, value: Boolean) {
        val p = prefix(net) ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("${p}_$type", value).apply()
    }

    fun maxMb(ctx: Context, net: Net): Int {
        val p = prefix(net) ?: return 0
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("${p}_max", defaultMaxMb(net))
    }

    fun setMaxMb(ctx: Context, net: Net, mb: Int) {
        val p = prefix(net) ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("${p}_max", mb).apply()
    }

    /** Back to the defaults (every switch and limit). */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // --- The actual gate ---

    fun currentNet(ctx: Context): Net {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Net.NONE
        val active = cm.activeNetwork ?: return Net.NONE
        val caps = cm.getNetworkCapabilities(active) ?: return Net.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Net.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val notRoaming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) else true
                if (notRoaming) Net.MOBILE else Net.ROAMING
            }
            else -> Net.NONE
        }
    }

    /** Map a stored media_type (+ filename) to a settings category. Voice notes
     *  (audio named "voice…") are separate from music (other audio). */
    fun categoryOf(mediaType: String?, filename: String?): String = when {
        mediaType == "image" -> "photo"
        mediaType == "video" && filename?.startsWith("gif_") == true -> "gif"
        mediaType == "video" && filename?.startsWith("round_") == true -> "round"
        mediaType == "video" -> "video"
        mediaType == "audio" -> if (filename?.startsWith("voice") == true) "voice" else "music"
        else -> "file"
    }

    /** Should this media auto-download right now? */
    fun shouldAuto(ctx: Context, mediaType: String?, filename: String?, sizeBytes: Long): Boolean {
        val net = currentNet(ctx)
        val type = categoryOf(mediaType, filename)
        val enabled = net != Net.NONE && isEnabled(ctx, net, type)
        val cap = maxMb(ctx, net)
        val allow = when {
            !enabled -> false
            cap <= 0 -> true // unlimited
            // Unknown size WITH a cap set → be CONSERVATIVE and do NOT auto-fetch
            // (returning true here would silently bypass the size cap). The user
            // taps to download instead.
            sizeBytes <= 0 -> false
            else -> sizeBytes <= cap * 1024L * 1024L
        }
        return allow
    }
}
