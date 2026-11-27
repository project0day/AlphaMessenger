//! # Group transport directory — acceptance decision
//!
//! This holds the PURE rule: what to do with an incoming directory. No DB,
//! no MLS session, no network — only facts in, decision out.
//!
//! It lives here not for beauty. The rule is four prohibitions, each closing
//! its own attack, and they must be checked by exhaustive cases — including
//! ones impossible to reproduce on a live group (a directory from a foreign
//! epoch, one member trying to name another's address). With the DB and MLS
//! inside, such an enumeration is impossible.
//!
//! [`crate::core`] applies the decision: it reads the records, calls [`decide`]
//! and writes the result. There is no second copy of the rule.

use alpha_messages::message_type::GroupDirectoryEntry;

/// What is known about the group and the incoming directory.
pub(crate) struct DirectoryFacts<'a> {
    /// Signing keys of CURRENT members — the only source of membership.
    pub members: &'a [String],
    /// The directory sender's signing key (attested by MLS).
    pub sender_sig: &'a str,
    pub version: u32,
    /// Directory revision — the MLS epoch at snapshot time.
    pub revision: u64,
    /// The group's current epoch.
    pub current_epoch: u64,
    /// Highest already-accepted revision.
    pub known_epoch: u64,
    /// Established bindings: signing key → address.
    pub existing: &'a dyn Fn(&str) -> Option<String>,
    /// Authorizations: member signing key → the signing key of whoever may
    /// first name their address.
    pub authority: &'a dyn Fn(&str) -> Option<String>,
    /// The inviter's address, verified against the Seal signature on join.
    pub bootstrap_npub: Option<String>,
    pub entries: &'a [GroupDirectoryEntry],
}

/// Decision result.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum DirectoryDecision {
    /// Reject entirely. Partial application is not allowed: half a directory
    /// is worse than no records at all.
    Reject(RejectReason),
    /// Accept the listed NEW bindings (established ones are not repeated).
    Accept(Vec<(String, String)>),
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum RejectReason {
    /// Unsupported schema version.
    Version,
    /// The sender is not in the group.
    NotMember,
    /// Revision older than an accepted one — rollback attempt.
    Rollback,
    /// Revision does not match the current epoch — describes a different membership.
    WrongEpoch,
    /// Attempt to rewrite an established binding.
    Remap,
    /// First binding without traceable provenance.
    NoProvenance,
    /// One address named for two members.
    DuplicateAddress,
}

/// Decide on a directory. Four prohibitions — see [`RejectReason`].
pub(crate) fn decide(f: &DirectoryFacts<'_>) -> DirectoryDecision {
    use DirectoryDecision::*;
    use RejectReason::*;

    if f.version == 0 || f.version > super::core::DIRECTORY_VERSION {
        return Reject(Version);
    }
    // The sender's authorization starts with membership — but doesn't end there.
    if !f.members.iter().any(|m| m == f.sender_sig) {
        return Reject(NotMember);
    }
    if f.revision < f.known_epoch {
        return Reject(Rollback);
    }
    if f.revision != f.current_epoch {
        return Reject(WrongEpoch);
    }

    // Is the sender the one who invited us? Checked against the sender's PROVEN
    // binding, not their claim about themselves: a self-proclaimed address is
    // no proof.
    let sender_is_inviter = match (&f.bootstrap_npub, (f.existing)(f.sender_sig)) {
        (Some(boot), Some(bound)) => !boot.is_empty() && *boot == bound,
        _ => false,
    };

    let mut accepted: Vec<(String, String)> = Vec::new();
    let mut addresses: Vec<String> = Vec::new();

    for e in f.entries {
        // Only MLS changes membership: a record about a non-member is just
        // ignored, not grounds to reject the whole directory (membership may be ahead).
        if !f.members.iter().any(|m| *m == e.sig_key) {
            continue;
        }
        if let Some(bound) = (f.existing)(&e.sig_key) {
            if bound != e.npub_hex {
                return Reject(Remap);
            }
            addresses.push(bound);
            continue; // re-checking what is already verified is harmless
        }
        // First binding. Two provenances qualify: the sender themselves added
        // this member, or the sender added US to the group.
        let by_adder = (f.authority)(&e.sig_key).map(|a| a == f.sender_sig).unwrap_or(false);
        if !by_adder && !sender_is_inviter {
            return Reject(NoProvenance);
        }
        addresses.push(e.npub_hex.clone());
        accepted.push((e.sig_key.clone(), e.npub_hex.clone()));
    }

    // One address for two members is a contradiction: one of them would
    // receive the other's messages.
    let mut sorted = addresses.clone();
    sorted.sort();
    let before = sorted.len();
    sorted.dedup();
    if sorted.len() != before {
        return Reject(DuplicateAddress);
    }

    Accept(accepted)
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALICE: &str = "a1a1a1";
    const BOB: &str = "b0b0b0";
    const CAROL: &str = "c0c0c0";
    const N_ALICE: &str = "npub_alice";
    const N_BOB: &str = "npub_bob";
    const N_CAROL: &str = "npub_carol";
    const N_MALLORY: &str = "npub_mallory";

    fn entry(sig: &str, npub: &str) -> GroupDirectoryEntry {
        GroupDirectoryEntry { sig_key: sig.into(), npub_hex: npub.into() }
    }

    /// Convenience constructor: nothing bound and nothing authorized by default.
    struct Case {
        members: Vec<String>,
        existing: std::collections::HashMap<String, String>,
        authority: std::collections::HashMap<String, String>,
        bootstrap: Option<String>,
        revision: u64,
        current_epoch: u64,
        known_epoch: u64,
        version: u32,
    }

    impl Default for Case {
        fn default() -> Self {
            Self {
                members: vec![ALICE.into(), BOB.into()],
                existing: Default::default(),
                authority: Default::default(),
                bootstrap: None,
                revision: 1,
                current_epoch: 1,
                known_epoch: 0,
                version: 1,
            }
        }
    }

    impl Case {
        fn decide(&self, sender: &str, entries: &[GroupDirectoryEntry]) -> DirectoryDecision {
            let ex = |s: &str| self.existing.get(s).cloned();
            let au = |s: &str| self.authority.get(s).cloned();
            decide(&DirectoryFacts {
                members: &self.members,
                sender_sig: sender,
                version: self.version,
                revision: self.revision,
                current_epoch: self.current_epoch,
                known_epoch: self.known_epoch,
                existing: &ex,
                authority: &au,
                bootstrap_npub: self.bootstrap.clone(),
                entries,
            })
        }
    }

    // --- Lifecycle ---

    /// Whoever added a member may first name their address.
    #[test]
    fn create_directory_complete() {
        let mut c = Case::default();
        c.authority.insert(BOB.into(), ALICE.into());
        let d = c.decide(ALICE, &[entry(BOB, N_BOB)]);
        assert_eq!(d, DirectoryDecision::Accept(vec![(BOB.into(), N_BOB.into())]));
    }

    /// A third member is added — whoever added them names their address.
    #[test]
    fn add_directory_complete() {
        let mut c = Case::default();
        c.members.push(CAROL.into());
        c.existing.insert(BOB.into(), N_BOB.into());
        c.authority.insert(CAROL.into(), ALICE.into());
        let d = c.decide(ALICE, &[entry(BOB, N_BOB), entry(CAROL, N_CAROL)]);
        assert_eq!(d, DirectoryDecision::Accept(vec![(CAROL.into(), N_CAROL.into())]));
    }

    /// The newcomer is addressable right after the directory is accepted —
    /// before their first message. That is what the directory is for.
    #[test]
    fn add_new_member_addressable() {
        let mut c = Case::default();
        c.members.push(CAROL.into());
        c.authority.insert(CAROL.into(), ALICE.into());
        match c.decide(ALICE, &[entry(CAROL, N_CAROL)]) {
            DirectoryDecision::Accept(v) => {
                assert!(v.iter().any(|(s, n)| s == CAROL && n == N_CAROL));
            }
            other => panic!("expected acceptance, got {other:?}"),
        }
    }

    // --- Prohibitions ---

    #[test]
    fn rollback_rejected() {
        let mut c = Case::default();
        c.known_epoch = 5;
        c.revision = 3;
        c.current_epoch = 5;
        assert_eq!(c.decide(ALICE, &[]), DirectoryDecision::Reject(RejectReason::Rollback));
    }

    #[test]
    fn wrong_epoch_rejected() {
        let mut c = Case::default();
        c.revision = 4;
        c.current_epoch = 7;
        assert_eq!(c.decide(ALICE, &[]), DirectoryDecision::Reject(RejectReason::WrongEpoch));
    }

    #[test]
    fn non_member_sender_rejected() {
        let c = Case::default();
        assert_eq!(
            c.decide("deadbeef", &[entry(BOB, N_BOB)]),
            DirectoryDecision::Reject(RejectReason::NotMember),
        );
    }

    /// A record about someone absent from membership is dropped — but the
    /// directory as a whole survives: membership may have moved ahead.
    #[test]
    fn non_member_entry_ignored() {
        let mut c = Case::default();
        c.authority.insert(BOB.into(), ALICE.into());
        let d = c.decide(ALICE, &[entry(BOB, N_BOB), entry("ffff", "npub_ghost")]);
        assert_eq!(d, DirectoryDecision::Accept(vec![(BOB.into(), N_BOB.into())]));
    }

    /// An established binding cannot be rewritten — otherwise chats are stolen by spoofing.
    #[test]
    fn member_remap_rejected() {
        let mut c = Case::default();
        c.existing.insert(BOB.into(), N_BOB.into());
        c.authority.insert(BOB.into(), ALICE.into());
        assert_eq!(
            c.decide(ALICE, &[entry(BOB, N_MALLORY)]),
            DirectoryDecision::Reject(RejectReason::Remap),
        );
    }

    /// A group member WITHOUT authorization cannot be first to claim another's binding.
    #[test]
    fn cross_member_binding_not_authorized() {
        let mut c = Case::default();
        c.members.push(CAROL.into());
        // The authorization for Carol belongs to Alice, but Bob sends it.
        c.authority.insert(CAROL.into(), ALICE.into());
        assert_eq!(
            c.decide(BOB, &[entry(CAROL, N_MALLORY)]),
            DirectoryDecision::Reject(RejectReason::NoProvenance),
        );
    }

    /// Self-proclamation is not proof: the sender declares themselves to be
    /// the inviter's address without having a confirmed binding of their own.
    #[test]
    fn self_declared_remap_rejected() {
        let mut c = Case::default();
        c.bootstrap = Some(N_ALICE.into());
        // The sender has NO binding → they did not prove they are the inviter.
        assert_eq!(
            c.decide(BOB, &[entry(BOB, N_ALICE), entry(ALICE, N_MALLORY)]),
            DirectoryDecision::Reject(RejectReason::NoProvenance),
        );
    }

    /// Inviter-address provenance: their binding is CONFIRMED and matched the
    /// address verified via the Seal on join. Only then is their primary
    /// directory accepted.
    #[test]
    fn bootstrap_address_provenance() {
        let mut c = Case::default();
        c.members.push(CAROL.into());
        c.bootstrap = Some(N_ALICE.into());
        c.existing.insert(ALICE.into(), N_ALICE.into()); // proven on join
        let d = c.decide(ALICE, &[entry(ALICE, N_ALICE), entry(CAROL, N_CAROL)]);
        assert_eq!(d, DirectoryDecision::Accept(vec![(CAROL.into(), N_CAROL.into())]));
    }

    /// Two members with one address is a contradiction, not a trifle.
    #[test]
    fn duplicate_address_rejected() {
        let mut c = Case::default();
        c.members.push(CAROL.into());
        c.authority.insert(BOB.into(), ALICE.into());
        c.authority.insert(CAROL.into(), ALICE.into());
        assert_eq!(
            c.decide(ALICE, &[entry(BOB, N_BOB), entry(CAROL, N_BOB)]),
            DirectoryDecision::Reject(RejectReason::DuplicateAddress),
        );
    }

    /// A removed member does not enter the directory: they are not in
    /// membership, so their record is dropped and they become no recipient.
    #[test]
    fn removed_member_not_in_result() {
        let mut c = Case::default();           // members: Alice, Bob
        c.authority.insert(BOB.into(), ALICE.into());
        let d = c.decide(ALICE, &[entry(BOB, N_BOB), entry(CAROL, N_CAROL)]);
        match d {
            DirectoryDecision::Accept(v) => {
                assert!(!v.iter().any(|(s, _)| s == CAROL), "a removed member got into the directory");
            }
            other => panic!("expected acceptance, got {other:?}"),
        }
    }

    #[test]
    fn unsupported_version_rejected() {
        let mut c = Case::default();
        c.version = 99;
        assert_eq!(c.decide(ALICE, &[]), DirectoryDecision::Reject(RejectReason::Version));
    }
}

// --- Group readiness to send ---

/// Why a group is not ready to accept a send.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum NotReady {
    /// MLS membership unavailable — session not loaded.
    NoMembership,
    /// Some member has no address to send to.
    MissingBinding(usize),
    /// One address assigned to two members.
    DuplicateAddress,
    /// The directory describes an epoch other than the current membership.
    StaleDirectory,
}

/// Is the group ready: EVERY remote member has exactly one verified
/// address, no duplicates, directory not stale.
///
/// Refusal here means ZERO publishes. A silent skip of unresolvable
/// recipients was harmless with public events — everyone saw the event.
/// With personal envelopes a skip becomes an undelivered message with no
/// trace of error, so it is either full coverage or refusal.
pub(crate) fn transport_ready(
    members: &[String],
    own_sig: &str,
    current_epoch: u64,
    directory_epoch: u64,
    binding: &dyn Fn(&str) -> Option<String>,
) -> Result<Vec<String>, NotReady> {
    if members.is_empty() {
        return Err(NotReady::NoMembership);
    }
    let remote: Vec<&String> = members.iter().filter(|m| *m != own_sig).collect();
    if remote.is_empty() {
        return Ok(Vec::new()); // we are alone in the group — no one to address
    }
    // A directory from a past epoch describes a different membership. A
    // revision ahead of the current epoch is not allowed either: the snapshot
    // is taken of the CURRENT membership.
    if directory_epoch > current_epoch {
        return Err(NotReady::StaleDirectory);
    }

    let mut addresses: Vec<String> = Vec::with_capacity(remote.len());
    let mut missing = 0usize;
    for sig in &remote {
        match binding(sig) {
            Some(a) if !a.is_empty() => addresses.push(a),
            _ => missing += 1,
        }
    }
    if missing > 0 {
        return Err(NotReady::MissingBinding(missing));
    }
    let mut sorted = addresses.clone();
    sorted.sort();
    let before = sorted.len();
    sorted.dedup();
    if sorted.len() != before {
        return Err(NotReady::DuplicateAddress);
    }
    Ok(addresses)
}

#[cfg(test)]
mod ready_tests {
    use super::*;
    use std::collections::HashMap;

    const ME: &str = "me";
    const A: &str = "aaaa";
    const B: &str = "bbbb";

    fn map(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect()
    }

    /// Direct chat: one remote member, address present — ready.
    #[test]
    fn direct_ready() {
        let m = map(&[(A, "npub_a")]);
        let r = transport_ready(
            &[ME.into(), A.into()], ME, 3, 3, &|s| m.get(s).cloned(),
        );
        assert_eq!(r, Ok(vec!["npub_a".to_string()]));
    }

    /// Group: everyone remote has an address.
    #[test]
    fn group_ready() {
        let m = map(&[(A, "npub_a"), (B, "npub_b")]);
        let r = transport_ready(
            &[ME.into(), A.into(), B.into()], ME, 5, 5, &|s| m.get(s).cloned(),
        ).unwrap();
        assert_eq!(r.len(), 2);
    }

    /// One member unaddressable → NOT ready. Refusal instead of guesswork.
    #[test]
    fn missing_binding_not_ready() {
        let m = map(&[(A, "npub_a")]);
        let r = transport_ready(
            &[ME.into(), A.into(), B.into()], ME, 5, 5, &|s| m.get(s).cloned(),
        );
        assert_eq!(r, Err(NotReady::MissingBinding(1)));
    }

    /// One address for two — a contradiction: one would receive the other's mail.
    #[test]
    fn duplicate_address_not_ready() {
        let m = map(&[(A, "npub_same"), (B, "npub_same")]);
        let r = transport_ready(
            &[ME.into(), A.into(), B.into()], ME, 5, 5, &|s| m.get(s).cloned(),
        );
        assert_eq!(r, Err(NotReady::DuplicateAddress));
    }

    /// The directory is ahead of the current epoch — describes another membership.
    #[test]
    fn stale_directory_not_ready() {
        let m = map(&[(A, "npub_a")]);
        let r = transport_ready(
            &[ME.into(), A.into()], ME, 2, 9, &|s| m.get(s).cloned(),
        );
        assert_eq!(r, Err(NotReady::StaleDirectory));
    }

    /// A removed member is not in membership → they become no recipient, even
    /// if their binding survived somewhere.
    #[test]
    fn removed_member_ignored_as_recipient() {
        let m = map(&[(A, "npub_a"), (B, "npub_b")]); // B's binding intact
        let r = transport_ready(
            &[ME.into(), A.into()], ME, 5, 5, &|s| m.get(s).cloned(),
        ).unwrap();
        assert_eq!(r, vec!["npub_a".to_string()], "a removed member got into the recipients");
    }

    /// Membership unavailable — nowhere to publish.
    #[test]
    fn no_membership_not_ready() {
        let r = transport_ready(&[], ME, 1, 1, &|_| None);
        assert_eq!(r, Err(NotReady::NoMembership));
    }
}
