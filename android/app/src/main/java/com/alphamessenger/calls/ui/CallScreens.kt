package com.alphamessenger.calls.ui

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alphamessenger.R
import com.alphamessenger.calls.AudioRoute
import com.alphamessenger.calls.CallPhase
import com.alphamessenger.calls.CallType
import com.alphamessenger.calls.CallUiState
import com.alphamessenger.calls.engine.CallRendererBridge
import com.alphamessenger.calls.isTerminal
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme
import org.webrtc.SurfaceViewRenderer
import java.io.File

/*
 * THE call screen.
 *
 *  - The background comes from the theme: its accent and the second colour of
 *    the outgoing bubble glow over the theme's ground (three styles, chosen in
 *    Appearance). Light themes ring on a deep tone of their accent, so video
 *    and white text stay readable. Drawn once in the draw phase — never
 *    animated, so nothing recomposes under the video surfaces.
 *  - Every animation (ringing waves, the answer button's nudge, breathing)
 *    reads its value in the draw / layer phase only: no recomposition per frame.
 *  - One button language: 76 dp to answer, 56 dp glass circles in the panel
 *    (glass = off, white = on), a red pill to end.
 */

private val CallRed = Color(0xFFE5484D)
private val CallGreen = Color(0xFF2FA866)
private val Ink = Color(0xFF111318)
private val Glass = Color.White.copy(alpha = 0.14f)

/** How the call background is drawn (Appearance → Call background). */
enum class CallBackdrop(val label: Int) {
    AURORA(R.string.call_bg_aurora),
    DEPTH(R.string.call_bg_depth),
    SPOT(R.string.call_bg_spot),
}

object CallLookPrefs {
    private const val PREFS = "ui_prefs"
    var backdrop by mutableStateOf(CallBackdrop.AURORA)
        private set
    private var loaded = false

    fun load(ctx: android.content.Context) {
        if (loaded) return
        loaded = true
        val name = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("call_backdrop", null)
        backdrop = CallBackdrop.entries.firstOrNull { it.name == name } ?: CallBackdrop.AURORA
    }

    fun set(ctx: android.content.Context, v: CallBackdrop) {
        backdrop = v
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString("call_backdrop", v.name).apply()
    }
}

/** The ground of the call screen: the theme's own, or — for a light theme — a deep tone of its accent. */
fun callBaseColor(colors: AlphaColorScheme, light: Boolean): Color =
    if (light) lerp(Color(0xFF05060A), colors.accent, 0.16f)
    else lerp(colors.bgPrimary, Color.Black, 0.3f)

/** The theme's call background in [style]. Static: drawn once per size. */
fun Modifier.callBackdrop(colors: AlphaColorScheme, light: Boolean, style: CallBackdrop): Modifier {
    val base = callBaseColor(colors, light)
    val a = colors.accent
    val b = colors.msgOutEnd
    return drawBehind {
        drawRect(base)
        val w = size.width
        val h = size.height
        // Not laid out yet: a gradient of zero radius is illegal.
        if (w < 1f || h < 1f) return@drawBehind
        val m = maxOf(w, h)
        when (style) {
            CallBackdrop.AURORA -> {
                drawRect(Brush.radialGradient(listOf(a.copy(alpha = 0.45f), Color.Transparent), Offset(w / 2f, -0.08f * h), 0.72f * m))
                drawRect(Brush.radialGradient(listOf(b.copy(alpha = 0.35f), Color.Transparent), Offset(1.05f * w, 1.08f * h), 0.6f * m))
            }
            CallBackdrop.DEPTH -> drawRect(
                Brush.verticalGradient(0f to a.copy(alpha = 0.40f), 0.38f to b.copy(alpha = 0.15f), 0.72f to Color.Transparent),
            )
            CallBackdrop.SPOT -> drawRect(
                Brush.radialGradient(
                    0f to a.copy(alpha = 0.44f), 0.45f to b.copy(alpha = 0.18f), 1f to Color.Transparent,
                    center = Offset(w / 2f, 0.33f * h), radius = 0.85f * w,
                ),
            )
        }
    }
}

/** Everything the screen can ask for. */
class CallScreenActions(
    val accept: () -> Unit,
    val reject: () -> Unit,
    val replyWithText: (String) -> Unit,
    val replyCustom: () -> Unit,
    val remindLater: () -> Unit,
    val cancel: () -> Unit,
    val hangup: () -> Unit,
    val toggleMute: () -> Unit,
    val setRoute: (AudioRoute) -> Unit,
    val toggleVideo: () -> Unit,
    val switchCamera: () -> Unit,
    val toggleHold: () -> Unit,
    val acceptVideo: () -> Unit,
    val declineVideo: () -> Unit,
    val minimize: () -> Unit,
    val message: () -> Unit,
    val redial: () -> Unit,
    /** Bring more people in: the call moves to a group made for it. */
    val addPeople: () -> Unit = {},
)

@Composable
fun CallScreen(
    state: CallUiState,
    renderer: CallRendererBridge?,
    lightTheme: Boolean,
    inPip: Boolean,
    actions: CallScreenActions,
) {
    val colors = AlphaTheme.colorScheme
    CallLookPrefs.load(LocalContext.current)
    val style = CallLookPrefs.backdrop
    Box(Modifier.fillMaxSize().callBackdrop(colors, lightTheme, style)) {
        val videoPhase = state.phase == CallPhase.CONNECTING || state.phase == CallPhase.CONNECTED ||
            state.phase == CallPhase.RECONNECTING
        when {
            state.phase == CallPhase.INCOMING_RINGING -> IncomingContent(state, colors, actions)
            state.phase == CallPhase.OUTGOING_CALLING || state.phase == CallPhase.OUTGOING_RINGING ->
                OutgoingContent(state, colors, actions)
            videoPhase && state.type == CallType.VIDEO ->
                VideoContent(state, renderer, colors, lightTheme, style, inPip, actions)
            videoPhase -> VoiceContent(state, colors, actions)
            state.phase.isTerminal -> EndedContent(state, colors, actions)
            else -> Unit
        }
        if (state.incomingVideoRequest && !inPip) {
            VideoRequestCard(state.peerName, colors, actions.acceptVideo, actions.declineVideo)
        }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────

/** Scale to 92 % while pressed. */
@Composable
private fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** A tap that also ticks. */
@Composable
private fun rememberTick(onClick: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
}

@Composable
private fun PeerName(state: CallUiState, size: TextUnit) {
    Text(
        state.peerName.ifBlank { stringResource(R.string.peer_fallback) },
        color = Color.White, fontSize = size, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

/** The person: their photo, or their initial on the theme gradient. */
@Composable
private fun CallAvatar(state: CallUiState, colors: AlphaColorScheme, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(state.peerAvatarPath) {
        state.peerAvatarPath.takeIf { it.isNotBlank() && File(it).exists() }
            ?.let { runCatching { com.alphamessenger.data.MediaVault.decodeFile(it) }.getOrNull() }
    }
    Box(
        modifier
            .size(size)
            .shadow(28.dp, CircleShape, ambientColor = colors.accent, spotColor = colors.accent)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.accent, colors.msgOutEnd)))
            .border(4.dp, Color.White.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), state.peerName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                state.peerName.take(1).uppercase().ifBlank { "?" },
                color = colors.msgOutText, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** A small rounded label: "Encrypted", "Reconnecting…", "Camera is off"… */
@Composable
private fun CallChip(icon: ImageVector, text: String, tint: Color = Color.White.copy(alpha = 0.85f), bg: Color = Color.White.copy(alpha = 0.10f)) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 44 dp round button of the top bar. */
@Composable
internal fun TopCircle(icon: ImageVector, label: String, onClick: () -> Unit, bg: Color = Color.White.copy(alpha = 0.10f)) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).pressScale(src).clip(CircleShape).background(bg)
            .clickable(interactionSource = src, indication = null, onClick = rememberTick(onClick)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

/** Minimize · encrypted · (hold). */
@Composable
internal fun CallTopBar(onMinimize: () -> Unit, right: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopCircle(AlphaIcons.Filled.ExpandMore, stringResource(R.string.call_minimize), onMinimize)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(AlphaIcons.Filled.Lock, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.call_encrypted), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
        }
        if (right != null) right() else Spacer(Modifier.size(44.dp))
    }
}

/** A 56 dp glass circle with its label: glass = off, white = on. */
@Composable
internal fun PanelButton(
    icon: ImageVector,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    size: Dp = 56.dp,
    enabled: Boolean = true,
) {
    val src = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size + 8.dp)) {
        Box(
            Modifier.size(size).pressScale(src).clip(CircleShape)
                .background(if (on) Color.White else if (enabled) Glass else Color.Transparent)
                .then(if (enabled) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape))
                .clickable(enabled = enabled, interactionSource = src, indication = null, onClick = rememberTick(onClick)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, label,
                tint = if (on) Ink else Color.White.copy(alpha = if (enabled) 1f else 0.45f),
                modifier = Modifier.size(size * 0.43f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The red pill that ends (or cancels) the call. */
@Composable
internal fun EndPill(label: String, onClick: () -> Unit, height: Dp = 56.dp) {
    val src = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = height + 16.dp, height = height).pressScale(src)
                .shadow(14.dp, RoundedCornerShape(height / 2), ambientColor = CallRed, spotColor = CallRed)
                .clip(RoundedCornerShape(height / 2)).background(CallRed)
                .clickable(interactionSource = src, indication = null, onClick = rememberTick(onClick)),
            contentAlignment = Alignment.Center,
        ) { Icon(AlphaIcons.Filled.CallEnd, label, tint = Color.White, modifier = Modifier.size(height * 0.46f)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
    }
}

/** The glass panel the controls sit in. */
@Composable
internal fun ControlPanel(bg: Color = Color.White.copy(alpha = 0.08f), content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp)
            .clip(RoundedCornerShape(34.dp)).background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(34.dp))
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) { content() }
}

/** The audio output button: shows where the sound goes, a tap moves it on. */
@Composable
private fun RouteButton(state: CallUiState, setRoute: (AudioRoute) -> Unit, size: Dp = 56.dp) {
    val (icon, label) = when (state.audioRoute) {
        AudioRoute.SPEAKER -> AlphaIcons.AutoMirrored.Filled.VolumeUp to stringResource(R.string.call_speaker)
        AudioRoute.BLUETOOTH -> AlphaIcons.Filled.Bluetooth to "Bluetooth"
        AudioRoute.WIRED_HEADSET -> AlphaIcons.Filled.Headset to stringResource(R.string.call_route_headset)
        AudioRoute.EARPIECE -> AlphaIcons.Filled.PhoneInTalk to stringResource(R.string.call_route_phone)
    }
    PanelButton(icon, label, on = state.audioRoute == AudioRoute.SPEAKER || state.audioRoute == AudioRoute.BLUETOOTH,
        onClick = { setRoute(nextRoute(state)) }, size = size)
}

/** Earpiece (or the wired headset) → speaker → Bluetooth (when there is one) → back. */
private fun nextRoute(s: CallUiState): AudioRoute {
    val first = if (s.audioRoute == AudioRoute.WIRED_HEADSET) AudioRoute.WIRED_HEADSET else AudioRoute.EARPIECE
    val cycle = listOfNotNull(first, AudioRoute.SPEAKER, AudioRoute.BLUETOOTH.takeIf { s.bluetoothAvailable })
    return cycle[(cycle.indexOf(s.audioRoute) + 1) % cycle.size]
}

@Composable
private fun MuteButton(state: CallUiState, onClick: () -> Unit, size: Dp = 56.dp) =
    PanelButton(
        if (state.muted) AlphaIcons.Filled.MicOff else AlphaIcons.Filled.Mic,
        stringResource(if (state.muted) R.string.call_muted else R.string.call_mic),
        on = state.muted, onClick = onClick, size = size,
    )

private fun formatDuration(sec: Int): String {
    val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ── Incoming ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncomingContent(state: CallUiState, colors: AlphaColorScheme, a: CallScreenActions) {
    var replying by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CallChip(AlphaIcons.Filled.Lock, stringResource(R.string.call_e2ee))
        Spacer(Modifier.height(44.dp))
        RingingAvatar(state, colors)
        Spacer(Modifier.height(14.dp))
        PeerName(state, 32.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (state.type == CallType.VIDEO) R.string.call_incoming_video_call else R.string.calls_incoming_voice),
            color = Color.White.copy(alpha = 0.78f), fontSize = 15.sp,
        )
        Spacer(Modifier.weight(1f))
        if (replying) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(R.string.call_qr_later, R.string.call_qr_cant, R.string.call_qr_text).forEach { res ->
                    val text = stringResource(res)
                    GlassPill(null, text) { a.replyWithText(text) }
                }
                GlassPill(null, stringResource(R.string.call_qr_custom), dashed = true, onClick = a.replyCustom)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill(AlphaIcons.Outlined.ChatBubbleOutline, stringResource(R.string.call_reply_text)) { replying = true }
                GlassPill(AlphaIcons.Outlined.Schedule, stringResource(R.string.call_remind), onClick = a.remindLater)
            }
        }
        Spacer(Modifier.height(34.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            AnswerButton(AlphaIcons.Filled.CallEnd, stringResource(R.string.call_decline), CallRed, nudge = false, onClick = a.reject)
            AnswerButton(
                if (state.type == CallType.VIDEO) AlphaIcons.Filled.Videocam else AlphaIcons.Filled.Call,
                stringResource(R.string.calls_answer), CallGreen, nudge = true, onClick = a.accept,
            )
        }
    }
}

/** Waves leave the avatar while it rings. */
@Composable
private fun RingingAvatar(state: CallUiState, colors: AlphaColorScheme) {
    val t = rememberInfiniteTransition(label = "ring")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "wave")
    val ring = colors.accent
    Box(
        Modifier.size(236.dp).drawBehind {
            val r = size.minDimension / 2f
            for (i in 0 until 3) {
                val f = (p.value + i / 3f) % 1f
                drawCircle(ring.copy(alpha = 0.32f * (1f - f)), radius = r * (0.6f + 0.4f * f))
            }
        },
        contentAlignment = Alignment.Center,
    ) { CallAvatar(state, colors, 136.dp) }
}

@Composable
private fun GlassPill(icon: ImageVector?, text: String, dashed: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.height(44.dp).pressScale(src).clip(RoundedCornerShape(22.dp))
            .background(if (dashed) Color.Transparent else Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = if (dashed) 0.3f else 0.14f), RoundedCornerShape(22.dp))
            .clickable(interactionSource = src, indication = null, onClick = rememberTick(onClick))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 76 dp answer / decline. The answer one hops now and then. */
@Composable
private fun AnswerButton(icon: ImageVector, label: String, color: Color, nudge: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hop = if (nudge) {
        rememberInfiniteTransition(label = "nudge").animateFloat(
            0f, 0f,
            infiniteRepeatable(keyframes {
                durationMillis = 2200
                0f at 1360
                -10f at 1580
                0f at 1800
                -4f at 1980
            }),
            label = "hop",
        )
    } else null
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp)
                .graphicsLayer { translationY = (hop?.value ?: 0f) * density }
                .pressScale(src)
                .shadow(18.dp, CircleShape, ambientColor = color, spotColor = color)
                .clip(CircleShape).background(color)
                .clickable(interactionSource = src, indication = null, onClick = rememberTick(onClick)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

// ── Outgoing ────────────────────────────────────────────────────────────────

@Composable
private fun OutgoingContent(state: CallUiState, colors: AlphaColorScheme, a: CallScreenActions) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CallTopBar(a.minimize)
        Spacer(Modifier.height(72.dp))
        BreathingAvatar(state, colors)
        Spacer(Modifier.height(24.dp))
        PeerName(state, 32.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (state.phase == CallPhase.OUTGOING_RINGING) R.string.call_ringing else R.string.call_dialing),
            color = Color.White.copy(alpha = 0.82f), fontSize = 16.sp,
        )
        Spacer(Modifier.weight(1f))
        ControlPanel {
            if (state.type == CallType.VIDEO) PanelButton(AlphaIcons.Filled.Cameraswitch, stringResource(R.string.call_flip), false, a.switchCamera)
            RouteButton(state, a.setRoute)
            MuteButton(state, a.toggleMute)
            EndPill(stringResource(R.string.common_cancel), a.cancel)
        }
    }
}

/** The avatar with a soft halo breathing around it (while it is calling). */
@Composable
private fun BreathingAvatar(state: CallUiState, colors: AlphaColorScheme) {
    val t = rememberInfiniteTransition(label = "breathe")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "halo")
    val ring = colors.accent
    Box(
        Modifier.size(176.dp).drawBehind {
            val r = size.minDimension / 2f
            drawCircle(ring.copy(alpha = 0.30f - 0.2f * p.value), radius = r * (0.82f + 0.12f * p.value))
        },
        contentAlignment = Alignment.Center,
    ) { CallAvatar(state, colors, 136.dp) }
}

// ── Voice call ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceContent(state: CallUiState, colors: AlphaColorScheme, a: CallScreenActions) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CallTopBar(a.minimize) {
            if (state.phase == CallPhase.CONNECTED) {
                TopCircle(AlphaIcons.Filled.PersonAdd, stringResource(R.string.gcall_add_title), a.addPeople)
                Spacer(Modifier.width(8.dp))
            }
            TopCircle(
                if (state.localOnHold) AlphaIcons.Filled.PlayArrow else AlphaIcons.Filled.Pause,
                stringResource(if (state.localOnHold) R.string.call_resume else R.string.call_hold),
                a.toggleHold,
                bg = if (state.localOnHold) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.10f),
            )
        }
        Spacer(Modifier.height(84.dp))
        val ring = colors.accent
        Box(
            Modifier.size(172.dp).drawBehind {
                val r = size.minDimension / 2f
                drawCircle(ring.copy(alpha = 0.12f), radius = r)
                drawCircle(ring.copy(alpha = 0.22f), radius = r * 0.89f)
            },
            contentAlignment = Alignment.Center,
        ) { CallAvatar(state, colors, 136.dp) }
        Spacer(Modifier.height(26.dp))
        PeerName(state, 32.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.phase) {
                CallPhase.CONNECTING -> stringResource(R.string.calls_connecting_text)
                CallPhase.RECONNECTING -> stringResource(R.string.call_reconnecting)
                else -> formatDuration(state.durationSec)
            },
            color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(18.dp))
        StatusChips(state)
        Spacer(Modifier.weight(1f))
        ControlPanel {
            RouteButton(state, a.setRoute)
            PanelButton(AlphaIcons.Filled.Videocam, stringResource(R.string.perm_video), false, a.toggleVideo, enabled = state.phase == CallPhase.CONNECTED)
            MuteButton(state, a.toggleMute)
            EndPill(stringResource(R.string.call_end), a.hangup)
        }
    }
}

/** What is going on with the line: hold on either side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(state: CallUiState) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        if (state.remoteOnHold) CallChip(AlphaIcons.Filled.Pause, stringResource(R.string.call_peer_on_hold))
        if (state.localOnHold) CallChip(AlphaIcons.Filled.Pause, stringResource(R.string.call_on_hold_you))
    }
}

// ── Video call ──────────────────────────────────────────────────────────────

@Composable
private fun VideoContent(
    state: CallUiState,
    renderer: CallRendererBridge?,
    colors: AlphaColorScheme,
    lightTheme: Boolean,
    style: CallBackdrop,
    inPip: Boolean,
    a: CallScreenActions,
) {
    // Controls fade away while the peer's picture is up; a tap brings them back.
    var controls by remember { mutableStateOf(true) }
    val showControls = controls && !inPip
    LaunchedEffect(controls, state.remoteVideoActive) {
        if (controls && state.remoteVideoActive) {
            kotlinx.coroutines.delay(5000)
            controls = false
        }
    }
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            controls = !controls
        },
    ) {
        // The peer's video, full screen. Mounted for the whole video call and never
        // torn down: removing it when their camera goes off would recreate the
        // surface and freeze the picture.
        if (renderer != null) {
            AndroidView(
                factory = { ctx -> SurfaceViewRenderer(ctx).apply { setEnableHardwareScaler(true) } },
                update = { sv -> renderer.bindRemote(sv) },
                onRelease = { sv -> renderer.unbind(sv) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Their camera is off (or not on yet): the person on the theme's background,
        // drawn over the idle surface.
        if (!state.remoteVideoActive) {
            Column(
                Modifier.fillMaxSize().callBackdrop(colors, lightTheme, style),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CallAvatar(state, colors, 136.dp)
                if (!inPip) {
                    Spacer(Modifier.height(24.dp))
                    PeerName(state, 30.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(statusText(state), color = Color.White.copy(alpha = 0.88f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(18.dp))
                    CallChip(
                        AlphaIcons.Filled.VideocamOff,
                        stringResource(if (state.awaitingVideoResponse) R.string.call_waiting_response else R.string.call_peer_camera_off),
                        bg = Color.Black.copy(alpha = 0.28f),
                    )
                }
            }
        } else if (!inPip) {
            // Scrims keep the top bar and the panel readable on any picture.
            Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
            )
        }
        // Our camera, in a small window over theirs. Rectangular on purpose: a
        // SurfaceView cannot be clipped to rounded corners reliably.
        if (state.videoEnabled && renderer != null && !inPip) {
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 72.dp, end = 16.dp)
                    .size(width = 108.dp, height = 156.dp)
                    .background(Color.Black)
                    .border(1.5.dp, Color.White.copy(alpha = 0.35f)),
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            // Composite ABOVE the full-screen remote surface, or the
                            // preview hides behind it.
                            setZOrderMediaOverlay(true)
                            setMirror(true)
                            setEnableHardwareScaler(true)
                        }
                    },
                    update = { sv -> renderer.bindLocal(sv) },
                    onRelease = { sv -> renderer.unbind(sv) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopCircle(AlphaIcons.Filled.ExpandMore, stringResource(R.string.call_minimize), a.minimize, bg = Color.Black.copy(alpha = 0.35f))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.remoteVideoActive) {
                        Text(
                            state.peerName.ifBlank { stringResource(R.string.peer_fallback) },
                            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AlphaIcons.Filled.Lock, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(statusText(state), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AlphaIcons.Filled.Lock, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.call_encrypted), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
                        }
                    }
                }
                if (state.phase == CallPhase.CONNECTED) {
                    TopCircle(AlphaIcons.Filled.PersonAdd, stringResource(R.string.gcall_add_title), a.addPeople, bg = Color.Black.copy(alpha = 0.35f))
                } else Spacer(Modifier.size(44.dp))
            }
        }
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            ControlPanel(bg = Color(0x7314161C)) {
                PanelButton(AlphaIcons.Filled.Cameraswitch, stringResource(R.string.call_flip), false, a.switchCamera, size = 54.dp, enabled = state.videoEnabled)
                PanelButton(
                    if (state.videoEnabled) AlphaIcons.Filled.Videocam else AlphaIcons.Filled.VideocamOff,
                    stringResource(R.string.perm_video), state.videoEnabled, a.toggleVideo, size = 54.dp,
                    enabled = state.phase == CallPhase.CONNECTED,
                )
                MuteButton(state, a.toggleMute, size = 54.dp)
                RouteButton(state, a.setRoute, size = 54.dp)
                EndPill(stringResource(R.string.call_end), a.hangup, height = 54.dp)
            }
        }
    }
}

@Composable
private fun statusText(state: CallUiState): String = when (state.phase) {
    CallPhase.CONNECTING -> stringResource(R.string.calls_connecting_text)
    CallPhase.RECONNECTING -> stringResource(R.string.call_reconnecting)
    else -> formatDuration(state.durationSec)
}

/** "The peer wants video" — consent before their picture shows. */
@Composable
private fun VideoRequestCard(peerName: String, colors: AlphaColorScheme, onAccept: () -> Unit, onDecline: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF1C1D22))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) { Icon(AlphaIcons.Filled.Videocam, null, tint = colors.accent, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.call_wants_video, peerName.ifBlank { stringResource(R.string.peer_fallback) }),
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassPill(null, stringResource(R.string.call_not_now), onClick = onDecline)
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(colors.accent, colors.msgOutEnd)))
                        .clickable(onClick = onAccept).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.call_switch_video), color = colors.msgOutText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ── The call is over ────────────────────────────────────────────────────────

@Composable
private fun EndedContent(state: CallUiState, colors: AlphaColorScheme, a: CallScreenActions) {
    val outcome = when (state.phase) {
        CallPhase.BUSY -> stringResource(R.string.call_busy)
        CallPhase.REJECTED -> stringResource(R.string.call_error_peer_rejected)
        CallPhase.TIMEOUT -> stringResource(R.string.call_error_timeout)
        CallPhase.FAILED -> stringResource(R.string.call_connection_error)
        else -> stringResource(R.string.call_ended)
    }
    // Same rule as the manager's linger: a call that was ours to follow up on
    // stays a moment longer, with "Message" and "Call back".
    val followUp = state.outgoing || state.durationSec > 0
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(110.dp))
        // No alpha layer here: it would render the avatar's glow into a square
        // offscreen buffer and cut it at the edges (a visible square).
        CallAvatar(state, colors, 112.dp)
        Spacer(Modifier.height(24.dp))
        PeerName(state, 28.sp)
        Spacer(Modifier.height(6.dp))
        Text(outcome, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, textAlign = TextAlign.Center)
        if (state.durationSec > 0) {
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EndedStat(formatDuration(state.durationSec), stringResource(R.string.call_duration_label))
                Box(Modifier.width(1.dp).height(40.dp).background(Color.White.copy(alpha = 0.12f)))
                EndedStat(
                    stringResource(if (state.type == CallType.VIDEO) R.string.perm_video else R.string.call_type_voice),
                    stringResource(R.string.call_type_label),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (followUp && state.chatId.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val msgSrc = remember { MutableInteractionSource() }
                Row(
                    Modifier.weight(1f).height(56.dp).pressScale(msgSrc).clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(28.dp))
                        .clickable(interactionSource = msgSrc, indication = null, onClick = rememberTick(a.message)),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Outlined.ChatBubbleOutline, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.calls_message), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                val callSrc = remember { MutableInteractionSource() }
                Row(
                    Modifier.weight(1f).height(56.dp).pressScale(callSrc)
                        .shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = colors.accent, spotColor = colors.accent)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(listOf(colors.accent, colors.msgOutEnd)))
                        .clickable(interactionSource = callSrc, indication = null, onClick = rememberTick(a.redial)),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Filled.Call, null, tint = colors.msgOutText, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.calls_callback), color = colors.msgOutText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(18.dp))
            // Runs down over the linger, then the screen closes by itself.
            val drain = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(Unit) { drain.animateTo(0f, tween(com.alphamessenger.calls.manager.CallManager.FOLLOW_UP_LINGER_MS.toInt(), easing = LinearEasing)) }
            Box(Modifier.width(120.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = drain.value
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }.background(Color.White.copy(alpha = 0.7f)),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.call_closes_itself), color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun EndedStat(value: String, label: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
