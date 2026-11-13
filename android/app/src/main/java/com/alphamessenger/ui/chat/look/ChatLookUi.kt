package com.alphamessenger.ui.chat.look

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.ChatWallpaper
import com.alphamessenger.ui.theme.ChatWallpaperBackground
import com.alphamessenger.ui.theme.CustomTheme
import com.alphamessenger.ui.theme.LocalAlphaColorScheme
import com.alphamessenger.ui.theme.LocalChatWallpaper
import com.alphamessenger.ui.theme.SystemBars
import com.alphamessenger.ui.theme.ThemeViewModel
import com.alphamessenger.ui.theme.WALLPAPER_SHOWN
import com.alphamessenger.ui.theme.WALLPAPER_SOLID_PRESETS
import com.alphamessenger.ui.theme.persistId
import com.alphamessenger.ui.theme.readableOn
import com.alphamessenger.ui.theme.toMaterial3
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** The app's own scheme and background, under any chat's look (previews of "as everywhere"). */
private val LocalAppScheme = staticCompositionLocalOf<AlphaColorScheme?> { null }
private val LocalAppWallpaper = staticCompositionLocalOf<ChatWallpaper?> { null }

/**
 * Wraps a chat: puts on the look it has (mine for this chat, else its admins',
 * else nothing) — colours, background, system bar icons — and hosts "Chat
 * theme" when the chat's menu asks for it. Re-reads when the chat's metadata
 * arrives (a new admins' look) or anything about looks changes.
 */
@Composable
fun ChatLookScope(chatId: String, repo: AlphaCoreRepository, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    ChatLooks.load(ctx)
    val tick = ChatLooks.tick
    var metaTick by remember { mutableStateOf(0) }
    LaunchedEffect(chatId) { repo.chatMetadataUpdated.filter { it == chatId }.collect { metaTick++ } }
    val mine = remember(chatId, tick) { ChatLooks.mine(ctx, chatId) }
    val admins by produceState<ChatLook?>(null, chatId, metaTick, tick) { value = ChatLooks.admins(ctx, repo, chatId) }
    val effective = when {
        mine?.off == true -> null
        mine != null && !mine.isEmpty -> mine
        ChatLooks.showAdmin -> admins?.takeIf { !it.isEmpty }
        else -> null
    }
    val appScheme = LocalAlphaColorScheme.current
    val appWallpaper = LocalChatWallpaper.current
    val scheme = remember(effective?.theme) { effective?.theme?.scheme() } ?: appScheme
    val wallpaper = effective?.wallpaper() ?: appWallpaper

    // The status bar's icons follow the chat's own light/dark while it is open.
    val light = effective?.theme?.light
    DisposableEffect(light) {
        if (light != null) SystemBars.lightIcons = light
        onDispose { if (light != null) SystemBars.lightIcons = null }
    }

    val blobFetcher: suspend (String) -> String? = remember(repo) { { ref -> repo.downloadMediaToCache(ref) } }
    CompositionLocalProvider(
        com.alphamessenger.ui.chat.preview.LocalBlobFetcher provides blobFetcher,
        LocalAppScheme provides appScheme,
        LocalAppWallpaper provides appWallpaper,
        LocalAlphaColorScheme provides scheme,
        LocalChatWallpaper provides wallpaper,
    ) {
        MaterialTheme(colorScheme = scheme.toMaterial3().let { if (effective?.theme != null) it.copy(onPrimary = readableOn(scheme.accent)) else it }) {
            Box(Modifier.fillMaxSize()) {
                content()
                // First time in a chat dressed by its admins: say so, offer to switch it off here.
                val fromAdmins = effective != null && effective === admins
                if (fromAdmins && !ChatLooks.noticeSeen(ctx, chatId)) AdminsNotice(
                    onOff = { ChatLooks.markNoticeSeen(ctx, chatId); ChatLooks.setMine(ctx, chatId, ChatLook(off = true)) },
                    onSeen = { ChatLooks.markNoticeSeen(ctx, chatId) },
                )
            }
            if (ChatLooks.editMine) ChatLookDialog(
                title = stringResource(R.string.look_title_mine),
                note = stringResource(R.string.look_note_mine),
                initial = mine?.takeIf { !it.off } ?: ChatLook(),
                applyLabel = stringResource(R.string.look_done),
                onDismiss = { ChatLooks.editMine = false },
                onApply = { look -> ChatLooks.setMine(ctx, chatId, look.takeIf { !it.isEmpty }); ChatLooks.editMine = false },
                photoPrefix = "m_${chatId.take(12)}",
            )
        }
    }
}

@Composable
private fun AdminsNotice(onOff: () -> Unit, onSeen: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(7000); if (shown) { shown = false; onSeen() } }
    if (!shown) return
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 72.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(cs.bgSecondary.copy(alpha = 0.94f))
                .border(0.5.dp, cs.alphaBorder, RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AlphaIcons.Outlined.Palette, null, tint = cs.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.look_notice), color = cs.textPrimary, fontSize = 13.sp)
            TextButton(onClick = { shown = false; onOff() }) { Text(stringResource(R.string.look_notice_off), color = cs.accent, fontSize = 13.sp) }
        }
    }
}

/** "Chat theme" / "Look" in its own full window (over a chat). */
@Composable
fun ChatLookDialog(
    title: String,
    note: String,
    initial: ChatLook,
    applyLabel: String,
    onDismiss: () -> Unit,
    onApply: suspend (ChatLook) -> Unit,
    photoPrefix: String,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val bg = AlphaTheme.colorScheme.bgPrimary
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = bg.luminanceIsLight())
        Box(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.statusBars)) {
            ChatLookEditor(title, note, initial, applyLabel, onDismiss, onApply, photoPrefix)
        }
    }
}

private fun Color.luminanceIsLight() = this.luminance() >= 0.5f

/**
 * Pick a theme and a background for one chat, with the chat preview live on
 * top. Used for "mine" (over the chat) and for the admins' look (group settings).
 */
@Composable
fun ChatLookEditor(
    title: String,
    note: String,
    initial: ChatLook,
    applyLabel: String,
    onBack: () -> Unit,
    onApply: suspend (ChatLook) -> Unit,
    photoPrefix: String,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AlphaTheme.colorScheme
    val appScheme = LocalAppScheme.current ?: colors
    val appWallpaper = LocalAppWallpaper.current ?: LocalChatWallpaper.current
    val themeVm: ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val myThemes by themeVm.customThemes.collectAsState()
    val myBackgrounds by themeVm.customWallpapers.collectAsState()
    var look by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            ChatLooks.importPhoto(ctx, uri, photoPrefix)?.let { look = look.copy(photoPath = it, photoRef = null, photoHash = null, wallpaperId = null) }
            busy = false
        }
    }
    val scheme = remember(look.theme, appScheme) { look.theme?.scheme() ?: appScheme }
    val wallpaper = look.wallpaper() ?: appWallpaper

    OptionScreen(title = title, onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        com.alphamessenger.ui.settings.LiveChatPreview(scheme = scheme, wallpaper = wallpaper, msgFontFactor = 1f)

        OptionSection(stringResource(R.string.look_section_theme))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeTile(stringResource(R.string.look_none), appScheme, look.theme == null) { look = look.copy(theme = null) }
            (CustomTheme.PRESETS + myThemes).forEach { t ->
                val s = remember(t) { t.scheme() }
                val name = if (t.id.startsWith("p-")) com.alphamessenger.ui.settings.presetName(t) else t.name
                ThemeTile(name, s, look.theme?.encode() == t.encode()) { look = look.copy(theme = t) }
            }
        }

        OptionSection(stringResource(R.string.look_section_bg))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BgTile(stringResource(R.string.look_none), null, look.wallpaperId == null && look.photoPath == null) {
                look = look.copy(wallpaperId = null, photoPath = null, photoRef = null, photoHash = null)
            }
            // A photo: the chosen one shown, or "pick".
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(12.dp)).background(colors.bgTertiary)
                        .border(2.dp, if (look.photoPath != null) colors.accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy) { pick.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    val photo = look.photoPath
                    if (photo != null) ChatWallpaperBackground(ChatWallpaper.Gallery("look:$photo", "file://$photo"), Modifier.fillMaxSize())
                    if (busy) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    else if (photo == null) Icon(AlphaIcons.Outlined.Image, null, tint = colors.textSecondary)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.look_photo), color = colors.textSecondary, fontSize = 11.sp)
            }
            (WALLPAPER_SOLID_PRESETS + WALLPAPER_SHOWN + myBackgrounds).forEach { w ->
                BgTile(null, w, look.photoPath == null && look.wallpaperId == w.persistId()) {
                    look = look.copy(wallpaperId = w.persistId(), photoPath = null, photoRef = null, photoHash = null)
                }
            }
        }
        OptionNote(note)

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(colors.accent)
                .clickable(enabled = !busy) {
                    scope.launch { busy = true; onApply(look); busy = false }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(color = readableOn(colors.accent), strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else Text(applyLabel, color = readableOn(colors.accent), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/** A theme as a tiny chat: its background, a bar, two bubbles. */
@Composable
private fun ThemeTile(name: String, s: AlphaColorScheme, on: Boolean, onClick: () -> Unit) {
    val colors = AlphaTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(
            Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(12.dp)).background(s.bgPrimary)
                .border(2.dp, if (on) colors.accent else colors.alphaBorder, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        ) {
            Box(Modifier.fillMaxWidth().height(14.dp).background(s.bgSecondary))
            Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(30.dp, 10.dp).clip(RoundedCornerShape(5.dp)).background(s.msgIn))
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 14.dp).size(34.dp, 11.dp).clip(RoundedCornerShape(6.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(s.msgOutStart, s.msgOutEnd))),
            )
            if (on) Icon(AlphaIcons.Filled.Check, null, tint = colors.accent, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(name, color = if (on) colors.accent else colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BgTile(name: String?, w: ChatWallpaper?, on: Boolean, onClick: () -> Unit) {
    val colors = AlphaTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(12.dp)).background(colors.bgTertiary)
                .border(2.dp, if (on) colors.accent else colors.alphaBorder, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        ) {
            if (w != null) ChatWallpaperBackground(w, Modifier.fillMaxSize())
            if (on) Icon(AlphaIcons.Filled.Check, null, tint = colors.accent, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(name ?: " ", color = if (on) colors.accent else colors.textSecondary, fontSize = 11.sp, maxLines = 1)
    }
}
