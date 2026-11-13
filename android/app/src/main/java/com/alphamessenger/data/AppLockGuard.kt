package com.alphamessenger.data

import android.os.SystemClock

/**
 * AppLockGuard — suppresses the secondary AppLock (PIN/biometric) auto-lock for
 * the brief background that happens when the app launches an in-app
 * ActivityResult (gallery/crop/camera/file picker, etc.).
 *
 * # The bug this fixes
 *
 * Picking an avatar (or any media) backgrounds our Activity while the system
 * picker is shown. With AppLock auto-lock delay = 0 ("lock immediately"), the
 * lifecycle ON_STOP→ON_START round-trip re-locked the app the moment the user
 * came back from the picker — and re-unlocking rebuilt the NavHost, dumping the
 * user back on the chat list and losing the screen they were on.
 *
 * # How it works
 *
 * [MainActivity] overrides `startActivityForResult` / `startIntentSenderForResult`
 * (the single choke point every Compose `launcher.launch()` goes through in this
 * single-Activity app) and calls [armPickerExemption]. On the next foreground
 * the AppLock observer calls [consumeExemption]; if armed (and within the time
 * window), it skips the re-lock exactly once.
 *
 * This concerns ONLY the secondary AppLock UX barrier — it does NOT touch the C2
 * DB unlock ([CoreHolder]/[UnlockManager]), which never re-locks on background.
 */
object AppLockGuard {
    @Volatile
    private var exemptUntilElapsed = 0L

    // Enough to pick, crop or shoot; after it the app re-locks normally so a
    // phone left on the picker is protected soon.
    private const val WINDOW_MS = 2 * 60 * 1000L

    /** Arm a one-shot exemption for the imminent in-app activity result. */
    fun armPickerExemption() {
        exemptUntilElapsed = SystemClock.elapsedRealtime() + WINDOW_MS
    }

    /** Consume the exemption: returns true (skip the lock) iff armed and unexpired. */
    fun consumeExemption(): Boolean {
        val exempt = SystemClock.elapsedRealtime() < exemptUntilElapsed
        exemptUntilElapsed = 0L
        return exempt
    }
}
