package com.alphamessenger.ui.chat.v2

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * THE single permanent bottom surface (unified V2).
 *
 * ONE [Column] that ALWAYS exists. Renders, in order:
 *   1. [HorizontalDivider]
 *   2. [header] (reply / edit / forward card), then [inputBar] — ALWAYS composed, NEVER destroyed. All its persistent
 *      state (FocusRequester / ScrollState / TextFieldValue) lives in
 *      [BottomSurfaceState]. During ATTACH it collapses to 0 height (the
 *      attach panel has its own caption bar) but stays IN the composition —
 *      the collapse/expand is animated so the surface never jumps.
 *      During locked recording, [lockedRecordingBar] is drawn ON TOP of the input row
 *      (overlay per spec — the InputBar survives underneath).
 *   3. [bottomPanel] — ONE slot whose height = [BottomSurfaceState.bottomPanelHeight]
 *      and whose CONTENT follows [BottomSurfaceState.panelMode].
 *
 * ## THE ONE actuation
 * panelMode drives IME show/hide + focus — nothing else does. Pure state:
 *   KEYBOARD → focus + show; EMOJI/ATTACH/NONE → hide + clear focus;
 *   RECORDING → keep the IME only if it was up when the lock started.
 */
@Composable
fun BottomSurfaceV2(
    state: BottomSurfaceState,
    colors: AlphaColorScheme,
    inputBar: @Composable () -> Unit,
    bottomPanel: @Composable () -> Unit,
    lockedRecording: Boolean = false,
    lockedRecordingBar: @Composable () -> Unit = {},
    /** Sits on the chat's frosted glass: no divider, no fills of its own. */
    onGlass: Boolean = false,
    /** Drawn under the panel slot (emoji, attach) alone, not under the input bar. */
    panelModifier: Modifier = Modifier,
    /** Around the input bar and the locked-recording bar together: one width for both. */
    barModifier: Modifier = Modifier,
    /** Reply / edit / forward / link preview, above the input row: no recording bar covers it. */
    header: @Composable () -> Unit = {},
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.panelMode) {
        when (state.panelMode) {
            PanelMode.KEYBOARD -> {
                state.focusInput()
            }
            PanelMode.EMOJI, PanelMode.ATTACH, PanelMode.NONE -> {
                state.releaseInput()
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
            }
            PanelMode.RECORDING -> {
                if (!state.recordingKeepsKb) {
                    state.releaseInput()
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!onGlass) HorizontalDivider(thickness = 0.5.dp, color = colors.alphaBorder)

        // InputBar — ALWAYS composed, and it stays in place while the attach sheet
        // lies over the chat; the locked-recording bar overlays it when locked.
        Column(modifier = Modifier.fillMaxWidth().then(barModifier)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) { header() }
            Box(
                modifier = Modifier.fillMaxWidth()
                    // Locked recording: the bar sits UNDER the recording strip — the
                    // enlarged mic must not stick out above it.
                    .then(if (lockedRecording) Modifier.clipToBounds() else Modifier),
            ) {
                Box(Modifier.alpha(if (lockedRecording) 0f else 1f)) { inputBar() }
                // Locked-recording bar — an OVERLAY that COVERS the whole
                // InputBar (opaque, matchParentSize, centered) so no buttons
                // peek out from underneath.
                if (lockedRecording) {
                    Box(
                        modifier = Modifier.matchParentSize().then(if (onGlass) Modifier else Modifier.background(colors.bgSecondary)),
                        contentAlignment = Alignment.Center,
                    ) {
                        lockedRecordingBar()
                    }
                }
            }
        }

        // BottomPanel — ONE slot, height = bottomPanelHeight, content = mode.
        Box(panelModifier) { bottomPanel() }
    }
}
