package com.alphamessenger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified

// ══════════════════════════════════════════════════════════════════════
// AlphaColorScheme — full design-token colour system for 5 themes
// ══════════════════════════════════════════════════════════════════════

/**
 * Complete colour token set for one AlphaMessenger theme.
 *
 * Every token matches a CSS variable from the web demo (alpha-demo.css).
 * Components should read the current scheme via `LocalAlphaColorScheme.current`
 * so that colours change instantly when the user switches themes.
 */
data class AlphaColorScheme(
    // ── Backgrounds ───────────────────────────────────────────────
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgTertiary: Color,
    val bgChat: Color,

    // ── Accent ────────────────────────────────────────────────────
    val accent: Color,
    val accentDim: Color,
    val accentGlow: Color,

    // ── Text hierarchy ────────────────────────────────────────────
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,

    // ── Message bubbles ───────────────────────────────────────────
    val msgOutStart: Color,
    val msgOutEnd: Color,
    val msgOutText: Color,
    val msgIn: Color,

    // ── Borders ───────────────────────────────────────────────────
    val alphaBorder: Color,
    val borderLight: Color,

    // ── Semantic / status ─────────────────────────────────────────
    val danger: Color,
    val warning: Color,
    val success: Color,

    // ── Glass surface tokens (used by Glass theme & shared components) ──
    val glassBg: Color,
    val glassBgHigh: Color,
    val glassBorder: Color,
    val glassInnerHighlight: Color,

    // ── Ephemeral indicators ──────────────────────────────────────
    val ephemeralBlue: Color,
    val viewOncePink: Color,

    // ── Online / badges ───────────────────────────────────────────
    val onlineGreen: Color,
    val badgeBg: Color,
    val badgeText: Color,

    // ── Avatar gradient presets ───────────────────────────────────
    val avatarGradient1Start: Color,
    val avatarGradient1End: Color,
    val avatarGradient2Start: Color,
    val avatarGradient2End: Color,
    val avatarGradient3Start: Color,
    val avatarGradient3End: Color,

    // ── Aurora background orbs ────────────────────────────────────
    val auroraOrb1: Color,
    val auroraOrb2: Color,
    val auroraOrb3: Color,

    // ── Scrim / overlay ───────────────────────────────────────────
    val scrimDark: Color,
    val scrimLight: Color,

    // ── Theme-specific feature flags ──────────────────────────────
    val isScanlineEnabled: Boolean,
    val isGlitchEnabled: Boolean,
    val isGlassMorphism: Boolean,

    // ── Own-message bubbles (own themes) ──────────────────────────
    /** The fill of own bubbles; Unspecified = the accent (built-in themes). */
    val bubbleOut: Color = Color.Unspecified,
    /** Where that fill runs to (a gradient); Unspecified = one colour. */
    val bubbleOutEnd: Color = Color.Unspecified,
    /** Where the screen background runs to (top to bottom); Unspecified = one colour. */
    val bgEnd: Color = Color.Unspecified,
)

/** The colour of own bubbles (a gradient's first colour). */
val AlphaColorScheme.outBubble: Color
    get() = if (bubbleOut.isSpecified) bubbleOut else accent

/** The screen background's gradient, or null when it is one colour. */
val AlphaColorScheme.bgBrush: androidx.compose.ui.graphics.Brush?
    get() = if (bgEnd.isSpecified && bgEnd != bgPrimary)
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bgPrimary, bgEnd)) else null

/** The screen background's colour at the bottom edge: what the navigation bar continues. */
val AlphaColorScheme.bgBottom: Color
    get() = if (bgEnd.isSpecified) bgEnd else bgPrimary

/** Paint the theme's screen background: its gradient, else its colour. */
fun androidx.compose.ui.Modifier.themeBackground(cs: AlphaColorScheme): androidx.compose.ui.Modifier =
    cs.bgBrush?.let { this.then(androidx.compose.ui.Modifier.background(it)) }
        ?: this.then(androidx.compose.ui.Modifier.background(cs.bgPrimary))

/** Own bubbles' gradient, or null when they are one colour. */
val AlphaColorScheme.outBubbleBrush: androidx.compose.ui.graphics.Brush?
    get() = if (bubbleOut.isSpecified && bubbleOutEnd.isSpecified && bubbleOutEnd != bubbleOut)
        androidx.compose.ui.graphics.Brush.linearGradient(listOf(bubbleOut, bubbleOutEnd)) else null

// ══════════════════════════════════════════════════════════════════════
// THEME 1 — HACKTIVIST  (green terminal aesthetic)
// ══════════════════════════════════════════════════════════════════════

fun hacktivistColorScheme() = AlphaColorScheme(
    // Backgrounds
    bgPrimary          = Color(0xFF070B07),
    bgSecondary        = Color(0xFF0A120A),
    bgTertiary         = Color(0xFF0F1A0F),
    bgChat             = Color(0xFF070B07),

    // Accent
    accent             = Color(0xFF00FF41),
    accentDim          = Color(0x1800FF41),   // #00ff4118
    accentGlow         = Color(0x5500FF41),   // #00ff4155

    // Text
    textPrimary        = Color(0xFFB8FFC8),
    textSecondary      = Color(0xFF3EA35A),
    textMuted          = Color(0xFF1F6E34),

    // Messages
    msgOutStart        = Color(0xFF0A3318),
    msgOutEnd          = Color(0xFF0F5A28),
    msgOutText         = Color(0xFFAAFFBB),
    msgIn              = Color(0xFF0C160C),

    // Borders
    alphaBorder        = Color(0x1800FF41),   // #00ff4118
    borderLight        = Color(0x3000FF41),   // #00ff4130

    // Semantic
    danger             = Color(0xFFFF0040),
    warning            = Color(0xFFFFFF33),
    success            = Color(0xFF00FF41),

    // Glass (not used in hacktivist, but provide fallbacks)
    glassBg            = Color(0x10FFFFFF),
    glassBgHigh        = Color(0x1AFFFFFF),
    glassBorder        = Color(0x1800FF41),
    glassInnerHighlight= Color(0x1800FF41),

    // Ephemeral
    ephemeralBlue      = Color(0xFF60A5FA),
    viewOncePink       = Color(0xFFFF0040),

    // Online / badge
    onlineGreen        = Color(0xFF00FF41),
    badgeBg            = Color(0xFF00FF41),
    badgeText          = Color(0xFF070B07),

    // Avatars
    avatarGradient1Start = Color(0xFF00FF41),
    avatarGradient1End   = Color(0xFF00CC33),
    avatarGradient2Start = Color(0xFF3EA35A),
    avatarGradient2End   = Color(0xFF1F6E34),
    avatarGradient3Start = Color(0xFFFFFF33),
    avatarGradient3End   = Color(0xFF00FF41),

    // Aurora
    auroraOrb1         = Color(0xFF00FF41),
    auroraOrb2         = Color(0xFF00CC33),
    auroraOrb3         = Color(0xFFFFFF33),

    // Scrim
    scrimDark          = Color(0x33000000),
    scrimLight         = Color(0x1A000000),

    // Feature flags
    isScanlineEnabled  = true,
    isGlitchEnabled    = true,
    isGlassMorphism    = false,
)

// ══════════════════════════════════════════════════════════════════════
// THEME 2 — VEIL  (default dark blue / teal)
// ══════════════════════════════════════════════════════════════════════

fun veilColorScheme() = AlphaColorScheme(
    // Backgrounds
    bgPrimary          = Color(0xFF0A0A0F),
    bgSecondary        = Color(0xFF12121A),
    bgTertiary         = Color(0xFF1A1A2E),
    bgChat             = Color(0xFF0D0D14),

    // Accent
    accent             = Color(0xFF00D4AA),
    accentDim          = Color(0x2200D4AA),   // #00d4aa22
    accentGlow         = Color(0x6600D4AA),   // #00d4aa66

    // Text
    textPrimary        = Color(0xFFE8E8F0),
    textSecondary      = Color(0xFF8888AA),
    textMuted          = Color(0xFF555577),

    // Messages
    msgOutStart        = Color(0xFF00D4AA),
    msgOutEnd          = Color(0xFF0088FF),
    msgOutText         = Color(0xFF04181F),
    msgIn              = Color(0xFF1A1A2E),

    // Borders
    alphaBorder        = Color(0x12FFFFFF),   // #ffffff12
    borderLight        = Color(0x1FFFFFFF),   // #ffffff1f

    // Semantic
    danger             = Color(0xFFFF4466),
    warning            = Color(0xFFFFAA00),
    success            = Color(0xFF00D4AA),

    // Glass
    glassBg            = Color(0x10FFFFFF),
    glassBgHigh        = Color(0x1AFFFFFF),
    glassBorder        = Color(0x12FFFFFF),
    glassInnerHighlight= Color(0x18FFFFFF),

    // Ephemeral
    ephemeralBlue      = Color(0xFF60A5FA),
    viewOncePink       = Color(0xFFF472B6),

    // Online / badge
    onlineGreen        = Color(0xFF22C55E),
    badgeBg            = Color(0xFF00D4AA),
    badgeText          = Color(0xFF04181F),

    // Avatars
    avatarGradient1Start = Color(0xFF00D4AA),
    avatarGradient1End   = Color(0xFF0088FF),
    avatarGradient2Start = Color(0xFF6366F1),
    avatarGradient2End   = Color(0xFFA78BFA),
    avatarGradient3Start = Color(0xFFF97066),
    avatarGradient3End   = Color(0xFFFBBF24),

    // Aurora
    auroraOrb1         = Color(0xFF00D4AA),
    auroraOrb2         = Color(0xFF6366F1),
    auroraOrb3         = Color(0xFFF97066),

    // Scrim
    scrimDark          = Color(0x33000000),
    scrimLight         = Color(0x1A000000),

    // Feature flags
    isScanlineEnabled  = false,
    isGlitchEnabled    = false,
    isGlassMorphism    = false,
)

// ══════════════════════════════════════════════════════════════════════
// THEME 3 — PARANOID  (pure black + red)
// ══════════════════════════════════════════════════════════════════════

fun paranoidColorScheme() = AlphaColorScheme(
    // Backgrounds
    bgPrimary          = Color(0xFF000000),
    bgSecondary        = Color(0xFF070707),
    bgTertiary         = Color(0xFF101010),
    bgChat             = Color(0xFF020202),

    // Accent
    accent             = Color(0xFFFF0040),
    accentDim          = Color(0x18FF0040),   // #ff004018
    accentGlow         = Color(0x55FF0040),   // #ff004055

    // Text
    textPrimary        = Color(0xFFCFCFCF),
    textSecondary      = Color(0xFF777777),
    textMuted          = Color(0xFF444444),

    // Messages
    msgOutStart        = Color(0xFF3A0A16),
    msgOutEnd          = Color(0xFF5A0F22),
    msgOutText         = Color(0xFFFFD5DD),
    msgIn              = Color(0xFF0E0E0E),

    // Borders
    alphaBorder        = Color(0x10FFFFFF),   // #ffffff10
    borderLight        = Color(0x1AFFFFFF),   // #ffffff1a

    // Semantic
    danger             = Color(0xFFFF0040),
    warning            = Color(0xFFFF6600),
    success            = Color(0xFF00CC44),

    // Glass
    glassBg            = Color(0x10FFFFFF),
    glassBgHigh        = Color(0x1AFFFFFF),
    glassBorder        = Color(0x10FFFFFF),
    glassInnerHighlight= Color(0x18FFFFFF),

    // Ephemeral
    ephemeralBlue      = Color(0xFF60A5FA),
    viewOncePink       = Color(0xFFFF0040),

    // Online / badge
    onlineGreen        = Color(0xFF00CC44),
    badgeBg            = Color(0xFFFF0040),
    badgeText          = Color(0xFFFFFFFF),

    // Avatars
    avatarGradient1Start = Color(0xFFFF0040),
    avatarGradient1End   = Color(0xFFFF4466),
    avatarGradient2Start = Color(0xFF5A0F22),
    avatarGradient2End   = Color(0xFF3A0A16),
    avatarGradient3Start = Color(0xFFFF6600),
    avatarGradient3End   = Color(0xFFFF0040),

    // Aurora
    auroraOrb1         = Color(0xFFFF0040),
    auroraOrb2         = Color(0xFF5A0F22),
    auroraOrb3         = Color(0xFFFF6600),

    // Scrim
    scrimDark          = Color(0x44000000),
    scrimLight         = Color(0x22000000),

    // Feature flags
    isScanlineEnabled  = false,
    isGlitchEnabled    = false,
    isGlassMorphism    = false,
)

// ══════════════════════════════════════════════════════════════════════
// THEME 4 — GHOST  (purple / ethereal)
// ══════════════════════════════════════════════════════════════════════

fun ghostColorScheme() = AlphaColorScheme(
    // Backgrounds
    bgPrimary          = Color(0xFF0D0818),
    bgSecondary        = Color(0xFF150F22),
    bgTertiary         = Color(0xFF1F1533),
    bgChat             = Color(0xFF0D0818),

    // Accent
    accent             = Color(0xFFBF5AF2),
    accentDim          = Color(0x18BF5AF2),   // #bf5af218
    accentGlow         = Color(0x55BF5AF2),   // #bf5af255

    // Text
    textPrimary        = Color(0xFFE6DCF5),
    textSecondary      = Color(0xFF9080AA),
    textMuted          = Color(0xFF5A4A77),

    // Messages
    msgOutStart        = Color(0xFF7C3AED),
    msgOutEnd          = Color(0xFFBF5AF2),
    msgOutText         = Color(0xFF1A0530),
    msgIn              = Color(0xFF1F1533),

    // Borders
    alphaBorder        = Color(0x18BF5AF2),   // #bf5af218
    borderLight        = Color(0x30BF5AF2),   // #bf5af230

    // Semantic
    danger             = Color(0xFFFF4488),
    warning            = Color(0xFFFFAA55),
    success            = Color(0xFF7C3AED),

    // Glass
    glassBg            = Color(0x10FFFFFF),
    glassBgHigh        = Color(0x1AFFFFFF),
    glassBorder        = Color(0x18BF5AF2),
    glassInnerHighlight= Color(0x18BF5AF2),

    // Ephemeral
    ephemeralBlue      = Color(0xFFA78BFA),
    viewOncePink       = Color(0xFFFF4488),

    // Online / badge
    onlineGreen        = Color(0xFF22C55E),
    badgeBg            = Color(0xFFBF5AF2),
    badgeText          = Color(0xFF1A0530),

    // Avatars
    avatarGradient1Start = Color(0xFF7C3AED),
    avatarGradient1End   = Color(0xFFBF5AF2),
    avatarGradient2Start = Color(0xFFA78BFA),
    avatarGradient2End   = Color(0xFFDDD6FE),
    avatarGradient3Start = Color(0xFFF472B6),
    avatarGradient3End   = Color(0xFFBF5AF2),

    // Aurora
    auroraOrb1         = Color(0xFF7C3AED),
    auroraOrb2         = Color(0xFFBF5AF2),
    auroraOrb3         = Color(0xFFA78BFA),

    // Scrim
    scrimDark          = Color(0x33000000),
    scrimLight         = Color(0x1A000000),

    // Feature flags
    isScanlineEnabled  = false,
    isGlitchEnabled    = false,
    isGlassMorphism    = false,
)

// ══════════════════════════════════════════════════════════════════════
// THEME 5 — GLASS  (teal / cyan glass morphism)
// ══════════════════════════════════════════════════════════════════════

fun glassColorScheme() = AlphaColorScheme(
    // Backgrounds
    bgPrimary          = Color(0xFF0A0E1A),
    bgSecondary        = Color(0xBF101424),   // rgba(16,20,36,0.75)
    bgTertiary         = Color(0x991A2038),   // rgba(26,32,56,0.6)
    bgChat             = Color(0xFF080C16),

    // Accent
    accent             = Color(0xFF2DD4BF),
    accentDim          = Color(0x222DD4BF),   // #2dd4bf22
    accentGlow         = Color(0x662DD4BF),   // #2dd4bf66

    // Text
    textPrimary        = Color(0xFFF1F5F9),
    textSecondary      = Color(0xFF94A3B8),
    textMuted          = Color(0xFF64748B),

    // Messages
    msgOutStart        = Color(0xFF2DD4BF),
    msgOutEnd          = Color(0xFF0EA5E9),
    msgOutText         = Color(0xFFFFFFFF),
    msgIn              = Color(0x14FFFFFF),   // rgba(255,255,255,0.08)

    // Borders
    alphaBorder        = Color(0x1FFFFFFF),   // rgba(255,255,255,0.12)
    borderLight        = Color(0x2FFFFFFF),   // rgba(255,255,255,0.18)

    // Semantic
    danger             = Color(0xFFF43F5E),
    warning            = Color(0xFFF59E0B),
    success            = Color(0xFF2DD4BF),

    // Glass surface (primary tokens for Glass theme)
    glassBg            = Color(0x10FFFFFF),   // white 6%
    glassBgHigh        = Color(0x1AFFFFFF),   // white 10%
    glassBorder        = Color(0x1FFFFFFF),   // white 12%
    glassInnerHighlight= Color(0x18FFFFFF),   // white 9%

    // Ephemeral
    ephemeralBlue      = Color(0xFF60A5FA),
    viewOncePink       = Color(0xFFF472B6),

    // Online / badge
    onlineGreen        = Color(0xFF22C55E),
    badgeBg            = Color(0xFF0EA5E9),
    badgeText          = Color(0xFFFFFFFF),

    // Avatars
    avatarGradient1Start = Color(0xFF2DD4BF),
    avatarGradient1End   = Color(0xFF0EA5E9),
    avatarGradient2Start = Color(0xFF6366F1),
    avatarGradient2End   = Color(0xFFA78BFA),
    avatarGradient3Start = Color(0xFFF97066),
    avatarGradient3End   = Color(0xFFFBBF24),

    // Aurora
    auroraOrb1         = Color(0xFF2DD4BF),
    auroraOrb2         = Color(0xFF6366F1),
    auroraOrb3         = Color(0xFFF97066),

    // Scrim
    scrimDark          = Color(0x33000000),
    scrimLight         = Color(0x1A000000),

    // Feature flags
    isScanlineEnabled  = false,
    isGlitchEnabled    = false,
    isGlassMorphism    = true,
)

// ══════════════════════════════════════════════════════════════════════
// THEME 6 — GREY
// ══════════════════════════════════════════════════════════════════════

fun greyColorScheme() = AlphaColorScheme(
 // Backgrounds — neutral greys.
    bgPrimary          = Color(0xFF313338),   // main chat surface
    bgSecondary        = Color(0xFF2B2D31),   // top/bottom bars, sidebars
    bgTertiary         = Color(0xFF383A40),   // input field, tertiary
    bgChat             = Color(0xFF313338),

 // Accent — other messengers blurple.
    accent             = Color(0xFF5865F2),
    accentDim          = Color(0x265865F2),   // blurple ~15%
    accentGlow         = Color(0x665865F2),

    // Text
    textPrimary        = Color(0xFFF2F3F5),
    textSecondary      = Color(0xFFB5BAC1),
    textMuted          = Color(0xFF80848E),

    // Messages — own bubble blurple, incoming a darker grey card.
    msgOutStart        = Color(0xFF5865F2),
    msgOutEnd          = Color(0xFF4752C4),
    msgOutText         = Color(0xFFFFFFFF),
    msgIn              = Color(0xFF383A40),

    // Borders
    alphaBorder        = Color(0x14FFFFFF),
    borderLight        = Color(0x24FFFFFF),

 // Semantic — other messengers red / yellow / green.
    danger             = Color(0xFFED4245),
    warning            = Color(0xFFF0B132),
    success            = Color(0xFF23A55A),

    // Glass fallbacks
    glassBg            = Color(0x10FFFFFF),
    glassBgHigh        = Color(0x1AFFFFFF),
    glassBorder        = Color(0x14FFFFFF),
    glassInnerHighlight= Color(0x18FFFFFF),

    // Ephemeral
    ephemeralBlue      = Color(0xFF00A8FC),
    viewOncePink       = Color(0xFFEB459E),   // other messengers fuchsia

    // Online / badge
    onlineGreen        = Color(0xFF23A55A),
    badgeBg            = Color(0xFF5865F2),
    badgeText          = Color(0xFFFFFFFF),

    // Avatars — blurple / green / fuchsia.
    avatarGradient1Start = Color(0xFF5865F2),
    avatarGradient1End   = Color(0xFF4752C4),
    avatarGradient2Start = Color(0xFF23A55A),
    avatarGradient2End   = Color(0xFF1A8347),
    avatarGradient3Start = Color(0xFFEB459E),
    avatarGradient3End   = Color(0xFF5865F2),

    // Aurora orbs (subtle, theme has no aurora effect)
    auroraOrb1         = Color(0xFF5865F2),
    auroraOrb2         = Color(0xFF4752C4),
    auroraOrb3         = Color(0xFF23A55A),

    // Scrim
    scrimDark          = Color(0x55000000),
    scrimLight         = Color(0x22000000),

    // Feature flags
    isScanlineEnabled  = false,
    isGlitchEnabled    = false,
    isGlassMorphism    = false,
)

// ══════════════════════════════════════════════════════════════════════
// THEME PALETTES — 6 curated themes (4 dark, 2 light), all derived from the
// Grey base via .copy() so every token stays populated. Add more the same way.
// ══════════════════════════════════════════════════════════════════════

/** Midnight — deep navy + teal accent (dark). */
fun midnightColorScheme() = greyColorScheme().copy(
    bgPrimary   = Color(0xFF0E1622),
    bgSecondary = Color(0xFF0A1019),
    bgTertiary  = Color(0xFF152233),
    bgChat      = Color(0xFF0E1622),
    accent      = Color(0xFF2DD4BF),
    accentDim   = Color(0x262DD4BF),
    accentGlow  = Color(0x662DD4BF),
    msgOutStart = Color(0xFF2DD4BF),
    msgOutEnd   = Color(0xFF0EA5A4),
    msgIn       = Color(0xFF152233),
    badgeBg     = Color(0xFF2DD4BF),
    onlineGreen = Color(0xFF34D399),
)

/** Carbon — near-black graphite + warm orange accent (dark). */
fun carbonColorScheme() = greyColorScheme().copy(
    bgPrimary   = Color(0xFF161616),
    bgSecondary = Color(0xFF101010),
    bgTertiary  = Color(0xFF1F1F1F),
    bgChat      = Color(0xFF161616),
    accent      = Color(0xFFFF8A34),
    accentDim   = Color(0x26FF8A34),
    accentGlow  = Color(0x66FF8A34),
    msgOutStart = Color(0xFFFF8A34),
    msgOutEnd   = Color(0xFFE0681E),
    msgIn       = Color(0xFF1F1F1F),
    badgeBg     = Color(0xFFFF8A34),
)

/** Black — true #000 for OLED screens (dark pixels are switched off). */
fun amoledColorScheme() = greyColorScheme().copy(
    bgPrimary   = Color(0xFF000000),
    bgSecondary = Color(0xFF000000),
    bgTertiary  = Color(0xFF161616),
    bgChat      = Color(0xFF000000),
    accent      = Color(0xFF4F8DFF),
    accentDim   = Color(0x264F8DFF),
    accentGlow  = Color(0x664F8DFF),
    msgOutStart = Color(0xFF3F7BFF),
    msgOutEnd   = Color(0xFF2A5FD8),
    msgIn       = Color(0xFF1A1A1A),
    badgeBg     = Color(0xFF4F8DFF),
    alphaBorder = Color(0x1FFFFFFF),
)

/** Violet — dark purple mystique (dark). */
fun violetColorScheme() = greyColorScheme().copy(
    bgPrimary   = Color(0xFF1A1426),
    bgSecondary = Color(0xFF140F1E),
    bgTertiary  = Color(0xFF241B36),
    bgChat      = Color(0xFF1A1426),
    accent      = Color(0xFF9B6BFF),
    accentDim   = Color(0x269B6BFF),
    accentGlow  = Color(0x669B6BFF),
    msgOutStart = Color(0xFF9B6BFF),
    msgOutEnd   = Color(0xFF6E40D8),
    msgIn       = Color(0xFF241B36),
    badgeBg     = Color(0xFF9B6BFF),
)

/** Daylight — clean light theme, blue accent (LIGHT). */
fun daylightColorScheme() = greyColorScheme().copy(
    bgPrimary    = Color(0xFFF2F3F5),
    bgSecondary  = Color(0xFFFFFFFF),
    bgTertiary   = Color(0xFFE7E9ED),
    bgChat       = Color(0xFFF2F3F5),
    accent       = Color(0xFF3B6FF2),
    accentDim    = Color(0x223B6FF2),
    accentGlow   = Color(0x553B6FF2),
    textPrimary  = Color(0xFF1E1F22),
    textSecondary= Color(0xFF4E5058),
    textMuted    = Color(0xFF8A8F98),
    msgOutStart  = Color(0xFF3B6FF2),
    msgOutEnd    = Color(0xFF2F5BD0),
    msgOutText   = Color(0xFFFFFFFF),
    msgIn        = Color(0xFFE7E9ED),
    alphaBorder  = Color(0x14000000),
    borderLight  = Color(0x22000000),
    badgeBg      = Color(0xFF3B6FF2),
    scrimDark    = Color(0x33000000),
    scrimLight   = Color(0x14000000),
)

/** Sand — warm light theme, amber accent (LIGHT). */
fun sandColorScheme() = greyColorScheme().copy(
    bgPrimary    = Color(0xFFF6F1E7),
    bgSecondary  = Color(0xFFFCF8F1),
    bgTertiary   = Color(0xFFEBE3D4),
    bgChat       = Color(0xFFF6F1E7),
    accent       = Color(0xFFC2772B),
    accentDim    = Color(0x22C2772B),
    accentGlow   = Color(0x55C2772B),
    textPrimary  = Color(0xFF3A332A),
    textSecondary= Color(0xFF6B6254),
    textMuted    = Color(0xFF9C9180),
    msgOutStart  = Color(0xFFC2772B),
    msgOutEnd    = Color(0xFFA9621D),
    msgOutText   = Color(0xFFFFFFFF),
    msgIn        = Color(0xFFEBE3D4),
    alphaBorder  = Color(0x14000000),
    borderLight  = Color(0x22000000),
    badgeBg      = Color(0xFFC2772B),
    scrimDark    = Color(0x33000000),
    scrimLight   = Color(0x14000000),
)

// ══════════════════════════════════════════════════════════════════════
// 4 additional themes — Cosmos & Emerald (dark), Sky & Lilac (light).
// Derived from the Grey base via .copy() so every token stays populated.
// ══════════════════════════════════════════════════════════════════════

/** Cosmos — cosmic indigo, vibrant (DARK). */
fun cosmosColorScheme() = greyColorScheme().copy(
    bgPrimary    = Color(0xFF0C0E1E),
    bgSecondary  = Color(0xFF080A16),
    bgTertiary   = Color(0xFF161A30),
    bgChat       = Color(0xFF0C0E1E),
    accent       = Color(0xFF6366F1),
    accentDim    = Color(0x266366F1),
    accentGlow   = Color(0x666366F1),
    msgOutStart  = Color(0xFF6366F1),
    msgOutEnd    = Color(0xFF4F46E5),
    msgIn        = Color(0xFF161A30),
    badgeBg      = Color(0xFF6366F1),
    onlineGreen  = Color(0xFF34D399),
)

/** Emerald — deep green, gemstone (DARK). */
fun emeraldColorScheme() = greyColorScheme().copy(
    bgPrimary    = Color(0xFF081711),
    bgSecondary  = Color(0xFF05100B),
    bgTertiary   = Color(0xFF0E2018),
    bgChat       = Color(0xFF081711),
    accent       = Color(0xFF059669),
    accentDim    = Color(0x26059669),
    accentGlow   = Color(0x66059669),
    msgOutStart  = Color(0xFF059669),
    msgOutEnd    = Color(0xFF047857),
    msgIn        = Color(0xFF0E2018),
    badgeBg      = Color(0xFF059669),
    onlineGreen  = Color(0xFF34D399),
)

/** Sky — airy light theme, sky-blue accent (LIGHT). */
fun skyColorScheme() = greyColorScheme().copy(
    bgPrimary    = Color(0xFFEEF4F8),
    bgSecondary  = Color(0xFFFFFFFF),
    bgTertiary   = Color(0xFFE0EAF1),
    bgChat       = Color(0xFFEEF4F8),
    accent       = Color(0xFF0EA5E9),
    accentDim    = Color(0x220EA5E9),
    accentGlow   = Color(0x550EA5E9),
    textPrimary  = Color(0xFF0F1E2A),
    textSecondary= Color(0xFF43586B),
    textMuted    = Color(0xFF7C8FA0),
    msgOutStart  = Color(0xFF0EA5E9),
    msgOutEnd    = Color(0xFF0284C7),
    msgOutText   = Color(0xFFFFFFFF),
    msgIn        = Color(0xFFE0EAF1),
    alphaBorder  = Color(0x14000000),
    borderLight  = Color(0x22000000),
    badgeBg      = Color(0xFF0EA5E9),
    scrimDark    = Color(0x33000000),
    scrimLight   = Color(0x14000000),
)

/** Lilac — soft light theme, violet-lilac accent (LIGHT). */
fun lilacColorScheme() = greyColorScheme().copy(
    bgPrimary    = Color(0xFFF3EFFA),
    bgSecondary  = Color(0xFFFFFFFF),
    bgTertiary   = Color(0xFFE7DEF3),
    bgChat       = Color(0xFFF3EFFA),
    accent       = Color(0xFF8B5CF6),
    accentDim    = Color(0x228B5CF6),
    accentGlow   = Color(0x558B5CF6),
    textPrimary  = Color(0xFF1F1729),
    textSecondary= Color(0xFF4E4266),
    textMuted    = Color(0xFF847599),
    msgOutStart  = Color(0xFF8B5CF6),
    msgOutEnd    = Color(0xFF7C3AED),
    msgOutText   = Color(0xFFFFFFFF),
    msgIn        = Color(0xFFE7DEF3),
    alphaBorder  = Color(0x14000000),
    borderLight  = Color(0x22000000),
    badgeBg      = Color(0xFF8B5CF6),
    scrimDark    = Color(0x33000000),
    scrimLight   = Color(0x14000000),
)

/** Azure — Material-style dark (#212121 surfaces) + light blue accent (DARK). */
fun azureColorScheme() = greyColorScheme().copy(
    bgPrimary    = Color(0xFF212121),
    bgSecondary  = Color(0xFF1A1A1A),
    bgTertiary   = Color(0xFF2C2C2C),
    bgChat       = Color(0xFF212121),
    accent       = Color(0xFF42A5F5),   // Material light blue 400
    accentDim    = Color(0x2642A5F5),
    accentGlow   = Color(0x6642A5F5),
    msgOutStart  = Color(0xFF42A5F5),
    msgOutEnd    = Color(0xFF1E88E5),   // blue 600
    msgOutText   = Color(0xFFFFFFFF),
    msgIn        = Color(0xFF2C2C2C),
    badgeBg      = Color(0xFF42A5F5),
    onlineGreen  = Color(0xFF66BB6A),
    ephemeralBlue= Color(0xFF42A5F5),
)

// ══════════════════════════════════════════════════════════════════════
// Backward-compatible top-level colour constants
// ══════════════════════════════════════════════════════════════════════
// These default to the Glass theme values so existing code that imports
// them directly continues to compile. New code should prefer reading
// from LocalAlphaColorScheme.current instead.

// ── Accent gradient ───────────────────────────────────────────────
val AccentTeal  = Color(0xFF2DD4BF)
val AccentSky   = Color(0xFF0EA5E9)

// ── Aurora orb colors ─────────────────────────────────────────────
val AuroraTeal   = Color(0xFF2DD4BF)
val AuroraIndigo = Color(0xFF6366F1)
val AuroraCoral  = Color(0xFFF97066)

// ── Dark base ─────────────────────────────────────────────────────
val DarkBase        = Color(0xFF0A0E1A)
val DarkSurface     = Color(0xBF101424)
val DarkSurfaceHigh = Color(0x991A2038)

// ── Glass surface colors ──────────────────────────────────────────
val GlassDarkBg       = Color(0x10FFFFFF)
val GlassDarkBgHigh   = Color(0x1AFFFFFF)
val GlassLightBg      = Color(0x8CFFFFFF)
val GlassLightBgHigh  = Color(0xB8FFFFFF)

// ── Glass borders & highlights ────────────────────────────────────
val GlassBorder         = Color(0x1FFFFFFF)
val GlassInnerHighlight = Color(0x18FFFFFF)

// ── Scrim ─────────────────────────────────────────────────────────
val ScrimDark   = Color(0x33000000)
val ScrimLight  = Color(0x1A000000)

// ── Outgoing bubble gradient ──────────────────────────────────────
val OutgoingGradientStart = Color(0xFF2DD4BF)
val OutgoingGradientEnd   = Color(0xFF0EA5E9)

// ── Incoming bubble ───────────────────────────────────────────────
val IncomingBubbleBg = Color(0x14FFFFFF)

// ── Text hierarchy ────────────────────────────────────────────────
val TextPrimary   = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val TextTertiary  = Color(0xFF64748B)

// ── Semantic / status ─────────────────────────────────────────────
val ErrorRed      = Color(0xFFF43F5E)
val WarningAmber  = Color(0xFFF59E0B)
val SuccessGreen  = Color(0xFF2DD4BF)

// ── Ephemeral indicators ──────────────────────────────────────────
val EphemeralBlue  = Color(0xFF60A5FA)
val ViewOncePink   = Color(0xFFF472B6)

// ── Online status dot ─────────────────────────────────────────────
val OnlineGreen = Color(0xFF22C55E)

// ── Badge ─────────────────────────────────────────────────────────
val BadgeBg   = Color(0xFF0EA5E9)
val BadgeText = Color.White

// ── Avatar gradient presets ───────────────────────────────────────
val AvatarGradient1Start = Color(0xFF2DD4BF)
val AvatarGradient1End   = Color(0xFF0EA5E9)
val AvatarGradient2Start = Color(0xFF6366F1)
val AvatarGradient2End   = Color(0xFFA78BFA)
val AvatarGradient3Start = Color(0xFFF97066)
val AvatarGradient3End   = Color(0xFFFBBF24)

// ── Light theme surfaces (legacy) ────────────────────────────────
val LightBackground       = Color(0xFFF1F5F9)
val LightSurface          = Color(0xFFFFFFFF)
val LightOnSurface        = Color(0xFF0F172A)
val LightOnSurfaceVariant = Color(0xFF64748B)
val LightOutline          = Color(0xFFCBD5E1)

// ── Midnight theme (legacy) ──────────────────────────────────────
val MidnightBase         = Color(0xFF080510)
val MidnightSurface      = Color(0xFF0F0A1E)
val MidnightSurfaceHigh  = Color(0xFF1A1333)
val MidnightOnSurface    = Color(0xFFDDD6FE)
val MidnightOnSurfaceVar = Color(0xFF8B7FA8)
