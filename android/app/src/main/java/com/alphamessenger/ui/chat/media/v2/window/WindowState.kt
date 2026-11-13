package com.alphamessenger.ui.chat.media.v2.window

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Current viewer-window status. A data holder with no logic.
 *
 * [attached] — the controller has the window and manages it.
 * [attachedToWindow] — the view is actually alive on screen. These must not
 * be confused: the window can be handed over before the view attaches, and
 * settings writes would go nowhere.
 */
@Stable
class WindowState {

    var attached by mutableStateOf(false)
        internal set

    var attachedToWindow by mutableStateOf(false)
        internal set

    var fullscreen by mutableStateOf(false)
        internal set

    var secure by mutableStateOf(false)
        internal set

    /** Content insets away from the system bars (true) or extends under them (false). */
    var decorFits by mutableStateOf(true)
        internal set
}
