package com.alphamessenger.calls.group

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Entry points to group calls for screens that are not Hilt-injected. */
object GroupCalls {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access { fun groupCallManager(): GroupCallManager }

    fun manager(ctx: Context): GroupCallManager =
        EntryPointAccessors.fromApplication(ctx.applicationContext, Access::class.java).groupCallManager()

    const val EXTRA_START_CHAT = "gcall_start_chat"
    const val EXTRA_JOIN_CALL = "gcall_join_call"
    const val EXTRA_VIDEO = "gcall_video"
    const val EXTRA_RING_AGAIN = "gcall_ring_again"

    /** Open the call screen to start a call in [chatId] (permissions are asked there). */
    fun start(ctx: Context, chatId: String, video: Boolean, ringAgain: Boolean = false) {
        ctx.startActivity(Intent(ctx, GroupCallActivity::class.java)
            .apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .putExtra(EXTRA_START_CHAT, chatId).putExtra(EXTRA_VIDEO, video).putExtra(EXTRA_RING_AGAIN, ringAgain))
    }

    /** Back to the call screen. */
    fun open(ctx: Context) {
        ctx.startActivity(Intent(ctx, GroupCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Open the call screen to join the call going on in [chatId]. */
    fun join(ctx: Context, chatId: String, callId: String) {
        ctx.startActivity(Intent(ctx, GroupCallActivity::class.java)
            .putExtra(EXTRA_START_CHAT, chatId).putExtra(EXTRA_JOIN_CALL, callId).putExtra(EXTRA_VIDEO, false))
    }
}

/** Over a group chat while a call is on in it: who is in, and "Join". */
@Composable
fun GroupCallBanner(chatId: String) {
    val ctx = LocalContext.current
    val manager = remember { GroupCalls.manager(ctx) }
    LaunchedEffect(chatId) { manager.refreshActive(chatId) }
    val active by manager.active.collectAsState()
    val call = active[chatId] ?: return
    val ok = Color(0xFF2FB37A)
    val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
    Row(
        // An opaque card: the chat must not show through it.
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(cs.bgSecondary).background(ok.copy(alpha = 0.16f))
            .clickable { GroupCalls.join(ctx, chatId, call.callId) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (call.video) AlphaIcons.Outlined.Videocam else AlphaIcons.Outlined.Phone, null, tint = ok, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.gcall_banner), color = ok, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.gcall_banner_count, call.count), color = ok.copy(alpha = 0.8f), fontSize = 12.sp)
        }
        Text(
            stringResource(R.string.gcall_join), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(ok).padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** While we are in a group call and its screen is folded: back to it, mute, leave. */
@Composable
fun GroupCallReturnBar() {
    val ctx = LocalContext.current
    val manager = remember { GroupCalls.manager(ctx) }
    val st by manager.state.collectAsState()
    if (st.phase != GroupCallPhase.IN_CALL) return
    val colors = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
    val ink = colors.msgOutText
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val secs = ((now - st.since) / 1000).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.accent, colors.msgOutEnd)))
            .clickable { GroupCalls.open(ctx) }
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(com.alphamessenger.ui.icons.AlphaIcons.Filled.Group, null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.gcall_bar_title, st.members.size + 1) + " · " + "%d:%02d".format(secs / 60, secs % 60),
                color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(stringResource(R.string.call_tap_to_return), color = ink.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1)
        }
        androidx.compose.foundation.layout.Box(
            Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (st.mic) Color.White.copy(alpha = 0.22f) else Color.White)
                .clickable { manager.setMic(!st.mic) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (st.mic) com.alphamessenger.ui.icons.AlphaIcons.Filled.Mic else com.alphamessenger.ui.icons.AlphaIcons.Filled.MicOff,
                null, tint = if (st.mic) ink else Color(0xFF111318), modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        androidx.compose.foundation.layout.Box(
            Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFE5484D)).clickable { manager.leave() },
            contentAlignment = Alignment.Center,
        ) { Icon(com.alphamessenger.ui.icons.AlphaIcons.Filled.CallEnd, stringResource(R.string.gcall_leave), tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}
