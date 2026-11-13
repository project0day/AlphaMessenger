//! Encrypted on-disk storage for all private keys.
//!
//! The `KeyVault` provides AES-256-GCM encryption of the serialized
//! `DualIdentity` with Argon2id key derivation from a user-supplied passphrase.
//!
//! # File Format
//!
//! ```text
//! [16-byte salt][12-byte nonce][ciphertext][16-byte authentication tag]
//! ```
//!
//! The ciphertext is the AES-256-GCM encrypted JSON serialization of
//! `SerializableDualIdentity`. The authentication tag is appended by the
//! AES-GCM library and verified on decryption.
//!
//! # Security Properties
//!
//! - **KDF**: Argon2id (memory-hard, resistant to GPU/ASIC attacks)
//! - **Salt**: 16 bytes, generated via CSPRNG (`OsRng`)
//! - **Nonce**: 12 bytes, generated via CSPRNG (`OsRng`)
//! - **Zeroization**: All secret material (derived keys, plaintext JSON,
//!   serialized identity) is zeroized on drop using the `zeroize` crate.

use std::path::Path;

use aes_gcm::aead::{Aead, KeyInit, OsRng};
use aes_gcm::{Aes256Gcm, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use rand::RngCore;
use zeroize::{Zeroize, Zeroizing};

use crate::dual_identity::{DualIdentity, SerializableDualIdentity};
use crate::error::KeyVaultError;

/// Salt length for Argon2id (16 bytes — exceeds Argon2 minimum of 8 bytes).
const ARGON2_SALT_LEN: usize = 16;

/// Nonce length for AES-256-GCM (12 bytes).
const NONCE_LEN: usize = 12;

/// Tag length for AES-256-GCM (16 bytes).
const TAG_LEN: usize = 16;

/// Derived key length (AES-256 = 32 bytes).
const KEY_LEN: usize = 32;

/// Minimum vault file size: salt + nonce + tag (ciphertext can be empty in theory).
const MIN_VAULT_SIZE: usize = ARGON2_SALT_LEN + NONCE_LEN + TAG_LEN;

/// Encrypted key vault for persisting the dual identity.
///
/// The vault stores all private keys (both Nostr and MLS) in an encrypted
/// file protected by a user passphrase.
///
/// # Zeroization
///
/// The `encrypted` field is zeroized on drop to prevent lingering ciphertext
/// in memory (which could be used as an oracle for known-plaintext attacks
/// on the key derivation).
pub struct KeyVault {
    /// The encrypted vault bytes (the complete file contents).
    /// Zeroized on drop to prevent memory forensics.
    encrypted: Vec<u8>,
}

impl Drop for KeyVault {
    fn drop(&mut self) {
        self.encrypted.zeroize();
    }
}

impl std::fmt::Debug for KeyVault {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("KeyVault")
            .field("encrypted", &format!("[{} bytes redacted]", self.encrypted.len()))
            .finish()
    }
}

impl KeyVault {
    /// Create a new key vault, encrypting the given identity with the passphrase.
    ///
    /// This serializes the identity to JSON, encrypts it with AES-256-GCM
    /// using a key derived from the passphrase via Argon2id, and
    /// stores the result.
    ///
    /// # Arguments
    /// * `passphrase` - The user-supplied passphrase (will be zeroized internally where possible)
    /// * `identity` - The dual identity to encrypt and store
    ///
    /// # Returns
    /// The `KeyVault` containing the encrypted data, which can then be
    /// written to disk with [`KeyVault::save`].
    pub fn create(passphrase: &str, identity: &DualIdentity) -> Result<Self, KeyVaultError> {
        // Serialize the identity to JSON — wrap in Zeroizing to zeroize on drop
        let serializable = identity.to_serializable();
        let json_bytes = Zeroizing::new(
            serde_json::to_vec(&serializable)
                .map_err(|e| KeyVaultError::SerializationError(e.to_string()))?,
        );

        // Encrypt the JSON bytes
        let encrypted = encrypt_with_passphrase(passphrase, &json_bytes)?;

        Ok(Self { encrypted })
    }

    /// Unlock the vault by decrypting and restoring the dual identity.
    ///
    /// Reads the vault file from disk, decrypts it using the passphrase,
    /// and deserializes the `DualIdentity`.
    ///
    /// # Arguments
    /// * `passphrase` - The user-supplied passphrase
    /// * `vault_path` - Path to the vault file on disk
    ///
    /// # Errors
    /// Returns `KeyVaultError::InvalidPassphrase` if the passphrase is wrong
    /// (detected via AES-GCM authentication tag mismatch).
    pub fn unlock(passphrase: &str, vault_path: &Path) -> Result<DualIdentity, KeyVaultError> {
        let encrypted = std::fs::read(vault_path)?;

        // Decrypt the vault data — result is zeroized after use
        let json_bytes = Zeroizing::new(decrypt_with_passphrase(passphrase, &encrypted)?);

        // Deserialize the identity
        let serializable: SerializableDualIdentity = serde_json::from_slice(&json_bytes)
            .map_err(|e| KeyVaultError::DeserializationError(e.to_string()))?;

        let result = DualIdentity::from_serializable(&serializable)
            .map_err(|e| KeyVaultError::DeserializationError(e.to_string()));

        // SerializableDualIdentity contains secret key material — zeroize it
        // (its Drop impl handles this via zeroize)
        drop(serializable);

        result
    }

    /// Change the passphrase of an existing vault file.
    ///
    /// This decrypts the vault with the old passphrase, re-encrypts with
    /// the new passphrase, and writes the result back to the same file.
    ///
    /// # Arguments
    /// * `old` - The current passphrase
    /// * `new` - The new passphrase
    /// * `vault_path` - Path to the vault file on disk
    pub fn change_passphrase(
        old: &str,
        new: &str,
        vault_path: &Path,
    ) -> Result<(), KeyVaultError> {
        // Read and decrypt with old passphrase — Zeroizing ensures zeroize on drop
        let encrypted = std::fs::read(vault_path)?;
        let mut json_bytes = Zeroizing::new(decrypt_with_passphrase(old, &encrypted)?);

        // Deserialize to verify integrity
        let mut serializable: SerializableDualIdentity = serde_json::from_slice(&json_bytes)
            .map_err(|e| KeyVaultError::DeserializationError(e.to_string()))?;

        // Re-encrypt with new passphrase
        let new_encrypted = encrypt_with_passphrase(new, &json_bytes)?;

        // Write back to disk
        std::fs::write(vault_path, &new_encrypted)?;

        // Explicit zeroization of intermediate secret data
        // (Zeroizing wrapper handles json_bytes, SerializableDualIdentity's Drop handles serializable)
        json_bytes.zeroize();
        serializable.zeroize();

        Ok(())
    }

    /// Save the vault to a file on disk.
    pub fn save(&self, path: &Path) -> Result<(), KeyVaultError> {
        std::fs::write(path, &self.encrypted)?;
        Ok(())
    }

    /// Get the raw encrypted bytes (for alternative storage).
    pub fn encrypted_bytes(&self) -> &[u8] {
        &self.encrypted
    }

    /// Create a KeyVault from raw encrypted bytes (e.g., loaded from alternative storage).
    pub fn from_encrypted_bytes(bytes: Vec<u8>) -> Result<Self, KeyVaultError> {
        if bytes.len() < MIN_VAULT_SIZE {
            return Err(KeyVaultError::CorruptedVault(format!(
                "vault data too short: {} bytes (minimum {})",
                bytes.len(),
                MIN_VAULT_SIZE
            )));
        }
        Ok(Self { encrypted: bytes })
    }

    /// Unlock directly from encrypted bytes (no file I/O).
    pub fn unlock_from_bytes(passphrase: &str, encrypted: &[u8]) -> Result<DualIdentity, KeyVaultError> {
        let json_bytes = Zeroizing::new(decrypt_with_passphrase(passphrase, encrypted)?);

        let serializable: SerializableDualIdentity = serde_json::from_slice(&json_bytes)
            .map_err(|e| KeyVaultError::DeserializationError(e.to_string()))?;

        let result = DualIdentity::from_serializable(&serializable)
            .map_err(|e| KeyVaultError::DeserializationError(e.to_string()));

        drop(serializable);

        result
    }
}

// ---------------------------------------------------------------------------
// Internal crypto helpers
// ---------------------------------------------------------------------------

/// Derive a 256-bit encryption key from a passphrase and salt using Argon2id.
///
/// Argon2id is the recommended KDF for password-based key derivation,
/// providing resistance against GPU and ASIC attacks via memory-hard
/// computation.
///
/// Parameters: m=65536 KiB (64 MiB), t=3, p=4, output=32 bytes. These match
/// the hardened production parameters used by `alpha-keys` (`KdfParams::recommended`)
/// so that the master Nostr/MLS identity — the single most valuable offline-attack
/// target — is protected at least as strongly as the database key, not weaker.
/// (Previously this used `Params::default()` = m=19456/t=2/p=1, the entry-level
/// example from the argon2 docs — see RoadmapSecurityAudit C3.)
fn derive_key(passphrase: &str, salt: &[u8]) -> Result<[u8; KEY_LEN], KeyVaultError> {
    let params = Params::new(65_536, 3, 4, Some(KEY_LEN))
        .map_err(|e| KeyVaultError::KeyDerivationFailed(e.to_string()))?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = [0u8; KEY_LEN];
    argon2
        .hash_password_into(passphrase.as_bytes(), salt, &mut key)
        .map_err(|e| KeyVaultError::KeyDerivationFailed(e.to_string()))?;
    Ok(key)
}

/// Encrypt plaintext using AES-256-GCM with a passphrase-derived key.
///
/// Returns: `[salt (16 bytes)][nonce (12 bytes)][ciphertext + tag]`
fn encrypt_with_passphrase(passphrase: &str, plaintext: &[u8]) -> Result<Vec<u8>, KeyVaultError> {
    // Generate random salt (CSPRNG — OsRng uses OS entropy source)
    let mut salt = [0u8; ARGON2_SALT_LEN];
    OsRng.fill_bytes(&mut salt);

    // Generate random nonce (CSPRNG — OsRng uses OS entropy source)
    let mut nonce_bytes = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce_bytes);

    // Derive encryption key using Argon2id — zeroize after use
    let mut key = derive_key(passphrase, &salt)?;

    // Encrypt with AES-256-GCM
    let cipher = Aes256Gcm::new_from_slice(&key)
        .map_err(|e| KeyVaultError::EncryptionFailed(e.to_string()))?;
    let nonce = Nonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(nonce, plaintext)
        .map_err(|e| KeyVaultError::EncryptionFailed(e.to_string()))?;

    // Zeroize the derived key — it's no longer needed
    key.zeroize();

    // Assemble: salt + nonce + (ciphertext || tag)
    // Note: aes-gcm appends the 16-byte tag to the ciphertext
    let mut output = Vec::with_capacity(ARGON2_SALT_LEN + NONCE_LEN + ciphertext.len());
    output.extend_from_slice(&salt);
    output.extend_from_slice(&nonce_bytes);
    output.extend_from_slice(&ciphertext);

    Ok(output)
}

/// Decrypt vault data using AES-256-GCM with a passphrase-derived key.
///
/// Expected format: `[salt (16 bytes)][nonce (12 bytes)][ciphertext + tag]`
fn decrypt_with_passphrase(passphrase: &str, data: &[u8]) -> Result<Vec<u8>, KeyVaultError> {
    // Validate minimum size
    if data.len() < MIN_VAULT_SIZE {
        return Err(KeyVaultError::CorruptedVault(format!(
            "vault data too short: {} bytes (minimum {})",
            data.len(),
            MIN_VAULT_SIZE
        )));
    }

    // Split the data
    let salt = &data[..ARGON2_SALT_LEN];
    let nonce_bytes = &data[ARGON2_SALT_LEN..ARGON2_SALT_LEN + NONCE_LEN];
    let ciphertext_and_tag = &data[ARGON2_SALT_LEN + NONCE_LEN..];

    // Derive decryption key using Argon2id — zeroize after use
    let mut key = derive_key(passphrase, salt)?;

    // Decrypt with AES-256-GCM
    let cipher = Aes256Gcm::new_from_slice(&key)
        .map_err(|e| KeyVaultError::DecryptionFailed(e.to_string()))?;
    let nonce = Nonce::from_slice(nonce_bytes);

    let result = cipher
        .decrypt(nonce, ciphertext_and_tag)
        .map_err(|_| KeyVaultError::InvalidPassphrase);

    // Zeroize the derived key — it's no longer needed
    key.zeroize();

    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vault_create_and_unlock() {
        let identity = DualIdentity::generate().expect("generation should succeed");
        let passphrase = "test-passphrase-123";

        let vault = KeyVault::create(passphrase, &identity).expect("vault creation should succeed");

        // Save to a temp path
        let dir = std::env::temp_dir().join("alpha-identity-test-vault");
        std::fs::create_dir_all(&dir).ok();
        let path = dir.join("test.vault");
        vault.save(&path).expect("save should succeed");

        // Unlock
        let restored = KeyVault::unlock(passphrase, &path).expect("unlock should succeed");

        assert_eq!(
            identity.nostr().public_key_hex(),
            restored.nostr().public_key_hex()
        );
        assert_eq!(
            identity.mls().identity_bytes(),
            restored.mls().identity_bytes()
        );

        // Cleanup
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn test_vault_wrong_passphrase() {
        let identity = DualIdentity::generate().expect("generation should succeed");

        let vault = KeyVault::create("correct-passphrase", &identity).expect("vault creation should succeed");

        let dir = std::env::temp_dir().join("alpha-identity-test-vault-wrong");
        std::fs::create_dir_all(&dir).ok();
        let path = dir.join("test.vault");
        vault.save(&path).expect("save should succeed");

        let result = KeyVault::unlock("wrong-passphrase", &path);
        assert!(result.is_err(), "wrong passphrase should fail");
        match result.unwrap_err() {
            KeyVaultError::InvalidPassphrase => {} // expected
            other => panic!("expected InvalidPassphrase, got: {:?}", other),
        }

        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn test_vault_change_passphrase() {
        let identity = DualIdentity::generate().expect("generation should succeed");

        let vault = KeyVault::create("old-passphrase", &identity).expect("vault creation should succeed");

        let dir = std::env::temp_dir().join("alpha-identity-test-vault-change");
        std::fs::create_dir_all(&dir).ok();
        let path = dir.join("test.vault");
        vault.save(&path).expect("save should succeed");

        // Change passphrase
        KeyVault::change_passphrase("old-passphrase", "new-passphrase", &path)
            .expect("change passphrase should succeed");

        // Old passphrase should no longer work
        let old_result = KeyVault::unlock("old-passphrase", &path);
        assert!(old_result.is_err(), "old passphrase should fail after change");

        // New passphrase should work
        let restored = KeyVault::unlock("new-passphrase", &path)
            .expect("new passphrase should work");
        assert_eq!(
            identity.nostr().public_key_hex(),
            restored.nostr().public_key_hex()
        );

        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn test_vault_from_bytes() {
        let identity = DualIdentity::generate().expect("generation should succeed");
        let passphrase = "bytes-test-passphrase";

        let vault = KeyVault::create(passphrase, &identity).expect("vault creation should succeed");
        let bytes = vault.encrypted_bytes().to_vec();

        let restored = KeyVault::unlock_from_bytes(passphrase, &bytes)
            .expect("unlock from bytes should succeed");

        assert_eq!(
            identity.nostr().public_key_hex(),
            restored.nostr().public_key_hex()
        );
    }
}
