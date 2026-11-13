package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.onboarding.OnbField
import com.alphamessenger.ui.onboarding.OnbIconTile
import com.alphamessenger.ui.onboarding.OnbPrimaryButton
import com.alphamessenger.ui.onboarding.SecureScreenEffect
import com.alphamessenger.ui.onboarding.surface1
import com.alphamessenger.ui.onboarding.surface2
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the password dialog is asked for. */
private enum class PwAction { Upload, Export, Reveal }

/**
 * Backup — new design: the state of the copy on top with one bright action,
 * then the file copy, the keys (phrase shown only after the password), and
 * deleting the online copy. Every action that touches the backup asks for the
 * backup password in one dialog.
 */
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val cs = AlphaTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pwAction by remember { mutableStateOf<PwAction?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val bytes = pendingBytes ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = writeBytesToUri(context, uri, bytes)
            snackbar.showSnackbar(
                if (ok) context.getString(R.string.backup_saved_kb, bytes.size / 1024)
                else context.getString(R.string.seed_backup_save_failed),
            )
            pendingBytes = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCloudInfo()
        viewModel.events.collect { event ->
            when (event) {
                is BackupViewModel.Event.Exported -> {
                    pwAction = null
                    viewModel.onPasswordChange("")
                    pendingBytes = event.bytes
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    createDoc.launch("alpha-backup-$stamp.enc")
                }
                is BackupViewModel.Event.Imported -> snackbar.showSnackbar(context.getString(R.string.backup_restored))
                is BackupViewModel.Event.Inspected -> snackbar.showSnackbar(event.summary)
                is BackupViewModel.Event.CloudUploaded -> {
                    pwAction = null
                    viewModel.onPasswordChange("")
                    snackbar.showSnackbar(context.getString(R.string.bk_uploaded))
                }
                is BackupViewModel.Event.CloudDeleted -> snackbar.showSnackbar(context.getString(R.string.backup_cloud_deleted))
            }
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }
    val busy = state.isUploadingCloud || state.isExporting || state.isRevealing
    // The keys are on screen → the password dialog has done its job.
    LaunchedEffect(state.revealedWords, state.revealedNsec) {
        if (state.revealedWords != null || state.revealedNsec != null) pwAction = null
    }

    Box(Modifier.fillMaxSize().background(cs.bgPrimary)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(AlphaIcons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = cs.textPrimary)
                }
                Text(stringResource(R.string.settings_backup_title), color = cs.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusCard(state.cloud, busy = state.isUploadingCloud, enabled = viewModel.canBackup()) { pwAction = PwAction.Upload }

                SectionLabel(stringResource(R.string.bk_section_auto))
                Group {
                    var daily by remember { mutableStateOf(com.alphamessenger.service.AutoBackupWorker.isEnabled(context)) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp)
                            .clickable { daily = !daily; com.alphamessenger.service.AutoBackupWorker.setEnabled(context, daily) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.bk_daily), color = cs.textPrimary, fontSize = 16.sp)
                            Text(stringResource(R.string.bk_daily_note), color = cs.textMuted, fontSize = 13.sp)
                        }
                        androidx.compose.material3.Switch(
                            checked = daily,
                            onCheckedChange = { daily = it; com.alphamessenger.service.AutoBackupWorker.setEnabled(context, it) },
                            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = cs.accent, checkedThumbColor = Color.White),
                        )
                    }
                }

                SectionLabel(stringResource(R.string.bk_section_file))
                Group {
                    ActionRow(AlphaIcons.Outlined.SaveAlt, stringResource(R.string.bk_save_file), stringResource(R.string.bk_save_file_note)) {
                        pwAction = PwAction.Export
                    }
                }

                SectionLabel(stringResource(R.string.bk_section_keys))
                Group {
                    ActionRow(AlphaIcons.Outlined.Key, stringResource(R.string.bk_show_phrase), stringResource(R.string.bk_show_phrase_note)) {
                        pwAction = PwAction.Reveal
                    }
                }

                if (state.cloud.has) {
                    Group {
                        ActionRow(
                            AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.backup_delete_cloud), null,
                            tint = cs.danger,
                        ) { confirmDelete = true }
                    }
                }

                Text(
                    stringResource(R.string.bk_footer),
                    color = cs.textMuted, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    pwAction?.let { action ->
        PasswordDialog(
            password = state.password,
            onPassword = viewModel::onPasswordChange,
            loading = busy,
            confirmLabel = stringResource(
                when (action) {
                    PwAction.Upload -> R.string.bk_upload_now
                    PwAction.Export -> R.string.bk_save_file
                    PwAction.Reveal -> R.string.bk_show_phrase
                },
            ),
            onConfirm = {
                when (action) {
                    PwAction.Upload -> viewModel.uploadCloudBackup()
                    PwAction.Export -> viewModel.exportBackup()
                    PwAction.Reveal -> viewModel.revealKeys()
                }
            },
            onDismiss = { pwAction = null; viewModel.onPasswordChange("") },
        )
    }

    if (state.revealedWords != null || state.revealedNsec != null) {
        KeysDialog(words = state.revealedWords, nsec = state.revealedNsec, onClose = viewModel::hideKeys)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = cs.bgSecondary,
            title = { Text(stringResource(R.string.backup_delete_dialog_title), color = cs.textPrimary) },
            text = { Text(stringResource(R.string.backup_delete_dialog_body), color = cs.textSecondary) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteCloudBackup() }) {
                    Text(stringResource(R.string.common_delete), color = cs.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel), color = cs.textSecondary) }
            },
        )
    }
}

@Composable
private fun StatusCard(cloud: BackupViewModel.CloudBackupInfo, busy: Boolean, enabled: Boolean, onUpload: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surface1).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (cloud.has) {
                OnbIconTile(AlphaIcons.Outlined.VerifiedUser, cs.success, cs.success.copy(alpha = 0.14f), size = 48)
            } else {
                OnbIconTile(AlphaIcons.Outlined.CloudOff, cs.warning, cs.warning.copy(alpha = 0.14f), size = 48)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (cloud.has) R.string.bk_status_ok else R.string.bk_status_none),
                    color = cs.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (cloud.has) "${formatBackupDate(cloud.at)} · ${cloud.size / 1024} KB" else stringResource(R.string.bk_status_none_note),
                    color = cs.textMuted, fontSize = 14.sp,
                )
            }
        }
        OnbPrimaryButton(stringResource(R.string.bk_upload_now), onUpload, enabled = enabled, loading = busy)
        if (!enabled) Text(stringResource(R.string.backup_need_password), color = cs.textMuted, fontSize = 13.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    val cs = AlphaTheme.colorScheme
    Text(
        text.uppercase(), color = cs.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp,
        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surface1)) { content() }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, note: String?, tint: Color? = null, onClick: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = tint ?: cs.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = tint ?: cs.textPrimary, fontSize = 16.sp)
            if (note != null) Text(note, color = cs.textMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PasswordDialog(
    password: String,
    onPassword: (String) -> Unit,
    loading: Boolean,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.bgSecondary,
        title = { Text(stringResource(R.string.bk_pw_title), color = cs.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.bk_pw_note), color = cs.textSecondary, fontSize = 14.sp)
                OnbField(value = password, onValueChange = onPassword, label = null, password = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = password.isNotBlank() && !loading) {
                Text(if (loading) "…" else confirmLabel, color = cs.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = cs.textSecondary) }
        },
    )
}

/** The phrase (or nsec), screenshots blocked while it is on screen. */
@Composable
private fun KeysDialog(words: List<String>?, nsec: String?, onClose: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    SecureScreenEffect()
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = cs.bgSecondary,
        title = { Text(stringResource(if (words != null) R.string.onb_phrase_title else R.string.onb_key_title), color = cs.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (words != null) {
                    words.chunked(2).forEachIndexed { row, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEachIndexed { col, w ->
                                Row(
                                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(cs.surface2).padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${row * 2 + col + 1}", color = cs.textMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(w, color = cs.textPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                } else if (nsec != null) {
                    Text(nsec, color = cs.textPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp)
                }
                Text(stringResource(R.string.onb_phrase_warn), color = cs.textMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_close), color = cs.accent) } },
    )
}

private fun formatBackupDate(at: String): String = runCatching {
    val zdt = java.time.OffsetDateTime.parse(at).atZoneSameInstant(java.time.ZoneId.systemDefault())
    java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()).format(zdt)
}.recoverCatching {
    val secs = at.toLong()
    java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        .format(java.time.Instant.ofEpochSecond(secs).atZone(java.time.ZoneId.systemDefault()))
}.getOrDefault(at)

private suspend fun writeBytesToUri(context: Context, uri: Uri?, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        if (uri == null) return@withContext false
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }
