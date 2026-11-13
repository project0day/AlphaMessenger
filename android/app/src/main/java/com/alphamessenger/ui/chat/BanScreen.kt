package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * What a banned member sees of a group or channel: the ban and its reason,
 * nothing else. The chat's content is already gone from the phone.
 */
@Composable
fun BanScreen(
    name: String,
    reason: String,
    atSec: Long,
    isChannel: Boolean,
    /** Not a ban: the owner deleted the chat for everyone (no reason, no names). */
    disbanded: Boolean = false,
    onBack: () -> Unit,
    onRemove: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val cs = AlphaTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.bgPrimary).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AlphaIcons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = cs.textPrimary) }
            Text(name, color = cs.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.bgSecondary).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(cs.danger.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (disbanded) AlphaIcons.Outlined.DeleteOutline else AlphaIcons.Outlined.Block, null,
                        tint = cs.danger, modifier = Modifier.size(30.dp),
                    )
                }
                Text(
                    stringResource(
                        if (!disbanded) R.string.ban_screen_title
                        else if (isChannel) R.string.disband_title_channel else R.string.disband_title_group,
                    ),
                    color = cs.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(
                        if (disbanded) { if (isChannel) R.string.disband_text_channel else R.string.disband_text_group }
                        else if (isChannel) R.string.ban_screen_text_channel else R.string.ban_screen_text_group,
                    ),
                    color = cs.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                if (!disbanded) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.bgTertiary).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.ban_screen_reason), color = cs.textMuted, fontSize = 12.sp)
                        Text(reason.ifBlank { stringResource(R.string.ban_screen_no_reason) }, color = cs.textPrimary, fontSize = 15.sp)
                    }
                }
                if (atSec > 0) {
                    Text(
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT)
                            .format(java.util.Date(atSec * 1000)),
                        color = cs.textMuted, fontSize = 12.sp,
                    )
                }
            }
        }
        Box(
            Modifier.padding(16.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(cs.danger).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.ban_screen_remove), color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}
