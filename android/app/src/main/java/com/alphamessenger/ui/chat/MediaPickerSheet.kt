package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A gallery grid item shown by the attachment panel.
 *
 * Selection state is NOT held here — it lives in `MediaDraftStore`.
 */
data class GalleryItem(
    val uri: Uri,
    val isVideo: Boolean,
    val durationMs: Long,
    val dateAdded: Long = 0L, // epoch seconds from MediaStore
)

/** A device media folder (bucket). [id] is the MediaStore BUCKET_ID. */
data class MediaBucket(
    val id: String,
    val name: String,
    val count: Int,
)

/**
 * classic media picker — but rendered **INLINE in the main window**
 * (not a ModalBottomSheet). This is the key to reliable keyboard behaviour:
 * the host places this in the chat's root Column, and a separate caption bar
 * (also in the main window) lifts with the keyboard via the main window's
 * imePadding — something a sheet (which lives in its own window) can't do.
 *
 * This composable renders ONLY the grabber + action row + gallery grid. The
 * selection list is hoisted to the host so the host can show the caption bar
 * and drive the panel height. Dragging the grabber/header streams per-frame
 * deltas to the host ([onDrag]); the host resizes the panel continuously and
 * snaps to a detent on release ([onDragRelease]).
 */
/** Max items selectable per send (gallery grid + "File" multi-pick). */
const val MAX_MEDIA_SELECTION = 30

@Composable
fun MediaPickerContent(
    colors: AlphaColorScheme,
    // Selection lives in MediaDraftStore; this sheet only renders it.
    orderOf: (GalleryItem) -> Int,
    onToggle: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean = false,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onMusic: () -> Unit,
    onLocation: () -> Unit,
    onSecretNote: () -> Unit = {},
    onClose: () -> Unit,
    /** Raw vertical drag delta in px (positive = finger moving down). */
    onDrag: (Float) -> Unit,
    onDragRelease: () -> Unit,
) {
    val ctx = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasGalleryPermission(ctx)) }
    var allItems   by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var buckets    by remember { mutableStateOf<List<MediaBucket>>(emptyList()) }
    var activeBucket by remember { mutableStateOf<MediaBucket?>(null) } // null = all buckets
    val gridState = rememberLazyGridState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> hasPermission = result.values.any { it } }

    // Load all media + buckets when permission is granted.
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val (imgs, vids) = withContext(Dispatchers.IO) { loadRawMedia(ctx) }
        allItems = (imgs + vids).sortedByDescending { it.second }.take(500).map { it.first }
        buckets  = withContext(Dispatchers.IO) { loadBuckets(ctx) }
    }

    // Filtered items are resolved properly by re-querying per bucket.
    var filteredItems by remember { mutableStateOf<List<GalleryItem>?>(null) }
    LaunchedEffect(activeBucket, allItems) {
        filteredItems = if (activeBucket == null) null
        else withContext(Dispatchers.IO) { loadRecentMedia(ctx, bucketId = activeBucket?.id) }
    }
    val displayItems = filteredItems ?: allItems

    // Scroll to top when bucket changes.
    LaunchedEffect(activeBucket) { gridState.scrollToItem(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(colors.bgSecondary),
    ) {
        // --- Draggable header: grabber pill + close ✕ ---
        Box(
            modifier = Modifier.fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, delta -> onDrag(delta) },
                        onDragEnd = { onDragRelease() },
                        onDragCancel = { onDragRelease() },
                    )
                },
        ) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp, bottom = 8.dp)
                    .width(40.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textMuted.copy(alpha = 0.4f)),
            )
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).size(40.dp).clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AlphaIcons.Outlined.Close, stringResource(R.string.common_close), tint = colors.textMuted, modifier = Modifier.size(20.dp))
            }
        }

        // --- Action row ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PickerAction(stringResource(R.string.media_kind_music), AlphaIcons.Outlined.MusicNote,   Color(0xFFFF8A34), colors, onMusic)
            PickerAction(stringResource(R.string.media_kind_file),  AlphaIcons.Outlined.Folder,      Color(0xFF36C46A), colors, onFile)
            PickerAction(stringResource(R.string.attach_whisper), AlphaIcons.Outlined.GraphicEq, Color(0xFF8B5CF6), colors, onSecretNote)
            PickerAction(stringResource(R.string.attach_geo),    AlphaIcons.Outlined.LocationOn,  Color(0xFFEB5757), colors, onLocation)
            PickerAction(stringResource(R.string.perm_camera), AlphaIcons.Outlined.CameraAlt,   Color(0xFF4A8CFF), colors, onCamera)
        }

        // --- Folder chip bar (full-screen mode only) ---
        if (isFullScreen && buckets.isNotEmpty()) {
            HorizontalDivider(color = colors.textMuted.copy(alpha = 0.10f))
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // "All" chip
                item {
                    BucketChip(
                        name = stringResource(R.string.picker_all),
                        count = allItems.size,
                        selected = activeBucket == null,
                        colors = colors,
                        onClick = { activeBucket = null },
                    )
                }
                lazyItems(buckets, key = { it.id }) { bucket ->
                    BucketChip(
                        name = bucket.name,
                        count = bucket.count,
                        selected = activeBucket?.id == bucket.id,
                        colors = colors,
                        onClick = { activeBucket = bucket },
                    )
                }
            }
        }

        HorizontalDivider(color = colors.textMuted.copy(alpha = 0.12f))

        // --- Gallery grid (or permission prompt) ---
        if (!hasPermission) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.picker_permission_media),
                    color = colors.textSecondary, fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.accent)
                        .clickable { permLauncher.launch(galleryPermissions()) }
                        .padding(horizontal = 22.dp, vertical = 11.dp),
                ) { Text(stringResource(R.string.perm_allow), color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.fillMaxSize().padding(start = 2.dp, end = 2.dp),
                ) {
                    items(displayItems, key = { it.uri.toString() }) { item ->
                        GalleryCell(item, orderOf(item), colors) { onToggle(item) }
                    }
                }
                DateScrubber(
                    state = gridState,
                    items = displayItems,
                    colors = colors,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp),
                )
            }
        }
    }
}

@Composable
private fun BucketChip(
    name: String,
    count: Int,
    selected: Boolean,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    val bg = if (selected) colors.accent else colors.accent.copy(alpha = 0.10f)
    val textColor = if (selected) Color.White else colors.textSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(name, color = textColor, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Text("$count", color = textColor.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

// --- Private helpers (gallery grid, loading, formatting) ---

/**
 * Passive scroll indicator — a slim pill on the right edge that shows where
 * you are while you scroll the gallery yourself. NOT draggable (dragging
 * would fight the grid's own scroll); no date badge.
 */
@Composable
private fun DateScrubber(
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    items: List<GalleryItem>,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
) {
    val total = items.size
    if (total == 0) return

    // Fraction mirrors the actual scroll position of the grid.
    val info = state.layoutInfo
    val visibleCount = info.visibleItemsInfo.size
    val firstIdx = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    val thumbFrac = if (total > visibleCount && visibleCount > 0)
        firstIdx.toFloat() / (total - visibleCount) else 0f

    // Only show the indicator when the content is actually scrollable.
    if (visibleCount == 0 || total <= visibleCount) return

    val thumbColor = colors.accent

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val trackPx: Float = constraints.maxHeight.toFloat()
        val thumbHeight = 44.dp
        val thumbPx: Float = with(density) { thumbHeight.toPx() }
        val travel = (trackPx - thumbPx).coerceAtLeast(1f)
        val thumbY = (travel * thumbFrac).coerceIn(0f, travel)
        val thumbYDp = with(density) { thumbY.toDp() }

        // Slim pill — purely visual, no pointerInput so the grid scrolls freely.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbYDp)
                .padding(end = 4.dp)
                .size(width = 6.dp, height = thumbHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(thumbColor.copy(alpha = 0.7f)),
        )
    }
}

@Composable
private fun GalleryCell(
    item: GalleryItem,
    order: Int,
    colors: AlphaColorScheme,
    onTap: () -> Unit,
) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onTap),
    ) {
        if (item.isVideo) {
            var frame by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(item.uri) {
                frame = withContext(Dispatchers.IO) { videoFrame(ctx, item.uri) }
            }
            frame?.let {
                Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) { Text(fmtDuration(item.durationMs), color = Color.White, fontSize = 10.sp) }
        } else {
            AsyncImage(
                model = item.uri, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            )
        }
        if (order > 0) {
            Box(modifier = Modifier.matchParentSize().background(colors.accent.copy(alpha = 0.28f)))
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (order > 0) colors.accent else Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (order > 0) {
                Text("$order", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PickerAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = colors.textSecondary, fontSize = 11.sp)
    }
}

// --- Gallery loading helpers ---

private fun galleryPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasGalleryPermission(ctx: Context): Boolean =
    galleryPermissions().any {
        ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/**
 * Returns (images, videos) as pairs of (GalleryItem, dateAdded) — used to build
 * the merged "All" list and the bucket list in one pass.
 */
private fun loadRawMedia(ctx: Context): Pair<List<Pair<GalleryItem, Long>>, List<Pair<GalleryItem, Long>>> {
    val imgs = ArrayList<Pair<GalleryItem, Long>>()
    val vids = ArrayList<Pair<GalleryItem, Long>>()

    val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    runCatching {
        ctx.contentResolver.query(
            imgUri,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(imgUri, c.getLong(idCol))
                val date = c.getLong(dateCol)
                imgs.add(GalleryItem(uri, false, 0L, date) to date)
            }
        }
    }

    val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    runCatching {
        ctx.contentResolver.query(
            vidUri,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION),
            null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durCol  = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val uri  = ContentUris.withAppendedId(vidUri, c.getLong(idCol))
                val date = c.getLong(dateCol)
                vids.add(GalleryItem(uri, true, c.getLong(durCol), date) to date)
            }
        }
    }
    return imgs to vids
}

/** Query filtered by [bucketId]. Used when user picks a specific folder. */
private fun loadRecentMedia(ctx: Context, bucketId: String? = null, limit: Int = 500): List<GalleryItem> {
    data class Raw(val uri: Uri, val isVideo: Boolean, val durationMs: Long, val dateAdded: Long)
    val raw = ArrayList<Raw>(limit * 2)

    val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    runCatching {
        val sel  = if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID}=?" else null
        val args = if (bucketId != null) arrayOf(bucketId) else null
        ctx.contentResolver.query(
            imgUri,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext())
                raw.add(Raw(ContentUris.withAppendedId(imgUri, c.getLong(idCol)), false, 0L, c.getLong(dateCol)))
        }
    }

    val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    runCatching {
        val sel  = if (bucketId != null) "${MediaStore.Video.Media.BUCKET_ID}=?" else null
        val args = if (bucketId != null) arrayOf(bucketId) else null
        ctx.contentResolver.query(
            vidUri,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION),
            sel, args, "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durCol  = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext())
                raw.add(Raw(ContentUris.withAppendedId(vidUri, c.getLong(idCol)), true, c.getLong(durCol), c.getLong(dateCol)))
        }
    }

    return raw.sortedByDescending { it.dateAdded }.take(limit).map { GalleryItem(it.uri, it.isVideo, it.durationMs, it.dateAdded) }
}

/** Collect all unique media buckets (folders) sorted by item count descending. */
private fun loadBuckets(ctx: Context): List<MediaBucket> {
    val map = LinkedHashMap<String, Pair<String, Int>>() // bucketId → (name, count)

    val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    runCatching {
        ctx.contentResolver.query(
            imgUri,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val bid  = c.getString(idCol) ?: continue
                val name = c.getString(nameCol) ?: ctx.getString(R.string.preview_photo)
                val prev = map[bid]
                map[bid] = name to ((prev?.second ?: 0) + 1)
            }
        }
    }

    val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    runCatching {
        ctx.contentResolver.query(
            vidUri,
            arrayOf(MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val bid  = c.getString(idCol) ?: continue
                val name = c.getString(nameCol) ?: ctx.getString(R.string.media_kind_video)
                val prev = map[bid]
                map[bid] = name to ((prev?.second ?: 0) + 1)
            }
        }
    }

    return map.entries
        .map { (id, v) -> MediaBucket(id, v.first, v.second) }
        .sortedByDescending { it.count }
}

private fun videoFrame(ctx: Context, uri: Uri): ImageBitmap? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    r.setDataSource(ctx, uri)
    val b = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    r.release()
    b?.asImageBitmap()
}.getOrNull()

internal fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
