-- Polls: one row per (poll, voter) — the voter's current choice (a new vote
-- replaces the old one; no row = no vote) — and the polls that were closed.
CREATE TABLE IF NOT EXISTS poll_votes (
    chat_id  TEXT NOT NULL,
    poll_id  TEXT NOT NULL,
    voter    TEXT NOT NULL,
    options  TEXT NOT NULL,
    at       INTEGER NOT NULL,
    PRIMARY KEY (chat_id, poll_id, voter)
);
CREATE TABLE IF NOT EXISTS poll_closed (
    chat_id  TEXT NOT NULL,
    poll_id  TEXT NOT NULL,
    at       INTEGER NOT NULL,
    tally    TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (chat_id, poll_id)
);
