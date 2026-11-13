//! AlphaMessenger notification system.
//!
//! Privacy-first push notifications using ntfy (primary) and
//! UnifiedPush (fallback).  Push notifications contain ZERO
//! plaintext — only a "wake" signal that tells the app to
//! connect and fetch actual messages.

pub mod error;
pub mod ntfy_client;
pub mod notification_handler;
pub mod push_dispatcher;
pub mod unified_push;
pub mod wake_service;

// Re-export key types for convenience.

// --- Network proxy (Tor / Orbot) ---

static HTTP_PROXY: std::sync::RwLock<Option<std::net::SocketAddr>> = std::sync::RwLock::new(None);

/// Route HTTP clients created after this call through a SOCKS5 proxy
/// (DNS resolved by the proxy). `None` = direct.
pub fn set_http_proxy(addr: Option<std::net::SocketAddr>) {
    if let Ok(mut p) = HTTP_PROXY.write() {
        *p = addr;
    }
}

/// Build `builder`; if it fails, a plain client — still behind the proxy.
pub fn proxied_client(builder: reqwest::ClientBuilder) -> reqwest::Client {
    builder.build().unwrap_or_else(|e| {
        tracing::warn!("http client: {e}; using a plain one behind the same proxy");
        with_http_proxy(reqwest::Client::builder()).build().expect("http client")
    })
}

/// Apply the configured proxy, if any, to a client builder.
pub fn with_http_proxy(builder: reqwest::ClientBuilder) -> reqwest::ClientBuilder {
    match HTTP_PROXY.read().ok().and_then(|p| *p) {
        Some(addr) => match reqwest::Proxy::all(format!("socks5h://{addr}")) {
            Ok(proxy) => builder.proxy(proxy),
            // Never direct when a proxy is set: a dead local port refuses instead.
            Err(e) => {
                tracing::error!("proxy {addr} not applicable: {e}");
                builder.proxy(reqwest::Proxy::all("socks5h://127.0.0.1:9").expect("static proxy url"))
            }
        },
        None => builder,
    }
}

pub use error::NotificationError;
pub use ntfy_client::{NtfyClient, NtfyMessage, NtfyNotification};
pub use notification_handler::{NotificationAction, NotificationHandler};
pub use push_dispatcher::PushDispatcher;
pub use unified_push::UnifiedPushClient;
pub use wake_service::WakeService;
