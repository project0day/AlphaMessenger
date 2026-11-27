//! Repository pattern for CRUD operations on all database tables.
//!
//! Each repository is a stateless struct that accepts a `&rusqlite::Connection`
//! so callers control transaction boundaries. All methods propagate errors via
//! [`crate::error::DatabaseError`].

use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension};
use uuid::Uuid;

use crate::error::Result;
use crate::models::*;

// --- Helper ---

fn now_rfc3339() -> String {
    Utc::now().to_rfc3339()
}

/// Helper to read a Message row from a SELECT that includes all columns.
///
/// The SELECT must include columns in the following order:
/// `id, chat_id, sender_id, msg_type, content, reply_to, media_ref,
///  ttl_seconds, expires_at, created_at, edited_at, is_deleted,
///  expiry_policy, read_at, consumed, delivered_at`
fn read_message_row(row: &rusqlite::Row<'_>) -> std::result::Result<Message, rusqlite::Error> {
    let msg_type_str: String = row.get(3)?;
    let expiry_policy_str: Option<String> = row.get(12)?;
    Ok(Message {
        id: row.get(0)?,
        chat_id: row.get(1)?,
        sender_id: row.get(2)?,
        msg_type: MessageType::from_sql(&msg_type_str, 3)?,
        content: row.get(4)?,
        reply_to: row.get(5)?,
        media_ref: row.get(6)?,
        ttl_seconds: row.get(7)?,
        expires_at: row.get(8)?,
        created_at: row.get(9)?,
        edited_at: row.get(10)?,
        is_deleted: row.get(11)?,
        expiry_policy: expiry_policy_str
            .as_deref()
            .map(|s| ExpiryPolicyType::from_sql(s, 12))
            .transpose()
            .unwrap_or(None),
        read_at: row.get(13)?,
        consumed: row.get(14)?,
        delivered_at: row.get(15)?,
    })
}

/// Full column list for SELECT queries on the messages table.
const MESSAGE_COLUMNS: &str = "\
    id, chat_id, sender_id, msg_type, content, reply_to, media_ref, \
    ttl_seconds, expires_at, created_at, edited_at, is_deleted, \
    expiry_policy, read_at, consumed, delivered_at";

// --- ContactRepo ---

/// CRUD operations for the `contacts` table.
pub struct ContactRepo;

impl ContactRepo {
    const FULL_COLS: &'static str =
        "id, display_name, nostr_pubkey, mls_credential, created_at, updated_at, \
         bio, avatar_path, avatar_hash, profile_version, profile_updated_at";

    /// Insert a new contact.
    pub fn insert(conn: &Connection, contact: &Contact) -> Result<()> {
        conn.execute(
            "INSERT INTO contacts (id, display_name, nostr_pubkey, mls_credential, created_at, updated_at,
                                   bio, avatar_path, avatar_hash, profile_version, profile_updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            params![
                contact.id,
                contact.display_name,
                contact.nostr_pubkey,
                contact.mls_credential,
                contact.created_at,
                contact.updated_at,
                contact.bio,
                contact.avatar_path,
                contact.avatar_hash,
                contact.profile_version,
                contact.profile_updated_at,
            ],
        )?;
        Ok(())
    }

    fn row_to_contact(row: &rusqlite::Row<'_>) -> rusqlite::Result<Contact> {
        Ok(Contact {
            id: row.get(0)?,
            display_name: row.get(1)?,
            nostr_pubkey: row.get(2)?,
            mls_credential: row.get(3)?,
            created_at: row.get(4)?,
            updated_at: row.get(5)?,
            bio: row.get::<_, Option<String>>(6)?.unwrap_or_default(),
            avatar_path: row.get::<_, Option<String>>(7)?.unwrap_or_default(),
            avatar_hash: row.get::<_, Option<String>>(8)?.unwrap_or_default(),
            profile_version: row.get::<_, Option<i64>>(9)?.unwrap_or(0),
            profile_updated_at: row.get(10)?,
        })
    }

    /// Get a contact by ID (Nostr npub).
    pub fn get(conn: &Connection, id: &str) -> Result<Option<Contact>> {
        let sql = format!("SELECT {} FROM contacts WHERE id = ?1", Self::FULL_COLS);
        let mut stmt = conn.prepare(&sql)?;
        let result = stmt
            .query_row(params![id], Self::row_to_contact)
            .optional()?;
        Ok(result)
    }

    /// List all contacts ordered by display name.
    pub fn list(conn: &Connection) -> Result<Vec<Contact>> {
        let sql = format!(
            "SELECT {} FROM contacts ORDER BY display_name COLLATE NOCASE",
            Self::FULL_COLS,
        );
        let mut stmt = conn.prepare(&sql)?;
        let rows = stmt.query_map([], Self::row_to_contact)?;
        let mut contacts = Vec::new();
        for row in rows {
            contacts.push(row?);
        }
        Ok(contacts)
    }

    /// Update a contact's display name and/or MLS credential.
    pub fn update(
        conn: &Connection,
        id: &str,
        display_name: Option<&str>,
        mls_credential: Option<&[u8]>,
    ) -> Result<bool> {
        let now = now_rfc3339();
        let changes = conn.execute(
            "UPDATE contacts SET display_name = ?1, mls_credential = ?2, updated_at = ?3
             WHERE id = ?4",
            params![display_name, mls_credential, now, id],
        )?;
        Ok(changes > 0)
    }

    /// Apply an incoming `ProfileUpdate` to the contact row.
    ///
    /// - Inserts a placeholder contact row if none exists yet (with the
    ///   minimal `nostr_pubkey` provided), then updates the profile fields.
    /// - Returns `false` if `incoming_version <= profile_version` already
    ///   stored (replay/regression guard).
    /// - Returns `true` when the row was actually updated.
    #[allow(clippy::too_many_arguments)]
    /// Commit a peer's avatar AFTER a background blob download succeeded.
    /// No version guard — the profile metadata (name/bio/version) was already
    /// applied by `upsert_profile`; this only swaps in the freshly-downloaded
    /// image path + hash. No-op if the contact row doesn't exist.
    pub fn set_avatar(conn: &Connection, id: &str, avatar_path: &str, avatar_hash: &str) -> Result<()> {
        conn.execute(
            "UPDATE contacts SET avatar_path = ?2, avatar_hash = ?3, updated_at = ?4 WHERE id = ?1",
            params![id, avatar_path, avatar_hash, now_rfc3339()],
        )?;
        Ok(())
    }

    pub fn upsert_profile(
        conn: &Connection,
        id: &str,
        nostr_pubkey: &[u8],
        display_name: &str,
        bio: &str,
        avatar_path: &str,
        avatar_hash: &str,
        version: i64,
    ) -> Result<bool> {
        let now = now_rfc3339();

        // Replay guard: bail out if we already have a newer (or equal) version.
        let cur_ver: Option<i64> = conn
            .query_row(
                "SELECT profile_version FROM contacts WHERE id = ?1",
                params![id],
                |row| row.get(0),
            )
            .optional()?;
        if let Some(cur) = cur_ver {
            if version <= cur {
                return Ok(false);
            }
        }

        // Upsert: ensure the row exists, then UPDATE the profile fields.
        // Using ON CONFLICT(id) DO UPDATE lets us replace name/bio/avatar in
        // one statement without an explicit read.
        conn.execute(
            "INSERT INTO contacts (id, display_name, nostr_pubkey, mls_credential, created_at, updated_at,
                                   bio, avatar_path, avatar_hash, profile_version, profile_updated_at)
             VALUES (?1, ?2, ?3, NULL, ?4, ?4, ?5, ?6, ?7, ?8, ?4)
             ON CONFLICT(id) DO UPDATE SET
                 display_name        = excluded.display_name,
                 bio                 = excluded.bio,
                 avatar_path         = excluded.avatar_path,
                 avatar_hash         = excluded.avatar_hash,
                 profile_version     = excluded.profile_version,
                 profile_updated_at  = excluded.profile_updated_at,
                 updated_at          = excluded.updated_at",
            params![
                id,
                if display_name.is_empty() { None } else { Some(display_name) },
                nostr_pubkey,
                now,
                bio,
                avatar_path,
                avatar_hash,
                version,
            ],
        )?;
        Ok(true)
    }

    /// Delete a contact by ID.
    pub fn delete(conn: &Connection, id: &str) -> Result<bool> {
        let changes = conn.execute("DELETE FROM contacts WHERE id = ?1", params![id])?;
        Ok(changes > 0)
    }
}

// --- ChatRepo ---

/// CRUD operations for the `chats` and `chat_members` tables.
pub struct ChatRepo;

impl ChatRepo {
    /// Columns selected by `get`/`list` — kept in one place so adding a
    /// field doesn't drift the two queries apart.
    const FULL_COLS: &'static str =
        "id, chat_type, name, epoch, created_at, updated_at, \
         pinned_at, muted_until, archived_at, unread_override, \
         avatar_path, avatar_hash, description, metadata_version";

    fn row_to_chat(row: &rusqlite::Row<'_>) -> rusqlite::Result<Chat> {
        let chat_type_str: String = row.get(1)?;
        Ok(Chat {
            id: row.get(0)?,
            chat_type: ChatType::from_sql(&chat_type_str, 1)?,
            name: row.get(2)?,
            epoch: row.get(3)?,
            created_at: row.get(4)?,
            updated_at: row.get(5)?,
            pinned_at: row.get(6)?,
            muted_until: row.get(7)?,
            archived_at: row.get(8)?,
            unread_override: row.get::<_, i64>(9)? != 0,
            avatar_path: row.get::<_, Option<String>>(10)?.unwrap_or_default(),
            avatar_hash: row.get::<_, Option<String>>(11)?.unwrap_or_default(),
            description: row.get::<_, Option<String>>(12)?.unwrap_or_default(),
            metadata_version: row.get::<_, Option<i64>>(13)?.unwrap_or(0),
        })
    }

    /// Insert a new chat.
    pub fn insert(conn: &Connection, chat: &Chat) -> Result<()> {
        conn.execute(
            "INSERT INTO chats (id, chat_type, name, epoch, created_at, updated_at,
                                avatar_path, avatar_hash, description, metadata_version)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
            params![
                chat.id,
                chat.chat_type.as_str(),
                chat.name,
                chat.epoch,
                chat.created_at,
                chat.updated_at,
                chat.avatar_path,
                chat.avatar_hash,
                chat.description,
                chat.metadata_version,
            ],
        )?;
        Ok(())
    }

    /// Get a chat by ID.
    pub fn get(conn: &Connection, id: &str) -> Result<Option<Chat>> {
        let sql = format!("SELECT {} FROM chats WHERE id = ?1", Self::FULL_COLS);
        let mut stmt = conn.prepare(&sql)?;
        let result = stmt
            .query_row(params![id], Self::row_to_chat)
            .optional()?;
        Ok(result)
    }

    /// List all chats. Pinned chats first (most-recent pin on top), then
    /// non-archived ordered by `updated_at DESC`. Archived chats are at
    /// the very end (UI can hide them behind an "Archive" entry point).
    pub fn list(conn: &Connection) -> Result<Vec<Chat>> {
        let sql = format!(
            "SELECT {} FROM chats \
             ORDER BY archived_at IS NOT NULL ASC, \
                      pinned_at IS NULL ASC, \
                      pinned_at DESC, \
                      updated_at DESC",
            Self::FULL_COLS,
        );
        let mut stmt = conn.prepare(&sql)?;
        let rows = stmt.query_map([], Self::row_to_chat)?;
        let mut chats = Vec::new();
        for row in rows {
            chats.push(row?);
        }
        Ok(chats)
    }

    /// Apply an incoming GroupMetadataUpdate or self-initiated metadata
    /// change to the chat row. Returns `false` if `incoming_version <=
    /// metadata_version` already stored (replay/regression guard).
    pub fn apply_metadata(
        conn: &Connection,
        id: &str,
        name: Option<&str>,
        description: Option<&str>,
        avatar_path: Option<&str>,
        avatar_hash: Option<&str>,
        version: i64,
    ) -> Result<bool> {
        let cur_ver: Option<i64> = conn
            .query_row(
                "SELECT metadata_version FROM chats WHERE id = ?1",
                params![id],
                |row| row.get(0),
            )
            .optional()?;
        if let Some(cur) = cur_ver {
            if version <= cur {
                return Ok(false);
            }
        } else {
            return Ok(false); // chat row missing
        }

        let now = now_rfc3339();
        let changed = conn.execute(
            "UPDATE chats SET
                name             = COALESCE(?1, name),
                description      = COALESCE(?2, description),
                avatar_path      = COALESCE(?3, avatar_path),
                avatar_hash      = COALESCE(?4, avatar_hash),
                metadata_version = ?5,
                updated_at       = ?6
             WHERE id = ?7",
            params![name, description, avatar_path, avatar_hash, version, now, id],
        )?;
        Ok(changed > 0)
    }

    // --- UX flags ---

    pub fn set_pinned(conn: &Connection, id: &str, pinned: bool) -> Result<()> {
        let now = if pinned { Some(chrono::Utc::now().timestamp()) } else { None };
        conn.execute("UPDATE chats SET pinned_at = ?1 WHERE id = ?2", params![now, id])?;
        Ok(())
    }

    /// `until` semantics: `None` → unmute; `Some(i64::MAX)` → mute forever;
    /// otherwise a unix timestamp when the mute auto-expires.
    pub fn set_muted(conn: &Connection, id: &str, until: Option<i64>) -> Result<()> {
        conn.execute("UPDATE chats SET muted_until = ?1 WHERE id = ?2", params![until, id])?;
        Ok(())
    }

    pub fn set_archived(conn: &Connection, id: &str, archived: bool) -> Result<()> {
        let now = if archived { Some(chrono::Utc::now().timestamp()) } else { None };
        conn.execute("UPDATE chats SET archived_at = ?1 WHERE id = ?2", params![now, id])?;
        Ok(())
    }

    pub fn set_unread_override(conn: &Connection, id: &str, flag: bool) -> Result<()> {
        let v = if flag { 1i64 } else { 0i64 };
        conn.execute("UPDATE chats SET unread_override = ?1 WHERE id = ?2", params![v, id])?;
        Ok(())
    }

    /// Update a chat's name and/or epoch.
    pub fn update(conn: &Connection, id: &str, name: Option<&str>, epoch: Option<i64>) -> Result<bool> {
        let now = now_rfc3339();
        // Only update fields that are provided; keep existing values otherwise.
        if let Some(name_val) = name {
            conn.execute(
                "UPDATE chats SET name = ?1, updated_at = ?2 WHERE id = ?3",
                params![name_val, now, id],
            )?;
        }
        if let Some(epoch_val) = epoch {
            conn.execute(
                "UPDATE chats SET epoch = ?1, updated_at = ?2 WHERE id = ?3",
                params![epoch_val, now, id],
            )?;
        }
        // If neither was provided, just touch updated_at.
        if name.is_none() && epoch.is_none() {
            conn.execute(
                "UPDATE chats SET updated_at = ?1 WHERE id = ?2",
                params![now, id],
            )?;
        }
        Ok(true)
    }

    /// Delete a chat by ID (cascades to members and messages).
    pub fn delete(conn: &Connection, id: &str) -> Result<bool> {
        let changes = conn.execute("DELETE FROM chats WHERE id = ?1", params![id])?;
        Ok(changes > 0)
    }

    // --- Members ---

    /// Add a member to a chat.
    pub fn add_member(conn: &Connection, member: &ChatMember) -> Result<()> {
        conn.execute(
            "INSERT INTO chat_members (chat_id, contact_id, leaf_index, joined_at)
             VALUES (?1, ?2, ?3, ?4)",
            params![member.chat_id, member.contact_id, member.leaf_index, member.joined_at],
        )?;
        Ok(())
    }

    /// Remove a member from a chat.
    pub fn remove_member(conn: &Connection, chat_id: &str, contact_id: &str) -> Result<bool> {
        let changes = conn.execute(
            "DELETE FROM chat_members WHERE chat_id = ?1 AND contact_id = ?2",
            params![chat_id, contact_id],
        )?;
        Ok(changes > 0)
    }

    /// List all members of a chat.
    pub fn list_members(conn: &Connection, chat_id: &str) -> Result<Vec<ChatMember>> {
        let mut stmt = conn.prepare(
            "SELECT chat_id, contact_id, leaf_index, joined_at
             FROM chat_members WHERE chat_id = ?1",
        )?;

        let rows = stmt.query_map(params![chat_id], |row| {
            Ok(ChatMember {
                chat_id: row.get(0)?,
                contact_id: row.get(1)?,
                leaf_index: row.get(2)?,
                joined_at: row.get(3)?,
            })
        })?;

        let mut members = Vec::new();
        for row in rows {
            members.push(row?);
        }
        Ok(members)
    }
}

// --- MessageRepo ---

/// Prefix: media refs of deleted messages whose files are still on the phone.
const GONE_MEDIA_PREFIX: &str = "gone_media:";

/// Media refs of messages deleted since the last call, for the app to delete their files.
pub fn take_gone_media(conn: &Connection) -> Result<Vec<String>> {
    let gone: Vec<(String, String)> = KeyValueRepo::list(conn)?
        .into_iter()
        .filter(|e| e.key.starts_with(GONE_MEDIA_PREFIX))
        .map(|e| (e.key, e.value))
        .collect();
    for (k, _) in &gone {
        KeyValueRepo::delete(conn, k)?;
    }
    Ok(gone.into_iter().map(|(_, v)| v).collect())
}

/// CRUD operations for the `messages` table.
pub struct MessageRepo;

impl MessageRepo {
    /// Insert a new message.
    pub fn insert(conn: &Connection, msg: &Message) -> Result<()> {
        conn.execute(
            "INSERT INTO messages
             (id, chat_id, sender_id, msg_type, content, reply_to, media_ref,
              ttl_seconds, expires_at, created_at, edited_at, is_deleted,
              expiry_policy, read_at, consumed)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
            params![
                msg.id,
                msg.chat_id,
                msg.sender_id,
                msg.msg_type.as_str(),
                msg.content,
                msg.reply_to,
                msg.media_ref,
                msg.ttl_seconds,
                msg.expires_at,
                msg.created_at,
                msg.edited_at,
                msg.is_deleted,
                msg.expiry_policy.as_ref().map(|p| p.as_str()),
                msg.read_at,
                msg.consumed,
            ],
        )?;
        Ok(())
    }

    /// Delete the entire message history of a chat (local). Used by
    /// "delete dialog" — the chat row goes too but the MLS group stays alive,
    /// so a future incoming message re-creates a fresh (empty) dialog.
    pub fn delete_for_chat(conn: &Connection, chat_id: &str) -> Result<u64> {
        let changes = conn.execute("DELETE FROM messages WHERE chat_id = ?1", params![chat_id])?;
        Ok(changes as u64)
    }

    /// Latest non-deleted message in a chat. Used for chat-list previews
    /// without paging through the whole history.
    pub fn latest_in_chat(conn: &Connection, chat_id: &str) -> Result<Option<Message>> {
        let mut stmt = conn.prepare(
            &format!(
                "SELECT {} FROM messages
                 WHERE chat_id = ?1 AND is_deleted = 0
                   AND msg_type NOT IN ('reaction', 'delete', 'edit', 'read_signal')
                   AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.id)
                 ORDER BY created_at DESC LIMIT 1",
                MESSAGE_COLUMNS
            ),
        )?;
        let res = stmt.query_row(params![chat_id], |row| read_message_row(row)).optional()?;
        Ok(res)
    }

    /// Like [`latest_in_chat`] but skipping messages whose id (album items:
    /// the part before `#al`) is in `skip` — scheduled messages not yet sent.
    pub fn latest_in_chat_skipping(
        conn: &Connection,
        chat_id: &str,
        skip: &std::collections::HashSet<String>,
    ) -> Result<Option<Message>> {
        if skip.is_empty() { return Self::latest_in_chat(conn, chat_id); }
        let mut stmt = conn.prepare(
            &format!(
                "SELECT {} FROM messages
                 WHERE chat_id = ?1 AND is_deleted = 0
                   AND msg_type NOT IN ('reaction', 'delete', 'edit', 'read_signal')
                   AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.id)
                 ORDER BY created_at DESC LIMIT 200",
                MESSAGE_COLUMNS
            ),
        )?;
        let rows = stmt.query_map(params![chat_id], |row| read_message_row(row))?;
        for r in rows {
            let m = r?;
            let base = m.id.split("#al").next().unwrap_or(&m.id);
            if !skip.contains(base) { return Ok(Some(m)); }
        }
        Ok(None)
    }

    /// A scheduled message goes out now: it takes its place in the chat at
    /// the moment it is actually sent (album items share the base id).
    /// `ttl_secs`: a disappearing timer frozen while the message was held
    /// ([`freeze_expiry`]) starts now.
    pub fn restamp_created_at(conn: &Connection, id: &str, ttl_secs: Option<i64>) -> Result<bool> {
        let now = Utc::now();
        // Receipts that touched it while waiting were not about it.
        let changes = conn.execute(
            "UPDATE messages SET created_at = ?1, read_at = NULL, delivered_at = NULL
             WHERE id = ?2 OR id LIKE ?2 || '#al%'",
            params![now.to_rfc3339(), id],
        )?;
        if let Some(ttl) = ttl_secs.filter(|t| *t > 0) {
            let exp = (now + chrono::Duration::seconds(ttl)).to_rfc3339();
            conn.execute(
                "UPDATE messages SET expires_at = ?1 WHERE id = ?2 OR id LIKE ?2 || '#al%'",
                params![exp, id],
            )?;
        }
        Ok(changes > 0)
    }

    /// A held (scheduled) message is dated at its send time while it waits:
    /// "read up to X" receipts for messages sent meanwhile never cover it.
    pub fn date_held(conn: &Connection, id: &str, not_before_unix: i64) -> Result<bool> {
        let at = chrono::DateTime::<Utc>::from_timestamp(not_before_unix, 0)
            .unwrap_or_else(Utc::now).to_rfc3339();
        let changes = conn.execute(
            "UPDATE messages SET created_at = ?1, read_at = NULL, delivered_at = NULL
             WHERE id = ?2 OR id LIKE ?2 || '#al%'",
            params![at, id],
        )?;
        Ok(changes > 0)
    }

    /// A message that is held (scheduled) must not run its disappearing
    /// timer before it is even sent: clear `expires_at` and return the ttl
    /// so the timer can start at release. None = no running timer.
    pub fn freeze_expiry(conn: &Connection, id: &str) -> Result<Option<i64>> {
        let ttl: Option<i64> = conn.query_row(
            "SELECT ttl_seconds FROM messages WHERE (id = ?1 OR id LIKE ?1 || '#al%')
               AND expires_at IS NOT NULL AND ttl_seconds IS NOT NULL LIMIT 1",
            params![id],
            |row| row.get(0),
        ).optional()?;
        if ttl.is_some() {
            conn.execute(
                "UPDATE messages SET expires_at = NULL WHERE id = ?1 OR id LIKE ?1 || '#al%'",
                params![id],
            )?;
        }
        Ok(ttl)
    }

    /// True when the message (or any item of an album with this base id) exists.
    pub fn exists_base(conn: &Connection, id: &str) -> Result<bool> {
        let n: i64 = conn.query_row(
            "SELECT COUNT(*) FROM messages WHERE id = ?1 OR id LIKE ?1 || '#al%'",
            params![id],
            |row| row.get(0),
        )?;
        Ok(n > 0)
    }

    /// Messages whose words (text, file name, link title) start with every word
    /// of `query` (any case, any script), newest first; one chat or all; of one
    /// `kind` ("media" | "link" | "music" | "voice" | "doc" | "app" | "archive", or
    /// "file" for every file) or any; from one author (`sender`: "self" or a
    /// person's pubkey hex, any of their member keys) and within [`since`, `until`)
    /// (UTC "YYYY-MM-DDTHH:MM:SS"). Any filter without a query lists what it keeps.
    /// Comments stay in their discussions.
    pub fn search(conn: &Connection, query: &str, f: &SearchFilter<'_>, limit: u32, offset: u32) -> Result<Vec<SearchRow>> {
        let fts = fts_query(query);
        if fts.is_none() && f.kind.is_none() && f.sender.is_none() && f.since.is_none() && f.until.is_none() {
            return Ok(Vec::new());
        }
        let mut stmt = conn.prepare(
            "SELECT m.id, m.chat_id, m.sender_id, COALESCE(m.content, ''), m.created_at, m.msg_type,
                    COALESCE(m.media_kind, ''), COALESCE(m.media_name, '')
             FROM messages m
             WHERE (?5 IS NULL OR m.media_kind = ?5 OR (?5 = 'file' AND m.media_kind IN ('doc', 'app', 'archive')))
               AND (?1 IS NULL OR m.rowid IN (SELECT rowid FROM message_fts WHERE message_fts MATCH ?1))
               AND m.is_deleted = 0
               AND (?2 IS NULL OR m.chat_id = ?2)
               AND (?6 IS NULL OR m.sender_id = ?6 OR m.sender_id IN
                    (SELECT substr(key, 13) FROM key_value_store WHERE key LIKE 'member_npub:%' AND value = ?6))
               AND (?7 IS NULL OR m.created_at >= ?7)
               AND (?8 IS NULL OR m.created_at < ?8)
               AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = m.id)
             ORDER BY m.created_at DESC
             LIMIT ?3 OFFSET ?4",
        )?;
        let rows = stmt.query_map(params![fts, f.chat_id, limit, offset, f.kind, f.sender, f.since, f.until], |r| {
            Ok(SearchRow {
                id: r.get(0)?,
                chat_id: r.get(1)?,
                sender_id: r.get(2)?,
                content: r.get(3)?,
                created_at: r.get(4)?,
                msg_type: r.get(5)?,
                media_kind: r.get(6)?,
                media_name: r.get(7)?,
            })
        })?;
        rows.collect::<rusqlite::Result<_>>().map_err(Into::into)
    }

    /// The first message of a chat's own feed written at or after `at` (UTC,
    /// "YYYY-MM-DDTHH:MM:SS"): where a jump to a date lands.
    pub fn first_at_or_after(conn: &Connection, chat_id: &str, at: &str) -> Result<Option<String>> {
        conn.query_row(
            "SELECT id FROM messages
             WHERE chat_id = ?1 AND created_at >= ?2 AND is_deleted = 0
               AND msg_type NOT IN ('reaction', 'delete', 'edit', 'read_signal')
               AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.id)
             ORDER BY created_at ASC LIMIT 1",
            params![chat_id, at],
            |r| r.get::<_, String>(0),
        ).optional().map_err(Into::into)
    }

    /// Count unread incoming messages: read_at IS NULL AND sender_id != "self".
    /// Used as the unread badge in the chat list.
    pub fn count_unread(conn: &Connection, chat_id: &str) -> Result<i64> {
        let n: i64 = conn.query_row(
            "SELECT COUNT(*) FROM messages
             WHERE chat_id = ?1 AND read_at IS NULL AND sender_id != 'self' AND is_deleted = 0
               AND msg_type NOT IN ('reaction', 'delete', 'edit', 'read_signal')
               AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.id)",
            params![chat_id],
            |row| row.get(0),
        )?;
        Ok(n)
    }

    /// Ids of incoming, not-yet-read, real messages in a chat. Used to emit
    /// per-message ReadSignals back to the sender when the chat is opened.
    pub fn unread_incoming_ids(conn: &Connection, chat_id: &str, through: Option<&str>) -> Result<Vec<String>> {
        let mut stmt = conn.prepare(
            "SELECT id FROM messages
             WHERE chat_id = ?1 AND read_at IS NULL AND sender_id != 'self' AND is_deleted = 0
               AND msg_type NOT IN ('reaction', 'delete', 'edit', 'read_signal')
               AND (?2 IS NULL OR created_at <= (SELECT created_at FROM messages WHERE id = ?2))
             ORDER BY created_at ASC",
        )?;
        let rows = stmt.query_map(params![chat_id, through], |row| row.get::<_, String>(0))?;
        let mut ids = Vec::new();
        for r in rows {
            ids.push(r?);
        }
        Ok(ids)
    }

    /// Mark incoming messages in a chat as read (sets read_at = NOW): all of them, or
    /// only those up to and including [through], the newest one the reader has seen.
    /// No-op for outgoing messages and already-read ones.
    pub fn mark_chat_read(conn: &Connection, chat_id: &str, through: Option<&str>) -> Result<usize> {
        let now = now_rfc3339();
        // An after-read message starts its countdown now, as `mark_read` does.
        let timed: Vec<(String, i64)> = {
            let mut stmt = conn.prepare(
                "SELECT id, ttl_seconds FROM messages
                 WHERE chat_id = ?1 AND read_at IS NULL AND sender_id != 'self'
                   AND expiry_policy = 'after_read' AND ttl_seconds IS NOT NULL AND expires_at IS NULL
                   AND (?2 IS NULL OR created_at <= (SELECT created_at FROM messages WHERE id = ?2))",
            )?;
            let rows = stmt.query_map(params![chat_id, through], |r| Ok((r.get(0)?, r.get(1)?)))?;
            rows.collect::<rusqlite::Result<_>>()?
        };
        let n = conn.execute(
            "UPDATE messages SET read_at = ?1
             WHERE chat_id = ?2 AND read_at IS NULL AND sender_id != 'self'
               AND (?3 IS NULL OR created_at <= (SELECT created_at FROM messages WHERE id = ?3))",
            params![now, chat_id, through],
        )?;
        let start = chrono::Utc::now();
        for (id, ttl) in timed {
            let expires_at = (start + chrono::Duration::seconds(ttl)).to_rfc3339();
            conn.execute("UPDATE messages SET expires_at = ?1 WHERE id = ?2", params![expires_at, id])?;
        }
        Ok(n)
    }

    /// Get a single message by ID.
    pub fn get(conn: &Connection, id: &str) -> Result<Option<Message>> {
        let mut stmt = conn.prepare(
            &format!("SELECT {} FROM messages WHERE id = ?1", MESSAGE_COLUMNS),
        )?;

        let result = stmt
            .query_row(params![id], |row| read_message_row(row))
            .optional()?;

        Ok(result)
    }

    /// Get messages for a chat with pagination.
    /// Every attachment kept in the history (album parts one by one), as
    /// (chat_id, media_type, hash, local_path): what the storage screen and the
    /// cache trimmer measure, without carrying whole messages across.
    pub fn stored_media(conn: &Connection) -> Result<Vec<(String, String, String, String)>> {
        let mut stmt = conn.prepare(
            "SELECT chat_id, COALESCE(json_extract(media_ref, '$.media_type'), 'file'), COALESCE(json_extract(media_ref, '$.hash'), ''),
                    COALESCE(json_extract(media_ref, '$.local_path'), '')
             FROM messages
             WHERE msg_type = 'media' AND media_ref IS NOT NULL AND json_valid(media_ref)
               AND COALESCE(json_extract(media_ref, '$.album'), 0) != 1
             UNION ALL
             SELECT m.chat_id, COALESCE(json_extract(j.value, '$.media_type'), 'file'), COALESCE(json_extract(j.value, '$.hash'), ''),
                    COALESCE(json_extract(j.value, '$.local_path'), '')
             FROM messages m, json_each(m.media_ref, '$.items') j
             WHERE m.msg_type = 'media' AND m.media_ref IS NOT NULL AND json_valid(m.media_ref)
               AND json_extract(m.media_ref, '$.album') = 1",
        )?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?)))?;
        rows.collect::<rusqlite::Result<_>>().map_err(Into::into)
    }

    /// A chat's messages of the given kinds (see `media_kind`), newest first, over
    /// the whole history: what the chat's shared media shows. Comments excluded.
    pub fn of_kinds(conn: &Connection, chat_id: &str, kinds: &[&str], limit: u32, offset: u32) -> Result<Vec<Message>> {
        if kinds.is_empty() { return Ok(Vec::new()); }
        let marks = (0..kinds.len()).map(|i| format!("?{}", i + 4)).collect::<Vec<_>>().join(", ");
        let sql = format!(
            "SELECT {} FROM messages
             WHERE chat_id = ?1 AND is_deleted = 0 AND media_kind IN ({marks})
               AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.id)
             ORDER BY created_at DESC LIMIT ?2 OFFSET ?3",
            MESSAGE_COLUMNS
        );
        let mut stmt = conn.prepare(&sql)?;
        let mut args: Vec<&dyn rusqlite::ToSql> = vec![&chat_id, &limit, &offset];
        for k in kinds { args.push(k); }
        let rows = stmt.query_map(args.as_slice(), |row| read_message_row(row))?;
        rows.collect::<rusqlite::Result<_>>().map_err(Into::into)
    }

    /// The messages with these ids, in that order (missing ones left out).
    pub fn by_ids(conn: &Connection, ids: &[String]) -> Result<Vec<Message>> {
        let sql = format!("SELECT {} FROM messages WHERE id = ?1", MESSAGE_COLUMNS);
        let mut stmt = conn.prepare(&sql)?;
        let mut out = Vec::with_capacity(ids.len());
        for id in ids {
            if let Some(m) = stmt.query_row(params![id], |row| read_message_row(row)).optional()? { out.push(m); }
        }
        Ok(out)
    }

    /// How many of the rows [get_by_chat] returns come after `message_id` (newest
    /// first): the window a jump to it must load. None when it is not in the chat.
    pub fn rank_from_newest(conn: &Connection, chat_id: &str, message_id: &str) -> Result<Option<u32>> {
        let at: Option<String> = conn.query_row(
            "SELECT created_at FROM messages WHERE id = ?1 AND chat_id = ?2 AND is_deleted = 0",
            params![message_id, chat_id],
            |r| r.get(0),
        ).optional()?;
        let Some(at) = at else { return Ok(None) };
        let n: i64 = conn.query_row(
            "SELECT COUNT(*) FROM messages
             WHERE chat_id = ?1 AND created_at > ?2
               AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.id)
               AND NOT (msg_type IN ('reaction', 'edit', 'delete') AND reply_to IS NOT NULL
                        AND EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.reply_to))",
            params![chat_id, at],
            |r| r.get(0),
        )?;
        Ok(Some(n as u32))
    }

    pub fn get_by_chat(
        conn: &Connection,
        chat_id: &str,
        limit: u32,
        offset: u32,
    ) -> Result<Vec<Message>> {
        // Return the NEWEST `limit` messages (so a chat with >limit messages
        // still shows the latest ones — otherwise new messages fall outside an
        // oldest-first window and "vanish" from the open chat while the chat-list
        // preview still shows them). Inner query picks newest-first; outer
        // re-sorts to chronological ASC for the UI (oldest→newest).
        let mut stmt = conn.prepare(
            &format!(
                "SELECT * FROM (
                    SELECT {} FROM messages
                    WHERE chat_id = ?1
                      AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.id)
                      AND NOT (msg_type IN ('reaction', 'edit', 'delete') AND reply_to IS NOT NULL
                               AND EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.reply_to))
                    ORDER BY created_at DESC
                    LIMIT ?2 OFFSET ?3
                 ) ORDER BY created_at ASC",
                MESSAGE_COLUMNS
            ),
        )?;

        let rows = stmt.query_map(params![chat_id, limit, offset], |row| {
            read_message_row(row)
        })?;

        let mut messages = Vec::new();
        for row in rows {
            messages.push(row?);
        }
        Ok(messages)
    }

    /// One page of a chat for an export: its messages after the cursor
    /// (created_at, id), oldest first, within [since, until) unix seconds
    /// (0 = open). Comments, timed and deleted messages stay out.
    pub fn export_page(
        conn: &Connection,
        chat_id: &str,
        after_at: &str,
        after_id: &str,
        since: i64,
        until: i64,
        limit: u32,
    ) -> Result<Vec<Message>> {
        let mut stmt = conn.prepare(&format!(
            "SELECT {} FROM messages
             WHERE chat_id = ?1
               AND msg_type IN ('text', 'reply', 'media', 'disappearing')
               AND is_deleted = 0
               AND COALESCE(expiry_policy, 'none') = 'none'
               AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.message_id = messages.id)
               AND (created_at > ?2 OR (created_at = ?2 AND id > ?3))
               AND (?4 = 0 OR CAST(strftime('%s', created_at) AS INTEGER) >= ?4)
               AND (?5 = 0 OR CAST(strftime('%s', created_at) AS INTEGER) < ?5)
             ORDER BY created_at ASC, id ASC
             LIMIT ?6",
            MESSAGE_COLUMNS
        ))?;
        let rows = stmt.query_map(params![chat_id, after_at, after_id, since, until, limit], |row| read_message_row(row))?;
        rows.collect::<std::result::Result<Vec<_>, _>>().map_err(Into::into)
    }

    /// (message, emoji, how many) for every reaction in a chat.
    pub fn reaction_counts(conn: &Connection, chat_id: &str) -> Result<Vec<(String, String, i64)>> {
        let mut stmt = conn.prepare(
            "SELECT reply_to, content, COUNT(*) FROM messages
             WHERE chat_id = ?1 AND msg_type = 'reaction' AND reply_to IS NOT NULL AND content IS NOT NULL
             GROUP BY reply_to, content",
        )?;
        let rows = stmt.query_map(params![chat_id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
        rows.collect::<std::result::Result<Vec<_>, _>>().map_err(Into::into)
    }

    /// The discussion of one post: its comments (newest `limit`, returned
    /// oldest first like [`get_by_chat`]) together with the reactions, edits
    /// and deletes aimed at them.
    pub fn get_thread(
        conn: &Connection,
        chat_id: &str,
        post_id: &str,
        limit: u32,
        offset: u32,
    ) -> Result<Vec<Message>> {
        let mut stmt = conn.prepare(
            &format!(
                "SELECT * FROM (
                    SELECT {} FROM messages
                    WHERE chat_id = ?1
                      AND (id IN (SELECT message_id FROM comments WHERE chat_id = ?1 AND post_id = ?2)
                           OR (msg_type IN ('reaction', 'edit', 'delete')
                               AND reply_to IN (SELECT message_id FROM comments WHERE chat_id = ?1 AND post_id = ?2)))
                    ORDER BY created_at DESC
                    LIMIT ?3 OFFSET ?4
                 ) ORDER BY created_at ASC",
                MESSAGE_COLUMNS
            ),
        )?;
        let rows = stmt.query_map(params![chat_id, post_id, limit, offset], |row| read_message_row(row))?;
        let mut messages = Vec::new();
        for row in rows {
            messages.push(row?);
        }
        Ok(messages)
    }

    /// Persist the DELIVERED status: stamp `delivered_at = NOW` on a
    /// message once every recipient has ACKed it. Idempotent and forward-only —
    /// only writes when `delivered_at` is still NULL, so a duplicate ACK never
    /// moves the timestamp. Returns true if a row was stamped.
    pub fn mark_delivered(conn: &Connection, id: &str) -> Result<bool> {
        let now = now_rfc3339();
        let changes = conn.execute(
            "UPDATE messages SET delivered_at = ?1 WHERE id = ?2 AND delivered_at IS NULL",
            params![now, id],
        )?;
        Ok(changes > 0)
    }

    /// Mark a message as deleted (tombstone).
    /// Also clears content for security (zeroize plaintext from DB). Its media ref is
    /// kept aside for the app, which deletes the file from the phone ([take_gone_media]).
    pub fn mark_deleted(conn: &Connection, id: &str) -> Result<bool> {
        let media: Option<String> = conn
            .query_row("SELECT media_ref FROM messages WHERE id = ?1", params![id], |r| r.get(0))
            .optional()?
            .flatten();
        if let Some(m) = media.filter(|m| !m.is_empty()) {
            KeyValueRepo::set(conn, &format!("{GONE_MEDIA_PREFIX}{id}"), &m)?;
        }
        let now = now_rfc3339();
        let changes = conn.execute(
            "UPDATE messages SET is_deleted = 1, content = NULL, media_ref = NULL, edited_at = ?1 WHERE id = ?2",
            params![now, id],
        )?;
        Ok(changes > 0)
    }

    /// Replace a message's media_ref (used by the optimistic upload: the row
    /// is first persisted with a local-pending ref, then swapped for the real
    /// Blossom MediaRef JSON once the upload finishes).
    /// Mark a row as a reply to [reply_to] (an outgoing photo, video or sticker answering a message).
    pub fn mark_silent(conn: &Connection, id: &str) -> Result<bool> {
        Ok(conn.execute("UPDATE messages SET silent = 1 WHERE id = ?1", params![id])? > 0)
    }

    pub fn is_silent(conn: &Connection, id: &str) -> bool {
        conn.query_row("SELECT silent FROM messages WHERE id = ?1", params![id], |r| r.get::<_, i64>(0))
            .map(|v| v != 0).unwrap_or(false)
    }

    pub fn set_reply_to(conn: &Connection, id: &str, reply_to: &str) -> Result<bool> {
        let changes = conn.execute(
            "UPDATE messages SET reply_to = ?1 WHERE id = ?2",
            params![reply_to, id],
        )?;
        Ok(changes > 0)
    }

    pub fn update_media_ref(conn: &Connection, id: &str, media_ref: &str) -> Result<bool> {
        let changes = conn.execute(
            "UPDATE messages SET media_ref = ?1 WHERE id = ?2",
            params![media_ref, id],
        )?;
        Ok(changes > 0)
    }

    /// Apply an edit to the ORIGINAL message row: replace its content and
    /// stamp edited_at. The fold also has the EDIT row, but updating the base
    /// row keeps `latest_in_chat` (the chat-list preview) in sync — otherwise
    /// the preview keeps showing the pre-edit text. No-op if the target row
    /// isn't present yet (out-of-order delivery); the EDIT row still carries
    /// the new text for the fold.
    pub fn apply_edit(conn: &Connection, target_id: &str, new_body: &str, edited_at: &str) -> Result<bool> {
        let changes = conn.execute(
            "UPDATE messages SET content = ?1, edited_at = ?2 WHERE id = ?3",
            params![new_body, edited_at, target_id],
        )?;
        Ok(changes > 0)
    }

    /// Delete a reaction row matching (chat, sender, target, emoji). Used for
    /// reaction toggle — tapping the same emoji twice removes it.
    pub fn delete_reaction(
        conn: &Connection,
        chat_id: &str,
        sender_id: &str,
        target_id: &str,
        emoji: &str,
    ) -> Result<usize> {
        Ok(conn.execute(
            "DELETE FROM messages WHERE chat_id = ?1 AND sender_id = ?2 \
             AND reply_to = ?3 AND content = ?4 AND msg_type = 'reaction'",
            params![chat_id, sender_id, target_id, emoji],
        )?)
    }

    /// Our own timed message (album items too) starts its countdown when a relay took
    /// it, as a received one does when it is read. One-time messages count from opening.
    pub fn start_own_countdown(conn: &Connection, id: &str) -> Result<usize> {
        let rows: Vec<(String, i64)> = {
            let mut stmt = conn.prepare(
                "SELECT id, ttl_seconds FROM messages
                 WHERE (id = ?1 OR id LIKE ?1 || '#al%') AND sender_id = 'self'
                   AND ttl_seconds > 0 AND expires_at IS NULL
                   AND (expiry_policy IS NULL OR expiry_policy != 'view_once')",
            )?;
            let found = stmt.query_map(params![id], |r| Ok((r.get(0)?, r.get(1)?)))?;
            found.collect::<rusqlite::Result<_>>()?
        };
        let now = Utc::now();
        for (row, ttl) in &rows {
            let at = (now + chrono::Duration::seconds(*ttl)).to_rfc3339();
            conn.execute("UPDATE messages SET expires_at = ?1 WHERE id = ?2", params![at, row])?;
        }
        Ok(rows.len())
    }

    /// When the next timed message is due to disappear, in unix milliseconds.
    pub fn next_expiry_ms(conn: &Connection) -> Result<Option<i64>> {
        let at: Option<String> = conn.query_row(
            "SELECT MIN(expires_at) FROM messages WHERE ttl_seconds IS NOT NULL AND expires_at IS NOT NULL",
            [],
            |r| r.get(0),
        )?;
        Ok(at.and_then(|t| chrono::DateTime::parse_from_rfc3339(&t).ok()).map(|t| t.timestamp_millis()))
    }

    /// Remove all expired disappearing messages from the database.
    ///
    /// Returns the number of messages removed.
    pub fn cleanup_expired(conn: &Connection) -> Result<u64> {
        let now = now_rfc3339();
        let changes = conn.execute(
            "DELETE FROM messages WHERE ttl_seconds IS NOT NULL AND expires_at IS NOT NULL AND expires_at <= ?1",
            params![now],
        )?;
        Ok(changes as u64)
    }

    /// Update a message's content (for edits).
    pub fn update_content(conn: &Connection, id: &str, content: &str) -> Result<bool> {
        let now = now_rfc3339();
        let changes = conn.execute(
            "UPDATE messages SET content = ?1, edited_at = ?2 WHERE id = ?3",
            params![content, now, id],
        )?;
        Ok(changes > 0)
    }

    /// Delete a message by ID (hard delete).
    pub fn delete(conn: &Connection, id: &str) -> Result<bool> {
        let changes = conn.execute("DELETE FROM messages WHERE id = ?1", params![id])?;
        Ok(changes > 0)
    }

    /// Count messages in a chat.
    pub fn count_by_chat(conn: &Connection, chat_id: &str) -> Result<i64> {
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM messages WHERE chat_id = ?1",
            params![chat_id],
            |row| row.get(0),
        )?;
        Ok(count)
    }

    // --- Ephemeral message operations ---

    /// Mark a message as read by recording the `read_at` timestamp
    /// and computing the `expires_at` based on the message's TTL.
    ///
    /// For `AfterRead` messages, this starts the expiry timer.
    /// Returns `true` if the message was updated.
    /// A read receipt covers everything before it: mark our own messages in the
    /// chat up to (and including) `target_id` as read. Returns the updated ids.
    pub fn mark_outgoing_read_up_to(conn: &Connection, chat_id: &str, target_id: &str, read_at: &str) -> Result<Vec<String>> {
        let mut stmt = conn.prepare(
            "SELECT id FROM messages
             WHERE chat_id = ?1 AND sender_id = 'self' AND read_at IS NULL AND is_deleted = 0
               AND created_at <= (SELECT created_at FROM messages WHERE id = ?2)",
        )?;
        let ids: Vec<String> = stmt
            .query_map(params![chat_id, target_id], |row| row.get::<_, String>(0))?
            .collect::<std::result::Result<_, _>>()?;
        for id in &ids {
            let _ = Self::mark_read(conn, id, read_at);
        }
        Ok(ids)
    }

    pub fn mark_read(conn: &Connection, id: &str, read_at: &str) -> Result<bool> {
        // Get the TTL for this message to compute expires_at
        let ttl: Option<i64> = conn
            .query_row(
                "SELECT ttl_seconds FROM messages WHERE id = ?1",
                params![id],
                |row| row.get(0),
            )
            .optional()?
            .flatten();

        let changes = if let Some(ttl_secs) = ttl {
            let read_time = chrono::DateTime::parse_from_rfc3339(read_at)
                .map_err(|e| crate::error::DatabaseError::QueryFailed(e.to_string()))?;
            let expires_at = (read_time + chrono::Duration::seconds(ttl_secs)).to_rfc3339();
            conn.execute(
                "UPDATE messages SET read_at = ?1, expires_at = COALESCE(expires_at, ?2)
                 WHERE id = ?3 AND read_at IS NULL",
                params![read_at, expires_at, id],
            )?
        } else {
            conn.execute(
                "UPDATE messages SET read_at = ?1 WHERE id = ?2 AND read_at IS NULL",
                params![read_at, id],
            )?
        };

        Ok(changes > 0)
    }

    /// Mark a ViewOnce message as consumed (viewed).
    ///
    /// This also clears `content` and `media_ref` from the database
    /// (they should already have been zeroized in memory).
    /// The message row is kept as a tombstone until the sweeper deletes it.
    pub fn mark_consumed(conn: &Connection, id: &str) -> Result<bool> {
        let changes = conn.execute(
            "UPDATE messages SET consumed = 1, content = NULL, media_ref = NULL WHERE id = ?1 AND expiry_policy = 'view_once'",
            params![id],
        )?;
        Ok(changes > 0)
    }

    /// Get all messages that have expired (for pre-deletion media cleanup).
    ///
    /// Returns (id, media_ref) pairs for expired messages that have media.
    pub fn get_expired_with_media(conn: &Connection) -> Result<Vec<(String, String)>> {
        let now = now_rfc3339();
        let mut stmt = conn.prepare(
            "SELECT id, media_ref FROM messages
             WHERE expires_at IS NOT NULL AND expires_at <= ?1
             AND media_ref IS NOT NULL",
        )?;

        let rows = stmt.query_map(params![now], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;

        let mut results = Vec::new();
        for row in rows {
            results.push(row?);
        }
        Ok(results)
    }

    /// Get all consumed ViewOnce messages that haven't been deleted yet.
    pub fn get_consumed_view_once(conn: &Connection) -> Result<Vec<Message>> {
        let mut stmt = conn.prepare(
            &format!(
                "SELECT {} FROM messages WHERE consumed = 1 AND expiry_policy = 'view_once'",
                MESSAGE_COLUMNS
            ),
        )?;

        let rows = stmt.query_map([], |row| read_message_row(row))?;

        let mut messages = Vec::new();
        for row in rows {
            messages.push(row?);
        }
        Ok(messages)
    }

    /// Hard-delete all expired messages (used by ExpirySweeper).
    /// Returns the number of deleted rows.
    pub fn delete_expired(conn: &Connection) -> Result<u64> {
        let now = now_rfc3339();
        let changes = conn.execute(
            "DELETE FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?1",
            params![now],
        )?;
        Ok(changes as u64)
    }

    /// Hard-delete all consumed ViewOnce messages.
    /// Returns the number of deleted rows.
    pub fn delete_consumed_view_once(conn: &Connection) -> Result<u64> {
        let changes = conn.execute(
            "DELETE FROM messages WHERE consumed = 1 AND expiry_policy = 'view_once'
             AND id NOT IN (SELECT message_id FROM whispers)",
            params![],
        )?;
        Ok(changes as u64)
    }
}

// --- MlsGroupRepo ---

/// CRUD operations for the `mls_groups` table.
pub struct MlsGroupRepo;

impl MlsGroupRepo {
    /// Insert a new MLS group metadata row.
    /// Idempotent: INSERT OR IGNORE prevents duplicate rows for the same group_id.
    pub fn insert(conn: &Connection, group: &MlsGroupRow) -> Result<()> {
        conn.execute(
            "INSERT OR IGNORE INTO mls_groups (group_id, epoch, ciphersuite, is_active, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                group.group_id,
                group.epoch,
                group.ciphersuite,
                group.is_active,
                group.created_at,
                group.updated_at,
            ],
        )?;
        Ok(())
    }

    /// Get an MLS group by its hex-encoded GroupId.
    pub fn get(conn: &Connection, group_id: &str) -> Result<Option<MlsGroupRow>> {
        let mut stmt = conn.prepare(
            "SELECT group_id, epoch, ciphersuite, is_active, created_at, updated_at
             FROM mls_groups WHERE group_id = ?1",
        )?;

        let result = stmt
            .query_row(params![group_id], |row| {
                Ok(MlsGroupRow {
                    group_id: row.get(0)?,
                    epoch: row.get(1)?,
                    ciphersuite: row.get(2)?,
                    is_active: row.get(3)?,
                    created_at: row.get(4)?,
                    updated_at: row.get(5)?,
                })
            })
            .optional()?;

        Ok(result)
    }

    /// List all active MLS groups.
    pub fn list_active(conn: &Connection) -> Result<Vec<MlsGroupRow>> {
        let mut stmt = conn.prepare(
            "SELECT group_id, epoch, ciphersuite, is_active, created_at, updated_at
             FROM mls_groups WHERE is_active = 1 ORDER BY updated_at DESC",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(MlsGroupRow {
                group_id: row.get(0)?,
                epoch: row.get(1)?,
                ciphersuite: row.get(2)?,
                is_active: row.get(3)?,
                created_at: row.get(4)?,
                updated_at: row.get(5)?,
            })
        })?;

        let mut groups = Vec::new();
        for row in rows {
            groups.push(row?);
        }
        Ok(groups)
    }

    /// Update the epoch and/or active status of an MLS group.
    pub fn update(
        conn: &Connection,
        group_id: &str,
        epoch: Option<i64>,
        is_active: Option<bool>,
    ) -> Result<bool> {
        let now = now_rfc3339();

        if let Some(epoch_val) = epoch {
            conn.execute(
                "UPDATE mls_groups SET epoch = ?1, updated_at = ?2 WHERE group_id = ?3",
                params![epoch_val, now, group_id],
            )?;
        }
        if let Some(active_val) = is_active {
            conn.execute(
                "UPDATE mls_groups SET is_active = ?1, updated_at = ?2 WHERE group_id = ?3",
                params![active_val, now, group_id],
            )?;
        }
        if epoch.is_none() && is_active.is_none() {
            conn.execute(
                "UPDATE mls_groups SET updated_at = ?1 WHERE group_id = ?2",
                params![now, group_id],
            )?;
        }
        Ok(true)
    }

    /// Delete an MLS group by GroupId.
    pub fn delete(conn: &Connection, group_id: &str) -> Result<bool> {
        let changes =
            conn.execute("DELETE FROM mls_groups WHERE group_id = ?1", params![group_id])?;
        Ok(changes > 0)
    }
}

// --- KeyPackageRepo ---

/// CRUD operations for the `mls_key_packages` table.
pub struct KeyPackageRepo;

impl KeyPackageRepo {
    /// Insert a new key package.
    pub fn insert(conn: &Connection, kp: &MlsKeyPackageRow) -> Result<()> {
        conn.execute(
            "INSERT INTO mls_key_packages (id, hash_ref, key_package_data, created_at, is_consumed)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                kp.id,
                kp.hash_ref,
                kp.key_package_data,
                kp.created_at,
                kp.is_consumed,
            ],
        )?;
        Ok(())
    }

    /// Get a key package by ID.
    pub fn get(conn: &Connection, id: &str) -> Result<Option<MlsKeyPackageRow>> {
        let mut stmt = conn.prepare(
            "SELECT id, hash_ref, key_package_data, created_at, is_consumed
             FROM mls_key_packages WHERE id = ?1",
        )?;

        let result = stmt
            .query_row(params![id], |row| {
                Ok(MlsKeyPackageRow {
                    id: row.get(0)?,
                    hash_ref: row.get(1)?,
                    key_package_data: row.get(2)?,
                    created_at: row.get(3)?,
                    is_consumed: row.get(4)?,
                })
            })
            .optional()?;

        Ok(result)
    }

    /// Find a key package by its hash reference.
    pub fn get_by_hash_ref(conn: &Connection, hash_ref: &[u8]) -> Result<Option<MlsKeyPackageRow>> {
        let mut stmt = conn.prepare(
            "SELECT id, hash_ref, key_package_data, created_at, is_consumed
             FROM mls_key_packages WHERE hash_ref = ?1",
        )?;

        let result = stmt
            .query_row(params![hash_ref], |row| {
                Ok(MlsKeyPackageRow {
                    id: row.get(0)?,
                    hash_ref: row.get(1)?,
                    key_package_data: row.get(2)?,
                    created_at: row.get(3)?,
                    is_consumed: row.get(4)?,
                })
            })
            .optional()?;

        Ok(result)
    }

    /// List all unconsumed key packages.
    pub fn list_available(conn: &Connection) -> Result<Vec<MlsKeyPackageRow>> {
        let mut stmt = conn.prepare(
            "SELECT id, hash_ref, key_package_data, created_at, is_consumed
             FROM mls_key_packages WHERE is_consumed = 0 ORDER BY created_at ASC",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(MlsKeyPackageRow {
                id: row.get(0)?,
                hash_ref: row.get(1)?,
                key_package_data: row.get(2)?,
                created_at: row.get(3)?,
                is_consumed: row.get(4)?,
            })
        })?;

        let mut packages = Vec::new();
        for row in rows {
            packages.push(row?);
        }
        Ok(packages)
    }

    /// Mark a key package as consumed.
    pub fn mark_consumed(conn: &Connection, id: &str) -> Result<bool> {
        let changes = conn.execute(
            "UPDATE mls_key_packages SET is_consumed = 1 WHERE id = ?1",
            params![id],
        )?;
        Ok(changes > 0)
    }

    /// Delete a key package by ID.
    pub fn delete(conn: &Connection, id: &str) -> Result<bool> {
        let changes =
            conn.execute("DELETE FROM mls_key_packages WHERE id = ?1", params![id])?;
        Ok(changes > 0)
    }
}

// --- SyncCheckpointRepo ---

/// Read/update operations for the `sync_checkpoints` table.
pub struct SyncCheckpointRepo;

impl SyncCheckpointRepo {
    /// Get the checkpoint for a relay.
    pub fn get(conn: &Connection, relay_url: &str) -> Result<Option<SyncCheckpoint>> {
        let mut stmt = conn.prepare(
            "SELECT relay_url, last_event_id, last_timestamp, updated_at
             FROM sync_checkpoints WHERE relay_url = ?1",
        )?;

        let result = stmt
            .query_row(params![relay_url], |row| {
                Ok(SyncCheckpoint {
                    relay_url: row.get(0)?,
                    last_event_id: row.get(1)?,
                    last_timestamp: row.get(2)?,
                    updated_at: row.get(3)?,
                })
            })
            .optional()?;

        Ok(result)
    }

    /// Upsert a sync checkpoint for a relay.
    pub fn upsert(
        conn: &Connection,
        relay_url: &str,
        last_event_id: Option<&str>,
        last_timestamp: i64,
    ) -> Result<()> {
        let now = now_rfc3339();
        // Forward-only: only advance the checkpoint, never regress it. Several
        // `SyncState` instances (realtime path, startup catch-up, FFI catchUp)
        // each hold their own in-memory cache and write through here; the
        // in-memory "advance only" guard does NOT coordinate across instances, so
        // without the WHERE clause a stale instance could move the persisted
        // checkpoint backward and trigger redundant re-fetch churn. A new relay
        // row still inserts normally.
        conn.execute(
            "INSERT INTO sync_checkpoints (relay_url, last_event_id, last_timestamp, updated_at)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(relay_url) DO UPDATE SET
                 last_event_id = excluded.last_event_id,
                 last_timestamp = excluded.last_timestamp,
                 updated_at = excluded.updated_at
             WHERE excluded.last_timestamp > sync_checkpoints.last_timestamp",
            params![relay_url, last_event_id, last_timestamp, now],
        )?;
        Ok(())
    }

    /// List all sync checkpoints.
    pub fn list(conn: &Connection) -> Result<Vec<SyncCheckpoint>> {
        let mut stmt = conn.prepare(
            "SELECT relay_url, last_event_id, last_timestamp, updated_at
             FROM sync_checkpoints ORDER BY relay_url",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(SyncCheckpoint {
                relay_url: row.get(0)?,
                last_event_id: row.get(1)?,
                last_timestamp: row.get(2)?,
                updated_at: row.get(3)?,
            })
        })?;

        let mut checkpoints = Vec::new();
        for row in rows {
            checkpoints.push(row?);
        }
        Ok(checkpoints)
    }

    /// Delete a checkpoint for a relay.
    pub fn delete(conn: &Connection, relay_url: &str) -> Result<bool> {
        let changes = conn.execute(
            "DELETE FROM sync_checkpoints WHERE relay_url = ?1",
            params![relay_url],
        )?;
        Ok(changes > 0)
    }
}

// --- ProcessedEventsRepo — dedup of already-handled relay events ---

/// Tracks relay event ids we've already processed (decrypted, or that failed
/// with SecretReuseError = already decrypted earlier) so the catch-up backfill
/// never re-decrypts them. Bounded; pruned oldest-first.
pub struct ProcessedEventsRepo;

impl ProcessedEventsRepo {
    pub fn is_processed(conn: &Connection, event_id: &str) -> bool {
        conn.query_row(
            "SELECT 1 FROM processed_events WHERE event_id = ?1",
            params![event_id],
            |_| Ok(()),
        ).is_ok()
    }

    pub fn mark(conn: &Connection, event_id: &str) {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let _ = conn.execute(
            "INSERT OR IGNORE INTO processed_events (event_id, processed_at) VALUES (?1, ?2)",
            params![event_id, now],
        );
    }

    /// Keep the table bounded: drop the oldest rows beyond `keep`.
    pub fn prune(conn: &Connection, keep: i64) {
        let _ = conn.execute(
            "DELETE FROM processed_events WHERE event_id NOT IN (
                 SELECT event_id FROM processed_events ORDER BY processed_at DESC LIMIT ?1
             )",
            params![keep],
        );
    }
}

// --- KeyValueRepo ---

/// Generic key-value store operations.
pub struct KeyValueRepo;

impl KeyValueRepo {
    /// Get a value by key.
    pub fn get(conn: &Connection, key: &str) -> Result<Option<String>> {
        let result = conn
            .query_row(
                "SELECT value FROM key_value_store WHERE key = ?1",
                params![key],
                |row| row.get(0),
            )
            .optional()?;
        Ok(result)
    }

    /// Set a value (insert or update).
    pub fn set(conn: &Connection, key: &str, value: &str) -> Result<()> {
        let now = now_rfc3339();
        conn.execute(
            "INSERT INTO key_value_store (key, value, updated_at)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(key) DO UPDATE SET
                 value = excluded.value,
                 updated_at = excluded.updated_at",
            params![key, value, now],
        )?;
        Ok(())
    }

    /// Delete a key.
    pub fn delete(conn: &Connection, key: &str) -> Result<bool> {
        let changes = conn.execute("DELETE FROM key_value_store WHERE key = ?1", params![key])?;
        Ok(changes > 0)
    }

    /// `(key, value)` of every entry whose key starts with `prefix`.
    pub fn list_prefix(conn: &Connection, prefix: &str) -> Result<Vec<(String, String)>> {
        // A range on the primary key instead of LIKE: no wildcard escaping, uses the index.
        let mut stmt = conn.prepare(
            "SELECT key, value FROM key_value_store WHERE key >= ?1 AND key < ?1 || char(1114111) ORDER BY key",
        )?;
        let rows = stmt.query_map(params![prefix], |row| Ok((row.get(0)?, row.get(1)?)))?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    /// List all key-value entries.
    pub fn list(conn: &Connection) -> Result<Vec<KeyValueEntry>> {
        let mut stmt = conn.prepare(
            "SELECT key, value, updated_at FROM key_value_store ORDER BY key",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(KeyValueEntry {
                key: row.get(0)?,
                value: row.get(1)?,
                updated_at: row.get(2)?,
            })
        })?;

        let mut entries = Vec::new();
        for row in rows {
            entries.push(row?);
        }
        Ok(entries)
    }
}

// --- MlsStorageRepo — persistent backing for OpenMLS MemoryStorage snapshot ---

/// CRUD operations for the `mls_storage_v1` table.
///
/// Stores a flat key→value map mirroring `openmls_memory_storage::MemoryStorage`
/// so MLS group state survives process kill / OOM. Snapshots are written as a
/// batch on each mutation. SQLCipher encrypts the whole table at rest.
pub struct MlsStorageRepo;

impl MlsStorageRepo {
    /// Replace the entire table with the given key→value map atomically.
    /// We accept the table-wipe cost because MemoryStorage snapshots are
    /// small (few KB per group) and avoid a tricky incremental diff.
    pub fn save_snapshot(conn: &Connection, snapshot: &[(Vec<u8>, Vec<u8>)]) -> Result<()> {
        let tx = conn.unchecked_transaction()?;
        // Incremental UPSERT instead of DELETE ALL + re-INSERT: the wipe
        // rewrote every entry on every persist — O(total) per call. With
        // several groups the MLS storage grows to hundreds of KB, and this
        // runs on every send/add/receive, blocking the DB connection and
        // freezing the UI. INSERT OR REPLACE is O(changed) — only modified
        // keys are written.
        {
            let mut stmt = tx.prepare(
                "INSERT INTO mls_storage_v1 (k, v) VALUES (?1, ?2)
                 ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            )?;
            for (k, v) in snapshot {
                stmt.execute(params![k, v])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    /// Read back the full key→value snapshot.
    pub fn load_snapshot(conn: &Connection) -> Result<Vec<(Vec<u8>, Vec<u8>)>> {
        let mut stmt = conn.prepare("SELECT k, v FROM mls_storage_v1")?;
        let rows = stmt.query_map([], |row| Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?)))?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }
}

// --- Convenience: new_* helpers for model construction ---

/// Helper to create a new [`Contact`] with current timestamps.
pub fn new_contact(
    id: String,
    display_name: Option<String>,
    nostr_pubkey: Vec<u8>,
    mls_credential: Option<Vec<u8>>,
) -> Contact {
    let now = now_rfc3339();
    Contact {
        id,
        display_name,
        nostr_pubkey,
        mls_credential,
        created_at: now.clone(),
        updated_at: now,
        bio: String::new(),
        avatar_path: String::new(),
        avatar_hash: String::new(),
        profile_version: 0,
        profile_updated_at: None,
    }
}

/// Helper to create a new [`Chat`] with current timestamps.
pub fn new_chat(id: String, chat_type: ChatType, name: Option<String>, epoch: i64) -> Chat {
    let now = now_rfc3339();
    Chat {
        id,
        chat_type,
        name,
        epoch,
        created_at: now.clone(),
        updated_at: now,
        pinned_at: None,
        muted_until: None,
        archived_at: None,
        unread_override: false,
        avatar_path: String::new(),
        avatar_hash: String::new(),
        description: String::new(),
        metadata_version: 0,
    }
}

/// Helper to create a new [`ChatMember`] with current timestamp.
pub fn new_chat_member(
    chat_id: String,
    contact_id: String,
    leaf_index: Option<i64>,
) -> ChatMember {
    ChatMember {
        chat_id,
        contact_id,
        leaf_index,
        joined_at: now_rfc3339(),
    }
}

/// Helper to create a new [`Message`] with a generated UUID and current timestamp.
pub fn new_message(
    chat_id: String,
    sender_id: String,
    msg_type: MessageType,
    content: Option<String>,
    reply_to: Option<String>,
    media_ref: Option<String>,
    ttl_seconds: Option<i64>,
    expires_at: Option<String>,
) -> Message {
    let now = now_rfc3339();
    Message {
        id: Uuid::new_v4().to_string(),
        chat_id,
        sender_id,
        msg_type,
        content,
        reply_to,
        media_ref,
        ttl_seconds,
        expires_at,
        created_at: now,
        edited_at: None,
        is_deleted: false,
        expiry_policy: None,
        read_at: None,
        consumed: false,
        delivered_at: None,
    }
}

/// Helper to create a new ephemeral [`Message`] with expiry policy.
///
/// For ViewOnce messages, `content` is set to `None` (plaintext never
/// written to disk — held in memory only).
pub fn new_ephemeral_message(
    chat_id: String,
    sender_id: String,
    msg_type: MessageType,
    content: Option<String>,
    reply_to: Option<String>,
    media_ref: Option<String>,
    ttl_seconds: Option<i64>,
    expires_at: Option<String>,
    expiry_policy: ExpiryPolicyType,
) -> Message {
    let now = now_rfc3339();
    // ViewOnce: plaintext never on disk
    let safe_content = match expiry_policy {
        ExpiryPolicyType::ViewOnce => None,
        _ => content,
    };
    Message {
        id: Uuid::new_v4().to_string(),
        chat_id,
        sender_id,
        msg_type,
        content: safe_content,
        reply_to,
        media_ref,
        ttl_seconds,
        expires_at,
        created_at: now,
        edited_at: None,
        is_deleted: false,
        expiry_policy: Some(expiry_policy),
        read_at: None,
        consumed: false,
        delivered_at: None,
    }
}

/// Helper to create a new [`MlsGroupRow`] with current timestamps.
pub fn new_mls_group_row(
    group_id: String,
    epoch: i64,
    ciphersuite: i64,
    is_active: bool,
) -> MlsGroupRow {
    let now = now_rfc3339();
    MlsGroupRow {
        group_id,
        epoch,
        ciphersuite,
        is_active,
        created_at: now.clone(),
        updated_at: now,
    }
}

/// Helper to create a new [`MlsKeyPackageRow`] with a generated UUID and current timestamp.
pub fn new_mls_key_package_row(hash_ref: Vec<u8>, key_package_data: Vec<u8>) -> MlsKeyPackageRow {
    MlsKeyPackageRow {
        id: Uuid::new_v4().to_string(),
        hash_ref,
        key_package_data,
        created_at: now_rfc3339(),
        is_consumed: false,
    }
}

// --- Outbox — guaranteed-delivery queue for outgoing MLS envelopes ---

/// One queued MLS envelope awaiting confirmed relay delivery.
pub struct OutboxEntry {
    pub id: i64,
    pub group_id: Vec<u8>,
    pub epoch: i64,
    pub ciphertext: Vec<u8>,
}

pub struct OutboxRepo;

impl OutboxRepo {
    /// Enqueue an envelope; returns its row id so the caller can delete it once
    /// the relay confirms acceptance.
    pub fn enqueue(conn: &Connection, group_id: &[u8], epoch: i64, ciphertext: &[u8]) -> Result<i64> {
        conn.execute(
            "INSERT INTO outbox (group_id, epoch, ciphertext, retry_count, created_at)
             VALUES (?1, ?2, ?3, 0, ?4)",
            params![group_id, epoch, ciphertext, now_rfc3339()],
        )?;
        Ok(conn.last_insert_rowid())
    }

    /// All queued entries, oldest first (preserves send order on replay).
    pub fn list(conn: &Connection) -> Result<Vec<OutboxEntry>> {
        let mut stmt = conn.prepare(
            "SELECT id, group_id, epoch, ciphertext FROM outbox ORDER BY id ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(OutboxEntry {
                id: r.get(0)?,
                group_id: r.get(1)?,
                epoch: r.get(2)?,
                ciphertext: r.get(3)?,
            })
        })?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    pub fn delete(conn: &Connection, id: i64) -> Result<()> {
        conn.execute("DELETE FROM outbox WHERE id = ?1", params![id])?;
        Ok(())
    }

    pub fn increment_retry(conn: &Connection, id: i64) -> Result<()> {
        conn.execute("UPDATE outbox SET retry_count = retry_count + 1 WHERE id = ?1", params![id])?;
        Ok(())
    }

    pub fn count(conn: &Connection) -> Result<i64> {
        Ok(conn.query_row("SELECT COUNT(*) FROM outbox", [], |r| r.get(0))?)
    }
}

// --- Pending sends — defer-encryption outgoing queue ---

/// One queued OUTGOING message awaiting MLS-encrypt + publish (plaintext).
pub struct PendingSend {
    pub id: i64,
    pub chat_id: String,
    pub msg_id: String,
    pub payload: Vec<u8>,
    pub created_at: String,
}

pub struct PendingSendRepo;

impl PendingSendRepo {
    pub fn enqueue(conn: &Connection, chat_id: &str, msg_id: &str, payload: &[u8]) -> Result<i64> {
        conn.execute(
            "INSERT INTO pending_sends (chat_id, msg_id, payload, created_at) VALUES (?1, ?2, ?3, ?4)",
            params![chat_id, msg_id, payload, now_rfc3339()],
        )?;
        Ok(conn.last_insert_rowid())
    }

    /// All queued sends, oldest first (preserves compose order on flush).
    pub fn list(conn: &Connection) -> Result<Vec<PendingSend>> {
        let mut stmt = conn.prepare(
            "SELECT id, chat_id, msg_id, payload, created_at FROM pending_sends ORDER BY id ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(PendingSend { id: r.get(0)?, chat_id: r.get(1)?, msg_id: r.get(2)?, payload: r.get(3)?, created_at: r.get(4)? })
        })?;
        let mut out = Vec::new();
        for row in rows { out.push(row?); }
        Ok(out)
    }

    pub fn delete(conn: &Connection, id: i64) -> Result<()> {
        conn.execute("DELETE FROM pending_sends WHERE id = ?1", params![id])?;
        Ok(())
    }

    /// Drops the queued send of [msg_id]; true when it had not gone out yet.
    pub fn delete_by_msg(conn: &Connection, msg_id: &str) -> Result<bool> {
        Ok(conn.execute("DELETE FROM pending_sends WHERE msg_id = ?1", params![msg_id])? > 0)
    }

    pub fn count(conn: &Connection) -> Result<i64> {
        Ok(conn.query_row("SELECT COUNT(*) FROM pending_sends", [], |r| r.get(0))?)
    }
}

// --- Pending media — offline media send queue ---

pub struct PendingMedia {
    pub id: i64,
    pub chat_id: String,
    pub msg_id: String,
    pub kind: String,
    pub payload: String,
    pub status: String,
    pub failure_reason: Option<String>,
    pub attempts: i64,
    /// RFC3339 instant before which this row must NOT be retried (exponential
    /// backoff). Without it a transient failure re-uploaded the same blob on
    /// every flush tick and starved the runtime.
    pub next_retry: Option<String>,
    /// True once the blobs are on Blossom. A retry then publishes ONLY — Blossom
    /// is content-addressed, so re-uploading the same bytes is pure waste.
    pub blob_uploaded: bool,
    /// The MLS-facing media ref produced by the upload. All that the publish step
    /// needs. `None` until the blobs are up.
    pub media_ref: Option<String>,
    pub last_error: Option<String>,
}

pub struct PendingMediaRepo;

impl PendingMediaRepo {
    /// Enqueue a message — exactly one row.
    ///
    /// It used to be a plain insert, and every failure added ANOTHER row
    /// for the same message. The queue walker works off a snapshot taken
    /// at the start of its pass, so the second row went out after the
    /// message was already published via the first — the recipient saw the file twice.
    ///
    /// Re-queueing the same message is not new work but a confirmation
    /// of the old one: the row returns to the queue with fresh content,
    /// the backoff and the claim cleared.
    pub fn enqueue(conn: &Connection, chat_id: &str, msg_id: &str, kind: &str, payload: &str) -> Result<i64> {
        conn.execute(
            "INSERT INTO pending_media (chat_id, msg_id, kind, payload, created_at) \
             VALUES (?1,?2,?3,?4,?5) \
             ON CONFLICT(msg_id) DO UPDATE SET \
                chat_id = excluded.chat_id, kind = excluded.kind, payload = excluded.payload, \
                status = 'queued', next_retry = NULL, claimed_at = NULL",
            params![chat_id, msg_id, kind, payload, now_rfc3339()],
        )?;
        Ok(conn.last_insert_rowid())
    }

    /// Claim-lease duration. A claim that outlived its process MUST
    /// expire: otherwise the send would forever belong to an owner that
    /// no longer exists. An hour is a margin over the longest honest send
    /// (observed maximum: minutes) yet small enough not to delay recovery.
    pub const CLAIM_LEASE_SECS: i64 = 3600;

    /// Claim the right to send a row. `true` — the right is granted.
    ///
    /// This is the only gate to sending: claiming and the busy check are
    /// one DB write, so two queue passes cannot take the same row however
    /// they overlap in time. The "row still exists" check is in the same
    /// write: published rows are deleted, leaving nothing to claim — a
    /// list snapshot taken before publication sends nothing twice.
    pub fn claim(conn: &Connection, id: i64) -> Result<bool> {
        let now = chrono::Utc::now().timestamp();
        let n = conn.execute(
            "UPDATE pending_media SET status = 'uploading', claimed_at = ?2 \
             WHERE id = ?1 AND (status <> 'uploading' OR claimed_at IS NULL OR claimed_at < ?3)",
            params![id, now, now - Self::CLAIM_LEASE_SECS],
        )?;
        Ok(n == 1)
    }

    /// Release the claim without touching the rest of the row's state.
    pub fn release(conn: &Connection, id: i64) -> Result<()> {
        conn.execute(
            "UPDATE pending_media SET status = 'queued', claimed_at = NULL \
             WHERE id = ?1 AND status = 'uploading'",
            params![id],
        )?;
        Ok(())
    }

    /// All rows, oldest first (any status). `flush_pending_media` filters out
    /// `failed` ones itself so it doesn't re-hammer a permanently-rejected blob.
    pub fn list(conn: &Connection) -> Result<Vec<PendingMedia>> {
        let mut stmt = conn.prepare(
            "SELECT id, chat_id, msg_id, kind, payload, status, failure_reason, attempts, \
                    next_retry, blob_uploaded, media_ref, last_error \
             FROM pending_media ORDER BY id ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(PendingMedia {
                id: r.get(0)?, chat_id: r.get(1)?, msg_id: r.get(2)?, kind: r.get(3)?,
                payload: r.get(4)?, status: r.get(5)?, failure_reason: r.get(6)?, attempts: r.get(7)?,
                next_retry: r.get(8)?,
                blob_uploaded: r.get::<_, i64>(9)? != 0,
                media_ref: r.get(10)?,
                last_error: r.get(11)?,
            })
        })?;
        let mut out = Vec::new();
        for row in rows { out.push(row?); }
        Ok(out)
    }

    /// msg_ids that are still "in flight" (queued/uploading) — NOT the ones parked
    /// as `failed`. Drives the sender-side "sending…" indicator: a failed upload
    /// must stop spinning and show Retry instead.
    pub fn active_msg_ids(conn: &Connection) -> Result<Vec<String>> {
        let mut stmt = conn.prepare(
            "SELECT msg_id FROM pending_media WHERE status != 'failed'",
        )?;
        let rows = stmt.query_map([], |r| r.get::<_, String>(0))?;
        let mut out = Vec::new();
        for row in rows { out.push(row?); }
        Ok(out)
    }

    /// Park a row as permanently failed (kept for a manual Retry).
    pub fn mark_failed(conn: &Connection, msg_id: &str, reason: &str) -> Result<()> {
        conn.execute(
            "UPDATE pending_media SET status = 'failed', failure_reason = ?2, \
             attempts = attempts + 1, last_attempt = ?3 WHERE msg_id = ?1",
            params![msg_id, reason, now_rfc3339()],
        )?;
        Ok(())
    }

    /// Record a transient failure (stays queued, will be retried later).
    pub fn mark_transient(conn: &Connection, msg_id: &str, reason: &str) -> Result<()> {
        conn.execute(
            "UPDATE pending_media SET status = 'queued', failure_reason = ?2, \
             attempts = attempts + 1, last_attempt = ?3 WHERE msg_id = ?1",
            params![msg_id, reason, now_rfc3339()],
        )?;
        Ok(())
    }

    /// The blobs are on Blossom. Remember the media ref so a later retry — even
    /// after a process restart — performs the PUBLISH ONLY and never re-uploads
    /// bytes that are already content-addressed on the server.
    pub fn mark_blob_uploaded(conn: &Connection, msg_id: &str, media_ref: &str) -> Result<()> {
        conn.execute(
            "UPDATE pending_media SET blob_uploaded = 1, media_ref = ?2 WHERE msg_id = ?1",
            params![msg_id, media_ref],
        )?;
        Ok(())
    }

    /// Transient failure WITH an exponential-backoff window: the row is not
    /// eligible for another attempt until [`next_retry`].
    pub fn mark_transient_until(
        conn: &Connection,
        msg_id: &str,
        reason: &str,
        next_retry: &str,
    ) -> Result<()> {
        conn.execute(
            "UPDATE pending_media SET status = 'queued', failure_reason = ?2, \
             attempts = attempts + 1, last_attempt = ?3, next_retry = ?4 WHERE msg_id = ?1",
            params![msg_id, reason, now_rfc3339(), next_retry],
        )?;
        Ok(())
    }

    /// Reset a failed row back to `queued` for a user-initiated Retry.
    pub fn requeue_by_msg(conn: &Connection, msg_id: &str) -> Result<()> {
        conn.execute(
            "UPDATE pending_media SET status = 'queued', failure_reason = NULL, next_retry = NULL \
             WHERE msg_id = ?1",
            params![msg_id],
        )?;
        Ok(())
    }

    pub fn delete(conn: &Connection, id: i64) -> Result<()> {
        conn.execute("DELETE FROM pending_media WHERE id = ?1", params![id])?;
        Ok(())
    }

    pub fn delete_by_msg(conn: &Connection, msg_id: &str) -> Result<()> {
        conn.execute("DELETE FROM pending_media WHERE msg_id = ?1", params![msg_id])?;
        Ok(())
    }
}

/// Media encryption keys for an unfinished send.
///
/// Kept for one property: after process death the BYTE-FOR-BYTE same
/// encrypted object must be reproducible from the immutable source.
/// Without the key, re-derivation would give different ciphertext and a
/// different object identity — resume of missing replicas would be
/// impossible, with nothing to match the copy against.
///
/// The record has no encryption of its own: the whole DB is under
/// SQLCipher, so the row is already encrypted at rest and readable only
/// after unlock. The key is never logged and never enters error texts.
pub struct MediaSendKeyRepo;

impl MediaSendKeyRepo {
    /// The canonical identifier. Built HERE, not accepted as a string
    /// from outside: an arbitrary caller string would allow reading and
    /// overwriting foreign records.
    pub fn identity(send_id: &str, item_index: u32, revision: u64) -> String {
        format!("{send_id}#{item_index}#r{revision}")
    }

    pub fn store(
        conn: &Connection,
        send_id: &str,
        item_index: u32,
        revision: u64,
        key_material: &[u8],
    ) -> Result<()> {
        let identity = Self::identity(send_id, item_index, revision);
        conn.execute(
            "INSERT INTO media_send_keys (identity, key_material, created_at)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(identity) DO UPDATE SET key_material = excluded.key_material",
            rusqlite::params![identity, key_material, chrono::Utc::now().timestamp()],
        )?;
        Ok(())
    }

    pub fn load(
        conn: &Connection,
        send_id: &str,
        item_index: u32,
        revision: u64,
    ) -> Result<Option<Vec<u8>>> {
        let identity = Self::identity(send_id, item_index, revision);
        let mut stmt = conn.prepare("SELECT key_material FROM media_send_keys WHERE identity = ?1")?;
        let mut rows = stmt.query(rusqlite::params![identity])?;
        match rows.next()? {
            Some(row) => Ok(Some(row.get(0)?)),
            None => Ok(None),
        }
    }

    /// This key's duty is done — the secret must disappear.
    pub fn delete(
        conn: &Connection,
        send_id: &str,
        item_index: u32,
        revision: u64,
    ) -> Result<()> {
        let identity = Self::identity(send_id, item_index, revision);
        conn.execute("DELETE FROM media_send_keys WHERE identity = ?1", rusqlite::params![identity])?;
        Ok(())
    }

    /// Drop all send keys — e.g. when a job is cancelled wholesale.
    pub fn delete_for_send(conn: &Connection, send_id: &str) -> Result<usize> {
        Ok(conn.execute(
            "DELETE FROM media_send_keys WHERE identity LIKE ?1 || '#%'",
            rusqlite::params![send_id],
        )?)
    }
}

/// Trusted record of a transport object of an unfinished send.
///
/// Holds what the core has NO RIGHT to accept from outside: the path
/// to the immutable output and the expected object identity. Kotlin
/// passes only the send identity; everything else the core finds here
/// itself. Otherwise the replication primitive would become "read and send any file I name".
pub struct MediaSendObjectRepo;

/// What is known about one transport object.
#[derive(Debug, Clone)]
pub struct MediaSendObject {
    pub send_id: String,
    pub item_index: u32,
    pub revision: u64,
    pub object_index: u32,
    pub object_total: u32,
    pub output_path: String,
    pub expected_id: String,
}

impl MediaSendObjectRepo {
    fn identity(send_id: &str, item_index: u32, revision: u64, object_index: u32) -> String {
        format!("{send_id}#{item_index}#r{revision}#{object_index}")
    }

    pub fn store(conn: &Connection, o: &MediaSendObject) -> Result<()> {
        conn.execute(
            "INSERT INTO media_send_objects
                (identity, send_id, item_index, revision, object_index, object_total,
                 output_path, expected_id, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
             ON CONFLICT(identity) DO UPDATE SET
                object_total = excluded.object_total,
                output_path  = excluded.output_path,
                expected_id  = excluded.expected_id",
            rusqlite::params![
                Self::identity(&o.send_id, o.item_index, o.revision, o.object_index),
                o.send_id, o.item_index, o.revision as i64, o.object_index, o.object_total,
                o.output_path, o.expected_id, chrono::Utc::now().timestamp(),
            ],
        )?;
        Ok(())
    }

    pub fn load(
        conn: &Connection,
        send_id: &str,
        item_index: u32,
        revision: u64,
        object_index: u32,
    ) -> Result<Option<MediaSendObject>> {
        let identity = Self::identity(send_id, item_index, revision, object_index);
        let mut stmt = conn.prepare(
            "SELECT send_id, item_index, revision, object_index, object_total, output_path, expected_id
             FROM media_send_objects WHERE identity = ?1",
        )?;
        let mut rows = stmt.query(rusqlite::params![identity])?;
        match rows.next()? {
            Some(r) => Ok(Some(MediaSendObject {
                send_id: r.get(0)?,
                item_index: r.get(1)?,
                revision: r.get::<_, i64>(2)? as u64,
                object_index: r.get(3)?,
                object_total: r.get(4)?,
                output_path: r.get(5)?,
                expected_id: r.get(6)?,
            })),
            None => Ok(None),
        }
    }

    /// The send finished or was cancelled — the records are no longer needed.
    ///
    /// Album elements travel under composite ids of the form
    /// `<message>#<index>`, so cancelling a message must remove them too:
    /// otherwise chunk confirmations nobody needs would stay on disk
    /// after the album was cancelled.
    /// Forget what storage confirmed for one output: its bytes changed, so
    /// those chunks belong to another encryption.
    pub fn delete_for_output(conn: &Connection, send_id: &str, item_index: u32, revision: u64) -> Result<usize> {
        Ok(conn.execute(
            "DELETE FROM media_send_objects WHERE send_id = ?1 AND item_index = ?2 AND revision = ?3",
            rusqlite::params![send_id, item_index, revision as i64],
        )?)
    }

    pub fn delete_for_send(conn: &Connection, send_id: &str) -> Result<usize> {
        Ok(conn.execute(
            "DELETE FROM media_send_objects WHERE send_id = ?1 OR send_id LIKE ?1 || '#%'",
            rusqlite::params![send_id],
        )?)
    }

    /// Record a batch of confirmations in ONE transaction.
    ///
    /// A batch, not one-by-one, because of cost: every separate
    /// transaction is a disk flush, and chunks arrive at 256 KiB each.
    /// Writing per chunk would make the disk work hundreds of times per
    /// video for no gain: losing a batch of several chunks on a crash
    /// costs no more than losing one — both cases simply re-send those chunks.
    ///
    /// The transaction IS the durability boundary. Before `commit` the
    /// confirmations do not exist: a process dying before it re-uploads
    /// those chunks — safe, no data corruption. A passed `commit` means
    /// those chunks never go to the network again.
    ///
    /// The row is created or updated in place. There is deliberately no
    /// separate "declared" record before sending: it would cost as much
    /// as the confirmation itself and buy nothing — a declared-but-
    /// unaccepted chunk and an absent chunk require the same action. The
    /// declared/accepted distinction survives: `confirmed_at` carries it,
    /// and only it grants the right to skip a chunk.
    pub fn confirm_batch(conn: &mut Connection, batch: &[ChunkConfirmation]) -> Result<usize> {
        if batch.is_empty() {
            return Ok(0);
        }
        let tx = conn.transaction()?;
        let now = chrono::Utc::now().timestamp();
        let mut done = 0usize;
        {
            let mut stmt = tx.prepare(
                "INSERT INTO media_send_objects
                    (identity, send_id, item_index, revision, object_index, object_total,
                     output_path, expected_id, created_at,
                     confirmed_at, plaintext_bytes, ciphertext_bytes, chunk_hash, locations_json)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?9, ?10, ?11, ?12, ?13)
                 ON CONFLICT(identity) DO UPDATE SET
                    object_total     = excluded.object_total,
                    output_path      = excluded.output_path,
                    expected_id      = excluded.expected_id,
                    confirmed_at     = excluded.confirmed_at,
                    plaintext_bytes  = excluded.plaintext_bytes,
                    ciphertext_bytes = excluded.ciphertext_bytes,
                    chunk_hash       = excluded.chunk_hash,
                    locations_json   = excluded.locations_json",
            )?;
            for c in batch {
                done += stmt.execute(rusqlite::params![
                    Self::identity(&c.send_id, c.item_index, c.revision, c.object_index),
                    c.send_id,
                    c.item_index,
                    c.revision as i64,
                    c.object_index,
                    c.object_total,
                    c.output_path,
                    c.transport_object_id,
                    now,
                    c.plaintext_bytes as i64,
                    c.ciphertext_bytes as i64,
                    c.chunk_hash,
                    c.locations_json,
                ])?;
            }
        }
        tx.commit()?;
        Ok(done)
    }

    /// Confirmed chunks of one send, in ascending index order.
    ///
    /// A row missing any required field is skipped silently: an
    /// incomplete checkpoint cannot be trusted, and the only cost of
    /// skipping is re-sending that chunk. Panicking here would turn one
    /// corrupt row into "the file can never be sent at all".
    pub fn load_confirmed(
        conn: &Connection,
        send_id: &str,
        item_index: u32,
        revision: u64,
    ) -> Result<Vec<ChunkConfirmation>> {
        let mut stmt = conn.prepare(
            "SELECT object_index, expected_id, plaintext_bytes, ciphertext_bytes,
                    chunk_hash, locations_json, object_total, output_path
             FROM media_send_objects
             WHERE send_id = ?1 AND item_index = ?2 AND revision = ?3
               AND confirmed_at IS NOT NULL
             ORDER BY object_index",
        )?;
        let rows = stmt.query_map(
            rusqlite::params![send_id, item_index, revision as i64],
            |r| {
                Ok((
                    r.get::<_, u32>(0)?,
                    r.get::<_, Option<String>>(1)?,
                    r.get::<_, Option<i64>>(2)?,
                    r.get::<_, Option<i64>>(3)?,
                    r.get::<_, Option<String>>(4)?,
                    r.get::<_, Option<String>>(5)?,
                    r.get::<_, u32>(6)?,
                    r.get::<_, String>(7)?,
                ))
            },
        )?;

        let mut out = Vec::new();
        for row in rows {
            let (object_index, id, plain, cipher, hash, locations, object_total, output_path) = row?;
            let (Some(id), Some(plain), Some(cipher), Some(hash), Some(locations)) =
                (id, plain, cipher, hash, locations)
            else {
                tracing::warn!(
                    "checkpoint: chunk {object_index} of send {send_id} is incomplete, it will be sent again"
                );
                continue;
            };
            if id.is_empty() || hash.is_empty() || locations.is_empty() || plain <= 0 {
                tracing::warn!(
                    "checkpoint: chunk {object_index} of send {send_id} is corrupt, it will be sent again"
                );
                continue;
            }
            out.push(ChunkConfirmation {
                send_id: send_id.to_string(),
                item_index,
                revision,
                object_index,
                object_total,
                output_path,
                transport_object_id: id,
                plaintext_bytes: plain as u64,
                ciphertext_bytes: cipher.max(0) as u64,
                chunk_hash: hash,
                locations_json: locations,
            });
        }
        Ok(out)
    }
}

/// Confirmation of one chunk: the storage accepted EXACTLY THESE bytes.
///
/// Everything needed to assemble the final media ref without ever
/// re-sending this chunk. The recipient must not be able to tell a
/// resumed send from a fresh one by data completeness.
///
/// There are no secrets here and cannot be: key, ciphertext and source
/// bytes never enter — only identities, sizes and addresses, exactly
/// what the public ref already carries.
#[derive(Debug, Clone)]
pub struct ChunkConfirmation {
    pub send_id: String,
    pub item_index: u32,
    pub revision: u64,
    pub object_index: u32,
    pub object_total: u32,
    /// The immutable output the chunk is reproduced from, byte for byte.
    pub output_path: String,
    /// The identity the storage returned AND that MATCHED the expected one.
    pub transport_object_id: String,
    pub plaintext_bytes: u64,
    pub ciphertext_bytes: u64,
    /// BLAKE3 of the chunk's source bytes, hex.
    pub chunk_hash: String,
    /// Copy addresses. Serialized with the working `ChunkLocation` format,
    /// not a private scheme: the same representation the ref carries.
    pub locations_json: String,
}

#[cfg(test)]
mod t4_checkpoint {
    use super::*;
    use crate::pool::DatabasePool;

    fn fresh_pool() -> DatabasePool {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_ckpt_{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&p, "").expect("open test db");
        let _ = std::fs::remove_file(&p);
        {
            let conn = pool.connection();
            crate::migrations::run_migrations(&conn).expect("migrations");
        }
        pool
    }

    fn conf(send: &str, revision: u64, index: u32) -> ChunkConfirmation {
        ChunkConfirmation {
            send_id: send.into(),
            item_index: 0,
            revision,
            object_index: index,
            object_total: 4,
            output_path: "/data/0.r7.mp4".into(),
            transport_object_id: format!("{index:064x}"),
            plaintext_bytes: 262_144,
            ciphertext_bytes: 262_160,
            chunk_hash: "ab".repeat(32),
            locations_json: r#"[{"relay":"https://s","url":"https://s/x"}]"#.into(),
        }
    }

    /// What one transaction wrote is read back whole and in order.
    #[test]
    fn confirmations_survive_and_read_back_in_order() {
        let pool = fresh_pool();
        let mut conn = pool.connection();
        let batch: Vec<_> = [2u32, 0, 1].iter().map(|i| conf("s1", 7, *i)).collect();
        assert_eq!(MediaSendObjectRepo::confirm_batch(&mut conn, &batch).unwrap(), 3);

        let got = MediaSendObjectRepo::load_confirmed(&conn, "s1", 0, 7).unwrap();
        assert_eq!(got.iter().map(|c| c.object_index).collect::<Vec<_>>(), vec![0, 1, 2]);
        assert_eq!(got[0].plaintext_bytes, 262_144);
        assert_eq!(got[0].transport_object_id, format!("{:064x}", 0));
    }

    /// A declared but NOT confirmed chunk grants no skip.
    ///
    /// That is the distinction migration 019 exists for: a chunk row can
    /// exist before any send, and taking it as proof of acceptance would
    /// hand the recipient a ref to chunks that are not in storage.
    #[test]
    fn an_announced_chunk_is_not_confirmed() {
        let pool = fresh_pool();
        let conn = pool.connection();
        MediaSendObjectRepo::store(&conn, &MediaSendObject {
            send_id: "s2".into(), item_index: 0, revision: 7,
            object_index: 0, object_total: 4,
            output_path: "/data/0.r7.mp4".into(),
            expected_id: format!("{:064x}", 0),
        }).unwrap();

        assert!(
            MediaSendObjectRepo::load_confirmed(&conn, "s2", 0, 7).unwrap().is_empty(),
            "a row without confirmation must stay invisible to resume",
        );
    }

    /// An empty batch opens no transaction and changes nothing.
    #[test]
    fn an_empty_batch_does_nothing() {
        let pool = fresh_pool();
        let mut conn = pool.connection();
        assert_eq!(MediaSendObjectRepo::confirm_batch(&mut conn, &[]).unwrap(), 0);
    }

    /// The revision changed — old confirmations do not apply to the new output.
    #[test]
    fn confirmations_of_another_revision_are_not_visible() {
        let pool = fresh_pool();
        let mut conn = pool.connection();
        MediaSendObjectRepo::confirm_batch(&mut conn, &[conf("s3", 7, 0)]).unwrap();

        assert_eq!(MediaSendObjectRepo::load_confirmed(&conn, "s3", 0, 7).unwrap().len(), 1);
        assert!(
            MediaSendObjectRepo::load_confirmed(&conn, "s3", 0, 8).unwrap().is_empty(),
            "a new revision must start clean",
        );
        assert!(
            MediaSendObjectRepo::load_confirmed(&conn, "other", 0, 7).unwrap().is_empty(),
            "another send must not see these confirmations",
        );
    }

    /// A corrupt row drops out silently; the rest keep working.
    ///
    /// Panicking here would turn one broken record into "the file can
    /// never be sent at all"; the cost of skipping is re-sending that chunk.
    #[test]
    fn a_corrupt_row_does_not_break_the_rest() {
        let pool = fresh_pool();
        let mut conn = pool.connection();
        let batch: Vec<_> = (0..3u32).map(|i| conf("s4", 7, i)).collect();
        MediaSendObjectRepo::confirm_batch(&mut conn, &batch).unwrap();

        // Chunk 1 loses its addresses, chunk 2 its size.
        conn.execute(
            "UPDATE media_send_objects SET locations_json = '' WHERE object_index = 1 AND send_id = 's4'",
            [],
        ).unwrap();
        conn.execute(
            "UPDATE media_send_objects SET plaintext_bytes = NULL WHERE object_index = 2 AND send_id = 's4'",
            [],
        ).unwrap();

        let got = MediaSendObjectRepo::load_confirmed(&conn, "s4", 0, 7).unwrap();
        assert_eq!(got.iter().map(|c| c.object_index).collect::<Vec<_>>(), vec![0]);
    }

    /// Cancel and success clear the send state wholesale.
    #[test]
    fn clearing_removes_the_sends_confirmations() {
        let pool = fresh_pool();
        let mut conn = pool.connection();
        let batch: Vec<_> = (0..4u32).map(|i| conf("s5", 7, i)).collect();
        MediaSendObjectRepo::confirm_batch(&mut conn, &batch).unwrap();

        assert_eq!(MediaSendObjectRepo::delete_for_send(&conn, "s5").unwrap(), 4);
        assert!(MediaSendObjectRepo::load_confirmed(&conn, "s5", 0, 7).unwrap().is_empty());
    }

    /// Re-confirming the same chunk grows no rows.
    #[test]
    fn a_repeated_confirmation_updates_not_duplicates() {
        let pool = fresh_pool();
        let mut conn = pool.connection();
        MediaSendObjectRepo::confirm_batch(&mut conn, &[conf("s6", 7, 0)]).unwrap();
        MediaSendObjectRepo::confirm_batch(&mut conn, &[conf("s6", 7, 0)]).unwrap();
        assert_eq!(MediaSendObjectRepo::load_confirmed(&conn, "s6", 0, 7).unwrap().len(), 1);
    }
}

/// Exclusive ownership of the media queue.
///
/// Guards a defect proven on device: a file reached the recipient twice
/// — 150 chunks uploaded and published, and 25 seconds later the same
/// 150 chunks re-uploaded and the message published again.
#[cfg(test)]
mod t4_single_owner {
    use super::*;
    use crate::pool::DatabasePool;

    fn fresh_pool() -> DatabasePool {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_owner_{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&p, "").expect("open test db");
        let _ = std::fs::remove_file(&p);
        pool
    }

    /// Re-queueing does not multiply rows. Multiplication is exactly what
    /// caused the second send: success deletes a message's rows, but the
    /// queue walker follows a snapshot and knows nothing of the deletion.
    #[test]
    fn enqueueing_again_adds_no_rows() {
        let pool = fresh_pool();
        let conn = pool.connection();
        for n in 0..5 {
            PendingMediaRepo::enqueue(&conn, "chat", "msg-1", "single", &format!("{{\"n\":{n}}}"))
                .expect("enqueue");
        }
        let rows = PendingMediaRepo::list(&conn).expect("list");
        assert_eq!(rows.len(), 1, "one message, one queue row");
        assert!(rows[0].payload.contains("\"n\":4"), "the content must be the latest");
    }

    /// A claim is granted exactly once. Two queue passes, however they
    /// overlap in time, cannot take the same row into work.
    #[test]
    fn only_one_claim_wins() {
        let pool = fresh_pool();
        let conn = pool.connection();
        let id = PendingMediaRepo::enqueue(&conn, "chat", "msg-1", "single", "{}").expect("enqueue");

        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"), "the first claim must win");
        assert!(!PendingMediaRepo::claim(&conn, id).expect("claim"), "a second claim on the same row must fail");
        assert!(!PendingMediaRepo::claim(&conn, id).expect("claim"), "and so must a third");
    }

    /// Published rows are deleted — there is nothing left to claim. That
    /// is the guard against a list snapshot taken before publication.
    #[test]
    fn a_published_row_cannot_be_claimed() {
        let pool = fresh_pool();
        let conn = pool.connection();
        let id = PendingMediaRepo::enqueue(&conn, "chat", "msg-1", "single", "{}").expect("enqueue");
        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"));

        // Successful publish.
        PendingMediaRepo::delete(&conn, id).expect("delete after success");

        assert!(
            !PendingMediaRepo::claim(&conn, id).expect("claim"),
            "the row is gone, nothing to send again",
        );
    }

    /// Failure returns the row to the queue: a retry must remain possible.
    #[test]
    fn a_failure_releases_the_claim() {
        let pool = fresh_pool();
        let conn = pool.connection();
        let id = PendingMediaRepo::enqueue(&conn, "chat", "msg-1", "single", "{}").expect("enqueue");
        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"));

        PendingMediaRepo::mark_transient(&conn, "msg-1", "network").expect("transient failure");
        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"), "after a failure the row is free again");

        PendingMediaRepo::release(&conn, id).expect("release");
        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"), "an explicit release frees the row too");
    }

    /// Permanent failure parks the row — but the claim is lifted,
    /// otherwise a manual retry would hit the right of a nonexistent owner.
    #[test]
    fn a_final_failure_does_not_hold_the_claim() {
        let pool = fresh_pool();
        let conn = pool.connection();
        let id = PendingMediaRepo::enqueue(&conn, "chat", "msg-1", "single", "{}").expect("enqueue");
        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"));

        PendingMediaRepo::mark_failed(&conn, "msg-1", "refused").expect("final failure");
        PendingMediaRepo::requeue_by_msg(&conn, "msg-1").expect("manual retry");

        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"), "a manual retry must win the claim");
    }

    /// A claim that outlived its process MUST expire: otherwise the send
    /// would forever belong to an owner that no longer exists.
    #[test]
    fn an_expired_claim_goes_to_a_new_owner() {
        let pool = fresh_pool();
        let conn = pool.connection();
        let id = PendingMediaRepo::enqueue(&conn, "chat", "msg-1", "single", "{}").expect("enqueue");
        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"));

        let stale = chrono::Utc::now().timestamp() - PendingMediaRepo::CLAIM_LEASE_SECS - 60;
        conn.execute("UPDATE pending_media SET claimed_at = ?1 WHERE id = ?2", params![stale, id])
            .expect("age the claim");

        assert!(
            PendingMediaRepo::claim(&conn, id).expect("claim"),
            "an expired lease must hand the row to a new owner",
        );
    }

    /// Opening the DB releases the previous run's claims: after a restart
    /// there is nothing to wait the lease out for — no old owner remains.
    #[test]
    fn opening_the_database_releases_claims_of_the_previous_run() {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_owner_{}.db", uuid::Uuid::new_v4()));

        let id = {
            let pool = DatabasePool::open(&p, "").expect("open");
            let conn = pool.connection();
            let id = PendingMediaRepo::enqueue(&conn, "chat", "msg-1", "single", "{}").expect("enqueue");
            assert!(PendingMediaRepo::claim(&conn, id).expect("claim"));
            id
        };

        let pool = DatabasePool::open(&p, "").expect("reopen");
        let conn = pool.connection();
        let rows = PendingMediaRepo::list(&conn).expect("list");
        assert_eq!(rows[0].status, "queued", "the previous run's claim must be released");
        assert!(PendingMediaRepo::claim(&conn, id).expect("claim"));
        drop(conn);
        drop(pool);
        let _ = std::fs::remove_file(&p);
    }
}

// --- Per-recipient delivery (delivery_fanout) ---

/// The state of SENDING an envelope to one recipient.
///
/// `RelayAccepted` — the relay accepted the envelope, nothing more.
/// Whether the recipient received and parsed it is a different
/// question, living in `delivery_acks`. Never mix them: relay acceptance promises no delivery.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FanoutState {
    Pending,
    InFlight,
    RelayAccepted,
    RetryWait,
    PermanentFailed,
    /// The member was removed from the group — delivery stopped.
    ///
    /// Not delivered and never will be: the removed member can still
    /// decrypt old ciphertext (they held that epoch's keys), so
    /// finishing delivery to them would hand content to someone expelled.
    CanceledRemoved,
}

impl FanoutState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::InFlight => "in_flight",
            Self::RelayAccepted => "relay_accepted",
            Self::RetryWait => "retry_wait",
            Self::PermanentFailed => "permanent_failed",
            Self::CanceledRemoved => "canceled_removed",
        }
    }

    pub fn from_str(s: &str) -> Self {
        match s {
            "in_flight" => Self::InFlight,
            "relay_accepted" => Self::RelayAccepted,
            "retry_wait" => Self::RetryWait,
            "permanent_failed" => Self::PermanentFailed,
            "canceled_removed" => Self::CanceledRemoved,
            _ => Self::Pending,
        }
    }

    /// Work on this row is not finished yet.
    pub fn outstanding(self) -> bool {
        matches!(self, Self::Pending | Self::InFlight | Self::RetryWait)
    }
}

#[derive(Debug, Clone)]
pub struct FanoutRow {
    pub chat_id: String,
    pub msg_id: String,
    pub recipient_sig: String,
    pub state: FanoutState,
    pub attempts: i64,
    pub last_error_class: String,
}

pub struct DeliveryFanoutRepo;

impl DeliveryFanoutRepo {
    /// Recipient snapshot at the moment the logical message was created.
    ///
    /// Membership is fixed HERE and never recomputed. Otherwise a
    /// message composed before a membership change would retroactively
    /// gain recipients: a later-added member would get what was not written to them.
    pub fn snapshot(
        conn: &Connection,
        chat_id: &str,
        msg_id: &str,
        recipients: &[String],
    ) -> Result<usize> {
        let now = now_rfc3339();
        let mut n = 0usize;
        for sig in recipients {
            conn.execute(
                "INSERT INTO delivery_fanout \
                 (chat_id, msg_id, recipient_sig, state, attempts, last_attempt_at) \
                 VALUES (?1, ?2, ?3, 'pending', 0, ?4) \
                 ON CONFLICT(chat_id, msg_id, recipient_sig) DO NOTHING",
                params![chat_id, msg_id, sig, now],
            )?;
            n += 1;
        }
        Ok(n)
    }

    /// Who still needs a send. Relay-accepted ones are not here —
    /// re-sending to a delivered recipient means a duplicate.
    pub fn outstanding(conn: &Connection, chat_id: &str, msg_id: &str) -> Result<Vec<String>> {
        let mut stmt = conn.prepare(
            "SELECT recipient_sig FROM delivery_fanout \
             WHERE chat_id = ?1 AND msg_id = ?2 \
               AND state IN ('pending','in_flight','retry_wait')",
        )?;
        let rows = stmt.query_map(params![chat_id, msg_id], |r| r.get::<_, String>(0))?;
        let mut out = Vec::new();
        for r in rows { out.push(r?); }
        Ok(out)
    }

    pub fn mark_in_flight(conn: &Connection, chat_id: &str, msg_id: &str, sig: &str) -> Result<()> {
        conn.execute(
            "UPDATE delivery_fanout SET state = 'in_flight', attempts = attempts + 1, \
                last_attempt_at = ?4 \
             WHERE chat_id = ?1 AND msg_id = ?2 AND recipient_sig = ?3",
            params![chat_id, msg_id, sig, now_rfc3339()],
        )?;
        Ok(())
    }

    pub fn mark_accepted(conn: &Connection, chat_id: &str, msg_id: &str, sig: &str) -> Result<()> {
        let now = now_rfc3339();
        conn.execute(
            "UPDATE delivery_fanout SET state = 'relay_accepted', delivered_at = ?4, \
                last_error_class = '' \
             WHERE chat_id = ?1 AND msg_id = ?2 AND recipient_sig = ?3",
            params![chat_id, msg_id, sig, now],
        )?;
        Ok(())
    }

    pub fn mark_failed(
        conn: &Connection,
        chat_id: &str,
        msg_id: &str,
        sig: &str,
        class: &str,
        permanent: bool,
    ) -> Result<()> {
        let state = if permanent { "permanent_failed" } else { "retry_wait" };
        conn.execute(
            "UPDATE delivery_fanout SET state = ?5, last_error_class = ?4, last_attempt_at = ?6 \
             WHERE chat_id = ?1 AND msg_id = ?2 AND recipient_sig = ?3",
            params![chat_id, msg_id, sig, class, state, now_rfc3339()],
        )?;
        Ok(())
    }

    /// Stop delivery to a removed member.
    ///
    /// Call ONLY after a confirmed MLS membership change — not on a UI
    /// intention and not before the removal is applied. Otherwise a
    /// failed removal would silently leave a person without messages.
    ///
    /// What the relay already accepted is not recalled: recalling
    /// published data is impossible, and pretending otherwise is not
    /// allowed. Only what has not gone out yet is stopped — recorded with an honest reason, not "delivered".
    pub fn cancel_recipient(conn: &Connection, chat_id: &str, sig: &str) -> Result<usize> {
        Ok(conn.execute(
            "UPDATE delivery_fanout SET state = 'canceled_removed', last_error_class = 'removed' \
             WHERE chat_id = ?1 AND recipient_sig = ?2 \
               AND state IN ('pending','in_flight','retry_wait')",
            params![chat_id, sig],
        )?)
    }

    pub fn rows(conn: &Connection, chat_id: &str, msg_id: &str) -> Result<Vec<FanoutRow>> {
        let mut stmt = conn.prepare(
            "SELECT chat_id, msg_id, recipient_sig, state, attempts, last_error_class \
             FROM delivery_fanout WHERE chat_id = ?1 AND msg_id = ?2 ORDER BY recipient_sig",
        )?;
        let rows = stmt.query_map(params![chat_id, msg_id], |r| {
            Ok(FanoutRow {
                chat_id: r.get(0)?,
                msg_id: r.get(1)?,
                recipient_sig: r.get(2)?,
                state: FanoutState::from_str(&r.get::<_, String>(3)?),
                attempts: r.get(4)?,
                last_error_class: r.get(5)?,
            })
        })?;
        let mut out = Vec::new();
        for r in rows { out.push(r?); }
        Ok(out)
    }

    /// A logical message counts as fanned out when every recipient's row
    /// is finished. One unconfirmed row prevents declaring completion —
    /// and does not force re-sending to the accepted ones.
    pub fn all_settled(conn: &Connection, chat_id: &str, msg_id: &str) -> Result<bool> {
        let n: i64 = conn.query_row(
            "SELECT COUNT(*) FROM delivery_fanout \
             WHERE chat_id = ?1 AND msg_id = ?2 \
               AND state IN ('pending','in_flight','retry_wait')",
            params![chat_id, msg_id],
            |r| r.get(0),
        )?;
        Ok(n == 0)
    }
}

#[cfg(test)]
mod p1_fanout {
    use super::*;
    use crate::pool::DatabasePool;

    const A: &str = "aaaa";
    const B: &str = "bbbb";
    const C: &str = "cccc";

    fn fresh_pool() -> DatabasePool {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_fanout_{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&p, "").expect("open test db");
        let _ = std::fs::remove_file(&p);
        pool
    }

    fn snap(conn: &Connection) {
        DeliveryFanoutRepo::snapshot(conn, "chat", "msg", &[A.into(), B.into(), C.into()])
            .expect("snapshot");
    }

    /// The snapshot creates a row for EVERY recipient: otherwise there would be nothing to retry.
    #[test]
    fn rows_are_created_for_every_recipient() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        let rows = DeliveryFanoutRepo::rows(&conn, "chat", "msg").unwrap();
        assert_eq!(rows.len(), 3);
        assert!(rows.iter().all(|r| r.state == FanoutState::Pending));
    }

    /// Partial failure is preserved BY NAME: A and C accepted, B is not.
    #[test]
    fn a_partial_failure_is_recorded_per_recipient() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", A).unwrap();
        DeliveryFanoutRepo::mark_failed(&conn, "chat", "msg", B, "offline", false).unwrap();
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", C).unwrap();

        let rows = DeliveryFanoutRepo::rows(&conn, "chat", "msg").unwrap();
        let by = |s: &str| rows.iter().find(|r| r.recipient_sig == s).unwrap().clone();
        assert_eq!(by(A).state, FanoutState::RelayAccepted);
        assert_eq!(by(B).state, FanoutState::RetryWait);
        assert_eq!(by(C).state, FanoutState::RelayAccepted);
        assert_eq!(by(B).last_error_class, "offline");
    }

    /// Retry — only the undelivered. An accepted one does not return to
    /// work, or it would receive a duplicate.
    #[test]
    fn a_retry_takes_only_the_undelivered() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", A).unwrap();
        DeliveryFanoutRepo::mark_failed(&conn, "chat", "msg", B, "timeout", false).unwrap();
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", C).unwrap();

        let todo = DeliveryFanoutRepo::outstanding(&conn, "chat", "msg").unwrap();
        assert_eq!(todo, vec![B.to_string()]);
    }

    /// The fan-out is not complete while a single recipient is unsettled.
    #[test]
    fn a_fanout_is_done_only_when_all_are_settled() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", A).unwrap();
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", C).unwrap();
        assert!(!DeliveryFanoutRepo::all_settled(&conn, "chat", "msg").unwrap());

        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", B).unwrap();
        assert!(DeliveryFanoutRepo::all_settled(&conn, "chat", "msg").unwrap());
    }

    /// A process death mid-send does not leave the row hanging forever:
    /// opening the DB returns it to work.
    #[test]
    fn a_restart_returns_a_stuck_send() {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_fanout_restart_{}.db", uuid::Uuid::new_v4()));
        {
            let pool = DatabasePool::open(&p, "").expect("open");
            let conn = pool.connection();
            snap(&conn);
            DeliveryFanoutRepo::mark_in_flight(&conn, "chat", "msg", B).unwrap();
            let rows = DeliveryFanoutRepo::rows(&conn, "chat", "msg").unwrap();
            assert_eq!(
                rows.iter().find(|r| r.recipient_sig == B).unwrap().state,
                FanoutState::InFlight,
            );
        }
        // Re-open = process restart.
        {
            let pool = DatabasePool::open(&p, "").expect("reopen");
            let conn = pool.connection();
            let todo = DeliveryFanoutRepo::outstanding(&conn, "chat", "msg").unwrap();
            assert!(todo.contains(&B.to_string()), "the stuck send did not come back");
        }
        let _ = std::fs::remove_file(&p);
    }

    /// State survives closing the DB — it is on disk, not in memory.
    #[test]
    fn the_state_survives_a_restart() {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_fanout_persist_{}.db", uuid::Uuid::new_v4()));
        {
            let pool = DatabasePool::open(&p, "").expect("open");
            let conn = pool.connection();
            snap(&conn);
            DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", A).unwrap();
        }
        {
            let pool = DatabasePool::open(&p, "").expect("reopen");
            let conn = pool.connection();
            let todo = DeliveryFanoutRepo::outstanding(&conn, "chat", "msg").unwrap();
            assert!(!todo.contains(&A.to_string()), "an accepted recipient came back after a restart");
            assert_eq!(todo.len(), 2);
        }
        let _ = std::fs::remove_file(&p);
    }

    /// A new member is NOT added to an existing message's snapshot:
    /// otherwise they would receive what was not written to them.
    #[test]
    fn a_new_member_is_not_added_to_an_old_message() {
        let pool = fresh_pool();
        let conn = pool.connection();
        DeliveryFanoutRepo::snapshot(&conn, "chat", "msg", &[A.into(), B.into()]).unwrap();
        // Membership grew, but the message's snapshot is already taken.
        let rows = DeliveryFanoutRepo::rows(&conn, "chat", "msg").unwrap();
        assert_eq!(rows.len(), 2);
        assert!(!rows.iter().any(|r| r.recipient_sig == C));
    }

    /// The removed member stops being a recipient in existing snapshots too.
    #[test]
    fn a_removed_member_leaves_the_snapshots() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        let removed = DeliveryFanoutRepo::cancel_recipient(&conn, "chat", B).unwrap();
        assert_eq!(removed, 1);
        let todo = DeliveryFanoutRepo::outstanding(&conn, "chat", "msg").unwrap();
        assert!(!todo.contains(&B.to_string()), "still sending to a removed member");
    }

    /// A repeated snapshot grows no rows and resets no progress.
    #[test]
    fn a_repeated_snapshot_adds_no_rows() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", A).unwrap();
        snap(&conn); // repeat
        let rows = DeliveryFanoutRepo::rows(&conn, "chat", "msg").unwrap();
        assert_eq!(rows.len(), 3);
        assert_eq!(
            rows.iter().find(|r| r.recipient_sig == A).unwrap().state,
            FanoutState::RelayAccepted,
            "a repeated snapshot reset an accepted recipient",
        );
    }
}

#[cfg(test)]
mod p1_removed_recipient {
    use super::*;
    use crate::pool::DatabasePool;

    const A: &str = "aaaa";
    const B: &str = "bbbb";
    const C: &str = "cccc";

    fn fresh_pool() -> DatabasePool {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_removed_{}.db", uuid::Uuid::new_v4()));
        let pool = DatabasePool::open(&p, "").expect("open test db");
        let _ = std::fs::remove_file(&p);
        pool
    }

    fn snap(conn: &Connection) {
        DeliveryFanoutRepo::snapshot(conn, "chat", "msg", &[A.into(), B.into(), C.into()])
            .expect("snapshot");
    }

    fn state_of(conn: &Connection, sig: &str) -> FanoutState {
        DeliveryFanoutRepo::rows(conn, "chat", "msg").unwrap()
            .into_iter().find(|r| r.recipient_sig == sig).unwrap().state
    }

    /// Removed BEFORE the envelope went out — it will not go out at all.
    ///
    /// That is the point of the policy: the removed member can still
    /// decrypt old ciphertext, so finishing delivery to them would hand
    /// the content to someone already expelled.
    #[test]
    fn removal_before_acceptance_stops_delivery() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        DeliveryFanoutRepo::mark_failed(&conn, "chat", "msg", B, "offline", false).unwrap();

        DeliveryFanoutRepo::cancel_recipient(&conn, "chat", B).unwrap();

        assert_eq!(state_of(&conn, B), FanoutState::CanceledRemoved);
        let todo = DeliveryFanoutRepo::outstanding(&conn, "chat", "msg").unwrap();
        assert!(!todo.contains(&B.to_string()), "still trying to send to a removed member");
    }

    /// What the relay already accepted cannot be recalled — it is
    /// physically impossible, and pretending it worked is not allowed.
    #[test]
    fn removal_after_acceptance_does_not_revoke() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", B).unwrap();

        DeliveryFanoutRepo::cancel_recipient(&conn, "chat", B).unwrap();

        assert_eq!(
            state_of(&conn, B), FanoutState::RelayAccepted,
            "a relay acceptance was replaced by a cancel",
        );
    }

    /// The removed member does not keep the fan-out unfinished, but is
    /// NOT counted as delivered either: the reason is recorded honestly.
    #[test]
    fn a_removed_member_is_not_needed_to_finish() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", A).unwrap();
        DeliveryFanoutRepo::mark_accepted(&conn, "chat", "msg", C).unwrap();
        assert!(!DeliveryFanoutRepo::all_settled(&conn, "chat", "msg").unwrap());

        DeliveryFanoutRepo::cancel_recipient(&conn, "chat", B).unwrap();

        assert!(
            DeliveryFanoutRepo::all_settled(&conn, "chat", "msg").unwrap(),
            "the fanout hangs on a removed member",
        );
        assert_ne!(
            state_of(&conn, B), FanoutState::RelayAccepted,
            "a removed member was reported delivered",
        );
    }

    /// A failed removal does not touch the recipient: delivery may be
    /// stopped ONLY after a confirmed membership change.
    #[test]
    fn a_failed_removal_keeps_the_recipient() {
        let pool = fresh_pool();
        let conn = pool.connection();
        snap(&conn);
        // The removal was not applied — cancel_recipient is not called at all.
        let todo = DeliveryFanoutRepo::outstanding(&conn, "chat", "msg").unwrap();
        assert!(todo.contains(&B.to_string()), "the recipient was dropped without a confirmed removal");
        assert_eq!(state_of(&conn, B), FanoutState::Pending);
    }

    /// Cancellation survives a restart: the removed one does not return to work.
    #[test]
    fn a_cancel_survives_a_restart() {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_removed_restart_{}.db", uuid::Uuid::new_v4()));
        {
            let pool = DatabasePool::open(&p, "").expect("open");
            let conn = pool.connection();
            snap(&conn);
            DeliveryFanoutRepo::mark_in_flight(&conn, "chat", "msg", B).unwrap();
            DeliveryFanoutRepo::cancel_recipient(&conn, "chat", B).unwrap();
        }
        {
            let pool = DatabasePool::open(&p, "").expect("reopen");
            let conn = pool.connection();
            let todo = DeliveryFanoutRepo::outstanding(&conn, "chat", "msg").unwrap();
            assert!(
                !todo.contains(&B.to_string()),
                "a removed member came back after a restart",
            );
        }
        let _ = std::fs::remove_file(&p);
    }
}

/// What [MessageRepo::search] keeps besides the words; all optional.
#[derive(Debug, Default, Clone, Copy)]
pub struct SearchFilter<'a> {
    pub kind: Option<&'a str>,
    pub chat_id: Option<&'a str>,
    pub sender: Option<&'a str>,
    pub since: Option<&'a str>,
    pub until: Option<&'a str>,
}

/// A message found by [MessageRepo::search].
#[derive(Debug, Clone)]
pub struct SearchRow {
    pub id: String,
    pub chat_id: String,
    pub sender_id: String,
    pub content: String,
    pub created_at: String,
    pub msg_type: String,
    /// "media" | "link" | "music" | "voice" | "doc" | "app" | "archive" | "file", or empty.
    pub media_kind: String,
    /// A file's name, a link's title and address; empty otherwise.
    pub media_name: String,
}

/// The user's words as an FTS5 query: each word a quoted prefix, all required.
/// Anything else they typed (quotes, operators, punctuation) only separates words.
fn fts_query(query: &str) -> Option<String> {
    let words: Vec<String> = query
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| !w.is_empty())
        .map(|w| format!("\"{w}\"*"))
        .collect();
    if words.is_empty() { None } else { Some(words.join(" ")) }
}

#[cfg(test)]
mod search_tests {
    use super::*;

    fn db() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        crate::migrations::run_migrations(&conn).unwrap();
        conn.execute_batch(
            "INSERT INTO contacts (id, nostr_pubkey, created_at, updated_at) VALUES ('self', x'00', 't', 't');
             INSERT INTO chats (id, chat_type, created_at, updated_at) VALUES ('c1', 'direct', 't', 't');",
        ).unwrap();
        conn
    }

    fn put(conn: &Connection, id: &str, body: &str, at: &str) {
        conn.execute(
            "INSERT INTO messages(id, chat_id, sender_id, msg_type, content, created_at) VALUES (?1, 'c1', 'self', 'text', ?2, ?3)",
            params![id, body, at],
        ).unwrap();
    }

    // The search samples are Cyrillic on purpose: case folding and word starts must
    // work beyond ASCII.
    #[test]
    fn finds_word_starts_in_any_case_and_follows_edits_and_deletes() {
        let conn = db();
        put(&conn, "a", "Передай **привет** Маше", "2026-01-01T10:00:00Z");
        put(&conn, "b", "Приветствие новым", "2026-01-01T11:00:00Z");
        put(&conn, "c", "непривет", "2026-01-01T12:00:00Z");
        let ids = |q: &str| MessageRepo::search(&conn, q, &SearchFilter::default(), 50, 0).unwrap().into_iter().map(|r| r.id).collect::<Vec<_>>();
        assert_eq!(ids("прив"), vec!["b", "a"]);
        assert_eq!(ids("привет маш"), vec!["a"]);
        assert!(ids("\"*)(").is_empty());
        conn.execute("UPDATE messages SET content = 'пока' WHERE id = 'a'", []).unwrap();
        assert_eq!(ids("прив"), vec!["b"]);
        conn.execute("UPDATE messages SET is_deleted = 1, content = NULL WHERE id = 'b'", []).unwrap();
        assert!(ids("прив").is_empty());
        assert_eq!(ids("пок"), vec!["a"]);
    }

    #[test]
    fn kinds_are_listed_and_files_found_by_name() {
        let conn = db();
        let media = |id: &str, r: &str, body: Option<&str>, at: &str| {
            conn.execute(
                "INSERT INTO messages(id, chat_id, sender_id, msg_type, content, media_ref, created_at) VALUES (?1, 'c1', 'self', 'media', ?2, ?3, ?4)",
                params![id, body, r, at],
            ).unwrap();
        };
        media("f", r#"{"media_type":"file","filename":"Отчёт за ноябрь.pdf"}"#, None, "2026-01-01T10:00:00Z");
        media("v", r#"{"media_type":"audio","filename":"voice_123.ogg"}"#, None, "2026-01-01T11:00:00Z");
        media("m", r#"{"media_type":"audio","filename":"song.mp3"}"#, Some("слушай"), "2026-01-01T12:00:00Z");
        media("p", r#"{"media_type":"image"}"#, Some("море"), "2026-01-01T13:00:00Z");
        put(&conn, "l", "смотри https://example.org", "2026-01-01T14:00:00Z");
        let of = |q: &str, k: Option<&str>| MessageRepo::search(&conn, q, &SearchFilter { kind: k, ..Default::default() }, 50, 0).unwrap().into_iter().map(|r| r.id).collect::<Vec<_>>();
        assert_eq!(of("отч", None), vec!["f"]);
        assert_eq!(of("", Some("file")), vec!["f"]);
        assert_eq!(of("", Some("doc")), vec!["f"]);
        media("a", r#"{"media_type":"file","filename":"alpha.release.APK"}"#, None, "2026-01-01T09:00:00Z");
        media("z", r#"{"media_type":"file","filename":"photos.zip"}"#, None, "2026-01-01T08:00:00Z");
        media("x", r#"{"media_type":"file","filename":"README"}"#, None, "2026-01-01T07:00:00Z");
        media("r", r#"{"media_type":"video","filename":"round_1.mp4"}"#, None, "2026-01-01T06:00:00Z");
        media("g", r#"{"media_type":"video","filename":"gif_1.mp4"}"#, None, "2026-01-01T05:00:00Z");
        media("q", r#"{"poll":true,"media_type":"poll","q":"Куда едем?","o":["Горы","Море"]}"#, None, "2026-01-01T04:00:00Z");
        assert_eq!(of("", Some("round")), vec!["r"]);
        assert_eq!(of("", Some("gif")), vec!["g"]);
        assert_eq!(of("гор", Some("poll")), vec!["q"]);
        assert!(!of("", Some("media")).contains(&"g".to_string()));
        assert_eq!(of("", Some("app")), vec!["a"]);
        assert_eq!(of("", Some("archive")), vec!["z"]);
        assert_eq!(of("", Some("file")), vec!["f", "a", "z", "x"]);
        assert!(of("", Some("doc")).iter().all(|i| i == "f"));
        assert_eq!(of("", Some("voice")), vec!["v"]);
        assert_eq!(of("", Some("music")), vec!["m"]);
        assert_eq!(of("мор", Some("media")), vec!["p"]);
        assert!(of("мор", Some("file")).is_empty());
        assert_eq!(of("", Some("link")), vec!["l"]);
        conn.execute("UPDATE messages SET media_ref = '{\"media_type\":\"file\",\"filename\":\"new.txt\"}' WHERE id = 'f'", []).unwrap();
        assert_eq!(of("new", Some("file")), vec!["f"]);
        assert!(of("отч", None).is_empty());
    }

    #[test]
    fn filters_by_author_and_period() {
        let conn = db();
        conn.execute_batch(
            "INSERT INTO contacts (id, nostr_pubkey, created_at, updated_at) VALUES ('sig_a', x'01', 't', 't');
             INSERT INTO key_value_store (key, value, updated_at) VALUES ('member_npub:sig_a', 'pk_a', 't');",
        ).unwrap();
        put(&conn, "mine", "привет всем", "2026-01-01T10:00:00Z");
        conn.execute(
            "INSERT INTO messages(id, chat_id, sender_id, msg_type, content, created_at) VALUES ('theirs', 'c1', 'sig_a', 'text', 'привет тебе', '2026-01-05T10:00:00Z')",
            [],
        ).unwrap();
        let of = |q: &str, f: SearchFilter<'_>| MessageRepo::search(&conn, q, &f, 50, 0).unwrap().into_iter().map(|r| r.id).collect::<Vec<_>>();
        assert_eq!(of("прив", SearchFilter { sender: Some("pk_a"), ..Default::default() }), vec!["theirs"]);
        assert_eq!(of("", SearchFilter { sender: Some("self"), ..Default::default() }), vec!["mine"]);
        assert_eq!(of("прив", SearchFilter { since: Some("2026-01-02T00:00:00"), ..Default::default() }), vec!["theirs"]);
        assert_eq!(of("", SearchFilter { until: Some("2026-01-02T00:00:00"), ..Default::default() }), vec!["mine"]);
    }

    #[test]
    fn an_album_of_files_is_files_and_a_jump_knows_where_it_is() {
        let conn = db();
        conn.execute(
            "INSERT INTO messages(id, chat_id, sender_id, msg_type, media_ref, created_at) VALUES ('al', 'c1', 'self', 'media', ?1, '2026-01-01T10:00:00Z')",
            params![r#"{"album":true,"items":[{"media_type":"file","filename":"Смета.xlsx"},{"media_type":"file","filename":"План.pdf"}]}"#],
        ).unwrap();
        conn.execute(
            "INSERT INTO messages(id, chat_id, sender_id, msg_type, media_ref, created_at) VALUES ('ph', 'c1', 'self', 'media', ?1, '2026-01-01T11:00:00Z')",
            params![r#"{"album":true,"items":[{"media_type":"image"},{"media_type":"video"}]}"#],
        ).unwrap();
        put(&conn, "t1", "a", "2026-01-01T12:00:00Z");
        put(&conn, "t2", "b", "2026-01-01T13:00:00Z");
        let of = |q: &str, k: Option<&str>| MessageRepo::search(&conn, q, &SearchFilter { kind: k, ..Default::default() }, 50, 0).unwrap().into_iter().map(|r| r.id).collect::<Vec<_>>();
        assert_eq!(of("", Some("doc")), vec!["al"]);
        assert_eq!(of("план", None), vec!["al"]);
        assert_eq!(of("", Some("media")), vec!["ph"]);
        assert_eq!(MessageRepo::rank_from_newest(&conn, "c1", "al").unwrap(), Some(3));
        assert_eq!(MessageRepo::rank_from_newest(&conn, "c1", "t2").unwrap(), Some(0));
        assert_eq!(MessageRepo::rank_from_newest(&conn, "c1", "nope").unwrap(), None);
    }

    #[test]
    fn stored_media_lists_album_parts_one_by_one() {
        let conn = db();
        conn.execute(
            "INSERT INTO messages(id, chat_id, sender_id, msg_type, media_ref, created_at) VALUES ('al', 'c1', 'self', 'media', ?1, 't1')",
            params![r#"{"album":true,"items":[{"media_type":"image","hash":"h1"},{"media_type":"video","hash":"h2","local_path":"/x"}]}"#],
        ).unwrap();
        conn.execute(
            "INSERT INTO messages(id, chat_id, sender_id, msg_type, media_ref, created_at) VALUES ('f', 'c1', 'self', 'media', ?1, 't2')",
            params![r#"{"media_type":"file","hash":"h3"}"#],
        ).unwrap();
        let mut got = MessageRepo::stored_media(&conn).unwrap();
        got.sort();
        assert_eq!(got, vec![
            ("c1".into(), "file".into(), "h3".into(), "".into()),
            ("c1".into(), "image".into(), "h1".into(), "".into()),
            ("c1".into(), "video".into(), "h2".into(), "/x".into()),
        ]);
        let ids = |k: &[&str]| MessageRepo::of_kinds(&conn, "c1", k, 50, 0).unwrap().into_iter().map(|m| m.id).collect::<Vec<_>>();
        assert_eq!(ids(&["media"]), vec!["al"]);
        assert_eq!(ids(&["file", "doc"]), vec!["f"]);
        assert_eq!(MessageRepo::by_ids(&conn, &["f".into(), "nope".into(), "al".into()]).unwrap().len(), 2);
    }

    #[test]
    fn a_date_lands_on_its_first_message() {
        let conn = db();
        put(&conn, "a", "one", "2026-01-01T10:00:00+00:00");
        put(&conn, "b", "two", "2026-01-02T09:30:00.250+00:00");
        put(&conn, "c", "three", "2026-01-02T18:00:00+00:00");
        let at = |t: &str| MessageRepo::first_at_or_after(&conn, "c1", t).unwrap();
        assert_eq!(at("2026-01-02T00:00:00").as_deref(), Some("b"));
        assert_eq!(at("2026-01-01T00:00:00").as_deref(), Some("a"));
        assert_eq!(at("2026-01-03T00:00:00"), None);
    }
}

#[cfg(test)]
mod export_pages {
    use super::*;
    use crate::pool::DatabasePool;

    #[test]
    fn a_cursor_walks_a_chat_once_and_keeps_silent_and_reactions() {
        let path = std::env::temp_dir().join(format!("alpha_export_{}.db", Uuid::new_v4()));
        let pool = DatabasePool::open(&path, "k").unwrap();
        let conn = pool.connection();
        ContactRepo::insert(&conn, &new_contact("peer".into(), None, vec![0; 32], None)).unwrap();
        ChatRepo::insert(&conn, &new_chat("c".into(), ChatType::Direct, None, 0)).unwrap();
        let at = |i: u32| format!("2026-11-0{}T10:00:00+00:00", 1 + i / 2);
        let mut ids = Vec::new();
        for i in 0..7u32 {
            let mut m = new_message("c".into(), "peer".into(), MessageType::Text, Some(format!("m{i}")), None, None, None, None);
            m.created_at = at(i);
            ids.push(m.id.clone());
            MessageRepo::insert(&conn, &m).unwrap();
        }
        let mut r = new_message("c".into(), "peer".into(), MessageType::Reaction, Some("👍".into()), Some(ids[0].clone()), None, None, None);
        r.created_at = at(1);
        MessageRepo::insert(&conn, &r).unwrap();
        assert!(MessageRepo::mark_silent(&conn, &ids[2]).unwrap());

        let (mut cur_at, mut cur_id, mut seen) = (String::new(), String::new(), Vec::new());
        loop {
            let page = MessageRepo::export_page(&conn, "c", &cur_at, &cur_id, 0, 0, 3).unwrap();
            if page.is_empty() { break; }
            let last = page.last().unwrap();
            cur_at = last.created_at.clone();
            cur_id = last.id.clone();
            seen.extend(page.into_iter().map(|m| m.content.unwrap()));
        }
        assert_eq!(seen.len(), 7, "every message once, reactions not among them");
        let since = chrono::DateTime::parse_from_rfc3339(&at(2)).unwrap().timestamp();
        assert_eq!(MessageRepo::export_page(&conn, "c", "", "", since, 0, 50).unwrap().len(), 5);
        assert_eq!(MessageRepo::reaction_counts(&conn, "c").unwrap(), vec![(ids[0].clone(), "👍".to_string(), 1)]);
        assert!(MessageRepo::is_silent(&conn, &ids[2]));
        assert!(!MessageRepo::is_silent(&conn, &ids[3]));
        drop(conn);
        drop(pool);
        let _ = std::fs::remove_file(path);
    }
}

#[cfg(test)]
mod countdown_tests {
    use super::*;

    fn db() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        crate::migrations::run_migrations(&conn).unwrap();
        conn.execute_batch(
            "INSERT INTO contacts (id, nostr_pubkey, created_at, updated_at) VALUES ('self', x'00', 't', 't');
             INSERT INTO contacts (id, nostr_pubkey, created_at, updated_at) VALUES ('peer', x'01', 't', 't');
             INSERT INTO chats (id, chat_type, created_at, updated_at) VALUES ('c1', 'direct', 't', 't');",
        ).unwrap();
        conn
    }

    fn put(conn: &Connection, id: &str, sender: &str, ttl: i64, policy: &str) {
        conn.execute(
            "INSERT INTO messages(id, chat_id, sender_id, msg_type, content, ttl_seconds, expiry_policy, created_at)
             VALUES (?1, 'c1', ?2, 'text', 'x', ?3, ?4, '2026-01-01T00:00:00+00:00')",
            params![id, sender, ttl, policy],
        ).unwrap();
    }

    fn expires(conn: &Connection, id: &str) -> Option<String> {
        conn.query_row("SELECT expires_at FROM messages WHERE id = ?1", params![id], |r| r.get(0)).unwrap()
    }

    #[test]
    fn our_timed_message_counts_from_its_send_and_a_one_time_one_does_not() {
        let conn = db();
        put(&conn, "m", "self", 10, "after_read");
        conn.execute(
            "INSERT INTO messages(id, chat_id, sender_id, msg_type, content, ttl_seconds, created_at)
             VALUES ('m#al1', 'c1', 'self', 'media', 'x', 10, '2026-01-01T00:00:00+00:00')",
            [],
        ).unwrap();
        put(&conn, "once", "self", 10, "view_once");
        put(&conn, "theirs", "peer", 10, "after_read");
        assert_eq!(MessageRepo::next_expiry_ms(&conn).unwrap(), None, "nothing counts before it is sent");

        let before = Utc::now();
        assert_eq!(MessageRepo::start_own_countdown(&conn, "m").unwrap(), 2, "the album items count too");
        assert_eq!(MessageRepo::start_own_countdown(&conn, "once").unwrap(), 0);
        assert_eq!(MessageRepo::start_own_countdown(&conn, "theirs").unwrap(), 0, "a received one counts from reading");
        assert!(expires(&conn, "once").is_none());

        let due = MessageRepo::next_expiry_ms(&conn).unwrap().expect("a countdown runs");
        let ten = before.timestamp_millis() + 10_000;
        assert!((ten..ten + 2_000).contains(&due), "due ten seconds after the send: {due}");
        assert_eq!(MessageRepo::start_own_countdown(&conn, "m").unwrap(), 0, "a countdown starts once");
    }
}
