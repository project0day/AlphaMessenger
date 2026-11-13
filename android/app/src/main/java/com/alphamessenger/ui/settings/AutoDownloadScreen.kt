package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.data.MediaAutoDownload
import com.alphamessenger.data.MediaAutoDownload.Net
import com.alphamessenger.ui.theme.AlphaTheme

private val SIZE_OPTIONS = listOf(1, 5, 10, 25, 50, 100, 0) // 0 = no limit
private val NETS = listOf(Net.WIFI, Net.MOBILE, Net.ROAMING)

@Composable
private fun typeLabel(type: String): String = when (type) {
    "photo" -> "📷  " + stringResource(R.string.media_kind_photo)
    "video" -> "🎬  " + stringResource(R.string.media_kind_video)
    "gif" -> "🎞  GIF"
    "round" -> "⭕  " + stringResource(R.string.round_label)
    "voice" -> "🎤  " + stringResource(R.string.media_kind_voice)
    "music" -> "🎵  " + stringResource(R.string.media_kind_music)
    else -> "📄  " + stringResource(R.string.media_kind_file)
}

@Composable
private fun netLabel(net: Net): String = stringResource(when (net) {
    Net.WIFI -> R.string.autodownload_col_wifi
    Net.MOBILE -> R.string.autodownload_col_mobile
    else -> R.string.autodownload_col_roaming
})

/**
 * Auto-download as one table: media types down, networks across — a dot per
 * cell (tap to switch) — and the size limit of each network under it. Then
 * what "don't keep on servers" changes, a reset, and "what downloads now".
 */
@Composable
fun AutoDownloadScreen(onBack: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    // SharedPreferences are not observable: a write bumps this to re-read.
    var rev by remember { mutableIntStateOf(0) }
    var checkOpen by remember { mutableStateOf(false) }

    com.alphamessenger.ui.components.OptionScreen(
        title = stringResource(R.string.settings_autodownload_title),
        onBack = onBack,
    ) {
        com.alphamessenger.ui.components.OptionNote(stringResource(R.string.autodownload_note))
        com.alphamessenger.ui.components.OptionSection(stringResource(R.string.autodownload_what))
        com.alphamessenger.ui.components.OptionGroup {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                // Header: the networks.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(stringResource(R.string.autodownload_col_type), color = cs.textMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    NETS.forEach { Text(netLabel(it), color = cs.textMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(60.dp)) }
                }
                MediaAutoDownload.TYPES.forEach { type ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(typeLabel(type), color = cs.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        NETS.forEach { net ->
                            val on = remember(rev, type, net) { MediaAutoDownload.isEnabled(ctx, net, type) }
                            Box(Modifier.width(60.dp).height(40.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape)
                                        .background(if (on) cs.accent else Color.Transparent)
                                        .border(2.dp, if (on) cs.accent else cs.textMuted.copy(alpha = 0.5f), CircleShape)
                                        .clickable { MediaAutoDownload.setEnabled(ctx, net, type, !on); rev++ },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (on) Icon(AlphaIcons.Filled.Check, null, tint = com.alphamessenger.ui.theme.readableOn(cs.accent), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                com.alphamessenger.ui.components.OptionDivider(inset = false)
                // The size limit per network.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.autodownload_max_size), color = cs.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    NETS.forEach { net -> SizeCell(net, rev) { rev++ } }
                }
            }
        }
        com.alphamessenger.ui.components.OptionNote(stringResource(R.string.autodownload_nostore_note))

        Spacer(Modifier.height(8.dp))
        com.alphamessenger.ui.components.OptionGroup {
            com.alphamessenger.ui.components.OptionRow(
                title = stringResource(R.string.autodownload_check),
                subtitle = stringResource(R.string.autodownload_check_hint),
                onClick = { checkOpen = true },
            )
            com.alphamessenger.ui.components.OptionDivider()
            com.alphamessenger.ui.components.OptionRow(
                title = stringResource(R.string.autodownload_reset),
                onClick = { MediaAutoDownload.reset(ctx); rev++ },
            )
        }
    }

    if (checkOpen) {
        val net = remember { MediaAutoDownload.currentNet(ctx) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { checkOpen = false },
            containerColor = cs.bgSecondary,
            title = { Text(stringResource(R.string.autodownload_check_now, if (net == Net.NONE) stringResource(R.string.autodownload_no_network) else netLabel(net)), color = cs.textPrimary) },
            text = {
                Column {
                    MediaAutoDownload.TYPES.forEach { type ->
                        val on = net != Net.NONE && MediaAutoDownload.isEnabled(ctx, net, type)
                        val cap = MediaAutoDownload.maxMb(ctx, net)
                        val what = when {
                            !on -> stringResource(R.string.autodownload_check_tap)
                            cap <= 0 -> stringResource(R.string.autodownload_check_any)
                            else -> stringResource(R.string.autodownload_check_upto, cap)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(typeLabel(type), color = cs.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(what, color = if (on) cs.accent else cs.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { checkOpen = false }) { Text(stringResource(R.string.common_done), color = cs.accent) } },
        )
    }
}

@Composable
private fun SizeCell(net: Net, rev: Int, onChange: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val cap = remember(rev, net) { MediaAutoDownload.maxMb(ctx, net) }
    Box(Modifier.width(60.dp), contentAlignment = Alignment.Center) {
        Text(
            if (cap <= 0) "∞" else stringResource(R.string.autodownload_mb_short, cap),
            color = cs.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(horizontal = 6.dp, vertical = 6.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            SIZE_OPTIONS.forEach { mb ->
                DropdownMenuItem(
                    text = { Text(if (mb <= 0) stringResource(R.string.autodownload_unlimited) else stringResource(R.string.autodownload_up_to_mb, mb)) },
                    onClick = { MediaAutoDownload.setMaxMb(ctx, net, mb); menu = false; onChange() },
                )
            }
        }
    }
}
