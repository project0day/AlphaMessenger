//! A persona keeps our identity out of a group: every operation there is
//! signed by the persona, and members only ever see the persona's key.

use alpha_mls::{AlphaCredential, MlsSessionManager, Persona, ReceivedMessage};

fn session(name: &str) -> MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto> {
    let (_, signer) = AlphaCredential::generate(name.as_bytes()).unwrap();
    let cred = AlphaCredential::new(name.as_bytes(), &signer).unwrap();
    MlsSessionManager::new_in_memory(signer, cred)
}

fn signer_of(s: &MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>) -> Vec<u8> {
    s.signer_public()
}

#[test]
fn members_see_only_the_persona() {
    let mut owner = session("owner");
    let mut bob = session("bob");
    let owner_identity_key = signer_of(&owner);

    let persona = Persona::generate().unwrap();
    let persona_key = persona.public_key();
    let gid = owner.create_group_as(persona).unwrap();
    assert!(owner.has_persona(&gid));
    assert_eq!(owner.signer_public_for(&gid), persona_key);

    let kp = bob.generate_key_packages().unwrap().key_package_bytes().unwrap();
    let (_commit, welcome) = owner.add_member_to_group(&gid, &kp).unwrap();
    bob.join_chat(&welcome).unwrap();

    let ct = owner.send(&gid, b"post").unwrap();
    match bob.receive(&gid, &ct).unwrap() {
        ReceivedMessage::Application { data, sender } => {
            assert_eq!(data, b"post");
            assert_eq!(sender.signature_key, persona_key);
            assert_ne!(sender.signature_key, owner_identity_key);
            assert!(!String::from_utf8_lossy(&sender.credential_bytes).contains("owner"));
        }
        other => panic!("unexpected {other:?}"),
    }
    let info = bob.group_info(&gid).unwrap();
    assert!(info.members.iter().all(|m| m.signature_key != owner_identity_key));
}

#[test]
fn persona_survives_json_and_joins_by_private_key_package() {
    let mut owner = session("owner");
    let mut admin = session("admin");
    let admin_identity_key = signer_of(&admin);

    let gid = owner.create_group_as(Persona::generate().unwrap()).unwrap();

    // The admin's persona: its KeyPackage goes to the owner privately.
    let persona = Persona::generate().unwrap();
    let json = persona.to_json().unwrap();
    let kp = admin.generate_key_package_as(&persona).unwrap().key_package_bytes().unwrap();
    let (_c, welcome) = owner.add_member_to_group(&gid, &kp).unwrap();
    let joined = admin.join_chat(&welcome).unwrap();
    assert_eq!(joined, gid);
    admin.set_persona(&gid, Persona::from_json(&json).unwrap());

    let ct = admin.send(&gid, b"from admin").unwrap();
    match owner.receive(&gid, &ct).unwrap() {
        ReceivedMessage::Application { sender, .. } => {
            assert_ne!(sender.signature_key, admin_identity_key);
            assert_eq!(sender.signature_key, Persona::from_json(&json).unwrap().public_key());
        }
        other => panic!("unexpected {other:?}"),
    }
}
