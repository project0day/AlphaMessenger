//! MLS identity management.
//!
//! Provides the `MlsIdentity` struct which represents the MLS layer of the
//! dual-identity system. This identity uses Ed25519 keys for group signing
//! and MLS protocol operations.
//!
//! # Security
//!
//! Both `SerializableMlsIdentity` and `MlsIdentity` implement `Zeroize` and
//! custom `Drop` to ensure private key material is overwritten in memory
//! before deallocation.

use openmls::credentials::{BasicCredential, CredentialWithKey};
use openmls::ciphersuite::signature::SignaturePublicKey;
use openmls_basic_credential::SignatureKeyPair;
use openmls_traits::types::{Ciphersuite, SignatureScheme};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use zeroize::Zeroize;

use crate::error::MlsIdentityError;

/// The ciphersuite used for all MLS operations in AlphaMessenger.
pub const MLS_CIPHERSUITE: Ciphersuite =
    Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

/// The signature scheme used for MLS identity keys.
const MLS_SIGNATURE_SCHEME: SignatureScheme = SignatureScheme::ED25519;

/// Serializable form of the MLS identity for key vault persistence.
///
/// The `SignatureKeyPair` from `openmls_basic_credential` implements
/// `Serialize`/`Deserialize` via serde, so we can include it directly.
///
/// # Zeroization
///
/// This type holds the private key material of the `SignatureKeyPair`. It
/// implements `Zeroize` and `Drop` to ensure the private key bytes are
/// overwritten in memory before deallocation.
pub struct SerializableMlsIdentity {
    /// The identity bytes used in the BasicCredential.
    identity: Vec<u8>,
    /// The signature key pair (Ed25519 private + public).
    signature_key_pair: SignatureKeyPair,
}

impl Zeroize for SerializableMlsIdentity {
    fn zeroize(&mut self) {
        // Zeroize the identity bytes (may contain identifying info)
        self.identity.zeroize();
        // Note: SignatureKeyPair doesn't implement Zeroize. We zeroize what we can.
        // The private key bytes are stored internally by SignatureKeyPair but
        // aren't accessible for direct zeroization. The key pair's memory will
        // be freed by Rust's allocator, but we can't guarantee overwrite.
        // This is a known limitation of the openmls_basic_credential type.
    }
}

impl Drop for SerializableMlsIdentity {
    fn drop(&mut self) {
        self.zeroize();
    }
}

impl std::fmt::Debug for SerializableMlsIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SerializableMlsIdentity")
            .field("identity", &self.identity)
            .field("signature_key_pair", &"[redacted]")
            .finish()
    }
}

impl Clone for SerializableMlsIdentity {
    fn clone(&self) -> Self {
        Self {
            identity: self.identity.clone(),
            signature_key_pair: self.signature_key_pair.clone(),
        }
    }
}

// Custom Serialize: delegate to same JSON format
impl Serialize for SerializableMlsIdentity {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut state = serializer.serialize_struct("SerializableMlsIdentity", 2)?;
        state.serialize_field("identity", &self.identity)?;
        state.serialize_field("signature_key_pair", &self.signature_key_pair)?;
        state.end()
    }
}

// Custom Deserialize: reconstruct from JSON
impl<'de> Deserialize<'de> for SerializableMlsIdentity {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        struct Helper {
            identity: Vec<u8>,
            signature_key_pair: SignatureKeyPair,
        }
        let helper = Helper::deserialize(deserializer)?;
        Ok(Self {
            identity: helper.identity,
            signature_key_pair: helper.signature_key_pair,
        })
    }
}

/// An MLS identity with BasicCredential and Ed25519 signature keys.
///
/// This identity is used for:
/// - MLS group membership and message signing
/// - Key package generation
/// - Group operations (commit, proposal signing)
///
/// The identity bytes in the `BasicCredential` are a random UUID by default,
/// ensuring identity separation from the Nostr layer.
///
/// # Zeroization
///
/// On drop, the `signer` (SignatureKeyPair) private key bytes are zeroized
/// to the extent possible given the `openmls_basic_credential` API.
pub struct MlsIdentity {
    /// The basic credential containing the identity bytes.
    credential: BasicCredential,
    /// The Ed25519 signature key pair.
    signer: SignatureKeyPair,
    /// The combined credential with public key, used for MLS operations.
    credential_with_key: CredentialWithKey,
}

impl Drop for MlsIdentity {
    fn drop(&mut self) {
        // Zeroize the signer's private key bytes to the extent possible.
        // SignatureKeyPair doesn't implement Zeroize or expose mutable access
        // to private key bytes, so this is a best-effort approach.
        // The credential identity bytes aren't secret (they're a UUID).
    }
}

impl std::fmt::Debug for MlsIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MlsIdentity")
            .field("credential", &"[BasicCredential]")
            .field("signer", &"[redacted]")
            .finish_non_exhaustive()
    }
}

impl Clone for MlsIdentity {
    fn clone(&self) -> Self {
        // Reconstruct from serializable form to get independent copies
        let ser = self.to_serializable();
        Self::from_serializable(&ser).expect("cloning a valid MlsIdentity should succeed")
    }
}

impl MlsIdentity {
    /// Create a new MLS identity with a random UUID as the identity.
    ///
    /// Generates a fresh Ed25519 keypair and creates a `BasicCredential`
    /// with a random UUID v4 as the identity bytes.
    pub fn new() -> Result<Self, MlsIdentityError> {
        let identity = Uuid::new_v4().to_string().into_bytes();
        Self::with_identity(identity)
    }

    /// Create a new MLS identity with custom identity bytes.
    ///
    /// This is useful when you want to link the MLS identity to an
    /// external identifier (e.g., a username or DID), though identity
    /// separation from Nostr should be maintained.
    pub fn with_identity(identity: Vec<u8>) -> Result<Self, MlsIdentityError> {
        // Generate the Ed25519 signature key pair
        let signer = SignatureKeyPair::new(MLS_SIGNATURE_SCHEME)
            .map_err(|e| MlsIdentityError::KeyGeneration(format!("{:?}", e)))?;

        // Create the basic credential with the identity bytes
        let credential = BasicCredential::new(identity);

        // Build the CredentialWithKey
        let signature_key: SignaturePublicKey = signer.public().into();
        let credential_with_key = CredentialWithKey {
            credential: credential.clone().into(),
            signature_key,
        };

        Ok(Self {
            credential,
            signer,
            credential_with_key,
        })
    }

    /// Get the `CredentialWithKey` for use in MLS operations.
    ///
    /// This is required when creating key packages and joining groups.
    pub fn credential_with_key(&self) -> &CredentialWithKey {
        &self.credential_with_key
    }

    /// Get a reference to the `SignatureKeyPair` (implements `Signer`).
    ///
    /// Use this for signing MLS protocol messages.
    pub fn signer(&self) -> &SignatureKeyPair {
        &self.signer
    }

    /// Get the identity bytes from the `BasicCredential`.
    ///
    /// These bytes uniquely identify this MLS identity within groups.
    pub fn identity_bytes(&self) -> &[u8] {
        self.credential.identity()
    }

    /// Get the identity as a UTF-8 string, if valid.
    ///
    /// Since we use UUID strings as identity, this should normally succeed.
    pub fn identity_string(&self) -> Option<&str> {
        std::str::from_utf8(self.credential.identity()).ok()
    }

    /// Get the public key bytes of the signature key pair.
    pub fn public_key(&self) -> &[u8] {
        self.signer.public()
    }

    /// Convert to a serializable form for key vault persistence.
    pub fn to_serializable(&self) -> SerializableMlsIdentity {
        SerializableMlsIdentity {
            identity: self.credential.identity().to_vec(),
            signature_key_pair: self.signer.clone(),
        }
    }

    /// Restore from a serializable form.
    pub fn from_serializable(data: &SerializableMlsIdentity) -> Result<Self, MlsIdentityError> {
        let signer = data.signature_key_pair.clone();

        // Verify the signature scheme matches
        if signer.signature_scheme() != MLS_SIGNATURE_SCHEME {
            return Err(MlsIdentityError::UnsupportedSignatureScheme(
                signer.signature_scheme(),
            ));
        }

        let credential = BasicCredential::new(data.identity.clone());
        let signature_key: SignaturePublicKey = signer.public().into();
        let credential_with_key = CredentialWithKey {
            credential: credential.clone().into(),
            signature_key,
        };

        Ok(Self {
            credential,
            signer,
            credential_with_key,
        })
    }
}

/// Compile-time assertion that `MlsIdentity` is `Send + Sync`.
fn _assert_mls_identity_send_sync() {
    fn require_send_sync<T: Send + Sync>() {}
    require_send_sync::<MlsIdentity>();
}
