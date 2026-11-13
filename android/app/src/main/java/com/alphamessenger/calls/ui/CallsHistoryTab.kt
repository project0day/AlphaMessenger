package com.alphamessenger.calls.ui

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.calls.CallHistoryEntry
import com.alphamessenger.calls.CallHistoryStore
import com.alphamessenger.calls.CallLauncher
import com.alphamessenger.calls.CallOutcome
import com.alphamessenger.calls.CallType
import com.alphamessenger.ui.theme.AlphaColorScheme
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CallsHistoryViewModel @Inject constructor(
    val store: CallHistoryStore,
) : ViewModel() {
    val entries = store.entries
    fun delete(callIds: Set<String>) = store.delete(callIds)
}

/**
 * THE "Calls" tab — the local call journal.
 *
 * ## Selection
 *  Long-press a row → checkmark badges appear, action buttons hide, the MAIN
 *  app top bar (Alpha title + search + avatar) is replaced by a selection bar
 *  (Close + "Selected: N" + Delete). The selection state is lifted to the host
 *  (ChatListScreen) so it can swap the top bar.
 *
 * ## FAB
 *  The FAB is a phone icon → opens a "new call" contact sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallsHistoryTab(
    modifier: Modifier = Modifier,
    colors: AlphaColorScheme,
    onOpenChat: (String) -> Unit,
    onStartNewCall: () -> Unit = {},
    /** Selected call IDs — lifted to the host so the top bar can swap. */
    selectedIds: Set<String> = emptySet(),
    onSelectionChange: (Set<String>) -> Unit = {},
) {
    val vm: CallsHistoryViewModel = hiltViewModel()
    val entries by vm.entries.collectAsState()
    val ctx = LocalContext.current
    val inSelectionMode = selectedIds.isNotEmpty()

    if (entries.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(top = com.alphamessenger.ui.chatlist.LocalListTopInset.current).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(AlphaIcons.Outlined.Phone, null, tint = colors.textMuted, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.call_history_empty), color = colors.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.call_history_empty_hint),
                color = colors.textMuted, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = com.alphamessenger.ui.chatlist.LocalListTopInset.current,
                bottom = com.alphamessenger.ui.chatlist.LocalDockInset.current,
            ),
        ) {
            items(entries, key = { it.callId + it.atMs }) { e ->
                val isSelected = e.callId in selectedIds
                CallHistoryRow(
                    entry = e,
                    colors = colors,
                    isSelected = isSelected,
                    inSelectionMode = inSelectionMode,
                    onCallBack = {
                        if (inSelectionMode) {
                            onSelectionChange(if (isSelected) selectedIds - e.callId else selectedIds + e.callId)
                        } else {
                            CallLauncher.start(ctx, e.chatId, if (e.video) CallType.VIDEO else CallType.AUDIO)
                        }
                    },
                    onMessage = { onOpenChat(e.chatId) },
                    onLongPress = {
                        if (!inSelectionMode) onSelectionChange(selectedIds + e.callId)
                    },
                )
            }
            // Room for the tab dock and its call button.
            item { Spacer(Modifier.height(com.alphamessenger.ui.chatlist.LocalDockInset.current)) }
        }

    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallHistoryRow(
    entry: CallHistoryEntry,
    colors: AlphaColorScheme,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onCallBack: () -> Unit,
    onMessage: () -> Unit,
    onLongPress: () -> Unit,
) {
    val missed = entry.outcome == CallOutcome.MISSED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(
                onClick = onCallBack,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection checkmark badge (left of avatar).
        if (inSelectionMode) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accent else Color.Transparent)
                    .border(
                        width = 2.dp,
                        color = if (isSelected) colors.accent else colors.textMuted.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        AlphaIcons.Outlined.Check,
                        contentDescription = stringResource(R.string.call_selected),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.bgTertiary),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.peerAvatarPath.isNotBlank()) {
                AsyncImage(
                    model = java.io.File(entry.peerAvatarPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    entry.peerName.take(1).uppercase().ifBlank { "?" },
                    color = colors.textSecondary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.peerName.ifBlank { stringResource(R.string.peer_fallback) },
                color = if (missed) colors.danger else colors.textPrimary,
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dirIcon, dirTint) = when {
                    missed -> AlphaIcons.AutoMirrored.Filled.CallMissed to colors.danger
                    entry.outgoing -> AlphaIcons.AutoMirrored.Filled.CallMade to colors.textMuted
                    else -> AlphaIcons.AutoMirrored.Filled.CallReceived to Color34C759
                }
                Icon(dirIcon, null, tint = dirTint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    outcomeLabel(entry) + " · " + formatCallTime(entry.atMs),
                    color = colors.textMuted, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
        if (!inSelectionMode) {
            IconButton(onClick = onMessage) {
                Icon(AlphaIcons.Outlined.ChatBubbleOutline, stringResource(R.string.calls_message), tint = colors.textMuted, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onCallBack) {
                Icon(
                    if (entry.video) AlphaIcons.Outlined.Videocam else AlphaIcons.Outlined.Phone,
                    stringResource(R.string.calls_callback), tint = colors.accent, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private val Color34C759 = androidx.compose.ui.graphics.Color(0xFF34C759)

@Composable
private fun outcomeLabel(e: CallHistoryEntry): String = when (e.outcome) {
    CallOutcome.ANSWERED -> {
        val d = e.durationSec
        if (d >= 60) "%d:%02d".format(d / 60, d % 60) else pluralStringResource(R.plurals.call_seconds_abbrev, d, d)
    }
    CallOutcome.MISSED -> stringResource(R.string.call_missed)
    CallOutcome.REJECTED -> if (e.outgoing) stringResource(R.string.call_declined) else stringResource(R.string.call_declined_by_you)
    CallOutcome.BUSY -> stringResource(R.string.call_busy)
    CallOutcome.NO_ANSWER -> stringResource(R.string.call_error_timeout)
    CallOutcome.CANCELLED -> stringResource(R.string.call_cancelled)
    CallOutcome.FAILED -> stringResource(R.string.call_failed)
    CallOutcome.REFUSED -> stringResource(R.string.call_refused_by_rules)
}

private fun formatCallTime(atMs: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = atMs
    val that = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return if (that == today) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))
    } else {
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(atMs))
    }
}
