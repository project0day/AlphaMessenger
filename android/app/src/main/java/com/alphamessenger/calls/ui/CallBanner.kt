package com.alphamessenger.calls.ui

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.alphamessenger.R
import com.alphamessenger.calls.manager.CallManager
import com.alphamessenger.calls.CallPhase
import com.alphamessenger.calls.CallUiState
import com.alphamessenger.calls.CallLauncher
import com.alphamessenger.calls.isActive
import com.alphamessenger.ui.theme.AlphaTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CallBannerViewModel @Inject constructor(
    val callManager: CallManager,
) : ViewModel() {
    val state get() = callManager.uiState
    fun hangup() = callManager.hangup()
    fun toggleMute() = callManager.toggleMute()
}

/**
 * THE compact call banner — shows between the top bar and the chat list when a
 * call is active but the CallActivity was minimized (#1).
 *
 * ## Layout
 * ```
 * ┌──────────────────────────────────────────────────┐
 * │ ●  Name · 00:17    [Mic○] [End●]    [Expand ⌄] │
 * └──────────────────────────────────────────────────┘
 * ```
 *  - Pulsing green/red dot (phase indicator).
 *  - Peer name + duration (or status if connecting).
 *  - Mute toggle + End call.
 *  - Tapping the banner (NOT the buttons) reopens the full-screen CallActivity.
 *
 * Animated in/out (expandVertically/shrinkVertically) so it doesn't jump.
 */
@Composable
fun CallBanner(modifier: Modifier = Modifier) {
    val vm: CallBannerViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val colors = AlphaTheme.colorScheme

    AnimatedVisibility(
        visible = state.phase.isActive,
        enter = expandVertically(tween(250)),
        exit = shrinkVertically(tween(200)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(colors.accent, colors.msgOutEnd)))
                .clickable {
                    // Back to the full-screen call.
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(ctx, CallActivity::class.java).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                }
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = colors.msgOutText
            // Three bars that move while the line is live (drawn, not recomposed).
            val t = rememberInfiniteTransition(label = "eq")
            val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "eqp")
            val live = state.phase == CallPhase.CONNECTED
            Box(
                Modifier.size(width = 15.dp, height = 16.dp).drawBehind {
                    val w = 3.dp.toPx()
                    for (i in 0 until 3) {
                        val f = if (live) ((p.value + i * 0.33f) % 1f).let { if (it > 0.5f) 1f - it else it } * 2f else 0.3f
                        val h = size.height * (0.3f + 0.7f * f)
                        drawRoundRect(
                            ink, topLeft = Offset(i * (w + 3.dp.toPx()), (size.height - h) / 2f),
                            size = Size(w, h), cornerRadius = CornerRadius(w / 2f),
                        )
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            val status = when (state.phase) {
                CallPhase.CONNECTED -> formatDuration(state.durationSec)
                CallPhase.CONNECTING -> stringResource(R.string.calls_connecting_text)
                CallPhase.RECONNECTING -> stringResource(R.string.call_reconnecting)
                CallPhase.OUTGOING_CALLING -> stringResource(R.string.call_dialing)
                CallPhase.OUTGOING_RINGING -> stringResource(R.string.call_ringing)
                CallPhase.INCOMING_RINGING -> stringResource(R.string.call_incoming)
                else -> ""
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.peerName.ifBlank { stringResource(R.string.peer_fallback) } + " · " + status,
                    color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(stringResource(R.string.call_tap_to_return), color = ink.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1)
            }
            // Mute.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (state.muted) Color.White else Color.White.copy(alpha = 0.22f))
                    .clickable { vm.toggleMute() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.muted) AlphaIcons.Filled.MicOff else AlphaIcons.Filled.Mic,
                    stringResource(R.string.call_mic),
                    tint = if (state.muted) Color(0xFF111318) else ink,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            // End.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5484D))
                    .clickable { vm.hangup() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AlphaIcons.Filled.CallEnd, stringResource(R.string.call_end), tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatDuration(sec: Int): String {
    val m = sec / 60; val s = sec % 60
    return "%02d:%02d".format(m, s)
}
