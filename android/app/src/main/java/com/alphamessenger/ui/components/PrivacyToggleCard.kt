package com.alphamessenger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.HankenGrotesque

/**
 * Glass card with a leading icon, title/subtitle text, and toggle switch.
 *
 * When enabled (checked), the accent colour is applied to the switch
 * and a subtle accent tint appears on the card background.
 *
 * All colours come from [AlphaTheme.colorScheme].
 *
 * @param icon      Leading icon vector
 * @param title     Primary label text
 * @param subtitle  Secondary description text
 * @param checked   Whether the toggle is currently on
 * @param onCheckedChange  Callback when toggle state changes
 * @param modifier  Layout modifier
 */
@Composable
fun PrivacyToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AlphaTheme.colorScheme

    // New design: a solid surface card; the whole row toggles.
    val iconTint = if (checked) colors.accent else colors.textSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(color = colors.bgSecondary)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                .background(if (checked) colors.accent.copy(alpha = 0.14f) else colors.bgTertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontFamily = HankenGrotesque
            )
            Text(
                text = subtitle,
                color = colors.textMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = HankenGrotesque
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Toggle switch
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent,
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                uncheckedTrackColor = colors.bgTertiary,
                uncheckedThumbColor = colors.textMuted,
                uncheckedBorderColor = colors.alphaBorder
            )
        )
    }
}

/** Card shape — 16dp rounded corners. */
private val CardShape = RoundedCornerShape(18.dp)
