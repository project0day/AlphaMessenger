package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.layout.heightIn
import com.alphamessenger.ui.components.avatarBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.alphamessenger.R
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.alphamessenger.ui.theme.themeBackground
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.chat.avatarGradientFor
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * SettingsScreen — classic root settings.
 *
 * Layout matches the user's reference: search-equipped TopAppBar,
 * gradient avatar profile header with QR + add buttons, then a list of
 * categories with coloured square icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onProfileClick: () -> Unit,
    onRelaysClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onThemeClick: () -> Unit,
    onStickersClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onExportClick: () -> Unit = {},
    onAppLockClick: () -> Unit = {},
    onDevicesClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onFoldersClick: () -> Unit = {},
    onAutoDownloadClick: () -> Unit = {},
    onLanguageClick: () -> Unit = {},
    onAssistantClick: () -> Unit = {},
    onBrowserClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val colors = AlphaTheme.colorScheme
    val themeViewModel: com.alphamessenger.ui.theme.ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val currentThemeVariant by themeViewModel.themeVariant.collectAsState()
    val currentCustomTheme by themeViewModel.activeCustom.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var shareSheetOpen by remember { mutableStateOf(false) }
    val npubFromState = state.identityInfo?.npub.orEmpty()

    // Pull the latest profile (avatar path + display name). Initial load
    // + reactive refresh whenever the user changes profile elsewhere.
    var avatarPath by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { viewModel.repository.getMyProfile() }.getOrNull()?.let {
            avatarPath = it.avatarPath
            displayName = it.displayName.ifBlank { state.displayName }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.repository.profileChanged.collect {
            runCatching { viewModel.repository.getMyProfile() }.getOrNull()?.let {
                avatarPath = it.avatarPath
                displayName = it.displayName.ifBlank { state.displayName }
            }
        }
    }

    Scaffold(
        modifier = Modifier.themeBackground(colors),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SettingsTopBar(
                colors = colors,
                onBack = onBack,
                onSearch = { /* TODO: in-settings search */ },
            )
        },
        snackbarHost = {
            androidx.compose.material3.SnackbarHost(
                snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(
                bottom = 32.dp +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            item {
                ProfileHeader(
                    displayName = displayName.ifBlank { state.displayName.ifBlank { stringResource(R.string.profile_no_name) } },
                    npub = state.identityInfo?.npub ?: stringResource(R.string.profile_npub_loading),
                    avatarPath = avatarPath,
                    onClick = onProfileClick,
                    onQrClick = { shareSheetOpen = true },        // opens standalone QR sheet
                    colors = colors,
                )
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(thickness = 0.5.dp, color = colors.alphaBorder)
                Spacer(Modifier.height(8.dp))
            }
            item {
                com.alphamessenger.ui.update.UpdateCard(colors, viewModel.repository,
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp), expanded = true)
            }

            // --- Account ---
            item {
                SettingsSectionLabel(stringResource(R.string.settings_section_account), colors)
                SettingsGroup(colors) {
                CategoryRow(
                    title = stringResource(R.string.settings_backup_title),
                    subtitle = stringResource(R.string.settings_backup_subtitle),
                    icon = AlphaIcons.Outlined.CloudUpload,
                    iconBg = Color(0xFF22A7B8),
                    onClick = onBackupClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.export_title_profile),
                    subtitle = stringResource(R.string.settings_export_subtitle),
                    icon = AlphaIcons.Outlined.FolderZip,
                    iconBg = Color(0xFF8E6CEF),
                    onClick = onExportClick,
                    colors = colors,
                )
                }
            }

            // --- Privacy & security ---
            item {
                Spacer(Modifier.height(8.dp))
                SettingsSectionLabel(stringResource(R.string.settings_section_privacy), colors)
                SettingsGroup(colors) {
                CategoryRow(
                    title = stringResource(R.string.settings_privacy_title),
                    subtitle = stringResource(R.string.settings_privacy_subtitle),
                    icon = AlphaIcons.Outlined.Lock,
                    iconBg = Color(0xFF5B7CFA),
                    onClick = onPrivacyClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.settings_applock_title),
                    subtitle = stringResource(R.string.settings_applock_subtitle),
                    icon = AlphaIcons.Outlined.Lock,
                    iconBg = Color(0xFF7A5BF0),
                    onClick = onAppLockClick,
                    colors = colors,
                )
                // Linking is frozen: the row stays only while a phone is still linked (to unlink it).
                val linkedPhones by androidx.compose.runtime.produceState(0) {
                    value = viewModel.repository.linkedDevices().size
                }
                if (com.alphamessenger.ui.onboarding.LinkFlow.LINKING_ENABLED || linkedPhones > 1) {
                    SettingsRowDivider(colors)
                    CategoryRow(
                        title = stringResource(R.string.settings_devices_title),
                        subtitle = stringResource(R.string.settings_devices_subtitle),
                        icon = AlphaIcons.Outlined.PhonelinkErase,
                        iconBg = Color(0xFF3B9BE8),
                        onClick = onDevicesClick,
                        colors = colors,
                    )
                }
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.assistant_title),
                    subtitle = stringResource(R.string.assistant_settings_subtitle),
                    icon = AlphaIcons.Outlined.AutoAwesome,
                    iconBg = Color(0xFF6A5BF0),
                    onClick = onAssistantClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.browser_title),
                    subtitle = stringResource(R.string.browser_settings_subtitle),
                    icon = AlphaIcons.Outlined.Public,
                    iconBg = Color(0xFF2F80ED),
                    onClick = onBrowserClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.settings_relays_title),
                    subtitle = stringResource(R.string.settings_relays_active, state.relays.count { it.isEnabled }),
                    icon = AlphaIcons.Outlined.Hub,
                    iconBg = Color(0xFF26A69A),
                    onClick = onRelaysClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.folders_settings_title),
                    subtitle = stringResource(R.string.folders_settings_subtitle),
                    icon = AlphaIcons.Outlined.Folder,
                    iconBg = Color(0xFFF0A93B),
                    onClick = onFoldersClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.settings_storage_title),
                    subtitle = stringResource(R.string.settings_storage_subtitle),
                    icon = AlphaIcons.Outlined.Storage,
                    iconBg = Color(0xFF5C9CE6),
                    onClick = onStorageClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.settings_autodownload_title),
                    subtitle = stringResource(R.string.settings_autodownload_subtitle),
                    icon = AlphaIcons.Outlined.Download,
                    iconBg = Color(0xFF42B883),
                    onClick = onAutoDownloadClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                var backgroundGuide by remember { mutableStateOf(false) }
                CategoryRow(
                    title = stringResource(R.string.bg_delivery_title),
                    subtitle = stringResource(R.string.settings_bg_delivery_subtitle),
                    icon = AlphaIcons.Outlined.BatteryChargingFull,
                    iconBg = Color(0xFFE5793B),
                    onClick = { backgroundGuide = true },
                    colors = colors,
                )
                if (backgroundGuide) com.alphamessenger.ui.background.BackgroundDeliverySheet(onDismiss = { backgroundGuide = false })
                }
            }

            // --- Appearance ---
            item {
                Spacer(Modifier.height(8.dp))
                SettingsSectionLabel(stringResource(R.string.settings_section_appearance), colors)
                SettingsGroup(colors) {
                CategoryRow(
                    title = stringResource(R.string.settings_theme_title),
                    subtitle = currentCustomTheme?.name ?: currentThemeVariant.displayName,
                    icon = AlphaIcons.Outlined.Brightness4,
                    iconBg = Color(0xFF9B7BD3),
                    onClick = onThemeClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.settings_stickers_emoji),
                    subtitle = stringResource(R.string.settings_stickers_emoji_hint),
                    icon = AlphaIcons.Outlined.EmojiEmotions,
                    iconBg = Color(0xFFE5A93B),
                    onClick = onStickersClick,
                    colors = colors,
                )
                SettingsRowDivider(colors)
                CategoryRow(
                    title = stringResource(R.string.settings_language_title),
                    subtitle = currentLanguageName(),
                    icon = AlphaIcons.Outlined.Language,
                    iconBg = Color(0xFF4FA3E0),
                    onClick = onLanguageClick,
                    colors = colors,
                )
                }
            }

            // --- About ---
            item {
                Spacer(Modifier.height(8.dp))
                SettingsGroup(colors) {
                CategoryRow(
                    title = stringResource(R.string.settings_about_title),
                    subtitle = "Alpha ${com.alphamessenger.BuildConfig.VERSION_NAME} · MLS + Nostr",
                    icon = AlphaIcons.Outlined.Info,
                    iconBg = Color(0xFF5B7CFA),
                    onClick = onAboutClick,
                    colors = colors,
                )
                }
            }
        }
    }

    if (shareSheetOpen) {
        com.alphamessenger.ui.chatlist.ShareNpubSheet(
            npub = npubFromState,
            colors = colors,
            onDismiss = { shareSheetOpen = false },
            loadCode = { viewModel.repository.contactCode() },
        )
    }

}

@Composable
private fun currentLanguageName(): String {
    val tags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
    return if (tags.isEmpty()) stringResource(R.string.language_system) else nativeLanguageName(tags) ?: tags
}

// --- Top bar with back + search ---

@Composable
private fun SettingsTopBar(
    colors: AlphaColorScheme,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgPrimary)
            .padding(
                top = WindowInsets.statusBars
                    .asPaddingValues()
                    .calculateTopPadding(),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    AlphaIcons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = colors.textPrimary,
                )
            }
            Text(
                stringResource(R.string.settings_title),
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
                Icon(
                    AlphaIcons.Outlined.Search,
                    contentDescription = stringResource(R.string.common_search),
                    tint = colors.textPrimary,
                )
            }
        }
    }
}

// --- Profile header — gradient avatar + name + QR/add buttons ---

@Composable
private fun ProfileHeader(
    displayName: String,
    npub: String,
    avatarPath: String,
    onClick: () -> Unit,
    onQrClick: () -> Unit,
    colors: AlphaColorScheme,
) {
    val grad = avatarGradientFor(npub)
    val settingsCtx = androidx.compose.ui.platform.LocalContext.current
    val avatarRequest = remember(avatarPath) {
        val p = avatarPath
        if (p.isBlank()) null
        else java.io.File(p).let { f ->
            coil3.request.ImageRequest.Builder(settingsCtx).data(f)
                .memoryCacheKey("$p#${f.lastModified()}")
                .diskCacheKey("$p#${f.lastModified()}")
                .build()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (displayName.firstOrNull() ?: 'A').uppercaseChar().toString(),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (avatarRequest != null) {
                coil3.compose.AsyncImage(
                    model = avatarRequest,
                    contentDescription = stringResource(R.string.common_avatar),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            // Current status (indicator + text) instead of the public key — the
            // key lives on the profile screen; here it's handier to see status.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val (busy, custom, color) = loadStatus(ctx)
            val selfStatus = if (busy) com.alphamessenger.ui.chat.ProfileStatus.Dnd
                else com.alphamessenger.ui.chat.ProfileStatus.Online
            val dotColor = com.alphamessenger.ui.chat.statusDotColor(selfStatus, color.takeIf { it != 0 })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = custom.ifBlank { stringResource(selfStatus.labelRes) },
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionChip(icon = AlphaIcons.Outlined.QrCode, onClick = onQrClick, colors = colors, tinted = false)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    colors: AlphaColorScheme,
    tinted: Boolean,
) {
    val iconTint = if (tinted) colors.accent else colors.textPrimary
    val ringColor = if (tinted) colors.accent else colors.textMuted.copy(alpha = 0.6f)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.bgTertiary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
 // Subtle ring around the chip — matches outline look.
        androidx.compose.foundation.Canvas(modifier = Modifier.size(36.dp)) {
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - 0.5.dp.toPx(),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

private fun shortNpub(npub: String): String =
    if (npub.length > 24) "${npub.take(16)}…${npub.takeLast(6)}" else npub

// --- Section label — small muted header above a group of rows ---

@Composable
private fun SettingsSectionLabel(text: String, colors: AlphaColorScheme) {
    Text(
        text = text.uppercase(),
        color = colors.textMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 8.dp),
    )
}

/** A card holding the rows of one section (new design: solid surface, no glass). */
@Composable
private fun SettingsGroup(colors: AlphaColorScheme, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.bgSecondary),
    ) { content() }
}

@Composable
private fun SettingsRowDivider(colors: AlphaColorScheme) {
    HorizontalDivider(Modifier.padding(start = 68.dp), thickness = 0.5.dp, color = colors.alphaBorder)
}

// --- Category row — coloured square icon + title + subtitle ---

@Composable
private fun CategoryRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    onClick: () -> Unit,
    colors: AlphaColorScheme,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = iconBg,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
