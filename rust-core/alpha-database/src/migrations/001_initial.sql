-- Migration 001: Initial schema

CREATE TABLE IF NOT EXISTS contacts (
    id TEXT PRIMARY KEY,          -- Nostr npub
    display_name TEXT,
    nostr_pubkey BLOB NOT NULL,   -- raw 32-byte secp256k1 pubkey
    mls_credential BLOB,          -- serialized MLS credential (serde)
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY,          -- MLS GroupId hex
    chat_type TEXT NOT NULL CHECK(chat_type IN ('direct', 'group')),
    name TEXT,                    -- group name (NULL for 1:1)
    epoch INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_members (
    chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    contact_id TEXT NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    leaf_index INTEGER,           -- MLS leaf index
    joined_at TEXT NOT NULL,
    PRIMARY KEY (chat_id, contact_id)
);

CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,          -- UUID
    chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    sender_id TEXT NOT NULL REFERENCES contacts(id),
    msg_type TEXT NOT NULL CHECK(msg_type IN ('text', 'edit', 'reaction', 'reply', 'delete', 'media', 'disappearing')),
    content TEXT,                 -- message body (NULL for delete/tombstone)
    reply_to TEXT,                -- message ID being replied to
    media_ref TEXT,               -- reference to media attachment
    ttl_seconds INTEGER,          -- for disappearing messages
    expires_at TEXT,              -- computed expiry timestamp
    created_at TEXT NOT NULL,
    edited_at TEXT,
    -- Tombstone flag
    is_deleted BOOLEAN NOT NULL DEFAULT 0
);

CREATE INDEX idx_messages_chat_created ON messages(chat_id, created_at);
CREATE INDEX idx_messages_expires ON messages(expires_at) WHERE ttl_seconds IS NOT NULL;

CREATE TABLE IF NOT EXISTS mls_groups (
    group_id TEXT PRIMARY KEY,    -- hex-encoded MLS GroupId
    epoch INTEGER NOT NULL DEFAULT 0,
    ciphersuite INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS mls_key_packages (
    id TEXT PRIMARY KEY,
    hash_ref BLOB NOT NULL UNIQUE, -- KeyPackage hash reference
    key_package_data BLOB NOT NULL, -- TLS-serialized KeyPackage
    created_at TEXT NOT NULL,
    is_consumed BOOLEAN NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sync_checkpoints (
    relay_url TEXT NOT NULL,
    last_event_id TEXT,            -- Nostr event ID
    last_timestamp INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (relay_url)
);

CREATE TABLE IF NOT EXISTS key_value_store (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
