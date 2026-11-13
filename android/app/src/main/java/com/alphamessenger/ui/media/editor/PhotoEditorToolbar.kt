package com.alphamessenger.ui.media.editor

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/** One bottom-bar action. The list is data, not layout. */
data class EditorAction(val id: String, val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * The editor's bottom bar.
 *
 * A scrolling row: more tools than the screen is wide still fit. Each item is
 * an icon and caption of fixed width, so the row reads as a row. Not a lazy
 * list: a tap on a tool swaps this bar out in the same frame, and removing a
 * lazy list together with the item being tapped fails in the composition.
 */
@Composable
fun EditorActionBar(actions: List<EditorAction>, colors: AlphaColorScheme, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action -> androidx.compose.runtime.key(action.id) {
            Column(
                modifier = Modifier.width(76.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = action.onClick).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(action.icon, action.label, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text(
                    action.label,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } }
    }
}

/** Tool icons — from Material, no unicode symbols in text. */
object EditorIcons {
    val Crop = AlphaIcons.Outlined.Crop
    val Rotate = AlphaIcons.Outlined.RotateRight
    val FlipHorizontal = AlphaIcons.Outlined.Flip
    val FlipVertical = AlphaIcons.Outlined.SwapVert
    val Reset = AlphaIcons.Outlined.RestartAlt
    val Draw = AlphaIcons.Outlined.Brush
    val Sticker = AlphaIcons.Outlined.EmojiEmotions
}
