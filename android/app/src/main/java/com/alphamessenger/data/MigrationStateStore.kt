package com.alphamessenger.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MigrationStateStore — plaintext PRE-OPEN HINT for the C2 SQLCipher production-key
 * migration state machine.
 *
 * # Role and authority
 *
 * The migration state (legacy → in_progress → migrated → confirmed) has TWO
 * copies:
 *  - **DB KV** (`db_rekey_state_v1`, inside the encrypted DB) — the SINGLE SOURCE
 *    OF TRUTH once the DB is open.
 *  - **This store** (SharedPreferences) — a HINT, readable BEFORE the DB is open,
 *    used in Stage 3 to decide which key to try first.
 *
 * **No migration decision may be made on this hint alone.** After the DB opens,
 * code reconciles against DB KV and DB KV wins on any disagreement. This store
 * only narrows down which key to attempt first; the actual open validates it.
 *
 * Contains only a short state string — no keys, no secrets. `allowBackup="false"`
 * keeps it off cloud backups; a full app-data wipe clears it together with the
 * DB, so the two never desync in a dangerous direction.
 */
@Singleton
class MigrationStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The pre-open hint, or [LEGACY] if nothing has been written yet. */
    fun getHint(): String = prefs.getString(KEY_STATE, null)?.takeIf { it.isNotBlank() } ?: LEGACY

    /** Write the pre-open hint. Should track the DB KV state around migrate/confirm. */
    fun setHint(state: String) {
        prefs.edit { putString(KEY_STATE, state) }
    }

    /**
     * The one-shot recovery flag mirror (C2 Stage 3). True if a previous launch
     * already performed a restore→legacy fallback for a `migrated`/`in_progress`
     * DB that failed to open under db_key. Read BEFORE open and passed into
     * `newUnlocked` so a SECOND failure becomes a hard error instead of an
     * infinite restore loop. The DB KV row remains authoritative; this is the
     * pre-open mirror. Cleared on a successful confirm.
     */
    fun getRecoveryAttempted(): Boolean = prefs.getBoolean(KEY_RECOVERY_ATTEMPTED, false)

    fun setRecoveryAttempted(value: Boolean) {
        prefs.edit { putBoolean(KEY_RECOVERY_ATTEMPTED, value) }
    }

    /** Clear the hint + recovery flag (e.g. explicit sign-out / data wipe / confirm). */
    fun clear() {
        prefs.edit { remove(KEY_STATE); remove(KEY_RECOVERY_ATTEMPTED) }
    }

    companion object {
        const val LEGACY = "legacy"
        const val IN_PROGRESS = "in_progress"
        const val MIGRATED = "migrated"
        const val CONFIRMED = "confirmed"

        private const val PREFS_NAME = "alpha_migration_state"
        private const val KEY_STATE = "db_rekey_state_v1"
        private const val KEY_RECOVERY_ATTEMPTED = "migration_recovery_attempted_v1"
    }
}
