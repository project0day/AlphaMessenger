package com.alphamessenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.components.GlassState
import com.alphamessenger.ui.components.glassPanel
import com.alphamessenger.ui.theme.AlphaTheme

/** The glass the mini-players are cut from (the screen they float over). */
val LocalPlayerHaze = compositionLocalOf<GlassState?> { null }

/**
 * One look for every mini-player (music, voice, video messages): a rounded
 * pill of frosted glass, like the pinned message — the content shows through,
 * blurred. Without glass (a screen that provides none) it is a plain pill.
 */
@Composable
fun Modifier.playerPill(): Modifier {
    val colors = AlphaTheme.colorScheme
    val pill = RoundedCornerShape(24.dp)
    val haze = LocalPlayerHaze.current
    return this
        .padding(start = 8.dp, end = 8.dp, top = 6.dp)
        .then(
            haze?.let {
                Modifier.glassPanel(
                    it, pill,
                    tint = colors.bgSecondary.copy(alpha = if (colors.bgSecondary.luminance() > 0.5f) 0.74f else 0.55f),
                    backdrop = colors.bgChat, blur = 22.dp,
                )
            } ?: Modifier.clip(pill).background(colors.bgSecondary),
        )
        .clip(pill)
}
