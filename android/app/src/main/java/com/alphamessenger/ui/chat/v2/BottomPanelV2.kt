package com.alphamessenger.ui.chat.v2

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.util.TrustedTime
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the panel slot is currently SHOWING (≠ panelMode — see below). */
private enum class PanelContentV2 { EMPTY, EMOJI }

/**
 * THE ONE bottom panel slot (unified V2).
 *
 * ONE Box whose height = [BottomSurfaceState.bottomPanelHeight] and whose
 * CONTENT follows the mode. The slot itself NEVER disappears.
 *
 * ## Content persistence (no blank flashes)
 * The shown content is NOT naively `panelMode`:
 *  - emoji→keyboard swap: mode is already KEYBOARD but the slot is still held
 *    while the keyboard rises — the emoji grid stays visible under the rising
 *    keyboard, no blank panel;
 *  - close animation: mode is already NONE but the slot is still sliding down
 *    — the last content stays visible during the slide;
 *  - only when the slot is fully covered by the IME (or fully closed) does the
 *    content switch to empty — an invisible switch.
 *
 * ## Drag-to-resize (the grabber)
 * EMOJI renders a grabber pill at the top. Dragging it resizes the
 * panel 1:1 with the finger; release settles to the nearest comfortable anchor
 * (keyboard height / 62% / max) with a soft spring + a haptic tick; dragging
 * below the close threshold dismisses the panel.
 */
@Composable
fun BottomPanelV2(
    state: BottomSurfaceState,
    colors: AlphaColorScheme,
    onEmojiPick: (String) -> Unit,
    onEmojiBackspace: () -> Unit,
    onGifSend: (com.alphamessenger.ui.gif.GifEntry) -> Unit,
    onStickerSend: (String, com.alphamessenger.ui.sticker.Sticker) -> Unit,
    repository: com.alphamessenger.data.AlphaCoreRepository,
) {
    val height = state.bottomPanelHeight()
    val mode = state.panelMode

    // Last real content — survives KEYBOARD/NONE so it stays visible during
    // the IME-rise (hold) and the close slide.
    var lastContent by remember { mutableStateOf(PanelContentV2.EMPTY) }
    // Emoji or GIF — kept while the chat is open.
    var exprTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    if (mode == PanelMode.EMOJI && lastContent != PanelContentV2.EMOJI) lastContent = PanelContentV2.EMOJI

    val slotVisibleAboveIme = height > state.ownKeyboardDp + 4.dp
    val shown = when {
        mode == PanelMode.EMOJI -> lastContent
        slotVisibleAboveIme -> lastContent
        else -> PanelContentV2.EMPTY
    }

    Box(
        modifier = Modifier.fillMaxWidth().height(height)
            .clipToBounds().background(colors.bgSecondary),
    ) {
        AnimatedContent(
            targetState = shown,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "bottomPanel",
        ) { content ->
            when (content) {
                PanelContentV2.EMPTY -> Box(Modifier.fillMaxSize())
                PanelContentV2.EMOJI -> Column(Modifier.fillMaxSize()) {
                    PanelGrabber(state, colors, onDismiss = { state.closeEmoji() })
                    Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0 to R.string.panel_tab_emoji, 1 to R.string.panel_tab_stickers, 2 to R.string.panel_tab_gif).forEach { (tab, label) ->
                            val on = exprTab == tab
                            Box(
                                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                                    .background(if (on) colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { exprTab = tab }.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(label), color = if (on) androidx.compose.ui.graphics.Color.White else colors.textSecondary,
                                    fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (exprTab == 1) com.alphamessenger.ui.sticker.StickerPanel(
                        colors = colors,
                        repo = repository,
                        onSend = onStickerSend,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) else if (exprTab == 2) com.alphamessenger.ui.gif.GifPanel(
                        colors = colors,
                        repo = repository,
                        onSend = onGifSend,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) else EmojiPanelV2(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors = colors,
                        onPick = onEmojiPick,
                        onBackspace = onEmojiBackspace,
                    )
                }
            }
        }
    }
}

/**
 * The grabber pill — drag up/down to resize the panel.
 * Release settles to the nearest anchor; below the close threshold dismisses.
 */
@Composable
private fun PanelGrabber(
    state: BottomSurfaceState,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        val mode = state.panelMode
                        if (mode != PanelMode.EMOJI) return@detectVerticalDragGestures
                        val current = state.emojiPanelHeight
                        if (current < state.closeThreshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                            return@detectVerticalDragGestures
                        }
                        val target = state.dragAnchors().minByOrNull { abs((it - current).value) } ?: return@detectVerticalDragGestures
                        haptic.performHapticFeedbackSafely()
                        scope.launch {
                            animate(current.value, target.value) { v, _ -> state.emojiDragHeight = v.dp }
                            // Settled back to the default → return to kbFloor tracking.
                            if (target == state.kbFloor) state.emojiDragHeight = null
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    state.onPanelDragBy(with(density) { (-dragAmount).toDp() })
                }
            }
            .padding(top = 8.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.width(36.dp).height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.textMuted.copy(alpha = 0.45f)),
        )
    }
}

private fun androidx.compose.ui.hapticfeedback.HapticFeedback.performHapticFeedbackSafely() {
    runCatching { performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

/** Read-only state folded into a small model. */
data class ReadOnlyModel(
    val peerBlocked: Boolean,
    val removedFromGroup: Boolean,
    val chatClosedUntilMe: Long,
    val writeRestricted: Boolean,
    val mutedUntilMe: Long,
    val onUnblock: () -> Unit,
)

/**
 * Why this chat cannot be written to — drawn in place of the input bar, at
 * its height: one rounded line with an icon (and "Unblock" for a blocked peer).
 */
@Composable
fun ReadOnlyStripV2(colors: AlphaColorScheme, model: ReadOnlyModel) {
    val now = TrustedTime.nowSeconds()
    fun fmt(sec: Long) = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))
    val (icon, text) = when {
        model.peerBlocked -> com.alphamessenger.ui.icons.AlphaIcons.Outlined.Block to stringResource(R.string.chat_blocked_notice)
        model.removedFromGroup -> com.alphamessenger.ui.icons.AlphaIcons.Outlined.GroupOff to stringResource(R.string.chat_group_unavailable)
        model.chatClosedUntilMe > now -> com.alphamessenger.ui.icons.AlphaIcons.Outlined.Lock to stringResource(R.string.chat_closed_until, fmt(model.chatClosedUntilMe))
        model.writeRestricted -> com.alphamessenger.ui.icons.AlphaIcons.Outlined.Campaign to stringResource(R.string.chat_admins_only)
        else -> com.alphamessenger.ui.icons.AlphaIcons.Outlined.VolumeOff to stringResource(R.string.chat_muted_until, fmt(model.mutedUntilMe))
    }
    Row(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(colors.bgTertiary)
            .padding(start = 16.dp, end = if (model.peerBlocked) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (model.peerBlocked) Arrangement.Start else Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text, color = colors.textSecondary, fontSize = 14.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = if (model.peerBlocked) Modifier.weight(1f) else Modifier,
        )
        if (model.peerBlocked) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.accent.copy(alpha = 0.14f))
                    .clickable { model.onUnblock() }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.chat_unblock), color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
