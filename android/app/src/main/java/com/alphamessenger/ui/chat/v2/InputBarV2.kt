@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.alphamessenger.ui.chat.v2

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import com.alphamessenger.R
import com.alphamessenger.ui.chat.fmtMs
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * InputBar V2 (from scratch) — ALWAYS exists, NEVER destroyed.
 *
 * ALL buttons (Emoji/TextField/Attach/Camera/Mic/Send) are ALWAYS present —
 * NO conditionals (if showAttach / if recording / if emoji). The visual pill
 * design is preserved (the user likes it).
 *
 * ## Read-only (per spec)
 * InputBar gets [enabled] = false + [alpha] dimming. The send button area is
 * replaced by [readOnlyContent] (a ReadOnlyStrip). NO separate branch — the
 * InputBar stays composed, just disabled.
 *
 * ## Recording (per spec — overlay, NOT a panel)
 * While [isRecording] is true, a [RecordingPanelV2] overlay appears ON TOP of
 * the InputBar (AnimatedVisibility). The InputBar stays composed underneath
 * (disabled) — its persistent state survives. panelMode=RECORDING → the bottom
 * panel slot is empty (height 0); the recording UI lives entirely in this
 * overlay.
 *
 * ## Hold-to-record gesture (full usual behaviour)
 * press = start, release = send, swipe-left = cancel, swipe-up/tap = lock.
 *
 * Persistent state ([FocusRequester]/[TextFieldValue]) lives in
 * the shared [BottomSurfaceState] — NEVER recreated.
 */
@Composable
fun InputBarV2(
    state: BottomSurfaceState,
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    /** "Send later" in the hold menu of Send (with text). */
    onSendLongPress: (() -> Unit)? = null,
    /** "Send without sound" in the hold menu of Send (with text). */
    onSendSilent: (() -> Unit)? = null,
    onAttach: () -> Unit,
    onEmoji: () -> Unit,
    onCamera: () -> Unit = {},
    forceSend: Boolean = false,
    colors: AlphaColorScheme,
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    onStartRecord: () -> Unit = {},
    onFinishRecord: () -> Unit = {},
    onCancelRecord: () -> Unit = {},
    onTapLockRecord: () -> Unit = {},
    emojiOpen: Boolean = false,
    onInputFocused: () -> Unit = {},
    /** Read-only: when true, the InputBar is disabled + dimmed, and
     *  [readOnlyContent] replaces the send button area. NO separate branch. */
    readOnly: Boolean = false,
    readOnlyContent: @Composable (() -> Unit)? = null,
    /** Non-null when this chat has scheduled messages: a calendar with a dot
     *  at the right of the field opens them. */
    onOpenScheduled: (() -> Unit)? = null,
    /** Sits on the chat's frosted glass: no fill of its own. */
    onGlass: Boolean = false,
    /** An answer is being written for this chat: with the field empty, the button stops it. */
    onStop: (() -> Unit)? = null,
    /** Slow mode: seconds until the next message may go. The field stays
     *  open (the next one can be typed); the send button counts down. */
    cooldownSeconds: Long = 0,
    /** Holding the camera in the field records a video message (a tap still
     *  opens the camera). Null = the camera only opens. */
    onRoundStart: (() -> Unit)? = null,
    /** The hold ended: send, cancel (slid left) or keep recording hands-free (slid up). */
    onRoundEnd: (RoundEnd) -> Unit = {},
    /** Group members offered after "@". Empty = no mentions here. */
    mentionCandidates: List<MentionCandidate> = emptyList(),
    /** A caption being edited keeps the caption limit: past it the count turns red and Send waits. */
    charLimit: Int? = null,
) {
    // Mirror the external String `value` into the persistent TextFieldValue.
    LaunchedEffect(value) {
        if (value != state.textFieldValue.text) {
            state.textFieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }

    val hasText = value.isNotBlank() || forceSend
    val cooling = cooldownSeconds > 0
    val overLimit = charLimit != null && value.length > charLimit
    val cooldownLabel = "%d:%02d".format(cooldownSeconds / 60, cooldownSeconds % 60)
    val enabled = !readOnly && !isRecording
    // Read-only: the bar stays composed (its state persists) but is not shown;
    // the status strip takes its exact place — same height, no buttons.
    val barAlpha = if (readOnly) 0f else 1f

    // BATTERY: the mic-blink InfiniteTransition used to run for the ENTIRE life
    // of the InputBar (i.e. whenever any chat is open) — a per-frame state write
    // forever, even though the value is only used while recording. Now it is
    // created ONLY while recording; the rest of the time there is no animation
    // clock at all. `1f` is the steady (non-blinking) alpha.
    val micAlpha = if (isRecording) recordingBlinkAlpha() else 1f

 // classic look: the mic button grows + gives a haptic tick the moment you
    // press-and-hold to record.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var micPressed by remember { mutableStateOf(false) }
    // Finger travel while holding (px): left = towards cancel, up = towards lock.
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    val cancelPx = with(density) { CANCEL_DISTANCE.toPx() }
    val lockPx = with(density) { LOCK_DISTANCE.toPx() }
    val micScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isRecording || micPressed) MIC_HELD_SCALE else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "micScale",
    )
    // The button follows the finger and springs back when released.
    val micDx by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (micPressed) dragX else 0f,
        animationSpec = if (micPressed) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.6f),
        label = "micDx",
    )
    val micDy by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (micPressed) dragY else 0f,
        animationSpec = if (micPressed) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.6f),
        label = "micDy",
    )
    val cancelProgress = (-micDx / cancelPx).coerceIn(0f, 1f)
    val lockProgress = (-micDy / lockPx).coerceIn(0f, 1f)
    val stopping = onStop != null && !hasText
    val micModifier = if (hasText || readOnly || stopping) Modifier else Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown()
                val downTime = System.currentTimeMillis()
                dragX = 0f; dragY = 0f
                micPressed = true
                strongTick(ctx, 45)
                onStartRecord()
                var cancelled = false
                var locked = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) break
                    val dx = (ch.position.x - down.position.x).coerceIn(-cancelPx * 1.1f, 0f)
                    val dy = (ch.position.y - down.position.y).coerceIn(-lockPx * 1.1f, 0f)
 // One direction at a time, as messengers usually do: the larger pull wins.
                    if (-dx >= -dy) { dragX = dx; dragY = 0f } else { dragY = dy; dragX = 0f }
                    if (!cancelled && -dragX >= cancelPx) { cancelled = true; strongTick(ctx, 30); break }
                    if (!locked && -dragY >= lockPx) { locked = true; strongTick(ctx, 30); break }
                    ch.consume()
                }
                micPressed = false
                dragX = 0f; dragY = 0f
                val heldMs = System.currentTimeMillis() - downTime
                when {
                    cancelled -> onCancelRecord()
                    locked || heldMs < 350 -> onTapLockRecord()
                    else -> onFinishRecord()
                }
            }
        }
    }

    // clipToBounds normally keeps button visuals inside the bar — BUT while
    // recording / pressing the mic the enlarged button must be allowed to grow
    // beyond the bar (else it gets clipped/hidden).
    // While the mic is held or recording, everything else on the bar steps
    // back: the overlay covers the bar only up to the enlarged button, and a
    // sliver of the camera button used to show in the gap.
    val othersAlpha = if (!hasText && (micPressed || isRecording)) 0f else 1f
    // Two rows: once the text needs a second line the field takes the whole
    // width and its buttons move to a row under the text. Latched until the
    // text is cleared — the wider field must not bounce it back to one row.
    var twoRow by remember { mutableStateOf(false) }
    LaunchedEffect(value.isBlank()) { if (value.isBlank()) twoRow = false }
    val sendBg = when {
        isRecording -> Color(0xFFEB5757)
        hasText && (cooling || overLimit) -> colors.textMuted
        hasText -> colors.accent
        stopping -> colors.accent
        else -> Color(0xFF34C759)
    }
    var sendMenu by remember { mutableStateOf(false) }
    val hasSendMenu = onSendLongPress != null || onSendSilent != null
    val sendClick = if (hasText) Modifier.combinedClickable(
        enabled = !isSending && !cooling && !overLimit,
        onClick = { onSend() },
        onLongClick = if (hasSendMenu) ({
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            sendMenu = true
        }) else null,
    ) else if (stopping) Modifier.combinedClickable(onClick = { onStop?.invoke() }) else Modifier
    val sendOptions: @Composable () -> Unit = {
        SendOptionsMenu(
            open = sendMenu && hasText,
            colors = colors,
            onDismiss = { sendMenu = false },
            onUncompressed = null,
            onLater = onSendLongPress,
            onSilent = onSendSilent,
        )
    }
    val emojiIcon = if (emojiOpen) AlphaIcons.Outlined.Keyboard else com.alphamessenger.ui.components.AlphaIcons.Expressions
    val emojiLabel = if (emojiOpen) stringResource(R.string.preview_keyboard) else stringResource(R.string.chat_emoji)
    val emojiTint = if (emojiOpen) colors.accent else colors.textMuted
    Box(
        modifier = Modifier.fillMaxWidth()
            // The bar sits over the chat: a touch anywhere on it, field edges included,
            // stays here instead of falling through to the list behind.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .then(if (micPressed || isRecording) Modifier else Modifier.clipToBounds())
            .then(if (onGlass) Modifier else Modifier.background(colors.bgSecondary)),
    ) {
        // ── The InputBar (ALWAYS composed) ─────
        Row(
            modifier = Modifier.fillMaxWidth().alpha(barAlpha)
                // While recording the row (only the mic is visible) draws ABOVE the
                // "slide to cancel" strip, so the dragged mic slides over it.
                .then(if (!hasText && (micPressed || isRecording)) Modifier.zIndex(2f) else Modifier)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The field: one rounded surface. The text keeps ONE place in the tree in
            // both layouts, so the keyboard connection survives the switch.
            Column(
                modifier = Modifier.weight(1f).alpha(othersAlpha)
                    .clip(RoundedCornerShape(22.dp)).background(colors.bgTertiary.copy(alpha = if (onGlass) 0.55f else 1f)),
            ) {
                // Text selected → formatting; "@…" before the cursor → members.
                val tfv = state.textFieldValue
                fun replace(v: TextFieldValue) {
                    state.textFieldValue = v
                    if (v.text != value) onValueChange(v.text)
                }
                val mention = if (enabled && mentionCandidates.isNotEmpty()) mentionQuery(tfv) else null
                val matches = mention?.let { (_, q) ->
                    mentionCandidates.filter { it.token.startsWith(q, true) || it.name.contains(q, true) }.take(5)
                }.orEmpty()
                // Always composed (empty when there is nothing to offer): the nodes
                // before the text field never come and go around it.
                MentionList(if (mention != null) matches else emptyList(), colors) { c ->
                    mention?.let { replace(insertMention(tfv, it.first, c)) }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    if (!twoRow) FieldIcon(emojiIcon, emojiLabel, emojiTint, enabled, onEmoji)
                    // The platform field: selection, its menu (with formatting), scrolling
                    // under a dragged handle and the scroll bar all come from the system.
                    ComposerField(
                        state = state,
                        enabled = enabled,
                        hint = if (cooling) stringResource(R.string.cmt_slow_next, cooldownLabel) else stringResource(R.string.msg_fallback_title),
                        colors = colors,
                        twoRow = twoRow,
                        maxHeight = MAX_FIELD_HEIGHT,
                        onChange = { replace(it) },
                        onTapped = onInputFocused,
                        onMultiline = { if (!twoRow) twoRow = true },
                        modifier = Modifier.weight(1f),
                    )
                    if (!twoRow) {
                        if (onOpenScheduled != null) ScheduledIcon(colors, enabled, onOpenScheduled)
                        FieldIcon(AlphaIcons.Outlined.AttachFile, stringResource(R.string.chat_attach_a11y), colors.textMuted, enabled, onAttach)
                        // Camera stays put: nothing in the field moves while typing.
                        // Tap: the camera. Hold: a video message.
                        CameraFieldIcon(colors.textMuted, enabled, onCamera, onRoundStart, onRoundEnd)
                    }
                }
                if (twoRow) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FieldIcon(emojiIcon, emojiLabel, emojiTint, enabled, onEmoji, height = 40.dp)
                        FieldIcon(AlphaIcons.Outlined.AttachFile, stringResource(R.string.chat_attach_a11y), colors.textMuted, enabled, onAttach, height = 40.dp)
                        FieldIcon(AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.perm_camera), colors.textMuted, enabled, onCamera, height = 40.dp)
                        if (onOpenScheduled != null) ScheduledIcon(colors, enabled, onOpenScheduled)
                        Spacer(Modifier.weight(1f))
                        // Length of a long text (a text past one message goes out in parts).
                        val remaining = charLimit?.let { it - value.length }
                        if (remaining != null && remaining <= 200) Text(
                            "$remaining",
                            color = if (remaining < 0) Color(0xFFEB5757) else colors.textMuted,
                            fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.padding(end = 8.dp),
                        ) else if (value.length >= 100) Text(
                            pluralStringResource(R.plurals.composer_chars, value.length, value.length),
                            color = colors.textMuted,
                            fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(sendBg).then(sendClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (cooling) Text(cooldownLabel, color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                            else Icon(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.common_send), tint = Color.White, modifier = Modifier.size(20.dp))
                            if (twoRow) sendOptions()
                        }
                    }
                }
            }

            // Send / Mic (one row only) — OR the read-only strip's room.
            if (!twoRow) {
                if (readOnly) {
                    Spacer(Modifier.size(48.dp))
                } else {
                    Box(
                        modifier = micModifier.size(48.dp)
                            // While pressed the enlarged mic must sit ABOVE the other
                            // input panels (otherwise its left edge slides behind them).
                            .then(if (!hasText && (micPressed || isRecording)) Modifier.zIndex(6f) else Modifier)
                            .then(if (!hasText) Modifier.graphicsLayer {
                                translationX = micDx
                                translationY = micDy
                                scaleX = micScale; scaleY = micScale
                            } else Modifier)
                            .clip(CircleShape)
                            .background(sendBg)
                            .then(sendClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (stopping) Box(
                            Modifier.size(15.dp).clip(RoundedCornerShape(3.dp)).background(Color.White),
                        ) else if (hasText && cooling) Text(
                            cooldownLabel, color = Color.White, fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1,
                        ) else Icon(
                            imageVector = if (hasText) AlphaIcons.AutoMirrored.Outlined.Send else AlphaIcons.Outlined.Mic,
                            contentDescription = if (hasText) stringResource(R.string.common_send) else stringResource(R.string.voice_note_label),
                            tint = if (isRecording) Color.White.copy(alpha = micAlpha) else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        if (!twoRow) sendOptions()
                    }
                }
            }
        }

        // ── Read-only strip: exactly over the bar, full width, one line ──
        if (readOnly && readOnlyContent != null) {
            Box(Modifier.matchParentSize().padding(horizontal = 6.dp, vertical = 8.dp)) { readOnlyContent() }
        }

        // ── Recording overlay (per spec — overlay, NOT a panel) ───────────
 // usual behaviour: covers the bar EXCEPT the mic button itself —
        // the button stays visible (red, blinking) under the user's finger.
        // Covered area = everything left of the button (edge padding 6dp +
        // 48dp button = 54dp kept clear on the right).
        // Only while the finger holds the mic: a locked recording (tap, or slid up) has its own bar,
        // and on its cancel this strip must not flash up for the moment the recording still runs.
        AnimatedVisibility(
            visible = isRecording && micPressed,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
        ) {
            Row(
                // end padding keeps the ENLARGED (1.4×) mic fully clear: button
                // is 48dp @ 6dp edge inset, scaled it reaches ~64dp from the right,
                // so we clear 68dp — its left edge no longer slides under this bar.
                modifier = Modifier.fillMaxSize().padding(end = 76.dp)
                    .then(if (onGlass) Modifier else Modifier.background(colors.bgSecondary)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(AlphaIcons.Outlined.Mic, null, tint = Color(0xFFEB5757).copy(alpha = micAlpha), modifier = Modifier.size(22.dp))
                Text(fmtMs(recordingSeconds * 1000), color = colors.textPrimary, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.chat_slide_to_cancel),
                    color = colors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        translationX = micDx * 0.6f
                        alpha = 1f - cancelProgress
                    },
                )
            }
        }

        // Lock hint: rises above the held mic; pulling up fills it.
        if (micPressed && isRecording && !hasText) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 10.dp)
                    .graphicsLayer {
                        translationY = -with(density) { 96.dp.toPx() } + micDy * 0.5f
                        alpha = 1f - cancelProgress
                    }
                    .size(width = 40.dp, height = 64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.bgSecondary)
                    .border(1.dp, colors.alphaBorder, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (lockProgress > 0.9f) AlphaIcons.Outlined.Lock else AlphaIcons.Outlined.LockOpen, null,
                    tint = if (lockProgress > 0.9f) colors.accent else colors.textMuted,
                    modifier = Modifier.size(20.dp).graphicsLayer { translationY = -lockProgress * 10f },
                )
            }
        }
    }
}

private const val MIC_HELD_SCALE = 1.7f
private val CANCEL_DISTANCE = 120.dp
private val LOCK_DISTANCE = 90.dp

/** A firm tick (the default haptic is barely felt on many phones). */
private fun strongTick(ctx: android.content.Context, ms: Long) {
    runCatching {
        val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        vib.vibrate(android.os.VibrationEffect.createOneShot(ms, 255))
    }
}

/** The recording-indicator blink alpha. Its InfiniteTransition exists ONLY
 *  while this composable is in composition (i.e. only while recording), so the
 *  animation clock stops the instant recording ends — no idle battery cost. */
@Composable
private fun recordingBlinkAlpha(): Float {
    val blink = rememberInfiniteTransition(label = "rec")
    val a by blink.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "mic",
    )
    return a
}

/** Nine lines of text, then the field scrolls. */
private val MAX_FIELD_HEIGHT = (9 * 20 + 24).dp

/** An icon button inside the field (no circle of its own). */
@Composable
private fun FieldIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp = 44.dp,
) {
    Box(
        modifier = Modifier.size(width = 40.dp, height = height)
            .clip(RoundedCornerShape(20.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** How holding the camera ended. */
enum class RoundEnd { SEND, CANCEL, LOCK }

/**
 * The camera in the field: a tap opens the camera; a hold (from 0.3 s) records
 * a video message — let go to send, slide left to cancel, slide up to go on
 * hands-free.
 */
@Composable
private fun CameraFieldIcon(
    tint: Color,
    enabled: Boolean,
    onTap: () -> Unit,
    onHoldStart: (() -> Unit)?,
    onHoldEnd: (RoundEnd) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cancelPx = with(density) { CANCEL_DISTANCE.toPx() }
    val lockPx = with(density) { LOCK_DISTANCE.toPx() }
    val currentTap by androidx.compose.runtime.rememberUpdatedState(onTap)
    val currentStart by androidx.compose.runtime.rememberUpdatedState(onHoldStart)
    val currentEnd by androidx.compose.runtime.rememberUpdatedState(onHoldEnd)
    Box(
        modifier = Modifier.size(width = 40.dp, height = 44.dp)
            .clip(RoundedCornerShape(20.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var released = false
                    val up = withTimeoutOrNull(300) { waitForUpOrCancellation().also { released = true } }
                    if (released) { if (up != null) currentTap(); return@awaitEachGesture }
                    val start = currentStart
                    if (start == null) { currentTap(); return@awaitEachGesture }
                    strongTick(ctx, 45)
                    start()
                    var end = RoundEnd.SEND
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) break
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        if (-dx >= cancelPx) { end = RoundEnd.CANCEL; strongTick(ctx, 30); break }
                        if (-dy >= lockPx) { end = RoundEnd.LOCK; strongTick(ctx, 30); break }
                        ch.consume()
                    }
                    currentEnd(end)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.perm_camera), tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Calendar with a dot: this chat has messages waiting for their time. */
@Composable
private fun ScheduledIcon(colors: AlphaColorScheme, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(width = 40.dp, height = 44.dp).clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AlphaIcons.Outlined.CalendarMonth,
            contentDescription = stringResource(R.string.schedule_list_title),
            tint = colors.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 7.dp).size(7.dp)
                .clip(CircleShape).background(colors.accent),
        )
    }
}
