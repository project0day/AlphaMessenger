# Calls Module — Installation

The calls module (`com.alphamessenger.calls`) is fully implemented. The ONLY
manual step required is adding the **WebRTC dependency** to your
`android/app/build.gradle.kts` (the lite backup doesn't ship it).

## 1. Add the WebRTC dependency

In `android/app/build.gradle.kts`, inside `dependencies { }`, add:

```kotlin
// WebRTC (official Android binary maintained by Stream, on Maven Central).
// Provides the `org.webrtc.*` classes used by WebRtcEngine.kt.
implementation("io.getstream:stream-webrtc-android:1.3.8")
```

That's the only change needed. The module uses `org.webrtc.*` (the standard
Google WebRTC API surface), which `stream-webrtc-android` ships verbatim.

## 2. Permissions & manifest

Already in place (the agent did this in step 1):
- RECORD_AUDIO, CAMERA, MODIFY_AUDIO_SETTINGS, BLUETOOTH_CONNECT
- FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_CAMERA
- USE_FULL_SCREEN_INTENT, ACCESS_NETWORK_STATE, WAKE_LOCK
- `CallActivity` (showWhenLocked, turnScreenOn, supportsPictureInPicture)
- `CallForegroundService` (foregroundServiceType=microphone|camera)

## 3. Files added/changed

### New (calls module)
- `calls/CallModel.kt` — types, phases, CallSignal + JSON codec (agent)
- `calls/CallRepository.kt` — peer resolution (agent)
- `calls/CallLauncher.kt` — Hilt EntryPoint + start call from anywhere
- `calls/signaling/CallSignaling.kt` — MLS ChatState transport
- `calls/manager/CallStateMachine.kt` — legal transitions
- `calls/manager/CallManager.kt` — orchestrator (timers, busy, reconnect)
- `calls/engine/WebRtcEngine.kt` — peer connection, tracks, ICE restart
- `calls/audio/CallAudioRouter.kt` — focus, earpiece/speaker/BT, ringtone
- `calls/service/CallNotificationHelper.kt` — FGS notification + actions
- `calls/service/CallForegroundService.kt` — keeps WebRTC alive in background
- `calls/ui/CallViewModel.kt` — exposes CallUiState + delegates actions
- `calls/ui/CallScreens.kt` — Material 3 UI (incoming/outgoing/voice/video)
- `calls/ui/CallActivity.kt` — full-screen, lockscreen, PiP

### Changed (existing files, minimal edits)
- `data/AlphaCoreRepository.kt` — added `callSignals: SharedFlow` (filters
  `call:`-prefixed ChatState messages; presence logic untouched)
- `AlphaMessengerApp.kt` — eager-init CallManager via EntryPoint
- `ui/chat/ChatScreen.kt` — `onPhone`/`onVideo` now call `CallLauncher.start`

## 4. Architecture

```
ChatScreen (onPhone/onVideo) → CallLauncher → CallManager
                                                  │
        ┌─────────────────────────────────────────┼────────────────────────┐
        ▼                   ▼                     ▼                        ▼
  CallSignaling       WebRtcEngine         CallAudioRouter        CallStateMachine
  (MLS ChatState)     (org.webrtc.*)       (focus/routes)         (legal transitions)
        │                   │                     │
        ▼                   ▼                     ▼
  AlphaCoreRepository  PeerConnection      AudioManager
  (callSignals flow)   + A/V tracks        + ringtone/ringback
```

## 5. State machine

```
IDLE → OUTGOING_CALLING → OUTGOING_RINGING → CONNECTING → CONNECTED
IDLE → INCOMING_RINGING → CONNECTING → CONNECTED
CONNECTED → RECONNECTING → CONNECTED (ICE restart)
any active → ENDING → ENDED
terminal → IDLE (1.5s linger for UI)
```

## 6. Signaling (rides existing MLS ChatState)

All call signals are encoded `call:<base64(JSON)>` and sent via the existing
`AlphaCoreRepository.sendChatStateDetached` — same GiftWrap/NIP-59 + MLS
encryption as typing indicators. **Zero changes to chat transport.**

Signals: Offer, Answer, Ice, Ringing, Reject, Busy, Cancel, End, Hold, Resume.
