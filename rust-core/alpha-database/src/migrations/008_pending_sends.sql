-- Defer-encryption outbox for OUTGOING messages.
--
-- When the user composes a message we store its PLAINTEXT payload here and then
-- try to flush. MLS encryption + relay publish happen at flush time — so when
-- offline we never encrypt (the ratchet isn't advanced for an undeliverable
-- message), and on reconnect messages are encrypted in order and sent exactly
-- as a normal live send would. This avoids the ratchet desync that re-sending
-- stored ciphertext caused.
--
-- `payload` is the serialized AlphaMessage (still plaintext — the DB file itself
-- is SQLCipher-encrypted at rest). `msg_id` is the stable message id (receiver
-- dedups by it, so retries are harmless).
CREATE TABLE IF NOT EXISTS pending_sends (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id TEXT NOT NULL,
    msg_id TEXT NOT NULL,
    payload BLOB NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pending_sends_id ON pending_sends(id);
