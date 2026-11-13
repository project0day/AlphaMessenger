package com.alphamessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.alphamessenger.R

/** Slate that sits calmly on light and dark themes alike. */
val GroupPlaceholderBg = Color(0xFF5B6474)
val GroupPlaceholderFg = Color(0xFFE9EDF3)

/**
 * A group or channel that has no picture yet: the ghost on a neutral disc,
 * instead of a letter (an emoji name turned into a broken glyph on a random
 * colour). Fills its parent; the caller clips it.
 */
@Composable
fun GroupAvatarPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(GroupPlaceholderBg), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_stat_alpha),
            contentDescription = null,
            tint = GroupPlaceholderFg,
            modifier = Modifier.fillMaxSize(0.58f),
        )
    }
}
