//! End-to-end check of the sealed transport on real MLS state: epoch keys fed
//! by the session listener must match between members, seal/open must round-trip,
//! and a commit must be sealed under the epoch it was created in.

use std::sync::Arc;

use alpha_mls::{AlphaCredential, MlsSessionManager, ReceivedMessage};
use alpha_transport::sealed::{self, TransportKeyRing};
use alpha_transport::{MlsEnvelope, MlsMessageType};
use openmls_rust_crypto::OpenMlsRustCrypto;

fn member(name: &[u8]) -> (MlsSessionManager<OpenMlsRustCrypto>, Arc<TransportKeyRing>) {
    let (_basic, signer) = AlphaCredential::generate(name).expect("credential");
    let cred = AlphaCredential::new(name, &signer).expect("alpha credential");
    let mut session = MlsSessionManager::new_in_memory(signer, cred);
    let ring = Arc::new(TransportKeyRing::default());
    let r = Arc::clone(&ring);
    session.set_epoch_listener(Box::new(move |g, e, s| {
        r.insert_epoch(g, e, s);
    }));
    (session, ring)
}

/// Seal like `NostrMlsBridge::send_public`; open like `NostrRelayClient::raw_from_event`.
fn transmit(from: &TransportKeyRing, to: &TransportKeyRing, envelope: &MlsEnvelope) -> MlsEnvelope {
    let key = from.key_for_message(&envelope.group_id, &envelope.payload, envelope.epoch).expect("sender key");
    let content = sealed::seal(&key.key, &key.tag, &envelope.to_bytes()).expect("seal");
    let rkey = to.lookup(&key.tag).expect("receiver knows the routing tag");
    assert_eq!(rkey.group_id, envelope.group_id);
    let bytes = sealed::unseal(&rkey.key, &key.tag, &content).expect("open");
    MlsEnvelope::from_bytes(&bytes).expect("envelope")
}

#[test]
fn sealed_messages_and_commits_flow_between_members() {
    let (mut alice, ring_a) = member(b"alice");
    let (mut bob, ring_b) = member(b"bob");
    let (carol, _ring_c) = member(b"carol");

    let bob_kp = bob.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (gid, _commit, welcome) = alice.create_direct_chat(&bob_kp).expect("create");
    bob.join_chat(&welcome).expect("join");
    assert_eq!(ring_a.subscription_tags(), ring_b.subscription_tags(), "same epoch → same tag");

    // Application message in the current epoch.
    let epoch = alice.group_epoch(&gid).unwrap();
    let ct = alice.send(&gid, b"hello").unwrap();
    let env = transmit(&ring_a, &ring_b, &MlsEnvelope::new(MlsMessageType::Application, gid.clone(), epoch, ct));
    match bob.receive(&gid, &env.payload).expect("bob decrypts") {
        ReceivedMessage::Application { data, .. } => assert_eq!(data, b"hello"),
        other => panic!("unexpected {other:?}"),
    }

    // Commit: created in `epoch`, Alice is already in `epoch + 1`. The envelope
    // carries the NEW epoch (as the core does for adds) — sealing must still use
    // the commit's own epoch, which Bob holds.
    let carol_kp = carol.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (commit, _welcome) = alice.add_member_to_group(&gid, &carol_kp).expect("add");
    let new_epoch = alice.group_epoch(&gid).unwrap();
    assert_eq!(new_epoch, epoch + 1);
    let env = transmit(&ring_a, &ring_b, &MlsEnvelope::new(MlsMessageType::Commit, gid.clone(), new_epoch, commit));
    assert!(matches!(bob.receive(&gid, &env.payload).expect("bob applies commit"), ReceivedMessage::Commit));
    assert_eq!(bob.group_epoch(&gid), Some(new_epoch));

    // Next message travels on the new epoch's tag, which Bob learned from the commit.
    let ct = alice.send(&gid, b"after commit").unwrap();
    let env = transmit(&ring_a, &ring_b, &MlsEnvelope::new(MlsMessageType::Application, gid.clone(), new_epoch, ct));
    match bob.receive(&gid, &env.payload).expect("bob decrypts in new epoch") {
        ReceivedMessage::Application { data, .. } => assert_eq!(data, b"after commit"),
        other => panic!("unexpected {other:?}"),
    }

    // Relays can't link the epochs: the tag changed with the epoch.
    let old = ring_b.key_for_message(&gid, &[], epoch).unwrap().tag;
    let new = ring_b.key_for_message(&gid, &[], new_epoch).unwrap().tag;
    assert_ne!(old, new);
}

#[test]
fn outsider_cannot_open_or_route() {
    let (mut alice, ring_a) = member(b"alice");
    let (mut bob, _ring_b) = member(b"bob");
    let (_eve, ring_e) = member(b"eve");
    let bob_kp = bob.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (gid, _c, welcome) = alice.create_direct_chat(&bob_kp).unwrap();
    bob.join_chat(&welcome).unwrap();
    let ct = alice.send(&gid, b"secret").unwrap();
    let env = MlsEnvelope::new(MlsMessageType::Application, gid.clone(), 1, ct);
    let key = ring_a.key_for_message(&gid, &env.payload, 1).unwrap();
    assert!(ring_e.lookup(&key.tag).is_none(), "outsider has no route for the tag");
}
