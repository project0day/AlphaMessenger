//! MLS credential management.
//!
//! Provides the [`AlphaCredential`] wrapper around OpenMLS `BasicCredential`
//! with convenience methods for creation, serialization, and key management.
//!
//! # Security
//!
//! `AlphaCredential` implements `Zeroize` and `Drop` to ensure the
//! `serialized_signer` field (which contains private key material as JSON)
//! is overwritten in memory before deallocation.

use openmls::prelude::BasicCredential;
use openmls_basic_credential::SignatureKeyPair;
use openmls_traits::types::Ciphersuite;
use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

use crate::error::MlsError;

/// Default ciphersuite used throughout AlphaMessenger.
///
/// This is the stock RFC 9420 ciphersuite 0x0001:
/// `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`
///
/// **NOT** a custom ciphersuite. No XChaCha20 in MLS.
pub const DEFAULT_CIPHERSUITE: Ciphersuite =
    Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

/// The numeric ciphersuite ID as defined in RFC 9420.
pub const DEFAULT_CIPHERSUITE_ID: u16 = 0x0001;

/// Wrapper around OpenMLS BasicCredential with convenience methods.
///
/// In AlphaMessenger, a credential consists of an identity (e.g. a
/// Nostr npub or a user ID) and a signature key pair that authenticates
/// MLS protocol messages.
///
/// # Zeroization
///
/// The `serialized_signer` field contains the JSON-serialized `SignatureKeyPair`,
/// which includes the private key. On drop, this field is zeroized to prevent
/// the private key from lingering in memory.
pub struct AlphaCredential {
    /// The identity bytes (e.g. Nostr public key, username).
    identity: Vec<u8>,
    /// The serialized credential + signer pair.
    /// Stored as JSON because both types implement Serialize/Deserialize.
    /// **Contains private key material** — zeroized on drop.
    serialized_signer: Vec<u8>,
}

impl Zeroize for AlphaCredential {
    fn zeroize(&mut self) {
        self.identity.zeroize();
        self.serialized_signer.zeroize();
    }
}

impl Drop for AlphaCredential {
    fn drop(&mut self) {
        self.zeroize();
    }
}

impl std::fmt::Debug for AlphaCredential {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AlphaCredential")
            .field("identity", &self.identity)
            .field("serialized_signer", &"[redacted]")
            .finish()
    }
}

impl Clone for AlphaCredential {
    fn clone(&self) -> Self {
        Self {
            identity: self.identity.clone(),
            serialized_signer: self.serialized_signer.clone(),
        }
    }
}

// Custom Serialize: same format as before
impl Serialize for AlphaCredential {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut state = serializer.serialize_struct("AlphaCredential", 2)?;
        state.serialize_field("identity", &self.identity)?;
        state.serialize_field("serialized_signer", &self.serialized_signer)?;
        state.end()
    }
}

// Custom Deserialize: reconstruct from JSON
impl<'de> Deserialize<'de> for AlphaCredential {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        struct Helper {
            identity: Vec<u8>,
            serialized_signer: Vec<u8>,
        }
        let helper = Helper::deserialize(deserializer)?;
        Ok(Self {
            identity: helper.identity,
            serialized_signer: helper.serialized_signer,
        })
    }
}

impl AlphaCredential {
    /// Generate a new credential and associated signer.
    ///
    /// This creates:
    /// - A `BasicCredential` containing `identity`
    /// - A `SignatureKeyPair` for the default ciphersuite
    ///
    /// The signer is stored in the provider's key store automatically
    /// when you call [`Self::store_signer`].
    pub fn generate(identity: &[u8]) -> Result<(BasicCredential, SignatureKeyPair), MlsError> {
        let credential = BasicCredential::new(identity.to_vec());
        let signer = SignatureKeyPair::new(DEFAULT_CIPHERSUITE.signature_algorithm())
            .map_err(|e| MlsError::Crypto(format!("Failed to generate signer: {e:?}")))?;
        Ok((credential, signer))
    }

    /// Create an `AlphaCredential` from identity bytes and a signer.
    ///
    /// This is the main constructor when you already have a signer.
    pub fn new(identity: &[u8], signer: &SignatureKeyPair) -> Result<Self, MlsError> {
        let serialized_signer = serde_json::to_vec(signer)
            .map_err(|e| MlsError::Serialization(e.to_string()))?;
        Ok(Self {
            identity: identity.to_vec(),
            serialized_signer,
        })
    }

    /// Build the OpenMLS `CredentialWithKey` from this credential and signer.
    ///
    /// This is the struct that OpenMLS APIs expect when creating groups
    /// or key packages.
    pub fn credential_with_key(&self) -> Result<openmls::prelude::CredentialWithKey, MlsError> {
        let credential = BasicCredential::new(self.identity.clone());
        let signer = self.signer()?;
        Ok(openmls::prelude::CredentialWithKey {
            credential: credential.into(),
            signature_key: signer.public().into(),
        })
    }

    /// Get the underlying `SignatureKeyPair` (signer).
    pub fn signer(&self) -> Result<SignatureKeyPair, MlsError> {
        serde_json::from_slice(&self.serialized_signer)
            .map_err(|e| MlsError::Serialization(e.to_string()))
    }

    /// Get the identity bytes.
    pub fn identity(&self) -> &[u8] {
        &self.identity
    }

    /// Get the public signature key bytes.
    pub fn public_key(&self) -> Result<Vec<u8>, MlsError> {
        let signer = self.signer()?;
        Ok(signer.public().to_vec())
    }

    /// Store the signer in the provider's key store so OpenMLS can
    /// retrieve it later for signing operations.
    pub fn store_signer(
        &self,
        storage: &impl openmls_traits::storage::StorageProvider<1>,
    ) -> Result<(), MlsError> {
        let signer = self.signer()?;
        signer
            .store(storage)
            .map_err(|e| MlsError::Storage(format!("Failed to store signer: {e:?}")))?;
        Ok(())
    }
}
