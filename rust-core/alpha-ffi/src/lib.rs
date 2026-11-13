//! AlphaMessenger FFI bridge.
//!
//! UniFFI-based FFI layer that exposes the Rust core to Kotlin
//! (Android) and Swift (iOS).  Produces a `.udl` file and Rust
//! scaffolding for generating bindings.

pub mod callbacks;
pub mod core;
pub(crate) mod directory;
pub(crate) mod personas;
pub(crate) mod invite_link;
pub mod event_dispatcher;
pub mod events;
pub mod presence_engine;
pub mod lock_track;
pub(crate) mod rooms;
pub mod reliability;

// Re-export all sub-crates so they're accessible from the FFI layer.
pub use alpha_database;
pub use alpha_identity;
pub use alpha_media;
pub use alpha_messages;
pub use alpha_mls;
pub use alpha_notifications;
pub use alpha_sync;
pub use alpha_transport;

// Re-export FFI-safe types.
pub use callbacks::{AlphaMessengerCallback, CallbackDispatcher};
// BLOCK K — CoreEvent must be in crate-root scope for the UDL scaffolding.
pub use events::CoreEvent;
pub use core::{
    AlphaMessengerCore, ChatInfo, ExpiryPolicy, IdentityInfo, MessageInfo,
    MessengerError, MessageType, PeerProfile, ProfileInfo, RelayInfo,
    ReplicationObjectSpec, ReplicationOutcome, SearchHit,
};
// C2 Stage 3: top-level (namespace) function — derive the MasterDataKey from a
// password + public KDF JSON, with no DB / core instance (pre-open path).
pub use core::derive_master_key_from_public;
pub use core::{lock_report, mnemonic_generate, mnemonic_to_nsec, mnemonic_wordlist};

// One-shot tracing init: on Android we install `tracing-android` so every
// `tracing::info!` / `tracing::warn!` from the entire Rust stack lands in
// `adb logcat` under the `alpha_ffi` tag. Without this we are completely
// blind to publish/fetch/decrypt failures inside the FFI layer.
#[cfg(target_os = "android")]
fn install_tracing() {
    use std::sync::Once;
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let android_layer = tracing_android::layer("alpha_ffi")
            .expect("init tracing-android logcat sink");
        // Noise cleanup: alpha_=info (kills the auto-generated per-FFI-call
        // `log: <fn>` debug spam from alphamessenger.uniffi.rs + our own debug),
        // nostr_sdk/openmls=warn. Keeps useful INFO (RELAY_EVENT_IN, catch-up,
        // publish, STALE). Override at runtime via RUST_LOG if deeper debug needed.
        let filter = tracing_subscriber::EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new(
                if cfg!(debug_assertions) {
                    "info,alpha_=info,nostr_sdk=warn,openmls=warn,DELIVERY_TRACE=warn,RELAY_TRACE=warn"
                } else {
                    "warn,ACCOUNT=info"
                }
            ));
        let _ = tracing_subscriber::registry()
            .with(filter)
            .with(android_layer)
            .try_init();
    });
}

#[cfg(not(target_os = "android"))]
fn install_tracing() {}

// H1 (RoadmapSecurityAudit): install a panic hook so that any panic anywhere in
// the Rust stack — including inside spawned tokio tasks that are NOT covered by
// UniFFI's per-call `catch_unwind` boundary — is logged with its location and
// message to `tracing::error!` (and thus to `adb logcat` under `alpha_ffi`)
// BEFORE unwinding. Without this, a panic deep inside a background task was
// effectively invisible: we lost the foreground service / MLS state with no
// trace. This hook does NOT abort and does NOT change unwinding behaviour — it
// is purely additive observability. UniFFI still converts panics on the FFI
// call thread into an `InternalError` returned to Kotlin (default profile keeps
// `panic = "unwind"`), so the JVM is not killed for those.
fn install_panic_hook() {
    use std::sync::Once;
    static HOOK: Once = Once::new();
    HOOK.call_once(|| {
        let default_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let location = info
                .location()
                .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
                .unwrap_or_else(|| "<unknown>".to_string());
            let payload = info
                .payload()
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| info.payload().downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "<non-string panic payload>".to_string());
            tracing::error!(
                target: "alpha_ffi",
                "RUST PANIC at {location}: {payload}"
            );
            // Preserve any previously-installed behaviour (e.g. default backtrace).
            default_hook(info);
        }));
    });
}

#[ctor::ctor]
fn _ffi_init() {
    install_tracing();
    install_panic_hook();
}

// Include the UniFFI scaffolding generated from the UDL
uniffi::include_scaffolding!("alphamessenger");
