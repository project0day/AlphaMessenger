-- Video messages (circles), GIFs, places and polls get kinds of their own; a
-- poll is found by its question and answers, a place by its name and address.
UPDATE messages SET media_kind = CASE
        WHEN messages.is_deleted != 0 OR instr(COALESCE(messages.content, ''), char(3)) > 0 THEN NULL
        WHEN messages.msg_type NOT IN ('text', 'reply', 'media', 'disappearing') OR COALESCE(messages.expiry_policy, 'none') = 'view_once' THEN NULL
        WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref) THEN CASE
            WHEN json_extract(messages.media_ref, '$.album') = 1 THEN 'media'
            WHEN json_extract(messages.media_ref, '$.poll') = 1 THEN 'poll'
            WHEN json_extract(messages.media_ref, '$.location') = 1 OR json_extract(messages.media_ref, '$.media_type') = 'location' THEN 'location'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 6) = 'round_' THEN 'round'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 4) = 'gif_' THEN 'gif'
            WHEN json_extract(messages.media_ref, '$.media_type') IN ('image', 'video') THEN 'media'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 5) = 'voice' THEN 'voice'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' THEN 'music'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('pdf', 'doc', 'docx', 'xls', 'xlsx', 'xlsm', 'csv', 'ppt', 'pptx', 'odt', 'ods', 'odp', 'rtf', 'txt', 'md', 'epub', 'fb2', 'djvu', 'pages', 'numbers', 'key') THEN 'doc'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('apk', 'apks', 'xapk', 'apkm', 'aab', 'ipa') THEN 'app'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'xz') THEN 'archive'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'file' THEN 'file'
            WHEN json_extract(messages.media_ref, '$.media_type') = 'link' THEN 'link'
        END
        WHEN messages.content LIKE '%http://%' OR messages.content LIKE '%https://%' OR messages.content LIKE '%www.%' THEN 'link'
    END,
    media_name = CASE
        WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref)
             AND json_extract(messages.media_ref, '$.media_type') IN ('file', 'audio', 'link', 'poll', 'location')
        THEN trim(COALESCE(json_extract(messages.media_ref, '$.title'), '') || ' ' ||
                  COALESCE(json_extract(messages.media_ref, '$.filename'), '') || ' ' ||
                  COALESCE(json_extract(messages.media_ref, '$.url'), '') || ' ' ||
                  COALESCE(json_extract(messages.media_ref, '$.q'), '') || ' ' ||
                  COALESCE(json_extract(messages.media_ref, '$.o'), '') || ' ' ||
                  COALESCE(json_extract(messages.media_ref, '$.name'), '') || ' ' ||
                  COALESCE(json_extract(messages.media_ref, '$.address'), ''))
    END
WHERE media_ref IS NOT NULL;

INSERT INTO message_fts(message_fts) VALUES ('delete-all');
INSERT INTO message_fts(rowid, body)
SELECT m.rowid, replace(COALESCE(m.content, ''), char(1) || 'FWD' || char(1), ' ') || ' ' || COALESCE(m.media_name, '') FROM messages m WHERE m.is_deleted = 0
      AND m.msg_type IN ('text', 'reply', 'media', 'disappearing')
      AND COALESCE(m.expiry_policy, 'none') != 'view_once'
      AND instr(COALESCE(m.content, ''), char(2)) = 0 AND instr(COALESCE(m.content, ''), char(3)) = 0
      AND (COALESCE(m.content, '') != '' OR COALESCE(m.media_name, '') != '');

DROP TRIGGER IF EXISTS message_fts_ai;
DROP TRIGGER IF EXISTS message_fts_au;

CREATE TRIGGER message_fts_ai AFTER INSERT ON messages
BEGIN
    UPDATE messages SET media_kind = CASE
            WHEN messages.is_deleted != 0 OR instr(COALESCE(messages.content, ''), char(3)) > 0 THEN NULL
            WHEN messages.msg_type NOT IN ('text', 'reply', 'media', 'disappearing') OR COALESCE(messages.expiry_policy, 'none') = 'view_once' THEN NULL
            WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref) THEN CASE
                WHEN json_extract(messages.media_ref, '$.album') = 1 THEN 'media'
                WHEN json_extract(messages.media_ref, '$.poll') = 1 THEN 'poll'
                WHEN json_extract(messages.media_ref, '$.location') = 1 OR json_extract(messages.media_ref, '$.media_type') = 'location' THEN 'location'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 6) = 'round_' THEN 'round'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 4) = 'gif_' THEN 'gif'
                WHEN json_extract(messages.media_ref, '$.media_type') IN ('image', 'video') THEN 'media'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 5) = 'voice' THEN 'voice'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' THEN 'music'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('pdf', 'doc', 'docx', 'xls', 'xlsx', 'xlsm', 'csv', 'ppt', 'pptx', 'odt', 'ods', 'odp', 'rtf', 'txt', 'md', 'epub', 'fb2', 'djvu', 'pages', 'numbers', 'key') THEN 'doc'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('apk', 'apks', 'xapk', 'apkm', 'aab', 'ipa') THEN 'app'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'xz') THEN 'archive'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' THEN 'file'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'link' THEN 'link'
            END
            WHEN messages.content LIKE '%http://%' OR messages.content LIKE '%https://%' OR messages.content LIKE '%www.%' THEN 'link'
        END,
        media_name = CASE
            WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref)
                 AND json_extract(messages.media_ref, '$.media_type') IN ('file', 'audio', 'link', 'poll', 'location')
            THEN trim(COALESCE(json_extract(messages.media_ref, '$.title'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.filename'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.url'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.q'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.o'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.name'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.address'), ''))
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

CREATE TRIGGER message_fts_au AFTER UPDATE OF content, media_ref, is_deleted, expiry_policy, msg_type ON messages
BEGIN
    UPDATE messages SET media_kind = CASE
            WHEN messages.is_deleted != 0 OR instr(COALESCE(messages.content, ''), char(3)) > 0 THEN NULL
            WHEN messages.msg_type NOT IN ('text', 'reply', 'media', 'disappearing') OR COALESCE(messages.expiry_policy, 'none') = 'view_once' THEN NULL
            WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref) THEN CASE
                WHEN json_extract(messages.media_ref, '$.album') = 1 THEN 'media'
                WHEN json_extract(messages.media_ref, '$.poll') = 1 THEN 'poll'
                WHEN json_extract(messages.media_ref, '$.location') = 1 OR json_extract(messages.media_ref, '$.media_type') = 'location' THEN 'location'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 6) = 'round_' THEN 'round'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'video' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 4) = 'gif_' THEN 'gif'
                WHEN json_extract(messages.media_ref, '$.media_type') IN ('image', 'video') THEN 'media'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' AND substr(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), 1, 5) = 'voice' THEN 'voice'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'audio' THEN 'music'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('pdf', 'doc', 'docx', 'xls', 'xlsx', 'xlsm', 'csv', 'ppt', 'pptx', 'odt', 'ods', 'odp', 'rtf', 'txt', 'md', 'epub', 'fb2', 'djvu', 'pages', 'numbers', 'key') THEN 'doc'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('apk', 'apks', 'xapk', 'apkm', 'aab', 'ipa') THEN 'app'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' AND lower(replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), rtrim(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), replace(COALESCE(json_extract(messages.media_ref, '$.filename'), ''), '.', '')), '')) IN ('zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'xz') THEN 'archive'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'file' THEN 'file'
                WHEN json_extract(messages.media_ref, '$.media_type') = 'link' THEN 'link'
            END
            WHEN messages.content LIKE '%http://%' OR messages.content LIKE '%https://%' OR messages.content LIKE '%www.%' THEN 'link'
        END,
        media_name = CASE
            WHEN messages.media_ref IS NOT NULL AND json_valid(messages.media_ref)
                 AND json_extract(messages.media_ref, '$.media_type') IN ('file', 'audio', 'link', 'poll', 'location')
            THEN trim(COALESCE(json_extract(messages.media_ref, '$.title'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.filename'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.url'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.q'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.o'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.name'), '') || ' ' ||
                      COALESCE(json_extract(messages.media_ref, '$.address'), ''))
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
