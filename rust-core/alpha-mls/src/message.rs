//! Message types returned from MLS message receive operations.
//!
//! These are higher-level, AlphaMessenger-specific types that wrap
//! the OpenMLS `ProcessedMessageContent` variants into a simpler
//! enum that the rest of the application can consume.
//!
//! # Sender Attribution (C2)
//!
//! Every `ReceivedMessage::Application` carries a [`SenderId`] that
//! identifies *who* sent the message, extracted from the MLS leaf-node
//! credential at decrypt time. This is critical for:
//!
//! - Displaying the correct author in the UI
//! - Access-control decisions (e.g. admin-only operations)
//! - Auditing / verification of message provenance
//!
//! The sender is derived from the MLS layer — it is **not** taken from
//! the Nostr event author (which could be spoofed by a malicious relay).

use serde::{Deserialize, Serialize};

/// Identifies the sender of an MLS message within a group.
///
/// This is extracted from the MLS leaf-node credential when a message
/// is decrypted. It is NOT the Nostr identity — it is the MLS identity
/// (typically a UUID in the `BasicCredential`).
///
/// The `credential_bytes` field matches the `credential_bytes` field
/// of [`GroupMember`], so you can correlate a sender with a group member.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SenderId {
    /// The leaf index of the sender in the ratchet tree.
    pub leaf_index: u32,

    /// The serialized credential bytes (identity) of the sender.
    /// This matches `GroupMember::credential_bytes` for member lookup.
    pub credential_bytes: Vec<u8>,

    /// The public signature key of the sender.
    pub signature_key: Vec<u8>,
}

/// A decoded MLS message returned by [`crate::group::AlphaGroup::receive_message`]
/// or [`crate::session::MlsSessionManager::receive`].
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ReceivedMessage {
    /// Application (plaintext) data with sender attribution.
    ///
    /// The `sender` field is populated from the MLS leaf-node credential
    /// of the message author, ensuring correct attribution regardless of
    /// the transport layer.
    Application {
        /// The decrypted plaintext bytes.
        data: Vec<u8>,
        /// The authenticated sender of this message.
        sender: SenderId,
    },

    /// A Commit was processed and the epoch has advanced.
    /// The caller may need to merge the staged commit if it hasn't been
    /// merged already (though `receive_message` auto-merges).
    Commit,

    /// A standalone proposal that should be queued.
    Proposal,

    /// A Welcome message inviting this client to join a group.
    /// This variant is informational — normally, Welcome processing
    /// is handled via `join_from_welcome`.
    Welcome,
}

impl ReceivedMessage {
    /// Convenience: extract the plaintext bytes if this is an Application message.
    ///
    /// Returns `None` for Commit, Proposal, and Welcome variants.
    pub fn application_data(&self) -> Option<&[u8]> {
        match self {
            ReceivedMessage::Application { data, .. } => Some(data),
            _ => None,
        }
    }

    /// Convenience: extract the sender if this is an Application message.
    ///
    /// Returns `None` for non-Application variants.
    pub fn sender(&self) -> Option<&SenderId> {
        match self {
            ReceivedMessage::Application { sender, .. } => Some(sender),
            _ => None,
        }
    }

    /// Convenience: check if this is an Application message.
    pub fn is_application(&self) -> bool {
        matches!(self, ReceivedMessage::Application { .. })
    }
}

/// Information about a single member of an MLS group.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GroupMember {
    /// The leaf index of this member in the ratchet tree.
    pub leaf_index: u32,

    /// The serialized credential bytes (identity) of this member.
    pub credential_bytes: Vec<u8>,

    /// The public signature key of this member.
    pub signature_key: Vec<u8>,

    /// The public HPKE encryption key of this member.
    pub encryption_key: Vec<u8>,
}

impl GroupMember {
    /// Convert this GroupMember into a SenderId for message attribution.
    pub fn to_sender_id(&self) -> SenderId {
        SenderId {
            leaf_index: self.leaf_index,
            credential_bytes: self.credential_bytes.clone(),
            signature_key: self.signature_key.clone(),
        }
    }
}
