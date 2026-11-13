//! AEAD backup container for the local `backup.enc` file (Phase 2).
//!
//! ## Format (versioned, all fields little-endian where applicable)
//!
//! ```text
//! offset  size  field
//! 0       8     magic = b"ALPHBK1\0"
//! 8       2     version   (u16, currently 2)
//! 10      2     reserved  (u16, zero — future flags)
//! 12      20    argon2 params packed: m_cost(4) t_cost(4) p_cost(4) out_len(4) + pad(4)
//! 32      16    salt (random, public)
//! 48      24    nonce (random — XChaCha20 supports random nonces safely)
//! 72      ..    ciphertext (plaintext || AEAD tag, 16 bytes appended)
//! ```
//!
//! The salt + argon2 params are stored in cleartext: they're needed to
//! re-derive the key from the user's password, and on their own they reveal
//! nothing. The ciphertext is XChaCha20-Poly1305 over the plaintext payload
//! (a SQLite dump), so any tampering trips the Poly1305 tag. From version 2 the
//! whole header is the AEAD's associated data: it cannot be altered either.
//!
//! ## Why XChaCha20-Poly1305
//!
//!  - 24-byte nonces ⇒ random nonces are collision-safe for any realistic
//!    number of backups (no per-message counter bookkeeping needed).
//!  - Constant-time, hardware-independent, fast on ARM (no AES-NI assumption).
//!  - Same crate family (`chacha20poly1305`) already used for media encryption.

use crate::{derive_master_key, derive_subkey, KdfParams, SALT_LEN, KDF_INFO_BACKUP};

/// Magic bytes identifying an AlphaMessenger backup container.
pub const MAGIC: &[u8; 8] = b"ALPHBK1\0";

/// Current on-disk container version. Bump when the header layout changes;
/// the loader rejects anything newer than it understands.
pub const CONTAINER_VERSION: u16 = 2;

/// The first version whose header is authenticated with the payload.
const AAD_SINCE_VERSION: u16 = 2;

/// XChaCha20 nonce length (bytes).
const NONCE_LEN: usize = 24;

/// Poly1305 authentication tag length (bytes), appended to ciphertext.
const TAG_LEN: usize = 16;

/// Packed Argon2 params block length in the header.
const PARAMS_BLOCK_LEN: usize = 20;

/// Total fixed header length in front of the ciphertext.
pub const HEADER_LEN: usize = 8 + 2 + 2 + PARAMS_BLOCK_LEN + SALT_LEN + NONCE_LEN; // = 72

#[derive(Debug, thiserror::Error)]
pub enum BackupError {
    #[error("not a backup file (bad magic)")]
    BadMagic,
    #[error("unsupported backup version {0} (this build supports up to {1})")]
    UnsupportedVersion(u16, u16),
    #[error("backup header is truncated: need {needed}, have {have}")]
    TruncatedHeader { needed: usize, have: usize },
    #[error("decryption failed — wrong password or corrupted file")]
    DecryptFailed,
    #[error("encryption failed: {0}")]
    EncryptFailed(String),
    #[error("invalid parameters: {0}")]
    InvalidParams(String),
}

// ─────────────────────────────────────────────────────────────────────
// Header (de)serialization
// ─────────────────────────────────────────────────────────────────────

/// A parsed, in-memory header. The ciphertext follows immediately after it
/// in the byte stream.
#[derive(Debug, Clone)]
pub struct BackupHeader {
    pub version: u16,
    pub reserved: u16,
    pub params: KdfParams,
    pub salt: [u8; SALT_LEN],
    pub nonce: [u8; NONCE_LEN],
}

impl BackupHeader {
    /// Serialize the fixed header to exactly [`HEADER_LEN`] bytes.
    pub fn to_bytes(&self) -> [u8; HEADER_LEN] {
        let mut out = [0u8; HEADER_LEN];
        out[0..8].copy_from_slice(MAGIC);
        out[8..10].copy_from_slice(&self.version.to_le_bytes());
        out[10..12].copy_from_slice(&self.reserved.to_le_bytes());
        // Packed params: m, t, p, out_len (u32 each) + 4 bytes pad (reserved).
        out[12..16].copy_from_slice(&self.params.m_cost.to_le_bytes());
        out[16..20].copy_from_slice(&self.params.t_cost.to_le_bytes());
        out[20..24].copy_from_slice(&self.params.p_cost.to_le_bytes());
        out[24..28].copy_from_slice(&self.params.output_len.to_le_bytes());
        // 28..32 reserved pad (already zero)
        out[32..48].copy_from_slice(&self.salt);
        out[48..72].copy_from_slice(&self.nonce);
        out
    }

    /// Parse a header from the front of `bytes`. Returns the header and the
    /// ciphertext slice (everything after the header).
    pub fn from_bytes(bytes: &[u8]) -> Result<(BackupHeader, &[u8]), BackupError> {
        if bytes.len() < HEADER_LEN {
            return Err(BackupError::TruncatedHeader {
                needed: HEADER_LEN,
                have: bytes.len(),
            });
        }
        if &bytes[0..8] != MAGIC {
            return Err(BackupError::BadMagic);
        }
        let version = u16::from_le_bytes([bytes[8], bytes[9]]);
        if version == 0 || version > CONTAINER_VERSION {
            return Err(BackupError::UnsupportedVersion(version, CONTAINER_VERSION));
        }
        let reserved = u16::from_le_bytes([bytes[10], bytes[11]]);
        if reserved != 0 {
            return Err(BackupError::InvalidParams(format!("reserved header field {reserved}")));
        }
        let params = KdfParams {
            m_cost: u32::from_le_bytes(bytes[12..16].try_into().unwrap()),
            t_cost: u32::from_le_bytes(bytes[16..20].try_into().unwrap()),
            p_cost: u32::from_le_bytes(bytes[20..24].try_into().unwrap()),
            output_len: u32::from_le_bytes(bytes[24..28].try_into().unwrap()),
        };
        let mut salt = [0u8; SALT_LEN];
        salt.copy_from_slice(&bytes[32..48]);
        let mut nonce = [0u8; NONCE_LEN];
        nonce.copy_from_slice(&bytes[48..72]);

        let header = BackupHeader { version, reserved, params, salt, nonce };
        Ok((header, &bytes[HEADER_LEN..]))
    }
}

// ─────────────────────────────────────────────────────────────────────
// Encrypt / decrypt
// ─────────────────────────────────────────────────────────────────────

/// Encrypt `plaintext` (the SQLCipher dump) into a self-contained backup
/// container protected by the user's Recovery Password.
///
/// Generates a fresh salt + nonce, derives `MasterDataKey → BackupFileKey`
/// and seals the payload with XChaCha20-Poly1305. Returns
/// `header || ciphertext`.
///
/// CPU-intensive (Argon2id) — call off the UI thread.
pub fn encrypt_backup(
    password: &str,
    plaintext: &[u8],
    params: &KdfParams,
) -> Result<Vec<u8>, BackupError> {
    let salt = crate::generate_salt();
    let key = derive_backup_key(password, &salt, params)?;
    encrypt_backup_with_key(&key, &salt, params, plaintext)
}

/// The BackupFileKey for (password, salt, params) — the expensive Argon2 step.
/// Kept by the caller so scheduled backups need no password.
pub fn derive_backup_key(
    password: &str,
    salt: &[u8; SALT_LEN],
    params: &KdfParams,
) -> Result<[u8; 32], BackupError> {
    let master = derive_master_key(password, salt, params)
        .map_err(|e| BackupError::InvalidParams(e.to_string()))?;
    Ok(*derive_subkey(&master, KDF_INFO_BACKUP).as_bytes())
}

/// Seal with an already derived key. The container is identical in format to
/// [`encrypt_backup`] (same header, the salt the key was derived with), so
/// [`decrypt_backup`] opens it with the password as usual.
pub fn encrypt_backup_with_key(
    key: &[u8; 32],
    salt: &[u8; SALT_LEN],
    params: &KdfParams,
    plaintext: &[u8],
) -> Result<Vec<u8>, BackupError> {
    use chacha20poly1305::{KeyInit, XChaCha20Poly1305, XNonce, aead::{Aead, Payload}};
    use rand::RngCore;

    let salt = *salt;
    let backup_key = crate::SecretKey::from_bytes(*key);

    let mut nonce = [0u8; NONCE_LEN];
    rand::thread_rng().fill_bytes(&mut nonce);

    let header = BackupHeader {
        version: CONTAINER_VERSION,
        reserved: 0,
        params: params.clone(),
        salt,
        nonce,
    }
    .to_bytes();

    let cipher = XChaCha20Poly1305::new_from_slice(backup_key.as_bytes())
        .map_err(|e| BackupError::EncryptFailed(e.to_string()))?;
    let ciphertext = cipher
        .encrypt(XNonce::from_slice(&nonce), Payload { msg: plaintext, aad: &header })
        .map_err(|e| BackupError::EncryptFailed(e.to_string()))?;

    let mut out = Vec::with_capacity(HEADER_LEN + ciphertext.len());
    out.extend_from_slice(&header);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Decrypt a backup container produced by [`encrypt_backup`].
///
/// Reads the header, re-derives `MasterDataKey → BackupFileKey` from the
/// password + stored salt/params, and opens the AEAD. On a wrong password
/// the Poly1305 tag check fails and you get [`BackupError::DecryptFailed`]
/// — no data is mutated, safe to retry.
pub fn decrypt_backup(password: &str, container: &[u8]) -> Result<Vec<u8>, BackupError> {
    use chacha20poly1305::{KeyInit, XChaCha20Poly1305, XNonce, aead::{Aead, Payload}};

    let (header, ciphertext) = BackupHeader::from_bytes(container)?;
    if ciphertext.len() < TAG_LEN {
        return Err(BackupError::TruncatedHeader {
            needed: HEADER_LEN + TAG_LEN,
            have: container.len(),
        });
    }

    let master = derive_master_key(password, &header.salt, &header.params)
        .map_err(|e| BackupError::InvalidParams(e.to_string()))?;
    let backup_key = derive_subkey(&master, KDF_INFO_BACKUP);

    let cipher = XChaCha20Poly1305::new_from_slice(backup_key.as_bytes())
        .map_err(|e| BackupError::EncryptFailed(e.to_string()))?;
    // Version 1 files predate the authenticated header; a v2 file relabelled as
    // v1 fails here too, since its tag was computed with the header.
    let aad: &[u8] = if header.version >= AAD_SINCE_VERSION { &container[..HEADER_LEN] } else { &[] };
    cipher
        .decrypt(XNonce::from_slice(&header.nonce), Payload { msg: ciphertext, aad })
        .map_err(|_| BackupError::DecryptFailed)
}

/// Peek at the KDF parameters of a container WITHOUT deriving any key.
/// Lets the UI show "this backup was created with params X" before asking
/// the user for a password. Header-only read; never touches secrets.
pub fn peek_params(container: &[u8]) -> Result<KdfParams, BackupError> {
    let (header, _) = BackupHeader::from_bytes(container)?;
    Ok(header.params)
}

// ─────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn fast_params() -> KdfParams {
        // Tiny params so tests run in milliseconds, not seconds.
        KdfParams { m_cost: 8, t_cost: 1, p_cost: 1, output_len: 32 }
    }

    #[test]
    fn roundtrip_encrypt_decrypt() {
        let p = fast_params();
        let plain = b"the quick brown fox jumps over the lazy dog";
        let enc = encrypt_backup("hunter2", plain, &p).unwrap();
        let dec = decrypt_backup("hunter2", &enc).unwrap();
        assert_eq!(dec, plain);
    }

    #[test]
    fn wrong_password_fails() {
        let p = fast_params();
        let enc = encrypt_backup("correct", b"secret data", &p).unwrap();
        assert!(matches!(
            decrypt_backup("wrong", &enc),
            Err(BackupError::DecryptFailed)
        ));
    }

    #[test]
    fn tampered_ciphertext_fails() {
        let p = fast_params();
        let mut enc = encrypt_backup("pw", b"payload", &p).unwrap();
        // Flip a byte inside the ciphertext region.
        let last = enc.len() - 1;
        enc[last] ^= 0xff;
        assert!(matches!(
            decrypt_backup("pw", &enc),
            Err(BackupError::DecryptFailed)
        ));
    }

    #[test]
    fn header_roundtrip() {
        let p = KdfParams::recommended();
        let salt = crate::generate_salt();
        let mut nonce = [0u8; NONCE_LEN];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut nonce);
        let h = BackupHeader {
            version: CONTAINER_VERSION,
            reserved: 0,
            params: p.clone(),
            salt,
            nonce,
        };
        let bytes = h.to_bytes();
        assert_eq!(bytes.len(), HEADER_LEN);
        let (h2, rest) = BackupHeader::from_bytes(&bytes).unwrap();
        assert!(rest.is_empty());
        assert_eq!(h2.version, h.version);
        assert_eq!(h2.params, h.params);
        assert_eq!(h2.salt, h.salt);
        assert_eq!(h2.nonce, h.nonce);
    }

    #[test]
    fn bad_magic_rejected() {
        let mut bytes = vec![0u8; HEADER_LEN];
        assert!(matches!(
            BackupHeader::from_bytes(&bytes),
            Err(BackupError::BadMagic)
        ));
        // Right magic but version 0: no container was ever written so.
        bytes[0..8].copy_from_slice(MAGIC);
        assert!(matches!(BackupHeader::from_bytes(&bytes), Err(BackupError::UnsupportedVersion(0, _))));
        bytes[8] = 1;
        bytes[10] = 1; // reserved must be zero
        assert!(BackupHeader::from_bytes(&bytes).is_err());
        bytes[10] = 0;
        assert!(BackupHeader::from_bytes(&bytes).is_ok());
    }

    #[test]
    fn truncated_header_rejected() {
        let short = vec![0u8; 10];
        assert!(matches!(
            BackupHeader::from_bytes(&short),
            Err(BackupError::TruncatedHeader { .. })
        ));
    }

    #[test]
    fn future_version_rejected() {
        let p = fast_params();
        let enc = encrypt_backup("pw", b"x", &p).unwrap();
        // Bump the version byte in place.
        let mut tampered = enc.clone();
        tampered[8] = 0xff; // version = 255
        assert!(matches!(
            decrypt_backup("pw", &tampered),
            Err(BackupError::UnsupportedVersion(255, CONTAINER_VERSION))
        ));
    }

    #[test]
    fn a_tampered_header_fails() {
        let p = fast_params();
        let mut enc = encrypt_backup("pw", b"payload", &p).unwrap();
        enc[30] ^= 1; // the reserved pad: changes no derivation, only the header
        assert!(matches!(decrypt_backup("pw", &enc), Err(BackupError::DecryptFailed)));
        let mut relabelled = encrypt_backup("pw", b"payload", &p).unwrap();
        relabelled[8] = 1; // pass a v2 file off as v1, without the header check
        assert!(matches!(decrypt_backup("pw", &relabelled), Err(BackupError::DecryptFailed)));
    }

    #[test]
    fn a_version_1_file_still_opens() {
        use chacha20poly1305::{KeyInit, XChaCha20Poly1305, XNonce, aead::Aead};
        let p = fast_params();
        let salt = crate::generate_salt();
        let key = derive_backup_key("pw", &salt, &p).unwrap();
        let nonce = [5u8; NONCE_LEN];
        let ct = XChaCha20Poly1305::new_from_slice(&key).unwrap()
            .encrypt(XNonce::from_slice(&nonce), b"old".as_slice()).unwrap();
        let header = BackupHeader { version: 1, reserved: 0, params: p, salt, nonce };
        let file = [header.to_bytes().as_slice(), &ct].concat();
        assert_eq!(decrypt_backup("pw", &file).unwrap(), b"old");
    }

    #[test]
    fn peek_params_does_not_need_password() {
        let p = fast_params();
        let enc = encrypt_backup("pw", b"x", &p).unwrap();
        let peeked = peek_params(&enc).unwrap();
        assert_eq!(peeked, p);
    }
}
