package com.alphamessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.HankenGrotesque

/**
 * Small pill badge showing an unread count.
 *
 * Uses theme colours:
 *   - Background: [AlphaColorScheme.badgeBg]
 *   - Text: [AlphaColorScheme.badgeText]
 *
 * @param count     Unread count to display
 * @param modifier  Layout modifier
 */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count <= 0) return

    val colors = AlphaTheme.colorScheme

    val text = if (count > 99) "99+" else count.toString()
    // Fixed square size ensures a perfect circle after CircleShape clip.
    val size = if (text.length > 1) 26.dp else 22.dp
    Box(
        modifier = modifier
            .size(size)
            .clip(BadgeShape)
            .background(color = colors.badgeBg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = TextStyle(
                color = colors.badgeText,
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = HankenGrotesque,
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            )
        )
    }
}

/** Badge shape — perfectly round circle. */
private val BadgeShape = CircleShape
