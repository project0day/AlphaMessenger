package com.alphamessenger.calls

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.alphamessenger.R
import com.alphamessenger.data.NetworkPrivacyPrefs
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * Calls cannot go through Tor, so in private mode they are relay-only (see
 * [NetworkPrivacyPrefs.callsMustRelay]). Without a TURN server such a call
 * could never connect: it is refused up front, with the reason.
 */
object TorCallWarning {
    fun needed(context: Context): Boolean =
        NetworkPrivacyPrefs(context).let { it.torEnabled && it.turnUrl.isBlank() }
}

@Composable
fun TorCallWarningDialog(onDismiss: () -> Unit) {
    val colors = AlphaTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(R.string.call_tor_title), color = colors.textPrimary) },
        text = { Text(stringResource(R.string.call_tor_body), color = colors.textSecondary) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.call_tor_confirm), color = colors.accent) } },
    )
}
