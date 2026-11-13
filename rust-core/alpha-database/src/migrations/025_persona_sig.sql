-- A persona's MLS leaf key (hex), kept beside its keys: "who am I in this
-- chat" is answered without unpacking the MLS credential.
ALTER TABLE personas ADD COLUMN sig TEXT NOT NULL DEFAULT '';
