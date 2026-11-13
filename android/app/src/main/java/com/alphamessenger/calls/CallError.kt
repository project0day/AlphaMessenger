package com.alphamessenger.calls

import androidx.annotation.StringRes
import com.alphamessenger.R

/**
 * THE call error type — a sealed class so the UI can pattern-match on the
 * exact failure and show a localized, user-friendly message. No magic strings.
 *
 * ## Why now (auditor's recommendation)
 *  Later the UI will want different toasts for "permission denied" vs "network
 *  lost" vs "peer busy". With a sealed class, the manager emits a [CallError]
 *  and the UI `when`'s on it — exhaustive, type-safe, no string typos.
 *
 * ## Categories
 *  - Permission (mic/camera denied)
 *  - Peer state (busy, rejected, timed out)
 *  - Network (relay down, ICE failed)
 *  - Device (camera/mic unavailable)
 *  - Negotiation (codec mismatch)
 */
sealed class CallError {
    /** The user denied microphone/camera permission. */
    data class PermissionDenied(val permission: String) : CallError()
    /** The peer is already on another call. */
    object PeerBusy : CallError()
    /** The peer declined the call. */
    object PeerRejected : CallError()
    /** The peer device didn't answer in time. */
    object Timeout : CallError()
    /** The local network (relay/Internet) was lost. */
    object NetworkLost : CallError()
    /** The relay was unreachable (GiftWrap delivery failed). */
    object RelayUnavailable : CallError()
    /** ICE failed to establish a connection (TURN/STUN exhaustion). */
    object IceFailed : CallError()
    /** The camera couldn't be opened (busy / hardware fault). */
    object CameraUnavailable : CallError()
    /** The microphone couldn't be opened (busy / hardware fault). */
    object MicrophoneUnavailable : CallError()
    /** No common codec could be agreed on with the peer. */
    object CodecNegotiationFailed : CallError()
    /** Catch-all for unexpected failures. */
    data class Unknown(val reason: String) : CallError()
}

/** Map a [CallError] to a short, user-facing label resource (the UI's job
 *  to resolve, but a sensible default lives next to the type). For
 *  [CallError.Unknown] (and null) the resource is an "Error: %1$s" template —
 *  callers must format it with the reason: getString(res, reason). */
@StringRes
fun CallError?.labelRes(): Int = when (this) {
    is CallError.PermissionDenied -> R.string.call_error_permission_denied
    CallError.PeerBusy -> R.string.call_error_peer_busy
    CallError.PeerRejected -> R.string.call_error_peer_rejected
    CallError.Timeout -> R.string.call_error_timeout
    CallError.NetworkLost -> R.string.call_error_network_lost
    CallError.RelayUnavailable -> R.string.call_error_relay_unavailable
    CallError.IceFailed -> R.string.call_error_ice_failed
    CallError.CameraUnavailable -> R.string.call_error_camera_unavailable
    CallError.MicrophoneUnavailable -> R.string.call_error_microphone_unavailable
    CallError.CodecNegotiationFailed -> R.string.call_error_codec_failed
    is CallError.Unknown -> R.string.error_with_reason
    null -> R.string.error_with_reason
}
