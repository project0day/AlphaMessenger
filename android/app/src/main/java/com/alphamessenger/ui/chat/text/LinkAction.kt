package com.alphamessenger.ui.chat.text

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * LinkAction — the action model for a clickable entity.
 *
 * TextRenderer turns a tap on a TextEntity into ONE [LinkAction]; the UI
 * (bottom sheet) then decides what to do with it. Bubble knows nothing of it.
 */
sealed class LinkAction {
    abstract val display: String
    abstract val intentUri: String

    data class OpenUrl(val url: String) : LinkAction() {
        override val display: String get() = url
        override val intentUri: String get() = url
    }

    data class Email(val address: String) : LinkAction() {
        override val display: String get() = address
        override val intentUri: String get() = "mailto:$address"
    }

    data class Phone(val number: String, val normalized: String) : LinkAction() {
        override val display: String get() = number
        override val intentUri: String get() = "tel:$normalized"
    }

    /** #hashtag: searched for in the chat, inside the app. */
    data class Hashtag(val tag: String) : LinkAction() {
        override val display: String get() = "#$tag"
        override val intentUri: String get() = ""
    }

    /** Invites are handled inside the app (join), not via an Intent. */
    data class Invite(val link: String) : LinkAction() {
        override val display: String get() = link
        override val intentUri: String get() = link
    }
}

/** Build an action from an entity; null for non-clickable ones. */
fun TextEntity.toLinkAction(): LinkAction? = when (this) {
    is TextEntity.Url -> LinkAction.OpenUrl(url)
    is TextEntity.Email -> LinkAction.Email(address)
    is TextEntity.Phone -> LinkAction.Phone(number, normalized)
    is TextEntity.AlphaInvite -> LinkAction.Invite(link)
    is TextEntity.Hashtag -> LinkAction.Hashtag(tag)
    else -> null // Mention / Hashtag / Markdown / Spoiler — skeleton, not clickable
}

// --- Link action bottom sheet ---

/**
 * A small bottom sheet tied to a link: Open / Copy / Share. Safer than
 * other messengers (no accidental opens) and faster than other messengers (no confirmation per
 * tap — the menu opens on tap instead of an extra dialog).
 *
 * Rendered over the chat. The caller keeps `var pendingLinkAction`, passes it
 * here, and clears it on action or dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkActionSheet(
    action: LinkAction,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onOpenInvite: (String) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun open() {
        when (action) {
            is LinkAction.Invite -> if (com.alphamessenger.ui.sticker.isPackLink(action.link)) com.alphamessenger.ui.sticker.StickerUi.open(action.link, null)
                else if (com.alphamessenger.ui.theme.isThemeLink(action.link)) com.alphamessenger.ui.theme.ThemeUi.open(action.link) else onOpenInvite(action.link)
            is LinkAction.OpenUrl -> if (com.alphamessenger.ui.browser.BrowserActivity.isWebLink(action.url) &&
                com.alphamessenger.ui.browser.BrowserPrefs(ctx).openLinksInside
            ) com.alphamessenger.ui.browser.BrowserActivity.open(ctx, action.url) else ctx.startViewIntent(action.intentUri)
            else -> ctx.startViewIntent(action.intentUri)
        }
    }

    fun copy() {
        ctx.copyToClipboard("link", action.display)
    }

    fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, action.display)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.link_share))) }
    }

    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Text(
            action.display,
            color = colors.textMuted,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LinkActionRow(stringResource(R.string.common_open), AlphaIcons.Outlined.OpenInNew, colors) { onDismiss(); open() }
        LinkActionRow(stringResource(R.string.link_copy), AlphaIcons.Outlined.ContentCopy, colors) { onDismiss(); copy() }
        LinkActionRow(stringResource(R.string.common_share), AlphaIcons.Outlined.Share, colors) { onDismiss(); share() }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LinkActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            icon, null, tint = colors.textPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(label, color = colors.textPrimary, fontSize = 16.sp)
    }
}

// --- System helpers (UI layer) ---

private fun Context.startViewIntent(uri: String) {
    runCatching {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), getString(R.string.chat_open_with)))
    }.recoverCatching {
        // Some OEM builds have no chooser wrapper — try directly.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (_: ActivityNotFoundException) {
        }
    }
}

private fun Context.copyToClipboard(label: String, value: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    android.widget.Toast.makeText(this, getString(R.string.chat_copied), android.widget.Toast.LENGTH_SHORT).show()
}
