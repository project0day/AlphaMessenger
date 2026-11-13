package com.alphamessenger.ui.chat.media.v2.hero

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

/** What the transition is doing now. While viewing — [IDLE], and the frame does not exist. */
enum class HeroPhase { IDLE, ENTERING, EXITING }

/**
 * Everything needed to draw the flying frame, and nothing more.
 *
 * The frame is stored HERE and only here: once the flight is over the
 * reference is dropped — the transition never holds the image throughout
 * the viewing session.
 */
@Stable
class HeroState {

    var phase by mutableStateOf(HeroPhase.IDLE)
        internal set

    /** Transition fraction: 0 — at the source, 1 — on screen. */
    var progress by mutableStateOf(0f)
        internal set

    var bitmap by mutableStateOf<ImageBitmap?>(null)
        internal set

    var geometry by mutableStateOf<HeroGeometry?>(null)
        internal set

    /** Whose frame is flying. The same id dims the source for the flight. */
    var mediaId by mutableStateOf<String?>(null)
        internal set

    /** Transition running right now — the viewer content must be hidden. */
    val isRunning: Boolean
        get() = phase != HeroPhase.IDLE

    /** Something to draw: without a frame and geometry there is no transition. */
    val isEnabled: Boolean
        get() = bitmap != null && geometry != null

    internal fun begin(mediaId: String, bitmap: ImageBitmap?, geometry: HeroGeometry?, phase: HeroPhase, progress: Float) {
        this.mediaId = mediaId
        this.bitmap = bitmap
        this.geometry = geometry
        this.phase = phase
        this.progress = progress
    }

    /** Flight over: release the frame, the layer disappears. */
    internal fun clear() {
        phase = HeroPhase.IDLE
        progress = 0f
        bitmap = null
        geometry = null
        mediaId = null
    }
}
