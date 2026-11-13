-- Whispers: messages read only under the finger, within a time limit, then burned.
--
-- The message itself is an ordinary row of `messages` marked one-view
-- (expiry_policy = 'view_once', ttl_seconds = the reading limit), with no
-- content and no media ref: the chat list, search and backups never see the
-- secret. The secret (incoming only) waits HERE, inside the encrypted database,
-- until it is opened; burning wipes it and keeps a tombstone.
CREATE TABLE IF NOT EXISTS whispers (
    message_id TEXT PRIMARY KEY,
    chat_id    TEXT NOT NULL,
    -- The sender's message id (what a "burned" notice names).
    wire_id    TEXT NOT NULL,
    outgoing   INTEGER NOT NULL DEFAULT 0,
    -- "text" or the media type ("image", "video").
    kind       TEXT NOT NULL,
    body       TEXT,
    media_ref  TEXT,
    secs       INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    burned_at  TEXT,
    used_secs  INTEGER,
    shot       INTEGER NOT NULL DEFAULT 0,
    -- Never opened in time: burned unread.
    expired    INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_whispers_chat ON whispers(chat_id);
CREATE INDEX IF NOT EXISTS idx_whispers_wire ON whispers(chat_id, wire_id);

-- A message that is gone (deleted chat, cleared history) takes its whisper along.
CREATE TRIGGER IF NOT EXISTS whispers_follow_messages
AFTER DELETE ON messages
BEGIN
    DELETE FROM whispers WHERE message_id = OLD.id;
END;
