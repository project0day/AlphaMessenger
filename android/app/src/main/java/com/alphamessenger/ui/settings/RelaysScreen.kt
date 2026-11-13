package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/**
 * Relays, in the common settings look (OptionKit):
 *   ▪ status card: "N of M online · average ping", refresh in the bar
 *   ▪ My relays — health dot, domain, ping, Tor badge, on/off, delete; "Add manually" row
 *   ▪ Recommended — tap to add
 *   ▪ Anonymity — private mode (Tor) switch, in-app Tor status, note
 */
@Composable
fun RelaysScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsState()
    val colors = AlphaTheme.colorScheme
    var addDialogOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<String?>(null) }
    var manualUrl by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val torLive by com.alphamessenger.data.EmbeddedTor.enabled.collectAsState()

    val existingUrls = remember(ui.relays) {
        ui.relays.map { it.url.lowercase(java.util.Locale.ROOT) }.toSet()
    }
    val presets = remember(existingUrls) {
        (CuratedRelays.clearnet + CuratedRelays.tor)
            .filter { url -> url.lowercase(java.util.Locale.ROOT) !in existingUrls }
    }

    OptionScreen(
        title = stringResource(R.string.relays_title),
        onBack = onBack,
        actions = {
            if (ui.pinging) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = colors.accent,
                    modifier = Modifier.padding(end = 16.dp).size(20.dp),
                )
            } else {
                IconButton(onClick = { viewModel.pingRelays() }, modifier = Modifier.size(48.dp)) {
                    Icon(AlphaIcons.Outlined.Refresh, stringResource(R.string.relays_refresh_ping), tint = colors.textPrimary)
                }
            }
        },
    ) {
        // States change by themselves (a relay goes down, a probe brings it back): follow them while shown.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                viewModel.loadRelays()
                kotlinx.coroutines.delay(3_000)
            }
        }
        Spacer(Modifier.height(8.dp))
        val online = ui.relays.count { it.isConnected && it.isEnabled }
        val down = ui.relays.count { it.isEnabled && it.state == "unreachable" }
        val total = ui.relays.count { it.isEnabled }
        val avgPing = ui.relays
            .filter { it.isEnabled && it.pingMs > 0 }
            .map { it.pingMs }
            .let { if (it.isEmpty()) 0 else it.average().toInt() }
        OptionGroup {
            val ago = if (ui.lastPingedAt == 0L) stringResource(R.string.relays_not_checked_yet)
            else {
                val secs = ((System.currentTimeMillis() - ui.lastPingedAt) / 1000).coerceAtLeast(0)
                when {
                    secs < 60 -> stringResource(R.string.relays_updated_secs_ago, secs)
                    secs < 3600 -> stringResource(R.string.relays_updated_min_ago, secs / 60)
                    else -> stringResource(R.string.relays_updated_long_ago)
                }
            }
            val avgPrefix = if (avgPing > 0) stringResource(R.string.relays_avg_ping_ms, avgPing) else ""
            val downPrefix = if (down > 0) stringResource(R.string.relays_status_down, down) else ""
            OptionRow(
                title = stringResource(R.string.relays_status_online, online, total),
                subtitle = downPrefix + avgPrefix + ago,
                icon = AlphaIcons.Outlined.WifiTethering,
                iconTint = when {
                    total > 0 && online == 0 -> colors.danger
                    down > 0 -> colors.warning
                    else -> colors.accent
                },
            )
        }

        OptionSection(stringResource(R.string.relays_section_mine))
        OptionGroup {
            // Working ones first, the fastest on top; switched-off ones last.
            val ordered = ui.relays.withIndex().sortedWith(
                compareBy<IndexedValue<RelayInfo>>(
                    { !it.value.isEnabled },
                    { when (it.value.health()) { RelayHealth.GREEN -> 0; RelayHealth.YELLOW -> 1; RelayHealth.UNKNOWN -> 2; RelayHealth.RED -> 3 } },
                    { if (it.value.pingMs > 0) it.value.pingMs else Int.MAX_VALUE },
                ),
            )
            ordered.forEach { (index, relay) ->
                RelayRow(
                    relay = relay,
                    colors = colors,
                    onToggle = { viewModel.toggleRelayEnabled(index) },
                    onRemove = { viewModel.removeRelay(index) },
                )
                OptionDivider()
            }
            OptionRow(
                title = stringResource(R.string.relays_add_manual),
                icon = AlphaIcons.Outlined.Add,
                chevron = false,
                onClick = { addDialogOpen = true },
            )
            OptionDivider()
            OptionRow(
                title = stringResource(R.string.relays_export_row),
                icon = AlphaIcons.Outlined.Share,
                chevron = false,
                onClick = { exportOpen = true },
            )
            OptionDivider()
            OptionRow(
                title = stringResource(R.string.relays_import_row),
                icon = AlphaIcons.Outlined.Download,
                chevron = false,
                onClick = { scanned = null; importOpen = true },
            )
        }
        OptionNote(stringResource(R.string.relays_share_note))
        if (ui.relays.isEmpty()) OptionNote(stringResource(R.string.relays_empty))

        if (presets.isNotEmpty()) {
            OptionSection(stringResource(R.string.relays_section_curated))
            OptionGroup {
                presets.forEachIndexed { i, url ->
                    if (i > 0) OptionDivider()
                    PresetRow(url = url, colors = colors, onAdd = { viewModel.addPresetRelay(url) })
                }
            }
        }

        OptionSection(stringResource(R.string.relays_section_anonymity))
        OptionGroup {
            OptionSwitchRow(
                title = stringResource(R.string.relays_tor_title),
                subtitle = stringResource(R.string.relays_tor_subtitle),
                checked = ui.torEnabled,
                onCheckedChange = { viewModel.toggleTor(it) },
            )
            if (ui.torEnabled) {
                OptionDivider()
                Box(Modifier.padding(horizontal = 16.dp)) { TorStatus(colors) }
            }
            OptionDivider()
            androidx.compose.runtime.LaunchedEffect(Unit) { com.alphamessenger.ui.components.TorButtonPref.load(ctx) }
            OptionSwitchRow(
                title = stringResource(R.string.tor_button_title),
                subtitle = stringResource(R.string.tor_button_subtitle),
                checked = com.alphamessenger.ui.components.TorButtonPref.visible,
                onCheckedChange = { com.alphamessenger.ui.components.TorButtonPref.set(ctx, it) },
            )
            OptionDivider()
            Box(Modifier.padding(horizontal = 16.dp)) { IpCheck(colors, viaTor = torLive) }
        }
        OptionNote(stringResource(R.string.relays_tor_note))

        // Tor keeps running for secret chats even with private mode off: say so, and what it costs.
        val secretCount by androidx.compose.runtime.produceState(0) {
            value = viewModel.repository.secretChatIds().size
        }
        OptionSection(stringResource(R.string.secret_settings_section))
        OptionGroup {
            OptionRow(
                title = stringResource(R.string.secret_settings_tor_title),
                subtitle = if (secretCount > 0) {
                    androidx.compose.ui.res.pluralStringResource(R.plurals.secret_settings_tor_running, secretCount, secretCount)
                } else stringResource(R.string.secret_settings_tor_idle),
                icon = AlphaIcons.Outlined.WifiTethering,
                iconTint = if (secretCount > 0) colors.accent else colors.textMuted,
            )
        }
        OptionNote(stringResource(R.string.secret_settings_note))
    }

    if (exportOpen) {
        RelayExportDialog(
            urls = ui.relays.filter { it.isEnabled && it.url.startsWith("wss://") }.map { it.url },
            colors = colors,
            onDismiss = { exportOpen = false },
        )
    }
    if (importOpen) {
        RelayImportDialog(
            known = ui.relays.map { it.url.trimEnd('/').lowercase(java.util.Locale.ROOT) }.toSet(),
            colors = colors,
            onScan = { scanOpen = true },
            scanned = scanned,
            onAdd = { urls -> urls.forEach { viewModel.addRelayUrl(it) } },
            onDismiss = { importOpen = false },
        )
    }
    if (scanOpen) {
        com.alphamessenger.ui.chatlist.QrScannerSheet(
            colors = colors,
            onDismiss = { scanOpen = false },
            onResult = { code -> scanned = code; scanOpen = false },
            accept = { parseRelayList(it).isNotEmpty() },
        )
    }

    if (addDialogOpen) {
        AddRelayDialog(
            url = manualUrl,
            onUrlChange = { manualUrl = it },
            onDismiss = { addDialogOpen = false; manualUrl = "" },
            onConfirm = {
                val u = manualUrl.trim()
                if (u.startsWith("wss://") || u.startsWith("ws://")) {
                    viewModel.addRelayUrl(u)
                    addDialogOpen = false
                    manualUrl = ""
                }
            },
            colors = colors,
        )
    }
}

// --- Relay row (configured) ---

@Composable
private fun RelayRow(
    relay: RelayInfo,
    colors: AlphaColorScheme,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val dot by animateColorAsState(
        targetValue = when (relay.health()) {
            RelayHealth.GREEN -> colors.success
            RelayHealth.YELLOW -> colors.warning
            RelayHealth.RED -> colors.danger
            RelayHealth.UNKNOWN -> colors.textMuted
        },
        label = "dot",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                relay.url.removePrefix("wss://").removePrefix("ws://"),
                color = if (relay.isEnabled) colors.textPrimary else colors.textMuted,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = when {
                    !relay.isEnabled -> stringResource(R.string.relays_state_disabled)
                    relay.state == "unreachable" -> stringResource(R.string.relays_state_down_retry, waitText(relay.retrySecs))
                    relay.state == "limited" -> stringResource(R.string.relays_state_limited, waitText(relay.retrySecs))
                    relay.state == "connecting" -> stringResource(R.string.relays_state_connecting)
                    relay.pingMs > 0 -> stringResource(R.string.relays_state_online_ping, relay.pingMs)
                    else -> stringResource(R.string.relays_state_online)
                }
                Text(label, color = colors.textMuted, fontSize = 13.sp)
                if (relay.isTor) {
                    Spacer(Modifier.width(8.dp))
                    TorBadge(colors)
                }
            }
            // What it does with us while connected: refuses our events, or keeps our inbox behind sign-in.
            val warning = when {
                !relay.isEnabled || relay.state != "connected" -> null
                relay.rejected > 0 && relay.accepted == 0 -> stringResource(R.string.relays_warn_rejects_all, relay.lastError)
                relay.lastError.isNotEmpty() -> stringResource(R.string.relays_warn_rejects_some, relay.lastError)
                relay.inboxAuth -> stringResource(R.string.relays_warn_inbox_auth)
                else -> null
            }
            if (warning != null) {
                Text(
                    warning,
                    color = if (relay.accepted == 0 && relay.rejected > 0) colors.danger else colors.warning,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = relay.isEnabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = colors.bgTertiary,
                uncheckedThumbColor = colors.textMuted,
                uncheckedBorderColor = colors.alphaBorder,
            ),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
            Icon(AlphaIcons.Outlined.Delete, stringResource(R.string.common_delete), tint = colors.danger, modifier = Modifier.size(20.dp))
        }
    }
}

/** "40 s", "4 min": how long until the next try or the end of a pause. */
@Composable
private fun waitText(secs: Int): String =
    if (secs < 60) stringResource(R.string.relays_secs, secs.coerceAtLeast(1))
    else stringResource(R.string.relays_mins, (secs + 59) / 60)

@Composable
private fun TorBadge(colors: AlphaColorScheme) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.accent.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text("TOR", color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

// --- Preset row ---

@Composable
private fun PresetRow(url: String, colors: AlphaColorScheme, onAdd: () -> Unit) {
    OptionRow(
        title = url.removePrefix("wss://").removePrefix("ws://"),
        onClick = onAdd,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (url.contains(".onion")) {
                    TorBadge(colors)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(AlphaIcons.Outlined.Add, stringResource(R.string.common_add), tint = colors.accent, modifier = Modifier.size(22.dp))
            }
        },
    )
}

// --- Add relay dialog ---

@Composable
private fun AddRelayDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    colors: AlphaColorScheme,
) {
    val valid = url.trim().startsWith("wss://") || url.trim().startsWith("ws://")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(R.string.relays_new_dialog_title), color = colors.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    stringResource(R.string.relays_new_dialog_hint),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.bgTertiary)
                        .border(
                            1.dp,
                            if (valid) colors.accent else colors.borderLight,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        decorationBox = { inner ->
                            if (url.isEmpty()) {
                                Text(
                                    "wss://relay.example.com",
                                    color = colors.textMuted,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            inner()
                        },
                    )
                }
                AnimatedVisibility(visible = url.isNotBlank() && !valid) {
                    Text(
                        stringResource(R.string.relays_url_invalid),
                        color = colors.danger,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = valid) {
                Text(stringResource(R.string.common_add), color = if (valid) colors.accent else colors.textMuted, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
            }
        },
    )
}

/** State of the in-app Tor: connecting (with bootstrap progress) or connected. */
@Composable
private fun TorStatus(colors: com.alphamessenger.ui.theme.AlphaColorScheme) {
    val state by com.alphamessenger.data.EmbeddedTor.state.collectAsState()
    var percent by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(state) {
        while (state != com.alphamessenger.data.EmbeddedTor.State.OFF) {
            percent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.alphamessenger.data.EmbeddedTor.bootstrapPercent()
            }
            if (percent == 100) break
            kotlinx.coroutines.delay(1_500)
        }
    }
    val ok = state == com.alphamessenger.data.EmbeddedTor.State.ON || percent == 100
    Text(
        when {
            ok -> stringResource(R.string.relays_tor_connected)
            else -> stringResource(R.string.relays_tor_connecting, percent ?: 0)
        },
        color = if (ok) colors.success else colors.warning,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    )
}

/** What the outside world sees: our address, its country, whether it is Tor.
 *  Checked when the screen opens and on tap; each check tells third-party
 *  services our address (through Tor they only see the exit). */
@Composable
private fun IpCheck(colors: com.alphamessenger.ui.theme.AlphaColorScheme, viaTor: Boolean) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<IpInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    fun run() {
        if (busy) return
        busy = true
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lookupIp(viaTor) }
            info = r
            failed = r == null
            busy = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(viaTor) { run() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.relays_ip_title), color = colors.textPrimary, fontSize = 15.sp)
            val i = info
            when {
                i != null -> {
                    Text(i.ip, color = colors.textSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        listOfNotNull(i.flag, i.country ?: stringResource(R.string.relays_ip_country_unknown)).joinToString(" "),
                        color = colors.textPrimary, fontSize = 14.sp,
                    )
                    Text(
                        stringResource(if (i.tor) R.string.relays_ip_tor_yes else R.string.relays_ip_tor_no),
                        color = if (i.tor) colors.success else colors.warning, fontSize = 13.sp,
                    )
                }
                failed -> Text(stringResource(R.string.relays_ip_failed), color = colors.warning, fontSize = 13.sp)
                else -> Text(stringResource(R.string.relays_ip_checking), color = colors.textMuted, fontSize = 13.sp)
            }
        }
        if (busy) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = colors.accent, modifier = Modifier.size(20.dp))
        } else {
            IconButton(onClick = { run() }, modifier = Modifier.size(40.dp)) {
                Icon(AlphaIcons.Outlined.Refresh, stringResource(R.string.relays_ip_check), tint = colors.accent)
            }
        }
    }
}

private data class IpInfo(val ip: String, val country: String?, val flag: String?, val tor: Boolean)

/** Address + country, or null when offline. Country from the first service that answers. */
private fun lookupIp(viaTor: Boolean): IpInfo? {
    fun get(url: String): String? = runCatching {
        val c = (if (viaTor) java.net.URL(url).openConnection(com.alphamessenger.data.EmbeddedTor.proxy())
                 else java.net.URL(url).openConnection()) as java.net.HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()
    fun json(url: String) = get(url)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    val tor = json("https://check.torproject.org/api/ip") ?: return null
    val ip = tor.optString("IP").takeIf { it.isNotBlank() } ?: return null
    val code: String? = sequenceOf(
        { json("https://api.country.is/$ip")?.optString("country") },
        { json("https://ipwho.is/$ip")?.optString("country_code") },
        { get("https://ipinfo.io/$ip/country")?.trim() },
    ).firstNotNullOfOrNull { f -> f()?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) } }
    val country = code?.let { java.util.Locale("", it).displayCountry.ifBlank { it } }
    val flag = code?.let { c -> String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A'))) }
    return IpInfo(ip, country, flag, tor.optBoolean("IsTor"))
}
