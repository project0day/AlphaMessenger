package com.alphamessenger.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// --- Theme variant enum — the 5 themes from the web demo ---

enum class AlphaThemeVariant(val displayName: String, val isLight: Boolean = false) {
    GREY("Grey"),
    MIDNIGHT("Midnight"),
    CARBON("Carbon"),
    AMOLED("Black"),
    VIOLET("Violet"),
    COSMOS("Cosmos"),
    EMERALD("Emerald"),
    AZURE("Azure"),
    DAYLIGHT("Daylight", isLight = true),
    SAND("Sand", isLight = true),
    SKY("Sky", isLight = true),
    LILAC("Lilac", isLight = true);

    companion object {
        /** Default theme. */
        val DEFAULT = GREY
    }
}

// --- CompositionLocal for the custom colour scheme ---

/**
 * Provides the current [AlphaColorScheme] down the tree.
 *
 * Read it in any composable:
 * ```
 * val colors = LocalAlphaColorScheme.current
 * Box(background = colors.bgPrimary) { … }
 * ```
 */
val LocalAlphaColorScheme = staticCompositionLocalOf<AlphaColorScheme> {
    error("No AlphaColorScheme provided. Wrap your content in AlphaTheme {}")
}

/**
 * Convenience accessor — returns the active [AlphaColorScheme].
 */
object AlphaTheme {
    val colorScheme: AlphaColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAlphaColorScheme.current
}

// --- Derive Material 3 ColorScheme from AlphaColorScheme ---

/**
 * Maps an [AlphaColorScheme] to a Material 3 [androidx.compose.material3.ColorScheme]
 * so that all M3 components (TopAppBar, NavigationBar, Switch, etc.) pick up
 * the correct colours automatically.
 */
internal fun AlphaColorScheme.toMaterial3() = darkColorScheme(
    primary              = accent,
    onPrimary            = msgOutText,
    primaryContainer     = accentDim,
    onPrimaryContainer   = textPrimary,
    secondary            = msgOutEnd,
    onSecondary          = msgOutText,
    secondaryContainer   = accentDim,
    onSecondaryContainer = textPrimary,
    tertiary             = auroraOrb2,
    onTertiary           = Color.White,
    background           = bgPrimary,
    onBackground         = textPrimary,
    surface              = bgSecondary,
    onSurface            = textPrimary,
    surfaceVariant       = bgTertiary,
    onSurfaceVariant     = textSecondary,
    outline              = borderLight,
    outlineVariant       = alphaBorder,
    error                = danger,
    onError              = Color.White,
    errorContainer       = danger.copy(alpha = 0.15f),
    onErrorContainer     = danger,
    inverseSurface       = textPrimary,
    inverseOnSurface     = bgPrimary,
    inversePrimary      = accent,
    surfaceTint          = accent,
)

// --- AlphaTheme composable ---

/**
 * Root theme composable for AlphaMessenger.
 *
 * Provides:
 *  - **MaterialTheme** (M3 colour scheme derived from the selected variant)
 *  - **LocalAlphaColorScheme** (full custom token set)
 *
 * @param variant The selected theme variant (default: [AlphaThemeVariant.VEIL])
 * @param typography Optional typography override; defaults to [AlphaTypography]
 * @param content Your composable content
 */
@Composable
fun AlphaTheme(
    variant: AlphaThemeVariant = AlphaThemeVariant.DEFAULT,
    typography: androidx.compose.material3.Typography = AlphaTypography,
    /** The user's own theme (built on [variant]); null = the built-in [variant]. */
    custom: CustomTheme? = null,
    content: @Composable () -> Unit
) {
    val customScheme = androidx.compose.runtime.remember(custom) { custom?.scheme() }
    val alphaColors = customScheme ?: when (variant) {
        AlphaThemeVariant.GREY     -> greyColorScheme()
        AlphaThemeVariant.MIDNIGHT -> midnightColorScheme()
        AlphaThemeVariant.CARBON   -> carbonColorScheme()
        AlphaThemeVariant.AMOLED   -> amoledColorScheme()
        AlphaThemeVariant.VIOLET   -> violetColorScheme()
        AlphaThemeVariant.COSMOS   -> cosmosColorScheme()
        AlphaThemeVariant.EMERALD  -> emeraldColorScheme()
        AlphaThemeVariant.AZURE    -> azureColorScheme()
        AlphaThemeVariant.DAYLIGHT -> daylightColorScheme()
        AlphaThemeVariant.SAND     -> sandColorScheme()
        AlphaThemeVariant.SKY      -> skyColorScheme()
        AlphaThemeVariant.LILAC    -> lilacColorScheme()
    }

    // An own theme's accent can be any colour: text on accent buttons takes
    // whichever of white/near-black reads on it.
    val m3Colors = alphaColors.toMaterial3().let { if (custom != null) it.copy(onPrimary = readableOn(alphaColors.accent)) else it }

    // --- Edge-to-edge with transparent system bars ---
    val view = LocalView.current
    if (!view.isInEditMode) {
        // The ONE place the window gets painted. Values are read in composition,
        // so changing the request ([SystemBars]) repaints immediately — and after
        // any screen leaves, the color returns to the theme by itself, without a
        // manual "restore the previous value".
        DisposableEffect(view) {
            val window = (view.context as Activity).window
            SystemBars.attach(window)
            onDispose { SystemBars.detach(window) }
        }
        // State read in composition + applied through the single window owner.
        val overlay = SystemBars.fullscreenOverlay
        val navRequest = SystemBars.navColor
        val statusRequest = SystemBars.statusColor
        val iconsRequest = SystemBars.lightIcons
        DisposableEffect(overlay, navRequest, statusRequest, iconsRequest, alphaColors.bgBottom) {
            SystemBars.apply(view, alphaColors.bgBottom, Color.Transparent, variant.isLight)
            onDispose { }
        }
        val latestNav = androidx.compose.runtime.rememberUpdatedState(alphaColors.bgBottom)
        val latestLight = androidx.compose.runtime.rememberUpdatedState(variant.isLight)
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, view) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    SystemBars.attach((view.context as Activity).window) // drops the stale cache
                    SystemBars.apply(view, latestNav.value, Color.Transparent, latestLight.value)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    val themedTypography = typography

    CompositionLocalProvider(
        LocalAlphaColorScheme provides alphaColors
    ) {
        MaterialTheme(
            colorScheme = m3Colors,
            typography  = themedTypography,
            content     = content
        )
    }
}

// --- Typography helpers ---

/**
 * Returns a copy of the Typography where every text style uses
 * [JetBrainsMono] as its font family — for the Hacktivist theme.
 */
private fun androidx.compose.material3.Typography.withMonoFont() =
    copy(
        displayLarge  = displayLarge.copy(fontFamily  = JetBrainsMono),
        displayMedium = displayMedium.copy(fontFamily = JetBrainsMono),
        headlineLarge = headlineLarge.copy(fontFamily = JetBrainsMono),
        headlineMedium= headlineMedium.copy(fontFamily= JetBrainsMono),
        titleLarge    = titleLarge.copy(fontFamily    = JetBrainsMono),
        titleMedium   = titleMedium.copy(fontFamily   = JetBrainsMono),
        titleSmall    = titleSmall.copy(fontFamily    = JetBrainsMono),
        bodyLarge     = bodyLarge.copy(fontFamily     = JetBrainsMono),
        bodyMedium    = bodyMedium.copy(fontFamily    = JetBrainsMono),
        bodySmall     = bodySmall.copy(fontFamily     = JetBrainsMono),
        labelLarge    = labelLarge.copy(fontFamily    = JetBrainsMono),
        labelMedium   = labelMedium.copy(fontFamily   = JetBrainsMono),
        labelSmall    = labelSmall.copy(fontFamily    = JetBrainsMono),
    )

// --- Backward-compatible alias (so existing code still compiles) ---

/**
 * @deprecated Use [AlphaTheme] instead.
 */
@Composable
fun AlphaMessengerTheme(
    themeVariant: AlphaThemeVariant = AlphaThemeVariant.DEFAULT,
    content: @Composable () -> Unit
) {
    AlphaTheme(variant = themeVariant, content = content)
}
