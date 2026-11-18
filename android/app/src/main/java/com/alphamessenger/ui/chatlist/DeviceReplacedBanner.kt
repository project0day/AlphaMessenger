package com.alphamessenger.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.flow.MutableStateFlow

/** When another phone took this account over (restored from a backup), since then (unix seconds). */
object DeviceReplaced {
    val at = MutableStateFlow(0L)
}

/**
 * Another phone restored this account: this one is off the network for good
 * (two phones on one place in the groups fork each other). Says so, and offers
 * to bring the account back here — a fresh start, then Restore.
 */
@Composable
fun DeviceReplacedBanner(repository: AlphaCoreRepository, colors: AlphaColorScheme) {
    val at by DeviceReplaced.at.collectAsState()
    LaunchedEffect(Unit) {
        while (DeviceReplaced.at.value == 0L) {
            val found = runCatching { repository.deviceReplacedAt() }.getOrDefault(0L)
            if (found > 0) { DeviceReplaced.at.value = found; break }
            kotlinx.coroutines.delay(10 * 60_000L)
        }
    }
    if (at <= 0) return
    var confirm by remember { mutableStateOf(false) }
    val date = remember(at) {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at * 1000))
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.danger.copy(alpha = 0.14f))
            .clickable { confirm = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.device_replaced_title), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.device_replaced_body, date), color = colors.textSecondary, fontSize = 13.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp))
        Text(stringResource(R.string.device_replaced_action), color = colors.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp))
    }
    if (confirm) {
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.device_replaced_action), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.device_replaced_confirm), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { com.alphamessenger.data.PanicWipe.wipe(ctx) }) {
                    Text(stringResource(R.string.device_replaced_wipe), color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary) }
            },
            containerColor = colors.bgSecondary,
        )
    }
}
