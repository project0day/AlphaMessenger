-- Group metadata fields on the `chats` table (Этап 8.8 v3).
--
-- Used for group avatar / name / description propagation via
-- AlphaMessage::GroupMetadataUpdate. Direct chats reuse these same
-- columns for their peer-side name+avatar mirror so the UI never
-- needs to branch.
--
-- `metadata_version` is a monotonic counter so receivers can drop
-- stale updates (replay protection). Bumped by the sender every
-- time `set_group_metadata` ships an update.
ALTER TABLE chats ADD COLUMN avatar_path      TEXT    NOT NULL DEFAULT '';
ALTER TABLE chats ADD COLUMN avatar_hash      TEXT    NOT NULL DEFAULT '';
ALTER TABLE chats ADD COLUMN description      TEXT    NOT NULL DEFAULT '';
ALTER TABLE chats ADD COLUMN metadata_version INTEGER NOT NULL DEFAULT 0;
