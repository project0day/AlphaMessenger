package com.alphamessenger.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/**
 * One owner of the activity window's screen protection. Screens ask for it
 * and give it back; the window stays protected while anyone holds it or the
 * user chose to protect the whole app. A screen closing never lifts what
 * another one still needs.
 */
object ScreenSecurity {
    private const val PREFS = "screen_security"
    private const val KEY_ALWAYS = "always"

    private val holds = java.util.WeakHashMap<Window, Int>()

    /** The whole app protected: no screenshots, no content in Recents. */
    var always by mutableStateOf(false)
        private set

    fun load(context: Context) {
        always = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALWAYS, false)
    }

    fun setAlways(context: Context, on: Boolean) {
        always = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ALWAYS, on).apply()
        context.findActivity()?.let { apply(it) }
    }

    @Synchronized
    fun acquire(window: Window) {
        holds[window] = (holds[window] ?: 0) + 1
        update(window)
    }

    @Synchronized
    fun release(window: Window) {
        val n = (holds[window] ?: 0) - 1
        if (n > 0) holds[window] = n else holds.remove(window)
        update(window)
    }

    /** For the activity at start: the user's choice, Recents included. */
    fun apply(activity: Activity) {
        update(activity.window)
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!always)
    }

    @Synchronized
    private fun update(window: Window) {
        if (always || (holds[window] ?: 0) > 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** This screen's content may not be captured while it is shown (when [active]). */
@Composable
fun SecureWindow(active: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val window = view.context.findActivity()?.window
        if (active && window != null) ScreenSecurity.acquire(window)
        onDispose { if (active && window != null) ScreenSecurity.release(window) }
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
