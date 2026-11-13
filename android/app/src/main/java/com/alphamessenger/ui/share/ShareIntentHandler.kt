package com.alphamessenger.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Parse an incoming Share. The Activity only hands the Intent over here.
 *
 * No file reading or type detection here: URIs from the intent go to the
 * shared attachment factory, which treats gallery, camera, file picker and
 * Share alike. Types were never derived from `intent.type` here — for
 * "5 photos + a video" the system sends a wildcard type, and some apps
 * report `application/octet-stream` for everything.
 */
object ShareIntentHandler {

    /** Flag "this Intent was already accepted" — survives Activity recreation. */
    private const val EXTRA_CONSUMED = "com.alphamessenger.share.consumed"

    fun isShare(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return false
        return !intent.getBooleanExtra(EXTRA_CONSUMED, false)
    }

    fun markConsumed(intent: Intent) {
        intent.putExtra(EXTRA_CONSUMED, true)
    }

    /** Accept a Share. Reads extras only — no I/O, no locks. Our own files are
     *  never taken: another app could point a Share at our database or keys
     *  (a file path into our folder, or our own file provider) to have them sent. */
    fun receive(intent: Intent, ctx: android.content.Context): SharePayload? =
        SharePayload(uris = incomingUris(intent).filter { !isOurs(it, ctx) }, text = incomingText(intent)).takeIf { !it.isEmpty }

    private fun isOurs(uri: Uri, ctx: android.content.Context): Boolean = when (uri.scheme) {
        "content" -> uri.authority?.startsWith(ctx.packageName) == true
        "file" -> runCatching {
            val path = java.io.File(uri.path ?: return@runCatching true).canonicalPath
            val mine = listOfNotNull(ctx.dataDir, ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir, ctx.externalCacheDir, ctx.getExternalFilesDir(null))
                .map { it.canonicalPath }
            mine.any { path == it || path.startsWith(it + java.io.File.separator) } || path.contains("/data/data/") || path.contains("/data/user/")
        }.getOrDefault(true)
        else -> true
    }

    private fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(parcelable(intent, Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> parcelableList(intent, Intent.EXTRA_STREAM)
        else -> emptyList()
    }

    /** Text: SEND_MULTIPLE may deliver a list — join it with newlines. */
    private fun incomingText(intent: Intent): String? {
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val list = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) ?: return null
        return list.joinToString("\n").takeIf { it.isNotBlank() }
    }

    @Suppress("DEPRECATION")
    private fun parcelable(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun parcelableList(intent: Intent, key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
}
