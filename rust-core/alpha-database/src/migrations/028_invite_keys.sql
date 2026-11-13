-- Reserved. Applied on test devices by an invite-key experiment that was
-- withdrawn; the tables are unused. Kept so the version number is never reused
-- (a database already at 28 would skip a different migration 28).
CREATE TABLE IF NOT EXISTS invite_keys (
    id         TEXT PRIMARY KEY,
    nostr_sk   TEXT NOT NULL,
    nostr_pk   TEXT NOT NULL UNIQUE,
    mls        TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    retired_at INTEGER
);

CREATE TABLE IF NOT EXISTS invite_chats (
    chat_id   TEXT PRIMARY KEY,
    invite_id TEXT NOT NULL REFERENCES invite_keys(id)
);
