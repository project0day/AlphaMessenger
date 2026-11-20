//! Presence engine — real online / away / offline + manual Busy + custom text
//! status + `last_seen`, with anti-fake protection. Pure, transport-agnostic
//! core. It does NOT touch Nostr / MLS / chat / groups / recovery / catch-up —
//! presence is a fully independent service.
//!
//! The wire protocol is specified in `PRESENCE_PROTOCOL.md` (repo root). In
//! short, each heartbeat carries: `v` (protocol version), `device_id`, `seq`
//! (per-device monotonic), `ts` (unix secs), and manual fields (`busy`,
//! `custom`, `color`).
//!
//! # Rules
//!  - **Presence** (`Online / Away / Offline`) is derived from how long ago we
//!    RECEIVED the newest heartbeat (LOCAL clock — can't be faked by a peer).
//!  - **Manual Busy** is set ONLY by the user, never inferred from heartbeats,
//!    and stored separately from presence. Busy overlays Online/Away but not
//!    Offline.
//!  - **Custom text status** (+ colour) is manual and never affects presence.
//!  - **Ordering** is by per-device `seq` (primary); `ts` is used ONLY for the
//!    displayed `last_seen` + a future/TTL sanity gate. Duplicates
//!    `(device_id, seq)` are dropped.
//!  - **TTL**: heartbeats whose `ts` is older than [`PRESENCE_TTL_SECS`] are
//!    ignored (a relay replaying an ancient beat can't resurrect presence).
//!  - **Version**: updates with a MAJOR version newer than ours are ignored
//!    (forward-compatible — old clients don't choke on new formats).
//!  - **Multi-device**: presence is the best across all of a user's devices.
//!  - Live state is in MEMORY only; persisting `last_seen` is the caller's job
//!    and must NOT happen on every heartbeat.

use std::collections::HashMap;

/// Presence protocol version we speak. Bump on breaking wire changes.
pub const PROTOCOL_VERSION: u32 = 1;
/// Heartbeats with `ts` older than this (secs) are ignored on receipt.
pub const PRESENCE_TTL_SECS: i64 = 10 * 60;

const ONLINE_WINDOW_SECS: i64 = 60;
const AWAY_WINDOW_SECS: i64 = 5 * 60;
const MAX_FUTURE_SKEW_SECS: i64 = 120;
/// Foreground heartbeat cadence (base + up to 10s jitter → ~27–37s).
const PING_FG_SECS: i64 = 27;
const PING_FG_JITTER_SECS: i64 = 10;
/// Background heartbeat cadence — much rarer to spare battery + relays.
const PING_BG_SECS: i64 = 180;
/// The very first heartbeat is delayed 2–5s after arming (avoid a startup
/// thundering-herd + a fixed "app just opened" fingerprint).
const STARTUP_DELAY_BASE_SECS: i64 = 2;
const STARTUP_DELAY_JITTER_SECS: i64 = 4; // 2..5
/// Bound per-user device state (multi-device is supported, but not unbounded).
const MAX_DEVICES_PER_USER: usize = 8;
/// Drop device state we haven't heard from in this long (housekeeping — keeps
/// the in-memory map from growing with dead/rotated devices).
const DEVICE_RETENTION_SECS: i64 = 24 * 60 * 60;

/// Presence computed purely from heartbeat freshness.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PresenceState {
    Online,
    Away,
    Offline,
}

/// What the UI renders — presence PLUS the manual Busy overlay.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EffectiveStatus {
    Online,
    Away,
    Busy,
    Offline,
}

impl EffectiveStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            EffectiveStatus::Online => "online",
            EffectiveStatus::Away => "away",
            EffectiveStatus::Busy => "busy",
            EffectiveStatus::Offline => "offline",
        }
    }
}

/// Run mode reported by the platform layer (Kotlin), so the engine stays free
/// of any Android specifics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PresenceMode {
    Foreground,
    Background,
    Stopped,
}

/// Per-device heartbeat state.
#[derive(Debug, Clone)]
struct DevicePresence {
    last_seq: u64,
    /// LOCAL receive time (our clock) — used for the presence age.
    last_recv: i64,
    /// Peer-claimed `ts` — used for `last_seen` display only.
    last_ts: i64,
    /// The same two for the last beat sent with the app on screen: only those
    /// make someone online and set their last seen. A beat from the background
    /// says the device is reachable, not that its owner is there.
    last_fg_recv: i64,
    last_fg_ts: i64,
    /// Local receive time of the last background beat: newer than the last
    /// foreground one = the app has just left the screen.
    last_bg_recv: i64,
    /// Feature flags this device advertised (e.g. "voice", "video", "calls").
    /// Empty today; lets the UI show "available for calls" without a format
    /// change later.
    capabilities: Vec<String>,
}

/// A user's aggregated presence + manual status.
#[derive(Debug, Clone, Default)]
struct UserPresence {
    devices: HashMap<String, DevicePresence>,
    manual_busy: bool,
    custom_status: Option<String>,
    status_color: Option<u32>,
    /// ts of the update that last set the manual/custom fields (newest wins).
    manual_ts: i64,
    /// Newest on-screen ts from a beat too old to count for presence.
    seen_ts: i64,
}

pub struct PresenceEngine {
    users: HashMap<String, UserPresence>,
    /// Last effective status we reported to the UI per user — so `poll_changes`
    /// only surfaces users whose *effective* status actually changed.
    last_reported: HashMap<String, EffectiveStatus>,
    last_ping_ts: i64,
    /// When heartbeats were armed (for the first-beat startup delay); 0 = not armed.
    armed_at: i64,
    // ── Our own broadcast state (the status WE publish) ─────────────────────
    my_busy: bool,
    my_custom: Option<String>,
    my_color: Option<u32>,
    my_mode: PresenceMode,
    my_seq: u64,
    /// Forces the next `should_ping` to fire so a manual status change is
    /// broadcast immediately instead of waiting for the cadence.
    pending_publish: bool,
}

impl Default for PresenceEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl PresenceEngine {
    pub fn new() -> Self {
        Self {
            users: HashMap::new(),
            last_reported: HashMap::new(),
            last_ping_ts: 0,
            armed_at: 0,
            my_busy: false,
            my_custom: None,
            my_color: None,
            my_mode: PresenceMode::Stopped, // no heartbeat until the platform says otherwise
            my_seq: 0,
            pending_publish: false,
        }
    }

    // ── Our own status (what we broadcast) ──────────────────────────────────

    /// Set our manual status AUTHORITATIVELY — the UI always passes the full
    /// desired state, so `custom == Some("")`/`None` clears the text and
    /// `color == None` clears the colour (fixes "old colour hangs" when the
    /// user resets to plain online). Also flags an immediate re-publish so the
    /// change propagates in seconds, not on the next cadence tick.
    pub fn set_my_status(&mut self, busy: bool, custom: Option<String>, color: Option<u32>) {
        self.my_busy = busy;
        self.my_custom = custom.and_then(|s| if s.trim().is_empty() { None } else { Some(s) });
        self.my_color = color;
        self.pending_publish = true;
    }

    pub fn my_busy(&self) -> bool { self.my_busy }
    pub fn my_custom(&self) -> Option<String> { self.my_custom.clone() }
    pub fn my_color(&self) -> Option<u32> { self.my_color }

    /// Update the platform run mode (Foreground/Background/Stopped).
    /// Leaving the screen is said at once: the next tick sends one beat that
    /// ends "online" for contacts instead of letting it linger a minute.
    pub fn set_mode(&mut self, mode: PresenceMode) {
        if self.my_mode == PresenceMode::Foreground && mode != PresenceMode::Foreground {
            self.pending_publish = true;
        }
        self.my_mode = mode;
    }
    pub fn mode(&self) -> PresenceMode { self.my_mode }

    /// Next per-device sequence number for OUR outgoing heartbeat.
    pub fn next_seq(&mut self) -> u64 {
        self.my_seq += 1;
        self.my_seq
    }

    /// Ingest a received presence heartbeat.
    ///
    /// Guards (all must pass): known protocol version, non-empty ids, `ts`
    /// within `[now - TTL, now + skew]`, and `seq` strictly newer than the last
    /// for this device. Returns `true` if accepted (caller may then re-evaluate
    /// [`effective_status`] and fire a change callback).
    #[allow(clippy::too_many_arguments)]
    pub fn ingest_update(
        &mut self,
        version: u32,
        user_id: &str,
        device_id: &str,
        seq: u64,
        ts: i64,
        manual_busy: bool,
        custom_status: Option<String>,
        status_color: Option<u32>,
        capabilities: Vec<String>,
        now: i64,
    ) -> bool {
        self.ingest_beat(version, user_id, device_id, seq, ts, manual_busy, custom_status, status_color, capabilities, true, now)
    }

    /// [`Self::ingest_update`] for a beat that says whether the app was on screen.
    #[allow(clippy::too_many_arguments)]
    pub fn ingest_beat(
        &mut self,
        version: u32,
        user_id: &str,
        device_id: &str,
        seq: u64,
        ts: i64,
        manual_busy: bool,
        custom_status: Option<String>,
        status_color: Option<u32>,
        capabilities: Vec<String>,
        foreground: bool,
        now: i64,
    ) -> bool {
        // Forward-compat: ignore a strictly newer MAJOR protocol we don't grok.
        if version > PROTOCOL_VERSION {
            return false;
        }
        if user_id.is_empty() || device_id.is_empty() || ts <= 0 {
            return false;
        }
        if ts > now + MAX_FUTURE_SKEW_SECS {
            return false;
        }
        // Too old to say the peer is here now, still the latest we know of when they were.
        if now - ts > PRESENCE_TTL_SECS {
            if foreground {
                let u = self.users.entry(user_id.to_string()).or_default();
                u.seen_ts = u.seen_ts.max(ts);
            }
            return false;
        }
        let u = self.users.entry(user_id.to_string()).or_default();
        {
            let d = u.devices.entry(device_id.to_string()).or_insert(DevicePresence {
                last_seq: 0,
                last_recv: 0,
                last_ts: 0,
                last_fg_recv: 0,
                last_fg_ts: 0,
                last_bg_recv: 0,
                capabilities: Vec::new(),
            });
            // Accept when EITHER the seq advanced OR the wall-clock ts advanced.
            // A peer's seq lives in memory and resets to 0 on app restart /
            // reinstall, so a pure `seq <= last_seq` guard would permanently
            // reject every heartbeat after the peer restarted (seq going 50→1),
            // freezing them on a stale status. `ts` keeps moving forward across
            // restarts, so it rescues that case; together they still drop true
            // duplicates and reorders (both stale).
            if seq <= d.last_seq && ts <= d.last_ts {
                return false;
            }
            d.last_seq = seq;
            d.last_recv = now; // presence age is measured on OUR clock
            d.last_ts = ts;
            if foreground {
                d.last_fg_recv = now;
                d.last_fg_ts = ts;
            } else {
                d.last_bg_recv = now;
            }
            d.capabilities = capabilities;
        }
        // Housekeeping: drop long-dead devices + cap how many we keep per user.
        u.devices.retain(|_, d| now - d.last_recv <= DEVICE_RETENTION_SECS);
        if u.devices.len() > MAX_DEVICES_PER_USER {
            // Evict the oldest-seen devices down to the cap.
            let mut by_age: Vec<(String, i64)> =
                u.devices.iter().map(|(k, d)| (k.clone(), d.last_recv)).collect();
            by_age.sort_by_key(|(_, recv)| *recv);
            for (k, _) in by_age.into_iter().take(u.devices.len() - MAX_DEVICES_PER_USER) {
                u.devices.remove(&k);
            }
        }
        // Manual/custom fields are user-level; the newest update (by ts) wins.
        if ts >= u.manual_ts {
            u.manual_ts = ts;
            u.manual_busy = manual_busy;
            // Authoritative: the heartbeat always carries the full custom/colour
            // state, so an absent value CLEARS it (fixes a reset status/colour
            // staying stuck on the peer's screen).
            u.custom_status = custom_status.and_then(|s| if s.trim().is_empty() { None } else { Some(s) });
            u.status_color = status_color;
        }
        true
    }

    /// Computed presence aggregated across ALL of the user's devices.
    pub fn presence_state(&self, user_id: &str, now: i64) -> PresenceState {
        let u = match self.users.get(user_id) {
            Some(u) => u,
            None => return PresenceState::Offline,
        };
        let mut best = PresenceState::Offline;
        for d in u.devices.values() {
            let age = now - d.last_fg_recv;
            let s = if d.last_fg_recv == 0 || age < 0 || age >= AWAY_WINDOW_SECS {
                PresenceState::Offline
            } else if age < ONLINE_WINDOW_SECS && d.last_fg_recv >= d.last_bg_recv {
                PresenceState::Online
            } else {
                PresenceState::Away
            };
            best = match (best, s) {
                (PresenceState::Online, _) | (_, PresenceState::Online) => PresenceState::Online,
                (PresenceState::Away, _) | (_, PresenceState::Away) => PresenceState::Away,
                _ => PresenceState::Offline,
            };
        }
        best
    }

    /// The single status the UI renders (presence + manual Busy overlay).
    pub fn effective_status(&self, user_id: &str, now: i64) -> EffectiveStatus {
        match self.presence_state(user_id, now) {
            PresenceState::Offline => EffectiveStatus::Offline,
            PresenceState::Away => {
                if self.manual_busy(user_id) { EffectiveStatus::Busy } else { EffectiveStatus::Away }
            }
            PresenceState::Online => {
                if self.manual_busy(user_id) { EffectiveStatus::Busy } else { EffectiveStatus::Online }
            }
        }
    }

    /// Users who are online or away right now.
    pub fn online_users(&self, now: i64) -> Vec<String> {
        self.users.keys()
            .filter(|u| self.presence_state(u, now) != PresenceState::Offline)
            .cloned()
            .collect()
    }

    /// `last_seen` unix-secs = newest peer-claimed on-screen heartbeat `ts`, stale ones included.
    pub fn last_seen(&self, user_id: &str) -> i64 {
        self.users
            .get(user_id)
            .map(|u| u.devices.values().map(|d| d.last_fg_ts).max().unwrap_or(0).max(u.seen_ts))
            .unwrap_or(0)
    }

    pub fn manual_busy(&self, user_id: &str) -> bool {
        self.users.get(user_id).map(|u| u.manual_busy).unwrap_or(false)
    }

    pub fn custom_status(&self, user_id: &str) -> Option<String> {
        self.users.get(user_id).and_then(|u| u.custom_status.clone())
    }

    pub fn status_color(&self, user_id: &str) -> Option<u32> {
        self.users.get(user_id).and_then(|u| u.status_color)
    }

    /// Union of capabilities across the user's REACHABLE devices (Online/Away).
    /// Offline devices contribute nothing — you can't call an offline device.
    /// Deduped + sorted for a stable result.
    pub fn capabilities(&self, user_id: &str, now: i64) -> Vec<String> {
        let u = match self.users.get(user_id) {
            Some(u) => u,
            None => return Vec::new(),
        };
        let mut caps: Vec<String> = u
            .devices
            .values()
            .filter(|d| {
                let age = now - d.last_recv;
                age >= 0 && age < AWAY_WINDOW_SECS
            })
            .flat_map(|d| d.capabilities.iter().cloned())
            .collect();
        caps.sort();
        caps.dedup();
        caps
    }

    /// Return users whose EFFECTIVE status changed since the last call (covers
    /// both freshly-ingested updates and time-based Online→Away→Offline decay).
    /// The UI is updated only from these deltas — never by polling every peer.
    pub fn poll_changes(&mut self, now: i64) -> Vec<(String, EffectiveStatus)> {
        let ids: Vec<String> = self.users.keys().cloned().collect();
        let mut changes = Vec::new();
        for id in ids {
            let cur = self.effective_status(&id, now);
            if self.last_reported.get(&id).copied() != Some(cur) {
                self.last_reported.insert(id.clone(), cur);
                changes.push((id, cur));
            }
        }
        changes
    }

    // ── Self heartbeat cadence (driven by PresenceMode) ─────────────────────

    /// Arm the heartbeat scheduler (call once when sync starts). The first beat
    /// then fires 2–5s later, not instantly.
    pub fn arm(&mut self, now: i64) {
        self.armed_at = now;
        self.last_ping_ts = 0;
    }

    /// Whether we should emit a heartbeat now. Stopped → never. Foreground →
    /// ~27–37s. Background → ~3 min. First beat is delayed 2–5s after `arm`.
    pub fn should_ping(&self, mode: PresenceMode, now: i64) -> bool {
        if self.armed_at == 0 {
            return false;
        }
        // A manual status change, or leaving the screen, publishes right away.
        if self.pending_publish {
            return true;
        }
        if matches!(mode, PresenceMode::Stopped) {
            return false;
        }
        if self.last_ping_ts == 0 {
            let delay = STARTUP_DELAY_BASE_SECS + self.armed_at.rem_euclid(STARTUP_DELAY_JITTER_SECS + 1);
            return now - self.armed_at >= delay;
        }
        let interval = match mode {
            PresenceMode::Foreground => PING_FG_SECS + now.rem_euclid(PING_FG_JITTER_SECS + 1),
            PresenceMode::Background => PING_BG_SECS,
            PresenceMode::Stopped => return false,
        };
        now - self.last_ping_ts >= interval
    }

    pub fn mark_pinged(&mut self, now: i64) {
        self.last_ping_ts = now;
        self.pending_publish = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const V: u32 = PROTOCOL_VERSION;

    fn ok(e: &mut PresenceEngine, u: &str, dev: &str, seq: u64, ts: i64, now: i64) -> bool {
        e.ingest_update(V, u, dev, seq, ts, false, None, None, Vec::new(), now)
    }

    #[test]
    fn a_beat_too_old_for_presence_still_tells_when_they_were_here() {
        let mut e = PresenceEngine::new();
        let now = 100_000;
        assert!(!ok(&mut e, "a", "d1", 1, now - 3 * 3600, now));
        assert_eq!(e.effective_status("a", now), EffectiveStatus::Offline);
        assert_eq!(e.last_seen("a"), now - 3 * 3600);
    }

    #[test]
    fn a_background_beat_is_not_online() {
        let mut e = PresenceEngine::new();
        assert!(e.ingest_beat(V, "a", "d1", 1, 1000, false, None, None, vec!["voice".into()], false, 1000));
        assert_eq!(e.effective_status("a", 1010), EffectiveStatus::Offline);
        assert_eq!(e.last_seen("a"), 0, "the app was not open");
        assert_eq!(e.capabilities("a", 1010), vec!["voice".to_string()], "still reachable");
        assert!(e.ingest_beat(V, "a", "d1", 2, 1020, false, None, None, Vec::new(), true, 1020));
        assert_eq!(e.effective_status("a", 1030), EffectiveStatus::Online);
        assert!(e.ingest_beat(V, "a", "d1", 3, 1200, false, None, None, Vec::new(), false, 1200));
        assert_eq!(e.effective_status("a", 1200), EffectiveStatus::Away, "went to the background");
        assert_eq!(e.last_seen("a"), 1020);
    }

    #[test]
    fn leaving_the_screen_ends_online_at_once() {
        let mut e = PresenceEngine::new();
        e.ingest_beat(V, "a", "d1", 1, 1000, false, None, None, Vec::new(), true, 1000);
        e.ingest_beat(V, "a", "d1", 2, 1005, false, None, None, Vec::new(), false, 1005);
        assert_eq!(e.effective_status("a", 1006), EffectiveStatus::Away);
        let mut me = PresenceEngine::new();
        me.arm(1);
        me.set_mode(PresenceMode::Foreground);
        me.mark_pinged(100);
        me.set_mode(PresenceMode::Stopped);
        assert!(me.should_ping(PresenceMode::Stopped, 101), "one beat on the way out");
        me.mark_pinged(101);
        assert!(!me.should_ping(PresenceMode::Stopped, 500));
    }

    #[test]
    fn fresh_is_online() {
        let mut e = PresenceEngine::new();
        assert!(ok(&mut e, "a", "d1", 1, 1000, 1000));
        assert_eq!(e.effective_status("a", 1010), EffectiveStatus::Online);
    }

    #[test]
    fn busy_is_manual_only() {
        let mut e = PresenceEngine::new();
        ok(&mut e, "a", "d1", 1, 1000, 1000);
        assert_eq!(e.effective_status("a", 1030), EffectiveStatus::Online);
        e.ingest_update(V, "a", "d1", 2, 1031, true, None, None, Vec::new(), 1031);
        assert_eq!(e.effective_status("a", 1040), EffectiveStatus::Busy);
    }

    #[test]
    fn busy_survives_away_not_offline() {
        let mut e = PresenceEngine::new();
        e.ingest_update(V, "a", "d1", 1, 1000, true, None, None, Vec::new(), 1000);
        assert_eq!(e.effective_status("a", 1000 + 180), EffectiveStatus::Busy);
        assert_eq!(e.effective_status("a", 1000 + 360), EffectiveStatus::Offline);
    }

    #[test]
    fn ages_online_away_offline() {
        let mut e = PresenceEngine::new();
        ok(&mut e, "a", "d1", 1, 1000, 1000);
        assert_eq!(e.presence_state("a", 1030), PresenceState::Online);
        assert_eq!(e.presence_state("a", 1090), PresenceState::Away);
        assert_eq!(e.presence_state("a", 1360), PresenceState::Offline);
    }

    #[test]
    fn seq_dedup_and_reorder_rejected() {
        let mut e = PresenceEngine::new();
        assert!(ok(&mut e, "a", "d1", 107, 2000, 2000));
        assert!(!ok(&mut e, "a", "d1", 106, 1999, 2001)); // older seq and ts
        assert!(!ok(&mut e, "a", "d1", 107, 2000, 2002)); // duplicate
        assert!(ok(&mut e, "a", "d1", 108, 2003, 2003));  // newer
        assert!(ok(&mut e, "a", "d1", 1, 2004, 2004));    // peer restarted: seq reset, ts advanced
    }

    #[test]
    fn ttl_and_future_rejected() {
        let mut e = PresenceEngine::new();
        assert!(!ok(&mut e, "a", "d1", 1, 100, 1000));        // ts 900s old > TTL(600)
        assert!(!ok(&mut e, "a", "d1", 1, 99_999, 1000));     // far future
        assert_eq!(e.presence_state("a", 1000), PresenceState::Offline);
    }

    #[test]
    fn newer_protocol_version_ignored() {
        let mut e = PresenceEngine::new();
        assert!(!e.ingest_update(V + 1, "a", "d1", 1, 1000, false, None, None, Vec::new(), 1000));
        assert_eq!(e.presence_state("a", 1000), PresenceState::Offline);
    }

    #[test]
    fn presence_age_uses_local_clock() {
        // Peer sends an in-window (but past) ts; presence age is measured on our
        // clock (now at receipt), not the peer ts.
        let mut e = PresenceEngine::new();
        ok(&mut e, "a", "d1", 1, 940, 1000); // ts 60s old (within TTL), recv now=1000
        assert_eq!(e.presence_state("a", 1030), PresenceState::Online); // 30s since receipt
        assert_eq!(e.last_seen("a"), 940); // last_seen = peer ts
    }

    #[test]
    fn multi_device_online_if_any() {
        let mut e = PresenceEngine::new();
        ok(&mut e, "a", "phone", 1, 1000, 1000);
        ok(&mut e, "a", "desktop", 1, 1350, 1350);
        assert_eq!(e.presence_state("a", 1360), PresenceState::Online);
        assert_eq!(e.last_seen("a"), 1350);
    }

    #[test]
    fn custom_status_carries_and_persists() {
        let mut e = PresenceEngine::new();
        e.ingest_update(V, "a", "d1", 1, 1000, false, Some("пью кофе ☕".into()), Some(0xFF8800FF), Vec::new(), 1000);
        assert_eq!(e.custom_status("a").as_deref(), Some("пью кофе ☕"));
        assert_eq!(e.status_color("a"), Some(0xFF8800FF));
        e.ingest_update(V, "a", "d1", 2, 1030, false, Some("пью кофе ☕".into()), Some(0xFF8800FF), Vec::new(), 1030);
        assert_eq!(e.custom_status("a").as_deref(), Some("пью кофе ☕"));
        // Every heartbeat carries the full state: an absent status clears it.
        ok(&mut e, "a", "d1", 3, 1060, 1060);
        assert_eq!(e.custom_status("a"), None);
        assert_eq!(e.status_color("a"), None);
    }

    #[test]
    fn heartbeat_startup_delay_and_cadence() {
        let mut e = PresenceEngine::new();
        assert!(!e.should_ping(PresenceMode::Foreground, 1000)); // not armed
        e.arm(1000);
        assert!(!e.should_ping(PresenceMode::Foreground, 1001)); // 1s — startup delay
        assert!(e.should_ping(PresenceMode::Foreground, 1000 + 6)); // after 2–5s delay
        e.mark_pinged(1006);
        assert!(!e.should_ping(PresenceMode::Foreground, 1016));      // too soon
        assert!(e.should_ping(PresenceMode::Foreground, 1006 + 45));  // due
        assert!(!e.should_ping(PresenceMode::Stopped, 9_999_999));    // never
    }

    #[test]
    fn unknown_user_offline() {
        let e = PresenceEngine::new();
        assert_eq!(e.effective_status("nobody", 1000), EffectiveStatus::Offline);
        assert_eq!(e.last_seen("nobody"), 0);
    }

    #[test]
    fn capabilities_union_of_reachable_devices() {
        let mut e = PresenceEngine::new();
        e.ingest_update(V, "a", "phone", 1, 1000, false, None, None, vec!["voice".into(), "video".into()], 1000);
        e.ingest_update(V, "a", "desktop", 1, 1000, false, None, None, vec!["voice".into(), "calls".into()], 1000);
        assert_eq!(
            e.capabilities("a", 1010),
            vec!["calls".to_string(), "video".to_string(), "voice".to_string()],
        );
        // Once both devices decay to Offline, no capabilities are offered.
        assert!(e.capabilities("a", 1000 + 400).is_empty());
    }

    #[test]
    fn device_count_is_bounded() {
        let mut e = PresenceEngine::new();
        for i in 0..(MAX_DEVICES_PER_USER + 3) {
            let t = 2000 + i as i64;
            ok(&mut e, "a", &format!("dev{}", i), 1, t, t);
        }
        assert_eq!(e.users.get("a").unwrap().devices.len(), MAX_DEVICES_PER_USER);
    }
}
