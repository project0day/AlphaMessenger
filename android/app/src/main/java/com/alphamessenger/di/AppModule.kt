package com.alphamessenger.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "alpha_settings"
)

/**
 * Application-scoped DI module.
 *
 * Provides foundational infrastructure that is shared across the entire app:
 * - [DataStore] for preferences persistence
 * - [CoroutineScope] for long-lived background work (repositories, sync, etc.)
 *
 * Note: `@ApplicationContext Context` is provided automatically by Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.dataStore
    }

    /**
     * Application-scoped [CoroutineScope] tied to the process lifecycle.
     *
     * Uses [SupervisorJob] so that a failure in one child coroutine does not
     * cancel siblings. The [Dispatchers.IO] default dispatcher is appropriate
     * for the repository's FFI calls. UI-level work should override the
     * dispatcher at the call site.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

}
