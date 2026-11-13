package com.alphamessenger.calls.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import com.alphamessenger.calls.AudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE audio router — manages audio focus, output routes (earpiece/speaker/
 * wired/BT), and the ringtone/ringback tones.
 *
 * ## Lifecycle
 *  - [start] (call connects): request focus, set communication mode, route to
 *    the default (earpiece for audio, speaker for video).
 *  - [setRoute]: switch output on demand.
 *  - [setMicMuted]: mute/unmute the mic.
 *  - [stop]: abandon focus, restore normal mode.
 *
 * ## Ringtones
 *  - [playRingtone] / [stopRingtone]: the incoming-call ringtone (looping).
 *  - [playRingback] / [stopRingback]: the "calling..." tone (the user hears
 *    while the peer device rings).
 *
 * ## Bluetooth / wired
 *  Routes are detected via [AudioManager] — when a BT headset connects, the
 *  system reports ROUTE_BLUETOOTH; the UI exposes the BT button only when
 *  [bluetoothAvailable] is true.
 */
@Singleton
class CallAudioRouter @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val am: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _route = MutableStateFlow(AudioRoute.EARPIECE)
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    private val _bluetoothAvailable = MutableStateFlow(false)
    val bluetoothAvailable: StateFlow<Boolean> = _bluetoothAvailable.asStateFlow()

    private var ringtonePlayer: MediaPlayer? = null
    private var ringbackTone: android.media.ToneGenerator? = null
    private var shortTone: android.media.ToneGenerator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var started = false

    /** Begin audio management for a call. [video] = default to speaker. */
    fun start(video: Boolean) {
        if (started) return
        started = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        // Default route: speaker for video, earpiece for audio.
        val initial = if (video) AudioRoute.SPEAKER else detectInitialRoute()
        setRoute(initial)
        refreshBluetoothAvailable()
    }

    /** Switch the audio output route. BLUETOOTH starts SCO (the actual voice
     *  link to the headset); every other route stops it. */
    fun setRoute(target: AudioRoute) {
        _route.value = target
        when (target) {
            AudioRoute.SPEAKER -> { stopSco(); am.isSpeakerphoneOn = true }
            AudioRoute.EARPIECE -> { stopSco(); am.isSpeakerphoneOn = false }
            AudioRoute.WIRED_HEADSET -> { stopSco(); am.isSpeakerphoneOn = false }
            AudioRoute.BLUETOOTH -> {
                am.isSpeakerphoneOn = false
                startSco()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startSco() {
        runCatching {
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
        }
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        runCatching {
            if (am.isBluetoothScoOn) {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
        }
    }

    /** Mute/unmute the mic (system audio source). */
    fun setMicMuted(muted: Boolean) {
        am.isMicrophoneMute = muted
    }

    /** Play the incoming-call ringtone (looping, the user's system ringtone)
     *  + vibration. Respects the ringer mode: NORMAL → ring + vibrate,
     *  VIBRATE → vibrate only, SILENT/DND → neither. */
    fun playRingtone() {
        stopRingtone()
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) startVibration()
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            ?: return
        val tp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setLooping(true)
            try {
                setDataSource(context, uri)
                prepare()
                start()
            } catch (_: Exception) { /* fallback silent */ }
        }
        ringtonePlayer = tp
    }

    fun stopRingtone() {
        ringtonePlayer?.let { runCatching { it.stop(); it.release() } }
        ringtonePlayer = null
        stopVibration()
    }

    // --- Incoming-call vibration ---

    private fun vibrator(): android.os.Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }

    private fun startVibration() {
        val vib = vibrator() ?: return
        runCatching {
            // Classic phone-ring cadence: pause 0, buzz 800ms, rest 900ms, repeat.
            val effect = android.os.VibrationEffect.createWaveform(longArrayOf(0, 800, 900), 0)
            vib.vibrate(effect)
        }
    }

    private fun stopVibration() {
        runCatching { vibrator()?.cancel() }
    }

    /** Play the "calling..." ringback tone — a soft telephony ring the CALLER
     *  hears in the earpiece while the peer's device rings (NOT the loud
     *  system ringtone). */
    fun playRingback() {
        stopRingback()
        runCatching {
            ringbackTone = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also {
                it.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }

    fun stopRingback() {
        ringbackTone?.let { runCatching { it.stopTone(); it.release() } }
        ringbackTone = null
    }

    // --- Short tones (end / busy) ---

    /** Play a short "end" tone (like hanging up a phone). */
    fun playEndTone() {
        stopShortTone()
        runCatching {
            shortTone = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).also {
                it.startTone(android.media.ToneGenerator.TONE_PROP_PROMPT, 200)
            }
        }
    }

    /** Play a short "busy" tone. */
    fun playBusyTone() {
        stopShortTone()
        runCatching {
            shortTone = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).also {
                it.startTone(android.media.ToneGenerator.TONE_SUP_BUSY, 400)
            }
        }
    }

    private fun stopShortTone() {
        shortTone?.let { runCatching { it.stopTone(); it.release() } }
        shortTone = null
    }

    // --- Haptics ---

    /** Short haptic when the user accepts a call. */
    fun hapticAccept() {
        runCatching {
            vibrator()?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Very short haptic when a call ends. */
    fun hapticEnd() {
        runCatching {
            vibrator()?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Tear down — abandon focus, restore normal mode, stop tones. */
    fun stop() {
        if (!started) return
        started = false
        stopRingtone()
        stopRingback()
        stopShortTone()
        stopSco()
        am.isSpeakerphoneOn = false
        am.isMicrophoneMute = false
        abandonFocus()
        am.mode = AudioManager.MODE_NORMAL
    }

    /** Refresh the BT availability (called by the manager on device changes).
     *  True only when a BT audio device is actually connected right now. */
    fun refreshBluetoothAvailable() {
        _bluetoothAvailable.value = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    // --- Helpers ---

    private fun detectInitialRoute(): AudioRoute {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (d in devices) {
            when (d.type) {
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return AudioRoute.BLUETOOTH
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> return AudioRoute.WIRED_HEADSET
            }
        }
        return AudioRoute.EARPIECE
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            // NOTE: setAcceptsDelayedFocusGain / pause-on-duck REQUIRE an
            // OnAudioFocusChangeListener — building without one throws
            // IllegalStateException. We don't react to focus changes during a
            // call (the call keeps going), so a no-op listener is correct.
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { /* call audio continues through transient losses */ }
                .build()
            am.requestAudioFocus(req)
            focusRequest = req
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
