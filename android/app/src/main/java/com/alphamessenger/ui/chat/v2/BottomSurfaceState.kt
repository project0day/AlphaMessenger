package com.alphamessenger.ui.chat.v2

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The ONE panel mode. Exactly 5 values — per spec.
 */
enum class PanelMode {
    NONE,       // nothing — chat fills the screen
    KEYBOARD,   // the IME fills the bottom panel
    EMOJI,      // the emoji grid fills the bottom panel
    ATTACH,     // the attach panel fills the bottom panel
    RECORDING,  // locked recording — overlay over InputBar
}

/**
 * THE single permanent owner of the bottom surface (unified V2).
 *
 * ## One derived mode (single source of truth)
 * [panelMode] is DERIVED, never set directly:
 *   - ViewModel truth (attach open / locked recording) wins;
 *   - otherwise the local keyboard/emoji choice ([inputMode]).
 * The host calls [syncExternal] every composition to fold the ViewModel truth
 * in. Setting inputMode → the whole UI rebuilds itself. Pure state.
 *
 * ## THE ONE height formula (no spacers, no compensators)
 * ```
 * NONE      → 0
 * KEYBOARD  → max(ime, holdHeight)      // rides the IME 1:1; holdHeight keeps
 *                                       // the slot tall during an emoji→kb
 *                                       // swap so the InputBar NEVER dips
 * EMOJI     → max(emojiHeight, ime)     // holds at the emoji height while the
 *                                       // IME hides underneath (kb→emoji swap)
 * ATTACH    → attachHeight + ime        // caption keyboard lifts the panel;
 *           (enter-hold: max(attach, ime) while the old IME slides away)
 * RECORDING → keepsKb ? ime : 0
 * ```
 * KEYBOARD and EMOJI resolve to the SAME value during swaps → the target never
 * moves → the InputBar never jumps. This is the other messengers mechanism.
 *
 * ## Animation policy (the bump fix)
 * The height is ONE [Animatable]:
 *   - target changed because the MODE changed → smooth spring (open/close);
 *   - target changed within the same mode (live IME / finger drag) → snapTo,
 *     glued 1:1 — a spring would lag the system IME animation by 1-2 frames
 *     and the InputBar would dip into the keyboard (bump/jerk).
 *
 * ## Drag-to-resize
 * EMOJI and ATTACH both OPEN at the keyboard height ([kbFloor]) — every panel
 * opens the SAME size. The grabber lets the user drag the panel to ANY height
 * between [minPanelHeight] and [maxPanelHeight]; release settles to the
 * nearest comfortable anchor; dragging below the close threshold dismisses.
 *
 * ## settledKbHeight
 * Captured when the IME is stable for 150ms above 120dp (allows both grow and
 * shrink — keyboards change height with suggestion strips). Remembered
 * for good, per orientation. Used as the default panel height for EMOJI/ATTACH.
 */
@Stable
class BottomSurfaceState internal constructor(
    val screenHeightDp: Dp,
    persistedKbHeightDp: Dp,
) {
    // --- Live inputs ---

    /** The keyboard's height as it slides, never past where it settles. */
    val keyboard = KeyboardMotion()

    /** Per-frame live IME height above the nav bar (read-only measurement). */
    var imeContentDp: Dp by mutableStateOf(0.dp)
        internal set

    /** Local keyboard/emoji choice — only NONE / KEYBOARD / EMOJI. */
    internal var inputMode: PanelMode by mutableStateOf(PanelMode.NONE)

    /** ViewModel truth, folded in via [syncExternal]. */
    internal var attachOpen: Boolean by mutableStateOf(false)
    internal var recordingLocked: Boolean by mutableStateOf(false)

    // --- Persistent input-widget state (created once, NEVER recreated) ---

    /** The composer's text field while it is on screen. */
    internal var field: android.widget.EditText? = null

    /** Cursor into the field and the keyboard up. */
    fun focusInput() { field?.focusAndShowKeyboard() }

    /** Keyboard down and the cursor out of the field. */
    fun releaseInput() { field?.hideKeyboardAndBlur() }

    var textFieldValue: TextFieldValue by mutableStateOf(TextFieldValue("", TextRange(0)))
        internal set

    /** Whether the keyboard was up when locked recording started. */
    var recordingKeepsKb: Boolean by mutableStateOf(false)

    // --- THE derived mode ---

    val panelMode: PanelMode
        get() = when {
            attachOpen -> PanelMode.ATTACH
            recordingLocked -> PanelMode.RECORDING
            else -> inputMode
        }

    // --- Captured keyboard height ---

    internal var settledKbHeight: Dp by mutableStateOf(persistedKbHeightDp)

    /** Default panel height before the keyboard has ever been measured. */
    private val defaultPanelHeight: Dp get() = screenHeightDp * 0.35f

    /** The uniform default open height for EMOJI/ATTACH — the keyboard height. */
    val kbFloor: Dp get() = if (settledKbHeight > 120.dp) settledKbHeight else defaultPanelHeight

    // --- Holds (transition-only, cleared automatically) ---

    /** EMOJI→KEYBOARD hand-off: keeps the slot tall while the IME rises. */
    internal var holdHeight: Dp by mutableStateOf(0.dp)

    /** Arms the KEYBOARD auto-dismiss only after the IME genuinely rose. */
    internal var imeReachedUp: Boolean by mutableStateOf(false)

    /** KEYBOARD just closed: the bar rides the keyboard down instead of dropping ahead of it. */
    internal var keyboardClosing: Boolean by mutableStateOf(false)

    // --- Drag-to-resize state ---

    /** User-dragged emoji height. null = default ([kbFloor]). Reset on close. */
    var emojiDragHeight: Dp? by mutableStateOf(null)
        internal set

    /** Attach panel height — reset to [kbFloor] on every open. */
    var attachDragHeight: Dp by mutableStateOf(0.dp)
        internal set

    val minPanelHeight: Dp get() = screenHeightDp * 0.15f
    /** Room kept free at the top (the chat header the messages run under). */
    var topReserve: Dp by mutableStateOf(0.dp)
    val maxPanelHeight: Dp get() = (screenHeightDp * 0.92f).coerceAtMost(screenHeightDp - topReserve)

    /** Below this on release → the panel closes (must be above the drag
     *  minimum, otherwise it would be unreachable). */
    val closeThreshold: Dp get() = screenHeightDp * 0.22f

    val emojiPanelHeight: Dp get() = emojiDragHeight ?: kbFloor

    // --- THE ONE height target ---

    val panelTarget: Dp
        get() = when (panelMode) {
            PanelMode.NONE -> if (keyboardClosing) imeContentDp else 0.dp
            PanelMode.KEYBOARD -> max(imeContentDp, holdHeight)
            PanelMode.EMOJI -> max(emojiPanelHeight, imeContentDp)
            // The attach sheet lies over the chat: the bottom surface keeps no room for it.
            PanelMode.ATTACH -> 0.dp
            PanelMode.RECORDING -> if (recordingKeepsKb) imeContentDp else 0.dp
        }

    // --- Mode transitions (pure state — no IME juggling, no delays) ---

    /** Enter KEYBOARD. Coming from EMOJI, hold the slot at the emoji height so
     *  the InputBar stays put while the IME rises underneath (the bump fix). */
    fun enterKeyboard() {
        if (inputMode == PanelMode.EMOJI) holdHeight = emojiPanelHeight
        imeReachedUp = false
        inputMode = PanelMode.KEYBOARD
    }

    fun enterEmoji() {
        holdHeight = 0.dp
        imeReachedUp = false
        inputMode = PanelMode.EMOJI
    }

    /** ATTACH itself is driven by the ViewModel truth (see [syncExternal]);
     *  this just clears local holds at the moment of the click. */
    fun enterAttach() {
        holdHeight = 0.dp
        imeReachedUp = false
    }

    fun dismiss() {
        keyboardClosing = inputMode == PanelMode.KEYBOARD
        holdHeight = 0.dp
        imeReachedUp = false
        emojiDragHeight = null
        inputMode = PanelMode.NONE
    }

    /** Fold the ViewModel truth in. Called every composition by the host —
     *  guarded writes, so it's idempotent and cheap. */
    fun syncExternal(attach: Boolean, recording: Boolean) {
        if (attach && !attachOpen) {
            // Opening now: the sheet rises to its half-screen detent.
            attachDragHeight = attachOpenHeight
            holdHeight = 0.dp
            inputMode = PanelMode.NONE
        }
        if (attachOpen != attach) attachOpen = attach
        if (recordingLocked != recording) recordingLocked = recording
    }

    /** Finger drag on the grabber (positive delta = up). snapTo follows 1:1. */
    fun onPanelDragBy(deltaDp: Dp) {
        when (panelMode) {
            PanelMode.EMOJI ->
                emojiDragHeight = ((emojiDragHeight ?: kbFloor) + deltaDp)
                    .coerceIn(minPanelHeight, maxPanelHeight)
            else -> Unit
        }
    }

    /** Comfortable settle anchors for the drag release. */
    fun dragAnchors(): List<Dp> = listOf(kbFloor, screenHeightDp * 0.62f, maxPanelHeight)

    /** The attach sheet opens at half the screen and goes up to full height. */
    val attachOpenHeight: Dp get() = screenHeightDp * 0.55f
    fun attachAnchors(): List<Dp> = listOf(attachOpenHeight, maxPanelHeight)

    // --- THE ONE animated height ---

    internal val bottomPanel: Animatable<Dp, *> = Animatable(0.dp, Dp.VectorConverter)

    /** Last mode the actuation saw — plain var, deliberately NOT snapshot
     *  state (it must not trigger recomposition). */
    internal var lastAnimatedMode: PanelMode = PanelMode.NONE
}

/** Soft spring for open/close transitions — smooth, NOT bouncy. */
private const val KB_PREFS = "ui_prefs"

internal val GlideSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
fun rememberBottomSurfaceState(): BottomSurfaceState {
    val config = LocalConfiguration.current
    val screenHeightDp = config.screenHeightDp.dp
    // Kept for good, per orientation: a chat opened fresh shows the emoji panel at
    // the keyboard's real height instead of a guess it then has to slide away from.
    val prefs = LocalContext.current.getSharedPreferences(KB_PREFS, Context.MODE_PRIVATE)
    val key = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "kb_landscape_dp" else "kb_portrait_dp"
    val state = remember(screenHeightDp) {
        BottomSurfaceState(screenHeightDp, prefs.getFloat(key, 0f).dp)
    }
    LaunchedEffect(state.settledKbHeight) {
        if (state.settledKbHeight > 120.dp) prefs.edit().putFloat(key, state.settledKbHeight.value).apply()
    }
    return state
}

/**
 * THE ONE animated height — call from the panel slot and use the return value
 * as the slot height.
 *
 * Spring when the MODE changed (open/close), snapTo when only the target value
 * moved within the same mode (live IME rise/fall, finger drag) — glued 1:1 to
 * the system IME animation, zero lag, zero bump.
 */
@Composable
fun BottomSurfaceState.bottomPanelHeight(): Dp {
    val target = panelTarget
    // Riding the keyboard: its height is used in this very frame. Any animation in
    // between trails the keyboard, which then covers the bar and the bar jumps after it.
    val followsKeyboard = when (panelMode) {
        PanelMode.KEYBOARD -> true
        PanelMode.NONE -> keyboardClosing
        PanelMode.RECORDING -> recordingKeepsKb
        else -> false
    }
    LaunchedEffect(target, followsKeyboard) {
        val mode = panelMode
        val springy = mode != lastAnimatedMode && !followsKeyboard
        lastAnimatedMode = mode
        if (springy) bottomPanel.animateTo(target, GlideSpec)
        else bottomPanel.snapTo(target)
    }
    return if (followsKeyboard) target else bottomPanel.value
}

/**
 * Track the live IME inset + capture the settled keyboard height + clear the
 * transition holds + auto-dismiss KEYBOARD on system IME close.
 *
 * Must be called once by the host (ChatScreen).
 */
@Composable
fun BottomSurfaceState.bindImeInset() {
    val density = LocalDensity.current
    BindKeyboardMotion(keyboard)
    val imePx = keyboard.heightPx
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    imeContentDp = with(density) { (imePx - navBarPx).coerceAtLeast(0).toDp() }

    // The ride down ends with the keyboard, or as soon as another panel takes over.
    if (keyboardClosing && (imeContentDp == 0.dp || panelMode != PanelMode.NONE)) keyboardClosing = false

    // (1) Capture the settled keyboard height — stable 150ms above 120dp.
    //     Allows both grow and shrink (suggestion strips change the height).
    LaunchedEffect(imeContentDp) {
        if (imeContentDp > 120.dp) {
            delay(150)
            if (imeContentDp > 120.dp && abs((imeContentDp - settledKbHeight).value) > 2f) {
                settledKbHeight = imeContentDp
            }
        }
    }

    // (2) Arm the auto-dismiss + fire it when the system closes the IME.
    //     Guarded by imeReachedUp (can't fire before the IME rose) and by
    //     holdHeight == 0 (can't fire during an emoji→keyboard hand-off).
    LaunchedEffect(imeContentDp) {
        if (panelMode == PanelMode.KEYBOARD) {
            if (imeContentDp > 120.dp) imeReachedUp = true
            if (imeContentDp < 10.dp && imeReachedUp && holdHeight == 0.dp) {
                keyboardClosing = true
                inputMode = PanelMode.NONE
                imeReachedUp = false
            }
        }
    }

    // (3) Clear the emoji→keyboard hold once the IME caught up (invisible —
    //     the slot is already exactly at the IME height). If the keyboard
    //     settled SHORTER than the held height (e.g. the emoji panel was
    //     drag-expanded), glide the hold down smoothly instead of snapping.
    LaunchedEffect(imeContentDp) {
        if (holdHeight > 0.dp) {
            if (imeContentDp >= holdHeight - 2.dp) {
                holdHeight = 0.dp
            } else if (imeContentDp > 120.dp) {
                delay(150)
                if (holdHeight > 0.dp && imeContentDp > 120.dp && imeContentDp < holdHeight - 2.dp) {
                    animate(holdHeight.value, 0f) { v, _ -> holdHeight = v.dp }
                }
            }
        }
    }

    // (4) Hold fallback: if the IME never rises (hardware keyboard, IME
    //     disabled), release the hold gently after 700ms so the panel can't
    //     get stuck tall.
    val holdActive = holdHeight > 0.dp
    LaunchedEffect(holdActive) {
        if (holdActive) {
            delay(700)
            if (holdHeight > 0.dp && imeContentDp < 120.dp) {
                animate(holdHeight.value, 0f) { v, _ -> holdHeight = v.dp }
            }
        }
    }
}
