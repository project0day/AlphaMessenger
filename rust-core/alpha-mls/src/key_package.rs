//! KeyPackage lifecycle management.
//!
//! Wraps OpenMLS `KeyPackageBundle` with higher-level create / serialize /
//! consume helpers suitable for the AlphaMessenger transport layer.

use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_traits::storage::StorageProvider as _;
use openmls_traits::types::Ciphersuite;
use tls_codec::Serialize as TlsSerialize;

use crate::credential::DEFAULT_CIPHERSUITE;
use crate::error::MlsError;

/// Wrapper around an OpenMLS [`KeyPackageBundle`].
///
/// A `KeyPackageBundle` contains:
/// - The public `KeyPackage` (sent to other clients / the delivery service)
/// - The private init key (used when receiving a Welcome)
/// - The private encryption key
///
/// The bundle is automatically stored in the provider's storage when
/// created via [`AlphaKeyPackage::generate`].
#[derive(Debug, Clone)]
pub struct AlphaKeyPackage {
    bundle: KeyPackageBundle,
}

impl AlphaKeyPackage {
    /// Generate a new key package and store it in the provider's key store.
    ///
    /// The returned `AlphaKeyPackage` can be serialized and sent to the
    /// delivery service for distribution to other group members.
    pub fn generate(
        provider: &impl OpenMlsProvider,
        signer: &SignatureKeyPair,
        credential: CredentialWithKey,
    ) -> Result<Self, MlsError> {
        let bundle = KeyPackage::builder()
            .build(DEFAULT_CIPHERSUITE, provider, signer, credential)
            .map_err(|e| MlsError::Other(format!("KeyPackage generation failed: {e:?}")))?;
        Ok(Self { bundle })
    }

    /// A key package kept after use: one phone of ours joins every chat with
    /// it while it is being linked (a one-time package per chat would be needed otherwise).
    pub fn generate_last_resort(
        provider: &impl OpenMlsProvider,
        signer: &SignatureKeyPair,
        credential: CredentialWithKey,
    ) -> Result<Self, MlsError> {
        let bundle = KeyPackage::builder()
            .mark_as_last_resort()
            // The leaf must say it understands the extension it carries.
            .leaf_node_capabilities(Capabilities::builder().extensions(vec![ExtensionType::LastResort]).build())
            .build(DEFAULT_CIPHERSUITE, provider, signer, credential)
            .map_err(|e| MlsError::Other(format!("KeyPackage generation failed: {e:?}")))?;
        Ok(Self { bundle })
    }

    /// Generate a key package with a specific ciphersuite.
    pub fn generate_with_ciphersuite(
        ciphersuite: Ciphersuite,
        provider: &impl OpenMlsProvider,
        signer: &SignatureKeyPair,
        credential: CredentialWithKey,
    ) -> Result<Self, MlsError> {
        let bundle = KeyPackage::builder()
            .build(ciphersuite, provider, signer, credential)
            .map_err(|e| MlsError::Other(format!("KeyPackage generation failed: {e:?}")))?;
        Ok(Self { bundle })
    }

    /// TLS-serialize the public key package for publishing to the
    /// delivery service or sending to a peer.
    pub fn key_package_bytes(&self) -> Result<Vec<u8>, MlsError> {
        self.bundle
            .key_package()
            .tls_serialize_detached()
            .map_err(|e| MlsError::TlsCodec(format!("KeyPackage serialization failed: {e:?}")))
    }

    /// Compute the hash reference for this key package.
    ///
    /// The `KeyPackageRef` is used internally by OpenMLS to match
    /// a Welcome message to the correct key package.
    pub fn key_package_ref(
        &self,
        crypto: &impl openmls_traits::crypto::OpenMlsCrypto,
    ) -> Result<KeyPackageRef, MlsError> {
        self.bundle
            .key_package()
            .hash_ref(crypto)
            .map_err(|e| MlsError::Crypto(format!("KeyPackageRef computation failed: {e:?}")))
    }

    /// Consume a key package from storage by its hash reference.
    ///
    /// This is typically called internally by OpenMLS when processing
    /// a Welcome message, but can also be used explicitly.
    pub fn consume(
        hash_ref: &KeyPackageRef,
        provider: &impl OpenMlsProvider,
    ) -> Result<Option<KeyPackageBundle>, MlsError> {
        provider
            .storage()
            .key_package(hash_ref)
            .map_err(|e| MlsError::Storage(format!("KeyPackage lookup failed: {e:?}")))
    }

    /// Get a reference to the inner `KeyPackageBundle`.
    pub fn bundle(&self) -> &KeyPackageBundle {
        &self.bundle
    }

    /// Get a reference to the public `KeyPackage`.
    pub fn key_package(&self) -> &KeyPackage {
        self.bundle.key_package()
    }

    /// Get the ciphersuite of this key package.
    pub fn ciphersuite(&self) -> Ciphersuite {
        self.bundle.key_package().ciphersuite()
    }

    /// Deserialize and validate a `KeyPackage` from TLS-encoded bytes using
    /// the given crypto provider.
    ///
    /// This is the primary way to deserialize a key package received from
    /// the network. OpenMLS requires a crypto provider for validation.
    pub fn key_package_from_bytes_with_provider(
        bytes: &[u8],
        crypto: &impl openmls_traits::crypto::OpenMlsCrypto,
    ) -> Result<KeyPackage, MlsError> {
        let (kp_in, _rest) = KeyPackageIn::tls_deserialize_bytes(bytes)
            .map_err(|e| MlsError::TlsCodec(format!("KeyPackageIn deserialization failed: {e:?}")))?;
        kp_in
            .validate(crypto, ProtocolVersion::Mls10)
            .map_err(|e| MlsError::Other(format!("KeyPackage validation failed: {e:?}")))
    }
}
