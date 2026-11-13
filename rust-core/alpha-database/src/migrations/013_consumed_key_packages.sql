-- Phase 3 fix: persist consumed (used) MLS KeyPackages so they survive restarts.
-- Without this, pick_distinct_kp lost its memory on restart and could reuse a
-- consumed KP → NoMatchingKeyPackage → group creation fails. Each KP is
-- one-time-use per the MLS spec; once consumed by an Add commit, reusing it
-- always fails.
CREATE TABLE IF NOT EXISTS consumed_key_packages (
    peer_hex   TEXT NOT NULL,
    kp_hash    BLOB NOT NULL,
    consumed_at TEXT NOT NULL,
    PRIMARY KEY (peer_hex, kp_hash)
);
CREATE INDEX IF NOT EXISTS idx_consumed_kp_peer ON consumed_key_packages(peer_hex);
