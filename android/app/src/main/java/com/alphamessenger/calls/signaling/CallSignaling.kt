package com.alphamessenger.calls.signaling

import com.alphamessenger.calls.CallSignal
import com.alphamessenger.calls.CallSignalCodec
import com.alphamessenger.data.AlphaCoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE calls signaling transport. Rides the existing MLS-encrypted transient
 * ChatState channel (GiftWrap / NIP-59 underneath) — so call signals get the
 * same end-to-end encryption and relay delivery as typing indicators, with
 * ZERO changes to the core messenger.
 *
 * ## Outgoing (send)
 *  [send] encodes a [CallSignal] via [CallSignalCodec] and fires it through
 *  [AlphaCoreRepository.sendChatStateDetached] (fire-and-forget, survives
 *  caller teardown).
 *
 * ## Incoming (receive)
 *  Subscribes to [AlphaCoreRepository.callSignals] (a dedicated flow that
 *  filters ChatState messages starting with "call:"). Decodes each one and
 *  emits it on [incoming]. The [Triple] carries (chatId, senderNpub, signal)
 *  so the manager can route by both.
 *
 * Fully decoupled from chat internals — depends ONLY on [AlphaCoreRepository].
 */
@Singleton
class CallSignaling @Inject constructor(
    private val core: AlphaCoreRepository,
) {
    /** Outgoing call signal + the chat it rides on. */
    private data class Outgoing(val chatId: String, val signal: CallSignal)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A decoded incoming signal and how long ago it was sent. [elsewhere]: our
     *  other phone sent it (it answered, declined or hung up there). */
    data class Incoming(val chatId: String, val senderId: String, val signal: CallSignal, val ageMs: Long, val elsewhere: Boolean = false)

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    init {
        // Subscribe to the core's dedicated call-signals flow and decode.
        scope.launch {
            core.callSignals.collect { s ->
                val elsewhere = s.state.startsWith(ELSEWHERE)
                val raw = if (elsewhere) CallSignalCodec.PREFIX + s.state.removePrefix(ELSEWHERE) else s.state
                val signal = CallSignalCodec.decode(raw) ?: return@collect
                _incoming.tryEmit(Incoming(s.chatId, s.senderId, signal, s.ageMs, elsewhere))
            }
        }
    }

    private companion object {
        const val ELSEWHERE = "call-elsewhere:"
    }

    /** Encode + send a call signal on the given chat's MLS ChatState channel.
     *  Fire-and-forget — never throws to the caller. */
    fun send(chatId: String, signal: CallSignal) {
        val encoded = CallSignalCodec.encode(signal)
        core.sendChatStateDetached(chatId, encoded)
    }
}
