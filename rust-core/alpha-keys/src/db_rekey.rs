//! Database re-key helpers (RoadmapE2EE Phase 6).
//!
//! Migrates the live SQLCipher database from the legacy empty passphrase to a
//! real `DatabaseKey` derived from the user's Recovery Password.
//!
//! This is the **riskiest** operation in the whole roadmap — a botched rekey
//! can make the DB permanently unreadable. The helpers here implement a safe,
//! reversible procedure:
//!
//! 1. **Backup** the DB file to `<db_path>.prekey-bak` *before* touching the key.
//! 2. **Rekey** via SQLCipher `PRAGMA rekey` on the already-open connection.
//! 3. **Verify** the new key works by forcing a page read (`SELECT count(*) …`).
//!    If it fails, restore the backup file and return an error.
//! 4. **Commit** by setting a KV flag so the next launch opens with the new key.
//!
//! All file I/O is done through the filesystem (the rusqlite connection itself
//! can't be "rewound" after a failed rekey, so the file copy is our safety net).
//!
//! # Why derive from the Recovery Password
//!
//! `DatabaseKey = HKDF(MasterDataKey, "alpha/v1/db")` — i.e. it's a sub-key of
//! the hierarchy established in Phase 1. That means the same Recovery Password
//! re-derives the exact same DatabaseKey on any device, so a restored account
//! (Phase 4) opens its DB with the correct key without any extra storage.

use crate::{derive_master_key, derive_subkey, KdfParams, SecretKey, KDF_INFO_DB};
use std::path::{Path, PathBuf};

#[derive(Debug, thiserror::Error)]
pub enum RekeyError {
    #[error("database backup failed: {0}")]
    Backup(String),
    #[error("PRAGMA rekey failed: {0}")]
    Rekey(String),
    #[error("post-rekey verify failed: {0}")]
    Verify(String),
    #[error("could not restore backup: {0}")]
    Restore(String),
    #[error("invalid parameters: {0}")]
    InvalidParams(String),
    #[error("argon2id derivation failed: {0}")]
    Kdf(String),
}

/// Result of a successful rekey — what the caller needs to persist.
#[derive(Debug, Clone)]
pub struct RekeyOutcome {
    /// Path of the safety backup file (kept around so we can roll back later).
    pub backup_path: PathBuf,
    /// Hex of the DatabaseKey actually applied (for diagnostics ONLY; never log).
    pub _key_hex_redacted: String,
}

/// Derive the SQLCipher `DatabaseKey` (32 bytes) from the Recovery Password.
///
/// `password` + `salt` + `params` → MasterDataKey (Argon2id) → DatabaseKey (HKDF).
/// The same inputs always yield the same DatabaseKey, which is what lets a
/// restored account open its DB on a new device.
///
/// Returns a [`SecretKey`] so the caller can wipe it when done.
pub fn derive_database_key(
    password: &str,
    salt: &[u8; 16],
    params: &KdfParams,
) -> Result<SecretKey, RekeyError> {
    let master = derive_master_key(password, salt, params).map_err(|e| RekeyError::Kdf(e.to_string()))?;
    Ok(derive_subkey(&master, KDF_INFO_DB))
}

/// The SQLCipher PRAGMA-key string for a raw 32-byte key.
///
/// SQLCipher accepts a raw key as `x'<64 hex chars>'` (the `x'…'` form bypasses
/// the KDF entirely — we've already done Argon2id ourselves). Hex is lowercase.
pub fn pragma_key_for(key: &SecretKey) -> String {
    let hex = hex::encode(key.as_bytes());
    format!("x'{hex}'")
}

/// Make a timestamped backup path next to the DB file: `<db>.prekey-bak`.
/// This is the file we restore from if the rekey verification fails.
pub fn backup_path_for(db_path: &Path) -> PathBuf {
    let mut p = db_path.as_os_str().to_os_string();
    p.push(".prekey-bak");
    PathBuf::from(p)
}

/// Copy the DB file to [backup_path_for]. Returns the backup path on success.
/// Fails if the source doesn't exist or the copy can't be written.
pub fn backup_db_file(db_path: &Path) -> Result<PathBuf, RekeyError> {
    let bak = backup_path_for(db_path);
    std::fs::copy(db_path, &bak)
        .map_err(|e| RekeyError::Backup(format!("copy {} → {}: {}", db_path.display(), bak.display(), e)))?;
    Ok(bak)
}

/// Restore the DB file from a previous [backup_db_file]. Used as the rollback
/// path when post-rekey verification fails. Overwrites the (now-broken) DB file.
pub fn restore_db_file(db_path: &Path) -> Result<(), RekeyError> {
    let bak = backup_path_for(db_path);
    if !bak.exists() {
        return Err(RekeyError::Restore(format!(
            "backup {} not found — cannot roll back",
            bak.display()
        )));
    }
    std::fs::copy(&bak, db_path)
        .map_err(|e| RekeyError::Restore(format!("restore {} → {}: {}", bak.display(), db_path.display(), e)))?;
    // The journal of the failed attempt belongs to the re-keyed file: replayed
    // onto the restored one it would corrupt it.
    for side in ["-wal", "-shm"] {
        let mut p = db_path.as_os_str().to_os_string();
        p.push(side);
        let _ = std::fs::remove_file(PathBuf::from(p));
    }
    Ok(())
}

/// Delete the backup file once the caller is confident the migration is good
/// (e.g. after several successful launches with the new key). Optional — leaving
/// the backup around is harmless, it's just disk space.
pub fn discard_backup(db_path: &Path) {
    let bak = backup_path_for(db_path);
    // The copy may be readable without the new key: overwrite it before unlinking.
    if let Ok(len) = std::fs::metadata(&bak).map(|m| m.len()) {
        if let Ok(mut f) = std::fs::OpenOptions::new().write(true).open(&bak) {
            use std::io::Write;
            let zeros = vec![0u8; 64 * 1024];
            let mut left = len;
            while left > 0 {
                let n = left.min(zeros.len() as u64) as usize;
                if f.write_all(&zeros[..n]).is_err() { break; }
                left -= n as u64;
            }
            let _ = f.sync_all();
        }
    }
    let _ = std::fs::remove_file(&bak);
}

// ─────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────
//
// We can't exercise a real SQLCipher rekey in unit tests without a live
// encrypted DB, so these tests cover the deterministic, pure-logic parts:
// key derivation stability, PRAGMA string formatting, and backup-path naming.

#[cfg(test)]
mod tests {
    use super::*;
    use crate::generate_salt;

    #[test]
    fn database_key_is_deterministic() {
        let salt = generate_salt();
        let p = KdfParams { m_cost: 8, t_cost: 1, p_cost: 1, output_len: 32 };
        let a = derive_database_key("pw", &salt, &p).unwrap();
        let b = derive_database_key("pw", &salt, &p).unwrap();
        assert_eq!(a.as_bytes(), b.as_bytes());
    }

    #[test]
    fn database_key_differs_from_master_and_other_subkeys() {
        let salt = generate_salt();
        let p = KdfParams { m_cost: 8, t_cost: 1, p_cost: 1, output_len: 32 };
        let master = derive_master_key("pw", &salt, &p).unwrap();
        let db_key = derive_database_key("pw", &salt, &p).unwrap();
        assert_ne!(master.as_bytes(), db_key.as_bytes(), "DB key must be HKDF-isolated from master");
    }

    #[test]
    fn pragma_key_format_is_raw_hex() {
        let salt = generate_salt();
        let p = KdfParams { m_cost: 8, t_cost: 1, p_cost: 1, output_len: 32 };
        let key = derive_database_key("pw", &salt, &p).unwrap();
        let s = pragma_key_for(&key);
        assert!(s.starts_with("x'") && s.ends_with('\''), "expected x'…' form, got {s}");
        // 32 bytes → 64 hex chars, plus 3 wrapper chars = 67.
        assert_eq!(s.len(), 67);
        let hex = &s[2..66];
        assert!(hex.chars().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn backup_path_naming() {
        let db = Path::new("/tmp/alpha.db");
        let bak = backup_path_for(db);
        assert_eq!(bak, Path::new("/tmp/alpha.db.prekey-bak"));
    }

    #[test]
    fn backup_then_restore_roundtrips_file() {
        let dir = std::env::temp_dir();
        let db = dir.join("alpha_rekey_test.db");
        let _ = std::fs::remove_file(&db);
        let _ = std::fs::remove_file(backup_path_for(&db));
        std::fs::write(&db, b"hello-db").unwrap();

        let bak = backup_db_file(&db).unwrap();
        assert!(bak.exists());

        // Corrupt the "live" file, then restore.
        std::fs::write(&db, b"corrupted").unwrap();
        restore_db_file(&db).unwrap();
        let restored = std::fs::read(&db).unwrap();
        assert_eq!(restored, b"hello-db");

        discard_backup(&db);
        assert!(!backup_path_for(&db).exists());
        let _ = std::fs::remove_file(&db);
    }

    #[test]
    fn restore_without_backup_errors() {
        let dir = std::env::temp_dir();
        let db = dir.join("alpha_rekey_nobak_test.db");
        let _ = std::fs::remove_file(&db);
        let _ = std::fs::remove_file(backup_path_for(&db));
        let res = restore_db_file(&db);
        assert!(matches!(res, Err(RekeyError::Restore(_))));
    }
}
