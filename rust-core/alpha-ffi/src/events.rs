//! Unified Event Dispatcher — event model (BLOCK K).
//!
//! `CoreEvent` is the ONE type every high-frequency Rust-Core signal is
//! expressed as before it crosses the FFI boundary. Every property the
//! dispatcher needs to route an event lives ON the event itself
//! ([`CoreEvent::dispatch_policy`], [`CoreEvent::priority`],
//! [`CoreEvent::coalesce_key`], [`CoreEvent::forces_flush`]), so the dispatcher
//! never switches on a concrete variant — new event types plug in without
//! touching it.
//!
//! Design principles (architect review):
//!  1. The Unified Event Dispatcher is a PERMANENT Rust-Core subsystem, not an
//!     upload-progress optimization.
//!  2. Every new Rust event decides its own `DispatchPolicy` before it reaches
//!     Kotlin.
//!  3. Kotlin NEVER deduplicates. coalesce / priority / batch / throttle /
//!     dispatch live only here in Rust.
//!
//! Only [`CoreEvent`] is FFI-exposed (via `on_events_batch(sequence<CoreEvent>)`
//! in the UDL). Everything else in this module is internal Rust.

use std::sync::atomic::{AtomicU64, Ordering};

/// A batchable core event delivered to the app in a coalesced batch via
/// `AlphaMessengerCallback::on_events_batch`.
///
/// Immediate DOMAIN facts (message_received, chat_created, member_*, …) keep
/// their own typed callbacks for now; when they migrate here in K2 they simply
/// return [`DispatchPolicy::Immediate`] — the dispatcher needs no change.
///
/// Struct-variant syntax matches the UDL `[Enum] interface CoreEvent { … }`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CoreEvent {
    /// Media transfer progress (upload or download). `terminal` marks the final
    /// event of a transfer (completed OR failed) — it forces an immediate flush
    /// so the UI never sticks below 100%.
    MediaProgress {
        op_id: String,
        downloaded: u64,
        total: u64,
        terminal: bool,
    },
    /// A peer started/stopped typing or recording ("typing"/"recording"/"paused").
    ChatState {
        chat_id: String,
        sender_id: String,
        state: String,
    },
    /// A message's delivery status changed ("sent"/"delivered"/"read"/…).
    DeliveryUpdate {
        message_id: String,
        status: String,
    },
}

/// How the dispatcher delivers an event. A property of the event, so the
/// dispatcher stays type-agnostic (principle #2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DispatchPolicy {
    /// Deliver right now, never coalesced (domain facts — K2).
    Immediate,
    /// Insert into the coalesce map; delivered on the next flush.
    Batchable,
}

/// Delivery priority tier. Foundation for future routing — declared now so the
/// model never changes when it starts being used.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Priority {
    Low,
    Normal,
    High,
    Critical,
}

/// The dedup key for the pending map: a later event with the SAME key REPLACES
/// the earlier one (progress 1→2→3 collapses to 3). An enum (not a formatted
/// `String`) → no per-event key allocation beyond the ids it already owns.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum EventKey {
    MediaProgress(String),        // op_id
    ChatState(String, String),    // (chat_id, sender_id)
    DeliveryUpdate(String),       // message_id
}

/// Why a flush fired — an enum, not strings, so metrics are cheap and exact.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FlushReason {
    Timer,
    Capacity,
    Terminal,
    Manual,
}

impl CoreEvent {
    /// Batch-vs-immediate. All K1 events are batchable; K2 domain facts will
    /// return `Immediate`.
    pub fn dispatch_policy(&self) -> DispatchPolicy {
        match self {
            CoreEvent::MediaProgress { .. }
            | CoreEvent::ChatState { .. }
            | CoreEvent::DeliveryUpdate { .. } => DispatchPolicy::Batchable,
        }
    }

    /// A batchable event that must not wait for the timer — flush the whole
    /// pending batch NOW (the terminal progress is already coalesced in, so the
    /// flush carries the final 100% / failure).
    pub fn forces_flush(&self) -> bool {
        matches!(self, CoreEvent::MediaProgress { terminal: true, .. })
    }

    /// Priority tier (foundation; not yet used for routing).
    pub fn priority(&self) -> Priority {
        match self {
            CoreEvent::MediaProgress { terminal: true, .. } => Priority::High,
            _ => Priority::Normal,
        }
    }

    /// The coalesce key (see [`EventKey`]).
    pub fn coalesce_key(&self) -> EventKey {
        match self {
            CoreEvent::MediaProgress { op_id, .. } => EventKey::MediaProgress(op_id.clone()),
            CoreEvent::ChatState { chat_id, sender_id, .. } => {
                EventKey::ChatState(chat_id.clone(), sender_id.clone())
            }
            CoreEvent::DeliveryUpdate { message_id, .. } => {
                EventKey::DeliveryUpdate(message_id.clone())
            }
        }
    }
}

/// Rust-only metrics for the dispatcher. Cheap atomics; snapshot via
/// [`EventMetrics::snapshot`] for a future developer/debug screen. Never on the
/// UI hot path.
#[derive(Debug, Default)]
pub struct EventMetrics {
    /// Events handed to `emit()`.
    pub received: AtomicU64,
    /// Events that overwrote an existing pending event (deduplicated away).
    pub coalesced: AtomicU64,
    /// Individual events actually delivered inside a batch.
    pub flushed: AtomicU64,
    /// Number of `on_events_batch` calls made.
    pub batches: AtomicU64,
    /// Largest single batch seen.
    pub peak_batch: AtomicU64,
    /// Flush-reason tallies.
    pub flush_timer: AtomicU64,
    pub flush_capacity: AtomicU64,
    pub flush_terminal: AtomicU64,
    pub flush_manual: AtomicU64,
    /// Batch latency = ns from the OLDEST pending event to its flush.
    pub latency_ns_sum: AtomicU64,
    pub latency_ns_max: AtomicU64,
}

/// A plain (non-atomic) snapshot of [`EventMetrics`] for logging / a debug API.
#[derive(Debug, Clone, Default)]
pub struct EventMetricsSnapshot {
    pub received: u64,
    pub coalesced: u64,
    pub flushed: u64,
    pub batches: u64,
    pub peak_batch: u64,
    pub avg_batch: f64,
    pub flush_timer: u64,
    pub flush_capacity: u64,
    pub flush_terminal: u64,
    pub flush_manual: u64,
    pub latency_ms_avg: f64,
    pub latency_ms_max: f64,
}

impl EventMetrics {
    pub fn on_received(&self) {
        self.received.fetch_add(1, Ordering::Relaxed);
    }
    pub fn on_coalesced(&self) {
        self.coalesced.fetch_add(1, Ordering::Relaxed);
    }
    /// Record a flush of `n` events for `reason`, with `latency_ns` = age of the
    /// oldest event in the batch.
    pub fn on_flush(&self, n: u64, reason: FlushReason, latency_ns: u64) {
        self.flushed.fetch_add(n, Ordering::Relaxed);
        self.batches.fetch_add(1, Ordering::Relaxed);
        self.peak_batch.fetch_max(n, Ordering::Relaxed);
        match reason {
            FlushReason::Timer => &self.flush_timer,
            FlushReason::Capacity => &self.flush_capacity,
            FlushReason::Terminal => &self.flush_terminal,
            FlushReason::Manual => &self.flush_manual,
        }
        .fetch_add(1, Ordering::Relaxed);
        self.latency_ns_sum.fetch_add(latency_ns, Ordering::Relaxed);
        self.latency_ns_max.fetch_max(latency_ns, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> EventMetricsSnapshot {
        let batches = self.batches.load(Ordering::Relaxed);
        let flushed = self.flushed.load(Ordering::Relaxed);
        let lat_sum = self.latency_ns_sum.load(Ordering::Relaxed);
        EventMetricsSnapshot {
            received: self.received.load(Ordering::Relaxed),
            coalesced: self.coalesced.load(Ordering::Relaxed),
            flushed,
            batches,
            peak_batch: self.peak_batch.load(Ordering::Relaxed),
            avg_batch: if batches > 0 { flushed as f64 / batches as f64 } else { 0.0 },
            flush_timer: self.flush_timer.load(Ordering::Relaxed),
            flush_capacity: self.flush_capacity.load(Ordering::Relaxed),
            flush_terminal: self.flush_terminal.load(Ordering::Relaxed),
            flush_manual: self.flush_manual.load(Ordering::Relaxed),
            latency_ms_avg: if batches > 0 {
                (lat_sum as f64 / batches as f64) / 1_000_000.0
            } else {
                0.0
            },
            latency_ms_max: self.latency_ns_max.load(Ordering::Relaxed) as f64 / 1_000_000.0,
        }
    }
}
