//! Named mutex that records who holds it.
//!
//! A stuck lock is reported with the holder's source line and thread, and a
//! same-thread re-lock returns an error instead of deadlocking forever.

use std::fmt;
use std::panic::Location;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::thread::ThreadId;
use std::time::{Duration, Instant};

/// Holders older than this are logged by the watcher.
const HELD_WARN: Duration = Duration::from_secs(10);

#[derive(Clone)]
struct Holder {
    at: &'static Location<'static>,
    thread: ThreadId,
    thread_name: String,
    since: Instant,
}

struct Slot {
    name: &'static str,
    holder: Mutex<Option<Holder>>,
    waiters: AtomicUsize,
}

impl Slot {
    fn get(&self) -> Option<Holder> {
        self.holder.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }
    fn set(&self, h: Option<Holder>) {
        *self.holder.lock().unwrap_or_else(|p| p.into_inner()) = h;
    }
}

fn registry() -> &'static Mutex<Vec<&'static Slot>> {
    static R: OnceLock<Mutex<Vec<&'static Slot>>> = OnceLock::new();
    R.get_or_init(|| {
        let _ = std::thread::Builder::new().name("lock-watch".into()).spawn(|| loop {
            std::thread::sleep(Duration::from_secs(5));
            for line in held_lines(HELD_WARN) {
                tracing::warn!(target: "TRANSPORT", "LOCK_HELD {}", line);
            }
        });
        Mutex::new(Vec::new())
    })
}

fn held_lines(min: Duration) -> Vec<String> {
    let slots: Vec<&'static Slot> = registry().lock().unwrap_or_else(|p| p.into_inner()).clone();
    slots
        .iter()
        .filter_map(|s| {
            let h = s.get()?;
            let age = h.since.elapsed();
            (age >= min).then(|| {
                format!(
                    "{} {}s by {}:{} thread={} waiters={}",
                    s.name, age.as_secs(), h.at.file(), h.at.line(), h.thread_name,
                    s.waiters.load(Ordering::Relaxed),
                )
            })
        })
        .collect()
}

/// Every tracked lock that is held right now, plus the database lock.
/// Takes no core lock, so it answers while the core is stuck.
pub fn report() -> String {
    let mut lines = held_lines(Duration::ZERO);
    if let Some(db) = alpha_database::pool::db_holder() {
        lines.push(format!("db {}", db));
    }
    if lines.is_empty() { "no locks held".to_string() } else { lines.join("\n") }
}

#[derive(Debug)]
pub enum LockError {
    Poisoned(&'static str),
    Reentrant { name: &'static str, at: String, held_at: String },
}

impl fmt::Display for LockError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            LockError::Poisoned(n) => write!(f, "{} lock poisoned", n),
            LockError::Reentrant { name, at, held_at } => {
                write!(f, "{} re-locked at {} while held at {}", name, at, held_at)
            }
        }
    }
}

impl std::error::Error for LockError {}

#[derive(Debug)]
pub enum TryLockError {
    WouldBlock,
    Poisoned(&'static str),
}

pub struct TrackedMutex<T> {
    inner: Mutex<T>,
    slot: &'static Slot,
}

impl<T> TrackedMutex<T> {
    pub fn new(name: &'static str, value: T) -> Self {
        // One slot per lock for the process lifetime; a handful exist.
        let slot: &'static Slot = Box::leak(Box::new(Slot {
            name,
            holder: Mutex::new(None),
            waiters: AtomicUsize::new(0),
        }));
        registry().lock().unwrap_or_else(|p| p.into_inner()).push(slot);
        Self { inner: Mutex::new(value), slot }
    }

    #[track_caller]
    pub fn lock(&self) -> Result<TrackedGuard<'_, T>, LockError> {
        let at = Location::caller();
        let me = std::thread::current();
        if let Some(h) = self.slot.get() {
            if h.thread == me.id() {
                let err = LockError::Reentrant {
                    name: self.slot.name,
                    at: format!("{}:{}", at.file(), at.line()),
                    held_at: format!("{}:{}", h.at.file(), h.at.line()),
                };
                tracing::error!(target: "TRANSPORT", "LOCK_REENTRY {}", err);
                return Err(err);
            }
        }
        self.slot.waiters.fetch_add(1, Ordering::Relaxed);
        let res = self.inner.lock();
        self.slot.waiters.fetch_sub(1, Ordering::Relaxed);
        let guard = res.map_err(|_| LockError::Poisoned(self.slot.name))?;
        Ok(self.held(guard, at))
    }

    #[track_caller]
    pub fn try_lock(&self) -> Result<TrackedGuard<'_, T>, TryLockError> {
        let at = Location::caller();
        match self.inner.try_lock() {
            Ok(guard) => Ok(self.held(guard, at)),
            Err(std::sync::TryLockError::WouldBlock) => Err(TryLockError::WouldBlock),
            Err(std::sync::TryLockError::Poisoned(_)) => Err(TryLockError::Poisoned(self.slot.name)),
        }
    }

    fn held<'a>(&'a self, guard: MutexGuard<'a, T>, at: &'static Location<'static>) -> TrackedGuard<'a, T> {
        let me = std::thread::current();
        self.slot.set(Some(Holder {
            at,
            thread: me.id(),
            thread_name: me.name().unwrap_or("?").to_string(),
            since: Instant::now(),
        }));
        TrackedGuard { guard, slot: self.slot }
    }
}

pub struct TrackedGuard<'a, T> {
    guard: MutexGuard<'a, T>,
    slot: &'static Slot,
}

impl<T> std::ops::Deref for TrackedGuard<'_, T> {
    type Target = T;
    fn deref(&self) -> &T { &self.guard }
}

impl<T> std::ops::DerefMut for TrackedGuard<'_, T> {
    fn deref_mut(&mut self) -> &mut T { &mut self.guard }
}

impl<T> Drop for TrackedGuard<'_, T> {
    fn drop(&mut self) { self.slot.set(None); }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn same_thread_relock_is_an_error_not_a_hang() {
        let m = TrackedMutex::new("t_reentry", 0u32);
        let _g = m.lock().unwrap();
        assert!(matches!(m.lock(), Err(LockError::Reentrant { .. })));
    }

    #[test]
    fn holder_is_reported_and_cleared() {
        let m = TrackedMutex::new("t_report", 0u32);
        {
            let _g = m.lock().unwrap();
            assert!(report().contains("t_report"));
        }
        assert!(!report().contains("t_report"));
    }

    #[test]
    fn other_thread_waits_normally() {
        let m = Arc::new(TrackedMutex::new("t_wait", 0u32));
        let g = m.lock().unwrap();
        let m2 = m.clone();
        let h = std::thread::spawn(move || { *m2.lock().unwrap() += 1; });
        std::thread::sleep(Duration::from_millis(50));
        drop(g);
        h.join().unwrap();
        assert_eq!(*m.lock().unwrap(), 1);
    }
}
