package com.alphamessenger.ui.contacts

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.onboarding.CalloutKind
import com.alphamessenger.ui.onboarding.OnbCallout
import com.alphamessenger.ui.onboarding.OnbCardRow
import com.alphamessenger.ui.onboarding.OnbPrimaryButton
import com.alphamessenger.ui.onboarding.OnbScreen
import com.alphamessenger.ui.onboarding.OnbSecondaryButton
import com.alphamessenger.ui.onboarding.OnbTextButton
import com.alphamessenger.ui.onboarding.surface1
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Safety number: the same 60 digits on both phones. Compare them in person,
 * or scan the other phone's QR — a match marks the contact verified.
 */
@Composable
fun VerifyScreen(
    viewModel: VerifyViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val cs = AlphaTheme.colorScheme
    var scanning by remember { mutableStateOf(false) }

    if (scanning) {
        com.alphamessenger.ui.chatlist.QrScannerSheet(
            colors = cs,
            onResult = { code -> scanning = false; viewModel.onScanned(code) },
            onDismiss = { scanning = false },
            // A safety number is digits (in groups): the contact-QR rule would read it past.
            accept = { code -> code.count { it.isDigit() } >= SAFETY_DIGITS_MIN && code.all { it.isDigit() || it.isWhitespace() } },
        )
    }

    OnbScreen(
        title = stringResource(R.string.verify_title),
        subtitle = stringResource(R.string.verify_sub),
        onBack = onBack,
        bottom = {
            if (state.safetyNumber.isNotBlank()) {
                OnbSecondaryButton(
                    stringResource(R.string.verify_scan), { viewModel.clearScan(); scanning = true },
                    Modifier.fillMaxWidth(), leading = AlphaIcons.Outlined.QrCodeScanner,
                )
                if (state.isVerified) {
                    OnbTextButton(stringResource(R.string.verify_unmark), { viewModel.setVerified(false) }, Modifier.align(Alignment.CenterHorizontally))
                } else {
                    OnbPrimaryButton(stringResource(R.string.verify_mark), { viewModel.setVerified(true) })
                }
            }
        },
    ) {
        when {
            state.isLoading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.accent)
            }
            state.safetyNumber.isBlank() -> OnbCallout(stringResource(R.string.verify_unavailable), CalloutKind.Info)
            else -> {
                if (state.isVerified) {
                    OnbCardRow(
                        icon = AlphaIcons.Outlined.VerifiedUser,
                        title = stringResource(R.string.verify_verified),
                        note = stringResource(R.string.verify_verified_note),
                        iconTint = cs.success,
                        iconBackground = cs.success.copy(alpha = 0.14f),
                    )
                }
                if (state.keyChanged) OnbCallout(stringResource(R.string.verify_key_changed), CalloutKind.Warn)
                // The chat's own key: signed by the account, or made before keys were signed
                // (then the compared code is what vouches for the account).
                OnbCallout(
                    stringResource(when {
                        state.proven -> R.string.verify_proven
                        state.isVerified -> R.string.verify_unproven_verified
                        else -> R.string.verify_unproven
                    }),
                    CalloutKind.Info,
                )
                when (state.scanMatch) {
                    true -> OnbCallout(stringResource(R.string.verify_scan_match), CalloutKind.Info)
                    false -> OnbCallout(stringResource(R.string.verify_scan_mismatch), CalloutKind.Error)
                    null -> Unit
                }
                val qr = remember(state.safetyNumber) {
                    com.alphamessenger.ui.chatlist.generateQrBitmap(state.safetyNumber, 640)?.asImageBitmap()
                }
                if (qr != null) {
                    Box(
                        Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp),
                    ) { Image(qr, stringResource(R.string.verify_title), Modifier.size(200.dp)) }
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface1).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    state.safetyNumber.split(' ').chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { group ->
                                Text(
                                    group, color = cs.textPrimary, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The shortest safety number taken from a scan: far longer than any stray number in a QR. */
private const val SAFETY_DIGITS_MIN = 30
