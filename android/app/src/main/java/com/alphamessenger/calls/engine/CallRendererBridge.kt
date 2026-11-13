package com.alphamessenger.calls.engine

import org.webrtc.SurfaceViewRenderer

/**
 * THE renderer bridge — the ONLY surface the UI is allowed to touch for video
 * rendering. Keeps the UI layer fully decoupled from [WebRtcEngine] internals:
 * the UI never imports `org.webrtc.*` (other than [SurfaceViewRenderer], which
 * is the unavoidable Compose AndroidView target).
 *
 * ## Why this exists
 *  The auditor's rule: "UI → CallManager only — never WebRTC directly." A
 *  Compose `AndroidView { SurfaceViewRenderer }` needs three things from the
 *  engine: the EGL context (to init), the local track (preview), and the
 *  remote track (fullscreen). Without this bridge, the UI would have to
 *  `@Inject WebRtcEngine` and read its flows — coupling the UI to the WebRTC
 *  library, which forbids swapping the engine.
 *
 *  With the bridge, the UI depends on [CallRendererBridge] (a tiny interface
 *  in the calls package). The engine implements it; the UI doesn't know or
 *  care what library is underneath.
 */
interface CallRendererBridge {
    /** The EGL context — needed to init each SurfaceViewRenderer. Null before
     *  the engine has started (no call). */
    val eglContext: Any?

    /** Attach a local-preview renderer. Safe to call repeatedly. */
    fun bindLocal(renderer: SurfaceViewRenderer)

    /** Attach a remote-video renderer. Safe to call repeatedly. */
    fun bindRemote(renderer: SurfaceViewRenderer)

    /** Detach a renderer (e.g. on PiP / dispose). */
    fun unbind(renderer: SurfaceViewRenderer)
}
