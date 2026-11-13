package com.alphamessenger.ui.channel

import com.alphamessenger.ui.icons.AlphaIcons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Color
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.theme.AlphaColorScheme

/** "New channel": cover, name, description, how people join, what subscribers may do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChannelSheet(
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, avatarUri: String?, cfg: ChannelCfg) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<Uri?>(null) }
    var cfg by remember { mutableStateOf(ChannelCfg(on = true, anon = true)) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) avatar = uri }

    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
    ) {
        // Compact, to fit one screen; never under the status bar if it reaches the top.
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().verticalScroll(rememberScrollState())
                .navigationBarsPadding().imePadding()
                .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp),
        ) {
            Text(stringResource(R.string.channel_new_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(colors.bgSecondary)
                        .border(1.dp, colors.alphaBorder, RoundedCornerShape(22.dp))
                        .clickable { pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar != null) AsyncImage(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                    else Icon(AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.channel_cover), tint = colors.accent, modifier = Modifier.size(26.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(name, { name = it.take(64) }, stringResource(R.string.channel_name_hint), colors)
                    Field(description, { description = it.take(255) }, stringResource(R.string.channel_desc_hint), colors)
                }
            }
            // How people join: two pills, the chosen one's note under them.
            OptionSection(stringResource(R.string.channel_join_section))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ChannelCfg.JOIN_LINK to R.string.channel_join_link, ChannelCfg.JOIN_REQUEST to R.string.channel_join_request)
                    .forEach { (j, label) ->
                        val on = cfg.join == j
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) colors.accent else colors.bgSecondary)
                                .clickable { cfg = cfg.copy(join = j) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(label), color = if (on) Color.White else colors.textPrimary, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
            }
            Text(
                stringResource(if (cfg.join == ChannelCfg.JOIN_REQUEST) R.string.channel_join_request_hint else R.string.channel_join_link_hint),
                color = colors.textMuted, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp),
            )
            OptionSection(stringResource(R.string.channel_subscribers_section))
            OptionGroup {
                OptionSwitchRow(stringResource(R.string.channel_anon), stringResource(R.string.channel_anon_hint),
                    checked = cfg.anon, onCheckedChange = { cfg = cfg.copy(anon = it) })
                OptionDivider()
                OptionSwitchRow(stringResource(R.string.channel_comments), checked = cfg.comments,
                    onCheckedChange = { cfg = cfg.copy(comments = it) })
                OptionDivider()
                OptionSwitchRow(stringResource(R.string.channel_protect), stringResource(R.string.channel_protect_hint),
                    checked = cfg.protect, onCheckedChange = { cfg = cfg.copy(protect = it) })
                OptionDivider()
                OptionSwitchRow(stringResource(R.string.history_newcomers), stringResource(R.string.history_newcomers_channel_hint),
                    checked = cfg.history ?: true, onCheckedChange = { cfg = cfg.copy(history = it) })
            }
            Spacer(Modifier.height(14.dp))
            val ready = name.isNotBlank() && !busy
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp))
                    .background(if (ready) colors.accent else colors.bgTertiary)
                    .clickable(enabled = ready) { busy = true; onCreate(name, description, avatar?.toString(), cfg) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.channel_create), color = if (ready) colors.bgPrimary else colors.textMuted,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, colors: AlphaColorScheme) {
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(colors.bgSecondary)
            .border(1.dp, colors.alphaBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = colors.textMuted, fontSize = 15.sp)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
            cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth())
    }
}
