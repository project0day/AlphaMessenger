package com.alphamessenger.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * Says when the user's relays are the reason nothing arrives: all switched off,
 * all deleted, or the app is on the reserve relay. Tapping opens the relays.
 */
@Composable
fun RelayLinkBanner(viewModel: ChatListViewModel, colors: AlphaColorScheme, onOpenRelays: () -> Unit) {
    val link by viewModel.relayLink.collectAsState()
    val (text, tint) = when (link) {
        ChatListViewModel.RelayLink.OFF -> R.string.relay_link_off to colors.danger
        ChatListViewModel.RelayLink.NONE -> R.string.relay_link_none to colors.warning
        ChatListViewModel.RelayLink.RESERVE -> R.string.relay_link_reserve to colors.warning
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onOpenRelays)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(text), color = colors.textPrimary, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("›", color = tint, fontSize = 20.sp)
    }
}
