package com.alphamessenger.calls.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.alphamessenger.calls.AudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE media device manager — knows about the device's PHYSICAL media hardware
 * (cameras, microphones, speakers, Bluetooth). Distinct from [CallAudioRouter]
 * (which ROUTES audio): this is the device DISCOVERY + enumeration layer.
 *
 * ## Why now (auditor's recommendation)
 *  Today the audio router + the WebRTC engine each probe devices ad-hoc. As
 *  the module grows (camera picker UI, multi-mic selection, BT device list),
 *  a single source of truth for "what hardware is available right now" keeps
 *  the UI + engine + router in sync.
 *
 * ## Responsibilities
 *  - Enumerate cameras (front/back, with facing + capabilities).
 *  - Detect connected audio output devices (earpiece/speaker/wired/BT).
 *  - Expose a [StateFlow] so the UI reacts to device plug/unplug live.
 *
 * ## NOT here
 *  - Switching the active route → [CallAudioRouter].
 *  - Capturing from the camera → [WebRtcEngine].
 *  This is discovery only.
 */
@Singleton
class MediaDeviceManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val am: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The currently connected audio output routes (live-updating). */
    private val _availableRoutes = MutableStateFlow(detectRoutes())
    val availableRoutes: StateFlow<Set<AudioRoute>> = _availableRoutes.asStateFlow()

    /** Refresh the connected-routes snapshot (call on device-change events). */
    fun refresh() {
        _availableRoutes.value = detectRoutes()
    }

    /** True if a Bluetooth audio device is connected RIGHT NOW. */
    fun isBluetoothConnected(): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

    /** True if a wired headset is connected RIGHT NOW. */
    fun isWiredHeadsetConnected(): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }

    /** Camera device ids (Camera2 enumeration). Empty if no camera.
     *  The engine uses this to pick the front/back capturer. */
    fun enumerateCameras(): List<CameraInfo> {
        val enumerator = org.webrtc.Camera2Enumerator(context)
        return enumerator.deviceNames.mapIndexed { i, name ->
            CameraInfo(
                id = name,
                facing = when {
                    enumerator.isFrontFacing(name) -> CameraFacing.FRONT
                    enumerator.isBackFacing(name) -> CameraFacing.BACK
                    else -> CameraFacing.EXTERNAL
                },
                index = i,
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun detectRoutes(): Set<AudioRoute> {
        val routes = mutableSetOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (d in devices) {
            when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> routes += AudioRoute.WIRED_HEADSET
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> routes += AudioRoute.BLUETOOTH
            }
        }
        return routes
    }
}

/** One camera device. */
data class CameraInfo(
    val id: String,
    val facing: CameraFacing,
    val index: Int,
)

enum class CameraFacing { FRONT, BACK, EXTERNAL }
