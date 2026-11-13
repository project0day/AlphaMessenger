package com.alphamessenger.ui.media.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Player surface that occupies EXACTLY the frame's rectangle.
 *
 * The only place in the app where a `PlayerView` is created. Not for beauty:
 * the video surface renders outside the normal draw pass and cannot be
 * covered by a picture, and until the player reports the frame size its
 * container stretches the surface over all available space — the first frame
 * manages to draw stretched (the "other frames flash at the sides" effect).
 * The only cure is a rectangle known IN ADVANCE, and the rule must live in
 * one place: while there were two, the second copy kept the old bug.
 *
 * [aspect] — frame width/height. Zero means "not known yet": the surface
 * then fills the whole area, as before.
 */
@Composable
fun FittedPlayerSurface(
    player: ExoPlayer,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val boxAspect = if (maxHeight.value > 0f) maxWidth / maxHeight else 1f
        val fitted = if (aspect <= 0f) Modifier.fillMaxSize() else {
            // Exactly the "fit inside" rule: screen wider than the frame —
            // constrained by height; narrower — by width.
            Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = boxAspect > aspect)
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { it.player = player },
            modifier = fitted,
        )
    }
}
