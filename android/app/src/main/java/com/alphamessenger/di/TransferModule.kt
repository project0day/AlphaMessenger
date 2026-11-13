package com.alphamessenger.di

import android.content.Context
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.data.MediaAutoDownload
import com.alphamessenger.data.blobHashOf
import com.alphamessenger.transfer.CacheEntry
import com.alphamessenger.transfer.TransferEngine
import com.alphamessenger.transfer.TransferPolicy
import com.alphamessenger.transfer.TransferRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * App-scoped Transfer subsystem (track C fix — "progress lost on chat re-entry").
 *
 * The [TransferEngine] used to be a `ChatViewModel` field built with
 * `scope = viewModelScope`. `ChatViewModel` is a `NavBackStackEntry`-scoped
 * `@HiltViewModel`, so leaving a chat cleared the VM → the engine's whole live
 * state map (rings, %, ETA, album groups) was discarded and its coroutine scope
 * was cancelled. Re-entering built a fresh, empty engine, so an in-flight bubble
 * fell back to ▶ (sender's own file) or ↓ (receiver) — the ring vanished.
 *
 * Making the engine a process-lifetime `@Singleton` with a process-lifetime
 * coroutine scope fixes this at the root: the live state and the download/upload
 * jobs now genuinely OUTLIVE any single chat screen. `retainOnly` was hardened to
 * never prune a key that is still active/paused in another chat (see its comment).
 */
@Module
@InstallIn(SingletonComponent::class)
object TransferModule {

    @Provides
    @Singleton
    fun provideTransferEngine(
        repo: TransferRepository,
        @ApplicationContext appContext: Context,
    ): TransferEngine = TransferEngine(
        // Process-lifetime scope — transfers must outlive any single chat screen.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        repo = repo,
        // The ONE place the auto-download policy is evaluated (never in a bubble).
        policy = TransferPolicy { mt, fn, sz -> MediaAutoDownload.shouldAuto(appContext, mt, fn, sz) },
    )

    /**
     * The engine's view of the data layer. Depends ONLY on app-scoped singletons
     * ([AlphaCoreRepository], the app [Context]) — no ChatViewModel coupling — so
     * it can be a process singleton alongside the engine. `downloadTracked` also
     * warms [com.alphamessenger.ui.chat.MediaPathCache] so the next open is instant
     * (this replaces the old `ChatViewModel.loadMediaTracked` indirection).
     */
    @Provides
    @Singleton
    fun provideTransferRepository(
        repository: AlphaCoreRepository,
        @ApplicationContext appContext: Context,
    ): TransferRepository = object : TransferRepository {
        override fun cacheEntry(ref: String): CacheEntry = repository.cacheEntry(ref).let {
            CacheEntry(
                exists = it.exists, localPath = it.localPath, verified = it.verified,
                size = it.size, thumbExists = it.thumbExists, streamReady = it.streamReady,
                lastAccessMs = it.lastAccessMs,
            )
        }

        override fun mediaAvailability(ref: String, senderLocalPath: String?) =
            repository.mediaAvailability(ref, senderLocalPath)

        override suspend fun downloadTracked(ref: String, opId: String): String? =
            repository.downloadMediaToCache(ref, opId)?.also {
                com.alphamessenger.ui.chat.MediaPathCache[ref] = it
            }

        override fun cancelDownload(opId: String) = repository.cancelMediaDownload(opId)

        override fun deleteCache(ref: String, deleteLocalCopy: Boolean): Long =
            repository.deleteMediaCacheBytes(ref, deleteLocalCopy)

        // Eviction marker: a `<hash>.evicted` file in cacheDir/media/ — the
        // Repository's disk domain (NOT SharedPreferences). Survives recreation.
        private fun evictedFile(ref: String) =
            java.io.File(java.io.File(appContext.cacheDir, "media"), "${blobHashOf(ref)}.evicted")

        override fun markCacheEvicted(ref: String) {
            evictedFile(ref).apply { parentFile?.mkdirs(); createNewFile() }
        }

        override fun unmarkCacheEvicted(ref: String) {
            evictedFile(ref).delete()
        }

        override fun isCacheEvicted(ref: String): Boolean = evictedFile(ref).exists()

        override val progress get() = repository.mediaProgress
        override val blobServer get() = "blossom.primal.net"
    }
}
