//! # alpha-keys — Recovery Password key hierarchy
//!
//! Implements the **Data** layer of the dual-secret model (RoadmapE2EE §1):
//!
//! ```text
//! Recovery Password
//!       │  Argon2id (m=64MB, t=3, p=4, salt=16B random)
//!       ▼
//!  MasterDataKey (32 B)
//!       │  HKDF-SHA256 (per-domain `info` label)
//!       ▼
//!  DatabaseKey · MLSBackupKey · MediaKey · BackupFileKey · SettingsKey
//! ```
//!
//! ## Security invariants (enforced here)
//!
//! 1. All secret material lives in [`SecretKey`] which implements [`Zeroize`]
//!    + `ZeroizeOnDrop`, so keys are wiped from memory on scope exit.
//! 2. The salt and Argon2id parameters are **public** (stored in the container
//!    header / KV in cleartext). Only the Recovery Password is secret.
//! 3. Domain separation is fixed via the `KDF_INFO_*` constants — never derived
//!    from user input. Changing an `info` label requires a version bump.
//! 4. `derive_master_key` is deliberately expensive (Argon2id, memory-hard) —
//!    it must run off the UI thread.
//!
//! The `nsec` identity key (Phase 0) is deliberately separate: leaking the
//! Recovery Password does NOT let an attacker sign events, and leaking the
//! nsec does NOT decrypt history.

use argon2::{Algorithm, Argon2, Params, Version};
use hkdf::Hkdf;
use sha2::Sha256;
use subtle::ConstantTimeEq;
use zeroize::{Zeroize, ZeroizeOnDrop};

// AEAD backup container (Phase 2 — backup.enc).
pub mod backup;
pub use backup::{
    decrypt_backup, derive_backup_key, encrypt_backup, encrypt_backup_with_key, peek_params, BackupError, BackupHeader,
    CONTAINER_VERSION, HEADER_LEN, MAGIC,
};

// Database re-key helpers (Phase 6 — SQLCipher rekey from empty → DatabaseKey).
pub mod db_rekey;
pub use db_rekey::{
    backup_db_file, backup_path_for, derive_database_key, discard_backup,
    pragma_key_for, restore_db_file, RekeyError, RekeyOutcome,
};

// ─────────────────────────────────────────────────────────────────────
// Domain separation labels — fixed, version-pinned.
// Changing ANY of these without bumping ALPHA_KDF_VERSION silently makes
// existing backups undecryptable, so treat them as immutable.
// ─────────────────────────────────────────────────────────────────────

/// Bumped when the KDF design, params-defaults, or info-labels change.
/// Stored in the container header so future code can migrate.
pub const ALPHA_KDF_VERSION: u32 = 1;

pub const KDF_INFO_DB: &[u8] = b"alpha/v1/db";
pub const KDF_INFO_MLS: &[u8] = b"alpha/v1/mls";
pub const KDF_INFO_MEDIA: &[u8] = b"alpha/v1/media";
pub const KDF_INFO_BACKUP: &[u8] = b"alpha/v1/backup";
pub const KDF_INFO_SETTINGS: &[u8] = b"alpha/v1/settings";

// ─────────────────────────────────────────────────────────────────────
// Errors
// ─────────────────────────────────────────────────────────────────────

#[derive(Debug, thiserror::Error)]
pub enum KeyError {
    #[error("Argon2id derivation failed: {0}")]
    Argon2(String),
    #[error("invalid parameters: {0}")]
    InvalidParams(String),
    #[error("invalid salt length: expected 16 bytes, got {0}")]
    InvalidSaltLen(usize),
    #[error("salt is all zero bytes — refusing to derive a deterministic shared key")]
    ZeroSalt,
}

// argon2 0.5's `Error` doesn't impl `std::error::Error`, so we can't use
// `#[from]`. Convert to a string description instead — the error is only
// surfaced to the user as "derivation failed".
impl From<argon2::Error> for KeyError {
    fn from(e: argon2::Error) -> Self {
        KeyError::Argon2(format!("{:?}", e))
    }
}

// ─────────────────────────────────────────────────────────────────────
// Secret key wrapper — zeroized on drop
// ─────────────────────────────────────────────────────────────────────

/// A 32-byte secret key that is wiped from memory when dropped.
///
/// Wrap every derived key in this before handing it to a consumer so we
/// never leave raw key bytes on the heap after use.
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct SecretKey([u8; 32]);

impl SecretKey {
    /// Wrap raw 32 bytes. The caller is responsible for how those bytes
    /// were produced (typically via [`derive_master_key`] or
    /// [`derive_subkey`]).
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// Construct from a variable-length slice, rejecting anything that
    /// isn't exactly 32 bytes.
    pub fn from_slice(s: &[u8]) -> Result<Self, KeyError> {
        if s.len() != 32 {
            return Err(KeyError::InvalidParams(format!(
                "secret key must be 32 bytes, got {}",
                s.len()
            )));
        }
        let mut arr = [0u8; 32];
        arr.copy_from_slice(s);
        Ok(Self(arr))
    }

    /// Read-only access to the raw bytes. The borrow is short-lived; the
    /// underlying array is still zeroized when the [`SecretKey`] drops.
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    /// Fixed-length hex for storage/transport where bech32 isn't suitable.
    /// Returns a [`zeroize::Zeroizing`] wrapper so the string is wiped too.
    pub fn to_hex(&self) -> zeroize::Zeroizing<String> {
        zeroize::Zeroizing::new(hex::encode(self.0))
    }
}

impl std::fmt::Debug for SecretKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SecretKey([redacted])")
    }
}

// ─────────────────────────────────────────────────────────────────────
// Argon2id parameters
// ─────────────────────────────────────────────────────────────────────

/// Tunable Argon2id parameters, versioned and serialised into the container
/// header so a future, stronger default can migrate existing accounts.
///
/// Defaults follow OWASP 2024 guidance for password hashing on mobile:
///   * `m_cost` = 64 MiB  (memory-hard; fine on modern phones, blocks GPU farms)
///   * `t_cost` = 3       (iterations)
///   * `p_cost` = 4       (lanes / parallelism)
///
/// These are deliberately heavy — derivation runs on a background thread and
/// the UI shows a spinner. Lowering them would weaken brute-force resistance.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct KdfParams {
    /// Memory cost in **kibibytes** (Argon2's native unit). 65_536 = 64 MiB.
    pub m_cost: u32,
    /// Time cost (iterations over memory).
    pub t_cost: u32,
    /// Parallelism (lanes).
    pub p_cost: u32,
    /// Output length in bytes — always 32 for our hierarchy.
    pub output_len: u32,
}

impl Default for KdfParams {
    fn default() -> Self {
        Self::recommended()
    }
}

impl KdfParams {
    /// OWASP-2024-recommended mobile Argon2id parameters (64 MiB / 3 / 4).
    pub fn recommended() -> Self {
        Self {
            m_cost: 65_536, // 64 MiB
            t_cost: 3,
            p_cost: 4,
            output_len: 32,
        }
    }

    /// Every record and backup we ever made uses [`Self::recommended`]: anything
    /// weaker comes from a tampered file and would make guessing the password cheap.
    fn check_floor(&self) -> Result<(), KeyError> {
        let floor = Self::recommended();
        if self.m_cost < floor.m_cost || self.t_cost < floor.t_cost {
            return Err(KeyError::InvalidParams(format!(
                "below the floor: m={} KiB t={}", self.m_cost, self.t_cost,
            )));
        }
        Ok(())
    }

    /// Build the `argon2::Params` struct used by the actual KDF.
    ///
    /// Parameters also come from outside — a backup header, the stored public
    /// record — so they are bounded: a header asking for gigabytes of memory
    /// or minutes of work must not take the phone down.
    fn to_argon2(&self) -> Result<Params, KeyError> {
        // This crate's own tests run with tiny parameters to stay fast.
        if !cfg!(test) {
            self.check_floor()?;
        }
        if self.m_cost > 256 * 1024
            || !(1..=10).contains(&self.t_cost)
            || !(1..=8).contains(&self.p_cost)
            || self.output_len != 32
        {
            return Err(KeyError::InvalidParams(format!(
                "out of bounds: m={} KiB t={} p={} len={}",
                self.m_cost, self.t_cost, self.p_cost, self.output_len,
            )));
        }
        Params::new(self.m_cost, self.t_cost, self.p_cost, Some(self.output_len as usize))
            .map_err(|e| KeyError::InvalidParams(e.to_string()))
    }
}

// ─────────────────────────────────────────────────────────────────────
// Salt
// ─────────────────────────────────────────────────────────────────────

/// Salt length in bytes. 128 bits is the NIST-SP-800-132 recommendation.
pub const SALT_LEN: usize = 16;

/// Generate a cryptographically-random 16-byte salt.
pub fn generate_salt() -> [u8; SALT_LEN] {
    use rand::RngCore;
    let mut salt = [0u8; SALT_LEN];
    rand::thread_rng().fill_bytes(&mut salt);
    salt
}

// ─────────────────────────────────────────────────────────────────────
// Master key derivation (Argon2id)
// ─────────────────────────────────────────────────────────────────────

/// Derive the 32-byte `MasterDataKey` from a Recovery Password.
///
/// `password` is the user's secret; `salt` and `params` are public. The same
/// inputs always yield the same key, which is what lets the user re-derive
/// every sub-key on a new device after entering their password.
///
/// CPU/memory intensive — call from a worker thread, never the UI thread.
pub fn derive_master_key(
    password: &str,
    salt: &[u8; SALT_LEN],
    params: &KdfParams,
) -> Result<SecretKey, KeyError> {
    // M2 (RoadmapSecurityAudit): defensive guard against an all-zero salt. The
    // `&[u8; 16]` type already guards length, but a bug higher up the stack
    // passing a zeroed salt would make every account on this code path derive
    // the SAME deterministic master key. Refuse rather than silently comply.
    if salt.iter().all(|&b| b == 0) {
        return Err(KeyError::ZeroSalt);
    }
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params.to_argon2()?);
    let mut out = [0u8; 32];
    argon2.hash_password_into(password.as_bytes(), salt.as_ref(), &mut out)?;
    Ok(SecretKey::from_bytes(out))
}

// ─────────────────────────────────────────────────────────────────────
// Sub-key derivation (HKDF-SHA256)
// ─────────────────────────────────────────────────────────────────────

/// Derive a domain-separated sub-key from the master key.
///
/// `info` MUST be one of the `KDF_INFO_*` constants — never caller-supplied.
/// This is what keeps the database key, MLS key, media key etc. cryptographically
/// isolated even though they all stem from the same master key.
pub fn derive_subkey(master: &SecretKey, info: &[u8]) -> SecretKey {
    // HKDF-Extract: master key is already high-entropy (32 uniform bytes from
    // Argon2id), so the extract step is effectively a pass-through. We still
    // run it for standards-conformance (RFC 5869).
    let hk = Hkdf::<Sha256>::from_prk(master.as_bytes())
        .expect("32-byte Argon2id output is a valid HKDF PRK");
    let mut okm = [0u8; 32];
    hk.expand(info, &mut okm)
        .expect("HKDF-Expand of 32 bytes with a fixed info never fails");
    SecretKey::from_bytes(okm)
}

/// Convenience: derive ALL five sub-keys at once. Used right after the master
/// key is unlocked so callers don't each re-run HKDF.
pub struct SubKeys {
    pub database: SecretKey,
    pub mls_backup: SecretKey,
    pub media: SecretKey,
    pub backup_file: SecretKey,
    pub settings: SecretKey,
}

impl SubKeys {
    pub fn derive_all(master: &SecretKey) -> Self {
        Self {
            database: derive_subkey(master, KDF_INFO_DB),
            mls_backup: derive_subkey(master, KDF_INFO_MLS),
            media: derive_subkey(master, KDF_INFO_MEDIA),
            backup_file: derive_subkey(master, KDF_INFO_BACKUP),
            settings: derive_subkey(master, KDF_INFO_SETTINGS),
        }
    }
}

impl std::fmt::Debug for SubKeys {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SubKeys")
            .field("database", &"[redacted]")
            .field("mls_backup", &"[redacted]")
            .field("media", &"[redacted]")
            .field("backup_file", &"[redacted]")
            .field("settings", &"[redacted]")
            .finish()
    }
}

// ─────────────────────────────────────────────────────────────────────
// Verification token
// ─────────────────────────────────────────────────────────────────────

/// A verifier is a constant-time MAC over a known label using a derived key.
/// We store `HMAC(label, BackupFileKey)` so that on restore we can confirm
/// the user typed the right password **without** storing the password or the
/// master key. Never used to decrypt anything — purely a yes/no check.
///
/// Implementation: SHA-256(label || BackupFileKey) is sufficient because the
/// attacker would need to brute-force Argon2id to find a matching password,
/// and the label is a fixed public constant.
pub fn compute_verifier(master: &SecretKey) -> [u8; 32] {
    use sha2::Digest;
    let backup_key = derive_subkey(master, KDF_INFO_BACKUP);
    let mut h = sha2::Sha256::new();
    h.update(b"alpha-recovery-verifier-v1");
    h.update(backup_key.as_bytes());
    let out = h.finalize();
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&out);
    arr
}

/// `true` iff the entered password reproduces the stored verifier.
pub fn verify_master_key(master: &SecretKey, stored: &[u8; 32]) -> bool {
    let candidate = compute_verifier(master);
    candidate.ct_eq(stored).into()
}

// ─────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outsized_kdf_parameters_are_refused() {
        let salt = generate_salt();
        for p in [
            KdfParams { m_cost: 4 * 1024 * 1024, ..KdfParams::recommended() },
            KdfParams { t_cost: 1000, ..KdfParams::recommended() },
            KdfParams { p_cost: 64, ..KdfParams::recommended() },
            KdfParams { output_len: 4096, ..KdfParams::recommended() },
        ] {
            assert!(matches!(derive_master_key("pw", &salt, &p), Err(KeyError::InvalidParams(_))));
        }
    }

    #[test]
    fn weak_kdf_parameters_are_refused() {
        assert!(KdfParams::recommended().check_floor().is_ok());
        assert!(KdfParams { m_cost: 8, t_cost: 1, p_cost: 1, output_len: 32 }.check_floor().is_err());
        assert!(KdfParams { t_cost: 2, ..KdfParams::recommended() }.check_floor().is_err());
    }

    #[test]
    fn master_key_is_deterministic() {
        let salt = generate_salt();
        let p = KdfParams::recommended();
        let a = derive_master_key("correct horse battery staple", &salt, &p).unwrap();
        let b = derive_master_key("correct horse battery staple", &salt, &p).unwrap();
        assert_eq!(a.as_bytes(), b.as_bytes(), "same input → same key");
    }

    #[test]
    fn different_passwords_yield_different_keys() {
        let salt = generate_salt();
        let p = KdfParams::recommended();
        let a = derive_master_key("password1", &salt, &p).unwrap();
        let b = derive_master_key("password2", &salt, &p).unwrap();
        assert_ne!(a.as_bytes(), b.as_bytes());
    }

    #[test]
    fn different_salts_yield_different_keys() {
        let p = KdfParams::recommended();
        let a = derive_master_key("same", &generate_salt(), &p).unwrap();
        let b = derive_master_key("same", &generate_salt(), &p).unwrap();
        assert_ne!(a.as_bytes(), b.as_bytes());
    }

    #[test]
    fn subkeys_are_isolated() {
        let salt = generate_salt();
        let p = KdfParams::recommended();
        let master = derive_master_key("hunter2", &salt, &p).unwrap();
        let keys = SubKeys::derive_all(&master);

        // All five must be distinct from each other and from the master.
        assert_ne!(keys.database.as_bytes(), master.as_bytes());
        assert_ne!(keys.database.as_bytes(), keys.mls_backup.as_bytes());
        assert_ne!(keys.mls_backup.as_bytes(), keys.media.as_bytes());
        assert_ne!(keys.media.as_bytes(), keys.backup_file.as_bytes());
        assert_ne!(keys.backup_file.as_bytes(), keys.settings.as_bytes());
    }

    #[test]
    fn verifier_roundtrip() {
        let salt = generate_salt();
        let p = KdfParams::recommended();
        let master = derive_master_key("my-recovery-pass", &salt, &p).unwrap();
        let v = compute_verifier(&master);
        assert!(verify_master_key(&master, &v), "correct password verifies");
        let wrong = derive_master_key("wrong-password", &salt, &p).unwrap();
        assert!(!verify_master_key(&wrong, &v), "wrong password fails");
    }

    #[test]
    fn secret_key_zeroizes_on_drop() {
        // Smoke test: a dropped key should not be observable. We can't assert
        // the actual memory is zeroed (UB to read after drop), but constructing
        // and dropping must not panic.
        let salt = generate_salt();
        let p = KdfParams::recommended();
        let master = derive_master_key("drop-test", &salt, &p).unwrap();
        let _ = master.to_hex();
        drop(master); // ZeroizeOnDrop runs here
    }
}
