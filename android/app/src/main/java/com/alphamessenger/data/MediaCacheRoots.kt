package com.alphamessenger.data

import android.content.Context
import java.io.File

/**
 * What exactly the product counts as CACHE.
 *
 * One answer for the whole project. Four components used to answer it
 * differently (bubble, storage screen, auto-trim, view-once trim), and the
 * mismatches cost exactly what you'd expect: "clear cache" on the wrong
 * document could wipe the only copy of an unfinished send.
 *
 * Ownership split:
 *
 * * **SOURCE** — the user's original. Not ours.
 * * **Set OUTBOX** — `filesDir/outbox/work`. Finished files of a set send;
 *   swept by `MediaSendStore.sweep`.
 * * **CACHE** — [disposableRoots]. Disposable: received, decrypted,
 *   streamed chunks, partial downloads, plus our own copy of a finished
 *   single send `cacheDir/send_*`.
 * * **PREVIEW** — the poster lives inside the message and survives cache
 *   cleanup on purpose: an empty grey rectangle instead of a preview is a
 *   worse outcome than extra kilobytes. `notif_thumbs` is a separate cache.
 */
object MediaCacheRoots {

    /** Directories whose contents may be deleted without data loss. */
    fun disposableRoots(context: Context): List<File> = listOf(
        File(context.cacheDir, "media"),
        File(context.cacheDir, "stream_chunks"),
    )

    /** Notification-thumbnail cache — separate policy (see note above). */
    fun notificationThumbs(context: Context): File = File(context.cacheDir, "notif_thumbs")

    /**
     * Whether a path belongs to the set OUTBOX. Cache cleanup must not touch it.
     *
     * Our own copy of a SINGLE send (`cacheDir/send_*`) is deliberately NOT
     * included. Protecting it by location broke the main flow — the document
     * became undeletable and piled up forever. Protect the PHASE, not the
     * directory: an active send is guarded by the queue check in
     * `ChatViewModel.clearMediaCache`; a finished copy is disposable again.
     */
    fun isOutbox(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            File(path).absolutePath.startsWith(File(context.filesDir, "outbox").absolutePath)
        }.getOrDefault(false)
    }

    /**
     * Mark a deleted cache file as "deleted by the user".
     *
     * Without the mark, auto-download would re-fetch the deleted file over
     * the network without any user action — the classic "deleted, but it
     * downloaded itself again" defect. The bubble sets this mark from the
     * start; the settings-screen trim and the auto-trim didn't, so the defect
     * lived on those two paths.
     */
    fun markEvicted(file: File) {
        // Called AFTER deletion — the file itself is already gone, so checking
        // its existence is pointless; only the name is needed.
        val name = file.name
        if (name.endsWith(EVICTED_SUFFIX)) return
        // The mark is set on the HASH, not on the partial: `<hash>_c3.part` is
        // part of the same download, a second mark for it is redundant.
        val hash = name.substringBefore('.').substringBefore("_c")
        if (hash.isBlank()) return
        runCatching { File(file.parentFile, "$hash$EVICTED_SUFFIX").createNewFile() }
    }

    const val EVICTED_SUFFIX = ".evicted"
}
