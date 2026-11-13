-- MLS persistent storage (Этап 2).
-- Snapshot of openmls_memory_storage::MemoryStorage flushed after every
-- mutating MLS operation so that groups survive process kill / swipe / OOM.
-- Single-row snapshot is good enough for MVP — incremental writes can come
-- later (one row per (label,key)) without a schema break.
CREATE TABLE IF NOT EXISTS mls_storage_v1 (
    k BLOB PRIMARY KEY,
    v BLOB NOT NULL
);
