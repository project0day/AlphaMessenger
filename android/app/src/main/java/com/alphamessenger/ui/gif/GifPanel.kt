package com.alphamessenger.ui.gif

import com.alphamessenger.ui.icons.AlphaIcons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alphamessenger.R
import com.alphamessenger.ui.chat.bubbles.extractVideoThumb
import com.alphamessenger.ui.profile.FragmentStrip
import com.alphamessenger.ui.profile.PlayerSurface
import com.alphamessenger.ui.profile.VideoAvatarExport
import com.alphamessenger.ui.profile.rememberFragmentPlayer
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private enum class GifSource { SAVED, PACKS, CHATS }

/**
 * The GIF tab: search over the GIFs this phone has, three sources (saved,
 * Alpha packs, from chats) and a grid; a tap sends. Nothing is looked up
 * outside, so no service learns what is searched for.
 */
@Composable
fun GifPanel(
    colors: AlphaColorScheme,
    repo: com.alphamessenger.data.AlphaCoreRepository,
    onSend: (GifEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var source by rememberSaveable { mutableStateOf(GifSource.SAVED) }
    var making by remember { mutableStateOf<Uri?>(null) }
    var removing by remember { mutableStateOf<GifEntry?>(null) }
    val version by GifLibrary.version.collectAsState()
    val list by produceState(emptyList<GifEntry>(), source, version) {
        value = withContext(Dispatchers.IO) {
            when (source) {
                GifSource.SAVED -> GifLibrary.saved(ctx)
                GifSource.CHATS -> GifLibrary.fromChats(ctx)
                GifSource.PACKS -> com.alphamessenger.ui.feed.FeedStore.gifs(ctx, repo)
            }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) making = uri
    }

    Column(modifier.fillMaxWidth()) {
        // Sources.
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                GifSource.SAVED to R.string.gif_src_saved,
                GifSource.PACKS to R.string.gif_src_packs,
                GifSource.CHATS to R.string.gif_src_chats,
            ).forEach { (s, label) ->
                val on = s == source
                Box(
                    Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
                        .background(if (on) colors.accent.copy(alpha = 0.22f) else colors.bgTertiary)
                        .clickable { source = s }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), color = if (on) colors.textPrimary else colors.textSecondary,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val makeTile = source == GifSource.SAVED
        if (list.isEmpty() && !makeTile) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (source == GifSource.PACKS) R.string.gif_empty_packs else R.string.gif_empty_chats),
                    color = colors.textMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (makeTile) item(key = "make") {
                    Column(
                        Modifier.height(96.dp).clip(RoundedCornerShape(10.dp)).background(colors.bgTertiary)
                            .clickable { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(AlphaIcons.Outlined.Add, null, tint = colors.accent, modifier = Modifier.size(26.dp))
                        Text(stringResource(R.string.gif_make), color = colors.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
                items(list, key = { it.path }) { e -> GifTile(e, colors, onClick = { onSend(e) }, onLongClick = { removing = e }) }
            }
        }
        Text(stringResource(R.string.gif_private_note), color = colors.textMuted, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }

    making?.let { uri -> GifMaker(uri, colors, onDismiss = { making = null }, onSaved = { making = null; source = GifSource.SAVED }) }
    removing?.let { e ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.gif_remove_title), color = colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = { GifLibrary.remove(ctx, e); removing = null }) {
                    Text(stringResource(R.string.common_delete), color = colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GifTile(e: GifEntry, colors: AlphaColorScheme, onClick: () -> Unit, onLongClick: () -> Unit) {
    val poster by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, e.path) {
        value = extractVideoThumb(e.path)?.asImageBitmap()
    }
    Box(
        Modifier.height(96.dp).clip(RoundedCornerShape(10.dp)).background(colors.bgTertiary)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        poster?.let { Image(it, e.name.ifBlank { "GIF" }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Text("GIF", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

/**
 * A GIF from a video: drag the window (up to [GIF_MAX_MS]) along the frames,
 * name it for search, done — a small silent clip joins the saved GIFs.
 */
@Composable
private fun GifMaker(source: Uri, colors: AlphaColorScheme, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val duration by produceState(0L, source) { value = withContext(Dispatchers.IO) { VideoAvatarExport.durationMs(ctx, source) } }
    val size by produceState(0 to 0, source) { value = withContext(Dispatchers.IO) { GifLibrary.shownSize(ctx, source) } }
    // Length is the user's choice: 1 s up to [GIF_MAX_MS] (or the whole video
    // when shorter); the window on the strip follows it.
    val maxWindow = minOf(duration, GIF_MAX_MS).coerceAtLeast(1L)
    val minWindow = minOf(1_000L, maxWindow)
    var lengthMs by remember(duration) { mutableStateOf(minOf(duration, GIF_DEFAULT_MS).coerceAtLeast(1L)) }
    val window = lengthMs.coerceIn(minWindow, maxWindow)
    var startMs by remember { mutableStateOf(0L) }
    // A longer window must still fit: pull the start back if it would run past the end.
    androidx.compose.runtime.LaunchedEffect(window, duration) {
        if (duration > 0 && startMs + window > duration) startMs = (duration - window).coerceAtLeast(0L)
    }
    var busy by remember { mutableStateOf(false) }
    val player = rememberFragmentPlayer(source, startMs, window)
    val aspect = if (size.first > 0 && size.second > 0) size.first.toFloat() / size.second else 1f

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        // Edge to edge: the bars sit on the editor's own dark background.
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = false)
        Column(
            Modifier.fillMaxSize().background(Color(0xFF111214)).systemBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!busy) onDismiss() }) { Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_cancel), tint = Color.White) }
                Text(stringResource(R.string.gif_make_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(colors.accent)
                        .clickable(enabled = !busy && duration > 0 && size.first > 0) {
                            busy = true
                            scope.launch {
                                // Long side 480, the picture's own shape (even sides for the encoder).
                                val (w, h) = size
                                val scale = 480f / maxOf(w, h)
                                val ow = ((w * minOf(1f, scale)) / 2).roundToInt() * 2
                                val oh = ((h * minOf(1f, scale)) / 2).roundToInt() * 2
                                val tmp = File(ctx.cacheDir, "gif_make_${System.currentTimeMillis()}.mp4")
                                val out = VideoAvatarExport.export(
                                    ctx, source, startMs, startMs + window, tmp,
                                    presentation = androidx.media3.effect.Presentation.createForWidthAndHeight(
                                        ow, oh, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                                    ),
                                    bitrate = 700_000,
                                )
                                val ok = out != null && GifLibrary.save(ctx, out, "")
                                tmp.delete()
                                busy = false
                                android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.gif_saved else R.string.gif_make_failed),
                                    android.widget.Toast.LENGTH_SHORT).show()
                                if (ok) onSaved()
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else Text(stringResource(R.string.common_done), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                PlayerSurface(player, Modifier.heightIn(max = 420.dp).aspectRatio(aspect).clip(RoundedCornerShape(16.dp)))
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.video_avatar_fragment), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("%.1f – %.1f".format(startMs / 1000f, (startMs + window) / 1000f), color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                FragmentStrip(source, duration, window, startMs) { startMs = it }
                if (maxWindow > minWindow) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.gif_length), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("%.1f s".format(window / 1000f), color = Color.White, fontSize = 12.sp)
                    }
                    com.alphamessenger.ui.components.AlphaSlider(
                        value = window.toFloat(),
                        onValueChange = { lengthMs = (it / 100f).roundToInt() * 100L },
                        valueRange = minWindow.toFloat()..maxWindow.toFloat(),
                        enabled = !busy,
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = colors.accent, activeTrackColor = colors.accent,
                        ),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
