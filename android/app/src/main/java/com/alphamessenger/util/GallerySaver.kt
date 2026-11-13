package com.alphamessenger.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** Writes a decrypted file into the public gallery (Pictures / Movies → AlphaMessenger). */
object GallerySaver {
    fun save(context: Context, file: File, isVideo: Boolean, name: String? = null): Boolean = runCatching {
        val resolver = context.contentResolver
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // The sender names the file: no folders, no hidden or control characters.
        val fname = name?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.filter { it >= ' ' && it != ':' }?.trimStart('.')?.take(120)?.takeIf { it.isNotBlank() }
            ?: ("alpha_${System.currentTimeMillis()}" + if (isVideo) ".mp4" else ".jpg")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fname)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/AlphaMessenger" else "Pictures/AlphaMessenger")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return@runCatching false
        resolver.openOutputStream(uri)?.use { out -> com.alphamessenger.data.MediaVault.open(file).use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false)

    /** Any other file (archive, document, audio) into Downloads → AlphaMessenger. Android 10+. */
    fun saveFile(context: Context, file: File, name: String?): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching false
        val fname = name?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.filter { it >= ' ' && it != ':' }?.trimStart('.')?.take(120)?.takeIf { it.isNotBlank() }
            ?: "alpha_${System.currentTimeMillis()}"
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fname.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fname)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AlphaMessenger")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        resolver.openOutputStream(uri)?.use { out -> com.alphamessenger.data.MediaVault.open(file).use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}
