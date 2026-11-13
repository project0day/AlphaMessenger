package com.alphamessenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RecoveryKeyManager — caches the MasterDataKey in the Android Keystore under
 * biometric/PIN protection (RoadmapE2EE Phase 1.3).
 *
 * # Why this exists
 *
 * Deriving the MasterDataKey requires Argon2id over the user's Recovery
 * Password — expensive (~hundreds of ms) and requires the user to retype the
 * password. We don't want to do that on every app launch. Instead:
 *
 *   1. The first time the password is entered (onboarding / unlock), we derive
 *      the MasterDataKey and encrypt it with a hardware-backed Keystore key
 *      ( StrongBox where available, else TEE-backed).
 *   2. The encrypted blob + IV live in SharedPreferences (cleartext blob is
 *      useless without the Keystore key).
 *   3. On subsequent launches, the Keystore key is unlocked with
 *      `BiometricPrompt` / device credential → the blob is decrypted → the
 *      MasterDataKey is available without retyping the password.
 *
 * # Security properties
 *
 *  - The Keystore key is created with `setUserAuthenticationRequired(true)`
 *    and a 30-second auth timeout, so it can't be used without the user
 *    authenticating.
 *  - The encrypted blob in prefs reveals nothing without the Keystore key.
 *  - The plaintext MasterDataKey is held in memory only inside a
 *    [MasterDataKey] (which zeroizes on close) and only for as long as needed.
 *  - On a new device there is no Keystore key and no blob → the only path is
 *    to re-derive from the password (Phase 4 restore flow).
 *
 * # Failure / fallback
 *
 * If the Keystore is unavailable (very old ROM, hardware failure) or the user
 * can't authenticate, every method returns null and the caller falls back to
 * the password-entry flow. We never weaken security to "make it work".
 */
@Singleton
class RecoveryKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** True iff a cached, Keystore-encrypted MasterDataKey blob exists on this device. */
    fun hasCachedKey(): Boolean =
        prefs.contains(KEY_BLOB) && prefs.contains(KEY_IV) && keyStore.containsAlias(KEY_ALIAS)

    /**
     * Encrypt and cache the [masterKey] so future unlocks can use biometrics
     * instead of the password. Called once after the password is entered.
     *
     * Creates the Keystore key (StrongBox-first) if it doesn't exist yet.
     * Returns true on success, false if the Keystore is unavailable — in that
     * case the caller should NOT block the user, but they'll need the password
     * again next launch.
     */
    fun cacheMasterKey(masterKey: MasterDataKey): Boolean {
        return try {
            val secret = getOrCreateKeystoreKey() ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val iv = cipher.iv
            val blob = cipher.doFinal(masterKey.get())
            prefs.edit {
                putString(KEY_BLOB, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
                putString(KEY_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            }
            true
        } catch (e: Exception) {
            // Keystore/StrongBox unavailable, key invalidated, etc. Non-fatal:
            // the user just won't get the biometric fast-path next launch.
            false
        }
    }

    /**
     * Returns a [Cipher] pre-initialised for ENCRYPT mode, wired to the (created
     * if needed) Keystore key. Pass it to `BiometricPrompt.CryptoObject` so the
     * encryption is cryptographically bound to a successful biometric auth —
     * then call [finishCacheMasterKey] with the authenticated cipher.
     *
     * This is the RELIABLE caching path. The old [cacheMasterKey] does a bare
     * ENCRYPT that throws `UserNotAuthenticatedException` unless an auth happened
     * within the key's timeout window — which is why enabling biometric could
     * fail with a misleading "no screen lock" message.
     */
    fun getCacheCipher(): Cipher? {
        return try {
            val secret = getOrCreateKeystoreKey()
            if (secret == null) {
                android.util.Log.w("RecoveryKeyManager", "getCacheCipher: keystore key unavailable")
                return null
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher
        } catch (e: Exception) {
            android.util.Log.w("RecoveryKeyManager", "getCacheCipher init failed: ${e.message}")
            null
        }
    }

    /**
     * Finish caching using a [Cipher] authenticated via `BiometricPrompt`
     * (obtained from [getCacheCipher]). Encrypts and stores the master key blob.
     */
    fun finishCacheMasterKey(authenticatedCipher: Cipher, masterKey: MasterDataKey): Boolean {
        return try {
            val iv = authenticatedCipher.iv
            val blob = authenticatedCipher.doFinal(masterKey.get())
            prefs.edit {
                putString(KEY_BLOB, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
                putString(KEY_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns a [Cipher] pre-initialised for DECRYPT mode, wired to the
     * Keystore key. This is the object you pass to
     * `BiometricPrompt.CryptoObject` so the unlock is cryptographically bound
     * to a successful biometric authentication.
     *
     * Returns null if no cached key exists or the Keystore key is missing.
     */
    fun getUnlockCipher(): Cipher? {
        if (!hasCachedKey()) return null
        return try {
            val (secret, iv) = prepareDecrypt() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finish the unlock using a [Cipher] that was authenticated via
     * `BiometricPrompt`. Returns the decrypted MasterDataKey, or null on any
     * failure (wrong auth, corrupted blob, key invalidated).
     */
    fun decryptCachedKey(authenticatedCipher: Cipher): MasterDataKey? {
        return try {
            val blobB64 = prefs.getString(KEY_BLOB, null) ?: return null
            val blob = android.util.Base64.decode(blobB64, android.util.Base64.NO_WRAP)
            val plain = authenticatedCipher.doFinal(blob)
            MasterDataKey(plain)
        } catch (e: Exception) {
            null
        }
    }

    /** Remove the cached key + Keystore alias (e.g. on explicit "sign out"). */
    fun clearCachedKey() {
        prefs.edit { remove(KEY_BLOB); remove(KEY_IV) }
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }

    // ── internals ────────────────────────────────────────────────

    private fun getOrCreateKeystoreKey(): SecretKey? {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // Build the spec; auth-per-use + biometric for reliable CryptoObject use.
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
            if (strongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                b.setIsStrongBoxBacked(true)
            }
            return b.build()
        }
        // Try StrongBox first; if unavailable, fall back to TEE-backed.
        generateWithSpec(spec(strongBox = true))?.let { return it }
        return generateWithSpec(spec(strongBox = false))
    }

    private fun generateWithSpec(spec: KeyGenParameterSpec): SecretKey? = try {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(spec)
        gen.generateKey()
    } catch (e: Exception) {
        android.util.Log.w("RecoveryKeyManager", "Keystore key gen failed (strongBox in spec): ${e.message}")
        null
    }

    private fun prepareDecrypt(): Pair<SecretKey, ByteArray>? {
        val secret = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: return null
        val iv = android.util.Base64.decode(
            prefs.getString(KEY_IV, null) ?: return null,
            android.util.Base64.NO_WRAP,
        )
        return secret to iv
    }

    private companion object {
        const val PREFS_NAME = "alpha_recovery_key"
        const val KEY_BLOB = "master_blob"
        const val KEY_IV = "master_iv"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        // v2: auth-per-use (timeout 0) key for reliable CryptoObject binding.
        const val KEY_ALIAS = "alpha.masterdatakey.v2"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
