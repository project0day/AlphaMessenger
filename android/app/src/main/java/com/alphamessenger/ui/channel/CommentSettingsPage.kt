package com.alphamessenger.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/** Settings → Comments: default for new posts, slow mode, what comments may carry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommentSettingsPage(cfg: ChannelCfg, colors: AlphaColorScheme, onBack: () -> Unit, onChange: (ChannelCfg) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    com.alphamessenger.ui.components.OptionScreen(stringResource(R.string.cmt_settings_title), onBack = onBack) {
        Spacer(Modifier.size(8.dp))
        com.alphamessenger.ui.components.OptionGroup {
            com.alphamessenger.ui.components.OptionSwitchRow(
                stringResource(R.string.cmt_new_posts), stringResource(R.string.cmt_new_posts_hint),
                checked = cfg.comments, onCheckedChange = { onChange(cfg.copy(comments = it)) },
            )
        }
        com.alphamessenger.ui.components.OptionSection(stringResource(R.string.cmt_slow_section))
        com.alphamessenger.ui.components.OptionGroup {
            FlowRow(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 30, 60, 300, 900, 3600).forEach { secs ->
                    val on = cfg.slow == secs
                    val label = when {
                        secs == 0 -> stringResource(R.string.cmt_slow_off)
                        secs < 60 -> stringResource(R.string.cmt_slow_sec, secs)
                        secs < 3600 -> stringResource(R.string.cmt_slow_min, secs / 60)
                        else -> stringResource(R.string.cmt_mute_1h)
                    }
                    Text(
                        label, color = if (on) Color.White else colors.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) colors.accent else colors.bgTertiary)
                            .clickable { onChange(cfg.copy(slow = secs)) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        com.alphamessenger.ui.components.OptionNote(stringResource(R.string.cmt_slow_note))
        com.alphamessenger.ui.components.OptionSection(stringResource(R.string.cmt_kinds_section))
        com.alphamessenger.ui.components.OptionGroup {
            val labels = mapOf(
                "text" to R.string.cmt_kind_text, "link" to R.string.cmt_kind_link, "photo" to R.string.cmt_kind_photo,
                "video" to R.string.cmt_kind_video, "voice" to R.string.cmt_kind_voice, "file" to R.string.cmt_kind_file,
            )
            ChannelCfg.KINDS.forEachIndexed { i, k ->
                if (i > 0) com.alphamessenger.ui.components.OptionDivider()
                com.alphamessenger.ui.components.OptionSwitchRow(
                    stringResource(labels.getValue(k)), checked = cfg.allow[k] != false,
                    onCheckedChange = { on -> onChange(cfg.copy(allow = cfg.allow + (k to on))) },
                )
            }
        }
        com.alphamessenger.ui.components.OptionNote(stringResource(R.string.cmt_kinds_note))
    }
}
