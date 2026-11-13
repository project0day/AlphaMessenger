package com.alphamessenger.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.chat.media.v2.ViewerCallbacks
import com.alphamessenger.ui.chat.media.v2.ViewerGeometryProvider
import com.alphamessenger.ui.chat.media.v2.ViewerHost
import com.alphamessenger.ui.chat.media.v2.ViewerPlaceholder
import com.alphamessenger.ui.chat.media.v2.ViewerSession
import com.alphamessenger.ui.chat.media.v2.hero.HeroBitmapProvider
import com.alphamessenger.ui.chat.media.v2.hero.HeroGeometry
import com.alphamessenger.ui.chat.media.v2.pager.PagerMediaSource
import com.alphamessenger.ui.chat.media.v2.pager.PagerPages
import com.alphamessenger.ui.chat.media.v2.rememberViewerHostState
import com.alphamessenger.ui.chat.media.v2.window.ViewerWindow
import com.alphamessenger.ui.chat.media.v2.window.rememberWindowController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The single bridge between the app and the new viewer.
 *
 * The two worlds meet only here: outside — collection, repository and source
 * registry; inside — session, page set and ready-made rectangles. The new
 * viewer knows none of this: it receives ready implementations.
 */
@Composable
fun MediaViewer(
    collection: MediaCollection,
    onDismiss: () -> Unit,
    onDownload: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    repository: AlphaCoreRepository? = null,
    source: MediaSource = MediaSource(),
    onForward: ((MediaItem) -> Unit)? = null,
    onShare: ((MediaItem) -> Unit)? = null,
    onInfo: ((MediaItem) -> Unit)? = null,
    onReply: ((MediaItem) -> Unit)? = null,
    onShowInChat: ((MediaItem) -> Unit)? = null,
    onOpenWith: ((MediaItem) -> Unit)? = null,
    onShowAllMedia: (() -> Unit)? = null,
    onQuickReply: ((MediaItem, String) -> Unit)? = null,
    onReact: ((MediaItem, String) -> Unit)? = null,
    reactionPolicy: com.alphamessenger.ui.chat.ReactionPolicy = com.alphamessenger.ui.chat.ReactionPolicy.ALL,
    myReactions: (MediaItem) -> Set<String> = { emptySet() },
    reactions: (MediaItem) -> Map<String, Int> = { emptyMap() },
    downloadOf: (MediaItem) -> kotlinx.coroutines.flow.StateFlow<com.alphamessenger.transfer.TransferSnapshot?>? = { null },
) {
    if (collection.items.isEmpty()) return
    val context = LocalContext.current
    val byId = remember(collection) { collection.items.associateBy { it.id } }

    // Bubbles must not move or re-register while the viewer is open.
    DisposableEffect(collection) {
        MediaSourceRegistry.freeze()

        // Chat system bars must NOT be touched. Making them transparent yields
        // a black hole — chat content never draws under the bars. Keep the chat
        // as usual so the fading viewer background reveals it in one motion.
        // A viewer closed by an action (show in chat, reply, forward) skips the
        // return flight, which is what relights the source: relight it here.
        onDispose {
            MediaSourceRegistry.setActive(null)
            MediaSourceRegistry.unfreeze()
        }
    }

    val media = remember(collection, repository) {
        object : PagerMediaSource {
            override suspend fun fullBitmap(item: MediaItem): ImageBitmap? =
                withContext(Dispatchers.IO) { loadFullBitmap(item, repository) }

            override suspend fun poster(item: MediaItem): ImageBitmap? =
                withContext(Dispatchers.IO) { loadThumb(item) }

            override suspend fun videoFile(item: MediaItem): File? =
                withContext(Dispatchers.IO) { ensureVideoFile(context, item, repository) }

            override suspend fun localVideo(item: MediaItem): File? =
                withContext(Dispatchers.IO) { localVideoFile(context, item) }

            override suspend fun videoStream(item: MediaItem): androidx.media3.datasource.DataSource.Factory? {
                val repo = repository ?: return null
                val ref = item.refJson ?: return null
                val full = repo.resolveMediaRef(ref) ?: return null
                val streamable = runCatching {
                    val o = org.json.JSONObject(full)
                    o.optBoolean("streaming", false) && (o.optJSONArray("chunks")?.length() ?: 0) > 0
                }.getOrDefault(false)
                if (!streamable) return null
                val dir = File(context.cacheDir, "stream").apply { mkdirs() }
                return androidx.media3.datasource.DataSource.Factory {
                    com.alphamessenger.ui.chat.EncryptedDataSource(repo, full, dir)
                }
            }
        }
    }

    // Transition frame comes only from already-warm caches, no decoding.
    val frames = remember(collection) {
        HeroBitmapProvider { id -> byId[id]?.let { cachedFrame(it) } }
    }

    // Where the frame flies from and to: bubble → the spot the image takes on
    // screen. The aspect ratio comes from the item itself, else the frame jumps.
    val geometry = remember(collection, source) {
        ViewerGeometryProvider { mediaId, viewport ->
            val item = byId[mediaId]
            val bubble = MediaSourceRegistry.get(mediaId) ?: source.rect
            // Take the aspect ratio from the frame that actually flies: image
            // files carry no dimensions in the message, and otherwise the
            // target rect degenerates to the whole screen.
            val ratio = aspectRatioOf(item)
            val target = if (bubble == null || viewport.width <= 0f || viewport.height <= 0f) {
                null
            } else {
                displayRect(ratio, viewport)
            }
            if (bubble == null || target == null) null else HeroGeometry(bubble, target)
        }
    }

    val placeholder = remember { ViewerPlaceholder { id -> MediaSourceRegistry.setActive(id) } }

    val session = remember(collection, media, frames, geometry, placeholder) {
        ViewerSession(
            pages = PagerPages(
                items = collection.items,
                startIndex = collection.startIndex,
                allowsPaging = collection.allowsPaging,
            ),
            media = media,
            frames = frames,
            geometry = geometry,
            placeholder = placeholder,
        )
    }

    val hostState = rememberViewerHostState(collection.items.firstOrNull()?.id)
    val windowController = rememberWindowController()

    ViewerWindow(onDismissRequest = { hostState.close() }) {
        ViewerHost(
            session = session,
            callbacks = ViewerCallbacks(
                onDismiss = {
                    onDismiss()
                },
                onSave = onDownload,
                onForward = onForward,
                onShare = onShare,
                onReply = onReply,
                onShowInChat = onShowInChat,
                onOpenWith = onOpenWith,
                onShowAllMedia = onShowAllMedia,
                onInfo = onInfo,
                onQuickReply = onQuickReply,
                onReact = onReact,
                reactionPolicy = reactionPolicy,
                myReactions = myReactions,
                reactions = reactions,
                downloadOf = downloadOf,
            ),
            state = hostState,
            window = windowController,
        )
    }
}

/**
 * Aspect ratio of the frame. First from the message; if absent, from an
 * already-warm frame (poster or decrypted file). Guessing "whole screen" is
 * not allowed: the frame must fly into the image's own rectangle.
 */
private fun aspectRatioOf(item: MediaItem?): Float? {
    if (item == null) return null
    val w = item.width?.toFloat()
    val h = item.height?.toFloat()
    if (w != null && h != null && w > 0f && h > 0f) return w / h
    val frame = cachedFrame(item) ?: return null
    return if (frame.height > 0) frame.width.toFloat() / frame.height.toFloat() else null
}

/** The spot the image takes on screen when fitted by size. */
private fun displayRect(ratio: Float?, viewport: Size): Rect {
    var width = viewport.width
    var height = viewport.height
    if (ratio != null && ratio > 0f) {
        if (viewport.width / viewport.height > ratio) {
            height = viewport.height
            width = height * ratio
        } else {
            width = viewport.width
            height = width / ratio
        }
    }
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun Rect?.fmt(): String =
    this?.let { "${it.left.toInt()},${it.top.toInt()}..${it.right.toInt()},${it.bottom.toInt()}" } ?: "null"

/** Where the viewer was opened from: the message bubble and its on-screen bounds. */
data class MediaSource(
    val messageId: String? = null,
    val mediaId: String? = null,
    val rect: Rect? = null,
)
