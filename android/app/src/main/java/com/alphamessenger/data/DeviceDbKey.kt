package com.alphamessenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key until a Recovery Password exists: 32 random bytes, stored
 * only sealed by a keystore key that never leaves this phone. A copied data
 * folder therefore opens nowhere else, even during onboarding.
 */
object DeviceDbKey {
    private const val PREFS = "alpha_device_db_key"
    private const val KEY_SEALED = "sealed"
    private const val ALIAS = "alpha_device_db"
    private const val GCM_TAG_BITS = 128

    /** The key, created on first use; empty if the keystore cannot serve one. */
    @Synchronized
    fun get(context: Context): ByteArray = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SEALED, null)?.let { return@runCatching open(it) }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // commit, not apply: the database is created under this key right after.
        check(prefs.edit().putString(KEY_SEALED, seal(raw)).commit())
        raw
    }.getOrElse {
        android.util.Log.w("DeviceDbKey", "unavailable: ${it.javaClass.simpleName}")
        ByteArray(0)
    }

    private fun seal(raw: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(raw), Base64.NO_WRAP)
    }

    private fun open(sealed: String): ByteArray {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
