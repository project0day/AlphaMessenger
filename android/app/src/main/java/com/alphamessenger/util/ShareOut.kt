package com.alphamessenger.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hands our media to other apps ("Share").
 *
 * Decrypted media live in the cache under their content hash, with no name and
 * no extension — a receiving app saw a nameless blob and stored it as ".bin".
 * Here every outgoing file gets a proper copy: its own name (files) or
 * "Alpha_<date>_<time>.<ext>" (photos, videos, voice), the extension read from
 * the bytes themselves, and an exact MIME type. Albums go out whole.
 * The copies live in cache/share and are swept after an hour.
 */
object ShareOut {

    class Item(val path: String, val filename: String?, val mediaType: String?)

    private const val DIR = "share"
    private const val KEEP_MS = 60 * 60 * 1000L

    /** Prepare named copies (IO) and open the system share sheet. False if nothing could be prepared. */
    suspend fun share(ctx: Context, items: List<Item>, chooserTitle: String): Boolean {
        val files = withContext(Dispatchers.IO) { prepare(ctx, items) }
        if (files.isEmpty()) return false
        val uris = files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) }
        val mimes = files.map { mimeOf(it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimes[0]
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                // One family for all ("image/*"), or anything.
                val families = mimes.map { it.substringBefore('/') }.distinct()
                type = if (families.size == 1) "${families[0]}/*" else "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        // The grant must cover every file: ClipData carries all of them.
        intent.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply {
            uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, chooserTitle).apply {
            if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    /** One properly named copy (for "Open with…"), or null. */
    suspend fun namedCopy(ctx: Context, item: Item): File? =
        withContext(Dispatchers.IO) { prepare(ctx, listOf(item)).firstOrNull() }

    private fun prepare(ctx: Context, items: List<Item>): List<File> {
        val root = File(ctx.cacheDir, DIR).apply { mkdirs() }
        sweep(root)
        // One folder per share: equal names in one album never collide.
        val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return items.mapIndexedNotNull { i, item ->
            runCatching {
                val src = File(item.path)
                if (!src.exists() || src.length() == 0L) return@runCatching null
                val given = item.filename?.let { safeName(it) }?.takeIf { it.isNotBlank() }
                val ext = given?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
                    ?: sniff(src)
                    ?: when (item.mediaType) { "video" -> "mp4"; "audio" -> "m4a"; "image" -> "jpg"; else -> "dat" }
                val name = when {
                    given != null && given.contains('.') -> given
                    given != null -> "$given.$ext"
                    items.size > 1 -> "Alpha_${stamp}_${i + 1}.$ext"
                    else -> "Alpha_$stamp.$ext"
                }
                // Another app gets the content itself, never our sealed form.
                File(dir, name).also { com.alphamessenger.data.MediaVault.copyPlain(src, it) }
            }.getOrNull()
        }
    }

    /** No path parts or control characters in a name that came from someone else. */
    private fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }
            .trim().take(120)

    /** The file type from its first bytes. */
    private fun sniff(f: File): String? {
        val h = ByteArray(16)
        val n = runCatching { com.alphamessenger.data.MediaVault.open(f).use { it.read(h) } }.getOrDefault(0)
        if (n < 4) return null
        fun at(i: Int, s: String) = n >= i + s.length && s.indices.all { h[i + it] == s[it].code.toByte() }
        val b0 = h[0].toInt() and 0xFF
        val b1 = h[1].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xD8 -> "jpg"
            b0 == 0x89 && at(1, "PNG") -> "png"
            at(0, "GIF8") -> "gif"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(4, "ftyp") -> when {
                at(8, "heic") || at(8, "heix") || at(8, "mif1") || at(8, "msf1") -> "heic"
                at(8, "qt  ") -> "mov"
                at(8, "M4A ") -> "m4a"
                else -> "mp4"
            }
            at(0, "OggS") -> "ogg"
            at(0, "ID3") || (b0 == 0xFF && (b1 and 0xE0) == 0xE0) -> "mp3"
            at(0, "%PDF") -> "pdf"
            at(0, "PK") -> "zip"
            at(0, "\u001AE") -> "webm"
            else -> null
        }
    }

    fun mimeOf(f: File): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: when (f.extension.lowercase()) { "heic" -> "image/heic"; "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"; else -> "application/octet-stream" }

    /** Earlier shares are kept a while for the receiving app, then removed. */
    private fun sweep(root: File) {
        val cut = System.currentTimeMillis() - KEEP_MS
        root.listFiles()?.forEach { d -> if (d.lastModified() < cut) d.deleteRecursively() }
    }
}
