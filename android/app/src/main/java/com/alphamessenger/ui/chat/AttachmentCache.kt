package com.alphamessenger.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The single mechanism that copies an attachment from `content://` into our cache.
 * Both sending and Share receiving need identical copy semantics (name,
 * sanitizing, empty-file check), so there is one copier with two callers.
 */
object AttachmentCache {

    /** Provider display name (never "" — null lets the caller decide). Our own
     *  copies are `file://` — no provider answers for them: their name is the file's. */
    fun displayName(context: Context, uri: Uri): String? = if (uri.scheme == "file") {
        uri.path?.let { File(it).name }?.takeIf { it.isNotBlank() }
    } else try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) { null }

    /** Provider did not report a size. 0 would wrongly mean "empty file". */
    const val SIZE_UNKNOWN = -1L

    /**
     * Size from the provider, then from the file descriptor. The whole file is
     * never read into memory just to learn its size.
     */
    fun size(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        }
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                if (it.length >= 0) return it.length
            }
        }
        return SIZE_UNKNOWN
    }

    /**
     * File name without what breaks a file system (separators, reserved and
     * control characters). Letters of any language stay: "Отчёт.pdf" is sent as
     * "Отчёт.pdf", not "_____.pdf".
     */
    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.')
        if (cleaned.isEmpty()) return "file"
        if (cleaned.length <= 120) return cleaned
        val ext = cleaned.substringAfterLast('.', "").take(10)
        return if (ext.isEmpty()) cleaned.take(120) else cleaned.take(119 - ext.length) + "." + ext
    }

    /**
     * Copy for sending. The copy keeps the ORIGINAL file name — the recipient
     * sees it — so each copy gets its own directory (`send/<ts>/<name>`)
     * instead of a prefixed name.
     */
    fun copyToCache(context: Context, uri: Uri): File? {
        val name = displayName(context, uri) ?: "file_${System.currentTimeMillis()}"
        val dir = File(context.cacheDir, "send/${System.currentTimeMillis()}_${(0..9999).random()}")
        if (!dir.exists() && !dir.mkdirs()) return null
        return copy(context, uri, File(dir, safeName(name)))
    }

    /**
     * Copy into a separate directory under the ORIGINAL file name. Share receive
     * puts this name into the message, and the recipient must see "report.pdf",
     * not "share_1723_report.pdf".
     */
    fun copyToDir(context: Context, uri: Uri, dir: File, fallbackName: String, maxBytes: Long = Long.MAX_VALUE): File? {
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = displayName(context, uri) ?: fallbackName
        return copy(context, uri, File(dir, safeName(name)), maxBytes)
    }

    /**
     * The copy appears under its final name only when complete: write to a temp
     * file and rename, so a partially-written file can never be mistaken for a
     * finished one (the send job may pick the file up mid-copy).
     */
    private fun copy(context: Context, uri: Uri, out: File, maxBytes: Long = Long.MAX_VALUE): File? = try {
        val part = File(out.parentFile, "${out.name}.part")
        runCatching { part.delete() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { output ->
                // The declared size is the sender's word; the stream is counted.
                val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw java.io.IOException("larger than $maxBytes bytes")
                    output.write(buf, 0, n)
                }
                output.flush()
                // Force data onto disk (not the kernel buffer): if the process dies,
                // the renamed file must not end up empty.
                runCatching { output.fd.sync() }
            }
        }
        if (!part.exists() || part.length() == 0L) {
            runCatching { part.delete() }
            null
        } else {
            // Location / date / device out of photos and videos — before the file
            // appears under its final name, so nothing ever reads it uncleaned.
            com.alphamessenger.ui.media.MetadataScrubber.scrubInPlace(context, part, finalName = out.name)
            runCatching { out.delete() }
            if (!part.renameTo(out)) {
                runCatching { part.copyTo(out, overwrite = true); part.delete() }
            }
            out.takeIf { it.exists() && it.length() > 0L }
        }
    } catch (e: Exception) {
        android.util.Log.e("AttachmentCache", "copy failed: $uri", e)
        runCatching { File(out.parentFile, "${out.name}.part").delete() }
        null
    }
}
