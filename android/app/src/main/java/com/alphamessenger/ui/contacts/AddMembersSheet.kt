package com.alphamessenger.ui.contacts

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.components.AlphaSheet
import com.alphamessenger.ui.components.AvatarSize
import com.alphamessenger.ui.components.AvatarView
import com.alphamessenger.ui.theme.AlphaColorScheme

/** Someone from our contacts who could be added. */
data class PickContact(val npub: String, val name: String, val avatarPath: String)

/**
 * "Add members" / "Add subscribers": our contacts to tick (those already in are
 * left out), or anyone by public key. One button adds them all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMembersSheet(
    title: String,
    colors: AlphaColorScheme,
    loadContacts: suspend () -> List<PickContact>,
    busy: Boolean,
    onAdd: (npubs: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val contacts by produceState<List<PickContact>?>(null) { value = loadContacts() }
    var query by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val clipboard = LocalClipboardManager.current
    val keyValid = key.trim().let { it.startsWith("npub1") && it.length >= 60 || com.alphamessenger.ui.chatlist.isContactKey(it) }
    val chosen = picked + if (keyValid) setOf(key.trim()) else emptySet()

    AlphaSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 12.dp))

            // Search among contacts.
            Field(query, { query = it }, stringResource(R.string.add_members_search), AlphaIcons.Outlined.Search, colors)
            Spacer(Modifier.height(10.dp))

            val q = query.trim()
            val shown = contacts.orEmpty().filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                if (contacts == null) item {
                    Text(stringResource(R.string.common_loading), color = colors.textMuted, fontSize = 14.sp,
                        modifier = Modifier.padding(12.dp))
                } else if (shown.isEmpty()) item {
                    Text(stringResource(R.string.add_members_no_contacts), color = colors.textMuted, fontSize = 14.sp,
                        modifier = Modifier.padding(12.dp))
                }
                items(shown, key = { it.npub }) { c ->
                    val on = c.npub in picked
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { picked = if (on) picked - c.npub else picked + c.npub }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarView(name = c.name, imageUrl = c.avatarPath, size = AvatarSize.MEDIUM)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, color = colors.textPrimary, fontSize = 15.5.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(
                            if (on) AlphaIcons.Filled.CheckCircle else AlphaIcons.Outlined.RadioButtonUnchecked, null,
                            tint = if (on) colors.accent else colors.textMuted, modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            // Anyone else: by public key.
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.add_members_by_key), color = colors.textMuted, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Field(key, { key = it.trim() }, "npub1…", AlphaIcons.Outlined.Key, colors)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(colors.bgSecondary)
                        .clickable { clipboard.getText()?.text?.trim()?.let { key = it } },
                    contentAlignment = Alignment.Center,
                ) { Icon(AlphaIcons.Outlined.ContentPaste, stringResource(R.string.add_members_paste), tint = colors.accent) }
            }

            Spacer(Modifier.height(16.dp))
            val ready = chosen.isNotEmpty() && !busy
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (ready) colors.accent else colors.bgTertiary)
                    .clickable(enabled = ready) { onAdd(chosen.toList()) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) stringResource(R.string.invite_sending)
                    else if (chosen.isEmpty()) stringResource(R.string.add_members_action)
                    else stringResource(R.string.add_members_action_n, chosen.size),
                    color = if (ready) colors.bgPrimary else colors.textMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: AlphaColorScheme,
) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(colors.bgSecondary)
            .border(1.dp, colors.alphaBorder, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = colors.textMuted, fontSize = 15.sp, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}
