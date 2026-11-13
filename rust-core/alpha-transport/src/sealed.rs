//! Sealed transport (v2): metadata-private publishing on public relays.
//!
//! Every group event (application message, commit, proposal) is published as a
//! kind-445 event that:
//!  - is signed by a **fresh one-time key** (never the user's identity key);
//!  - carries only an opaque routing tag `h`, derived from the MLS exporter
//!    secret of the epoch the message belongs to (it rotates every epoch);
//!  - has content `XChaCha20-Poly1305(epoch_key, padded(MlsEnvelope))`, so the
//!    relay sees neither the group id nor the epoch nor the real size.
//!
//! Only group members can derive the tag and key, so a relay learns nothing
//! about who talks to whom. Presence uses the same idea with a per-user
//! presence secret shared with contacts over MLS.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use base64::Engine;
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::Sha256;

/// Sealed group events (NIP-EE group-event kind).
pub const KIND_SEALED_GROUP: u16 = 445;
/// Sealed presence heartbeats. Ephemeral range: relays forward, never store.
pub const KIND_SEALED_PRESENCE: u16 = 20777;
/// Routing tag name (single letter, so relays index it).
pub const TAG_ROUTE: &str = "h";

const SEAL_VERSION: u8 = 2;
const HKDF_SALT: &[u8] = b"alpha-transport-v2";
const NONCE_LEN: usize = 24;
/// Epoch keys kept per group (late messages from recent epochs stay readable).
const KEEP_EPOCHS_PER_GROUP: usize = 4;
/// Epoch tags per group included in the live subscription.
const SUBSCRIBE_EPOCHS_PER_GROUP: usize = 2;
const OWN_EVENTS_CAP: usize = 8192;

pub type Tag16 = [u8; 16];
pub type Key32 = [u8; 32];

fn hkdf_expand<const N: usize>(ikm: &[u8], info: &[u8]) -> [u8; N] {
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), ikm);
    let mut out = [0u8; N];
    // Lengths used here are far below the HKDF-SHA256 limit.
    hk.expand(info, &mut out).expect("hkdf length");
    out
}

/// Routing tag + AEAD key for one MLS epoch, derived from its exporter secret.
pub fn derive_epoch_keys(exporter_secret: &[u8]) -> (Tag16, Key32) {
    (hkdf_expand::<16>(exporter_secret, b"group-route"), hkdf_expand::<32>(exporter_secret, b"group-key"))
}

/// Presence routing tag for one UTC hour.
pub fn presence_tag(presence_secret: &Key32, hour: u64) -> Tag16 {
    let mut info = b"presence-route".to_vec();
    info.extend_from_slice(&hour.to_be_bytes());
    hkdf_expand::<16>(presence_secret, &info)
}

/// Presence AEAD key.
pub fn presence_key(presence_secret: &Key32) -> Key32 {
    hkdf_expand::<32>(presence_secret, b"presence-key")
}

/// Private ntfy topic for wake-ups, derived from the user's wake secret.
pub fn wake_topic(wake_secret: &Key32) -> String {
    format!("am{}", hex::encode(hkdf_expand::<16>(wake_secret, b"wake-topic")))
}

/// Backup locator: a signing secret and an encryption key derived from the
/// identity secret key. The locator event is unlinkable to the npub, yet
/// anyone holding the nsec (the owner on a new device) can find it.
pub fn backup_locator(identity_secret: &[u8]) -> (Key32, Key32) {
    (hkdf_expand::<32>(identity_secret, b"backup-locator-sign"), hkdf_expand::<32>(identity_secret, b"backup-locator-key"))
}

pub fn random_secret() -> Key32 {
    let mut s = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut s);
    s
}

pub fn current_hour() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() / 3600)
        .unwrap_or(0)
}

// --- AEAD with size padding ---

/// Padded size bucket: 256 B minimum, powers of two up to 4 KiB, then
/// multiples of 4 KiB. Hides the length of short messages while keeping the
/// overhead of large ones small (relays cap event size around 64 KiB).
fn padded_len(n: usize) -> usize {
    if n <= 256 {
        256
    } else if n <= 4096 {
        n.next_power_of_two()
    } else {
        n.div_ceil(4096) * 4096
    }
}

fn pad(plaintext: &[u8]) -> Vec<u8> {
    let body = 4 + plaintext.len();
    let mut out = Vec::with_capacity(padded_len(body));
    out.extend_from_slice(&(plaintext.len() as u32).to_be_bytes());
    out.extend_from_slice(plaintext);
    out.resize(padded_len(body), 0);
    out
}

fn unpad(padded: &[u8]) -> Option<Vec<u8>> {
    if padded.len() < 4 {
        return None;
    }
    let len = u32::from_be_bytes(padded[..4].try_into().ok()?) as usize;
    padded.get(4..4 + len).map(|s| s.to_vec())
}

fn content_digest(plaintext: &[u8]) -> [u8; 32] {
    use sha2::Digest;
    Sha256::digest(plaintext).into()
}

/// Seal `plaintext` for the given routing tag. Output is base64 text for the
/// event content: `version || nonce || ciphertext`. The tag is bound as AAD.
///
/// Deterministic: the nonce is derived from the key and the plaintext, so a
/// retransmission of the same MLS message yields the same event (relays and
/// receivers dedup it by id). Distinct plaintexts never share a nonce.
pub fn seal(key: &Key32, tag: &Tag16, plaintext: &[u8]) -> Option<String> {
    let cipher = XChaCha20Poly1305::new(chacha20poly1305::Key::from_slice(key));
    let mut info = b"nonce".to_vec();
    info.extend_from_slice(&content_digest(plaintext));
    let nonce = hkdf_expand::<NONCE_LEN>(key, &info);
    let ct = cipher
        .encrypt(XNonce::from_slice(&nonce), Payload { msg: &pad(plaintext), aad: tag })
        .ok()?;
    let mut out = Vec::with_capacity(1 + NONCE_LEN + ct.len());
    out.push(SEAL_VERSION);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ct);
    Some(base64::engine::general_purpose::STANDARD.encode(out))
}

/// Open content produced by [`seal`]. `None` on any mismatch.
pub fn unseal(key: &Key32, tag: &Tag16, content: &str) -> Option<Vec<u8>> {
    let raw = base64::engine::general_purpose::STANDARD.decode(content.trim()).ok()?;
    if raw.len() < 1 + NONCE_LEN + 16 || raw[0] != SEAL_VERSION {
        return None;
    }
    let cipher = XChaCha20Poly1305::new(chacha20poly1305::Key::from_slice(key));
    let padded = cipher
        .decrypt(XNonce::from_slice(&raw[1..1 + NONCE_LEN]), Payload { msg: &raw[1 + NONCE_LEN..], aad: tag })
        .ok()?;
    unpad(&padded)
}

/// One-time event signing key for a sealed event, derived from the epoch key
/// and the plaintext (same message → same key → same event id on retransmit).
pub fn event_signer(key: &Key32, plaintext: &[u8]) -> nostr::Keys {
    let mut info = b"signer".to_vec();
    info.extend_from_slice(&content_digest(plaintext));
    let secret = hkdf_expand::<32>(key, &info);
    nostr::SecretKey::from_slice(&secret)
        .map(nostr::Keys::new)
        .unwrap_or_else(|_| nostr::Keys::generate())
}

pub fn parse_tag(hex_tag: &str) -> Option<Tag16> {
    hex::decode(hex_tag).ok()?.try_into().ok()
}

// --- Epoch key ring ---

#[derive(Clone)]
pub struct EpochKey {
    pub group_id: Vec<u8>,
    pub epoch: u64,
    pub tag: Tag16,
    pub key: Key32,
}

#[derive(Default)]
struct RingInner {
    by_tag: HashMap<Tag16, EpochKey>,
    /// Per group, ascending by epoch.
    by_group: HashMap<Vec<u8>, Vec<EpochKey>>,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct StoredKey {
    g: String,
    e: u64,
    t: String,
    k: String,
}

/// Epoch from the clear MLS framing of a public/private message.
pub fn mls_message_epoch(mls_payload: &[u8]) -> Option<u64> {
    use openmls::prelude::*;
    let (msg, _) = MlsMessageIn::tls_deserialize_bytes(mls_payload).ok()?;
    Some(msg.try_into_protocol_message().ok()?.epoch().as_u64())
}

/// Keys of the recent epochs of every group we are in. Fed by the MLS layer on
/// every epoch change; read by the transport to seal, open and subscribe.
pub struct TransportKeyRing {
    inner: RwLock<RingInner>,
    version: AtomicU64,
}

impl std::fmt::Debug for TransportKeyRing {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let n = self.inner.read().map(|i| i.by_tag.len()).unwrap_or(0);
        write!(f, "TransportKeyRing({n} keys, v{})", self.version())
    }
}

impl Default for TransportKeyRing {
    fn default() -> Self {
        Self { inner: RwLock::new(RingInner::default()), version: AtomicU64::new(0) }
    }
}

impl TransportKeyRing {
    /// Bumped on every change; used to know when to resubscribe / persist.
    pub fn version(&self) -> u64 {
        self.version.load(Ordering::Acquire)
    }

    fn insert(&self, k: EpochKey) -> bool {
        let Ok(mut inner) = self.inner.write() else { return false };
        if inner.by_tag.contains_key(&k.tag) {
            return false;
        }
        let list = inner.by_group.entry(k.group_id.clone()).or_default();
        if list.iter().any(|e| e.epoch == k.epoch) {
            return false;
        }
        list.push(k.clone());
        list.sort_by_key(|e| e.epoch);
        let mut dropped = Vec::new();
        while list.len() > KEEP_EPOCHS_PER_GROUP {
            dropped.push(list.remove(0).tag);
        }
        for t in dropped {
            inner.by_tag.remove(&t);
        }
        inner.by_tag.insert(k.tag, k);
        self.version.fetch_add(1, Ordering::AcqRel);
        true
    }

    /// Register the exporter secret of `epoch` for `group_id`. Returns true if new.
    pub fn insert_epoch(&self, group_id: &[u8], epoch: u64, exporter_secret: &[u8]) -> bool {
        let (tag, key) = derive_epoch_keys(exporter_secret);
        self.insert(EpochKey { group_id: group_id.to_vec(), epoch, tag, key })
    }

    /// Forget every group not in `live` (left / deleted chats).
    pub fn retain_groups(&self, live: &[Vec<u8>]) {
        let Ok(mut inner) = self.inner.write() else { return };
        let gone: Vec<Vec<u8>> = inner.by_group.keys().filter(|g| !live.contains(g)).cloned().collect();
        if gone.is_empty() {
            return;
        }
        for g in gone {
            if let Some(list) = inner.by_group.remove(&g) {
                for e in list {
                    inner.by_tag.remove(&e.tag);
                }
            }
        }
        self.version.fetch_add(1, Ordering::AcqRel);
    }

    pub fn lookup(&self, tag: &Tag16) -> Option<EpochKey> {
        self.inner.read().ok()?.by_tag.get(tag).cloned()
    }

    /// Key to seal an MLS message of `group_id`. The epoch is read from the MLS
    /// message itself (envelope epochs are not reliable for commits); falls
    /// back to `hint_epoch`, then to the newest known epoch.
    pub fn key_for_message(&self, group_id: &[u8], mls_payload: &[u8], hint_epoch: u64) -> Option<EpochKey> {
        let real = mls_message_epoch(mls_payload);
        let inner = self.inner.read().ok()?;
        let list = inner.by_group.get(group_id)?;
        let pick = |e: u64| list.iter().find(|k| k.epoch == e).cloned();
        real.and_then(pick).or_else(|| pick(hint_epoch)).or_else(|| list.last().cloned())
    }

    /// Hex routing tags for the live subscription (newest epochs of every group).
    pub fn subscription_tags(&self) -> Vec<String> {
        self.subscription_tags_where(|_| true)
    }

    /// [`Self::subscription_tags`] of the groups `keep` accepts.
    pub fn subscription_tags_where(&self, keep: impl Fn(&[u8]) -> bool) -> Vec<String> {
        let Ok(inner) = self.inner.read() else { return Vec::new() };
        let mut tags = Vec::new();
        for list in inner.by_group.iter().filter(|(g, _)| keep(g)).map(|(_, l)| l) {
            for k in list.iter().rev().take(SUBSCRIBE_EPOCHS_PER_GROUP) {
                tags.push(hex::encode(k.tag));
            }
        }
        tags.sort();
        tags
    }

    /// Hex routing tags of every kept epoch (catch-up).
    pub fn all_tags(&self) -> Vec<String> {
        self.all_tags_where(|_| true)
    }

    /// [`Self::all_tags`] of the groups `keep` accepts.
    pub fn all_tags_where(&self, keep: impl Fn(&[u8]) -> bool) -> Vec<String> {
        let Ok(inner) = self.inner.read() else { return Vec::new() };
        let mut tags: Vec<String> = inner.by_tag.iter()
            .filter(|(_, k)| keep(&k.group_id))
            .map(|(t, _)| hex::encode(t))
            .collect();
        tags.sort();
        tags
    }

    pub fn export_json(&self) -> String {
        let Ok(inner) = self.inner.read() else { return "[]".into() };
        let all: Vec<StoredKey> = inner
            .by_tag
            .values()
            .map(|k| StoredKey { g: hex::encode(&k.group_id), e: k.epoch, t: hex::encode(k.tag), k: hex::encode(k.key) })
            .collect();
        serde_json::to_string(&all).unwrap_or_else(|_| "[]".into())
    }

    pub fn import_json(&self, json: &str) {
        let Ok(list) = serde_json::from_str::<Vec<StoredKey>>(json) else { return };
        for s in list {
            let (Ok(g), Some(tag), Ok(key)) = (hex::decode(&s.g), parse_tag(&s.t), hex::decode(&s.k)) else { continue };
            let Ok(key) = <Key32>::try_from(key.as_slice()) else { continue };
            self.insert(EpochKey { group_id: g, epoch: s.e, tag, key });
        }
    }
}

// --- Own published events (self-echo filter) ---

/// Ids of events we published, so their relay echo is not fed back into MLS
/// (the author key is one-time, so it can't be used to recognise them).
#[derive(Debug, Default)]
pub struct OwnEvents {
    set: HashSet<String>,
    order: VecDeque<String>,
}

impl OwnEvents {
    pub fn insert(&mut self, id: String) {
        if self.set.insert(id.clone()) {
            self.order.push_back(id);
            while self.order.len() > OWN_EVENTS_CAP {
                if let Some(old) = self.order.pop_front() {
                    self.set.remove(&old);
                }
            }
        }
    }

    pub fn contains(&self, id: &str) -> bool {
        self.set.contains(id)
    }
}

pub type SharedOwnEvents = Arc<Mutex<OwnEvents>>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seal_roundtrip_and_tag_binding() {
        let (tag, key) = derive_epoch_keys(&[7u8; 32]);
        let sealed = seal(&key, &tag, b"hello").unwrap();
        assert_eq!(unseal(&key, &tag, &sealed).unwrap(), b"hello");
        let (other_tag, _) = derive_epoch_keys(&[8u8; 32]);
        assert!(unseal(&key, &other_tag, &sealed).is_none());
    }

    #[test]
    fn sealing_is_deterministic_per_message() {
        let (tag, key) = derive_epoch_keys(&[3u8; 32]);
        assert_eq!(seal(&key, &tag, b"a"), seal(&key, &tag, b"a"));
        assert_ne!(seal(&key, &tag, b"a"), seal(&key, &tag, b"b"));
        assert_eq!(event_signer(&key, b"a").public_key(), event_signer(&key, b"a").public_key());
        assert_ne!(event_signer(&key, b"a").public_key(), event_signer(&key, b"b").public_key());
    }

    #[test]
    fn padding_hides_length() {
        let (tag, key) = derive_epoch_keys(&[1u8; 32]);
        let a = seal(&key, &tag, &[0u8; 3]).unwrap();
        let b = seal(&key, &tag, &[0u8; 200]).unwrap();
        assert_eq!(a.len(), b.len());
    }

    #[test]
    fn ring_keeps_recent_epochs_and_picks_hint() {
        let ring = TransportKeyRing::default();
        for e in 0..6u64 {
            assert!(ring.insert_epoch(b"g", e, &[e as u8; 32]));
        }
        assert_eq!(ring.all_tags().len(), KEEP_EPOCHS_PER_GROUP);
        assert_eq!(ring.subscription_tags().len(), SUBSCRIBE_EPOCHS_PER_GROUP);
        assert_eq!(ring.key_for_message(b"g", b"", 4).unwrap().epoch, 4);
        assert_eq!(ring.key_for_message(b"g", b"", 0).unwrap().epoch, 5);
        let restored = TransportKeyRing::default();
        restored.import_json(&ring.export_json());
        assert_eq!(restored.all_tags(), ring.all_tags());
    }

    #[test]
    fn presence_tags_rotate_hourly() {
        let s = [9u8; 32];
        assert_ne!(presence_tag(&s, 1), presence_tag(&s, 2));
        assert!(wake_topic(&s).starts_with("am"));
    }
}
