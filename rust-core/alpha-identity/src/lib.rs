//! # AlphaMessenger Identity Management
//!
//! This crate manages the dual-identity system for AlphaMessenger:
//!
//! - **Nostr identity** — secp256k1 keypair for transport-layer event signing
//!   and relay communication
//! - **MLS identity** — Ed25519 keypair with `BasicCredential` for group
//!   signing and MLS protocol operations
//!
//! Identity separation between the two layers is a critical design principle:
//! compromise of one layer does not affect the other.
//!
//! # Key Types
//!
//! - [`DualIdentity`] — The combined identity holder (start here)
//! - [`NostrIdentity`] — Nostr-specific identity
//! - [`MlsIdentity`] — MLS-specific identity
//! - [`KeyVault`] — Encrypted on-disk storage for private keys
//!
//! # Example
//!
//! ```no_run
//! use alpha_identity::{DualIdentity, KeyVault};
//! use std::path::Path;
//!
//! // Generate a new dual identity
//! let identity = DualIdentity::generate().expect("failed to generate identity");
//!
//! // Store in encrypted vault
//! let vault = KeyVault::create("my-passphrase", &identity).expect("failed to create vault");
//! vault.save(Path::new("/path/to/vault.dat")).expect("failed to save vault");
//!
//! // Later, unlock the vault
//! let restored = KeyVault::unlock("my-passphrase", Path::new("/path/to/vault.dat"))
//!     .expect("failed to unlock vault");
//! ```

pub mod dual_identity;
pub mod error;
pub mod key_material;
pub mod key_vault;
pub mod mls_identity;
pub mod nostr_identity;

// Re-export the primary types
pub use dual_identity::{DualIdentity, SerializableDualIdentity};
pub use error::{IdentityError, KeyVaultError, MlsIdentityError, NostrIdentityError};
pub use key_material::{MlsKeyPair, NostrKeyPair};
pub use key_vault::KeyVault;
pub use mls_identity::{MlsIdentity, MLS_CIPHERSUITE, SerializableMlsIdentity};
pub use nostr_identity::NostrIdentity;
