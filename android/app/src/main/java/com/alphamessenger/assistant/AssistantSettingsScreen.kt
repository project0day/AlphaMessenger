package com.alphamessenger.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.launch

/** Settings → Assistant: the profiles, a new one, and the journal. */
@Composable
fun AssistantSettingsScreen(
    onBack: () -> Unit,
    onEdit: (profileId: String?) -> Unit,
    onNewCouncil: () -> Unit,
    onJournal: () -> Unit,
    onOpenChat: (chatId: String) -> Unit,
) {
    val ctx = LocalContext.current
    AssistantStore.init(ctx)
    val profiles by AssistantStore.profiles.collectAsState()
    OptionScreen(title = stringResource(R.string.assistant_title), onBack = onBack) {
        OptionNote(stringResource(R.string.assistant_intro))
        if (profiles.isNotEmpty()) {
            OptionSection(stringResource(R.string.assistant_profiles))
            OptionGroup {
                profiles.forEachIndexed { i, p ->
                    if (i > 0) OptionDivider()
                    OptionRow(
                        title = p.name.ifBlank { p.model },
                        subtitle = if (p.isCouncil) p.members.mapNotNull { id -> profiles.firstOrNull { it.id == id }?.name }.joinToString(", ")
                            else listOf(p.model, hostOf(p.endpoint)).filter { it.isNotBlank() }.joinToString(" · "),
                        icon = AlphaIcons.Outlined.AutoAwesome,
                        onClick = { onEdit(p.id) },
                        trailing = {
                            Text(
                                stringResource(R.string.assistant_open_chat),
                                color = AlphaTheme.colorScheme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onOpenChat(p.chatId) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OptionGroup {
            OptionRow(title = stringResource(R.string.assistant_add), icon = AlphaIcons.Outlined.Add, onClick = { onEdit(null) })
            OptionDivider()
            val singles = profiles.count { !it.isCouncil }
            OptionRow(
                title = stringResource(R.string.assistant_council_new),
                subtitle = stringResource(if (singles >= 2) R.string.assistant_council_hint else R.string.assistant_council_need),
                icon = AlphaIcons.Outlined.Hub,
                onClick = if (singles >= 2) onNewCouncil else null,
                chevron = singles >= 2,
            )
            OptionDivider()
            OptionRow(title = stringResource(R.string.assistant_journal), subtitle = stringResource(R.string.assistant_journal_hint), icon = AlphaIcons.Outlined.History, onClick = onJournal)
        }
        OptionNote(stringResource(R.string.assistant_privacy_note))
    }
}

private fun hostOf(endpoint: String): String =
    endpoint.substringAfter("://").substringBefore('/').take(40)

/** One profile: the service, the model, how it may reach it and what it may do. */
@Composable
fun AssistantProfileScreen(
    profileId: String?,
    repository: AlphaCoreRepository,
    newCouncil: Boolean = false,
    onBack: () -> Unit,
    onOpenChat: (chatId: String) -> Unit,
) {
    val ctx = LocalContext.current
    AssistantStore.init(ctx)
    val cs = AlphaTheme.colorScheme
    val scope = rememberCoroutineScope()
    val existing = remember(profileId) { profileId?.let { AssistantStore.profile(it) } }
    val id = remember(profileId) { existing?.id ?: java.util.UUID.randomUUID().toString().replace("-", "").take(12) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var endpoint by rememberSaveable { mutableStateOf(existing?.endpoint ?: "https://") }
    var key by rememberSaveable { mutableStateOf(existing?.let { AssistantStore.apiKey(it.id) } ?: "") }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: "") }
    var system by rememberSaveable { mutableStateOf(existing?.systemPrompt ?: "") }
    var searchKey by rememberSaveable { mutableStateOf(AssistantStore.searchKey()) }
    var stt by rememberSaveable { mutableStateOf(existing?.sttModel ?: "") }
    var perms by remember { mutableStateOf(existing?.perms ?: setOf(AssistantPerm.SHARE)) }
    var imageModel by rememberSaveable { mutableStateOf(existing?.imageModel ?: "") }
    var ttsModel by rememberSaveable { mutableStateOf(existing?.ttsModel ?: "") }
    var ttsVoice by rememberSaveable { mutableStateOf(existing?.ttsVoice ?: "alloy") }
    var videoModel by rememberSaveable { mutableStateOf(existing?.videoModel ?: "") }
    val council = newCouncil || existing?.isCouncil == true
    var members by remember { mutableStateOf(existing?.members ?: emptyList()) }
    var eachOwn by rememberSaveable { mutableStateOf(existing?.eachOwn ?: false) }
    var memoryOn by rememberSaveable { mutableStateOf(existing?.memory ?: false) }
    val memory by AssistantStore.memory.collectAsState()
    var clearingMemory by remember { mutableStateOf(false) }
    val allProfiles by AssistantStore.profiles.collectAsState()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var probe by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var probing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val errText = errorTexts()

    fun current() = AssistantProfile(
        id, name.trim(), endpoint.trim(), model.trim(), system.trim(), stt.trim(), perms,
        imageModel = imageModel.trim(), ttsModel = ttsModel.trim(), ttsVoice = ttsVoice.trim().ifBlank { "alloy" }, videoModel = videoModel.trim(),
        members = if (council) members else emptyList(), eachOwn = eachOwn, memory = memoryOn,
    )

    OptionScreen(
        title = stringResource(if (council) R.string.assistant_council else if (existing == null) R.string.assistant_new else R.string.assistant_profile),
        onBack = onBack,
    ) {
        if (council) {
            OptionNote(stringResource(R.string.assistant_council_intro))
            OptionGroup {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Field(name, { name = it }, stringResource(R.string.assistant_name), stringResource(R.string.assistant_council))
                }
            }
            OptionSection(stringResource(R.string.assistant_council_members))
            OptionGroup {
                allProfiles.filter { !it.isCouncil }.forEachIndexed { i, p ->
                    if (i > 0) OptionDivider()
                    val on = p.id in members
                    OptionSwitchRow(p.name, p.model, on, { members = if (it) (members + p.id).take(3) else members - p.id })
                }
            }
            OptionNote(stringResource(R.string.assistant_council_members_hint))
            OptionSection(stringResource(R.string.assistant_council_mode))
            OptionGroup {
                com.alphamessenger.ui.components.OptionChoiceRow(stringResource(R.string.assistant_council_common), stringResource(R.string.assistant_council_common_hint), !eachOwn) { eachOwn = false }
                OptionDivider()
                com.alphamessenger.ui.components.OptionChoiceRow(stringResource(R.string.assistant_council_each), stringResource(R.string.assistant_council_each_hint), eachOwn) { eachOwn = true }
            }
        }
        if (!council) {
        OptionSection(stringResource(R.string.assistant_service))
        OptionGroup {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(name, { name = it }, stringResource(R.string.assistant_name), stringResource(R.string.assistant_name_hint))
                Field(endpoint, { endpoint = it; probe = null }, stringResource(R.string.assistant_endpoint), "https://api.example.com/v1", KeyboardType.Uri)
                Field(key, { key = it; probe = null }, stringResource(R.string.assistant_key), "sk-…", KeyboardType.Password, secret = true)
                Field(model, { model = it }, stringResource(R.string.assistant_model), "gpt-4o-mini")
                if (models.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        models.take(40).forEach { m ->
                            val on = m == model
                            Text(
                                m, fontSize = 12.5.sp,
                                color = if (on) Color.White else cs.textPrimary,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                    .background(if (on) cs.accent else cs.bgTertiary)
                                    .clickable { model = m }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (probing) R.string.assistant_probing else R.string.assistant_probe),
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(cs.accent)
                            .clickable(enabled = !probing) {
                                probing = true
                                probe = null
                                scope.launch {
                                    val t0 = System.currentTimeMillis()
                                    val p = current()
                                    probe = try {
                                        val list = AssistantClient.models(p, key.trim())
                                        models = list
                                        if (model.isBlank() && list.isNotEmpty()) model = list.first()
                                        true to ctx.getString(R.string.assistant_probe_ok, list.size, (System.currentTimeMillis() - t0) / 100 / 10.0)
                                    } catch (e: AssistantFailure) {
                                        false to (errText[e.error.kind] ?: e.error.kind.name) + (e.error.code.takeIf { it > 0 }?.let { " ($it)" } ?: "")
                                    } catch (e: Exception) {
                                        false to (e.message ?: e.javaClass.simpleName)
                                    }
                                    probing = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    probe?.let { (ok, text) ->
                        Text(text, color = if (ok) Color(0xFF34C759) else cs.danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (!council) {
            OptionSection(stringResource(R.string.assistant_check_section))
            OptionGroup { ModelCheck(profile = { current() }, key = { key.trim() }) }
        }

        OptionSection(stringResource(R.string.assistant_behaviour))
        OptionGroup {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(system, { system = it }, stringResource(R.string.assistant_instruction), stringResource(R.string.assistant_instruction_hint), singleLine = false)
                Field(stt, { stt = it }, stringResource(R.string.assistant_stt), "whisper-1")
            }
        }

        OptionSection(stringResource(R.string.assistant_skills))
        OptionGroup {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(imageModel, { imageModel = it }, stringResource(R.string.assistant_image_model), "gpt-image-1")
                Field(ttsModel, { ttsModel = it }, stringResource(R.string.assistant_tts_model), "tts-1")
                if (ttsModel.isNotBlank()) Field(ttsVoice, { ttsVoice = it }, stringResource(R.string.assistant_tts_voice), "alloy")
                Field(videoModel, { videoModel = it }, stringResource(R.string.assistant_video_model), stringResource(R.string.assistant_video_later))
            }
        }
        OptionNote(stringResource(R.string.assistant_skills_note))

        OptionSection(stringResource(R.string.assistant_perms))
        OptionGroup {
            OptionSwitchRow(
                stringResource(R.string.assistant_perm_share), stringResource(R.string.assistant_perm_share_hint),
                AssistantPerm.SHARE in perms, { perms = if (it) perms + AssistantPerm.SHARE else perms - AssistantPerm.SHARE },
            )
            OptionDivider()
            OptionSwitchRow(
                stringResource(R.string.assistant_perm_connection), stringResource(R.string.assistant_perm_connection_hint),
                AssistantPerm.CONNECTION in perms, { perms = if (it) perms + AssistantPerm.CONNECTION else perms - AssistantPerm.CONNECTION },
            )
            OptionDivider()
            OptionSwitchRow(
                stringResource(R.string.assistant_perm_web), stringResource(R.string.assistant_perm_web_hint),
                AssistantPerm.WEB in perms, { perms = if (it) perms + AssistantPerm.WEB else perms - AssistantPerm.WEB },
            )
            if (AssistantPerm.WEB in perms) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    Field(searchKey, { searchKey = it }, stringResource(R.string.assistant_search_key), "https://…\ntvly-…",
                        secret = searchKey.lines().any { it.isNotBlank() && !it.trim().startsWith("https://") },
                        singleLine = false)
                    Text(stringResource(R.string.assistant_search_key_hint), color = cs.textMuted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            OptionDivider()
            OptionSwitchRow(
                stringResource(R.string.assistant_perm_themes), stringResource(R.string.assistant_perm_themes_hint),
                AssistantPerm.THEMES in perms, { perms = if (it) perms + AssistantPerm.THEMES else perms - AssistantPerm.THEMES },
            )
            OptionDivider()
            OptionRow(
                title = stringResource(R.string.assistant_perm_chats),
                subtitle = stringResource(R.string.assistant_perm_chats_hint),
                chevron = false,
                trailing = { Text("🔒", fontSize = 16.sp) },
            )
        }
        OptionNote(stringResource(R.string.assistant_privacy_note))
        } // not a council

        OptionSection(stringResource(R.string.assistant_memory))
        OptionGroup {
            OptionSwitchRow(stringResource(R.string.assistant_memory_on), stringResource(R.string.assistant_memory_hint), memoryOn, { memoryOn = it })
            val facts = memory[id].orEmpty()
            facts.asReversed().forEach { f ->
                OptionDivider()
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.text, color = AlphaTheme.colorScheme.textPrimary, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                    Text(
                        "✕", color = AlphaTheme.colorScheme.textMuted, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { AssistantStore.forgetAt(id, f.at) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (facts.isNotEmpty()) {
                OptionDivider()
                Text(
                    stringResource(R.string.assistant_memory_clear), color = AlphaTheme.colorScheme.danger, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { clearingMemory = true }.padding(horizontal = 16.dp, vertical = 13.dp),
                )
            }
        }
        OptionNote(stringResource(if (memory[id].isNullOrEmpty()) R.string.assistant_memory_empty else R.string.assistant_memory_note))
        if (clearingMemory) AlertDialog(
            onDismissRequest = { clearingMemory = false },
            containerColor = AlphaTheme.colorScheme.bgSecondary,
            title = { Text(stringResource(R.string.assistant_memory_clear_q), color = AlphaTheme.colorScheme.textPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    AssistantStore.clearMemory(id)
                    AssistantStore.log(name, JournalEntry.Kind.ACT, "memory_cleared", "")
                    clearingMemory = false
                }) { Text(stringResource(R.string.assistant_memory_clear), color = AlphaTheme.colorScheme.danger) }
            },
            dismissButton = { TextButton(onClick = { clearingMemory = false }) { Text(stringResource(R.string.common_cancel), color = AlphaTheme.colorScheme.textMuted) } },
        )

        Spacer(Modifier.height(8.dp))
        OptionGroup {
            OptionRow(
                title = stringResource(R.string.assistant_save_open),
                icon = AlphaIcons.Outlined.Chat,
                onClick = {
                    if (council && members.size < 2) {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.assistant_council_need), android.widget.Toast.LENGTH_SHORT).show()
                        return@OptionRow
                    }
                    val p = current().let { if (it.name.isBlank()) it.copy(name = ctx.getString(if (council) R.string.assistant_council else R.string.assistant_title)) else it }
                    AssistantStore.save(p, key.trim())
                    if (!council) AssistantStore.setSearchKey(searchKey)
                    scope.launch {
                        runCatching { repository.ensureAssistantChat(p.id, p.name) }
                        onOpenChat(p.chatId)
                    }
                },
            )
            if (existing != null) {
                OptionDivider()
                OptionRow(title = stringResource(R.string.assistant_delete), icon = AlphaIcons.Outlined.Delete, danger = true, onClick = { confirmDelete = true })
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = cs.bgSecondary,
            title = { Text(stringResource(R.string.assistant_delete_q), color = cs.textPrimary) },
            text = { Text(stringResource(R.string.assistant_delete_text), color = cs.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    AssistantStore.delete(existing.id)
                    scope.launch {
                        runCatching { repository.assistantConversations(existing.id).forEach { repository.deleteChat(it.chatId) } }
                        runCatching { repository.deleteChat(existing.chatId) }
                        onBack()
                    }
                }) { Text(stringResource(R.string.common_delete), color = cs.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel), color = cs.textMuted) } },
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    singleLine: Boolean = true,
) {
    val cs = AlphaTheme.colorScheme
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = cs.textMuted) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = !secret && type == KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = cs.textPrimary, unfocusedTextColor = cs.textPrimary,
            focusedBorderColor = cs.accent, unfocusedBorderColor = cs.alphaBorder,
            focusedLabelColor = cs.accent, unfocusedLabelColor = cs.textMuted, cursorColor = cs.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun errorTexts(): Map<AssistantError.Kind, String> = mapOf(
    AssistantError.Kind.NO_NETWORK to stringResource(R.string.assistant_err_network),
    AssistantError.Kind.TIMEOUT to stringResource(R.string.assistant_err_timeout),
    AssistantError.Kind.TOR_DOWN to stringResource(R.string.assistant_err_tor),
    AssistantError.Kind.BAD_KEY to stringResource(R.string.assistant_err_key),
    AssistantError.Kind.NOT_FOUND to stringResource(R.string.assistant_err_not_found),
    AssistantError.Kind.RATE_LIMIT to stringResource(R.string.assistant_err_rate),
    AssistantError.Kind.SERVER to stringResource(R.string.assistant_err_server),
    AssistantError.Kind.BAD_ADDRESS to stringResource(R.string.assistant_err_address_text),
    AssistantError.Kind.BAD_REQUEST to stringResource(R.string.assistant_err_bad_request),
)

/** What the assistants did: facts only (never message text), newest first. */
@Composable
fun AssistantJournalScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    AssistantStore.init(ctx)
    val cs = AlphaTheme.colorScheme
    val entries by AssistantStore.journal.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
    OptionScreen(
        title = stringResource(R.string.assistant_journal),
        onBack = onBack,
        actions = {
            if (entries.isNotEmpty()) Text(
                stringResource(R.string.assistant_journal_clear), color = cs.accent, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { AssistantStore.clearJournal() }.padding(10.dp),
            )
        },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                null to stringResource(R.string.assistant_journal_all),
                JournalEntry.Kind.ACT.name to stringResource(R.string.assistant_journal_actions),
                JournalEntry.Kind.ERR.name to stringResource(R.string.assistant_journal_errors),
                JournalEntry.Kind.PERM.name to stringResource(R.string.assistant_journal_perms),
            ).forEach { (k, label) ->
                val on = filter == k
                Text(
                    label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (on) Color.White else cs.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) cs.accent else cs.bgSecondary)
                        .clickable { filter = k }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        val shown = entries.filter { filter == null || it.kind.name == filter }
        if (shown.isEmpty()) {
            OptionNote(stringResource(R.string.assistant_journal_empty))
        } else OptionGroup {
            shown.forEachIndexed { i, e ->
                if (i > 0) OptionDivider(inset = false)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    val dot = when (e.kind) {
                        JournalEntry.Kind.ERR -> Color(0xFFFF6B6E)
                        JournalEntry.Kind.PERM -> Color(0xFFFFC46B)
                        JournalEntry.Kind.WEB -> Color(0xFF9AB7FF)
                        JournalEntry.Kind.ACT -> Color(0xFF6FD3A0)
                    }
                    Text("●", color = dot, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(journalTitle(e), color = cs.textPrimary, fontSize = 14.5.sp)
                        Text(
                            listOf(fmt.format(java.util.Date(e.at)), e.profile, e.detail).filter { it.isNotBlank() }.joinToString(" · "),
                            color = cs.textMuted, fontSize = 12.5.sp,
                        )
                    }
                }
            }
        }
        OptionNote(stringResource(R.string.assistant_journal_note))
    }
}

@Composable
private fun journalTitle(e: JournalEntry): String = when {
    e.kind == JournalEntry.Kind.PERM -> {
        val perm = when (e.title.substringAfter(':')) {
            AssistantPerm.SHARE.name -> stringResource(R.string.assistant_perm_share)
            AssistantPerm.CONNECTION.name -> stringResource(R.string.assistant_perm_connection)
            AssistantPerm.THEMES.name -> stringResource(R.string.assistant_perm_themes)
            else -> stringResource(R.string.assistant_perm_web)
        }
        stringResource(if (e.title.startsWith("on:")) R.string.assistant_j_perm_on else R.string.assistant_j_perm_off, perm)
    }
    e.kind == JournalEntry.Kind.ERR -> stringResource(R.string.assistant_j_error, errorTexts()[runCatching { AssistantError.Kind.valueOf(e.title) }.getOrNull()] ?: e.title)
    e.title == "reply" -> stringResource(R.string.assistant_j_reply)
    e.title == "stopped" -> stringResource(R.string.assistant_j_stopped)
    e.title == "superseded" -> stringResource(R.string.assistant_j_superseded)
    e.title == "transcribe" -> stringResource(R.string.assistant_j_transcribe)
    e.title == "connection" -> stringResource(R.string.assistant_j_connection)
    e.title == "image" -> stringResource(R.string.assistant_j_image)
    e.title == "speak" -> stringResource(R.string.assistant_j_speak)
    e.title == "theme" -> stringResource(R.string.assistant_j_theme)
    e.title == "wallpaper" -> stringResource(R.string.assistant_j_wallpaper)
    e.title == "search" -> stringResource(R.string.assistant_j_search)
    e.title == "read" -> stringResource(R.string.assistant_j_read)
    e.title == "relays_check" -> stringResource(R.string.assistant_j_relays_check)
    e.title == "remember" -> stringResource(R.string.assistant_j_remember)
    e.title == "no_tools" -> stringResource(R.string.assistant_j_no_tools)
    e.title == "forget" -> stringResource(R.string.assistant_j_forget)
    e.title == "memory_cleared" -> stringResource(R.string.assistant_j_memory_cleared)
    e.title == "relays_plan" -> stringResource(R.string.assistant_j_relays_plan)
    e.title == "relays_applied" -> stringResource(R.string.assistant_j_relays_applied)
    else -> e.title
}
