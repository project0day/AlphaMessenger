package com.alphamessenger.ui.secret

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import kotlinx.coroutines.launch
import com.alphamessenger.ui.components.AlphaSheet
import com.alphamessenger.ui.theme.AlphaTheme

/** Tor's violet: the mark of everything that goes only through Tor. */
val SecretViolet = Color(0xFF8B6CF6)

private fun outlineIcon(name: String, vararg paths: String, dots: List<String> = emptyList()): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                pathData = androidx.compose.ui.graphics.vector.addPathNodes(it),
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
        }
        dots.forEach {
            addPath(pathData = androidx.compose.ui.graphics.vector.addPathNodes(it), fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
        }
    }.build()

/** Tor's onion, drawn small and calm. */
private val OnionIcon: ImageVector by lazy {
    outlineIcon(
        "Onion",
        "M12 4c-0.5 1.9-1.8 3.1-3.4 4.3C6.4 9.9 5 12 5 14.5 5 18.1 8.1 21 12 21s7-2.9 7-6.5c0-2.5-1.4-4.6-3.6-6.2C13.8 7.1 12.5 5.9 12 4z",
        "M12 7.5c-0.3 1.5-1.2 2.5-2.2 3.5-1.1 1.1-1.8 2.3-1.8 3.8 0 2.3 1.8 4.1 4 4.1",
        "M12 7.5c0.3 1.5 1.2 2.5 2.2 3.5 1.1 1.1 1.8 2.3 1.8 3.8 0 2.3-1.8 4.1-4 4.1",
        "M12 11v7.8",
        "M12 4c0-1 0.6-1.8 1.6-2.3",
    )
}

/** The secret chat's mark everywhere: Tor's onion. */
@Composable
fun SecretMark(tint: Color = SecretViolet, size: Dp = 22.dp) {
    Icon(OnionIcon, null, tint = tint, modifier = Modifier.size(size))
}

/** The profile's entry to a secret chat with this contact. */
@Composable
fun SecretChatCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = AlphaTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SecretViolet.copy(alpha = 0.12f))
            .border(1.dp, SecretViolet.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(SecretViolet.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) { SecretMark() }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.secret_chat_title), color = cs.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.secret_chat_card_sub), color = cs.textSecondary, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Icon(AlphaIcons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = cs.textSecondary)
    }
}

/** What a secret chat gives and what it turns off, before the invitation goes. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SecretChatStartSheet(peerName: String, onStart: () -> Unit, onDismiss: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    AlphaSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 16.dp)
                .navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(SecretViolet.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) { SecretMark(size = 34.dp) }
                Text(
                    stringResource(R.string.secret_start_title, peerName), color = cs.textPrimary,
                    fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.secret_start_sub), color = cs.textSecondary,
                    fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                )
            }
            Point(AlphaIcons.Outlined.Public, stringResource(R.string.secret_point_tor_title), stringResource(R.string.secret_point_tor_sub))
            Point(AlphaIcons.Outlined.Key, stringResource(R.string.secret_point_keys_title), stringResource(R.string.secret_point_keys_sub))
            Point(AlphaIcons.Outlined.Disappearing, stringResource(R.string.secret_point_gone_title), stringResource(R.string.secret_point_gone_sub))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.bgTertiary).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.secret_off_title).uppercase(), color = cs.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.secret_off_list), color = cs.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(SecretViolet)
                    .clickable(onClick = onStart),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.secret_start_send), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.secret_not_now), color = SecretViolet)
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, text: String) {
    val cs = AlphaTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(cs.bgTertiary),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = SecretViolet, modifier = Modifier.size(18.dp)) }
        Column {
            Text(title, color = cs.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = cs.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** In the ordinary chat: the contact invites us to a secret chat. */
@Composable
fun SecretOfferBanner(peerName: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(SecretViolet.copy(alpha = 0.14f))
            .border(1.dp, SecretViolet.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SecretMark(size = 20.dp)
        Text(
            stringResource(R.string.secret_offer_text, peerName), color = cs.textPrimary,
            fontSize = 14.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDecline) { Text(stringResource(R.string.secret_offer_decline), color = cs.textSecondary) }
        TextButton(onClick = onAccept) { Text(stringResource(R.string.secret_offer_accept), color = SecretViolet, fontWeight = FontWeight.SemiBold) }
    }
}

/** In the secret chat: Tor is not up, so nothing leaves the phone yet. */
@Composable
fun SecretTorBanner() {
    val warn = Color(0xFFF2A24B)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(warn.copy(alpha = 0.13f))
            .border(1.dp, warn.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AlphaIcons.Outlined.WarningAmber, null, tint = warn, modifier = Modifier.size(22.dp))
        Column {
            Text(stringResource(R.string.secret_tor_banner_title), color = AlphaTheme.colorScheme.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.secret_tor_banner_sub), color = AlphaTheme.colorScheme.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** An invitation in the ordinary chat; the invitee answers right on it. */
@Composable
fun SecretInviteNotice(text: String, pending: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(SecretViolet.copy(alpha = 0.13f))
                .border(1.dp, SecretViolet.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretMark(size = 18.dp)
                Text(text, color = cs.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDecline) { Text(stringResource(R.string.secret_offer_decline), color = cs.textSecondary) }
                    TextButton(onClick = onAccept) {
                        Text(stringResource(R.string.secret_offer_accept), color = SecretViolet, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Tor's bootstrap progress (0..100) while it connects, null once up or unknown. */
@Composable
fun rememberTorProgress(linkUp: Boolean = false): Int? {
    val state by com.alphamessenger.data.EmbeddedTor.state.collectAsState()
    val percent = androidx.compose.runtime.produceState<Int?>(null, state, linkUp) {
        while (state != com.alphamessenger.data.EmbeddedTor.State.ON && !linkUp) {
            value = com.alphamessenger.data.EmbeddedTor.bootstrapPercent()
            kotlinx.coroutines.delay(1_000)
        }
        value = null
    }
    return percent.value
}

/** How this chat travels right now, and what else goes through Tor. */
@Composable
fun SecretConnectionCard(linkUp: Boolean) {
    val cs = AlphaTheme.colorScheme
    val state by com.alphamessenger.data.EmbeddedTor.state.collectAsState()
    val privateMode by com.alphamessenger.data.EmbeddedTor.enabled.collectAsState()
    // The chat's own connection being up proves Tor is, whatever the service reported.
    val on = linkUp || state == com.alphamessenger.data.EmbeddedTor.State.ON
    val percent = rememberTorProgress(linkUp)
    val warn = Color(0xFFF2A24B)
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "tor")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = if (on) 1f else 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "dot",
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.bgTertiary).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background((if (on) cs.success else warn).copy(alpha = alpha)))
            Text(
                when {
                    on -> stringResource(R.string.secret_tor_on)
                    percent != null -> stringResource(R.string.secret_tor_progress, percent)
                    else -> stringResource(R.string.secret_tor_connecting)
                },
                color = cs.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (!on) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = SecretViolet, trackColor = SecretViolet.copy(alpha = 0.2f),
            )
            Text(stringResource(R.string.secret_tor_wait_note), color = cs.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Text(
            stringResource(if (privateMode) R.string.secret_scope_all else R.string.secret_scope_secret_only),
            color = cs.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

private sealed interface CheckState {
    data object Idle : CheckState
    data object Busy : CheckState
    data class Done(val tor: Boolean, val ip: String, val country: String) : CheckState
    data object Failed : CheckState
}

/** Proof that the chat goes through Tor, and a new circuit when it is slow. */
@Composable
fun SecretRouteCard(onCheck: suspend () -> String?, onNewCircuit: suspend () -> Boolean) {
    val cs = AlphaTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var check by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<CheckState>(CheckState.Idle) }
    var renewing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val runCheck: () -> Unit = {
        check = CheckState.Busy
        scope.launch {
            check = onCheck()?.let { json ->
                runCatching {
                    val o = org.json.JSONObject(json)
                    CheckState.Done(o.optBoolean("tor"), o.optString("ip"), o.optString("country"))
                }.getOrNull()
            } ?: CheckState.Failed
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.bgTertiary).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val c = check) {
            CheckState.Idle -> Unit
            CheckState.Busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = SecretViolet, strokeWidth = 2.dp)
                Text(stringResource(R.string.secret_checking), color = cs.textPrimary, fontSize = 15.sp)
            }
            is CheckState.Done -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        if (c.tor) AlphaIcons.Outlined.VerifiedUser else AlphaIcons.Outlined.WarningAmber, null,
                        tint = if (c.tor) cs.success else cs.danger, modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(if (c.tor) R.string.secret_check_ok else R.string.secret_check_not_tor),
                        color = cs.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (c.ip.isNotBlank()) {
                    Text(
                        stringResource(R.string.secret_check_exit, c.country.ifBlank { "?" }, c.ip),
                        color = cs.textSecondary, fontSize = 13.sp,
                    )
                }
            }
            CheckState.Failed -> Text(stringResource(R.string.secret_check_fail), color = cs.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Text(stringResource(R.string.secret_route_note), color = cs.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, SecretViolet.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable(enabled = check != CheckState.Busy && !renewing) { runCheck() },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.secret_check), color = SecretViolet, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(SecretViolet)
                    .clickable(enabled = !renewing) {
                        renewing = true
                        check = CheckState.Idle
                        scope.launch {
                            onNewCircuit()
                            renewing = false
                            runCheck()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (renewing) R.string.secret_new_circuit_busy else R.string.secret_new_circuit),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
        }
    }
}

/** A secret chat's part of its info page: how it travels, its timer, what is
 *  always on, and the way out. */
@Composable
fun SecretInfoSection(
    linkUp: Boolean,
    /** Tor check through the chat's route: JSON, or null when it failed. */
    onCheck: suspend () -> String?,
    onNewCircuit: suspend () -> Boolean,
    timerSeconds: Int,
    /** (seconds, label) of every timer choice. */
    timerOptions: List<Pair<Int, String>>,
    onTimer: (Int) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = AlphaTheme.colorScheme
    var timerOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val timerLabel = timerOptions.firstOrNull { it.first == timerSeconds }?.second.orEmpty()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.secret_info_key), color = cs.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        Text(stringResource(R.string.secret_conn_title).uppercase(), color = cs.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        SecretConnectionCard(linkUp)
        Text(stringResource(R.string.secret_route_title).uppercase(), color = cs.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        SecretRouteCard(onCheck, onNewCircuit)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.bgTertiary)) {
            Row(
                Modifier.fillMaxWidth().clickable { timerOpen = !timerOpen }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(AlphaIcons.Outlined.Disappearing, null, tint = SecretViolet, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.secret_info_timer), color = cs.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(timerLabel, color = cs.textSecondary, fontSize = 14.sp)
                Icon(
                    AlphaIcons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = cs.textSecondary,
                    modifier = Modifier.rotate(if (timerOpen) 90f else 0f),
                )
            }
            androidx.compose.animation.AnimatedVisibility(timerOpen) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    timerOptions.forEach { (secs, label) ->
                        val on = secs == timerSeconds
                        Row(
                            Modifier.fillMaxWidth().clickable { onTimer(secs); timerOpen = false }
                                .padding(start = 48.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, color = if (on) SecretViolet else cs.textPrimary, fontSize = 15.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (on) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(SecretViolet))
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.secret_info_always).uppercase(), color = cs.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.secret_always_list), color = cs.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp))
                .background(cs.danger.copy(alpha = 0.12f)).clickable(onClick = onEnd),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.secret_end), color = cs.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** The top of a secret chat's history: where it stands and what it promises. */
@Composable
fun SecretChatNotice(state: String, peerName: String) {
    val cs = AlphaTheme.colorScheme
    val headline = when (state) {
        "invited" -> stringResource(R.string.secret_invited_text, peerName)
        "accepted" -> stringResource(R.string.secret_accepted_text)
        "waiting" -> stringResource(R.string.secret_waiting_peer, peerName)
        "declined" -> stringResource(R.string.secret_declined_text)
        else -> stringResource(R.string.secret_chat_title)
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(SecretViolet.copy(alpha = 0.12f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecretMark(size = 30.dp)
            Text(
                headline, color = if (state == "active" || state.isEmpty()) cs.textPrimary else SecretViolet,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    R.string.secret_point_e2ee, R.string.secret_point_traces,
                    R.string.secret_point_timer, R.string.secret_point_noforward,
                ).forEach { res ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 6.dp).size(5.dp).clip(RoundedCornerShape(3.dp)).background(SecretViolet))
                        Text(stringResource(res), color = cs.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

/** Instead of the composer while we have not answered the invitation. */
@Composable
fun SecretAnswerBar(onAccept: () -> Unit, onDecline: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(cs.bgTertiary)
                .clickable(onClick = onDecline),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.secret_offer_decline), color = cs.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(SecretViolet)
                .clickable(onClick = onAccept),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.secret_offer_accept), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}
