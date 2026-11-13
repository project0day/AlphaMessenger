package com.alphamessenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key derived from the Recovery Password, kept on this phone sealed
 * by a keystore key that needs no fingerprint. After a restart the app can then
 * open its database by itself and keep receiving messages; the password is only
 * asked on a new phone. The app lock (PIN, fingerprint) still guards the screen.
 *
 * "Ask for the password after a restart" turns it off: nothing is kept then.
 */
object RememberedDbKey {
    private const val PREFS = "alpha_remembered_db_key"
    private const val KEY_SEALED = "sealed"
    private const val KEY_STRICT = "ask_after_restart"
    private const val ALIAS = "alpha_remembered_db"
    private const val GCM_TAG_BITS = 128

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The password is asked after every restart; messages wait until it is entered. */
    fun askAfterRestart(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_STRICT, false)

    fun setAskAfterRestart(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_STRICT, on).apply()
        if (on) clear(ctx)
    }

    fun has(ctx: Context): Boolean = prefs(ctx).contains(KEY_SEALED)

    /** Keeps [key] (32 bytes) unless the strict mode is on. */
    @Synchronized
    fun save(ctx: Context, key: ByteArray) {
        if (key.size != 32 || askAfterRestart(ctx)) return
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
            prefs(ctx).edit().putString(KEY_SEALED, Base64.encodeToString(c.iv + c.doFinal(key), Base64.NO_WRAP)).commit()
        }.onFailure { android.util.Log.w("RememberedDbKey", "not kept: ${it.javaClass.simpleName}") }
    }

    /** The kept key, or null (none kept, strict mode, or the keystore cannot open it). */
    @Synchronized
    fun get(ctx: Context): ByteArray? {
        if (askAfterRestart(ctx)) return null
        val sealed = prefs(ctx).getString(KEY_SEALED, null) ?: return null
        return runCatching {
            val b = Base64.decode(sealed, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, b, 0, 12))
                doFinal(b, 12, b.size - 12)
            }
        }.getOrNull()
    }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SEALED).commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
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
