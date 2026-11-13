package com.alphamessenger.ui.update

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.feed.FeedPost
import com.alphamessenger.ui.feed.FeedStore
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.launch

/**
 * "Update available": shown while the feed has a build newer than this one.
 * [expanded] also shows what is new (settings); the chat list keeps it to one line until tapped.
 */
@Composable
fun UpdateCard(colors: AlphaColorScheme, repo: AlphaCoreRepository, modifier: Modifier = Modifier, expanded: Boolean = false) {
    val ctx = LocalContext.current
    FeedStore.load(ctx)
    val post = remember(FeedStore.posts, AppUpdate.laterTick) { if (expanded) AppUpdate.available(ctx) else AppUpdate.offerInList(ctx) } ?: return
    val state = AppUpdate.state
    var open by remember { mutableStateOf(expanded) }
    val busy = state is AppUpdate.State.Downloading || state is AppUpdate.State.Verifying || state is AppUpdate.State.Installing
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.accent.copy(alpha = 0.12f))
            .clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(AlphaIcons.Outlined.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_available), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (state) {
                        is AppUpdate.State.Downloading -> stringResource(R.string.update_downloading,
                            if (state.total > 0) (state.done * 100 / state.total).toInt() else 0)
                        AppUpdate.State.Verifying -> stringResource(R.string.update_verifying)
                        AppUpdate.State.Installing -> stringResource(R.string.update_installing)
                        is AppUpdate.State.Failed -> stringResource(when (state.reason) {
                            AppUpdate.Reason.DOWNLOAD -> R.string.update_failed_download
                            AppUpdate.Reason.CHECK -> R.string.update_failed_check
                            AppUpdate.Reason.PERMISSION -> R.string.update_failed_permission
                            AppUpdate.Reason.INSTALL -> R.string.update_failed_install
                        })
                        AppUpdate.State.Idle -> stringResource(R.string.update_version, post.versionName ?: post.versionCode.toString())
                    },
                    color = if (state is AppUpdate.State.Failed) colors.danger else colors.textSecondary,
                    fontSize = 13.sp, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!expanded && !busy) Text(stringResource(R.string.update_later), color = colors.textSecondary, fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { AppUpdate.later(ctx, post.versionCode) }
                    .padding(horizontal = 8.dp, vertical = 8.dp))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(if (busy) colors.bgTertiary else colors.accent)
                    .clickable(enabled = !busy) { AppUpdate.start(ctx, repo, post) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.update_install), color = if (busy) colors.textMuted else Color.White,
                    fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (state is AppUpdate.State.Downloading) {
            Spacer(Modifier.height(10.dp))
            val p = if (state.total > 0) state.done.toFloat() / state.total else 0f
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = colors.accent, trackColor = colors.bgTertiary)
        }
        if (open && post.body.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.update_whats_new), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            com.alphamessenger.ui.chat.text.MessageText(
                text = post.body, color = colors.textPrimary, fontSize = 13.5.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Notices from Alpha on top of the chat list. An urgent one stays; an important one can be hidden here. */
@Composable
fun AlertBanners(colors: AlphaColorScheme, repo: AlphaCoreRepository, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    FeedStore.load(ctx)
    val prefs = remember { ctx.getSharedPreferences("alpha_alerts", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet()).orEmpty()) }
    val alerts = FeedStore.alerts.filter { it.level == FeedPost.ALERT_CRITICAL || it.id !in hidden }
    if (alerts.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        alerts.forEach { a ->
            val critical = a.level == FeedPost.ALERT_CRITICAL
            val tone = if (critical) colors.danger else Color(0xFFF5A524)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tone.copy(alpha = 0.14f))
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(if (critical) AlphaIcons.Outlined.ErrorOutline else AlphaIcons.Outlined.WarningAmber, null, tint = tone,
                    modifier = Modifier.size(22.dp).padding(top = 1.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (a.title.isNotBlank()) Text(a.title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (a.body.isNotBlank()) Text(a.body, color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 18.sp)
                    if (FeedStore.isOwner) Text(stringResource(R.string.alert_remove), color = colors.danger, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp).clickable { scope.launch { FeedStore.delete(ctx, repo, a.id) } })
                }
                if (!critical) Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable {
                        hidden = hidden + a.id
                        prefs.edit().putStringSet("hidden", hidden).apply()
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.alert_hide), tint = colors.textMuted, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}
