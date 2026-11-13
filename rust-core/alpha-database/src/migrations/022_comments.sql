-- Comment threads under channel posts.
--
-- A comment is an ordinary message row (so reactions, edits, deletes, media,
-- delivery status and replies work exactly as in any chat); this table only
-- says which post it discusses. The channel feed is `messages` minus the rows
-- listed here; a discussion is the rows listed here for one post.
CREATE TABLE IF NOT EXISTS comments (
    message_id TEXT PRIMARY KEY,
    chat_id    TEXT NOT NULL,
    post_id    TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_comments_post ON comments(chat_id, post_id, created_at);

-- A message that is gone (deleted chat, expiry sweep, ...) leaves no comment behind.
CREATE TRIGGER IF NOT EXISTS comments_follow_messages
AFTER DELETE ON messages
BEGIN
    DELETE FROM comments WHERE message_id = OLD.id;
END;
