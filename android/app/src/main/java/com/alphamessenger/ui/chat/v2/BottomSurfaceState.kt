package com.alphamessenger.ui.chat.v2

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** What fills the space under the input bar. */
enum class PanelMode {
    NONE,
    KEYBOARD,
    EMOJI,
    ATTACH,
    RECORDING,
}

/**
 * The one owner of the space under the chat's input bar: the system keyboard, the emoji panel
 * and the room each of them takes.
 *
 * Whether the system keyboard is up is never stored here: it is read from the window, where the
 * keyboard's own animation states where it is heading. Only what is ours is state: the emoji
 * panel, and a short hand-over while the keyboard comes up in its place. So this state and the
 * screen cannot disagree about the keyboard.
 *
 * The keyboard counts only while it serves this composer's field. One raised for a screen drawn
 * over the chat (a caption) leaves everything here where it is.
 *
 * Room under the input bar:
 * ```
 * ATTACH            → 0 (the attach sheet lies over the chat)
 * RECORDING         → the keyboard, if it was up when the lock started
 * EMOJI             → the panel's height (the keyboard going down underneath never shows)
 * held              → the keyboard's height, for a keyboard on its way (after the emoji panel,
 *                     or back from the background); let go a second after it was asked for
 * KEYBOARD / NONE   → the keyboard as it moves, frame by frame
 * ```
 */
@Stable
class BottomSurfaceState internal constructor(
    val screenHeightDp: Dp,
    persistedKbHeightDp: Dp,
) {
    /** The keyboard's height as it slides, never past where it settles. */
    val keyboard = KeyboardMotion()

    /** The live keyboard height above the navigation bar, whoever the keyboard is for. */
    var imeContentDp: Dp by mutableStateOf(0.dp)
        internal set

    /** Whether the system keyboard is up or on its way up, from where its animation is heading. */
    val keyboardShown: Boolean get() = keyboard.targetPx > 0

    /**
     * The keyboard serves this composer: its field holds the focus. Read in the same frame as
     * the keyboard itself, so a keyboard the window brings back is ridden from its first frame.
     */
    internal val keyboardOwned: Boolean get() = fieldFocused

    /** The keyboard's live height while it is this composer's, otherwise nothing. */
    val ownKeyboardDp: Dp get() = if (keyboardOwned) imeContentDp else 0.dp

    internal var emojiOpen: Boolean by mutableStateOf(false)

    /**
     * The keyboard's room is held for a keyboard on its way: the emoji panel gave way to it, or
     * the app went to the background with it up. Held only so long after it was asked for.
     */
    internal var awaitingKeyboard: Boolean by mutableStateOf(false)

    /** The held keyboard has been asked for, so the wait for it runs. */
    internal var keyboardRequested: Boolean by mutableStateOf(true)

    /** ViewModel truth, folded in via [syncExternal]. */
    internal var attachOpen: Boolean by mutableStateOf(false)
    internal var recordingLocked: Boolean by mutableStateOf(false)

    /** Whether the keyboard was up when locked recording started. */
    var recordingKeepsKb: Boolean by mutableStateOf(false)

    // --- The composer's field ---

    internal var fieldFocused: Boolean by mutableStateOf(false)

    /** The composer's text field while it is on screen. */
    internal var field: android.widget.EditText? = null
        set(value) {
            (field as? ComposerEditText)?.onFocus = null
            field = value
            (value as? ComposerEditText)?.onFocus = { fieldFocused = it }
            fieldFocused = value?.hasFocus() == true
        }

    var textFieldValue: TextFieldValue by mutableStateOf(TextFieldValue("", TextRange(0)))
        internal set

    // --- What fills the space ---

    val panelMode: PanelMode
        get() = when {
            attachOpen -> PanelMode.ATTACH
            recordingLocked -> PanelMode.RECORDING
            emojiOpen -> PanelMode.EMOJI
            awaitingKeyboard || (keyboardOwned && keyboardShown) -> PanelMode.KEYBOARD
            else -> PanelMode.NONE
        }

    // --- Requests ---

    /** Cursor into the field and the keyboard up, in place of the emoji panel if it is open. */
    fun openKeyboard() {
        if (emojiOpen) handToKeyboard()
        field?.focusAndShowKeyboard()
    }

    /** The emoji button: the panel in place of the keyboard, or the keyboard back in its place. */
    fun toggleEmoji() {
        if (emojiOpen) openKeyboard()
        else {
            awaitingKeyboard = false
            emojiOpen = true
            // The cursor stays: picked emoji go where it stands.
            field?.hideKeyboard()
        }
    }

    /** A tap on the field brings the system keyboard up by itself: the emoji panel gives way. */
    fun onFieldTapped() {
        if (emojiOpen) handToKeyboard()
    }

    fun closeEmoji() {
        emojiOpen = false
        awaitingKeyboard = false
        emojiDragHeight = null
    }

    /** The attach sheet is opening: nothing of ours stays under it. */
    fun enterAttach() {
        closeEmoji()
    }

    private fun handToKeyboard() {
        emojiOpen = false
        emojiDragHeight = null
        awaitingKeyboard = true
        keyboardRequested = true
    }

    /** Fold the ViewModel truth in. Called every composition by the host; guarded writes. */
    fun syncExternal(attach: Boolean, recording: Boolean) {
        if (attach && !attachOpen) {
            attachDragHeight = attachOpenHeight
            emojiOpen = false
            awaitingKeyboard = false
        }
        if (attachOpen != attach) attachOpen = attach
        if (recordingLocked != recording) recordingLocked = recording
    }

    // --- Keyboard height, kept for good per orientation ---

    internal var settledKbHeight: Dp by mutableStateOf(persistedKbHeightDp)

    /** Before the keyboard has ever been measured. */
    private val defaultPanelHeight: Dp get() = screenHeightDp * 0.35f

    /** The height every panel of ours opens at: the keyboard's. */
    val kbFloor: Dp get() = if (settledKbHeight > MIN_KEYBOARD) settledKbHeight else defaultPanelHeight

    // --- Drag-to-resize ---

    /** User-dragged emoji height. null = the keyboard's. Reset on close. */
    var emojiDragHeight: Dp? by mutableStateOf(null)
        internal set

    /** Attach panel height, reset to [attachOpenHeight] on every open. */
    var attachDragHeight: Dp by mutableStateOf(0.dp)
        internal set

    val minPanelHeight: Dp get() = screenHeightDp * 0.15f
    /** Room kept free at the top (the chat header the messages run under). */
    var topReserve: Dp by mutableStateOf(0.dp)
    val maxPanelHeight: Dp get() = (screenHeightDp * 0.92f).coerceAtMost(screenHeightDp - topReserve)

    /** Released below this, the panel closes. */
    val closeThreshold: Dp get() = screenHeightDp * 0.22f

    val emojiPanelHeight: Dp get() = emojiDragHeight ?: kbFloor

    fun onPanelDragBy(deltaDp: Dp) {
        if (panelMode == PanelMode.EMOJI) {
            emojiDragHeight = ((emojiDragHeight ?: kbFloor) + deltaDp).coerceIn(minPanelHeight, maxPanelHeight)
        }
    }

    /** Where a released drag settles. */
    fun dragAnchors(): List<Dp> = listOf(kbFloor, screenHeightDp * 0.62f, maxPanelHeight)

    /** The attach sheet opens at half the screen and goes up to full height. */
    val attachOpenHeight: Dp get() = screenHeightDp * 0.55f
    fun attachAnchors(): List<Dp> = listOf(attachOpenHeight, maxPanelHeight)

    // --- The room under the input bar ---

    val panelTarget: Dp
        get() = when (panelMode) {
            PanelMode.ATTACH -> 0.dp
            PanelMode.RECORDING -> if (recordingKeepsKb) ownKeyboardDp else 0.dp
            PanelMode.EMOJI -> max(emojiPanelHeight, ownKeyboardDp)
            PanelMode.KEYBOARD -> if (awaitingKeyboard) max(kbFloor, ownKeyboardDp) else ownKeyboardDp
            PanelMode.NONE -> ownKeyboardDp
        }

    /** The room is the keyboard itself, taken frame by frame; anything else glides. */
    internal val followsKeyboard: Boolean
        get() = when (panelMode) {
            PanelMode.KEYBOARD -> !awaitingKeyboard
            PanelMode.NONE -> keyboardOwned
            PanelMode.RECORDING -> recordingKeepsKb
            else -> false
        }

    internal val bottomPanel: Animatable<Dp, *> = Animatable(0.dp, Dp.VectorConverter)

    /** Last mode the glide saw; deliberately not state. */
    internal var lastAnimatedMode: PanelMode = PanelMode.NONE
}

private const val KB_PREFS = "ui_prefs"

/** Smaller than any on-screen keyboard: a toolbar or a hardware keyboard's strip. */
private val MIN_KEYBOARD = 120.dp

/** How long the emoji panel's room is held for a keyboard that was asked for but never came. */
private const val KEYBOARD_ARRIVAL_MS = 1_000L

/** How long the keyboard stays up after the app went to the background; a quick return keeps it. */
private const val KEYBOARD_AWAY_MS = 500L

/** Soft spring for panels opening and closing; never bouncy. */
internal val GlideSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
fun rememberBottomSurfaceState(): BottomSurfaceState {
    val config = LocalConfiguration.current
    val screenHeightDp = config.screenHeightDp.dp
    // A chat opened fresh shows the emoji panel at the keyboard's real height.
    val prefs = LocalContext.current.getSharedPreferences(KB_PREFS, Context.MODE_PRIVATE)
    val key = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "kb_landscape_dp" else "kb_portrait_dp"
    val state = remember(screenHeightDp) {
        BottomSurfaceState(screenHeightDp, prefs.getFloat(key, 0f).dp)
    }
    LaunchedEffect(state.settledKbHeight) {
        if (state.settledKbHeight > MIN_KEYBOARD) prefs.edit().putFloat(key, state.settledKbHeight.value).apply()
    }
    return state
}

/** The room under the input bar, as drawn this frame. */
@Composable
fun BottomSurfaceState.bottomPanelHeight(): Dp {
    val target = panelTarget
    val follows = followsKeyboard
    LaunchedEffect(target, follows) {
        val mode = panelMode
        val glide = mode != lastAnimatedMode && !follows
        lastAnimatedMode = mode
        if (glide) bottomPanel.animateTo(target, GlideSpec) else bottomPanel.snapTo(target)
    }
    // Riding the keyboard, its height is used in this very frame: anything in between trails it.
    return if (follows) target else bottomPanel.value
}

/**
 * Reads the keyboard from the window and keeps what is ours in step with it.
 * Called once by the host (ChatScreen).
 */
@Composable
fun BottomSurfaceState.bindImeInset() {
    val density = LocalDensity.current
    BindKeyboardMotion(keyboard)
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    imeContentDp = with(density) { (keyboard.heightPx - navBarPx).coerceAtLeast(0).toDp() }
    val shown = keyboardShown

    // The keyboard's height, from where its animation is heading rather than a frame on the way.
    val targetDp = with(density) { (keyboard.targetPx - navBarPx).coerceAtLeast(0).toDp() }
    LaunchedEffect(targetDp) {
        if (targetDp > MIN_KEYBOARD) settledKbHeight = targetDp
    }

    // The keyboard came up for the field while the emoji panel was open (a tap, the system
    // itself): the panel gives way to it.
    LaunchedEffect(shown) {
        if (shown && fieldFocused && emojiOpen) {
            emojiOpen = false
            emojiDragHeight = null
            awaitingKeyboard = true
            keyboardRequested = true
        }
    }

    // The hold ends once the keyboard stands where it was heading, or a second after it was
    // asked for without it coming (refused, hardware keyboard): the room is never held for nothing.
    LaunchedEffect(awaitingKeyboard, keyboardRequested) {
        if (awaitingKeyboard && keyboardRequested) {
            delay(KEYBOARD_ARRIVAL_MS)
            awaitingKeyboard = false
        }
    }
    LaunchedEffect(shown, keyboard.heightPx == keyboard.targetPx) {
        if (awaitingKeyboard && shown && keyboard.heightPx == keyboard.targetPx) awaitingKeyboard = false
    }

    // The attach sheet takes the screen: the composer's keyboard goes.
    LaunchedEffect(attachOpen) {
        if (attachOpen) field?.hideKeyboardAndBlur()
    }

    // Leaving with the keyboard up, it is put away shortly after (a quick return keeps it) while
    // its room stays as it was: the window does not reflow in the background, so what the return
    // animation shows is what comes back. On return the keyboard is asked back once the window has
    // its focus again, and lands in that room; if it does not come, the room is let go.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var reopen = false
        val putAway = Runnable { field?.hideKeyboard() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (keyboardOwned && keyboardShown && !emojiOpen) {
                    reopen = true
                    awaitingKeyboard = true
                    keyboardRequested = false
                    main.postDelayed(putAway, KEYBOARD_AWAY_MS)
                }
                Lifecycle.Event.ON_RESUME -> {
                    main.removeCallbacks(putAway)
                    if (reopen) {
                        reopen = false
                        val f = field
                        if (f != null && !attachOpen) f.focusAndShowKeyboard { keyboardRequested = true }
                        else { awaitingKeyboard = false; keyboardRequested = true }
                    }
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            main.removeCallbacks(putAway)
        }
    }
}
