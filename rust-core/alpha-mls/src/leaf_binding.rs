//! A leaf's credential that proves whose it is: the Nostr key signs the MLS
//! signing key. Any member can then check "this leaf is Bob's" without
//! trusting whoever added it, and nobody can make one without Bob's secret key.
//!
//! Leaves of older builds carry a random identity instead: they still work,
//! but prove nothing about their owner.

use nostr::hashes::{sha256, Hash};
use nostr::secp256k1::{schnorr, Message, XOnlyPublicKey};

/// Leading bytes of a bound credential (version 1).
const MAGIC: &[u8] = b"alpha-id\x01";
const DOMAIN: &[u8] = b"alpha-mls-leaf-binding-v1";
const BOUND_LEN: usize = MAGIC.len() + 32 + 64;

/// What a leaf's credential says about its owner.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LeafVerdict {
    /// Signed by this Nostr key (x-only, 32 bytes).
    Bound([u8; 32]),
    /// No claim (an older build): the owner is known only from the directory.
    Legacy,
    /// Claims a Nostr key but the signature does not hold: never accepted.
    Forged,
}

impl LeafVerdict {
    pub fn npub_hex(&self) -> Option<String> {
        match self {
            LeafVerdict::Bound(pk) => Some(hex::encode(pk)),
            _ => None,
        }
    }
}

fn digest(signature_key: &[u8]) -> Message {
    let mut data = Vec::with_capacity(DOMAIN.len() + signature_key.len());
    data.extend_from_slice(DOMAIN);
    data.extend_from_slice(signature_key);
    Message::from_digest(sha256::Hash::hash(&data).to_byte_array())
}

/// The credential identity binding `signature_key` to `keys`.
pub fn bind(keys: &nostr::Keys, signature_key: &[u8]) -> Vec<u8> {
    let sig = keys.sign_schnorr(&digest(signature_key));
    let mut out = Vec::with_capacity(BOUND_LEN);
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&keys.public_key().to_bytes());
    out.extend_from_slice(sig.as_ref());
    out
}

/// Check a leaf: its credential identity against its signing key.
pub fn check(credential_identity: &[u8], signature_key: &[u8]) -> LeafVerdict {
    if !credential_identity.starts_with(MAGIC) {
        return LeafVerdict::Legacy;
    }
    if credential_identity.len() != BOUND_LEN {
        return LeafVerdict::Forged;
    }
    let pk_bytes = &credential_identity[MAGIC.len()..MAGIC.len() + 32];
    let sig_bytes = &credential_identity[MAGIC.len() + 32..];
    let (Ok(pk), Ok(sig)) = (XOnlyPublicKey::from_slice(pk_bytes), schnorr::Signature::from_slice(sig_bytes)) else {
        return LeafVerdict::Forged;
    };
    match nostr::SECP256K1.verify_schnorr(&sig, &digest(signature_key), &pk) {
        Ok(()) => {
            let mut out = [0u8; 32];
            out.copy_from_slice(pk_bytes);
            LeafVerdict::Bound(out)
        }
        Err(_) => LeafVerdict::Forged,
    }
}

/// The owner a serialized KeyPackage's leaf proves; `None` if it does not parse.
pub fn key_package_owner(kp_bytes: &[u8]) -> Option<LeafVerdict> {
    let crypto = openmls_rust_crypto::RustCrypto::default();
    let kp = crate::key_package::AlphaKeyPackage::key_package_from_bytes_with_provider(kp_bytes, &crypto).ok()?;
    let leaf = kp.leaf_node();
    Some(check(leaf.credential().serialized_content(), leaf.signature_key().as_slice()))
}

/// The MLS signing key of the leaf a KeyPackage would add.
pub fn key_package_signature_key(kp_bytes: &[u8]) -> Option<Vec<u8>> {
    let crypto = openmls_rust_crypto::RustCrypto::default();
    let kp = crate::key_package::AlphaKeyPackage::key_package_from_bytes_with_provider(kp_bytes, &crypto).ok()?;
    Some(kp.leaf_node().signature_key().as_slice().to_vec())
}

/// (signing key hex, owner npub hex) of every member whose leaf proves its owner.
pub fn proven_owners(members: &[crate::message::GroupMember]) -> Vec<(String, String)> {
    members.iter()
        .filter_map(|m| check(&m.credential_bytes, &m.signature_key).npub_hex().map(|n| (hex::encode(&m.signature_key), n)))
        .collect()
}

/// Whether a leaf may replace `old` in the same slot: a proven owner stays the
/// owner; a leaf without proof may gain one.
pub fn may_replace(old: LeafVerdict, new: LeafVerdict) -> bool {
    match (old, new) {
        (_, LeafVerdict::Forged) => false,
        (LeafVerdict::Bound(a), LeafVerdict::Bound(b)) => a == b,
        (LeafVerdict::Bound(_), _) => false,
        _ => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_bound_leaf_names_its_owner() {
        let keys = nostr::Keys::generate();
        let leaf = [7u8; 32];
        let id = bind(&keys, &leaf);
        assert_eq!(check(&id, &leaf), LeafVerdict::Bound(keys.public_key().to_bytes()));
    }

    #[test]
    fn a_binding_does_not_move_to_another_leaf() {
        let keys = nostr::Keys::generate();
        let id = bind(&keys, &[7u8; 32]);
        assert_eq!(check(&id, &[8u8; 32]), LeafVerdict::Forged);
    }

    #[test]
    fn someone_else_cannot_claim_a_key() {
        let victim = nostr::Keys::generate();
        let mallory = nostr::Keys::generate();
        let leaf = [7u8; 32];
        let mut id = bind(&mallory, &leaf);
        id[MAGIC.len()..MAGIC.len() + 32].copy_from_slice(&victim.public_key().to_bytes());
        assert_eq!(check(&id, &leaf), LeafVerdict::Forged);
        assert_eq!(check(&id[..id.len() - 1], &leaf), LeafVerdict::Forged);
    }

    #[test]
    fn old_credentials_are_legacy() {
        assert_eq!(check(b"0f1e2d3c4b5a69788796a5b4c3d2e1f0", &[7u8; 32]), LeafVerdict::Legacy);
    }

    #[test]
    fn a_proven_owner_cannot_be_replaced() {
        let a = LeafVerdict::Bound([1; 32]);
        let b = LeafVerdict::Bound([2; 32]);
        assert!(may_replace(a, a));
        assert!(!may_replace(a, b));
        assert!(!may_replace(a, LeafVerdict::Legacy));
        assert!(may_replace(LeafVerdict::Legacy, a));
        assert!(!may_replace(LeafVerdict::Legacy, LeafVerdict::Forged));
    }
}
