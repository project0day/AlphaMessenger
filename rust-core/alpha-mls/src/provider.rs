//! OpenMLS provider implementations for AlphaMessenger.
//!
//! Three providers are offered:
//!
//! - **`AlphaCryptoProvider`** — wraps `openmls_rust_crypto::OpenMlsRustCrypto` which
//!   implements the full `OpenMlsProvider` trait using in-memory storage.
//!   Useful for testing and for situations where persistent storage isn't required.
//!
//! - **`SqlCipherStorageProvider`** — implements the `StorageProvider<CURRENT_VERSION>` trait
//!   backed by a SQLCipher (encrypted SQLite) database. It uses the same polymorphic
//!   `openmls_group_data` table pattern that OpenMLS's own sqlite_storage uses,
//!   delegating (de)serialization to serde_json (just like MemoryStorage does).
//!
//! - **`AlphaMlsProvider`** — combines `SqlCipherStorageProvider` (persistent storage)
//!   with the stock `openmls_rust_crypto` crypto and rand providers.
//!   This is the production provider: persistent MLS state + stock audited crypto.
//!
//! ## Crypto / Ciphersuite guarantee
//!
//! **No custom crypto.** All providers delegate `CryptoProvider` and `RandProvider`
//! to the stock `openmls_rust_crypto::OpenMlsRustCrypto`. The only customization
//! is `StorageProvider`. The ciphersuite is
//! `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (0x0001, RFC 9420 stock).

use std::sync::{Arc, Mutex};

use openmls_traits::storage::{
    traits, StorageProvider, CURRENT_VERSION,
};
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_rust_crypto::RustCrypto;
use openmls_traits::OpenMlsProvider;
use rusqlite::Connection;
use serde::Serialize;

use crate::error::MlsError;

// ---------------------------------------------------------------------------
// AlphaCryptoProvider (in-memory, for tests / ephemeral sessions)
// ---------------------------------------------------------------------------

/// Convenience wrapper around [`OpenMlsRustCrypto`].
///
/// `OpenMlsRustCrypto` already implements `OpenMlsProvider` fully
/// (crypto + random + MemoryStorage). We just re-export it under an
/// AlphaMessenger-friendly name and add a couple of helpers.
#[derive(Debug, Default)]
pub struct AlphaCryptoProvider {
    inner: OpenMlsRustCrypto,
}

impl AlphaCryptoProvider {
    /// Create a new provider backed by in-memory storage.
    pub fn new() -> Self {
        Self::default()
    }

    /// Obtain a reference to the underlying `OpenMlsRustCrypto`.
    pub fn inner(&self) -> &OpenMlsRustCrypto {
        &self.inner
    }
}

impl OpenMlsProvider for AlphaCryptoProvider {
    type CryptoProvider = <OpenMlsRustCrypto as OpenMlsProvider>::CryptoProvider;
    type RandProvider = <OpenMlsRustCrypto as OpenMlsProvider>::RandProvider;
    type StorageProvider = <OpenMlsRustCrypto as OpenMlsProvider>::StorageProvider;

    fn storage(&self) -> &Self::StorageProvider {
        self.inner.storage()
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        self.inner.crypto()
    }

    fn rand(&self) -> &Self::RandProvider {
        self.inner.rand()
    }
}

// ---------------------------------------------------------------------------
// SqlCipherStorageProvider — persistent OpenMLS state in SQLCipher
// ---------------------------------------------------------------------------

/// Error type for the SQLCipher storage provider.
#[derive(thiserror::Error, Debug)]
pub enum SqlCipherStorageError {
    /// A rusqlite operation failed.
    #[error("SQLite error: {0}")]
    Sqlite(#[from] rusqlite::Error),

    /// Serde (de)serialization failed.
    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    /// An internal / unexpected error.
    #[error("Internal error: {0}")]
    Internal(String),
}

/// A persistent `StorageProvider<CURRENT_VERSION>` backed by an encrypted SQLite
/// database (SQLCipher).
///
/// ## Table layout
///
/// All OpenMLS state is stored in a single polymorphic table:
///
/// ```sql
/// CREATE TABLE IF NOT EXISTS openmls_group_data (
///     label   TEXT    NOT NULL,
///     key     BLOB    NOT NULL,
///     value   BLOB    NOT NULL,
///     PRIMARY KEY (label, key)
/// );
/// ```
///
/// For list-like entries (own leaf nodes, proposal queue refs, epoch key pairs)
/// the `value` column holds a serde_json `Vec<Vec<u8>>` which is read, mutated,
/// and written back atomically. This mirrors the approach taken by
/// `openmls_memory_storage::MemoryStorage`.
///
/// ## Thread safety
///
/// The `Connection` is wrapped in `Arc<Mutex<..>>` so the provider is `Send + Sync`.
/// All operations take the mutex (single-writer, no concurrent reads during writes).
#[derive(Debug, Clone)]
pub struct SqlCipherStorageProvider {
    conn: Arc<Mutex<Connection>>,
}

impl SqlCipherStorageProvider {
    /// Open (or create) an encrypted SQLite database at `path` using `passphrase`.
    ///
    /// The passphrase is passed to SQLCipher via the `PRAGMA key` directive
    /// using hex notation to avoid SQL injection vulnerabilities.
    pub fn open(path: &str, passphrase: &str) -> Result<Self, SqlCipherStorageError> {
        let conn = Connection::open(path)?;
        // Use hex-encoded passphrase to avoid SQL injection via format string.
        // The x'...' hex notation is supported by SQLCipher's PRAGMA key and
        // avoids any string escaping issues (quotes, semicolons, etc.).
        let hex_key = hex::encode(passphrase.as_bytes());
        conn.execute_batch(&format!("PRAGMA key = \"x'{hex_key}'\";"))?;
        // Verify the database is readable.
        conn.execute_batch("SELECT count(*) FROM sqlite_master;")?;

        let me = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        me.ensure_schema()?;
        Ok(me)
    }

    /// Open an in-memory encrypted SQLite database (for testing).
    pub fn open_in_memory() -> Result<Self, SqlCipherStorageError> {
        let conn = Connection::open_in_memory()?;
        let me = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        me.ensure_schema()?;
        Ok(me)
    }

    /// Create the polymorphic table if it doesn't exist.
    fn ensure_schema(&self) -> Result<(), SqlCipherStorageError> {
        let conn = self.conn.lock().map_err(|e| SqlCipherStorageError::Internal(e.to_string()))?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS openmls_group_data (
                label   TEXT    NOT NULL,
                key     BLOB    NOT NULL,
                value   BLOB    NOT NULL,
                PRIMARY KEY (label, key)
             );"
        )?;
        Ok(())
    }

    // -- Low-level helpers ---------------------------------------------------

    fn write_row(&self, label: &str, key: &[u8], value: &[u8]) -> Result<(), SqlCipherStorageError> {
        let conn = self.conn.lock().map_err(|e| SqlCipherStorageError::Internal(e.to_string()))?;
        conn.execute(
            "INSERT OR REPLACE INTO openmls_group_data (label, key, value) VALUES (?1, ?2, ?3)",
            rusqlite::params![label, key, value],
        )?;
        Ok(())
    }

    fn read_row(&self, label: &str, key: &[u8]) -> Result<Option<Vec<u8>>, SqlCipherStorageError> {
        let conn = self.conn.lock().map_err(|e| SqlCipherStorageError::Internal(e.to_string()))?;
        let mut stmt = conn.prepare(
            "SELECT value FROM openmls_group_data WHERE label = ?1 AND key = ?2"
        )?;
        let mut rows = stmt.query(rusqlite::params![label, key])?;
        match rows.next()? {
            Some(row) => Ok(Some(row.get(0)?)),
            None => Ok(None),
        }
    }

    fn delete_row(&self, label: &str, key: &[u8]) -> Result<(), SqlCipherStorageError> {
        let conn = self.conn.lock().map_err(|e| SqlCipherStorageError::Internal(e.to_string()))?;
        conn.execute(
            "DELETE FROM openmls_group_data WHERE label = ?1 AND key = ?2",
            rusqlite::params![label, key],
        )?;
        Ok(())
    }

    fn append_to_list(&self, label: &str, key: &[u8], item: &[u8]) -> Result<(), SqlCipherStorageError> {
        let conn = self.conn.lock().map_err(|e| SqlCipherStorageError::Internal(e.to_string()))?;
        let mut list: Vec<Vec<u8>> = {
            let mut stmt = conn.prepare(
                "SELECT value FROM openmls_group_data WHERE label = ?1 AND key = ?2"
            )?;
            let mut rows = stmt.query(rusqlite::params![label, key])?;
            match rows.next()? {
                Some(row) => {
                    let bytes: Vec<u8> = row.get(0)?;
                    serde_json::from_slice(&bytes).unwrap_or_default()
                }
                None => vec![],
            }
        };
        list.push(item.to_vec());
        let encoded = serde_json::to_vec(&list)?;
        conn.execute(
            "INSERT OR REPLACE INTO openmls_group_data (label, key, value) VALUES (?1, ?2, ?3)",
            rusqlite::params![label, key, encoded],
        )?;
        Ok(())
    }

    fn remove_from_list(&self, label: &str, key: &[u8], item: &[u8]) -> Result<(), SqlCipherStorageError> {
        let conn = self.conn.lock().map_err(|e| SqlCipherStorageError::Internal(e.to_string()))?;
        let mut list: Vec<Vec<u8>> = {
            let mut stmt = conn.prepare(
                "SELECT value FROM openmls_group_data WHERE label = ?1 AND key = ?2"
            )?;
            let mut rows = stmt.query(rusqlite::params![label, key])?;
            match rows.next()? {
                Some(row) => {
                    let bytes: Vec<u8> = row.get(0)?;
                    serde_json::from_slice(&bytes).unwrap_or_default()
                }
                None => vec![],
            }
        };
        list.retain(|v| v.as_slice() != item);
        let encoded = serde_json::to_vec(&list)?;
        conn.execute(
            "INSERT OR REPLACE INTO openmls_group_data (label, key, value) VALUES (?1, ?2, ?3)",
            rusqlite::params![label, key, encoded],
        )?;
        Ok(())
    }

    fn read_list<V: serde::de::DeserializeOwned>(&self, label: &str, key: &[u8]) -> Result<Vec<V>, SqlCipherStorageError> {
        let conn = self.conn.lock().map_err(|e| SqlCipherStorageError::Internal(e.to_string()))?;
        let mut stmt = conn.prepare(
            "SELECT value FROM openmls_group_data WHERE label = ?1 AND key = ?2"
        )?;
        let mut rows = stmt.query(rusqlite::params![label, key])?;
        match rows.next()? {
            Some(row) => {
                let bytes: Vec<u8> = row.get(0)?;
                let items: Vec<Vec<u8>> = serde_json::from_slice(&bytes).unwrap_or_default();
                items
                    .iter()
                    .map(|b| serde_json::from_slice(b))
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(SqlCipherStorageError::from)
            }
            None => Ok(vec![]),
        }
    }

    // -- Helpers for (de)serializing keys and values -------------------------

    fn serialize_key<K: Serialize>(key: &K) -> Vec<u8> {
        serde_json::to_vec(key).unwrap_or_else(|e| {
            tracing::error!("Failed to serialize storage key: {e}");
            Vec::new()
        })
    }

    fn serialize_value<V: Serialize>(value: &V) -> Vec<u8> {
        serde_json::to_vec(value).unwrap_or_else(|e| {
            tracing::error!("Failed to serialize storage value: {e}");
            Vec::new()
        })
    }

    fn deserialize_value<V: serde::de::DeserializeOwned>(bytes: &[u8]) -> Result<V, SqlCipherStorageError> {
        serde_json::from_slice(bytes).map_err(SqlCipherStorageError::from)
    }
}

// ---------------------------------------------------------------------------
// StorageProvider<CURRENT_VERSION> implementation
// ---------------------------------------------------------------------------

// Labels — mirrors openmls_memory_storage exactly.
const KEY_PACKAGE_LABEL: &str = "KeyPackage";
const PSK_LABEL: &str = "Psk";
const ENCRYPTION_KEY_PAIR_LABEL: &str = "EncryptionKeyPair";
const SIGNATURE_KEY_PAIR_LABEL: &str = "SignatureKeyPair";
const EPOCH_KEY_PAIRS_LABEL: &str = "EpochKeyPairs";
const TREE_LABEL: &str = "Tree";
const GROUP_CONTEXT_LABEL: &str = "GroupContext";
const INTERIM_TRANSCRIPT_HASH_LABEL: &str = "InterimTranscriptHash";
const CONFIRMATION_TAG_LABEL: &str = "ConfirmationTag";
const JOIN_CONFIG_LABEL: &str = "MlsGroupJoinConfig";
const OWN_LEAF_NODES_LABEL: &str = "OwnLeafNodes";
const GROUP_STATE_LABEL: &str = "GroupState";
const QUEUED_PROPOSAL_LABEL: &str = "QueuedProposal";
const PROPOSAL_QUEUE_REFS_LABEL: &str = "ProposalQueueRefs";
const OWN_LEAF_NODE_INDEX_LABEL: &str = "OwnLeafNodeIndex";
const EPOCH_SECRETS_LABEL: &str = "EpochSecrets";
const RESUMPTION_PSK_STORE_LABEL: &str = "ResumptionPsk";
const MESSAGE_SECRETS_LABEL: &str = "MessageSecrets";

impl StorageProvider<CURRENT_VERSION> for SqlCipherStorageProvider {
    type Error = SqlCipherStorageError;

    // -- Group state writers -------------------------------------------------

    fn write_mls_join_config<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        MlsGroupJoinConfig: traits::MlsGroupJoinConfig<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        config: &MlsGroupJoinConfig,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(config);
        self.write_row(JOIN_CONFIG_LABEL, &key, &value)
    }

    fn append_own_leaf_node<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        LeafNode: traits::LeafNode<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        leaf_node: &LeafNode,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(leaf_node);
        self.append_to_list(OWN_LEAF_NODES_LABEL, &key, &value)
    }

    fn queue_proposal<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
        QueuedProposal: traits::QueuedProposal<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        proposal_ref: &ProposalRef,
        proposal: &QueuedProposal,
    ) -> Result<(), Self::Error> {
        // Write proposal to (group_id, proposal_ref) key
        let inner_key = Self::serialize_key(&(group_id, proposal_ref));
        let inner_value = Self::serialize_value(proposal);
        self.write_row(QUEUED_PROPOSAL_LABEL, &inner_key, &inner_value)?;

        // Append proposal ref to the per-group list
        let list_key = Self::serialize_key(group_id);
        let ref_value = Self::serialize_value(proposal_ref);
        self.append_to_list(PROPOSAL_QUEUE_REFS_LABEL, &list_key, &ref_value)
    }

    fn write_tree<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        TreeSync: traits::TreeSync<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        tree: &TreeSync,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(tree);
        self.write_row(TREE_LABEL, &key, &value)
    }

    fn write_interim_transcript_hash<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        InterimTranscriptHash: traits::InterimTranscriptHash<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        interim_transcript_hash: &InterimTranscriptHash,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(interim_transcript_hash);
        self.write_row(INTERIM_TRANSCRIPT_HASH_LABEL, &key, &value)
    }

    fn write_context<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        GroupContext: traits::GroupContext<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        group_context: &GroupContext,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(group_context);
        self.write_row(GROUP_CONTEXT_LABEL, &key, &value)
    }

    fn write_confirmation_tag<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ConfirmationTag: traits::ConfirmationTag<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        confirmation_tag: &ConfirmationTag,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(confirmation_tag);
        self.write_row(CONFIRMATION_TAG_LABEL, &key, &value)
    }

    fn write_group_state<
        GroupState: traits::GroupState<CURRENT_VERSION>,
        GroupId: traits::GroupId<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        group_state: &GroupState,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(group_state);
        self.write_row(GROUP_STATE_LABEL, &key, &value)
    }

    fn write_message_secrets<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        MessageSecrets: traits::MessageSecrets<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        message_secrets: &MessageSecrets,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(message_secrets);
        self.write_row(MESSAGE_SECRETS_LABEL, &key, &value)
    }

    fn write_resumption_psk_store<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ResumptionPskStore: traits::ResumptionPskStore<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        resumption_psk_store: &ResumptionPskStore,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(resumption_psk_store);
        self.write_row(RESUMPTION_PSK_STORE_LABEL, &key, &value)
    }

    fn write_own_leaf_index<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        LeafNodeIndex: traits::LeafNodeIndex<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        own_leaf_index: &LeafNodeIndex,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(own_leaf_index);
        self.write_row(OWN_LEAF_NODE_INDEX_LABEL, &key, &value)
    }

    fn write_group_epoch_secrets<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        GroupEpochSecrets: traits::GroupEpochSecrets<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        group_epoch_secrets: &GroupEpochSecrets,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        let value = Self::serialize_value(group_epoch_secrets);
        self.write_row(EPOCH_SECRETS_LABEL, &key, &value)
    }

    // -- Crypto object writers -----------------------------------------------

    fn write_signature_key_pair<
        SignaturePublicKey: traits::SignaturePublicKey<CURRENT_VERSION>,
        SignatureKeyPair: traits::SignatureKeyPair<CURRENT_VERSION>,
    >(
        &self,
        public_key: &SignaturePublicKey,
        signature_key_pair: &SignatureKeyPair,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(public_key);
        let value = Self::serialize_value(signature_key_pair);
        self.write_row(SIGNATURE_KEY_PAIR_LABEL, &key, &value)
    }

    fn write_encryption_key_pair<
        EncryptionKey: traits::EncryptionKey<CURRENT_VERSION>,
        HpkeKeyPair: traits::HpkeKeyPair<CURRENT_VERSION>,
    >(
        &self,
        public_key: &EncryptionKey,
        key_pair: &HpkeKeyPair,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(public_key);
        let value = Self::serialize_value(key_pair);
        self.write_row(ENCRYPTION_KEY_PAIR_LABEL, &key, &value)
    }

    fn write_encryption_epoch_key_pairs<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        EpochKey: traits::EpochKey<CURRENT_VERSION>,
        HpkeKeyPair: traits::HpkeKeyPair<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        epoch: &EpochKey,
        leaf_index: u32,
        key_pairs: &[HpkeKeyPair],
    ) -> Result<(), Self::Error> {
        let mut key = Self::serialize_key(group_id);
        key.extend_from_slice(&Self::serialize_key(epoch));
        key.extend_from_slice(&Self::serialize_key(&leaf_index));
        // Serialize each key pair individually then collect as a Vec<Vec<u8>>
        // to avoid requiring Clone on HpkeKeyPair
        let serialized: Vec<Vec<u8>> = key_pairs
            .iter()
            .map(|kp| Self::serialize_value(kp))
            .collect();
        let value = Self::serialize_value(&serialized);
        self.write_row(EPOCH_KEY_PAIRS_LABEL, &key, &value)
    }

    fn write_key_package<
        HashReference: traits::HashReference<CURRENT_VERSION>,
        KeyPackage: traits::KeyPackage<CURRENT_VERSION>,
    >(
        &self,
        hash_ref: &HashReference,
        key_package: &KeyPackage,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(hash_ref);
        let value = Self::serialize_value(key_package);
        self.write_row(KEY_PACKAGE_LABEL, &key, &value)
    }

    fn write_psk<
        PskId: traits::PskId<CURRENT_VERSION>,
        PskBundle: traits::PskBundle<CURRENT_VERSION>,
    >(
        &self,
        psk_id: &PskId,
        psk: &PskBundle,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(psk_id);
        let value = Self::serialize_value(psk);
        self.write_row(PSK_LABEL, &key, &value)
    }

    // -- Group state getters -------------------------------------------------

    fn mls_group_join_config<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        MlsGroupJoinConfig: traits::MlsGroupJoinConfig<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<MlsGroupJoinConfig>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(JOIN_CONFIG_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn own_leaf_nodes<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        LeafNode: traits::LeafNode<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Vec<LeafNode>, Self::Error> {
        let key = Self::serialize_key(group_id);
        self.read_list(OWN_LEAF_NODES_LABEL, &key)
    }

    fn queued_proposal_refs<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Vec<ProposalRef>, Self::Error> {
        let key = Self::serialize_key(group_id);
        self.read_list(PROPOSAL_QUEUE_REFS_LABEL, &key)
    }

    fn queued_proposals<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
        QueuedProposal: traits::QueuedProposal<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Vec<(ProposalRef, QueuedProposal)>, Self::Error> {
        let refs: Vec<ProposalRef> = {
            let key = Self::serialize_key(group_id);
            self.read_list(PROPOSAL_QUEUE_REFS_LABEL, &key)?
        };
        let mut result = Vec::new();
        for proposal_ref in refs {
            let inner_key = Self::serialize_key(&(group_id, &proposal_ref));
            match self.read_row(QUEUED_PROPOSAL_LABEL, &inner_key)? {
                Some(bytes) => {
                    let proposal: QueuedProposal = Self::deserialize_value(&bytes)?;
                    result.push((proposal_ref, proposal));
                }
                None => continue,
            }
        }
        Ok(result)
    }

    fn tree<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        TreeSync: traits::TreeSync<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<TreeSync>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(TREE_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn group_context<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        GroupContext: traits::GroupContext<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<GroupContext>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(GROUP_CONTEXT_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn interim_transcript_hash<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        InterimTranscriptHash: traits::InterimTranscriptHash<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<InterimTranscriptHash>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(INTERIM_TRANSCRIPT_HASH_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn confirmation_tag<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ConfirmationTag: traits::ConfirmationTag<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<ConfirmationTag>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(CONFIRMATION_TAG_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn group_state<
        GroupState: traits::GroupState<CURRENT_VERSION>,
        GroupId: traits::GroupId<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<GroupState>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(GROUP_STATE_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn message_secrets<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        MessageSecrets: traits::MessageSecrets<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<MessageSecrets>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(MESSAGE_SECRETS_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn resumption_psk_store<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ResumptionPskStore: traits::ResumptionPskStore<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<ResumptionPskStore>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(RESUMPTION_PSK_STORE_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn own_leaf_index<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        LeafNodeIndex: traits::LeafNodeIndex<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<LeafNodeIndex>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(OWN_LEAF_NODE_INDEX_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn group_epoch_secrets<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        GroupEpochSecrets: traits::GroupEpochSecrets<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<Option<GroupEpochSecrets>, Self::Error> {
        let key = Self::serialize_key(group_id);
        match self.read_row(EPOCH_SECRETS_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    // -- Crypto object getters -----------------------------------------------

    fn signature_key_pair<
        SignaturePublicKey: traits::SignaturePublicKey<CURRENT_VERSION>,
        SignatureKeyPair: traits::SignatureKeyPair<CURRENT_VERSION>,
    >(
        &self,
        public_key: &SignaturePublicKey,
    ) -> Result<Option<SignatureKeyPair>, Self::Error> {
        let key = Self::serialize_key(public_key);
        match self.read_row(SIGNATURE_KEY_PAIR_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn encryption_key_pair<
        HpkeKeyPair: traits::HpkeKeyPair<CURRENT_VERSION>,
        EncryptionKey: traits::EncryptionKey<CURRENT_VERSION>,
    >(
        &self,
        public_key: &EncryptionKey,
    ) -> Result<Option<HpkeKeyPair>, Self::Error> {
        let key = Self::serialize_key(public_key);
        match self.read_row(ENCRYPTION_KEY_PAIR_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn encryption_epoch_key_pairs<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        EpochKey: traits::EpochKey<CURRENT_VERSION>,
        HpkeKeyPair: traits::HpkeKeyPair<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        epoch: &EpochKey,
        leaf_index: u32,
    ) -> Result<Vec<HpkeKeyPair>, Self::Error> {
        let mut key = Self::serialize_key(group_id);
        key.extend_from_slice(&Self::serialize_key(epoch));
        key.extend_from_slice(&Self::serialize_key(&leaf_index));
        match self.read_row(EPOCH_KEY_PAIRS_LABEL, &key)? {
            Some(bytes) => Self::deserialize_value(&bytes),
            None => Ok(vec![]),
        }
    }

    fn key_package<
        KeyPackageRef: traits::HashReference<CURRENT_VERSION>,
        KeyPackage: traits::KeyPackage<CURRENT_VERSION>,
    >(
        &self,
        hash_ref: &KeyPackageRef,
    ) -> Result<Option<KeyPackage>, Self::Error> {
        let key = Self::serialize_key(hash_ref);
        match self.read_row(KEY_PACKAGE_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    fn psk<PskBundle: traits::PskBundle<CURRENT_VERSION>, PskId: traits::PskId<CURRENT_VERSION>>(
        &self,
        psk_id: &PskId,
    ) -> Result<Option<PskBundle>, Self::Error> {
        let key = Self::serialize_key(psk_id);
        match self.read_row(PSK_LABEL, &key)? {
            Some(bytes) => Ok(Some(Self::deserialize_value(&bytes)?)),
            None => Ok(None),
        }
    }

    // -- Deleters for group state --------------------------------------------

    fn remove_proposal<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        proposal_ref: &ProposalRef,
    ) -> Result<(), Self::Error> {
        // Remove from the refs list
        let list_key = Self::serialize_key(group_id);
        let ref_value = Self::serialize_value(proposal_ref);
        self.remove_from_list(PROPOSAL_QUEUE_REFS_LABEL, &list_key, &ref_value)?;

        // Remove the proposal itself
        let inner_key = Self::serialize_key(&(group_id, proposal_ref));
        self.delete_row(QUEUED_PROPOSAL_LABEL, &inner_key)
    }

    fn delete_own_leaf_nodes<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(OWN_LEAF_NODES_LABEL, &key)
    }

    fn delete_group_config<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(JOIN_CONFIG_LABEL, &key)
    }

    fn delete_tree<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(TREE_LABEL, &key)
    }

    fn delete_confirmation_tag<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(CONFIRMATION_TAG_LABEL, &key)
    }

    fn delete_group_state<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(GROUP_STATE_LABEL, &key)
    }

    fn delete_context<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(GROUP_CONTEXT_LABEL, &key)
    }

    fn delete_interim_transcript_hash<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(INTERIM_TRANSCRIPT_HASH_LABEL, &key)
    }

    fn delete_message_secrets<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(MESSAGE_SECRETS_LABEL, &key)
    }

    fn delete_all_resumption_psk_secrets<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(RESUMPTION_PSK_STORE_LABEL, &key)
    }

    fn delete_own_leaf_index<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(OWN_LEAF_NODE_INDEX_LABEL, &key)
    }

    fn delete_group_epoch_secrets<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(group_id);
        self.delete_row(EPOCH_SECRETS_LABEL, &key)
    }

    fn clear_proposal_queue<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> Result<(), Self::Error> {
        // Get all refs, then delete each proposal, then delete the refs list
        let refs: Vec<ProposalRef> = {
            let key = Self::serialize_key(group_id);
            self.read_list(PROPOSAL_QUEUE_REFS_LABEL, &key)?
        };
        for proposal_ref in &refs {
            let inner_key = Self::serialize_key(&(group_id, proposal_ref));
            self.delete_row(QUEUED_PROPOSAL_LABEL, &inner_key)?;
        }
        let list_key = Self::serialize_key(group_id);
        self.delete_row(PROPOSAL_QUEUE_REFS_LABEL, &list_key)
    }

    // -- Deleters for crypto objects -----------------------------------------

    fn delete_signature_key_pair<SignaturePublicKey: traits::SignaturePublicKey<CURRENT_VERSION>>(
        &self,
        public_key: &SignaturePublicKey,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(public_key);
        self.delete_row(SIGNATURE_KEY_PAIR_LABEL, &key)
    }

    fn delete_encryption_key_pair<EncryptionKey: traits::EncryptionKey<CURRENT_VERSION>>(
        &self,
        public_key: &EncryptionKey,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(public_key);
        self.delete_row(ENCRYPTION_KEY_PAIR_LABEL, &key)
    }

    fn delete_encryption_epoch_key_pairs<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        EpochKey: traits::EpochKey<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        epoch: &EpochKey,
        leaf_index: u32,
    ) -> Result<(), Self::Error> {
        let mut key = Self::serialize_key(group_id);
        key.extend_from_slice(&Self::serialize_key(epoch));
        key.extend_from_slice(&Self::serialize_key(&leaf_index));
        self.delete_row(EPOCH_KEY_PAIRS_LABEL, &key)
    }

    fn delete_key_package<KeyPackageRef: traits::HashReference<CURRENT_VERSION>>(
        &self,
        hash_ref: &KeyPackageRef,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(hash_ref);
        self.delete_row(KEY_PACKAGE_LABEL, &key)
    }

    fn delete_psk<PskKey: traits::PskId<CURRENT_VERSION>>(
        &self,
        psk_id: &PskKey,
    ) -> Result<(), Self::Error> {
        let key = Self::serialize_key(psk_id);
        self.delete_row(PSK_LABEL, &key)
    }
}

// ---------------------------------------------------------------------------
// AlphaMlsProvider — production provider: SQLCipher storage + stock crypto/rand
// ---------------------------------------------------------------------------

/// Production-grade `OpenMlsProvider` that combines:
///
/// - **Crypto**: stock `openmls_rust_crypto` (audited, RFC 9420 compliant)
/// - **Random**: stock `openmls_rust_crypto` (OS-level CSPRNG)
/// - **Storage**: `SqlCipherStorageProvider` (encrypted, persistent SQLite)
///
/// This is the provider you should use in the real application. It persists
/// all MLS group state to an encrypted database while using the unmodified,
/// audited OpenMLS crypto implementation.
///
/// # Ciphersuite
///
/// `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (0x0001) — stock RFC 9420.
/// No custom ciphersuite, no XChaCha20 in MLS.
pub struct AlphaMlsProvider {
    crypto: <OpenMlsRustCrypto as OpenMlsProvider>::CryptoProvider,
    rand: <OpenMlsRustCrypto as OpenMlsProvider>::RandProvider,
    storage: SqlCipherStorageProvider,
}

impl AlphaMlsProvider {
    /// Create a new production provider backed by a SQLCipher database at `path`.
    ///
    /// The `passphrase` is used as the SQLCipher encryption key.
    /// Crypto and random providers are freshly instantiated from the stock
    /// `openmls_rust_crypto::RustCrypto` (each gets its own CSPRNG seeded from OS entropy).
    pub fn open(path: &str, passphrase: &str) -> Result<Self, SqlCipherStorageError> {
        Ok(Self {
            crypto: RustCrypto::default(),
            rand: RustCrypto::default(),
            storage: SqlCipherStorageProvider::open(path, passphrase)?,
        })
    }

    /// Create a new production provider backed by an in-memory SQLite database.
    ///
    /// Useful for testing without touching the filesystem.
    pub fn open_in_memory() -> Result<Self, SqlCipherStorageError> {
        Ok(Self {
            crypto: RustCrypto::default(),
            rand: RustCrypto::default(),
            storage: SqlCipherStorageProvider::open_in_memory()?,
        })
    }

    /// Get a reference to the underlying SQLCipher storage provider.
    pub fn sqlcipher_storage(&self) -> &SqlCipherStorageProvider {
        &self.storage
    }
}

impl OpenMlsProvider for AlphaMlsProvider {
    type CryptoProvider = <OpenMlsRustCrypto as OpenMlsProvider>::CryptoProvider;
    type RandProvider = <OpenMlsRustCrypto as OpenMlsProvider>::RandProvider;
    type StorageProvider = SqlCipherStorageProvider;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        &self.crypto
    }

    fn rand(&self) -> &Self::RandProvider {
        &self.rand
    }
}

// ---------------------------------------------------------------------------
// Conversions
// ---------------------------------------------------------------------------

impl From<SqlCipherStorageError> for MlsError {
    fn from(e: SqlCipherStorageError) -> Self {
        MlsError::Storage(e.to_string())
    }
}
