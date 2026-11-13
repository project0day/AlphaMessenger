# Calls Module — FINAL Architecture

> Status: **COMPLETE**. Architecture frozen per auditor's recommendation.
> Only bug fixes / optimizations / new features from here — NO new managers,
> coordinators, or layers.

## 📊 Final stats

- **29 files**, ~3000 lines total
- **CallManager**: 340 lines (thin orchestrator — delegates to 5 coordinators)
- **Architecture**: UI → ViewModel → CallManager → {Coordinators} → {Signaling, Engine, Audio, Repo} → GiftWrap → Relay

## 🏗 Final architecture diagram

```
UI (Compose)
  ↓ StateFlow<CallUiState> + StateFlow<CallError?>
CallViewModel
  ↓
CallManager (orchestrator, 340 строк)
  │  owns: currentSession: CallSession, uiState, lastError
  │  guards: ONE call at a time (busy guard)
  ↓ (delegates to)
┌─────────────────────────────────────────────────────────────┐
│ OutgoingCallCoordinator  IncomingCallCoordinator            │
│ ReconnectCoordinator     TimeoutCoordinator  AudioCoordinator│
└─────────────────────────────────────────────────────────────┘
  ↓                              ↓                    ↓
CallSignaling                WebRtcEngine          CallAudioRouter
  ↓                              │ (implements)         │
AlphaCoreRepository        CallRendererBridge      AudioProcessingConfig
  ↓                              ↑                    ↑
GiftWrap / NIP-59 / MLS         UI (AndroidView)    (config)
  ↓
Relay
```

**WebRtcEngine is fully separate** — knows NOTHING about Nostr/MLS/GiftWrap.
UI touches ONLY `CallRendererBridge` (never `WebRtcEngine` directly).

## 📦 Module map (29 files)

### Core types (`calls/`)
| File | Role |
|---|---|
| `CallModel.kt` | CallType, CallPhase, CallUiState, CallSignal, CallSignalCodec |
| `CallSession.kt` | Call identity + history (callId, peer, timestamps, metrics) |
| `CallCapabilities.kt` | Feature flags (supportsVideo/GroupCalls/Pip/...) |
| `CallPolicy.kt` | Runtime knobs (AutoAnswerBT, AllowVideoOverMobile, timeouts) |
| `DeviceCapabilities.kt` | Hardware probe (cameras, BT, mic, AV1/H265, HW enc/dec) |
| `CallError.kt` | Sealed class (PermissionDenied/PeerBusy/IceFailed/...) + userMessage() |
| `CallMetrics.kt` | Stats collector (ICE/Reconnect/PacketLoss/Bitrate/Codec/RTT/Jitter) |
| `CallCodecNegotiator.kt` | Codec preference (VP8/H264/AV1/Opus) |
| `CallRecorder.kt` | No-op interface (start/stop) — reserved for future recording |
| `CallRepository.kt` | Peer resolution (npub → name + avatar) |
| `CallLauncher.kt` | Hilt EntryPoint — entry point from ChatScreen |

### Audio (`calls/audio/`)
| File | Role |
|---|---|
| `CallAudioRouter.kt` | Focus + output routes (earpiece/speaker/BT/wired) + ringtone |
| `AudioProcessingConfig.kt` | NS/AEC/AGC/HPF DSP toggles |

### Engine (`calls/engine/`)
| File | Role |
|---|---|
| `WebRtcEngine.kt` | PeerConnection, A/V tracks, ICE restart, TURN, DSP constraints |
| `CallRendererBridge.kt` | UI abstraction (bindLocal/bindRemote/unbind) |

### Manager (`calls/manager/`)
| File | Role |
|---|---|
| `CallManager.kt` | Orchestrator (340 lines) — owns session + state + busy guard |
| `CallStateMachine.kt` | Legal phase transitions |
| `OutgoingCallCoordinator.kt` | Offer/ringback/cancel |
| `IncomingCallCoordinator.kt` | Ringtone/accept/reject |
| `ReconnectCoordinator.kt` | ICE watchdog + forced restart |
| `TimeoutCoordinator.kt` | 45s/60s/1.5s/duration timers |
| `AudioCoordinator.kt` | Wraps CallAudioRouter + AudioProcessingConfig |

### Media (`calls/media/`)
| File | Role |
|---|---|
| `MediaDeviceManager.kt` | Camera enumeration + audio route discovery |

### Service (`calls/service/`)
| File | Role |
|---|---|
| `CallForegroundService.kt` | FGS (microphone\|camera) — keeps WebRTC alive in background |
| `CallNotificationHelper.kt` | Full-screen intent + Answer/Reject/Hangup actions |

### Signaling (`calls/signaling/`)
| File | Role |
|---|---|
| `CallSignaling.kt` | MLS ChatState transport (send + incoming flow) |

### UI (`calls/ui/`)
| File | Role |
|---|---|
| `CallActivity.kt` | Full-screen, lockscreen, PiP, configChanges |
| `CallScreens.kt` | Incoming/Outgoing/Voice/Video/Terminal composables |
| `CallViewModel.kt` | Hilt VM — exposes state + delegates actions |

## ✅ Auditor's 10-point checklist (all PASS)

1. ✅ WebRTC knows nothing about GiftWrap/Nostr/MLS/core
2. ✅ UI calls ONLY CallManager (via ViewModel) — never WebRTC directly (uses CallRendererBridge)
3. ✅ StateMachine changed ONLY by CallManager
4. ✅ ONE call at a time (busy guard + callId match)
5. ✅ Relay reconnect: signaling is passive (flow resumes when core reconnects)
6. ✅ WebRTC survives GiftWrap blip (ICE independent of relay)
7. ✅ TURN supported (BuildConfig fields, engine reads them — manager stays ICE-agnostic)
8. ✅ CallManager knows nothing about Compose
9. ✅ WebRTC library swappable (only WebRtcEngine.kt + CallRendererBridge.kt)
10. ✅ No cyclic dependencies (each layer depends only on lower layers)

## 🚫 Frozen — do NOT add

Per auditor's recommendation, the architecture is now FROZEN. Do NOT add:
- ❌ CoordinatorCoordinator / CoordinatorFactory / CoordinatorManager
- ❌ Another manager layer
- ❌ Another abstraction layer

Only:
- ✅ Bug fixes
- ✅ Optimizations
- ✅ New features (within existing layers)

## 🔧 Installation

Add to `android/app/build.gradle.kts` → `dependencies { }`:
```kotlin
implementation("io.getstream:stream-webrtc-android:1.3.8")
```

That's the ONLY manual step. Everything else is wired.
