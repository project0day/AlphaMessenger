package com.alphamessenger.ui.chat.v2

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * The menu a hold on Send opens, just above the button: send without sound,
 * without compression, or later. It takes no focus, so an open keyboard stays where it is; an outside
 * tap or Back closes it. Place it beside the Send button, inside the same box.
 */
@Composable
fun SendOptionsMenu(
    open: Boolean,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onUncompressed: (() -> Unit)?,
    onLater: (() -> Unit)?,
    onSilent: (() -> Unit)? = null,
) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = open
    if (!shown.currentState && !shown.targetState) return
    val lift = with(LocalDensity.current) { 8.dp.roundToPx() }
    // Above the button, right edges aligned; the card grows upward from the button.
    val above = remember(lift) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ) = IntOffset(
                (anchorBounds.right - popupContentSize.width).coerceAtLeast(0),
                anchorBounds.top - popupContentSize.height - lift,
            )
        }
    }
    Popup(
        popupPositionProvider = above,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        BackHandler(enabled = open, onBack = onDismiss)
        Box {
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 1f)),
                exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.94f, transformOrigin = TransformOrigin(1f, 1f)),
            ) {
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier.width(236.dp).clip(shape)
                        .background(colors.bgSecondary).border(0.5.dp, colors.alphaBorder, shape)
                        .padding(vertical = 6.dp),
                ) {
                    if (onSilent != null) Option(AlphaIcons.Outlined.NotificationsOff, stringResource(R.string.send_silent), colors) {
                        onDismiss(); onSilent()
                    }
                    if (onUncompressed != null) Option(AlphaIcons.Outlined.HighQuality, stringResource(R.string.send_uncompressed), colors) {
                        onDismiss(); onUncompressed()
                    }
                    if (onLater != null) Option(AlphaIcons.Outlined.Schedule, stringResource(R.string.schedule_title), colors) {
                        onDismiss(); onLater()
                    }
                }
            }
        }
    }
}

@Composable
private fun Option(icon: ImageVector, label: String, colors: AlphaColorScheme, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).background(colors.accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(label, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
