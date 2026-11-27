package com.alphamessenger.ui.onboarding

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme

/*
 * Onboarding kit — the new design (docs: "AlphaMessenger — new design").
 *
 * Solid surfaces, no glass: depth comes from a step of background brightness,
 * never from blur or shadow. One primary action per screen, at the bottom
 * under the thumb. Colours come from the current theme, so the same screens
 * work in the dark (Grey) and light (Daylight) themes.
 */

/** The English BIP-39 words (from the core), loaded once. */
val Bip39Words: List<String> by lazy { runCatching { uniffi.alphamessenger.mnemonicWordlist() }.getOrDefault(emptyList()) }

/** Text on an accent fill: dark on a light accent, white on a dark one. */
fun AlphaColorScheme.onAccent(): Color =
    if (accent.luminance() > 0.45f) Color(0xFF06221E) else Color.White

/** Surface one step above the background (cards, fields). */
val AlphaColorScheme.surface1: Color get() = bgSecondary

/** Surface two steps above (chips inside cards). */
val AlphaColorScheme.surface2: Color get() = bgTertiary

/** Call at the top of any onboarding screen: the navigation bar takes the page colour. */
@Composable
fun OnboardingNavBarEffect() {
    val cs = AlphaTheme.colorScheme
    DisposableEffect(cs.bgPrimary) {
        val owner = Any()
        com.alphamessenger.ui.theme.SystemBars.requestNav(owner, cs.bgPrimary)
        onDispose { com.alphamessenger.ui.theme.SystemBars.releaseNav(owner) }
    }
}

/** Screenshots and the "Recents" preview are blocked while this is on screen. */
@Composable
fun SecureScreenEffect() {
    com.alphamessenger.ui.components.SecureWindow()
}

/**
 * The page every onboarding step uses: back + step bar on top, title and
 * explanation, scrollable content, and the actions pinned to the bottom
 * (lifted by the keyboard).
 */
@Composable
fun OnbScreen(
    title: String?,
    subtitle: String? = null,
    step: Int? = null,
    totalSteps: Int = 5,
    onBack: (() -> Unit)? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    OnboardingNavBarEffect()
    Column(
        Modifier
            .fillMaxSize()
            .background(cs.bgPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (onBack != null || step != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp).height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AlphaIcons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = cs.textPrimary, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Spacer(Modifier.width(44.dp))
                }
                Spacer(Modifier.width(8.dp))
                if (step != null) {
                    StepBar(step, totalSteps, Modifier.weight(1f))
                    Text(
                        stringResource(R.string.onb_step_of, step, totalSteps),
                        color = cs.textMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, color = cs.textPrimary, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                    if (subtitle != null) {
                        Text(subtitle, color = cs.textSecondary, fontSize = 15.sp, lineHeight = 22.sp)
                    }
                }
            }
            content()
        }
        if (bottom != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = bottom,
            )
        }
    }
}

@Composable
fun StepBar(step: Int, total: Int, modifier: Modifier = Modifier) {
    val cs = AlphaTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < step) cs.accent else cs.alphaBorder),
            )
        }
    }
}

/** The one bright action of a screen. */
@Composable
fun OnbPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val cs = AlphaTheme.colorScheme
    val on = cs.onAccent()
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) cs.accent else cs.accent.copy(alpha = 0.35f))
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = on, strokeWidth = 2.5.dp)
        } else {
            Text(text, color = on.copy(alpha = if (enabled) 1f else 0.7f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A quieter full-width action on a surface. */
@Composable
fun OnbSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) {
    val cs = AlphaTheme.colorScheme
    Row(
        modifier
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surface1)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = cs.textPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = cs.textPrimary.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Text-only action (skip, later, alternatives). */
@Composable
fun OnbTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    leading: ImageVector? = null,
) {
    val cs = AlphaTheme.colorScheme
    val color = if (accent) cs.accent else cs.textSecondary
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Labelled input: accent border while focused, red with a message on error. */
@Composable
fun OnbField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    revealed: Boolean = false,
    error: String? = null,
    ok: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = AlphaTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val border = when {
        error != null -> cs.danger
        focused -> cs.accent
        else -> cs.alphaBorder
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, color = cs.textSecondary, fontSize = 14.sp)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(cs.surface1)
                .border(if (focused || error != null) 1.5.dp else 1.dp, border, RoundedCornerShape(14.dp))
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, color = cs.textMuted, fontSize = 17.sp, fontFamily = if (mono) FontFamily.Monospace else null)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    textStyle = TextStyle(
                        color = cs.textPrimary, fontSize = 17.sp,
                        fontFamily = if (mono) FontFamily.Monospace else null,
                    ),
                    cursorBrush = SolidColor(cs.accent),
                    visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        autoCorrect = false,
                    ),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            if (ok) {
                Icon(AlphaIcons.Filled.Check, null, tint = cs.success, modifier = Modifier.padding(end = 10.dp).size(20.dp))
            }
            trailing?.invoke(this)
        }
        if (error != null) {
            Text(error, color = cs.danger, fontSize = 13.sp)
        }
    }
}

enum class CalloutKind { Info, Warn, Error }

/** A calm explanatory panel. Yellow for caution, red only for real errors. */
@Composable
fun OnbCallout(text: String, kind: CalloutKind = CalloutKind.Info, modifier: Modifier = Modifier) {
    val cs = AlphaTheme.colorScheme
    val (tint, icon) = when (kind) {
        CalloutKind.Info -> cs.textSecondary to AlphaIcons.Outlined.Info
        CalloutKind.Warn -> cs.warning to AlphaIcons.Outlined.WarningAmber
        CalloutKind.Error -> cs.danger to AlphaIcons.Outlined.ErrorOutline
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (kind == CalloutKind.Info) cs.surface1 else tint.copy(alpha = 0.10f))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, color = if (kind == CalloutKind.Info) cs.textSecondary else cs.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** Icon tile used in rows and cards. */
@Composable
fun OnbIconTile(icon: ImageVector, tint: Color, background: Color, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(background),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size / 2).dp)) }
}

/** A row on a surface: icon, title + note, optional trailing control. */
@Composable
fun OnbCardRow(
    icon: ImageVector,
    title: String,
    note: String?,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    iconBackground: Color? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = AlphaTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cs.surface1)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) cs.accent else Color.Transparent, RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OnbIconTile(icon, iconTint ?: cs.accent, iconBackground ?: cs.surface2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = cs.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (note != null) Text(note, color = cs.textMuted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        trailing?.invoke()
    }
}

/** Radio dot for option cards. */
@Composable
fun OnbRadio(selected: Boolean) {
    val cs = AlphaTheme.colorScheme
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(if (selected) 7.dp else 2.dp, if (selected) cs.accent else cs.alphaBorder, CircleShape),
    )
}

/** Small pill action inside a row ("Allow"). */
@Composable
fun OnbPillButton(text: String, onClick: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = cs.onAccent(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** The app's mark (launcher foreground). */
@Composable
fun AppLogoIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = "Alpha Messenger",
        tint = Color.Unspecified,
        modifier = modifier,
    )
}
