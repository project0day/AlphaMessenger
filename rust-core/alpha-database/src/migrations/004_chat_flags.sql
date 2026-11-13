-- Chat-list UX flags (Этап 8.4 / 8.5).
-- Local-only: pin, mute, archive, mark-as-unread. These never go on the wire
-- — they're per-device preferences. SQLCipher encrypts them on disk anyway.
ALTER TABLE chats ADD COLUMN pinned_at INTEGER;       -- unix ts when pinned, NULL = not pinned
ALTER TABLE chats ADD COLUMN muted_until INTEGER;     -- unix ts when mute expires, NULL = not muted, MAX_INT = forever
ALTER TABLE chats ADD COLUMN archived_at INTEGER;     -- unix ts when archived, NULL = not archived
ALTER TABLE chats ADD COLUMN unread_override INTEGER NOT NULL DEFAULT 0;  -- 1 = user marked unread manually

CREATE INDEX IF NOT EXISTS chats_pinned_at_idx ON chats(pinned_at) WHERE pinned_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS chats_archived_at_idx ON chats(archived_at);
