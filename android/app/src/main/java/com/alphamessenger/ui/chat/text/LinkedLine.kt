package com.alphamessenger.ui.chat.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.alphamessenger.ui.theme.AlphaTheme

/** Whether [text] holds something to tap (a link, an invite, an email, a number). */
fun hasTappable(text: String): Boolean = TextParser.parse(text).any { it.clickable }

/**
 * A short line (a profile status) whose links open like in a message:
 * the same parser, colours and action sheet. Invites open in the app.
 */
@Composable
fun LinkedLine(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    val ctx = LocalContext.current
    var action by remember { mutableStateOf<LinkAction?>(null) }
    MessageText(
        text = text,
        color = color,
        fontSize = fontSize,
        lineHeight = fontSize * 1.3f,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onEntityTap = { e -> action = e.toLinkAction() },
    )
    action?.let { a ->
        LinkActionSheet(
            action = a,
            colors = AlphaTheme.colorScheme,
            onDismiss = { action = null },
            onOpenInvite = { link ->
                action = null
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                            .setPackage(ctx.packageName)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}
