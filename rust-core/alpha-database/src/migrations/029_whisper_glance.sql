-- A timed view: the whole picture shown for a few seconds (0 = until closed),
-- instead of a whisper read under the finger.
ALTER TABLE whispers ADD COLUMN glance INTEGER NOT NULL DEFAULT 0;
