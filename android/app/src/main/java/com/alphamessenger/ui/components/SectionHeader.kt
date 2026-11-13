package com.alphamessenger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.HankenGrotesque

/**
 * Uppercase, small, muted text for section dividers.
 *
 * Used for grouping content on screens like Settings, e.g.
 * "SECURITY", "CHAT", "ACCOUNT".
 *
 * Uses [AlphaColorScheme.textMuted] for the text colour.
 *
 * @param title     Section title text (will be displayed uppercase)
 * @param modifier  Layout modifier
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    val colors = AlphaTheme.colorScheme

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title.uppercase(),
            style = TextStyle(
                color = colors.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = HankenGrotesque,
                letterSpacing = 1.sp
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}
