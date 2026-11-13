package com.alphamessenger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp

// ══════════════════════════════════════════════════════════════════════
// Google Fonts provider
// ══════════════════════════════════════════════════════════════════════

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates     = com.alphamessenger.R.array.com_google_android_gms_fonts_certs
)

// ── Display font ─────────────────────────────────────────────────
private val bricolageGrotesqueFont = GoogleFont("Bricolage Grotesque")
val BricolageGrotesque = FontFamily(
    Font(googleFont = bricolageGrotesqueFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = bricolageGrotesqueFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = bricolageGrotesqueFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = bricolageGrotesqueFont, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = bricolageGrotesqueFont, fontProvider = provider, weight = FontWeight.ExtraBold),
)

// ── Body font ────────────────────────────────────────────────────
private val hankenGrotesqueFont = GoogleFont("Hanken Grotesque")
val HankenGrotesque = FontFamily(
    Font(googleFont = hankenGrotesqueFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = hankenGrotesqueFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = hankenGrotesqueFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = hankenGrotesqueFont, fontProvider = provider, weight = FontWeight.Bold),
)

// ── Mono font (Hacktivist theme) ────────────────────────────────
private val jetBrainsMonoFont = GoogleFont("JetBrains Mono")
val JetBrainsMono = FontFamily(
    Font(googleFont = jetBrainsMonoFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = jetBrainsMonoFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = jetBrainsMonoFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = jetBrainsMonoFont, fontProvider = provider, weight = FontWeight.Bold),
)

// ══════════════════════════════════════════════════════════════════════
// Typography scale
// ══════════════════════════════════════════════════════════════════════

/**
 * Full Material 3 typography scale for AlphaMessenger.
 *
 * Display / headline: [Bricolage Grotesque] — bold, geometric personality
 * Body / label:       [Hanken Grotesque]    — clean, highly legible
 * Hacktivist:         [JetBrains Mono]      — applied dynamically in Theme.kt
 */
val AlphaTypography = Typography(

    // ── Display — hero / onboarding titles ──────────────────────
    displayLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    displaySmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),

    // ── Headline — section headers, screen titles ──────────────
    headlineLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),

    // ── Title — contact name, card titles ──────────────────────
    titleLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,      // 700 — spec: 16.5sp, weight 700
        fontSize = 16.5.sp,
        lineHeight = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = HankenGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = HankenGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),

    // ── Body — message text, previews ──────────────────────────
    bodyLarge = TextStyle(
        fontFamily = HankenGrotesque,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = HankenGrotesque,
        fontWeight = FontWeight.Normal,     // 400 — spec: 14sp, weight 400
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = HankenGrotesque,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ── Label — timestamps, badges ─────────────────────────────
    labelLarge = TextStyle(
        fontFamily = HankenGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = HankenGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
    ),
    labelSmall = TextStyle(
        fontFamily = HankenGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,                  // spec: 11–12sp, alpha 0.5
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    ),
)
