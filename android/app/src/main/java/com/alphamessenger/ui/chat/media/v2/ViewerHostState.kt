package com.alphamessenger.ui.chat.media.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The viewer's three phases. Only subsystem events move it between them. */
enum class ViewerPhase { OPENING, VIEWING, CLOSING }

/**
 * Coordinator state: what is currently visible and which phase the viewer is in.
 *
 * No scale, no pan, no transition fraction here — that kinematics belongs to
 * the gesture state and the transition state; duplicating it would create a
 * second source of truth.
 */
@Stable
class ViewerHostState {

    var phase by mutableStateOf(ViewerPhase.OPENING)
        internal set

    /** The user has not hidden the chrome. Final visibility: see [chromeShown]. */
    var chromeVisible by mutableStateOf(true)
        internal set

    /** Pages hidden while the frame flies. Only the coordinator toggles this. */
    var pagerVisible by mutableStateOf(false)
        internal set

    /** While the menu is open it owns input: gestures off, chrome never fades. */
    var menuOpen by mutableStateOf(false)
        internal set

    var captionExpanded by mutableStateOf(false)
        internal set

    /** The reply line is open: the bottom bar shows only it, above the keyboard. */
    var composing by mutableStateOf(false)
        internal set

    /** Reply text survives closing the line (a tap on the video) until sent. */
    var replyDraft by mutableStateOf("")
        internal set

    var reactionSheetOpen by mutableStateOf(false)
        internal set

    /**
     * The frame is being dragged away. Chrome hides for the duration: its
     * background (top gradient + bottom plate) covers exactly the system-bar
     * areas and does NOT take part in the backdrop fade — left in place it
     * would read as two stationary black strips.
     */
    var dragging by mutableStateOf(false)
        internal set

    // --- Playback: values for the bottom bar ---
    var fillScreen by mutableStateOf(false)
        internal set
    var muted by mutableStateOf(false)
        internal set
    var speed by mutableStateOf(1f)
        internal set
    var positionMs by mutableStateOf(0L)
        internal set
    var durationMs by mutableStateOf(0L)
        internal set

    /**
     * Close requested from outside (system back). The viewer then closes via
     * its normal path — the frame flight, not an instant window removal.
     */
    var closeRequested by mutableStateOf(false)
        internal set

    /** Back first closes the reply line, then the viewer. */
    fun close() {
        when {
            reactionSheetOpen -> reactionSheetOpen = false
            composing -> composing = false
            else -> closeRequested = true
        }
    }

    /** Seek hint: true — forward, false — back, null — none. */
    var seekHint by mutableStateOf<Boolean?>(null)
        internal set

    /** The single visibility rule for all chrome. */
    val chromeShown: Boolean
        get() = (chromeVisible || menuOpen || composing) && phase == ViewerPhase.VIEWING && !dragging

    /** Input is accepted only when the menu has not claimed it and we are not closing. */
    val gesturesEnabled: Boolean
        get() = !menuOpen && !reactionSheetOpen && phase == ViewerPhase.VIEWING

    fun toggleChrome() { if (!menuOpen && !composing) chromeVisible = !chromeVisible }
    fun setComposing(value: Boolean) { composing = value; if (value) chromeVisible = true }
    fun setReplyDraft(value: String) { replyDraft = value }
    fun setReactionSheet(open: Boolean) { reactionSheetOpen = open }
    internal fun onDragStarted() { dragging = true }
    internal fun onDragEnded() { dragging = false }
    fun openMenu() { menuOpen = true }
    fun closeMenu() { menuOpen = false }
    fun toggleCaption() { captionExpanded = !captionExpanded }
    fun toggleFillScreen() { fillScreen = !fillScreen }
    fun toggleMuted() { muted = !muted }
    fun setSpeed(value: Float) { speed = value }

    internal fun showSeekHint(forward: Boolean) { seekHint = forward }
    internal fun clearSeekHint() { seekHint = null }
    internal fun onPageChanged() { captionExpanded = false }
    internal fun setPlaybackTime(position: Long, duration: Long) {
        positionMs = position
        durationMs = duration
    }
}

@Composable
fun rememberViewerHostState(key: Any? = Unit): ViewerHostState =
    remember(key) { ViewerHostState() }
