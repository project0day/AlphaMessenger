//! Relays that keep failing to connect are parked: their connection stops
//! redialling them (the pool alone would retry every few seconds, forever) and
//! one probe at a growing interval finds out when they are back.
//!
//! State is kept per connection (lane), because lanes take different routes:
//! a relay that refuses one route may serve another.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

/// Connect attempts in a row without a success before a relay is parked.
pub(crate) const PARK_AFTER: usize = 4;
/// The first probe after parking; each failed probe doubles the wait.
const FIRST_PROBE: Duration = Duration::from_secs(30);
/// Longest wait between probes while other relays carry the traffic.
const MAX_PROBE: Duration = Duration::from_secs(600);
/// Longest wait while no relay is up at all: the way back must stay short.
const MAX_PROBE_ALONE: Duration = Duration::from_secs(30);

/// What to do with one relay of one lane right now.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Verdict {
    /// Connected, or still within its normal retries.
    Leave,
    /// Failed too often: stop its dialling.
    Park,
    /// Parked and due: one connect attempt.
    Probe,
}

#[derive(Debug, Clone, Copy)]
struct Parked {
    level: u32,
    due: Instant,
    probing: bool,
}

#[derive(Debug, Clone, Copy)]
struct Watch {
    attempts: usize,
    success: usize,
    streak: usize,
    parked: Option<Parked>,
}

#[derive(Debug, Default)]
pub(crate) struct Parking {
    map: HashMap<(u64, String), Watch>,
}

/// The wait before probe number [level] + 1, with ±25% jitter so relays (and
/// devices) parked together don't all knock at the same instant.
pub(crate) fn probe_wait(level: u32, any_up: bool, jitter: f64) -> Duration {
    let cap = if any_up { MAX_PROBE } else { MAX_PROBE_ALONE };
    FIRST_PROBE.saturating_mul(1u32 << level.min(10)).min(cap).mul_f64(jitter.clamp(0.75, 1.25))
}

fn jitter() -> f64 {
    0.75 + rand::random::<f64>() * 0.5
}

impl Parking {
    /// One look at [url] on lane [lane]: its pool status and connection counters.
    pub fn observe(&mut self, lane: u64, url: &str, connected: bool, attempts: usize, success: usize, now: Instant) -> Verdict {
        let w = self.map.entry((lane, url.to_string()))
            .or_insert(Watch { attempts, success, streak: 0, parked: None });
        if connected {
            *w = Watch { attempts, success, streak: 0, parked: None };
            return Verdict::Leave;
        }
        if let Some(p) = w.parked.as_mut() {
            if !p.probing && now >= p.due {
                p.probing = true;
                return Verdict::Probe;
            }
            return Verdict::Leave;
        }
        if success > w.success {
            w.streak = 0;
        } else {
            w.streak += attempts.saturating_sub(w.attempts);
        }
        w.attempts = attempts;
        w.success = success;
        if w.streak >= PARK_AFTER { Verdict::Park } else { Verdict::Leave }
    }

    /// [url] stops being dialled on [lane]; returns the wait before its probe.
    pub fn park(&mut self, lane: u64, url: &str, any_up: bool, now: Instant) -> Duration {
        let wait = probe_wait(0, any_up, jitter());
        if let Some(w) = self.map.get_mut(&(lane, url.to_string())) {
            w.parked = Some(Parked { level: 0, due: now + wait, probing: false });
        }
        wait
    }

    /// The probe found it down: wait longer before the next one.
    pub fn probe_failed(&mut self, lane: u64, url: &str, any_up: bool, now: Instant) -> Duration {
        let Some(p) = self.map.get_mut(&(lane, url.to_string())).and_then(|w| w.parked.as_mut()) else {
            return Duration::ZERO;
        };
        p.level = p.level.saturating_add(1);
        let wait = probe_wait(p.level, any_up, jitter());
        p.due = now + wait;
        p.probing = false;
        wait
    }

    /// The probe connected: the relay is back on that lane.
    pub fn probe_ok(&mut self, lane: u64, url: &str) {
        self.map.remove(&(lane, url.to_string()));
    }

    /// Whether [url] is parked on [lane].
    pub fn is_parked(&self, lane: u64, url: &str) -> bool {
        self.map.get(&(lane, url.to_string())).is_some_and(|w| w.parked.is_some())
    }

    /// How long until [url]'s next probe on [lane], when it is parked.
    pub fn retry_in(&self, lane: u64, url: &str, now: Instant) -> Option<Duration> {
        self.map.get(&(lane, url.to_string()))?.parked.map(|p| p.due.saturating_duration_since(now))
    }

    /// Relays parked on [lane].
    pub fn parked_on(&self, lane: u64) -> HashSet<String> {
        self.map.iter().filter(|((l, _), w)| *l == lane && w.parked.is_some()).map(|((_, u), _)| u.clone()).collect()
    }

    /// A new route for [lane] (new circuits): its relays get a fresh start.
    pub fn clear_lane(&mut self, lane: u64) {
        self.map.retain(|(l, _), _| *l != lane);
    }

    /// [url] was switched on again by the user: a fresh start on every lane.
    pub fn clear_url(&mut self, url: &str) {
        self.map.retain(|(_, u), _| u != url);
    }

    /// A new network: what failed on the old one owes nothing.
    pub fn clear(&mut self) {
        self.map.clear();
    }

    /// Forget lanes that are gone (a persona's connection closed).
    pub fn retain_lanes(&mut self, lanes: &HashSet<u64>) {
        self.map.retain(|(l, _), _| lanes.contains(l));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const U: &str = "wss://dead.relay";

    #[test]
    fn a_relay_is_parked_after_repeated_failed_attempts_only() {
        let mut p = Parking::default();
        let t = Instant::now();
        assert_eq!(p.observe(1, U, false, 1, 0, t), Verdict::Leave);
        assert_eq!(p.observe(1, U, false, 3, 0, t), Verdict::Leave);
        assert_eq!(p.observe(1, U, false, 5, 0, t), Verdict::Park);
        // A success in between starts the count again.
        let mut q = Parking::default();
        q.observe(1, U, false, 1, 0, t);
        q.observe(1, U, false, 4, 1, t);
        assert_eq!(q.observe(1, U, false, 6, 1, t), Verdict::Leave);
    }

    #[test]
    fn a_parked_relay_is_probed_once_when_due_and_waits_longer_after_each_failure() {
        let mut p = Parking::default();
        let t = Instant::now();
        p.observe(1, U, false, 0, 0, t);
        p.observe(1, U, false, 9, 0, t);
        let first = p.park(1, U, true, t);
        assert!(first >= Duration::from_millis(22_500) && first <= Duration::from_millis(37_500));
        assert_eq!(p.observe(1, U, false, 9, 0, t), Verdict::Leave);
        assert_eq!(p.observe(1, U, false, 9, 0, t + first), Verdict::Probe);
        // One probe at a time.
        assert_eq!(p.observe(1, U, false, 9, 0, t + first), Verdict::Leave);
        let second = p.probe_failed(1, U, true, t + first);
        assert!(second > first);
        assert!(p.is_parked(1, U));
    }

    #[test]
    fn the_wait_is_capped_shorter_when_nothing_else_is_up() {
        assert!(probe_wait(20, true, 1.0) == MAX_PROBE);
        assert!(probe_wait(20, false, 1.0) == MAX_PROBE_ALONE);
        assert_eq!(probe_wait(0, true, 1.0), FIRST_PROBE);
    }

    #[test]
    fn connecting_or_a_new_network_clears_the_parking() {
        let mut p = Parking::default();
        let t = Instant::now();
        p.observe(1, U, false, 0, 0, t);
        p.observe(1, U, false, 9, 0, t);
        p.park(1, U, true, t);
        assert!(p.is_parked(1, U));
        assert!(!p.is_parked(2, U), "another lane takes another route");
        assert_eq!(p.observe(1, U, true, 10, 1, t), Verdict::Leave);
        assert!(!p.is_parked(1, U));
        p.observe(1, U, false, 10, 1, t);
        p.observe(1, U, false, 20, 1, t);
        p.park(1, U, true, t);
        p.clear();
        assert!(!p.is_parked(1, U));
    }
}
