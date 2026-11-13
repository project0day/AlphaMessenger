//! Unified Event Dispatcher (BLOCK K) — the single exit point for every
//! high-frequency Rust-Core event.
//!
//! ```text
//! core code ── emit(CoreEvent) ──► UnifiedEventDispatcher
//!                                      │ policy = event.dispatch_policy()
//!                                      ├─ Batchable → coalesce map (dedup by key)
//!                                      └─ (Immediate/terminal) → flush now
//!                                      │
//!                    flush: 25ms OR 64 events OR terminal
//!                                      ▼
//!                       callback.on_events_batch(Vec<CoreEvent>)   (ONE JNI call)
//! ```
//!
//! The dispatcher knows ONLY [`CoreEvent`] and the [`CallbackDispatcher`]
//! abstraction — nothing about Kotlin / JNI / SharedFlow / Compose. Coalesce,
//! priority, throttle and metrics all live here (never in Kotlin).

use crate::callbacks::CallbackDispatcher;
use crate::events::{CoreEvent, DispatchPolicy, EventKey, EventMetrics, EventMetricsSnapshot, FlushReason};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Timer flush cadence. A batch is delivered at most this often (≈40/s ceiling).
const FLUSH_INTERVAL: Duration = Duration::from_millis(25);
/// Size cap: flush as soon as this many distinct events are pending, so a burst
/// (e.g. 50 concurrent uploads) never waits on the timer.
const MAX_BATCH: usize = 64;

/// Coalesced pending events + the instant the current (non-empty) batch began
/// accumulating (for latency metrics).
#[derive(Default)]
struct Pending {
    map: HashMap<EventKey, CoreEvent>,
    oldest: Option<Instant>,
}

pub struct UnifiedEventDispatcher {
    pending: Mutex<Pending>,
    callback: CallbackDispatcher,
    metrics: EventMetrics,
    stop: AtomicBool,
}

impl UnifiedEventDispatcher {
    /// Create the dispatcher and spawn its background flush thread. Returns an
    /// `Arc` the core holds; the flush thread holds its own clone (so it lives
    /// for the process lifetime; [`shutdown`](Self::shutdown) stops it in tests).
    pub fn start(callback: CallbackDispatcher) -> Arc<Self> {
        let this = Arc::new(Self {
            pending: Mutex::new(Pending::default()),
            callback,
            metrics: EventMetrics::default(),
            stop: AtomicBool::new(false),
        });
        let worker = Arc::clone(&this);
        std::thread::Builder::new()
            .name("alpha-event-flush".into())
            .spawn(move || {
                while !worker.stop.load(Ordering::Relaxed) {
                    std::thread::sleep(FLUSH_INTERVAL);
                    worker.flush(FlushReason::Timer);
                }
            })
            .expect("spawn alpha-event-flush thread");
        this
    }

    /// THE single entry point for core events (principle: 100% through here).
    pub fn emit(&self, event: CoreEvent) {
        self.metrics.on_received();

        // Immediate policy or a terminal transfer event must not wait for the
        // timer. The event is still coalesced in first, so the flush it triggers
        // carries the final state (e.g. 100% / failed).
        let flush_now =
            event.dispatch_policy() == DispatchPolicy::Immediate || event.forces_flush();

        let over_capacity = {
            let mut p = self.pending.lock().expect("event pending lock poisoned");
            if p.map.is_empty() {
                p.oldest = Some(Instant::now());
            }
            if p.map.insert(event.coalesce_key(), event).is_some() {
                // An older pending event for this key was dropped (deduplicated).
                self.metrics.on_coalesced();
            }
            p.map.len() >= MAX_BATCH
        };

        if flush_now {
            self.flush(FlushReason::Terminal);
        } else if over_capacity {
            self.flush(FlushReason::Capacity);
        }
    }

    /// Drain the pending map and deliver it as ONE batch. No-op when empty, so an
    /// idle app produces zero callbacks (preserves idle-quiescence). The pending
    /// lock is released BEFORE the JNI call — never held across the boundary.
    pub fn flush(&self, reason: FlushReason) {
        let (events, latency_ns) = {
            let mut p = self.pending.lock().expect("event pending lock poisoned");
            if p.map.is_empty() {
                return;
            }
            let latency_ns = p
                .oldest
                .take()
                .map(|t| t.elapsed().as_nanos() as u64)
                .unwrap_or(0);
            let events: Vec<CoreEvent> = p.map.drain().map(|(_, v)| v).collect();
            (events, latency_ns)
        };

        self.metrics.on_flush(events.len() as u64, reason, latency_ns);
        self.callback.on_events_batch(events);
    }

    /// Snapshot the internal metrics (for a future developer/debug screen).
    pub fn metrics(&self) -> EventMetricsSnapshot {
        self.metrics.snapshot()
    }

    /// Stop the flush thread and deliver anything still pending. Idempotent.
    pub fn shutdown(&self) {
        self.stop.store(true, Ordering::Relaxed);
        self.flush(FlushReason::Manual);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::callbacks::AlphaMessengerCallback;
    use std::sync::atomic::AtomicU64;

    #[derive(Default)]
    struct BatchCounter {
        batches: AtomicU64,
        events: AtomicU64,
        last_batch_len: AtomicU64,
    }
    impl AlphaMessengerCallback for BatchCounter {
        fn on_message_received(&self, _c: String, _m: String) {}
        fn on_delivery_update(&self, _m: String, _s: String) {}
        fn on_sync_complete(&self, _n: u64) {}
        fn on_chat_created(&self, _c: String, _t: String) {}
        fn on_devices_event(&self, _k: String) {}
        fn on_member_added(&self, _c: String, _m: String) {}
        fn on_member_removed(&self, _c: String, _m: String) {}
        fn on_notification_received(&self) {}
        fn on_peer_profile_updated(&self, _s: String) {}
        fn on_chat_metadata_updated(&self, _c: String) {}
        fn on_chat_state(&self, _c: String, _s: String, _st: String, _t: String, _a: u64) {}
        fn on_media_progress(&self, _o: String, _d: u64, _t: u64) {}
        fn on_join_requested(&self, _c: String, _r: String, _t: String) {}
        fn on_member_leave_requested(&self, _c: String, _l: String) {}
        fn on_relay_status_change(&self, _r: String, _s: String) {}
        fn on_sync_state(&self, _c: u32, _r: u32, _u: bool) {}
        fn on_events_batch(&self, events: Vec<CoreEvent>) {
            self.batches.fetch_add(1, Ordering::SeqCst);
            self.events.fetch_add(events.len() as u64, Ordering::SeqCst);
            self.last_batch_len.store(events.len() as u64, Ordering::SeqCst);
        }
    }

    fn setup() -> (Arc<UnifiedEventDispatcher>, *const BatchCounter) {
        let disp = CallbackDispatcher::new();
        let cb = Box::new(BatchCounter::default());
        let ptr = cb.as_ref() as *const BatchCounter;
        disp.set_callback(cb);
        // Build the dispatcher WITHOUT the timer thread firing during the test by
        // stopping it immediately; we drive flush() manually.
        let ued = UnifiedEventDispatcher::start(disp);
        ued.stop.store(true, Ordering::Relaxed);
        (ued, ptr)
    }

    #[test]
    fn progress_is_coalesced_per_op() {
        let (ued, ptr) = setup();
        for pct in 1..=5u64 {
            ued.emit(CoreEvent::MediaProgress { op_id: "v1".into(), downloaded: pct, total: 5, terminal: false });
        }
        // 5 emits, same op → 1 pending; nothing delivered until flush.
        assert_eq!(unsafe { &*ptr }.batches.load(Ordering::SeqCst), 0);
        ued.flush(FlushReason::Manual);
        assert_eq!(unsafe { &*ptr }.batches.load(Ordering::SeqCst), 1);
        assert_eq!(unsafe { &*ptr }.last_batch_len.load(Ordering::SeqCst), 1, "coalesced to one");
        let m = ued.metrics();
        assert_eq!(m.received, 5);
        assert_eq!(m.coalesced, 4);
    }

    #[test]
    fn terminal_forces_immediate_flush_with_final_value() {
        let (ued, ptr) = setup();
        ued.emit(CoreEvent::MediaProgress { op_id: "f1".into(), downloaded: 30, total: 100, terminal: false });
        assert_eq!(unsafe { &*ptr }.batches.load(Ordering::SeqCst), 0, "non-terminal waits");
        ued.emit(CoreEvent::MediaProgress { op_id: "f1".into(), downloaded: 100, total: 100, terminal: true });
        assert_eq!(unsafe { &*ptr }.batches.load(Ordering::SeqCst), 1, "terminal flushed now");
        assert_eq!(unsafe { &*ptr }.last_batch_len.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn distinct_ops_batch_together() {
        let (ued, ptr) = setup();
        ued.emit(CoreEvent::MediaProgress { op_id: "a".into(), downloaded: 1, total: 9, terminal: false });
        ued.emit(CoreEvent::MediaProgress { op_id: "b".into(), downloaded: 2, total: 9, terminal: false });
        ued.emit(CoreEvent::ChatState { chat_id: "c".into(), sender_id: "s".into(), state: "typing".into() });
        ued.flush(FlushReason::Manual);
        assert_eq!(unsafe { &*ptr }.last_batch_len.load(Ordering::SeqCst), 3, "3 distinct keys in one batch");
    }

    #[test]
    fn idle_flush_is_noop() {
        let (ued, ptr) = setup();
        ued.flush(FlushReason::Timer);
        assert_eq!(unsafe { &*ptr }.batches.load(Ordering::SeqCst), 0, "empty flush = no callback");
    }
}
