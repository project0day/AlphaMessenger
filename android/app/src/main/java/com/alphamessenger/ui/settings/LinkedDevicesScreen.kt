package com.alphamessenger.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * This account's phones: this one, the linked ones (each can be unlinked), and
 * "Link a phone" — the new phone shows a code, this one scans it. A link in
 * progress shows how far it is (chats joined, owners asked, history sent).
 */
@Composable
fun LinkedDevicesScreen(repository: AlphaCoreRepository, onBack: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf<List<AlphaCoreRepository.LinkedDevice>>(emptyList()) }
    var status by remember { mutableStateOf(org.json.JSONObject()) }
    var scanning by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf<AlphaCoreRepository.LinkedDevice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    LaunchedEffect(rev) { devices = repository.linkedDevices() }
    LaunchedEffect(Unit) { repository.devicesChanged.collect { rev++ } }
    // A link runs on for a while after the scan: follow it while it does.
    LaunchedEffect(Unit) {
        while (true) {
            status = runCatching { org.json.JSONObject(repository.linkStatus()) }.getOrDefault(org.json.JSONObject())
            delay(if (status.optString("stage") in setOf("offered", "adding", "history")) 1_500 else 5_000)
        }
    }

    OptionScreen(title = stringResource(R.string.settings_devices_title), onBack = onBack) {
        val stage = status.optString("stage")
        val fresh = System.currentTimeMillis() / 1000 - status.optLong("at") < 30 * 60
        if (stage.isNotEmpty() && fresh) {
            Spacer(Modifier.height(12.dp))
            OptionGroup {
                Column(Modifier.padding(16.dp)) {
                    val (total, done, asked) = Triple(status.optInt("total"), status.optInt("done"), status.optInt("asked"))
                    Text(
                        when (stage) {
                            "offered" -> stringResource(R.string.devices_stage_offered)
                            "adding" -> stringResource(R.string.devices_stage_adding, done, total)
                            "history" -> stringResource(R.string.devices_stage_history)
                            else -> stringResource(R.string.devices_stage_done)
                        },
                        color = cs.textPrimary, fontSize = 16.sp,
                    )
                    if (stage != "done") {
                        Spacer(Modifier.height(10.dp))
                        if (stage == "adding" && total > 0) LinearProgressIndicator(
                            progress = { done / total.toFloat() }, color = cs.accent, trackColor = cs.bgTertiary,
                            modifier = Modifier.fillMaxWidth(),
                        ) else LinearProgressIndicator(color = cs.accent, trackColor = cs.bgTertiary, modifier = Modifier.fillMaxWidth())
                    }
                    if (asked > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.devices_stage_asked, asked), color = cs.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }

        val (mine, others) = devices.partition { it.isThis }
        OptionSection(stringResource(R.string.devices_this))
        OptionGroup {
            OptionRow(
                title = mine.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: repository.thisDeviceName,
                icon = AlphaIcons.Outlined.PhonelinkErase,
                chevron = false,
            )
        }

        OptionSection(stringResource(R.string.devices_linked))
        OptionGroup {
            if (others.isEmpty()) {
                OptionRow(title = stringResource(R.string.devices_none), chevron = false)
            }
            others.forEachIndexed { i, d ->
                if (i > 0) OptionDivider()
                OptionRow(
                    title = d.name.ifBlank { stringResource(R.string.devices_unnamed) },
                    subtitle = d.addedAt.takeIf { it > 0 }?.let {
                        stringResource(R.string.devices_added, dateFormat.format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())))
                    },
                    icon = AlphaIcons.Outlined.PhonelinkErase,
                    onClick = { unlinking = d },
                )
            }
            if (com.alphamessenger.ui.onboarding.LinkFlow.LINKING_ENABLED) {
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.devices_link_button),
                    icon = AlphaIcons.Outlined.QrCodeScanner,
                    onClick = { error = null; scanning = true },
                )
            }
        }
        if (com.alphamessenger.ui.onboarding.LinkFlow.LINKING_ENABLED) OptionNote(stringResource(R.string.devices_note))
        error?.let { OptionNote(stringResource(R.string.devices_link_failed, it)) }
    }

    if (scanning) {
        com.alphamessenger.ui.chatlist.QrScannerSheet(
            colors = cs,
            accept = { it.startsWith("alphalink:1:") },
            onDismiss = { scanning = false },
            onResult = { qr ->
                scanning = false
                scope.launch {
                    runCatching { repository.linkDevice(qr) }
                        .onFailure { error = it.message ?: it.javaClass.simpleName }
                    rev++
                }
            },
        )
    }

    unlinking?.let { d ->
        AlertDialog(
            onDismissRequest = { unlinking = null },
            containerColor = cs.bgSecondary,
            title = { Text(stringResource(R.string.devices_unlink_title, d.name.ifBlank { stringResource(R.string.devices_unnamed) }), color = cs.textPrimary) },
            text = { Text(stringResource(R.string.devices_unlink_text), color = cs.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    unlinking = null
                    scope.launch {
                        runCatching { repository.unlinkDevice(d.id) }.onFailure { error = it.message }
                        rev++
                    }
                }) { Text(stringResource(R.string.devices_unlink), color = cs.danger) }
            },
            dismissButton = { TextButton(onClick = { unlinking = null }) { Text(stringResource(R.string.common_cancel), color = cs.textMuted) } },
        )
    }
}
