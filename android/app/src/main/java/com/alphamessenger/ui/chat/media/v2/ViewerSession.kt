package com.alphamessenger.ui.chat.media.v2

import androidx.compose.ui.geometry.Size
import com.alphamessenger.ui.chat.media.v2.hero.HeroBitmapProvider
import com.alphamessenger.ui.chat.media.v2.hero.HeroGeometry
import com.alphamessenger.ui.chat.media.v2.pager.PagerMediaSource
import com.alphamessenger.ui.chat.media.v2.pager.PagerPages

/**
 * One viewer opening: what we show and where everything else comes from.
 *
 * The session is the only boundary to the outside world. The viewer never
 * looks up sources itself: bytes, transition frames, geometry and bubble
 * dimming arrive as ready implementations, so no layer knows about storage
 * or the chat.
 */
data class ViewerSession(
    val pages: PagerPages,
    val media: PagerMediaSource = PagerMediaSource.Empty,
    val frames: HeroBitmapProvider = HeroBitmapProvider.None,
    val geometry: ViewerGeometryProvider = ViewerGeometryProvider.None,
    val placeholder: ViewerPlaceholder = ViewerPlaceholder.None,
)

/**
 * Where the frame flies from and to. The caller computes the rects — it alone
 * knows where the source lies on screen. It also keeps the viewer from
 * reaching the source registry directly.
 */
fun interface ViewerGeometryProvider {

    /** null — no geometry, no transition. */
    fun geometryFor(mediaId: String, viewport: Size): HeroGeometry?

    companion object {
        val None = ViewerGeometryProvider { _, _ -> null }
    }
}

/**
 * Dims the source while it is being viewed: `mediaId` — dim, `null` — restore.
 * How that happens is unknown to the viewer.
 */
fun interface ViewerPlaceholder {

    fun setActive(mediaId: String?)

    companion object {
        val None = ViewerPlaceholder { }
    }
}
