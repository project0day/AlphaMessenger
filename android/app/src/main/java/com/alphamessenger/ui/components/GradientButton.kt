package com.alphamessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.HankenGrotesque

/**
 * Gradient pill button with accent→sky gradient background.
 *
 * Uses the theme's accent colour for the gradient start and
 * avatarGradient1End for the gradient end (which matches the
 * sky/accent-secondary colour for each theme).
 *
 * Text is rendered in the theme's badgeText colour (typically dark)
 * for maximum contrast against the bright gradient.
 *
 * @param text      Button label
 * @param onClick   Click handler
 * @param modifier  Layout modifier
 * @param enabled   Whether the button is interactive
 * @param contentPadding  Internal padding (default horizontal 24dp, vertical 12dp)
 */
@Composable
fun GradientPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
) {
    val colors = AlphaTheme.colorScheme

    val gradient = Brush.horizontalGradient(
        colors = listOf(colors.accent, colors.avatarGradient1End)
    )

    Box(
        modifier = modifier
            .clip(PillShape)
            .background(
                brush = if (enabled) gradient else Brush.horizontalGradient(
                    colors = listOf(colors.textMuted, colors.textMuted)
                )
            )
            .clickable(
                enabled = enabled,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (enabled) colors.badgeText else colors.textMuted,
                fontSize = 15.sp,
                fontFamily = HankenGrotesque
            )
        )
    }
}

/**
 * Glass pill button with glass background and border.
 *
 * Uses the theme's glass surface tokens for the background,
 * alphaBorder for the outline, and textSecondary for the label.
 *
 * @param text      Button label
 * @param onClick   Click handler
 * @param modifier  Layout modifier
 * @param enabled   Whether the button is interactive
 * @param contentPadding  Internal padding
 */
@Composable
fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
) {
    val colors = AlphaTheme.colorScheme

    Box(
        modifier = modifier
            .clip(PillShape)
            .background(color = colors.glassBg)
            .border(
                width = 1.dp,
                color = if (enabled) colors.alphaBorder else colors.textMuted,
                shape = PillShape
            )
            .clickable(
                enabled = enabled,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (enabled) colors.textSecondary else colors.textMuted,
                fontSize = 15.sp,
                fontFamily = HankenGrotesque
            )
        )
    }
}

/** Pill shape — fully rounded ends. */
private val PillShape = RoundedCornerShape(50)
