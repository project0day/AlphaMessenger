-- Extended contact profile (Этап 8.8 Часть 2 — Profile propagation via MLS).
--
-- Stores the peer's cached profile data so we can render their display name,
-- avatar, and bio in chat bubbles, chat list rows, and the chat top bar.
--
-- Populated by the `process_application_message` handler when an
-- `AlphaMessage::ProfileUpdate` is received. Source of truth is the peer's
-- monotonic `profile_version` — we never overwrite with an older version
-- (replay protection).
ALTER TABLE contacts ADD COLUMN bio TEXT NOT NULL DEFAULT '';
ALTER TABLE contacts ADD COLUMN avatar_path TEXT NOT NULL DEFAULT '';
ALTER TABLE contacts ADD COLUMN avatar_hash TEXT NOT NULL DEFAULT '';
ALTER TABLE contacts ADD COLUMN profile_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contacts ADD COLUMN profile_updated_at TEXT;
