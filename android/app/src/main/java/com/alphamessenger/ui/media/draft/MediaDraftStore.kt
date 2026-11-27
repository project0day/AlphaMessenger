package com.alphamessenger.ui.media.draft

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * A set of attachments the user is preparing to send.
 *
 * One store for all inputs: gallery, camera, files, system Share. It has a
 * single owner — [MediaDraftViewModel] scoped to the screen's lifecycle;
 * global objects like the former `PendingShare` are deliberately absent: an
 * invisible screen could grab the set through them, and re-sharing worked
 * only intermittently.
 *
 * One Activity, so one layer: "hand the set over exactly once" is expressed
 * by [consume].
 */
class MediaDraftStore {

    /** Order = send order. Reorder changes exactly this. */
    val items: SnapshotStateList<MediaDraftItem> = mutableListOf<MediaDraftItem>().toMutableStateList()

    /** What is open in the preview. Held here, not in the screen: survives recreation. */
    var currentIndex by mutableStateOf(0)
        private set

    /**
     * Caption for the whole set. Kept apart from the items deliberately: writing
     * it into `items[0]` would change the list on every keystroke and re-render
     * the viewer page — the photo set would flicker while typing. Text arriving
     * from Share together with attachments lands here too.
     */
    var text by mutableStateOf<String?>(null)

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val current: MediaDraftItem? get() = items.getOrNull(currentIndex)

    // --- Filling ---

    fun replaceAll(newItems: List<MediaDraftItem>) {
        items.clear()
        items.addAll(newItems)
        currentIndex = 0
    }

    fun add(item: MediaDraftItem) {
        items.add(item)
    }

    fun addAll(newItems: List<MediaDraftItem>) {
        items.addAll(newItems)
    }

    // --- Edits ---

    fun removeById(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items.removeAt(index)
        currentIndex = currentIndex.coerceAtMost((items.size - 1).coerceAtLeast(0))
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        currentIndex = to
    }

    fun setCurrent(index: Int) {
        if (index in items.indices) currentIndex = index
    }

    /** Point update of an item — editor result, caption, mute, trim. */
    fun update(id: String, transform: (MediaDraftItem) -> MediaDraftItem) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = transform(items[index])
    }

    fun setCaption(caption: String?) {
        text = caption?.takeIf { it.isNotBlank() }
    }

    val caption: String? get() = text

    // --- Completion ---

    /**
     * Hand the set over for sending and clear the store.
     *
     * Exactly once: a repeat call returns an empty list, so the same set cannot
     * be sent twice — not by a second tap, not after screen recreation. Temp
     * copies are NOT deleted here: the upload still needs the files; cleanup
     * runs after it via [cleanupTemporaries].
     */
    fun consume(): List<MediaDraftItem> {
        val snapshot = items.toList()
        items.clear()
        currentIndex = 0
        text = null
        return snapshot
    }

    /** Cancel: the set is dropped, our temp copies are deleted immediately. */
    fun discard(context: Context?) {
        if (context != null) cleanupTemporaries(context, items.toList())
        items.clear()
        currentIndex = 0
        text = null
    }

    // --- State saving ---

    fun saveTo(handle: SavedStateHandle) {
        handle[KEY_ITEMS] = ArrayList(items)
        handle[KEY_CURRENT] = currentIndex
        handle[KEY_TEXT] = text
    }

    fun restoreFrom(handle: SavedStateHandle) {
        val saved: ArrayList<MediaDraftItem> = handle[KEY_ITEMS] ?: return
        items.clear()
        items.addAll(saved)
        currentIndex = (handle.get<Int>(KEY_CURRENT) ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        text = handle[KEY_TEXT]
    }

    companion object {
        private const val KEY_ITEMS = "media_draft_items"
        private const val KEY_CURRENT = "media_draft_current"
        private const val KEY_TEXT = "media_draft_text"

        /** Directory for our temp copies inside the cache. Only the factory writes there. */
        const val DRAFT_DIR = "draft"

        /** Directory of the former Share intake. No longer written — only swept. */
        private const val LEGACY_SHARE_DIR = "share"

        /**
         * Delete the temp copies we made at intake. Foreign files and gallery files
         * are untouched — only the one we created ourselves is marked.
         */
        fun cleanupTemporaries(context: Context, items: List<MediaDraftItem>) {
            val cacheRoot = context.cacheDir.absolutePath
            for (item in items) {
                if (!item.deleteAfterUse) continue
                val path = item.uri.path ?: continue
                val file = File(path)
                if (file.absolutePath.startsWith(cacheRoot)) runCatching { file.delete() }
            }
        }

        /**
         * Sweep old temp copies.
         *
         * Only directories older than an hour land here: by then the set has either
         * been sent or abandoned. Cancel cleans up precisely, via
         * [cleanupTemporaries].
         *
         * This sweep CANNOT harm an active send: a queued job takes its copies with
         * it (moves them into the send directory), so only what nobody needs anymore
         * stays in `draft/`. A file's lifetime must not decide whether the message
         * arrives.
         */
        fun sweepStale(context: Context, olderThanMs: Long = 60L * 60 * 1000) {
            val cutoff = System.currentTimeMillis() - olderThanMs
            File(context.cacheDir, DRAFT_DIR).listFiles()?.forEach { entry ->
                if (entry.lastModified() < cutoff) runCatching { entry.deleteRecursively() }
            }
            // Legacy of the former Share intake: the copy was made twice, and nobody
            // ever cleaned this directory.
            runCatching { File(context.cacheDir, LEGACY_SHARE_DIR).deleteRecursively() }
        }
    }
}

/**
 * Owner of [MediaDraftStore] and the single point of adding to it.
 *
 * Lives as long as the chat screen (navigation-entry scope), survives
 * rotation and Activity recreation. There is deliberately no global object.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class MediaDraftViewModel @javax.inject.Inject constructor(
    private val savedState: androidx.lifecycle.SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val sendQueue: com.alphamessenger.ui.media.send.MediaSendQueue,
) : ViewModel() {

    val store = MediaDraftStore().also { it.restoreFrom(savedState) }

    init {
        // Sweep past temp copies — once per screen, off the main thread.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { MediaDraftStore.sweepStale(appContext) }
        }
        savedState.get<String>("chatId")?.takeIf { it.isNotBlank() }?.let(::preUploadWhileComposing)
    }

    /** The set goes up as it settles: picks, edits and quality changes, a moment after the last one. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun preUploadWhileComposing(chatId: String) {
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow {
                Triple(
                    store.items.toList(),
                    com.alphamessenger.ui.media.export.MediaQuality.hd,
                    com.alphamessenger.ui.media.export.MediaQuality.videoEdge,
                )
            }
                .debounce(PRE_UPLOAD_SETTLE_MS)
                .collect { (items, _, _) -> sendQueue.compose(chatId, items) }
        }
    }

    /**
     * Intake is strictly queued: two quick taps on the grid are two coroutines,
     * and without a queue the set order would depend on whose file was read
     * first. The kotlinx Mutex is fair (FIFO), so order = selection order.
     */
    private val ingestLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Turn a URI into set items. [accept] is called on the main thread with the
     * finished item — limits are checked there; if it returns false, the item is
     * not added.
     */
    fun ingest(
        uris: List<Uri>,
        source: MediaDraftItem.Source,
        asFile: Boolean = false,
        limit: Int = Int.MAX_VALUE,
        accept: ((MediaDraftItem) -> Boolean)? = null,
        onFailure: ((Uri, Throwable) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            ingestLock.withLock {
                for (uri in uris) {
                    if (store.size >= limit) break
                    val result = MediaDraftFactory.fromUri(appContext, uri, source, asFile)
                    result
                        .onSuccess { item -> if (accept?.invoke(item) != false) store.add(item) }
                        .onFailure { error -> onFailure?.invoke(uri, error) }
                }
                persist()
            }
            // After the queue: the set is complete, sending can proceed.
            onComplete?.invoke()
        }
    }

    /** Remove an item by source URI — the grid knows URIs, not keys. */
    fun removeByUri(uri: Uri) {
        val item = store.items.firstOrNull { it.originalUri == uri || it.uri == uri } ?: return
        store.removeById(item.id)
        persist()
    }

    fun orderOf(uri: Uri): Int =
        store.items.indexOfFirst { it.originalUri == uri || it.uri == uri }.let { if (it >= 0) it + 1 else 0 }

    /**
     * Hand the set over for sending. The same set will not go twice.
     *
     * Edits are deliberately NOT applied here: the item carries them, and the
     * send queue applies them after the message has appeared in the chat, so the
     * bubble shows instantly instead of waiting for re-encoding.
     */
    fun consume(): List<MediaDraftItem> = store.consume().also {
        sendQueue.reserve(it)
        persist()
    }

    /** Cancel: the set is dropped, our temp copies are deleted, its uploads leave the servers. */
    fun discard() {
        sendQueue.discardComposed(store.items.toList())
        store.discard(appContext)
        persist()
    }

    /** Called before leaving the screen and on process state saving. */
    fun persist() = store.saveTo(savedState)

    override fun onCleared() {
        persist()
        sendQueue.discardComposed(store.items.toList())
        super.onCleared()
    }

    private companion object {
        const val PRE_UPLOAD_SETTLE_MS = 700L
    }
}
