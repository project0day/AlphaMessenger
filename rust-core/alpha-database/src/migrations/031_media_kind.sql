-- What a message carries (media, file, link, music, voice) and its name (file
-- name, link title), so the search finds files by name and lists each kind.
-- The index is rebuilt over the text and the name together.
ALTER TABLE messages ADD COLUMN media_kind TEXT;
ALTER TABLE messages ADD COLUMN media_name TEXT;

UPDATE messages SET media_kind = CASE
        WHEN messages.is_deleted != 0 OR instr(COALESCE(messages.content, ''), char(3)) > 0 THEN NULL
        WHEN messages.msg_type NOT IN ('text', 'reply', 'media', 'disappearing') OR COALESCE(messages.expiry_policy, 'none') = 'view_once' THEN NULL
        WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref) THEN CASE
            WHEN json_extract(messages.media_ref, '$.album') = 1 THEN 'media'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 6) = 'round_' THEN 'voice'
            WHEN json_extract(messages.media_ref, '$.media_type') IN ('image', 'video') THEN 'media'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 5) = 'voice' THEN 'voice'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' THEN 'music'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'file' THEN 'file'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'link' THEN 'link'
        END
        WHEN messages.content LIKE '%http://%' OR messages.content LIKE '%https://%' OR messages.content LIKE '%www.%' THEN 'link'
    END,
    media_name = CASE
        WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref)
             AND json_extract(messages.media_ref, '$.media_type') IN ('file', 'audio', 'link')
        THEN trim(COALESCE(json_extract(messages.media_ref, '$.title'), '') || ' ' ||
                  COALESCE(json_extract(messages.media_ref, '$.filename'), '') || ' ' ||
                  COALESCE(json_extract(messages.media_ref, '$.url'), ''))
    END;

CREATE INDEX IF NOT EXISTS idx_messages_kind ON messages(media_kind, created_at) WHERE media_kind IS NOT NULL;

DROP TRIGGER IF EXISTS message_fts_ai;
DROP TRIGGER IF EXISTS message_fts_au;

INSERT INTO message_fts(message_fts) VALUES ('delete-all');
INSERT INTO message_fts(rowid, body)
SELECT m.rowid, replace(COALESCE(m.content, ''), char(1) || 'FWD' || char(1), ' ') || ' ' || COALESCE(m.media_name, '') FROM messages m WHERE m.is_deleted = 0
      AND m.msg_type IN ('text', 'reply', 'media', 'disappearing')
      AND COALESCE(m.expiry_policy, 'none') != 'view_once'
      AND instr(COALESCE(m.content, ''), char(2)) = 0 AND instr(COALESCE(m.content, ''), char(3)) = 0
      AND (COALESCE(m.content, '') != '' OR COALESCE(m.media_name, '') != '');

CREATE TRIGGER message_fts_ai AFTER INSERT ON messages
BEGIN
    UPDATE messages SET media_kind = CASE
            WHEN messages.is_deleted != 0 OR instr(COALESCE(messages.content, ''), char(3)) > 0 THEN NULL
            WHEN messages.msg_type NOT IN ('text', 'reply', 'media', 'disappearing') OR COALESCE(messages.expiry_policy, 'none') = 'view_once' THEN NULL
            WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref) THEN CASE
                WHEN json_extract(messages.media_ref, '$.album') = 1 THEN 'media'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 6) = 'round_' THEN 'voice'
                WHEN json_extract(messages.media_ref, '$.media_type') IN ('image', 'video') THEN 'media'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 5) = 'voice' THEN 'voice'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' THEN 'music'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' THEN 'file'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'link' THEN 'link'
            END
            WHEN messages.content LIKE '%http://%' OR messages.content LIKE '%https://%' OR messages.content LIKE '%www.%' THEN 'link'
        END,
        media_name = CASE
            WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref)
                 AND json_extract(messages.media_ref, '$.media_type') IN ('file', 'audio', 'link')
            THEN trim(COALESCE(json_extract(messages.media_ref, '$.title'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.filename'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.url'), ''))
        END
    WHERE rowid = new.rowid;
    DELETE FROM message_fts WHERE rowid = new.rowid;
    INSERT INTO message_fts(rowid, body)
    SELECT m.rowid, replace(COALESCE(m.content, ''), char(1) || 'FWD' || char(1), ' ') || ' ' || COALESCE(m.media_name, '') FROM messages m
    WHERE m.rowid = new.rowid AND m.is_deleted = 0
          AND m.msg_type IN ('text', 'reply', 'media', 'disappearing')
          AND COALESCE(m.expiry_policy, 'none') != 'view_once'
          AND instr(COALESCE(m.content, ''), char(2)) = 0 AND instr(COALESCE(m.content, ''), char(3)) = 0
          AND (COALESCE(m.content, '') != '' OR COALESCE(m.media_name, '') != '');
END;

-- An edit, a delete for everyone, a finished upload or a policy change re-files it.
CREATE TRIGGER message_fts_au AFTER UPDATE OF content, media_ref, is_deleted, expiry_policy, msg_type ON messages
BEGIN
    UPDATE messages SET media_kind = CASE
            WHEN messages.is_deleted != 0 OR instr(COALESCE(messages.content, ''), char(3)) > 0 THEN NULL
            WHEN messages.msg_type NOT IN ('text', 'reply', 'media', 'disappearing') OR COALESCE(messages.expiry_policy, 'none') = 'view_once' THEN NULL
            WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref) THEN CASE
                WHEN json_extract(messages.media_ref, '$.album') = 1 THEN 'media'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 6) = 'round_' THEN 'voice'
                WHEN json_extract(messages.media_ref, '$.media_type') IN ('image', 'video') THEN 'media'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 5) = 'voice' THEN 'voice'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' THEN 'music'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' THEN 'file'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'link' THEN 'link'
            END
            WHEN messages.content LIKE '%http://%' OR messages.content LIKE '%https://%' OR messages.content LIKE '%www.%' THEN 'link'
        END,
        media_name = CASE
            WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref)
                 AND json_extract(messages.media_ref, '$.media_type') IN ('file', 'audio', 'link')
            THEN trim(COALESCE(json_extract(messages.media_ref, '$.title'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.filename'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.url'), ''))
        END
    WHERE rowid = new.rowid;
    DELETE FROM message_fts WHERE rowid = new.rowid;
    INSERT INTO message_fts(rowid, body)
    SELECT m.rowid, replace(COALESCE(m.content, ''), char(1) || 'FWD' || char(1), ' ') || ' ' || COALESCE(m.media_name, '') FROM messages m
    WHERE m.rowid = new.rowid AND m.is_deleted = 0
          AND m.msg_type IN ('text', 'reply', 'media', 'disappearing')
          AND COALESCE(m.expiry_policy, 'none') != 'view_once'
          AND instr(COALESCE(m.content, ''), char(2)) = 0 AND instr(COALESCE(m.content, ''), char(3)) = 0
          AND (COALESCE(m.content, '') != '' OR COALESCE(m.media_name, '') != '');
END;
