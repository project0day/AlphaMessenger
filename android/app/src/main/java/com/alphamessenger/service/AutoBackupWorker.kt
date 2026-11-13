package com.alphamessenger.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alphamessenger.data.AlphaCoreRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The daily backup: once a day, on charge and Wi-Fi, the encrypted copy is
 * rebuilt and uploaded with the key the last manual backup remembered — no
 * password prompt. Skipped quietly while the app is locked or before the
 * first manual backup.
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AlphaCoreRepository,
    private val coreHolder: com.alphamessenger.data.CoreHolder,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!isEnabled(applicationContext)) return Result.success()
        if (!coreHolder.isUnlocked() || !repository.autoBackupReady()) return Result.success()
        return runCatching { repository.uploadBackupAuto() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val WORK = "alpha_daily_backup"
        private const val PREFS = "backup_prefs"
        private const val KEY_DAILY = "daily"

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DAILY, true)

        fun setEnabled(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DAILY, enabled).apply()
            schedule(ctx)
        }

        /** Keep the periodic job in line with the switch. Safe to call on every start. */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!isEnabled(ctx)) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
