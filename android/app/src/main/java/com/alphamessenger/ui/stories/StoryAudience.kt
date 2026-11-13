package com.alphamessenger.ui.stories

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection

/*
 * Who sees my stories — one setting, used by the story editor (the default it
 * starts with) and by Privacy. "All contacts" and "all but…" also reach people
 * who become contacts while a story is up; the hand-picked lists do not grow
 * by themselves.
 */

private const val PREFS = "stories_audience"

/** The audience new stories start with. */
fun storyAudienceDefault(ctx: android.content.Context): StoryAudience =
    runCatching { StoryAudience.valueOf(ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("mode", "CONTACTS")!!) }
        .getOrDefault(StoryAudience.CONTACTS)

/** Label of the current default (Privacy row value). */
@Composable
fun storyAudienceLabelRes(): Int = when (storyAudienceDefault(LocalContext.current)) {
    StoryAudience.CONTACTS -> R.string.stories_aud_contacts
    StoryAudience.CLOSE -> R.string.stories_aud_close
    StoryAudience.EXCEPT -> R.string.stories_aud_except
    StoryAudience.ONLY -> R.string.stories_aud_only
}

/** "Who sees" choices and, for a list audience, the people in it. */
@Composable
internal fun StoryAudienceSection(
    audience: StoryAudience,
    picked: Set<String>,
    contacts: List<StoryContact>,
    onAudience: (StoryAudience) -> Unit,
    onPicked: (Set<String>) -> Unit,
) {
    val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
    Column {
        OptionSection(stringResource(R.string.stories_who_sees))
        OptionGroup {
            StoryAudience.entries.forEachIndexed { i, a ->
                if (i > 0) OptionDivider()
                OptionChoiceRow(audienceLabel(a), audienceSub(a), audience == a) { onAudience(a) }
            }
        }
        if (audience != StoryAudience.CONTACTS) {
            OptionSection(stringResource(if (audience == StoryAudience.EXCEPT) R.string.stories_hide_from else R.string.stories_show_to))
            OptionGroup {
                contacts.forEachIndexed { i, c ->
                    if (i > 0) OptionDivider()
                    val on = c.chatId in picked
                    Row(
                        Modifier.fillMaxWidth().clickable { onPicked(if (on) picked - c.chatId else picked + c.chatId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StoryAvatar(c.avatarPath, c.chatId, com.alphamessenger.ui.components.avatarInitial(c.name), 38)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name.ifBlank { stringResource(R.string.chat_member_fallback) }, color = cs.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(if (on) cs.accent else cs.bgTertiary),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(AlphaIcons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}

/** Privacy → Stories: the default audience and its lists, saved as they change. */
@Composable
fun StoryAudiencePage(onBack: () -> Unit) {
    val viewModel: StoriesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val st by viewModel.state.collectAsState()
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var audience by remember { mutableStateOf(storyAudienceDefault(ctx)) }
    var picked by remember(audience) { mutableStateOf(viewModel.savedList(audience.name)) }
    LaunchedEffect(Unit) { viewModel.loadContacts() }
    OptionScreen(stringResource(R.string.privacy_seen_stories), onBack = onBack) {
        Spacer(Modifier.height(4.dp))
        StoryAudienceSection(
            audience, picked, st.contacts,
            onAudience = { audience = it; prefs.edit().putString("mode", it.name).apply() },
            onPicked = { picked = it; viewModel.saveList(audience.name, it) },
        )
        OptionNote(stringResource(R.string.privacy_seen_stories_note))
    }
}
