package com.alphamessenger.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.export.ExportOptions
import com.alphamessenger.export.ExportProgress
import com.alphamessenger.export.ExportService
import com.alphamessenger.export.MediaKind
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val SIZE_LIMITS = listOf(8, 32, 100, 500, 0) // MB, 0 = no limit

@Composable
private fun kindLabel(k: MediaKind): String = when (k) {
    MediaKind.PHOTO -> stringResource(R.string.media_kind_photo)
    MediaKind.VIDEO -> stringResource(R.string.media_kind_video)
    MediaKind.VOICE -> stringResource(R.string.export_voice)
    MediaKind.ROUND -> stringResource(R.string.export_rounds)
    MediaKind.STICKER -> stringResource(R.string.export_stickers)
    MediaKind.GIF -> "GIF"
    MediaKind.FILE -> stringResource(R.string.media_kind_file)
}

@Composable
private fun sizeLabel(mb: Int): String =
    if (mb == 0) stringResource(R.string.export_no_limit) else stringResource(R.string.export_mb, mb)

/**
 * What to take out and how: kinds of chats (or one chat), attachments and
 * their size limit, a period, HTML and/or JSON. The archive is written by
 * [ExportService]; this screen follows its progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(chatId: String?, onBack: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    val state by ExportProgress.state.collectAsState()
    var opts by remember { mutableStateOf(ExportOptions(chatId = chatId)) }
    var sizeMenu by remember { mutableStateOf(false) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { ExportProgress.acknowledge() } }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) {
            // The service writes after this screen may be gone.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            ExportService.start(ctx, uri, opts)
        }
    }
    val dayFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    fun day(sec: Long) = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).toLocalDate()

    OptionScreen(
        title = stringResource(if (chatId != null) R.string.export_title_chat else R.string.export_title_profile),
        onBack = onBack,
    ) {
        when (val s = state) {
            is ExportProgress.State.Running -> {
                Spacer(Modifier.height(12.dp))
                OptionGroup {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (s.chat.isBlank()) stringResource(R.string.export_preparing) else stringResource(R.string.export_running, s.chat),
                            color = cs.textPrimary, fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (s.chats > 0) LinearProgressIndicator(
                            progress = { (s.chatIndex - 1).coerceAtLeast(0) / s.chats.toFloat() },
                            color = cs.accent, trackColor = cs.bgTertiary, modifier = Modifier.fillMaxWidth(),
                        ) else LinearProgressIndicator(color = cs.accent, trackColor = cs.bgTertiary, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.export_progress, s.messages, s.files), color = cs.textMuted, fontSize = 13.sp)
                    }
                    OptionDivider(inset = false)
                    OptionRow(title = stringResource(R.string.common_cancel), danger = true, onClick = { ExportService.cancel(ctx) })
                }
                OptionNote(stringResource(R.string.export_background_note))
            }
            is ExportProgress.State.Done -> {
                Spacer(Modifier.height(12.dp))
                OptionGroup {
                    OptionRow(
                        title = stringResource(R.string.export_done),
                        subtitle = stringResource(R.string.export_done_detail, s.chats, s.messages, s.files) +
                            if (s.missing > 0) "\n" + stringResource(R.string.export_missing, s.missing) else "",
                        icon = AlphaIcons.Outlined.CheckCircle,
                    )
                    OptionDivider()
                    OptionRow(title = stringResource(R.string.common_open), icon = AlphaIcons.Outlined.FolderZip, onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(s.uri), "application/zip")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    })
                }
            }
            is ExportProgress.State.Failed -> OptionNote(stringResource(R.string.export_failed, s.reason))
            ExportProgress.State.Cancelled -> OptionNote(stringResource(R.string.export_cancelled))
            ExportProgress.State.Idle -> {}
        }
        if (state !is ExportProgress.State.Running) {
            if (chatId == null) {
                OptionSection(stringResource(R.string.export_section_what))
                OptionGroup {
                    OptionSwitchRow(stringResource(R.string.export_profile), stringResource(R.string.export_profile_hint), opts.profile, { opts = opts.copy(profile = it) })
                    OptionDivider()
                    OptionSwitchRow(stringResource(R.string.contacts_title), null, opts.contacts, { opts = opts.copy(contacts = it) })
                    OptionDivider()
                    OptionSwitchRow(stringResource(R.string.export_personal), null, opts.personal, { opts = opts.copy(personal = it) })
                    OptionDivider()
                    OptionSwitchRow(stringResource(R.string.export_groups), null, opts.groups, { opts = opts.copy(groups = it) })
                    OptionDivider()
                    OptionSwitchRow(stringResource(R.string.export_channels), null, opts.channels, { opts = opts.copy(channels = it) })
                    OptionDivider()
                    OptionSwitchRow(stringResource(R.string.menu_saved), null, opts.saved, { opts = opts.copy(saved = it) })
                }
            }

            OptionSection(stringResource(R.string.export_section_media))
            OptionGroup {
                MediaKind.entries.forEach { k ->
                    OptionSwitchRow(kindLabel(k), null, k in opts.media, { on -> opts = opts.copy(media = if (on) opts.media + k else opts.media - k) })
                    OptionDivider()
                }
                Box {
                    OptionRow(title = stringResource(R.string.export_size_limit), value = sizeLabel(opts.maxFileMb), onClick = { sizeMenu = true })
                    DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                        SIZE_LIMITS.forEach { mb ->
                            DropdownMenuItem(text = { Text(sizeLabel(mb)) }, onClick = { opts = opts.copy(maxFileMb = mb); sizeMenu = false })
                        }
                    }
                }
            }
            OptionNote(stringResource(R.string.export_media_note))

            OptionSection(stringResource(R.string.export_section_period))
            OptionGroup {
                OptionChoiceRow(stringResource(R.string.export_period_all), selected = opts.sinceSec == 0L && opts.untilSec == 0L) {
                    opts = opts.copy(sinceSec = 0, untilSec = 0)
                }
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.export_period_from),
                    value = if (opts.sinceSec > 0) dayFormat.format(day(opts.sinceSec)) else "—",
                    onClick = { pickFrom = true },
                )
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.export_period_to),
                    // [until] is the start of the day after the last one taken.
                    value = if (opts.untilSec > 0) dayFormat.format(day(opts.untilSec).minusDays(1)) else "—",
                    onClick = { pickTo = true },
                )
            }

            OptionSection(stringResource(R.string.export_section_format))
            OptionGroup {
                OptionChoiceRow(stringResource(R.string.export_format_html), stringResource(R.string.export_format_html_hint), selected = opts.html && !opts.json) {
                    opts = opts.copy(html = true, json = false)
                }
                OptionDivider()
                OptionChoiceRow(stringResource(R.string.export_format_json), stringResource(R.string.export_format_json_hint), selected = opts.json && !opts.html) {
                    opts = opts.copy(html = false, json = true)
                }
                OptionDivider()
                OptionChoiceRow(stringResource(R.string.export_format_both), selected = opts.html && opts.json) {
                    opts = opts.copy(html = true, json = true)
                }
            }
            OptionNote(stringResource(R.string.export_note))
            Spacer(Modifier.height(20.dp))
            com.alphamessenger.ui.onboarding.OnbPrimaryButton(
                text = stringResource(R.string.export_start),
                onClick = { create.launch((if (chatId != null) "ChatExport_" else "AlphaExport_") + LocalDate.now() + ".zip") },
                enabled = opts.takesAnything(),
            )
        }
    }

    if (pickFrom || pickTo) {
        val initial = (if (pickFrom) opts.sinceSec else opts.untilSec).takeIf { it > 0 }
            ?.let { day(it).let { d -> if (pickTo) d.minusDays(1) else d } }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickFrom = false; pickTo = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        val zone = ZoneId.systemDefault()
                        opts = if (pickFrom) opts.copy(sinceSec = d.atStartOfDay(zone).toEpochSecond())
                        else opts.copy(untilSec = d.plusDays(1).atStartOfDay(zone).toEpochSecond())
                    }
                    pickFrom = false; pickTo = false
                }) { Text(stringResource(R.string.common_continue), color = cs.accent) }
            },
            dismissButton = { TextButton(onClick = { pickFrom = false; pickTo = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state = picker) }
    }
}
