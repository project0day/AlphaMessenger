//! Comprehensive error types for all identity operations.

use thiserror::Error;

/// Errors that can occur during identity operations.
#[derive(Error, Debug)]
pub enum IdentityError {
    /// Failed to generate a Nostr keypair.
    #[error("failed to generate Nostr keypair: {0}")]
    NostrKeyGeneration(String),

    /// Failed to generate an MLS keypair.
    #[error("failed to generate MLS keypair: {0}")]
    MlsKeyGeneration(String),

    /// Failed to serialize identity data.
    #[error("serialization error: {0}")]
    Serialization(String),

    /// Failed to deserialize identity data.
    #[error("deserialization error: {0}")]
    Deserialization(String),

    /// Invalid key material.
    #[error("invalid key material: {0}")]
    InvalidKeyMaterial(String),

    /// Signing operation failed.
    #[error("signing failed: {0}")]
    SigningFailed(String),

    /// Invalid passphrase provided.
    #[error("invalid passphrase")]
    InvalidPassphrase,

    /// Identity not found.
    #[error("identity not found: {0}")]
    NotFound(String),
}

/// Errors specific to key vault operations.
#[derive(Error, Debug)]
pub enum KeyVaultError {
    /// Failed to encrypt the vault data.
    #[error("encryption failed: {0}")]
    EncryptionFailed(String),

    /// Failed to decrypt the vault data.
    #[error("decryption failed: {0}")]
    DecryptionFailed(String),

    /// Key derivation failed.
    #[error("key derivation failed: {0}")]
    KeyDerivationFailed(String),

    /// Vault file I/O error.
    #[error("I/O error: {0}")]
    IoError(#[from] std::io::Error),

    /// Serialization error within the vault.
    #[error("serialization error: {0}")]
    SerializationError(String),

    /// Deserialization error within the vault.
    #[error("deserialization error: {0}")]
    DeserializationError(String),

    /// Invalid passphrase for vault unlock.
    #[error("invalid passphrase: decryption integrity check failed")]
    InvalidPassphrase,

    /// Corrupted vault file (unexpected format or size).
    #[error("corrupted vault file: {0}")]
    CorruptedVault(String),

    /// Invalid old passphrase when changing passphrase.
    #[error("invalid old passphrase")]
    OldPassphraseMismatch,
}

/// Errors specific to Nostr identity operations.
#[derive(Error, Debug)]
pub enum NostrIdentityError {
    /// Failed to generate Nostr keys.
    #[error("key generation failed: {0}")]
    KeyGeneration(String),

    /// Failed to encode public key to npub.
    #[error("npub encoding failed: {0}")]
    NpubEncoding(String),

    /// Failed to sign event.
    #[error("event signing failed: {0}")]
    SigningFailed(String),

    /// Invalid secret key format.
    #[error("invalid secret key format: {0}")]
    InvalidSecretKey(String),
}

/// Errors specific to MLS identity operations.
#[derive(Error, Debug)]
pub enum MlsIdentityError {
    /// Failed to generate MLS keys.
    #[error("key generation failed: {0}")]
    KeyGeneration(String),

    /// Failed to create MLS credential.
    #[error("credential creation failed: {0}")]
    CredentialCreation(String),

    /// Serialization error.
    #[error("serialization error: {0}")]
    Serialization(String),

    /// Deserialization error.
    #[error("deserialization error: {0}")]
    Deserialization(String),

    /// Unsupported signature scheme.
    #[error("unsupported signature scheme: {0:?}")]
    UnsupportedSignatureScheme(openmls_traits::types::SignatureScheme),
}
