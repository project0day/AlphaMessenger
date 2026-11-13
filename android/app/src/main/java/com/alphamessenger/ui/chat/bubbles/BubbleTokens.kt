package com.alphamessenger.ui.chat.bubbles

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Alpha Bubble 2.0 — design tokens.
 *
 * SINGLE source of geometry for bubbles. No magic numbers in bubble files — every
 * size/radius/padding comes from here. See docs/BUBBLE_DESIGN_SPEC.md.
 *
 * Hybrid: 70% other messengers geometry (symmetric padding, 7/7 rhythm) + 30% other messengers media
 * UX (footer pill over media) + 100% Alpha identity (our tail, colours, themes).
 */
object BubbleTokens {
    // --- Shape ---
    val cornerRadius = 18.dp        // bubble corner (reference == Alpha — identity)
    val collapseRadius = 6.dp       // grouped-run edge (softer than 4dp)
    val tailWidth = 7.dp
    val tailHeight = 14.dp

    // --- Text / content padding (symmetric — outgoing bubbles must not shift left) ---
    val textPadH = 11.dp            // flat-side inner padding, BOTH sides
    val textPadV = 7.dp             // top & bottom (other messengers 7/7 rhythm)
    val tailSidePad = textPadH + tailWidth   // 17dp — tail side, so content stays 10dp from the flat body edge

    /**
     * THE single content-padding rule for EVERY bubble (text/image/video/file/…).
     *
     * The tail (BubbleShape, one glued Path) carves tailWidth out of ONE side, so
     * content on that side gets tailWidth extra padding to clear it; the flat side uses
     * textPadH. A tailless (mid-run) bubble is symmetric — MessageLayout shifts it inward
     * by tailWidth so its body edge still lines up with the tailed bubbles in the run.
     */
    fun contentPadding(isOutgoing: Boolean, showTail: Boolean): PaddingValues = PaddingValues(
        start = if (!isOutgoing && showTail) tailSidePad else textPadH,
        end = if (isOutgoing && showTail) tailSidePad else textPadH,
        top = textPadV,
        bottom = textPadV,
    )

    // --- Compact media/card bubbles (file, voice, album, location, link) ---
    /** Small even inset; the tail side reserves [compactPad] + [tailWidth] so the
     *  content keeps the same border on every side and the tail lands outside it. Same
     *  rule as [contentPadding] but with the compact base, so these cards share ONE
     *  source of truth instead of repeating `4.dp` / `4.dp + 7.dp` in five places. */
    val compactPad = 4.dp
    fun compactPadding(
        isOutgoing: Boolean,
        showTail: Boolean,
        top: Dp = compactPad,
        bottom: Dp = compactPad,
    ): PaddingValues = PaddingValues(
        start = if (!isOutgoing && showTail) compactPad + tailWidth else compactPad,
        end = if (isOutgoing && showTail) compactPad + tailWidth else compactPad,
        top = top,
        bottom = bottom,
    )

    // --- Media ---
    val mediaCorner = 12.dp         // aligned with OverlaySpec.CORNER
    val mediaMaxW = 240.dp          // reference == Alpha
    val mediaMaxH = 320.dp
    val mediaCondensedH = 150.dp    // video / condensed default
    val mediaMinSolo = 150.dp
    // Bubble content max width when it wraps fixed-size media (media + both paddings).
    val mediaBubbleMaxW = mediaMaxW + textPadH + tailSidePad   // 267dp

    // --- Footer (time + status) ---
    val footerTimeSp = 12           // the time under every bubble
    val footerEditSp = 10
    /** The edited pencil: a little under the time's cap height. */
    val footerEditIcon = 11.dp
 /** classic translucent pill behind time/status when drawn OVER media. */
    val footerPillBg = Color.Black.copy(alpha = 0.38f)
    val footerPillCorner = 10.dp
    val footerPillPadH = 6.dp
    val footerPillPadV = 2.dp
    val footerPillInset = 6.dp      // distance from the media's bottom-end corner
    val footerOnMediaText = Color.White.copy(alpha = 0.95f)
    // --- Shared BubbleFooter (time · "edited" · ticks) — no magic numbers ---
    val footerTopPad = 2.dp             // content → footer gap (inline)
    val footerEndInset = 2.dp           // footer → bubble edge, same for all types
    val footerEditedGap = 4.dp          // "edited" → time gap
    val footerTickGap = 4.dp            // time → ticks gap
    val footerTimeAlpha = 0.7f          // time alpha over bubble text background
    val footerEditedAlpha = 0.65f
    val footerOnMediaEditedAlpha = 0.8f // "edited" in the pill over media

    // --- TickMark — the single delivery indicator ---
    val tickWidth = 18.dp           // one box for every state: no footer shift on change
    val tickHeight = 12.dp
    val tickAlpha = 0.6f            // sending/sent/delivered tint relative to text; read is full

    // --- Sender name (group chats) ---
    val senderNameSp = 13
    val senderNameH = 10.dp
    val senderNameV = 2.dp

    // --- Caption ---
    val captionSp = 16
    val captionLineSp = 21

    // --- Text bubble (CompactBubbleLayout) ---
    val textBubbleMaxW = 320.dp     // text bubble content cap
    val textBodySp = 16             // message text (16 like the reference apps)
    val textBodyLineSp = 21
    val blockGapSender = 2.dp       // sender name → next block
    val blockGapReply = 4.dp        // reply/forward → text
    val textStatusGap = 8.dp        // text ↔ inline time on the last line
    val minBubbleWidth = 85.dp      // so a tiny message + time never collapses
    /** How wide a reply quote may make a bubble on its own (it then fills the bubble). */
    val quoteMaxNaturalWidth = 260.dp
}

/** The narrowest a single photo/video bubble gets (dp): a tall picture is cropped
 *  in height instead of turning into a strip with a one-word-per-line caption. */
const val MEDIA_MIN_W = 230f
