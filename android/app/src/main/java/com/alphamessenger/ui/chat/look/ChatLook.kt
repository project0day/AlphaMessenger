package com.alphamessenger.ui.chat.look

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.theme.ChatWallpaper
import com.alphamessenger.ui.theme.CustomTheme
import com.alphamessenger.ui.theme.resolveWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * How one chat looks: a theme and/or a background (a colour/gradient id or a
 * photo). Two sources, the first that has something wins:
 *  1. mine for this chat — on this phone only;
 *  2. the admins' for a group / channel — shipped to every member with the
 *     chat's metadata (core `set_chat_look`), shown unless switched off;
 *  then the app's own theme.
 *
 * Shared form (JSON): {"t": theme code, "w": background id, "p": photo ref,
 * "h": photo hash, "a": when the photo was uploaded (s)}. Mine adds "f" (the
 * photo's file here) and "off" (the admins' look switched off for this chat).
 */
data class ChatLook(
    val theme: CustomTheme? = null,
    val wallpaperId: String? = null,
    /** The photo on this phone (mine, or the admins' once downloaded). */
    val photoPath: String? = null,
    /** The admins' photo: its blob ref, hash, upload time. */
    val photoRef: String? = null,
    val photoHash: String? = null,
    val photoAt: Long = 0L,
    /** Mine only: "show my own theme here, not the admins' look". */
    val off: Boolean = false,
) {
    val isEmpty: Boolean get() = theme == null && wallpaperId == null && photoPath == null && photoRef == null

    /** The background to draw, or null for the theme's own. */
    fun wallpaper(): ChatWallpaper? = when {
        photoPath != null && File(photoPath).length() > 0 -> ChatWallpaper.Gallery("look:$photoPath", "file://$photoPath")
        wallpaperId != null -> resolveWallpaper(wallpaperId, null).takeIf { it !is ChatWallpaper.Default }
        else -> null
    }

    fun toJson(local: Boolean): String = JSONObject().apply {
        theme?.let { put("t", it.encode()) }
        wallpaperId?.let { put("w", it) }
        if (local) {
            photoPath?.let { put("f", it) }
            if (off) put("off", true)
        } else {
            photoRef?.let { put("p", it) }
            photoHash?.let { put("h", it) }
            if (photoAt > 0) put("a", photoAt)
        }
    }.toString()

    companion object {
        fun parse(json: String?): ChatLook? = runCatching {
            if (json.isNullOrBlank()) return null
            val o = JSONObject(json)
            fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() }
            ChatLook(
                theme = s("t")?.let { CustomTheme.decode(it) },
                // Only colours and gradients travel; a gallery id would point at someone's phone.
                wallpaperId = s("w")?.takeIf { !it.startsWith("gallery:") },
                photoPath = s("f"),
                photoRef = s("p"),
                photoHash = s("h")?.filter { it.isLetterOrDigit() }?.take(64),
                photoAt = o.optLong("a"),
                off = o.optBoolean("off"),
            )
        }.getOrNull()
    }
}

object ChatLooks {
    private const val PREFS = "chat_looks"

    /** Bumped on every change (mine, the admins', the switch), so open chats re-read. */
    var tick by mutableIntStateOf(0)
        private set

    /** "Show groups' and channels' looks" (Appearance), on by default. */
    var showAdmin by mutableStateOf(true)
        private set

    /** A request (from the chat's menu) to open "Chat theme" for the chat in view. */
    var editMine by mutableStateOf(false)

    private var loaded = false
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        showAdmin = prefs(ctx).getBoolean("show_admin", true)
    }

    fun setShowAdmin(ctx: Context, v: Boolean) {
        showAdmin = v
        prefs(ctx).edit().putBoolean("show_admin", v).apply()
        tick++
    }

    fun bump() { tick++ }

    fun mine(ctx: Context, chatId: String): ChatLook? = ChatLook.parse(prefs(ctx).getString("m:$chatId", null))

    fun setMine(ctx: Context, chatId: String, look: ChatLook?) {
        val old = mine(ctx, chatId)
        val e = prefs(ctx).edit()
        if (look == null || (look.isEmpty && !look.off)) e.remove("m:$chatId") else e.putString("m:$chatId", look.toJson(local = true))
        e.apply()
        // A photo no longer used is deleted.
        old?.photoPath?.takeIf { it != look?.photoPath }?.let { File(it).delete() }
        tick++
    }

    fun mineCount(ctx: Context): Int = prefs(ctx).all.keys.count { it.startsWith("m:") }

    fun clearMine(ctx: Context) {
        val p = prefs(ctx)
        p.all.keys.filter { it.startsWith("m:") }.forEach { k -> ChatLook.parse(p.getString(k, null))?.photoPath?.let { File(it).delete() } }
        p.edit().apply { p.all.keys.filter { it.startsWith("m:") }.forEach { remove(it) } }.apply()
        tick++
    }

    /** Shown once per chat: "this is the admins' look — switch it off here". */
    fun noticeSeen(ctx: Context, chatId: String) = prefs(ctx).getBoolean("seen:$chatId", false)
    fun markNoticeSeen(ctx: Context, chatId: String) { prefs(ctx).edit().putBoolean("seen:$chatId", true).apply() }

    private fun photoDir(ctx: Context) = File(ctx.filesDir, "chat_looks").apply { mkdirs() }

    /** The admins' look for [chatId], its photo fetched once and kept by hash. */
    suspend fun admins(ctx: Context, repo: AlphaCoreRepository, chatId: String): ChatLook? = withContext(Dispatchers.IO) {
        val look = ChatLook.parse(runCatching { repo.chatLook(chatId) }.getOrNull()) ?: return@withContext null
        val ref = look.photoRef
        val hash = look.photoHash
        if (ref == null || hash.isNullOrBlank()) return@withContext look
        val f = File(photoDir(ctx), "a_$hash.jpg")
        if (f.length() <= 0) {
            val got = repo.downloadMediaToCache(ref) ?: return@withContext look.copy(photoPath = null)
            runCatching { File(got).copyTo(f, overwrite = true) }
        }
        look.copy(photoPath = f.absolutePath.takeIf { f.length() > 0 })
    }

    /** Put [look] on the group / channel for everyone (the photo uploaded first). */
    suspend fun applyForAll(ctx: Context, repo: AlphaCoreRepository, chatId: String, look: ChatLook): Boolean = withContext(Dispatchers.IO) {
        var shared = look
        val path = look.photoPath
        if (path != null && look.photoRef == null) {
            val ref = repo.uploadBlobRefOnly(path, "image", null, "look_${chatId.take(12)}_${System.currentTimeMillis()}") ?: return@withContext false
            val hash = runCatching { JSONObject(ref).optString("hash") }.getOrNull().orEmpty().filter { it.isLetterOrDigit() }.take(64)
            if (hash.isNotBlank()) runCatching { File(path).copyTo(File(photoDir(ctx), "a_$hash.jpg"), overwrite = true) }
            shared = look.copy(photoRef = ref, photoHash = hash, photoAt = System.currentTimeMillis() / 1000)
        }
        val json = if (shared.isEmpty) "" else shared.toJson(local = false)
        val ok = runCatching { repo.setChatLook(chatId, json) }.isSuccess
        if (ok) tick++
        ok
    }

    /**
     * A photo for a background: at most 1600 px on its long side, JPEG under
     * ~350 KB, kept in the app's files ([prefix] names it).
     */
    suspend fun importPhoto(ctx: Context, uri: android.net.Uri, prefix: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2
            val src = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
            } ?: return@runCatching null
            val k = minOf(1f, 1600f / maxOf(src.width, src.height))
            val bmp = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
            val out = File(photoDir(ctx), "${prefix}_${System.currentTimeMillis()}.jpg")
            var q = 85
            do {
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }
                q -= 10
            } while (out.length() > 350 * 1024 && q >= 45)
            out.absolutePath
        }.getOrNull()
    }
}
