package com.alphamessenger.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaColorScheme

private val RELAY_URL = Regex("""wss://[A-Za-z0-9.\-]+(?::\d{1,5})?(?:/[A-Za-z0-9._~\-/]*)?""")

/** Every secure relay address in [text] (a shared list, a message, a QR), once each. */
internal fun parseRelayList(text: String): List<String> =
    RELAY_URL.findAll(text).map { it.value.trimEnd('/', '.') }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        .take(20)
        .toList()

/** The list as text: readable by people, parsed back by [parseRelayList]. */
internal fun relayListText(ctx: Context, urls: List<String>): String =
    ctx.getString(R.string.relays_share_header) + "\n" + urls.joinToString("\n")

private fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Our enabled relays as a QR, with copy and share. */
@Composable
internal fun RelayExportDialog(urls: List<String>, colors: AlphaColorScheme, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember(urls) { relayListText(ctx, urls) }
    val qr = remember(text) { com.alphamessenger.ui.chatlist.generateQrBitmap(text, 640) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(R.string.relays_export_title), color = colors.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.relays_export_hint), color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                if (qr != null) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                            .background(Color.White).padding(12.dp),
                    ) {
                        Image(qr.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(12.dp))
                }
                urls.forEach { Text(it, color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = {
            TextButton(onClick = { shareText(ctx, text); onDismiss() }) {
                Text(stringResource(R.string.common_share), color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("relays", text))
                onDismiss()
            }) { Text(stringResource(R.string.common_copy), color = colors.textSecondary) }
        },
    )
}

/** Relays from a friend's list (pasted text or a scanned QR): pick which to add. */
@Composable
internal fun RelayImportDialog(
    known: Set<String>,
    colors: AlphaColorScheme,
    onScan: () -> Unit,
    scanned: String?,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember {
        val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        mutableStateOf(if (parseRelayList(clip).isNotEmpty()) clip else "")
    }
    if (scanned != null && scanned != text && parseRelayList(scanned).isNotEmpty()) text = scanned
    val found = remember(text) { parseRelayList(text) }
    val picked = remember(found) {
        mutableStateListOf<String>().apply { addAll(found.filter { it.lowercase(java.util.Locale.ROOT) !in known }) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(R.string.relays_import_title), color = colors.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.relays_import_hint), color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
                        .background(colors.bgTertiary).padding(10.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        if (clip.isNotBlank()) text = clip
                    }) {
                        Icon(AlphaIcons.Outlined.ContentPaste, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Text(" " + stringResource(R.string.relays_import_paste), color = colors.accent)
                    }
                    TextButton(onClick = onScan) {
                        Icon(AlphaIcons.Outlined.QrCodeScanner, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Text(" " + stringResource(R.string.relays_import_scan), color = colors.accent)
                    }
                }
                if (found.isEmpty() && text.isNotBlank()) {
                    Text(stringResource(R.string.relays_import_none), color = colors.textMuted, fontSize = 13.sp)
                }
                found.forEach { url ->
                    val have = url.lowercase(java.util.Locale.ROOT) in known
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !have) {
                            if (url in picked) picked.remove(url) else picked.add(url)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = have || url in picked,
                            onCheckedChange = null,
                            enabled = !have,
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent),
                        )
                        Column {
                            Text(url, color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            if (have) Text(stringResource(R.string.relays_import_have), color = colors.textMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(picked.toList()); onDismiss() }, enabled = picked.isNotEmpty()) {
                Text(stringResource(R.string.relays_import_add, picked.size), color = if (picked.isNotEmpty()) colors.accent else colors.textMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary) }
        },
    )
}
