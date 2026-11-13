package com.alphamessenger.ui.profile

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.alphamessenger.R
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.ui.theme.AlphaColorScheme
import java.io.File

/**
 * Unified peer profile sheet.
 *
 * Layout (top → bottom):
 *   ProfileHeader  — banner (gradient fallback) + avatar overlay + name + status
 *   ProfileActions — 4 action squares (Message / Call / Video / Info)
 *   ProfileInfo    — public key card
 *   Destructive    — Delete chat / Block user (with confirmation dialogs)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    peerName: String,
    peerAvatarPath: String,
    peerNpub: String,
    colors: AlphaColorScheme,
    onMessage: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onInfo: () -> Unit,
    onDeleteChat: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock  by remember { mutableStateOf(false) }

    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            ProfileHeader(
                peerName      = peerName,
                peerAvatarPath = peerAvatarPath,
                colors        = colors,
            )

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(14.dp))

                ProfileActions(
                    colors       = colors,
                    onMessage    = onMessage,
                    onVoiceCall  = onVoiceCall,
                    onVideoCall  = onVideoCall,
                    onInfo       = onInfo,
                )

                if (peerNpub.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    ProfileInfo(peerNpub = peerNpub, colors = colors)
                }

                Spacer(Modifier.height(20.dp))

                ProfileDestructiveRow(
                    icon  = AlphaIcons.Outlined.DeleteOutline,
                    label = stringResource(R.string.profile_delete_chat),
                    tint  = colors.textPrimary,
                    colors = colors,
                ) { confirmDelete = true }

                ProfileDestructiveRow(
                    icon  = AlphaIcons.Outlined.Block,
                    label = stringResource(R.string.profile_block_user),
                    tint  = colors.danger,
                    colors = colors,
                ) { confirmBlock = true }

                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor   = colors.bgSecondary,
            title = { Text(stringResource(R.string.profile_delete_dialog_title), color = colors.textPrimary) },
            text  = {
                Text(
                    stringResource(R.string.profile_delete_dialog_text, peerName.ifBlank { stringResource(R.string.profile_delete_fallback_contact) }),
                    color = colors.textMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteChat() }) {
                    Text(stringResource(R.string.common_delete), color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                }
            },
        )
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            containerColor   = colors.bgSecondary,
            title = { Text(stringResource(R.string.profile_block_dialog_title), color = colors.textPrimary) },
            text  = {
                Text(
                    stringResource(R.string.profile_block_dialog_text, peerName.ifBlank { stringResource(R.string.profile_block_fallback_user) }),
                    color = colors.textMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmBlock = false; onBlock() }) {
                    Text(stringResource(R.string.profile_block_action), color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlock = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ProfileHeader — banner (3:1 gradient) + avatar overlap + name + status line
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileHeader(
    peerName: String,
    peerAvatarPath: String,
    colors: AlphaColorScheme,
) {
    val bannerHeight = 148.dp
    val avatarSize   = 88.dp
    val avatarOffset = avatarSize / 2   // half avatar peeks above banner bottom

    Box(modifier = Modifier.fillMaxWidth()) {
        // Banner — gradient placeholder (real banner URL lands in a later phase)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bannerHeight)
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.accent.copy(alpha = 0.70f),
                            colors.accent.copy(alpha = 0.20f),
                            colors.bgSecondary,
                        )
                    )
                ),
        )

        // Avatar circle — bottom-left corner, overlapping the banner
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp)
                .offset(y = avatarOffset),
        ) {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .border(3.dp, colors.bgSecondary, CircleShape)
                    .background(colors.bgTertiary),
                contentAlignment = Alignment.Center,
            ) {
                if (peerAvatarPath.isNotBlank() && File(peerAvatarPath).exists()) {
                    AsyncImage(
                        model             = File(peerAvatarPath),
                        contentDescription = null,
                        contentScale      = ContentScale.Crop,
                        modifier          = Modifier
                            .size(avatarSize)
                            .clip(CircleShape),
                    )
                } else {
                    Text(
                        com.alphamessenger.ui.components.avatarInitial(peerName),
                        color      = colors.accent,
                        fontSize   = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Online presence dot
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.bgSecondary),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(colors.onlineGreen),
                )
            }
        }
    }

    // Name + status — shifted down so avatar overlap is absorbed
    Column(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = avatarOffset + 6.dp),
    ) {
        Text(
            text       = peerName.ifBlank { stringResource(R.string.profile_contact_fallback) },
            color      = colors.textPrimary,
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        StatusLine(colors = colors)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// StatusLine — presence indicator + custom text status
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusLine(
    colors: AlphaColorScheme,
    statusText: String = stringResource(R.string.presence_online),     // placeholder; real presence wired later
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.onlineGreen),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text     = statusText,
            color    = colors.onlineGreen,
            fontSize = 13.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ProfileActions — 4 icon+label squares
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileActions(
    colors: AlphaColorScheme,
    onMessage: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProfileActionSquare(AlphaIcons.Outlined.ChatBubbleOutline, stringResource(R.string.profile_action_message), colors, Modifier.weight(1f), onMessage)
        ProfileActionSquare(AlphaIcons.Outlined.Call,              stringResource(R.string.profile_action_call),    colors, Modifier.weight(1f), onVoiceCall)
        ProfileActionSquare(AlphaIcons.Outlined.Videocam,          stringResource(R.string.perm_video),     colors, Modifier.weight(1f), onVideoCall)
        ProfileActionSquare(AlphaIcons.Outlined.Info,              stringResource(R.string.profile_action_info),      colors, Modifier.weight(1f), onInfo)
    }
}

@Composable
private fun ProfileActionSquare(
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
        Icon(icon, contentDescription = label, tint = colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = colors.textPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ProfileInfo — public key card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileInfo(
    peerNpub: String,
    colors: AlphaColorScheme,
) {
    val ctx = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.bgTertiary)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AlphaIcons.Outlined.Key,
                contentDescription = null,
                tint     = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.profile_public_key),
                    color    = colors.textSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    peerNpub,
                    color    = colors.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.12f))
                    .clickable {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("npub", peerNpub))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AlphaIcons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.common_copy),
                    tint     = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ProfileDestructiveRow — delete / block rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileDestructiveRow(
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
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}
