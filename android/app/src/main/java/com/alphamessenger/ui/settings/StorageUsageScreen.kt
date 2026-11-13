package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.theme.AlphaTheme

private data class StorageCategory(
    val key: String,         // "image"/"video"/"file"/"voice" for deletable media; "" otherwise
    val label: String,
    val color: Color,
    val bytes: Long,
)

/** Storage — the same look as every settings screen (option kit). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageUsageScreen(
    viewModel: StorageUsageViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val colors = AlphaTheme.colorScheme
    val r = state.report

    var sheetFor by remember { mutableStateOf<StorageCategory?>(null) }
    var chatSheet by remember { mutableStateOf<StorageUsageViewModel.ChatUsage?>(null) }
    var triggerSheetOpen by remember { mutableStateOf(false) }

    val media = listOf(
        StorageCategory("image", stringResource(R.string.media_kind_photo), Color(0xFF5B7CFA), r.photos),
        StorageCategory("video", stringResource(R.string.media_kind_video), Color(0xFFE45D5D), r.videos),
        StorageCategory("file", stringResource(R.string.media_kind_file), Color(0xFFF0A93B), r.files),
        StorageCategory("voice", stringResource(R.string.media_kind_voice), Color(0xFF26A69A), r.voice),
    )
    val other = listOf(
        StorageCategory("", stringResource(R.string.storage_avatars), Color(0xFF9C6BF0), r.avatars),
        StorageCategory("", stringResource(R.string.storage_stickers_gifs), Color(0xFFF5C451), r.stickers),
        StorageCategory("", stringResource(R.string.storage_cache), Color(0xFF7A8BA3), r.cache),
        StorageCategory("", stringResource(R.string.storage_database), Color(0xFF4DB6AC), r.database),
    )

    OptionScreen(title = stringResource(R.string.settings_storage_title), onBack = onBack) {
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else {

        // --- The whole picture ---
        Spacer(Modifier.height(8.dp))
        OptionGroup {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Donut((media + other).filter { it.bytes > 0 }, r.total, colors)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(StorageUsageViewModel.formatBytes(r.total), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.storage_used_label), color = colors.textSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        // --- Media (tap to free space) ---
        OptionSection(stringResource(R.string.storage_section_media))
        OptionGroup {
            media.forEachIndexed { i, c ->
                if (i > 0) OptionDivider()
                CategoryRow(c, onClick = if (c.bytes > 0) ({ sheetFor = c }) else null)
            }
        }
        OptionNote(stringResource(R.string.storage_local_only_note))

        // --- By chat: who keeps what, each one cleared on its own ---
        if (r.byChat.isNotEmpty()) {
            OptionSection(stringResource(R.string.storage_section_chats))
            OptionGroup {
                val top = r.byChat.first().bytes.coerceAtLeast(1L)
                var showAll by remember { mutableStateOf(false) }
                val shown = if (showAll) r.byChat else r.byChat.take(6)
                shown.forEachIndexed { i, c ->
                    if (i > 0) OptionDivider()
                    ChatUsageRow(c, top, media, colors) { chatSheet = c }
                }
                if (r.byChat.size > 6 && !showAll) {
                    OptionDivider()
                    OptionRow(stringResource(R.string.storage_show_all_chats, r.byChat.size), chevron = false, onClick = { showAll = true })
                }
            }
            OptionNote(stringResource(R.string.storage_chats_note))
        }

        // --- The rest ---
        OptionSection(stringResource(R.string.storage_section_other))
        OptionGroup {
            other.forEachIndexed { i, c ->
                if (i > 0) OptionDivider()
                CategoryRow(c, onClick = null)
            }
            OptionDivider()
            OptionRow(stringResource(R.string.storage_stat_app), value = StorageUsageViewModel.formatBytes(r.appBytes))
            OptionDivider()
            OptionRow(stringResource(R.string.storage_stat_data), value = StorageUsageViewModel.formatBytes(r.dataBytes))
        }

        // --- Cache ---
        OptionSection(stringResource(R.string.storage_cache))
        OptionGroup {
            OptionRow(
                title = if (state.busy) stringResource(R.string.storage_clearing) else stringResource(R.string.storage_clear_cache),
                value = StorageUsageViewModel.formatBytes(r.cacheBytes),
                icon = AlphaIcons.Outlined.CleaningServices,
                chevron = false,
                onClick = if (state.busy) null else ({ viewModel.clearCache() }),
            )
        }
        OptionNote(stringResource(R.string.storage_clear_note))

        // --- Automatic cleanup ---
        OptionSection(stringResource(R.string.storage_autoclean_title))
        OptionGroup {
            val triggerLabel = StorageUsageViewModel.AUTO_TRIGGER_OPTIONS
                .firstOrNull { it.first == state.autoTrigger }?.second?.let { stringResource(it) } ?: stringResource(R.string.storage_never)
            OptionRow(
                title = stringResource(R.string.storage_condition),
                subtitle = if (state.autoTrigger == "never") null
                else if (state.autoLastCleanupMs <= 0L) stringResource(R.string.storage_last_cleanup_never)
                else stringResource(R.string.storage_last_cleanup_at, relativeTime(state.autoLastCleanupMs)),
                value = triggerLabel,
                icon = AlphaIcons.Outlined.AutoDelete,
                onClick = { triggerSheetOpen = true },
            )
            if (state.autoTrigger != "never") {
                listOf(
                    Triple("photo", R.string.media_kind_photo, state.autoPhoto),
                    Triple("video", R.string.media_kind_video, state.autoVideo),
                    Triple("voice", R.string.media_kind_voice, state.autoVoice),
                    Triple("file", R.string.media_kind_documents, state.autoFile),
                ).forEach { (key, label, on) ->
                    OptionDivider()
                    OptionSwitchRow(stringResource(label), checked = on, onCheckedChange = { viewModel.setAutoCategory(key, it) })
                }
            }
        }
        OptionNote(stringResource(R.string.storage_cache_only_note))
        Spacer(Modifier.height(8.dp))
        }
    }

    // --- Free a media type ---
    sheetFor?.let { cat ->
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { sheetFor = null }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(
                    "${cat.label} · ${StorageUsageViewModel.formatBytes(cat.bytes)}",
                    color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                listOf(
                    stringResource(R.string.storage_delete_30d) to 30,
                    stringResource(R.string.storage_delete_90d) to 90,
                    stringResource(R.string.storage_delete_1y) to 365,
                    stringResource(R.string.storage_delete_all) to null,
                ).forEach { (label, days) ->
                    OptionRow(label, danger = days == null, chevron = false, onClick = { viewModel.deleteType(cat.key, days); sheetFor = null })
                }
                OptionNote(stringResource(R.string.storage_local_only_note))
            }
        }
    }

    // --- Free one chat ---
    chatSheet?.let { c ->
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { chatSheet = null }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.alphamessenger.ui.stories.StoryAvatar(c.avatarPath, c.chatId, com.alphamessenger.ui.components.avatarInitial(c.name), 44)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(c.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1)
                        Text(StorageUsageViewModel.formatBytes(c.bytes), color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
                // What it is made of, in the colours of the chart above.
                listOf(media[0] to c.photos, media[1] to c.videos, media[2] to c.files, media[3] to c.voice)
                    .filter { it.second > 0 }
                    .forEach { (cat, bytes) -> CategoryRow(cat.copy(bytes = bytes), onClick = null) }
                Spacer(Modifier.height(4.dp))
                OptionRow(
                    stringResource(R.string.storage_clear_chat, StorageUsageViewModel.formatBytes(c.bytes)),
                    danger = true, chevron = false,
                    onClick = { viewModel.clearChat(c.chatId); chatSheet = null },
                )
                OptionNote(stringResource(R.string.storage_local_only_note))
            }
        }
    }

    // --- When to clean automatically ---
    if (triggerSheetOpen) {
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { triggerSheetOpen = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(
                    stringResource(R.string.storage_autoclean_short),
                    color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                StorageUsageViewModel.AUTO_TRIGGER_OPTIONS.forEach { (key, label) ->
                    OptionChoiceRow(stringResource(label), selected = state.autoTrigger == key) {
                        viewModel.setAutoTrigger(key); triggerSheetOpen = false
                    }
                }
            }
        }
    }
}

/** A chat in the cache: its face, name, share of the largest one and what fills it. */
@Composable
private fun ChatUsageRow(
    c: StorageUsageViewModel.ChatUsage,
    top: Long,
    cats: List<StorageCategory>,
    colors: com.alphamessenger.ui.theme.AlphaColorScheme,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.alphamessenger.ui.stories.StoryAvatar(c.avatarPath, c.chatId, com.alphamessenger.ui.components.avatarInitial(c.name), 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, color = colors.textPrimary, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(StorageUsageViewModel.formatBytes(c.bytes), color = colors.textSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            // One bar, split by kind, as long as its share of the largest chat.
            val frac = (c.bytes.toFloat() / top).coerceIn(0.04f, 1f)
            Row(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(3.dp))) {
                listOf(cats[0].color to c.photos, cats[1].color to c.videos, cats[2].color to c.files, cats[3].color to c.voice)
                    .filter { it.second > 0 }
                    .forEach { (color, bytes) -> Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(color)) }
            }
        }
    }
}

/** A storage category: colour dot, name, size (and a chevron when it can be freed). */
@Composable
private fun CategoryRow(c: StorageCategory, onClick: (() -> Unit)?) {
    val colors = AlphaTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(c.color))
        Spacer(Modifier.width(14.dp))
        Text(c.label, color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(StorageUsageViewModel.formatBytes(c.bytes), color = colors.textMuted, fontSize = 15.sp)
        if (onClick != null) Icon(AlphaIcons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Donut(parts: List<StorageCategory>, total: Long, colors: com.alphamessenger.ui.theme.AlphaColorScheme) {
    androidx.compose.foundation.Canvas(Modifier.size(150.dp)) {
        val stroke = 24.dp.toPx()
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        if (total <= 0 || parts.isEmpty()) {
            drawArc(
                color = colors.bgTertiary, startAngle = -90f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
            return@Canvas
        }
        var start = -90f
        parts.forEach { p ->
            val sweep = 360f * (p.bytes.toFloat() / total)
            drawArc(
                color = p.color, startAngle = start, sweepAngle = (sweep - 1.5f).coerceAtLeast(0.5f),
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Butt),
            )
            start += sweep
        }
    }
}

/** Coarse "N days/hours ago" for the last-cleanup line. */
@Composable
private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    val h = diff / 3600_000L
    return when {
        h < 1 -> stringResource(R.string.storage_just_now)
        h < 24 -> stringResource(R.string.storage_hours_ago, h)
        else -> stringResource(R.string.storage_days_ago, h / 24)
    }
}
