//! Resource limits and chunk-context validation.
//!
//! Everything arriving from the other side is untrusted numbers. A manifest
//! may declare a million chunks, a size in gigabytes or a string of hundreds
//! of megabytes, and trusting these numbers BEFORE validation lets the app
//! kill itself: allocate memory to a foreign spec, base64-decode an unknown
//! size, or fan out requests to a list from a foreign file. So the limits
//! stand BEFORE every expensive operation, not after.
//!
//! Values are derived from the product cap, not picked at random.

use crate::error::{MediaError, MediaResult};

/// V1 attachment size cap.
///
/// The product had no explicit media cap — Android enforces per-type
/// `AttachmentLimits`, but there was no end-to-end transport value. Without one
/// neither the chunk count nor the manifest size can be derived. The cap is
/// defined here explicitly and conservatively, above current sends (hundreds
/// of megabytes), and it is VERSIONED: raising it is a deliberate decision,
/// not a side effect.
pub const MAX_MEDIA_BYTES: u64 = 2 * 1024 * 1024 * 1024; // 2 GiB

/// Stream chunk size. Matches `crypto::STREAM_CHUNK_SIZE`.
pub const CHUNK_SIZE: u64 = 256 * 1024;

/// Chunk maximum — computed, not assigned.
pub const MAX_CHUNKS: u32 = ((MAX_MEDIA_BYTES + CHUNK_SIZE - 1) / CHUNK_SIZE) as u32;

/// Mirrors per chunk. More is either an error or an attempt to fan out
/// network requests by foreign hands.
pub const MAX_LOCATIONS_PER_CHUNK: usize = 8;

/// A manifest describes up to [`MAX_CHUNKS`] chunks; this cap is comfortably
/// enough for a record with addresses and hashes.
pub const MAX_MANIFEST_BYTES: u64 = 64 * 1024 * 1024;

/// Envelope: chunk ciphertext plus base64 and JSON wrapping (~1.34×), with headroom.
pub const MAX_ENVELOPE_BYTES: u64 = 2 * 1024 * 1024;

pub const MAX_URL_LENGTH: usize = 2048;
pub const MAX_METADATA_STRING: usize = 512;

fn deny(what: &str) -> MediaError {
    MediaError::SerializationError(format!("limits: {what}"))
}

/// Validate declared chunk sizes BEFORE allocating memory.
pub fn check_declared_sizes(original_size: u64, ciphertext_size: u64) -> MediaResult<()> {
    if original_size > CHUNK_SIZE {
        return Err(deny(&format!("original_size {original_size} exceeds the chunk size")));
    }
    if ciphertext_size > MAX_ENVELOPE_BYTES {
        return Err(deny(&format!("ciphertext_size {ciphertext_size} exceeds the envelope limit")));
    }
    Ok(())
}

/// Expectations for a chunk, taken from an AUTHENTICATED source (manifest/ref).
///
/// Envelope fields are not a source of truth: they sit outside AEAD and are
/// not cryptographically bound. They can only be CROSS-CHECKED against these values.
#[derive(Debug, Clone, Copy)]
pub struct ChunkContext {
    pub expected_index: u32,
    pub expected_total: u32,
}

/// Cross-check an envelope against expectations. Mismatch — refuse before using the data.
pub fn check_chunk_context(ctx: ChunkContext, envelope_index: u32, envelope_total: u32) -> MediaResult<()> {
    if ctx.expected_total == 0 {
        return Err(deny("the expected chunk count is zero"));
    }
    if ctx.expected_total > MAX_CHUNKS {
        return Err(deny(&format!("chunk count {} exceeds the limit {MAX_CHUNKS}", ctx.expected_total)));
    }
    if ctx.expected_index >= ctx.expected_total {
        return Err(deny("chunk index out of range"));
    }
    if envelope_index != ctx.expected_index {
        return Err(deny(&format!(
            "envelope index {envelope_index} does not match the expected {}", ctx.expected_index
        )));
    }
    if envelope_total != ctx.expected_total {
        return Err(deny(&format!(
            "envelope chunk count {envelope_total} does not match the expected {}", ctx.expected_total
        )));
    }
    Ok(())
}

/// Validate that the chunk descriptor sequence is well-formed.
///
/// Nothing is sorted or repaired: an invalid manifest is REJECTED. Silent
/// sorting would "heal" a reordering attack exactly enough to hide it while
/// leaving it possible.
pub fn check_sequence(indices: &[u32], expected_total: u32) -> MediaResult<()> {
    if expected_total == 0 || expected_total > MAX_CHUNKS {
        return Err(deny("invalid chunk count"));
    }
    if indices.len() != expected_total as usize {
        return Err(deny(&format!(
            "{} chunks instead of {expected_total} (gap or duplicate)", indices.len()
        )));
    }
    for (position, index) in indices.iter().enumerate() {
        if *index != position as u32 {
            return Err(deny(&format!(
                "chunk at position {position} declared as {index} (reorder or duplicate)"
            )));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx(i: u32, t: u32) -> ChunkContext {
        ChunkContext { expected_index: i, expected_total: t }
    }

    #[test]
    fn total_context_binding() {
        assert!(check_chunk_context(ctx(2, 5), 2, 5).is_ok());
        assert!(check_chunk_context(ctx(2, 5), 2, 4).is_err(), "total -1");
        assert!(check_chunk_context(ctx(2, 5), 2, 6).is_err(), "total +1");
        assert!(check_chunk_context(ctx(2, 5), 3, 5).is_err(), "foreign index");
        assert!(check_chunk_context(ctx(0, 0), 0, 0).is_err(), "zero total");
        assert!(check_chunk_context(ctx(5, 5), 5, 5).is_err(), "index out of range");
        assert!(check_chunk_context(ctx(0, u32::MAX), 0, u32::MAX).is_err(), "huge total");
    }

    #[test]
    fn duplicate_omission_reorder_rejected() {
        assert!(check_sequence(&[0, 1, 2], 3).is_ok());
        assert!(check_sequence(&[0, 0, 2], 3).is_err(), "duplicate");
        assert!(check_sequence(&[0, 2], 3).is_err(), "gap");
        assert!(check_sequence(&[0, 1, 2, 2], 3).is_err(), "extra");
        assert!(check_sequence(&[0, 2, 1], 3).is_err(), "reorder");
        assert!(check_sequence(&[0, 1], 3).is_err(), "last missing");
    }

    #[test]
    fn size_tamper_rejected() {
        assert!(check_declared_sizes(CHUNK_SIZE, CHUNK_SIZE + 64).is_ok());
        assert!(check_declared_sizes(CHUNK_SIZE + 1, 100).is_err(), "chunk over the limit");
        assert!(check_declared_sizes(u64::MAX, 100).is_err(), "huge original_size");
        assert!(check_declared_sizes(100, u64::MAX).is_err(), "huge ciphertext_size");
    }

    #[test]
    fn limits_are_derived_not_invented() {
        assert_eq!(MAX_CHUNKS as u64, MAX_MEDIA_BYTES.div_ceil(CHUNK_SIZE));
        assert!(MAX_ENVELOPE_BYTES > CHUNK_SIZE * 2, "the envelope must fit a base64 chunk");
    }
}
