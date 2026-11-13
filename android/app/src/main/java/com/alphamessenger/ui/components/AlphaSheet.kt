package com.alphamessenger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alphamessenger.ui.theme.AlphaTheme

/**
 * The one bottom sheet of the app: the same surface colour everywhere, no drag
 * handle, one even dim behind it, and edge to edge — the sheet runs under the
 * navigation bar, so nothing of the screen behind shows through down there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlphaSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    /** Lift the content above the keyboard (off for a sheet that must stay put while it closes). */
    imeAware: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = cs.bgPrimary,
        contentColor = cs.textPrimary,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().then(if (imeAware) Modifier.imePadding() else Modifier),
            content = content,
        )
    }
}
