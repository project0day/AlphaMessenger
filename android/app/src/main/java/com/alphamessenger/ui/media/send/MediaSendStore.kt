package com.alphamessenger.ui.media.send

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Send-job storage that survives process death.
 *
 * Each job is its own file, not a line in a shared list: two sends run
 * independently, and a shared file would be rewritten whole on every step,
 * risking a neighbouring job on a failed write.
 *
 * Writes are atomic (temp file + rename): if the process dies at any moment,
 * disk holds either the old or the new state, never half of it.
 *
 * The directory deliberately lives in `filesDir`, not the cache: an
 * unfinished send must not be kept "best effort".
 */
class MediaSendStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, OUTBOX_DIR)
    private val jobsDir: File get() = File(root, "jobs").apply { mkdirs() }

    /** Directory of finished files for this send. Keyed by [MediaSendJob.sendId]. */
    fun workDir(sendId: String): File = File(root, "work/$sendId").apply { mkdirs() }

    private fun jobFile(sendId: String) = File(jobsDir, "$sendId.json")

    @Synchronized
    fun save(job: MediaSendJob) {
        runCatching {
            val target = jobFile(job.sendId)
            val tmp = File(target.parentFile, "${job.sendId}.tmp")
            tmp.writeText(MediaSendJson.jobToJson(job).toString())
            if (!tmp.renameTo(target)) {
                // Renaming over an existing file fails on some filesystems.
                target.delete()
                tmp.renameTo(target)
            }
        }.onFailure { android.util.Log.e(TAG, "save ${job.sendId}: ${it.message}") }
    }

    /** Cancelled: no work left, nobody needs the files. */
    @Synchronized
    fun delete(job: MediaSendJob) {
        runCatching { jobFile(job.sendId).delete() }
        runCatching { workDir(job.sendId).deleteRecursively() }
    }

    /**
     * Delivered: drop only the job record. The files move into the media cache
     * right after (the queue adopts them); [sweep] takes whatever was left.
     */
    @Synchronized
    fun deleteRecord(job: MediaSendJob) {
        runCatching { jobFile(job.sendId).delete() }
    }

    /** All unfinished jobs, in creation order so the queue stays fair. */
    @Synchronized
    fun all(): List<MediaSendJob> =
        jobsDir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { MediaSendJson.jobFromJson(JSONObject(f.readText())) }.getOrNull()
                    ?: run {
                        // A corrupt job cannot be restored; keeping it forever
                        // would retry garbage on every launch.
                        android.util.Log.w(TAG, "corrupt job ${f.name} deleted")
                        f.delete(); null
                    }
            }
            ?.sortedBy { it.createdAt }
            ?: emptyList()

    @Synchronized
    fun find(messageId: String): MediaSendJob? = all().firstOrNull { it.messageId == messageId }

    /**
     * Sweep stale state: directories of delivered sends and jobs that did not
     * finish within a day.
     *
     * Age is essential here: a directory without a job is usually a
     * successfully delivered attachment whose files still render the bubble;
     * deleting them immediately would show "download" on one's own photo a
     * minute after sending.
     *
     * ⚠ Read before enabling background replication (T3): once missing
     * replicas can be re-uploaded, this file becomes the worker's ONLY source
     * of ciphertext, and a time-based sweep would silently leave one copy
     * instead of three with no error in the log. Rule for T3: a file may be
     * removed only when its required copy is confirmed AND the desired
     * redundancy is reached — age becomes a secondary condition, not the
     * reason.
     */
    @Synchronized
    fun sweep(maxAgeMs: Long = 24L * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        val cutoff = now - maxAgeMs
        val jobs = all()
        val alive = jobs.map { it.sendId }.toSet()
        // A finished send's files live in the cache; a work dir without a job is a
        // leftover. Only a just-created one may still be waiting for its job record.
        val settled = now - WORK_DIR_GRACE_MS
        File(root, "work").listFiles()?.forEach { dir ->
            val touched = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
            if (dir.name !in alive && touched < settled) runCatching { dir.deleteRecursively() }
        }
        jobs.filter { it.createdAt < cutoff }.forEach { delete(it) }
    }

    companion object {
        private const val TAG = "AlphaSendQueue"
        private const val OUTBOX_DIR = "outbox"
        private const val WORK_DIR_GRACE_MS = 10L * 60 * 1000
    }
}
