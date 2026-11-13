package com.alphamessenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme

/*
 * A chat from someone who is not in our contacts waits in Requests. Until the
 * user answers, nothing goes back: no "read", no typing, no profile, no calls.
 */

/** In place of the composer: block, delete, or accept the chat. */
@Composable
fun ChatRequestBar(colors: AlphaColorScheme, onBlock: () -> Unit, onDelete: () -> Unit, onAccept: () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RequestButton(stringResource(R.string.request_block), colors.danger, colors.bgTertiary, Modifier.weight(1f), onBlock)
        RequestButton(stringResource(R.string.request_delete), colors.textPrimary, colors.bgTertiary, Modifier.weight(1f), onDelete)
        RequestButton(stringResource(R.string.request_accept), Color.White, colors.accent, Modifier.weight(1.3f), onAccept)
    }
}

@Composable
private fun RequestButton(label: String, fg: Color, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** At the top of a request's history: what the other side can and cannot see. */
@Composable
fun ChatRequestNotice(colors: AlphaColorScheme) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.request_notice),
            color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp))
                .background(colors.bgSecondary.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
