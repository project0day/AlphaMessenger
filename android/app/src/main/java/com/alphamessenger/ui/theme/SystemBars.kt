package com.alphamessenger.ui.theme

import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

/**
 * The only place in the project that writes to the Window. Screens only
 * declare the desired state; windows register here and apply it themselves.
 */
object SystemBars {
    /**
     * Navigation-bar colour requests, newest on top. A screen adds its own and,
     * when it leaves, removes ONLY its own — the one beneath applies again. (A
     * single shared value let a closing screen wipe the colour of the screen it
     * returned to: the chat's see-through bar turned solid after the camera.)
     */
    private val navRequests = androidx.compose.runtime.mutableStateListOf<Pair<Any, Color>>()
    val navColor: Color? get() = navRequests.lastOrNull()?.second

    fun requestNav(owner: Any, color: Color) {
        navRequests.removeAll { it.first === owner }
        navRequests.add(owner to color)
    }

    fun releaseNav(owner: Any) {
        navRequests.removeAll { it.first === owner }
    }

    var statusColor by mutableStateOf<Color?>(null)
    var lightIcons by mutableStateOf<Boolean?>(null)

    /**
     * A fullscreen layer over the app: the bars go black. DISCRETELY, not
     * per-frame — otherwise the window is repainted on every animation frame.
     */
    var fullscreenOverlay by mutableStateOf(false)

    private val windows = LinkedHashSet<Window>()
    private var lastNav: Int? = null
    private var lastStatus: Int? = null
    private var lastLight: Boolean? = null
    // The system scrim behind the bars: while it is enabled, a transparent bar
    // color makes the system draw a dark strip under the bars — it belongs to
    // none of our layers and does not dim with the viewer background. We
    // remember the original state and restore it once transparency is no
    // longer needed.
    private var savedNavContrast: Boolean? = null
    private var savedStatusContrast: Boolean? = null

    /** Count of actual writes — to check "1 on open, 1 on close". */
    var writes = 0
        private set

    fun attach(window: Window) {
        windows += window
        // The "already applied" cache belongs to the PREVIOUS window. A new window
        // (a second Activity instance, recreation after a theme change) has not
        // been painted yet — without a reset, apply() would hit the cache and the
        // bars would stay transparent.
        lastNav = null; lastStatus = null; lastLight = null
    }

    fun detach(window: Window) {
        windows -= window
        lastNav = null; lastStatus = null; lastLight = null
    }

    fun reset() {
        navRequests.clear()
        statusColor = null
        lightIcons = null
        fullscreenOverlay = false
    }

    /** Called by the theme only; writes to windows only on a real change. */
    fun apply(view: android.view.View, baseNav: Color, baseStatus: Color, baseLight: Boolean) {
        val over = fullscreenOverlay
        val nav = (if (over) Color.Black else (navColor ?: baseNav)).toArgb()
        val status = (if (over) Color.Black else (statusColor ?: baseStatus)).toArgb()
        val light = if (over) false else (lightIcons ?: baseLight)
        if (nav == lastNav && status == lastStatus && light == lastLight) return
        lastNav = nav; lastStatus = status; lastLight = light
        writes++
        windows.forEach { w ->
            w.statusBarColor = status
            w.navigationBarColor = nav
            // A transparent bar + enabled enforcement = the dark system scrim over our
            // background. We turn it off for exactly as long as the bar is transparent
            // and restore the original value afterwards.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val transparent = nav == android.graphics.Color.TRANSPARENT
                if (transparent) {
                    if (savedNavContrast == null) {
                        savedNavContrast = w.isNavigationBarContrastEnforced
                        savedStatusContrast = w.isStatusBarContrastEnforced
                    }
                    w.isNavigationBarContrastEnforced = false
                    w.isStatusBarContrastEnforced = false
                } else {
                    savedNavContrast?.let { w.isNavigationBarContrastEnforced = it }
                    savedStatusContrast?.let { w.isStatusBarContrastEnforced = it }
                    savedNavContrast = null
                    savedStatusContrast = null
                }
            }
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
}
