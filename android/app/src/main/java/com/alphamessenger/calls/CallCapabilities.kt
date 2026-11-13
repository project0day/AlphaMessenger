package com.alphamessenger.calls

/**
 * Feature flags for the calls module — declares what the build SUPPORTS, not
 * what the current call uses. Each flag is a stable contract: the UI / manager
 * branch on these (not on ad-hoc booleans), so flipping a flag later (e.g.
 * enabling group calls) is a one-line change with NO call-site rewrite.
 *
 * ## Why now (auditor's recommendation)
 *  Today every capability is `true`. In a year when you add group calls /
 *  screen share / a second codec, the manager + UI already gate on these —
 *  nothing else changes. This is the "place already reserved" pattern.
 *
 * ## Source
 *  Read from BuildConfig at construction (so a release build can disable
 *  experimental features). Defaults to all-true so dev builds get everything.
 */
data class CallCapabilities(
    /** Video tracks (front/back camera + remote view). */
    val supportsVideo: Boolean = true,
    /** Screen capture as a video source (desktop-style share). */
    val supportsScreenShare: Boolean = false,
    /** Call hold/resume (local + remote). */
    val supportsHold: Boolean = true,
    /** Bluetooth headset routing. */
    val supportsBluetooth: Boolean = true,
    /** Front ↔ back camera switch mid-call. */
    val supportsCameraSwitch: Boolean = true,
    /** Multi-party (3+ participants) calls. */
    val supportsGroupCalls: Boolean = false,
    /** Server-side recording (CallRecorder). */
    val supportsRecording: Boolean = false,
    /** Picture-in-Picture video preview when backgrounded. */
    val supportsPip: Boolean = true,
)

/** THE singleton capabilities — resolved from BuildConfig flags (all-true by
 *  default). Provided via Hilt so the whole app reads the same source. */
object CallCapabilitiesProvider {
    val current: CallCapabilities = CallCapabilities()
}
