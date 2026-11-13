package com.alphamessenger.ui.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.BuildConfig
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaTheme

/** The public repository. Empty: the source rows say "soon" and the GitHub links hide. */
const val SOURCE_URL = "https://github.com/Vincentdiligent/AlphaMessenger"

/** Where people can support the project. Empty: the row hides. */
const val DONATE_URL = ""

/** Bundled from the repository root at build time (see app/build.gradle.kts). */
enum class LegalDoc(val asset: String) { LICENSE("legal/LICENSE"), COMPONENTS("legal/THIRD_PARTY_LICENSES.md") }

@Composable
fun AboutScreen(repository: AlphaCoreRepository, onBack: () -> Unit, onDoc: (LegalDoc) -> Unit) {
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    var tapAt by remember { mutableLongStateOf(0L) }
    var ownerKeyOpen by remember { mutableStateOf(false) }
    val open = { url: String -> com.alphamessenger.ui.browser.BrowserActivity.open(ctx, url) }

    OptionScreen(title = stringResource(R.string.settings_about_title), onBack = onBack) {
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                // The adaptive icon's layers are 108 dp with a 72 dp safe zone: drawn 1.5× to fill.
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(144.dp))
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(144.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Alpha",
                style = TextStyle(
                    brush = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFC026D3), Color(0xFFEC4899))),
                    fontFamily = com.alphamessenger.ui.theme.BricolageGrotesque,
                    fontWeight = FontWeight.Bold, fontSize = 34.sp,
                ),
            )
            // Seven taps in a row: the feed's publishing key (nothing shows it otherwise).
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                color = cs.textMuted, fontSize = 14.sp,
                modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                    val now = System.currentTimeMillis()
                    taps = if (now - tapAt < 600) taps + 1 else 1
                    tapAt = now
                    if (taps >= 7) { taps = 0; ownerKeyOpen = true }
                },
            )
            Text(
                stringResource(R.string.about_tagline), color = cs.textSecondary, fontSize = 15.sp, lineHeight = 21.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }

        com.alphamessenger.ui.update.UpdateCard(cs, repository, Modifier.padding(top = 8.dp), expanded = true)

        OptionSection(stringResource(R.string.about_section_privacy))
        OptionGroup {
            OptionRow(stringResource(R.string.about_p_account), stringResource(R.string.about_p_account_sub), AlphaIcons.Outlined.Key, Color(0xFFF59E0B))
            OptionDivider()
            OptionRow(stringResource(R.string.about_p_e2e), stringResource(R.string.about_p_e2e_sub), AlphaIcons.Outlined.Lock, Color(0xFF22C55E))
            OptionDivider()
            OptionRow(stringResource(R.string.about_p_network), stringResource(R.string.about_p_network_sub), AlphaIcons.Outlined.Hub, Color(0xFF5B7CFA))
            OptionDivider()
            OptionRow(stringResource(R.string.about_p_tracking), stringResource(R.string.about_p_tracking_sub), AlphaIcons.Outlined.VisibilityOff, Color(0xFFEC4899))
        }

        OptionSection(stringResource(R.string.about_section_tech))
        OptionGroup {
            OptionRow(stringResource(R.string.about_t_messages), "MLS (RFC 9420) · X25519 · AES-128-GCM", AlphaIcons.Outlined.Lock, Color(0xFF22C55E))
            OptionDivider()
            OptionRow(stringResource(R.string.about_t_network), stringResource(R.string.about_t_network_sub), AlphaIcons.Outlined.Hub, Color(0xFF5B7CFA))
            OptionDivider()
            OptionRow(stringResource(R.string.about_t_media), stringResource(R.string.about_t_media_sub), AlphaIcons.Outlined.Photo, Color(0xFFF59E0B))
            OptionDivider()
            OptionRow(stringResource(R.string.about_t_storage), stringResource(R.string.about_t_storage_sub), AlphaIcons.Outlined.Storage, Color(0xFF14B8A6))
            OptionDivider()
            OptionRow(stringResource(R.string.about_t_core), stringResource(R.string.about_t_core_sub), AlphaIcons.Outlined.Code, Color(0xFF8B5CF6))
        }

        OptionSection(stringResource(R.string.about_section_open))
        OptionGroup {
            OptionRow(
                stringResource(R.string.about_source),
                if (SOURCE_URL.isEmpty()) stringResource(R.string.about_source_soon) else SOURCE_URL.removePrefix("https://"),
                AlphaIcons.Outlined.Code, Color(0xFF8B5CF6),
                onClick = if (SOURCE_URL.isEmpty()) null else ({ open(SOURCE_URL) }),
            )
            OptionDivider()
            OptionRow(
                stringResource(R.string.about_license), stringResource(R.string.about_license_value),
                AlphaIcons.Outlined.Description, Color(0xFF14B8A6), onClick = { onDoc(LegalDoc.LICENSE) },
            )
            OptionDivider()
            OptionRow(
                stringResource(R.string.about_components), stringResource(R.string.about_components_sub),
                AlphaIcons.Outlined.Info, Color(0xFF64748B), onClick = { onDoc(LegalDoc.COMPONENTS) },
            )
            if (SOURCE_URL.isNotEmpty()) {
                OptionDivider()
                // One way in: the page lets a bug go public and a vulnerability go privately.
                OptionRow(
                    stringResource(R.string.about_bug), stringResource(R.string.about_bug_sub),
                    AlphaIcons.Outlined.Shield, Color(0xFFF59E0B), onClick = { open("$SOURCE_URL/issues/new/choose") },
                )
            }
        }

        if (DONATE_URL.isNotEmpty()) {
            OptionSection(stringResource(R.string.about_section_support))
            OptionGroup {
                OptionRow(
                    stringResource(R.string.about_donate), stringResource(R.string.about_donate_sub),
                    AlphaIcons.Outlined.FavoriteBorder, Color(0xFFEC4899), onClick = { open(DONATE_URL) },
                )
            }
        }
        OptionNote(stringResource(R.string.about_license_note))
    }

    if (ownerKeyOpen) {
        androidx.compose.runtime.LaunchedEffect(Unit) { com.alphamessenger.ui.feed.FeedStore.loadOwner(ctx) }
        com.alphamessenger.ui.feed.OwnerKeyDialog(cs, repository, onDismiss = { ownerKeyOpen = false })
    }
}

/** The license, or the components list, as shipped inside the app. */
@Composable
fun LegalDocScreen(doc: LegalDoc, onBack: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    val text = remember(doc) { readAsset(ctx, doc.asset) }
    val title = stringResource(if (doc == LegalDoc.LICENSE) R.string.about_license else R.string.about_components)
    OptionScreen(title = title, onBack = onBack) {
        when (doc) {
            LegalDoc.LICENSE -> SelectionContainer {
                Text(
                    text, color = cs.textSecondary, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
            LegalDoc.COMPONENTS -> Components(text)
        }
    }
}

/** The Markdown file's sections and tables as option groups; its prose as notes. */
@Composable
private fun Components(md: String) {
    val blocks = remember(md) { parseComponents(md) }
    blocks.forEach { b ->
        when (b) {
            is Block.Heading -> OptionSection(b.text)
            is Block.Note -> OptionNote(b.text)
            is Block.Table -> OptionGroup {
                b.rows.forEachIndexed { i, (name, license) ->
                    if (i > 0) OptionDivider()
                    OptionRow(name, license, chevron = false)
                }
            }
        }
    }
}

private sealed interface Block {
    data class Heading(val text: String) : Block
    data class Note(val text: String) : Block
    data class Table(val rows: List<Pair<String, String>>) : Block
}

private fun parseComponents(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    var rows: MutableList<Pair<String, String>>? = null
    fun flush() {
        if (para.isNotBlank()) out += Block.Note(para.toString().trim())
        para.clear()
        rows?.let { if (it.isNotEmpty()) out += Block.Table(it) }
        rows = null
    }
    for (raw in md.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("# ") -> flush() // the page title is the screen's own
            line.startsWith("## ") -> { flush(); out += Block.Heading(line.removePrefix("## ")) }
            line.startsWith("|") -> {
                if (para.isNotBlank()) { out += Block.Note(para.toString().trim()); para.clear() }
                val cells = line.trim('|').split('|').map { it.trim().replace("`", "") }
                val header = rows == null
                if (rows == null) rows = mutableListOf()
                if (!header && cells.size >= 2 && !cells[0].startsWith("---")) rows!!.add(cells[0] to cells[1])
            }
            line.isEmpty() -> flush()
            else -> { if (rows != null) flush(); para.append(line.replace("`", "")).append(' ') }
        }
    }
    flush()
    return out
}

private fun readAsset(ctx: Context, path: String): String =
    runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
