//! Core FFI implementation.
//!
//! `AlphaMessengerCore` is the main object exposed through UniFFI.
//! It wraps all sub-crate functionality behind a single, thread-safe
//! interface.

use std::path::Path;
use std::sync::Arc;
use std::sync::Mutex;

use crate::lock_track::TrackedMutex;

use alpha_database::{
    ChatRepo, DatabasePool, KeyValueRepo, MessageRepo, MlsGroupRepo, OutboxRepo, PendingMediaRepo,
    PendingSendRepo, new_chat, new_mls_group_row, new_message,
};
use alpha_identity::DualIdentity;
use alpha_keys::{self as keys, KdfParams};
use alpha_media::{BlossomClient, MediaPipeline, MediaRef};
use alpha_messages::{AlphaMessage, ExpirySweeper, MessageBuilder};
use alpha_messages::ExpiryPolicy as AlphaExpiryPolicy;
use alpha_mls::{
    AlphaCredential, MlsSessionManager,
};
use alpha_notifications::{
    NtfyClient, NtfyMessage, NotificationHandler, PushDispatcher, WakeService,
};
use alpha_sync::{CatchUpManager, ProcessedEvent, SyncState};
use alpha_transport::{MlsEnvelope, MlsMessageType, NostrMlsBridge, NostrRelayClient, RelayPoolNotification, RelayStatus};
use tokio::task::JoinHandle;

/// Key under which the persisted dual identity (Nostr + MLS secret material)
/// is stored in `key_value_store`.
/// Schema version of the group transport directory. The MLS epoch serves as
/// the revision — it is monotonic by the protocol itself, so no separate scale.
pub(crate) const DIRECTORY_VERSION: u32 = 1;

const KV_IDENTITY: &str = "identity_v1";
/// The account's BIP-39 phrase (NIP-06), when it was created from one.
const KV_MNEMONIC: &str = "identity_mnemonic";
/// Ghost mode: no presence heartbeat, no typing, no read receipts.
const KV_GHOST: &str = "ghost_mode";
/// Scheduled ("held") messages: `held:<msg_id>` → {chat, nb, kind, payload}.
/// The message exists locally and its bytes are ready; it reaches the network
/// at `nb` (unix seconds). kind = "outbox" (queue_and_send) | "direct" (media).
const KV_HELD_PREFIX: &str = "held:";

/// The next user message sent in this chat is held until the given time
/// (set right before an ordinary send call). One-shot.
/// (chat, send time, armed at): the schedule the user just picked, waiting
/// for the message it belongs to. Short-lived: an arm whose message never
/// came must not catch a later, ordinary message.
fn armed_hold() -> &'static std::sync::Mutex<Option<(String, i64, i64)>> {
    static A: std::sync::OnceLock<std::sync::Mutex<Option<(String, i64, i64)>>> = std::sync::OnceLock::new();
    A.get_or_init(|| std::sync::Mutex::new(None))
}
const ARM_VALID_SECS: i64 = 60;

/// (chat, armed at): the next message the user writes there goes without sound.
fn armed_silent() -> &'static std::sync::Mutex<Option<(String, i64)>> {
    static A: std::sync::OnceLock<std::sync::Mutex<Option<(String, i64)>>> = std::sync::OnceLock::new();
    A.get_or_init(|| std::sync::Mutex::new(None))
}

fn take_armed_silent(chat_id: &str) -> bool {
    let now = chrono::Utc::now().timestamp();
    let Ok(mut a) = armed_silent().lock() else { return false };
    match a.as_ref() {
        Some((c, at)) if c == chat_id && now - at <= ARM_VALID_SECS => { *a = None; true }
        Some((_, at)) if now - at > ARM_VALID_SECS => { *a = None; false }
        _ => false,
    }
}

/// Take the armed schedule for `chat_id` (its send time), if still fresh.
fn take_armed(chat_id: &str) -> Option<i64> {
    let now = chrono::Utc::now().timestamp();
    let mut a = armed_hold().lock().ok()?;
    match a.as_ref() {
        Some((c, _, at)) if now - at > ARM_VALID_SECS => { let _ = c; *a = None; None }
        Some((c, _, _)) if c == chat_id => a.take().map(|(_, nb, _)| nb),
        _ => None,
    }
}
/// salt‖BackupFileKey of the last manual backup, for the daily backup.
const KV_BACKUP_AUTO_KEY: &str = "backup_auto_key";
/// Largest backup container accepted for import.
const MAX_BACKUP_BYTES: usize = 512 * 1024 * 1024;
/// People in one group or channel (MLS): see `ensure_room_for_member`.
pub(crate) const MAX_GROUP_MEMBERS: usize = 1000;
const KV_RELAYS: &str = "relays_v1";

/// H3 (RoadmapSecurityAudit): true if the URL's authority (host[:port]) is a
/// Tor `.onion` address. Cleartext `ws://`/`http://` is only tolerated for
/// `.onion` hosts, where the transport is already encrypted by Tor itself.
fn is_onion_authority(url: &str) -> bool {
    // Strip the scheme, then take the authority up to the first '/', '?' or '#'.
    let after_scheme = match url.split_once("://") {
        Some((_, rest)) => rest,
        None => url,
    };
    let authority = after_scheme
        .split(['/', '?', '#'])
        .next()
        .unwrap_or(after_scheme);
    // Drop any userinfo ("user@host") and port (":443"), then match the host.
    let host = authority
        .rsplit_once('@')
        .map(|(_, h)| h)
        .unwrap_or(authority);
    let host = host.split(':').next().unwrap_or(host);
    host.to_ascii_lowercase().ends_with(".onion")
}

// --- Recovery Password KV keys ---
// We persist the Argon2id parameters, the random salt and the verifier
// (NOT the password, NOT the master key). All three are public-by-design
// per RoadmapE2EE §1: the salt/params can't help an attacker, and the
// verifier only lets them do an offline guess that Argon2id makes expensive.
const KV_RECOVERY_KDF: &str = "recovery_kdf_v1";     // JSON: {params, salt_b64, verifier_b64}
const KV_RECOVERY_SET: &str = "recovery_set_v1";      // "1" once a password has been set

// --- DB rekey migration state ---
// Authoritative (post-open) state of the SQLCipher production-key migration.
// Lives INSIDE the encrypted DB. A plaintext pre-open hint is mirrored by Kotlin
// in SharedPreferences, but the DB KV row is the single source of truth once the
// DB is open. Forward-only: legacy → in_progress → migrated → confirmed.
//
// ALL service keys in this namespace share the `db_rekey_`/`migration_` prefixes
// so `import_backup` can strip the WHOLE namespace (a restored device must never
// inherit another device's migration state). Keep new keys within these prefixes.
const KV_DB_REKEY_STATE: &str = "db_rekey_state_v1";
const KV_MIGRATION_RECOVERY_ATTEMPTED: &str = "migration_recovery_attempted_v1";

// --- Sealed transport (v2) ---
/// Persisted epoch keys of the sealed transport (inside the encrypted DB).
const KV_TRANSPORT_KEYRING: &str = "transport_keyring_v2";
/// Our presence-channel secret (shared with contacts via ProfileUpdate).
const KV_OWN_PRESENCE_SECRET: &str = "own_presence_secret_v2";
/// Our custom status text and colour (sent in profile copies, by "status" privacy).
const KV_MY_STATUS_TEXT: &str = "my_status_text_v1";
const KV_MY_STATUS_COLOR: &str = "my_status_color_v1";
/// Our wake secret; the private ntfy topic is derived from it.
const KV_OWN_WAKE_SECRET: &str = "own_wake_secret_v2";
/// Unix time of the last broadcast of our contact secrets.
const KV_SECRETS_BROADCAST_AT: &str = "contact_secrets_broadcast_at_v2";
/// SOCKS5 proxy for all network traffic ("" = direct): the in-app Tor.
const KV_TOR_PROXY: &str = "tor_proxy_v1";
/// "0" = don't tell peers when we read their messages.
const KV_READ_RECEIPTS: &str = "read_receipts_v1";
/// Root of the per-blob Blossom keys (lets us delete our own uploads).
const KV_MEDIA_KEY_ROOT: &str = "media_key_root_v1";
/// Prefix: chats whose content is not kept on servers.
const KV_NO_STORE_PREFIX: &str = "no_store:";
/// The media vault key (hex).
const KV_MEDIA_VAULT_KEY: &str = "media_vault_key";
/// Global "don't keep on servers" default for every chat.
const KV_NO_STORE_DEFAULT: &str = "no_store_default";
/// Prefix: media scheduled for deletion from storage servers.
const KV_PURGE_PREFIX: &str = alpha_database::whispers::MEDIA_PURGE_PREFIX;
/// Keep media this long after every recipient got the message (time to download).
const PURGE_AFTER_DELIVERY_SECS: i64 = 6 * 3600;
/// Upper bound when a recipient never confirms delivery.
const PURGE_MAX_AGE_SECS: i64 = 7 * 24 * 3600;
/// AAD of the encrypted backup-locator content.
const BACKUP_LOCATOR_AAD: alpha_transport::sealed::Tag16 = *b"alpha-backup-v2!";
const STICKER_PACK_AAD: alpha_transport::sealed::Tag16 = *b"alpha-stickers-1";

/// Refs of expired media waiting for the app to delete their files.
fn expired_media() -> &'static std::sync::Mutex<Vec<String>> {
    static Q: std::sync::OnceLock<std::sync::Mutex<Vec<String>>> = std::sync::OnceLock::new();
    Q.get_or_init(|| std::sync::Mutex::new(Vec::new()))
}
/// Alpha's feed key (public half): only posts it signed are shown.
const ALPHA_FEED_PUBKEY: &str = "68e4444782adfea8a2c1bd66a75569b81cb2ef33543c86d5613e538a3f8f44aa";
const ALPHA_FEED_TAG: &str = "alpha-feed";

/// A 32-byte key from hex.
fn read_key32(hex_str: &str) -> Result<[u8; 32], MessengerError> {
    let v = hex::decode(hex_str.trim())
        .map_err(|e| MessengerError::InvalidArgument { description: format!("pack read key: {}", e) })?;
    v.try_into().map_err(|_| MessengerError::InvalidArgument { description: "pack read key length".to_string() })
}
/// SOCKS port of the Tor the app runs itself (see EmbeddedTor on Android).
const TOR_DEFAULT_PROXY: &str = "127.0.0.1:39050";
const SECRETS_REBROADCAST_SECS: i64 = 7 * 24 * 3600;

/// Load a persisted 32-byte secret, creating it on first use.
fn own_secret(conn: &rusqlite::Connection, kv_key: &str) -> alpha_transport::sealed::Key32 {
    if let Some(hex_s) = KeyValueRepo::get(conn, kv_key).ok().flatten() {
        if let Ok(bytes) = hex::decode(hex_s.trim()) {
            if let Ok(k) = <alpha_transport::sealed::Key32>::try_from(bytes.as_slice()) {
                return k;
            }
        }
    }
    let k = alpha_transport::sealed::random_secret();
    let _ = KeyValueRepo::set(conn, kv_key, &hex::encode(k));
    k
}

/// Our (presence secret hex, private wake topic) for ProfileUpdate.
fn own_contact_secrets(conn: &rusqlite::Connection) -> (String, String) {
    let presence = own_secret(conn, KV_OWN_PRESENCE_SECRET);
    let wake = own_secret(conn, KV_OWN_WAKE_SECRET);
    (hex::encode(presence), alpha_transport::sealed::wake_topic(&wake))
}

/// A contact's shared secrets, resolved to their npub (hex).
struct ContactSecrets {
    npub_hex: String,
    presence: Option<alpha_transport::sealed::Key32>,
    wake_topic: Option<String>,
}

/// Join `contact_secrets:<sig>` with `member_npub:<sig>`.
fn load_contact_secrets(conn: &rusqlite::Connection) -> Vec<ContactSecrets> {
    let Ok(entries) = KeyValueRepo::list(conn) else { return Vec::new() };
    let mut npub_of = std::collections::HashMap::new();
    let mut secrets = Vec::new();
    for e in &entries {
        if let Some(sig) = e.key.strip_prefix("member_npub:") {
            if !e.value.is_empty() {
                npub_of.insert(sig.to_string(), e.value.clone());
            }
        } else if let Some(sig) = e.key.strip_prefix("contact_secrets:") {
            secrets.push((sig.to_string(), e.value.clone()));
        }
    }
    secrets
        .into_iter()
        .filter_map(|(sig, json)| {
            let npub_hex = npub_of.get(&sig)?.clone();
            let v: serde_json::Value = serde_json::from_str(&json).ok()?;
            let presence = v.get("p").and_then(|x| x.as_str())
                .and_then(|h| hex::decode(h).ok())
                .and_then(|b| <alpha_transport::sealed::Key32>::try_from(b.as_slice()).ok());
            let wake_topic = v.get("w").and_then(|x| x.as_str()).filter(|t| !t.is_empty()).map(String::from);
            Some(ContactSecrets { npub_hex, presence, wake_topic })
        })
        .collect()
}

/// Persist the transport key ring if it changed since the last call. Returns
/// true when it changed (the caller should then resubscribe).
fn sync_transport_ring(db: &DatabasePool, transport: &NostrRelayClient, synced: &std::sync::atomic::AtomicU64) -> bool {
    let ring = transport.key_ring();
    let v = ring.version();
    if synced.swap(v, std::sync::atomic::Ordering::AcqRel) == v {
        return false;
    }
    let conn = db.connection();
    if let Err(e) = KeyValueRepo::set(&conn, KV_TRANSPORT_KEYRING, &ring.export_json()) {
        tracing::warn!("transport keyring persist failed: {}", e);
    }
    true
}

/// Set by the heartbeat the instant a relay connection is (re)established. The
/// next `flush_pending_media` then ignores the backoff windows once — a message
/// that had backed off to a 10-minute retry goes out as soon as the network is
/// really back, instead of making the user wait out a timer whose only job was
/// to protect the runtime while we were offline.
static PENDING_WAKE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
const REKEY_STATE_LEGACY: &str = "legacy";
const REKEY_STATE_IN_PROGRESS: &str = "in_progress";
const REKEY_STATE_MIGRATED: &str = "migrated";
const REKEY_STATE_CONFIRMED: &str = "confirmed";

/// The deterministic open plan for a given pre-open migration hint.
///
/// This is the SINGLE source of truth for the "which key, what fallback"
/// decision. Rust owns it; Android passes the hint string in and never
/// replicates this logic, so Rust / Android / restore can't diverge.
#[derive(Debug, PartialEq, Eq)]
enum OpenPlan {
    /// `legacy`/absent/unknown → open with the empty legacy key.
    Legacy,
    /// `in_progress` → interrupted migration: probe "" then db_key, restore on fail.
    RecoverInProgress,
    /// `migrated` → db_key; a single restore→legacy fallback is allowed.
    MigratedDbKey,
    /// `confirmed` → db_key ONLY. No legacy fallback (the backup is gone).
    ConfirmedDbKey,
}

/// Map a pre-open hint string to its [`OpenPlan`]. Pure and total — any unknown
/// value is treated as `legacy` (the safe default that never assumes a db_key).
fn resolve_open_plan(hint: &str) -> OpenPlan {
    match hint {
        REKEY_STATE_IN_PROGRESS => OpenPlan::RecoverInProgress,
        REKEY_STATE_MIGRATED => OpenPlan::MigratedDbKey,
        REKEY_STATE_CONFIRMED => OpenPlan::ConfirmedDbKey,
        _ => OpenPlan::Legacy,
    }
}

/// Derive the 32-byte MasterDataKey from a password using PUBLIC
/// KDF material supplied as JSON, WITHOUT touching the database.
///
/// This is the pre-open (cold-start / onboarding) password path: the DB can't be
/// opened to read its `KV_RECOVERY_KDF` yet, so the caller passes the mirrored
/// public blob from `RecoveryKdfPublicStore`. The password is
/// verified against the stored non-reversible verifier; a mismatch returns an
/// error so the UI can say "wrong password". The returned master key then feeds
/// `new_unlocked`. Pure crypto — no DB, no core instance required.
/// 12 fresh words (128-bit entropy, BIP-39 English).
pub fn mnemonic_generate() -> String {
    use rand::RngCore;
    let mut entropy = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut entropy);
    let words = nostr::bip39::Mnemonic::from_entropy(&entropy)
        .map(|m| m.to_string())
        .unwrap_or_default();
    entropy.fill(0);
    words
}

/// Normalise user input: lower case, single spaces, no punctuation.
fn normalize_words(words: &str) -> String {
    words
        .split(|c: char| !c.is_alphabetic())
        .filter(|w| !w.is_empty())
        .map(|w| w.to_lowercase())
        .collect::<Vec<_>>()
        .join(" ")
}

/// NIP-06: the Nostr secret key (nsec) that the phrase stands for.
pub fn mnemonic_to_nsec(words: String) -> Result<String, MessengerError> {
    // Wiped from memory when this call ends.
    let words = zeroize::Zeroizing::new(words);
    use nostr::nips::nip06::FromMnemonic;
    use nostr::nips::nip19::ToBech32;
    let phrase = normalize_words(&words);
    nostr::bip39::Mnemonic::parse_in_normalized(nostr::bip39::Language::English, &phrase)
        .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid phrase: {e}") })?;
    let keys = nostr::Keys::from_mnemonic(phrase.as_str(), None)
        .map_err(|e| MessengerError::CryptoError { description: format!("phrase derivation: {e}") })?;
    keys.secret_key().to_bech32()
        .map_err(|e| MessengerError::InternalError { description: e.to_string() })
}

/// Held core locks with their holders; never blocks on a core lock.
pub fn lock_report() -> String {
    crate::lock_track::report()
}

pub fn mnemonic_wordlist() -> Vec<String> {
    nostr::bip39::Language::English.word_list().iter().map(|w| w.to_string()).collect()
}

#[cfg(test)]
mod mnemonic_tests {
    use super::*;

    /// The official NIP-06 vector: the same words must give the same key as
    /// in every other Nostr client.
    #[test]
    fn nip06_vector() {
        use nostr::nips::nip19::FromBech32;
        let nsec = mnemonic_to_nsec("leader monkey parrot ring guide accident before fence cannon height naive bean".into()).unwrap();
        let sk = nostr::SecretKey::from_bech32(&nsec).unwrap();
        assert_eq!(sk.to_secret_hex(), "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a");
    }

    #[test]
    fn typo_and_messy_input() {
        assert!(mnemonic_to_nsec("leader monkey parrot ring guide accident before fence cannon height naive beam".into()).is_err());
        let a = mnemonic_to_nsec("  Leader, monkey
parrot ring guide accident before fence cannon height naive bean ".into()).unwrap();
        let b = mnemonic_to_nsec("leader monkey parrot ring guide accident before fence cannon height naive bean".into()).unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn generated_phrase_round_trips() {
        let w = mnemonic_generate();
        assert_eq!(w.split(' ').count(), 12);
        assert!(mnemonic_to_nsec(w).is_ok());
        assert_eq!(mnemonic_wordlist().len(), 2048);
    }
}

pub fn derive_master_key_from_public(
    password: String,
    public_kdf_json: String,
) -> Result<Vec<u8>, MessengerError> {
    // Wiped from memory when this call ends.
    let password = zeroize::Zeroizing::new(password);
    let record: RecoveryKdfRecord = serde_json::from_str(&public_kdf_json)
        .map_err(|e| MessengerError::InvalidArgument { description: format!("public kdf json parse: {}", e) })?;
    let salt = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &record.salt_b64)
        .map_err(|e| MessengerError::InvalidArgument { description: format!("salt decode: {}", e) })?;
    if salt.len() != keys::SALT_LEN {
        return Err(MessengerError::InvalidArgument {
            description: format!("salt length {} != {}", salt.len(), keys::SALT_LEN),
        });
    }
    let mut salt_arr = [0u8; keys::SALT_LEN];
    salt_arr.copy_from_slice(&salt);
    let master = keys::derive_master_key(&password, &salt_arr, &record.params)
        .map_err(|e| MessengerError::CryptoError { description: format!("argon2id: {}", e) })?;
    // Verify against the stored verifier (constant-time inside verify_master_key).
    if !keys::verify_master_key(&master, &record.verifier_bytes()) {
        return Err(MessengerError::InvalidArgument {
            description: "wrong recovery password".to_string(),
        });
    }
    Ok(master.as_bytes().to_vec())
}

/// Outcome of [`resolve_and_open`]: the opened pool plus whether a one-shot
/// restore→legacy fallback was just performed (so the caller persists the flag).
struct ResolvedOpen {
    pool: DatabasePool,
    recovery_attempted_now: bool,
}

/// Open the DB by deterministically resolving the correct key from
/// the migration hint, executing any interrupted-migration recovery, and
/// reconciling the authoritative DB KV state.
///
/// Safety net: even if the hint is stale, the actual key attempts validate
/// against reality, and the `confirmed ⟹ no backup` invariant means a legacy
/// fallback is impossible once truly confirmed (there is nothing to restore).
fn resolve_and_open(
    db_path: &Path,
    db_key_hex: Option<&str>,
    device_key_hex: Option<&str>,
    hint: &str,
    recovery_already_attempted: bool,
) -> Result<ResolvedOpen, MessengerError> {
    let db_err = |e: alpha_database::DatabaseError| MessengerError::DatabaseError {
        description: e.to_string(),
    };
    // Before a Recovery Password exists the database is under the device key. The
    // empty key only ever OPENS a database made before device keys existed; a
    // new one is never created without a key.
    let existed = db_path.exists();
    let open_legacy = || -> std::result::Result<DatabasePool, alpha_database::DatabaseError> {
        match device_key_hex {
            Some(dk) => DatabasePool::open_with_raw_key(db_path, dk).or_else(|e| {
                if !existed { return Err(e); }
                // A database from before device keys: opened once with the empty
                // key and moved under the device key at once, so the empty key
                // never opens it again.
                let pool = DatabasePool::open(db_path, "")?;
                if let Err(re) = pool.rekey_to_raw_key(dk) {
                    tracing::warn!("legacy database not moved under the device key: {re}");
                }
                Ok(pool)
            }),
            None if existed => DatabasePool::open(db_path, ""),
            None => Err(alpha_database::DatabaseError::DecryptionFailed),
        }
    };
    let need_key = || MessengerError::InvalidArgument {
        description: "this DB requires the production database key (master key) to open".to_string(),
    };

    // Helper: open legacy and reset DB KV state to `legacy` (authoritative).
    let open_legacy_reset = || -> Result<DatabasePool, MessengerError> {
        let pool = open_legacy().map_err(db_err)?;
        let conn = pool.connection();
        let _ = KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, REKEY_STATE_LEGACY);
        drop(conn);
        Ok(pool)
    };

    match resolve_open_plan(hint) {
        OpenPlan::Legacy => Ok(ResolvedOpen {
            pool: open_legacy().map_err(db_err)?,
            recovery_attempted_now: false,
        }),

        OpenPlan::ConfirmedDbKey => {
            // db_key ONLY — no legacy fallback, no backup exists.
            let key = db_key_hex.ok_or_else(need_key)?;
            let pool = DatabasePool::open_with_raw_key(db_path, key).map_err(|_| {
                MessengerError::DatabaseError {
                    description: "confirmed DB failed to open with the database key — correct Recovery Password required".to_string(),
                }
            })?;
            Ok(ResolvedOpen { pool, recovery_attempted_now: false })
        }

        OpenPlan::MigratedDbKey => {
            let key = db_key_hex.ok_or_else(need_key)?;
            match DatabasePool::open_with_raw_key(db_path, key) {
                Ok(pool) => Ok(ResolvedOpen { pool, recovery_attempted_now: false }),
                Err(_) => {
                    if recovery_already_attempted {
                        return Err(MessengerError::DatabaseError {
                            description: "migrated DB failed to open and recovery was already attempted — Recovery Password required".to_string(),
                        });
                    }
                    // One-shot: restore the pre-rekey backup → legacy.
                    keys::restore_db_file(db_path).map_err(|e| MessengerError::DatabaseError {
                        description: format!("rollback restore failed: {}", e),
                    })?;
                    let pool = open_legacy_reset()?;
                    {
                        let conn = pool.connection();
                        let _ = KeyValueRepo::set(&conn, KV_MIGRATION_RECOVERY_ATTEMPTED, "1");
                    }
                    // Restored and open: the copy has done its job and must not linger.
                    keys::discard_backup(db_path);
                    Ok(ResolvedOpen { pool, recovery_attempted_now: true })
                }
            }
        }

        OpenPlan::RecoverInProgress => {
            // Probe legacy first — success means the rekey hadn't taken (or was
            // already rolled back); reset to legacy and retry migration later.
            if let Ok(pool) = open_legacy() {
                let conn = pool.connection();
                let _ = KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, REKEY_STATE_LEGACY);
                drop(conn);
                return Ok(ResolvedOpen { pool, recovery_attempted_now: false });
            }
            // "" failed → the rekey likely completed; we need the db_key.
            let key = db_key_hex.ok_or_else(need_key)?;
            match DatabasePool::open_with_raw_key(db_path, key) {
                Ok(pool) => {
                    // Rekey had finished but the state wasn't advanced — finalize.
                    let conn = pool.connection();
                    let _ = KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, REKEY_STATE_MIGRATED);
                    drop(conn);
                    Ok(ResolvedOpen { pool, recovery_attempted_now: false })
                }
                Err(_) => {
                    if recovery_already_attempted {
                        return Err(MessengerError::DatabaseError {
                            description: "in_progress DB unreadable and recovery already attempted — Recovery Password required".to_string(),
                        });
                    }
                    keys::restore_db_file(db_path).map_err(|e| MessengerError::DatabaseError {
                        description: format!("rollback restore failed: {}", e),
                    })?;
                    let pool = open_legacy_reset()?;
                    {
                        let conn = pool.connection();
                        let _ = KeyValueRepo::set(&conn, KV_MIGRATION_RECOVERY_ATTEMPTED, "1");
                    }
                    // Restored and open: the copy has done its job and must not linger.
                    keys::discard_backup(db_path);
                    Ok(ResolvedOpen { pool, recovery_attempted_now: true })
                }
            }
        }
    }
}

// --- Cloud backup KV keys ---
// We persist the last-uploaded backup's Blossom URL + SHA-256 so the UI can
// show "backup synced <date>" and so we can re-download / re-publish without
// re-encrypting. The URL/hash are public (the blob itself is AEAD-encrypted).
const KV_BACKUP_URL: &str = "cloud_backup_url_v1";
/// Own sticker packs' signing keys, by the pack's public key.
const KV_STICKER_SIGN_PREFIX: &str = "sticker_sign_v1:";    // Blossom blob URL
/// Verified contacts: peer pubkey hex -> the safety number it was verified for.
const KV_VERIFIED_PREFIX: &str = "verified_contact:";
const KV_BACKUP_SHA: &str = "cloud_backup_sha_v1";    // SHA-256 hex of the blob
const KV_BACKUP_AT: &str = "cloud_backup_at_v1";      // RFC3339 upload timestamp
const KV_BACKUP_SIZE: &str = "cloud_backup_size_v1";  // blob size in bytes

/// Persisted relay config row (URL + flags). Stored as JSON array under
/// `KV_RELAYS` so the user's manual add/remove/disable choices survive
/// process restart.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone)]
struct PersistedRelay {
    url: String,
    #[serde(default = "default_true")]
    read: bool,
    #[serde(default = "default_true")]
    write: bool,
    #[serde(default = "default_true")]
    enabled: bool,
}

fn default_true() -> bool { true }

/// Message ids whose media send the user has CANCELLED.
///
/// A Kotlin coroutine cancellation cannot reach an upload: the FFI call is
/// synchronous and the work happens inside `block_on`, so the bytes kept flowing
/// and — worse — the message was published at the end. The peer received a file
/// the sender had already cancelled. This is the flag the pipeline checks between
/// chunks, and that the publish step checks before it commits anything.
///
/// Album items report as `"<msg_id>#<n>"`, so a cancelled album head cancels all
/// of its items.
static UPLOAD_CANCEL: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<String>>> =
    std::sync::OnceLock::new();

fn upload_cancel_set() -> &'static std::sync::Mutex<std::collections::HashSet<String>> {
    UPLOAD_CANCEL.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
}

/// Sends whose encoder finished: their early upload stops taking new chunks.
static EARLY_DONE: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<String>>> =
    std::sync::OnceLock::new();

fn early_done_set() -> &'static std::sync::Mutex<std::collections::HashSet<String>> {
    EARLY_DONE.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
}

fn early_is_done(op_id: &str) -> bool {
    early_done_set().lock().map(|s| s.contains(op_id)).unwrap_or(true)
}

/// The key record of chunks sent before the output was finished: its content
/// hash is not known yet, so it is bound to the bytes only once they are checked.
const EARLY_KEY_MARK: [u8; 32] = [0u8; 32];
/// Last byte of a key record whose file goes up as written (see `stable_media_key`).
const AS_WRITTEN: u8 = b'w';

fn is_upload_cancelled(op_id: &str) -> bool {
    let head = op_id.split('#').next().unwrap_or(op_id);
    match upload_cancel_set().lock() {
        Ok(s) => s.contains(op_id) || s.contains(head),
        Err(_) => false,
    }
}

/// Downloads the user cancelled, by op id. A download is one `block_on` call,
/// so cancelling the caller's coroutine left it running to the end.
static DOWNLOAD_CANCEL: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<String>>> =
    std::sync::OnceLock::new();

fn download_cancel_set() -> &'static std::sync::Mutex<std::collections::HashSet<String>> {
    DOWNLOAD_CANCEL.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
}

/// Run a download to completion, or stop it once its op is cancelled (checked
/// every 100 ms): dropping the future ends its requests; a streaming download
/// keeps its `.part` for a later resume. `None` = cancelled.
fn run_cancellable<F: std::future::Future>(op_id: Option<&str>, fut: F) -> Option<F::Output> {
    let rt = runtime();
    let Some(id) = op_id.map(str::to_string) else { return Some(rt.block_on(fut)) };
    if let Ok(mut s) = download_cancel_set().lock() { s.remove(&id); }
    let out = rt.block_on(async {
        tokio::pin!(fut);
        loop {
            tokio::select! {
                r = &mut fut => return Some(r),
                _ = tokio::time::sleep(std::time::Duration::from_millis(100)) => {
                    if download_cancel_set().lock().map(|s| s.contains(&id)).unwrap_or(false) {
                        return None;
                    }
                }
            }
        }
    });
    if let Ok(mut s) = download_cancel_set().lock() { s.remove(&id); }
    out
}

/// Uploads currently being worked on by someone else.
///
/// Exists because of a measured incident, not caution: after connectivity
/// returned, the app's retry and the core-queue tick both started the same
/// upload seconds apart — two owners racing on one progress bubble and a
/// duplicate relay event for a single message.
///
/// The claim is taken inside the upload itself, where both paths converge, so
/// there is no second way to learn "busy". In memory, not in the DB: the
/// question "is this work running in this process" is settled by process death.
static UPLOADS_IN_FLIGHT: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<String>>> =
    std::sync::OnceLock::new();

fn uploads_in_flight() -> &'static std::sync::Mutex<std::collections::HashSet<String>> {
    UPLOADS_IN_FLIGHT.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
}

/// Who is currently registered as waiting for the network.
///
/// Serves exactly one purpose: report the TRANSITION, not the state. The queue
/// tick runs every twelve seconds; a status repeated on every tick trained the
/// UI to ignore it, so a transient "failed" stayed on the bubble forever while
/// the upload was merely waiting for connectivity.
static WAITING_NETWORK: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<String>>> =
    std::sync::OnceLock::new();

fn waiting_network_set() -> &'static std::sync::Mutex<std::collections::HashSet<String>> {
    WAITING_NETWORK.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
}

/// Mark network-wait. `true` means a transition happened and must be reported.
fn mark_waiting_network(msg_id: &str) -> bool {
    waiting_network_set()
        .lock()
        .map(|mut s| s.insert(msg_id.to_string()))
        .unwrap_or(false)
}

/// The upload is moving again (or finished) — the wait no longer applies.
/// The next outage must announce itself anew.
fn clear_waiting_network(msg_id: &str) {
    if let Ok(mut s) = waiting_network_set().lock() {
        s.remove(msg_id);
    }
}

/// Claim an upload. `None` — already claimed, a second worker has nothing to do.
///
/// Release happens on `Drop`, not via a separate call: the upload has a dozen
/// early error returns, and any forgotten path would leave the upload
/// claimed forever — i.e. unsendable.
struct UploadClaim(String);

impl UploadClaim {
    fn take(op_id: &str) -> Option<Self> {
        let head = op_id.split('#').next().unwrap_or(op_id).to_string();
        let mut g = uploads_in_flight().lock().ok()?;
        if !g.insert(op_id.to_string()) {
            tracing::info!(target: "alpha_ffi", "UPLOAD занято op={op_id} — второй отправитель отступает");
            return None;
        }
        let _ = head;
        Some(Self(op_id.to_string()))
    }
}

impl Drop for UploadClaim {
    fn drop(&mut self) {
        if let Ok(mut g) = uploads_in_flight().lock() {
            g.remove(&self.0);
        }
    }
}

fn upload_is_in_flight(msg_id: &str) -> bool {
    match uploads_in_flight().lock() {
        Ok(g) => g.iter().any(|k| k == msg_id || k.starts_with(&format!("{msg_id}#"))),
        Err(_) => false,
    }
}

/// Prepared-output revision, parsed from the file name: `<index>.r<rev>.<ext>`.
///
/// The "source + edits + output profile version" fingerprint is computed by
/// the app when queueing. Here it is only read: a second "same job or not"
/// formula would eventually silently diverge from the first — and the cost of
/// divergence is confirmations from an old file being applied to a new one.
fn output_revision(file_path: &str) -> Option<u64> {
    let name = std::path::Path::new(file_path).file_name().and_then(|n| n.to_str())?;

    // 1. The authoritative revision of the prepared output, if any.
    if let Some(r) = name
        .split('.')
        .find_map(|p| p.strip_prefix('r'))
        .and_then(|r| r.parse::<u64>().ok())
    {
        return Some(r);
    }

    // 2. Otherwise — the IMMUTABLE SOURCE SNAPSHOT.
    //
    // Documents and "send as file" never go through preparation: nothing
    // transcodes them, so they have no output fingerprint. They must still
    // send the same bytes on every retry, otherwise resume cannot work for
    // them at all — a broken link once re-sent a whole file because the
    // revision was undefined and a fresh key was derived on each attempt.
    //
    // Identity here is the snapshot itself: the temp-storage copy is written
    // ONCE, never changes, and lives until the send finishes, surviving
    // retries. Different sends of the same file get different copies — i.e.
    // different identities — so one send's confirmations cannot apply to another.
    //
    // The file name is NOT parsed: no timestamp or index is extracted. It is
    // taken whole as an opaque snapshot address and mixed with the size — a
    // coarse check that it is the same file.
    // The content is not re-read: for large files that would cost a full disk
    // pass for a value that is already unambiguous.
    let size = std::fs::metadata(file_path).ok()?.len();
    if size == 0 {
        return None;
    }
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in file_path.as_bytes() {
        h ^= *b as u64;
        h = h.wrapping_mul(0x1000_0000_01b3);
    }
    for b in size.to_le_bytes() {
        h ^= b as u64;
        h = h.wrapping_mul(0x1000_0000_01b3);
    }
    Some(h)
}

/// Send checkpoint stored on top of the core's encrypted database.
///
/// Identity (which send, which element, which revision) is baked in here and
/// cannot be swapped from outside: otherwise "resume" would become the right
/// to attach someone else's confirmed chunks to your own send.
struct DbCheckpoint<'a> {
    db: &'a alpha_database::DatabasePool,
    send_id: String,
    item_index: u32,
    revision: u64,
    output_path: String,
}

impl alpha_media::resume::ChunkCheckpoint for DbCheckpoint<'_> {
    fn load(&self) -> Vec<alpha_media::ConfirmedChunk> {
        let conn = self.db.connection();
        let rows = match alpha_database::MediaSendObjectRepo::load_confirmed(
            &conn, &self.send_id, self.item_index, self.revision,
        ) {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!(target: "alpha_ffi", "RESUME читать нечего: {e}");
                return Vec::new();
            }
        };
        // An unusable row silently drops out: its only cost is re-sending that
        // chunk. Panicking here would turn one corrupt row into "the file can
        // never be sent at all".
        rows.into_iter()
            .filter_map(|r| {
                let id = alpha_media::media_ref::TransportObjectId::parse(&r.transport_object_id).ok()?;
                let locations: Vec<alpha_media::media_ref::ChunkLocation> =
                    serde_json::from_str(&r.locations_json).ok()?;
                if locations.is_empty() {
                    return None;
                }
                Some(alpha_media::ConfirmedChunk {
                    index: r.object_index,
                    total: r.object_total,
                    transport_object_id: id,
                    plaintext_bytes: r.plaintext_bytes,
                    ciphertext_bytes: r.ciphertext_bytes,
                    chunk_hash: r.chunk_hash,
                    locations,
                })
            })
            .collect()
    }

    fn commit(&self, batch: &[alpha_media::ConfirmedChunk]) -> Result<(), String> {
        let rows: Vec<alpha_database::ChunkConfirmation> = batch
            .iter()
            .filter_map(|c| {
                // Addresses are serialized the SAME way as in the ref — no
                // separate scheme for checkpoints.
                let locations_json = serde_json::to_string(&c.locations).ok()?;
                Some(alpha_database::ChunkConfirmation {
                    send_id: self.send_id.clone(),
                    item_index: self.item_index,
                    revision: self.revision,
                    object_index: c.index,
                    object_total: c.total,
                    output_path: self.output_path.clone(),
                    transport_object_id: c.transport_object_id.as_hex().to_string(),
                    plaintext_bytes: c.plaintext_bytes,
                    ciphertext_bytes: c.ciphertext_bytes,
                    chunk_hash: c.chunk_hash.clone(),
                    locations_json,
                })
            })
            .collect();
        let mut conn = self.db.connection();
        alpha_database::MediaSendObjectRepo::confirm_batch(&mut conn, &rows)
            .map(|_| ())
            .map_err(|e| e.to_string())
    }
}

/// Forget a cancellation once the send is gone, so the id can never poison a
/// later message that happens to reuse it (a retry does).
fn clear_upload_cancel(msg_id: &str) {
    if let Ok(mut s) = upload_cancel_set().lock() {
        s.remove(msg_id);
        s.retain(|k| k.split('#').next().unwrap_or(k) != msg_id);
    }
}

/// Display metadata the SENDER measured locally (only it can: the bytes are
/// end-to-end encrypted, so no server and no receiver can probe a duration or a
/// resolution out of them).
///
/// The album manifest has carried these keys since day one, which is why an
/// album's videos show their "0:15" badge and their true aspect ratio before a
/// byte is fetched. Single media never carried them, so a received video sat
/// there with no badge and a guessed 3:2 box until the whole file had been
/// downloaded. These are the keys the album already uses — same names, same
/// units — so the Kotlin side parses both with one function.
///
/// Merged into the ref at upload; `MediaRef::display` carries them through
/// every later parse, so no wire-format version changes.
fn merge_display_meta(v: &mut serde_json::Value, meta_json: &Option<String>) {
    const KEYS: [&str; 7] = ["duration_ms", "width", "height", "rotation", "mime", "codec", "waveform"];
    let raw = match meta_json {
        Some(s) if !s.is_empty() => s,
        _ => return,
    };
    let src = match serde_json::from_str::<serde_json::Value>(raw) {
        Ok(serde_json::Value::Object(m)) => m,
        _ => return, // malformed metadata must never fail a send
    };
    if let serde_json::Value::Object(dst) = v {
        for k in KEYS {
            if let Some(val) = src.get(k) {
                dst.insert(k.to_string(), val.clone());
            }
        }
    }
}

/// Stamp the sender's send time (ms) on a content message that has none, so
/// peers can order messages sent within the same second. Other payloads pass.
fn with_silent(payload: Vec<u8>) -> Vec<u8> {
    let Ok(mut msg) = alpha_messages::deserialize(&payload) else { return payload };
    if msg.silent() || !msg.is_user_content() { return payload }
    msg.set_silent(true);
    alpha_messages::serialize(&msg).unwrap_or(payload)
}

fn with_sent_ms(payload: Vec<u8>, ms: impl FnOnce() -> u64) -> Vec<u8> {
    let Ok(mut msg) = alpha_messages::deserialize(&payload) else { return payload };
    if msg.sent_ms() != 0 { return payload }
    msg.set_sent_ms(ms());
    if msg.sent_ms() == 0 { return payload }
    alpha_messages::serialize(&msg).unwrap_or(payload)
}

/// Where the net switch sends traffic in private mode.
fn tor_route_for(enabled: bool) -> Option<std::net::SocketAddr> {
    if enabled { TOR_DEFAULT_PROXY.parse().ok() } else { None }
}

/// The local switch listeners, started once per process: relays for our
/// identity, relays for our groups (circuits apart from the identity's), and
/// media (internal networks refused on the direct route). `None` if a listener
/// could not start: direct then.
struct NetSwitches {
    relay: Option<std::net::SocketAddr>,
    groups: Option<std::net::SocketAddr>,
    media: Option<std::net::SocketAddr>,
}

fn net_switches() -> &'static NetSwitches {
    static SW: std::sync::OnceLock<NetSwitches> = std::sync::OnceLock::new();
    SW.get_or_init(|| {
        let rt = runtime();
        let start = |what: &str, allow: fn(&std::net::IpAddr) -> bool| {
            rt.block_on(alpha_transport::net_switch::start(allow))
                .map_err(|e| tracing::warn!("NET_SWITCH {what} listener: {e}")).ok()
        };
        NetSwitches {
            relay: start("relay", |_| true),
            groups: start("groups", |_| true),
            media: start("media", alpha_media::egress::resolved_allowed),
        }
    })
}

/// The switch listener of secret chats' files: Tor whatever the mode. Started
/// once; without it the address leads nowhere, so a file never goes direct.
fn secret_media_proxy() -> std::net::SocketAddr {
    static SW: std::sync::OnceLock<(std::net::SocketAddr, Option<alpha_transport::net_switch::OnceListener>)> = std::sync::OnceLock::new();
    SW.get_or_init(|| match runtime().block_on(alpha_transport::net_switch::start_tor_only()) {
        Ok((addr, guard)) => (addr, Some(guard)),
        Err(e) => {
            tracing::error!("NET_SWITCH secret media listener: {e}");
            (std::net::SocketAddr::from(([127, 0, 0, 1], 9)), None)
        }
    }).0
}

/// Lazy global tokio runtime used by all sync FFI methods that need to run
/// async code (transport connect, publish, etc.). FFI calls come in on
/// non-tokio Kotlin threads, so `Handle::current()` would panic — we own
/// the runtime explicitly here.
fn runtime() -> &'static tokio::runtime::Runtime {
    use std::sync::OnceLock;
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("alpha-ffi")
            .build()
            .expect("failed to build tokio runtime")
    })
}

use crate::callbacks::CallbackDispatcher;

mod secret_chat;
mod devices;
mod relay_routes;
mod assistant;
pub use assistant::{is_local_chat, is_assistant_chat};

/// Running catch-ups and live relays, reported to the app as they change — the
/// app shows "connecting" / "updating" from it.
#[derive(Clone)]
struct SyncGauge {
    running: Arc<std::sync::atomic::AtomicUsize>,
    transport: Arc<NostrRelayClient>,
    callback: CallbackDispatcher,
}

impl SyncGauge {
    fn new(transport: Arc<NostrRelayClient>, callback: CallbackDispatcher) -> Self {
        Self { running: Arc::new(std::sync::atomic::AtomicUsize::new(0)), transport, callback }
    }

    /// A catch-up starts; it counts as running until the returned value drops.
    fn begin(&self) -> CatchUpRun {
        self.running.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        self.report();
        CatchUpRun(self.clone())
    }

    /// Reads the state when the report runs, so reports racing each other
    /// all end on the current one. Local state only, no network.
    fn report(&self) {
        let g = self.clone();
        runtime().spawn(async move {
            let statuses = g.transport.relay_statuses().await;
            let relays = statuses.iter().filter(|s| s.enabled).count() as u32;
            let connected = statuses.iter().filter(|s| s.enabled && s.connected).count() as u32;
            let catching_up = g.running.load(std::sync::atomic::Ordering::SeqCst) > 0;
            g.callback.on_sync_state(connected, relays, catching_up);
        });
    }
}

struct CatchUpRun(SyncGauge);

impl Drop for CatchUpRun {
    fn drop(&mut self) {
        self.0.running.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
        self.0.report();
    }
}
use crate::event_dispatcher::UnifiedEventDispatcher;
use crate::events::CoreEvent;

// --- FFI error type ---

/// Error type for all fallible FFI operations.
///
/// Struct variants with `message` field match the UDL definition
/// `[Error] interface MessengerError { InternalError(string message); ... }`.
#[derive(Debug, thiserror::Error)]
pub enum MessengerError {
    #[error("Internal error: {description}")]
    InternalError { description: String },
    #[error("Invalid argument: {description}")]
    InvalidArgument { description: String },
    #[error("Database error: {description}")]
    DatabaseError { description: String },
    #[error("Crypto error: {description}")]
    CryptoError { description: String },
    #[error("Network error: {description}")]
    NetworkError { description: String },
    #[error("KeyPackage exhausted for peer {peer} — they must publish fresh KeyPackages")]
    KeyPackageExhausted { peer: String },
}

impl From<String> for MessengerError {
    fn from(s: String) -> Self {
        MessengerError::InternalError { description: s }
    }
}

// --- FFI-safe type definitions ---

/// Message type enum matching the UDL definition.
///
/// Variant names use snake_case to match the UDL `enum MessageType { "text", ... }`
/// which UniFFI generates as `r#text`, etc.
#[derive(Debug, Clone, PartialEq, Eq)]
#[allow(non_camel_case_types)]
pub enum MessageType {
    text,
    edit,
    reaction,
    reply,
    delete,
    disappearing,
    media,
    read_signal,
}

impl From<alpha_database::models::MessageType> for MessageType {
    fn from(mt: alpha_database::models::MessageType) -> Self {
        match mt {
            alpha_database::models::MessageType::Text => MessageType::text,
            alpha_database::models::MessageType::Edit => MessageType::edit,
            alpha_database::models::MessageType::Reaction => MessageType::reaction,
            alpha_database::models::MessageType::Reply => MessageType::reply,
            alpha_database::models::MessageType::Delete => MessageType::delete,
            alpha_database::models::MessageType::Disappearing => MessageType::disappearing,
            alpha_database::models::MessageType::Media => MessageType::media,
            alpha_database::models::MessageType::ReadSignal => MessageType::read_signal,
        }
    }
}

/// Expiry policy for ephemeral messages exposed to FFI.
///
/// Struct variant syntax (`None {}`, `ViewOnce {}`) matches the UDL
/// `[Enum] interface ExpiryPolicy { None(); ... }` generated code.
/// See [`AlphaExpiryPolicy`] for detailed documentation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ExpiryPolicy {
    None {},
    AfterSend { ttl_secs: u32 },
    AfterRead { ttl_secs: u32 },
    ViewOnce {},
    Whisper { secs: u32 },
}

impl From<AlphaExpiryPolicy> for ExpiryPolicy {
    fn from(p: AlphaExpiryPolicy) -> Self {
        match p {
            AlphaExpiryPolicy::None => ExpiryPolicy::None {},
            AlphaExpiryPolicy::AfterSend { ttl_secs } => ExpiryPolicy::AfterSend { ttl_secs },
            AlphaExpiryPolicy::AfterRead { ttl_secs } => ExpiryPolicy::AfterRead { ttl_secs },
            AlphaExpiryPolicy::ViewOnce => ExpiryPolicy::ViewOnce {},
            AlphaExpiryPolicy::Whisper { secs, .. } => ExpiryPolicy::Whisper { secs },
        }
    }
}

/// Identity information exposed to FFI.
#[derive(Debug, Clone)]
pub struct IdentityInfo {
    pub nostr_pubkey: String,
    pub npub: String,
    pub mls_credential_hex: String,
}

/// Message information exposed to FFI.
#[derive(Debug, Clone)]
pub struct MessageInfo {
    pub id: String,
    pub chat_id: String,
    pub sender_id: String,
    pub msg_type: MessageType,
    pub content: Option<String>,
    pub reply_to: Option<String>,
    pub media_ref: Option<String>,
    pub ttl_seconds: Option<u32>,
    pub expires_at: Option<String>,
    pub expiry_policy: Option<ExpiryPolicy>,
    pub read_at: Option<String>,
    pub consumed: bool,
    pub created_at: String,
    pub edited_at: Option<String>,
    pub is_deleted: bool,
    /// When the message became fully delivered (all recipients ACKed) — RFC3339,
    /// or None. Persisted (TD-003) so DELIVERED (✓✓) survives reload.
    pub delivered_at: Option<String>,
}

impl From<alpha_database::models::Message> for MessageInfo {
    fn from(msg: alpha_database::models::Message) -> Self {
        Self {
            id: msg.id,
            chat_id: msg.chat_id,
            sender_id: msg.sender_id,
            msg_type: msg.msg_type.into(),
            content: msg.content,
            reply_to: msg.reply_to,
            media_ref: msg.media_ref,
            ttl_seconds: msg.ttl_seconds.map(|t| t as u32),
            expires_at: msg.expires_at,
            expiry_policy: msg.expiry_policy.map(|p| match p {
                alpha_database::models::ExpiryPolicyType::None => ExpiryPolicy::None {},
                alpha_database::models::ExpiryPolicyType::AfterSend => {
                    // TTL is in ttl_seconds field
                    ExpiryPolicy::AfterSend {
                        ttl_secs: msg.ttl_seconds.unwrap_or(0) as u32,
                    }
                }
                alpha_database::models::ExpiryPolicyType::AfterRead => {
                    ExpiryPolicy::AfterRead {
                        ttl_secs: msg.ttl_seconds.unwrap_or(0) as u32,
                    }
                }
                // A one-view row that carries a reading time is a whisper.
                alpha_database::models::ExpiryPolicyType::ViewOnce => match msg.ttl_seconds {
                    Some(secs) if secs > 0 => ExpiryPolicy::Whisper { secs: secs as u32 },
                    _ => ExpiryPolicy::ViewOnce {},
                },
            }),
            read_at: msg.read_at,
            consumed: msg.consumed,
            created_at: msg.created_at,
            edited_at: msg.edited_at,
            is_deleted: msg.is_deleted,
            delivered_at: msg.delivered_at,
        }
    }
}

/// Chat information exposed to FFI.
#[derive(Debug, Clone)]
pub struct ChatInfo {
    pub id: String,
    pub chat_type: String,
    pub name: Option<String>,
    pub epoch: u64,
    pub created_at: String,
    // Chat-list extras.
    /// One-line preview of the latest message ("📷 Photo", "Hi!", …).
    /// Empty string for chats with no messages yet.
    pub last_message_preview: String,
    /// RFC3339 ts of the latest message — empty if no messages.
    pub last_message_at: String,
    /// Message type discriminator for the preview icon ("text", "media", …).
    pub last_message_kind: String,
    /// Whether the latest message was sent by us — affects bubble/avatar
    /// in the preview (e.g. "You: ..." vs sender's name).
    pub last_message_outgoing: bool,
    /// Delivery status of the latest message when outgoing: "sending" (queued/
    /// uploading), "sent" (✓), "read" (✓✓). Empty for incoming / no messages.
    pub last_message_status: String,
    /// Unread incoming count.
    pub unread_count: u32,
    /// Manual unread mark from long-press menu — UI shows badge "•" even
    /// when `unread_count` == 0.
    pub unread_override: bool,
    /// Unix ts (seconds) when this chat was pinned, or 0.
    pub pinned_at: u64,
    /// Unix ts when mute expires; 0 = not muted; i64::MAX = forever.
    pub muted_until: u64,
    /// Unix ts when archived; 0 = not archived.
    pub archived_at: u64,
    // --- Mirror Chat metadata so UI can render avatars ---
    pub avatar_path: String,
    pub description: String,
    /// Per-chat disappearing-messages timer in seconds (0 = off).
    pub ephemeral_seconds: u32,
    /// Last message is an incoming voice note not yet played.
    pub last_message_unplayed: bool,
    /// Members of a group (its MLS roster); 0 for a 1:1.
    pub member_count: u32,
    /// My role in a group: "owner" | "admin" | "moderator" | "member"; empty for a 1:1.
    pub my_role: String,
    /// A group's channel settings, as [channel_settings] gives them; empty for a 1:1.
    pub channel_settings: String,
}

/// A time from the app (RFC 3339, seconds optional) as the UTC text the
/// messages table compares against.
fn utc_bound(t: &str) -> Option<String> {
    let parsed = chrono::DateTime::parse_from_rfc3339(t).ok()
        .or_else(|| chrono::DateTime::parse_from_str(t, "%Y-%m-%dT%H:%M%:z").ok())?;
    Some(parsed.with_timezone(&chrono::Utc).format("%Y-%m-%dT%H:%M:%S").to_string())
}

#[cfg(test)]
mod utc_bound_tests {
    #[test]
    fn midnight_with_or_without_seconds() {
        assert_eq!(super::utc_bound("2026-11-05T00:00+01:00").as_deref(), Some("2026-11-04T23:00:00"));
        assert_eq!(super::utc_bound("2026-11-05T00:00:00+01:00").as_deref(), Some("2026-11-04T23:00:00"));
        assert_eq!(super::utc_bound("yesterday"), None);
    }
}

/// FFI: one message found by the search.
#[derive(Debug, Clone)]
pub struct SearchHit {
    pub message_id: String,
    pub chat_id: String,
    pub sender_id: String,
    /// The stored text, markers included (the screen strips them).
    pub content: String,
    pub created_at: String,
    pub msg_type: String,
    /// "media" | "file" | "link" | "music" | "voice", or empty.
    pub media_kind: String,
    /// A file's name, a link's title and address; empty otherwise.
    pub media_name: String,
}

/// FFI: own profile (display name + avatar path + bio). Persisted locally
/// in KV — propagation to peers via MLS application messages comes in the
/// next iteration.
#[derive(Debug, Clone)]
pub struct ProfileInfo {
    pub display_name: String,
    pub bio: String,
    pub avatar_path: String,        // empty = no avatar set
    pub npub: String,
}

/// A peer's profile (display_name + avatar) exposed to Kotlin so the UI
/// can render names in bubble headers, chat list rows, chat top bar.
///
/// Sourced from the local `contacts` table — populated by incoming
/// `AlphaMessage::ProfileUpdate` events. All fields are empty when nothing
/// is known about the peer yet (e.g. they haven't sent us a profile, or
/// haven't sent us a message at all).
#[derive(Debug, Clone)]
pub struct PeerProfile {
    /// MLS leaf signature key hex — the stable per-peer id we use across
    /// the app. Same as `sender_id` on incoming messages.
    pub id: String,
    pub display_name: String,
    pub bio: String,
    /// Absolute file path to the cached avatar JPEG, empty if none.
    pub avatar_path: String,
    pub avatar_hash: String,
    pub profile_version: u64,
}

/// Relay status snapshot for the Settings → Relays screen.
///
/// `ping_ms` is only populated by `ping_all_relays()`; `list_relay_statuses()`
/// returns it as 0 (UniFFI doesn't allow Option<u64> in dictionaries we already
/// expose to Kotlin so we use 0 as "unknown" / "not measured").
#[derive(Debug, Clone)]
pub struct RelayInfo {
    pub url: String,
    pub read: bool,
    pub write: bool,
    pub enabled: bool,
    pub connected: bool,
    /// 0 = unknown / not measured / unreachable. Use `ping_all_relays()` for live values.
    pub ping_ms: u32,
    /// True for .onion addresses — requires Tor / Orbot to be routed.
    pub is_tor: bool,
    /// "connected", "connecting", "unreachable", "limited" or "disabled".
    pub state: String,
    /// Seconds until the next try (unreachable) or the end of its pause (limited).
    pub retry_secs: u32,
    /// Serves our inbox only after AUTH: other relays carry our private messages.
    pub inbox_auth: bool,
    pub accepted: u32,
    pub rejected: u32,
    /// The relay's own words for its last refusal; empty when the last event went in.
    pub last_error: String,
}

const PROFILE_KV_NAME: &str = "my_profile_display_name";
/// Monotonically-increasing counter for the user's own profile broadcasts.
/// Bumped every time `broadcast_my_profile` ships an update. Receivers use
/// this for replay protection: only apply a ProfileUpdate if its version
/// is strictly greater than the one already in `contacts.profile_version`.
const PROFILE_KV_VERSION: &str = "my_profile_version";
const PROFILE_KV_BIO: &str = "my_profile_bio";
const PROFILE_KV_AVATAR: &str = "my_profile_avatar_path";
const PROFILE_KV_COVER: &str = "my_profile_cover_path";
// Profile images now travel via the blob/chunk pipeline — ProfileUpdate carries
// only these small refs (MediaRef JSON) + hashes + versions, never the JPEG.
const PROFILE_KV_AVATAR_REF: &str = "my_avatar_ref";
const PROFILE_KV_AVATAR_MHASH: &str = "my_avatar_mhash";   // MediaRef hash (broadcast/dedup)
const PROFILE_KV_AVATAR_SRC: &str = "my_avatar_srchash";   // local file sha256 (change detect)
const PROFILE_KV_AVATAR_VER: &str = "my_avatar_ver";
const PROFILE_KV_COVER_REF: &str = "my_cover_ref";
const PROFILE_KV_COVER_MHASH: &str = "my_cover_mhash";
const PROFILE_KV_COVER_SRC: &str = "my_cover_srchash";
const PROFILE_KV_COVER_VER: &str = "my_cover_ver";
/// Our video avatar: local file, then its blob ref / hashes / version (as the cover).
const PROFILE_KV_VIDEO: &str = "my_profile_video_path";
const PROFILE_KV_VIDEO_REF: &str = "my_video_ref";
const PROFILE_KV_VIDEO_MHASH: &str = "my_video_mhash";
const PROFILE_KV_VIDEO_SRC: &str = "my_video_srchash";
const PROFILE_KV_VIDEO_VER: &str = "my_video_ver";

/// SHA256 hex of a file's bytes (cheap change-detection key). "" on error.
fn file_sha256(path: &str) -> String {
    use sha2::Digest;
    if path.is_empty() { return String::new(); }
    match std::fs::read(path) {
        Ok(b) => format!("{:x}", sha2::Sha256::digest(&b)),
        Err(_) => String::new(),
    }
}

/// Download one profile-media blob by its MediaRef; the pipeline verifies the
/// content hash internally. `None` on any failure.
async fn fetch_profile_blob(mp: &MediaPipeline, ref_json: &str) -> Option<Vec<u8>> {
    let mref: MediaRef = serde_json::from_str(ref_json).ok()?;
    let up = mref.to_upload_result().ok()?;
    mp.download(&up.media_key, &up.media_hash, &up.blob_urls, up.original_size).await.ok()
}

/// tmp-write + rename = never a torn/half-written file at `target`.
fn atomic_write_file(target: &std::path::Path, bytes: &[u8]) -> bool {
    if let Some(parent) = target.parent() { let _ = std::fs::create_dir_all(parent); }
    let tmp = target.with_extension("tmp");
    if std::fs::write(&tmp, bytes).is_err() { return false; }
    std::fs::rename(&tmp, target).is_ok()
}

/// Background: fetch a peer's changed avatar/cover via the blob pipeline,
/// atomically replace the local file (old image stays until success), commit
/// the new hash, and notify the UI. Concurrency-capped so it never crowds out
/// video streaming. This future is `Send` (blossom's download progress cb is
/// now `dyn FnMut(u64) + Send`), so callers use a plain `tokio::spawn` — no
/// per-download OS thread.
#[allow(clippy::too_many_arguments)]
async fn download_peer_profile_media(
    db: Arc<alpha_database::DatabasePool>,
    media_pipeline: Arc<MediaPipeline>,
    callback: CallbackDispatcher,
    avatars_dir: std::path::PathBuf,
    sem: Arc<tokio::sync::Semaphore>,
    sender_id: String,
    avatar_ref: String,
    avatar_hash: String,
    cover_ref: String,
    cover_hash: String,
    video_ref: String,
    video_hash: String,
) {
    let _permit = match sem.acquire().await { Ok(p) => p, Err(_) => return };
    if !video_ref.is_empty() {
        if let Some(bytes) = fetch_profile_blob(&media_pipeline, &video_ref).await {
            let target = avatars_dir.join(format!("{}_video.mp4", sender_id));
            if atomic_write_file(&target, &bytes) {
                {
                    let conn = db.connection();
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("peer_video_path:{}", sender_id), &target.to_string_lossy());
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("peer_video_hash:{}", sender_id), &video_hash);
                }
                callback.on_peer_profile_updated(&sender_id);
                tracing::info!("peer video avatar downloaded: {} ({} bytes)", &sender_id[..sender_id.len().min(8)], bytes.len());
            }
        }
    }

    if !avatar_ref.is_empty() {
        if let Some(bytes) = fetch_profile_blob(&media_pipeline, &avatar_ref).await {
            let target = avatars_dir.join(format!("{}.jpg", sender_id));
            if atomic_write_file(&target, &bytes) {
                {
                    let conn = db.connection();
                    let _ = alpha_database::ContactRepo::set_avatar(
                        &conn, &sender_id, &target.to_string_lossy(), &avatar_hash);
                }
                callback.on_peer_profile_updated(&sender_id);
                tracing::info!("peer avatar downloaded: {} ({} bytes)", &sender_id[..sender_id.len().min(8)], bytes.len());
            }
        }
    }
    if !cover_ref.is_empty() {
        if let Some(bytes) = fetch_profile_blob(&media_pipeline, &cover_ref).await {
            let target = avatars_dir.join(format!("{}_cover.jpg", sender_id));
            if atomic_write_file(&target, &bytes) {
                {
                    let conn = db.connection();
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("peer_cover_path:{}", sender_id), &target.to_string_lossy());
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("peer_cover_hash:{}", sender_id), &cover_hash);
                }
                callback.on_peer_profile_updated(&sender_id);
                tracing::info!("peer cover downloaded: {} ({} bytes)", &sender_id[..sender_id.len().min(8)], bytes.len());
            }
        }
    }
}

/// Reserved chat_id for the local-only "Saved Messages" chat.
/// Recognised by send_* and process_application_message so we don't try to
/// MLS-encrypt or publish notes-to-self.
pub const SAVED_MESSAGES_CHAT_ID: &str = "saved_messages_local";


/// Build a ChatInfo with empty preview / zero counters — for places that
/// return a freshly-created chat (no messages, no flags yet). list_chats
/// hydrates these fields properly with a per-chat query.
fn chat_info_basic(id: String, chat_type: String, name: Option<String>, epoch: u64, created_at: String) -> ChatInfo {
    ChatInfo {
        id,
        chat_type,
        name,
        epoch,
        created_at,
        last_message_preview: String::new(),
        last_message_at: String::new(),
        last_message_kind: String::new(),
        last_message_outgoing: false,
        last_message_status: String::new(),
        unread_count: 0,
        unread_override: false,
        pinned_at: 0,
        muted_until: 0,
        archived_at: 0,
        avatar_path: String::new(),
        description: String::new(),
        ephemeral_seconds: 0,
        last_message_unplayed: false,
        member_count: 0,
        my_role: String::new(),
        channel_settings: String::new(),
    }
}

///
/// Stored as JSON under `KV_RECOVERY_KDF`. Contains ONLY public data:
/// the Argon2id parameters, the random salt, and a non-reversible verifier.
/// The password and the derived master key are NEVER persisted.
#[derive(serde::Serialize, serde::Deserialize)]
struct RecoveryKdfRecord {
    /// KDF design version (alpha_keys::ALPHA_KDF_VERSION).
    version: u32,
    /// Argon2id parameters (memory/time/parallelism).
    params: KdfParams,
    /// Base64-encoded 16-byte salt (public).
    salt_b64: String,
    /// Base64-encoded 32-byte verifier (public; not reversible).
    verifier_b64: String,
}

impl RecoveryKdfRecord {
    /// Decode the base64 verifier into its raw 32 bytes.
    fn verifier_bytes(&self) -> [u8; 32] {
        let decoded = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &self.verifier_b64)
            .unwrap_or_default();
        let mut arr = [0u8; 32];
        if decoded.len() == 32 {
            arr.copy_from_slice(&decoded);
        }
        arr
    }
}

// --- Core object ---

/// The main FFI-exposed object that wraps all AlphaMessenger
/// functionality behind a single, thread-safe interface.
pub struct AlphaMessengerCore {
    /// Encrypted database pool.
    db: Arc<DatabasePool>,
    /// Filesystem path of the SQLCipher database file. Needed by the rekey
    /// orchestrator for the pre-rekey file backup and the second-connection
    /// verify.
    db_path: std::path::PathBuf,
    /// Set if this open performed a one-shot restore→legacy fallback
    /// (a `migrated`/`in_progress` DB that failed to open under db_key). The
    /// caller reads it via [`Self::db_did_recovery_restore`] to persist the
    /// one-shot flag so a second failure becomes a hard error (no restore loop).
    did_recovery_restore: std::sync::atomic::AtomicBool,
    /// System-level non-reentrancy guard for the rekey migration.
    /// Beyond the per-connection Mutex, this ensures only ONE migration can be
    /// in flight at a time even if two callers race into the orchestrator.
    migration_in_flight: std::sync::atomic::AtomicBool,
    /// Dual identity (Nostr + MLS).
    identity: Arc<TrackedMutex<Option<DualIdentity>>>,
    /// MLS session manager.
    mls_session: Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    /// Nostr relay transport.
    transport: Arc<NostrRelayClient>,
    /// Media upload/download pipeline.
    media_pipeline: Arc<MediaPipeline>,
    /// Raw Blossom client (no media-pipeline encryption) for the encrypted
    /// account backup container. Backup blobs are already AEAD-sealed
    /// by `alpha_keys::encrypt_backup`, so they go to Blossom as opaque bytes.
    blossom: Arc<BlossomClient>,
    /// The same, over a Tor-only channel: files of secret chats.
    secret_media: Arc<MediaPipeline>,
    secret_blossom: Arc<BlossomClient>,
    /// Catch-up sync manager.
    sync_manager: Arc<TrackedMutex<Option<CatchUpManager>>>,
    /// Notification handler.
    notification_handler: Arc<Mutex<Option<NotificationHandler>>>,
    /// Wake service.
    wake_service: Arc<Mutex<Option<WakeService>>>,
    /// Handle for the realtime relay listener task spawned by `start_sync`.
    /// Aborted in `stop_sync`.
    realtime_handle: Arc<Mutex<Option<JoinHandle<()>>>>,
    /// Handle for the transport heartbeat task. Pings relays every
    /// ~30s; on sustained loss it forces a clean reconnect. Aborted in stop_sync.
    heartbeat_handle: Arc<Mutex<Option<JoinHandle<()>>>>,
    /// Wall-clock of the last realtime event we received from any relay. The
    /// heartbeat uses it for STALE detection ("socket alive but subscription
    /// dead"): if connected yet silent too long, it re-subscribes.
    last_event_at: Arc<std::sync::Mutex<std::time::Instant>>,
    /// Pulse of the transport heartbeat task (unix secs of its last tick).
    /// Watched from OUTSIDE the core: a heartbeat that stopped ticking (a
    /// network wait that never returned) is noticed and the sync restarted.
    heartbeat_at: Arc<std::sync::atomic::AtomicI64>,
    /// Relays that closed our live subscription: the heartbeat asks each again
    /// once its pause is over.
    resubscribe_wanted: Arc<std::sync::Mutex<std::collections::HashSet<String>>>,
    /// Unix secs of the last finished catch-up pass.
    catchup_at: Arc<std::sync::atomic::AtomicI64>,
    /// Running catch-ups and live relays, reported to the app as they change.
    sync_gauge: SyncGauge,
    /// Wall-clock of the last successful relay reconnect/resubscribe (heartbeat
    /// STALE path OR an FFI `force_reconnect`). Used to debounce redundant full
    /// reconnects: when several triggers fire within a few seconds (heartbeat
    /// soft-resubscribe → ntfy-wake callback → Kotlin force_reconnect, or
    /// foreground + network-available coinciding), the later ones downgrade to a
    /// cheap resubscribe instead of tearing down every socket again.
    last_reconnect_at: Arc<std::sync::Mutex<std::time::Instant>>,
    /// True while a full relay reconnect (disconnect → connect → resubscribe) is
    /// actually executing. The time-based `last_reconnect_at` debounce can't see
    /// an in-flight reconnect (its timestamp is only written on completion ~300ms
    /// later), so a second trigger arriving mid-reconnect would tear the sockets
    /// down again. This flag serialises them: a concurrent caller downgrades to a
    /// cheap resubscribe instead of a second full teardown.
    reconnect_in_progress: Arc<std::sync::atomic::AtomicBool>,
    /// ntfy server URL (set when notifications start). Needed by the send path
    /// to publish peer-wake pushes to recipients' topics. None until
    /// `start_notifications` runs.
    ntfy_server_url: Arc<std::sync::Mutex<Option<String>>>,
    /// Per-topic debounce for outgoing peer-wake pushes: topic → last wake sent.
    /// Caps wakes at 1 per recipient per few seconds so a burst of messages
    /// doesn't spam ntfy (energy + rate-limit friendly).
    peer_wake_debounce: Arc<std::sync::Mutex<std::collections::HashMap<String, std::time::Instant>>>,
    /// In-memory queue of ResendRequests received for
    /// OUR messages (chat_id, from_seq, to_seq). The realtime listener pushes
    /// here; the delivery-resend timer drains it and re-publishes from the
    /// delivery outbox. Keeps the realtime path lock-free + lets the timer
    /// batch/coalesce.
    pending_resends: Arc<std::sync::Mutex<Vec<(String, u64, u64)>>>,
    /// Single-flight guard for the delivery timer loop (prevents
    /// multiple concurrent timer tasks if start_delivery_timers is called
    /// repeatedly on reconnect).
    /// The reliability controller: the single decision center for
    /// system health. Observes metrics from all subsystems and decides the
    /// minimal action (soft resubscribe / catch-up / gap recovery). Never
    /// panic-reconnects. Exposed to the UI via `get_health_status` /
    /// `get_health_snapshot_json`.
    reliability: Arc<crate::reliability::ReliabilityController>,
    /// Presence service — real online/away/busy/offline + custom status. Fully
    /// independent of MLS/chat (see PRESENCE_PROTOCOL.md). FFI surface
    /// only; transport heartbeat/ingest are handled by their own tasks.
    presence: Arc<Mutex<crate::presence_engine::PresenceEngine>>,
    /// Version of the transport key ring last persisted + subscribed.
    ring_synced: Arc<std::sync::atomic::AtomicU64>,
    /// Presence routing tag → (contact npub hex, key), rebuilt every tick.
    /// Presence channel → everyone it belongs to (one secret can be known under
    /// several of its owner's signature keys) and its key.
    presence_routes: Arc<Mutex<std::collections::HashMap<alpha_transport::sealed::Tag16, (Vec<String>, alpha_transport::sealed::Key32)>>>,
    /// Opened presence heartbeats (npub hex, json) waiting for the next tick.
    presence_inbox: Arc<Mutex<Vec<(String, String)>>>,
    /// Single-flight guard for the health timer loop.
    /// Callback dispatcher.
    callback: CallbackDispatcher,
    /// Unified Event Dispatcher. The single exit point for all
    /// high-frequency events (media progress, typing, delivery). Coalesces +
    /// batches them into ONE `on_events_batch` FFI call instead of a per-event
    /// flood. Shares the same `callback` (clone) so `set_callback` reaches both.
    event_dispatcher: Arc<UnifiedEventDispatcher>,
    /// Directory where incoming peer avatars get cached as JPEGs.
    /// Sits next to the SQLCipher DB at <db_path's parent>/contact_avatars/.
    /// Synthesized in `new()`.
    contact_avatars_dir: std::path::PathBuf,
    /// Directory where group avatars (admin broadcasts) get cached.
    /// `<db_path's parent>/group_avatars/`.
    group_avatars_dir: std::path::PathBuf,
    /// Caps concurrent background avatar/cover blob downloads (small images →
    /// must never crowd out video streaming).
    profile_dl_sem: Arc<tokio::sync::Semaphore>,
    /// Serialises `flush_pending_sends` so concurrent flushes (a live send +
    /// a reconnect trigger) can't double-encrypt/re-order the queue.
    flush_lock: Mutex<()>,
    /// Per-peer set of KeyPackages we've already consumed this session
    /// (peer_pubkey_hex → set of kp_bytes). MLS KeyPackages are ONE-TIME, so
    /// adding the same peer to several groups MUST use a DISTINCT KeyPackage
    /// each time — reuse makes the peer fail to join with NoMatchingKeyPackage.
    used_key_packages: Arc<Mutex<std::collections::HashMap<String, std::collections::HashSet<Vec<u8>>>>>,
}

impl AlphaMessengerCore {
    /// Create a new `AlphaMessengerCore`.
    ///
    /// - `db_path`: path to the encrypted SQLite database.
    /// - `passphrase`: encryption passphrase for the database.
    /// - `relay_urls_json`: JSON array of Nostr relay URLs.
    pub fn new(db_path: String, passphrase: String, relay_urls_json: String) -> Result<Self, MessengerError> {
        // Wiped from memory when this call ends.
        let passphrase = zeroize::Zeroizing::new(passphrase);
        // Parse relay URLs.
        let relay_urls: Vec<String> = serde_json::from_str(&relay_urls_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid relay_urls_json: {}", e) })?;

        // Open the database (legacy passphrase path).
        let db = DatabasePool::open(Path::new(&db_path), &passphrase)
            .map_err(|e| MessengerError::DatabaseError { description: format!("failed to open database: {}", e) })?;
        Self::finish_construction(Arc::new(db), db_path, relay_urls, false)
    }

    /// Open the DB by resolving the correct key from the migration
    /// state, then construct the core.
    ///
    /// - `master_key`: the 32-byte MasterDataKey (already unlocked by the caller
    ///   via biometric Keystore or Recovery Password). An empty vec forces the
    ///   legacy open (used only where no key is available yet).
    /// - `rekey_hint`: the plaintext pre-open hint from Android SharedPreferences
    ///   (`legacy`/`in_progress`/`migrated`/`confirmed`). The DB KV row remains
    ///   the authority AFTER open; this is only the first-attempt optimisation.
    /// - `recovery_already_attempted`: the one-shot fallback flag mirror — true
    ///   if a previous launch already did a restore→legacy for this DB.
    ///
    /// The single deterministic open decision lives in [`resolve_and_open`];
    /// Android never replicates it — it passes the hint/flag in and reads the
    /// reconciled outcome back via [`Self::db_rekey_state`] /
    /// [`Self::db_did_recovery_restore`].
    pub fn new_unlocked(
        db_path: String,
        master_key: Vec<u8>,
        relay_urls_json: String,
        rekey_hint: String,
        recovery_already_attempted: bool,
        device_key: Vec<u8>,
    ) -> Result<Self, MessengerError> {
        let relay_urls: Vec<String> = serde_json::from_str(&relay_urls_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid relay_urls_json: {}", e) })?;

        // Derive the DatabaseKey hex if a 32-byte master key was supplied.
        let db_key_hex = if master_key.len() == 32 {
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&master_key);
            let db_key = keys::derive_subkey(&keys::SecretKey::from_bytes(arr), keys::KDF_INFO_DB);
            if db_key.as_bytes().iter().all(|&b| b == 0) {
                return Err(MessengerError::CryptoError {
                    description: "derived all-zero database key".to_string(),
                });
            }
            Some(hex::encode(db_key.as_bytes()))
        } else {
            None
        };

        let device_key_hex = match device_key.len() {
            0 => None,
            32 if device_key.iter().any(|&b| b != 0) => Some(hex::encode(&device_key)),
            n => return Err(MessengerError::InvalidArgument { description: format!("device key must be 32 bytes, got {}", n) }),
        };
        let resolved = resolve_and_open(
            Path::new(&db_path),
            db_key_hex.as_deref(),
            device_key_hex.as_deref(),
            &rekey_hint,
            recovery_already_attempted,
        )?;
        let did_restore = resolved.recovery_attempted_now;
        Self::finish_construction(Arc::new(resolved.pool), db_path, relay_urls, did_restore)
    }

    /// Post-open construction shared by [`Self::new`] and [`Self::new_unlocked`].
    /// `did_recovery_restore` records whether [`resolve_and_open`] just performed
    /// a restore→legacy fallback, so the caller can persist the one-shot flag.
    fn finish_construction(
        db: Arc<DatabasePool>,
        db_path: String,
        relay_urls: Vec<String>,
        did_recovery_restore: bool,
    ) -> Result<Self, MessengerError> {
        // Create the identity (load existing or generate new).
        let identity = Arc::new(TrackedMutex::new("identity", None::<DualIdentity>));

        // One-time relay reset (v3): a single default relay (Damus) was a single
        // point of failure — when it returned 503, KeyPackage publish and group
        // delivery broke entirely. Clear the persisted list ONCE so existing
        // installs re-seed from the redundant defaults (Damus + nos.lol + primal).
        // Runs before the load below. (v2 was the earlier "Damus only" reset.)
        {
            let conn = db.connection();
            let done = KeyValueRepo::get(&conn, "relay_defaults_v3").ok().flatten().is_some();
            if !done {
                let _ = KeyValueRepo::delete(&conn, KV_RELAYS);
                let _ = KeyValueRepo::set(&conn, "relay_defaults_v3", "1");
            }
        }

        // One-time (v4): three more relays join an existing saved list, so one
        // relay that throttles or bans can't cut a chat off. Nothing the user
        // chose is reset; a list the user emptied stays empty.
        {
            let conn = db.connection();
            if KeyValueRepo::get(&conn, "relay_defaults_v4").ok().flatten().is_none() {
                if let Ok(Some(json)) = KeyValueRepo::get(&conn, KV_RELAYS) {
                    if let Ok(mut list) = serde_json::from_str::<Vec<PersistedRelay>>(&json) {
                        if !list.is_empty() {
                            for url in ["wss://nostr.mom", "wss://nostr.oxtr.dev"] {
                                if !list.iter().any(|r| r.url.trim_end_matches('/') == url) {
                                    list.push(PersistedRelay { url: url.to_string(), read: true, write: true, enabled: true });
                                }
                            }
                            if let Ok(j) = serde_json::to_string(&list) {
                                let _ = KeyValueRepo::set(&conn, KV_RELAYS, &j);
                            }
                        }
                    }
                }
                let _ = KeyValueRepo::set(&conn, "relay_defaults_v4", "1");
            }
            // v5: offchain.pub refuses events of keys outside its "web of trust"
            // (every one of ours) — out of the saved lists again.
            if KeyValueRepo::get(&conn, "relay_defaults_v5").ok().flatten().is_none() {
                if let Ok(Some(json)) = KeyValueRepo::get(&conn, KV_RELAYS) {
                    if let Ok(mut list) = serde_json::from_str::<Vec<PersistedRelay>>(&json) {
                        let before = list.len();
                        list.retain(|r| r.url.trim_end_matches('/') != "wss://offchain.pub");
                        if list.len() != before && !list.is_empty() {
                            if let Ok(j) = serde_json::to_string(&list) {
                                let _ = KeyValueRepo::set(&conn, KV_RELAYS, &j);
                            }
                        }
                    }
                }
                let _ = KeyValueRepo::set(&conn, "relay_defaults_v5", "1");
            }
            // v6: Damus serves gift wraps only after AUTH (so no invitation ever
            // came through it) and bans our request rate — out of the saved lists.
            if KeyValueRepo::get(&conn, "relay_defaults_v6").ok().flatten().is_none() {
                if let Ok(Some(json)) = KeyValueRepo::get(&conn, KV_RELAYS) {
                    if let Ok(mut list) = serde_json::from_str::<Vec<PersistedRelay>>(&json) {
                        let before = list.len();
                        list.retain(|r| r.url.trim_end_matches('/') != "wss://relay.damus.io");
                        if list.len() != before && !list.is_empty() {
                            if let Ok(j) = serde_json::to_string(&list) {
                                let _ = KeyValueRepo::set(&conn, KV_RELAYS, &j);
                            }
                        }
                    }
                }
                let _ = KeyValueRepo::set(&conn, "relay_defaults_v6", "1");
            }
            // v7 (2026-11): nos.lol and nostr.mom (one host) refuse every connection
            // and are dropped; three relays probed good join. A relay the user
            // removed earlier is not brought back; an emptied list stays empty.
            if KeyValueRepo::get(&conn, "relay_defaults_v7").ok().flatten().is_none() {
                if let Ok(Some(json)) = KeyValueRepo::get(&conn, KV_RELAYS) {
                    if let Ok(mut list) = serde_json::from_str::<Vec<PersistedRelay>>(&json) {
                        if !list.is_empty() {
                            let dead = ["wss://nos.lol", "wss://nostr.mom"];
                            list.retain(|r| !dead.contains(&r.url.trim_end_matches('/')));
                            for url in ["wss://relay.nostr.net", "wss://nostr.data.haus", "wss://nostr.bitcoiner.social"] {
                                if !list.iter().any(|r| r.url.trim_end_matches('/') == url) {
                                    list.push(PersistedRelay { url: url.to_string(), read: true, write: true, enabled: true });
                                }
                            }
                            if let Ok(j) = serde_json::to_string(&list) {
                                let _ = KeyValueRepo::set(&conn, KV_RELAYS, &j);
                            }
                        }
                    }
                }
                let _ = KeyValueRepo::set(&conn, "relay_defaults_v7", "1");
            }
            // v8: the relay v7 put in refuses our events (its "web of trust"
            // takes no one-off key): out again, another open one in its place.
            if KeyValueRepo::get(&conn, "relay_defaults_v8").ok().flatten().is_none() {
                if let Ok(Some(json)) = KeyValueRepo::get(&conn, KV_RELAYS) {
                    if let Ok(mut list) = serde_json::from_str::<Vec<PersistedRelay>>(&json) {
                        if !list.is_empty() {
                            list.retain(|r| r.url.trim_end_matches('/') != "wss://nostr.bitcoiner.social");
                            let add = "wss://nostr-01.yakihonne.com";
                            if !list.iter().any(|r| r.url.trim_end_matches('/') == add) {
                                list.push(PersistedRelay { url: add.to_string(), read: true, write: true, enabled: true });
                            }
                            if let Ok(j) = serde_json::to_string(&list) {
                                let _ = KeyValueRepo::set(&conn, KV_RELAYS, &j);
                            }
                        }
                    }
                }
                let _ = KeyValueRepo::set(&conn, "relay_defaults_v8", "1");
            }
        }

        // One-time: comments stored in the old text format are filed under
        // their posts and lose the header (typed comments, schema v22).
        {
            let conn = db.connection();
            if KeyValueRepo::get(&conn, "comments_adopted_v1").ok().flatten().is_none() {
                let n = alpha_sync::adopt_legacy_comments(&conn);
                tracing::info!("comments: {} old-format comments filed under their posts", n);
                let _ = KeyValueRepo::set(&conn, "comments_adopted_v1", "1");
            }
        }

        // Load the relay set: prefer DB-persisted (user's manual edits)
        // over the default JSON passed in from Kotlin. Without this, every
        // process restart would silently re-add the relays the user just
        // deleted — exactly what was reported by the user.
        // True when the saved list is empty because the user removed every relay.
        let mut user_emptied = false;
        let (effective_urls, persisted_flags): (Vec<String>, Vec<(String, bool, bool, bool)>) = {
            let conn = db.connection();
            match KeyValueRepo::get(&conn, KV_RELAYS)
                .map_err(|e| MessengerError::DatabaseError { description: format!("relays load: {}", e) })?
            {
                Some(json) => {
                    match serde_json::from_str::<Vec<PersistedRelay>>(&json) {
                        // The user removed every relay: respect it, never
                        // bring the defaults back behind their back.
                        Ok(list) if list.is_empty() => {
                            tracing::info!("Relay list is empty by the user's choice");
                            user_emptied = true;
                            (Vec::new(), Vec::new())
                        }
                        Ok(list) => {
                            tracing::info!("Loaded {} relay(s) from DB", list.len());
                            let urls = list.iter().map(|r| r.url.clone()).collect();
                            let flags = list.into_iter()
                                .map(|r| (r.url, r.read, r.write, r.enabled))
                                .collect();
                            (urls, flags)
                        }
                        _ => {
                            tracing::info!("Persisted relay list empty/invalid — falling back to defaults");
                            (relay_urls.clone(), Vec::new())
                        }
                    }
                }
                None => {
                    tracing::info!("No persisted relay list — seeding from defaults ({} relay(s))", relay_urls.len());
                    (relay_urls.clone(), Vec::new())
                }
            }
        };

        // Relays, media and wake-ups all go through the local net switch; it
        // sends them straight out or into the in-app Tor, and private mode can
        // change while the app runs. Set before any network client is built.
        {
            let conn = db.connection();
            let proxy = KeyValueRepo::get(&conn, KV_TOR_PROXY).ok().flatten().unwrap_or_default();
            drop(conn);
            // Any saved value means "private mode": older builds stored the
            // standalone Tor port, the app now runs its own Tor.
            alpha_transport::net_switch::set_tor(tor_route_for(!proxy.trim().is_empty()));
            // Secret chats reach Tor whatever the mode.
            alpha_transport::net_switch::set_tor_port(TOR_DEFAULT_PROXY.parse().ok());
            let sw = net_switches();
            alpha_transport::nostr_relay_client::set_network_proxy(sw.relay);
            alpha_transport::nostr_relay_client::set_group_proxy(sw.groups);
            alpha_media::set_http_proxy(sw.media);
            alpha_notifications::set_http_proxy(sw.media);
        }

        // Create the transport with the effective URL set.
        let transport = NostrRelayClient::new(effective_urls.clone());
        let transport = Arc::new(transport);

        // Re-apply persisted flags (enabled/read/write) — the constructor
        // defaults them all to true, so without this disabled relays would
        // come back enabled after restart.
        if !persisted_flags.is_empty() {
            let rt = runtime();
            for (url, read, write, enabled) in &persisted_flags {
                let _ = rt.block_on(transport.update_relay_flags(url, Some(*read), Some(*write), Some(*enabled)));
            }
        }

        // First-launch seed: persist whatever we ended up with so
        // subsequent restarts read from DB.
        if persisted_flags.is_empty() && !user_emptied {
            let initial: Vec<PersistedRelay> = effective_urls.iter().map(|u| PersistedRelay {
                url: u.clone(), read: true, write: true, enabled: true,
            }).collect();
            if let Ok(json) = serde_json::to_string(&initial) {
                let conn = db.connection();
                let _ = KeyValueRepo::set(&conn, KV_RELAYS, &json);
            }
        }

        // Load existing identity from DB, or generate + persist a fresh one.
        // Persistence is critical: a regenerated identity each launch means
        // a new npub each launch — peers could never reach you.
        let loaded_identity = {
            let conn = db.connection();
            match KeyValueRepo::get(&conn, KV_IDENTITY)
                .map_err(|e| MessengerError::DatabaseError { description: format!("identity load: {}", e) })?
            {
                Some(json) => {
                    let serializable: alpha_identity::SerializableDualIdentity = serde_json::from_str(&json)
                        .map_err(|e| MessengerError::InternalError { description: format!("identity deserialize: {}", e) })?;
                    Some(DualIdentity::from_serializable(&serializable)
                        .map_err(|e| MessengerError::CryptoError { description: format!("identity from_serializable: {}", e) })?)
                }
                None => None,
            }
        };

        let active_identity = match loaded_identity {
            Some(id) => {
                tracing::info!("Loaded persisted identity from DB");
                id
            }
            None => {
                let fresh = DualIdentity::generate()
                    .map_err(|e| MessengerError::CryptoError { description: format!("failed to generate identity: {}", e) })?;
                let serializable = fresh.to_serializable();
                let json = serde_json::to_string(&serializable)
                    .map_err(|e| MessengerError::InternalError { description: format!("identity serialize: {}", e) })?;
                let conn = db.connection();
                KeyValueRepo::set(&conn, KV_IDENTITY, &json)
                    .map_err(|e| MessengerError::DatabaseError { description: format!("identity save: {}", e) })?;
                tracing::info!("Generated and persisted fresh identity");
                fresh
            }
        };

        // Create the MLS session manager bound to the active identity.
        let mls_signer = active_identity.mls().signer().clone();
        // Our leaf names our npub, signed by it: members can check it is ours.
        let mls_credential = AlphaCredential::new(
            &alpha_mls::leaf_binding::bind(active_identity.nostr().keypair().keys(), mls_signer.public()),
            active_identity.mls().signer(),
        )
        .map_err(|e| MessengerError::CryptoError { description: format!("failed to create MLS credential: {}", e) })?;
        let mut mls_session = MlsSessionManager::new_in_memory(mls_signer, mls_credential);

        // Groups re-opened below; checked against our signer once personas are in.
        let mut restored_groups: Vec<(String, Vec<u8>)> = Vec::new();

        // --- Rehydrate MLS storage from SQLCipher ---
        // OpenMlsRustCrypto ships with an in-RAM HashMap; we mirror it into
        // `mls_storage_v1` after every mutation. On startup we load the dump
        // back and ask MLS to re-open each known group so chats survive a
        // process kill / OOM / swipe-from-recents.
        {
            let conn = db.connection();

            // Diagnostic: dump everything we know about chat persistence
            // so we can see where data is being lost between sessions.
            match ChatRepo::list(&conn) {
                Ok(chats) => {
                    tracing::info!("startup: ChatRepo::list returned {} chat row(s)", chats.len());
                    for c in &chats {
                        tracing::info!(
                            "  chat row: id={} type={:?} name={:?} pinned={:?}",
                            c.id, c.chat_type, c.name, c.pinned_at
                        );
                    }
                }
                Err(e) => tracing::warn!("startup: ChatRepo::list failed: {}", e),
            }

            match alpha_database::MlsStorageRepo::load_snapshot(&conn) {
                Ok(entries) if !entries.is_empty() => {
                    tracing::info!("Loading {} MLS storage entries from SQLCipher", entries.len());
                    mls_session.restore_storage(entries);

                    // Re-activate every chat row we already know about. `load_group`
                    // silently returns false if the storage doesn't contain that
                    // group anymore (e.g. user deleted data) — fine, the UI will
                    // show the chat row with no MLS backing and any incoming
                    // ciphertext will report "Group not found".
                    match ChatRepo::list(&conn) {
                        Ok(chats) => {
                            tracing::info!("restoring MLS for {} chat row(s)", chats.len());
                            for chat in chats {
                                if let Ok(gid) = hex::decode(&chat.id) {
                                    match mls_session.load_group(&gid) {
                                        Ok(true) => {
                                            tracing::info!("Restored MLS group {}", chat.id);
                                            restored_groups.push((chat.id.clone(), gid.clone()));
                                        }
                                        Ok(false) => tracing::warn!(
                                            "Chat {} has no MLS state in storage — needs a fresh Welcome",
                                            chat.id,
                                        ),
                                        Err(e) => tracing::warn!(
                                            "load_group failed for {}: {}", chat.id, e,
                                        ),
                                    }
                                } else {
                                    tracing::info!("skip non-hex chat id (likely Saved Messages): {}", chat.id);
                                }
                            }
                        }
                        Err(e) => tracing::warn!("ChatRepo::list during restore: {}", e),
                    }
                }
                Ok(_) => tracing::info!("No persisted MLS state yet (first run)"),
                Err(e) => tracing::warn!("MLS storage load failed (continuing fresh): {}", e),
            }
        }

        // Personas (anonymous channels): who we are there — for MLS, the
        // transport's inboxes and the in-memory "who am I in this chat" cache.
        {
            let conn = db.connection();
            alpha_database::personas::load_cache(&conn);
            let mut inboxes = Vec::new();
            let mut active = 0usize;
            for r in alpha_database::personas::all(&conn) {
                let (Ok(p), Ok(gid)) = (alpha_mls::Persona::from_json(&r.mls), hex::decode(&r.chat_id)) else {
                    tracing::warn!("persona of {} unreadable — skipped", r.chat_id);
                    continue;
                };
                if let Ok(pk) = nostr::PublicKey::from_hex(&r.nostr_pk) { inboxes.push(pk); }
                let sig = hex::encode(p.public_key());
                if r.sig != sig { let _ = alpha_database::personas::set_sig(&conn, &r.chat_id, &sig); }
                // A persona still waiting for its Welcome is not "us" in that
                // chat yet: only its inbox listens.
                if r.joined {
                    mls_session.set_persona(&gid, p);
                    active += 1;
                }
            }
            let (routes, tor_only) = secret_chat::persona_routes(&conn);
            drop(conn);
            tracing::info!("personas: {} active, {} inbox(es), {} Tor-only", active, inboxes.len(), tor_only.len());
            transport.set_persona_inboxes(inboxes);
            transport.set_persona_routes(routes, tor_only);
            transport.set_persona_plans(secret_chat::persona_plans(&db.connection()));
        }
        for (chat_id, gid) in &restored_groups {
            if mls_session.signer_mismatch(gid) {
                tracing::warn!(
                    "MLS_SIGNER_MISMATCH chat={}: our leaf key is not the one we hold; peers will reject our messages",
                    chat_id,
                );
            }
        }

        // Sealed transport: restore the persisted epoch keys, then let MLS report
        // every (re)loaded group and every future epoch change into the ring.
        {
            let ring = transport.key_ring();
            if let Some(json) = KeyValueRepo::get(&db.connection(), KV_TRANSPORT_KEYRING).ok().flatten() {
                ring.import_json(&json);
            }
            let listener_ring = Arc::clone(&ring);
            mls_session.set_epoch_listener(Box::new(move |group_id, epoch, secret| {
                listener_ring.insert_epoch(group_id, epoch, secret);
            }));
            ring.retain_groups(&mls_session.active_groups());
            if let Ok(entries) = KeyValueRepo::list(&db.connection()) {
                if entries.iter().any(|e| e.key == KV_NO_STORE_DEFAULT && e.value == "1") {
                    transport.set_no_store_default(true);
                }
                for e in entries {
                    if let Some(chat) = e.key.strip_prefix(KV_NO_STORE_PREFIX) {
                        if let Ok(g) = hex::decode(chat) { transport.set_no_store(&g, e.value == "1"); }
                    }
                }
            }
            tracing::info!("transport keyring: {} active tag(s)", ring.all_tags().len());
        }

        let mls_session = Arc::new(TrackedMutex::new("mls", mls_session));

        // Store the active identity.
        {
            let mut guard = identity.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            *guard = Some(active_identity);
        }

        // Insert "self" as a contact so message FK constraints are satisfied.
        {
            let guard = identity.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let conn = db.connection();
            if let Some(ref id) = *guard {
                let _npub = id.npub()
                    .map_err(|e| MessengerError::CryptoError { description: format!("npub error: {}", e) })?;
                let now = chrono::Utc::now().to_rfc3339();
                let self_contact = alpha_database::models::Contact {
                    id: "self".to_string(),
                    display_name: Some("You".to_string()),
                    nostr_pubkey: id.nostr().public_key().to_bytes().to_vec(),
                    mls_credential: None,
                    created_at: now.clone(),
                    updated_at: now,
                    bio: String::new(),
                    avatar_path: String::new(),
                    avatar_hash: String::new(),
                    profile_version: 0,
                    profile_updated_at: None,
                };
                // Ignore error if contact already exists
                let _ = alpha_database::ContactRepo::insert(&conn, &self_contact);
                alpha_database::devices::set_own_account(&conn, &id.nostr().public_key_hex());
                alpha_database::devices::load_cache(&conn);
            }
        }

        // Create the media pipeline. Blossom is HTTP blob storage — it is a
        // SEPARATE service from the Nostr relays (which are wss://). Passing a
        // relay URL here produced "builder error for url (wss://…/upload)".
        // Use a fallback list of public Blossom servers.
        let blossom = BlossomClient::with_servers(
            &alpha_media::DEFAULT_BLOSSOM_SERVERS
                .iter()
                .map(|s| s.to_string())
                .collect::<Vec<_>>(),
        );
        let media_pipeline = Arc::new(MediaPipeline::new(blossom.clone()));
        let secret_blossom = BlossomClient::with_servers_via(
            &alpha_media::DEFAULT_BLOSSOM_SERVERS.iter().map(|s| s.to_string()).collect::<Vec<_>>(),
            secret_media_proxy(),
        );
        let secret_media = Arc::new(MediaPipeline::new(secret_blossom.clone()));
        let secret_blossom = Arc::new(secret_blossom);
        // Separate raw client for the encrypted backup container.
        // BlossomClient is cheaply cloneable (Arc<reqwest::Client> inside).
        let blossom_backup = Arc::new(blossom);

        // Build ONE CallbackDispatcher and share it with the unified
        // Event Dispatcher (both hold clones of the same inner Arc, so a later
        // `set_callback` reaches both). The UED spawns its flush thread here.
        let callback = CallbackDispatcher::new();
        let event_dispatcher = UnifiedEventDispatcher::start(callback.clone());
        let sync_gauge = SyncGauge::new(Arc::clone(&transport), callback.clone());

        // NIP-42 answers: each inbox with its own key (only to relays that refused it without).
        {
            let (db, identity) = (Arc::clone(&db), Arc::clone(&identity));
            transport.set_auth_signer(Arc::new(move |who: Option<&nostr::PublicKey>| match who {
                None => identity.lock().ok().and_then(|g| g.as_ref().map(|id| id.nostr().keypair().keys().clone())),
                Some(pk) => {
                    let hex = pk.to_hex();
                    alpha_database::personas::all(&db.connection()).into_iter()
                        .find(|r| r.nostr_pk == hex)
                        .and_then(|r| nostr::Keys::parse(&r.nostr_sk).ok())
                }
            }));
        }

        Ok(Self {
            db,
            identity,
            mls_session,
            transport,
            media_pipeline,
            blossom: blossom_backup,
            secret_media,
            secret_blossom,
            sync_manager: Arc::new(TrackedMutex::new("sync_manager", None)),
            notification_handler: Arc::new(Mutex::new(None)),
            wake_service: Arc::new(Mutex::new(None)),
            realtime_handle: Arc::new(Mutex::new(None)),
            heartbeat_handle: Arc::new(Mutex::new(None)),
            last_event_at: Arc::new(std::sync::Mutex::new(std::time::Instant::now())),
            heartbeat_at: Arc::new(std::sync::atomic::AtomicI64::new(0)),
            resubscribe_wanted: Arc::new(std::sync::Mutex::new(std::collections::HashSet::new())),
            catchup_at: Arc::new(std::sync::atomic::AtomicI64::new(0)),
            sync_gauge,
            // Initialise "far in the past" so the first reconnect always runs in
            // full (subtract the debounce window so elapsed() exceeds it at boot).
            last_reconnect_at: Arc::new(std::sync::Mutex::new(
                std::time::Instant::now()
                    .checked_sub(std::time::Duration::from_secs(3600))
                    .unwrap_or_else(std::time::Instant::now),
            )),
            reconnect_in_progress: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            ntfy_server_url: Arc::new(std::sync::Mutex::new(None)),
            peer_wake_debounce: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
            pending_resends: Arc::new(std::sync::Mutex::new(Vec::new())),
            reliability: Arc::new(crate::reliability::ReliabilityController::new()),
            presence: Arc::new(Mutex::new(crate::presence_engine::PresenceEngine::new())),
            ring_synced: Arc::new(std::sync::atomic::AtomicU64::new(u64::MAX)),
            presence_routes: Arc::new(Mutex::new(std::collections::HashMap::new())),
            presence_inbox: Arc::new(Mutex::new(Vec::new())),
            callback,
            event_dispatcher,
            contact_avatars_dir: Path::new(&db_path)
                .parent()
                .map(|p| p.to_path_buf())
                .unwrap_or_else(|| Path::new(".").to_path_buf())
                .join("contact_avatars"),
            group_avatars_dir: Path::new(&db_path)
                .parent()
                .map(|p| p.to_path_buf())
                .unwrap_or_else(|| Path::new(".").to_path_buf())
                .join("group_avatars"),
            profile_dl_sem: Arc::new(tokio::sync::Semaphore::new(3)),
            flush_lock: Mutex::new(()),
            used_key_packages: Arc::new(Mutex::new(std::collections::HashMap::new())),
            db_path: Path::new(&db_path).to_path_buf(),
            did_recovery_restore: std::sync::atomic::AtomicBool::new(did_recovery_restore),
            migration_in_flight: std::sync::atomic::AtomicBool::new(false),
        })
    }

    /// Pick a KeyPackage from a freshly-fetched `pool` that we have NOT already
    /// used for `peer_hex` this session, and mark it used. Guarantees distinct
    /// one-time KeyPackages across back-to-back invites of the same peer (so
    /// groups 2..N don't collide on a consumed KeyPackage). Falls back to a
    /// random pick (and resets the used-set) only if every fetched KP has been
    /// used — at which point the peer must have replenished its pool.
    fn pick_distinct_kp(&self, peer_hex: &str, pool: Vec<Vec<u8>>) -> Result<Vec<u8>, MessengerError> {
        use rand::seq::SliceRandom;
        // A KeyPackage found under this npub but whose leaf proves another
        // owner (or proves nothing it claims) is never used.
        let offered = pool.len();
        let pool: Vec<Vec<u8>> = pool.into_iter().filter(|kp| {
            match alpha_mls::leaf_binding::key_package_owner(kp) {
                Some(alpha_mls::LeafVerdict::Bound(pk)) => hex::encode(pk) == peer_hex,
                Some(alpha_mls::LeafVerdict::Legacy) => true,
                _ => false,
            }
        }).collect();
        if pool.len() != offered {
            tracing::warn!(target: "ACCOUNT", "KeyPackages refused: {} of {} do not prove this owner", offered - pool.len(), offered);
        }
        if pool.is_empty() {
            return Err(MessengerError::NetworkError {
                description: format!("no KeyPackage available for {} (peer must publish first)", peer_hex),
            });
        }
        // Persist consumed KPs in the DB so they survive restarts: an in-memory
        // set dies with the process, a reused KP then yields NoMatchingKeyPackage
        // and group creation fails. Read the consumed set from the DB and write
        // each new consumption back.
        let consumed: std::collections::HashSet<Vec<u8>> = {
            let conn = self.db.connection();
            let mut stmt = match conn.prepare(
                "SELECT kp_hash FROM consumed_key_packages WHERE peer_hex = ?1",
            ) {
                Ok(s) => s,
                Err(_) => {
                    // Table might not exist yet (pre-migration). Fall back to
                    // in-memory tracking.
                    let mut guard = self.used_key_packages.lock()
                        .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
                    let used = guard.entry(peer_hex.to_string()).or_default();
                    let unused: Vec<&Vec<u8>> = pool.iter().filter(|kp| !used.contains(*kp)).collect();
                    // NEVER reuse a consumed KeyPackage — surface an error.
                    let chosen = match unused.choose(&mut rand::thread_rng()) {
                        Some(kp) => (**kp).clone(),
                        None => return Err(MessengerError::KeyPackageExhausted { peer: peer_hex.to_string() }),
                    };
                    used.insert(chosen.clone());
                    return Ok(chosen);
                }
            };
            let rows = stmt.query_map(rusqlite::params![peer_hex], |r| r.get::<_, Vec<u8>>(0));
            match rows {
                Ok(rs) => rs.filter_map(|r| r.ok()).collect(),
                Err(_) => std::collections::HashSet::new(),
            }
        };

        // Hash each KP for comparison (the raw bytes are what we store).
        // Prefer an unused KP (not in the consumed set).
        let unused: Vec<&Vec<u8>> = pool.iter().filter(|kp| !consumed.contains(*kp)).collect();
        // NEVER fall back to a consumed KeyPackage: `pool.first()` may return
        // an already-consumed init_key → NoMatchingKeyPackage on the peer and
        // silently broken groups 2..N. Surface a typed error instead.
        let chosen = match unused.choose(&mut rand::thread_rng()) {
            Some(kp) => (**kp).clone(),
            None => {
                tracing::warn!(
                    "pick_distinct_kp: all {} KPs of the peer are consumed — peer must publish fresh KPs",
                    pool.len(),
                );
                return Err(MessengerError::KeyPackageExhausted { peer: peer_hex.to_string() });
            }
        };

        // Persist the consumption so it survives restart.
        {
            let conn = self.db.connection();
            let _ = conn.execute(
                "INSERT OR IGNORE INTO consumed_key_packages (peer_hex, kp_hash, consumed_at)
                 VALUES (?1, ?2, ?3)",
                rusqlite::params![
                    peer_hex,
                    &chosen,
                    chrono::Utc::now().to_rfc3339(),
                ],
            );
        }
        // Also update the in-memory set (backward compat for any code that
        // still reads it).
        if let Ok(mut guard) = self.used_key_packages.lock() {
            guard.entry(peer_hex.to_string()).or_default().insert(chosen.clone());
        }
        Ok(chosen)
    }

    // --- Lifecycle ---

    /// Initialize the messenger core.
    ///
    /// Identity is already generated in the constructor. This method
    /// is provided for future initialization steps.
    pub fn initialize(&self) -> Result<(), MessengerError> {
        // Identity already generated in constructor. Nothing extra to do.
        Ok(())
    }

    /// Shut down the messenger core.
    ///
    /// Stops all running services (sync, notifications) and cleans up.
    pub fn shutdown(&self) -> Result<(), MessengerError> {
        // Clean shutdown would stop all services.
        Ok(())
    }

    // --- Identity ---

    /// Get the current identity information.
    pub fn get_identity_info(&self) -> Result<IdentityInfo, MessengerError> {
        let guard = self.identity.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let identity = guard
            .as_ref()
            .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?;

        let nostr_pubkey = identity.nostr().public_key_hex();
        let npub = identity.npub()
            .map_err(|e| MessengerError::CryptoError { description: format!("npub error: {}", e) })?;
        let mls_credential_hex = identity.mls_identity_string()
            .unwrap_or("<unknown>")
            .to_string();

        Ok(IdentityInfo {
            nostr_pubkey,
            npub,
            mls_credential_hex,
        })
    }

    /// Export the public key package for sharing with peers.
    pub fn export_public_key_package(&self) -> Result<String, MessengerError> {
        let mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let kp = mls.generate_key_packages()
            .map_err(|e| MessengerError::CryptoError { description: format!("failed to generate key package: {}", e) })?;
        let kp_bytes = kp.key_package_bytes()
            .map_err(|e| MessengerError::CryptoError { description: format!("failed to serialize key package: {}", e) })?;
        Ok(base64::Engine::encode(
            &base64::engine::general_purpose::STANDARD,
            kp_bytes,
        ))
    }

    /// Export the **Nostr identity secret key** as a bech32 `nsec1…` string.
    ///
    /// This is the *identity / login* key — it proves ownership of the account
    /// and is what the user imports on a new device to recover their npub.
    /// It is **not** the data-encryption key: history, MLS state and backups are
    /// encrypted with keys derived from the Recovery Password (RoadmapE2EE
    /// so leaking the nsec never exposes message history.
    ///
    /// # Security
    ///
    /// - The returned string is the raw private key in bech32 form. Treat it
    ///   like a password — the caller (UI) is responsible for letting the user
    ///   copy/save it and must never log it.
    /// - The hex form held inside `NostrKeyPair` stays zeroized on drop; here
    ///   we only read it briefly to bech32-encode.
    /// Keep the phrase next to the identity key (same database, same protection).
    /// Stored only if it really is THIS account's phrase.
    pub fn store_mnemonic(&self, words: String) -> Result<(), MessengerError> {
        // Wiped from memory when this call ends.
        let words = zeroize::Zeroizing::new(words);
        let nsec = zeroize::Zeroizing::new(mnemonic_to_nsec(String::clone(&words))?);
        let own = zeroize::Zeroizing::new(self.export_nsec()?);
        if *nsec != *own {
            return Err(MessengerError::InvalidArgument { description: "phrase does not match this account".into() });
        }
        let conn = self.db.connection();
        KeyValueRepo::set(&conn, KV_MNEMONIC, &normalize_words(&words))
            .map_err(|e| MessengerError::DatabaseError { description: format!("mnemonic: {}", e) })
    }

    pub fn stored_mnemonic(&self) -> Option<String> {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, KV_MNEMONIC).ok().flatten().filter(|s| !s.is_empty())
    }

    pub fn export_nsec(&self) -> Result<String, MessengerError> {
        use nostr::nips::nip19::ToBech32;
        let guard = self.identity.lock()
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let identity = guard
            .as_ref()
            .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?;
        let nsec = identity.nostr().keypair().keys().secret_key().to_bech32()
            .map_err(|e| MessengerError::CryptoError { description: format!("nsec encoding: {}", e) })?;
        Ok(nsec)
    }

    /// Import a Nostr identity from a bech32 `nsec1…` string.
    ///
    /// Rebuilds the full `DualIdentity` from the imported Nostr secret key:
    /// the Nostr layer is reconstructed from the supplied nsec, while the MLS
    /// layer is freshly generated (MLS group state is recovered separately via
    /// the backup/restore flow in later phases).
    ///
    /// After import the new identity is persisted to SQLCipher under
    /// [`KV_IDENTITY`] so subsequent launches reuse it. The caller should then
    /// re-initialize the MLS session and rejoin groups.
    ///
    /// Accepts either `nsec1…` bech32 or a 64-char hex secret key for
    /// convenience. Rejects an empty string.
    pub fn import_identity(&self, nsec_or_hex: String) -> Result<(), MessengerError> {
        // Wiped from memory when this call ends.
        let nsec_or_hex = zeroize::Zeroizing::new(nsec_or_hex);
        use nostr::nips::nip19::{FromBech32, ToBech32};
        let trimmed = nsec_or_hex.trim();
        if trimmed.is_empty() {
            return Err(MessengerError::InvalidArgument { description: "empty identity secret".to_string() });
        }

        // Parse the secret key from either nsec bech32 or raw hex.
        let secret_key = if trimmed.starts_with("nsec1") {
            nostr::SecretKey::from_bech32(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid nsec: {}", e) })?
        } else {
            nostr::SecretKey::from_hex(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid hex secret key: {}", e) })?
        };

        // Rebuild the Nostr identity from the imported secret.
        let nostr_keys = nostr::Keys::new(secret_key);
        let nostr_keypair = alpha_identity::NostrKeyPair::from_keys(nostr_keys);
        let nostr_identity = alpha_identity::NostrIdentity::from_keys(nostr_keypair);

        // Fresh MLS layer — group state is restored via the backup flow later.
        let mls_identity = alpha_identity::MlsIdentity::new()
            .map_err(|e| MessengerError::CryptoError { description: format!("mls identity generation: {}", e) })?;

        let new_identity = alpha_identity::DualIdentity::from_parts(nostr_identity, mls_identity);

        // Persist so the next launch reuses this identity (stable npub).
        {
            let serializable = new_identity.to_serializable();
            let json = serde_json::to_string(&serializable)
                .map_err(|e| MessengerError::InternalError { description: format!("identity serialize: {}", e) })?;
            let conn = self.db.connection();
            KeyValueRepo::set(&conn, KV_IDENTITY, &json)
                .map_err(|e| MessengerError::DatabaseError { description: format!("identity save: {}", e) })?;
        }

        // The running MLS session must sign with the key we just persisted:
        // otherwise groups made before the next restart get a leaf whose
        // private key is gone after it, and peers reject every message.
        let mls_signer = new_identity.mls().signer().clone();
        let mls_credential = AlphaCredential::new(
            &alpha_mls::leaf_binding::bind(new_identity.nostr().keypair().keys(), mls_signer.public()),
            &mls_signer,
        )
            .map_err(|e| MessengerError::CryptoError { description: format!("mls credential: {}", e) })?;

        // Swap the active identity in memory.
        {
            let mut guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            *guard = Some(new_identity);
        }
        self.mls_session.lock()
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?
            .set_identity(mls_signer, mls_credential);

        // Sanity: bech32 round-trips so we never silently accept garbage.
        let _npub_check = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                .nostr().keypair().keys().public_key().to_bech32()
                .map_err(|e| MessengerError::CryptoError { description: format!("npub encode: {}", e) })?
        };

        if let Some(hex) = self.identity.lock().ok().and_then(|g| g.as_ref().map(|id| id.nostr().public_key_hex())) {
            alpha_database::devices::set_own_account(&self.db.connection(), &hex);
        }
        tracing::info!("Imported identity; npub={}", _npub_check);
        Ok(())
    }

    // --- Recovery Password ---
    //
    // The Recovery Password is the user's secret that derives the MasterDataKey
    // via Argon2id. We store ONLY the public parameters (salt + KdfParams) and
    // a non-reversible verifier — never the password and never the master key.
    //
    // Sub-keys (DatabaseKey, BackupFileKey, …) are derived on demand from the
    // master key. The master key does NOT re-key the live SQLCipher database
    // (the riskiest op); the backup container is the consumer that needs it.

    /// `true` if the user has already set a Recovery Password on this device.
    ///
    /// Drives the onboarding gate: the user must set a password once before the
    /// flow can complete. Also used at app start to decide whether to ask for
    /// the password (vs. biometric unlock of the cached key).
    pub fn has_recovery_password(&self) -> bool {
        let conn = self.db.connection();
        matches!(
            KeyValueRepo::get(&conn, KV_RECOVERY_SET).ok().flatten().as_deref(),
            Some("1")
        )
    }

    /// Set (or replace) the Recovery Password.
    ///
    /// Generates a fresh random salt, derives the MasterDataKey via Argon2id
    /// with the recommended params, computes a non-reversible verifier, and
    /// persists `{params, salt, verifier}` under `KV_RECOVERY_KDF`. The master
    /// key itself is never persisted — callers that need it must call
    /// [`Self::derive_master_key`] right after.
    ///
    /// CPU/memory intensive (Argon2id, 64 MiB) — call from a worker thread.
    pub fn set_recovery_password(&self, password: String) -> Result<(), MessengerError> {
        // Wiped from memory when this call ends.
        let password = zeroize::Zeroizing::new(password);
        if password.trim().is_empty() {
            return Err(MessengerError::InvalidArgument {
                description: "recovery password must not be empty".to_string(),
            });
        }
        let salt = keys::generate_salt();
        let params = KdfParams::recommended();
        let master = keys::derive_master_key(&password, &salt, &params)
            .map_err(|e| MessengerError::CryptoError { description: format!("argon2id: {}", e) })?;
        let verifier = keys::compute_verifier(&master);

        let record = RecoveryKdfRecord {
            version: keys::ALPHA_KDF_VERSION,
            params,
            salt_b64: base64::Engine::encode(&base64::engine::general_purpose::STANDARD, salt),
            verifier_b64: base64::Engine::encode(&base64::engine::general_purpose::STANDARD, verifier),
        };
        let json = serde_json::to_string(&record)
            .map_err(|e| MessengerError::InternalError { description: format!("recovery kdf serialize: {}", e) })?;

        let conn = self.db.connection();
        KeyValueRepo::set(&conn, KV_RECOVERY_KDF, &json)
            .map_err(|e| MessengerError::DatabaseError { description: format!("recovery kdf save: {}", e) })?;
        KeyValueRepo::set(&conn, KV_RECOVERY_SET, "1")
            .map_err(|e| MessengerError::DatabaseError { description: format!("recovery flag save: {}", e) })?;
        tracing::info!("Recovery password set (argon2id params stored, verifier saved)");
        Ok(())
    }

    /// Verify a candidate password against the stored verifier.
    ///
    /// Returns `true` iff the password reproduces the stored verifier. Crucially
    /// this does NOT unlock anything — it's a yes/no check the UI uses to
    /// confirm the user typed the right password before proceeding (e.g. before
    /// exporting a backup, or as a "current password" gate when changing it).
    ///
    /// Returns `false` (not an error) when no password has been set yet, and
    /// `false` when the password is wrong. Errors only on DB/parse failure.
    pub fn verify_recovery_password(&self, password: String) -> Result<bool, MessengerError> {
        // Wiped from memory when this call ends.
        let password = zeroize::Zeroizing::new(password);
        let record = match self.load_recovery_kdf_record()? {
            Some(r) => r,
            None => return Ok(false),
        };
        let master = self.derive_master_key_internal(&password, &record)?;
        Ok(keys::verify_master_key(&master, &record.verifier_bytes()))
    }

    /// Derive the 32-byte MasterDataKey from a password.
    ///
    /// Returns the key as 32 raw bytes (base64-decoded by Kotlin into a
    /// `ByteArray`). The caller is responsible for caching it in the Android
    /// Keystore under biometrics and for zeroizing it when done.
    ///
    /// Returns an error if no Recovery Password has been set yet, or if the
    /// stored KDF record is corrupt.
    pub fn derive_master_key(&self, password: String) -> Result<Vec<u8>, MessengerError> {
        // Wiped from memory when this call ends.
        let password = zeroize::Zeroizing::new(password);
        let record = self.load_recovery_kdf_record()?.ok_or_else(|| MessengerError::InternalError {
            description: "no recovery password has been set".to_string(),
        })?;
        let master = self.derive_master_key_internal(&password, &record)?;
        Ok(master.as_bytes().to_vec())
    }

    /// Export the PUBLIC Recovery-Password KDF material as JSON.
    ///
    /// Returns the `{version, params, salt_b64, verifier_b64}` record — exactly
    /// the same public data already stored under `KV_RECOVERY_KDF`. Returns an
    /// empty string if no Recovery Password has been set yet.
    ///
    /// # Why this exists
    ///
    /// The DatabaseKey is `HKDF(Argon2id(password, salt), "alpha/v1/db")`. To
    /// open the encrypted DB with that key on a future launch we need the
    /// `salt` + Argon2 `params` BEFORE the DB is open — but today they live
    /// INSIDE the encrypted DB (chicken-and-egg). Kotlin mirrors this public
    /// blob to a plaintext store (SharedPreferences) so the key can be derived
    /// the key pre-open.
    ///
    /// # Security
    ///
    /// This is rebuilt from the typed [`RecoveryKdfRecord`], whose only fields
    /// are the **public** salt, Argon2 params and a non-reversible verifier.
    /// The master key, database key, any derived key and the password itself
    /// are NEVER part of this record and CANNOT leak through here.
    pub fn export_recovery_kdf_public(&self) -> Result<String, MessengerError> {
        let record = match self.load_recovery_kdf_record()? {
            Some(r) => r,
            None => return Ok(String::new()),
        };
        // Re-serialize from the typed record (defense-in-depth: even if the
        // stored JSON were ever polluted with extra fields, only the four
        // public fields of RecoveryKdfRecord are emitted here).
        serde_json::to_string(&record)
            .map_err(|e| MessengerError::InternalError { description: format!("recovery kdf public serialize: {}", e) })
    }

    // --- Recovery helpers (private) ---

    fn load_recovery_kdf_record(&self) -> Result<Option<RecoveryKdfRecord>, MessengerError> {
        let conn = self.db.connection();
        let json = match KeyValueRepo::get(&conn, KV_RECOVERY_KDF)
            .map_err(|e| MessengerError::DatabaseError { description: format!("recovery kdf load: {}", e) })?
        {
            Some(j) => j,
            None => return Ok(None),
        };
        let record: RecoveryKdfRecord = serde_json::from_str(&json)
            .map_err(|e| MessengerError::InternalError { description: format!("recovery kdf parse: {}", e) })?;
        Ok(Some(record))
    }

    fn derive_master_key_internal(
        &self,
        password: &str,
        record: &RecoveryKdfRecord,
    ) -> Result<keys::SecretKey, MessengerError> {
        let salt = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &record.salt_b64)
            .map_err(|e| MessengerError::InternalError { description: format!("salt decode: {}", e) })?;
        let mut salt_arr = [0u8; keys::SALT_LEN];
        if salt.len() != keys::SALT_LEN {
            return Err(MessengerError::InternalError {
                description: format!("salt length {} != {}", salt.len(), keys::SALT_LEN),
            });
        }
        salt_arr.copy_from_slice(&salt);
        keys::derive_master_key(password, &salt_arr, &record.params)
            .map_err(|e| MessengerError::CryptoError { description: format!("argon2id: {}", e) })
    }

    // --- SQLCipher production-key migration orchestrator ---
    //
    // Migrates the live DB from the legacy empty passphrase to the production
    // DatabaseKey = HKDF(MasterDataKey, "alpha/v1/db"). State machine (in DB KV
    // `db_rekey_state_v1`): legacy → in_progress → migrated → confirmed.
    //
    // INVARIANTS:
    //   - `*.prekey-bak` is created BEFORE PRAGMA rekey, after a WAL checkpoint
    //     so it is a consistent copy.
    //   - state := in_progress BEFORE rekey; state := migrated ONLY after the
    //     extended verify (incl. a fresh second connection) passes.
    //   - confirmed ⟹ backup absent AND recovery flag absent (see confirm).
    //
    // The DatabaseKey is unchanged crypto: HKDF over the existing MasterDataKey.
    // No new KDF, no new key format, no change to the Recovery Password flow.

    /// Read the authoritative migration state from DB KV (defaults to `legacy`).
    fn read_rekey_state(&self) -> String {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, KV_DB_REKEY_STATE)
            .ok()
            .flatten()
            .unwrap_or_else(|| REKEY_STATE_LEGACY.to_string())
    }

    /// Current DB-rekey migration state as a string (diagnostics).
    pub fn db_rekey_state(&self) -> String {
        self.read_rekey_state()
    }

    /// True if this open performed a one-shot restore→legacy
    /// fallback. The caller persists the one-shot flag in its pre-open store so a
    /// second failure becomes a hard error instead of an infinite restore loop.
    pub fn db_did_recovery_restore(&self) -> bool {
        self.did_recovery_restore.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// Migrate the live SQLCipher DB to the production DatabaseKey derived from
    /// the supplied 32-byte MasterDataKey.
    ///
    /// Returns `true` if the DB is migrated (or was already). Returns an error if
    /// the migration was attempted but rolled back, OR if the DB is left in a
    /// state requiring an app restart — see below.
    ///
    /// # When to call
    ///
    /// MUST be called right after the DB is opened and BEFORE any service/UI
    /// touches it heavily. On
    /// success the live connection transparently continues under the new key. On
    /// failure the file is restored from the pre-rekey backup, but the live
    /// in-memory connection may be unusable — the caller must surface a "please
    /// restart" and rebuild the core. This method never loses data: the
    /// pre-rekey backup is the guarantee.
    pub fn migrate_db_to_recovery_key(&self, master_key: Vec<u8>) -> Result<bool, MessengerError> {
        if master_key.len() != 32 {
            return Err(MessengerError::InvalidArgument {
                description: format!("master key must be 32 bytes, got {}", master_key.len()),
            });
        }

        // System-level non-reentrancy (C2 requirement): only ONE migration may
        // run at a time, even if two callers race in. The per-connection Mutex
        // alone wouldn't stop two threads both passing the state guard. The RAII
        // guard clears the flag on every return path.
        use std::sync::atomic::Ordering;
        if self
            .migration_in_flight
            .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .is_err()
        {
            return Err(MessengerError::InternalError {
                description: "a DB migration is already in progress".to_string(),
            });
        }
        struct InFlightGuard<'a>(&'a std::sync::atomic::AtomicBool);
        impl Drop for InFlightGuard<'_> {
            fn drop(&mut self) {
                self.0.store(false, std::sync::atomic::Ordering::Release);
            }
        }
        let _in_flight = InFlightGuard(&self.migration_in_flight);

        // Idempotency / guard by state.
        match self.read_rekey_state().as_str() {
            REKEY_STATE_MIGRATED | REKEY_STATE_CONFIRMED => return Ok(true),
            REKEY_STATE_IN_PROGRESS => {
                // A previous attempt was interrupted. Recovery from in_progress is
                // the open-path's job (it needs the key at open time);
                // refuse here rather than guess.
                return Err(MessengerError::InternalError {
                    description: "rekey state is in_progress — needs startup recovery, not a fresh migrate".to_string(),
                });
            }
            _ => {} // legacy / absent → proceed
        }

        // Derive DatabaseKey = HKDF(master, "alpha/v1/db").
        let mut master_arr = [0u8; 32];
        master_arr.copy_from_slice(&master_key);
        let master_secret = keys::SecretKey::from_bytes(master_arr);
        let db_key = keys::derive_subkey(&master_secret, keys::KDF_INFO_DB);
        // Defensive: never proceed with an all-zero key.
        if db_key.as_bytes().iter().all(|&b| b == 0) {
            return Err(MessengerError::CryptoError {
                description: "derived all-zero database key — refusing to rekey".to_string(),
            });
        }
        let db_key_hex = hex::encode(db_key.as_bytes());

        // Hold the SINGLE pool connection lock across the ENTIRE critical section
        // (in_progress → checkpoint → backup → rekey → verify → migrated) so no
        // other in-process writer can interleave. The app has exactly one
        // DatabasePool with one Mutex<Connection>; EVERY writer (MessageSyncService,
        // WorkManager workers, CallbackBridge, ViewModels) goes through it, so
        // holding it here is a hard guarantee of "no parallel writes" during the
        // rekey. This is additionally scheduled before services start.
        let result: std::result::Result<(), MessengerError> = (|| {
            let conn = self.db.connection();

            // 1. in_progress, written under the legacy key (captured by backup).
            KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, REKEY_STATE_IN_PROGRESS)
                .map_err(|e| MessengerError::DatabaseError { description: format!("set in_progress: {}", e) })?;

            // 2. Checkpoint WAL → main file, THEN file backup. Holding the lock
            //    across both keeps the -wal empty between them, so the plain file
            //    copy is a consistent snapshot.
            DatabasePool::checkpoint_conn(&conn)
                .map_err(|e| MessengerError::DatabaseError { description: format!("pre-backup checkpoint: {}", e) })?;
            if let Err(e) = keys::backup_db_file(&self.db_path) {
                // Nothing re-keyed yet — reset state and abort cleanly (DB still "").
                let _ = KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, REKEY_STATE_LEGACY);
                return Err(MessengerError::DatabaseError { description: format!("pre-rekey backup: {}", e) });
            }

            // 3. Re-key on the held connection.
            if let Err(e) = DatabasePool::rekey_conn(&conn, &db_key_hex) {
                drop(conn);
                let _ = keys::restore_db_file(&self.db_path);
                return Err(MessengerError::DatabaseError {
                    description: format!("rekey failed, restored backup — restart required: {}", e),
                });
            }
            // 3b. Flush the re-encrypted pages from the WAL into the MAIN file.
            //     `cipher_integrity_check` reads the main file directly, so without
            //     this it would see stale old-key pages and report bogus problems.
            if let Err(e) = DatabasePool::checkpoint_conn(&conn) {
                drop(conn);
                let _ = keys::restore_db_file(&self.db_path);
                return Err(MessengerError::DatabaseError {
                    description: format!("post-rekey checkpoint failed, restored backup — restart required: {}", e),
                });
            }

            // 4a. Verify pass 1 on the held (live) connection.
            if let Err(e) = DatabasePool::run_integrity_battery(&conn, KV_IDENTITY) {
                drop(conn);
                let _ = keys::restore_db_file(&self.db_path);
                return Err(MessengerError::DatabaseError {
                    description: format!("post-rekey verify (live) failed, restored backup — restart required: {}", e),
                });
            }
            // 4b. Verify pass 2 on a fresh, independent second connection.
            if let Err(e) = DatabasePool::open_and_verify(&self.db_path, &db_key_hex, KV_IDENTITY) {
                drop(conn);
                let _ = keys::restore_db_file(&self.db_path);
                return Err(MessengerError::DatabaseError {
                    description: format!("post-rekey verify (fresh open) failed, restored backup — restart required: {}", e),
                });
            }

            // 5. Commit → migrated (written under the new key).
            KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, REKEY_STATE_MIGRATED)
                .map_err(|e| MessengerError::DatabaseError { description: format!("set migrated: {}", e) })?;
            Ok(())
        })();
        result?;
        // The new key opened the DB on a fresh connection: the old copy (readable
        // without the new key) is not kept a moment longer.
        keys::discard_backup(&self.db_path);
        tracing::info!("C2: DB migrated to production DatabaseKey (state=migrated, backup discarded)");
        Ok(true)
    }

    /// Finalize a completed migration after a proven working session.
    ///
    /// Kotlin calls this only after: app started under the new key + history read
    /// + a message sent + a message received. Order enforces the invariant
    /// `confirmed ⟹ no backup AND no recovery flag`:
    ///   1. delete `*.prekey-bak`
    ///   2. delete the one-shot recovery flag
    ///   3. state := confirmed
    /// A crash before step 3 leaves us in `migrated` (db_key still opens) and the
    /// next confirm re-runs harmlessly.
    pub fn confirm_db_migration(&self) -> Result<(), MessengerError> {
        match self.read_rekey_state().as_str() {
            REKEY_STATE_CONFIRMED => return Ok(()), // already done
            REKEY_STATE_MIGRATED => {}
            other => {
                return Err(MessengerError::InternalError {
                    description: format!("cannot confirm migration from state '{}'", other),
                });
            }
        }
        // 1. Backup gone first (idempotent — file may already be absent).
        keys::discard_backup(&self.db_path);
        // 2. Clear the one-shot recovery flag.
        {
            let conn = self.db.connection();
            let _ = KeyValueRepo::delete(&conn, KV_MIGRATION_RECOVERY_ATTEMPTED);
        }
        // 3. Advance to confirmed.
        {
            let conn = self.db.connection();
            KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, REKEY_STATE_CONFIRMED)
                .map_err(|e| MessengerError::DatabaseError { description: format!("set confirmed: {}", e) })?;
        }
        tracing::info!("C2: migration confirmed (backup discarded, recovery flag cleared)");
        Ok(())
    }

    /// Change the Recovery Password, re-keying the live DB to the new
    /// `DatabaseKey` (C2). Returns the NEW 32-byte MasterDataKey so the caller
    /// can re-cache it for biometric unlock.
    ///
    /// Because the DB is now encrypted under `HKDF(old_password)`, a password
    /// change is a full re-key — NOT just a verifier rewrite (that would brick
    /// the DB on next launch). Steps, under the shared non-reentrancy guard:
    ///   1. verify `old_password` against the stored verifier;
    ///   2. derive a NEW master/db_key from `new_password` + a fresh salt;
    ///   3. checkpoint + file backup;
    ///   4. `PRAGMA rekey` the live connection (current key → new db_key);
    ///   5. extended verify (battery + fresh second connection);
    ///   6. update `KV_RECOVERY_KDF` (new salt/params/verifier) + state=migrated;
    ///   7. on ANY failure: restore the pre-change backup and return an error
    ///      (the old password keeps working after a restart).
    ///
    /// The live connection is already authenticated under the current key (legacy
    /// "" or the old db_key), so `PRAGMA rekey` re-encrypts from whatever it is —
    /// we don't need to recompute the old db_key for the rekey itself.
    pub fn change_recovery_password(
        &self,
        old_password: String,
        new_password: String,
    ) -> Result<Vec<u8>, MessengerError> {
        // Wiped from memory when this call ends.
        let old_password = zeroize::Zeroizing::new(old_password);
        let new_password = zeroize::Zeroizing::new(new_password);
        if new_password.trim().is_empty() {
            return Err(MessengerError::InvalidArgument {
                description: "new recovery password must not be empty".to_string(),
            });
        }

        // 1. Verify the current password.
        let record = self.load_recovery_kdf_record()?.ok_or_else(|| MessengerError::InternalError {
            description: "no recovery password set — cannot change it".to_string(),
        })?;
        let old_master = self.derive_master_key_internal(&old_password, &record)?;
        if !keys::verify_master_key(&old_master, &record.verifier_bytes()) {
            return Err(MessengerError::InvalidArgument {
                description: "current recovery password is incorrect".to_string(),
            });
        }

        // 2. Derive NEW material (fresh salt + recommended params).
        let new_salt = keys::generate_salt();
        let new_params = KdfParams::recommended();
        let new_master = keys::derive_master_key(&new_password, &new_salt, &new_params)
            .map_err(|e| MessengerError::CryptoError { description: format!("argon2id: {}", e) })?;
        let new_verifier = keys::compute_verifier(&new_master);
        let new_db_key = keys::derive_subkey(&new_master, keys::KDF_INFO_DB);
        if new_db_key.as_bytes().iter().all(|&b| b == 0) {
            return Err(MessengerError::CryptoError {
                description: "derived all-zero database key — refusing".to_string(),
            });
        }
        let new_db_key_hex = hex::encode(new_db_key.as_bytes());

        let new_record = RecoveryKdfRecord {
            version: keys::ALPHA_KDF_VERSION,
            params: new_params,
            salt_b64: base64::Engine::encode(&base64::engine::general_purpose::STANDARD, new_salt),
            verifier_b64: base64::Engine::encode(&base64::engine::general_purpose::STANDARD, new_verifier),
        };
        let new_record_json = serde_json::to_string(&new_record)
            .map_err(|e| MessengerError::InternalError { description: format!("kdf serialize: {}", e) })?;

        // Share the migration non-reentrancy guard (no concurrent rekeys).
        use std::sync::atomic::Ordering;
        if self
            .migration_in_flight
            .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .is_err()
        {
            return Err(MessengerError::InternalError {
                description: "a DB migration is already in progress".to_string(),
            });
        }
        struct InFlightGuard<'a>(&'a std::sync::atomic::AtomicBool);
        impl Drop for InFlightGuard<'_> {
            fn drop(&mut self) {
                self.0.store(false, std::sync::atomic::Ordering::Release);
            }
        }
        let _in_flight = InFlightGuard(&self.migration_in_flight);

        // Critical section under the single pool lock (like migrate).
        let result: std::result::Result<(), MessengerError> = (|| {
            let conn = self.db.connection();
            DatabasePool::checkpoint_conn(&conn)
                .map_err(|e| MessengerError::DatabaseError { description: format!("pre-backup checkpoint: {}", e) })?;
            keys::backup_db_file(&self.db_path)
                .map_err(|e| MessengerError::DatabaseError { description: format!("pre-change backup: {}", e) })?;
            if let Err(e) = DatabasePool::rekey_conn(&conn, &new_db_key_hex) {
                drop(conn);
                let _ = keys::restore_db_file(&self.db_path);
                return Err(MessengerError::DatabaseError {
                    description: format!("rekey failed, restored backup — restart required: {}", e),
                });
            }
            // Flush re-encrypted pages into the main file before cipher_integrity_check
            // (it reads the main file directly — stale WAL pages = bogus failures).
            if let Err(e) = DatabasePool::checkpoint_conn(&conn) {
                drop(conn);
                let _ = keys::restore_db_file(&self.db_path);
                return Err(MessengerError::DatabaseError {
                    description: format!("post-rekey checkpoint failed, restored backup — restart required: {}", e),
                });
            }
            if let Err(e) = DatabasePool::run_integrity_battery(&conn, KV_IDENTITY) {
                drop(conn);
                let _ = keys::restore_db_file(&self.db_path);
                return Err(MessengerError::DatabaseError {
                    description: format!("post-rekey verify (live) failed, restored backup — restart required: {}", e),
                });
            }
            if let Err(e) = DatabasePool::open_and_verify(&self.db_path, &new_db_key_hex, KV_IDENTITY) {
                drop(conn);
                let _ = keys::restore_db_file(&self.db_path);
                return Err(MessengerError::DatabaseError {
                    description: format!("post-rekey verify (fresh open) failed, restored backup — restart required: {}", e),
                });
            }
            // Commit the new KDF material + state (now under the new key).
            KeyValueRepo::set(&conn, KV_RECOVERY_KDF, &new_record_json)
                .map_err(|e| MessengerError::DatabaseError { description: format!("kdf save: {}", e) })?;
            let _ = KeyValueRepo::set(&conn, KV_RECOVERY_SET, "1");
            KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, REKEY_STATE_MIGRATED)
                .map_err(|e| MessengerError::DatabaseError { description: format!("set migrated: {}", e) })?;
            let _ = KeyValueRepo::delete(&conn, KV_MIGRATION_RECOVERY_ATTEMPTED);
            Ok(())
        })();
        result?;
        // Verified under the new key: the copy under the OLD password goes now,
        // and so does the daily backup's key — it was derived from the old one.
        keys::discard_backup(&self.db_path);
        {
            let conn = self.db.connection();
            let _ = KeyValueRepo::delete(&conn, KV_BACKUP_AUTO_KEY);
        }
        tracing::info!("C2: recovery password changed + DB re-keyed (state=migrated, backup discarded)");
        Ok(new_master.as_bytes().to_vec())
    }

    // --- Backup container (backup.enc) ---
    //
    // Exports the user-visible data (contacts, chats, messages, outbox,
    // pending queues, key/value profile state) into a self-contained AEAD
    // container sealed with the BackupFileKey derived from the Recovery
    // Password. MLS crypto state (mls_groups, mls_key_packages, mls_storage)
    // and sync checkpoints are deliberately excluded — MLS is restored via
    // the snapshot path, and checkpoints are per-device noise.

    /// Tables excluded from the backup (rebuilt locally, or restored later).
    ///
    /// `mls_groups` / `mls_key_packages` / `mls_storage_v1` / `mls_storage`:
    ///   MLS crypto state — restored via the snapshot flow.
    /// `sync_checkpoints`: per-device relay cursor — meaningless on another device.
    /// `schema_migrations`: re-applied by the migration runner on the new DB.
    // Tables NOT included in the backup container.
    //
    // `mls_storage_v1` (the full OpenMLS MemoryStorage snapshot) is now
    // backed up so groups survive a device migration. `mls_storage_v1` is
    // restored by REPLACE (see `import_backup` clearing it first), never merged.
    //
    // `sync_checkpoints` MUST stay excluded: on a restored device we WANT the
    // catch-up sync to re-run from scratch and pull any commits that advanced
    // the group epoch after the backup was taken (the "recovery sync"). Carrying
    // over the old device's checkpoints would make the new device think it is
    // already up to date and skip that catch-up.
    const BACKUP_EXCLUDED_TABLES: &[&str] = &[
        "sync_checkpoints",
        "schema_migrations",
        // Transient container-only table holding avatar image bytes; it is
        // built manually during export and dropped right after restore, so it
        // must never be picked up by the generic table enumeration.
        "backup_blobs",
    ];

    /// MLS state tables that must be fully REPLACED (not merged) on restore.
    /// A fresh device may have lazily created its own MLS rows before the user
    /// restores; merging the backup into those with INSERT OR IGNORE would mix
    /// two identities' key material and corrupt the group state. We clear these
    /// before replaying the dump so the backup's rows land in an empty table.
    const MLS_REPLACE_TABLES: &[&str] = &[
        "mls_storage_v1",
    ];

    /// Export a full encrypted backup of the user data, sealed with the
    /// BackupFileKey derived from the Recovery Password.
    ///
    /// The plaintext payload is a portable SQLite dump (see
    /// [`Self::dump_user_data_sql`]) containing every user-visible table.
    /// Returns the complete `header || ciphertext` container ready to write
    /// to disk as `mybackup.enc`.
    ///
    /// Requires that a Recovery Password has been set (otherwise there's no
    /// key to encrypt with). CPU-heavy (Argon2id + DB dump) — call off-UI.
    pub fn export_backup(&self, password: String) -> Result<Vec<u8>, MessengerError> {
        // Wiped from memory when this call ends.
        let password = zeroize::Zeroizing::new(password);
        if password.trim().is_empty() {
            return Err(MessengerError::InvalidArgument {
                description: "password must not be empty".to_string(),
            });
        }
        let record = self.load_recovery_kdf_record()?.ok_or_else(|| MessengerError::InternalError {
            description: "no recovery password has been set — cannot export backup".to_string(),
        })?;

        // Flush the live in-memory MLS state to `mls_storage_v1` so the
        // dump captures the freshest group/epoch/keys snapshot (otherwise a
        // mutation that hasn't been persisted yet would be missing from the
        // backup and the restored device would be a stale epoch behind).
        self.persist_mls_storage();

        // 1. Dump user-visible tables into a portable SQL script.
        let sql = {
            let conn = self.db.connection();
            self.dump_user_data_sql(&conn)
                .map_err(|e| MessengerError::DatabaseError { description: format!("backup dump: {}", e) })?
        };

        // 2. Seal the dump with XChaCha20-Poly1305, key = BackupFileKey. The
        // derived key (+ its salt) is remembered in the encrypted database so
        // the daily backup can run without asking for the password.
        let salt = keys::generate_salt();
        let key = keys::derive_backup_key(&password, &salt, &record.params)
            .map_err(|e| MessengerError::CryptoError { description: format!("backup key: {}", e) })?;
        let container = keys::encrypt_backup_with_key(&key, &salt, &record.params, sql.as_bytes())
            .map_err(|e| MessengerError::CryptoError { description: format!("backup encrypt: {}", e) })?;
        {
            let mut blob = salt.to_vec();
            blob.extend_from_slice(&key);
            let conn = self.db.connection();
            let _ = KeyValueRepo::set(&conn, KV_BACKUP_AUTO_KEY, &hex::encode(&blob));
        }

        tracing::info!("export_backup: wrote {} bytes (plaintext {} bytes)", container.len(), sql.len());
        Ok(container)
    }

    /// Decrypt a backup container and validate it, WITHOUT touching the live
    /// database. Returns the schema-version + table list found inside, so the
    /// UI can show "this backup contains N chats / M messages" before the user
    /// commits to a destructive restore.
    ///
    /// `password` must match the one used at export time. Wrong password ⇒
    /// `CryptoError` with "DecryptFailed" (no data mutated).
    pub fn inspect_backup(&self, container: Vec<u8>, password: String) -> Result<String, MessengerError> {
        // Wiped from memory when this call ends.
        let password = zeroize::Zeroizing::new(password);
        if container.len() > MAX_BACKUP_BYTES {
            return Err(MessengerError::InvalidArgument {
                description: format!("backup too large: {} bytes", container.len()),
            });
        }
        let plaintext = keys::decrypt_backup(&password, &container)
            .map_err(|e| MessengerError::CryptoError { description: format!("backup decrypt: {}", e) })?;
        let sql = String::from_utf8_lossy(&plaintext);
        Ok(summarize_dump(&sql))
    }

    /// Decrypt a backup container and apply it to the live database.
    ///
    /// This is the **restore** path: on a fresh device the user
    /// imports `mybackup.enc` + enters their password, and this replays the
    /// SQL dump into the current DB. It is idempotent in the sense that
    /// `INSERT OR IGNORE` is used for table rows, so re-applying won't crash
    /// on duplicates — but the intended use is on an empty DB.
    ///
    /// ⚠️ Wrong password is detected via the AEAD tag and rejected BEFORE any
    /// SQL runs, so the live DB is never left half-overwritten.
    pub fn import_backup(&self, container: Vec<u8>, password: String) -> Result<(), MessengerError> {
        // Wiped from memory when this call ends.
        let password = zeroize::Zeroizing::new(password);
        if container.len() > MAX_BACKUP_BYTES {
            return Err(MessengerError::InvalidArgument {
                description: format!("backup too large: {} bytes", container.len()),
            });
        }
        // 1. Decrypt first — fail fast on wrong password, before touching the DB.
        let plaintext = keys::decrypt_backup(&password, &container)
            .map_err(|e| MessengerError::CryptoError { description: format!("backup decrypt: {}", e) })?;
        let sql = Self::sanitize_backup_sql(&String::from_utf8_lossy(&plaintext));
        drop(plaintext);

        // A restore brings back this account, never puts another one in its
        // place: whoever hands over a "backup" and its password must not swap
        // the keys of a running account for ones they know.
        let account_before = self.identity.lock().ok()
            .and_then(|g| g.as_ref().map(|id| id.nostr().public_key_hex()));

        // 2. Replay the dump inside a single transaction. If anything fails we
        //    roll back so the DB isn't left in a half-restored state.
        let conn = self.db.connection();
        conn.execute_batch("BEGIN;")
            .map_err(|e| MessengerError::DatabaseError { description: format!("begin: {}", e) })?;

        // Clear MLS state tables FIRST so the backup's snapshot rows
        // fully REPLACE any lazily-created rows on this device (the dump uses
        // INSERT OR IGNORE, which would otherwise skip rows whose primary key
        // already exists → mixed/corrupt MLS state). `DELETE FROM` on a missing
        // table would abort the txn, so we guard on table existence.
        let apply_result = Self::replay_backup_sql(&conn, &sql);

        match apply_result {
            Ok(()) => {
                if let Some(before) = account_before.as_deref() {
                    let after = KeyValueRepo::get(&conn, KV_IDENTITY).ok().flatten()
                        .and_then(|j| serde_json::from_str::<alpha_identity::SerializableDualIdentity>(&j).ok())
                        .and_then(|s| DualIdentity::from_serializable(&s).ok())
                        .map(|id| id.nostr().public_key_hex());
                    if after.as_deref().is_some_and(|a| a != before) {
                        let _ = conn.execute_batch("ROLLBACK;");
                        tracing::warn!(target: "ACCOUNT", "import_backup: refused, the backup belongs to another account");
                        return Err(MessengerError::InvalidArgument {
                            description: "backup of another account".to_string(),
                        });
                    }
                }
                conn.execute_batch("COMMIT;")
                    .map_err(|e| MessengerError::DatabaseError { description: format!("commit: {}", e) })?;
                // Release the single-connection MutexGuard BEFORE reloading MLS —
                // `reload_mls_from_storage` re-acquires `self.db.connection()`, and
                // holding this guard would deadlock the same-thread re-lock.
                drop(conn);
                tracing::info!("import_backup: restore applied successfully ({} bytes of SQL)", sql.len());
                // Write embedded avatar image files back to disk (the DB only
                // carries their paths) so a restored device shows real avatars.
                self.restore_avatar_blobs();
                // Swap the in-memory identity to the one just restored from the
                // backup (correct MLS signer), so the running session — and the
                // MLS group reload below — use the signer that matches our leaf.
                self.reload_identity_from_storage();
                // Re-activate MLS groups from the freshly restored
                // snapshot so they're usable immediately (no app restart). The
                // subsequent catch-up sync advances any group whose epoch moved
                // on after the backup was taken.
                self.reload_mls_from_storage();
                Ok(())
            }
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                let why = Self::short_sql_error(&e);
                tracing::warn!(target: "ACCOUNT", "import_backup: restore failed: {why}");
                Err(MessengerError::DatabaseError {
                    description: format!("restore failed (rolled back): {why}"),
                })
            }
        }
    }

    /// Every CREATE made IF NOT EXISTS: older backups carry bare ones, which
    /// collide with the schema the migrations already made here.
    fn sanitize_backup_sql(raw: &str) -> String {
        raw.replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE")
            .replace("CREATE TABLE", "CREATE TABLE IF NOT EXISTS")
            .replace("CREATE INDEX IF NOT EXISTS", "CREATE INDEX")
            .replace("CREATE INDEX", "CREATE INDEX IF NOT EXISTS")
            .replace("CREATE UNIQUE INDEX IF NOT EXISTS", "CREATE UNIQUE INDEX")
            .replace("CREATE UNIQUE INDEX", "CREATE UNIQUE INDEX IF NOT EXISTS")
            // Backups made before the search index was left out of them.
            .replace("CREATE VIRTUAL TABLE IF NOT EXISTS", "CREATE VIRTUAL TABLE")
            .replace("CREATE VIRTUAL TABLE", "CREATE VIRTUAL TABLE IF NOT EXISTS")
    }

    /// Replays a backup dump inside the caller's transaction.
    fn replay_backup_sql(conn: &rusqlite::Connection, sql: &str) -> rusqlite::Result<()> {
        for t in Self::MLS_REPLACE_TABLES {
            let exists: bool = conn
                .query_row(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?1",
                    rusqlite::params![t],
                    |_| Ok(true),
                )
                .unwrap_or(false);
            if exists {
                conn.execute_batch(&format!("DELETE FROM \"{}\";", t))?;
            }
        }
        // The restore happens AFTER onboarding's `import_identity`, which has
        // already written a FRESH `identity_v1` KV row with a brand-new random
        // MLS signer. The dump replays `key_value_store` with INSERT OR IGNORE,
        // so that fresh row would shadow the backup's identity — leaving the
        // device with an MLS signer that does NOT match its own leaf in the
        // restored groups (is_self never matches → false "removed" banner +
        // lost owner/role badge, even though the Nostr key is identical).
        // Fix: if the backup carries an identity row, delete the local one
        // first so the backup's identity (correct MLS signer) wins.
        if sql.contains(KV_IDENTITY) {
            conn.execute(
                "DELETE FROM key_value_store WHERE key=?1",
                rusqlite::params![KV_IDENTITY],
            )?;
        }
        // The file may come from anyone: only what our exporter writes runs.
        alpha_database::backup_sql::apply(conn, sql)?;
        // A restored device MUST NOT inherit the source device's
        // DB-rekey migration state — its own SQLCipher file is fresh and may
        // be under a different key. Strip the ENTIRE migration service
        // namespace (`db_rekey_*` + `migration_*`) so the new device
        // re-computes its own state from scratch (legacy → migrate locally).
        // This sanitises the data on IMPORT; the backup container format is
        // unchanged (export is untouched).
        conn.execute(
            "DELETE FROM key_value_store WHERE key LIKE 'db_rekey_%' OR key LIKE 'migration_%'",
            [],
        )?;
        // Older backups carried the daily backup key; it is not taken over
        // (the next backup by hand makes a fresh one).
        conn.execute("DELETE FROM key_value_store WHERE key=?1", rusqlite::params![KV_BACKUP_AUTO_KEY])?;
        Ok(())
    }

    /// A SQLite error in one short line. For a batch, rusqlite puts all the
    /// rest of it into the message: megabytes of a backup, with its messages.
    fn short_sql_error(e: &rusqlite::Error) -> String {
        match e {
            rusqlite::Error::SqlInputError { msg, sql, .. } => {
                let head = sql.split(" VALUES").next().unwrap_or("");
                let head: String = head.chars().take(100).collect();
                format!("{msg} at: {head}")
            }
            other => other.to_string().chars().take(300).collect(),
        }
    }

    /// Reload the in-memory identity from the persisted `KV_IDENTITY` row.
    /// Used after [`Self::import_backup`] replaces the freshly-onboarded
    /// identity with the backup's one: the backup carries the original MLS
    /// signer that matches our leaf in the restored groups, so without this
    /// swap the running session would keep the onboarding signer and fail
    /// `is_self` checks (false "removed" banner + lost role badge).
    pub(crate) fn reload_identity_from_storage(&self) {
        // Signer hash BEFORE the swap (the onboarding/fresh signer).
        let before_sig = {
            let g = self.identity.lock().ok();
            g.and_then(|g| g.as_ref().map(|id| hex::encode(id.mls().signer().public())))
                .unwrap_or_default()
        };
        let json = {
            let conn = self.db.connection();
            match KeyValueRepo::get(&conn, KV_IDENTITY) {
                Ok(Some(j)) => j,
                _ => {
                    tracing::warn!("reload_identity_from_storage: no identity row after restore");
                    return;
                }
            }
        };
        let serializable: alpha_identity::SerializableDualIdentity = match serde_json::from_str(&json) {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!("reload_identity_from_storage: deserialize failed: {}", e);
                return;
            }
        };
        match DualIdentity::from_serializable(&serializable) {
            Ok(id) => {
                let after_sig = hex::encode(id.mls().signer().public());
                let short = |s: &str| -> String { if s.len() > 12 { format!("{}…{}", &s[..6], &s[s.len()-6..]) } else { s.to_string() } };
                if let Ok(mut guard) = self.identity.lock() {
                    let npub = id.nostr().public_key_hex();
                    *guard = Some(id);
                    tracing::info!(
                        "IDENTITY-SWAP: before_signer={} after_signer={} match={} (npub_hex={})",
                        short(&before_sig), short(&after_sig),
                        before_sig == after_sig, short(&npub),
                    );
                }
            }
            Err(e) => tracing::warn!("reload_identity_from_storage: from_serializable failed: {}", e),
        }
    }

    /// Reload the in-memory MLS session from the persisted `mls_storage_v1`
    /// snapshot and re-activate every known chat's group. Mirrors the restore
    /// path in [`Self::new`]; used after [`Self::import_backup`] so a restored
    /// device can decrypt immediately without a restart.
    pub(crate) fn reload_mls_from_storage(&self) {
        // Read everything from the DB first; lock order is MLS -> DB.
        let (entries, chats) = {
            let conn = self.db.connection();
            let entries = match alpha_database::MlsStorageRepo::load_snapshot(&conn) {
                Ok(e) => e,
                Err(e) => {
                    tracing::warn!("reload_mls_from_storage: load_snapshot failed: {}", e);
                    return;
                }
            };
            (entries, ChatRepo::list(&conn))
        };
        if entries.is_empty() {
            tracing::info!("reload_mls_from_storage: no MLS snapshot in restored backup");
            return;
        }
        // Grab the (already restored) identity's MLS signer BEFORE locking the
        // session, to avoid an identity→session vs session→identity lock-order
        // inversion with the other call sites.
        let restored_signer = {
            let g = self.identity.lock().ok();
            g.and_then(|g| g.as_ref().map(|id| id.mls().signer().clone()))
        };
        let mut session = match self.mls_session.lock() {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!("reload_mls_from_storage: mls lock poisoned: {}", e);
                return;
            }
        };
        session.restore_storage(entries);
        match chats {
            Ok(chats) => {
                for chat in chats {
                    if let Ok(gid) = hex::decode(&chat.id) {
                        match session.load_group(&gid) {
                            Ok(true) => tracing::info!("reload_mls: restored group {}", chat.id),
                            Ok(false) => tracing::warn!(
                                "reload_mls: chat {} has no MLS state in backup", chat.id
                            ),
                            Err(e) => tracing::warn!("reload_mls: load_group {} failed: {}", chat.id, e),
                        }
                    }
                }
            }
            Err(e) => tracing::warn!("reload_mls_from_storage: ChatRepo::list: {}", e),
        }
        // The session was built at startup with the onboarding signer. Now that
        // the identity has been swapped to the backup's (which matches our leaf),
        // point the session at the SAME signer — otherwise every outgoing message
        // is signed with the stale key and rejected by peers as InvalidSignature.
        if let Some(sig) = restored_signer {
            let short = |b: &[u8]| -> String { let h = hex::encode(b); if h.len() > 12 { format!("{}…{}", &h[..6], &h[h.len()-6..]) } else { h } };
            let before = session.signer_public();
            session.set_signer(sig);
            let after = session.signer_public();
            tracing::info!(
                "SESSION-SIGNER: before={} after={} changed={}",
                short(&before), short(&after), before != after,
            );
        }
    }

    /// Dump all user-visible tables into a portable SQL script.
    ///
    /// For each included table we emit `CREATE TABLE IF NOT EXISTS …` followed
    /// by `INSERT OR IGNORE INTO …` rows. Excluded tables (MLS crypto state,
    /// checkpoints, migrations) are skipped entirely. The output is plain SQL
    /// that `import_backup` replays via `execute_batch`.
    fn dump_user_data_sql(&self, conn: &rusqlite::Connection) -> rusqlite::Result<String> {
        let mut out = String::with_capacity(64 * 1024);
        out.push_str("-- AlphaMessenger backup (Phase 2)\n");
        out.push_str("-- Auto-generated; do not edit.\n\n");
        out.push_str("PRAGMA foreign_keys=OFF;\n\n");
        Self::dump_tables_sql(conn, &mut out, false)?;

        // Avatar image FILES: the DB only stores PATHS; the JPEGs live in
        // app-private storage and aren't otherwise in the container, so a
        // restored device shows blank avatars. Embed the bytes as blob rows.
        self.append_avatar_blobs_sql(conn, &mut out)?;

        out.push_str("PRAGMA foreign_keys=ON;\n");
        Ok(out)
    }

    /// What a phone of ours linked just now takes over: the backup's tables
    /// without what belongs to one phone (its MLS state, queues, keys, drafts,
    /// scheduled sends). Chats and contacts replace the rows its Welcomes made.
    pub(super) fn dump_link_history_sql(&self, conn: &rusqlite::Connection) -> rusqlite::Result<String> {
        let mut out = String::with_capacity(256 * 1024);
        out.push_str("-- AlphaMessenger history for a linked phone\n\n");
        Self::dump_tables_sql(conn, &mut out, true)?;
        self.append_avatar_blobs_sql(conn, &mut out)?;
        Ok(out)
    }

    /// Tables a linked phone never takes from another: each has its own.
    const LINK_EXCLUDED_TABLES: &[&str] = &[
        "mls_storage_v1", "mls_groups", "mls_key_packages", "consumed_key_packages",
        "processed_events", "pending_sends", "outbox", "pending_media",
        "media_send_keys", "media_send_objects", "personas", "whispers",
        "invite_keys", "invite_chats",
        "delivery_acks", "delivery_fanout", "delivery_gaps", "delivery_outbox",
        "delivery_pending_seqs", "delivery_seq_counters", "delivery_watermarks",
    ];

    /// Settings of one phone (or its secrets) that another must not take over.
    const LINK_EXCLUDED_KV: &[&str] = &[
        "identity_%", "recovery_%", "cloud_backup_%", "backup_%", "db_rekey_%", "migration_%",
        "media_vault_key", "media_key_root_%", "transport_keyring_%", "presence_device_id",
        "device_id_%", "linked_devices_%", "own_account_%", "link_%", "devsync_in:%",
        "held:%", "draft:%", "join_wanted:%", "join_sent:%", "join_req:%", "group_invite:%",
        "last_published_keypackage_at:%", "keypackages_mirrored_%", "last_commit_author:%",
        "pending_hist:%", "pending_perms:%", "tor_proxy_%",
    ];

    /// Every backed-up table: its schema and its rows. `link`: the history
    /// for a phone of ours (see [`Self::dump_link_history_sql`]).
    fn dump_tables_sql(conn: &rusqlite::Connection, out: &mut String, link: bool) -> rusqlite::Result<()> {
        // Enumerate user tables (sqlite_master), skip excluded + internal.
        let mut stmt = conn.prepare(
            "SELECT name, sql FROM sqlite_master
             WHERE type='table' AND name NOT LIKE 'sqlite_%'
             ORDER BY name",
        )?;
        let tables: Vec<(String, Option<String>)> = stmt.query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?))
        })?
        .filter_map(|r| r.ok())
        .collect();
        drop(stmt);

        // Secret chats stay on this phone: none of their rows, keys or MLS
        // state goes into a backup.
        let secret_ids: Vec<String> = KeyValueRepo::list_prefix(conn, secret_chat::KV_SECRET_CHAT)
            .unwrap_or_default()
            .into_iter()
            .filter_map(|(k, _)| k.strip_prefix(secret_chat::KV_SECRET_CHAT).map(str::to_string))
            .filter(|id| !id.is_empty() && id.bytes().all(|b| b.is_ascii_hexdigit()))
            .collect();
        let secret_markers: Vec<Vec<u8>> = secret_ids.iter()
            .filter_map(|id| hex::decode(id).ok())
            .map(|gid| alpha_mls::group_storage_marker(&gid))
            .collect();
        let secret_list = secret_ids.iter().map(|id| format!("'{id}'")).collect::<Vec<_>>().join(",");

        // The search index is rebuilt from the messages on restore.
        let derived = alpha_database::backup_sql::derived_tables(conn)?;
        for (name, create_sql) in &tables {
            if Self::BACKUP_EXCLUDED_TABLES.contains(&name.as_str())
                || derived.contains(&name.to_ascii_lowercase())
                || (link && Self::LINK_EXCLUDED_TABLES.contains(&name.as_str())) {
                continue;
            }
            // Schema (CREATE TABLE). Force "IF NOT EXISTS": on a restored device
            // the schema is ALREADY created by migrations at startup, so a raw
            // `CREATE TABLE x` collides ("table x already exists") and rolls the
            // whole restore back. With IF NOT EXISTS the existing (empty) tables
            // are kept and the INSERT OR IGNORE rows below populate them.
            if let Some(ddl) = create_sql {
                let ddl = if ddl.trim_start().starts_with("CREATE TABLE IF NOT EXISTS") {
                    ddl.clone()
                } else {
                    ddl.replacen("CREATE TABLE", "CREATE TABLE IF NOT EXISTS", 1)
                };
                out.push_str(&format!("{};\n", ddl));
            }

            // Column names (for the INSERT statement).
            let cols: Vec<String> = conn
                .prepare(&format!("PRAGMA table_info(\"{}\")", name))?
                .query_map([], |row| row.get::<_, String>(1))?
                .filter_map(|r| r.ok())
                .collect();
            if cols.is_empty() {
                out.push('\n');
                continue;
            }
            let col_list = cols.iter().map(|c| format!("\"{}\"", c)).collect::<Vec<_>>().join(", ");

            // Rows. The daily backup key never goes into a backup: one stolen
            // copy would open every other.
            let mut conds: Vec<String> = Vec::new();
            if name == "key_value_store" {
                conds.push(format!("key <> '{}'", KV_BACKUP_AUTO_KEY));
                if link {
                    for p in Self::LINK_EXCLUDED_KV { conds.push(format!("key NOT LIKE '{p}'")); }
                }
            }
            if !secret_ids.is_empty() {
                let has = |c: &str| cols.iter().any(|x| x == c);
                if has("chat_id") { conds.push(format!("chat_id NOT IN ({secret_list})")); }
                match name.as_str() {
                    "chats" => conds.push(format!("id NOT IN ({secret_list})")),
                    "mls_groups" => conds.push(format!("group_id NOT IN ({secret_list})")),
                    "outbox" => conds.push(format!("lower(hex(group_id)) NOT IN ({secret_list})")),
                    "receipts" => conds.push(format!(
                        "message_id NOT IN (SELECT id FROM messages WHERE chat_id IN ({secret_list}))")),
                    "key_value_store" => {
                        conds.push(format!("key NOT LIKE '{}%'", secret_chat::KV_SECRET_PREFIX));
                        for id in &secret_ids { conds.push(format!("instr(key, '{id}') = 0")); }
                    }
                    _ => {}
                }
            }
            let select = if conds.is_empty() {
                format!("SELECT {} FROM \"{}\"", col_list, name)
            } else {
                format!("SELECT {} FROM \"{}\" WHERE {}", col_list, name, conds.join(" AND "))
            };
            let skip_secret_state = name == "mls_storage_v1" && !secret_markers.is_empty();
            let mut sel = conn.prepare(&select)?;
            let col_count = cols.len();
            let mut rows = sel.query([])?;
            let mut row_emitted = false;
            while let Some(row) = rows.next()? {
                if skip_secret_state {
                    let k: Vec<u8> = row.get(0).unwrap_or_default();
                    if secret_markers.iter().any(|m| !m.is_empty() && k.windows(m.len()).any(|w| w == m.as_slice())) {
                        continue;
                    }
                }
                if !row_emitted {
                    let verb = if link && (name == "chats" || name == "contacts") { "REPLACE" } else { "IGNORE" };
                    out.push_str(&format!("INSERT OR {} INTO \"{}\" ({}) VALUES\n", verb, name, col_list));
                    row_emitted = true;
                } else {
                    out.push_str(",\n");
                }
                out.push('(');
                for c in 0..col_count {
                    if c > 0 {
                        out.push_str(", ");
                    }
                    out.push_str(&sql_value(&row, c)?);
                }
                out.push(')');
            }
            if row_emitted {
                out.push_str(";\n\n");
            } else {
                out.push('\n');
            }
        }
        Ok(())
    }

    /// Collect every avatar file referenced by the DB (group avatars in
    /// `chats.avatar_path`, contact avatars in `contacts.avatar_path`, and the
    /// user's own profile avatar in KV) and emit them as `backup_blobs(path,
    /// data)` rows so [`Self::import_backup`] can write the image files back to
    /// disk — not just their paths. Best-effort: unreadable / oversized files
    /// are skipped (avatars are capped well under 512 KB).
    fn append_avatar_blobs_sql(&self, conn: &rusqlite::Connection, out: &mut String) -> rusqlite::Result<()> {
        use std::collections::BTreeSet;
        let mut paths: BTreeSet<String> = BTreeSet::new();
        for (sql, ) in [
            ("SELECT avatar_path FROM chats WHERE avatar_path IS NOT NULL AND avatar_path <> ''",),
            ("SELECT avatar_path FROM contacts WHERE avatar_path IS NOT NULL AND avatar_path <> ''",),
        ] {
            if let Ok(mut s) = conn.prepare(sql) {
                if let Ok(rows) = s.query_map([], |r| r.get::<_, String>(0)) {
                    for p in rows.flatten() { paths.insert(p); }
                }
            }
        }
        if let Some(p) = KeyValueRepo::get(conn, PROFILE_KV_AVATAR).ok().flatten() {
            if !p.is_empty() { paths.insert(p); }
        }
        if paths.is_empty() { return Ok(()); }

        let cap = 512 * 1024;
        let mut emitted = false;
        for p in paths {
            match std::fs::read(&p) {
                Ok(bytes) if !bytes.is_empty() && bytes.len() <= cap => {
                    if !emitted {
                        out.push_str("CREATE TABLE IF NOT EXISTS backup_blobs(path TEXT PRIMARY KEY, data BLOB);\n");
                        emitted = true;
                    }
                    let esc = p.replace('\'', "''");
                    out.push_str(&format!(
                        "INSERT OR REPLACE INTO backup_blobs(path, data) VALUES('{}', x'{}');\n",
                        esc, hex::encode(&bytes),
                    ));
                }
                Ok(bytes) => tracing::warn!("backup: avatar '{}' skipped (len={})", p, bytes.len()),
                Err(e) => tracing::warn!("backup: avatar '{}' unreadable: {}", p, e),
            }
        }
        if emitted { out.push('\n'); }
        Ok(())
    }

    /// Write avatar image files embedded in the backup (see
    /// [`Self::append_avatar_blobs_sql`]) back to their original on-disk paths,
    /// then drop the transient table. Best-effort; never fails the restore.
    /// Where a backup may put avatar bytes: an image file inside this install's
    /// avatar caches or its files directory — never the database, settings or
    /// any path climbing out with `..`.
    fn is_restorable_avatar_path(&self, path: &str) -> bool {
        use std::path::{Component, Path};
        let p = Path::new(path);
        if !p.is_absolute() || p.components().any(|c| matches!(c, Component::ParentDir | Component::CurDir)) {
            return false;
        }
        let image = p.extension().and_then(|e| e.to_str())
            .map(|e| matches!(e.to_ascii_lowercase().as_str(), "jpg" | "jpeg" | "png" | "webp"))
            .unwrap_or(false);
        let files_dir = Path::new(&self.db_path).parent().and_then(|d| d.parent()).map(|root| root.join("files"));
        image && (p.starts_with(&self.contact_avatars_dir)
            || p.starts_with(&self.group_avatars_dir)
            || files_dir.map(|f| p.starts_with(f)).unwrap_or(false))
    }

    fn restore_avatar_blobs(&self) {
        let conn = self.db.connection();
        let exists: bool = conn
            .query_row(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='backup_blobs'",
                [],
                |_| Ok(true),
            )
            .unwrap_or(false);
        if !exists {
            return;
        }
        let rows: Vec<(String, Vec<u8>)> = {
            let mut stmt = match conn.prepare("SELECT path, data FROM backup_blobs") {
                Ok(s) => s,
                Err(_) => return,
            };
            let collected: Vec<(String, Vec<u8>)> = match stmt
                .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, Vec<u8>>(1)?)))
            {
                Ok(it) => it.filter_map(|x| x.ok()).collect(),
                Err(_) => return,
            };
            collected
        };
        let mut ok = 0usize;
        for (path, data) in &rows {
            if !self.is_restorable_avatar_path(path) {
                tracing::warn!("restore_avatar_blobs: path refused");
                continue;
            }
            if let Some(parent) = std::path::Path::new(path).parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            match std::fs::write(path, data) {
                Ok(()) => ok += 1,
                Err(e) => tracing::warn!("restore_avatar_blobs: write '{}' failed: {}", path, e),
            }
        }
        let _ = conn.execute_batch("DROP TABLE IF EXISTS backup_blobs;");
        tracing::info!("restore_avatar_blobs: wrote {}/{} avatar files", ok, rows.len());
    }

    // --- Cloud backup via Blossom ---
    //
    // Produces the same AEAD container as `export_backup`, then
    // uploads it to a Blossom server authenticated by the user's own nsec
    // (kind 24242 auth event). The resulting blob URL is persisted in KV and
    // optionally published as a Nostr replaceable event so other devices can
    // discover it by npub (restore flow).

    /// Upload an encrypted backup to Blossom.
    ///
    /// 1. Build the AEAD container (`export_backup`).
    /// 2. Sign a kind 24242 auth event with the user's own Nostr keys.
    /// 3. PUT the container to Blossom; on success persist `{url, sha, at, size}`.
    /// 4. Optionally publish a replaceable Nostr event (kind 30078) pointing
    ///    at the blob so other devices can find it by npub.
    ///
    /// `publish_nostr_link = true` ⇒ step 4 runs (best-effort; a relay failure
    /// doesn't fail the whole upload since the blob is already safe on Blossom).
    ///
    /// Returns the Blossom blob URL on success. CPU/network-heavy — call off-UI.
    /// True when a scheduled backup can run (a manual backup remembered its key).
    pub fn auto_backup_ready(&self) -> bool {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, KV_BACKUP_AUTO_KEY).ok().flatten().map(|v| !v.is_empty()).unwrap_or(false)
    }

    /// The daily backup: same container and upload as [`Self::upload_backup`],
    /// sealed with the remembered BackupFileKey — no password needed.
    pub fn upload_backup_auto(&self) -> Result<String, MessengerError> {
        let record = self.load_recovery_kdf_record()?.ok_or_else(|| MessengerError::InternalError {
            description: "no recovery password has been set".to_string(),
        })?;
        let blob = {
            let conn = self.db.connection();
            KeyValueRepo::get(&conn, KV_BACKUP_AUTO_KEY).ok().flatten()
        }
        .and_then(|h| hex::decode(h).ok())
        .ok_or_else(|| MessengerError::InvalidArgument { description: "no backup key yet — make one backup by hand first".into() })?;
        if blob.len() != keys::SALT_LEN + 32 {
            return Err(MessengerError::InternalError { description: "bad stored backup key".into() });
        }
        let mut salt = [0u8; keys::SALT_LEN];
        salt.copy_from_slice(&blob[..keys::SALT_LEN]);
        let mut key = [0u8; 32];
        key.copy_from_slice(&blob[keys::SALT_LEN..]);
        self.persist_mls_storage();
        let sql = {
            let conn = self.db.connection();
            self.dump_user_data_sql(&conn)
                .map_err(|e| MessengerError::DatabaseError { description: format!("backup dump: {}", e) })?
        };
        let container = keys::encrypt_backup_with_key(&key, &salt, &record.params, sql.as_bytes())
            .map_err(|e| MessengerError::CryptoError { description: format!("backup encrypt: {}", e) })?;
        key.fill(0);
        self.upload_backup_container(container, true)
    }

    pub fn upload_backup(
        &self,
        password: String,
        publish_nostr_link: bool,
    ) -> Result<String, MessengerError> {
        // 1. Build the encrypted container.
        let container = self.export_backup(password.clone())?;
        self.upload_backup_container(container, publish_nostr_link)
    }

    fn upload_backup_container(&self, container: Vec<u8>, publish_nostr_link: bool) -> Result<String, MessengerError> {

        // 2. Sign with the backup-locator key (derived from the nsec): the
        // storage server can't tie the backup blob to our npub, and the same
        // key can delete it later.
        let signer = alpha_media::MediaSigner::new(self.backup_locator_keys()?.0);

        // 3. Upload to Blossom (PUT /upload with auth event in Authorization header).
        let descriptor = {
            let rt = runtime();
            rt.block_on(self.blossom.upload(&container, &signer, "application/octet-stream"))
                .map_err(|e| MessengerError::NetworkError { description: format!("blossom upload: {}", e) })?
        };

        // 4. Persist the blob reference locally.
        let now = chrono::Utc::now().to_rfc3339();
        {
            let conn = self.db.connection();
            KeyValueRepo::set(&conn, KV_BACKUP_URL, &descriptor.url)
                .map_err(|e| MessengerError::DatabaseError { description: format!("backup url save: {}", e) })?;
            KeyValueRepo::set(&conn, KV_BACKUP_SHA, &descriptor.sha256)
                .map_err(|e| MessengerError::DatabaseError { description: format!("backup sha save: {}", e) })?;
            KeyValueRepo::set(&conn, KV_BACKUP_AT, &now)
                .map_err(|e| MessengerError::DatabaseError { description: format!("backup at save: {}", e) })?;
            KeyValueRepo::set(&conn, KV_BACKUP_SIZE, &descriptor.size.to_string())
                .map_err(|e| MessengerError::DatabaseError { description: format!("backup size save: {}", e) })?;
        }

        tracing::info!(
            "upload_backup: {} bytes → {} (sha256={})",
            descriptor.size, descriptor.url, &descriptor.sha256[..16.min(descriptor.sha256.len())]
        );

        // 5. Optionally publish a Nostr replaceable event so other devices can
        //    discover the backup by npub. Best-effort.
        if publish_nostr_link {
            if let Err(e) = self.publish_backup_link(&descriptor.url) {
                tracing::warn!("upload_backup: Nostr link publish failed (non-fatal): {}", e);
            }
        }

        Ok(descriptor.url)
    }

    /// Keys of the backup locator, derived from our identity secret: a signing
    /// key (unlinkable to the npub) and the key that encrypts the URL.
    fn backup_locator_keys(&self) -> Result<(nostr::Keys, alpha_transport::sealed::Key32), MessengerError> {
        let secret = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                .nostr().keypair().keys().secret_key().to_secret_bytes()
        };
        let (sign, key) = alpha_transport::sealed::backup_locator(&secret);
        let keys = nostr::SecretKey::from_slice(&sign)
            .map(nostr::Keys::new)
            .map_err(|e| MessengerError::CryptoError { description: format!("backup locator key: {}", e) })?;
        Ok((keys, key))
    }

    /// Publish the backup locator: a replaceable kind-30078 event signed by a
    /// key derived from our nsec, with the blob URL encrypted. Relays can't tie
    /// it to our npub; our new device (holding the nsec) can find and open it.
    fn publish_backup_link(&self, blob_url: &str) -> Result<(), MessengerError> {
        let (locator_keys, key) = self.backup_locator_keys()?;
        let content = alpha_transport::sealed::seal(&key, &BACKUP_LOCATOR_AAD, blob_url.as_bytes())
            .ok_or_else(|| MessengerError::CryptoError { description: "backup locator seal failed".to_string() })?;

        let event = nostr::EventBuilder::new(nostr::Kind::Custom(30078), content)
            .tag(nostr::Tag::identifier("alpha-backup"))
            .sign_with_keys(&locator_keys)
            .map_err(|e| MessengerError::CryptoError { description: format!("backup link sign: {}", e) })?;

        let rt = runtime();
        rt.block_on(self.transport.publish(event))
            .map_err(|e| MessengerError::NetworkError { description: format!("backup link publish: {}", e) })?;
        tracing::info!("publish_backup_link: published kind 30078 → {}", blob_url);
        Ok(())
    }

    /// Keys for a new sticker pack that updates itself: `"pub:read"` (hex).
    /// The signing key publishes new versions and never leaves the database;
    /// the pack's public key finds them; the read key opens them. None of it is
    /// tied to our identity.
    pub fn sticker_pack_keys(&self) -> Result<String, MessengerError> {
        use rand::RngCore;
        let keys = nostr::Keys::generate();
        self.store_sticker_sign(&keys)?;
        let mut read = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut read);
        Ok(format!("{}:{}", keys.public_key().to_hex(), hex::encode(read)))
    }

    pub fn sticker_pack_adopt_key(&self, sign_hex: String) -> Result<(), MessengerError> {
        let keys = nostr::Keys::parse(sign_hex.trim())
            .map_err(|e| MessengerError::InvalidArgument { description: format!("pack key: {}", e) })?;
        self.store_sticker_sign(&keys)
    }

    fn store_sticker_sign(&self, keys: &nostr::Keys) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        KeyValueRepo::set(&conn, &format!("{}{}", KV_STICKER_SIGN_PREFIX, keys.public_key().to_hex()), &keys.secret_key().to_secret_hex())
            .map_err(|e| MessengerError::DatabaseError { description: format!("pack key store: {}", e) })
    }

    /// Publish a sticker pack's latest version: the encrypted list's ref, sealed
    /// with the read key, as a replaceable event signed by the pack's own key.
    pub fn sticker_pack_publish(&self, pub_hex: String, read_hex: String, manifest_ref: String) -> Result<(), MessengerError> {
        let sign = {
            let conn = self.db.connection();
            KeyValueRepo::get(&conn, &format!("{}{}", KV_STICKER_SIGN_PREFIX, pub_hex.trim().to_lowercase()))
                .map_err(|e| MessengerError::DatabaseError { description: format!("pack key load: {}", e) })?
                .ok_or_else(|| MessengerError::InvalidArgument { description: "not our sticker pack".to_string() })?
        };
        let keys = nostr::Keys::parse(&sign)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("pack key: {}", e) })?;
        let read = read_key32(&read_hex)?;
        let content = alpha_transport::sealed::seal(&read, &STICKER_PACK_AAD, manifest_ref.as_bytes())
            .ok_or_else(|| MessengerError::CryptoError { description: "pack seal failed".to_string() })?;
        let event = nostr::EventBuilder::new(nostr::Kind::Custom(30078), content)
            .tag(nostr::Tag::identifier("alpha-stickers"))
            .sign_with_keys(&keys)
            .map_err(|e| MessengerError::CryptoError { description: format!("pack sign: {}", e) })?;
        runtime().block_on(self.transport.publish(event))
            .map_err(|e| MessengerError::NetworkError { description: format!("pack publish: {}", e) })?;
        Ok(())
    }

    /// A sticker pack's latest version (the encrypted list's ref), or "" when
    /// none is found.
    pub fn sticker_pack_latest(&self, pub_hex: String, read_hex: String) -> Result<String, MessengerError> {
        let pk = nostr::PublicKey::from_hex(pub_hex.trim())
            .map_err(|e| MessengerError::InvalidArgument { description: format!("pack id: {}", e) })?;
        let read = read_key32(&read_hex)?;
        let filter = nostr::Filter::new()
            .author(pk)
            .kind(nostr::Kind::Custom(30078))
            .identifier("alpha-stickers")
            .limit(5);
        // Not on our own connection: the lookup would tie the pack to our npub.
        let rt = runtime();
        let urls = rt.block_on(self.transport.enabled_relay_urls());
        let events = rt.block_on(alpha_transport::NostrRelayClient::fetch_anonymous(urls, filter, std::time::Duration::from_secs(12)))
            .map_err(|e| MessengerError::NetworkError { description: format!("pack fetch: {}", e) })?;
        // Several relays may hold different versions: the newest that opens wins.
        let mut events: Vec<_> = events.into_iter().collect();
        events.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        Ok(events.iter()
            .find_map(|e| alpha_transport::sealed::unseal(&read, &STICKER_PACK_AAD, &e.content))
            .and_then(|b| String::from_utf8(b).ok())
            .unwrap_or_default())
    }

    /// Public emoji sets from Nostr (kind 30030), for the opt-in import: one set
    /// by its address (`naddr…`, also as `nostr:naddr…` or inside a web link), or
    /// every set of an author (`npub…` / hex key). Read over a separate anonymous
    /// connection (through Tor when it is on), like the feed. Newest version of
    /// each set, as `[{"name","author","emoji":[[code,url],…]}]`; https images only.
    pub fn nostr_emoji_sets(&self, input: String) -> Result<String, MessengerError> {
        use nostr::nips::nip19::{FromBech32, ToBech32};
        const EMOJI_SET: u16 = 30030;
        let bad = || MessengerError::InvalidArgument { description: "not a Nostr emoji set or npub".into() };
        let text = input.trim().to_lowercase();
        // The bech32 word starting with `hrp1`, wherever it sits in the text.
        let word = |hrp: &str| -> Option<String> {
            let i = text.find(&format!("{hrp}1"))?;
            Some(text[i..].chars().take_while(|c| c.is_ascii_alphanumeric()).collect())
        };
        let rt = runtime();
        let mut urls = rt.block_on(self.transport.relay_urls());
        let (filter, author) = if let Some(a) = word("naddr") {
            let c = nostr::nips::nip19::Nip19Coordinate::from_bech32(&a).map_err(|_| bad())?;
            if c.kind.as_u16() != EMOJI_SET { return Err(bad()); }
            // The relays the address names, besides ours (a few at most).
            for r in c.relays.iter().take(3) {
                let u = r.to_string();
                if !urls.contains(&u) { urls.push(u); }
            }
            (nostr::Filter::new().author(c.public_key).kind(nostr::Kind::from(EMOJI_SET)).identifier(c.identifier.clone()).limit(5), c.public_key)
        } else {
            let pk = match word("npub") {
                Some(n) => nostr::PublicKey::from_bech32(&n).ok(),
                None => nostr::PublicKey::from_hex(&text).ok(),
            }.ok_or_else(bad)?;
            (nostr::Filter::new().author(pk).kind(nostr::Kind::from(EMOJI_SET)).limit(50), pk)
        };
        let events = rt.block_on(alpha_transport::NostrRelayClient::fetch_anonymous(urls, filter, std::time::Duration::from_secs(15)))
            .map_err(|e| MessengerError::NetworkError { description: format!("emoji sets: {}", e) })?;
        // One version per set: the newest signed by the author.
        let mut latest: std::collections::HashMap<String, nostr::Event> = std::collections::HashMap::new();
        for e in events.into_iter() {
            if e.pubkey != author || e.kind.as_u16() != EMOJI_SET || e.verify().is_err() { continue; }
            let d = e.tags.identifier().map(|s| s.to_string()).unwrap_or_default();
            if latest.get(&d).map(|old| old.created_at < e.created_at).unwrap_or(true) {
                latest.insert(d, e);
            }
        }
        let npub = author.to_bech32().unwrap_or_default();
        let mut sets: Vec<_> = latest.into_iter().collect();
        sets.sort_by(|a, b| b.1.created_at.cmp(&a.1.created_at));
        let out: Vec<serde_json::Value> = sets.into_iter().filter_map(|(d, e)| {
            let tag = |k: &str| e.tags.iter().find_map(|t| {
                let s = t.as_slice();
                (s.len() >= 2 && s[0] == k && !s[1].trim().is_empty()).then(|| s[1].trim().to_string())
            });
            let name: String = tag("title").or_else(|| tag("name")).unwrap_or(d).chars().take(48).collect();
            let mut seen = std::collections::HashSet::new();
            let emoji: Vec<serde_json::Value> = e.tags.iter().filter_map(|t| {
                let s = t.as_slice();
                if s.len() < 3 || s[0] != "emoji" { return None; }
                let (code, url) = (s[1].trim(), s[2].trim());
                let ok_code = !code.is_empty() && code.len() <= 64
                    && code.chars().all(|c| c.is_alphanumeric() || c == '_' || c == '-');
                (ok_code && url.starts_with("https://") && url.len() <= 1024 && seen.insert(url.to_string()))
                    .then(|| serde_json::json!([code, url]))
            }).take(200).collect();
            (!emoji.is_empty()).then(|| serde_json::json!({ "name": name, "author": npub, "emoji": emoji }))
        }).collect();
        Ok(serde_json::Value::Array(out).to_string())
    }

    /// The Alpha feed: its posts, newest first, as `[{"d","at","c"}]` (post id,
    /// time, content). Only events signed by Alpha's key count — the key is built
    /// into the app — and they are read over a separate anonymous connection, so
    /// no relay learns who reads the feed.
    pub fn alpha_feed_fetch(&self, limit: u32) -> Result<String, MessengerError> {
        let alpha = nostr::PublicKey::from_hex(ALPHA_FEED_PUBKEY)
            .map_err(|e| MessengerError::InternalError { description: format!("feed key: {}", e) })?;
        let filter = nostr::Filter::new()
            .author(alpha)
            .kind(nostr::Kind::Custom(30078))
            .hashtag(ALPHA_FEED_TAG)
            .limit(limit.clamp(1, 200) as usize);
        let rt = runtime();
        let urls = rt.block_on(self.transport.enabled_relay_urls());
        let events = rt.block_on(alpha_transport::NostrRelayClient::fetch_anonymous(urls, filter, std::time::Duration::from_secs(15)))
            .map_err(|e| MessengerError::NetworkError { description: format!("feed fetch: {}", e) })?;
        // One post per id (a re-published post replaces its older version).
        let mut latest: std::collections::HashMap<String, nostr::Event> = std::collections::HashMap::new();
        for e in events.into_iter() {
            if e.pubkey != alpha || e.verify().is_err() { continue; }
            let d = e.tags.identifier().map(|s| s.to_string()).unwrap_or_default();
            if d.is_empty() { continue; }
            if latest.get(&d).map(|old| old.created_at < e.created_at).unwrap_or(true) {
                latest.insert(d, e);
            }
        }
        let mut posts: Vec<_> = latest.into_iter().collect();
        posts.sort_by(|a, b| b.1.created_at.cmp(&a.1.created_at));
        let out: Vec<serde_json::Value> = posts.into_iter()
            .map(|(d, e)| serde_json::json!({ "d": d, "at": e.created_at.as_u64(), "c": e.content }))
            .collect();
        Ok(serde_json::Value::Array(out).to_string())
    }

    /// Is [key] Alpha's feed key (the one whose posts the app shows)?
    pub fn alpha_feed_key_ok(&self, key: String) -> bool {
        nostr::Keys::parse(key.trim()).map(|k| k.public_key().to_hex() == ALPHA_FEED_PUBKEY).unwrap_or(false)
    }

    /// Publish (or replace) post [d] of the Alpha feed. Refused for any key but
    /// Alpha's.
    pub fn alpha_feed_publish(&self, key: String, d: String, content: String) -> Result<(), MessengerError> {
        let keys = nostr::Keys::parse(key.trim())
            .map_err(|e| MessengerError::InvalidArgument { description: format!("feed key: {}", e) })?;
        if keys.public_key().to_hex() != ALPHA_FEED_PUBKEY {
            return Err(MessengerError::InvalidArgument { description: "not the Alpha feed key".to_string() });
        }
        let event = nostr::EventBuilder::new(nostr::Kind::Custom(30078), content)
            .tag(nostr::Tag::identifier(d))
            .tag(nostr::Tag::hashtag(ALPHA_FEED_TAG))
            .sign_with_keys(&keys)
            .map_err(|e| MessengerError::CryptoError { description: format!("feed sign: {}", e) })?;
        runtime().block_on(self.transport.publish(event))
            .map_err(|e| MessengerError::NetworkError { description: format!("feed publish: {}", e) })?;
        Ok(())
    }

    /// A query on a separate short-lived connection with a random key, to our
    /// relays. Lookups that name someone (a contact, our backup locator) never
    /// ride the connection whose subscription carries our npub.
    fn fetch_detached(&self, filter: nostr::Filter, timeout: std::time::Duration) -> Result<Vec<nostr::Event>, String> {
        let rt = runtime();
        let urls = rt.block_on(self.transport.enabled_relay_urls());
        rt.block_on(alpha_transport::NostrRelayClient::fetch_anonymous(urls, filter, timeout))
            .map_err(|e| e.to_string())
    }

    /// Look up a backup blob URL for the given npub by fetching the user's
    /// replaceable kind 30078 event from relays. Returns the URL, or an error
    /// if no such event exists (e.g. the user never enabled cloud backup).
    ///
    /// This is the discovery half of restore: on a new device we know
    /// the npub (from the imported nsec) and use this to find the backup.
    pub fn lookup_backup_url(&self, npub_or_hex: String) -> Result<String, MessengerError> {
        use nostr::nips::nip19::FromBech32;
        // Private locator first (works when the identity is the imported one).
        if let Ok((locator_keys, key)) = self.backup_locator_keys() {
            let filter = nostr::Filter::new()
                .author(locator_keys.public_key())
                .kind(nostr::Kind::Custom(30078))
                .identifier("alpha-backup")
                .limit(1);
            if let Ok(events) = self.fetch_detached(filter, std::time::Duration::from_secs(15)) {
                if let Some(url) = events.into_iter().next()
                    .and_then(|e| alpha_transport::sealed::unseal(&key, &BACKUP_LOCATOR_AAD, &e.content))
                    .and_then(|b| String::from_utf8(b).ok())
                {
                    return Ok(url);
                }
            }
        }
        // Legacy: older versions published the link openly under the npub.
        let trimmed = npub_or_hex.trim();
        let pk = if trimmed.starts_with("npub1") {
            nostr::PublicKey::from_bech32(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid npub: {}", e) })?
        } else {
            nostr::PublicKey::from_hex(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid hex pubkey: {}", e) })?
        };

        // Query for the author's kind 30078 events with d-tag "alpha-backup".
        let filter = nostr::Filter::new()
            .author(pk)
            .kind(nostr::Kind::Custom(30078))
            .identifier("alpha-backup")
            .limit(1);

        let events = self.fetch_detached(filter, std::time::Duration::from_secs(15))
            .map_err(|e| MessengerError::NetworkError { description: format!("backup lookup fetch: {}", e) })?;

        let event = events.into_iter().next().ok_or_else(|| MessengerError::NetworkError {
            description: "no backup link event found for this npub".to_string(),
        })?;

        // Parse the JSON content → extract url.
        let v: serde_json::Value = serde_json::from_str(&event.content)
            .map_err(|e| MessengerError::InternalError { description: format!("backup link parse: {}", e) })?;
        let url = v.get("url").and_then(|t| t.as_str()).ok_or_else(|| MessengerError::InternalError {
            description: "backup link event has no url field".to_string(),
        })?;
        Ok(url.to_string())
    }

    /// Fetch a peer's Nostr kind-0 (metadata) profile by npub, for the
    /// join-requests UI so an owner can see WHO is asking to join before
    /// accepting. Returns a small JSON object `{"name":..,"picture":..}` (either
    /// field may be absent/empty). Returns `"{}"` when no profile is published —
    /// it never blocks the flow.
    pub fn fetch_profile_by_npub(&self, npub_or_hex: String) -> Result<String, MessengerError> {
        use nostr::nips::nip19::FromBech32;
        let npub_or_hex = self.accept_contact_code(&npub_or_hex);
        let trimmed = npub_or_hex.trim();
        let pk = if trimmed.starts_with("npub1") {
            nostr::PublicKey::from_bech32(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid npub: {}", e) })?
        } else {
            nostr::PublicKey::from_hex(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid hex pubkey: {}", e) })?
        };

        let filter = nostr::Filter::new()
            .author(pk)
            .kind(nostr::Kind::Metadata)
            .limit(1);

        let events = self
            .fetch_detached(filter, std::time::Duration::from_secs(10))
            .map_err(|e| MessengerError::NetworkError { description: format!("profile fetch: {}", e) })?;

        // Relays may hold several versions: the newest by this author wins.
        let event = match events.into_iter().filter(|e| e.pubkey == pk).max_by_key(|e| e.created_at) {
            Some(e) => e,
            None => return Ok("{}".to_string()),
        };
        // kind-0 content is JSON like {"name":..,"display_name":..,"picture":..}.
        let v: serde_json::Value = serde_json::from_str(&event.content).unwrap_or(serde_json::json!({}));
        let name = v.get("display_name").and_then(|t| t.as_str())
            .filter(|s| !s.is_empty())
            .or_else(|| v.get("name").and_then(|t| t.as_str()))
            .unwrap_or("");
        let picture = v.get("picture").and_then(|t| t.as_str()).unwrap_or("");
        Ok(serde_json::json!({ "name": name, "picture": picture }).to_string())
    }

    /// Download a backup container from Blossom. `blob_url` is the URL stored
    /// locally (get_backup_info) or discovered via [lookup_backup_url].
    ///
    /// Returns the raw container bytes (`header || ciphertext`), ready to feed
    /// to `import_backup` on a fresh device. Does NOT decrypt — the caller
    /// needs the Recovery Password for that.
    pub fn download_backup(&self, blob_url: String) -> Result<Vec<u8>, MessengerError> {
        let rt = runtime();
        let bytes = rt.block_on(self.blossom.download(&blob_url))
            .map_err(|e| MessengerError::NetworkError { description: format!("blossom download: {}", e) })?;
        tracing::info!("download_backup: {} bytes from {}", bytes.len(), blob_url);
        Ok(bytes)
    }

    /// Information about the last successful cloud backup, for the UI.
    /// Returns a JSON string: `{"url":"","sha":"","at":"","size":0, "has":bool}`.
    /// `has=false` means no cloud backup has been uploaded yet.
    pub fn get_backup_info(&self) -> String {
        let conn = self.db.connection();
        let url = KeyValueRepo::get(&conn, KV_BACKUP_URL).ok().flatten().unwrap_or_default();
        let sha = KeyValueRepo::get(&conn, KV_BACKUP_SHA).ok().flatten().unwrap_or_default();
        let at = KeyValueRepo::get(&conn, KV_BACKUP_AT).ok().flatten().unwrap_or_default();
        let size: u64 = KeyValueRepo::get(&conn, KV_BACKUP_SIZE)
            .ok().flatten()
            .and_then(|s| s.parse().ok())
            .unwrap_or(0);
        let has = !url.is_empty();
        serde_json::json!({
            "has": has,
            "url": url,
            "sha": sha,
            "at": at,
            "size": size,
        }).to_string()
    }

    /// Delete the cloud backup: remove the blob from Blossom (authenticated by
    /// the user's own nsec) and clear the locally-stored backup reference.
    ///
    /// Best-effort on the network side — if the Blossom server rejects/ignores
    /// the delete we STILL clear the local record so the UI reflects "no cloud
    /// backup". The encrypted container is useless without the Recovery Password
    /// anyway. The Nostr discovery link (kind 30078) is left as-is (relays don't
    /// guarantee deletion); a future re-upload simply replaces it.
    pub fn delete_cloud_backup(&self) -> Result<(), MessengerError> {
        let sha = {
            let conn = self.db.connection();
            KeyValueRepo::get(&conn, KV_BACKUP_SHA).ok().flatten().unwrap_or_default()
        };

        // Ask Blossom to delete the blob (best-effort).
        if !sha.is_empty() {
            let signer = alpha_media::MediaSigner::new(self.backup_locator_keys()?.0);
            let rt = runtime();
            match rt.block_on(self.blossom.delete(&sha, &signer)) {
                Ok(()) => tracing::info!("delete_cloud_backup: blob {} deleted from Blossom", &sha[..16.min(sha.len())]),
                Err(e) => tracing::warn!("delete_cloud_backup: Blossom delete failed (clearing local anyway): {}", e),
            }
        }

        // Clear the local reference regardless of the network result.
        {
            let conn = self.db.connection();
            let _ = KeyValueRepo::delete(&conn, KV_BACKUP_URL);
            let _ = KeyValueRepo::delete(&conn, KV_BACKUP_SHA);
            let _ = KeyValueRepo::delete(&conn, KV_BACKUP_AT);
            let _ = KeyValueRepo::delete(&conn, KV_BACKUP_SIZE);
        }
        Ok(())
    }

    // --- Chat operations ---

    /// Add a contact by their Nostr public identity (npub or hex) and open a
    /// 1:1 chat with them.
    ///
    /// End-to-end flow:
    /// 1. Parse `npub_or_hex` as either a NIP-19 npub or a 64-char hex pubkey.
    /// 2. Fetch the peer's published KeyPackage from the Nostr relay pool.
    /// 3. Build an MLS Welcome locally (`mls.create_direct_chat`).
    /// 4. Gift-wrap the Welcome via NIP-59 and publish it as a kind-1059 event
    ///    to the peer's pubkey. The peer's realtime listener will unwrap and
    ///    auto-join the chat.
    /// 5. Persist the chat locally and fire `on_chat_created`.
    ///
    /// Returns the new `ChatInfo`. Errors surface as `MessengerError` variants
    /// so the UI can show a real reason (no keypackage found, relay error, etc).
    /// True if a direct chat with this peer already exists locally, so the UI
    /// can tell the user "already added" instead of silently re-opening it.
    pub fn direct_chat_exists(&self, npub_or_hex: String) -> Result<bool, MessengerError> {
        use nostr::nips::nip19::FromBech32;
        let npub_or_hex = self.accept_contact_code(&npub_or_hex);
        let trimmed = npub_or_hex.trim();
        let peer_pk = if trimmed.starts_with("npub1") {
            nostr::PublicKey::from_bech32(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid npub: {}", e) })?
        } else {
            nostr::PublicKey::from_hex(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid hex pubkey: {}", e) })?
        };
        let kv_key = format!("direct_chat_for:{}", peer_pk.to_hex());
        let conn = self.db.connection();
        match KeyValueRepo::get(&conn, &kv_key)
            .map_err(|e| MessengerError::DatabaseError { description: format!("kv lookup: {}", e) })?
        {
            Some(chat_id) => {
                let chats = ChatRepo::list(&conn)
                    .map_err(|e| MessengerError::DatabaseError { description: format!("list chats: {}", e) })?;
                Ok(chats.iter().any(|c| c.id == chat_id))
            }
            None => Ok(false),
        }
    }

    pub fn add_contact_by_npub(&self, npub_or_hex: String) -> Result<ChatInfo, MessengerError> {
        use nostr::nips::nip19::FromBech32;
        let npub_or_hex = self.accept_contact_code(&npub_or_hex);

        // 1. Parse pubkey from either npub bech32 or raw hex.
        let trimmed = npub_or_hex.trim();
        let peer_pk = if trimmed.starts_with("npub1") {
            nostr::PublicKey::from_bech32(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid npub: {}", e) })?
        } else {
            nostr::PublicKey::from_hex(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid hex pubkey: {}", e) })?
        };

        // Reject adding self as a contact (would create a degenerate group).
        {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if let Some(ref id) = *guard {
                if id.nostr().public_key() == peer_pk {
                    return Err(MessengerError::InvalidArgument {
                        description: "cannot add yourself as a contact".to_string(),
                    });
                }
            }
        }

        // 1a. Idempotency: if a direct chat with this peer already exists
        //     locally, return it instead of creating yet another MLS group.
        //     Without this, every "Add" tap produces a fresh chat row in the
        //     UI and floods the user with duplicates of the same conversation.
        let peer_pk_hex = peer_pk.to_hex();
        let kv_key = format!("direct_chat_for:{}", peer_pk_hex);
        // Lock order: the DB guard is released BEFORE the MLS lock is taken.
        // Holding the DB while waiting for MLS deadlocks against sync, which
        // holds MLS and then asks for the DB — and the whole app freezes.
        let existing = {
            let conn = self.db.connection();
            match KeyValueRepo::get(&conn, &kv_key)
                .map_err(|e| MessengerError::DatabaseError { description: format!("kv lookup: {}", e) })?
            {
                Some(existing_chat_id) => ChatRepo::list(&conn)
                    .map_err(|e| MessengerError::DatabaseError { description: format!("list chats: {}", e) })?
                    .into_iter().find(|c| c.id == existing_chat_id),
                None => None,
            }
        };
        if let Some(existing) = existing {
            // The chat row exists, but is the underlying MLS group still
            // alive? If we reuse the chat_id without a live group, send/receive
            // breaks silently — and worse, we never re-send Welcome to the
            // peer, so they stay empty forever.
            let group_alive = {
                let group_id_bytes = hex::decode(&existing.id).unwrap_or_default();
                let mls = self.mls_session.lock()
                    .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
                !group_id_bytes.is_empty() && mls.group_info(&group_id_bytes).is_some()
            };
            if group_alive {
                tracing::info!("add_contact_by_npub: reusing live chat {} for peer {}", existing.id, peer_pk_hex);
                return Ok(chat_info_basic(
                    existing.id,
                    existing.chat_type.as_str().to_string(),
                    existing.name,
                    existing.epoch as u64,
                    existing.created_at,
                ));
            }
            tracing::warn!(
                "add_contact_by_npub: KV maps to chat {} but MLS group is gone (likely restart) — rebuilding",
                existing.id,
            );
            // Drop the stale chat row so the new one with a new group_id
            // doesn't collide. The KV entry is overwritten below.
            if let Err(e) = ChatRepo::delete(&self.db.connection(), &existing.id) {
                tracing::warn!("Failed to delete stale chat {}: {}", existing.id, e);
            }
        }
        // A mapping that pointed at a chat row that's gone falls through and is
        // recreated; the stale KV entry is overwritten below.

        // 2. Fetch the peer's KeyPackage from relays.
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let rt = runtime();
        // MLS KeyPackages are ONE-TIME: a distinct unused one per phone of the
        // peer, so concurrent invites never reuse a consumed init_key.
        let mut kps = self.member_key_packages(&peer_pk)?;
        let own_others = self.own_other_device_kps();
        kps.extend(own_others.iter().cloned());

        // 3. Build the MLS Welcome locally (one for every phone it brings in).
        let (group_id, _commit_bytes, welcome_bytes) = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.create_direct_chat_with(&kps)
                .map_err(|e| MessengerError::CryptoError { description: format!("create MLS chat: {}", e) })?
        };

        // The peer's address is known for certain — the KeyPackage was just
        // fetched by it. Bind IMMEDIATELY: otherwise the very first message has
        // no one to address — the map used to learn only from incoming messages.
        // The group was just created, so "before" is empty and the peer is the
        // only new member.
        self.bind_new_members(&group_id, &[], &peer_pk_hex);

        // Persist the freshly-built MLS group so a kill before the next
        // mutation doesn't strand us with a half-bootstrapped chat.
        self.persist_mls_storage();

        // 4. Wrap the Welcome in an MlsEnvelope and gift-wrap it to the peer.
        //    The peer's realtime listener will decode the gift-wrap, extract
        //    the Welcome, and auto-join via the catch-up event processor.
        let welcome_envelope = MlsEnvelope::new(
            MlsMessageType::Welcome,
            group_id.clone(),
            0,
            welcome_bytes,
        );

        let own_keys = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                .nostr().keypair().keys().clone()
        };

        // Fire-and-forget: a slow relay should never make the user think
        // adding a contact failed. The local chat is created either way;
        // worst case the peer never receives the Welcome and the chat sits
        // empty on their side until they re-import the npub.
        let welcome_keys = own_keys.clone();
        let welcome_peer = peer_pk;
        let welcome_envelope_copy = welcome_envelope.clone();
        let welcome_bridge = bridge.clone();
        // A single fire-and-forget publish is lost forever if the relay
        // hiccups at that instant. Retry up to 3 times with backoff
        // (in-memory — no outbox table, no schema change).
        rt.spawn(async move {
            for attempt in 1..=3u32 {
                match welcome_bridge.send_mls_message(
                    &welcome_keys, &welcome_envelope_copy, Some(&welcome_peer), None,
                ).await {
                    Ok(event_id) => {
                        tracing::info!("Published Welcome to {}: event_id={} (attempt {})", welcome_peer, event_id, attempt);
                        return;
                    }
                    Err(e) => {
                        tracing::warn!("Welcome publish attempt {} failed for {}: {}", attempt, welcome_peer, e);
                        tokio::time::sleep(std::time::Duration::from_secs(2 * attempt as u64)).await;
                    }
                }
            }
            tracing::error!("Welcome publish failed permanently for {}", welcome_peer);
        });

        // Our other phones open the same Welcome (sealed by us, to us).
        if !own_others.is_empty() {
            let (k, env, b) = (own_keys.clone(), welcome_envelope.clone(), bridge.clone());
            let me = own_keys.public_key();
            rt.spawn(async move {
                if let Err(e) = b.send_mls_message(&k, &env, Some(&me), None).await {
                    tracing::warn!("Welcome to our other phones failed: {}", e);
                }
            });
        }

        // FIX (Welcome delivery): wake the peer over ntfy so a backgrounded
        // (Doze) recipient fetches the Welcome now instead of waiting for the
        // next slow sync window. Without this, the first Welcome frequently
        // "doesn't arrive in time" and the new chat sits empty on their side.
        self.send_peer_wake_now(std::slice::from_ref(&peer_pk_hex));

        // 5. Persist the chat locally so it shows up in the chat list immediately.
        let chat_id = hex::encode(&group_id);
        let epoch = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };

        let conn = self.db.connection();
        let display_name = format!(
            "npub…{}",
            &peer_pk_hex[peer_pk_hex.len().saturating_sub(8)..]
        );
        let chat = new_chat(
            chat_id.clone(),
            alpha_database::models::ChatType::Direct,
            Some(display_name),
            epoch as i64,
        );
        ChatRepo::insert(&conn, &chat)
            .map_err(|e| MessengerError::DatabaseError { description: format!("persist chat: {}", e) })?;

        // Remember the peer → chat mapping so a second Add call returns this
        // same chat instead of spawning a duplicate MLS group.
        KeyValueRepo::set(&conn, &kv_key, &chat_id)
            .map_err(|e| MessengerError::DatabaseError { description: format!("kv save: {}", e) })?;

        // 8.8 v2: cache the peer's *Nostr* pubkey under a stable lookup key
        // (`peer_npub_for_chat:{chat_id}`) so the UI can render the real
        // npub bech32 in the avatar preview without going through the
        // MLS leaf signature key (which is unrelated to nostr addresses).
        let pk_kv_key = format!("peer_npub_for_chat:{}", chat_id);
        KeyValueRepo::set(&conn, &pk_kv_key, &peer_pk_hex)
            .map_err(|e| MessengerError::DatabaseError { description: format!("peer pk save: {}", e) })?;

        self.callback.on_chat_created(&chat_id, "direct");

        tracing::info!("Added contact {} → chat {}", peer_pk, chat_id);

        // 8.8 v2: Kotlin side hooks `on_chat_created` and calls
        // `broadcast_my_profile_now()` from the callback handler — that's
        // where the broadcast happens. Doing it here too would double-send.

        Ok(chat_info_basic(chat_id, "direct".to_string(), chat.name, epoch, chat.created_at))
    }

    /// Create a 1:1 direct chat with a peer.
    pub fn create_direct_chat(&self, peer_key_package_base64: String) -> Result<ChatInfo, MessengerError> {
        let peer_kp_bytes = base64::Engine::decode(
            &base64::engine::general_purpose::STANDARD,
            &peer_key_package_base64,
        ).map_err(|e| MessengerError::InvalidArgument { description: format!("invalid base64 key package: {}", e) })?;

        let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let (group_id, _commit_bytes, _welcome_bytes) = mls
            .create_direct_chat(&peer_kp_bytes)
            .map_err(|e| MessengerError::CryptoError { description: format!("failed to create MLS direct chat: {}", e) })?;

        let chat_id = hex::encode(&group_id);
        let epoch = mls.group_info(&group_id)
            .map(|info| info.epoch)
            .unwrap_or(0);

        // Persist the chat.
        let conn = self.db.connection();
        let chat = new_chat(
            chat_id.clone(),
            alpha_database::models::ChatType::Direct,
            None,
            epoch as i64,
        );
        ChatRepo::insert(&conn, &chat)
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
        drop(conn);
        drop(mls);

        self.callback.on_chat_created(&chat_id, "direct");

        Ok(chat_info_basic(chat_id, "direct".to_string(), None, epoch, chat.created_at))
    }

    /// Join a chat from a Welcome message.
    pub fn join_chat(&self, welcome_base64: String) -> Result<ChatInfo, MessengerError> {
        let welcome_bytes = base64::Engine::decode(
            &base64::engine::general_purpose::STANDARD,
            &welcome_base64,
        ).map_err(|e| MessengerError::InvalidArgument { description: format!("invalid base64 welcome: {}", e) })?;

        let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let group_id = mls
            .join_chat(&welcome_bytes)
            .map_err(|e| MessengerError::CryptoError { description: format!("failed to join: {}", e) })?;

        let chat_id = hex::encode(&group_id);
        let info = mls.group_info(&group_id);
        let epoch = info.as_ref().map(|i| i.epoch).unwrap_or(0);
        let proven = info.map(|i| alpha_mls::leaf_binding::proven_owners(&i.members)).unwrap_or_default();

        let conn = self.db.connection();
        alpha_database::policy::record_proven(&conn, &chat_id, &proven);
        let chat = new_chat(
            chat_id.clone(),
            alpha_database::models::ChatType::Group,
            None,
            epoch as i64,
        );
        ChatRepo::insert(&conn, &chat)
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;

        // MlsGroupRepo lifecycle: record group as active so leave_chat guard works.
        MlsGroupRepo::insert(&conn, &new_mls_group_row(chat_id.clone(), epoch as i64, 0, true))
            .map_err(|e| MessengerError::DatabaseError { description: format!("mls_groups insert: {}", e) })?;
        drop(conn);
        drop(mls);

        self.callback.on_chat_created(&chat_id, "group");

        Ok(chat_info_basic(chat_id, "group".to_string(), chat.name, epoch, chat.created_at))
    }

    /// Live member count for a chat from MLS state (not the chat row).
    ///
    /// Returns 0 if the chat exists in the DB but its MLS group is not
    /// loaded (e.g. join failed). The UI uses this to show the "X
    /// members" subtitle.
    pub fn chat_member_count(&self, chat_id: String) -> Result<u32, MessengerError> {
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("chat_id hex: {}", e) })?;
        Ok(self.people_in(&group_id))
    }

    /// How many people are in a group (a person's phones are one of them).
    fn people_in(&self, group_id: &[u8]) -> u32 {
        let sigs: Vec<String> = self.member_sig_keys(group_id);
        let chat_id = hex::encode(group_id);
        let conn = self.db.connection();
        let owners: std::collections::HashSet<String> = sigs.into_iter().map(|s| {
            alpha_database::policy::sender_pubkey(&conn, &chat_id, &s).unwrap_or(s)
        }).collect();
        owners.len() as u32
    }

    /// Read own profile from local KV.
    // --- Drafts (unsent message text, stored ENCRYPTED in the SQLCipher DB) ---
    // A draft is content the user is typing but hasn't sent. It must never reach
    // MLS / a relay, but it also must not sit in plaintext on disk — so it lives
    // in the encrypted KV store under `draft:<chat_id>`, protected by the same
    // master key as everything else and wiped by panic-wipe.

    pub fn set_draft(&self, chat_id: String, text: String) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        let key = format!("draft:{}", chat_id);
        if text.trim().is_empty() {
            let _ = KeyValueRepo::delete(&conn, &key);
        } else {
            KeyValueRepo::set(&conn, &key, &text)
                .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
        }
        Ok(())
    }

    pub fn get_draft(&self, chat_id: String) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        Ok(KeyValueRepo::get(&conn, &format!("draft:{}", chat_id))
            .ok().flatten().unwrap_or_default())
    }

    pub fn clear_draft(&self, chat_id: String) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        let _ = KeyValueRepo::delete(&conn, &format!("draft:{}", chat_id));
        Ok(())
    }

    /// All drafts as a JSON object `{ "<chat_id>": "<text>", … }` for the
    /// chat-list "Draft" preview.
    pub fn list_drafts(&self) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        let entries = KeyValueRepo::list(&conn)
            .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
        let mut map = serde_json::Map::new();
        for e in entries {
            if let Some(chat_id) = e.key.strip_prefix("draft:") {
                if !e.value.is_empty() {
                    map.insert(chat_id.to_string(), serde_json::Value::String(e.value));
                }
            }
        }
        Ok(serde_json::Value::Object(map).to_string())
    }

    pub fn get_my_profile(&self) -> Result<ProfileInfo, MessengerError> {
        // Identity before the database, never inside it: catch-up holds the
        // identity while it writes to the database, so the other order hangs.
        let npub = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .and_then(|id| id.npub().ok())
                .unwrap_or_default()
        };
        let conn = self.db.connection();
        let display_name = KeyValueRepo::get(&conn, PROFILE_KV_NAME)
            .ok().flatten().unwrap_or_default();
        let bio = KeyValueRepo::get(&conn, PROFILE_KV_BIO)
            .ok().flatten().unwrap_or_default();
        let avatar_path = KeyValueRepo::get(&conn, PROFILE_KV_AVATAR)
            .ok().flatten().unwrap_or_default();
        Ok(ProfileInfo { display_name, bio, avatar_path, npub })
    }

    /// Get a peer's cached profile from the contacts table.
    ///
    /// `id` is the MLS leaf-signature hex (same as `sender_id` on
    /// incoming messages). Returns a `PeerProfile` with empty fields if
    /// the contact row doesn't exist yet (UI should fall back to a
    /// generated gradient + npub-suffix placeholder).
    pub fn get_peer_profile(&self, id: String) -> Result<PeerProfile, MessengerError> {
        let conn = self.db.connection();
        let mut c = alpha_database::ContactRepo::get(&conn, &id)
            .map_err(|e| MessengerError::DatabaseError { description: format!("contact lookup: {}", e) })?;
        // Another phone of a known person: the profile their other leaf sent.
        if c.as_ref().map_or(true, |c| c.display_name.as_deref().map_or(true, |n| n.is_empty() || n.starts_with("peer…"))) {
            if let Some(owner) = KeyValueRepo::get(&conn, &format!("member_npub:{id}")).ok().flatten().filter(|v| !v.is_empty()) {
                let sibling = KeyValueRepo::list_prefix(&conn, "member_npub:").unwrap_or_default().into_iter()
                    .filter(|(k, v)| *v == owner && k.as_str() != format!("member_npub:{id}"))
                    .filter_map(|(k, _)| alpha_database::ContactRepo::get(&conn, &k["member_npub:".len()..]).ok().flatten())
                    .find(|s| s.display_name.as_deref().is_some_and(|n| !n.is_empty() && !n.starts_with("peer…")));
                if sibling.is_some() { c = sibling; }
            }
        }
        Ok(match c {
            Some(c) => PeerProfile {
                id: c.id,
                display_name: c.display_name.unwrap_or_default(),
                bio: c.bio,
                avatar_path: c.avatar_path,
                avatar_hash: c.avatar_hash,
                profile_version: c.profile_version.max(0) as u64,
            },
            None => PeerProfile {
                id,
                display_name: String::new(),
                bio: String::new(),
                avatar_path: String::new(),
                avatar_hash: String::new(),
                profile_version: 0,
            },
        })
    }

    /// Return the bech32 npub of the peer that we associate with this
    /// direct chat. Empty string if not direct, not found, or the chat
    /// was created before we started tracking this mapping.
    ///
    /// **Source of truth**: a KV entry under `peer_npub_for_chat:{chat_id}`
    /// is written by `add_contact_by_npub` (sender side) and by the
    /// Welcome-receive path in `catch_up.rs` (recipient side). Both store
    /// the 64-char Nostr public key hex; this method bech32-encodes it
    /// into the user-facing `npub1...` form before returning.
    pub fn get_peer_npub_for_chat(&self, chat_id: String) -> Result<String, MessengerError> {
        use nostr::nips::nip19::ToBech32;
        let conn = self.db.connection();
        let kv_key = format!("peer_npub_for_chat:{}", chat_id);
        let mut pk_hex = alpha_database::KeyValueRepo::get(&conn, &kv_key)
            .ok().flatten().unwrap_or_default();

        // Backfill: chats created before the `peer_npub_for_chat:` mapping
        // existed don't have it. Recover the peer's pubkey by reverse-
        // scanning `direct_chat_for:{peer_pk_hex}` → chat_id mappings.
        if pk_hex.is_empty() {
            if let Ok(rows) = alpha_database::KeyValueRepo::list(&conn) {
                for row in rows {
                    if let Some(rest) = row.key.strip_prefix("direct_chat_for:") {
                        if row.value == chat_id {
                            pk_hex = rest.to_string();
                            // Save the canonical mapping for future calls.
                            let _ = alpha_database::KeyValueRepo::set(&conn, &kv_key, &pk_hex);
                            break;
                        }
                    }
                }
            }
        }

        if pk_hex.is_empty() {
            return Ok(String::new());
        }
        match nostr::PublicKey::from_hex(&pk_hex) {
            Ok(pk) => Ok(pk.to_bech32().unwrap_or_default()),
            Err(_) => Ok(String::new()),
        }
    }

    /// Nostr pubkey hex of a group member (the presence key), from their MLS
    /// `sender_id`. "" if we haven't decrypted a message from them yet.
    pub fn member_hex(&self, sender_id: String) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        let pk_hex = alpha_database::KeyValueRepo::get(&conn, &format!("member_npub:{}", sender_id))
            .ok().flatten().unwrap_or_default();
        match nostr::PublicKey::from_hex(&pk_hex) {
            Ok(pk) => Ok(pk.to_hex()),
            Err(_) => Ok(String::new()),
        }
    }

    /// Raw 64-char Nostr pubkey hex of a direct chat's peer — the SAME key
    /// presence heartbeats are authored/ingested under (unlike the MLS
    /// leaf-signature `sender_id`). Used to bind chat rows to presence.
    /// Mirrors `get_peer_npub_for_chat`'s lookup + backfill but returns hex.
    pub fn peer_hex_for_chat(&self, chat_id: String) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        let kv_key = format!("peer_npub_for_chat:{}", chat_id);
        let mut pk_hex = alpha_database::KeyValueRepo::get(&conn, &kv_key)
            .ok().flatten().unwrap_or_default();
        if pk_hex.is_empty() {
            if let Ok(rows) = alpha_database::KeyValueRepo::list(&conn) {
                for row in rows {
                    if let Some(rest) = row.key.strip_prefix("direct_chat_for:") {
                        if row.value == chat_id {
                            pk_hex = rest.to_string();
                            let _ = alpha_database::KeyValueRepo::set(&conn, &kv_key, &pk_hex);
                            break;
                        }
                    }
                }
            }
        }
        match nostr::PublicKey::from_hex(&pk_hex) {
            Ok(pk) => Ok(pk.to_hex()),
            Err(_) => Ok(String::new()),
        }
    }

    /// A direct chat's safety number and its state, as JSON
    /// `{number, verified, changed, proven}`. The number covers both npubs, and
    /// every new leaf is signed by its npub, so it covers the keys MLS uses
    /// too. `changed`: marked verified for a number that no longer holds (our
    /// own key was replaced). `proven`: the peer's leaf here is signed by them.
    pub fn verification_info(&self, chat_id: String) -> Result<String, MessengerError> {
        let Some((number, peer_hex)) = self.safety_number(&chat_id)? else {
            return Ok(serde_json::json!({ "number": "" }).to_string());
        };
        let proven = hex::decode(&chat_id).ok()
            .map(|gid| proven_owners_of(&self.mls_session, &gid).iter().any(|(_, owner)| *owner == peer_hex))
            .unwrap_or(false);
        let stored = KeyValueRepo::get(&self.db.connection(), &format!("{KV_VERIFIED_PREFIX}{peer_hex}"))
            .ok().flatten().unwrap_or_default();
        Ok(serde_json::json!({
            "number": number,
            "verified": stored == number,
            "changed": !stored.is_empty() && stored != number,
            "proven": proven,
        }).to_string())
    }

    /// Re-sign our leaf in a direct chat whose leaf was made before keys were bound
    /// to accounts, so the peer can see it is ours. One side at a time, never both
    /// at once (two commits at one epoch fork the chat): the smaller pubkey goes
    /// first, the other once the first one's leaf shows as bound. True: a commit went out.
    pub fn refresh_leaf_binding(&self, chat_id: String) -> Result<bool, MessengerError> {
        let Ok(group_id) = hex::decode(&chat_id) else { return Ok(false) };
        let direct = {
            let conn = self.db.connection();
            ChatRepo::get(&conn, &chat_id).ok().flatten().map(|c| c.chat_type.as_str() == "direct").unwrap_or(false)
        };
        if !direct { return Ok(false); }
        let needs = self.mls_session.lock().map(|m| m.own_leaf_needs_binding(&group_id)).unwrap_or(false);
        if !needs { return Ok(false); }
        let own_hex = self.own_hex_in(&chat_id);
        let peer_hex = self.peer_hex_for_chat(chat_id.clone())?;
        if own_hex.is_empty() || peer_hex.is_empty() { return Ok(false); }
        let peer_bound = proven_owners_of(&self.mls_session, &group_id).iter().any(|(_, o)| *o == peer_hex);
        if !(own_hex < peer_hex || peer_bound) { return Ok(false); }

        // The commit, under the MLS lock alone; persisting and sending after it.
        let (commit_bytes, epoch) = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if !mls.own_leaf_needs_binding(&group_id) { return Ok(false); }
            let c = mls.rebind_own_leaf(&group_id)
                .map_err(|e| MessengerError::CryptoError { description: format!("re-sign leaf: {}", e) })?;
            (c, mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0))
        };
        self.persist_mls_storage();
        let own_keys = self.keys_in(&chat_id)?;
        let envelope = MlsEnvelope::new(MlsMessageType::Commit, group_id.clone(), epoch, commit_bytes);
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let for_log = chat_id.clone();
        runtime().spawn(async move {
            match bridge.send_mls_message(&own_keys, &envelope, None, None).await {
                Ok(eid) => tracing::info!("leaf re-signed in {}: event_id={}", &for_log[..for_log.len().min(8)], eid),
                Err(e) => tracing::warn!("leaf re-sign commit for {} not sent: {}", &for_log[..for_log.len().min(8)], e),
            }
        });
        {
            let conn = self.db.connection();
            ChatRepo::update(&conn, &chat_id, None, Some(epoch as i64))
                .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
        }
        Ok(true)
    }

    /// Mark the peer verified for the current safety number, or clear it.
    pub fn set_contact_verified(&self, chat_id: String, verified: bool) -> Result<(), MessengerError> {
        let Some((number, peer_hex)) = self.safety_number(&chat_id)? else {
            return Err(MessengerError::InvalidArgument { description: "no peer key for this chat".to_string() });
        };
        let conn = self.db.connection();
        let key = format!("{KV_VERIFIED_PREFIX}{peer_hex}");
        let done = if verified { KeyValueRepo::set(&conn, &key, &number) } else { KeyValueRepo::delete(&conn, &key).map(|_| ()) };
        done.map_err(|e| MessengerError::DatabaseError { description: format!("verified mark: {}", e) })
    }

    /// (safety number, peer pubkey hex), or None while the peer is unknown.
    fn safety_number(&self, chat_id: &str) -> Result<Option<(String, String)>, MessengerError> {
        let own = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref().and_then(|i| i.nostr().npub().ok()).unwrap_or_default()
        };
        let peer = self.get_peer_npub_for_chat(chat_id.to_string())?;
        let peer_hex = self.peer_hex_for_chat(chat_id.to_string())?;
        if own.is_empty() || peer.is_empty() || peer_hex.is_empty() {
            return Ok(None);
        }
        Ok(Some((safety_number_of(&own, &peer), peer_hex)))
    }

    /// Resolve a group member's bech32 npub from their MLS leaf-signature
    /// hex id (`sender_id`). Backed by the `member_npub:{sender_id}` KV
    /// written when their group application messages are decrypted.
    /// Returns empty string if we haven't seen a message from them yet.
    pub fn get_member_npub(&self, sender_id: String) -> Result<String, MessengerError> {
        use nostr::nips::nip19::ToBech32;
        let conn = self.db.connection();
        let kv_key = format!("member_npub:{}", sender_id);
        let pk_hex = alpha_database::KeyValueRepo::get(&conn, &kv_key)
            .ok().flatten().unwrap_or_default();
        if pk_hex.is_empty() {
            return Ok(String::new());
        }
        match nostr::PublicKey::from_hex(&pk_hex) {
            Ok(pk) => Ok(pk.to_bech32().unwrap_or_default()),
            Err(_) => Ok(String::new()),
        }
    }

    // --- Member-to-transport-address binding ---
    //
    // The `member_npub:{hex(signature_key)}` map is filled FROM INCOMING
    // messages: a member's address only becomes known after they send
    // something themselves. While application messages went out as a public
    // event this didn't matter — everyone saw them, including those we
    // could not address.
    //
    // With personal envelopes the address is needed BEFORE sending. So the
    // binding is written where the address is certainly valid: right after the
    // KeyPackage is fetched by it and the member added to the group. Learning
    // the map "from the first message" no longer works — a new member who
    // hasn't written yet would be unaddressable, and that is a common case.

    // --- Binding storage: PER-GROUP with an epoch ---
    //
    // The existing `member_npub:{sig}` map is shared across all groups. It
    // is fine as a display representation, but routing must not be built on
    // it: a stale record from one group would reach into another, and a
    // removed member would stay addressable just because their key was once
    // seen somewhere else.
    //
    // So routing has its own record keyed by (group, key) pair, plus the
    // highest accepted epoch of the group stored separately.

    fn dir_key(chat_id: &str, sig_hex: &str) -> String {
        format!("dir:{chat_id}:{sig_hex}")
    }

    fn dir_epoch_key(chat_id: &str) -> String {
        format!("dir_epoch:{chat_id}")
    }

    /// Who is entitled to FIRST name the address for this signing key.
    ///
    /// Binding provenance matters more than the sender's membership. Being in
    /// the group is not authorization: otherwise any member could claim the
    /// empty slot and declare "Bob's key → Mallory's address". Bob would not
    /// decrypt others' messages but would not receive his own either, while the
    /// sender believes delivery succeeded.
    ///
    /// Authorization arises where the address was earned by doing: whoever
    /// added the member fetched the KeyPackage from that address.
    fn dir_auth_key(chat_id: &str, sig_hex: &str) -> String {
        format!("dir_auth:{chat_id}:{sig_hex}")
    }

    /// Who invited us into the group — their directory is trusted on join.
    ///
    /// A newcomer cannot have authorization records: they just arrived and
    /// added no one. The only one whose authorization they can confirm is the
    /// inviter: their signature is on the Seal of the very Welcome the newcomer
    /// joined by. That is the composition-transition proof.
    fn dir_bootstrap_key(chat_id: &str) -> String {
        format!("dir_bootstrap_auth:{chat_id}")
    }

    /// Highest accepted directory epoch for the group.
    fn directory_epoch(&self, chat_id: &str) -> u64 {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, &Self::dir_epoch_key(chat_id))
            .ok()
            .flatten()
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(0)
    }

    /// Current group epoch per MLS — the single source of truth.
    fn mls_epoch(&self, group_id: &[u8]) -> u64 {
        self.mls_session
            .lock()
            .ok()
            .and_then(|mls| mls.group_info(group_id).map(|i| i.epoch))
            .unwrap_or(0)
    }

    /// Accept a directory from a group member.
    ///
    /// Three prohibitions, each closing its own attack:
    ///
    /// * **Rollback.** The revision is the MLS epoch, monotonic by the
    ///   protocol itself. An old but authentic message arriving later via
    ///   another relay cannot revive old addresses or re-add a removed member.
    /// * **Authorization.** The directory cannot change membership: records for
    ///   keys not among current members are dropped. Only MLS knows membership.
    /// * **Spoofing.** An established binding cannot be rewritten. Otherwise a
    ///   member could send "Bob's key → Mallory's address" and steal the chat.
    ///   A mismatch rejects the whole directory — no partial application.
    fn apply_group_directory(
        &self,
        group_id: &[u8],
        sender_sig_hex: &str,
        version: u32,
        revision: u64,
        entries: &[alpha_messages::message_type::GroupDirectoryEntry],
    ) {
        apply_group_directory_raw(
            &self.db,
            &self.mls_session,
            &self.identity,
            group_id,
            sender_sig_hex,
            version,
            revision,
            entries,
        );
    }

    /// Build and send a directory for the group's CURRENT membership.
    ///
    /// The snapshot reflects actual membership, not the epoch by itself: the
    /// epoch also changes on key rotations where membership is the same. It
    /// serves as revision only for freshness and rollback protection.
    fn publish_group_directory(&self, group_id: &[u8]) {
        let chat_id = hex::encode(group_id);
        let members = self.member_sig_keys(group_id);
        if members.len() < 2 {
            return; // single-member group — nothing to fan out, no one to send to
        }
        let epoch = self.mls_epoch(group_id);
        // Our own entry is never learned from anyone: we ARE it. Without it the
        // directory is never complete and never goes out.
        let (own_sig, own_hex) = self.own_keys_in(&chat_id);
        let entries: Vec<alpha_messages::message_type::GroupDirectoryEntry> = {
            let conn = self.db.connection();
            members
                .iter()
                .filter_map(|sig| {
                    let npub = if *sig == own_sig { own_hex.clone() } else {
                        KeyValueRepo::get(&conn, &Self::dir_key(&chat_id, sig))
                            .ok().flatten().unwrap_or_default()
                    };
                    if npub.is_empty() {
                        None
                    } else {
                        Some(alpha_messages::message_type::GroupDirectoryEntry {
                            sig_key: sig.clone(),
                            npub_hex: npub,
                        })
                    }
                })
                .collect()
        };
        // Sending an incomplete directory is pointless: the recipient still
        // cannot address everyone, and the send would falsely look successful.
        if entries.len() != members.len() {
            tracing::warn!(
                "DIR не разослан: известно {} адресов из {} участников",
                entries.len(), members.len(),
            );
            return;
        }
        let msg = AlphaMessage::GroupDirectory {
            version: DIRECTORY_VERSION,
            revision: epoch,
            entries,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        let payload = match alpha_messages::serialize(&msg) { Ok(p) => p, Err(_) => return };
        let ciphertext = {
            let mut mls = match self.mls_session.lock() { Ok(m) => m, Err(_) => return };
            if mls.group_info(group_id).is_none() { return; }
            match mls.send(group_id, &payload) { Ok(c) => c, Err(_) => return }
        };
        self.persist_mls_storage();
        // An ordinary sealed group event, like any message: one event under the
        // epoch's route tag. Never one wrap per member — N `#p` tags sent at
        // once would hand the relay the group's full membership.
        match self.publish_application_message(group_id, epoch, ciphertext) {
            Ok(()) => tracing::info!("DIR разослан: эпоха={epoch} участников={}", members.len()),
            Err(e) => tracing::warn!("DIR разослан не всем: {e}"),
        }
    }

    /// Drop a removed member's binding and authorization.
    ///
    /// Both records are removed together. Keeping the authorization would let
    /// the removed member keep naming others' addresses first; keeping the
    /// binding would keep them a recipient while MLS still lists them.
    fn purge_member_binding(&self, group_id: &[u8], sig_hex: &str) {
        let chat_id = hex::encode(group_id);
        let conn = self.db.connection();
        let _ = KeyValueRepo::delete(&conn, &Self::dir_key(&chat_id, sig_hex));
        let _ = KeyValueRepo::delete(&conn, &Self::dir_auth_key(&chat_id, sig_hex));
        tracing::info!("DIR: привязка и полномочие участника сняты");
    }

    /// Signing keys of all group members, hex-encoded.
    fn member_sig_keys(&self, group_id: &[u8]) -> Vec<String> {
        self.mls_session
            .lock()
            .ok()
            .and_then(|mls| mls.group_info(group_id).map(|i| i.members))
            .unwrap_or_default()
            .iter()
            .map(|m| hex::encode(&m.signature_key))
            .collect()
    }

    /// Bind members who appeared in the group AFTER [`before`] to the address.
    ///
    /// Works by membership diff, not by guessing which leaf the newcomer got:
    /// the before/after comparison is equally correct for direct-chat creation
    /// and for adding to a group.
    ///
    /// Returns no error: the binding alone performs no send.
    fn bind_new_members(&self, group_id: &[u8], before: &[String], npub_hex: &str) {
        if npub_hex.is_empty() {
            return;
        }
        let after = self.member_sig_keys(group_id);
        let chat_id = hex::encode(group_id);
        let epoch = self.mls_epoch(group_id);
        // Read from MLS before the database is taken (lock order: MLS, then DB).
        let own_sig = self.own_sig_in(&chat_id);
        let proven = proven_owners_of(&self.mls_session, group_id);
        let conn = self.db.connection();
        let mut bound = 0usize;
        for sig in after.iter().filter(|s| !before.contains(s)) {
            // The ROUTING record is per-group, stored with the epoch.
            match KeyValueRepo::set(&conn, &Self::dir_key(&chat_id, sig), npub_hex) {
                Ok(_) => bound += 1,
                Err(e) => tracing::warn!("BIND: не удалось записать привязку: {e}"),
            }
            // The shared map is only a display representation for names/avatars.
            let _ = KeyValueRepo::set(&conn, &format!("member_npub:{sig}"), npub_hex);
            // Provenance: WE added this member, so the right to first name
            // their address is ours. Our directory will confirm it for other
            // members, and an arbitrary group member cannot claim the empty
            // slot.
            if !own_sig.is_empty() {
                let _ = KeyValueRepo::set(&conn, &Self::dir_auth_key(&chat_id, sig), &own_sig);
            }
        }
        // The binding comes from a genuine add operation: the address is known
        // for certain — the KeyPackage was just fetched by it. That is the
        // authorization source a network-delivered directory cannot have.
        let _ = KeyValueRepo::set(&conn, &Self::dir_epoch_key(&chat_id), &epoch.to_string());
        alpha_database::policy::record_proven(&conn, &chat_id, &proven);
        // A short key tail keeps log entries distinguishable without exposing
        // a member's full transport address.
        tracing::info!(
            "BIND участников={bound} адрес=…{}",
            &npub_hex[npub_hex.len().saturating_sub(6)..],
        );
    }

    /// List the current members of an MLS group as a JSON array.
    ///
    /// Each entry: `{leaf_index, sender_id, npub, display_name, avatar_path, is_self}`.
    /// `sender_id` = hex(signature_key) — the stable per-leaf id also used by the
    /// `member_npub:<sender_id>` KV mapping (learned from received events). When a
    /// member's Nostr pubkey isn't known yet, `npub`/name are empty and the UI
    /// shows a generic "Member".
    pub fn list_group_members(&self, chat_id: String) -> Result<String, MessengerError> {
        use nostr::nips::nip19::ToBech32;
        let gid = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("bad chat_id: {}", e) })?;

        let snapshot = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.group_info(&gid)
        };
        let snap = match snapshot {
            Some(s) => s,
            None => return Ok("[]".to_string()),
        };

        // Who we are in this chat (a persona in an anonymous channel).
        let (own_sig_hex, own_pk_hex) = (self.own_sig_in(&chat_id), self.own_hex_in(&chat_id));

        let conn = self.db.connection();
        let contacts = alpha_database::ContactRepo::list(&conn).unwrap_or_default();

        let mut arr: Vec<serde_json::Value> = Vec::with_capacity(snap.members.len());
        for m in snap.members {
            let sender_id = hex::encode(&m.signature_key);
            let is_self = !own_sig_hex.is_empty() && sender_id == own_sig_hex;

            // For our OWN leaf, member_npub is never written (we don't receive
            // our own events) — resolve our pubkey directly from identity so
            // role detection (owner/admin) works for the creator.
            let pk_hex = if is_self {
                own_pk_hex.clone()
            } else {
                alpha_database::KeyValueRepo::get(&conn, &format!("member_npub:{}", sender_id))
                    .ok().flatten().unwrap_or_default()
            };
            let mut npub = String::new();
            let mut display_name = String::new();
            let mut avatar_path = String::new();
            // Effective role (owner > admin/moderator > member).
            let mut role = alpha_database::group_roles::ROLE_MEMBER.to_string();
            if !pk_hex.is_empty() {
                if let Ok(pk) = nostr::PublicKey::from_hex(&pk_hex) {
                    npub = pk.to_bech32().unwrap_or_default();
                }
                role = alpha_database::group_roles::role_of(&conn, &chat_id, &pk_hex);
            }
            // Name/avatar: the contact row is keyed by `sender_id` (the MLS leaf
            // signature key hex) — that's how incoming ProfileUpdate stores it
            // (id = sender_id). Looking it up by nostr_pubkey never matched,
            // because that field holds the leaf key, not the real npub. So look
            // up by id == sender_id directly. (Profile Sync fix.)
            if is_self {
                // We never store ourselves in ContactRepo, so pull our own
                // name/avatar from the profile KV — otherwise the owner's row
                // showed a gradient initial while everyone else had avatars.
                display_name = alpha_database::KeyValueRepo::get(&conn, PROFILE_KV_NAME)
                    .ok().flatten().unwrap_or_default();
                avatar_path = alpha_database::KeyValueRepo::get(&conn, PROFILE_KV_AVATAR)
                    .ok().flatten().unwrap_or_default();
            } else if let Some(c) = contacts.iter().find(|c| c.id == sender_id) {
                display_name = c.display_name.clone().unwrap_or_default();
                avatar_path = c.avatar_path.clone();
            }
            // An admin persona we invited: we (the owner) know whose it is.
            if display_name.is_empty() && !pk_hex.is_empty() {
                if let Some(name) = alpha_database::KeyValueRepo::get(&conn, &format!("admin_of:{}:{}", chat_id, pk_hex)).ok().flatten()
                    .and_then(|h| serde_json::from_str::<serde_json::Value>(&h).ok())
                    .and_then(|v| v.get("name").and_then(|x| x.as_str()).map(str::to_string))
                {
                    display_name = name;
                }
            }

            // Mute expiry (0 = not muted) for the muted-state UI.
            let muted_until = if pk_hex.is_empty() { 0i64 }
                else { alpha_database::group_roles::mute_until(&conn, &chat_id, &pk_hex) };
            arr.push(serde_json::json!({
                "leaf_index": m.leaf_index,
                "sender_id": sender_id,
                "npub": npub,
                "display_name": display_name,
                "avatar_path": avatar_path,
                "is_self": is_self || (!pk_hex.is_empty() && pk_hex == own_pk_hex),
                "role": role,
                "muted_until": muted_until,
            }));
        }
        // One row per person: their other phones are leaves of theirs too
        // (ours: the one of this phone).
        let mut keep: std::collections::HashMap<String, usize> = std::collections::HashMap::new();
        for (i, v) in arr.iter().enumerate() {
            let n = v["npub"].as_str().unwrap_or_default();
            if n.is_empty() { continue; }
            let this_phone = v["sender_id"].as_str() == Some(own_sig_hex.as_str());
            if this_phone || !keep.contains_key(n) { keep.insert(n.to_string(), i); }
        }
        let arr: Vec<serde_json::Value> = arr.into_iter().enumerate()
            .filter(|(i, v)| {
                let n = v["npub"].as_str().unwrap_or_default();
                n.is_empty() || keep.get(n) == Some(i)
            })
            .map(|(_, v)| v)
            .collect();
        Ok(serde_json::Value::Array(arr).to_string())
    }

    /// True if we were removed/banned from this group (persistent flag set on
    /// self-removal, cleared on a genuine re-add). Drives the read-only ban
    /// notice so it ALWAYS shows — independent of transient MLS state.
    pub fn is_removed_from_group(&self, chat_id: String) -> Result<bool, MessengerError> {
        // Lock order is MLS -> DB: read MLS first, with no DB guard held.

        // --- FULL DIAGNOSTIC DUMP (membership/owner after restore) ---
        let (mls_loaded, mls_active) = match hex::decode(&chat_id) {
            Ok(gid) => match self.mls_session.lock() {
                Ok(mls) => match mls.group_info(&gid) {
                    Some(i) => (true, i.is_active),
                    None => (false, false),
                },
                Err(_) => (false, false),
            },
            Err(_) => (false, false),
        };
        let own_hex = self.own_hex_in(&chat_id);
        let conn = self.db.connection();
        let flag = alpha_database::KeyValueRepo::get(&conn, &format!("removed_group:{}", chat_id))
            .ok().flatten().as_deref() == Some("1");
        let owner_kv = alpha_database::group_roles::owner_of(&conn, &chat_id).unwrap_or_default();
        let my_role = alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex);
        let short = |s: &str| -> String { if s.len() > 12 { format!("{}…{}", &s[..6], &s[s.len()-6..]) } else { s.to_string() } };
        tracing::info!(
            "DUMP chat={} flag={} mls_loaded={} mls_active={} my_pubkey={} group_owner={} owner_match={} my_role={}",
            chat_id, flag, mls_loaded, mls_active,
            short(&own_hex), short(&owner_kv),
            (!own_hex.is_empty() && own_hex == owner_kv), my_role,
        );

        if !flag {
            return Ok(false);
        }
        let is_owner = !own_hex.is_empty() && owner_kv == own_hex;

        // SELF-HEAL (Membership Hardening): if MLS cryptographically confirms we
        // ARE an active member, OR we're the recorded owner, the "removed" flag
        // is stale (e.g. a false self-removal that got baked into a backup). The
        // owner can't be banned from their own group. Clear it — this is NOT a
        // blind reset: it only fires when membership is provably real.
        if mls_active || is_owner {
            tracing::info!(
                "is_removed_from_group chat={}: STALE flag cleared (mls_active={} owner={})",
                chat_id, mls_active, is_owner,
            );
            let _ = alpha_database::KeyValueRepo::delete(&conn, &format!("removed_group:{}", chat_id));
            return Ok(false);
        }
        tracing::info!("is_removed_from_group chat={} flag=true mls_active=false owner=false → removed", chat_id);
        Ok(true)
    }

    /// True when we can no longer post to this GROUP: the MLS group state is gone
    /// (we left / deleted / were removed / it was disbanded) or the local
    /// `MlsGroupRow` is marked inactive. Lightweight — uses `group_epoch`, never
    /// `members()`. Drives the read-only "Group unavailable" composer strip.
    pub fn is_group_unavailable(&self, chat_id: String) -> bool {
        let gid = match hex::decode(&chat_id) { Ok(g) => g, Err(_) => return false };
        if gid.is_empty() { return false; }
        // Cheap DB check first (no MLS lock): explicit inactive flag.
        {
            let conn = self.db.connection();
            if let Some(r) = alpha_database::MlsGroupRepo::get(&conn, &chat_id).ok().flatten() {
                if !r.is_active { return true; }
            }
        }
        // MLS existence via NON-BLOCKING try_lock — must never stall the UI on a
        // contended MLS lock (that froze the whole composer-state computation,
        // breaking mute/removed/restricted strips too).
        match self.mls_session.try_lock() {
            Ok(mls) => mls.group_epoch(&gid).is_none(),
            Err(_) => false,
        }
    }

    /// Bulk fetch of all peer profiles known to the local contacts table.
    /// Used by chat list / chat top bar to render avatars + names without
    /// N+1 FFI roundtrips.
    pub fn list_peer_profiles(&self) -> Result<Vec<PeerProfile>, MessengerError> {
        let conn = self.db.connection();
        let rows = alpha_database::ContactRepo::list(&conn)
            .map_err(|e| MessengerError::DatabaseError { description: format!("list contacts: {}", e) })?;
        Ok(rows.into_iter().map(|c| PeerProfile {
            id: c.id,
            display_name: c.display_name.unwrap_or_default(),
            bio: c.bio,
            avatar_path: c.avatar_path,
            avatar_hash: c.avatar_hash,
            profile_version: c.profile_version.max(0) as u64,
        }).collect())
    }

    /// Update one or more fields of the own profile. Empty string clears.
    /// Returns the new full ProfileInfo so the UI doesn't have to do a
    /// follow-up read.
    pub fn set_my_profile(
        &self,
        display_name: Option<String>,
        bio: Option<String>,
        avatar_path: Option<String>,
    ) -> Result<ProfileInfo, MessengerError> {
        let t0 = std::time::Instant::now();
        tracing::info!(
            "set_my_profile: name={:?} bio_len={:?} avatar={:?}",
            display_name.as_deref().map(|s| s.chars().take(32).collect::<String>()),
            bio.as_ref().map(|s| s.len()),
            avatar_path.as_deref(),
        );
        // Hold the DB lock for the writes only, then DROP it before reading
        // back via get_my_profile(). The previous version kept the
        // MutexGuard alive while calling get_my_profile() — which in turn
        // tries to take self.db.connection() again. std::sync::Mutex is
        // NOT reentrant, so the same thread deadlocked itself. The user's
        // symptom: "kruťits and nichego ne proishodit" was exactly this.
        let (read_name, read_bio, read_avatar) = {
            let conn = self.db.connection();
            if let Some(n) = display_name {
                KeyValueRepo::set(&conn, PROFILE_KV_NAME, &n)
                    .map_err(|e| MessengerError::DatabaseError { description: format!("save name: {}", e) })?;
            }
            if let Some(b) = bio {
                KeyValueRepo::set(&conn, PROFILE_KV_BIO, &b)
                    .map_err(|e| MessengerError::DatabaseError { description: format!("save bio: {}", e) })?;
            }
            if let Some(p) = avatar_path {
                KeyValueRepo::set(&conn, PROFILE_KV_AVATAR, &p)
                    .map_err(|e| MessengerError::DatabaseError { description: format!("save avatar: {}", e) })?;
            }
            // Read back the canonical values WHILE we still hold the lock,
            // so we don't need a second connection() call.
            let dn = KeyValueRepo::get(&conn, PROFILE_KV_NAME).ok().flatten().unwrap_or_default();
            let bi = KeyValueRepo::get(&conn, PROFILE_KV_BIO).ok().flatten().unwrap_or_default();
            let av = KeyValueRepo::get(&conn, PROFILE_KV_AVATAR).ok().flatten().unwrap_or_default();
            (dn, bi, av)
        }; // ← MutexGuard drops here

        // Now safely call identity (different lock) — no DB lock held.
        let npub = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref().and_then(|id| id.npub().ok()).unwrap_or_default()
        };

        tracing::info!("set_my_profile: done in {}ms (name='{}', avatar='{}')",
            t0.elapsed().as_millis(), read_name, read_avatar);

        // Fire-and-forget broadcast to every chat we're part of.
        // This is what makes the peer see our updated avatar/name.
        // Runs in the background so the FFI return is still fast.
        let bcast_name = read_name.clone();
        let bcast_bio = read_bio.clone();
        let bcast_avatar = read_avatar.clone();
        // We can't move `self` into a thread — instead, do the broadcast
        // synchronously but with a cap on chat count. This is short for a
        // 1:1 user but could grow with many groups; future iteration can
        // background it via a dedicated thread.
        if let Err(e) = self.broadcast_my_profile(&bcast_name, &bcast_bio, &bcast_avatar) {
            tracing::warn!("set_my_profile: broadcast failed (non-fatal): {}", e);
        }

        Ok(ProfileInfo {
            display_name: read_name,
            bio: read_bio,
            avatar_path: read_avatar,
            npub,
        })
    }

    /// Public FFI handle so Kotlin can trigger a broadcast after an
    /// `onChatCreated` callback (the realtime / catch-up spawned tasks
    /// don't have `&self` access). Safe to call repeatedly; each call
    /// bumps the version and ships to all current chats.
    pub fn broadcast_my_profile_now(&self) -> Result<(), MessengerError> {
        let p = self.get_my_profile()?;
        self.broadcast_my_profile(&p.display_name, &p.bio, &p.avatar_path)
    }

    /// Set (or clear with "") my cover/banner file path, then re-broadcast the
    /// profile so peers receive the cover inline. Cover is a local file; only
    /// the hash+bytes travel in `ProfileUpdate`.
    pub fn set_my_cover(&self, cover_path: String) -> Result<(), MessengerError> {
        {
            let conn = self.db.connection();
            KeyValueRepo::set(&conn, PROFILE_KV_COVER, &cover_path)
                .map_err(|e| MessengerError::DatabaseError { description: format!("save cover: {}", e) })?;
        }
        let p = self.get_my_profile()?;
        self.broadcast_my_profile(&p.display_name, &p.bio, &p.avatar_path)
    }

    /// A peer's cover/banner local path ("" = none). Keyed by their MLS
    /// `sender_id` (same id used for `get_peer_profile`).
    pub fn get_peer_cover_path(&self, sender_id: String) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        Ok(KeyValueRepo::get(&conn, &format!("peer_cover_path:{}", sender_id))
            .ok().flatten().unwrap_or_default())
    }

    /// Update a chat's metadata locally + broadcast `GroupMetadataUpdate`
    /// to other members. Pass `None` to leave a field untouched, or empty
    /// string to clear it.
    ///
    /// - `name`: chat title shown in list/top bar.
    /// - `description`: optional topic/description.
    /// - `avatar_path`: absolute path on disk to the new avatar JPEG.
    ///   File is read here, base64-encoded and shipped through MLS.
    ///
    /// Saved Messages is rejected explicitly (no peer to broadcast to).
    pub fn set_group_metadata(
        &self,
        chat_id: String,
        name: Option<String>,
        description: Option<String>,
        avatar_path: Option<String>,
    ) -> Result<(), MessengerError> {
        use base64::Engine;
        use sha2::Digest;

        if chat_id == SAVED_MESSAGES_CHAT_ID {
            return Err(MessengerError::InvalidArgument {
                description: "cannot set metadata on Saved Messages".to_string(),
            });
        }

        // Only OWNER/ADMIN may edit group metadata (enforced when the
        // group has a recorded owner; legacy groups stay permissive).
        {
            let own_hex = self.own_hex_in(&chat_id);
            let conn = self.db.connection();
            if alpha_database::group_roles::owner_of(&conn, &chat_id).is_some() {
                let role = alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex);
                // Honor the "who can edit" permission — admins always, plus
                // everyone when the policy is "all".
                let (_, _, who_can_edit) = alpha_database::group_roles::permissions(&conn, &chat_id);
                if !alpha_messages::can_manage_group(&role) && who_can_edit != "all" {
                    return Err(MessengerError::InvalidArgument {
                        description: "у вас нет прав менять данные группы".to_string(),
                    });
                }
            }
        }

        // --- Read existing row + bump version atomically ---
        let (new_version, applied_name, applied_desc, applied_avatar_path, applied_hash) = {
            let conn = self.db.connection();
            let existing = ChatRepo::get(&conn, &chat_id)
                .map_err(|e| MessengerError::DatabaseError { description: format!("chat lookup: {}", e) })?
                .ok_or_else(|| MessengerError::InvalidArgument {
                    description: format!("chat {} not found", chat_id),
                })?;

            let new_ver = existing.metadata_version + 1;
            let new_name = name.unwrap_or(existing.name.clone().unwrap_or_default());
            let new_desc = description.unwrap_or(existing.description.clone());

            let (new_path, new_hash) = match avatar_path {
                Some(p) if p.is_empty() => (String::new(), String::new()),
                Some(p) => {
                    match std::fs::read(&p) {
                        Ok(bytes) if bytes.len() <= 200 * 1024 => {
                            let hash = format!("{:x}", sha2::Sha256::digest(&bytes));
                            // Our own copy, named by content — a new photo is a new
                            // path everywhere (chat list, info, edit), never a stale one.
                            let stored = alpha_sync::event_processor::store_group_avatar(
                                &self.group_avatars_dir, &chat_id, &hash, &bytes,
                            ).unwrap_or(p);
                            (stored, hash)
                        }
                        Ok(bytes) => {
                            tracing::warn!("set_group_metadata: avatar too large ({}>200KB), skipped", bytes.len());
                            (existing.avatar_path.clone(), existing.avatar_hash.clone())
                        }
                        Err(e) => {
                            tracing::warn!("set_group_metadata: read avatar '{}' failed: {}", p, e);
                            (existing.avatar_path.clone(), existing.avatar_hash.clone())
                        }
                    }
                }
                None => (existing.avatar_path.clone(), existing.avatar_hash.clone()),
            };

            // Persist locally.
            let _ = ChatRepo::apply_metadata(
                &conn,
                &chat_id,
                Some(&new_name),
                Some(&new_desc),
                Some(&new_path),
                Some(&new_hash),
                new_ver,
            ).map_err(|e| MessengerError::DatabaseError { description: format!("apply metadata: {}", e) })?;

            (new_ver, new_name, new_desc, new_path, new_hash)
        };

        // --- Build and broadcast through MLS into the chat ---
        let avatar_b64 = if applied_avatar_path.is_empty() {
            String::new()
        } else {
            match std::fs::read(&applied_avatar_path) {
                Ok(bytes) => base64::engine::general_purpose::STANDARD.encode(&bytes),
                Err(_) => String::new(),
            }
        };

        let (look, notices) = { let conn = self.db.connection(); (chat_look_raw(&conn, &chat_id), notices_raw(&conn, &chat_id)) };
        let msg = AlphaMessage::GroupMetadataUpdate {
            version: new_version as u64,
            name: applied_name,
            description: applied_desc,
            avatar_jpeg_base64: avatar_b64,
            avatar_hash: applied_hash,
            look,
            notices,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;

        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;

        let ciphertext = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if mls.group_info(&group_id).is_none() {
                tracing::warn!("set_group_metadata: MLS group not loaded for {}; local change only", chat_id);
                return Ok(());
            }
            mls.send(&group_id, &payload)
                .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?
        };
        let epoch = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };
        self.publish_application_message(&group_id, epoch, ciphertext)?;
        self.persist_mls_storage();
        tracing::info!("set_group_metadata: shipped v{} for chat {}", new_version, chat_id);
        Ok(())
    }

    /// Whether the group shows join/leave lines in the chat (default on).
    pub fn group_notices(&self, chat_id: String) -> bool {
        let conn = self.db.connection();
        notices_raw(&conn, &chat_id) != "off"
    }

    /// Show or hide join/leave lines for every member; owner and admins only.
    pub fn set_group_notices(&self, chat_id: String, on: bool) -> Result<(), MessengerError> {
        {
            let own_hex = self.own_hex_in(&chat_id);
            let conn = self.db.connection();
            let role = alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex);
            if !alpha_messages::can_manage_group(&role) {
                return Err(MessengerError::InvalidArgument { description: "only admins change this".to_string() });
            }
            KeyValueRepo::set(&conn, &format!("group_notices:{}", chat_id), if on { "on" } else { "off" })
                .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
        }
        self.set_group_metadata(chat_id, None, None, None)
    }

    /// The look this chat's admins set (JSON), or "" for none.
    pub fn chat_look(&self, chat_id: String) -> String {
        let conn = self.db.connection();
        let raw = chat_look_raw(&conn, &chat_id);
        if raw == "{}" { String::new() } else { raw }
    }

    /// Set (or clear, with "") this group's / channel's look for every member:
    /// stored here and shipped with the metadata (a new version), under the
    /// same rights as the name and avatar.
    pub fn set_chat_look(&self, chat_id: String, look: String) -> Result<(), MessengerError> {
        let look = if look.trim().is_empty() { "{}".to_string() } else { look };
        if look.len() > 8192 || !serde_json::from_str::<serde_json::Value>(&look).map(|v| v.is_object()).unwrap_or(false) {
            return Err(MessengerError::InvalidArgument { description: "bad chat look".to_string() });
        }
        {
            let own_hex = self.own_hex_in(&chat_id);
            let conn = self.db.connection();
            if alpha_database::group_roles::owner_of(&conn, &chat_id).is_some() {
                let role = alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex);
                if !alpha_messages::can_manage_group(&role) {
                    return Err(MessengerError::InvalidArgument { description: "only admins set the chat's look".to_string() });
                }
            }
            KeyValueRepo::set(&conn, &format!("chat_look:{}", chat_id), &look)
                .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
        }
        self.set_group_metadata(chat_id, None, None, None)
    }

    /// Re-broadcast the CURRENT group metadata (name/description/avatar) WITHOUT
    /// bumping the version. Used right after adding a member so a late joiner
    /// receives the existing avatar/description (the Welcome only carries the
    /// name). Existing members drop it (version not newer); the new member
    /// (version 0) accepts it. Best-effort, silent on failure.
    fn broadcast_current_group_metadata(&self, chat_id: &str) {
        use base64::Engine;
        let (version, name, desc, avatar_path, avatar_hash) = {
            let conn = self.db.connection();
            match ChatRepo::get(&conn, chat_id).ok().flatten() {
                Some(c) => (c.metadata_version, c.name.unwrap_or_default(), c.description,
                            c.avatar_path, c.avatar_hash),
                None => return,
            }
        };
        // Nothing meaningful to share yet.
        if version <= 0 && avatar_path.is_empty() && desc.is_empty() { return; }
        let avatar_b64 = if avatar_path.is_empty() { String::new() } else {
            match std::fs::read(&avatar_path) {
                Ok(bytes) => base64::engine::general_purpose::STANDARD.encode(&bytes),
                Err(_) => String::new(),
            }
        };
        let (look, notices) = { let conn = self.db.connection(); (chat_look_raw(&conn, chat_id), notices_raw(&conn, chat_id)) };
        let msg = AlphaMessage::GroupMetadataUpdate {
            version: version.max(1) as u64,
            name, description: desc,
            avatar_jpeg_base64: avatar_b64, avatar_hash, look, notices,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        let payload = match alpha_messages::serialize(&msg) { Ok(p) => p, Err(_) => return };
        let group_id = match hex::decode(chat_id) { Ok(g) => g, Err(_) => return };
        let ciphertext = {
            let mut mls = match self.mls_session.lock() { Ok(m) => m, Err(_) => return };
            if mls.group_info(&group_id).is_none() { return; }
            match mls.send(&group_id, &payload) { Ok(c) => c, Err(_) => return }
        };
        let epoch = {
            let mls = match self.mls_session.lock() { Ok(m) => m, Err(_) => return };
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };
        let _ = self.publish_application_message(&group_id, epoch, ciphertext);
        self.persist_mls_storage();
        tracing::info!("broadcast_current_group_metadata: shipped v{} for {}", version, chat_id);
    }

    /// Broadcast a `GroupDisbanded` control message: the owner deleted the group,
    /// so every member should tear down their local group, show the "Group
    /// deleted by owner" notice and lock the composer. Best-effort. MUST run
    /// BEFORE we tear down our own MLS state (we need it to encrypt).
    fn send_group_disbanded(&self, chat_id: &str) {
        let own_hex = self.own_hex_in(chat_id);
        if own_hex.is_empty() { return; }
        let group_id = match hex::decode(chat_id) { Ok(g) => g, Err(_) => return };
        let msg = AlphaMessage::GroupDisbanded {
            actor_pubkey: own_hex,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        let payload = match alpha_messages::serialize(&msg) { Ok(p) => p, Err(_) => return };
        let (ciphertext, epoch) = {
            let mut mls = match self.mls_session.lock() { Ok(m) => m, Err(_) => return };
            if mls.group_info(&group_id).is_none() { return; }
            let ct = match mls.send(&group_id, &payload) { Ok(c) => c, Err(_) => return };
            let ep = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
            (ct, ep)
        };
        let _ = self.publish_application_message(&group_id, epoch, ciphertext);
        self.persist_mls_storage();
        tracing::info!("send_group_disbanded: shipped for chat {}", chat_id);
    }

    /// Build an `AlphaMessage::ProfileUpdate` carrying our current profile
    /// (name + bio + avatar bytes) and ship it through MLS into every chat
    /// we participate in.
    ///
    /// - Reads `PROFILE_KV_VERSION`, bumps it, persists the new value.
    /// - Loads avatar JPEG from disk if the path is set and the file
    ///   exists. Cap at 200KB — anything bigger is skipped (a future
    ///   iteration will resize on the Kotlin side before save).
    /// - Computes SHA256 over the JPEG bytes for receiver-side dedup.
    /// - Enumerates `ChatRepo::list` for direct + group chats and sends
    ///   the same ProfileUpdate to each. Saved Messages is skipped
    ///   (notes-to-self chat with no peer).
    /// - All publishes are fire-and-forget at the relay layer.
    /// Upload a profile image to the blob pipeline IF its file content changed,
    /// then store the small MediaRef + hash + bumped version in KV. `ProfileUpdate`
    /// ships only these refs (never the JPEG). Empty path clears the refs.
    fn sync_profile_image(&self, path: &str, kv_ref: &str, kv_mhash: &str, kv_src: &str, kv_ver: &str) {
        self.sync_profile_blob(path, kv_ref, kv_mhash, kv_src, kv_ver, "profile.jpg", alpha_media::MediaType::Image)
    }

    /// Upload a profile file (picture or video avatar) when it changed and
    /// keep its blob ref; an empty path clears it.
    #[allow(clippy::too_many_arguments)]
    fn sync_profile_blob(
        &self, path: &str, kv_ref: &str, kv_mhash: &str, kv_src: &str, kv_ver: &str,
        name: &str, kind: alpha_media::MediaType,
    ) {
        let conn0 = self.db.connection();
        let bump_ver = |conn: &_| {
            let cur: u64 = KeyValueRepo::get(conn, kv_ver).ok().flatten()
                .and_then(|s| s.parse().ok()).unwrap_or(0);
            let _ = KeyValueRepo::set(conn, kv_ver, &(cur + 1).to_string());
        };

        if path.is_empty() {
            // Cleared → drop refs + bump version so peers remove the image.
            let stored = KeyValueRepo::get(&conn0, kv_src).ok().flatten().unwrap_or_default();
            if !stored.is_empty() {
                let _ = KeyValueRepo::set(&conn0, kv_ref, "");
                let _ = KeyValueRepo::set(&conn0, kv_mhash, "");
                let _ = KeyValueRepo::set(&conn0, kv_src, "");
                bump_ver(&conn0);
            }
            return;
        }

        let src = file_sha256(path);
        if src.is_empty() { return; }
        let stored = KeyValueRepo::get(&conn0, kv_src).ok().flatten().unwrap_or_default();
        if stored == src { return; } // unchanged → reuse existing ref
        drop(conn0);

        let bytes = match std::fs::read(path) { Ok(b) => b, Err(_) => return };
        let signer = self.media_signer();
        let up = match runtime().block_on(self.media_pipeline.upload(
            &bytes, name, kind, &signer,
        )) {
            Ok(u) => u,
            Err(e) => { tracing::warn!("profile image upload failed: {}", e); return; }
        };
        let mref = alpha_media::MediaRef::from_upload_result(&up);
        let ref_json = serde_json::to_string(&mref).unwrap_or_default();
        let mhash = mref.hash.clone();
        let conn = self.db.connection();
        let _ = KeyValueRepo::set(&conn, kv_ref, &ref_json);
        let _ = KeyValueRepo::set(&conn, kv_mhash, &mhash);
        let _ = KeyValueRepo::set(&conn, kv_src, &src);
        bump_ver(&conn);
        tracing::info!("profile image uploaded via blob: ref_len={} mhash={}", ref_json.len(), &mhash[..mhash.len().min(16)]);
    }

    fn broadcast_my_profile(
        &self,
        display_name: &str,
        bio: &str,
        avatar_path: &str,
    ) -> Result<(), MessengerError> {
        let t0 = std::time::Instant::now();
        tracing::info!(
            "broadcast_my_profile ENTRY: name='{}' bio_len={} avatar='{}'",
            display_name, bio.len(), avatar_path,
        );

        // --- Bump version ---
        let new_version: i64 = {
            let conn = self.db.connection();
            let cur: i64 = KeyValueRepo::get(&conn, PROFILE_KV_VERSION)
                .ok().flatten()
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
            let next = cur + 1;
            if let Err(e) = KeyValueRepo::set(&conn, PROFILE_KV_VERSION, &next.to_string()) {
                tracing::warn!("broadcast_my_profile: KV bump version failed: {}", e);
                return Err(MessengerError::DatabaseError { description: format!("bump version: {}", e) });
            }
            next
        };

        // --- Ensure avatar/cover are uploaded to blob (only if changed) ---
        let cover_path = {
            let conn = self.db.connection();
            KeyValueRepo::get(&conn, PROFILE_KV_COVER).ok().flatten().unwrap_or_default()
        };
        self.sync_profile_image(avatar_path, PROFILE_KV_AVATAR_REF, PROFILE_KV_AVATAR_MHASH, PROFILE_KV_AVATAR_SRC, PROFILE_KV_AVATAR_VER);
        self.sync_profile_image(&cover_path, PROFILE_KV_COVER_REF, PROFILE_KV_COVER_MHASH, PROFILE_KV_COVER_SRC, PROFILE_KV_COVER_VER);
        let video_path = {
            let conn = self.db.connection();
            KeyValueRepo::get(&conn, PROFILE_KV_VIDEO).ok().flatten().unwrap_or_default()
        };
        self.sync_profile_blob(&video_path, PROFILE_KV_VIDEO_REF, PROFILE_KV_VIDEO_MHASH, PROFILE_KV_VIDEO_SRC,
            PROFILE_KV_VIDEO_VER, "avatar.mp4", alpha_media::MediaType::Video);

        // --- Read the small refs/hashes/versions for the message ---
        let (a_ref, a_hash, a_ver, c_ref, c_hash, c_ver, v_ref, v_ver) = {
            let conn = self.db.connection();
            let g = |k: &str| KeyValueRepo::get(&conn, k).ok().flatten().unwrap_or_default();
            let gv = |k: &str| g(k).parse::<u64>().unwrap_or(0);
            (g(PROFILE_KV_AVATAR_REF), g(PROFILE_KV_AVATAR_MHASH), gv(PROFILE_KV_AVATAR_VER),
             g(PROFILE_KV_COVER_REF), g(PROFILE_KV_COVER_MHASH), gv(PROFILE_KV_COVER_VER),
             g(PROFILE_KV_VIDEO_REF), gv(PROFILE_KV_VIDEO_VER))
        };

        self.device_note(serde_json::json!({
            "op": "profile", "name": display_name, "bio": bio, "avatar_ref": a_ref, "avatar_hash": a_hash,
        }));

        // --- Build the ProfileUpdate message (metadata only) ---
        let (presence_secret, wake_topic, listen_relays) = {
            let conn = self.db.connection();
            let (p, w) = own_contact_secrets(&conn);
            (p, w, relay_routes::own_listen_relays(&conn))
        };
        let msg = AlphaMessage::ProfileUpdate {
            version: new_version as u64,
            display_name: display_name.to_string(),
            bio: bio.to_string(),
            avatar_jpeg_base64: String::new(),
            avatar_hash: a_hash,
            cover_jpeg_base64: String::new(),
            cover_hash: c_hash,
            avatar_ref: a_ref,
            cover_ref: c_ref,
            avatar_version: a_ver,
            cover_version: c_ver,
            presence_secret,
            wake_topic,
            ts: chrono::Utc::now().to_rfc3339(),
            scope: None,
            status: None,
            status_color: None,
            can_call: None,
            can_video: None,
            video_ref: v_ref,
            video_version: v_ver,
            relays: listen_relays,
        };

        // --- Enumerate chats ---
        let target_chat_ids: Vec<String> = {
            let conn = self.db.connection();
            ChatRepo::list(&conn)
                .map_err(|e| MessengerError::DatabaseError { description: format!("list chats: {}", e) })?
                .into_iter()
                .filter(|c| !is_local_chat(&c.id))
                // Never into a channel: it would hand every subscriber our
                // name, presence secret and wake topic.
                .filter(|c| !alpha_database::group_roles::is_channel(&conn, &c.id))
                // Nor where we are the hidden owner: our name would unmask us.
                .filter(|c| !hidden_here(&conn, &c.id))
                // Nor into a request we have not accepted: a stranger gets
                // neither our name and photo nor our presence secret.
                .filter(|c| !alpha_database::privacy::is_request(&conn, &c.id))
                .map(|c| c.id)
                .collect()
        };
        tracing::info!("broadcast_my_profile: shipping v{} to {} chat(s)", new_version, target_chat_ids.len());

        // --- Encrypt + publish per chat ---
        let mut sent = 0;
        let mut skipped = 0;
        for chat_id in &target_chat_ids {
            let group_id = match hex::decode(chat_id) {
                Ok(g) => g,
                Err(_) => { skipped += 1; continue; }
            };
            // What this chat may see of us (privacy settings).
            let payload_bytes = {
                let conn = self.db.connection();
                match alpha_messages::serialize(&shape_profile(&conn, &msg, chat_id)) {
                    Ok(b) => b,
                    Err(_) => { skipped += 1; continue; }
                }
            };
            // Encrypt under the chat's MLS group.
            let cipher_res = {
                let mut mls = match self.mls_session.lock() {
                    Ok(g) => g,
                    Err(e) => {
                        tracing::warn!("broadcast_my_profile: mls lock poisoned: {}", e);
                        continue;
                    }
                };
                if mls.group_info(&group_id).is_none() {
                    skipped += 1;
                    continue;
                }
                mls.send(&group_id, &payload_bytes)
            };
            let ciphertext = match cipher_res {
                Ok(c) => c,
                Err(e) => {
                    tracing::warn!("broadcast_my_profile: MLS encrypt failed for chat {}: {}", chat_id, e);
                    skipped += 1;
                    continue;
                }
            };
            let epoch = {
                let Ok(mls) = self.mls_session.lock() else { skipped += 1; continue };
                mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
            };
            if let Err(e) = self.publish_application_message(&group_id, epoch, ciphertext) {
                tracing::warn!("broadcast_my_profile: publish failed for chat {}: {}", chat_id, e);
            } else {
                sent += 1;
            }
        }

        // Persist MLS storage once after all sends.
        self.persist_mls_storage();

        tracing::info!(
            "broadcast_my_profile: v{} sent to {} chat(s), {} skipped, {}ms",
            new_version, sent, skipped, t0.elapsed().as_millis(),
        );
        Ok(())
    }

    /// Ensure a special local-only "Saved Messages" chat exists and return
 /// its info. This is a notes-to-self space (like Saved
    /// Messages) — never published to relays, never encrypted with MLS,
    /// just stored locally inside SQLCipher. Pinned by default so it sits
    /// at the top of the Chats tab.
    pub fn ensure_saved_messages_chat(&self) -> Result<ChatInfo, MessengerError> {
        let conn = self.db.connection();
        // Reuse if already created.
        if let Ok(Some(c)) = ChatRepo::get(&conn, SAVED_MESSAGES_CHAT_ID) {
            tracing::debug!("ensure_saved_messages_chat: reusing existing row");
            return Ok(chat_info_basic(
                c.id, "direct".to_string(), c.name, 0, c.created_at,
            ));
        }
        tracing::info!("ensure_saved_messages_chat: creating fresh row");
        // First-time creation: insert chat row + pin it.
        let mut chat = new_chat(
            SAVED_MESSAGES_CHAT_ID.to_string(),
            alpha_database::models::ChatType::Direct,
            Some("Избранное".to_string()),
            0,
        );
        chat.pinned_at = Some(chrono::Utc::now().timestamp());
        ChatRepo::insert(&conn, &chat)
            .map_err(|e| MessengerError::DatabaseError { description: format!("create saved messages: {}", e) })?;
        ChatRepo::set_pinned(&conn, SAVED_MESSAGES_CHAT_ID, true)
            .map_err(|e| MessengerError::DatabaseError { description: format!("pin saved messages: {}", e) })?;
        Ok(chat_info_basic(
            chat.id, "direct".to_string(), chat.name, 0, chat.created_at,
        ))
    }

    /// List all chats with the chat-list UX extras (preview, unread count,
    /// pin/mute/archive flags). Pinned first, then by updated_at desc.
    pub fn list_chats(&self) -> Result<Vec<ChatInfo>, MessengerError> {
        let conn = self.db.connection();
        let chats = ChatRepo::list(&conn)
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;

        // Message ids still waiting in the offline queues — used to mark the
        // chat-list preview's last message as "sending" (clock) when it's ours.
        let pending_ids: std::collections::HashSet<String> = {
            let mut s = std::collections::HashSet::new();
            if let Ok(v) = PendingSendRepo::list(&conn) { for e in v { s.insert(e.msg_id); } }
            if let Ok(v) = PendingMediaRepo::list(&conn) { for e in v { s.insert(e.msg_id); } }
            s
        };

        // Scheduled messages are not in the conversation until they go out.
        let held_ids: std::collections::HashSet<String> = KeyValueRepo::list(&conn).unwrap_or_default()
            .into_iter().filter_map(|e| e.key.strip_prefix(KV_HELD_PREFIX).map(str::to_string)).collect();

        let mut out = Vec::with_capacity(chats.len());
        for c in chats {
            let latest = MessageRepo::latest_in_chat_skipping(&conn, &c.id, &held_ids).ok().flatten();
            let unread = MessageRepo::count_unread(&conn, &c.id).unwrap_or(0) as u32;
            // Status of the latest message (only meaningful when it's ours).
            // SINGLE SOURCE OF TRUTH: this precedence mirrors MessageUi.toMessageUi
            // (MessageUi.kt:181-186,344) exactly so the chat-list preview and the
            // chat bubble never diverge:
            //   read_at||consumed → "read"   (✓✓ accent)
            //   delivered_at      → "delivered" (✓✓ grey)
            //   pending (only when NOT yet delivered/read) → "sending" (🕐)
            //   else              → "sent"   (✓)
            let last_status = match &latest {
                Some(m) if m.sender_id == "self" => {
                    if m.read_at.is_some() || m.consumed {
                        "read".to_string()
                    } else if m.delivered_at.is_some() {
                        "delivered".to_string()
                    } else {
                        let media_pending = m.media_ref.as_deref()
                            .map(|j| j.contains("\"pending\":true"))
                            .unwrap_or(false);
                        if media_pending || pending_ids.contains(&m.id) {
                            "sending".to_string()
                        } else {
                            "sent".to_string()
                        }
                    }
                }
                _ => String::new(),
            };
            let (preview, msg_at, kind, outgoing, unplayed) = match latest {
                Some(m) => {
                    let body = m.content.clone().unwrap_or_default();
                    // For media, refine the kind from the MediaRef's media_type
                    // ("image"/"video"/"audio"/"file") so the chat list shows a
                    // photo / video / voice / file label+icon, not just "media".
                    // A whisper (one-view row with a reading time) is named, never shown.
                    let whisper = (m.expiry_policy == Some(alpha_database::models::ExpiryPolicyType::ViewOnce)
                        && m.ttl_seconds.unwrap_or(0) > 0)
                        || alpha_database::whispers::is_whisper(&conn, &m.id);
                    let glance = alpha_database::whispers::glance_kind(&conn, &m.id);
                    let kind = if let Some(k) = glance {
                        if k == "video" { "glance_video".to_string() } else { "glance_image".to_string() }
                    } else if whisper {
                        "whisper".to_string()
                    } else if m.msg_type.as_str() == "media" {
                        m.media_ref.as_deref()
                            .and_then(|j| serde_json::from_str::<serde_json::Value>(j).ok())
                            .map(|v| {
                                // A one-item album is that item (a lone photo / video), not "Album".
                                let single = v.get("items").and_then(|a| a.as_array())
                                    .filter(|a| a.len() == 1).and_then(|a| a.first()).cloned();
                                let v = single.as_ref().unwrap_or(&v);
                                let mt = v.get("media_type").and_then(|t| t.as_str()).unwrap_or("media");
                                let fname = v.get("filename").and_then(|t| t.as_str()).unwrap_or("");
                                // Voice notes are recorded as voice_*.m4a → mark
                                // them distinctly from music files.
                                if mt == "audio" && fname.starts_with("voice") { "voice".to_string() }
                                // A GIF and a circle are videos named for what they are.
                                else if mt == "video" && fname.starts_with("gif_") { "gif".to_string() }
                                else if mt == "video" && fname.starts_with("round_") { "round".to_string() }
                                else if mt == "image" && fname.starts_with("sticker_") { "sticker".to_string() }
                                else { mt.to_string() }
                            })
                            .unwrap_or_else(|| "media".to_string())
                    } else {
                        m.msg_type.as_str().to_string()
                    };
                    // A poll is previewed by its question.
                    let body = if kind == "poll" && body.trim().is_empty() {
                        m.media_ref.as_deref()
                            .and_then(|j| serde_json::from_str::<serde_json::Value>(j).ok())
                            .and_then(|v| v.get("q").and_then(|q| q.as_str()).map(str::to_string))
                            .unwrap_or_default()
                    } else { body };
                    let outgoing = m.sender_id == "self";
                    // Compact preview — UI also has a per-type icon, so we
                    // keep the text short. Take the first 120 CHARS (not bytes):
                    // byte-slicing panics when index 120 lands inside a multi-byte
                    // UTF-8 char (e.g. Cyrillic = 2 bytes) — that panic broke
                    // list_chats entirely and made chats appear to vanish.
                    let preview = if body.chars().count() > 120 {
                        format!("{}…", body.chars().take(120).collect::<String>())
                    } else {
                        body
                    };
                    // Voice preview stays "unplayed" (accent) until the chat is
                    // read (opening it sets read_at) — then it goes grey. Preview-
                    // only signal; no per-bubble play tracking needed.
                    let unplayed = kind == "voice" && m.sender_id != "self"
                        && m.read_at.is_none() && !m.consumed;
                    (preview, m.created_at.clone(), kind, outgoing, unplayed)
                }
                None => (String::new(), String::new(), String::new(), false, false),
            };
            let ephemeral_seconds = alpha_database::KeyValueRepo::get(&conn, &format!("chat_ephemeral:{}", c.id))
                .ok().flatten().and_then(|s| s.parse::<u32>().ok()).unwrap_or(0);
            out.push(ChatInfo {
                id: c.id,
                chat_type: c.chat_type.as_str().to_string(),
                name: c.name,
                epoch: c.epoch as u64,
                created_at: c.created_at,
                last_message_preview: preview,
                last_message_at: msg_at,
                last_message_kind: kind,
                last_message_outgoing: outgoing,
                last_message_status: last_status,
                unread_count: unread,
                unread_override: c.unread_override,
                pinned_at: c.pinned_at.unwrap_or(0).max(0) as u64,
                muted_until: c.muted_until.unwrap_or(0).max(0) as u64,
                archived_at: c.archived_at.unwrap_or(0).max(0) as u64,
                avatar_path: c.avatar_path,
                description: c.description,
                ephemeral_seconds,
                last_message_unplayed: unplayed,
                member_count: 0,
                my_role: String::new(),
                channel_settings: String::new(),
            });
        }
        drop(conn);
        // Who I am in each group and how many are in it, so a chat opens with its real
        // header and composer on the first frame. Each lock on its own: identity, then
        // the database, then MLS — never one inside another.
        for c in out.iter_mut().filter(|c| c.chat_type == "group") {
            let own_hex = self.own_hex_in(&c.id);
            {
                let conn = self.db.connection();
                if !own_hex.is_empty() { c.my_role = alpha_database::group_roles::role_of(&conn, &c.id, &own_hex); }
            }
            c.channel_settings = self.channel_settings(c.id.clone());
            if c.my_role.is_empty() { c.my_role = alpha_database::group_roles::ROLE_MEMBER.to_string(); }
            if let Ok(gid) = hex::decode(&c.id) {
                c.member_count = self.people_in(&gid);
            }
        }
        Ok(out)
    }

    /// Every attachment in the history as `[chat_id, media_type, hash, local_path]`
    /// quadruples, flattened: what storage accounting needs, nothing more.
    pub fn stored_media(&self) -> Result<Vec<String>, MessengerError> {
        let conn = self.db.connection();
        let rows = MessageRepo::stored_media(&conn)
            .map_err(|e| MessengerError::DatabaseError { description: format!("stored_media: {}", e) })?;
        Ok(rows.into_iter().flat_map(|(c, t, h, p)| [c, t, h, p]).collect())
    }

    /// A chat's messages of these kinds over its whole history, newest first.
    pub fn chat_media(&self, chat_id: String, kinds: Vec<String>, limit: u32, offset: u32) -> Result<Vec<MessageInfo>, MessengerError> {
        let conn = self.db.connection();
        let k: Vec<&str> = kinds.iter().map(String::as_str).collect();
        let rows = MessageRepo::of_kinds(&conn, &chat_id, &k, limit, offset)
            .map_err(|e| MessengerError::DatabaseError { description: format!("chat_media: {}", e) })?;
        Ok(rows.into_iter().map(MessageInfo::from).collect())
    }

    /// The messages with these ids (a search's hits drawn as tiles and cards).
    pub fn messages_by_ids(&self, ids: Vec<String>) -> Result<Vec<MessageInfo>, MessengerError> {
        let conn = self.db.connection();
        let rows = MessageRepo::by_ids(&conn, &ids)
            .map_err(|e| MessengerError::DatabaseError { description: format!("messages_by_ids: {}", e) })?;
        Ok(rows.into_iter().map(MessageInfo::from).collect())
    }

    /// How many rows of the chat's history come after `message_id`: a jump loads
    /// that many and a page more. None when the message is gone.
    pub fn message_rank(&self, chat_id: String, message_id: String) -> Result<Option<u32>, MessengerError> {
        let conn = self.db.connection();
        MessageRepo::rank_from_newest(&conn, &chat_id, &message_id)
            .map_err(|e| MessengerError::DatabaseError { description: format!("message_rank: {}", e) })
    }

    /// The first message of `chat_id` from `at` (RFC 3339) on; None when nothing
    /// was written since.
    pub fn message_at_date(&self, chat_id: String, at: String) -> Result<Option<String>, MessengerError> {
        let at = utc_bound(&at)
            .ok_or_else(|| MessengerError::DatabaseError { description: format!("message_at_date: bad time {at}") })?;
        let conn = self.db.connection();
        MessageRepo::first_at_or_after(&conn, &chat_id, &at)
            .map_err(|e| MessengerError::DatabaseError { description: format!("message_at_date: {}", e) })
    }

    /// Messages matching `query` word by word (prefixes), newest first, in one
    /// chat or in all of them; a page of `limit` from `offset`.
    #[allow(clippy::too_many_arguments)]
    pub fn search_messages(
        &self,
        query: String,
        kind: Option<String>,
        chat_id: Option<String>,
        sender: Option<String>,
        since: Option<String>,
        until: Option<String>,
        limit: u32,
        offset: u32,
    ) -> Result<Vec<SearchHit>, MessengerError> {
        // The bounds come as RFC 3339 with any offset; the table keeps UTC.
        let bound = |t: Option<String>| -> Result<Option<String>, MessengerError> {
            match t {
                None => Ok(None),
                Some(t) => utc_bound(&t).map(Some)
                    .ok_or_else(|| MessengerError::DatabaseError { description: format!("search: bad time {t}") }),
            }
        };
        let (since, until) = (bound(since)?, bound(until)?);
        let filter = alpha_database::repositories::SearchFilter {
            kind: kind.as_deref(),
            chat_id: chat_id.as_deref(),
            sender: sender.as_deref(),
            since: since.as_deref(),
            until: until.as_deref(),
        };
        let conn = self.db.connection();
        let rows = MessageRepo::search(&conn, &query, &filter, limit, offset)
            .map_err(|e| MessengerError::DatabaseError { description: format!("search: {}", e) })?;
        Ok(rows.into_iter().map(|r| SearchHit {
            message_id: r.id,
            chat_id: r.chat_id,
            sender_id: r.sender_id,
            content: r.content,
            created_at: r.created_at,
            msg_type: r.msg_type,
            media_kind: r.media_kind,
            media_name: r.media_name,
        }).collect())
    }

    // --- Chat-list UX mutations ---

    /// Pin a chat to the top. `pinned = false` unpins.
    pub fn pin_chat(&self, chat_id: String, pinned: bool) -> Result<(), MessengerError> {
        ChatRepo::set_pinned(&self.db.connection(), &chat_id, pinned)
            .map_err(|e| MessengerError::DatabaseError { description: format!("pin_chat: {}", e) })?;
        self.device_note(serde_json::json!({ "op": "pin", "chat": chat_id, "on": pinned }));
        Ok(())
    }

    /// Mute notifications for `seconds`. Pass 0 to unmute; pass i64::MAX to
    /// mute forever.
    pub fn mute_chat(&self, chat_id: String, seconds: i64) -> Result<(), MessengerError> {
        let until = if seconds <= 0 {
            None
        } else if seconds == i64::MAX {
            Some(i64::MAX)
        } else {
            let now = chrono::Utc::now().timestamp();
            Some(now.saturating_add(seconds))
        };
        ChatRepo::set_muted(&self.db.connection(), &chat_id, until)
            .map_err(|e| MessengerError::DatabaseError { description: format!("mute_chat: {}", e) })?;
        self.device_note(serde_json::json!({ "op": "mute", "chat": chat_id, "until": until.unwrap_or(0) }));
        Ok(())
    }

    pub fn archive_chat(&self, chat_id: String, archived: bool) -> Result<(), MessengerError> {
        ChatRepo::set_archived(&self.db.connection(), &chat_id, archived)
            .map_err(|e| MessengerError::DatabaseError { description: format!("archive_chat: {}", e) })?;
        self.device_note(serde_json::json!({ "op": "archive", "chat": chat_id, "on": archived }));
        Ok(())
    }

    /// Delete a chat. For a 1:1 this is a LOCAL-only delete (Variant-1: keep the
    /// MLS group alive so the dialog reappears on a new message). For a GROUP /
    /// channel it is a REAL, terminal leave (self-remove from MLS) — otherwise we
    /// stay in the member list and a peer's message auto-recreates a stray 1:1.
    /// Delete a one-to-one chat here and at the other side, history and all.
    /// The other side is told first (its MLS state is needed to encrypt);
    /// a group or a secret chat falls back to a delete here.
    pub fn delete_chat_for_both(&self, chat_id: String) -> Result<(), MessengerError> {
        let direct = {
            let conn = self.db.connection();
            ChatRepo::get(&conn, &chat_id).ok().flatten().is_some_and(|c| c.chat_type.as_str() != "group")
                && !self.is_secret_chat_in(&conn, &chat_id)
        };
        if !direct { return self.delete_chat(chat_id); }
        let sent = (|| {
            let group_id = hex::decode(&chat_id).ok()?;
            let msg = AlphaMessage::ChatDeleted { ts: chrono::Utc::now().to_rfc3339() };
            let payload = alpha_messages::serialize(&msg).ok()?;
            let (ciphertext, epoch) = {
                let mut mls = self.mls_session.lock().ok()?;
                mls.group_info(&group_id)?;
                let ct = mls.send(&group_id, &payload).ok()?;
                let ep = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
                (ct, ep)
            };
            self.publish_application_message(&group_id, epoch, ciphertext).ok()
        })();
        tracing::info!(target: "ACCOUNT", "delete for both {}: the other side {}", chat_id,
            if sent.is_some() { "was told" } else { "could not be told now" });
        wipe_direct_raw(&self.db, &self.mls_session, &chat_id);
        self.persist_mls_storage();
        self.device_note(serde_json::json!({ "op": "deleted", "chat": chat_id }));
        Ok(())
    }

    pub fn delete_chat(&self, chat_id: String) -> Result<(), MessengerError> {
        self.delete_chat_here(&chat_id)?;
        if is_assistant_chat(&chat_id) { return Ok(()); }
        self.device_note(serde_json::json!({ "op": "deleted", "chat": chat_id }));
        Ok(())
    }

    /// [`Self::delete_chat`] on this phone only.
    pub(super) fn delete_chat_here(&self, chat_id: &str) -> Result<(), MessengerError> {
        let chat_id = chat_id.to_string();
        tracing::warn!("delete_chat called: chat_id={}", chat_id);
        // Determine the chat type BEFORE we delete the row.
        let is_group = {
            let conn = self.db.connection();
            ChatRepo::get(&conn, &chat_id).ok().flatten()
                .map(|c| c.chat_type.as_str() == "group")
                .unwrap_or(false)
        };
        if is_group {
            // If we OWN this group, deleting = DISBAND: tell every member (before
            // we tear down our own MLS state — we need it to encrypt) so their
            // group is torn down too, with a "Group deleted by owner" notice +
            // locked composer. A non-owner deleting just leaves locally.
            let we_are_owner = {
                let own_hex = self.own_hex_in(&chat_id);
                let conn = self.db.connection();
                !own_hex.is_empty()
                    && alpha_database::group_roles::owner_of(&conn, &chat_id).as_deref()
                        == Some(own_hex.as_str())
            };
            if we_are_owner {
                self.send_group_disbanded(&chat_id);
            }
            // GROUPS/CHANNELS: tear down our LOCAL MLS state so we stop receiving
            // the group's messages. Variant-1's "keep MLS alive so the dialog
            // reappears" is 1:1-ONLY — for a group it left us in the ratchet tree,
            // so we kept receiving and the peer's next message auto-recreated the
            // chat as a stray 1:1. delete_group works (NOT a self-commit remove,
            // which OpenMLS rejects).
            if let Ok(gid) = hex::decode(&chat_id) {
                if !gid.is_empty() {
                    if let Ok(mut mls) = self.mls_session.lock() {
                        let _ = mls.delete_group(&gid);
                    }
                    self.persist_mls_storage();
                    let conn = self.db.connection();
                    let _ = alpha_database::MlsGroupRepo::update(&conn, &chat_id, None, Some(false));
                }
            }
        }
        // Wipe local history + the chat row (delete = gone from the list). For a 1:1
 // the MLS group + peer mapping stay alive (other messengers reappear-on-msg).
        {
            let conn = self.db.connection();
            let _ = alpha_database::MessageRepo::delete_for_chat(&conn, &chat_id);
            let _ = KeyValueRepo::delete(&conn, &format!("banned:{}", chat_id));
            ChatRepo::delete(&conn, &chat_id)
                .map_err(|e| MessengerError::DatabaseError { description: format!("delete_chat: {}", e) })?;
        }
        self.forget_persona(&chat_id);
        Ok(())
    }

    /// Persist the per-chat disappearing-messages timer (seconds; 0 = off).
    pub fn set_chat_ephemeral(&self, chat_id: String, seconds: u32) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        if seconds == 0 {
            let _ = alpha_database::KeyValueRepo::delete(&conn, &format!("chat_ephemeral:{}", chat_id));
        } else {
            let _ = alpha_database::KeyValueRepo::set(&conn, &format!("chat_ephemeral:{}", chat_id), &seconds.to_string());
        }
        Ok(())
    }

    /// Read the per-chat disappearing-messages timer (seconds; 0 = off).
    pub fn get_chat_ephemeral(&self, chat_id: String) -> u32 {
        let conn = self.db.connection();
        alpha_database::KeyValueRepo::get(&conn, &format!("chat_ephemeral:{}", chat_id))
            .ok().flatten().and_then(|s| s.parse::<u32>().ok()).unwrap_or(0)
    }

    // --- Presence (FFI surface) ---
    // See PRESENCE_PROTOCOL.md. Pure state access; transport heartbeat/ingest
    // are handled by their own tasks. All calls are best-effort + non-panicking so a
    // presence hiccup can never affect chat/MLS.

    /// Report the platform run mode: "foreground" | "background" | "stopped".
    pub fn presence_set_mode(&self, mode: String) {
        let m = match mode.as_str() {
            "foreground" => crate::presence_engine::PresenceMode::Foreground,
            "background" => crate::presence_engine::PresenceMode::Background,
            _ => crate::presence_engine::PresenceMode::Stopped,
        };
        if let Ok(mut p) = self.presence.lock() { p.set_mode(m); }
    }

    /// Set OUR manual status: `busy` (DND), `custom` text ("" = clear), `color`
    /// packed 0xRRGGBBAA (0 = leave unchanged).
    pub fn presence_set_my_status(&self, busy: bool, custom: String, color: u32) {
        // Busy stays a presence signal. The text and its colour are a profile
        // fact with their own audience (privacy "status"): kept here and sent
        // in each profile copy, never in the presence heartbeat.
        if let Ok(mut p) = self.presence.lock() {
            p.set_my_status(busy, None, None);
        }
        let text: String = custom.trim().chars().take(140).collect();
        let changed = {
            let conn = self.db.connection();
            let before_t = KeyValueRepo::get(&conn, KV_MY_STATUS_TEXT).ok().flatten().unwrap_or_default();
            let before_c = KeyValueRepo::get(&conn, KV_MY_STATUS_COLOR).ok().flatten().unwrap_or_default();
            let _ = KeyValueRepo::set(&conn, KV_MY_STATUS_TEXT, &text);
            let _ = KeyValueRepo::set(&conn, KV_MY_STATUS_COLOR, &color.to_string());
            before_t != text || before_c != color.to_string()
        };
        if changed {
            let _ = self.broadcast_my_profile_now();
        }
    }

    /// Arm the heartbeat scheduler (first beat fires 2–5s later). Idempotent.
    pub fn presence_arm(&self) {
        let now = chrono::Utc::now().timestamp();
        if let Ok(mut p) = self.presence.lock() { p.arm(now); }
    }

    /// A peer's effective status: "online" | "away" | "busy" | "offline".
    pub fn presence_status(&self, user_id: String) -> String {
        let now = chrono::Utc::now().timestamp();
        self.presence.lock()
            .map(|p| p.effective_status(&user_id, now).as_str().to_string())
            .unwrap_or_else(|_| "offline".to_string())
    }

    /// A peer's `last_seen` unix seconds (0 = unknown).
    pub fn presence_last_seen(&self, user_id: String) -> i64 {
        self.presence.lock().map(|p| p.last_seen(&user_id)).unwrap_or(0)
    }

    /// A peer's custom text status ("" = none) — what their profile lets us see.
    /// `user_id`: their pubkey hex.
    pub fn presence_custom_status(&self, user_id: String) -> String {
        self.peer_status(&user_id).0
    }

    /// A peer's custom status colour packed 0xRRGGBBAA (0 = none).
    pub fn presence_color(&self, user_id: String) -> u32 {
        self.peer_status(&user_id).1
    }

    /// May we call this member (`sender_id`)? `{"voice": bool, "video": bool}`
    /// from their latest profile copy (no copy yet = allowed; their phone
    /// decides anyway).
    pub fn peer_call_rights(&self, sender_id: String) -> String {
        let conn = self.db.connection();
        let v = KeyValueRepo::get(&conn, &format!("peer_calls:{sender_id}")).ok().flatten()
            .and_then(|j| serde_json::from_str::<serde_json::Value>(&j).ok());
        let voice = v.as_ref().and_then(|v| v.get("v")).and_then(|b| b.as_bool()).unwrap_or(true);
        let video = v.as_ref().and_then(|v| v.get("x")).and_then(|b| b.as_bool()).unwrap_or(true);
        serde_json::json!({"voice": voice, "video": video}).to_string()
    }

    /// A peer's advertised capabilities across reachable devices.
    pub fn presence_capabilities(&self, user_id: String) -> Vec<String> {
        let now = chrono::Utc::now().timestamp();
        self.presence.lock().map(|p| p.capabilities(&user_id, now)).unwrap_or_default()
    }

    /// Presence driver: one presence tick. Publishes OUR heartbeat if due, pulls
    /// peers' latest presence + ingests them, and returns the users whose
    /// effective status CHANGED as JSON `[{"user":"<hex>","status":"online"}…]`
    /// (empty `[]` if nothing changed). Kotlin calls this on a background loop.
    /// Fully isolated + best-effort — never affects chat/MLS.
    pub fn presence_tick(&self) -> String {
        use alpha_transport::sealed::{current_hour, presence_key, presence_tag};
        let now = chrono::Utc::now().timestamp();
        self.sweep_media_purges(now);
        let _ = self.release_due_held();
        self.retry_story_posts(now);
        let hour = current_hour();

        let (own_presence, contacts, broadcast_due) = {
            let conn = self.db.connection();
            let own = own_secret(&conn, KV_OWN_PRESENCE_SECRET);
            let contacts = load_contact_secrets(&conn);
            let last: i64 = KeyValueRepo::get(&conn, KV_SECRETS_BROADCAST_AT).ok().flatten()
                .and_then(|v| v.parse().ok()).unwrap_or(0);
            (own, contacts, now - last > SECRETS_REBROADCAST_SECS)
        };

        // Contacts' presence channels: routing table for the listener and the
        // tags of the live subscription (previous/current/next hour for skew).
        let mut routes: std::collections::HashMap<_, (Vec<String>, _)> = std::collections::HashMap::new();
        for c in &contacts {
            if let Some(secret) = c.presence {
                let key = presence_key(&secret);
                for h in [hour.saturating_sub(1), hour, hour + 1] {
                    let owners = &mut routes.entry(presence_tag(&secret, h)).or_insert_with(|| (Vec::new(), key)).0;
                    if !owners.contains(&c.npub_hex) {
                        owners.push(c.npub_hex.clone());
                    }
                }
            }
        }
        let tags: Vec<String> = routes.keys().map(hex::encode).collect();
        if let Ok(mut r) = self.presence_routes.lock() {
            *r = routes;
        }
        if self.transport.set_presence_tags(tags) {
            self.spawn_resubscribe();
        }

        // Share our presence secret + wake topic with contacts: once after the
        // upgrade, then weekly (new chats also get them via ProfileRequest).
        if broadcast_due && runtime().block_on(self.transport.is_connected()) {
            {
                let conn = self.db.connection();
                let _ = KeyValueRepo::set(&conn, KV_SECRETS_BROADCAST_AT, &now.to_string());
            }
            if let Err(e) = self.broadcast_my_profile_now() {
                tracing::warn!("contact secrets broadcast failed: {}", e);
            }
        }

        // Our heartbeat on the current hour's tag, signed by a one-time key.
        // Ghost mode publishes none: contacts see us as offline.
        let ghost = self.ghost_mode();
        let due_payload = if ghost { None } else {
            match self.presence.lock() {
                Ok(mut p) => {
                    let mode = p.mode();
                    if p.should_ping(mode, now) {
                        let seq = p.next_seq();
                        p.mark_pinged(now);
                        Some(serde_json::json!({
                            "v": crate::presence_engine::PROTOCOL_VERSION,
                            "device_id": self.presence_device_id(),
                            "seq": seq,
                            "ts": now,
                            "busy": p.my_busy(),
                            "custom": p.my_custom(),
                            "color": p.my_color(),
                            "capabilities": Vec::<String>::new(),
                            "fg": matches!(mode, crate::presence_engine::PresenceMode::Foreground),
                        }).to_string())
                    } else { None }
                }
                Err(_) => return "[]".to_string(),
            }
        };
        let published = due_payload.is_some();
        if let Some(payload) = due_payload {
            let tag = presence_tag(&own_presence, hour);
            let key = presence_key(&own_presence);
            let transport = (*self.transport).clone();
            runtime().spawn(async move {
                let _ = transport.publish_sealed_presence(&tag, &key, payload.as_bytes()).await;
            });
        }

        // Heartbeats received by the realtime listener since the last tick.
        let inbox: Vec<(String, String)> = self.presence_inbox.lock()
            .map(|mut i| std::mem::take(&mut *i))
            .unwrap_or_default();
        let received = inbox.len();
        let mut accepted = 0usize;
        if !inbox.is_empty() {
            if let Ok(mut p) = self.presence.lock() {
                for (author_hex, content) in inbox {
                    if let Ok(v) = serde_json::from_str::<serde_json::Value>(&content) {
                        let version = v.get("v").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
                        let device_id = v.get("device_id").and_then(|x| x.as_str()).unwrap_or("").to_string();
                        let seq = v.get("seq").and_then(|x| x.as_u64()).unwrap_or(0);
                        let ts = v.get("ts").and_then(|x| x.as_i64()).unwrap_or(0);
                        let busy = v.get("busy").and_then(|x| x.as_bool()).unwrap_or(false);
                        let custom = v.get("custom").and_then(|x| x.as_str()).map(|s| s.to_string());
                        let color = v.get("color").and_then(|x| x.as_u64()).map(|c| c as u32);
                        let caps: Vec<String> = v.get("capabilities").and_then(|x| x.as_array())
                            .map(|a| a.iter().filter_map(|e| e.as_str().map(String::from)).collect())
                            .unwrap_or_default();
                        // Builds before the flag only beat with the app on screen.
                        let fg = v.get("fg").and_then(|x| x.as_bool()).unwrap_or(true);
                        if p.ingest_beat(version, &author_hex, &device_id, seq, ts, busy, custom, color, caps, fg, now) {
                            accepted += 1;
                        }
                    }
                }
            }
        }

        let changes = match self.presence.lock() {
            Ok(mut p) => p.poll_changes(now),
            Err(_) => return "[]".to_string(),
        };
        // Counts only (no keys or people): what was missing shows at once.
        if !changes.is_empty() || received > accepted {
            let online = self.presence.lock().map(|p| p.online_users(now).len()).unwrap_or(0);
            tracing::info!(target: "ACCOUNT", "presence: contacts {} keyed {}, ghost {}, beat {}, in {} ok {}, changed {}, online {}",
                contacts.len(), contacts.iter().filter(|c| c.presence.is_some()).count(), ghost,
                if published { "sent" } else { "-" }, received, accepted, changes.len(), online);
        }
        let arr: Vec<serde_json::Value> = changes.into_iter()
            .map(|(u, s)| serde_json::json!({ "user": u, "status": s.as_str() }))
            .collect();
        serde_json::to_string(&arr).unwrap_or_else(|_| "[]".to_string())
    }

    /// Stable per-install device id for presence multi-device (persisted in KV).
    fn presence_device_id(&self) -> String {
        let conn = self.db.connection();
        if let Some(id) = alpha_database::KeyValueRepo::get(&conn, "presence_device_id").ok().flatten() {
            if !id.is_empty() { return id; }
        }
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        let id = format!("dev-{:x}", nanos);
        let _ = alpha_database::KeyValueRepo::set(&conn, "presence_device_id", &id);
        id
    }

    // --- "Don't keep on servers" ---

    /// Signer for our media uploads: per-blob keys derived from a private root,
    /// so we can delete our own blobs later and servers still can't link them.
    fn media_signer(&self) -> alpha_media::MediaSigner {
        let conn = self.db.connection();
        alpha_media::MediaSigner::derived(own_secret(&conn, KV_MEDIA_KEY_ROOT))
    }

    pub fn set_chat_no_store(&self, chat_id: String, enabled: bool) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        KeyValueRepo::set(&conn, &format!("{KV_NO_STORE_PREFIX}{chat_id}"), if enabled { "1" } else { "0" })
            .map_err(|e| MessengerError::DatabaseError { description: format!("no_store: {}", e) })?;
        if let Ok(g) = hex::decode(&chat_id) { self.transport.set_no_store(&g, enabled); }
        Ok(())
    }

    /// Effective setting: the chat's own choice, else the global default.
    pub fn chat_no_store(&self, chat_id: String) -> bool {
        let conn = self.db.connection();
        match KeyValueRepo::get(&conn, &format!("{KV_NO_STORE_PREFIX}{chat_id}")).ok().flatten() {
            Some(v) => v == "1",
            None => KeyValueRepo::get(&conn, KV_NO_STORE_DEFAULT).ok().flatten().as_deref() == Some("1"),
        }
    }

    /// Global default for all chats and groups. Changing it resets every
    /// per-chat choice, so the switch really applies everywhere.
    pub fn set_no_store_default(&self, enabled: bool) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        KeyValueRepo::set(&conn, KV_NO_STORE_DEFAULT, if enabled { "1" } else { "0" })
            .map_err(|e| MessengerError::DatabaseError { description: format!("no_store: {}", e) })?;
        if let Ok(entries) = KeyValueRepo::list(&conn) {
            for e in entries.iter().filter(|e| e.key.starts_with(KV_NO_STORE_PREFIX)) {
                let _ = KeyValueRepo::delete(&conn, &e.key);
            }
        }
        self.transport.set_no_store_default(enabled);
        Ok(())
    }

    pub fn no_store_default(&self) -> bool {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, KV_NO_STORE_DEFAULT).ok().flatten().as_deref() == Some("1")
    }

    /// Remember the media of an outgoing message so it is deleted from every
    /// storage server once recipients had time to download it.
    pub fn register_media_purge(&self, message_id: String, refs_json: String) -> Result<(), MessengerError> {
        let ids: Vec<String> = match serde_json::from_str::<Vec<String>>(&refs_json) {
            Ok(refs) => refs.iter().flat_map(|r| alpha_media::pipeline::blob_ids_in_ref(r)).collect(),
            Err(_) => alpha_media::pipeline::blob_ids_in_ref(&refs_json),
        };
        if ids.is_empty() { return Ok(()); }
        let due = chrono::Utc::now().timestamp() + PURGE_MAX_AGE_SECS;
        // A secret chat's file is deleted the way it went up: through Tor.
        let tor = refs_json.contains("\"tor\":true");
        let json = serde_json::json!({ "ids": ids, "due": due, "tor": tor }).to_string();
        let conn = self.db.connection();
        KeyValueRepo::set(&conn, &format!("{KV_PURGE_PREFIX}{message_id}"), &json)
            .map_err(|e| MessengerError::DatabaseError { description: format!("purge: {}", e) })
    }

    /// For a "don't keep" chat: schedule deletion of the media's blobs and flag
    /// the wire ref so recipients download it immediately.
    fn mark_no_store(&self, chat_id: &str, msg_id: &str, full_ref_json: &str, wire_json: &str) -> String {
        if !self.chat_no_store(chat_id.to_string()) { return wire_json.to_string(); }
        let _ = self.register_media_purge(msg_id.to_string(), full_ref_json.to_string());
        match serde_json::from_str::<serde_json::Value>(wire_json) {
            Ok(mut v) => {
                if let Some(o) = v.as_object_mut() { o.insert("ns".into(), serde_json::Value::Bool(true)); }
                v.to_string()
            }
            Err(_) => wire_json.to_string(),
        }
    }

    fn on_media_delivered(&self, message_id: &str) {
        let key = format!("{KV_PURGE_PREFIX}{message_id}");
        let conn = self.db.connection();
        let Some(json) = KeyValueRepo::get(&conn, &key).ok().flatten() else { return };
        let Ok(mut v) = serde_json::from_str::<serde_json::Value>(&json) else { return };
        let soon = chrono::Utc::now().timestamp() + PURGE_AFTER_DELIVERY_SECS;
        let due = v.get("due").and_then(|d| d.as_i64()).unwrap_or(soon).min(soon);
        v["due"] = serde_json::json!(due);
        let _ = KeyValueRepo::set(&conn, &key, &v.to_string());
    }

    fn sweep_media_purges(&self, now: i64) {
        let due: Vec<(String, Vec<String>, bool)> = {
            let conn = self.db.connection();
            KeyValueRepo::list(&conn).unwrap_or_default().into_iter()
                .filter(|e| e.key.starts_with(KV_PURGE_PREFIX))
                .filter_map(|e| {
                    let v: serde_json::Value = serde_json::from_str(&e.value).ok()?;
                    (v.get("due")?.as_i64()? <= now).then(|| {
                        let ids = v.get("ids").and_then(|a| a.as_array()).map(|a| {
                            a.iter().filter_map(|x| x.as_str().map(String::from)).collect()
                        }).unwrap_or_default();
                        (e.key, ids, v.get("tor").and_then(|t| t.as_bool()).unwrap_or(false))
                    })
                })
                .collect()
        };
        if due.is_empty() { return; }
        {
            let conn = self.db.connection();
            for (key, _, _) in &due { let _ = KeyValueRepo::delete(&conn, key); }
        }
        let open = (*self.blossom).clone();
        let tor = (*self.secret_blossom).clone();
        let signer = self.media_signer();
        runtime().spawn(async move {
            for (_, ids, via_tor) in due {
                let blossom = if via_tor { &tor } else { &open };
                for id in ids {
                    // Best-effort on every configured server (primary + replicas).
                    let _ = blossom.delete(&id, &signer).await;
                }
            }
        });
    }

    /// Private ntfy topics of our contacts, by npub hex.
    fn contact_wake_topics(&self) -> std::collections::HashMap<String, String> {
        let conn = self.db.connection();
        load_contact_secrets(&conn)
            .into_iter()
            .filter_map(|c| c.wake_topic.map(|t| (c.npub_hex.to_lowercase(), t)))
            .collect()
    }

    pub fn set_read_receipts_enabled(&self, enabled: bool) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        KeyValueRepo::set(&conn, KV_READ_RECEIPTS, if enabled { "1" } else { "0" })
            .map_err(|e| MessengerError::DatabaseError { description: format!("read receipts: {}", e) })
    }

    pub fn read_receipts_enabled(&self) -> bool {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, KV_READ_RECEIPTS).ok().flatten().map(|v| v != "0").unwrap_or(true)
    }

    // --- Scheduled messages ---------------------------------------------

    /// The next message the user sends in `chat_id` waits until `not_before`
    /// (unix seconds). Call right before the ordinary send call.
    pub fn arm_scheduled_send(&self, chat_id: String, not_before: i64) {
        let now = chrono::Utc::now().timestamp();
        if let Ok(mut a) = armed_hold().lock() {
            *a = if chat_id.is_empty() { None } else { Some((chat_id, not_before, now)) };
        }
    }

    /// The next message written in [chat_id] (any kind) is sent without sound.
    pub fn arm_silent_send(&self, chat_id: String) {
        let now = chrono::Utc::now().timestamp();
        if let Ok(mut a) = armed_silent().lock() {
            *a = if chat_id.is_empty() { None } else { Some((chat_id, now)) };
        }
    }

    /// Whether [msg_id] came (or goes) without sound.
    pub fn is_message_silent(&self, msg_id: String) -> bool {
        MessageRepo::is_silent(&self.db.connection(), &msg_id)
    }

    fn row_silent(&self, msg_id: &str) -> bool {
        MessageRepo::is_silent(&self.db.connection(), msg_id)
    }

    /// Media path: the message was just created — take the armed schedule
    /// for its chat (0 = none) so it can be held by id.
    pub fn take_armed_send(&self, chat_id: String) -> i64 {
        take_armed(&chat_id).unwrap_or(0)
    }

    /// Hold a message that already exists (media: created first, published
    /// after the upload). Must be called before its publication.
    pub fn hold_message(&self, msg_id: String, chat_id: String, not_before: i64) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        let ttl = MessageRepo::freeze_expiry(&conn, &msg_id).ok().flatten();
        let _ = MessageRepo::date_held(&conn, &msg_id, not_before);
        let v = serde_json::json!({ "chat": chat_id, "nb": not_before, "kind": "", "payload": "", "ttl": ttl });
        KeyValueRepo::set(&conn, &format!("{KV_HELD_PREFIX}{msg_id}"), &v.to_string())
            .map_err(|e| MessengerError::DatabaseError { description: format!("hold: {}", e) })
    }

    fn held_get(&self, msg_id: &str) -> Option<serde_json::Value> {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, &format!("{KV_HELD_PREFIX}{msg_id}")).ok().flatten()
            .and_then(|s| serde_json::from_str(&s).ok())
    }

    fn held_put(&self, msg_id: &str, v: &serde_json::Value) {
        let conn = self.db.connection();
        let _ = KeyValueRepo::set(&conn, &format!("{KV_HELD_PREFIX}{msg_id}"), &v.to_string());
    }

    fn held_drop(&self, msg_id: &str) {
        let conn = self.db.connection();
        let _ = KeyValueRepo::delete(&conn, &format!("{KV_HELD_PREFIX}{msg_id}"));
    }

    /// True = the bytes were kept for later and must NOT be published now.
    fn hold_if_scheduled(&self, chat_id: &str, msg_id: &str, payload: &[u8], kind: &str) -> bool {
        let now = chrono::Utc::now().timestamp();
        let prev = self.held_get(msg_id);
        let registered = prev.as_ref().and_then(|v| v.get("nb").and_then(|x| x.as_i64()));
        let prev_ttl = prev.as_ref().and_then(|v| v.get("ttl").and_then(|x| x.as_i64()));
        let nb = match registered {
            Some(nb) => nb,
            None => {
                // An armed send applies only to a message the user wrote.
                let user_content = alpha_messages::deserialize(payload).map(|m| m.is_user_content()).unwrap_or(false);
                if !user_content { return false; }
                match take_armed(chat_id) { Some(nb) => nb, None => return false }
            }
        };
        if nb <= now {
            // Due (release or "send now"): go out the normal way, dated now.
            self.held_drop(msg_id);
            if registered.is_some() {
                let conn = self.db.connection();
                let _ = MessageRepo::restamp_created_at(&conn, msg_id, prev_ttl);
            }
            return false;
        }
        let ttl = if registered.is_some() { prev_ttl } else {
            let conn = self.db.connection();
            let _ = MessageRepo::date_held(&conn, msg_id, nb);
            MessageRepo::freeze_expiry(&conn, msg_id).ok().flatten()
        };
        let b64 = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, payload);
        self.held_put(msg_id, &serde_json::json!({ "chat": chat_id, "nb": nb, "kind": kind, "payload": b64, "ttl": ttl }));
        tracing::info!("SCHEDULED msg={} chat={} at={}", msg_id, chat_id, nb);
        true
    }

    // --- Stories ------------------------------------------------------------

    /// Publish a story to the given 1:1 chats (its audience). `params_json`:
    /// {kind, media_ref, text, bg, caption, ttl_secs, allow_replies,
    /// allow_screenshots, chats:[chat ids]}. The media (if any) was uploaded
    /// once already; every viewer gets the same encrypted ref in their own
    /// end-to-end encrypted chat with us. Returns the story id.
    pub fn publish_story(&self, params_json: String) -> Result<String, MessengerError> {
        let p: serde_json::Value = serde_json::from_str(&params_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("story: {}", e) })?;
        let s = |k: &str| p.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
        let b = |k: &str| p.get(k).and_then(|x| x.as_bool()).unwrap_or(false);
        let ttl = p.get("ttl_secs").and_then(|x| x.as_i64()).unwrap_or(86_400).clamp(3600, 48 * 3600);
        let chats: Vec<String> = p.get("chats").and_then(|x| x.as_array())
            .map(|a| a.iter().filter_map(|c| c.as_str().map(str::to_string)).collect()).unwrap_or_default();
        // Never where we own the chat hidden: the story would unmask us.
        let chats: Vec<String> = {
            let conn = self.db.connection();
            chats.into_iter().filter(|c| !hidden_here(&conn, c)).collect()
        };
        let now = chrono::Utc::now().timestamp();
        let id = uuid::Uuid::new_v4().to_string();
        let msg = AlphaMessage::StoryPost {
            story_id: id.clone(), kind: s("kind"), media_ref: s("media_ref"), text: s("text"), bg: s("bg"),
            caption: s("caption"), created_at: now, expires_at: now + ttl,
            allow_replies: b("allow_replies"), allow_screenshots: b("allow_screenshots"),
            stickers: s("stickers"),
            ts: chrono::Utc::now().to_rfc3339(),
        };
        alpha_messages::validate(&msg).map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })?;
        {
            let conn = self.db.connection();
            alpha_database::stories::put_mine(&conn, &id, &serde_json::json!({
                "id": id, "kind": s("kind"), "media_ref": s("media_ref"), "text": s("text"), "bg": s("bg"),
                "caption": s("caption"), "created_at": now, "expires_at": now + ttl,
                "allow_replies": b("allow_replies"), "allow_screenshots": b("allow_screenshots"),
                "chats": chats, "viewers": [], "stickers": s("stickers"),
                // Who it is for: "contacts" and "except" grow with new contacts
                // (share_stories_with_contacts); "close"/"only" are hand-picked.
                "audience": s("audience"),
                "except": p.get("except").cloned().unwrap_or(serde_json::json!([])),
            }));
        }
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let mut pending: Vec<String> = Vec::new();
        for chat in &chats {
            if let Err(e) = self.queue_and_send(chat, &payload, &format!("story-{}-{}", id, chat)) {
                tracing::warn!("story {} to {}: {} — will retry", id, chat, e);
                pending.push(chat.clone());
            }
        }
        // When its time is over the encrypted file leaves the storage server too
        // (a video: its manifest AND every chunk the manifest lists). Resolved
        // before the database is taken: resolving may read it too.
        let mut ids = alpha_media::pipeline::blob_ids_in_ref(&s("media_ref"));
        if !s("media_ref").is_empty() {
            if let Ok(full) = self.resolve_media_ref(s("media_ref")) {
                ids.extend(alpha_media::pipeline::blob_ids_in_ref(&full));
            }
        }
        ids.sort();
        ids.dedup();
        {
            let conn = self.db.connection();
            if !pending.is_empty() {
                if let Some(mut v) = alpha_database::stories::get_mine(&conn, &id) {
                    v["pending"] = serde_json::json!(pending);
                    alpha_database::stories::put_mine(&conn, &id, &v);
                }
            }
            if !ids.is_empty() {
                let json = serde_json::json!({ "ids": ids, "due": now + ttl }).to_string();
                let _ = KeyValueRepo::set(&conn, &format!("{KV_PURGE_PREFIX}story-{id}"), &json);
            }
        }
        Ok(id)
    }

    /// Our active stories meant for "all contacts" (or all but some) reach
    /// people who became contacts after they were posted — a request accepted,
    /// a person added or brought back. Idempotent: a story goes to a chat once.
    /// Returns how many deliveries were queued.
    pub fn share_stories_with_contacts(&self) -> u32 {
        let now = chrono::Utc::now().timestamp();
        let (stories, contact_chats): (Vec<(String, serde_json::Value)>, Vec<String>) = {
            let conn = self.db.connection();
            let stories = alpha_database::stories::mine(&conn).into_iter()
                .filter(|v| v.get("expires_at").and_then(|x| x.as_i64()).unwrap_or(0) > now)
                .filter(|v| matches!(v.get("audience").and_then(|x| x.as_str()), Some("contacts") | Some("except")))
                .filter_map(|v| v.get("id").and_then(|x| x.as_str()).map(|id| (id.to_string(), v.clone())))
                .collect();
            let chats = alpha_database::privacy::live_contacts(&conn).into_iter().map(|(_, chat)| chat).collect();
            (stories, chats)
        };
        let mut queued = 0u32;
        for (id, v) in stories {
            let list = |k: &str| -> Vec<String> {
                v.get(k).and_then(|x| x.as_array())
                    .map(|a| a.iter().filter_map(|c| c.as_str().map(str::to_string)).collect()).unwrap_or_default()
            };
            let (sent, except) = (list("chats"), list("except"));
            let new: Vec<String> = contact_chats.iter()
                .filter(|c| !sent.contains(c) && !except.contains(c))
                .cloned().collect();
            if new.is_empty() { continue; }
            let Ok(payload) = alpha_messages::serialize(&story_post_of(&id, &v, now)) else { continue };
            let mut reached = sent.clone();
            for chat in &new {
                if self.queue_and_send(chat, &payload, &format!("story-{}-{}", id, chat)).is_ok() {
                    reached.push(chat.clone());
                    queued += 1;
                }
            }
            let conn = self.db.connection();
            if let Some(mut cur) = alpha_database::stories::get_mine(&conn, &id) {
                cur["chats"] = serde_json::json!(reached);
                alpha_database::stories::put_mine(&conn, &id, &cur);
            }
        }
        if queued > 0 {
            tracing::info!(target: "ACCOUNT", "stories shared with new contacts: {} deliveries", queued);
        }
        queued
    }

    /// Re-send our stories to the chats they did not reach (we were offline).
    fn retry_story_posts(&self, now: i64) {
        let todo: Vec<(String, serde_json::Value)> = {
            let conn = self.db.connection();
            alpha_database::stories::mine(&conn).into_iter()
                .filter(|v| v.get("expires_at").and_then(|x| x.as_i64()).unwrap_or(0) > now)
                .filter(|v| v.get("pending").and_then(|x| x.as_array()).map(|a| !a.is_empty()).unwrap_or(false))
                .filter_map(|v| v.get("id").and_then(|x| x.as_str()).map(|id| (id.to_string(), v.clone())))
                .collect()
        };
        for (id, v) in todo {
            let Ok(payload) = alpha_messages::serialize(&story_post_of(&id, &v, now)) else { continue };
            let pending: Vec<String> = v.get("pending").and_then(|x| x.as_array())
                .map(|a| a.iter().filter_map(|c| c.as_str().map(str::to_string)).collect()).unwrap_or_default();
            let still: Vec<String> = pending.into_iter()
                .filter(|chat| self.queue_and_send(chat, &payload, &format!("story-{}-{}", id, chat)).is_err())
                .collect();
            let conn = self.db.connection();
            if let Some(mut cur) = alpha_database::stories::get_mine(&conn, &id) {
                cur["pending"] = serde_json::json!(still);
                alpha_database::stories::put_mine(&conn, &id, &cur);
            }
        }
    }

    /// The viewer took a screenshot of a contact's story: tell its author.
    pub fn story_screenshot(&self, story_id: String) -> Result<(), MessengerError> {
        let chat_id = {
            let conn = self.db.connection();
            alpha_database::stories::get_story(&conn, &story_id)
                .and_then(|v| v.get("chat_id").and_then(|x| x.as_str()).map(str::to_string)).unwrap_or_default()
        };
        if chat_id.is_empty() { return Ok(()); }
        let msg = AlphaMessage::StoryView { story_id, reaction: String::new(), screenshot: true, ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("story-shot-{}", uuid::Uuid::new_v4()))
    }

    /// {"mine":[…], "others":[…]} — every story still in its time (expired
    /// ones are removed here). Others carry author_sid / chat_id / seen.
    pub fn stories_json(&self) -> String {
        let conn = self.db.connection();
        alpha_database::stories::sweep_expired(&conn, chrono::Utc::now().timestamp());
        serde_json::json!({
            "mine": alpha_database::stories::mine(&conn),
            "others": alpha_database::stories::stories(&conn),
        }).to_string()
    }

    /// We watched a contact's story: remember it, and tell its author unless
    /// ghost mode keeps us invisible. `reaction` = "" just seen, else an emoji.
    pub fn story_seen(&self, story_id: String, reaction: String) -> Result<(), MessengerError> {
        let chat_id = {
            let conn = self.db.connection();
            let Some(mut v) = alpha_database::stories::get_story(&conn, &story_id) else { return Ok(()) };
            let already = v.get("seen").and_then(|x| x.as_bool()).unwrap_or(false);
            v["seen"] = serde_json::json!(true);
            // "-" takes our reaction back; any other non-empty value sets it.
            if reaction == "-" { v["my_reaction"] = serde_json::json!(""); }
            else if !reaction.is_empty() { v["my_reaction"] = serde_json::json!(reaction); }
            alpha_database::stories::put_story(&conn, &story_id, &v);
            if already && reaction.is_empty() { return Ok(()); }
            v.get("chat_id").and_then(|x| x.as_str()).unwrap_or("").to_string()
        };
        if chat_id.is_empty() || (self.ghost_mode() && reaction.is_empty()) { return Ok(()); }
        let msg = AlphaMessage::StoryView { story_id: story_id.clone(), reaction, screenshot: false, ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("story-view-{}", uuid::Uuid::new_v4()))
    }

    /// Remove our story — here and for everyone it was sent to.
    pub fn delete_my_story(&self, story_id: String) -> Result<(), MessengerError> {
        let chats: Vec<String> = {
            let conn = self.db.connection();
            let chats = alpha_database::stories::get_mine(&conn, &story_id)
                .and_then(|v| v.get("chats").and_then(|x| x.as_array()).cloned())
                .map(|a| a.iter().filter_map(|c| c.as_str().map(str::to_string)).collect()).unwrap_or_default();
            alpha_database::stories::delete_mine(&conn, &story_id);
            // Its file leaves the storage server on the next sweep.
            let key = format!("{KV_PURGE_PREFIX}story-{story_id}");
            if let Some(json) = KeyValueRepo::get(&conn, &key).ok().flatten() {
                if let Ok(mut v) = serde_json::from_str::<serde_json::Value>(&json) {
                    v["due"] = serde_json::json!(0);
                    let _ = KeyValueRepo::set(&conn, &key, &v.to_string());
                }
            }
            chats
        };
        let msg = AlphaMessage::StoryDelete { story_id: story_id.clone(), ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        for chat in chats {
            let _ = self.queue_and_send(&chat, &payload, &format!("story-del-{}-{}", story_id, chat));
        }
        Ok(())
    }

    /// Pinned messages of a chat as a JSON array of ids, newest pin first.
    pub fn pinned_messages_json(&self, chat_id: String) -> String {
        let conn = self.db.connection();
        serde_json::to_string(&alpha_database::group_roles::pinned(&conn, &chat_id)).unwrap_or_else(|_| "[]".into())
    }

    /// Pin (or unpin) a message for everyone in the chat. Refused in a group
    /// when we may not pin; Saved Messages keep it on this phone only.
    pub fn pin_message(&self, chat_id: String, target_id: String, pin: bool) -> Result<(), MessengerError> {
        let own_hex = self.own_hex_in(&chat_id);
        {
            let conn = self.db.connection();
            if !alpha_database::group_roles::may_pin(&conn, &chat_id, &own_hex) {
                return Err(MessengerError::InvalidArgument { description: "нет права закреплять в этой группе".to_string() });
            }
            alpha_database::group_roles::set_pinned(&conn, &chat_id, &target_id, pin);
        }
        if is_local_chat(&chat_id) { return Ok(()); }
        let msg = AlphaMessage::PinMessage { target_id, unpin: !pin, ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("pin-{}", uuid::Uuid::new_v4()))
    }

    /// Publish every held message whose time has come. Returns how many went out.
    pub fn release_due_held(&self) -> u32 {
        let now = chrono::Utc::now().timestamp();
        let due: Vec<(String, serde_json::Value)> = {
            let conn = self.db.connection();
            KeyValueRepo::list(&conn).unwrap_or_default().into_iter()
                .filter_map(|e| {
                    let id = e.key.strip_prefix(KV_HELD_PREFIX)?.to_string();
                    let v: serde_json::Value = serde_json::from_str(&e.value).ok()?;
                    let nb = v.get("nb")?.as_i64()?;
                    let ready = !v.get("payload")?.as_str()?.is_empty();
                    (nb <= now && ready).then_some((id, v))
                })
                .collect()
        };
        let mut sent = 0u32;
        for (msg_id, v) in due {
            // Deleted while waiting: it must not go out unseen.
            let gone = {
                let conn = self.db.connection();
                !MessageRepo::exists_base(&conn, &msg_id).unwrap_or(true)
            };
            if gone { self.held_drop(&msg_id); continue; }
            let chat =v.get("chat").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let kind = v.get("kind").and_then(|x| x.as_str()).unwrap_or("outbox").to_string();
            let payload = match base64::Engine::decode(&base64::engine::general_purpose::STANDARD, v.get("payload").and_then(|x| x.as_str()).unwrap_or("")) {
                Ok(p) => p,
                Err(_) => { self.held_drop(&msg_id); continue; }
            };
            let ok = if kind == "direct" {
                self.held_drop(&msg_id);
                {
                    let conn = self.db.connection();
                    let _ = MessageRepo::restamp_created_at(&conn, &msg_id, v.get("ttl").and_then(|x| x.as_i64()));
                }
                let r = (|| -> Result<(), MessengerError> {
                    let group_id = hex::decode(&chat)
                        .map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })?;
                    let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
                    let ct = mls.send(&group_id, &payload).map_err(|e| MessengerError::CryptoError { description: e.to_string() })?;
                    let epoch = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
                    drop(mls);
                    self.publish_application_message_confirmed(&group_id, epoch, ct)
                })();
                if r.is_ok() { self.clear_send_state(&msg_id, "опубликовано"); }
                else { self.held_put(&msg_id, &v); }
                r.is_ok()
            } else {
                // queue_and_send sees the hold is due, drops it and sends.
                self.queue_and_send(&chat, &payload, &msg_id).is_ok()
            };
            if ok {
                sent += 1;
            }
        }
        sent
    }

    /// [{"id","nb"}] of the held messages of a chat ("" = all chats).
    pub fn held_messages_json(&self, chat_id: String) -> String {
        let conn = self.db.connection();
        let list: Vec<serde_json::Value> = KeyValueRepo::list(&conn).unwrap_or_default().into_iter()
            .filter_map(|e| {
                let id = e.key.strip_prefix(KV_HELD_PREFIX)?.to_string();
                // The message was deleted (or wiped) while waiting: nothing to send.
                if !MessageRepo::exists_base(&conn, &id).unwrap_or(true) {
                    let _ = KeyValueRepo::delete(&conn, &e.key);
                    return None;
                }
                let v: serde_json::Value = serde_json::from_str(&e.value).ok()?;
                let chat = v.get("chat")?.as_str()?.to_string();
                (chat_id.is_empty() || chat == chat_id).then(|| serde_json::json!({ "id": id, "chat": chat, "nb": v.get("nb") }))
            })
            .collect();
        serde_json::Value::Array(list).to_string()
    }

    pub fn reschedule_held(&self, msg_id: String, not_before: i64) -> Result<(), MessengerError> {
        let mut v = self.held_get(&msg_id).ok_or_else(|| MessengerError::InvalidArgument { description: "not scheduled".into() })?;
        v["nb"] = serde_json::json!(not_before);
        self.held_put(&msg_id, &v);
        {
            let conn = self.db.connection();
            let _ = MessageRepo::date_held(&conn, &msg_id, not_before);
        }
        if not_before <= chrono::Utc::now().timestamp() { let _ = self.release_due_held(); }
        Ok(())
    }

    /// Cancel: the message never leaves the phone and disappears from the chat.
    pub fn cancel_held(&self, msg_id: String) -> Result<(), MessengerError> {
        self.held_drop(&msg_id);
        let conn = self.db.connection();
        let _ = MessageRepo::delete(&conn, &msg_id);
        drop(conn);
        Ok(())
    }

    /// Change the text (or caption) of a held message — locally and in the
    /// bytes waiting to be sent.
    pub fn edit_held_text(&self, msg_id: String, text: String) -> Result<(), MessengerError> {
        let mut v = self.held_get(&msg_id).ok_or_else(|| MessengerError::InvalidArgument { description: "not scheduled".into() })?;
        let b64 = v.get("payload").and_then(|x| x.as_str()).unwrap_or("").to_string();
        if !b64.is_empty() {
            let bytes = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &b64)
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let mut m = alpha_messages::deserialize(&bytes).map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            m.set_user_text(&text);
            let out = alpha_messages::serialize(&m).map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            v["payload"] = serde_json::json!(base64::Engine::encode(&base64::engine::general_purpose::STANDARD, out));
            self.held_put(&msg_id, &v);
        }
        let conn = self.db.connection();
        let _ = MessageRepo::update_content(&conn, &msg_id, &text);
        Ok(())
    }

    /// Ghost mode: others see no "online", no "typing…" and no read ticks from
    /// us, while we still see theirs. Delivery acks and the read signal of
    /// "delete after reading" messages stay — without them messages would
    /// re-send or never disappear.
    pub fn set_ghost_mode(&self, enabled: bool) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        KeyValueRepo::set(&conn, KV_GHOST, if enabled { "1" } else { "0" })
            .map_err(|e| MessengerError::DatabaseError { description: format!("ghost: {}", e) })
    }

    pub fn ghost_mode(&self) -> bool {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, KV_GHOST).ok().flatten().as_deref() == Some("1")
    }

    /// Our row's own time for [msg_id] in ms: both sides then hold the same instant.
    fn row_sent_ms(&self, msg_id: &str) -> u64 {
        let conn = self.db.connection();
        MessageRepo::get(&conn, msg_id).ok().flatten()
            .and_then(|m| chrono::DateTime::parse_from_rfc3339(&m.created_at).ok())
            .map(|t| t.timestamp_millis().max(0) as u64)
            .unwrap_or_else(|| chrono::Utc::now().timestamp_millis().max(0) as u64)
    }

    fn receipts_allowed(&self) -> bool {
        self.read_receipts_enabled() && !self.ghost_mode()
    }

    /// Private mode: all core traffic through the in-app Tor. Applies at once.
    pub fn set_tor_enabled(&self, enabled: bool) -> Result<(), MessengerError> {
        {
            let conn = self.db.connection();
            KeyValueRepo::set(&conn, KV_TOR_PROXY, if enabled { TOR_DEFAULT_PROXY } else { "" })
                .map_err(|e| MessengerError::DatabaseError { description: format!("tor setting: {}", e) })?;
        }
        // Live: the switch closes the old tunnels, relays come back on the new route.
        alpha_transport::net_switch::set_tor(tor_route_for(enabled));
        let _ = self.force_reconnect();
        Ok(())
    }

    pub fn is_tor_enabled(&self) -> bool {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, KV_TOR_PROXY).ok().flatten().map(|v| !v.trim().is_empty()).unwrap_or(false)
    }

    /// Mark every incoming message in a chat as read AND clear the manual
    /// unread-override flag. Called when the user opens the chat or taps
    /// "Mark as read" in the long-press menu.
    pub fn mark_chat_read(&self, chat_id: String) -> Result<(), MessengerError> {
        self.mark_read_through(chat_id, None)
    }

    /// Read up to and including [message_id], the newest message the reader has seen:
    /// what came after it stays unread until it is on screen.
    pub fn mark_chat_read_through(&self, chat_id: String, message_id: String) -> Result<(), MessengerError> {
        self.mark_read_through(chat_id, Some(message_id))
    }

    fn mark_read_through(&self, chat_id: String, through: Option<String>) -> Result<(), MessengerError> {
        let conn = self.db.connection();

        // Collect the incoming messages we're about to mark read so we can
        // send a ReadSignal back per message (powers the sender's double
        // green check). Saved Messages has no peer — skip the roundtrip.
        // One receipt for the newest message covers everything before it (the
        // receiver marks earlier ones too). Per-message receipts flooded relays
        // and got the client rate-limit banned. "After read" messages still get
        // their own receipt: it drives their synchronized deletion.
        let to_ack: Vec<String> = if is_local_chat(&chat_id) {
            Vec::new()
        } else {
            let unread = MessageRepo::unread_incoming_ids(&conn, &chat_id, through.as_deref()).unwrap_or_default();
            let newest = unread.last().cloned();
            let mut ids: Vec<String> = unread.into_iter()
                .filter(|id| MessageRepo::get(&conn, id).ok().flatten()
                    .map(|m| m.expiry_policy == Some(alpha_database::models::ExpiryPolicyType::AfterRead))
                    .unwrap_or(false))
                .collect();
            if let Some(n) = newest {
                if !ids.contains(&n) { ids.push(n); }
            }
            ids
        };

        MessageRepo::mark_chat_read(&conn, &chat_id, through.as_deref())
            .map_err(|e| MessengerError::DatabaseError { description: format!("mark_chat_read: {}", e) })?;
        ChatRepo::set_unread_override(&conn, &chat_id, false)
            .map_err(|e| MessengerError::DatabaseError { description: format!("mark_chat_read: {}", e) })?;
        drop(conn);
        // Read here: read on our other phones too (receipts or not).
        if !to_ack.is_empty() || through.is_none() {
            self.device_note(serde_json::json!({ "op": "read", "chat": chat_id, "through": through.clone().unwrap_or_default() }));
        }

        // Emit read receipts (unless the user turned them off). Best-effort: a
        // failed send must not block the local read state.
        if !to_ack.is_empty() && !self.receipts_allowed() {
            let why = if self.ghost_mode() { "ghost_mode" } else { "receipts_off" };
            tracing::warn!("RECEIPT_SKIPPED chat={} reason={}", &chat_id[..chat_id.len().min(8)], why);
        }
        if !to_ack.is_empty() && self.receipts_allowed() && !self.is_channel_chat(&chat_id) && !self.is_request_chat(&chat_id) {
            let read_at = chrono::Utc::now().to_rfc3339();
            if let Ok(group_id) = hex::decode(&chat_id) {
                for message_id in &to_ack {
                    let read_signal = MessageBuilder::read_signal(message_id, &chat_id)
                        .read_at(&read_at)
                        .build();
                    let payload_bytes = match alpha_messages::serialize(&read_signal) {
                        Ok(b) => b,
                        Err(_) => continue,
                    };
                    let mut mls = match self.mls_session.lock() {
                        Ok(m) => m,
                        Err(_) => break,
                    };
                    let ciphertext = match mls.send(&group_id, &payload_bytes) {
                        Ok(c) => c,
                        Err(_) => { drop(mls); continue; }
                    };
                    let epoch = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
                    drop(mls);
                    let _ = self.publish_application_message(&group_id, epoch, ciphertext);
                }
                tracing::warn!("RECEIPT_SENT chat={} n={}", &chat_id[..chat_id.len().min(8)], to_ack.len());
            }
        }
        Ok(())
    }

    /// Toggle the manual "unread" badge. Use when the user wants to flag
    /// a chat for later even though all messages are technically read.
    pub fn mark_chat_unread(&self, chat_id: String) -> Result<(), MessengerError> {
        ChatRepo::set_unread_override(&self.db.connection(), &chat_id, true)
            .map_err(|e| MessengerError::DatabaseError { description: format!("mark_chat_unread: {}", e) })?;
        self.device_note(serde_json::json!({ "op": "unread", "chat": chat_id }));
        Ok(())
    }

    // --- Messaging ---

    /// Broadcast a transient typing / recording signal into a chat.
    ///
    /// `state` is "typing", "recording" or "paused". Fire-and-forget: the
    /// signal is never persisted and a failed send is swallowed (presence
    /// is best-effort by nature). Skipped for Saved Messages (no peer).
    /// Our typing / recording in a chat — or, with `thread`, in a channel
    /// post's discussion (a channel's feed itself never shows it).
    pub fn send_chat_state(&self, chat_id: String, state: String, thread: Option<String>) -> Result<(), MessengerError> {
        let thread = thread.filter(|t| !t.is_empty());
        if is_local_chat(&chat_id) || self.ghost_mode() || self.is_request_chat(&chat_id)
            || (thread.is_none() && self.is_channel_chat(&chat_id)) {
            return Ok(());
        }
        // A secret chat says nothing about us beyond its messages: no typing,
        // and no call (a call's media would go around Tor and show our address).
        if secret_chat::is_secret(&self.db.connection(), &chat_id) {
            return Ok(());
        }
        let msg = MessageBuilder::chat_state(&chat_id, &state, thread.as_deref());
        let payload_bytes = match alpha_messages::serialize(&msg) {
            Ok(b) => b,
            Err(_) => return Ok(()),
        };
        let group_id = match hex::decode(&chat_id) {
            Ok(g) => g,
            Err(_) => return Ok(()),
        };
        let mut mls = match self.mls_session.lock() {
            Ok(m) => m,
            Err(_) => return Ok(()),
        };
        let ciphertext = match mls.send(&group_id, &payload_bytes) {
            Ok(c) => c,
            Err(_) => { drop(mls); return Ok(()); }
        };
        let epoch = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
        drop(mls);
        let _ = self.publish_application_message(&group_id, epoch, ciphertext);
        Ok(())
    }

    /// A transient signal for one member of the chat (`to` = their member id,
    /// as chat states name senders). Everyone else drops it unseen.
    pub fn send_chat_signal(&self, chat_id: String, to: String, payload: String) -> Result<(), MessengerError> {
        if to.is_empty() || to.contains(':') {
            return Err(MessengerError::InvalidArgument { description: "bad recipient".to_string() });
        }
        self.send_chat_state(chat_id, format!("to:{}:{}", to, payload), None)
    }

    /// Announce our state in a room of this chat (e.g. a call); repeat within
    /// the room TTL to stay in. `state` is the app's JSON; "" leaves the room.
    pub fn room_announce(&self, chat_id: String, room: String, state: String) -> Result<(), MessengerError> {
        let room = crate::rooms::clean_room(&room);
        if room.is_empty() {
            return Err(MessengerError::InvalidArgument { description: "bad room".to_string() });
        }
        crate::rooms::set(&chat_id, &room, &self.own_sig_in(&chat_id), &state);
        self.send_chat_state(chat_id, format!("room:{}:{}", room, state), None)
    }

    /// Who is in a room now: `[{"member","state","age"}]` (ourselves included).
    pub fn room_members(&self, chat_id: String, room: String) -> String {
        crate::rooms::snapshot(&chat_id, &crate::rooms::clean_room(&room))
    }

    /// Our member id in this chat, as chat states name senders (a hidden
    /// owner's persona key where we own the chat hidden).
    pub fn own_member_id(&self, chat_id: String) -> String {
        self.own_sig_in(&chat_id)
    }

    /// How many are in a room now.
    pub fn room_count(&self, chat_id: String, room: String) -> u32 {
        crate::rooms::count(&chat_id, &crate::rooms::clean_room(&room))
    }

    /// Send a text message. `thread`: the channel post it comments on.
    pub fn send_text(&self, chat_id: String, body: String, thread: Option<String>) -> Result<String, MessengerError> {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: uuid::Uuid::new_v4().to_string(),
            body: body.clone(),
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: thread.filter(|t| !t.is_empty()),
        };
        let msg_id = msg.id().to_string();

        // Saved Messages: notes-to-self chat. SQLCipher already
        // encrypts at rest — there's nothing to gain from an MLS roundtrip
        // back to ourselves, and there's no peer to publish to.
        if is_local_chat(&chat_id) {
            self.persist_alpha_message(&chat_id, &msg, "self")?;
            return Ok(msg_id);
        }

        // Persist locally first (instant bubble), then queue for delivery.
        // Defer-encryption: MLS-encrypt + publish happen at flush time so an
        // offline compose isn't encrypted on stale state / lost.
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;

        Ok(msg_id)
    }

    /// Send a location as a media message. The `media_ref` column carries a JSON
    /// object {location:true, lat, lng, name, address}; the client renders a map
    /// card from it (no blob upload — the mini-map is drawn from the coords).
    pub fn send_location(
        &self,
        chat_id: String,
        lat: f64,
        lng: f64,
        name: String,
        address: String,
        thread: Option<String>,
    ) -> Result<String, MessengerError> {
        let ref_json = serde_json::json!({
            "location": true,
            "lat": lat,
            "lng": lng,
            "name": name,
            "address": address,
            "media_type": "location",
        }).to_string();
        let msg = MessageBuilder::media(&ref_json, "location", "location", 0).build().in_thread(thread);
        let msg_id = msg.id().to_string();
        if is_local_chat(&chat_id) {
            self.persist_alpha_message(&chat_id, &msg, "self")?;
            return Ok(msg_id);
        }
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;
        Ok(msg_id)
    }

    /// Send a link-preview card as a media message. `media_ref` carries a JSON
    /// object {link:true, url, title, description, image, site}. Optional
    /// `caption` is the user's accompanying text. Client renders a rich card.
    pub fn send_link_preview(
        &self,
        chat_id: String,
        url: String,
        title: String,
        description: String,
        image: String,
        site: String,
        caption: Option<String>,
        thread: Option<String>,
        image_ref: Option<String>,
        video: bool,
    ) -> Result<String, MessengerError> {
        // `image` is a small inline copy (shown at once); `image_ref` the full
        // picture as an encrypted blob (fetched from our storage, never the site).
        let mut ref_json = serde_json::json!({
            "link": true,
            "url": url,
            "title": title,
            "description": description,
            "image": image,
            "site": site,
            "media_type": "link",
        });
        if let Some(r) = image_ref.filter(|r| !r.is_empty() && r.len() <= 8192) { ref_json["image_ref"] = serde_json::Value::String(r); }
        if video { ref_json["video"] = serde_json::Value::Bool(true); }
        let ref_json = ref_json.to_string();
        let mut builder = MessageBuilder::media(&ref_json, "link", "link", 0);
        if let Some(cap) = &caption {
            if !cap.is_empty() { builder = builder.caption(cap); }
        }
        let msg = builder.build().in_thread(thread);
        let msg_id = msg.id().to_string();
        if is_local_chat(&chat_id) {
            self.persist_alpha_message(&chat_id, &msg, "self")?;
            return Ok(msg_id);
        }
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;
        Ok(msg_id)
    }

    /// Send a poll (a media message whose ref carries it). [poll_json]:
    /// {"q", "o": [options], "anon", "multi", "quiz", "right", "expl", "close_at"};
    /// it is checked and normalized here. `thread`: the channel post it comments on.
    pub fn send_poll(&self, chat_id: String, poll_json: String, thread: Option<String>) -> Result<String, MessengerError> {
        let bad = |d: &str| MessengerError::InvalidArgument { description: format!("poll: {}", d) };
        let v: serde_json::Value = serde_json::from_str(&poll_json).map_err(|_| bad("not JSON"))?;
        let q = v.get("q").and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
        if q.is_empty() || q.chars().count() > 300 { return Err(bad("question")); }
        let opts: Vec<String> = v.get("o").and_then(|x| x.as_array()).map(|a| a.iter()
            .filter_map(|x| x.as_str()).map(|x| x.trim().chars().take(100).collect::<String>())
            .filter(|x| !x.is_empty()).collect()).unwrap_or_default();
        if !(2..=10).contains(&opts.len()) { return Err(bad("options")); }
        let quiz = v.get("quiz").and_then(|x| x.as_bool()).unwrap_or(false);
        let right = v.get("right").and_then(|x| x.as_u64()).unwrap_or(0);
        if quiz && right as usize >= opts.len() { return Err(bad("right answer")); }
        let close_at = v.get("close_at").and_then(|x| x.as_i64()).unwrap_or(0).max(0);
        let ref_json = serde_json::json!({
            "poll": true,
            "media_type": "poll",
            "q": q,
            "o": opts,
            "anon": v.get("anon").and_then(|x| x.as_bool()).unwrap_or(true),
            "multi": !quiz && v.get("multi").and_then(|x| x.as_bool()).unwrap_or(false),
            "quiz": quiz,
            "right": if quiz { right } else { 0 },
            "expl": v.get("expl").and_then(|x| x.as_str()).unwrap_or("").trim().chars().take(200).collect::<String>(),
            "close_at": close_at,
        }).to_string();
        let msg = MessageBuilder::media(&ref_json, "poll", "poll", 0).build().in_thread(thread);
        let msg_id = msg.id().to_string();
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        if is_local_chat(&chat_id) { return Ok(msg_id); }
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;
        Ok(msg_id)
    }

    /// Our vote in a poll (empty = withdraw it): kept here, then sent.
    pub fn poll_vote(&self, chat_id: String, poll_id: String, options: Vec<u32>) -> Result<(), MessengerError> {
        let now = chrono::Utc::now().timestamp();
        {
            let conn = self.db.connection();
            let spec = MessageRepo::get(&conn, &poll_id).ok().flatten()
                .filter(|m| m.chat_id == chat_id)
                .and_then(|m| m.media_ref.as_deref().and_then(alpha_database::polls::PollSpec::from_ref))
                .ok_or_else(|| MessengerError::InvalidArgument { description: "no such poll".to_string() })?;
            if !alpha_database::polls::vote(&conn, &chat_id, &poll_id, &spec, "self", &options, now)
                .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })? {
                return Err(MessengerError::InvalidArgument { description: "vote not accepted".to_string() });
            }
        }
        if is_local_chat(&chat_id) { return Ok(()); }
        let msg = AlphaMessage::PollVote { poll_id: poll_id.clone(), options, ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg).map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("pvote-{}-{}", poll_id, now))
    }

    /// Close a poll we wrote (or, as an admin, any poll of the group).
    pub fn poll_close(&self, chat_id: String, poll_id: String) -> Result<(), MessengerError> {
        let now = chrono::Utc::now().timestamp();
        let own_hex = self.own_hex_in(&chat_id);
        let tally = {
            let conn = self.db.connection();
            let poll = MessageRepo::get(&conn, &poll_id).ok().flatten().filter(|m| m.chat_id == chat_id)
                .ok_or_else(|| MessengerError::InvalidArgument { description: "no such poll".to_string() })?;
            let spec = poll.media_ref.as_deref().and_then(alpha_database::polls::PollSpec::from_ref)
                .ok_or_else(|| MessengerError::InvalidArgument { description: "not a poll".to_string() })?;
            let admin = alpha_messages::can_manage_group(&alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex));
            if poll.sender_id != "self" && !admin {
                return Err(MessengerError::InvalidArgument { description: "only its author or an admin closes a poll".to_string() });
            }
            let r = alpha_database::polls::results(&conn, &chat_id, &poll_id, &spec)
                .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
            alpha_database::polls::close(&conn, &chat_id, &poll_id, now, &r.counts)
                .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
            r.counts
        };
        if is_local_chat(&chat_id) { return Ok(()); }
        let msg = AlphaMessage::PollClose { poll_id: poll_id.clone(), tally, ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg).map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("pclose-{}", poll_id))
    }

    /// A poll's results as the screen needs them:
    /// {"counts": [..], "voters": n, "closed": b, "mine": [..], "by": [{"v": voter, "o": [..]}]}
    /// ("by" is empty for anonymous polls).
    pub fn poll_results(&self, chat_id: String, poll_id: String) -> String {
        let conn = self.db.connection();
        let Some(spec) = MessageRepo::get(&conn, &poll_id).ok().flatten()
            .filter(|m| m.chat_id == chat_id)
            .and_then(|m| m.media_ref.as_deref().and_then(alpha_database::polls::PollSpec::from_ref)) else { return String::new() };
        let Ok(r) = alpha_database::polls::results(&conn, &chat_id, &poll_id, &spec) else { return String::new() };
        let mine = alpha_database::polls::choice_of(&conn, &chat_id, &poll_id, "self").unwrap_or_default();
        let closed = r.closed || (spec.close_at > 0 && chrono::Utc::now().timestamp() > spec.close_at);
        serde_json::json!({
            "counts": r.counts, "voters": r.voters, "closed": closed, "mine": mine,
            "by": r.by.iter().map(|(v, o)| serde_json::json!({"v": v, "o": o})).collect::<Vec<_>>(),
        }).to_string()
    }

    /// Send a reply message. `thread`: the channel post it comments on.
    pub fn send_reply(&self, chat_id: String, body: String, reply_to: String, quoted_text: String, thread: Option<String>) -> Result<String, MessengerError> {
        let msg = AlphaMessage::Reply { sent_ms: 0, silent: false,
            id: uuid::Uuid::new_v4().to_string(),
            body: body.clone(),
            reply_to: reply_to.clone(),
            quoted_text: quoted_text.clone(),
            seq: 0,
            thread: thread.filter(|t| !t.is_empty()),
        };
        let msg_id = msg.id().to_string();

        // Persist locally, then queue (defer-encryption — see queue_and_send).
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;

        Ok(msg_id)
    }

    /// Send an edit message.
    pub fn send_edit(
        &self,
        chat_id: String,
        message_id: String,
        new_body: String,
    ) -> Result<String, MessengerError> {
        let msg = AlphaMessage::Edit {
            id: message_id.clone(),
            body: new_body.clone(),
            edited_at: chrono::Utc::now().to_rfc3339(),
            seq: 0,
        };
        let msg_id = msg.id().to_string();

        // Persist locally, then queue (defer-encryption — see queue_and_send).
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;

        Ok(msg_id)
    }

    /// Send a reaction.
    pub fn send_reaction(&self, chat_id: String, target_id: String, emoji: String, remove: bool) -> Result<String, MessengerError> {
        if !remove {
            let conn = self.db.connection();
            if !alpha_database::group_roles::reaction_allowed(&conn, &chat_id, &emoji) {
                return Err(MessengerError::InvalidArgument { description: "реакция запрещена в этой группе".to_string() });
            }
        }
        let msg = AlphaMessage::Reaction {
            id: uuid::Uuid::new_v4().to_string(),
            target_id: target_id.clone(),
            emoji: emoji.clone(),
            remove: if remove { Some(true) } else { None },
            seq: 0,
        };
        let msg_id = msg.id().to_string();

        // Persist locally, then queue (defer-encryption — see queue_and_send).
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;

        Ok(msg_id)
    }

    /// Send a delete (tombstone).
    pub fn send_delete(&self, chat_id: String, target_id: String) -> Result<String, MessengerError> {
        let msg = AlphaMessage::Delete {
            id: uuid::Uuid::new_v4().to_string(),
            target_id: target_id.clone(),
            seq: 0,
        };
        let msg_id = msg.id().to_string();

        // Persist locally, then queue (defer-encryption — see queue_and_send).
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;

        Ok(msg_id)
    }

    /// Delete a message **locally only** ("Delete for me") — marks the row
    /// as deleted so the bubble disappears, without broadcasting anything
    /// to peers. For "Delete for everyone" use `send_delete`.
    pub fn delete_message_local(&self, message_id: String) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        alpha_database::MessageRepo::mark_deleted(&conn, &message_id)
            .map_err(|e| MessengerError::DatabaseError { description: format!("delete local: {}", e) })?;
        Ok(())
    }


    /// Send a disappearing message (legacy).
    pub fn send_disappearing(
        &self,
        chat_id: String,
        body: String,
        ttl_seconds: u32,
    ) -> Result<String, MessengerError> {
        let msg = AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: uuid::Uuid::new_v4().to_string(),
            body: body.clone(),
            ttl_seconds,
            seq: 0,
        };
        let msg_id = msg.id().to_string();

        // Persist locally, then queue (defer-encryption — see queue_and_send).
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;

        Ok(msg_id)
    }

    // --- Ephemeral messages ---

    /// Send an ephemeral text message with the specified expiry policy.
    ///
    /// `expiry_mode` is one of: "after_send", "after_read", "view_once".
    /// `ttl_secs` is required for after_send and after_read (ignored for view_once).
    pub fn send_ephemeral_text(
        &self,
        chat_id: String,
        body: String,
        expiry_mode: String,
        ttl_secs: u32,
    ) -> Result<String, MessengerError> {
        let expiry = match expiry_mode.as_str() {
            "after_send" => AlphaExpiryPolicy::AfterSend { ttl_secs },
            "after_read" => AlphaExpiryPolicy::AfterRead { ttl_secs },
            "view_once" => AlphaExpiryPolicy::ViewOnce,
            "whisper" => AlphaExpiryPolicy::Whisper { secs: ttl_secs, glance: false },
            "glance" => AlphaExpiryPolicy::Whisper { secs: ttl_secs, glance: true },
            _ => return Err(MessengerError::InvalidArgument { description: format!("invalid expiry_mode: '{}' (must be after_send, after_read, view_once, whisper or glance)", expiry_mode) }),
        };

        // Validate the policy
        alpha_messages::validate_expiry_policy(&expiry)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid expiry policy: {}", e) })?;

        let whisper = matches!(expiry, AlphaExpiryPolicy::Whisper { .. });
        let msg = MessageBuilder::text(&body)
            .expiry(expiry)
            .build();
        let msg_id = msg.id().to_string();

        // Persist locally, then queue (defer-encryption — see queue_and_send).
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        if whisper {
            let conn = self.db.connection();
            let _ = alpha_database::whispers::put_outgoing(&conn, &msg_id, &chat_id, "text", ttl_secs, false, &chrono::Utc::now().to_rfc3339());
        }
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;

        Ok(msg_id)
    }


    /// Optimistic send. Persists the media message IMMEDIATELY with
    /// a local-pending media_ref (carries the device file path) so the bubble
    /// shows the local file right away, no upload wait. Returns the message id;
    /// the caller then runs [`upload_pending_media`] in the background.
    pub fn persist_outgoing_media(
        &self,
        chat_id: String,
        file_path: String,
        media_type: String,
        caption: Option<String>,
        ttl_secs: u32,
        thread: Option<String>,
    ) -> Result<String, MessengerError> {
        let file_data = std::fs::read(&file_path)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("read file: {}", e) })?;
        let size_bytes = file_data.len() as u64;
        let filename = std::path::Path::new(&file_path)
            .file_name().map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "file".to_string());
        let pending_ref = serde_json::json!({
            "local_path": file_path,
            "pending": true,
            "media_type": media_type,
            "filename": filename,
            "original_size": size_bytes,
        }).to_string();
        let mut builder = MessageBuilder::media(&pending_ref, &media_type, &filename, size_bytes);
        if let Some(cap) = &caption {
            if !cap.is_empty() { builder = builder.caption(cap); }
        }
        // Chat timer: counts from reading, on both sides (see `Disappearing`).
        // The publish takes the expiry from this stored row.
        if ttl_secs > 0 {
            builder = builder.expiry(AlphaExpiryPolicy::AfterRead { ttl_secs });
        }
        let msg = builder.build().in_thread(thread);
        let msg_id = msg.id().to_string();
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        // Scheduled: hold it NOW, before the upload can publish it.
        if let Some(nb) = take_armed(&chat_id) {
            let _ = self.hold_message(msg_id.clone(), chat_id.clone(), nb);
        }
        Ok(msg_id)
    }

    /// Persist a local outgoing media echo DIRECTLY from an existing MediaRef
    /// (forward / reuse). The message carries the final ref verbatim, so the bubble
    /// renders its FINAL content (filename, poster, size, media_type) from the first
    /// frame; the later `broadcast_media` writes the SAME ref → nothing rebuilds, only
    /// the delivery status advances (clock → check). No file read — size and filename
    /// travel inside the ref. This is the single-source-of-truth echo the forward path
    /// needs (vs `persist_outgoing_media`, which derives a placeholder ref from a path).
    pub fn persist_outgoing_media_ref(
        &self,
        chat_id: String,
        mls_ref: String,
        media_type: String,
        caption: Option<String>,
        ttl_secs: u32,
        thread: Option<String>,
    ) -> Result<String, MessengerError> {
        let v: serde_json::Value = serde_json::from_str(&mls_ref)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("bad mls_ref: {}", e) })?;
        let filename = v.get("filename").and_then(|x| x.as_str()).unwrap_or("file").to_string();
        let size_bytes = v.get("original_size").and_then(|x| x.as_u64()).unwrap_or(0);
        let mut builder = MessageBuilder::media(&mls_ref, &media_type, &filename, size_bytes);
        if let Some(cap) = &caption {
            if !cap.is_empty() { builder = builder.caption(cap); }
        }
        if ttl_secs > 0 {
            builder = builder.expiry(AlphaExpiryPolicy::AfterRead { ttl_secs });
        }
        let msg = builder.build().in_thread(thread);
        let msg_id = msg.id().to_string();
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        // Scheduled: hold it NOW, before the upload can publish it.
        if let Some(nb) = take_armed(&chat_id) {
            let _ = self.hold_message(msg_id.clone(), chat_id.clone(), nb);
        }
        Ok(msg_id)
    }

    pub fn set_media_reply(&self, msg_id: String, reply_to: String) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        MessageRepo::set_reply_to(&conn, &msg_id, &reply_to)
            .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
        Ok(())
    }

    /// The message an outgoing media row answers — it rides on the wire so the peer quotes it too.
    fn reply_of(&self, msg_id: &str) -> Option<String> {
        let conn = self.db.connection();
        MessageRepo::get(&conn, msg_id).ok().flatten().and_then(|m| m.reply_to).filter(|r| !r.is_empty())
    }

    /// PROTOCOL v2 (Media Manifest) — externalize a large ref's chunk list into an
    /// encrypted manifest blob and return a SLIM ref (`format_version=2` +
    /// `manifest_url`/`manifest_hash`). Small refs pass through unchanged. The
    /// manifest IS the serialized FULL `MediaRef` (single source of truth),
    /// encrypted with the media key and uploaded as one blob — so the Nostr event
    /// size becomes independent of the file size / chunk count.
    fn externalize_ref_if_large(&self, media_ref: &MediaRef) -> Result<MediaRef, MessengerError> {
        use sha2::Digest;
        // NEVER re-externalize an ALREADY-slim external-manifest ref. Wrapping it in a
        // new manifest would store the SLIM ref (chunks=None, blob_urls=[]) inside; the
        // receiver's resolve_manifest does NOT recurse, so it would hand the downloader a
        // chunk-less ref → "No chunk URLs provided". This is exactly what broke forwarding
        // a received large-media ref whose JSON (big poster_b64) exceeds the inline cap:
        // it points at its manifest already — pass it through untouched.
        if media_ref.is_external_manifest() {
            return Ok(media_ref.clone());
        }
        // DescriptorStrategy (auto-by-size today; pluggable by chunk-count / type /
        // server caps later): above this serialized size the ref goes external.
        const MAX_INLINE_REF_SIZE: usize = 16 * 1024;
        let full_json = serde_json::to_string(media_ref)
            .map_err(|e| MessengerError::InternalError { description: format!("manifest serialize: {e}") })?;
        if full_json.len() <= MAX_INLINE_REF_SIZE {
            return Ok(media_ref.clone());
        }
        let key: [u8; 32] = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &media_ref.key)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("manifest: bad key: {e}") })?
            .try_into().map_err(|_| MessengerError::InvalidArgument { description: "manifest: key not 32 bytes".into() })?;
        let enc = alpha_media::crypto::MediaCrypto::encrypt(full_json.as_bytes(), &key)
            .map_err(|e| MessengerError::InternalError { description: format!("manifest encrypt: {e}") })?;
        // Stable blob layout: nonce(24) || plaintext_hash(32) || orig_size(8 LE) || ciphertext.
        let mut blob = Vec::with_capacity(64 + enc.ciphertext.len());
        blob.extend_from_slice(&enc.nonce);
        blob.extend_from_slice(&enc.hash);
        blob.extend_from_slice(&enc.original_size.to_le_bytes());
        blob.extend_from_slice(&enc.ciphertext);
        let manifest_hash = hex::encode(sha2::Sha256::digest(&blob));
        let signer = self.media_signer();
        let rt = runtime();
        let descriptor = rt.block_on(self.blossom_for(media_ref.tor).upload(&blob, &signer, "application/octet-stream"))
            .map_err(|e| MessengerError::NetworkError { description: format!("manifest upload: {e}") })?;
        // Size breakdown (TEMP diag): which inline lists dominate the wire ref.
        let chunks_len = media_ref.chunks.as_ref()
            .and_then(|c| serde_json::to_string(c).ok()).map(|s| s.len()).unwrap_or(0);
        let blobs_len = serde_json::to_string(&media_ref.blob_urls).map(|s| s.len()).unwrap_or(0);
        let thumbs_len = media_ref.thumbnail_urls.as_ref()
            .and_then(|t| serde_json::to_string(t).ok()).map(|s| s.len()).unwrap_or(0);
        let mut slim = media_ref.clone();
        // Strip EVERY per-chunk inline list — chunks, the per-chunk blob_urls (one
        // URL PER CHUNK: 1921 for a 480 MB video), and the thumbnail chunk URLs.
        // Leaving any of them inline scales the event with the file and defeats the
        // manifest. All live in the manifest's full ref; the receiver restores them
        // on resolve. Instant preview rides on the inline `poster_b64` instead.
        slim.chunks = None;
        slim.blob_urls = Vec::new();
        slim.thumbnail_urls = None;
        slim.manifest_url = Some(descriptor.url);
        slim.manifest_hash = Some(manifest_hash);
        slim.format_version = Some(2);
        let slim_len = serde_json::to_string(&slim).map(|s| s.len()).unwrap_or(0);
        tracing::info!(target: "alpha_ffi", "MANIFEST externalized: full={}B -> slim={}B (stripped chunks={}B blob_urls={}B thumb_urls={}B)", full_json.len(), slim_len, chunks_len, blobs_len, thumbs_len);
        Ok(slim)
    }

    /// PROTOCOL v2 — receiver side. If `media_ref` is a slim external-manifest ref,
    /// fetch + verify (sha256) + decrypt the manifest blob and return the FULL ref
    /// (with chunks). Otherwise return the ref unchanged (inline / legacy).
    fn resolve_manifest(&self, media_ref: &MediaRef) -> Result<MediaRef, MessengerError> {
        use sha2::Digest;
        let rt = runtime();
        // RECURSE through nested manifests. PROVEN on device (MFDIAG): a manifest blob
        // can itself contain another slim external-manifest ref (M2 → M1 → … → full),
        // so a single resolve stops at an inner manifest with chunks=0 → the downloader
        // gets a chunk-less ref → "No chunk URLs provided". Keep resolving until the
        // inline/legacy ref that actually carries the chunk list; depth-capped so a
        // malformed cycle can't spin forever. Heals both new sends and already-nested
        // refs left by earlier forwards.
        let mut current = media_ref.clone();
        let mut depth = 0;
        while current.is_external_manifest() {
            if depth >= 6 {
                return Err(MessengerError::InternalError { description: "manifest nesting too deep".into() });
            }
            depth += 1;
            let url = current.manifest_url.clone().unwrap_or_default();
            let key: [u8; 32] = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &current.key)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("manifest: bad key: {e}") })?
                .try_into().map_err(|_| MessengerError::InvalidArgument { description: "manifest: key not 32 bytes".into() })?;
            let t_dl = std::time::Instant::now();
            // The manifest is a content-addressed object like a chunk, and its
            // identity is ALREADY in the ref: `manifest_hash` is the SHA-256 of
            // exactly the bytes uploaded (see externalize_ref_if_large).
            // So the loss of one server does not make the whole file
            // unavailable — a copy can be found on any of our storages. Without
            // this step chunk discovery is useless: there is no way to reach
            // the chunk list.
            let blob = match rt.block_on(self.blossom_for(media_ref.tor).download(&url)) {
                Ok(b) => b,
                Err(primary) => {
                    let recovered = current.manifest_hash.as_deref().and_then(|id| {
                        rt.block_on(self.blossom_for(media_ref.tor).discover_by_content_id(id)).ok()
                    });
                    match recovered {
                        Some(b) => {
                            tracing::info!(target: "alpha_ffi", "MANIFEST_DISCOVER recovered by content-id");
                            b
                        }
                        None => return Err(MessengerError::NetworkError {
                            description: format!("manifest download: {primary}"),
                        }),
                    }
                }
            };
            tracing::info!(target: "alpha_ffi", "STAGE_MANIFEST depth={} download={}ms size={}", depth, t_dl.elapsed().as_millis(), blob.len());
            if let Some(expected) = &current.manifest_hash {
                let got = hex::encode(sha2::Sha256::digest(&blob));
                if &got != expected {
                    return Err(MessengerError::InternalError { description: "manifest hash mismatch".into() });
                }
            }
            if blob.len() < 64 {
                return Err(MessengerError::InternalError { description: "manifest blob too short".into() });
            }
            let mut nonce = [0u8; 24]; nonce.copy_from_slice(&blob[0..24]);
            let mut phash = [0u8; 32]; phash.copy_from_slice(&blob[24..56]);
            let mut szb = [0u8; 8]; szb.copy_from_slice(&blob[56..64]);
            let enc = alpha_media::crypto::EncryptedMedia {
                nonce, hash: phash, original_size: u64::from_le_bytes(szb), ciphertext: blob[64..].to_vec(),
            };
            let plain = alpha_media::crypto::MediaCrypto::decrypt(&enc, &key)
                .map_err(|e| MessengerError::InternalError { description: format!("manifest decrypt: {e}") })?;
            current = serde_json::from_slice(&plain)
                .map_err(|e| MessengerError::InternalError { description: format!("manifest parse: {e}") })?;
        }
        // The route belongs to the message, whatever the manifest says.
        current.tor = current.tor || media_ref.tor;
        Ok(current)
    }

    /// Fast-batch phase B — broadcast an already-uploaded media ref with a
    /// CONFIRMED publish (awaits relay acceptance instead of fire-and-forget).
    ///
    /// Call these sequentially for a batch: each returns only after the relay
    /// accepted the event, so nothing is dropped and message order is exact.
    /// `mls_ref` is the value returned by [`upload_media_blob_only`].
    ///
    /// This is the SINGLE media publish API: `expiry_mode` (+ `ttl_secs`) merge
    /// the old dedicated ephemeral-media path in. `expiry_mode` is one of
    /// "after_send" | "after_read" | "view_once" (or "none"/null for a normal,
    /// non-expiring send); `ttl_secs` is the lifetime for the timed modes.
    pub fn broadcast_media(
        &self,
        chat_id: String,
        msg_id: String,
        mls_ref: String,
        media_type: String,
        caption: Option<String>,
        expiry_mode: Option<String>,
        ttl_secs: Option<u32>,
    ) -> Result<(), MessengerError> {
        if is_upload_cancelled(&msg_id) {
            let conn = self.db.connection();
            let _ = PendingMediaRepo::delete_by_msg(&conn, &msg_id);
            let _ = alpha_database::MessageRepo::delete(&conn, &msg_id);
            drop(conn);
            clear_upload_cancel(&msg_id);
            return Err(MessengerError::InvalidArgument { description: "upload cancelled".into() });
        }
        // --- ONE publish pipeline for every caller (fresh upload,
        //     forward/reuse, resend); filename + size travel inside the ref ---
        let v: serde_json::Value = serde_json::from_str(&mls_ref)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("bad mls_ref: {}", e) })?;
        let filename = v.get("filename").and_then(|x| x.as_str()).unwrap_or("file").to_string();
        let size_bytes = v.get("original_size").and_then(|x| x.as_u64()).unwrap_or(0);

        // WIRE ref: externalize a large inline chunk list into a manifest blob so the
        // MLS event stays tiny regardless of file size — the SAME treatment the album
        // path already uses. Idempotent (≤16 KB refs pass through unchanged). Strip the
        // sender-only local_path from the wire. This is what makes a forward that reuses
        // a big ref (200+ chunks) publish instantly instead of choking the relay.
        let (wire_ref, local_ref): (String, String) = match serde_json::from_value::<MediaRef>(v.clone()) {
            Ok(mr) => {
                let slim = self.externalize_ref_if_large(&mr)?;
                let wv = serde_json::to_value(&slim)
                    .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
                (strip_local_path(&wv), local_copy_of(&wv, &v, slim.is_external_manifest() && !mr.is_external_manifest())
                    .unwrap_or_else(|| mls_ref.clone()))
            }
            Err(_) => (mls_ref.clone(), mls_ref.clone()),
        };

        // Saved Messages (self-chat): finalize the LOCAL ref (full, with local_path) and
        // SKIP the network publish — same pipeline, one exception (no peers). Without the
        // local write the message would stay optimistic-pending forever.
        if is_local_chat(&chat_id) {
            let conn = self.db.connection();
            let _ = MessageRepo::update_media_ref(&conn, &msg_id, &local_ref);
            drop(conn);
            self.clear_send_state(&msg_id, "сохранено у себя");
            return Ok(());
        }

        let wire_ref = self.mark_no_store(&chat_id, &msg_id, &mls_ref, &wire_ref);
        let mut builder = MessageBuilder::media(&wire_ref, &media_type, &filename, size_bytes).id(&msg_id);
        if let Some(r) = self.reply_of(&msg_id) { builder = builder.reply_to(&r); }
        if let Some(cap) = &caption {
            if !cap.is_empty() { builder = builder.caption(cap); }
        }
        // A message marked a whisper goes out as one on EVERY path — the first
        // publish and any retry from the queue (which only remembers a ttl):
        // a whisper photo must never leave as an ordinary photo.
        let whisper_mode = {
            let conn = self.db.connection();
            alpha_database::whispers::outgoing_mode(&conn, &msg_id)
        };
        // Called without an expiry, it still goes out with the one the message
        // was stored with (a chat timer): no path may send it without one.
        let expiry_mode = expiry_mode.or_else(|| {
            let conn = self.db.connection();
            let m = MessageRepo::get(&conn, &msg_id).ok().flatten()?;
            match (m.expiry_policy?, m.ttl_seconds) {
                (alpha_database::models::ExpiryPolicyType::AfterRead, Some(_)) => Some("after_read".to_string()),
                (alpha_database::models::ExpiryPolicyType::AfterSend, Some(_)) => Some("after_send".to_string()),
                _ => None,
            }
        });
        let ttl_secs = ttl_secs.or_else(|| {
            let conn = self.db.connection();
            MessageRepo::get(&conn, &msg_id).ok().flatten()?.ttl_seconds.map(|t| t as u32)
        });
        if let Some((secs, glance)) = whisper_mode {
            builder = builder.expiry(AlphaExpiryPolicy::Whisper { secs, glance });
            // Its file leaves every storage server once it is seen (or at the
            // latest when unopened whispers burn).
            let _ = self.register_media_purge(msg_id.clone(), mls_ref.clone());
        } else
        // Merged ephemeral-media path: attach the expiry policy when requested.
        // Absent / "none" ⇒ a normal, non-expiring media message.
        if let Some(mode) = expiry_mode.as_deref() {
            let ttl = ttl_secs.unwrap_or(0);
            let expiry = match mode {
                "none" | "" => None,
                "after_send" => Some(AlphaExpiryPolicy::AfterSend { ttl_secs: ttl }),
                "after_read" => Some(AlphaExpiryPolicy::AfterRead { ttl_secs: ttl }),
                "view_once" => Some(AlphaExpiryPolicy::ViewOnce),
                "whisper" => Some(AlphaExpiryPolicy::Whisper { secs: ttl, glance: false }),
                "glance" => Some(AlphaExpiryPolicy::Whisper { secs: ttl, glance: true }),
                other => return Err(MessengerError::InvalidArgument {
                    description: format!("invalid expiry_mode: '{}' (must be after_send, after_read, view_once, whisper, glance or none)", other),
                }),
            };
            if let Some(policy) = expiry {
                alpha_messages::validate_expiry_policy(&policy)
                    .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid expiry policy: {}", e) })?;
                builder = builder.expiry(policy);
            }
        }
        let mut msg = builder.build().in_thread(self.thread_of_message(&msg_id));
        if self.row_silent(&msg_id) { msg.set_silent(true); }
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        // Scheduled media: uploaded now, published at its time.
        if self.hold_if_scheduled(&chat_id, &msg_id, &payload_bytes, "direct") {
            let conn = self.db.connection();
            let _ = MessageRepo::update_media_ref(&conn, &msg_id, &local_ref);
            return Ok(());
        }
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let ciphertext = mls
            .send(&group_id, &payload_bytes)
            .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?;
        let epoch = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
        drop(mls);
        let published = self.publish_application_message_confirmed(&group_id, epoch, ciphertext);
        // Clear the optimistic "pending:true" ref — the finalized ref is now
        // published. Mirrors send_album (core.rs:5466) and the legacy upload
        // paths (4978/5083/5234). The mls_ref already carries local_path (set
        // in upload_blob_ref_only:5373), so the sender's bubble still renders
        // from disk. Without this, list_chats sees "pending":true forever and
        // the chat-list preview is stuck on 🕐 (audit UI_AUDIT_2 Block 3).
        {
            let conn = self.db.connection();
            let _ = MessageRepo::update_media_ref(&conn, &msg_id, &local_ref);
        }
        if published.is_ok() {
            self.clear_send_state(&msg_id, "опубликовано");
        }
        published
    }

    // --- Album send (ONE MLS message for a whole photo/video batch) ---
    //
 // The correct model: upload every blob, then send a
    // SINGLE MLS application message whose media_ref is an "album manifest"
    // carrying all item refs. One event per album → no relay rate-limit drops,
    // exact order, atomic. Reuses MessageType::MEDIA so the receiver/event
    // pipeline is unchanged — only the ref JSON is richer.

    /// Upload ONE blob and return its ref JSON (no DB write, no MLS). Designed
    /// to be called in parallel for every album item. The returned ref carries
    /// the blob locator + media_type + filename (+ poster for video, + the
    /// sender's local_path so the sender's bubble renders without downloading).
    /// Encryption key that SURVIVES send retries.
    ///
    /// Chunked resume is only possible when a retry yields the SAME bytes:
    /// otherwise the chunk has a different identity and cannot be recognized
    /// as the object the server already accepted. Deriving the key inside the
    /// upload on every call made every outage cost the whole file.
    ///
    /// What forms the key's identity:
    ///  * `op_id` — this send and this element of it;
    ///  * the PREPARED-OUTPUT REVISION — the existing "source + edits + output
    ///    profile version" fingerprint. The app computes it when queueing and
    ///    it sits in the output file name: `<index>.r<rev>`.
    ///    A second fingerprint is NOT computed here: two independent "same job?"
    ///    formulas would eventually diverge — silently.
    ///
    /// What the identity does NOT include: the file modification time. It
    /// changes on things that don't change content (copying, restore, chmod)
    /// and stays put where content may change — it lies in both directions as
    /// an edit marker. Size is kept, not as identity but as a coarse check:
    /// if a found record's size differs, it is a different file.
    ///
    /// If the revision cannot be determined (foreign file name) the key is
    /// neither saved nor looked up: better to send whole than apply a foreign key.
    ///
    /// Storage is the encrypted table originally built for media replication;
    /// no second key store is introduced.
    ///
    /// Also returns whether the file goes up as written: true once chunks of it were
    /// sent while it was encoded. Kept in the key record, so every retry decides alike.
    fn stable_media_key(&self, op_id: &str, file_path: &str) -> ([u8; 32], bool) {
        let revision = match output_revision(file_path) {
            Some(r) => r,
            None => {
                tracing::info!(
                    target: "alpha_ffi",
                    "MEDIA_KEY ревизия не определена по имени файла — ключ разовый",
                );
                return (*alpha_media::generate_key(), false);
            }
        };
        let size = std::fs::metadata(file_path).map(|m| m.len()).unwrap_or(0);
        // The key is reused only for the very same bytes: chunk nonces depend on
        // (key, index) alone, so other bytes under the same key would reuse the
        // keystream. The stored record is key ‖ BLAKE3 of the output.
        let content = match file_blake3(file_path) {
            Some(h) => h,
            None => return (*alpha_media::generate_key(), false),
        };

        let stored = {
            let conn = self.db.connection();
            alpha_database::MediaSendKeyRepo::load(&conn, op_id, 0, revision).ok().flatten()
        };
        // Checked with no DB connection held: the checkpoint read takes its own,
        // and the file pass (re-encrypting every early chunk) must not hold the lock.
        if let Some(k) = stored.as_ref().filter(|k| k.len() == 64 && k[32..] == EARLY_KEY_MARK) {
            if let Ok(arr) = <[u8; 32]>::try_from(&k[..32]) {
                let early = self.checkpoint_for(op_id, file_path)
                    .map(|c| alpha_media::resume::ChunkCheckpoint::load(&c)).unwrap_or_default();
                let path = std::path::Path::new(file_path);
                if alpha_media::growing::verify_confirmed(path, &arr, &early) {
                    let mut record = arr.to_vec();
                    record.extend_from_slice(&content);
                    record.push(AS_WRITTEN);
                    let conn = self.db.connection();
                    let _ = alpha_database::MediaSendKeyRepo::store(&conn, op_id, 0, revision, &record);
                    tracing::info!(target: "alpha_ffi", "MEDIA_KEY early chunks adopted op={op_id} n={}", early.len());
                    return (arr, true);
                }
                tracing::warn!(target: "alpha_ffi", "MEDIA_KEY early chunks rejected op={op_id} n={}", early.len());
            }
        }

        let conn = self.db.connection();
        if let Some(k) = stored {
            let as_written = k.len() == 65 && k[64] == AS_WRITTEN;
            if (k.len() == 64 || as_written) && k[32..64] == content {
                if let Ok(arr) = <[u8; 32]>::try_from(&k[..32]) {
                    tracing::info!(target: "alpha_ffi", "MEDIA_KEY reused op={op_id} rev={revision} size={size}");
                    return (arr, as_written);
                }
            }
            // Other bytes (or a record from before the hash): what storage
            // confirmed belongs to the old encryption and is not resumed.
            let _ = alpha_database::MediaSendObjectRepo::delete_for_output(&conn, op_id, 0, revision);
        }
        let fresh: [u8; 32] = *alpha_media::generate_key();
        let mut record = fresh.to_vec();
        record.extend_from_slice(&content);
        // Not saved — no problem: the send goes whole, as before. A message
        // must not be lost over this.
        if let Err(e) = alpha_database::MediaSendKeyRepo::store(&conn, op_id, 0, revision, &record) {
            tracing::warn!(target: "alpha_ffi", "MEDIA_KEY not stored op={op_id}: {e}");
        } else {
            tracing::info!(target: "alpha_ffi", "MEDIA_KEY stored op={op_id} rev={revision} size={size}");
        }
        (fresh, false)
    }

    /// Where to record "this chunk was accepted by storage" for a given send.
    ///
    /// `None` exactly where the key is one-shot: without a revision the output
    /// has no identity, and foreign confirmations must not apply to it. The
    /// send then goes exactly as before — whole.
    fn checkpoint_for(&self, op_id: &str, file_path: &str) -> Option<DbCheckpoint<'_>> {
        Some(DbCheckpoint {
            db: &self.db,
            send_id: op_id.to_string(),
            item_index: 0,
            revision: output_revision(file_path)?,
            output_path: file_path.to_string(),
        })
    }

    /// Send finished or cancelled — the checkpoint and the key must go.
    ///
    /// The key lives exactly as long as it is needed: keeping a secret after
    /// it has served its purpose is holding it for no reason.
    fn clear_send_state(&self, op_id: &str, why: &str) {
        // The send ended — success or cancel. Network-wait is cleared in both
        // cases: a cancelled send must not come back as waiting, and a published
        // one has nothing left to wait for.
        clear_waiting_network(op_id);
        if let Ok(mut s) = early_done_set().lock() {
            s.retain(|k| k != op_id && k.split('#').next().unwrap_or(k) != op_id);
        }
        let conn = self.db.connection();
        let objects = alpha_database::MediaSendObjectRepo::delete_for_send(&conn, op_id).unwrap_or(0);
        let keys = alpha_database::MediaSendKeyRepo::delete_for_send(&conn, op_id).unwrap_or(0);
        if objects > 0 || keys > 0 {
            tracing::info!(
                target: "alpha_ffi",
                "SEND_STATE очищено op={op_id} причина={why} кусков={objects} ключей={keys}",
            );
        }
    }

    /// Upload a video's final chunks while the encoder still writes [growing_path]
    /// (the finished file becomes [final_path]); runs until [end_preupload].
    /// What it sent is skipped by the regular upload of the finished file.
    pub fn preupload_growing(
        &self,
        op_id: String,
        growing_path: String,
        final_path: String,
        estimate_bytes: u64,
    ) -> Result<u64, MessengerError> {
        let Some(revision) = output_revision(&final_path) else { return Ok(0) };
        let _claim = UploadClaim::take(&op_id).ok_or_else(|| MessengerError::NetworkError {
            description: "эта отправка уже идёт".into(),
        })?;
        let key: [u8; 32] = *alpha_media::generate_key();
        {
            let conn = self.db.connection();
            let _ = alpha_database::MediaSendObjectRepo::delete_for_output(&conn, &op_id, 0, revision);
            let mut record = key.to_vec();
            record.extend_from_slice(&EARLY_KEY_MARK);
            if alpha_database::MediaSendKeyRepo::store(&conn, &op_id, 0, revision, &record).is_err() {
                return Ok(0);
            }
        }
        let Some(checkpoint) = self.checkpoint_for(&op_id, &final_path) else { return Ok(0) };
        let filename = std::path::Path::new(&final_path)
            .file_name().map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "file".to_string());
        let signer = self.media_signer();
        let ued = self.event_dispatcher.clone();
        let op = op_id.clone();
        let tor = self.op_is_secret(&op_id);
        let result = runtime().block_on(self.media_for(tor).preupload_growing(
            std::path::Path::new(&growing_path), &filename, &signer, key, &checkpoint, estimate_bytes,
            &move |done, total| ued.emit(CoreEvent::MediaProgress {
                op_id: op.clone(), downloaded: done, total, terminal: false,
            }),
            &|| early_is_done(&op_id),
            &|| is_upload_cancelled(&op_id),
        ));
        if let Ok(mut s) = early_done_set().lock() { s.remove(&op_id); }
        result.map_err(|e| MessengerError::NetworkError { description: format!("early upload: {e}") })
    }

    /// The encoder finished: the early upload of [op_id] takes no new chunks.
    pub fn end_preupload(&self, op_id: String) {
        if let Ok(mut s) = early_done_set().lock() { s.insert(op_id); }
    }

    pub fn upload_blob_ref_only(
        &self,
        file_path: String,
        media_type: String,
        poster_b64: Option<String>,
        op_id: String,
        meta_json: Option<String>,
    ) -> Result<String, MessengerError> {
        // One sender per upload. The second gets an error instead of
        // starting a parallel upload — see UploadClaim.
        let _claim = UploadClaim::take(&op_id).ok_or_else(|| MessengerError::NetworkError {
            description: "эта отправка уже идёт".into(),
        })?;
        if let Some(local) = self.local_upload_ref(&op_id, &file_path, &media_type, &poster_b64, &meta_json) {
            return Ok(local);
        }
        let filename = std::path::Path::new(&file_path)
            .file_name().map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "file".to_string());
        let mt = match media_type.as_str() {
            "image" => alpha_media::MediaType::Image,
            "video" => alpha_media::MediaType::Video,
            "audio" => alpha_media::MediaType::Audio,
            _ => alpha_media::MediaType::File,
        };
        let signer = self.media_signer();
        let ued = self.event_dispatcher.clone();
        let op = op_id.clone();
        let rt = runtime();
        // Large-file / ANR fix: EVERY media type now uploads via TRUE streaming
        // (read → encrypt → upload one chunk at a time), exactly like video. The
        // file is NEVER loaded whole into memory (peak RSS is O(chunk)), so two
        // 150 MB documents no longer thrash the heap and ANR the sender. Before,
        // only "video" streamed; photo/audio/document went through std::fs::read
        // (whole file) + upload_with_progress. upload_streaming_file is media-type
        // agnostic — its only video-specific step (MP4 faststart) is a no-op for
        // other types — and it produces a streaming ref that download_media_inner
        // already handles for ANY type (it branches on stream_chunks, not on
        // media_type). One architecture for all files.
        let tor = self.op_is_secret(&op_id);
        let (key, as_written) = self.stable_media_key(&op_id, &file_path);
        let media = self.media_for(tor);
        let checkpoint = self.checkpoint_for(&op_id, &file_path);
        let checkpoint = checkpoint.as_ref().map(|c| c as &dyn alpha_media::resume::ChunkCheckpoint);
        let progress = move |done, total| ued.emit(CoreEvent::MediaProgress {
            op_id: op.clone(), downloaded: done, total, terminal: total > 0 && done >= total,
        });
        let cancelled = || is_upload_cancelled(&op_id);
        let path = std::path::Path::new(&file_path);
        let result = rt.block_on(async {
            // Sent while encoded: the player reads it by range, so its index stays where the encoder left it.
            if as_written {
                media.upload_streaming_as_written(path, &filename, mt, &signer, key, &progress, &cancelled, checkpoint).await
            } else {
                media.upload_streaming_file(path, &filename, mt, &signer, key, &progress, &cancelled, checkpoint).await
            }
        })
        .map_err(|e| MessengerError::NetworkError { description: format!("media upload failed: {}", e) })?;

        let mut media_ref = MediaRef::from_upload_result(&result);
        media_ref.tor = tor;
        let mut v = serde_json::to_value(&media_ref)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        if let serde_json::Value::Object(ref mut m) = v {
            m.insert("media_type".to_string(), serde_json::Value::String(media_type.clone()));
            m.insert("local_path".to_string(), serde_json::Value::String(file_path.clone()));
            if let Some(p) = &poster_b64 {
                if !p.is_empty() { m.insert("poster_b64".to_string(), serde_json::Value::String(p.clone())); }
            }
        }
        merge_display_meta(&mut v, &meta_json);
        Ok(v.to_string())
    }

    /// Persist a pending album locally (ONE bubble, rendered from local files)
    /// and return its message id. `items_json` is a JSON array of
    /// `{local_path, media_type, poster_b64?}`.
    pub fn persist_outgoing_album(
        &self,
        chat_id: String,
        items_json: String,
        caption: Option<String>,
        ttl_secs: u32,
        thread: Option<String>,
    ) -> Result<String, MessengerError> {
        let items: Vec<serde_json::Value> = serde_json::from_str(&items_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("bad items_json: {}", e) })?;
        let mut total: u64 = 0;
        let mut manifest_items = Vec::with_capacity(items.len());
        for it in &items {
            let local = it.get("local_path").and_then(|x| x.as_str()).unwrap_or("");
            let mtype = it.get("media_type").and_then(|x| x.as_str()).unwrap_or("image");
            let size = std::fs::metadata(local).map(|m| m.len()).unwrap_or(0);
            total += size;
            let mut o = serde_json::Map::new();
            o.insert("local_path".to_string(), serde_json::Value::String(local.to_string()));
            o.insert("media_type".to_string(), serde_json::Value::String(mtype.to_string()));
            o.insert("pending".to_string(), serde_json::Value::Bool(true));
            o.insert("original_size".to_string(), serde_json::json!(size));
            if let Some(p) = it.get("poster_b64").and_then(|x| x.as_str()) {
                if !p.is_empty() { o.insert("poster_b64".to_string(), serde_json::Value::String(p.to_string())); }
            }
            manifest_items.push(serde_json::Value::Object(o));
        }
        let manifest = serde_json::json!({
            "album": true, "media_type": "album", "pending": true,
            "items": manifest_items,
        }).to_string();
        let mut builder = MessageBuilder::media(&manifest, "album", "album", total);
        if let Some(cap) = &caption {
            if !cap.is_empty() { builder = builder.caption(cap); }
        }
        if ttl_secs > 0 {
            builder = builder.expiry(AlphaExpiryPolicy::AfterRead { ttl_secs });
        }
        let msg = builder.build().in_thread(thread);
        let msg_id = msg.id().to_string();
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        // Scheduled: hold it NOW, before the upload can publish it.
        if let Some(nb) = take_armed(&chat_id) {
            let _ = self.hold_message(msg_id.clone(), chat_id.clone(), nb);
        }
        Ok(msg_id)
    }

    /// Broadcast a finished album as ONE confirmed MLS message. `item_refs_json`
    /// is a JSON array of the per-item refs returned by [`upload_blob_ref_only`].
    pub fn send_album(
        &self,
        chat_id: String,
        msg_id: String,
        item_refs_json: String,
        caption: Option<String>,
        ttl_secs: u32,
    ) -> Result<(), MessengerError> {
        // Same gate as publish_media_ref: a cancelled album never reaches a peer.
        if is_upload_cancelled(&msg_id) {
            let conn = self.db.connection();
            let _ = PendingMediaRepo::delete_by_msg(&conn, &msg_id);
            let _ = alpha_database::MessageRepo::delete(&conn, &msg_id);
            drop(conn);
            clear_upload_cancel(&msg_id);
            return Err(MessengerError::InvalidArgument { description: "upload cancelled".into() });
        }
        let items: Vec<serde_json::Value> = serde_json::from_str(&item_refs_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("bad item_refs_json: {}", e) })?;
        let total: u64 = items.iter()
            .filter_map(|it| it.get("original_size").and_then(|x| x.as_u64()))
            .sum();
        // MLS manifest strips local_path (sender-only); stored keeps it so the
        // sender's bubble renders from disk without re-downloading. PROTOCOL v2:
        // externalize each large per-item ref (a big video inside an album would
        // otherwise re-inline 1000s of chunks and blow the album event past the
        // relay limit) — same slim-descriptor treatment as a single send. Errors
        // (manifest upload failed) propagate so the album never ships a giant event.
        let mut mls_items: Vec<serde_json::Value> = Vec::with_capacity(items.len());
        // What this phone keeps: a big item as its slim ref plus our file, like a receiver.
        let mut local_items: Vec<serde_json::Value> = Vec::with_capacity(items.len());
        for it in &items {
            let (mut wire, local) = match serde_json::from_value::<MediaRef>(it.clone()) {
                Ok(mr) => {
                    let slim = self.externalize_ref_if_large(&mr)?;
                    let wv = serde_json::to_value(&slim)
                        .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
                    let local = local_copy_of(&wv, it, slim.is_external_manifest() && !mr.is_external_manifest())
                        .and_then(|s| serde_json::from_str(&s).ok())
                        .unwrap_or_else(|| it.clone());
                    (wv, local)
                }
                // Not a MediaRef shape (shouldn't happen) — pass the item through.
                Err(_) => (it.clone(), it.clone()),
            };
            if let Some(m) = wire.as_object_mut() { m.remove("local_path"); }
            mls_items.push(wire);
            local_items.push(local);
        }
        // "Don't keep on servers": recipients fetch at once (ns flag) and we
        // delete the blobs from storage after delivery.
        if self.chat_no_store(chat_id.clone()) {
            for w in mls_items.iter_mut() {
                if let Some(m) = w.as_object_mut() { m.insert("ns".into(), serde_json::Value::Bool(true)); }
            }
            let _ = self.register_media_purge(msg_id.clone(), serde_json::Value::Array(items.clone()).to_string());
        }
        // A timed view is one photo or video, sent as a single media message
        // so the viewer opens it whole; its file leaves the servers once seen.
        let whisper_mode = {
            let conn = self.db.connection();
            alpha_database::whispers::outgoing_mode(&conn, &msg_id)
        };
        let single = whisper_mode.filter(|_| items.len() == 1);
        let (wire_json, stored_json) = match single {
            Some(_) => (mls_items[0].to_string(), local_items[0].to_string()),
            None => (
                serde_json::json!({ "album": true, "media_type": "album", "items": mls_items }).to_string(),
                serde_json::json!({ "album": true, "media_type": "album", "items": local_items }).to_string(),
            ),
        };
        {
            let conn = self.db.connection();
            let _ = MessageRepo::update_media_ref(&conn, &msg_id, &stored_json);
        }
        if is_local_chat(&chat_id) {
            self.clear_send_state(&msg_id, "сохранено у себя");
            return Ok(());
        }
        let mut builder = match single {
            Some(_) => {
                let it = &items[0];
                let field = |k: &str| it.get(k).and_then(|x| x.as_str()).unwrap_or_default().to_string();
                MessageBuilder::media(&wire_json, &field("media_type"), &field("filename"), total).id(&msg_id)
            }
            None => MessageBuilder::media(&wire_json, "album", "album", total).id(&msg_id),
        };
        if let Some(r) = self.reply_of(&msg_id) { builder = builder.reply_to(&r); }
        if let Some(cap) = &caption {
            if !cap.is_empty() { builder = builder.caption(cap); }
        }
        if let Some((secs, glance)) = single {
            builder = builder.expiry(AlphaExpiryPolicy::Whisper { secs, glance });
            let _ = self.register_media_purge(msg_id.clone(), stored_json.clone());
        } else if ttl_secs > 0 {
            builder = builder.expiry(AlphaExpiryPolicy::AfterRead { ttl_secs });
        }
        let mut msg = builder.build().in_thread(self.thread_of_message(&msg_id));
        if self.row_silent(&msg_id) { msg.set_silent(true); }
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        if self.hold_if_scheduled(&chat_id, &msg_id, &payload_bytes, "direct") {
            return Ok(());
        }
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let ciphertext = mls
            .send(&group_id, &payload_bytes)
            .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?;
        let epoch = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
        drop(mls);
        // Send state lives until PUBLICATION, not until the last byte.
        //
        // Cleanup must not run right after upload: an element can finish
        // uploading before the album's publication completes, and a checkpoint
        // wiped too early makes the queue re-send the whole element from zero.
        //
        // Until the message is published the work is not done: the album
        // publishes as a whole, and any of its elements may be needed again.
        let published = self.publish_application_message_confirmed(&group_id, epoch, ciphertext);
        if published.is_ok() {
            // One statement removes the album and all its elements: their
            // identifiers are `<message>#<index>`.
            self.clear_send_state(&msg_id, "опубликовано");
        }
        published
    }

    // --- Offline media queue ---

    /// Queue a single media (photo/video/voice/file) for offline-tolerant send.
    /// Called by the optimistic send path right after the local bubble is
    /// persisted; deleted once the upload+broadcast succeeds.
    pub fn enqueue_pending_media_single(
        &self,
        chat_id: String,
        msg_id: String,
        file_path: String,
        media_type: String,
        caption: Option<String>,
        poster_b64: Option<String>,
        streaming: bool,
        ttl_secs: u32,
        meta_json: Option<String>,
    ) -> Result<(), MessengerError> {
        // The display metadata rides in the queued payload too, so a send that is
        // only completed later — offline queue, retry, restart after process death
        // — still ships the duration badge with it.
        let meta = meta_json
            .as_deref()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(s).ok())
            .filter(|v| v.is_object());
        let payload = serde_json::json!({
            "file_path": file_path, "media_type": media_type,
            "caption": caption, "poster_b64": poster_b64, "streaming": streaming,
            "ttl_secs": ttl_secs, "meta": meta,
        }).to_string();
        let conn = self.db.connection();
        let r = PendingMediaRepo::enqueue(&conn, &chat_id, &msg_id, "single", &payload)
            .map(|_| ())
            .map_err(|e| MessengerError::DatabaseError { description: format!("queue media: {}", e) });
        if r.is_ok() {
            // The intent is recorded and survives process death. No key, ref or
            // addresses here — only whose message it is and of what kind.
            tracing::info!(target: "alpha_ffi", "JOB_CREATE msg={msg_id} род=single");
        }
        r
    }

    /// Queue an album for offline-tolerant send. `items_json` is the same array
    /// passed to `persist_outgoing_album` ({local_path, media_type, poster_b64?}).
    pub fn enqueue_pending_media_album(
        &self,
        chat_id: String,
        msg_id: String,
        items_json: String,
        caption: Option<String>,
        ttl_secs: u32,
    ) -> Result<(), MessengerError> {
        let payload = serde_json::json!({ "caption": caption, "ttl_secs": ttl_secs, "items": serde_json::from_str::<serde_json::Value>(&items_json).unwrap_or(serde_json::Value::Array(vec![])) }).to_string();
        let conn = self.db.connection();
        PendingMediaRepo::enqueue(&conn, &chat_id, &msg_id, "album", &payload)
            .map(|_| ())
            .map_err(|e| MessengerError::DatabaseError { description: format!("queue album: {}", e) })
    }

    /// Remove a media item from the queue once it's been sent successfully.
    pub fn delete_pending_media(&self, msg_id: String) {
        let conn = self.db.connection();
        let _ = PendingMediaRepo::delete_by_msg(&conn, &msg_id);
    }

    /// The user pressed ✕ on a media send.
    ///
    /// The upload itself is a synchronous FFI call running inside `block_on`, so
    /// cancelling the Kotlin coroutine that started it does nothing to the work —
    /// the bytes kept going and the message was published at the end, which is why
    /// a cancelled file still arrived at the peer. Raising this flag stops the
    /// pipeline within one chunk and, more importantly, makes the publish step
    /// refuse to commit: nothing is sent, nothing is queued, the row is gone.
    ///
    /// Idempotent, and safe to call for an id that is not uploading.
    pub fn cancel_media_upload(&self, msg_id: String) {
        if let Ok(mut s) = upload_cancel_set().lock() {
            s.insert(msg_id.clone());
        }
        // A cancelled send never resumes — keeping its checkpoint and key is
        // pointless. This is the only outcome where state is deleted on
        // non-success: failure keeps it, because a retry follows.
        self.clear_send_state(&msg_id, "отмена");
        let conn = self.db.connection();
        let _ = PendingMediaRepo::delete_by_msg(&conn, &msg_id);
    }

    /// Upload + broadcast every queued media item (offline → reconnect). Mirrors
    /// the online send paths but runs entirely in the core so it works from the
    /// background sync service. Returns how many were delivered. Call AFTER
    /// catch-up so encryption/epoch is current.
    pub fn flush_pending_media(&self) -> u32 {
        let entries = {
            let conn = self.db.connection();
            PendingMediaRepo::list(&conn).unwrap_or_default()
        };
        if entries.is_empty() { return 0; }

        // --- Connectivity gate ---
        //
        // Uploading blobs when no relay is reachable is worse than pointless: the
        // blobs go up (slow, CPU-heavy encryption), the MLS publish then fails
        // with "not connected to relay", and we do the whole thing again on the
        // next tick. Observed in the field: a 20 KB voice note re-encrypted and
        // re-uploaded to Blossom every 12s for hours, each attempt blocking a
        // runtime worker via `block_on` — which starved the relay's own async
        // reconnect, so the connection could never come back. A self-sustaining
        // death spiral that only an app restart broke.
        //
        // If we cannot publish, we do not upload. The queue simply waits.
        let connected = runtime().block_on(self.transport.is_connected());
        if !connected {
            tracing::info!(
                target: "TRANSPORT",
                "flush_pending_media: no relay connected — {} item(s) stay queued (not re-uploading)",
                entries.len(),
            );
            // Waiting for transport is NOT a failure.
            //
            // Reporting nothing here teaches the screen to trust its last
            // heard state — "failed" stuck while the send was merely waiting
            // for the network. Hence ↻ instead of ✕: waiting could not be
            // cancelled, and a retry without network returned the same icon
            // within half a second.
            //
            // The three states are distinguished by source, not guesswork: the
            // queue row exists (work not abandoned), no connectivity (nothing
            // to continue with), no error (nothing to retry).
            //
            // Only the TRANSITION is reported. The same status repeated every
            // twelve seconds is noise — the noise that trained the screen not
            // to trust state.
            for e in &entries {
                if e.status == "failed" { continue; }
                if mark_waiting_network(&e.msg_id) {
                    tracing::info!(target: "alpha_ffi", "WAIT_NETWORK msg={}", e.msg_id);
                    self.callback.on_delivery_update(&e.msg_id, "waiting_network");
                }
            }
            return 0;
        }

        let now = chrono::Utc::now();
        // The network just came back → this pass ignores the backoff windows.
        let woke = PENDING_WAKE.swap(false, std::sync::atomic::Ordering::Relaxed);
        let mut sent = 0u32;
        // Drain the queue in small bites. If 100 items were waiting out an outage,
        // firing them all the moment the network returns is just a second storm —
        // the same one we came here to kill. The next tick picks up the rest.
        const MAX_PER_FLUSH: u32 = 3;
        for e in entries {
            if sent >= MAX_PER_FLUSH {
                tracing::info!(
                    target: "TRANSPORT",
                    "flush_pending_media: sent {MAX_PER_FLUSH} this pass — rest of the queue waits for the next tick",
                );
                break;
            }
            // Respect the backoff window set by the previous transient failure —
            // unless the connection was just restored, in which case waiting out a
            // timer that only existed for the offline case would be absurd.
            if !woke {
                if let Some(next) = e.next_retry.as_deref() {
                    if let Ok(t) = chrono::DateTime::parse_from_rfc3339(next) {
                        if now < t.with_timezone(&chrono::Utc) {
                            continue; // not due yet
                        }
                    }
                }
            }
            // Rows PARKED as permanently failed are not retried — the user must
            // press Retry (which flips them back to 'queued'). Skip them so a 415
            // never re-hammers the relay / re-spins the UI.
            if e.status == "failed" { continue; }
            // Someone is already on this upload — don't start a second one.
            //
            // The queue row appears on failure but does not vanish the instant
            // work resumes: the app retries on its own while the core's tick
            // arrives twelve seconds later and sees the same row — that is how
            // two uploaders and a duplicate event for one message happen.
            if upload_is_in_flight(&e.msg_id) {
                tracing::info!(target: "alpha_ffi", "pending media {}: уже отправляется — пропуск", e.msg_id);
                continue;
            }
            // Claim the row FIRST — only then do anything.
            //
            // The list is snapshotted at the start of a pass, but work on it
            // runs for minutes — a row can be published and deleted meanwhile.
            // The claim checks existence and busy-ness in one DB write, so two
            // queue passes can no longer race.
            let claimed = {
                let c = self.db.connection();
                PendingMediaRepo::claim(&c, e.id).unwrap_or(false)
            };
            if !claimed {
                tracing::info!(
                    target: "alpha_ffi",
                    "CLAIM_SKIP msg={}: строка занята или уже отправлена", e.msg_id,
                );
                continue;
            }
            tracing::info!(target: "alpha_ffi", "CLAIM msg={} попыток={}", e.msg_id, e.attempts);
            // Work resumed — the network wait is over. The screen learns this
            // from the same status as a normal send.
            clear_waiting_network(&e.msg_id);
            self.callback.on_delivery_update(&e.msg_id, "sending");
            let v: serde_json::Value = match serde_json::from_str(&e.payload) { Ok(x) => x, Err(_) => { let c=self.db.connection(); let _=PendingMediaRepo::delete(&c,e.id); continue; } };
            // The blobs are already on Blossom (content-addressed — they are not
            // going anywhere). Whatever knocked us over happened at the PUBLISH
            // step, so redo only that. This is what stops a network drop from
            // turning into an endless re-encrypt + re-upload of the same file.
            // Same publish API as a normal send: broadcast_media(mref, …).
            let res = if e.blob_uploaded && e.media_ref.is_some() && e.kind != "album" {
                let mref = e.media_ref.clone().unwrap_or_default();
                let media_type = v.get("media_type").and_then(|x| x.as_str()).unwrap_or("file").to_string();
                let caption = v.get("caption").and_then(|x| x.as_str()).map(|s| s.to_string());
                let ttl_secs = v.get("ttl_secs").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
                let (expiry_mode, ttl) = if ttl_secs > 0 {
                    (Some("after_send".to_string()), Some(ttl_secs))
                } else {
                    (None, None)
                };
                tracing::info!("pending media {}: blobs already on Blossom → publish only", e.msg_id);
                self.broadcast_media(
                    e.chat_id.clone(), e.msg_id.clone(), mref, media_type, caption, expiry_mode, ttl,
                ).map_err(|err| err.to_string())
            } else if e.kind == "album" {
                self.flush_one_album(&e.chat_id, &e.msg_id, &v)
            } else {
                self.flush_one_single(&e.chat_id, &e.msg_id, &v)
            };
            match res {
                Ok(()) => {
                    let c = self.db.connection();
                    let _ = PendingMediaRepo::delete(&c, e.id);
                    self.callback.on_delivery_update(&e.msg_id, "sent");
                    sent += 1;
                }
                Err(err) => {
                    let (permanent, reason) = Self::classify_upload_error(&err);
                    let c = self.db.connection();
                    if permanent {
                        // 415 / 400 / 401 / 403 — retrying can NEVER succeed. Park
                        // as failed (kept for Retry) and tell the UI, then keep
                        // draining the rest of the queue.
                        tracing::warn!("pending media {} permanently failed ({reason}): {err}", e.msg_id);
                        let _ = PendingMediaRepo::mark_failed(&c, &e.msg_id, reason);
                        self.callback.on_delivery_update(&e.msg_id, "failed");
                    } else if reason == "unknown" && e.attempts + 1 >= Self::MAX_PENDING_ATTEMPTS {
                        // A NETWORK failure is retried forever — someone on a
                        // 12-hour flight must not lose their message because we
                        // gave up after eight tries. The backoff (up to 10 min)
                        // makes that free. Only an error we cannot even classify
                        // is capped, so a genuinely broken row can't grind on.
                        tracing::warn!(
                            "pending media {} gave up after {} unclassified attempts: {err}",
                            e.msg_id, e.attempts + 1,
                        );
                        let _ = PendingMediaRepo::mark_failed(&c, &e.msg_id, reason);
                        self.callback.on_delivery_update(&e.msg_id, "failed");
                    } else {
                        // Transient (offline / timeout / 5xx / 429). Back off
                        // EXPONENTIALLY — a fixed retry cadence turned every flush
                        // tick into a full re-encrypt + re-upload of the same blob,
                        // which is what starved the runtime and killed the relay
                        // reconnect. 15s, 30s, 60s … capped at 10 min.
                        let attempt = (e.attempts + 1).clamp(1, 6) as u32;
                        let delay_s = 15u64.saturating_mul(1u64 << (attempt - 1)).min(600);
                        let next = chrono::Utc::now()
                            + chrono::Duration::seconds(delay_s as i64);
                        tracing::info!(
                            "pending media {} transient failure ({reason}), retry in {}s: {err}",
                            e.msg_id, delay_s,
                        );
                        let _ = PendingMediaRepo::mark_transient_until(
                            &c, &e.msg_id, reason, &next.to_rfc3339(),
                        );
                        break;
                    }
                }
            }
        }
        sent
    }

    // --- Transport-object replication ---
    //
    // The whole step runs HERE, where keys, encryption and the storage client
    // already live. No key, ciphertext or envelope leaves the core: Kotlin gets
    // only a semantic outcome. File paths and storage addresses are not accepted
    // from outside — they come from the trusted record and the configured
    // topology, otherwise the primitive would be "read and send whatever I name".

    // --- Media encryption keys ---
    //
    // Kept so the SAME ciphertext can be reproduced after process death —
    // without that, resuming missing replicas is impossible. The row is already
    // encrypted at rest: the whole DB is under SQLCipher. The key never reaches
    // logs or error texts.

    pub fn store_media_send_key(
        &self,
        send_id: String,
        item_index: u32,
        revision: u64,
        key_material: Vec<u8>,
    ) -> Result<(), MessengerError> {
        if key_material.is_empty() || key_material.len() > 64 {
            return Err(MessengerError::InvalidArgument {
                description: "media send key: недопустимая длина".into(),
            });
        }
        let conn = self.db.connection();
        alpha_database::MediaSendKeyRepo::store(&conn, &send_id, item_index, revision, &key_material)
            .map_err(|e| MessengerError::InternalError { description: format!("store media key: {e}") })
    }

    pub fn load_media_send_key(&self, send_id: String, item_index: u32, revision: u64) -> Option<Vec<u8>> {
        let conn = self.db.connection();
        alpha_database::MediaSendKeyRepo::load(&conn, &send_id, item_index, revision).ok().flatten()
    }

    pub fn delete_media_send_key(&self, send_id: String, item_index: u32, revision: u64) {
        let conn = self.db.connection();
        let _ = alpha_database::MediaSendKeyRepo::delete(&conn, &send_id, item_index, revision);
    }

    pub fn delete_media_send_keys(&self, send_id: String) -> u32 {
        let conn = self.db.connection();
        alpha_database::MediaSendKeyRepo::delete_for_send(&conn, &send_id).unwrap_or(0) as u32
    }

    /// Retry a permanently-failed pending-media upload (user pressed "Retry").
    /// Flips the row back to `queued` and drains the queue. Returns true if it
    /// then sent successfully.
    pub fn retry_pending_media(&self, msg_id: String) -> bool {
        // A retry is a fresh intent: forget the earlier cancellation, or the
        // publish gate would silently swallow this send too.
        clear_upload_cancel(&msg_id);
        {
            let c = self.db.connection();
            let _ = PendingMediaRepo::requeue_by_msg(&c, &msg_id);
        }
        // A retry clears the network-wait mark — otherwise the connectivity gate
        // below treats the wait as already announced and stays silent, leaving
        // the screen on "sending" forever. The mark exists to avoid repeating
        // one status, not to hide a new attempt.
        clear_waiting_network(&msg_id);
        self.callback.on_delivery_update(&msg_id, "sending");
        self.flush_pending_media() > 0
    }

    fn flush_one_single(&self, chat_id: &str, msg_id: &str, v: &serde_json::Value) -> Result<(), String> {
        let file_path = v.get("file_path").and_then(|x| x.as_str()).unwrap_or("").to_string();
        // --- Source missing ---
        //
        // Returning Ok(()) here would be the worst kind of lie: the caller would
        // treat the send as SUCCEEDED, drop the queue row and report "sent" — a
        // checkmark under a message nobody ever received.
        //
        // A source can legitimately disappear: it lived in the cache, and both
        // the system and the user clean the cache. New sends keep a copy in
        // OUTBOX, but old rows and foreign paths remain — and the honest answer
        // for them is a permanent failure with a retry offer, not a silent
        // success or an eternal spinner.
        if file_path.is_empty() || !std::path::Path::new(&file_path).exists() {
            return Err("source_missing: исходный файл отправки недоступен".to_string());
        }
        let media_type = v.get("media_type").and_then(|x| x.as_str()).unwrap_or("file").to_string();
        let caption = v.get("caption").and_then(|x| x.as_str()).map(|s| s.to_string());
        let poster = v.get("poster_b64").and_then(|x| x.as_str()).map(|s| s.to_string());
        let ttl_secs = v.get("ttl_secs").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
        // UNIFIED PIPELINE — identical to a normal (online) send: upload the blob,
        // then broadcast the ref. upload_blob_ref_only streams video per-chunk
        // automatically (so the old `streaming` flag is obsolete), and
        // broadcast_media is the single publish API (carries the expiry mode).
        let mls_ref = self
            .upload_blob_ref_only(file_path, media_type.clone(), poster, msg_id.to_string(), v.get("meta").filter(|m| m.is_object()).map(|m| m.to_string()))
            .map_err(|e| e.to_string())?;
        // Record the blob as uploaded BEFORE broadcasting. Blossom is content-
        // addressed, so if the publish below fails the retry broadcasts only —
        // it never re-encrypts + re-uploads the same file (the death spiral the
        // pending-media state machine exists to prevent).
        {
            let c = self.db.connection();
            let _ = PendingMediaRepo::mark_blob_uploaded(&c, msg_id, &mls_ref);
        }
        // Chat timer → after-read expiry travels with the send.
        let (expiry_mode, ttl) = if ttl_secs > 0 {
            (Some("after_read".to_string()), Some(ttl_secs))
        } else {
            (None, None)
        };
        self.broadcast_media(chat_id.to_string(), msg_id.to_string(), mls_ref, media_type, caption, expiry_mode, ttl)
            .map_err(|e| e.to_string())
    }

    fn flush_one_album(&self, chat_id: &str, msg_id: &str, v: &serde_json::Value) -> Result<(), String> {
        let caption = v.get("caption").and_then(|x| x.as_str()).map(|s| s.to_string());
        let items = match v.get("items").and_then(|x| x.as_array()) { Some(a) => a.clone(), None => return Ok(()) };
        let mut refs: Vec<String> = Vec::with_capacity(items.len());
        for it in &items {
            let local = it.get("local_path").and_then(|x| x.as_str()).unwrap_or("");
            if local.is_empty() || !std::path::Path::new(local).exists() { continue; }
            let mtype = it.get("media_type").and_then(|x| x.as_str()).unwrap_or("image").to_string();
            let poster = it.get("poster_b64").and_then(|x| x.as_str()).map(|s| s.to_string());
            match self.upload_blob_ref_only(local.to_string(), mtype, poster, msg_id.to_string(), None) {
                Ok(r) => refs.push(r),
                Err(e) => return Err(e.to_string()),
            }
        }
        if refs.is_empty() { return Ok(()); }
        let refs_json = format!("[{}]", refs.join(","));
        let ttl_secs = v.get("ttl_secs").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
        self.send_album(chat_id.to_string(), msg_id.to_string(), refs_json, caption, ttl_secs).map_err(|e| e.to_string())
    }

    /// Transient retries are bounded. 8 attempts on the 15s→10min ladder is
    /// ~35 minutes of trying; past that the row is parked as failed and the user
    /// gets ↻ Retry, instead of the app grinding the CPU and the battery forever.
    const MAX_PENDING_ATTEMPTS: i64 = 8;

    /// Classify an upload failure → `(permanent, reason_slug)`.
    ///
    /// PERMANENT (retry can never help → park as failed, offer Retry button):
    ///   415 Unsupported Media Type, 400 "file type not allowed", 401/403 auth.
    /// TRANSIENT (retry later): offline/DNS/timeout, 429 rate-limit, 5xx, unknown.
    /// Unknown defaults to transient so a genuine blip is not treated as fatal.
    fn classify_upload_error(err: &str) -> (bool, &'static str) {
        let m = err.to_lowercase();
        // The source is gone — nothing to retry until a human intervenes. The
        // queue row SURVIVES (permanent failure does not delete it), so "retry"
        // still has something to work with if the file comes back.
        if m.contains("source_missing") {
            (true, "source_missing")
        } else if m.contains("415") || m.contains("unsupported media")
            || m.contains("file type not allowed")
            || m.contains("does not match the file content") {
            (true, "unsupported_media")
        } else if m.contains(" 401") || m.contains("unauthorized")
            || m.contains(" 403") || m.contains("forbidden")
            || m.contains("permission denied") {
            (true, "unauthorized")
        } else if m.contains("429") || m.contains("too many requests") {
            (false, "rate_limited")
        } else if m.contains("timed out") || m.contains("timeout") {
            (false, "timeout")
        } else if m.contains("error sending request") || m.contains("dns")
            || m.contains("no address") || m.contains("connect")
            || m.contains("offline") || m.contains("network") {
            (false, "offline")
        } else if m.contains(" 500") || m.contains(" 502") || m.contains(" 503")
            || m.contains(" 504") || m.contains("server error") {
            (false, "server")
        } else {
            (false, "unknown")
        }
    }

    /// Send an ephemeral media message with the specified expiry policy.
    pub fn send_ephemeral_media(
        &self,
        chat_id: String,
        media_ref: String,
        media_type: String,
        filename: String,
        size_bytes: u64,
        caption: Option<String>,
        expiry_mode: String,
        ttl_secs: u32,
    ) -> Result<String, MessengerError> {
        let expiry = match expiry_mode.as_str() {
            "after_send" => AlphaExpiryPolicy::AfterSend { ttl_secs },
            "after_read" => AlphaExpiryPolicy::AfterRead { ttl_secs },
            "view_once" => AlphaExpiryPolicy::ViewOnce,
            "whisper" => AlphaExpiryPolicy::Whisper { secs: ttl_secs, glance: false },
            "glance" => AlphaExpiryPolicy::Whisper { secs: ttl_secs, glance: true },
            _ => return Err(MessengerError::InvalidArgument { description: format!("invalid expiry_mode: '{}' (must be after_send, after_read, view_once, whisper or glance)", expiry_mode) }),
        };

        alpha_messages::validate_expiry_policy(&expiry)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid expiry policy: {}", e) })?;

        let mut builder = MessageBuilder::media(&media_ref, &media_type, &filename, size_bytes)
            .expiry(expiry);
        if let Some(cap) = &caption {
            builder = builder.caption(cap);
        }

        let msg = builder.build();
        let msg_id = msg.id().to_string();

        // Persist locally, then queue (defer-encryption — see queue_and_send).
        self.persist_alpha_message(&chat_id, &msg, "self")?;
        let payload_bytes = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload_bytes, &msg_id)?;

        Ok(msg_id)
    }

    /// Mark a message as read by the current user.
    ///
    /// For AfterRead messages, this computes the expiry timestamp and
    /// sends a ReadSignal back to the sender via MLS so both sides
    /// delete at the same time.
    pub fn mark_message_read(&self, chat_id: String, message_id: String) -> Result<(), MessengerError> {
        let read_at = chrono::Utc::now().to_rfc3339();

        // Mark as read in the database (computes expires_at for AfterRead)
        let conn = self.db.connection();
        MessageRepo::mark_read(&conn, &message_id, &read_at)
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;

        // The ReadSignal also drives synchronized deletion of "after read"
        // messages, so those still send it even with receipts turned off.
        let after_read = MessageRepo::get(&conn, &message_id).ok().flatten()
            .map(|m| m.expiry_policy == Some(alpha_database::models::ExpiryPolicyType::AfterRead))
            .unwrap_or(false);
        drop(conn);
        // A request has not been accepted: its sender learns nothing, not even "read".
        if self.is_request_chat(&chat_id) || (!after_read && !self.receipts_allowed()) {
            return Ok(());
        }

        // Send ReadSignal back to the sender via MLS
        let read_signal = MessageBuilder::read_signal(&message_id, &chat_id)
            .read_at(&read_at)
            .build();

        let payload_bytes = alpha_messages::serialize(&read_signal)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;

        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let ciphertext = mls
            .send(&group_id, &payload_bytes)
            .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?;
        let epoch = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
        drop(mls);
        self.publish_application_message(&group_id, epoch, ciphertext)?;

        tracing::info!("Marked message {} as read, sent ReadSignal", message_id);
        Ok(())
    }

    /// Mark a ViewOnce message as consumed (viewed).
    ///
    /// This clears the plaintext from the database and marks the
    /// message as consumed. The caller is responsible for zeroizing
    /// any in-memory plaintext and media keys before calling this.
    pub fn mark_view_once_consumed(&self, message_id: String) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        MessageRepo::mark_consumed(&conn, &message_id)
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;

        tracing::info!("Marked ViewOnce message {} as consumed", message_id);
        Ok(())
    }

    /// Our media message `message_id` is a whisper (set right after it is
    /// persisted, before any upload): its publish — and every retry — carries
    /// the whisper mode.
    pub fn whisper_mark_outgoing(&self, chat_id: String, message_id: String, kind: String, secs: u32, glance: bool) {
        let secs = if glance {
            secs.min(alpha_messages::MAX_GLANCE_SECS)
        } else {
            secs.clamp(alpha_messages::MIN_WHISPER_SECS, alpha_messages::MAX_WHISPER_SECS)
        };
        let conn = self.db.connection();
        let _ = alpha_database::whispers::put_outgoing(&conn, &message_id, &chat_id, &kind, secs, glance, &chrono::Utc::now().to_rfc3339());
    }

    /// The secret of an incoming whisper that has not burned (JSON), or None.
    /// Opening does not burn: it burns when the reader leaves or time is up.
    pub fn whisper_secret(&self, message_id: String) -> Option<String> {
        let conn = self.db.connection();
        alpha_database::whispers::secret(&conn, &message_id).and_then(|s| serde_json::to_string(&s).ok())
    }

    /// Burn an incoming whisper (read for `used_secs`; `shot` = a capture was
    /// tried) and tell its sender. False if it had already burned.
    pub fn whisper_burn(&self, message_id: String, used_secs: u32, shot: bool) -> bool {
        let burned = {
            let conn = self.db.connection();
            alpha_database::whispers::burn_incoming(&conn, &message_id, used_secs, shot, &chrono::Utc::now().to_rfc3339())
                .ok()
                .flatten()
        };
        let Some((chat_id, wire_id)) = burned else { return false };
        self.send_whisper_burned(&chat_id, &wire_id, used_secs, shot, false);
        true
    }

    /// The whispers of a chat as the screen needs them (never the secrets).
    pub fn whispers_json(&self, chat_id: String) -> String {
        let conn = self.db.connection();
        serde_json::to_string(&alpha_database::whispers::list_for_chat(&conn, &chat_id)).unwrap_or_else(|_| "[]".into())
    }

    /// "Your whisper burned" to its sender, through the reliable queue.
    fn send_whisper_burned(&self, chat_id: &str, wire_id: &str, used_secs: u32, shot: bool, expired: bool) {
        let msg = AlphaMessage::WhisperBurned { target_id: wire_id.to_string(), used_secs, shot, expired };
        if let Ok(payload) = alpha_messages::serialize(&msg) {
            let _ = self.queue_and_send(chat_id, &payload, &format!("wburn-{}", wire_id));
        }
    }

    /// Run the expiry sweeper: delete all expired ephemeral messages
    /// and consumed ViewOnce messages.
    ///
    /// Should be called on app startup and periodically (e.g., every 30s).
    /// Returns the number of messages deleted.
    pub fn sweep_expired_messages(&self) -> Result<u64, MessengerError> {
        // Whispers nobody opened within a day burn unread; their senders learn it.
        let unopened = {
            let conn = self.db.connection();
            alpha_database::whispers::expire_unopened(&conn, &chrono::Utc::now()).unwrap_or_default()
        };
        for (_, chat_id, wire_id) in unopened {
            self.send_whisper_burned(&chat_id, &wire_id, 0, false, true);
        }
        let conn = self.db.connection();
        ExpirySweeper::sweep_full(&conn, |_msg_id, media_ref| {
            // The message is gone; its decrypted file must go too. The app owns
            // the cache, so the ref waits here until it asks (drain_expired_media).
            if !media_ref.is_empty() {
                if let Ok(mut q) = expired_media().lock() { q.push(media_ref.to_string()); }
            }
        })
        .map_err(|e| MessengerError::InternalError { description: format!("sweep error: {}", e) })
    }

    /// Media refs of messages that expired since the last call: the app deletes
    /// their files (cache copy, the sender's own copy).
    pub fn drain_expired_media(&self) -> Vec<String> {
        expired_media().lock().map(|mut q| std::mem::take(&mut *q)).unwrap_or_default()
    }

    // --- Media ---


    /// Download a media file.
    /// Stop the download running under `op_id` (within ~100 ms). Idempotent.
    pub fn cancel_media_download(&self, op_id: String) {
        if let Ok(mut s) = download_cancel_set().lock() {
            s.insert(op_id);
        }
    }

    pub fn download_media(&self, media_ref_json: String, output_path: String) -> Result<(), MessengerError> {
        self.download_media_inner(&media_ref_json, &output_path, None)
    }

    /// The media vault key (32 bytes): received media is kept on disk sealed
    /// under it. It lives in the encrypted database, so it exists only after
    /// the unlock; the app holds it in memory to read the files back.
    pub fn media_vault_key(&self) -> Vec<u8> {
        let conn = self.db.connection();
        if let Some(k) = KeyValueRepo::get(&conn, KV_MEDIA_VAULT_KEY).ok().flatten()
            .and_then(|h| hex::decode(h).ok()).filter(|k| k.len() == 32)
        {
            return k;
        }
        let mut k = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut k);
        if KeyValueRepo::set(&conn, KV_MEDIA_VAULT_KEY, &hex::encode(k)).is_err() { return Vec::new(); }
        k.to_vec()
    }

    /// Received media is kept sealed, every kind: photos, videos (GIFs and
    /// video messages too), audio and documents.
    fn vault_for(&self, media_type: &str) -> Option<alpha_media::vault::VaultKey> {
        if !matches!(media_type, "image" | "video" | "audio" | "file") { return None; }
        let k: [u8; 32] = self.media_vault_key().try_into().ok()?;
        Some(alpha_media::vault::VaultKey::from_bytes(k))
    }

    /// Download a media file, reporting byte-level progress to the UI via
    /// `on_media_progress(op_id, downloaded, total)`. `op_id` is any caller
    /// token (we use the message id) so the UI can match progress to a bubble.
    pub fn download_media_with_progress(
        &self,
        media_ref_json: String,
        output_path: String,
        op_id: String,
    ) -> Result<(), MessengerError> {
        self.download_media_inner(&media_ref_json, &output_path, Some(op_id))
    }

    fn download_media_inner(
        &self,
        media_ref_json: &str,
        output_path: &str,
        op_id: Option<String>,
    ) -> Result<(), MessengerError> {
        let mut media_ref: MediaRef =
            serde_json::from_str(media_ref_json).map_err(|e| MessengerError::InvalidArgument { description: format!("invalid media_ref JSON: {}", e) })?;
        self.tor_policy(&mut media_ref, op_id.as_deref());

        // PROTOCOL v2: if this is a slim external-manifest ref, fetch + decrypt the
        // manifest blob to recover the full chunk list. Inline/legacy refs pass
        // through unchanged, so old messages keep working with no migration.
        let media_ref = self.resolve_manifest(&media_ref)?;

        let upload_result = media_ref.to_upload_result()
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid media_ref: {}", e) })?;

        let rt = runtime();
        let cancel_id = op_id.clone();
        let cancelled = || MessengerError::NetworkError { description: "download cancelled".into() };
        let vault = self.vault_for(&media_ref.media_type);
        // A streaming ref's blobs are encrypted PER CHUNK — each with its own
        // nonce derived from the chunk index. The legacy whole-file path below
        // cannot decrypt them, so it is not merely slower here, it fails. Without
        // this branch a video can only ever be streamed, never downloaded: the ↓
        // button had nothing behind it, "delete from cache" had nothing to delete,
        // and the bubble could not tell the two states apart.
        if let Some(chunks) = upload_result.stream_chunks.as_ref() {
            let ued = self.event_dispatcher.clone();
            // TRUE streaming to disk: the pipeline writes each decrypted chunk
            // straight to output_path (write → flush → drop), so the receiver
            // never holds the whole file in memory. A 150 MB download no longer
            // buffers a 150 MB Vec<u8> then fs::write's it — peak RAM is O(chunk).
            let progress = move |done, total| {
                if let Some(id) = op_id.as_deref() {
                    ued.emit(CoreEvent::MediaProgress {
                        op_id: id.to_string(), downloaded: done, total, terminal: total > 0 && done >= total,
                    });
                }
            };
            run_cancellable(cancel_id.as_deref(), self.media_for(media_ref.tor).download_streaming_to_file(
                &upload_result.media_key,
                &upload_result.media_hash,
                chunks,
                std::path::Path::new(output_path),
                &progress,
                vault.as_ref(),
            ))
            .ok_or_else(cancelled)?
            .map_err(|e| MessengerError::NetworkError { description: format!("streaming download failed: {}", e) })?;
            return Ok(());
        }
        let plaintext = match op_id {
            Some(id) => {
                let ued = self.event_dispatcher.clone();
                let progress = move |done, total| ued.emit(CoreEvent::MediaProgress {
                    op_id: id.clone(), downloaded: done, total, terminal: total > 0 && done >= total,
                });
                run_cancellable(cancel_id.as_deref(), self.media_for(media_ref.tor).download_with_progress(
                    &upload_result.media_key,
                    &upload_result.media_hash,
                    &upload_result.blob_urls,
                    upload_result.original_size,
                    &progress,
                ))
                .ok_or_else(cancelled)?
            }
            None => rt.block_on(self.media_for(media_ref.tor).download(
                &upload_result.media_key,
                &upload_result.media_hash,
                &upload_result.blob_urls,
                upload_result.original_size,
            )),
        }
        .map_err(|e| MessengerError::NetworkError { description: format!("media download failed: {}", e) })?;

        // Same rule as the streaming path: the final name appears only complete.
        let part = format!("{}.part", output_path);
        alpha_media::vault::write_file(std::path::Path::new(&part), vault.as_ref(), &plaintext)
            .and_then(|_| std::fs::rename(&part, output_path))
            .map_err(|e| {
                let _ = std::fs::remove_file(&part);
                MessengerError::InternalError { description: format!("failed to write file: {}", e) }
            })?;

        Ok(())
    }

    // --- Streaming E2EE Media ---


    /// The FULL media ref: a slim external-manifest ref resolved to the one
    /// carrying the chunk list. Streaming playback needs it to map byte
    /// positions to chunks before anything is downloaded.
    pub fn resolve_media_ref(&self, media_ref_json: String) -> Result<String, MessengerError> {
        let mut media_ref: MediaRef = serde_json::from_str(&media_ref_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid media_ref JSON: {}", e) })?;
        self.tor_policy(&mut media_ref, None);
        let full = self.resolve_manifest(&media_ref)?;
        serde_json::to_string(&full)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })
    }

    /// Download and decrypt a single streaming chunk by index.
    ///
    /// The decrypted plaintext is written to `output_path`. Returns the
    /// chunk's original plaintext size in bytes.
    ///
    /// Used by the Kotlin `EncryptedDataSource` (ExoPlayer) to progressively
    /// fetch individual chunks for streaming playback.
    pub fn download_stream_chunk(
        &self,
        media_ref_json: String,
        chunk_index: u32,
        output_path: String,
    ) -> Result<String, MessengerError> {
        let mut media_ref: MediaRef = serde_json::from_str(&media_ref_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid media_ref JSON: {}", e) })?;
        self.tor_policy(&mut media_ref, None);

        if !media_ref.streaming {
            return Err(MessengerError::InvalidArgument { description: "media_ref is not streaming (legacy format)".into() });
        }

        let chunks = media_ref.chunks.as_ref()
            .ok_or_else(|| MessengerError::InternalError { description: "streaming ref has no chunks".into() })?;

        let chunk_info = chunks.get(chunk_index as usize)
            .ok_or_else(|| MessengerError::InvalidArgument { description: format!("chunk {chunk_index} out of range (total: {})", chunks.len()) })?;

        let upload_result = media_ref.to_upload_result()
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid media_ref: {}", e) })?;

        let rt = runtime();
        let plaintext = rt.block_on(self.media_for(media_ref.tor).download_stream_chunk(
            &upload_result.media_key,
            &chunk_info.locations,
            chunk_index,
            &chunk_info.hash,
            // Explicit object identity when the message carries one. Messages
            // sent before that field existed fall back to deriving it from the
            // address — the only case where that is allowed.
            chunk_info.transport_object_id.as_ref(),
            // Expectations come from the ALREADY-parsed (authenticated,
            // hash-checked) manifest, not from the downloaded envelope: its
            // fields sit outside AEAD and are good only for cross-checking.
            Some(alpha_media::limits::ChunkContext {
                expected_index: chunk_index,
                expected_total: chunks.len() as u32,
            }),
        ))
        .map_err(|e| MessengerError::NetworkError { description: format!("stream chunk download failed: {}", e) })?;

        let vault = self.vault_for(&media_ref.media_type);
        alpha_media::vault::write_file(std::path::Path::new(&output_path), vault.as_ref(), &plaintext)
            .map_err(|e| MessengerError::InternalError { description: format!("failed to write chunk: {}", e) })?;

        // Return the plaintext size so the caller knows the chunk's byte length
        Ok(chunk_info.original_size.to_string())
    }

    /// Get the number of streaming chunks in a media ref.
    ///
    /// Returns 0 for non-streaming (legacy) media refs.
    pub fn get_stream_chunk_count(&self, media_ref_json: String) -> Result<u32, MessengerError> {
        let media_ref: MediaRef = serde_json::from_str(&media_ref_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid media_ref JSON: {}", e) })?;
        if !media_ref.streaming {
            return Ok(0);
        }
        Ok(media_ref.chunks.as_ref().map(|c| c.len() as u32).unwrap_or(0))
    }

    /// Get chunk info JSON for a specific streaming chunk.
    ///
    /// Returns a JSON string with `{"url": "...", "ciphertext_size": N,
    /// "original_size": N, "hash": "hex..."}`.
    pub fn get_stream_chunk_info(&self, media_ref_json: String, chunk_index: u32) -> Result<String, MessengerError> {
        let media_ref: MediaRef = serde_json::from_str(&media_ref_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid media_ref JSON: {}", e) })?;
        let chunks = media_ref.chunks.as_ref()
            .ok_or_else(|| MessengerError::InvalidArgument { description: "not a streaming ref".into() })?;
        let chunk = chunks.get(chunk_index as usize)
            .ok_or_else(|| MessengerError::InvalidArgument { description: format!("chunk {chunk_index} out of range") })?;
        serde_json::to_string(chunk)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })
    }

    // --- Sync ---

    /// Start the sync engine (connect to relays and begin listening).
    /// TRANSPORT hardening: force a clean relay reconnect + re-subscribe.
    /// Called by the app on network-available / foreground so realtime delivery
    /// resumes immediately instead of waiting for the SDK's passive reconnect
    /// (which left half-open sockets after airplane mode → "messages don't
    /// arrive for ~5 min"). Cheap to call repeatedly.
    pub fn force_reconnect(&self) -> Result<(), MessengerError> {
        let rt = runtime();
        // Debounce: if a reconnect/resubscribe already happened in the last few
        // seconds (e.g. the heartbeat just soft-resubscribed and then signalled
        // the Kotlin ntfy-wake path, or foreground + network-available fired
        // together), don't tear every socket down again. Just re-assert the
        // subscription on the still-healthy sockets. This removes the
        // "double reconnect" race that wasted the heartbeat's cheap resubscribe.
        const RECONNECT_DEBOUNCE_SECS: u64 = 3;
        let recently_reconnected = self
            .last_reconnect_at
            .lock()
            .map(|g| g.elapsed().as_secs() < RECONNECT_DEBOUNCE_SECS)
            .unwrap_or(false);

        let self_pubkey = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                .nostr().public_key()
        };

        // Try to claim the reconnect slot. If another full reconnect is already
        // executing, OR one completed within the debounce window, don't tear the
        // sockets down again — just re-assert the subscription (idempotent) so a
        // catch-up can still follow.
        use std::sync::atomic::Ordering;
        let claimed = self
            .reconnect_in_progress
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_ok();

        if recently_reconnected || !claimed {
            rt.block_on(self.transport.subscribe_mls_and_giftwrap(&self_pubkey))
                .map_err(|e| MessengerError::NetworkError { description: format!("resubscribe failed: {}", e) })?;
            let why = if !claimed { "another reconnect in progress" } else { "reconnected <3s ago" };
            tracing::info!(target: "TRANSPORT", "force_reconnect: debounced ({}) — resubscribe only", why);
            return Ok(());
        }

        // We hold the slot — guarantee it's released even on the error paths.
        let result = (|| -> Result<(), MessengerError> {
            rt.block_on(self.transport.force_reconnect())
                .map_err(|e| MessengerError::NetworkError { description: format!("force_reconnect failed: {}", e) })?;
            // Re-create subscriptions on the fresh sockets (NIP-59 #p filter etc.).
            rt.block_on(self.transport.subscribe_mls_and_giftwrap(&self_pubkey))
                .map_err(|e| MessengerError::NetworkError { description: format!("resubscribe failed: {}", e) })?;
            Ok(())
        })();
        self.reconnect_in_progress.store(false, Ordering::Release);

        result?;
        if let Ok(mut g) = self.last_reconnect_at.lock() { *g = std::time::Instant::now(); }
        tracing::info!(target: "TRANSPORT", "force_reconnect: reconnected + resubscribed");
        Ok(())
    }

    /// The OS reported the device's network: gained, switched or lost. This is
    /// the authority on the network, so it is never debounced. With a network,
    /// every relay and the wake channel reconnect at once, and this returns as
    /// soon as one relay is up (at most a few seconds) so the caller's catch-up
    /// runs on live sockets.
    pub fn set_network_available(&self, available: bool) -> Result<(), MessengerError> {
        let rt = runtime();
        rt.block_on(self.transport.set_network_available(available));
        if !available {
            return Ok(());
        }
        if let Some(ws) = self.wake_service.lock().ok().as_ref().and_then(|w| w.as_ref()) {
            ws.network_changed();
        }
        let self_pubkey = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                .nostr().public_key()
        };
        if rt.block_on(self.transport.wait_until_connected(std::time::Duration::from_secs(10))) {
            // Relays resend their own subscriptions as they connect; this also
            // moves the realtime window up to now.
            rt.block_on(self.transport.subscribe_mls_and_giftwrap(&self_pubkey))
                .map_err(|e| MessengerError::NetworkError { description: format!("resubscribe failed: {}", e) })?;
        }
        if let Ok(mut g) = self.last_reconnect_at.lock() { *g = std::time::Instant::now(); }
        Ok(())
    }

    pub fn start_sync(&self) -> Result<(), MessengerError> {
        let rt = runtime();

        // 1. Connect to all enabled relays.
        rt.block_on(self.transport.connect())
            .map_err(|e| MessengerError::NetworkError { description: format!("transport connect failed: {}", e) })?;

        // The delivery + reliability timers are DISABLED: their traffic scales
        // with the number of (forked) groups until the app collapses. In a forked
        // group the gaps never close (the peer is on a different MLS branch), so
        // the 10s delivery timer re-publishes DeliveryAcks + ResendRequests for
        // every open gap forever — N groups means N× kind-443 publishes every 10s,
        // each echoing back → catch-up churn → MLS-lock saturation → the
        // freeze / history-loss / dead-delivery users reported. The 5s health
        // timer adds to it: it locks the MLS mutex to poll epoch and fires "soft
        // resubscribe" nudges that add subscription churn.
        let _ = &self.reliability; // keep field used; controller now passive
        self.reliability.record_subscription_started();
        // The resend outbox and settled receipts hold nothing anyone reads.
        {
            let conn = self.db.connection();
            let _ = conn.execute("DELETE FROM delivery_outbox", []);
            let _ = alpha_database::receipts::prune(&conn);
        }

        // 2. Subscribe to both public MLS envelopes (kind 443) and gift-wrapped
        //    messages (kind 1059) — this is the channel that delivers real-time
        //    messages and Welcome packets.
        // NIP-59 requires a `#p` tag filter on kind-1059 — without it, relays
        //    hand back zero gift-wraps, leaving "Add by npub" silent on the peer
        //    side.
        let self_pubkey = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                .nostr().public_key()
        };
        // A relay that is not connected yet must NEVER abort the start: that left
        // the realtime listener and the heartbeat unstarted — the phone kept
        // sending but heard almost nothing (2026-10-13, both phones). Subscribe
        // again in the background until it takes.
        if let Err(e) = rt.block_on(self.transport.subscribe_mls_and_giftwrap(&self_pubkey)) {
            tracing::warn!(target: "TRANSPORT", "subscribe at start failed ({}) — retrying in the background", e);
            let t = Arc::clone(&self.transport);
            let pk = self_pubkey;
            rt.spawn(async move {
                for attempt in 1..=20u32 {
                    tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                    match tokio::time::timeout(std::time::Duration::from_secs(20), t.subscribe_mls_and_giftwrap(&pk)).await {
                        Ok(Ok(_)) => {
                            tracing::warn!(target: "TRANSPORT", "subscribe at start succeeded on retry {}", attempt);
                            return;
                        }
                        _ => {}
                    }
                }
                tracing::warn!(target: "TRANSPORT", "subscribe at start still failing — the heartbeat takes over");
            });
        }
        tracing::info!(target: "ACCOUNT", "sync started");

        // 2a. Publish our KeyPackage so peers can fetch it by our npub and
        //     create a Welcome to add us into a direct chat. Without this
        //     "Add by npub" never works because the other side has no
        //     KeyPackage to wrap into a Welcome.
        //
        //     `generate_key_packages` is non-destructive (takes &self) and
        //     `publish_key_package` is idempotent at the relay level: relays
        //     simply store the latest copy.
        match self.publish_own_key_package() {
            Ok(()) => tracing::info!("Published own KeyPackage to relays"),
            Err(e) => tracing::warn!("KeyPackage publish failed (non-fatal): {}", e),
        }

        // 3. Create the catch-up manager (used by both startup catch-up and
        //    realtime event processing).
        let db = Arc::clone(&self.db);
        let sync_state = SyncState::new(&db).map_err(|e| MessengerError::InternalError { description: format!("sync state init failed: {}", e) })?;
        let transport_clone = (*self.transport).clone();
        let catch_up = CatchUpManager::new(
            transport_clone, sync_state, db, self_pubkey,
            self.contact_avatars_dir.clone(),
            self.group_avatars_dir.clone(),
        );

        {
            let mut guard = self.sync_manager.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            *guard = Some(catch_up);
        }

        // 3a. Trigger a one-shot catch-up in the background so historical
        //     Welcomes (kind-1059) and group messages (kind-443) published
        //     while we were offline are pulled and processed. Without this,
        //     a peer who comes online AFTER another peer sent a Welcome
        //     never joins the chat — the subscribe below only delivers
        //     events going forward, not the backlog.
        //
        //     We construct a dedicated CatchUpManager here (not the one
        //     stored in `self.sync_manager`) because std::sync::Mutex's
        //     MutexGuard is !Send and we cannot hold it across the
        //     network await inside `catch_up()`.
        let cu_transport = (*self.transport).clone();
        // Separate transport clone for replying to role-sync requests that
        // arrived while we were offline (owner answers on next catch-up).
        let cu_resp_transport = (*self.transport).clone();
        let cu_db = Arc::clone(&self.db);
        let cu_avatars_dir = self.contact_avatars_dir.clone();
        let cu_group_avatars_dir = self.group_avatars_dir.clone();
        let cu_sync_state = SyncState::new(&cu_db)
            .map_err(|e| MessengerError::InternalError { description: format!("catch-up sync state init: {}", e) })?;
        let cu_mls = Arc::clone(&self.mls_session);
        let cu_identity = Arc::clone(&self.identity);
        let cu_callback = self.callback.clone();
        let cu_run = self.sync_gauge.begin();
        runtime().spawn(async move {
            let _run = cu_run;
            let mut manager = CatchUpManager::new(
                cu_transport, cu_sync_state, Arc::clone(&cu_db), self_pubkey,
                cu_avatars_dir.clone(),
                cu_group_avatars_dir.clone(),
            );
            let events = match manager.catch_up().await {
                Ok(events) => events,
                Err(e) => {
                    tracing::warn!("catch-up fetch failed: {}", e);
                    return;
                }
            };

            tracing::info!("catch-up: fetched {} historical events", events.len());
            if events.is_empty() {
                return;
            }

            // Now process synchronously — all the std::sync::Mutex locks are
            // released before the next .await (there is none after this).
            let processed = {
                let mut mls = match cu_mls.lock() {
                    Ok(g) => g,
                    Err(e) => {
                        tracing::error!("catch-up: mls lock poisoned: {}", e);
                        return;
                    }
                };
                let id_guard = match cu_identity.lock() {
                    Ok(g) => g,
                    Err(_) => return,
                };
                let id = match id_guard.as_ref() {
                    Some(i) => i,
                    None => return,
                };
                let keys = id.nostr().keypair().keys().clone();
                drop(id_guard);
                manager.process_missed_events(events, &mut mls, &keys)
            };

            match processed {
                Ok(events) => {
                    tracing::info!("catch-up: processed {} events", events.len());
                    // Each event that decrypted or joined a group mutated MLS
                    // ratchet/storage. Snapshot now so a kill before the next
                    // mutation doesn't lose the receive state.
                    if !events.is_empty() {
                        let snapshot = match cu_mls.lock() {
                            Ok(g) => g.snapshot_storage(),
                            Err(e) => {
                                tracing::warn!("catch-up persist: mls lock poisoned: {}", e);
                                Vec::new()
                            }
                        };
                        if !snapshot.is_empty() {
                            let conn = cu_db.connection();
                            if let Err(e) = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snapshot) {
                                tracing::warn!("catch-up persist: save_snapshot failed: {}", e);
                            } else {
                                tracing::debug!("catch-up: persisted {} MLS entries", snapshot.len());
                            }
                        }
                    }
                    // What arrived gets its "delivered" back — one receipt per chat.
                    send_delivered(&cu_db, &cu_mls, &cu_identity, &cu_resp_transport, received_by_chat(&events));
                    for ev in events {
                        match ev {
                            ProcessedEvent::NewMessage { chat_id, message, .. } => {
                                let json = serde_json::to_string(&message).unwrap_or_default();
                                cu_callback.on_message_received(&chat_id, &json);
                            }
                            ProcessedEvent::DeliveredReceived { chat_id, sender_id, message_ids } => {
                                apply_delivered(&cu_db, &cu_mls, &cu_callback, &chat_id, &sender_id, &message_ids);
                            }
                            // Transport directory. The core does the
                            // verification: it needs group membership and the
                            // MLS epoch. Never becomes a chat bubble.
                            ProcessedEvent::DirectoryReceived {
                                chat_id, sender_id, version, revision, entries,
                            } => {
                                if let Ok(gid) = hex::decode(&chat_id) {
                                    apply_group_directory_raw(
                                        &cu_db, &cu_mls, &cu_identity,
                                        &gid, &sender_id, version, revision, &entries,
                                    );
                                }
                            }
                            ProcessedEvent::SecretChatSignal { chat_id, sender_id, invite_id, step, data } => {
                                let ctx = secret_chat::Ctx {
                                    db: &cu_db, mls: &cu_mls, identity: &cu_identity,
                                    transport: &cu_resp_transport, callback: &cu_callback,
                                };
                                secret_chat::on_signal(&ctx, &chat_id, &sender_id, &invite_id, &step, &data);
                            }
                            ProcessedEvent::NewChat { group_id } => {
                                cu_callback.on_chat_created(&group_id, "group");
                            }
                            ProcessedEvent::MemberAdded { chat_id, member } => {
                                cu_callback.on_member_added(&chat_id, &member);
                            }
                            ProcessedEvent::MemberRemoved { chat_id, member } => {
                                cu_callback.on_member_removed(&chat_id, &member);
                            }
                            ProcessedEvent::ChatMetadataUpdated { chat_id } => {
                                cu_callback.on_chat_metadata_updated(&chat_id);
                            }
                            ProcessedEvent::MemberBanned { chat_id, target_pubkey, reason } => {
                                if who_in_raw(&cu_identity, &chat_id).map(|(h, _)| h) == Some(target_pubkey) {
                                    apply_ban_raw(&cu_db, &cu_mls, &chat_id, &reason);
                                }
                                cu_callback.on_chat_metadata_updated(&chat_id);
                            }
                            ProcessedEvent::ChatDisbanded { chat_id } => {
                                apply_disband_raw(&cu_db, &cu_mls, &chat_id);
                                cu_callback.on_chat_metadata_updated(&chat_id);
                            }
                            ProcessedEvent::ChatDeletedByPeer { chat_id } => {
                                wipe_direct_raw(&cu_db, &cu_mls, &chat_id);
                                cu_callback.on_chat_metadata_updated(&chat_id);
                            }
                            ProcessedEvent::RoleSyncRequested { chat_id } => {
                                // Owner answers a sync request that arrived while
                                // offline (no-op for non-owners) + re-broadcasts
                                // current metadata (group avatar/desc).
                                publish_role_snapshot_raw(
                                    &cu_db, &cu_mls, &cu_identity,
                                    cu_resp_transport.clone(), &chat_id, false,
                                );
                                broadcast_current_metadata_raw(
                                    &cu_db, &cu_mls, &cu_identity,
                                    cu_resp_transport.clone(), &chat_id,
                                );
                            }
                            ProcessedEvent::ProfileRequested { chat_id, .. } => {
                                // Reply with our profile to a request that arrived
                                // while we were offline.
                                reply_profile_into_chat_raw(
                                    &cu_db, &cu_mls, &cu_identity,
                                    cu_resp_transport.clone(), &chat_id,
                                );
                            }
                            ProcessedEvent::JoinRequested { chat_id, requester_npub, token } => {
                                if devices_token(&token) {
                                    cu_callback.on_devices_event("inbox");
                                } else if AlphaMessengerCore::claim_join_request(&cu_db, &chat_id, &token, &requester_npub) {
                                    cu_callback.on_join_requested(&chat_id, &requester_npub, &token);
                                }
                            }
                            ProcessedEvent::DeviceSync => cu_callback.on_devices_event("inbox"),
                            ProcessedEvent::MemberLeaveRequested { chat_id, leaver_pubkey } => {
                                let npub = nostr::PublicKey::from_hex(&leaver_pubkey).ok()
                                    .and_then(|p| nostr::nips::nip19::ToBech32::to_bech32(&p).ok())
                                    .unwrap_or(leaver_pubkey);
                                cu_callback.on_member_leave_requested(&chat_id, &npub);
                            }
                            ProcessedEvent::ForkDetected { chat_id, current_epoch, incoming_epoch, author } => {
                                tracing::warn!(
                                    target: "MLS_DIAG",
                                    "ForkDetected (catch-up task) chat={} cur={} incoming={} author={} — recovery will run on next start_sync",
                                    chat_id, current_epoch, incoming_epoch, author,
                                );
                            }
                            _ => {}
                        }
                    }
                }
                Err(e) => tracing::warn!("catch-up event processing failed: {}", e),
            }
        });

        // 4. Spawn the realtime listener task. It receives every event the
        //    relay pool pushes to subscribers, decodes it as an MLS envelope or
        //    gift-wrap, runs it through MLS decryption, and fires callbacks
        //    for the UI. This is what makes incoming messages feel instant
        //    instead of waiting for the 15-minute WorkManager poll.
        let transport_arc = Arc::clone(&self.transport);
        let mls_session = Arc::clone(&self.mls_session);
        let identity = Arc::clone(&self.identity);
        let sync_manager = Arc::clone(&self.sync_manager);
        let callback = self.callback.clone();
        let gauge = self.sync_gauge.clone();
        // Pre-compute our own pubkey hex for the own-message skip
        // in the realtime listener (avoids re-computing on every event).
        // Needed inside the spawn to persist MLS storage after each successful
        // receive — without this every realtime decrypt is volatile.
        let rt_db = Arc::clone(&self.db);
        let rt_media = Arc::clone(&self.media_pipeline);
        let rt_avatars = self.contact_avatars_dir.clone();
        let rt_prof_sem = Arc::clone(&self.profile_dl_sem);
        let rt_last_event = Arc::clone(&self.last_event_at);
        let rt_pending_resends = Arc::clone(&self.pending_resends);
        // The directory is applied inline in realtime, so this scope needs its
        // own references to the MLS session and identity.
        let rt_mls = Arc::clone(&self.mls_session);
        let rt_identity = Arc::clone(&self.identity);
        let rt_ring_synced = Arc::clone(&self.ring_synced);
        let rt_presence_routes = Arc::clone(&self.presence_routes);
        let rt_presence_inbox = Arc::clone(&self.presence_inbox);
        let rt_self_pk = self_pubkey;
        let rt_resub = Arc::clone(&self.resubscribe_wanted);

        let status_callback = callback.clone();
        let status_gauge = gauge.clone();
        let status_rx = transport_arc.relay_status();
        let handle: JoinHandle<()> = rt.spawn(async move {
            // Relay status → UI indicator ONLY (green/yellow dot). NO
            // force_reconnect here: a per-relay flap must not tear down the whole
            // pool's subscriptions (that caused missed events, gaps, resend storms
            // and slow delivery). The pool self-heals via the cooldown-gated
            // router heartbeat. Lives and dies with the listener below.
            let statuses = async move {
                let Some(mut rx) = status_rx else { return };
                loop {
                    match rx.recv().await {
                        Ok(alpha_transport::MonitorNotification::StatusChanged { relay_url, status }) => match status {
                            RelayStatus::Connected => {
                                status_callback.on_relay_status_change(&relay_url.to_string(), "connected");
                                status_gauge.report();
                            }
                            RelayStatus::Disconnected | RelayStatus::Terminated => {
                                status_callback.on_relay_status_change(&relay_url.to_string(), "disconnected");
                                status_gauge.report();
                            }
                            _ => {}
                        },
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                        Err(tokio::sync::broadcast::error::RecvError::Closed) => return,
                    }
                }
            };
            let listener = async move {
            // The recv loop runs under a supervisor: a transient broadcast
            // `Closed` must not kill the listener permanently. The supervisor
            // recreates the subscription + a FRESH dedup cache and restarts
            // after 2s. Only an intentional pool `Shutdown` exits for good.
            'supervisor: loop {
            let mut notifications = transport_arc.notifications();
            tracing::info!("Realtime relay listener started");

            // Dedup cache for event_ids we've already processed in this
            // process lifetime. Without it, the same event arriving from
            // 3 different relays gets decrypted 3 times — the second/third
            // attempts advance the MLS ratchet into a SecretReuseError state
            // and the user sees "messages lost" / "groups break" symptoms.
            // Bounded HashSet (FIFO eviction) keeps memory in check.
            let mut seen_events: std::collections::HashSet<String> = std::collections::HashSet::new();
            let mut seen_order: std::collections::VecDeque<String> = std::collections::VecDeque::with_capacity(8192);
            const SEEN_CAP: usize = 4096;

            loop {
                match notifications.recv().await {
                    Ok(RelayPoolNotification::Event { relay_url, event, .. }) => {
                        // STALE signal: we got SOMETHING from a relay → connection
                        // is genuinely live (not just a TCP socket).
                        if let Ok(mut g) = rt_last_event.lock() { *g = std::time::Instant::now(); }
                        let kind = event.kind.as_u16();
                        // Dedup BEFORE decrypt to avoid SecretReuseError from
                        // the same event being delivered by multiple relays.
                        let event_id_hex = event.id.to_hex();
                        let t_relay_in_ms = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_millis()).unwrap_or(0);
                        tracing::info!(
                            target: "DELIVERY_TRACE",
                            "RELAY_EVENT_IN event_id={} relay={} kind={} nostr_ts={} t_ms={}",
                            event_id_hex, relay_url, kind, event.created_at.as_u64(), t_relay_in_ms
                        );
                        // A relay's word is not proof: an event whose id or signature
                        // does not check out is dropped before it can take the dedupe
                        // slot of the genuine one.
                        if event.verify().is_err() {
                            tracing::warn!("realtime: dropping unverifiable event from {}", relay_url);
                            continue;
                        }
                        if !seen_events.insert(event_id_hex.clone()) {
                            tracing::debug!("realtime: skipping duplicate event {} from {}", event_id_hex, relay_url);
                            continue;
                        }
                        seen_order.push_back(event_id_hex.clone());
                        if seen_order.len() > SEEN_CAP {
                            if let Some(old) = seen_order.pop_front() {
                                seen_events.remove(&old);
                            }
                        }
                        // Sealed presence heartbeat from a contact: open + queue
                        // for the next presence tick. Never touches MLS.
                        if kind == alpha_transport::sealed::KIND_SEALED_PRESENCE {
                            let routes = rt_presence_routes.lock().map(|r| r.clone()).unwrap_or_default();
                            if let Some((tag, payload)) = transport_arc.open_presence(&event, |t| routes.get(t).map(|(_, k)| *k)) {
                                if let (Some((owners, _)), Ok(json)) = (routes.get(&tag), String::from_utf8(payload)) {
                                    if let Ok(mut inbox) = rt_presence_inbox.lock() {
                                        for npub_hex in owners {
                                            if inbox.len() < 1024 {
                                                inbox.push((npub_hex.clone(), json.clone()));
                                            }
                                        }
                                    }
                                }
                            }
                            continue;
                        }
                        // Gift wraps pass through; sealed group events are opened
                        // with our epoch keys; own echoes and foreign events drop.
                        let raw_event = match transport_arc.raw_from_event(&event, relay_url.as_str()) {
                            Some(r) => r,
                            None => continue,
                        };

                        // CRITICAL FIX: skip our OWN kind-443 Application
                        // messages in realtime too. Relays echo our published
                        // messages back to us; MLS rejects them with
                        // CannotDecryptOwnMessage, wasting CPU + blocking locks.
                        // (The catch-up path has the same guard, but filtering
                        // here avoids even building the RawMlsEvent + taking
                        // the sync_manager lock.)

                        // Decrypt + process under brief lock contention. Locks
                        // are released before firing callbacks (which cross
                        // the FFI boundary and may block on UI threads).
                        let processed = {
                            // try_lock, NOT lock: if catch_up holds sync_manager (it's
                            // inside its network fetch), blocking THIS listener thread on
                            // a std Mutex is what cascaded into a full runtime freeze
                            // (all workers blocked → even tokio's timer couldn't run →
                            // the 20s fetch timeout never fired → permanent silence).
                            // Skip instead; the in-flight catch-up delivers this event
                            // (last_event_at was already bumped above, so no false STALE).
                            let mut sync_guard = match sync_manager.try_lock() {
                                Ok(g) => g,
                                Err(crate::lock_track::TryLockError::WouldBlock) => {
                                    tracing::debug!("realtime: sync_manager busy (catch-up running) — skipping event, catch-up will cover");
                                    continue;
                                }
                                Err(crate::lock_track::TryLockError::Poisoned(e)) => {
                                    tracing::error!("sync_manager poisoned: {}", e);
                                    continue;
                                }
                            };
                            let manager = match sync_guard.as_mut() {
                                Some(m) => m,
                                None => continue,
                            };
                            let _t_lock_wait = std::time::Instant::now();
                            let mut mls = match mls_session.lock() {
                                Ok(g) => {
                                    let w = _t_lock_wait.elapsed().as_millis();
                                    if w > 100 {
                                        tracing::warn!(target: "TRANSPORT", "MLS_LOCK_WAIT site=rt_recv wait_ms={}", w);
                                    }
                                    g
                                }
                                Err(e) => {
                                    tracing::error!("mls_session poisoned: {}", e);
                                    continue;
                                }
                            };
                            let _t_lock_held = std::time::Instant::now();
                            let id_guard = match identity.lock() {
                                Ok(g) => g,
                                Err(e) => {
                                    tracing::error!("identity poisoned: {}", e);
                                    continue;
                                }
                            };
                            let id = match id_guard.as_ref() {
                                Some(i) => i,
                                None => continue,
                            };
                            let keys = id.nostr().keypair().keys().clone();
                            drop(id_guard);

                            tracing::warn!(target: "TRANSPORT", "RT_PROCESS_START kind={}", kind);
                            let _r = manager.process_missed_events(vec![raw_event], &mut mls, &keys);
                            tracing::warn!(target: "TRANSPORT", "RT_PROCESS_DONE");
                            let _held = _t_lock_held.elapsed().as_millis();
                            if _held > 100 {
                                tracing::warn!(target: "TRANSPORT", "MLS_LOCK_HELD site=rt_recv held_ms={}", _held);
                            }
                            _r
                        };
                        // A processed commit / welcome may have added epoch keys:
                        // persist them and refresh the subscription with the new
                        // tags (its 120 s look-back picks up anything just sent).
                        if sync_transport_ring(&rt_db, &transport_arc, &rt_ring_synced) {
                            let _ = transport_arc.subscribe_mls_and_giftwrap(&rt_self_pk).await;
                        }
                        let t_processed_ms = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_millis()).unwrap_or(0);

                        match processed {
                            Ok(events) => {
                                // Persist MLS state after any successful receive
                                // (Application/Commit/Welcome all mutate the ratchet).
                                if !events.is_empty() {
                                    let snapshot = match mls_session.lock() {
                                        Ok(g) => g.snapshot_storage(),
                                        Err(_) => Vec::new(),
                                    };
                                    if !snapshot.is_empty() {
                                        let conn = rt_db.connection();
                                        if let Err(e) = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snapshot) {
                                            tracing::warn!("realtime persist: save_snapshot failed: {}", e);
                                        }
                                    }
                                }
                                tracing::info!(
                                    target: "DELIVERY_TRACE",
                                    "PROCESSED event_id={} count={} dt_ms={}",
                                    event_id_hex, events.len(),
                                    t_processed_ms.saturating_sub(t_relay_in_ms)
                                );
                                // What arrived gets its "delivered" back — one receipt per chat.
                                send_delivered(&rt_db, &mls_session, &identity, &transport_arc, received_by_chat(&events));
                                for ev in events {
                                    match ev {
                                        ProcessedEvent::DeliveredReceived { chat_id, sender_id, message_ids } => {
                                            apply_delivered(&rt_db, &mls_session, &callback, &chat_id, &sender_id, &message_ids);
                                        }
                                        ProcessedEvent::NewMessage { chat_id, message, .. } => {
                                            let json = serde_json::to_string(&message).unwrap_or_default();
                                            let t_cb_ms = std::time::SystemTime::now()
                                                .duration_since(std::time::UNIX_EPOCH)
                                                .map(|d| d.as_millis()).unwrap_or(0);
                                            tracing::info!(
                                                target: "DELIVERY_TRACE",
                                                "CALLBACK_MSG event_id={} chat_id={} t_ms={} total_dt_ms={}",
                                                event_id_hex, chat_id, t_cb_ms,
                                                t_cb_ms.saturating_sub(t_relay_in_ms)
                                            );
                                            callback.on_message_received(&chat_id, &json);
                                        }
                                        // Transport directory — applied by
                                        // the core, never surfaced outside.
                                        ProcessedEvent::DirectoryReceived {
                                            chat_id, sender_id, version, revision, entries,
                                        } => {
                                            if let Ok(gid) = hex::decode(&chat_id) {
                                                apply_group_directory_raw(
                                                    &rt_db, &rt_mls, &rt_identity,
                                                    &gid, &sender_id, version, revision, &entries,
                                                );
                                            }
                                        }
                                        ProcessedEvent::SecretChatSignal { chat_id, sender_id, invite_id, step, data } => {
                                            let ctx = secret_chat::Ctx {
                                                db: &rt_db, mls: &rt_mls, identity: &rt_identity,
                                                transport: &transport_arc, callback: &callback,
                                            };
                                            secret_chat::on_signal(&ctx, &chat_id, &sender_id, &invite_id, &step, &data);
                                        }
                                        ProcessedEvent::NewChat { group_id } => {
                                            callback.on_chat_created(&group_id, "group");
                                        }
                                        ProcessedEvent::MemberAdded { chat_id, member } => {
                                            callback.on_member_added(&chat_id, &member);
                                        }
                                        ProcessedEvent::MemberRemoved { chat_id, member } => {
                                            callback.on_member_removed(&chat_id, &member);
                                        }
                                        ProcessedEvent::EpochAdvanced { chat_id, new_epoch } => {
                                            tracing::debug!("Realtime: chat {} epoch → {}", chat_id, new_epoch);
                                        }
                                        ProcessedEvent::ReadSignalReceived { target_id, .. } => {
                                            // Sender's check turns double-green the moment the
                                            // peer's ReadSignal lands — no reload needed.
                                            callback.on_delivery_update(&target_id, "read");
                                        }
                                        ProcessedEvent::ChatStateReceived { chat_id, sender_id, state, thread, age_ms } => {
                                            tracing::info!(
                                                target: "DELIVERY_TRACE",
                                                "CALLBACK_CHAT_STATE event_id={} chat_id={} state={} t_ms={}",
                                                event_id_hex, chat_id, state,
                                                std::time::SystemTime::now()
                                                    .duration_since(std::time::UNIX_EPOCH)
                                                    .map(|d| d.as_millis()).unwrap_or(0)
                                            );
                                            let own = own_sig_raw(&identity, &chat_id);
                                            if let Some(state) = crate::rooms::route(&own, &chat_id, &sender_id, &state) {
                                                callback.on_chat_state(&chat_id, &sender_id, &state, &thread, age_ms);
                                            }
                                        }
                                        ProcessedEvent::ProfileUpdated { sender_id, avatar_path, avatar_ref, avatar_dl_hash, cover_ref, cover_dl_hash, video_ref, video_dl_hash } => {
                                            tracing::info!(
                                                "Realtime: ProfileUpdated from {} avatar='{}' dl_avatar={} dl_cover={}",
                                                sender_id, avatar_path, !avatar_ref.is_empty(), !cover_ref.is_empty(),
                                            );
                                            callback.on_peer_profile_updated(&sender_id);
                                            if !avatar_ref.is_empty() || !cover_ref.is_empty() || !video_ref.is_empty() {
                                                runtime().spawn(download_peer_profile_media(
                                                    Arc::clone(&rt_db), Arc::clone(&rt_media), callback.clone(),
                                                    rt_avatars.clone(), Arc::clone(&rt_prof_sem),
                                                    sender_id.clone(), avatar_ref, avatar_dl_hash, cover_ref, cover_dl_hash,
                                                    video_ref, video_dl_hash,
                                                ));
                                            }
                                        }
                                        ProcessedEvent::ChatMetadataUpdated { chat_id } => {
                                            tracing::info!("Realtime: ChatMetadataUpdated for {}", chat_id);
                                            callback.on_chat_metadata_updated(&chat_id);
                                        }
                                        ProcessedEvent::MemberBanned { chat_id, target_pubkey, reason } => {
                                            if who_in_raw(&identity, &chat_id).map(|(h, _)| h) == Some(target_pubkey) {
                                                apply_ban_raw(&rt_db, &mls_session, &chat_id, &reason);
                                            }
                                            callback.on_chat_metadata_updated(&chat_id);
                                        }
                                        ProcessedEvent::ChatDisbanded { chat_id } => {
                                            apply_disband_raw(&rt_db, &mls_session, &chat_id);
                                            callback.on_chat_metadata_updated(&chat_id);
                                        }
                                        ProcessedEvent::ChatDeletedByPeer { chat_id } => {
                                            wipe_direct_raw(&rt_db, &mls_session, &chat_id);
                                            callback.on_chat_metadata_updated(&chat_id);
                                        }
                                        ProcessedEvent::ProfileRequested { sender_id, chat_id } => {
                                            tracing::info!(
                                                "Realtime: ProfileRequested from {} in chat {}",
                                                sender_id, chat_id,
                                            );
                                            // Member Profile Sync: reply live with our profile so
                                            // names/avatars fill in without waiting for a restart.
                                            reply_profile_into_chat_raw(
                                                &rt_db, &mls_session, &identity,
                                                (*transport_arc).clone(), &chat_id,
                                            );
                                        }
                                        ProcessedEvent::RoleSyncRequested { chat_id } => {
                                            // Pull-based role sync: owner replies with a snapshot +
                                            // re-broadcasts current metadata (group avatar/desc) so
                                            // a restored member pulls fresh state.
                                            publish_role_snapshot_raw(
                                                &rt_db, &mls_session, &identity,
                                                (*transport_arc).clone(), &chat_id, false,
                                            );
                                            broadcast_current_metadata_raw(
                                                &rt_db, &mls_session, &identity,
                                                (*transport_arc).clone(), &chat_id,
                                            );
                                        }
                                        ProcessedEvent::JoinRequested { chat_id, requester_npub, token } => {
                                            if devices_token(&token) {
                                                callback.on_devices_event("inbox");
                                            } else if AlphaMessengerCore::claim_join_request(&rt_db, &chat_id, &token, &requester_npub) {
                                                callback.on_join_requested(&chat_id, &requester_npub, &token);
                                            }
                                        }
                                        ProcessedEvent::DeviceSync => callback.on_devices_event("inbox"),
                                        ProcessedEvent::MemberLeaveRequested { chat_id, leaver_pubkey } => {
                                            let npub = nostr::PublicKey::from_hex(&leaver_pubkey).ok()
                                                .and_then(|p| nostr::nips::nip19::ToBech32::to_bech32(&p).ok())
                                                .unwrap_or(leaver_pubkey);
                                            callback.on_member_leave_requested(&chat_id, &npub);
                                        }
                                        ProcessedEvent::ForkDetected { chat_id, current_epoch, incoming_epoch, author } => {
                                            tracing::warn!(
                                                target: "MLS_DIAG",
                                                "ForkDetected (realtime) chat={} cur={} incoming={} author={} — recovery will run on next start_sync",
                                                chat_id, current_epoch, incoming_epoch, author,
                                            );
                                        }
                                        ProcessedEvent::DeliveryAckReceived { chat_id, sender_id, acker_id, upto_seq } => {
                                            // Record the ACK if it's for OUR messages.
                                            // Our leaf in THIS chat (a persona's in an anonymous channel).
                                            let own_sig = hex::decode(&chat_id).ok()
                                                .and_then(|g| mls_session.lock().ok().map(|m| hex::encode(m.signer_public_for(&g))))
                                                .unwrap_or_default();
                                            if !own_sig.is_empty() && sender_id == own_sig {
                                                let conn = rt_db.connection();
                                                let _ = alpha_database::delivery::DeliveryRepo::record_ack(
                                                    &conn, &chat_id, &acker_id, upto_seq,
                                                );
                                                tracing::info!(
                                                    target: "DELIVERY",
                                                    "ACK_RECORDED chat={} acker={} upto_seq={}",
                                                    chat_id, acker_id, upto_seq,
                                                );
                                                // Outbox prune is done by the periodic timer; no
                                                // need to recompute here (avoids needing the member
                                                // list under the realtime lock).
                                            }
                                        }
                                        ProcessedEvent::ResendRequested { chat_id, sender_id, requester_id: _, from_seq, to_seq } => {
                                            // If the request is for OUR messages, queue it
                                            // for the delivery-resend timer to fulfill (re-publish
                                            // from our outbox). Using the in-memory queue keeps the
                                            // realtime path lock-free and lets the timer batch.
                                            // Our leaf in THIS chat (a persona's in an anonymous channel).
                                            let own_sig = hex::decode(&chat_id).ok()
                                                .and_then(|g| mls_session.lock().ok().map(|m| hex::encode(m.signer_public_for(&g))))
                                                .unwrap_or_default();
                                            if !own_sig.is_empty() && sender_id == own_sig {
                                                if let Ok(mut q) = rt_pending_resends.lock() {
                                                    q.push((chat_id.clone(), from_seq, to_seq));
                                                    tracing::info!(
                                                        target: "DELIVERY",
                                                        "RESEND_QUEUED chat={} range=[{}..{}]",
                                                        chat_id, from_seq, to_seq,
                                                    );
                                                }
                                            }
                                        }
                                        ProcessedEvent::DuplicateReceived { chat_id } => {
                                            tracing::debug!(target: "DELIVERY", "DUP (realtime) chat={}", chat_id);
                                        }
                                    }
                                }
                            }
                            Err(e) => {
                                tracing::warn!("Realtime: failed to process incoming event: {}", e);
                            }
                        }
                    }
                    Ok(RelayPoolNotification::Shutdown) => {
                        tracing::info!("Realtime listener: pool shutdown — exiting");
                        break 'supervisor;
                    }
                    // A relay CLOSED our live subscription (NIP-01): note why —
                    // "rate-limited"/"blocked" pauses that relay for every kind of
                    // request — and have the heartbeat ask again later, not now.
                    Ok(RelayPoolNotification::Message {
                        relay_url,
                        message: alpha_transport::RelayMessage::Closed { subscription_id, message },
                    }) => {
                        tracing::warn!(target: "TRANSPORT", "RELAY_CLOSED {} sub={} reason={}", relay_url, subscription_id, message);
                        if transport_arc.note_subscription_closed(&relay_url.to_string(), &subscription_id.to_string(), &message) {
                            if let Ok(mut s) = rt_resub.lock() { s.insert(relay_url.to_string()); }
                        }
                    }
                    Ok(_) => {} // message / other notifications — ignore
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        // Recoverable: we stay in the loop. Backlog beyond the
                        // channel capacity was dropped — catch-up backstops it.
                        tracing::warn!("Realtime listener lagged behind by {} events", n);
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                        tracing::warn!("Realtime listener: channel closed — restarting via supervisor");
                        break;
                    }
                }
            }
            tracing::warn!("Realtime listener stopped → restart in 2s");
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            }
            };
            tokio::pin!(listener);
            tokio::select! {
                _ = &mut listener => {}
                _ = statuses => listener.await,
            }
        });

        {
            let mut guard = self.realtime_handle.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            // Abort any previous listener (defensive: shouldn't happen if stop_sync was called).
            if let Some(prev) = guard.take() {
                prev.abort();
            }
            *guard = Some(handle);
        }

        // 5. Transport heartbeat. While the app is open the OS may not
        //    fire a network event when a socket silently dies (subscription goes
        //    quiet, publish stalls). Ping relays periodically; on sustained loss
        //    downgrade → DEGRADED → DISCONNECTED → force a clean reconnect.
        let hb_transport = Arc::clone(&self.transport);
        let hb_pubkey = self_pubkey;
        let hb_last_event = Arc::clone(&self.last_event_at);
        let hb_last_reconnect = Arc::clone(&self.last_reconnect_at);
        let hb_reconnect_in_progress = Arc::clone(&self.reconnect_in_progress);
        // Captured so the heartbeat can signal the Android layer to run
        // catchUp + outbox replay when it detects a stale subscription.
        let hb_callback = self.callback.clone();
        let hb_db = Arc::clone(&self.db);
        let hb_ring_synced = Arc::clone(&self.ring_synced);
        let hb_beat = Arc::clone(&self.heartbeat_at);
        let hb_resub = Arc::clone(&self.resubscribe_wanted);
        let hb_handle: JoinHandle<()> = rt.spawn(async move {
            let mut misses: u32 = 0;
            // Silence is NOT a failure: a quiet account simply gets no events.
            // (Escalating on silence — catch-up every 25 s, resubscribe every
            // 60 s, reconnect every 90 s — sent ~10 REQ per relay per 30 s while
            // idle and got us rate-limited and banned.) Liveness comes from the
            // pool's own sockets: nostr-sdk pings each WebSocket and reconnects
            // a dead one itself, replaying our subscription. We step in only
            // when NO relay is connected for a while, and pull the missed events
            // once when the connection comes back.
            let mut hb_ticks: u32 = 0;
            let mut was_connected = true;
            // Every network wait of the heartbeat has a deadline: one that never
            // returned once froze this task — and with it all receiving — for
            // hours, silently (2026-10-13, phone 2).
            const HB_STEP: std::time::Duration = std::time::Duration::from_secs(20);
            tracing::info!(target: "TRANSPORT", "heartbeat started (30s)");
            loop {
                hb_beat.store(chrono::Utc::now().timestamp(), std::sync::atomic::Ordering::Relaxed);
                let wait = if misses == 0 { 30 } else { 10 };
                tokio::time::sleep(std::time::Duration::from_secs(wait)).await;
                hb_beat.store(chrono::Utc::now().timestamp(), std::sync::atomic::Ordering::Relaxed);
                let connected = tokio::time::timeout(HB_STEP, hb_transport.is_connected()).await.unwrap_or(false);
                let _ = tokio::time::timeout(HB_STEP, hb_transport.revive_stuck_relays()).await;

                // Epoch keys added by any path (e.g. a Welcome joined during a
                // background catch-up): persist and subscribe to the new tags.
                if connected && sync_transport_ring(&hb_db, &hb_transport, &hb_ring_synced) {
                    let _ = tokio::time::timeout(HB_STEP, hb_transport.subscribe_mls_and_giftwrap(&hb_pubkey)).await;
                }
                // Secret chats: quiet ones sleep, visit, wake when opened.
                secret_chat::plan_tick(&hb_db, &hb_transport, &hb_pubkey);

                // NETWORK RESTORED → wake the queue at once. Without this a send
                // that backed off to a 10-minute window would sit there for ten
                // minutes after the user's Wi-Fi came back, which reads as "the
                // app is broken". The backoff protects the runtime; it must not
                // punish the user once the network is actually there.
                if connected && !was_connected {
                    // Back after an outage: wake the send queue and pull what was
                    // missed — once, here, instead of on a timer.
                    tracing::info!(target: "TRANSPORT", "relay connection restored — waking pending queue, one catch-up");
                    PENDING_WAKE.store(true, std::sync::atomic::Ordering::Relaxed);
                    hb_callback.on_notification_received();
                }
                was_connected = connected;
                hb_ticks += 1;
                // Every 5 minutes: which relays have a live socket (diagnostics only).
                if hb_ticks % 10 == 0 {
                    let up: Vec<String> = hb_transport.relay_statuses().await.into_iter()
                        .map(|s| format!("{}={}", s.url.trim_start_matches("wss://"), if s.connected { "up" } else { "down" }))
                        .collect();
                    tracing::info!(target: "TRANSPORT", "RELAY_STATS {}", up.join(" "));
                }
                if alpha_database::peer_relays::take_changed() {
                    relay_routes::refresh(&hb_db, &hb_transport);
                }
                // A relay closed our subscription (it asked us to slow down, or
                // restarted): ask again once it is no longer paused. The same
                // subscription id replaces, never adds, a REQ on every relay.
                if connected {
                    let paused: std::collections::HashSet<String> = hb_transport.paused_relays()
                        .into_iter().map(|(u, _)| u).collect();
                    let mut due: Vec<String> = hb_resub.lock().map(|mut s| {
                        let due: Vec<String> = s.iter()
                            .filter(|u| !paused.contains(&u.trim_end_matches('/').to_ascii_lowercase()))
                            .cloned().collect();
                        for u in &due { s.remove(u); }
                        due
                    }).unwrap_or_default();
                    // Held back while paused, or answered AUTH just now.
                    for u in hb_transport.take_resub_later() { if !due.contains(&u) { due.push(u); } }
                    if !due.is_empty() {
                        tracing::info!(target: "TRANSPORT", "resubscribing on {:?} (they closed our subscription)", due);
                        let _ = tokio::time::timeout(HB_STEP, hb_transport.subscribe_mls_and_giftwrap_on(&hb_pubkey, Some(due))).await;
                    }
                }
                let _ = &hb_last_event;
                if connected {
                    if misses > 0 {
                        tracing::info!(target: "TRANSPORT", "heartbeat recovered after {} miss(es)", misses);
                    }
                    misses = 0;
                } else if !hb_transport.network_available() {
                    // No network: nothing to repair; the OS tells us when it is back.
                    misses = 0;
                } else {
                    misses += 1;
                    tracing::warn!(
                        target: "TRANSPORT",
                        "heartbeat miss #{} — no relay connected", misses,
                    );
                    if misses == 2 {
                        tracing::warn!(target: "TRANSPORT", "relays DEGRADED");
                    }
                    if misses >= 4 {
                        tracing::warn!(target: "TRANSPORT", "relays DISCONNECTED → force reconnect");
                        // Serialise with any FFI-driven force_reconnect so the two
                        // don't tear the pool down concurrently.
                        use std::sync::atomic::Ordering;
                        if hb_reconnect_in_progress
                            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                            .is_ok()
                        {
                            let _ = tokio::time::timeout(HB_STEP * 3, hb_transport.force_reconnect()).await;
                            let _ = tokio::time::timeout(HB_STEP, hb_transport.subscribe_mls_and_giftwrap(&hb_pubkey)).await;
                            hb_reconnect_in_progress.store(false, Ordering::Release);
                            if let Ok(mut g) = hb_last_reconnect.lock() { *g = std::time::Instant::now(); }
                        } else {
                            // FFI reconnect already running — just re-assert the sub.
                            let _ = tokio::time::timeout(HB_STEP, hb_transport.subscribe_mls_and_giftwrap(&hb_pubkey)).await;
                        }
                        misses = 0;
                    }
                }
            }
        });
        {
            let mut guard = self.heartbeat_handle.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if let Some(prev) = guard.take() { prev.abort(); }
            *guard = Some(hb_handle);
        }

        tracing::info!("Sync engine started (realtime + catch-up + heartbeat)");
        Ok(())
    }

    /// How the receiving side is doing, for the service's watchdog (JSON):
    /// seconds since the heartbeat's last tick (-1 = never ran), since the
    /// last realtime event, since the last catch-up, and paused relays.
    pub fn sync_health_json(&self) -> String {
        let now = chrono::Utc::now().timestamp();
        let age = |at: i64| if at <= 0 { -1 } else { now - at };
        let silent = self.last_event_at.lock().map(|g| g.elapsed().as_secs() as i64).unwrap_or(-1);
        let paused: Vec<serde_json::Value> = self.transport.paused_relays().into_iter()
            .map(|(u, s)| serde_json::json!({"url": u, "secs": s}))
            .collect();
        // Live sockets now (local state, no network): lets the app skip tearing
        // down healthy connections when it only came back to the foreground.
        let statuses = runtime().block_on(async {
            tokio::time::timeout(std::time::Duration::from_secs(2), self.transport.relay_statuses()).await
        }).unwrap_or_default();
        // A parked relay is known to be down: it alone does not make the rest unhealthy.
        let parked: std::collections::HashSet<String> = self.transport.parked_relays().into_iter().collect();
        let key = |u: &str| u.trim_end_matches('/').to_ascii_lowercase();
        let enabled = statuses.iter().filter(|s| s.enabled && !parked.contains(&key(&s.url))).count();
        let connected = statuses.iter().filter(|s| s.enabled && s.connected).count();
        serde_json::json!({
            "heartbeat_age": age(self.heartbeat_at.load(std::sync::atomic::Ordering::Relaxed)),
            "realtime_silent": silent,
            "catchup_age": age(self.catchup_at.load(std::sync::atomic::Ordering::Relaxed)),
            "paused": paused,
            "relays": enabled,
            "connected": connected,
            "parked": parked.len(),
        }).to_string()
    }

    /// Stop the sync engine.
    pub fn stop_sync(&self) -> Result<(), MessengerError> {
        // Abort the realtime listener task before tearing down the relay
        // connection so it doesn't observe spurious errors during disconnect.
        {
            let mut guard = self.realtime_handle.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if let Some(handle) = guard.take() {
                handle.abort();
            }
        }
        {
            let mut guard = self.heartbeat_handle.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if let Some(handle) = guard.take() { handle.abort(); }
        }

        let rt = runtime();
        rt.block_on(self.transport.disconnect())
            .map_err(|e| MessengerError::NetworkError { description: format!("transport disconnect failed: {}", e) })?;

        let mut guard = self.sync_manager.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        *guard = None;

        tracing::info!("Sync engine stopped");
        Ok(())
    }

    /// Perform a catch-up and return the number of fetched events.
    ///
    /// Each pass can only fetch epochs whose keys we already hold; a commit
    /// processed in a pass reveals the next epoch's routing tag, so passes are
    /// repeated (bounded) until no new epoch keys appear.
    pub fn catch_up(&self) -> Result<u64, MessengerError> {
        let _run = self.sync_gauge.begin();
        let mut total = 0u64;
        for _ in 0..4 {
            let before = self.transport.key_ring().version();
            total += self.catch_up_once()?;
            if sync_transport_ring(&self.db, &self.transport, &self.ring_synced) {
                self.spawn_resubscribe();
            }
            if self.transport.key_ring().version() == before {
                break;
            }
        }
        Ok(total)
    }

    fn catch_up_once(&self) -> Result<u64, MessengerError> {
        let rt = runtime();
        // FETCH OUTSIDE THE LOCK (the proper fix for the old "lock held across
        // network I/O" deadlock). Hold sync_manager ONLY to read checkpoints +
        // clone the transport (cheap, local, no network), then RELEASE it before
        // the network round-trip so the realtime listener NEVER blocks on this std
        // Mutex during a slow/flapping relay. Safe: prepare_catch_up only READS
        // sync_state; the checkpoint advance happens later in process_missed_events
        // and is forward-only, so a concurrent realtime advance in the unlocked
        // window can't be clobbered, and event-id dedup prevents double-apply.
        let (cu_transport, cu_relay_since, cu_self_pk) = {
            let guard = self.sync_manager.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let manager = guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "sync not started".to_string() })?;
            rt.block_on(manager.prepare_catch_up())
        }; // sync_manager UNLOCKED here — realtime keeps flowing during the fetch.
        // A relay that asked us to slow down is left alone until its pause ends:
        // its watermark stays where it is, so nothing is lost — it is fetched
        // on the first catch-up after the pause.
        let paused: std::collections::HashSet<String> = cu_transport.paused_relays()
            .into_iter().map(|(u, _)| u)
            .chain(cu_transport.parked_relays())
            .collect();
        let cu_relay_since: Vec<(String, u64)> = cu_relay_since.into_iter()
            .filter(|(u, _)| !paused.contains(&u.trim_end_matches('/').to_ascii_lowercase()))
            .collect();
        tracing::warn!(target: "TRANSPORT", "CATCHUP_STEP1 prepared relays={} (paused {})", cu_relay_since.len(), paused.len());

        let events = rt
            .block_on(async {
                tokio::time::timeout(
                    std::time::Duration::from_secs(20),
                    CatchUpManager::fetch_events(&cu_transport, cu_relay_since, cu_self_pk),
                ).await
            })
            .map_err(|_| MessengerError::NetworkError { description: "catch-up fetch timed out (20s)".to_string() })?
            .map_err(|e| MessengerError::NetworkError { description: format!("catch-up failed: {}", e) })?;

        let count = events.len() as u64;
        tracing::warn!(target: "TRANSPORT", "CATCHUP_STEP2 fetched events={}", count);

        // Re-acquire the lock ONLY to process the fetched events.
        let mut guard = self.sync_manager.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        tracing::warn!(target: "TRANSPORT", "CATCHUP_STEP2b got sync_manager, locking mls");
        let manager = guard.as_mut()
            .ok_or_else(|| MessengerError::InternalError { description: "sync not started".to_string() })?;
        let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        tracing::warn!(target: "TRANSPORT", "CATCHUP_STEP3 locked, processing {} events", count);
        let processed = {
            let identity_guard = self.identity.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let identity = identity_guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?;
            let nostr_keys = identity.nostr().keypair().keys();
            let processed = manager
                .process_missed_events(events, &mut mls, nostr_keys)
                .map_err(|e| MessengerError::InternalError { description: format!("event processing failed: {}", e) })?;
            tracing::warn!(target: "TRANSPORT", "CATCHUP_STEP4 processed={}", processed.len());

            // Persist MLS storage while we still hold the lock — cheap and
            // guarantees the freshly-applied receive state survives a kill.
            if !processed.is_empty() {
                let snapshot = mls.snapshot_storage();
                if !snapshot.is_empty() {
                    let conn = self.db.connection();
                    if let Err(e) = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snapshot) {
                        tracing::warn!("catch_up persist: save_snapshot failed: {}", e);
                    }
                }
            }
            processed
        };
        // CRITICAL: release sync_manager BEFORE dispatching callbacks. Some
        // callbacks re-enter core methods (broadcast_my_profile / respond_role_sync
        // on ProfileRequested / RoleSyncRequested) which re-lock this SAME
        // std::sync::Mutex → reentrant self-deadlock = hung forever (NOT caught by
        // the fetch timeout, which is why the runtime still froze after that fix).
        // Callbacks also call into Kotlin via JNI; holding the lock there blocked
        // the realtime listener. The dispatch loop needs only `self`, not
        // `manager`, so dropping the guard here is safe.
        // MLS goes too: receipts below re-lock it, and callbacks must run with no core lock.
        drop(mls);
        drop(guard);
        tracing::warn!(target: "TRANSPORT", "CATCHUP_STEP5 callbacks start={}", processed.len());

        // What arrived gets its "delivered" back — one receipt per chat.
        send_delivered(&self.db, &self.mls_session, &self.identity, &self.transport, received_by_chat(&processed));
        // Dispatch callbacks for processed events.
        for event in processed {
            match event {
                ProcessedEvent::NewMessage { chat_id, message, .. } => {
                    let json = serde_json::to_string(&message).unwrap_or_default();
                    self.callback.on_message_received(&chat_id, &json);
                }
                ProcessedEvent::DeliveredReceived { chat_id, sender_id, message_ids } => {
                    apply_delivered(&self.db, &self.mls_session, &self.callback, &chat_id, &sender_id, &message_ids);
                }
                // Transport directory: applied by the core, not surfaced —
                // it is not a chat event.
                ProcessedEvent::DirectoryReceived {
                    chat_id, sender_id, version, revision, entries,
                } => {
                    if let Ok(gid) = hex::decode(&chat_id) {
                        self.apply_group_directory(&gid, &sender_id, version, revision, &entries);
                    }
                }
                ProcessedEvent::SecretChatSignal { chat_id, sender_id, invite_id, step, data } => {
                    let ctx = secret_chat::Ctx {
                        db: &self.db, mls: &self.mls_session, identity: &self.identity,
                        transport: &self.transport, callback: &self.callback,
                    };
                    secret_chat::on_signal(&ctx, &chat_id, &sender_id, &invite_id, &step, &data);
                }
                ProcessedEvent::NewChat { group_id } => {
                    self.callback.on_chat_created(&group_id, "group");
                    // 8.8 v2 broadcast handled by Kotlin's chatCreated
                    // collector (it calls broadcast_my_profile_now).
                }
                ProcessedEvent::MemberAdded { chat_id, member } => {
                    self.callback.on_member_added(&chat_id, &member);
                }
                ProcessedEvent::MemberRemoved { chat_id, member } => {
                    self.callback.on_member_removed(&chat_id, &member);
                }
                ProcessedEvent::EpochAdvanced { chat_id, new_epoch } => {
                    tracing::info!("Chat {} advanced to epoch {}", chat_id, new_epoch);
                }
                ProcessedEvent::ReadSignalReceived { chat_id, target_id, read_at } => {
                    tracing::info!(
                        "ReadSignal received for message {} in chat {}: read_at={}",
                        target_id,
                        chat_id,
                        read_at
                    );
                    // Push the read receipt to the UI so the sender's check
                    // turns double-green immediately — without this the status
                    // only refreshed on the next full reload (e.g. on reply).
                    self.callback.on_delivery_update(&target_id, "read");
                }
                ProcessedEvent::ChatStateReceived { chat_id, sender_id, state, thread, age_ms } => {
                    let own = self.own_sig_in(&chat_id);
                    if let Some(state) = crate::rooms::route(&own, &chat_id, &sender_id, &state) {
                        self.callback.on_chat_state(&chat_id, &sender_id, &state, &thread, age_ms);
                    }
                }
                ProcessedEvent::ProfileUpdated { sender_id, avatar_path, avatar_ref, avatar_dl_hash, cover_ref, cover_dl_hash, video_ref, video_dl_hash } => {
                    tracing::info!(
                        "ProfileUpdated from {} avatar='{}' dl_avatar={} dl_cover={}",
                        sender_id, avatar_path, !avatar_ref.is_empty(), !cover_ref.is_empty(),
                    );
                    self.callback.on_peer_profile_updated(&sender_id);
                    if !avatar_ref.is_empty() || !cover_ref.is_empty() || !video_ref.is_empty() {
                        runtime().spawn(download_peer_profile_media(
                            Arc::clone(&self.db), Arc::clone(&self.media_pipeline), self.callback.clone(),
                            self.contact_avatars_dir.clone(), Arc::clone(&self.profile_dl_sem),
                            sender_id.clone(), avatar_ref, avatar_dl_hash, cover_ref, cover_dl_hash,
                            video_ref, video_dl_hash,
                        ));
                    }
                }
                ProcessedEvent::ChatMetadataUpdated { chat_id } => {
                    tracing::info!("ChatMetadataUpdated for chat {}", chat_id);
                    self.callback.on_chat_metadata_updated(&chat_id);
                }
                ProcessedEvent::MemberBanned { chat_id, target_pubkey, reason } => {
                    if self.own_hex_in(&chat_id) == target_pubkey {
                        apply_ban_raw(&self.db, &self.mls_session, &chat_id, &reason);
                    }
                    self.callback.on_chat_metadata_updated(&chat_id);
                }
                ProcessedEvent::ChatDisbanded { chat_id } => {
                    apply_disband_raw(&self.db, &self.mls_session, &chat_id);
                    self.callback.on_chat_metadata_updated(&chat_id);
                }
                ProcessedEvent::ChatDeletedByPeer { chat_id } => {
                    wipe_direct_raw(&self.db, &self.mls_session, &chat_id);
                    self.callback.on_chat_metadata_updated(&chat_id);
                }
                ProcessedEvent::ProfileRequested { sender_id, chat_id } => {
                    tracing::info!(
                        "ProfileRequested from {} in chat {}", sender_id, chat_id,
                    );
                    // Respond with our current profile so the peer pulls it.
                    if let Ok(p) = self.get_my_profile() {
                        let _ = self.broadcast_my_profile(&p.display_name, &p.bio, &p.avatar_path);
                    }
                }
                ProcessedEvent::RoleSyncRequested { chat_id } => {
                    // Owner answers the pull-based role-sync request once.
                    self.respond_role_sync(&chat_id);
                }
                ProcessedEvent::JoinRequested { chat_id, requester_npub, token } => {
                    if devices_token(&token) {
                        self.callback.on_devices_event("inbox");
                    } else if Self::claim_join_request(&self.db, &chat_id, &token, &requester_npub) {
                        self.callback.on_join_requested(&chat_id, &requester_npub, &token);
                    }
                }
                ProcessedEvent::DeviceSync => self.callback.on_devices_event("inbox"),
                ProcessedEvent::MemberLeaveRequested { chat_id, leaver_pubkey } => {
                    let npub = nostr::PublicKey::from_hex(&leaver_pubkey).ok()
                        .and_then(|p| nostr::nips::nip19::ToBech32::to_bech32(&p).ok())
                        .unwrap_or(leaver_pubkey);
                    self.callback.on_member_leave_requested(&chat_id, &npub);
                }
                ProcessedEvent::ForkDetected { chat_id, current_epoch, incoming_epoch, author } => {
                    tracing::warn!(
                        target: "MLS_DIAG",
                        "ForkDetected chat={} cur={} incoming={} author={} — attempting owner recovery",
                        chat_id, current_epoch, incoming_epoch, author,
                    );
                    // Feed the reliability controller.
                    self.reliability.record_fork();
                    if let Err(e) = self.recover_group_from_fork(&chat_id) {
                        tracing::warn!(
                            target: "MLS_DIAG",
                            "recover_group_from_fork for {} failed: {} — will retry on next sync",
                            chat_id, e,
                        );
                    }
                }
                ProcessedEvent::DeliveryAckReceived { chat_id, sender_id, acker_id, upto_seq } => {
                    self.handle_delivery_ack(&chat_id, &sender_id, &acker_id, upto_seq);
                }
                ProcessedEvent::ResendRequested { chat_id, sender_id, requester_id: _, from_seq, to_seq } => {
                    self.handle_resend_request(&chat_id, &sender_id, from_seq, to_seq);
                }
                ProcessedEvent::DuplicateReceived { chat_id } => {
                    tracing::debug!(target: "DELIVERY", "DUP (sync) chat={}", chat_id);
                }
            }
        }

        tracing::warn!(target: "TRANSPORT", "CATCHUP_STEP6 finished count={}", count);
        self.catchup_at.store(chrono::Utc::now().timestamp(), std::sync::atomic::Ordering::Relaxed);
        self.callback.on_sync_complete(count);
        Ok(count)
    }

    // --- Notifications ---

    /// Start listening for push notifications via ntfy.
    pub fn start_notifications(&self, ntfy_server_url: String) -> Result<(), MessengerError> {
        // Idempotent: tear down any existing wake service first. Without this,
        // every repeated call (startSync on foreground / service restart) spawned
        // a NEW ntfy subscribe loop while the old task kept running — N concurrent
        // long-poll subscriptions piling up overnight ("Subscribing to ntfy
        // topic" storm across many threads). stop_notifications aborts the task.
        {
            let has_existing = self
                .wake_service
                .lock()
                .map(|g| g.is_some())
                .unwrap_or(false);
            if has_existing {
                let _ = self.stop_notifications();
            }
        }

        // Remember the server so the send path can publish peer-wake pushes.
        if let Ok(mut g) = self.ntfy_server_url.lock() {
            *g = Some(ntfy_server_url.clone());
        }

        // Our private wake topic (derived from a secret shared only with contacts).
        let own_wake_topic = {
            let conn = self.db.connection();
            own_contact_secrets(&conn).1
        };
        let guard = self.identity.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let identity = guard
            .as_ref()
            .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?;

        let _ = identity;
        let topic = own_wake_topic;

        let ntfy = NtfyClient::new(&ntfy_server_url, &topic);
        let dispatcher = PushDispatcher::new(NtfyClient::new(&ntfy_server_url, &topic));
        let handler = NotificationHandler::new(dispatcher);

        // Create the wake channel. WakeService sends a token on every ntfy
        // push; the watcher task below receives it and fires the Android
        // callback, which triggers `forceReconnectAndCatchUp` on the Kotlin
        // side — the only path that correctly handles catchUp + outbox replay
        // without duplicating that logic here.
        let (wake_tx, mut wake_rx) = tokio::sync::mpsc::channel::<()>(4);
        let wake_service = WakeService::new(handler, ntfy, wake_tx);

        // Store the handler before moving the service.
        {
            let mut nh = self.notification_handler.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            *nh = Some(NotificationHandler::new(PushDispatcher::new(
                NtfyClient::new(&ntfy_server_url, &topic),
            )));
        }

        let rt = runtime();
        rt.block_on(wake_service.start())
            .map_err(|e| MessengerError::NetworkError { description: format!("wake service start failed: {}", e) })?;

        {
            let mut ws = self.wake_service.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            *ws = Some(wake_service);
        }

        // Watcher task: relay wake tokens to the Android callback.  Exits
        // automatically when the WakeService is dropped (the tx side closes
        // and wake_rx.recv() returns None).
        let ntfy_callback = self.callback.clone();
        rt.spawn(async move {
            while wake_rx.recv().await.is_some() {
                tracing::info!(target: "TRANSPORT", "ntfy wake → on_notification_received");
                ntfy_callback.on_notification_received();
            }
            tracing::debug!(target: "TRANSPORT", "ntfy watcher task exiting (channel closed)");
        });

        tracing::info!("Notification service started on topic: {}", topic);
        Ok(())
    }

    /// Stop listening for push notifications.
    pub fn stop_notifications(&self) -> Result<(), MessengerError> {
        let mut ws = self.wake_service.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        if let Some(service) = ws.take() {
            let rt = runtime();
            rt.block_on(service.stop())
                .map_err(|e| MessengerError::NetworkError { description: format!("wake service stop failed: {}", e) })?;
        }

        let mut nh = self.notification_handler.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        *nh = None;

        tracing::info!("Notification service stopped");
        Ok(())
    }

    // --- Callbacks ---

    /// Set the callback for receiving events.
    pub fn set_callback(&self, callback: Box<dyn crate::callbacks::AlphaMessengerCallback>) {
        self.callback.set_callback(callback);
    }

    // --- Group management ---

    /// Create a group chat (currently uses MlsSessionManager for a
    /// single-member group — add members with add_member afterwards).
    pub fn create_group(&self, name: String) -> Result<ChatInfo, MessengerError> {
        let t0 = std::time::Instant::now();
        tracing::info!("create_group: '{}'", name);
        let mut mls = self.mls_session.lock().map_err(|e| {
            tracing::warn!("create_group: mls_session lock failed: {}", e);
            MessengerError::InternalError { description: e.to_string() }
        })?;

        // Create a single-member MLS group. Other members can be added later.
        let group_id = mls
            .create_group()
            .map_err(|e| {
                tracing::warn!("create_group: MLS create_group failed: {}", e);
                MessengerError::CryptoError { description: format!("failed to create MLS group: {}", e) }
            })?;

        let chat_id = hex::encode(&group_id);
        let epoch = mls.group_info(&group_id)
            .map(|info| info.epoch)
            .unwrap_or(0);
        tracing::info!("create_group: MLS group created chat_id={} epoch={}", chat_id, epoch);

        // Read before the database is taken: identity is never locked inside it.
        let own_hex = self.identity.lock().ok()
            .and_then(|g| g.as_ref().map(|id| id.nostr().public_key_hex()));
        let conn = self.db.connection();
        let chat = new_chat(
            chat_id.clone(),
            alpha_database::models::ChatType::Group,
            Some(name.clone()),
            epoch as i64,
        );
        ChatRepo::insert(&conn, &chat)
            .map_err(|e| {
                tracing::warn!("create_group: ChatRepo::insert failed: {}", e);
                MessengerError::DatabaseError { description: format!("database error: {}", e) }
            })?;

        // MlsGroupRepo lifecycle: record group as active so leave_chat guard works.
        MlsGroupRepo::insert(&conn, &new_mls_group_row(chat_id.clone(), epoch as i64, 0, true))
            .map_err(|e| {
                tracing::warn!("create_group: MlsGroupRepo::insert failed: {}", e);
                MessengerError::DatabaseError { description: format!("mls_groups insert: {}", e) }
            })?;

        // Record the creator as group OWNER (role KV, no migration).
        if let Some(own_hex) = own_hex {
            alpha_database::group_roles::set_owner(&conn, &chat_id, &own_hex);
        }
        drop(conn);
        drop(mls);

        // Our other phones are in it from the start.
        if let Ok(me) = self.account_hex() {
            for kp in self.own_other_device_kps() {
                if let Err(e) = self.add_member_with(chat_id.clone(), me.clone(), vec![kp], true) {
                    tracing::warn!("create_group: our other phone not added: {}", e);
                }
            }
        }

        self.callback.on_chat_created(&chat_id, "group");
        tracing::info!("create_group: done '{}' in {}ms (chat_id={})", name, t0.elapsed().as_millis(), chat_id);

        Ok(chat_info_basic(chat_id, "group".to_string(), Some(name), epoch, chat.created_at))
    }

    /// Create a channel that hides its admins: we own it under a persona that
    /// exists for this channel only — its own MLS leaf and its own Nostr key.
    /// Nothing in the channel (leaf, directory, envelopes, invite links, roles)
    /// leads back to our account.
    pub fn create_group_anonymous(&self, name: String) -> Result<ChatInfo, MessengerError> {
        // The persona's leaf is signed by the persona's key, never by ours.
        let keys = nostr::Keys::generate();
        let npub_hex = keys.public_key().to_hex();
        let persona = alpha_mls::Persona::generate_bound(&keys)
            .map_err(|e| MessengerError::CryptoError { description: format!("persona: {}", e) })?;
        let persona_json = persona.to_json()
            .map_err(|e| MessengerError::CryptoError { description: format!("persona: {}", e) })?;
        let sig_hex = hex::encode(persona.public_key());

        let (group_id, epoch) = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let gid = mls.create_group_as(persona)
                .map_err(|e| MessengerError::CryptoError { description: format!("failed to create MLS group: {}", e) })?;
            let epoch = mls.group_info(&gid).map(|i| i.epoch).unwrap_or(0);
            (gid, epoch)
        };
        self.persist_mls_storage();
        let chat_id = hex::encode(&group_id);

        let created_at = {
            let conn = self.db.connection();
            alpha_database::personas::put(&conn, &alpha_database::personas::PersonaRow {
                chat_id: chat_id.clone(),
                nostr_sk: keys.secret_key().to_secret_hex(),
                nostr_pk: npub_hex.clone(),
                mls: persona_json,
                sig: sig_hex.clone(),
                joined: true,
            }).map_err(|e| MessengerError::DatabaseError { description: format!("persona: {}", e) })?;
            let chat = new_chat(
                chat_id.clone(),
                alpha_database::models::ChatType::Group,
                Some(name.clone()),
                epoch as i64,
            );
            ChatRepo::insert(&conn, &chat)
                .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
            MlsGroupRepo::insert(&conn, &new_mls_group_row(chat_id.clone(), epoch as i64, 0, true))
                .map_err(|e| MessengerError::DatabaseError { description: format!("mls_groups insert: {}", e) })?;
            // The persona owns the channel; its own leaf is bound to its own
            // address, so the directory is complete from the first member on.
            alpha_database::group_roles::set_owner(&conn, &chat_id, &npub_hex);
            let _ = KeyValueRepo::set(&conn, &Self::dir_key(&chat_id, &sig_hex), &npub_hex);
            let _ = KeyValueRepo::set(&conn, &Self::dir_epoch_key(&chat_id), &epoch.to_string());
            chat.created_at.clone()
        };
        self.refresh_persona_inboxes();

        self.callback.on_chat_created(&chat_id, "group");
        tracing::info!("create_group_anonymous: channel {} owned by a persona", chat_id);
        Ok(chat_info_basic(chat_id, "group".to_string(), Some(name), epoch, created_at))
    }

    /// High-level: add a member to an existing group chat by their npub.
    ///
    /// Workflow:
    /// 1. Parse npub/hex into a Nostr public key.
    /// 2. Fetch the peer's MLS KeyPackage from the relays.
    /// 3. Apply an MLS add commit, producing a `(commit, welcome)` pair.
    /// 4. Gift-wrap the Welcome to the new peer (so they auto-join via the
    ///    realtime listener — same path as `add_contact_by_npub`).
    /// 5. Publish the Commit as a public kind-443 event so existing members
    ///    advance their ratchet.
    /// 6. Persist MLS state to SQLCipher.
    ///
    /// This is the function the "Add participant" button in the group info
    /// screen should call. Without it, groups are functionally dead — they
    /// can be created locally but never include anyone else.
    pub fn add_member_by_npub(&self, chat_id: String, npub_or_hex: String) -> Result<(), MessengerError> {
        let npub_or_hex = self.accept_contact_code(&npub_or_hex);
        self.add_member_with(chat_id, npub_or_hex, Vec::new(), false)
    }

    /// [`add_member_by_npub`] with the member's KeyPackage already in hand
    /// (a join request brings one); `None` fetches a published one.
    /// `own_device`: the leaf is another phone of ours (we are `npub_or_hex`).
    pub(super) fn add_member_with(&self, chat_id: String, npub_or_hex: String, key_packages: Vec<Vec<u8>>, own_device: bool) -> Result<(), MessengerError> {
        use nostr::nips::nip19::FromBech32;

        let trimmed = npub_or_hex.trim();
        let peer_pk = if trimmed.starts_with("npub1") {
            nostr::PublicKey::from_bech32(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid npub: {}", e) })?
        } else {
            nostr::PublicKey::from_hex(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid hex pubkey: {}", e) })?
        };

        // Reject self-add (degenerate) — but not another phone of ours.
        if !own_device {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if let Some(ref id) = *guard {
                if id.nostr().public_key() == peer_pk {
                    return Err(MessengerError::InvalidArgument {
                        description: "cannot add yourself to a group you're already in".to_string(),
                    });
                }
            }
        }

        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        if !own_device { self.ensure_room_for_member(&group_id)?; }

        // FIX (epoch-fork) — designated sequencer: only the OWNER may issue
        // membership commits (Add/Remove/Update). MLS-over-Nostr has no
        // Delivery Service to order concurrent commits, so two members
        // committing at the same time produces a WrongEpoch fork that "kills"
        // the group (messages stop decrypting, history hides). Routing every
        // membership mutation through the single owner eliminates the fork at
        // the source. Non-owners get a clear error so the UI can tell them to
        // ask the owner. (The reactive `recover_group_from_fork` handles any
        // residual races, e.g. two devices of the same owner.)
        {
            let own_hex = self.own_hex_in(&chat_id);
            let conn = self.db.connection();
            let owner = alpha_database::group_roles::owner_of(&conn, &chat_id);
            if let Some(owner_pk) = owner {
                if owner_pk != own_hex {
                    return Err(MessengerError::InvalidArgument {
                        description: "only the group owner can add members — ask the owner to add this contact".to_string(),
                    });
                }
            }
            // No owner recorded (legacy group): allow the add but log, so we
            // don't brick groups created before the owner-role KV existed.
        }

        // 1. Fetch peer's KeyPackage — SYNCHRONOUS but with a short 2s timeout.
        // The old 8s timeout (2 attempts × 4s) froze the UI. Now 1 attempt × 2s.
        // Errors are surfaced to the user (not swallowed) so they know WHY the
        // add failed.
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let rt = runtime();
        // MLS KeyPackages are ONE-TIME: a distinct unused one for each of the
        // member's phones (several groups in a row must not reuse one).
        let kps: Vec<Vec<u8>> = if key_packages.is_empty() { self.member_key_packages(&peer_pk)? } else { key_packages };
        let direct = {
            let conn = self.db.connection();
            ChatRepo::get(&conn, &chat_id).ok().flatten().map(|c| c.chat_type.as_str() != "group").unwrap_or(false)
        };

        // Membership BEFORE the add — the newcomer's leaf is found by diff.
        let members_before = self.member_sig_keys(&group_id);

        // 2. Apply add commit. Returns (commit, welcome) — both TLS-serialized.
        let (commit_bytes, welcome_bytes, epoch) = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let (c, w) = mls.add_members_to_group(&group_id, &kps)
                .map_err(|e| MessengerError::CryptoError { description: format!("MLS add_member: {}", e) })?;
            let e = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
            (c, w, e)
        };

        // The invitee's address is certain — the KeyPackage was just fetched
        // by it. Without this record they stay unaddressable until their first
        // message, and personal envelopes need the address up front.
        self.bind_new_members(&group_id, &members_before, &peer_pk.to_hex());
        // Membership changed — send a snapshot to the new epoch: everyone in it,
        // the newcomer included, reads it like any group message.
        self.publish_group_directory(&group_id);

        // Persist immediately — ratchet just advanced. (Incremental UPSERT.)
        self.persist_mls_storage();

        // 3. Snapshot Nostr keys.
        let own_keys = self.keys_in(&chat_id)?;

        // 4. Gift-wrap Welcome to the new peer. Use `new_group` so the receiver
        //    builds a GROUP chat row, not a DM. Attach the chat name (v3
        //    envelope) so the receiver shows the real title.
        let chat_title = {
            let conn = self.db.connection();
            ChatRepo::list(&conn).ok()
                .and_then(|chats| chats.into_iter().find(|c| c.id == chat_id))
                .and_then(|c| c.name)
        };
        // A direct chat stays one for the phone of ours it brings in.
        let mut welcome_envelope = if direct {
            MlsEnvelope::new(MlsMessageType::Welcome, group_id.clone(), epoch, welcome_bytes)
        } else {
            MlsEnvelope::new_group(MlsMessageType::Welcome, group_id.clone(), epoch, welcome_bytes)
        };
        if let Some(ref title) = chat_title {
            welcome_envelope = welcome_envelope.with_name(title.clone());
        }
        let welcome_keys = own_keys.clone();
        let welcome_bridge = bridge.clone();
        let welcome_peer = peer_pk;
        let welcome_chat = chat_id.clone();
        // Retry up to 3× with backoff so a momentary relay hiccup doesn't lose
        // the Welcome forever. In-memory only.
        rt.spawn(async move {
            for attempt in 1..=3u32 {
                match welcome_bridge.send_mls_message(&welcome_keys, &welcome_envelope, Some(&welcome_peer), None).await {
                    Ok(eid) => {
                        tracing::info!("Published Welcome for group {} to {}: event_id={} (attempt {})", welcome_chat, welcome_peer, eid, attempt);
                        return;
                    }
                    Err(e) => {
                        tracing::warn!("Welcome publish attempt {} for {} failed: {}", attempt, welcome_peer, e);
                        tokio::time::sleep(std::time::Duration::from_secs(2 * attempt as u64)).await;
                    }
                }
            }
            tracing::error!("Welcome publish failed permanently for group {} to {}", welcome_chat, welcome_peer);
        });

        // FIX (Welcome delivery): wake the new member over ntfy.
        self.send_peer_wake_now(std::slice::from_ref(&peer_pk.to_hex()));

        // 5. Broadcast Commit as public kind-443 so existing group members
        //    advance their ratchet (we already advanced ours via add_member).
        let commit_envelope = MlsEnvelope::new(
            MlsMessageType::Commit,
            group_id.clone(),
            epoch,
            commit_bytes,
        );
        let commit_keys = own_keys;
        let commit_bridge = bridge;
        let commit_chat = chat_id.clone();
        rt.spawn(async move {
            match commit_bridge.send_mls_message(&commit_keys, &commit_envelope, None, None).await {
                Ok(eid) => tracing::info!("Broadcast Commit for group {}: event_id={}", commit_chat, eid),
                Err(e) => tracing::warn!("Commit broadcast for {} failed: {}", commit_chat, e),
            }
        });

        // 6. Update chat epoch in DB.
        {
            let conn = self.db.connection();
            ChatRepo::update(&conn, &chat_id, None, Some(epoch as i64))
                .map_err(|e| MessengerError::DatabaseError { description: format!("chat update: {}", e) })?;
        }

        if direct {
            tracing::info!("Added our other phone to direct chat {}, new epoch={}", chat_id, epoch);
            return Ok(());
        }
        // Late joiners miss earlier metadata broadcasts — re-ship avatar/desc.
        self.broadcast_current_group_metadata(&chat_id);
        // …and the role snapshot, so the new member's owner/admin badges appear
        // immediately instead of only after they open group-info (which would
        // otherwise pull-request the roles on a noticeable delay). We are the
        // owner here (only the owner may add), so the guard passes.
        self.broadcast_role_snapshot(&chat_id, false);
        // …and the permissions with the channel settings: without them a new
        // subscriber doesn't know the group is a channel (nor who may write).
        self.broadcast_current_permissions(&chat_id);
        // …and the recent history, when the chat gives it to newcomers: MLS
        // gives them nothing from before they joined.
        self.send_channel_history(&chat_id);

        tracing::info!("Added {} to group {}, new epoch={}", peer_pk, chat_id, epoch);
        Ok(())
    }

    /// Send a channel's recent posts (up to 100) to the channel, for whoever
    /// just joined: MLS gives a newcomer nothing from before. Each post goes
    /// as it was sent — same id, original time — so members who have it drop
    /// it as a duplicate. Plain posts only: no comments, nothing that expires.
    /// Media go as their existing refs; nothing is uploaded again.
    fn send_channel_history(&self, chat_id: &str) {
        use alpha_database::models::MessageType as DbType;
        let (items, channel): (Vec<alpha_messages::HistoryItem>, bool) = {
            let conn = self.db.connection();
            if !alpha_database::group_roles::history_on(&conn, chat_id) { return; }
            let channel = alpha_database::group_roles::is_channel(&conn, chat_id);
            let items = MessageRepo::get_by_chat(&conn, chat_id, 300, 0).unwrap_or_default()
                .into_iter()
                .filter(|m| !m.is_deleted && m.expiry_policy.is_none() && m.ttl_seconds.is_none())
                .filter_map(|m| {
                    let msg = match m.msg_type {
                        DbType::Text => AlphaMessage::Text { sent_ms: 0, silent: false,
                            id: m.id.clone(),
                            body: m.content.clone()?,
                            reply_to: m.reply_to.clone(),
                            expiry: None,
                            seq: 0,
                            thread: None,
                        },
                        DbType::Reply => AlphaMessage::Reply { sent_ms: 0, silent: false,
                            id: m.id.clone(),
                            body: m.content.clone()?,
                            reply_to: m.reply_to.clone()?,
                            quoted_text: m.media_ref.clone().unwrap_or_default(),
                            seq: 0,
                            thread: None,
                        },
                        DbType::Media => {
                            let media_ref = m.media_ref.clone()?;
                            let r: serde_json::Value = serde_json::from_str(&media_ref).unwrap_or_default();
                            AlphaMessage::Media { sent_ms: 0, silent: false,
                                id: m.id.clone(),
                                media_type: r.get("media_type").and_then(|x| x.as_str()).unwrap_or_default().to_string(),
                                filename: r.get("filename").and_then(|x| x.as_str()).unwrap_or_default().to_string(),
                                size_bytes: r.get("original_size").and_then(|x| x.as_u64()).unwrap_or(0),
                                media_ref,
                                caption: m.content.clone(),
                                reply_to: m.reply_to.clone(),
                                expiry: None,
                                seq: 0,
                                thread: None,
                            }
                        }
                        _ => return None,
                    };
                    let at = chrono::DateTime::parse_from_rfc3339(&m.created_at).ok()?.timestamp().max(0) as u64;
                    let bytes = alpha_messages::serialize(&msg).ok()?;
                    // A channel's posts speak as the channel; a group's messages keep their author.
                    let from = (!channel && m.sender_id != "self").then(|| m.sender_id.clone());
                    Some(alpha_messages::HistoryItem { at, msg: hex::encode(bytes), from })
                })
                .collect();
            (items, channel)
        };
        let skip = items.len().saturating_sub(100);
        let posts: Vec<_> = items.into_iter().skip(skip).collect();
        if posts.is_empty() { return; }
        // …and the latest comments under those posts, each under its author.
        let mut items: Vec<alpha_messages::HistoryItem> = Vec::new();
        for post in posts {
            let post_id = alpha_messages::deserialize(&hex::decode(&post.msg).unwrap_or_default())
                .map(|m| m.id().to_string()).unwrap_or_default();
            items.push(post);
            if post_id.is_empty() || !channel { continue; }
            let rows = {
                let conn = self.db.connection();
                MessageRepo::get_thread(&conn, chat_id, &post_id, 30, 0).unwrap_or_default()
            };
            for m in rows.into_iter().filter(|m| !m.is_deleted && m.expiry_policy.is_none() && m.ttl_seconds.is_none()) {
                let thread = Some(post_id.clone());
                let msg = match m.msg_type {
                    DbType::Text => AlphaMessage::Text { sent_ms: 0, silent: false,
                        id: m.id.clone(), body: match m.content.clone() { Some(b) => b, None => continue },
                        reply_to: m.reply_to.clone(), expiry: None, seq: 0, thread,
                    },
                    DbType::Reply => AlphaMessage::Reply { sent_ms: 0, silent: false,
                        id: m.id.clone(), body: match m.content.clone() { Some(b) => b, None => continue },
                        reply_to: match m.reply_to.clone() { Some(r) => r, None => continue },
                        quoted_text: m.media_ref.clone().unwrap_or_default(), seq: 0, thread,
                    },
                    DbType::Media => {
                        let Some(media_ref) = m.media_ref.clone() else { continue };
                        let r: serde_json::Value = serde_json::from_str(&media_ref).unwrap_or_default();
                        AlphaMessage::Media { sent_ms: 0, silent: false,
                            id: m.id.clone(),
                            media_type: r.get("media_type").and_then(|x| x.as_str()).unwrap_or_default().to_string(),
                            filename: r.get("filename").and_then(|x| x.as_str()).unwrap_or_default().to_string(),
                            size_bytes: r.get("original_size").and_then(|x| x.as_u64()).unwrap_or(0),
                            media_ref, caption: m.content.clone(), reply_to: m.reply_to.clone(),
                            expiry: None, seq: 0, thread,
                        }
                    }
                    _ => continue,
                };
                let Some(at) = chrono::DateTime::parse_from_rfc3339(&m.created_at).ok().map(|t| t.timestamp().max(0) as u64) else { continue };
                let Ok(bytes) = alpha_messages::serialize(&msg) else { continue };
                // Our own comments come from us; anyone else's carry their author.
                let from = (m.sender_id != "self").then(|| m.sender_id.clone());
                items.push(alpha_messages::HistoryItem { at, msg: hex::encode(bytes), from });
            }
        }
        // Small envelopes: relays refuse big events. A file goes as it does in
        // a chat (slim ref, its chunk list in a manifest); what stays too big is left out.
        let mut batches: Vec<Vec<alpha_messages::HistoryItem>> = Vec::new();
        let mut cur: Vec<alpha_messages::HistoryItem> = Vec::new();
        let mut size = 0usize;
        let mut left_out = 0usize;
        for it in items {
            let Some(it) = self.history_item_for_wire(it) else { left_out += 1; continue };
            if !cur.is_empty() && (size + it.msg.len() > 24 * 1024 || cur.len() >= 25) {
                batches.push(std::mem::take(&mut cur));
                size = 0;
            }
            size += it.msg.len();
            cur.push(it);
        }
        if !cur.is_empty() { batches.push(cur); }
        let total: usize = batches.iter().map(|b| b.len()).sum();
        for batch in batches {
            let msg = AlphaMessage::ChannelHistory { items: batch, ts: chrono::Utc::now().to_rfc3339() };
            let Ok(payload) = alpha_messages::serialize(&msg) else { continue };
            if let Err(e) = self.queue_and_send(chat_id, &payload, &format!("chan-hist-{}", uuid::Uuid::new_v4())) {
                tracing::warn!("channel history to {} not sent: {}", chat_id, e);
            }
        }
        tracing::warn!("channel history: {} item(s) sent to {} ({} too big, left out)", total, chat_id, left_out);
    }

    /// A history item as it may travel: a media ref without our file path,
    /// a big file's chunk list moved into a manifest (album items each so).
    /// None when it still would not fit an event relays take.
    fn history_item_for_wire(&self, mut item: alpha_messages::HistoryItem) -> Option<alpha_messages::HistoryItem> {
        const MAX_ITEM_HEX: usize = 2 * 40 * 1024;
        let bytes = hex::decode(&item.msg).ok()?;
        let mut msg = alpha_messages::deserialize(&bytes).ok()?;
        if let AlphaMessage::Media { media_ref, .. } = &mut msg {
            *media_ref = self.wire_ref_of(media_ref);
            item.msg = hex::encode(alpha_messages::serialize(&msg).ok()?);
        }
        (item.msg.len() <= MAX_ITEM_HEX).then_some(item)
    }

    /// A stored ref in the form it is sent in: no local path, large refs slim.
    fn wire_ref_of(&self, media_ref: &str) -> String {
        let Ok(v) = serde_json::from_str::<serde_json::Value>(media_ref) else { return media_ref.to_string() };
        let slim = |r: &serde_json::Value| -> serde_json::Value {
            let mut w = match serde_json::from_value::<MediaRef>(r.clone()) {
                Ok(mr) => self.externalize_ref_if_large(&mr).ok()
                    .and_then(|s| serde_json::to_value(&s).ok())
                    .unwrap_or_else(|| r.clone()),
                // A poll, a place or a link card: no file, nothing to slim.
                Err(_) => r.clone(),
            };
            if let Some(m) = w.as_object_mut() { m.remove("local_path"); }
            w
        };
        if v.get("album").and_then(|x| x.as_bool()) == Some(true) {
            let mut out = v.clone();
            if let Some(items) = out.get_mut("items").and_then(|x| x.as_array_mut()) {
                for it in items.iter_mut() { *it = slim(it); }
            }
            if let Some(m) = out.as_object_mut() { m.remove("pending"); }
            return out.to_string();
        }
        slim(&v).to_string()
    }

    /// Publish a full `GroupRoleSnapshot` (owner + admin/moderator map)
    /// into the group so members converge on who holds which role. Best-effort:
    /// skips silently if there's no recorded owner (legacy group) or no MLS
    /// group loaded. STABILITY: sent ONLY as a reply to an explicit
    /// `GroupRoleSyncRequest` (pull-based sync) — never periodically, never on
    /// open/receive. This is what keeps role traffic ≈ zero.
    fn broadcast_role_snapshot(&self, chat_id: &str, bypass_owner_guard: bool) {
        publish_role_snapshot_raw(
            &self.db, &self.mls_session, &self.identity, (*self.transport).clone(), chat_id, bypass_owner_guard,
        );
    }

    /// High-level: create a new group with N initial members by npub.
    /// Iterates `add_member_by_npub` for each peer; partial failure is
    /// surfaced as a `NetworkError` listing the npubs that didn't make it.
    /// A group holds at most [MAX_GROUP_MEMBERS] people: past that, the Welcome
    /// carrying the whole tree outgrows what relays take, and joins fail unseen.
    fn ensure_room_for_member(&self, group_id: &[u8]) -> Result<(), MessengerError> {
        if self.people_in(group_id) as usize >= MAX_GROUP_MEMBERS {
            return Err(MessengerError::InvalidArgument {
                description: format!("group full: at most {MAX_GROUP_MEMBERS} members"),
            });
        }
        Ok(())
    }

    pub fn create_group_with_members(&self, name: String, npubs_json: String) -> Result<ChatInfo, MessengerError> {
        let npubs: Vec<String> = serde_json::from_str(&npubs_json)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("npubs must be JSON array: {}", e) })?;

        // Step 1: bootstrap empty group with us as the only member.
        let chat_info = self.create_group(name)?;
        let chat_id = chat_info.id.clone();

        // Step 2: add each peer. Collect failures so the caller knows who
        // didn't get invited (e.g. no KeyPackage on relays).
        let mut failed = Vec::new();
        for npub in &npubs {
            if let Err(e) = self.add_member_by_npub(chat_id.clone(), npub.clone()) {
                tracing::warn!("create_group_with_members: failed to add a member: {}", e);
                failed.push(format!("{}: {}", npub, e));
            }
        }

        if failed.is_empty() {
            Ok(chat_info)
        } else {
            // Group exists locally; tell the caller who's missing so they can retry.
            Err(MessengerError::NetworkError {
                description: format!("group created but {} member(s) could not be added: {}",
                    failed.len(), failed.join("; ")),
            })
        }
    }

    /// Add a member to a group chat.
    pub fn add_member(&self, chat_id: String, peer_key_package_base64: String) -> Result<String, MessengerError> {
        let kp_bytes = base64::Engine::decode(
            &base64::engine::general_purpose::STANDARD,
            &peer_key_package_base64,
        ).map_err(|e| MessengerError::InvalidArgument { description: format!("invalid base64 key package: {}", e) })?;

        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        self.ensure_room_for_member(&group_id)?;

        let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let (commit_bytes, _welcome_bytes) = mls
            .add_member_to_group(&group_id, &kp_bytes)
            .map_err(|e| MessengerError::CryptoError { description: format!("failed to add member: {}", e) })?;

        let commit_base64 = base64::Engine::encode(
            &base64::engine::general_purpose::STANDARD,
            commit_bytes,
        );

        // Update the chat epoch.
        let epoch = mls.group_info(&group_id)
            .map(|info| info.epoch as i64)
            .unwrap_or(0);
        let conn = self.db.connection();
        ChatRepo::update(&conn, &chat_id, None, Some(epoch))
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;

        Ok(commit_base64)
    }

    /// Remove a member from a group chat.
    /// Ban a member for good: their role goes, they get the reason while still
    /// in the group, then they are removed. Their phone keeps nothing of it.
    pub fn ban_member(&self, chat_id: String, npub_or_hex: String, leaf_index: u32, reason: String) -> Result<(), MessengerError> {
        use nostr::nips::nip19::FromBech32;
        let t = npub_or_hex.trim();
        let target_hex = if t.starts_with("npub1") {
            nostr::PublicKey::from_bech32(t).map(|p| p.to_hex()).map_err(|e| e.to_string())
        } else {
            nostr::PublicKey::from_hex(t).map(|p| p.to_hex()).map_err(|e| e.to_string())
        }.map_err(|e| MessengerError::InvalidArgument { description: format!("invalid pubkey: {}", e) })?;
        let own_hex = self.own_hex_in(&chat_id);
        let target_role = {
            let conn = self.db.connection();
            let actor = alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex);
            let target = alpha_database::group_roles::role_of(&conn, &chat_id, &target_hex);
            if !alpha_messages::can_remove(&actor, &target) {
                return Err(MessengerError::InvalidArgument { description: "your role cannot ban this member".to_string() });
            }
            target
        };
        if target_role != alpha_messages::ROLE_MEMBER {
            let _ = self.set_member_role(chat_id.clone(), target_hex.clone(), alpha_messages::ROLE_MEMBER.to_string());
        }
        let msg = AlphaMessage::GroupBan {
            target_pubkey: target_hex,
            reason: reason.trim().chars().take(300).collect(),
            ts: chrono::Utc::now().to_rfc3339(),
        };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        let (ciphertext, epoch) = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let ct = mls.send(&group_id, &payload)
                .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?;
            (ct, mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0))
        };
        self.publish_application_message(&group_id, epoch, ciphertext)?;
        self.persist_mls_storage();
        self.remove_member(chat_id, leaf_index).map(|_| ())
    }

    /// Our ban in this chat: `{"reason","at"}`, or "" when we are not banned.
    pub fn chat_ban(&self, chat_id: String) -> String {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, &format!("banned:{}", chat_id)).ok().flatten().unwrap_or_default()
    }

    pub fn remove_member(&self, chat_id: String, leaf_index: u32) -> Result<String, MessengerError> {
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        // The person behind this leaf, with all their phones — never a phone of ours.
        let members = self.mls_session.lock().ok().and_then(|m| m.group_info(&group_id)).map(|i| i.members).unwrap_or_default();
        let owner_of = |sig: &[u8]| alpha_database::policy::sender_pubkey(&self.db.connection(), &chat_id, &hex::encode(sig));
        let target = members.iter().find(|m| m.leaf_index == leaf_index).and_then(|m| owner_of(&m.signature_key));
        let leaves: Vec<u32> = match target.filter(|t| *t != self.own_hex_in(&chat_id)) {
            Some(t) => members.iter().filter(|m| owner_of(&m.signature_key).as_deref() == Some(t.as_str())).map(|m| m.leaf_index).collect(),
            None => vec![leaf_index],
        };
        self.remove_leaves(&chat_id, &leaves)
    }

    /// Remove exactly [leaves] from [chat_id], in one commit.
    pub(super) fn remove_leaves(&self, chat_id: &str, leaves: &[u32]) -> Result<String, MessengerError> {
        let chat_id = chat_id.to_string();
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;

        // FIX (epoch-fork) — designated sequencer: only the OWNER may issue
        // Remove commits. (See add_member_by_npub for the full rationale.)
        // NOTE: `recover_group_from_fork` calls this after its own owner
        // check, so recovery is not blocked.
        {
            let own_hex = self.own_hex_in(&chat_id);
            let conn = self.db.connection();
            if let Some(owner_pk) = alpha_database::group_roles::owner_of(&conn, &chat_id) {
                if owner_pk != own_hex {
                    return Err(MessengerError::InvalidArgument {
                        description: "only the group owner can remove members".to_string(),
                    });
                }
            }
        }

        // Membership BEFORE the removal — the diff tells whose binding to drop.
        let members_before_remove = self.member_sig_keys(&group_id);

        // Apply the Remove+Commit locally and capture the commit + new epoch.
        // Drop the MLS lock BEFORE persist/broadcast (persist re-locks it).
        let (commit_bytes, epoch) = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let c = mls.remove_members_from_group(&group_id, leaves)
                .map_err(|e| MessengerError::CryptoError { description: format!("failed to remove member: {}", e) })?;
            let ep = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
            (c, ep)
        };

        // The removed member stops being a recipient immediately. BOTH records
        // go — binding and authorization: a leftover authorization would let the
        // removed member keep naming others' addresses first.
        {
            let after = self.member_sig_keys(&group_id);
            for sig in members_before_remove.iter().filter(|s| !after.contains(s)) {
                self.purge_member_binding(&group_id, sig);
            }
        }

        // Persist the advanced ratchet so the new epoch survives a restart.
        self.persist_mls_storage();

        let commit_base64 = base64::Engine::encode(
            &base64::engine::general_purpose::STANDARD,
            &commit_bytes,
        );

        // CRITICAL: broadcast the Commit as a public kind-443 envelope so the
        // REMAINING members advance their ratchet to the new epoch. Without
        // this they stay at the old epoch and the group silently breaks
        // (our messages can't be decrypted by them, theirs not by us).
        let own_keys = self.keys_in(&chat_id)?;
        let commit_envelope = MlsEnvelope::new(
            MlsMessageType::Commit,
            group_id.clone(),
            epoch,
            commit_bytes,
        );
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let commit_chat = chat_id.clone();
        runtime().spawn(async move {
            match bridge.send_mls_message(&own_keys, &commit_envelope, None, None).await {
                Ok(eid) => tracing::info!("Broadcast remove-Commit for group {}: event_id={}", commit_chat, eid),
                Err(e) => tracing::warn!("remove-Commit broadcast for {} failed: {}", commit_chat, e),
            }
        });

        // Update the chat epoch in DB.
        {
            let conn = self.db.connection();
            ChatRepo::update(&conn, &chat_id, None, Some(epoch as i64))
                .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
        }

        // A snapshot of the NEW membership — those left learn the removed member
        // is gone from the directory. The new epoch is the revision, so an older
        // snapshot arriving later via another relay cannot resurrect them.
        self.publish_group_directory(&group_id);

        Ok(commit_base64)
    }

    /// FIX (epoch-fork): owner-only recovery for a forked MLS group.
    ///
    /// When an epoch fork is detected (two members committed concurrently, or
    /// a member missed intermediate commits and can no longer decrypt), the
    /// group is "dead" for the forked branch. There is no server to order
    /// commits, so the only server-free way to converge everyone onto a single
    /// live epoch is for the OWNER to re-key the group.
    ///
    /// This method performs a **Remove + re-Add** of every non-owner member
    /// using a FRESH KeyPackage fetched from relays. Each re-Add produces a
    /// higher-epoch Welcome that is gift-wrapped to the re-added member:
    ///   • FORKED members `join_chat` on it, REPLACING their forked local
    ///     state with a clean higher-epoch state (existing re-join path in
    ///     catch_up.rs). The group "lives" again for them.
    ///   • NON-forked members process the Remove+Add commits as normal kind-443
    ///     envelopes and advance their ratchet; the Welcome is a harmless
    ///     duplicate they skip (epoch guard).
    ///
    /// The Remove+Add commits are broadcast as public kind-443 events so every
    /// member advances; the Welcomes are gift-wrapped (kind-1059) so only the
    /// target member can read them. ntfy peer-wakes fire on every Welcome so
    /// backgrounded members fetch it now.
    ///
    /// Non-owners: returns `Ok(())` immediately — they cannot re-key a group
    /// they don't own; they wait for the owner's recovery Welcome.
    ///
    /// Best-effort: a member whose fresh KeyPackage can't be fetched is skipped
    /// (logged); the rest still recover. Safe to retry on the next sync.
    pub fn recover_group_from_fork(&self, chat_id: &str) -> Result<(), MessengerError> {
        let group_id = hex::decode(chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;

        // 1. Owner-only. Non-owners can't re-key; they wait for the owner's
        //    recovery Welcome (caught by the higher-epoch Welcome re-join path).
        let own_hex = self.own_hex_in(chat_id);
        let we_are_owner = {
            let conn = self.db.connection();
            alpha_database::group_roles::owner_of(&conn, chat_id)
                .as_deref() == Some(own_hex.as_str())
        };
        if !we_are_owner {
            tracing::info!(
                target: "MLS_DIAG",
                "recover_group_from_fork: not owner of {} — deferring recovery to the owner",
                chat_id,
            );
            return Ok(());
        }

        // 2. Snapshot current members (leaf_index + npub). We exclude the
        //    owner (ourselves) — the owner's state is already the canonical
        //    winning branch (we issued this recovery).
        let own_sig_hex = self.own_sig_in(chat_id);
        let members: Vec<(u32, String)> = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let info = mls.group_info(&group_id)
                .ok_or_else(|| MessengerError::InternalError {
                    description: format!("group {} not loaded — can't recover", chat_id),
                })?;
            let conn = self.db.connection();
            info.members.iter()
                .filter(|m| hex::encode(&m.signature_key) != own_sig_hex)
                .filter_map(|m| {
                    let sig_hex = hex::encode(&m.signature_key);
                    let npub_hex = alpha_database::KeyValueRepo::get(
                        &conn, &format!("member_npub:{}", sig_hex),
                    ).ok().flatten().unwrap_or_default();
                    if npub_hex.is_empty() { None } else { Some((m.leaf_index, npub_hex)) }
                })
                .collect()
        };

        if members.is_empty() {
            tracing::info!(
                target: "MLS_DIAG",
                "recover_group_from_fork: no recoverable members in {} — nothing to do",
                chat_id,
            );
            return Ok(());
        }

        tracing::info!(
            target: "MLS_DIAG",
            "RECOVER_START chat={} members={}",
            chat_id, members.len(),
        );
        // Feed the reliability controller.
        self.reliability.record_recovery();

        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let rt = runtime();

        // 3. Remove + re-Add each member with a FRESH KeyPackage. Each re-Add
        //    yields a higher-epoch Welcome that recovers forked members.
        let mut recovered = 0u32;
        for (leaf_index, npub_hex) in members {
            // Fetch a fresh KeyPackage (the old one was consumed at join).
            let peer_pk = match nostr::PublicKey::from_hex(&npub_hex) {
                Ok(pk) => pk,
                Err(e) => {
                    tracing::warn!(
                        target: "MLS_DIAG",
                        "RECOVER_SKIP chat={} — invalid member key: {}",
                        chat_id, e,
                    );
                    continue;
                }
            };
            let kp_list = match rt.block_on(bridge.fetch_key_packages(&peer_pk)) {
                Ok(list) if !list.is_empty() => list,
                Ok(_) => {
                    tracing::warn!(
                        target: "MLS_DIAG",
                        "RECOVER_SKIP chat={} — a member has no fresh KeyPackage",
                        chat_id,
                    );
                    continue;
                }
                Err(e) => {
                    tracing::warn!(
                        target: "MLS_DIAG",
                        "RECOVER_SKIP chat={} — fetch KeyPackage failed: {}",
                        chat_id, e,
                    );
                    continue;
                }
            };
            let kp_bytes = match self.pick_distinct_kp(&npub_hex, kp_list) {
                Ok(b) => b,
                Err(e) => {
                    tracing::warn!(
                        target: "MLS_DIAG",
                        "RECOVER_SKIP chat={} — no distinct KeyPackage: {}",
                        chat_id, e,
                    );
                    continue;
                }
            };

            // Remove the member (broadcast Remove commit so non-forked members
            // advance). Ignore failure — a member already removed by a prior
            // iteration's race is fine.
            if let Err(e) = self.remove_member(chat_id.to_string(), leaf_index) {
                tracing::warn!(
                    target: "MLS_DIAG",
                    "RECOVER remove failed chat={} leaf={}: {} — continuing",
                    chat_id, leaf_index, e,
                );
            }

            // Re-Add with the fresh KeyPackage. This applies the Add commit
            // locally (advancing our epoch), broadcasts the Commit, gift-wraps
            // the Welcome to the member, and fires an ntfy peer-wake — all via
            // the existing add_member path. The higher-epoch Welcome is what
            // recovers a forked member.
            let gid = group_id.clone();
            let (commit_bytes, welcome_bytes, epoch) = {
                let mut mls = self.mls_session.lock()
                    .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
                match mls.add_member_to_group(&gid, &kp_bytes) {
                    Ok((c, w)) => {
                        let e = mls.group_info(&gid).map(|i| i.epoch).unwrap_or(0);
                        (c, w, e)
                    }
                    Err(e) => {
                        tracing::warn!(
                            target: "MLS_DIAG",
                            "RECOVER re-add failed chat={}: {} — continuing",
                            chat_id, e,
                        );
                        continue;
                    }
                }
            };
            self.persist_mls_storage();

            // Gift-wrap the Welcome to the member (recovery Welcome).
            let own_keys = self.keys_in(chat_id)?;
            let welcome_envelope = MlsEnvelope::new_group(
                MlsMessageType::Welcome,
                gid.clone(),
                epoch,
                welcome_bytes,
            );
            let wk = own_keys.clone();
            let wb = bridge.clone();
            let wp = peer_pk;
            let wc = chat_id.to_string();
            // Retry the recovery Welcome too.
            rt.spawn(async move {
                for attempt in 1..=3u32 {
                    match wb.send_mls_message(&wk, &welcome_envelope, Some(&wp), None).await {
                        Ok(eid) => {
                            tracing::info!(
                                target: "MLS_DIAG",
                                "RECOVER welcome chat={} to={} event_id={} epoch={} (attempt {})",
                                wc, wp, eid, epoch, attempt,
                            );
                            return;
                        }
                        Err(e) => {
                            tracing::warn!(
                                target: "MLS_DIAG",
                                "RECOVER welcome attempt {} chat={} to={} failed: {}",
                                attempt, wc, wp, e,
                            );
                            tokio::time::sleep(std::time::Duration::from_secs(2 * attempt as u64)).await;
                        }
                    }
                }
                tracing::error!(target: "MLS_DIAG", "RECOVER welcome chat={} to={} failed permanently", wc, wp);
            });
            // Wake the member so a backgrounded device fetches the recovery
            // Welcome now.
            self.send_peer_wake_now(std::slice::from_ref(&npub_hex));

            // Broadcast the Add Commit so non-forked members advance.
            let commit_envelope = MlsEnvelope::new(
                MlsMessageType::Commit,
                gid.clone(),
                epoch,
                commit_bytes,
            );
            let ck = own_keys;
            let cb = bridge.clone();
            let cc = chat_id.to_string();
            rt.spawn(async move {
                match cb.send_mls_message(&ck, &commit_envelope, None, None).await {
                    Ok(eid) => tracing::info!(
                        target: "MLS_DIAG",
                        "RECOVER commit chat={} event_id={} epoch={}",
                        cc, eid, epoch,
                    ),
                    Err(e) => tracing::warn!(
                        target: "MLS_DIAG",
                        "RECOVER commit chat={} failed: {}",
                        cc, e,
                    ),
                }
            });

            // Update chat epoch.
            {
                let conn = self.db.connection();
                let _ = alpha_database::ChatRepo::update(&conn, chat_id, None, Some(epoch as i64));
            }
            recovered += 1;
        }

        tracing::info!(
            target: "MLS_DIAG",
            "RECOVER_DONE chat={} recovered={}",
            chat_id, recovered,
        );
        Ok(())
    }

    // --- E2E Delivery Layer ---

    /// Handle a `DeliveryAck` received for OUR messages. Records the acker's
    /// watermark (forward-only), then recomputes the delivery outbox: deletes
    /// rows that are now fully ACKed by ALL current group members and fires
    /// `on_delivery_update(msg_id, "delivered")` for each. For 1:1 the single
    /// peer is the only acker; for groups every non-self member must ack.
    fn handle_delivery_ack(&self, chat_id: &str, sender_id: &str, acker_id: &str, upto_seq: u64) {
        let own_sig = self.own_sig_in(chat_id);
        if own_sig.is_empty() || sender_id != own_sig {
            return; // not for us
        }
        // Record the acker's watermark.
        {
            let conn = self.db.connection();
            let _ = alpha_database::delivery::DeliveryRepo::record_ack(
                &conn, chat_id, acker_id, upto_seq,
            );
        }
        // Feed the reliability controller.
        // from the outbox row's last_sent_at — if available).
        {
            let conn = self.db.connection();
            let latency_ms: i64 = conn.query_row(
                "SELECT COALESCE(
                    (julianday('now') - julianday(strftime('%s', last_sent_at), 'unixepoch')) * 86400000,
                    0
                 ) FROM delivery_outbox WHERE chat_id = ?1 AND seq <= ?2 ORDER BY seq DESC LIMIT 1",
                rusqlite::params![chat_id, upto_seq as i64],
                |r| r.get(0),
            ).unwrap_or(0);
            self.reliability.record_ack(latency_ms.max(0) as u64);
        }
        // Recompute outbox: gather current non-self members (ackers) and prune.
        let acker_ids = self.current_acker_ids(chat_id, &own_sig);
        let deleted = {
            let conn = self.db.connection();
            alpha_database::delivery::DeliveryRepo::outbox_delete_fully_acked(&conn, chat_id, &acker_ids)
                .unwrap_or_default()
        };
        for msg_id in deleted {
            // TD-003: persist DELIVERED so it survives reload. Stamp delivered_at
            // BEFORE firing the callback so a reload racing the UI update still
            // reads the delivered state.
            {
                let conn = self.db.connection();
                let _ = alpha_database::MessageRepo::mark_delivered(&conn, &msg_id);
            }
            tracing::info!(
                target: "DELIVERY",
                "ACK_DELIVERED chat={} msg_id={} — fully acked, removed from outbox",
                chat_id, msg_id,
            );
            self.callback.on_delivery_update(&msg_id, "delivered");
            self.on_media_delivered(&msg_id);
        }
    }

    /// Gather the MLS signature-key hex of every current non-self member in a
    /// chat (the set of recipients whose ACKs we need before an outbox row is
    /// "fully delivered"). For 1:1 this is just the peer; for groups it's all
    /// members except us. Returns an empty vec if the group isn't loaded.
    fn current_acker_ids(&self, chat_id: &str, own_sig_hex: &str) -> Vec<String> {
        let gid = match hex::decode(chat_id) { Ok(g) => g, Err(_) => return Vec::new() };
        let members = match self.mls_session.lock() {
            Ok(m) => m.group_info(&gid).map(|i| i.members).unwrap_or_default(),
            Err(_) => return Vec::new(),
        };
        members.iter()
            .map(|m| hex::encode(&m.signature_key))
            .filter(|h| !h.is_empty() && h != own_sig_hex)
            .collect()
    }

    /// Handle a `ResendRequest` for OUR messages: re-publish the requested seq
    /// range from the delivery outbox. Each resent message is re-encrypted on
    /// the CURRENT MLS state (the ratchet has advanced since the original
    /// send, so the ciphertext differs, but the seq + msg_id + plaintext are
    /// identical → the receiver dedups by seq and persists if missing). The
    /// outbox row's send_count is bumped.
    fn handle_resend_request(&self, chat_id: &str, sender_id: &str, from_seq: u64, to_seq: u64) {
        let own_sig = self.own_sig_in(chat_id);
        if own_sig.is_empty() || sender_id != own_sig {
            return; // not for us
        }
        let entries = {
            let conn = self.db.connection();
            alpha_database::delivery::DeliveryRepo::outbox_get_range(&conn, chat_id, from_seq, to_seq)
                .unwrap_or_default()
        };
        if entries.is_empty() {
            tracing::info!(
                target: "DELIVERY",
                "RESEND_EMPTY chat={} range=[{}..{}] — no outbox rows (already acked/expired)",
                chat_id, from_seq, to_seq,
            );
            return;
        }
        tracing::info!(
            target: "DELIVERY",
            "RESEND_FULFILL chat={} range=[{}..{}] count={}",
            chat_id, from_seq, to_seq, entries.len(),
        );
        // Feed the reliability controller.
        self.reliability.record_resend();
        let gid = match hex::decode(chat_id) { Ok(g) => g, Err(_) => return };
        let keys = self.keys_in(chat_id).ok();
        let keys = match keys { Some(k) => k, None => return };
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let mls = self.mls_session.clone();
        let db = self.db.clone();
        let cb = self.callback.clone();
        runtime().spawn(async move {
            for e in entries {
                // Re-encrypt on current state.
                let enc = {
                    let mut m = match mls.lock() { Ok(m) => m, Err(_) => continue };
                    match m.send(&gid, &e.payload) {
                        Ok(ct) => { let ep = m.group_info(&gid).map(|i| i.epoch).unwrap_or(0); Some((ct, ep)) }
                        Err(err) => { tracing::warn!("resend encrypt fail seq={}: {}", e.seq, err); None }
                    }
                };
                let (ct, epoch) = match enc { Some(x) => x, None => continue };
                let envelope = MlsEnvelope::new(MlsMessageType::Application, gid.clone(), epoch, ct);
                match bridge.send_mls_message(&keys, &envelope, None, None).await {
                    Ok(_eid) => {
                        let conn = db.connection();
                        let _ = alpha_database::delivery::DeliveryRepo::outbox_mark_sent(&conn, e.id);
                        tracing::info!(target: "DELIVERY", "RESENT chat={} seq={}", e.chat_id, e.seq);
                    }
                    Err(err) => tracing::warn!("resend publish fail seq={}: {}", e.seq, err),
                }
                let _ = cb; // (status callback omitted — row stays pending until ACK)
            }
        });
    }

    /// Send a batched `DeliveryAck` to the group confirming receipt of all
    /// seqs 1..=upto_seq from `sender_id`. Sent as a normal MLS application
    /// message (control message — no seq of its own). The receiver (original
    /// sender) records it and prunes their outbox.
    fn send_delivery_ack(&self, chat_id: &str, sender_id: &str, upto_seq: u64) -> Result<(), MessengerError> {
        let ack = AlphaMessage::DeliveryAck {
            chat_id: chat_id.to_string(),
            sender_id: sender_id.to_string(),
            upto_seq,
        };
        let payload = alpha_messages::serialize(&ack)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        // Send as a control message — bypass the delivery outbox (acks aren't
        // acked). Use queue_and_send which will see it's not delivery-eligible
        // and skip the outbox, just encrypting + publishing.
        let msg_id = format!("ack-{}-{}", chat_id, upto_seq);
        self.queue_and_send(chat_id, &payload, &msg_id)?;
        // Advance our own acked watermark (forward-only).
        let conn = self.db.connection();
        let _ = alpha_database::delivery::DeliveryRepo::advance_acked(&conn, chat_id, sender_id, upto_seq);
        tracing::info!(
            target: "DELIVERY",
            "ACK_SENT chat={} sender_id={} upto_seq={}",
            chat_id, sender_id, upto_seq,
        );
        Ok(())
    }

    /// Send a `ResendRequest` to the group asking `sender_id` to re-publish
    /// seqs [from_seq, to_seq]. Fire-and-forget control message.
    fn send_resend_request(&self, chat_id: &str, sender_id: &str, from_seq: u64, to_seq: u64) {
        let req = AlphaMessage::ResendRequest {
            chat_id: chat_id.to_string(),
            sender_id: sender_id.to_string(),
            from_seq,
            to_seq,
        };
        let payload = match alpha_messages::serialize(&req) {
            Ok(p) => p,
            Err(e) => { tracing::warn!("resend_request serialize: {}", e); return; }
        };
        let msg_id = format!("rr-{}-{}-{}", chat_id, from_seq, to_seq);
        if let Err(e) = self.queue_and_send(chat_id, &payload, &msg_id) {
            tracing::warn!("resend_request send: {}", e);
        }
    }

    /// One tick of the delivery layer: (1) send batched ACKs for any unacked
    /// contiguous seqs, (2) send ResendRequests for open gaps, (3) fulfill
    /// queued incoming ResendRequests, (4) proactively re-publish pending
    /// outbox rows older than the resend timeout, (5) prune expired outbox.
    /// Called periodically by the delivery timer task.
    fn delivery_timer_tick(&self) {
        let own_sig = match self.mls_session.lock() {
            Ok(m) => hex::encode(m.signer_public()),
            Err(_) => return,
        };
        if own_sig.is_empty() { return; }

        // (1) Send batched ACKs. For each chat we're in, scan watermarks:
        // if highest_received > highest_acked for some sender, ACK up to
        // highest_received. We iterate over all chats we have group state for.
        let chats: Vec<Vec<u8>> = match self.mls_session.lock() {
            Ok(m) => m.active_groups(),
            Err(_) => Vec::new(),
        };
        for gid in &chats {
            let chat_id = hex::encode(gid);
            // For each sender in this chat (we track watermarks per sender).
            // We don't have a direct "list senders" API, so scan the
            // delivery_watermarks table for this chat where we're the receiver.
            let senders_to_ack: Vec<(String, u64)> = {
                let conn = self.db.connection();
                // Find watermarks where highest_received > highest_acked.
                let mut stmt = match conn.prepare(
                    "SELECT sender_id, highest_received_seq, highest_acked_seq
                     FROM delivery_watermarks
                     WHERE chat_id = ?1 AND highest_received_seq > highest_acked_seq",
                ) { Ok(s) => s, Err(_) => continue };
                let rows = stmt.query_map(rusqlite::params![chat_id], |r| {
                    let sid: String = r.get(0)?;
                    let hr: i64 = r.get(1)?;
                    Ok((sid, hr as u64))
                });
                match rows {
                    Ok(rs) => rs.filter_map(|r| r.ok()).collect(),
                    Err(_) => continue,
                }
            };
            for (sender_id, upto_seq) in senders_to_ack {
                if let Err(e) = self.send_delivery_ack(&chat_id, &sender_id, upto_seq) {
                    tracing::debug!("send_delivery_ack: {}", e);
                }
            }
        }

        // (2) Send ResendRequests for open gaps (receiver side).
        for gid in &chats {
            let chat_id = hex::encode(gid);
            // Gaps are per-sender; scan all senders for this chat.
            let gaps: Vec<(String, u64, u64)> = {
                let conn = self.db.connection();
                let mut stmt = match conn.prepare(
                    "SELECT sender_id, from_seq, to_seq FROM delivery_gaps WHERE chat_id = ?1",
                ) { Ok(s) => s, Err(_) => continue };
                let rows = stmt.query_map(rusqlite::params![chat_id], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)? as u64, r.get::<_, i64>(2)? as u64))
                });
                match rows {
                    Ok(rs) => rs.filter_map(|r| r.ok()).collect(),
                    Err(_) => continue,
                }
            };
            for (sender_id, from_seq, to_seq) in gaps {
                self.send_resend_request(&chat_id, &sender_id, from_seq, to_seq);
                // Mark requested (cooldown) so we don't spam.
                let conn = self.db.connection();
                let _ = alpha_database::delivery::DeliveryRepo::gap_mark_requested(
                    &conn, &chat_id, &sender_id, from_seq,
                );
            }
        }

        // (3) Fulfill queued incoming ResendRequests (for OUR messages).
        let pending: Vec<(String, u64, u64)> = self.pending_resends.lock()
            .map(|mut q| { let drained = q.clone(); q.clear(); drained })
            .unwrap_or_default();
        for (chat_id, from_seq, to_seq) in pending {
            self.handle_resend_request(&chat_id, &self.own_sig_in(&chat_id), from_seq, to_seq);
        }

        // (4) Proactive resend: re-publish pending outbox rows older than the
        // resend timeout (covers "relay lost the event, receiver never saw a
        // higher seq → no gap detected"). Backoff via send_count.
        const RESEND_TIMEOUT_SECS: i64 = 30;
        let stale: Vec<alpha_database::delivery::DeliveryOutboxEntry> = {
            let conn = self.db.connection();
            let cutoff = chrono::Utc::now() - chrono::Duration::seconds(RESEND_TIMEOUT_SECS);
            let all = alpha_database::delivery::DeliveryRepo::outbox_list_pending(&conn).unwrap_or_default();
            all.into_iter().filter(|e| {
                e.last_sent_at.is_empty()
                    || chrono::DateTime::parse_from_rfc3339(&e.last_sent_at)
                        .map(|t| t.with_timezone(&chrono::Utc) < cutoff)
                        .unwrap_or(true)
            }).collect()
        };
        for e in stale {
            // Cap retries to avoid infinite resend for a never-ACKing peer
            // (TTL gc will eventually clear it).
            if e.send_count > 50 { continue; }
            let gid = match hex::decode(&e.chat_id) { Ok(g) => g, Err(_) => continue };
            let enc = {
                let mut mls = match self.mls_session.lock() { Ok(m) => m, Err(_) => continue };
                match mls.send(&gid, &e.payload) {
                    Ok(ct) => { let ep = mls.group_info(&gid).map(|i| i.epoch).unwrap_or(0); Some((ct, ep)) }
                    Err(_) => None,
                }
            };
            let (ct, epoch) = match enc { Some(x) => x, None => continue };
            let keys = self.keys_in(&e.chat_id).ok();
            let keys = match keys { Some(k) => k, None => continue };
            let bridge = NostrMlsBridge::new((*self.transport).clone());
            let env = MlsEnvelope::new(MlsMessageType::Application, gid, epoch, ct);
            let db = self.db.clone();
            let id = e.id;
            let chat_id_clone = e.chat_id.clone();
            let seq = e.seq;
            runtime().spawn(async move {
                match bridge.send_mls_message(&keys, &env, None, None).await {
                    Ok(_) => {
                        let conn = db.connection();
                        let _ = alpha_database::delivery::DeliveryRepo::outbox_mark_sent(&conn, id);
                        tracing::info!(target: "DELIVERY", "PROACTIVE_RESEND chat={} seq={}", chat_id_clone, seq);
                    }
                    Err(err) => tracing::debug!("proactive resend fail seq={}: {}", seq, err),
                }
            });
        }

        // (5) Prune expired outbox rows (recipients who never ACKed —
        // uninstalled app / permanent offline). 7-day TTL.
        {
            let conn = self.db.connection();
            let _ = alpha_database::delivery::DeliveryRepo::outbox_prune_expired(&conn, 7 * 24 * 3600);
        }
    }


    // --- Reliability Controller ---


    /// Assign a group role to a member (or transfer ownership when
    /// `role == "owner"`). Enforces the caller's permission locally, applies
    /// the change to the role KV, and broadcasts a `GroupRoleUpdate` so other
    /// members converge. `npub_or_hex` identifies the target member.
    ///
    /// Honest limit: roles are an app-layer convention (MLS has none). Remote
    /// clients validate the sender's authority before applying — see
    /// `process_role_message`. A forked client could ignore that; real
    /// eviction still relies on MLS Remove+Commit.
    pub fn set_member_role(
        &self,
        chat_id: String,
        npub_or_hex: String,
        role: String,
    ) -> Result<(), MessengerError> {
        use nostr::nips::nip19::FromBech32;

        let new_role = alpha_messages::normalize_role(&role).to_string();

        // Resolve target pubkey hex.
        let trimmed = npub_or_hex.trim();
        let target_pk = if trimmed.starts_with("npub1") {
            nostr::PublicKey::from_bech32(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid npub: {}", e) })?
        } else {
            nostr::PublicKey::from_hex(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid hex pubkey: {}", e) })?
        };
        let target_hex = target_pk.to_hex();

        // Own pubkey hex.
        let own_hex = self.own_hex_in(&chat_id);

        // Permission check + replay version, then apply locally.
        let version = {
            let conn = self.db.connection();
            let owner = alpha_database::group_roles::owner_of(&conn, &chat_id).unwrap_or_default();
            let actor_role = alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex);
            let target_role = alpha_database::group_roles::role_of(&conn, &chat_id, &target_hex);
            if !alpha_messages::can_change_role(&actor_role, &target_role, &new_role) {
                return Err(MessengerError::InvalidArgument {
                    description: format!("your role ({}) cannot set {} → {}", actor_role, target_hex, new_role),
                });
            }
            let version = alpha_database::group_roles::roles_version(&conn, &chat_id) + 1;
            alpha_database::group_roles::apply_role_change(&conn, &chat_id, &owner, &target_hex, &new_role);
            alpha_database::group_roles::set_roles_version(&conn, &chat_id, version);
            version
        };

        // Broadcast the control message into the group.
        let msg = AlphaMessage::GroupRoleUpdate {
            version,
            target_pubkey: target_hex,
            role: new_role,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;

        let ciphertext = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if mls.group_info(&group_id).is_none() {
                tracing::warn!("set_member_role: MLS group not loaded for {}; local change only", chat_id);
                return Ok(());
            }
            mls.send(&group_id, &payload)
                .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?
        };
        let epoch = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };
        self.publish_application_message(&group_id, epoch, ciphertext)?;
        self.persist_mls_storage();
        tracing::info!("set_member_role: shipped v{} for chat {}", version, chat_id);
        // NOTE: do NOT also broadcast a full snapshot here — the incremental
        // GroupRoleUpdate above already syncs existing members. Broadcasting a
        // snapshot on every role change amplified outbound traffic (a message
        // storm with flaky relays). The snapshot is sent only on member add,
        // where it's actually needed (the new joiner must learn the owner).
        Ok(())
    }

    /// Transfer group ownership to another member (owner-only). The
    /// previous owner is demoted to ADMIN. Thin wrapper over `set_member_role`.
    pub fn transfer_ownership(&self, chat_id: String, npub_or_hex: String) -> Result<(), MessengerError> {
        self.set_member_role(chat_id.clone(), npub_or_hex, alpha_messages::ROLE_OWNER.to_string())?;
        // Ownership change is rare + critical. The incremental GroupRoleUpdate is
        // REJECTED by any member that never learned who the owner was (e.g. missed
        // the join-time snapshot) — to them the sender looks like a plain member.
        // Broadcasting a full snapshot here lets them converge: a member with no
        // recorded owner accepts it via bootstrap-trust (TOFU), others accept it
        // because it comes from the (current) owner.
        // bypass_owner_guard = true: the SQLite owner is already the NEW owner, so
        // the normal owner==self guard would suppress this bootstrap snapshot.
        self.broadcast_role_snapshot(&chat_id, true);
        Ok(())
    }

    /// Mute (until_ts>0, unix secs) or unmute (until_ts<=0) a member.
    /// Permission: OWNER/ADMIN/MODERATOR over a lower-ranked target. Broadcasts
    /// a `GroupMuteUpdate` so the muted member (and everyone) converges.
    pub fn set_member_mute(
        &self,
        chat_id: String,
        npub_or_hex: String,
        until_ts: i64,
    ) -> Result<(), MessengerError> {
        use nostr::nips::nip19::FromBech32;
        let trimmed = npub_or_hex.trim();
        let target_pk = if trimmed.starts_with("npub1") {
            nostr::PublicKey::from_bech32(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid npub: {}", e) })?
        } else {
            nostr::PublicKey::from_hex(trimmed)
                .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid hex pubkey: {}", e) })?
        };
        let target_hex = target_pk.to_hex();
        let own_hex = self.own_hex_in(&chat_id);

        let version = {
            let conn = self.db.connection();
            let actor_role = alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex);
            let target_role = alpha_database::group_roles::role_of(&conn, &chat_id, &target_hex);
            if !alpha_messages::can_remove(&actor_role, &target_role) {
                return Err(MessengerError::InvalidArgument {
                    description: format!("ваша роль ({}) не может заглушить этого участника", actor_role),
                });
            }
            let v = alpha_database::group_roles::mutes_version(&conn, &chat_id) + 1;
            alpha_database::group_roles::set_mute(&conn, &chat_id, &target_hex, until_ts);
            alpha_database::group_roles::set_mutes_version(&conn, &chat_id, v);
            v
        };

        let msg = AlphaMessage::GroupMuteUpdate {
            version,
            target_pubkey: target_hex,
            until_ts,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        let ciphertext = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if mls.group_info(&group_id).is_none() { return Ok(()); }
            mls.send(&group_id, &payload)
                .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?
        };
        let epoch = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };
        self.publish_application_message(&group_id, epoch, ciphertext)?;
        self.persist_mls_storage();
        Ok(())
    }

    /// Mute expiry (unix secs) for a member in a group, or 0 if not muted.
    /// Used by the composer (own npub) and the member list (muted icon).
    pub fn group_mute_until(&self, chat_id: String, npub_or_hex: String) -> Result<i64, MessengerError> {
        use nostr::nips::nip19::FromBech32;
        let trimmed = npub_or_hex.trim();
        let hex = if trimmed.starts_with("npub1") {
            nostr::PublicKey::from_bech32(trimmed).map(|p| p.to_hex()).unwrap_or_default()
        } else { trimmed.to_string() };
        if hex.is_empty() { return Ok(0); }
        let conn = self.db.connection();
        Ok(alpha_database::group_roles::mute_until(&conn, &chat_id, &hex))
    }

    /// The group audit log as a JSON array (newest last). Each entry:
    /// `{id, actor, action, target, target_name, ts}`.
    pub fn group_audit_log(&self, chat_id: String) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        Ok(alpha_database::group_roles::audit_log_json(&conn, &chat_id))
    }

    /// Public entry point — record + broadcast a group audit event. The
    /// Android layer calls this at each moderation action (ban/unban/mute/role/
    /// perms/join/leave) with the affected member's npub (may be empty) + name.
    pub fn log_group_event(
        &self,
        chat_id: String,
        action: String,
        target_npub_or_hex: String,
        target_name: String,
    ) -> Result<(), MessengerError> {
        use nostr::nips::nip19::FromBech32;
        let t = target_npub_or_hex.trim();
        let target_hex = if t.is_empty() {
            String::new()
        } else if t.starts_with("npub1") {
            nostr::PublicKey::from_bech32(t).map(|p| p.to_hex()).unwrap_or_default()
        } else {
            t.to_string()
        };
        self.emit_audit(&chat_id, &action, &target_hex, &target_name);
        Ok(())
    }

    /// Record a moderation action in the local audit log AND broadcast it
    /// to the group so every member's "Recent actions" stays in sync.
    /// Best-effort: failures are logged, never bubble up to abort the action.
    fn emit_audit(&self, chat_id: &str, action: &str, target_pubkey: &str, target_name: &str) {
        let own_hex = self.own_hex_in(chat_id);
        if own_hex.is_empty() { return; }
        use sha2::Digest;
        // Who did it, by name — never our real name when we act as a persona.
        let actor_name = {
            let conn = self.db.connection();
            if hidden_here(&conn, chat_id) { String::new() } else {
                alpha_database::KeyValueRepo::get(&conn, PROFILE_KV_NAME).ok().flatten().unwrap_or_default()
            }
        };
        let ts = chrono::Utc::now().to_rfc3339();
        let id = format!("{:x}", sha2::Sha256::digest(
            format!("{}|{}|{}|{}|{}", chat_id, own_hex, action, target_pubkey, ts).as_bytes(),
        ));
        {
            let conn = self.db.connection();
            alpha_database::group_roles::append_audit(
                &conn, chat_id, &id, &own_hex, &actor_name, action, target_pubkey, target_name, &ts,
            );
        }
        let group_id = match hex::decode(chat_id) { Ok(g) => g, Err(_) => return };
        let msg = AlphaMessage::GroupAuditEntry {
            id, actor_pubkey: own_hex, actor_name, action: action.to_string(),
            target_pubkey: target_pubkey.to_string(), target_name: target_name.to_string(), ts,
        };
        let payload = match alpha_messages::serialize(&msg) { Ok(p) => p, Err(_) => return };
        let ciphertext = {
            let mut mls = match self.mls_session.lock() { Ok(m) => m, Err(_) => return };
            if mls.group_info(&group_id).is_none() { return; }
            match mls.send(&group_id, &payload) { Ok(c) => c, Err(e) => { tracing::warn!("emit_audit send: {}", e); return; } }
        };
        let epoch = {
            let mls = match self.mls_session.lock() { Ok(m) => m, Err(_) => return };
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };
        let _ = self.publish_application_message(&group_id, epoch, ciphertext);
        self.persist_mls_storage();
    }

    /// Current group permissions as JSON `{"write":..,"invite":..,"edit":..}`,
    /// each "all"|"admins". Drives composer lock + invite/edit gating in the UI.
    pub fn group_permissions(&self, chat_id: String) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        let (w, i, e) = alpha_database::group_roles::permissions(&conn, &chat_id);
        let closed = alpha_database::group_roles::closed_until(&conn, &chat_id);
        let (rm, rl) = alpha_database::group_roles::reactions(&conn, &chat_id);
        Ok(serde_json::json!({
            "write": w, "invite": i, "edit": e, "closed_until": closed,
            "reactions_mode": rm, "reactions": rl,
        }).to_string())
    }

    /// Temporarily close the chat until `until_ts` (unix secs; 0 = reopen).
    /// While closed, only admins/owner can write. OWNER/ADMIN only. Reuses the
    /// permissions sync path (preserves the current write/invite/edit policy).
    pub fn set_group_closed(&self, chat_id: String, until_ts: i64) -> Result<(), MessengerError> {
        let own_hex = self.own_hex_in(&chat_id);
        let (version, w, i, e) = {
            let conn = self.db.connection();
            let role = alpha_database::group_roles::role_of(&conn, &chat_id, &own_hex);
            if !alpha_messages::can_manage_group(&role) {
                return Err(MessengerError::InvalidArgument {
                    description: "только владелец или администратор может закрывать чат".to_string(),
                });
            }
            let (w, i, e) = alpha_database::group_roles::permissions(&conn, &chat_id);
            let v = alpha_database::group_roles::perms_version(&conn, &chat_id) + 1;
            alpha_database::group_roles::set_closed_until(&conn, &chat_id, until_ts);
            alpha_database::group_roles::set_perms_version(&conn, &chat_id, v);
            (v, w, i, e)
        };
        let (reactions_mode, reactions) = {
            let conn = self.db.connection();
            alpha_database::group_roles::reactions(&conn, &chat_id)
        };
        let channel_cfg = { let conn = self.db.connection(); alpha_database::group_roles::channel_cfg_raw(&conn, &chat_id) };
        let msg = AlphaMessage::GroupPermissionsUpdate {
            version, who_can_write: w, who_can_invite: i, who_can_edit: e,
            closed_until: until_ts.max(0),
            reactions_mode, reactions, channel_cfg,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        self.broadcast_permissions_msg(&chat_id, msg)
    }

    /// Re-send the current permissions + channel settings as they are (same
    /// version — receivers that have it ignore it, a new member takes it).
    /// Owner only.
    fn broadcast_current_permissions(&self, chat_id: &str) {
        let own_hex = self.own_hex_in(chat_id);
        let msg = {
            let conn = self.db.connection();
            if alpha_database::group_roles::owner_of(&conn, chat_id).as_deref() != Some(own_hex.as_str()) { return; }
            let (w, i, e) = alpha_database::group_roles::permissions(&conn, chat_id);
            let (mode, list) = alpha_database::group_roles::reactions(&conn, chat_id);
            // Never 0: a later real change must be strictly newer than this one.
            let mut version = alpha_database::group_roles::perms_version(&conn, chat_id);
            if version == 0 { version = 1; alpha_database::group_roles::set_perms_version(&conn, chat_id, 1); }
            AlphaMessage::GroupPermissionsUpdate {
                version,
                who_can_write: w, who_can_invite: i, who_can_edit: e,
                closed_until: alpha_database::group_roles::closed_until(&conn, chat_id),
                reactions_mode: mode, reactions: list,
                channel_cfg: alpha_database::group_roles::channel_cfg_raw(&conn, chat_id),
                ts: chrono::Utc::now().to_rfc3339(),
            }
        };
        if let Err(e) = self.broadcast_permissions_msg(chat_id, msg) {
            tracing::warn!("broadcast_current_permissions {}: {}", chat_id, e);
        }
    }

    /// Shared sender for a prepared `GroupPermissionsUpdate`.
    fn broadcast_permissions_msg(&self, chat_id: &str, msg: AlphaMessage) -> Result<(), MessengerError> {
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let group_id = hex::decode(chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;
        let ciphertext = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if mls.group_info(&group_id).is_none() { return Ok(()); }
            mls.send(&group_id, &payload)
                .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?
        };
        let epoch = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };
        self.publish_application_message(&group_id, epoch, ciphertext)?;
        self.persist_mls_storage();
        Ok(())
    }

    /// Set the group permission policy (OWNER only). Persists locally,
    /// bumps the per-chat version, and broadcasts a `GroupPermissionsUpdate`
    /// to the group so every member enforces the same policy.
    pub fn set_group_permissions(
        &self,
        chat_id: String,
        who_can_write: String,
        who_can_invite: String,
        who_can_edit: String,
    ) -> Result<(), MessengerError> {
        let own_hex = self.own_hex_in(&chat_id);
        let version = {
            let conn = self.db.connection();
            // Only the owner may set group permissions.
            if alpha_database::group_roles::owner_of(&conn, &chat_id).as_deref() != Some(own_hex.as_str()) {
                return Err(MessengerError::InvalidArgument {
                    description: "только владелец может менять права группы".to_string(),
                });
            }
            let v = alpha_database::group_roles::perms_version(&conn, &chat_id) + 1;
            alpha_database::group_roles::set_permissions(&conn, &chat_id, &who_can_write, &who_can_invite, &who_can_edit);
            alpha_database::group_roles::set_perms_version(&conn, &chat_id, v);
            v
        };

        // Preserve the current temp-close state (this call only changes policy).
        let closed_until = {
            let conn = self.db.connection();
            alpha_database::group_roles::closed_until(&conn, &chat_id)
        };
        let (reactions_mode, reactions) = {
            let conn = self.db.connection();
            alpha_database::group_roles::reactions(&conn, &chat_id)
        };
        let channel_cfg = { let conn = self.db.connection(); alpha_database::group_roles::channel_cfg_raw(&conn, &chat_id) };
        let msg = AlphaMessage::GroupPermissionsUpdate {
            version,
            who_can_write,
            who_can_invite,
            who_can_edit,
            closed_until,
            reactions_mode,
            reactions,
            channel_cfg,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        self.broadcast_permissions_msg(&chat_id, msg)
    }

    /// Which reactions members may put (OWNER only, like the permissions):
    /// `mode` "all" | "some" | "none", `list_json` = the emoji for "some".
    /// Synced on the permissions path; every member enforces it on receive.
    pub fn set_group_reactions(&self, chat_id: String, mode: String, list_json: String) -> Result<(), MessengerError> {
        let own_hex = self.own_hex_in(&chat_id);
        let list: Vec<String> = serde_json::from_str::<Vec<String>>(&list_json).unwrap_or_default()
            .into_iter().filter(|e| !e.is_empty() && e.len() <= 32).take(64).collect();
        let (version, w, i, e, closed, mode, list) = {
            let conn = self.db.connection();
            if alpha_database::group_roles::owner_of(&conn, &chat_id).as_deref() != Some(own_hex.as_str()) {
                return Err(MessengerError::InvalidArgument {
                    description: "только владелец может менять реакции группы".to_string(),
                });
            }
            alpha_database::group_roles::set_reactions(&conn, &chat_id, &mode, &list);
            let (mode, list) = alpha_database::group_roles::reactions(&conn, &chat_id);
            let (w, i, e) = alpha_database::group_roles::permissions(&conn, &chat_id);
            let closed = alpha_database::group_roles::closed_until(&conn, &chat_id);
            let v = alpha_database::group_roles::perms_version(&conn, &chat_id) + 1;
            alpha_database::group_roles::set_perms_version(&conn, &chat_id, v);
            (v, w, i, e, closed, mode, list)
        };
        let channel_cfg = { let conn = self.db.connection(); alpha_database::group_roles::channel_cfg_raw(&conn, &chat_id) };
        let msg = AlphaMessage::GroupPermissionsUpdate {
            version, who_can_write: w, who_can_invite: i, who_can_edit: e,
            closed_until: closed,
            reactions_mode: mode, reactions: list, channel_cfg,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        self.broadcast_permissions_msg(&chat_id, msg)
    }

    // --- Channels ---
    // A channel is a group with `channel_cfg.on`: only the owner and admins
    // post; subscribers read, react and (if allowed) comment. Every receiver
    // enforces that (see the sync layer's write check).

    /// Set a group's channel settings (OWNER only) and tell every member.
    /// `cfg_json` = `{"on","comments","protect","join"}`. Turning a channel on
    /// also makes writing admins-only.
    pub fn set_channel_settings(&self, chat_id: String, cfg_json: String) -> Result<(), MessengerError> {
        let own_hex = self.own_hex_in(&chat_id);
        let mut cfg: serde_json::Value = serde_json::from_str(&cfg_json)
            .ok().filter(|v: &serde_json::Value| v.is_object())
            .ok_or_else(|| MessengerError::InvalidArgument { description: "channel settings".to_string() })?;
        // Hiding the owner needs a persona, made at creation; with one, the
        // owner may show who they are and hide again later.
        let persona = crate::personas::get(&chat_id).is_some();
        let wants = cfg.get("anon").and_then(|x| x.as_bool()).unwrap_or(true);
        let was_hidden = hidden_here(&self.db.connection(), &chat_id);
        cfg["anon"] = serde_json::Value::Bool(persona && wants);
        if let Some(o) = cfg.as_object_mut() { o.remove("persona"); }
        let (version, w, i, e, closed, mode, list, raw) = {
            let conn = self.db.connection();
            if alpha_database::group_roles::owner_of(&conn, &chat_id).as_deref() != Some(own_hex.as_str()) {
                return Err(MessengerError::InvalidArgument { description: "only the owner can change the channel".to_string() });
            }
            alpha_database::group_roles::set_channel_cfg(&conn, &chat_id, &cfg);
            if alpha_database::group_roles::is_channel(&conn, &chat_id) {
                let (_, inv, ed) = alpha_database::group_roles::permissions(&conn, &chat_id);
                alpha_database::group_roles::set_permissions(&conn, &chat_id, "admins", &inv, &ed);
            }
            let (w, i, e) = alpha_database::group_roles::permissions(&conn, &chat_id);
            let closed = alpha_database::group_roles::closed_until(&conn, &chat_id);
            let (mode, list) = alpha_database::group_roles::reactions(&conn, &chat_id);
            let v = alpha_database::group_roles::perms_version(&conn, &chat_id) + 1;
            alpha_database::group_roles::set_perms_version(&conn, &chat_id, v);
            (v, w, i, e, closed, mode, list, alpha_database::group_roles::channel_cfg_raw(&conn, &chat_id))
        };
        let msg = AlphaMessage::GroupPermissionsUpdate {
            version, who_can_write: w, who_can_invite: i, who_can_edit: e,
            closed_until: closed, reactions_mode: mode, reactions: list, channel_cfg: raw,
            ts: chrono::Utc::now().to_rfc3339(),
        };
        self.broadcast_permissions_msg(&chat_id, msg)?;
        // Shown now: the members get our profile under the owner's key.
        if persona && was_hidden && !wants {
            reply_profile_into_chat_raw(&self.db, &self.mls_session, &self.identity, (*self.transport).clone(), &chat_id);
        }
        Ok(())
    }

    /// The channel settings JSON (`{}` when the chat is not a channel).
    /// May WE write here, and if not — why. `post_id` empty = an ordinary
    /// message (a post); otherwise a comment of `kind` ("text" | "link" |
    /// "photo" | "video" | "voice" | "file") under that post. The answer the
    /// screen shows before typing; the send path applies the very same rule.
    /// JSON: `{"ok":true}` or `{"ok":false,"reason":"muted","value":<unix end>}`.
    pub fn write_status(&self, chat_id: String, post_id: String, kind: String) -> String {
        use alpha_database::policy::Intent;
        let intent = if post_id.is_empty() {
            Intent::Message
        } else {
            let kind: &'static str = match kind.as_str() {
                "link" => "link", "photo" => "photo", "video" => "video", "voice" => "voice", "file" => "file",
                _ => "text",
            };
            Intent::Comment { post_id, kind }
        };
        let (own_sig, own_pk) = self.own_keys_in(&chat_id);
        let now = chrono::Utc::now().timestamp();
        let conn = self.db.connection();
        let pk = (!own_pk.is_empty()).then_some(own_pk.as_str());
        match alpha_database::policy::may_write(&conn, &chat_id, pk, &own_sig, &intent, now, now) {
            Ok(()) => r#"{"ok":true}"#.to_string(),
            Err(d) => serde_json::json!({"ok": false, "reason": d.code(), "value": d.value()}).to_string(),
        }
    }

    /// Our Nostr pubkey (hex) in a chat: the persona's in an anonymous
    /// channel, our identity's everywhere else. Touches no DB or MLS lock.
    fn own_hex_in(&self, chat_id: &str) -> String {
        if let Some(p) = crate::personas::get(chat_id) {
            return p.keys.public_key().to_hex();
        }
        self.identity.lock().ok()
            .and_then(|g| g.as_ref().map(|id| id.nostr().public_key_hex()))
            .unwrap_or_default()
    }

    /// Our MLS leaf key (hex) in a chat. Touches no DB or MLS lock.
    fn own_sig_in(&self, chat_id: &str) -> String {
        if let Some(p) = crate::personas::get(chat_id) {
            return p.sig_hex;
        }
        self.identity.lock().ok()
            .and_then(|g| g.as_ref().map(|id| hex::encode(id.mls().signer().public())))
            .unwrap_or_default()
    }

    /// The Nostr keys we act with in a chat (envelopes we seal there).
    fn keys_in(&self, chat_id: &str) -> Result<nostr::Keys, MessengerError> {
        if let Some(p) = crate::personas::get(chat_id) {
            return Ok(p.keys);
        }
        let guard = self.identity.lock()
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        Ok(guard.as_ref()
            .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
            .nostr().keypair().keys().clone())
    }

    /// We are gone from a chat: its persona (if any) goes too — keys, cache, inbox.
    fn forget_persona(&self, chat_id: &str) {
        let had = alpha_database::personas::get(&self.db.connection(), chat_id).is_some();
        if !had { return; }
        let _ = alpha_database::personas::delete(&self.db.connection(), chat_id);
        self.refresh_persona_inboxes();
        tracing::info!("persona of {} forgotten", chat_id);
    }

    /// Tell the transport about every persona inbox (pending ones too) and
    /// resubscribe when the set changed.
    fn refresh_persona_inboxes(&self) {
        secret_chat::refresh_transport(&self.db, &self.identity, &self.transport);
    }

    /// Our MLS leaf key (hex) and pubkey (hex) in a chat — the persona's in an
    /// anonymous channel; empty before setup.
    fn own_keys_in(&self, chat_id: &str) -> (String, String) {
        (self.own_sig_in(chat_id), self.own_hex_in(chat_id))
    }

    pub fn channel_settings(&self, chat_id: String) -> String {
        let conn = self.db.connection();
        let raw = alpha_database::group_roles::channel_cfg_raw(&conn, &chat_id);
        let mut v: serde_json::Value = serde_json::from_str(&raw).ok().filter(|v: &serde_json::Value| v.is_object())
            .unwrap_or_else(|| serde_json::json!({}));
        // Whether we own it under a persona (only then can the owner be hidden).
        if crate::personas::get(&chat_id).is_some() { v["persona"] = serde_json::Value::Bool(true); }
        v.to_string()
    }

    /// We opened the channel and saw these posts: one view batch to the
    /// channel (each post counted once per device; nothing in ghost mode).
    pub fn channel_seen(&self, chat_id: String, post_ids_json: String) -> Result<(), MessengerError> {
        if self.ghost_mode() { return Ok(()); }
        let ids: Vec<String> = serde_json::from_str(&post_ids_json).unwrap_or_default();
        let fresh: Vec<String> = {
            let conn = self.db.connection();
            if !alpha_database::group_roles::is_channel(&conn, &chat_id) { return Ok(()); }
            let key = format!("chan_seen_sent:{}", chat_id);
            let mut sent: Vec<String> = KeyValueRepo::get(&conn, &key).ok().flatten()
                .and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
            let fresh: Vec<String> = ids.into_iter()
                .filter(|i| !i.is_empty() && i.len() <= 128 && !sent.contains(i)).take(100).collect();
            if fresh.is_empty() { return Ok(()); }
            sent.extend(fresh.iter().cloned());
            if sent.len() > 2000 { let cut = sent.len() - 2000; sent.drain(..cut); }
            let _ = KeyValueRepo::set(&conn, &key, &serde_json::to_string(&sent).unwrap_or_default());
            fresh
        };
        let own_sig = self.own_sig_in(&chat_id);
        if !own_sig.is_empty() {
            let conn = self.db.connection();
            alpha_database::group_roles::add_views(&conn, &chat_id, &fresh, &own_sig);
        }
        let msg = AlphaMessage::ChannelSeen { post_ids: fresh, forwarded: Vec::new(), ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("chan-seen-{}", uuid::Uuid::new_v4()))
    }

    /// We forwarded these channel posts somewhere: the channel counts it (it
    /// never learns where to). Counted once per post; nothing in ghost mode.
    pub fn channel_forwarded(&self, chat_id: String, post_ids_json: String) -> Result<(), MessengerError> {
        if self.ghost_mode() { return Ok(()); }
        let ids: Vec<String> = serde_json::from_str(&post_ids_json).unwrap_or_default();
        let fresh: Vec<String> = {
            let conn = self.db.connection();
            if !alpha_database::group_roles::is_channel(&conn, &chat_id) { return Ok(()); }
            let key = format!("chan_fwd_sent:{}", chat_id);
            let mut sent: Vec<String> = KeyValueRepo::get(&conn, &key).ok().flatten()
                .and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
            let fresh: Vec<String> = ids.into_iter()
                .filter(|i| !i.is_empty() && i.len() <= 128 && !sent.contains(i)).take(100).collect();
            if fresh.is_empty() { return Ok(()); }
            sent.extend(fresh.iter().cloned());
            if sent.len() > 2000 { let cut = sent.len() - 2000; sent.drain(..cut); }
            let _ = KeyValueRepo::set(&conn, &key, &serde_json::to_string(&sent).unwrap_or_default());
            fresh
        };
        let own_sig = self.own_sig_in(&chat_id);
        if !own_sig.is_empty() {
            let conn = self.db.connection();
            alpha_database::group_roles::add_forwards(&conn, &chat_id, &fresh, &own_sig);
        }
        let msg = AlphaMessage::ChannelSeen { post_ids: Vec::new(), forwarded: fresh, ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("chan-fwd-{}", uuid::Uuid::new_v4()))
    }

    /// Forward counts of channel posts: `{post id: count}`.
    pub fn channel_forwards(&self, chat_id: String, post_ids_json: String) -> String {
        let ids: Vec<String> = serde_json::from_str(&post_ids_json).unwrap_or_default();
        let conn = self.db.connection();
        alpha_database::group_roles::forwards_json(&conn, &chat_id, &ids)
    }

    /// View counts of channel posts: `{post id: count}`.
    pub fn channel_views(&self, chat_id: String, post_ids_json: String) -> String {
        let ids: Vec<String> = serde_json::from_str(&post_ids_json).unwrap_or_default();
        let conn = self.db.connection();
        alpha_database::group_roles::views_json(&conn, &chat_id, &ids)
    }

    /// Is this chat a channel? (Its subscribers stay quiet in it: no profile,
    /// typing or read receipts go there — those would show them to everyone.)
    fn is_channel_chat(&self, chat_id: &str) -> bool {
        let conn = self.db.connection();
        alpha_database::group_roles::is_channel(&conn, chat_id)
    }

    /// Pull-based role sync: if we DON'T know this group's owner locally
    /// (just joined / lost state), ask the group for the current role state by
    /// sending ONE `GroupRoleSyncRequest`. The owner replies once with a
    /// snapshot. No-op when the owner is already known (so it never spams).
    /// Safe to call on group open — it sends at most one request until answered.
    pub fn request_role_sync(&self, chat_id: String) -> Result<(), MessengerError> {
        // Already know the owner → nothing to sync.
        {
            let conn = self.db.connection();
            if alpha_database::group_roles::owner_of(&conn, &chat_id).is_some() {
                return Ok(());
            }
        }
        let group_id = match hex::decode(&chat_id) {
            Ok(g) => g,
            Err(_) => return Ok(()), // not an MLS group id (e.g. Saved Messages)
        };
        let msg = AlphaMessage::GroupRoleSyncRequest { ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let ciphertext = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if mls.group_info(&group_id).is_none() { return Ok(()); }
            mls.send(&group_id, &payload)
                .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?
        };
        let epoch = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };
        self.publish_application_message(&group_id, epoch, ciphertext)?;
        self.persist_mls_storage();
        tracing::info!("request_role_sync: asked for role state in chat {}", chat_id);
        Ok(())
    }

    /// Member Profile Sync: broadcast ONE `ProfileRequest` into the group so
    /// members reply with their current profile (name + avatar + bio). The
    /// caller (UI) gates this behind a TTL / "some member is unknown" check so
    /// it never spams. Each member answers once with a `ProfileUpdate`.
    pub fn request_group_profiles(&self, chat_id: String) -> Result<(), MessengerError> {
        let group_id = match hex::decode(&chat_id) {
            Ok(g) => g,
            Err(_) => return Ok(()),
        };
        let msg = AlphaMessage::ProfileRequest { ts: chrono::Utc::now().to_rfc3339() };
        let payload = alpha_messages::serialize(&msg)
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let ciphertext = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            if mls.group_info(&group_id).is_none() { return Ok(()); }
            mls.send(&group_id, &payload)
                .map_err(|e| MessengerError::CryptoError { description: e.to_string() })?
        };
        let epoch = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0)
        };
        self.publish_application_message(&group_id, epoch, ciphertext)?;
        self.persist_mls_storage();
        tracing::info!("request_group_profiles: asked members for profiles in {}", chat_id);
        Ok(())
    }

    // --- Invite Links ---
    // Invites live in KV, SEPARATE from roles, under
    //   group_invite:<chat_id>:<token>  →  JSON {created_by, created_at,
    //   expires_at, max_uses, used_count, revoked}.
    // OWNER/ADMIN only. No periodic snapshots. The shareable link is
    //   alpha://group/invite/<base64({g:chat_id, t:token, i:inviter_npub})>

    /// Create an invite link. `expires_at_secs` = 0 → permanent; `max_uses` = 0
    /// → unlimited. Returns the full `alpha://group/invite/...` link. OWNER/ADMIN
    /// only (enforced when the group has a recorded owner).
    pub fn create_group_invite(
        &self,
        chat_id: String,
        expires_at_secs: i64,
        max_uses: u32,
    ) -> Result<String, MessengerError> {
        // Permission: OWNER/ADMIN (only enforced once an owner is recorded).
        let own_npub_hex = self.own_hex_in(&chat_id);
        {
            let conn = self.db.connection();
            if alpha_database::group_roles::owner_of(&conn, &chat_id).is_some() {
                let role = alpha_database::group_roles::role_of(&conn, &chat_id, &own_npub_hex);
                // Honor the "who can invite" permission.
                let (_, who_can_invite, _) = alpha_database::group_roles::permissions(&conn, &chat_id);
                if !alpha_messages::can_manage_group(&role) && who_can_invite != "all" {
                    return Err(MessengerError::InvalidArgument {
                        description: "у вас нет прав создавать ссылки".to_string(),
                    });
                }
            }
        }
        let inviter_npub = {
            use nostr::nips::nip19::ToBech32;
            nostr::PublicKey::from_hex(&own_npub_hex).ok()
                .and_then(|pk| pk.to_bech32().ok()).unwrap_or_default()
        };

        let (group_name, is_channel) = {
            let conn = self.db.connection();
            let name = ChatRepo::get(&conn, &chat_id).ok().flatten()
                .and_then(|c| c.name).unwrap_or_default();
            (name, alpha_database::group_roles::is_channel(&conn, &chat_id))
        };
        let token = uuid::Uuid::new_v4().to_string().replace('-', "");
        let now = chrono::Utc::now().timestamp();
        let value = serde_json::json!({
            "created_by": inviter_npub,
            "created_at": now,
            "expires_at": expires_at_secs,
            "max_uses": max_uses,
            "used_count": 0,
            "revoked": false,
        }).to_string();
        {
            let conn = self.db.connection();
            alpha_database::KeyValueRepo::set(&conn, &format!("group_invite:{}:{}", chat_id, token), &value)
                .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
        }
        let by_request = !is_channel || {
            let conn = self.db.connection();
            alpha_database::group_roles::channel_cfg_raw(&conn, &chat_id).contains("\"join\":\"request\"")
        };
        crate::invite_link::encode(&crate::invite_link::Invite {
            channel: is_channel,
            by_request,
            group_hex: chat_id.clone(),
            token,
            inviter_hex: own_npub_hex,
            name: group_name,
        })
        .ok_or_else(|| MessengerError::InternalError { description: "invite link".to_string() })
    }

    /// List a group's invites as JSON: `[{token, link, expires_at, max_uses,
    /// used_count, revoked, active}]`. `active` = not revoked, not expired,
    /// uses remaining.
    pub fn list_group_invites(&self, chat_id: String) -> Result<String, MessengerError> {
        let inviter_hex = self.own_hex_in(&chat_id);
        let conn = self.db.connection();
        let prefix = format!("group_invite:{}:", chat_id);
        let now = chrono::Utc::now().timestamp();
        let group_name = ChatRepo::get(&conn, &chat_id).ok().flatten()
            .and_then(|c| c.name).unwrap_or_default();
        let is_channel = alpha_database::group_roles::is_channel(&conn, &chat_id);
        let by_request = !is_channel
            || alpha_database::group_roles::channel_cfg_raw(&conn, &chat_id).contains("\"join\":\"request\"");
        let mut arr: Vec<serde_json::Value> = Vec::new();
        if let Ok(entries) = alpha_database::KeyValueRepo::list(&conn) {
            for e in entries {
                let token = match e.key.strip_prefix(&prefix) { Some(t) => t, None => continue };
                let v: serde_json::Value = match serde_json::from_str(&e.value) { Ok(v) => v, Err(_) => continue };
                let expires_at = v.get("expires_at").and_then(|x| x.as_i64()).unwrap_or(0);
                let max_uses = v.get("max_uses").and_then(|x| x.as_u64()).unwrap_or(0);
                let used_count = v.get("used_count").and_then(|x| x.as_u64()).unwrap_or(0);
                let revoked = v.get("revoked").and_then(|x| x.as_bool()).unwrap_or(false);
                let expired = expires_at > 0 && expires_at < now;
                let used_up = max_uses > 0 && used_count >= max_uses;
                let active = !revoked && !expired && !used_up;
                let link = crate::invite_link::encode(&crate::invite_link::Invite {
                    channel: is_channel,
                    by_request,
                    group_hex: chat_id.clone(),
                    token: token.to_string(),
                    inviter_hex: inviter_hex.clone(),
                    name: group_name.clone(),
                }).unwrap_or_default();
                arr.push(serde_json::json!({
                    "token": token,
                    "link": link,
                    "expires_at": expires_at,
                    "max_uses": max_uses,
                    "used_count": used_count,
                    "revoked": revoked,
                    "active": active,
                }));
            }
        }
        Ok(serde_json::Value::Array(arr).to_string())
    }

    /// Revoke an invite (all copies of that link stop working). OWNER/ADMIN only.
    pub fn revoke_group_invite(&self, chat_id: String, token: String) -> Result<(), MessengerError> {
        let conn = self.db.connection();
        let key = format!("group_invite:{}:{}", chat_id, token);
        let mut v: serde_json::Value = alpha_database::KeyValueRepo::get(&conn, &key)
            .ok().flatten()
            .and_then(|s| serde_json::from_str(&s).ok())
            .ok_or_else(|| MessengerError::InvalidArgument { description: "invite not found".to_string() })?;
        v["revoked"] = serde_json::Value::Bool(true);
        alpha_database::KeyValueRepo::set(&conn, &key, &v.to_string())
            .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
        Ok(())
    }

    /// Join a group via an invite link (joiner side). Parses the link, then
    /// gift-wraps a `JoinRequest` to the inviter's npub. An online OWNER/ADMIN
    /// validates it and performs the real Add+Welcome → we join.
    pub fn send_join_request(&self, link: String, avatar_b64: String) -> Result<(), MessengerError> {
        use nostr::nips::nip19::FromBech32;

        let inv = crate::invite_link::decode(&link)
            .ok_or_else(|| MessengerError::InvalidArgument { description: "не похоже на ссылку-приглашение".to_string() })?;
        let group_hex = inv.group_hex;
        let token = inv.token;
        let inviter = inv.inviter_hex;
        if group_hex.is_empty() || token.is_empty() || inviter.is_empty() {
            return Err(MessengerError::InvalidArgument { description: "неполная ссылка".to_string() });
        }
        // One-time enforcement (per device): if we already sent a request for this
        // invite token, refuse — an invite link can't be reused to spam requests.
        {
            let conn = self.db.connection();
            if alpha_database::KeyValueRepo::get(&conn, &format!("join_sent:{}", token))
                .ok().flatten().is_some()
            {
                return Err(MessengerError::InvalidArgument {
                    description: "Эта ссылка-приглашение больше недействительна".to_string(),
                });
            }
        }
        let group_id = hex::decode(&group_hex)
            .map_err(|_| MessengerError::InvalidArgument { description: "битый group_id".to_string() })?;
        // Already a member (e.g. the owner tapping the link they generated) — you
        // can't request to join a group you're already in. Applies to everyone.
        if let Ok(mls) = self.mls_session.lock() {
            if mls.group_epoch(&group_id).is_some() {
                return Err(MessengerError::InvalidArgument {
                    description: "Вы уже участник этой группы".to_string(),
                });
            }
        }
        let inviter_pk = nostr::PublicKey::from_bech32(&inviter)
            .or_else(|_| nostr::PublicKey::from_hex(&inviter))
            .map_err(|_| MessengerError::InvalidArgument { description: "битый npub в ссылке".to_string() })?;

        let own_npub = {
            use nostr::nips::nip19::ToBech32;
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .and_then(|id| nostr::PublicKey::from_hex(&id.nostr().public_key_hex()).ok())
                .and_then(|pk| pk.to_bech32().ok())
                .unwrap_or_default()
        };
        // A KeyPackage made for this request: the owner adds us with it and
        // never depends on our published ones (which others may have used up).
        let kp_b64 = {
            use base64::Engine;
            let kp = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?
                .generate_key_packages()
                .and_then(|k| k.key_package_bytes())
                .map_err(|e| MessengerError::CryptoError { description: format!("key package: {}", e) })?;
            base64::engine::general_purpose::STANDARD.encode(kp)
        };
        self.persist_mls_storage();
        // What we show of ourselves: our name; our photo only if we show it to everyone.
        let (name, avatar) = {
            let conn = self.db.connection();
            let name = KeyValueRepo::get(&conn, PROFILE_KV_NAME).ok().flatten().unwrap_or_default();
            let photo_open = alpha_database::privacy::get(&conn, alpha_database::privacy::PHOTO) == alpha_database::privacy::ALL;
            (name, if photo_open && avatar_b64.len() <= 24 * 1024 { avatar_b64 } else { String::new() })
        };
        // Our other phones come in with this one.
        let more: Vec<String> = self.own_other_device_kps().into_iter()
            .map(|kp| base64::Engine::encode(&base64::engine::general_purpose::STANDARD, kp)).collect();
        let payload = serde_json::json!({ "token": token, "requester_npub": own_npub, "kp": kp_b64, "more": more, "name": name, "avatar": avatar })
            .to_string().into_bytes();
        let envelope = MlsEnvelope::new(MlsMessageType::JoinRequest, group_id, 0, payload);

        let own_keys = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                .nostr().keypair().keys().clone()
        };
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        runtime().block_on(async move {
            bridge.send_mls_message(&own_keys, &envelope, Some(&inviter_pk), None).await
        }).map_err(|e| MessengerError::NetworkError { description: format!("send join request: {}", e) })?;
        // Mark this token as used on THIS device so a second tap is refused above.
        {
            let conn = self.db.connection();
            let _ = alpha_database::KeyValueRepo::set(&conn, &format!("join_sent:{}", token), "1");
            // The Welcome into THIS group was asked for by us: it joins directly,
            // never as a request (catch-up checks it against the joined group).
            let _ = alpha_database::KeyValueRepo::set(&conn, &format!("join_wanted:{}", group_hex), "1");
        }
        tracing::info!("send_join_request: sent to {} for group {}", inviter, group_hex);
        Ok(())
    }

    /// Set our video avatar (a short silent mp4 the screen prepared; "" clears
    /// it) and send the profile to everyone who may see our photo.
    pub fn set_my_avatar_video(&self, path: String) -> Result<(), MessengerError> {
        {
            let conn = self.db.connection();
            KeyValueRepo::set(&conn, PROFILE_KV_VIDEO, &path)
                .map_err(|e| MessengerError::DatabaseError { description: e.to_string() })?;
        }
        self.broadcast_my_profile_now()
    }

    /// Our video avatar's file ("" = none).
    pub fn my_avatar_video(&self) -> String {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, PROFILE_KV_VIDEO).ok().flatten().unwrap_or_default()
    }

    /// A member's video avatar file, once downloaded ("" = none).
    pub fn peer_avatar_video(&self, sender_id: String) -> String {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, &format!("peer_video_path:{}", sender_id)).ok().flatten().unwrap_or_default()
    }

    /// Who asked to join: `{"name","avatar"}` as their request carried it.
    pub fn join_request_profile(&self, chat_id: String, requester_npub: String) -> String {
        use nostr::nips::nip19::FromBech32;
        let pk = nostr::PublicKey::from_bech32(requester_npub.trim())
            .or_else(|_| nostr::PublicKey::from_hex(requester_npub.trim()))
            .map(|p| p.to_hex()).unwrap_or_default();
        let conn = self.db.connection();
        let v: serde_json::Value = KeyValueRepo::get(&conn, &format!("join_req:{}:{}", chat_id, pk)).ok().flatten()
            .and_then(|h| serde_json::from_str(&h).ok()).unwrap_or_default();
        serde_json::json!({
            "name": v.get("name").and_then(|x| x.as_str()).unwrap_or_default(),
            "avatar": v.get("avatar").and_then(|x| x.as_str()).unwrap_or_default(),
            "admin": false,
        }).to_string()
    }

    /// Admin side: validate an invite token and, if OK, add the requester.
    /// Called from Kotlin after it has confirmed the requester isn't banned.
    /// Returns silently on invalid/expired/revoked/used-up invites.
    /// Owner-side GLOBAL one-time (or N-use) gate for incoming join requests.
    /// Returns true if this requester may be surfaced as a pending request. The
    /// invite's `max_uses` is enforced across ALL users (not per-device): once
    /// `requested_by` reaches `max_uses`, further NEW requesters are refused.
    /// Idempotent per requester (a request re-processed via catch-up + realtime
    /// doesn't consume twice).
    fn claim_join_request(db: &Arc<DatabasePool>, chat_id: &str, token: &str, requester: &str) -> bool {
        let key = format!("group_invite:{}:{}", chat_id, token);
        let now = chrono::Utc::now().timestamp();
        let conn = db.connection();
        let mut v: serde_json::Value = match alpha_database::KeyValueRepo::get(&conn, &key)
            .ok().flatten().and_then(|s| serde_json::from_str(&s).ok())
        {
            Some(v) => v,
            None => {
                tracing::warn!("claim_join_request: unknown token for {}", chat_id);
                return false;
            }
        };
        let revoked = v.get("revoked").and_then(|x| x.as_bool()).unwrap_or(false);
        let expires_at = v.get("expires_at").and_then(|x| x.as_i64()).unwrap_or(0);
        let max_uses = v.get("max_uses").and_then(|x| x.as_u64()).unwrap_or(0);
        if revoked || (expires_at > 0 && expires_at < now) {
            return false;
        }
        let mut claimed: Vec<String> = v.get("requested_by").and_then(|x| x.as_array())
            .map(|a| a.iter().filter_map(|e| e.as_str().map(String::from)).collect())
            .unwrap_or_default();
        if claimed.iter().any(|c| c == requester) {
            return true; // same requester already claimed — idempotent
        }
        if max_uses > 0 && (claimed.len() as u64) >= max_uses {
            tracing::info!("claim_join_request: invite used up ({} >= {}) for {}", claimed.len(), max_uses, chat_id);
            return false;
        }
        claimed.push(requester.to_string());
        v["requested_by"] = serde_json::Value::from(claimed);
        let _ = alpha_database::KeyValueRepo::set(&conn, &key, &v.to_string());
        true
    }

    pub fn approve_join_request(
        &self,
        chat_id: String,
        requester_npub: String,
        token: String,
    ) -> Result<(), MessengerError> {
        let key = format!("group_invite:{}:{}", chat_id, token);
        let now = chrono::Utc::now().timestamp();
        // Validate.
        let mut v: serde_json::Value = {
            let conn = self.db.connection();
            match alpha_database::KeyValueRepo::get(&conn, &key).ok().flatten()
                .and_then(|s| serde_json::from_str(&s).ok())
            {
                Some(v) => v,
                None => {
                    tracing::warn!("approve_join: unknown invite token for {}", chat_id);
                    return Ok(());
                }
            }
        };
        let revoked = v.get("revoked").and_then(|x| x.as_bool()).unwrap_or(false);
        let expires_at = v.get("expires_at").and_then(|x| x.as_i64()).unwrap_or(0);
        let max_uses = v.get("max_uses").and_then(|x| x.as_u64()).unwrap_or(0);
        let used_count = v.get("used_count").and_then(|x| x.as_u64()).unwrap_or(0);
        if revoked || (expires_at > 0 && expires_at < now) || (max_uses > 0 && used_count >= max_uses) {
            tracing::info!("approve_join: invite not valid (revoked/expired/used-up) for {}", chat_id);
            return Ok(());
        }

        // The KeyPackage the request brought (none in requests from older apps).
        let req_key = {
            use nostr::nips::nip19::FromBech32;
            let pk = nostr::PublicKey::from_bech32(requester_npub.trim())
                .or_else(|_| nostr::PublicKey::from_hex(requester_npub.trim()))
                .map(|p| p.to_hex()).unwrap_or_default();
            format!("join_req:{}:{}", chat_id, pk)
        };
        // With the KeyPackages of the requester's other phones; each must prove the requester.
        let supplied_kps: Vec<Vec<u8>> = {
            use base64::Engine;
            let conn = self.db.connection();
            let held = KeyValueRepo::get(&conn, &req_key).ok().flatten()
                .and_then(|h| serde_json::from_str::<serde_json::Value>(&h).ok())
                .unwrap_or_default();
            let requester_hex = req_key.rsplit(':').next().unwrap_or_default().to_string();
            std::iter::once(held.get("kp").cloned().unwrap_or_default())
                .chain(held.get("more").and_then(|m| m.as_array()).cloned().unwrap_or_default())
                .filter_map(|v| v.as_str().filter(|s| !s.is_empty()).and_then(|s| base64::engine::general_purpose::STANDARD.decode(s).ok()))
                .filter(|kp| alpha_mls::leaf_binding::key_package_owner(kp)
                    .map_or(false, |o| matches!(o, alpha_mls::LeafVerdict::Legacy) || o.npub_hex().as_deref() == Some(requester_hex.as_str())))
                .take(5)
                .collect()
        };

        // Perform the real Add+Commit+Welcome (reuses the proven path).
        self.add_member_with(chat_id.clone(), requester_npub.clone(), supplied_kps, false)?;
        {
            let conn = self.db.connection();
            let _ = KeyValueRepo::delete(&conn, &req_key);
        }

        // Increment used_count.
        v["used_count"] = serde_json::Value::from(used_count + 1);
        {
            let conn = self.db.connection();
            let _ = alpha_database::KeyValueRepo::set(&conn, &key, &v.to_string());
        }
        tracing::info!("approve_join: added {} to {} (uses now {})", requester_npub, chat_id, used_count + 1);
        Ok(())
    }

    /// Reply to a `GroupRoleSyncRequest`: if WE are the owner of this group,
    /// publish the current role snapshot once. Called from the event loop when
    /// a `RoleSyncRequested` event is processed.
    fn respond_role_sync(&self, chat_id: &str) {
        let own_hex = self.own_hex_in(chat_id);
        let is_owner = {
            let conn = self.db.connection();
            alpha_database::group_roles::owner_of(&conn, chat_id)
                .map(|o| o == own_hex)
                .unwrap_or(false)
        };
        if is_owner {
            self.broadcast_role_snapshot(chat_id, false);
            self.broadcast_current_group_metadata(chat_id);
            self.broadcast_current_permissions(chat_id);
        }
    }

    // --- Utility ---

    /// Leave a chat.
    pub fn leave_chat(&self, chat_id: String) -> Result<(), MessengerError> {
        let group_id = hex::decode(&chat_id)
            .map_err(|e| MessengerError::InvalidArgument { description: format!("invalid chat_id hex: {}", e) })?;

        // Say we leave, while still a member: our own leave is only a proposal
        // MLS needs someone to commit, so the owner removes us on this notice.
        // Here, not in the screens, so no way out of a group forgets it.
        {
            let own = self.own_hex_in(&chat_id);
            let is_owner = {
                let conn = self.db.connection();
                alpha_database::group_roles::owner_of(&conn, &chat_id).as_deref() == Some(own.as_str())
            };
            if !own.is_empty() && !is_owner {
                let name = {
                    let conn = self.db.connection();
                    if hidden_here(&conn, &chat_id) { String::new() } else {
                        KeyValueRepo::get(&conn, PROFILE_KV_NAME).ok().flatten().unwrap_or_default()
                    }
                };
                self.emit_audit(&chat_id, "leave", &own, &name);
            }
        }

        // 1. Self-commit leave: creates RemoveProposal + commits it locally.
        //    After this the MLS group state reflects our removal.
        let (commit_bytes, group_id_clone, epoch) = {
            let mut mls = self.mls_session.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let epoch = mls.group_epoch(&group_id).unwrap_or(0);
            let commit_bytes = mls.leave_group(&group_id)
                .map_err(|e| MessengerError::CryptoError { description: format!("failed to leave group: {}", e) })?;
            // Remove MLS group state from memory + persistent storage so we
            // can't decrypt future messages for this group.
            mls.delete_group(&group_id)
                .map_err(|e| MessengerError::CryptoError { description: format!("failed to delete MLS group state: {}", e) })?;
            (commit_bytes, group_id.clone(), epoch)
        };
        // After the guard is gone: persist re-locks MLS.
        self.persist_mls_storage();

        // 2. Publish the commit as a public kind-443 so other members advance
        //    their ratchet and see that we left.
        let envelope = MlsEnvelope::new_group(
            MlsMessageType::Commit,
            group_id_clone.clone(),
            epoch,
            commit_bytes,
        );
        let keys = self.keys_in(&chat_id)?;
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let leave_chat_id = chat_id.clone();
        runtime().spawn(async move {
            match bridge.send_mls_message(&keys, &envelope, None, None).await {
                Ok(eid) => tracing::info!("Broadcast leave-Commit for group {}: event_id={}", leave_chat_id, eid),
                Err(e) => tracing::warn!("Leave-Commit broadcast for {} failed: {}", leave_chat_id, e),
            }
        });

        // 3. Mark the chat as inactive in DB.
        let conn = self.db.connection();
        MlsGroupRepo::update(&conn, &chat_id, None, Some(false))
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;

        drop(conn);
        self.forget_persona(&chat_id);
        tracing::info!("Left chat {} (published commit, deleted MLS state)", chat_id);
        Ok(())
    }

    /// Get messages for a chat with pagination.
    pub fn get_messages(
        &self,
        chat_id: String,
        limit: u32,
        offset: u32,
    ) -> Result<Vec<MessageInfo>, MessengerError> {
        let conn = self.db.connection();
        let messages = MessageRepo::get_by_chat(&conn, &chat_id, limit, offset)
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
        Ok(messages.into_iter().map(MessageInfo::from).collect())
    }

    /// A page of [chat_id] for an export: messages after (after_at, after_id),
    /// oldest first, within [since, until) unix seconds (0 = open). A cursor,
    /// not an offset: messages arriving meanwhile neither repeat nor go missing.
    pub fn export_messages_page(
        &self,
        chat_id: String,
        after_at: String,
        after_id: String,
        since: i64,
        until: i64,
        limit: u32,
    ) -> Result<Vec<MessageInfo>, MessengerError> {
        let conn = self.db.connection();
        let messages = MessageRepo::export_page(&conn, &chat_id, &after_at, &after_id, since, until, limit.min(500))
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
        Ok(messages.into_iter().map(MessageInfo::from).collect())
    }

    /// Reactions of a chat for an export: {"<message id>": {"<emoji>": count}}.
    pub fn export_reactions_json(&self, chat_id: String) -> String {
        let conn = self.db.connection();
        let mut out = serde_json::Map::new();
        for (target, emoji, n) in MessageRepo::reaction_counts(&conn, &chat_id).unwrap_or_default() {
            let e = out.entry(target).or_insert_with(|| serde_json::Value::Object(serde_json::Map::new()));
            if let Some(m) = e.as_object_mut() { m.insert(emoji, serde_json::json!(n)); }
        }
        serde_json::Value::Object(out).to_string()
    }

    /// The discussion of one channel post (comments with their reactions,
    /// edits and deletes), paged like [`get_messages`].
    pub fn get_thread(
        &self,
        chat_id: String,
        post_id: String,
        limit: u32,
        offset: u32,
    ) -> Result<Vec<MessageInfo>, MessengerError> {
        let conn = self.db.connection();
        let messages = MessageRepo::get_thread(&conn, &chat_id, &post_id, limit, offset)
            .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
        Ok(messages.into_iter().map(MessageInfo::from).collect())
    }

    /// What the feed shows under each post that has comments:
    /// `[{"post": id, "count": n, "own": ours, "last_at": rfc3339, "senders": [sender ids]}]`.
    pub fn thread_summaries_json(&self, chat_id: String) -> String {
        let conn = self.db.connection();
        let list = alpha_database::comments::summaries(&conn, &chat_id).unwrap_or_default();
        serde_json::Value::Array(list.into_iter().map(|t| serde_json::json!({
            "post": t.post_id, "count": t.count, "own": t.own, "last_at": t.last_at, "senders": t.recent_senders,
        })).collect()).to_string()
    }

    // --- Privacy: who may reach me (decided on this phone) ---------------

    /// `{"calls": "contacts", "video_calls": …, "new_chats": …, "group_invites": …}`.
    pub fn privacy_settings_json(&self) -> String {
        let conn = self.db.connection();
        let mut o = serde_json::Map::new();
        for (k, v) in alpha_database::privacy::all(&conn) {
            o.insert(k.to_string(), serde_json::Value::String(v));
        }
        serde_json::Value::Object(o).to_string()
    }

    /// Change one privacy setting; `false` for an unknown name or value. A
    /// change to what others see reaches them at once: the profile is sent
    /// again, shaped for each chat, and a new presence secret replaces the one
    /// people who may no longer see our "last seen" still hold.
    pub fn set_privacy(&self, name: String, value: String) -> bool {
        use alpha_database::privacy::{ABOUT, CALLS, LAST_SEEN, PHOTO, STATUS, VIDEO_CALLS};
        let changed = {
            let conn = self.db.connection();
            let before = alpha_database::privacy::get(&conn, &name);
            if !alpha_database::privacy::set(&conn, &name, &value) { return false; }
            before != value
        };
        // Everything a profile copy carries for its receiver: what they see of
        // us and whether they may call (their buttons follow at once).
        if changed && [PHOTO, LAST_SEEN, ABOUT, STATUS, CALLS, VIDEO_CALLS].contains(&name.as_str()) {
            if name == LAST_SEEN { self.rotate_presence_secret(); }
            let _ = self.broadcast_my_profile_now();
        }
        true
    }

    /// My contacts: `[{"npub", "chat_id"}]` (accepted direct chats not removed).
    pub fn contacts_json(&self) -> String {
        use nostr::nips::nip19::ToBech32;
        let conn = self.db.connection();
        serde_json::Value::Array(alpha_database::privacy::live_contacts(&conn).into_iter().map(|(pk, chat)| {
            let npub = nostr::PublicKey::from_hex(&pk).ok().and_then(|p| p.to_bech32().ok()).unwrap_or(pk);
            serde_json::json!({"npub": npub, "chat_id": chat})
        }).collect()).to_string()
    }

    /// Take someone out of my contacts (their chat stays) or back in. Out:
    /// they no longer get what only contacts see — a fresh presence secret and
    /// profile go to everyone still allowed.
    pub fn set_contact(&self, npub_or_hex: String, contact: bool) {
        let Some(pk) = parse_pubkey_hex(&npub_or_hex) else { return };
        alpha_database::privacy::set_contact(&self.db.connection(), &pk, contact);
        if !contact { self.rotate_presence_secret(); }
        let _ = self.broadcast_my_profile_now();
        if contact { self.share_stories_with_contacts(); }
    }

    /// Mirror of the user's block list: a blocked person cannot bring us into
    /// a chat or call us. `npub_or_hex` of the person.
    pub fn set_user_blocked(&self, npub_or_hex: String, blocked: bool) {
        let Some(pk) = parse_pubkey_hex(&npub_or_hex) else { return };
        alpha_database::privacy::set_blocked(&self.db.connection(), &pk, blocked, chrono::Utc::now().timestamp());
    }

    /// Chats waiting in Requests, newest first:
    /// `[{"chat_id", "from": npub, "kind": "direct"|"group", "at": unix secs}]`.
    pub fn chat_requests_json(&self) -> String {
        use nostr::nips::nip19::ToBech32;
        let conn = self.db.connection();
        serde_json::Value::Array(alpha_database::privacy::requests(&conn).into_iter().map(|r| {
            let from = nostr::PublicKey::from_hex(&r.from).ok().and_then(|p| p.to_bech32().ok()).unwrap_or(r.from);
            serde_json::json!({"chat_id": r.chat_id, "from": from, "kind": r.kind, "at": r.at})
        }).collect()).to_string()
    }

    pub fn is_chat_request(&self, chat_id: String) -> bool {
        self.is_request_chat(&chat_id)
    }

    /// The user accepts a request: it becomes an ordinary chat and the other
    /// side now gets our profile (name, photo, presence) like any contact.
    pub fn accept_chat_request(&self, chat_id: String) {
        alpha_database::privacy::accept(&self.db.connection(), &chat_id);
        reply_profile_into_chat_raw(&self.db, &self.mls_session, &self.identity, (*self.transport).clone(), &chat_id);
        self.share_stories_with_contacts();
    }

    /// The user refuses a request. Nobody is told; the chat is forgotten
    /// completely — history, row, our MLS state and the person→chat link — so
    /// the sender is not a contact and their next message opens nothing (a new
    /// invitation would be a new request). `block`: never again from them.
    pub fn decline_chat_request(&self, chat_id: String, block: bool) {
        let request = alpha_database::privacy::request_of(&self.db.connection(), &chat_id);
        let Some(r) = request else { return };
        if let Ok(gid) = hex::decode(&chat_id) {
            if !gid.is_empty() {
                if let Ok(mut mls) = self.mls_session.lock() {
                    let _ = mls.delete_group(&gid);
                }
                self.persist_mls_storage();
            }
        }
        let conn = self.db.connection();
        if block {
            alpha_database::privacy::set_blocked(&conn, &r.from, true, chrono::Utc::now().timestamp());
        }
        let link = format!("direct_chat_for:{}", r.from);
        if KeyValueRepo::get(&conn, &link).ok().flatten().as_deref() == Some(chat_id.as_str()) {
            let _ = KeyValueRepo::delete(&conn, &link);
        }
        let _ = alpha_database::MlsGroupRepo::update(&conn, &chat_id, None, Some(false));
        let _ = alpha_database::MessageRepo::delete_for_chat(&conn, &chat_id);
        let _ = ChatRepo::delete(&conn, &chat_id);
        alpha_database::privacy::accept(&conn, &chat_id);
    }

    /// May the member `sender_id` (their signature key) ring us from `chat_id`?
    pub fn call_allowed(&self, chat_id: String, sender_id: String, video: bool) -> bool {
        let conn = self.db.connection();
        if secret_chat::is_secret(&conn, &chat_id) { return false; }
        let Some(pk) = alpha_database::policy::sender_pubkey(&conn, &chat_id, &sender_id) else { return false };
        alpha_database::privacy::call_allowed(&conn, &chat_id, &pk, video)
    }
}

// --- Private helpers (outside uniffi::export) ---

impl AlphaMessengerCore {
    /// Snapshot the OpenMLS in-RAM storage and persist it to SQLCipher.
    ///
    /// Must be called by every code path that mutates MLS state — create,
    /// join, receive Commit/Application, add/remove member, leave — otherwise
    /// the next process restart loses all encryption keys for that group.
    ///
    /// Called with the mls_session lock NOT held (we acquire it here briefly)
    /// so callers don't need to think about ordering. The whole snapshot is
    /// written in a single transaction.
    pub(crate) fn persist_mls_storage(&self) {
        let _t_wait = std::time::Instant::now();
        let snapshot = match self.mls_session.lock() {
            Ok(s) => {
                let w = _t_wait.elapsed().as_millis();
                if w > 100 { tracing::warn!(target: "TRANSPORT", "MLS_LOCK_WAIT site=persist wait_ms={}", w); }
                let _t_held = std::time::Instant::now();
                let snap = s.snapshot_storage();
                let h = _t_held.elapsed().as_millis();
                if h > 100 { tracing::warn!(target: "TRANSPORT", "MLS_LOCK_HELD site=persist held_ms={} entries={}", h, snap.len()); }
                snap
            }
            Err(e) => {
                tracing::warn!("persist_mls_storage: mls lock poisoned: {}", e);
                return;
            }
        };
        let conn = self.db.connection();
        if let Err(e) = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snapshot) {
            tracing::warn!("persist_mls_storage: save_snapshot failed: {}", e);
        } else {
            tracing::debug!("persist_mls_storage: wrote {} entries", snapshot.len());
        }
        drop(conn);
        if sync_transport_ring(&self.db, &self.transport, &self.ring_synced) {
            self.spawn_resubscribe();
        }
    }

    /// Refresh the live relay subscription (new routing tags) in the background.
    fn spawn_resubscribe(&self) {
        let Some(pk) = self.identity.lock().ok().and_then(|g| g.as_ref().map(|id| id.nostr().public_key())) else { return };
        let transport = (*self.transport).clone();
        runtime().spawn(async move {
            if let Err(e) = transport.subscribe_mls_and_giftwrap(&pk).await {
                tracing::debug!("resubscribe skipped: {}", e);
            }
        });
    }

    /// Wrap an MLS ciphertext in an [`MlsEnvelope`] and publish it to the
    /// configured Nostr relays as a public kind-443 event.
    ///
    /// **Fire-and-forget**: the publish runs on the tokio runtime in the
    /// background and the FFI call returns immediately. This is critical
    /// for UX: a slow or unreachable relay must never block the sender.
    /// The user's typed message is already persisted to the encrypted
    /// local DB *before* this helper is called, so even if the publish
    /// fails the message is preserved (and visible in the sender's UI).
    ///
    /// Errors during the spawned publish are logged but not surfaced.
    /// A future "retry failed sends" pass can scan the DB for messages
    /// whose status is still `SENDING` and republish them.
    fn publish_application_message(
        &self,
        group_id: &[u8],
        epoch: u64,
        ciphertext: Vec<u8>,
    ) -> Result<(), MessengerError> {
        // Every send mutates MLS ratchet state — without this snapshot the
        // next process restart would replay an exhausted nonce and the
        // group becomes undecryptable.
        self.persist_mls_storage();

        let envelope = MlsEnvelope::new(
            MlsMessageType::Application,
            group_id.to_vec(),
            epoch,
            ciphertext,
        );

        // Snapshot Nostr keys without holding the lock across .await.
        let keys = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let id = guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?;
            id.nostr().keypair().keys().clone()
        };

        // Enqueue to the outbox BEFORE publishing. If the publish below fails
        // (offline / relay down / process killed) the entry stays and is
        // re-sent by `replay_outbox()` on the next reconnect — guaranteed
        // delivery instead of a silently-dropped fire-and-forget event.
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let group_id_len = group_id.len();
        runtime().spawn(async move {
            match bridge.send_mls_message(&keys, &envelope, None, None).await {
                Ok(event_id) => tracing::info!(
                    "Published MLS application message: group_id_len={}, epoch={}, event_id={}",
                    group_id_len, epoch, event_id,
                ),
                Err(e) => tracing::warn!(
                    "Publish failed for group_id_len={}, epoch={}: {}",
                    group_id_len, epoch, e,
                ),
            }
        });

        Ok(())
    }

    /// Like [`publish_application_message`] but BLOCKS until the relay accepts
    /// the event (or errors). Used by the fast-batch media path so the caller
    /// knows each event landed before sending the next — no dropped events, no
    /// blind delays. Returns an error the caller can retry on.
    fn publish_application_message_confirmed(
        &self,
        group_id: &[u8],
        epoch: u64,
        ciphertext: Vec<u8>,
    ) -> Result<(), MessengerError> {
        self.publish_application_message_confirmed_at(group_id, epoch, ciphertext, None)
    }

    /// Like [`publish_application_message_confirmed`] but pins the event's
    /// created_at (used by flush so offline messages keep their compose order).
    fn publish_application_message_confirmed_at(
        &self,
        group_id: &[u8],
        epoch: u64,
        ciphertext: Vec<u8>,
        created_at_secs: Option<u64>,
    ) -> Result<(), MessengerError> {
        // Persist ratchet state mutated by the send before we go to the network.
        self.persist_mls_storage();

        let envelope = MlsEnvelope::new(
            MlsMessageType::Application,
            group_id.to_vec(),
            epoch,
            ciphertext,
        );
        let keys = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let id = guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?;
            id.nostr().keypair().keys().clone()
        };
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        runtime()
            .block_on(async move { bridge.send_mls_message_at(&keys, &envelope, None, None, created_at_secs).await })
            .map(|_| ())
            .map_err(|e| MessengerError::NetworkError {
                description: format!("confirmed publish failed: {}", e),
            })
    }

    /// Queue a persistent chat message (PLAINTEXT) and immediately try to flush.
    ///
    /// Defer-encryption: we store the serialized AlphaMessage and only MLS-
    /// encrypt + publish at flush time. So when offline nothing is encrypted
    /// (the ratchet isn't advanced for an undeliverable message); on reconnect
    /// — AFTER catch-up has applied any new Commits — messages are encrypted on
    /// the CURRENT group state, in compose order, and published. Returns Ok even
    /// offline (the message waits in the queue).
    /// Collect the ntfy wake-topics for every OTHER participant of `chat_id`,
    /// applying a per-topic debounce. Returns `(server_url, topics)`; the caller
    /// publishes a contentless "wake" to each after the relay ACK so a
    /// backgrounded/Dozing recipient pulls the message within seconds instead of
    /// waiting for a throttled timer. Empty when push isn't configured or there
    /// are no fresh recipients.
    ///
    /// The topic is `hash(recipient_pubkey)` — the wake carries NO sender, chat,
    /// or content. Self is always excluded.
    fn collect_wake_topics(&self, chat_id: &str) -> (Option<String>, Vec<String>) {
        if !secret_chat::wakes_peers(&self.db.connection(), chat_id) {
            return (None, Vec::new());
        }
        let server = match self.ntfy_server_url.lock().ok().and_then(|g| g.clone()) {
            Some(s) => s,
            None => return (None, Vec::new()),
        };

        // Gather recipient pubkey hexes (excluding self).
        let mut pubkey_hexes: Vec<String> = Vec::new();
        // MLS before the database, never inside it: catch-up holds MLS while it
        // writes to the database, so the other order freezes the core.
        let own_sig_hex = self.own_sig_in(chat_id);
        let members = hex::decode(chat_id).ok()
            .and_then(|gid| self.mls_session.lock().ok().and_then(|mls| mls.group_info(&gid).map(|s| s.members)))
            .unwrap_or_default();
        {
            let conn = self.db.connection();
            // 1:1 peer mapping.
            if let Ok(Some(h)) =
                alpha_database::KeyValueRepo::get(&conn, &format!("peer_npub_for_chat:{}", chat_id))
            {
                if !h.is_empty() { pubkey_hexes.push(h); }
            }
            // Group members (if this chat is an MLS group we have loaded).
            {
                for m in members {
                    let sender_id = hex::encode(&m.signature_key);
                    if !own_sig_hex.is_empty() && sender_id == own_sig_hex { continue; }
                    if let Ok(Some(h)) = alpha_database::KeyValueRepo::get(
                        &conn, &format!("member_npub:{}", sender_id),
                    ) {
                        if !h.is_empty() { pubkey_hexes.push(h); }
                    }
                }
            }
        }
        if pubkey_hexes.is_empty() { return (Some(server), Vec::new()); }
        pubkey_hexes.sort();
        pubkey_hexes.dedup();

        // Derive topics, apply per-topic debounce.
        // Recipients are woken only on the private topics they shared over MLS.
        let wake_topics = self.contact_wake_topics();
        const WAKE_DEBOUNCE_SECS: u64 = 5;
        let mut topics: Vec<String> = Vec::new();
        if let Ok(mut dbnc) = self.peer_wake_debounce.lock() {
            let now = std::time::Instant::now();
            for ph in &pubkey_hexes {
                let Some(topic) = wake_topics.get(&ph.to_lowercase()).cloned() else { continue };
                let fresh = dbnc
                    .get(&topic)
                    .map(|t| now.duration_since(*t).as_secs() >= WAKE_DEBOUNCE_SECS)
                    .unwrap_or(true);
                if fresh {
                    dbnc.insert(topic.clone(), now);
                    topics.push(topic);
                }
            }
            // Keep the map from growing unbounded.
            dbnc.retain(|_, t| now.duration_since(*t).as_secs() < 600);
        }
        (Some(server), topics)
    }

    /// Send an ntfy peer-wake to one or more recipients (by Nostr pubkey hex)
    /// RIGHT NOW, outside the message-send path.
    ///
    /// This is the fix for unreliable Welcome delivery: when we gift-wrap a
    /// Welcome to add a contact / group member, the recipient is often in Doze
    /// and won't fetch the Welcome until their next slow sync window (minutes
    /// to hours). Without a wake, the first Welcome frequently "doesn't arrive
    /// in time" and the new chat sits empty on their side. `queue_and_send`
    /// already wakes peers on message send — this method covers the Welcome
    /// path (and any future control-message path) so a wake is always fired
    /// when we publish something a peer must fetch promptly.
    ///
    /// Reuses the same per-topic 5s debounce as `collect_wake_topics` so a
    /// burst of Welcomes (e.g. `create_group_with_members`) doesn't hammer the
    /// ntfy server. Fire-and-forget: a failed wake is only logged.
    fn send_peer_wake_now(&self, recipient_pubkey_hexes: &[String]) {
        if recipient_pubkey_hexes.is_empty() {
            return;
        }
        let server = match self.ntfy_server_url.lock().ok().and_then(|g| g.clone()) {
            Some(s) => s,
            None => {
                tracing::debug!(
                    target: "TRANSPORT",
                    "peer-wake skipped (no ntfy server configured) for {} recipient(s)",
                    recipient_pubkey_hexes.len(),
                );
                return;
            }
        };

        // Dedup + derive topics + apply the 5s debounce.
        let mut pubkey_hexes: Vec<String> = recipient_pubkey_hexes
            .iter()
            .filter(|h| !h.is_empty())
            .cloned()
            .collect();
        pubkey_hexes.sort();
        pubkey_hexes.dedup();

        // Recipients are woken only on the private topics they shared over MLS.
        let wake_topics = self.contact_wake_topics();
        const WAKE_DEBOUNCE_SECS: u64 = 5;
        let mut topics: Vec<String> = Vec::new();
        if let Ok(mut dbnc) = self.peer_wake_debounce.lock() {
            let now = std::time::Instant::now();
            for ph in &pubkey_hexes {
                let Some(topic) = wake_topics.get(&ph.to_lowercase()).cloned() else { continue };
                let fresh = dbnc
                    .get(&topic)
                    .map(|t| now.duration_since(*t).as_secs() >= WAKE_DEBOUNCE_SECS)
                    .unwrap_or(true);
                if fresh {
                    dbnc.insert(topic.clone(), now);
                    topics.push(topic);
                }
            }
            dbnc.retain(|_, t| now.duration_since(*t).as_secs() < 600);
        }
        if topics.is_empty() {
            return;
        }

        // Fire-and-forget: publish each wake. A slow/unreachable ntfy server
        // must never block the Welcome path.
        let topics_clone = topics.clone();
        let reliability = self.reliability.clone();
        runtime().spawn(async move {
            let mut any_ok = false;
            for topic in topics_clone {
                let client = NtfyClient::new(&server, &topic);
                match client.publish(&NtfyMessage::wake()).await {
                    Ok(_) => {
                        any_ok = true;
                        tracing::info!(
                            target: "TRANSPORT",
                            "peer-wake (welcome/control) sent to topic {}", topic,
                        );
                    }
                    Err(e) => tracing::debug!(
                        target: "TRANSPORT",
                        "peer-wake (welcome/control) failed for {}: {}", topic, e,
                    ),
                }
            }
            // Feed the reliability controller.
            reliability.record_peer_wake(any_ok);
        });
    }

    fn queue_and_send(&self, chat_id: &str, payload_bytes: &[u8], msg_id: &str) -> Result<(), MessengerError> {
        // A local chat has no peers: whatever was stored is all there is to do.
        if is_local_chat(chat_id) { return Ok(()); }
        // Scheduled: keep the ready bytes, publish at the chosen time. Before
        // seq allocation, so a held message never opens a gap others see.
        if self.hold_if_scheduled(chat_id, msg_id, payload_bytes, "outbox") {
            return Ok(());
        }
        let t_send_start_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis()).unwrap_or(0);

        // Allocate a monotonic seq for content
        // messages, stamp it onto the serialized payload, and enqueue the
        // seq-bearing payload into the delivery outbox (state=pending). The
        // outbox row lives until ALL recipients ACK it; the resend timer re-
        // publishes from here. Control messages (ReadSignal, ChatState,
        // profile/role/..., DeliveryAck, ResendRequest) skip this — they're
        // not content, don't need delivery guarantees, and acking an ack
        // would recurse.
        //
        // This is the surgical injection point: we deserialize the already-
        // serialized payload, stamp seq, re-serialize. The double-serialize
        // is negligible (small JSON). `final_payload` is what gets encrypted
        // + published + stored in pending_sends (so flush_pending_sends
        // retries with the SAME seq-bearing payload).
        let (final_payload, outbox_seq): (Vec<u8>, Option<u64>) = {
            // Try to deserialize; if it fails (not an AlphaMessage), send as-is.
            match alpha_messages::deserialize(payload_bytes) {
                Ok(mut msg) if msg.is_delivery_eligible() => {
                    // Allocate seq (sender's own counter).
                    let own_sig_hex = self.own_sig_in(chat_id);
                    if !own_sig_hex.is_empty() && msg.seq().is_none() {
                        let conn = self.db.connection();
                        match alpha_database::delivery::DeliveryRepo::alloc_seq(
                            &conn, chat_id, &own_sig_hex,
                        ) {
                            Ok(seq) => {
                                msg.set_seq(seq);
                                let re = alpha_messages::serialize(&msg)
                                    .map_err(|e| MessengerError::InternalError {
                                        description: format!("re-serialize with seq: {}", e),
                                    })?;
                                // No outbox copy: resends are off and receipts are per
                                // message (DeliveredSignal) — a copy would only pile up.
                                tracing::info!(
                                    target: "DELIVERY",
                                    "SEQ_ALLOC chat={} msg_id={} seq={}",
                                    chat_id, msg_id, seq,
                                );
                                (re, Some(seq))
                            }
                            Err(e) => {
                                tracing::warn!("alloc_seq failed: {} — sending unsequenced", e);
                                (payload_bytes.to_vec(), None)
                            }
                        }
                    } else {
                        // seq already set (rare) or no own_sig → send as-is.
                        (payload_bytes.to_vec(), None)
                    }
                }
                _ => {
                    // Control message or unparseable → send as-is, no outbox.
                    (payload_bytes.to_vec(), None)
                }
            }
        };

        let final_payload = with_sent_ms(final_payload, || self.row_sent_ms(msg_id));
        let final_payload = if self.row_silent(msg_id) { with_silent(final_payload) } else { final_payload };

        let pending_id = {
            let conn = self.db.connection();
            PendingSendRepo::enqueue(&conn, chat_id, msg_id, &final_payload)
                .map_err(|e| MessengerError::DatabaseError { description: format!("queue send: {}", e) })?
        };
        tracing::info!(
            target: "DELIVERY_TRACE",
            "SEND_ENQUEUE msg_id={} chat_id={} t_ms={} seq={:?}",
            msg_id, chat_id, t_send_start_ms, outbox_seq
        );
        let group_id = match hex::decode(chat_id) {
            Ok(g) => g,
            Err(_) => return Ok(()), // bad chat_id — flush will clean it up
        };
        // Encrypt now (fast, local). If the group state isn't usable right now,
        // leave it queued for flush_pending_sends to retry.
        let enc = {
            let _t_wait = std::time::Instant::now();
            let mut mls = match self.mls_session.lock() {
                Ok(m) => {
                    let w = _t_wait.elapsed().as_millis();
                    if w > 100 { tracing::warn!(target: "TRANSPORT", "MLS_LOCK_WAIT site=send wait_ms={}", w); }
                    m
                }
                Err(e) => return Err(MessengerError::InternalError { description: e.to_string() }),
            };
            let _t_held = std::time::Instant::now();
            let r = match mls.send(&group_id, &final_payload) {
                Ok(ct) => {
                    let ep = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
                    Some((ct, ep))
                }
                Err(_) => None,
            };
            let h = _t_held.elapsed().as_millis();
            if h > 100 { tracing::warn!(target: "TRANSPORT", "MLS_LOCK_HELD site=send held_ms={}", h); }
            r
        };
        // If encryption succeeded and we have a delivery_outbox row, mark it
        // as sent (bump send_count + last_sent_at). The row stays pending
        // until ACKed.
        if let Some(seq) = outbox_seq {
            if enc.is_some() {
                let conn = self.db.connection();
                // Mark the outbox row matching (chat_id, msg_id, seq) as sent.
                let _ = conn.execute(
                    "UPDATE delivery_outbox SET send_count = send_count + 1, last_sent_at = ?1
                     WHERE chat_id = ?2 AND msg_id = ?3 AND seq = ?4",
                    rusqlite::params![
                        chrono::Utc::now().to_rfc3339(),
                        chat_id, msg_id, seq as i64,
                    ],
                );
            }
        }
        if let Some((ciphertext, epoch)) = enc {
            self.persist_mls_storage();
            let envelope = MlsEnvelope::new(MlsMessageType::Application, group_id, epoch, ciphertext);
            let keys = {
                let guard = self.identity.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
                guard.as_ref()
                    .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                    .nostr().keypair().keys().clone()
            };
            // Fire-and-forget publish (returns instantly — no blocking the UI on
            // a slow relay). Delete the queue row only once a relay confirms; if
            // it fails (offline), the row stays and flush_pending_sends retries.
            let bridge = NostrMlsBridge::new((*self.transport).clone());
            let db = self.db.clone();
            let cb = self.callback.clone();
            let mid = msg_id.to_string();
            // Collect peer-wake topics now (needs DB/MLS locks on this thread);
            // publish them only AFTER the relay ACK so we never wake a recipient
            // for a send that failed.
            let (wake_server, wake_topics) = self.collect_wake_topics(chat_id);
            let t_publish_start_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis()).unwrap_or(0);
            tracing::info!(
                target: "DELIVERY_TRACE",
                "RELAY_PUBLISH_START msg_id={} chat_id={} t_ms={} encrypt_dt_ms={}",
                mid, chat_id, t_publish_start_ms,
                t_publish_start_ms.saturating_sub(t_send_start_ms)
            );
            runtime().spawn(async move {
                match bridge.send_mls_message(&keys, &envelope, None, None).await {
                    Ok(eid) => {
                        let t_ack_ms = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_millis()).unwrap_or(0);
                        tracing::info!(
                            target: "DELIVERY_TRACE",
                            "RELAY_ACK msg_id={} event_id={} t_ms={} relay_dt_ms={}",
                            mid, eid, t_ack_ms,
                            t_ack_ms.saturating_sub(t_publish_start_ms)
                        );
                        {
                            let conn = db.connection();
                            let _ = PendingSendRepo::delete(&conn, pending_id);
                        }
                        // SENDING → SENT (one tick) now that a relay accepted it.
                        cb.on_delivery_update(&mid, "sent");
                        // Peer-wake: nudge backgrounded recipients to catch up now.
                        if let Some(server) = wake_server {
                            for topic in wake_topics {
                                let client = NtfyClient::new(&server, &topic);
                                match client.publish(&NtfyMessage::wake()).await {
                                    Ok(_) => tracing::info!(target: "TRANSPORT", "peer-wake sent to topic {}", topic),
                                    Err(e) => tracing::debug!(target: "TRANSPORT", "peer-wake failed for {}: {}", topic, e),
                                }
                            }
                        }
                    }
                    Err(e) => {
                        tracing::warn!(
                            target: "DELIVERY_TRACE",
                            "RELAY_FAIL msg_id={} err={} t_ms={}",
                            mid, e,
                            std::time::SystemTime::now()
                                .duration_since(std::time::UNIX_EPOCH)
                                .map(|d| d.as_millis()).unwrap_or(0)
                        );
                    }
                }
            });
        }
        Ok(())
    }

    /// Encrypt + publish every queued outgoing message, in compose order,
    /// deleting each on confirmed relay acceptance. Encryption happens HERE (not
    /// at compose time) so it always uses the latest MLS state. Stops at the
    /// first publish failure (offline) — the rest stay queued for the next call.
    ///
    /// IMPORTANT: callers must run catch-up FIRST on reconnect so we encrypt on
    /// an up-to-date epoch.
    pub fn flush_pending_sends(&self) -> u32 {
        // Single-flight: a live send and a reconnect trigger must not interleave.
        let _guard = match self.flush_lock.try_lock() {
            Ok(g) => g,
            Err(_) => return 0, // another flush is running; it'll cover us
        };
        let entries = {
            let conn = self.db.connection();
            PendingSendRepo::list(&conn).unwrap_or_default()
        };
        if entries.is_empty() {
            return 0;
        }
        let mut sent = 0u32;
        for e in entries {
            let group_id = match hex::decode(&e.chat_id) {
                Ok(g) => g,
                Err(_) => { let conn = self.db.connection(); let _ = PendingSendRepo::delete(&conn, e.id); continue; }
            };
            // Encrypt on the CURRENT group state (advances ratchet).
            let enc = {
                let mut mls = match self.mls_session.lock() {
                    Ok(m) => m,
                    Err(_) => return sent,
                };
                match mls.send(&group_id, &e.payload) {
                    Ok(ct) => {
                        let ep = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
                        Some((ct, ep))
                    }
                    Err(err) => {
                        // Group missing / unrecoverable for this entry → drop it
                        // rather than blocking the queue forever.
                        tracing::warn!("flush: encrypt failed for {} (dropping): {:?}", e.msg_id, err);
                        None
                    }
                }
            };
            let (ciphertext, epoch) = match enc {
                Some(x) => x,
                None => { let conn = self.db.connection(); let _ = PendingSendRepo::delete(&conn, e.id); continue; }
            };
            // Pin the event's created_at to the original COMPOSE time so the
            // receiver shows offline-composed messages in send order (they're
            // all published now, within the same second otherwise → scattered).
            let compose_secs = chrono::DateTime::parse_from_rfc3339(&e.created_at)
                .ok().map(|dt| dt.timestamp() as u64);
            match self.publish_application_message_confirmed_at(&group_id, epoch, ciphertext, compose_secs) {
                Ok(_) => {
                    let conn = self.db.connection();
                    let _ = PendingSendRepo::delete(&conn, e.id);
                    self.callback.on_delivery_update(&e.msg_id, "sent"); // SENDING → SENT
                    sent += 1;
                }
                Err(_) => {
                    // Offline / relay down → stop; remaining (and this one) stay
                    // queued. Order is preserved for the next flush.
                    tracing::info!("flush: publish failed (offline?) — {} message(s) still queued", 1);
                    break;
                }
            }
        }
        sent
    }

    /// IDs of messages still waiting in the send queue (not yet accepted by a
    /// relay). The UI shows these with the "sending" clock; everything else
    /// that's outgoing is at least "sent". Cheap — used on every reload.
    pub fn pending_send_ids(&self) -> Vec<String> {
        let conn = self.db.connection();
        let mut ids: Vec<String> = PendingSendRepo::list(&conn)
            .unwrap_or_default()
            .into_iter()
            .map(|e| e.msg_id)
            .collect();
        // The MEDIA queue is unfinished sending work too, and it was missing
        // here. The consequence shows after process death: the queue row is
        // intact, the download resumes, but the screen knows nothing — its
        // transfer state lives only in memory and dies with the process.
        // The bubble showed a clock and NOT ONE icon: no ✕ to cancel, no ring
        // to show work continues.
        //
        // The answer to "what is unsent right now" must be one and include both
        // kinds of queues — otherwise the screen has nothing to restore from.
        if let Ok(media) = PendingMediaRepo::list(&conn) {
            for e in media {
                if !ids.contains(&e.msg_id) {
                    ids.push(e.msg_id);
                }
            }
        }
        ids
    }

    /// Re-publish every queued (undelivered) outbox envelope with a confirmed
    /// send, deleting each on relay acceptance. Called on app start and whenever
    /// connectivity returns, so messages composed while offline / dropped by a
    /// relay are delivered as soon as a relay is reachable. Returns the number
    /// of envelopes successfully (re)delivered.
    pub fn replay_outbox(&self) -> u32 {
        let entries = {
            let conn = self.db.connection();
            OutboxRepo::list(&conn).unwrap_or_default()
        };
        if entries.is_empty() {
            return 0;
        }
        tracing::info!("replay_outbox: {} queued envelope(s)", entries.len());
        // Snapshot Nostr keys once.
        let keys = match self.identity.lock().ok().and_then(|g| g.as_ref().map(|id| id.nostr().keypair().keys().clone())) {
            Some(k) => k,
            None => return 0,
        };
        let mut delivered = 0u32;
        for e in entries {
            let envelope = MlsEnvelope::new(
                MlsMessageType::Application,
                e.group_id.clone(),
                e.epoch as u64,
                e.ciphertext.clone(),
            );
            let bridge = NostrMlsBridge::new((*self.transport).clone());
            let keys2 = keys.clone();
            let result = runtime()
                .block_on(async move { bridge.send_mls_message(&keys2, &envelope, None, None).await });
            let conn = self.db.connection();
            match result {
                Ok(_) => { let _ = OutboxRepo::delete(&conn, e.id); delivered += 1; }
                Err(err) => {
                    let _ = OutboxRepo::increment_retry(&conn, e.id);
                    tracing::warn!("replay_outbox: still failing for id={}: {}", e.id, err);
                }
            }
        }
        tracing::info!("replay_outbox: delivered {}", delivered);
        delivered
    }

    /// Generate a fresh MLS KeyPackage and publish it to Nostr relays.
    ///
    /// Other clients who know our npub can then call `fetch_key_packages`
    /// to retrieve a KeyPackage for us and use it to create a Welcome that
    /// invites us into a 1:1 chat. Without this, "Add contact by npub"
    /// never works — the other side has nothing to wrap into a Welcome.
    fn publish_own_key_package(&self) -> Result<(), MessengerError> {
        // MLS KeyPackages are ONE-TIME by spec: one KP = one Welcome.
        // A single KP on the relay means the second Welcome to the same
        // user (e.g. group invite right after a DM was just opened) fails
        // with `NoMatchingKeyPackage`. The peer's MLS storage marked our
        // init_key consumed after the first join.
        //
        // Solution: publish a POOL of N independent KPs at startup. The
        // peer's `fetch_key_packages` returns the whole pool and the
        // inviter picks one at random so concurrent invites don't collide.
        //
        // 24-hour throttle prevents republishing the pool on every app
        // open — we keep the same pool for a day, which is fine because
        // the pool has plenty of unused KPs.
        // Bigger pool + faster refresh so bursts of group invites to the same
        // peer always find an unused one-time KeyPackage (each invite consumes
        // one). 30 covers heavy use; republishing every ~3h tops the pool back
        // up as KeyPackages get consumed by joins.
        // 12, not 30: 30 KeyPackages fired at once got the account banned by a
        // relay (spam limit). They also go out one by one, see below.
        const POOL_SIZE: usize = 12;
        const KP_TTL_HOURS: i64 = 3;

        let keys = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
                .nostr().keypair().keys().clone()
        };
        // The throttle belongs to THIS identity: a new account on the same
        // database must publish at once, not inherit "published 1h ago" from
        // the account it replaced (it then stayed unreachable for hours).
        let kp_kv_key = format!("last_published_keypackage_at:{}", keys.public_key().to_hex());

        // One publish at a time. start_sync runs several times in a row (database
        // open, end of sign-up, back to the foreground); each used to pass the
        // throttle before the first had finished, and the pool went out again and
        // again (once a minute on a slow relay) — load the relays answer with
        // rate limits.
        // Per ACCOUNT: a publish still running for the previous account must not
        // stop a just-created one from publishing (it then stayed unfindable).
        static KP_PUBLISHING: std::sync::Mutex<Option<std::collections::HashSet<String>>> = std::sync::Mutex::new(None);
        let me = keys.public_key().to_hex();
        let claimed = KP_PUBLISHING.lock()
            .map(|mut g| g.get_or_insert_with(Default::default).insert(me.clone()))
            .unwrap_or(true);
        if !claimed {
            tracing::info!(target: "ACCOUNT", "KeyPackage pool publish already running for this account — skipped");
            return Ok(());
        }
        struct Release(String);
        impl Drop for Release {
            fn drop(&mut self) {
                if let Ok(mut g) = KP_PUBLISHING.lock() {
                    if let Some(s) = g.as_mut() { s.remove(&self.0); }
                }
            }
        }
        let _release = Release(me);

        let now = chrono::Utc::now().timestamp();
        // Accounts from before the KeyPackages were mirrored to the well-known
        // relays publish once more now, whatever the TTL says.
        let mirror_key = format!("keypackages_mirrored_v1:{}", keys.public_key().to_hex());
        let mirrored_before = {
            let conn = self.db.connection();
            KeyValueRepo::get(&conn, &mirror_key).ok().flatten().is_some()
        };
        if mirrored_before {
            let conn = self.db.connection();
            if let Ok(Some(last_ts_str)) = KeyValueRepo::get(&conn, &kp_kv_key) {
                if let Ok(last_ts) = last_ts_str.parse::<i64>() {
                    let age_hours = (now - last_ts) / 3600;
                    if age_hours < KP_TTL_HOURS {
                        tracing::info!(
                            target: "ACCOUNT",
                            "KeyPackage pool publish skipped — published {}h ago (TTL {}h)",
                            age_hours, KP_TTL_HOURS,
                        );
                        return Ok(());
                    }
                }
            }
        }

        let kp_pool: Vec<Vec<u8>> = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let mut bag = Vec::with_capacity(POOL_SIZE);
            for _ in 0..POOL_SIZE {
                let kp = mls.generate_key_packages()
                    .map_err(|e| MessengerError::CryptoError { description: format!("kp generate: {}", e) })?;
                bag.push(kp.key_package_bytes()
                    .map_err(|e| MessengerError::CryptoError { description: format!("kp serialize: {}", e) })?);
            }
            bag
        };

        // Persist BEFORE publishing — every KP's private material is now in
        // OpenMLS storage; if we publish a KP and crash before the snapshot
        // hits SQLCipher the peer would open a Welcome we can't reciprocate.
        self.persist_mls_storage();

        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let rt = runtime();
        let mut published = 0usize;
        let mut events = Vec::with_capacity(POOL_SIZE);
        let device_id = alpha_database::devices::this_device_id(&self.db.connection());
        for (i, kp_bytes) in kp_pool.into_iter().enumerate() {
            let ev = match NostrMlsBridge::key_package_event(&keys, &kp_bytes, Some(&device_id)) {
                Ok(ev) => ev,
                Err(e) => { tracing::warn!("KP pool sign failed at index {}: {}", i, e); continue; }
            };
            // Paced by the transport (a few events per second). No sleep here:
            // this runs inside start_sync, which must not stall — and a
            // `tokio::time::sleep` built outside the runtime panics and aborted
            // the whole start (receiving never started, 2026-10-13).
            match rt.block_on(bridge.relay_client().publish(ev.clone())) {
                Ok(_) => published += 1,
                Err(e) => tracing::warn!("KP pool publish failed at index {}: {}", i, e),
            }
            events.push(ev);
        }
        // The same KeyPackages on the well-known relays too: whether someone can
        // add us must not hang on our own relay list (one relay down or banning
        // us left an account unreachable — "no KeyPackage, peer must publish").
        // `None`: every well-known relay is already ours — nothing to copy.
        let mirror = rt.block_on(bridge.mirror_key_packages(events));
        let mirrored = mirror.unwrap_or(0);
        if published == 0 && mirrored == 0 {
            return Err(MessengerError::NetworkError {
                description: "couldn't publish any KeyPackage to relays".to_string(),
            });
        }
        if mirror.map_or(published > 0, |n| n > 0) {
            let conn = self.db.connection();
            let _ = KeyValueRepo::set(&conn, &mirror_key, "1");
        }
        tracing::info!(target: "ACCOUNT", "Published {} KeyPackage(s) to own relays, {} to the well-known ones (pool of {})", published, mirrored, POOL_SIZE);

        // Verify our own KeyPackage is actually VISIBLE on the relays before we
        // consider the account publishable (agent's bulletproof check). A relay
        // can answer OK but a reconnect / blip can still mean peers see nothing.
        // We log the result; we don't hard-fail (propagation lag is normal) but
        // only mark the throttle timestamp once it's confirmed visible — so a
        // failed publish is retried on the next start instead of being skipped.
        let own_pubkey = keys.public_key();
        let visible = match rt.block_on(bridge.fetch_key_packages(&own_pubkey)) {
            Ok(kps) => kps.len(),
            Err(e) => { tracing::warn!(target: "TRANSPORT", "verify own KeyPackage: fetch failed: {}", e); 0 }
        };
        if visible > 0 {
            tracing::info!(target: "ACCOUNT", "verify: own KeyPackage VISIBLE on relays ({} found) — account publishable", visible);
            let conn = self.db.connection();
            let _ = KeyValueRepo::set(&conn, &kp_kv_key, &now.to_string());
        } else {
            tracing::warn!(target: "ACCOUNT", "verify: own KeyPackage NOT yet visible — will republish on next start");
            // Do NOT set the throttle key → next start_sync republishes.
        }

        Ok(())
    }

    /// Persist an AlphaMessage to the database.
    fn persist_alpha_message(&self, chat_id: &str, msg: &AlphaMessage, sender_id: &str) -> Result<(), MessengerError> {
        use alpha_database::models::{MessageType as DbMessageType, ExpiryPolicyType};

        // Our own message: the same rules every receiver applies (policy). A
        // forbidden one is refused HERE, before it is stored or sent — it would
        // only be dropped by everyone else, and the sender would never know.
        if sender_id == "self" && !is_local_chat(chat_id) {
            if let Some(intent) = alpha_sync::write_intent(msg) {
                let (own_sig, own_pk) = self.own_keys_in(chat_id);
                let now = chrono::Utc::now().timestamp();
                let conn = self.db.connection();
                let pk = (!own_pk.is_empty()).then_some(own_pk.as_str());
                if let Err(d) = alpha_database::policy::may_write(&conn, chat_id, pk, &own_sig, &intent, now, now) {
                    return Err(MessengerError::InvalidArgument {
                        description: format!("write_denied:{}:{}", d.code(), d.value()),
                    });
                }
                if matches!(intent, alpha_database::policy::Intent::Comment { .. }) {
                    alpha_database::policy::note_comment(&conn, chat_id, &own_sig, now);
                }
            }
        }

        // Reaction toggle: remove=true deletes the matching reaction row.
                if let AlphaMessage::Reaction { target_id, emoji, remove: Some(true), .. } = msg {
            let conn = self.db.connection();
            let _ = alpha_database::MessageRepo::delete_reaction(&conn, chat_id, sender_id, target_id, emoji);
            return Ok(());
        }

        // Extract expiry information from the message
        let expiry = msg.expiry();
        let (expiry_type, ttl_seconds, expires_at) = match &expiry {
            Some(AlphaExpiryPolicy::AfterSend { ttl_secs }) => {
                let created = chrono::Utc::now();
                let exp = (created + chrono::Duration::seconds(i64::from(*ttl_secs))).to_rfc3339();
                (Some(ExpiryPolicyType::AfterSend), Some(*ttl_secs as i64), Some(exp))
            }
            Some(AlphaExpiryPolicy::AfterRead { ttl_secs }) => {
                // expires_at is NULL until read
                (Some(ExpiryPolicyType::AfterRead), Some(*ttl_secs as i64), None)
            }
            Some(AlphaExpiryPolicy::ViewOnce) => {
                (Some(ExpiryPolicyType::ViewOnce), None, None)
            }
            Some(AlphaExpiryPolicy::Whisper { secs, .. }) => {
                (Some(ExpiryPolicyType::ViewOnce), Some(*secs as i64), None)
            }
            Some(AlphaExpiryPolicy::None) | None => (None, None, None),
        };

        let (msg_type, content, reply_to, media_ref) = match msg {
            AlphaMessage::Text { body, reply_to, expiry, .. } => {
                // D3: ViewOnce plaintext NEVER on disk; we keep no copy of our whisper.
                let safe_content = match expiry {
                    Some(AlphaExpiryPolicy::ViewOnce) | Some(AlphaExpiryPolicy::Whisper { .. }) => None,
                    _ => Some(body.clone()),
                };
                (DbMessageType::Text, safe_content, reply_to.clone(), None)
            }
            AlphaMessage::Edit { id, body, .. } => (
                DbMessageType::Edit,
                Some(body.clone()),
                Some(id.clone()),
                None,
            ),
            AlphaMessage::Reaction { target_id, emoji, .. } => (
                DbMessageType::Reaction,
                Some(emoji.clone()),
                Some(target_id.clone()),
                None,
            ),
            AlphaMessage::Reply { body, reply_to, quoted_text, .. } => (
                DbMessageType::Reply,
                Some(body.clone()),
                Some(reply_to.clone()),
                // Stash the inline quote snippet in media_ref (unused for
                // text replies) so the recipient renders the quote without
                // needing the original message.
                if quoted_text.is_empty() { None } else { Some(quoted_text.clone()) },
            ),
            AlphaMessage::Delete { target_id, .. } => (
                DbMessageType::Delete,
                None,
                Some(target_id.clone()),
                None,
            ),
            AlphaMessage::Disappearing { body, ttl_seconds: _, .. } => (
                DbMessageType::Disappearing,
                Some(body.clone()),
                None,
                None,
            ),
            AlphaMessage::Media { media_ref, caption, expiry, .. } => {
                // D3: ViewOnce media ref and content NEVER on disk
                let safe_caption = match expiry {
                    Some(AlphaExpiryPolicy::ViewOnce) | Some(AlphaExpiryPolicy::Whisper { .. }) => None,
                    _ => caption.clone(),
                };
                let safe_media_ref = match expiry {
                    Some(AlphaExpiryPolicy::ViewOnce) | Some(AlphaExpiryPolicy::Whisper { .. }) => None,
                    _ => Some(media_ref.clone()),
                };
                (DbMessageType::Media, safe_caption, None, safe_media_ref)
            }
            AlphaMessage::ReadSignal { .. } | AlphaMessage::DeliveredSignal { .. } => {
                // ReadSignal is not persisted
                return Ok(());
            }
            AlphaMessage::ChatState { .. } => {
                // Transient presence signal — never persisted.
                return Ok(());
            }
            AlphaMessage::ProfileUpdate { .. }
            | AlphaMessage::ProfileRequest { .. }
            | AlphaMessage::GroupMetadataUpdate { .. }
            | AlphaMessage::GroupRoleUpdate { .. }
            | AlphaMessage::GroupRoleSnapshot { .. }
            | AlphaMessage::GroupRoleSyncRequest { .. }
            // Transport directory — internal, applied to the address map,
            // never becomes a bubble.
            | AlphaMessage::GroupDirectory { .. }
            | AlphaMessage::SecretChat { .. }
            | AlphaMessage::GroupMuteUpdate { .. }
            | AlphaMessage::GroupBan { .. }
            | AlphaMessage::GroupPermissionsUpdate { .. }
            | AlphaMessage::GroupAuditEntry { .. }
            | AlphaMessage::GroupDisbanded { .. }
            | AlphaMessage::ChatDeleted { .. }
            | AlphaMessage::PinMessage { .. }
            | AlphaMessage::StoryPost { .. }
            | AlphaMessage::StoryDelete { .. }
            | AlphaMessage::StoryView { .. }
            | AlphaMessage::ChannelSeen { .. }
            | AlphaMessage::ChannelHistory { .. }
            | AlphaMessage::DeliveryAck { .. }
            | AlphaMessage::ResendRequest { .. }
            | AlphaMessage::PollVote { .. }
            | AlphaMessage::PollClose { .. }
            | AlphaMessage::WhisperBurned { .. } => {
                // Profile / group-metadata / role / mute / permissions / audit
                // control messages AND delivery-layer control messages are
                // handled out-of-band; not chat rows.
                return Ok(());
            }
        };

        // For legacy Disappearing messages, override ttl
        let final_ttl = match msg {
            AlphaMessage::Disappearing { ttl_seconds, .. } => Some(*ttl_seconds as i64),
            _ => ttl_seconds,
        };
        let final_expires = expires_at;

        let conn = self.db.connection();

        // Stable id across devices (see receiver-side note in alpha-sync).
        // EDIT carries the ORIGINAL message id (so the fold can match it via
        // reply_to), but it MUST get its own row id — otherwise INSERT collides
        // with the base row ("UNIQUE constraint failed: messages.id"). Derive a
        // composite id that is deterministic across devices (edited_at travels
        // inside the message) yet distinct from the base id.
        let stable_id = match msg {
            AlphaMessage::Edit { id, edited_at, .. } => format!("{}#edit#{}", id, edited_at),
            _ => msg.id().to_string(),
        };
        if let Some(ep_type) = &expiry_type {
            // Use ephemeral-aware helper
            let mut db_msg = alpha_database::new_ephemeral_message(
                chat_id.to_string(),
                sender_id.to_string(),
                msg_type,
                content,
                reply_to,
                media_ref,
                final_ttl,
                final_expires,
                ep_type.clone(),
            );
            if !stable_id.is_empty() { db_msg.id = stable_id.clone(); }
            MessageRepo::insert(&conn, &db_msg)
                .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
        } else {
            let mut db_msg = new_message(
                chat_id.to_string(),
                sender_id.to_string(),
                msg_type,
                content,
                reply_to,
                media_ref,
                final_ttl,
                final_expires,
            );
            if !stable_id.is_empty() { db_msg.id = stable_id.clone(); }
            MessageRepo::insert(&conn, &db_msg)
                .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
        }

        // Every path that writes a message of ours passes here: the armed
        // "without sound" lands on its row, and each publish reads it from there.
        if sender_id == "self" && !is_local_chat(chat_id) && msg.is_user_content() && take_armed_silent(chat_id) {
            let _ = MessageRepo::mark_silent(&conn, &stable_id);
        }

        // Delete-for-everyone: soft-delete the target so the chat-list
        // preview falls back to the previous real message (mirrors the
        // receiver-side logic in event_processor).
                if let AlphaMessage::Delete { target_id, .. } = msg {
            let _ = MessageRepo::mark_deleted(&conn, target_id);
        }
        // Edit: rewrite the original row's content so the chat-list preview
        // shows the edited text (the EDIT row alone wouldn't update it).
                if let AlphaMessage::Edit { id, body, edited_at, seq: 0, } = msg {
            let _ = MessageRepo::apply_edit(&conn, id, body, edited_at);
        }
        // A comment: filed under its post (out of the feed, into the discussion).
        alpha_sync::file_comment(&conn, chat_id, msg);
        drop(conn);

        // A note to self (text) goes to our other phones as well.
        if chat_id == SAVED_MESSAGES_CHAT_ID && sender_id == "self"
            && matches!(msg, AlphaMessage::Text { .. } | AlphaMessage::Reply { .. })
            && !devices::applying_note()
        {
            if let Ok(v) = serde_json::to_value(msg) {
                self.device_note(serde_json::json!({ "op": "saved", "msg": v }));
            }
        }
        Ok(())
    }

    /// Forget our presence secret: the next use makes a new one, so whoever
    /// holds the old one stops seeing our "last seen" until we send them the
    /// new one (the profile broadcast does, to those allowed).
    fn rotate_presence_secret(&self) {
        let _ = KeyValueRepo::delete(&self.db.connection(), KV_OWN_PRESENCE_SECRET);
    }

    /// `(text, colour)` of a peer's status from their profile; `user_id` is
    /// their pubkey hex (any of their member signature keys maps to it).
    fn peer_status(&self, user_id: &str) -> (String, u32) {
        let conn = self.db.connection();
        let sigs: Vec<String> = KeyValueRepo::list_prefix(&conn, "member_npub:").unwrap_or_default().into_iter()
            .filter(|(_, pk)| pk.eq_ignore_ascii_case(user_id))
            .filter_map(|(k, _)| k.strip_prefix("member_npub:").map(str::to_string))
            .collect();
        for sig in sigs {
            if let Some(v) = KeyValueRepo::get(&conn, &format!("peer_status:{sig}")).ok().flatten()
                .and_then(|j| serde_json::from_str::<serde_json::Value>(&j).ok())
            {
                let t = v.get("t").and_then(|t| t.as_str()).unwrap_or_default().to_string();
                let c = v.get("c").and_then(|c| c.as_u64()).unwrap_or(0) as u32;
                return (t, c);
            }
        }
        (String::new(), 0)
    }

    /// A chat still waiting in Requests (see `alpha_database::privacy`).
    fn is_request_chat(&self, chat_id: &str) -> bool {
        alpha_database::privacy::is_request(&self.db.connection(), chat_id)
    }

    /// The post a stored message comments on (from the comments table).
    fn thread_of_message(&self, message_id: &str) -> Option<String> {
        alpha_database::comments::post_of(&self.db.connection(), message_id)
    }

    // -----------------------------------------------------------------
    // Relay management (Settings → Relays)
    // -----------------------------------------------------------------

    /// Snapshot of every configured relay with current connection state.
    /// `ping_ms` is 0 here — call `ping_all_relays` for live RTT values.
    pub fn list_relay_statuses(&self) -> Result<Vec<RelayInfo>, MessengerError> {
        let rt = tokio::runtime::Runtime::new()
            .map_err(|e| MessengerError::InternalError { description: format!("runtime: {}", e) })?;
        let snaps = rt.block_on(self.transport.relay_statuses());
        Ok(snaps.into_iter().map(relay_snapshot_to_info).collect())
    }

    /// Measure TCP ping (ms) to every configured relay in parallel.
    /// Returns the same set as `list_relay_statuses` with `ping_ms` populated
    /// (0 = unreachable within `timeout_ms`).
    pub fn ping_all_relays(&self, timeout_ms: u32) -> Result<Vec<RelayInfo>, MessengerError> {
        let rt = tokio::runtime::Runtime::new()
            .map_err(|e| MessengerError::InternalError { description: format!("runtime: {}", e) })?;
        let timeout = std::time::Duration::from_millis(timeout_ms.max(500) as u64);
        let (snaps, pings) = rt.block_on(async {
            let snaps = self.transport.relay_statuses().await;
            let pings = self.transport.ping_relays(timeout).await;
            (snaps, pings)
        });
        let pings: std::collections::HashMap<String, Option<u64>> = pings.into_iter().collect();
        let out = snaps.into_iter().map(|s| {
            let mut info = relay_snapshot_to_info(s);
            if let Some(Some(ms)) = pings.get(&info.url) {
                info.ping_ms = (*ms).min(u64::from(u32::MAX)) as u32;
            }
            info
        }).collect();
        Ok(out)
    }

    /// Add a relay at runtime. URL must start with `ws://` or `wss://`.
    pub fn add_relay(&self, url: String, read: bool, write: bool) -> Result<(), MessengerError> {
        if !url.starts_with("ws://") && !url.starts_with("wss://") {
            return Err(MessengerError::InvalidArgument {
                description: "relay URL must start with ws:// or wss://".to_string(),
            });
        }
        // H3 (RoadmapSecurityAudit): reject cleartext `ws://` relays. The MLS
        // payload stays encrypted, but the gift-wrap envelope (signatures),
        // metadata and the user's IP are exposed to a MITM on a cleartext hop.
        // The default relay set is already WSS, so this only blocks a user (or a
        // malicious QR/import) from adding an insecure relay. Exception: `.onion`
        // hosts, where the cleartext WS is tunnelled inside Tor's own encryption.
        if url.starts_with("ws://") && !is_onion_authority(&url) {
            return Err(MessengerError::InvalidArgument {
                description: "cleartext ws:// relays are not allowed (use wss:// or a .onion host)"
                    .to_string(),
            });
        }
        let rt = runtime();
        rt.block_on(self.transport.add_relay_with_flags(&url, read, write, true))
            .map_err(|e| MessengerError::NetworkError { description: e.to_string() })?;
        self.persist_relays_to_db();
        tracing::info!("add_relay persisted: {}", url);
        self.relays_changed();
        Ok(())
    }

    /// The relay set grew: subscribe on it now (not after a restart) and put
    /// our KeyPackage there, so people who only know this relay can reach us.
    fn relays_changed(&self) {
        if let Err(e) = self.force_reconnect() { tracing::warn!("relays_changed: reconnect: {}", e); }
        if let Err(e) = self.publish_own_key_package() { tracing::warn!("relays_changed: KeyPackage: {}", e); }
        // Our own relays are left out of the routes; contacts hear of the change
        // from the app, once the user is done editing the list.
        alpha_database::peer_relays::mark_changed();
    }

    /// Remove a relay at runtime.
    pub fn remove_relay(&self, url: String) -> Result<(), MessengerError> {
        let rt = runtime();
        rt.block_on(self.transport.remove_relay(&url))
            .map_err(|e| MessengerError::NetworkError { description: e.to_string() })?;
        self.persist_relays_to_db();
        tracing::info!("remove_relay persisted: {}", url);
        Ok(())
    }

    /// Several relay changes at once (an assistant's plan): one save and one
    /// reconnect at the end — per step, each add or switch-on reconnected every
    /// relay. Returns how many steps failed.
    pub fn apply_relay_changes(
        &self,
        add: Vec<String>,
        enable: Vec<String>,
        disable: Vec<String>,
        remove: Vec<String>,
    ) -> Result<u32, MessengerError> {
        let rt = runtime();
        let mut failed = 0u32;
        let mut grew = false;
        for url in &add {
            // Same rule as add_relay: no cleartext ws:// except to an onion host.
            let allowed = url.starts_with("wss://") || (url.starts_with("ws://") && is_onion_authority(url));
            if allowed && rt.block_on(self.transport.add_relay_with_flags(url, true, true, true)).is_ok() {
                grew = true;
            } else {
                failed += 1;
            }
        }
        for url in &enable {
            match rt.block_on(self.transport.update_relay_flags(url, None, None, Some(true))) {
                Ok(()) => grew = true,
                Err(_) => failed += 1,
            }
        }
        for url in &disable {
            if rt.block_on(self.transport.update_relay_flags(url, None, None, Some(false))).is_err() { failed += 1; }
        }
        for url in &remove {
            if rt.block_on(self.transport.remove_relay(url)).is_err() { failed += 1; }
        }
        self.persist_relays_to_db();
        if grew { self.relays_changed(); }
        tracing::info!(
            "apply_relay_changes: +{} on {} off {} -{} failed {}",
            add.len(), enable.len(), disable.len(), remove.len(), failed
        );
        Ok(failed)
    }

    /// Toggle whether a relay should be used. Disabled relays stay in the
    /// list but are skipped for publish/subscribe.
    pub fn set_relay_enabled(&self, url: String, enabled: bool) -> Result<(), MessengerError> {
        let rt = runtime();
        rt.block_on(self.transport.update_relay_flags(&url, None, None, Some(enabled)))
            .map_err(|e| MessengerError::NetworkError { description: e.to_string() })?;
        self.persist_relays_to_db();
        tracing::info!("set_relay_enabled persisted: {} -> {}", url, enabled);
        if enabled { self.relays_changed(); }
        Ok(())
    }

    /// Update the read / write direction flags for a relay.
    pub fn set_relay_flags(&self, url: String, read: bool, write: bool) -> Result<(), MessengerError> {
        let rt = runtime();
        rt.block_on(self.transport.update_relay_flags(&url, Some(read), Some(write), None))
            .map_err(|e| MessengerError::NetworkError { description: e.to_string() })?;
        self.persist_relays_to_db();
        Ok(())
    }

    /// Snapshot the current in-memory relay set + flags and write to KV
    /// under `KV_RELAYS`. Called from every mutating relay method so the
    /// user's choices survive a process restart.
    fn persist_relays_to_db(&self) {
        let rt = runtime();
        let snapshot: Vec<(String, bool, bool, bool)> = rt.block_on(self.transport.relay_config_snapshot());
        let list: Vec<PersistedRelay> = snapshot.into_iter()
            .map(|(url, read, write, enabled)| PersistedRelay { url, read, write, enabled })
            .collect();
        match serde_json::to_string(&list) {
            Ok(json) => {
                let conn = self.db.connection();
                if let Err(e) = KeyValueRepo::set(&conn, KV_RELAYS, &json) {
                    tracing::warn!("persist_relays_to_db: KV write failed: {}", e);
                } else {
                    tracing::debug!("Persisted {} relay(s) to DB", list.len());
                }
            }
            Err(e) => tracing::warn!("persist_relays_to_db: serialize failed: {}", e),
        }
    }
}

/// Map a transport-layer relay snapshot to the FFI-friendly form.
fn relay_snapshot_to_info(s: alpha_transport::RelayStatusSnapshot) -> RelayInfo {
    let is_tor = s.url.contains(".onion");
    RelayInfo {
        url: s.url,
        read: s.read,
        write: s.write,
        enabled: s.enabled,
        connected: s.connected,
        ping_ms: 0,
        is_tor,
        state: s.state.to_string(),
        retry_secs: s.retry_secs.min(u64::from(u32::MAX)) as u32,
        inbox_auth: s.inbox_auth,
        accepted: s.accepted,
        rejected: s.rejected,
        last_error: s.last_error,
    }
}

// --- Backup dump helpers ---

/// Render a single SQLite value at `row[col]` as a SQL literal, properly
/// escaped. NULL → "NULL", text → single-quoted with embedded quotes doubled,
/// BLOB → X'..' hex, integer/real → as-is.
fn sql_value(row: &rusqlite::Row<'_>, col: usize) -> rusqlite::Result<String> {
    use rusqlite::types::ValueRef;
    let val: ValueRef = row.get_ref(col)?;
    Ok(match val {
        ValueRef::Null => "NULL".to_string(),
        ValueRef::Integer(i) => i.to_string(),
        ValueRef::Real(f) => f.to_string(),
        ValueRef::Text(bytes) => {
            let s = std::str::from_utf8(bytes).unwrap_or("");
            format!("'{}'", s.replace('\'', "''"))
        }
        ValueRef::Blob(b) => {
            let hex = b.iter().map(|byte| format!("{:02x}", byte)).collect::<String>();
            format!("X'{}'", hex)
        }
    })
}

/// Produce a short human-readable summary of a backup dump for the UI
/// (e.g. "chats: 3, messages: 142, contacts: 7"). Pure string scan — never
/// executes SQL, so it's safe to run on untrusted input.
fn summarize_dump(sql: &str) -> String {
    // Collect the distinct table names we see INSERT statements for. We store
    // owned Strings (not borrows) because each line is processed and dropped.
    let mut names: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();
    for line in sql.lines() {
        let lower = line.trim_start().to_ascii_lowercase();
        if let Some(rest) = lower.strip_prefix("insert or ignore into \"") {
            if let Some(end) = rest.find('"') {
                names.insert(rest[..end].to_string());
            }
        }
    }
    if names.is_empty() {
        return "Пустой бэкап (нет данных)".to_string();
    }
    // The UI just needs to confirm the backup is non-empty and well-formed —
    // a table presence list is sufficient (precise row counts would require a
    // full SQL parse, not worth it here).
    let list: Vec<String> = names.into_iter().collect();
    format!("Бэкап содержит таблицы: {}", list.join(", "))
}

/// Pull-based role sync: publish a full `GroupRoleSnapshot` reply, but ONLY
/// if WE are the owner of `chat_id`. Free function so it can run both from
/// `&self` methods and inside the realtime/catch-up listener spawns (which hold
/// cloned Arcs, not `self`). Best-effort; silent on any failure.
/// Re-broadcast the CURRENT group metadata (name/description/avatar) at its
/// current version — owner-only, used to answer a role-sync request so a freshly
/// restored member pulls the group avatar/description. Free fn (runs from the
/// listener spawns). No-op if we're not the owner.
fn broadcast_current_metadata_raw(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    identity: &Arc<TrackedMutex<Option<DualIdentity>>>,
    transport: NostrRelayClient,
    chat_id: &str,
) {
    use base64::Engine;
    let Some((own_hex, keys)) = who_in_raw(identity, chat_id) else { return };
    // Only the owner re-broadcasts metadata.
    {
        let conn = db.connection();
        if alpha_database::group_roles::owner_of(&conn, chat_id).as_deref() != Some(own_hex.as_str()) {
            return;
        }
    }
    let (version, name, desc, avatar_path, avatar_hash) = {
        let conn = db.connection();
        match alpha_database::ChatRepo::get(&conn, chat_id).ok().flatten() {
            Some(c) => (c.metadata_version, c.name.unwrap_or_default(), c.description,
                        c.avatar_path, c.avatar_hash),
            None => return,
        }
    };
    if version <= 0 && avatar_path.is_empty() && desc.is_empty() { return; }
    let avatar_b64 = if avatar_path.is_empty() { String::new() } else {
        match std::fs::read(&avatar_path) {
            Ok(b) => base64::engine::general_purpose::STANDARD.encode(&b),
            Err(_) => String::new(),
        }
    };
    let (look, notices) = { let conn = db.connection(); (chat_look_raw(&conn, chat_id), notices_raw(&conn, chat_id)) };
    let msg = AlphaMessage::GroupMetadataUpdate {
        version: version.max(1) as u64,
        name, description: desc, avatar_jpeg_base64: avatar_b64, avatar_hash, look, notices,
        ts: chrono::Utc::now().to_rfc3339(),
    };
    let payload = match alpha_messages::serialize(&msg) { Ok(p) => p, Err(_) => return };
    let group_id = match hex::decode(chat_id) { Ok(g) => g, Err(_) => return };
    let (ciphertext, epoch) = {
        let mut mls = match mls_session.lock() { Ok(m) => m, Err(_) => return };
        if mls.group_info(&group_id).is_none() { return; }
        let ct = match mls.send(&group_id, &payload) { Ok(c) => c, Err(_) => return };
        let ep = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
        (ct, ep)
    };
    if let Ok(g) = mls_session.lock() {
        let snap = g.snapshot_storage();
        if !snap.is_empty() {
            let conn = db.connection();
            let _ = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snap);
        }
    }
    let envelope = MlsEnvelope::new(MlsMessageType::Application, group_id, epoch, ciphertext);
    let bridge = NostrMlsBridge::new(transport);
    runtime().spawn(async move {
        let _ = bridge.send_mls_message(&keys, &envelope, None, None).await;
    });
}

fn publish_role_snapshot_raw(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    identity: &Arc<TrackedMutex<Option<DualIdentity>>>,
    transport: NostrRelayClient,
    chat_id: &str,
    // Ownership transfer ONLY: the SQLite owner row is already the NEW owner by
    // the time we ship the bootstrap snapshot, so the owner==self guard would
    // (wrongly) suppress it and the new owner would never receive role state.
    // When true, skip that guard — we are the outgoing owner sending the final
    // authoritative snapshot (which already names the new owner).
    bypass_owner_guard: bool,
) {
    // Own pubkey (and Nostr keys) — read first, lock released before mls lock.
    let Some((own_hex, keys)) = who_in_raw(identity, chat_id) else { return };
    if own_hex.is_empty() { return; }

    // Only the OWNER answers a sync request — UNLESS this is the ownership-transfer
    // bootstrap snapshot (we just handed ownership over and must ship the final
    // role state to the new owner before the guard would block us).
    let owner = {
        let conn = db.connection();
        alpha_database::group_roles::owner_of(&conn, chat_id).unwrap_or_default()
    };
    if !bypass_owner_guard && (owner.is_empty() || owner != own_hex) { return; }

    // Build the snapshot from the role KV + bump the version.
    let (roles_json, version) = {
        let conn = db.connection();
        let prefix = format!("group_role:{}:", chat_id);
        let mut map = std::collections::HashMap::new();
        if let Ok(entries) = alpha_database::KeyValueRepo::list(&conn) {
            for e in entries {
                if let Some(pk) = e.key.strip_prefix(&prefix) {
                    map.insert(pk.to_string(), e.value);
                }
            }
        }
        let v = alpha_database::group_roles::roles_version(&conn, chat_id) + 1;
        alpha_database::group_roles::set_roles_version(&conn, chat_id, v);
        (serde_json::to_string(&map).unwrap_or_else(|_| "{}".to_string()), v)
    };

    let msg = AlphaMessage::GroupRoleSnapshot {
        version,
        owner_pubkey: owner,
        roles_json,
        ts: chrono::Utc::now().to_rfc3339(),
    };
    let payload = match alpha_messages::serialize(&msg) { Ok(p) => p, Err(_) => return };
    let group_id = match hex::decode(chat_id) { Ok(g) => g, Err(_) => return };

    let (ciphertext, epoch) = {
        let mut mls = match mls_session.lock() { Ok(m) => m, Err(_) => return };
        if mls.group_info(&group_id).is_none() { return; }
        let ct = match mls.send(&group_id, &payload) { Ok(c) => c, Err(_) => return };
        let ep = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
        (ct, ep)
    };

    // Persist the advanced ratchet so the reply survives a restart.
    if let Ok(g) = mls_session.lock() {
        let snap = g.snapshot_storage();
        if !snap.is_empty() {
            let conn = db.connection();
            let _ = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snap);
        }
    }

    let envelope = MlsEnvelope::new(MlsMessageType::Application, group_id, epoch, ciphertext);
    let bridge = NostrMlsBridge::new(transport);
    runtime().spawn(async move {
        match bridge.send_mls_message(&keys, &envelope, None, None).await {
            Ok(eid) => tracing::info!("role snapshot reply sent: event_id={}", eid),
            Err(e) => tracing::warn!("role snapshot reply failed: {}", e),
        }
    });
}

/// The look stored for a chat, raw: "" = never set, "{}" = cleared.
fn chat_look_raw(conn: &rusqlite::Connection, chat_id: &str) -> String {
    KeyValueRepo::get(conn, &format!("chat_look:{}", chat_id)).ok().flatten().unwrap_or_default()
}

/// We were banned from `chat_id`: keep only the reason, drop everything else —
/// messages, comments and our MLS state, so nothing new can be read either.
fn apply_ban_raw(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    chat_id: &str,
    reason: &str,
) {
    let record = serde_json::json!({ "reason": reason, "at": chrono::Utc::now().timestamp() }).to_string();
    let snapshot = hex::decode(chat_id).ok().and_then(|gid| {
        let mut m = mls_session.lock().ok()?;
        let _ = m.delete_group(&gid);
        Some(m.snapshot_storage())
    });
    let conn = db.connection();
    let _ = KeyValueRepo::set(&conn, &format!("banned:{}", chat_id), &record);
    let _ = KeyValueRepo::set(&conn, &format!("removed_group:{}", chat_id), "1");
    let _ = alpha_database::MessageRepo::delete_for_chat(&conn, chat_id);
    let _ = alpha_database::MlsGroupRepo::update(&conn, chat_id, None, Some(false));
    if let Some(snap) = snapshot.filter(|s| !s.is_empty()) {
        let _ = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snap);
    }
    tracing::warn!("banned from {}: local content wiped", chat_id);
}

/// The owner deleted `chat_id`: drop everything, as for a ban, and keep only
/// that it was deleted (shown in place of the chat; no names travel).
/// A 1:1 gone for both sides: its group, history, row and the peer's link to
/// it. Nothing is left that a later message could bring back; adding the
/// person again starts a new chat.
fn wipe_direct_raw(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    chat_id: &str,
) {
    let snapshot = hex::decode(chat_id).ok().and_then(|gid| {
        let mut m = mls_session.lock().ok()?;
        let _ = m.delete_group(&gid);
        Some(m.snapshot_storage())
    });
    let conn = db.connection();
    let _ = alpha_database::MessageRepo::delete_for_chat(&conn, chat_id);
    let _ = alpha_database::MlsGroupRepo::update(&conn, chat_id, None, Some(false));
    for (k, v) in KeyValueRepo::list_prefix(&conn, "direct_chat_for:").unwrap_or_default() {
        if v == chat_id { let _ = KeyValueRepo::delete(&conn, &k); }
    }
    let _ = ChatRepo::delete(&conn, chat_id);
    if let Some(snap) = snapshot.filter(|s| !s.is_empty()) {
        let _ = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snap);
    }
    tracing::warn!(target: "ACCOUNT", "1:1 {} deleted for both sides", chat_id);
}

fn apply_disband_raw(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    chat_id: &str,
) {
    let snapshot = hex::decode(chat_id).ok().and_then(|gid| {
        let mut m = mls_session.lock().ok()?;
        let _ = m.delete_group(&gid);
        Some(m.snapshot_storage())
    });
    let conn = db.connection();
    let record = serde_json::json!({ "reason": "", "at": chrono::Utc::now().timestamp(), "disbanded": true }).to_string();
    let _ = KeyValueRepo::set(&conn, &format!("banned:{}", chat_id), &record);
    let _ = KeyValueRepo::set(&conn, &format!("removed_group:{}", chat_id), "1");
    let _ = alpha_database::MessageRepo::delete_for_chat(&conn, chat_id);
    let _ = alpha_database::MlsGroupRepo::update(&conn, chat_id, None, Some(false));
    if let Some(snap) = snapshot.filter(|s| !s.is_empty()) {
        let _ = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snap);
    }
    tracing::warn!("{} deleted by its owner: local content wiped", chat_id);
}

/// Our member id in a chat (a persona's leaf in a chat we own hidden).
fn own_sig_raw(identity: &Arc<TrackedMutex<Option<DualIdentity>>>, chat_id: &str) -> String {
    if let Some(p) = crate::personas::get(chat_id) {
        return p.sig_hex;
    }
    identity.lock().ok()
        .and_then(|g| g.as_ref().map(|id| hex::encode(id.mls().signer().public())))
        .unwrap_or_default()
}

/// The group's join/leave lines setting as shipped: "on" | "off" | "" (never set).
fn notices_raw(conn: &rusqlite::Connection, chat_id: &str) -> String {
    KeyValueRepo::get(conn, &format!("group_notices:{}", chat_id)).ok().flatten().unwrap_or_default()
}

/// Member Profile Sync: reply to a `ProfileRequest` by shipping OUR current
/// profile (name + bio + avatar) into ONE chat. Free function so it can run
/// from the realtime/catch-up listener spawns (no `self`). Best-effort.
/// Our profile as `chat_id` may see it: a contact (our accepted direct chat)
/// gets what the privacy settings show contacts, everyone else what they show
/// everyone. Name and the wake topic always go (a member list needs names;
/// the topic only makes delivery prompt).
fn shape_profile(conn: &rusqlite::Connection, msg: &AlphaMessage, chat_id: &str) -> AlphaMessage {
    use alpha_database::privacy::{calls_open_to, shows, ABOUT, LAST_SEEN, PHOTO, STATUS};
    let mut m = msg.clone();
    let to_contact = alpha_database::privacy::is_contact_chat(conn, chat_id);
    if let AlphaMessage::ProfileUpdate {
        bio, avatar_jpeg_base64, avatar_hash, cover_jpeg_base64, cover_hash, avatar_ref, cover_ref,
        avatar_version, cover_version, presence_secret, scope, status, status_color, can_call, can_video,
        video_ref, video_version, ..
    } = &mut m {
        if !shows(conn, PHOTO, to_contact) {
            avatar_jpeg_base64.clear(); avatar_hash.clear(); avatar_ref.clear(); *avatar_version = 0;
            cover_jpeg_base64.clear(); cover_hash.clear(); cover_ref.clear(); *cover_version = 0;
            video_ref.clear(); *video_version = 0;
        }
        if !shows(conn, ABOUT, to_contact) { bio.clear(); }
        if !shows(conn, LAST_SEEN, to_contact) { presence_secret.clear(); }
        // Our status text goes in the profile (its own audience), not in presence.
        let text = KeyValueRepo::get(conn, KV_MY_STATUS_TEXT).ok().flatten().unwrap_or_default();
        let color = KeyValueRepo::get(conn, KV_MY_STATUS_COLOR).ok().flatten().and_then(|c| c.parse::<u32>().ok()).unwrap_or(0);
        if shows(conn, STATUS, to_contact) && !text.trim().is_empty() {
            *status = Some(text);
            *status_color = (color != 0).then_some(color);
        } else {
            *status = None;
            *status_color = None;
        }
        *can_call = Some(calls_open_to(conn, false, to_contact));
        *can_video = Some(calls_open_to(conn, true, to_contact));
        *scope = Some(if to_contact { "contact" } else { "member" }.to_string());
    }
    if let AlphaMessage::ProfileUpdate { version, display_name, avatar_ref, bio, scope, status, can_call, can_video, presence_secret, .. } = &m {
        tracing::info!(
            target: "ACCOUNT",
            "profile copy for chat {}: v{} {:?} name={} avatar={} about={} status={} call={:?} video={:?} presence={}",
            &chat_id[..chat_id.len().min(12)], version, scope,
            !display_name.is_empty(), !avatar_ref.is_empty(), !bio.is_empty(), status.is_some(), can_call, can_video,
            !presence_secret.is_empty(),
        );
    }
    m
}

/// The wire message of one of our stored stories.
fn story_post_of(id: &str, v: &serde_json::Value, now: i64) -> AlphaMessage {
    let g = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
    AlphaMessage::StoryPost {
        story_id: id.to_string(), kind: g("kind"), media_ref: g("media_ref"), text: g("text"), bg: g("bg"),
        caption: g("caption"),
        created_at: v.get("created_at").and_then(|x| x.as_i64()).unwrap_or(now),
        expires_at: v.get("expires_at").and_then(|x| x.as_i64()).unwrap_or(now),
        allow_replies: v.get("allow_replies").and_then(|x| x.as_bool()).unwrap_or(true),
        allow_screenshots: v.get("allow_screenshots").and_then(|x| x.as_bool()).unwrap_or(false),
        stickers: g("stickers"),
        ts: chrono::Utc::now().to_rfc3339(),
    }
}

/// A NIP-19 npub or a 64-char hex key → the hex key (None when neither).
fn parse_pubkey_hex(npub_or_hex: &str) -> Option<String> {
    use nostr::nips::nip19::FromBech32;
    let t = npub_or_hex.trim();
    let pk = if t.starts_with("npub1") { nostr::PublicKey::from_bech32(t).ok()? } else { nostr::PublicKey::from_hex(t).ok()? };
    Some(pk.to_hex())
}

/// Who we are in a chat, for the free functions: (pubkey hex, Nostr keys) of
/// our persona in an anonymous channel, else of our identity.
fn who_in_raw(identity: &Arc<TrackedMutex<Option<DualIdentity>>>, chat_id: &str) -> Option<(String, nostr::Keys)> {
    if let Some(p) = crate::personas::get(chat_id) {
        return Some((p.keys.public_key().to_hex(), p.keys));
    }
    let guard = identity.lock().ok()?;
    let id = guard.as_ref()?;
    Some((id.nostr().public_key_hex(), id.nostr().keypair().keys().clone()))
}

/// We own this chat hidden right now: a persona speaks for us and the owner
/// has not chosen to show who they are. Nothing that names us goes there.
fn hidden_here(conn: &rusqlite::Connection, chat_id: &str) -> bool {
    crate::personas::get(chat_id).is_some()
        && serde_json::from_str::<serde_json::Value>(&alpha_database::group_roles::channel_cfg_raw(conn, chat_id))
            .ok().and_then(|v| v.get("anon").and_then(|x| x.as_bool())).unwrap_or(true)
}

fn reply_profile_into_chat_raw(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    identity: &Arc<TrackedMutex<Option<DualIdentity>>>,
    transport: NostrRelayClient,
    chat_id: &str,
) {
    use base64::Engine;
    use sha2::Digest;

    // A request we have not accepted gets no profile (accepting sends it).
    if alpha_database::privacy::is_request(&db.connection(), chat_id) { return; }
    // Owned hidden: our profile never goes there.
    if hidden_here(&db.connection(), chat_id) { return; }
    let group_id = match hex::decode(chat_id) { Ok(g) => g, Err(_) => return };

    // Our profile from KV + bumped version. Ships stored blob refs (upload
    // already happened in set_my_profile/set_my_cover) — no bytes, no upload here.
    let (name, bio, version, a_ref, a_hash, a_ver, c_ref, c_hash, c_ver, v_ref, v_ver) = {
        let conn = db.connection();
        let g = |k: &str| alpha_database::KeyValueRepo::get(&conn, k).ok().flatten().unwrap_or_default();
        let gv = |k: &str| g(k).parse::<u64>().unwrap_or(0);
        let cur: i64 = g(PROFILE_KV_VERSION).parse().unwrap_or(0);
        let next = cur + 1;
        let _ = alpha_database::KeyValueRepo::set(&conn, PROFILE_KV_VERSION, &next.to_string());
        (g(PROFILE_KV_NAME), g(PROFILE_KV_BIO), next as u64,
         g(PROFILE_KV_AVATAR_REF), g(PROFILE_KV_AVATAR_MHASH), gv(PROFILE_KV_AVATAR_VER),
         g(PROFILE_KV_COVER_REF), g(PROFILE_KV_COVER_MHASH), gv(PROFILE_KV_COVER_VER),
         g(PROFILE_KV_VIDEO_REF), gv(PROFILE_KV_VIDEO_VER))
    };

    let (presence_secret, wake_topic, listen_relays) = {
        let conn = db.connection();
        let (p, w) = own_contact_secrets(&conn);
        (p, w, relay_routes::own_listen_relays(&conn))
    };
    let msg = AlphaMessage::ProfileUpdate {
        version,
        display_name: name,
        bio,
        avatar_jpeg_base64: String::new(),
        avatar_hash: a_hash,
        cover_jpeg_base64: String::new(),
        cover_hash: c_hash,
        avatar_ref: a_ref,
        cover_ref: c_ref,
        avatar_version: a_ver,
        cover_version: c_ver,
        presence_secret,
        wake_topic,
        ts: chrono::Utc::now().to_rfc3339(),
        scope: None,
        status: None,
        status_color: None,
        can_call: None,
        can_video: None,
        video_ref: v_ref,
        video_version: v_ver,
        relays: listen_relays,
    };
    let msg = shape_profile(&db.connection(), &msg, chat_id);
    let payload = match alpha_messages::serialize(&msg) { Ok(p) => p, Err(_) => return };

    let keys = {
        let guard = match identity.lock() { Ok(g) => g, Err(_) => return };
        match guard.as_ref() { Some(id) => id.nostr().keypair().keys().clone(), None => return }
    };
    let (ciphertext, epoch) = {
        let mut mls = match mls_session.lock() { Ok(m) => m, Err(_) => return };
        if mls.group_info(&group_id).is_none() { return; }
        let ct = match mls.send(&group_id, &payload) { Ok(c) => c, Err(_) => return };
        let ep = mls.group_info(&group_id).map(|i| i.epoch).unwrap_or(0);
        (ct, ep)
    };
    if let Ok(g) = mls_session.lock() {
        let snap = g.snapshot_storage();
        if !snap.is_empty() {
            let conn = db.connection();
            let _ = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snap);
        }
    }
    let envelope = MlsEnvelope::new(MlsMessageType::Application, group_id, epoch, ciphertext);
    let bridge = NostrMlsBridge::new(transport);
    runtime().spawn(async move {
        match bridge.send_mls_message(&keys, &envelope, None, None).await {
            Ok(eid) => tracing::info!(target: "ACCOUNT", "profile reply sent: event_id={}", eid),
            Err(e) => tracing::warn!("profile reply failed: {}", e),
        }
    });
}

// --- Delivery receipts: "delivered" (two grey checks) ---
//
// A recipient confirms each batch of messages it received, decrypted and
// stored: one DeliveredSignal per chat, listing the message ids — right after
// the batch, no timer, no resends (a lost receipt only leaves one check). The
// sender marks a message delivered once every other member has confirmed it.

/// A join request between an account and a group's owner about the account's
/// own phones (see core/devices.rs), not one made with an invite link.
fn devices_token(token: &str) -> bool {
    token.starts_with("device:") || token.starts_with("unlink:")
}

/// The received messages worth confirming (text, replies, media), by chat.
fn received_by_chat(events: &[ProcessedEvent]) -> std::collections::HashMap<String, Vec<String>> {
    let mut out: std::collections::HashMap<String, Vec<String>> = std::collections::HashMap::new();
    for ev in events {
        // What our own other phone wrote is no delivery to confirm.
        if let ProcessedEvent::NewMessage { chat_id, message, own_device: false } = ev {
            let countable = matches!(message,
                AlphaMessage::Text { .. } | AlphaMessage::Reply { .. } | AlphaMessage::Media { .. }
                    | AlphaMessage::Disappearing { .. });
            let id = message.id();
            if countable && !id.is_empty() {
                out.entry(chat_id.clone()).or_default().push(id.to_string());
            }
        }
    }
    out
}

/// Send one DeliveredSignal per chat. Not for message requests (their sender
/// learns nothing) nor channels (posts count views, not receipts).
fn send_delivered(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    identity: &Arc<TrackedMutex<Option<alpha_identity::DualIdentity>>>,
    transport: &NostrRelayClient,
    by_chat: std::collections::HashMap<String, Vec<String>>,
) {
    for (chat_id, mut ids) in by_chat {
        {
            let conn = db.connection();
            if alpha_database::privacy::is_request(&conn, &chat_id)
                || alpha_database::group_roles::is_channel(&conn, &chat_id) {
                continue;
            }
        }
        ids.sort();
        ids.dedup();
        ids.truncate(500);
        let Ok(gid) = hex::decode(&chat_id) else { continue };
        let signal = AlphaMessage::DeliveredSignal { chat_id: chat_id.clone(), message_ids: ids.clone() };
        let Ok(payload) = alpha_messages::serialize(&signal) else { continue };
        // MLS first (send + snapshot of the advanced ratchet), then the database.
        let sent = mls_session.lock().ok().and_then(|mut m| {
            let ct = m.send(&gid, &payload).ok()?;
            let epoch = m.group_info(&gid).map(|i| i.epoch).unwrap_or(0);
            Some((ct, epoch, m.snapshot_storage()))
        });
        let Some((ct, epoch, snapshot)) = sent else { continue };
        if !snapshot.is_empty() {
            let conn = db.connection();
            let _ = alpha_database::MlsStorageRepo::save_snapshot(&conn, &snapshot);
        }
        let keys = identity.lock().ok().and_then(|g| g.as_ref().map(|id| id.nostr().keypair().keys().clone()));
        let Some(keys) = keys else { continue };
        let env = MlsEnvelope::new(MlsMessageType::Application, gid, epoch, ct);
        let bridge = NostrMlsBridge::new(transport.clone());
        tracing::info!(target: "DELIVERY", "DELIVERED_SENT chat={} n={}", chat_id, ids.len());
        runtime().spawn(async move {
            let _ = bridge.send_mls_message(&keys, &env, None, None).await;
        });
    }
}

/// A member received some of our messages: record it, and mark a message
/// delivered once every other member has it. Never lowers "read".
fn apply_delivered(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    callback: &CallbackDispatcher,
    chat_id: &str,
    member: &str,
    message_ids: &[String],
) {
    // Who else must have it (MLS first, before any database lock): people,
    // not phones — a person's phones count once, ours not at all.
    let others: Vec<String> = hex::decode(chat_id).ok().and_then(|gid| {
        let m = mls_session.lock().ok()?;
        let own = hex::encode(m.signer_public_for(&gid));
        m.group_info(&gid).map(|i| i.members.iter()
            .map(|x| hex::encode(&x.signature_key))
            .filter(|s| *s != own)
            .collect())
    }).unwrap_or_default();
    let now = chrono::Utc::now().to_rfc3339();
    let mut delivered = Vec::new();
    {
        let conn = db.connection();
        let me = alpha_database::devices::own_in_chat(chat_id);
        let person = |sig: &str| alpha_database::policy::sender_pubkey(&conn, chat_id, sig).unwrap_or_else(|| sig.to_string());
        let people: std::collections::HashSet<String> = others.iter().map(|s| person(s)).filter(|p| *p != me).collect();
        let needed = people.len().max(1) as u32;
        let member = person(member);
        if member == me { return; }
        let member = member.as_str();
        for id in message_ids {
            let Ok(Some(m)) = MessageRepo::get(&conn, id) else { continue };
            if m.sender_id != "self" || m.chat_id != chat_id || m.read_at.is_some() || m.delivered_at.is_some() { continue; }
            let have = alpha_database::receipts::record(&conn, id, member, &now).unwrap_or(0);
            if have >= needed && MessageRepo::mark_delivered(&conn, id).unwrap_or(false) {
                delivered.push(id.clone());
            }
        }
    }
    for id in delivered {
        tracing::info!(target: "DELIVERY", "DELIVERED chat={} msg_id={}", chat_id, id);
        callback.on_delivery_update(&id, "delivered");
    }
}

// --- E2E delivery: free-function tick for the timer task ---
//
// The delivery timer runs in a spawned tokio task that captures Arc clones
// (db, mls_session, identity, transport, pending_resends, callback) — it
// can't hold `&self`. This free function is the tick body, mirroring
// `AlphaMessenger::delivery_timer_tick` but operating on the captured Arcs.
// Kept in sync with the method; both are thin wrappers over the same
// DeliveryRepo + queue_and_send primitives.

#[allow(clippy::too_many_arguments)]
fn delivery_tick_free(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    identity: &Arc<TrackedMutex<Option<alpha_identity::DualIdentity>>>,
    transport: &Arc<NostrRelayClient>,
    pending_resends: &Arc<std::sync::Mutex<Vec<(String, u64, u64)>>>,
    _callback: &CallbackDispatcher,
    own_sig: &str,
) {
    if own_sig.is_empty() { return; }

    // (1) Send batched ACKs for unacked contiguous seqs.
    let chats: Vec<Vec<u8>> = match mls_session.lock() {
        Ok(m) => m.active_groups(),
        Err(_) => return,
    };
    for gid in &chats {
        let chat_id = hex::encode(gid);
        let senders_to_ack: Vec<(String, u64)> = {
            let conn = db.connection();
            let mut stmt = match conn.prepare(
                "SELECT sender_id, highest_received_seq FROM delivery_watermarks
                 WHERE chat_id = ?1 AND highest_received_seq > highest_acked_seq",
            ) { Ok(s) => s, Err(_) => continue };
            let rows = stmt.query_map(rusqlite::params![chat_id], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)? as u64))
            });
            match rows {
                Ok(rs) => rs.filter_map(|r| r.ok()).collect(),
                Err(_) => continue,
            }
        };
        for (sender_id, upto_seq) in senders_to_ack {
            let ack = AlphaMessage::DeliveryAck {
                chat_id: chat_id.clone(),
                sender_id: sender_id.clone(),
                upto_seq,
            };
            if let Ok(payload) = alpha_messages::serialize(&ack) {
                let msg_id = format!("ack-{}-{}", chat_id, upto_seq);
                // Send via a lightweight inline publish (control msg, no outbox).
                let gid2 = gid.clone();
                let keys = identity.lock().ok()
                    .and_then(|g| g.as_ref().map(|id| id.nostr().keypair().keys().clone()));
                if let Some(keys) = keys {
                    let enc = mls_session.lock().ok().and_then(|mut m| {
                        m.send(&gid2, &payload).ok().map(|ct| {
                            let ep = m.group_info(&gid2).map(|i| i.epoch).unwrap_or(0);
                            (ct, ep)
                        })
                    });
                    if let Some((ct, epoch)) = enc {
                        let env = MlsEnvelope::new(MlsMessageType::Application, gid2.clone(), epoch, ct);
                        let bridge = NostrMlsBridge::new((**transport).clone());
                        runtime().spawn(async move {
                            let _ = bridge.send_mls_message(&keys, &env, None, None).await;
                        });
                        let conn = db.connection();
                        let _ = alpha_database::delivery::DeliveryRepo::advance_acked(
                            &conn, &chat_id, &sender_id, upto_seq,
                        );
                    }
                }
            }
        }
    }

    // (2) Send ResendRequests for open gaps.
    for gid in &chats {
        let chat_id = hex::encode(gid);
        let gaps: Vec<(String, u64, u64)> = {
            let conn = db.connection();
            let mut stmt = match conn.prepare(
                "SELECT sender_id, from_seq, to_seq FROM delivery_gaps WHERE chat_id = ?1",
            ) { Ok(s) => s, Err(_) => continue };
            let rows = stmt.query_map(rusqlite::params![chat_id], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)? as u64, r.get::<_, i64>(2)? as u64))
            });
            match rows {
                Ok(rs) => rs.filter_map(|r| r.ok()).collect(),
                Err(_) => continue,
            }
        };
        for (sender_id, from_seq, to_seq) in gaps {
            let req = AlphaMessage::ResendRequest {
                chat_id: chat_id.clone(), sender_id: sender_id.clone(), from_seq, to_seq,
            };
            if let Ok(payload) = alpha_messages::serialize(&req) {
                let gid2 = gid.clone();
                let keys = identity.lock().ok()
                    .and_then(|g| g.as_ref().map(|id| id.nostr().keypair().keys().clone()));
                if let Some(keys) = keys {
                    let enc = mls_session.lock().ok().and_then(|mut m| {
                        m.send(&gid2, &payload).ok().map(|ct| {
                            let ep = m.group_info(&gid2).map(|i| i.epoch).unwrap_or(0);
                            (ct, ep)
                        })
                    });
                    if let Some((ct, epoch)) = enc {
                        let env = MlsEnvelope::new(MlsMessageType::Application, gid2.clone(), epoch, ct);
                        let bridge = NostrMlsBridge::new((**transport).clone());
                        runtime().spawn(async move {
                            let _ = bridge.send_mls_message(&keys, &env, None, None).await;
                        });
                    }
                }
            }
            let conn = db.connection();
            let _ = alpha_database::delivery::DeliveryRepo::gap_mark_requested(
                &conn, &chat_id, &sender_id, from_seq,
            );
        }
    }

    // (3) Fulfill queued incoming ResendRequests (for OUR messages).
    let pending: Vec<(String, u64, u64)> = pending_resends.lock()
        .map(|mut q| { let drained = q.clone(); q.clear(); drained })
        .unwrap_or_default();
    for (chat_id, from_seq, to_seq) in pending {
        let entries = {
            let conn = db.connection();
            alpha_database::delivery::DeliveryRepo::outbox_get_range(&conn, &chat_id, from_seq, to_seq)
                .unwrap_or_default()
        };
        let gid = match hex::decode(&chat_id) { Ok(g) => g, Err(_) => continue };
        let keys = identity.lock().ok()
            .and_then(|g| g.as_ref().map(|id| id.nostr().keypair().keys().clone()));
        let keys = match keys { Some(k) => k, None => continue };
        let bridge = NostrMlsBridge::new((**transport).clone());
        let mls_clone = mls_session.clone();
        let db_clone = db.clone();
        runtime().spawn(async move {
            for e in entries {
                let enc = mls_clone.lock().ok().and_then(|mut m| {
                    m.send(&gid, &e.payload).ok().map(|ct| {
                        let ep = m.group_info(&gid).map(|i| i.epoch).unwrap_or(0);
                        (ct, ep)
                    })
                });
                let (ct, epoch) = match enc { Some(x) => x, None => continue };
                let env = MlsEnvelope::new(MlsMessageType::Application, gid.clone(), epoch, ct);
                if bridge.send_mls_message(&keys, &env, None, None).await.is_ok() {
                    let conn = db_clone.connection();
                    let _ = alpha_database::delivery::DeliveryRepo::outbox_mark_sent(&conn, e.id);
                }
            }
        });
    }

    // (4) Proactive resend of stale pending outbox rows.
    const RESEND_TIMEOUT_SECS: i64 = 30;
    let stale: Vec<alpha_database::delivery::DeliveryOutboxEntry> = {
        let conn = db.connection();
        let cutoff = chrono::Utc::now() - chrono::Duration::seconds(RESEND_TIMEOUT_SECS);
        alpha_database::delivery::DeliveryRepo::outbox_list_pending(&conn).unwrap_or_default()
            .into_iter().filter(|e| {
                e.last_sent_at.is_empty()
                    || chrono::DateTime::parse_from_rfc3339(&e.last_sent_at)
                        .map(|t| t.with_timezone(&chrono::Utc) < cutoff).unwrap_or(true)
            }).collect()
    };
    for e in stale {
        if e.send_count > 50 { continue; }
        let gid = match hex::decode(&e.chat_id) { Ok(g) => g, Err(_) => continue };
        let enc = mls_session.lock().ok().and_then(|mut m| {
            m.send(&gid, &e.payload).ok().map(|ct| {
                let ep = m.group_info(&gid).map(|i| i.epoch).unwrap_or(0);
                (ct, ep)
            })
        });
        let (ct, epoch) = match enc { Some(x) => x, None => continue };
        let keys = identity.lock().ok()
            .and_then(|g| g.as_ref().map(|id| id.nostr().keypair().keys().clone()));
        let keys = match keys { Some(k) => k, None => continue };
        let bridge = NostrMlsBridge::new((**transport).clone());
        let env = MlsEnvelope::new(MlsMessageType::Application, gid, epoch, ct);
        let db_clone = db.clone();
        let id = e.id;
        runtime().spawn(async move {
            if bridge.send_mls_message(&keys, &env, None, None).await.is_ok() {
                let conn = db_clone.connection();
                let _ = alpha_database::delivery::DeliveryRepo::outbox_mark_sent(&conn, id);
            }
        });
    }

    // (5) Prune expired outbox (7-day TTL).
    let conn = db.connection();
    let _ = alpha_database::delivery::DeliveryRepo::outbox_prune_expired(&conn, 7 * 24 * 3600);
}

#[cfg(test)]
mod stage3_resolver_tests {
    use super::*;
    use alpha_database::{DatabasePool, KeyValueRepo};

    fn tmp(name: &str) -> std::path::PathBuf {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_stage3_{name}.db"));
        let _ = std::fs::remove_file(&p);
        let _ = std::fs::remove_file(format!("{}-wal", p.display()));
        let _ = std::fs::remove_file(format!("{}-shm", p.display()));
        let _ = std::fs::remove_file(keys::backup_path_for(&p));
        p
    }

    fn db_key_hex(master: [u8; 32]) -> String {
        let k = keys::derive_subkey(&keys::SecretKey::from_bytes(master), keys::KDF_INFO_DB);
        hex::encode(k.as_bytes())
    }

    fn seed_identity(pool: &DatabasePool) {
        let conn = pool.connection();
        let _ = KeyValueRepo::set(&conn, KV_IDENTITY, "{\"x\":1}");
    }

    fn state_of(pool: &DatabasePool) -> String {
        let conn = pool.connection();
        KeyValueRepo::get(&conn, KV_DB_REKEY_STATE).ok().flatten().unwrap_or_else(|| "legacy".into())
    }

    #[test]
    fn derive_master_from_public_roundtrip_and_wrong_password() {
        // Build a public KDF record exactly like set_recovery_password does, then
        // confirm the standalone (DB-free) derivation reproduces the master key
        // and rejects a wrong password.
        let salt = keys::generate_salt();
        let params = keys::KdfParams::recommended();
        let master = keys::derive_master_key("correct horse", &salt, &params).unwrap();
        let verifier = keys::compute_verifier(&master);
        let record = RecoveryKdfRecord {
            version: keys::ALPHA_KDF_VERSION,
            params,
            salt_b64: base64::Engine::encode(&base64::engine::general_purpose::STANDARD, salt),
            verifier_b64: base64::Engine::encode(&base64::engine::general_purpose::STANDARD, verifier),
        };
        let json = serde_json::to_string(&record).unwrap();

        let got = derive_master_key_from_public("correct horse".into(), json.clone()).unwrap();
        assert_eq!(got.as_slice(), master.as_bytes());

        assert!(derive_master_key_from_public("WRONG".into(), json).is_err());
        assert!(derive_master_key_from_public("x".into(), "not json".into()).is_err());
    }

    #[test]
    fn plan_mapping_is_total_and_deterministic() {
        assert_eq!(resolve_open_plan("legacy"), OpenPlan::Legacy);
        assert_eq!(resolve_open_plan(""), OpenPlan::Legacy);
        assert_eq!(resolve_open_plan("garbage"), OpenPlan::Legacy);
        assert_eq!(resolve_open_plan("in_progress"), OpenPlan::RecoverInProgress);
        assert_eq!(resolve_open_plan("migrated"), OpenPlan::MigratedDbKey);
        assert_eq!(resolve_open_plan("confirmed"), OpenPlan::ConfirmedDbKey);
    }

    #[test]
    fn legacy_hint_opens_an_existing_empty_key_database() {
        let path = tmp("legacy");
        DatabasePool::open(&path, "").unwrap().close().unwrap();
        let r = resolve_and_open(&path, None, None, "legacy", false).unwrap();
        assert!(!r.recovery_attempted_now);
        let _ = r.pool.close();
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn no_new_database_without_a_key() {
        let path = tmp("no_key");
        let _ = std::fs::remove_file(&path);
        assert!(resolve_and_open(&path, None, None, "legacy", false).is_err());
        assert!(!path.exists() || std::fs::metadata(&path).map(|m| m.len() == 0).unwrap_or(true));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_new_database_is_under_the_device_key() {
        let path = tmp("device_new");
        let device = db_key_hex([9u8; 32]);
        let r = resolve_and_open(&path, None, Some(&device), "legacy", false).unwrap();
        seed_identity(&r.pool);
        r.pool.close().unwrap();
        assert!(DatabasePool::open(&path, "").is_err(), "the empty key must not open it");
        let again = resolve_and_open(&path, None, Some(&device), "legacy", false).unwrap();
        let _ = again.pool.close();
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn an_empty_key_database_still_opens_with_a_device_key() {
        let path = tmp("device_old");
        let pool = DatabasePool::open(&path, "").unwrap();
        seed_identity(&pool);
        pool.close().unwrap();
        let r = resolve_and_open(&path, None, Some(&db_key_hex([9u8; 32])), "legacy", false).unwrap();
        let _ = r.pool.close();
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn confirmed_opens_with_db_key_and_rejects_without() {
        let path = tmp("confirmed");
        let master = [7u8; 32];
        let key = db_key_hex(master);
        // Create + rekey to db_key.
        let pool = DatabasePool::open(&path, "").unwrap();
        seed_identity(&pool);
        pool.rekey_to_raw_key(&key).unwrap();
        pool.close().unwrap();

        // confirmed + correct key → opens.
        let r = resolve_and_open(&path, Some(&key), None, "confirmed", false).unwrap();
        assert!(!r.recovery_attempted_now);
        let _ = r.pool.close();
        // confirmed + no key → hard error (NO legacy fallback).
        assert!(resolve_and_open(&path, None, None, "confirmed", false).is_err());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn migrated_one_shot_fallback_then_hard_error() {
        let path = tmp("migrated_fallback");
        let master = [9u8; 32];
        let key = db_key_hex(master);
        let wrong = db_key_hex([1u8; 32]);
        // Legacy DB + backup (the pre-rekey "" copy), then rekey main to db_key.
        let pool = DatabasePool::open(&path, "").unwrap();
        seed_identity(&pool);
        pool.checkpoint_truncate().unwrap();
        keys::backup_db_file(&path).unwrap();
        pool.rekey_to_raw_key(&key).unwrap();
        pool.close().unwrap();

        // migrated + WRONG key + first attempt → restore→legacy fallback.
        let r = resolve_and_open(&path, Some(&wrong), None, "migrated", false).unwrap();
        assert!(r.recovery_attempted_now, "should have restored on first failure");
        assert_eq!(state_of(&r.pool), "legacy");
        let _ = r.pool.close();

        // Re-stage migrated again, but now recovery_already_attempted=true → hard error.
        let pool = DatabasePool::open(&path, "").unwrap();
        seed_identity(&pool);
        keys::backup_db_file(&path).unwrap();
        pool.rekey_to_raw_key(&key).unwrap();
        pool.close().unwrap();
        assert!(resolve_and_open(&path, Some(&wrong), None, "migrated", true).is_err());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn in_progress_recovers_both_ways() {
        // (a) rekey hadn't taken → "" still opens → reset to legacy.
        let path = tmp("inprog_legacy");
        let pool = DatabasePool::open(&path, "").unwrap();
        seed_identity(&pool);
        { let conn = pool.connection(); let _ = KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, "in_progress"); }
        pool.close().unwrap();
        let r = resolve_and_open(&path, Some(&db_key_hex([3u8; 32])), None, "in_progress", false).unwrap();
        assert!(!r.recovery_attempted_now);
        assert_eq!(state_of(&r.pool), "legacy");
        let _ = r.pool.close();
        let _ = std::fs::remove_file(&path);

        // (b) rekey completed but state stuck → db_key opens → finalize migrated.
        let path = tmp("inprog_migrated");
        let master = [5u8; 32];
        let key = db_key_hex(master);
        let pool = DatabasePool::open(&path, "").unwrap();
        seed_identity(&pool);
        { let conn = pool.connection(); let _ = KeyValueRepo::set(&conn, KV_DB_REKEY_STATE, "in_progress"); }
        pool.rekey_to_raw_key(&key).unwrap();
        pool.close().unwrap();
        let r = resolve_and_open(&path, Some(&key), None, "in_progress", false).unwrap();
        assert!(!r.recovery_attempted_now);
        assert_eq!(state_of(&r.pool), "migrated");
        let _ = r.pool.close();
        let _ = std::fs::remove_file(&path);
    }
}

/// Outcome of one replication step.
///
/// Intentionally typed: the executor must distinguish permanent refusal from
/// a transient one. Collapsing everything to `null` would treat an identity
/// mismatch and a network cut alike — with a retry that can never help the
/// former.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReplicationOutcome {
    /// The storage confirmed a copy with the same identity.
    Confirmed,
    /// A re-derived object did not match expectations — the network untouched.
    IdentityMismatch,
    /// The storage is not in the configured topology or is disabled.
    EndpointUnavailable,
    NetworkError,
    /// The storage rejects our object format.
    CapabilityUnsupported,
    /// The immutable output is unavailable.
    SourceMissing,
    /// No key — deterministic derivation impossible.
    KeyMissing,
}

/// Trusted facts about an object: written by the send path, read by the core.
#[derive(Debug, Clone)]
pub struct ReplicationObjectSpec {
    pub send_id: String,
    pub item_index: u32,
    pub revision: u64,
    pub object_index: u32,
    pub object_total: u32,
    pub output_path: String,
    pub expected_id: String,
}

#[cfg(test)]
mod t4_identity {
    use super::output_revision;

    /// Prepared output: the revision comes from the fingerprint computed by
    /// the app. No second "same job?" formula appears here.
    #[test]
    fn авторитетная_ревизия_имеет_приоритет() {
        assert_eq!(output_revision("/data/0.r1294751840514572310.mp4"), Some(1294751840514572310));
    }

    /// Document: same copy — same identity on every attempt. Without this,
    /// document resume is impossible in principle.
    #[test]
    fn снимок_документа_даёт_ту_же_личность() {
        let p = std::env::temp_dir().join(format!("t4id_{}.bin", uuid::Uuid::new_v4()));
        std::fs::write(&p, vec![7u8; 4096]).unwrap();
        let path = p.to_string_lossy().to_string();
        let a = output_revision(&path).expect("личность обязана определиться");
        let b = output_revision(&path).expect("личность обязана определиться");
        assert_eq!(a, b, "повторная попытка обязана дать ту же личность");
        let _ = std::fs::remove_file(&p);
    }

    /// A different copy is a different identity: one send's confirmations must
    /// not apply to another.
    #[test]
    fn разные_снимки_не_делят_личность() {
        let dir = std::env::temp_dir();
        let p1 = dir.join(format!("t4id_a_{}.bin", uuid::Uuid::new_v4()));
        let p2 = dir.join(format!("t4id_b_{}.bin", uuid::Uuid::new_v4()));
        std::fs::write(&p1, vec![1u8; 4096]).unwrap();
        std::fs::write(&p2, vec![1u8; 4096]).unwrap();
        assert_ne!(
            output_revision(&p1.to_string_lossy()),
            output_revision(&p2.to_string_lossy()),
        );
        let _ = std::fs::remove_file(&p1);
        let _ = std::fs::remove_file(&p2);
    }

    /// The size changed — a different file; old confirmations are not trusted.
    #[test]
    fn изменившийся_размер_меняет_личность() {
        let p = std::env::temp_dir().join(format!("t4id_{}.bin", uuid::Uuid::new_v4()));
        std::fs::write(&p, vec![7u8; 4096]).unwrap();
        let path = p.to_string_lossy().to_string();
        let before = output_revision(&path);
        std::fs::write(&p, vec![7u8; 8192]).unwrap();
        assert_ne!(before, output_revision(&path));
        let _ = std::fs::remove_file(&p);
    }

    /// An empty or missing file has no identity — the send goes whole, as
    /// before, instead of applying foreign state.
    #[test]
    fn без_файла_личности_нет() {
        assert_eq!(output_revision("/nope/missing.bin"), None);
    }
}

#[cfg(test)]
mod t4_one_owner {
    use super::{upload_is_in_flight, UploadClaim};

    /// A second claim on the same send is never granted. That is the guard
    /// against two uploaders sending one message to the relay twice.
    #[test]
    fn вторая_заявка_на_ту_же_отправку_отклоняется() {
        let a = UploadClaim::take("msg-1").expect("первая заявка обязана пройти");
        assert!(UploadClaim::take("msg-1").is_none(), "вторая заявка обязана быть отклонена");
        drop(a);
        assert!(UploadClaim::take("msg-1").is_some(), "после освобождения отправка снова доступна");
    }

    /// Different sends do not interfere.
    #[test]
    fn разные_отправки_идут_одновременно() {
        let _a = UploadClaim::take("msg-2").unwrap();
        assert!(UploadClaim::take("msg-3").is_some());
    }

    /// Busy-ness is visible per whole message, including set elements: the
    /// core queue holds one row per message, elements go as `<message>#<n>`.
    #[test]
    fn занятость_элемента_видна_по_сообщению() {
        let _a = UploadClaim::take("msg-4#2").unwrap();
        assert!(upload_is_in_flight("msg-4"), "занятый элемент обязан закрывать всё сообщение");
        assert!(!upload_is_in_flight("msg-5"));
    }

    /// Release happens on early error returns too: the claim is dropped by
    /// destroying the value, not by a call someone could forget.
    #[test]
    fn ошибка_не_оставляет_отправку_занятой() {
        fn падает() -> Result<(), ()> {
            let _claim = UploadClaim::take("msg-6").ok_or(())?;
            Err(())
        }
        assert!(падает().is_err());
        assert!(!upload_is_in_flight("msg-6"), "после ошибки отправка обязана освободиться");
    }
}

/// Apply a transport directory — the SINGLE implementation.
///
/// A free function because it is called from two places: a core method and
/// the catch-up event handler, which holds only Arcs, not `self`.
/// Same idiom as `publish_role_snapshot_raw`. No second copy of the logic.
#[allow(clippy::too_many_arguments)]
fn apply_group_directory_raw(
    db: &Arc<DatabasePool>,
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    identity: &Arc<TrackedMutex<Option<DualIdentity>>>,
    group_id: &[u8],
    sender_sig_hex: &str,
    version: u32,
    revision: u64,
    entries: &[alpha_messages::message_type::GroupDirectoryEntry],
) {
        if version == 0 || version > DIRECTORY_VERSION {
            tracing::warn!("DIR отклонён: версия {version} не поддерживается");
            return;
        }
        let chat_id = hex::encode(group_id);
        let members = member_sig_keys_of(mls_session, group_id);
        let proven = proven_owners_of(mls_session, group_id);

        // Authorization: the sender must themselves be in the group.
        if !members.iter().any(|m| m == sender_sig_hex) {
            tracing::warn!("DIR отклонён: отправитель не состоит в группе");
            return;
        }

        // Rollback: a revision older than the accepted one is dropped. An equal
        // revision with different content is also rejected (the mismatch is
        // caught below, when rewriting an established binding).
        let known = directory_epoch_of(db, &chat_id);
        if revision < known {
            tracing::warn!("DIR отклонён: ревизия {revision} старше принятой {known}");
            return;
        }
        // The revision must equal the group's current epoch: a directory from a
        // foreign epoch describes a different membership.
        let epoch_now = mls_epoch_of(mls_session, group_id);
        if revision != epoch_now {
            tracing::warn!("DIR отклонён: ревизия {revision} не совпала с эпохой {epoch_now}");
            return;
        }

        let conn = db.connection();
        // The inviter is the only one whose authorization a newcomer can
        // confirm: their signature is on the Seal of the Welcome we joined by.
        // Everyone else needs a provenance record.
        let bootstrap_auth = KeyValueRepo::get(&conn, &dir_bootstrap_key_of(&chat_id))
            .ok().flatten().unwrap_or_default();
        // --- The inviter is identified by PROOF, not by assertion ---
        //
        // The inviter must be identified by PROOF, not by the sender asserting
        // an address: a self-proclaimed address proves nothing, and binding
        // security cannot rest on "a spoof would not decrypt anyway".
        //
        // The real proof is TWO independent checks matching in one message:
        // MLS attests the sender's signing key, the Seal attests their transport
        // address. That pair binds key and address cryptographically, and it is
        // recorded while parsing the incoming message, not taken from directory
        // content.
        //
        // So exactly one question remains here: is the sender's binding already
        // confirmed? If yes, they are who they claim, and their primary directory
        // is accepted.
        let sender_bound = KeyValueRepo::get(&conn, &dir_key_of(&chat_id, sender_sig_hex))
            .ok().flatten().unwrap_or_default();
        let sender_is_inviter = !bootstrap_auth.is_empty()
            && !sender_bound.is_empty()
            && sender_bound == bootstrap_auth;

        // Check EVERYTHING first, then write: a partially applied directory is
        // worse than a rejected one.
        let mut pending: Vec<(String, String)> = Vec::new();
        for e in entries {
            if !members.iter().any(|m| *m == e.sig_key) {
                tracing::warn!("DIR: запись про не-участника отброшена");
                continue; // only MLS changes membership
            }
            // A leaf that proves its owner needs no provenance, and no
            // directory may name anyone else for it.
            if let Some((_, owner)) = proven.iter().find(|(sig, _)| *sig == e.sig_key) {
                if *owner != e.npub_hex {
                    tracing::warn!("DIR отклонён целиком: запись противоречит подписи участника");
                    return;
                }
                continue;
            }
            let existing = KeyValueRepo::get(&conn, &dir_key_of(&chat_id, &e.sig_key))
                .ok().flatten().unwrap_or_default();
            if !existing.is_empty() {
                if existing != e.npub_hex {
                    tracing::warn!("DIR отклонён целиком: попытка переписать готовую привязку");
                    return;
                }
                continue; // already checked above; a repeat is harmless
            }
            // --- FIRST binding: provenance required ---
            //
            // The sender's membership was checked above and is NOT enough.
            // One of two things qualifies: they added this member to the group
            // (the address was earned — the KeyPackage was fetched by it), or
            // they added US and we accept their primary directory.
            let authority = KeyValueRepo::get(&conn, &dir_auth_key_of(&chat_id, &e.sig_key))
                .ok().flatten().unwrap_or_default();
            let by_adder = !authority.is_empty() && authority == sender_sig_hex;
            if !by_adder && !sender_is_inviter {
                tracing::warn!(
                    "DIR отклонён целиком: первая привязка без подтверждённого происхождения",
                );
                return;
            }
            pending.push((e.sig_key.clone(), e.npub_hex.clone()));
        }
        for (sig, npub) in &pending {
            let _ = KeyValueRepo::set(&conn, &dir_key_of(&chat_id, sig), npub);
            // The shared map stays a DISPLAY representation for names only.
            let _ = KeyValueRepo::set(&conn, &format!("member_npub:{sig}"), npub);
        }
        let _ = KeyValueRepo::set(&conn, &dir_epoch_key_of(&chat_id), &revision.to_string());
        alpha_database::policy::record_proven(&conn, &chat_id, &proven);
    tracing::info!("DIR принят: эпоха={revision} записей={} новых={}", entries.len(), pending.len());
}

// --- Directory free helpers ---
//
// The same key-naming rules as the core methods, but callable from the
// catch-up handler. Declared once; the core methods use them too.

fn dir_key_of(chat_id: &str, sig_hex: &str) -> String {
    format!("dir:{chat_id}:{sig_hex}")
}

fn dir_epoch_key_of(chat_id: &str) -> String {
    format!("dir_epoch:{chat_id}")
}

fn dir_auth_key_of(chat_id: &str, sig_hex: &str) -> String {
    format!("dir_auth:{chat_id}:{sig_hex}")
}

fn dir_bootstrap_key_of(chat_id: &str) -> String {
    format!("dir_bootstrap_auth:{chat_id}")
}

fn member_sig_keys_of(
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    group_id: &[u8],
) -> Vec<String> {
    mls_session
        .lock()
        .ok()
        .and_then(|mls| mls.group_info(group_id).map(|i| i.members))
        .unwrap_or_default()
        .iter()
        .map(|m| hex::encode(&m.signature_key))
        .collect()
}

/// Order-independent: SHA-512 over both npubs, 12 groups of 5 digits. The same
/// on both phones, and unchanged since the first version.
fn safety_number_of(a: &str, b: &str) -> String {
    use sha2::{Digest, Sha512};
    let (x, y) = if a < b { (a, b) } else { (b, a) };
    let digest = Sha512::digest(format!("alpha-safety-number-v1|{x}|{y}").as_bytes());
    (0..12)
        .map(|g| {
            let v = digest[g * 5..g * 5 + 5].iter().fold(0u64, |v, b| (v << 8) | u64::from(*b));
            format!("{:05}", v % 100_000)
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Members whose leaf proves its owner: (signing key hex, npub hex).
fn proven_owners_of(
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    group_id: &[u8],
) -> Vec<(String, String)> {
    mls_session
        .lock()
        .ok()
        .and_then(|mls| mls.group_info(group_id).map(|i| alpha_mls::leaf_binding::proven_owners(&i.members)))
        .unwrap_or_default()
}

fn mls_epoch_of(
    mls_session: &Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>,
    group_id: &[u8],
) -> u64 {
    mls_session
        .lock()
        .ok()
        .and_then(|mls| mls.group_info(group_id).map(|i| i.epoch))
        .unwrap_or(0)
}

fn directory_epoch_of(db: &Arc<DatabasePool>, chat_id: &str) -> u64 {
    let conn = db.connection();
    KeyValueRepo::get(&conn, &dir_epoch_key_of(chat_id))
        .ok()
        .flatten()
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0)
}

/// BLAKE3 of a whole file (streamed), or None when it cannot be read.
fn file_blake3(path: &str) -> Option<[u8; 32]> {
    let mut f = std::fs::File::open(path).ok()?;
    let mut h = blake3::Hasher::new();
    std::io::copy(&mut f, &mut h).ok()?;
    Some(*h.finalize().as_bytes())
}

#[cfg(test)]
mod safety_number {
    use super::safety_number_of;

    /// Same digits as the app computed before the number moved into the core:
    /// numbers people already compared stay valid.
    #[test]
    fn matches_the_first_version_and_ignores_order() {
        let want = "19301 32491 95452 42789 48952 69674 27681 24068 69629 44228 80776 35599";
        assert_eq!(safety_number_of("npub1aaa", "npub1bbb"), want);
        assert_eq!(safety_number_of("npub1bbb", "npub1aaa"), want);
    }
}

#[cfg(test)]
mod secret_backup {
    use super::*;

    /// A secret chat leaves no trace in a backup: no chat row, no messages,
    /// no keys, no MLS state — while an ordinary chat is all there.
    #[test]
    fn secret_chats_stay_out_of_backups() {
        let dir = std::env::temp_dir().join(format!("secret-backup-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = DatabasePool::open(&dir.join("t.db"), "").unwrap();
        let conn = db.connection();
        let (secret, plain) = ("aa11", "bb22");
        for id in [secret, plain] {
            ChatRepo::insert(&conn, &new_chat(id.to_string(), alpha_database::models::ChatType::Direct, None, 0)).unwrap();
        }
        KeyValueRepo::set(&conn, &format!("{}{secret}", secret_chat::KV_SECRET_CHAT), plain).unwrap();
        KeyValueRepo::set(&conn, &format!("no_store:{secret}"), "1").unwrap();
        KeyValueRepo::set(&conn, &format!("no_store:{plain}"), "1").unwrap();
        let marker = alpha_mls::group_storage_marker(&hex::decode(secret).unwrap());
        let mut k = b"group_state".to_vec();
        k.extend_from_slice(&marker);
        alpha_database::MlsStorageRepo::save_snapshot(&conn, &[(k, b"secret-state".to_vec()), (b"other".to_vec(), b"plain-state".to_vec())]).unwrap();

        KeyValueRepo::set(&conn, KV_IDENTITY, "identity-of-this-phone").unwrap();
        let mut out = String::new();
        AlphaMessengerCore::dump_tables_sql(&conn, &mut out, false).unwrap();
        assert!(!out.contains(secret), "secret chat id leaked into the backup");
        assert!(out.contains(plain));
        assert!(!out.contains(&hex::encode(b"secret-state")) && !out.to_lowercase().contains(&hex::encode(b"secret-state")));
        assert!(out.to_lowercase().contains(&hex::encode(b"plain-state")));
        assert!(out.contains("identity-of-this-phone"), "a backup moves this phone's identity");

        // The history for a linked phone: no MLS state, no identity, chats replaced.
        let mut link = String::new();
        AlphaMessengerCore::dump_tables_sql(&conn, &mut link, true).unwrap();
        assert!(!link.contains(secret));
        assert!(link.contains(plain));
        assert!(!link.to_lowercase().contains(&hex::encode(b"plain-state")), "MLS state stays with its phone");
        assert!(!link.contains("identity-of-this-phone"), "the other phone keeps its own identity");
        assert!(link.contains("INSERT OR REPLACE INTO \"chats\""));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A backup of a real database restores into a fresh one, and its
    /// messages can be found by their words afterwards.
    #[test]
    fn a_backup_restores_with_search() {
        let dir = std::env::temp_dir().join(format!("restore-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let from = DatabasePool::open(&dir.join("a.db"), "").unwrap();
        let sql = {
            let conn = from.connection();
            ChatRepo::insert(&conn, &new_chat("cc33".into(), alpha_database::models::ChatType::Direct, None, 0)).unwrap();
            alpha_database::ContactRepo::insert(&conn, &alpha_database::repositories::new_contact("self".into(), None, vec![1; 32], None)).unwrap();
            for text in ["hello restored world", "second note"] {
                let m = alpha_database::repositories::new_message(
                    "cc33".into(), "self".into(), alpha_database::models::MessageType::Text,
                    Some(text.into()), None, None, None, None);
                MessageRepo::insert(&conn, &m).unwrap();
            }
            let mut out = String::from("PRAGMA foreign_keys=OFF;\n");
            AlphaMessengerCore::dump_tables_sql(&conn, &mut out, false).unwrap();
            out.push_str("PRAGMA foreign_keys=ON;\n");
            out
        };
        assert!(!sql.contains("message_fts"), "the search index stays out of backups");
        // What backups made before that carry of it.
        let old = format!(
            "CREATE VIRTUAL TABLE message_fts USING fts5(body, content = '', contentless_delete = 1);\n\
             INSERT OR IGNORE INTO \"message_fts\" (\"body\") VALUES\n(NULL),\n(NULL);\n\
             CREATE TABLE 'message_fts_data'(id INTEGER PRIMARY KEY, block BLOB);\n\
             INSERT OR IGNORE INTO \"message_fts_data\" (\"id\", \"block\") VALUES\n(10, X'00');\n{sql}");
        let to = DatabasePool::open(&dir.join("b.db"), "").unwrap();
        let conn = to.connection();
        conn.execute_batch("BEGIN;").unwrap();
        let r = AlphaMessengerCore::replay_backup_sql(&conn, &AlphaMessengerCore::sanitize_backup_sql(&old));
        let shown = r.as_ref().err().map(|e| AlphaMessengerCore::short_sql_error(e));
        assert!(r.is_ok(), "restore failed: {shown:?}");
        conn.execute_batch("COMMIT;").unwrap();
        let n: i64 = conn.query_row("SELECT count(*) FROM messages", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 2);
        let found: i64 = conn.query_row(
            "SELECT count(*) FROM message_fts WHERE message_fts MATCH 'restored'", [], |r| r.get(0)).unwrap();
        assert_eq!(found, 1, "restored messages are searchable");
        let triggers: i64 = conn.query_row(
            "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'message_fts_%'", [], |r| r.get(0)).unwrap();
        assert_eq!(triggers, 3, "the search triggers are back after the restore");
        drop(conn);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A failed statement names itself briefly: the rest of the dump stays out.
    #[test]
    fn a_failed_restore_says_little() {
        let c = rusqlite::Connection::open_in_memory().unwrap();
        let tail = "x".repeat(100_000);
        let e = c.execute_batch(&format!("INSERT INTO nowhere VALUES(1); SELECT '{tail}';")).unwrap_err();
        let s = AlphaMessengerCore::short_sql_error(&e);
        assert!(s.contains("nowhere") && s.len() < 400, "{s}");
    }
}

/// A ref as it goes on the wire: our file's path stays on this phone.
fn strip_local_path(v: &serde_json::Value) -> String {
    let mut w = v.clone();
    if let Some(m) = w.as_object_mut() { m.remove("local_path"); }
    w.to_string()
}

/// What the sender keeps for a ref that went out through a manifest: the slim ref
/// (as a receiver has it) plus the path to our own file. The full chunk list of a
/// big file is megabytes; kept in the message it was carried to the UI on every
/// reload. None when nothing was externalized: the original stays as it was.
fn local_copy_of(slim: &serde_json::Value, original: &serde_json::Value, externalized: bool) -> Option<String> {
    if !externalized { return None; }
    let mut local = slim.clone();
    let path = original.get("local_path").cloned();
    if let (Some(m), Some(p)) = (local.as_object_mut(), path) { m.insert("local_path".into(), p); }
    Some(local.to_string())
}
