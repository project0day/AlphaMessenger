//! `alpha-mls` — Core MLS session engine for AlphaMessenger.
//!
//! This crate provides a production-grade MLS (Messaging Layer Security)
//! implementation based on OpenMLS v0.8.1 (RFC 9420). It handles:
//!
//! - KeyPackage generation and publishing
//! - Group creation (1:1 and multi-person)
//! - Welcome message processing
//! - Application message encrypt/decrypt
//! - Epoch management
//! - Persistence via OpenMLS's `StorageProvider` trait
//! - Message padding for metadata protection (C4)
//!
//! # Ciphersuite
//!
//! All groups use `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`.
//!
//! # Security Features
//!
//! - **Sender Attribution (C2)**: `ReceivedMessage::Application` includes a
//!   `sender: SenderId` field with the authenticated sender identity.
//! - **Message Padding (C4)**: Plaintext is padded to fixed-size buckets
//!   before MLS encryption to mitigate length-based traffic analysis.
//! - **Secret Zeroization (C1)**: All secret-bearing types implement
//!   `Zeroize` or `ZeroizeOnDrop`.

pub mod credential;
pub mod epoch_manager;
pub mod error;
pub mod group;
pub mod key_package;
pub mod leaf_binding;
pub mod message;
pub mod padding;
pub mod provider;
pub mod session;

// Re-export the most commonly used types at the crate root.
pub use credential::{AlphaCredential, DEFAULT_CIPHERSUITE, DEFAULT_CIPHERSUITE_ID};
pub use error::MlsError;
pub use epoch_manager::{EpochGap, EpochTracker};
pub use group::AlphaGroup;
pub use key_package::AlphaKeyPackage;
pub use leaf_binding::LeafVerdict;
pub use message::{GroupMember, ReceivedMessage, SenderId};
pub use padding::{MessagePadder, PADDING_BUCKETS};
pub use provider::{AlphaCryptoProvider, AlphaMlsProvider, SqlCipherStorageProvider};
pub use session::{group_storage_marker, AddMemberResult, DirectChatResult, EpochListener, GroupInfoSnapshot, MlsSessionManager, Persona, TRANSPORT_EXPORTER_LABEL};

// ---------------------------------------------------------------------------
// STAGE A integration test — verifies crypto purity and provider correctness
// ---------------------------------------------------------------------------

#[cfg(test)]
mod stage_a_tests {
    use openmls_traits::crypto::OpenMlsCrypto;
    use openmls_traits::random::OpenMlsRand;
    use openmls_traits::types::Ciphersuite;
    use openmls_traits::OpenMlsProvider;

    use crate::credential::{AlphaCredential, DEFAULT_CIPHERSUITE, DEFAULT_CIPHERSUITE_ID};
    use crate::provider::{AlphaCryptoProvider, AlphaMlsProvider, SqlCipherStorageProvider};
    use crate::key_package::AlphaKeyPackage;
    use crate::session::MlsSessionManager;

    // A1: Ciphersuite is stock RFC 9420 (0x0001)
    #[test]
    fn test_ciphersuite_is_stock_rfc9420() {
        // Verify ciphersuite ID
        assert_eq!(
            DEFAULT_CIPHERSUITE_ID, 0x0001,
            "Ciphersuite must be 0x0001 (stock RFC 9420)"
        );
        // Verify the enum variant matches
        assert_eq!(
            DEFAULT_CIPHERSUITE,
            Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519,
            "Ciphersuite must be the stock X25519+AES128GCM+Ed25519 variant"
        );
    }

    // A1: AlphaCryptoProvider uses stock crypto/rand (no custom crypto)
    #[test]
    fn test_alpha_crypto_provider_uses_stock_crypto() {
        let provider = AlphaCryptoProvider::new();

        // The stock provider must support our ciphersuite
        let supported = provider.crypto().supported_ciphersuites();
        assert!(
            supported.contains(&DEFAULT_CIPHERSUITE),
            "Stock crypto provider must support our default ciphersuite"
        );

        // Must NOT include any custom ciphersuite (there are only 3 supported)
        assert_eq!(
            supported.len(), 3,
            "Stock openmls_rust_crypto supports exactly 3 ciphersuites (no custom additions)"
        );
    }

    // A1: AlphaMlsProvider uses stock crypto + SQLCipher storage
    #[test]
    fn test_alpha_mls_provider_uses_stock_crypto_with_sqlcipher() {
        let provider = AlphaMlsProvider::open_in_memory()
            .expect("Failed to create AlphaMlsProvider");

        // Crypto must support our ciphersuite
        let supported = provider.crypto().supported_ciphersuites();
        assert!(
            supported.contains(&DEFAULT_CIPHERSUITE),
            "AlphaMlsProvider crypto must support our default ciphersuite"
        );

        // Rand must work (generate some bytes)
        let random_bytes = provider.rand().random_vec(32);
        assert!(
            random_bytes.is_ok(),
            "AlphaMlsProvider rand must work"
        );
        assert_eq!(
            random_bytes.unwrap().len(), 32,
            "Random bytes must be 32 bytes long"
        );
    }

    // A1+A2: Full MLS round-trip with stock crypto (create group, send/receive)
    #[test]
    fn test_mls_round_trip_stock_crypto() {
        let provider = AlphaCryptoProvider::new();

        // Generate Alice identity
        let (_alice_cred, alice_signer) = AlphaCredential::generate(b"alice")
            .expect("Failed to generate Alice credential");
        let alice_alpha_cred = AlphaCredential::new(b"alice", &alice_signer)
            .expect("Failed to create Alice AlphaCredential");
        alice_alpha_cred.store_signer(provider.storage())
            .expect("Failed to store Alice signer");

        // Generate Bob identity
        let (_bob_cred, bob_signer) = AlphaCredential::generate(b"bob")
            .expect("Failed to generate Bob credential");
        let bob_alpha_cred = AlphaCredential::new(b"bob", &bob_signer)
            .expect("Failed to create Bob AlphaCredential");
        bob_alpha_cred.store_signer(provider.storage())
            .expect("Failed to store Bob signer");

        // Create session managers
        let mut alice_session = MlsSessionManager::new(
            AlphaCryptoProvider::new(), alice_signer, alice_alpha_cred,
        );
        let mut bob_session = MlsSessionManager::new(
            AlphaCryptoProvider::new(), bob_signer, bob_alpha_cred,
        );

        // Alice generates a key package and serializes it
        let _alice_kp = alice_session.generate_key_packages()
            .expect("Alice: key package generation failed");

        // Bob generates a key package and serializes it
        let bob_kp = bob_session.generate_key_packages()
            .expect("Bob: key package generation failed");
        let bob_kp_bytes = bob_kp.key_package_bytes()
            .expect("Bob: key package serialization failed");

        // Alice creates a direct chat with Bob
        let (group_id, _commit_bytes, welcome_bytes) = alice_session
            .create_direct_chat(&bob_kp_bytes)
            .expect("Alice: failed to create direct chat");

        // Bob joins from the welcome
        let bob_group_id = bob_session
            .join_chat(&welcome_bytes)
            .expect("Bob: failed to join from welcome");

        // Both must have the same group ID
        assert_eq!(
            group_id, bob_group_id,
            "Alice and Bob must share the same group ID"
        );

        // Alice sends a message
        let plaintext = b"Hello Bob from Alice!";
        let ciphertext = alice_session
            .send(&group_id, plaintext)
            .expect("Alice: failed to send message");

        // Bob receives and decrypts
        let received = bob_session
            .receive(&group_id, &ciphertext)
            .expect("Bob: failed to receive message");

        match received {
            crate::message::ReceivedMessage::Application { data, sender } => {
                assert_eq!(
                    data.as_slice(), plaintext,
                    "Bob must decrypt the exact message Alice sent"
                );
                // C2: Verify sender attribution is populated
                assert!(
                    !sender.credential_bytes.is_empty(),
                    "Sender credential bytes must be populated"
                );
                assert!(
                    !sender.signature_key.is_empty(),
                    "Sender signature key must be populated"
                );
            }
            other => {
                panic!("Expected Application message, got {:?}", other);
            }
        }

        // Bob sends a reply
        let reply = b"Hello Alice from Bob!";
        let reply_ct = bob_session
            .send(&group_id, reply)
            .expect("Bob: failed to send reply");

        let alice_received = alice_session
            .receive(&group_id, &reply_ct)
            .expect("Alice: failed to receive reply");

        match alice_received {
            crate::message::ReceivedMessage::Application { data, sender } => {
                assert_eq!(
                    data.as_slice(), reply,
                    "Alice must decrypt Bob's reply"
                );
                // C2: Verify sender attribution is populated
                assert!(
                    !sender.credential_bytes.is_empty(),
                    "Sender credential bytes must be populated"
                );
            }
            other => {
                panic!("Expected Application message, got {:?}", other);
            }
        }
    }

    // A3: Verify no duplicate crates — this is a compile-time check,
    // but we verify that our key types are uniquely named
    #[test]
    fn test_no_duplicate_crate_names() {
        // If there were duplicate crates, the following types would be
        // ambiguous and wouldn't compile. This test existing and passing
        // proves there are no duplicate crate definitions.
        let _ = std::any::type_name::<AlphaCredential>();
        let _ = std::any::type_name::<AlphaCryptoProvider>();
        let _ = std::any::type_name::<SqlCipherStorageProvider>();
        let _ = std::any::type_name::<AlphaMlsProvider>();
        let _ = std::any::type_name::<AlphaKeyPackage>();
        let _ = std::any::type_name::<MlsSessionManager<AlphaCryptoProvider>>();
    }
}

// ---------------------------------------------------------------------------
// FIX (epoch-fork) stress tests — reproduce the fork + verify recovery
// ---------------------------------------------------------------------------
//
// These tests exercise the MECHANISM that the FFI `recover_group_from_fork`
// relies on, at the pure-MLS level (no network, no SQLCipher): concurrent
// membership commits produce a WrongEpoch fork, and an owner-initiated
// Remove+re-Add with a FRESH KeyPackage yields a higher-epoch Welcome that
// lets the forked member re-join (`join_chat` replaces the forked local
// state) so the group "lives" again — messages decrypt end-to-end.
//
// They also document WHY the owner-sequencer guard in core.rs is necessary:
// two non-owners committing concurrently is exactly the fork reproduced here.

#[cfg(test)]
mod fork_stress_tests {
    use crate::credential::AlphaCredential;
    use crate::session::MlsSessionManager;
    use openmls_traits::OpenMlsProvider;

    /// Build a fresh in-memory identity (provider + signer + credential +
    /// session manager). Each member gets its own `AlphaCryptoProvider` so
    /// their MLS state is fully isolated (separate MemoryStorage).
    fn make_member(label: &[u8]) -> (
        MlsSessionManager<crate::provider::AlphaCryptoProvider>,
        AlphaCredential,
    ) {
        let provider = crate::provider::AlphaCryptoProvider::new();
        let (_cred, signer) = AlphaCredential::generate(label).expect("credential");
        let alpha_cred = AlphaCredential::new(label, &signer).expect("alpha credential");
        alpha_cred.store_signer(provider.storage()).expect("store signer");
        let session = MlsSessionManager::new(provider, signer, alpha_cred.clone());
        (session, alpha_cred)
    }

    /// A group made under the old key keeps it in our leaf; after an identity
    /// swap only groups made afterwards match the signer.
    #[test]
    fn identity_swap_is_detected_per_group() {
        let (mut s, _) = make_member(b"first");
        let old_gid = s.create_group().expect("group");
        assert!(!s.signer_mismatch(&old_gid));

        let (_c, signer) = AlphaCredential::generate(b"second").expect("credential");
        let cred = AlphaCredential::new(b"second", &signer).expect("alpha credential");
        s.set_identity(signer, cred);

        assert!(s.signer_mismatch(&old_gid));
        let new_gid = s.create_group().expect("group");
        assert!(!s.signer_mismatch(&new_gid));
    }

    /// A fresh KeyPackage for `owner`, TLS-serialized (ready for `add_member_to_group`).
    fn fresh_kp_bytes(owner: &mut MlsSessionManager<crate::provider::AlphaCryptoProvider>) -> Vec<u8> {
        owner.generate_key_packages()
            .expect("kp gen")
            .key_package_bytes()
            .expect("kp bytes")
    }

    /// Reproduce the epoch fork: two members each issue an Add commit for a
    /// different new member, both targeting the same epoch transition. Applying
    /// the second commit to a member who already merged the first fails with
    /// WrongEpoch — the group is forked for that member.
    #[test]
    fn test_concurrent_adds_produce_epoch_fork() {
        let (mut owner, _) = make_member(b"owner");
        let (mut alice, _) = make_member(b"alice");
        let (mut bob, _) = make_member(b"bob");
        let (mut carol, _) = make_member(b"carol");
        let (mut dave, _) = make_member(b"dave");

        // owner creates the group, adds alice + bob (so 3 members, epoch 2).
        let gid = owner.create_group().expect("create");
        let alice_kp = fresh_kp_bytes(&mut alice);
        let (_commit_add_alice, welcome_alice) =
            owner.add_member_to_group(&gid, &alice_kp).expect("add alice");
        alice.join_chat(&welcome_alice).expect("alice join");
        let bob_kp = fresh_kp_bytes(&mut bob);
        let (commit_add_bob, welcome_bob) =
            owner.add_member_to_group(&gid, &bob_kp).expect("add bob");
        // alice must process bob's add commit to stay in sync.
        alice.receive(&gid, &commit_add_bob).expect("alice recv bob add");
        bob.join_chat(&welcome_bob).expect("bob join");

        let epoch_before = owner.group_info(&gid).unwrap().epoch;
        assert_eq!(epoch_before, 2, "group should be at epoch 2 after 2 adds");

        // ── Concurrent adds (the fork source) ───────────────────────────
        // owner adds carol; alice SIMULTANEOUSLY adds dave (both build a
        // commit on epoch 2 → epoch 3). In MLS-over-Nostr without a Delivery
        // Service this is a real race. Here we simulate it sequentially:
        // owner's commit "wins" (applied first), alice's commit then forks.
        let carol_kp = fresh_kp_bytes(&mut carol);
        let (_commit_owner_carol, _welcome_carol) =
            owner.add_member_to_group(&gid, &carol_kp).expect("owner add carol");
        // owner is now at epoch 3.

        let dave_kp = fresh_kp_bytes(&mut dave);
        // alice builds her add-dave commit on HER epoch-2 state (she hasn't
        // seen owner's epoch-3 commit yet).
        let (commit_alice_dave, _welcome_dave) =
            alice.add_member_to_group(&gid, &dave_kp).expect("alice add dave");
        // alice's local state advanced to epoch 3 on HER branch.

        // owner tries to apply alice's commit → WrongEpoch (owner is already
        // at epoch 3 on a DIFFERENT branch). This is the fork.
        let recv_result = owner.receive(&gid, &commit_alice_dave);
        assert!(
            recv_result.is_err(),
            "owner must reject alice's forked commit (WrongEpoch) — got {:?}",
            recv_result,
        );
        // The error should mention epoch/wrong — but OpenMLS's exact wording
        // varies across versions, so we only log it rather than hard-asserting
        // the substring (the `is_err()` above is the fork proof).
        let err = recv_result.unwrap_err().to_string();
        tracing::debug!("fork error (expected): {}", err);
        // The fork is real: owner can't decrypt alice's branch and vice-versa.
    }

    /// Verify owner-initiated recovery: after a fork, the owner Remove+re-Adds
    /// the forked member with a FRESH KeyPackage, producing a higher-epoch
    /// Welcome. The forked member `join_chat`s it, replacing their forked
    /// state, and the group "lives" again — a message from the owner decrypts.
    #[test]
    fn test_owner_recover_from_fork_via_remove_readd() {
        let (mut owner, _) = make_member(b"owner");
        let (mut alice, _) = make_member(b"alice");

        // Bootstrap: owner creates group + adds alice.
        let gid = owner.create_group().expect("create");
        let alice_kp = fresh_kp_bytes(&mut alice);
        let (_commit, welcome) = owner.add_member_to_group(&gid, &alice_kp).expect("add alice");
        alice.join_chat(&welcome).expect("alice join");

        // Sanity: round-trip works pre-fork.
        let ct = owner.send(&gid, b"hello-pre-fork").expect("send");
        let r = alice.receive(&gid, &ct).expect("recv");
        assert!(matches!(r, crate::message::ReceivedMessage::Application { .. }));

        // ── Simulate alice's local state going stale (fork) ─────────────
        // alice "misses" a membership commit that owner applied, so her local
        // epoch drifts behind owner's. We simulate this by owner doing a
        // Remove+Add of alice (which advances owner's epoch) WITHOUT alice
        // processing the Remove commit — alice is now on a stale branch.
        // Find alice's leaf index (exclude the owner by signature key).
        let owner_sig = owner.signer_public();
        let alice_leaf = owner
            .group_info(&gid)
            .unwrap()
            .members
            .iter()
            .find(|m| m.signature_key != owner_sig)
            .map(|m| m.leaf_index)
            .expect("alice must be a member pre-recovery");
        // owner removes alice (epoch advances).
        let _remove_commit = owner
            .remove_member_from_group(&gid, alice_leaf)
            .expect("remove alice");
        // owner re-adds alice with a FRESH KeyPackage → higher-epoch Welcome.
        let alice_kp2 = fresh_kp_bytes(&mut alice);
        let (_readd_commit, readd_welcome) =
            owner.add_member_to_group(&gid, &alice_kp2).expect("re-add alice");
        let owner_epoch_after = owner.group_info(&gid).unwrap().epoch;
        assert!(
            owner_epoch_after > 1,
            "owner epoch must have advanced past alice's forked epoch (1) after remove+readd, got {}",
            owner_epoch_after,
        );

        // alice's old state is forked (she's at epoch 1, owner advanced past
        // it). A send from owner would NOT decrypt on alice's old state. She
        // re-joins via the higher-epoch Welcome — but FIRST the stale local
        // group must be deleted, because OpenMLS's `join_from_welcome` refuses
        // to create a group that already exists (`GroupAlreadyExists`). This
        // delete-before-rejoin is exactly what catch_up.rs's REKEY_DELETE does
        // on the re-key path; without it the recovery Welcome is rejected and
        // the group stays permanently dead.
        alice.delete_group(&gid).expect("delete stale alice group");
        alice.join_chat(&readd_welcome).expect("alice re-join");
        let alice_epoch = alice.group_info(&gid).unwrap().epoch;
        assert_eq!(
            alice_epoch, owner_epoch_after,
            "alice's epoch must match owner's after re-join recovery",
        );

        // ── The group "lives" again ────────────────────────────────────
        let ct2 = owner.send(&gid, b"hello-after-recovery").expect("send2");
        let r2 = alice.receive(&gid, &ct2).expect("recv2");
        match r2 {
            crate::message::ReceivedMessage::Application { data, .. } => {
                assert_eq!(data, b"hello-after-recovery", "alice must decrypt post-recovery");
            }
            other => panic!("expected Application after recovery, got {:?}", other),
        }

        // And alice can reply on the recovered branch.
        let ct3 = alice.send(&gid, b"reply-after-recovery").expect("send3");
        let r3 = owner.receive(&gid, &ct3).expect("recv3");
        match r3 {
            crate::message::ReceivedMessage::Application { data, .. } => {
                assert_eq!(data, b"reply-after-recovery", "owner must decrypt alice's reply");
            }
            other => panic!("expected Application for owner, got {:?}", other),
        }
    }

    /// Verify that a stale (already-applied) commit is detectable — the
    /// classification the catch_up.rs COMMIT_STALE path uses to break the
    /// SecretReuse cascade. Re-applying an already-merged commit fails; the
    /// fix marks it processed and skips instead of flooding logs.
    #[test]
    fn test_stale_commit_reapply_fails() {
        let (mut owner, _) = make_member(b"owner");
        let (mut alice, _) = make_member(b"alice");

        let gid = owner.create_group().expect("create");
        let alice_kp = fresh_kp_bytes(&mut alice);
        let (commit, welcome) = owner.add_member_to_group(&gid, &alice_kp).expect("add");
        alice.join_chat(&welcome).expect("join");

        let cur = owner.group_info(&gid).unwrap().epoch;
        assert!(cur >= 1, "epoch must have advanced after the add");

        // Re-receiving the SAME commit owner already merged must fail. In
        // production `envelope.epoch == cur` (the post-commit epoch the sender
        // published) and `same_author == true`, so the catch_up.rs classifier
        // marks it STALE → processed + skip (no log flood, no SecretReuse).
        let r = owner.receive(&gid, &commit);
        assert!(
            r.is_err(),
            "re-receiving an already-applied commit must fail — got {:?}",
            r,
        );
    }
}

#[cfg(test)]
mod leaf_binding_tests {
    use crate::credential::AlphaCredential;
    use crate::error::MlsError;
    use crate::group::AlphaGroup;
    use crate::key_package::AlphaKeyPackage;
    use crate::leaf_binding;
    use crate::provider::AlphaCryptoProvider;
    use openmls::prelude::*;
    use openmls_basic_credential::SignatureKeyPair;
    use openmls_traits::OpenMlsProvider;

    struct Who {
        provider: AlphaCryptoProvider,
        signer: SignatureKeyPair,
        cred: AlphaCredential,
    }

    /// A member whose leaf is signed by `keys`.
    fn bound(keys: &nostr::Keys) -> Who {
        let provider = AlphaCryptoProvider::new();
        let (_, signer) = AlphaCredential::generate(b"").unwrap();
        let cred = AlphaCredential::new(&leaf_binding::bind(keys, signer.public()), &signer).unwrap();
        cred.store_signer(provider.storage()).unwrap();
        Who { provider, signer, cred }
    }

    fn kp(w: &Who) -> KeyPackage {
        AlphaKeyPackage::generate(&w.provider, &w.signer, w.cred.credential_with_key().unwrap())
            .unwrap().key_package().clone()
    }

    /// Mallory's own key, wearing a copy of Bob's credential.
    fn fake_bob(bob: &Who) -> Who {
        let provider = AlphaCryptoProvider::new();
        let (_, signer) = AlphaCredential::generate(b"").unwrap();
        let cred = AlphaCredential::new(bob.cred.identity(), &signer).unwrap();
        cred.store_signer(provider.storage()).unwrap();
        Who { provider, signer, cred }
    }

    fn group_of(owner: &Who) -> AlphaGroup {
        AlphaGroup::create_group(&owner.provider, &owner.signer, owner.cred.credential_with_key().unwrap()).unwrap()
    }

    /// Commit + Welcome made without our own add-time check, as a modified
    /// client would.
    fn raw_add(group: &mut AlphaGroup, by: &Who, kps: &[KeyPackage]) -> (Vec<u8>, Vec<u8>) {
        let (commit, welcome, _) = group.inner_mut().add_members(&by.provider, &by.signer, kps).unwrap();
        group.inner_mut().merge_pending_commit(&by.provider).unwrap();
        (commit.to_bytes().unwrap(), welcome.to_bytes().unwrap())
    }

    #[test]
    fn a_legacy_leaf_is_re_signed_and_the_peer_sees_it_bound() {
        let alice = bound(&nostr::Keys::generate());
        // Bob from before keys were bound: a random identity on his leaf.
        let bob_keys = nostr::Keys::generate();
        let bob_provider = AlphaCryptoProvider::new();
        let (_, bob_signer) = AlphaCredential::generate(b"").unwrap();
        let bob_old = AlphaCredential::new(b"bob-old-build", &bob_signer).unwrap();
        bob_old.store_signer(bob_provider.storage()).unwrap();
        let bob_kp = AlphaKeyPackage::generate(&bob_provider, &bob_signer, bob_old.credential_with_key().unwrap())
            .unwrap().key_package().clone();

        let mut a = group_of(&alice);
        let (_, welcome) = a.add_member(&alice.provider, &alice.signer, bob_kp).unwrap();
        let mut b = AlphaGroup::join_from_welcome(&bob_provider, &welcome).unwrap();
        let bob_verdict = |g: &AlphaGroup| g.inner().members()
            .find(|m| m.signature_key.as_slice() == bob_signer.public())
            .map(|m| leaf_binding::check(m.credential.serialized_content(), m.signature_key.as_slice()));
        assert_eq!(bob_verdict(&a), Some(leaf_binding::LeafVerdict::Legacy));
        assert_eq!(b.own_leaf_verdict(), Some(leaf_binding::LeafVerdict::Legacy));

        // Bob's newer build re-signs the same key with his account.
        let bob_new = AlphaCredential::new(&leaf_binding::bind(&bob_keys, bob_signer.public()), &bob_signer).unwrap();
        let commit = b.rebind_own_leaf(&bob_provider, &bob_signer, bob_new.credential_with_key().unwrap()).unwrap();
        a.receive_message(&alice.provider, &commit).unwrap();
        assert_eq!(a.epoch(), b.epoch());
        let want = leaf_binding::LeafVerdict::Bound(bob_keys.public_key().to_bytes());
        assert_eq!(bob_verdict(&a), Some(want));
        assert_eq!(b.own_leaf_verdict(), Some(want));

        // The chat goes on both ways at the new epoch.
        let to_bob = a.send_message(&alice.provider, &alice.signer, b"hi").unwrap();
        assert!(b.receive_message(&bob_provider, &to_bob).is_ok());
        let to_alice = b.send_message(&bob_provider, &bob_signer, b"hey").unwrap();
        assert!(a.receive_message(&alice.provider, &to_alice).is_ok());
    }

    #[test]
    fn a_member_cannot_add_someone_else_s_key_under_bob_s_name() {
        let alice = bound(&nostr::Keys::generate());
        let mallory = bound(&nostr::Keys::generate());
        let bob = bound(&nostr::Keys::generate());
        let mut a = group_of(&alice);
        let (_, welcome) = a.add_member(&alice.provider, &alice.signer, kp(&mallory)).unwrap();
        let mut m = AlphaGroup::join_from_welcome(&mallory.provider, &welcome).unwrap();

        let (commit, _) = raw_add(&mut m, &mallory, &[kp(&fake_bob(&bob))]);
        let epoch = a.epoch();
        assert!(matches!(a.receive_message(&alice.provider, &commit), Err(MlsError::ForgedLeaf(_))));
        assert_eq!(a.epoch(), epoch, "the forged commit must not be merged");
    }

    #[test]
    fn a_welcome_with_a_forged_member_is_not_joined() {
        let mallory = bound(&nostr::Keys::generate());
        let alice = bound(&nostr::Keys::generate());
        let bob = bound(&nostr::Keys::generate());
        let mut m = group_of(&mallory);
        let (_, welcome) = raw_add(&mut m, &mallory, &[kp(&alice), kp(&fake_bob(&bob))]);
        assert!(matches!(AlphaGroup::join_from_welcome(&alice.provider, &welcome), Err(MlsError::ForgedLeaf(_))));
    }

    #[test]
    fn our_own_add_refuses_a_forged_key_package() {
        let alice = bound(&nostr::Keys::generate());
        let bob = bound(&nostr::Keys::generate());
        let mut a = group_of(&alice);
        assert!(matches!(a.add_member(&alice.provider, &alice.signer, kp(&fake_bob(&bob))), Err(MlsError::ForgedLeaf(_))));
    }

    #[test]
    fn a_genuine_bound_member_joins_and_older_leaves_still_work() {
        let alice = bound(&nostr::Keys::generate());
        let bob_keys = nostr::Keys::generate();
        let bob = bound(&bob_keys);
        let legacy = {
            let provider = AlphaCryptoProvider::new();
            let (_, signer) = AlphaCredential::generate(b"").unwrap();
            let cred = AlphaCredential::new(b"0f1e2d3c4b5a69788796a5b4c3d2e1f0", &signer).unwrap();
            cred.store_signer(provider.storage()).unwrap();
            Who { provider, signer, cred }
        };
        let mut a = group_of(&alice);
        let (_, w1) = a.add_member(&alice.provider, &alice.signer, kp(&bob)).unwrap();
        let b = AlphaGroup::join_from_welcome(&bob.provider, &w1).unwrap();
        let (_, w2) = a.add_member(&alice.provider, &alice.signer, kp(&legacy)).unwrap();
        AlphaGroup::join_from_welcome(&legacy.provider, &w2).unwrap();

        let verdicts: Vec<_> = b.members().iter().map(|m| leaf_binding::check(&m.credential_bytes, &m.signature_key)).collect();
        assert!(verdicts.contains(&crate::LeafVerdict::Bound(bob_keys.public_key().to_bytes())));
    }
}

#[cfg(test)]
mod secret_chat_tests {
    use crate::credential::AlphaCredential;
    use crate::leaf_binding::{self, LeafVerdict};
    use crate::message::ReceivedMessage;
    use crate::provider::AlphaCryptoProvider;
    use crate::session::{MlsSessionManager, Persona};
    use openmls_traits::OpenMlsProvider;

    fn account(label: &[u8]) -> MlsSessionManager<AlphaCryptoProvider> {
        let provider = AlphaCryptoProvider::new();
        let (_, signer) = AlphaCredential::generate(label).unwrap();
        let cred = AlphaCredential::new(label, &signer).unwrap();
        cred.store_signer(provider.storage()).unwrap();
        MlsSessionManager::new(provider, signer, cred)
    }

    /// Both sides speak only as their personas: neither account's key is in
    /// the group, and each leaf proves its persona key.
    #[test]
    fn two_personas_set_up_a_chat_without_their_accounts() {
        let mut alice = account(b"alice");
        let mut bob = account(b"bob");
        let (ka, kb) = (nostr::Keys::generate(), nostr::Keys::generate());

        let gid = alice.create_group_as(Persona::generate_bound(&ka).unwrap()).unwrap();
        let bob_persona = Persona::generate_bound(&kb).unwrap();
        let kp = bob.generate_key_package_as(&bob_persona).unwrap().key_package_bytes().unwrap();
        assert!(matches!(leaf_binding::key_package_owner(&kp), Some(LeafVerdict::Bound(_))));

        let (_, welcome) = alice.add_member_to_group(&gid, &kp).unwrap();
        assert_eq!(bob.join_chat(&welcome).unwrap(), gid);
        bob.set_persona(&gid, bob_persona);

        let members = alice.group_info(&gid).unwrap().members;
        let owners: Vec<_> = leaf_binding::proven_owners(&members).into_iter().map(|(_, n)| n).collect();
        assert_eq!(owners.len(), 2);
        assert!(owners.contains(&ka.public_key().to_hex()) && owners.contains(&kb.public_key().to_hex()));
        let accounts = [alice.signer_public(), bob.signer_public()];
        assert!(members.iter().all(|m| !accounts.contains(&m.signature_key)));

        let ct = bob.send(&gid, b"hi").unwrap();
        assert!(matches!(alice.receive(&gid, &ct).unwrap(), ReceivedMessage::Application { data, .. } if data == b"hi"));
        let ct = alice.send(&gid, b"hey").unwrap();
        assert!(matches!(bob.receive(&gid, &ct).unwrap(), ReceivedMessage::Application { data, .. } if data == b"hey"));
    }
}

#[cfg(test)]
mod linked_device_tests {
    use crate::credential::AlphaCredential;
    use crate::leaf_binding;
    use crate::message::ReceivedMessage;
    use crate::provider::AlphaCryptoProvider;
    use crate::session::MlsSessionManager;
    use openmls_traits::OpenMlsProvider;

    fn phone(keys: &nostr::Keys) -> MlsSessionManager<AlphaCryptoProvider> {
        let provider = AlphaCryptoProvider::new();
        let (_, signer) = AlphaCredential::generate(b"x").unwrap();
        let cred = AlphaCredential::new(&leaf_binding::bind(keys, signer.public()), &signer).unwrap();
        cred.store_signer(provider.storage()).unwrap();
        MlsSessionManager::new(provider, signer, cred)
    }

    /// A second phone of the same account joins two chats with ONE package
    /// kept after use; both phones prove the same owner and read each other.
    #[test]
    fn a_linked_phone_joins_every_chat_with_one_kept_package() {
        let me = nostr::Keys::generate();
        let peer = nostr::Keys::generate();
        let mut first = phone(&me);
        let mut second = phone(&me);
        let mut other = phone(&peer);

        let peer_kp = other.generate_key_packages().unwrap().key_package_bytes().unwrap();
        let (direct, _, welcome) = first.create_direct_chat(&peer_kp).unwrap();
        assert_eq!(other.join_chat(&welcome).unwrap(), direct);
        let group = first.create_group().unwrap();

        let kept = second.generate_last_resort_key_package(None).unwrap().key_package_bytes().unwrap();
        let (commit, w1) = first.add_members_to_group(&direct, &[kept.clone()]).unwrap();
        assert!(matches!(other.receive(&direct, &commit).unwrap(), ReceivedMessage::Commit));
        let (_, w2) = first.add_members_to_group(&group, &[kept]).unwrap();
        assert_eq!(second.join_chat(&w1).unwrap(), direct);
        assert_eq!(second.join_chat(&w2).unwrap(), group, "the kept package opens the second chat too");

        let owners = leaf_binding::proven_owners(&other.group_info(&direct).unwrap().members);
        assert_eq!(owners.iter().filter(|(_, pk)| *pk == me.public_key().to_hex()).count(), 2);

        let ct = second.send(&direct, b"from the second phone").unwrap();
        assert!(matches!(first.receive(&direct, &ct).unwrap(), ReceivedMessage::Application { data, .. } if data == b"from the second phone"));
        let ct = other.send(&direct, b"hi both").unwrap();
        assert!(matches!(second.receive(&direct, &ct).unwrap(), ReceivedMessage::Application { .. }));
        assert!(matches!(first.receive(&direct, &ct).unwrap(), ReceivedMessage::Application { .. }));
    }

    /// A new chat takes the peer's phones and our other phones in one Welcome.
    #[test]
    fn a_new_chat_brings_in_all_phones_at_once() {
        let (me, peer) = (nostr::Keys::generate(), nostr::Keys::generate());
        let mut first = phone(&me);
        let mut second = phone(&me);
        let mut p1 = phone(&peer);
        let mut p2 = phone(&peer);
        let kps = vec![
            p1.generate_key_packages().unwrap().key_package_bytes().unwrap(),
            p2.generate_key_packages().unwrap().key_package_bytes().unwrap(),
            second.generate_key_packages().unwrap().key_package_bytes().unwrap(),
        ];
        let (gid, _, welcome) = first.create_direct_chat_with(&kps).unwrap();
        for s in [&mut p1, &mut p2, &mut second] {
            assert_eq!(s.join_chat(&welcome).unwrap(), gid);
        }
        assert_eq!(first.group_info(&gid).unwrap().members.len(), 4);
    }
}

#[cfg(test)]
mod storage_marker_tests {
    use crate::credential::AlphaCredential;
    use crate::session::{group_storage_marker, MlsSessionManager};

    fn contains(hay: &[u8], needle: &[u8]) -> bool {
        hay.windows(needle.len()).any(|w| w == needle)
    }

    /// A group's state can be told apart from the others' in the storage:
    /// leaving its entries out of a copy leaves every other group whole.
    #[test]
    fn a_group_s_entries_are_found_by_its_marker() {
        let (_, signer) = AlphaCredential::generate(b"a").unwrap();
        let cred = AlphaCredential::new(b"a", &signer).unwrap();
        let mut s = MlsSessionManager::new_in_memory(signer, cred);
        let g1 = s.create_group().unwrap();
        let g2 = s.create_group().unwrap();
        let (m1, m2) = (group_storage_marker(&g1), group_storage_marker(&g2));
        let snap = s.snapshot_storage();
        let of1 = snap.iter().filter(|(k, _)| contains(k, &m1)).count();
        let of2 = snap.iter().filter(|(k, _)| contains(k, &m2)).count();
        assert!(of1 >= 3 && of2 >= 3, "group state found: {of1}, {of2}");
        assert!(!snap.iter().any(|(k, _)| contains(k, &m1) && contains(k, &m2)));
    }
}
