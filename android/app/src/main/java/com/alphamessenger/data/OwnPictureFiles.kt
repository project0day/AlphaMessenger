package com.alphamessenger.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Our own profile pictures on disk. Each one is stored under a name taken from its
 * content, so a new picture is a new path: every screen and image cache keyed by the
 * path shows it at once, and no file is ever rewritten in place.
 */
object OwnPictureFiles {
    const val PHOTO = "photo"
    const val COVER = "cover"

    fun dir(ctx: Context): File = File(ctx.filesDir, "profile").apply { mkdirs() }

    /** A file to write a new picture into before [adopt] gives it its name. */
    fun scratch(ctx: Context, kind: String): File = File(dir(ctx), "$kind.pending.jpg")

    /** Moves a freshly written picture to its content name; its path, or null when it is unreadable. */
    fun adopt(ctx: Context, written: File, kind: String): String? {
        val hash = sha256(written) ?: return null
        val target = File(dir(ctx), "${kind}_${hash.take(16)}.jpg")
        if (target.exists()) written.delete()
        else if (!written.renameTo(target)) {
            written.copyTo(target, overwrite = true)
            written.delete()
        }
        return target.absolutePath.takeIf { target.length() > 0 }
    }

    /** Deletes every picture of ours that is not one of [keep] (the core's current ones). */
    fun prune(ctx: Context, keep: Collection<String>) {
        val kept = keep.filter { it.isNotBlank() }.mapTo(HashSet()) { canonical(File(it)) }
        val ours = dir(ctx).listFiles().orEmpty().filter { f ->
            OWN_PREFIXES.any { f.name.startsWith(it) } && !f.name.contains(".pending")
        } + ctx.filesDir.listFiles().orEmpty().filter { it.name.startsWith("my_avatar") && it.name.endsWith(".jpg") } +
            File(File(ctx.cacheDir, "media"), "cover.jpg")
        ours.filter { it.isFile && canonical(it) !in kept }.forEach { it.delete() }
    }

    private fun canonical(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    private fun sha256(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    // The linked-device copy is "my_avatar_<hash>.jpg" in filesDir; video avatars and
    // their stills come from the editor as "avatar_video_*" / "avatar_cover_*".
    private val OWN_PREFIXES = listOf("${PHOTO}_", "${COVER}_", "avatar_video_", "avatar_cover_")
}
