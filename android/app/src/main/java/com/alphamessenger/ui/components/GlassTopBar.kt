package com.alphamessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * The plain top bar of a settings page (the name is historical).
 *
 * Visual layers (top → bottom):
 * 1. Inner highlight line at top edge
 * 2. Content row (back button, title/subtitle, trailing actions)
 * 3. Bottom border (alphaBorder)
 * 4. Glass background (blur or fallback tint)
 *
 * All colours come from [AlphaTheme.colorScheme]:
 *   - Back icon: textSecondary
 *   - Title: textPrimary
 *   - Subtitle: textMuted
 *   - Border: alphaBorder
 *   - Highlight: glassInnerHighlight
 *
 * @param title       Screen title
 * @param subtitle    Optional subtitle below the title
 * @param onBack      Optional back callback; shows arrow icon when non-null
 * @param actions     Optional trailing actions composable slot
 */
@Composable
fun GlassTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    // New design: a solid bar in the page colour (the name stays for the
    // existing callers; there is no glass any more).
    val colors = AlphaTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgPrimary)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                androidx.compose.material3.Icon(
                    com.alphamessenger.ui.icons.AlphaIcons.AutoMirrored.Outlined.ArrowBack,
                    androidx.compose.ui.res.stringResource(com.alphamessenger.R.string.common_back),
                    tint = colors.textPrimary,
                )
            }
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                title, color = colors.textPrimary, fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1,
            )
            if (subtitle != null) {
                androidx.compose.material3.Text(subtitle, color = colors.textMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
        actions()
    }
}

/** Standard top-app-bar height. */
private val TopBarHeight = 64.dp

/** Taller bar when subtitle is present. */
private val TopBarHeightSubtitle = 78.dp
