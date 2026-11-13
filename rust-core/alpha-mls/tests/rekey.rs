//! A re-key Welcome for a group we hold is read and checked before anything
//! of ours changes; only an applied one replaces the group.

use alpha_mls::{AlphaCredential, MlsSessionManager, ReceivedMessage};

type Session = MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>;

fn session(name: &str) -> Session {
    let (_, signer) = AlphaCredential::generate(name.as_bytes()).unwrap();
    let cred = AlphaCredential::new(name.as_bytes(), &signer).unwrap();
    MlsSessionManager::new_in_memory(signer, cred)
}

fn reads(from: &mut Session, to: &mut Session, gid: &[u8], text: &[u8]) -> bool {
    let ct = from.send(gid, text).unwrap();
    matches!(to.receive(gid, &ct), Ok(ReceivedMessage::Application { data, .. }) if data == text)
}

/// Owner and Bob in one group.
fn pair() -> (Session, Session, Vec<u8>) {
    let mut owner = session("owner");
    let mut bob = session("bob");
    let gid = owner.create_group().unwrap();
    let kp = bob.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (_c, welcome) = owner.add_member_to_group(&gid, &kp).unwrap();
    bob.join_chat(&welcome).unwrap();
    (owner, bob, gid)
}

#[test]
fn a_garbage_welcome_leaves_the_group_as_it_was() {
    let (mut owner, mut bob, gid) = pair();
    let epoch = bob.group_epoch(&gid).unwrap();
    assert!(bob.stage_rejoin(b"not a welcome at all").is_err());
    assert_eq!(bob.group_epoch(&gid), Some(epoch));
    assert!(reads(&mut owner, &mut bob, &gid, b"still here"));
}

#[test]
fn a_staged_welcome_of_another_group_changes_nothing() {
    let (mut owner, mut bob, gid) = pair();
    let mut mallory = session("mallory");
    let other = mallory.create_group().unwrap();
    let kp = bob.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (_c, welcome) = mallory.add_member_to_group(&other, &kp).unwrap();

    let staged = bob.stage_rejoin(&welcome).unwrap();
    assert_ne!(staged.group_id(), gid);
    drop(staged);
    assert!(reads(&mut owner, &mut bob, &gid, b"untouched"));
}

#[test]
fn a_real_re_key_replaces_the_group_once_applied() {
    let (mut owner, mut bob, gid) = pair();
    let old_epoch = bob.group_epoch(&gid).unwrap();
    let bob_key = bob.signer_public();
    let leaf = owner.group_info(&gid).unwrap().members.iter()
        .find(|m| m.signature_key == bob_key).unwrap().leaf_index;
    owner.remove_member_from_group(&gid, leaf).unwrap();
    let kp = bob.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (_c, welcome) = owner.add_member_to_group(&gid, &kp).unwrap();

    let staged = bob.stage_rejoin(&welcome).unwrap();
    assert_eq!(staged.group_id(), gid);
    assert!(staged.epoch() > old_epoch);
    let (_, sender_key) = staged.sender().unwrap();
    assert_eq!(sender_key, owner.signer_public());
    // Read only: the old state is still there until applied.
    assert_eq!(bob.group_epoch(&gid), Some(old_epoch));

    bob.commit_rejoin(staged).unwrap();
    assert!(bob.group_epoch(&gid).unwrap() > old_epoch);
    assert!(reads(&mut owner, &mut bob, &gid, b"after re-key"));
}
