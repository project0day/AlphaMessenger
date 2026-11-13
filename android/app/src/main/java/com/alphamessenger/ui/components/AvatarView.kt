package com.alphamessenger.ui.components

import androidx.compose.foundation.background
import com.alphamessenger.ui.components.avatarBackdrop
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.HankenGrotesque

/**
 * Avatar size presets matching the design spec.
 */
enum class AvatarSize(
    val diameter: Dp,
    val fontSize: TextUnit,
    val indicatorDiameter: Dp,
    val indicatorOffset: Dp
) {
    /** 24dp — group mini-avatars */
    SMALL(24.dp, 10.sp, 6.dp, 0.dp),
    /** 40dp — top bar */
    MEDIUM(40.dp, 16.sp, 10.dp, 0.dp),
    /** 50dp — chat list items */
    LARGE(50.dp, 20.sp, 12.dp, 1.dp),
    /** 88dp — profile screen */
    XL(88.dp, 34.sp, 16.dp, 2.dp);
}

/**
 * Circular avatar with gradient background and letter initial.
 *
 * Supports:
 *   - Gradient background from theme avatar gradient presets
 *   - White bold letter initial text
 *   - Optional online indicator dot (bottom-right, green)
 *   - Coil image loading (AsyncImage with circle crop)
 *   - 4 size presets: Small, Medium, Large, XL
 *
 * When [imageUrl] is provided and non-blank, loads the image via Coil
 * with circle crop. Falls back to the gradient + initial otherwise.
 *
 * @param name          Display name (first letter used as initial)
 * @param imageUrl      Optional URL for Coil AsyncImage; blank = use gradient
 * @param size          Size preset (default [AvatarSize.LARGE])
 * @param isOnline      Whether to show the green indicator dot
 * @param gradientIndex Which avatar gradient pair to use (0, 1, or 2)
 * @param modifier      Layout modifier
 */
@Composable
fun AvatarView(
    name: String,
    imageUrl: String = "",
    size: AvatarSize = AvatarSize.LARGE,
    isOnline: Boolean = false,
    gradientIndex: Int = 0,
    modifier: Modifier = Modifier
) {
    val colors = AlphaTheme.colorScheme

    // Pick gradient pair from theme
    val gradientPair = remember(gradientIndex, colors) {
        when (gradientIndex % 3) {
            0 -> colors.avatarGradient1Start to colors.avatarGradient1End
            1 -> colors.avatarGradient2Start to colors.avatarGradient2End
            else -> colors.avatarGradient3Start to colors.avatarGradient3End
        }
    }

    val initial = remember(name) {
        com.alphamessenger.ui.components.avatarInitial(name)
    }

    Box(modifier = modifier) {
        // ── Avatar circle ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(size.diameter)
                .clip(CircleShape)
                .avatarBackdrop(Brush.linearGradient(
                        colors = listOf(gradientPair.first, gradientPair.second)
                    )),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl.isNotBlank()) {
                // Coil image with circle crop
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    modifier = Modifier
                        .size(size.diameter)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Letter initial
                Text(
                    text = initial,
                    style = TextStyle(
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = size.fontSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = HankenGrotesque
                    )
                )
            }
        }

        // ── Online indicator dot ──────────────────────────────────
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size.indicatorDiameter)
                    .align(Alignment.BottomEnd)
                    .offset(x = size.indicatorOffset, y = size.indicatorOffset)
                    .clip(CircleShape)
                    .background(color = colors.onlineGreen)
                    .border(
                        width = 2.dp,
                        color = colors.bgPrimary,
                        shape = CircleShape
                    )
            )
        }
    }
}
