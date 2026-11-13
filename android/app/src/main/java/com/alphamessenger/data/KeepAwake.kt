package com.alphamessenger.data

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU awake while a transfer runs. The sync service keeps the process
 * alive, but with the screen off Android still suspends the CPU, and an upload
 * or download stalls until the phone is woken. Counted: held while any transfer
 * runs, released when the last one ends; each start renews a safety cap.
 */
class KeepAwake(context: Context, tag: String) {
    private val lock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        .apply { setReferenceCounted(false) }
    private var holders = 0

    inline fun <T> during(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun acquire() = synchronized(this) {
        holders++
        lock.acquire(CAP_MS)
    }

    fun release() = synchronized(this) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0 && lock.isHeld) lock.release()
    }

    private companion object {
        const val CAP_MS = 30 * 60_000L
    }
}
