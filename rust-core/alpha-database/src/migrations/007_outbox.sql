-- Outbox: guaranteed-delivery queue for outgoing MLS application messages.
--
-- Every message is enqueued here right before it is published to the relays and
-- removed once a relay confirms acceptance. Anything still present (the app was
-- offline, the relay was down, the process was killed) is re-published by
-- `replay_outbox()` on the next reconnect / app start — so a "sent" message is
-- never silently lost.
--
-- We store the already-encrypted MLS envelope (group_id + epoch + ciphertext),
-- so re-publishing later is just re-broadcasting identical bytes; the receiver
-- decrypts regardless of arrival time and dedups by the stable message id.
CREATE TABLE IF NOT EXISTS outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id BLOB NOT NULL,
    epoch INTEGER NOT NULL,
    ciphertext BLOB NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_outbox_id ON outbox(id);
