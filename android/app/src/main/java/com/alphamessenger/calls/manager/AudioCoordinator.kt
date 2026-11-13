package com.alphamessenger.calls.manager

import com.alphamessenger.calls.AudioRoute
import com.alphamessenger.calls.audio.AudioProcessingConfig
import com.alphamessenger.calls.audio.AudioProcessingConfigProvider
import com.alphamessenger.calls.audio.CallAudioRouter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates AUDIO for a call — wraps [CallAudioRouter] (output routing +
 * ringtone/ringback) with the [AudioProcessingConfig] (NS/AEC/AGC). Keeps the
 * audio DSP concerns in ONE place, separate from the WebRTC engine.
 *
 * Owned by [CallManager]; never instantiated elsewhere.
 */
@Singleton
class AudioCoordinator @Inject constructor(
    private val router: CallAudioRouter,
) {
    /** The DSP config (NS/AEC/AGC) — read by the engine when it builds the
     *  AudioSource. Exposed here so the manager can pass it down. */
    val processingConfig: AudioProcessingConfig
        get() = AudioProcessingConfigProvider.current

    // ── Delegated to CallAudioRouter ─────────────────────────────────────

    fun start(video: Boolean) = router.start(video)
    fun stop() = router.stop()
    fun setRoute(route: AudioRoute) = router.setRoute(route)
    fun setMicMuted(muted: Boolean) = router.setMicMuted(muted)
    fun playRingtone() = router.playRingtone()
    fun stopRingtone() = router.stopRingtone()
    fun playRingback() = router.playRingback()
    fun stopRingback() = router.stopRingback()
    fun playEndTone() = router.playEndTone()
    fun playBusyTone() = router.playBusyTone()
    fun hapticAccept() = router.hapticAccept()
    fun hapticEnd() = router.hapticEnd()
    fun refreshBluetoothAvailable() = router.refreshBluetoothAvailable()
    val route: kotlinx.coroutines.flow.StateFlow<AudioRoute> get() = router.route
    val bluetoothAvailable: kotlinx.coroutines.flow.StateFlow<Boolean> get() = router.bluetoothAvailable
}
