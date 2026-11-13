package com.alphamessenger.ui.stories

import com.alphamessenger.ui.icons.AlphaIcons
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.components.OptionSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * New story: a text on a gradient, or a photo / video from the gallery (our
 * copy, cleaned of location/date/device). Who sees it, how long it lives,
 * replies and screenshots are chosen in one sheet and remembered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryCreateScreen(viewModel: StoriesViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val st by viewModel.state.collectAsState()
    BlackSystemBars()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.loadContacts() }

    var kind by remember { mutableStateOf("text") }
    var localPath by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var bg by remember { mutableStateOf(StoriesViewModel.BACKGROUNDS.first()) }
    var font by remember { mutableStateOf("sans") }
    var showBgPicker by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(listOf<StorySticker>()) }
    var showEmoji by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("stories_audience", android.content.Context.MODE_PRIVATE) }
    var audience by remember { mutableStateOf(runCatching { StoryAudience.valueOf(prefs.getString("mode", "CONTACTS")!!) }.getOrDefault(StoryAudience.CONTACTS)) }
    var ttl by remember { mutableIntStateOf(prefs.getInt("ttl", 24)) }
    var allowReplies by remember { mutableStateOf(prefs.getBoolean("replies", true)) }
    var allowShots by remember { mutableStateOf(prefs.getBoolean("shots", false)) }
    var showOptions by remember { mutableStateOf(false) }
    // Picked people per list (close friends / except / only), kept on this phone.
    var picked by remember(audience) { mutableStateOf(viewModel.savedList(audience.name)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            val file = withContext(Dispatchers.IO) { com.alphamessenger.ui.chat.AttachmentCache.copyToCache(ctx, uri) }
            if (file != null) { localPath = file.absolutePath; kind = if (mime.startsWith("video/")) "video" else "image" }
        }
    }
    // Leaving with something made asks first — a stray "back" must not lose it.
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = text.isNotBlank() || localPath != null || caption.isNotBlank() || stickers.isNotEmpty()
    val tryClose: () -> Unit = { if (dirty && !st.publishing) confirmDiscard = true else onClose() }
    BackHandler { tryClose() }
    if (confirmDiscard) {
        val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = cs.bgSecondary,
            title = { Text(stringResource(R.string.stories_discard_title), color = cs.textPrimary) },
            text = { Text(stringResource(R.string.stories_discard_text), color = cs.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDiscard = false; onClose() }) {
                    Text(stringResource(R.string.stories_discard_yes), color = cs.danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.stories_discard_no), color = cs.accent)
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // --- Canvas ---
        Box(
            Modifier.fillMaxSize().padding(bottom = 132.dp).clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                // The colour backdrop is the text story's. Under a photo or video it
                // would show as a thin coloured rim along the rounded corners
                // (the antialiased clip edge lets the backdrop through).
                .background(if (kind == "text") Brush.linearGradient(bg.map { Color(it) }) else SolidColor(Color.Black))
                // Stickers move / resize from here: the area sees every touch,
                // the text field and buttons keep theirs.
                .stickerGestures(stickers) { i, st -> stickers = stickers.toMutableList().also { it[i] = st } },
            contentAlignment = Alignment.Center,
        ) {
            when (kind) {
                "text" -> {
                    if (text.isEmpty()) Text(stringResource(R.string.stories_text_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    BasicTextField(
                        value = text, onValueChange = { text = it.take(700) },
                        textStyle = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 38.sp,
                            fontFamily = StoriesViewModel.fontFamily(font)),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                "image" -> localPath?.let { AsyncImage(model = java.io.File(it), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color.Black)) }
                "video" -> localPath?.let { VideoPreview(it) }
            }
            // Emoji stickers: drag, pinch to resize, double tap to remove.
            StickerLayer(
                stickers, editable = true,
                onRemove = { i -> stickers = stickers.toMutableList().also { it.removeAt(i) } },
            )
        }

        // --- Top tools ---
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundTool(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tryClose)
            Spacer(Modifier.weight(1f))
            // Tt: a text story; again → the next font.
            RoundTool(AlphaIcons.Outlined.TextFields, stringResource(R.string.stories_mode_text)) {
                if (kind != "text") { kind = "text"; localPath = null }
                else font = StoriesViewModel.FONTS[(StoriesViewModel.FONTS.indexOf(font) + 1) % StoriesViewModel.FONTS.size]
            }
            Spacer(Modifier.width(8.dp))
            if (kind == "text") {
                RoundTool(AlphaIcons.Outlined.Palette, stringResource(R.string.stories_background)) { showBgPicker = true }
                Spacer(Modifier.width(8.dp))
            }
            RoundTool(AlphaIcons.Outlined.EmojiEmotions, stringResource(R.string.stories_sticker)) { showEmoji = true }
            Spacer(Modifier.width(8.dp))
            RoundTool(AlphaIcons.Outlined.PhotoLibrary, stringResource(R.string.stories_mode_media)) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        }

        // --- Bottom: caption (media) + audience + time + publish ---
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp)) {
            if (kind != "text") {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    if (caption.isEmpty()) Text(stringResource(R.string.stories_caption_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                    BasicTextField(caption, { caption = it.take(500) }, textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color.White), maxLines = 3, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(AlphaIcons.Outlined.Group, audienceLabel(audience), Modifier.weight(1f, fill = false)) { showOptions = true }
                Spacer(Modifier.width(8.dp))
                Chip(AlphaIcons.Outlined.Schedule, stringResource(R.string.stories_hours, ttl)) { showOptions = true }
                Spacer(Modifier.width(8.dp))
                val canPublish = !st.publishing && when (kind) { "text" -> text.isNotBlank(); else -> localPath != null }
                Box(
                    Modifier.height(48.dp).widthIn(min = 112.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (canPublish) com.alphamessenger.ui.theme.AlphaTheme.colorScheme.accent else Color.White.copy(alpha = 0.15f))
                        .clickable(enabled = canPublish) {
                            val chats = viewModel.audienceChats(audience, picked)
                            if (chats.isEmpty()) {
                                // "All except" that leaves nobody says so plainly.
                                val why = if (audience == StoryAudience.EXCEPT && st.contacts.isNotEmpty()) R.string.stories_except_all
                                    else R.string.stories_no_audience
                                Toast.makeText(ctx, ctx.getString(why), Toast.LENGTH_LONG).show(); return@clickable
                            }
                            viewModel.publish(kind, localPath, text.trim(), bg, font, caption.trim(), ttl, chats, allowReplies, allowShots, stickers, audience, picked) { ok ->
                                Toast.makeText(ctx, ctx.getString(if (ok) R.string.stories_published else R.string.stories_publish_failed), Toast.LENGTH_SHORT).show()
                                if (ok) onClose()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (st.publishing) stringResource(R.string.stories_publishing) else stringResource(R.string.stories_publish),
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
        }
    }

    if (showEmoji) {
        val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { showEmoji = false }) {
            com.alphamessenger.ui.chat.v2.EmojiPanelV2(
                modifier = Modifier.fillMaxWidth().height(380.dp).navigationBarsPadding(),
                colors = cs,
                onPick = { e ->
                    if (stickers.size < 30) stickers = stickers + StorySticker(e, 0.5f, 0.42f, 1f)
                    showEmoji = false
                },
                onBackspace = {},
                showTabs = false,
            )
        }
    }

    if (showBgPicker) {
        GradientPickerSheet(
            current = bg,
            custom = st.customBgs,
            onPick = { bg = it },
            onSave = { viewModel.saveCustomBg(it) },
            onDelete = { viewModel.deleteCustomBg(it) },
            onDismiss = { showBgPicker = false },
        )
    }

    if (showOptions) {
        val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
        com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = {
            showOptions = false
            prefs.edit().putString("mode", audience.name).putInt("ttl", ttl).putBoolean("replies", allowReplies).putBoolean("shots", allowShots).apply()
            viewModel.saveList(audience.name, picked)
        }) {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                item {
                    StoryAudienceSection(audience, picked, st.contacts, onAudience = { audience = it }, onPicked = { picked = it })
                }
                item {
                    OptionSection(stringResource(R.string.stories_expires_in))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6, 12, 24, 48).forEach { h ->
                            val on = ttl == h
                            Box(
                                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(if (on) cs.accent else cs.bgSecondary).clickable { ttl = h },
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(R.string.stories_hours, h), color = if (on) Color.White else cs.textSecondary, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    OptionSection(stringResource(R.string.stories_section_options))
                    OptionGroup {
                        OptionSwitchRow(stringResource(R.string.stories_allow_replies), stringResource(R.string.stories_allow_replies_sub), allowReplies, { allowReplies = it })
                        OptionDivider()
                        OptionSwitchRow(stringResource(R.string.stories_allow_shots), stringResource(R.string.stories_allow_shots_sub), allowShots, { allowShots = it })
                    }
                    OptionNote(stringResource(R.string.stories_privacy_note))
                }
            }
        }
    }
}

@Composable
internal fun audienceLabel(a: StoryAudience): String = stringResource(
    when (a) {
        StoryAudience.CONTACTS -> R.string.stories_aud_contacts
        StoryAudience.CLOSE -> R.string.stories_aud_close
        StoryAudience.EXCEPT -> R.string.stories_aud_except
        StoryAudience.ONLY -> R.string.stories_aud_only
    },
)

@Composable
internal fun audienceSub(a: StoryAudience): String = stringResource(
    when (a) {
        StoryAudience.CONTACTS -> R.string.stories_aud_contacts_sub
        StoryAudience.CLOSE -> R.string.stories_aud_close_sub
        StoryAudience.EXCEPT -> R.string.stories_aud_except_sub
        StoryAudience.ONLY -> R.string.stories_aud_only_sub
    },
)

@Composable
private fun RoundTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f))) {
        Icon(icon, label, tint = Color.White)
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The picked video, playing muted in a loop. */
@Composable
private fun VideoPreview(path: String) {
    val ctx = LocalContext.current
    val player = remember(path) {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(ctx)).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(java.io.File(path))))
            volume = 0f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            prepare(); playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(path) { onDispose { player.release() } }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c -> androidx.media3.ui.PlayerView(c).apply { useController = false; this.player = player } },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}
