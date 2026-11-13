//! Error types for media operations.

use thiserror::Error;

/// Error types for media operations.
#[derive(Debug, Error)]
pub enum MediaError {
    /// Encryption failed.
    #[error("Encryption failed: {0}")]
    EncryptionFailed(String),

    /// Decryption failed.
    #[error("Decryption failed: {0}")]
    DecryptionFailed(String),

    /// Integrity check failed — BLAKE3 hash mismatch.
    #[error("Integrity check failed: expected {expected}, got {actual}")]
    IntegrityCheckFailed {
        expected: String,
        actual: String,
    },

    /// Chunking error.
    #[error("Chunking error: {0}")]
    ChunkingError(String),

    /// Upload to Blossom relay failed.
    #[error("Upload failed: {0}")]
    UploadFailed(String),

    /// Download from Blossom relay failed.
    #[error("Download failed: {0}")]
    DownloadFailed(String),

    /// Delete from Blossom relay failed.
    #[error("Delete failed: {0}")]
    DeleteFailed(String),

    /// Serialization/deserialization error.
    #[error("Serialization error: {0}")]
    SerializationError(String),

    /// Invalid key size.
    #[error("Invalid key size: expected {expected} bytes, got {actual}")]
    InvalidKeySize { expected: usize, actual: usize },

    /// Invalid media type.
    #[error("Invalid media type: {0}")]
    InvalidMediaType(String),

    /// Thumbnail generation error.
    #[error("Thumbnail generation failed: {0}")]
    ThumbnailFailed(String),

    /// Signing error for Blossom auth.
    #[error("Signing error: {0}")]
    SigningError(String),

    /// HTTP request error.
    #[error("HTTP error: {0}")]
    HttpError(String),

    /// Pipeline error — wraps multiple sub-errors.
    #[error("Pipeline error: {0}")]
    PipelineError(String),

    /// Missing thumbnail data when expected.
    #[error("Missing thumbnail data")]
    MissingThumbnail,

    /// No chunk URLs provided.
    #[error("No chunk URLs provided")]
    NoChunkUrls,

    /// The user cancelled the transfer. Checked between chunks, so an upload
    /// stops within one chunk of the ✕ instead of running to completion inside a
    /// `block_on` that no Kotlin coroutine cancellation can reach.
    #[error("Cancelled by user")]
    Cancelled,

    /// Streaming chunk not found.
    #[error("Streaming chunk {0} not found in manifest")]
    StreamChunkNotFound(u32),

    /// Streaming manifest is invalid or incomplete.
    #[error("Invalid stream manifest: {0}")]
    InvalidStreamManifest(String),

    /// I/O error.
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
}

impl From<serde_json::Error> for MediaError {
    fn from(err: serde_json::Error) -> Self {
        MediaError::SerializationError(err.to_string())
    }
}

impl From<reqwest::Error> for MediaError {
    fn from(err: reqwest::Error) -> Self {
        MediaError::HttpError(err.to_string())
    }
}

impl From<chacha20poly1305::aead::Error> for MediaError {
    fn from(err: chacha20poly1305::aead::Error) -> Self {
        MediaError::DecryptionFailed(err.to_string())
    }
}

impl From<image::ImageError> for MediaError {
    fn from(err: image::ImageError) -> Self {
        MediaError::ThumbnailFailed(err.to_string())
    }
}

/// Convenience type alias for media results.
pub type MediaResult<T> = Result<T, MediaError>;
