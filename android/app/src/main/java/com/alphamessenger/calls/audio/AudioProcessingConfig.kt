package com.alphamessenger.calls.audio

/**
 * THE audio processing configuration — Noise Suppression, Echo Cancellation,
 * Automatic Gain Control. Kept SEPARATE from [CallAudioRouter] (which handles
 * output routing) per the auditor's recommendation: these are DSP features on
 * the audio SOURCE, not routing concerns.
 *
 * ## Why now (auditor's recommendation)
 *  Today all three are forced ON (sensible defaults for voice calls). Later
 *  you may want a "high-quality music mode" that disables NS+AGC, or a
 *  per-device override. With this as a separate config, the engine reads it
 *  at audio-source creation — no routing logic touched.
 *
 * ## Source
 *  Read from BuildConfig at construction. The engine applies these when it
 *  builds the AudioSource constraints.
 */
data class AudioProcessingConfig(
    /** Noise Suppression — removes steady background noise (fans, keyboards). */
    val noiseSuppression: Boolean = true,
    /** Echo Cancellation — removes the speaker's audio leaking back into the mic. */
    val echoCancellation: Boolean = true,
    /** Automatic Gain Control — normalizes mic levels for quiet/loud talkers. */
    val automaticGainControl: Boolean = true,
    /** High-pass filter — removes DC offset / low-frequency rumble. */
    val highPassFilter: Boolean = true,
)

/** THE singleton audio processing config — all DSP on by default. */
object AudioProcessingConfigProvider {
    val current: AudioProcessingConfig = AudioProcessingConfig()
}
