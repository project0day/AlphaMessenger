package com.alphamessenger.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alphamessenger.data.AlphaCoreRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Wakes at a scheduled message's time and asks the core to publish every held
 * message that is due. The core also releases them on its periodic tick; this
 * only makes the time exact while the app is closed.
 */
@HiltWorker
class ReleaseHeldWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AlphaCoreRepository,
    private val coreHolder: com.alphamessenger.data.CoreHolder,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!coreHolder.isUnlocked()) return Result.retry()
        repository.releaseDueHeld()
        return Result.success()
    }

    companion object {
        /** One wake-up per distinct time; re-arming the same time is a no-op. */
        fun schedule(ctx: Context, atMillis: Long) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val req = OneTimeWorkRequestBuilder<ReleaseHeldWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("release_held_${atMillis / 1000}", ExistingWorkPolicy.KEEP, req)
        }
    }
}
