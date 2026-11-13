package com.alphamessenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.alphamessenger.R
import kotlinx.coroutines.delay

/**
 * Network status strip, like the one Chrome shows at the bottom of a page.
 *
 * Why it exists: sending a large video on a dead connection looks exactly
 * like a merely slow upload — the same spinning ring. The user can't tell
 * them apart and starts pressing buttons. The strip answers that question
 * before it comes up.
 *
 * Deliberately asymmetric:
 *  - no connection — the strip stays for as long as that's true;
 *  - connection back — a brief green confirmation, then gone.
 *
 * A permanent "all good" would say nothing: the normal state needs no
 * reminder.
 */
@Composable
fun NetworkBanner(
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    // The first value doesn't count as a change: on app start with network
    // there is no reason to show "connected" — nothing happened.
    var seenOffline by remember { mutableStateOf(false) }
    var showRestored by remember { mutableStateOf(false) }

    LaunchedEffect(online) {
        if (!online) {
            seenOffline = true
            showRestored = false
        } else if (seenOffline) {
            showRestored = true
            delay(RESTORED_MS)
            showRestored = false
        }
    }

    AnimatedVisibility(
        visible = !online || showRestored,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        val offline = !online
        // Only the BOTTOM system bar is tinted: the background tucks under the
        // navigation bar, and the label sits in the narrow strip above it. A big
        // half-screen plate would be wrong here — network state is background
        // information, not an event worth pushing the conversation aside.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (offline) OFFLINE else RESTORED),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(vertical = 4.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (offline) stringResource(R.string.network_banner_offline) else stringResource(R.string.network_banner_restored),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private val OFFLINE = Color(0xFFD32F2F)
private val RESTORED = Color(0xFF2E7D32)

/** How long the green confirmation stays. Longer gets annoying. */
private const val RESTORED_MS = 2000L
