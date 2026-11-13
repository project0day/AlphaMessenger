package com.alphamessenger.ui.onboarding

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/** Welcome: who we are in three lines, then create or restore. */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onNavigateToSeedBackup: () -> Unit,
    onNavigateToRestore: () -> Unit,
    /** The account lives on another phone: link this one to it. */
    onLinkPhone: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val cs = AlphaTheme.colorScheme
    OnboardingNavBarEffect()

    LaunchedEffect(uiState.navigateToSeedBackup) {
        if (uiState.navigateToSeedBackup) { onNavigateToSeedBackup(); viewModel.onNavigationConsumed() }
    }
    LaunchedEffect(uiState.navigateToRestore) {
        if (uiState.navigateToRestore) { onNavigateToRestore(); viewModel.onNavigationConsumed() }
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it); viewModel.clearError() }
    }

    var languageOpen by remember { mutableStateOf(false) }
    if (languageOpen) LanguageDialog(onDismiss = { languageOpen = false })
    var torDialog by remember { mutableStateOf(false) }
    if (torDialog) {
        com.alphamessenger.ui.components.TorToggleDialog(
            enable = !com.alphamessenger.data.EmbeddedTor.enabled.value,
            setCore = { viewModel.setTorEnabled(it) },
            onDismiss = { torDialog = false },
        )
    }

    Box(Modifier.fillMaxSize().background(cs.bgPrimary)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                // Tor before anything else: creating or restoring then goes through it.
                val torState by com.alphamessenger.data.EmbeddedTor.state.collectAsState()
                val torOn by com.alphamessenger.data.EmbeddedTor.enabled.collectAsState()
                Row(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(cs.bgSecondary)
                        .border(1.dp, cs.alphaBorder, RoundedCornerShape(18.dp))
                        .clickable { torDialog = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.alphamessenger.ui.components.OnionIcon(com.alphamessenger.ui.components.torIndicatorColor(), 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            when {
                                !torOn -> R.string.onb_tor_off
                                torState == com.alphamessenger.data.EmbeddedTor.State.ON -> R.string.onb_tor_on
                                else -> R.string.onb_tor_connecting
                            }
                        ),
                        color = cs.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
                Row(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(cs.bgSecondary)
                        .border(1.dp, cs.alphaBorder, RoundedCornerShape(18.dp))
                        .clickable { languageOpen = true }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(currentLanguageLabel(), color = cs.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    Icon(AlphaIcons.Outlined.KeyboardArrowDown, null, tint = cs.textSecondary, modifier = Modifier.size(16.dp))
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            ) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(cs.accent),
                    contentAlignment = Alignment.Center,
                ) { AppLogoIcon(Modifier.size(64.dp)) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.onb_welcome_title),
                        color = cs.textPrimary, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.onb_welcome_sub), color = cs.textSecondary, fontSize = 16.sp, lineHeight = 23.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Promise(AlphaIcons.Outlined.PhonelinkErase, R.string.onb_promise1_t, R.string.onb_promise1_d)
                    Promise(AlphaIcons.Outlined.Lock, R.string.onb_promise2_t, R.string.onb_promise2_d)
                    Promise(AlphaIcons.Outlined.Hub, R.string.onb_promise3_t, R.string.onb_promise3_d)
                }
                Spacer(Modifier.height(8.dp))
            }

            Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnbPrimaryButton(
                    text = stringResource(R.string.onboarding_create_account),
                    onClick = { viewModel.createIdentity() },
                    loading = uiState.isLoading,
                )
                OnbSecondaryButton(
                    text = stringResource(R.string.onb_have_account),
                    onClick = { viewModel.requestRestore() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading,
                )
                if (LinkFlow.LINKING_ENABLED) OnbTextButton(
                    text = stringResource(R.string.onb_link_phone),
                    onClick = onLinkPhone,
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    leading = AlphaIcons.Outlined.QrCode,
                )
                Text(
                    stringResource(R.string.onb_footer),
                    color = cs.textMuted, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                )
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun Promise(icon: ImageVector, title: Int, note: Int) {
    val cs = AlphaTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        OnbIconTile(icon, cs.accent, cs.bgSecondary, size = 40)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), color = cs.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(note), color = cs.textMuted, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

private val LANGUAGES = listOf("" to 0, "en" to 1, "ru" to 2, "fr" to 3, "es" to 4)

@Composable
private fun languageName(tag: String): String = when (tag) {
    "en" -> "English"
    "ru" -> "Русский"
    "fr" -> "Français"
    "es" -> "Español"
    else -> stringResource(R.string.language_system)
}

@Composable
private fun currentLanguageLabel(): String {
    val tags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
    return languageName(tags.substringBefore(',').substringBefore('-'))
}

/** Same switch as Settings → Language; the app restarts its UI in the new language. */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
        .substringBefore(',').substringBefore('-')
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.bgSecondary,
        title = { Text(stringResource(R.string.settings_language_title), color = cs.textPrimary) },
        text = {
            Column {
                LANGUAGES.forEach { (tag, _) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val list = if (tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                                else androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(list)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OnbRadio(selected = tag == current)
                        Spacer(Modifier.width(14.dp))
                        Text(languageName(tag), color = cs.textPrimary, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close), color = cs.accent) }
        },
    )
}
