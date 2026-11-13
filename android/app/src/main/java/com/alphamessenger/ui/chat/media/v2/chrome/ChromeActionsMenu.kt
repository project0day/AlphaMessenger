package com.alphamessenger.ui.chat.media.v2.chrome

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Media actions menu.
 *
 * The menu itself never decides availability: an unavailable action arrives as
 * `null` and is simply not drawn. Item order, labels and icons live here;
 * availability rules live with whoever opens the viewer.
 *
 * Styled like every other "three dots" menu in the app: `bgSecondary`
 * background, icon on the left, text in theme color.
 */
@Composable
fun ChromeActionsMenu(
    expanded: Boolean,
    onDismissMenu: () -> Unit,
    onReply: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onShowInChat: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    saveLabel: String? = null,
    onSave: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null,
    onShowAllMedia: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
) {
    val colors = AlphaTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissMenu,
        modifier = Modifier.background(colors.bgSecondary),
    ) {
        @Composable
        fun row(label: String, icon: ImageVector, action: () -> Unit) = DropdownMenuItem(
            text = { Text(label, color = colors.textPrimary) },
            leadingIcon = { Icon(icon, null, tint = colors.textPrimary, modifier = Modifier.size(20.dp)) },
            onClick = { onDismissMenu(); action() },
        )

        onReply?.let { cb -> row(stringResource(R.string.notif_reply), AlphaIcons.AutoMirrored.Outlined.Reply, cb) }
        onForward?.let { cb -> row(stringResource(R.string.common_forward), AlphaIcons.AutoMirrored.Outlined.Send, cb) }
        onShowInChat?.let { cb -> row(stringResource(R.string.chat_show_in_chat), AlphaIcons.Outlined.Chat, cb) }
        onShare?.let { cb -> row(stringResource(R.string.common_share), AlphaIcons.Outlined.Share, cb) }
        if (saveLabel != null) onSave?.let { cb -> row(saveLabel, AlphaIcons.Outlined.Download, cb) }
        onOpenWith?.let { cb -> row(stringResource(R.string.chat_open_with), AlphaIcons.Outlined.OpenInNew, cb) }
        onShowAllMedia?.let { cb -> row(stringResource(R.string.chat_show_all_media), AlphaIcons.Outlined.PhotoLibrary, cb) }
        onInfo?.let { cb -> row(stringResource(R.string.common_info), AlphaIcons.Outlined.Info, cb) }
    }
}
