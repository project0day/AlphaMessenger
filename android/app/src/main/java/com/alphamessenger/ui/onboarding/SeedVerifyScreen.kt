package com.alphamessenger.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Step 2 of 5: proof the phrase was written down. Three random positions,
 * three candidates each (the right word + two from the dictionary). A wrong
 * pick just says so — the user can re-pick as often as needed.
 */
@Composable
fun SeedVerifyScreen(
    viewModel: OnboardingViewModel,
    wordlist: List<String>,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val cs = AlphaTheme.colorScheme
    SecureScreenEffect()
    val words = uiState.mnemonic
    if (words == null) {
        // No phrase to check (account kept its nsec): nothing to verify here.
        androidx.compose.runtime.LaunchedEffect(Unit) { onContinue() }
        return
    }

    // Positions and candidates are fixed for this visit of the screen.
    val questions = remember(words) {
        val rnd = java.security.SecureRandom()
        (0 until words.size).shuffled(rnd).take(3).sorted().map { index ->
            val decoys = wordlist.filter { it != words[index] && it !in words }.shuffled(rnd).take(2)
            index to (decoys + words[index]).shuffled(rnd)
        }
    }
    val picks = remember(words) { mutableStateMapOf<Int, String>() }
    val allRight = questions.all { (i, _) -> picks[i] == words[i] }

    OnbScreen(
        title = stringResource(R.string.onb_verify_title),
        subtitle = stringResource(R.string.onb_verify_sub),
        step = 2,
        onBack = onBack,
        bottom = {
            OnbPrimaryButton(stringResource(R.string.common_continue), onContinue, enabled = allRight)
            OnbTextButton(stringResource(R.string.onb_verify_back), onBack, Modifier.align(Alignment.CenterHorizontally))
        },
    ) {
        questions.forEach { (index, options) ->
            val picked = picks[index]
            val wrong = picked != null && picked != words[index]
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.onb_verify_word_n, index + 1), color = cs.textSecondary, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        val selected = option == picked
                        val border = when {
                            selected && wrong -> cs.danger
                            selected -> cs.accent
                            else -> cs.alphaBorder
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected && !wrong) cs.accent.copy(alpha = 0.12f) else cs.surface1)
                                .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(12.dp))
                                .clickable { picks[index] = option },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(option, color = cs.textPrimary, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                if (wrong) Text(stringResource(R.string.onb_verify_wrong), color = cs.danger, fontSize = 13.sp)
            }
        }
    }
}
