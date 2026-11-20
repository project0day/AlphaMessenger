//! # AlphaMessenger Media Crate
//!
//! Attachment/media pipeline with end-to-end encryption for AlphaMessenger.
//!
//! ## Architecture
//!
//! - **Encryption**: XChaCha20-Poly1305 (stream cipher, no block alignment issues)
//! - **Integrity**: BLAKE3 hash (fast, verified on decrypt)
//! - **Chunking**: Split large files into 512KB chunks for upload
//! - **Storage**: Blossom/file relay for blob storage (decentralized Nostr-adjacent)
//! - **Pipeline**: encrypt → chunk → upload → ref in MLS message; download → verify BLAKE3 → decrypt
//! - **Thumbnails**: Generated locally, encrypted separately, smaller chunks
//!
//! ## Usage
//!
//! ```rust,ignore
//! use alpha_media::{MediaPipeline, BlossomClient, MediaSigner, MediaType};
//!
//! let blossom = BlossomClient::new("https://blossom.example.com");
//! let pipeline = MediaPipeline::new(blossom);
//! let signer = MediaSigner::generate();
//!
//! // Upload
//! let result = pipeline.upload(&file_data, "photo.jpg", MediaType::Image, &signer).await?;
//!
//! // Create a MediaRef to include in an MLS message
//! let media_ref = alpha_media::MediaRef::from_upload_result(&result);
//!
//! // Download
//! let plaintext = pipeline.download(
//!     &result.media_key,
//!     &result.media_hash,
//!     &result.blob_urls,
//!     result.original_size,
//! ).await?;
//! ```

pub mod blob_transport;
pub mod blossom;
pub mod chunker;
pub mod crypto;
pub mod egress;
pub mod error;
pub mod faststart;
pub mod growing;
pub mod limits;
pub mod manifest_blob;
mod link;
pub mod media_ref;
pub mod pipeline;
pub mod resume;
#[cfg(test)]
mod resume_e2e;
#[cfg(test)]
mod slow_link_e2e;
pub mod thumbnail;
pub mod vault;
pub mod wire_budget;

// Re-export primary types for convenience

// --- Network proxy (Tor / Orbot) ---

static HTTP_PROXY: std::sync::RwLock<Option<std::net::SocketAddr>> = std::sync::RwLock::new(None);

/// Route HTTP clients created after this call through a SOCKS5 proxy
/// (DNS resolved by the proxy). `None` = direct.
pub fn set_http_proxy(addr: Option<std::net::SocketAddr>) {
    if let Ok(mut p) = HTTP_PROXY.write() {
        *p = addr;
    }
}

/// Apply the configured proxy, if any, to a client builder.
/// Whether media traffic goes through the SOCKS proxy (names resolve at the exit).
pub fn http_proxy_active() -> bool {
    HTTP_PROXY.read().ok().and_then(|p| *p).is_some()
}

pub fn with_http_proxy(builder: reqwest::ClientBuilder) -> reqwest::ClientBuilder {
    with_socks_proxy(builder, HTTP_PROXY.read().ok().and_then(|p| *p))
}

/// Route a client builder through the SOCKS proxy at [addr] (`None` = direct).
pub fn with_socks_proxy(builder: reqwest::ClientBuilder, addr: Option<std::net::SocketAddr>) -> reqwest::ClientBuilder {
    match addr {
        Some(addr) => match reqwest::Proxy::all(format!("socks5h://{addr}")) {
            Ok(proxy) => builder.proxy(proxy),
            // Never direct when a proxy is set: a dead local port refuses instead.
            Err(e) => {
                tracing::error!("proxy {addr} not applicable: {e}");
                builder.proxy(reqwest::Proxy::all("socks5h://127.0.0.1:9").expect("static proxy url"))
            }
        },
        None => builder,
    }
}

pub use blossom::{own_servers, set_own_servers, set_map_down, BlobDescriptor, BlossomClient, MediaSigner, DEFAULT_BLOSSOM_SERVERS};
pub use blob_transport::{
    active_codec, unwrap_blob, BlobEnvelope, BlobEnvelopeCodec, BlobMetrics, BlobMetricsSnapshot,
    JsonCodec, BLOB_ENVELOPE_VERSION,
};
pub use chunker::{Chunk, EncryptedChunker, CHUNK_SIZE};
pub use crypto::{blake3_hash, generate_key, EncryptedMedia, EncryptedStreamChunk, MediaCrypto, StreamCrypto, STREAM_CHUNK_SIZE};
pub use error::{MediaError, MediaResult};
pub use media_ref::{MediaRef, StreamChunkInfo, StreamChunkUploadInfo, UploadResult};
pub use link::network_changed;
pub use pipeline::MediaPipeline;
pub use resume::{ChunkCheckpoint, ConfirmedChunk};
pub use thumbnail::{MediaType, Thumbnail, ThumbnailGenerator};
