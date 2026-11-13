//! Epoch tracking and validation.
//!
//! The [`EpochTracker`] keeps an in-memory record of epoch transitions
//! for each MLS group. It can:
//!
//! - Return the current known epoch for a group
//! - Validate that an epoch advance is sequential (no skips)
//! - Detect gaps between the local epoch and a received epoch,
//!   which may indicate missed commits

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::error::MlsError;

/// A detected gap in epoch numbers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct EpochGap {
    /// The last epoch we have locally.
    pub from_epoch: u64,
    /// The epoch we received from the network.
    pub to_epoch: u64,
}

/// In-memory tracker of MLS group epochs.
///
/// This is intentionally lightweight: it does not persist to disk on
/// every update. Instead, callers should periodically flush the
/// tracker state to the database (e.g. via
/// `alpha_database::AlphaDatabase`).
///
/// The tracker is `Send + Sync` because it only uses `HashMap` with
/// `u64` values (no interior mutability beyond `&mut self`).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct EpochTracker {
    /// Map from group_id (as raw bytes, hex-encoded for the key)
    /// to the last-known epoch number.
    epochs: HashMap<String, u64>,
}

impl EpochTracker {
    /// Create a new empty tracker.
    pub fn new() -> Self {
        Self::default()
    }

    /// Get the current known epoch for a group.
    ///
    /// Returns `0` if the group has never been tracked.
    pub fn current_epoch(&self, group_id: &[u8]) -> u64 {
        let key = hex::encode(group_id);
        *self.epochs.get(&key).unwrap_or(&0)
    }

    /// Record a new epoch for a group after validation.
    ///
    /// Call this after successfully processing a commit that
    /// advances the epoch.
    pub fn advance_epoch(&mut self, group_id: &[u8], new_epoch: u64) {
        let key = hex::encode(group_id);
        self.epochs.insert(key, new_epoch);
    }

    /// Validate that an epoch advance is legal.
    ///
    /// The new epoch must be exactly `current + 1`. Returns
    /// [`MlsError::InvalidEpoch`] if the new epoch doesn't follow
    /// sequentially from the current one.
    pub fn validate_epoch_advance(&self, group_id: &[u8], new_epoch: u64) -> Result<(), MlsError> {
        let current = self.current_epoch(group_id);
        if new_epoch != current + 1 {
            return Err(MlsError::InvalidEpoch {
                expected: current + 1,
                actual: new_epoch,
            });
        }
        Ok(())
    }

    /// Detect whether there is a gap between our local epoch and a
    /// received epoch.
    ///
    /// A gap indicates that we missed one or more commits and may
    /// need to request missing messages from the delivery service.
    pub fn detect_epoch_gap(&self, group_id: &[u8], received_epoch: u64) -> Option<EpochGap> {
        let current = self.current_epoch(group_id);
        if received_epoch > current + 1 {
            Some(EpochGap {
                from_epoch: current,
                to_epoch: received_epoch,
            })
        } else {
            None
        }
    }

    /// Remove a group from tracking (e.g. after the group is deleted).
    pub fn remove_group(&mut self, group_id: &[u8]) {
        let key = hex::encode(group_id);
        self.epochs.remove(&key);
    }

    /// Get all tracked group IDs.
    pub fn tracked_groups(&self) -> Vec<Vec<u8>> {
        self.epochs
            .keys()
            .filter_map(|k| hex::decode(k).ok())
            .collect()
    }

    /// Serialize the tracker state for persistence.
    pub fn serialize(&self) -> Result<Vec<u8>, MlsError> {
        serde_json::to_vec(self).map_err(|e| MlsError::Serialization(e.to_string()))
    }

    /// Deserialize a tracker from persisted state.
    pub fn deserialize(bytes: &[u8]) -> Result<Self, MlsError> {
        serde_json::from_slice(bytes).map_err(|e| MlsError::Serialization(e.to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_epoch_tracking() {
        let mut tracker = EpochTracker::new();
        let gid = b"group1";

        assert_eq!(tracker.current_epoch(gid), 0);

        tracker.advance_epoch(gid, 1);
        assert_eq!(tracker.current_epoch(gid), 1);

        assert!(tracker.validate_epoch_advance(gid, 2).is_ok());
        assert!(tracker.validate_epoch_advance(gid, 3).is_err());

        tracker.advance_epoch(gid, 2);
        assert_eq!(tracker.current_epoch(gid), 2);

        // Gap detection
        assert!(tracker.detect_epoch_gap(gid, 3).is_none());
        assert!(tracker.detect_epoch_gap(gid, 5).is_some());
        let gap = tracker.detect_epoch_gap(gid, 5).unwrap();
        assert_eq!(gap.from_epoch, 2);
        assert_eq!(gap.to_epoch, 5);
    }

    #[test]
    fn test_serialize_deserialize() {
        let mut tracker = EpochTracker::new();
        tracker.advance_epoch(b"g1", 3);
        tracker.advance_epoch(b"g2", 7);

        let bytes = tracker.serialize().unwrap();
        let restored = EpochTracker::deserialize(&bytes).unwrap();

        assert_eq!(restored.current_epoch(b"g1"), 3);
        assert_eq!(restored.current_epoch(b"g2"), 7);
    }
}
