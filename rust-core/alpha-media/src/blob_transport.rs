//! # BlobPipeline — the universal transport layer for encrypted media chunks
//!
//! ```text
//!   Media (video / audio / doc / archive / photo — type-agnostic)
//!      ↓  Chunker            (unchanged)
//!      ↓  Encrypt            (unchanged: XChaCha20-Poly1305, derived nonce)
//!      ↓  BlobEnvelope       ── version + chunk/total + alg + crc ── this module
//!      ↓  BlobEnvelopeCodec  ── JSON (V1) | future: Base91 / CBOR ── this module
//!      ↓  Upload             (Blossom, parallel + per-chunk retry)
//!   ── Blossom ──
//!      ↓  Download           (parallel + per-chunk retry)
//!      ↓  BlobEnvelopeCodec  ── decode + VALIDATE ─────────────── this module
//!      ↓  Decrypt            (unchanged: AEAD tag + BLAKE3 verify)
//!      ↓  Assembler          (unchanged)
//! ```
//!
//! ## Why a transport shell exists at all
//!
//! Public Blossom servers do **body sniffing**: they ignore `Content-Type` and
//! inspect the first bytes. Measured against every server:
//!
//! | body                        | result |
//! |-----------------------------|--------|
//! | raw ciphertext              | 415    |
//! | CBOR (even as `application/cbor`) | 415 — sniffs as octet-stream |
//! | JSON (text)                 | **200** |
//!
//! So the shell must currently be **text**. That is a property of today's
//! servers, not of this design: the shell is an explicit versioned envelope and
//! the byte-rendering lives behind [`BlobEnvelopeCodec`]. Point the client at a
//! permissive (non-sniffing) server and a denser codec drops in at
//! [`active_codec`] — nothing else in the pipeline moves.
//!
//! ## Invariants
//!
//! * Ciphertext rides **verbatim**. Crypto, nonce derivation, chunking, BLAKE3,
//!   MediaRef/manifest, MLS and GiftWrap are untouched by this layer.
//! * **Nothing here branches on media type.** Video merely happens to be the
//!   first caller; audio/docs/archives/photos take the identical path.
//! * `v` travels *inside* the envelope, so an Envelope V2 can coexist with V1.
//! * Decode is **total**: every malformed input returns an error. No panics, no
//!   unbounded allocation (see [`limits`]).

use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, Ordering};

use crate::error::{MediaError, MediaResult};

/// Current envelope version. Lives inside the blob so V2 can coexist with V1.
pub const BLOB_ENVELOPE_VERSION: u8 = 1;

/// AEAD algorithm label recorded in the envelope (informational — decryption
/// still uses the pipeline's own key/nonce derivation, which is not changed).
pub const ENVELOPE_ALG: &str = "xchacha20poly1305";

/// Hard bounds enforced BEFORE any allocation or decrypt. A hostile or corrupt
/// blob can therefore never blow up memory or panic us.
pub mod limits {
    /// Plaintext stream chunk is 256 KiB; ciphertext adds an AEAD tag. Allow a
    /// generous ceiling so a future larger chunk size doesn't trip the guard,
    /// while still bounding a hostile blob.
    pub const MAX_CIPHERTEXT: usize = 8 * 1024 * 1024;
    /// Encoded envelope (base64 inflates ~33%, plus JSON scaffolding).
    pub const MAX_ENCODED_BLOB: usize = 16 * 1024 * 1024;
    /// A single media can't plausibly have more chunks than this (1 GiB / 256 KiB
    /// = 4096; leave headroom).
    pub const MAX_CHUNKS: u32 = 65_536;
    /// Explicit nonce / detached tag, when a codec carries them.
    pub const MAX_NONCE: usize = 32;
    pub const MAX_TAG: usize = 32;
}

// --- Metrics (counters only — no logging, no UI coupling) ---

/// Process-wide transport counters. Cheap atomics; read a consistent-ish
/// snapshot with [`BlobMetrics::snapshot`]. Used to find real problems later
/// (retry storms, slow codecs, oversized envelopes) without log spelunking.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct BlobMetricsSnapshot {
    pub chunks_encoded: u64,
    pub chunks_decoded: u64,
    pub decode_failures: u64,
    pub legacy_raw_blobs: u64,
    pub bytes_ciphertext: u64,
    pub bytes_encoded: u64,
    pub encode_nanos: u64,
    pub decode_nanos: u64,
}

impl BlobMetricsSnapshot {
    /// Mean transport overhead (encoded / ciphertext), e.g. 1.335 for JSON+base64.
    pub fn overhead_ratio(&self) -> f64 {
        if self.bytes_ciphertext == 0 {
            return 0.0;
        }
        self.bytes_encoded as f64 / self.bytes_ciphertext as f64
    }
    pub fn avg_encode_micros(&self) -> f64 {
        if self.chunks_encoded == 0 {
            return 0.0;
        }
        self.encode_nanos as f64 / self.chunks_encoded as f64 / 1000.0
    }
    pub fn avg_decode_micros(&self) -> f64 {
        if self.chunks_decoded == 0 {
            return 0.0;
        }
        self.decode_nanos as f64 / self.chunks_decoded as f64 / 1000.0
    }
}

pub struct BlobMetrics;

static M_ENCODED: AtomicU64 = AtomicU64::new(0);
static M_DECODED: AtomicU64 = AtomicU64::new(0);
static M_DECODE_FAIL: AtomicU64 = AtomicU64::new(0);
static M_LEGACY: AtomicU64 = AtomicU64::new(0);
static M_BYTES_CT: AtomicU64 = AtomicU64::new(0);
static M_BYTES_ENC: AtomicU64 = AtomicU64::new(0);
static M_ENC_NS: AtomicU64 = AtomicU64::new(0);
static M_DEC_NS: AtomicU64 = AtomicU64::new(0);

impl BlobMetrics {
    pub fn snapshot() -> BlobMetricsSnapshot {
        BlobMetricsSnapshot {
            chunks_encoded: M_ENCODED.load(Ordering::Relaxed),
            chunks_decoded: M_DECODED.load(Ordering::Relaxed),
            decode_failures: M_DECODE_FAIL.load(Ordering::Relaxed),
            legacy_raw_blobs: M_LEGACY.load(Ordering::Relaxed),
            bytes_ciphertext: M_BYTES_CT.load(Ordering::Relaxed),
            bytes_encoded: M_BYTES_ENC.load(Ordering::Relaxed),
            encode_nanos: M_ENC_NS.load(Ordering::Relaxed),
            decode_nanos: M_DEC_NS.load(Ordering::Relaxed),
        }
    }
    pub fn reset() {
        for c in [
            &M_ENCODED, &M_DECODED, &M_DECODE_FAIL, &M_LEGACY,
            &M_BYTES_CT, &M_BYTES_ENC, &M_ENC_NS, &M_DEC_NS,
        ] {
            c.store(0, Ordering::Relaxed);
        }
    }
}

// --- Structural checksum (NOT cryptography) ---

/// CRC-32 (IEEE) of the ciphertext, carried inside the envelope.
///
/// This is a **structural** integrity check, not a security one — the AEAD tag
/// and BLAKE3 hash already provide cryptographic integrity. Its job is to catch
/// a truncated/corrupted blob *cheaply, before* we spend time decrypting.
fn crc32(data: &[u8]) -> u32 {
    let mut crc: u32 = 0xFFFF_FFFF;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}

// --- The envelope ---

/// One encrypted chunk plus just enough self-description to decode it years from
/// now without the manifest.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlobEnvelope {
    pub v: u8,
    pub chunk: u32,
    pub total: u32,
    pub alg: String,
    /// AEAD ciphertext, byte-for-byte as the crypto layer produced it.
    pub ciphertext: Vec<u8>,
    /// Explicit nonce, if an algorithm stores one. The streaming pipeline
    /// *derives* the nonce from (key, chunk_index), so this stays empty there
    /// and is never used for decryption.
    pub nonce: Vec<u8>,
    /// Detached AEAD tag, for algorithms that split it out.
    pub tag: Vec<u8>,
    /// CRC-32 of `ciphertext` (structural check — see [`crc32`]).
    pub crc: u32,
}

impl BlobEnvelope {
    /// Wrap a ciphertext for transport. The ciphertext is not modified.
    pub fn wrap(chunk: u32, total: u32, ciphertext: Vec<u8>) -> Self {
        let crc = crc32(&ciphertext);
        Self {
            v: BLOB_ENVELOPE_VERSION,
            chunk,
            total,
            alg: ENVELOPE_ALG.to_string(),
            ciphertext,
            nonce: Vec::new(),
            tag: Vec::new(),
            crc,
        }
    }

    /// Full structural validation. Runs on every decode, BEFORE the ciphertext is
    /// handed to the crypto layer. Never panics.
    pub fn validate(&self) -> MediaResult<()> {
        let bad = |m: String| Err(MediaError::ChunkingError(format!("BlobEnvelope: {m}")));
        if self.v != BLOB_ENVELOPE_VERSION {
            return bad(format!(
                "unsupported version {} (this build understands {BLOB_ENVELOPE_VERSION})",
                self.v
            ));
        }
        if self.alg != ENVELOPE_ALG {
            return bad(format!("unknown alg {:?}", self.alg));
        }
        if self.total == 0 || self.total > limits::MAX_CHUNKS {
            return bad(format!("total {} out of range (1..={})", self.total, limits::MAX_CHUNKS));
        }
        if self.chunk >= self.total {
            return bad(format!("chunk {} >= total {}", self.chunk, self.total));
        }
        if self.ciphertext.is_empty() {
            return bad("empty ciphertext".into());
        }
        if self.ciphertext.len() > limits::MAX_CIPHERTEXT {
            return bad(format!(
                "ciphertext {} B exceeds max {} B",
                self.ciphertext.len(),
                limits::MAX_CIPHERTEXT
            ));
        }
        if self.nonce.len() > limits::MAX_NONCE {
            return bad(format!("nonce {} B too large", self.nonce.len()));
        }
        if self.tag.len() > limits::MAX_TAG {
            return bad(format!("tag {} B too large", self.tag.len()));
        }
        // Structural checksum LAST — cheapest way to catch a corrupt/truncated
        // blob before we waste an AEAD decrypt on it.
        let actual = crc32(&self.ciphertext);
        if actual != self.crc {
            return bad(format!("crc mismatch: envelope says {:08x}, got {:08x}", self.crc, actual));
        }
        Ok(())
    }
}

// --- Codec ---

/// Renders a [`BlobEnvelope`] to the exact bytes on the wire, and back.
///
/// Swapping the codec swaps the entire transport representation — and nothing
/// else. Implementations MUST round-trip the ciphertext bit-for-bit and MUST
/// validate on decode.
pub trait BlobEnvelopeCodec: Send + Sync {
    /// Serialize an envelope built from borrowed parts — avoids copying the
    /// ciphertext, which matters at 4 parallel chunks × hundreds of MB.
    fn encode_chunk(&self, chunk: u32, total: u32, ciphertext: &[u8]) -> MediaResult<Vec<u8>>;
    /// Parse blob bytes into a **validated** envelope.
    fn decode(&self, bytes: &[u8]) -> MediaResult<BlobEnvelope>;
    /// The `Content-Type` this codec's blobs are uploaded with.
    fn content_type(&self) -> &'static str;
    /// Short name for diagnostics.
    fn name(&self) -> &'static str;
}

/// **Codec V1** — JSON with base64 byte fields.
///
/// The only representation today's sniffing Blossom servers accept (raw → 415,
/// CBOR → 415, JSON → 200). Costs ~+33% size; encode/decode ~110µs/~70µs per
/// 256 KiB chunk, i.e. noise next to the network.
pub struct JsonCodec;

#[derive(Serialize, Deserialize)]
struct JsonEnvelopeV1 {
    v: u8,
    chunk: u32,
    total: u32,
    alg: String,
    ciphertext: String,
    #[serde(default)]
    nonce: String,
    #[serde(default)]
    tag: String,
    #[serde(default)]
    crc: u32,
}

fn b64_encode(data: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(data)
}

fn b64_decode(s: &str, what: &str) -> MediaResult<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD
        .decode(s)
        .map_err(|e| MediaError::ChunkingError(format!("BlobEnvelope: bad base64 in {what}: {e}")))
}

impl BlobEnvelopeCodec for JsonCodec {
    fn encode_chunk(&self, chunk: u32, total: u32, ciphertext: &[u8]) -> MediaResult<Vec<u8>> {
        if ciphertext.len() > limits::MAX_CIPHERTEXT {
            return Err(MediaError::ChunkingError(format!(
                "BlobEnvelope: ciphertext {} B exceeds max {} B",
                ciphertext.len(),
                limits::MAX_CIPHERTEXT
            )));
        }
        let t0 = std::time::Instant::now();
        let j = JsonEnvelopeV1 {
            v: BLOB_ENVELOPE_VERSION,
            chunk,
            total,
            alg: ENVELOPE_ALG.to_string(),
            ciphertext: b64_encode(ciphertext),
            nonce: String::new(),
            tag: String::new(),
            crc: crc32(ciphertext),
        };
        let out = serde_json::to_vec(&j)
            .map_err(|e| MediaError::ChunkingError(format!("BlobEnvelope encode: {e}")))?;

        M_ENCODED.fetch_add(1, Ordering::Relaxed);
        M_BYTES_CT.fetch_add(ciphertext.len() as u64, Ordering::Relaxed);
        M_BYTES_ENC.fetch_add(out.len() as u64, Ordering::Relaxed);
        M_ENC_NS.fetch_add(t0.elapsed().as_nanos() as u64, Ordering::Relaxed);
        Ok(out)
    }

    fn decode(&self, bytes: &[u8]) -> MediaResult<BlobEnvelope> {
        // Bound the input BEFORE parsing so a hostile blob can't balloon memory.
        if bytes.len() > limits::MAX_ENCODED_BLOB {
            M_DECODE_FAIL.fetch_add(1, Ordering::Relaxed);
            return Err(MediaError::ChunkingError(format!(
                "BlobEnvelope: encoded blob {} B exceeds max {} B",
                bytes.len(),
                limits::MAX_ENCODED_BLOB
            )));
        }
        let t0 = std::time::Instant::now();
        let j: JsonEnvelopeV1 = serde_json::from_slice(bytes).map_err(|e| {
            M_DECODE_FAIL.fetch_add(1, Ordering::Relaxed);
            MediaError::ChunkingError(format!("BlobEnvelope decode: {e}"))
        })?;
        let env = BlobEnvelope {
            v: j.v,
            chunk: j.chunk,
            total: j.total,
            alg: j.alg,
            ciphertext: b64_decode(&j.ciphertext, "ciphertext")?,
            nonce: b64_decode(&j.nonce, "nonce")?,
            tag: b64_decode(&j.tag, "tag")?,
            crc: j.crc,
        };
        env.validate().inspect_err(|_| {
            M_DECODE_FAIL.fetch_add(1, Ordering::Relaxed);
        })?;

        M_DECODED.fetch_add(1, Ordering::Relaxed);
        M_DEC_NS.fetch_add(t0.elapsed().as_nanos() as u64, Ordering::Relaxed);
        Ok(env)
    }

    fn content_type(&self) -> &'static str {
        "application/json"
    }

    fn name(&self) -> &'static str {
        "json-v1"
    }
}

/// THE swap point. When a permissive (non-sniffing) blob server is configured, a
/// denser codec is returned here and the whole pipeline follows — no other file
/// changes.
pub fn active_codec() -> &'static dyn BlobEnvelopeCodec {
    &JsonCodec
}

// --- Wire v2: featureless, fixed-size chunks ---
//
// v1 exposed `chunk`, `total`, `alg` and the exact ciphertext length in clear
// JSON: a server learned file sizes (and so photo vs video) and could recognise
// the app by the field set. A v2 chunk is `0x02 || masked_len(4) || ciphertext ||
// zero padding` to ONE fixed size: to a server, the same count of random bytes
// for every chunk of every file. The length is masked with a keystream derived
// from the media key and the chunk index, so only holders of the key can strip
// the padding; reordering is still caught by the per-index AEAD nonce. Encoding
// is deterministic (stable content ids for dedup and resume).
//
// It is stored as raw bytes. Chunks stored before 2026-11-20 carry the same body in
// a `{"d":"<base64>"}` shell (a third larger), and still read.

const WIRE_V2: u8 = 2;
const WIRE_V2_HEADER: usize = 1 + 4;
/// Every v2 chunk body has this size: a full stream chunk plus its AEAD tag.
const WIRE_V2_BODY: usize = WIRE_V2_HEADER + crate::crypto::STREAM_CHUNK_SIZE + 16;

fn length_mask(media_key: &[u8; 32], index: u32) -> [u8; 4] {
    let mut input = b"alpha-wire-len-v2:".to_vec();
    input.extend_from_slice(&index.to_le_bytes());
    let h = blake3::keyed_hash(media_key, &input);
    let mut m = [0u8; 4];
    m.copy_from_slice(&h.as_bytes()[..4]);
    m
}

/// How a chunk is declared to storage: opaque bytes, whatever the file inside.
pub const CHUNK_CONTENT_TYPE: &str = "application/octet-stream";

/// Wrap one chunk ciphertext for storage (wire v2).
pub fn wrap_chunk(media_key: &[u8; 32], index: u32, ciphertext: &[u8]) -> MediaResult<Vec<u8>> {
    if ciphertext.len() > limits::MAX_CIPHERTEXT {
        return Err(MediaError::ChunkingError(format!(
            "wire v2: ciphertext {} B exceeds max {} B", ciphertext.len(), limits::MAX_CIPHERTEXT
        )));
    }
    let needed = WIRE_V2_HEADER + ciphertext.len();
    // Oversized chunks (not produced by the stream path) round up to 64 KiB.
    let size = if needed <= WIRE_V2_BODY { WIRE_V2_BODY } else { needed.div_ceil(65536) * 65536 };
    let mut body = Vec::with_capacity(size);
    body.push(WIRE_V2);
    let mask = length_mask(media_key, index);
    let len = (ciphertext.len() as u32).to_be_bytes();
    body.extend((0..4).map(|i| len[i] ^ mask[i]));
    body.extend_from_slice(ciphertext);
    body.resize(size, 0);
    M_ENCODED.fetch_add(1, Ordering::Relaxed);
    Ok(body)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireV2 {
    d: String,
}

/// A raw v2 body is told apart by its first byte and one of the sizes v2 pads to:
/// a JSON shell starts with `{`, and a legacy raw ciphertext is never padded.
fn is_v2_body(blob: &[u8]) -> bool {
    blob.first() == Some(&WIRE_V2)
        && (blob.len() == WIRE_V2_BODY || (blob.len() > WIRE_V2_BODY && blob.len() % 65536 == 0))
}

fn try_unwrap_v2(blob: &[u8], media_key: &[u8; 32], index: u32) -> Option<Vec<u8>> {
    if blob.len() > limits::MAX_ENCODED_BLOB {
        return None;
    }
    if is_v2_body(blob) {
        return open_v2(blob, media_key, index);
    }
    let w: WireV2 = serde_json::from_slice(blob).ok()?;
    open_v2(&b64_decode(&w.d, "d").ok()?, media_key, index)
}

fn open_v2(body: &[u8], media_key: &[u8; 32], index: u32) -> Option<Vec<u8>> {
    if body.len() < WIRE_V2_HEADER || body[0] != WIRE_V2 {
        return None;
    }
    let mask = length_mask(media_key, index);
    let mut len = [0u8; 4];
    for i in 0..4 {
        len[i] = body[1 + i] ^ mask[i];
    }
    let len = u32::from_be_bytes(len) as usize;
    let end = WIRE_V2_HEADER.checked_add(len)?;
    if len == 0 || end > body.len() {
        return None;
    }
    M_DECODED.fetch_add(1, Ordering::Relaxed);
    Some(body[WIRE_V2_HEADER..end].to_vec())
}

/// Unwrap a downloaded chunk: wire v2 (raw or in its JSON shell), then v1 / legacy raw blobs.
pub fn unwrap_chunk(blob: Vec<u8>, media_key: &[u8; 32], index: u32) -> Vec<u8> {
    match try_unwrap_v2(&blob, media_key, index) {
        Some(ct) => ct,
        None => unwrap_blob(blob),
    }
}

/// Unwrap a downloaded blob back to raw ciphertext.
///
/// Backwards compatible: blobs uploaded before this layer existed are raw
/// ciphertext with no envelope. Anything that doesn't parse+validate as an
/// envelope is treated as a legacy raw blob, so old media keeps playing.
pub fn unwrap_blob(bytes: Vec<u8>) -> Vec<u8> {
    match active_codec().decode(&bytes) {
        Ok(env) => env.ciphertext,
        Err(_) => {
            M_LEGACY.fetch_add(1, Ordering::Relaxed);
            bytes
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ct(n: usize) -> Vec<u8> {
        (0..n).map(|i| (i % 251) as u8).collect()
    }

    fn json_shell(body: &[u8]) -> Vec<u8> {
        format!("{{\"d\":\"{}\"}}", b64_encode(body)).into_bytes()
    }

    #[test]
    fn wire_v2_roundtrip_fixed_size_and_featureless() {
        let key = [7u8; 32];
        let small = wrap_chunk(&key, 0, &ct(100)).unwrap();
        let full = wrap_chunk(&key, 5, &ct(crate::crypto::STREAM_CHUNK_SIZE + 16)).unwrap();
        assert_eq!(small.len(), full.len(), "all chunks must look the same size");
        assert_eq!(small.len(), WIRE_V2_BODY, "raw bytes, no base64 shell");
        // Past the version byte, only the masked length and the ciphertext: no field names, no size.
        assert!(!small.windows(5).any(|w| w == b"chunk" || w == b"total"));
        assert_eq!(unwrap_chunk(small.clone(), &key, 0), ct(100));
        assert_eq!(unwrap_chunk(full, &key, 5), ct(crate::crypto::STREAM_CHUNK_SIZE + 16));
        assert_eq!(wrap_chunk(&key, 0, &ct(100)).unwrap(), small, "deterministic");
        // Wrong key / index can't recover the length → not this chunk's data.
        assert_ne!(unwrap_chunk(small.clone(), &[8u8; 32], 0), ct(100));
    }

    #[test]
    fn chunks_stored_in_the_json_shell_still_read() {
        let key = [3u8; 32];
        let body = wrap_chunk(&key, 4, &ct(5000)).unwrap();
        assert_eq!(unwrap_chunk(json_shell(&body), &key, 4), ct(5000));
    }

    #[test]
    fn legacy_raw_ciphertext_is_never_taken_for_a_v2_body() {
        let key = [3u8; 32];
        let mut legacy = ct(crate::crypto::STREAM_CHUNK_SIZE + 16);
        legacy[0] = WIRE_V2;
        assert_eq!(unwrap_chunk(legacy.clone(), &key, 0), legacy);
    }

    #[test]
    fn wire_v1_still_decodes() {
        let key = [1u8; 32];
        let v1 = active_codec().encode_chunk(2, 5, &ct(300)).unwrap();
        assert_eq!(unwrap_chunk(v1, &key, 2), ct(300));
    }

    #[test]
    fn roundtrips_ciphertext_verbatim_and_is_text() {
        let c = ct(5000);
        let blob = active_codec().encode_chunk(7, 42, &c).unwrap();
        // MUST be text — sniffing servers reject binary.
        assert_eq!(blob[0], b'{');
        let back = active_codec().decode(&blob).unwrap();
        assert_eq!(back.ciphertext, c);
        assert_eq!(back.chunk, 7);
        assert_eq!(back.total, 42);
        assert_eq!(back.v, BLOB_ENVELOPE_VERSION);
    }

    #[test]
    fn legacy_raw_blob_passes_through() {
        let raw = vec![0xDEu8, 0xAD, 0xBE, 0xEF];
        assert_eq!(unwrap_blob(raw.clone()), raw);
    }

    #[test]
    fn rejects_corrupt_ciphertext_via_crc() {
        let blob = active_codec().encode_chunk(0, 1, &ct(1000)).unwrap();
        let mut env = active_codec().decode(&blob).unwrap();
        env.ciphertext[0] ^= 0xFF; // flip a bit → crc must catch it
        assert!(env.validate().is_err());
    }

    #[test]
    fn rejects_chunk_index_out_of_range() {
        let mut env = BlobEnvelope::wrap(5, 5, ct(10)); // chunk == total → invalid
        assert!(env.validate().is_err());
        env.chunk = 4;
        assert!(env.validate().is_ok());
    }

    #[test]
    fn rejects_bad_version_and_alg() {
        let mut env = BlobEnvelope::wrap(0, 1, ct(10));
        env.v = 99;
        assert!(env.validate().is_err());
        let mut env = BlobEnvelope::wrap(0, 1, ct(10));
        env.alg = "rot13".into();
        assert!(env.validate().is_err());
    }

    #[test]
    fn rejects_oversized_and_empty() {
        let mut env = BlobEnvelope::wrap(0, 1, ct(10));
        env.ciphertext = Vec::new();
        assert!(env.validate().is_err());
        assert!(active_codec()
            .decode(&vec![b'x'; limits::MAX_ENCODED_BLOB + 1])
            .is_err());
    }

    #[test]
    fn garbage_never_panics() {
        for bad in [
            &b""[..], &b"{"[..], &b"null"[..], &b"{\"v\":1}"[..],
            &b"{\"v\":1,\"chunk\":0,\"total\":1,\"alg\":\"x\",\"ciphertext\":\"!!!\"}"[..],
        ] {
            let _ = active_codec().decode(bad); // must return Err, not panic
        }
    }

    #[test]
    fn metrics_track_encode_decode() {
        // Counters are process-global and the test harness runs tests in
        // parallel, so assert on DELTAS rather than absolute values.
        let before = BlobMetrics::snapshot();
        let c = ct(2048);
        let blob = active_codec().encode_chunk(0, 1, &c).unwrap();
        let _ = active_codec().decode(&blob).unwrap();
        let after = BlobMetrics::snapshot();

        assert!(after.chunks_encoded >= before.chunks_encoded + 1);
        assert!(after.chunks_decoded >= before.chunks_decoded + 1);
        // Transport overhead for THIS chunk: base64 + JSON scaffolding ≈ +33%.
        let ratio = blob.len() as f64 / c.len() as f64;
        assert!(ratio > 1.3 && ratio < 1.45, "overhead ratio was {ratio}");
    }

    //  Transport V1 — canonicalization of the transport object.
    //
    //  An object's storage identity is the SHA-256 of EXACTLY the bytes
    //  that went into the PUT — the encoded envelope. So replica lookup by
    //  content-id depends on serialization stability: any drift — field
    //  order, whitespace, base64 variant, number rendering — yields a
    //  DIFFERENT identifier for the same chunk, and replica discovery
    //  silently stops working.
    //
    //  These tests pin what the transport relies on. They change nothing in
    //  the send path — they only forbid it from changing unnoticed.

    fn sha256_hex(data: &[u8]) -> String {
        use sha2::{Digest, Sha256};
        let mut h = Sha256::new();
        h.update(data);
        hex::encode(h.finalize())
    }

    /// The same chunk MUST yield a BYTE-FOR-BYTE identical envelope.
    #[test]
    fn t1_encoding_is_byte_stable_across_calls() {
        let c = ct(64 * 1024);
        let a = active_codec().encode_chunk(17, 600, &c).unwrap();
        let b = active_codec().encode_chunk(17, 600, &c).unwrap();
        assert_eq!(a, b, "повторная сериализация дала другие байты");
        assert_eq!(sha256_hex(&a), sha256_hex(&b), "content-id нестабилен");
    }

    /// The content-id MUST depend on the chunk's CONTEXT, not just the ciphertext.
    ///
    /// That is what makes chunk reordering detectable at the storage level:
    /// objects 17 and 18 with identical content are DIFFERENT objects.
    #[test]
    fn t1_content_id_binds_chunk_index_and_total() {
        let c = ct(4096);
        let at17 = sha256_hex(&active_codec().encode_chunk(17, 600, &c).unwrap());
        let at18 = sha256_hex(&active_codec().encode_chunk(18, 600, &c).unwrap());
        let other_total = sha256_hex(&active_codec().encode_chunk(17, 601, &c).unwrap());
        assert_ne!(at17, at18, "индекс чанка не входит в идентичность объекта");
        assert_ne!(at17, other_total, "общее число чанков не входит в идентичность");
    }

    /// Different content — a different object. A degenerate-codec check.
    #[test]
    fn t1_content_id_changes_with_ciphertext() {
        let a = sha256_hex(&active_codec().encode_chunk(0, 1, &ct(4096)).unwrap());
        let b = sha256_hex(&active_codec().encode_chunk(0, 1, &ct(4097)).unwrap());
        assert_ne!(a, b);
    }

    /// Codec V1 stays TEXTUAL and keeps its version.
    ///
    /// Both checks are load-bearing: text, because servers rejected binary
    /// (415); the version, because a representation change must bump it,
    /// not pass silently.
    #[test]
    fn t1_codec_identity_is_pinned() {
        let blob = active_codec().encode_chunk(3, 9, &ct(1024)).unwrap();
        assert_eq!(blob[0], b'{', "конверт перестал быть текстовым");
        assert_eq!(active_codec().content_type(), "application/json");
        assert_eq!(BLOB_ENVELOPE_VERSION, 1);
        let env = active_codec().decode(&blob).unwrap();
        assert_eq!(env.v, 1);
    }

    /// Fixture: a pinned envelope sample that must keep decoding.
    ///
    /// The bytes are written from the current codec. If it changes without a
    /// version bump, this test fails — as intended: old messages must stay readable.
    #[test]
    fn t1_fixture_envelope_stays_decodable() {
        let ciphertext = vec![1u8, 2, 3, 4, 5, 6, 7, 8];
        let blob = active_codec().encode_chunk(2, 5, &ciphertext).unwrap();
        let text = String::from_utf8(blob.clone()).expect("конверт обязан быть UTF-8");
        // The field set is part of the contract: the object is self-describing.
        for field in ["\"v\"", "\"chunk\"", "\"total\"", "\"alg\"", "\"crc\""] {
            assert!(text.contains(field), "в конверте нет поля {field}: {text}");
        }
        let back = active_codec().decode(&blob).unwrap();
        assert_eq!(back.ciphertext, ciphertext);
        assert_eq!(back.chunk, 2);
        assert_eq!(back.total, 5);
    }

    /// Transport overhead — MEASURED, not arithmetic.
    ///
    /// Prints the actual ratio at a near-production chunk size (256 KiB) —
    /// one of the load-bearing numbers of the transport.
    #[test]
    fn t1_measure_transport_overhead() {
        let plaintext_like = ct(256 * 1024);
        let blob = active_codec().encode_chunk(0, 600, &plaintext_like).unwrap();
        let ratio = blob.len() as f64 / plaintext_like.len() as f64;
        println!(
            "T1 overhead: payload={} B, envelope={} B, ratio={:.4}",
            plaintext_like.len(), blob.len(), ratio,
        );
        assert!(ratio > 1.30 && ratio < 1.40, "коэффициент вышел за ожидание: {ratio}");
    }
}

//  TRANSPORT V1 — GOLDEN FIXTURE of the wire format.
//
//  The bytes are FROZEN as a constant, not recomputed by the current encoder.
//  The difference is fundamental: deriving the expectation from the code
//  under test means encoder and test break IDENTICALLY and silently — a
//  representation change would slip through, taking the storage identity with it.
//
//  If this test fails, the wire format changed. That is allowed only with a
//  BLOB_ENVELOPE_VERSION bump and a compatibility plan, never as a side effect.
#[cfg(test)]
mod t1_golden {
    use super::*;

    /// An envelope for chunk 2 of 5 with ciphertext `00 01 02 … 17`.
    const GOLDEN_ENVELOPE: &str = concat!(
        r#"{"v":1,"chunk":2,"total":5,"alg":"xchacha20poly1305","#,
        r#""ciphertext":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYX","#,
        r#""nonce":"","tag":"","crc":2190845590}"#,
    );

    /// `transportObjectId` = SHA-256 of exactly the bytes that go into the PUT.
    const GOLDEN_SHA256: &str =
        "81afbd5c3e07233703bf9101f8793080e997a7103e9618d1fe58e0be052c393f";

    fn golden_ciphertext() -> Vec<u8> {
        (0..24u8).collect()
    }

    /// The encoder MUST produce the BYTE-FOR-BYTE frozen envelope.
    #[test]
    fn t1_wire_format_matches_frozen_golden() {
        let produced = active_codec()
            .encode_chunk(2, 5, &golden_ciphertext())
            .expect("encode");
        assert_eq!(
            String::from_utf8(produced).expect("UTF-8"),
            GOLDEN_ENVELOPE,
            "проводной формат изменился без подъёма версии конверта",
        );
    }

    /// The storage object identity is frozen too.
    #[test]
    fn t1_transport_object_id_matches_frozen_golden() {
        use sha2::{Digest, Sha256};
        let produced = active_codec()
            .encode_chunk(2, 5, &golden_ciphertext())
            .expect("encode");
        let mut h = Sha256::new();
        h.update(&produced);
        assert_eq!(hex::encode(h.finalize()), GOLDEN_SHA256, "content-id уехал");
    }

    /// The frozen bytes MUST be readable by the current decoder.
    ///
    /// That is the "old → current decoder" compatibility check: the constant
    /// was written once and has not changed since.
    #[test]
    fn t1_frozen_golden_still_decodes() {
        let env = active_codec()
            .decode(GOLDEN_ENVELOPE.as_bytes())
            .expect("замороженный конверт перестал декодироваться");
        assert_eq!(env.v, 1);
        assert_eq!(env.chunk, 2);
        assert_eq!(env.total, 5);
        assert_eq!(env.alg, ENVELOPE_ALG);
        assert_eq!(env.ciphertext, golden_ciphertext());
    }
}
