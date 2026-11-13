package com.alphamessenger.ui.browser

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaTheme

/** Settings → Browser: whether chat links open inside, and how the browser behaves. */
@Composable
fun BrowserSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = AlphaTheme.colorScheme
    val prefs = remember { BrowserPrefs(ctx) }
    var inside by remember { mutableStateOf(prefs.openLinksInside) }
    var adBlock by remember { mutableStateOf(prefs.adBlock) }
    var desktop by remember { mutableStateOf(prefs.desktopMode) }
    var wipe by remember { mutableStateOf(prefs.wipeOnClose) }
    var thirdParty by remember { mutableStateOf(prefs.thirdPartyCookies) }
    var engine by remember { mutableStateOf(prefs.searchEngine) }
    var custom by remember { mutableStateOf(prefs.customSearch) }
    var zoom by remember { mutableIntStateOf(prefs.textZoom) }
    var clearing by remember { mutableStateOf(false) }

    OptionScreen(stringResource(R.string.browser_title), onBack) {
        OptionGroup {
            OptionSwitchRow(
                stringResource(R.string.browser_set_inside), stringResource(R.string.browser_set_inside_hint),
                inside, { inside = it; prefs.openLinksInside = it }, icon = AlphaIcons.Outlined.Public,
            )
            OptionDivider()
            OptionRow(
                title = stringResource(R.string.browser_set_open), icon = AlphaIcons.Outlined.OpenInNew,
                onClick = { BrowserSession.expand(ctx) },
            )
        }

        OptionSection(stringResource(R.string.browser_set_search))
        OptionGroup {
            SearchEngine.entries.forEachIndexed { i, e ->
                if (i > 0) OptionDivider()
                val title = if (e == SearchEngine.CUSTOM) stringResource(R.string.browser_set_custom) else e.title
                OptionChoiceRow(title, null, engine == e) { engine = e; prefs.searchEngine = e }
            }
            if (engine == SearchEngine.CUSTOM) {
                androidx.compose.material3.OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it; prefs.customSearch = it },
                    label = { Text(stringResource(R.string.browser_set_custom_field)) },
                    placeholder = { Text("https://example.org/search?q=%s", color = cs.textMuted) },
                    singleLine = true,
                    isError = custom.isNotBlank() && !custom.trim().startsWith("https://") && !custom.trim().startsWith("http://"),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false,
                    ),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = cs.textPrimary, unfocusedTextColor = cs.textPrimary,
                        focusedBorderColor = cs.accent, unfocusedBorderColor = cs.alphaBorder,
                        focusedLabelColor = cs.accent, unfocusedLabelColor = cs.textMuted, cursorColor = cs.accent,
                    ),
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        OptionNote(stringResource(R.string.browser_set_search_note))

        OptionSection(stringResource(R.string.browser_set_pages))
        OptionGroup {
            OptionSwitchRow(
                stringResource(R.string.browser_adblock), stringResource(R.string.browser_set_adblock_hint),
                adBlock, { adBlock = it; prefs.adBlock = it; if (it) AdBlock.warmUp(ctx) }, icon = AlphaIcons.Outlined.Shield,
            )
            OptionDivider()
            OptionSwitchRow(stringResource(R.string.browser_desktop), null, desktop, { desktop = it; prefs.desktopMode = it }, icon = AlphaIcons.Outlined.Language)
            OptionDivider()
            OptionRow(
                title = stringResource(R.string.browser_set_zoom), icon = AlphaIcons.Outlined.TextFields,
                value = "$zoom%", chevron = false,
                onClick = {
                    val steps = listOf(85, 100, 115, 130, 150)
                    zoom = steps[(steps.indexOf(zoom) + 1).mod(steps.size)].also { prefs.textZoom = it }
                },
            )
        }

        OptionSection(stringResource(R.string.browser_set_privacy))
        OptionGroup {
            OptionSwitchRow(
                stringResource(R.string.browser_set_wipe), stringResource(R.string.browser_set_wipe_hint),
                wipe, { wipe = it; prefs.wipeOnClose = it }, icon = AlphaIcons.Outlined.AutoDelete,
            )
            OptionDivider()
            OptionSwitchRow(
                stringResource(R.string.browser_set_third_party), stringResource(R.string.browser_set_third_party_hint),
                thirdParty, { thirdParty = it; prefs.thirdPartyCookies = it }, icon = AlphaIcons.Outlined.Block,
            )
            OptionDivider()
            OptionRow(title = stringResource(R.string.browser_set_clear), icon = AlphaIcons.Outlined.DeleteSweep, danger = true, onClick = { clearing = true })
        }
        OptionNote(stringResource(R.string.browser_set_tor_note))
    }

    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        containerColor = cs.bgSecondary,
        title = { Text(stringResource(R.string.browser_clear_q), color = cs.textPrimary) },
        text = { Text(stringResource(R.string.browser_clear_text), color = cs.textSecondary) },
        confirmButton = {
            TextButton(onClick = {
                clearing = false
                BrowserActivity.clearEverything(ctx)
                Toast.makeText(ctx, ctx.getString(R.string.browser_set_cleared), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.browser_clear_all), color = cs.danger) }
        },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.common_cancel), color = cs.textMuted) } },
    )
}
