//! Relays that keep failing to connect are parked: their connection stops
//! redialling them (the pool alone would retry every few seconds, forever) and
//! one probe at a growing interval finds out when they are back.
//!
//! State is kept per connection (lane), because lanes take different routes:
//! a relay that refuses one route may serve another.
//!
//! While no relay is up at all (no route out, a firewall), one scout probes for
//! everyone: one attempt at a time, relays in turn, the pause growing to ten
//! minutes. A network change from the system clears it all and dials at once.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

/// Connect attempts in a row without a success before a relay is parked.
pub(crate) const PARK_AFTER: usize = 4;
/// The first probe after parking; each failed probe doubles the wait.
const FIRST_PROBE: Duration = Duration::from_secs(30);
/// Longest wait between probes, for one relay and for the scout alike.
const MAX_PROBE: Duration = Duration::from_secs(600);

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
    /// While nothing is up: the one probe in flight, and when the next may go.
    scout: Option<(u64, String)>,
    scout_level: u32,
    scout_next: Option<Instant>,
}

/// The wait before probe number [level] + 1, with ±25% jitter so relays (and
/// devices) parked together don't all knock at the same instant.
pub(crate) fn probe_wait(level: u32, jitter: f64) -> Duration {
    FIRST_PROBE.saturating_mul(1u32 << level.min(10)).min(MAX_PROBE).mul_f64(jitter.clamp(0.75, 1.25))
}

fn jitter() -> f64 {
    0.75 + rand::random::<f64>() * 0.5
}

impl Parking {
    /// One look at [url] on lane [lane]: its pool status and connection counters.
    /// [alone]: no relay is up anywhere, so only the scout may probe, and a relay that
    /// failed once is parked at once.
    #[allow(clippy::too_many_arguments)]
    pub fn observe(&mut self, lane: u64, url: &str, connected: bool, attempts: usize, success: usize, now: Instant, alone: bool) -> Verdict {
        let scout_busy = self.scout.is_some() || self.scout_next.is_some_and(|t| now < t);
        let w = self.map.entry((lane, url.to_string()))
            .or_insert(Watch { attempts, success, streak: 0, parked: None });
        if connected {
            *w = Watch { attempts, success, streak: 0, parked: None };
            return Verdict::Leave;
        }
        if let Some(p) = w.parked.as_mut() {
            if !p.probing && now >= p.due {
                if alone {
                    if scout_busy {
                        return Verdict::Leave;
                    }
                    self.scout = Some((lane, url.to_string()));
                }
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
        let limit = if alone { 1 } else { PARK_AFTER };
        if w.streak >= limit { Verdict::Park } else { Verdict::Leave }
    }

    /// [url] stops being dialled on [lane]; returns the wait before its probe.
    pub fn park(&mut self, lane: u64, url: &str, now: Instant) -> Duration {
        let wait = probe_wait(0, jitter());
        if let Some(w) = self.map.get_mut(&(lane, url.to_string())) {
            w.parked = Some(Parked { level: 0, due: now + wait, probing: false });
        }
        wait
    }

    /// The probe found it down: wait longer before the next one. The scout's failure
    /// also pushes back the next scouting, for every relay.
    pub fn probe_failed(&mut self, lane: u64, url: &str, now: Instant) -> Duration {
        let key = (lane, url.to_string());
        let mut wait = Duration::ZERO;
        if let Some(p) = self.map.get_mut(&key).and_then(|w| w.parked.as_mut()) {
            p.level = p.level.saturating_add(1);
            wait = probe_wait(p.level, jitter());
            p.due = now + wait;
            p.probing = false;
        }
        if self.scout.as_ref() == Some(&key) {
            self.scout = None;
            wait = probe_wait(self.scout_level, jitter());
            self.scout_level = self.scout_level.saturating_add(1);
            self.scout_next = Some(now + wait);
        }
        wait
    }

    /// The probe connected: the relay is back on that lane, and scouting is over.
    pub fn probe_ok(&mut self, lane: u64, url: &str) {
        self.map.remove(&(lane, url.to_string()));
        self.scout = None;
        self.scout_level = 0;
        self.scout_next = None;
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

    /// Someone asked for the connection now (the app was opened, a push arrived):
    /// scouting starts over, so one probe goes at once and the pause grows again.
    pub fn wake_scout(&mut self, now: Instant) {
        self.scout_level = 0;
        self.scout_next = None;
        for p in self.map.values_mut().filter_map(|w| w.parked.as_mut()) {
            p.due = p.due.min(now);
        }
    }

    /// A new network: what failed on the old one owes nothing.
    pub fn clear(&mut self) {
        self.map.clear();
        self.scout = None;
        self.scout_level = 0;
        self.scout_next = None;
    }

    /// Forget lanes that are gone (a persona's connection closed).
    pub fn retain_lanes(&mut self, lanes: &HashSet<u64>) {
        self.map.retain(|(l, _), _| lanes.contains(l));
        if self.scout.as_ref().is_some_and(|(l, _)| !lanes.contains(l)) {
            self.scout = None;
        }
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
        assert_eq!(p.observe(1, U, false, 1, 0, t, false), Verdict::Leave);
        assert_eq!(p.observe(1, U, false, 3, 0, t, false), Verdict::Leave);
        assert_eq!(p.observe(1, U, false, 5, 0, t, false), Verdict::Park);
        // A success in between starts the count again.
        let mut q = Parking::default();
        q.observe(1, U, false, 1, 0, t, false);
        q.observe(1, U, false, 4, 1, t, false);
        assert_eq!(q.observe(1, U, false, 6, 1, t, false), Verdict::Leave);
    }

    #[test]
    fn a_parked_relay_is_probed_once_when_due_and_waits_longer_after_each_failure() {
        let mut p = Parking::default();
        let t = Instant::now();
        p.observe(1, U, false, 0, 0, t, false);
        p.observe(1, U, false, 9, 0, t, false);
        let first = p.park(1, U, t);
        assert!(first >= Duration::from_millis(22_500) && first <= Duration::from_millis(37_500));
        assert_eq!(p.observe(1, U, false, 9, 0, t, false), Verdict::Leave);
        assert_eq!(p.observe(1, U, false, 9, 0, t + first, false), Verdict::Probe);
        // One probe at a time.
        assert_eq!(p.observe(1, U, false, 9, 0, t + first, false), Verdict::Leave);
        let second = p.probe_failed(1, U, t + first);
        assert!(second > first);
        assert!(p.is_parked(1, U));
    }

    #[test]
    fn the_wait_grows_to_ten_minutes() {
        assert_eq!(probe_wait(0, 1.0), FIRST_PROBE);
        assert_eq!(probe_wait(1, 1.0), FIRST_PROBE * 2);
        assert_eq!(probe_wait(20, 1.0), MAX_PROBE);
    }

    #[test]
    fn with_nothing_up_one_scout_probes_for_everyone_and_waits_longer_each_time() {
        const V: &str = "wss://other.relay";
        let mut p = Parking::default();
        let t = Instant::now();
        for u in [U, V] {
            p.observe(1, u, false, 0, 0, t, true);
            assert_eq!(p.observe(1, u, false, 1, 0, t, true), Verdict::Park, "parked after one failure");
            p.park(1, u, t);
        }
        let later = t + MAX_PROBE;
        assert_eq!(p.observe(1, U, false, 1, 0, later, true), Verdict::Probe);
        assert_eq!(p.observe(1, V, false, 1, 0, later, true), Verdict::Leave, "one probe at a time");
        let first = p.probe_failed(1, U, later);
        assert_eq!(p.observe(1, V, false, 1, 0, later, true), Verdict::Leave, "the scout waits before the next");
        assert_eq!(p.observe(1, V, false, 1, 0, later + first, true), Verdict::Probe, "relays take turns");
        let second = p.probe_failed(1, V, later + first);
        assert!(second > first, "the pause grows");
        let opened = later + first + Duration::from_secs(1);
        assert_eq!(p.observe(1, U, false, 1, 0, opened, true), Verdict::Leave);
        p.wake_scout(opened);
        assert_eq!(p.observe(1, U, false, 1, 0, opened, true), Verdict::Probe, "opening the app probes at once");
        assert_eq!(p.observe(1, V, false, 1, 0, opened, true), Verdict::Leave, "still one at a time");
        p.probe_ok(1, U);
        assert!(p.scout_next.is_none(), "a way back ends the scouting");
    }

    #[test]
    fn connecting_or_a_new_network_clears_the_parking() {
        let mut p = Parking::default();
        let t = Instant::now();
        p.observe(1, U, false, 0, 0, t, false);
        p.observe(1, U, false, 9, 0, t, false);
        p.park(1, U, t);
        assert!(p.is_parked(1, U));
        assert!(!p.is_parked(2, U), "another lane takes another route");
        assert_eq!(p.observe(1, U, true, 10, 1, t, false), Verdict::Leave);
        assert!(!p.is_parked(1, U));
        p.observe(1, U, false, 10, 1, t, false);
        p.observe(1, U, false, 20, 1, t, false);
        p.park(1, U, t);
        p.clear();
        assert!(!p.is_parked(1, U));
    }
}
