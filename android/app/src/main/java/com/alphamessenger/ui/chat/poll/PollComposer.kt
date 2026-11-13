package com.alphamessenger.ui.chat.poll

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.readableOn
import org.json.JSONArray
import org.json.JSONObject

/**
 * "New poll": a question, 2–10 options, and how it works — anonymous, several
 * answers, a quiz (its right answer and an explanation), closing by itself.
 * [channel]: a channel's polls are always anonymous. [onSend] gets the poll's JSON.
 */
@Composable
fun PollComposer(channel: Boolean, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val cs = AlphaTheme.colorScheme
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var anon by remember { mutableStateOf(true) }
    var multi by remember { mutableStateOf(false) }
    var quiz by remember { mutableStateOf(false) }
    var right by remember { mutableIntStateOf(0) }
    var explanation by remember { mutableStateOf("") }
    var closeAfter by remember { mutableLongStateOf(0L) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    val ready = question.isNotBlank() && filled.size >= 2 && (!quiz || options.getOrNull(right)?.isNotBlank() == true)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = cs.bgPrimary.luminance() >= 0.5f)
        // Above the navigation bar, or the keyboard when it is open (the larger of the two).
        Box(Modifier.fillMaxSize().background(cs.bgPrimary).windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    OptionScreen(title = stringResource(R.string.poll_new), onBack = onDismiss) {
                        OptionSection(stringResource(R.string.poll_question))
                        OptionGroup { Field(question, stringResource(R.string.poll_question_hint), bold = true) { question = it.take(300) } }

                        OptionSection(stringResource(R.string.poll_options))
                        OptionGroup {
                            options.forEachIndexed { i, text ->
                                if (i > 0) OptionDivider()
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                                    // In a quiz: tap the circle to mark the right answer.
                                    if (quiz) Box(
                                        Modifier.padding(start = 14.dp).size(22.dp).clip(CircleShape)
                                            .background(if (right == i) Color(0xFF2FB37A) else Color.Transparent)
                                            .border(2.dp, if (right == i) Color.Transparent else cs.textMuted, CircleShape)
                                            .clickable { right = i },
                                        contentAlignment = Alignment.Center,
                                    ) { if (right == i) Icon(AlphaIcons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                                    Box(Modifier.weight(1f)) {
                                        Field(text, stringResource(R.string.poll_option_hint, i + 1)) { options[i] = it.take(100) }
                                    }
                                    if (options.size > 2) IconButton(onClick = {
                                        options.removeAt(i)
                                        if (right >= options.size) right = 0
                                    }) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_delete), tint = cs.textMuted, modifier = Modifier.size(18.dp)) }
                                }
                            }
                            if (options.size < 10) {
                                OptionDivider()
                                Row(
                                    Modifier.fillMaxWidth().clickable { options.add("") }.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(AlphaIcons.Outlined.Add, null, tint = cs.accent, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.poll_add_option), color = cs.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Text(stringResource(R.string.poll_up_to_10), color = cs.textMuted, fontSize = 12.sp)
                                }
                            }
                        }

                        OptionSection(stringResource(R.string.poll_settings))
                        OptionGroup {
                            OptionSwitchRow(stringResource(R.string.poll_anon), stringResource(R.string.poll_anon_hint),
                                checked = anon || channel, enabled = !channel, onCheckedChange = { anon = it })
                            OptionDivider()
                            OptionSwitchRow(stringResource(R.string.poll_multi), null, checked = multi && !quiz, enabled = !quiz,
                                onCheckedChange = { multi = it })
                            OptionDivider()
                            OptionSwitchRow(stringResource(R.string.poll_quiz), stringResource(R.string.poll_quiz_hint), checked = quiz,
                                onCheckedChange = { quiz = it; if (it) multi = false })
                        }
                        if (quiz) {
                            OptionSection(stringResource(R.string.poll_explanation))
                            OptionGroup { Field(explanation, stringResource(R.string.poll_explanation_hint)) { explanation = it.take(200) } }
                        }

                        OptionSection(stringResource(R.string.poll_close_after))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0L to R.string.poll_close_never, 3_600L to R.string.poll_close_1h, 86_400L to R.string.poll_close_1d, 604_800L to R.string.poll_close_1w)
                                .forEach { (secs, label) ->
                                    val on = closeAfter == secs
                                    Box(
                                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                                            .background(if (on) cs.accent else cs.bgSecondary).clickable { closeAfter = secs },
                                        contentAlignment = Alignment.Center,
                                    ) { Text(stringResource(label), color = if (on) readableOn(cs.accent) else cs.textPrimary, fontSize = 12.5.sp, maxLines = 1) }
                                }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                Box(
                    Modifier.fillMaxWidth().padding(12.dp).height(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (ready) cs.accent else cs.bgTertiary)
                        .clickable(enabled = ready) {
                            val json = JSONObject()
                                .put("q", question.trim())
                                .put("o", JSONArray(options.map { it.trim() }.filter { it.isNotEmpty() }))
                                .put("anon", anon || channel)
                                .put("multi", multi && !quiz)
                                .put("quiz", quiz)
                                // The right answer among the options left after empty ones drop out.
                                .put("right", options.take(right).count { it.isNotBlank() })
                                .put("expl", explanation.trim())
                                .put("close_at", if (closeAfter > 0) System.currentTimeMillis() / 1000 + closeAfter else 0)
                            onSend(json.toString())
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.poll_send), color = if (ready) readableOn(cs.accent) else cs.textSecondary,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, bold: Boolean = false, onChange: (String) -> Unit) {
    val cs = AlphaTheme.colorScheme
    BasicTextField(
        value = value, onValueChange = onChange,
        textStyle = TextStyle(color = cs.textPrimary, fontSize = 15.5.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        cursorBrush = SolidColor(cs.accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, color = cs.textMuted, fontSize = 15.5.sp)
            inner()
        },
    )
}
