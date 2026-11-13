-- Messages sent without sound: the notification for them stays quiet.
ALTER TABLE messages ADD COLUMN silent INTEGER NOT NULL DEFAULT 0;
