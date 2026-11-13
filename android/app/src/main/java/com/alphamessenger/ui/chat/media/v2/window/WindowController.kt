package com.alphamessenger.ui.chat.media.v2.window

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * The single owner of the viewer window. No other v2 file knows the words
 * `Window`, `Dialog`, `FLAG_SECURE` or `WindowInsetsController`.
 *
 * The controller draws and moves nothing and never touches bar COLORS: color
 * stays with the app theme. Letting several owners paint the bars leads to
 * late repaints after close and hundreds of redundant window writes per open.
 * Here the window is only configured: screenshot protection, insets and icon
 * appearance.
 *
 * Whatever the controller changes is restored on detach, exactly as received.
 */
@Stable
class WindowController internal constructor(val state: WindowState = WindowState()) {

    private var window: Window? = null
    private var view: View? = null

    private var savedLightStatusIcons: Boolean? = null
    private var savedStatusBarColor: Int? = null
    private var savedNavigationBarColor: Int? = null
    private var savedNavigationBarContrast: Boolean? = null
    private var savedLightNavIcons: Boolean? = null

    var onAttached: () -> Unit = {}
    var onDetached: () -> Unit = {}

    fun attach(window: Window, view: View) {
        if (this.window === window) return
        this.window = window
        this.view = view
        val insets = WindowCompat.getInsetsController(window, view)
        savedLightStatusIcons = insets.isAppearanceLightStatusBars
        savedLightNavIcons = insets.isAppearanceLightNavigationBars

        // Why the window was app-area-sized instead of display-sized: Compose
        // sets window dimensions only when the default width is requested
        // (WRAP_CONTENT). With a non-default width the window size is never
        // set, so it stays wrapped around content measured within the available
        // area — i.e. without the system bars.
        //
        // So tell the window EXPLICITLY to take everything, skip fitting the
        // frame around the bars, and extend under the cutout.
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )

        // WITHOUT this flag the system fills the system-bar zones with OPAQUE
        // black over the content and any configured bar colors have no effect:
        // the viewer background dims while two black stripes stay until the
        // window goes away. An Activity gets this flag from its theme; a Compose
        // dialog does not.
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        state.decorFits = false
        state.attached = true
        state.attachedToWindow = view.isAttachedToWindow
        onAttached()
    }

    /** Give the window back: restore the settings first, then drop the reference. */
    fun detach() {
        restoreChat()
        disableSecure()
        window = null
        view = null
        state.attached = false
        state.attachedToWindow = false
        onDetached()
    }

    /** Content must not appear in screenshots or the recents list. */
    fun enableSecure() {
        val w = window ?: return
        w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        state.secure = true
    }

    fun disableSecure() {
        val w = window ?: return
        if (!state.secure) return
        w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        state.secure = false
    }

    /**
     * The background under the frame is dark, so system-bar icons are light.
     *
     * No window insets here: content extends under the bars from the window's
     * birth, not toggled on the fly.
     */
    fun applyFullscreen() {
        val w = window ?: return
        val v = view ?: return
        if (state.fullscreen) return

        // The system-bar stripes are painted by the window ITSELF, over the
        // content. While opaque they do not take part in the background fade:
        // the viewer dims while two black stripes hold until close and vanish
        // separately. Make them transparent so our background shows through and
        // the whole screen dims in one color.
        savedStatusBarColor = w.statusBarColor
        savedNavigationBarColor = w.navigationBarColor
        w.statusBarColor = android.graphics.Color.TRANSPARENT
        w.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The system lays a semi-transparent scrim under the navigation
            // bar by default — another stripe living outside our background.
            savedNavigationBarContrast = w.isNavigationBarContrastEnforced
            w.isNavigationBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(w, v).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        state.fullscreen = true
    }

    /** Restore what the window had before the viewer. */
    fun restoreChat() {
        val w = window ?: return
        val v = view ?: return
        if (!state.fullscreen) return
        savedStatusBarColor?.let { w.statusBarColor = it }
        savedNavigationBarColor?.let { w.navigationBarColor = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savedNavigationBarContrast?.let { w.isNavigationBarContrastEnforced = it }
        }
        WindowCompat.getInsetsController(w, v).apply {
            savedLightStatusIcons?.let { isAppearanceLightStatusBars = it }
            savedLightNavIcons?.let { isAppearanceLightNavigationBars = it }
        }
        state.fullscreen = false
    }
}

@Composable
fun rememberWindowController(): WindowController = remember { WindowController() }

/**
 * Attach to the window for the content's lifetime and detach on leave. The
 * controller finds the window itself — users need not know anything about the
 * window machinery.
 */
@Composable
fun WindowController.attach(secure: Boolean, fullscreen: Boolean = true) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window

    DisposableEffect(window, view) {
        if (window != null) attach(window, view)
        onDispose { detach() }
    }
    DisposableEffect(window, fullscreen) {
        if (window != null && fullscreen) applyFullscreen()
        onDispose { }
    }
    DisposableEffect(window, secure) {
        if (window != null) {
            if (secure) enableSecure() else disableSecure()
        }
        onDispose { }
    }
}

/**
 * The viewer window: the single place in the project where the dialog is
 * created — everything else just draws content and does not know what it
 * lives in.
 *
 * The window fills the whole screen immediately: content starts at 0,0 and
 * reaches the bottom edge, otherwise the first layout would run in a shrunken
 * area and its bounds would show as stripes top and bottom.
 */
@Composable
fun ViewerWindow(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // IMPORTANT: exactly true. With false the dialog content is
            // measured against app metrics (without system bars) and Compose
            // forces the window to the measured content size on EVERY layout
            // pass, overwriting any size we set. Keeping the width "default"
            // disables that forced shrinking; the full-screen window size is
            // set by attach().
            usePlatformDefaultWidth = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect {
            dialogWindow?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        content()
    }
}
