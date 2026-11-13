package com.alphamessenger.ui.onboarding

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Step 1 of 5: the secret phrase. 12 words in two columns, hidden until the
 * user asks to see them (someone may be looking over the shoulder), and
 * screenshots are blocked. An account without a phrase shows its nsec.
 */
@Composable
fun SeedBackupScreen(
    viewModel: OnboardingViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val cs = AlphaTheme.colorScheme
    SecureScreenEffect()
    var revealed by remember { mutableStateOf(false) }
    val words = uiState.mnemonic

    OnbScreen(
        title = stringResource(if (words != null) R.string.onb_phrase_title else R.string.onb_key_title),
        subtitle = stringResource(if (words != null) R.string.onb_phrase_sub else R.string.onb_key_sub),
        step = 1,
        onBack = onBack,
        bottom = {
            OnbPrimaryButton(
                text = stringResource(R.string.onb_phrase_done),
                onClick = onContinue,
                enabled = revealed,
            )
        },
    ) {
        if (words != null) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface1)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    words.chunked(2).forEachIndexed { row, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEachIndexed { col, word ->
                                WordChip(row * 2 + col + 1, if (revealed) word else "•••••", Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (!revealed) {
                    Column(
                        Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(18.dp))
                            .background(cs.surface1.copy(alpha = 0.92f))
                            .clickable { revealed = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(AlphaIcons.Outlined.Visibility, null, tint = cs.accent, modifier = Modifier.size(28.dp))
                        Text(
                            stringResource(R.string.onb_phrase_tap_reveal),
                            color = cs.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        } else {
            // No phrase (it could not be made): the nsec is the only key.
            val nsec = uiState.nsec.orEmpty()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface1).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (revealed) nsec else nsec.take(8) + "…" + nsec.takeLast(4),
                    color = cs.textPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OnbSecondaryButton(
                        stringResource(R.string.common_show), { revealed = true },
                        Modifier.weight(1f), leading = AlphaIcons.Outlined.Visibility,
                    )
                    OnbSecondaryButton(
                        stringResource(R.string.common_copy), { com.alphamessenger.util.SecretClipboard.copy(ctx, "nsec", nsec); revealed = true },
                        Modifier.weight(1f), leading = AlphaIcons.Outlined.ContentCopy,
                    )
                }
            }
        }
        OnbCallout(stringResource(R.string.onb_phrase_warn), CalloutKind.Warn)
    }
}

@Composable
private fun WordChip(n: Int, word: String, modifier: Modifier) {
    val cs = AlphaTheme.colorScheme
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.surface2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            n.toString(), color = cs.textMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(20.dp),
        )
        Text(word, color = cs.textPrimary, fontSize = 16.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 10.dp))
    }
}
