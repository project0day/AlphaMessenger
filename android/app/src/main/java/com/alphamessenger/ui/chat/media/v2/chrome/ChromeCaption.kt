package com.alphamessenger.ui.chat.media.v2.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R

/**
 * Media caption: collapsed — two lines, expanded — scrollable.
 *
 * The caller owns the expanded state so the whole bar can hide without
 * losing the reading position.
 */
@Composable
fun ChromeCaption(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        if (expanded) {
            // Height is capped by a screen fraction, not magic dp: a long
            // text would otherwise push the filmstrip off the bottom edge.
            val maxCaptionHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
            com.alphamessenger.ui.chat.text.MessageText(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                onTextTap = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxCaptionHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.chat_show_less),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
        } else {
            com.alphamessenger.ui.chat.text.MessageText(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                onTextTap = onToggle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
