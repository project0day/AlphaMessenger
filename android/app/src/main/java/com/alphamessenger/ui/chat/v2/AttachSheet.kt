package com.alphamessenger.ui.chat.v2

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * The attach sheet: it lies over the chat instead of taking room under the input
 * bar, so nothing in the chat moves when it opens. The scrim closes it; the sheet
 * rides above the keyboard or the emoji panel of its caption (one place for both, so
 * switching between them moves nothing) and the navigation bar, and keeps the
 * system's edge Back off its area so swipes across it stay the sheet's.
 */
@Composable
fun AttachSheet(
    visible: Boolean,
    state: BottomSurfaceState,
    colors: AlphaColorScheme,
    caption: TextFieldValue,
    onCaptionChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, AttachCaptionKeys) -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).pointerInput(Unit) {
            detectTapGestures { onDismiss() }
        })
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it },
        exit = slideOutVertically(tween(220)) { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val density = LocalDensity.current
        var emojiOpen by remember { mutableStateOf(false) }
        // Back to the keyboard: the emoji panel stays until the keyboard stands in its place.
        var awaitingKeyboard by remember { mutableStateOf(false) }
        val field = remember { arrayOfNulls<android.widget.EditText>(1) }
        val navDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        // The keyboard as the chat tracks it: the slide's own progress, never the raw
        // insets, which jump on a quick reopen.
        val imeDp = state.imeContentDp
        // Room held while the keyboard leaves for an overlay (the time sheet), so the
        // sheet stands still and settles after.
        val hold = remember { androidx.compose.animation.core.Animatable(0f) }
        var afterKb by remember { mutableStateOf<(() -> Unit)?>(null) }
        LaunchedEffect(imeDp < 10.dp, afterKb) {
            val action = afterKb ?: return@LaunchedEffect
            if (imeDp >= 10.dp) return@LaunchedEffect
            afterKb = null
            action()
            // Outside this effect: clearing [afterKb] restarts it and would cut the settle short.
            scope.launch { hold.animateTo(0f, tween(260)) }
        }
        val imeUp = imeDp > 120.dp
        // The emoji panel holds its room until the keyboard has risen all the way (it shows
        // under the rising keyboard): let go earlier and the caption dips with a half-risen
        // keyboard, then climbs back. A keyboard that stops short is let go once it rests.
        LaunchedEffect(imeDp, awaitingKeyboard) {
            if (!awaitingKeyboard || !imeUp) return@LaunchedEffect
            if (imeDp < state.kbFloor - 4.dp) kotlinx.coroutines.delay(250)
            emojiOpen = false; awaitingKeyboard = false
        }
        // The panel opens first and only then the keyboard leaves: the room under the
        // caption is already taken, so nothing drops.
        LaunchedEffect(emojiOpen) { if (emojiOpen) field[0]?.hideKeyboard() }
        BackHandler(enabled = emojiOpen) { emojiOpen = false }
        val keys = AttachCaptionKeys(
            emojiOpen = emojiOpen,
            onEmoji = {
                if (emojiOpen) { awaitingKeyboard = true; field[0]?.focusAndShowKeyboard() } else emojiOpen = true
            },
            onFieldTapped = { if (emojiOpen) awaitingKeyboard = true },
            onField = { field[0] = it },
            afterKeyboard = { action ->
                if (imeDp < 10.dp) action()
                else {
                    scope.launch { hold.snapTo(imeDp.value) }
                    afterKb = action
                    field[0]?.hideKeyboard()
                }
            },
        )
        val base = if (emojiOpen && state.kbFloor > imeDp) state.kbFloor else imeDp
        val under = if (hold.value.dp > base) hold.value.dp else base

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val topDp = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
            // The keyboard (or emoji panel) takes its room from the grid, so the sheet's top
            // stands still and only the caption rides up. A sheet too low for that grows,
            // and it never passes the top of the screen.
            val room = state.screenHeightDp - topDp - under - navDp - 8.dp
            val wanted = maxOf(state.attachDragHeight - under, 160.dp)
            val height = if (wanted < room) wanted else room
            val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            Column(
                Modifier.fillMaxWidth()
                    .shadow(18.dp, shape, clip = false)
                    .clip(shape)
                    .background(colors.bgSecondary)
                    .systemGestureExclusion()
                    // Touches on the sheet stay on it: nothing reaches the chat beneath.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            ) {
                content(Modifier.fillMaxWidth().height(height), keys)
                Box(Modifier.fillMaxWidth().height(under)) {
                    if (emojiOpen) EmojiPanelV2(
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                        onPick = { e -> onCaptionChange(caption.insert(e)) },
                        onBackspace = { onCaptionChange(caption.backspace()) },
                    )
                }
                Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
