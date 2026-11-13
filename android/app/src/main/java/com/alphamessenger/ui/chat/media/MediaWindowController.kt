package com.alphamessenger.ui.chat.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.alphamessenger.ui.theme.SystemBars

/**
 * The dialog window registers itself with [SystemBars] — the viewer does not
 * write bar colors itself. Only window-own properties remain here (dim,
 * animations, system insets) and FLAG_SECURE.
 */
@Composable
internal fun MediaWindowEffect(secure: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(Unit) {
        SystemBars.fullscreenOverlay = true
        onDispose {
            SystemBars.fullscreenOverlay = false
        }
    }

    DisposableEffect(view, secure) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val activityWindow = context.findActivity()?.window
        dialogWindow?.apply {
            setDimAmount(0f)
            setWindowAnimations(0)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            SystemBars.attach(this)
        }
        if (secure) {
            dialogWindow?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            activityWindow?.let { com.alphamessenger.ui.components.ScreenSecurity.acquire(it) }
        }
        onDispose {
            dialogWindow?.let { SystemBars.detach(it) }
            SystemBars.reset()
            if (secure) activityWindow?.let { com.alphamessenger.ui.components.ScreenSecurity.release(it) }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
