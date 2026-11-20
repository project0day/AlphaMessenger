//! The stored form of a file's manifest (its full ref, chunk list included).
//!
//! The manifest grows with the file, so its size would tell a server how big the file
//! is. It is sealed with its length inside the ciphertext and padded to a power of two
//! (at least [`MIN_SEALED`]): a server learns at most the order of magnitude.
//!
//! Layout: `MAGIC(8) || nonce(24) || blake3(padded plaintext)(32) || ciphertext`, where
//! the plaintext is `len(8, LE) || manifest || zeros`. Manifests stored before
//! 2026-11-20 are `nonce(24) || blake3(32) || len(8, LE) || ciphertext` and still open.

use crate::crypto::{EncryptedMedia, MediaCrypto};
use crate::error::{MediaError, MediaResult};

const MAGIC: &[u8; 8] = b"AMMFST02";
const HEADER: usize = 64;
const TAG: usize = 16;
/// The smallest sealed manifest: a small file's list costs this much, a large one's no less.
const MIN_SEALED: usize = 64 * 1024;

/// Seal `manifest` with `key` into a blob of a power-of-two size.
pub fn seal(manifest: &[u8], key: &[u8; 32]) -> MediaResult<Vec<u8>> {
    let needed = HEADER + 8 + manifest.len() + TAG;
    let size = needed.next_power_of_two().max(MIN_SEALED);
    let mut plain = Vec::with_capacity(size - HEADER - TAG);
    plain.extend_from_slice(&(manifest.len() as u64).to_le_bytes());
    plain.extend_from_slice(manifest);
    plain.resize(size - HEADER - TAG, 0);
    let enc = MediaCrypto::encrypt(&plain, key)?;
    let mut blob = Vec::with_capacity(size);
    blob.extend_from_slice(MAGIC);
    blob.extend_from_slice(&enc.nonce);
    blob.extend_from_slice(&enc.hash);
    blob.extend_from_slice(&enc.ciphertext);
    debug_assert_eq!(blob.len(), size);
    Ok(blob)
}

/// Open a sealed manifest, in either layout.
pub fn open(blob: &[u8], key: &[u8; 32]) -> MediaResult<Vec<u8>> {
    let short = || MediaError::DecryptionFailed("manifest blob too short".into());
    if blob.len() < HEADER + TAG {
        return Err(short());
    }
    let mut nonce = [0u8; 24];
    let mut hash = [0u8; 32];
    if blob.starts_with(MAGIC) {
        nonce.copy_from_slice(&blob[8..32]);
        hash.copy_from_slice(&blob[32..64]);
        let plain = MediaCrypto::decrypt(
            &EncryptedMedia { nonce, hash, original_size: 0, ciphertext: blob[HEADER..].to_vec() },
            key,
        )?;
        let len = u64::from_le_bytes(plain.get(..8).ok_or_else(short)?.try_into().map_err(|_| short())?) as usize;
        return plain.get(8..8usize.saturating_add(len)).map(<[u8]>::to_vec).ok_or_else(short);
    }
    nonce.copy_from_slice(&blob[0..24]);
    hash.copy_from_slice(&blob[24..56]);
    let original_size = u64::from_le_bytes(blob[56..64].try_into().map_err(|_| short())?);
    MediaCrypto::decrypt(&EncryptedMedia { nonce, hash, original_size, ciphertext: blob[HEADER..].to_vec() }, key)
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [9u8; 32];

    fn legacy(manifest: &[u8]) -> Vec<u8> {
        let enc = MediaCrypto::encrypt(manifest, &KEY).unwrap();
        let mut blob = enc.nonce.to_vec();
        blob.extend_from_slice(&enc.hash);
        blob.extend_from_slice(&enc.original_size.to_le_bytes());
        blob.extend_from_slice(&enc.ciphertext);
        blob
    }

    #[test]
    fn a_manifest_comes_back_whole() {
        let manifest = br#"{"chunks":[1,2,3]}"#.to_vec();
        assert_eq!(open(&seal(&manifest, &KEY).unwrap(), &KEY).unwrap(), manifest);
    }

    #[test]
    fn its_size_tells_only_the_order_of_magnitude() {
        let small = seal(&vec![b'a'; 3_000], &KEY).unwrap();
        let medium = seal(&vec![b'a'; 40_000], &KEY).unwrap();
        let large = seal(&vec![b'a'; 70_000], &KEY).unwrap();
        assert_eq!(small.len(), MIN_SEALED);
        assert_eq!(medium.len(), MIN_SEALED, "anything under 64 KiB looks the same");
        assert_eq!(large.len(), 128 * 1024);
        // No clear length anywhere: the bytes after the magic are nonce, hash and ciphertext.
        assert!(!large.windows(8).any(|w| w == (70_000u64).to_le_bytes()));
    }

    #[test]
    fn manifests_stored_before_still_open() {
        let manifest = br#"{"old":true}"#.to_vec();
        assert_eq!(open(&legacy(&manifest), &KEY).unwrap(), manifest);
    }

    #[test]
    fn a_wrong_key_or_a_changed_byte_is_refused() {
        let mut blob = seal(b"{}", &KEY).unwrap();
        assert!(open(&blob, &[1u8; 32]).is_err());
        let last = blob.len() - 1;
        blob[last] ^= 1;
        assert!(open(&blob, &KEY).is_err());
    }
}
