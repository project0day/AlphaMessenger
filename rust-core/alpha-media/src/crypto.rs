use chacha20poly1305::{
    aead::{Aead, KeyInit, OsRng},
    XChaCha20Poly1305, XNonce,
};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::error::{MediaError, MediaResult};

/// Encrypted media container with nonce, ciphertext, hash, and original size.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedMedia {
    /// XChaCha20 nonce (24 bytes).
    pub nonce: [u8; 24],
    /// Encrypted data (ciphertext + Poly1305 authentication tag).
    pub ciphertext: Vec<u8>,
    /// BLAKE3 hash of the original plaintext.
    pub hash: [u8; 32],
    /// Original file size in bytes.
    pub original_size: u64,
}

/// A single independently-encrypted chunk for streaming E2EE.
///
/// Each chunk has its own AEAD boundary (nonce + ciphertext + Poly1305 tag),
/// so it can be downloaded, decrypted and verified independently of all
/// other chunks. This is the foundation for streaming playback: ExoPlayer
/// can start playing as soon as the first chunk(s) are available.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedStreamChunk {
    /// Zero-based chunk index.
    pub index: u32,
    /// XChaCha20 nonce used for this chunk (24 bytes, derived from master key).
    pub nonce: [u8; 24],
    /// Ciphertext + Poly1305 authentication tag.
    pub ciphertext: Vec<u8>,
    /// BLAKE3 hash of the plaintext chunk data.
    pub hash: [u8; 32],
    /// Original plaintext size of this chunk (last chunk may be smaller).
    pub original_size: u64,
}

/// Cryptographic operations for media encryption/decryption.
pub struct MediaCrypto;

impl MediaCrypto {
    /// Encrypt data using XChaCha20-Poly1305 with the given key.
    ///
    /// Returns an `EncryptedMedia` containing the random nonce, ciphertext
    /// (with Poly1305 tag), BLAKE3 hash of the plaintext, and the original size.
    pub fn encrypt(data: &[u8], key: &[u8; 32]) -> MediaResult<EncryptedMedia> {
        let cipher = XChaCha20Poly1305::new_from_slice(key)
            .map_err(|e| MediaError::EncryptionFailed(format!("Invalid key: {e}")))?;

        // Generate a random 24-byte nonce for XChaCha20 (CSPRNG — OsRng uses OS entropy)
        let mut nonce_bytes = [0u8; 24];
        OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = XNonce::from_slice(&nonce_bytes);

        // Encrypt the data
        let ciphertext = cipher
            .encrypt(nonce, data)
            .map_err(|e| MediaError::EncryptionFailed(format!("Encryption failed: {e}")))?;

        // Compute BLAKE3 hash of the plaintext
        let hash = blake3::hash(data).into();

        Ok(EncryptedMedia {
            nonce: nonce_bytes,
            ciphertext,
            hash,
            original_size: data.len() as u64,
        })
    }

    /// Decrypt an `EncryptedMedia` using the given key.
    ///
    /// After decryption, verifies that the BLAKE3 hash of the decrypted
    /// plaintext matches the stored hash. Returns an integrity error on mismatch.
    ///
    /// **Security note**: The caller is responsible for zeroizing the
    /// returned plaintext after use. Use `zeroize::Zeroize` on the `Vec<u8>`
    /// when done.
    pub fn decrypt(encrypted: &EncryptedMedia, key: &[u8; 32]) -> MediaResult<Vec<u8>> {
        let cipher = XChaCha20Poly1305::new_from_slice(key)
            .map_err(|e| MediaError::DecryptionFailed(format!("Invalid key: {e}")))?;

        let nonce = XNonce::from_slice(&encrypted.nonce);

        // Decrypt the data
        let plaintext = cipher
            .decrypt(nonce, encrypted.ciphertext.as_ref())
            .map_err(|e| MediaError::DecryptionFailed(format!("Decryption failed: {e}")))?;

        // Verify BLAKE3 hash of the decrypted plaintext
        let computed_hash: [u8; 32] = blake3::hash(&plaintext).into();
        if computed_hash != encrypted.hash {
            return Err(MediaError::IntegrityCheckFailed {
                expected: hex::encode(encrypted.hash),
                actual: hex::encode(computed_hash),
            });
        }

        Ok(plaintext)
    }
}

/// Cryptographic operations for per-chunk streaming E2EE.
///
/// Each chunk is encrypted independently with its own XChaCha20-Poly1305
/// nonce derived from the master key + chunk index via BLAKE3 keyed hash.
/// This enables:
/// - Playback after downloading just the first chunk(s)
/// - Seeking by downloading only the required chunk
/// - Resume after network interruption (chunk-level granularity)
///
/// # Security
///
/// The master key is transported inside an MLS-encrypted message, so it
/// is never visible to the Blossom relay or Nostr relays. Per-chunk nonces
/// are derived deterministically (BLAKE3 keyed hash), which is safe because
/// the (key, index) pair is unique per file. Each chunk has its own AEAD
/// boundary with a Poly1305 authentication tag for integrity.
pub struct StreamCrypto;

/// Chunk size for streaming encryption: 256 KB.
/// Smaller than the upload chunk size (512 KB) so that playback can start
/// faster (first chunk arrives sooner) and seeking is more granular.
pub const STREAM_CHUNK_SIZE: usize = 256 * 1024;

impl StreamCrypto {
    /// Derive a deterministic 24-byte XChaCha20 nonce from the master key
    /// and chunk index using BLAKE3 keyed hashing.
    ///
    /// `nonce = BLAKE3(key=master_key, input="alpha-stream-chunk-v1:" || le_u32(index))[0..24]`
    ///
    /// This is cryptographically sound: BLAKE3 keyed mode is a PRF, so
    /// different (key, index) pairs produce independent nonces.
    pub fn derive_nonce(master_key: &[u8; 32], chunk_index: u32) -> [u8; 24] {
        let mut input = b"alpha-stream-chunk-v1:".to_vec();
        input.extend_from_slice(&chunk_index.to_le_bytes());
        let keyed_hash = blake3::keyed_hash(master_key, &input);
        let full = *keyed_hash.as_bytes();
        let mut nonce = [0u8; 24];
        nonce.copy_from_slice(&full[..24]);
        nonce
    }

    /// Encrypt a single plaintext chunk with its own nonce.
    ///
    /// Returns an `EncryptedStreamChunk` containing the nonce, ciphertext
    /// (with Poly1305 tag), BLAKE3 hash of the plaintext, and original size.
    pub fn encrypt_chunk(
        data: &[u8],
        master_key: &[u8; 32],
        chunk_index: u32,
    ) -> MediaResult<EncryptedStreamChunk> {
        let cipher = XChaCha20Poly1305::new_from_slice(master_key)
            .map_err(|e| MediaError::EncryptionFailed(format!("Invalid key: {e}")))?;

        let nonce_bytes = Self::derive_nonce(master_key, chunk_index);
        let nonce = XNonce::from_slice(&nonce_bytes);

        let ciphertext = cipher
            .encrypt(nonce, data)
            .map_err(|e| MediaError::EncryptionFailed(format!("Chunk {chunk_index} encrypt failed: {e}")))?;

        let hash = blake3::hash(data).into();

        Ok(EncryptedStreamChunk {
            index: chunk_index,
            nonce: nonce_bytes,
            ciphertext,
            hash,
            original_size: data.len() as u64,
        })
    }

    /// Decrypt a single stream chunk.
    ///
    /// Verifies the BLAKE3 hash of the decrypted plaintext against the
    /// stored hash. Returns the plaintext on success, or an integrity error.
    pub fn decrypt_chunk(
        chunk: &EncryptedStreamChunk,
        master_key: &[u8; 32],
    ) -> MediaResult<Vec<u8>> {
        let cipher = XChaCha20Poly1305::new_from_slice(master_key)
            .map_err(|e| MediaError::DecryptionFailed(format!("Invalid key: {e}")))?;

        // Verify the nonce matches our derivation (tamper detection)
        let expected_nonce = Self::derive_nonce(master_key, chunk.index);
        if expected_nonce != chunk.nonce {
            return Err(MediaError::IntegrityCheckFailed {
                expected: format!("derived nonce for chunk {}", chunk.index),
                actual: "stored nonce mismatch (possible tampering)".into(),
            });
        }

        let nonce = XNonce::from_slice(&chunk.nonce);
        let plaintext = cipher
            .decrypt(nonce, chunk.ciphertext.as_ref())
            .map_err(|e| MediaError::DecryptionFailed(format!("Chunk {} decrypt failed: {e}", chunk.index)))?;

        let computed_hash: [u8; 32] = blake3::hash(&plaintext).into();
        if computed_hash != chunk.hash {
            return Err(MediaError::IntegrityCheckFailed {
                expected: hex::encode(chunk.hash),
                actual: hex::encode(computed_hash),
            });
        }

        Ok(plaintext)
    }

    /// Encrypt a file for streaming: split into `STREAM_CHUNK_SIZE` plaintext
    /// chunks and encrypt each independently.
    ///
    /// Returns the vector of encrypted chunks (ready for individual upload).
    pub fn encrypt_stream(
        data: &[u8],
        master_key: &[u8; 32],
    ) -> MediaResult<Vec<EncryptedStreamChunk>> {
        if data.is_empty() {
            return Err(MediaError::EncryptionFailed("Cannot encrypt empty stream".into()));
        }

        let chunk_count = data.len().div_ceil(STREAM_CHUNK_SIZE);
        let mut chunks = Vec::with_capacity(chunk_count);

        for (i, start) in (0..data.len()).step_by(STREAM_CHUNK_SIZE).enumerate() {
            let end = std::cmp::min(start + STREAM_CHUNK_SIZE, data.len());
            let chunk = Self::encrypt_chunk(&data[start..end], master_key, i as u32)?;
            chunks.push(chunk);
        }

        Ok(chunks)
    }

    /// Decrypt all stream chunks and concatenate into the original file.
    ///
    /// Each chunk is independently verified (hash + AEAD tag) before
    /// concatenation. If any chunk fails, the entire operation fails.
    pub fn decrypt_stream(
        chunks: &[EncryptedStreamChunk],
        master_key: &[u8; 32],
    ) -> MediaResult<Vec<u8>> {
        if chunks.is_empty() {
            return Err(MediaError::DecryptionFailed("No stream chunks provided".into()));
        }

        let mut sorted = chunks.to_vec();
        sorted.sort_by_key(|c| c.index);

        let total_plaintext: u64 = sorted.iter().map(|c| c.original_size).sum();
        let mut result = Vec::with_capacity(total_plaintext as usize);

        for chunk in &sorted {
            let plaintext = Self::decrypt_chunk(chunk, master_key)?;
            result.extend_from_slice(&plaintext);
        }

        Ok(result)
    }

    /// Decrypt a single chunk by index from a slice of stream chunks.
    ///
    /// Used by the streaming player to decrypt only the chunk it needs.
    pub fn decrypt_chunk_by_index(
        chunks: &[EncryptedStreamChunk],
        master_key: &[u8; 32],
        index: u32,
    ) -> MediaResult<Vec<u8>> {
        let chunk = chunks
            .iter()
            .find(|c| c.index == index)
            .ok_or_else(|| MediaError::DecryptionFailed(format!("Chunk {index} not found")))?;
        Self::decrypt_chunk(chunk, master_key)
    }
}

/// Generate a random 256-bit key suitable for XChaCha20-Poly1305 encryption.
///
/// Returns a `Zeroizing<[u8; 32]>` wrapper that automatically zeroizes
/// the key when dropped, preventing it from lingering in memory.
///
/// # Security
///
/// The key is generated using `OsRng` which is a CSPRNG backed by the
/// operating system's entropy source.
pub fn generate_key() -> Zeroizing<[u8; 32]> {
    let mut key = Zeroizing::new([0u8; 32]);
    OsRng.fill_bytes(&mut *key);
    key
}

/// Compute a BLAKE3 hash of the given data.
pub fn blake3_hash(data: &[u8]) -> [u8; 32] {
    blake3::hash(data).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = generate_key();
        let plaintext = b"Hello, AlphaMessenger! This is a test of the media encryption pipeline.";

        let encrypted = MediaCrypto::encrypt(plaintext, &key).expect("encryption should succeed");
        assert_eq!(encrypted.original_size, plaintext.len() as u64);
        assert_ne!(encrypted.ciphertext.as_slice(), plaintext.as_slice());

        let decrypted = MediaCrypto::decrypt(&encrypted, &key).expect("decryption should succeed");
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_decrypt_with_wrong_key_fails() {
        let key1 = generate_key();
        let key2 = generate_key();

        let plaintext = b"Secret data";
        let encrypted = MediaCrypto::encrypt(plaintext, &key1).expect("encryption should succeed");

        let result = MediaCrypto::decrypt(&encrypted, &key2);
        assert!(result.is_err());
    }

    #[test]
    fn test_hash_integrity_check() {
        let key = generate_key();
        let plaintext = b"Data with integrity check";
        let mut encrypted = MediaCrypto::encrypt(plaintext, &key).expect("encryption should succeed");

        // Tamper with the hash
        encrypted.hash[0] = encrypted.hash[0].wrapping_add(1);

        let result = MediaCrypto::decrypt(&encrypted, &key);
        assert!(matches!(result, Err(MediaError::IntegrityCheckFailed { .. })));
    }

    #[test]
    fn test_blake3_hash_deterministic() {
        let data = b"deterministic test";
        let hash1 = blake3_hash(data);
        let hash2 = blake3_hash(data);
        assert_eq!(hash1, hash2);
    }

    #[test]
    fn test_generate_key_unique() {
        let key1 = generate_key();
        let key2 = generate_key();
        assert_ne!(*key1, *key2);
    }

    #[test]
    fn test_stream_nonce_deterministic() {
        let key = generate_key();
        let n1 = StreamCrypto::derive_nonce(&key, 0);
        let n2 = StreamCrypto::derive_nonce(&key, 0);
        assert_eq!(n1, n2, "Same key+index must produce same nonce");
        let n3 = StreamCrypto::derive_nonce(&key, 1);
        assert_ne!(n1, n3, "Different index must produce different nonce");
    }

    #[test]
    fn test_stream_chunk_encrypt_decrypt_roundtrip() {
        let key = generate_key();
        let data = b"Hello streaming world! This is chunk data for per-chunk AEAD test.";
        let encrypted = StreamCrypto::encrypt_chunk(data, &key, 0).expect("encrypt should work");
        assert_eq!(encrypted.original_size, data.len() as u64);
        let decrypted = StreamCrypto::decrypt_chunk(&encrypted, &key).expect("decrypt should work");
        assert_eq!(decrypted, data);
    }

    #[test]
    fn test_stream_chunk_wrong_key_fails() {
        let key1 = generate_key();
        let key2 = generate_key();
        let data = b"secret chunk";
        let encrypted = StreamCrypto::encrypt_chunk(data, &key1, 0).expect("encrypt should work");
        let result = StreamCrypto::decrypt_chunk(&encrypted, &key2);
        assert!(result.is_err());
    }

    #[test]
    fn test_encrypt_decrypt_stream_roundtrip() {
        let key = generate_key();
        // Data larger than STREAM_CHUNK_SIZE to force multi-chunk
        let data = vec![0xAB; STREAM_CHUNK_SIZE * 2 + 12345];
        let chunks = StreamCrypto::encrypt_stream(&data, &key).expect("encrypt_stream should work");
        assert_eq!(chunks.len(), 3);
        assert_eq!(chunks[0].index, 0);
        assert_eq!(chunks[1].index, 1);
        assert_eq!(chunks[2].index, 2);
        let decrypted = StreamCrypto::decrypt_stream(&chunks, &key).expect("decrypt_stream should work");
        assert_eq!(decrypted, data);
    }

    #[test]
    fn test_decrypt_single_chunk_by_index() {
        let key = generate_key();
        let data = vec![0x42; STREAM_CHUNK_SIZE + 500];
        let chunks = StreamCrypto::encrypt_stream(&data, &key).expect("encrypt should work");
        // Decrypt only chunk 1
        let decrypted = StreamCrypto::decrypt_chunk_by_index(&chunks, &key, 1)
            .expect("decrypt chunk 1 should work");
        assert_eq!(decrypted.len(), 500);
        assert_eq!(decrypted, &data[STREAM_CHUNK_SIZE..]);
    }

    #[test]
    fn test_stream_tampered_chunk_detected() {
        let key = generate_key();
        let data = b"important streaming data";
        let mut chunk = StreamCrypto::encrypt_chunk(data, &key, 0).expect("encrypt should work");
        // Tamper with the ciphertext
        if let Some(byte) = chunk.ciphertext.get_mut(5) {
            *byte ^= 0xFF;
        }
        let result = StreamCrypto::decrypt_chunk(&chunk, &key);
        assert!(result.is_err(), "Tampered chunk must fail AEAD verification");
    }
}

#[cfg(test)]
mod security_gate {
    use super::*;
    use std::collections::HashSet;

    fn key(seed: u8) -> [u8; 32] { [seed; 32] }
    fn pt(n: usize, seed: u8) -> Vec<u8> { (0..n).map(|i| (i as u8) ^ seed).collect() }

    /// NONCE_UNIQUENESS: one key — different indices — always different nonces.
    ///
    /// This is a load-bearing property of XChaCha20-Poly1305: reusing the
    /// (key, nonce) pair on different data breaks confidentiality entirely.
    #[test]
    fn nonce_uniqueness() {
        let k = key(1);
        let mut seen = HashSet::new();
        for i in 0..10_000u32 {
            assert!(seen.insert(StreamCrypto::derive_nonce(&k, i)), "nonce repeated at index {i}");
        }
        // A different key — a different nonce space.
        let k2 = key(2);
        for i in [0u32, 1, 17, 9999] {
            assert_ne!(StreamCrypto::derive_nonce(&k, i), StreamCrypto::derive_nonce(&k2, i));
        }
        // Determinism: regenerating yields the same value — resume relies
        // on this.
        assert_eq!(StreamCrypto::derive_nonce(&k, 17), StreamCrypto::derive_nonce(&k, 17));
    }

    /// REORDER_REJECTED: chunk 17's ciphertext cannot be passed off as chunk 18.
    #[test]
    fn reorder_rejected() {
        let k = key(3);
        let c17 = StreamCrypto::encrypt_chunk(&pt(1000, 17), &k, 17).unwrap();
        let mut moved = c17.clone();
        moved.index = 18;                       // reordered
        moved.nonce = StreamCrypto::derive_nonce(&k, 18); // and re-derived the nonce
        assert!(
            StreamCrypto::decrypt_chunk(&moved, &k).is_err(),
            "a reordered chunk must be refused",
        );
        // The same chunk with only the index swapped — also rejected (nonce won't match).
        let mut idx_only = c17.clone();
        idx_only.index = 18;
        assert!(StreamCrypto::decrypt_chunk(&idx_only, &k).is_err());
    }

    /// CROSS_MEDIA_REJECTED: one file's chunk cannot be substituted into another.
    #[test]
    fn cross_media_rejected() {
        let (ka, kb) = (key(4), key(5));
        let a = StreamCrypto::encrypt_chunk(&pt(500, 1), &ka, 3).unwrap();
        assert!(
            StreamCrypto::decrypt_chunk(&a, &kb).is_err(),
            "a chunk of another file must be refused",
        );
    }

    /// AEAD_TAMPER_REJECTED: any change to ciphertext, tag or nonce.
    #[test]
    fn aead_tamper_rejected() {
        let k = key(6);
        let good = StreamCrypto::encrypt_chunk(&pt(777, 9), &k, 9).unwrap();

        let mut ct = good.clone();
        ct.ciphertext[10] ^= 0x01;
        assert!(StreamCrypto::decrypt_chunk(&ct, &k).is_err(), "a ciphertext bit");

        let mut tag = good.clone();
        let last = tag.ciphertext.len() - 1;
        tag.ciphertext[last] ^= 0x80;
        assert!(StreamCrypto::decrypt_chunk(&tag, &k).is_err(), "a Poly1305 tag bit");

        let mut nonce = good.clone();
        nonce.nonce[0] ^= 0xFF;
        assert!(StreamCrypto::decrypt_chunk(&nonce, &k).is_err(), "a swapped nonce");

        let mut trunc = good.clone();
        trunc.ciphertext.truncate(trunc.ciphertext.len() - 1);
        assert!(StreamCrypto::decrypt_chunk(&trunc, &k).is_err(), "truncation");
    }

    /// PLAINTEXT_HASH_TAMPER_REJECTED: a swapped BLAKE3 in the descriptor.
    #[test]
    fn plaintext_hash_tamper_rejected() {
        let k = key(7);
        let mut c = StreamCrypto::encrypt_chunk(&pt(300, 2), &k, 0).unwrap();
        c.hash[0] ^= 0xFF;
        assert!(
            StreamCrypto::decrypt_chunk(&c, &k).is_err(),
            "a BLAKE3 mismatch must refuse the chunk",
        );
    }

    /// The success path stays successful — otherwise the checks above mean nothing.
    #[test]
    fn honest_chunk_still_decrypts() {
        let k = key(8);
        let data = pt(4096, 33);
        let c = StreamCrypto::encrypt_chunk(&data, &k, 42).unwrap();
        assert_eq!(StreamCrypto::decrypt_chunk(&c, &k).unwrap(), data);
    }

    /// TOTAL_TAMPER: `total` in the envelope is NOT cryptographically bound.
    ///
    /// The test pins the REAL property, not the desired one: only the index
    /// enters the nonce and no AAD is used, so changing `total` does not
    /// break decryption. It is caught one level up — by the chunk count in
    /// the manifest, which is itself authenticated. What matters here is
    /// that the property is PINNED: anyone starting to trust `total` as an
    /// authenticated field will see from this test that there are no grounds.
    #[test]
    fn total_is_not_bound_to_chunk_aead() {
        let k = key(9);
        let c = StreamCrypto::encrypt_chunk(&pt(100, 1), &k, 5).unwrap();
        // Decryption does not depend on total — it is not in the chunk struct at all.
        assert!(StreamCrypto::decrypt_chunk(&c, &k).is_ok());
    }
}

#[cfg(test)]
mod t3_deterministic {
    use super::*;
    use crate::media_ref::TransportObjectId;

    /// LOAD-BEARING INVARIANT of media replication.
    ///
    /// Background resume of missing replicas is only possible if, after
    /// process death, the same immutable source produces a BYTE-FOR-BYTE
    /// identical encrypted object. Otherwise the copy has a different
    /// identifier and cannot be matched to the already-sent ref — a
    /// "replica" would be a different object the recipient does not expect.
    ///
    /// The whole chain is checked: key → nonce → AEAD → envelope → identity.
    #[test]
    fn same_source_and_key_yield_identical_transport_object() {
        let key = [0x42u8; 32];
        let plaintext: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();

        let produce = || {
            let enc = StreamCrypto::encrypt_chunk(&plaintext, &key, 7).unwrap();
            let blob = crate::blob_transport::active_codec()
                .encode_chunk(7, 12, &enc.ciphertext)
                .unwrap();
            let id = TransportObjectId::of_bytes(&blob);
            (enc.ciphertext, blob, id)
        };

        let (ct1, blob1, id1) = produce();
        let (ct2, blob2, id2) = produce();

        assert_eq!(ct1, ct2, "the ciphertext does not reproduce");
        assert_eq!(blob1, blob2, "the envelope does not reproduce");
        assert_eq!(id1, id2, "the object identity does not reproduce");
    }

    /// The inverse property: a different key — a different object. Without
    /// it, the first test would pass on degenerate encryption too.
    #[test]
    fn different_key_yields_different_object() {
        let plaintext = vec![7u8; 4096];
        let a = StreamCrypto::encrypt_chunk(&plaintext, &[1u8; 32], 0).unwrap();
        let b = StreamCrypto::encrypt_chunk(&plaintext, &[2u8; 32], 0).unwrap();
        assert_ne!(a.ciphertext, b.ciphertext);

        let ida = TransportObjectId::of_bytes(
            &crate::blob_transport::active_codec().encode_chunk(0, 1, &a.ciphertext).unwrap(),
        );
        let idb = TransportObjectId::of_bytes(
            &crate::blob_transport::active_codec().encode_chunk(0, 1, &b.ciphertext).unwrap(),
        );
        assert_ne!(ida, idb);
    }

    /// The key must be long and random enough — otherwise everything above
    /// is meaningless.
    #[test]
    fn generated_keys_are_32_bytes_and_distinct() {
        let a = generate_key();
        let b = generate_key();
        assert_eq!(a.len(), 32);
        assert_ne!(*a, *b, "two calls gave the same key");
    }
}
