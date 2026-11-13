//! High-level MLS group management.
//!
//! [`AlphaGroup`] wraps OpenMLS `MlsGroup` with a simplified API that
//! handles the common patterns used in AlphaMessenger:
//!
//! - Direct (1:1) chat creation
//! - Multi-person group creation
//! - Adding / removing members
//! - Encrypting and decrypting application messages
//! - Exporting secrets for cross-protocol verification
//! - Persistence via the OpenMLS `StorageProvider` trait
//!
//! # Sender Attribution (C2)
//!
//! When [`AlphaGroup::receive_message`] returns a
//! [`ReceivedMessage::Application`], the `sender` field is populated
//! from the MLS `ProcessedMessage` sender info. This ensures that
//! message authorship is cryptographically attributed through the
//! MLS layer — **not** inferred from the Nostr transport.

use openmls::prelude::*;

use crate::credential::DEFAULT_CIPHERSUITE;
use crate::error::MlsError;
use crate::leaf_binding::{self, LeafVerdict};
use crate::message::{GroupMember, ReceivedMessage, SenderId};
use crate::padding::MessagePadder;

fn verdict_of(leaf: &LeafNode) -> LeafVerdict {
    leaf_binding::check(leaf.credential().serialized_content(), leaf.signature_key().as_slice())
}

// Note: We define the default join/create config inline rather than storing them
// as crate-level constants, because the OpenMLS config builder types don't
// implement Copy/Clone consistently.

/// Default join configuration for AlphaMessenger groups.
///
/// We use `PURE_CIPHERTEXT_WIRE_FORMAT_POLICY` so that all handshake
/// messages are encrypted (not just application messages).
fn default_join_config() -> MlsGroupJoinConfig {
    MlsGroupJoinConfig::builder()
        .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
        .use_ratchet_tree_extension(true)
        .sender_ratchet_configuration(alpha_sender_ratchet())
        .build()
}

/// A Welcome read but not applied: what it would make us join.
pub struct StagedJoin {
    staged: StagedWelcome,
}

impl StagedJoin {
    pub fn group_id(&self) -> Vec<u8> {
        self.staged.group_context().group_id().as_slice().to_vec()
    }

    pub fn epoch(&self) -> u64 {
        self.staged.group_context().epoch().as_u64()
    }

    pub fn members(&self) -> Vec<GroupMember> {
        self.staged.members()
            .map(|m| GroupMember {
                leaf_index: m.index.u32(),
                credential_bytes: m.credential.serialized_content().to_vec(),
                signature_key: m.signature_key,
                encryption_key: m.encryption_key,
            })
            .collect()
    }

    /// The member who made the Welcome: (credential, signature key).
    pub fn sender(&self) -> Option<(Vec<u8>, Vec<u8>)> {
        let leaf = self.staged.welcome_sender().ok()?;
        Some((leaf.credential().serialized_content().to_vec(), leaf.signature_key().as_slice().to_vec()))
    }
}

/// Out-of-order delivery tolerance for the sender ratchet.
///
/// Over Nostr, application messages arrive HEAVILY shuffled: each event is
/// delivered by several relays in different orders, and catch-up pulls them in
/// unsorted batches. OpenMLS's default tolerance is only **5** generations — so
/// in a burst of N messages most fall outside the window and decrypt as
/// `TooDistantInThePast`, i.e. are lost forever (observed: 1-2 of 9 arriving).
///
/// We keep a large window of past message keys so out-of-order messages still
/// decrypt. Memory cost is O(tolerance) ratchet keys per epoch — a few thousand
/// is negligible. `maximum_forward_distance` is left generous too.
fn alpha_sender_ratchet() -> SenderRatchetConfiguration {
    SenderRatchetConfiguration::new(2000, 5000)
}

/// Default create configuration for AlphaMessenger groups.
#[allow(dead_code)]
fn default_create_config() -> MlsGroupCreateConfig {
    MlsGroupCreateConfig::builder()
        .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
        .use_ratchet_tree_extension(true)
        .sender_ratchet_configuration(alpha_sender_ratchet())
        .ciphersuite(DEFAULT_CIPHERSUITE)
        .build()
}

/// High-level wrapper around an OpenMLS [`MlsGroup`].
///
/// An `AlphaGroup` is the primary interface for MLS group operations
/// in AlphaMessenger. It manages the lifecycle of a single MLS group,
/// including creation, membership changes, message encryption/decryption,
/// and persistence.
pub struct AlphaGroup {
    group: MlsGroup,
}

impl AlphaGroup {
    // =======================================================================
    // Group creation
    // =======================================================================

    /// Create a 2-person MLS group (1:1 / direct chat).
    ///
    /// This creates a new group with the caller as the first member,
    /// then immediately adds the peer using their key package.
    ///
    /// Returns `(AlphaGroup, commit_bytes, welcome_bytes)` where:
    /// - `commit_bytes` is the TLS-serialized commit to send to the DS
    /// - `welcome_bytes` is the TLS-serialized Welcome to send to the peer
    pub fn create_direct(
        provider: &impl OpenMlsProvider,
        signer: &impl openmls_traits::signatures::Signer,
        credential: CredentialWithKey,
        peer_key_packages: Vec<KeyPackage>,
    ) -> Result<(Self, Vec<u8>, Vec<u8>), MlsError> {
        if peer_key_packages.iter().any(|kp| verdict_of(kp.leaf_node()) == LeafVerdict::Forged) {
            return Err(MlsError::ForgedLeaf("in the KeyPackage".to_string()));
        }
        // Step 1: Create the group with only ourselves
        let mut group = MlsGroup::builder()
            .with_group_id(GroupId::random(provider.rand()))
            .with_wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
            .use_ratchet_tree_extension(true)
            .sender_ratchet_configuration(alpha_sender_ratchet())
            .ciphersuite(DEFAULT_CIPHERSUITE)
            .build(provider, signer, credential)
            .map_err(|e| MlsError::GroupCreation(format!("{e:?}")))?;

        // Merge our own pending commit (the initial standalone group)
        group
            .merge_pending_commit(provider)
            .map_err(|e| MlsError::GroupCreation(format!("Merge initial commit: {e:?}")))?;

        // Step 2: Add the peer (every phone of theirs, and our other ones)
        let (commit_msg, welcome_msg, _group_info) = group
            .add_members(provider, signer, &peer_key_packages)
            .map_err(|e| MlsError::AddMember(format!("{e:?}")))?;

        // Merge the add commit
        group
            .merge_pending_commit(provider)
            .map_err(|e| MlsError::GroupCreation(format!("Merge add commit: {e:?}")))?;

        // Serialize commit and welcome for the wire
        let commit_bytes = commit_msg
            .to_bytes()
            .map_err(|e| MlsError::TlsCodec(format!("Commit serialization: {e:?}")))?;

        let welcome_bytes = welcome_msg
            .to_bytes()
            .map_err(|e| MlsError::TlsCodec(format!("Welcome serialization: {e:?}")))?;

        Ok((Self { group }, commit_bytes, welcome_bytes))
    }

    /// Create a new group with the caller as the sole member.
    ///
    /// Other members can be added later via [`Self::add_member`].
    pub fn create_group(
        provider: &impl OpenMlsProvider,
        signer: &impl openmls_traits::signatures::Signer,
        credential: CredentialWithKey,
    ) -> Result<Self, MlsError> {
        let mut group = MlsGroup::builder()
            .with_group_id(GroupId::random(provider.rand()))
            .with_wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
            .use_ratchet_tree_extension(true)
            .sender_ratchet_configuration(alpha_sender_ratchet())
            .ciphersuite(DEFAULT_CIPHERSUITE)
            .build(provider, signer, credential)
            .map_err(|e| MlsError::GroupCreation(format!("{e:?}")))?;

        // Merge the initial commit
        group
            .merge_pending_commit(provider)
            .map_err(|e| MlsError::GroupCreation(format!("Merge initial commit: {e:?}")))?;

        Ok(Self { group })
    }

    /// Wrap an already-loaded [`MlsGroup`] (e.g. from `MlsGroup::load`).
    /// Used when restoring groups from persisted storage on app start.
    pub fn from_mls_group(group: MlsGroup) -> Self {
        Self { group }
    }

    // =======================================================================
    // Joining
    // =======================================================================

    /// Join a group from a Welcome message.
    ///
    /// `welcome_bytes` should be the TLS-serialized `MlsMessageOut`
    /// containing a Welcome (as produced by the peer's `add_members`).
    ///
    /// The ratchet tree is expected to be embedded in the Welcome via
    /// the `use_ratchet_tree_extension` flag.
    /// Read a Welcome for a group we already hold (a re-key) without touching
    /// it: the result is only inspected until [Self::from_staged] applies it.
    pub fn stage_rejoin(
        provider: &impl OpenMlsProvider,
        welcome_bytes: &[u8],
    ) -> Result<StagedJoin, MlsError> {
        let (msg_in, _rest) = MlsMessageIn::tls_deserialize_bytes(welcome_bytes)
            .map_err(|e| MlsError::TlsCodec(format!("Welcome deserialization: {e:?}")))?;
        let MlsMessageBodyIn::Welcome(welcome) = msg_in.extract() else {
            return Err(MlsError::WelcomeJoin("Expected Welcome, got a different message type".to_string()));
        };
        let staged = StagedWelcome::build_from_welcome(provider, &default_join_config(), welcome)
            .map_err(|e| MlsError::WelcomeJoin(format!("StagedWelcome: {e:?}")))?
            .replace_old_group()
            .build()
            .map_err(|e| MlsError::WelcomeJoin(format!("StagedWelcome: {e:?}")))?;
        if staged.members().any(|m| {
            leaf_binding::check(m.credential.serialized_content(), m.signature_key.as_slice()) == LeafVerdict::Forged
        }) {
            return Err(MlsError::ForgedLeaf("in the Welcome's tree".to_string()));
        }
        Ok(StagedJoin { staged })
    }

    /// Apply a staged re-join; the group's stored state is replaced.
    pub fn from_staged(provider: &impl OpenMlsProvider, staged: StagedJoin) -> Result<Self, MlsError> {
        let group = staged.staged
            .into_group(provider)
            .map_err(|e| MlsError::WelcomeJoin(format!("into_group: {e:?}")))?;
        Ok(Self { group })
    }

    pub fn join_from_welcome(
        provider: &impl OpenMlsProvider,
        welcome_bytes: &[u8],
    ) -> Result<Self, MlsError> {
        let (msg_in, _rest) = MlsMessageIn::tls_deserialize_bytes(welcome_bytes)
            .map_err(|e| MlsError::TlsCodec(format!("Welcome deserialization: {e:?}")))?;

        let welcome = match msg_in.extract() {
            MlsMessageBodyIn::Welcome(w) => w,
            _ => {
                return Err(MlsError::WelcomeJoin(
                    "Expected Welcome, got a different message type".to_string()
                ))
            }
        };

        let join_config = default_join_config();

        // Use the StagedWelcome API to join with embedded ratchet tree extension
        let staged_welcome = StagedWelcome::new_from_welcome(
            provider,
            &join_config,
            welcome,
            None, // ratchet tree is in the extension
        )
        .map_err(|e| MlsError::WelcomeJoin(format!("StagedWelcome: {e:?}")))?;

        // A group with a forged leaf is not joined: whoever made it claims
        // to be someone they cannot prove to be.
        if staged_welcome.members().any(|m| {
            leaf_binding::check(m.credential.serialized_content(), m.signature_key.as_slice()) == LeafVerdict::Forged
        }) {
            return Err(MlsError::ForgedLeaf("in the Welcome's tree".to_string()));
        }

        let group = staged_welcome
            .into_group(provider)
            .map_err(|e| MlsError::WelcomeJoin(format!("into_group: {e:?}")))?;

        Ok(Self { group })
    }

    // =======================================================================
    // Membership operations
    // =======================================================================

    /// Add a member to the group.
    ///
    /// Returns `(commit_bytes, welcome_bytes)`.
    /// The `welcome_bytes` must be delivered to the new member
    /// (typically via the delivery service).
    pub fn add_member(
        &mut self,
        provider: &impl OpenMlsProvider,
        signer: &impl openmls_traits::signatures::Signer,
        key_package: KeyPackage,
    ) -> Result<(Vec<u8>, Vec<u8>), MlsError> {
        self.add_members(provider, signer, vec![key_package])
    }

    /// Add several leaves in one commit (a person's phones): one Welcome for all.
    pub fn add_members(
        &mut self,
        provider: &impl OpenMlsProvider,
        signer: &impl openmls_traits::signatures::Signer,
        key_packages: Vec<KeyPackage>,
    ) -> Result<(Vec<u8>, Vec<u8>), MlsError> {
        if key_packages.is_empty() {
            return Err(MlsError::AddMember("no KeyPackage".to_string()));
        }
        if key_packages.iter().any(|kp| verdict_of(kp.leaf_node()) == LeafVerdict::Forged) {
            return Err(MlsError::ForgedLeaf("in the KeyPackage".to_string()));
        }
        let (commit_msg, welcome_msg, _group_info) = self
            .group
            .add_members(provider, signer, &key_packages)
            .map_err(|e| MlsError::AddMember(format!("{e:?}")))?;

        // Merge the commit locally
        self.group
            .merge_pending_commit(provider)
            .map_err(|e| MlsError::AddMember(format!("Merge commit: {e:?}")))?;

        let commit_bytes = commit_msg
            .to_bytes()
            .map_err(|e| MlsError::TlsCodec(format!("{e:?}")))?;

        let welcome_bytes = welcome_msg
            .to_bytes()
            .map_err(|e| MlsError::TlsCodec(format!("{e:?}")))?;

        Ok((commit_bytes, welcome_bytes))
    }

    /// What our own leaf's credential proves (None: no own leaf yet).
    pub fn own_leaf_verdict(&self) -> Option<LeafVerdict> {
        self.group.own_leaf_node().map(verdict_of)
    }

    /// Replace our leaf's credential with `credential` (same signing key, now bound
    /// to the account) by a commit; returns it to be sent. Members accept a
    /// Legacy leaf becoming Bound, never a Bound one changing its owner.
    pub fn rebind_own_leaf(
        &mut self,
        provider: &impl OpenMlsProvider,
        signer: &openmls_basic_credential::SignatureKeyPair,
        credential: CredentialWithKey,
    ) -> Result<Vec<u8>, MlsError> {
        let bundle = self
            .group
            .self_update_with_new_signer(
                provider,
                signer,
                openmls::credentials::NewSignerBundle { signer, credential_with_key: credential },
                LeafNodeParameters::default(),
            )
            .map_err(|e| MlsError::Other(format!("rebind own leaf: {e:?}")))?;
        self.group
            .merge_pending_commit(provider)
            .map_err(|e| MlsError::Other(format!("rebind merge: {e:?}")))?;
        bundle.commit().to_bytes().map_err(|e| MlsError::TlsCodec(format!("{e:?}")))
    }

    /// Remove a member from the group by their leaf index.
    ///
    /// Returns the TLS-serialized commit message to be sent to the DS.
    pub fn remove_member(
        &mut self,
        provider: &impl OpenMlsProvider,
        signer: &impl openmls_traits::signatures::Signer,
        leaf_index: LeafNodeIndex,
    ) -> Result<Vec<u8>, MlsError> {
        self.remove_members(provider, signer, &[leaf_index])
    }

    /// Remove several leaves in one commit (all phones of one person).
    pub fn remove_members(
        &mut self,
        provider: &impl OpenMlsProvider,
        signer: &impl openmls_traits::signatures::Signer,
        leaves: &[LeafNodeIndex],
    ) -> Result<Vec<u8>, MlsError> {
        let (commit_msg, _welcome_msg, _group_info) = self
            .group
            .remove_members(provider, signer, leaves)
            .map_err(|e| MlsError::RemoveMember(format!("{e:?}")))?;

        // Merge the remove commit locally
        self.group
            .merge_pending_commit(provider)
            .map_err(|e| MlsError::RemoveMember(format!("Merge commit: {e:?}")))?;

        commit_msg
            .to_bytes()
            .map_err(|e| MlsError::TlsCodec(format!("{e:?}")))
    }

    // =======================================================================
    // Messaging
    // =======================================================================

    /// Encrypt a plaintext message and return the TLS-serialized ciphertext.
    ///
    /// The plaintext is padded to a fixed-size bucket (C4) before MLS
    /// encryption to mitigate length-based traffic analysis. The receiver
    /// will automatically remove the padding in [`receive_message`].
    pub fn send_message(
        &mut self,
        provider: &impl OpenMlsProvider,
        signer: &impl openmls_traits::signatures::Signer,
        plaintext: &[u8],
    ) -> Result<Vec<u8>, MlsError> {
        // C4: Pad the plaintext before MLS encryption
        let padded = MessagePadder::pad(plaintext);

        let mls_msg = self
            .group
            .create_message(provider, signer, &padded)
            .map_err(|e| MlsError::SendMessage(format!("{e:?}")))?;

        mls_msg
            .to_bytes()
            .map_err(|e| MlsError::TlsCodec(format!("{e:?}")))
    }

    /// Decrypt a received ciphertext and return the typed content with
    /// sender attribution.
    ///
    /// For Commits, the commit is automatically merged after processing.
    /// For Proposals, they are stored in the pending proposal queue.
    ///
    /// # Sender Attribution
    ///
    /// The `sender` field in `ReceivedMessage::Application` is populated
    /// from the MLS `ProcessedMessage` sender info. This maps the sender's
    /// leaf index to their credential bytes and signature key, providing
    /// cryptographically-authenticated author attribution.
    pub fn receive_message(
        &mut self,
        provider: &impl OpenMlsProvider,
        ciphertext: &[u8],
    ) -> Result<ReceivedMessage, MlsError> {
        let (msg_in, _rest) = MlsMessageIn::tls_deserialize_bytes(ciphertext)
            .map_err(|e| MlsError::TlsCodec(format!("Message deserialization: {e:?}")))?;

        let protocol_msg = msg_in
            .try_into_protocol_message()
            .map_err(|e| MlsError::ReceiveMessage(format!("Not a protocol message: {e:?}")))?;

        let processed = self
            .group
            .process_message(provider, protocol_msg)
            .map_err(|e| MlsError::ReceiveMessage(format!("{e:?}")))?;

        // Extract sender information before consuming the processed message.
        // The sender's leaf index is available from the processed message.
        let sender_ref = processed.sender();
        let sender_ref_owned = sender_ref.clone();

        // Look up the sender's credential from the group membership.
        // This provides the credential bytes and signature key for attribution.
        let sender_id = self.sender_id_from_sender(sender_ref);

        let content = processed.into_content();
        match content {
            ProcessedMessageContent::ApplicationMessage(app) => {
                // C4: Remove the padding that was applied before encryption
                let padded_data = app.into_bytes();
                let data = MessagePadder::unpad(&padded_data)?;
                Ok(ReceivedMessage::Application {
                    data,
                    sender: sender_id,
                })
            }
            ProcessedMessageContent::StagedCommitMessage(staged_commit) => {
                // Not merged when it brings a forged leaf or takes a proven
                // owner's slot from them: we stay in the old epoch.
                self.vet_commit(sender_ref_owned, &staged_commit)?;
                self.group
                    .merge_staged_commit(provider, *staged_commit)
                    .map_err(|e| MlsError::ReceiveMessage(format!("Merge commit: {e:?}")))?;
                Ok(ReceivedMessage::Commit)
            }
            ProcessedMessageContent::ProposalMessage(_proposal) => {
                // Store the proposal for later committing
                // Note: The proposal has already been stored by process_message internally
                // in the proposal store. We just return that we received it.
                Ok(ReceivedMessage::Proposal)
            }
            ProcessedMessageContent::ExternalJoinProposalMessage(_proposal) => {
                Ok(ReceivedMessage::Proposal)
            }
        }
    }

    /// Every leaf a commit brings in or changes must hold its credential; a
    /// leaf that replaces another keeps the owner the old one proved.
    fn vet_commit(&self, committer: Sender, staged: &StagedCommit) -> Result<(), MlsError> {
        for add in staged.add_proposals() {
            if verdict_of(add.add_proposal().key_package().leaf_node()) == LeafVerdict::Forged {
                return Err(MlsError::ForgedLeaf("added by a commit".to_string()));
            }
        }
        for update in staged.update_proposals() {
            if let Sender::Member(i) = update.sender() {
                self.vet_replacement(*i, update.update_proposal().leaf_node())?;
            }
        }
        if let Some(leaf) = staged.update_path_leaf_node() {
            match committer {
                Sender::Member(i) => self.vet_replacement(i, leaf)?,
                _ if verdict_of(leaf) == LeafVerdict::Forged => {
                    return Err(MlsError::ForgedLeaf("joining by commit".to_string()));
                }
                _ => {}
            }
        }
        Ok(())
    }

    fn vet_replacement(&self, index: LeafNodeIndex, leaf: &LeafNode) -> Result<(), MlsError> {
        let old = self.group.members()
            .find(|m| m.index == index)
            .map(|m| leaf_binding::check(m.credential.serialized_content(), m.signature_key.as_slice()))
            .unwrap_or(LeafVerdict::Legacy);
        if leaf_binding::may_replace(old, verdict_of(leaf)) {
            Ok(())
        } else {
            Err(MlsError::ForgedLeaf(format!("leaf {} changes its proven owner", index.u32())))
        }
    }

    // =======================================================================
    // Sender attribution helpers (C2)
    // =======================================================================

    /// Map a [`Sender`] to a [`SenderId`] by looking up the member's
    /// credential in the group's ratchet tree.
    ///
    /// For `Sender::Member(leaf_index)`, we look up the member in the
    /// tree and return their credential bytes and signature key.
    ///
    /// For other sender types (External, NewMemberProposal, NewMemberCommit),
    /// we return a fallback SenderId with just the type information.
    fn sender_id_from_sender(&self, sender: &Sender) -> SenderId {
        match sender {
            Sender::Member(leaf_index) => {
                let idx = leaf_index.u32();

                // Look up this member in the group tree
                for member in self.group.members() {
                    if member.index.u32() == idx {
                        return SenderId {
                            leaf_index: idx,
                            credential_bytes: member.credential.serialized_content().to_vec(),
                            signature_key: member.signature_key.clone(),
                        };
                    }
                }

                // Fallback: member not found (shouldn't happen in normal operation)
                tracing::warn!(
                    "Sender leaf index {} not found in group members; using fallback SenderId",
                    idx
                );
                SenderId {
                    leaf_index: idx,
                    credential_bytes: Vec::new(),
                    signature_key: Vec::new(),
                }
            }
            Sender::External(_) => {
                SenderId {
                    leaf_index: u32::MAX, // sentinel for external sender
                    credential_bytes: Vec::new(),
                    signature_key: Vec::new(),
                }
            }
            Sender::NewMemberProposal | Sender::NewMemberCommit => {
                SenderId {
                    leaf_index: u32::MAX, // sentinel for new member
                    credential_bytes: Vec::new(),
                    signature_key: Vec::new(),
                }
            }
        }
    }

    // =======================================================================
    // Group info / queries
    // =======================================================================

    /// Get the list of current group members.
    pub fn members(&self) -> Vec<GroupMember> {
        self.group
            .members()
            .map(|m| GroupMember {
                leaf_index: m.index.u32(),
                credential_bytes: m.credential.serialized_content().to_vec(),
                signature_key: m.signature_key,
                encryption_key: m.encryption_key,
            })
            .collect()
    }

    /// Get the current epoch number.
    pub fn epoch(&self) -> u64 {
        self.group.epoch().as_u64()
    }

    /// Get the group ID as raw bytes.
    pub fn group_id(&self) -> Vec<u8> {
        self.group.group_id().as_slice().to_vec()
    }

    /// Check if this group is still active (we haven't been evicted).
    pub fn is_active(&self) -> bool {
        self.group.is_active()
    }

    /// Export a secret from the current epoch.
    ///
    /// This can be used for cross-protocol verification (e.g. QR code
    /// matching, safety number verification).
    ///
    /// **Security note**: The caller is responsible for zeroizing the
    /// returned secret after use. Use `zeroize::Zeroize` on the `Vec<u8>`
    /// when done.
    pub fn export_secret(
        &self,
        provider: &impl OpenMlsProvider,
        label: &str,
        context: &[u8],
        length: usize,
    ) -> Result<Vec<u8>, MlsError> {
        self.group
            .export_secret(provider.crypto(), label, context, length)
            .map_err(|e| MlsError::ExportSecret(format!("{e:?}")))
    }

    /// Get the epoch authenticator for cross-device verification.
    pub fn epoch_authenticator(&self) -> Vec<u8> {
        self.group.epoch_authenticator().as_slice().to_vec()
    }

    // =======================================================================
    // Leaving / cleanup
    // =======================================================================

    /// Leave the group by issuing a Remove proposal for ourselves.
    ///
    /// Leave the group by creating a RemoveProposal for ourselves and
    /// immediately self-committing it (same pattern as `remove_member`).
    ///
    /// Returns the TLS-serialized commit message to send to the DS.
    /// After this call the group is inactive — no more messages can be
    /// sent or received (unless re-joined via a new Welcome).
    pub fn leave(
        &mut self,
        provider: &impl OpenMlsProvider,
        signer: &impl openmls_traits::signatures::Signer,
    ) -> Result<Vec<u8>, MlsError> {
        let own_credential = self
            .group
            .credential()
            .map(|c| c.serialized_content().to_vec())
            .unwrap_or_default();
        let own_index = self
            .group
            .members()
            .find(|m| m.credential.serialized_content() == own_credential.as_slice())
            .map(|m| m.index)
            .ok_or_else(|| MlsError::LeaveGroup("Cannot find own leaf index".into()))?;

        // Create + self-commit the remove (same as remove_member).
        let (commit_msg, _welcome, _group_info) = self
            .group
            .remove_members(provider, signer, &[own_index])
            .map_err(|e| MlsError::LeaveGroup(format!("{e:?}")))?;

        // Merge locally so the group state reflects our own removal.
        self.group
            .merge_pending_commit(provider)
            .map_err(|e| MlsError::LeaveGroup(format!("Merge commit: {e:?}")))?;

        commit_msg
            .to_bytes()
            .map_err(|e| MlsError::TlsCodec(format!("{e:?}")))
    }

    /// Load a previously persisted group from storage.
    pub fn load(
        provider: &impl OpenMlsProvider,
        group_id: &[u8],
    ) -> Result<Option<Self>, MlsError> {
        let gid = GroupId::from_slice(group_id);
        let group = MlsGroup::load(provider.storage(), &gid)
            .map_err(|e| MlsError::Storage(format!("{e:?}")))?;
        Ok(group.map(|g| Self { group: g }))
    }

    /// Delete this group from persistent storage.
    pub fn delete(self, provider: &impl OpenMlsProvider) -> Result<(), MlsError> {
        let mut group = self.group;
        group
            .delete(provider.storage())
            .map_err(|e| MlsError::Storage(format!("{e:?}")))?;
        Ok(())
    }

    /// Get a reference to the inner OpenMLS `MlsGroup`.
    ///
    /// This is exposed for advanced use cases where direct access
    /// to the OpenMLS API is needed.
    pub fn inner(&self) -> &MlsGroup {
        &self.group
    }

    /// Get a mutable reference to the inner OpenMLS `MlsGroup`.
    pub fn inner_mut(&mut self) -> &mut MlsGroup {
        &mut self.group
    }
}
