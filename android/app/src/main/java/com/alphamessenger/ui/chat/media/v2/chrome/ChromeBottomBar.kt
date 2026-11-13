package com.alphamessenger.ui.chat.media.v2.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bottom bar — ONE Surface for caption, filmstrip and the transport bar.
 * One background, one height, one alpha: three independent overlays drift
 * apart, one does not.
 *
 * Content arrives as slots, so the bar knows nothing about the player, the
 * collection or the caption — only about order and spacing.
 */
@Composable
fun ChromeBottomBar(
    modifier: Modifier = Modifier,
    caption: (@Composable () -> Unit)? = null,
    filmstrip: (@Composable () -> Unit)? = null,
    reactions: (@Composable () -> Unit)? = null,
    transport: (@Composable () -> Unit)? = null,
    reply: (@Composable () -> Unit)? = null,
) {
    // Nothing to show — no empty strip.
    if (caption == null && filmstrip == null && reactions == null && transport == null && reply == null) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Above the navigation bar, and above the keyboard while typing a reply.
                .windowInsetsPadding(
                    androidx.compose.foundation.layout.WindowInsets.navigationBars
                        .union(androidx.compose.foundation.layout.WindowInsets.ime),
                )
                .padding(top = 10.dp, bottom = 6.dp),
        ) {
            if (caption != null) {
                caption()
                Spacer(Modifier.height(8.dp))
            }
            if (filmstrip != null) {
                filmstrip()
                Spacer(Modifier.height(6.dp))
            }
            if (reactions != null) {
                reactions()
                Spacer(Modifier.height(6.dp))
            }
            transport?.invoke()
            reply?.invoke()
        }
    }
}
