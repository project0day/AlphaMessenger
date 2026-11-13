package com.alphamessenger.ui.video

import android.util.Rational

/**
 * A link whose site has an official embed player that plays inside the app. Sites
 * whose player refuses to run in an app's web view are not listed: their links open outside.
 */
internal class VideoSource private constructor(
    val embedUrl: String,
    val referer: String,
    val ratio: Rational,
    /** Page paths that stay inside the player; any other page opens outside the app. */
    val playerPath: String,
) {
    companion object {
        private val YOUTUBE_ID = Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""")

        fun of(url: String): VideoSource? {
            val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
            return when {
                host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ->
                    YOUTUBE_ID.find(url)?.groupValues?.get(1)?.let { id ->
                        VideoSource(
                            "https://www.youtube-nocookie.com/embed/$id?autoplay=1&playsinline=1&rel=0",
                            "https://www.youtube-nocookie.com/",
                            if (url.contains("/shorts/")) Rational(9, 16) else Rational(16, 9),
                            "/embed/",
                        )
                    }
                else -> null
            }
        }
    }
}
