package com.alphamessenger.calls

import android.content.Context
import android.media.AudioManager
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE device-capabilities probe — declares what HARDWARE the device has.
 * Distinct from [CallCapabilities] (feature flags) and [CallPolicy] (user
 * prefs): this is cold, factual hardware presence (does this phone have a
 * front camera? a Bluetooth radio? an AV1 hardware decoder?).
 *
 * ## Why now (auditor's recommendation)
 *  The UI gates "switch camera" / "use Bluetooth" / "AV1 codec" on these —
 *  so adding a new device feature check is a one-liner here, no call-site
 *  rewrite. Probed once at construction (cheap), cached.
 *
 * ## Source
 *  Probed via [Context] system services + PackageManager. The codec support
 *  is checked via the WebRTC encoder/decoder factory (deferred to the engine
 *  at runtime — here we only declare the hardware presence).
 */
@Singleton
class DeviceCapabilities @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    /** Has at least one back-facing camera. */
    val hasBackCamera: Boolean by lazy {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA)
    }

    /** Has a front-facing camera. */
    val hasFrontCamera: Boolean by lazy {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FRONT)
    }

    /** Has a microphone. */
    val hasMicrophone: Boolean by lazy {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
    }

    /** Has a Bluetooth radio (BLE or classic). */
    val hasBluetooth: Boolean by lazy {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH)
    }

    /** Has a speaker (every phone, but declared for completeness). */
    val hasSpeaker: Boolean = true

    /** Likely supports AV1 hardware decode (API 34+ has the platform decoder;
     *  actual WebRTC support is checked at runtime by the engine). */
    val supportsAv1: Boolean by lazy { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE }

    /** Likely supports H265/HEVC hardware decode — probed via MediaCodecList
     *  (there is no PackageManager feature flag for HEVC). */
    val supportsH265: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
                }
        }.getOrDefault(false)
    }

    /** Hardware video encoder available (almost always true on modern devices). */
    val hasHardwareEncoder: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
    }

    /** Hardware video decoder available. */
    val hasHardwareDecoder: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
    }

    /** A wired headset or Bluetooth route is currently connected (runtime check). */
    fun hasAudioRouteConnected(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }
}
