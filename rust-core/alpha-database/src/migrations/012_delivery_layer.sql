-- Phase 1: E2E Delivery Layer — seq numbers, ACK watermarks, gap tracking,
-- delivery outbox, per-recipient ACK state.
--
-- This makes message delivery server-grade over the Nostr fabric: a message
-- is "delivered" only after the recipient decrypts + persists + sends a
-- DeliveryAck, NOT when a relay accepts the publish. See AlphaMessage::seq /
-- DeliveryAck / ResendRequest in alpha-messages.
--
-- All tables are keyed by (chat_id, sender_id) where sender_id is the MLS leaf
-- signature key hex (stable per identity, available on both sides after
-- decryption). seq == 0 means "unsequenced" (legacy/control) and is excluded
-- from delivery tracking.

-- Sender's monotonic sequence counter per (chat, sender). Each sender
-- allocates its own seq stream — no coordination needed.
CREATE TABLE IF NOT EXISTS delivery_seq_counters (
    chat_id      TEXT NOT NULL,
    sender_id    TEXT NOT NULL,
    next_seq     INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (chat_id, sender_id)
);

-- Receiver's tracking of a sender's stream. highest_received_seq = highest
-- CONTIGUOUS seq decrypted+persisted from this sender (forward-only, never
-- decreased). highest_acked_seq = highest seq we've sent a DeliveryAck for
-- (forward-only, <= highest_received_seq). A gap exists when a higher seq
-- arrived out-of-order (stored in delivery_gaps + delivery_pending_seqs).
CREATE TABLE IF NOT EXISTS delivery_watermarks (
    chat_id              TEXT NOT NULL,
    sender_id            TEXT NOT NULL,
    highest_received_seq INTEGER NOT NULL DEFAULT 0,
    highest_acked_seq    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (chat_id, sender_id)
);

-- Open gaps: a hole in a sender's seq stream that we detected but haven't
-- filled. The recovery timer sends a ResendRequest for each open gap after a
-- short debounce. Closed automatically when the missing seqs arrive and the
-- contiguous watermark advances past them.
CREATE TABLE IF NOT EXISTS delivery_gaps (
    chat_id       TEXT NOT NULL,
    sender_id     TEXT NOT NULL,
    from_seq      INTEGER NOT NULL,
    to_seq        INTEGER NOT NULL,
    detected_at   INTEGER NOT NULL,
    requested_at  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (chat_id, sender_id, from_seq)
);
CREATE INDEX IF NOT EXISTS idx_delivery_gaps_chat ON delivery_gaps(chat_id, sender_id);

-- Pending-ahead seqs: seqs received OUT of order (higher than the contiguous
-- watermark) but persisted. These "fill in" the gap when the missing lower
-- seqs arrive, letting highest_received_seq jump forward contiguously.
-- Lightweight (just the seq number) — the message itself is in `messages`.
CREATE TABLE IF NOT EXISTS delivery_pending_seqs (
    chat_id    TEXT NOT NULL,
    sender_id  TEXT NOT NULL,
    seq        INTEGER NOT NULL,
    PRIMARY KEY (chat_id, sender_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_delivery_pending_chat ON delivery_pending_seqs(chat_id, sender_id);

-- Delivery outbox: the sender's per-message store. A row lives here from
-- compose until ALL current recipients ACK it (state='acked') or it expires
-- (state='failed'). On ACK the row is DELETED (the `messages` table keeps the
-- user-facing copy). On ResendRequest we pull the payload from here and
-- re-publish. `payload` is the serialized AlphaMessage WITH seq embedded.
CREATE TABLE IF NOT EXISTS delivery_outbox (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id       TEXT NOT NULL,
    msg_id        TEXT NOT NULL,
    seq           INTEGER NOT NULL,
    payload       BLOB NOT NULL,
    state         TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','acked','failed')),
    created_at    TEXT NOT NULL,
    last_sent_at  TEXT NOT NULL DEFAULT '',
    send_count    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_delivery_outbox_state ON delivery_outbox(state, last_sent_at);
CREATE INDEX IF NOT EXISTS idx_delivery_outbox_chat ON delivery_outbox(chat_id, seq);

-- Sender's per-recipient ACK tracking. Each recipient (identified by their
-- MLS sig key hex = acker_id) acks independently. A delivery_outbox row with
-- seq=S is fully ACKed when ALL current non-self group members have
-- highest_acked_seq >= S. For 1:1 (one member) it's just that one acker.
CREATE TABLE IF NOT EXISTS delivery_acks (
    chat_id            TEXT NOT NULL,
    acker_id           TEXT NOT NULL,
    highest_acked_seq  INTEGER NOT NULL DEFAULT 0,
    updated_at         TEXT NOT NULL,
    PRIMARY KEY (chat_id, acker_id)
);
