-- Upload state machine for the offline media queue.
--
-- Previously a failed upload (e.g. Blossom 415 Unsupported Media Type) left the
-- row untouched and `flush_pending_media` just `break`ed, so the entry was
-- retried forever and the UI span "sending…" indefinitely. We now track a real
-- lifecycle so a PERMANENT failure (4xx) parks the row as `failed` (kept, not
-- deleted, so Retry can re-queue it) while a TEMPORARY failure (offline/timeout/
-- 5xx/429) is retried later. Only a successful send deletes the row.
--
--   status         : 'queued' | 'uploading' | 'failed' | (row deleted on 'sent')
--   failure_reason : short slug of the last failure (e.g. 'unsupported_media',
--                    'offline', 'unauthorized', 'server', 'unknown') — nullable
--   attempts       : number of upload attempts so far
--   last_attempt   : RFC3339 timestamp of the last attempt (nullable)
--   next_retry     : RFC3339 timestamp of the earliest next retry (nullable)
ALTER TABLE pending_media ADD COLUMN status TEXT NOT NULL DEFAULT 'queued';
ALTER TABLE pending_media ADD COLUMN failure_reason TEXT;
ALTER TABLE pending_media ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pending_media ADD COLUMN last_attempt TEXT;
ALTER TABLE pending_media ADD COLUMN next_retry TEXT;
