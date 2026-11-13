package com.alphamessenger.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/**
 * Deleting a chat. For a one-to-one, [peerName] non-null: a box deletes it at
 * the other side too, history and all.
 */
@Composable
fun DeleteChatDialog(
    title: String,
    body: String,
    colors: AlphaColorScheme,
    peerName: String?,
    onConfirm: (forBoth: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var forBoth by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(title, color = colors.textPrimary) },
        text = {
            Column {
                Text(body, color = colors.textSecondary)
                if (peerName != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { forBoth = !forBoth },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = forBoth,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = colors.danger),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_for_both_label, peerName), color = colors.textPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(forBoth) }) {
                Text(stringResource(R.string.common_delete), color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary) }
        },
    )
}
