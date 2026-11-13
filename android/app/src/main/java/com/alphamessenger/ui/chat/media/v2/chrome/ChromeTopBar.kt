package com.alphamessenger.ui.chat.media.v2.chrome

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R

/**
 * Viewer top bar.
 *
 * Knows only the text it is given and three events. It formats nothing,
 * touches no state, closes nothing: "back" is a callback, not a dismiss.
 *
 * The menu arrives as a slot, so the bar knows no media actions — only the
 * place where the menu opens.
 */
@Composable
fun ChromeTopBar(
    title: String,
    subtitle: String?,
    counter: String?,
    showMenuButton: Boolean,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Gradient, not a solid fill: the frame stays visible under the bar.
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.60f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChromeIconButton(onClick = onBack) {
                Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = Color.White)
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box {
                if (showMenuButton) {
                    ChromeIconButton(onClick = onMenu) {
                        Icon(AlphaIcons.Outlined.MoreVert, stringResource(R.string.common_more), tint = Color.White)
                    }
                }
                menu()
            }
        }
        if (counter != null) {
            Text(
                text = counter,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ChromeIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
    ) { content() }
}
