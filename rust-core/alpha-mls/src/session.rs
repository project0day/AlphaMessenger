//! High-level MLS session manager.
//!
//! [`MlsSessionManager`] is the top-level entry point for MLS operations
//! in AlphaMessenger. It manages multiple MLS groups simultaneously
//! and provides a simple interface for the transport layer.
//!
//! # Sender Attribution
//!
//! When [`MlsSessionManager::receive`] returns a
//! [`ReceivedMessage::Application`], the `sender` field is populated
//! from the MLS layer, providing cryptographically-authenticated
//! author attribution.

use std::collections::HashMap;

use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::OpenMlsProvider;

use crate::credential::AlphaCredential;
use crate::epoch_manager::EpochTracker;
use crate::error::MlsError;
use crate::group::AlphaGroup;
use crate::key_package::AlphaKeyPackage;
use crate::message::{GroupMember, ReceivedMessage};

/// Result of creating a direct chat: (group_id, commit_bytes, welcome_bytes).
pub type DirectChatResult = Result<(Vec<u8>, Vec<u8>, Vec<u8>), MlsError>;

/// Result of adding a member: (commit_bytes, welcome_bytes).
pub type AddMemberResult = Result<(Vec<u8>, Vec<u8>), MlsError>;

/// Exporter label for the transport keys (routing tag + sealing key per epoch).
pub const TRANSPORT_EXPORTER_LABEL: &str = "alpha/transport/v2";

/// Called with `(group_id, epoch, exporter_secret)` whenever a group enters a
/// new epoch or is (re)loaded, so the transport can derive that epoch's keys.
pub type EpochListener = Box<dyn Fn(&[u8], u64, &[u8]) + Send + Sync>;

/// Manages multiple MLS groups for a single AlphaMessenger identity.
///
/// The session manager owns:
/// - A reference to the OpenMLS provider (crypto + storage)
/// - The local identity's signer and credential
/// - An in-memory cache of active [`AlphaGroup`] instances
/// - An [`EpochTracker`] for epoch validation
pub struct MlsSessionManager<P: OpenMlsProvider> {
    provider: P,
    signer: SignatureKeyPair,
    credential: AlphaCredential,
    groups: HashMap<Vec<u8>, AlphaGroup>,
    /// Groups where we act under a separate persona instead of our identity:
    /// its own signing key and credential, used for every operation in that
    /// group only. Nothing in such a group links back to our identity.
    personas: HashMap<Vec<u8>, Persona>,
    epoch_tracker: EpochTracker,
    epoch_listener: Option<EpochListener>,
}

/// A per-group MLS identity: signer + credential that exist for one group only.
pub struct Persona {
    pub signer: SignatureKeyPair,
    pub credential: AlphaCredential,
}

impl Persona {
    /// A fresh persona: new signing key, random credential identity.
    pub fn generate() -> Result<Self, MlsError> {
        let signer = SignatureKeyPair::new(crate::credential::DEFAULT_CIPHERSUITE.signature_algorithm())
            .map_err(|e| MlsError::Crypto(format!("persona signer: {e:?}")))?;
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
        let identity = hex::encode(id);
        let credential = AlphaCredential::new(identity.as_bytes(), &signer)?;
        Ok(Self { signer, credential })
    }

    /// A fresh persona whose leaf is signed by its own Nostr key.
    pub fn generate_bound(keys: &nostr::Keys) -> Result<Self, MlsError> {
        let signer = SignatureKeyPair::new(crate::credential::DEFAULT_CIPHERSUITE.signature_algorithm())
            .map_err(|e| MlsError::Crypto(format!("persona signer: {e:?}")))?;
        let credential = AlphaCredential::new(&crate::leaf_binding::bind(keys, signer.public()), &signer)?;
        Ok(Self { signer, credential })
    }

    /// Restore from the form produced by [`Persona::to_json`].
    pub fn from_json(json: &str) -> Result<Self, MlsError> {
        let credential: AlphaCredential = serde_json::from_str(json)
            .map_err(|e| MlsError::Serialization(e.to_string()))?;
        let signer = credential.signer()?;
        Ok(Self { signer, credential })
    }

    /// Serialized persona (contains the private key — store encrypted only).
    pub fn to_json(&self) -> Result<String, MlsError> {
        serde_json::to_string(&self.credential).map_err(|e| MlsError::Serialization(e.to_string()))
    }

    pub fn public_key(&self) -> Vec<u8> {
        self.signer.public().to_vec()
    }
}

impl<P: OpenMlsProvider> MlsSessionManager<P> {
    /// Create a new session manager.
    ///
    /// The `signer` and `credential` represent this client's MLS identity.
    /// They are stored for the lifetime of the session and used for all
    /// group operations.
    pub fn new(provider: P, signer: SignatureKeyPair, credential: AlphaCredential) -> Self {
        Self {
            provider,
            signer,
            credential,
            groups: HashMap::new(),
            personas: HashMap::new(),
            epoch_tracker: EpochTracker::new(),
            epoch_listener: None,
        }
    }

    /// Install the epoch listener and report every group already loaded.
    pub fn set_epoch_listener(&mut self, listener: EpochListener) {
        self.epoch_listener = Some(listener);
        let ids: Vec<Vec<u8>> = self.groups.keys().cloned().collect();
        for id in ids {
            self.notify_epoch(&id);
        }
    }

    /// Report the current epoch's exporter secret of `group_id` to the listener.
    fn notify_epoch(&self, group_id: &[u8]) {
        let (Some(listener), Some(group)) = (&self.epoch_listener, self.groups.get(group_id)) else { return };
        match group.export_secret(&self.provider, TRANSPORT_EXPORTER_LABEL, b"", 32) {
            Ok(mut secret) => {
                listener(group_id, group.epoch(), &secret);
                zeroize::Zeroize::zeroize(&mut secret);
            }
            Err(e) => tracing::warn!("transport exporter failed: {e}"),
        }
    }

    /// Get a reference to the underlying provider.
    pub fn provider(&self) -> &P {
        &self.provider
    }

    /// Replace the session's signing key.
    ///
    /// Needed after a backup-restore: the restored backup carries the original
    /// signer bound to our leaf in the group. Without the swap, peers reject
    /// every outgoing message with `ValidationError(InvalidSignature)`.
    pub fn set_signer(&mut self, signer: SignatureKeyPair) {
        self.signer = signer;
    }

    /// Replace the whole MLS identity (signer + credential).
    ///
    /// Needed when the account identity is swapped in a running core: groups
    /// and KeyPackages made afterwards must use the key that gets persisted.
    pub fn set_identity(&mut self, signer: SignatureKeyPair, credential: AlphaCredential) {
        self.signer = signer;
        self.credential = credential;
    }

    /// Whether our leaf in `group_id` carries a key other than the one we sign with.
    /// Every message we send there would then fail verification at peers.
    pub fn signer_mismatch(&self, group_id: &[u8]) -> bool {
        let Some(group) = self.groups.get(group_id) else { return false };
        match group.inner().own_leaf_node() {
            Some(leaf) => leaf.signature_key().as_slice() != self.signer_for(group_id).public(),
            None => false,
        }
    }

    /// The public bytes of the session's current signing key.
    pub fn signer_public(&self) -> Vec<u8> {
        self.signer.public().to_vec()
    }

    /// Get a reference to the credential.
    pub fn credential(&self) -> &AlphaCredential {
        &self.credential
    }

    /// Act in `group_id` under `persona` from now on.
    pub fn set_persona(&mut self, group_id: &[u8], persona: Persona) {
        self.personas.insert(group_id.to_vec(), persona);
    }

    /// Whether we act in `group_id` under a persona.
    pub fn has_persona(&self, group_id: &[u8]) -> bool {
        self.personas.contains_key(group_id)
    }

    /// The signing key we use in `group_id`: the persona's, else our own.
    fn signer_for(&self, group_id: &[u8]) -> &SignatureKeyPair {
        self.personas.get(group_id).map(|p| &p.signer).unwrap_or(&self.signer)
    }

    /// Public signing key we use in `group_id` (our leaf there).
    pub fn signer_public_for(&self, group_id: &[u8]) -> Vec<u8> {
        self.signer_for(group_id).public().to_vec()
    }

    /// Create a group in which we are `persona`, not our identity.
    pub fn create_group_as(&mut self, persona: Persona) -> Result<Vec<u8>, MlsError> {
        let cred_with_key = persona.credential.credential_with_key()?;
        let group = AlphaGroup::create_group(&self.provider, &persona.signer, cred_with_key)?;
        let group_id = group.group_id();
        self.epoch_tracker.advance_epoch(&group_id, group.epoch());
        self.groups.insert(group_id.clone(), group);
        self.personas.insert(group_id.clone(), persona);
        self.notify_epoch(&group_id);
        Ok(group_id)
    }

    /// A KeyPackage for `persona` — handed over privately, never published.
    pub fn generate_key_package_as(&self, persona: &Persona) -> Result<AlphaKeyPackage, MlsError> {
        let cred_with_key = persona.credential.credential_with_key()?;
        AlphaKeyPackage::generate(&self.provider, &persona.signer, cred_with_key)
    }

    /// Create a 1:1 (direct) chat with a peer.
    ///
    /// `peer_key_package_bytes` is the TLS-serialized KeyPackage of
    /// the peer, obtained from the delivery service.
    ///
    /// Returns `(group_id, commit_bytes, welcome_bytes)`.
    /// - `commit_bytes` should be sent to the DS for broadcast
    /// - `welcome_bytes` should be sent to the DS for delivery to the peer
    pub fn create_direct_chat(
        &mut self,
        peer_key_package_bytes: &[u8],
    ) -> DirectChatResult {
        self.create_direct_chat_with(&[peer_key_package_bytes.to_vec()])
    }

    /// [`Self::create_direct_chat`] with every phone of the peer (and our
    /// other phones) at once: one Welcome serves them all.
    pub fn create_direct_chat_with(&mut self, key_packages: &[Vec<u8>]) -> DirectChatResult {
        let kps = key_packages.iter()
            .map(|b| AlphaKeyPackage::key_package_from_bytes_with_provider(b, self.provider.crypto()))
            .collect::<Result<Vec<_>, _>>()?;

        let cred_with_key = self.credential.credential_with_key()?;

        let (group, commit_bytes, welcome_bytes) = AlphaGroup::create_direct(
            &self.provider,
            &self.signer,
            cred_with_key,
            kps,
        )?;

        let group_id = group.group_id();
        self.epoch_tracker.advance_epoch(&group_id, group.epoch());
        self.groups.insert(group_id.clone(), group);
        self.notify_epoch(&group_id);

        Ok((group_id, commit_bytes, welcome_bytes))
    }

    /// Create a new group with the caller as the sole member.
    ///
    /// Other members can be added later via [`Self::add_member_to_group`].
    ///
    /// Returns the `GroupId` of the created group.
    pub fn create_group(&mut self) -> Result<Vec<u8>, MlsError> {
        let cred_with_key = self.credential.credential_with_key()?;

        let group = AlphaGroup::create_group(
            &self.provider,
            &self.signer,
            cred_with_key,
        )?;

        let group_id = group.group_id();
        self.epoch_tracker.advance_epoch(&group_id, group.epoch());
        self.groups.insert(group_id.clone(), group);
        self.notify_epoch(&group_id);

        Ok(group_id)
    }

    /// Join a chat from a Welcome message.
    ///
    /// `welcome_bytes` is the TLS-serialized Welcome obtained
    /// from the delivery service.
    ///
    /// Returns the `GroupId` of the joined group.
    /// Read a re-key Welcome for a group we hold, changing nothing yet.
    pub fn stage_rejoin(&self, welcome_bytes: &[u8]) -> Result<crate::group::StagedJoin, MlsError> {
        AlphaGroup::stage_rejoin(&self.provider, welcome_bytes)
    }

    /// Replace a group by a staged re-join that was found authorized.
    pub fn commit_rejoin(&mut self, staged: crate::group::StagedJoin) -> Result<Vec<u8>, MlsError> {
        let group_id = staged.group_id();
        // The persona we speak as there stays ours: the re-key keeps our leaf.
        let persona = self.personas.remove(&group_id);
        if let Some(old) = self.groups.remove(&group_id) {
            old.delete(&self.provider)?;
            self.epoch_tracker.remove_group(&group_id);
        }
        let group = AlphaGroup::from_staged(&self.provider, staged)?;
        self.epoch_tracker.advance_epoch(&group_id, group.epoch());
        self.groups.insert(group_id.clone(), group);
        if let Some(p) = persona { self.personas.insert(group_id.clone(), p); }
        self.notify_epoch(&group_id);
        Ok(group_id)
    }

    pub fn join_chat(&mut self, welcome_bytes: &[u8]) -> Result<Vec<u8>, MlsError> {
        let group = AlphaGroup::join_from_welcome(&self.provider, welcome_bytes)?;
        let group_id = group.group_id();
        self.epoch_tracker.advance_epoch(&group_id, group.epoch());
        self.groups.insert(group_id.clone(), group);
        self.notify_epoch(&group_id);
        Ok(group_id)
    }

    /// Encrypt and send a message to a group.
    ///
    /// Returns the TLS-serialized ciphertext to send to the DS.
    pub fn send(&mut self, group_id: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, MlsError> {
        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::GroupNotFound)?;
        let signer = self.personas.get(group_id).map(|p| &p.signer).unwrap_or(&self.signer);
        group.send_message(&self.provider, signer, plaintext)
    }

    /// Decrypt a received message for a group.
    ///
    /// Returns the typed content of the message with sender attribution.
    ///
    /// # Sender Attribution
    ///
    /// The returned [`ReceivedMessage::Application`] includes a `sender`
    /// field with the authenticated sender identity. Use this to determine
    /// who sent the message in the UI layer.
    pub fn receive(&mut self, group_id: &[u8], ciphertext: &[u8]) -> Result<ReceivedMessage, MlsError> {
        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::GroupNotFound)?;

        let old_epoch = group.epoch();
        let result = group.receive_message(&self.provider, ciphertext)?;

        // If we got a Commit, the epoch may have advanced
        if matches!(result, ReceivedMessage::Commit) {
            let new_epoch = group.epoch();
            if new_epoch != old_epoch {
                self.epoch_tracker.advance_epoch(group_id, new_epoch);
                self.notify_epoch(group_id);
            }
        }

        Ok(result)
    }

    /// Add a member to an existing group.
    ///
    /// Returns `(commit_bytes, welcome_bytes)`.
    pub fn add_member_to_group(
        &mut self,
        group_id: &[u8],
        key_package_bytes: &[u8],
    ) -> AddMemberResult {
        let kp = AlphaKeyPackage::key_package_from_bytes_with_provider(
            key_package_bytes,
            self.provider.crypto(),
        )?;

        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::GroupNotFound)?;

        let signer = self.personas.get(group_id).map(|p| &p.signer).unwrap_or(&self.signer);
        let result = group.add_member(&self.provider, signer, kp)?;

        // Epoch advanced after add
        self.epoch_tracker.advance_epoch(group_id, group.epoch());
        self.notify_epoch(group_id);

        Ok(result)
    }

    /// [`Self::add_member_to_group`] for several leaves in one commit.
    pub fn add_members_to_group(&mut self, group_id: &[u8], key_packages: &[Vec<u8>]) -> AddMemberResult {
        let kps = key_packages.iter()
            .map(|b| AlphaKeyPackage::key_package_from_bytes_with_provider(b, self.provider.crypto()))
            .collect::<Result<Vec<_>, _>>()?;
        let group = self.groups.get_mut(group_id).ok_or(MlsError::GroupNotFound)?;
        let signer = self.personas.get(group_id).map(|p| &p.signer).unwrap_or(&self.signer);
        let result = group.add_members(&self.provider, signer, kps)?;
        self.epoch_tracker.advance_epoch(group_id, group.epoch());
        self.notify_epoch(group_id);
        Ok(result)
    }

    /// Remove a member from an existing group.
    ///
    /// Returns the TLS-serialized commit to send to the DS.
    /// Our leaf in `group_id` carries no account proof yet while our credential
    /// does: it should be re-signed. Groups under a persona keep their own leaf.
    pub fn own_leaf_needs_binding(&self, group_id: &[u8]) -> bool {
        if self.personas.contains_key(group_id) { return false; }
        let Some(group) = self.groups.get(group_id) else { return false };
        let own_bound = matches!(
            crate::leaf_binding::check(self.credential.identity(), self.signer.public()),
            crate::LeafVerdict::Bound(_)
        );
        own_bound && group.own_leaf_verdict() == Some(crate::LeafVerdict::Legacy) && !self.signer_mismatch(group_id)
    }

    /// Re-sign our leaf in `group_id` with the account-bound credential; returns the
    /// commit to send. Our signing key stays the same.
    pub fn rebind_own_leaf(&mut self, group_id: &[u8]) -> Result<Vec<u8>, MlsError> {
        let cred = self.credential.credential_with_key()?;
        let group = self.groups.get_mut(group_id).ok_or(MlsError::GroupNotFound)?;
        let commit = group.rebind_own_leaf(&self.provider, &self.signer, cred)?;
        self.epoch_tracker.advance_epoch(group_id, group.epoch());
        self.notify_epoch(group_id);
        Ok(commit)
    }

    pub fn remove_member_from_group(
        &mut self,
        group_id: &[u8],
        leaf_index: u32,
    ) -> Result<Vec<u8>, MlsError> {
        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::GroupNotFound)?;

        let signer = self.personas.get(group_id).map(|p| &p.signer).unwrap_or(&self.signer);
        let result = group.remove_member(
            &self.provider,
            signer,
            LeafNodeIndex::new(leaf_index),
        )?;

        // Epoch advanced after remove
        self.epoch_tracker.advance_epoch(group_id, group.epoch());
        self.notify_epoch(group_id);

        Ok(result)
    }

    /// [`Self::remove_member_from_group`] for several leaves in one commit.
    pub fn remove_members_from_group(&mut self, group_id: &[u8], leaves: &[u32]) -> Result<Vec<u8>, MlsError> {
        let group = self.groups.get_mut(group_id).ok_or(MlsError::GroupNotFound)?;
        let signer = self.personas.get(group_id).map(|p| &p.signer).unwrap_or(&self.signer);
        let idx: Vec<LeafNodeIndex> = leaves.iter().map(|i| LeafNodeIndex::new(*i)).collect();
        let result = group.remove_members(&self.provider, signer, &idx)?;
        self.epoch_tracker.advance_epoch(group_id, group.epoch());
        self.notify_epoch(group_id);
        Ok(result)
    }

    /// Get the list of active group IDs.
    pub fn active_groups(&self) -> Vec<Vec<u8>> {
        self.groups.keys().cloned().collect()
    }

    /// Get information about a specific group.
    ///
    /// Returns `None` if the group is not in the local cache.
    pub fn group_info(&self, group_id: &[u8]) -> Option<GroupInfoSnapshot> {
        self.groups.get(group_id).map(|g| GroupInfoSnapshot {
            group_id: g.group_id(),
            epoch: g.epoch(),
            is_active: g.is_active(),
            members: g.members(),
        })
    }

    /// Lightweight epoch lookup that does NOT enumerate members.
    /// `g.members()` can hang in an infinite ratchet-tree traversal on a
    /// corrupt/forked group; the hot gift-wrap duplicate-Welcome check only
    /// needs the epoch, so use this instead.
    pub fn group_epoch(&self, group_id: &[u8]) -> Option<u64> {
        self.groups.get(group_id).map(|g| g.epoch())
    }

    /// Get the epoch tracker for epoch validation.
    pub fn epoch_tracker(&self) -> &EpochTracker {
        &self.epoch_tracker
    }

    /// Get a mutable reference to the epoch tracker.
    pub fn epoch_tracker_mut(&mut self) -> &mut EpochTracker {
        &mut self.epoch_tracker
    }

    /// Whether this phone can still open a Welcome made with this KeyPackage (its private
    /// part is in our storage). `None`: not a KeyPackage at all.
    pub fn holds_key_package(&self, kp_bytes: &[u8]) -> Option<bool> {
        let kp = AlphaKeyPackage::key_package_from_bytes_with_provider(kp_bytes, self.provider.crypto()).ok()?;
        let hash_ref = kp.hash_ref(self.provider.crypto()).ok()?;
        let stored: Result<Option<KeyPackageBundle>, _> = openmls_traits::storage::StorageProvider::key_package(self.provider.storage(), &hash_ref);
        Some(matches!(stored, Ok(Some(_))))
    }

    /// Generate a new key package for this identity.
    ///
    /// The key package can be published to the delivery service
    /// so that other clients can add us to groups.
    pub fn generate_key_packages(&self) -> Result<AlphaKeyPackage, MlsError> {
        let cred_with_key = self.credential.credential_with_key()?;
        AlphaKeyPackage::generate(&self.provider, &self.signer, cred_with_key)
    }

    /// A key package kept after use, as ourselves (`None`) or as a persona:
    /// this phone joins all of the account's chats with it while being linked.
    pub fn generate_last_resort_key_package(&self, persona: Option<&Persona>) -> Result<AlphaKeyPackage, MlsError> {
        match persona {
            Some(p) => AlphaKeyPackage::generate_last_resort(&self.provider, &p.signer, p.credential.credential_with_key()?),
            None => AlphaKeyPackage::generate_last_resort(&self.provider, &self.signer, self.credential.credential_with_key()?),
        }
    }

    /// Persist all group states and then reload them on next access.
    ///
    /// Groups are automatically persisted by OpenMLS when you call
    /// mutating methods. This function is a no-op that exists for
    /// API symmetry — calling it simply ensures the epoch tracker
    /// state is available for serialization.
    pub fn epoch_tracker_snapshot(&self) -> Result<Vec<u8>, MlsError> {
        self.epoch_tracker.serialize()
    }

    /// Restore epoch tracker state from a previously serialized snapshot.
    pub fn restore_epoch_tracker(&mut self, bytes: &[u8]) -> Result<(), MlsError> {
        self.epoch_tracker = EpochTracker::deserialize(bytes)?;
        Ok(())
    }

    /// Load a group from storage into the in-memory cache.
    ///
    /// This is useful after a restart when groups need to be
    /// re-activated.
    pub fn load_group(&mut self, group_id: &[u8]) -> Result<bool, MlsError> {
        if self.groups.contains_key(group_id) {
            return Ok(true);
        }
        match AlphaGroup::load(&self.provider, group_id)? {
            Some(group) => {
                self.epoch_tracker.advance_epoch(group_id, group.epoch());
                self.groups.insert(group_id.to_vec(), group);
                self.notify_epoch(group_id);
                Ok(true)
            }
            None => Ok(false),
        }
    }

    /// Leave a group.
    pub fn leave_group(&mut self, group_id: &[u8]) -> Result<Vec<u8>, MlsError> {
        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::GroupNotFound)?;
        let signer = self.personas.get(group_id).map(|p| &p.signer).unwrap_or(&self.signer);
        let out = group.leave(&self.provider, signer);
        self.notify_epoch(group_id);
        out
    }

    /// Delete a group from both the cache and persistent storage.
    pub fn delete_group(&mut self, group_id: &[u8]) -> Result<(), MlsError> {
        self.personas.remove(group_id);
        if let Some(group) = self.groups.remove(group_id) {
            group.delete(&self.provider)?;
            self.epoch_tracker.remove_group(group_id);
        }
        Ok(())
    }
}

// --- Persistence helpers ---
//
// `OpenMlsRustCrypto` ships with an in-RAM `MemoryStorage` (HashMap<Vec<u8>,
// Vec<u8>>). On process kill / swipe / OOM every MLS group dies with it.
// We snapshot the underlying map to SQLCipher after every mutation and load
// it back on startup; OpenMLS already knows how to walk its own storage to
// reconstruct group state, so the only glue we add here is dumping/restoring
// the raw map.

impl MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto> {
    /// Dump the OpenMLS storage map for persisting to SQLCipher.
    pub fn snapshot_storage(&self) -> Vec<(Vec<u8>, Vec<u8>)> {
        let storage = self.provider.storage();
        let map = storage.values.read().expect("MemoryStorage poisoned");
        map.iter().map(|(k, v)| (k.clone(), v.clone())).collect()
    }

    /// Replace the OpenMLS storage map with a previously saved snapshot.
    /// Call this BEFORE `load_group` for any groups you want to re-activate.
    pub fn restore_storage(&self, entries: Vec<(Vec<u8>, Vec<u8>)>) {
        let storage = self.provider.storage();
        let mut map = storage.values.write().expect("MemoryStorage poisoned");
        map.clear();
        for (k, v) in entries {
            map.insert(k, v);
        }
    }
}

/// A snapshot of group information returned by [`MlsSessionManager::group_info`].
#[derive(Debug, Clone)]
pub struct GroupInfoSnapshot {
    /// The group ID.
    pub group_id: Vec<u8>,
    /// The current epoch.
    pub epoch: u64,
    /// Whether this group is still active.
    pub is_active: bool,
    /// The list of group members.
    pub members: Vec<GroupMember>,
}

// Convenience implementation for the default in-memory provider.
impl MlsSessionManager<OpenMlsRustCrypto> {
    /// Create a new session manager with the default in-memory provider.
    pub fn new_in_memory(signer: SignatureKeyPair, credential: AlphaCredential) -> Self {
        Self::new(OpenMlsRustCrypto::default(), signer, credential)
    }
}

/// The bytes by which a group's own entries in the MLS storage can be found:
/// storage keys carry the group id in this form.
pub fn group_storage_marker(group_id: &[u8]) -> Vec<u8> {
    serde_json::to_vec(&openmls::prelude::GroupId::from_slice(group_id)).unwrap_or_default()
}
