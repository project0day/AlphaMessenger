package com.alphamessenger.ui.media.send

import android.content.Context
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.media.export.MediaQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads the photos and videos of a set while it is still being composed, so Send
 * finds them up already and only publishes.
 *
 * Each upload is keyed by its draft and everything that shapes the file: an edit or a
 * quality change starts a new one and drops the old. What no message ends up using is
 * stopped and deleted from the servers, also after a restart. Videos that would be
 * re-encoded wait for Send. The core picks the route by the chat: Tor for a secret one.
 */
@Singleton
class MediaPreUpload @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlphaCoreRepository,
) {
    private class Entry(val chatId: String, val key: String, val opId: String, val path: String, val result: Deferred<String?>) {
        /** Handed to a send: no change to the set may drop it any more. */
        @Volatile var reserved = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val producing = Semaphore(2)
    private val root = File(context.cacheDir, "preupload")

    init {
        // Nothing survives a restart in memory: whatever is on disk belongs to no message.
        scope.launch { root.listFiles()?.forEach { dropLeftover(it) } }
    }

    /** The set as it now stands: new items start, edited ones restart, removed ones go. */
    fun offer(chatId: String, drafts: List<MediaDraftItem>) {
        MediaQuality.load(context)
        val wanted = drafts.filter { (it.isImage || it.isVideo) && !it.isDocument }.associateBy { it.id }
        for ((id, e) in entries) {
            val draft = wanted[id]
            if (!e.reserved && (draft == null || keyOf(draft) != e.key)) drop(id)
        }
        for (draft in wanted.values) {
            if (!entries.containsKey(draft.id)) start(chatId, draft)
        }
    }

    /** The set goes to Send: its uploads now wait for the send to take them. */
    fun reserve(drafts: List<MediaDraftItem>) {
        drafts.forEach { entries[it.id]?.reserved = true }
    }

    /** The set was dropped without sending. */
    fun discard(drafts: List<MediaDraftItem>) {
        drafts.forEach { if (entries[it.id]?.reserved == false) drop(it.id) }
    }

    /**
     * The upload of [draftId] made under [key], with its file moved to [target]; the
     * ref then points there. Null: none matches or it failed, and the send uploads itself.
     */
    suspend fun adopt(draftId: String, key: String, target: String): String? {
        val e = entries[draftId] ?: return null
        if (e.key != key) {
            drop(draftId)
            return null
        }
        e.reserved = true
        val ref = runCatching { e.result.await() }.getOrNull()
        entries.remove(draftId, e)
        if (ref == null) {
            repository.discardPreUpload(e.chatId, e.opId, e.path, "")
            File(e.path).parentFile?.deleteRecursively()
            return null
        }
        val dst = File(target)
        dst.parentFile?.mkdirs()
        val src = File(e.path)
        val moved = src.renameTo(dst) || runCatching { src.copyTo(dst, overwrite = true); true }.getOrDefault(false)
        src.parentFile?.deleteRecursively()
        if (!moved) {
            repository.discardPreUpload(e.chatId, e.opId, e.path, ref)
            return null
        }
        return JSONObject(ref).put("local_path", target).toString()
    }

    /** The op the upload of [draftId] under [key] reports its bytes as, while it runs. */
    fun opIdOf(draftId: String, key: String): String? = entries[draftId]?.takeIf { it.key == key }?.opId

    /** The key a send computes for [draft] under its own settings; equal to [keyOf] for the same ones. */
    fun keyOf(draft: MediaDraftItem, photoMaxEdge: Int, photoQuality: Int, hd: Boolean, videoEdge: Int): String {
        val revision = MediaSendJob.revisionOf(draft, photoMaxEdge, photoQuality)
        return "${draft.id}|$revision|" + if (draft.isVideo) "v$videoEdge" else "p$hd"
    }

    /** As [keyOf], under the settings a send of the set would use now. */
    private fun keyOf(draft: MediaDraftItem): String {
        val hd = MediaQuality.hd
        return keyOf(draft, photoEdge(hd), photoQuality(hd), hd, MediaQuality.videoEdge)
    }

    private fun start(chatId: String, draft: MediaDraftItem) {
        val hd = MediaQuality.hd
        val videoEdge = MediaQuality.videoEdge
        val maxEdge = photoEdge(hd)
        val quality = photoQuality(hd)
        val opId = "pre-" + java.util.UUID.randomUUID()
        val revision = MediaSendJob.revisionOf(draft, maxEdge, quality)
        val path = File(File(root, opId), "0.r$revision.${if (draft.isVideo) "mp4" else "jpg"}").path
        val item = MediaSendItem(index = 0, draft = draft, outputPath = path, editRevision = revision)
        val policy = if (draft.isVideo) MediaQuality.videoPolicy(videoEdge) else MediaQuality.policy(hd)
        val result = scope.async {
            val file = producing.withPermit {
                if (MediaOutputFactory.reencodes(context, item, maxEdge, quality, policy)) return@async null
                File(path).parentFile?.mkdirs()
                MediaOutputFactory.produce(context, item, maxEdge, quality, policy = policy)
            } ?: return@async null
            // Kept next to the file: after a restart the leftover is deleted by the same route.
            File(File(path).parentFile, CHAT_FILE).writeText(chatId)
            repository.preUploadMedia(chatId, opId, file.path, item.mediaType)
                ?.also { File(File(path).parentFile, REF_FILE).writeText(it) }
        }
        entries[draft.id] = Entry(chatId, keyOf(draft, maxEdge, quality, hd, videoEdge), opId, path, result)
    }

    private fun drop(draftId: String) {
        val e = entries.remove(draftId) ?: return
        scope.launch {
            // Stop it now, then delete everything it got up, once it has stopped.
            repository.discardPreUpload(e.chatId, e.opId, e.path, "")
            val ref = runCatching { e.result.await() }.getOrNull()
            repository.discardPreUpload(e.chatId, e.opId, e.path, ref.orEmpty())
            File(e.path).parentFile?.deleteRecursively()
        }
    }

    private suspend fun dropLeftover(dir: File) {
        val opId = dir.name
        val file = dir.listFiles()?.firstOrNull { it.name.startsWith("0.r") && !it.name.endsWith(".part") }
        if (opId.startsWith("pre-") && file != null) {
            val ref = File(dir, REF_FILE).takeIf { it.exists() }?.readText().orEmpty()
            val chatId = File(dir, CHAT_FILE).takeIf { it.exists() }?.readText().orEmpty()
            repository.discardPreUpload(chatId, opId, file.path, ref)
        }
        dir.deleteRecursively()
    }

    private fun photoEdge(hd: Boolean) = maxOf(DEFAULT_PHOTO_EDGE, MediaQuality.photoEdge(hd))
    private fun photoQuality(hd: Boolean) = maxOf(DEFAULT_PHOTO_QUALITY, MediaQuality.photoQuality(hd))

    private companion object {
        const val REF_FILE = "ref.json"
        const val CHAT_FILE = "chat"
        /** What a chat's send passes before raising them for HD ([com.alphamessenger.ui.chat.ChatViewModel.sendDraftMedia]). */
        const val DEFAULT_PHOTO_EDGE = 1600
        const val DEFAULT_PHOTO_QUALITY = 80
    }
}
