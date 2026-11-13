use serde::{Deserialize, Serialize};

use crate::error::{MediaError, MediaResult};
use crate::thumbnail::MediaType;

/// Result of a successful media upload through the pipeline.
#[derive(Debug, Clone)]
pub struct UploadResult {
    /// XChaCha20 encryption key — must be sent to recipient via MLS message.
    pub media_key: [u8; 32],
    /// BLAKE3 hash of the original plaintext.
    pub media_hash: [u8; 32],
    /// URLs for each uploaded chunk on the Blossom relay.
    pub blob_urls: Vec<String>,
    /// Original file size in bytes.
    pub original_size: u64,
    /// Thumbnail encryption key (if a thumbnail was generated).
    pub thumbnail_key: Option<[u8; 32]>,
    /// Thumbnail BLAKE3 hash (if a thumbnail was generated).
    pub thumbnail_hash: Option<[u8; 32]>,
    /// Thumbnail chunk URLs (if a thumbnail was generated).
    pub thumbnail_urls: Option<Vec<String>>,
    /// Thumbnail size in bytes (if a thumbnail was generated).
    pub thumbnail_size: Option<u64>,
    /// Type of the media file.
    pub media_type: MediaType,
    /// Original filename.
    pub filename: String,
    /// Streaming upload: per-chunk info (URL, ciphertext size, plaintext size, hash).
    /// Present when `streaming == true`.
    pub stream_chunks: Option<Vec<StreamChunkUploadInfo>>,
}

/// Transport object identity — the SHA-256 of EXACTLY the bytes that went
/// to storage (the encoded [`crate::blob_transport::BlobEnvelope`]).
///
/// A separate type, not a `String`, for one reason: an address says WHERE
/// an object lay at send time, identity says WHAT the object is. The former
/// rots with the server, the latter never does. Replica discovery rests on
/// it: a copy can be found on any known storage.
///
/// Invariant: a value cannot be built bypassing [`TransportObjectId::parse`]
/// — the field is private. So no unvalidated identity exists inside the
/// transport; validation lives at the parse boundary and only there.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct TransportObjectId(String);

impl TransportObjectId {
    /// Parse the wire representation.
    ///
    /// Case is ignored deliberately: SHA-256 itself does not change with
    /// letter case, and old addresses may arrive upper-cased. The canonical
    /// lower-case always goes out — so one object's identity never splits in two.
    pub fn parse(s: &str) -> MediaResult<Self> {
        let t = s.trim();
        if t.len() != 64 || !t.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(MediaError::SerializationError(format!(
                "transportObjectId: ожидались 64 шестнадцатеричных символа, получено {} симв.",
                t.len()
            )));
        }
        Ok(Self(t.to_ascii_lowercase()))
    }

    /// Identity of the already-uploaded body. The single way it can appear.
    pub fn of_bytes(body: &[u8]) -> Self {
        use sha2::Digest;
        Self(hex::encode(sha2::Sha256::digest(body)))
    }

    pub fn as_hex(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for TransportObjectId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl Serialize for TransportObjectId {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for TransportObjectId {
    /// A broken explicit identity is an ERROR, not a reason to silently fall
    /// back to parsing the address. A silent fallback would hide corruption
    /// of the new ref under the guise of old-compat.
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let raw = String::deserialize(d)?;
        Self::parse(&raw).map_err(serde::de::Error::custom)
    }
}

/// ONE place a chunk's encrypted blob is stored. A chunk is REPLICATED across
/// several Blossom servers, so a dead/slow mirror never blocks the download — the
/// pipeline just moves to the next location. A struct (not a bare URL string) so
/// future needs — region, CDN, priority, user's home relay, trusted/untrusted —
/// slot in without breaking the wire format again.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChunkLocation {
    /// The Blossom server base that holds this replica (diagnostics / future priority).
    pub relay: String,
    /// The full blob URL on that server.
    pub url: String,
}

/// Per-chunk metadata for a streaming upload.
///
/// Each stream chunk is independently encrypted and replicated to several Blossom
/// servers. This struct carries the info needed to download + decrypt one chunk.
#[derive(Debug, Clone)]
pub struct StreamChunkUploadInfo {
    /// Every mirror that holds this chunk (≥1). Download tries them in order.
    pub locations: Vec<ChunkLocation>,
    /// Ciphertext size (encrypted chunk size on the relay).
    pub ciphertext_size: u64,
    /// Original plaintext size of this chunk.
    pub original_size: u64,
    /// BLAKE3 hash of the plaintext (hex-encoded for JSON).
    pub hash_hex: String,
    /// Transport object identity: SHA-256 of the uploaded envelope. `None`
    /// only on paths where it is not computed yet (legacy uploads).
    pub transport_object_id: Option<TransportObjectId>,
}

/// Serializable per-chunk metadata embedded in `MediaRef`.
///
/// This is what gets transported inside the MLS message so the recipient
/// can download and decrypt individual chunks for streaming playback.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StreamChunkInfo {
    /// Every mirror that holds this chunk (≥1). Download falls over between them.
    pub locations: Vec<ChunkLocation>,
    /// Ciphertext size on the relay (for range-request calculations).
    pub ciphertext_size: u64,
    /// Original plaintext size of this chunk.
    pub original_size: u64,
    /// BLAKE3 hash of the plaintext (hex-encoded).
    pub hash: String,
    /// Transport object identity — a replica is looked up by it on any known
    /// storage. Absent on messages sent before the field existed: their
    /// identity is recovered from the address (LEGACY_IDENTITY_RECOVERY).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transport_object_id: Option<TransportObjectId>,
}

/// Media reference format for inclusion in MLS messages.
///
/// This is the serializable representation that gets embedded in
/// an MLS group message so recipients can locate and decrypt the media.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MediaRef {
    /// Base64-encoded XChaCha20 key.
    pub key: String,
    /// Hex-encoded BLAKE3 hash of the plaintext.
    pub hash: String,
    /// URLs for each chunk on the Blossom relay (legacy whole-file encryption).
    pub blob_urls: Vec<String>,
    /// Original file size in bytes.
    pub original_size: u64,
    /// Media type string: "image", "video", "audio", "file".
    pub media_type: String,
    /// Original filename.
    pub filename: String,
    /// Base64-encoded thumbnail XChaCha20 key (if thumbnail exists).
    pub thumbnail_key: Option<String>,
    /// Hex-encoded thumbnail BLAKE3 hash (if thumbnail exists).
    pub thumbnail_hash: Option<String>,
    /// Thumbnail chunk URLs (if thumbnail exists).
    pub thumbnail_urls: Option<Vec<String>>,
    /// Thumbnail size in bytes (if thumbnail exists).
    pub thumbnail_size: Option<u64>,
    /// `true` when the media was uploaded with per-chunk streaming E2EE.
    /// When `true`, `chunks` contains per-chunk metadata and `blob_urls` is
    /// kept for backward compatibility but should be ignored.
    /// When absent or `false`, legacy whole-file encryption is used.
    #[serde(default)]
    pub streaming: bool,
    /// Per-chunk metadata for streaming E2EE (only present when `streaming == true`).
    /// Each entry describes one independently-encrypted chunk that can be
    /// downloaded, decrypted, and verified on its own.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub chunks: Option<Vec<StreamChunkInfo>>,
    /// Media Manifest (protocol v2). When set, `chunks` is NOT inline in the
    /// message — the full chunk list lives in an encrypted "manifest" blob at
    /// `manifest_url` (itself a serialized `MediaRef` with `chunks` populated),
    /// keeping the Nostr event tiny regardless of file size. The receiver fetches
    /// it, verifies `manifest_hash`, decrypts with `key`, and gets the full ref.
    /// Absent → the ref is self-contained (inline chunks / legacy) as before.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub manifest_url: Option<String>,
    /// BLAKE3 hash (hex) of the encrypted manifest blob — integrity check.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub manifest_hash: Option<String>,
    /// Media-ref FORMAT version — the EXPLICIT scheme marker (not `manifest_url`
    /// presence). 1 (or absent) = self-contained inline/legacy ref; 2 = slim ref
    /// whose chunk list lives in an external encrypted manifest blob. Lets future
    /// schemes (compressed / signed manifest …) be told apart unambiguously.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub format_version: Option<u8>,
    /// Video poster frame as base64 JPEG (optional, set by sender for instant preview).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub poster_b64: Option<String>,
    /// Local file path on the sender's device (not sent to peers).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local_path: Option<String>,
    /// `true` when the media is still being uploaded (optimistic send).
    #[serde(default)]
    pub pending: bool,
    /// Its blobs travel only through Tor (a secret chat's file): every copy of
    /// this ref is fetched over the Tor-only channel.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub tor: bool,
    /// Display metadata only the sender can measure (duration, size, voice
    /// waveform). Kept as-is so it survives every parse, slim ref and forward.
    #[serde(flatten, default)]
    pub display: serde_json::Map<String, serde_json::Value>,
}

impl MediaRef {
    /// Create a `MediaRef` from an `UploadResult`.
    pub fn from_upload_result(result: &UploadResult) -> Self {
        let stream_chunk_infos = result.stream_chunks.as_ref().map(|chunks| {
            chunks
                .iter()
                .enumerate()
                .map(|(_, c)| StreamChunkInfo {
                    locations: c.locations.clone(),
                    ciphertext_size: c.ciphertext_size,
                    original_size: c.original_size,
                    hash: c.hash_hex.clone(),
                    transport_object_id: c.transport_object_id.clone(),
                })
                .collect()
        });
        let is_streaming = result.stream_chunks.is_some();

        Self {
            key: base64::Engine::encode(
                &base64::engine::general_purpose::STANDARD,
                result.media_key,
            ),
            hash: hex::encode(result.media_hash),
            blob_urls: result.blob_urls.clone(),
            original_size: result.original_size,
            media_type: result.media_type.to_string(),
            filename: result.filename.clone(),
            thumbnail_key: result.thumbnail_key.map(|k| {
                base64::Engine::encode(&base64::engine::general_purpose::STANDARD, k)
            }),
            thumbnail_hash: result.thumbnail_hash.map(hex::encode),
            thumbnail_urls: result.thumbnail_urls.clone(),
            thumbnail_size: result.thumbnail_size,
            streaming: is_streaming,
            chunks: stream_chunk_infos,
            poster_b64: None,
            local_path: None,
            manifest_url: None,
            manifest_hash: None,
            format_version: None,
            pending: false,
            tor: false,
            display: Default::default(),
        }
    }

    /// PROTOCOL v2 — true when this is a SLIM ref whose chunk list lives in an
    /// EXTERNAL manifest blob (must be resolved before download). Explicit: keyed
    /// on `format_version == 2` AND a `manifest_url`, never on the URL alone.
    pub fn is_external_manifest(&self) -> bool {
        self.format_version == Some(2) && self.manifest_url.is_some()
    }

    /// Centralized structural validation — the ONE place send / download / backup
    /// / sync / restore check a ref's shape. Verifies the key and the
    /// inline-vs-manifest invariants.
    pub fn validate(&self) -> MediaResult<()> {
        let key = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &self.key)
            .map_err(|e| MediaError::PipelineError(format!("media_ref: bad key base64: {e}")))?;
        if key.len() != 32 {
            return Err(MediaError::PipelineError("media_ref: key not 32 bytes".into()));
        }
        if self.is_external_manifest() {
            if self.manifest_hash.is_none() {
                return Err(MediaError::PipelineError("media_ref: external manifest without manifest_hash".into()));
            }
        } else if self.streaming {
            if self.chunks.as_ref().map_or(true, |c| c.is_empty()) {
                return Err(MediaError::PipelineError("media_ref: streaming ref without inline chunks".into()));
            }
        } else if self.blob_urls.is_empty() {
            return Err(MediaError::PipelineError("media_ref: non-streaming ref without blob_urls".into()));
        }
        Ok(())
    }

    /// Convert back to an `UploadResult` for use in the download pipeline.
    ///
    /// Decodes the base64 key and hex hash back to their raw byte forms.
    pub fn to_upload_result(&self) -> MediaResult<UploadResult> {
        let media_key_bytes = base64::Engine::decode(
            &base64::engine::general_purpose::STANDARD,
            &self.key,
        )
        .map_err(|e| MediaError::SerializationError(format!("Invalid base64 key: {e}")))?;

        if media_key_bytes.len() != 32 {
            return Err(MediaError::InvalidKeySize {
                expected: 32,
                actual: media_key_bytes.len(),
            });
        }

        let mut media_key = [0u8; 32];
        media_key.copy_from_slice(&media_key_bytes);

        let media_hash_bytes = hex::decode(&self.hash)
            .map_err(|e| MediaError::SerializationError(format!("Invalid hex hash: {e}")))?;

        if media_hash_bytes.len() != 32 {
            return Err(MediaError::SerializationError(format!(
                "Invalid hash length: expected 32, got {}",
                media_hash_bytes.len()
            )));
        }

        let mut media_hash = [0u8; 32];
        media_hash.copy_from_slice(&media_hash_bytes);

        let media_type = match self.media_type.as_str() {
            "image" => MediaType::Image,
            "video" => MediaType::Video,
            "audio" => MediaType::Audio,
            "file" => MediaType::File,
            other => {
                return Err(MediaError::InvalidMediaType(other.to_string()));
            }
        };

        // Parse thumbnail key if present
        let thumbnail_key = self
            .thumbnail_key
            .as_ref()
            .map(|k| {
                let bytes = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, k)
                    .map_err(|e| {
                        MediaError::SerializationError(format!("Invalid thumbnail key base64: {e}"))
                    })?;
                if bytes.len() != 32 {
                    return Err(MediaError::InvalidKeySize {
                        expected: 32,
                        actual: bytes.len(),
                    });
                }
                let mut key = [0u8; 32];
                key.copy_from_slice(&bytes);
                Ok(key)
            })
            .transpose()?;

        // Parse thumbnail hash if present
        let thumbnail_hash = self
            .thumbnail_hash
            .as_ref()
            .map(|h| {
                let bytes = hex::decode(h).map_err(|e| {
                    MediaError::SerializationError(format!("Invalid thumbnail hash hex: {e}"))
                })?;
                if bytes.len() != 32 {
                    return Err(MediaError::SerializationError(format!(
                        "Invalid thumbnail hash length: expected 32, got {}",
                        bytes.len()
                    )));
                }
                let mut hash = [0u8; 32];
                hash.copy_from_slice(&bytes);
                Ok(hash)
            })
            .transpose()?;

        Ok(UploadResult {
            media_key,
            media_hash,
            blob_urls: self.blob_urls.clone(),
            original_size: self.original_size,
            thumbnail_key,
            thumbnail_hash,
            thumbnail_urls: self.thumbnail_urls.clone(),
            thumbnail_size: self.thumbnail_size,
            media_type,
            filename: self.filename.clone(),
            stream_chunks: self.chunks.as_ref().map(|chunks| {
                chunks
                    .iter()
                    .map(|c| StreamChunkUploadInfo {
                        locations: c.locations.clone(),
                        ciphertext_size: c.ciphertext_size,
                        original_size: c.original_size,
                        hash_hex: c.hash.clone(),
                        transport_object_id: c.transport_object_id.clone(),
                    })
                    .collect()
            }),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_test_upload_result() -> UploadResult {
        UploadResult {
            media_key: [0x42; 32],
            media_hash: [0xAB; 32],
            blob_urls: vec!["https://relay.example.com/blob1".into()],
            original_size: 1024,
            media_type: MediaType::Image,
            filename: "test.png".into(),
            thumbnail_key: Some([0x11; 32]),
            thumbnail_hash: Some([0x22; 32]),
            thumbnail_urls: Some(vec!["https://relay.example.com/thumb1".into()]),
            thumbnail_size: Some(128),
            stream_chunks: None,
        }
    }

    #[test]
    fn test_media_ref_roundtrip() {
        let upload_result = make_test_upload_result();
        let media_ref = MediaRef::from_upload_result(&upload_result);

        assert_eq!(media_ref.media_type, "image");
        assert_eq!(media_ref.filename, "test.png");
        assert_eq!(media_ref.original_size, 1024);
        assert!(media_ref.thumbnail_key.is_some());
        assert!(media_ref.thumbnail_hash.is_some());

        let restored = media_ref.to_upload_result().expect("roundtrip should succeed");
        assert_eq!(restored.media_key, upload_result.media_key);
        assert_eq!(restored.media_hash, upload_result.media_hash);
        assert_eq!(restored.blob_urls, upload_result.blob_urls);
        assert_eq!(restored.original_size, upload_result.original_size);
        assert_eq!(restored.thumbnail_key, upload_result.thumbnail_key);
        assert_eq!(restored.thumbnail_hash, upload_result.thumbnail_hash);
        assert_eq!(restored.thumbnail_urls, upload_result.thumbnail_urls);
        assert_eq!(restored.thumbnail_size, upload_result.thumbnail_size);
        assert_eq!(restored.media_type, upload_result.media_type);
        assert_eq!(restored.filename, upload_result.filename);
    }

    #[test]
    fn test_media_ref_without_thumbnail() {
        let upload_result = UploadResult {
            media_key: [0x42; 32],
            media_hash: [0xAB; 32],
            blob_urls: vec!["https://relay.example.com/blob1".into()],
            original_size: 2048,
            media_type: MediaType::File,
            filename: "doc.pdf".into(),
            thumbnail_key: None,
            thumbnail_hash: None,
            thumbnail_urls: None,
            thumbnail_size: None,
            stream_chunks: None,
        };

        let media_ref = MediaRef::from_upload_result(&upload_result);
        assert!(media_ref.thumbnail_key.is_none());
        assert!(media_ref.thumbnail_hash.is_none());
        assert!(media_ref.thumbnail_urls.is_none());
        assert!(media_ref.thumbnail_size.is_none());

        let restored = media_ref.to_upload_result().expect("roundtrip should succeed");
        assert!(restored.thumbnail_key.is_none());
        assert!(restored.thumbnail_hash.is_none());
    }

    #[test]
    fn test_media_ref_serialization() {
        let upload_result = make_test_upload_result();
        let media_ref = MediaRef::from_upload_result(&upload_result);

        let json = serde_json::to_string(&media_ref).expect("serialization should succeed");
        let deserialized: MediaRef =
            serde_json::from_str(&json).expect("deserialization should succeed");

        assert_eq!(deserialized.key, media_ref.key);
        assert_eq!(deserialized.hash, media_ref.hash);
    }

    #[test]
    fn display_meta_survives_parse_and_slim() {
        let mut v = serde_json::to_value(MediaRef::from_upload_result(&make_test_upload_result())).unwrap();
        v["duration_ms"] = serde_json::json!(4200);
        v["waveform"] = serde_json::json!("AAEC");
        let mut parsed: MediaRef = serde_json::from_value(v).unwrap();
        parsed.chunks = None;
        parsed.blob_urls = Vec::new();
        let out = serde_json::to_value(&parsed).unwrap();
        assert_eq!(out["duration_ms"], 4200);
        assert_eq!(out["waveform"], "AAEC");
        assert!(out.get("display").is_none());
    }

    #[test]
    fn test_invalid_base64_key() {
        let media_ref = MediaRef {
            key: "not-valid-base64!!!".into(),
            hash: hex::encode([0u8; 32]),
            blob_urls: vec![],
            original_size: 0,
            media_type: "file".into(),
            filename: "test".into(),
            thumbnail_key: None,
            thumbnail_hash: None,
            thumbnail_urls: None,
            thumbnail_size: None,
            streaming: false,
            chunks: None,
            poster_b64: None,
            local_path: None,
            manifest_url: None,
            manifest_hash: None,
            format_version: None,
            pending: false,
            tor: false,
            display: Default::default(),
        };

        let result = media_ref.to_upload_result();
        assert!(result.is_err());
    }

    #[test]
    fn test_invalid_media_type() {
        let media_ref = MediaRef {
            key: base64::Engine::encode(&base64::engine::general_purpose::STANDARD, [0u8; 32]),
            hash: hex::encode([0u8; 32]),
            blob_urls: vec![],
            original_size: 0,
            media_type: "unknown".into(),
            filename: "test".into(),
            thumbnail_key: None,
            thumbnail_hash: None,
            thumbnail_urls: None,
            thumbnail_size: None,
            streaming: false,
            chunks: None,
            poster_b64: None,
            local_path: None,
            manifest_url: None,
            manifest_hash: None,
            format_version: None,
            pending: false,
            tor: false,
            display: Default::default(),
        };

        let result = media_ref.to_upload_result();
        assert!(matches!(result, Err(MediaError::InvalidMediaType(_))));
    }

    #[test]
    fn test_streaming_media_ref_roundtrip() {
        let upload_result = UploadResult {
            media_key: [0x42; 32],
            media_hash: [0xAB; 32],
            blob_urls: vec![],
            original_size: 500_000,
            media_type: MediaType::Video,
            filename: "clip.mp4".into(),
            thumbnail_key: None,
            thumbnail_hash: None,
            thumbnail_urls: None,
            thumbnail_size: None,
            stream_chunks: Some(vec![
                // A chunk lives on SEVERAL mirrors: replication replaced the
                // single `url` field with a `locations` list. The tests below
                // predate that change, no longer compile and have not run since.
                StreamChunkUploadInfo {
                    locations: vec![ChunkLocation {
                        relay: "https://relay.example.com".into(),
                        url: "https://relay.example.com/chunk0".into(),
                    }],
                    ciphertext_size: 262160,
                    original_size: 262144,
                    hash_hex: hex::encode([0x11; 32]),
                    transport_object_id: None,
                },
                StreamChunkUploadInfo {
                    locations: vec![ChunkLocation {
                        relay: "https://relay.example.com".into(),
                        url: "https://relay.example.com/chunk1".into(),
                    }],
                    ciphertext_size: 237880,
                    original_size: 237856,
                    hash_hex: hex::encode([0x22; 32]),
                    transport_object_id: None,
                },
            ]),
        };

        let media_ref = MediaRef::from_upload_result(&upload_result);
        assert!(media_ref.streaming);
        assert!(media_ref.chunks.is_some());
        let chunks = media_ref.chunks.as_ref().unwrap();
        assert_eq!(chunks.len(), 2);
        assert_eq!(chunks[0].locations[0].url, "https://relay.example.com/chunk0");

        // JSON serialization preserves streaming fields
        let json = serde_json::to_string(&media_ref).expect("serialize should work");
        assert!(json.contains("\"streaming\":true"));
        assert!(json.contains("\"chunks\""));

        // Deserialization roundtrip
        let deserialized: MediaRef = serde_json::from_str(&json).expect("deserialize should work");
        assert!(deserialized.streaming);
        assert_eq!(deserialized.chunks.as_ref().unwrap().len(), 2);

        // to_upload_result preserves stream_chunks
        let restored = deserialized.to_upload_result().expect("roundtrip should work");
        assert!(restored.stream_chunks.is_some());
        assert_eq!(restored.stream_chunks.as_ref().unwrap().len(), 2);
    }

    #[test]
    fn test_legacy_media_ref_deserialization() {
        // A legacy MediaRef JSON (no streaming, no chunks) must deserialize
        // with streaming=false and chunks=None — backward compatibility.
        let legacy_json = r#"{
            "key": "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=",
            "hash": "abababababababababababababababababababababababababababababababab",
            "blob_urls": ["https://relay.example.com/blob1"],
            "original_size": 1024,
            "media_type": "image",
            "filename": "photo.jpg",
            "thumbnail_key": null,
            "thumbnail_hash": null,
            "thumbnail_urls": null,
            "thumbnail_size": null
        }"#;
        let media_ref: MediaRef = serde_json::from_str(legacy_json).expect("legacy JSON should parse");
        assert!(!media_ref.streaming);
        assert!(media_ref.chunks.is_none());
        assert!(!media_ref.pending);
    }
}

#[cfg(test)]
mod t2_identity {
    use super::*;

    const H: &str = "a01e2b4d620777e41a774c078b3577ce90b5faf88e8f0686f63193aa1083fc3e";

    #[test]
    fn t2_parse_accepts_canonical_and_normalizes_case() {
        assert_eq!(TransportObjectId::parse(H).unwrap().as_hex(), H);
        // Letter case does not change the SHA-256 itself: accept any case, normalize to canonical.
        assert_eq!(TransportObjectId::parse(&H.to_uppercase()).unwrap().as_hex(), H);
    }

    #[test]
    fn t2_parse_rejects_everything_else() {
        for bad in ["", "zz", &H[..63], &format!("{H}f"), &format!("{}g", &H[..63])] {
            assert!(TransportObjectId::parse(bad).is_err(), "принято: {bad}");
        }
    }

    #[test]
    fn t2_identity_of_bytes_is_sha256_of_exact_body() {
        use sha2::Digest;
        let body = b"alpha transport object";
        let expect = hex::encode(sha2::Sha256::digest(body));
        assert_eq!(TransportObjectId::of_bytes(body).as_hex(), expect);
    }

    #[test]
    fn t2_serialize_is_always_canonical_lowercase() {
        let id = TransportObjectId::parse(&H.to_uppercase()).unwrap();
        assert_eq!(serde_json::to_string(&id).unwrap(), format!("\"{H}\""));
    }

    /// No field — an old message; reads normally.
    #[test]
    fn t2_missing_identity_is_legacy_not_error() {
        let json = r#"{"locations":[],"ciphertext_size":1,"original_size":1,"hash":"ab"}"#;
        let c: StreamChunkInfo = serde_json::from_str(json).expect("legacy ref обязан читаться");
        assert!(c.transport_object_id.is_none());
    }

    /// The field IS present but broken — a protocol error, not a silent address fallback.
    #[test]
    fn t2_present_but_invalid_identity_is_protocol_error() {
        let json = r#"{"locations":[],"ciphertext_size":1,"original_size":1,"hash":"ab","transport_object_id":"nothex"}"#;
        assert!(serde_json::from_str::<StreamChunkInfo>(json).is_err());
    }

    /// Golden: a new ref with an explicit identity — exact bytes.
    #[test]
    fn t2_new_golden_ref_with_explicit_identity() {
        let c = StreamChunkInfo {
            locations: vec![ChunkLocation { relay: "https://s".into(), url: "https://s/x".into() }],
            ciphertext_size: 262160,
            original_size: 262144,
            hash: "aa".into(),
            transport_object_id: Some(TransportObjectId::parse(H).unwrap()),
        };
        let json = serde_json::to_string(&c).unwrap();
        assert!(json.contains(&format!("\"transport_object_id\":\"{H}\"")), "{json}");
        let back: StreamChunkInfo = serde_json::from_str(&json).unwrap();
        assert_eq!(back.transport_object_id.unwrap().as_hex(), H);
    }
}
