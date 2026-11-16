package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.CustomTheme
import com.alphamessenger.ui.components.isDarkSurface
import com.alphamessenger.ui.components.seasonalDecor
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.alphamessenger.ui.theme.customWallpaperBackground
import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import com.alphamessenger.calls.ui.callBackdrop
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.AlphaThemeVariant
import com.alphamessenger.ui.theme.ChatWallpaper
import com.alphamessenger.ui.theme.FontSize
import com.alphamessenger.ui.theme.ThemeViewModel
import com.alphamessenger.ui.theme.WALLPAPER_PRESETS
import com.alphamessenger.ui.theme.WALLPAPER_SOLID_PRESETS
import com.alphamessenger.ui.theme.azureColorScheme
import com.alphamessenger.ui.theme.carbonColorScheme
import com.alphamessenger.ui.theme.amoledColorScheme
import com.alphamessenger.ui.theme.cosmosColorScheme
import com.alphamessenger.ui.theme.daylightColorScheme
import com.alphamessenger.ui.theme.emeraldColorScheme
import com.alphamessenger.ui.theme.greyColorScheme
import com.alphamessenger.ui.theme.lilacColorScheme
import com.alphamessenger.ui.theme.midnightColorScheme
import com.alphamessenger.ui.theme.sandColorScheme
import com.alphamessenger.ui.theme.skyColorScheme
import com.alphamessenger.ui.theme.violetColorScheme
import java.io.File
import com.alphamessenger.R
import androidx.compose.ui.res.stringResource

/**
 * Appearance screen — classic theme selector with a **live chat preview**
 * at the top and horizontal scrollable theme cards with mini-previews.
 *
 * Sections:
 *   1. Live Chat Preview (a mini chat layout with real colors)
 *   2. Horizontal theme cards (dark + light)
 *   3. UI font size
 *   4. Message text size
 *   5. Chat background (default / solid / gradients / from gallery)
 *
 * Every choice persists via [ThemeViewModel] and applies live.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ThemeSelectorScreen(
    onBack: () -> Unit,
    themeViewModel: ThemeViewModel = hiltViewModel(),
) {
    val colors = AlphaTheme.colorScheme
    val ctx = LocalContext.current

    val currentVariant by themeViewModel.themeVariant.collectAsState()
    val msgScale by themeViewModel.messageFontScale.collectAsState()
    val bubbleRadius by themeViewModel.bubbleRadius.collectAsState()
    val wallpaper by themeViewModel.chatWallpaper.collectAsState()

    val activeCustom by themeViewModel.activeCustom.collectAsState()
    val customThemes by themeViewModel.customThemes.collectAsState()
    // Resolve color scheme for the preview (animated)
    val previewScheme = remember(currentVariant, activeCustom) { activeCustom?.scheme() ?: schemeFor(currentVariant) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = copyWallpaperToInternal(ctx, uri)
            if (path != null) {
                themeViewModel.setChatWallpaper(
                    ChatWallpaper.Gallery(id = "gallery:$path", uri = "file://$path")
                )
            }
        }
    }

    // "Your background" (colour or gradient) opens over Appearance.
    var making by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    making?.let { gradient ->
        androidx.activity.compose.BackHandler { making = null }
        CustomWallpaperScreen(
            startWithGradient = gradient,
            onBack = { making = null },
            onSave = { w -> themeViewModel.saveCustomWallpaper(w); making = null },
        )
        return
    }
    // A pattern (one of Alpha's, or a kept one to adjust) opens over Appearance too.
    var patternEdit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<com.alphamessenger.ui.theme.PatternSpec?, Int>?>(null) }
    patternEdit?.let { (start, recipe) ->
        androidx.activity.compose.BackHandler { patternEdit = null }
        PatternEditorScreen(start, recipe, onBack = { patternEdit = null }, onSave = { s ->
            com.alphamessenger.ui.theme.PatternStore.save(ctx, s)
            themeViewModel.setChatWallpaper(ChatWallpaper.Pattern(s.id(), s))
            patternEdit = null
        })
        return
    }
    com.alphamessenger.ui.theme.PatternStore.load(ctx)
    val mine by themeViewModel.customWallpapers.collectAsState()

    // "Own theme" editor: (theme, is it new).
    var editingTheme by remember { androidx.compose.runtime.mutableStateOf<Pair<CustomTheme, Boolean>?>(null) }
    var deletingTheme by remember { androidx.compose.runtime.mutableStateOf<CustomTheme?>(null) }
    editingTheme?.let { (start, isNew) ->
        CustomThemeEditor(
            start = start, isNew = isNew, wallpaper = wallpaper, msgFontFactor = msgScale,
            onBack = { editingTheme = null },
            onSave = { t -> hapticTick(ctx); themeViewModel.saveCustomTheme(t); editingTheme = null },
            onDelete = if (isNew) null else ({ themeViewModel.deleteCustomTheme(start.id); editingTheme = null }),
        )
        return
    }

    deletingTheme?.let { t ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deletingTheme = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.theme_delete_q, t.name), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.theme_delete_text), color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { themeViewModel.deleteCustomTheme(t.id); deletingTheme = null }) {
                    Text(stringResource(R.string.theme_editor_delete), color = colors.danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deletingTheme = null }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) }
            },
        )
    }

    com.alphamessenger.ui.components.OptionScreen(
        title = stringResource(R.string.settings_section_appearance),
        onBack = onBack,
    ) {
        run {
            // --- 1. Live Chat Preview ---
            Spacer(Modifier.height(8.dp))
            LiveChatPreview(
                scheme = previewScheme,
                wallpaper = wallpaper,
                msgFontFactor = msgScale,
            )

            // --- Follow the device's dark/light mode ---
            val followsSystem by themeViewModel.followsSystem.collectAsState()
            Spacer(Modifier.height(16.dp))
            com.alphamessenger.ui.components.OptionGroup {
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.theme_follow_system),
                    subtitle = stringResource(R.string.theme_follow_system_hint),
                    checked = followsSystem,
                    onCheckedChange = { on ->
                        hapticTick(ctx)
                        if (on) themeViewModel.followSystem() else themeViewModel.setTheme(currentVariant)
                    },
                )
            }

            // --- The day / night pair the avatar menu switches between ---
            val (dayName, nightName) = themeViewModel.dayNight.collectAsState().value
            com.alphamessenger.ui.components.OptionSection(stringResource(R.string.theme_pair_section))
            com.alphamessenger.ui.components.OptionGroup {
                com.alphamessenger.ui.components.OptionRow(
                    title = stringResource(R.string.menu_light_theme), icon = AlphaIcons.Outlined.Sun,
                    value = dayName, chevron = false,
                )
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionRow(
                    title = stringResource(R.string.menu_dark_theme), icon = AlphaIcons.Outlined.Moon,
                    value = nightName, chevron = false,
                )
            }
            com.alphamessenger.ui.components.OptionNote(stringResource(R.string.theme_pair_hint))

            // --- Tab bar: where it sits, whether it hides on scroll, labels ---
            com.alphamessenger.ui.chatlist.TabDockPrefs.load(ctx)
            val dock = com.alphamessenger.ui.chatlist.TabDockPrefs
            com.alphamessenger.ui.components.OptionSection(stringResource(R.string.tabbar_section))
            com.alphamessenger.ui.components.OptionGroup {
                com.alphamessenger.ui.components.OptionChoiceRow(
                    title = stringResource(R.string.tabbar_bottom),
                    selected = !dock.top,
                    onClick = { hapticTick(ctx); dock.setTop(ctx, false) },
                )
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionChoiceRow(
                    title = stringResource(R.string.tabbar_top),
                    selected = dock.top,
                    onClick = { hapticTick(ctx); dock.setTop(ctx, true) },
                )
            }
            Spacer(Modifier.height(12.dp))
            com.alphamessenger.ui.components.OptionGroup {
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.tabbar_autohide),
                    subtitle = stringResource(R.string.tabbar_autohide_hint),
                    checked = dock.autoHide,
                    onCheckedChange = { dock.setAutoHide(ctx, it) },
                )
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.tabbar_labels),
                    checked = dock.labels,
                    onCheckedChange = { dock.setLabels(ctx, it) },
                )
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.list_dividers),
                    subtitle = stringResource(R.string.list_dividers_hint),
                    checked = dock.dividers,
                    onCheckedChange = { dock.setDividers(ctx, it) },
                )
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.feed.FeedStore.load(ctx)
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.feed_show_row),
                    subtitle = stringResource(R.string.feed_show_row_hint),
                    checked = !com.alphamessenger.ui.feed.FeedStore.hidden,
                    onCheckedChange = { com.alphamessenger.ui.feed.FeedStore.setHidden(ctx, !it) },
                )
                com.alphamessenger.ui.components.OptionDivider()
                var feedNotify by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.alphamessenger.ui.feed.FeedNotifier.enabled(ctx)) }
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.feed_notify),
                    subtitle = stringResource(R.string.feed_notify_hint),
                    checked = feedNotify,
                    onCheckedChange = { feedNotify = it; com.alphamessenger.ui.feed.FeedNotifier.setEnabled(ctx, it) },
                )
            }
            // --- Message menu: beside the message (frosted) or the bottom panel ---
            com.alphamessenger.ui.chat.MessageMenuPrefs.load(ctx)
            val menuPrefs = com.alphamessenger.ui.chat.MessageMenuPrefs
            com.alphamessenger.ui.components.OptionSection(stringResource(R.string.msg_menu_section))
            com.alphamessenger.ui.components.OptionGroup {
                com.alphamessenger.ui.components.OptionChoiceRow(
                    title = stringResource(R.string.msg_menu_popup),
                    selected = menuPrefs.popup,
                    onClick = { hapticTick(ctx); menuPrefs.set(ctx, true) },
                )
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionChoiceRow(
                    title = stringResource(R.string.msg_menu_sheet),
                    selected = !menuPrefs.popup,
                    onClick = { hapticTick(ctx); menuPrefs.set(ctx, false) },
                )
            }
            // --- Chats and channels: admins' looks, my own chat themes ---
            com.alphamessenger.ui.chat.look.ChatLooks.load(ctx)
            val looks = com.alphamessenger.ui.chat.look.ChatLooks
            val mineCount = remember(looks.tick) { looks.mineCount(ctx) }
            com.alphamessenger.ui.components.OptionSection(stringResource(R.string.look_settings_section))
            com.alphamessenger.ui.components.OptionGroup {
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.look_settings_admin),
                    subtitle = stringResource(R.string.look_settings_admin_hint),
                    checked = looks.showAdmin,
                    onCheckedChange = { hapticTick(ctx); looks.setShowAdmin(ctx, it) },
                )
                if (mineCount > 0) {
                    com.alphamessenger.ui.components.OptionDivider()
                    com.alphamessenger.ui.components.OptionRow(
                        title = stringResource(R.string.look_settings_mine, mineCount),
                        subtitle = stringResource(R.string.look_settings_mine_reset),
                        onClick = { hapticTick(ctx); looks.clearMine(ctx) },
                    )
                }
            }
            // --- Seasonal decorations: on by themselves in the first days of a season ---
            com.alphamessenger.ui.components.SeasonPrefs.load(ctx)
            val seasons = com.alphamessenger.ui.components.SeasonPrefs
            com.alphamessenger.ui.components.OptionSection(stringResource(R.string.season_section))
            com.alphamessenger.ui.components.OptionGroup {
                com.alphamessenger.ui.components.StarSky.load(ctx)
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.starry_toggle),
                    subtitle = stringResource(R.string.starry_toggle_hint),
                    checked = com.alphamessenger.ui.components.StarSky.enabled,
                    onCheckedChange = { hapticTick(ctx); com.alphamessenger.ui.components.StarSky.set(ctx, it) },
                )
                if (com.alphamessenger.ui.components.StarSky.enabled) {
                    DecorAreaChoice(com.alphamessenger.ui.components.StarSky.fullScreen) {
                        hapticTick(ctx); com.alphamessenger.ui.components.StarSky.setFullScreen(ctx, it)
                    }
                }
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.MatrixRain.load(ctx)
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.matrix_toggle),
                    subtitle = stringResource(R.string.matrix_toggle_hint),
                    checked = com.alphamessenger.ui.components.MatrixRain.enabled,
                    onCheckedChange = { hapticTick(ctx); com.alphamessenger.ui.components.MatrixRain.set(ctx, it) },
                )
                if (com.alphamessenger.ui.components.MatrixRain.enabled) {
                    DecorAreaChoice(com.alphamessenger.ui.components.MatrixRain.fullScreen) {
                        hapticTick(ctx); com.alphamessenger.ui.components.MatrixRain.setFullScreen(ctx, it)
                    }
                }
                com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionSwitchRow(
                    title = stringResource(R.string.season_toggle),
                    subtitle = stringResource(R.string.season_toggle_hint),
                    checked = seasons.enabled,
                    onCheckedChange = { hapticTick(ctx); seasons.set(ctx, it) },
                )
                if (seasons.enabled) {
                    DecorAreaChoice(seasons.fullScreen) { hapticTick(ctx); seasons.setFullScreen(ctx, it) }
                }
                com.alphamessenger.ui.components.OptionDivider()
                // Look at any season now (until the app restarts), whatever the date.
                Text(
                    stringResource(R.string.season_preview), color = colors.textSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        com.alphamessenger.ui.components.Season.WINTER to R.string.season_winter,
                        com.alphamessenger.ui.components.Season.SPRING to R.string.season_spring,
                        com.alphamessenger.ui.components.Season.SUMMER to R.string.season_summer,
                        com.alphamessenger.ui.components.Season.AUTUMN to R.string.season_autumn,
                    ).forEach { (s, label) ->
                        val on = seasons.preview == s
                        Box(
                            Modifier.weight(1f).height(40.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(if (on) colors.accent else colors.bgTertiary)
                                .clickable { hapticTick(ctx); seasons.preview = if (on) null else s },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(label), color = if (on) Color.White else colors.textPrimary, fontSize = 13.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
                if (seasons.preview != null) Box(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).height(84.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(colors.bgPrimary)
                        .seasonalDecor(isDarkSurface(colors.bgPrimary), always = true),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "Alpha",
                        modifier = Modifier.padding(start = 18.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFC026D3), Color(0xFFEC4899))),
                            fontFamily = com.alphamessenger.ui.theme.BricolageGrotesque,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                            fontSize = 26.sp,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            com.alphamessenger.ui.components.GlassPrefs.load(ctx)
            com.alphamessenger.ui.components.OptionGroup {
                Text(
                    stringResource(R.string.glass_panels), color = colors.textPrimary, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                )
                Text(
                    stringResource(R.string.glass_panels_hint), color = colors.textSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                )
                val glass = com.alphamessenger.ui.components.GlassPrefs
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    listOf("frost" to R.string.glass_mode_frost, "dim" to R.string.glass_mode_dim, "solid" to R.string.glass_mode_solid)
                        .forEach { (m, label) ->
                            val on = glass.mode == m
                            Box(
                                Modifier.weight(1f).height(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) colors.accent else colors.bgTertiary)
                                    .clickable { hapticTick(ctx); glass.setMode(ctx, m) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(label), color = if (on) Color.White else colors.textPrimary, fontSize = 13.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                }
            }

            Spacer(Modifier.height(12.dp))
            ChatPanelsSettings(colors)

            // --- 2. Theme cards — Dark ---
            SectionLabel(stringResource(R.string.theme_dark), colors)
            ThemeCardRow(
                themes = DARK_THEMES,
                currentVariant = currentVariant.takeIf { activeCustom == null },
                onSelect = { variant ->
                    hapticTick(ctx)
                    themeViewModel.setTheme(variant)
                },
            )

            // --- 2b. Theme cards — Light ---
            SectionLabel(stringResource(R.string.theme_light), colors)
            ThemeCardRow(
                themes = LIGHT_THEMES,
                currentVariant = currentVariant.takeIf { activeCustom == null },
                onSelect = { variant ->
                    hapticTick(ctx)
                    themeViewModel.setTheme(variant)
                },
            )

            // --- 2b'. Own themes: "+ Own" and the ones made (tap again to edit) ---
            SectionLabel(stringResource(R.string.theme_custom_section), colors)
            val firstPreset = CustomTheme.PRESETS.first()
            val firstName = presetName(firstPreset)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.width(82.dp).height(118.dp).clip(RoundedCornerShape(14.dp))
                            .border(1.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .background(colors.accent.copy(alpha = 0.08f))
                            .clickable {
                                hapticTick(ctx)
                                editingTheme = firstPreset.copy(id = CustomTheme.newId(), name = firstName) to true
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent, modifier = Modifier.size(28.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.theme_custom_new), color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                customThemes.forEach { t ->
                    val selected = activeCustom?.id == t.id
                    val scheme = remember(t) { t.scheme() }
                    ThemeMiniCard(
                        name = t.name, scheme = scheme, selected = selected, currentColors = colors,
                        onClick = {
                            hapticTick(ctx)
                            if (selected) editingTheme = t to false else themeViewModel.useCustomTheme(t.id)
                        },
                        onLongClick = { hapticTick(ctx); deletingTheme = t },
                    )
                }
            }
            if (customThemes.isNotEmpty()) Text(
                stringResource(R.string.theme_custom_hint), color = colors.textMuted, fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )

            // --- 2c. Call background: three styles, drawn as the call screen draws them ---
            SectionLabel(stringResource(R.string.call_bg_section), colors)
            com.alphamessenger.calls.ui.CallLookPrefs.load(ctx)
            SectionCard(colors) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.alphamessenger.calls.ui.CallBackdrop.entries.forEach { style ->
                        val on = com.alphamessenger.calls.ui.CallLookPrefs.backdrop == style
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))
                                    .callBackdrop(previewScheme, currentVariant.isLight, style)
                                    .border(
                                        if (on) 2.dp else 1.dp,
                                        if (on) colors.accent else colors.alphaBorder,
                                        RoundedCornerShape(16.dp),
                                    )
                                    .clickable { hapticTick(ctx); com.alphamessenger.calls.ui.CallLookPrefs.set(ctx, style) },
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Box(
                                    Modifier.padding(top = 34.dp).size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Brush.linearGradient(listOf(previewScheme.accent, previewScheme.msgOutEnd))),
                                )
                                if (on) SelectedBadge(colors)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(style.label), color = if (on) colors.textPrimary else colors.textMuted, fontSize = 12.sp)
                        }
                    }
                }
            }

            // --- 4. Message text size ---
            SectionLabel(stringResource(R.string.theme_msg_text_size), colors)
            SectionCard(colors) {
                // The slider moves a local value; the setting is written once on release.
                var scale by remember(msgScale) { mutableStateOf(msgScale) }
                var radius by remember(bubbleRadius) { mutableStateOf(bubbleRadius) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(previewScheme.bgChat)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.clip(com.alphamessenger.ui.chat.bubbleShape(outgoing = false, showTail = false, radius = radius.dp))
                            .background(previewScheme.msgIn).padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.theme_sample_in), color = previewScheme.textPrimary, fontSize = (15f * scale).sp) }
                    Box(
                        Modifier.align(Alignment.End)
                            .clip(com.alphamessenger.ui.chat.bubbleShape(outgoing = true, showTail = false, radius = radius.dp))
                            .background(Brush.linearGradient(listOf(previewScheme.msgOutStart, previewScheme.msgOutEnd)))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.theme_sample_title), color = previewScheme.msgOutText, fontSize = (15f * scale).sp) }
                }
                Spacer(Modifier.height(10.dp))
                ScaleSlider(
                    small = "A", large = "A", value = scale,
                    range = com.alphamessenger.ui.theme.MSG_FONT_MIN..com.alphamessenger.ui.theme.MSG_FONT_MAX,
                    colors = colors, onChange = { scale = it }, onDone = { themeViewModel.setMessageFontScale(scale) },
                )
                SubLabel(stringResource(R.string.theme_bubble_corners), colors)
                ScaleSlider(
                    small = "▢", large = "◯", value = radius,
                    range = com.alphamessenger.ui.theme.BUBBLE_RADIUS_MIN..com.alphamessenger.ui.theme.BUBBLE_RADIUS_MAX,
                    colors = colors, onChange = { radius = it }, onDone = { themeViewModel.setBubbleRadius(radius) },
                )
            }

            // --- 5. Chat background: a few ready ones, yours, a photo ---
            SectionLabel(stringResource(R.string.theme_chat_bg), colors)
            SectionCard(colors) {
                SubLabel(stringResource(R.string.wp_ready), colors)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WallpaperTile(
                        selected = wallpaper is ChatWallpaper.Default,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { themeViewModel.setChatWallpaper(ChatWallpaper.Default) },
                        fill = { Box(Modifier.fillMaxSize().background(colors.bgChat)) },
                        label = stringResource(R.string.theme_default),
                    )
                    com.alphamessenger.ui.theme.WALLPAPER_SHOWN.forEach { g ->
                        SwatchTile(
                            selected = (wallpaper as? ChatWallpaper.Gradient)?.id == g.id,
                            brush = Brush.linearGradient(listOf(g.start, g.end)),
                            colors = colors,
                            modifier = Modifier.weight(1f).padding(bottom = 15.dp),
                            onClick = { themeViewModel.setChatWallpaper(g) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Patterns: Alpha's open the editor; kept ones go on with a tap (tap again to adjust, hold to remove).
                SubLabel(stringResource(R.string.wp_patterns), colors)
                val onPattern = (wallpaper as? ChatWallpaper.Pattern)?.id
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    com.alphamessenger.ui.theme.PatternStore.mine.forEach { s ->
                        val id = s.id()
                        val on = onPattern == id
                        Box(
                            Modifier.size(64.dp, 54.dp).clip(RoundedCornerShape(12.dp))
                                .border(if (on) 2.dp else 1.dp, if (on) colors.accent else colors.alphaBorder, RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { if (on) patternEdit = s to 0 else themeViewModel.setChatWallpaper(ChatWallpaper.Pattern(id, s)) },
                                    onLongClick = { com.alphamessenger.ui.theme.PatternStore.delete(ctx, s) },
                                ),
                        ) {
                            com.alphamessenger.ui.theme.PatternWallpaper(s.copy(scale = s.scale * 0.5f), Modifier.fillMaxSize())
                            if (on) SelectedBadge(colors)
                        }
                    }
                    com.alphamessenger.ui.theme.PATTERN_RECIPES.forEachIndexed { i, r ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { patternEdit = null to i }) {
                            Box(Modifier.size(64.dp, 54.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, colors.alphaBorder, RoundedCornerShape(12.dp))) {
                                com.alphamessenger.ui.theme.PatternWallpaper(r.thumb, Modifier.fillMaxSize())
                            }
                            Text(r.name, color = colors.textSecondary, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SubLabel(stringResource(if (mine.isEmpty()) R.string.wp_custom_title else R.string.wp_mine), colors)
                // Mine (hold to remove), then "colour" and "gradient" to make one.
                val tiles: List<ChatWallpaper.Custom?> = mine + listOf(null, null)
                tiles.chunked(4).forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEachIndexed { i, w ->
                            val index = rowIndex * 4 + i
                            if (w != null) {
                                val on = (wallpaper as? ChatWallpaper.Custom)?.id == w.id
                                Box(
                                    Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(12.dp))
                                        .customWallpaperBackground(w.colors, w.degrees)
                                        .border(if (on) 2.dp else 1.dp, if (on) colors.accent else colors.alphaBorder, RoundedCornerShape(12.dp))
                                        .combinedClickable(
                                            onClick = { themeViewModel.setChatWallpaper(w) },
                                            onLongClick = { themeViewModel.deleteCustomWallpaper(w) },
                                        ),
                                ) { if (on) SelectedBadge(colors) }
                            } else {
                                val gradient = index == mine.size + 1
                                Column(
                                    Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, colors.textMuted.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .clickable { making = gradient },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        Modifier.size(20.dp).clip(if (gradient) RoundedCornerShape(6.dp) else androidx.compose.foundation.shape.CircleShape)
                                            .background(
                                                if (gradient) Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFF6366F1)))
                                                else Brush.sweepGradient(listOf(Color(0xFFFF4D4D), Color(0xFFFFD84D), Color(0xFF4DFF88), Color(0xFF4DD2FF), Color(0xFF8A4DFF), Color(0xFFFF4DD8), Color(0xFFFF4D4D))),
                                            ),
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(stringResource(if (gradient) R.string.wp_gradient else R.string.wp_color), color = colors.textSecondary, fontSize = 10.sp)
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp))
                        .border(
                            if (wallpaper is ChatWallpaper.Gallery) 2.dp else 1.dp,
                            if (wallpaper is ChatWallpaper.Gallery) colors.accent else colors.alphaBorder,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { galleryPicker.launch("image/*") },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AlphaIcons.Outlined.Image, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.theme_from_gallery), color = colors.textPrimary, fontSize = 14.sp)
                }
            }
        }
    }
}

// --- LIVE CHAT PREVIEW — classic mock chat at the top ---

/**
 * A beautiful miniature chat interface rendered with the colours of the
 * currently selected theme. Shows a fake conversation so the user can
 * instantly see how colours, bubbles, and wallpaper look together.
 */
@Composable
internal fun LiveChatPreview(
    scheme: AlphaColorScheme,
    wallpaper: ChatWallpaper,
    msgFontFactor: Float,
) {
    // Animate all colours for smooth transitions
    val animBgPrimary by animateColorAsState(scheme.bgPrimary, tween(400), label = "bgP")
    val animBgSecondary by animateColorAsState(scheme.bgSecondary, tween(400), label = "bgS")
    val animBgChat by animateColorAsState(scheme.bgChat, tween(400), label = "bgC")
    val animTextPrimary by animateColorAsState(scheme.textPrimary, tween(400), label = "txP")
    val animTextSecondary by animateColorAsState(scheme.textSecondary, tween(400), label = "txS")
    val animTextMuted by animateColorAsState(scheme.textMuted, tween(400), label = "txM")
    val animAccent by animateColorAsState(scheme.accent, tween(400), label = "acc")
    val animMsgOutStart by animateColorAsState(scheme.msgOutStart, tween(400), label = "moS")
    val animMsgOutEnd by animateColorAsState(scheme.msgOutEnd, tween(400), label = "moE")
    val animMsgOutText by animateColorAsState(scheme.msgOutText, tween(400), label = "moT")
    val animMsgIn by animateColorAsState(scheme.msgIn, tween(400), label = "miB")
    val animBgTertiary by animateColorAsState(scheme.bgTertiary, tween(400), label = "bgT")
    val animBorder by animateColorAsState(scheme.alphaBorder, tween(400), label = "brd")

    // Outer frame — imitates a phone screen
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, animBorder, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(animBgPrimary)
        ) {
            // --- Mock top bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(animBgSecondary)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back arrow mock
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AlphaIcons.AutoMirrored.Outlined.ArrowBack, null,
                        tint = animTextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                // Avatar
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(animAccent, animMsgOutEnd))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.theme_sample_avatar),
                        color = animMsgOutText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.theme_sample_name),
                        color = animTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.common_online),
                        color = animAccent,
                        fontSize = 11.sp,
                    )
                }
            }

            // --- Chat area with wallpaper ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                // Wallpaper layer
                when (wallpaper) {
                    is ChatWallpaper.Default -> {
                        Box(Modifier.fillMaxSize().background(animBgChat))
                    }
                    is ChatWallpaper.Solid -> {
                        Box(Modifier.fillMaxSize().background(wallpaper.color))
                    }
                    is ChatWallpaper.Gradient -> {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.linearGradient(listOf(wallpaper.start, wallpaper.end))
                            )
                        )
                    }
                    is ChatWallpaper.Custom -> {
                        Box(Modifier.fillMaxSize().customWallpaperBackground(wallpaper.colors, wallpaper.degrees))
                    }
                    is ChatWallpaper.Pattern -> com.alphamessenger.ui.theme.PatternWallpaper(
                        wallpaper.spec, Modifier.fillMaxSize(), dark = scheme.bgPrimary.luminance() < 0.5f)
                    is ChatWallpaper.Gallery -> {
                        val ctx = LocalContext.current
                        val bitmap = remember(wallpaper.uri) {
                            runCatching {
                                ctx.contentResolver.openInputStream(android.net.Uri.parse(wallpaper.uri))
                                    ?.use { input -> android.graphics.BitmapFactory.decodeStream(input) }
                                    ?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(animBgChat))
                        }
                    }
                }

                // Messages overlay
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        // Date chip
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(animBgSecondary.copy(alpha = 0.7f))
                                    .padding(horizontal = 12.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    stringResource(R.string.common_today),
                                    color = animTextSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Incoming message 1
                        MockIncomingBubble(
                            text = stringResource(R.string.theme_msg_1),
                            time = "14:22",
                            bgColor = animMsgIn,
                            textColor = animTextPrimary,
                            timeColor = animTextMuted,
                            fontFactor = msgFontFactor,
                        )

                        Spacer(Modifier.height(6.dp))

                        // Outgoing message
                        MockOutgoingBubble(
                            text = stringResource(R.string.theme_msg_2),
                            time = "14:23",
                            gradientStart = animMsgOutStart,
                            gradientEnd = animMsgOutEnd,
                            textColor = animMsgOutText,
                            timeColor = animMsgOutText.copy(alpha = 0.7f),
                            fontFactor = msgFontFactor,
                        )

                        Spacer(Modifier.height(6.dp))

                        // Incoming message 2
                        MockIncomingBubble(
                            text = stringResource(R.string.theme_msg_3),
                            time = "14:24",
                            bgColor = animMsgIn,
                            textColor = animTextPrimary,
                            timeColor = animTextMuted,
                            fontFactor = msgFontFactor,
                        )

                        Spacer(Modifier.height(6.dp))

                        // Outgoing message 2
                        MockOutgoingBubble(
                            text = stringResource(R.string.theme_msg_4),
                            time = "14:24",
                            gradientStart = animMsgOutStart,
                            gradientEnd = animMsgOutEnd,
                            textColor = animMsgOutText,
                            timeColor = animMsgOutText.copy(alpha = 0.7f),
                            fontFactor = msgFontFactor,
                        )
                    }
                }
            }

            // --- Mock input bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(animBgSecondary)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AlphaIcons.Outlined.EmojiEmotions, null,
                    tint = animTextMuted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(animBgTertiary)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(R.string.theme_input_hint),
                        color = animTextMuted,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    AlphaIcons.Outlined.AttachFile, null,
                    tint = animTextMuted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(animAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AlphaIcons.Outlined.Mic, null,
                        tint = animMsgOutText,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MockIncomingBubble(
    text: String,
    time: String,
    bgColor: Color,
    textColor: Color,
    timeColor: Color,
    fontFactor: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(bgColor)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text,
                color = textColor,
                fontSize = (13f * fontFactor).sp,
                lineHeight = (17f * fontFactor).sp,
            )
            Text(
                time,
                color = timeColor,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MockOutgoingBubble(
    text: String,
    time: String,
    gradientStart: Color,
    gradientEnd: Color,
    textColor: Color,
    timeColor: Color,
    fontFactor: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(Brush.linearGradient(listOf(gradientStart, gradientEnd)))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text,
                color = textColor,
                fontSize = (13f * fontFactor).sp,
                lineHeight = (17f * fontFactor).sp,
            )
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    time,
                    color = timeColor,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.width(3.dp))
                // Double check (read indicator)
                Icon(
                    AlphaIcons.Filled.Check, null,
                    tint = timeColor,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

// --- HORIZONTAL THEME CARDS — mini-preview for each theme ---

@Composable
private fun ThemeCardRow(
    themes: List<ThemeChip>,
    /** null while an own theme is on (no built-in card is selected). */
    currentVariant: AlphaThemeVariant?,
    onSelect: (AlphaThemeVariant) -> Unit,
) {
    val colors = AlphaTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        themes.forEach { theme ->
            val selected = theme.variant == currentVariant
            val scheme = remember(theme.variant) { schemeFor(theme.variant) }
            ThemeMiniCard(
                name = theme.name,
                scheme = scheme,
                selected = selected,
                currentColors = colors,
                onClick = { onSelect(theme.variant) },
            )
        }
    }
}

/**
 * A compact card (80×120dp) that shows a tiny preview of how the theme looks:
 * mini bubbles, accent strip, bg colour. Selected card has an accent border
 * and a check badge.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ThemeMiniCard(
    name: String,
    scheme: AlphaColorScheme,
    selected: Boolean,
    currentColors: AlphaColorScheme,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 0.5.dp,
        animationSpec = tween(200),
        label = "border",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) currentColors.accent else currentColors.alphaBorder,
        animationSpec = tween(200),
        label = "borderC",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(82.dp)
                .height(118.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            // Card content — mini theme preview
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.bgPrimary)
            ) {
                // Mini top bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(scheme.bgSecondary)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(scheme.accent)
                        )
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .width(24.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.textPrimary.copy(alpha = 0.5f))
                        )
                    }
                }

                // Mini chat area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(scheme.bgChat)
                        .padding(horizontal = 5.dp, vertical = 5.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        // Mini incoming bubble
                        Box(
                            Modifier
                                .width(38.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(scheme.msgIn)
                        )
                        // Mini outgoing bubble
                        Box(
                            modifier = Modifier
                                .width(42.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(scheme.msgOutStart, scheme.msgOutEnd)
                                    )
                                )
                                .align(Alignment.End),
                        )
                        // Mini incoming 2
                        Box(
                            Modifier
                                .width(32.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(scheme.msgIn)
                        )
                        // Mini outgoing 2
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(scheme.msgOutStart, scheme.msgOutEnd)
                                    )
                                )
                                .align(Alignment.End),
                        )
                    }
                }

                // Mini input bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .background(scheme.bgSecondary)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(7.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(scheme.bgTertiary)
                        )
                        Spacer(Modifier.width(3.dp))
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(scheme.accent)
                        )
                    }
                }
            }

            // Check badge
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(currentColors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AlphaIcons.Filled.Check, null,
                        tint = currentColors.badgeText,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            name,
            color = if (selected) currentColors.accent else currentColors.textSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(82.dp),
        )
    }
}

// --- Theme catalogue ---

private data class ThemeChip(
    val variant: AlphaThemeVariant,
    val name: String,
    val accent: Color,
    val bg: Color,
)

private val DARK_THEMES = listOf(
    ThemeChip(AlphaThemeVariant.GREY, "Grey", greyColorScheme().accent, greyColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.MIDNIGHT, "Midnight", midnightColorScheme().accent, midnightColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.CARBON, "Carbon", carbonColorScheme().accent, carbonColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.AMOLED, "Black", amoledColorScheme().accent, amoledColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.VIOLET, "Violet", violetColorScheme().accent, violetColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.COSMOS, "Cosmos", cosmosColorScheme().accent, cosmosColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.EMERALD, "Emerald", emeraldColorScheme().accent, emeraldColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.AZURE, "Azure", azureColorScheme().accent, azureColorScheme().bgSecondary),
)

private val LIGHT_THEMES = listOf(
    ThemeChip(AlphaThemeVariant.DAYLIGHT, "Daylight", daylightColorScheme().accent, daylightColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.SAND, "Sand", sandColorScheme().accent, sandColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.SKY, "Sky", skyColorScheme().accent, skyColorScheme().bgSecondary),
    ThemeChip(AlphaThemeVariant.LILAC, "Lilac", lilacColorScheme().accent, lilacColorScheme().bgSecondary),
)

// --- Helper: resolve AlphaColorScheme from variant ---

private fun schemeFor(variant: AlphaThemeVariant): AlphaColorScheme = when (variant) {
    AlphaThemeVariant.GREY     -> greyColorScheme()
    AlphaThemeVariant.MIDNIGHT -> midnightColorScheme()
    AlphaThemeVariant.CARBON   -> carbonColorScheme()
    AlphaThemeVariant.AMOLED   -> amoledColorScheme()
    AlphaThemeVariant.VIOLET   -> violetColorScheme()
    AlphaThemeVariant.COSMOS   -> cosmosColorScheme()
    AlphaThemeVariant.EMERALD  -> emeraldColorScheme()
    AlphaThemeVariant.AZURE    -> azureColorScheme()
    AlphaThemeVariant.DAYLIGHT -> daylightColorScheme()
    AlphaThemeVariant.SAND     -> sandColorScheme()
    AlphaThemeVariant.SKY      -> skyColorScheme()
    AlphaThemeVariant.LILAC    -> lilacColorScheme()
}

// --- Reusable building blocks ---

@Composable
private fun SectionLabel(text: String, @Suppress("UNUSED_PARAMETER") colors: AlphaColorScheme) {
    com.alphamessenger.ui.components.OptionSection(text)
}

@Composable
private fun SubLabel(text: String, colors: AlphaColorScheme) {
    Text(
        text,
        color = colors.textMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SectionCard(colors: AlphaColorScheme, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.bgSecondary)
            .padding(16.dp),
    ) { content() }
}

/** A slider between a small and a large mark; [onDone] once the finger lifts. */
@Composable
private fun ScaleSlider(
    small: String,
    large: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: AlphaColorScheme,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(small, color = colors.textMuted, fontSize = 13.sp, modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.bgTertiary,
            ),
        )
        Text(large, color = colors.textMuted, fontSize = 20.sp, modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun WallpaperTile(
    selected: Boolean,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    fill: @Composable () -> Unit,
    label: String,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else colors.alphaBorder,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onClick),
        ) {
            fill()
            if (selected) SelectedBadge(colors)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = colors.textMuted, fontSize = 11.sp)
    }
}

@Composable
private fun SwatchTile(
    selected: Boolean,
    brush: Brush,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(brush)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.accent else colors.alphaBorder,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        if (selected) SelectedBadge(colors)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SelectedBadge(colors: AlphaColorScheme) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AlphaIcons.Filled.Check, null, tint = colors.badgeText, modifier = Modifier.size(13.dp))
    }
}

// --- Haptic feedback helper ---

private fun hapticTick(ctx: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        }
    } catch (_: Exception) {
        // Silently ignore — some emulators lack vibration
    }
}

/**
 * Copies a gallery-picked image into app-private storage so the wallpaper
 * survives reboots and revoked content permissions. Returns the absolute
 * file path, or null on failure.
 */
private fun copyWallpaperToInternal(ctx: Context, uri: Uri): String? = runCatching {
    val dir = File(ctx.filesDir, "wallpaper").apply { mkdirs() }
    val out = File(dir, "chat_wallpaper_${System.currentTimeMillis()}.jpg")
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    }
    out.absolutePath
}.getOrNull()

/** Where a decoration lives: the header only, or the whole main screen. */
@Composable
private fun DecorAreaChoice(fullScreen: Boolean, onChange: (Boolean) -> Unit) {
    val cs = AlphaTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        listOf(false to R.string.decor_area_header, true to R.string.decor_area_screen).forEach { (full, label) ->
            val on = full == fullScreen
            Box(
                Modifier.weight(1f).height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) cs.accent else cs.bgTertiary)
                    .clickable { if (!on) onChange(full) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), color = if (on) Color.White else cs.textPrimary, fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
