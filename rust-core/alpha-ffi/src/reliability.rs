//! Phase 2: Reliability Controller — the single decision center for system
//! health.
//!
//! This controller does NOT deliver messages, does NOT encrypt, does NOT
//! touch MLS. It only **observes** metrics from every subsystem and **decides**
//! the minimal action needed to keep the system healthy. The guiding
//! principle: never panic-reconnect / reset / disconnect on minor hiccups —
//! take the *minimal* corrective action and let the self-healing delivery
//! layer (Phase 1) do the heavy lifting.
//!
//! # State model (per account)
//!
//! ```text
//!   HEALTHY ──(degradation)──► DEGRADED
//!   DEGRADED ──(recovery start)──► RECOVERING
//!   RECOVERING ──(all clear)──► HEALTHY
//!   any ──(no network)──► OFFLINE
//!   OFFLINE ──(network back)──► RECOVERING
//! ```
//!
//! # Actions per state (minimal)
//!
//! - **HEALTHY**: nothing.
//! - **DEGRADED**: soft resubscribe (re-issue the subscription filter without
//!   tearing down connections). Cheap, non-disruptive.
//! - **RECOVERING**: trigger a catch-up pass + gap-recovery sweep (the
//!   delivery timer already does this; the controller just ensures it runs).
//! - **OFFLINE**: wait for the network monitor to report connectivity. No
//!   relay actions.
//!
//! The controller NEVER issues `force_reconnect`, `disconnect`, or relay
//! resets on its own — those are reserved for explicit user action only.

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;
use std::time::Instant;

use serde::{Deserialize, Serialize};

/// The four health states. Serialized as lowercase strings for the UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum HealthState {
    /// Everything nominal: relays connected, delivery flowing, no stuck gaps.
    Healthy,
    /// Something is slow / slightly off (relay latency high, a few unacked
    /// messages, stale watermark) but the system is still functioning. A soft
    /// resubscribe is the only action.
    Degraded,
    /// Active recovery in progress: catch-up + gap recovery running. The
    /// system is fixing itself; no user action needed.
    Recovering,
    /// No network / all relays down. Waiting for connectivity. No relay
    /// actions — they'd just waste battery.
    Offline,
}

impl HealthState {
    pub fn as_str(self) -> &'static str {
        match self {
            HealthState::Healthy => "healthy",
            HealthState::Degraded => "degraded",
            HealthState::Recovering => "recovering",
            HealthState::Offline => "offline",
        }
    }

    /// The emoji the UI shows next to the status.
    pub fn emoji(self) -> &'static str {
        match self {
            HealthState::Healthy => "🟢",
            HealthState::Degraded => "🟡",
            HealthState::Recovering => "🔴",
            HealthState::Offline => "⚫",
        }
    }
}

impl std::fmt::Display for HealthState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

impl Default for HealthState {
    fn default() -> Self {
        HealthState::Offline
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Metrics — one struct per subsystem, aggregated into `Metrics`.
// All times are `Instant` (monotonic) for age math; the UI gets seconds.
// ═══════════════════════════════════════════════════════════════════════════

#[derive(Debug, Clone, Default)]
pub struct RelayMetrics {
    /// Per-relay url → (connected, ping_ms, last_failure_ago_secs, event_count).
    pub relays: HashMap<String, RelayHealth>,
    /// Total relay publish failures since start.
    pub total_failures: u64,
    /// Total reconnects triggered (by anyone) since start.
    pub total_reconnects: u64,
    /// When the current subscription was (re)established.
    pub subscription_started: Option<Instant>,
}

#[derive(Debug, Clone, Default)]
pub struct RelayHealth {
    pub connected: bool,
    pub ping_ms: Option<u64>,
    pub failures: u64,
    pub last_failure: Option<Instant>,
    pub events_received: u64,
}

#[derive(Debug, Clone, Default)]
pub struct DeliveryMetrics {
    /// Count of `delivery_outbox` rows in state=pending (messages awaiting ACK).
    pub pending_outbox: u64,
    /// Count of open `delivery_gaps` (holes in received seq streams).
    pub gap_count: u64,
    /// Total resends triggered (proactive + on-request) since start.
    pub resend_count: u64,
    /// Total ACKs received since start.
    pub ack_count: u64,
    /// Rolling average ACK latency in millis (time from send → ACK).
    pub avg_ack_latency_ms: u64,
    /// When the most recent ACK was received.
    pub last_ack_at: Option<Instant>,
}

#[derive(Debug, Clone, Default)]
pub struct WakeMetrics {
    /// ntfy subscribe stream alive?
    pub ntfy_connected: bool,
    /// Last peer-wake publish succeeded?
    pub last_wake_ok: bool,
    /// When the last peer-wake was sent.
    pub last_wake_at: Option<Instant>,
    /// Total peer-wakes sent.
    pub wake_count: u64,
    /// Total peer-wake publish failures.
    pub wake_failures: u64,
}

#[derive(Debug, Clone, Default)]
pub struct SyncMetrics {
    /// When the last catch-up completed.
    pub last_catchup_at: Option<Instant>,
    /// Duration of the last catch-up in millis.
    pub last_catchup_duration_ms: u64,
    /// When the last gap-recovery sweep ran.
    pub last_recovery_at: Option<Instant>,
    /// Seconds since the delivery watermark last advanced (staleness signal).
    pub watermark_age_secs: u64,
}

#[derive(Debug, Clone, Default)]
pub struct MlsMetrics {
    /// Current group epoch (max across active groups) — diagnostic.
    pub max_epoch: u64,
    /// Number of epoch forks detected since start.
    pub fork_count: u64,
    /// Number of `recover_group_from_fork` invocations.
    pub recovery_count: u64,
    /// Count of Welcomes received but not yet joined (stuck KeyPackages).
    pub welcome_pending: u64,
}

/// The full metrics snapshot. `ReliabilityController::snapshot()` returns a
/// clone of this for the UI / debug screen.
#[derive(Debug, Clone, Default)]
pub struct Metrics {
    pub relay: RelayMetrics,
    pub delivery: DeliveryMetrics,
    pub wake: WakeMetrics,
    pub sync: SyncMetrics,
    pub mls: MlsMetrics,
    /// Current health state (the controller's decision).
    pub state: HealthState,
    /// When the state last changed.
    pub state_since: Option<Instant>,
    /// Network online (from the online monitor / last relay event).
    pub network_online: bool,
}

// ═══════════════════════════════════════════════════════════════════════════
// ReliabilityController
// ═══════════════════════════════════════════════════════════════════════════

/// The single decision center. Held inside `AlphaMessenger` as an `Arc`.
/// Subsystems call `record_*` methods to feed metrics; the health timer calls
/// `tick()` every few seconds to evaluate state + trigger minimal actions.
///
/// Actions are surfaced via `pop_pending_actions()` — the FFI layer drains
/// them and executes (soft resubscribe / catch-up / gap sweep). This keeps
/// the controller free of transport/MLS coupling.
pub struct ReliabilityController {
    metrics: Mutex<Metrics>,
    /// Actions the controller decided to take. Drained by the FFI tick.
    pending_actions: Mutex<Vec<ReliabilityAction>>,
    /// Thresholds for state transitions (tunable).
    thresholds: Thresholds,
}

/// Tunable thresholds. Defaults are conservative (avoid flapping).
#[derive(Debug, Clone)]
pub struct Thresholds {
    /// Relay ping above this (ms) for >50% of relays → DEGRADED.
    pub relay_ping_degraded_ms: u64,
    /// Pending outbox above this → DEGRADED.
    pub pending_outbox_degraded: u64,
    /// Pending outbox above this → RECOVERING.
    pub pending_outbox_recovering: u64,
    /// Open gaps above this → RECOVERING.
    pub gap_count_recovering: u64,
    /// Watermark age above this (secs) → DEGRADED.
    pub watermark_stale_secs: u64,
    /// No relay event for this (secs) → OFFLINE.
    pub no_event_offline_secs: u64,
    /// No catch-up for this (secs) → DEGRADED.
    pub no_catchup_degraded_secs: u64,
}

impl Default for Thresholds {
    fn default() -> Self {
        Self {
            relay_ping_degraded_ms: 3000,
            pending_outbox_degraded: 5,
            pending_outbox_recovering: 20,
            gap_count_recovering: 3,
            watermark_stale_secs: 120,
            no_event_offline_secs: 90,
            no_catchup_degraded_secs: 600,
        }
    }
}

/// An action the controller requests. The FFI layer executes it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReliabilityAction {
    /// Re-issue the MLS subscription filter without tearing down connections.
    /// Cheap, non-disruptive. Triggered in DEGRADED.
    ///
    /// NOTE (Phase 3 fix): this is now GATED by the REQ budget (max 2/min).
    /// The FFI layer checks the budget before actually re-subscribing — if
    /// the budget is exhausted, the action is a no-op. This eliminates the
    /// soft-resubscribe storm that overloaded relays.
    SoftResubscribe,
    /// Nudge the gap-recovery sweep (send ResendRequests for open gaps). The
    /// delivery timer does this already (every 10s); this is an immediate
    /// nudge when RECOVERING. Does NOT create new subscriptions or catch-up
    /// REQs — gap recovery uses the existing subscription + E2E resend.
    TriggerGapRecovery,
    /// No action (HEALTHY / OFFLINE). Included for completeness.
    None,
}

impl ReliabilityController {
    pub fn new() -> Self {
        Self {
            metrics: Mutex::new(Metrics {
                state: HealthState::Offline,
                state_since: Some(Instant::now()),
                network_online: false,
                ..Metrics::default()
            }),
            pending_actions: Mutex::new(Vec::new()),
            thresholds: Thresholds::default(),
        }
    }

    pub fn with_thresholds(thresholds: Thresholds) -> Self {
        let mut me = Self::new();
        me.thresholds = thresholds;
        me
    }

    // ── Recording hooks (called by subsystems) ───────────────────────────

    /// Record a per-relay health update (from the relay status snapshot).
    pub fn record_relay_statuses(&self, statuses: &[(String, bool, Option<u64>)]) {
        let mut m = self.metrics.lock().expect("metrics lock");
        for (url, connected, ping_ms) in statuses {
            let entry = m.relay.relays.entry(url.clone()).or_default();
            entry.connected = *connected;
            entry.ping_ms = *ping_ms;
        }
        // Network online iff at least one relay is connected.
        m.network_online = m.relay.relays.values().any(|r| r.connected);
    }

    /// Record a relay event arrival (for event-rate + liveness).
    pub fn record_relay_event(&self, url: &str) {
        let mut m = self.metrics.lock().expect("metrics lock");
        let entry = m.relay.relays.entry(url.to_string()).or_default();
        entry.events_received += 1;
    }

    /// Record a relay publish failure.
    pub fn record_relay_failure(&self, url: Option<&str>) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.relay.total_failures += 1;
        if let Some(u) = url {
            let entry = m.relay.relays.entry(u.to_string()).or_default();
            entry.failures += 1;
            entry.last_failure = Some(Instant::now());
        }
    }

    /// Record that a reconnect was triggered (by anyone — usually the user).
    pub fn record_reconnect(&self) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.relay.total_reconnects += 1;
        m.relay.subscription_started = Some(Instant::now());
    }

    /// Record that the subscription was (re)established.
    pub fn record_subscription_started(&self) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.relay.subscription_started = Some(Instant::now());
    }

    /// Record delivery outbox / gap counts (from the DB, polled by the tick).
    pub fn record_delivery_counts(&self, pending_outbox: u64, gap_count: u64) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.delivery.pending_outbox = pending_outbox;
        m.delivery.gap_count = gap_count;
    }

    /// Record an ACK received + its latency (send→ACK time).
    pub fn record_ack(&self, latency_ms: u64) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.delivery.ack_count += 1;
        m.delivery.last_ack_at = Some(Instant::now());
        // Rolling average (simple EMA for stability).
        if m.delivery.avg_ack_latency_ms == 0 {
            m.delivery.avg_ack_latency_ms = latency_ms;
        } else {
            m.delivery.avg_ack_latency_ms =
                (m.delivery.avg_ack_latency_ms * 7 + latency_ms) / 8;
        }
    }

    /// Record a resend (proactive or on-request).
    pub fn record_resend(&self) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.delivery.resend_count += 1;
    }

    /// Record ntfy connection state.
    pub fn record_ntfy_state(&self, connected: bool) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.wake.ntfy_connected = connected;
    }

    /// Record a peer-wake publish result.
    pub fn record_peer_wake(&self, ok: bool) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.wake.last_wake_ok = ok;
        m.wake.last_wake_at = Some(Instant::now());
        m.wake.wake_count += 1;
        if !ok {
            m.wake.wake_failures += 1;
        }
    }

    /// Record that a catch-up pass completed (duration in millis).
    pub fn record_catchup(&self, duration_ms: u64) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.sync.last_catchup_at = Some(Instant::now());
        m.sync.last_catchup_duration_ms = duration_ms;
    }

    /// Record that a gap-recovery sweep ran.
    pub fn record_recovery_sweep(&self) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.sync.last_recovery_at = Some(Instant::now());
    }

    /// Record the delivery watermark age (seconds since last advance).
    pub fn record_watermark_age(&self, secs: u64) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.sync.watermark_age_secs = secs;
    }

    /// Record MLS metrics (polled by the tick from the MLS session).
    pub fn record_mls(&self, max_epoch: u64, welcome_pending: u64) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.mls.max_epoch = max_epoch;
        m.mls.welcome_pending = welcome_pending;
    }

    /// Record an MLS epoch fork detection.
    pub fn record_fork(&self) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.mls.fork_count += 1;
    }

    /// Record an MLS recovery invocation.
    pub fn record_recovery(&self) {
        let mut m = self.metrics.lock().expect("metrics lock");
        m.mls.recovery_count += 1;
    }

    // ── Decision tick ────────────────────────────────────────────────────

    /// Evaluate the current metrics and decide the health state + any minimal
    /// action. Called periodically (every ~5s) by the health timer. Idempotent
    /// and cheap — just reads metrics under a brief lock, computes, and pushes
    /// at most one action to `pending_actions`.
    pub fn tick(&self, last_event_at: Instant) {
        let new_state = self.evaluate(last_event_at);
        let action = self.action_for(new_state);
        {
            let mut m = self.metrics.lock().expect("metrics lock");
            if m.state != new_state {
                tracing::info!(
                    target: "RELIABILITY",
                    "state transition {} → {} (action={:?})",
                    m.state, new_state, action,
                );
                m.state = new_state;
                m.state_since = Some(Instant::now());
            }
        }
        // Push the action (dedup: don't queue the same action if one is
        // already pending). None is never queued.
        if action != ReliabilityAction::None {
            let mut pa = self.pending_actions.lock().expect("actions lock");
            if !pa.contains(&action) {
                pa.push(action);
            }
        }
    }

    /// Compute the health state from current metrics. Pure function (no side
    /// effects) — easy to unit-test.
    fn evaluate(&self, last_event_at: Instant) -> HealthState {
        let m = self.metrics.lock().expect("metrics lock");
        // OFFLINE takes precedence: no network → nothing else matters.
        let secs_since_event = last_event_at.elapsed().as_secs();
        if !m.network_online || secs_since_event > self.thresholds.no_event_offline_secs {
            // But if we have pending outbox + gaps, we're RECOVERING once net
            // returns — for now, OFFLINE.
            return HealthState::Offline;
        }
        // RECOVERING: lots of pending work that needs active catch-up.
        if m.delivery.pending_outbox > self.thresholds.pending_outbox_recovering
            || m.delivery.gap_count > self.thresholds.gap_count_recovering
        {
            return HealthState::Recovering;
        }
        // DEGRADED: something is slow/stale but not critical.
        let degraded_ping = {
            let connected: Vec<&RelayHealth> = m
                .relay
                .relays
                .values()
                .filter(|r| r.connected)
                .collect();
            if connected.is_empty() {
                false
            } else {
                let slow = connected
                    .iter()
                    .filter(|r| r.ping_ms.unwrap_or(u64::MAX) > self.thresholds.relay_ping_degraded_ms)
                    .count();
                slow * 2 > connected.len()
            }
        };
        let stale_watermark =
            m.sync.watermark_age_secs > self.thresholds.watermark_stale_secs;
        // Phase 3 fix: "stale catch-up" alone is NOT a degradation signal.
        // Catch-up is now gap-only (no periodic polling). If there are 0 gaps
        // + 0 pending outbox, the system is HEALTHY even if catch-up hasn't
        // run recently — there's simply nothing to catch up on. Only flag
        // degraded if there's actual pending work.
        let degraded_outbox =
            m.delivery.pending_outbox > self.thresholds.pending_outbox_degraded;
        if degraded_ping || stale_watermark || degraded_outbox {
            return HealthState::Degraded;
        }
        // HEALTHY: everything nominal.
        HealthState::Healthy
    }

    /// The minimal action for a given state.
    fn action_for(&self, state: HealthState) -> ReliabilityAction {
        match state {
            HealthState::Healthy | HealthState::Offline => ReliabilityAction::None,
            HealthState::Degraded => ReliabilityAction::SoftResubscribe,
            HealthState::Recovering => ReliabilityAction::TriggerGapRecovery,
        }
    }

    /// Drain pending actions (FFI layer calls this on each health tick and
    /// executes them). Returns actions in FIFO order; clears the queue.
    pub fn pop_pending_actions(&self) -> Vec<ReliabilityAction> {
        let mut pa = self.pending_actions.lock().expect("actions lock");
        let drained = pa.clone();
        pa.clear();
        drained
    }

    // ── Snapshots for the UI ─────────────────────────────────────────────

    /// Current health state.
    pub fn state(&self) -> HealthState {
        self.metrics.lock().expect("metrics lock").state
    }

    /// A full metrics snapshot (cloned under a brief lock).
    pub fn snapshot(&self) -> Metrics {
        self.metrics.lock().expect("metrics lock").clone()
    }

    /// A JSON snapshot for the FFI/UI. This is what `get_health_snapshot_json`
    /// returns to Kotlin. Keeps the wire format stable + human-readable.
    pub fn snapshot_json(&self) -> String {
        let m = self.metrics.lock().expect("metrics lock");
        let now = Instant::now();
        let secs_since = |t: Option<Instant>| -> u64 {
            t.map(|t| now.duration_since(t).as_secs()).unwrap_or(0)
        };
        // Relay summary: connected count + worst ping.
        let connected_relays = m.relay.relays.values().filter(|r| r.connected).count();
        let total_relays = m.relay.relays.len();
        let worst_ping = m
            .relay
            .relays
            .values()
            .filter_map(|r| r.ping_ms)
            .max()
            .unwrap_or(0);
        let sub_age = secs_since(m.relay.subscription_started);
        let total_events: u64 = m.relay.relays.values().map(|r| r.events_received).sum();

        serde_json::json!({
            "state": m.state.as_str(),
            "state_emoji": m.state.emoji(),
            "network_online": m.network_online,
            "relay": {
                "connected": connected_relays,
                "total": total_relays,
                "worst_ping_ms": worst_ping,
                "subscription_age_secs": sub_age,
                "total_failures": m.relay.total_failures,
                "total_reconnects": m.relay.total_reconnects,
                "total_events": total_events,
            },
            "delivery": {
                "pending_outbox": m.delivery.pending_outbox,
                "gap_count": m.delivery.gap_count,
                "resend_count": m.delivery.resend_count,
                "ack_count": m.delivery.ack_count,
                "avg_ack_latency_ms": m.delivery.avg_ack_latency_ms,
                "last_ack_secs_ago": secs_since(m.delivery.last_ack_at),
            },
            "wake": {
                "ntfy_connected": m.wake.ntfy_connected,
                "last_wake_ok": m.wake.last_wake_ok,
                "wake_count": m.wake.wake_count,
                "wake_failures": m.wake.wake_failures,
                "last_wake_secs_ago": secs_since(m.wake.last_wake_at),
            },
            "sync": {
                "last_catchup_secs_ago": secs_since(m.sync.last_catchup_at),
                "last_catchup_duration_ms": m.sync.last_catchup_duration_ms,
                "last_recovery_secs_ago": secs_since(m.sync.last_recovery_at),
                "watermark_age_secs": m.sync.watermark_age_secs,
            },
            "mls": {
                "max_epoch": m.mls.max_epoch,
                "fork_count": m.mls.fork_count,
                "recovery_count": m.mls.recovery_count,
                "welcome_pending": m.mls.welcome_pending,
            },
        })
        .to_string()
    }
}

impl Default for ReliabilityController {
    fn default() -> Self {
        Self::new()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Tests
// ═══════════════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    fn ctrl() -> ReliabilityController {
        ReliabilityController::new()
    }

    #[test]
    fn test_state_transitions_offline_when_no_network() {
        let c = ctrl();
        // No relays recorded → network_online=false → OFFLINE.
        let now = Instant::now();
        c.tick(now);
        assert_eq!(c.state(), HealthState::Offline);
    }

    #[test]
    fn test_healthy_when_all_nominal() {
        let c = ctrl();
        // One connected relay, recent event, recent catch-up, no pending.
        c.record_relay_statuses(&[("wss://r1".into(), true, Some(100))]);
        c.record_subscription_started();
        c.record_catchup(500);
        c.record_watermark_age(5);
        let now = Instant::now();
        c.tick(now);
        assert_eq!(c.state(), HealthState::Healthy);
    }

    #[test]
    fn test_degraded_when_ping_high() {
        let c = ctrl();
        // >50% of relays have high ping → DEGRADED.
        c.record_relay_statuses(&[
            ("wss://r1".into(), true, Some(5000)),
            ("wss://r2".into(), true, Some(5000)),
            ("wss://r3".into(), true, Some(100)),
        ]);
        c.record_subscription_started();
        c.record_catchup(500);
        c.record_watermark_age(5);
        let now = Instant::now();
        c.tick(now);
        assert_eq!(c.state(), HealthState::Degraded);
    }

    #[test]
    fn test_healthy_without_catchup() {
        // Phase 3 fix: no catch-up + no gaps + no pending = HEALTHY (not degraded).
        // Catch-up is gap-only now; if there's nothing to catch up on, the system
        // is healthy.
        let c = ctrl();
        c.record_relay_statuses(&[("wss://r1".into(), true, Some(100))]);
        c.record_subscription_started();
        c.record_watermark_age(5);
        c.record_delivery_counts(0, 0); // no pending, no gaps
        let now = Instant::now();
        c.tick(now);
        assert_eq!(c.state(), HealthState::Healthy, "no pending work → healthy");
    }

    #[test]
    fn test_recovering_when_many_pending() {
        let c = ctrl();
        c.record_relay_statuses(&[("wss://r1".into(), true, Some(100))]);
        c.record_subscription_started();
        c.record_catchup(500);
        c.record_watermark_age(5);
        c.record_delivery_counts(50, 0); // > pending_outbox_recovering (20)
        let now = Instant::now();
        c.tick(now);
        assert_eq!(c.state(), HealthState::Recovering);
    }

    #[test]
    fn test_recovering_when_many_gaps() {
        let c = ctrl();
        c.record_relay_statuses(&[("wss://r1".into(), true, Some(100))]);
        c.record_subscription_started();
        c.record_catchup(500);
        c.record_watermark_age(5);
        c.record_delivery_counts(0, 10); // > gap_count_recovering (3)
        let now = Instant::now();
        c.tick(now);
        assert_eq!(c.state(), HealthState::Recovering);
    }

    #[test]
    fn test_action_for_state() {
        let c = ctrl();
        assert_eq!(c.action_for(HealthState::Healthy), ReliabilityAction::None);
        assert_eq!(c.action_for(HealthState::Offline), ReliabilityAction::None);
        assert_eq!(c.action_for(HealthState::Degraded), ReliabilityAction::SoftResubscribe);
        assert_eq!(c.action_for(HealthState::Recovering), ReliabilityAction::TriggerGapRecovery);
    }

    #[test]
    fn test_pending_actions_dedup() {
        let c = ctrl();
        // Push the same action twice → only one queued.
        let mut pa = c.pending_actions.lock().unwrap();
        pa.push(ReliabilityAction::SoftResubscribe);
        pa.push(ReliabilityAction::SoftResubscribe);
        drop(pa);
        // pop returns both (dedup happens in tick, not in pop). Verify pop clears.
        let drained = c.pop_pending_actions();
        assert_eq!(drained.len(), 2); // we pushed twice directly
        // After pop, queue is empty.
        assert!(c.pop_pending_actions().is_empty());
    }

    #[test]
    fn test_snapshot_json_has_all_sections() {
        let c = ctrl();
        c.record_relay_statuses(&[("wss://r1".into(), true, Some(100))]);
        c.record_ack(1200);
        c.record_catchup(500);
        let json = c.snapshot_json();
        assert!(json.contains("\"state\""));
        assert!(json.contains("\"relay\""));
        assert!(json.contains("\"delivery\""));
        assert!(json.contains("\"wake\""));
        assert!(json.contains("\"sync\""));
        assert!(json.contains("\"mls\""));
        assert!(json.contains("avg_ack_latency_ms"));
    }

    #[test]
    fn test_state_emoji_and_str() {
        assert_eq!(HealthState::Healthy.as_str(), "healthy");
        assert_eq!(HealthState::Degraded.as_str(), "degraded");
        assert_eq!(HealthState::Recovering.as_str(), "recovering");
        assert_eq!(HealthState::Offline.as_str(), "offline");
        assert_eq!(HealthState::Healthy.emoji(), "🟢");
        assert_eq!(HealthState::Degraded.emoji(), "🟡");
        assert_eq!(HealthState::Recovering.emoji(), "🔴");
        assert_eq!(HealthState::Offline.emoji(), "⚫");
    }
}
