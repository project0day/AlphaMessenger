-- Split the two halves of a media send so a retry never redoes work that is
-- already done.
--
-- A send is really TWO operations against different systems:
--
--   1. put the encrypted blobs on Blossom   (content-addressed, idempotent)
--   2. publish the MLS/GiftWrap event       (needs a live relay)
--
-- They fail independently. In the field we lost the relay between (1) and (2),
-- and every retry redid BOTH: a 20 KB voice note was re-encrypted and
-- re-uploaded to Blossom every 12 seconds for hours, each attempt blocking a
-- runtime worker — which starved the relay's own reconnect, so step (2) could
-- never succeed. The queue was fighting itself.
--
-- Blossom is content-addressed: a blob that is already stored under its sha256
-- never needs to be uploaded again. So we remember that step (1) is done, and a
-- retry after a network drop performs step (2) ONLY.
--
--   blob_uploaded : 1 once the blobs are on Blossom and `media_ref` is valid
--   media_ref     : the MLS-facing media ref JSON produced by the upload — all
--                   that step (2) needs. NULL until the upload succeeds.
--   last_error    : the most recent failure, for the Inspector.
ALTER TABLE pending_media ADD COLUMN blob_uploaded INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pending_media ADD COLUMN media_ref TEXT;
ALTER TABLE pending_media ADD COLUMN last_error TEXT;
