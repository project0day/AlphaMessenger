//! Nostr identity management.
//!
//! Provides the `NostrIdentity` struct which represents the Nostr layer of the
//! dual-identity system. This identity uses secp256k1 keys for transport-level
//! event signing and relay communication.

use nostr::nips::nip19::ToBech32;
use nostr::secp256k1::schnorr::Signature;
use nostr::secp256k1::Message;

use crate::error::NostrIdentityError;
use crate::key_material::NostrKeyPair;

/// A Nostr identity backed by a secp256k1 keypair.
///
/// This identity is used for:
/// - Publishing and signing Nostr events
/// - Transport-level communication via Nostr relays
/// - npub-based user identification
#[derive(Debug, Clone)]
pub struct NostrIdentity {
    /// The underlying Nostr keypair.
    keypair: NostrKeyPair,
}

impl NostrIdentity {
    /// Create a `NostrIdentity` from an existing `NostrKeyPair`.
    pub fn from_keys(keypair: NostrKeyPair) -> Self {
        Self { keypair }
    }

    /// Generate a new random Nostr identity.
    pub fn generate() -> Self {
        Self {
            keypair: NostrKeyPair::generate(),
        }
    }

    /// Get the Nostr public key.
    pub fn public_key(&self) -> nostr::PublicKey {
        self.keypair.public_key()
    }

    /// Get the npub (bech32-encoded public key) representation.
    ///
    /// This is the standard human-readable format for Nostr public keys,
    /// e.g., `npub1abc2def3...`
    pub fn npub(&self) -> Result<String, NostrIdentityError> {
        self.keypair
            .public_key()
            .to_bech32()
            .map_err(|e| NostrIdentityError::NpubEncoding(e.to_string()))
    }

    /// Sign a raw 32-byte message hash using Schnorr signature.
    ///
    /// This is the low-level signing primitive. For event signing, use
    /// [`NostrIdentity::sign_event`] which handles the serialization and
    /// hashing internally.
    ///
    /// # Arguments
    /// * `message` - The 32-byte message hash to sign (must be a SHA-256 digest)
    ///
    /// # Returns
    /// The 64-byte Schnorr signature
    pub fn sign_schnorr(&self, message: &[u8]) -> Result<Signature, NostrIdentityError> {
        let msg = Message::from_digest_slice(message)
            .map_err(|e| NostrIdentityError::SigningFailed(format!("invalid message: {}", e)))?;
        Ok(self.keypair.keys().sign_schnorr(&msg))
    }

    /// Sign a Nostr event and return the signed event.
    ///
    /// This is the primary signing method for Nostr events. It takes an
    /// `EventBuilder`, finalizes it with the identity's keys, and returns
    /// a fully signed `Event`.
    ///
    /// # Arguments
    /// * `event_builder` - The event builder containing the event template
    ///
    /// # Returns
    /// A signed Nostr `Event`
    pub fn sign_event(
        &self,
        event_builder: nostr::EventBuilder,
    ) -> Result<nostr::Event, NostrIdentityError> {
        event_builder
            .sign_with_keys(self.keypair.keys())
            .map_err(|e| NostrIdentityError::SigningFailed(e.to_string()))
    }

    /// Get a reference to the underlying keypair.
    pub fn keypair(&self) -> &NostrKeyPair {
        &self.keypair
    }

    /// Get the public key as a hex string.
    pub fn public_key_hex(&self) -> String {
        self.keypair.public_key().to_hex()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_nostr_identity() {
        let identity = NostrIdentity::generate();
        let npub = identity.npub().expect("npub encoding should succeed");
        assert!(npub.starts_with("npub1"), "npub should start with 'npub1'");
    }

    #[test]
    fn test_public_key_hex() {
        let identity = NostrIdentity::generate();
        let hex = identity.public_key_hex();
        assert_eq!(hex.len(), 64, "secp256k1 public key should be 32 bytes = 64 hex chars");
    }

    #[test]
    fn test_from_keys_roundtrip() {
        let original = NostrIdentity::generate();
        let secret_hex = original.keypair().secret_key_hex();

        let restored_keypair = NostrKeyPair::from_secret_key_hex(&secret_hex).unwrap();
        let restored = NostrIdentity::from_keys(restored_keypair);

        assert_eq!(
            original.public_key_hex(),
            restored.public_key_hex(),
            "restored identity should have the same public key"
        );
    }
}
