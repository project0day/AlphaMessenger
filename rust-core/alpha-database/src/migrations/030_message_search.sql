-- Full-text search over what people wrote. Words match from their start, in any
-- case and script. Only the index is stored: the text itself stays in messages.
-- Service notes (timer, secret, story, sticker marks) and view-once / whisper
-- messages are never indexed; the forward mark is not part of the words.
CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
    body,
    content = '',
    contentless_delete = 1,
    tokenize = 'unicode61 remove_diacritics 2'
);

INSERT INTO message_fts(rowid, body)
SELECT rowid, replace(content, char(1) || 'FWD' || char(1), ' ')
FROM messages
WHERE is_deleted = 0 AND content IS NOT NULL AND content != ''
  AND msg_type IN ('text', 'reply', 'media', 'disappearing')
  AND COALESCE(expiry_policy, 'none') != 'view_once'
  AND instr(content, char(2)) = 0 AND instr(content, char(3)) = 0;

CREATE TRIGGER IF NOT EXISTS message_fts_ai AFTER INSERT ON messages
WHEN new.is_deleted = 0 AND new.content IS NOT NULL AND new.content != ''
  AND new.msg_type IN ('text', 'reply', 'media', 'disappearing')
  AND COALESCE(new.expiry_policy, 'none') != 'view_once'
  AND instr(new.content, char(2)) = 0 AND instr(new.content, char(3)) = 0
BEGIN
    INSERT INTO message_fts(rowid, body) VALUES (new.rowid, replace(new.content, char(1) || 'FWD' || char(1), ' '));
END;

CREATE TRIGGER IF NOT EXISTS message_fts_ad AFTER DELETE ON messages
BEGIN
    DELETE FROM message_fts WHERE rowid = old.rowid;
END;

-- An edit, a delete-for-everyone or a policy change re-files the message.
CREATE TRIGGER IF NOT EXISTS message_fts_au AFTER UPDATE OF content, is_deleted, expiry_policy, msg_type ON messages
BEGIN
    DELETE FROM message_fts WHERE rowid = old.rowid;
    INSERT INTO message_fts(rowid, body)
    SELECT new.rowid, replace(new.content, char(1) || 'FWD' || char(1), ' ')
    WHERE new.is_deleted = 0 AND new.content IS NOT NULL AND new.content != ''
      AND new.msg_type IN ('text', 'reply', 'media', 'disappearing')
      AND COALESCE(new.expiry_policy, 'none') != 'view_once'
      AND instr(new.content, char(2)) = 0 AND instr(new.content, char(3)) = 0;
END;
