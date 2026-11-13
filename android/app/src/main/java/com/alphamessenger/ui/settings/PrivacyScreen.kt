package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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

/** Privacy — notifications, activity signals, media storage, calls, blocklist. */
@Composable
fun PrivacyScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val blocked by viewModel.blockedUsers.collectAsState()
    val readReceipts by viewModel.readReceipts.collectAsState()
    val noStoreDefault by viewModel.noStoreDefault.collectAsState()
    val ghost by viewModel.ghost.collectAsState()
    val reach by viewModel.privacy.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshBlocked(); viewModel.refreshPrivacy() }

    // One "who may…" rule opened on a page of its own.
    var rule by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var storiesPage by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (storiesPage) {
        androidx.activity.compose.BackHandler { storiesPage = false }
        com.alphamessenger.ui.stories.StoryAudiencePage(onBack = { storiesPage = false })
        return
    }
    rule?.let { name ->
        androidx.activity.compose.BackHandler { rule = null }
        ReachRulePage(name, reach[name].orEmpty(), onBack = { rule = null }) { viewModel.setPrivacy(name, it) }
        return
    }

    OptionScreen(title = stringResource(R.string.settings_privacy_title), onBack = onBack) {
        // --- Who may reach me ---
        OptionSection(stringResource(R.string.privacy_section_reach))
        OptionGroup {
            REACH_RULES.forEachIndexed { i, r ->
                if (i > 0) OptionDivider()
                OptionRow(
                    title = stringResource(r.title), icon = r.icon,
                    value = stringResource(reachValueLabel(reach[r.name].orEmpty())),
                    onClick = { rule = r.name },
                )
            }
        }
        OptionNote(stringResource(R.string.privacy_reach_note))

        // --- What others see of me ---
        OptionSection(stringResource(R.string.privacy_section_seen))
        OptionGroup {
            SEEN_RULES.forEachIndexed { i, r ->
                if (i > 0) OptionDivider()
                OptionRow(
                    title = stringResource(r.title), icon = r.icon,
                    value = stringResource(reachValueLabel(reach[r.name].orEmpty())),
                    onClick = { rule = r.name },
                )
            }
            OptionDivider()
            OptionRow(
                title = stringResource(R.string.privacy_seen_stories), icon = com.alphamessenger.ui.icons.AlphaIcons.Outlined.AutoStories,
                value = stringResource(com.alphamessenger.ui.stories.storyAudienceLabelRes()),
                onClick = { storiesPage = true },
            )
        }
        OptionNote(stringResource(R.string.privacy_seen_note))

        // --- What a notification shows ---
        OptionSection(stringResource(R.string.notif_preview_title))
        OptionGroup {
            val options = listOf(
                Triple("full", R.string.notif_preview_full, R.string.notif_preview_full_desc),
                Triple("name", R.string.notif_preview_name, R.string.notif_preview_name_desc),
                Triple("none", R.string.notif_preview_none, R.string.notif_preview_none_desc),
            )
            options.forEachIndexed { i, (key, title, desc) ->
                if (i > 0) OptionDivider()
                OptionChoiceRow(stringResource(title), stringResource(desc), uiState.notifPreview == key) {
                    viewModel.setNotifPreview(key)
                }
            }
        }

        // --- What others see ---
        OptionSection(stringResource(R.string.privacy_section_activity))
        OptionGroup {
            OptionSwitchRow(
                title = stringResource(R.string.ghost_title),
                subtitle = stringResource(R.string.ghost_subtitle),
                checked = ghost,
                onCheckedChange = { viewModel.toggleGhost(it) },
                icon = AlphaIcons.Outlined.VisibilityOff,
            )
            OptionDivider()
            val btnCtx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) { com.alphamessenger.ui.components.GhostButtonPref.load(btnCtx) }
            OptionSwitchRow(
                title = stringResource(R.string.ghost_button_title),
                subtitle = stringResource(R.string.ghost_button_subtitle),
                checked = com.alphamessenger.ui.components.GhostButtonPref.visible,
                onCheckedChange = { com.alphamessenger.ui.components.GhostButtonPref.set(btnCtx, it) },
            )
            OptionDivider()
            // Ghost mode covers these two; they come back as they were when it is off.
            OptionSwitchRow(
                title = stringResource(R.string.privacy_typing_title),
                subtitle = stringResource(R.string.privacy_typing_subtitle),
                checked = uiState.typingIndicatorsEnabled && !ghost,
                onCheckedChange = { viewModel.toggleTypingIndicators(it) },
                icon = AlphaIcons.Outlined.Keyboard,
                enabled = !ghost,
            )
            OptionDivider()
            OptionSwitchRow(
                title = stringResource(R.string.privacy_receipts_title),
                subtitle = stringResource(R.string.privacy_receipts_subtitle),
                checked = readReceipts && !ghost,
                onCheckedChange = { viewModel.toggleReadReceipts(it) },
                icon = AlphaIcons.Outlined.DoneAll,
                enabled = !ghost,
            )
        }

        // --- Media on storage servers ---
        OptionSection(stringResource(R.string.privacy_section_media))
        OptionGroup {
            val metaCtx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) { com.alphamessenger.ui.media.MetadataScrubber.load(metaCtx) }
            OptionSwitchRow(
                title = stringResource(R.string.privacy_strip_meta_title),
                subtitle = stringResource(R.string.privacy_strip_meta_subtitle),
                checked = com.alphamessenger.ui.media.MetadataScrubber.enabled,
                onCheckedChange = { com.alphamessenger.ui.media.MetadataScrubber.setEnabled(metaCtx, it) },
                icon = AlphaIcons.Outlined.LocationOff,
            )
            OptionDivider()
            OptionSwitchRow(
                title = stringResource(R.string.privacy_no_store_title),
                subtitle = stringResource(R.string.privacy_no_store_subtitle),
                checked = noStoreDefault,
                onCheckedChange = { viewModel.toggleNoStoreDefault(it) },
                icon = AlphaIcons.Outlined.CloudOff,
            )
        }

        // --- Screen ---
        OptionSection(stringResource(R.string.privacy_section_screen))
        val screenCtx = androidx.compose.ui.platform.LocalContext.current
        OptionGroup {
            OptionSwitchRow(
                title = stringResource(R.string.privacy_screen_security),
                subtitle = stringResource(R.string.privacy_screen_security_hint),
                checked = com.alphamessenger.ui.components.ScreenSecurity.always,
                onCheckedChange = { com.alphamessenger.ui.components.ScreenSecurity.setAlways(screenCtx, it) },
                icon = com.alphamessenger.ui.icons.AlphaIcons.Outlined.Lock,
            )
        }

        // --- Calls ---
        OptionSection(stringResource(R.string.privacy_section_calls))
        CallsPrivacySection(viewModel)

        // --- Links ---
        OptionSection(stringResource(R.string.privacy_section_links))
        val linkPreviews by viewModel.linkPreviews.collectAsState()
        OptionGroup {
            OptionSwitchRow(
                title = stringResource(R.string.privacy_link_previews),
                subtitle = stringResource(R.string.privacy_link_previews_hint),
                checked = linkPreviews,
                onCheckedChange = { viewModel.toggleLinkPreviews(it) },
                icon = com.alphamessenger.ui.icons.AlphaIcons.Outlined.Link,
            )
            OptionDivider()
            val inAppVideo by viewModel.inAppVideo.collectAsState()
            OptionSwitchRow(
                title = stringResource(R.string.privacy_in_app_video),
                subtitle = stringResource(R.string.privacy_in_app_video_hint),
                checked = inAppVideo,
                onCheckedChange = { viewModel.toggleInAppVideo(it) },
                icon = com.alphamessenger.ui.icons.AlphaIcons.Outlined.PlayCircle,
            )
        }

        // --- Blocked ---
        OptionSection(stringResource(R.string.privacy_blocked_title))
        OptionGroup {
            if (blocked.isEmpty()) {
                OptionRow(title = stringResource(R.string.privacy_blocked_empty))
            } else {
                blocked.forEachIndexed { i, user ->
                    if (i > 0) OptionDivider()
                    OptionRow(
                        title = user.name.ifBlank { stringResource(R.string.profile_no_name) },
                        subtitle = user.npub.take(16) + "…" + user.npub.takeLast(6),
                        trailing = {
                            TextButton(onClick = { viewModel.unblockUser(user.npub) }) {
                                Text(stringResource(R.string.privacy_unblock), color = AlphaTheme.colorScheme.accent)
                            }
                        },
                    )
                }
            }
        }

        // --- What the app guarantees ---
        OptionSection(stringResource(R.string.privacy_guarantees_title))
        OptionGroup {
            Column(Modifier.padding(16.dp)) {
                listOf(
                    R.string.privacy_bullet_1, R.string.privacy_bullet_2, R.string.privacy_bullet_3,
                    R.string.privacy_bullet_4, R.string.privacy_bullet_5,
                ).forEach { res ->
                    Text(
                        "•  " + stringResource(res), color = AlphaTheme.colorScheme.textSecondary,
                        fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Relay-only calls through the user's TURN server: the peer never sees our IP. */
@Composable
private fun CallsPrivacySection(viewModel: SettingsViewModel) {
    val cs = AlphaTheme.colorScheme
    val relayOnly by viewModel.callsRelayOnly.collectAsState()
    val turn by viewModel.turn.collectAsState()
    OptionGroup {
        OptionSwitchRow(
            title = stringResource(R.string.privacy_calls_title),
            subtitle = stringResource(R.string.privacy_calls_subtitle),
            checked = relayOnly,
            onCheckedChange = { viewModel.toggleCallsRelayOnly(it) },
            icon = AlphaIcons.Outlined.Call,
        )
        if (relayOnly) {
            var url by remember(turn) { mutableStateOf(turn.first) }
            var user by remember(turn) { mutableStateOf(turn.second) }
            var pass by remember(turn) { mutableStateOf(turn.third) }
            OptionDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.accent, unfocusedBorderColor = cs.alphaBorder,
                    focusedTextColor = cs.textPrimary, unfocusedTextColor = cs.textPrimary, cursorColor = cs.accent,
                    focusedLabelColor = cs.accent, unfocusedLabelColor = cs.textMuted,
                )
                if (turn.first.isBlank()) {
                    Text(stringResource(R.string.privacy_calls_need_turn), color = cs.warning, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.privacy_turn_url)) }, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.privacy_turn_user)) }, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.privacy_turn_pass)) },
                    visualTransformation = PasswordVisualTransformation(), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { viewModel.saveTurn(url, user, pass) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.common_save), color = cs.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// --- Who may reach me -----------------------------------------------------------

/** A rule of "who may reach me": its core name, title and the values offered. */
private class ReachRule(
    val name: String,
    @androidx.annotation.StringRes val title: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val values: List<String>,
)

private val REACH_RULES = listOf(
    ReachRule("calls", R.string.privacy_reach_calls, com.alphamessenger.ui.icons.AlphaIcons.Outlined.Call, listOf("all", "contacts", "nobody")),
    ReachRule("video_calls", R.string.privacy_reach_video, com.alphamessenger.ui.icons.AlphaIcons.Outlined.Videocam, listOf("all", "contacts", "nobody")),
    ReachRule("new_chats", R.string.privacy_reach_new_chats, com.alphamessenger.ui.icons.AlphaIcons.Outlined.MarkEmailUnread, listOf("all", "requests", "nobody")),
    ReachRule("group_invites", R.string.privacy_reach_groups, com.alphamessenger.ui.icons.AlphaIcons.Outlined.GroupAdd, listOf("all", "contacts", "nobody")),
)

private val SEEN_RULES = listOf(
    ReachRule("photo", R.string.privacy_seen_photo, com.alphamessenger.ui.icons.AlphaIcons.Outlined.AccountCircle, listOf("all", "contacts", "nobody")),
    ReachRule("last_seen", R.string.privacy_seen_last_seen, com.alphamessenger.ui.icons.AlphaIcons.Outlined.Schedule, listOf("all", "contacts", "nobody")),
    ReachRule("about", R.string.privacy_seen_about, com.alphamessenger.ui.icons.AlphaIcons.Outlined.Info, listOf("all", "contacts", "nobody")),
    ReachRule("status", R.string.privacy_seen_status, com.alphamessenger.ui.icons.AlphaIcons.Outlined.ChatBubbleOutline, listOf("all", "contacts", "nobody")),
)

private fun reachValueLabel(value: String): Int = when (value) {
    "all" -> R.string.privacy_value_all
    "nobody" -> R.string.privacy_value_nobody
    "requests" -> R.string.privacy_value_requests
    else -> R.string.privacy_value_contacts
}

@Composable
private fun ReachRulePage(name: String, current: String, onBack: () -> Unit, onPick: (String) -> Unit) {
    val r = (REACH_RULES + SEEN_RULES).first { it.name == name }
    OptionScreen(title = stringResource(r.title), onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        OptionGroup {
            r.values.forEachIndexed { i, v ->
                if (i > 0) OptionDivider()
                OptionChoiceRow(stringResource(reachValueLabel(v)), selected = current == v) { onPick(v) }
            }
        }
        OptionNote(stringResource(
            when (name) {
                "new_chats" -> R.string.privacy_reach_new_chats_note
                "group_invites" -> R.string.privacy_reach_groups_note
                "photo" -> R.string.privacy_seen_photo_note
                "last_seen" -> R.string.privacy_seen_last_seen_note
                "about" -> R.string.privacy_seen_about_note
                "status" -> R.string.privacy_seen_status_note
                else -> R.string.privacy_reach_calls_note
            },
        ))
    }
}

