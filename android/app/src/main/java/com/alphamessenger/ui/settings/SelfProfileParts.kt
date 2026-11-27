package com.alphamessenger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaColorScheme
import java.io.File

private val HERO = 236.dp
private val AVATAR = 112.dp
private val DND_RED = Color(0xFFE5484D)
private val ONLINE_GREEN = Color(0xFF3BD16F)

/** Our avatar: the video when there is one, else the photo, else the initial on our gradient. */
@Composable
internal fun OwnAvatar(name: String, seed: String, photo: String, video: String, size: Dp) {
    val grad = remember(seed, name) { com.alphamessenger.ui.chat.avatarGradientFor(seed.ifBlank { name }) }
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(grad.first, grad.second))),
        contentAlignment = Alignment.Center,
    ) {
        if (photo.isNotBlank()) {
            AsyncImage(model = File(photo), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            if (video.isNotBlank()) com.alphamessenger.ui.profile.LoopingVideoAvatar(video, size)
        } else {
            Text(com.alphamessenger.ui.components.avatarInitial(name), color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The cover running into the page, the avatar over its lower edge, then name, status and key. */
@Composable
internal fun SelfHero(
    colors: AlphaColorScheme,
    name: String,
    npub: String,
    photo: String,
    video: String,
    cover: String,
    statusLine: String,
    statusDot: Color,
    onBack: () -> Unit,
    onCover: () -> Unit,
    onAvatar: () -> Unit,
    onCopyKey: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(HERO)) {
            if (cover.isNotBlank()) {
                AsyncImage(model = File(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(colors.accent.copy(alpha = 0.85f), colors.bgPrimary))))
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, colors.bgPrimary))),
            )
            Row(
                Modifier.fillMaxWidth()
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.28f))
                        .clickable(onClick = onCover).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Outlined.Image, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.common_cover), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(top = HERO - 78.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Box(
                    Modifier.size(AVATAR).clip(CircleShape).border(4.dp, colors.bgPrimary, CircleShape).clickable(onClick = onAvatar),
                    contentAlignment = Alignment.Center,
                ) { OwnAvatar(name, npub, photo, video, AVATAR - 8.dp) }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape).background(colors.bgPrimary).padding(3.dp)
                        .clip(CircleShape).background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AlphaIcons.Outlined.PhotoCamera, null, tint = colors.bgPrimary, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(name.ifBlank { "—" }, color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusDot))
                Spacer(Modifier.width(6.dp))
                Text(statusLine, color = colors.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (npub.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(colors.bgTertiary)
                        .clickable(onClick = onCopyKey).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(npub.take(10) + "…" + npub.takeLast(4), color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Icon(AlphaIcons.Outlined.ContentCopy, stringResource(R.string.common_copy), tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** One square shortcut under the header. */
@Composable
internal fun SelfTile(icon: ImageVector, label: String, colors: AlphaColorScheme, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(64.dp).clip(RoundedCornerShape(16.dp)).background(colors.bgSecondary).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SelfSectionTitle(text: String, colors: AlphaColorScheme) {
    Text(
        text.uppercase(), color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A rounded group of rows. */
@Composable
internal fun SelfCard(colors: AlphaColorScheme, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(colors.bgSecondary)) { content() }
}

@Composable
internal fun SelfCardDivider(colors: AlphaColorScheme) {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(colors.borderLight.copy(alpha = 0.35f)))
}

/** A value over its label, opening its editor. */
@Composable
internal fun SelfRow(
    value: String,
    label: String,
    colors: AlphaColorScheme,
    placeholder: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead != null) { lead(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(value, color = if (placeholder) colors.textMuted else colors.textPrimary, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(label, color = colors.textMuted, fontSize = 12.sp)
        }
        Icon(AlphaIcons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun DangerBadge(icon: ImageVector) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(DND_RED.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color(0xFFFF6B70), modifier = Modifier.size(18.dp))
    }
}

/** The dot shown with our status: our chosen colour, red for do-not-disturb, else online green. */
internal fun ownStatusDot(busy: Boolean, color: Int): Color = when {
    color != 0 -> Color(color)
    busy -> DND_RED
    else -> ONLINE_GREEN
}

/** Avatar: see it, replace it with a photo or a video, or remove it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnAvatarSheet(
    colors: AlphaColorScheme,
    name: String,
    seed: String,
    photo: String,
    video: String,
    onDismiss: () -> Unit,
    onOpen: (() -> Unit)?,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onRemoveVideo: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                OwnAvatar(name, seed, photo, video, 64.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.common_avatar), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(R.string.avatar_sheet_hint), color = colors.textMuted, fontSize = 13.sp)
                }
            }
            if (onOpen != null) SheetChoice(AlphaIcons.Outlined.Visibility, stringResource(R.string.common_open), colors.accent, colors.textPrimary, colors.bgTertiary, onOpen)
            SheetChoice(AlphaIcons.Outlined.PhotoLibrary, stringResource(R.string.avatar_sheet_pick_photo), colors.accent, colors.textPrimary, colors.bgTertiary, onPhoto)
            SheetChoice(AlphaIcons.Outlined.Videocam, stringResource(R.string.avatar_sheet_video), colors.accent, colors.textPrimary, colors.bgTertiary, onVideo)
            if (onRemoveVideo != null || onRemove != null) Spacer(Modifier.height(8.dp))
            if (onRemoveVideo != null) SheetChoice(AlphaIcons.Outlined.Videocam, stringResource(R.string.video_avatar_remove), DND_RED, Color(0xFFFF6B70), DND_RED.copy(alpha = 0.10f), onRemoveVideo)
            if (onRemove != null) SheetChoice(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.avatar_sheet_remove), DND_RED, Color(0xFFFF6B70), DND_RED.copy(alpha = 0.10f), onRemove)
        }
    }
}

@Composable
private fun SheetChoice(icon: ImageVector, label: String, tint: Color, text: Color, fill: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(16.dp)).background(fill).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = text, fontSize = 15.sp)
    }
}

/** Status: a line of text, a large colour to pick, do-not-disturb — previewed as contacts see it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnStatusSheet(
    colors: AlphaColorScheme,
    initialBusy: Boolean,
    initialCustom: String,
    initialColor: Int,
    palette: List<Int>,
    onDismiss: () -> Unit,
    onSave: (busy: Boolean, text: String, color: Int) -> Unit,
) {
    var busy by remember { mutableStateOf(initialBusy) }
    var custom by remember { mutableStateOf(initialCustom) }
    var color by remember { mutableStateOf(initialColor) }
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.profile_status_title), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

            val dot = ownStatusDot(busy, color)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.bgTertiary).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(dot.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        custom.trim().ifBlank { stringResource(if (busy) R.string.profile_dnd else R.string.presence_online) },
                        color = colors.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(stringResource(R.string.status_preview_hint), color = colors.textMuted, fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it.take(80) },
                label = { Text(stringResource(R.string.status_text_label)) },
                placeholder = { Text(stringResource(R.string.profile_status_hint), color = colors.textMuted) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = textFieldColors(colors),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.profile_status_color), color = colors.textMuted, fontSize = 12.sp)
                palette.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { c ->
                            val selected = c == color
                            Box(
                                Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(18.dp)).background(Color(c))
                                    .border(3.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(18.dp))
                                    .clickable { color = if (selected) 0 else c },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(AlphaIcons.Outlined.Check, null, tint = if (Color(c).luminanceLight()) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.status_green_note), color = colors.textMuted, fontSize = 12.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_dnd), color = colors.textPrimary, fontSize = 15.sp)
                    Text(stringResource(R.string.profile_dnd_desc), color = colors.textMuted, fontSize = 12.sp)
                }
                Switch(checked = busy, onCheckedChange = { busy = it })
            }

            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).background(colors.accent)
                    .clickable { onSave(busy, custom.trim(), color) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.common_save), color = colors.bgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(18.dp)).clickable { onSave(false, "", 0) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.profile_reset_status), color = colors.textMuted, fontSize = 14.sp)
            }
        }
    }
}

private fun Color.luminanceLight() = (0.299f * red + 0.587f * green + 0.114f * blue) > 0.7f
