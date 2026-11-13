package com.alphamessenger.ui.media.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.alphamessenger.R
import com.alphamessenger.media.MediaSizeFormat
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.media.export.MediaQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The quality of what is sent, left of the pencil: an HD/SD badge with the chosen
 * resolution on a small tag. A tap drops a capsule under it — the resolutions below
 * the clip's own (a photo: SD/HD) — with what the pick will weigh beneath.
 */
@Composable
fun MediaQualityButton(item: MediaDraftItem) {
    val ctx = LocalContext.current
    MediaQuality.load(ctx)
    val video = item.kind == MediaDraftItem.Kind.VIDEO
    val edge = MediaQuality.videoEdge
    val hd = MediaQuality.hd
    val shown = remember { MutableTransitionState(false) }
    val options by produceState<List<QualityOption>?>(null, item.uri, item.trimStartMs, item.trimEndMs, item.muted, video) {
        value = withContext(Dispatchers.IO) { if (video) videoOptions(ctx, item) else photoOptions(ctx, item) }
    }
    val list = options
    val current = list?.let { l ->
        if (video) l.firstOrNull { it.id == edge } ?: l.lastOrNull() else l.firstOrNull { it.id == (if (hd) 1 else 0) }
    }
    val isHd = current?.hd ?: hd

    Box {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                .clickable(enabled = list != null) { shown.targetState = !shown.currentState },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (isHd) AlphaIcons.Outlined.HighQuality else AlphaIcons.Outlined.Sd,
                stringResource(if (video) R.string.media_quality_title else R.string.media_quality_photo_title),
                tint = Color.White, modifier = Modifier.size(24.dp))
        }
        // The resolution, on a tag at the badge's corner (a photo has none).
        if (video && current != null) Text(
            current.tag, color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 9.sp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = 1.dp)
                .clip(RoundedCornerShape(5.dp)).background(Color.White).padding(horizontal = 3.dp, vertical = 1.dp),
        )
        if ((shown.currentState || shown.targetState) && list != null) Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(with(LocalDensity.current) { 8.dp.roundToPx() }, with(LocalDensity.current) { 50.dp.roundToPx() }),
            onDismissRequest = { shown.targetState = false },
            properties = PopupProperties(focusable = true),
        ) {
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)),
                exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)),
            ) {
                QualityCapsule(list, current) { o ->
                    if (video) MediaQuality.setVideoEdge(ctx, o.id) else MediaQuality.setHd(ctx, o.id == 1)
                    shown.targetState = false
                }
            }
        }
    }
}

/** The choices in one capsule, the picked one lit; its weight on a quiet line below. */
@Composable
private fun QualityCapsule(options: List<QualityOption>, current: QualityOption?, onPick: (QualityOption) -> Unit) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.shadow(18.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(Color(0xFF1F1F25))
                .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { o ->
                val on = o.id == current?.id
                Box(
                    Modifier.height(34.dp).clip(CircleShape)
                        .background(if (on) Color.White else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(o) }
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(o.short, color = if (on) Color.Black else Color.White.copy(alpha = 0.9f),
                        fontSize = 13.5.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold)
                }
            }
        }
        current?.takeIf { it.bytes > 0 }?.let { c ->
            Text(
                (if (c.exact) "" else "≈ ") + MediaSizeFormat.short(c.bytes),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private class QualityOption(
    val id: Int, val short: String, val tag: String, val hd: Boolean, val bytes: Long, val exact: Boolean,
)

private const val HD_EDGE = 1280

private suspend fun videoOptions(ctx: android.content.Context, item: MediaDraftItem): List<QualityOption> {
    val meta = com.alphamessenger.ui.media.export.SourceProbe.read(ctx, item.uri)
        .let { if (it.durationMs <= 0L) it.copy(durationMs = item.durationMs) else it }
    val own = maxOf(meta.displayWidth, meta.displayHeight)
    val spec = com.alphamessenger.ui.media.export.MediaEditSpec.of(item, MediaQuality.photoEdge(true), MediaQuality.photoQuality(true))
    suspend fun weigh(edge: Int): Long = runCatching {
        val plan = com.alphamessenger.ui.media.export.MediaExportPlanner.plan(meta, spec, MediaQuality.videoPolicy(edge))
        com.alphamessenger.ui.media.editor.VideoTrimmer.measure(ctx, item.uri, plan, meta).estimatedOutputBytes
    }.getOrDefault(0L)
    // Lowest to highest, the clip as it is last: the order a slider reads in.
    val lower = MediaQuality.qualitiesFor(own).sortedBy { it.longEdge }.map {
        val p = it.label.removeSuffix("p")
        QualityOption(it.longEdge, p, p, it.longEdge >= HD_EDGE, weigh(it.longEdge), exact = false)
    }
    val ownP = "${minOf(meta.displayWidth, meta.displayHeight)}"
    return lower + QualityOption(MediaQuality.ORIGINAL, ctx.getString(R.string.media_quality_original_short), ownP,
        own >= HD_EDGE, meta.fileBytes, exact = true)
}

/** SD (0) and HD (1) for a photo, each weighed by compressing it the way the send will. */
private suspend fun photoOptions(ctx: android.content.Context, item: MediaDraftItem): List<QualityOption> =
    listOf(false, true).map { hd ->
        val label = if (hd) "HD" else "SD"
        QualityOption(if (hd) 1 else 0, label, label, hd, sentSize(ctx, item, hd, MediaQuality.ORIGINAL), exact = false)
    }
