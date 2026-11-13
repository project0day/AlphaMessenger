-- Migration 002: Ephemeral message support (STAGE D)
--
-- Adds columns for:
-- - expiry_policy: the mode ('none', 'after_send', 'after_read', 'view_once')
-- - read_at: when the recipient first opened the message (for AfterRead)
-- - consumed: whether a ViewOnce message has been viewed

-- Add expiry_policy column (NULL = no expiry, same as 'none')
ALTER TABLE messages ADD COLUMN expiry_policy TEXT
    CHECK(expiry_policy IS NULL OR expiry_policy IN ('none', 'after_send', 'after_read', 'view_once'));

-- Add read_at timestamp for AfterRead messages
ALTER TABLE messages ADD COLUMN read_at TEXT;

-- Add consumed flag for ViewOnce messages
ALTER TABLE messages ADD COLUMN consumed BOOLEAN NOT NULL DEFAULT 0;

-- Index for sweep: find expired messages efficiently
CREATE INDEX IF NOT EXISTS idx_messages_expiry_sweep
    ON messages(expires_at) WHERE expires_at IS NOT NULL;

-- Index for finding consumed ViewOnce messages that need cleanup
CREATE INDEX IF NOT EXISTS idx_messages_consumed
    ON messages(consumed) WHERE consumed = 1 AND expiry_policy = 'view_once';

-- Update the msg_type CHECK constraint to include 'read_signal'
-- SQLite doesn't support ALTER TABLE ... ALTER CONSTRAINT, so we recreate.
-- However, since this is a CHECK constraint on the table, and SQLite
-- doesn't allow altering CHECK constraints, we note that new messages
-- of type 'read_signal' will be handled by the application layer.
-- The read_signal messages are NOT persisted to the messages table
-- (they are control messages), so this constraint doesn't need updating.
