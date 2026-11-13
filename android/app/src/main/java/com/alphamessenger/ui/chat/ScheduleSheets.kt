package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import java.time.LocalDateTime
import java.time.ZoneId

/** Earliest allowed send time: a minute from now (a time in the past is not a schedule). */
private fun earliest(): Long = System.currentTimeMillis() + 60_000L

/** "Today, 20:00" / "Tomorrow, 09:00" / "12 Oct, 09:00". */
@Composable
fun scheduleLabel(atMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val t = java.time.Instant.ofEpochMilli(atMillis).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val time = t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        today -> stringResource(R.string.schedule_today_at, time)
        today.plusDays(1) -> stringResource(R.string.schedule_tomorrow_at, time)
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }
}

private fun atLocal(day: java.time.LocalDate, hour: Int): Long =
    LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Quick times + a full date/time picker. Only future times can be chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSendSheet(colors: AlphaColorScheme, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickDate by remember { mutableStateOf(false) }
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    var pastError by remember { mutableStateOf(false) }
    val now = remember { LocalDateTime.now() }
    val today = now.toLocalDate()
    val quick = buildList {
        add(stringResource(R.string.schedule_in_hour) to System.currentTimeMillis() + 3_600_000L)
        if (now.hour < 19) add(stringResource(R.string.schedule_today_at, "20:00") to atLocal(today, 20))
        add(stringResource(R.string.schedule_tomorrow_at, "09:00") to atLocal(today.plusDays(1), 9))
    }
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.schedule_title), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            quick.forEach { (label, at) -> Line(AlphaIcons.Outlined.Schedule, label, colors) { onPick(at) } }
            Line(AlphaIcons.Outlined.CalendarMonth, stringResource(R.string.schedule_pick), colors) { pickDate = true }
        }
    }

    if (pickDate) {
        // Days before today cannot be selected at all.
        val todayUtc = java.time.LocalDate.now().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = todayUtc,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
                override fun isSelectableYear(year: Int) = year >= java.time.LocalDate.now().year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = { pickedDay = dateState.selectedDateMillis; pickDate = false }) {
                    Text(stringResource(R.string.common_continue), color = colors.accent)
                }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state = dateState) }
    }
    pickedDay?.let { dayUtcMillis ->
        val timeState = rememberTimePickerState(initialHour = (now.hour + 1) % 24, initialMinute = 0, is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickedDay = null; pastError = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.schedule_pick_time), color = colors.textPrimary) },
            text = {
                Column {
                    TimePicker(state = timeState)
                    if (pastError) Text(stringResource(R.string.schedule_past_error), color = colors.danger, fontSize = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // The date picker returns UTC midnight of the chosen day.
                    val day = java.time.Instant.ofEpochMilli(dayUtcMillis).atZone(ZoneId.of("UTC")).toLocalDate()
                    val at = LocalDateTime.of(day, java.time.LocalTime.of(timeState.hour, timeState.minute))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (at < earliest()) {
                        pastError = true
                    } else {
                        pickedDay = null; pastError = false
                        onPick(at)
                    }
                }) { Text(stringResource(R.string.schedule_confirm), color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { pickedDay = null; pastError = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun Line(icon: ImageVector, label: String, colors: AlphaColorScheme, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) colors.danger else colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (danger) colors.danger else colors.textPrimary, fontSize = 16.sp)
    }
}

/** The strip above the composer: "Scheduled: N" → opens the chat's scheduled view. */
@Composable
fun ScheduledStrip(count: Int, colors: AlphaColorScheme, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(colors.bgSecondary).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AlphaIcons.Outlined.Schedule, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.schedule_strip, count), color = colors.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.schedule_show), color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Actions on a scheduled message (the scheduled view of a chat). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledActionsSheet(
    atMillis: Long,
    canEdit: Boolean,
    colors: AlphaColorScheme,
    onSendNow: () -> Unit,
    onReschedule: () -> Unit,
    onEdit: () -> Unit,
    onReplace: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.schedule_will_send, scheduleLabel(atMillis)), color = colors.textMuted, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Line(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.schedule_send_now), colors, onClick = onSendNow)
            Line(AlphaIcons.Outlined.Schedule, stringResource(R.string.schedule_reschedule), colors, onClick = onReschedule)
            if (canEdit) Line(AlphaIcons.Outlined.Edit, stringResource(R.string.common_edit), colors, onClick = onEdit)
            if (onReplace != null) Line(AlphaIcons.Outlined.SwapHoriz, stringResource(R.string.schedule_replace), colors, onClick = onReplace)
            Line(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.common_delete), colors, danger = true, onClick = onDelete)
        }
    }
}

/** Replace a waiting message's content: pick what it becomes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplaceKindSheet(
    colors: AlphaColorScheme,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onMusic: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.schedule_replace), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Line(AlphaIcons.Outlined.PhotoLibrary, stringResource(R.string.schedule_replace_gallery), colors, onClick = onGallery)
            Line(AlphaIcons.Outlined.InsertDriveFile, stringResource(R.string.preview_file), colors, onClick = onFile)
            Line(AlphaIcons.Outlined.MusicNote, stringResource(R.string.media_kind_music), colors, onClick = onMusic)
        }
    }
}
