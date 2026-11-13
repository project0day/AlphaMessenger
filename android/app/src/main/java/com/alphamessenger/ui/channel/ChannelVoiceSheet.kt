package com.alphamessenger.ui.channel

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.components.AlphaSheet
import com.alphamessenger.ui.components.AvatarSize
import com.alphamessenger.ui.components.AvatarView
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * Tapping a post or reply that came from the channel itself. There is no
 * person behind it to open, call or write to — only the channel. In a channel
 * that hides its admins the sheet says so plainly.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChannelVoiceSheet(
    name: String,
    avatarPath: String,
    anonymous: Boolean,
    colors: AlphaColorScheme,
    /** A group's hidden owner, not a channel's voice. */
    inGroup: Boolean = false,
    onReply: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AvatarView(name = name, imageUrl = avatarPath, size = AvatarSize.LARGE)
                Column {
                    Text(name, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(if (inGroup) R.string.group_voice_subtitle else R.string.channel_voice_subtitle), color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            if (anonymous) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.bgSecondary)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(AlphaIcons.Outlined.Shield, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                    Text(stringResource(if (inGroup) R.string.group_voice_anon else R.string.channel_voice_anon), color = colors.textPrimary, fontSize = 13.5.sp, lineHeight = 19.sp)
                }
            }
            if (onReply != null) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(colors.accent)
                        .clickable { onReply() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.channel_voice_reply), color = colors.bgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
