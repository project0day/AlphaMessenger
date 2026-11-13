//! Dual identity combining Nostr and MLS identities.
//!
//! The `DualIdentity` struct holds both a `NostrIdentity` and an `MlsIdentity`
//! together, representing the complete identity of an AlphaMessenger user.
//! Identity separation between Nostr (transport) and MLS (group signing) is
//! a core design principle — the two identities use entirely independent keys.
//!
//! # Security
//!
//! `SerializableDualIdentity` implements `Zeroize` and has a custom `Drop`
//! that zeroizes the Nostr secret key hex string and delegates to the
//! `SerializableMlsIdentity` zeroization for the MLS keypair.

use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

use crate::error::IdentityError;
use crate::key_material::NostrKeyPair;
use crate::mls_identity::{MlsIdentity, SerializableMlsIdentity};
use crate::nostr_identity::NostrIdentity;

/// Serializable form of the dual identity for key vault persistence.
///
/// Both keypairs are stored independently, maintaining identity separation.
///
/// # Zeroization
///
/// This type holds secret key material (the Nostr secret key hex and the
/// MLS signature key pair). It implements `Zeroize` and `Drop` to ensure
/// all secret material is overwritten in memory before deallocation.
pub struct SerializableDualIdentity {
    /// The Nostr secret key as a hex string.
    nostr_secret_key_hex: String,
    /// The MLS identity in serializable form.
    mls: SerializableMlsIdentity,
}

impl Zeroize for SerializableDualIdentity {
    fn zeroize(&mut self) {
        // Zeroize the secret key hex string — this is the most sensitive field
        // String doesn't impl Zeroize directly, so we overwrite with zeros
        self.nostr_secret_key_hex.zeroize();
        // Delegate to SerializableMlsIdentity's zeroize
        self.mls.zeroize();
    }
}

impl Drop for SerializableDualIdentity {
    fn drop(&mut self) {
        self.zeroize();
    }
}

// Custom Serialize: delegate to the same JSON format as before
impl Serialize for SerializableDualIdentity {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut state = serializer.serialize_struct("SerializableDualIdentity", 2)?;
        state.serialize_field("nostr_secret_key_hex", &self.nostr_secret_key_hex)?;
        state.serialize_field("mls", &self.mls)?;
        state.end()
    }
}

// Custom Deserialize: reconstruct from JSON
impl<'de> Deserialize<'de> for SerializableDualIdentity {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        struct Helper {
            nostr_secret_key_hex: String,
            mls: SerializableMlsIdentity,
        }
        let helper = Helper::deserialize(deserializer)?;
        Ok(Self {
            nostr_secret_key_hex: helper.nostr_secret_key_hex,
            mls: helper.mls,
        })
    }
}

impl std::fmt::Debug for SerializableDualIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SerializableDualIdentity")
            .field("nostr_secret_key_hex", &"[redacted]")
            .field("mls", &self.mls)
            .finish()
    }
}

impl Clone for SerializableDualIdentity {
    fn clone(&self) -> Self {
        Self {
            nostr_secret_key_hex: self.nostr_secret_key_hex.clone(),
            mls: self.mls.clone(),
        }
    }
}

/// The complete dual identity of an AlphaMessenger user.
///
/// This holds both:
/// - **NostrIdentity** — for transport-layer event signing and relay communication
/// - **MlsIdentity** — for MLS group operations and message signing
///
/// These identities are cryptographically independent, ensuring that compromise
/// of one layer does not affect the other.
#[derive(Debug, Clone)]
pub struct DualIdentity {
    /// The Nostr identity (secp256k1).
    nostr: NostrIdentity,
    /// The MLS identity (Ed25519).
    mls: MlsIdentity,
}

impl DualIdentity {
    /// Generate a new dual identity with fresh random keys for both layers.
    ///
    /// This creates independent keypairs:
    /// - Nostr: secp256k1 keypair for event signing
    /// - MLS: Ed25519 keypair for group signing
    pub fn generate() -> Result<Self, IdentityError> {
        let nostr = NostrIdentity::generate();
        let mls = MlsIdentity::new()
            .map_err(|e| IdentityError::MlsKeyGeneration(e.to_string()))?;

        Ok(Self { nostr, mls })
    }

    /// Create a `DualIdentity` from existing identity components.
    pub fn from_parts(nostr: NostrIdentity, mls: MlsIdentity) -> Self {
        Self { nostr, mls }
    }

    /// Get a reference to the Nostr identity.
    pub fn nostr(&self) -> &NostrIdentity {
        &self.nostr
    }

    /// Get a reference to the MLS identity.
    pub fn mls(&self) -> &MlsIdentity {
        &self.mls
    }

    /// Convert to a serializable form for key vault persistence.
    ///
    /// This serializes both the Nostr secret key (hex) and the MLS
    /// keypair into a format suitable for encrypted storage.
    pub fn to_serializable(&self) -> SerializableDualIdentity {
        SerializableDualIdentity {
            nostr_secret_key_hex: self.nostr.keypair().secret_key_hex().to_string(),
            mls: self.mls.to_serializable(),
        }
    }

    /// Restore from a serializable form.
    ///
    /// Reconstructs both identities from their persisted state.
    pub fn from_serializable(data: &SerializableDualIdentity) -> Result<Self, IdentityError> {
        let nostr_keypair = NostrKeyPair::from_secret_key_hex(&data.nostr_secret_key_hex)
            .map_err(|e| IdentityError::Deserialization(e.to_string()))?;
        let nostr = NostrIdentity::from_keys(nostr_keypair);

        let mls = MlsIdentity::from_serializable(&data.mls)
            .map_err(|e| IdentityError::Deserialization(e.to_string()))?;

        Ok(Self { nostr, mls })
    }

    /// Get the Nostr npub (human-readable public key).
    pub fn npub(&self) -> Result<String, IdentityError> {
        self.nostr
            .npub()
            .map_err(|e| IdentityError::Serialization(e.to_string()))
    }

    /// Get the MLS identity string (UUID).
    pub fn mls_identity_string(&self) -> Option<&str> {
        self.mls.identity_string()
    }
}

/// Compile-time assertion that `DualIdentity` is `Send + Sync`.
fn _assert_dual_identity_send_sync() {
    fn require_send_sync<T: Send + Sync>() {}
    require_send_sync::<DualIdentity>();
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    #[test]
    fn test_generate_dual_identity() {
        let identity = DualIdentity::generate().expect("generation should succeed");

        // Nostr identity should be valid
        let npub = identity.npub().expect("npub should succeed");
        assert!(npub.starts_with("npub1"));

        // MLS identity should be valid
        let mls_id = identity.mls_identity_string().expect("MLS identity should be a valid string");
        let _ = Uuid::parse_str(mls_id).expect("MLS identity should be a valid UUID");
    }

    #[test]
    fn test_serialization_roundtrip() {
        let original = DualIdentity::generate().expect("generation should succeed");

        let serialized = original.to_serializable();
        let json = serde_json::to_string(&serialized).expect("JSON serialization should succeed");
        let deserialized: SerializableDualIdentity =
            serde_json::from_str(&json).expect("JSON deserialization should succeed");

        let restored = DualIdentity::from_serializable(&deserialized)
            .expect("restoration should succeed");

        // Verify Nostr identity matches
        assert_eq!(
            original.nostr().public_key_hex(),
            restored.nostr().public_key_hex(),
            "Nostr public keys should match after roundtrip"
        );

        // Verify MLS identity matches
        assert_eq!(
            original.mls().identity_bytes(),
            restored.mls().identity_bytes(),
            "MLS identity bytes should match after roundtrip"
        );
        assert_eq!(
            original.mls().public_key(),
            restored.mls().public_key(),
            "MLS public keys should match after roundtrip"
        );
    }
}
