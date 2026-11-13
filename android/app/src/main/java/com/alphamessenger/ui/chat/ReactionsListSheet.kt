package com.alphamessenger.ui.chat

import androidx.compose.foundation.background
import com.alphamessenger.ui.components.avatarBackdrop
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

private data class Reactor(val senderId: String, val emoji: String, val mine: Boolean)

/**
 * Who reacted: tabs "All N" + one per emoji, then people with
 * their reaction. My own row says so and removes my reaction on tap.
 * The same sheet for direct chats and groups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsListSheet(
    message: MessageUi,
    ownId: String,
    nameOf: @Composable (String) -> String,
    avatarOf: (String) -> String,
    colors: AlphaColorScheme,
    /** My own name and avatar for my row. */
    myName: String = "",
    myAvatar: String = "",
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val all = remember(message.reactors, message.myReactions) {
        message.reactors.flatMap { (emoji, ids) ->
            ids.map { Reactor(it, emoji, it == ownId || it == "self") }
        }.sortedByDescending { it.mine }
    }
    val tabs = remember(message.reactions) { message.reactions.entries.sortedByDescending { it.value } }
    var filter by remember { mutableStateOf<String?>(null) }
    val shown = if (filter == null) all else all.filter { it.emoji == filter }

    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tab(stringResource(R.string.reactions_all, all.size), filter == null, colors) { filter = null }
                tabs.forEach { (emoji, count) ->
                    Tab("$emoji  $count", filter == emoji, colors) { filter = emoji }
                }
            }
            Spacer(Modifier.size(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(shown, key = { it.senderId + it.emoji }) { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (r.mine) Modifier.clickable { onToggle(r.emoji) } else Modifier)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val grad = remember(r.senderId) { avatarGradientFor(r.senderId) }
                        val path = if (r.mine) myAvatar else avatarOf(r.senderId)
                        val name = if (r.mine) myName.ifBlank { stringResource(R.string.notif_you) } else nameOf(r.senderId)
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).avatarBackdrop(Brush.linearGradient(listOf(grad.first, grad.second))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(com.alphamessenger.ui.components.avatarInitial(name), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            if (path.isNotBlank()) {
                                coil3.compose.AsyncImage(
                                    model = java.io.File(path), contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(CircleShape),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = colors.textPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.mine) Text(stringResource(R.string.reactions_you_tap_remove), color = colors.textMuted, fontSize = 13.sp)
                        }
                        Text(r.emoji, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tab(text: String, selected: Boolean, colors: AlphaColorScheme, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.bgTertiary)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) colors.accent else colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
