package com.alphamessenger.data

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local app-lock settings: biometric flag + a PBKDF2-hashed PIN.
 *
 * This gates the UI only — it is independent of the data-encryption keys
 * (Recovery Password / SQLCipher). The PIN is never stored in clear: we keep a
 * random salt + PBKDF2-HMAC-SHA256 hash so the stored value can't be reversed.
 *
 * Stored in a dedicated SharedPreferences file (not the encrypted DB) because
 * it must be readable BEFORE anything is unlocked, at the very first frame.
 */
class AppLockManager(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Biometric ---
    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) { prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply() }

    // --- PIN ---
    val isPinSet: Boolean
        get() = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()

    /** Set (or replace) the PIN. Stores salt + a keyed digest, never the PIN.
     *  False when the keystore cannot key it: then nothing is stored. */
    fun setPin(pin: String): Boolean = store(KEY_PIN_SALT, KEY_PIN_HASH, KEY_PIN_LEN, pin)

    fun verifyPin(pin: String): Boolean = verify(KEY_PIN_SALT, KEY_PIN_HASH, KEY_PIN_LEN, pin)

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).remove(KEY_PIN_LEN).apply()
    }

    /**
     * Lengths of the PINs that are set, once known (a PIN set before lengths were
     * kept is learnt at its next good entry). Empty = not known yet.
     */
    fun pinLengths(): Set<Int> = buildSet {
        if (isPinSet) prefs.getInt(KEY_PIN_LEN, 0).takeIf { it > 0 }?.let(::add) ?: return emptySet()
        if (isPanicPinSet) prefs.getInt(KEY_PANIC_LEN, 0).takeIf { it > 0 }?.let(::add) ?: return emptySet()
    }

    // --- Panic PIN ---
    // A SECOND, distinct PIN. Entering it at the lock screen wipes LOCAL data
    // (DB, MLS, caches, secure storage) and logs out — leaving the app like a
    // fresh install. Account is recoverable later via nsec + Recovery Password.
    val isPanicPinSet: Boolean
        get() = !prefs.getString(KEY_PANIC_HASH, null).isNullOrBlank()

    fun setPanicPin(pin: String): Boolean = store(KEY_PANIC_SALT, KEY_PANIC_HASH, KEY_PANIC_LEN, pin)

    fun verifyPanicPin(pin: String): Boolean = verify(KEY_PANIC_SALT, KEY_PANIC_HASH, KEY_PANIC_LEN, pin)

    fun clearPanicPin() {
        prefs.edit().remove(KEY_PANIC_HASH).remove(KEY_PANIC_SALT).remove(KEY_PANIC_LEN).apply()
    }

    // --- Failed-attempt throttling ---
    /**
     * Wrong PINs so far, sealed with the keystore key: a count edited in the
     * preferences file reads as a long run of failures, never as zero.
     */
    var failedAttempts: Int
        get() {
            val raw = prefs.getString(KEY_FAILED_SEALED, null) ?: run {
                // A count from before sealing: taken over once.
                val old = runCatching { prefs.getInt(KEY_FAILED_ATTEMPTS, 0) }.getOrDefault(0)
                if (prefs.contains(KEY_FAILED_ATTEMPTS)) failedAttempts = old
                return old
            }
            val n = raw.substringBefore(':').toIntOrNull() ?: return TAMPERED_ATTEMPTS
            val ok = runCatching {
                java.security.MessageDigest.isEqual(keyedDigest("attempts:$n".toByteArray()), Base64.decode(raw.substringAfter(':'), Base64.NO_WRAP))
            }.getOrDefault(false)
            return if (ok) n else TAMPERED_ATTEMPTS
        }
        set(value) {
            val mac = runCatching { b64(keyedDigest("attempts:$value".toByteArray())) }.getOrNull()
            val e = prefs.edit().remove(KEY_FAILED_ATTEMPTS)
            if (mac != null) e.putString(KEY_FAILED_SEALED, "$value:$mac") else e.remove(KEY_FAILED_SEALED)
            e.apply()
        }

    /**
     * How long PIN entry stays blocked. Counted on the boot clock, so setting the
     * date forward does not end it; after a reboot the whole pause starts over.
     */
    fun lockoutRemainingMs(): Long {
        val pause = prefs.getLong(KEY_LOCKOUT_PAUSE, 0L)
        if (pause <= 0L) return 0L
        val now = android.os.SystemClock.elapsedRealtime()
        if (prefs.getInt(KEY_LOCKOUT_BOOT, -1) != bootCount) {
            prefs.edit().putInt(KEY_LOCKOUT_BOOT, bootCount).putLong(KEY_LOCKOUT_AT, now).apply()
        }
        return (prefs.getLong(KEY_LOCKOUT_AT, now) + pause - now).coerceAtLeast(0L)
    }

    /** Register a wrong PIN. From the 5th failure on, block for 30 s, doubling each time (max 1 h). */
    fun registerFailure() {
        val n = failedAttempts + 1
        failedAttempts = n
        if (n >= 5) {
            val pause = (30_000L shl (n - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
            prefs.edit()
                .putLong(KEY_LOCKOUT_PAUSE, pause)
                .putLong(KEY_LOCKOUT_AT, android.os.SystemClock.elapsedRealtime())
                .putInt(KEY_LOCKOUT_BOOT, bootCount)
                .apply()
        }
    }

    fun registerSuccess() {
        failedAttempts = 0
        prefs.edit().remove(KEY_LOCKOUT_PAUSE).remove(KEY_LOCKOUT_AT).remove(KEY_LOCKOUT_BOOT).apply()
    }

    private val bootCount: Int
        get() = runCatching {
            android.provider.Settings.Global.getInt(appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
        }.getOrDefault(0)

    /** Reply from a notification while the app is locked. Off by default: the
     *  shade would otherwise send in our name without the lock. */
    var replyFromNotifications: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_REPLY, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIF_REPLY, value).apply() }

    /** Whether a notification may carry Reply and send it. */
    val notificationReplyAllowed: Boolean
        get() = !isLockEnabled || replyFromNotifications

    // --- Auto-lock delay ---
    /** Milliseconds the app may stay in the background before it re-locks.
     *  0 = lock immediately. */
    var autoLockDelayMs: Long
        get() = prefs.getLong(KEY_AUTOLOCK_MS, 0L)
        set(value) { prefs.edit().putLong(KEY_AUTOLOCK_MS, value).apply() }

    /** True when ANY lock method is active — drives the launch-time gate. */
    val isLockEnabled: Boolean
        get() = biometricEnabled || isPinSet

    /** `SystemClock.elapsedRealtime()` of when the app last went to background.
     *  Persisted so the auto-lock delay is honoured even after the Activity is
     *  recreated (e.g. a recents-swipe that keeps the process but rebuilds the UI).
     *  elapsedRealtime resets on reboot — callers treat now < stored as expired. */
    var lastBackgroundedElapsed: Long
        get() = prefs.getLong(KEY_LAST_BG_ELAPSED, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_BG_ELAPSED, value).apply() }

    /**
     * Whether the app may start UNLOCKED (skip the AppLock overlay) given the
     * configured auto-lock delay and the last time it was backgrounded. Lock when:
     *  no lock set is irrelevant (caller checks isLockEnabled), delay is 0 ("Immediately"),
     * never backgrounded, the clock reset (reboot), or the window has elapsed.
     */
    fun mayStartUnlocked(): Boolean {
        val delay = autoLockDelayMs
        if (delay <= 0L) return false
        val last = lastBackgroundedElapsed
        if (last <= 0L) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < last) return false // reboot / clock reset → require unlock
        return (now - last) < delay
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** Only ever keyed: a digest without the keystore key could be brute-forced
     *  from a copied file, so without the keystore nothing is stored. */
    private fun store(saltKey: String, hashKey: String, lenKey: String, pin: String): Boolean {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keyed = runCatching { KEYED + b64(keyedDigest(pbkdf2(pin, salt))) }.getOrNull() ?: return false
        val ok = prefs.edit().putString(saltKey, b64(salt)).putString(hashKey, keyed).putInt(lenKey, pin.length).commit()
        keyedOnly()
        return ok
    }

    /** Whether unkeyed digests are over for good; the mark is set the first
     *  time no stored PIN is unkeyed, and never cleared. */
    private fun keyedOnly(): Boolean {
        if (prefs.getBoolean(KEY_KEYED_ONLY, false)) return true
        val unkeyed = listOf(KEY_PIN_HASH, KEY_PANIC_HASH).any { k ->
            prefs.getString(k, null)?.let { it.isNotBlank() && !it.startsWith(KEYED) } == true
        }
        if (!unkeyed) prefs.edit().putBoolean(KEY_KEYED_ONLY, true).apply()
        return !unkeyed
    }

    /**
     * A PIN stored before keyed digests opens once more and is re-stored
     * keyed. Once every stored PIN is keyed an unkeyed one is never accepted:
     * a digest planted in the preferences file opens nothing.
     */
    private fun verify(saltKey: String, hashKey: String, lenKey: String, pin: String): Boolean {
        val salt = prefs.getString(saltKey, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val stored = prefs.getString(hashKey, null) ?: return false
        val derived = pbkdf2(pin, salt)
        val ok = if (stored.startsWith(KEYED)) {
            runCatching { java.security.MessageDigest.isEqual(keyedDigest(derived), Base64.decode(stored.removePrefix(KEYED), Base64.NO_WRAP)) }
                .getOrDefault(false)
        } else {
            !keyedOnly() && java.security.MessageDigest.isEqual(derived, Base64.decode(stored, Base64.NO_WRAP))
        }
        if (ok && !stored.startsWith(KEYED)) store(saltKey, hashKey, lenKey, pin)
        if (ok && prefs.getInt(lenKey, 0) != pin.length) prefs.edit().putInt(lenKey, pin.length).apply()
        return ok
    }

    /**
     * The PIN digest passes through an HMAC key that never leaves this phone's
     * keystore: a copied preferences file cannot be brute-forced elsewhere.
     */
    private fun keyedDigest(derived: ByteArray): ByteArray {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getKey(HMAC_ALIAS, null) as? javax.crypto.SecretKey) ?: javax.crypto.KeyGenerator
            .getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            .apply {
                init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        HMAC_ALIAS, android.security.keystore.KeyProperties.PURPOSE_SIGN,
                    ).build(),
                )
            }
            .generateKey()
        return javax.crypto.Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(derived)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private companion object {
        const val PREFS = "alpha_app_lock"
        const val KEY_BIOMETRIC = "biometric_enabled"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PANIC_HASH = "panic_pin_hash"
        const val KEY_PANIC_SALT = "panic_pin_salt"
        const val KEY_PIN_LEN = "pin_len"
        const val KEY_NOTIF_REPLY = "notif_reply_when_locked"
        const val KEY_PANIC_LEN = "panic_pin_len"
        const val KEY_AUTOLOCK_MS = "autolock_delay_ms"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_FAILED_SEALED = "failed_attempts_sealed"
        const val KEY_KEYED_ONLY = "pin_keyed_only"
        /** What a tampered attempt count reads as: the longest pause. */
        const val TAMPERED_ATTEMPTS = 12
        const val KEY_LOCKOUT_PAUSE = "lockout_pause_ms"
        const val KEY_LOCKOUT_AT = "lockout_at_elapsed"
        const val KEY_LOCKOUT_BOOT = "lockout_boot"
        const val KEYED = "k1:"
        const val HMAC_ALIAS = "alpha_applock_hmac"
        const val KEY_LAST_BG_ELAPSED = "last_backgrounded_elapsed"
        const val ITERATIONS = 120_000
        const val KEY_BITS = 256
    }
}
