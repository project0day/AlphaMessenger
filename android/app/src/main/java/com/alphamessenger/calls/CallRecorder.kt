package com.alphamessenger.calls

import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE call recorder — a reserved place for server-side or local call recording.
 *
 * ## Why now (auditor's recommendation)
 *  Even if recording isn't implemented yet, the [CallManager] calls
 *  [start]/[stop] at the right moments (call connected / ended). So when you
 *  DO implement recording (local file upload, server-side mix, etc.), it's a
 *  matter of filling in this class — the manager's lifecycle hooks are
 *  already wired. No retroactive threading.
 *
 * ## Current behaviour
 *  No-op — [start] / [stop] do nothing. Gated behind [CallCapabilities.supportsRecording]
 *  (false by default) so the manager only calls them when recording is enabled.
 *
 * ## Future implementation sketch
 *  - Local: MediaRecorder on the audio track + a MediaCodec muxer for video.
 *  - Server: stream the mixed track to a recording relay.
 *  Either way, this interface stays the same.
 */
@Singleton
class CallRecorder @Inject constructor() {

    /** Begin recording the current call. No-op if recording isn't supported
     *  or not enabled. */
    fun start(callId: String) {
        // TODO: implement when CallCapabilities.supportsRecording is flipped.
    }

    /** Stop recording + finalize the output. No-op if not recording. */
    fun stop() {
        // TODO: implement when CallCapabilities.supportsRecording is flipped.
    }

    /** Whether a recording is currently in progress. */
    val isRecording: Boolean = false
}
