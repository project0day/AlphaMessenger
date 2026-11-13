package com.alphamessenger.ui.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.feed.FeedKind
import com.alphamessenger.ui.feed.FeedPost
import com.alphamessenger.ui.feed.FeedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Updates of the app itself, published in the Alpha feed. A build is installed
 * only if the post is signed by the feed's key (checked by the core), the file's
 * SHA-256 is the one the post names, and the APK is this app, of that version,
 * signed with the same certificate as the installed one.
 */
object AppUpdate {
    private const val TAG = "AppUpdate"
    private const val OP = "app_update"
    private const val PREFS = "app_update"

    sealed interface State {
        object Idle : State
        class Downloading(val done: Long, val total: Long) : State
        object Verifying : State
        object Installing : State
        class Failed(val reason: Reason) : State
    }

    enum class Reason { DOWNLOAD, CHECK, PERMISSION, INSTALL }

    var state by mutableStateOf<State>(State.Idle)
        private set

    fun installedCode(ctx: Context): Long = runCatching {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    }.getOrDefault(Long.MAX_VALUE)

    /** The newest build in the feed that is newer than this one. */
    fun available(ctx: Context): FeedPost? {
        val mine = installedCode(ctx)
        return FeedStore.posts
            .filter { it.kind == FeedKind.UPDATE && it.apk != null && it.sha256 != null && it.versionCode > mine }
            .maxByOrNull { it.versionCode }
    }

    /** Bumped when "Later" is pressed, so what shows the offer looks again. */
    var laterTick by mutableStateOf(0)
        private set

    /** "Later": the chat list stops offering [versionCode] for a day (settings still show it). */
    fun later(ctx: Context, versionCode: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("later_vc", versionCode).putLong("later_until", System.currentTimeMillis() + LATER_MS).apply()
        laterTick++
    }

    fun isLater(ctx: Context, versionCode: Long): Boolean {
        laterTick
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getLong("later_vc", 0) == versionCode && System.currentTimeMillis() < p.getLong("later_until", 0)
    }

    /** What the chat list offers: the newest build, unless put off. */
    fun offerInList(ctx: Context): FeedPost? = available(ctx)?.takeUnless { isLater(ctx, it.versionCode) }

    private const val LATER_MS = 24 * 3600_000L

    /** A downloaded APK left from an update that has since gone in. */
    fun cleanUp(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = p.getString("apk", null) ?: return
        if (installedCode(ctx) >= p.getLong("vc", Long.MAX_VALUE)) {
            File(path).delete()
            p.edit().clear().apply()
        }
    }

    /** Download, check and install [post]. The system may ask the user to confirm the install. */
    suspend fun install(ctx: Context, repo: AlphaCoreRepository, post: FeedPost) {
        if (state is State.Downloading || state is State.Verifying || state is State.Installing) return
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            state = State.Failed(Reason.PERMISSION)
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        state = State.Downloading(0, 0)
        val path = coroutineScope {
            val progress = launch {
                repo.mediaProgress.filter { it.first == OP }.collect { (_, done, total) -> state = State.Downloading(done, total) }
            }
            repo.downloadMediaToCache(post.apk!!, OP).also { progress.cancel() }
        }
        if (path == null) { state = State.Failed(Reason.DOWNLOAD); return }
        state = State.Verifying
        // The media cache is encrypted at rest: checks and the installer need the plain bytes.
        val file = withContext(Dispatchers.IO) { plainCopy(app, File(path), "alpha.apk") }
        if (file == null) { state = State.Failed(Reason.DOWNLOAD); return }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("apk", file.absolutePath).putLong("vc", post.versionCode).apply()

        if (!withContext(Dispatchers.IO) { verified(app, file, post) }) {
            file.delete()
            state = State.Failed(Reason.CHECK)
            return
        }
        // The build's compile profile, if published with it: optional, never blocks the update.
        val dm = post.dm?.let { ref ->
            repo.downloadMediaToCache(ref)?.let { p -> withContext(Dispatchers.IO) { plainCopy(app, File(p), "alpha.dm") } }
        // The published profile is the Android 12+ format; older systems install without it.
        }?.takeIf { Build.VERSION.SDK_INT >= 31 && isDexMetadata(it) }
        state = State.Installing
        val ok = withContext(Dispatchers.IO) { runCatching { commit(app, file, dm) }.onFailure { Log.w(TAG, "install", it) }.isSuccess }
        if (!ok) state = State.Failed(Reason.INSTALL)
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    /** [install], kept running when the screen that started it goes away. */
    fun start(ctx: Context, repo: AlphaCoreRepository, post: FeedPost) {
        scope.launch { install(ctx.applicationContext, repo, post) }
    }

    /** A build picked for publishing: this app, signed like the installed one. */
    class Apk(val versionCode: Long, val versionName: String, val sha256: String)

    fun inspect(ctx: Context, file: File): Apk? = runCatching {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        if (apk.packageName != ctx.packageName) return null
        val mine = signers(pm.getPackageInfo(ctx.packageName, flags))
        if (mine.isEmpty() || signers(apk) != mine) return null
        Apk(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(apk), apk.versionName.orEmpty(), sha256(file))
    }.getOrNull()

    internal fun reset() { state = State.Idle }
    internal fun failed() { state = State.Failed(Reason.INSTALL) }

    private fun verified(ctx: Context, file: File, post: FeedPost): Boolean {
        val sha = sha256(file)
        if (!sha.equals(post.sha256, ignoreCase = true)) { Log.w(TAG, "sha mismatch"); return false }
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (apk.packageName != ctx.packageName) { Log.w(TAG, "other package"); return false }
        if (androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(apk) != post.versionCode) { Log.w(TAG, "version mismatch"); return false }
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (signers(apk) != signers(mine) || signers(mine).isEmpty()) { Log.w(TAG, "signer mismatch"); return false }
        return true
    }

    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun plainCopy(ctx: Context, cached: File, name: String): File? = runCatching {
        val out = File(File(ctx.cacheDir, "update").apply { mkdirs() }, name)
        if (com.alphamessenger.data.MediaVault.isSealed(cached)) com.alphamessenger.data.MediaVault.copyPlain(cached, out)
        else cached.copyTo(out, overwrite = true)
        cached.delete()
        out
    }.onFailure { Log.w(TAG, "plain copy", it) }.getOrNull()

    /** A dex metadata file: a zip holding the compile profile ("primary.prof"). */
    fun isDexMetadata(f: File): Boolean = f.length() in 1..(4L shl 20) && runCatching {
        java.util.zip.ZipFile(f).use { it.getEntry("primary.prof") != null }
    }.getOrDefault(false)

    private fun commit(ctx: Context, file: File, dm: File?) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(file.length())
            // Once this app installed itself, the next updates need no second confirmation.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("alpha.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
                session.fsync(out)
            }
            // Named like the APK: the system compiles the profiled hot code at install.
            dm?.let { d ->
                session.openWrite("alpha.dm", 0, d.length()).use { out ->
                    d.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateInstallReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }
}

/** The system installer's answer: a confirmation to show, or how it ended. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i("AppUpdate", "installer status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { AppUpdate.failed(); return }
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdate.reset()
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdate.reset() // the user said no
            else -> AppUpdate.failed()
        }
    }
}
