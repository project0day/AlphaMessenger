package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.ClipData
import com.alphamessenger.ui.components.avatarBackdrop
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.ui.profile.LoopingVideoAvatar
import coil3.request.ImageRequest
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * Build a Coil model for a local file whose PATH is reused across updates
 * (my_avatar.jpg / cover.jpg get overwritten in place). Keying the mem/disk
 * cache by lastModified forces a reload instead of serving the stale image.
 */
@Composable
private fun cacheBustedFile(path: String, version: Int): ImageRequest {
    val ctx = LocalContext.current
    val f = java.io.File(path)
    val key = "$path:${f.lastModified()}:$version"
    return ImageRequest.Builder(ctx)
        .data(f)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .build()
}

/**
 * Presence status shown under the name. Presets carry an emoji + a localized
 * label + the colour used for the avatar dot on the self / main profile.
 */
enum class ProfileStatus(@StringRes val labelRes: Int, val dot: Color) {
    Online(R.string.presence_online, Color(0xFF3BD16F)),
    Away(R.string.presence_away, Color(0xFFF0B429)),
    Dnd(R.string.profile_dnd, Color(0xFFE5484D)),
    Offline(R.string.presence_offline, Color(0xFF8A8F98)),
}

/** Map the Rust presence status string to a [ProfileStatus]. */
fun profileStatusOf(raw: String): ProfileStatus = when (raw.lowercase()) {
    "online" -> ProfileStatus.Online
    "away" -> ProfileStatus.Away
    "busy", "dnd" -> ProfileStatus.Dnd
    else -> ProfileStatus.Offline
}

/** How long the peer's own status and their presence each stay before the other shows. */
private const val STATUS_TURN_MS = 15_000L

/**
 * The line under a contact's name: "online" taking turns with their own status text,
 * "last seen 5 min ago", or "last seen recently" when the time is unknown.
 */
@Composable
fun presenceSubtitle(status: String, statusText: String, lastSeen: Long): String {
    val st = profileStatusOf(status)
    return when (st) {
        ProfileStatus.Online, ProfileStatus.Away, ProfileStatus.Dnd -> {
            val label = stringResource(st.labelRes).lowercase()
            // Their own status and "online" take turns, so neither hides the other.
            var ownTurn by androidx.compose.runtime.remember(statusText) { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(statusText) {
                while (statusText.isNotBlank()) {
                    kotlinx.coroutines.delay(STATUS_TURN_MS)
                    ownTurn = !ownTurn
                }
            }
            if (ownTurn && statusText.isNotBlank()) statusText else label
        }
        ProfileStatus.Offline -> {
            val seen = lastSeenLabel(lastSeen)
            if (seen.isBlank()) stringResource(R.string.presence_seen_recently)
            else stringResource(R.string.presence_seen, seen)
        }
    }
}

/**
 * A status line that changes softly: the old text slides up and fades, the new
 * one rises from below ("offline" → "online" → "typing…"). The colour change
 * (accent while online/typing) goes with it.
 */
@Composable
fun AnimatedStatusText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    androidx.compose.animation.AnimatedContent(
        targetState = text to color,
        modifier = modifier,
        transitionSpec = {
            val dur = 260
            (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(dur)) { it / 2 } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(dur))) togetherWith
                (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(dur - 60)) { -it / 2 } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(dur - 100)))
        },
        label = "status",
    ) { (t, c) ->
        Text(
            t, color = c, fontSize = fontSize, lineHeight = lineHeight,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** True when the line should read as "live" (accent colour). */
fun presenceIsLive(status: String): Boolean = profileStatusOf(status) == ProfileStatus.Online

/**
 * Fair play: whoever hides their own "last seen" from everyone sees nobody
 * else's time either — only "last seen recently". Mirrors the user's privacy
 * setting; kept up to date by the repository.
 */
object LastSeenReciprocity {
    var hideOthers by androidx.compose.runtime.mutableStateOf(false)
}

/** "Last seen" text from the presence engine's unix seconds. "" = unknown (or hidden, see [LastSeenReciprocity]). */
@Composable
fun lastSeenLabel(epochSeconds: Long): String {
    // "Minutes ago" moves on by itself, not only when a presence signal arrives.
    val clock by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    if (epochSeconds <= 0L || LastSeenReciprocity.hideOthers) return ""
    val zone = java.time.ZoneId.systemDefault()
    val seen = java.time.Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val now = java.time.Instant.ofEpochMilli(clock).atZone(zone)
    val minutes = java.time.Duration.between(seen, now).toMinutes()
    val time = seen.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        minutes < 1 -> stringResource(R.string.storage_just_now)
        minutes < 60 -> stringResource(R.string.profile_seen_minutes_ago, minutes)
        seen.toLocalDate() == now.toLocalDate() -> stringResource(R.string.profile_seen_today, time)
        seen.toLocalDate() == now.toLocalDate().minusDays(1) -> stringResource(R.string.profile_seen_yesterday, time)
        else -> seen.format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }
}

/**
 * The SINGLE indicator colour, one rule everywhere (profile, chat list, sheet):
 *  - Offline → always grey (a custom colour must never fake presence).
 *  - Dnd → always red (the busy state stays visible).
 *  - Online / Away → user's custom colour if set, else the real presence colour.
 */
fun statusDotColor(status: ProfileStatus, customColor: Int?): Color = when (status) {
    ProfileStatus.Offline -> ProfileStatus.Offline.dot
    ProfileStatus.Dnd -> ProfileStatus.Dnd.dot
    else -> if (customColor != null && customColor != 0) Color(customColor) else status.dot
}

/**
 * Unified profile UI. The stateless [ProfileContent] is shared by BOTH the
 * peer sheet ([ProfileScreen] modal) and the self profile screen
 * (`SelfProfileScreen`). Header = cover + avatar (+ status dot for self) +
 * name + pill public key + status; actions split peer vs self. No
 * chat/MLS/storage/networking logic here — action lambdas call existing
 * handlers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    displayName: String,
    avatarPath: String,
    npub: String,
    colors: AlphaColorScheme,
    bannerPath: String = "",
    /** A video avatar: plays in the circle, silent, looping ("" = none). */
    videoPath: String = "",
    status: ProfileStatus = ProfileStatus.Online,
    statusText: String = "",
    statusColor: Int? = null,
    mediaVersion: Int = 0,
    lastSeen: String = "",
    mutualGroups: List<String> = emptyList(),
    /** "About me", shown between the buttons and the public key. */
    bio: String = "",
    isSelf: Boolean = false,
    showDeleteChat: Boolean = true,
    /** Seed of the placeholder gradient — the same one the chat uses. */
    avatarSeed: String = npub,
    /** Saved Messages: a personal vault, not a person — no calls, key, status or block. */
    isSaved: Boolean = false,
    /** A group: its line under the name ("8 members"); no calls, key, status or block. */
    groupSubtitle: String? = null,
    /** In place of the presence line (a secret chat has none). */
    subtitle: String? = null,
    /** A secret chat: Tor's violet cover with the onion. */
    secret: Boolean = false,
    /** An assistant on this phone: no presence, key or block — it is not a person. */
    isBot: Boolean = false,
    onMessage: () -> Unit = {},
    /** null = no button: the person's privacy rules don't let us call. */
    onVoiceCall: (() -> Unit)? = {},
    onVideoCall: (() -> Unit)? = {},
    onInfo: () -> Unit = {},
    onDeleteChat: () -> Unit = {},
    /** Offered as a box in the delete dialog (null = a chat that can't be). */
    onDeleteChatForBoth: (() -> Unit)? = null,
    onBlock: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ProfileContent(
            displayName = displayName,
            avatarPath = avatarPath,
            npub = npub,
            colors = colors,
            coverPath = bannerPath,
            videoPath = videoPath,
            status = status,
            statusText = statusText,
            statusColor = statusColor,
            mediaVersion = mediaVersion,
            lastSeen = lastSeen,
            mutualGroups = mutualGroups,
            bio = bio,
            isSelf = isSelf,
            showDeleteChat = showDeleteChat,
            avatarSeed = avatarSeed,
            isSaved = isSaved,
            groupSubtitle = groupSubtitle,
            subtitle = subtitle,
            secret = secret,
            isBot = isBot,
            // peer keeps the status LINE only — no dot on the avatar.
            showAvatarDot = false,
            modifier = Modifier.navigationBarsPadding(),
            onMessage = onMessage,
            onVoiceCall = onVoiceCall,
            onVideoCall = onVideoCall,
            onInfo = onInfo,
            onDeleteChat = onDeleteChat,
            onDeleteChatForBoth = onDeleteChatForBoth,
            onBlock = onBlock,
        )
    }
}

/**
 * Stateless profile body reused by the peer sheet and the self screen.
 * [isSelf] switches the action row (Message/Call/Video/Info → Edit/Cover/
 * Avatar/Status) and hides the delete/block rows. [showAvatarDot] draws the
 * presence dot on the avatar (self / main profile; the peer sheet relies on
 * the status line instead).
 */
@Composable
fun ProfileContent(
    displayName: String,
    avatarPath: String,
    npub: String,
    colors: AlphaColorScheme,
    coverPath: String = "",
    videoPath: String = "",
    status: ProfileStatus = ProfileStatus.Online,
    statusText: String = "",
    statusColor: Int? = null,
    mediaVersion: Int = 0,
    lastSeen: String = "",
    mutualGroups: List<String> = emptyList(),
    /** "About me", shown between the buttons and the public key. */
    bio: String = "",
    /** Our own profile: tapping "About me" edits it. */
    onEditBio: (() -> Unit)? = null,
    isSelf: Boolean = false,
    // Group member: a chat with them may not exist yet — nothing to delete.
    showDeleteChat: Boolean = true,
    showAvatarDot: Boolean = false,
    avatarSeed: String = npub,
    isSaved: Boolean = false,
    groupSubtitle: String? = null,
    subtitle: String? = null,
    secret: Boolean = false,
    isBot: Boolean = false,
    modifier: Modifier = Modifier,
    onMessage: () -> Unit = {},
    onVoiceCall: (() -> Unit)? = {},
    onVideoCall: (() -> Unit)? = {},
    onInfo: () -> Unit = {},
    onChangeCover: () -> Unit = {},
    onChangeAvatar: () -> Unit = {},
    onSetStatus: () -> Unit = {},
    onEditName: () -> Unit = {},
    onDeleteChat: () -> Unit = {},
    onDeleteChatForBoth: (() -> Unit)? = null,
    onBlock: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        ProfileHeader(
            displayName = displayName,
            avatarPath = avatarPath,
            coverPath = coverPath,
            videoPath = videoPath,
            status = status,
            statusText = statusText,
            statusColor = statusColor,
            mediaVersion = mediaVersion,
            showAvatarDot = showAvatarDot,
            colors = colors,
            avatarSeed = avatarSeed,
            isSaved = isSaved,
            subtitleOverride = groupSubtitle ?: subtitle ?: if (isBot) stringResource(R.string.profile_bot) else null,
            secret = secret,
            isBot = isBot,
            isGroup = groupSubtitle != null,
        )
        val isGroup = groupSubtitle != null

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(10.dp))
            if (isSelf) {
                SelfActions(colors, onChangeCover, onChangeAvatar, onSetStatus, onEditName)
            } else if (isSaved || isGroup || isBot) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionSquare(AlphaIcons.Outlined.ChatBubbleOutline, stringResource(R.string.profile_action_message), colors, Modifier.weight(1f), onMessage)
                    ActionSquare(AlphaIcons.Outlined.Info, stringResource(R.string.profile_action_info), colors, Modifier.weight(1f), onInfo)
                }
            } else {
                ProfileActions(colors, onMessage, onVoiceCall, onVideoCall, onInfo)
            }

            if (!isSaved && !isGroup && (bio.isNotBlank() || onEditBio != null)) {
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.bgTertiary)
                        .then(if (onEditBio != null) Modifier.clickable(onClick = onEditBio) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (bio.isNotBlank()) com.alphamessenger.ui.chat.text.LinkedLine(bio, colors.textPrimary, 15.sp, maxLines = 12)
                    else Text(stringResource(R.string.profile_bio_add), color = colors.textMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.profile_bio_label), color = colors.textMuted, fontSize = 12.sp)
                }
            }

            if (!isSaved && !isGroup && !isBot) {
                Spacer(Modifier.height(18.dp))
                ProfileInfo(npub = npub, isSelf = isSelf, lastSeen = lastSeen, mutualGroups = mutualGroups, colors = colors)
            }

            if (!isSelf && !isSaved && !isGroup) {
                Spacer(Modifier.height(14.dp))
                if (showDeleteChat) {
                    ProfileListRow(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.profile_delete_chat), colors.textPrimary, colors) { confirmDelete = true }
                }
                if (!isBot) ProfileListRow(AlphaIcons.Outlined.Block, stringResource(R.string.profile_block_user), colors.danger, colors) { confirmBlock = true }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmDelete) {
        val who = displayName.ifBlank { stringResource(R.string.profile_delete_fallback_contact) }
        DeleteChatDialog(
            title = stringResource(R.string.delete_chat_title),
            body = stringResource(R.string.profile_delete_dialog_text, who),
            colors = colors,
            peerName = if (onDeleteChatForBoth != null) who else null,
            onConfirm = { forBoth ->
                confirmDelete = false
                if (forBoth) onDeleteChatForBoth?.invoke() else onDeleteChat()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.profile_block_dialog_title), color = colors.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.profile_block_dialog_text, displayName.ifBlank { stringResource(R.string.profile_block_fallback_user) }),
                    color = colors.textMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmBlock = false; onBlock() }) {
                    Text(stringResource(R.string.profile_block_action), color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) }
            },
        )
    }
}

// --- ProfileHeader ---

/** A secret chat's cover: Tor's violet with the onion in the middle. */
@Composable
private fun SecretCover() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp)
            .background(Brush.linearGradient(listOf(Color(0xFFA463D6), Color(0xFF6A35A8), Color(0xFF2C1554))))
            .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent))),
        contentAlignment = Alignment.Center,
    ) {
        com.alphamessenger.ui.secret.SecretMark(tint = Color.White.copy(alpha = 0.92f), size = 64.dp)
    }
}

/**
 * Cover (image or fallback gradient) with the avatar overlapping its
 * bottom-left, then the display name, pill public key and status line. NO
 * nickname — only displayName.
 */
@Composable
private fun ProfileHeader(
    displayName: String,
    avatarPath: String,
    coverPath: String,
    videoPath: String,
    status: ProfileStatus,
    statusText: String,
    statusColor: Int?,
    mediaVersion: Int,
    showAvatarDot: Boolean,
    colors: AlphaColorScheme,
    avatarSeed: String = "",
    isSaved: Boolean = false,
    subtitleOverride: String? = null,
    secret: Boolean = false,
    isBot: Boolean = false,
    isGroup: Boolean = false,
) {
    val grad = remember(avatarSeed, isSaved, colors.accent) {
        if (isSaved) colors.accent to colors.accent.copy(alpha = 0.55f).compositeOver(Color(0xFF101418))
        else avatarGradientFor(avatarSeed.ifBlank { displayName })
    }
    // A tap on the avatar or the cover opens them full screen.
    val pages = remember(avatarPath, videoPath, coverPath, mediaVersion) {
        if (isSaved) emptyList() else com.alphamessenger.ui.profile.avatarPages(avatarPath, videoPath, coverPath)
    }
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    viewerAt?.let { at ->
        com.alphamessenger.ui.profile.AvatarViewer(displayName, pages, at, onDismiss = { viewerAt = null })
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(158.dp)
                .then(if (!secret && coverPath.isNotBlank() && pages.isNotEmpty())
                    Modifier.clickable { viewerAt = pages.indexOfFirst { it.path == coverPath }.coerceAtLeast(0) } else Modifier),
        ) {
            if (secret) {
                SecretCover()
            } else if (coverPath.isNotBlank()) {
                AsyncImage(
                    model = cacheBustedFile(coverPath, mediaVersion),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(158.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(158.dp)
                        .background(
                            // A group's cover is told apart at a glance: teal into
                            // the theme's accent, where a person's is accent only.
                            Brush.linearGradient(
                                if (subtitleOverride != null) listOf(
                                    Color(0xFF14B8A6).copy(alpha = 0.60f),
                                    colors.accent.copy(alpha = 0.22f),
                                ) else listOf(
                                    colors.accent.copy(alpha = 0.55f),
                                    colors.accent.copy(alpha = 0.16f),
                                )
                            )
                        ),
                )
            }
        }

        // Avatar overlapping the cover bottom-left + optional presence dot.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp)
                .offset(y = 40.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .border(3.dp, colors.bgSecondary, CircleShape)
                    .avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second)))
                    .then(if (pages.isNotEmpty() && pages.first().path != coverPath) Modifier.clickable { viewerAt = 0 } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (isSaved) {
                    Icon(com.alphamessenger.ui.components.AlphaIcons.Vault, null, tint = Color.White, modifier = Modifier.size(42.dp))
                } else if (isBot) {
                    com.alphamessenger.assistant.AssistantAvatar(84.dp)
                } else if (avatarPath.isNotBlank()) {
                    AsyncImage(
                        model = cacheBustedFile(avatarPath, mediaVersion),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(CircleShape),
                    )
                    // The video avatar plays over its still cover.
                    val hasVideo = remember(videoPath, mediaVersion) { videoPath.isNotBlank() && java.io.File(videoPath).length() > 0 }
                    if (hasVideo) LoopingVideoAvatar(videoPath, 84.dp)
                } else if (isGroup) {
                    // The same stand-in the chat list shows for a group without a photo.
                    com.alphamessenger.ui.components.GroupAvatarPlaceholder(Modifier.matchParentSize())
                } else {
                    Text(
                        com.alphamessenger.ui.components.avatarInitial(displayName),
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (showAvatarDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colors.bgSecondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .clip(CircleShape)
                            .background(status.dot),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(52.dp))

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isBot) com.alphamessenger.assistant.BotMark(20.dp, Modifier.padding(end = 6.dp))
            Text(
                displayName.ifBlank { stringResource(R.string.profile_contact_fallback) },
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isBot) com.alphamessenger.assistant.VerifiedBadge(20.dp, Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        // ONE indicator: a single dot (custom colour overrides presence) + the
        // status text (custom text if set, otherwise the preset label).
        if (subtitleOverride != null) {
            Text(subtitleOverride, color = colors.textSecondary, fontSize = 13.sp)
        } else if (isSaved) {
            Text(stringResource(R.string.saved_subtitle), color = colors.textSecondary, fontSize = 13.sp)
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(statusDotColor(status, statusColor)),
            )
            Spacer(Modifier.size(7.dp))
            // A status with a link: the link opens, like in a message.
            if (statusText.isNotBlank() && com.alphamessenger.ui.chat.text.hasTappable(statusText)) {
                com.alphamessenger.ui.chat.text.LinkedLine(statusText, colors.textSecondary, 13.sp)
            } else Text(
                statusText.ifBlank { stringResource(status.labelRes) },
                color = colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- Actions ---

/** Four rounded-square action buttons — shown for a peer's profile. */
@Composable
private fun ProfileActions(
    colors: AlphaColorScheme,
    onMessage: () -> Unit,
    onVoiceCall: (() -> Unit)?,
    onVideoCall: (() -> Unit)?,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionSquare(AlphaIcons.Outlined.ChatBubbleOutline, stringResource(R.string.profile_action_message), colors, Modifier.weight(1f), onMessage)
        onVoiceCall?.let { ActionSquare(AlphaIcons.Outlined.Call, stringResource(R.string.profile_action_call), colors, Modifier.weight(1f), it) }
        onVideoCall?.let { ActionSquare(AlphaIcons.Outlined.Videocam, stringResource(R.string.perm_video), colors, Modifier.weight(1f), it) }
        ActionSquare(AlphaIcons.Outlined.Info, stringResource(R.string.profile_action_info), colors, Modifier.weight(1f), onInfo)
    }
}

/** Self actions — Edit profile / Change cover / Change avatar / Set status. */
@Composable
private fun SelfActions(
    colors: AlphaColorScheme,
    onChangeCover: () -> Unit,
    onChangeAvatar: () -> Unit,
    onSetStatus: () -> Unit,
    onEditName: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionSquare(AlphaIcons.Outlined.Image, stringResource(R.string.common_cover), colors, Modifier.weight(1f), onChangeCover)
        ActionSquare(AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.common_avatar), colors, Modifier.weight(1f), onChangeAvatar)
        ActionSquare(AlphaIcons.Outlined.MoodBad, stringResource(R.string.profile_action_status), colors, Modifier.weight(1f), onSetStatus)
        ActionSquare(AlphaIcons.Outlined.Edit, stringResource(R.string.common_name), colors, Modifier.weight(1f), onEditName)
    }
}

// --- ProfileInfo ---

/**
 * Single clean home for the public key (copy, + share for self) plus last seen
 * and mutual groups — one card, no duplicated key elsewhere.
 */
@Composable
private fun ProfileInfo(
    npub: String,
    isSelf: Boolean,
    lastSeen: String,
    mutualGroups: List<String>,
    colors: AlphaColorScheme,
) {
    if (npub.isBlank() && lastSeen.isBlank() && mutualGroups.isEmpty()) return
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgTertiary)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        if (npub.isNotBlank()) {
            val short = if (npub.length > 22) "${npub.take(14)}…${npub.takeLast(6)}" else npub
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AlphaIcons.Outlined.Key, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_public_key), color = colors.textMuted, fontSize = 11.sp)
                    Text(short, color = colors.textSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("npub", npub))
                }) {
                    Icon(AlphaIcons.Outlined.ContentCopy, contentDescription = stringResource(R.string.profile_copy_key), tint = colors.accent, modifier = Modifier.size(18.dp))
                }
                if (isSelf) {
                    val shareNpubTitle = stringResource(R.string.qr_share_npub_chooser)
                    IconButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, npub)
                        }
                        ctx.startActivity(android.content.Intent.createChooser(send, shareNpubTitle).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }) {
                        Icon(AlphaIcons.Outlined.Share, contentDescription = stringResource(R.string.common_share), tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (lastSeen.isNotBlank()) {
            InfoRow(AlphaIcons.Outlined.Schedule, stringResource(R.string.profile_last_seen), lastSeen, colors)
        }
        if (mutualGroups.isNotEmpty()) {
            InfoRow(
                AlphaIcons.Outlined.Group,
                stringResource(R.string.profile_common_groups),
                mutualGroups.joinToString(", ").let { if (it.length > 60) it.take(60) + "…" else it },
                colors,
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, colors: AlphaColorScheme) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.textMuted, fontSize = 11.sp)
            Text(value, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// --- Shared small pieces ---

@Composable
private fun ActionSquare(
    icon: ImageVector,
    label: String,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.bgTertiary)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = colors.accent)
        Spacer(Modifier.height(6.dp))
        Text(label, color = colors.textPrimary, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ProfileListRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Spacer(Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}
