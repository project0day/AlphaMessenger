package com.alphamessenger.ui.sticker

import android.content.Context
import android.graphics.Canvas

/**
 * A vector animation view that survives a broken animation: some files ask the
 * renderer for shapes it refuses (a gradient of zero radius) and it throws while
 * drawing. Here such a sticker goes blank and says so; the app keeps running.
 */
internal class SafeLottieView(context: Context) : com.airbnb.lottie.LottieAnimationView(context) {
    var onBroken: (() -> Unit)? = null
    private var broken = false

    init {
        // Drawn on this thread, where a failure can be caught.
        setAsyncUpdates(com.airbnb.lottie.AsyncUpdates.DISABLED)
    }

    override fun draw(canvas: Canvas) {
        if (broken) return
        try {
            super.draw(canvas)
        } catch (e: RuntimeException) {
            broken = true
            cancelAnimation()
            post { onBroken?.invoke() }
        }
    }
}

/** Whether [json] draws through its whole run without the renderer throwing (checked on import). */
internal fun drawsSafely(json: String, key: String): Boolean = runCatching {
    val comp = com.airbnb.lottie.LottieCompositionFactory.fromJsonStringSync(json, key).value ?: return false
    val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val d = com.airbnb.lottie.LottieDrawable().apply {
        setAsyncUpdates(com.airbnb.lottie.AsyncUpdates.DISABLED)
        composition = comp
        setBounds(0, 0, 64, 64)
    }
    for (i in 0..SAFETY_FRAMES) {
        d.progress = i / SAFETY_FRAMES.toFloat()
        d.draw(canvas)
    }
    bmp.recycle()
    true
}.getOrDefault(false)

private const val SAFETY_FRAMES = 16
