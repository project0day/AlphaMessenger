package com.alphamessenger.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Core FFI module (C2 Stage 3).
 *
 * # No DB providers — by design
 *
 * The [uniffi.alphamessenger.AlphaMessengerCore] is NO LONGER provided here as
 * an eager Hilt singleton. The old `provideAlphaMessengerCore` opened the
 * encrypted SQLCipher database in `Application` field-injection — i.e. BEFORE any
 * unlock — which violated the invariant **NO DB ACCESS BEFORE UNLOCK**.
 *
 * The core is now built exactly once, after the MasterDataKey is available, by
 * [com.alphamessenger.data.UnlockManager] (the single DB-lifecycle entrypoint)
 * and held in [com.alphamessenger.data.CoreHolder]. Reaching it through the
 * holder makes a pre-unlock DB access impossible (it throws).
 *
 * [com.alphamessenger.data.AlphaCoreRepository], [CoreHolder], [UnlockManager]
 * and [com.alphamessenger.data.CallbackBridge] are all provided by Hilt through
 * their `@Inject` constructors and need no `@Provides` here — and crucially none
 * of them transitively constructs the core, so there is no eager DB creation in
 * the Hilt graph. The default relay set + db path now live in [UnlockManager].
 *
 * This module is intentionally empty: it stays as the documented home for any
 * future *non-DB* FFI-layer providers.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule
