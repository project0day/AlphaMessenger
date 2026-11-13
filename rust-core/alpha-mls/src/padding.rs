//! Message padding for metadata protection (C4).
//!
//! Before MLS encryption, plaintext messages are padded to the nearest
//! fixed-size bucket. This prevents an observer from inferring message
//! content from ciphertext length — a significant metadata leakage vector.
//!
//! # Padding scheme
//!
//! The padding scheme uses a simple length-prefix approach:
//!
//! ```text
//! [4 bytes: original_length (big-endian u32)][padded_content]
//! ```
//!
//! The `padded_content` is the original plaintext followed by zero-byte
//! padding to fill the bucket. On unpad, we read the original length
//! and truncate.
//!
//! # Bucket sizes
//!
//! Messages are padded to the nearest bucket from [`PADDING_BUCKETS`].
//! If a message exceeds the largest bucket, it is not padded (but the
//! length prefix is still prepended for consistent unpadding).
//!
//! # Security properties
//!
//! - An observer who sees two ciphertexts of the same size cannot
//!   distinguish whether they contain a short or long message (within
//!   the same bucket).
//! - The padding is applied *before* MLS encryption, so the relay
//!   sees only the padded ciphertext size.
//! - The original length is embedded in the padded data (not visible
//!   to the relay), allowing precise reconstruction.

use crate::error::MlsError;

/// Fixed-size padding buckets in bytes.
///
/// Messages are padded up to the nearest bucket size. The buckets
/// are chosen to provide reasonable granularity while minimizing
/// wasted bandwidth:
///
/// - **128** bytes: Short messages (emojis, quick replies)
/// - **256** bytes: Brief text messages
/// - **512** bytes: Medium messages
/// - **1024** bytes: Long messages
/// - **2048** bytes: Very long messages
/// - **4096** bytes: Extended messages
/// - **8192** bytes: Maximum common message size
/// - **16384** bytes: Very large messages
/// - **65536** bytes: Maximum supported size
///
/// Messages larger than the largest bucket are sent without padding
/// (but still with the 4-byte length prefix).
pub const PADDING_BUCKETS: [usize; 11] = [
    128,
    256,
    512,
    1024,
    2048,
    4096,
    8192,
    16384,
    // Finer steps above 16 KiB: a 64 KiB bucket turned a ~25 KB message into a
    // ~93 KB relay event that public relays reject ("event too large").
    // Above 40 KiB messages are not padded.
    24576,
    32768,
    40960,
];

/// The number of bytes used for the original-length prefix.
const LENGTH_PREFIX_SIZE: usize = 4;

/// Message padding engine.
///
/// Provides `pad` and `unpad` operations that transform plaintext
/// before MLS encryption and after MLS decryption, respectively.
#[derive(Debug, Clone, Copy)]
pub struct MessagePadder;

impl MessagePadder {
    /// Pad a plaintext message to the nearest fixed-size bucket.
    ///
    /// The output format is:
    /// ```text
    /// [4 bytes: original_length (big-endian u32)][plaintext || zero_padding]
    /// ```
    ///
    /// The total output length (including the 4-byte prefix) is
    /// equal to the nearest bucket size from [`PADDING_BUCKETS`].
    /// If the input exceeds the largest bucket, only the 4-byte
    /// length prefix is prepended (no bucket padding).
    ///
    /// # Arguments
    /// * `plaintext` - The original message bytes
    ///
    /// # Returns
    /// The padded message ready for MLS encryption.
    pub fn pad(plaintext: &[u8]) -> Vec<u8> {
        let original_len = plaintext.len() as u32;

        // Total size = length prefix + content
        let content_plus_prefix = LENGTH_PREFIX_SIZE + plaintext.len();

        // Find the appropriate bucket
        let target_size = Self::find_bucket(content_plus_prefix);

        let mut output = Vec::with_capacity(target_size);

        // Write original length as big-endian u32
        output.extend_from_slice(&original_len.to_be_bytes());

        // Write the original plaintext
        output.extend_from_slice(plaintext);

        // Pad with zeros to reach the target size
        let padding_needed = target_size.saturating_sub(output.len());
        output.resize(output.len() + padding_needed, 0u8);

        output
    }

    /// Remove padding from a decrypted message.
    ///
    /// This reads the 4-byte original-length prefix and truncates
    /// the output to the original message size, discarding the
    /// zero-byte padding.
    ///
    /// # Arguments
    /// * `padded` - The padded message from MLS decryption
    ///
    /// # Errors
    /// Returns [`MlsError::ReceiveMessage`] if the padded data is too
    /// short to contain the length prefix, or if the claimed original
    /// length exceeds the available data.
    pub fn unpad(padded: &[u8]) -> Result<Vec<u8>, MlsError> {
        if padded.len() < LENGTH_PREFIX_SIZE {
            return Err(MlsError::ReceiveMessage(
                "Padded message too short for length prefix".to_string()
            ));
        }

        // Read original length from big-endian u32
        let original_len = u32::from_be_bytes([
            padded[0],
            padded[1],
            padded[2],
            padded[3],
        ]) as usize;

        // Verify the claimed length doesn't exceed available data
        let available = padded.len() - LENGTH_PREFIX_SIZE;
        if original_len > available {
            return Err(MlsError::ReceiveMessage(format!(
                "Original length ({}) exceeds available data ({})",
                original_len, available
            )));
        }

        // Extract the original plaintext
        Ok(padded[LENGTH_PREFIX_SIZE..LENGTH_PREFIX_SIZE + original_len].to_vec())
    }

    /// Find the smallest bucket that fits the given size.
    ///
    /// If no bucket is large enough, returns `size` (no padding).
    fn find_bucket(size: usize) -> usize {
        for &bucket in &PADDING_BUCKETS {
            if size <= bucket {
                return bucket;
            }
        }
        // Message is larger than the largest bucket — no padding
        size
    }

    /// Get the bucket size that would be used for a given plaintext length.
    ///
    /// Useful for estimating bandwidth usage.
    pub fn bucket_for(plaintext_len: usize) -> usize {
        Self::find_bucket(LENGTH_PREFIX_SIZE + plaintext_len)
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pad_small_message() {
        let msg = b"hi";
        let padded = MessagePadder::pad(msg);

        // Should be padded to the first bucket (128 bytes)
        assert_eq!(padded.len(), PADDING_BUCKETS[0]);

        // Verify length prefix
        let len = u32::from_be_bytes([padded[0], padded[1], padded[2], padded[3]]);
        assert_eq!(len as usize, msg.len());

        // Verify the original message is present after the prefix
        assert_eq!(&padded[4..6], msg);
    }

    #[test]
    fn test_pad_exact_bucket() {
        // A message that exactly fills a bucket (minus the 4-byte prefix)
        let msg_len = PADDING_BUCKETS[0] - LENGTH_PREFIX_SIZE; // 124
        let msg = vec![0xABu8; msg_len];
        let padded = MessagePadder::pad(&msg);

        assert_eq!(padded.len(), PADDING_BUCKETS[0]);

        // Unpad should recover exactly
        let unpadded = MessagePadder::unpad(&padded).unwrap();
        assert_eq!(unpadded, msg);
    }

    #[test]
    fn test_pad_just_over_bucket() {
        // A message that's one byte over the first bucket
        let msg_len = PADDING_BUCKETS[0] - LENGTH_PREFIX_SIZE + 1; // 125
        let msg = vec![0xCDu8; msg_len];
        let padded = MessagePadder::pad(&msg);

        // Should be padded to the second bucket (256 bytes)
        assert_eq!(padded.len(), PADDING_BUCKETS[1]);

        let unpadded = MessagePadder::unpad(&padded).unwrap();
        assert_eq!(unpadded, msg);
    }

    #[test]
    fn test_pad_unpad_roundtrip() {
        let large_msg = vec![0x42u8; 500];
        let messages: Vec<&[u8]> = vec![
            b"",
            b"a",
            b"Hello, world!",
            b"This is a longer message that should fit in a medium bucket.",
            &large_msg,
        ];

        for msg in messages {
            let padded = MessagePadder::pad(msg);
            let unpadded = MessagePadder::unpad(&padded).unwrap();
            assert_eq!(unpadded.as_slice(), msg, "Roundtrip failed for message of length {}", msg.len());
        }
    }

    #[test]
    fn test_unpad_too_short() {
        let short = [0u8; 3]; // Less than LENGTH_PREFIX_SIZE
        let result = MessagePadder::unpad(&short);
        assert!(result.is_err());
    }

    #[test]
    fn test_unpad_invalid_length() {
        // Claim length of 1000 but only have 10 bytes of data
        let mut padded = vec![0u8; 20];
        padded[0..4].copy_from_slice(&1000u32.to_be_bytes());
        let result = MessagePadder::unpad(&padded);
        assert!(result.is_err());
    }

    #[test]
    fn test_pad_zero_length_message() {
        let padded = MessagePadder::pad(b"");
        assert_eq!(padded.len(), PADDING_BUCKETS[0]);

        let len = u32::from_be_bytes([padded[0], padded[1], padded[2], padded[3]]);
        assert_eq!(len, 0);

        let unpadded = MessagePadder::unpad(&padded).unwrap();
        assert!(unpadded.is_empty());
    }

    #[test]
    fn test_padding_buckets_are_sorted() {
        // Verify the bucket sizes are in ascending order
        for i in 1..PADDING_BUCKETS.len() {
            assert!(
                PADDING_BUCKETS[i] > PADDING_BUCKETS[i - 1],
                "Padding buckets must be in ascending order"
            );
        }
    }

    #[test]
    fn test_oversized_message_no_padding() {
        // A message larger than the largest bucket
        let msg_len = PADDING_BUCKETS[PADDING_BUCKETS.len() - 1] + 100;
        let msg = vec![0xFFu8; msg_len];
        let padded = MessagePadder::pad(&msg);

        // Should be msg_len + LENGTH_PREFIX_SIZE (no bucket padding)
        assert_eq!(padded.len(), msg_len + LENGTH_PREFIX_SIZE);

        let unpadded = MessagePadder::unpad(&padded).unwrap();
        assert_eq!(unpadded, msg);
    }

    #[test]
    fn test_bucket_for() {
        assert_eq!(MessagePadder::bucket_for(0), PADDING_BUCKETS[0]);
        assert_eq!(MessagePadder::bucket_for(100), PADDING_BUCKETS[0]); // 104 -> 128
        assert_eq!(MessagePadder::bucket_for(252), PADDING_BUCKETS[1]); // 256 -> 256
    }

    #[test]
    fn test_two_messages_same_bucket_same_size() {
        // Two different messages that should end up the same padded size
        let msg1 = b"short";
        let msg2 = b"also sort of short";

        let padded1 = MessagePadder::pad(msg1);
        let padded2 = MessagePadder::pad(msg2);

        // Both should be in the same bucket
        assert_eq!(padded1.len(), padded2.len());

        // But the unpadded versions should be different
        let unpad1 = MessagePadder::unpad(&padded1).unwrap();
        let unpad2 = MessagePadder::unpad(&padded2).unwrap();
        assert_ne!(unpad1, unpad2);
        assert_eq!(unpad1.as_slice(), msg1);
        assert_eq!(unpad2.as_slice(), msg2);
    }
}
