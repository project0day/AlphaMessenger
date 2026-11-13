package com.alphamessenger.ui.components

import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.themeBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/*
 * Option kit — ONE look for every settings/options screen (new design):
 * a solid bar, section labels, and rows grouped in rounded cards with thin
 * separators. No glass, colours only from the theme.
 */

/** The page: solid top bar (back + title + optional actions) and scrolling content. */
@Composable
fun OptionScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    Column(Modifier.fillMaxSize().then(Modifier.themeBackground(cs))) {
        OptionTopBar(title, onBack, actions)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
fun OptionTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val cs = AlphaTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.bgPrimary).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = cs.textPrimary)
        }
        Text(
            title, color = cs.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun OptionSection(text: String) {
    val cs = AlphaTheme.colorScheme
    Text(
        text.uppercase(), color = cs.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** A card holding related rows; put [OptionDivider] between them. */
@Composable
fun OptionGroup(content: @Composable ColumnScope.() -> Unit) {
    val cs = AlphaTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.bgSecondary),
        content = content,
    )
}

@Composable
fun OptionDivider(inset: Boolean = true) {
    val cs = AlphaTheme.colorScheme
    HorizontalDivider(Modifier.padding(start = if (inset) 16.dp else 0.dp), thickness = 0.5.dp, color = cs.alphaBorder)
}

/** Footnote under a group. */
@Composable
fun OptionNote(text: String) {
    val cs = AlphaTheme.colorScheme
    Text(
        text, color = cs.textMuted, fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
    )
}

@Composable
private fun OptionIcon(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** Row: optional icon, title + subtitle, trailing value or chevron. */
@Composable
fun OptionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    value: String? = null,
    danger: Boolean = false,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = AlphaTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            OptionIcon(icon, if (danger) cs.danger else iconTint ?: cs.accent)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (danger) cs.danger else cs.textPrimary, fontSize = 16.sp)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = cs.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, color = cs.textMuted, fontSize = 15.sp, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else if (onClick != null && chevron && !danger) {
            Icon(AlphaIcons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = cs.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** Row with a switch; the whole row toggles. */
@Composable
fun OptionSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val cs = AlphaTheme.colorScheme
    OptionRow(
        title = title, subtitle = subtitle, icon = icon,
        iconTint = if (checked) cs.accent else cs.textSecondary,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailing = {
            Switch(
                checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = cs.accent,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = cs.bgTertiary,
                    uncheckedThumbColor = cs.textMuted,
                    uncheckedBorderColor = cs.alphaBorder,
                ),
            )
        },
    )
}

/** One choice of a single-select list (check mark on the selected one). */
@Composable
fun OptionChoiceRow(title: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    OptionRow(
        title = title, subtitle = subtitle, onClick = onClick,
        trailing = {
            if (selected) Icon(AlphaIcons.Outlined.Check, stringResource(R.string.common_selected), tint = cs.accent, modifier = Modifier.size(22.dp))
            else Spacer(Modifier.size(22.dp))
        },
    )
}
