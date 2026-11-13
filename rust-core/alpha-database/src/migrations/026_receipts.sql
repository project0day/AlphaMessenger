-- Who has received our messages: one row per (message, member). A message is
-- "delivered" (two grey checks) once every other member of the chat is here.
CREATE TABLE IF NOT EXISTS receipts (
    message_id TEXT NOT NULL,
    member     TEXT NOT NULL,
    at         TEXT NOT NULL,
    PRIMARY KEY (message_id, member)
);
