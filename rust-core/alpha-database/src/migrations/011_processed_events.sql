-- Processed-event dedup: remember which relay events we've already handled
-- (decrypted, or that failed with SecretReuseError = already decrypted earlier)
-- so the 300s catch-up backfill never re-decrypts them. Re-decrypting an
-- already-ratcheted message fails with SecretReuseError, and a forked/broken
-- group could flood every catch-up cycle with hundreds of such failures —
-- drowning real delivery and burning CPU. This table breaks that loop.

CREATE TABLE IF NOT EXISTS processed_events (
    event_id     TEXT PRIMARY KEY,
    processed_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_processed_events_at ON processed_events(processed_at);
