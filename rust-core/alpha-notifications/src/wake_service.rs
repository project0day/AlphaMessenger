//! Background wake service.
//!
//! Listens for push notifications and triggers sync catch-up
//! when one arrives.  Runs as a background Tokio task.

use crate::error::{NotificationError, Result};
use crate::ntfy_client::NtfyClient;
use crate::notification_handler::{NotificationAction, NotificationHandler};

use futures_util::StreamExt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};
use tokio::task::JoinHandle;

/// A reconnect delay of `secs` with ±20% jitter, so a fleet of devices
/// recovering from the same outage doesn't reconnect in lockstep (thundering
/// herd). Derives the jitter from the wall clock — no `rand` dependency. Floored
/// at 500ms so a base of 0 can't busy-loop.
fn with_jitter_secs(secs: u64) -> std::time::Duration {
    let base_ms = secs.saturating_mul(1000);
    let spread = (base_ms / 5).max(1); // ±20%
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos() as u64)
        .unwrap_or(0);
    let delta = (nanos % spread.saturating_mul(2)) as i64 - spread as i64;
    let ms = (base_ms as i64 + delta).max(500) as u64;
    std::time::Duration::from_millis(ms)
}

/// Background service that listens for push notifications and
/// triggers message fetch operations.
///
/// Lifecycle:
/// 1. [`WakeService::start`] — begins listening for push notifications.
/// 2. When a notification arrives, the service evaluates the
///    [`NotificationAction`] and signals that a sync catch-up
///    should be performed.
/// 3. [`WakeService::stop`] — shuts down the background listener.
pub struct WakeService {
    /// Notification handler.
    handler: NotificationHandler,
    /// ntfy client for subscribing.
    ntfy: NtfyClient,
    /// Whether the service is currently running.
    running: Arc<AtomicBool>,
    /// Handle to the background subscription task.
    task_handle: Arc<Mutex<Option<JoinHandle<()>>>>,
    /// Sender half of the wake channel. When a push notification arrives the
    /// background task fires this so the FFI layer can call
    /// `on_notification_received()` and trigger a catch-up on the Kotlin side.
    wake_tx: mpsc::Sender<()>,
    /// Signalled when the device's network changes: a reconnect waiting out
    /// its backoff goes at once, over the new network.
    network: Arc<tokio::sync::Notify>,
}

impl WakeService {
    /// Create a new wake service.
    ///
    /// `wake_tx` is the sender half of an mpsc channel created by the FFI
    /// layer.  The FFI layer holds the receiver and spawns a watcher task that
    /// calls `callback.on_notification_received()` whenever a token is sent,
    /// which in turn signals the Android `MessageSyncService` to run
    /// `forceReconnectAndCatchUp`.
    pub fn new(handler: NotificationHandler, ntfy: NtfyClient, wake_tx: mpsc::Sender<()>) -> Self {
        Self {
            handler,
            ntfy,
            running: Arc::new(AtomicBool::new(false)),
            task_handle: Arc::new(Mutex::new(None)),
            wake_tx,
            network: Arc::new(tokio::sync::Notify::new()),
        }
    }

    /// The device's network changed: reconnect now, with a fresh backoff.
    pub fn network_changed(&self) {
        self.network.notify_one();
    }

    /// Start listening for push notifications.
    ///
    /// Spawns a background Tokio task that subscribes to the ntfy
    /// topic and processes incoming notifications.  When a
    /// notification arrives, the handler's `on_notification` method
    /// is called and the result is logged.
    ///
    /// If the service is already running, returns
    /// [`NotificationError::AlreadyRunning`].
    pub async fn start(&self) -> Result<()> {
        if self.running.load(Ordering::SeqCst) {
            return Err(NotificationError::AlreadyRunning);
        }

        self.running.store(true, Ordering::SeqCst);
        tracing::info!("Wake service starting on topic: {}", self.ntfy.topic());

        let ntfy = self.ntfy.clone();
        let handler = NotificationHandler::new(self.handler.push_dispatcher().clone());
        let running = Arc::clone(&self.running);
        let wake_tx = self.wake_tx.clone();
        let network = Arc::clone(&self.network);

        let handle = tokio::spawn(async move {
            // Track the timestamp of the last wake we processed so that, on
            // (re)subscribe, ntfy replays any wakes published while we were
            // disconnected. On the very first connect we look back a fixed
            // window so an overnight airplane-mode gap is covered (ntfy.sh
            // caches ~12h). Wakes are debounced + idempotent downstream, so a
            // little replay overlap is harmless.
            const FIRST_LOOKBACK_SECS: u64 = 12 * 3600;
            let mut last_seen: Option<u64> = Some(
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0)
                    .saturating_sub(FIRST_LOOKBACK_SECS),
            );
            // STORM FIX: coalesce bursts of ntfy wakes. When the peer creates
            // many groups / sends many messages fast, ntfy replays a burst of
            // pushes (observed: ~20 in 300ms). Firing a wake token for each one
            // hammered `forceReconnectAndCatchUp` on the Android side and churned
            // the relay subscription into silence ("phone fell off"). We collapse
            // any burst into a SINGLE wake token per WAKE_COALESCE window — one
            // catch-up covers all the messages behind the burst anyway.
            const WAKE_COALESCE_SECS: u64 = 5;
            let mut last_wake = std::time::Instant::now()
                .checked_sub(std::time::Duration::from_secs(3600))
                .unwrap_or_else(std::time::Instant::now);
            // BATTERY: exponential backoff with jitter for RECONNECTS.
            // Previously a persistent ntfy/network failure retried at a fixed
            // 5s/2s forever → a reconnect storm (constant HTTP attempts) that
            // keeps the radio hot — the likely reason one device ran much
            // hotter than the other. Now: healthy subscribe resets to the base;
            // repeated failures escalate 2→4→8→16→32→64→128→256→300s (capped),
            // each with ±20% jitter so many devices don't sync up into a
            // thundering herd. A NORMAL stream end (ntfy closes idle sockets)
            // still reconnects fast (base delay), so delivery latency is
            // unaffected — only pathological failure loops are throttled.
            const BACKOFF_BASE_SECS: u64 = 2;
            const BACKOFF_MAX_SECS: u64 = 300;
            let mut backoff_secs = BACKOFF_BASE_SECS;

            // Reconnect loop: if the subscription drops, try to
            // re-establish it.
            while running.load(Ordering::SeqCst) {
                match ntfy.subscribe(last_seen).await {
                    Ok(mut stream) => {
                        tracing::info!("Subscribed to ntfy topic");
                        // Healthy subscribe → reset the failure backoff.
                        backoff_secs = BACKOFF_BASE_SECS;

                        while running.load(Ordering::SeqCst) {
                            let next = tokio::select! {
                                n = stream.next() => n,
                                // The old network's socket is dead: subscribe over the new one now.
                                _ = network.notified() => {
                                    tracing::info!("ntfy: network changed, resubscribing");
                                    break;
                                }
                            };
                            match next {
                                Some(notification) => {
                                    // Advance the replay cursor past this wake so
                                    // a later reconnect doesn't reprocess it.
                                    if notification.time > 0 {
                                        last_seen = Some(notification.time.saturating_add(1));
                                    }
                                    let action = handler.on_notification(&notification);
                                    match action {
                                        NotificationAction::FetchMessages => {
                                            // Coalesce bursts: only signal once per
                                            // WAKE_COALESCE_SECS. The catch-up that
                                            // one token triggers pulls EVERYTHING
                                            // published in the burst, so dropping the
                                            // intermediate tokens loses no messages —
                                            // it only prevents the reconnect storm.
                                            if last_wake.elapsed().as_secs() >= WAKE_COALESCE_SECS {
                                                last_wake = std::time::Instant::now();
                                                tracing::info!(
                                                    "Push notification received — signalling catch-up"
                                                );
                                                let _ = wake_tx.try_send(());
                                            } else {
                                                tracing::debug!(
                                                    "ntfy wake coalesced (burst within {}s)",
                                                    WAKE_COALESCE_SECS,
                                                );
                                            }
                                        }
                                    }
                                }
                                None => {
                                    tracing::warn!("ntfy stream ended; reconnecting...");
                                    break;
                                }
                            }
                        }
                        // Normal stream end after a healthy connection → quick
                        // reconnect (base delay + jitter), NOT escalating backoff.
                        if running.load(Ordering::SeqCst) {
                            tokio::select! {
                                _ = tokio::time::sleep(with_jitter_secs(BACKOFF_BASE_SECS)) => {}
                                _ = network.notified() => {}
                            }
                        }
                    }
                    Err(e) => {
                        tracing::error!(
                            "Failed to subscribe to ntfy: {} — backing off {}s",
                            e, backoff_secs,
                        );
                        if running.load(Ordering::SeqCst) {
                            tokio::select! {
                                _ = tokio::time::sleep(with_jitter_secs(backoff_secs)) => {
                                    // Escalate for the next failure (capped).
                                    backoff_secs = backoff_secs.saturating_mul(2).min(BACKOFF_MAX_SECS);
                                }
                                // A new network owes nothing to the old one's failures.
                                _ = network.notified() => backoff_secs = BACKOFF_BASE_SECS,
                            }
                        }
                    }
                }
            }

            tracing::info!("Wake service background task exiting");
        });

        let mut task = self.task_handle.lock().await;
        *task = Some(handle);

        Ok(())
    }

    /// Stop the wake service.
    ///
    /// Signals the background task to exit and waits for it to
    /// finish.
    pub async fn stop(&self) -> Result<()> {
        if !self.running.load(Ordering::SeqCst) {
            return Err(NotificationError::NotRunning);
        }

        self.running.store(false, Ordering::SeqCst);
        tracing::info!("Wake service stopping");

        let mut task = self.task_handle.lock().await;
        if let Some(handle) = task.take() {
            // Aborting is safe here — the task checks `running`
            // periodically and will exit gracefully.
            handle.abort();
        }

        Ok(())
    }

    /// Whether the service is currently running.
    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }
}
