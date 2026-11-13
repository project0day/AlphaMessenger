package com.alphamessenger.data

import android.app.ActivityManager
import android.content.Context
import java.security.KeyStore

/**
 * Panic wipe — erases ALL LOCAL data and restarts the app as if freshly
 * installed. Triggered by entering the Panic PIN at the lock screen.
 *
 * Scope (deliberately LOCAL only):
 *  - SQLCipher DB (messages, contacts, MLS state), caches, attachments,
 *    SharedPreferences (onboarding flag, app-lock, theme), WorkManager, etc.
 *    All of these live in the app's data dir, wiped atomically by the OS via
 *    [ActivityManager.clearApplicationUserData].
 *  - Hardware Keystore entries (the encrypted MasterDataKey blob's key) —
 *    cleared explicitly; without the (now-deleted) blob they're inert anyway.
 *
 * Does NOT touch: the Nostr account, the cloud (Blossom) backup, or any other
 * device. The user can fully restore later with `nsec` + Recovery Password.
 *
 * `clearApplicationUserData()` terminates the process immediately after wiping,
 * so the next launch starts at onboarding (the `onboarding_completed` flag is
 * gone with the prefs).
 */
object PanicWipe {

    fun wipe(context: Context) {
        // Best-effort: drop hardware-Keystore key material first. The app-data
        // wipe below removes the encrypted blobs that these keys protect, but
        // we also delete the keys so nothing lingers in the system keystore.
        // The app's own keystore holds only our keys (ours and the libraries'
        // master keys alike): all of them go.
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (a in ks.aliases().toList()) runCatching { ks.deleteEntry(a) }
        }

        // Atomic, OS-level wipe of the entire app data dir (DB + caches +
        // prefs + files). This call does NOT return — it kills the process.
        val am = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ok = runCatching { am.clearApplicationUserData() }.getOrDefault(false)

        if (!ok) {
            // Refused by the system (some vendors): delete everything ourselves —
            // the whole data dir (database, files, caches, every preference file,
            // no-backup), and the external dirs — then exit.
            val ctx = context.applicationContext
            runCatching {
                ctx.dataDir.listFiles()?.forEach { f -> if (f.name != "lib") f.deleteRecursively() }
            }
            runCatching { ctx.externalCacheDirs.forEach { it?.deleteRecursively() } }
            runCatching { ctx.getExternalFilesDirs(null).forEach { it?.deleteRecursively() } }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
