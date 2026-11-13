package com.alphamessenger.ui.media.draft

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import com.alphamessenger.ui.chat.AttachmentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single entry point for external [Uri] values; turns them into a [MediaDraftItem].
 *
 * The factory does nothing else — no sending, no screens, no repository or
 * upload knowledge; every source (gallery, camera, file picker, system Share)
 * gets the same result. The order mirrors the reference `MediaItem.getFromUri`
 * (AGPL-3.0, see THIRD_PARTY_LICENSES.md): MIME -> name -> size -> EXIF ->
 * permissions, with a local copy when a grant is refused. Implementation is
 * our own.
 */
object MediaDraftFactory {

    /** What to do with a foreign URI whose grants are not persistent. */
    enum class CopyPolicy {
        /** Copy if the system gave no persistent grant. For external sources. */
        WHEN_NOT_PERSISTABLE,

        /** Do not copy: the URI lives exactly as long as our process. */
        NEVER,
    }

    sealed class Failure(message: String) : Exception(message) {
        /** URI unavailable: deleted, provider silent, or grants revoked. */
        class Unreadable(val uri: Uri) : Failure("URI недоступен для чтения: $uri")

        /** Over the sending limit for its type: not copied at all. */
        class TooLarge(val uri: Uri) : Failure("Файл больше допустимого размера: $uri")

        /** Grants are transient and a local copy could not be made. */
        class CopyFailed(val uri: Uri) : Failure("Не удалось создать локальную копию: $uri")
    }

    suspend fun fromUri(
        context: Context,
        uri: Uri,
        source: MediaDraftItem.Source,
        asFile: Boolean = false,
        copyPolicy: CopyPolicy = defaultPolicyFor(source),
    ): Result<MediaDraftItem> = withContext(Dispatchers.IO) {
        runCatching { buildItem(context.applicationContext, uri, source, asFile, copyPolicy) }
    }

    /** Each URI is processed independently: one failure does not sink the rest. */
    suspend fun fromUris(
        context: Context,
        uris: List<Uri>,
        source: MediaDraftItem.Source,
        asFile: Boolean = false,
        copyPolicy: CopyPolicy = defaultPolicyFor(source),
    ): List<Result<MediaDraftItem>> = withContext(Dispatchers.IO) {
        uris.map { runCatching { buildItem(context.applicationContext, it, source, asFile, copyPolicy) } }
    }

    /**
     * Share brings a transient grant: it dies with the task, and the source app
     * closes immediately. Our own sources live as long as the process; sending
     * makes the copy for them.
     */
    private fun defaultPolicyFor(source: MediaDraftItem.Source): CopyPolicy =
        if (source == MediaDraftItem.Source.SHARE) CopyPolicy.WHEN_NOT_PERSISTABLE else CopyPolicy.NEVER

    private fun buildItem(
        context: Context,
        uri: Uri,
        source: MediaDraftItem.Source,
        asFile: Boolean,
        copyPolicy: CopyPolicy,
    ): MediaDraftItem {
        if (!isReadable(context, uri)) throw Failure.Unreadable(uri)

        val displayName = AttachmentCache.displayName(context, uri) ?: nameFromUri(uri)
        val mimeType = resolveMime(context, uri, displayName)
        val kind = kindOf(mimeType)
        val filename = displayName?.let { withExtension(it, mimeType) } ?: fallbackName(mimeType)
        val size = AttachmentCache.size(context, uri)
        val limit = com.alphamessenger.config.AttachmentLimits.limitFor(mimeType)
        if (size > limit) throw Failure.TooLarge(uri)

        // Take a persisted grant ONLY when the copy decision depends on it: their
        // number per app is limited, and gallery/camera URIs already live as long
        // as the process.
        val persistable = copyPolicy == CopyPolicy.WHEN_NOT_PERSISTABLE && runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)

        var workingUri = uri
        var deleteAfterUse = false
        if (!persistable && copyPolicy == CopyPolicy.WHEN_NOT_PERSISTABLE) {
            val dir = File(context.cacheDir, "${MediaDraftStore.DRAFT_DIR}/${System.currentTimeMillis()}")
            val copy = AttachmentCache.copyToDir(context, uri, dir, filename, maxBytes = limit)
                ?: throw Failure.CopyFailed(uri)
            workingUri = Uri.fromFile(copy)
            deleteAfterUse = true
        }

        val exif = if (kind == MediaDraftItem.Kind.IMAGE) readExif(context, workingUri) else ExifOrientation()
        val duration = when (kind) {
            MediaDraftItem.Kind.VIDEO, MediaDraftItem.Kind.AUDIO -> readDurationMs(context, workingUri)
            else -> 0L
        }

        return MediaDraftItem(
            id = MediaDraftItem.newId(),
            uri = workingUri,
            originalUri = uri,
            kind = kind,
            source = source,
            mimeType = mimeType,
            filename = filename,
            sizeBytes = size,
            durationMs = duration,
            exifRotationDegrees = exif.rotation,
            exifFlip = exif.flip,
            sendAsFile = asFile,
            deleteAfterUse = deleteAfterUse,
        )
    }

    // --- Reading metadata ---

    /** Cheap availability check: open the stream, close it right away. */
    private fun isReadable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * MIME from the URI, then from the file name extension. A generic type is not
     * an answer: Share and some file managers report `application/octet-stream`
     * for everything, which cannot tell a PDF from a photo.
     */
    private fun resolveMime(context: Context, uri: Uri, displayName: String?): String {
        val fromProvider = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (isSpecific(fromProvider)) return fromProvider!!

        val ext = extensionOf(displayName ?: uri.lastPathSegment)
        if (ext != null) {
            val byExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (isSpecific(byExt)) return byExt!!
        }
        return fromProvider?.takeIf { it.isNotBlank() } ?: MIME_FALLBACK
    }

    private fun isSpecific(mime: String?): Boolean =
        !mime.isNullOrBlank() && !mime.endsWith("/*") && mime != "*/*" && mime != MIME_FALLBACK

    private fun kindOf(mime: String): MediaDraftItem.Kind = when {
        mime.startsWith("image/") -> MediaDraftItem.Kind.IMAGE
        mime.startsWith("video/") -> MediaDraftItem.Kind.VIDEO
        mime.startsWith("audio/") -> MediaDraftItem.Kind.AUDIO
        else -> MediaDraftItem.Kind.FILE
    }

    private fun nameFromUri(uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it.contains('.') }

    /** [name] as it is, or with the extension of [mime] when it has none (the recipient must be able to open it). */
    private fun withExtension(name: String, mime: String): String {
        if (extensionOf(name) != null) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
        return "$name.$ext"
    }

    private fun fallbackName(mime: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (ext != null) "file.$ext" else "file"
    }

    private fun extensionOf(name: String?): String? =
        name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() && it.length <= 5 }

    private class ExifOrientation(
        val rotation: Int = 0,
        val flip: Int = MediaDraftItem.FLIP_NONE,
    )

    /**
     * Orientation read from the file itself. Stored SEPARATELY from the rotation
     * the user applies later in the editor, so one does not overwrite the other.
     */
    private fun readExif(context: Context, uri: Uri): ExifOrientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = android.media.ExifInterface(stream)
            when (exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> ExifOrientation(90)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> ExifOrientation(180)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> ExifOrientation(270)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    ExifOrientation(0, MediaDraftItem.FLIP_HORIZONTAL)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    ExifOrientation(0, MediaDraftItem.FLIP_VERTICAL)
                android.media.ExifInterface.ORIENTATION_TRANSPOSE ->
                    ExifOrientation(90, MediaDraftItem.FLIP_HORIZONTAL)
                android.media.ExifInterface.ORIENTATION_TRANSVERSE ->
                    ExifOrientation(270, MediaDraftItem.FLIP_HORIZONTAL)
                else -> ExifOrientation()
            }
        } ?: ExifOrientation()
    }.getOrDefault(ExifOrientation())

    /** Video/audio duration. Metadata only: no frames, no re-encoding. */
    private fun readDurationMs(context: Context, uri: Uri): Long = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)

    private const val MIME_FALLBACK = "application/octet-stream"
}
