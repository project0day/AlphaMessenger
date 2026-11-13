package com.alphamessenger.ui.onboarding

import com.alphamessenger.ui.icons.AlphaIcons
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.onboarding.RestoreViewModel.InputMode
import com.alphamessenger.ui.onboarding.RestoreViewModel.Step
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Restore: the key (12 words or an nsec) → the backup is found and fetched →
 * its password → the history is replayed. Each stage is visible; nothing
 * fails silently.
 */
@Composable
fun RestoreScreen(
    viewModel: RestoreViewModel,
    onBack: () -> Unit,
    onRestoreComplete: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val ctx = LocalContext.current
    SecureScreenEffect()

    LaunchedEffect(state.step) {
        if (state.step == Step.DONE) onRestoreComplete()
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) viewModel.importLocalContainer(bytes)
        }
    }
    val pickFile = { openDoc.launch(arrayOf("*/*")) }

    when (state.step) {
        Step.METHOD_SELECT, Step.ENTER_NSEC -> KeyStep(state, viewModel, onBack, pickFile)
        Step.LOOKUP, Step.DOWNLOAD -> {
            val notFound = state.step == Step.LOOKUP && !state.isLoading && !state.hasCloudBackup && state.error == null
            if (notFound) NotFoundStep(state, viewModel, onBack, pickFile) else ProgressStep(state, onRetry = viewModel::retryLookup)
        }
        Step.ENTER_PASSWORD -> PasswordStep(state, viewModel, onBack)
        Step.RESTORE, Step.DONE -> ProgressStep(state, onRetry = null)
    }
}

@Composable
private fun KeyStep(state: RestoreViewModel.UiState, vm: RestoreViewModel, onBack: () -> Unit, pickFile: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var focused by remember { mutableStateOf(0) }

    OnbScreen(
        title = stringResource(R.string.onb_restore_title),
        subtitle = stringResource(if (state.mode == InputMode.WORDS) R.string.onb_restore_sub_words else R.string.onb_restore_sub_key),
        step = 1,
        totalSteps = 2,
        onBack = onBack,
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OnbSecondaryButton(
                    stringResource(R.string.onb_restore_paste),
                    { clipboard.getText()?.text?.let(vm::pastePhrase) },
                    leading = AlphaIcons.Outlined.ContentPaste,
                )
                Box(Modifier.weight(1f)) {
                    OnbPrimaryButton(stringResource(R.string.common_continue), vm::submitNsec, enabled = state.canSubmitNsec, loading = state.isLoading)
                }
            }
            OnbTextButton(
                stringResource(R.string.onb_restore_from_file), pickFile,
                Modifier.align(Alignment.CenterHorizontally), leading = AlphaIcons.Outlined.FileOpen,
            )
        },
    ) {
        // Phrase / key switch.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface1).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(InputMode.WORDS to R.string.onb_restore_tab_words, InputMode.KEY to R.string.onb_restore_tab_key).forEach { (mode, label) ->
                val on = state.mode == mode
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (on) cs.surface2 else cs.surface1)
                        .clickable { vm.setMode(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), color = if (on) cs.textPrimary else cs.textMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (state.mode == InputMode.WORDS) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until 12).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { i ->
                            WordCell(
                                n = i + 1,
                                value = state.words[i],
                                bad = state.badWordIndex == i,
                                focused = focused == i,
                                onFocus = { focused = i },
                                onChange = { vm.onWordChange(i, it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            // Suggestions for the cell being typed (3 closest dictionary words).
            val typed = state.words.getOrNull(focused).orEmpty()
            val suggestions = remember(typed) {
                if (typed.length < 2 || typed in vm.wordlist) emptyList()
                else vm.wordlist.filter { it.startsWith(typed) }.take(3)
            }
            if (suggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        Box(
                            Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(cs.surface1)
                                .border(1.dp, cs.alphaBorder, RoundedCornerShape(20.dp))
                                .clickable {
                                    vm.onWordChange(focused, s)
                                    if (focused < 11) focused += 1
                                }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(s, color = cs.textPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        } else {
            OnbField(
                value = state.nsecInput,
                onValueChange = vm::onNsecChange,
                label = null,
                placeholder = "nsec1…",
                mono = true,
                singleLine = false,
            )
        }
        state.error?.let { OnbCallout(it, CalloutKind.Error) }
    }
}

@Composable
private fun WordCell(
    n: Int,
    value: String,
    bad: Boolean,
    focused: Boolean,
    onFocus: () -> Unit,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    val cs = AlphaTheme.colorScheme
    val border = when {
        bad -> cs.danger
        focused -> cs.accent
        else -> cs.alphaBorder
    }
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surface1)
            .border(if (bad || focused) 1.5.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(n.toString(), color = cs.textMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(20.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = if (bad) cs.danger else cs.textPrimary, fontSize = 16.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(cs.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocus() },
        )
    }
}

@Composable
private fun PasswordStep(state: RestoreViewModel.UiState, vm: RestoreViewModel, onBack: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    var revealed by remember { mutableStateOf(false) }
    OnbScreen(
        title = stringResource(R.string.onb_restore_pw_title),
        subtitle = stringResource(R.string.onb_restore_pw_sub),
        step = 2,
        totalSteps = 2,
        onBack = onBack,
        bottom = {
            OnbPrimaryButton(stringResource(R.string.onb_restore_restore), vm::submitPassword, enabled = state.canSubmitPassword, loading = state.isLoading)
        },
    ) {
        OnbCardRow(
            icon = AlphaIcons.Filled.Check,
            title = stringResource(R.string.onb_restore_found),
            note = state.npub.take(12) + "…" + state.npub.takeLast(6),
            iconTint = cs.success,
            iconBackground = cs.success.copy(alpha = 0.14f),
        )
        OnbField(
            value = state.passwordInput,
            onValueChange = vm::onPasswordChange,
            label = stringResource(R.string.onb_pw_label),
            password = true,
            revealed = revealed,
            error = state.error,
            trailing = {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { revealed = !revealed }, contentAlignment = Alignment.Center) {
                    Icon(
                        if (revealed) AlphaIcons.Outlined.VisibilityOff else AlphaIcons.Outlined.Visibility,
                        stringResource(if (revealed) R.string.common_hide else R.string.common_show),
                        tint = cs.textMuted, modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun NotFoundStep(state: RestoreViewModel.UiState, vm: RestoreViewModel, onBack: () -> Unit, pickFile: () -> Unit) {
    OnbScreen(
        title = stringResource(R.string.restore_cloud_not_found),
        subtitle = stringResource(R.string.restore_cloud_not_found_details, state.npub.takeLast(16)),
        onBack = onBack,
        bottom = {
            OnbPrimaryButton(stringResource(R.string.restore_import_file), pickFile)
            OnbTextButton(stringResource(R.string.restore_retry_search), vm::retryLookup, Modifier.align(Alignment.CenterHorizontally))
        },
    ) {}
}

private enum class StageState { Done, Active, Waiting }

/** The restore as a list of stages with ticks, a spinner on the current one. */
@Composable
private fun ProgressStep(state: RestoreViewModel.UiState, onRetry: (() -> Unit)?) {
    val cs = AlphaTheme.colorScheme
    val order = listOf(Step.LOOKUP, Step.DOWNLOAD, Step.ENTER_PASSWORD, Step.RESTORE, Step.DONE)
    val at = order.indexOf(state.step).coerceAtLeast(0)
    fun stage(i: Int) = when {
        at > i || state.step == Step.DONE -> StageState.Done
        at == i -> StageState.Active
        else -> StageState.Waiting
    }
    val stages = listOf(
        R.string.onb_restore_step_keys to StageState.Done,
        R.string.onb_restore_step_find to stage(0),
        R.string.onb_restore_step_download to stage(1),
        R.string.onb_restore_step_decrypt to stage(3),
    )
    OnbScreen(
        title = stringResource(R.string.onb_restore_progress_title),
        subtitle = stringResource(R.string.onb_restore_progress_sub),
        bottom = if (state.error != null && onRetry != null) ({
            OnbPrimaryButton(stringResource(R.string.restore_retry_search), onRetry)
        }) else null,
    ) {
        Column {
            stages.forEachIndexed { index, (label, st) ->
                Row(Modifier.heightIn(min = 60.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (st == StageState.Done) cs.accent else cs.bgPrimary)
                                .border(
                                    if (st == StageState.Done) 0.dp else 2.dp,
                                    if (st == StageState.Active) cs.accent else cs.alphaBorder,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (st) {
                                StageState.Done -> Icon(AlphaIcons.Filled.Check, null, tint = cs.onAccent(), modifier = Modifier.size(16.dp))
                                StageState.Active -> CircularProgressIndicator(Modifier.size(16.dp), color = cs.accent, strokeWidth = 2.dp)
                                StageState.Waiting -> Unit
                            }
                        }
                        if (index < stages.lastIndex) {
                            Box(Modifier.width(2.dp).height(32.dp).background(if (st == StageState.Done) cs.accent else cs.alphaBorder))
                        }
                    }
                    Text(
                        stringResource(label),
                        color = if (st == StageState.Waiting) cs.textMuted else cs.textPrimary,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        state.error?.let { OnbCallout(it, CalloutKind.Error) }
        OnbCallout(stringResource(R.string.onb_restore_keep_open))
    }
}
