//! How many transfer requests the phone's link carries at once, learned from the
//! requests themselves.
//!
//! The window is sized so the bytes in flight drain in about [`DRAIN_TARGET`]. On a
//! weak link that is one or two chunks, so each lands (and is checkpointed) soon, the
//! progress moves from the first seconds, and messages are not queued behind a wall of media. A request that waits on its server
//! rather than on the link costs little link time, so a slow server never narrows it.

use std::sync::Mutex;
use std::time::{Duration, Instant};

const DRAIN_TARGET: Duration = Duration::from_secs(5);
/// One cut per burst: the requests of one dead path all fail together.
const CUT_SPACING: Duration = Duration::from_secs(2);

const UPLOAD_START: usize = 2;
const DOWNLOAD_START: usize = 8;

pub(crate) struct LinkWindow {
    start: usize,
    max: usize,
    state: Mutex<State>,
    freed: tokio::sync::Notify,
}

struct State {
    limit: usize,
    active: usize,
    /// Smoothed link time of one request, `took / sharing`, in ms; 0 = not measured.
    per_request_ms: u64,
    last_end: Option<Instant>,
    last_cut: Option<Instant>,
    /// Bumped on a network change: requests begun before it teach nothing.
    generation: u64,
}

/// How a request ended, as far as the link is concerned.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Outcome {
    Delivered,
    /// The server answered with an error status: the link worked.
    Answered,
    /// The server was never reached: its own fault or its route's, not the link's.
    Unreached,
    /// No answer: the connection broke or stopped moving.
    Broken,
}

/// A place in the window, held for one request.
pub(crate) struct Turn {
    window: &'static LinkWindow,
    started: Instant,
    sharing: usize,
    generation: u64,
}

impl LinkWindow {
    const fn new(start: usize, max: usize) -> Self {
        Self {
            start,
            max,
            state: Mutex::new(State {
                limit: start,
                active: 0,
                per_request_ms: 0,
                last_end: None,
                last_cut: None,
                generation: 0,
            }),
            freed: tokio::sync::Notify::const_new(),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }

    pub(crate) async fn enter(&'static self) -> Turn {
        loop {
            let freed = self.freed.notified();
            futures::pin_mut!(freed);
            freed.as_mut().enable();
            {
                let mut s = self.lock();
                if s.active < s.limit {
                    s.active += 1;
                    return Turn { window: self, started: Instant::now(), sharing: s.active, generation: s.generation };
                }
            }
            freed.await;
        }
    }

    /// How long no request has been in flight.
    pub(crate) fn quiet_for(&self) -> Duration {
        let s = self.lock();
        match (s.active, s.last_end) {
            (0, Some(t)) => t.elapsed(),
            (0, None) => Duration::MAX,
            _ => Duration::ZERO,
        }
    }

    fn reset(&self) {
        let mut s = self.lock();
        s.limit = self.start;
        s.per_request_ms = 0;
        s.last_cut = None;
        s.generation += 1;
        drop(s);
        self.freed.notify_waiters();
    }

    fn learn(&self, turn: &Turn, outcome: Outcome) {
        let mut s = self.lock();
        if turn.generation != s.generation {
            return;
        }
        let limit = s.limit;
        // A window that was not full says nothing about what the link would carry.
        let sharing = turn.sharing.max(s.active);
        let next = match outcome {
            Outcome::Answered | Outcome::Unreached => return,
            Outcome::Broken => {
                if s.last_cut.is_some_and(|t| t.elapsed() < CUT_SPACING) {
                    return;
                }
                s.last_cut = Some(Instant::now());
                (limit / 2).max(1)
            }
            Outcome::Delivered if sharing < limit => return,
            Outcome::Delivered => {
                let sample = (turn.started.elapsed().as_millis() as u64 / sharing as u64).max(1);
                s.per_request_ms = match s.per_request_ms {
                    0 => sample,
                    old => (old * 3 + sample) / 4,
                };
                let target = (DRAIN_TARGET.as_millis() as u64 / s.per_request_ms).clamp(1, self.max as u64) as usize;
                if target > limit {
                    limit + 1
                } else if target < limit {
                    target.max(limit / 2).max(1)
                } else {
                    limit
                }
            }
        };
        if next != limit {
            s.limit = next;
            let per = s.per_request_ms;
            drop(s);
            tracing::warn!(target: "alpha_ffi", "LINK_WINDOW max={} {limit}->{next} per_request_ms={per} {outcome:?}", self.max);
            if next > limit {
                self.freed.notify_waiters();
            }
        }
    }
}

impl Turn {
    pub(crate) fn finish(self, outcome: Outcome) {
        self.window.learn(&self, outcome);
    }
}

impl Drop for Turn {
    fn drop(&mut self) {
        let mut s = self.window.lock();
        s.active -= 1;
        s.last_end = Some(Instant::now());
        drop(s);
        self.window.freed.notify_waiters();
    }
}

pub(crate) fn upload_window() -> &'static LinkWindow {
    static W: LinkWindow = LinkWindow::new(UPLOAD_START, crate::pipeline::GLOBAL_UPLOAD_SLOTS);
    &W
}

pub(crate) fn download_window() -> &'static LinkWindow {
    static W: LinkWindow = LinkWindow::new(DOWNLOAD_START, crate::pipeline::GLOBAL_CHUNK_SLOTS);
    &W
}

static NETWORK_EPOCH: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Which network the device is on, as a counter: it moves on every switch.
pub(crate) fn network_epoch() -> u64 {
    NETWORK_EPOCH.load(std::sync::atomic::Ordering::Relaxed)
}

/// The device switched networks: what was learned about the old link no longer holds.
pub fn network_changed() {
    NETWORK_EPOCH.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    upload_window().reset();
    download_window().reset();
}

#[cfg(test)]
mod tests {
    use super::*;

    fn leaked(start: usize, max: usize) -> &'static LinkWindow {
        Box::leak(Box::new(LinkWindow::new(start, max)))
    }

    fn limit(w: &LinkWindow) -> usize {
        w.lock().limit
    }

    /// Fills the window, then ends every request as if it took `took`.
    async fn wave(w: &'static LinkWindow, took: Duration, outcome: Outcome) {
        let n = limit(w);
        let mut turns = Vec::new();
        for _ in 0..n {
            turns.push(w.enter().await);
        }
        for mut t in turns {
            t.started = Instant::now() - took;
            t.finish(outcome);
        }
    }

    #[tokio::test]
    async fn a_fast_link_opens_the_window_to_its_cap() {
        let w = leaked(4, 8);
        for _ in 0..4 {
            wave(w, Duration::from_millis(400), Outcome::Delivered).await;
        }
        assert_eq!(limit(w), 8);
    }

    #[tokio::test]
    async fn a_slow_server_on_a_fast_link_keeps_the_window_open() {
        let w = leaked(8, 8);
        for _ in 0..6 {
            wave(w, Duration::from_secs(5), Outcome::Delivered).await;
        }
        assert_eq!(limit(w), 8);
    }

    #[tokio::test]
    async fn a_weak_link_narrows_to_what_drains_in_time() {
        // 30 KB/s and 341 KB chunks: one chunk alone takes ~11 s.
        let w = leaked(8, 8);
        for _ in 0..6 {
            let n = limit(w) as u64;
            wave(w, Duration::from_millis(11_000 * n), Outcome::Delivered).await;
        }
        assert_eq!(limit(w), 1);
    }

    #[tokio::test]
    async fn server_errors_leave_the_window_alone() {
        let w = leaked(6, 8);
        wave(w, Duration::from_secs(1), Outcome::Answered).await;
        assert_eq!(limit(w), 6);
    }

    #[tokio::test]
    async fn an_unreachable_server_leaves_the_window_alone() {
        let w = leaked(8, 8);
        wave(w, Duration::from_secs(12), Outcome::Unreached).await;
        assert_eq!(limit(w), 8);
    }

    #[tokio::test]
    async fn a_dead_path_cuts_once_per_burst() {
        let w = leaked(8, 8);
        wave(w, Duration::from_secs(30), Outcome::Broken).await;
        assert_eq!(limit(w), 4);
    }

    #[tokio::test]
    async fn requests_begun_before_a_network_change_teach_nothing() {
        let w = leaked(4, 8);
        let t = w.enter().await;
        w.reset();
        t.finish(Outcome::Broken);
        assert_eq!(limit(w), 4);
    }

    #[tokio::test]
    async fn an_idle_window_learns_nothing_from_a_lone_request() {
        let w = leaked(4, 8);
        let mut t = w.enter().await;
        t.started = Instant::now() - Duration::from_secs(20);
        t.finish(Outcome::Delivered);
        assert_eq!(limit(w), 4);
    }

    #[tokio::test]
    async fn quiet_counts_from_the_last_request() {
        let w = leaked(4, 8);
        assert_eq!(w.quiet_for(), Duration::MAX);
        let t = w.enter().await;
        assert_eq!(w.quiet_for(), Duration::ZERO);
        drop(t);
        assert!(w.quiet_for() < Duration::from_secs(1));
    }
}
