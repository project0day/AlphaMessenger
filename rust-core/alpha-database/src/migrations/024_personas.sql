-- Personas: who we are in an anonymous channel.
--
-- In a channel that hides its admins we never act under our own identity.
-- The owner and every admin act under a persona that exists for that one
-- channel only: its own Nostr key (inbox for join requests, directory address,
-- sender of envelopes) and its own MLS signing key (our leaf there). Nothing
-- in the channel links back to the account. The keys never leave this
-- encrypted database.
CREATE TABLE IF NOT EXISTS personas (
    chat_id    TEXT PRIMARY KEY,
    nostr_sk   TEXT NOT NULL,
    nostr_pk   TEXT NOT NULL UNIQUE,
    -- Serialized MLS signer + credential.
    mls        TEXT NOT NULL,
    -- 0 while an admin's persona waits for its Welcome into the channel.
    joined     INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);
