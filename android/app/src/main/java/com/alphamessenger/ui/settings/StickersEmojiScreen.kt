package com.alphamessenger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionRow
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.emoji.AnimatedEmoji
import com.alphamessenger.ui.emoji.EmojiPrefs
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Stickers and emoji: when animations play, the animated emoji set on
 * this phone (open, remove, download) and my sticker packs (with archive import).
 */
@Composable
fun StickersEmojiScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = AlphaTheme.colorScheme
    EmojiPrefs.load(ctx)
    val ready by AnimatedEmoji.ready.collectAsState()
    val onPhone by produceState(0L to 0, ready) {
        value = withContext(Dispatchers.IO) { AnimatedEmoji.sizeOnPhone(ctx) to AnimatedEmoji.index(ctx).size }
    }
    var setOpen by remember { mutableStateOf(false) }
    var packsOpen by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    OptionScreen(title = stringResource(R.string.settings_stickers_emoji), onBack = onBack) {
        OptionSection(stringResource(R.string.sticker_size_section))
        OptionGroup {
            Box(Modifier.padding(12.dp)) { SizePreview(ctx) }
            SizeSlider(
                stringResource(R.string.sticker_size_stickers), EmojiPrefs.stickerDp, EmojiPrefs.STICKER_RANGE,
                EmojiPrefs.STICKER_DEFAULT, step = 10, onChange = EmojiPrefs::resizeStickers, onDone = { EmojiPrefs.persistSizes(ctx) },
            )
            OptionDivider()
            SizeSlider(
                stringResource(R.string.sticker_size_emoji), EmojiPrefs.emojiDp, EmojiPrefs.EMOJI_RANGE,
                EmojiPrefs.EMOJI_DEFAULT, step = 8, onChange = EmojiPrefs::resizeEmoji, onDone = { EmojiPrefs.persistSizes(ctx) },
            )
            if (EmojiPrefs.stickerDp != EmojiPrefs.STICKER_DEFAULT || EmojiPrefs.emojiDp != EmojiPrefs.EMOJI_DEFAULT) {
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.sticker_size_reset),
                    onClick = {
                        EmojiPrefs.resizeStickers(EmojiPrefs.STICKER_DEFAULT)
                        EmojiPrefs.resizeEmoji(EmojiPrefs.EMOJI_DEFAULT)
                        EmojiPrefs.persistSizes(ctx)
                    },
                )
            }
        }

        OptionSection(stringResource(R.string.emoji_play_section))
        OptionGroup {
            OptionChoiceRow(
                title = stringResource(R.string.emoji_play_tap),
                selected = !EmojiPrefs.autoPlay,
                onClick = { EmojiPrefs.setAutoPlay(ctx, false) },
            )
            OptionDivider()
            OptionChoiceRow(
                title = stringResource(R.string.emoji_play_auto),
                selected = EmojiPrefs.autoPlay,
                onClick = { EmojiPrefs.setAutoPlay(ctx, true) },
            )
        }

        OptionSection(stringResource(R.string.emoji_settings_section))
        OptionGroup {
            val (bytes, count) = onPhone
            if (count > 0) {
                OptionRow(
                    title = stringResource(R.string.emoji_set_title),
                    subtitle = stringResource(R.string.emoji_set_on_phone, count, android.text.format.Formatter.formatShortFileSize(ctx, bytes)),
                    onClick = { setOpen = true },
                )
                OptionDivider()
                OptionRow(
                    title = stringResource(R.string.emoji_set_remove),
                    subtitle = stringResource(R.string.emoji_set_remove_hint),
                    onClick = { AnimatedEmoji.remove(ctx) },
                )
            } else {
                OptionRow(
                    title = stringResource(if (fetching) R.string.emoji_set_loading else R.string.emoji_set_download),
                    subtitle = stringResource(R.string.emoji_set_download_hint),
                    onClick = {
                        val repo = AnimatedEmoji.repo
                        if (!fetching && repo != null) {
                            fetching = true
                            scope.launch { AnimatedEmoji.ensure(ctx.applicationContext, repo, force = true); fetching = false }
                        }
                    },
                )
            }
        }

        OptionSection(stringResource(R.string.sticker_settings_section))
        OptionGroup {
            OptionRow(
                title = stringResource(R.string.sticker_my_packs),
                subtitle = stringResource(R.string.sticker_settings_packs_hint),
                onClick = { packsOpen = true },
            )
        }
    }

    val repo = AnimatedEmoji.repo
    if (setOpen && repo != null) com.alphamessenger.ui.emoji.EmojiSetDialog(colors, repo, onDismiss = { setOpen = false })
    if (packsOpen && repo != null) com.alphamessenger.ui.sticker.MyPacksScreen(colors, repo, onDismiss = { packsOpen = false })
}

/**
 * The chat as it will look: a big emoji and a sticker on the chat's own background,
 * resized live by the sliders under them. The stage keeps the largest height, so the
 * sliders never move under the finger.
 */
@Composable
private fun SizePreview(ctx: android.content.Context) {
    val colors = AlphaTheme.colorScheme
    val sample by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            com.alphamessenger.ui.sticker.StickerStore.recent(ctx).firstOrNull()?.second?.file
                ?: run {
                    com.alphamessenger.ui.sticker.AlphaGhostPack.ensure(ctx)
                    com.alphamessenger.ui.sticker.StickerStore.packs(ctx)
                        .firstOrNull { it.id == com.alphamessenger.ui.sticker.AlphaGhostPack.ID }?.stickers?.getOrNull(1)?.file
                }
        }
    }
    val wallpaper = com.alphamessenger.ui.theme.Appearance.chatWallpaper
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.bgChat),
    ) {
        com.alphamessenger.ui.theme.ChatWallpaperBackground(wallpaper, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Box(Modifier.fillMaxWidth().height(EmojiPrefs.EMOJI_RANGE.last.dp), contentAlignment = Alignment.BottomStart) {
                com.alphamessenger.ui.emoji.AnimatedEmojiView(
                    "😂", EmojiPrefs.emojiDp.dp, Modifier.size(EmojiPrefs.emojiDp.dp), plays = 1,
                    fallback = { Text("😂", fontSize = (56 * EmojiPrefs.emojiDp / EmojiPrefs.EMOJI_DEFAULT).sp) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(EmojiPrefs.STICKER_RANGE.last.dp), contentAlignment = Alignment.BottomEnd) {
                val path = sample
                if (path != null) com.alphamessenger.ui.sticker.StickerImage(path, Modifier.size(EmojiPrefs.stickerDp.dp), 512)
            }
        }
    }
}

/** A size slider: snaps every [step] dp; the value shows against the default (100%). */
@Composable
private fun SizeSlider(title: String, value: Int, range: IntRange, default: Int, step: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    val colors = AlphaTheme.colorScheme
    // This Slider rebuilds its state whenever its callbacks change identity, which ends
    // the drag after one step: hand it callbacks that live as long as it does.
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    val onValue = remember { { v: Float -> change(v.roundToInt()) } }
    val onFinished = remember { { done() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("${value * 100 / default}%", color = colors.accent, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", color = colors.textMuted, fontSize = 12.sp)
            com.alphamessenger.ui.components.AlphaSlider(
                value = value.toFloat(),
                onValueChange = onValue,
                onValueChangeFinished = onFinished,
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first) / step - 1,
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent, activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.textMuted.copy(alpha = 0.3f),
                    activeTickColor = colors.bgSecondary, inactiveTickColor = colors.textMuted.copy(alpha = 0.6f),
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("A", color = colors.textMuted, fontSize = 20.sp)
        }
    }
}
