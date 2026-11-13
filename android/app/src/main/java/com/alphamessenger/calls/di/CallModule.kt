package com.alphamessenger.calls.di

import com.alphamessenger.calls.engine.CallRendererBridge
import com.alphamessenger.calls.engine.WebRtcEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Calls-module DI bindings. The UI injects [CallRendererBridge] (the tiny
 * renderer interface) — this maps it to the [WebRtcEngine] singleton that
 * implements it, keeping the UI decoupled from the WebRTC library.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {

    @Binds
    abstract fun bindRendererBridge(engine: WebRtcEngine): CallRendererBridge
}
