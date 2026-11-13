package com.alphamessenger.ui.chat.v2

import android.view.View
import com.alphamessenger.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/**
 * The keyboard's height as it slides: from where it was to where it will settle,
 * by the system animation's own fraction.
 *
 * The end height is known before the first frame (the end-state insets are applied
 * ahead of the animation), so the bar is placed by the slide's own progress and
 * never passes where the keyboard stops.
 */
@Stable
class KeyboardMotion {
    var heightPx by mutableIntStateOf(0)
        private set

    private var animating = false
    private var from = 0
    private var to = 0
    /** The open keyboard's height once it stood still. */
    private var settledPx = 0

    internal fun onInsets(imeBottom: Int) {
        if (animating) to = endHeight(imeBottom)
        else {
            heightPx = imeBottom
            if (imeBottom > 0) settledPx = imeBottom
        }
    }

    // Reopened quickly, the keyboard announces an end height taller than it is and
    // corrects it only after the slide, while the keyboard itself never grows. A real
    // size change arrives outside an animation and is taken as it comes.
    private fun endHeight(imeBottom: Int) =
        if (settledPx > 0 && imeBottom > settledPx) settledPx else imeBottom

    internal fun callback(view: View) = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            if (animation.isIme()) {
                animating = true
                from = heightPx
                to = heightPx
            }
        }

        override fun onStart(
            animation: WindowInsetsAnimationCompat,
            bounds: WindowInsetsAnimationCompat.BoundsCompat,
        ): WindowInsetsAnimationCompat.BoundsCompat {
            // The end state is normally applied before this; when it is not, read it here.
            if (animation.isIme()) ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { to = endHeight(it) }
            return bounds
        }

        override fun onProgress(
            insets: WindowInsetsCompat,
            runningAnimations: MutableList<WindowInsetsAnimationCompat>,
        ): WindowInsetsCompat {
            val ime = runningAnimations.firstOrNull { it.isIme() } ?: return insets
            heightPx = (from + (to - from) * ime.interpolatedFraction).roundToInt()
            return insets
        }

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            if (!animation.isIme()) return
            animating = false
            heightPx = to
            // A keyboard that really did change size reports it once the slide is over.
            view.post {
                if (animating) return@post
                ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
                    ?.let { onInsets(it) }
            }
        }
    }

    private fun WindowInsetsAnimationCompat.isIme() = typeMask and WindowInsetsCompat.Type.ime() != 0
}

/**
 * The window's one listener and animation callback, shared by every composer on screen.
 * A view holds only one of each, and a screen opened over a chat (scheduled messages)
 * binds before the chat underneath lets go: with one each, the leaving chat would clear
 * what the new screen had just set.
 */
private class KeyboardMotionHub(private val content: View) {
    val motions = mutableListOf<KeyboardMotion>()
    private val callbacks = HashMap<KeyboardMotion, WindowInsetsAnimationCompat.Callback>()

    fun add(motion: KeyboardMotion) {
        motions += motion
        callbacks[motion] = motion.callback(content)
        if (motions.size == 1) install()
        ViewCompat.requestApplyInsets(content)
    }

    fun remove(motion: KeyboardMotion) {
        motions -= motion
        callbacks.remove(motion)
        if (motions.isEmpty()) {
            ViewCompat.setOnApplyWindowInsetsListener(content, null)
            ViewCompat.setWindowInsetsAnimationCallback(content, null)
            content.setTag(R.id.keyboard_motion_hub, null)
        }
    }

    private fun install() {
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            motions.toList().forEach { it.onInsets(ime) }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(content, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                callbacks.values.toList().forEach { it.onPrepare(animation) }
            }
            override fun onStart(
                animation: WindowInsetsAnimationCompat,
                bounds: WindowInsetsAnimationCompat.BoundsCompat,
            ): WindowInsetsAnimationCompat.BoundsCompat {
                callbacks.values.toList().forEach { it.onStart(animation, bounds) }
                return bounds
            }
            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>,
            ): WindowInsetsCompat {
                callbacks.values.toList().forEach { it.onProgress(insets, runningAnimations) }
                return insets
            }
            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                callbacks.values.toList().forEach { it.onEnd(animation) }
            }
        })
    }

    companion object {
        fun of(content: View): KeyboardMotionHub =
            content.getTag(R.id.keyboard_motion_hub) as? KeyboardMotionHub
                ?: KeyboardMotionHub(content).also { content.setTag(R.id.keyboard_motion_hub, it) }
    }
}

/** Feeds [motion] from the window's content view for as long as the caller is composed. */
@Composable
fun BindKeyboardMotion(motion: KeyboardMotion) {
    val view = LocalView.current
    DisposableEffect(view, motion) {
        val content = view.rootView.findViewById<View>(android.R.id.content) ?: view
        val hub = KeyboardMotionHub.of(content)
        hub.add(motion)
        onDispose { hub.remove(motion) }
    }
}
