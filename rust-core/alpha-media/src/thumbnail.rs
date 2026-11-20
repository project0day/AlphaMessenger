use serde::{Deserialize, Serialize};
use tracing::debug;

use crate::error::{MediaError, MediaResult};

/// Media type classification for thumbnail generation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MediaType {
    Image,
    Video,
    Audio,
    File,
}

impl std::fmt::Display for MediaType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MediaType::Image => write!(f, "image"),
            MediaType::Video => write!(f, "video"),
            MediaType::Audio => write!(f, "audio"),
            MediaType::File => write!(f, "file"),
        }
    }
}

impl MediaType {
    /// Parse a media type from a MIME type string.
    pub fn from_mime(mime: &str) -> Self {
        if mime.starts_with("image/") {
            MediaType::Image
        } else if mime.starts_with("video/") {
            MediaType::Video
        } else if mime.starts_with("audio/") {
            MediaType::Audio
        } else {
            MediaType::File
        }
    }
}

/// A generated thumbnail with dimensions and media type.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Thumbnail {
    /// Thumbnail image data (JPEG encoded).
    pub data: Vec<u8>,
    /// Width of the thumbnail in pixels.
    pub width: u32,
    /// Height of the thumbnail in pixels.
    pub height: u32,
    /// Media type of the source file.
    pub media_type: MediaType,
}

/// Thumbnail generation for various media types.
pub struct ThumbnailGenerator;

impl ThumbnailGenerator {
    /// Generate a thumbnail for the given media data.
    ///
    /// - **Images**: Resized to fit within `max_size` (width or height) while
    ///   maintaining aspect ratio. Output as JPEG.
    /// - **Video**: First frame extraction — currently a stub (TODO for
    ///   platform-specific implementation using ffmpeg or similar).
    /// - **Audio / File**: Returns `MediaError::ThumbnailFailed` since no
    ///   visual thumbnail can be generated.
    pub fn generate(
        data: &[u8],
        media_type: &MediaType,
        max_size: u32,
    ) -> MediaResult<Thumbnail> {
        match media_type {
            MediaType::Image => Self::generate_image_thumbnail(data, max_size),
            MediaType::Video => {
                // TODO: Implement video thumbnail extraction using ffmpeg
                // or a platform-specific video decoding library.
                // For now, return an error indicating this is not yet supported.
                Err(MediaError::ThumbnailFailed(
                    "Video thumbnail extraction is not yet implemented (TODO)".into(),
                ))
            }
            MediaType::Audio | MediaType::File => {
                Err(MediaError::ThumbnailFailed(format!(
                    "Cannot generate thumbnail for {media_type} media type"
                )))
            }
        }
    }

    /// Resize an image to fit within `max_size` and encode as JPEG.
    fn generate_image_thumbnail(data: &[u8], max_size: u32) -> MediaResult<Thumbnail> {
        let img = image::load_from_memory(data)?;

        let (orig_width, orig_height) = (img.width(), img.height());
        debug!(
            orig_width,
            orig_height, max_size, "Generating image thumbnail"
        );

        // Calculate new dimensions maintaining aspect ratio
        let (new_width, new_height) = if orig_width <= max_size && orig_height <= max_size {
            // Image is already smaller than max_size — no resize needed
            (orig_width, orig_height)
        } else {
            let width_ratio = max_size as f64 / orig_width as f64;
            let height_ratio = max_size as f64 / orig_height as f64;
            let scale = width_ratio.min(height_ratio);
            let new_w = (orig_width as f64 * scale).round() as u32;
            let new_h = (orig_height as f64 * scale).round() as u32;
            // Ensure at least 1px in each dimension
            (new_w.max(1), new_h.max(1))
        };

        let resized = img.resize(new_width, new_height, image::imageops::FilterType::Lanczos3);

        // `resize` preserves aspect ratio, so the ACTUAL dimensions can differ
        // from the requested (new_width, new_height) — e.g. a non-square image
        // fit into 320×320 comes out 320×213. Encoding with the requested dims
        // mismatches the pixel buffer length and panics ("invalid buffer
        // length: expected … got …"). Always use the resized image's real dims.
        let actual_width = resized.width();
        let actual_height = resized.height();

        let mut jpeg_data = Vec::new();
        let mut encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg_data, 80);
        encoder.encode(
            resized.as_bytes(),
            actual_width,
            actual_height,
            resized.color().into(),
        )?;

        debug!(
            actual_width,
            actual_height,
            jpeg_size = jpeg_data.len(),
            "Image thumbnail generated"
        );

        Ok(Thumbnail {
            data: jpeg_data,
            width: actual_width,
            height: actual_height,
            media_type: MediaType::Image,
        })
    }
}

/// The same picture as a smaller JPEG whose base64 fits `limit`, at the largest size that
/// does (the smallest tried if none does); `None` if `b64` is not an image.
pub fn fit_poster_b64(b64: &str, limit: usize) -> Option<String> {
    use base64::Engine;
    let codec = base64::engine::general_purpose::STANDARD;
    if b64.len() <= limit {
        return Some(b64.to_string());
    }
    let img = image::load_from_memory(&codec.decode(b64).ok()?).ok()?;
    let edge = img.width().max(img.height());
    let mut smallest = None;
    let mut last_edge = 0;
    for max in [256u32, 200, 160, 128, 96, 72, 56, 40] {
        let max = max.min(edge);
        if max == last_edge {
            continue;
        }
        last_edge = max;
        let small = img.thumbnail(max, max).to_rgb8();
        for quality in [60u8, 40] {
            let mut jpeg = Vec::new();
            image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, quality)
                .encode(small.as_raw(), small.width(), small.height(), image::ExtendedColorType::Rgb8)
                .ok()?;
            let out = codec.encode(&jpeg);
            if out.len() <= limit {
                return Some(out);
            }
            smallest = Some(out);
        }
    }
    smallest
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{DynamicImage, ImageFormat};

    #[test]
    fn test_media_type_from_mime() {
        assert_eq!(MediaType::from_mime("image/png"), MediaType::Image);
        assert_eq!(MediaType::from_mime("image/jpeg"), MediaType::Image);
        assert_eq!(MediaType::from_mime("video/mp4"), MediaType::Video);
        assert_eq!(MediaType::from_mime("audio/ogg"), MediaType::Audio);
        assert_eq!(MediaType::from_mime("application/pdf"), MediaType::File);
    }

    #[test]
    fn test_media_type_display() {
        assert_eq!(format!("{}", MediaType::Image), "image");
        assert_eq!(format!("{}", MediaType::Video), "video");
        assert_eq!(format!("{}", MediaType::Audio), "audio");
        assert_eq!(format!("{}", MediaType::File), "file");
    }

    #[test]
    fn test_thumbnail_audio_fails() {
        let result = ThumbnailGenerator::generate(b"fake audio", &MediaType::Audio, 200);
        assert!(result.is_err());
    }

    #[test]
    fn test_thumbnail_video_fails() {
        let result = ThumbnailGenerator::generate(b"fake video", &MediaType::Video, 200);
        assert!(result.is_err());
    }

    #[test]
    fn test_thumbnail_file_fails() {
        let result = ThumbnailGenerator::generate(b"fake file", &MediaType::File, 200);
        assert!(result.is_err());
    }

    #[test]
    fn test_generate_image_thumbnail() {
        // Create a small test image (100x100 red square)
        let img = DynamicImage::new_rgb8(100, 100);
        let mut png_data = Vec::new();
        img.write_to(&mut std::io::Cursor::new(&mut png_data), ImageFormat::Png)
            .expect("write should succeed");

        let thumbnail =
            ThumbnailGenerator::generate(&png_data, &MediaType::Image, 50).expect("thumbnail should succeed");

        assert!(!thumbnail.data.is_empty());
        assert!(thumbnail.width <= 50);
        assert!(thumbnail.height <= 50);
        assert_eq!(thumbnail.media_type, MediaType::Image);
    }

    #[test]
    fn test_generate_image_thumbnail_small_image() {
        // Create a very small test image (10x10) — should not be upscaled
        let img = DynamicImage::new_rgb8(10, 10);
        let mut png_data = Vec::new();
        img.write_to(&mut std::io::Cursor::new(&mut png_data), ImageFormat::Png)
            .expect("write should succeed");

        let thumbnail =
            ThumbnailGenerator::generate(&png_data, &MediaType::Image, 200).expect("thumbnail should succeed");

        assert_eq!(thumbnail.width, 10);
        assert_eq!(thumbnail.height, 10);
    }
}
