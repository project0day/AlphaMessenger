package com.alphamessenger.ui.contacts

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * What can be done to one group member, by the viewer's own rights: roles,
 * mute, ban, ownership. One place for the group info list and the
 * Administrators page. [target] opens the menu; the follow-up dialogs
 * (ban, mute, transfer) are kept here.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MemberActionsHost(
    state: ContactInfoUiState,
    viewModel: ContactInfoViewModel?,
    target: PeerRow?,
    onDismiss: () -> Unit,
) {
    val colors = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    var banTarget by remember { mutableStateOf<PeerRow?>(null) }
    var muteTarget by remember { mutableStateOf<PeerRow?>(null) }
    var transferTarget by remember { mutableStateOf<PeerRow?>(null) }
    var banReason by remember { mutableStateOf("") }
    // Temporary Ban: selected duration in hours (0 = permanent).

    // Member action dialog: ban (G-2). Ban = remove from the group + blocklist
    // (permanent until you unban). Timed durations belong to MUTE (G-4), not ban.
    // --- Member action menu (G-3 roles + ban, by own rights) ---
    target?.let { peer ->
        val myRole = state.myRole
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onDismiss() },
            containerColor = colors.bgSecondary,
            title = { Text(peer.displayName.ifBlank { stringResource(R.string.chat_member_fallback) }, color = colors.textPrimary) },
            text = {
                Column {
                    Text(roleLabel(peer.role), color = colors.textMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    // Roles are only available in groups with a recorded owner.
                    if (state.rolesEnabled) {
                        // Promote to admin (OWNER only)
                        if (canChangeRole(myRole, peer.role, "admin")) {
                            MemberActionItem(stringResource(R.string.member_action_make_admin), colors, AlphaIcons.Outlined.AdminPanelSettings) {
                                viewModel?.setMemberRole(peer, "admin") { toast(ctx, it) }; onDismiss()
                            }
                        }
                        // Grant / revoke moderator (OWNER/ADMIN)
                        // A channel has no moderators: subscribers can't write, there is nothing to moderate.
                        if (state.channel == null && peer.role != "moderator" && canChangeRole(myRole, peer.role, "moderator")) {
                            MemberActionItem(stringResource(R.string.member_action_make_moderator), colors, AlphaIcons.Outlined.Shield) {
                                viewModel?.setMemberRole(peer, "moderator") { toast(ctx, it) }; onDismiss()
                            }
                        }
                        if (roleRank(peer.role) in 1..2 && canChangeRole(myRole, peer.role, "member")) {
                            MemberActionItem(stringResource(R.string.member_action_remove_role), colors, AlphaIcons.Outlined.PersonRemove) {
                                viewModel?.setMemberRole(peer, "member") { toast(ctx, it) }; onDismiss()
                            }
                        }
                        // Transfer ownership (OWNER only)
                        if (myRole == "owner" && peer.role != "owner") {
                            MemberActionItem(stringResource(R.string.member_action_transfer_ownership), colors, AlphaIcons.Outlined.Key) {
                                transferTarget = peer; onDismiss()
                            }
                        }
                    }
                    // Mute / unmute (G-4, requires the remove right). Not in a channel:
                    // subscribers don't write there anyway — remove or ban instead.
                    if (state.channel == null && canRemove(myRole, peer.role)) {
                        val muted = peer.mutedUntil > com.alphamessenger.util.TrustedTime.nowSeconds()
                        if (muted) {
                            MemberActionItem(stringResource(R.string.member_action_unmute), colors, AlphaIcons.Outlined.VolumeUp) {
                                viewModel?.muteMember(peer, 0L) { toast(ctx, it) }; onDismiss()
                            }
                        } else {
                            MemberActionItem(stringResource(R.string.member_action_mute), colors, AlphaIcons.Outlined.VolumeOff) {
                                muteTarget = peer; onDismiss()
                            }
                        }
                    }
                    // Ban (requires the remove right)
                    if (canRemove(myRole, peer.role)) {
                        MemberActionItem(stringResource(R.string.member_action_ban), colors, AlphaIcons.Outlined.Block, danger = true) {
                            banTarget = peer; onDismiss()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { onDismiss() }) {
                    Text(stringResource(R.string.common_close), color = colors.textMuted)
                }
            },
        )
    }

    // --- Ownership-transfer confirmation ---
    transferTarget?.let { peer ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { transferTarget = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.transfer_confirm_title), color = colors.textPrimary) },
            text = {
                Text(
                    stringResource(
                        R.string.transfer_confirm_text,
                        peer.displayName.ifBlank { stringResource(R.string.chat_member_fallback) },
                    ),
                    color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel?.transferOwnership(peer) { toast(ctx, it) }
                    transferTarget = null
                }) { Text(stringResource(R.string.transfer_action), color = Color(0xFFFF5A5A)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { transferTarget = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                }
            },
        )
    }

    // --- Ban confirmation ---
    banTarget?.let { peer ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { banReason = ""; banTarget = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.ban_confirm_title, peer.displayName), color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.ban_confirm_text),
                        color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Ready reasons: one tap fills the reason (it can still be edited).
                    Text(stringResource(R.string.ban_reason_label), color = colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val presets = listOf(
                        stringResource(R.string.ban_reason_spam), stringResource(R.string.ban_reason_insults),
                        stringResource(R.string.ban_reason_fraud), stringResource(R.string.ban_reason_flood),
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        presets.forEach { label ->
                            val on = banReason == label
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) colors.accent else colors.bgTertiary)
                                    .clickable { banReason = if (on) "" else label }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text(label, color = if (on) Color.White else colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = banReason,
                        onValueChange = { banReason = it.take(120) },
                        placeholder = { Text(stringResource(R.string.ban_reason_hint), color = colors.textMuted) },
                        singleLine = false,
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel?.banMember(peer, 0L, banReason)
                    banReason = ""
                    banTarget = null
                }) { Text(stringResource(R.string.member_action_ban), color = Color(0xFFFF5A5A)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { banReason = ""; banTarget = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                }
            },
        )
    }

    // --- Mute member (pick duration) ---
    muteTarget?.let { peer ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { muteTarget = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.mute_confirm_title, peer.displayName), color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.mute_confirm_text),
                        color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    val opts = listOf(stringResource(R.string.mute_for_1h) to 1L, stringResource(R.string.chat_timer_24h) to 24L, stringResource(R.string.chat_timer_7d) to 168L)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        opts.forEach { (label, hrs) ->
                            Box(
                                modifier = Modifier.weight(1f)
                                    .clip(RoundedCornerShape(12.dp)).background(colors.accentDim)
                                    .clickable {
                                        val until = com.alphamessenger.util.TrustedTime.nowSeconds() + hrs * 3600
                                        viewModel?.muteMember(peer, until) { toast(ctx, it) }
                                        muteTarget = null
                                    }
                                    .padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { muteTarget = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textMuted)
                }
            },
        )
    }
}
