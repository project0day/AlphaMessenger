-- Offline media send queue. Media composed offline (photo/video/voice/file/
-- album) is persisted locally (bubble renders from the local file) and queued
-- here. On reconnect `flush_pending_media` uploads the blobs to Blossom and
-- broadcasts the message — mirroring `pending_sends` but for media.
--
-- `kind` = "single" | "album". `payload` is JSON:
--   single: {"file_path","media_type","caption","poster_b64","streaming"}
--   album:  {"caption","items":[{"local_path","media_type","poster_b64"}]}
CREATE TABLE IF NOT EXISTS pending_media (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id TEXT NOT NULL,
    msg_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pending_media_id ON pending_media(id);
CREATE INDEX IF NOT EXISTS idx_pending_media_msg ON pending_media(msg_id);
