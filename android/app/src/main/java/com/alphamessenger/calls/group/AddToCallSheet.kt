package com.alphamessenger.calls.group

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository

/** A friend who can be added to a call. */
data class CallFriend(val npub: String, val name: String, val avatar: String)

/**
 * "Add to the call": pick friends while in a one-to-one call. The call then
 * moves into a small group made for it (the person you talk with and the
 * friends you picked), where everyone is rung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCallSheet(
    core: AlphaCoreRepository,
    currentChatId: String,
    onDismiss: () -> Unit,
    onConfirm: (List<CallFriend>) -> Unit,
) {
    val friends by produceState(emptyList<CallFriend>(), currentChatId) {
        value = runCatching {
            val own = core.getIdentityInfo().npub
            val currentPeer = core.getPeerNpubForChat(currentChatId)
            core.listChats().filter { it.chatType == "direct" && it.id != currentChatId && it.id != "saved_messages_local" && !com.alphamessenger.assistant.isAssistantChat(it.id) }
                .mapNotNull { c ->
                    val npub = runCatching { core.getPeerNpubForChat(c.id) }.getOrNull().orEmpty()
                    if (npub.isBlank() || npub == own || npub == currentPeer) return@mapNotNull null
                    val sid = runCatching { core.resolvePeerSenderId(c.id, own) }.getOrNull().orEmpty()
                    val prof = if (sid.isBlank()) null else runCatching { core.getPeerProfile(sid) }.getOrNull()
                    CallFriend(npub, prof?.displayName?.takeIf { it.isNotBlank() } ?: c.name.orEmpty(), prof?.avatarPath.orEmpty())
                }
                .distinctBy { it.npub }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }
    val picked = remember { mutableStateListOf<String>() }
    val max = GroupCallManager.MAX_MEMBERS - 2
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color(0xFF181B21),
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.gcall_add_title), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.gcall_add_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(friends, key = { it.npub }) { f ->
                    val on = f.npub in picked
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { if (on) picked.remove(f.npub) else if (picked.size < max) picked.add(f.npub) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3D9DF0)), contentAlignment = Alignment.Center) {
                            Text(f.name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (f.avatar.isNotBlank()) AsyncImage(java.io.File(f.avatar), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(f.name.ifBlank { "…" }, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (on) Icon(AlphaIcons.Filled.CheckCircle, null, tint = Color(0xFF2FB37A))
                        else Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val ready = picked.isNotEmpty()
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (ready) Color(0xFF2FB37A) else Color.White.copy(alpha = 0.12f))
                    .clickable(enabled = ready) { onConfirm(friends.filter { it.npub in picked }) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.gcall_add_confirm, picked.size), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
