use serde::{Deserialize, Serialize};

use crate::crypto::EncryptedMedia;
use crate::error::{MediaError, MediaResult};

/// Default chunk size: 512 KB.
pub const CHUNK_SIZE: usize = 512 * 1024;

/// A single chunk of a split file.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Chunk {
    /// Zero-based index of this chunk.
    pub index: u32,
    /// Total number of chunks.
    pub total: u32,
    /// Chunk data.
    pub data: Vec<u8>,
}

/// Split raw data into fixed-size chunks.
///
/// Each chunk carries its index, the total number of chunks, and a slice
/// of the data. The last chunk may be smaller than `CHUNK_SIZE`.
pub fn split(data: &[u8]) -> Vec<Chunk> {
    if data.is_empty() {
        return vec![Chunk {
            index: 0,
            total: 1,
            data: Vec::new(),
        }];
    }

    let total = data.len().div_ceil(CHUNK_SIZE) as u32;
    let mut chunks = Vec::with_capacity(total as usize);

    for (i, start) in (0..data.len()).step_by(CHUNK_SIZE).enumerate() {
        let end = std::cmp::min(start + CHUNK_SIZE, data.len());
        chunks.push(Chunk {
            index: i as u32,
            total,
            data: data[start..end].to_vec(),
        });
    }

    chunks
}

/// Reassemble chunks back into the original data.
///
/// Chunks are sorted by index and concatenated in order.
pub fn join(chunks: Vec<Chunk>) -> Vec<u8> {
    let mut sorted = chunks;
    sorted.sort_by_key(|c| c.index);

    sorted.iter().flat_map(|c| c.data.clone()).collect()
}

/// Chunker for encrypted media — handles serialization before splitting
/// and deserialization after joining.
pub struct EncryptedChunker;

impl EncryptedChunker {
    /// Serialize an `EncryptedMedia` and split it into raw byte chunks
    /// (each chunk is simply a slice of the serialized bytes).
    pub fn split_encrypted(encrypted: &EncryptedMedia) -> MediaResult<Vec<Vec<u8>>> {
        let serialized = serde_json::to_vec(encrypted)
            .map_err(|e| MediaError::ChunkingError(format!("Failed to serialize EncryptedMedia: {e}")))?;

        if serialized.is_empty() {
            return Ok(vec![Vec::new()]);
        }

        let chunk_count = serialized.len().div_ceil(CHUNK_SIZE);
        let mut chunks = Vec::with_capacity(chunk_count);

        for start in (0..serialized.len()).step_by(CHUNK_SIZE) {
            let end = std::cmp::min(start + CHUNK_SIZE, serialized.len());
            chunks.push(serialized[start..end].to_vec());
        }

        Ok(chunks)
    }

    /// Join raw byte chunks and deserialize back into an `EncryptedMedia`.
    pub fn join_encrypted(chunks: &[Vec<u8>]) -> MediaResult<EncryptedMedia> {
        if chunks.is_empty() {
            return Err(MediaError::ChunkingError("No chunks provided".into()));
        }

        let total_len: usize = chunks.iter().map(|c| c.len()).sum();
        let mut combined = Vec::with_capacity(total_len);
        for chunk in chunks {
            combined.extend_from_slice(chunk);
        }

        let encrypted: EncryptedMedia = serde_json::from_slice(&combined)
            .map_err(|e| MediaError::ChunkingError(format!("Failed to deserialize EncryptedMedia: {e}")))?;

        Ok(encrypted)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_split_and_join_roundtrip() {
        let data = vec![0xAB; CHUNK_SIZE * 2 + 123]; // 2 full chunks + partial
        let chunks = split(&data);
        assert_eq!(chunks.len(), 3);
        assert_eq!(chunks[0].index, 0);
        assert_eq!(chunks[0].total, 3);
        assert_eq!(chunks[0].data.len(), CHUNK_SIZE);
        assert_eq!(chunks[1].data.len(), CHUNK_SIZE);
        assert_eq!(chunks[2].data.len(), 123);

        let reassembled = join(chunks);
        assert_eq!(reassembled, data);
    }

    #[test]
    fn test_split_empty_data() {
        let data: Vec<u8> = Vec::new();
        let chunks = split(&data);
        assert_eq!(chunks.len(), 1);
        assert!(chunks[0].data.is_empty());

        let reassembled = join(chunks);
        assert!(reassembled.is_empty());
    }

    #[test]
    fn test_split_single_chunk() {
        let data = vec![0x42; 100];
        let chunks = split(&data);
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0].total, 1);

        let reassembled = join(chunks);
        assert_eq!(reassembled, data);
    }

    #[test]
    fn test_join_unordered_chunks() {
        let data = vec![0x42; CHUNK_SIZE + 100];
        let mut chunks = split(&data);
        // Reverse the order
        chunks.reverse();

        let reassembled = join(chunks);
        assert_eq!(reassembled, data);
    }

    #[test]
    fn test_encrypted_chunker_roundtrip() {
        use crate::crypto::{MediaCrypto, generate_key};

        let key = generate_key();
        let plaintext = b"Test data for encrypted chunking roundtrip";
        let encrypted = MediaCrypto::encrypt(plaintext, &key).expect("encrypt should work");

        let chunks = EncryptedChunker::split_encrypted(&encrypted).expect("split should work");
        assert!(!chunks.is_empty());

        let reassembled = EncryptedChunker::join_encrypted(&chunks).expect("join should work");
        assert_eq!(reassembled.nonce, encrypted.nonce);
        assert_eq!(reassembled.ciphertext, encrypted.ciphertext);
        assert_eq!(reassembled.hash, encrypted.hash);
        assert_eq!(reassembled.original_size, encrypted.original_size);
    }

    #[test]
    fn test_join_encrypted_empty_chunks() {
        let result = EncryptedChunker::join_encrypted(&[]);
        assert!(result.is_err());
    }
}
