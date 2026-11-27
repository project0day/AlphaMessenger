package com.alphamessenger.ui.chatlist

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.alphamessenger.ui.theme.bgBottom
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.components.glassPanel

/*
 * The floating tab dock: tabs in one rounded capsule (like the message field)
 * with the screen's main action as a round button beside it. Everything under
 * it stays visible; scrolling down hides it, scrolling up brings it back.
 * Bottom (default) or top, auto-hide and labels are settings.
 */

/** Tab dock settings (Appearance → Tab bar). */
object TabDockPrefs {
    private const val PREFS = "ui_prefs"
    var top by mutableStateOf(false)
        private set
    var autoHide by mutableStateOf(true)
        private set
    var labels by mutableStateOf(true)
        private set
    /** Thin lines between the rows of the lists (off: a cleaner, airier list). */
    var dividers by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        top = p.getBoolean("dock_top", false)
        autoHide = p.getBoolean("dock_autohide", true)
        labels = p.getBoolean("dock_labels", true)
        dividers = p.getBoolean("list_dividers", false)
    }

    fun setTop(ctx: Context, v: Boolean) { top = v; save(ctx, "dock_top", v) }
    fun setAutoHide(ctx: Context, v: Boolean) { autoHide = v; save(ctx, "dock_autohide", v) }
    fun setLabels(ctx: Context, v: Boolean) { labels = v; save(ctx, "dock_labels", v) }
    fun setDividers(ctx: Context, v: Boolean) { dividers = v; save(ctx, "list_dividers", v) }

    private fun save(ctx: Context, key: String, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
}

/** Room the lists leave at their start: they run under the frosted header. */
val LocalListTopInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** The list under the bottom dock, blurred behind its capsule. */
val LocalDockHaze = androidx.compose.runtime.compositionLocalOf<com.alphamessenger.ui.components.GlassState?> { null }

/** Room the lists leave at their end so the last row is not under the dock. */
val LocalDockInset = compositionLocalOf { 0.dp }

class DockTab(
    val key: ChatListViewModel.BottomTab,
    val icon: ImageVector,
    val label: String,
    val badge: Int = 0,
)

/** What the round button does on the current tab. */
class DockAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Height the bottom dock takes, system navigation bar included. */
@Composable
fun dockInset(): Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return if (TabDockPrefs.top) nav + 88.dp else nav + (if (TabDockPrefs.labels) 64.dp else 56.dp) + 26.dp
}

/**
 * Where the swipeable tab pages are right now: page index plus the fraction
 * of the swipe (2.4 = 40% of the way from the 3rd tab to the 4th). The dock
 * highlights from it frame by frame, so it moves WITH the finger instead of
 * switching once the swipe settles. null = no pager: follow the selected tab.
 */
val LocalTabPosition = androidx.compose.runtime.compositionLocalOf<(() -> Float)?> { null }

@Composable
fun TabPill(
    tabs: List<DockTab>,
    selected: ChatListViewModel.BottomTab,
    onSelect: (ChatListViewModel.BottomTab) -> Unit,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val labels = TabDockPrefs.labels
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier

            .clip(shape)
            .then(
                LocalDockHaze.current?.let { haze ->
                    Modifier.glassPanel(haze, shape, tint = colors.bgSecondary.copy(alpha = 0.55f), backdrop = colors.bgPrimary, blur = 22.dp)
                } ?: Modifier.background(colors.bgSecondary.copy(alpha = 0.5f))
            )
            .border(1.dp, colors.alphaBorder, shape)
            .height(if (labels) 64.dp else 56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val position = LocalTabPosition.current
        tabs.forEach { t ->
            val on = t.key == selected
            val animBg by animateColorAsState(if (on) colors.accent.copy(alpha = 0.15f) else Color.Transparent, label = "tabBg")
            val animFg by animateColorAsState(if (on) colors.accent else colors.textMuted, label = "tabFg")
            // With the pages: 1 on this tab, falling to 0 one page away.
            val near = position?.let { (1f - kotlin.math.abs(it() - t.key.ordinal)).coerceIn(0f, 1f) }
            val bg = if (near != null) colors.accent.copy(alpha = 0.15f * near) else animBg
            val fg = if (near != null) androidx.compose.ui.graphics.lerp(colors.textMuted, colors.accent, near) else animFg
            Column(
                Modifier
                    .weight(1f)
                    .height(if (labels) 52.dp else 44.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(bg)
                    .clickable {
                        if (!on) com.alphamessenger.util.Haptics.tick(ctx)
                        onSelect(t.key)
                    }
                    .semantics { role = Role.Tab; this.selected = on; contentDescription = t.label },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    Icon(t.icon, null, tint = fg, modifier = Modifier.size(24.dp))
                    if (t.badge > 0) {
                        val txt = if (t.badge > 99) "99+" else t.badge.toString()
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp, y = (-1).dp)
                                .heightIn(min = 16.dp)
                                .clip(CircleShape)
                                .background(colors.accent)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(txt, color = colors.bgPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
                        }
                    }
                }
                if (labels) Text(t.label, color = fg, fontSize = 10.5.sp, maxLines = 1, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
fun DockFab(action: DockAction, colors: AlphaColorScheme, modifier: Modifier = Modifier) {
    Box(
        modifier

            .size(60.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(onClick = action.onClick)
            .semantics { role = Role.Button; contentDescription = action.label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(action.icon, null, tint = colors.bgPrimary, modifier = Modifier.size(26.dp))
    }
}

/** The bottom dock: capsule + round button; leaves downward, comes back with a pop. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BottomTabDock(
    visible: Boolean,
    tabs: List<DockTab>,
    selected: ChatListViewModel.BottomTab,
    onSelect: (ChatListViewModel.BottomTab) -> Unit,
    action: DockAction?,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
) {
    // The dock's haze reaches under the navigation buttons: no scrim over it.
    if (visible) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            val owner = Any()
            com.alphamessenger.ui.theme.SystemBars.requestNav(owner, androidx.compose.ui.graphics.Color.Transparent)
            onDispose { com.alphamessenger.ui.theme.SystemBars.releaseNav(owner) }
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                // A haze under the dock and the navigation buttons: the list fades out, no band.
                .drawBehind {
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to colors.bgBottom.copy(alpha = 0f), 0.55f to colors.bgBottom.copy(alpha = 0.6f), 1f to colors.bgBottom.copy(alpha = 0.9f)))
                }
                .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TabPill(tabs, selected, onSelect, colors, Modifier.weight(1f))
            if (action != null) {
                DockFab(
                    action, colors,
                    Modifier.animateEnterExit(
                        enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = 500f), initialScale = 0.4f),
                        exit = scaleOut(tween(160), targetScale = 0.4f),
                    ),
                )
            }
        }
    }
}

/** The top variant: the capsule under the title; folds away on scroll down. */
@Composable
fun TopTabDock(
    visible: Boolean,
    tabs: List<DockTab>,
    selected: ChatListViewModel.BottomTab,
    onSelect: (ChatListViewModel.BottomTab) -> Unit,
    colors: AlphaColorScheme,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) + fadeIn(tween(160)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(140)),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDockHaze provides null) {
            TabPill(tabs, selected, onSelect, colors, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
        }
    }
}

/** Top variant's lone round button, bottom-right. */
@Composable
fun FloatingDockAction(visible: Boolean, action: DockAction?, colors: AlphaColorScheme, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible && action != null,
        modifier = modifier,
        enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = 500f), initialScale = 0.4f) + fadeIn(),
        exit = scaleOut(tween(160), targetScale = 0.4f) + fadeOut(tween(160)),
    ) {
        if (action != null) DockFab(action, colors, Modifier.navigationBarsPadding().padding(20.dp))
    }
}
