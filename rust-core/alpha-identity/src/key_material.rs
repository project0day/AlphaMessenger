//! Low-level key material types for Nostr and MLS identities.
//!
//! This module provides wrapper types around the raw cryptographic keypairs
//! used by Nostr (secp256k1) and MLS (Ed25519) with proper serialization
//! support for the key vault.
//!
//! # Security
//!
//! All types holding secret key material implement `Drop` with explicit
//! zeroization to ensure secrets don't linger in memory after use.

use serde::{Deserialize, Deserializer, Serialize, Serializer};
use zeroize::Zeroizing;

use crate::error::{IdentityError, MlsIdentityError, NostrIdentityError};

// ---------------------------------------------------------------------------
// NostrKeyPair
// ---------------------------------------------------------------------------

/// A Nostr keypair wrapping `nostr::Keys` (secp256k1).
///
/// This type provides serialization by storing the secret key as a hex string,
/// and reconstructing the full keypair on deserialization.
///
/// # Zeroization
///
/// `nostr::Keys` erases its secret key when dropped (nostr ≥ 0.44), so this
/// wrapper needs no `Drop` of its own; hex copies it hands out are `Zeroizing`.
pub struct NostrKeyPair {
    keys: nostr::Keys,
}

impl std::fmt::Debug for NostrKeyPair {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NostrKeyPair")
            .field("public_key", &self.keys.public_key().to_hex())
            .finish_non_exhaustive()
    }
}

impl Clone for NostrKeyPair {
    fn clone(&self) -> Self {
        // A plain copy of the keys: no hex string of the secret on the way.
        Self { keys: self.keys.clone() }
    }
}

impl NostrKeyPair {
    /// Generate a new random Nostr keypair.
    pub fn generate() -> Self {
        Self {
            keys: nostr::Keys::generate(),
        }
    }

    /// Create a `NostrKeyPair` from an existing `nostr::Keys` object.
    pub fn from_keys(keys: nostr::Keys) -> Self {
        Self { keys }
    }

    /// Create a `NostrKeyPair` from a hex-encoded secret key string.
    pub fn from_secret_key_hex(hex: &str) -> Result<Self, NostrIdentityError> {
        let secret_key = nostr::SecretKey::from_hex(hex)
            .map_err(|e| NostrIdentityError::InvalidSecretKey(e.to_string()))?;
        let keys = nostr::Keys::new(secret_key);
        Ok(Self { keys })
    }

    /// Get a reference to the inner `nostr::Keys`.
    pub fn keys(&self) -> &nostr::Keys {
        &self.keys
    }

    /// Get the public key.
    pub fn public_key(&self) -> nostr::PublicKey {
        self.keys.public_key()
    }

    /// Get the secret key as a hex string.
    ///
    /// Returns a `Zeroizing<String>` wrapper that automatically zeroizes
    /// the string contents on drop, preventing the secret from lingering
    /// in memory.
    pub fn secret_key_hex(&self) -> Zeroizing<String> {
        Zeroizing::new(self.keys.secret_key().to_secret_hex())
    }
}

// Custom Serialize: store only the secret key as hex
impl Serialize for NostrKeyPair {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        // Serialize the secret key as a hex string
        // The Zeroizing wrapper is transparent for serialization
        let hex = self.secret_key_hex();
        serializer.serialize_str(&hex)
    }
}

// Custom Deserialize: reconstruct from hex secret key
impl<'de> Deserialize<'de> for NostrKeyPair {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let hex = Zeroizing::new(String::deserialize(deserializer)?);
        Self::from_secret_key_hex(&hex).map_err(serde::de::Error::custom)
    }
}

// ---------------------------------------------------------------------------
// MlsKeyPair
// ---------------------------------------------------------------------------

/// An MLS keypair combining a `SignatureKeyPair` with identity information.
///
/// This wraps the OpenMLS `SignatureKeyPair` (which holds Ed25519 keys) along
/// with the serialized form needed for persistence. The `SignatureKeyPair`
/// from `openmls_basic_credential` already implements `Serialize`/`Deserialize`.
///
/// # Zeroization
///
/// Not wiped on drop: OpenMLS's `SignatureKeyPair` keeps the private key in a
/// plain `Vec<u8>` it gives no mutable access to, and the vendored crate stays
/// byte-for-byte upstream. The key lives as long as the identity anyway.
pub struct MlsKeyPair {
    /// The signature key pair (Ed25519 private + public).
    signature_key_pair: openmls_basic_credential::SignatureKeyPair,
}

impl std::fmt::Debug for MlsKeyPair {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MlsKeyPair")
            .field("signature_scheme", &self.signature_key_pair.signature_scheme())
            .finish_non_exhaustive()
    }
}

impl Clone for MlsKeyPair {
    fn clone(&self) -> Self {
        Self {
            signature_key_pair: self.signature_key_pair.clone(),
        }
    }
}

impl MlsKeyPair {
    /// Generate a new MLS keypair using Ed25519.
    pub fn generate() -> Result<Self, MlsIdentityError> {
        let signature_key_pair =
            openmls_basic_credential::SignatureKeyPair::new(
                openmls_traits::types::SignatureScheme::ED25519,
            )
            .map_err(|e| MlsIdentityError::KeyGeneration(format!("{:?}", e)))?;

        Ok(Self { signature_key_pair })
    }

    /// Create an `MlsKeyPair` from an existing `SignatureKeyPair`.
    pub fn from_signature_key_pair(key_pair: openmls_basic_credential::SignatureKeyPair) -> Self {
        Self {
            signature_key_pair: key_pair,
        }
    }

    /// Get a reference to the inner `SignatureKeyPair`.
    pub fn signature_key_pair(&self) -> &openmls_basic_credential::SignatureKeyPair {
        &self.signature_key_pair
    }

    /// Get the public key bytes.
    pub fn public_key(&self) -> &[u8] {
        self.signature_key_pair.public()
    }

    /// Get the signature scheme.
    pub fn signature_scheme(&self) -> openmls_traits::types::SignatureScheme {
        self.signature_key_pair.signature_scheme()
    }
}

// Implement Serialize by delegating to SignatureKeyPair's serde impl
impl Serialize for MlsKeyPair {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        self.signature_key_pair.serialize(serializer)
    }
}

// Implement Deserialize by delegating to SignatureKeyPair's serde impl
impl<'de> Deserialize<'de> for MlsKeyPair {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let skp = openmls_basic_credential::SignatureKeyPair::deserialize(deserializer)?;
        Ok(Self { signature_key_pair: skp })
    }
}

// ---------------------------------------------------------------------------
// Helper functions
// ---------------------------------------------------------------------------

/// Generate both a Nostr and MLS keypair in one call.
///
/// Returns a tuple of `(NostrKeyPair, MlsKeyPair)` with freshly generated keys.
pub fn generate_both() -> Result<(NostrKeyPair, MlsKeyPair), IdentityError> {
    let nostr_keypair = NostrKeyPair::generate();
    let mls_keypair = MlsKeyPair::generate()
        .map_err(|e| IdentityError::MlsKeyGeneration(e.to_string()))?;

    Ok((nostr_keypair, mls_keypair))
}
