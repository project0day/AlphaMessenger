package com.alphamessenger.calls

/**
 * THE call policy — runtime-tunable behavioural knobs for the calls module.
 * Distinct from [CallCapabilities] (which declares what the BUILD supports):
 * CallPolicy is about USER/APP preferences (timeouts, when to allow video,
 * background camera, etc.). Lives separately so the engine + manager never
 * branch on ad-hoc settings.
 *
 * ## Why now (auditor's recommendation)
 *  Today these are sensible defaults. Later the user picks them in Settings
 *  ("allow video over mobile", "auto-answer on Bluetooth", etc.) — the manager
 *  already reads them, so a Settings screen is purely additive.
 *
 * ## NOT for
 *  - Hardware support → [DeviceCapabilities]
 *  - Feature flags → [CallCapabilities]
 *  - DSP toggles → [AudioProcessingConfig]
 */
data class CallPolicy(
    /** Auto-answer an incoming call when a Bluetooth headset is connected
     *  (car-kit / headset button behaviour). Off by default. */
    val autoAnswerBluetooth: Boolean = false,
    /** Allow video tracks over mobile data. Off → video calls downgrade to
     *  audio-only on mobile, full video only on Wi-Fi. */
    val allowVideoOverMobile: Boolean = true,
    /** Low-bandwidth mode: cap video resolution/bitrate, prefer audio-only. */
    val lowBandwidthMode: Boolean = false,
    /** Max ICE restart attempts before declaring FAILED. */
    val maxReconnectAttempts: Int = 3,
    /** Incoming call ring timeout (ms) before the callee is marked unreachable. */
    val incomingTimeoutMs: Long = 60_000L,
    /** Outgoing call ring timeout (ms) before the caller gives up. */
    val outgoingTimeoutMs: Long = 45_000L,
    /** Allow Picture-in-Picture when the user backgrounds a video call. */
    val allowPip: Boolean = true,
    /** Allow the local camera to keep streaming while the app is backgrounded
     *  (the FGS still hosts it). Off → camera pauses on background. */
    val allowBackgroundCamera: Boolean = true,
)
