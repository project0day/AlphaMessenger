package com.alphamessenger.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RecoveryKdfPublicStore — a plaintext mirror of the PUBLIC Recovery-Password
 * KDF material, kept OUTSIDE the encrypted SQLCipher database (C2 Stage 0).
 *
 * # Why this exists
 *
 * The production `DatabaseKey` is `HKDF(Argon2id(password, salt), "alpha/v1/db")`.
 * To open the encrypted DB with that key on a future launch (C2 Stage 2/3) we
 * need the `salt` + Argon2 `params` *before* the DB is open — but today they
 * live INSIDE the encrypted DB (`KV_RECOVERY_KDF`), which is the classic
 * chicken-and-egg. This store breaks the cycle by mirroring the public blob to
 * SharedPreferences, readable before any core/DB exists.
 *
 * # What is stored here — and what is NOT
 *
 * Stored: exactly the JSON returned by `core.exportRecoveryKdfPublic()`, i.e.
 * `{version, params, salt_b64, verifier_b64}` — all PUBLIC values.
 *
 * NEVER stored here (by construction — the Rust getter cannot emit them):
 *  - the Recovery Password
 *  - the MasterDataKey
 *  - the DatabaseKey or any HKDF-derived sub-key
 *
 * The `verifier` is a non-reversible hash; the `salt`/`params` are public KDF
 * metadata. None of this weakens security if read by an attacker — deriving any
 * key still requires the user's password (Argon2id, 64 MiB).
 *
 * `allowBackup="false"` in the manifest keeps this out of cloud backups.
 */
@Singleton
class RecoveryKdfPublicStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The mirrored public KDF JSON, or null if nothing has been mirrored yet. */
    fun get(): String? = prefs.getString(KEY_PUBLIC_JSON, null)?.takeIf { it.isNotBlank() }

    /** True iff a non-empty public KDF blob has been mirrored. */
    fun has(): Boolean = !get().isNullOrBlank()

    /**
     * Mirror [json] (the output of `core.exportRecoveryKdfPublic()`). A blank
     * string means "no Recovery Password set yet" and is ignored, so we never
     * overwrite a good blob with an empty one.
     */
    fun put(json: String) {
        if (json.isBlank()) return
        prefs.edit { putString(KEY_PUBLIC_JSON, json) }
    }

    /** Remove the mirror (e.g. on explicit sign-out / data wipe). */
    fun clear() {
        prefs.edit { remove(KEY_PUBLIC_JSON) }
    }

    private companion object {
        const val PREFS_NAME = "alpha_recovery_kdf_public"
        const val KEY_PUBLIC_JSON = "recovery_kdf_public_v1"
    }
}
