package com.alphamessenger.ui.chat.whisper

import com.alphamessenger.ui.icons.AlphaIcons
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import com.alphamessenger.R
import com.alphamessenger.data.WhisperMeta
import com.alphamessenger.data.WhisperSecret
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Whisper — read only under the finger.
 *
 * The secret lies dark; a circle of light follows the finger and shows only
 * what is under it, so a whole-screen capture is pointless (and the window is
 * secure anyway). Reading time counts only while the finger is down; when it
 * runs out — or the reader leaves after reading — it burns on both sides.
 */

/** The whisper's own colours: violet light on near-black, ash orange when burned. */
private val Violet = Color(0xFFA78BFA)
private val VioletDeep = Color(0xFF7C3AED)
private val VioletText = Color(0xFFC4B5FD)
private val Night = Color(0xFF06070B)
private val Ash = Color(0xFFF97316)

/** Reading limits offered when sending. */
val WHISPER_SECS = listOf(5, 15, 30, 60)

// ── In the chat ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhisperBubble(
    meta: WhisperMeta,
    colors: AlphaColorScheme,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val outgoing = meta.outgoing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        val shape = if (outgoing) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
        val tap = Modifier.combinedClickable(onClick = { if (!outgoing && !meta.burned) onOpen() }, onLongClick = onLongPress)
        when {
            outgoing -> Column(
                Modifier.widthIn(max = 270.dp).clip(shape)
                    .background(Brush.linearGradient(listOf(colors.msgOutStart, colors.msgOutEnd)))
                    .then(tap).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (meta.glance) GlanceTitle(glanceKindLabel(meta), colors.msgOutText)
                else WhisperTitle(stringResource(R.string.whisper_yours), colors.msgOutText)
                Text(
                    when {
                        meta.glance && meta.expired -> stringResource(R.string.glance_expired)
                        meta.glance && meta.burned -> stringResource(R.string.glance_opened)
                        meta.glance -> stringResource(R.string.glance_not_opened)
                        meta.expired -> stringResource(R.string.whisper_burned_unread)
                        meta.burned -> stringResource(R.string.whisper_read_for, meta.usedSecs ?: 0, meta.secs)
                        else -> stringResource(R.string.whisper_not_read_yet, meta.secs)
                    },
                    color = colors.msgOutText.copy(alpha = 0.92f), fontSize = 13.sp,
                )
                if (meta.shot) {
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.22f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AlphaIcons.Outlined.CameraAlt, null, tint = colors.msgOutText, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.whisper_shot_blocked), color = colors.msgOutText, fontSize = 12.sp)
                    }
                }
            }
            else -> {
                // Burned just now (the reader closed a moment ago): burn it on screen.
                val play = remember(meta.messageId, meta.burned) { meta.burned && WhisperBurnFx.consume(meta.messageId) }
                val burn = remember(meta.messageId) { Animatable(if (play) 0f else 1f) }
                LaunchedEffect(play) { if (play) burn.animateTo(1f, tween(1600, easing = LinearEasing)) }
                when {
                    !meta.burned -> SealedWhisper(meta, shape, tap)
                    burn.value >= 1f -> AshWhisper(meta, shape, tap)
                    else -> Box {
                        AshWhisper(meta, shape, tap, Modifier.graphicsLayer { alpha = (burn.value * 1.4f - 0.3f).coerceIn(0f, 1f) })
                        BurningOverlay(meta, shape, burn.value)
                    }
                }
            }
        }
    }
}


/** Message ids burned in this session whose bubble has not burned on screen yet. */
object WhisperBurnFx {
    private val pending = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun mark(messageId: String) { pending.add(messageId) }
    /** True once for a just-burned whisper: its bubble plays the burn. */
    fun consume(messageId: String): Boolean = pending.remove(messageId)
}

/** Sealed, with a slow light passing over it. */
@Composable
private fun SealedWhisper(meta: WhisperMeta, shape: RoundedCornerShape, tap: Modifier, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "seal")
    val p = t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "shine")
    val photo = meta.kind != "text"
    Column(
        modifier.width(if (photo) 230.dp else 250.dp).clip(shape)
            .background(if (photo) Color(0xFF120F1F) else Color(0xFF1B1730))
            .border(1.dp, Violet.copy(alpha = 0.35f), shape)
            .drawWithContent {
                drawContent()
                val x = size.width * p.value
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Violet.copy(alpha = 0.16f), Color.Transparent),
                        startX = x - 120f, endX = x + 120f,
                    ),
                )
            }
            .then(tap)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        horizontalAlignment = if (photo) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (meta.glance) {
            Spacer(Modifier.height(18.dp))
            Icon(
                if (meta.kind == "video") AlphaIcons.Outlined.Videocam else AlphaIcons.Outlined.Photo, null,
                tint = VioletText, modifier = Modifier.size(34.dp),
            )
            Text(glanceKindLabel(meta), color = VioletText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.glance_tap_to_open), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
        } else if (photo) {
            Spacer(Modifier.height(18.dp))
            Icon(AlphaIcons.Outlined.Photo, null, tint = VioletText, modifier = Modifier.size(34.dp))
            Text(stringResource(R.string.whisper_photo), color = VioletText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.whisper_tap_photo, meta.secs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
        } else {
            WhisperTitle(stringResource(R.string.whisper_text), VioletText)
            listOf(200, 160, 110).forEach { w ->
                Box(Modifier.width(w.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.10f)))
            }
            Text(stringResource(R.string.whisper_tap_text, meta.secs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

/** What is left: a warm, dark ember with a living flame — no frame, no dashes. */
@Composable
private fun AshWhisper(meta: WhisperMeta, shape: RoundedCornerShape, tap: Modifier, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ember")
    val f = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300), androidx.compose.animation.core.RepeatMode.Reverse), label = "flicker")
    Row(
        modifier.widthIn(max = 270.dp).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF26160D), Color(0xFF17171C), Color(0xFF141418))))
            .drawBehind {
                // The glow of the last ember at the flame.
                drawCircle(
                    Brush.radialGradient(listOf(Ash.copy(alpha = 0.22f + 0.12f * f.value), Color.Transparent), center = Offset(26.dp.toPx(), size.height / 2f), radius = 46.dp.toPx()),
                    radius = 46.dp.toPx(), center = Offset(26.dp.toPx(), size.height / 2f),
                )
            }
            .then(tap).padding(start = 12.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AlphaIcons.Outlined.LocalFireDepartment, null, tint = Ash,
            modifier = Modifier.size(22.dp).graphicsLayer {
                val k = 0.92f + 0.12f * f.value
                scaleX = k; scaleY = k; alpha = 0.75f + 0.25f * f.value
            },
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                stringResource(if (meta.glance) R.string.glance_opened else R.string.whisper_burned),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    when {
                        meta.glance && meta.expired -> R.string.glance_expired
                        meta.glance -> R.string.glance_gone_sub
                        meta.expired -> R.string.whisper_burned_unread
                        else -> R.string.whisper_burned_sub
                    },
                ),
                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
            )
        }
    }
}

/** The sealed bubble burning away from left to right: a fire edge, embers rising. */
@Composable
private fun BurningOverlay(meta: WhisperMeta, shape: RoundedCornerShape, p: Float) {
    val sparks = remember(meta.messageId) {
        val r = java.util.Random(meta.messageId.hashCode().toLong())
        List(26) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.4f, r.nextFloat()) }
    }
    Box(
        Modifier.drawWithContent {
            val edge = size.width * p
            // What has not burned yet.
            clipRect(left = edge) { this@drawWithContent.drawContent() }
            // The burning edge.
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Ash.copy(alpha = 0.85f), Color(0xFFFDE68A), Ash.copy(alpha = 0.6f), Color.Transparent),
                    startX = edge - 18.dp.toPx(), endX = edge + 10.dp.toPx(),
                ),
                topLeft = Offset(edge - 18.dp.toPx(), 0f), size = Size(28.dp.toPx(), size.height),
            )
            // Embers rising from what already burned.
            sparks.forEach { (x0, delay, speed, drift) ->
                val born = x0 * size.width
                if (born > edge) return@forEach
                val age = ((p - x0 * 0.9f - delay * 0.1f) * 3f * speed).coerceIn(0f, 1f)
                if (age <= 0f || age >= 1f) return@forEach
                val y = size.height * (0.85f - age * 1.4f)
                val x = born + (drift - 0.5f) * 30.dp.toPx() * age
                drawCircle(
                    (if (drift > 0.5f) Color(0xFFFDE68A) else Ash).copy(alpha = (1f - age) * 0.9f),
                    radius = (2.2f * (1f - age * 0.6f)).dp.toPx(), center = Offset(x, y),
                )
            }
        },
    ) { SealedWhisper(meta, shape, Modifier) }
}

/** "Photo · 5 s", "Photo · until closed", "Video · one view". */
@Composable
private fun glanceKindLabel(meta: WhisperMeta): String = when {
    meta.kind == "video" -> stringResource(R.string.glance_video_label)
    meta.secs == 0 -> stringResource(R.string.glance_photo_until_closed)
    else -> stringResource(R.string.glance_photo_label, meta.secs)
}

@Composable
private fun GlanceTitle(text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AlphaIcons.Outlined.ViewOnce, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WhisperTitle(text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WaveIcon(tint, 16.dp)
        Spacer(Modifier.width(7.dp))
        Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The whisper mark: a short sound wave. */
@Composable
fun WaveIcon(tint: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(3f * u, 12f * u); lineTo(6f * u, 12f * u); lineTo(9f * u, 5f * u)
            lineTo(13f * u, 19f * u); lineTo(16f * u, 12f * u); lineTo(21f * u, 12f * u)
        }
        drawPath(
            path, tint,
            style = Stroke(2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
}

// ── Sending ──────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WhisperComposeSheet(
    onDismiss: () -> Unit,
    onSendText: (String, Int) -> Unit,
    onSendPhoto: (android.net.Uri, Int) -> Unit,
) {
    var photoMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<android.net.Uri?>(null) }
    var secs by remember { mutableIntStateOf(15) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }
    val ctx = LocalContext.current
    val preview by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, photo) {
        value = photo?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                }.getOrNull()
            }
        }
    }
    // The sheet sits on the theme's surface: text and tints follow the theme
    // (white on a light surface was invisible). Violet stays the whisper accent.
    val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
    val fg = cs.textPrimary
    val dark = cs.bgSecondary.luminance() <= 0.5f
    val field = if (dark) Color(0xFF0B0C12) else Color(0xFFF4F1FF)
    val accentText = if (dark) VioletText else VioletDeep
    com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (dark) Color(0xFF2A2340) else Color(0xFFEDE7FF)), contentAlignment = Alignment.Center) {
                    WaveIcon(accentText, 20.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.whisper_title), color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.whisper_subtitle), color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fg.copy(alpha = 0.06f)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(false to R.string.whisper_tab_text, true to R.string.whisper_tab_photo).forEach { (p, label) ->
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (photoMode == p) fg.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { photoMode = p },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(label), color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (!photoMode) {
                BasicTextField(
                    value = text, onValueChange = { text = it.take(2000) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = fg, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Violet),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(16.dp))
                        .background(field).border(1.dp, Violet.copy(alpha = 0.3f), RoundedCornerShape(16.dp)).padding(14.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(stringResource(R.string.whisper_hint), color = fg.copy(alpha = 0.35f), fontSize = 16.sp)
                        inner()
                    },
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp)).background(field)
                        .border(1.dp, Violet.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = preview
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(AlphaIcons.Outlined.AddPhotoAlternate, null, tint = accentText, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.whisper_pick_photo), color = fg.copy(alpha = 0.7f), fontSize = 13.sp)
                        }
                    }
                }
            }
            Text(stringResource(R.string.whisper_time_label), color = fg.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WHISPER_SECS.forEach { v ->
                    val on = v == secs
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) VioletDeep.copy(alpha = if (dark) 0.35f else 0.18f) else fg.copy(alpha = 0.06f))
                            .border(1.dp, if (on) Violet else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { secs = v },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.whisper_secs, v), color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(fg.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoLine(fg, accentText, AlphaIcons.Outlined.Schedule, stringResource(R.string.whisper_info_24h))
                InfoLine(fg, accentText, AlphaIcons.Outlined.Check, stringResource(R.string.whisper_info_receipt))
                InfoLine(fg, accentText, AlphaIcons.Outlined.TouchApp, stringResource(R.string.whisper_info_finger))
            }
            val ready = if (photoMode) photo != null else text.isNotBlank()
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (ready) Brush.linearGradient(listOf(VioletDeep, Violet)) else androidx.compose.ui.graphics.SolidColor(fg.copy(alpha = 0.08f)))
                    .clickable(enabled = ready) {
                        if (photoMode) photo?.let { onSendPhoto(it, secs) } else onSendText(text.trim(), secs)
                        onDismiss()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaveIcon(if (ready) Color.White else fg.copy(alpha = 0.5f), 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.whisper_send), color = if (ready) Color.White else fg.copy(alpha = 0.5f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(fg: Color, accent: Color, icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = fg.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

// ── Reading ──────────────────────────────────────────────────────────────────

/**
 * The reader. [loadSecret] gives the secret (null: already burned), [loadPhoto]
 * a decrypted file for a photo whisper, [onBurn] burns it (read seconds, a
 * capture attempt), [onForgetPhoto] wipes the decrypted copy.
 */
@Composable
fun WhisperReader(
    senderName: String,
    loadSecret: suspend () -> WhisperSecret?,
    loadPhoto: suspend (String) -> String?,
    onBurn: (usedSecs: Int, shot: Boolean) -> Unit,
    onForgetPhoto: (String) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        // Full screen the proven way (all three settings together, see the
        // media viewer's WindowController): the dark reaches under the status
        // and navigation bars, no strip of the chat shows through.
        // Nothing of this window may be captured or recorded: the dialog re-applies
        // its secure policy on every recomposition, so the policy is the protection.
        properties = DialogProperties(
            usePlatformDefaultWidth = true, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = false)
        // No dimmed chat behind it.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.setDimAmount(0f)
            onDispose { }
        }
        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        var secret by remember { mutableStateOf<WhisperSecret?>(null) }
        var loaded by remember { mutableStateOf(false) }
        var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var usedMs by remember { mutableLongStateOf(0L) }
        var touch by remember { mutableStateOf<Offset?>(null) }
        var burned by remember { mutableStateOf(false) }
        var shot by remember { mutableStateOf(false) }
        var seen by remember { mutableStateOf(false) }
        val radius = remember { Animatable(0f) }
        val density = LocalDensity.current
        val ctx = LocalContext.current

        fun burn() {
            if (burned) return
            burned = true
            onBurn(((usedMs + 999) / 1000).toInt(), shot)
            secret?.mediaRef?.let(onForgetPhoto)
            bitmap = null
            secret = null
        }

        LaunchedEffect(Unit) {
            val s = loadSecret()
            secret = s
            if (s?.mediaRef != null) {
                val path = loadPhoto(s.mediaRef)
                bitmap = path?.let { p ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            com.alphamessenger.data.MediaVault.decodeFile(p, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > 2160 || bounds.outHeight / sample > 2160) sample *= 2
                            com.alphamessenger.data.MediaVault.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })
                        }.getOrNull()
                    }
                }
            }
            loaded = true
            if (s == null) burned = true
        }
        // Reading time runs only while the finger is down.
        LaunchedEffect(touch != null, burned) {
            if (touch == null || burned) return@LaunchedEffect
            var last = androidx.compose.runtime.withFrameNanos { it }
            var lastTick = -1L
            while (touch != null && !burned) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                usedMs += (now - last) / 1_000_000
                last = now
                val limitMs = (secret?.secs ?: 15) * 1000L
                val left = (limitMs - usedMs) / 1000
                if (left in 0..4 && left != lastTick) { lastTick = left; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                if (usedMs >= limitMs) { burn(); break }
            }
        }
        // A capture attempt (Android 14+) is reported to the sender.
        DisposableEffect(Unit) {
            val activity = ctx as? android.app.Activity
            if (android.os.Build.VERSION.SDK_INT >= 34 && activity != null) {
                val cb = android.app.Activity.ScreenCaptureCallback { shot = true }
                runCatching { activity.registerScreenCaptureCallback(activity.mainExecutor, cb) }
                onDispose { runCatching { activity.unregisterScreenCaptureCallback(cb) } }
            } else onDispose { }
        }
        // Leaving after reading burns it; leaving untouched keeps it for later.
        DisposableEffect(Unit) { onDispose { if (usedMs > 0 && !burned) burn() } }

        val limit = (secret?.secs ?: 15)
        val leftSecs = ((limit * 1000L - usedMs).coerceAtLeast(0) + 999) / 1000
        val urgent = leftSecs <= 4

        Box(Modifier.fillMaxSize().background(Night)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable {
                            if (usedMs > 0) burn()
                            onClose()
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AlphaIcons.Filled.Close, stringResource(R.string.common_close), tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(senderName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            stringResource(if (secret?.mediaRef != null) R.string.whisper_photo_one_view else R.string.whisper_only_you),
                            color = Violet, fontSize = 12.sp,
                        )
                    }
                    if (!burned && loaded) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(stringResource(R.string.whisper_secs, leftSecs.toInt()), color = if (urgent) Ash else Violet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.width(64.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                                Box(
                                    Modifier.fillMaxSize().graphicsLayer {
                                        scaleX = (1f - usedMs / (limit * 1000f)).coerceIn(0f, 1f)
                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                    }.background(if (urgent) Ash else Violet),
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .pointerInput(burned, loaded) {
                            if (burned || !loaded) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                touch = down.position
                                seen = true
                                scope.launch { radius.animateTo(with(density) { 90.dp.toPx() }, tween(180)) }
                                do {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull() ?: break
                                    if (c.pressed) touch = c.position
                                    c.consume()
                                } while (ev.changes.any { it.pressed })
                                touch = null
                                scope.launch { radius.animateTo(0f, tween(220)) }
                            }
                        },
                ) {
                    if (burned) {
                        // Embers keep rising where the words were.
                        val et = rememberInfiniteTransition(label = "embers")
                        val ep = et.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "rise")
                        val embers = remember { val r = java.util.Random(7L); List(40) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat(), r.nextFloat()) } }
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                embers.forEach { (x0, phase, speed, size0) ->
                                    val a = ((ep.value * speed + phase) % 1f)
                                    val y = size.height * (1.05f - a * 1.1f)
                                    val x = size.width * x0 + kotlin.math.sin((a * 6f + phase * 6f).toDouble()).toFloat() * 14.dp.toPx()
                                    drawCircle(
                                        (if (size0 > 0.6f) Color(0xFFFDE68A) else Ash).copy(alpha = (1f - a) * 0.55f),
                                        radius = (1.2f + size0 * 2f).dp.toPx(), center = Offset(x, y),
                                    )
                                }
                            },
                        )
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(AlphaIcons.Outlined.LocalFireDepartment, null, tint = Ash, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(14.dp))
                            Text(stringResource(R.string.whisper_burned), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.whisper_sender_knows), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                            Spacer(Modifier.height(18.dp))
                            Box(
                                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                                    .clickable(onClick = onClose).padding(horizontal = 22.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(R.string.common_close), color = Color.White, fontSize = 14.sp) }
                        }
                    } else if (loaded) {
                        val s = secret
                        // The revealed layer: only the circle under the finger lets it through.
                        val reveal = Modifier.fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val c = touch
                                val r = radius.value
                                if (c != null && r > 1f) {
                                    drawRect(
                                        Brush.radialGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent, center = c, radius = r),
                                        blendMode = BlendMode.DstIn,
                                    )
                                } else drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                            }
                        val bmp = bitmap
                        if (bmp != null) {
                            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = reveal)
                        } else if (s?.body != null) {
                            // Only the words under the finger exist; elsewhere, pure dark.
                            Text(s.body, color = Color(0xFFF4F1FF), fontSize = 24.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium,
                                modifier = reveal.padding(horizontal = 28.dp, vertical = 40.dp))
                        }
                        // The light itself.
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                val c = touch ?: return@drawBehind
                                // A gradient needs a radius above zero (the first frame of a touch is at 0).
                                if (radius.value < 1f) return@drawBehind
                                drawCircle(
                                    Brush.radialGradient(listOf(Violet.copy(alpha = 0.18f), Color.Transparent), center = c, radius = radius.value * 1.15f),
                                    radius = radius.value * 1.15f, center = c,
                                )
                            },
                        )
                        if (!seen) {
                            Row(
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(AlphaIcons.Outlined.TouchApp, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(if (bmp != null) R.string.whisper_hint_photo else R.string.whisper_hint_read),
                                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(AlphaIcons.Outlined.CameraAlt, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.whisper_no_capture), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
        }
    }
}
