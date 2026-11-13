package com.alphamessenger.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush

/**
 * The coloured disc behind an avatar (initials, or under a photo while it
 * loads). Drawn a hair inside the circle: a photo on top covers its edge
 * completely, so no tinted ring shows around a photo (a full-size backdrop
 * bleeds through the photo's anti-aliased edge).
 */
fun Modifier.avatarBackdrop(brush: Brush): Modifier = drawBehind {
    drawCircle(brush, radius = size.minDimension / 2f - 1.5f, center = center)
}
