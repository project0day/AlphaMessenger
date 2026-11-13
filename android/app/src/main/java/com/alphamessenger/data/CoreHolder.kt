package com.alphamessenger.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.alphamessenger.AlphaMessengerCore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CoreHolder — the single runtime holder of the [AlphaMessengerCore] (C2 Stage 3).
 *
 * # Why this exists
 *
 * The core opens the encrypted SQLCipher database in its constructor. To honour
 * the invariant **NO DB ACCESS BEFORE UNLOCK**, the core is no longer an eager
 * Hilt singleton — it is built by [UnlockManager] only AFTER the MasterDataKey is
 * available, and installed here. Everything that needs the core reaches it
 * through this holder, so a pre-unlock access is impossible (it throws).
 *
 * This is pure runtime state — it does not open the DB, build the core, or depend
 * on the core at construction time.
 */
@Singleton
class CoreHolder @Inject constructor() {

    @Volatile
    private var core: AlphaMessengerCore? = null

    private val _unlocked = MutableStateFlow(false)

    /** Observable unlock state for the UI gate (false until a core is installed). */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** True once a core has been installed (i.e. the DB has been unlocked+opened). */
    fun isUnlocked(): Boolean = core != null

    /**
     * Return the live core, or throw if the DB has not been unlocked yet. This is
     * the guard that makes pre-unlock DB access impossible: any caller that
     * reaches a repository method before unlock fails fast instead of silently
     * opening or touching the database.
     */
    fun require(): AlphaMessengerCore =
        core ?: throw IllegalStateException("AlphaMessengerCore accessed before unlock")

    /** Non-throwing peek (e.g. for services/workers that early-return if locked). */
    fun peek(): AlphaMessengerCore? = core

    /** Install the freshly-built core and flip the unlocked state. */
    fun install(c: AlphaMessengerCore) {
        core = c
        _unlocked.value = true
    }

    /** Drop the core reference (e.g. explicit lock / sign-out). */
    fun clear() {
        core = null
        _unlocked.value = false
    }
}
