package com.alphamessenger.ui.browser

import android.widget.FrameLayout
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alphamessenger.R
import com.alphamessenger.ui.components.AlphaSheet
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserScreen(host: BrowserActivity, stage: FrameLayout) {
    val cs = AlphaTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val tab = host.current
    val keyboard = WindowInsets.isImeVisible

    Box(Modifier.fillMaxSize().background(cs.bgPrimary)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (host.findQuery != null) FindBar(host) else TopBar(host, tab)
            AndroidView(factory = { stage }, modifier = Modifier.fillMaxWidth().weight(1f))
            if (!keyboard) BottomBar(host, tab, onMenu = { menu = true })
        }
        host.reader?.let { (title, parts) -> Reader(title, parts) { host.reader = null } }
        if (host.showTabs) TabSwitcher(host)
    }
    if (menu && tab != null) MenuSheet(host, tab) { menu = false }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TopBar(host: BrowserActivity, tab: BrowserTab?) {
    val cs = AlphaTheme.colorScheme
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var editing by remember(tab?.id) { mutableStateOf(false) }
    var field by remember(tab?.id) { mutableStateOf(TextFieldValue("")) }
    var hadFocus by remember(tab?.id) { mutableStateOf(false) }
    val url = tab?.url.orEmpty()
    val secure = url.startsWith("https://")

    Column(Modifier.fillMaxWidth().background(cs.bgSecondary).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(AlphaIcons.Outlined.KeyboardArrowDown, stringResource(R.string.browser_fold)) { host.fold() }
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(cs.bgTertiary)
                    .combinedClickable(
                        enabled = !editing,
                        onLongClick = { host.copyLink() },
                        onClick = { hadFocus = false; editing = true; field = TextFieldValue(url, TextRange(0, url.length)) },
                    )
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editing || url.isEmpty()) {
                    BasicTextField(
                        value = field, onValueChange = { field = it },
                        singleLine = true,
                        textStyle = TextStyle(color = cs.textPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(cs.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { host.load(field.text); editing = false; focus.clearFocus() }),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                if (field.text.isEmpty()) Text(stringResource(R.string.browser_address_hint), color = cs.textMuted, fontSize = 15.sp)
                                inner()
                            }
                        },
                        // The first report comes before the focus does: only a real loss ends editing.
                        modifier = Modifier.weight(1f).focusRequester(requester)
                            .onFocusChanged {
                                if (it.isFocused) hadFocus = true
                                else if (hadFocus && url.isNotEmpty()) { editing = false; hadFocus = false }
                            },
                    )
                    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
                    if (field.text.isNotEmpty()) Icon(
                        AlphaIcons.Outlined.ContentCopy, stringResource(R.string.browser_copy), tint = cs.textMuted,
                        modifier = Modifier.size(32.dp).clip(CircleShape).clickable {
                            val sel = field.selection
                            val text = if (sel.collapsed) field.text else field.text.substring(sel.min, sel.max)
                            host.copyText(text)
                        }.padding(7.dp),
                    )
                } else {
                    Icon(
                        if (secure) AlphaIcons.Outlined.Lock else AlphaIcons.Outlined.Public, null,
                        tint = if (secure) Color(0xFF34C759) else cs.textMuted, modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        hostOf(url), color = cs.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (host.viaTor) Text("Tor", color = cs.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                    Icon(
                        if (tab?.loading == true) AlphaIcons.Outlined.Close else AlphaIcons.Outlined.Refresh,
                        stringResource(if (tab?.loading == true) R.string.browser_stop else R.string.browser_reload),
                        tint = cs.textMuted,
                        modifier = Modifier.padding(start = 6.dp).size(30.dp).clip(CircleShape).clickable { host.reloadOrStop() }.padding(6.dp),
                    )
                }
            }
            BarButton(AlphaIcons.Outlined.Close, stringResource(R.string.browser_close)) { host.finish() }
        }
        // The page's progress, a thin line under the bar.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (tab?.loading == true) {
                Box(Modifier.fillMaxWidth(tab.progress.coerceIn(0.05f, 1f)).fillMaxHeight().background(cs.accent))
            }
        }
    }
}

@Composable
private fun BottomBar(host: BrowserActivity, tab: BrowserTab?, onMenu: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.bgSecondary).navigationBarsPadding().height(58.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        BarButton(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.browser_back), enabled = tab?.canBack == true) { host.back() }
        BarButton(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.browser_forward), enabled = tab?.canForward == true, mirror = true) { host.forward() }
        Box(
            Modifier.width(56.dp).height(42.dp).clip(RoundedCornerShape(21.dp)).background(cs.accent).clickable { host.sendToChat() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.browser_send_to_chat), tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Box(
            Modifier.size(46.dp).clip(CircleShape).clickable { host.openSwitcher() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(22.dp).border(2.dp, cs.textPrimary, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // No font padding: the digit sits in the middle of the box, not below it.
                Text(
                    host.tabs.size.toString(), color = cs.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    style = TextStyle(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 11.sp,
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                            androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
        BarButton(AlphaIcons.Outlined.MoreVert, stringResource(R.string.browser_menu), onClick = onMenu)
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, enabled: Boolean = true, mirror: Boolean = false, onClick: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Box(
        Modifier.size(46.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label, tint = if (enabled) cs.textPrimary else cs.textMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(22.dp).then(if (mirror) Modifier.scale(-1f, 1f) else Modifier),
        )
    }
}

@Composable
private fun TabSwitcher(host: BrowserActivity) {
    val cs = AlphaTheme.colorScheme
    var clearing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(cs.bgPrimary).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.browser_tabs_title, host.tabs.size), color = cs.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { host.showTabs = false }) { Text(stringResource(R.string.browser_done), color = cs.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(host.tabs, key = { _, t -> t.id }) { i, t ->
                val isActive = i == host.active
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(cs.bgSecondary)
                        .border(2.dp, if (isActive) cs.accent else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable { host.show(i) },
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        t.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))) }
                            ?: Icon(AlphaIcons.Outlined.Public, null, tint = cs.textMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.title.ifBlank { hostOf(t.url).ifBlank { stringResource(R.string.browser_new_tab) } },
                            color = cs.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Icon(
                            AlphaIcons.Outlined.Close, stringResource(R.string.browser_close_tab), tint = cs.textMuted,
                            modifier = Modifier.size(34.dp).clip(CircleShape).clickable { host.closeTab(i) }.padding(9.dp),
                        )
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(cs.bgTertiary)) {
                        t.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.browser_local_note), color = cs.textMuted, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { clearing = true }) { Text(stringResource(R.string.browser_clear_all), color = cs.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.width(56.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(cs.accent).clickable { host.newTab() },
                contentAlignment = Alignment.Center,
            ) { Icon(AlphaIcons.Outlined.Add, stringResource(R.string.browser_new_tab), tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
    }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        containerColor = cs.bgSecondary,
        title = { Text(stringResource(R.string.browser_clear_q), color = cs.textPrimary) },
        text = { Text(stringResource(R.string.browser_clear_text), color = cs.textSecondary) },
        confirmButton = { TextButton(onClick = { clearing = false; host.clearAll() }) { Text(stringResource(R.string.browser_clear_all), color = cs.danger) } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.common_cancel), color = cs.textMuted) } },
    )
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun MenuSheet(host: BrowserActivity, tab: BrowserTab, onDismiss: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    AlphaSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(cs.bgTertiary), contentAlignment = Alignment.Center) {
                tab.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(24.dp)) }
                    ?: Icon(AlphaIcons.Outlined.Public, null, tint = cs.textMuted, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tab.title.ifBlank { hostOf(tab.url) }, color = cs.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    hostOf(tab.url) + " · " + stringResource(if (tab.url.startsWith("https://")) R.string.browser_secure else R.string.browser_not_secure) +
                        (if (host.viaTor) " · Tor" else ""),
                    color = cs.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(AlphaIcons.AutoMirrored.Outlined.Send, stringResource(R.string.browser_send_to_chat), Modifier.weight(1f)) { onDismiss(); host.sendToChat() }
            QuickAction(AlphaIcons.Outlined.ContentCopy, stringResource(R.string.browser_copy), Modifier.weight(1f)) { onDismiss(); host.copyLink() }
            QuickAction(AlphaIcons.Outlined.Share, stringResource(R.string.browser_share), Modifier.weight(1f)) { onDismiss(); host.share() }
            QuickAction(AlphaIcons.Outlined.OpenInNew, stringResource(R.string.browser_outside), Modifier.weight(1f)) { onDismiss(); host.openOutside() }
        }
        Box(Modifier.padding(horizontal = 14.dp)) {
            OptionGroup {
                OptionRow(title = stringResource(R.string.browser_new_tab), icon = AlphaIcons.Outlined.Add, chevron = false, onClick = { onDismiss(); host.newTab() })
                OptionDivider()
                OptionRow(title = stringResource(R.string.browser_find), icon = AlphaIcons.Outlined.Search, chevron = false, onClick = { onDismiss(); host.find("") })
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.browser_reader), subtitle = stringResource(R.string.browser_reader_hint),
                    icon = AlphaIcons.Outlined.AutoStories, chevron = false, onClick = { onDismiss(); host.openReader() },
                )
                OptionDivider()
                OptionSwitchRow(
                    stringResource(R.string.browser_adblock),
                    if (host.adBlock && tab.blocked > 0) stringResource(R.string.browser_adblock_count, tab.blocked) else null,
                    host.adBlock, { host.setAdBlocking(it) }, icon = AlphaIcons.Outlined.Shield,
                )
                OptionDivider()
                OptionSwitchRow(stringResource(R.string.browser_desktop), null, host.desktop, { host.setDesktopMode(it) }, icon = AlphaIcons.Outlined.Language)
                OptionDivider()
                OptionSwitchRow(
                    stringResource(R.string.browser_links_inside), stringResource(R.string.browser_links_inside_hint),
                    host.linksInside, { host.setOpenLinksInside(it) }, icon = AlphaIcons.Outlined.Link,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(16.dp)).background(cs.bgSecondary).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = cs.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, color = cs.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FindBar(host: BrowserActivity) {
    val cs = AlphaTheme.colorScheme
    val requester = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(cs.bgSecondary).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text, onValueChange = { text = it; host.find(it) },
                singleLine = true,
                textStyle = TextStyle(color = cs.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(cs.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { host.findNext(true) }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(stringResource(R.string.browser_find_hint), color = cs.textMuted, fontSize = 16.sp)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(requester),
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
            if (text.isNotEmpty()) Text(
                if (host.findCount == 0) "0/0" else "${host.findIndex + 1}/${host.findCount}",
                color = cs.textMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp),
            )
            BarButton(AlphaIcons.Outlined.KeyboardArrowUp, stringResource(R.string.browser_find_prev), enabled = host.findCount > 0) { host.findNext(false) }
            BarButton(AlphaIcons.Outlined.KeyboardArrowDown, stringResource(R.string.browser_find_next), enabled = host.findCount > 0) { host.findNext(true) }
            BarButton(AlphaIcons.Outlined.Close, stringResource(R.string.browser_close)) { host.closeFind() }
        }
    }
}

/** The page's text alone, in the app's own type and colours. */
@Composable
private fun Reader(title: String, parts: List<String>, onClose: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.bgPrimary).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(AlphaIcons.Outlined.Close, stringResource(R.string.browser_close)) { onClose() }
            Text(stringResource(R.string.browser_reader), color = cs.textMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text(title, color = cs.textPrimary, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold) }
            items(parts.size) { i ->
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(parts[i], color = cs.textPrimary, fontSize = 17.sp, lineHeight = 26.sp)
                }
            }
        }
    }
}

internal fun hostOf(url: String): String =
    runCatching { android.net.Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
