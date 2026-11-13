package com.alphamessenger.ui.channel

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.theme.AlphaColorScheme

/** Short count: 980, 1,2K, 15K. */
fun compactCount(n: Int): String = when {
    n < 1000 -> n.toString()
    n < 10_000 -> String.format(java.util.Locale.getDefault(), "%.1fK", n / 1000f)
    else -> "${n / 1000}K"
}

/** A subscriber's bottom bar: no composer, just sound on/off for the channel. */
@Composable
fun ChannelSubscriberBar(muted: Boolean, colors: AlphaColorScheme, onToggleMute: () -> Unit, compact: Boolean = false) {
    // compact = drawn inside the input bar's own slot (it sets the size).
    Box(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            (if (compact) Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)) else Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)))
                .background(colors.bgSecondary).border(1.dp, colors.alphaBorder, RoundedCornerShape(26.dp))
                .clickable(onClick = onToggleMute),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (muted) AlphaIcons.Outlined.Notifications else AlphaIcons.Outlined.NotificationsOff, null,
                tint = colors.textPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (muted) R.string.channel_unmute else R.string.channel_mute),
                color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
